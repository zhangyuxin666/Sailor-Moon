package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReplicationMapper {
    @Select("SELECT pg_current_wal_lsn()::text")
    String currentPosition();

    @Select("SELECT pg_is_in_recovery() AND pg_last_wal_replay_lsn() >= CAST(#{position} AS pg_lsn)")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Boolean hasReplayed(@Param("position") String position);
}
