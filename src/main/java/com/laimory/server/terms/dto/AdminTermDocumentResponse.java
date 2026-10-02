package com.laimory.server.terms.dto;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;

/** 관리자 약관 이력의 문서 한 버전. */
public record AdminTermDocumentResponse(TermType termType, String version, String title, String contentUrl) {

    public static AdminTermDocumentResponse from(TermDocument document) {
        return new AdminTermDocumentResponse(document.getTermType(), document.getVersion(), document.getTitle(),
                document.getContentUrl());
    }
}
