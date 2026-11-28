package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

@Mapper
public interface OutboundMapper {
    @Insert("""
            INSERT INTO outbound_deliveries(logical_key,payload_hash,group_openid,content,status)
            VALUES (#{key},#{payloadHash},#{group},#{content},'sending')
            ON CONFLICT DO NOTHING
            """)
    int insertIntent(@Param("key") String key, @Param("payloadHash") String payloadHash, @Param("group") String group, @Param("content") String content);

    @Select("""
            SELECT * FROM outbound_deliveries
            WHERE logical_key=#{key} FOR UPDATE
            """)
    Map<String, Object> lockIntent(@Param("key") String key);

    @Update("""
            UPDATE outbound_deliveries
            SET status='sending',attempts=attempts+1,error=NULL,updated_at=clock_timestamp()
            WHERE logical_key=#{key}
            """)
    int retryIntent(@Param("key") String key);

    @Update("""
            UPDATE outbound_deliveries
            SET status=#{status},external_id=COALESCE(#{externalId},external_id),error=#{error},updated_at=clock_timestamp()
            WHERE logical_key=#{key} AND status<>'sent'
            """)
    int completeIntent(@Param("status") String status, @Param("externalId") String externalId, @Param("error") String error, @Param("key") String key);
}
