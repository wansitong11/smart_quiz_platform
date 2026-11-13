package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.RagStreamEvent.Source;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.BudgetSnapshot;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.EvidenceBlock;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.EvidenceUnit;
import interview.guide.modules.knowledgebase.service.RagContextAssembler.PreparedContext;
import interview.guide.modules.knowledgebase.service.scope.ResolvedScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

public final class RagEvidenceSession {
  private final Long actorUserId;
  private final Long effectiveOwnerId;
  private final boolean adminAll;
  private final ResolvedScope originalScope;
  private final AiCallContext callContext;
  private final String effectiveQuestion;
  private final SecurityContext securityContext;
  private final Map<String, PreparedContext> cache = new LinkedHashMap<>();
  private final Map<String, AcceptedUnit> accepted = new LinkedHashMap<>();
  private final Map<String, EvidenceUnit> acceptedGraph = new LinkedHashMap<>();
  private final Map<String, EvidenceBlock> provided = new LinkedHashMap<>();
  private final Set<Long> counted = new LinkedHashSet<>();
  private int sequence;
  private int batches;
  private boolean failed;

  public record AcceptedUnit(String id, List<EvidenceBlock> blocks, String fingerprint) { }
  public record CitationResult(List<String> unitIds, boolean invalid) { }
  public record CitationOutput(List<Source> sources, String warning) { }

  public RagEvidenceSession(Long actorUserId, Long effectiveOwnerId, boolean adminAll,
      ResolvedScope originalScope, AiCallContext callContext, String effectiveQuestion) {
    if (actorUserId == null || !adminAll && effectiveOwnerId == null || originalScope == null
        || callContext == null || effectiveQuestion == null) {
      throw failure("PROVIDER：缺少问答授权或计费上下文");
    }
    this.actorUserId = actorUserId;
    this.effectiveOwnerId = effectiveOwnerId;
    this.adminAll = adminAll;
    this.originalScope = originalScope;
    this.callContext = callContext;
    this.effectiveQuestion = effectiveQuestion;
    this.securityContext = SecurityContextHolder.createEmptyContext();
    this.securityContext.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
  }

  public Long effectiveOwnerId() { return effectiveOwnerId; }
  public String effectiveQuestion() { return effectiveQuestion; }
  public AiCallContext callContext() { return callContext; }
  public SecurityContext securityContext() { return securityContext; }
  public ResolvedScope originalScope() { return originalScope; }
  public synchronized String allocateUnitId() { return "E" + ++sequence; }
  public synchronized boolean failed() { return failed; }
  public synchronized void markFailed() { failed = true; }
  public synchronized Map<String, AcceptedUnit> acceptedUnits() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(accepted));
  }
  public synchronized List<Long> newlyCounted(List<Long> ids) {
    return ids.stream().filter(counted::add).toList();
  }

  public synchronized BudgetSnapshot budgetSnapshot() {
    return new BudgetSnapshot(List.copyOf(provided.values()), List.copyOf(acceptedGraph.values()));
  }

  public synchronized void reauthorize(Set<Long> allowed) {
    cache.entrySet().removeIf(entry -> entry.getValue().blocks().stream()
        .anyMatch(block -> !allowed.contains(block.kbId())));
    accepted.entrySet().removeIf(entry -> entry.getValue().blocks().stream()
        .anyMatch(block -> !allowed.contains(block.kbId())));
    acceptedGraph.keySet().retainAll(accepted.keySet());
    provided.entrySet().removeIf(entry -> !allowed.contains(entry.getValue().kbId()));
  }

  public synchronized PreparedContext reviewCached(String key, int maxBatches, int tokenLimit,
      Supplier<PreparedContext> operation, java.util.function.Consumer<String> resultMetric) {
    if (failed) throw failure("PROVIDER：本轮资料复核已中断");
    PreparedContext existing = cache.get(key);
    if (existing != null) {
      resultMetric.accept("cache_hit");
      return new PreparedContext(existing.status(), existing.blocks(), existing.units(),
          RagContextAssembler.renderEvidence(List.of(), existing.units()), existing.tokens());
    }
    if (batches >= maxBatches) {
      failed = true;
      resultMetric.accept("limit");
      throw failure("BATCH_LIMIT：本轮资料复核次数已达上限");
    }
    batches++;
    try {
      PreparedContext kept = operation.get();
      Map<String, String> aliases = new LinkedHashMap<>();
      List<EvidenceBlock> blocks = new ArrayList<>();
      List<EvidenceBlock> newBlocks = new ArrayList<>();
      for (EvidenceBlock block : kept.blocks()) {
        String identity = block.document().getId();
        EvidenceBlock old = provided.get(identity);
        if (old != null && !old.document().getText().equals(block.document().getText())) {
          throw failure("INPUT_LIMIT：同轮资料版本发生变化，请重试");
        }
        EvidenceBlock relabeled = old == null ? new EvidenceBlock("B" + batches + block.id(),
            block.document(), block.kbId(), block.title(), block.path(), block.parent(),
            block.index(), block.direct(), block.order()) : new EvidenceBlock(old.id(), block.document(),
                block.kbId(), block.title(), block.path(), block.parent(), block.index(), block.direct(), block.order());
        aliases.put(block.id(), relabeled.id());
        blocks.add(relabeled);
        if (old == null) newBlocks.add(relabeled);
      }
      List<EvidenceUnit> units = kept.units().stream().map(unit -> new EvidenceUnit(allocateUnitId(),
          aliases.get(unit.anchorBlockId()), unit.blockIds().stream().map(aliases::get).toList())).toList();
      if (RagContextAssembler.tokenCount(RagContextAssembler.renderCombined(budgetSnapshot(), blocks, units))
          > tokenLimit) throw failure("INPUT_LIMIT：累计资料上下文超限");
      for (EvidenceUnit unit : units) {
        List<EvidenceBlock> window = blocks.stream().filter(block -> unit.blockIds().contains(block.id())).toList();
        accepted.put(unit.id(), new AcceptedUnit(unit.id(), window, key));
        acceptedGraph.put(unit.id(), unit);
      }
      blocks.forEach(block -> provided.putIfAbsent(block.document().getId(), block));
      PreparedContext result = new PreparedContext(kept.status(), List.copyOf(blocks), units,
          RagContextAssembler.renderEvidence(newBlocks, units),
          RagContextAssembler.tokenCount(RagContextAssembler.renderEvidence(blocks, units)));
      cache.put(key, result);
      resultMetric.accept(units.isEmpty() ? "empty" : "success");
      return result;
    } catch (RuntimeException exception) {
      failed = true;
      resultMetric.accept("error");
      throw exception;
    }
  }

  public synchronized CitationOutput citations(String answer) {
    if (failed) return new CitationOutput(List.of(), "");
    CitationResult scan = scanCitations(answer, accepted.keySet());
    Map<Long, LinkedHashSet<String>> segments = new LinkedHashMap<>();
    Map<Long, String> names = new LinkedHashMap<>();
    for (String id : scan.unitIds()) {
      for (EvidenceBlock block : accepted.get(id).blocks()) {
        segments.computeIfAbsent(block.kbId(), unused -> new LinkedHashSet<>()).add(block.document().getId());
        names.putIfAbsent(block.kbId(), block.title());
      }
    }
    List<Source> sources = segments.entrySet().stream()
        .map(entry -> new Source(entry.getKey(), names.get(entry.getKey()), entry.getValue().size())).toList();
    String warning = accepted.isEmpty() ? "" : sources.isEmpty()
        ? "\n\n提示：本次回答未包含有效资料引用，未展示出处。"
        : scan.invalid() ? "\n\n提示：部分引用编号无效，仅展示可核验的出处。" : "";
    return new CitationOutput(sources, warning);
  }

  public static CitationResult scanCitations(String text, Set<String> valid) {
    LinkedHashSet<String> found = new LinkedHashSet<>();
    boolean invalid = false;
    char fence = 0;
    int fenceLength = 0;
    int inlineLength = 0;
    Pattern opener = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    for (String line : text.split("\\n", -1)) {
      Matcher match = opener.matcher(line);
      if (fence != 0) {
        if (match.matches() && match.group(1).charAt(0) == fence
            && match.group(1).length() >= fenceLength && match.group(2).isBlank()) fence = 0;
        continue;
      }
      if (inlineLength == 0 && match.matches()) {
        fence = match.group(1).charAt(0);
        fenceLength = match.group(1).length();
        continue;
      }
      for (int offset = 0; offset < line.length();) {
        if (line.charAt(offset) == '`') {
          int end = offset;
          while (end < line.length() && line.charAt(end) == '`') end++;
          int length = end - offset;
          if (inlineLength == 0) inlineLength = length;
          else if (inlineLength == length) inlineLength = 0;
          offset = end;
        } else if (inlineLength == 0 && line.startsWith("〔E", offset)) {
          int end = line.indexOf('〕', offset);
          String marker = end < 0 ? line.substring(offset + 1) : line.substring(offset + 1, end);
          if (marker.matches("E[1-9][0-9]{0,5}") && valid.contains(marker)) found.add(marker);
          else invalid = true;
          offset = end < 0 ? line.length() : end + 1;
        } else offset++;
      }
    }
    return new CitationResult(List.copyOf(found), invalid);
  }

  public String authorizationKey() {
    return actorUserId + ":" + effectiveOwnerId + ":" + adminAll + ":" + originalScope;
  }

  public static String hash(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public static BusinessException failure(String reason) {
    return new BusinessException(ErrorCode.RAG_EVIDENCE_REVIEW_FAILED, "资料相关性复核失败：" + reason);
  }
}
