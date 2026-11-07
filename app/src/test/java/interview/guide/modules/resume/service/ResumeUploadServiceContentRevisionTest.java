package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.resume.listener.PaperExtractionStreamProducer;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("重新分析内容版本")
class ResumeUploadServiceContentRevisionTest {

  @Mock
  private OwnerScope ownerScope;
  @Mock
  private ResumeParseService parseService;
  @Mock
  private FileStorageService storageService;
  @Mock
  private ResumePersistenceService persistenceService;
  @Mock
  private AppConfigProperties appConfig;
  @Mock
  private FileValidationService fileValidationService;
  @Mock
  private PaperExtractionStreamProducer paperExtractionProducer;
  @Mock
  private PaperRecognitionService recognitionService;
  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private TransactionalExecutor transactionalExecutor;
  @Mock
  private PaperContentGuard paperContentGuard;

  @Test
  @DisplayName("重新分析在重置状态时原子推进内容代次")
  void reanalyzeAdvancesContentRevision() {
    Long paperId = 7L;
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setId(paperId);
    when(ownerScope.effectiveOwnerId()).thenReturn(1L);
    when(resumeRepository.findByIdScoped(paperId, 1L)).thenReturn(Optional.of(paper));
    when(paperExtractionProducer.sendExtractTask(paperId)).thenReturn(true);
    doAnswer(invocation -> {
      Runnable action = invocation.getArgument(1);
      action.run();
      return null;
    }).when(paperContentGuard).withPaperLock(eq(paperId), any(Runnable.class));
    doAnswer(invocation -> {
      Runnable action = invocation.getArgument(0);
      action.run();
      return null;
    }).when(transactionalExecutor).run(any(Runnable.class));

    service().reanalyze(paperId);

    verify(paperContentGuard).prepareForContentChange(paperId);
    verify(resumeRepository).save(paper);
    verify(resumeRepository).incrementContentRevision(eq(paperId), any(LocalDateTime.class));
    verify(paperExtractionProducer).sendExtractTask(paperId);
    verify(resumeRepository).resetAnalysisRecoveryCount(anyLong());
  }

  private ResumeUploadService service() {
    return new ResumeUploadService(
        ownerScope,
        parseService,
        storageService,
        persistenceService,
        appConfig,
        fileValidationService,
        paperExtractionProducer,
        recognitionService,
        resumeRepository,
        transactionalExecutor,
        paperContentGuard
    );
  }
}
