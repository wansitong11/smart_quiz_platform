package interview.guide.modules.auth.service;

import interview.guide.modules.auth.model.PermissionEntity;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 从账号实体展开角色与权限点。
 * <p>调用方必须保证 roles/permissions 已在事务内加载（见 UserRepository 的 EntityGraph）。
 */
@Component
public class UserAuthorityResolver {

  public Set<String> roles(UserEntity user) {
    Set<String> roles = new LinkedHashSet<>();
    user.getRoles().stream()
        .map(RoleEntity::getCode)
        .sorted()
        .forEach(roles::add);
    return roles;
  }

  public Set<String> permissions(UserEntity user) {
    Set<String> permissions = new TreeSet<>();
    user.getRoles().stream()
        .flatMap(role -> role.getPermissions().stream())
        .map(PermissionEntity::getCode)
        .forEach(permissions::add);
    return permissions;
  }

  public List<String> roleList(UserEntity user) {
    return List.copyOf(roles(user));
  }

  public List<String> permissionList(UserEntity user) {
    return List.copyOf(permissions(user));
  }
}
