package testutil

import (
	"context"
	"path/filepath"
	"testing"

	"lightnote/server/internal/db"
)

func NewStore(t *testing.T) *db.Store {
	t.Helper()
	store, err := db.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })
	if err := db.Migrate(context.Background(), store); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return store
}
