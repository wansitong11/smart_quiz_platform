package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SectionChunkerTest {
  @Test
  @DisplayName("分块按原顺序保留短尾和表情字符")
  void retainsAllText() {
    String text = "规则说明。".repeat(20) + "😀末尾";
    assertThat(String.join("", new SectionChunker().split(text, 10, 100))).isEqualTo(text);
  }

  @Test
  @DisplayName("超过块数上限时失败")
  void rejectsTooManyChunks() {
    assertThatThrownBy(() -> new SectionChunker().split("规则说明。".repeat(30), 5, 1))
        .isInstanceOf(BusinessException.class).hasMessageContaining("数量超过上限");
  }

  @Test
  @DisplayName("章节解析保留标题层级并忽略围栏内标题")
  void parsesSections() {
    var sections = new MarkdownSectionParser().parse(
        "# 一级\n开头\n```\n## 代码\n```\n## 二级\n结尾", true);
    assertThat(sections).hasSize(2);
    assertThat(sections.getFirst().body()).contains("## 代码");
    assertThat(sections.getLast().path()).containsExactly("一级", "二级");
  }
}
