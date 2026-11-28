package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExecutionJournalMapper {
    record StepData(String status, String detail, String phase, String inputJson,
                    String outputJson, String error) {}

    @Select("SELECT result_json FROM idempotency_keys WHERE key = #{key} AND tool_name = #{tool}")
    String findResult(@Param("key") String key, @Param("tool") String tool);

    @Insert("""
            INSERT INTO idempotency_keys (key, tool_name, result_json, created_at)
            VALUES (#{key}, #{tool}, #{result}, #{createdAt})
            ON CONFLICT (key) DO NOTHING
            """)
    void saveResult(@Param("key") String key, @Param("tool") String tool,
                    @Param("result") String result, @Param("createdAt") String createdAt);

    @Select("SELECT id FROM steps WHERE activity_id = #{activityId} AND idempotency_key = #{key}")
    Long findStepId(@Param("activityId") String activityId, @Param("key") String key);

    @Insert("""
            INSERT INTO steps (activity_id, step, status, detail, created_at, phase,
                               idempotency_key, input_json, output_json, error, attempts)
            VALUES (#{activityId}, #{name}, #{data.status}, #{data.detail}, #{createdAt},
                    #{data.phase}, #{key}, #{data.inputJson}, #{data.outputJson}, #{data.error}, 1)
            """)
    void insertStep(@Param("activityId") String activityId, @Param("name") String name,
                    @Param("key") String key, @Param("createdAt") String createdAt,
                    @Param("data") StepData data);

    @Update("""
            UPDATE steps
            SET status = #{data.status}, detail = #{data.detail}, phase = #{data.phase},
                input_json = COALESCE(#{data.inputJson}, input_json),
                output_json = COALESCE(#{data.outputJson}, output_json),
                error = #{data.error}, attempts = attempts + 1
            WHERE id = #{id}
            """)
    void updateStep(@Param("id") long id, @Param("data") StepData data);
}
