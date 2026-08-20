package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.push.dto.PushEnabledRequest;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.terms.LoginTermsExempt;
import com.laimory.server.user.CurrentSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 푸시 수신 설정 API의 문서·계약(구현은 {@link PushSettingController}).
 *
 * <p>모든 값의 권위는 서버다 — 앱은 재설치·기기 변경 뒤에도 로컬 추정값이 아니라 조회 응답을 표시한다.
 * 설정 owner는 {@code @CurrentSubject}가 JWT principal에서 해석한 subject이며 클라이언트 입력이 아니다.
 *
 * <p>모든 operation에 {@link LoginTermsExempt}를 붙인다 — 약관에 아직 동의하지 않은 사용자도 알림을
 * 끌 수 있어야 한다. bearer 인증(401)은 그대로 요구한다.
 *
 */
@Tag(name = "Push Settings", description = "푸시 수신 설정 — 전체 ON/OFF, 일일 리마인더")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/push-settings")
public interface PushSettingApi {

    @Operation(summary = "푸시 수신 설정 조회",
            description = "전체 푸시 ON/OFF와 일일 리마인더 ON/OFF·시각(Asia/Seoul HH:mm)을 반환한다. "
                    + "일일 리마인더는 기본 ON이고 전체 사용자에게 매일 21:00 일괄 발송하므로 `time`은 "
                    + "서버가 고정한 읽기 전용 값이다(변경 API 없음 — 앱 안내 문구용). 행 생성은 가입 "
                    + "transaction과 rollout backfill이 소유하며, 설정 행이 없는 사용자는 기본값으로 "
                    + "가리지 않고 500으로 실패한다(운영 신호 — 조회·발송·쓰기가 한 방향을 가리킨다).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @GetMapping
    @LoginTermsExempt
    ResponseEntity<ApiResponse<PushSettingsResponse>> getPushSettings(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId);

    @Operation(summary = "전체 푸시 수신 ON/OFF",
            description = "모든 알림의 최상위 스위치를 바꾼다. OFF는 타임라인 완료 알림과 모든 예정 알림을 "
                    + "차단하지만 종류별 설정값·시각은 그대로 보존한다(다시 켜면 이전 설정으로 "
                    + "재개하며 지나간 알림을 몰아 보내지 않는다). 같은 값 재요청은 멱등 성공이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "변경 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — enabled 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요")
    })
    @PutMapping("/enabled")
    @LoginTermsExempt
    ResponseEntity<ApiResponse<Void>> updatePushEnabled(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody PushEnabledRequest request);

    @Operation(summary = "일일 리마인더 수신 ON/OFF",
            description = "일일 리마인더만 바꾼다(전체 푸시와 고정 시각은 보존). 기본값은 ON이라 "
                    + "`false`로 끄면 발송이 멈추고, 다시 `true`로 켜면 다음 21:00부터 재개한다"
                    + "(끈 사이 지나간 알림은 몰아 보내지 않는다). 같은 값 재요청은 멱등 성공이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "변경 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — enabled 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요"),
    })
    @PutMapping("/daily-reminder/enabled")
    @LoginTermsExempt
    ResponseEntity<ApiResponse<Void>> updateDailyReminderEnabled(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody PushEnabledRequest request);

}
