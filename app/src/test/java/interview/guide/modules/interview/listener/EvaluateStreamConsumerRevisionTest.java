package interview.guide.modules.interview.listener;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.resume.model.PracticePaperEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("批改内容版本护栏")
class EvaluateStreamConsumerRevisionTest {

  @Test
  @DisplayName("会话内容版本落后时拒绝批改")
  void rejectsStalePaperContentRevision() {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setContentRevision(2L);
    PracticeSessionEntity session = new PracticeSessionEntity();
    session.setSourceType("PAPER");
    session.setResume(paper);
    session.setPaperContentRevision(1L);

    assertThatThrownBy(() -> EvaluateStreamConsumer.assertPaperContentRevision(session))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("试卷内容已更新");
  }
}
