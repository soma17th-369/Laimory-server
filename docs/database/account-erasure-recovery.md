# 계정 삭제 수동 재처리

계정 삭제 worker(#302·#397)는 접수일 D(KST) 기준 D+3·D+4·D+5 02:30 실행에서만 삭제를 시도한다.
다음 두 경보가 뜨면 이 절차로 확인하고 재처리한다. 둘 다 job과 데이터는 보존돼 있다.

| 경보 로그(ERROR) | 뜻 |
|---|---|
| `계정 삭제 처리 창 만료: expiredCount=N` | D+5까지 삭제를 끝내지 못해 자동 재시도에서 빠진 job |
| `계정 삭제 수동 확인 대기: manualReviewCount=N` | 대상 확인 실패 등으로 격리된 job(`MANUAL_REVIEW`) |

공개 약관은 "탈퇴 접수일로부터 5일 이내 파기"다. 만료 job은 이미 기한을 넘긴 상태이므로 원인 제거와
재처리를 미루지 않는다.

## 1. 확인

운영 DB에서 읽기 전용으로 조회한다. 식별자(`user_id`·job id)는 운영자 콘솔 안에서만 다루고 이슈·PR·
채팅에 복제하지 않는다.

```sql
SELECT status, DATE(created_at) AS received_on, COUNT(*)
FROM account_erasure_jobs
GROUP BY status, DATE(created_at)
ORDER BY received_on;
```

`created_at`은 KST 벽시계 접수 시각이다. 접수 감사 시각은 `users.withdrawal_requested_at`에 따로
보존된다.

## 2. 원인 제거

같은 시각대의 앱 로그에서 원인을 찾는다. 로그에는 식별자가 없고 `exceptionType`만 남는다.

| 로그 | 흔한 원인 |
|---|---|
| `계정 삭제 실패(job 보존, 다음 실행에서 재시도): exceptionType=...` | MySQL·S3 일시 장애, S3 권한 누락(`AccessDeniedException` — 2026-09-25 prod 사례는 `s3:ListBucketVersions`·`s3:DeleteObjectVersion` 누락) |
| `계정 삭제 대상 확인 실패로 수동 확인 필요: exceptionType=...` | 회원 상태가 `WITHDRAWAL_PENDING`이 아님, subject mapping 누락, 다른 subject의 Item이 섞임(`CrossSubjectItemException`) |

원인이 남아 있으면 재처리하지 않는다. `CrossSubjectItemException`은 데이터 손상 신호라 재처리 전에
해당 Item의 소유 관계부터 조사한다.

## 3. 재처리

자동 claim 조건은 `created_at`이 처리 창 안에 있고 `updated_at`이 실행일 00:00 이전인 행이다. 다음 02:30
실행일을 R이라 할 때 `created_at`의 날짜를 **R-3**으로 옮기면 R·R+1·R+2 세 번 다시 시도된다. 시:분:초는
원래 값을 유지하고 `updated_at`은 건드리지 않는다.

- 만료 job: `created_at`만 옮긴다.

  ```sql
  UPDATE account_erasure_jobs
  SET created_at = '<R-3 날짜> <원래 시:분:초>'
  WHERE account_erasure_job_id = <id>;
  ```

- `MANUAL_REVIEW` job: 상태를 `PENDING`으로 되돌리고 필요하면 `created_at`도 같은 방식으로 옮긴다.

  ```sql
  UPDATE account_erasure_jobs
  SET status = 'PENDING', created_at = '<R-3 날짜> <원래 시:분:초>'
  WHERE account_erasure_job_id = <id> AND status = 'MANUAL_REVIEW';
  ```

MySQL 호스트 시계는 UTC이고 컬럼은 KST 벽시계다. `NOW()`·`CURDATE()`를 쓰지 말고 KST 날짜 literal을
쓴다.

prod 데이터 수정은 대상 job 수·영향·되돌리는 방법(원래 `created_at`·`status` 값)을 먼저 기록하고 건별
승인을 받은 뒤 실행한다.

## 4. 완료 확인

R 02:30 실행 뒤 앱 로그 `계정 삭제 worker run 완료: processed=N ...`와 job 행 소멸을 확인한다. job 행이
사라지면 회원 행과 subject mapping도 함께 지워진 것이고, 다음 실행부터 경보도 멈춘다.
