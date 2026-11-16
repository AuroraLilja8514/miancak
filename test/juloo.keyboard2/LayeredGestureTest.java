package juloo.keyboard2;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LayeredGestureTest
{
  @Test public void explicitShiftLayerPrecedesTheExistingGestureFnFallback()
  {
    KeyValue key = KeyValue.layered(KeyValue.makeCharKey('a'), KeyValue.getKeyByName(":literal:'甲'"));
    Pointers.Modifiers gesture = Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.makeInternalModifier(KeyValue.Modifier.GESTURE));
    assertEquals("甲", KeyModifier.modify(key, gesture).getSemanticText());
    assertEquals("甲", KeyModifier.modify(key, KeyValue.Modifier.GESTURE).getSemanticText());
    assertEquals(KeyValue.getKeyByName("f1"), KeyModifier.modify(KeyValue.layered(KeyValue.makeCharKey('1'), KeyValue.makeCharKey('1')), gesture));
    assertNull(KeyModifier.modify(KeyValue.layered(KeyValue.makeCharKey('a'), null), gesture));
  }
  @Test public void previewRestoresTheActiveKeyboardModifierMap()
  {
    Modmap active = new Modmap(), preview = new Modmap();
    active.add(Modmap.M.Shift, KeyValue.makeCharKey('a'), KeyValue.makeCharKey('X'));
    preview.add(Modmap.M.Shift, KeyValue.makeCharKey('a'), KeyValue.makeCharKey('Y'));
    KeyModifier.set_modmap(active);
    try
    {
      assertEquals(KeyValue.makeCharKey('Y'), KeyModifier.preview(KeyValue.makeCharKey('a'), true, preview));
      assertEquals(KeyValue.makeCharKey('X'), KeyModifier.modify(KeyValue.makeCharKey('a'), KeyValue.Modifier.SHIFT));
    }
    finally { KeyModifier.set_modmap(null); }
  }
}
