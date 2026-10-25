package juloo.keyboard2;

import android.content.SharedPreferences;
import juloo.keyboard2.input.PageConfig;

public final class PagePreferences
{
  private PagePreferences() {}
  public static PageConfig load(SharedPreferences preferences)
  {
    return new PageConfig(string(preferences, "pages_order"), string(preferences, "pages_enabled"));
  }
  private static String string(SharedPreferences preferences, String key)
  { Object value = preferences.getAll().get(key); return value instanceof String ? (String)value : null; }
  public static void save(SharedPreferences preferences, PageConfig config)
  {
    preferences.edit().putString("pages_order", PageConfig.csv(config.order))
      .putString("pages_enabled", PageConfig.csv(config.enabled)).apply();
  }
  /** Preserve the exact v1 settings before retiring its arbitrary Fn targets. */
  public static void migrate(SharedPreferences preferences)
  {
    java.util.Map<String, ?> values = preferences.getAll();
    Object version = values.get("pages_version");
    if (version instanceof Integer && (Integer)version >= 2) return;
    try
    {
      org.json.JSONObject previous = new org.json.JSONObject().put("version", 1)
        .put("order", values.get("pages_order"))
        .put("enabled", values.get("pages_enabled"))
        .put("fn_zh", values.get("pages_fn_zh"))
        .put("fn_en", values.get("pages_fn_en"))
        .put("pages_raw", values.containsKey(PageKeyOverrides.KEY) ? values.get(PageKeyOverrides.KEY) : "{}");
      PageConfig config = load(preferences);
      SharedPreferences.Editor edit = preferences.edit();
      if (!preferences.contains("pages_v1_backup")) edit.putString("pages_v1_backup", previous.toString());
      edit.putInt("pages_version", 2).putString("pages_order", PageConfig.csv(config.order))
        .putString("pages_enabled", PageConfig.csv(config.enabled))
        .remove("pages_fn_zh").remove("pages_fn_en").apply();
    }
    catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
  }
  public static int label(String id)
  {
    switch (id)
    {
      case "zh": return R.string.page_zh;
      case "en": return R.string.page_en;
      case "zh_fn": return R.string.page_zh_fn;
      case "en_fn": return R.string.page_en_fn;
      case "greek_fn": return R.string.page_greek_fn;
      case "numeric_fn": return R.string.page_numeric_fn;
      case "symbols": return R.string.page_symbols;
      case "numeric": return R.string.page_numeric;
      case "greek": return R.string.page_greek;
      case "emoji": return R.string.page_emoji;
      case "clipboard": return R.string.page_clipboard;
      case "pinyin": return R.string.page_pinyin;
      case "pinyin_finals": return R.string.page_pinyin_finals;
      case "zhuyin": return R.string.page_zhuyin;
      default: throw new IllegalArgumentException(id);
    }
  }
}
