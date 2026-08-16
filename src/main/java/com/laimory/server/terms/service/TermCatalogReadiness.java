package com.laimory.server.terms.service;

import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermTimes;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 약관 catalog 준비 상태 검사 — seed 존재와 {@link TermType} 기대 mapping 정합성의 단일 판정 지점.
 *
 * <p>기동 시 다섯 종류 seed 존재(미래 효력 포함)와 모든 행의 {@code (termType, stage, required)} 일치를
 * 검사하고, 누락·불일치·현재 유효 필수 문서 집합 불완전을 bounded ERROR log와 metric으로 경보한다 —
 * 기동과 공개 조회는 막지 않는다.
 *
 * <p>runtime enforcement는 요청마다 {@link #checkStage(TermStage, LocalDateTime)}로 DB 권위를 직접
 * 조회한다(임의 TTL cache 없음 — activation 즉시 판정 반영). 기대 필수 종류 중 하나라도 current 문서가
 * 없거나 mapping이 불일치한 stage는 부분 강제하지 않고 준비되지 않은 catalog로 표시한다 — gate는 stage
 * 전체를 fail-open하고, 잘못된 seed가 5xx나 전 회원 차단으로 이어지지 않게 한다.
 *
 * <p>로그는 상태 전이에서만 남기고(bounded — 요청마다 반복하지 않음) 발생 빈도는
 * {@code laimory.terms.gate.fail_open} counter와 {@code laimory.terms.catalog.ready} gauge가 담당한다.
 */
@Slf4j
@Component
public class TermCatalogReadiness {

    static final String CATALOG_READY_GAUGE = "laimory.terms.catalog.ready";
    static final String GATE_FAIL_OPEN_COUNTER = "laimory.terms.gate.fail_open";

    private final TermDocumentRepository termDocumentRepository;
    private final TermDocumentService termDocumentService;
    private final Clock clock;

    private final Map<TermStage, AtomicInteger> stageReadyGauges = new EnumMap<>(TermStage.class);
    private final Map<TermStage, Counter> failOpenCounters = new EnumMap<>(TermStage.class);
    private final Map<TermStage, AtomicBoolean> notReadyLogged = new EnumMap<>(TermStage.class);

    public TermCatalogReadiness(TermDocumentRepository termDocumentRepository,
                                TermDocumentService termDocumentService,
                                Clock clock,
                                MeterRegistry meterRegistry) {
        this.termDocumentRepository = termDocumentRepository;
        this.termDocumentService = termDocumentService;
        this.clock = clock;
        for (TermStage stage : TermStage.values()) {
            AtomicInteger readyState = new AtomicInteger(0);
            stageReadyGauges.put(stage, readyState);
            meterRegistry.gauge(CATALOG_READY_GAUGE, Tags.of("stage", stage.name()), readyState);
            failOpenCounters.put(stage, Counter.builder(GATE_FAIL_OPEN_COUNTER)
                    .description("Terms gate skipped because the stage catalog is not ready (fail-open)")
                    .tag("stage", stage.name())
                    .register(meterRegistry));
            notReadyLogged.put(stage, new AtomicBoolean(false));
        }
    }

    /** stage catalog 판정 결과 — 준비되지 않았으면 enforcement가 stage 전체를 fail-open한다. */
    public record StageCatalog(boolean ready, List<TermDocument> currentRequiredDocuments) {
    }

    /** 판정 시각은 지금 캡처한 instant의 KST 벽시계다. */
    public StageCatalog checkStage(TermStage stage) {
        return checkStage(stage, TermTimes.kstWallClock(clock.instant()));
    }

    /**
     * stage 준비 상태와 현재 필수 문서 집합을 함께 계산한다. 준비 조건:
     * 기대 필수 종류 전부에 현재 문서가 있고, stage의 현재 문서 전부가 enum mapping과 일치한다.
     */
    public StageCatalog checkStage(TermStage stage, LocalDateTime nowKst) {
        List<TermDocument> currentDocuments = termDocumentService.findCurrentDocuments(
                TermType.typesOf(stage), nowKst);

        boolean mappingConsistent = currentDocuments.stream()
                .allMatch(TermCatalogReadiness::matchesEnumMapping);
        Set<TermType> currentTypes = currentDocuments.stream()
                .map(TermDocument::getTermType)
                .collect(Collectors.toSet());
        boolean requiredCovered = currentTypes.containsAll(TermType.requiredTypesOf(stage));

        boolean ready = mappingConsistent && requiredCovered;
        publishStageState(stage, ready);
        List<TermDocument> currentRequired = currentDocuments.stream()
                .filter(document -> document.getTermType().required())
                .toList();
        return new StageCatalog(ready, currentRequired);
    }

    /** gate가 미준비 stage를 통과시킬 때 호출한다 — 발생량은 counter, 상세는 전이 로그가 담당한다. */
    public void recordFailOpen(TermStage stage) {
        failOpenCounters.get(stage).increment();
    }

    /** 기동 정합성 검사 — seed 누락·mapping 불일치·stage 미준비를 경보하되 기동은 막지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyCatalogOnStartup() {
        List<String> problems = new ArrayList<>();
        try {
            List<TermDocumentRepository.TermCatalogRow> rows = termDocumentRepository.findCatalogRows();
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
            LocalDateTime nowKst = TermTimes.kstWallClock(clock.instant());
            for (TermStage stage : TermStage.values()) {
                if (!checkStage(stage, nowKst).ready()) {
                    problems.add("stage not ready (incomplete current required set or mapping mismatch): "
                            + stage.name());
                }
            }
        } catch (RuntimeException e) {
            log.error("term catalog startup verification failed", e);
            return;
        }
        if (problems.isEmpty()) {
            log.info("term catalog verified: all {} term types seeded and consistent", TermType.values().length);
        } else {
            // 경보 1줄(bounded) — 기동·공개 조회는 계속되고 미준비 stage의 gate는 fail-open된다.
            log.error("term catalog inconsistent: {}", String.join("; ", problems));
        }
    }

    private static void validateRow(TermDocumentRepository.TermCatalogRow row, List<String> problems) {
        TermType type;
        try {
            type = TermType.valueOf(row.getTermType());
        } catch (IllegalArgumentException e) {
            problems.add("unknown termType literal in term_documents: " + row.getTermType());
            return;
        }
        if (!type.stage().name().equals(row.getStage()) || row.getRequired() == null
                || type.required() != row.getRequired()) {
            problems.add("mapping mismatch for termType=" + type.name()
                    + " (db stage=" + row.getStage() + ", db required=" + row.getRequired()
                    + ", expected stage=" + type.stage().name() + ", expected required=" + type.required() + ")");
        }
    }

    private static boolean matchesEnumMapping(TermDocument document) {
        TermType type = document.getTermType();
        return type.stage().name().equals(document.getStage())
                && document.getRequired() != null
                && document.getRequired() == type.required();
    }

    /** 상태 gauge 갱신 + 전이 시에만 로그(bounded — not-ready 지속 중 반복 ERROR 없음). */
    private void publishStageState(TermStage stage, boolean ready) {
        stageReadyGauges.get(stage).set(ready ? 1 : 0);
        AtomicBoolean logged = notReadyLogged.get(stage);
        if (!ready && logged.compareAndSet(false, true)) {
            log.error("term catalog not ready for stage {} — enforcement fails open until seed/activation is fixed",
                    stage.name());
        } else if (ready && logged.compareAndSet(true, false)) {
            log.info("term catalog recovered for stage {}", stage.name());
        }
    }
}
