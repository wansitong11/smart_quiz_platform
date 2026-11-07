package interview.guide.modules.resume.listener;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiFeature;
import interview.guide.common.async.recovery.ResumeAnalysisRecoveryProperties;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("切题恢复调度器")
class PaperExtractionRecoverySchedulerTest {

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private PaperExtractionStreamProducer producer;

  private ResumeAnalysisRecoveryProperties properties;
  private PaperExtractionRecoveryScheduler scheduler;

  @BeforeEach
  void setUp() {
    properties = new ResumeAnalysisRecoveryProperties();
    properties.setEnabled(true);
    properties.setPendingThreshold(Duration.ofMinutes(10));
    properties.setProcessingThreshold(Duration.ofMinutes(15));
    properties.setBatchSize(10);
    properties.setMaxRecoveryCount(3);
    scheduler = new PaperExtractionRecoveryScheduler(
        resumeRepository, producer, properties);
  }

  @Test
  @DisplayName("只使用正文就绪过滤查询，不再扫描全部 PENDING")
  void usesExtractableQueryOnly() {
    PracticePaperEntity paper = paper(1L);
    when(resumeRepository.findStaleExtractableByAnalysisStatus(
        eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of(paper));
    when(resumeRepository.findStaleExtractableByAnalysisStatus(
        eq(AsyncTaskStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(resumeRepository.touchQueuedAnalyzeForRecovery(
        eq(1L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
    when(resumeRepository.findById(1L)).thenReturn(Optional.of(paper));
    when(producer.sendExtractTask(1L)).thenReturn(true);

    scheduler.recoverStuckTasks();

    verify(resumeRepository).findStaleExtractableByAnalysisStatus(
        eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class));
    verify(resumeRepository).findStaleExtractableByAnalysisStatus(
        eq(AsyncTaskStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class));
    verify(resumeRepository, never()).findStaleByAnalysisStatus(
        any(AsyncTaskStatus.class), any(LocalDateTime.class), any(Pageable.class));
    verify(producer).sendExtractTask(1L);
  }

  @Test
  @DisplayName("未确认试卷不在过滤结果中时不会补投")
  void doesNotRequeueWhenFilteredQueryReturnsEmpty() {
    when(resumeRepository.findStaleExtractableByAnalysisStatus(
        eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(resumeRepository.findStaleExtractableByAnalysisStatus(
        eq(AsyncTaskStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());

    scheduler.recoverStuckTasks();

    verify(producer, never()).sendExtractTask(any());
  }

  @Test
  @DisplayName("PROCESSING 超时重置后再补投")
  void resetsAndRequeuesStaleProcessing() {
    PracticePaperEntity paper = paper(2L);
    paper.setOwnerId(42L);
    when(resumeRepository.findStaleExtractableByAnalysisStatus(
        eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());
    when(resumeRepository.findStaleExtractableByAnalysisStatus(
        eq(AsyncTaskStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of(paper));
    when(resumeRepository.resetStaleAnalyzeProcessing(
        eq(2L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
    when(resumeRepository.findById(2L)).thenReturn(Optional.of(paper));
    AtomicReference<AiCallContextHolder.AiCallContext> sentContext = new AtomicReference<>();
    when(producer.sendExtractTask(2L)).thenAnswer(invocation -> {
      sentContext.set(AiCallContextHolder.get());
      return true;
    });

    scheduler.recoverStuckTasks();

    verify(resumeRepository).resetStaleAnalyzeProcessing(
        eq(2L), any(LocalDateTime.class), any(LocalDateTime.class));
    verify(producer).sendExtractTask(2L);
    assertThat(sentContext.get()).isNotNull();
    assertThat(sentContext.get().userId()).isEqualTo(42L);
    assertThat(sentContext.get().feature()).isEqualTo(AiFeature.PAPER_EXTRACTION);
  }

  private PracticePaperEntity paper(Long id) {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setId(id);
    paper.setRecognitionStatus(RecognitionStatus.EXTRACTING);
    paper.setResumeText("正文");
    paper.setAnalysisStatus(AsyncTaskStatus.PENDING);
    paper.setAnalysisRecoveryCount(0);
    return paper;
  }
}
