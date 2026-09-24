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

    /** 접수 요청의 순서(0부터) — 관리자 화면이 같은 순서로 보여준다. */
    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    protected InquiryAttachment() {
    }

    private InquiryAttachment(Long inquiryId, String filename, int position) {
        this.inquiryId = inquiryId;
        this.filename = filename;
        this.position = position;
    }

    public static InquiryAttachment of(Long inquiryId, String filename, int position) {
        return new InquiryAttachment(inquiryId, filename, position);
    }
}
