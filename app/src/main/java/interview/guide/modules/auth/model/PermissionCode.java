package interview.guide.modules.auth.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 功能权限点。
 * <p>权限只做"能不能用某个功能"，不做数据行级隔离——当前产品形态是少数人共用一套数据，
 * 每个人都应看到全部试卷与资料（详见 {@code docs/项目部署/登录鉴权与发布方案.md}）。
 */
public enum PermissionCode {

  PAPER_READ("paper:read", "查看试卷与材料"),
  PAPER_WRITE("paper:write", "上传与解析试卷"),
  PRACTICE_USE("practice:use", "练习作答与批改"),
  KB_READ("kb:read", "查看资料库"),
  KB_WRITE("kb:write", "上传与向量化资料"),
  KB_MANAGE("kb:manage", "资料分类管理"),
  RAG_CHAT("rag:chat", "资料问答"),
  TRACE_READ("trace:read", "问答轨迹榜单"),
  VOICE_USE("voice:use", "口语练习"),
  SCHEDULE_USE("schedule:use", "练习安排"),
  PROVIDER_MANAGE("provider:manage", "模型 Provider 管理"),
  USER_MANAGE("user:manage", "用户与角色管理");

  private final String code;
  private final String name;

  PermissionCode(String code, String name) {
    this.code = code;
    this.name = name;
  }

  public String code() {
    return code;
  }

  public String displayName() {
    return name;
  }

  /** 普通用户默认拥有的权限：全部功能权限，但不含用户管理与 Provider 管理 */
  public static Set<PermissionCode> defaultUserPermissions() {
    Set<PermissionCode> codes = new LinkedHashSet<>(Arrays.asList(values()));
    codes.remove(USER_MANAGE);
    codes.remove(PROVIDER_MANAGE);
    return codes;
  }
}
