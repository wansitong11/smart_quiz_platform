package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库解析服务测试")
class KnowledgeBaseParseServiceTest {

  @Mock
  private DocumentParseService documentParseService;
  @Mock
  private ContentTypeDetectionService contentTypeDetectionService;
  @Mock
  private FileStorageService storageService;
  @Mock
  private KnowledgeBaseContentExtractor contentExtractor;

  private KnowledgeBaseParseService service;

  @BeforeEach
  void setUp() {
    service = new KnowledgeBaseParseService(
        documentParseService, contentTypeDetectionService, storageService, contentExtractor);
  }

  @Test
  @DisplayName("下载字节后交给知识库正文抽取器")
  void shouldDelegateDownloadedBytesToExtractor() {
    byte[] bytes = new byte[] {1, 2, 3};
    when(storageService.downloadFile("kb/1")).thenReturn(bytes);
    when(contentExtractor.extractText(bytes, "a.png")).thenReturn("识别文字");

    String text = service.downloadAndParseContent("kb/1", "a.png");

    assertThat(text).isEqualTo("识别文字");
    verify(contentExtractor).extractText(bytes, "a.png");
    verify(documentParseService, never()).downloadAndParseContent(any(), any(), any());
  }

  @Test
  @DisplayName("下载为空时抛出业务异常")
  void shouldThrowWhenDownloadedBytesEmpty() {
    when(storageService.downloadFile("kb/empty")).thenReturn(new byte[0]);

    assertThatThrownBy(() -> service.downloadAndParseContent("kb/empty", "a.png"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("下载文件失败");
    verify(contentExtractor, never()).extractText(any(), eq("a.png"));
  }
}
