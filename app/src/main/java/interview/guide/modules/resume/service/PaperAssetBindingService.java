package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionOption;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.log.PaperFlowLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把确定性绑定结果挂到材料、题目和选项上。
 *
 * <p>本服务只消费 {@link PaperAssetBinding}，不读取模型返回的 assetRefs。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperAssetBindingService {

  private static final Set<String> OPTION_PLACEHOLDERS = Set.of(
      "[无法辨认]",
      "无法辨认",
      "[图片]"
  );

  private final PaperAssetService assetService;
  private final OptionRowSplitter optionRowSplitter;

  public BindingResult bind(
      Long paperId,
      PaperNode root,
      Map<String, PaperAssetBinding> bindings,
      List<PaperAssetEntity> assets
  ) {
    List<String> warnings = new ArrayList<>();
    PaperFlowLog.event("ASSET_BINDING_BEGIN paperId={}, root={}, bindings={}, assets={}",
        paperId, root == null ? null : root.id(), bindings == null ? 0 : bindings.size(),
        assets == null ? 0 : assets.size());
    if (root == null || bindings == null || bindings.isEmpty()) {
      warnMissingImageOptions(root, warnings);
      PaperFlowLog.event("ASSET_BINDING_END paperId={}, bound=0, warnings={}",
          paperId, warnings);
      return new BindingResult(root, List.copyOf(warnings));
    }

    Map<String, PaperAssetEntity> assetsByKey = new LinkedHashMap<>();
    for (PaperAssetEntity asset : assets == null ? List.<PaperAssetEntity>of() : assets) {
      if (asset != null && asset.getAssetKey() != null) {
        assetsByKey.put(asset.getAssetKey(), asset);
      }
    }
    List<NodeOwner> owners = new ArrayList<>();
    collectOwners(root, owners);

    Map<String, Set<String>> nodeAssetIds = new HashMap<>();
    Map<Integer, OptionPlan> optionPlans = new HashMap<>();
    Set<String> used = new LinkedHashSet<>();

    for (PaperAssetBinding binding : bindings.values().stream()
        .sorted(Comparator.comparing(PaperAssetBinding::anchorLine)
            .thenComparing(PaperAssetBinding::assetKey))
        .toList()) {
      if (!used.add(binding.assetKey())) {
        warnings.add("DUPLICATE_ASSET: 资源 " + binding.assetKey() + " 被重复绑定");
        PaperFlowLog.warn("ASSET_BINDING_DUPLICATE paperId={}, binding={}",
            paperId, binding);
        continue;
      }
      boolean optionAsset = isOptionAsset(binding);
      NodeOwner owner = optionAsset
          ? findQuestionOwner(binding, owners)
          : findAssetOwner(binding, owners);
      if (owner == null) {
        warnings.add("UNBOUND_ASSET: 资源 " + binding.assetKey()
            + "（第 " + binding.pageNo() + " 页，锚点行 " + binding.anchorLine()
            + "）无法唯一确定归属，已保留未绑定");
        PaperFlowLog.warn("ASSET_BINDING_UNBOUND paperId={}, binding={}, optionAsset={}",
            paperId, binding, optionAsset);
        continue;
      }
      if (optionAsset) {
        InterviewQuestionDTO question = owner.node().question();
        if (question == null || !isChoiceType(question.questionType())) {
          warnings.add("INVALID_OPTION_OWNER: 第 "
              + displayQuestionNo(owner.node()) + " 题不是选择题，拒绝资源 "
              + binding.assetKey());
          PaperFlowLog.warn("ASSET_BINDING_INVALID_OPTION_OWNER paperId={}, binding={}, "
                  + "question={}, questionType={}",
              paperId, binding, displayQuestionNo(owner.node()), question.questionType());
          continue;
        }
        if (hasTextOptions(question)) {
          warnings.add("INVALID_OPTION_OWNER: 第 "
              + displayQuestionNo(owner.node()) + " 题已有文字选项，拒绝图片选项资源 "
              + binding.assetKey());
          PaperFlowLog.warn("ASSET_BINDING_TEXT_OPTION_CONFLICT paperId={}, binding={}, "
                  + "question={}",
              paperId, binding, displayQuestionNo(owner.node()));
          continue;
        }
        PaperFlowLog.event("ASSET_BINDING_OPTION paperId={}, binding={}, question={}",
            paperId, binding, displayQuestionNo(owner.node()));
        optionPlans.computeIfAbsent(owner.node().questionIndex(), key -> new OptionPlan())
            .add(binding);
        continue;
      }
      PaperFlowLog.event("ASSET_BINDING_NODE paperId={}, binding={}, ownerId={}, ownerType={}, "
              + "question={}",
          paperId, binding, owner.node().id(), owner.node().type(), displayQuestionNo(owner.node()));
      nodeAssetIds.computeIfAbsent(owner.node().id(), key -> new LinkedHashSet<>())
          .add(binding.assetKey());
    }

    Map<Integer, List<QuestionOption>> resolvedOptions = new HashMap<>();
    for (Map.Entry<Integer, OptionPlan> entry : optionPlans.entrySet()) {
      PaperNode questionNode = findQuestion(root, entry.getKey());
      if (questionNode == null || questionNode.question() == null) {
        continue;
      }
      List<QuestionOption> options = resolveOptions(
          paperId, entry.getValue(), questionNode.question(), assetsByKey, warnings);
      if (options != null && options.size() >= 2) {
        resolvedOptions.put(entry.getKey(), options);
      }
    }

    PaperNode boundRoot = bindNode(root, nodeAssetIds, resolvedOptions);
    warnMissingImageOptions(boundRoot, warnings);
    PaperFlowLog.event("ASSET_BINDING_END paperId={}, boundAssets={}, optionQuestions={}, "
            + "warnings={}",
        paperId, nodeAssetIds.values().stream().mapToInt(Set::size).sum(),
        resolvedOptions.size(), warnings);
    return new BindingResult(boundRoot, List.copyOf(warnings));
  }

  private PaperNode bindNode(
      PaperNode node,
      Map<String, Set<String>> nodeAssetIds,
      Map<Integer, List<QuestionOption>> resolvedOptions
  ) {
    InterviewQuestionDTO question = node.question();
    if (node.questionIndex() != null) {
      List<QuestionOption> options = resolvedOptions.get(node.questionIndex());
      if (options != null) {
        question = question.withOptions(options);
      }
    }
    List<String> assetIds = nodeAssetIds.containsKey(node.id())
        ? List.copyOf(nodeAssetIds.get(node.id()))
        : node.meta() == null || node.meta().assetIds() == null
            ? List.of() : node.meta().assetIds();
    PaperNode.PaperMeta meta = node.meta() == null ? null : new PaperNode.PaperMeta(
        node.meta().fullScore(),
        node.meta().questionCount(),
        node.meta().durationMinutes(),
        assetIds,
        node.meta().startLine(),
        node.meta().endLine()
    );
    List<PaperNode> children = node.safeChildren().stream()
        .map(child -> bindNode(child, nodeAssetIds, resolvedOptions))
        .toList();
    return new PaperNode(node.id(), node.type(), node.order(), node.depth(), node.leaf(),
        node.title(), node.subtitle(), node.content(), meta, node.questionIndex(), question, children);
  }

  private List<QuestionOption> resolveOptions(
      Long paperId,
      OptionPlan plan,
      InterviewQuestionDTO question,
      Map<String, PaperAssetEntity> assetsByKey,
      List<String> warnings
  ) {
    PaperAssetBinding rowBinding = plan.row();
    if (rowBinding != null) {
      return splitRow(paperId, rowBinding, question, assetsByKey, warnings);
    }
    return itemOptions(plan.items(), assetsByKey, warnings);
  }

  private List<QuestionOption> splitRow(
      Long paperId,
      PaperAssetBinding binding,
      InterviewQuestionDTO question,
      Map<String, PaperAssetEntity> assetsByKey,
      List<String> warnings
  ) {
    PaperAssetEntity row = assetsByKey.get(binding.assetKey());
    if (row == null) {
      warnings.add("UNBOUND_ASSET: 图片选项资源 " + binding.assetKey() + " 不存在");
      return null;
    }
    int count = question.options() != null && question.options().size() >= 2
        ? question.options().size()
        : row.getOptionCount() != null ? row.getOptionCount() : 4;
    try {
      byte[] bytes = assetService.download(row);
      assetService.deleteOptionTiles(paperId, row.getAssetKey());
      OptionRowSplitter.SplitResult split = optionRowSplitter.split(bytes, count, row.getLayout());
      if (split == null || split.tiles().size() < 2) {
        warnings.add("OPTION_ROW_SPLIT_FAILED: 资源 " + binding.assetKey()
            + " 切分失败，保留图片选项占位");
        return null;
      }
      List<QuestionOption> options = new ArrayList<>();
      for (int i = 0; i < split.tiles().size(); i++) {
        String label = String.valueOf((char) ('A' + i));
        String key = PaperAssetKey.optionTile(row.getAssetKey(), label);
        assetService.saveTile(paperId, row.getPageNo() == null ? 1 : row.getPageNo(), key,
            row.getAssetKey(), split.tiles().get(i), 0, 0, row.getSourceAttemptId());
        options.add(new QuestionOption(label, null, key));
      }
      assetService.updateCutOffsets(row, split.cutOffsets().toString());
      return options;
    } catch (Exception e) {
      PaperFlowLog.error("ASSET_OPTION_ROW_SPLIT_FAILED paperId={}, asset={}",
          e, paperId, binding.assetKey());
      log.warn("图片选项切分失败: paperId={}, asset={}", paperId, binding.assetKey(), e);
      warnings.add("OPTION_ROW_SPLIT_FAILED: 资源 " + binding.assetKey()
          + " 切分失败，保留图片选项占位");
      return null;
    }
  }

  private List<QuestionOption> itemOptions(
      List<PaperAssetBinding> itemBindings,
      Map<String, PaperAssetEntity> assetsByKey,
      List<String> warnings
  ) {
    List<PaperAssetEntity> items = itemBindings.stream()
        .map(binding -> assetsByKey.get(binding.assetKey()))
        .filter(java.util.Objects::nonNull)
        .sorted(Comparator.comparing(PaperAssetEntity::getAssetKey))
        .toList();
    if (items.size() < 2) {
      if (!itemBindings.isEmpty()) {
        warnings.add("OPTION_ITEM_MISSING: 图片选项不足两个，无法组成选项");
      }
      return null;
    }
    return toOptions(items);
  }

  private List<QuestionOption> toOptions(List<PaperAssetEntity> items) {
    List<QuestionOption> options = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      options.add(new QuestionOption(
          String.valueOf((char) ('A' + i)), null, items.get(i).getAssetKey()));
    }
    return options;
  }

  private NodeOwner findQuestionOwner(PaperAssetBinding binding, List<NodeOwner> owners) {
    List<NodeOwner> candidates = owners.stream()
        .filter(owner -> PaperNode.TYPE_QUESTION.equals(owner.node().type()))
        .filter(owner -> owner.contains(binding.anchorLine()))
        .toList();
    return uniqueNarrowest(candidates);
  }

  private NodeOwner findAssetOwner(PaperAssetBinding binding, List<NodeOwner> owners) {
    List<NodeOwner> candidates = owners.stream()
        .filter(owner -> PaperNode.TYPE_MATERIAL.equals(owner.node().type())
            || PaperNode.TYPE_QUESTION.equals(owner.node().type()))
        .filter(owner -> owner.contains(binding.anchorLine()))
        .filter(owner -> binding.role() == null
            || !"MATERIAL".equalsIgnoreCase(binding.role())
            || PaperNode.TYPE_MATERIAL.equals(owner.node().type()))
        .toList();
    return uniqueNarrowest(candidates);
  }

  private NodeOwner uniqueNarrowest(List<NodeOwner> candidates) {
    if (candidates.isEmpty()) {
      return null;
    }
    List<NodeOwner> sorted = candidates.stream()
        .sorted(Comparator.comparingInt(NodeOwner::length)
            .thenComparing(owner -> owner.node().id()))
        .toList();
    if (sorted.size() > 1 && sorted.get(0).length() == sorted.get(1).length()) {
      return null;
    }
    return sorted.getFirst();
  }

  private void collectOwners(PaperNode node, List<NodeOwner> owners) {
    if (node == null) {
      return;
    }
    if (node.meta() != null && node.meta().startLine() != null
        && node.meta().endLine() != null
        && (PaperNode.TYPE_MATERIAL.equals(node.type())
            || PaperNode.TYPE_QUESTION.equals(node.type()))) {
      owners.add(new NodeOwner(node, node.meta().startLine(), node.meta().endLine()));
    }
    for (PaperNode child : node.safeChildren()) {
      collectOwners(child, owners);
    }
  }

  private PaperNode findQuestion(PaperNode node, int questionIndex) {
    if (node.questionIndex() != null && node.questionIndex() == questionIndex) {
      return node;
    }
    for (PaperNode child : node.safeChildren()) {
      PaperNode found = findQuestion(child, questionIndex);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private void warnMissingImageOptions(PaperNode node, List<String> warnings) {
    if (node == null) {
      return;
    }
    InterviewQuestionDTO question = node.question();
    if (question != null && isChoiceType(question.questionType())
        && !hasUsableOptions(question)) {
      warnings.add("IMAGE_OPTION_MISSING: 第 " + displayQuestionNo(node)
          + " 题没有可用图片选项，已保留空占位");
    }
    for (PaperNode child : node.safeChildren()) {
      warnMissingImageOptions(child, warnings);
    }
  }

  private boolean isOptionAsset(PaperAssetBinding binding) {
    return "OPTION_ROW".equalsIgnoreCase(binding.kind())
        || "OPTION_ITEM".equalsIgnoreCase(binding.kind());
  }

  private boolean isChoiceType(String questionType) {
    return InterviewQuestionDTO.TYPE_SINGLE_CHOICE.equals(questionType)
        || InterviewQuestionDTO.TYPE_MULTI_CHOICE.equals(questionType);
  }

  private boolean hasTextOptions(InterviewQuestionDTO question) {
    return question.options() != null && question.options().stream()
        .anyMatch(option -> !isPlaceholderOptionText(option.text()));
  }

  private boolean hasUsableOptions(InterviewQuestionDTO question) {
    return question.options() != null && question.options().stream()
        .anyMatch(option -> option.assetId() != null && !option.assetId().isBlank()
            || !isPlaceholderOptionText(option.text()));
  }

  private boolean isPlaceholderOptionText(String text) {
    if (text == null || text.isBlank()) {
      return true;
    }
    return OPTION_PLACEHOLDERS.contains(text.strip());
  }

  private int displayQuestionNo(PaperNode node) {
    return node.questionIndex() == null ? -1 : node.questionIndex() + 1;
  }

  public record BindingResult(
      PaperNode root,
      List<String> warnings
  ) {
    public BindingResult {
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  private record NodeOwner(PaperNode node, int startLine, int endLine) {
    boolean contains(int line) {
      return line >= startLine && line <= endLine;
    }

    int length() {
      return endLine - startLine;
    }
  }

  private static final class OptionPlan {
    private PaperAssetBinding row;
    private final List<PaperAssetBinding> items = new ArrayList<>();

    void add(PaperAssetBinding binding) {
      if ("OPTION_ROW".equalsIgnoreCase(binding.kind())) {
        if (row == null) {
          row = binding;
        }
      } else {
        items.add(binding);
      }
    }

    PaperAssetBinding row() {
      return row;
    }

    List<PaperAssetBinding> items() {
      return items;
    }
  }
}
