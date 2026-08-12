package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"lightnote/server/internal/auth"
	"lightnote/server/internal/blob"
	"lightnote/server/internal/config"
	"lightnote/server/internal/db"
	"lightnote/server/internal/sync"
	"lightnote/server/internal/testutil"
)

type testApp struct {
	handler http.Handler
	store   *db.Store
}

func newTestApp(t *testing.T) *testApp {
	t.Helper()
	store := testutil.NewStore(t)
	a := auth.New(store, "test-secret", time.Hour)
	if _, err := a.EnsureDefaultUser(context.Background(), "admin", "admin123"); err != nil {
		t.Fatalf("ensure user: %v", err)
	}
	bs, err := blob.NewStore(t.TempDir())
	if err != nil {
		t.Fatalf("blob store: %v", err)
	}
	handler := New(&config.Config{}, store, a, sync.NewPushService(store), sync.NewPuller(store), bs).Handler()
	return &testApp{handler: handler, store: store}
}

func (app *testApp) do(t *testing.T, method, path, token string, body any) (int, map[string]any) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal body: %v", err)
		}
		reader = bytes.NewReader(b)
	}
	req := httptest.NewRequest(method, path, reader)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	app.handler.ServeHTTP(rec, req)
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

type loginResp struct {
	AccessToken string `json:"access_token"`
	DeviceID    string `json:"device_id"`
}

func doLogin(t *testing.T, app *testApp, username, password, deviceName string) (int, loginResp) {
	t.Helper()
	status, out := app.do(t, "POST", "/api/v1/auth/login", "",
		map[string]any{"username": username, "password": password, "device_name": deviceName, "device_type": "desktop"})
	b, _ := json.Marshal(out)
	var resp loginResp
	_ = json.Unmarshal(b, &resp)
	return status, resp
}

func loginOK(t *testing.T, app *testApp, deviceName string) (string, string) {
	t.Helper()
	status, out := doLogin(t, app, "admin", "admin123", deviceName)
	if status != http.StatusOK || out.AccessToken == "" {
		t.Fatalf("login failed: status=%d out=%+v", status, out)
	}
	return out.AccessToken, out.DeviceID
}

func noteChange(id, noteID, op string, base, version int64, title string) sync.Change {
	payload, _ := json.Marshal(map[string]any{"title": title, "note_type": "text", "is_deleted": false})
	return sync.Change{
		ChangeID: id, EntityType: "note", EntityID: noteID, Operation: op,
		BaseVersion: base, Version: version, Payload: payload,
	}
}

func TestLoginEndpoints(t *testing.T) {
	app := newTestApp(t)
	status, out := doLogin(t, app, "admin", "admin123", "pc-1")
	if status != http.StatusOK {
		t.Fatalf("login status = %d", status)
	}
	if out.AccessToken == "" || out.DeviceID == "" {
		t.Errorf("missing fields: %+v", out)
	}
	status, _ = doLogin(t, app, "admin", "wrong", "pc-2")
	if status != http.StatusUnauthorized {
		t.Errorf("wrong password status = %d, want 401", status)
	}
	status, _ = doLogin(t, app, "ghost", "admin123", "pc-3")
	if status != http.StatusUnauthorized {
		t.Errorf("unknown user status = %d, want 401", status)
	}
}

func TestHealthzNoAuth(t *testing.T) {
	app := newTestApp(t)
	status, out := app.do(t, "GET", "/api/v1/healthz", "", nil)
	if status != http.StatusOK {
		t.Errorf("healthz status = %d, want 200", status)
	}
	if out["status"] != "ok" {
		t.Errorf("healthz body = %v", out)
	}
}

func TestAuthRequired(t *testing.T) {
	app := newTestApp(t)
	status, out := app.do(t, "GET", "/api/v1/notes", "", nil)
	if status != http.StatusUnauthorized {
		t.Errorf("no token status = %d, want 401", status)
	}
	if out["code"] != "UNAUTHORIZED" {
		t.Errorf("code = %v, want UNAUTHORIZED", out["code"])
	}
	status, _ = app.do(t, "POST", "/api/v1/sync/push", "garbage.token.here", map[string]any{"changes": []any{}})
	if status != http.StatusUnauthorized {
		t.Errorf("garbage token status = %d, want 401", status)
	}
}

func TestForgedDeviceIDRejected(t *testing.T) {
	app := newTestApp(t)
	token, deviceID := loginOK(t, app, "pc-forge")
	ch := noteChange("c-"+uuid.NewString(), "n-"+uuid.NewString(), "CREATE", 0, 1, "x")
	ch.OriginDeviceID = "evil-device"
	status, out := app.do(t, "POST", "/api/v1/sync/push", token,
		map[string]any{"changes": []sync.Change{ch}, "device_id": "evil-device"})
	if status != http.StatusOK {
		t.Fatalf("push status = %d: %v", status, out)
	}
	var origin string
	if err := app.store.Read().QueryRowContext(context.Background(),
		"SELECT origin_device_id FROM entity_changes WHERE change_id = ?", ch.ChangeID).Scan(&origin); err != nil {
		t.Fatal(err)
	}
	if origin != deviceID {
		t.Errorf("origin_device_id = %q, want JWT device_id %q (forged body rejected)", origin, deviceID)
	}
}

func TestRevokedDeviceRejected(t *testing.T) {
	app := newTestApp(t)
	token, deviceID := loginOK(t, app, "pc-revoke")
	if _, err := app.store.Write().ExecContext(context.Background(),
		"UPDATE devices SET revoked_at = ? WHERE device_id = ?", time.Now().UnixMilli(), deviceID); err != nil {
		t.Fatal(err)
	}
	status, out := app.do(t, "POST", "/api/v1/sync/push", token, map[string]any{"changes": []any{}})
	if status != http.StatusForbidden {
		t.Errorf("revoked device status = %d, want 403", status)
	}
	if out["code"] != "DEVICE_REVOKED" {
		t.Errorf("code = %v, want DEVICE_REVOKED", out["code"])
	}
}

func TestPushPullRoundtrip(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-roundtrip")
	ch := noteChange("c-"+uuid.NewString(), "n-round", "CREATE", 0, 1, "hello world")
	status, out := app.do(t, "POST", "/api/v1/sync/push", token, map[string]any{"changes": []sync.Change{ch}})
	if status != http.StatusOK {
		t.Fatalf("push status = %d: %v", status, out)
	}
	results := out["results"].([]any)
	if results[0].(map[string]any)["status"] != "APPLIED" {
		t.Errorf("result = %v", results[0])
	}
	status, out = app.do(t, "GET", "/api/v1/sync/changes?after=0", token, nil)
	if status != http.StatusOK {
		t.Fatalf("pull status = %d", status)
	}
	changes := out["changes"].([]any)
	if len(changes) != 1 {
		t.Fatalf("changes = %d, want 1", len(changes))
	}
	c := changes[0].(map[string]any)
	if c["change_id"] != ch.ChangeID || c["entity_type"] != "note" || c["version"].(float64) != 1 {
		t.Errorf("change = %v", c)
	}
	payload := c["payload"].(map[string]any)
	if payload["title"] != "hello world" {
		t.Errorf("payload title = %v", payload["title"])
	}
	if out["has_more"] != false {
		t.Errorf("has_more = %v", out["has_more"])
	}
}

func TestPullRequiresAfter(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-pull")
	status, _ := app.do(t, "GET", "/api/v1/sync/changes", token, nil)
	if status != http.StatusBadRequest {
		t.Errorf("missing after status = %d, want 400", status)
	}
}

func TestPushTooLarge(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-big")
	big := strings.Repeat("a", 9<<20)
	payload := `{"title":"` + big + `"}`
	ch := sync.Change{
		ChangeID: "c-big", EntityType: "note", EntityID: "n-big", Operation: "CREATE",
		BaseVersion: 0, Version: 1, Payload: json.RawMessage(payload),
	}
	status, _ := app.do(t, "POST", "/api/v1/sync/push", token, map[string]any{"changes": []sync.Change{ch}})
	if status != http.StatusRequestEntityTooLarge {
		t.Errorf("oversize push status = %d, want 413", status)
	}
}

func TestNoteDeleteTombstone(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-del")
	ch := noteChange("c-"+uuid.NewString(), "n-del", "CREATE", 0, 1, "doomed")
	if status, out := app.do(t, "POST", "/api/v1/sync/push", token, map[string]any{"changes": []sync.Change{ch}}); status != http.StatusOK {
		t.Fatalf("push: %d %v", status, out)
	}
	status, out := app.do(t, "DELETE", "/api/v1/notes/n-del", token, nil)
	if status != http.StatusOK {
		t.Fatalf("delete status = %d: %v", status, out)
	}
	_, out = app.do(t, "GET", "/api/v1/notes", token, nil)
	notes := out["notes"].([]any)
	if len(notes) != 0 {
		t.Errorf("notes after delete = %d, want 0", len(notes))
	}
	_, out = app.do(t, "GET", "/api/v1/notes?include_deleted=true", token, nil)
	notes = out["notes"].([]any)
	if len(notes) != 1 || notes[0].(map[string]any)["is_deleted"] != true {
		t.Errorf("include_deleted notes = %v", notes)
	}
	status, out = app.do(t, "GET", "/api/v1/notes/n-del", token, nil)
	if status != http.StatusOK || out["is_deleted"] != true {
		t.Errorf("get deleted note = %d %v", status, out)
	}
	status, _ = app.do(t, "GET", "/api/v1/notes/n-missing", token, nil)
	if status != http.StatusNotFound {
		t.Errorf("missing note status = %d, want 404", status)
	}
	_, out = app.do(t, "GET", "/api/v1/sync/changes?after=0", token, nil)
	changes := out["changes"].([]any)
	if len(changes) != 2 {
		t.Fatalf("changes = %d, want 2 (CREATE + DELETE)", len(changes))
	}
	if changes[1].(map[string]any)["operation"] != "DELETE" {
		t.Errorf("second change = %v, want DELETE", changes[1])
	}
}

func TestBranchesAndAttributes(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-ba")
	branchPayload, _ := json.Marshal(map[string]any{
		"parent_note_id": "n-parent", "child_note_id": "n-child", "sort_order": 2, "is_deleted": false,
	})
	attrPayload, _ := json.Marshal(map[string]any{
		"note_id": "n-child", "attr_type": "label", "name": "tag1", "value": "v1", "is_inherited": false,
	})
	changes := []sync.Change{
		noteChange("c-note", "n-child", "CREATE", 0, 1, "child note"),
		{ChangeID: "c-branch", EntityType: "branch", EntityID: "b-1", Operation: "CREATE", BaseVersion: 0, Version: 1, Payload: branchPayload},
		{ChangeID: "c-attr", EntityType: "attribute", EntityID: "a-1", Operation: "CREATE", BaseVersion: 0, Version: 1, Payload: attrPayload},
	}
	if status, out := app.do(t, "POST", "/api/v1/sync/push", token, map[string]any{"changes": changes}); status != http.StatusOK {
		t.Fatalf("push: %d %v", status, out)
	}
	status, out := app.do(t, "GET", "/api/v1/branches?parent_note_id=n-parent", token, nil)
	if status != http.StatusOK {
		t.Fatalf("branches status = %d", status)
	}
	branches := out["branches"].([]any)
	if len(branches) != 1 {
		t.Fatalf("branches = %d, want 1", len(branches))
	}
	b := branches[0].(map[string]any)
	if b["branch_id"] != "b-1" || b["sort_order"].(float64) != 2 {
		t.Errorf("branch = %v", b)
	}
	status, _ = app.do(t, "GET", "/api/v1/branches", token, nil)
	if status != http.StatusBadRequest {
		t.Errorf("branches without parent = %d, want 400", status)
	}
	status, out = app.do(t, "GET", "/api/v1/notes/n-child/attributes", token, nil)
	if status != http.StatusOK {
		t.Fatalf("attributes status = %d", status)
	}
	attrs := out["attributes"].([]any)
	if len(attrs) != 1 {
		t.Fatalf("attributes = %d, want 1", len(attrs))
	}
	a := attrs[0].(map[string]any)
	if a["attribute_id"] != "a-1" || a["name"] != "tag1" || a["attr_type"] != "label" {
		t.Errorf("attribute = %v", a)
	}
	status, out = app.do(t, "GET", "/api/v1/notes?parent_note_id=n-parent", token, nil)
	if status != http.StatusOK {
		t.Fatalf("notes by parent status = %d", status)
	}
	notes := out["notes"].([]any)
	if len(notes) != 1 || notes[0].(map[string]any)["note_id"] != "n-child" {
		t.Errorf("child notes = %v", notes)
	}
}
