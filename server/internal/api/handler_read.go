package api

import (
	"database/sql"
	"errors"
	"net/http"
	"strconv"

	"lightnote/server/internal/sync"
)

type Note struct {
	NoteID            string  `json:"note_id"`
	Title             string  `json:"title"`
	NoteType          string  `json:"note_type"`
	BlobID            *string `json:"blob_id,omitempty"`
	IsDeleted         bool    `json:"is_deleted"`
	Version           int64   `json:"version"`
	UpdatedAt         int64   `json:"updated_at"`
	UpdatedBy         *string `json:"updated_by,omitempty"`
	CreatedAt         int64   `json:"created_at"`
	ConflictOfNoteID  *string `json:"conflict_of_note_id,omitempty"`
}

type Branch struct {
	BranchID      string `json:"branch_id"`
	ParentNoteID  string `json:"parent_note_id"`
	ChildNoteID   string `json:"child_note_id"`
	SortOrder     int64  `json:"sort_order"`
	IsDeleted     bool   `json:"is_deleted"`
}

type Attribute struct {
	AttributeID string  `json:"attribute_id"`
	NoteID      string  `json:"note_id"`
	AttrType    string  `json:"attr_type"`
	Name        string  `json:"name"`
	Value       *string `json:"value,omitempty"`
	IsInherited bool    `json:"is_inherited"`
}

func (s *Server) handleListNotes(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	includeDeleted := q.Get("include_deleted") == "true"
	limit := 200
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			writeError(w, http.StatusBadRequest, "INVALID_DATA", "limit 必须为正整数")
			return
		}
		if n > 1000 {
			n = 1000
		}
		limit = n
	}
	var rows *sql.Rows
	var err error
	if parent := q.Get("parent_note_id"); parent != "" {
		rows, err = s.store.Read().QueryContext(r.Context(), `SELECT n.note_id, n.title, n.note_type, n.blob_id,
				n.is_deleted, n.version, n.updated_at, n.updated_by, n.created_at, n.conflict_of_note_id
			FROM branches b JOIN notes n ON n.note_id = b.child_note_id
			WHERE b.parent_note_id = ? AND b.is_deleted = 0`+deletedClause("n", includeDeleted)+`
			ORDER BY b.sort_order ASC, n.title ASC LIMIT ?`, parent, limit)
	} else {
		rows, err = s.store.Read().QueryContext(r.Context(), `SELECT note_id, title, note_type, blob_id,
				is_deleted, version, updated_at, updated_by, created_at, conflict_of_note_id
			FROM notes WHERE 1 = 1`+deletedClause("", includeDeleted)+`
			ORDER BY updated_at DESC LIMIT ?`, limit)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	defer rows.Close()
	notes, err := scanNotes(rows)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"notes": notes})
}

func deletedClause(table string, includeDeleted bool) string {
	if includeDeleted {
		return ""
	}
	if table == "" {
		return " AND is_deleted = 0"
	}
	return " AND " + table + ".is_deleted = 0"
}

func scanNotes(rows *sql.Rows) ([]Note, error) {
	notes := []Note{}
	for rows.Next() {
		var n Note
		var isDeleted int
		if err := rows.Scan(&n.NoteID, &n.Title, &n.NoteType, &n.BlobID, &isDeleted, &n.Version,
			&n.UpdatedAt, &n.UpdatedBy, &n.CreatedAt, &n.ConflictOfNoteID); err != nil {
			return nil, err
		}
		n.IsDeleted = isDeleted == 1
		notes = append(notes, n)
	}
	return notes, rows.Err()
}

func (s *Server) handleGetNote(w http.ResponseWriter, r *http.Request) {
	noteID := r.PathValue("note_id")
	var n Note
	var isDeleted int
	err := s.store.Read().QueryRowContext(r.Context(), `SELECT note_id, title, note_type, blob_id,
			is_deleted, version, updated_at, updated_by, created_at, conflict_of_note_id
		FROM notes WHERE note_id = ?`, noteID).
		Scan(&n.NoteID, &n.Title, &n.NoteType, &n.BlobID, &isDeleted, &n.Version,
			&n.UpdatedAt, &n.UpdatedBy, &n.CreatedAt, &n.ConflictOfNoteID)
	if err == sql.ErrNoRows {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "笔记不存在")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	n.IsDeleted = isDeleted == 1
	writeJSON(w, http.StatusOK, n)
}

func (s *Server) handleDeleteNote(w http.ResponseWriter, r *http.Request) {
	noteID := r.PathValue("note_id")
	err := s.committer.DeleteNote(r.Context(), deviceIDFrom(r), noteID)
	if errors.Is(err, sync.ErrNoteNotFound) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "笔记不存在")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}

func (s *Server) handleListBranches(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	parent := q.Get("parent_note_id")
	if parent == "" {
		writeError(w, http.StatusBadRequest, "INVALID_DATA", "parent_note_id 不能为空")
		return
	}
	includeDeleted := q.Get("include_deleted") == "true"
	query := `SELECT branch_id, parent_note_id, child_note_id, sort_order, is_deleted
		FROM branches WHERE parent_note_id = ?` + deletedClause("", includeDeleted) + `
		ORDER BY sort_order ASC, created_at ASC`
	rows, err := s.store.Read().QueryContext(r.Context(), query, parent)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	defer rows.Close()
	branches := []Branch{}
	for rows.Next() {
		var b Branch
		var isDeleted int
		if err := rows.Scan(&b.BranchID, &b.ParentNoteID, &b.ChildNoteID, &b.SortOrder, &isDeleted); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
			return
		}
		b.IsDeleted = isDeleted == 1
		branches = append(branches, b)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"branches": branches})
}

func (s *Server) handleListAttributes(w http.ResponseWriter, r *http.Request) {
	noteID := r.PathValue("note_id")
	rows, err := s.store.Read().QueryContext(r.Context(), `SELECT attribute_id, note_id, attr_type, name, value, is_inherited
		FROM attributes WHERE note_id = ? AND is_deleted = 0
		ORDER BY attr_type ASC, name ASC`, noteID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	defer rows.Close()
	attrs := []Attribute{}
	for rows.Next() {
		var a Attribute
		var isInherited int
		if err := rows.Scan(&a.AttributeID, &a.NoteID, &a.AttrType, &a.Name, &a.Value, &isInherited); err != nil {
			writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
			return
		}
		a.IsInherited = isInherited == 1
		attrs = append(attrs, a)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"attributes": attrs})
}
