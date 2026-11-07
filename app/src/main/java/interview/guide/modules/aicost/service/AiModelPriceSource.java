package interview.guide.modules.aicost.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.aicost.AiCostProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelPriceSource {

  private final AiCostProperties properties;
  private final ObjectMapper objectMapper;

  public record PriceSeed(
      String provider,
      String model,
      String modality,
      BigDecimal inputPricePer1m,
      BigDecimal outputPricePer1m,
      BigDecimal cachedPricePer1m
  ) {
  }

  public record PriceSeeds(
      List<PriceSeed> seeds,
      String source
  ) {
  }

  public List<PriceSeed> load() {
    return loadWithSource().seeds();
  }

  public PriceSeeds loadWithSource() {
    String syncUrl = properties.getPrices().getSyncUrl();
    if (syncUrl != null && !syncUrl.isBlank()) {
      try {
        String body = RestClient.create().get().uri(syncUrl).retrieve().body(String.class);
        return new PriceSeeds(parseMaps(objectMapper.readValue(body,
            new TypeReference<List<Map<String, Object>>>() {
            })), "external");
      } catch (Exception e) {
        log.warn("[AiModelPriceSource] 拉取外部价格失败，回退内置价目表: url={}", syncUrl, e);
      }
    }
    String localPath = properties.getPrices().getLocalPath();
    if (localPath != null && !localPath.isBlank()) {
      try {
        return new PriceSeeds(parseMaps(loadYaml(new FileSystemResource(localPath))), "local");
      } catch (Exception e) {
        log.warn("[AiModelPriceSource] 读取本地价目表失败，回退内置价目表: path={}", localPath, e);
      }
    }
    return new PriceSeeds(parseMaps(loadBundled()), "bundled");
  }

  private List<Map<String, Object>> loadBundled() {
    return loadYaml(new ClassPathResource("prices/ai-model-prices.yml"));
  }

  private List<Map<String, Object>> loadYaml(Resource resource) {
    YamlMapFactoryBean yaml = new YamlMapFactoryBean();
    yaml.setResources(resource);
    Map<String, Object> root = yaml.getObject();
    Object prices = root == null ? null : root.get("prices");
    if (prices instanceof List<?> list) {
      List<Map<String, Object>> result = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> cast = (Map<String, Object>) map;
          result.add(cast);
        }
      }
      return result;
    }
    return List.of();
  }

  private List<PriceSeed> parseMaps(List<Map<String, Object>> items) {
    if (items == null) {
      return List.of();
    }
    List<PriceSeed> seeds = new ArrayList<>();
    for (Map<String, Object> item : items) {
      String provider = str(item.get("provider"));
      String model = str(item.get("model"));
      String modality = str(item.get("modality"));
      if (provider == null || model == null || modality == null) {
        continue;
      }
      seeds.add(new PriceSeed(
          provider, model, modality.toUpperCase(),
          decimal(item.get("input-price-per-1m")),
          decimal(item.get("output-price-per-1m")),
          decimal(item.get("cached-price-per-1m"))
      ));
    }
    return seeds;
  }

  private String str(Object value) {
    return value == null ? null : value.toString();
  }

  private BigDecimal decimal(Object value) {
    if (value == null) {
      return null;
    }
    return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
  }
}
