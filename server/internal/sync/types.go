package sync

import "encoding/json"

type Change struct {
	ChangeID       string          `json:"change_id"`
	OriginDeviceID string          `json:"origin_device_id,omitempty"`
	EntityType     string          `json:"entity_type"`
	EntityID       string          `json:"entity_id"`
	Operation      string          `json:"operation"`
	BaseVersion    int64           `json:"base_version"`
	Version        int64           `json:"version"`
	ContentHash    string          `json:"content_hash,omitempty"`
	Payload        json.RawMessage `json:"payload"`
	CreatedAt      int64           `json:"created_at,omitempty"`
}

type ResultStatus string

const (
	StatusApplied        ResultStatus = "APPLIED"
	StatusAlreadyApplied ResultStatus = "ALREADY_APPLIED"
	StatusConflict       ResultStatus = "CONFLICT"
	StatusInvalid        ResultStatus = "INVALID"
)

type Result struct {
	ChangeID       string       `json:"change_id"`
	Status         ResultStatus `json:"status"`
	ServerSequence *int64       `json:"server_sequence,omitempty"`
}

type PullChange struct {
	ServerSequence int64           `json:"server_sequence"`
	ChangeID       string          `json:"change_id"`
	OriginDeviceID string          `json:"origin_device_id"`
	EntityType     string          `json:"entity_type"`
	EntityID       string          `json:"entity_id"`
	Operation      string          `json:"operation"`
	Version        int64           `json:"version"`
	Payload        json.RawMessage `json:"payload"`
}

type PullResponse struct {
	Changes      []PullChange `json:"changes"`
	NextSequence int64        `json:"next_sequence"`
	HasMore      bool         `json:"has_more"`
}

type PushResponse struct {
	Results []Result `json:"results"`
}
