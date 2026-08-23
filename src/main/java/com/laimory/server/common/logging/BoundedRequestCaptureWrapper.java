package com.laimory.server.common.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** 요청 소비를 방해하지 않고 제한된 로그용 cache의 overflow 여부만 추가로 노출한다. */
final class BoundedRequestCaptureWrapper extends ContentCachingRequestWrapper {

    private boolean overflowed;

    BoundedRequestCaptureWrapper(HttpServletRequest request, int cacheLimit) {
        super(request, cacheLimit);
    }

    @Override
    protected void handleContentOverflow(int contentCacheLimit) {
        overflowed = true;
    }

    boolean isOverflowed() {
        return overflowed;
    }
}
