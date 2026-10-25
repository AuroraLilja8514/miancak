package juloo.keyboard2;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class FnLayoutTest
{
  private KeyboardData.Key key(String center)
  {
    KeyValue[] keys = new KeyValue[9]; keys[0] = KeyValue.getKeyByName(center);
    return new KeyboardData.Key(keys, null, 0, 1, 0, null, KeyboardData.Key.Role.Normal);
  }
  private KeyboardData layout(KeyboardData.Key... keys)
  {
    return new KeyboardData(Arrays.asList(new KeyboardData.Row(Arrays.asList(keys), 1, 0)), keys.length,
        null, "latin", null, "test", false, false, false);
  }
  @Test public void materializedVariantsContainEventsAndPreserveSharedSpace()
  {
    KeyboardData data = LayoutControls.normalize(layout(key("1"), key("space"), key("enter"), key("backspace")));
    KeyboardData result = FnLayout.materialize(data, false);
    assertEquals(KeyValue.getKeyByName("f1"), result.rows.get(0).keys.get(0).keys[0]);
    for (int slot : new int[] {0, 5, 6, 7, 8})
      assertEquals(data.rows.get(0).keys.get(1).keys[slot], result.rows.get(0).keys.get(1).keys[slot]);
    assertTrue(result.getKeys().containsKey(KeyValue.getKeyByName("nbsp")));
    assertEquals(KeyValue.getKeyByName("layout_picker"), result.rows.get(0).keys.get(2).keys[4]);
    assertEquals(KeyValue.getKeyByName("delete"), result.rows.get(0).keys.get(3).keys[2]);
  }
  @Test public void navigationMovesToDiagonalsWithoutChangingArrowDirections()
  {
    KeyboardData.Key arrows = key("compose").withKeyValue(5, KeyValue.getKeyByName("left"))
      .withKeyValue(6, KeyValue.getKeyByName("right")).withKeyValue(7, KeyValue.getKeyByName("up"))
      .withKeyValue(8, KeyValue.getKeyByName("down"));
    KeyboardData.Key result = FnLayout.materialize(layout(arrows), false).rows.get(0).keys.get(0);
    for (int i = 5; i <= 8; i++) assertEquals(arrows.keys[i], result.keys[i]);
    assertEquals(KeyValue.getKeyByName("home"), result.keys[1]);
    assertEquals(KeyValue.getKeyByName("page_up"), result.keys[2]);
    assertEquals(KeyValue.getKeyByName("end"), result.keys[3]);
    assertEquals(KeyValue.getKeyByName("page_down"), result.keys[4]);
  }
  @Test public void chineseReplacementRetainsDisplacedUpstreamOutputAndExplicitShiftPair()
  {
    KeyboardData data = layout(key("("), key("["), key("d"), key("f"));
    KeyboardData result = FnLayout.materialize(data, true);
    assertEquals(KeyValue.getKeyByName("（"), result.rows.get(0).keys.get(0).keys[0]);
    assertEquals(KeyValue.getKeyByName("［"), result.rows.get(0).keys.get(1).keys[0]);
    assertTrue(result.getKeys().containsKey(KeyValue.getKeyByName("“")));
    assertTrue(result.getKeys().containsKey(KeyValue.getKeyByName("‘")));
    assertEquals(KeyValue.getKeyByName("("), result.modmap.get(Modmap.M.Shift, KeyValue.getKeyByName("（")));
    assertNull(data.modmap);
  }
}
