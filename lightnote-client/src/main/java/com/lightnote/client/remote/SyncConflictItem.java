package com.lightnote.client.remote;

public record SyncConflictItem(
        String noteUuid,
        long clientBaseObjectVersion,
        long serverObjectVersion,
        RemoteNote serverNote
) {
}
