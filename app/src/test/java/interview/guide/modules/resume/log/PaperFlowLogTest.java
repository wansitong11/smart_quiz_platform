package interview.guide.modules.resume.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("试卷链路日志")
class PaperFlowLogTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger("paper.flow");
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void setUp() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  @DisplayName("原样输出链路字段、模型文本和完整异常链")
  void shouldKeepPaperFlowFieldsRawContentAndCauseChain() {
    IllegalStateException root = new IllegalStateException("bbox=broken");
    RuntimeException error = new RuntimeException("vision failed", root);

    try (PaperFlowLog.Scope ignored =
             PaperFlowLog.open("PAGE_RECOGNITION", 42L, "attempt-1", 2)) {
      PaperFlowLog.raw("VISION_MODEL_RESPONSE", "line1\n{\"raw\":true}");
      PaperFlowLog.error("VISION_CALL_FAILED failureClass={}", error, "INCOMPLETE_CONTENT");
    }

    ILoggingEvent rawEvent = appender.list.stream()
        .filter(event -> event.getLevel() == Level.INFO)
        .findFirst()
        .orElseThrow();
    assertThat(rawEvent.getFormattedMessage())
        .contains("VISION_MODEL_RESPONSE BEGIN length=")
        .contains("line1\n{\"raw\":true}");
    assertThat(rawEvent.getMDCPropertyMap())
        .containsEntry("paperTraceId", "paper-42")
        .containsEntry("paperStage", "PAGE_RECOGNITION")
        .containsEntry("paperId", "42")
        .containsEntry("pageNo", "2")
        .containsEntry("attemptId", "attempt-1");

    ILoggingEvent errorEvent = appender.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .findFirst()
        .orElseThrow();
    assertThat(errorEvent.getFormattedMessage()).contains("failureClass=INCOMPLETE_CONTENT");
    IThrowableProxy throwable = errorEvent.getThrowableProxy();
    assertThat(throwable).isNotNull();
    assertThat(throwable.getMessage()).isEqualTo("vision failed");
    assertThat(throwable.getCause()).isNotNull();
    assertThat(throwable.getCause().getMessage()).isEqualTo("bbox=broken");
  }
}
