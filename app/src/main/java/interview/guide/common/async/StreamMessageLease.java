package interview.guide.common.async;

import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

/**
 * 单条 Stream 消息的 ACK 句柄。
 */
@Slf4j
public final class StreamMessageLease {

  private final RedisService redisService;
  private final StreamWorkerPool workerPool;
  private final String streamKey;
  private final String groupName;
  private final StreamMessageId messageId;

  StreamMessageLease(
      RedisService redisService,
      StreamWorkerPool workerPool,
      String streamKey,
      String groupName,
      StreamMessageId messageId
  ) {
    this.redisService = redisService;
    this.workerPool = workerPool;
    this.streamKey = streamKey;
    this.groupName = groupName;
    this.messageId = messageId;
  }

  public StreamMessageId messageId() {
    return messageId;
  }

  public void ack() {
    if (!workerPool.ackAllowed()) {
      log.warn("Stream worker 已停止 ACK，消息保持 Pending: stream={}, group={}, messageId={}",
          streamKey, groupName, messageId);
      return;
    }
    try {
      redisService.streamAck(streamKey, groupName, messageId);
    } catch (Exception e) {
      workerPool.recordAckFailure();
      log.error("ACK Stream 消息失败，消息保持 Pending: stream={}, group={}, messageId={}",
          streamKey, groupName, messageId, e);
    }
  }
}
