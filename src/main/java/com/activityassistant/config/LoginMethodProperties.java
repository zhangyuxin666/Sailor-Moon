package com.activityassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.login")
public record LoginMethodProperties(
        String wechatAppId, String wechatAppSecret,
        String smsAccessKeyId, String smsAccessKeySecret, String smsSignName,
        String smsTemplateCode, String smsSchemeName
) {
    public boolean wechatEnabled() {
        return present(wechatAppId) && present(wechatAppSecret);
    }

    public boolean phoneEnabled() {
        return present(smsAccessKeyId) && present(smsAccessKeySecret)
                && present(smsSignName) && present(smsTemplateCode);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
