package com.laimory.server.testsupport;

import com.laimory.server.timeline.TaskTokens;

/** 단일 task token hash 픽스처. */
public final class TaskTokenFixtures {

    private TaskTokenFixtures() {
    }

    /** 기존 테스트 호출부의 이름을 유지하는 단일 hash 픽스처. */
    public static String tokenHashes(String hash) {
        return hash;
    }

    /** 실제 task token 원문에서 만든 hash. taskId는 구 테스트 호출부 호환용이며 hash에 사용하지 않는다. */
    public static String derivedTokenHashes(String taskToken, String taskId) {
        return TaskTokens.hash(taskToken);
    }
}
