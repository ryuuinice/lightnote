import type {
  Note,
  NoteMeta,
  TreeNode,
  SearchResult,
  Tag,
  Attribute,
  SyncStatus,
  SyncReport,
  Settings,
  Device,
  ConflictInfo,
} from '../types'

export interface IpcApi {
  'notes.list'(params: { parentNoteId?: string; includeDeleted?: boolean }): Promise<NoteMeta[]>
  'notes.get'(params: { noteId: string }): Promise<Note>
  'notes.create'(params: { parentNoteId: string; title: string; noteType?: string }): Promise<NoteMeta>
  'notes.update'(params: { noteId: string; title?: string }): Promise<NoteMeta>
  'notes.delete'(params: { noteId: string }): Promise<void>
  'notes.restore'(params: { noteId: string }): Promise<NoteMeta>
  'notes.saveContent'(params: { noteId: string; content: string }): Promise<string /* blob_id */>
  'notes.getContent'(params: { noteId: string }): Promise<{ blobId: string; content: string | null }>
  'notes.attach'(params: { parentNoteId: string; name: string; mimeType: string; dataBase64: string }): Promise<NoteMeta>
  'tree.children'(params: { parentNoteId: string }): Promise<TreeNode[]>
  'tree.move'(params: { noteId: string; newParentNoteId: string; newSortOrder?: number }): Promise<void>
  'search.query'(params: { query: string; limit?: number }): Promise<SearchResult[]>
  'sync.status'(): Promise<SyncStatus>
  'sync.trigger'(): Promise<SyncReport>
  'blobs.get'(params: { blobId: string }): Promise<number[]>
  'blobs.exists'(params: { blobId: string }): Promise<boolean>
  'blobs.download'(params: { blobId: string }): Promise<void>
  'tags.list'(params: { noteId?: string }): Promise<Tag[]>
  'tags.add'(params: { noteId: string; name: string; value?: string }): Promise<Attribute>
  'tags.remove'(params: { attributeId: string }): Promise<void>
  'trash.list'(): Promise<NoteMeta[]>
  'trash.empty'(): Promise<number /* deleted */>
  'conflicts.list'(): Promise<ConflictInfo[]>
  'conflicts.resolve'(params: { conflictNoteId: string; action: 'keep_conflict' | 'discard_conflict' }): Promise<void>
  'settings.get'(): Promise<Settings>
  'settings.update'(params: Partial<Settings>): Promise<Settings>
  'settings.logout'(): Promise<void>
  'devices.list'(): Promise<Device[]>
  'devices.revoke'(params: { deviceId: string }): Promise<void>
}

export type IpcCommand = keyof IpcApi
