package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.push.dto.DailyReminderTimeRequest;
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
                    + "설정 행이 아직 없는 사용자에게는 기본값(전체 ON / 리마인더 OFF·21:00)을 응답한다"
                    + "(순수 조회 — 행 생성은 가입·backfill·첫 설정 변경이 소유).")
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
            description = "일일 리마인더만 바꾼다(전체 푸시와 저장된 시각은 보존). 기본값은 OFF이며 "
                    + "사용자가 직접 켜야 발송된다.")
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

    @Operation(summary = "일일 리마인더 시각 설정",
            description = "분 단위 `HH:mm`만 허용하며 기준 timezone은 서버가 `Asia/Seoul`로 고정한다"
                    + "(클라이언트 timezone을 받지 않는다). 형식·범위 오류는 DB 변경 전에 400으로 거절한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "변경 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — time 누락·형식 오류(HH:mm 아님)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요"),
    })
    @PutMapping("/daily-reminder/time")
    @LoginTermsExempt
    ResponseEntity<ApiResponse<Void>> updateDailyReminderTime(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody DailyReminderTimeRequest request);

}
