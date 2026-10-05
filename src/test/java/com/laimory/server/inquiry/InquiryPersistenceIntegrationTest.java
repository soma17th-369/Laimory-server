package com.laimory.server.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.inquiry.InquiryObjectKeys;
import com.laimory.server.inquiry.dto.InquiryDetailResponse;
import com.laimory.server.inquiry.dto.InquirySummaryResponse;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 문의 ↔ 실 MySQL 왕복(#518) — V4 migration·엔티티 매핑(validate)·subject FK RESTRICT의 fail-closed 성질·
 * 탈퇴 삭제 순서(첨부 → 문의)와, 앱 "내 문의" 조회(#529)의 owner 격리·최신 순·50건 상한을 검증한다.
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
    private Long otherUserId;
    private UUID otherSubjectId;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            erase(userId, subjectId);
            userId = null;
        }
        if (otherUserId != null) {
            erase(otherUserId, otherSubjectId);
            otherUserId = null;
        }
    }

    private void erase(Long userId, UUID subjectId) {
        inquiryService.deleteAllBySubjectId(subjectId);
        jdbcTemplate.update("DELETE FROM user_memories WHERE subject_id = ?", subjectId.toString());
        jdbcTemplate.update("DELETE FROM subject_credits WHERE subject_id = ?", subjectId.toString());
        jdbcTemplate.update("DELETE FROM daily_notification_preferences WHERE subject_id = ?", subjectId.toString());
        jdbcTemplate.update("DELETE FROM subject_preferences WHERE subject_id = ?", subjectId.toString());
        userRepository.deleteById(userId);
        userSubjectLinkRepository.deleteById(subjectLookupKeyDeriver.deriveCurrent(userId));
    }

    @Test
    void myInquiriesAreScopedToTheOwnerSubjectForBothListAndDetail() {
        provisionUser();
        provisionOtherUser();
        inquiryService.register("v1", subjectId, "me@example.com", "내 문의", "내용", List.of(FILENAME_B, FILENAME_A));
        long mine = latestInquiryId(subjectId);
        inquiryService.register("v1", otherSubjectId, "other@example.com", "남의 문의", "내용", null);
        long others = latestInquiryId(otherSubjectId);

        assertThat(inquiryService.findMine("v1", subjectId)).extracting(InquirySummaryResponse::inquiryId)
                .containsExactly(mine);
        assertThat(inquiryService.findMine("v1", otherSubjectId)).extracting(InquirySummaryResponse::inquiryId)
                .containsExactly(others);

        InquiryDetailResponse detail = inquiryService.getMine("v1", subjectId, mine);
        assertThat(detail.title()).isEqualTo("내 문의");
        assertThat(detail.attachmentUrls()).satisfiesExactly( // 요청(PK) 순서
                url -> assertThat(url).endsWith("/" + InquiryObjectKeys.fullKey(FILENAME_B, subjectId)),
                url -> assertThat(url).endsWith("/" + InquiryObjectKeys.fullKey(FILENAME_A, subjectId)));
        // 실재하는 남의 문의 id도 없는 문의와 같은 404다.
        assertThatThrownBy(() -> inquiryService.getMine("v1", subjectId, others))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }

    @Test
    void myInquiriesListIsNewestFirstAndCappedAtFifty() {
        provisionUser();
        List<Long> registeredIds = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            inquiryService.register("v1", subjectId, "me@example.com", "문의 " + i, "내용", null);
            registeredIds.add(latestInquiryId(subjectId));
        }

        List<Long> listedIds = inquiryService.findMine("v1", subjectId).stream()
                .map(InquirySummaryResponse::inquiryId).toList();

        // 최신 50건만 — 가장 먼저 접수한 1건이 빠진다.
        assertThat(listedIds).hasSize(50).doesNotContain(registeredIds.get(0));
        assertThat(listedIds).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(listedIds.get(0)).isEqualTo(registeredIds.get(50));
    }

    @Test
    void registeredInquiryBlocksSubjectMappingDeletionUntilErasedInAttachmentThenInquiryOrder() {
        provisionUser();

        inquiryService.register("v1", subjectId, "it@example.com",
                "사진이 안 올라가요", "첫 줄\n둘째 줄", List.of(FILENAME_B, FILENAME_A));
        long inquiryId = latestInquiryId(subjectId);

        Inquiry stored = inquiryRepository.findByInquiryId(inquiryId).orElseThrow();
        assertThat(stored.getSubjectId()).isEqualTo(subjectId);
        assertThat(stored.getEmail()).isEqualTo("it@example.com");
        assertThat(stored.getTitle()).isEqualTo("사진이 안 올라가요");
        assertThat(stored.getDescription()).isEqualTo("첫 줄\n둘째 줄");
        assertThat(stored.getAnsweredAt()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(inquiryAttachmentRepository.findByInquiryIdOrderByInquiryAttachmentIdAsc(inquiryId))
                .extracting(attachment -> attachment.getFilename())
                .containsExactly(FILENAME_B, FILENAME_A); // filename 정렬이 아니라 요청 순서

        // subject FK RESTRICT — 문의가 남아 있는 한 mapping 삭제는 DB가 거절한다(탈퇴 finalize의 fail-closed 근거).
        byte[] lookupKey = subjectLookupKeyDeriver.deriveCurrent(userId);
        assertThatThrownBy(() -> userSubjectLinkRepository.deleteById(lookupKey))
                .isInstanceOf(DataIntegrityViolationException.class);

        inquiryService.deleteAllBySubjectId(subjectId);

        assertThat(inquiryRepository.findByInquiryId(inquiryId)).isEmpty();
        assertThat(inquiryAttachmentRepository.findByInquiryIdOrderByInquiryAttachmentIdAsc(inquiryId)).isEmpty();
    }

    @Test
    void answeredMarkIsPersistedAndClearable() {
        provisionUser();
        inquiryService.register("v1", subjectId, "it@example.com", "제안", "기능 제안", null);
        long inquiryId = latestInquiryId(subjectId);

        inquiryService.changeAnswered(inquiryId, true);
        assertThat(inquiryRepository.findByInquiryId(inquiryId).orElseThrow().getAnsweredAt()).isNotNull();

        inquiryService.changeAnswered(inquiryId, false);
        assertThat(inquiryRepository.findByInquiryId(inquiryId).orElseThrow().getAnsweredAt()).isNull();
    }

    /** 접수 응답은 ID를 싣지 않는다 — 테스트마다 새로 만든 subject의 최신 문의가 방금 접수한 행이다. */
    private long latestInquiryId(UUID subject) {
        return inquiryRepository.findBySubjectIdOrderByInquiryIdDesc(subject, PageRequest.of(0, 1))
                .getFirst().getInquiryId();
    }

    private void provisionOtherUser() {
        User user = newUserProvisioner.provision(Provider.KAKAO,
                "inquiry-it-" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L),
                null, "문의테스트2");
        otherUserId = user.getUserId();
        otherSubjectId = UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT subject_id FROM user_subject_links WHERE user_lookup_key = ?",
                String.class, (Object) subjectLookupKeyDeriver.deriveCurrent(otherUserId)));
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
