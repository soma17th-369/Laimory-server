package com.laimory.server.timeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * AI 결과 저장 요청의 지문. 응답 유실 뒤 재요청이 <b>같은 결과</b>인지 판정하는 데만 쓴다 — 요청 본문은
 * 저장하지 않고 이 지문만 task에 남긴다.
 *
 * <p>지문은 <b>치환본</b>으로 계산한다(저장 경계는 v1 privacy 치환 후 값만 본다). 그래서 원문 PII만 다르고
 * 치환 결과가 같은 두 body는 같은 결과로 취급한다 — 실제로 저장될 graph가 동일하기 때문이다.
 *
 * <p>직렬화 형태가 지문을 정하므로 앱 전역 Jackson 설정에서 격리된 전용 mapper를 소유한다. 전역
 * {@code spring.jackson.*} 변경이 살아있는 receipt를 조용히 무효화하면 안 된다. TimeZone을 지정하지 않아
 * context timezone 보정이 끼어들지 않고, DTO의 {@code @JsonFormat} pattern이 {@code OffsetDateTime}
 * 직렬화를 결정적으로 고정한다. record component 순서가 곧 JSON 순서다.
 *
 * <p>{@link AiTimelineResultRequest}에 필드를 추가하면 지문이 함께 바뀐다 — golden 테스트가 깨지는 것이
 * 정상이며 새 값으로 다시 고정하면 된다.
 */
public final class AiTimelineResultDigest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private AiTimelineResultDigest() {
    }

    /** 치환본 결과 요청의 SHA-256을 Base64로 반환한다. */
    public static String of(AiTimelineResultRequest redacted) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsString(redacted).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI 결과 지문 직렬화에 실패했습니다", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
