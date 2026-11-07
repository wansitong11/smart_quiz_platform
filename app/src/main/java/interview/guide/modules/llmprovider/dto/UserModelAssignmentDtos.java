package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户模型绑定相关 DTO。字段名与前端保持一致。
 */
public final class UserModelAssignmentDtos {

  private UserModelAssignmentDtos() {
  }

  /**
   * 单个用户赋值；字段为 null / blank 表示清除该维度绑定（继承全局默认）。
   */
  public record UserModelAssignmentRequest(
      String textProviderId,
      String visionProviderId) {
  }

  /**
   * 批量赋值：同一份绑定覆盖所有选中用户。
   */
  public record UserModelBatchAssignRequest(
      @NotEmpty List<Long> userIds,
      String textProviderId,
      String visionProviderId) {
  }

  public record UserModelAssignmentItem(
      Long userId,
      String username,
      String displayName,
      /** 用户显式绑定值；null 表示没有绑定，编辑时继续沿用“留空=默认”的语义。 */
      String textProviderId,
      /** 按当前用户档位解析后的实际生效 Provider。 */
      String textEffectiveProviderId,
      /** 实际生效 Provider 对应的模型名。 */
      String textEffectiveModel,
      boolean textDefault,
      String visionProviderId,
      String visionEffectiveProviderId,
      String visionEffectiveModel,
      boolean visionDefault,
      LocalDateTime updatedAt) {
  }

  public record UserModelPageResult(
      List<UserModelAssignmentItem> items,
      long total,
      int page,
      int size) {
  }

  public record ProviderOption(String id, String model) {
  }

  public record UserModelOptions(
      List<ProviderOption> textProviders,
      List<ProviderOption> visionProviders) {
  }
}
