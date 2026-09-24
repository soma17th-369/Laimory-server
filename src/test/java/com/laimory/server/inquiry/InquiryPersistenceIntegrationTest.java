package com.laimory.server.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.inquiry.entity.Inquiry;
import com.laimory.server.inquiry.repository.InquiryAttachmentRepository;
import com.laimory.server.inquiry.repository.InquiryRepository;
import com.laimory.server.inquiry.service.InquiryService;
import com.laimory.server.user.Provider;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.UserRepository;
import com.laimory.server.user.repository.UserSubjectLinkRepository;
import com.laimory.server.user.service.NewUserProvisioner;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 문의 ↔ 실 MySQL 왕복(#518) — V4 migration·엔티티 매핑(validate)·subject FK RESTRICT의 fail-closed 성질·
 * 탈퇴 삭제 순서(첨부 → 문의)를 검증한다.
 *
 * 실행: docker compose up -d --wait 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class InquiryPersistenceIntegrationTest {

    private static final String FILENAME_A = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.jpg";
    private static final String FILENAME_B = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a7.png";

    @Autowired
    private NewUserProvisioner newUserProvisioner;
    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private InquiryRepository inquiryRepository;
    @Autowired
    private InquiryAttachmentRepository inquiryAttachmentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSubjectLinkRepository userSubjectLinkRepository;
    @Autowired
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private UUID subjectId;

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        inquiryService.deleteAllBySubjectId(subjectId);
        jdbcTemplate.update("DELETE FROM daily_notification_preferences WHERE subject_id = ?", subjectId.toString());
        jdbcTemplate.update("DELETE FROM subject_preferences WHERE subject_id = ?", subjectId.toString());
        userRepository.deleteById(userId);
        userSubjectLinkRepository.deleteById(subjectLookupKeyDeriver.deriveCurrent(userId));
        userId = null;
    }

    @Test
    void registeredInquiryBlocksSubjectMappingDeletionUntilErasedInAttachmentThenInquiryOrder() {
        provisionUser();

        Inquiry inquiry = inquiryService.register("v1", subjectId, "it@example.com",
                "첫 줄\n둘째 줄", List.of(FILENAME_A, FILENAME_B));

        Inquiry stored = inquiryRepository.findByInquiryId(inquiry.getInquiryId()).orElseThrow();
        assertThat(stored.getSubjectId()).isEqualTo(subjectId);
        assertThat(stored.getEmail()).isEqualTo("it@example.com");
        assertThat(stored.getBody()).isEqualTo("첫 줄\n둘째 줄");
        assertThat(stored.getAnsweredAt()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(inquiryAttachmentRepository.findByInquiryIdOrderByPositionAsc(inquiry.getInquiryId()))
                .extracting(attachment -> attachment.getFilename())
                .containsExactly(FILENAME_A, FILENAME_B);

        // subject FK RESTRICT — 문의가 남아 있는 한 mapping 삭제는 DB가 거절한다(탈퇴 finalize의 fail-closed 근거).
        byte[] lookupKey = subjectLookupKeyDeriver.deriveCurrent(userId);
        assertThatThrownBy(() -> userSubjectLinkRepository.deleteById(lookupKey))
                .isInstanceOf(DataIntegrityViolationException.class);

        inquiryService.deleteAllBySubjectId(subjectId);

        assertThat(inquiryRepository.findByInquiryId(inquiry.getInquiryId())).isEmpty();
        assertThat(inquiryAttachmentRepository.findByInquiryIdOrderByPositionAsc(inquiry.getInquiryId())).isEmpty();
    }

    @Test
    void answeredMarkIsPersistedAndClearable() {
        provisionUser();
        Inquiry inquiry = inquiryService.register("v1", subjectId, "it@example.com",
                "제안", null);

        inquiryService.changeAnswered(inquiry.getInquiryId(), true);
        assertThat(inquiryRepository.findByInquiryId(inquiry.getInquiryId()).orElseThrow().getAnsweredAt())
                .isNotNull();

        inquiryService.changeAnswered(inquiry.getInquiryId(), false);
        assertThat(inquiryRepository.findByInquiryId(inquiry.getInquiryId()).orElseThrow().getAnsweredAt())
                .isNull();
    }

    private void provisionUser() {
        User user = newUserProvisioner.provision(Provider.KAKAO,
                "inquiry-it-" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L),
                null, "문의테스트");
        userId = user.getUserId();
        /* subject_id는 VARCHAR(36)이라 String으로 읽어 파싱한다 — UUID로 바로 받으면 바이트가 그대로 해석된다. */
        subjectId = UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT subject_id FROM user_subject_links WHERE user_lookup_key = ?",
                String.class, (Object) subjectLookupKeyDeriver.deriveCurrent(userId)));
    }
}
