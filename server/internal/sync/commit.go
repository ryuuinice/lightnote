package sync

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"

	"lightnote/server/internal/db"
)

var ErrNoteNotFound = errors.New("note not found")

type Committer struct {
	store *db.Store
}

func NewCommitter(store *db.Store) *Committer {
	return &Committer{store: store}
}

type noteSnapshot struct {
	Title            string  `json:"title"`
	NoteType         string  `json:"note_type"`
	BlobID           *string `json:"blob_id"`
	IsDeleted        *bool   `json:"is_deleted"`
	CreatedAt        *int64  `json:"created_at"`
	UpdatedAt        *int64  `json:"updated_at"`
	ConflictOfNoteID *string `json:"conflict_of_note_id"`
}

type branchSnapshot struct {
	ParentNoteID string  `json:"parent_note_id"`
	ChildNoteID  string  `json:"child_note_id"`
	SortOrder    *int64  `json:"sort_order"`
	IsDeleted    *bool   `json:"is_deleted"`
	CreatedAt    *int64  `json:"created_at"`
	UpdatedAt    *int64  `json:"updated_at"`
}

type attributeSnapshot struct {
	NoteID      string  `json:"note_id"`
	AttrType    string  `json:"attr_type"`
	Name        string  `json:"name"`
	Value       *string `json:"value"`
	IsInherited *bool   `json:"is_inherited"`
	IsDeleted   *bool   `json:"is_deleted"`
	CreatedAt   *int64  `json:"created_at"`
	UpdatedAt   *int64  `json:"updated_at"`
}

type blobSnapshot struct {
	Size        *int64  `json:"size"`
	MimeType    *string `json:"mime_type"`
	StorageType *string `json:"storage_type"`
	StoragePath *string `json:"storage_path"`
	CreatedAt   *int64  `json:"created_at"`
}

var entityTypes = map[string]bool{"note": true, "branch": true, "attribute": true, "blob": true}

var operations = map[string]bool{"CREATE": true, "UPDATE": true, "DELETE": true}

var attrTypes = map[string]bool{"label": true, "relation": true, "meta": true}

func (c *Committer) Commit(ctx context.Context, deviceID string, ch *Change) (*Result, error) {
	if st := validateChange(ch); st != "" {
		return &Result{ChangeID: ch.ChangeID, Status: st}, nil
	}
	snap, err := parseSnapshot(ch)
	if err != nil {
		return &Result{ChangeID: ch.ChangeID, Status: StatusInvalid}, nil
	}
	tx, err := c.store.Write().BeginTx(ctx, nil)
	if err != nil {
		return nil, fmt.Errorf("begin commit: %w", err)
	}
	defer tx.Rollback()

	var existing sql.NullInt64
	err = tx.QueryRowContext(ctx, "SELECT server_sequence FROM entity_changes WHERE change_id = ?", ch.ChangeID).Scan(&existing)
	if err == nil {
		return &Result{ChangeID: ch.ChangeID, Status: StatusAlreadyApplied, ServerSequence: ptr(existing.Int64)}, nil
	}
	if err != sql.ErrNoRows {
		return nil, fmt.Errorf("idempotency check: %w", err)
	}

	current, exists, err := loadEntityVersion(ctx, tx, ch.EntityType, ch.EntityID)
	if err != nil {
		return nil, fmt.Errorf("load entity: %w", err)
	}

	if !exists && ch.Operation != "CREATE" {
		return &Result{ChangeID: ch.ChangeID, Status: StatusInvalid}, nil
	}

	if exists && ch.EntityType != "blob" && ch.BaseVersion != current {
		if ch.EntityType == "note" {
			seq, err := c.conflictCopy(ctx, tx, deviceID, ch, snap.(*noteSnapshot))
			if err != nil {
				return nil, fmt.Errorf("create conflict copy: %w", err)
			}
			if err := tx.Commit(); err != nil {
				return nil, fmt.Errorf("commit conflict copy: %w", err)
			}
			return &Result{ChangeID: ch.ChangeID, Status: StatusConflict, ServerSequence: &seq}, nil
		}
		return &Result{ChangeID: ch.ChangeID, Status: StatusConflict}, nil
	}

	if err := c.applyEntity(ctx, tx, deviceID, ch, snap); err != nil {
		return nil, fmt.Errorf("apply entity: %w", err)
	}
	seq, err := db.NextSequence(ctx, tx)
	if err != nil {
		return nil, err
	}
	if err := recordChange(ctx, tx, deviceID, ch, seq); err != nil {
		return nil, fmt.Errorf("record change: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return nil, fmt.Errorf("commit change: %w", err)
	}
	return &Result{ChangeID: ch.ChangeID, Status: StatusApplied, ServerSequence: &seq}, nil
}

func validateChange(ch *Change) ResultStatus {
	switch {
	case ch.ChangeID == "":
		return StatusInvalid
	case !entityTypes[ch.EntityType]:
		return StatusInvalid
	case !operations[ch.Operation]:
		return StatusInvalid
	case ch.BaseVersion < 0 || ch.Version < 1 || ch.Version < ch.BaseVersion:
		return StatusInvalid
	case len(ch.Payload) == 0 || !json.Valid(ch.Payload) || string(ch.Payload) == "null":
		return StatusInvalid
	}
	return ""
}

func parseSnapshot(ch *Change) (any, error) {
	switch ch.EntityType {
	case "note":
		var s noteSnapshot
		if err := json.Unmarshal(ch.Payload, &s); err != nil {
			return nil, err
		}
		return &s, nil
	case "branch":
		var s branchSnapshot
		if err := json.Unmarshal(ch.Payload, &s); err != nil {
			return nil, err
		}
		if s.ParentNoteID == "" || s.ChildNoteID == "" {
			return nil, errors.New("branch payload requires parent_note_id and child_note_id")
		}
		return &s, nil
	case "attribute":
		var s attributeSnapshot
		if err := json.Unmarshal(ch.Payload, &s); err != nil {
			return nil, err
		}
		if s.NoteID == "" || s.Name == "" || !attrTypes[s.AttrType] {
			return nil, errors.New("attribute payload requires note_id, attr_type and name")
		}
		return &s, nil
	case "blob":
		var s blobSnapshot
		if err := json.Unmarshal(ch.Payload, &s); err != nil {
			return nil, err
		}
		if s.Size == nil || *s.Size < 0 {
			return nil, errors.New("blob payload requires size")
		}
		return &s, nil
	}
	return nil, errors.New("unknown entity type")
}

func loadEntityVersion(ctx context.Context, tx *sql.Tx, entityType, entityID string) (int64, bool, error) {
	if entityType == "blob" {
		var one int
		err := tx.QueryRowContext(ctx, "SELECT 1 FROM blobs WHERE blob_id = ?", entityID).Scan(&one)
		if err == sql.ErrNoRows {
			return 0, false, nil
		}
		if err != nil {
			return 0, false, err
		}
		return 0, true, nil
	}
	var table, col string
	switch entityType {
	case "note":
		table, col = "notes", "note_id"
	case "branch":
		table, col = "branches", "branch_id"
	case "attribute":
		table, col = "attributes", "attribute_id"
	}
	var v int64
	err := tx.QueryRowContext(ctx, "SELECT version FROM "+table+" WHERE "+col+" = ?", entityID).Scan(&v)
	if err == sql.ErrNoRows {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	return v, true, nil
}

func (c *Committer) conflictCopy(ctx context.Context, tx *sql.Tx, deviceID string, ch *Change, snap *noteSnapshot) (int64, error) {
	now := nowMs()
	copyID := newID()
	title := snap.Title
	if title == "" {
		title = "（冲突副本）"
	} else {
		title += "（冲突副本）"
	}
	conflictOf := ch.EntityID
	payload, err := json.Marshal(noteSnapshot{
		Title:            title,
		NoteType:         snap.NoteType,
		BlobID:           snap.BlobID,
		IsDeleted:        boolPtr(false),
		CreatedAt:        &now,
		UpdatedAt:        &now,
		ConflictOfNoteID: &conflictOf,
	})
	if err != nil {
		return 0, err
	}
	copyChange := &Change{
		ChangeID:    newID(),
		EntityType:  "note",
		EntityID:    copyID,
		Operation:   "CREATE",
		BaseVersion: 0,
		Version:     1,
		Payload:     payload,
		CreatedAt:   ch.CreatedAt,
	}
	if err := c.applyNote(ctx, tx, deviceID, copyChange, &noteSnapshot{
		Title:            title,
		NoteType:         snap.NoteType,
		BlobID:           snap.BlobID,
		IsDeleted:        boolPtr(false),
		CreatedAt:        &now,
		UpdatedAt:        &now,
		ConflictOfNoteID: &conflictOf,
	}); err != nil {
		return 0, err
	}
	seq, err := db.NextSequence(ctx, tx)
	if err != nil {
		return 0, err
	}
	if err := recordChange(ctx, tx, deviceID, copyChange, seq); err != nil {
		return 0, err
	}
	return seq, nil
}

func (c *Committer) applyEntity(ctx context.Context, tx *sql.Tx, deviceID string, ch *Change, snap any) error {
	switch ch.EntityType {
	case "note":
		return c.applyNote(ctx, tx, deviceID, ch, snap.(*noteSnapshot))
	case "branch":
		return c.applyBranch(ctx, tx, deviceID, ch, snap.(*branchSnapshot))
	case "attribute":
		return c.applyAttribute(ctx, tx, deviceID, ch, snap.(*attributeSnapshot))
	case "blob":
		return c.applyBlob(ctx, tx, ch.EntityID, snap.(*blobSnapshot))
	}
	return fmt.Errorf("unsupported entity type %s", ch.EntityType)
}

func (c *Committer) applyNote(ctx context.Context, tx *sql.Tx, deviceID string, ch *Change, snap *noteSnapshot) error {
	now := nowMs()
	noteType := snap.NoteType
	if noteType == "" {
		noteType = "text"
	}
	isDeleted := ch.Operation == "DELETE"
	if snap.IsDeleted != nil && *snap.IsDeleted {
		isDeleted = true
	}
	createdAt := now
	if snap.CreatedAt != nil {
		createdAt = *snap.CreatedAt
	}
	updatedAt := now
	if snap.UpdatedAt != nil {
		updatedAt = *snap.UpdatedAt
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO notes
		(note_id, title, note_type, blob_id, is_deleted, version, updated_at, updated_by, created_at, conflict_of_note_id)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(note_id) DO UPDATE SET
			title = excluded.title,
			note_type = excluded.note_type,
			blob_id = excluded.blob_id,
			is_deleted = excluded.is_deleted,
			version = excluded.version,
			updated_at = excluded.updated_at,
			updated_by = excluded.updated_by`,
		ch.EntityID, snap.Title, noteType, snap.BlobID, isDeleted, ch.Version, updatedAt, deviceID, createdAt, snap.ConflictOfNoteID)
	if err != nil {
		return err
	}
	return nil
}

func (c *Committer) applyBranch(ctx context.Context, tx *sql.Tx, deviceID string, ch *Change, snap *branchSnapshot) error {
	now := nowMs()
	sortOrder := int64(0)
	if snap.SortOrder != nil {
		sortOrder = *snap.SortOrder
	}
	isDeleted := ch.Operation == "DELETE"
	if snap.IsDeleted != nil && *snap.IsDeleted {
		isDeleted = true
	}
	createdAt := now
	if snap.CreatedAt != nil {
		createdAt = *snap.CreatedAt
	}
	updatedAt := now
	if snap.UpdatedAt != nil {
		updatedAt = *snap.UpdatedAt
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO branches
		(branch_id, parent_note_id, child_note_id, sort_order, is_deleted, version, updated_at, updated_by, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(branch_id) DO UPDATE SET
			parent_note_id = excluded.parent_note_id,
			child_note_id = excluded.child_note_id,
			sort_order = excluded.sort_order,
			is_deleted = excluded.is_deleted,
			version = excluded.version,
			updated_at = excluded.updated_at,
			updated_by = excluded.updated_by`,
		ch.EntityID, snap.ParentNoteID, snap.ChildNoteID, sortOrder, isDeleted, ch.Version, updatedAt, deviceID, createdAt)
	if err != nil {
		return err
	}
	return nil
}

func (c *Committer) applyAttribute(ctx context.Context, tx *sql.Tx, deviceID string, ch *Change, snap *attributeSnapshot) error {
	now := nowMs()
	isInherited := false
	if snap.IsInherited != nil {
		isInherited = *snap.IsInherited
	}
	isDeleted := ch.Operation == "DELETE"
	if snap.IsDeleted != nil && *snap.IsDeleted {
		isDeleted = true
	}
	createdAt := now
	if snap.CreatedAt != nil {
		createdAt = *snap.CreatedAt
	}
	updatedAt := now
	if snap.UpdatedAt != nil {
		updatedAt = *snap.UpdatedAt
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO attributes
		(attribute_id, note_id, attr_type, name, value, is_inherited, is_deleted, version, updated_at, updated_by, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(attribute_id) DO UPDATE SET
			note_id = excluded.note_id,
			attr_type = excluded.attr_type,
			name = excluded.name,
			value = excluded.value,
			is_inherited = excluded.is_inherited,
			is_deleted = excluded.is_deleted,
			version = excluded.version,
			updated_at = excluded.updated_at,
			updated_by = excluded.updated_by`,
		ch.EntityID, snap.NoteID, snap.AttrType, snap.Name, snap.Value, isInherited, isDeleted, ch.Version, updatedAt, deviceID, createdAt)
	if err != nil {
		return err
	}
	return nil
}

func (c *Committer) applyBlob(ctx context.Context, tx *sql.Tx, entityID string, snap *blobSnapshot) error {
	now := nowMs()
	storageType := "file"
	if snap.StorageType != nil {
		storageType = *snap.StorageType
	}
	storagePath := ""
	if snap.StoragePath != nil {
		storagePath = *snap.StoragePath
	}
	createdAt := now
	if snap.CreatedAt != nil {
		createdAt = *snap.CreatedAt
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO blobs
		(blob_id, size, mime_type, storage_type, storage_path, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(blob_id) DO UPDATE SET
			size = excluded.size,
			mime_type = excluded.mime_type,
			storage_type = excluded.storage_type,
			storage_path = excluded.storage_path`,
		entityID, *snap.Size, snap.MimeType, storageType, storagePath, createdAt)
	if err != nil {
		return err
	}
	return nil
}

func recordChange(ctx context.Context, tx *sql.Tx, deviceID string, ch *Change, seq int64) error {
	created := ch.CreatedAt
	if created <= 0 {
		created = nowMs()
	}
	var contentHash any
	if ch.ContentHash != "" {
		contentHash = ch.ContentHash
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO entity_changes
		(change_id, origin_device_id, entity_type, entity_id, operation, base_version, version, server_sequence, content_hash, payload, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		ch.ChangeID, deviceID, ch.EntityType, ch.EntityID, ch.Operation, ch.BaseVersion, ch.Version, seq, contentHash, string(ch.Payload), created)
	if err != nil {
		return err
	}
	return nil
}

func (c *Committer) DeleteNote(ctx context.Context, deviceID, noteID string) error {
	tx, err := c.store.Write().BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin delete note: %w", err)
	}
	defer tx.Rollback()
	var version int64
	var isDeleted int
	var title, noteType string
	var blobID, conflictOf sql.NullString
	var createdAt, updatedAt int64
	err = tx.QueryRowContext(ctx,
		"SELECT version, is_deleted, title, note_type, blob_id, created_at, updated_at, conflict_of_note_id FROM notes WHERE note_id = ?",
		noteID).Scan(&version, &isDeleted, &title, &noteType, &blobID, &createdAt, &updatedAt, &conflictOf)
	if err == sql.ErrNoRows {
		return ErrNoteNotFound
	}
	if err != nil {
		return fmt.Errorf("load note: %w", err)
	}
	if isDeleted == 1 {
		return tx.Commit()
	}
	now := nowMs()
	if _, err := tx.ExecContext(ctx,
		"UPDATE notes SET is_deleted = 1, version = version + 1, updated_at = ?, updated_by = ? WHERE note_id = ?",
		now, deviceID, noteID); err != nil {
		return fmt.Errorf("tombstone note: %w", err)
	}
	payload, err := json.Marshal(noteSnapshot{
		Title:            title,
		NoteType:         noteType,
		BlobID:           nullableToPtr(blobID),
		IsDeleted:        boolPtr(true),
		CreatedAt:        &createdAt,
		UpdatedAt:        &now,
		ConflictOfNoteID: nullableToPtr(conflictOf),
	})
	if err != nil {
		return err
	}
	seq, err := db.NextSequence(ctx, tx)
	if err != nil {
		return err
	}
	ch := &Change{
		ChangeID:    newID(),
		EntityType:  "note",
		EntityID:    noteID,
		Operation:   "DELETE",
		BaseVersion: version,
		Version:     version + 1,
		Payload:     payload,
		CreatedAt:   now,
	}
	if err := recordChange(ctx, tx, deviceID, ch, seq); err != nil {
		return fmt.Errorf("record delete change: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit delete note: %w", err)
	}
	return nil
}

func ptr(v int64) *int64 { return &v }

func newID() string {
	id, err := uuid.NewV7()
	if err != nil {
		panic(err)
	}
	return id.String()
}

func boolPtr(v bool) *bool { return &v }

func nullableToPtr(n sql.NullString) *string {
	if n.Valid {
		return &n.String
	}
	return nil
}

func nowMs() int64 {
	return time.Now().UnixMilli()
}
