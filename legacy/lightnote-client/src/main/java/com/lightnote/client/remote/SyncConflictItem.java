package com.lightnote.client.remote;

/**
 * 同步冲突项模型，表示客户端推送时与服务端版本发生冲突的单条结果。
 */
public record SyncConflictItem(
        String noteUuid,
        long clientBaseObjectVersion,
        long serverObjectVersion,
        RemoteNote serverNote
) {
}

