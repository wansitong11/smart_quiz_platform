package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageAssetGeometry;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.log.PaperFlowLog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用页面几何把资源锚点解析到全卷连续行号。
 *
 * <p>纯计算：不读写数据库，不持有可变状态。相同输入始终得到相同结果；无法唯一
 * 消歧的资源直接不出现在结果中，由上层记录 UNBOUND_ASSET。</p>
 */
@Component
public class PaperAssetOwnershipResolver {

  private static final double SCORE_EPSILON = 1e-6;

  public Map<String, PaperAssetBinding> resolve(
      PageRecognitionGeometrySnapshot snapshot,
      int pageLineStart
  ) {
    if (snapshot == null || !snapshot.compatible() || snapshot.assets().isEmpty()) {
      PaperFlowLog.warn("ASSET_OWNERSHIP_RESOLVE_SKIPPED pageNo={}, pageLineStart={}, "
              + "snapshot={}, compatible={}, assets={}",
          snapshot == null ? null : snapshot.pageNo(), pageLineStart, snapshot,
          snapshot != null && snapshot.compatible(),
          snapshot == null ? 0 : snapshot.assets().size());
      return Map.of();
    }
    List<LineCandidate> visibleLines = visibleLines(snapshot.lines());
    PaperFlowLog.event("ASSET_OWNERSHIP_RESOLVE_BEGIN pageNo={}, pageLineStart={}, "
            + "visibleLines={}, assets={}",
        snapshot.pageNo(), pageLineStart, visibleLines.size(), snapshot.assets().size());
    Map<String, PaperAssetBinding> result = new LinkedHashMap<>();
    for (PageAssetGeometry asset : snapshot.assets()) {
      if (asset == null || asset.assetKey() == null || asset.assetKey().isBlank()) {
        PaperFlowLog.warn("ASSET_OWNERSHIP_INVALID_ASSET pageNo={}, asset={}",
            snapshot.pageNo(), asset);
        continue;
      }
      Integer anchorLine = resolveAnchor(asset, visibleLines, pageLineStart, snapshot.pageNo());
      if (anchorLine != null) {
        result.put(asset.assetKey(), new PaperAssetBinding(
            asset.assetKey(),
            snapshot.pageNo(),
            anchorLine,
            asset.kind(),
            asset.role(),
            asset.rect()
        ));
        PaperFlowLog.event("ASSET_OWNERSHIP_BOUND pageNo={}, key={}, kind={}, role={}, "
                + "anchor={}, anchorLine={}, rect={}",
            snapshot.pageNo(), asset.assetKey(), asset.kind(), asset.role(), asset.anchorText(),
            anchorLine, asset.rect());
      } else {
        PaperFlowLog.warn("ASSET_OWNERSHIP_UNBOUND pageNo={}, key={}, kind={}, role={}, "
                + "anchor={}, rect={}",
            snapshot.pageNo(), asset.assetKey(), asset.kind(), asset.role(), asset.anchorText(),
            asset.rect());
      }
    }
    PaperFlowLog.event("ASSET_OWNERSHIP_RESOLVE_END pageNo={}, resolved={}, total={}",
        snapshot.pageNo(), result.size(), snapshot.assets().size());
    return Map.copyOf(result);
  }

  private Integer resolveAnchor(
      PageAssetGeometry asset,
      List<LineCandidate> lines,
      int pageLineStart,
      int pageNo
  ) {
    String anchor = normalize(asset.anchorText());
    if (anchor.isEmpty()) {
      PaperFlowLog.warn("ASSET_ANCHOR_EMPTY pageNo={}, key={}, rect={}",
          pageNo, asset.assetKey(), asset.rect());
      return null;
    }
    List<ScoredLine> candidates = new ArrayList<>();
    for (LineCandidate line : lines) {
      String text = normalize(line.text());
      if (!text.contains(anchor) && !anchor.contains(text)) {
        continue;
      }
      if (!sameColumn(asset.rect(), line.bbox())) {
        continue;
      }
      candidates.add(new ScoredLine(line, score(asset.rect(), line.bbox())));
    }
    if (candidates.isEmpty()) {
      PaperFlowLog.warn("ASSET_ANCHOR_NO_CANDIDATE pageNo={}, key={}, anchor={}, "
              + "pageLineStart={}, visibleLines={}",
          pageNo, asset.assetKey(), asset.anchorText(), pageLineStart, lines.size());
      return null;
    }
    candidates.sort(Comparator
        .comparingDouble(ScoredLine::score)
        .thenComparingInt(scored -> scored.line().ordinal()));
    ScoredLine best = candidates.getFirst();
    if (candidates.size() > 1
        && Math.abs(candidates.get(1).score() - best.score()) <= SCORE_EPSILON) {
      PaperFlowLog.warn("ASSET_ANCHOR_AMBIGUOUS pageNo={}, key={}, anchor={}, "
              + "best={}, second={}, pageLineStart={}",
          pageNo, asset.assetKey(), asset.anchorText(), best.score(),
          candidates.get(1).score(), pageLineStart);
      return null;
    }
    int resolved = pageLineStart + best.line().ordinal();
    PaperFlowLog.event("ASSET_ANCHOR_RESOLVED pageNo={}, key={}, anchor={}, line={}, "
            + "score={}, text={}",
        pageNo, asset.assetKey(), asset.anchorText(), resolved, best.score(),
        best.line().text());
    return resolved;
  }

  private List<LineCandidate> visibleLines(List<PageRecognition.PageLine> lines) {
    List<LineCandidate> visible = new ArrayList<>();
    if (lines == null) {
      return visible;
    }
    int ordinal = 0;
    for (PageRecognition.PageLine line : lines) {
      if (line == null || line.text() == null || line.text().isBlank()) {
        continue;
      }
      visible.add(new LineCandidate(line.text(), line.bbox(), ordinal++));
    }
    return visible;
  }

  private boolean sameColumn(double[] assetRect, double[] lineBbox) {
    if (assetRect == null || assetRect.length < 4
        || lineBbox == null || lineBbox.length < 4) {
      return true;
    }
    double assetLeft = assetRect[0];
    double assetRight = assetRect[0] + assetRect[2];
    double lineLeft = lineBbox[0];
    double lineRight = lineBbox[0] + lineBbox[2];
    double overlap = Math.min(assetRight, lineRight) - Math.max(assetLeft, lineLeft);
    if (overlap > 0) {
      return true;
    }
    double centerX = assetLeft + assetRect[2] / 2;
    double padding = Math.max(0.02, assetRect[2] / 10);
    return centerX >= lineLeft - padding && centerX <= lineRight + padding;
  }

  private double score(double[] assetRect, double[] lineBbox) {
    if (assetRect == null || assetRect.length < 4
        || lineBbox == null || lineBbox.length < 4) {
      return 0;
    }
    double assetCenterY = assetRect[1] + assetRect[3] / 2;
    double lineCenterY = lineBbox[1] + lineBbox[3] / 2;
    return Math.abs(assetCenterY - lineCenterY);
  }

  private String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
  }

  private record LineCandidate(String text, double[] bbox, int ordinal) {
  }

  private record ScoredLine(LineCandidate line, double score) {
  }
}
