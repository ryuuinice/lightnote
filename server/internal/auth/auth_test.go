package auth

import (
	"context"
	"errors"
	"testing"
	"time"

	"lightnote/server/internal/testutil"
)

func newAuth(t *testing.T) *Auth {
	store := testutil.NewStore(t)
	a := New(store, "test-secret", time.Hour)
	if _, err := a.EnsureDefaultUser(context.Background(), "admin", "admin123"); err != nil {
		t.Fatalf("ensure user: %v", err)
	}
	return a
}

func TestLoginSuccess(t *testing.T) {
	a := newAuth(t)
	res, err := a.Login(context.Background(), "admin", "admin123", "pc-1", "desktop")
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if res.AccessToken == "" || res.DeviceID == "" {
		t.Fatal("login returned empty token/device_id")
	}
	if res.ExpiresIn != 3600 {
		t.Errorf("expires_in = %d, want 3600", res.ExpiresIn)
	}
	res2, err := a.Login(context.Background(), "admin", "admin123", "pc-1", "desktop")
	if err != nil {
		t.Fatalf("second login: %v", err)
	}
	if res2.DeviceID != res.DeviceID {
		t.Errorf("device_id not stable across logins: %q != %q", res2.DeviceID, res.DeviceID)
	}
}

func TestLoginFailure(t *testing.T) {
	a := newAuth(t)
	if _, err := a.Login(context.Background(), "admin", "wrong", "pc-1", ""); !errors.Is(err, ErrInvalidCredentials) {
		t.Errorf("wrong password: got %v, want ErrInvalidCredentials", err)
	}
	if _, err := a.Login(context.Background(), "ghost", "admin123", "pc-1", ""); !errors.Is(err, ErrInvalidCredentials) {
		t.Errorf("unknown user: got %v, want ErrInvalidCredentials", err)
	}
}

func TestTokenRoundTrip(t *testing.T) {
	a := newAuth(t)
	token, err := a.IssueToken("user-1", "dev-1")
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	claims, err := a.ParseToken(token)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if claims.Subject != "user-1" || claims.DeviceID != "dev-1" {
		t.Errorf("claims = %+v", claims)
	}
	if _, err := a.ParseToken(token + "tampered"); err == nil {
		t.Error("tampered token accepted")
	}
}

func TestExpiredTokenRejected(t *testing.T) {
	store := testutil.NewStore(t)
	a := New(store, "test-secret", -time.Minute)
	token, err := a.IssueToken("user-1", "dev-1")
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	if _, err := a.ParseToken(token); err == nil {
		t.Error("expired token accepted")
	}
}

func TestCheckDevice(t *testing.T) {
	a := newAuth(t)
	res, err := a.Login(context.Background(), "admin", "admin123", "pc-1", "")
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if err := a.CheckDevice(context.Background(), res.DeviceID); err != nil {
		t.Errorf("active device rejected: %v", err)
	}
	if err := a.CheckDevice(context.Background(), "no-such-device"); !errors.Is(err, ErrDeviceNotFound) {
		t.Errorf("unknown device: got %v, want ErrDeviceNotFound", err)
	}
	if _, err := a.store.Write().ExecContext(context.Background(),
		"UPDATE devices SET revoked_at = ? WHERE device_id = ?", time.Now().UnixMilli(), res.DeviceID); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	if err := a.CheckDevice(context.Background(), res.DeviceID); !errors.Is(err, ErrDeviceRevoked) {
		t.Errorf("revoked device: got %v, want ErrDeviceRevoked", err)
	}
}

func TestRefreshTokenFlow(t *testing.T) {
	a := newAuth(t)
	res, err := a.Login(context.Background(), "admin", "admin123", "pc-refresh", "")
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if res.RefreshToken == "" {
		t.Fatal("login should issue refresh token")
	}
	// 正常刷新（轮换）
	r2, err := a.RefreshAccess(context.Background(), res.RefreshToken)
	if err != nil {
		t.Fatalf("refresh: %v", err)
	}
	if r2.RefreshToken == res.RefreshToken {
		t.Error("refresh token must rotate")
	}
	// 旧 token 复用必须失败（已轮换吊销）
	if _, err := a.RefreshAccess(context.Background(), res.RefreshToken); !errors.Is(err, ErrInvalidCredentials) {
		t.Errorf("reused rotated token: got %v, want ErrInvalidCredentials", err)
	}
	// 伪造 token 必须失败
	if _, err := a.RefreshAccess(context.Background(), "deadbeef"); !errors.Is(err, ErrInvalidCredentials) {
		t.Errorf("forged token: got %v, want ErrInvalidCredentials", err)
	}
	// 设备吊销后刷新必须失败
	r3, err := a.RefreshAccess(context.Background(), r2.RefreshToken)
	if err != nil {
		t.Fatalf("refresh 2: %v", err)
	}
	if _, err := a.store.Write().ExecContext(context.Background(),
		"UPDATE devices SET revoked_at = ? WHERE device_id = ?", time.Now().UnixMilli(), r3.DeviceID); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	if _, err := a.RefreshAccess(context.Background(), r3.RefreshToken); !errors.Is(err, ErrDeviceRevoked) {
		t.Errorf("revoked device refresh: got %v, want ErrDeviceRevoked", err)
	}
}
