package interview.guide.modules.auth.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 解析真实客户端 IP。生产链路是 浏览器 → gateway(nginx) → frontend(nginx) → app，
 * 两层 nginx 都会追加 X-Forwarded-For，取第一段即真实来源。
 */
public final class ClientIpResolver {

  private ClientIpResolver() {
  }

  public static String resolve(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
      if (!first.isEmpty() && !"unknown".equalsIgnoreCase(first)) {
        return first;
      }
    }
    String realIp = request.getHeader("X-Real-IP");
    if (realIp != null && !realIp.isBlank() && !"unknown".equalsIgnoreCase(realIp)) {
      return realIp.trim();
    }
    return request.getRemoteAddr();
  }
}
