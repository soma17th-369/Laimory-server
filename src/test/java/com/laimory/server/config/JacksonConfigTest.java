package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.terms.dto.TermAgreementCreateRequest;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import com.laimory.server.timeline.dto.PhotoUploadItem;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

/** Boot 관리 mapper의 전역 설정이 다른 HTTP 입력면에도 적용되는지 검증한다. */
@JsonTest
@Import(JacksonConfig.class)
class JacksonConfigTest {

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @MethodSource("scalarContracts")
    void acceptsCanonicalShapeAndRejectsCoercion(Class<?> type, String valid, String invalid) throws Exception {
        assertThat(objectMapper.readValue(valid, type)).isNotNull();
        assertThatThrownBy(() -> objectMapper.readValue(invalid, type))
                .isInstanceOf(JsonProcessingException.class);
    }

    private static Stream<Arguments> scalarContracts() {
        return Stream.of(
                Arguments.of(TermAgreementCreateRequest.class,
                        "{\"agreements\":[{\"termType\":\"TERMS_OF_SERVICE\",\"version\":\"1.0\"}]}",
                        "{\"agreements\":[{\"termType\":0,\"version\":\"1.0\"}]}"),
                Arguments.of(AiTimelineResultRequest.class,
                        "{\"events\":[{\"eventType\":\"MEAL\"}]}",
                        "{\"events\":[{\"eventType\":0}]}"),
                Arguments.of(AiUserMemoryUpdateResultRequest.class,
                        "{\"status\":\"FAILED\",\"errorCode\":1210}",
                        "{\"status\":\"FAILED\",\"errorCode\":\"1210\"}"),
                Arguments.of(PhotoUploadItem.class,
                        "{\"size\":1024}", "{\"size\":\"1024\"}"));
    }
}
