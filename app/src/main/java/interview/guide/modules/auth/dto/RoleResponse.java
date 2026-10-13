package interview.guide.modules.auth.dto;

import java.util.List;

/**
 * 角色与权限点
 */
public record RoleResponse(
    Long id,
    String code,
    String name,
    String description,
    Boolean systemRole,
    List<String> permissions) {
}
