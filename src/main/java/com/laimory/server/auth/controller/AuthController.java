package com.laimory.server.auth.controller;

import com.laimory.server.auth.dto.LogoutRequest;
import com.laimory.server.auth.dto.TokenIssueRequest;
import com.laimory.server.auth.dto.TokenRefreshRequest;
import com.laimory.server.auth.dto.TokenResponse;
import com.laimory.server.auth.service.AuthTokenService;
import com.laimory.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자체 토큰 발급/갱신/로그아웃 API 구현. HTTP 문서·계약은 {@link AuthApi}.
 */
@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthTokenService authTokenService;

    @Override
    public ResponseEntity<ApiResponse<TokenResponse>> issueTokens(
            String applicationVersion, TokenIssueRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                authTokenService.issueTokens(applicationVersion, request.appCode(), request.appVerifier())));
    }

    @Override
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            String applicationVersion, TokenRefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                authTokenService.refresh(applicationVersion, request.refreshToken())));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> logout(
            String applicationVersion, LogoutRequest request) {
        authTokenService.logout(applicationVersion, request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
