package interview.guide.modules.resume.service;

import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.resume.document.AssetKind;
import interview.guide.modules.resume.document.AssetRole;
import interview.guide.modules.resume.document.DocAsset;
import interview.guide.modules.resume.model.PaperAssetEntity;
import interview.guide.modules.resume.repository.PaperAssetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("页资源代次写入")
class PaperAssetServiceGenerationTest {

  private final PaperAssetRepository repository = mock(PaperAssetRepository.class);
  private final FileStorageService storageService = mock(FileStorageService.class);
  private final PaperAssetService service =
      new PaperAssetService(repository, storageService);

  @Test
  @DisplayName("不同识别代次生成不同 assetKey 且不覆盖旧资源")
  void differentAttemptsCreateDifferentAssetKeys() {
    when(repository.findByPaperIdAndAssetKey(any(), anyString()))
        .thenReturn(Optional.empty());
    when(repository.save(any(PaperAssetEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(storageService.uploadBytes(any(), anyString(), anyString(), anyString()))
        .thenReturn("stored-key");

    DocAsset asset = new DocAsset(
        "p1a4", AssetKind.OPTION_ROW, AssetRole.OPTIONS,
        new byte[] {1, 2, 3}, "A.", "1x4", 4, null,
        new double[] {0.1, 0.2, 0.8, 0.1}, 100, 20);

    PaperAssetService.PageAssetWriteResult first =
        service.replacePageGeneration(1L, 1, List.of(asset), null, "attempt-one");
    PaperAssetService.PageAssetWriteResult second =
        service.replacePageGeneration(1L, 1, List.of(asset), null, "attempt-two");

    assertThat(first.originalKeyToVersionedKey().get("p1a4"))
        .isEqualTo("p1a4@attemptone");
    assertThat(second.originalKeyToVersionedKey().get("p1a4"))
        .isEqualTo("p1a4@attempttwo");
    assertThat(first.entities()).extracting(PaperAssetEntity::getSourceAttemptId)
        .containsExactly("attempt-one");
    assertThat(second.entities()).extracting(PaperAssetEntity::getSourceAttemptId)
        .containsExactly("attempt-two");
    verify(repository, never()).delete(any());
  }
}
