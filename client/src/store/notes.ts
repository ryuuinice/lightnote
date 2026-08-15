import { defineStore } from 'pinia'
import type { Note, NoteMeta, TreeNode, SyncStatus, Settings } from '../types'
import { ipc } from '../api/ipc'

export const useNotesStore = defineStore('notes', {
  state: () => ({
    tree: [] as TreeNode[],
    notes: [] as NoteMeta[],
    currentNote: null as Note | null,
    currentContent: '',
    dirty: false,
    switching: false, // openNote 异步切换期间为 true：忽略编辑输入，防止被旧内容覆盖
    saveTimer: null as ReturnType<typeof setTimeout> | null,
    searchResults: [] as { noteId: string; title: string; snippet: string; matchedTags: string[] }[],
    trash: [] as NoteMeta[],
    sync: { state: 'idle', pendingCount: 0, failedCount: 0 } as SyncStatus,
    settings: { serverUrl: '', autoSync: true, syncIntervalSec: 60 } as Settings,
    expanded: new Set<string>(['root']),
    selectedNoteId: '' as string,
    selectedTreeParent: 'root' as string,
    activePanel: 'tree' as 'tree' | 'tags' | 'search' | 'trash',
    mobilePane: 'navigation' as 'navigation' | 'notes' | 'editor',
    paletteVisible: false,
    editorMode: 'edit' as 'edit' | 'split' | 'preview',
    findVisible: false,
    attachments: [] as NoteMeta[],
  }),

  getters: {
    selectedNote(): NoteMeta | null {
      return this.notes.find((n) => n.noteId === this.selectedNoteId) ?? null
    },
  },

  actions: {
    async loadTree(): Promise<void> {
      this.tree = await ipc.invoke('tree.children', { parentNoteId: 'root' })
      const refreshExpanded = async (nodes: TreeNode[]): Promise<void> => {
        for (const node of nodes) {
          if (!this.expanded.has(node.noteId)) continue
          node.children = await ipc.invoke('tree.children', { parentNoteId: node.noteId })
          await refreshExpanded(node.children)
        }
      }
      await refreshExpanded(this.tree)
    },

    async expandNode(parentNoteId: string): Promise<void> {
      this.expanded.add(parentNoteId)
      const children = await ipc.invoke('tree.children', { parentNoteId })
      if (parentNoteId === 'root') {
        this.tree = children
        return
      }
      const walk = (nodes: TreeNode[]): boolean => {
        for (const n of nodes) {
          if (n.noteId === parentNoteId) {
            n.children = children
            return true
          }
          if (n.children && walk(n.children)) return true
        }
        return false
      }
      walk(this.tree)
    },

    async loadNotes(parentNoteId?: string): Promise<void> {
      this.notes = await ipc.invoke('notes.list', { parentNoteId })
    },

    async openNote(noteId: string): Promise<void> {
      if (this.currentNote?.noteId === noteId) return
      if (this.switching) return
      this.switching = true
      try {
        if (this.saveTimer) {
          clearTimeout(this.saveTimer)
          this.saveTimer = null
        }
        if (this.currentNote && this.dirty && !(await this.saveContent())) return
        this.selectedNoteId = noteId
        this.currentNote = await ipc.invoke('notes.get', { noteId })
        const { content } = await ipc.invoke('notes.getContent', { noteId })
        this.currentContent = content ?? ''
        this.dirty = false
        this.mobilePane = 'editor'
        await this.loadAttachments(noteId)
      } finally {
        this.switching = false
      }
    },

    async createNote(parentNoteId: string, title: string): Promise<void> {
      await ipc.invoke('notes.create', { parentNoteId, title })
      await this.loadTree()
      await this.loadNotes(parentNoteId)
    },

    async createAndOpen(parentNoteId: string): Promise<void> {
      const created = await ipc.invoke('notes.create', { parentNoteId, title: '未命名' })
      await this.loadTree()
      await this.loadNotes(parentNoteId)
      await this.openNote(created.noteId)
    },

    async createFolder(parentNoteId: string): Promise<void> {
      await ipc.invoke('notes.create', { parentNoteId, title: '新目录', noteType: 'folder' })
      await this.loadTree()
      await this.loadNotes(parentNoteId)
    },

    async selectTreeParent(noteId: string): Promise<void> {
      this.selectedTreeParent = noteId
      await this.loadNotes(noteId)
      this.mobilePane = 'notes'
    },

    async moveNote(noteId: string, newParentNoteId: string): Promise<void> {
      if (noteId === newParentNoteId) return
      await ipc.invoke('tree.move', { noteId, newParentNoteId })
      await this.loadTree()
      await this.loadNotes(this.selectedTreeParent)
    },

    updateContent(content: string): void {
      if (this.switching) return // 切换加载中：旧笔记的迟到输入不覆盖待加载内容
      this.currentContent = content
      this.dirty = true
      if (this.saveTimer) clearTimeout(this.saveTimer)
      this.saveTimer = setTimeout(async () => {
        this.saveTimer = null
        await this.saveContent()
      }, 800)
    },

    async saveContent(): Promise<boolean> {
      if (!this.currentNote || !this.dirty) return true
      const noteId = this.currentNote.noteId
      const content = this.currentContent
      this.dirty = false
      try {
        await ipc.invoke('notes.saveContent', { noteId, content })
        return true
      } catch {
        if (this.currentNote?.noteId === noteId) this.dirty = true
        return false
      }
    },

    async saveNow(): Promise<boolean> {
      if (!this.currentNote) return true
      this.dirty = true
      return this.saveContent()
    },

    async loadAttachments(noteId: string): Promise<void> {
      this.attachments = await ipc.invoke('notes.list', { parentNoteId: noteId })
    },

    async attachFile(parentNoteId: string, name: string, mimeType: string, data: ArrayBuffer): Promise<void> {
      const bytes = Array.from(new Uint8Array(data))
      const created = await ipc.invoke('notes.attach', { parentNoteId, name, mimeType, data: bytes })
      await this.loadTree()
      await this.loadNotes(parentNoteId)
      await this.loadAttachments(parentNoteId)
      if (created.noteId) void created.noteId
    },

    async updateTitle(title: string): Promise<void> {
      if (!this.currentNote) return
      const noteId = this.currentNote.noteId
      if (this.currentNote.title === title) return
      const updated = await ipc.invoke('notes.update', { noteId, title })
      if (this.currentNote?.noteId === noteId) this.currentNote.title = updated.title
      const listNote = this.notes.find((note) => note.noteId === noteId)
      if (listNote) listNote.title = updated.title
    },

    async deleteNote(noteId: string): Promise<void> {
      if (this.saveTimer) {
        clearTimeout(this.saveTimer)
        this.saveTimer = null
      }
      await ipc.invoke('notes.delete', { noteId })
      await this.loadTree()
      await this.loadNotes(this.selectedTreeParent)
      if (this.selectedNoteId === noteId) {
        this.currentNote = null
        this.currentContent = ''
      }
    },

    async restoreNote(noteId: string): Promise<void> {
      await ipc.invoke('notes.restore', { noteId })
      await this.loadTrash()
      await this.loadTree()
    },

    async loadTrash(): Promise<void> {
      this.trash = await ipc.invoke('trash.list')
    },

    async search(query: string): Promise<void> {
      if (!query.trim()) {
        this.searchResults = []
        return
      }
      this.searchResults = await ipc.invoke('search.query', { query })
    },

    async triggerSync(): Promise<void> {
      this.sync.state = 'pushing'
      try {
        await ipc.invoke('sync.trigger')
        this.sync.state = 'idle'
        // GUI-003a：同步可能拉入远端变更，立即刷新 UI（树/列表/当前笔记），
        // 无需用户切换笔记即可看到远端内容
        await this.refreshAfterSync()
      } catch (error) {
        this.sync.state = 'error'
        throw error
      }
    },

    /// 同步成功后重载 UI 状态。当前笔记若有未保存本地编辑（dirty）则跳过正文重载，
    /// 避免覆盖用户正在输入的内容。
    async refreshAfterSync(): Promise<void> {
      const selectedParent = this.selectedTreeParent
      await this.loadTree()
      await this.loadNotes(selectedParent === 'root' ? undefined : selectedParent)
      if (this.activePanel === 'trash') await this.loadTrash()
      const noteId = this.currentNote?.noteId
      if (noteId && !this.dirty) {
        try {
          const { content } = await ipc.invoke('notes.getContent', { noteId })
          const note = await ipc.invoke('notes.get', { noteId })
          if (this.currentNote?.noteId === noteId && !this.dirty) {
            this.currentNote = note
            this.currentContent = content ?? ''
            await this.loadAttachments(noteId)
          }
        } catch {
          // 笔记可能被远端删除：忽略，下一次列表刷新会移除
        }
      }
    },

    async loadSettings(): Promise<void> {
      this.settings = await ipc.invoke('settings.get')
    },

    async updateSettings(patch: Partial<Settings>): Promise<void> {
      this.settings = await ipc.invoke('settings.update', patch)
    },
  },
})
