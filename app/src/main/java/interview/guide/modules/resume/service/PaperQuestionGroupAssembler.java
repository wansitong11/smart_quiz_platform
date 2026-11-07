package interview.guide.modules.resume.service;

import interview.guide.modules.resume.service.BlockTreeDTO.BlockNodeDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把规范化的块节点组装成材料题组计划。
 *
 * <p>规则只依赖行号和节点类型，不依赖模型输出的父子关系：
 * 连续 MATERIAL 形成一个材料包，其后的连续 QUESTION 共享这个材料包；
 * 遇到新的 MATERIAL、SECTION/PART 或 ANSWER_KEY 时关闭当前题组。</p>
 */
@Component
public class PaperQuestionGroupAssembler {

  public static final int HIERARCHY_SCHEMA_VERSION = 1;

  public GroupAssembly assemble(List<BlockNodeDTO> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return new GroupAssembly(List.of(), List.of());
    }
    List<BlockNodeDTO> sorted = nodes.stream()
        .filter(node -> node != null)
        .sorted(Comparator.comparingInt(BlockNodeDTO::startLine)
            .thenComparingInt(BlockNodeDTO::endLine))
        .toList();

    List<QuestionGroupPlan> plans = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<BlockNodeDTO> materials = new ArrayList<>();
    List<BlockNodeDTO> questions = new ArrayList<>();

    for (BlockNodeDTO node : sorted) {
      String type = normalizeType(node.type());
      if (isBoundary(type)) {
        flush(plans, warnings, materials, questions);
        continue;
      }
      if (isMaterial(type)) {
        if (!materials.isEmpty() && !questions.isEmpty()) {
          flush(plans, warnings, materials, questions);
        } else if (materials.isEmpty() && !questions.isEmpty()) {
          // 前面的独立题不属于后续材料，不能把它们反向收进新材料组。
          questions.clear();
        }
        materials.add(node);
        continue;
      }
      if (isQuestion(type)) {
        questions.add(node);
      }
      // EXCERPT / INSTRUCTION 不创建节点，也不切断材料包。
    }
    flush(plans, warnings, materials, questions);
    return new GroupAssembly(List.copyOf(plans), List.copyOf(warnings));
  }

  private void flush(
      List<QuestionGroupPlan> plans,
      List<String> warnings,
      List<BlockNodeDTO> materials,
      List<BlockNodeDTO> questions
  ) {
    if (!materials.isEmpty() && !questions.isEmpty()) {
      int start = materials.stream().mapToInt(BlockNodeDTO::startLine).min().orElse(1);
      int end = questions.stream().mapToInt(BlockNodeDTO::endLine).max()
          .orElseGet(() -> materials.stream().mapToInt(BlockNodeDTO::endLine).max().orElse(start));
      plans.add(new QuestionGroupPlan(
          "g" + (plans.size() + 1),
          start,
          end,
          List.copyOf(materials),
          List.copyOf(questions)
      ));
    } else if (!materials.isEmpty()) {
      int start = materials.getFirst().startLine();
      int end = materials.getLast().endLine();
      warnings.add("ORPHAN_MATERIAL: 第 " + start + "–" + end
          + " 行的材料没有后续题目，未生成空题组");
    }
    materials.clear();
    questions.clear();
  }

  private boolean isBoundary(String type) {
    return "SECTION".equals(type) || "PART".equals(type) || "ANSWER_KEY".equals(type);
  }

  private boolean isMaterial(String type) {
    return "MATERIAL".equals(type);
  }

  private boolean isQuestion(String type) {
    return "QUESTION".equals(type)
        || "SUB_QUESTION".equals(type)
        || "BLANK".equals(type);
  }

  private String normalizeType(String type) {
    return type == null ? "" : type.trim().toUpperCase();
  }

  public record GroupAssembly(
      List<QuestionGroupPlan> plans,
      List<String> warnings
  ) {
    public GroupAssembly {
      plans = plans == null ? List.of() : List.copyOf(plans);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }
}
