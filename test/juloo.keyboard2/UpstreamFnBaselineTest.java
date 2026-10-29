package juloo.keyboard2;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

/** Modifier evaluation only. A passing test does not prove the UI Fn route works. */
public class UpstreamFnBaselineTest
{
  @Test public void everyPinnedUpstreamCharacterFnValueIsPreserved() throws Exception
  {
    java.util.List<String> lines = java.nio.file.Files.readAllLines(
        java.nio.file.Paths.get("test/fixtures/upstream-fn/fn-values.tsv"), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals("source_hex\ttarget_name", lines.get(0));
    assertTrue("Must cover all language mappings, not only the QWERTY samples", lines.size() > 200);
    for (String line : lines.subList(1, lines.size()))
    {
      String[] columns = line.split("\t", -1);
      char source = (char)Integer.parseInt(columns[0], 16);
      KeyValue expected = KeyValue.getKeyByName(columns[1]);
      KeyValue actual = KeyModifier.fn_value(KeyValue.makeCharKey(source), null);
      int presentation = KeyValue.FLAG_SMALLER_FONT | KeyValue.FLAG_KEY_FONT | KeyValue.FLAG_GREYED;
      assertEquals("Pinned upstream Fn action U+" + columns[0],
          expected.withFlags(expected.getFlags() & ~presentation), actual.withFlags(actual.getFlags() & ~presentation));
    }
  }
  private KeyValue fn(String key)
  {
    return KeyModifier.modify(KeyValue.getKeyByName(key), KeyValue.Modifier.FN);
  }

  private void event(String source, int expected)
  {
    KeyValue result = fn(source);
    assertEquals(source, KeyValue.Kind.Keyevent, result.getKind());
    assertEquals(source, expected, result.getKeyevent());
  }

  @Test public void digitsProduceFunctionKeyEventsRatherThanLabels()
  {
    String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    int[] codes = {KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
      KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
      KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10};
    for (int i = 0; i < digits.length; i++) event(digits[i], codes[i]);
    event("f11_placeholder", KeyEvent.KEYCODE_F11);
    event("f12_placeholder", KeyEvent.KEYCODE_F12);
  }

  @Test public void upstreamNavigationAndEditingRemainAvailableToMaterialize()
  {
    event("up", KeyEvent.KEYCODE_PAGE_UP);
    event("down", KeyEvent.KEYCODE_PAGE_DOWN);
    event("left", KeyEvent.KEYCODE_MOVE_HOME);
    event("right", KeyEvent.KEYCODE_MOVE_END);
    event("esc", KeyEvent.KEYCODE_INSERT);
    assertEquals(KeyValue.getKeyByName("redo"), fn("undo"));
    assertEquals(KeyValue.getKeyByName("pasteAsPlainText"), fn("paste"));
    assertEquals(KeyValue.getKeyByName("nbsp"), fn("space"));
  }

  @Test public void greekAndMathAlternatesAreNotReplacedByALanguageJump()
  {
    assertEquals(KeyValue.getKeyByName("ϖ"), fn("π"));
    assertEquals(KeyValue.getKeyByName("⋃"), fn("∪"));
    assertEquals(KeyValue.getKeyByName("∮"), fn("∫"));
    assertEquals(KeyValue.getKeyByName("∑"), fn("Σ"));
  }
}
