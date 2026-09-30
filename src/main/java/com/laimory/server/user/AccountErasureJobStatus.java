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
 * <p>삭제 pass는 콘텐츠 graph·owner 행·S3·finalization을 한 job 처리 안에서 모두 끝내므로 중간 단계
 * 상태를 두지 않는다 — 각 단계가 멱등이라 실패하면 다음 실행이 처음부터 다시 한다.
 */
public enum AccountErasureJobStatus {

    /**
     * 탈퇴 transaction이 접수한 미처리 삭제 요청(#305). User Memory 미반영 큐는 탈퇴 commit 직후 비웠고
     * (#397), 데이터는 아직 지우지 않았다. 유예가 지나면 삭제 pass가 여기서 바로 처리한다.
     */
    PENDING,

    /**
     * 옛 정지 pass(#302, #397에서 제거)가 남긴 상태 — 큐를 비웠다는 표시였다. 더 이상 새로 만들어지지
     * 않지만, 배포 전환 중 옛 인스턴스가 남긴 행을 읽을 수 있도록 값을 남긴다. 삭제 pass는
     * {@link #PENDING}과 똑같이 처리한다.
     */
    QUIESCED,

    /**
     * 사람이 봐야 하는 실패 — 자동 재시도에서 제외된다. mapping 해석 불가, 회원 상태 불일치처럼
     * <b>무엇이 잘못됐는지 아는</b> 경우다. 이유를 모른 채 처리 창을 넘긴 job은 이 상태가 아니라
     * 만료로 분류되며 둘 다 건수만 ERROR 로그로 경보한다.
     */
    MANUAL_REVIEW
}
