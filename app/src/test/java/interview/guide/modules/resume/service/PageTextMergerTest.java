package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("整卷文本合并与页码定位")
class PageTextMergerTest {

  private final PageTextMerger merger = new PageTextMerger();

  @Test
  @DisplayName("合并时清洗页眉页脚，并删除续页重复题号")
  void mergeCleansHeaderAndContinuationNumber() {
    PaperPageEntity page1 = page(1, "1. alpha question\nA. one\nB. two\n六年级语文试卷 第 1 页（共 2 页）");
    PaperPageEntity page2 = page(2, "1. beta option continues\n2. gamma question");

    PageTextMerger.MergeResult result = merger.merge(List.of(page1, page2));

    assertThat(result.text()).doesNotContain("第 1 页");
    assertThat(result.text()).contains("beta option continues");
    assertThat(result.warnings()).anyMatch(w -> w.contains("重复题号 1"));
    assertThat(result.pageRanges()).hasSize(2);
  }

  @Test
  @DisplayName("题干重新编号后仍能按文本位置定位到正确页码")
  void locatorFindsPageByQuestionText() {
    String page1 = "SECTION-A\n1. alpha choice\n2. beta choice\n";
    String page2 = "SECTION-B\n1. gamma fill\n2. reading one\n";
    String page3 = "reading two\n1. final essay\n";
    String text = page1 + page2 + page3;
    List<int[]> ranges = List.of(
        new int[] {1, 0, page1.length()},
        new int[] {2, page1.length(), page1.length() + page2.length()},
        new int[] {3, page1.length() + page2.length(), text.length()}
    );

    PageTextMerger.PageLocator locator = new PageTextMerger.PageLocator(text, ranges);

    assertThat(locator.locate("1. alpha choice")).containsExactly(1, 1);
    assertThat(locator.locate("3. gamma fill")).containsExactly(2, 2);
    assertThat(locator.locate("5. final essay")).containsExactly(3, 3);
  }

  private PaperPageEntity page(int pageNo, String text) {
    PaperPageEntity page = new PaperPageEntity();
    page.setPageNo(pageNo);
    page.setSource(PageSource.TEXT);
    page.setStatus(PageStatus.READY);
    page.setTextContent(text);
    return page;
  }
}
