package com.laimory.server.push;

import java.util.List;

/**
 * 발송 시도 결과 — 개수와 영구 무효(등록 삭제 대상) FID 목록만 담는다. Firebase 응답 원문·message ID는
 * 보존하지 않는다(FCM 수락은 기기 표시 보장이 아니고, 로그에도 개수·오류 분류만 남기는 계약).
 */
public record PushSendResult(int targetCount, int successCount, int failureCount,
                             List<String> invalidFirebaseInstallationIds) {

    public static PushSendResult empty() {
        return new PushSendResult(0, 0, 0, List.of());
    }
}
