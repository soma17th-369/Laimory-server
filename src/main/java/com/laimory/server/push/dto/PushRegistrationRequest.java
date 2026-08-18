package com.laimory.server.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FID 등록·해제 공용 request body — FID는 URL(path/query)이 아닌 body로 받아 access log·프록시 URL
 * 노출을 막는다. 민감 opaque 식별자라 access log body에서도 마스킹된다(문서에 예시 값을 두지 않는다).
 */
@Schema(description = "푸시 등록 요청")
public record PushRegistrationRequest(
        @Schema(description = "Firebase Installation ID(FID) — Firebase Messaging 등록 콜백(onRegistered)이 "
                + "전달한 원문을 가공(trim·대소문자 변환) 없이 그대로 보낸다. 민감 opaque 식별자로 취급한다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String firebaseInstallationId,

        @Schema(description = "설치별 수신거부 credential — 앱이 만들어 private storage에 보관하는 256-bit "
                + "무작위 값을 padding 없는 base64url 43자로 보낸다. 서버는 SHA-256 hash만 저장한다. "
                + "등록 PUT마다 같은 원문을 다시 보내고, 로그아웃·계정 전환 때 새로 만든다. 값이 없으면 "
                + "서버의 기존 hash도 지워지며 그 설치는 광고성 알림 대상에서 빠진다. 해제 DELETE는 사용하지 않는다.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String optOutToken
) {
}
