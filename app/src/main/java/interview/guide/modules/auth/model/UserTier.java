package interview.guide.modules.auth.model;

/**
 * 用户档位。
 *
 * <p>免费档用户占用月度总量池、走较便宜的模型；授权档用户由管理员手动放开，
 * 豁免额度限制并走较贵的模型。管理员账号本身始终豁免。</p>
 */
public enum UserTier {

  /** 免费：占月度总量池，文本/识图走免费档 Provider。 */
  FREE,

  /** 授权：不占总量池、不限额度，文本/识图走授权档 Provider。 */
  AUTHORIZED
}
