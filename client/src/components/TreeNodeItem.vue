<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useNotesStore } from '../store/notes'
import type { TreeNode } from '../types'

defineOptions({ name: 'TreeNodeItem' })

const props = defineProps<{
  node: TreeNode
  depth: number
}>()

const emit = defineEmits<{
  dragstart: [noteId: string]
  drop: [parentNoteId: string]
}>()

const store = useNotesStore()
const editing = ref(false)
const editValue = ref('')
const inputEl = ref<HTMLInputElement | null>(null)
const menuVisible = ref(false)
const menuX = ref(0)
const menuY = ref(0)

async function onToggle(): Promise<void> {
  if (store.expanded.has(props.node.noteId) && props.node.children) {
    store.expanded.delete(props.node.noteId)
    return
  }
  await store.expandNode(props.node.noteId)
}

async function onSelect(): Promise<void> {
  await store.selectTreeParent(props.node.noteId)
}

async function onOpen(): Promise<void> {
  await store.openNote(props.node.noteId)
}

function iconOf(node: TreeNode): string {
  return node.noteType === 'folder' ? '📁' : '📄'
}

function startRename(): Promise<void> {
  editing.value = true
  editValue.value = props.node.title
  return nextTick().then(() => {
    inputEl.value?.focus()
    inputEl.value?.select()
  })
}

async function commitRename(): Promise<void> {
  const value = editValue.value.trim()
  editing.value = false
  if (!value || value === props.node.title) return
  await store.renameNote(props.node.noteId, value)
}

function cancelRename(): void {
  editing.value = false
}

function onRowKeydown(e: KeyboardEvent): void {
  if (e.key === 'F2' && store.selectedTreeParent === props.node.noteId) {
    e.preventDefault()
    void startRename()
  }
}

function onContextmenu(e: MouseEvent): void {
  e.preventDefault()
  e.stopPropagation()
  menuX.value = e.clientX
  menuY.value = e.clientY
  menuVisible.value = true
}

async function onMenuRename(): Promise<void> {
  menuVisible.value = false
  await startRename()
}

function onMenuNewNote(): void {
  menuVisible.value = false
  void store.createAndOpen(props.node.noteId)
}

function onMenuNewFolder(): void {
  menuVisible.value = false
  void store.createFolder(props.node.noteId)
}

async function onMenuDelete(): Promise<void> {
  menuVisible.value = false
  if (props.node.noteType === 'folder' && !confirm(`删除目录「${props.node.title}」及其全部子内容？可从回收站恢复。`)) return
  if (props.node.noteType !== 'folder' && !confirm(`删除笔记「${props.node.title}」？可从回收站恢复。`)) return
  await store.deleteNote(props.node.noteId)
}

function onGlobalClick(): void {
  menuVisible.value = false
}

onMounted(() => window.addEventListener('click', onGlobalClick))
onUnmounted(() => window.removeEventListener('click', onGlobalClick))
</script>

<template>
  <div
    v-if="!editing"
    class="tree-row"
    :class="{ selected: store.selectedTreeParent === node.noteId }"
    :style="{ paddingLeft: `${12 + depth * 16}px` }"
    draggable="true"
    @dragstart.stop="emit('dragstart', node.noteId)"
    @dragover.prevent
    @drop.prevent.stop="emit('drop', node.noteId)"
    @click="onSelect"
    @dblclick="onOpen"
    @keydown="onRowKeydown"
    @contextmenu="onContextmenu"
    :tabindex="0"
  >
    <span class="twist" @click.stop="onToggle">{{ store.expanded.has(node.noteId) ? '▾' : '▸' }}</span>
    <span class="icon">{{ iconOf(node) }}</span>
    <span class="label">{{ node.title }}</span>
  </div>
  <div v-else class="tree-row editing-row" :style="{ paddingLeft: `${12 + depth * 16}px` }">
    <span class="twist"> </span>
    <span class="icon">{{ iconOf(node) }}</span>
    <input
      ref="inputEl"
      v-model="editValue"
      class="rename-input"
      @keydown.enter.prevent="commitRename"
      @keydown.esc.prevent="cancelRename"
      @blur="commitRename"
      @click.stop
      @dragstart.stop.prevent
    />
  </div>
  <Teleport to="body">
    <div
      v-if="menuVisible"
      class="ctx-menu"
      :style="{ left: `${menuX}px`, top: `${menuY}px` }"
      @click.stop
    >
      <button class="ctx-item" @click="onMenuRename">重命名（F2）</button>
      <template v-if="node.noteType === 'folder'">
        <button class="ctx-item" @click="onMenuNewNote">新建笔记</button>
        <button class="ctx-item" @click="onMenuNewFolder">新建子目录</button>
      </template>
      <button class="ctx-item danger" @click="onMenuDelete">删除</button>
    </div>
  </Teleport>
  <template v-if="store.expanded.has(node.noteId) && node.children">
    <TreeNodeItem
      v-for="child in node.children"
      :key="child.noteId"
      :node="child"
      :depth="depth + 1"
      @dragstart="emit('dragstart', $event)"
      @drop="emit('drop', $event)"
    />
  </template>
</template>

<style scoped>
.tree-row {
  display: flex;
  align-items: center;
  padding-top: 4px;
  padding-right: 6px;
  padding-bottom: 4px;
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
  outline: none;
}

.tree-row:hover {
  background: #eceff3;
}

.tree-row.selected {
  background: #e8f0fe;
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

.editing-row {
  background: #e8f0fe;
}

.rename-input {
  flex: 1;
  min-width: 0;
  border: 1px solid #1a73e8;
  border-radius: 3px;
  padding: 1px 4px;
  font-size: 13px;
  outline: none;
  background: #fff;
}

.ctx-menu {
  position: fixed;
  z-index: 2000;
  background: #fff;
  border: 1px solid #d5d9e0;
  border-radius: 6px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.14);
  padding: 4px;
  display: flex;
  flex-direction: column;
  min-width: 150px;
}

.ctx-item {
  border: none;
  background: transparent;
  text-align: left;
  padding: 7px 12px;
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
  color: #222;
}

.ctx-item:hover {
  background: #f0f3f8;
}

.ctx-item.danger {
  color: #d93025;
}
</style>
