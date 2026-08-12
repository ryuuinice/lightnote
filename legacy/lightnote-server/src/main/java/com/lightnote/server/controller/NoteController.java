package com.lightnote.server.controller;

import com.lightnote.server.common.ApiResponse;
import com.lightnote.server.dto.NoteCreateRequest;
import com.lightnote.server.dto.NoteResponse;
import com.lightnote.server.dto.NoteUpdateRequest;
import com.lightnote.server.security.UserPrincipal;
import com.lightnote.server.service.NoteService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制器，负责暴露对应业务模块的 HTTP 接口。
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @GetMapping
    public ApiResponse<List<NoteResponse>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(noteService.listActive(principal.userId()));
    }

    @PostMapping
    public ApiResponse<NoteResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody NoteCreateRequest request
    ) {
        return ApiResponse.success(noteService.create(principal.userId(), request));
    }

    @PutMapping("/{noteUuid}")
    public ApiResponse<NoteResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String noteUuid,
            @Valid @RequestBody NoteUpdateRequest request
    ) {
        return ApiResponse.success(noteService.update(principal.userId(), noteUuid, request));
    }

    @DeleteMapping("/{noteUuid}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String noteUuid
    ) {
        noteService.delete(principal.userId(), noteUuid);
        return ApiResponse.success();
    }
}

