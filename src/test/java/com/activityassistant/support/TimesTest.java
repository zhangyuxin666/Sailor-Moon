package com.activityassistant.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimesTest {
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
