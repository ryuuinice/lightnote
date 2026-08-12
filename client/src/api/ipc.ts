import type { IpcApi, IpcCommand } from './contract'
import { mockApi } from '../mock/ipc'

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'

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

