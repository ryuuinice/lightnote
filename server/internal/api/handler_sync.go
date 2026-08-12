package api

import (
	"net/http"
	"strconv"

	"lightnote/server/internal/auth"
	"lightnote/server/internal/sync"
)

const (
	maxPushBytes      = 8 << 20
	maxPushChanges    = 1000
	maxPullLimit      = 1000
	defaultPullLimit  = 500
)

type pushRequest struct {
	Changes []sync.Change `json:"changes"`
}

func (s *Server) handlePush(w http.ResponseWriter, r *http.Request) {
	var req pushRequest
	if !readJSONBody(w, r, maxPushBytes, &req) {
		return
	}
	if len(req.Changes) > maxPushChanges {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "单批 Change 不能超过 1000 条")
		return
	}
	if len(req.Changes) == 0 {
		writeJSON(w, http.StatusOK, sync.PushResponse{Results: []sync.Result{}})
		return
	}
	deviceID := deviceIDFrom(r)
	results, err := s.push.Push(r.Context(), deviceID, req.Changes)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	_ = s.store.TouchDeviceLastSeen(r.Context(), deviceID)
	writeJSON(w, http.StatusOK, sync.PushResponse{Results: results})
}

func (s *Server) handlePull(w http.ResponseWriter, r *http.Request) {
	after, err := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	if err != nil || after < 0 {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "after 必须为非负整数")
		return
	}
	limit := defaultPullLimit
	if v := r.URL.Query().Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			writeError(w, http.StatusBadRequest, "INVALID_DATA", "limit 必须为正整数")
			return
		}
		if n > maxPullLimit {
			n = maxPullLimit
		}
		limit = n
	}
	deviceID := deviceIDFrom(r)
	resp, err := s.pull.Pull(r.Context(), after, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	_ = s.store.TouchDevice(r.Context(), deviceID, resp.NextSequence)
	writeJSON(w, http.StatusOK, resp)
}

func deviceIDFrom(r *http.Request) string {
	claims := claimsFrom(r)
	if claims == nil {
		return ""
	}
	return claims.DeviceID
}

func claimsFrom(r *http.Request) *auth.Claims {
	if v := r.Context().Value(claimsKey{}); v != nil {
		return v.(*auth.Claims)
	}
	return nil
}
