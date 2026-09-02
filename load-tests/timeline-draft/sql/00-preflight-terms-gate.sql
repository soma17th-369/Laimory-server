-- 실행 전 게이트: 약관 catalog가 아직 fail-open인지 확인한다.
--
-- 왜 필요한가 — 합성 사용자는 `term_agreements`가 0이다. 서버의 `TermsEnforcementInterceptor`는
-- LOGIN 단계 필수 문서를 `/a/api` 대부분에 강제하고, draft 생성은 TIMELINE_FIRST_CREATE 단계까지 본다.
-- 다만 `TermsEnforcementService`는 stage catalog가 준비되지 않았으면 **stage 전체를 fail-open**한다.
-- 운영 catalog는 #383으로 이미 seed됐으므로 운영에서는 합성 사용자가 **전량 403**이다. catalog가 비어
-- 있는 환경(예: dev)에서만 fail-open으로 통과한다. 대상 DB가 어느 쪽인지 실행 전에 확인한다.
--
-- 사용:
--   mysql --defaults-extra-file=<config> <db> < load-tests/timeline-draft/sql/00-preflight-terms-gate.sql
--
-- 판정: active_documents가 0이면 그대로 진행한다.
--       0이 아니면 **실행하지 않는다.** catalog가 활성화된 것이므로, 합성 사용자에게 현재 문서에 대한
--       `term_agreements`를 함께 seed해야 한다. 그때는 정리도 같이 늘려야 한다 —
--       `term_agreements`에는 users FK가 없어 06의 users 삭제로 지워지지 않으므로 직접 DELETE가 필요하고,
--       05 dry-run의 `term_agreements` expected-0 검사도 함께 고쳐야 한다.

-- 두 stage의 필수 문서 종류(TermType). 서버 enum과 어긋나면 이 게이트가 무의미해지므로
-- TermType을 바꿀 때 여기도 함께 고친다.
SELECT
    'terms catalog active documents' AS check_name,
    COUNT(*)                         AS active_documents,
    CASE WHEN COUNT(*) = 0
         THEN 'OK - catalog fail-open, 합성 사용자로 실행 가능'
         ELSE 'STOP - catalog 활성. term_agreements seed·cleanup 없이는 전량 403'
    END                              AS verdict
FROM term_documents
WHERE term_type IN (
        'TERMS_OF_SERVICE',               -- LOGIN
        'PRIVACY_POLICY',                 -- LOGIN
        'SENSITIVE_INFORMATION_CONSENT',  -- TIMELINE_FIRST_CREATE
        'THIRD_PARTY_PROVISION_CONSENT',  -- TIMELINE_FIRST_CREATE
        'CROSS_BORDER_TRANSFER_CONSENT'   -- TIMELINE_FIRST_CREATE
      )
  AND effective_at <= CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00');
