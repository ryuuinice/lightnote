<script setup lang="ts">
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
</script>

<template>
  <div
    class="tree-row"
    :class="{ selected: store.selectedTreeParent === node.noteId }"
    :style="{ paddingLeft: `${12 + depth * 16}px` }"
    draggable="true"
    @dragstart.stop="emit('dragstart', node.noteId)"
    @dragover.prevent
    @drop.prevent.stop="emit('drop', node.noteId)"
    @click="onSelect"
    @dblclick="onOpen"
  >
    <span class="twist" @click.stop="onToggle">{{ store.expanded.has(node.noteId) ? '▾' : '▸' }}</span>
    <span class="icon">{{ iconOf(node) }}</span>
    <span class="label">{{ node.title }}</span>
  </div>
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
</style>
