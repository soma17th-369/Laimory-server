-- #474: 구버전 앱(dev DB를 공유하는 test 포함)이 모두 제거된 뒤에만 실행한다.
-- 일반 rolling 배포 금지. docs/database/474-draft-cleanup-rollout.md의 중지/전환/복구 절차 참조.
ALTER TABLE timeline_draft_source_items
    DROP INDEX idx_draft_source_cleanup,
    DROP COLUMN cleanup_available_at;
