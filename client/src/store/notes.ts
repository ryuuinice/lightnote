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
    /// 正文懒下载未完成：currentContent 为空串仅是占位，绝非真实内容。
    /// 此状态下禁止保存，否则会用空内容覆盖远端正文。
    contentMissing: false,
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
        const { content, blobId } = await ipc.invoke('notes.getContent', { noteId })
        if (content === null && blobId) {
          // Lazy Download 缺失：标记缺失态，后台补拉，绝不把 null 当空内容参与保存
          this.currentContent = ''
          this.contentMissing = true
          void this.fetchMissingContent(noteId, blobId)
        } else {
          this.currentContent = content ?? ''
          this.contentMissing = false
        }
        this.dirty = false
        this.mobilePane = 'editor'
        await this.loadAttachments(noteId)
      } catch (e) {
        window.showError(e)
      } finally {
        this.switching = false
      }
    },

    /// 按需补拉缺失正文（Lazy Download）。成功后若用户未编辑则填入。
    async fetchMissingContent(noteId: string, blobId: string): Promise<void> {
      try {
        await ipc.invoke('blobs.download', { blobId })
        const { content } = await ipc.invoke('notes.getContent', { noteId })
        if (this.currentNote?.noteId === noteId && this.contentMissing && !this.dirty) {
          this.currentContent = content ?? ''
        }
        this.contentMissing = this.currentNote?.noteId === noteId ? false : this.contentMissing
      } catch {
        // 离线/服务端不可达：保持缺失态，EditorPane 显示占位提示，不自动重试
      }
    },

    async createNote(parentNoteId: string, title: string): Promise<void> {
      await ipc.invoke('notes.create', { parentNoteId, title })
      await this.loadTree()
      await this.loadNotes(parentNoteId)
    },

    async createAndOpen(parentNoteId: string): Promise<void> {
      try {
        const created = await ipc.invoke('notes.create', { parentNoteId, title: '未命名' })
        await this.loadTree()
        await this.loadNotes(parentNoteId)
        await this.openNote(created.noteId)
      } catch (e) {
        window.showError(e)
      }
    },

    async createFolder(parentNoteId: string): Promise<void> {
      try {
        await ipc.invoke('notes.create', { parentNoteId, title: '新目录', noteType: 'folder' })
        await this.loadTree()
        await this.loadNotes(parentNoteId)
      } catch (e) {
        window.showError(e)
      }
    },

    async selectTreeParent(noteId: string): Promise<void> {
      this.selectedTreeParent = noteId
      await this.loadNotes(noteId)
      this.mobilePane = 'notes'
    },

    async moveNote(noteId: string, newParentNoteId: string): Promise<void> {
      if (noteId === newParentNoteId) return
      try {
        await ipc.invoke('tree.move', { noteId, newParentNoteId })
      } catch (e) {
        window.showError(e)
        return
      }
      await this.loadTree()
      await this.loadNotes(this.selectedTreeParent)
    },

    updateContent(content: string): void {
      if (this.switching) return // 切换加载中：旧笔记的迟到输入不覆盖待加载内容
      this.currentContent = content
      this.contentMissing = false // 用户真实输入即视为已有内容
      this.dirty = true
      if (this.saveTimer) clearTimeout(this.saveTimer)
      this.saveTimer = setTimeout(async () => {
        this.saveTimer = null
        await this.saveContent()
      }, 800)
    },

    async saveContent(): Promise<boolean> {
      if (!this.currentNote || !this.dirty) return true
      // 缺失态的空串不是内容：保存会覆盖远端正文，禁止
      if (this.contentMissing) return false
      const noteId = this.currentNote.noteId
      const content = this.currentContent
      this.dirty = false
      try {
        await ipc.invoke('notes.saveContent', { noteId, content })
        return true
      } catch (e) {
        if (this.currentNote?.noteId === noteId) this.dirty = true
        window.showError(new Error(`保存失败（内容仍在编辑区，可重试 Ctrl+S）：${String((e as { message?: string })?.message ?? e)}`))
        return false
      }
    },

    async saveNow(): Promise<boolean> {
      if (!this.currentNote) return true
      if (this.contentMissing) {
        // 缺失态没有可保存的本地内容；提示后由调用方决定是否仍要离开
        window.showError(new Error('正文尚未从服务器下载完成，无法保存'))
        return false
      }
      if (!this.dirty) return true
      return this.saveContent()
    },

    async loadAttachments(noteId: string): Promise<void> {
      this.attachments = await ipc.invoke('notes.list', { parentNoteId: noteId })
    },

    async attachFile(parentNoteId: string, name: string, mimeType: string, data: ArrayBuffer): Promise<void> {
      // base64 分块编码（大文件一次性 String.fromCharCode 会爆调用栈）
      const bytes = new Uint8Array(data)
      let binary = ''
      const chunk = 0x8000
      for (let i = 0; i < bytes.length; i += chunk) {
        binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
      }
      const dataBase64 = btoa(binary)
      try {
        await ipc.invoke('notes.attach', { parentNoteId, name, mimeType, dataBase64 })
        await this.loadTree()
        await this.loadNotes(parentNoteId)
        await this.loadAttachments(parentNoteId)
      } catch (e) {
        window.showError(new Error(`附件上传失败：${String((e as { message?: string })?.message ?? e)}`))
      }
    },

    async updateTitle(title: string): Promise<void> {
      if (!this.currentNote) return
      const noteId = this.currentNote.noteId
      if (this.currentNote.title === title) return
      try {
        const updated = await ipc.invoke('notes.update', { noteId, title })
        if (this.currentNote?.noteId === noteId) this.currentNote.title = updated.title
        const listNote = this.notes.find((note) => note.noteId === noteId)
        if (listNote) listNote.title = updated.title
        this.syncTreeTitle(noteId, updated.title)
      } catch (e) {
        window.showError(e)
      }
    },

    /// 树内节点改名后同步标题显示（不动层级结构）
    syncTreeTitle(noteId: string, title: string): void {
      const walk = (nodes: TreeNode[]): boolean => {
        for (const n of nodes) {
          if (n.noteId === noteId) {
            n.title = title
            return true
          }
          if (n.children && walk(n.children)) return true
        }
        return false
      }
      walk(this.tree)
    },

    /// 任意节点（含目录）改名：F2 / 右键菜单入口统一走这里
    async renameNote(noteId: string, title: string): Promise<void> {
      if (!title.trim()) return
      try {
        await ipc.invoke('notes.update', { noteId, title: title.trim() })
        this.syncTreeTitle(noteId, title.trim())
        if (this.currentNote?.noteId === noteId) this.currentNote.title = title.trim()
        const listNote = this.notes.find((note) => note.noteId === noteId)
        if (listNote) listNote.title = title.trim()
      } catch (e) {
        window.showError(e)
      }
    },

    async deleteNote(noteId: string): Promise<void> {
      // 仅当删除的是当前笔记时才丢弃其待保存内容；删别的笔记不动当前笔记的保存定时器
      const isCurrent = this.selectedNoteId === noteId
      if (isCurrent && this.saveTimer) {
        clearTimeout(this.saveTimer)
        this.saveTimer = null
        this.dirty = false
      }
      try {
        await ipc.invoke('notes.delete', { noteId })
      } catch (e) {
        window.showError(e)
        return
      }
      await this.loadTree()
      await this.loadNotes(this.selectedTreeParent)
      if (isCurrent) {
        this.currentNote = null
        this.currentContent = ''
        this.contentMissing = false
      }
    },

    async restoreNote(noteId: string): Promise<void> {
      try {
        await ipc.invoke('notes.restore', { noteId })
      } catch (e) {
        window.showError(e)
        return
      }
      await this.loadTrash()
      await this.loadTree()
      // 恢复的笔记可能落在当前列表所属目录下，刷新让用户立刻看到
      await this.loadNotes(this.selectedTreeParent)
    },

    async loadTrash(): Promise<void> {
      try {
        this.trash = await ipc.invoke('trash.list')
      } catch (e) {
        window.showError(e)
      }
    },

    async search(query: string): Promise<void> {
      if (!query.trim()) {
        this.searchResults = []
        return
      }
      try {
        this.searchResults = await ipc.invoke('search.query', { query })
      } catch (e) {
        window.showError(e)
      }
    },

    async triggerSync(): Promise<void> {
      this.sync.state = 'pushing'
      try {
        const report = await ipc.invoke('sync.trigger')
        this.sync.state = 'idle'
        // 仅在拉到远端变更或懒下载补全时刷新 UI（树/列表/当前笔记），
        // 避免空同步的全量重载闪烁
        if (report.pulled > 0 || report.blobDownloaded > 0) {
          await this.refreshAfterSync()
        }
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
      try {
        this.settings = await ipc.invoke('settings.update', patch)
      } catch (e) {
        window.showError(e)
      }
    },

    /// 会话结束（登出/吊销/refresh 失败）后清空全部 UI 状态，回到登录页。
    /// 覆盖此前 onLogout 手工清不完整的字段（trash/searchResults/attachments 等）。
    resetAll(): void {
      if (this.saveTimer) {
        clearTimeout(this.saveTimer)
        this.saveTimer = null
      }
      this.tree = []
      this.notes = []
      this.currentNote = null
      this.currentContent = ''
      this.dirty = false
      this.contentMissing = false
      this.searchResults = []
      this.trash = []
      this.attachments = []
      this.expanded = new Set<string>(['root'])
      this.selectedNoteId = ''
      this.selectedTreeParent = 'root'
      this.activePanel = 'tree'
      this.mobilePane = 'navigation'
      this.paletteVisible = false
      this.findVisible = false
      this.sync = { state: 'idle', pendingCount: 0, failedCount: 0 }
    },
  },
})
