package interview.guide.modules.auth.dto;

import interview.guide.modules.auth.model.UserStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号列表/详情
 */
public record UserResponse(
    Long id,
    String username,
    String displayName,
    String email,
    UserStatus status,
    List<String> roles,
    LocalDateTime lastLoginAt,
    LocalDateTime createdAt) {
}
