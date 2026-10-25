package juloo.keyboard2;

import android.content.SharedPreferences;
import android.content.res.Resources;
import java.util.ArrayList;
import java.util.Iterator;
import juloo.keyboard2.input.PageConfig;
import org.json.JSONObject;

/** Versioned, bounded, exact per-position actions. Invalid imports are atomic. */
public final class PageKeyOverrides
{
  public static final String KEY = "page_customization";
  public static final int MAX_BYTES = 262144;
  private final JSONObject pages;
  public PageKeyOverrides() { pages = new JSONObject(); }
  private PageKeyOverrides(JSONObject pages) { this.pages = pages; }
  public static PageKeyOverrides load(SharedPreferences preferences)
  {
    try { return new PageKeyOverrides(new JSONObject(preferences.getString(KEY, "{}"))); }
    catch (Exception error) { return new PageKeyOverrides(); }
  }
  public PageKeyOverrides copy()
  {
    try { return new PageKeyOverrides(new JSONObject(pages.toString())); }
    catch (Exception impossible) { throw new AssertionError(impossible); }
  }
  public String snapshot() { return pages.toString(); }
  public static PageKeyOverrides restore(String snapshot) throws Exception
  { return new PageKeyOverrides(new JSONObject(snapshot)); }
  public String action(String page, int row, int col, int direction, boolean shift)
  {
    JSONObject keys = pages.optJSONObject(page);
    String position = position(row, col, direction, shift);
    return keys == null || !keys.has(position) ? null : keys.optString(position);
  }
  public static String position(int row, int col, int direction, boolean shift)
  { return row + ":" + col + ":" + direction + (shift ? ":shift" : ""); }
  public void set(String page, int row, int col, int direction, boolean shift, String value) throws Exception
  {
    if (value != null && !value.isEmpty()) validateAction(value);
    JSONObject keys = pages.optJSONObject(page);
    if (keys == null) { keys = new JSONObject(); pages.put(page, keys); }
    String position = position(row, col, direction, shift);
    if (value == null) keys.remove(position); else keys.put(position, value);
  }
  public void reset(String page) { pages.remove(page); }
  public static String literal(String text)
  { return ":literal:'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"; }
  public static void validateAction(String text) throws Exception
  {
    if (text.length() > 2048) throw new IllegalArgumentException("Action is too long");
    parse(text);
  }
  private static KeyValue parse(String text) throws Exception
  { KeyValue special = KeyValue.getSpecialKeyByName(text); return special != null ? special : KeyValueParser.parse(text); }
  public KeyboardData apply(String page, KeyboardData data)
  {
    ArrayList<KeyboardData.Row> rows = new ArrayList<>();
    for (int r = 0; r < data.rows.size(); r++)
    {
      ArrayList<KeyboardData.Key> keys = new ArrayList<>();
      for (int c = 0; c < data.rows.get(r).keys.size(); c++)
      {
        KeyboardData.Key key = data.rows.get(r).keys.get(c);
        KeyboardData.Key original = key;
        for (int direction = 0; direction < 9; direction++)
        {
          // Legacy per-page changes remain stored/backed up, but may not make
          // shared controls drift. Shared editing is implemented separately.
          if (LayoutControls.shared(original, direction)) continue;
          String normal = action(page, r, c, direction, false), shifted = action(page, r, c, direction, true);
          if (normal == null && shifted == null) continue;
          try
          {
            KeyValue value = normal == null ? key.keys[direction] : normal.isEmpty() ? null : parse(normal);
            if (shifted != null) value = KeyValue.layered(value, shifted.isEmpty() ? null : parse(shifted));
            key = key.withKeyValue(direction, value);
          }
          catch (Exception ignored) { /* Keep usable built-in action if stored data is damaged. */ }
        }
        keys.add(key);
      }
      rows.add(data.rows.get(r).with_keys(keys));
    }
    return LayoutControls.normalize(data.with_rows(rows));
  }
  public void save(SharedPreferences preferences)
  {
    try
    {
      String before = export(preferences);
      JSONObject next = new JSONObject(before).put("pages", pages);
      if (next.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
        throw new IllegalArgumentException("Layout settings exceed the 256 KiB file limit");
      preferences.edit().putString(KEY + "_previous_bundle", before)
        .putString(KEY + "_previous", preferences.getString(KEY, "{}"))
        .putString(KEY, pages.toString()).apply();
    }
    catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalStateException(error); }
  }
  public static String export(SharedPreferences preferences) throws Exception
  {
    PageConfig config = PagePreferences.load(preferences);
    return new JSONObject().put("version", 2).put("pages", load(preferences).pages)
      .put("order", PageConfig.csv(config.order)).put("enabled", PageConfig.csv(config.enabled)).toString(2);
  }
  public static void importJson(SharedPreferences preferences, Resources resources, String text) throws Exception
  {
    if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("File is too large");
    JSONObject root = new JSONObject(text);
    int version = root.getInt("version");
    if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported layout version");
    JSONObject pages = root.getJSONObject("pages");
    Iterator<String> ids = pages.keys();
    while (ids.hasNext())
    {
      String id = ids.next();
      if (!PageConfig.IDS.contains(id)) throw new IllegalArgumentException("Unknown page: " + id);
      KeyboardData layout = PageLayouts.base(resources, id);
      JSONObject keys = pages.getJSONObject(id); Iterator<String> positions = keys.keys();
      while (positions.hasNext())
      {
        String position = positions.next(); String[] parts = position.split(":");
        if (parts.length < 3 || parts.length > 4 || parts.length == 4 && !parts[3].equals("shift")) throw new IllegalArgumentException("Invalid key position");
        int row = Integer.parseInt(parts[0]), col = Integer.parseInt(parts[1]), direction = Integer.parseInt(parts[2]);
        if (row < 0 || row >= layout.rows.size() || col < 0 || col >= layout.rows.get(row).keys.size() || direction < 0 || direction > 8)
          throw new IllegalArgumentException("Key position is outside page");
        if (!(keys.get(position) instanceof String)) throw new IllegalArgumentException("Key action must be text");
        String action = keys.getString(position); if (!action.isEmpty()) validateAction(action);
      }
    }
    PageConfig config = new PageConfig(root.getString("order"), root.getString("enabled"));
    // Validate everything before committing any of the configuration.
    preferences.edit().putString(KEY + "_previous", preferences.getString(KEY, "{}"))
      .putString(KEY + "_previous_bundle", export(preferences))
      .putString(KEY, pages.toString()).putString("pages_order", PageConfig.csv(config.order))
      .putString("pages_enabled", PageConfig.csv(config.enabled)).putInt("pages_version", 2)
      .putString(KEY + "_import_source", text).remove("pages_fn_zh").remove("pages_fn_en").apply();
  }
}
