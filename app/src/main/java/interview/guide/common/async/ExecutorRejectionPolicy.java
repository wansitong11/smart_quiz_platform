package interview.guide.common.async;

/** 有界执行器队列满时的处理策略。 */
public enum ExecutorRejectionPolicy {

  /** 直接拒绝，调用方收到 {@link ExecutorRejectedException} 后自行退避重投。 */
  ABORT,

  /** 由提交任务的线程自己执行，形成天然背压。 */
  CALLER_RUNS
}
