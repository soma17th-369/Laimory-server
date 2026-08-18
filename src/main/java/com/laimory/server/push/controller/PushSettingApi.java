package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.push.dto.DailyReminderTimeRequest;
import com.laimory.server.push.dto.NotificationConsentRequest;
import com.laimory.server.push.dto.NotificationConsentResultResponse;
import com.laimory.server.push.dto.PushEnabledRequest;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.terms.LoginTermsExempt;
import com.laimory.server.user.CurrentSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 푸시 수신 설정·알림 수신 동의 API의 문서·계약(구현은 {@link PushSettingController}).
 *
 * <p>모든 값의 권위는 서버다 — 앱은 재설치·기기 변경 뒤에도 로컬 추정값이 아니라 조회 응답을 표시한다.
 * 설정 owner는 {@code @CurrentSubject}가 JWT principal에서 해석한 subject이며 클라이언트 입력이 아니다.
 *
 * <p>모든 operation에 {@link LoginTermsExempt}를 붙인다 — 약관에 아직 동의하지 않은 사용자도 수신을
 * 끄거나 광고 동의를 철회할 수 있어야 한다. bearer 인증(401)은 그대로 요구한다.
 *
 * <p>동의 API는 앱의 durable outbox가 붙인 {@code clientRequestId}로 멱등하다. 같은 ID의 재시도는 상태를
 * 다시 바꾸지 않고 원래 처리결과를 돌려주며, 같은 ID로 다른 의사 표시가 오면 409({@code -4003})다.
 */
@Tag(name = "Push Settings", description = "푸시 수신 설정 — 전체 ON/OFF, 일일 리마인더, 광고성 수신 동의")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/push-settings")
public interface PushSettingApi {

    @Operation(summary = "푸시 수신 설정 조회",
            description = "전체 푸시 ON/OFF, 일일 리마인더 ON/OFF·시각(Asia/Seoul HH:mm)·법적 분류, "
                    + "광고성·야간 광고성 동의 상태와 동의한 문서 버전, 최근 14일 동의 처리결과를 반환한다. "
                    + "설정 행이 아직 없는 사용자에게는 기본값(전체 ON / 리마인더 OFF·21:00 / 미동의)을 "
                    + "응답하면서 같은 요청에서 기본 행을 보정한다. `classification`은 서버가 확정한 값이라 "
                    + "항상 non-null이며 앱이 문구로 추정하지 않는다.")
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
                    + "차단하지만 종류별 설정값·시각과 법적 동의는 그대로 보존한다(다시 켜면 이전 설정으로 "
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
            description = "일일 리마인더만 바꾼다(전체 푸시와 저장된 시각은 보존). 이 알림이 광고성으로 "
                    + "분류된 경우 켜려면 광고성 수신 동의가 있어야 하고, 저장된 시각이 야간(21:00~08:00)이면 "
                    + "야간 동의도 있어야 한다 — 없으면 아무 값도 바꾸지 않고 409로 거절한다. 끄는 요청에는 "
                    + "동의를 요구하지 않으며 법적 동의를 자동 철회하지도 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "변경 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — enabled 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-4002` — 필요한 수신 동의 없음(동의 화면으로 분기)")
    })
    @PutMapping("/daily-reminder/enabled")
    @LoginTermsExempt
    ResponseEntity<ApiResponse<Void>> updateDailyReminderEnabled(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody PushEnabledRequest request);

    @Operation(summary = "일일 리마인더 시각 설정",
            description = "분 단위 `HH:mm`만 허용하며 기준 timezone은 서버가 `Asia/Seoul`로 고정한다"
                    + "(클라이언트 timezone을 받지 않는다). 형식·범위 오류는 DB 변경 전에 400으로 거절한다. "
                    + "켜져 있는 광고성 알림을 야간(21:00~08:00)으로 옮기려면 야간 동의가 필요하다 — 꺼져 "
                    + "있으면 동의 없이 저장할 수 있고 발송은 계속 차단된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "변경 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — time 누락·형식 오류(HH:mm 아님)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-4002` — 야간 시각에 필요한 야간 수신 동의 없음")
    })
    @PutMapping("/daily-reminder/time")
    @LoginTermsExempt
    ResponseEntity<ApiResponse<Void>> updateDailyReminderTime(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody DailyReminderTimeRequest request);

    @Operation(summary = "광고성 푸시 수신 동의·철회",
            description = "동의(`consented=true`)는 `ADVERTISING_PUSH_CONSENT`의 현재 유효 문서 버전과 "
                    + "정확히 일치하는 `termVersion`을 요구한다(불일치·미존재는 409 `-3002`). 철회는 "
                    + "`termVersion` 없이 보내며 켜져 있던 야간 동의도 같은 트랜잭션에서 함께 철회된다"
                    + "(그 경우 응답 배열에 두 건이 담긴다). 응답의 처리결과는 앱이 즉시 표시한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "처리 성공 — 처리결과 배열", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — clientRequestId/consented 누락, 동의인데 termVersion 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-3002` 문서 버전 불일치 / `-4003` 같은 clientRequestId로 다른 의사 표시")
    })
    @PutMapping("/advertising-consent")
    @LoginTermsExempt
    ResponseEntity<ApiResponse<List<NotificationConsentResultResponse>>> updateAdvertisingConsent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody NotificationConsentRequest request);

    @Operation(summary = "야간 광고성 푸시 수신 동의·철회",
            description = "21:00~08:00(Asia/Seoul) 광고성 전송에만 적용되는 별도 선택 동의다. 일반 광고성 "
                    + "수신 동의가 ON일 때만 켤 수 있다(아니면 409 `-4002`). 검증 문서는 "
                    + "`NIGHT_ADVERTISING_PUSH_CONSENT`의 현재 유효 버전이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "처리 성공 — 처리결과 배열", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — clientRequestId/consented 누락, 동의인데 termVersion 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-4002` 일반 광고 동의 없음 / `-3002` 문서 버전 불일치 / "
                            + "`-4003` 같은 clientRequestId로 다른 의사 표시")
    })
    @PutMapping("/night-advertising-consent")
    @LoginTermsExempt
    ResponseEntity<ApiResponse<List<NotificationConsentResultResponse>>> updateNightAdvertisingConsent(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId,
            @RequestBody NotificationConsentRequest request);
}
