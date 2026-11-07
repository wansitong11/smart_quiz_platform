package interview.guide.modules.resume.service;

import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 页识别终态与内容代次的原子写入。
 */
@Service
@RequiredArgsConstructor
public class PaperRecognitionStateService {

  private final PaperPageRepository pageRepository;
  private final ResumeRepository resumeRepository;

  @Transactional
  public boolean markPageReadyAndAdvanceRevision(
      Long paperId,
      int pageNo,
      String attemptId,
      String text,
      boolean hasFigure,
      String notes,
      String model,
      String assetsJson,
      LocalDateTime now
  ) {
    int updated = pageRepository.markPageReadyIfClaimed(
        paperId, pageNo, attemptId, text, hasFigure, notes, model, assetsJson, now);
    if (updated == 1) {
      resumeRepository.incrementContentRevision(paperId, now);
    }
    return updated == 1;
  }
}
