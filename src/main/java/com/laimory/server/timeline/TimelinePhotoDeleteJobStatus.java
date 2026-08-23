package com.laimory.server.timeline;

/** PHOTO delete job의 외부 S3 삭제 처리 상태. 완료·취소 job은 행 자체를 삭제한다. */
public enum TimelinePhotoDeleteJobStatus {
    PENDING,
    PROCESSING
}
