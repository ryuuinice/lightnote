package com.lightnote.server.service;

import com.lightnote.server.exception.BusinessException;
import com.lightnote.server.mapper.SyncLogMapper;
import org.springframework.stereotype.Service;

@Service
public class ServerVersionService {

    private final SyncLogMapper syncLogMapper;

    public ServerVersionService(SyncLogMapper syncLogMapper) {
        this.syncLogMapper = syncLogMapper;
    }

    public long nextServerVersion() {
        Long currentVersion = syncLogMapper.lockCurrentServerVersion();
        if (currentVersion == null) {
            throw new BusinessException(5001, "server state is not initialized");
        }
        long nextVersion = currentVersion + 1;
        syncLogMapper.updateCurrentServerVersion(nextVersion);
        return nextVersion;
    }
}
