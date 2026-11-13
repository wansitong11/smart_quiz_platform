package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

class KnowledgeBaseQueryPropertiesTest {
  @Test
  @DisplayName("默认不开启未经校准的筛选，嵌套阈值包含零和一")
  void validatesDefaultsAndInclusiveBounds() {
    var properties = new KnowledgeBaseQueryProperties();
    assertThat(properties.getSearch().getRelevance().isEnabled()).isFalse();
    assertThat(properties.getSearch().getRelevance().getMinScore()).isEqualTo(.40);
    assertThat(properties.getSearch().getRelevance().getMaxScoreDrop()).isEqualTo(.20);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(properties)).isEmpty();
      properties.getSearch().getRelevance().setMinScore(0);
      properties.getSearch().getRelevance().setMaxScoreDrop(1);
      assertThat(factory.getValidator().validate(properties)).isEmpty();
    }
  }

  @Test
  @DisplayName("即使筛选关闭也拒绝空配置、非有限数和越界值")
  void rejectsInvalidNestedValues() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      for (double value : new double[] {-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY}) {
        var properties = new KnowledgeBaseQueryProperties();
        properties.getSearch().getRelevance().setMinScore(value);
        assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        properties.getSearch().getRelevance().setMinScore(.4);
        properties.getSearch().getRelevance().setMaxScoreDrop(value);
        assertThat(factory.getValidator().validate(properties)).isNotEmpty();
      }
      var properties = new KnowledgeBaseQueryProperties();
      properties.getSearch().setRelevance(null);
      assertThat(factory.getValidator().validate(properties)).isNotEmpty();
    }
  }

  @Test
  @DisplayName("配置键可绑定，非法配置在绑定校验阶段失败")
  void bindsKebabCaseAndRejectsInvalidConfiguration() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var handler = new ValidationBindHandler(new SpringValidatorAdapter(factory.getValidator()));
      var source = new MapConfigurationPropertySource(Map.of(
          "app.ai.rag.search.relevance.enabled", "true",
          "app.ai.rag.search.relevance.min-score", "0.45",
          "app.ai.rag.search.relevance.max-score-drop", "0.25"));
      var bound = new Binder(source).bind("app.ai.rag",
          Bindable.of(KnowledgeBaseQueryProperties.class), handler).get();
      assertThat(bound.getSearch().getRelevance().isEnabled()).isTrue();
      assertThat(bound.getSearch().getRelevance().getMinScore()).isEqualTo(.45);
      assertThat(bound.getSearch().getRelevance().getMaxScoreDrop()).isEqualTo(.25);
      source.put("app.ai.rag.search.relevance.min-score", "1.5");
      assertThatThrownBy(() -> new Binder(source).bind("app.ai.rag",
          Bindable.of(KnowledgeBaseQueryProperties.class), handler)).isInstanceOf(BindException.class);
    }
  }

  @Test
  @DisplayName("保守改写模板可用问题和历史渲染，不残留未解析变量")
  void rendersConservativeRewriteTemplate() throws Exception {
    String template = new DefaultResourceLoader().getResource(
        "classpath:prompts/knowledgebase-query-rewrite.st").getContentAsString(StandardCharsets.UTF_8);
    String rendered = new PromptTemplate(template).render(Map.of("question", "制作它的步骤",
        "history", "用户：扇形统计图"));
    assertThat(rendered).contains("制作它的步骤", "用户：扇形统计图", "问题短不是扩写理由")
        .doesNotContain("{question}", "{history}");
  }
}
