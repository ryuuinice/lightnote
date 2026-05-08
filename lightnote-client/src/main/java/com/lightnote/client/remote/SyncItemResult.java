package com.lightnote.client.remote;

public record SyncItemResult(String noteUuid, long objectVersion, long serverVersion) {
}
