package interview.guide.modules.resume.listener;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.PageTextMerger;
import interview.guide.modules.resume.service.PaperStructureService;
import interview.guide.modules.resume.service.PaperExtractionResult;
import interview.guide.modules.resume.service.PaperQuestionExtractionService;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("试卷切题 Stream ID-only 消息测试")
class PaperExtractionStreamTest {

  @Mock
  private RedisService redisService;
  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private TransactionalExecutor transactionalExecutor;
  @Mock
  private PaperQuestionExtractionService extractionService;
  @Mock
  private InterviewSessionService interviewSessionService;
  @Mock
  private ResumePersistenceService persistenceService;
  @Mock
  private ResumeParseService parseService;
  @Mock
  private PaperPageRepository pageRepository;
  @Mock
  private PageTextMerger pageTextMerger;
  @Mock
  private PaperStructureService structureService;

  private PaperExtractionStreamProducer producer;
  private PaperExtractionStreamConsumer consumer;

  @BeforeEach
  void setUp() {
    producer = new PaperExtractionStreamProducer(redisService, resumeRepository, transactionalExecutor);
    consumer = new PaperExtractionStreamConsumer(redisService, extractionService, interviewSessionService,
        persistenceService, resumeRepository, parseService, pageRepository, pageTextMerger, structureService);
  }

  private InterviewQuestionDTO extractedQuestion() {
    return new InterviewQuestionDTO(0, "1. 下列读音正确的一项是", "SINGLE_CHOICE", "现代文阅读",
        "字音", null, null, null, false, null, "A", List.of("字音"), "选对得 3 分", null, 3,
        "SINGLE_CHOICE", List.of(new InterviewQuestionDTO.QuestionOption("A", "甲"),
            new InterviewQuestionDTO.QuestionOption("B", "乙")), "甲项符合原文读音。", null, null);
  }

  private PaperExtractionResult extractionResult() {
    List<InterviewQuestionDTO> questions = List.of(extractedQuestion());
    return new PaperExtractionResult("chinese",
        PaperTree.fromFlatQuestions(questions, "现代文阅读"),
        questions, 3, Map.of(), List.of());
  }

  private PracticePaperEntity paper(String paperText, String storageKey, String filename) {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setId(5L);
    paper.setResumeText(paperText);
    paper.setStorageKey(storageKey);
    paper.setOriginalFilename(filename);
    return paper;
  }

  private PaperExtractionStreamConsumer.PaperExtractPayload payload() {
    PaperExtractionStreamConsumer.PaperExtractPayload payload =
        new PaperExtractionStreamConsumer.PaperExtractPayload(5L);
    payload.setAttemptId("attempt-1");
    return payload;
  }

  private InterviewSessionDTO createdSession() {
    return new InterviewSessionDTO("session-1", "", 1, 0, List.of(), null, null, null, null, null);
  }

  private void givenHeartbeatSucceeds() {
    when(resumeRepository.heartbeatAnalyzeProcessing(eq(5L), eq("attempt-1"), any())).thenReturn(1);
  }

  @Nested
  @DisplayName("生产者")
  class ProducerTests {

    @Test
    @DisplayName("Stream 消息只包含 paperId 与 retryCount")
    void shouldSendIdOnlyMessage() {
      producer.sendExtractTask(5L);

      @SuppressWarnings("unchecked")
      ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
      verify(redisService).streamAdd(anyString(), captor.capture(), anyInt());
      assertThat(captor.getValue())
          .containsEntry("paperId", "5")
          .containsEntry("retryCount", "0")
          .hasSize(2);
    }
  }

  @Nested
  @DisplayName("消费者")
  class ConsumerTests {

    @Test
    @DisplayName("解析消息时忽略未知字段")
    void shouldIgnoreUnknownFields() {
      PaperExtractionStreamConsumer.PaperExtractPayload payload = consumer.parsePayload(
          new StreamMessageId(0, 0),
          Map.of("paperId", "5", "content", "旧格式正文", "retryCount", "0"));

      assertThat(payload.paperId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("用数据库正文切题并生成 PAPER 来源的练习会话")
    void shouldExtractFromDatabaseTextAndCreateSession() {
      when(resumeRepository.findById(5L))
          .thenReturn(Optional.of(paper("数据库中的试卷正文", "paper/5", "a.pdf")));
      when(extractionService.extract("数据库中的试卷正文", null, null)).thenReturn(extractionResult());
      when(interviewSessionService.createSessionFromQuestions(
          any(), any(), any(), eq("chinese"), anyString(), any(), any(), eq(5L), eq("PAPER")))
          .thenReturn(createdSession());
      givenHeartbeatSucceeds();

      consumer.processBusiness(payload());

      verify(parseService, never()).downloadAndParseContent(anyString(), anyString());
      verify(persistenceService).saveExtractionSummaryIfOwned(
          eq(5L), eq("attempt-1"), any(PaperExtractionResult.class), eq("session-1"));
    }

    @Test
    @DisplayName("历史正文为空时从对象存储恢复并回填后切题")
    void shouldRestoreTextFromStorageWhenMissing() {
      when(resumeRepository.findById(5L))
          .thenReturn(Optional.of(paper(null, "paper/5", "a.pdf")));
      when(parseService.downloadAndParseContent("paper/5", "a.pdf")).thenReturn("恢复的试卷正文");
      when(extractionService.extract("恢复的试卷正文", null, null)).thenReturn(extractionResult());
      when(interviewSessionService.createSessionFromQuestions(
          any(), any(), any(), anyString(), anyString(), any(), any(), eq(5L), eq("PAPER")))
          .thenReturn(createdSession());
      givenHeartbeatSucceeds();

      consumer.processBusiness(payload());

      verify(persistenceService).updateResumeText(5L, "恢复的试卷正文");
      verify(persistenceService).saveExtractionSummaryIfOwned(
          eq(5L), eq("attempt-1"), any(PaperExtractionResult.class), eq("session-1"));
    }

    @Test
    @DisplayName("恢复后正文仍为空时抛出业务异常且不调用模型")
    void shouldThrowWhenRestoredTextStillEmpty() {
      when(resumeRepository.findById(5L))
          .thenReturn(Optional.of(paper("", "paper/5", "a.pdf")));
      when(parseService.downloadAndParseContent("paper/5", "a.pdf")).thenReturn(" ");
      givenHeartbeatSucceeds();

      assertThatThrownBy(() -> consumer.processBusiness(payload()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("无法获取试卷文本内容");
      verify(extractionService, never()).extract(anyString(), any());
    }

    @Test
    @DisplayName("试卷已删除时跳过且不切题")
    void shouldSkipWhenEntityDeleted() {
      when(resumeRepository.findById(5L)).thenReturn(Optional.empty());

      consumer.processBusiness(payload());

      verify(extractionService, never()).extract(anyString(), any());
      verify(interviewSessionService, never()).createSessionFromQuestions(
          any(), any(), any(), anyString(), anyString(), any(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("重试消息只携带 paperId 与 retryCount")
    void shouldRetryWithIdOnlyMessage() {
      when(resumeRepository.resetAnalyzeToPending(eq(5L), eq("attempt-1"), any())).thenReturn(1);
      consumer.retryMessage(payload(), 3);

      @SuppressWarnings("unchecked")
      ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
      verify(redisService).streamAdd(anyString(), captor.capture(), anyInt());
      assertThat(captor.getValue())
          .containsEntry("paperId", "5")
          .containsEntry("retryCount", "3")
          .hasSize(2);
    }
  }
}
