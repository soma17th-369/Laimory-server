package com.laimory.server.user;

/**
 * 계정 삭제 작업 상태(#305 접수 · #302 처리).
 *
 * <p>단계 이름은 "다음에 할 일"이 아니라 <b>"여기까지 끝났다"</b>를 뜻한다 — 재시작이 항상 다음
 * 단계부터라 crash·경합 뒤에도 같은 단계를 두 번 하지 않는다. 전이는 전부
 * {@code (jobId, expectedStatus)} 조건부 UPDATE라 후발 worker는 0행으로 no-op한다.
 *
 * <p><b>완료 상태는 없다.</b> 완료는 행 삭제이며, 그것이 {@code users}를 향한
 * {@code ON DELETE RESTRICT}를 푸는 유일한 신호다.
 *
 * <p>콘텐츠 graph 삭제({@code DATABASE_CLEANED})와 S3 정리({@code S3_CLEANED})는 후속 PR이 이 체인
 * <b>뒤에</b> 끼워 넣는다. 새 단계가 항상 기존 단계 뒤에 들어가므로 중간 단계에 멈춰 있는 job이 있어도
 * 다음 배포가 이어서 처리한다.
 */
public enum AccountErasureJobStatus {

    /** 탈퇴 transaction이 접수한 미처리 삭제 요청(#305). 아직 정지도 삭제도 하지 않았다. */
    PENDING,

    /**
     * 정지 완료 — 이 subject로 새 AI 작업이 발급되지 않는다(User Memory 미반영 큐를 비웠다).
     * 데이터는 아직 지우지 않았다. 유예가 지나면 여기서 삭제 단계로 넘어간다.
     */
    QUIESCED,

    /**
     * 사람이 봐야 하는 실패 — 자동 재시도에서 제외된다. mapping 해석 불가, 회원 상태 불일치처럼
     * <b>무엇이 잘못됐는지 아는</b> 경우다. 이유를 모른 채 처리 창을 넘긴 job은 이 상태가 아니라
     * 만료로 분류되며 둘 다 건수만 ERROR 로그로 경보한다.
     */
    MANUAL_REVIEW
}
