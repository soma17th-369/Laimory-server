package com.laimory.server.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 비로그인 installation 수신거부 요청. 알림의 수신거부 action에서 로그인 없이 호출한다.
 *
 * <p>FID와 token은 URL이 아닌 body로 받는다 — access log·프록시 URL에 원문이 남지 않게 한다.
 */
@Schema(description = "비로그인 수신거부 요청")
public record PushOptOutRequest(
        @Schema(description = "Firebase Installation ID(FID) — 민감 opaque 식별자로 취급한다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String firebaseInstallationId,

        @Schema(description = "설치별 수신거부 credential 원문(등록 PUT에서 보낸 값과 같아야 한다)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String optOutToken
) {
}
