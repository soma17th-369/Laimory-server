package com.laimory.server.testsupport;

import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.entity.TimelineDraftTask;

/**
 * 단계별 토큰 hash 픽스처. 토큰 자체가 관심사가 아닌 테스트(상태 전이·index·TTL 등)가 같은 자리 값을
 * 반복해 만들지 않도록 모아 둔다.
 */
public final class TaskTokenFixtures {

    private TaskTokenFixtures() {
    }

    /** 세 단계 모두 같은 자리 값을 쓰는 hash 묶음(단계 구분이 검증 대상이 아닐 때). */
    public static TimelineDraftTask.TokenHashes tokenHashes(String hash) {
        return new TimelineDraftTask.TokenHashes(hash, hash, hash);
    }

    /** 실제 파생 규칙을 따르는 hash 묶음 — 단계별 토큰 검증이 관심사인 테스트가 쓴다. */
    public static TimelineDraftTask.TokenHashes derivedTokenHashes(String inputToken, String taskId) {
        String resultToken = TaskTokens.deriveResultToken(inputToken, taskId);
        return new TimelineDraftTask.TokenHashes(
                TaskTokens.hash(inputToken),
                TaskTokens.hash(resultToken),
                TaskTokens.hash(TaskTokens.deriveCallbackToken(resultToken, taskId)));
    }
}
