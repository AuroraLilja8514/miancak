package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import static org.junit.Assert.*;

/** A resumed Activity can precede Android serving its new input window. */
final class ImeTestFocus
{
  static void show(Instrumentation instrumentation, Context context, EditText editor)
  {
    InputMethodManager manager = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
    boolean[] served = {false}; long end = SystemClock.uptimeMillis() + 15000;
    do
    {
      instrumentation.runOnMainSync(() -> served[0] = editor.hasWindowFocus() && manager.isActive(editor));
      if (served[0]) break; SystemClock.sleep(30);
    } while (SystemClock.uptimeMillis() < end);
    assertTrue("Android did not serve the clicked editor", served[0]);
    instrumentation.runOnMainSync(() -> manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT));
  }
}
