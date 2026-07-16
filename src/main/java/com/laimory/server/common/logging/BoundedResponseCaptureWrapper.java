package com.laimory.server.common.logging;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/** 원 응답에 즉시 write하면서 access log용 앞부분만 제한적으로 복사하는 response decorator. */
final class BoundedResponseCaptureWrapper extends HttpServletResponseWrapper {

    private static final int CHAR_ENCODING_CHUNK_SIZE = 1024;

    private final BoundedCapture capture;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    BoundedResponseCaptureWrapper(HttpServletResponse response, int captureLimit) {
        super(response);
        this.capture = new BoundedCapture(captureLimit);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (outputStream == null) {
            ServletOutputStream delegate = super.getOutputStream();
            outputStream = new ServletOutputStream() {
                @Override
                public void write(int value) throws IOException {
                    delegate.write(value);
                    capture.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    delegate.write(bytes, offset, length);
                    capture.write(bytes, offset, length);
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    delegate.setWriteListener(listener);
                }

                @Override
                public void flush() throws IOException {
                    delegate.flush();
                }

                @Override
                public void close() throws IOException {
                    delegate.close();
                }
            };
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputStream != null) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        if (writer == null) {
            PrintWriter delegate = super.getWriter();
            Charset charset = Charset.forName(getCharacterEncoding());
            Writer tee = new Writer() {
                @Override
                public void write(char[] chars, int offset, int length) {
                    delegate.write(chars, offset, length);
                    capture.write(chars, offset, length, charset);
                }

                @Override
                public void write(String value, int offset, int length) {
                    delegate.write(value, offset, length);
                    capture.write(value, offset, length, charset);
                }

                @Override
                public void flush() {
                    delegate.flush();
                }

                @Override
                public void close() {
                    delegate.close();
                }
            };
            writer = new PrintWriter(tee) {
                @Override
                public boolean checkError() {
                    return delegate.checkError() || super.checkError();
                }
            };
        }
        return writer;
    }

    byte[] getCapturedBytes() {
        return capture.toByteArray();
    }

    long getObservedByteCount() {
        return capture.observedByteCount();
    }

    boolean isOverflowed() {
        return capture.isOverflowed();
    }

    @Override
    public void resetBuffer() {
        super.resetBuffer();
        capture.reset();
    }

    @Override
    public void reset() {
        super.reset();
        capture.reset();
    }

    @Override
    public void sendError(int status) throws IOException {
        super.sendError(status);
        capture.reset();
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        super.sendError(status, message);
        capture.reset();
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        super.sendRedirect(location);
        capture.reset();
    }

    private static final class BoundedCapture {

        private final int limit;
        private final ByteArrayOutputStream captured;
        private long observedByteCount;

        private BoundedCapture(int limit) {
            if (limit < 0) {
                throw new IllegalArgumentException("capture limit must not be negative");
            }
            this.limit = limit;
            this.captured = new ByteArrayOutputStream();
        }

        private void write(int value) {
            observedByteCount++;
            if (captured.size() < limit) {
                captured.write(value);
            }
        }

        private void write(byte[] bytes, int offset, int length) {
            if (length <= 0) {
                return;
            }
            observedByteCount += length;
            int remaining = limit - captured.size();
            if (remaining > 0) {
                captured.write(bytes, offset, Math.min(length, remaining));
            }
        }

        private void write(char[] chars, int offset, int length, Charset charset) {
            int end = offset + length;
            for (int cursor = offset; cursor < end;) {
                int chunkEnd = Math.min(cursor + CHAR_ENCODING_CHUNK_SIZE, end);
                if (chunkEnd < end
                        && Character.isHighSurrogate(chars[chunkEnd - 1])
                        && Character.isLowSurrogate(chars[chunkEnd])) {
                    chunkEnd--;
                }
                byte[] encoded = new String(chars, cursor, chunkEnd - cursor).getBytes(charset);
                write(encoded, 0, encoded.length);
                cursor = chunkEnd;
            }
        }

        private void write(String value, int offset, int length, Charset charset) {
            char[] chunk = new char[Math.min(CHAR_ENCODING_CHUNK_SIZE, length)];
            int end = offset + length;
            for (int cursor = offset; cursor < end;) {
                int chunkLength = Math.min(chunk.length, end - cursor);
                if (cursor + chunkLength < end
                        && Character.isHighSurrogate(value.charAt(cursor + chunkLength - 1))
                        && Character.isLowSurrogate(value.charAt(cursor + chunkLength))) {
                    chunkLength--;
                }
                value.getChars(cursor, cursor + chunkLength, chunk, 0);
                write(chunk, 0, chunkLength, charset);
                cursor += chunkLength;
            }
        }

        private byte[] toByteArray() {
            return captured.toByteArray();
        }

        private long observedByteCount() {
            return observedByteCount;
        }

        private boolean isOverflowed() {
            return observedByteCount > limit;
        }

        private void reset() {
            captured.reset();
            observedByteCount = 0;
        }
    }
}
