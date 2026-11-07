package interview.guide.modules.resume.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.RecognitionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("确认切题状态判断")
class PaperRecognitionServiceConfirmStateTest {

  @Test
  @DisplayName("切题排队或处理中才视为在途")
  void pendingOrProcessingIsInFlight() {
    assertThat(PaperRecognitionService.isExtractionInFlight(
        RecognitionStatus.EXTRACTING, AsyncTaskStatus.PENDING)).isTrue();
    assertThat(PaperRecognitionService.isExtractionInFlight(
        RecognitionStatus.EXTRACTING, AsyncTaskStatus.PROCESSING)).isTrue();
  }

  @Test
  @DisplayName("切题失败残留的 EXTRACTING 允许重新确认")
  void failedExtractionIsNotInFlight() {
    assertThat(PaperRecognitionService.isExtractionInFlight(
        RecognitionStatus.EXTRACTING, AsyncTaskStatus.FAILED)).isFalse();
  }

  @Test
  @DisplayName("非切题状态的 EXTRACTING 不阻止重新确认")
  void completedExtractionIsNotInFlight() {
    assertThat(PaperRecognitionService.isExtractionInFlight(
        RecognitionStatus.EXTRACTING, AsyncTaskStatus.COMPLETED)).isFalse();
  }

  @Test
  @DisplayName("非 EXTRACTING 状态不按在途处理")
  void readyStatusIsNotInFlight() {
    assertThat(PaperRecognitionService.isExtractionInFlight(
        RecognitionStatus.READY, AsyncTaskStatus.PROCESSING)).isFalse();
  }
}
