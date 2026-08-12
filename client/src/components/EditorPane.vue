<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import { useNotesStore } from '../store/notes'
import type { NoteMeta } from '../types'

const store = useNotesStore()
const textareaEl = ref<HTMLTextAreaElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const findText = ref('')
const findMsg = ref('')
const dragOver = ref(false)

const previewHtml = computed(() => marked.parse(store.currentContent || '') as string)

const title = ref('')

function onTitleChange(): void {
  store.updateTitle(title.value)
}

watch(
  () => store.currentNote?.noteId,
  async () => {
    title.value = store.currentNote?.title ?? ''
    await nextTick()
    textareaEl.value?.focus()
  },
  { immediate: true },
)

function doFind(): void {
  const text = findText.value
  const content = store.currentContent
  if (!text || !textareaEl.value) return
  const idx = content.indexOf(text)
  if (idx < 0) {
    findMsg.value = '未找到'
    return
  }
  findMsg.value = ''
  const el = textareaEl.value
  el.focus()
  el.setSelectionRange(idx, idx + text.length)
}

function onFilePicked(e: Event): void {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (file && store.currentNote) {
    readAndAttach(file)
  }
  input.value = ''
}

function readAndAttach(file: File): void {
  if (!store.currentNote) return
  file.arrayBuffer().then((buf) => {
    store.attachFile(store.currentNote!.noteId, file.name, file.type || 'application/octet-stream', buf)
  })
}

function onDrop(e: DragEvent): void {
  dragOver.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file && store.currentNote) readAndAttach(file)
}

function attachmentLabel(n: NoteMeta): string {
  return n.title.replace(/^📎 /, '')
}
</script>

<template>
  <div class="editor">
    <div v-if="store.currentNote?.noteType === 'folder'" class="folder-placeholder">
      <div class="folder-icon">📁</div>
      <div class="folder-title">{{ store.currentNote.title }}</div>
      <div class="folder-hint">这是一个目录，双击树中节点或在其下新建笔记</div>
    </div>
    <template v-else>
      <div class="toolbar">
        <input v-model="title" class="title-input" @change="onTitleChange" />
        <div class="modes">
          <button :class="{ active: store.editorMode === 'edit' }" @click="store.editorMode = 'edit'">编辑</button>
          <button :class="{ active: store.editorMode === 'split' }" @click="store.editorMode = 'split'">分栏</button>
          <button :class="{ active: store.editorMode === 'preview' }" @click="store.editorMode = 'preview'">预览</button>
        </div>
      </div>

      <div v-if="store.findVisible" class="findbar">
        <input v-model="findText" class="find-input" placeholder="当前笔记内查找" @keyup.enter="doFind" />
        <button class="find-btn" @click="doFind">查找</button>
        <span v-if="findMsg" class="find-msg">{{ findMsg }}</span>
        <button class="find-btn" @click="store.findVisible = false">✕</button>
      </div>

      <div v-if="store.editorMode === 'split'" class="split">
        <textarea
          ref="textareaEl"
          v-model="store.currentContent"
          class="editor-area"
          @input="store.updateContent(($event.target as HTMLTextAreaElement).value)"
        ></textarea>
        <article class="preview" v-html="previewHtml"></article>
      </div>
      <template v-else>
        <textarea
          v-if="store.editorMode === 'edit'"
          ref="textareaEl"
          v-model="store.currentContent"
          class="editor-area"
          @input="store.updateContent(($event.target as HTMLTextAreaElement).value)"
        ></textarea>
        <article v-else class="preview" v-html="previewHtml"></article>
      </template>

      <div class="attachments" :class="{ 'drag-over': dragOver }" @dragover.prevent="dragOver = true" @dragleave="dragOver = false" @drop.prevent="onDrop">
        <div class="attachments-header">
          <span>附件</span>
          <button class="attach-btn" @click="fileInput?.click()">＋ 添加</button>
          <input ref="fileInput" type="file" style="display: none" @change="onFilePicked" />
        </div>
        <div class="attachment-list">
          <div v-for="a in store.attachments.filter((n) => n.noteType === 'attachment')" :key="a.noteId" class="attachment-item">
            <span>📎 {{ attachmentLabel(a) }}</span>
          </div>
          <div v-if="store.attachments.filter((n) => n.noteType === 'attachment').length === 0" class="attachment-empty">
            拖入文件或将文件添加到此笔记
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 8px;
}

.folder-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 8px;
  color: #666;
}

.folder-icon {
  font-size: 56px;
}

.folder-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.folder-hint {
  font-size: 12px;
  color: #999;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f1f3;
}

.title-input {
  flex: 1;
  border: none;
  font-size: 16px;
  font-weight: 600;
  outline: none;
  background: transparent;
}

.modes button {
  border: 1px solid #e2e4e8;
  background: #fff;
  padding: 4px 12px;
  font-size: 12px;
  border-radius: 4px;
}

.modes button.active {
  background: #1a73e8;
  color: #fff;
  border-color: #1a73e8;
}

.findbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.find-input {
  flex: 0 0 220px;
  padding: 4px 8px;
  border: 1px solid #e2e4e8;
  border-radius: 4px;
  font-size: 13px;
}

.find-btn {
  border: 1px solid #e2e4e8;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  padding: 3px 10px;
}

.find-msg {
  font-size: 12px;
  color: #d93025;
}

.split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.editor-area {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  padding: 12px 0;
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 14px;
  line-height: 1.7;
  min-height: 0;
}

.preview {
  flex: 1;
  overflow: auto;
  padding: 12px 0;
  line-height: 1.7;
  border-left: 1px solid #f0f1f3;
  padding-left: 16px;
}

.attachments {
  border-top: 1px solid #f0f1f3;
  padding-top: 8px;
  max-height: 140px;
  overflow: auto;
}

.attachments.drag-over {
  outline: 2px dashed #1a73e8;
  background: #f5f9ff;
  padding: 8px;
  border-radius: 6px;
}

.attachments-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #666;
}

.attach-btn {
  border: 1px solid #e2e4e8;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  padding: 2px 10px;
}

.attachment-item {
  font-size: 13px;
  padding: 3px 0;
}

.attachment-empty {
  font-size: 12px;
  color: #bbb;
  padding: 4px 0;
}
</style>
