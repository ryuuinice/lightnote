import type {
  Note,
  NoteMeta,
  TreeNode,
  SearchResult,
  Tag,
  Attribute,
  SyncStatus,
  Settings,
  Device,
  ConflictInfo,
} from '../types'

export interface IpcApi {
  'notes.list'(params: { parentNoteId?: string; includeDeleted?: boolean }): Promise<NoteMeta[]>
  'notes.get'(params: { noteId: string }): Promise<Note>
  'notes.create'(params: { parentNoteId: string; title: string; noteType?: string }): Promise<NoteMeta>
  'notes.update'(params: { noteId: string; title?: string }): Promise<NoteMeta>
  'notes.delete'(params: { noteId: string }): Promise<{ ok: true }>
  'notes.restore'(params: { noteId: string }): Promise<NoteMeta>
  'notes.saveContent'(params: { noteId: string; content: string }): Promise<{ blobId: string }>
  'notes.getContent'(params: { noteId: string }): Promise<{ blobId: string; content: string | null }>
  'notes.attach'(params: { parentNoteId: string; name: string; mimeType: string; data: number[] }): Promise<NoteMeta>
  'tree.children'(params: { parentNoteId: string }): Promise<TreeNode[]>
  'tree.move'(params: { noteId: string; newParentNoteId: string; newSortOrder?: number }): Promise<{ ok: true }>
  'search.query'(params: { query: string; limit?: number }): Promise<SearchResult[]>
  'sync.status'(): Promise<SyncStatus>
  'sync.trigger'(): Promise<{ started: boolean }>
  'blobs.get'(params: { blobId: string }): Promise<{ data: string }>
  'blobs.exists'(params: { blobId: string }): Promise<{ exists: boolean }>
  'tags.list'(params: { noteId?: string }): Promise<Tag[]>
  'tags.add'(params: { noteId: string; name: string; value?: string }): Promise<Attribute>
  'tags.remove'(params: { attributeId: string }): Promise<{ ok: true }>
  'trash.list'(): Promise<NoteMeta[]>
  'trash.empty'(): Promise<{ deleted: number }>
  'conflicts.list'(): Promise<ConflictInfo[]>
  'conflicts.resolve'(params: { conflictNoteId: string; action: 'keep_conflict' | 'discard_conflict' }): Promise<{ ok: true }>
  'settings.get'(): Promise<Settings>
  'settings.update'(params: Partial<Settings>): Promise<Settings>
  'settings.logout'(): Promise<{ ok: true }>
  'devices.list'(): Promise<Device[]>
  'devices.revoke'(params: { deviceId: string }): Promise<{ ok: true }>
}

export type IpcCommand = keyof IpcApi
