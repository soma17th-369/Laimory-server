package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * User Memory leaf 서비스 계약: 조회는 행 없음이면 빈 Optional, 교체는 병합이 아닌 전체 대체이고
 * null·JSON null은 행 삭제다. 문서 내용은 해석하지 않으므로 직렬화 문자열을 그대로 넘기는지만 본다.
 * 실제 JSON 왕복은 persistence 통합 테스트가 소유한다. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryServiceTest {

    private static final long USER_ID = 7L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-05T03:00:00Z");

    @Mock
    private UserMemoryRepository userMemoryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserMemoryService service() {
        return new UserMemoryService(userMemoryRepository, Clock.fixed(NOW, ZONE));
    }

    /** 쓰기가 native query뿐이라 엔티티에 팩토리가 없다 — 조회 fixture는 필드를 직접 채운다. */
    private UserMemory memoryRow(JsonNode memory) {
        UserMemory row = new UserMemory();
        ReflectionTestUtils.setField(row, "userId", USER_ID);
        ReflectionTestUtils.setField(row, "memory", memory);
        return row;
    }

    @Test
    void find_absentRow_isEmpty() {
        when(userMemoryRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(service().find(USER_ID)).isEmpty();
    }

    @Test
    void find_existingRow_returnsStoredDocument() throws Exception {
        JsonNode document = objectMapper.readTree("{\"summary\":\"누적 요약\"}");
        when(userMemoryRepository.findById(USER_ID)).thenReturn(Optional.of(memoryRow(document)));

        assertThat(service().find(USER_ID)).contains(document);
    }

    @Test
    void replace_document_upsertsSerializedJsonWithClockTime() throws Exception {
        JsonNode document = objectMapper.readTree("{\"version\":2,\"topics\":[\"운동\"]}");

        service().replace(USER_ID, document);

        verify(userMemoryRepository).upsert(USER_ID, document.toString(),
                LocalDateTime.ofInstant(NOW, ZONE));
        verify(userMemoryRepository, never()).deleteByUserId(anyLong());
    }

    @Test
    void replace_null_deletesRow() {
        service().replace(USER_ID, null);

        verify(userMemoryRepository).deleteByUserId(USER_ID);
        verify(userMemoryRepository, never()).upsert(anyLong(), anyString(), any());
    }

    @Test
    void replace_jsonNull_deletesRow() {
        // JSON null은 의미 없는 행을 남기지 않고 "메모리 없음"으로 수렴한다.
        service().replace(USER_ID, objectMapper.nullNode());

        verify(userMemoryRepository).deleteByUserId(USER_ID);
        verify(userMemoryRepository, never()).upsert(anyLong(), anyString(), any());
    }
}
