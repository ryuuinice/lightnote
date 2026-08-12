package sync

import (
	"context"
	"encoding/json"
	"fmt"

	"lightnote/server/internal/db"
)

type Puller struct {
	store *db.Store
}

func NewPuller(store *db.Store) *Puller {
	return &Puller{store: store}
}

func (p *Puller) Pull(ctx context.Context, after int64, limit int) (*PullResponse, error) {
	rows, err := p.store.Read().QueryContext(ctx, `SELECT server_sequence, change_id, origin_device_id,
			entity_type, entity_id, operation, version, payload
		FROM entity_changes
		WHERE server_sequence > ?
		ORDER BY server_sequence
		LIMIT ?`, after, limit+1)
	if err != nil {
		return nil, fmt.Errorf("query changes: %w", err)
	}
	defer rows.Close()
	changes := make([]PullChange, 0, limit)
	hasMore := false
	for rows.Next() {
		if len(changes) == limit {
			hasMore = true
			break
		}
		var c PullChange
		var payload string
		if err := rows.Scan(&c.ServerSequence, &c.ChangeID, &c.OriginDeviceID, &c.EntityType, &c.EntityID,
			&c.Operation, &c.Version, &payload); err != nil {
			return nil, fmt.Errorf("scan change: %w", err)
		}
		c.Payload = json.RawMessage(payload)
		changes = append(changes, c)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate changes: %w", err)
	}
	next := after
	if len(changes) > 0 {
		next = changes[len(changes)-1].ServerSequence
	}
	return &PullResponse{Changes: changes, NextSequence: next, HasMore: hasMore}, nil
}
