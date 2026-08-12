<script setup lang="ts">
import { ref } from 'vue'
import { useNotesStore } from '../store/notes'

const store = useNotesStore()
const query = ref('')

async function onSearch(): Promise<void> {
  await store.search(query.value)
}

async function openNote(noteId: string): Promise<void> {
  await store.openNote(noteId)
}
</script>

<template>
  <div class="search">
    <input v-model="query" class="box" placeholder="搜索笔记…" @keyup.enter="onSearch" />
    <button class="btn" @click="onSearch">搜索</button>
    <div v-for="r in store.searchResults" :key="r.noteId" class="result" @click="openNote(r.noteId)">
      <div class="title">{{ r.title }}</div>
      <div class="snippet">{{ r.snippet }}</div>
      <div class="tags">
        <span v-for="t in r.matchedTags" :key="t" class="tag">#{{ t }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.box {
  width: 100%;
  padding: 6px 8px;
  border: 1px solid #e2e4e8;
  border-radius: 4px;
  margin-bottom: 8px;
}

.btn {
  width: 100%;
  padding: 4px;
  border: 1px solid #e2e4e8;
  background: #fff;
  border-radius: 4px;
  margin-bottom: 8px;
}

.result {
  padding: 6px;
  border-radius: 4px;
  cursor: pointer;
}

.result:hover {
  background: #eceff3;
}

.snippet {
  font-size: 12px;
  color: #666;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag {
  font-size: 11px;
  color: #1a73e8;
  margin-right: 6px;
}
</style>
