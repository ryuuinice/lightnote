package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"

	"lightnote/server/internal/auth"
	"lightnote/server/internal/blob"
	"lightnote/server/internal/config"
	"lightnote/server/internal/db"
	"lightnote/server/internal/sync"
)

type Server struct {
	cfg       *config.Config
	store     *db.Store
	auth      *auth.Auth
	push      *sync.PushService
	pull      *sync.Puller
	committer *sync.Committer
	blobs     *blob.Store
}

func New(cfg *config.Config, store *db.Store, a *auth.Auth, push *sync.PushService, pull *sync.Puller, blobs *blob.Store) *Server {
	return &Server{cfg: cfg, store: store, auth: a, push: push, pull: pull, committer: sync.NewCommitter(store), blobs: blobs}
}

func (s *Server) Handler() http.Handler {
	protected := http.NewServeMux()
	protected.HandleFunc("POST /api/v1/sync/push", s.handlePush)
	protected.HandleFunc("GET /api/v1/sync/changes", s.handlePull)
	protected.HandleFunc("GET /api/v1/notes", s.handleListNotes)
	protected.HandleFunc("GET /api/v1/notes/{note_id}", s.handleGetNote)
	protected.HandleFunc("DELETE /api/v1/notes/{note_id}", s.handleDeleteNote)
	protected.HandleFunc("GET /api/v1/notes/{note_id}/attributes", s.handleListAttributes)
	protected.HandleFunc("GET /api/v1/branches", s.handleListBranches)
	protected.HandleFunc("POST /api/v1/blobs/init", s.handleBlobInit)
	protected.HandleFunc("PUT /api/v1/blobs/{blob_id}/chunks/{index}", s.handleBlobChunk)
	protected.HandleFunc("POST /api/v1/blobs/{blob_id}/complete", s.handleBlobComplete)
	protected.HandleFunc("GET /api/v1/blobs/{blob_id}", s.handleBlobGet)
	protected.HandleFunc("GET /api/v1/devices", s.handleListDevices)
	protected.HandleFunc("DELETE /api/v1/devices/{device_id}", s.handleRevokeDevice)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/healthz", s.handleHealthz)
	mux.HandleFunc("POST /api/v1/auth/login", s.handleLogin)
	mux.HandleFunc("GET /api/v1/auth/refresh", s.handleRefresh)
	mux.HandleFunc("POST /api/v1/auth/refresh", s.handleRefresh)
	mux.Handle("/api/v1/", s.requireAuth(protected))
	return mux
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{"code": code, "message": message})
}

func readJSONBody(w http.ResponseWriter, r *http.Request, maxBytes int64, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeError(w, http.StatusRequestEntityTooLarge, "PAYLOAD_TOO_LARGE", "请求体超过 8MB")
			return false
		}
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "请求体解析失败")
		return false
	}
	return true
}

func (s *Server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, ok := bearerToken(r)
		if !ok {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "缺少 Bearer Token")
			return
		}
		claims, err := s.auth.ParseToken(token)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Token 无效或已过期")
			return
		}
		switch err := s.auth.CheckDevice(r.Context(), claims.DeviceID); err {
		case nil:
		case auth.ErrDeviceNotFound:
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "设备不存在")
			return
		case auth.ErrDeviceRevoked:
			writeError(w, http.StatusForbidden, "DEVICE_REVOKED", "设备已吊销")
			return
		default:
			writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
			return
		}
		ctx := context.WithValue(r.Context(), claimsKey{}, claims)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

type claimsKey struct{}

func bearerToken(r *http.Request) (string, bool) {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(h) <= len(prefix) || h[:len(prefix)] != prefix {
		return "", false
	}
	return h[len(prefix):], true
}
