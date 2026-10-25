package juloo.keyboard2;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;

/** A concrete, editable variant. No hidden FN modifier is latched by this page. */
public final class FnLayout
{
  private FnLayout() {}
  public static KeyboardData materialize(KeyboardData base, boolean chinese)
  {
    List<KeyValue> displaced = new ArrayList<>();
    KeyboardData result = base.mapKeys(original -> {
      KeyboardData.Key key = original;
      for (int slot = 0; slot < 9; slot++)
      {
        KeyValue source = original.keys[slot];
        if (source == null || LayoutControls.shared(original, slot)) continue;
        KeyValue value = KeyModifier.fn_value(source, base.modmap);
        // Keep localized flags until normal visibility/extra-key processing.
        if (value != source)
          key = replace(key, slot, value, original.keyHasFlag(slot, KeyboardData.Key.F_LOC));
        if (chinese && source.getKind() == KeyValue.Kind.Char)
        {
          String punctuation = chinese(source.getChar());
          if (punctuation != null)
          {
            displaced.add(value);
            key = replace(key, slot, KeyValue.getKeyByName(punctuation), original.keyHasFlag(slot, KeyboardData.Key.F_LOC));
          }
        }
      }
      if (LayoutControls.arrows(original))
        key = key.withKeyValue(1, KeyValue.getKeyByName("home"))
          .withKeyValue(2, KeyValue.getKeyByName("page_up"))
          .withKeyValue(3, KeyValue.getKeyByName("end"))
          .withKeyValue(4, KeyValue.getKeyByName("page_down"));
      return LayoutControls.normalize(key);
    });
    // Shared Space keeps its center. Preserve the upstream NBSP elsewhere.
    displaced.add(KeyValue.getKeyByName("nbsp"));
    for (KeyValue value : displaced)
      if (value != null && !value.getString().isEmpty() && !result.getKeys().containsKey(value))
      {
        List<java.util.Map.Entry<KeyValue, KeyboardData.PreferredPos>> extra = new ArrayList<>();
        extra.add(new SimpleEntry<>(value, KeyboardData.PreferredPos.ANYWHERE));
        result = result.addExtraKeys(extra.iterator());
      }
    if (chinese)
    {
      Modmap map = new Modmap();
      if (base.modmap != null)
        for (Modmap.M modifier : Modmap.M.values())
          if (base.modmap._map[modifier.ordinal()] != null)
            for (java.util.Map.Entry<KeyValue, KeyValue> entry : base.modmap._map[modifier.ordinal()].entrySet())
              map.add(modifier, entry.getKey(), entry.getValue());
      for (char value : "()[]{}.,!?;:".toCharArray())
        map.add(Modmap.M.Shift, KeyValue.getKeyByName(chinese(value)), KeyValue.makeCharKey(value));
      result = new KeyboardData(result.rows, result.keysWidth, map, result.script, result.numpad_script,
          result.name, result.bottom_row, result.embedded_number_row, result.locale_extra_keys);
    }
    return result;
  }
  private static KeyboardData.Key replace(KeyboardData.Key key, int slot, KeyValue value, boolean localized)
  {
    KeyValue[] keys = key.keys.clone(); keys[slot] = value;
    int flags = 0;
    for (int i = 0; i < 9; i++) if (key.keyHasFlag(i, KeyboardData.Key.F_LOC)) flags |= KeyboardData.Key.F_LOC << i;
    if (!localized) flags &= ~(KeyboardData.Key.F_LOC << slot);
    return new KeyboardData.Key(keys, key.anticircle, flags, key.width, key.shift, key.indication, key.role);
  }
  private static String chinese(char value)
  {
    switch (value)
    {
      case '(': return "（"; case ')': return "）";
      case '[': return "［"; case ']': return "］";
      case '{': return "｛"; case '}': return "｝";
      case '.': return "。"; case ',': return "，";
      case '!': return "！"; case '?': return "？";
      case ';': return "；"; case ':': return "：";
      default: return null;
    }
  }
}
