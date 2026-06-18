package com.laimory.server.timeline.controller;

import com.laimory.server.timeline.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 타임라인 컨트롤러 예외 매핑. 잘못된 요청 IllegalArgumentException→400.
 *
 * <p>상태 코드가 필요한 경우(409 SAVED 충돌, 404 task 미존재, 401 토큰 불일치)는 발생 지점에서
 * {@code ResponseStatusException}으로 던지고 프레임워크가 처리한다. 일반 {@code IllegalStateException}은
 * 서버 내부 불변식 위반(Redis 직렬화 실패 등)이므로 여기서 잡지 않고 기본 500으로 둔다 — 충돌과 내부 오류를 구분한다.
 */
@RestControllerAdvice(assignableTypes = {TimelineController.class, TimelineCallbackController.class})
public class TimelineExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }
}
