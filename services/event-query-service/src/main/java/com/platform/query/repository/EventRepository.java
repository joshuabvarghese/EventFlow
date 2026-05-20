package com.platform.query.repository;

import com.platform.query.model.StoredEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<StoredEvent, UUID> {

    // ── Single-event lookup ───────────────────────────────────────────────────

    Optional<StoredEvent> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    // ── Filtered page queries ─────────────────────────────────────────────────

    /**
     * Full filter query — all parameters optional; null values are ignored by
     * the JPQL {@code OR … IS NULL} guards so callers can leave them unset.
     */
    @Query("""
            SELECT e FROM StoredEvent e
            WHERE (:eventType   IS NULL OR e.eventType = :eventType)
              AND (:userId      IS NULL OR e.userId    = :userId)
              AND (:status      IS NULL OR e.status    = :status)
              AND (:from        IS NULL OR e.createdAt >= :from)
              AND (:to          IS NULL OR e.createdAt <= :to)
            ORDER BY e.createdAt DESC
            """)
    Page<StoredEvent> findFiltered(
            @Param("eventType") String eventType,
            @Param("userId")    String userId,
            @Param("status")    String status,
            @Param("from")      Instant from,
            @Param("to")        Instant to,
            Pageable pageable
    );

    // ── Aggregation helpers ───────────────────────────────────────────────────

    /** Distinct event types present in the store (for dropdown population). */
    @Query("SELECT DISTINCT e.eventType FROM StoredEvent e ORDER BY e.eventType")
    List<String> findDistinctEventTypes();

    /** Count events per type — used by the stats endpoint. */
    @Query("""
            SELECT e.eventType AS eventType, COUNT(e) AS count
            FROM StoredEvent e
            GROUP BY e.eventType
            ORDER BY COUNT(e) DESC
            """)
    List<EventTypeCount> countByEventType();

    /** Count events per status. */
    @Query("""
            SELECT e.status AS status, COUNT(e) AS count
            FROM StoredEvent e
            GROUP BY e.status
            """)
    List<StatusCount> countByStatus();

    /**
     * Hourly event counts for the last {@code hours} hours — drives the
     * time-series chart in the dashboard.
     */
    @Query(value = """
            SELECT date_trunc('hour', created_at) AS hour,
                   COUNT(*)                        AS count
            FROM   events
            WHERE  created_at >= NOW() - CAST(:hours || ' hours' AS INTERVAL)
            GROUP  BY 1
            ORDER  BY 1 ASC
            """, nativeQuery = true)
    List<HourlyCount> countByHour(@Param("hours") int hours);

    /**
     * Recent events ordered newest-first — used by the live log panel.
     */
    @Query("""
            SELECT e FROM StoredEvent e
            ORDER BY e.createdAt DESC
            """)
    List<StoredEvent> findRecent(Pageable pageable);

    // ── Projection interfaces ─────────────────────────────────────────────────

    interface EventTypeCount {
        String getEventType();
        Long   getCount();
    }

    interface StatusCount {
        String getStatus();
        Long   getCount();
    }

    interface HourlyCount {
        Instant getHour();
        Long    getCount();
    }
}
