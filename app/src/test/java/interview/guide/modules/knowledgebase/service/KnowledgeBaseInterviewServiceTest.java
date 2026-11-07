package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseInterviewServiceTest {

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  @Mock
  private KnowledgeBaseQuestionRepository questionRepository;

  @Mock
  private InterviewSessionService interviewSessionService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("启用题目不足时拒绝创建知识库面试")
  void shouldRejectWhenActiveQuestionsAreInsufficient() {
    KnowledgeBaseInterviewService service = newService();
    when(knowledgeBaseRepository.findByIdAccessible(1L, null))
        .thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of());

    CreateKnowledgeBaseInterviewRequest request =
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 0, "");

    assertThatThrownBy(() -> service.createSession(request))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT.getCode());
          assertThat(exception.getMessage()).contains("需要 1 道主问题", "只有 0 道");
        });
  }

  @Test
  @DisplayName("不传 category 时跨全部方向抽题")
  @SuppressWarnings("unchecked")
  void shouldCreateSessionAcrossAllCategoriesWhenCategoryIsNull() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    InterviewSessionDTO expected =
        new InterviewSessionDTO("session1", "", 2, 0, List.of(), SessionStatus.CREATED, 1L, null, null, null);
    when(knowledgeBaseRepository.findByIdAccessible(1L, null))
        .thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(expected);

    InterviewSessionDTO actual = service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 1, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));
    assertThat(actual).isSameAs(expected);
    assertThat(captor.getValue()).hasSize(1);
    assertThat(captor.getValue().get(0).isFollowUp()).isFalse();
    assertThat(captor.getValue().get(0).category()).isEqualTo("Redis");
  }

  @Test
  @DisplayName("传 category 时按方向过滤候选题")
  @SuppressWarnings("unchecked")
  void shouldFilterByCategoryWhenCategoryProvided() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findByIdAccessible(1L, null))
        .thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "Redis", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("Redis")))
        .thenReturn(new InterviewSessionDTO("s", "", 1, 0, List.of(), SessionStatus.CREATED, 1L, "Redis", null, null));

    service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, "Redis", "mid", 1, 0, ""));

    // 验证走的是按 category 过滤的查询方法，而不是全量方法
    verify(questionRepository).findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "Redis", KnowledgeBaseQuestionStatus.ACTIVE);
  }

  @Test
  @DisplayName("创建知识库面试时将 category 规范化后传递到面试会话")
  @SuppressWarnings("unchecked")
  void shouldPassNormalizedCategoryToSession() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findByIdAccessible(1L, null))
        .thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "MySQL", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("MySQL")))
        .thenReturn(new InterviewSessionDTO("s", "", 1, 0, List.of(), SessionStatus.CREATED, 1L, "MySQL", null, null));

    InterviewSessionDTO actual = service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, "  MySQL  ", "mid", 1, 0, ""));

    verify(interviewSessionService).createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("MySQL"));
    assertThat(actual.subjectCategory()).isEqualTo("MySQL");
  }

  @Test
  @DisplayName("资料测验忽略请求中的追问数，只生成主问题")
  @SuppressWarnings("unchecked")
  void shouldIgnoreRequestedFollowUpCount() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithThreeFollowUps();
    when(knowledgeBaseRepository.findByIdAccessible(1L, null))
        .thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(new InterviewSessionDTO("s", "", 3, 0, List.of(), SessionStatus.CREATED, 1L, null, null, null));

    service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 2, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));

    List<InterviewQuestionDTO> built = captor.getValue();
    assertThat(built).hasSize(1);
    assertThat(built.get(0).isFollowUp()).isFalse();
  }

  @Test
  @DisplayName("资料测验不因追问池不足而拒绝创建")
  @SuppressWarnings("unchecked")
  void shouldNotRejectWhenFollowUpPoolIsSmallerThanRequestedCount() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findByIdAccessible(1L, null))
        .thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    InterviewSessionDTO expected =
        new InterviewSessionDTO("session1", "", 1, 0, List.of(), SessionStatus.CREATED, 1L, null, null, null);
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(expected);

    InterviewSessionDTO actual = service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 3, ""));

    assertThat(actual).isSameAs(expected);
    verify(interviewSessionService).createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null));
  }

  @Test
  @DisplayName("容量查询按非空追问题干统计严格可用题数")
  void shouldCalculateStrictCapacityByUsableFollowUpCount() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity noFollowUp = questionWithFollowUps("Redis", List.of());
    KnowledgeBaseQuestionEntity oneFollowUp = questionWithFollowUps("Redis", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1"),
        new KnowledgeBaseQuestionFollowUpDTO("   ")
    ));
    KnowledgeBaseQuestionEntity threeFollowUps = questionWithFollowUps("MySQL", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2"),
        new KnowledgeBaseQuestionFollowUpDTO("追问3")
    ));
    when(knowledgeBaseRepository.findByIdAccessible(1L, null))
        .thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE))
        .thenReturn(List.of(noFollowUp, oneFollowUp, threeFollowUps));

    KnowledgeBaseInterviewCapacityResponse response =
        service.getCapacity(1L, null, "mid", 2);

    assertThat(response.followUpOptions())
        .extracting(
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::followUpCount,
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::availableQuestionCount,
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::selectable
        )
        .contains(
            org.assertj.core.groups.Tuple.tuple(0, 3, true),
            org.assertj.core.groups.Tuple.tuple(1, 2, true),
            org.assertj.core.groups.Tuple.tuple(2, 1, false),
            org.assertj.core.groups.Tuple.tuple(3, 1, false)
        );
    assertThat(response.categories())
        .extracting(
            KnowledgeBaseInterviewCapacityResponse.CategoryOption::category,
            KnowledgeBaseInterviewCapacityResponse.CategoryOption::availableQuestionCount
        )
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Redis", 2),
            org.assertj.core.groups.Tuple.tuple("MySQL", 1)
        );
  }

  @Test
  @DisplayName("容量查询按方向过滤追问选项但保留全部方向统计")
  void shouldFilterCapacityByCategoryAndKeepCategoryOptions() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity redis = questionWithFollowUps("Redis", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2")
    ));
    KnowledgeBaseQuestionEntity mysql = questionWithFollowUps("MySQL", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1")
    ));
    when(knowledgeBaseRepository.findByIdAccessible(1L, null))
        .thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(redis, mysql));

    KnowledgeBaseInterviewCapacityResponse response =
        service.getCapacity(1L, " Redis ", "mid", 1);

    assertThat(response.category()).isEqualTo("Redis");
    assertThat(response.followUpOptions())
        .filteredOn(option -> option.followUpCount() == 2)
        .singleElement()
        .satisfies(option -> {
          assertThat(option.availableQuestionCount()).isEqualTo(1);
          assertThat(option.selectable()).isTrue();
        });
    assertThat(response.categories()).hasSize(2);
  }

  private KnowledgeBaseInterviewService newService() {
    return new KnowledgeBaseInterviewService(
        new OwnerScope(),
        knowledgeBaseRepository,
        questionRepository,
        interviewSessionService,
        objectMapper
    );
  }

  private KnowledgeBaseQuestionEntity questionWithFollowUp() throws Exception {
    KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
    entity.setQuestion("主问题");
    entity.setType("REDIS");
    entity.setCategory("Redis");
    entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    entity.setDifficulty("mid");
    entity.setReferenceAnswer("参考答案");
    entity.setKeyPointsJson(objectMapper.writeValueAsString(List.of("要点")));
    entity.setScoringRubric("评分规则");
    entity.setFollowUpsJson(objectMapper.writeValueAsString(List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问", "追问参考答案", List.of("追问要点"), "追问评分规则")
    )));
    return entity;
  }

  private KnowledgeBaseQuestionEntity questionWithThreeFollowUps() throws Exception {
    return questionWithFollowUps("Redis", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1", "答1", List.of(), "规则1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2", "答2", List.of(), "规则2"),
        new KnowledgeBaseQuestionFollowUpDTO("追问3", "答3", List.of(), "规则3")
    ));
  }

  private KnowledgeBaseQuestionEntity questionWithFollowUps(
      String category,
      List<KnowledgeBaseQuestionFollowUpDTO> followUps
  ) throws Exception {
    KnowledgeBaseQuestionEntity entity = questionWithFollowUp();
    entity.setCategory(category);
    entity.setFollowUpsJson(objectMapper.writeValueAsString(followUps));
    return entity;
  }
}
