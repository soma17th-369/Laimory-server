package com.laimory.server.user.service;

/**
 * finalization 중 기대한 행이 없을 때 던진다(#302).
 *
 * <p><b>이 예외가 밖으로 나가는 것이 rollback 조건이다.</b> Spring 선언적 transaction은 예외 전파로만
 * rollback하므로, 영향 0행을 boolean으로 되돌려주면 그때까지의 DELETE가 그대로 commit된다 — mapping은
 * 지워졌는데 회원 행은 남는 반쪽 상태가 만들어진다. 그래서 finalization의 모든 예상 밖 0행은 값이
 * 아니라 예외로 보고한다.
 *
 * <p>정상 경합(다른 worker가 이미 완료)도 여기로 온다. 그 경우 rollback할 변경이 애초에 없고, 다음
 * 실행에서 mapping 부재로 job이 이미 사라졌음이 확인돼 조용히 수렴한다.
 *
 * <p>메시지에 userId·subjectId·jobId를 담지 않는다 — 어느 단계였는지만 남긴다.
 */
public class AccountErasureConflictException extends IllegalStateException {

    public AccountErasureConflictException(String step) {
        super("account erasure finalization conflict at step: " + step);
    }
}
