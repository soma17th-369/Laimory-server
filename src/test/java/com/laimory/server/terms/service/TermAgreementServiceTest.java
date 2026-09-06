package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.repository.TermAgreementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 동의 일괄 등록의 all-or-nothing 검증(전체 성공 / stale 409 / shape 400)과 KST 수락 시각 계약,
 * 그리고 동의 필요 판정(#434)의 대상 5종·미동의 선별·종류별 fail-open·enum 순 정렬 계약 검증.
 */
@ExtendWith(MockitoExtension.class)
class TermAgreementServiceTest {

    private static final long USER_ID = 7L;
    // UTC 2026-08-15T20:30 = KST 2026-08-16T05:30
    private static final Clock UTC_CLOCK = Clock.fixed(Instant.parse("2026-08-15T20:30:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime EXPECTED_KST = LocalDateTime.parse("2026-08-16T05:30:00");

    @Mock
    private TermDocumentService termDocumentService;
    @Mock
    private TermAgreementTransactionService termAgreementTransactionService;
    @Mock
    private TermAgreementRepository termAgreementRepository;

    private TermAgreementService service;

    @BeforeEach
    void setUp() {
        service = new TermAgreementService(termDocumentService, termAgreementTransactionService,
                termAgreementRepository, UTC_CLOCK);
    }

    @Test
    void agree_recordsAllCurrentDocuments_withSingleKstAcceptedAt() {
        TermDocumentSummary terms = summary(11L, TermType.TERMS_OF_SERVICE, "1.0");
        TermDocumentSummary sensitive = summary(12L, TermType.SENSITIVE_INFORMATION_CONSENT, "1.0");
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(terms, sensitive));

        service.agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0"),
                new TermAgreementCommand(TermType.SENSITIVE_INFORMATION_CONSENT, "1.0")));

        // 검증(current selection)과 수락 시각이 같은 캡처 instant의 KST 벽시계를 공유한다.
        ArgumentCaptor<LocalDateTime> nowKst = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(termDocumentService).findCurrentSummaries(anyCollection(), nowKst.capture());
        assertThat(nowKst.getValue()).isEqualTo(EXPECTED_KST);
        verify(termAgreementTransactionService).recordAgreements(USER_ID, List.of(11L, 12L), EXPECTED_KST);
    }

    @Test
    void agree_staleVersion_rejects409WithoutRecordingAnything() {
        // 민감정보 동의만 개정됨 — 하나라도 stale이면 전부 기록하지 않는다(all-or-nothing).
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(summary(11L, TermType.TERMS_OF_SERVICE, "1.0"),
                        summary(13L, TermType.SENSITIVE_INFORMATION_CONSENT, "1.1")));

        assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0"),
                new TermAgreementCommand(TermType.SENSITIVE_INFORMATION_CONSENT, "1.0"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getExceptionType())
                .isEqualTo(ExceptionType.STALE_TERM_VERSION);

        verifyNoInteractions(termAgreementTransactionService);
    }

    @Test
    void agree_unknownDocument_rejectsWithSame409() {
        // 현재 유효 문서가 아예 없는 종류(미활성·미존재) 제출도 같은 409로 수렴한다(재조회 신호).
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getExceptionType())
                .isEqualTo(ExceptionType.STALE_TERM_VERSION);

        verifyNoInteractions(termAgreementTransactionService);
    }

    @Test
    void agree_rejectsNullEmptyDuplicateAndMissingFields400() {
        List<List<TermAgreementCommand>> invalidRequests = List.of(
                List.of(),
                List.of(new TermAgreementCommand(null, "1.0")),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, null)),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, " ")),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0"),
                        new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0")));

        assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (List<TermAgreementCommand> request : invalidRequests) {
            assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(termDocumentService, termAgreementTransactionService);
    }

    @Test
    void agree_sameTypeDifferentVersions_isNotDuplicate_butStaleOneRejects() {
        // 같은 종류의 두 버전은 shape 중복이 아니다 — 대신 stale 검증이 409로 거절한다.
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(summary(11L, TermType.TERMS_OF_SERVICE, "1.0")));

        assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0"),
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "2026-07-01"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getExceptionType())
                .isEqualTo(ExceptionType.STALE_TERM_VERSION);
    }

    @Test
    void history_delegatesToOwnerScopedQuery() {
        when(termAgreementRepository.findHistoryByUserId(USER_ID)).thenReturn(List.of());

        assertThat(service.getHistory("v1", USER_ID)).isEmpty();
        verify(termAgreementRepository).findHistoryByUserId(USER_ID);
    }

    @Test
    void agreementRequired_targetsAllConsentTypesExceptPrivacyPolicy_atKstNow() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());

        assertThat(service.findAgreementRequiredTerms(USER_ID)).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TermType>> types = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<LocalDateTime> nowKst = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(termDocumentService).findCurrentSummaries(types.capture(), nowKst.capture());
        assertThat(types.getValue()).containsExactlyInAnyOrder(TermType.TERMS_OF_SERVICE,
                TermType.SENSITIVE_INFORMATION_CONSENT, TermType.THIRD_PARTY_PROVISION_CONSENT,
                TermType.CROSS_BORDER_TRANSFER_CONSENT, TermType.LOCATION_BASED_SERVICE_TERMS);
        assertThat(nowKst.getValue()).isEqualTo(EXPECTED_KST);
        // 후보 문서가 없으면(전체 미준비) 동의 조회 없이 빈 목록 — 앱 시작을 막지 않는다.
        verifyNoInteractions(termAgreementRepository);
    }

    @Test
    void agreementRequired_includesOnlyUnagreedCurrentDocuments_inEnumOrder() {
        TermDocumentSummary terms = summary(11L, TermType.TERMS_OF_SERVICE, "1.1");
        TermDocumentSummary sensitive = summary(12L, TermType.SENSITIVE_INFORMATION_CONSENT, "1.0");
        TermDocumentSummary location = summary(15L, TermType.LOCATION_BASED_SERVICE_TERMS, "1.0");
        // IN 조회 결과 순서는 보장되지 않는다 — 응답은 enum 선언 순으로 고정돼야 한다.
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(location, terms, sensitive));
        when(termAgreementRepository.findAgreedDocumentIds(eq(USER_ID), anyCollection()))
                .thenReturn(List.of(12L)); // 민감정보만 현재 버전 동의 있음

        assertThat(service.findAgreementRequiredTerms(USER_ID)).containsExactly(terms, location);
    }

    @Test
    void agreementRequired_allCurrentVersionsAgreed_returnsEmpty() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(summary(11L, TermType.TERMS_OF_SERVICE, "1.0"),
                        summary(12L, TermType.SENSITIVE_INFORMATION_CONSENT, "1.0")));
        when(termAgreementRepository.findAgreedDocumentIds(eq(USER_ID), anyCollection()))
                .thenReturn(List.of(11L, 12L));

        assertThat(service.findAgreementRequiredTerms(USER_ID)).isEmpty();
    }

    @Test
    void agreementRequired_missingCurrentDocumentTypes_areSkippedNotFailed() {
        // 5종 중 1종만 current 문서 존재(미래 effectiveAt만 있는 종류도 같은 형태로 빠진다) —
        // 미준비 종류는 그 종류만 제외되고(종류별 fail-open) 준비된 종류의 판정은 유지된다.
        TermDocumentSummary onlyReady = summary(11L, TermType.TERMS_OF_SERVICE, "1.0");
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(onlyReady));
        when(termAgreementRepository.findAgreedDocumentIds(eq(USER_ID), anyCollection()))
                .thenReturn(List.of());

        assertThat(service.findAgreementRequiredTerms(USER_ID)).containsExactly(onlyReady);
    }

    private static TermDocumentSummary summary(Long id, TermType type, String version) {
        return new TermDocumentSummary(id, type, version);
    }
}
