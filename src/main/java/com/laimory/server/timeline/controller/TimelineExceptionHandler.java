package com.laimory.server.timeline.controller;

import com.laimory.server.timeline.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 타임라인 컨트롤러 예외 매핑. IllegalArgumentException→400, IllegalStateException→409.
 * 404(task 미존재)는 서비스/인터셉터가 던지는 ResponseStatusException을 프레임워크가 처리하므로 별도 매핑이 없다.
 */
@RestControllerAdvice(assignableTypes = TimelineController.class)
public class TimelineExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }
}
