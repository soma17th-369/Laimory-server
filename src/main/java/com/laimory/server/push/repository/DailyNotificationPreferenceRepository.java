package com.laimory.server.push.repository;

import com.laimory.server.push.entity.DailyNotificationPreference;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** daily_notification_preferences 레포 — 설정과 worker의 occurrence claim을 함께 소유한다. */
public interface DailyNotificationPreferenceRepository
        extends JpaRepository<DailyNotificationPreference, UUID> {

    /**
     * 신규 행 쓰기는 native insert-if-absent 한 문장이라 read-then-insert 경합이 UNIQUE 예외로 새지
     * 않는다(행을 만드는 것은 가입 transaction뿐이고 rollout backfill이 같은 의미의 운영 SQL을 쓴다 —
     * 설정 쓰기는 행을 만들지 않는다). native INSERT는 JPA auditing을 우회하므로 감사 timestamp를 직접 채운다.
     */
    @Modifying
    @Transactional
    @Query(value = "insert ignore into daily_notification_preferences "
            + "(subject_id, enabled, next_due_at, created_at, updated_at) "
            + "values (:subjectId, :enabled, :nextDueAt, :now, :now)",
            nativeQuery = true)
    int insertIfAbsent(@Param("subjectId") String subjectId,
                       @Param("enabled") boolean enabled,
                       @Param("nextDueAt") LocalDateTime nextDueAt,
                       @Param("now") LocalDateTime now);

    /**
     * due occurrence를 row lock으로 분리한다. 허용 지연을 넘긴 행도 함께 claim한다 — 발송은 하지 않지만
     * 다음 미래 occurrence로 옮겨야 오래된 행이 다음 run에서 다시 선택되지 않는다(판정은 worker가 소유).
     */
    @Query(value = "select * from daily_notification_preferences "
            + "where enabled = true and next_due_at <= :nowKst "
            + "order by next_due_at, subject_id "
            + "limit :limit for update skip locked",
            nativeQuery = true)
    List<DailyNotificationPreference> findDueForUpdateSkipLocked(@Param("nowKst") LocalDateTime nowKst,
                                                                 @Param("limit") int limit);

    /**
     * claim한 occurrence를 다음 예정 시각으로 옮긴다. 발송한 행과 지연 초과로 건너뛴 행이 같은 문장을
     * 공유한다 — 시각이 서버 고정이라 claim된 행 전부가 같은 다음 예정 시각을 갖는다.
     *
     * <p>전진 값은 <b>Java에서 KST로 계산해</b> 넘긴다 — SQL 안에서 {@code date()}/{@code timestamp()}로
     * 파생하지 않는다. JDBC가 {@code LocalDateTime} 파라미터를 connection timezone 기준으로 변환하므로
     * DB 안에서 시각을 파생하면 JVM timezone이 결과에 새어 들어온다. 파라미터만 주고받으면 읽기와 쓰기의
     * 변환이 대칭이라 안전하다.
     */
    @Modifying
    @Transactional
    @Query("update DailyNotificationPreference p set p.nextDueAt = :nextDueAt where p.subjectId in :subjectIds")
    int advanceNextDueAt(@Param("subjectIds") Collection<UUID> subjectIds,
                         @Param("nextDueAt") LocalDateTime nextDueAt);

    /**
     * ON/OFF 전환 — {@code enabled}와 다음 예정 시각을 함께 바꾼다.
     *
     * <p>재장전이 없으면 켠 사용자가 그날 알림을 놓친다. 꺼져 있는 동안 worker가 그 행을 claim하지
     * 않아 {@code next_due_at}이 과거에 굳는데, 그 값이 21:00 run 기준 허용 지연(기본 30분)을 넘겨 있으면
     * run이 claim만 하고 발송 없이 다음 날로 넘긴다(18:00에 굳은 행을 20:50에 켜면 그날 발송이 없다).
     * 다음 미래 occurrence로 재장전해야 그날 21:00 발송분에 들어간다.
     *
     * <p>하루 1회 cron이 된 뒤로(#385) 이 재장전이 막는 것은 <b>오발송이 아니라 누락</b>이다. 매분 cron
     * 시절에는 켠 직후 tick이 예정에 없던 알림을 즉시 쏘는 것이 문제였고 그 창은 사라졌다 — 오발송이
     * 재현되지 않는다고 재장전을 걷어내면 위 누락이 조용히 생긴다.
     *
     * @param nextDueAt 고정 시각의 다음 미래 occurrence(호출자가 KST로 계산)
     */
    @Modifying
    @Transactional
    @Query("update DailyNotificationPreference p set p.enabled = :enabled, p.nextDueAt = :nextDueAt "
            + "where p.subjectId = :subjectId")
    int updateEnabled(@Param("subjectId") UUID subjectId,
                      @Param("enabled") boolean enabled,
                      @Param("nextDueAt") LocalDateTime nextDueAt);

    /**
     * 일일 알림 행 삭제 — 마스터보다 먼저 지운다(FK RESTRICT). 0행 허용(멱등).
     * 탈퇴는 삭제 대신 OFF로 바뀌었으므로(#367) 프로덕션 호출자가 없다 — #302 물리 삭제용이다.
     */
    @Modifying
    @Transactional
    @Query("delete from DailyNotificationPreference p where p.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);
}
