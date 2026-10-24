package juloo.keyboard2;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

/** Modifier evaluation only. A passing test does not prove the UI Fn route works. */
public class UpstreamFnBaselineTest
{
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
