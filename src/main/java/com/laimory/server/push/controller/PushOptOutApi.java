package com.laimory.server.push.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.push.dto.NotificationConsentResultResponse;
import com.laimory.server.push.dto.PushOptOutRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 비로그인 installation 수신거부 API의 문서·계약(구현은 {@link PushOptOutController}).
 *
 * <p>광고성 알림의 수신거부 action이 로그인 화면이나 중간 메뉴를 거치지 않고 호출한다 — 그래서 bearer가
 * 없는 public {@code /api}에 둔다. 인증 대신 설치가 보관한 FID와 수신거부 credential로 대상을 특정하며,
 * 이 경로가 줄 수 있는 권한은 <b>광고성 수신 철회</b> 하나뿐이다(동의·다른 설정 변경 불가).
 *
 * <p>FID·token은 URL이 아닌 body로 받는다 — access log·프록시 URL에 원문이 남지 않게 한다.
 */
@Tag(name = "Push Opt-out", description = "비로그인 광고성 푸시 수신거부")
@RequestMapping(ApiUrls.API_URL + "/push-opt-outs")
public interface PushOptOutApi {

    @Operation(summary = "비로그인 광고성 수신거부",
            description = "설치가 보관한 FID와 수신거부 credential을 검증해 해당 설치 소유자의 광고성 "
                    + "수신 동의를 철회한다. 켜져 있던 야간 동의도 함께 철회되어 응답 배열에 두 건이 담긴다. "
                    + "FID 등록은 삭제하지 않는다 — 정보성 알림 수신과 같은 요청의 재시도가 유지된다. "
                    + "재시도해도 상태는 어긋나지 않으며 이미 철회 상태면 `ALREADY_IN_STATE`로 응답한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "철회 성공 — 처리결과 배열", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-4001` — 수신거부 credential 무효. FID 미등록·token 미제출·불일치를 "
                            + "구분하지 않는 단일 응답이다(등록 존재 여부를 노출하지 않는다).")
    })
    @PostMapping
    ResponseEntity<ApiResponse<List<NotificationConsentResultResponse>>> optOut(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @RequestBody PushOptOutRequest request);
}
