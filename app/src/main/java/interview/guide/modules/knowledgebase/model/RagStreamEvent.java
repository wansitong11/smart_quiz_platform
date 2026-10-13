package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * RAG 问答流式事件。
 */
public record RagStreamEvent(String name, Object data) {

  public static final String TOOL = "tool";
  public static final String ANSWER = "answer";
  public static final String SOURCES = "sources";

  public static RagStreamEvent tool(String name, String status) {
    return new RagStreamEvent(TOOL, new ToolEvent(name, status));
  }

  public static RagStreamEvent answer(String chunk) {
    return new RagStreamEvent(ANSWER, chunk);
  }

  public static RagStreamEvent sources(List<Source> sources) {
    return new RagStreamEvent(SOURCES, List.copyOf(sources));
  }

  public String answerText() {
    return ANSWER.equals(name) && data instanceof String chunk ? chunk : "";
  }

  public String persistenceText() {
    if (ANSWER.equals(name)) {
      return answerText();
    }
    if (!SOURCES.equals(name) || !(data instanceof List<?> values)) {
      return "";
    }

    StringBuilder block = new StringBuilder("\n\n**出处**\n");
    for (Object value : values) {
      if (value instanceof Source source) {
        block.append("- 《").append(source.name()).append("》");
        if (source.segments() > 1) {
          block.append("（").append(source.segments()).append(" 段）");
        }
        block.append('\n');
      }
    }
    return block.length() == "\n\n**出处**\n".length() ? "" : block.toString();
  }

  public record ToolEvent(String name, String status) {
  }

  public record Source(Long kbId, String name, int segments) {
  }
}
