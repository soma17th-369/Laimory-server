package com.laimory.server.timeline;

/**
 * Redis PROCESSING task의 서버 간 처리 단계. 외부 polling 상태에는 노출하지 않는다.
 *
 * <p>{@link #DISPATCH_PENDING}은 접수 대기열에 적재했지만 worker가 아직 AI에 접수하지 않은 단계다(#515). 이 단계의
 * token hash는 원문을 아무에게도 주지 않은 token의 hash라 어떤 서버간 요청도 통과하지 못한다. worker가 접수 직전에
 * 새 token을 만들어 {@link #INPUT_PENDING}으로 전이한다. 구버전 서버는 이 값을 역직렬화하지 못하므로, 이 값을 쓰는
 * 경로(route=queue)는 모든 인스턴스가 이 값을 읽는 버전이 된 뒤에만 켠다.
 */
public enum ProcessStage {
    DISPATCH_PENDING,
    INPUT_PENDING,
    RESULT_PENDING,
    CALLBACK_PENDING
}
