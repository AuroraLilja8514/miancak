package juloo.keyboard2;

import org.junit.Test;
import static org.junit.Assert.*;

public class SemanticLayoutTest
{
  @Test public void sameVisibleTextCanHaveDifferentExplicitMeaning() throws Exception
  {
    KeyValue engine = KeyValueParser.parse(":engine:'.'");
    KeyValue literal = KeyValueParser.parse(":literal:'.'");
    assertEquals(engine.getString(), literal.getString());
    assertEquals(KeyValue.Kind.Engine, engine.getKind());
    assertEquals(KeyValue.Kind.Literal, literal.getKind());
    assertNotEquals(engine, literal);
    assertEquals(".", engine.getSemanticText());
    assertEquals(".", literal.getSemanticText());
  }
  @Test public void customLabelsDoNotTurnEngineTextIntoAMacro() throws Exception
  {
    KeyValue engine = KeyValueParser.parse(":engine symbol='Pinyin separator':'\\''");
    assertEquals(KeyValue.Kind.Engine, engine.getKind());
    assertEquals("'", engine.getSemanticText());
    assertEquals("Pinyin separator", engine.getString());
    assertEquals("'", engine.withSymbol("撇").getSemanticText());
    assertEquals(KeyValue.Kind.Engine, engine.withSymbol("撇").getKind());
  }
  @Test public void explicitTextIsStableUnderShiftAndSupportsControlShortcuts() throws Exception
  {
    KeyValue literal = KeyValueParser.parse(":literal:'q'");
    assertEquals(literal, KeyModifier.modify(literal, KeyValue.Modifier.SHIFT));
    KeyValue control = KeyModifier.modify(literal, KeyValue.Modifier.CTRL);
    assertEquals(KeyValue.Kind.Keyevent, control.getKind());
    assertEquals(android.view.KeyEvent.KEYCODE_Q, control.getKeyevent());
  }
}
