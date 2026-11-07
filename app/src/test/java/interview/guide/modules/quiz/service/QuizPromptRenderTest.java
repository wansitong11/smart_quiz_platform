package interview.guide.modules.quiz.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("模拟测验组卷 Prompt 模板")
class QuizPromptRenderTest {

  private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

  private String load(String location) throws Exception {
    return resourceLoader.getResource("classpath:" + location)
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private Map<String, Object> variables() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("examName", "中小学教师资格（小学）");
    map.put("subjectName", "教育教学知识与能力（小学）");
    map.put("persona", "你是该科目的命题教师。");
    map.put("outlineSection", "本卷覆盖考点：教育基础、教学实施。");
    map.put("difficultyDescription", "中等（需要两步以上推理）");
    map.put("questionCount", 8);
    map.put("typePreference", "按题型权重出题：单选（默认 2 分，约 50%）");
    map.put("categoryTable", "| EDU_BASICS | 教育基础 | 4 |\n| TEACHING_IMPL | 教学实施 | 4 |");
    map.put("historicalSection", "（无历史题目）");
    map.put("maxMaterialChars", 1200);
    map.put("maxReadingMaterialChars", 4000);
    map.put("maxTotalMaterialChars", 3600);
    map.put("maxStemChars", 600);
    return map;
  }

  @Test
  @DisplayName("系统模板与用户模板都能用全量变量正常渲染")
  void shouldRenderBothTemplates() throws Exception {
    String system = new PromptTemplate(load("prompts/quiz-paper-generation-system.st"))
        .render(variables());
    String user = new PromptTemplate(load("prompts/quiz-paper-generation-user.st"))
        .render(variables());

    assertThat(system)
        .contains("中小学教师资格（小学）")
        .contains("教育教学知识与能力（小学）")
        .doesNotContain("{examName}", "{subjectName}");
    assertThat(user)
        .contains("共 8 题")
        .contains("EDU_BASICS")
        .doesNotContain("{questionCount}", "{categoryTable}");
  }
}
