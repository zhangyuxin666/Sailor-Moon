package com.activityassistant.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimesTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "2026-10-26T07:00:00Z", "2026-10-26T15:00:00+08:00",
            "2026-10-26T03:00:00-04:00", "2026-10-26T15:00:00"
    })
    void apiAndLegacyTimesUseTheSameReminderInstantAndCalendar(String input) {
        assertThat(Times.instant(input, "Asia/Shanghai")).isEqualTo(java.time.Instant.parse("2026-10-26T07:00:00Z"));
        assertThat(Times.ics(input, "Asia/Shanghai")).isEqualTo("20261026T070000Z");
    }

    @Test
    void convertsNaiveShanghaiTimeToUtcIcs() {
        assertThat(Times.ics("2026-09-22T19:00:00", "Asia/Shanghai"))
                .isEqualTo("20260922T110000Z");
    }

    @Test
    void escapesIcsText() {
        assertThat(Times.escapeIcs("a,b;c\nline")).isEqualTo("a\\,b\\;c\\nline");
    }
}
