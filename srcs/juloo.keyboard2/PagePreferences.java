package juloo.keyboard2;

import android.content.SharedPreferences;
import juloo.keyboard2.input.PageConfig;

public final class PagePreferences
{
  private PagePreferences() {}
  public static PageConfig load(SharedPreferences preferences)
  {
    return new PageConfig(preferences.getString("pages_order", null),
        preferences.getString("pages_enabled", null),
        preferences.getString("pages_fn_zh", null), preferences.getString("pages_fn_en", null));
  }
  public static void save(SharedPreferences preferences, PageConfig config)
  {
    preferences.edit().putString("pages_order", PageConfig.csv(config.order))
      .putString("pages_enabled", PageConfig.csv(config.enabled))
      .putString("pages_fn_zh", config.chineseFn).putString("pages_fn_en", config.englishFn).apply();
  }
  public static int label(String id)
  {
    switch (id)
    {
      case "zh": return R.string.page_zh;
      case "en": return R.string.page_en;
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
