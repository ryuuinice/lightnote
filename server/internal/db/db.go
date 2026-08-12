package db

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

var timeNow = time.Now

const dsnPragmas = "?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)"

type Store struct {
	read  *sql.DB
	write *sql.DB
}

func Open(path string) (*Store, error) {
	if dir := filepath.Dir(path); dir != "." && dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("create db dir: %w", err)
		}
	}
	read, err := sql.Open("sqlite", "file:"+path+dsnPragmas)
	if err != nil {
		return nil, fmt.Errorf("open read pool: %w", err)
	}
	read.SetMaxOpenConns(8)
	write, err := sql.Open("sqlite", "file:"+path+dsnPragmas)
	if err != nil {
		read.Close()
		return nil, fmt.Errorf("open write pool: %w", err)
	}
	write.SetMaxOpenConns(1)
	if err := read.Ping(); err != nil {
		read.Close()
		write.Close()
		return nil, fmt.Errorf("ping read pool: %w", err)
	}
	if err := write.Ping(); err != nil {
		read.Close()
		write.Close()
		return nil, fmt.Errorf("ping write pool: %w", err)
	}
	return &Store{read: read, write: write}, nil
}

func (s *Store) Close() error {
	if err := s.read.Close(); err != nil {
		return err
	}
	return s.write.Close()
}

func (s *Store) Read() *sql.DB { return s.read }

func (s *Store) Write() *sql.DB { return s.write }

func (s *Store) TouchDevice(ctx context.Context, deviceID string, seq int64) error {
	now := nowMs()
	tx, err := s.write.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin touch device: %w", err)
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, "UPDATE devices SET last_seen = ? WHERE device_id = ?", now, deviceID); err != nil {
		return fmt.Errorf("update device last_seen: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO device_sync_state (device_id, last_server_sequence, last_seen, updated_at)
		VALUES (?, ?, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET
			last_server_sequence = MAX(device_sync_state.last_server_sequence, excluded.last_server_sequence),
			last_seen = excluded.last_seen,
			updated_at = excluded.updated_at`, deviceID, seq, now, now); err != nil {
		return fmt.Errorf("upsert device_sync_state: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit touch device: %w", err)
	}
	return nil
}

func (s *Store) TouchDeviceLastSeen(ctx context.Context, deviceID string) error {
	if _, err := s.write.ExecContext(ctx, "UPDATE devices SET last_seen = ? WHERE device_id = ?", nowMs(), deviceID); err != nil {
		return fmt.Errorf("update device last_seen: %w", err)
	}
	return nil
}

func nowMs() int64 {
	return timeNow().UnixMilli()
}

// NextSequence allocates a server_sequence inside the caller's transaction.
// AUTOINCREMENT guarantees the value is strictly greater than every
// previously committed value, so no two committed changes can ever share a
// server_sequence. If the enclosing transaction rolls back, SQLite reverts
// sqlite_sequence with it and the next allocation resumes from the last
// committed value; the rolled-back value is not consumed, which is safe:
// it can only reappear as the next value in line, never as a duplicate of
// an already committed row. entity_changes rows are never physically
// deleted, so AUTOINCREMENT's high-water mark is never defeated.
func NextSequence(ctx context.Context, tx *sql.Tx) (int64, error) {
	var seq int64
	if err := tx.QueryRowContext(ctx, "INSERT INTO sync_sequence DEFAULT VALUES RETURNING seq").Scan(&seq); err != nil {
		return 0, fmt.Errorf("allocate server_sequence: %w", err)
	}
	return seq, nil
}
