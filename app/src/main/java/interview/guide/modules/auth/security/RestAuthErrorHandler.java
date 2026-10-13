package interview.guide.modules.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 认证/鉴权失败统一出口。
 * <p>
 * 与全局异常处理器不同，这里返回真实的 HTTP 401/403：前端 axios 需要靠状态码触发
 * "静默续期后重试"的逻辑，若也返回 HTTP 200 会与普通业务失败混在一起难以区分。
 */
@Component
@RequiredArgsConstructor
public class RestAuthErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException authException) throws IOException {
    write(response, HttpServletResponse.SC_UNAUTHORIZED,
        Result.error(ErrorCode.AUTH_TOKEN_INVALID, "登录状态无效，请重新登录"));
  }

  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response,
      AccessDeniedException accessDeniedException) throws IOException {
    write(response, HttpServletResponse.SC_FORBIDDEN,
        Result.error(ErrorCode.AUTH_FORBIDDEN, "无操作权限"));
  }

  private void write(HttpServletResponse response, int status, Result<?> body) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }
}
