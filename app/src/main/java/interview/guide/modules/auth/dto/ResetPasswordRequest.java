package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员重置他人密码
 */
public record ResetPasswordRequest(
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 128, message = "新密码长度需在 8~128 之间")
    String newPassword) {
}
