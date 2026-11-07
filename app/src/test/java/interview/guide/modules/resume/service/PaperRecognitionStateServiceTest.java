package interview.guide.modules.resume.service;

import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("页识别状态与内容版本")
class PaperRecognitionStateServiceTest {

  private final PaperPageRepository pageRepository = mock(PaperPageRepository.class);
  private final ResumeRepository resumeRepository = mock(ResumeRepository.class);
  private final PaperRecognitionStateService service =
      new PaperRecognitionStateService(pageRepository, resumeRepository);

  @Test
  @DisplayName("当前页代次写入 READY 后推进内容版本")
  void advancesRevisionOnlyWhenPageWriteSucceeds() {
    LocalDateTime now = LocalDateTime.now();
    when(pageRepository.markPageReadyIfClaimed(
        7L, 2, "attempt", "正文", false, null, "model", "{}", now))
        .thenReturn(1);

    boolean updated = service.markPageReadyAndAdvanceRevision(
        7L, 2, "attempt", "正文", false, null, "model", "{}", now);

    assertThat(updated).isTrue();
    verify(resumeRepository).incrementContentRevision(7L, now);
  }

  @Test
  @DisplayName("fencing 失败时不推进内容版本")
  void doesNotAdvanceRevisionWhenPageWriteIsFencedOut() {
    LocalDateTime now = LocalDateTime.now();
    when(pageRepository.markPageReadyIfClaimed(
        7L, 2, "attempt", "正文", false, null, "model", "{}", now))
        .thenReturn(0);

    boolean updated = service.markPageReadyAndAdvanceRevision(
        7L, 2, "attempt", "正文", false, null, "model", "{}", now);

    assertThat(updated).isFalse();
    verify(resumeRepository, never()).incrementContentRevision(any(), any());
  }
}
