package interview.guide.common.ai;

/**
 * 档位查询插口。common 只依赖该接口，避免 common -> modules 的反向依赖。
 *
 * <p>实现方在业务模块，按用户档位决定是否路由到免费档模型。</p>
 */
public interface AiUserTierResolver {

  /** 是否免费档用户；null 或查询失败按 false（不切免费档）处理。 */
  boolean isFreeTier(Long userId);

  /**
   * 是否授权档用户。授权档固定使用系统默认付费模型，
   * 不再被用户级模型绑定覆盖。null 或查询失败按 false 处理。
   */
  default boolean isAuthorizedTier(Long userId) {
    return false;
  }
}
