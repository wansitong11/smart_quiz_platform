package interview.guide.modules.voiceinterview.repository;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.model.OralPracticeSessionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = VoiceInterviewSessionRepositoryTest.JpaConfig.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:voice-session-repository;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    })
@Transactional
@DisplayName("语音评估条件状态更新")
class VoiceInterviewSessionRepositoryTest {

  @Autowired
  private VoiceInterviewSessionRepository repository;

  @Test
  @DisplayName("只有 PENDING 能被领取，终态和重试都只作用于当前执行中任务")
  void conditionalTransitionsPreventDuplicateExecution() {
    OralPracticeSessionEntity session = new OralPracticeSessionEntity();
    session.setRoleType("chinese");
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);
    session = repository.saveAndFlush(session);

    LocalDateTime now = LocalDateTime.now();
    assertThat(repository.tryMarkEvaluateProcessing(session.getId(), now)).isEqualTo(1);
    assertThat(repository.tryMarkEvaluateProcessing(session.getId(), now)).isZero();
    assertThat(repository.resetEvaluateToPending(session.getId(), now)).isEqualTo(1);
    assertThat(repository.tryMarkEvaluateProcessing(session.getId(), now)).isEqualTo(1);
    assertThat(repository.completeEvaluateIfProcessing(session.getId(), now)).isEqualTo(1);
    assertThat(repository.completeEvaluateIfProcessing(session.getId(), now)).isZero();
  }

  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = OralPracticeSessionEntity.class)
  @EnableJpaRepositories(basePackageClasses = VoiceInterviewSessionRepository.class)
  static class JpaConfig {
  }
}
