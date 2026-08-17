export interface NoteMeta {
  noteId: string
  title: string
  noteType: string
  isDeleted: boolean
  sortOrder: number
  version: number
}

export interface Note extends NoteMeta {
  blobId?: string
  updatedAt?: number
  updatedBy?: string
  createdAt?: number
  conflictOfNoteId?: string
}

export interface TreeNode extends NoteMeta {
  children?: TreeNode[]
}

export interface SearchResult {
  noteId: string
  title: string
  snippet: string
  matchedTags: string[]
}

export interface Tag {
  name: string
  noteCount: number
}

export interface Attribute {
  attributeId: string
  noteId: string
  attrType: 'label' | 'relation' | 'meta'
  name: string
  value?: string
}

export interface SyncStatus {
  state: 'idle' | 'preparing' | 'pushing' | 'pulling' | 'applying' | 'completed' | 'error'
  lastSyncAt?: number
  pendingCount: number
  failedCount: number
}

export interface SyncReport {
  pushed: number
  pulled: number
  invalid: number
  cursor: number
  pendingRemaining: number
  blobQueued: number
  blobUploadFailed: number
  blobDownloadFailed: number
  blobDownloaded: number
}

export interface Settings {
  serverUrl: string
  autoSync: boolean
  syncIntervalSec: number
  lastSyncStatus?: string
}

export interface Device {
  deviceId: string
  deviceName: string
  deviceType?: string
  lastSeen?: number
  revokedAt?: number
  createdAt: number
}

export interface ConflictInfo {
  noteId: string
  conflictOfNoteId: string
  title: string
  version: number
  updatedAt: number
  updatedBy?: string
}

export interface AppError {
  code: string
  message: string
}
