package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeliveryLogMapper {
    @Insert("""
            INSERT INTO delivery_events
                (id, activity_id, kind, channel, target, content, status, external_id,
                 error, created_at, completed_at, logical_key)
            VALUES (#{id}, #{activity}, #{kind}, 'qq_group', #{target}, #{content},
                    #{status}, #{external}, #{error}, #{now}, #{completedAt}, #{key})
            ON CONFLICT (logical_key) WHERE logical_key IS NOT NULL DO UPDATE SET
                status = EXCLUDED.status,
                external_id = COALESCE(EXCLUDED.external_id, delivery_events.external_id),
                error = EXCLUDED.error,
                completed_at = EXCLUDED.completed_at
            WHERE delivery_events.status <> 'sent'
            """)
    void recordActivity(@Param("id") String id, @Param("activity") String activity,
                        @Param("kind") String kind, @Param("target") String target,
                        @Param("content") String content, @Param("status") String status,
                        @Param("external") String external, @Param("error") String error,
                        @Param("now") String now, @Param("completedAt") String completedAt,
                        @Param("key") String key);

    @Insert("""
            INSERT INTO classroom_deliveries
                (id, todo_id, kind, content, status, external_id, error, created_at, logical_key)
            VALUES (#{id}, #{todo}, #{kind}, #{content}, #{status}, #{external},
                    #{error}, #{now}, #{key})
            ON CONFLICT (logical_key) WHERE logical_key IS NOT NULL DO UPDATE SET
                status = EXCLUDED.status,
                external_id = COALESCE(EXCLUDED.external_id, classroom_deliveries.external_id),
                error = EXCLUDED.error
            WHERE classroom_deliveries.status <> 'sent'
            """)
    void recordTodo(@Param("id") String id, @Param("todo") String todo,
                    @Param("kind") String kind, @Param("content") String content,
                    @Param("status") String status, @Param("external") String external,
                    @Param("error") String error, @Param("now") String now, @Param("key") String key);
}
