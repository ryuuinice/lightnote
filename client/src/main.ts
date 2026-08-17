import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

// ToastHost 挂载前的早期错误兜底（启动加载失败等场景），挂载后由 toast 接管
window.showError = (e: unknown): void => {
  const msg = typeof e === 'string' ? e : (e as Error)?.message ?? String(e)
  console.error('[lightnote]', msg)
}

createApp(App).use(createPinia()).mount('#app')
