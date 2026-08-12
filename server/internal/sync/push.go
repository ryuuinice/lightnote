package sync

import (
	"context"

	"lightnote/server/internal/db"
)

type PushService struct {
	committer *Committer
}

func NewPushService(store *db.Store) *PushService {
	return &PushService{committer: NewCommitter(store)}
}

func (p *PushService) Push(ctx context.Context, deviceID string, changes []Change) ([]Result, error) {
	results := make([]Result, 0, len(changes))
	for i := range changes {
		r, err := p.committer.Commit(ctx, deviceID, &changes[i])
		if err != nil {
			return nil, err
		}
		results = append(results, *r)
	}
	return results, nil
}
