package com.activityassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String publicBaseUrl,
        boolean cookieSecure,
        int sessionDays,
        int uploadMaxMb,
        int userStorageQuotaMb,
        String storageLocalPath,
        String timezone,
        String aiBaseUrl,
        String aiInternalToken,
        String qqGatewayBaseUrl,
        String qqGatewayToken,
        String qqBotAppId,
        String qqBotAppSecret,
        Duration httpTimeout
) {
    public boolean qqConfigured() {
        return qqBotAppId != null && !qqBotAppId.isBlank()
                && qqBotAppSecret != null && !qqBotAppSecret.isBlank();
    }
}
