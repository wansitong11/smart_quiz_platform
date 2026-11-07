package interview.guide.modules.resume.service;

import interview.guide.common.async.TestBoundedExecutor;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.resume.model.PageAssetGeometry;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import interview.guide.modules.resume.service.BlockTreeDTO.BlockOptionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("切题阶段资源归属")
class PaperStructureResourceOwnershipTest {

  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private PaperPageRepository pageRepository;
  @Mock
  private PaperAssetService assetService;
  @Mock
  private LlmProviderRegistry providerRegistry;
  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;
  @Mock
  private TransactionalExecutor transactionalExecutor;
  @Mock
  private PaperAnalysisPacer analysisPacer;

  @Test
  @DisplayName("第 3 题表格与第 4 题图片选项按锚点分别绑定")
  void bindsAdjacentQuestionAssetsByAnchorLine() throws Exception {
    List<String> lines = List.of(
        "一、阅读",
        "3. 下列字形相同的一项是",
        "A. 辩 辨",
        "B. 辫 辩",
        "C. 辨 辫",
        "D. 辩 辨",
        "4. 看图选字",
        "A. [图片] B. [图片] C. [图片] D. [图片]"
    );
    BlockTreeDTO blocks = new BlockTreeDTO("chinese", 6, List.of(
        new BlockNodeDTO("SECTION", 1, 1, "一、阅读", null, null, List.of(),
            null, List.of(), null, null, null, List.of(), null),
        new BlockNodeDTO("QUESTION", 2, 6, null, "single_choice", 3,
            List.of(new BlockOptionDTO("A", "辩 辨"),
                new BlockOptionDTO("B", "辫 辩"),
                new BlockOptionDTO("C", "辨 辫"),
                new BlockOptionDTO("D", "辩 辨")),
            null, List.of(), null, null, null, List.of(), "TEXT"),
        new BlockNodeDTO("QUESTION", 7, 8, null, "single_choice", 3,
            List.of(), null, List.of(), null, null, null, List.of(), "IMAGE")
    ), List.of());
    List<PaperAssetEntity> assets = List.of(
        asset("p1a3", "TABLE", "OPTIONS", null),
        asset("p1a4", "OPTION_ROW", "OPTIONS", null),
        tile("p1a4:A", "p1a4"),
        tile("p1a4:B", "p1a4"),
        tile("p1a4:C", "p1a4"),
        tile("p1a4:D", "p1a4")
    );
    Map<String, PaperAssetBinding> bindings = Map.of(
        "p1a3", new PaperAssetBinding("p1a3", 1, 3, "TABLE", "OPTIONS", null),
        "p1a4", new PaperAssetBinding("p1a4", 1, 8, "OPTION_ROW", "OPTIONS", null)
    );
    when(assetService.download(assets.get(1))).thenReturn(optionRowImage(4));

    PaperStructureService service = service();
    PaperStructureService.BuiltStructure built = ReflectionTestUtils.invokeMethod(
        service, "assemble", 1L, lines,
        List.of(new int[] {1, 1, lines.size()}), blocks, assets, bindings);

    assertThat(built).isNotNull();
    List<PaperNode> questions = built.paperTree().questionNodes();
    assertThat(questions).hasSize(2);
    assertThat(questions.get(0).meta().assetIds()).containsExactly("p1a3");
    assertThat(questions.get(1).question().options())
        .extracting(option -> option.assetId())
        .containsExactly("p1a4:A", "p1a4:B", "p1a4:C", "p1a4:D");
  }

  @Test
  @DisplayName("当前结构只消费几何快照指向的资源代次")
  void filtersHistoricalAssetGeneration() {
    PaperAssetEntity current = asset("p1a4@newgeneration", "OPTION_ROW", "OPTIONS", null);
    PaperAssetEntity historical = asset("p1a4@oldgeneration", "OPTION_ROW", "OPTIONS", null);
    PageRecognitionGeometrySnapshot snapshot = new PageRecognitionGeometrySnapshot(
        PageRecognitionGeometrySnapshot.CURRENT_VERSION,
        1,
        List.of(),
        List.of(new PageAssetGeometry(
            "p1a4@newgeneration", "OPTION_ROW", "OPTIONS", "A.",
            new double[] {0.1, 0.2, 0.8, 0.1}, "1x4", 4))
    );

    PaperStructureService.AssetGenerationScope scope =
        PaperStructureService.resolveAssetGeneration(
            List.of(historical, current), List.of(snapshot));

    assertThat(scope.assets()).containsExactly(current);
    assertThat(scope.missingAssetKeys()).isEmpty();
  }

  private PaperStructureService service() {
    return new PaperStructureService(
        resumeRepository,
        pageRepository,
        assetService,
        new PaperSubQuestionGrouper(),
        new PaperAssetOwnershipResolver(),
        new PaperAssetBindingService(assetService, new OptionRowSplitter()),
        new PaperQuestionGroupAssembler(),
        providerRegistry,
        structuredOutputInvoker,
        transactionalExecutor,
        new ObjectMapper(),
        new DefaultResourceLoader(),
        analysisPacer,
        TestBoundedExecutor.create(1, 20)
    );
  }

  private PaperAssetEntity asset(String key, String kind, String role, String parent) {
    PaperAssetEntity asset = new PaperAssetEntity();
    asset.setPaperId(1L);
    asset.setPageNo(1);
    asset.setAssetKey(key);
    asset.setKind(kind);
    asset.setRole(role);
    asset.setParentKey(parent);
    return asset;
  }

  private PaperAssetEntity tile(String key, String parent) {
    return asset(key, "OPTION_ITEM", "OPTIONS", parent);
  }

  private byte[] optionRowImage(int optionCount) throws Exception {
    BufferedImage image = new BufferedImage(400, 80, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 400, 80);
    graphics.setColor(Color.BLACK);
    int segment = 400 / optionCount;
    for (int i = 0; i < optionCount; i++) {
      graphics.fillRect(i * segment + 20, 20, segment - 40, 40);
    }
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }
}
