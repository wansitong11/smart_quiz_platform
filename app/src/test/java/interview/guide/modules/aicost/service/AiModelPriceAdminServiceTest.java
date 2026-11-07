package interview.guide.modules.aicost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.aicost.PriceSource;
import interview.guide.modules.aicost.dto.AiUsageDtos.PriceUpsertRequest;
import interview.guide.modules.aicost.model.AiModelPriceEntity;
import interview.guide.modules.aicost.repository.AiModelPriceRepository;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("模型价格行级编辑")
class AiModelPriceAdminServiceTest {

  @Mock
  private AiModelPriceRepository priceRepository;

  @Mock
  private AiModelPriceSource source;

  @Mock
  private LlmProviderConfigService providerConfigService;

  @Test
  @DisplayName("编辑手工价时直接更新原行，不新增行")
  void shouldUpdateManualPriceInPlace() {
    AiModelPriceEntity manual = entity(1L, PriceSource.MANUAL);
    when(priceRepository.findById(1L)).thenReturn(Optional.of(manual));
    when(priceRepository.save(any(AiModelPriceEntity.class))).thenReturn(manual);

    AiModelPriceAdminService service =
        new AiModelPriceAdminService(priceRepository, source, providerConfigService);
    var result = service.upsert(new PriceUpsertRequest(
        1L, null, null, null, new BigDecimal("1.25"), new BigDecimal("2.50"), null));

    assertThat(result.id()).isEqualTo(1L);
    assertThat(result.inputPricePer1m()).isEqualByComparingTo("1.25");
    assertThat(result.outputPricePer1m()).isEqualByComparingTo("2.50");
    ArgumentCaptor<AiModelPriceEntity> captor =
        ArgumentCaptor.forClass(AiModelPriceEntity.class);
    verify(priceRepository).save(captor.capture());
    assertThat(captor.getValue().getSource()).isEqualTo(PriceSource.MANUAL);
  }

  @Test
  @DisplayName("编辑自动价时结束旧行并新增手工价")
  void shouldReplaceAutoWithManualOnEdit() {
    AiModelPriceEntity auto = entity(2L, PriceSource.AUTO);
    when(priceRepository.findById(2L)).thenReturn(Optional.of(auto));
    when(priceRepository.save(any(AiModelPriceEntity.class))).thenAnswer(invocation ->
        invocation.getArgument(0));

    AiModelPriceAdminService service =
        new AiModelPriceAdminService(priceRepository, source, providerConfigService);
    var result = service.upsert(new PriceUpsertRequest(
        2L, null, null, null, new BigDecimal("0.50"), new BigDecimal("2.00"), null));

    assertThat(result.source()).isEqualTo("MANUAL");
    assertThat(auto.getEffectiveTo()).isNotNull();
  }

  @Test
  @DisplayName("新增价格只接受已配置 Provider 的模型")
  void shouldCreatePriceForConfiguredProviderModel() {
    when(providerConfigService.listProviders()).thenReturn(List.of(provider()));
    when(priceRepository.findActive(any(Instant.class))).thenReturn(List.of());
    when(priceRepository.save(any(AiModelPriceEntity.class))).thenAnswer(invocation ->
        invocation.getArgument(0));

    AiModelPriceAdminService service =
        new AiModelPriceAdminService(priceRepository, source, providerConfigService);
    var result = service.upsert(new PriceUpsertRequest(
        null, "dashscope", "qwen-plus", "TEXT",
        new BigDecimal("0.50"), new BigDecimal("2.00"), null));

    assertThat(result.provider()).isEqualTo("dashscope");
    assertThat(result.model()).isEqualTo("qwen-plus");
    assertThat(result.source()).isEqualTo("MANUAL");
  }

  @Test
  @DisplayName("新增价格拒绝与 Provider 不匹配的模型")
  void shouldRejectUnknownProviderModel() {
    when(providerConfigService.listProviders()).thenReturn(List.of(provider()));

    AiModelPriceAdminService service =
        new AiModelPriceAdminService(priceRepository, source, providerConfigService);

    assertThatThrownBy(() -> service.upsert(new PriceUpsertRequest(
        null, "dashscope", "unknown-model", "TEXT",
        new BigDecimal("0.50"), new BigDecimal("2.00"), null)))
        .hasMessageContaining("模型与所选 Provider / 模态不匹配");
  }

  private ProviderDTO provider() {
    return ProviderDTO.builder()
        .id("dashscope")
        .model("qwen-plus")
        .embeddingModel("text-embedding-v3")
        .supportsEmbedding(true)
        .supportsVision(true)
        .build();
  }

  private AiModelPriceEntity entity(Long id, PriceSource source) {
    return AiModelPriceEntity.builder()
        .id(id)
        .provider("dashscope")
        .model("qwen-plus")
        .modality("TEXT")
        .inputPricePer1m(new BigDecimal("1.00"))
        .outputPricePer1m(new BigDecimal("2.00"))
        .unit("TOKEN")
        .currency("USD")
        .source(source)
        .effectiveFrom(Instant.now())
        .build();
  }
}
