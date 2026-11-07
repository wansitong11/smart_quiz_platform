package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 为带正文但没有页记录的文本文档补一页逻辑页。
 *
 * <p>TXT、DOCX 和带文字层 PDF 在上传阶段只保存合并正文；新结构切题依赖
 * {@code paper_pages}。该模块把历史正文无损映射进统一页模型，使后续切题、
 * 层级树和答案键流程只有一条入口。</p>
 */
@Service
@RequiredArgsConstructor
public class PaperTextPageBootstrapper {

  private final ResumeRepository resumeRepository;
  private final PaperPageRepository pageRepository;

  @Transactional
  public void ensureReadyTextPage(Long paperId, String paperText) {
    if (paperText == null || paperText.isBlank()) {
      throw new BusinessException(ErrorCode.PAPER_PARSE_FAILED, "试卷正文为空，无法创建文本页");
    }
    PracticePaperEntity paper = resumeRepository.findById(paperId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_NOT_FOUND, "试卷不存在"));
    List<PaperPageEntity> pages = pageRepository.findByPaperIdOrderByPageNoAsc(paperId);
    if (!pages.isEmpty()) {
      return;
    }

    LocalDateTime now = LocalDateTime.now();
    PaperPageEntity page = new PaperPageEntity();
    page.setPaperId(paperId);
    page.setPageNo(1);
    page.setSource(PageSource.TEXT);
    page.setStatus(PageStatus.READY);
    page.setTextContent(paperText.strip());
    page.setHasFigure(false);
    page.setLogicalPage(true);
    page.setAttempt(0);
    page.setUpdatedAt(now);
    pageRepository.save(page);

    paper.setPageCount(1);
    paper.setRecognitionStatus(RecognitionStatus.READY);
    paper.setRecognitionError(null);
    paper.setRecognitionAttemptId(null);
    paper.setRecognitionUpdatedAt(now);
    resumeRepository.save(paper);
  }
}
