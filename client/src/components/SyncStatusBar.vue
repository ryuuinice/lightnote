<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useNotesStore } from '../store/notes'
import { ipc } from '../api/ipc'

const store = useNotesStore()
const open = ref(false)

const labels: Record<string, string> = {
  idle: '✓ 已同步',
  preparing: '↻ 准备中',
  pushing: '↻ 同步中',
  pulling: '↻ 同步中',
  applying: '↻ 应用变更',
  completed: '✓ 已同步',
  error: '⚠ 同步失败',
  offline: '○ 离线',
}

onMounted(async () => {
  store.sync = await ipc.invoke('sync.status')
})

function fmtTime(ts?: number): string {
  if (!ts) return '—'
  return new Date(ts).toLocaleTimeString()
}

async function onTrigger(): Promise<void> {
  open.value = false
  await store.triggerSync().catch(() => undefined)
  store.sync = await ipc.invoke('sync.status').catch(() => store.sync)
}
</script>

<template>
  <div class="sync-wrap">
    <button class="trigger" @click="open = !open">
      <span :class="['dot', store.sync.state]">{{ labels[store.sync.state] ?? store.sync.state }}</span>
      <span v-if="store.sync.pendingCount > 0" class="meta">待同步 {{ store.sync.pendingCount }}</span>
    </button>

    <div v-if="open" class="popover" @click.stop>
      <div class="pop-title">同步状态</div>
      <div class="row"><span class="k">状态</span><span :class="['dot', store.sync.state]">{{ labels[store.sync.state] ?? store.sync.state }}</span></div>
      <div class="row"><span class="k">最后同步</span><span>{{ fmtTime(store.sync.lastSyncAt) }}</span></div>
      <div class="row"><span class="k">待同步变更</span><span>{{ store.sync.pendingCount }}</span></div>
      <div class="row"><span class="k">失败</span><span>{{ store.sync.failedCount }}</span></div>
      <button class="now" @click="onTrigger">立即同步</button>
    </div>
  </div>
</template>

<style scoped>
.sync-wrap {
  position: relative;
}

.trigger {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #666;
  border: none;
  background: none;
  padding: 4px 0;
}

.dot.idle,
.dot.completed {
  color: #188038;
}

.dot.error {
  color: #d93025;
}

.dot.offline {
  color: #999;
}

.popover {
  position: absolute;
  bottom: 30px;
  left: 0;
  min-width: 240px;
  background: #fff;
  border: 1px solid #e2e4e8;
  border-radius: 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.12);
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  z-index: 50;
}

.pop-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}

.row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.k {
  color: #999;
}

.now {
  margin-top: 6px;
  border: 1px solid #1a73e8;
  background: #1a73e8;
  color: #fff;
  border-radius: 4px;
  font-size: 12px;
  padding: 4px 0;
}
</style>
