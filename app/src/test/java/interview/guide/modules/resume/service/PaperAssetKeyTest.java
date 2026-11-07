package interview.guide.modules.resume.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("试卷资源代次键")
class PaperAssetKeyTest {

  @Test
  @DisplayName("UUID 去除横线后截取 12 位")
  void compactsAttemptId() {
    assertThat(PaperAssetKey.versioned(
        "p1a4", "12345678-90ab-cdef-1234-567890abcdef"))
        .isEqualTo("p1a4@1234567890ab");
  }

  @Test
  @DisplayName("初始资源使用 initial 代次")
  void usesInitialGenerationForBlankAttempt() {
    assertThat(PaperAssetKey.versioned("p1a4", null)).isEqualTo("p1a4@initial");
    assertThat(PaperAssetKey.optionTile("p1a4@initial", "A"))
        .isEqualTo("p1a4@initial:A");
  }
}
