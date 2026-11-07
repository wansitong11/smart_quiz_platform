package interview.guide.modules.quiz.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.quiz.model.WrongQuestionSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 错题身份指纹：同一道题跨会话聚合，答案/解析/分值不参与。
 */
public final class WrongQuestionFingerprint {

  private WrongQuestionFingerprint() {
  }

  public static String of(String skillId, InterviewQuestionDTO question,
                          List<WrongQuestionSnapshot.MaterialBlock> materials) {
    return of(skillId, question, null, null, materials);
  }

  public static String of(String skillId, InterviewQuestionDTO question,
                          WrongQuestionSnapshot snapshot) {
    if (snapshot == null) {
      return of(skillId, question, List.of());
    }
    return of(skillId, question, snapshot.sectionTitle(), snapshot.sectionSubtitle(),
        snapshot.materials());
  }

  private static String of(String skillId, InterviewQuestionDTO question,
                           String sectionTitle, String sectionSubtitle,
                           List<WrongQuestionSnapshot.MaterialBlock> materials) {
    if (question == null) {
      throw new IllegalArgumentException("question must not be null");
    }
    boolean readingQuestion = InterviewQuestionDTO.TYPE_READING.equals(question.questionType());
    String materialText = readingQuestion
        ? materialContext(sectionTitle, sectionSubtitle, materials) : "";
    String optionText = question.options() == null ? "" : question.options().stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(option -> nullToEmpty(option.key())))
        .map(option -> nullToEmpty(option.key()) + ":" + normalize(option.text())
            + ":" + nullToEmpty(option.assetId()))
        .collect(Collectors.joining("|"));
    String raw = nullToEmpty(skillId)
        + "|" + nullToEmpty(question.questionType())
        + "|" + normalize(question.question())
        + "|" + optionText
        + (materialText.isBlank() ? "" : "|" + materialText);
    return sha256(raw);
  }

  private static String materialContext(String sectionTitle, String sectionSubtitle,
      List<WrongQuestionSnapshot.MaterialBlock> materials) {
    String materialText = materials == null ? "" : materials.stream()
        .filter(Objects::nonNull)
        .map(material -> normalize(material.title()) + "\n" + normalize(material.content()))
        .filter(text -> !text.isBlank())
        .collect(Collectors.joining("\n"));
    return (normalize(sectionTitle) + "\n" + normalize(sectionSubtitle) + "\n" + materialText)
        .trim();
  }

  static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().replaceAll("\\s+", "");
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
