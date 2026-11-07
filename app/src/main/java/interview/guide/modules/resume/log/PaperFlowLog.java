package interview.guide.modules.resume.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * 试卷导入、识别、切题链路的专用日志入口。
 *
 * <p>该 logger 使用独立 appender，完整输出异常、模型原始回包和链路字段；不要在这里接入
 * {@code ErrorLogSanitizer}。用户可见的失败文案仍由业务层单独处理。</p>
 */
public final class PaperFlowLog {

  private static final Logger log = LoggerFactory.getLogger("paper.flow");
  private static final String MDC_TRACE_ID = "paperTraceId";
  private static final String MDC_STAGE = "paperStage";
  private static final String MDC_PAPER_ID = "paperId";
  private static final String MDC_PAGE_NO = "pageNo";
  private static final String MDC_ATTEMPT_ID = "attemptId";

  private PaperFlowLog() {
  }

  public static Scope open(String stage) {
    return open(stage, null, null, null);
  }

  public static Scope open(
      String stage,
      Long paperId,
      String attemptId,
      Integer pageNo
  ) {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    put(MDC_TRACE_ID, traceId(paperId));
    put(MDC_STAGE, stage);
    put(MDC_PAPER_ID, paperId);
    put(MDC_PAGE_NO, pageNo);
    put(MDC_ATTEMPT_ID, attemptId);
    return new Scope(previous);
  }

  public static void event(String message, Object... args) {
    log.info(message, args);
  }

  public static void warn(String message, Object... args) {
    log.warn(message, args);
  }

  public static void error(String message, Throwable error, Object... args) {
    log.error(message, append(error, args));
  }

  public static void value(String label, Object value) {
    log.info("{}={}", label, value);
  }

  /**
   * 上传入口在创建试卷实体后补绑 paperId，让后续日志的 MDC 和 traceId 能串到同一份试卷。
   * Scope.close() 仍会恢复调用前的 MDC，不会污染线程池线程。
   */
  public static void bindPaperId(Long paperId) {
    put(MDC_PAPER_ID, paperId);
    put(MDC_TRACE_ID, traceId(paperId));
  }

  public static void raw(String label, String content) {
    log.info("{} BEGIN length={}\n{}\n{} END", label,
        content == null ? 0 : content.length(), content, label);
  }

  public static void json(ObjectMapper objectMapper, String label, Object value) {
    try {
      value(label, objectMapper.writeValueAsString(value));
    } catch (Exception e) {
      error("{}序列化失败: label={}", e, "PAPER_FLOW_JSON", label);
    }
  }

  public static boolean isActive() {
    return MDC.get(MDC_STAGE) != null;
  }

  private static String traceId(Long paperId) {
    return paperId == null
        ? "upload-" + UUID.randomUUID().toString().substring(0, 8)
        : "paper-" + paperId;
  }

  private static Object[] append(Throwable error, Object[] args) {
    Object[] values = Arrays.copyOf(args, args.length + 1);
    values[args.length] = error;
    return values;
  }

  private static void put(String key, Object value) {
    if (value == null || value.toString().isBlank()) {
      MDC.remove(key);
      return;
    }
    MDC.put(key, value.toString());
  }

  private static void restore(Map<String, String> context) {
    if (context == null || context.isEmpty()) {
      MDC.clear();
      return;
    }
    MDC.setContextMap(context);
  }

  public static final class Scope implements AutoCloseable {

    private final Map<String, String> previous;

    private Scope(Map<String, String> previous) {
      this.previous = previous;
    }

    public Scope paperId(Long paperId) {
      put(MDC_PAPER_ID, paperId);
      put(MDC_TRACE_ID, traceId(paperId));
      return this;
    }

    public Scope pageNo(Integer pageNo) {
      put(MDC_PAGE_NO, pageNo);
      return this;
    }

    public Scope attemptId(String attemptId) {
      put(MDC_ATTEMPT_ID, attemptId);
      return this;
    }

    public Scope stage(String stage) {
      put(MDC_STAGE, stage);
      return this;
    }

    @Override
    public void close() {
      restore(previous);
    }
  }
}
