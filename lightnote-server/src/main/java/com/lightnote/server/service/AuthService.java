package com.lightnote.server.service;

import com.lightnote.server.dto.LoginRequest;
import com.lightnote.server.dto.LoginResponse;
import com.lightnote.server.entity.UserEntity;
import com.lightnote.server.exception.BusinessException;
import com.lightnote.server.mapper.UserMapper;
import com.lightnote.server.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务，负责账号密码校验和登录令牌签发。
 */
@Service
public class AuthService {

    private static final int USER_DISABLED = 1001;
    private static final int BAD_CREDENTIALS = 1002;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * 校验用户凭据并签发新的 JWT 令牌。
     */
    public LoginResponse login(LoginRequest request) {
        UserEntity user = userMapper.findByUsername(request.username());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(BAD_CREDENTIALS, "invalid username or password");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(USER_DISABLED, "user is disabled");
        }

        String token = jwtService.generateToken(user.getId(), user.getUsername());
        return new LoginResponse(token, jwtService.getExpireSeconds());
    }
}

