package com.activityassistant.api;

import com.activityassistant.config.AppProperties;
import com.activityassistant.service.LoginMethods;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@RestController
class LoginMethodsController {
    private static final String WECHAT_COOKIE = "activity_wechat_flow";
    private static final String CALLBACK = "/auth/wechat/callback";

    private final LoginMethods methods;
    private final AppProperties properties;

    LoginMethodsController(LoginMethods methods, AppProperties properties) {
        this.methods = methods;
        this.properties = properties;
    }

    @GetMapping("/auth/login-methods")
    Map<String, Boolean> availability() {
        return methods.availability();
    }

    @GetMapping("/account/login-methods")
    Map<String, Object> bindings(Authentication authentication) {
        return methods.bindings(AuthController.required(authentication).id());
    }

    @PostMapping("/auth/phone/code")
    Map<String, String> sendPhone(@Valid @RequestBody Phone body, HttpServletRequest request) {
        return Map.of("challenge_id", methods.sendCode(body.phone(), null, request.getRemoteAddr()));
    }

    @PostMapping("/auth/phone/login")
    ResponseEntity<Map<String, Object>> loginPhone(@Valid @RequestBody PhoneCode body) {
        var result = methods.loginByPhone(body.challengeId(), body.code());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, AuthController.sessionCookie(properties, result.token()).toString())
                .body(result.account().toPublicMap());
    }

    @PostMapping("/account/login-methods/phone/code")
    Map<String, String> sendBindingCode(@Valid @RequestBody PhoneBinding body, Authentication authentication,
                                        HttpServletRequest request) {
        var binding = authorizeBinding(authentication, body.password(), request);
        return Map.of("challenge_id", methods.sendCode(body.phone(), binding, request.getRemoteAddr()));
    }

    @PostMapping("/account/login-methods/phone")
    ResponseEntity<Void> bindPhone(@Valid @RequestBody PhoneCode body, Authentication authentication,
                                   HttpServletRequest request) {
        methods.bindPhone(body.challengeId(), body.code(), AuthController.required(authentication), AuthController.rawToken(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/auth/wechat/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> startWechat(HttpServletRequest request) {
        return wechatRedirect(methods.startWechat(null, request.getRemoteAddr()));
    }

    @PostMapping("/account/login-methods/wechat")
    ResponseEntity<Map<String, String>> bindWechat(@Valid @RequestBody Password body, Authentication authentication,
                                                   HttpServletRequest request) {
        return wechatRedirect(methods.startWechat(authorizeBinding(authentication, body.password(), request), request.getRemoteAddr()));
    }

    @GetMapping(CALLBACK)
    ResponseEntity<Void> wechatCallback(@RequestParam(required = false) String state,
                                        @RequestParam(required = false) String code,
                                        @CookieValue(name = WECHAT_COOKIE, required = false) String browser) {
        var result = methods.finishWechat(state, browser, code);
        String location = result.binding() ? "/settings?" : "/login?screen=account&";
        var response = ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .location(URI.create(location + "wechat=" + result.status()));
        if (!"expired".equals(result.status())) {
            response.header(HttpHeaders.SET_COOKIE, wechatCookie("", Duration.ZERO).toString());
        }
        if (result.login() != null) {
            response.header(HttpHeaders.SET_COOKIE, AuthController.sessionCookie(properties, result.login().token()).toString());
        }
        return response.build();
    }

    @DeleteMapping("/account/login-methods")
    ResponseEntity<Void> remove(@RequestParam String provider, @Valid @RequestBody Password body,
                                 Authentication authentication, HttpServletRequest request) {
        methods.remove(provider, authorizeBinding(authentication, body.password(), request));
        return ResponseEntity.noContent().build();
    }

    private LoginMethods.Binding authorizeBinding(Authentication authentication, String password, HttpServletRequest request) {
        return methods.authorizeBinding(AuthController.required(authentication), AuthController.rawToken(request), password,
                request.getRemoteAddr());
    }

    private ResponseEntity<Map<String, String>> wechatRedirect(LoginMethods.WechatStart start) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, wechatCookie(start.browser(), Duration.ofMinutes(10)).toString())
                .body(Map.of("url", start.url()));
    }

    private ResponseCookie wechatCookie(String value, Duration age) {
        return ResponseCookie.from(WECHAT_COOKIE, value).httpOnly(true).secure(true)
                .sameSite("Lax").path(CALLBACK).maxAge(age).build();
    }

    record Phone(@NotNull @Pattern(regexp = "1[3-9][0-9]{9}") String phone) {}
    record PhoneBinding(@NotNull @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
                        @NotBlank @Size(max = 128) String password) {}
    record PhoneCode(@NotNull @Pattern(regexp = "[a-f0-9]{32}") String challengeId,
                     @NotNull @Pattern(regexp = "[0-9]{6}") String code) {}
    record Password(@NotBlank @Size(max = 128) String password) {}
}
