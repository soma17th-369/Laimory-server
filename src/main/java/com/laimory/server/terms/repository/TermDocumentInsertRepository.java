package com.laimory.server.terms.repository;

import com.laimory.server.terms.entity.TermDocument;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Assigned EmbeddedId에서 save(merge)는 기존 행을 UPDATE할 수 있으므로 등록은 반드시 INSERT한다. */
@Repository
@RequiredArgsConstructor
public class TermDocumentInsertRepository {

    private final EntityManager entityManager;

    @Transactional
    public void insert(TermDocument document) {
        entityManager.persist(document);
        entityManager.flush();
    }
}
