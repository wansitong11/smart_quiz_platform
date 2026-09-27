package interview.guide.modules.knowledgebase;

import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.CreateKbCategoryRequest;
import interview.guide.modules.knowledgebase.model.KbCategoryDTO;
import interview.guide.modules.knowledgebase.model.UpdateKbCategoryRequest;
import interview.guide.modules.knowledgebase.service.KbCategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资料分类接口。
 * <p>
 * 分类用于组织资料（语文 / 数学 / 英语或自定义），也是问答会话检索范围的选项来源。
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "资料分类", description = "资料分类的增删改查")
public class KbCategoryController {

    private final KbCategoryService categoryService;

    /**
     * 分类列表（含资料数与未向量化数，末尾附带「未分类」统计）
     */
    @GetMapping("/api/kb-categories")
    public Result<List<KbCategoryDTO>> list() {
        return Result.success(categoryService.list());
    }

    /**
     * 新建分类
     */
    @PostMapping("/api/kb-categories")
    public Result<KbCategoryDTO> create(@Valid @RequestBody CreateKbCategoryRequest request) {
        return Result.success(categoryService.create(request.name(), request.code(),
            request.subjectCode(), request.sortOrder()));
    }

    /**
     * 改名 / 调整排序 / 挂学科
     */
    @PutMapping("/api/kb-categories/{id}")
    public Result<KbCategoryDTO> update(@PathVariable Long id,
                                        @RequestBody UpdateKbCategoryRequest request) {
        return Result.success(categoryService.update(id, request.name(), request.subjectCode(),
            request.sortOrder()));
    }

    /**
     * 删除分类（其下资料落回「未分类」；系统预置分类不可删）
     */
    @DeleteMapping("/api/kb-categories/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success(null);
    }
}
