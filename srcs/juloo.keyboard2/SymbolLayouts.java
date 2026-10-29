package juloo.keyboard2;

import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.input.SymbolCatalog;
import juloo.keyboard2.input.SymbolState;

/** Exact symbol output and explicit Unicode width pairs; controls are untouched. */
public final class SymbolLayouts
{
  private SymbolLayouts() {}
  public static String widthPair(String text)
  {
    if (text.length() != 1) return text;
    char value = text.charAt(0);
    if (value >= 0x21 && value <= 0x7e) return String.valueOf((char)(value + 0xfee0));
    if (value >= 0xff01 && value <= 0xff5e) return String.valueOf((char)(value - 0xfee0));
    return text;
  }
  public static KeyValue symbol(String text)
  {
    return KeyValue.layered(KeyValue.getKeyByName(PageKeyOverrides.literal(text)),
        KeyValue.getKeyByName(PageKeyOverrides.literal(widthPair(text))));
  }
  public static KeyboardData build(KeyboardData template, SymbolState state)
  {
    KeyboardData result = template;
    if (!state.category().equals("common"))
    {
      List<String> values = SymbolCatalog.items(state.category(), state.page());
      ArrayList<KeyboardData.Row> rows = new ArrayList<>();
      int index = 0;
      for (int r = 0; r < template.rows.size(); r++)
      {
        KeyboardData.Row row = template.rows.get(r);
        if (r >= 3) { rows.add(row); continue; }
        ArrayList<KeyboardData.Key> keys = new ArrayList<>();
        for (KeyboardData.Key original : row.keys)
        {
          if (original.keys[0] != null && original.keys[0].getKind() != KeyValue.Kind.Literal)
          { keys.add(original); continue; }
          KeyValue[] slots = new KeyValue[9];
          if (index < values.size())
          {
            String value = values.get(index++); slots[0] = symbol(value);
            String[] related = related(value);
            for (int corner = 0; corner < related.length; corner++) slots[corner + 1] = symbol(related[corner]);
          }
          keys.add(new KeyboardData.Key(slots, null, 0, original.width, original.shift, null, KeyboardData.Key.Role.Normal));
        }
        rows.add(row.with_keys(keys));
      }
      result = template.with_rows(rows);
    }
    else result = template.mapKeys(key -> {
      for (int slot = 0; slot < 9; slot++)
        if (key.keys[slot] != null && key.keys[slot].getKind() == KeyValue.Kind.Literal)
          key = key.withKeyValue(slot, symbol(key.keys[slot].getSemanticText()));
      return key;
    });
    return LayoutControls.normalize(result).mapKeys(key -> {
      if (KeyValue.getKeyByName("space").equals(key.keys[0]))
        key = key.withKeyValue(1, boundary("symbol_previous", state.previous()))
          .withKeyValue(2, boundary("symbol_next", state.next()));
      return key;
    });
  }
  private static KeyValue boundary(String name, boolean enabled)
  {
    KeyValue value = KeyValue.getKeyByName(name);
    return enabled ? value : value.withFlags(value.getFlags() | KeyValue.FLAG_GREYED);
  }
  private static String[] related(String value)
  {
    switch (value)
    {
      case "＋": return new String[] {"±", "∓", "⊕", "⊞"};
      case "−": return new String[] {"－", "∓", "⊖", "⊟"};
      case "＝": return new String[] {"≠", "≈", "≡", "≅"};
      case "∫": return new String[] {"∮", "∬", "∭", "∰"};
      case "←": return new String[] {"↖", "↙", "⇐", "⟵"};
      case "→": return new String[] {"↗", "↘", "⇒", "⟶"};
      default: return new String[0];
    }
  }
}
