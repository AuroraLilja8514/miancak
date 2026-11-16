package juloo.keyboard2;

import android.content.res.Resources;
import android.view.KeyEvent;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class LayoutModifier
{
  static Config globalConfig;
  static KeyboardData.Row bottom_row;
  static KeyboardData.Row number_row_no_symbols;
  static KeyboardData.Row number_row_symbols;
  static KeyboardData num_pad;
  // Not used in this file but defined here for convenience.
  public static KeyboardData.Row split_middle_column;

  /** Update the layout according to the configuration.
   *  - Remove the switching key if it isn't needed
   *  - Remove "localized" keys from other locales (not in 'extra_keys')
   *  - Replace the action key to show the right label
   *  - Swap the enter and action keys
   *  - Add the optional numpad and number row
   *  - Add the extra keys
   */
  public static KeyboardData modify_layout(KeyboardData kw)
  { return modify_layout(kw, false); }

  public static KeyboardData modify_layout(KeyboardData kw, boolean fnVariant)
  { return modify_layout(kw, fnVariant, null, ""); }

  public static KeyboardData modify_layout(KeyboardData kw, boolean fnVariant, PageKeyOverrides edits, String page)
  {
    boolean symbols = page.equals("symbols") || page.startsWith("symbols/");
    kw = symbols ? prepare_symbols(kw) : prepare_layout(kw, fnVariant, globalConfig.extra_keys_param,
        globalConfig.extra_keys_custom, globalConfig.extra_keys_subtype);
    if (edits != null) kw = edits.apply(page, kw);
    return present_layout(kw, fnVariant, symbols);
  }

  /** Fixed symbol coordinates do not inherit text-layout extras or digit rows. */
  public static KeyboardData prepare_symbols(KeyboardData kw)
  { return prepare_layout(kw, false, java.util.Collections.emptyMap(), java.util.Collections.emptyMap(), null); }

  /** Shared logical coordinates, before responsive splitting and digit rows. */
  public static KeyboardData prepare_layout(KeyboardData kw, boolean fnVariant,
      Map<KeyValue, KeyboardData.PreferredPos> standard,
      Map<KeyValue, KeyboardData.PreferredPos> custom, ExtraKeys subtype)
  {
    // Extra keys are removed from the set as they are encountered during the
    // first iteration then automatically added.
    final TreeMap<KeyValue, KeyboardData.PreferredPos> extra_keys = new TreeMap<KeyValue, KeyboardData.PreferredPos>();
    // Make sure the config key is accessible to avoid being locked in a custom
    // layout.
    extra_keys.put(KeyValue.CONFIG, KeyboardData.PreferredPos.ANYWHERE);
    extra_keys.putAll(standard);
    extra_keys.putAll(custom);
    // Add the bottom row before computing the extra keys
    if (kw.bottom_row)
      kw = kw.insert_row(bottom_row, kw.rows.size());
    // Compose keys to add to the layout
    // 'extra_keys_keyset' reflects changes made to 'extra_keys'
    Set<KeyValue> extra_keys_keyset = extra_keys.keySet();
    // 'kw_keys' contains the keys present on the layout without any extra keys
    Set<KeyValue> kw_keys = kw.getKeys().keySet();
    if (subtype != null && kw.locale_extra_keys)
    {
      Set<KeyValue> present = new HashSet<KeyValue>(kw_keys);
      present.addAll(extra_keys_keyset);
      subtype.compute(extra_keys,
          new ExtraKeys.Query(kw.script, present));
    }
    if (fnVariant)
    {
      TreeMap<KeyValue, KeyboardData.PreferredPos> mapped = new TreeMap<>();
      for (Map.Entry<KeyValue, KeyboardData.PreferredPos> entry : extra_keys.entrySet())
      {
        KeyValue value = KeyModifier.fn_value(entry.getKey(), kw.modmap);
        KeyboardData.PreferredPos position = new KeyboardData.PreferredPos(entry.getValue());
        if (position.next_to != null && !kw_keys.contains(position.next_to))
          position.next_to = KeyModifier.fn_value(position.next_to, kw.modmap);
        if (!value.getString().isEmpty()) mapped.put(value, position);
      }
      extra_keys.clear(); extra_keys.putAll(mapped);
    }
    kw = kw.mapKeys(new KeyboardData.MapKeyValues() {
      public KeyValue apply(KeyValue key, boolean localized)
      {
        if (localized && !extra_keys.containsKey(key))
          return null;
        return key;
      }
    });
    // Add extra keys that are not on the layout (including 'loc' keys)
    extra_keys_keyset.removeAll(kw_keys);
    if (extra_keys.size() > 0)
      kw = kw.addExtraKeys(extra_keys.entrySet().iterator());
    return kw.with_bottom_row(false);
  }

  private static KeyboardData present_layout(KeyboardData kw, boolean fnVariant, boolean symbols)
  {
    final Set<KeyValue> remove_keys = new HashSet<>();
    KeyboardData.Row added_number_row = null;
    KeyboardData added_numpad = null;
    if (!symbols && globalConfig.show_numpad)
    {
      added_numpad = modify_numpad(num_pad, kw);
      if (fnVariant) added_numpad = FnLayout.materialize(added_numpad, false);
      remove_keys.addAll(added_numpad.getKeys().keySet());
    }
    else if (!symbols && globalConfig.add_number_row && !kw.embedded_number_row)
    {
      added_number_row = modify_number_row(globalConfig.number_row_symbols ? number_row_symbols : number_row_no_symbols, kw);
      if (fnVariant) added_number_row = added_number_row.mapKeys(new KeyboardData.MapKeyValues() {
        public KeyValue apply(KeyValue value, boolean localized) { return KeyModifier.fn_value(value, null); }
      });
      if (globalConfig.split_layout) added_number_row = LayoutLandscapeModifier.transform_number_row(added_number_row);
      remove_keys.addAll(added_number_row.getKeys(0).keySet());
    }
    if (globalConfig.split_layout) kw = LayoutLandscapeModifier.transform_to_landscape(kw);
    kw = kw.mapKeys(new KeyboardData.MapKeyValues() {
      public KeyValue apply(KeyValue key, boolean localized)
      { return remove_keys.contains(key) ? null : modify_key(key); }
    });
    if (added_numpad != null) kw = kw.addNumPad(added_numpad);
    // Avoid adding extra keys to the number row
    if (added_number_row != null)
      kw = kw.insert_row(added_number_row, 0);
    return kw;
  }

  /** Handle the numpad layout. The [main_kw] is used to adapt the numpad to
      the main layout's script. */
  public static KeyboardData modify_numpad(KeyboardData kw, KeyboardData main_kw)
  {
    final int map_digit = KeyModifier.modify_numpad_script(main_kw.numpad_script);
    return kw.mapKeys(new KeyboardData.MapKeyValues() {
      public KeyValue apply(KeyValue key, boolean localized)
      {
        switch (key.getKind())
        {
          case Char:
            char prev_c = key.getChar();
            char c = prev_c;
            if (globalConfig.inverse_numpad)
              c = inverse_numpad_char(c);
            if (map_digit != -1)
            {
              KeyValue modified = ComposeKey.apply(map_digit, c);
              if (modified != null) // Was modified by script
                return modified;
            }
            if (prev_c != c) // Was inverted
              return key.withChar(c);
            return key; // Don't fallback into [modify_key]
        }
        return modify_key(key);
      }
    });
  }

  /** Modify the pin entry layout. [main_kw] is used to map the digits into the
      same script. */
  public static KeyboardData modify_pinentry(KeyboardData kw, KeyboardData main_kw)
  {
    KeyboardData.MapKeyValues m = numpad_script_map(main_kw.numpad_script);
    return m == null ? kw : kw.mapKeys(m);
  }

  /** Modify the number row according to [main_kw]'s script. */
  static KeyboardData.Row modify_number_row(KeyboardData.Row row,
      KeyboardData main_kw)
  {
    KeyboardData.MapKeyValues m = numpad_script_map(main_kw.numpad_script);
    return m == null ? row : row.mapKeys(m);
  }

  static KeyboardData.MapKeyValues numpad_script_map(String numpad_script)
  {
    final int map_digit = KeyModifier.modify_numpad_script(numpad_script);
    if (map_digit == -1)
      return null;
    return new KeyboardData.MapKeyValues() {
      public KeyValue apply(KeyValue key, boolean localized)
      {
        KeyValue modified = ComposeKey.apply(map_digit, key);
        return (modified != null) ? modified : key;
      }
    };
  }

  /** Modify keys on the main layout and on the numpad according to the config.
   */
  static KeyValue modify_key(KeyValue orig)
  {
    // Fn is always a paired-layout action, including imported/custom keys.
    if (orig.equals(KeyValue.getKeyByName("fn"))) return KeyValue.getKeyByName("fn_page");
    EditorConfig ec = globalConfig.editor_config;
    switch (orig.getKind())
    {
      case Layered:
        return KeyValue.layered(modify_key(orig.layer(false)), modify_key(orig.layer(true)));
      case Event:
        switch (orig.getEvent())
        {
          case CHANGE_METHOD_PICKER:
            return globalConfig.change_method_key_replacement;
          case ACTION:
            return ec.action_key_replacement;
          case SWITCH_FORWARD:
          case SWITCH_BACKWARD:
            return orig;
          case SWITCH_VOICE_TYPING:
          case SWITCH_VOICE_TYPING_CHOOSER:
            return globalConfig.shouldOfferVoiceTyping ? orig : null;
        }
        break;
      case Keyevent:
        switch (orig.getKeyevent())
        {
          case KeyEvent.KEYCODE_ENTER:
            if (ec.enter_key_replacement != null)
              return ec.enter_key_replacement;
            break;
        }
        break;
    }
    return orig;
  }

  static char inverse_numpad_char(char c)
  {
    switch (c)
    {
      case '7': return '1';
      case '8': return '2';
      case '9': return '3';
      case '1': return '7';
      case '2': return '8';
      case '3': return '9';
      default: return c;
    }
  }

  public static void init(Config globalConfig_, Resources res)
  {
    globalConfig = globalConfig_;
    try
    {
      number_row_no_symbols = KeyboardData.load_row(res, R.xml.number_row_no_symbols);
      number_row_symbols = KeyboardData.load_row(res, R.xml.number_row);
      bottom_row = KeyboardData.load_row(res, R.xml.bottom_row);
      num_pad = KeyboardData.load_num_pad(res);
      split_middle_column = KeyboardData.load_row(res, R.xml.split_middle_column);
    }
    catch (Exception e)
    {
      throw new RuntimeException(e.getMessage()); // Not recoverable
    }
  }
}
