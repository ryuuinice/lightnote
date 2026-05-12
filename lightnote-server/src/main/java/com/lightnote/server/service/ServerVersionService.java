package com.lightnote.server.service;

import com.lightnote.server.exception.BusinessException;
import com.lightnote.server.mapper.SyncLogMapper;
import org.springframework.stereotype.Service;

/**
 * 服务端全局版本服务，负责分配递增的同步版本号。
 */
@Service
public class ServerVersionService {

    private final SyncLogMapper syncLogMapper;

    public ServerVersionService(SyncLogMapper syncLogMapper) {
        this.syncLogMapper = syncLogMapper;
    }

    /**
     * 以加锁方式分配下一个全局 serverVersion，保证同步日志顺序单调递增。
     */
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

