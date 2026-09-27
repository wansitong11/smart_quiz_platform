package interview.guide.modules.interview.model;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 试卷层级树：存储层唯一的题目数据源。
 * <p>
 * 扁平题目列表由 {@link #flattenQuestions()} 运行时派生（供批改、缓存、历史去重），
 * 层级与材料原文由树本身承载（供作答页、报告页、PDF 与批改上下文使用）。
 * </p>
 */
public record PaperTree(int version, PaperNode root) {

    public static final int CURRENT_VERSION = 1;

    /**
     * 解析存储中的 JSON；内容非法时抛出业务异常。
     */
    public static PaperTree parse(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "试卷结构为空");
        }
        try {
            PaperTree tree = objectMapper.readValue(json, PaperTree.class);
            if (tree == null || tree.root() == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "试卷结构缺少根节点");
            }
            return tree;
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "试卷结构解析失败");
        }
    }

    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "试卷结构序列化失败");
        }
    }

    /**
     * 用扁平题目列表构造最小树（卷 → 一个大题 → 全部题目），
     * 供资料出题、Skill 出题等没有层级信息的场景使用。
     */
    public static PaperTree fromFlatQuestions(List<InterviewQuestionDTO> questions, String title) {
        List<PaperNode> questionNodes = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            questionNodes.add(PaperNode.ofQuestion(i + 1, questions.get(i)));
        }
        PaperNode section = PaperNode.section("n1", 1, title != null ? title : "全部题目", null, questionNodes);
        PaperNode root = PaperNode.paper("n0", title, null, List.of(section));
        return recalculate(new PaperTree(CURRENT_VERSION, root));
    }

    /**
     * 规范化：重排同级 order、回写 depth 与 leaf、按子节点汇总 meta。
     * 模型的"建议值"在这里被覆盖，避免自相矛盾。
     */
    public static PaperTree recalculate(PaperTree tree) {
        PaperNode root = normalize(tree.root(), 0, true);
        return new PaperTree(CURRENT_VERSION, root);
    }

    private static PaperNode normalize(PaperNode node, int depth, boolean isRoot) {
        List<PaperNode> children = new ArrayList<>();
        List<PaperNode> raw = new ArrayList<>(node.safeChildren());
        raw.sort(Comparator.comparingInt(PaperNode::order));
        for (int i = 0; i < raw.size(); i++) {
            PaperNode child = raw.get(i);
            PaperNode normalizedChild = normalize(child, depth + 1, false);
            children.add(new PaperNode(normalizedChild.id(), normalizedChild.type(), i + 1,
                normalizedChild.depth(), normalizedChild.leaf(), normalizedChild.title(),
                normalizedChild.subtitle(), normalizedChild.content(), normalizedChild.meta(),
                normalizedChild.questionIndex(), normalizedChild.question(), normalizedChild.children()));
        }
        boolean leaf = children.isEmpty();
        int ownScore = node.question() != null && node.question().fullScore() != null
            ? node.question().fullScore() : 0;
        int totalScore = ownScore + children.stream()
            .map(child -> child.meta() != null && child.meta().fullScore() != null ? child.meta().fullScore() : 0)
            .mapToInt(Integer::intValue)
            .sum();
        int totalQuestions = (node.isQuestion() ? 1 : 0) + children.stream()
            .map(child -> child.meta() != null && child.meta().questionCount() != null ? child.meta().questionCount() : 0)
            .mapToInt(Integer::intValue)
            .sum();
        PaperNode.PaperMeta meta = new PaperNode.PaperMeta(totalScore, totalQuestions,
            node.meta() != null ? node.meta().durationMinutes() : null);
        return new PaperNode(node.id(), node.type(), isRoot ? 0 : node.order(), depth, leaf,
            node.title(), node.subtitle(), node.content(), meta, node.questionIndex(),
            node.question(), children);
    }

    public List<PaperNode> questionNodes() {
        return root.collectQuestions().stream()
            .sorted(Comparator.comparingInt(node -> node.questionIndex() == null ? 0 : node.questionIndex()))
            .toList();
    }

    /**
     * 用运行时的题目列表替换树中的题目载荷（按 questionIndex 对齐）。
     * <p>
     * 作答、批改过程中答案与分数会写回题目 DTO，这里把最新载荷同步进树，
     * 保证树始终是唯一数据源，不需要再单独维护一份扁平题目 JSON。
     * 列表中缺失的题目保留原载荷。
     * </p>
     */
    public PaperTree withQuestions(List<InterviewQuestionDTO> questions) {
        java.util.Map<Integer, InterviewQuestionDTO> byIndex = new java.util.HashMap<>();
        if (questions != null) {
            for (InterviewQuestionDTO question : questions) {
                if (question != null) {
                    byIndex.put(question.questionIndex(), question);
                }
            }
        }
        return new PaperTree(version, replaceQuestions(root, byIndex));
    }

    private static PaperNode replaceQuestions(PaperNode node,
                                              java.util.Map<Integer, InterviewQuestionDTO> byIndex) {
        InterviewQuestionDTO question = node.question();
        PaperNode.PaperMeta meta = node.meta();
        if (node.isQuestion() && node.questionIndex() != null) {
            InterviewQuestionDTO updated = byIndex.get(node.questionIndex());
            if (updated != null) {
                question = updated;
                meta = new PaperNode.PaperMeta(
                    updated.fullScore() != null ? updated.fullScore()
                        : (meta != null ? meta.fullScore() : null),
                    meta != null ? meta.questionCount() : 1,
                    meta != null ? meta.durationMinutes() : null
                );
            }
        }
        List<PaperNode> children = node.safeChildren().stream()
            .map(child -> replaceQuestions(child, byIndex))
            .toList();
        return new PaperNode(node.id(), node.type(), node.order(), node.depth(), node.leaf(),
            node.title(), node.subtitle(), node.content(), meta, node.questionIndex(), question, children);
    }

    /**
     * 扁平题目列表：批改、缓存、历史去重使用的运行时视图。
     * <p>
     * 返回可变副本——批改与作答链路会在列表上写回答案/分数（与树解耦），
     * 需要回写树时再走 {@link #withQuestions(List)}。
     * </p>
     */
    public List<InterviewQuestionDTO> flattenQuestions() {
        return new ArrayList<>(questionNodes().stream()
            .map(PaperNode::question)
            .filter(java.util.Objects::nonNull)
            .toList());
    }

    public Optional<PaperNode> questionNode(int questionIndex) {
        return questionNodes().stream()
            .filter(node -> node.questionIndex() != null && node.questionIndex() == questionIndex)
            .findFirst();
    }

    /**
     * 该题到根的路径，从根往叶子排列，末位是该题自身。
     * 中间节点即该题的各级 SECTION / 祖先 MATERIAL。
     */
    public List<PaperNode> pathTo(int questionIndex) {
        List<PaperNode> path = new ArrayList<>();
        if (findPath(root, questionIndex, path)) {
            return path;
        }
        return List.of();
    }

    private boolean findPath(PaperNode node, int questionIndex, List<PaperNode> path) {
        if (node.isQuestion() && node.questionIndex() != null && node.questionIndex() == questionIndex) {
            return true;
        }
        for (PaperNode child : node.safeChildren()) {
            path.add(child);
            if (findPath(child, questionIndex, path)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    /**
     * 该题的批改上下文：路径上的小节标题与材料原文。
     */
    public String contextOf(int questionIndex) {
        StringBuilder sb = new StringBuilder();
        for (PaperNode node : pathTo(questionIndex)) {
            if (node.isSection() && node.title() != null && !node.title().isBlank()) {
                sb.append("【小节】").append(node.title());
                if (node.subtitle() != null && !node.subtitle().isBlank()) {
                    sb.append(' ').append(node.subtitle());
                }
                sb.append('\n');
            }
        }
        for (PaperNode material : materialsOf(questionIndex)) {
            if (material.title() != null && !material.title().isBlank()) {
                sb.append("【材料】").append(material.title()).append('\n');
            } else {
                sb.append("【材料】\n");
            }
            sb.append(material.content().trim()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 该题所属的最近 SECTION 节点。
     */
    public Optional<PaperNode> nearestSection(int questionIndex) {
        List<PaperNode> path = pathTo(questionIndex);
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i).isSection()) {
                return Optional.of(path.get(i));
            }
        }
        return Optional.empty();
    }

    /**
     * 该题的作答材料：既包括祖先链上的 MATERIAL，也包括每一层祖先中排在
     * 该题所在分支之前的 MATERIAL（材料通常是题目的同级节点，不在路径上）。
     * 排在该题之后的材料不算，避免把下一节的阅读材料混进本题上下文。
     */
    public List<PaperNode> materialsOf(int questionIndex) {
        List<PaperNode> materials = new ArrayList<>();
        collectMaterials(root, questionIndex, materials);
        return materials;
    }

    private boolean collectMaterials(PaperNode node, int questionIndex, List<PaperNode> materials) {
        if (node.isQuestion() && node.questionIndex() != null && node.questionIndex() == questionIndex) {
            return true;
        }
        int mark = materials.size();
        for (PaperNode child : node.safeChildren()) {
            if (child.isMaterial() && child.content() != null && !child.content().isBlank()) {
                materials.add(child);
            }
            if (collectMaterials(child, questionIndex, materials)) {
                return true;
            }
        }
        // 目标不在这棵子树里，回滚本层收集到的材料
        while (materials.size() > mark) {
            materials.remove(materials.size() - 1);
        }
        return false;
    }

    /**
     * 该题所属小节的完整标题路径，如「一、现代文阅读 / （一）现代文阅读 I」。
     * 无层级时返回空串。
     */
    public String sectionPathOf(int questionIndex) {
        return pathTo(questionIndex).stream()
            .filter(node -> PaperNode.TYPE_SECTION.equals(node.type()))
            .map(PaperTree::renderSectionTitle)
            .filter(title -> title != null && !title.isBlank())
            .collect(java.util.stream.Collectors.joining(" / "));
    }

    private static String renderSectionTitle(PaperNode node) {
        if (node.title() == null || node.title().isBlank()) {
            return node.subtitle();
        }
        if (node.subtitle() == null || node.subtitle().isBlank()) {
            return node.title();
        }
        return node.title() + " " + node.subtitle();
    }
}
