package blob

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/google/uuid"
)

const (
	ChunkMaxSize = 16 << 20
	MaxBlobSize  = 1 << 30
)

var (
	ErrNotFound       = errors.New("blob not found")
	ErrSessionMissing = errors.New("upload session not found")
	ErrRejected       = errors.New("content sha256 mismatch")
	ErrIncomplete     = errors.New("chunks missing or size mismatch")
)

type FileInfo struct {
	Size        int64
	MimeType    string
	StoragePath string
}

type Session struct {
	ID       string
	BlobID   string
	Size     int64
	MimeType string
	dir      string
	received map[int]struct{}
	sum      int64
	mu       sync.Mutex
}

type Store struct {
	root     string
	tmp      string
	mu       sync.Mutex
	sessions map[string]*Session
}

func NewStore(root string) (*Store, error) {
	if root == "" {
		return nil, fmt.Errorf("blob store root is required")
	}
	tmp := filepath.Join(root, "tmp")
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, fmt.Errorf("create blob root: %w", err)
	}
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return nil, fmt.Errorf("create blob tmp dir: %w", err)
	}
	entries, err := os.ReadDir(tmp)
	if err == nil {
		for _, e := range entries {
			_ = os.RemoveAll(filepath.Join(tmp, e.Name()))
		}
	}
	return &Store{root: root, tmp: tmp, sessions: make(map[string]*Session)}, nil
}

func ParseID(blobID string) (string, bool) {
	const prefix = "sha256:"
	if !strings.HasPrefix(blobID, prefix) {
		return "", false
	}
	h := blobID[len(prefix):]
	if len(h) != sha256.Size*2 {
		return "", false
	}
	for _, c := range h {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return "", false
		}
	}
	return h, true
}

func (s *Store) path(hex string) string {
	return filepath.Join(s.root, hex[:2], hex[2:4], hex)
}

func (s *Store) StoragePath(blobID string) (string, bool) {
	hex, ok := ParseID(blobID)
	if !ok {
		return "", false
	}
	return filepath.Join(hex[:2], hex[2:4], hex), true
}

func (s *Store) Exists(blobID string) bool {
	hex, ok := ParseID(blobID)
	if !ok {
		return false
	}
	_, err := os.Stat(s.path(hex))
	return err == nil
}

func (s *Store) Open(blobID string) (*os.File, error) {
	hex, ok := ParseID(blobID)
	if !ok {
		return nil, fmt.Errorf("invalid blob id: %w", ErrNotFound)
	}
	f, err := os.Open(s.path(hex))
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return f, nil
}

func (s *Store) Init(blobID string, size int64, mimeType string) (status, sessionID string, err error) {
	if size < 0 || size > MaxBlobSize {
		return "", "", fmt.Errorf("invalid size %d", size)
	}
	if s.Exists(blobID) {
		return "EXISTS", "", nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if sess, ok := s.sessions[blobID]; ok {
		return "CREATED", sess.ID, nil
	}
	dir, err := os.MkdirTemp(s.tmp, "sess-")
	if err != nil {
		return "", "", fmt.Errorf("create session dir: %w", err)
	}
	sess := &Session{
		ID:       uuid.NewString(),
		BlobID:   blobID,
		Size:     size,
		MimeType: mimeType,
		dir:      dir,
		received: make(map[int]struct{}),
	}
	s.sessions[blobID] = sess
	return "CREATED", sess.ID, nil
}

func (s *Store) PutChunk(blobID string, index int, r io.Reader) error {
	if index < 0 {
		return fmt.Errorf("invalid chunk index %d", index)
	}
	s.mu.Lock()
	sess, ok := s.sessions[blobID]
	s.mu.Unlock()
	if !ok {
		return ErrSessionMissing
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if _, done := sess.received[index]; done {
		return nil
	}
	path := filepath.Join(sess.dir, fmt.Sprintf("%d.chunk", index))
	f, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("create chunk file: %w", err)
	}
	n, err := io.Copy(f, r)
	cerr := f.Close()
	if err != nil {
		_ = os.Remove(path)
		return fmt.Errorf("write chunk: %w", err)
	}
	if cerr != nil {
		_ = os.Remove(path)
		return cerr
	}
	if sess.sum+n > sess.Size {
		_ = os.Remove(path)
		return fmt.Errorf("chunks exceed declared size %d", sess.Size)
	}
	sess.received[index] = struct{}{}
	sess.sum += n
	return nil
}

func (s *Store) Complete(blobID string) (FileInfo, error) {
	idHex, ok := ParseID(blobID)
	if !ok {
		return FileInfo{}, fmt.Errorf("invalid blob id: %w", ErrNotFound)
	}
	s.mu.Lock()
	sess, ok := s.sessions[blobID]
	s.mu.Unlock()
	if !ok {
		return FileInfo{}, ErrSessionMissing
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if s.Exists(blobID) {
		s.drop(sess)
		return FileInfo{Size: sess.Size, MimeType: sess.MimeType, StoragePath: shardPath(idHex)}, nil
	}
	indices := make([]int, 0, len(sess.received))
	for i := range sess.received {
		indices = append(indices, i)
	}
	sort.Ints(indices)
	for i, idx := range indices {
		if idx != i {
			s.drop(sess)
			return FileInfo{}, ErrIncomplete
		}
	}
	if sess.sum != sess.Size {
		s.drop(sess)
		return FileInfo{}, ErrIncomplete
	}
	fin, err := os.OpenFile(filepath.Join(sess.dir, "final"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return FileInfo{}, err
	}
	h := sha256.New()
	for _, idx := range indices {
		cf, err := os.Open(filepath.Join(sess.dir, fmt.Sprintf("%d.chunk", idx)))
		if err != nil {
			fin.Close()
			_ = os.Remove(fin.Name())
			return FileInfo{}, err
		}
		_, err = io.Copy(io.MultiWriter(fin, h), cf)
		cf.Close()
		if err != nil {
			fin.Close()
			_ = os.Remove(fin.Name())
			return FileInfo{}, err
		}
	}
	if err := fin.Close(); err != nil {
		_ = os.Remove(fin.Name())
		return FileInfo{}, err
	}
	if hex.EncodeToString(h.Sum(nil)) != idHex {
		_ = os.Remove(fin.Name())
		s.drop(sess)
		return FileInfo{}, ErrRejected
	}
	final := s.path(idHex)
	if err := os.MkdirAll(filepath.Dir(final), 0o755); err != nil {
		_ = os.Remove(fin.Name())
		return FileInfo{}, err
	}
	if err := os.Rename(fin.Name(), final); err != nil {
		_ = os.Remove(fin.Name())
		return FileInfo{}, err
	}
	s.drop(sess)
	return FileInfo{Size: sess.Size, MimeType: sess.MimeType, StoragePath: shardPath(idHex)}, nil
}

func shardPath(hex string) string {
	return filepath.Join(hex[:2], hex[2:4], hex)
}

func (s *Store) drop(sess *Session) {
	delete(s.sessions, sess.BlobID)
	_ = os.RemoveAll(sess.dir)
}
