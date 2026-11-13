package interview.guide.rag;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.knowledgebase.service.MarkdownSectionParser;
import interview.guide.modules.knowledgebase.service.SectionChunker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContinuityEvalCorpusTest {
  @Test
  @DisplayName("固定 48 题的条件和指代证据确实跨相邻生产分块")
  void fixedSamplesHaveRealNeighborBoundaries() {
    var fixtures = Map.of("continuity-conditions.md", ContinuityEvalCorpus.conditionFixture(),
        "continuity-reference.txt", ContinuityEvalCorpus.referenceFixture(),
        "continuity-facts.md", ContinuityEvalCorpus.factsFixture());
    var samples = ContinuityEvalCorpus.samples();
    assertThat(samples).hasSize(48);
    assertThat(samples.stream().map(RagEvalSample::id).distinct()).hasSize(48);
    for (String category : List.of("condition", "reference", "fact", "outside")) {
      assertThat(samples.stream().filter(sample -> sample.tags().contains(category))).hasSize(12);
    }
    var parser = new MarkdownSectionParser();
    var chunker = new SectionChunker();
    Map<String, List<String>> fixtureChunks = new java.util.HashMap<>();
    for (var fixture : fixtures.entrySet()) {
      List<String> chunks = new ArrayList<>();
      for (var section : parser.parse(fixture.getValue(), fixture.getKey().endsWith(".md"))) {
        chunks.addAll(chunker.split(section.body(), 800, 10000));
      }
      fixtureChunks.put(fixture.getKey(), chunks);
    }
    for (RagEvalSample sample : samples) {
      String fixture = fixtures.get(sample.fixture());
      assertThat(fixture).isNotNull();
      for (var evidence : sample.expectedEvidence()) {
        assertThat(fixture).as(sample.id()).contains(evidence.text());
      }
      if (!sample.tags().contains("condition") && !sample.tags().contains("reference")) continue;
      List<String> chunks = fixtureChunks.get(sample.fixture());
      int first = indexContaining(chunks, sample.expectedEvidence().getFirst().text());
      int second = indexContaining(chunks, sample.expectedEvidence().getLast().text());
      assertThat(first).as(sample.id() + " 的前置证据").isGreaterThanOrEqualTo(0);
      assertThat(second).as(sample.id() + " 的后置证据").isEqualTo(first + 1);
    }
  }

  private int indexContaining(List<String> chunks, String anchor) {
    for (int index = 0; index < chunks.size(); index++) {
      if (chunks.get(index).contains(anchor)) return index;
    }
    return -1;
  }
}
