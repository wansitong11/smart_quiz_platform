package interview.guide.modules.auth.model;

/**
 * 账号状态
 */
public enum UserStatus {
  /** 正常可用 */
  ACTIVE,
  /** 已禁用，禁止登录且存量令牌全部失效 */
  DISABLED
}
