package com.laimory.server.terms.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentInsertRepository;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TermDocumentRegistrationService {

    private final TermDocumentService documents;
    private final TermDocumentInsertRepository inserts;

    // INSERT transaction은 별도 repository proxy가 소유한다. rollback이 끝난 후에만 409로 변환한다.
    public TermDocument register(TermType type, String version, String title, String contentUrl,
                                 boolean publicationConfirmed) {
        if (type == null || title == null || title.isBlank() || title.length() > 255
                || contentUrl == null || contentUrl.length() > 512 || !publicationConfirmed) {
            throw new IllegalArgumentException("Invalid term publication");
        }
        URI uri = URI.create(contentUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Term URL must be absolute HTTPS with a host");
        }
        TermDocument proposed = TermDocument.of(type, version, title, contentUrl);
        List<TermDocument> current = documents.findCurrentDocuments(List.of(type));
        if (!current.isEmpty() && !proposed.isNewerThan(current.getFirst())) {
            throw new BusinessException(ExceptionType.TERM_DOCUMENT_VERSION_CONFLICT);
        }
        try {
            inserts.insert(proposed);
        } catch (DataIntegrityViolationException exception) {
            // MySQL ER_DUP_ENTRY만 처리한다. 다른 무결성 장애를 중복 버전으로 숨기지 않는다.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                    throw new BusinessException(ExceptionType.TERM_DOCUMENT_VERSION_CONFLICT);
                }
            }
            throw exception;
        }
        return proposed;
    }
}
