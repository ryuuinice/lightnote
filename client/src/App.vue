<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useNotesStore } from './store/notes'
import { authRefresh, authStatus, ipc } from './api/ipc'
import LoginView from './components/LoginView.vue'
import TreeView from './components/TreeView.vue'
import NoteList from './components/NoteList.vue'
import EditorPane from './components/EditorPane.vue'
import SearchPanel from './components/SearchPanel.vue'
import TagsPanel from './components/TagsPanel.vue'
import TrashPanel from './components/TrashPanel.vue'
import SyncStatusBar from './components/SyncStatusBar.vue'
import SettingsDialog from './components/SettingsDialog.vue'
import CommandPalette from './components/CommandPalette.vue'

const store = useNotesStore()
const showSettings = ref(false)
const needsLogin = ref(false)
const loading = ref(true)

onMounted(async () => {
  try {
    await store.loadSettings()
    // 启动恢复：若存在持久化会话，用 refresh_token 换新 access_token 后直接进主界面
    const status = await authStatus()
    if (status.has_session) {
      try {
        await authRefresh()
      } catch {
        needsLogin.value = true
        return
      }
    } else {
      needsLogin.value = true
      return
    }
    await store.loadTree()
    await store.loadNotes()
  } finally {
    loading.value = false
  }
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('open-settings', onOpenSettings)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('open-settings', onOpenSettings)
})

function onOpenSettings(): void {
  showSettings.value = true
}

function onLogout(): void {
  showSettings.value = false
  store.currentNote = null
  store.currentContent = ''
  store.notes = []
  store.tree = []
  needsLogin.value = true
}

function onGlobalKeydown(e: KeyboardEvent): void {
  if (!e.ctrlKey && !e.metaKey) return
  switch (e.key.toLowerCase()) {
    case 'n':
      if (store.paletteVisible) return
      e.preventDefault()
      store.createAndOpen(store.selectedTreeParent)
      break
    case 's':
      if (store.paletteVisible) return
      e.preventDefault()
      store.saveNow()
      break
    case 'e':
      e.preventDefault()
      store.editorMode = store.editorMode === 'preview' ? 'edit' : store.editorMode === 'edit' ? 'split' : 'preview'
      break
    case 'k':
      e.preventDefault()
      store.paletteVisible = !store.paletteVisible
      break
    case 'f':
      if (store.paletteVisible) return
      e.preventDefault()
      store.findVisible = !store.findVisible
      break
  }
}

async function onLoggedIn(): Promise<void> {
  loading.value = true
  try {
    needsLogin.value = false
    await store.loadSettings()
    await store.loadTree()
    await store.loadNotes()
    await ipc.invoke('sync.trigger').catch(() => undefined)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <LoginView v-if="needsLogin" @done="onLoggedIn" />
  <div v-else-if="loading" class="loading">正在加载笔记…</div>
  <div v-else class="app" :class="`mobile-${store.mobilePane}`">
      <aside class="sidebar">
      <div class="panel-tabs">
        <button :class="{ active: store.activePanel === 'tree' }" @click="store.activePanel = 'tree'">目录</button>
        <button :class="{ active: store.activePanel === 'tags' }" @click="store.activePanel = 'tags'">标签</button>
        <button :class="{ active: store.activePanel === 'search' }" @click="store.activePanel = 'search'">搜索</button>
        <button :class="{ active: store.activePanel === 'trash' }" @click="store.loadTrash(); store.activePanel = 'trash'">回收站</button>
      </div>
      <div class="panel-body">
        <TreeView v-if="store.activePanel === 'tree'" />
        <TagsPanel v-else-if="store.activePanel === 'tags'" />
        <SearchPanel v-else-if="store.activePanel === 'search'" />
        <TrashPanel v-else-if="store.activePanel === 'trash'" />
      </div>
      <button class="mobile-pane-nav mobile-next" @click="store.mobilePane = 'notes'">笔记列表 →</button>
    </aside>

    <section class="mid">
      <div class="mobile-pane-nav">
        <button @click="store.mobilePane = 'navigation'">← 导航</button>
        <span>笔记列表</span>
      </div>
      <NoteList />
    </section>

    <section class="main">
      <div class="mobile-pane-nav">
        <button @click="store.mobilePane = 'notes'">← 笔记列表</button>
      </div>
      <EditorPane v-if="store.currentNote" />
      <div v-else class="empty">选择或创建一篇笔记</div>
    </section>

    <footer class="statusbar">
      <SyncStatusBar />
      <button class="link" @click="showSettings = true">设置</button>
    </footer>

    <SettingsDialog v-if="showSettings" @close="showSettings = false" @logout="onLogout" />
    <CommandPalette v-if="store.paletteVisible" />
  </div>
</template>

<style scoped>
.app {
  display: grid;
  grid-template-columns: minmax(180px, 240px) minmax(220px, 280px) minmax(0, 1fr);
  grid-template-rows: 1fr 32px;
  height: 100%;
  min-width: 0;
}

.loading {
  display: grid;
  place-items: center;
  height: 100%;
  color: #666;
  background: #f5f6f8;
  font-size: 13px;
}

.sidebar {
  border-right: 1px solid #e2e4e8;
  background: #fafbfc;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
  min-height: 0;
}

.panel-tabs {
  display: flex;
  border-bottom: 1px solid #e2e4e8;
}

.panel-tabs button {
  flex: 1;
  border: none;
  background: transparent;
  padding: 8px 4px;
  font-size: 12px;
  color: #666;
}

.panel-tabs button.active {
  color: #1a73e8;
  border-bottom: 2px solid #1a73e8;
}

.panel-body {
  flex: 1;
  overflow: auto;
  padding: 8px;
}

.mid {
  border-right: 1px solid #e2e4e8;
  background: #fff;
  overflow: auto;
  min-width: 0;
  min-height: 0;
}

.main {
  background: #fff;
  overflow: auto;
  padding: 16px;
  min-width: 0;
  min-height: 0;
}

.empty {
  color: #999;
  text-align: center;
  margin-top: 40%;
}

.statusbar {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  background: #fafbfc;
  border-top: 1px solid #e2e4e8;
}

.link {
  border: none;
  background: none;
  color: #1a73e8;
  font-size: 12px;
}

.mobile-pane-nav {
  display: none;
}

@media (max-width: 760px) {
  .app {
    grid-template-columns: minmax(0, 1fr);
  }

  .sidebar,
  .mid,
  .main {
    grid-column: 1;
    grid-row: 1;
  }

  .app.mobile-navigation .mid,
  .app.mobile-navigation .main,
  .app.mobile-notes .sidebar,
  .app.mobile-notes .main,
  .app.mobile-editor .sidebar,
  .app.mobile-editor .mid {
    display: none;
  }

  .mobile-pane-nav {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 36px;
    padding: 6px 10px;
    border-bottom: 1px solid #e2e4e8;
    color: #666;
    font-size: 12px;
  }

  .mobile-pane-nav button {
    border: none;
    background: transparent;
    color: #1a73e8;
    padding: 2px 0;
  }

  .mobile-next {
    justify-content: center;
    width: 100%;
    border-top: 1px solid #e2e4e8;
    border-bottom: none;
  }

  .main {
    padding: 0 12px 12px;
  }
}
</style>
