<script setup lang="ts">
import { useNotesStore } from '../store/notes'

const store = useNotesStore()

async function onRestore(noteId: string): Promise<void> {
  await store.restoreNote(noteId)
}
</script>

<template>
  <div class="trash">
    <div v-for="n in store.trash" :key="n.noteId" class="item">
      <span class="title">{{ n.title || '未命名' }}</span>
      <button class="restore" @click="onRestore(n.noteId)">恢复</button>
    </div>
    <div v-if="store.trash.length === 0" class="empty">回收站为空</div>
  </div>
</template>

<style scoped>
.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  border-radius: 4px;
}

.item:hover {
  background: #eceff3;
}

.title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.restore {
  border: none;
  background: none;
  color: #1a73e8;
  font-size: 12px;
}

.empty {
  color: #999;
  font-size: 12px;
  text-align: center;
  margin-top: 16px;
}
</style>
