package juloo.keyboard2;

import android.content.res.Resources;

/** Stable logical coordinates are edited before responsive presentation. */
public final class PageLayouts
{
  private PageLayouts() {}
  public static KeyboardData base(Resources resources, String page)
  {
    boolean variant = juloo.keyboard2.input.PageConfig.isVariant(page);
    boolean chinese = page.equals("zh_fn");
    page = juloo.keyboard2.input.PageConfig.base(page);
    int id;
    switch (page)
    {
      case "zh": case "en": id = R.xml.miancak_qwerty; break;
      case "numeric": id = R.xml.numeric; break;
      case "symbols": id = R.xml.miancak_symbols; break;
      case "greek": id = R.xml.greekmath; break;
      case "emoji": id = R.xml.emoji_bottom_row; break;
      case "clipboard": id = R.xml.clipboard_bottom_row; break;
      case "pinyin": id = R.xml.miancak_pinyin; break;
      case "pinyin_finals": id = R.xml.miancak_pinyin_finals; break;
      case "zhuyin": id = R.xml.miancak_zhuyin; break;
      default: throw new IllegalArgumentException(page);
    }
    KeyboardData data = LayoutControls.normalize(complete(resources, KeyboardData.load(resources, id)));
    return variant ? FnLayout.materialize(data, chinese) : data;
  }
  public static KeyboardData complete(Resources resources, KeyboardData data)
  {
    if (data.bottom_row)
      try { data = data.insert_row(KeyboardData.load_row(resources, R.xml.bottom_row), data.rows.size()); }
      catch (Exception error) { throw new IllegalStateException(error); }
    return data.with_bottom_row(false);
  }
}
