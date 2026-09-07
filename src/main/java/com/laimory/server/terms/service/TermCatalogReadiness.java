package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 시 약관 seed 누락·종류 오타·HTTPS URL 형식을 확인하는 읽기 전용 검사.
 *
 * <p>raw catalog를 한 번 조회해 전체 {@link TermType} 존재와 각 행의 형식만 확인한다. 버전 형식은
 * 쓰기 경계와 DB CHECK가 보장하므로 재검증하지 않고, 원문 URL에도 HTTP 요청을 보내지 않는다.
 * 빈 catalog는 WARN, 잘못된 seed나 조회 실패는 ERROR로 알리되 기동·공개 조회는 막지 않는다.
 * 별도 준비 상태나 모니터링 지표는 유지하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TermCatalogReadiness {

    private final TermDocumentRepository termDocumentRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void verifyCatalogOnStartup() {
        List<String> problems = new ArrayList<>();
        try {
            List<TermDocumentRepository.TermCatalogRow> rows = termDocumentRepository.findCatalogRows();
            if (rows.isEmpty()) {
                log.warn("term catalog not seeded yet — public terms queries stay empty (pre-seed state)");
                return;
            }
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
        } catch (RuntimeException e) {
            log.error("term catalog startup verification failed", e);
            return;
        }
        if (problems.isEmpty()) {
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
            // 게시 host는 운영 선택이며 실제 page의 200 확인은 배포 게이트가 담당한다.
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
}
