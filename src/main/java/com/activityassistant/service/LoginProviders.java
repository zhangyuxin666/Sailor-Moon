package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.config.LoginMethodProperties;
import com.activityassistant.support.ApiException;
import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
class LoginProviders {
    static final String WECHAT_CALLBACK = "/auth/wechat/callback";

    private final LoginMethodProperties settings;
    private final AppProperties app;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    LoginProviders(LoginMethodProperties settings, AppProperties app, ObjectMapper json) {
        this.settings = settings;
        this.app = app;
        this.json = json;
    }

    boolean wechatEnabled() {
        return settings.wechatEnabled() && app.publicBaseUrl().startsWith("https://");
    }

    boolean phoneEnabled() {
        return settings.phoneEnabled();
    }

    String wechatUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://open.weixin.qq.com/connect/qrconnect")
                .queryParam("appid", "{appId}").queryParam("redirect_uri", "{callback}")
                .queryParam("response_type", "code").queryParam("scope", "snsapi_login")
                .queryParam("state", state).fragment("wechat_redirect")
                .encode().buildAndExpand(settings.wechatAppId(), app.publicBaseUrl().replaceAll("/+$", "") + WECHAT_CALLBACK)
                .toUriString();
    }

    String wechatSubject(String code) {
        URI uri = UriComponentsBuilder.fromUriString("https://api.weixin.qq.com/sns/oauth2/access_token")
                .queryParam("appid", "{appId}").queryParam("secret", "{secret}")
                .queryParam("code", "{code}").queryParam("grant_type", "authorization_code")
                .encode().buildAndExpand(settings.wechatAppId(), settings.wechatAppSecret(), code).toUri();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(response.body());
            if (response.statusCode() == 200 && body != null && body.path("errcode").asInt(0) == 0
                    && !body.path("access_token").asText().isBlank()) {
                String openId = body.path("openid").asText();
                if (!openId.isBlank() && openId.length() <= 200) return settings.wechatAppId() + ":" + openId;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            // Provider responses can contain credentials. Return a generic error to callers.
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "微信授权暂时不可用，请重试");
    }

    void sendPhoneCode(String phone, String code) {
        try {
            var client = new Client(new Config().setEndpoint("dypnsapi.aliyuncs.com")
                    .setAccessKeyId(settings.smsAccessKeyId()).setAccessKeySecret(settings.smsAccessKeySecret())
                    .setConnectTimeout(5_000).setReadTimeout(10_000));
            var request = new SendSmsVerifyCodeRequest().setPhoneNumber(phone).setCountryCode("86")
                    .setSignName(settings.smsSignName()).setTemplateCode(settings.smsTemplateCode())
                    .setTemplateParam(json.writeValueAsString(Map.of("code", code, "min", "5")))
                    .setCodeLength(6L).setValidTime(300L).setInterval(60L);
            if (settings.smsSchemeName() != null && !settings.smsSchemeName().isBlank()) {
                request.setSchemeName(settings.smsSchemeName());
            }
            var response = client.sendSmsVerifyCode(request).getBody();
            if (response != null && Boolean.TRUE.equals(response.getSuccess()) && "OK".equals(response.getCode())) return;
        } catch (Exception exception) {
            // SDK errors can include the SMS payload and account credentials.
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "短信暂时无法发送，请稍后重试");
    }
}
