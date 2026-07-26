package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.push.dto.PushRegistrationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * FCM 푸시 등록 API의 문서·계약(구현은 {@link PushRegistrationController}).
 * Android가 Firebase Messaging 등록 콜백(onRegistered)·로그인 직후·FID 변경 시 PUT을,
 * 로그아웃·계정 전환 전 best-effort로 DELETE를 호출한다.
 *
 * <p>등록은 특정 사용자에 종속되므로 인증 prefix({@code /a/api})에 둔다. userId는 인증된 JWT
 * principal에서 받으며 클라이언트 입력이 아니다 — OpenAPI parameter로 노출하지 않는다.
 *
 * <p>FID는 URL이 아닌 request body로 받는다 — access log·프록시 URL에 원문이 남지 않게 한다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Push Registration", description = "FCM 푸시 등록 — Firebase Installation ID(FID) 등록·갱신·해제")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/push-registrations")
public interface PushRegistrationApi {

    @Operation(summary = "FID 등록·갱신",
            description = "인증 사용자의 앱 설치(FID) 하나를 등록한다. 같은 사용자·FID 재등록은 멱등 성공이며 "
                    + "등록 시각만 갱신된다. 다른 사용자에게 결합돼 있던 FID는 현재 인증 사용자로 원자적으로 "
                    + "재결합된다(계정 전환). 한 사용자의 다른 FID는 별도 등록으로 유지된다(설치 여러 대). "
                    + "서버는 FID를 opaque 식별자로 취급한다 — trim·형식 검증·재작성을 하지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "등록 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — firebaseInstallationId null/공백/255자 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @PutMapping
    ResponseEntity<ApiResponse<Void>> registerPushRegistration(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @RequestBody PushRegistrationRequest request);

    @Operation(summary = "FID 해제",
            description = "인증 사용자의 FID 등록 하나를 해제한다(로그아웃·계정 전환 전 access token을 버리기 "
                    + "전에 best-effort 호출). (사용자, FID)가 함께 일치하는 등록만 삭제되므로 계정 전환으로 "
                    + "재결합된 등록을 이전 사용자의 늦은 해제가 지우지 않는다. 미존재 등록 해제도 200(멱등)이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "해제 성공(body 없음 — 등록이 없었어도 200)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — firebaseInstallationId null/공백/255자 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @DeleteMapping
    ResponseEntity<ApiResponse<Void>> unregisterPushRegistration(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @RequestBody PushRegistrationRequest request);
}
