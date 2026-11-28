package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;
import java.util.Optional;

@Mapper
public interface ClassroomSubmissionMapper {
    @Select("""
            SELECT storage_key, original_filename, content_type
            FROM file_assets
            WHERE todo_id = #{todoId} AND account_id = #{accountId}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    Map<String, Object> findLatestFileRow(
            @Param("todoId") String todoId, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findLatestFile(
            String todoId, String accountId) {
        return Optional.ofNullable(findLatestFileRow(todoId, accountId));
    }
}
