package interview.guide.modules.knowledgebase.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MarkdownSectionParser {
  private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*?)\\s*$");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
  private static final Pattern CLOSING_HEADING = Pattern.compile("[ \\t]+#+[ \\t]*$");

  public record Section(List<String> path, String body) {
    public Section {
      path = List.copyOf(path);
    }
  }

  public List<Section> parse(String content, boolean markdown) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    if (!markdown) {
      return List.of(new Section(List.of(), normalized.strip()));
    }
    List<Section> sections = new ArrayList<>();
    TreeMap<Integer, String> titles = new TreeMap<>();
    StringBuilder body = new StringBuilder();
    char fenceChar = 0;
    int fenceLength = 0;
    for (String line : normalized.split("\n", -1)) {
      Matcher fence = FENCE.matcher(line);
      if (fence.matches()) {
        String marker = fence.group(1);
        if (fenceChar == 0) {
          fenceChar = marker.charAt(0);
          fenceLength = marker.length();
        } else if (marker.charAt(0) == fenceChar && marker.length() >= fenceLength
            && fence.group(2).isBlank()) {
          fenceChar = 0;
        }
        body.append(line).append('\n');
        continue;
      }
      Matcher heading = HEADING.matcher(line);
      if (fenceChar == 0 && heading.matches()) {
        String title = CLOSING_HEADING.matcher(heading.group(2)).replaceFirst("").strip();
        if (!title.isEmpty()) {
          addSection(sections, titles, body);
          int level = heading.group(1).length();
          titles.tailMap(level).clear();
          titles.put(level, title);
          continue;
        }
      }
      body.append(line).append('\n');
    }
    addSection(sections, titles, body);
    return sections.isEmpty()
        ? List.of(new Section(List.of(), normalized.strip())) : List.copyOf(sections);
  }

  private void addSection(List<Section> sections, TreeMap<Integer, String> titles,
                          StringBuilder body) {
    String text = body.toString().strip();
    if (!text.isEmpty()) {
      sections.add(new Section(new ArrayList<>(titles.values()), text));
    }
    body.setLength(0);
  }
}
