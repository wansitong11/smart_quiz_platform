package interview.guide.modules.auth.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.CreateUserRequest;
import interview.guide.modules.auth.dto.ResetPasswordRequest;
import interview.guide.modules.auth.dto.RoleResponse;
import interview.guide.modules.auth.dto.UpdateUserRequest;
import interview.guide.modules.auth.dto.UserResponse;
import interview.guide.modules.auth.service.UserAdminService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号与角色管理，仅 {@code user:manage} 权限可访问。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('user:manage')")
@RequiredArgsConstructor
public class UserAdminController {

  private final UserAdminService userAdminService;

  @GetMapping("/users")
  public Result<List<UserResponse>> listUsers() {
    return Result.success(userAdminService.listUsers());
  }

  @PostMapping("/users")
  public Result<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
    return Result.success(userAdminService.createUser(request));
  }

  @PutMapping("/users/{id}")
  public Result<UserResponse> updateUser(@PathVariable Long id,
      @Valid @RequestBody UpdateUserRequest request) {
    return Result.success(userAdminService.updateUser(id, request));
  }

  @DeleteMapping("/users/{id}")
  public Result<Void> deleteUser(@PathVariable Long id) {
    userAdminService.deleteUser(id);
    return Result.success();
  }

  @PostMapping("/users/{id}/password")
  public Result<Void> resetPassword(@PathVariable Long id,
      @Valid @RequestBody ResetPasswordRequest request) {
    userAdminService.resetPassword(id, request.newPassword());
    return Result.success();
  }

  @PostMapping("/users/{id}/kick")
  public Result<Void> kickOut(@PathVariable Long id) {
    userAdminService.kickOut(id);
    return Result.success();
  }

  @GetMapping("/roles")
  public Result<List<RoleResponse>> listRoles() {
    return Result.success(userAdminService.listRoles());
  }
}
