package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = PaperPageRepositoryTest.JpaConfig.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:paper-page-repository;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    })
@Transactional
@DisplayName("试卷页识别 fencing")
class PaperPageRepositoryTest {

  @Autowired
  private PaperPageRepository pageRepository;

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("旧执行代次不能写入 READY")
  void oldAttemptCannotOverwriteNewAttempt() {
    PaperPageEntity page = pageRepository.save(page(PageStatus.PENDING));
    String oldAttempt = "old-attempt";
    String newAttempt = "new-attempt";

    assertThat(pageRepository.claimPage(
        1L, 1, oldAttempt, LocalDateTime.now())).isEqualTo(1);
    assertThat(pageRepository.resetClaimedPagesToPending(
        1L, List.of(oldAttempt), LocalDateTime.now())).isEqualTo(1);
    assertThat(pageRepository.claimPage(
        1L, 1, newAttempt, LocalDateTime.now())).isEqualTo(1);

    assertThat(pageRepository.markPageReadyIfClaimed(
        1L, 1, oldAttempt, "旧正文", false, null, "old-model", "{}", LocalDateTime.now()))
        .isZero();
    assertThat(pageRepository.existsByPaperIdAndPageNoAndStatusAndRecognitionAttemptId(
        1L, 1, PageStatus.PROCESSING, newAttempt)).isTrue();

    assertThat(pageRepository.markPageReadyIfClaimed(
        1L, 1, newAttempt, "新正文", false, null, "new-model", "{}", LocalDateTime.now()))
        .isEqualTo(1);
    entityManager.clear();
    assertThat(pageRepository.findById(page.getId()).orElseThrow().getTextContent())
        .isEqualTo("新正文");
  }

  @Test
  @DisplayName("恢复只重置超时 PROCESSING，READY 页保持不动")
  void resetsOnlyStaleProcessingPages() {
    PaperPageEntity stale = page(PageStatus.PROCESSING);
    stale.setPageNo(1);
    stale.setRecognitionAttemptId("stale-token");
    stale.setUpdatedAt(LocalDateTime.now().minusMinutes(20));
    pageRepository.save(stale);

    PaperPageEntity ready = page(PageStatus.READY);
    ready.setPageNo(2);
    ready.setTextContent("已经完成");
    ready.setUpdatedAt(LocalDateTime.now().minusMinutes(20));
    pageRepository.save(ready);

    int reset = pageRepository.resetStaleProcessingPages(
        1L, LocalDateTime.now().minusMinutes(10), LocalDateTime.now());

    assertThat(reset).isEqualTo(1);
    entityManager.clear();
    assertThat(pageRepository.findByPaperIdAndPageNo(1L, 1).orElseThrow().getStatus())
        .isEqualTo(PageStatus.PENDING);
    PaperPageEntity unchanged = pageRepository.findByPaperIdAndPageNo(1L, 2).orElseThrow();
    assertThat(unchanged.getStatus()).isEqualTo(PageStatus.READY);
    assertThat(unchanged.getTextContent()).isEqualTo("已经完成");
  }

  private PaperPageEntity page(PageStatus status) {
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(1L);
    page.setPageNo(1);
    page.setSource(PageSource.IMAGE);
    page.setStatus(status);
    page.setLogicalPage(false);
    page.setAttempt(0);
    page.setUpdatedAt(LocalDateTime.now());
    return page;
  }

  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = PaperPageEntity.class)
  @EnableJpaRepositories(basePackageClasses = PaperPageRepository.class)
  static class JpaConfig {
  }
}
