package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.RoleCode;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.RoleRepository;
import interview.guide.modules.auth.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号创建：自助注册与管理员建号共用同一套规则（密码加密、默认角色、唯一性校验）。
 */
@Service
@RequiredArgsConstructor
public class UserRegistrationService {

  /** 自助注册只能拿到普通用户角色 */
  public static final Set<String> SELF_REGISTER_ROLES = Set.of(RoleCode.USER.code());

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;

  /**
   * 创建账号。
   *
   * @param roleCodes 角色编码；为空时默认 {@link #SELF_REGISTER_ROLES}
   */
  @Transactional
  public UserEntity createUser(String username, String rawPassword, String displayName,
      String email, Set<String> roleCodes) {
    if (userRepository.existsByUsername(username)) {
      throw new BusinessException(ErrorCode.AUTH_USERNAME_EXISTS, "用户名已存在");
    }

    UserEntity user = new UserEntity();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(rawPassword));
    user.setDisplayName(displayName == null || displayName.isBlank() ? username : displayName.trim());
    user.setEmail(email == null || email.isBlank() ? null : email.trim());
    user.setStatus(UserStatus.ACTIVE);
    user.setTokenVersion(0);
    user.setCreatedAt(LocalDateTime.now());
    user.setRoles(resolveRoles(roleCodes));

    try {
      // 用 saveAndFlush 让唯一约束冲突在这里抛出，转成可读的业务错误，
      // 而不是并发注册时冒出一个 500
      return userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.AUTH_USERNAME_EXISTS, "用户名已存在");
    }
  }

  /**
   * 把角色编码解析成实体。不加 @Transactional：由调用方的事务承载，
   * 避免同类内部调用事务方法（项目约定）。
   */
  public Set<RoleEntity> resolveRoles(Set<String> roleCodes) {
    Set<String> codes = (roleCodes == null || roleCodes.isEmpty())
        ? SELF_REGISTER_ROLES
        : roleCodes;

    Set<RoleEntity> roles = new LinkedHashSet<>();
    for (String code : codes) {
      RoleEntity role = roleRepository.findWithPermissionsByCode(code)
          .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_ROLE_NOT_FOUND, "角色不存在: " + code));
      roles.add(role);
    }
    return roles;
  }
}
