package db

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

type schemaFile struct {
	Name    string
	Content string
}

const schemaDirEnv = "LIGHTNOTE_SCHEMA_DIR"

func loadSchemaDir() (string, error) {
	if dir := os.Getenv(schemaDirEnv); dir != "" {
		return dir, nil
	}
	_, file, _, ok := runtime.Caller(0)
	if ok {
		root := filepath.Dir(filepath.Dir(filepath.Dir(filepath.Dir(file))))
		if p := filepath.Join(root, "docs", "schema"); isDir(p) {
			return p, nil
		}
	}
	for _, p := range []string{"docs/schema", "../docs/schema", "../../docs/schema", "../../../docs/schema"} {
		if isDir(p) {
			return p, nil
		}
	}
	return "", fmt.Errorf("schema directory not found: set %s or run from the repository tree", schemaDirEnv)
}

func isDir(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.IsDir()
}

func loadSchemaFiles() ([]schemaFile, error) {
	dir, err := loadSchemaDir()
	if err != nil {
		return nil, err
	}
	names := []string{"common.sql", "server.sql"}
	files := make([]schemaFile, 0, len(names))
	for _, n := range names {
		b, err := os.ReadFile(filepath.Join(dir, n))
		if err != nil {
			return nil, fmt.Errorf("read schema %s: %w", n, err)
		}
		files = append(files, schemaFile{Name: n, Content: string(b)})
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Name < files[j].Name })
	return files, nil
}

func splitStatements(sql string) []string {
	var out []string
	var cur strings.Builder
	for _, line := range strings.Split(sql, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "--") {
			continue
		}
		cur.WriteString(line)
		cur.WriteString("\n")
		if strings.Contains(line, ";") {
			if s := strings.TrimSpace(cur.String()); s != "" {
				out = append(out, s)
			}
			cur.Reset()
		}
	}
	if s := strings.TrimSpace(cur.String()); s != "" {
		out = append(out, s)
	}
	return out
}

func Migrate(ctx context.Context, s *Store) error {
	files, err := loadSchemaFiles()
	if err != nil {
		return err
	}
	if len(files) == 0 {
		return fmt.Errorf("no schema files loaded")
	}
	tx, err := s.write.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin migration: %w", err)
	}
	defer tx.Rollback()
	var applied int
	var hasMigrations int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'").Scan(&hasMigrations); err != nil {
		return fmt.Errorf("check schema_migrations: %w", err)
	}
	if hasMigrations > 0 {
		if err := tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(version), 0) FROM schema_migrations").Scan(&applied); err != nil {
			return fmt.Errorf("read schema_migrations: %w", err)
		}
	}
	for i, f := range files {
		if i+1 <= applied {
			continue
		}
		for _, stmt := range splitStatements(f.Content) {
			if _, err := tx.ExecContext(ctx, stmt); err != nil {
				return fmt.Errorf("apply %s: %w", f.Name, err)
			}
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)", i+1, nowMs()); err != nil {
			return fmt.Errorf("record migration %s: %w", f.Name, err)
		}
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit migration: %w", err)
	}
	return nil
}
