package interview.guide.common.async;

/** 执行器队列已满；调用方应退避后重投，而不是把业务单元误标失败。 */
public class ExecutorRejectedException extends RuntimeException {

  private final String executorName;

  public ExecutorRejectedException(String executorName, Throwable cause) {
    super("执行器 " + executorName + " 队列已满", cause);
    this.executorName = executorName;
  }

  public String executorName() {
    return executorName;
  }
}
