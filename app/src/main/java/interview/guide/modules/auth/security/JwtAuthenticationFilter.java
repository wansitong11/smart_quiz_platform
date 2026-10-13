package interview.guide.modules.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Access Token 认证过滤器：从 {@code Authorization: Bearer xxx} 解析并建立安全上下文。
 * <p>
 * 校验链条：签名与有效期 → 令牌类型 → jti 黑名单 → 用户令牌版本（踢人下线）。
 * 任一环节不通过都不建立认证，由 EntryPoint 统一返回 401，业务接口不受影响。
 * <p>
 * WebSocket 握手无法携带请求头，因此额外支持 {@code ?token=} 查询参数，但仅对 {@code /ws/} 路径生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenService jwtTokenService;
  private final TokenRevocationService tokenRevocationService;

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain) throws ServletException, IOException {

    String token = resolveToken(request);
    if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
      authenticate(token, request);
    }
    filterChain.doFilter(request, response);
  }

  /**
   * SSE / Flux 接口（问答助手的流式回答）在 Spring MVC 下会被当作异步请求处理，
   * 流结束或抛异常时容器会再发起 ASYNC / ERROR 派发。
   * <p>Spring Security 的授权过滤器在这些派发阶段会再执行一次，而无状态会话不会保存
   * SecurityContext（SecurityContextHolderFilter 用的是 NullSecurityContextRepository），
   * 于是请求在异步阶段变成"未认证"，抛
   * {@code AuthorizationDeniedException}；此时响应已提交，最终表现为
   * <pre>Unable to handle the Spring Security Exception because the response is already committed.</pre>
   * <p>解决方式：让本过滤器在异步与错误派发阶段也重新解析一次令牌，
   * 请求头在派发时仍然存在，代价只是一次 JWT 解析 + 一次 Redis 读取。
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  private void authenticate(String token, HttpServletRequest request) {
    AuthUserPrincipal principal = resolvePrincipal(token);
    if (principal == null) {
      return;
    }

    List<GrantedAuthority> authorities = new ArrayList<>(
        principal.roles().size() + principal.permissions().size());
    principal.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority(role)));
    principal.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));

    UsernamePasswordAuthenticationToken authentication =
        UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);

    // 供 @RateLimit(dimension = USER) 使用
    request.setAttribute("userId", principal.userId());
  }

  private List<String> claimList(Jwt jwt, String name) {
    List<String> values = jwt.getClaimAsStringList(name);
    return values == null ? List.of() : values;
  }

  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      String value = header.substring(BEARER_PREFIX.length()).trim();
      return value.isEmpty() ? null : value;
    }
    // 仅 WebSocket 握手允许用查询参数传令牌
    if (request.getRequestURI().startsWith("/ws/")) {
      String param = request.getParameter("token");
      return (param == null || param.isBlank()) ? null : param;
    }
    return null;
  }

  /** 供 WebSocket 握手拦截器复用同一套校验逻辑 */
  public AuthUserPrincipal resolvePrincipal(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      Jwt jwt = jwtTokenService.decode(token);
      if (!jwtTokenService.isAccessToken(jwt)) {
        log.debug("令牌类型不是 access，忽略");
        return null;
      }
      if (tokenRevocationService.isBlacklisted(jwt.getId())) {
        log.debug("Access Token 已被撤销: jti={}", jwt.getId());
        return null;
      }

      Long userId = Long.valueOf(jwt.getSubject());
      // 注意：Nimbus 反序列化后数字声明可能是 Long，不能按 Integer 直接取，否则 ClassCastException
      Number tokenVersionClaim = jwt.getClaim("ver");
      int tokenVersion = tokenVersionClaim == null ? Integer.MIN_VALUE : tokenVersionClaim.intValue();
      int currentVersion = tokenRevocationService.currentTokenVersion(userId);
      if (currentVersion < 0 || tokenVersion != currentVersion) {
        log.debug("令牌版本失效（可能已被踢下线）: userId={}, tokenVer={}, currentVer={}",
            userId, tokenVersion, currentVersion);
        return null;
      }

      return new AuthUserPrincipal(
          userId,
          jwt.getClaimAsString("username"),
          null,
          new LinkedHashSet<>(claimList(jwt, "roles")),
          new LinkedHashSet<>(claimList(jwt, "perms")));
    } catch (JwtException | IllegalArgumentException e) {
      log.debug("Access Token 无效: {}", e.getMessage());
      return null;
    } catch (RuntimeException e) {
      // 兜底：未预期的解析异常按"未认证"处理，避免把 500 抛给前端
      log.warn("解析 Access Token 时发生未预期异常，按未认证处理", e);
      return null;
    }
  }
}
