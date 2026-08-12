package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** draft source item INSERT 전용 JDBC batch writer. 생성 PK는 호출부에서 사용하지 않는다. */
@Repository
@RequiredArgsConstructor
public class TimelineDraftSourceItemBatchRepository {

    private static final String INSERT_SQL = "insert into timeline_draft_source_items "
            + "(task_id, subject_id, item_type, raw_id, start_at, end_at, payload, created_at, updated_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void insertAll(List<TimelineDraftSourceItem> items) {
        if (items.isEmpty()) {
            return;
        }

        List<String> payloads = items.stream()
                .map(item -> serializePayload(item.getPayload()))
                .toList();
        LocalDateTime auditAt = LocalDateTime.now();

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                TimelineDraftSourceItem item = items.get(index);
                statement.setString(1, item.getTaskId());
                statement.setBytes(2, item.getSubjectId().bytes());
                statement.setString(3, item.getItemType().name());
                statement.setString(4, item.getRawId());
                setTimestamp(statement, 5, item.getStartAt());
                setTimestamp(statement, 6, item.getEndAt());
                statement.setString(7, payloads.get(index));
                setTimestamp(statement, 8, auditAt);
                setTimestamp(statement, 9, auditAt);
            }

            @Override
            public int getBatchSize() {
                return items.size();
            }
        });
    }

    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("draft source payload serialization failed", e);
        }
    }

    private static void setTimestamp(PreparedStatement statement, int parameterIndex, LocalDateTime value)
            throws SQLException {
        if (value == null) {
            statement.setNull(parameterIndex, Types.TIMESTAMP);
            return;
        }
        statement.setTimestamp(parameterIndex, Timestamp.valueOf(value));
    }
}
