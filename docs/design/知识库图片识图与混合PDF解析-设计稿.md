# 知识库图片识图与混合 PDF 解析 · 设计稿

> 状态：已实现（2026-11-02），待真实文件手工验收
> 日期：2026-11-02
> 关联决策：Q1=B（图片 + 扫描 PDF）、Q2=A（不落 OCR 文本）、Q3=A（复用默认视觉 Provider）、
> Q4=A（新建精简 OCR Prompt）、Q5=A（页数硬上限 + 全局闸门，不加用户配额）、
> Q6=A（PDF 页级判定）、Q7=A（复用试卷解析器）、Q8=DOCX-only。
> 并发决策（2026-11-02 追加）：Q1=A（任务级并行）、Q2=4 worker、Q3=B（全局视觉并发 2→4）。

## 0. 决策摘要

- 知识库上传新增 JPG / PNG 图片支持，图片经视觉模型转成文字后进入向量化。
- PDF 走「页级判定」：某一页文字层可信就用文字层；几乎没有文字层的页渲染成图再识图。
  因此一份「又有文字、又有扫描图」的混合 PDF 能整份正确处理，不会失败、也不会漏掉扫描页。
- DOCX 内嵌图片一并识图，识别文字插回正文；旧二进制 DOC 的内嵌图本轮不做。
- OCR 出来的文字不落库，只喂给本次向量化；「重新向量化」会重新识图。
- 复用试卷导入的默认识图 Provider、结构化输出调用器、识图并发闸门与图片归一化能力，不新开一套。

## 1. 使用示例（Quick Start）

1. 用户在「知识库上传」页选择一张截图（JPG / PNG），或一份扫描版 PDF，或一份文字与图片混合的 PDF。
2. 文件上传成功后，知识库列表显示「向量化中」。
3. 后台把图片 / 扫描页转成文字，再按原有规则切块、Embedding、写回向量库。
4. 完成后状态变为「已完成」，用户就能在知识库问答里检索到图片里的文字。
5. 若识图失败或超过页数上限，状态变为「失败」，管理页可点「重新向量化」重试。

## 2. 正文（业务逻辑）

### 2.1 图片资料识图

- 目的：让截图、照片这类没有文字层的资料也能进知识库，检索时能命中图片里的文字。
- 触发：用户上传 JPG / PNG 文件，且识图能力开关为开启。
- 规则与步骤：
  1. 上传时校验扩展名 / 文件类型为图片；文件仍按现有流程存到 RustFS，知识库记录为「待向量化」。
  2. 向量化任务开始后，先把图片归一化（摆正方向、控制尺寸与质量），再发给默认识图模型转写文字。
  3. 转写出的文字交给原有切块与向量化流程，和普通文档一视同仁。
- 结果：图片里的文字被检索到；原始图片文件仍保留在 RustFS，不覆盖、不删除。
- 边界与异常：只支持 JPG / PNG；识别返回空文本、模型调用失败或识图能力关闭时，任务标记失败并给出可读原因。
- 可改点：想改支持的图片格式、单份页数上限，改对应配置与上传校验规则。

### 2.2 扫描版与混合 PDF

- 目的：让整本扫描件和「部分页有文字层、部分页是扫描图」的 PDF 都能完整转成文字。
- 触发：用户上传 PDF。
- 规则与步骤：
  1. 逐页判断这一页文字层可不可信。
  2. 文字层可信的页：直接使用文字层，不再识图。
  3. 文字层不可信的页：把这一页渲染成图片，再走识图模型转写文字。
  4. 按原页码顺序把所有页的文字拼起来，交给切块与向量化。
- 结果：扫描页与文字页都被正确覆盖；纯文字 PDF 行为与现在一致（不额外识图、不额外计费）。
- 边界与异常：页数超过上限时整份任务失败并提示拆分；单页渲染或识图失败时任务失败，可手动重试。
- 可改点：想调整「文字层算不算可信」的阈值，改页级判定规则；想改单份页数上限，改配置。

### 2.3 DOCX 内嵌图片识图

- 目的：让 Word 里以截图形式出现的表格、公式、截图等也能被检索到。
- 触发：用户上传 DOCX。
- 规则与步骤：
  1. 按文档顺序读出段落与表格文字，同时按顺序取出内嵌图片。
  2. 每张图片单独识图，识别文字插到它所在段落的正文之后。
  3. 全文（正文 + 图片识别文字）按原有规则切块、向量化。
- 结果：DOCX 正文与图片文字都能检索。
- 边界与异常：内嵌图片数量超过上限时任务失败；过小的装饰图 / 图标不计入识图；旧二进制 DOC 本轮不处理内嵌图。
- 可改点：想改单文档图片数上限，改配置。

### 2.4 上限、失败与重试

- 目的：控制识图成本，同时保证失败可恢复。
- 规则与步骤：
  1. 单份知识库的识图页数、单份 DOCX 的内嵌图数量都有硬上限，超过即失败。
  2. 所有识图请求仍受全局识图并发闸门约束，和试卷识图共用额度，避免打爆视觉 Provider。
  3. 失败沿用知识库现有状态机：任务标「失败」并写清原因；用户可「重新向量化」重试。
- 结果：失败可见、可重试；成本有边界。
- 边界与异常：识别页数超限、识别无文字、下载失败、模型超时都会落到「失败」，不静默丢弃。
- 可改点：改页数 / 图片数上限、是否启用识图，改配置。

### 2.5 并发

- 目的：一次上传多个文件、或不同用户同时上传时，不必一个接一个排队。
- 触发：知识库向量化消息进入 Redis Stream。
- 规则与步骤：
  1. 每个 Stream 仍只有 1 条分发线程负责读消息、放入有界在途队列；业务由多个 worker 并行执行。
  2. 知识库向量化的 worker 数量从 2 调到 4：最多 4 个文件同时做「解析 / 识图 / 向量化」，其余排队。
  3. 全局识图并发上限从 2 调到 4：试卷识图与知识库识图共用这 4 个名额。
- 结果：多个上传文件可以并行处理；识图请求同时最多 4 个在跑。
- 边界与异常：单个文件内部的识图仍是逐页 / 逐图串行（本轮不做页级并行）；识图并发仍受全局闸门约束，
  排队超时会被拒绝并落到失败 / 重试。
- 可改点：改 worker 数量、全局视觉并发上限，都是配置，不动代码。

## 3. 实现简述

### 3.1 关键组件

- `KnowledgeBaseContentExtractor`（新增）：知识库正文抽取的唯一入口，按文件扩展名选择图片 / PDF / DOCX / 其他文本路径。
- `KnowledgeBaseImageOcrService`（新增）：封装一次「图片 → 文字」的视觉模型调用；只负责调用与空结果校验，不做存储与状态。
- `KnowledgeBaseOcrProperties`（新增）：`app.ai.rag.ocr` 配置，含开关、页数上限、DOCX 图片数上限。
- `KbOcrTextDTO`（新增）：识图结构化输出，只有一个 `text` 字段。
- `knowledgebase-image-ocr-system.st`（新增）：知识库专用识图 Prompt，只要求输出文字，不要试卷的 bbox / 资源字段。
- `KnowledgeBaseParseService`（修改）：`downloadAndParseContent` 下载字节后交给 `KnowledgeBaseContentExtractor`，不再直接交给通用 Tika 解析。
- `KnowledgeBaseUploadService`（修改）：上传校验允许图片 MIME / 扩展名。

### 3.2 端到端调用链

```text
上传：
KnowledgeBaseController.uploadKnowledgeBase
  -> KnowledgeBaseUploadService.uploadKnowledgeBase
      -> validateContentType(允许图片)
      -> FileStorageService.uploadKnowledgeBase   // 存 RustFS
      -> KnowledgeBasePersistenceService.saveKnowledgeBase  // 实体 PENDING
      -> VectorizeStreamProducer.sendVectorizeTask

向量化：
VectorizeStreamConsumer.processBusiness
  -> KnowledgeBaseParseService.downloadAndParseContent
      -> FileStorageService.downloadFile
      -> KnowledgeBaseContentExtractor.extractText
          ├─ jpg/jpeg/png -> ImageDocumentParser.parse -> KnowledgeBaseImageOcrService.ocrPageImage
          ├─ pdf          -> PdfDocumentParser.parse   -> TEXT 页拼文字 / IMAGE 页 ocrPageImage
          ├─ docx         -> DocxDocumentParser.parse  -> 正文 + 每张内嵌图 ocrDocxImage
          └─ 其他(txt/md/doc) -> DocumentParseService.parseContent
  -> KnowledgeBaseVectorService.vectorizeAndStore
```

### 3.3 状态与分支

- 知识库实体的 `vectorStatus` 状态机不变：`PENDING -> PROCESSING -> COMPLETED / FAILED`，仍由现有消费者用
  `tryMarkVectorProcessing` / 心跳 / 条件终态写入管理；本次不新增状态字段。
- `extractText` 分支只看文件扩展名小写：`jpg/jpeg/png`、`pdf`、`docx`、其他。
- PDF 页级分支：`PageSource.TEXT` 用文字层；`PageSource.IMAGE` 识图并累计识图页数。
- DOCX 分支：把每张图的识别文字插到 `anchorLineIndex + 1` 的位置，保持大致阅读顺序。

### 3.4 并发、事务与失败边界

- 任务级并发：知识库向量化 worker 池由 2 提升到 4（配置，无代码改动）；多个上传任务并行处理，
  单任务内识图仍逐页 / 逐图串行（页数上限限制单任务时长）。
- 视觉调用仍走 `StructuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation`，自动经过全局
  `VisionRequestGuard`（本轮 `max-in-flight` 由 2 提升到 4，带熔断与准入队列），与试卷识图共用同一额度；
  试卷页级池保持 2，不超过全局上限。
- LLM / 图片调用不在数据库事务内；向量化消费者原本就没有包事务，符合「LLM 不得在事务内」约束。
- 下载为空、识图超限、识图无文字都会抛 `BusinessException`，由消费者 `markFailed` 落终态；失败重试沿用
  现有 Stream 重试与恢复调度器，不再写新的重试机。

### 3.5 复用清单

- `LlmProviderRegistry.getDefaultVisionChatClient()`：默认识图模型。
- `StructuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation`：视觉单次调用 + 结构化解析。
- `VisionRequestGuard`：全局并发闸门与熔断（通过 invoker 间接生效）。
- `PageImageNormalizer`：EXIF 摆正、长边 2000px、JPEG 压缩。
- `ImageDocumentParser` / `PdfDocumentParser` / `DocxDocumentParser`：图片 / PDF 页级 / DOCX 顺序解析。
- `PageSourceDecider`：PDF 页级 TEXT / IMAGE 判定。
- `DocumentParseService`：TXT / MD / 旧 DOC 的 Tika 正文抽取。

## 4. 附录（零猜测实现）

### 4.1 文件清单

新增：

- `app/src/main/java/interview/guide/modules/knowledgebase/model/KbOcrTextDTO.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseOcrProperties.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseImageOcrService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseContentExtractor.java`
- `app/src/main/resources/prompts/knowledgebase-image-ocr-system.st`

修改：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseParseService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java`
- `app/src/main/resources/application.yml`
- `frontend/src/utils/batchUpload.ts`

### 4.2 类与签名

```java
// app/src/main/java/interview/guide/modules/knowledgebase/model/KbOcrTextDTO.java
package interview.guide.modules.knowledgebase.model;

/** 知识库识图结构化输出：只取文字。 */
public record KbOcrTextDTO(String text) {
}
```

```java
// app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseOcrProperties.java
package interview.guide.modules.knowledgebase.service;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.rag.ocr")
public class KnowledgeBaseOcrProperties {
  /** 识图总开关；关闭后图片上传被拒绝，PDF/DOCX 退回 Tika 纯文字。 */
  private boolean enabled = true;

  /** 单份知识库允许识图的页数上限（图片=1 页；PDF 只累计 IMAGE 页）。 */
  @Min(1)
  private int maxPages = 10;

  /** 单份 DOCX 允许识图的内嵌图片数上限。 */
  @Min(1)
  private int maxDocxImages = 40;
}
```

```java
// app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseImageOcrService.java
package interview.guide.modules.knowledgebase.service;

@Slf4j
@Component
public class KnowledgeBaseImageOcrService {

  private final StructuredOutputInvoker structuredOutputInvoker;
  private final LlmProviderRegistry providerRegistry;
  private final BeanOutputConverter<KbOcrTextDTO> outputConverter =
      new BeanOutputConverter<>(KbOcrTextDTO.class);
  private final String systemPrompt;

  public KnowledgeBaseImageOcrService(
      StructuredOutputInvoker structuredOutputInvoker,
      LlmProviderRegistry providerRegistry,
      ResourceLoader resourceLoader) throws IOException {
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.providerRegistry = providerRegistry;
    this.systemPrompt = resourceLoader
        .getResource("classpath:prompts/knowledgebase-image-ocr-system.st")
        .getContentAsString(StandardCharsets.UTF_8)
        + "\n\n" + outputConverter.getFormat();
  }

  /** PDF / 图片页识图。 */
  public String ocrPageImage(byte[] jpeg, int pageNo, int pageCount) {
    return ocr(jpeg,
        "这是第 " + pageNo + " 页（共 " + pageCount + " 页）。请转写图片里的全部文字，按阅读顺序输出。",
        "page-" + pageNo);
  }

  /** DOCX 内嵌图识图。 */
  public String ocrDocxImage(byte[] jpeg, int imageSeq) {
    return ocr(jpeg,
        "这是文档里的第 " + imageSeq + " 张内嵌图片。请转写其中全部文字，按阅读顺序输出。",
        "docx-img-" + imageSeq);
  }

  private String ocr(byte[] jpeg, String userPrompt, String label) {
    Media media = new Media(MimeTypeUtils.IMAGE_JPEG,
        new ByteArrayResource(jpeg, label + ".jpg"));
    KbOcrTextDTO dto = structuredOutputInvoker.invokeVisionOnceWithoutSchemaValidation(
        providerRegistry.getDefaultVisionChatClient(),
        systemPrompt,
        userPrompt,
        List.of(media),
        outputConverter,
        ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
        "知识库图片识别失败：",
        "知识库识图-" + label,
        log);
    String text = dto == null || dto.text() == null ? "" : dto.text().strip();
    if (text.isBlank()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
          "图片识别没有返回文字，请重试或更换更清晰的图片");
    }
    return text;
  }
}
```

```java
// app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseContentExtractor.java
package interview.guide.modules.knowledgebase.service;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseContentExtractor {

  private final ImageDocumentParser imageDocumentParser;
  private final PdfDocumentParser pdfDocumentParser;
  private final DocxDocumentParser docxDocumentParser;
  private final DocumentParseService documentParseService;
  private final KnowledgeBaseImageOcrService ocrService;
  private final KnowledgeBaseOcrProperties ocrProperties;

  /** 唯一正文抽取入口；bytes 为 RustFS 下载到的原始字节。 */
  public String extractText(byte[] bytes, String filename) {
    if (!ocrProperties.isEnabled()) {
      return documentParseService.parseContent(bytes, filename);
    }
    String ext = extension(filename);
    if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)) {
      return extractImage(bytes, filename);
    }
    if ("pdf".equals(ext)) {
      return extractPdf(bytes, filename);
    }
    if ("docx".equals(ext)) {
      return extractDocx(bytes, filename);
    }
    return documentParseService.parseContent(bytes, filename);
  }

  private String extractImage(byte[] bytes, String filename) {
    ParsedDocument doc = imageDocumentParser.parse(bytes, filename, null);
    ParsedPage page = doc.pages().get(0);
    enforceMaxPages(1);
    return ocrService.ocrPageImage(page.pageImage(), 1, 1);
  }

  private String extractPdf(byte[] bytes, String filename) {
    ParsedDocument doc = pdfDocumentParser.parse(bytes, filename, "application/pdf");
    StringBuilder sb = new StringBuilder();
    int visionPages = 0;
    for (ParsedPage page : doc.pages()) {
      if (page.source() == PageSource.TEXT) {
        appendLines(sb, page.lines());
      } else {
        visionPages++;
        enforceMaxPages(visionPages);
        appendLine(sb, ocrService.ocrPageImage(
            page.pageImage(), page.pageNo(), doc.pages().size()));
      }
    }
    return sb.toString().strip();
  }

  private String extractDocx(byte[] bytes, String filename) {
    ParsedDocument doc = docxDocumentParser.parse(bytes, filename,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    StringBuilder sb = new StringBuilder();
    int ocrImages = 0;
    for (ParsedPage page : doc.pages()) {
      List<String> lines = new ArrayList<>(page.lines());
      if (page.assets() != null) {
        for (DocAsset asset : page.assets()) {
          if (asset.bytes() == null || asset.bytes().length == 0) {
            continue;
          }
          ocrImages++;
          enforceMaxDocxImages(ocrImages);
          String text = ocrService.ocrDocxImage(asset.bytes(), ocrImages);
          int idx = asset.anchorLineIndex() == null
              ? lines.size()
              : Math.min(asset.anchorLineIndex() + 1, lines.size());
          lines.add(idx, text);
        }
      }
      appendLines(sb, lines);
    }
    return sb.toString().strip();
  }

  private void enforceMaxPages(int used) {
    if (used > ocrProperties.getMaxPages()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
          "识别页数超过上限（" + ocrProperties.getMaxPages() + " 页），请拆分后重新上传");
    }
  }

  private void enforceMaxDocxImages(int used) {
    if (used > ocrProperties.getMaxDocxImages()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
          "文档内图片数量超过上限（" + ocrProperties.getMaxDocxImages() + " 张）");
    }
  }

  private void appendLines(StringBuilder sb, List<String> lines) {
    for (String line : lines) {
      appendLine(sb, line);
    }
  }

  private void appendLine(StringBuilder sb, String line) {
    if (line != null && !line.isBlank()) {
      sb.append(line.strip()).append('\n');
    }
  }

  private String extension(String filename) {
    if (filename == null) {
      return "";
    }
    int i = filename.lastIndexOf('.');
    return i < 0 ? "" : filename.substring(i + 1).toLowerCase();
  }
}
```

```java
// KnowledgeBaseParseService.downloadAndParseContent 改为：
public String downloadAndParseContent(String storageKey, String originalFilename) {
  log.info("从存储下载并解析知识库文件: {}", originalFilename);
  byte[] bytes = storageService.downloadFile(storageKey);
  if (bytes == null || bytes.length == 0) {
    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败");
  }
  return contentExtractor.extractText(bytes, originalFilename);
}
```

### 4.3 上传校验改动

`KnowledgeBaseUploadService` 增加 `KnowledgeBaseOcrProperties ocrProperties`，并把
`validateContentType` 的校验器替换为下面两个私有方法：

```java
private boolean isKnowledgeBaseContentType(String contentType) {
  return fileValidationService.isKnowledgeBaseMimeType(contentType)
      || (ocrProperties.isEnabled()
          && contentType != null
          && contentType.toLowerCase().startsWith("image/"));
}

private boolean isKnowledgeBaseExtension(String fileName) {
  return fileValidationService.isMarkdownExtension(fileName)
      || isImageExtension(fileName);
}

private boolean isImageExtension(String fileName) {
  if (fileName == null) {
    return false;
  }
  String lower = fileName.toLowerCase();
  return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
}
```

调用点：

```java
fileValidationService.validateContentType(
    contentType,
    fileName,
    this::isKnowledgeBaseContentType,
    this::isKnowledgeBaseExtension,
    "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD、JPG、PNG");
```

### 4.4 Prompt 文件内容

`app/src/main/resources/prompts/knowledgebase-image-ocr-system.st`：

```text
你是资料数字化助手，只负责把一张图片转成保留版式的文字。

规则：
1. 只转写图中所有可见的印刷体 / 文字内容；忽略手写批注、印章、水印、涂改。
2. 保留标题、段落、列表、题号与序号等版面层次。
3. 表格转成 Markdown 表格；数学公式用 LaTeX（$...$）。
4. 无法辨认的内容写 [无法辨认]，不要猜测或补全。
5. 只返回一个 JSON 对象：{"text":"..."}；text 按阅读顺序换行输出，不要输出空行。
6. 不要输出任何解释文字、Markdown 代码块、坐标或资源字段。
```

### 4.5 配置键

在 `app/src/main/resources/application.yml` 的 `app.ai.rag` 下新增：

```yaml
      # 知识库识图：图片 / 扫描 PDF / DOCX 内嵌图
      ocr:
        enabled: ${APP_AI_RAG_OCR_ENABLED:true}
        max-pages: ${APP_AI_RAG_OCR_MAX_PAGES:10}
        max-docx-images: ${APP_AI_RAG_OCR_MAX_DOCX_IMAGES:40}
```

其余复用现有全局配置：`app.ai.default-vision-provider`、`app.ai.vision-request-timeout`、
`app.ai.vision-guard.max-in-flight`。

并发相关配置（直接改现有键的默认值，无需新增配置类）：

```yaml
  ai:
    vision-guard:
      # 由 2 提升到 4；试卷与知识库识图共用
      max-in-flight: ${APP_AI_VISION_MAX_IN_FLIGHT:4}

  async:
    stream:
      consumers:
        knowledgebase-vectorize:
          # 由 2 提升到 4
          worker-threads: ${APP_STREAM_KB_VECTORIZE_WORKERS:4}
          queue-capacity: ${APP_STREAM_KB_VECTORIZE_QUEUE:20}
```

注意：`paper.recognition.executor.pool-size` 保持默认 2，不随全局视觉并发上调；它只需
`<= app.ai.vision-guard.max-in-flight`，当前 2 <= 4 仍成立。

### 4.6 错误码与消息

全部复用 `ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED`（6002，知识库文件解析失败），不新增错误码。消息：

- 下载失败：`下载文件失败`
- 图片识别无文字：`图片识别没有返回文字，请重试或更换更清晰的图片`
- 页数超限：`识别页数超过上限（{maxPages} 页），请拆分后重新上传`
- DOCX 图数超限：`文档内图片数量超过上限（{maxDocxImages} 张）`
- 识图能力关闭：由上传校验提前拦截图片；若绕过，落到原有「扫描件或纯图片」提示。

### 4.7 前端改动

`frontend/src/utils/batchUpload.ts`：

```ts
export const KNOWLEDGE_BASE_FILE_POLICY: FileUploadPolicy = {
  extensions: ['pdf', 'doc', 'docx', 'txt', 'md', 'jpg', 'jpeg', 'png'],
  maxFileSize: 50 * 1024 * 1024,
  maxSizeLabel: '50MB',
  formatLabel: 'PDF、DOCX、DOC、TXT、MD、JPG、PNG',
};
```

### 4.8 测试与验证

后端新增 / 更新测试（JUnit 5 + Mockito + AssertJ）：

- `KnowledgeBaseContentExtractorTest`：扩展名分支、图片走 OCR、纯文字 PDF 不 OCR、扫描 PDF 逐页 OCR、
  混合 PDF 只 OCR 扫描页、DOCX 正文 + 内嵌图插回、DOCX 无图、页数 / 图数超限、识图关闭回退 Tika。
- `KnowledgeBaseImageOcrServiceTest`：媒体构造、Prompt、空结果抛 `KNOWLEDGE_BASE_PARSE_FAILED`。
- `KnowledgeBaseParseServiceTest`：下载为空抛错、下载后走 extractor。
- `KnowledgeBaseUploadServiceTest`：图片 MIME / 扩展名允许、非图片仍拒绝、识图关闭时拒绝图片。
- 既有 `VectorizeStreamConsumerTest` 回归（消费者调用面不变，应保持通过）。

验证分级（T2 模块内）：

```powershell
.\gradlew.bat :app:test --tests "interview.guide.modules.knowledgebase.service.KnowledgeBaseContentExtractorTest" --tests "interview.guide.modules.knowledgebase.service.KnowledgeBaseImageOcrServiceTest" --tests "interview.guide.modules.knowledgebase.service.KnowledgeBaseParseServiceTest" --tests "interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadServiceTest" --tests "interview.guide.modules.knowledgebase.listener.VectorizeStreamConsumerTest" --no-daemon 2>&1 | Select-Object -Last 60
```

```powershell
cd frontend; pnpm run build
```

手动验收：JPG / PNG、扫描 PDF、混合 PDF、含图 DOCX、无图 DOCX、旧 DOC（保持现状）、超页数上限。

### 4.9 可改点映射

- 图片格式：`KnowledgeBaseUploadService.isImageExtension` 与 `KnowledgeBaseContentExtractor.extension`。
- 单份识图页数：`app.ai.rag.ocr.max-pages`。
- 单份 DOCX 图数：`app.ai.rag.ocr.max-docx-images`。
- 识图总开关：`app.ai.rag.ocr.enabled`。
- 识图 Prompt：`prompts/knowledgebase-image-ocr-system.st`。
- PDF 页级判定阈值：复用 `PageSourceDecider`（如需按知识库单独调，需评估是否复制阈值，本轮不复制）。
- 任务级并发：`app.async.stream.consumers.knowledgebase-vectorize.worker-threads`。
- 全局视觉并发：`app.ai.vision-guard.max-in-flight`。

### 4.10 实现验证（2026-11-02）

- 后端 `:app:compileJava`、`:app:compileTestJava` 通过（JDK 25，纯 ASCII `Q:` 路径）。
- 知识库相关定向测试共 30 例全部通过：`KnowledgeBaseContentExtractorTest` 9、
  `KnowledgeBaseImageOcrServiceTest` 2、`KnowledgeBaseParseServiceTest` 2、
  `KnowledgeBaseUploadServiceTest` 9、`VectorizeStreamConsumerTest` 8。
- `VisionRequestGuardTest` 6 例通过，确认全局视觉并发默认值调整不影响准入与熔断测试。
- 前端 `pnpm run test:batch-upload` 6 例通过，`pnpm run build` 通过。
- 后端全量回归 `687` 例：`18` 失败、`45` 跳过。剩余失败均为已登记的
  `KnowledgeBaseInterviewServiceTest`、`KnowledgeBaseQuestionServiceTest`、
  `QuestionGenerationAsyncTest` 旧 `OwnerScope` 测试适配问题，本次知识库解析链路无新增失败。
- 真实 JPG / PNG、纯文字 PDF、扫描 PDF、混合 PDF、含图 DOCX、无图 DOCX、旧 DOC 和超页数上限
  尚未在运行环境中逐项手工验收。

## 5. 术语

- **解析文本**：从文件文字层抽取出的文字（Tika / PDFBox / POI）。
- **识别文本（OCR 文本）**：视觉模型从图片转写出的文字。
- **向量化文本**：进入切块与 Embedding 前的合并文本（解析文本 + 识别文本）。
- **识图页**：需要走视觉模型的页（纯图片文件、扫描 PDF 中被判定为 IMAGE 的页）。
- **混合 PDF**：同一份 PDF 同时存在「有文字层」的页与「无文字层」的扫描页。

注：这里用「识图 / OCR 文本」与试卷链路 GLOSSARY.md 里的「识别」区分；试卷「识别」是整卷转写与校对过程，
知识库此处只是「图片转文字」这一步。

## 6. 边界与失败模式汇总

- 图片格式仅 JPG / PNG；HEIC / WEBP / BMP / GIF 不在本轮。
- PDF 页级判定沿用试卷规则：大图 + 薄文字层的页按扫描页识图；文字层可信页忽略页内图片。
- 单份任务识图页数、DOCX 内嵌图数超限即失败，不截断、不静默丢弃。
- 视觉调用失败 / 超时 / 排队满由现有链路抛出，任务落 `FAILED`，可「重新向量化」重试；重试会重跑整份识图。
- OCR 文本不落库：重向量化会重新计费识图；原始文件始终保留，用于重试。
