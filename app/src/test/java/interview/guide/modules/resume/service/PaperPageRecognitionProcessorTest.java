package interview.guide.modules.resume.service;

import interview.guide.common.async.BoundedExecutor;
import interview.guide.common.async.BoundedExecutorFactory;
import interview.guide.common.async.ExecutorRejectedException;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.resume.model.PageAssetGeometry;
import interview.guide.modules.resume.model.PageRecognition;
import interview.guide.modules.resume.model.PageRecognitionGeometrySnapshot;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("试卷页并行识别协调器")
class PaperPageRecognitionProcessorTest {

  private PaperPageRepository pageRepository;
  private ResumeRepository resumeRepository;
  private PaperAssetService assetService;
  private List<BoundedExecutor> executors;

  @BeforeEach
  void setUp() {
    pageRepository = mock(PaperPageRepository.class);
    resumeRepository = mock(ResumeRepository.class);
    assetService = mock(PaperAssetService.class);
    executors = new ArrayList<>();
    when(pageRepository.claimPage(anyLong(), anyInt(), anyString(), any()))
        .thenReturn(1);
    when(pageRepository.markPageReadyIfClaimed(
        anyLong(), anyInt(), anyString(), any(), anyBoolean(), any(), any(), any(), any()))
        .thenReturn(1);
    when(pageRepository.markPageFailedIfClaimed(
        anyLong(), anyInt(), anyString(), anyString(), any()))
        .thenReturn(1);
  }

  @AfterEach
  void tearDown() {
    executors.forEach(BoundedExecutor::shutdown);
  }

  @Test
  @DisplayName("多个图片页可在执行器中并行识别")
  void recognizesImagePagesInParallel() throws Exception {
    BoundedExecutor executor = executor("paper-parallel", 2, 4);
    PaperPageRecognitionProcessor processor = processor(executor);
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    PaperPageRecognitionProcessor.PageRecognizer recognizer = (page, attemptId) -> {
      bothStarted.countDown();
      release.await(2, TimeUnit.SECONDS);
      return recognized("page-" + page.getPageNo());
    };

    CompletableFuture<Void> processing = CompletableFuture.runAsync(() ->
        processor.process(1L, List.of(page(1), page(2)), recognizer, () -> {
        }));

    assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    processing.get(2, TimeUnit.SECONDS);

    verify(pageRepository, times(2)).markPageReadyIfClaimed(
        anyLong(), anyInt(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("单页失败只标记该页，不影响其它页完成")
  void isolatesSinglePageFailure() {
    BoundedExecutor executor = executor("paper-isolation", 2, 4);
    PaperPageRecognitionProcessor processor = processor(executor);
    PaperPageRecognitionProcessor.PageRecognizer recognizer = (page, attemptId) -> {
      if (page.getPageNo() == 1) {
        throw new BusinessException("第 1 页识别失败");
      }
      return recognized("第 2 页正文");
    };

    processor.process(1L, List.of(page(1), page(2)), recognizer, () -> {
    });

    verify(pageRepository).markPageFailedIfClaimed(
        anyLong(), anyInt(), anyString(), anyString(), any());
    verify(pageRepository).markPageReadyIfClaimed(
        anyLong(), anyInt(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("READY 页不会再次提交识别")
  void skipsReadyPage() {
    BoundedExecutor executor = executor("paper-skip", 1, 2);
    PaperPageRecognitionProcessor processor = processor(executor);
    PaperPageEntity ready = page(1);
    ready.setStatus(PageStatus.READY);

    processor.process(1L, List.of(ready), (page, attemptId) -> {
      throw new AssertionError("READY 页不应进入识别");
    }, () -> {
    });

    verify(pageRepository, never()).claimPage(anyLong(), anyInt(), anyString(), any());
  }

  @Test
  @DisplayName("重处理 TEXT 页时保留已保存的资源几何快照")
  void preservesTextPageGeometryWhenReprocessing() throws Exception {
    BoundedExecutor executor = executor("paper-text-geometry", 1, 2);
    PaperPageRecognitionProcessor processor = processor(executor);
    PaperPageEntity pending = page(1);
    pending.setSource(PageSource.TEXT);
    pending.setStatus(PageStatus.PENDING);
    pending.setTextContent("A. [图片] B. [图片]");
    PageRecognitionGeometrySnapshot snapshot = new PageRecognitionGeometrySnapshot(
        PageRecognitionGeometrySnapshot.CURRENT_VERSION,
        1,
        List.of(new PageRecognition.PageLine(
            "A. [图片] B. [图片]", new double[] {0, 0.2, 1, 0.04})),
        List.of(new PageAssetGeometry(
            "p1a4", "OPTION_ROW", "OPTIONS", "A.", new double[] {0, 0.2, 1, 0.04},
            "1x4", 4))
    );
    pending.setAssetsJson(new ObjectMapper().writeValueAsString(snapshot));
    PaperPageEntity converged = page(1);
    converged.setStatus(PageStatus.READY);
    when(pageRepository.findByPaperIdOrderByPageNoAsc(1L)).thenReturn(List.of(converged));

    processor.process(1L, List.of(pending), (page, attemptId) -> {
      throw new AssertionError("TEXT 页不应进入图片识别");
    }, () -> {
    });

    ArgumentCaptor<String> geometryJson = ArgumentCaptor.forClass(String.class);
    verify(pageRepository).markPageReadyIfClaimed(
        eq(1L), eq(1), anyString(), eq("A. [图片] B. [图片]"),
        eq(false), isNull(), isNull(), geometryJson.capture(), any());
    assertThat(geometryJson.getValue()).contains("\"assetKey\":\"p1a4\"");
  }

  @Test
  @DisplayName("队列满时释放已领取页且不把页误标失败")
  void releasesClaimsWhenQueueRejects() throws Exception {
    BoundedExecutor executor = executor("paper-reject", 1, 1);
    PaperPageRecognitionProcessor processor = processor(executor);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    executor.submit(() -> {
      started.countDown();
      release.await(2, TimeUnit.SECONDS);
      return null;
    });
    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> processor.process(
        1L, List.of(page(1), page(2)), (page, attemptId) -> recognized("ok"), () -> {
        }))
        .isInstanceOf(ExecutorRejectedException.class);

    verify(pageRepository).resetClaimedPagesToPending(anyLong(), anyList(), any());
    verify(pageRepository, never()).markPageReadyIfClaimed(
        anyLong(), anyInt(), anyString(), any(), anyBoolean(), any(), any(), any(), any());
    verify(pageRepository, never()).markPageFailedIfClaimed(
        anyLong(), anyInt(), anyString(), anyString(), any());
    release.countDown();
  }

  @Test
  @DisplayName("所有页都失败时仍走整卷失败路径")
  void failsWholePaperWhenEveryPageFailed() {
    BoundedExecutor executor = executor("paper-all-failed", 1, 2);
    PaperPageRecognitionProcessor processor = processor(executor);
    PaperPageEntity first = page(1);
    first.setStatus(PageStatus.FAILED);
    PaperPageEntity second = page(2);
    second.setStatus(PageStatus.FAILED);
    when(pageRepository.findByPaperIdOrderByPageNoAsc(1L))
        .thenReturn(List.of(first, second));

    assertThatThrownBy(() -> processor.failIfAllPagesFailed(1L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("所有页均识别失败");
  }

  @Test
  @DisplayName("fencing 失败时清理本次识别代次资源")
  void cleansAttemptAssetsWhenPageWriteIsFencedOut() {
    BoundedExecutor executor = executor("paper-fenced-assets", 1, 2);
    PaperPageRecognitionProcessor processor = processor(executor);
    when(pageRepository.markPageReadyIfClaimed(
        anyLong(), anyInt(), anyString(), any(), anyBoolean(), any(), any(), any(), any()))
        .thenReturn(0);

    processor.process(1L, List.of(page(1)), (page, attemptId) -> recognized("正文"), () -> {
    });

    verify(assetService).deleteBySourceAttemptId(eq(1L), anyString());
  }

  private PaperPageRecognitionProcessor processor(BoundedExecutor executor) {
    PaperRecognitionExecutorProperties properties = new PaperRecognitionExecutorProperties();
    properties.setHeartbeatInterval(Duration.ofMillis(10));
    properties.setPageStaleThreshold(Duration.ofSeconds(2));
    PaperRecognitionStateService stateService =
        new PaperRecognitionStateService(pageRepository, resumeRepository);
    return new PaperPageRecognitionProcessor(
        pageRepository, stateService, assetService, executor, properties, new ObjectMapper());
  }

  private PaperPageEntity page(int pageNo) {
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(1L);
    page.setPageNo(pageNo);
    page.setSource(PageSource.IMAGE);
    page.setStatus(PageStatus.PENDING);
    page.setUpdatedAt(LocalDateTime.now());
    return page;
  }

  private PaperPageRecognitionProcessor.RecognizedPage recognized(String text) {
    return new PaperPageRecognitionProcessor.RecognizedPage(
        text, false, null, "vision-model");
  }

  private BoundedExecutor executor(String name, int poolSize, int queueCapacity) {
    @SuppressWarnings("unchecked")
    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
        mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    BoundedExecutorPropertiesAdapter properties = new BoundedExecutorPropertiesAdapter(
        poolSize, queueCapacity);
    BoundedExecutor executor = new BoundedExecutorFactory(provider).create(name, properties);
    executors.add(executor);
    return executor;
  }

  private static final class BoundedExecutorPropertiesAdapter
      extends interview.guide.common.async.BoundedExecutorProperties {

    private BoundedExecutorPropertiesAdapter(int poolSize, int queueCapacity) {
      setPoolSize(poolSize);
      setQueueCapacity(queueCapacity);
      setPropagateSecurityContext(false);
    }
  }
}
