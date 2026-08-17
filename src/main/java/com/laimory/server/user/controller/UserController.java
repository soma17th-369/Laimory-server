package com.laimory.server.user.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.user.dto.UserProfileResponse;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.service.UserService;
import com.laimory.server.user.service.UserWithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 정보 API 구현. HTTP 문서·계약은 {@link UserApi}.
 *
 * <p>userId는 클라이언트 값이 아니라 JWT 인증 principal이다. 회원 행이 없으면 Service가 기존 401
 * 계약({@code -2001})으로 수렴시키므로 컨트롤러는 성공 응답 구성만 한다. 탈퇴(#305)는 transaction
 * commit 뒤 202 — 물리 삭제가 아니라 논리 탈퇴와 삭제 요청 접수를 뜻한다.
 */
@RestController
@RequiredArgsConstructor
public class UserController implements UserApi {

    private final UserService userService;
    private final UserWithdrawalService userWithdrawalService;

    @Override
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(String applicationVersion, Long userId) {
        User user = userService.getProfile(applicationVersion, userId);
        return ResponseEntity.ok(ApiResponse.success(new UserProfileResponse(user.getNickname())));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> withdraw(String applicationVersion, Long userId) {
        userWithdrawalService.withdraw(applicationVersion, userId);
        return ResponseEntity.accepted().body(ApiResponse.success(null));
    }
}
