package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class BoundedResponseCaptureWrapperTest {

    private static final int LIMIT = AccessLogBodyMasker.CAPTURE_LIMIT_BYTES;

    @Test
    void outputStream_writesThroughAndOnlyCaptureIsBounded() throws Exception {
        MockHttpServletResponse original = new MockHttpServletResponse();
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);
        byte[] body = "a".repeat(LIMIT + 1).getBytes(StandardCharsets.UTF_8);

        wrapper.getOutputStream().write(body);

        assertThat(original.getContentAsByteArray()).isEqualTo(body);
        assertThat(wrapper.getCapturedBytes()).hasSize(LIMIT);
        assertThat(wrapper.getObservedByteCount()).isEqualTo(LIMIT + 1L);
        assertThat(wrapper.isOverflowed()).isTrue();
    }

    @Test
    void writer_writesThroughWithoutWrapperFlush_andCapturesUsingResponseCharset() throws Exception {
        MockHttpServletResponse original = new MockHttpServletResponse();
        original.setCharacterEncoding(StandardCharsets.UTF_8.name());
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);

        wrapper.getWriter().write("응답 본문");

        assertThat(original.getContentAsString()).isEqualTo("응답 본문");
        assertThat(new String(wrapper.getCapturedBytes(), StandardCharsets.UTF_8)).isEqualTo("응답 본문");
        assertThat(wrapper.isOverflowed()).isFalse();
    }

    @Test
    void writer_stringWrite_keepsEmojiAcrossInternalChunkBoundary() throws Exception {
        MockHttpServletResponse original = new MockHttpServletResponse();
        original.setCharacterEncoding(StandardCharsets.UTF_8.name());
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);
        // high surrogate가 index 1023, low surrogate가 1024 — 내부 1024-char 인코딩 chunk 경계에 정확히 걸친다.
        // 경계에서 pair를 가르면 각 반쪽이 U+FFFD로 인코딩돼 capture가 원문과 달라진다.
        String body = "a".repeat(1023) + "😀" + "tail";
        assertThat(Character.isHighSurrogate(body.charAt(1023))).isTrue();
        assertThat(Character.isLowSurrogate(body.charAt(1024))).isTrue();

        wrapper.getWriter().write(body);

        assertThat(original.getContentAsString()).isEqualTo(body);
        String captured = new String(wrapper.getCapturedBytes(), StandardCharsets.UTF_8);
        assertThat(captured).isEqualTo(body);
        assertThat(captured).doesNotContain("�");
        assertThat(wrapper.getObservedByteCount()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(wrapper.isOverflowed()).isFalse();
    }

    @Test
    void writer_charArrayWrite_keepsEmojiAcrossInternalChunkBoundary() throws Exception {
        // String write와 char[] write는 chunk 분할 branch가 별개 구현이라 각각 고정한다.
        MockHttpServletResponse original = new MockHttpServletResponse();
        original.setCharacterEncoding(StandardCharsets.UTF_8.name());
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);
        String body = "a".repeat(1023) + "😀" + "tail";
        assertThat(Character.isHighSurrogate(body.charAt(1023))).isTrue();
        assertThat(Character.isLowSurrogate(body.charAt(1024))).isTrue();

        wrapper.getWriter().write(body.toCharArray());

        assertThat(original.getContentAsString()).isEqualTo(body);
        String captured = new String(wrapper.getCapturedBytes(), StandardCharsets.UTF_8);
        assertThat(captured).isEqualTo(body);
        assertThat(captured).doesNotContain("�");
        assertThat(wrapper.getObservedByteCount()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(wrapper.isOverflowed()).isFalse();
    }

    @Test
    void writerAndOutputStream_areMutuallyExclusive() throws Exception {
        BoundedResponseCaptureWrapper writerFirst =
                new BoundedResponseCaptureWrapper(new MockHttpServletResponse(), LIMIT);
        writerFirst.getWriter();

        assertThatThrownBy(writerFirst::getOutputStream)
                .isInstanceOf(IllegalStateException.class);

        BoundedResponseCaptureWrapper streamFirst =
                new BoundedResponseCaptureWrapper(new MockHttpServletResponse(), LIMIT);
        streamFirst.getOutputStream();

        assertThatThrownBy(streamFirst::getWriter)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resetBuffer_discardsOriginalAndCapturedBodyTogether() throws Exception {
        MockHttpServletResponse original = new MockHttpServletResponse();
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);

        wrapper.getOutputStream().write("discarded".getBytes(StandardCharsets.UTF_8));
        wrapper.resetBuffer();
        wrapper.getOutputStream().write("final".getBytes(StandardCharsets.UTF_8));

        assertThat(original.getContentAsString()).isEqualTo("final");
        assertThat(new String(wrapper.getCapturedBytes(), StandardCharsets.UTF_8)).isEqualTo("final");
        assertThat(wrapper.getObservedByteCount()).isEqualTo(5);
    }

    @Test
    void sendRedirect_discardsEarlierCapture() throws Exception {
        MockHttpServletResponse original = new MockHttpServletResponse();
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);
        wrapper.getOutputStream().write("discarded".getBytes(StandardCharsets.UTF_8));

        wrapper.sendRedirect("/next");

        assertThat(original.getRedirectedUrl()).isEqualTo("/next");
        assertThat(wrapper.getCapturedBytes()).isEmpty();
        assertThat(wrapper.getObservedByteCount()).isZero();
    }

    @Test
    void reset_discardsBodyAndResponseMetadataBeforeNewWrite() throws Exception {
        MockHttpServletResponse original = new MockHttpServletResponse();
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);
        wrapper.setStatus(201);
        wrapper.getOutputStream().write("discarded".getBytes(StandardCharsets.UTF_8));

        wrapper.reset();
        wrapper.getOutputStream().write("final".getBytes(StandardCharsets.UTF_8));

        assertThat(original.getStatus()).isEqualTo(200);
        assertThat(original.getContentAsString()).isEqualTo("final");
        assertThat(new String(wrapper.getCapturedBytes(), StandardCharsets.UTF_8)).isEqualTo("final");
    }

    @Test
    void sendError_discardsEarlierCaptureAndOriginalBuffer() throws Exception {
        MockHttpServletResponse original = new MockHttpServletResponse();
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);
        wrapper.getOutputStream().write("discarded".getBytes(StandardCharsets.UTF_8));

        wrapper.sendError(503);

        assertThat(original.getStatus()).isEqualTo(503);
        // MockHttpServletResponse는 container와 달리 sendError 시 기존 body buffer를 비우지 않는다.
        // wrapper capture는 실제 API 호출 성공 뒤 반드시 같은 시점에 폐기되어야 한다.
        assertThat(wrapper.getCapturedBytes()).isEmpty();
        assertThat(wrapper.getObservedByteCount()).isZero();
    }

    @Test
    void outputStream_delegatesNonBlockingMethods() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        WriteListener[] assignedListener = new WriteListener[1];
        ServletOutputStream delegate = new ServletOutputStream() {
            @Override
            public void write(int value) {
                body.write(value);
            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                assignedListener[0] = listener;
            }
        };
        MockHttpServletResponse original = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return delegate;
            }
        };
        BoundedResponseCaptureWrapper wrapper = new BoundedResponseCaptureWrapper(original, LIMIT);
        WriteListener listener = new WriteListener() {
            @Override
            public void onWritePossible() throws IOException {
            }

            @Override
            public void onError(Throwable throwable) {
            }
        };

        assertThat(wrapper.getOutputStream().isReady()).isFalse();
        wrapper.getOutputStream().setWriteListener(listener);
        wrapper.getOutputStream().write('x');

        assertThat(assignedListener[0]).isSameAs(listener);
        assertThat(body.toByteArray()).containsExactly((byte) 'x');
    }
}
