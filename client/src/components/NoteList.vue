<script setup lang="ts">
import { useNotesStore } from '../store/notes'
import type { NoteMeta } from '../types'

const store = useNotesStore()

function onSelect(note: NoteMeta): void {
  store.openNote(note.noteId)
}

async function onDelete(note: NoteMeta): Promise<void> {
  if (confirm(`删除「${note.title}」？`)) await store.deleteNote(note.noteId)
}
</script>

<template>
  <div class="list">
    <div class="list-header">
      <span class="list-title">笔记</span>
      <button class="new" title="在选中目录下新建笔记" @click="store.createAndOpen(store.selectedTreeParent)">＋ 新建</button>
    </div>
    <div
      v-for="note in store.notes"
      :key="note.noteId"
      class="item"
      :class="{ active: note.noteId === store.selectedNoteId }"
      @click="onSelect(note)"
    >
      <div class="title">{{ note.title || '未命名' }}</div>
      <button class="del" title="删除" @click.stop="onDelete(note)">🗑</button>
    </div>
    <div v-if="store.notes.length === 0" class="empty">暂无笔记</div>
  </div>
</template>

<style scoped>
.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-bottom: 1px solid #f0f1f3;
}

.list-title {
  font-weight: 600;
  font-size: 13px;
}

.new {
  border: 1px solid #1a73e8;
  background: #1a73e8;
  color: #fff;
  border-radius: 4px;
  font-size: 12px;
  padding: 3px 10px;
}

.item {
  display: flex;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid #f0f1f3;
  cursor: pointer;
}

.item:hover {
  background: #f6f8fa;
}

.item.active {
  background: #e8f0fe;
}

.title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.del {
  border: none;
  background: none;
  font-size: 12px;
  visibility: hidden;
  opacity: 0.7;
}

.item:hover .del {
  visibility: visible;
}

.empty {
  padding: 16px;
  color: #999;
  text-align: center;
}
</style>
