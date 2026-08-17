import type { Note, NoteMeta, TreeNode, SearchResult, Tag, Attribute, SyncStatus, SyncReport, Settings, Device, ConflictInfo } from '../types'

interface MockDb {
  notes: Map<string, Note & { content: string }>
  branches: Map<string, { branchId: string; parentNoteId: string; childNoteId: string; sortOrder: number }>
  attributes: Map<string, Attribute>
}

let seq = 0
function newId(prefix: string): string {
  seq += 1
  return `${prefix}-${seq.toString(36).padStart(4, '0')}`
}

const db: MockDb = {
  notes: new Map(),
  branches: new Map(),
  attributes: new Map(),
}

function seed(): void {
  const root = { noteId: 'root', title: '知识库', noteType: 'text', isDeleted: false, sortOrder: 0, version: 1, content: '' }
  db.notes.set(root.noteId, { ...root, createdAt: Date.now(), updatedAt: Date.now() })

  const seeds = [
    { id: 'n-linux', title: 'Linux 学习', parent: 'root', content: '# Linux 学习\n\n## Docker 网络\n\n- bridge\n- host\n- overlay\n' },
    { id: 'n-docker', title: 'Docker 网络', parent: 'n-linux', content: '# Docker 网络\n\nDocker 的三种网络模式：\n\n1. bridge（默认）\n2. host\n3. overlay\n' },
    { id: 'n-rust', title: 'Rust 笔记', parent: 'root', content: '# Rust 笔记\n\n## Ownership\n\n- 每个值有唯一所有者\n' },
    { id: 'n-sync', title: '同步协议设计', parent: 'root', content: '# 同步协议\n\n- Change Log 驱动\n- Server Sequence 定序\n- 最终一致性\n' },
  ]

  for (const s of seeds) {
    const now = Date.now()
    db.notes.set(s.id, { noteId: s.id, title: s.title, noteType: 'text', isDeleted: false, sortOrder: 0, version: 1, createdAt: now, updatedAt: now, content: s.content, blobId: `sha256:mock-${s.id}` })
    const branchId = newId('branch')
    db.branches.set(branchId, { branchId, parentNoteId: s.parent, childNoteId: s.id, sortOrder: 0 })
  }

  const tags: Array<[string, string]> = [['n-linux', 'linux'], ['n-linux', '技术'], ['n-docker', 'docker'], ['n-rust', 'rust'], ['n-sync', '设计']]
  for (const [noteId, name] of tags) {
    const attributeId = newId('attr')
    db.attributes.set(attributeId, { attributeId, noteId, attrType: 'label', name })
  }
}
seed()

function noteMeta(n: Note & { content: string }): NoteMeta {
  return { noteId: n.noteId, title: n.title, noteType: n.noteType, isDeleted: n.isDeleted, sortOrder: 0, version: n.version }
}

function childrenOf(parentNoteId: string, includeDeleted = false): NoteMeta[] {
  const items: NoteMeta[] = []
  for (const b of db.branches.values()) {
    if (b.parentNoteId === parentNoteId) {
      const note = db.notes.get(b.childNoteId)
      if (note && (includeDeleted || !note.isDeleted)) {
        items.push(noteMeta(note))
      }
    }
  }
  return items.sort((a, b) => (a.title < b.title ? -1 : 1))
}

function clone<T>(x: T): T {
  return JSON.parse(JSON.stringify(x)) as T
}

export const mockApi = {
  async 'notes.list'(params: { parentNoteId?: string; includeDeleted?: boolean }): Promise<NoteMeta[]> {
    return clone(childrenOf(params.parentNoteId ?? 'root', params.includeDeleted))
  },
  async 'notes.get'(params: { noteId: string }): Promise<Note> {
    const n = db.notes.get(params.noteId)
    if (!n) throw { code: 'NOTE_NOT_FOUND', message: '笔记不存在' }
    return clone(n as Note)
  },
  async 'notes.create'(params: { parentNoteId: string; title: string; noteType?: string }): Promise<NoteMeta> {
    const now = Date.now()
    const note: Note & { content: string } = {
      noteId: newId('note'),
      title: params.title || '未命名',
      noteType: params.noteType ?? 'text',
      isDeleted: false,
      sortOrder: 0,
      version: 1,
      createdAt: now,
      updatedAt: now,
      content: '',
    }
    db.notes.set(note.noteId, note)
    db.branches.set(newId('branch'), { branchId: newId('b'), parentNoteId: params.parentNoteId, childNoteId: note.noteId, sortOrder: 0 })
    return noteMeta(note)
  },
  async 'notes.update'(params: { noteId: string; title?: string }): Promise<NoteMeta> {
    const n = db.notes.get(params.noteId)
    if (!n) throw { code: 'NOTE_NOT_FOUND', message: '笔记不存在' }
    if (params.title !== undefined) n.title = params.title
    n.version += 1
    n.updatedAt = Date.now()
    return noteMeta(n)
  },
  async 'notes.delete'(params: { noteId: string }): Promise<void> {
    const n = db.notes.get(params.noteId)
    if (n) { n.isDeleted = true; n.version += 1; n.updatedAt = Date.now() }
    return
  },
  async 'notes.restore'(params: { noteId: string }): Promise<NoteMeta> {
    const n = db.notes.get(params.noteId)
    if (!n) throw { code: 'NOTE_NOT_FOUND', message: '笔记不存在' }
    n.isDeleted = false
    n.version += 1
    n.updatedAt = Date.now()
    return noteMeta(n)
  },
  async 'notes.saveContent'(params: { noteId: string; content: string }): Promise<string> {
    const n = db.notes.get(params.noteId)
    if (!n) throw { code: 'NOTE_NOT_FOUND', message: '笔记不存在' }
    n.content = params.content
    n.version += 1
    n.updatedAt = Date.now()
    n.blobId = `sha256:mock-${n.noteId}-${n.version}`
    return n.blobId ?? ''
  },
  async 'notes.getContent'(params: { noteId: string }): Promise<{ blobId: string; content: string | null }> {
    const n = db.notes.get(params.noteId)
    if (!n) throw { code: 'NOTE_NOT_FOUND', message: '笔记不存在' }
    return { blobId: n.blobId ?? '', content: n.content ?? '' }
  },
  async 'notes.attach'(params: { parentNoteId: string; name: string; mimeType: string; dataBase64: string }): Promise<NoteMeta> {
    const now = Date.now()
    const note: Note & { content: string } = {
      noteId: newId('note'),
      title: `📎 ${name}`,
      noteType: 'attachment',
      isDeleted: false,
      sortOrder: 0,
      version: 1,
      createdAt: now,
      updatedAt: now,
      content: '',
      blobId: `sha256:mock-attach-${params.name}-${params.dataBase64.length}`,
    }
    db.notes.set(note.noteId, note)
    db.branches.set(newId('branch'), { branchId: newId('b'), parentNoteId: params.parentNoteId, childNoteId: note.noteId, sortOrder: 0 })
    return noteMeta(note)
  },
  async 'tree.children'(params: { parentNoteId: string }): Promise<TreeNode[]> {
    return clone(childrenOf(params.parentNoteId).map((c) => ({ ...c, children: undefined })))
  },
  async 'tree.move'(params: { noteId: string; newParentNoteId: string; newSortOrder?: number }): Promise<void> {
    const b = [...db.branches.values()].find((x) => x.childNoteId === params.noteId)
    if (b) {
      b.parentNoteId = params.newParentNoteId
      if (params.newSortOrder !== undefined) b.sortOrder = params.newSortOrder
    }
    return
  },
  async 'search.query'(params: { query: string; limit?: number }): Promise<SearchResult[]> {
    const q = params.query.toLowerCase()
    const out: SearchResult[] = []
    for (const n of db.notes.values()) {
      if (n.isDeleted) continue
      const titleHit = n.title.toLowerCase().includes(q)
      const contentHit = n.content.toLowerCase().includes(q)
      const tagsHit = [...db.attributes.values()].filter((a) => a.noteId === n.noteId && a.name.toLowerCase().includes(q)).map((a) => a.name)
      if (titleHit || contentHit || tagsHit.length > 0) {
        const idx = n.content.toLowerCase().indexOf(q)
        const snippet = idx >= 0 ? n.content.slice(Math.max(0, idx - 20), idx + 40) : n.content.slice(0, 60)
        out.push({ noteId: n.noteId, title: n.title, snippet, matchedTags: tagsHit })
        if (out.length >= (params.limit ?? 20)) break
      }
    }
    return clone(out)
  },
  async 'sync.status'(): Promise<SyncStatus> {
    return { state: 'idle', lastSyncAt: Date.now(), pendingCount: 0, failedCount: 0 }
  },
  async 'sync.trigger'(): Promise<SyncReport> {
    return { pushed: 0, pulled: 0, invalid: 0, cursor: 0, pendingRemaining: 0, blobQueued: 0, blobUploadFailed: 0, blobDownloadFailed: 0, blobDownloaded: 0 }
  },
  async 'blobs.get'(_params: { blobId: string }): Promise<number[]> {
    return []
  },
  async 'blobs.exists'(_params: { blobId: string }): Promise<boolean> {
    return true
  },
  async 'blobs.download'(_params: { blobId: string }): Promise<void> {
    // mock：数据本就在内存，无需下载
  },
  async 'tags.list'(params: { noteId?: string }): Promise<Tag[]> {
    const counts = new Map<string, number>()
    for (const a of db.attributes.values()) {
      if (params.noteId && a.noteId !== params.noteId) continue
      counts.set(a.name, (counts.get(a.name) ?? 0) + 1)
    }
    return [...counts.entries()].map(([name, noteCount]) => ({ name, noteCount }))
  },
  async 'tags.add'(params: { noteId: string; name: string; value?: string }): Promise<Attribute> {
    const attribute: Attribute = { attributeId: newId('attr'), noteId: params.noteId, attrType: 'label', name: params.name, value: params.value }
    db.attributes.set(attribute.attributeId, attribute)
    return clone(attribute)
  },
  async 'tags.remove'(params: { attributeId: string }): Promise<void> {
    db.attributes.delete(params.attributeId)
    return
  },
  async 'trash.list'(): Promise<NoteMeta[]> {
    const out: NoteMeta[] = []
    for (const n of db.notes.values()) {
      if (n.isDeleted) out.push(noteMeta(n))
    }
    return clone(out)
  },
  async 'trash.empty'(): Promise<number> {
    let deleted = 0
    for (const n of db.notes.values()) {
      if (n.isDeleted) { db.notes.delete(n.noteId); deleted += 1 }
    }
    return deleted
  },
  async 'conflicts.list'(): Promise<ConflictInfo[]> {
    const out: ConflictInfo[] = []
    for (const n of db.notes.values()) {
      if (n.conflictOfNoteId) {
        out.push({ noteId: n.noteId, conflictOfNoteId: n.conflictOfNoteId, title: n.title, version: n.version, updatedAt: n.updatedAt ?? Date.now() })
      }
    }
    return clone(out)
  },
  async 'conflicts.resolve'(params: { conflictNoteId: string; action: 'keep_conflict' | 'discard_conflict' }): Promise<void> {
    if (params.action === 'discard_conflict') {
      db.notes.delete(params.conflictNoteId)
    } else {
      const c = db.notes.get(params.conflictNoteId)
      if (c && c.conflictOfNoteId) {
        const orig = db.notes.get(c.conflictOfNoteId)
        if (orig) {
          orig.title = c.title
          orig.content = c.content
          orig.version += 1
        }
        // 与真实后端（lightnote_core conflicts_resolve）一致：覆盖原笔记后副本删除
        db.notes.delete(params.conflictNoteId)
      }
    }
    return
  },
  async 'settings.get'(): Promise<Settings> {
    return { serverUrl: 'https://lightnote.example.com', autoSync: true, syncIntervalSec: 60 }
  },
  async 'settings.update'(params: Partial<Settings>): Promise<Settings> {
    return { serverUrl: params.serverUrl ?? 'https://lightnote.example.com', autoSync: params.autoSync ?? true, syncIntervalSec: params.syncIntervalSec ?? 60 }
  },
  async 'settings.logout'(): Promise<void> {
    return
  },
  async 'devices.list'(): Promise<Device[]> {
    return [{ deviceId: 'device-mock-1', deviceName: 'PC-Windows', deviceType: 'desktop', createdAt: Date.now(), lastSeen: Date.now() }]
  },
  async 'devices.revoke'(_params: { deviceId: string }): Promise<void> {
    return
  },
} satisfies Record<string, (params: never) => Promise<unknown>>
