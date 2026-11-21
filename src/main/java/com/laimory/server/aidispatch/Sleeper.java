package com.laimory.server.aidispatch;

import java.time.Duration;

/**
 * 재시도 사이의 대기(#515). 운영은 {@link #SYSTEM}(스레드 sleep)이고, 테스트는 대기 없이 요청 시간만 기록하는 구현을
 * 넣어 결정적으로 검증한다.
 */
@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = duration -> Thread.sleep(duration.toMillis());

    void sleep(Duration duration) throws InterruptedException;
}
