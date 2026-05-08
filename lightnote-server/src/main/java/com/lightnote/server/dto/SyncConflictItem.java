package com.lightnote.server.dto;

public record SyncConflictItem(
        String noteUuid,
        long clientBaseObjectVersion,
        long serverObjectVersion,
        NoteResponse serverNote
) {
}
