package com.lightnote.server.controller;

import com.lightnote.server.common.ApiResponse;
import com.lightnote.server.dto.SyncChangesResponse;
import com.lightnote.server.dto.SyncPushRequest;
import com.lightnote.server.dto.SyncPushResponse;
import com.lightnote.server.security.UserPrincipal;
import com.lightnote.server.service.SyncService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/push")
    public ApiResponse<SyncPushResponse> push(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SyncPushRequest request
    ) {
        return ApiResponse.success(syncService.push(principal.userId(), request));
    }

    @GetMapping("/changes")
    public ApiResponse<SyncChangesResponse> changes(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") long sinceVersion,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return ApiResponse.success(syncService.changes(principal.userId(), sinceVersion, limit));
    }
}
