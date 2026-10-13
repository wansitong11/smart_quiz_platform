package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 自助注册请求（公开接口，只有用户名和密码）
 */
public record RegisterRequest(
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{3,64}$",
        message = "用户名只能包含字母、数字、下划线、点和短横线，长度 3~64")
    String username,

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 128, message = "密码长度需在 8~128 之间")
    String password) {
}
