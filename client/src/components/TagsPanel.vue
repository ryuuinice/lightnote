<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useNotesStore } from '../store/notes'
import type { Tag } from '../types'
import { ipc } from '../api/ipc'

const store = useNotesStore()
const tags = ref<Tag[]>([])

onMounted(async () => {
  tags.value = await ipc.invoke('tags.list')
})

async function onAdd(): Promise<void> {
  if (!store.currentNote) return
  const name = prompt('标签名')
  if (!name) return
  await ipc.invoke('tags.add', { noteId: store.currentNote.noteId, name })
  tags.value = await ipc.invoke('tags.list')
}
</script>

<template>
  <div class="tags">
    <div v-for="t in tags" :key="t.name" class="tag">
      <span>#{{ t.name }}</span>
      <span class="count">{{ t.noteCount }}</span>
    </div>
    <button v-if="store.currentNote" class="add" @click="onAdd">＋ 添加标签</button>
  </div>
</template>

<style scoped>
.tag {
  display: flex;
  justify-content: space-between;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.tag:hover {
  background: #eceff3;
}

.count {
  color: #999;
  font-size: 11px;
}

.add {
  margin-top: 8px;
  border: none;
  background: none;
  color: #1a73e8;
  font-size: 12px;
}
</style>
