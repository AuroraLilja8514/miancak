package juloo.keyboard2.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Layout IDs own input policy and fixed links. Fn never consults engine state. */
public final class PageConfig
{
  public static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
      "zh", "zh_fn", "en", "en_fn", "symbols", "numeric", "numeric_fn", "greek", "greek_fn",
      "emoji", "clipboard", "pinyin", "pinyin_finals", "zhuyin"));
  public final List<String> order;
  public final Set<String> enabled;
  public final String phoneticEntry;
  public PageConfig(String order, String enabled)
  { this(order, enabled, null); }
  public PageConfig(String order, String enabled, String phoneticEntry)
  {
    this.phoneticEntry = "zhuyin".equals(phoneticEntry) ? "zhuyin" : "pinyin";
    LinkedHashSet<String> sorted = valid(order);
    sorted.addAll(IDS);
    this.order = Collections.unmodifiableList(new ArrayList<>(sorted));
    LinkedHashSet<String> on = enabled == null ? new LinkedHashSet<>(IDS) : valid(enabled);
    on.add("zh"); on.add("en");
    for (String id : Arrays.asList("zh", "en", "numeric", "greek"))
      if (on.contains(id) || on.contains(id + "_fn")) { on.add(id); on.add(id + "_fn"); }
    this.enabled = Collections.unmodifiableSet(on);
  }

  private static LinkedHashSet<String> valid(String csv)
  {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (csv != null)
      for (String id : csv.split(",")) if (IDS.contains(id)) result.add(id);
    return result;
  }
  public static boolean isVariant(String id) { return id.endsWith("_fn") && IDS.contains(id); }
  public static String base(String id) { return isVariant(id) ? id.substring(0, id.length() - 3) : id; }
  public static boolean isText(String id) { return base(id).equals("zh") || base(id).equals("en"); }
  public static InputRouter.Mode mode(String id)
  { return base(id).equals("zh") ? InputRouter.Mode.CHINESE : InputRouter.Mode.ENGLISH; }
  public String fn(String id)
  {
    String target = isVariant(id) ? base(id) : id + "_fn";
    return enabled.contains(target) ? target : "";
  }
  public static String languageTarget(String id)
  {
    if (isText(id)) return (base(id).equals("zh") ? "en" : "zh") + (isVariant(id) ? "_fn" : "");
    return "zh";
  }
  public static String returnTarget(String id) { return isVariant(id) ? base(id) : "zh"; }
  public List<String> visible()
  {
    ArrayList<String> result = new ArrayList<>();
    for (String id : order) if (enabled.contains(id)) result.add(id);
    return Collections.unmodifiableList(result);
  }
  public PageConfig enable(String id, boolean value)
  {
    LinkedHashSet<String> next = new LinkedHashSet<>(enabled);
    String base = base(id);
    for (String member : Arrays.asList(base, base + "_fn"))
      if (IDS.contains(member)) { if (value) next.add(member); else next.remove(member); }
    return new PageConfig(csv(order), csv(next), phoneticEntry);
  }
  public PageConfig move(String id, int delta)
  {
    ArrayList<String> next = new ArrayList<>(order);
    int from = next.indexOf(id), to = from + delta;
    if (from >= 0 && to >= 0 && to < next.size()) Collections.swap(next, from, to);
    return new PageConfig(csv(next), csv(enabled), phoneticEntry);
  }
  public PageConfig withPhoneticEntry(String id)
  {
    if (!id.equals("pinyin") && !id.equals("zhuyin")) throw new IllegalArgumentException(id);
    return new PageConfig(csv(order), csv(enabled), id).enable(id, true);
  }
  public static String csv(Iterable<String> values)
  {
    StringBuilder text = new StringBuilder();
    for (String value : values) { if (text.length() > 0) text.append(','); text.append(value); }
    return text.toString();
  }
}
