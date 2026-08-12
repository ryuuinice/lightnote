<script setup lang="ts">
import { ref } from 'vue'
import { useNotesStore } from '../store/notes'
import type { TreeNode } from '../types'
import TreeNodeItem from './TreeNodeItem.vue'

const store = useNotesStore()

async function onCreateNote(node: TreeNode): Promise<void> {
  const title = prompt('新笔记标题', '未命名')
  if (!title) return
  const { ipc } = await import('../api/ipc')
  const created = await ipc.invoke('notes.create', { parentNoteId: node.noteId, title })
  await store.loadTree()
  await store.loadNotes(node.noteId)
  await store.openNote(created.noteId)
}

async function onCreateFolder(): Promise<void> {
  const title = prompt('新目录名称', '新目录')
  if (title) {
    await ipcCreateFolder(title)
  }
}

async function ipcCreateFolder(title: string): Promise<void> {
  const { ipc } = await import('../api/ipc')
  await ipc.invoke('notes.create', { parentNoteId: store.selectedTreeParent, title, noteType: 'folder' })
  await store.loadTree()
  await store.loadNotes(store.selectedTreeParent)
}

const dragNoteId = ref('')

function onDragStart(noteId: string): void {
  dragNoteId.value = noteId
}

async function onDrop(parentNoteId: string): Promise<void> {
  if (dragNoteId.value) {
    await store.moveNote(dragNoteId.value, parentNoteId)
  }
  dragNoteId.value = ''
}

async function onRootSelect(): Promise<void> {
  await store.selectTreeParent('root')
}

async function onRootToggle(): Promise<void> {
  if (store.expanded.has('root')) {
    store.expanded.delete('root')
    return
  }
  await store.expandNode('root')
}

</script>

<template>
  <div class="tree">
    <div class="tree-toolbar">
      <button class="btn" title="在选中目录下新建目录" @click="onCreateFolder">📁 新建目录</button>
      <button class="btn" title="在选中目录下新建笔记" @click="onCreateNote({ noteId: store.selectedTreeParent, title: '未命名', noteType: 'text', isDeleted: false, sortOrder: 0, version: 1 })">＋ 新建笔记</button>
    </div>

    <div
      class="tree-row root"
      :class="{ selected: store.selectedTreeParent === 'root' }"
      draggable="false"
       @dragover.prevent
       @drop.prevent="onDrop('root')"
       @click="onRootSelect"
     >
       <span class="twist" @click.stop="onRootToggle">{{ store.expanded.has('root') ? '▾' : '▸' }}</span>
      <span class="icon">📁</span>
      <span class="label">知识库</span>
    </div>

    <template v-if="store.expanded.has('root')">
      <div
        v-for="node in store.tree"
        :key="node.noteId"
        class="tree-node"
      >
        <TreeNodeItem
          :node="node"
          :depth="0"
          @dragstart="onDragStart"
          @drop="onDrop"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.tree {
  font-size: 13px;
}

.tree-toolbar {
  display: flex;
  gap: 6px;
  padding: 4px 0 8px;
  border-bottom: 1px solid #eee;
  margin-bottom: 6px;
}

.btn {
  flex: 1;
  border: 1px solid #e2e4e8;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  padding: 4px 0;
}

.tree-row {
  display: flex;
  align-items: center;
  padding: 4px 6px;
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
}

.tree-row:hover {
  background: #eceff3;
}

.tree-row.selected {
  background: #e8f0fe;
}

.tree-row.root {
  font-weight: 600;
}

.tree-row.child {
  padding-left: 12px;
}

.twist {
  width: 16px;
  color: #999;
  font-size: 11px;
  flex-shrink: 0;
}

.icon {
  width: 18px;
  flex-shrink: 0;
  font-size: 13px;
}

.label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.add {
  border: none;
  background: none;
  color: #1a73e8;
  font-size: 14px;
  visibility: hidden;
}

.tree-row.root .add {
  visibility: visible;
}

.tree-row:hover .add {
  visibility: visible;
}
</style>
