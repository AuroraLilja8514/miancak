package juloo.keyboard2;

import android.content.res.Resources;

/** Stable logical coordinates are edited before responsive presentation. */
public final class PageLayouts
{
  private PageLayouts() {}
  /** Resolved links are part of the layout definition, before user overrides. */
  public static KeyboardData links(KeyboardData data, String page, juloo.keyboard2.input.PageConfig config)
  {
    if (!juloo.keyboard2.input.PageConfig.base(page).equals("zh")) return data;
    KeyValue entry = config.enabled.contains(config.phoneticEntry)
      ? KeyValue.getKeyByName("switch_" + config.phoneticEntry) : null;
    return data.mapKeys(key -> KeyValue.getKeyByName("space").equals(key.keys[0])
        ? key.withKeyValue(1, entry) : key);
  }
  public static KeyboardData base(Resources resources, String page)
  { return base(resources, page, new juloo.keyboard2.input.PageConfig(null, null)); }
  public static KeyboardData base(Resources resources, String page, juloo.keyboard2.input.PageConfig config)
  {
    if (page.startsWith("symbols/"))
    {
      if (!juloo.keyboard2.input.SymbolCatalog.validScope(page)) throw new IllegalArgumentException(page);
      String[] parts = page.split("/");
      return symbols(resources, new juloo.keyboard2.input.SymbolState(parts[1], Integer.parseInt(parts[2])));
    }
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
    data = links(data, page, config);
    if (page.equals("symbols")) return SymbolLayouts.build(data, new juloo.keyboard2.input.SymbolState());
    return variant ? FnLayout.materialize(data, chinese) : data;
  }
  public static KeyboardData symbols(Resources resources, juloo.keyboard2.input.SymbolState state)
  {
    return SymbolLayouts.build(LayoutControls.normalize(complete(resources,
        KeyboardData.load(resources, R.xml.miancak_symbols))), state);
  }
  public static boolean editable(String id)
  { return juloo.keyboard2.input.PageConfig.IDS.contains(id) || juloo.keyboard2.input.SymbolCatalog.validScope(id); }
  public static KeyboardData complete(Resources resources, KeyboardData data)
  {
    if (data.bottom_row)
      try { data = data.insert_row(KeyboardData.load_row(resources, R.xml.bottom_row), data.rows.size()); }
      catch (Exception error) { throw new IllegalStateException(error); }
    return data.with_bottom_row(false);
  }
  /** Apply the configured digit order/script before deriving its Fn values. */
  public static KeyboardData numeric(Resources resources, boolean variant, KeyboardData main)
  {
    KeyboardData data = LayoutModifier.modify_numpad(base(resources, "numeric"), main);
    return variant ? FnLayout.materialize(data, false) : data;
  }
}
