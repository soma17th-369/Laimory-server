package com.laimory.server.terms.service;

import com.laimory.server.terms.TermTimes;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.repository.TermDocumentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 약관 catalog 준비 상태 검사 — seed 존재와 현재 동의 대상 종류 커버리지의 단일 판정 지점.
 *
 * <p>기동 시 {@link TermType}에 선언된 모든 종류의 seed 존재(미래 효력 포함)와 모든 행의
 * {@code term_type} literal·{@code content_url} 형식을 검사한다. 개인정보 처리방침은 상시 공개 문서로
 * seed 정합성에는 포함하지만 동의 enforcement 대상에서는 제외한다.
 *
 * <p>runtime enforcement는 요청마다 {@link #check(LocalDateTime)}로 DB 권위를 직접 조회한다(임의 TTL
 * cache 없음). 동의 대상 다섯 종류 중 하나라도 current 문서가 없으면 부분 강제하지 않고 전체 gate를
 * fail-open한다. 로그는 상태 전이에서만 남기고 발생 빈도는 counter와 ready gauge가 담당한다.
 */
@Slf4j
@Component
public class TermCatalogReadiness {

    static final String CATALOG_READY_GAUGE = "laimory.terms.catalog.ready";
    static final String GATE_FAIL_OPEN_COUNTER = "laimory.terms.gate.fail_open";

    static final List<TermType> ENFORCED_TYPES = List.of(
            TermType.TERMS_OF_SERVICE,
            TermType.SENSITIVE_INFORMATION_CONSENT,
            TermType.THIRD_PARTY_PROVISION_CONSENT,
            TermType.CROSS_BORDER_TRANSFER_CONSENT,
            TermType.LOCATION_BASED_SERVICE_TERMS);

    private final TermDocumentRepository termDocumentRepository;
    private final TermDocumentService termDocumentService;
    private final Clock clock;
    private final AtomicInteger readyGauge = new AtomicInteger(0);
    private final Counter failOpenCounter;
    private final AtomicBoolean notReadyLogged = new AtomicBoolean(false);

    public TermCatalogReadiness(TermDocumentRepository termDocumentRepository,
                                TermDocumentService termDocumentService,
                                Clock clock,
                                MeterRegistry meterRegistry) {
        this.termDocumentRepository = termDocumentRepository;
        this.termDocumentService = termDocumentService;
        this.clock = clock;
        meterRegistry.gauge(CATALOG_READY_GAUGE, readyGauge);
        this.failOpenCounter = Counter.builder(GATE_FAIL_OPEN_COUNTER)
                .description("Terms gate skipped because the enforcement catalog is not ready (fail-open)")
                .register(meterRegistry);
    }

    /** enforcement catalog 판정 결과 — 준비되지 않았으면 gate 전체를 fail-open한다. */
    public record Catalog(boolean ready, List<TermDocumentSummary> currentEnforcedDocuments) {
    }

    /** 판정 시각은 지금 캡처한 instant의 KST 벽시계다. */
    public Catalog check() {
        return check(TermTimes.kstWallClock(clock.instant()));
    }

    /** 동의 대상 종류 전부에 현재 문서가 있는지와 그 문서 집합을 함께 계산한다. */
    Catalog check(LocalDateTime nowKst) {
        List<TermDocumentSummary> currentDocuments = termDocumentService.findCurrentSummaries(
                ENFORCED_TYPES, nowKst);
        Set<TermType> currentTypes = currentDocuments.stream()
                .map(TermDocumentSummary::termType)
                .collect(Collectors.toSet());

        boolean ready = currentTypes.containsAll(ENFORCED_TYPES);
        publishState(ready, currentDocuments.isEmpty());
        return new Catalog(ready, currentDocuments);
    }

    /** gate가 미준비 catalog를 통과시킬 때 호출한다. */
    public void recordFailOpen() {
        failOpenCounter.increment();
    }

    /** 기동 정합성 검사 — seed 누락·미지 literal·잘못된 URL·enforcement 미준비를 경보하되 기동은 막지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyCatalogOnStartup() {
        List<String> problems = new ArrayList<>();
        boolean seeded;
        try {
            List<TermDocumentRepository.TermCatalogRow> rows = termDocumentRepository.findCatalogRows();
            seeded = !rows.isEmpty();
            Set<String> seededTypes = rows.stream()
                    .map(TermDocumentRepository.TermCatalogRow::getTermType)
                    .collect(Collectors.toSet());
            for (TermType type : TermType.values()) {
                if (!seededTypes.contains(type.name())) {
                    problems.add("missing seed for termType=" + type.name());
                }
            }
            for (TermDocumentRepository.TermCatalogRow row : rows) {
                validateRow(row, problems);
            }
            if (!check(TermTimes.kstWallClock(clock.instant())).ready()) {
                problems.add("enforcement catalog not ready (incomplete current required set)");
            }
        } catch (RuntimeException e) {
            log.error("term catalog startup verification failed", e);
            return;
        }
        if (!seeded) {
            log.warn("term catalog not seeded yet — enforcement fails open until activation (pre-activation state)");
        } else if (problems.isEmpty()) {
            log.info("term catalog verified: all {} term types seeded", TermType.values().length);
        } else {
            log.error("term catalog inconsistent: {}", String.join("; ", problems));
        }
    }

    private static void validateRow(TermDocumentRepository.TermCatalogRow row, List<String> problems) {
        try {
            TermType.valueOf(row.getTermType());
        } catch (IllegalArgumentException e) {
            problems.add("unknown termType literal in term_documents: " + row.getTermType());
            return;
        }
        if (!isPublishedPageUrl(row.getContentUrl())) {
            problems.add("invalid contentUrl for termType=" + row.getTermType()
                    + " (must be an absolute https URI): " + row.getContentUrl());
        }
    }

    private static boolean isPublishedPageUrl(String contentUrl) {
        if (contentUrl == null || contentUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(contentUrl);
            return uri.isAbsolute() && "https".equals(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** ready gauge 갱신 + 상태 전이에서만 bounded log를 남긴다. */
    private void publishState(boolean ready, boolean noCurrentCandidates) {
        readyGauge.set(ready ? 1 : 0);
        if (!ready && notReadyLogged.compareAndSet(false, true)) {
            if (noCurrentCandidates && termDocumentRepository.count() == 0) {
                log.warn("term catalog not seeded yet — enforcement fails open until activation");
            } else {
                log.error("term enforcement catalog not ready — enforcement fails open until seed/activation is fixed");
            }
        } else if (ready && notReadyLogged.compareAndSet(true, false)) {
            log.info("term enforcement catalog recovered");
        }
    }
}
