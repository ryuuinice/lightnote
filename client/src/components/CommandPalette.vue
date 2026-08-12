<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useNotesStore } from '../store/notes'
import { ipc } from '../api/ipc'
import type { SearchResult } from '../types'

const store = useNotesStore()
const query = ref('')
const noteResults = ref<SearchResult[]>([])
const activeIndex = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

interface PaletteItem {
  kind: 'command' | 'note'
  label: string
  hint?: string
  run: () => void
}

const commands: PaletteItem[] = [
  { kind: 'command', label: '新建笔记', hint: 'Ctrl+N', run: () => store.createAndOpen(store.selectedTreeParent) },
  { kind: 'command', label: '新建目录', hint: '', run: () => store.createFolder(store.selectedTreeParent) },
  { kind: 'command', label: '立即同步', hint: '', run: () => store.triggerSync() },
  { kind: 'command', label: '切换编辑/预览', hint: 'Ctrl+E', run: () => (store.editorMode = store.editorMode === 'preview' ? 'edit' : 'preview') },
  { kind: 'command', label: '强制保存', hint: 'Ctrl+S', run: () => store.saveNow() },
  { kind: 'command', label: '打开回收站', hint: '', run: () => (store.activePanel = 'trash') },
  { kind: 'command', label: '打开设置', hint: '', run: () => document.dispatchEvent(new CustomEvent('open-settings')) },
]

const items = ref<PaletteItem[]>([])

function rebuild(): void {
  const q = query.value.trim()
  const list: PaletteItem[] = [...commands]
  if (q) {
    for (const r of noteResults.value) {
      list.push({ kind: 'note', label: r.title, hint: r.snippet, run: () => store.openNote(r.noteId) })
    }
  }
  items.value = list
  activeIndex.value = 0
}

let timer: ReturnType<typeof setTimeout> | null = null

watch(query, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    const q = query.value.trim()
    noteResults.value = q ? await ipc.invoke('search.query', { query: q, limit: 8 }) : []
    rebuild()
  }, 200)
})

onMounted(() => {
  rebuild()
  inputEl.value?.focus()
})

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    store.paletteVisible = false
    return
  }
  if (e.key === 'ArrowDown') {
    activeIndex.value = Math.min(items.value.length - 1, activeIndex.value + 1)
    e.preventDefault()
    return
  }
  if (e.key === 'ArrowUp') {
    activeIndex.value = Math.max(0, activeIndex.value - 1)
    e.preventDefault()
    return
  }
  if (e.key === 'Enter' && items.value[activeIndex.value]) {
    const item = items.value[activeIndex.value]
    store.paletteVisible = false
    item.run()
  }
}

function runItem(item: PaletteItem): void {
  store.paletteVisible = false
  item.run()
}
</script>

<template>
  <div class="mask" @click.self="store.paletteVisible = false">
    <div class="palette">
      <input
        ref="inputEl"
        v-model="query"
        class="input"
        placeholder="输入搜索笔记，或选择命令…"
        @keydown="onKeydown"
      />
      <div class="items">
        <div
          v-for="(item, i) in items"
          :key="item.kind + item.label"
          class="item"
          :class="{ active: i === activeIndex }"
          @mousedown.prevent="runItem(item)"
        >
          <span class="icon">{{ item.kind === 'command' ? '⚡' : '📄' }}</span>
          <span class="label">{{ item.label }}</span>
          <span v-if="item.hint" class="hint">{{ item.hint }}</span>
        </div>
        <div v-if="items.length === 0" class="empty">无结果</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 15vh;
  z-index: 200;
}

.palette {
  width: 520px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.2);
  overflow: hidden;
}

.input {
  width: 100%;
  border: none;
  outline: none;
  padding: 14px 16px;
  font-size: 15px;
  border-bottom: 1px solid #eee;
}

.items {
  max-height: 320px;
  overflow: auto;
  padding: 6px;
}

.item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.item.active {
  background: #e8f0fe;
}

.icon {
  width: 18px;
}

.label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hint {
  font-size: 12px;
  color: #999;
  max-width: 50%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty {
  padding: 12px;
  text-align: center;
  color: #999;
  font-size: 13px;
}
</style>
