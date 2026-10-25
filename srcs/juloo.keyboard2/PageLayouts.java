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

  public static KeyboardData forEditor(android.content.Context context, String page, android.content.SharedPreferences preferences)
  {
    Resources resources = context.getResources();
    juloo.keyboard2.input.PageConfig config = PagePreferences.load(preferences);
    DeviceLocales locales = DeviceLocales.load(context);
    java.util.List<KeyboardData> mains = juloo.keyboard2.prefs.LayoutsPreference.load_from_preferences(resources, preferences);
    boolean wide = resources.getDisplayMetrics().widthPixels / resources.getDisplayMetrics().density >= Config.WIDE_DEVICE_THRESHOLD;
    Object stored = preferences.getAll().get(wide ? "current_layout_landscape" : "current_layout_portrait");
    int index = stored instanceof Integer ? (Integer)stored : 0;
    KeyboardData main = index >= 0 && index < mains.size() ? mains.get(index) : null;
    if (main == null && locales.default_ != null && locales.default_.default_layout != null)
      main = juloo.keyboard2.prefs.LayoutsPreference.layout_of_string(resources, locales.default_.default_layout);
    if (main == null) main = KeyboardData.load(resources, R.xml.miancak_qwerty);
    KeyboardData data;
    if (juloo.keyboard2.input.PageConfig.isText(page))
    {
      data = links(LayoutControls.normalize(complete(resources, main)), page, config);
      if (juloo.keyboard2.input.PageConfig.isVariant(page)) data = FnLayout.materialize(data, page.equals("zh_fn"));
    }
    else if (juloo.keyboard2.input.PageConfig.base(page).equals("numeric"))
    {
      data = editorDigits(base(resources, "numeric"), main, preferences);
      return juloo.keyboard2.input.PageConfig.isVariant(page) ? FnLayout.materialize(data, false) : data;
    }
    else if (juloo.keyboard2.input.PageConfig.base(page).equals("greek"))
      return editorDigits(base(resources, page), main, preferences);
    else data = base(resources, page, config);
    if (page.equals("emoji") || page.equals("clipboard")) return data;
    return LayoutModifier.prepare_layout(data, juloo.keyboard2.input.PageConfig.isVariant(page),
        juloo.keyboard2.prefs.ExtraKeysPreference.get_extra_keys(preferences),
        juloo.keyboard2.prefs.CustomExtraKeysPreference.get(preferences), locales.extra_keys());
  }
  private static KeyboardData editorDigits(KeyboardData data, KeyboardData main, android.content.SharedPreferences preferences)
  {
    final boolean inverse = "low_first".equals(preferences.getAll().get("numpad_layout"));
    final int script = KeyModifier.modify_numpad_script(main.numpad_script);
    return data.mapKeys(new KeyboardData.MapKeyValues() {
      public KeyValue apply(KeyValue key, boolean localized)
      {
        if (key.getKind() != KeyValue.Kind.Char) return key;
        char value = key.getChar();
        if (inverse) value = LayoutModifier.inverse_numpad_char(value);
        if (script != -1)
        {
          KeyValue mapped = ComposeKey.apply(script, value);
          if (mapped != null) return mapped;
        }
        return value == key.getChar() ? key : key.withChar(value);
      }
    });
  }
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
