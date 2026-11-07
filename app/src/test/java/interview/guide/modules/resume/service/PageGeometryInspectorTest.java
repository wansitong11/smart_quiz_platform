package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.model.PageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("页几何快照可用性判定")
class PageGeometryInspectorTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("识图页无资源时无需几何快照")
  void imagePageWithoutAssetsIsNotRequired() {
    PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
        PageSource.IMAGE, null, 0, objectMapper);

    assertThat(inspection.issue())
        .isEqualTo(PageGeometryInspector.GeometryIssue.NOT_REQUIRED);
  }

  @Test
  @DisplayName("识图页有资源但快照为空时判缺失")
  void imagePageWithAssetsAndBlankSnapshotIsMissing() {
    PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
        PageSource.IMAGE, null, 2, objectMapper);

    assertThat(inspection.issue()).isEqualTo(PageGeometryInspector.GeometryIssue.MISSING);
  }

  @Test
  @DisplayName("识图页快照无法反序列化时判不兼容")
  void imagePageWithBrokenSnapshotIsInvalid() {
    PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
        PageSource.IMAGE, "{not json", 2, objectMapper);

    assertThat(inspection.issue()).isEqualTo(PageGeometryInspector.GeometryIssue.INVALID);
    assertThat(inspection.failure()).isNotNull();
  }

  @Test
  @DisplayName("识图页快照版本不兼容时判不兼容")
  void imagePageWithIncompatibleSnapshotIsInvalid() throws Exception {
    String json = objectMapper.writeValueAsString(
        new PageRecognitionGeometrySnapshot(0, 1, List.of(), List.of()));

    PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
        PageSource.IMAGE, json, 2, objectMapper);

    assertThat(inspection.issue()).isEqualTo(PageGeometryInspector.GeometryIssue.INVALID);
  }

  @Test
  @DisplayName("识图页兼容快照直接返回")
  void imagePageWithCompatibleSnapshotIsOk() throws Exception {
    String json = objectMapper.writeValueAsString(
        PageRecognitionGeometrySnapshot.empty(1));

    PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
        PageSource.IMAGE, json, 2, objectMapper);

    assertThat(inspection.issue()).isEqualTo(PageGeometryInspector.GeometryIssue.OK);
    assertThat(inspection.snapshot()).isNotNull();
  }

  @Test
  @DisplayName("文本页空快照无需几何快照")
  void textPageWithoutSnapshotIsNotRequired() {
    PageGeometryInspector.Inspection inspection = PageGeometryInspector.inspect(
        PageSource.TEXT, null, 0, objectMapper);

    assertThat(inspection.issue())
        .isEqualTo(PageGeometryInspector.GeometryIssue.NOT_REQUIRED);
  }
}
