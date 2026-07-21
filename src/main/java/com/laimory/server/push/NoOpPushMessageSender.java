package com.laimory.server.push;

import com.laimory.server.timeline.TaskStatus;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * no-op {@link PushMessageSender}(기본 구현) — FID 등록 API/DB는 동작하되 외부 발송은 하지 않는다.
 *
 * <p>{@code matchIfMissing = true}라 {@code app.push.mode} 미설정 환경(prod 기본·로컬·CI)에서 항상 이
 * 구현이 선택된다. 이 빈이 없으면 noop 모드 컨텍스트에서
 * {@link com.laimory.server.push.service.TimelineCompletionPushNotifier}가 주입받을 sender가 없어
 * 기동하지 못한다. FID·발송 내용은 로그하지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.push.mode", havingValue = "noop", matchIfMissing = true)
class NoOpPushMessageSender implements PushMessageSender {

    @Override
    public PushSendResult send(String taskId, TaskStatus status, List<String> firebaseInstallationIds) {
        return PushSendResult.empty();
    }
}
