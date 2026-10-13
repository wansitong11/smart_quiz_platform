package interview.guide.modules.auth.dto;

import interview.guide.modules.auth.model.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * 修改账号（管理员）
 */
public record UpdateUserRequest(
    @Size(max = 64, message = "显示名称过长")
    String displayName,

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    String email,

    UserStatus status,

    Set<String> roles) {
}
