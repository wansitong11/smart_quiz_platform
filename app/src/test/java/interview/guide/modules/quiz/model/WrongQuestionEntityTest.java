package interview.guide.modules.quiz.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WrongQuestionEntityTest {

  @Test
  @DisplayName("累计答对三次后自动掌握")
  void mastersAfterThreeCorrectAnswers() {
    WrongQuestionEntity entity = new WrongQuestionEntity();
    LocalDateTime now = LocalDateTime.of(2026, 10, 28, 9, 0);

    entity.recordWrong(now);
    entity.recordCorrect(now.plusMinutes(1));
    entity.recordCorrect(now.plusMinutes(2));
    entity.recordCorrect(now.plusMinutes(3));

    assertThat(entity.getCorrectCount()).isEqualTo(3);
    assertThat(entity.getStatus()).isEqualTo(WrongQuestionStatus.MASTERED);
    assertThat(entity.getMasteredAt()).isEqualTo(now.plusMinutes(3));
  }

  @Test
  @DisplayName("已掌握后再答错会降级并清零掌握进度")
  void downgradesWhenMasteredQuestionIsWrongAgain() {
    WrongQuestionEntity entity = new WrongQuestionEntity();
    LocalDateTime now = LocalDateTime.of(2026, 10, 28, 9, 0);
    entity.recordWrong(now);
    entity.recordCorrect(now);
    entity.recordCorrect(now);
    entity.recordCorrect(now);

    entity.recordWrong(now.plusDays(1));

    assertThat(entity.getStatus()).isEqualTo(WrongQuestionStatus.PENDING);
    assertThat(entity.getCorrectCount()).isZero();
    assertThat(entity.getWrongCount()).isEqualTo(2);
    assertThat(entity.getMasteredAt()).isNull();
  }

  @Test
  @DisplayName("移出与恢复保留掌握进度")
  void archivesAndRestoresWithoutLosingProgress() {
    WrongQuestionEntity entity = new WrongQuestionEntity();
    entity.recordWrong(LocalDateTime.now());
    entity.recordCorrect(LocalDateTime.now());

    entity.archive(LocalDateTime.now());
    assertThat(entity.getStatus()).isEqualTo(WrongQuestionStatus.ARCHIVED);

    entity.restore();
    assertThat(entity.getStatus()).isEqualTo(WrongQuestionStatus.PENDING);
    assertThat(entity.getCorrectCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("已移除错题在测验中再次答错会清除移出时间")
  void clearsArchivedAtWhenArchivedQuestionIsWrongAgain() {
    WrongQuestionEntity entity = new WrongQuestionEntity();
    LocalDateTime now = LocalDateTime.of(2026, 10, 28, 9, 0);
    entity.recordWrong(now);
    entity.archive(now.plusMinutes(1));

    entity.recordWrong(now.plusHours(1));

    assertThat(entity.getStatus()).isEqualTo(WrongQuestionStatus.PENDING);
    assertThat(entity.getArchivedAt()).isNull();
    assertThat(entity.getWrongCount()).isEqualTo(2);
  }
}
