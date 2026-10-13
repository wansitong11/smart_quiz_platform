package interview.guide.modules.auth.dto;

/**
 * 登录/续期响应。
 * <p>Refresh Token 不放在响应体里，而是通过 HttpOnly Cookie 下发。
 */
public record LoginResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    CurrentUserResponse user) {
}
