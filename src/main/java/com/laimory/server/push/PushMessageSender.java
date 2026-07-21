package com.laimory.server.push;

import com.laimory.server.timeline.TaskStatus;
import java.util.List;

/**
 * 타임라인 완료 푸시 발송 port(Firebase SDK 독립). 구현은 {@code app.push.mode}로 정확히 하나만
 * 선택된다 — noop(기본)·firebase. 매칭 구현이 없으면(오타) 주입 실패로 컨텍스트가 기동하지 않는다.
 */
public interface PushMessageSender {

    /**
     * owner의 활성 설치 전체(FID)에 terminal({@code SUCCESS|FAILED}) 완료 알림을 발송한다.
     * 개별 실패는 결과 개수·무효 FID 목록으로 돌려주고 호출 수준 전이 오류도 실패 개수로 흡수한다 —
     * 발송 실패가 callback 처리로 전파되지 않는 best-effort 계약이다.
     */
    PushSendResult send(String taskId, TaskStatus status, List<String> firebaseInstallationIds);
}
