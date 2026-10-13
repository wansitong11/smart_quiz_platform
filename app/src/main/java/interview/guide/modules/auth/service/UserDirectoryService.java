package interview.guide.modules.auth.service;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户目录：给其他模块提供"按 id 批量查账号名"这类只读查询。
 *
 * <p>业务模块不要直接依赖认证模块的 Repository（跨模块边界），展示归属人就走这里。
 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

  private final UserRepository userRepository;

  /**
   * 批量取「用户 id → 账号名」。id 集合为空或全为 null 时返回空 Map。
   * 一次查询覆盖整页数据，避免逐行查库。
   */
  @Transactional(readOnly = true)
  public Map<Long, String> usernamesByIds(Collection<Long> ids) {
    if (ids == null) {
      return Map.of();
    }
    var distinctIds = ids.stream()
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return userRepository.findAllById(distinctIds).stream()
        .collect(Collectors.toMap(
            UserEntity::getId, UserEntity::getUsername, (first, second) -> first));
  }
}
