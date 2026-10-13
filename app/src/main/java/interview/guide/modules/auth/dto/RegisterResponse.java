package interview.guide.modules.auth.dto;

/**
 * 注册结果。注册不直接发令牌，前端拿到用户名后回到登录界面继续登录。
 */
public record RegisterResponse(String username) {
}
