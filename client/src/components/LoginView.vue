<script setup lang="ts">
import { ref } from 'vue'
import { authLogin } from '../api/ipc'

const serverUrl = ref('')
const username = ref('admin')
const password = ref('')
const deviceName = ref('PC')
const error = ref('')
const loading = ref(false)

const emit = defineEmits<{ done: [] }>()

async function onLogin(): Promise<void> {
  if (!serverUrl.value || !password.value) {
    error.value = '请填写服务端地址与密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await authLogin(serverUrl.value, username.value, password.value, deviceName.value)
    emit('done')
  } catch (e) {
    error.value = `登录失败：${e}`
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="card">
      <h1>LightNote</h1>
      <label>
        服务端地址
        <input v-model="serverUrl" placeholder="https://lightnote.example.com" />
      </label>
      <label>
        用户名
        <input v-model="username" />
      </label>
      <label>
        密码
        <input v-model="password" type="password" @keyup.enter="onLogin" />
      </label>
      <label>
        设备名
        <input v-model="deviceName" placeholder="PC-Windows" />
      </label>
      <div v-if="error" class="error">{{ error }}</div>
      <button class="btn" :disabled="loading" @click="onLogin">{{ loading ? '登录中…' : '登录' }}</button>
    </div>
  </div>
</template>

<style scoped>
.login {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: #f5f6f8;
}

.card {
  width: 340px;
  max-width: 100%;
  max-height: 100%;
  overflow: auto;
  background: #fff;
  border-radius: 10px;
  padding: 28px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

h1 {
  margin: 0 0 8px;
  font-size: 22px;
  text-align: center;
}

label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: #666;
}

input {
  padding: 8px 10px;
  border: 1px solid #e2e4e8;
  border-radius: 6px;
  font-size: 14px;
}

.error {
  color: #d93025;
  font-size: 12px;
}

.btn {
  margin-top: 8px;
  padding: 10px;
  border: none;
  border-radius: 6px;
  background: #1a73e8;
  color: #fff;
  font-size: 14px;
}

.btn:disabled {
  opacity: 0.6;
}
</style>
