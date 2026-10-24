package juloo.keyboard2;

import java.util.TreeMap;
import org.junit.Test;
import static org.junit.Assert.*;

/** Kind bits cross the signed-int boundary. Mixed user actions must still
 * form a transitive ordering for modifier maps and key configuration. */
public final class KeyOrderingTest
{
  @Test public void literalEngineLayeredAndModifierKeysRemainRetrievable()
  {
    KeyValue[] keys = {KeyValue.getKeyByName("q"), KeyValue.SHIFT, KeyValue.CONFIG,
      KeyValue.makeSemanticText("汉", false, null, 0), KeyValue.makeSemanticText("a", true, null, 0),
      KeyValue.layered(KeyValue.getKeyByName("a"), KeyValue.getKeyByName("A"))};
    for (KeyValue a : keys)
      for (KeyValue b : keys)
      {
        assertEquals(-Integer.signum(a.compareTo(b)), Integer.signum(b.compareTo(a)));
        for (KeyValue c : keys)
          if (a.compareTo(b) < 0 && b.compareTo(c) < 0) assertTrue(a.compareTo(c) < 0);
      }
    TreeMap<KeyValue, Integer> map = new TreeMap<>();
    for (int i = 0; i < keys.length; i++) map.put(keys[i], i);
    for (int i = 0; i < keys.length; i++) assertEquals(Integer.valueOf(i), map.get(keys[i]));
  }
}
