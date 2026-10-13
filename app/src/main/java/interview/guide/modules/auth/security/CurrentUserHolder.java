package interview.guide.modules.auth.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户的读取入口。业务代码不要直接摸 SecurityContextHolder。
 */
public final class CurrentUserHolder {

  private CurrentUserHolder() {
  }

  public static Optional<AuthUserPrincipal> current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthUserPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal);
  }

  public static AuthUserPrincipal requireCurrent() {
    return current().orElseThrow(() ->
        new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "登录状态无效，请重新登录"));
  }

  public static Long currentUserId() {
    return requireCurrent().userId();
  }
}
