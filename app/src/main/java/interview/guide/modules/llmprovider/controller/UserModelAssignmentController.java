package interview.guide.modules.llmprovider.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelAssignmentRequest;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelBatchAssignRequest;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelOptions;
import interview.guide.modules.llmprovider.dto.UserModelAssignmentDtos.UserModelPageResult;
import interview.guide.modules.llmprovider.service.UserModelAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员按用户分配模型（文字 / 识图）。与 AI 调用与成本后台共用 {@code ai:cost:view} 权限。
 */
@RestController
@RequestMapping("/api/admin/user-models")
@PreAuthorize("hasAuthority('ai:cost:view')")
@RequiredArgsConstructor
public class UserModelAssignmentController {

  private final UserModelAssignmentService service;

  @GetMapping("/users")
  public Result<UserModelPageResult> users(
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.success(service.listUsers(keyword, page, size));
  }

  @GetMapping("/options")
  public Result<UserModelOptions> options() {
    return Result.success(service.options());
  }

  @PutMapping("/{userId}")
  public Result<Void> assign(
      @PathVariable Long userId,
      @RequestBody @Valid UserModelAssignmentRequest request) {
    service.assign(userId, request);
    return Result.success();
  }

  @PutMapping("/batch")
  public Result<Void> assignBatch(@RequestBody @Valid UserModelBatchAssignRequest request) {
    service.assignBatch(request);
    return Result.success();
  }
}
