package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.PageSource;
import interview.guide.modules.resume.model.PageStatus;
import interview.guide.modules.resume.model.PaperPageEntity;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.RecognitionStatus;
import interview.guide.modules.resume.repository.PaperPageRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文本文档逻辑页自举")
class PaperTextPageBootstrapperTest {

  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private PaperPageRepository pageRepository;

  private PaperTextPageBootstrapper bootstrapper;

  @BeforeEach
  void setUp() {
    bootstrapper = new PaperTextPageBootstrapper(resumeRepository, pageRepository);
  }

  @Test
  @DisplayName("有正文但没有页记录时创建一页 READY 文本页")
  void shouldCreateReadyTextPage() {
    PracticePaperEntity paper = paper();
    when(resumeRepository.findById(21L)).thenReturn(Optional.of(paper));
    when(pageRepository.findByPaperIdOrderByPageNoAsc(21L)).thenReturn(List.of());

    bootstrapper.ensureReadyTextPage(21L, "1. 已知集合 A，求交集。");

    ArgumentCaptor<PaperPageEntity> pageCaptor = ArgumentCaptor.forClass(PaperPageEntity.class);
    verify(pageRepository).save(pageCaptor.capture());
    PaperPageEntity page = pageCaptor.getValue();
    assertThat(page.getPaperId()).isEqualTo(21L);
    assertThat(page.getPageNo()).isEqualTo(1);
    assertThat(page.getSource()).isEqualTo(PageSource.TEXT);
    assertThat(page.getStatus()).isEqualTo(PageStatus.READY);
    assertThat(page.isLogicalPage()).isTrue();
    assertThat(page.getTextContent()).contains("已知集合");
    assertThat(paper.getPageCount()).isEqualTo(1);
    assertThat(paper.getRecognitionStatus()).isEqualTo(RecognitionStatus.READY);
    verify(resumeRepository).save(paper);
  }

  @Test
  @DisplayName("已有页面时不重复创建")
  void shouldBeIdempotentWhenPagesExist() {
    PracticePaperEntity paper = paper();
    PaperPageEntity existing = new PaperPageEntity();
    existing.setPaperId(21L);
    existing.setPageNo(1);
    existing.setStatus(PageStatus.READY);
    when(resumeRepository.findById(21L)).thenReturn(Optional.of(paper));
    when(pageRepository.findByPaperIdOrderByPageNoAsc(21L)).thenReturn(List.of(existing));

    bootstrapper.ensureReadyTextPage(21L, "1. 已有页面。");

    verify(pageRepository, never()).save(org.mockito.ArgumentMatchers.any());
    verify(resumeRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  private PracticePaperEntity paper() {
    PracticePaperEntity paper = new PracticePaperEntity();
    paper.setId(21L);
    paper.setRecognitionStatus(RecognitionStatus.NONE);
    return paper;
  }
}
