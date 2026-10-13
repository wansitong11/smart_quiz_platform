package interview.guide.modules.auth.security;

import java.util.Set;

/**
 * 认证上下文中的当前用户（不落库，仅随请求存在）
 */
public record AuthUserPrincipal(
    Long userId,
    String username,
    String displayName,
    Set<String> roles,
    Set<String> permissions) {
}
