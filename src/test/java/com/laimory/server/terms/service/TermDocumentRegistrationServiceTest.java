package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class TermDocumentRegistrationServiceTest {
    private final TermDocumentService documents = mock(TermDocumentService.class);
    private final TermDocumentRepository repository = mock(TermDocumentRepository.class);
    private final TermDocumentRegistrationService service = new TermDocumentRegistrationService(documents, repository);
    private final TermType type = TermType.values()[0];

    @BeforeEach
    void current() {
        when(documents.findCurrentDocuments(List.of(type))).thenReturn(List.of(
                TermDocument.of(type, "1.9", "old", "https://example.com/old")));
    }

    @Test
    void higherNumericVersion_usesInsertWithExplicitAuditTime() {
        LocalDateTime before = LocalDateTime.now();
        TermDocument registered = register("1.10");
        var auditNow = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).insert(eq(type.name()), eq("1.10"), eq("title"), eq("https://example.com/doc"),
                auditNow.capture());
        assertThat(auditNow.getValue()).isBetween(before, LocalDateTime.now());
        verifyNoMoreInteractions(repository);
        assertThat(registered.getVersion()).isEqualTo("1.10");
    }

    @Test
    void emptyCatalog_acceptsFirstVersion() {
        when(documents.findCurrentDocuments(List.of(type))).thenReturn(List.of());
        verifyNoInteractions(repository);
        register("1.0");
        verify(repository).insert(eq(type.name()), eq("1.0"), eq("title"), eq("https://example.com/doc"),
                any(LocalDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9", "1.8", "1.0"})
    void equalOrLower_isConflictBeforeInsert(String version) {
        assertThatThrownBy(() -> register(version)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getExceptionType()).isEqualTo(ExceptionType.TERM_DOCUMENT_VERSION_CONFLICT));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1", "01.2", "1.01", "1", "1.2.3", "-1.0", "1.2\n"})
    void invalidVersion_isBadRequest(String version) {
        assertThatThrownBy(() -> register(version)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void duplicateFromInsert_isConflict_butOtherIntegrityErrorsPropagate() {
        DataIntegrityViolationException duplicate = new DataIntegrityViolationException("duplicate",
                new SQLException("duplicate", "23000", 1062));
        doThrow(duplicate).when(repository).insert(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> register("2.0")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(-3003));
        DataIntegrityViolationException other = new DataIntegrityViolationException("other",
                new SQLException("constraint", "23000", 1048));
        doThrow(other).when(repository).insert(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> register("2.0")).isSameAs(other);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/doc", "/doc", "https:///doc", "javascript:alert(1)", "https://"})
    void invalidUrl_isRejected(String url) {
        assertThatThrownBy(() -> service.register(type, "2.0", "title", url, true))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void publicationAndLengthsRequired() {
        assertThatThrownBy(() -> service.register(type, "2.0", "title", "https://example.com", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register(type, "2.0", " ", "https://example.com", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register(type, "2.0", "x".repeat(256), "https://example.com", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register(type, "2.0", "title", "https://example.com/" + "x".repeat(512), true))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private TermDocument register(String version) {
        return service.register(type, version, "title", "https://example.com/doc", true);
    }
}
