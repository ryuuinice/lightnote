<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useNotesStore } from '../store/notes'
import { ipc } from '../api/ipc'
import type { Device } from '../types'

const store = useNotesStore()
const serverUrl = ref(store.settings.serverUrl)
const autoSync = ref(store.settings.autoSync)
const interval = ref(store.settings.syncIntervalSec)
const devices = ref<Device[]>([])
const devicesLoaded = ref(false)

const emit = defineEmits<{ close: []; logout: [] }>()

onMounted(async () => {
  devices.value = await ipc.invoke('devices.list').catch(() => [])
  devicesLoaded.value = true
})

async function onSave(): Promise<void> {
  await store.updateSettings({ serverUrl: serverUrl.value, autoSync: autoSync.value, syncIntervalSec: Number(interval.value) })
  emit('close')
}

async function onRevoke(deviceId: string): Promise<void> {
  if (!confirm('吊销该设备？其令牌将立即失效。')) return
  await ipc.invoke('devices.revoke', { deviceId })
  devices.value = await ipc.invoke('devices.list')
}

async function onLogout(): Promise<void> {
  await ipc.invoke('settings.logout').catch(() => undefined)
  emit('logout')
}

function fmtTime(ts?: number): string {
  if (!ts) return '—'
  return new Date(ts).toLocaleString()
}
</script>

<template>
  <div class="mask" @click.self="emit('close')">
    <div class="dialog">
      <h3>设置</h3>
      <label>
        服务端地址
        <input v-model="serverUrl" class="input" placeholder="https://lightnote.example.com" />
      </label>
      <label class="row">
        <input v-model="autoSync" type="checkbox" />
        自动同步
      </label>
      <label>
        同步间隔（秒）
        <input v-model.number="interval" class="input" type="number" min="10" />
      </label>

      <div class="section">设备管理</div>
      <div v-if="!devicesLoaded" class="dev-hint">加载中…</div>
      <div v-else class="devices">
        <div v-for="d in devices" :key="d.deviceId" class="device">
          <div class="dev-main">
            <span class="dev-name">{{ d.deviceName }}</span>
            <span class="dev-meta">{{ d.deviceType || 'desktop' }} · 最后在线 {{ fmtTime(d.lastSeen) }}</span>
          </div>
          <button v-if="!d.revokedAt" class="revoke" @click="onRevoke(d.deviceId)">吊销</button>
          <span v-else class="revoked">已吊销</span>
        </div>
        <div v-if="devices.length === 0" class="dev-hint">暂无其他设备</div>
      </div>

      <div class="actions">
        <button class="btn ghost" @click="onLogout">退出登录</button>
        <span class="spacer"></span>
        <button class="btn ghost" @click="emit('close')">取消</button>
        <button class="btn" @click="onSave">保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.dialog {
  width: 420px;
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 80vh;
  overflow: auto;
}

h3 {
  margin: 0 0 8px;
}

label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}

label.row {
  flex-direction: row;
  align-items: center;
}

.section {
  font-weight: 600;
  font-size: 13px;
  margin-top: 8px;
  border-top: 1px solid #f0f1f3;
  padding-top: 8px;
}

.devices {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 180px;
  overflow: auto;
}

.device {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  border: 1px solid #f0f1f3;
  border-radius: 6px;
}

.dev-name {
  font-size: 13px;
  font-weight: 500;
}

.dev-meta {
  font-size: 11px;
  color: #999;
  display: block;
}

.revoke {
  border: 1px solid #d93025;
  color: #d93025;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  padding: 2px 8px;
}

.revoked {
  font-size: 12px;
  color: #999;
}

.dev-hint {
  font-size: 12px;
  color: #999;
}

.input {
  padding: 6px 8px;
  border: 1px solid #e2e4e8;
  border-radius: 4px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.spacer {
  flex: 1;
}

.btn {
  border: 1px solid #e2e4e8;
  background: #1a73e8;
  color: #fff;
  border-radius: 4px;
  padding: 6px 16px;
}

.btn.ghost {
  background: #fff;
  color: #333;
}
</style>
