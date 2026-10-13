package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * 新建账号（管理员）
 */
public record CreateUserRequest(
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{3,64}$",
        message = "用户名只能包含字母、数字、下划线、点和短横线，长度 3~64")
    String username,

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 128, message = "密码长度需在 8~128 之间")
    String password,

    @Size(max = 64, message = "显示名称过长")
    String displayName,

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱过长")
    String email,

    Set<String> roles) {
}
