package interview.guide.modules.auth.dto;

import java.util.List;

/**
 * 当前登录用户信息（前端路由守卫与权限控制用）
 */
public record CurrentUserResponse(
    Long id,
    String username,
    String displayName,
    String email,
    List<String> roles,
    List<String> permissions) {
}
