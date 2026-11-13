package interview.guide.modules.knowledgebase.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository.NeighborChunk;
import interview.guide.modules.knowledgebase.repository.VectorRepository.NeighborKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RagContextAssembler {
  private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry()
      .getEncoding(EncodingType.CL100K_BASE);
  private final VectorRepository repository;
  private final KnowledgeBaseListService listService;
  private final KnowledgeBaseQueryProperties properties;
  private final RagMetrics metrics;

  public enum Status { NO_HITS, READY, TOO_LONG }
  public record ContextResult(Status status, String text, List<Document> directHits,
                              List<Document> neighbors, List<Long> knowledgeBaseIds, int tokens) { }
  private record Chunk(Document document, long kbId, String parent, int index, int total,
                       String path, int order, Double score) { }
  public record EvidenceBlock(String id, Document document, long kbId, String title, String path,
                              String parent, int index, boolean direct, int order) { }
  public record EvidenceUnit(String id, String anchorBlockId, List<String> blockIds) { }
  public record PreparedContext(Status status, List<EvidenceBlock> blocks, List<EvidenceUnit> units,
                                String text, int tokens) { }
  public record BudgetSnapshot(List<EvidenceBlock> blocks, List<EvidenceUnit> units) { }
  private record Assembly(ContextResult context, PreparedContext evidence) { }

  public ContextResult assemble(List<Document> hits, Set<Long> authorizedIds) {
    return build(hits, authorizedIds, Integer.MAX_VALUE, true, false,
        new BudgetSnapshot(List.of(), List.of())).context();
  }

  public PreparedContext prepareEvidence(List<Document> hits, Set<Long> authorizedIds,
      int maxUnits, boolean includeNeighbors) {
    return prepareEvidence(hits, authorizedIds, maxUnits, includeNeighbors,
        new BudgetSnapshot(List.of(), List.of()));
  }

  public PreparedContext prepareEvidence(List<Document> hits, Set<Long> authorizedIds,
      int maxUnits, boolean includeNeighbors, BudgetSnapshot previous) {
    return build(hits, authorizedIds, maxUnits, includeNeighbors, true, previous).evidence();
  }

  public PreparedContext retainEvidence(PreparedContext prepared, Set<String> keepIds) {
    Set<String> valid = prepared.units().stream().map(EvidenceUnit::id)
        .collect(java.util.stream.Collectors.toSet());
    if (!valid.containsAll(keepIds)) {
      throw new BusinessException(ErrorCode.RAG_EVIDENCE_REVIEW_FAILED, "资料复核返回未知证据编号");
    }
    List<EvidenceUnit> units = prepared.units().stream().filter(unit -> keepIds.contains(unit.id())).toList();
    Set<String> blockIds = units.stream().flatMap(unit -> unit.blockIds().stream())
        .collect(java.util.stream.Collectors.toSet());
    List<EvidenceBlock> blocks = prepared.blocks().stream().filter(block -> blockIds.contains(block.id())).toList();
    String text = renderEvidence(blocks, units);
    return new PreparedContext(units.isEmpty() ? Status.NO_HITS : Status.READY, blocks, units, text, count(text));
  }

  private Assembly build(List<Document> hits, Set<Long> authorizedIds, int maxUnits,
      boolean includeNeighbors, boolean evidenceMode, BudgetSnapshot previous) {
    List<Chunk> candidates = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    if (hits != null) {
      for (Document document : hits) {
        Long id = kbId(document);
        if (id != null && authorizedIds.contains(id) && document.getText() != null
            && !document.getText().isBlank() && seen.add(document.getId())) {
          candidates.add(chunk(document, id, candidates.size()));
        }
      }
    }
    if (candidates.isEmpty()) {
      return emptyAssembly(Status.NO_HITS);
    }
    candidates.sort(Comparator.comparing(Chunk::score,
        Comparator.nullsLast(Comparator.reverseOrder())).thenComparingInt(Chunk::order));
    candidates = candidates.stream().limit(maxUnits).toList();
    Map<Long, String> names;
    try {
      names = listService.getAccessibleKnowledgeBaseNames(candidates.stream().map(Chunk::kbId).distinct().toList());
    } catch (Exception exception) {
      log.warn("读取资料名称失败，使用快照", exception);
      names = Map.of();
    }
    List<Chunk> selected = new ArrayList<>();
    List<Chunk> direct = new ArrayList<>();
    int skipped = 0;
    int errors = 0;
    int limit = properties.getContext().getMaxTokens();
    for (Chunk candidate : candidates) {
      selected.add(candidate);
      if (withinBudget(selected, directWith(direct, candidate), names, evidenceMode, previous, limit)) {
        direct.add(candidate);
      } else {
        selected.removeLast();
        skipped++;
      }
    }
    if (direct.isEmpty()) {
      metrics.recordContext(0, 0, skipped, 0);
      return emptyAssembly(Status.TOO_LONG);
    }
    LinkedHashSet<NeighborKey> keys = new LinkedHashSet<>();
    for (Chunk hit : direct) {
      if (!includeNeighbors || hit.parent() == null) continue;
      int max = Math.max(properties.getContext().getNeighborBefore(), properties.getContext().getNeighborAfter());
      for (int distance = 1; distance <= max; distance++) {
        if (distance <= properties.getContext().getNeighborBefore() && hit.index() >= distance)
          keys.add(new NeighborKey(hit.kbId(), hit.parent(), hit.index() - distance));
        if (distance <= properties.getContext().getNeighborAfter() && hit.index() + distance < hit.total())
          keys.add(new NeighborKey(hit.kbId(), hit.parent(), hit.index() + distance));
      }
    }
    List<Chunk> neighbors = new ArrayList<>();
    try {
      Map<NeighborKey, List<Chunk>> byKey = new HashMap<>();
      for (NeighborChunk neighbor : repository.findNeighbors(List.copyOf(keys))) {
        Long id = kbId(neighbor.metadata());
        if (id == null || !authorizedIds.contains(id) || neighbor.content() == null
            || neighbor.content().isBlank()) continue;
        Chunk chunk = chunk(new Document(neighbor.id(), neighbor.content(), neighbor.metadata()), id, Integer.MAX_VALUE);
        if (chunk.parent() == null) continue;
        NeighborKey key = new NeighborKey(id, chunk.parent(), chunk.index());
        if (keys.contains(key)) byKey.computeIfAbsent(key, unused -> new ArrayList<>()).add(chunk);
      }
      Set<String> selectedIds = new LinkedHashSet<>();
      direct.forEach(hit -> selectedIds.add(hit.document().getId()));
      for (NeighborKey key : keys) {
        List<Chunk> matches = byKey.getOrDefault(key, List.of());
        if (matches.size() > 1) {
          errors++;
          continue;
        }
        if (matches.isEmpty() || selectedIds.contains(matches.getFirst().document().getId())) continue;
        Chunk neighbor = matches.getFirst();
        selected.add(neighbor);
        if (withinBudget(selected, direct, names, evidenceMode, previous, limit)) {
          neighbors.add(neighbor);
          selectedIds.add(neighbor.document().getId());
        }
        else {
          selected.removeLast();
          skipped++;
        }
      }
    } catch (Exception exception) {
      log.warn("邻居读取失败，保留直接命中", exception);
      errors++;
    }
    PreparedContext evidence = prepare(selected, direct, names);
    String text = evidenceMode ? evidence.text() : render(selected, direct, names);
    if (properties.getContext().isDiagnosticsEnabled()) {
      log.info("RAG_CONTEXT_DIAGNOSTIC direct={} neighbors={} skippedOverBudget={} lookupErrors={} "
              + "tokens={} context={} ",
          direct.stream().map(this::diagnosticChunk).toList(),
          neighbors.stream().map(this::diagnosticChunk).toList(),
          skipped, errors, count(text), text);
    }
    metrics.recordContext(direct.size(), neighbors.size(), skipped, errors);
    List<Long> ids = sorted(selected, direct).stream().filter(direct::contains).map(Chunk::kbId).distinct().toList();
    return new Assembly(new ContextResult(Status.READY, text, direct.stream().map(Chunk::document).toList(),
        neighbors.stream().map(Chunk::document).toList(), ids, count(text)), evidence);
  }

  private boolean withinBudget(List<Chunk> chunks, List<Chunk> direct, Map<Long, String> names,
      boolean evidenceMode, BudgetSnapshot previous, int limit) {
    if (!evidenceMode) return count(render(chunks, direct, names)) <= limit;
    PreparedContext proposed = prepare(chunks, direct, names);
    return count(renderEvidence(proposed.blocks(), proposed.units())) <= limit
        && count(renderCombined(previous, proposed.blocks(), proposed.units())) <= limit;
  }

  private PreparedContext prepare(List<Chunk> chunks, List<Chunk> direct, Map<Long, String> names) {
    List<EvidenceBlock> blocks = new ArrayList<>();
    for (Chunk chunk : sorted(chunks, direct)) {
      Object snapshot = chunk.document().getMetadata().get("document_title");
      String title = names.getOrDefault(chunk.kbId(), snapshot instanceof String value ? value : "未知资料");
      blocks.add(new EvidenceBlock("C" + (blocks.size() + 1), chunk.document(), chunk.kbId(), title,
          chunk.path(), chunk.parent(), chunk.index(), direct.contains(chunk), chunk.order()));
    }
    List<EvidenceUnit> units = new ArrayList<>();
    for (Chunk anchor : direct) {
      EvidenceBlock anchorBlock = blocks.stream()
          .filter(block -> block.document().getId().equals(anchor.document().getId())).findFirst().orElseThrow();
      List<String> window = blocks.stream().filter(block -> block == anchorBlock
          || anchor.parent() != null && block.kbId() == anchor.kbId()
          && anchor.parent().equals(block.parent())
          && block.index() >= anchor.index() - properties.getContext().getNeighborBefore()
          && block.index() <= anchor.index() + properties.getContext().getNeighborAfter())
          .map(EvidenceBlock::id).toList();
      units.add(new EvidenceUnit("E" + (units.size() + 1), anchorBlock.id(), window));
    }
    String text = renderEvidence(blocks, units);
    return new PreparedContext(Status.READY, List.copyOf(blocks), List.copyOf(units), text, count(text));
  }

  public static String renderEvidence(List<EvidenceBlock> blocks, List<EvidenceUnit> units) {
    if (units.isEmpty()) return "";
    StringBuilder text = new StringBuilder("本批可引用证据编号：")
        .append(units.stream().map(unit -> "〔" + unit.id() + "〕")
            .collect(java.util.stream.Collectors.joining("、")))
        .append("\n原文块编号仅供定位，不是引用编号，不得将块编号改写成证据编号。\n\n");
    for (EvidenceBlock block : blocks) {
      text.append('[').append(block.id()).append("][资料：").append(block.title()).append("]\n");
      if (!block.path().isBlank()) text.append("[章节：").append(block.path()).append("]\n");
      text.append(block.document().getText()).append("\n\n");
    }
    for (EvidenceUnit unit : units) {
      text.append('〔').append(unit.id()).append("〕 锚点=").append(unit.anchorBlockId())
          .append(" 原文=").append(String.join(",", unit.blockIds())).append('\n');
    }
    return text.toString();
  }

  public static String renderCombined(BudgetSnapshot previous, List<EvidenceBlock> blocks,
      List<EvidenceUnit> units) {
    Map<String, EvidenceBlock> merged = new LinkedHashMap<>();
    previous.blocks().forEach(block -> merged.put(block.document().getId(), block));
    List<EvidenceUnit> allUnits = new ArrayList<>(previous.units());
    Map<String, String> aliases = new HashMap<>();
    for (EvidenceBlock block : blocks) {
      EvidenceBlock existing = merged.get(block.document().getId());
      aliases.put(block.id(), existing == null ? block.id() : existing.id());
      merged.putIfAbsent(block.document().getId(), block);
    }
    units.forEach(unit -> allUnits.add(new EvidenceUnit(unit.id(), aliases.get(unit.anchorBlockId()),
        unit.blockIds().stream().map(aliases::get).toList())));
    return renderEvidence(List.copyOf(merged.values()), allUnits);
  }

  public static int tokenCount(String text) { return ENCODING.countTokensOrdinary(text); }

  private Assembly emptyAssembly(Status status) {
    return new Assembly(empty(status), new PreparedContext(status, List.of(), List.of(), "", 0));
  }

  private String diagnosticChunk(Chunk chunk) {
    return "id=" + chunk.document().getId() + ", kbId=" + chunk.kbId()
        + ", index=" + chunk.index() + ", score=" + chunk.score();
  }

  private List<Chunk> directWith(List<Chunk> direct, Chunk candidate) {
    List<Chunk> result = new ArrayList<>(direct);
    result.add(candidate);
    return result;
  }

  private String render(List<Chunk> chunks, List<Chunk> direct, Map<Long, String> names) {
    return sorted(chunks, direct).stream().map(chunk -> {
      String name = names.get(chunk.kbId());
      if (name == null || name.isBlank()) {
        Object snapshot = chunk.document().getMetadata().get("document_title");
        name = snapshot instanceof String text && !text.isBlank() ? text : "未知资料";
      }
      return "[资料：" + name + "]\n" + (chunk.path().isEmpty() ? "" : "[章节：" + chunk.path() + "]\n")
          + chunk.document().getText();
    }).collect(java.util.stream.Collectors.joining("\n\n---\n\n"));
  }

  private List<Chunk> sorted(List<Chunk> chunks, List<Chunk> direct) {
    Map<Long, Integer> groups = new LinkedHashMap<>();
    Map<String, Integer> parents = new LinkedHashMap<>();
    for (Chunk hit : direct) {
      groups.putIfAbsent(hit.kbId(), groups.size());
      if (hit.parent() != null) parents.putIfAbsent(hit.kbId() + ":" + hit.parent(), parents.size());
    }
    return chunks.stream().sorted(Comparator.comparingInt((Chunk chunk) -> groups.get(chunk.kbId()))
        .thenComparingInt(chunk -> chunk.parent() == null ? Integer.MAX_VALUE
            : parents.getOrDefault(chunk.kbId() + ":" + chunk.parent(), Integer.MAX_VALUE - 1))
        .thenComparingInt(chunk -> chunk.parent() == null ? chunk.order() : chunk.index())).toList();
  }

  private Chunk chunk(Document document, long kbId, int order) {
    Map<String, Object> metadata = document.getMetadata();
    String parent = null;
    int index = -1;
    int total = 0;
    String path = "";
    try {
      if ("section-neighbor-v2".equals(metadata.get("chunk_strategy"))
          && metadata.get("parent_document_id") instanceof String rawParent
          && metadata.get("chunk_index") instanceof Integer rawIndex
          && metadata.get("total_chunks") instanceof Integer rawTotal
          && metadata.get("section_path") instanceof String rawPath
          && rawIndex >= 0 && rawIndex < rawTotal) {
        UUID.fromString(rawParent);
        parent = rawParent;
        index = rawIndex;
        total = rawTotal;
        path = rawPath;
      }
    } catch (IllegalArgumentException ignored) {
      parent = null;
    }
    Double score = document.getScore();
    if (score == null && metadata.get("distance") instanceof Number number) score = number.doubleValue();
    if (score != null && !Double.isFinite(score)) score = null;
    return new Chunk(document, kbId, parent, index, total, path, order, score);
  }

  private Long kbId(Document document) { return kbId(document.getMetadata()); }
  private Long kbId(Map<String, Object> metadata) {
    if (metadata == null) return null;
    try {
      long id = Long.parseLong(String.valueOf(metadata.get("kb_id")));
      return id > 0 ? id : null;
    } catch (NumberFormatException exception) { return null; }
  }
  private int count(String text) { return ENCODING.countTokensOrdinary(text); }
  private ContextResult empty(Status status) {
    return new ContextResult(status, "", List.of(), List.of(), List.of(), 0);
  }
}
