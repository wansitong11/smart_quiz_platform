package interview.guide.modules.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 握手鉴权。
 * <p>浏览器无法为 WebSocket 设置请求头，因此令牌通过 {@code ?token=} 传递；
 * 校验逻辑与 HTTP 侧完全一致（签名、有效期、黑名单、令牌版本）。
 * 校验失败直接拒绝握手，不建立连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

  public static final String PRINCIPAL_ATTRIBUTE = "authPrincipal";

  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler wsHandler, Map<String, Object> attributes) {
    String token = null;
    if (request instanceof ServletServerHttpRequest servletRequest) {
      HttpServletRequest servlet = servletRequest.getServletRequest();
      token = servlet.getParameter("token");
    }

    AuthUserPrincipal principal = jwtAuthenticationFilter.resolvePrincipal(token);
    if (principal == null) {
      log.warn("拒绝 WebSocket 握手：令牌缺失或无效, uri={}", request.getURI());
      return false;
    }
    attributes.put(PRINCIPAL_ATTRIBUTE, principal);
    return true;
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler wsHandler, Exception exception) {
    // 无需处理
  }
}
