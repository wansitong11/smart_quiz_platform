package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("长试卷语义分块边界")
class PaperStructureChunkingTest {

  @Test
  @DisplayName("新的大题标题和阅读小节可以作为分块边界")
  void detectsSemanticBoundary() {
    List<String> lines = List.of(
        "名片三",
        "第二部分 阅读与探究",
        "（二）非连续性文本阅读",
        "三 句子荟萃馆",
        "参考答案与解析"
    );

    assertThat(PaperStructureService.isSemanticBoundaryStart(lines, 2)).isTrue();
    assertThat(PaperStructureService.isSemanticBoundaryStart(lines, 3)).isTrue();
    assertThat(PaperStructureService.isSemanticBoundaryStart(lines, 4)).isTrue();
    assertThat(PaperStructureService.isSemanticBoundaryStart(lines, 5)).isTrue();
    assertThat(PaperStructureService.isSemanticBoundaryStart(lines, 1)).isFalse();
  }

  @Test
  @DisplayName("软上限只在语义边界拆块，硬上限强制拆块")
  void shouldPlanChunksBySoftAndHardLimits() {
    String firstPage = "甲".repeat(2000);
    String secondPage = "乙".repeat(2000);
    String boundaryPage = "第二部分 阅读与探究";

    List<PaperStructureService.ChunkPlan> semanticPlans =
        PaperStructureService.planChunks(
            List.of(firstPage, secondPage, boundaryPage),
            List.of(new int[] {1, 1, 1}, new int[] {2, 2, 2}, new int[] {3, 3, 3}));

    assertThat(semanticPlans).hasSize(2);
    assertThat(semanticPlans.get(0).startLine()).isEqualTo(1);
    assertThat(semanticPlans.get(0).endLine()).isEqualTo(2);
    assertThat(semanticPlans.get(0).charCount()).isEqualTo(4000);
    assertThat(semanticPlans.get(1).startLine()).isEqualTo(3);
    assertThat(semanticPlans.get(1).endLine()).isEqualTo(3);

    List<PaperStructureService.ChunkPlan> hardLimitPlans =
        PaperStructureService.planChunks(
            List.of("甲".repeat(3000), "乙".repeat(3000)),
            List.of(new int[] {1, 1, 1}, new int[] {2, 2, 2}));

    assertThat(hardLimitPlans).extracting(PaperStructureService.ChunkPlan::startLine)
        .containsExactly(1, 2);
  }

  @Test
  @DisplayName("组装时按行号排序、warnings 按块序合并，失败块不丢其他块")
  void shouldAssembleChunksInStableOrder() {
    BlockTreeDTO assembled = PaperStructureService.assembleChunks(List.of(
        new PaperStructureService.ChunkResult(
            List.of(node(20)), List.of("第一块提示"), "", true),
        new PaperStructureService.ChunkResult(
            List.of(node(10)), List.of("第二块提示"), "math", true)
    ));

    assertThat(assembled.subjectKey()).isEqualTo("math");
    assertThat(assembled.nodes()).extracting(BlockNodeDTO::startLine)
        .containsExactly(10, 20);
    assertThat(assembled.warnings()).containsExactly(
        "第一块提示", "第二块提示",
        "试卷较长，已分 2 块结构化；跨块材料请在校对页重点核对");

    BlockTreeDTO withFailedChunk = PaperStructureService.assembleChunks(List.of(
        new PaperStructureService.ChunkResult(
            List.of(node(5)), List.of("成功块"), "math", true),
        new PaperStructureService.ChunkResult(
            List.of(), List.of("失败块已跳过"), null, false)
    ));

    assertThat(withFailedChunk.nodes()).extracting(BlockNodeDTO::startLine)
        .containsExactly(5);
    assertThat(withFailedChunk.warnings()).containsExactly("成功块", "失败块已跳过");
  }

  private BlockNodeDTO node(int startLine) {
    return new BlockNodeDTO(
        "QUESTION", startLine, startLine, "题目", null, null, List.of(),
        null, List.of(), null, null, null, List.of(), null);
  }
}
