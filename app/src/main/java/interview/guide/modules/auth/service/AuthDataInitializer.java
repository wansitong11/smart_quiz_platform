package interview.guide.modules.auth.service;

import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.PermissionCode;
import interview.guide.modules.auth.model.PermissionEntity;
import interview.guide.modules.auth.model.RoleCode;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.PermissionRepository;
import interview.guide.modules.auth.repository.RoleRepository;
import interview.guide.modules.auth.repository.UserRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动时初始化 RBAC 基础数据（幂等）。
 * <ol>
 *   <li>按 {@link PermissionCode} 同步权限点；</li>
 *   <li>同步内置角色与角色-权限关系，新增权限点会自动挂到 ROLE_ADMIN，符合 {@link RoleCode} 定义的会挂到 ROLE_USER；</li>
 *   <li>用户表为空时创建初始管理员，密码取 APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD，未配置则随机生成并打印一次。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthDataInitializer implements ApplicationRunner {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final PermissionRepository permissionRepository;
  private final RoleRepository roleRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthProperties authProperties;
  private final RefreshTokenService refreshTokenService;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    syncPermissions();
    syncRoles();
    bootstrapAdmin();

    int purged = refreshTokenService.purgeExpired();
    if (purged > 0) {
      log.info("清理已过期的刷新令牌记录: {} 条", purged);
    }
  }

  private void syncPermissions() {
    for (PermissionCode code : PermissionCode.values()) {
      PermissionEntity entity = permissionRepository.findByCode(code.code())
          .orElseGet(() -> {
            PermissionEntity created = new PermissionEntity();
            created.setCode(code.code());
            created.setCreatedAt(LocalDateTime.now());
            return created;
          });
      entity.setName(code.displayName());
      permissionRepository.save(entity);
    }
  }

  private void syncRoles() {
    syncRole(RoleCode.ADMIN, List.of(PermissionCode.values()));
    syncRole(RoleCode.USER, PermissionCode.defaultUserPermissions());
  }

  private void syncRole(RoleCode roleCode, Iterable<PermissionCode> permissionCodes) {
    RoleEntity role = roleRepository.findWithPermissionsByCode(roleCode.code())
        .orElseGet(() -> {
          RoleEntity created = new RoleEntity();
          created.setCode(roleCode.code());
          created.setCreatedAt(LocalDateTime.now());
          return created;
        });
    role.setName(roleCode.roleName());
    role.setDescription(roleCode.description());
    role.setSystemRole(Boolean.TRUE);

    Set<PermissionEntity> permissions = new LinkedHashSet<>();
    for (PermissionCode code : permissionCodes) {
      permissionRepository.findByCode(code.code()).ifPresent(permissions::add);
    }
    role.setPermissions(permissions);
    roleRepository.save(role);
  }

  private void bootstrapAdmin() {
    if (userRepository.count() > 0) {
      return;
    }

    String username = authProperties.getBootstrapAdminUsername();
    String password = authProperties.getBootstrapAdminPassword();
    boolean generated = false;
    if (password == null || password.isBlank()) {
      byte[] bytes = new byte[18];
      RANDOM.nextBytes(bytes);
      password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      generated = true;
    }

    RoleEntity adminRole = roleRepository.findWithPermissionsByCode(RoleCode.ADMIN.code())
        .orElseThrow(() -> new IllegalStateException("初始化失败：ROLE_ADMIN 不存在"));

    UserEntity admin = new UserEntity();
    admin.setUsername(username);
    admin.setPasswordHash(passwordEncoder.encode(password));
    admin.setDisplayName("系统管理员");
    admin.setStatus(UserStatus.ACTIVE);
    admin.setTokenVersion(0);
    admin.setCreatedAt(LocalDateTime.now());
    admin.setRoles(new LinkedHashSet<>(Set.of(adminRole)));
    userRepository.save(admin);

    if (generated) {
      // 只打印一次，请立即登录并修改密码；生产建议改用 APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD 预设
      log.warn("已创建初始管理员账号，请立即登录并修改密码 -> username={} password={}", username, password);
    } else {
      log.info("已创建初始管理员账号: username={}（密码来自 APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD）", username);
    }
  }
}
