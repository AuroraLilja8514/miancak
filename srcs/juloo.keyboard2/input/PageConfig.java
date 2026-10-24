package juloo.keyboard2.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Version-independent page IDs; language profiles share one physical layout. */
public final class PageConfig
{
  public static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
      "zh", "en", "symbols", "numeric", "greek", "emoji", "clipboard"));
  public final List<String> order;
  public final Set<String> enabled;
  public final String chineseFn, englishFn;

  public PageConfig(String order, String enabled, String chineseFn, String englishFn)
  {
    LinkedHashSet<String> sorted = valid(order);
    sorted.addAll(IDS);
    this.order = Collections.unmodifiableList(new ArrayList<>(sorted));
    LinkedHashSet<String> on = enabled == null ? new LinkedHashSet<>(IDS) : valid(enabled);
    on.add("zh"); on.add("en");
    this.enabled = Collections.unmodifiableSet(on);
    this.chineseFn = target(chineseFn == null ? "symbols" : chineseFn);
    this.englishFn = target(englishFn == null ? "numeric" : englishFn);
  }

  private static LinkedHashSet<String> valid(String csv)
  {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (csv != null)
      for (String id : csv.split(",")) if (IDS.contains(id)) result.add(id);
    return result;
  }
  private String target(String value)
  {
    return enabled.contains(value) && !isText(value) ? value : "";
  }
  public static boolean isText(String id) { return id.equals("zh") || id.equals("en"); }
  public String fn(InputRouter.Mode mode) { return mode == InputRouter.Mode.CHINESE ? chineseFn : englishFn; }
  public List<String> visible()
  {
    ArrayList<String> result = new ArrayList<>();
    for (String id : order) if (enabled.contains(id)) result.add(id);
    return Collections.unmodifiableList(result);
  }
  public PageConfig enable(String id, boolean value)
  {
    LinkedHashSet<String> next = new LinkedHashSet<>(enabled);
    if (value) next.add(id); else next.remove(id);
    return new PageConfig(csv(order), csv(next), chineseFn, englishFn);
  }
  public PageConfig move(String id, int delta)
  {
    ArrayList<String> next = new ArrayList<>(order);
    int from = next.indexOf(id), to = from + delta;
    if (from >= 0 && to >= 0 && to < next.size()) Collections.swap(next, from, to);
    return new PageConfig(csv(next), csv(enabled), chineseFn, englishFn);
  }
  public PageConfig withFn(InputRouter.Mode mode, String target)
  {
    return new PageConfig(csv(order), csv(enabled),
        mode == InputRouter.Mode.CHINESE ? target : chineseFn,
        mode == InputRouter.Mode.ENGLISH ? target : englishFn);
  }
  public static String csv(Iterable<String> values)
  {
    StringBuilder text = new StringBuilder();
    for (String value : values) { if (text.length() > 0) text.append(','); text.append(value); }
    return text.toString();
  }
}
