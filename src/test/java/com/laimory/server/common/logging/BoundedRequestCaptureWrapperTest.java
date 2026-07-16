package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BoundedRequestCaptureWrapperTest {

    private static final int LIMIT = AccessLogBodyMasker.CAPTURE_LIMIT_BYTES;

    @Test
    void unknownLength_exactLimit_isFullyCachedWithoutOverflow() throws Exception {
        byte[] body = bytes(LIMIT);
        BoundedRequestCaptureWrapper wrapper = new BoundedRequestCaptureWrapper(
                unknownLengthRequest(body), LIMIT);

        byte[] downstreamBody = wrapper.getInputStream().readAllBytes();

        assertThat(downstreamBody).isEqualTo(body);
        assertThat(wrapper.getContentAsByteArray()).isEqualTo(body);
        assertThat(wrapper.isOverflowed()).isFalse();
    }

    @Test
    void unknownLength_overLimit_keepsDownstreamBodyAndMarksBoundedCacheOverflow() throws Exception {
        byte[] body = bytes(LIMIT + 1);
        BoundedRequestCaptureWrapper wrapper = new BoundedRequestCaptureWrapper(
                unknownLengthRequest(body), LIMIT);

        byte[] downstreamBody = wrapper.getInputStream().readAllBytes();

        assertThat(downstreamBody).isEqualTo(body);
        assertThat(wrapper.getContentAsByteArray()).hasSize(LIMIT);
        assertThat(wrapper.isOverflowed()).isTrue();
    }

    @Test
    void knownLength_overLimit_stillLetsDownstreamReadAllBytesAndBoundsCache() throws Exception {
        byte[] body = bytes(LIMIT + 1);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        request.setContentType("application/json");
        request.setContent(body);
        BoundedRequestCaptureWrapper wrapper = new BoundedRequestCaptureWrapper(request, LIMIT);

        byte[] downstreamBody = wrapper.getInputStream().readAllBytes();

        assertThat(request.getContentLengthLong()).isEqualTo(LIMIT + 1L);
        assertThat(downstreamBody).isEqualTo(body);
        assertThat(wrapper.getContentAsByteArray()).hasSize(LIMIT);
        assertThat(wrapper.isOverflowed()).isTrue();
    }

    private static MockHttpServletRequest unknownLengthRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    private static byte[] bytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) ('a' + index % 26);
        }
        return bytes;
    }
}
