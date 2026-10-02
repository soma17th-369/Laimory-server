package com.laimory.server.inquiry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 문의 첨부 사진 하나 — 문의 행에 plain {@code Long} FK로 연결한다(연관 매핑 없음, 다른 엔티티 선례).
 * DB에는 filename만 저장하고 S3 full key는 subject namespace에서 파생한다({@code InquiryObjectKeys}).
 * 첨부 순서는 PK 순서다 — 한 접수의 첨부는 한 transaction에서 요청 순서대로 저장된다.
 */
@Entity
@Table(name = "inquiry_attachments")
@Getter
public class InquiryAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_attachment_id")
    private Long inquiryAttachmentId;

    @Column(name = "inquiry_id", nullable = false, updatable = false)
    private Long inquiryId;

    @Column(name = "filename", nullable = false, updatable = false, length = 64)
    private String filename;

    protected InquiryAttachment() {
    }

    private InquiryAttachment(Long inquiryId, String filename) {
        this.inquiryId = inquiryId;
        this.filename = filename;
    }

    public static InquiryAttachment of(Long inquiryId, String filename) {
        return new InquiryAttachment(inquiryId, filename);
    }
}
