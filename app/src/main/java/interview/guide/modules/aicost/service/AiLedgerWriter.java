package interview.guide.modules.aicost.service;

import interview.guide.modules.aicost.AiCostProperties;
import interview.guide.modules.aicost.model.AiCallEventEntity;
import interview.guide.modules.aicost.repository.AiCallEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 明细账本异步落盘：业务线程只 offer，单写线程批量 save，队列满则丢弃并告警（fail-open）。
 */
@Slf4j
@Component
public class AiLedgerWriter {

  private final AiCostProperties properties;
  private final AiCallEventRepository eventRepository;

  private final LinkedBlockingQueue<AiCallEventEntity> queue;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong dropped = new AtomicLong();
  private Thread writer;

  public AiLedgerWriter(AiCostProperties properties, AiCallEventRepository eventRepository) {
    this.properties = properties;
    this.eventRepository = eventRepository;
    this.queue = new LinkedBlockingQueue<>(
        Math.max(100, properties.getLedger().getQueueCapacity()));
  }

  @PostConstruct
  void start() {
    running.set(true);
    writer = new Thread(this::loop, "ai-ledger-writer");
    writer.setDaemon(true);
    writer.start();
  }

  @PreDestroy
  void stop() {
    running.set(false);
    if (writer != null) {
      writer.interrupt();
    }
  }

  public void record(AiCallEventEntity event) {
    if (!queue.offer(event)) {
      long n = dropped.incrementAndGet();
      if (n == 1 || n % 1000 == 0) {
        log.warn("[AiLedgerWriter] 账本队列已满，丢弃 {} 条明细", n);
      }
    }
  }

  private void loop() {
    List<AiCallEventEntity> batch = new ArrayList<>();
    int flushSize = Math.max(1, properties.getLedger().getFlushBatchSize());
    while (running.get() || !queue.isEmpty()) {
      try {
        AiCallEventEntity event = queue.poll(500, TimeUnit.MILLISECONDS);
        if (event != null) {
          batch.add(event);
        }
        boolean idle = event == null && queue.isEmpty();
        if (!batch.isEmpty() && (idle || batch.size() >= flushSize)) {
          flush(batch);
          batch.clear();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.warn("[AiLedgerWriter] 账本落盘失败", e);
      }
    }
    if (!batch.isEmpty()) {
      flush(batch);
    }
  }

  private void flush(List<AiCallEventEntity> batch) {
    try {
      eventRepository.saveAll(batch);
    } catch (Exception e) {
      log.warn("[AiLedgerWriter] 批量写入 {} 条账本失败", batch.size(), e);
    }
  }
}
