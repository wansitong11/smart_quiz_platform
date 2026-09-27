package interview.guide.modules.resume.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 试卷结构化切题配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.paper.extraction")
public class PaperExtractionProperties {

    /** 切题 system prompt 路径 */
    private String systemPromptPath = "classpath:prompts/paper-question-extraction-system.st";

    /** 切题 user prompt 路径 */
    private String userPromptPath = "classpath:prompts/paper-question-extraction-user.st";

    /** 单批送模型的材料字符上限，超出后按题号切块分批处理 */
    private int batchCharLimit = 3000;

    /** 材料整体字符上限，超出部分截断（防止极端输入打爆上下文） */
    private int maxPaperChars = 30000;

    /** 单份材料允许抽取的最大题目数，超出后丢弃多余题目并告警 */
    private int maxQuestions = 80;
}
