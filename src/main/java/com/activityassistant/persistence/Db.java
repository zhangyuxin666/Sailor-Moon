package com.activityassistant.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class Db {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public Optional<Map<String, Object>> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.stream().findFirst();
    }

    public Map<String, Object> required(String sql, Object... args) {
        return one(sql, args).orElseThrow();
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public long insertReturningId(String sql, Map<String, ?> values) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        named.update(sql, new MapSqlParameterSource(values), holder, new String[]{"id"});
        if (holder.getKey() == null) throw new IllegalStateException("数据库未返回主键");
        return holder.getKey().longValue();
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
