package auth

import (
	"context"
	"crypto/rand"
	"database/sql"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"

	"lightnote/server/internal/db"
)

var (
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrDeviceNotFound     = errors.New("device not found")
	ErrDeviceRevoked      = errors.New("device revoked")
)

type Claims struct {
	DeviceID string `json:"device_id"`
	jwt.RegisteredClaims
}

type Auth struct {
	secret []byte
	ttl    time.Duration
	store  *db.Store
}

func newID() string {
	id, err := uuid.NewV7()
	if err != nil {
		panic(err)
	}
	return id.String()
}

func New(store *db.Store, secret string, ttl time.Duration) *Auth {
	return &Auth{secret: []byte(secret), ttl: ttl, store: store}
}

func RandomSecret() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func (a *Auth) EnsureDefaultUser(ctx context.Context, username, password string) (string, error) {
	var userID string
	err := a.store.Read().QueryRowContext(ctx, "SELECT user_id FROM users WHERE username = ?", username).Scan(&userID)
	if err == nil {
		return userID, nil
	}
	if err != sql.ErrNoRows {
		return "", fmt.Errorf("lookup default user: %w", err)
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", fmt.Errorf("hash default password: %w", err)
	}
	id := newID()
	if _, err := a.store.Write().ExecContext(ctx,
		"INSERT INTO users (user_id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
		id, username, string(hash), time.Now().UnixMilli()); err != nil {
		return "", fmt.Errorf("create default user: %w", err)
	}
	return id, nil
}

type LoginResult struct {
	AccessToken  string
	RefreshToken string
	ExpiresIn    int64
	DeviceID     string
}

func (a *Auth) Login(ctx context.Context, username, password, deviceName, deviceType string) (*LoginResult, error) {
	var userID, hash string
	err := a.store.Read().QueryRowContext(ctx,
		"SELECT user_id, password_hash FROM users WHERE username = ?", username).Scan(&userID, &hash)
	if err == sql.ErrNoRows {
		return nil, ErrInvalidCredentials
	}
	if err != nil {
		return nil, fmt.Errorf("lookup user: %w", err)
	}
	if bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) != nil {
		return nil, ErrInvalidCredentials
	}
	deviceID, err := a.upsertDevice(ctx, userID, deviceName, deviceType)
	if err != nil {
		return nil, err
	}
	token, err := a.IssueToken(userID, deviceID)
	if err != nil {
		return nil, err
	}
	refresh, err := a.IssueRefreshToken(ctx, deviceID)
	if err != nil {
		return nil, err
	}
	return &LoginResult{
		AccessToken:  token,
		RefreshToken: refresh,
		ExpiresIn:    int64(a.ttl.Seconds()),
		DeviceID:     deviceID,
	}, nil
}

func (a *Auth) upsertDevice(ctx context.Context, userID, deviceName, deviceType string) (string, error) {
	tx, err := a.store.Write().BeginTx(ctx, nil)
	if err != nil {
		return "", fmt.Errorf("begin device upsert: %w", err)
	}
	defer tx.Rollback()
	now := time.Now().UnixMilli()
	var deviceID string
	err = tx.QueryRowContext(ctx,
		"SELECT device_id FROM devices WHERE user_id = ? AND device_name = ? AND revoked_at IS NULL",
		userID, deviceName).Scan(&deviceID)
	switch {
	case err == sql.ErrNoRows:
		deviceID = newID()
		if _, err := tx.ExecContext(ctx,
			"INSERT INTO devices (device_id, user_id, device_name, device_type, last_seen, created_at) VALUES (?, ?, ?, ?, ?, ?)",
			deviceID, userID, deviceName, deviceType, now, now); err != nil {
			return "", fmt.Errorf("insert device: %w", err)
		}
	case err != nil:
		return "", fmt.Errorf("lookup device: %w", err)
	default:
		if _, err := tx.ExecContext(ctx,
			"UPDATE devices SET device_type = COALESCE(?, device_type), last_seen = ? WHERE device_id = ?",
			deviceType, now, deviceID); err != nil {
			return "", fmt.Errorf("update device: %w", err)
		}
	}
	if _, err := tx.ExecContext(ctx,
		"INSERT INTO device_sync_state (device_id, last_server_sequence, last_seen, updated_at) VALUES (?, 0, ?, ?) ON CONFLICT(device_id) DO NOTHING",
		deviceID, now, now); err != nil {
		return "", fmt.Errorf("init device_sync_state: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return "", fmt.Errorf("commit device upsert: %w", err)
	}
	return deviceID, nil
}

func (a *Auth) IssueToken(userID, deviceID string) (string, error) {
	now := time.Now()
	claims := Claims{
		DeviceID: deviceID,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID,
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(a.ttl)),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(a.secret)
}

const refreshTokenTTL = 30 * 24 * time.Hour

func (a *Auth) IssueRefreshToken(ctx context.Context, deviceID string) (string, error) {
	raw := make([]byte, 48)
	if _, err := rand.Read(raw); err != nil {
		return "", fmt.Errorf("refresh token rand: %w", err)
	}
	token := hex.EncodeToString(raw)
	hash := sha256Hex(token)
	now := time.Now().UnixMilli()
	if _, err := a.store.Write().ExecContext(ctx,
		"INSERT INTO refresh_tokens (token_hash, device_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
		hash, deviceID, now+refreshTokenTTL.Milliseconds(), now); err != nil {
		return "", fmt.Errorf("store refresh token: %w", err)
	}
	return token, nil
}

func (a *Auth) RefreshAccess(ctx context.Context, refreshToken string) (*LoginResult, error) {
	hash := sha256Hex(refreshToken)
	var deviceID, userID string
	var expiresAt, tokenRevokedAt, deviceRevokedAt sql.NullInt64
	err := a.store.Read().QueryRowContext(ctx,
		"SELECT rt.device_id, d.user_id, rt.expires_at, rt.revoked_at, d.revoked_at FROM refresh_tokens rt JOIN devices d ON d.device_id = rt.device_id WHERE rt.token_hash = ?",
		hash).Scan(&deviceID, &userID, &expiresAt, &tokenRevokedAt, &deviceRevokedAt)
	if err == sql.ErrNoRows {
		return nil, ErrInvalidCredentials
	}
	if err != nil {
		return nil, fmt.Errorf("lookup refresh token: %w", err)
	}
	if tokenRevokedAt.Valid {
		return nil, ErrInvalidCredentials
	}
	if expiresAt.Valid && expiresAt.Int64 < time.Now().UnixMilli() {
		return nil, ErrInvalidCredentials
	}
	if deviceRevokedAt.Valid {
		if _, err := a.store.Write().ExecContext(ctx,
			"UPDATE refresh_tokens SET revoked_at = ? WHERE device_id = ?",
			time.Now().UnixMilli(), deviceID); err != nil {
			return nil, fmt.Errorf("revoke device tokens: %w", err)
		}
		return nil, ErrDeviceRevoked
	}
	if _, err := a.store.Write().ExecContext(ctx,
		"UPDATE devices SET last_seen = ? WHERE device_id = ?",
		time.Now().UnixMilli(), deviceID); err != nil {
		return nil, fmt.Errorf("touch device: %w", err)
	}
	access, err := a.IssueToken(userID, deviceID)
	if err != nil {
		return nil, err
	}
	newRefresh, err := a.IssueRefreshToken(ctx, deviceID)
	if err != nil {
		return nil, err
	}
	if _, err := a.store.Write().ExecContext(ctx,
		"UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ?",
		time.Now().UnixMilli(), hash); err != nil {
		return nil, fmt.Errorf("rotate refresh token: %w", err)
	}
	return &LoginResult{
		AccessToken:  access,
		RefreshToken: newRefresh,
		ExpiresIn:    int64(a.ttl.Seconds()),
		DeviceID:     deviceID,
	}, nil
}

func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

func (a *Auth) ParseToken(tokenString string) (*Claims, error) {
	claims := &Claims{}
	_, err := jwt.ParseWithClaims(tokenString, claims, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method %v", t.Header["alg"])
		}
		return a.secret, nil
	})
	if err != nil {
		return nil, fmt.Errorf("parse token: %w", err)
	}
	return claims, nil
}

func (a *Auth) CheckDevice(ctx context.Context, deviceID string) error {
	var revoked sql.NullInt64
	err := a.store.Read().QueryRowContext(ctx,
		"SELECT revoked_at FROM devices WHERE device_id = ?", deviceID).Scan(&revoked)
	if err == sql.ErrNoRows {
		return ErrDeviceNotFound
	}
	if err != nil {
		return fmt.Errorf("check device: %w", err)
	}
	if revoked.Valid {
		return ErrDeviceRevoked
	}
	return nil
}
