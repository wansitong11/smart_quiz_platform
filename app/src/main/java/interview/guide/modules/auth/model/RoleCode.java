package interview.guide.modules.auth.model;

/**
 * 内置角色编码。
 * <p>Spring Security 约定 {@code hasRole("ADMIN")} 对应权限名 {@code ROLE_ADMIN}，
 * 因此这里的 code 统一带 {@code ROLE_} 前缀。
 */
public enum RoleCode {

  /** 管理员：全部权限 */
  ADMIN("ROLE_ADMIN", "管理员", "拥有全部功能权限，可管理用户与模型 Provider"),

  /** 普通用户：除用户管理与 Provider 管理外的全部功能 */
  USER("ROLE_USER", "普通用户", "可使用试卷、练习、资料库、口语与排期功能");

  private final String code;
  private final String name;
  private final String description;

  RoleCode(String code, String name, String description) {
    this.code = code;
    this.name = name;
    this.description = description;
  }

  public String code() {
    return code;
  }

  public String roleName() {
    return name;
  }

  public String description() {
    return description;
  }
}
