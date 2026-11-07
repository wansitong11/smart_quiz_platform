package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.resume.model.PaperAssetEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("试卷资源绑定")
class PaperAssetBindingServiceTest {

  private final PaperAssetService assetService = mock(PaperAssetService.class);
  private final PaperAssetBindingService service =
      new PaperAssetBindingService(assetService, new OptionRowSplitter());

  @Test
  @DisplayName("文字选项题拒绝 OPTION_ROW")
  void rejectsOptionRowForTextQuestion() {
    PaperNode question = questionNode(0, 1, 2, questionWithTextOptions());
    PaperNode root = root(question);

    PaperAssetBindingService.BindingResult result = service.bind(
        1L, root, Map.of("p1a4", optionRow("p1a4", 1)), List.of(asset("p1a4", "OPTION_ROW")));

    assertThat(result.warnings()).anyMatch(w -> w.startsWith("INVALID_OPTION_OWNER"));
    assertThat(result.root().collectQuestions().getFirst().question().options())
        .extracting(QuestionOption::text)
        .containsExactly("甲", "乙");
  }

  @ParameterizedTest
  @ValueSource(strings = {"[无法辨认]", "[图片]"})
  @DisplayName("占位文字选项按当前 OPTION_ROW 切分为图片选项")
  void replacesPlaceholderOptionsWithCurrentOptionRow(String placeholder) throws Exception {
    PaperNode question = questionNode(0, 1, 2, questionWithPlaceholderOptions(placeholder));
    PaperNode root = root(question);
    String rowKey = "p1a5";
    PaperAssetEntity row = asset(rowKey, "OPTION_ROW");
    row.setOptionCount(4);
    when(assetService.download(row)).thenReturn(optionRowImage(4));

    PaperAssetBindingService.BindingResult result = service.bind(
        1L, root, Map.of(rowKey, optionRow(rowKey, 1)), List.of(row));

    assertThat(result.root().collectQuestions().getFirst().question().options())
        .extracting(QuestionOption::assetId)
        .containsExactly(rowKey + ":A", rowKey + ":B", rowKey + ":C", rowKey + ":D");
    assertThat(result.root().collectQuestions().getFirst().question().options())
        .extracting(QuestionOption::text)
        .containsOnlyNulls();
    assertThat(result.warnings()).noneMatch(w -> w.startsWith("INVALID_OPTION_OWNER"));
    verify(assetService).deleteOptionTiles(1L, rowKey);
  }

  @Test
  @DisplayName("图片选择题忽略旧 OPTION_ITEM，按当前 OPTION_ROW 重新切分")
  void recutsOptionRowInsteadOfReusingOldTiles() throws Exception {
    PaperNode question = questionNode(0, 1, 2, imageQuestion());
    PaperNode root = root(question);
    String currentRowKey = "p1a4@newgeneration";
    PaperAssetEntity row = asset(currentRowKey, "OPTION_ROW");
    row.setSourceAttemptId("new-generation");
    row.setOptionCount(2);
    PaperAssetEntity oldTileA = tile("p1a4@oldgeneration:A", "p1a4@oldgeneration");
    PaperAssetEntity oldTileB = tile("p1a4@oldgeneration:B", "p1a4@oldgeneration");
    when(assetService.download(row)).thenReturn(optionRowImage(2));

    PaperAssetBindingService.BindingResult result = service.bind(
        1L, root, Map.of(currentRowKey, optionRow(currentRowKey, 1)),
        List.of(row, oldTileA, oldTileB));

    assertThat(result.root().collectQuestions().getFirst().question().options())
        .extracting(QuestionOption::assetId)
        .containsExactly(currentRowKey + ":A", currentRowKey + ":B");
    verify(assetService).deleteOptionTiles(1L, currentRowKey);
  }

  @Test
  @DisplayName("题干图挂到材料而不是相邻题")
  void bindsStemFigureToMaterial() {
    PaperNode material = materialNode(1, 2);
    PaperNode root = root(material);

    PaperAssetBindingService.BindingResult result = service.bind(
        1L, root, Map.of("p1a5", stem("p1a5", 1, "FIGURE")),
        List.of(asset("p1a5", "FIGURE")));

    assertThat(result.root().safeChildren().getFirst().meta().assetIds())
        .containsExactly("p1a5");
  }

  private PaperNode root(PaperNode child) {
    return PaperNode.paper("n0", "试卷", null, List.of(child));
  }

  private PaperNode materialNode(int start, int end) {
    return new PaperNode("n1", PaperNode.TYPE_MATERIAL, 1, 0, true,
        "材料", null, "正文", new PaperNode.PaperMeta(
            null, null, null, List.of(), start, end), null, null, List.of());
  }

  private PaperNode questionNode(
      int index,
      int start,
      int end,
      InterviewQuestionDTO question
  ) {
    return new PaperNode("q" + (index + 1), PaperNode.TYPE_QUESTION, 1, 0, true,
        null, null, null, new PaperNode.PaperMeta(
            question.fullScore(), 1, null, List.of(), start, end),
        index, question, List.of());
  }

  private InterviewQuestionDTO questionWithTextOptions() {
    return question(List.of(
        new QuestionOption("A", "甲"),
        new QuestionOption("B", "乙")
    ));
  }

  private InterviewQuestionDTO imageQuestion() {
    return question(List.of());
  }

  private InterviewQuestionDTO questionWithPlaceholderOptions(String placeholder) {
    return question(List.of(
        new QuestionOption("A", placeholder),
        new QuestionOption("B", placeholder),
        new QuestionOption("C", placeholder),
        new QuestionOption("D", placeholder)
    ));
  }

  private InterviewQuestionDTO question(List<QuestionOption> options) {
    return new InterviewQuestionDTO(
        0, "看图选择", "SINGLE_CHOICE", "阅读", null, null, null, null,
        false, null, "A", List.of(), null, null, 3,
        InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
        options, null, null, null, null, null, null, false);
  }

  private PaperAssetBinding optionRow(String key, int anchorLine) {
    return new PaperAssetBinding(key, 1, anchorLine, "OPTION_ROW", "OPTIONS",
        new double[] {0.1, 0.2, 0.8, 0.1});
  }

  private PaperAssetBinding stem(String key, int anchorLine, String kind) {
    return new PaperAssetBinding(key, 1, anchorLine, kind, "STEM",
        new double[] {0.1, 0.2, 0.8, 0.1});
  }

  private PaperAssetEntity asset(String key, String kind) {
    PaperAssetEntity asset = new PaperAssetEntity();
    asset.setPaperId(1L);
    asset.setPageNo(1);
    asset.setAssetKey(key);
    asset.setKind(kind);
    asset.setRole("OPTIONS");
    return asset;
  }

  private PaperAssetEntity tile(String key, String parentKey) {
    PaperAssetEntity asset = asset(key, "OPTION_ITEM");
    asset.setParentKey(parentKey);
    return asset;
  }

  private byte[] optionRowImage(int optionCount) throws Exception {
    int width = 400;
    int height = 80;
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, width, height);
    graphics.setColor(Color.BLACK);
    int segment = width / optionCount;
    for (int i = 0; i < optionCount; i++) {
      graphics.fillRect(i * segment + 20, 20, segment - 40, 40);
    }
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }
}
