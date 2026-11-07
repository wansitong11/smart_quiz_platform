package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.model.PageSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 页几何快照可用性的单一判定入口。
 *
 * <p>识别结果预览与切题前的确定性归属都要判断「这页的几何快照能不能用」，
 * 判定规则集中在这里，避免两处各自解析漂移。</p>
 */
public final class PageGeometryInspector {

  public enum GeometryIssue {
    OK,
    MISSING,
    INVALID,
    NOT_REQUIRED
  }

  /**
   * @param issue   判定结果
   * @param snapshot 仅 issue 为 OK 时有值
   * @param failure  issue 为 INVALID 且由反序列化失败导致时的原始异常
   * @param reason   issue 为 INVALID 但非反序列化失败时的说明
   */
  public record Inspection(
      GeometryIssue issue,
      PageRecognitionGeometrySnapshot snapshot,
      JacksonException failure,
      String reason
  ) {
  }

  private PageGeometryInspector() {
  }

  public static Inspection inspect(
      PageSource source,
      String assetsJson,
      int assetCount,
      ObjectMapper objectMapper
  ) {
    if (source == PageSource.IMAGE && assetCount == 0) {
      return new Inspection(GeometryIssue.NOT_REQUIRED, null, null, null);
    }
    if (assetsJson == null || assetsJson.isBlank()) {
      GeometryIssue issue = source == PageSource.IMAGE
          ? GeometryIssue.MISSING
          : GeometryIssue.NOT_REQUIRED;
      return new Inspection(issue, null, null, null);
    }
    try {
      PageRecognitionGeometrySnapshot snapshot = objectMapper.readValue(
          assetsJson, PageRecognitionGeometrySnapshot.class);
      if (snapshot != null && snapshot.compatible()) {
        return new Inspection(GeometryIssue.OK, snapshot, null, null);
      }
      return new Inspection(GeometryIssue.INVALID, null, null,
          "schemaVersion incompatible");
    } catch (JacksonException e) {
      return new Inspection(GeometryIssue.INVALID, null, e, null);
    }
  }
}
