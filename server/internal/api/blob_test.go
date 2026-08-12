package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

func blobIDOf(content []byte) string {
	h := sha256.Sum256(content)
	return "sha256:" + hex.EncodeToString(h[:])
}

func rawDo(t *testing.T, app *testApp, method, path, token string, ct string, body []byte) (int, []byte) {
	t.Helper()
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	if ct != "" {
		req.Header.Set("Content-Type", ct)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	app.handler.ServeHTTP(rec, req)
	return rec.Code, rec.Body.Bytes()
}

func blobInit(t *testing.T, app *testApp, token, blobID string, size int64, mime string) (int, map[string]any) {
	t.Helper()
	status, out := app.do(t, "POST", "/api/v1/blobs/init", token,
		map[string]any{"blob_id": blobID, "size": size, "mime_type": mime})
	return status, out
}

const chunkSize = 4 << 20

func uploadBlob(t *testing.T, app *testApp, token string, content []byte, mime string) string {
	t.Helper()
	blobID := blobIDOf(content)
	status, out := blobInit(t, app, token, blobID, int64(len(content)), mime)
	if status != http.StatusOK {
		t.Fatalf("init status = %d: %v", status, out)
	}
	if out["status"] != "CREATED" {
		t.Fatalf("init status field = %v, want CREATED", out["status"])
	}
	for i := 0; i*chunkSize < len(content); i++ {
		lo, hi := i*chunkSize, (i+1)*chunkSize
		if hi > len(content) {
			hi = len(content)
		}
		status, _ := rawDo(t, app, "PUT", fmt.Sprintf("/api/v1/blobs/%s/chunks/%d", blobID, i), token, "application/octet-stream", content[lo:hi])
		if status != http.StatusOK {
			t.Fatalf("chunk %d status = %d", i, status)
		}
	}
	status, out = app.do(t, "POST", "/api/v1/blobs/"+blobID+"/complete", token, nil)
	if status != http.StatusOK {
		t.Fatalf("complete status = %d: %v", status, out)
	}
	return blobID
}

func TestBlobFullFlow(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-full")
	content := bytes.Repeat([]byte("hello blob "), 100000)
	blobID := uploadBlob(t, app, token, content, "text/markdown")

	status, body := rawDo(t, app, "GET", "/api/v1/blobs/"+blobID, token, "", nil)
	if status != http.StatusOK {
		t.Fatalf("download status = %d", status)
	}
	if !bytes.Equal(body, content) {
		t.Errorf("downloaded content mismatch: %d vs %d bytes", len(body), len(content))
	}
	var size int64
	var mime, storageType, storagePath string
	if err := app.store.Read().QueryRowContext(context.Background(),
		"SELECT size, COALESCE(mime_type,''), storage_type, storage_path FROM blobs WHERE blob_id = ?", blobID).
		Scan(&size, &mime, &storageType, &storagePath); err != nil {
		t.Fatal(err)
	}
	if size != int64(len(content)) || mime != "text/markdown" || storageType != "file" || storagePath == "" {
		t.Errorf("blobs row = size:%d mime:%q type:%q path:%q", size, mime, storageType, storagePath)
	}
	want := storagePath
	if want != blobID[7:9]+"/"+blobID[9:11]+"/"+blobID[7:] {
		t.Errorf("storage_path = %q, want sharded", storagePath)
	}
}

func TestBlobInitExistsAndSession(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-init")
	content := []byte("exists check")
	blobID := blobIDOf(content)
	status, out := blobInit(t, app, token, blobID, int64(len(content)), "")
	if status != http.StatusOK || out["status"] != "CREATED" {
		t.Fatalf("first init = %d %v", status, out)
	}
	status, out = blobInit(t, app, token, blobID, int64(len(content)), "")
	if status != http.StatusOK || out["status"] != "CREATED" || out["upload_session_id"] == "" {
		t.Fatalf("second init = %d %v", status, out)
	}
	uploadBlob(t, app, token, content, "")
	status, out = blobInit(t, app, token, blobID, int64(len(content)), "")
	if status != http.StatusOK || out["status"] != "EXISTS" {
		t.Fatalf("init after complete = %d %v", status, out)
	}
}

func TestBlobInitInvalid(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-bad")
	cases := []map[string]any{
		{"blob_id": "sha256:xyz", "size": 10},
		{"blob_id": "md5:abcdef", "size": 10},
		{"blob_id": "", "size": 10},
		{"blob_id": blobIDOf([]byte("x")), "size": -1},
	}
	for _, c := range cases {
		status, _ := app.do(t, "POST", "/api/v1/blobs/init", token, c)
		if status != http.StatusBadRequest {
			t.Errorf("init %v status = %d, want 400", c, status)
		}
	}
}

func TestBlobChunkIdempotentDuplicate(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-dup")
	content := bytes.Repeat([]byte("dup"), chunkSize+10)
	blobID := blobIDOf(content)
	blobInit(t, app, token, blobID, int64(len(content)), "")
	for i := 0; i*chunkSize < len(content); i++ {
		lo, hi := i*chunkSize, (i+1)*chunkSize
		if hi > len(content) {
			hi = len(content)
		}
		for r := 0; r < 2; r++ {
			status, _ := rawDo(t, app, "PUT", fmt.Sprintf("/api/v1/blobs/%s/chunks/%d", blobID, i), token, "application/octet-stream", content[lo:hi])
			if status != http.StatusOK {
				t.Fatalf("chunk %d retry %d status = %d", i, r, status)
			}
		}
	}
	status, out := app.do(t, "POST", "/api/v1/blobs/"+blobID+"/complete", token, nil)
	if status != http.StatusOK {
		t.Fatalf("complete = %d %v", status, out)
	}
	_, body := rawDo(t, app, "GET", "/api/v1/blobs/"+blobID, token, "", nil)
	if !bytes.Equal(body, content) {
		t.Error("content mismatch after duplicate chunks")
	}
}

func TestBlobChunkOutOfOrder(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-o3")
	content := bytes.Repeat([]byte("ooo"), chunkSize+1)
	blobID := blobIDOf(content)
	blobInit(t, app, token, blobID, int64(len(content)), "")
	order := []int{3, 1, 2, 0}
	for _, i := range order {
		lo, hi := i*chunkSize, (i+1)*chunkSize
		if hi > len(content) {
			hi = len(content)
		}
		status, _ := rawDo(t, app, "PUT", fmt.Sprintf("/api/v1/blobs/%s/chunks/%d", blobID, i), token, "application/octet-stream", content[lo:hi])
		if status != http.StatusOK {
			t.Fatalf("chunk %d status = %d", i, status)
		}
	}
	status, out := app.do(t, "POST", "/api/v1/blobs/"+blobID+"/complete", token, nil)
	if status != http.StatusOK {
		t.Fatalf("complete = %d %v", status, out)
	}
	_, body := rawDo(t, app, "GET", "/api/v1/blobs/"+blobID, token, "", nil)
	if !bytes.Equal(body, content) {
		t.Error("content mismatch after out-of-order chunks")
	}
}

func TestBlobShaMismatchRejected(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-reject")
	actual := []byte("real content")
	claimed := blobIDOf([]byte("other content"))
	blobInit(t, app, token, claimed, int64(len(actual)), "")
	status, _ := rawDo(t, app, "PUT", "/api/v1/blobs/"+claimed+"/chunks/0", token, "application/octet-stream", actual)
	if status != http.StatusOK {
		t.Fatalf("chunk status = %d", status)
	}
	status, out := app.do(t, "POST", "/api/v1/blobs/"+claimed+"/complete", token, nil)
	if status != http.StatusBadRequest || out["code"] != "REJECT" {
		t.Fatalf("complete = %d %v, want 400 REJECT", status, out)
	}
	status, _ = rawDo(t, app, "GET", "/api/v1/blobs/"+claimed, token, "", nil)
	if status != http.StatusNotFound {
		t.Errorf("download after reject = %d, want 404", status)
	}
	var n int
	_ = app.store.Read().QueryRowContext(context.Background(),
		"SELECT COUNT(*) FROM blobs WHERE blob_id = ?", claimed).Scan(&n)
	if n != 0 {
		t.Errorf("blobs row count = %d, want 0 after reject", n)
	}
	status, out = blobInit(t, app, token, claimed, int64(len(actual)), "")
	if status != http.StatusOK || out["status"] != "CREATED" {
		t.Errorf("re-init after reject = %d %v", status, out)
	}
}

func TestBlobGetNotFound(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-404")
	status, _ := rawDo(t, app, "GET", "/api/v1/blobs/"+blobIDOf([]byte("missing")), token, "", nil)
	if status != http.StatusNotFound {
		t.Errorf("download missing = %d, want 404", status)
	}
}

func TestBlobChunkWithoutInit(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-nosess")
	blobID := blobIDOf([]byte("no session"))
	status, out := app.do(t, "POST", "/api/v1/blobs/"+blobID+"/complete", token, nil)
	if status != http.StatusNotFound || out["code"] != "UPLOAD_SESSION_NOT_FOUND" {
		t.Errorf("complete without init = %d %v", status, out)
	}
	status, _ = rawDo(t, app, "PUT", "/api/v1/blobs/"+blobID+"/chunks/0", token, "application/octet-stream", []byte("x"))
	if status != http.StatusNotFound {
		t.Errorf("chunk without init = %d, want 404", status)
	}
}

func TestBlobCompleteMissingChunk(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-gap")
	content := bytes.Repeat([]byte("gap"), chunkSize+5)
	blobID := blobIDOf(content)
	blobInit(t, app, token, blobID, int64(len(content)), "")
	status, _ := rawDo(t, app, "PUT", "/api/v1/blobs/"+blobID+"/chunks/1", token, "application/octet-stream", content[chunkSize:])
	if status != http.StatusOK {
		t.Fatalf("chunk 1 status = %d", status)
	}
	status, out := app.do(t, "POST", "/api/v1/blobs/"+blobID+"/complete", token, nil)
	if status != http.StatusBadRequest || out["code"] != "INCOMPLETE" {
		t.Errorf("complete with gap = %d %v, want 400 INCOMPLETE", status, out)
	}
}

func TestBlobSizeMismatch(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-size")
	content := []byte("short")
	blobID := blobIDOf(content)
	blobInit(t, app, token, blobID, 1000, "")
	status, _ := rawDo(t, app, "PUT", "/api/v1/blobs/"+blobID+"/chunks/0", token, "application/octet-stream", content)
	if status != http.StatusOK {
		t.Fatalf("chunk status = %d", status)
	}
	status, out := app.do(t, "POST", "/api/v1/blobs/"+blobID+"/complete", token, nil)
	if status != http.StatusBadRequest || out["code"] != "INCOMPLETE" {
		t.Errorf("complete size mismatch = %d %v", status, out)
	}
}

func TestBlobConcurrentUploads(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-cc")
	var wg sync.WaitGroup
	errs := make(chan error, 2)
	for _, content := range [][]byte{
		bytes.Repeat([]byte("alpha"), chunkSize+3),
		bytes.Repeat([]byte("beta"), chunkSize+7),
	} {
		wg.Add(1)
		go func(data []byte) {
			defer wg.Done()
			id := blobIDOf(data)
			status, out := blobInit(t, app, token, id, int64(len(data)), "")
			if status != http.StatusOK || out["status"] != "CREATED" {
				errs <- fmt.Errorf("init: %d %v", status, out)
				return
			}
			for i := 0; i*chunkSize < len(data); i++ {
				lo, hi := i*chunkSize, (i+1)*chunkSize
				if hi > len(data) {
					hi = len(data)
				}
				status, _ := rawDo(t, app, "PUT", fmt.Sprintf("/api/v1/blobs/%s/chunks/%d", id, i), token, "application/octet-stream", data[lo:hi])
				if status != http.StatusOK {
					errs <- fmt.Errorf("chunk %d: %d", i, status)
					return
				}
			}
			status, out = app.do(t, "POST", "/api/v1/blobs/"+id+"/complete", token, nil)
			if status != http.StatusOK {
				errs <- fmt.Errorf("complete: %d %v", status, out)
				return
			}
			status, body := rawDo(t, app, "GET", "/api/v1/blobs/"+id, token, "", nil)
			if status != http.StatusOK || !bytes.Equal(body, data) {
				errs <- fmt.Errorf("download mismatch")
			}
		}(content)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
}

func TestBlobUploadSessionReusableAcrossRestart(t *testing.T) {
	app := newTestApp(t)
	token, _ := loginOK(t, app, "pc-blob-restart")
	content := []byte("restart resume")
	blobID := blobIDOf(content)
	status, out := blobInit(t, app, token, blobID, int64(len(content)), "")
	if status != http.StatusOK || out["status"] != "CREATED" {
		t.Fatalf("init = %d %v", status, out)
	}
	sessionID, _ := out["upload_session_id"].(string)
	status, out = blobInit(t, app, token, blobID, int64(len(content)), "")
	if status != http.StatusOK || out["status"] != "CREATED" || out["upload_session_id"] != sessionID {
		t.Fatalf("re-init = %d %v, session changed", status, out)
	}
}
