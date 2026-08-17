import type { IpcApi, IpcCommand } from './contract'
import { mockApi } from '../mock/ipc'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

const COMMAND_MAP: Record<IpcCommand, string> = {
  'notes.list': 'notes_list',
  'notes.get': 'notes_get',
  'notes.create': 'notes_create',
  'notes.update': 'notes_update',
  'notes.delete': 'notes_delete',
  'notes.restore': 'notes_restore',
  'notes.saveContent': 'notes_save_content',
  'notes.getContent': 'notes_get_content',
  'notes.attach': 'notes_attach',
  'tree.children': 'tree_children',
  'tree.move': 'tree_move',
  'search.query': 'search_query',
  'sync.status': 'sync_status',
  'sync.trigger': 'sync_trigger',
  'blobs.get': 'blobs_get',
  'blobs.exists': 'blobs_exists',
  'tags.list': 'tags_list',
  'tags.add': 'tags_add',
  'tags.remove': 'tags_remove',
  'trash.list': 'trash_list',
  'trash.empty': 'trash_empty',
  'conflicts.list': 'conflicts_list',
  'conflicts.resolve': 'conflicts_resolve',
  'settings.get': 'settings_get',
  'settings.update': 'settings_update',
  'settings.logout': 'settings_logout',
  'devices.list': 'devices_list',
  'devices.revoke': 'devices_revoke',
}

async function invokeReal<K extends IpcCommand>(command: K, params: Parameters<IpcApi[K]>[0]): Promise<ReturnType<IpcApi[K]>> {
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke(COMMAND_MAP[command], params ?? {}) as Promise<ReturnType<IpcApi[K]>>
}

export const ipc = {
  async invoke<K extends IpcCommand>(command: K, params: Parameters<IpcApi[K]>[0] = {} as never): Promise<ReturnType<IpcApi[K]>> {
    if (USE_MOCK) {
      const fn = mockApi[command] as (p: never) => Promise<ReturnType<IpcApi[K]>>
      return fn(params as never)
    }
    return invokeReal(command, params)
  },
}

export async function authLogin(serverUrl: string, username: string, password: string, deviceName: string): Promise<void> {
  const { invoke } = await import('@tauri-apps/api/core')
  await invoke('auth_login', { serverUrl, username, password, deviceName })
}

export interface AuthStatus {
  has_session: boolean
  server_url: string
  device_id: string
  device_name: string
}

// 启动恢复：查询是否存在可恢复会话（持久化的 refresh_token + server_url）
export async function authStatus(): Promise<AuthStatus> {
  if (USE_MOCK) return { has_session: false, server_url: '', device_id: '', device_name: '' }
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke<AuthStatus>('auth_status')
}

// 用持久化 refresh_token 换新 access_token（轮换）；失败（401/403）= 需重新登录
export async function authRefresh(): Promise<void> {
  if (USE_MOCK) return
  const { invoke } = await import('@tauri-apps/api/core')
  await invoke('auth_refresh')
}

