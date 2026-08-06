package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

class TimelineDraftSourceItemBatchRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TimelineDraftSourceItemBatchRepository repository =
            new TimelineDraftSourceItemBatchRepository(jdbcTemplate, objectMapper);

    @Test
    void emptyItems_doNotExecuteJdbcBatch() {
        repository.insertAll(List.of());

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void insertsAllItemsInInputOrderAsOneJdbcBatch() throws Exception {
        List<TimelineDraftSourceItem> items = IntStream.range(0, 68)
                .mapToObj(index -> sourceItem("raw-" + (67 - index), index))
                .toList();

        repository.insertAll(items);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), setterCaptor.capture());
        assertThat(sqlCaptor.getValue()).doesNotContainIgnoringCase("current_timestamp");
        BatchPreparedStatementSetter setter = setterCaptor.getValue();
        assertThat(setter.getBatchSize()).isEqualTo(68);

        PreparedStatement statement = mock(PreparedStatement.class);
        for (int index = 0; index < setter.getBatchSize(); index++) {
            setter.setValues(statement, index);
        }

        ArgumentCaptor<String> rawIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(statement, times(68)).setString(eq(4), rawIdCaptor.capture());
        assertThat(rawIdCaptor.getAllValues())
                .containsExactlyElementsOf(items.stream().map(TimelineDraftSourceItem::getRawId).toList());
        verify(statement, times(68)).setNull(6, Types.TIMESTAMP);
        verify(statement, times(68)).setString(eq(7), anyString());

        ArgumentCaptor<Timestamp> createdAtCaptor = ArgumentCaptor.forClass(Timestamp.class);
        ArgumentCaptor<Timestamp> updatedAtCaptor = ArgumentCaptor.forClass(Timestamp.class);
        verify(statement, times(68)).setTimestamp(eq(8), createdAtCaptor.capture());
        verify(statement, times(68)).setTimestamp(eq(9), updatedAtCaptor.capture());
        assertThat(createdAtCaptor.getAllValues()).containsOnly(createdAtCaptor.getValue());
        assertThat(updatedAtCaptor.getAllValues()).containsExactlyElementsOf(createdAtCaptor.getAllValues());
    }

    @Test
    void payloadSerializationFailure_happensBeforeJdbcExecution_withoutLeakingInput() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new JsonProcessingException("failed") { });
        TimelineDraftSourceItemBatchRepository failingRepository =
                new TimelineDraftSourceItemBatchRepository(jdbcTemplate, failingMapper);

        assertThatThrownBy(() -> failingRepository.insertAll(List.of(sourceItem("sensitive-raw-id", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("draft source payload serialization failed")
                .hasMessageNotContaining("sensitive-raw-id");

        verify(jdbcTemplate, never()).batchUpdate(anyString(),
                org.mockito.ArgumentMatchers.any(BatchPreparedStatementSetter.class));
    }

    private TimelineDraftSourceItem sourceItem(String rawId, int index) {
        return TimelineDraftSourceItem.of(
                "11111111-1111-1111-1111-111111111111",
                7L,
                ItemType.CALENDAR,
                rawId,
                LocalDateTime.of(2026, 8, 6, 9, 0).plusMinutes(index),
                null,
                objectMapper.createObjectNode().put("index", index));
    }
}
