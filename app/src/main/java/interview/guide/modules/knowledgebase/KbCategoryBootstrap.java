package interview.guide.modules.knowledgebase;

import interview.guide.modules.knowledgebase.service.KbCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时保证系统预置分类（语文 / 数学 / 英语）存在，幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbCategoryBootstrap implements ApplicationRunner {

    private final KbCategoryService categoryService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            categoryService.ensureSystemCategories();
        } catch (Exception e) {
            log.error("初始化系统资料分类失败: {}", e.getMessage(), e);
        }
    }
}
