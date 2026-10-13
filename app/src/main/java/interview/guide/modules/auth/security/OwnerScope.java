package interview.guide.modules.auth.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 数据归属范围：决定一次查询"看谁的数据"。
 * <p>
 * 规则（决策 1）：
 * <ul>
 *   <li>普通用户：只能看自己的（返回自己的 userId）；</li>
 *   <li>管理员：默认也是只看自己的，显式带 {@code ?scope=all} 才切换成"查看全部"（返回 null 表示不过滤）；</li>
 *   <li>异步线程（消费者/调度器）没有请求上下文：{@link #effectiveOwnerId()} 返回当前登录用户或 null，
 *       消费端按 id 处理任务，不做列表过滤。</li>
 * </ul>
 * 越权统一抛 404（决策 5）：403 会暴露"这条记录存在"。
 */
@Slf4j
@Component
public class OwnerScope {

  /** 管理员查看全部数据的显式开关 */
  public static final String SCOPE_ALL = "all";

  /** 当前登录用户 id；未登录抛 401 */
  public Long currentUserId() {
    return CurrentUserHolder.currentUserId();
  }

  /**
   * 容错版：没有登录上下文时返回 null（异步线程里落库场景，不要把轨迹写入搞崩）。
   */
  public Long optionalOwnerId() {
    return CurrentUserHolder.current().map(AuthUserPrincipal::userId).orElse(null);
  }

  /**
   * 查询用的归属过滤值：null 表示不过滤（仅管理员 + scope=all）。
   */
  public Long effectiveOwnerId() {
    if (isAdmin() && SCOPE_ALL.equalsIgnoreCase(currentRequestParam("scope"))) {
      return null;
    }
    return CurrentUserHolder.current().map(AuthUserPrincipal::userId).orElse(null);
  }

  public boolean isAdmin() {
    return CurrentUserHolder.current()
        .map(principal -> principal.roles().contains("ROLE_ADMIN"))
        .orElse(false);
  }

  /**
   * 资料库可读判定：自己的、全局共享的，或管理员 scope=all。
   * （资料列表/搜索/分类统计都用它做内存过滤，避免为每个查询再加一套 owner 方法）
   */
  public boolean canReadKnowledgeBase(Long ownerId, String visibility) {
    Long effective = effectiveOwnerId();
    if (effective == null) {
      return true;
    }
    return effective.equals(ownerId) || "SHARED".equals(visibility);
  }

  /**
   * 资料库可维护判定（改分类 / 重新向量化 / 删除 / 题库写）：
   * <ul>
   *   <li>归属人本人：可以维护自己的资料；</li>
   *   <li>共享资料（SHARED）：只有管理员可以维护——共享资料是公共资源，谁创建的不重要；</li>
   *   <li>刻意**不看** {@code scope}：{@code ?scope=all} 只影响"能看多少"，
   *       不能让人靠切个开关就把别人的私有资料改掉。</li>
   * </ul>
   * 注意：异步线程没有登录上下文（这里会返回 false），所以后台任务不要用这个判定，
   * 它们按 id 直查即可（入队时已经校验过）。
   */
  public boolean canManageKnowledgeBase(Long ownerId, String visibility) {
    if (isAdmin() && "SHARED".equals(visibility)) {
      return true;
    }
    Long me = optionalOwnerId();
    return me != null && me.equals(ownerId);
  }

  /**
   * 按 id 取到实体后的归属断言（用于"先按 id 查、再校验"的写路径）。
   */
  public void requireOwner(Long ownerId) {
    Long effective = effectiveOwnerId();
    if (effective == null) {
      return;
    }
    if (ownerId == null || !effective.equals(ownerId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在或无权访问");
    }
  }

  private String currentRequestParam(String name) {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
      return null;
    }
    HttpServletRequest request = attributes.getRequest();
    return request == null ? null : request.getParameter(name);
  }

  /** 读取请求参数（如上传接口的 {@code ?visibility=SHARED}）；无请求上下文时返回 null */
  public String requestParam(String name) {
    return currentRequestParam(name);
  }

  /**
   * 资料库可见性：只有管理员可以显式指定 SHARED，其余一律 PRIVATE（决策 2）。
   */
  public String resolveKnowledgeBaseVisibility() {
    if (isAdmin() && "SHARED".equalsIgnoreCase(currentRequestParam("visibility"))) {
      return "SHARED";
    }
    return "PRIVATE";
  }
}
