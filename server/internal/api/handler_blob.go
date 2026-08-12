package api

import (
	"errors"
	"io"
	"net/http"
	"strconv"
	"time"

	"lightnote/server/internal/blob"
)

func nowMs() int64 {
	return time.Now().UnixMilli()
}

type blobInitRequest struct {
	BlobID   string `json:"blob_id"`
	Size     int64  `json:"size"`
	MimeType string `json:"mime_type"`
}

func (s *Server) handleBlobInit(w http.ResponseWriter, r *http.Request) {
	var req blobInitRequest
	if !readJSONBody(w, r, 1<<20, &req) {
		return
	}
	if _, ok := blob.ParseID(req.BlobID); !ok {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "blob_id 必须为 sha256: + 64 位十六进制")
		return
	}
	if req.Size < 0 || req.Size > blob.MaxBlobSize {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "size 超出允许范围")
		return
	}
	status, sessionID, err := s.blobs.Init(req.BlobID, req.Size, req.MimeType)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":            status,
		"upload_session_id": sessionID,
	})
}

func (s *Server) handleBlobChunk(w http.ResponseWriter, r *http.Request) {
	blobID := r.PathValue("blob_id")
	if _, ok := blob.ParseID(blobID); !ok {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "blob_id 无效")
		return
	}
	index, err := strconv.Atoi(r.PathValue("index"))
	if err != nil || index < 0 {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "分片索引必须为非负整数")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, blob.ChunkMaxSize)
	if err := s.blobs.PutChunk(blobID, index, r.Body); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeError(w, http.StatusRequestEntityTooLarge, "PAYLOAD_TOO_LARGE", "分片超过 16MB 上限")
			return
		}
		if errors.Is(err, blob.ErrSessionMissing) {
			writeError(w, http.StatusNotFound, "UPLOAD_SESSION_NOT_FOUND", "上传会话不存在，请重新 init")
			return
		}
		writeError(w, http.StatusBadRequest, "INVALID_DATA", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleBlobComplete(w http.ResponseWriter, r *http.Request) {
	blobID := r.PathValue("blob_id")
	if _, ok := blob.ParseID(blobID); !ok {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "blob_id 无效")
		return
	}
	info, err := s.blobs.Complete(blobID)
	switch {
	case err == nil:
	case errors.Is(err, blob.ErrSessionMissing):
		writeError(w, http.StatusNotFound, "UPLOAD_SESSION_NOT_FOUND", "上传会话不存在，请重新 init")
		return
	case errors.Is(err, blob.ErrRejected):
		writeError(w, http.StatusBadRequest, "REJECT", "SHA-256 与 blob_id 不一致")
		return
	case errors.Is(err, blob.ErrIncomplete):
		writeError(w, http.StatusBadRequest, "INCOMPLETE", "分片缺失或总大小与声明不一致")
		return
	default:
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	storagePath, ok := s.blobs.StoragePath(blobID)
	if !ok {
		storagePath = ""
	}
	if _, err := s.store.Write().ExecContext(r.Context(),
		`INSERT INTO blobs (blob_id, size, mime_type, storage_type, storage_path, created_at)
		 VALUES (?, ?, ?, 'file', ?, ?)
		 ON CONFLICT(blob_id) DO UPDATE SET
			size = excluded.size,
			mime_type = excluded.mime_type,
			storage_type = excluded.storage_type,
			storage_path = excluded.storage_path`,
		blobID, info.Size, mimeOrNull(info.MimeType), storagePath, nowMs()); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "size": info.Size})
}

func mimeOrNull(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (s *Server) handleBlobGet(w http.ResponseWriter, r *http.Request) {
	blobID := r.PathValue("blob_id")
	if _, ok := blob.ParseID(blobID); !ok {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "blob_id 无效")
		return
	}
	f, err := s.blobs.Open(blobID)
	if errors.Is(err, blob.ErrNotFound) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "Blob 不存在")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	defer f.Close()
	var mime string
	if err := s.store.Read().QueryRowContext(r.Context(),
		"SELECT COALESCE(mime_type, '') FROM blobs WHERE blob_id = ?", blobID).Scan(&mime); err != nil {
		mime = ""
	}
	if mime == "" {
		mime = "application/octet-stream"
	}
	w.Header().Set("Content-Type", mime)
	_, _ = io.Copy(w, f)
}
