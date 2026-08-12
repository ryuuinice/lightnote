package db

import (
	"context"
	"path/filepath"
	"testing"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	store, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })
	if err := Migrate(context.Background(), store); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return store
}

func TestMigrateCreatesTables(t *testing.T) {
	store := newStore(t)
	for _, table := range []string{"schema_migrations", "users", "notes", "branches", "attributes", "blobs", "entity_changes", "sync_sequence", "devices", "device_sync_state", "refresh_tokens"} {
		var n int
		if err := store.Read().QueryRowContext(context.Background(),
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", table).Scan(&n); err != nil {
			t.Fatalf("query %s: %v", table, err)
		}
		if n != 1 {
			t.Errorf("table %s missing", table)
		}
	}
	var migrations int
	if err := store.Read().QueryRowContext(context.Background(),
		"SELECT COUNT(*) FROM schema_migrations").Scan(&migrations); err != nil {
		t.Fatalf("count migrations: %v", err)
	}
	if migrations != 2 {
		t.Errorf("schema_migrations count = %d, want 2", migrations)
	}
}

func TestMigrateIdempotent(t *testing.T) {
	store := newStore(t)
	if err := Migrate(context.Background(), store); err != nil {
		t.Fatalf("second migrate: %v", err)
	}
	var migrations int
	if err := store.Read().QueryRowContext(context.Background(),
		"SELECT COUNT(*) FROM schema_migrations").Scan(&migrations); err != nil {
		t.Fatal(err)
	}
	if migrations != 2 {
		t.Errorf("schema_migrations count after rerun = %d, want 2", migrations)
	}
}

func TestWALEnabled(t *testing.T) {
	store := newStore(t)
	var mode string
	if err := store.Read().QueryRowContext(context.Background(), "PRAGMA journal_mode").Scan(&mode); err != nil {
		t.Fatalf("journal_mode: %v", err)
	}
	if mode != "wal" {
		t.Errorf("journal_mode = %q, want wal", mode)
	}
}

func TestSplitStatements(t *testing.T) {
	sql := "CREATE TABLE a (id INTEGER);\nCREATE TABLE b (id INTEGER);\n-- comment only\n"
	stmts := splitStatements(sql)
	if len(stmts) != 2 {
		t.Fatalf("got %d statements, want 2: %v", len(stmts), stmts)
	}
}
