package interview.guide.modules.auth.config;

import interview.guide.modules.auth.security.JwtAuthenticationFilter;
import interview.guide.modules.auth.security.RestAuthErrorHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Spring Security 配置：无状态 JWT 认证 + RBAC 方法级鉴权。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final RestAuthErrorHandler restAuthErrorHandler;
  private final AuthProperties authProperties;
  private final CorsConfigurationSource corsConfigurationSource;

  @Bean
  public PasswordEncoder passwordEncoder() {
    // BCrypt 默认强度 10，够用且不会拖慢登录接口
    return new BCryptPasswordEncoder();
  }

  /**
   * 本项目认证完全走 JWT，不使用表单登录的表单认证。
   * 显式提供一个 UserDetailsService，避免 Spring Boot 自动生成带随机密码的内存用户
   * （启动日志里那句 "Using generated security password" 就是它）。
   */
  @Bean
  public UserDetailsService userDetailsService() {
    return username -> {
      throw new UsernameNotFoundException("本项目不使用 UserDetailsService 认证: " + username);
    };
  }

  /**
   * 关键：{@link JwtAuthenticationFilter} 是 Spring Bean，Boot 会把它自动注册到 Servlet 过滤链，
   * 于是它会在 Spring Security 过滤链之前先跑一次并打上 OncePerRequestFilter 的"已执行"标记，
   * 导致安全链内的那次注册被跳过 —— 而 SecurityContextHolderFilter 会在链首清空上下文，
   * 最终表现为"带了合法令牌却仍然 401"。这里关闭这层多余注册。
   */
  @Bean
  public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
      JwtAuthenticationFilter filter) {
    FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        // CORS 必须在鉴权之前生效，否则浏览器的预检 OPTIONS 请求会被 401 拦掉
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(handling -> handling
            .authenticationEntryPoint(restAuthErrorHandler)
            .accessDeniedHandler(restAuthErrorHandler))
        .authorizeHttpRequests(auth -> {
          auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
          auth.requestMatchers(
              "/api/auth/login",
              "/api/auth/register",
              "/api/auth/refresh",
              // 退出登录做成幂等：Access Token 可能已过期，仍应允许清理 Cookie
              "/api/auth/logout").permitAll();
          auth.requestMatchers(
              "/actuator/health",
              "/actuator/health/**",
              "/actuator/info").permitAll();
          // WebSocket 握手由 HandshakeInterceptor 校验令牌，这里放行避免握手阶段被 401
          auth.requestMatchers("/ws/**").permitAll();
          if (authProperties.isSwaggerPublic()) {
            auth.requestMatchers(
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/v3/api-docs",
                "/v3/api-docs/**",
                "/swagger-resources/**",
                "/webjars/**").permitAll();
          }
          auth.anyRequest().authenticated();
        })
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
