package juloo.keyboard2;

/** Shared positions are normalized before page edits and before Fn expansion. */
public final class LayoutControls
{
  private LayoutControls() {}
  private static boolean center(KeyboardData.Key key, String name)
  { return KeyValue.getKeyByName(name).equals(key.keys[0]); }
  public static boolean arrows(KeyboardData.Key key)
  {
    return KeyValue.getKeyByName("left").equals(key.keys[5])
      && KeyValue.getKeyByName("right").equals(key.keys[6])
      && KeyValue.getKeyByName("up").equals(key.keys[7])
      && KeyValue.getKeyByName("down").equals(key.keys[8]);
  }
  public static boolean shared(KeyboardData.Key key, int slot)
  {
    return center(key, "space") && (slot == 0 || slot >= 5)
      || center(key, "enter") && (slot == 0 || slot == 4)
      || center(key, "backspace") && (slot == 0 || slot == 2)
      || arrows(key) && slot >= 5;
  }
  public static KeyboardData.Key normalize(KeyboardData.Key key)
  {
    for (int slot = 0; slot < 9; slot++)
      if (KeyValue.getKeyByName("fn").equals(key.keys[slot]))
        key = key.withKeyValue(slot, KeyValue.getKeyByName("fn_page"));
    if (center(key, "space"))
      return key.withKeyValue(7, KeyValue.getKeyByName("switch_forward"))
        .withKeyValue(8, KeyValue.getKeyByName("switch_backward"))
        .withKeyValue(5, KeyValue.getKeyByName("cursor_left"))
        .withKeyValue(6, KeyValue.getKeyByName("cursor_right"));
    if (center(key, "enter")) return key.withKeyValue(4, KeyValue.getKeyByName("layout_picker"));
    if (center(key, "backspace")) return key.withKeyValue(2, KeyValue.getKeyByName("delete"));
    return key;
  }
  public static KeyboardData normalize(KeyboardData data) { return data.mapKeys(LayoutControls::normalize); }
}
