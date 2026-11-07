package interview.guide.modules.resume.service;

import interview.guide.modules.interview.model.AnswerKeyDTO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 试卷内部结构快照：行号块树 + 答案键。
 * 只供后端判分和内部维护使用，不直接返回学生端。
 */
public record PaperStructureSnapshot(
    int schemaVersion,
    int revision,
    BlockTreeDTO blocks,
    Map<Integer, AnswerKeyDTO> answerKeys,
    Map<Integer, String> questionFingerprints
) {

  public static final int CURRENT_SCHEMA_VERSION = 6;

  public PaperStructureSnapshot {
    answerKeys = answerKeys == null ? Map.of() : Map.copyOf(answerKeys);
    questionFingerprints = questionFingerprints == null
        ? Map.of() : Map.copyOf(questionFingerprints);
  }

  /**
   * 兼容旧快照：指纹缺失时按"未知"处理，复用逻辑会保守保留已有答案。
   */
  public PaperStructureSnapshot(
      int schemaVersion,
      int revision,
      BlockTreeDTO blocks,
      Map<Integer, AnswerKeyDTO> answerKeys
  ) {
    this(schemaVersion, revision, blocks, answerKeys, Map.of());
  }

  public static PaperStructureSnapshot initial(BlockTreeDTO blocks) {
    return new PaperStructureSnapshot(CURRENT_SCHEMA_VERSION, 0, blocks, Map.of(), Map.of());
  }

  public PaperStructureSnapshot withAnswerKeys(Map<Integer, AnswerKeyDTO> keys) {
    return new PaperStructureSnapshot(
        CURRENT_SCHEMA_VERSION, revision + 1, blocks, new LinkedHashMap<>(keys),
        questionFingerprints);
  }

  public PaperStructureSnapshot withAnswerKeysAndFingerprints(
      Map<Integer, AnswerKeyDTO> keys,
      Map<Integer, String> fingerprints
  ) {
    return new PaperStructureSnapshot(
        CURRENT_SCHEMA_VERSION, revision + 1, blocks,
        keys == null ? Map.of() : new LinkedHashMap<>(keys),
        fingerprints == null ? Map.of() : new LinkedHashMap<>(fingerprints));
  }

  public PaperStructureSnapshot withBlocks(BlockTreeDTO normalizedBlocks) {
    return new PaperStructureSnapshot(
        schemaVersion, revision, normalizedBlocks, answerKeys, questionFingerprints);
  }

  public PaperStructureSnapshot withoutAnswerKeys() {
    return new PaperStructureSnapshot(
        CURRENT_SCHEMA_VERSION, revision, blocks, Map.of(), Map.of());
  }
}
