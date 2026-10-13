// 复现线上"解析卡死"的最小探针：用项目 fat-jar 里的 Tika/PDFBox + 与 DocumentParseService 相同的配置，
// 解析指定文件；期间每 2 秒打印一次堆/非堆用量，30 秒未返回就打印工作线程的调用栈定位卡点。
//
// 运行：
//   java -Xmx512m -cp app/build/libs/app-0.0.1-SNAPSHOT.jar scripts/TikaParseProbe.java <file>

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;

public class TikaParseProbe {

  /** 与线上一致：不解析嵌入文档 */
  static class NoOpExtractor implements EmbeddedDocumentExtractor {
    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
      return false;
    }

    @Override
    public void parseEmbedded(InputStream stream, org.xml.sax.ContentHandler handler,
                              Metadata metadata, boolean outputHtml) {
      // 忽略嵌入内容
    }
  }

  public static void main(String[] args) throws Exception {
    File file = new File(args.length > 0 ? args[0] : "docs/test-papers/数学知识点.pdf");
    System.out.printf("文件: %s (%.1f KB)%n", file.getName(), file.length() / 1024.0);

    ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "document-parse-probe");
      t.setDaemon(true);
      return t;
    });

    long start = System.currentTimeMillis();
    Future<String> future = executor.submit(() -> {
      try (InputStream in = new FileInputStream(file)) {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(5 * 1024 * 1024);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new NoOpExtractor());
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true);
        context.set(PDFParserConfig.class, pdfConfig);
        parser.parse(in, handler, metadata, context);
        return handler.toString();
      }
    });

    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    Thread monitor = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        System.out.printf("  [%4.1fs] heap=%dMB/%dMB nonheap=%dMB%n",
            (System.currentTimeMillis() - start) / 1000.0,
            heap.getUsed() / 1024 / 1024, heap.getMax() / 1024 / 1024,
            nonHeap.getUsed() / 1024 / 1024);
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          return;
        }
      }
    });
    monitor.setDaemon(true);
    monitor.start();

    try {
      String text = future.get(30, TimeUnit.SECONDS);
      monitor.interrupt();
      System.out.printf("解析完成：用时 %.2fs，文本 %d 字符%n",
          (System.currentTimeMillis() - start) / 1000.0, text.length());
      System.out.println("前 200 字符: " + text.substring(0, Math.min(200, text.length())).replaceAll("\\s+", " "));
    } catch (TimeoutException e) {
      monitor.interrupt();
      System.out.println("解析 30 秒仍未返回（线上就是这种卡住）");
      System.out.println("--- 工作线程调用栈 ---");
      Thread.getAllStackTraces().entrySet().stream()
          .filter(entry -> entry.getKey().getName().startsWith("document-parse-probe"))
          .forEach(entry -> {
            System.out.println("线程: " + entry.getKey().getName() + " " + entry.getKey().getState());
            for (StackTraceElement element : entry.getValue()) {
              System.out.println("    at " + element);
            }
          });
      System.exit(2);
    } finally {
      executor.shutdownNow();
    }
  }
}
