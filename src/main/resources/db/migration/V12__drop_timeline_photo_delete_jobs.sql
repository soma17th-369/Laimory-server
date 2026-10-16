-- #551: PHOTO 삭제 job을 없앤다. 사진 S3 삭제는 orphan 스위퍼가 S3 성공을 확인한 뒤 Item을 지우는 방식으로
-- 옮겨 갔고(#551 1단계), 이 테이블에 쓰는 앱 코드는 없다. 남아 있던 job 행은 FK가 테이블과 함께 사라지므로
-- 그 Item이 고아가 되어 다음 스위퍼 실행이 photoUrl에서 key를 복원해 S3까지 지운다.
--
-- 비호환 DDL이다: 이 migration 뒤에는 job entity를 가진 구 image(1단계 이전·1단계)가 Hibernate validate에
-- 실패해 기동하지 못한다. 1단계 image가 prod 전체에 반영된 뒤에만 배포하고, 이전 image로 되돌리려면 V1의
-- CREATE TABLE timeline_photo_delete_jobs를 먼저 승인받아 적용한다.
DROP TABLE timeline_photo_delete_jobs;
