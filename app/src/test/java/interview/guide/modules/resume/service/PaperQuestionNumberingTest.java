package interview.guide.modules.resume.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("切题前的题号重排")
class PaperQuestionNumberingTest {

  @Test
  @DisplayName("每个大题内部重新编号时，统一重排为全卷 1..N")
  void renumbersPerSectionQuestionsGlobally() {
    String text = """
        SECTION-A
        1. alpha
        2. beta
        SECTION-B
        1. gamma
        2. delta
        3) epsilon
        SECTION-C
        1. zeta
        """;

    String renumbered = PaperQuestionExtractionService.renumberQuestions(text);

    assertThat(renumbered).contains("1. alpha", "2. beta", "3. gamma", "4. delta", "5) epsilon", "6. zeta");
  }

  @Test
  @DisplayName("正文里的数字与无题号行保持不变")
  void keepsNonQuestionLinesUntouched() {
    String text = "满分 100 分\n1. first\n材料中提到 2024 年。";

    String renumbered = PaperQuestionExtractionService.renumberQuestions(text);

    assertThat(renumbered).isEqualTo("满分 100 分\n1. first\n材料中提到 2024 年。");
  }

  @Test
  @DisplayName("整段没有题号的大题补一个题号，已有题号的大题不动")
  void addsSyntheticNumberForUnnumberedSection() {
    String text = """
        一、看拼音写词语。（8 分）
        中国近代史是一部奋斗史，请根据拼音写词语。
        二、选择题。（6 分）
        1. alpha
        2. beta
        """;

    String normalized = PaperQuestionExtractionService.ensureSectionQuestions(text);

    assertThat(normalized).contains("一、看拼音写词语。（8 分）\n1. 看拼音写词语。（8 分）");
    assertThat(normalized).doesNotContain("1. 选择题");
  }
}
