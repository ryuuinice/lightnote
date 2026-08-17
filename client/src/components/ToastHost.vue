<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

interface Toast {
  id: number
  text: string
  kind: 'error' | 'info'
}

const toasts = ref<Toast[]>([])
let nextId = 1

function show(text: string, kind: Toast['kind'] = 'error', ttl = 4000): void {
  const id = nextId++
  toasts.value.push({ id, text, kind })
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }, ttl)
}

function parseError(e: unknown): string {
  if (typeof e === 'string') return e
  if (e instanceof Error) return e.message
  const anyE = e as { message?: string }
  if (anyE?.message) return anyE.message
  return String(e)
}

function onAppToast(e: Event): void {
  show((e as CustomEvent<string>).detail)
}

onMounted(() => {
  window.addEventListener('app-toast', onAppToast)
  window.showError = (e: unknown): void => show(parseError(e))
})

onUnmounted(() => {
  window.removeEventListener('app-toast', onAppToast)
})
</script>

<template>
  <div class="toasts">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind">{{ t.text }}</div>
  </div>
</template>

<style scoped>
.toasts {
  position: fixed;
  bottom: 40px;
  right: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 1000;
  max-width: 380px;
}

.toast {
  padding: 10px 14px;
  border-radius: 6px;
  font-size: 13px;
  color: #fff;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.18);
  word-break: break-all;
}

.toast.error {
  background: #d93025;
}

.toast.info {
  background: #1a73e8;
}
</style>
