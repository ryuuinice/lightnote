package sync

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"sync"
	"testing"

	"github.com/google/uuid"

	"lightnote/server/internal/testutil"
)

func noteChange(id, noteID, op string, base, version int64, title string) Change {
	payload, err := json.Marshal(map[string]any{"title": title, "note_type": "text", "is_deleted": false})
	if err != nil {
		panic(err)
	}
	return Change{
		ChangeID:    id,
		EntityType:  "note",
		EntityID:    noteID,
		Operation:   op,
		BaseVersion: base,
		Version:     version,
		Payload:     payload,
	}
}

func TestPushApplied(t *testing.T) {
	store := testutil.NewStore(t)
	svc := NewPushService(store)
	res, err := svc.Push(context.Background(), "dev-a", []Change{noteChange("c1", "n1", "CREATE", 0, 1, "hello")})
	if err != nil {
		t.Fatalf("push: %v", err)
	}
	if len(res) != 1 || res[0].Status != StatusApplied {
		t.Fatalf("results = %+v", res)
	}
	if res[0].ServerSequence == nil || *res[0].ServerSequence != 1 {
		t.Errorf("first sequence = %v, want 1", res[0].ServerSequence)
	}
	var title string
	var version int64
	if err := store.Read().QueryRowContext(context.Background(),
		"SELECT title, version FROM notes WHERE note_id = 'n1'").Scan(&title, &version); err != nil {
		t.Fatalf("load note: %v", err)
	}
	if title != "hello" || version != 1 {
		t.Errorf("note = (%q, %d), want (hello, 1)", title, version)
	}
	var origin string
	if err := store.Read().QueryRowContext(context.Background(),
		"SELECT origin_device_id FROM entity_changes WHERE change_id = 'c1'").Scan(&origin); err != nil {
		t.Fatal(err)
	}
	if origin != "dev-a" {
		t.Errorf("origin_device_id = %q, want dev-a", origin)
	}
}

func TestPushIdempotent(t *testing.T) {
	store := testutil.NewStore(t)
	svc := NewPushService(store)
	ctx := context.Background()
	ch := noteChange("c1", "n1", "CREATE", 0, 1, "hello")
	if _, err := svc.Push(ctx, "dev-a", []Change{ch}); err != nil {
		t.Fatalf("first push: %v", err)
	}
	res, err := svc.Push(ctx, "dev-a", []Change{ch})
	if err != nil {
		t.Fatalf("second push: %v", err)
	}
	if res[0].Status != StatusAlreadyApplied {
		t.Errorf("status = %s, want ALREADY_APPLIED", res[0].Status)
	}
	if res[0].ServerSequence == nil || *res[0].ServerSequence != 1 {
		t.Errorf("sequence = %v, want 1", res[0].ServerSequence)
	}
	var version int64
	var count int
	if err := store.Read().QueryRowContext(ctx, "SELECT version FROM notes WHERE note_id = 'n1'").Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 1 {
		t.Errorf("entity version = %d, want 1 (changed only once)", version)
	}
	if err := store.Read().QueryRowContext(ctx, "SELECT COUNT(*) FROM entity_changes").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Errorf("entity_changes count = %d, want 1", count)
	}
}

func TestPushConflictCopy(t *testing.T) {
	store := testutil.NewStore(t)
	svc := NewPushService(store)
	ctx := context.Background()
	res1, err := svc.Push(ctx, "dev-a", []Change{noteChange("c1", "n1", "CREATE", 0, 1, "first")})
	if err != nil {
		t.Fatalf("first push: %v", err)
	}
	res2, err := svc.Push(ctx, "dev-b", []Change{noteChange("c2", "n1", "CREATE", 0, 1, "second")})
	if err != nil {
		t.Fatalf("conflicting push: %v", err)
	}
	if res2[0].Status != StatusConflict {
		t.Fatalf("status = %s, want CONFLICT", res2[0].Status)
	}
	if res2[0].ServerSequence == nil || *res2[0].ServerSequence <= *res1[0].ServerSequence {
		t.Errorf("conflict copy sequence = %v, want > %v", res2[0].ServerSequence, res1[0].ServerSequence)
	}
	var title string
	var version int64
	if err := store.Read().QueryRowContext(ctx, "SELECT title, version FROM notes WHERE note_id = 'n1'").Scan(&title, &version); err != nil {
		t.Fatal(err)
	}
	if title != "first" || version != 1 {
		t.Errorf("original note = (%q, %d), want (first, 1) preserved", title, version)
	}
	var copyID, copyTitle, conflictOf string
	if err := store.Read().QueryRowContext(ctx,
		"SELECT note_id, title, conflict_of_note_id FROM notes WHERE note_id != 'n1'").Scan(&copyID, &copyTitle, &conflictOf); err != nil {
		t.Fatalf("conflict copy missing: %v", err)
	}
	if copyTitle != "second（冲突副本）" {
		t.Errorf("copy title = %q, want 冲突副本 suffix", copyTitle)
	}
	if conflictOf != "n1" {
		t.Errorf("conflict_of_note_id = %q, want n1", conflictOf)
	}
	var op string
	if err := store.Read().QueryRowContext(ctx,
		"SELECT operation FROM entity_changes WHERE entity_id = ?", copyID).Scan(&op); err != nil {
		t.Fatal(err)
	}
	if op != "CREATE" {
		t.Errorf("copy change op = %s, want CREATE", op)
	}
	var total int
	if err := store.Read().QueryRowContext(ctx, "SELECT COUNT(*) FROM entity_changes").Scan(&total); err != nil {
		t.Fatal(err)
	}
	if total != 2 {
		t.Errorf("entity_changes count = %d, want 2", total)
	}
}

func TestPushInvalid(t *testing.T) {
	store := testutil.NewStore(t)
	svc := NewPushService(store)
	ctx := context.Background()
	cases := []Change{
		{ChangeID: "", EntityType: "note", EntityID: "n", Operation: "CREATE", BaseVersion: 0, Version: 1, Payload: json.RawMessage(`{}`)},
		{ChangeID: "a", EntityType: "alien", EntityID: "n", Operation: "CREATE", BaseVersion: 0, Version: 1, Payload: json.RawMessage(`{}`)},
		{ChangeID: "b", EntityType: "note", EntityID: "n", Operation: "MOVE", BaseVersion: 0, Version: 1, Payload: json.RawMessage(`{}`)},
		{ChangeID: "c", EntityType: "note", EntityID: "n", Operation: "CREATE", BaseVersion: 5, Version: 2, Payload: json.RawMessage(`{}`)},
		{ChangeID: "d", EntityType: "note", EntityID: "n", Operation: "CREATE", BaseVersion: 0, Version: 1, Payload: json.RawMessage(`not json`)},
		{ChangeID: "e", EntityType: "branch", EntityID: "b", Operation: "CREATE", BaseVersion: 0, Version: 1, Payload: json.RawMessage(`{"child_note_id":"x"}`)},
	}
	res, err := svc.Push(ctx, "dev-a", cases)
	if err != nil {
		t.Fatalf("push: %v", err)
	}
	for _, r := range res {
		if r.Status != StatusInvalid {
			t.Errorf("change %s status = %s, want INVALID", r.ChangeID, r.Status)
		}
	}
	var count int
	if err := store.Read().QueryRowContext(ctx, "SELECT COUNT(*) FROM entity_changes").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Errorf("entity_changes count = %d, want 0", count)
	}
}

func TestSequenceMonotonicConcurrent(t *testing.T) {
	store := testutil.NewStore(t)
	svc := NewPushService(store)
	ctx := context.Background()
	const n = 100
	var wg sync.WaitGroup
	seqs := make([]int64, n)
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			ch := noteChange("c-"+uuid.NewString(), "n-"+uuid.NewString(), "CREATE", 0, 1, "title")
			res, err := svc.Push(ctx, "dev-a", []Change{ch})
			if err != nil {
				errs[i] = err
				return
			}
			if res[0].Status != StatusApplied {
				errs[i] = &pushError{res[0].Status}
				return
			}
			seqs[i] = *res[0].ServerSequence
		}(i)
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Fatalf("goroutine %d: %v", i, err)
		}
	}
	seen := map[int64]bool{}
	for _, s := range seqs {
		if s <= 0 {
			t.Fatalf("sequence %d <= 0", s)
		}
		if seen[s] {
			t.Fatalf("duplicate sequence %d", s)
		}
		seen[s] = true
	}
	sorted := append([]int64(nil), seqs...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	for i := 1; i < len(sorted); i++ {
		if sorted[i] <= sorted[i-1] {
			t.Fatalf("sequence not strictly increasing: %v", sorted)
		}
	}
	pull, err := NewPuller(store).Pull(ctx, 0, 1000)
	if err != nil {
		t.Fatalf("pull: %v", err)
	}
	if len(pull.Changes) != n {
		t.Errorf("pull returned %d changes, want %d", len(pull.Changes), n)
	}
	if pull.HasMore {
		t.Error("has_more = true, want false")
	}
	if pull.NextSequence != sorted[n-1] {
		t.Errorf("next_sequence = %d, want %d", pull.NextSequence, sorted[n-1])
	}
}

type pushError struct{ status ResultStatus }

func (e *pushError) Error() string { return string(e.status) }

func TestSequenceRollbackNotReused(t *testing.T) {
	store := testutil.NewStore(t)
	ctx := context.Background()
	svc := NewPushService(store)

	// Commit change A, establishing the committed sequence watermark.
	if _, err := svc.Push(ctx, "dev-a", []Change{noteChange("c1", "n1", "CREATE", 0, 1, "a")}); err != nil {
		t.Fatalf("push A: %v", err)
	}
	var committedA int64
	if err := store.Read().QueryRowContext(ctx,
		"SELECT server_sequence FROM entity_changes WHERE change_id = 'c1'").Scan(&committedA); err != nil {
		t.Fatal(err)
	}

	// Simulate a failed transaction: it allocates a sequence then rolls back
	// (exactly the failure window of a naive same-transaction allocation).
	tx, err := store.Write().BeginTx(ctx, nil)
	if err != nil {
		t.Fatalf("begin: %v", err)
	}
	var rolledBack int64
	if err := tx.QueryRowContext(ctx,
		"INSERT INTO sync_sequence DEFAULT VALUES RETURNING seq").Scan(&rolledBack); err != nil {
		t.Fatalf("in-tx allocation: %v", err)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatalf("rollback: %v", err)
	}

	// Push B: its committed sequence must be strictly greater than A's.
	// The rolled-back allocation must never duplicate a committed value or
	// regress the committed watermark.
	res, err := svc.Push(ctx, "dev-a", []Change{noteChange("c2", "n2", "CREATE", 0, 1, "b")})
	if err != nil {
		t.Fatalf("push B: %v", err)
	}
	committedB := *res[0].ServerSequence
	if committedB <= committedA {
		t.Fatalf("committed sequence %d <= previous committed %d (watermark regressed)", committedB, committedA)
	}
	if committedB < rolledBack {
		t.Fatalf("committed sequence %d below rolled-back allocation %d", committedB, rolledBack)
	}

	// Cursor semantics: pulling after A must return exactly B (and never
	// re-deliver A or skip a committed change).
	pull, err := NewPuller(store).Pull(ctx, committedA, 10)
	if err != nil {
		t.Fatalf("pull: %v", err)
	}
	if len(pull.Changes) != 1 || pull.Changes[0].ChangeID != "c2" {
		t.Fatalf("pull after A = %+v, want exactly change c2", pull.Changes)
	}
	if pull.Changes[0].ServerSequence != committedB {
		t.Errorf("pulled sequence = %d, want %d", pull.Changes[0].ServerSequence, committedB)
	}

	// All committed sequences are strictly increasing and unique.
	all, err := NewPuller(store).Pull(ctx, 0, 1000)
	if err != nil {
		t.Fatal(err)
	}
	for i, c := range all.Changes {
		if i > 0 && c.ServerSequence <= all.Changes[i-1].ServerSequence {
			t.Fatalf("committed sequences not strictly increasing: %+v", all.Changes)
		}
	}
}

func TestPullPaginationAndGap(t *testing.T) {
	store := testutil.NewStore(t)
	svc := NewPushService(store)
	ctx := context.Background()
	changes := make([]Change, 25)
	for i := range changes {
		changes[i] = noteChange("c-"+uuid.NewString(), "n-"+uuid.NewString(), "CREATE", 0, 1, "t")
	}
	if _, err := svc.Push(ctx, "dev-a", changes); err != nil {
		t.Fatalf("push: %v", err)
	}
	puller := NewPuller(store)
	page1, err := puller.Pull(ctx, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page1.Changes) != 10 || !page1.HasMore {
		t.Fatalf("page1 = %d changes has_more=%v, want 10 true", len(page1.Changes), page1.HasMore)
	}
	page2, err := puller.Pull(ctx, page1.NextSequence, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page2.Changes) != 10 || !page2.HasMore {
		t.Fatalf("page2 = %d changes has_more=%v, want 10 true", len(page2.Changes), page2.HasMore)
	}
	if page2.Changes[0].ServerSequence <= page1.Changes[len(page1.Changes)-1].ServerSequence {
		t.Error("page2 not after page1")
	}
	page3, err := puller.Pull(ctx, page2.NextSequence, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page3.Changes) != 5 || page3.HasMore {
		t.Fatalf("page3 = %d changes has_more=%v, want 5 false", len(page3.Changes), page3.HasMore)
	}
	empty, err := puller.Pull(ctx, page3.NextSequence, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(empty.Changes) != 0 || empty.HasMore {
		t.Error("final page should be empty")
	}
	if empty.NextSequence != page3.NextSequence {
		t.Errorf("empty next_sequence = %d, want %d", empty.NextSequence, page3.NextSequence)
	}
}

func TestDeleteNoteTombstone(t *testing.T) {
	store := testutil.NewStore(t)
	svc := NewPushService(store)
	committer := NewCommitter(store)
	ctx := context.Background()
	if _, err := svc.Push(ctx, "dev-a", []Change{noteChange("c1", "n1", "CREATE", 0, 1, "doomed")}); err != nil {
		t.Fatalf("push: %v", err)
	}
	if err := committer.DeleteNote(ctx, "dev-b", "n1"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	var isDeleted int
	var version int64
	if err := store.Read().QueryRowContext(ctx,
		"SELECT is_deleted, version FROM notes WHERE note_id = 'n1'").Scan(&isDeleted, &version); err != nil {
		t.Fatal(err)
	}
	if isDeleted != 1 {
		t.Errorf("is_deleted = %d, want 1 (tombstone, no physical delete)", isDeleted)
	}
	if version != 2 {
		t.Errorf("version = %d, want 2", version)
	}
	if err := store.Read().QueryRowContext(ctx,
		"SELECT COUNT(*) FROM entity_changes WHERE entity_id = 'n1' AND operation = 'DELETE'").Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 1 {
		t.Errorf("DELETE changes = %d, want 1", version)
	}
	if err := committer.DeleteNote(ctx, "dev-b", "n1"); err != nil {
		t.Fatalf("second delete: %v", err)
	}
	if err := store.Read().QueryRowContext(ctx,
		"SELECT COUNT(*) FROM entity_changes WHERE entity_id = 'n1'").Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 2 {
		t.Errorf("entity_changes after re-delete = %d, want 2 (no extra change)", version)
	}
}

func TestDeleteNoteNotFound(t *testing.T) {
	store := testutil.NewStore(t)
	if err := NewCommitter(store).DeleteNote(context.Background(), "dev-a", "missing"); !errors.Is(err, ErrNoteNotFound) {
		t.Fatalf("error = %v, want ErrNoteNotFound", err)
	}
}

func TestBlobChange(t *testing.T) {
	store := testutil.NewStore(t)
	svc := NewPushService(store)
	payload, err := json.Marshal(map[string]any{"size": 42, "mime_type": "text/markdown"})
	if err != nil {
		t.Fatal(err)
	}
	ch := Change{
		ChangeID: "b1", EntityType: "blob", EntityID: "sha256:abc", Operation: "CREATE",
		BaseVersion: 0, Version: 1, Payload: payload,
	}
	res, err := svc.Push(context.Background(), "dev-a", []Change{ch})
	if err != nil {
		t.Fatalf("push: %v", err)
	}
	if res[0].Status != StatusApplied {
		t.Fatalf("status = %s", res[0].Status)
	}
	var size int64
	if err := store.Read().QueryRowContext(context.Background(),
		"SELECT size FROM blobs WHERE blob_id = 'sha256:abc'").Scan(&size); err != nil {
		t.Fatal(err)
	}
	if size != 42 {
		t.Errorf("size = %d, want 42", size)
	}
}
