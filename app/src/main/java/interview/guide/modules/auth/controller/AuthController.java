package interview.guide.modules.auth.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.dto.ChangePasswordRequest;
import interview.guide.modules.auth.dto.CurrentUserResponse;
import interview.guide.modules.auth.dto.LoginRequest;
import interview.guide.modules.auth.dto.LoginResponse;
import interview.guide.modules.auth.dto.RegisterRequest;
import interview.guide.modules.auth.dto.RegisterResponse;
import interview.guide.modules.auth.service.AuthService;
import interview.guide.modules.auth.support.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 * <p>Access Token 走响应体（前端放内存），Refresh Token 走 HttpOnly Cookie（JS 不可读）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String BEARER_PREFIX = "Bearer ";

  private final AuthService authService;
  private final AuthProperties authProperties;

  @PostMapping("/register")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.HOURS)
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 50, interval = 1, timeUnit = RateLimit.TimeUnit.HOURS)
  public Result<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
    String username = authService.register(request.username(), request.password());
    return Result.success("注册成功，请登录", new RegisterResponse(username));
  }

  @PostMapping("/login")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 200, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public ResponseEntity<Result<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest) {
    AuthService.LoginResult result = authService.login(
        request.username(),
        request.password(),
        httpRequest.getHeader(HttpHeaders.USER_AGENT),
        ClientIpResolver.resolve(httpRequest));
    return withRefreshCookie(result);
  }

  @PostMapping("/refresh")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 60, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public ResponseEntity<Result<LoginResponse>> refresh(
      HttpServletRequest httpRequest) {
    AuthService.LoginResult result = authService.refresh(
        readRefreshToken(httpRequest),
        httpRequest.getHeader(HttpHeaders.USER_AGENT),
        ClientIpResolver.resolve(httpRequest));
    return withRefreshCookie(result);
  }

  @PostMapping("/logout")
  public ResponseEntity<Result<Void>> logout(
      HttpServletRequest httpRequest) {
    String authorization = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
    String accessToken = (authorization != null && authorization.startsWith(BEARER_PREFIX))
        ? authorization.substring(BEARER_PREFIX.length()).trim()
        : null;
    authService.logout(readRefreshToken(httpRequest), accessToken);

    ResponseCookie cleared = baseCookieBuilder("").maxAge(Duration.ZERO).build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cleared.toString())
        .body(Result.success());
  }

  @GetMapping("/me")
  public Result<CurrentUserResponse> me() {
    return Result.success(authService.currentUser());
  }

  @PostMapping("/password")
  public ResponseEntity<Result<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
    authService.changePassword(request.oldPassword(), request.newPassword());
    // 改密后当前会话也需要重新登录，直接清掉刷新令牌 Cookie
    ResponseCookie cleared = baseCookieBuilder("").maxAge(Duration.ZERO).build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cleared.toString())
        .body(Result.success("密码已更新，请重新登录", null));
  }

  private ResponseEntity<Result<LoginResponse>> withRefreshCookie(AuthService.LoginResult result) {
    ResponseCookie cookie = baseCookieBuilder(result.refreshToken())
        .maxAge(Duration.ofSeconds(result.refreshTtlSeconds()))
        .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(Result.success(result.response()));
  }

  private ResponseCookie.ResponseCookieBuilder baseCookieBuilder(String value) {
    return ResponseCookie.from(authProperties.getRefreshCookieName(), value)
        .httpOnly(true)
        .secure(authProperties.isCookieSecure())
        .path(authProperties.getRefreshCookiePath())
        .sameSite(authProperties.getCookieSameSite());
  }

  /** 手动读取刷新令牌 Cookie：避免在 @CookieValue 上依赖属性占位符解析 */
  private String readRefreshToken(HttpServletRequest request) {
    if (request.getCookies() == null) {
      return null;
    }
    String name = authProperties.getRefreshCookieName();
    return Arrays.stream(request.getCookies())
        .filter(cookie -> name.equals(cookie.getName()))
        .map(jakarta.servlet.http.Cookie::getValue)
        .findFirst()
        .orElse(null);
  }
}
