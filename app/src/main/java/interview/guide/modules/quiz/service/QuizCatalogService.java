package interview.guide.modules.quiz.service;

import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.quiz.model.QuizCatalogDTO;
import interview.guide.modules.quiz.model.QuizSubjectSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测验目录聚合：系统考试 + classpath 预设科目 + 当前用户自定义科目。
 */
@Service
@RequiredArgsConstructor
public class QuizCatalogService {

  private final InterviewSkillService skillService;
  private final QuizSubjectService subjectService;

  public QuizCatalogDTO catalog() {
    List<InterviewSkillService.SubjectDefinitionDTO> systemSubjects =
        skillService.listSubjectDefinitions();
    List<QuizCatalogDTO.Subject> customSubjects = subjectService.listMine();

    Map<String, List<QuizCatalogDTO.Subject>> byExam = new LinkedHashMap<>();
    for (InterviewSkillService.SubjectDefinitionDTO definition : systemSubjects) {
      byExam.computeIfAbsent(definition.examCode(), key -> new ArrayList<>())
          .add(toSubject(definition));
    }
    for (QuizCatalogDTO.Subject subject : customSubjects) {
      byExam.computeIfAbsent(subject.examCode(), key -> new ArrayList<>())
          .add(subject);
    }

    List<QuizCatalogDTO.Exam> exams = skillService.listExams().stream()
        .sorted(Comparator.comparingInt(InterviewSkillService.ExamDTO::sortOrder))
        .map(exam -> new QuizCatalogDTO.Exam(
            exam.code(),
            exam.name(),
            exam.description(),
            toDisplay(exam),
            exam.customBucket(),
            exam.sortOrder(),
            byExam.getOrDefault(exam.code(), List.of())))
        .toList();
    return new QuizCatalogDTO(exams);
  }

  private QuizCatalogDTO.Subject toSubject(InterviewSkillService.SubjectDefinitionDTO definition) {
    List<QuizCatalogDTO.KnowledgePoint> points = definition.categories().stream()
        .filter(category -> category.key() != null && category.label() != null)
        .map(category -> new QuizCatalogDTO.KnowledgePoint(category.key(), category.label()))
        .toList();
    List<QuizCatalogDTO.QuestionType> types = definition.questionTypes().stream()
        .map(type -> new QuizCatalogDTO.QuestionType(
            type.type(), type.defaultScore(), type.weight()))
        .toList();
    return new QuizCatalogDTO.Subject(
        definition.id(),
        definition.name(),
        definition.description(),
        definition.examCode(),
        false,
        points,
        types,
        definition.suggestedMinutes(),
        null,
        null);
  }

  /**
   * 组卷用：按科目代码解析为统一描述，系统预设优先，其次本人的自定义科目。
   */
  public QuizSubjectSpec resolveSubject(String subjectCode) {
    InterviewSkillService.SubjectDefinitionDTO preset =
        skillService.getSubjectDefinition(subjectCode);
    if (preset != null) {
      return toSpec(preset);
    }
    QuizCatalogDTO.Subject custom = subjectService.get(subjectCode);
    return toSpec(custom);
  }

  /**
   * 按显式 owner 解析科目，供异步线程组装课程展示名。
   */
  public QuizSubjectSpec resolveSubjectForOwner(Long ownerId, String subjectCode) {
    if (subjectCode == null || subjectCode.isBlank()) {
      return null;
    }
    InterviewSkillService.SubjectDefinitionDTO preset =
        skillService.getSubjectDefinition(subjectCode);
    if (preset != null) {
      return toSpec(preset);
    }
    QuizCatalogDTO.Subject custom = subjectService.getForOwner(ownerId, subjectCode);
    return custom == null ? null : toSpec(custom);
  }

  private QuizSubjectSpec toSpec(InterviewSkillService.SubjectDefinitionDTO definition) {
    List<QuizSubjectSpec.Category> categories = definition.categories().stream()
        .filter(category -> category.key() != null && category.label() != null)
        .map(category -> new QuizSubjectSpec.Category(category.key(), category.label()))
        .toList();
    List<QuizSubjectSpec.QuestionType> types = definition.questionTypes().stream()
        .map(type -> new QuizSubjectSpec.QuestionType(
            type.type(), type.defaultScore(), type.weight()))
        .toList();
    return new QuizSubjectSpec(
        definition.id(),
        definition.name(),
        definition.description(),
        definition.examCode(),
        examName(definition.examCode()),
        categories,
        types,
        definition.suggestedMinutes(),
        null,
        definition.persona());
  }

  private QuizSubjectSpec toSpec(QuizCatalogDTO.Subject subject) {
    List<QuizSubjectSpec.Category> categories = subject.knowledgePoints().stream()
        .filter(point -> point.code() != null && point.label() != null)
        .map(point -> new QuizSubjectSpec.Category(point.code(), point.label()))
        .toList();
    List<QuizSubjectSpec.QuestionType> types = subject.questionTypes().stream()
        .map(type -> new QuizSubjectSpec.QuestionType(
            type.type(), type.defaultScore(), type.weight()))
        .toList();
    return new QuizSubjectSpec(
        subject.code(),
        subject.name(),
        subject.description(),
        subject.examCode(),
        examName(subject.examCode()),
        categories,
        types,
        subject.suggestedMinutes(),
        subject.outlineText(),
        null);
  }

  private String examName(String examCode) {
    return skillService.listExams().stream()
        .filter(exam -> exam.code().equals(examCode))
        .map(InterviewSkillService.ExamDTO::name)
        .findFirst()
        .orElse("通用");
  }

  private QuizCatalogDTO.Display toDisplay(InterviewSkillService.ExamDTO exam) {
    if (exam.display() == null) {
      return null;
    }
    return new QuizCatalogDTO.Display(
        exam.display().icon(), exam.display().gradient(),
        exam.display().iconBg(), exam.display().iconColor());
  }
}
