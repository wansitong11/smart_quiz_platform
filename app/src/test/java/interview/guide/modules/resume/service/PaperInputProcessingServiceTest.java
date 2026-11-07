package interview.guide.modules.resume.service;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.resume.document.DocumentParser;
import interview.guide.modules.resume.document.ParsedDocument;
import interview.guide.modules.resume.document.ParsedPage;
import interview.guide.modules.resume.listener.PaperRecognitionStreamProducer;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PaperInputFileEntity;
import interview.guide.modules.resume.model.PaperUploadResult;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.ProcessingPath;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.repository.PaperInputFileRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("试卷输入解析 worker")
class PaperInputProcessingServiceTest {

  private static final Long PAPER_ID = 8L;
  private static final String ATTEMPT_ID = "input-attempt";

  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private PaperInputFileRepository inputFileRepository;
  @Mock
  private PaperRecognitionService recognitionService;
  @Mock
  private PaperRecognitionStreamProducer recognitionProducer;
  @Mock
  private FileStorageService storageService;
  @Mock
  private ContentTypeDetectionService contentTypeDetectionService;
  @Mock
  private DocumentParser documentParser;
  @Mock
  private TransactionalExecutor transactionalExecutor;
  @Mock
  private PaperUploadProperties uploadProperties;

  @Test
  @DisplayName("纯文字原始文件解析完成后直接投递切题，并持续刷新领取权")
  void parsesTextInputAndRoutesDirectExtraction() {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setId(PAPER_ID);
    PaperInputFileEntity input = new PaperInputFileEntity();
    input.setPaperId(PAPER_ID);
    input.setSeq(1);
    input.setFilename("paper.txt");
    input.setStorageKey("paper-input/paper.txt");
    ParsedDocument parsed = new ParsedDocument(
        "TXT",
        List.of(new ParsedPage(1, PageSource.TEXT, List.of("1. 题目"), List.of(), null, false, null)),
        List.of()
    );

    when(resumeRepository.findById(PAPER_ID)).thenReturn(Optional.of(paper));
    when(inputFileRepository.findByPaperIdOrderBySeqAsc(PAPER_ID)).thenReturn(List.of(input));
    when(resumeRepository.heartbeatInputProcessing(eq(PAPER_ID), eq(ATTEMPT_ID), any()))
        .thenReturn(1);
    when(storageService.downloadFile(input.getStorageKey())).thenReturn(new byte[]{1});
    when(contentTypeDetectionService.detectContentType(any(byte[].class), anyString()))
        .thenReturn("text/plain");
    when(documentParser.supports("text/plain", "paper.txt")).thenReturn(true);
    when(documentParser.parse(any(), anyString(), anyString())).thenReturn(parsed);
    when(uploadProperties.getMaxVisionPages()).thenReturn(10);
    when(uploadProperties.getMaxTextChars()).thenReturn(30000);
    when(recognitionService.resolveSourceType(List.of(parsed))).thenReturn("TXT");
    when(recognitionService.enqueueDirectExtraction(paper, 1)).thenReturn(
        new PaperUploadResult(PAPER_ID, 1, RecognitionStatus.NONE,
            ProcessingPath.DIRECT_EXTRACTION, false, "ok"));
    lenient().doAnswer(invocation -> {
      Runnable action = invocation.getArgument(0);
      action.run();
      return null;
    }).when(transactionalExecutor).run(any());

    PaperInputProcessingService service = new PaperInputProcessingService(
        resumeRepository,
        inputFileRepository,
        recognitionService,
        recognitionProducer,
        storageService,
        contentTypeDetectionService,
        List.of(documentParser),
        transactionalExecutor,
        uploadProperties
    );

    service.process(PAPER_ID, ATTEMPT_ID);

    verify(recognitionService).cleanupInputArtifacts(PAPER_ID, List.of());
    verify(recognitionService).createPages(eq(paper), eq(List.of(parsed)), any());
    verify(recognitionService).enqueueDirectExtraction(paper, 1);
    verify(resumeRepository, org.mockito.Mockito.atLeast(3))
        .heartbeatInputProcessing(eq(PAPER_ID), eq(ATTEMPT_ID), any());
  }
}
