package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {
    record Scope(String organizationId, String classId) {}

    String SCOPED = """
            <script>
            WITH scoped AS (
                SELECT DISTINCT a.id, a.status, a.created_at
                FROM activities a
                JOIN activity_classes ac ON ac.activity_id = a.id
                WHERE ac.organization_id = #{scope.organizationId}
                <if test="scope.classId != null">AND ac.class_id = #{scope.classId}</if>
            )
            """;

    @Select("""
            <script>
            SELECT count(DISTINCT cm.account_id)
            FROM class_members cm
            JOIN class_organizations co ON co.class_id = cm.class_id
            WHERE co.organization_id = #{scope.organizationId}
            <if test="scope.classId != null">AND cm.class_id = #{scope.classId}</if>
            </script>
            """)
    long countMembers(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT count(*) FROM scoped
            </script>
            """)
    long countActivities(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT count(*) FROM scoped WHERE status NOT IN ('finished','cancelled','failed')
            </script>
            """)
    long countActiveActivities(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT count(*) FROM registrations r JOIN forms f ON f.id=r.form_id JOIN scoped s ON s.id=f.activity_id WHERE r.status='active'
            </script>
            """)
    long countRegistrations(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT count(t.id) total, count(t.id) FILTER (WHERE t.status='done') done FROM tasks t JOIN scoped s ON s.id=t.activity_id
            </script>
            """)
    Map<String, Object> taskTotals(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT count(DISTINCT p.account_id) participants, count(DISTINCT ci.account_id) checked_in, count(DISTINCT f.account_id) feedback_count, coalesce(round(avg(f.rating),1),0) average_rating
            FROM scoped s LEFT JOIN activity_participants p ON p.activity_id=s.id
            LEFT JOIN activity_check_ins ci ON ci.activity_id=s.id AND ci.account_id=p.account_id
            LEFT JOIN activity_feedback f ON f.activity_id=s.id AND f.account_id=p.account_id
            </script>
            """)
    Map<String, Object> participation(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT substring(created_at,1,10) trend_date, count(*) n FROM scoped WHERE substring(created_at,1,10) &gt;= #{date} GROUP BY 1
            </script>
            """)
    List<Map<String, Object>> activityTrend(@Param("scope") Scope scope, @Param("date") String date);

    @Select(SCOPED + """
            SELECT substring(r.created_at,1,10) trend_date, count(*) n FROM registrations r
            JOIN forms f ON f.id=r.form_id JOIN scoped s ON s.id=f.activity_id
            WHERE r.status='active' AND substring(r.created_at,1,10) &gt;= #{date} GROUP BY 1
            </script>
            """)
    List<Map<String, Object>> registrationTrend(@Param("scope") Scope scope, @Param("date") String date);

    @Select(SCOPED + """
            SELECT status, count(*) value FROM scoped GROUP BY status ORDER BY value DESC
            </script>
            """)
    List<Map<String, Object>> activityStatuses(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT CASE WHEN trim(coalesce(t.assignee,''))='' THEN 'unassigned' ELSE t.status END status,
            count(*) value FROM tasks t JOIN scoped s ON s.id=t.activity_id GROUP BY 1 ORDER BY value DESC
            </script>
            """)
    List<Map<String, Object>> taskStatuses(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT count(*) FROM tasks t JOIN scoped s ON s.id=t.activity_id
            WHERE t.status &lt;&gt; 'done' AND t.deadline IS NOT NULL AND t.deadline &lt;&gt; '' AND t.deadline &lt; #{now}
            </script>
            """)
    long countOverdue(@Param("scope") Scope scope, @Param("now") String now);

    @Select(SCOPED + """
            SELECT count(*) FROM tasks t JOIN scoped s ON s.id=t.activity_id WHERE t.status &lt;&gt; 'done' AND trim(coalesce(t.assignee,''))=''
            </script>
            """)
    long countUnassigned(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT count(*) FROM delivery_events d JOIN scoped s ON s.id=d.activity_id WHERE d.status='failed'
            </script>
            """)
    long countFailedDeliveries(@Param("scope") Scope scope);

    @Select(SCOPED + """
            SELECT count(*) FROM reminders r JOIN scoped s ON s.id=r.activity_id WHERE r.status='failed'
            </script>
            """)
    long countFailedReminders(@Param("scope") Scope scope);

    @Select("""
            SELECT c.id, c.name
            FROM classes c
            JOIN class_organizations co ON co.class_id = c.id
            WHERE co.organization_id = #{organizationId}
            ORDER BY c.name
            """)
    List<Map<String, Object>> findClasses(@Param("organizationId") String organizationId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM class_organizations
                WHERE organization_id = #{organizationId} AND class_id = #{classId}
            )
            """)
    boolean containsClass(@Param("organizationId") String organizationId, @Param("classId") String classId);
}
