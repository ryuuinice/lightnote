package api

import (
	"errors"
	"net/http"

	"lightnote/server/internal/auth"
)

type loginRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceName string `json:"device_name"`
	DeviceType string `json:"device_type"`
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !readJSONBody(w, r, 1<<20, &req) {
		return
	}
	if req.Username == "" || req.Password == "" || req.DeviceName == "" {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "username、password、device_name 不能为空")
		return
	}
	res, err := s.auth.Login(r.Context(), req.Username, req.Password, req.DeviceName, req.DeviceType)
	if errors.Is(err, auth.ErrInvalidCredentials) {
		writeError(w, http.StatusUnauthorized, "INVALID_CREDENTIALS", "用户名或密码错误")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"access_token":  res.AccessToken,
		"refresh_token": res.RefreshToken,
		"expires_in":    res.ExpiresIn,
		"device_id":     res.DeviceID,
	})
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if !readJSONBody(w, r, 1<<20, &req) {
		return
	}
	if req.RefreshToken == "" {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "refresh_token 不能为空")
		return
	}
	res, err := s.auth.RefreshAccess(r.Context(), req.RefreshToken)
	switch {
	case errors.Is(err, auth.ErrInvalidCredentials):
		writeError(w, http.StatusUnauthorized, "INVALID_REFRESH_TOKEN", "刷新令牌无效或已过期")
		return
	case errors.Is(err, auth.ErrDeviceRevoked):
		writeError(w, http.StatusForbidden, "DEVICE_REVOKED", "设备已吊销")
		return
	case err != nil:
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"access_token":  res.AccessToken,
		"refresh_token": res.RefreshToken,
		"expires_in":    res.ExpiresIn,
	})
}
