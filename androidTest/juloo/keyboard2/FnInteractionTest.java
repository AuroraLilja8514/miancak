package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import juloo.keyboard2.input.PageConfig;
import juloo.keyboard2.prefs.LayoutsPreference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual IME touches and the target EditText's received down/up events. */
@RunWith(AndroidJUnit4.class)
public final class FnInteractionTest
{
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();
  private final UiDevice device = UiDevice.getInstance(instrumentation);
  private final List<KeyEvent> received = new CopyOnWriteArrayList<>();
  private ImeTestActivity activity;
  private String page;

  @Test public void pairedVariantsDeliverFunctionEventsAndKeepSharedControls() throws Exception
  {
    device.wakeUp(); device.setOrientationNatural();
    instrumentation.runOnMainSync(() -> {
      android.content.SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(context);
      MiancakPreferences.initialize(prefs);
      PagePreferences.save(prefs, new PageConfig(null, null));
      android.content.SharedPreferences.Editor edit = prefs.edit().remove(PageKeyOverrides.KEY)
        .putString("pages_last_text", "zh").putString("physical_keyboard_behavior", "show")
        .putString("number_row", "no_number_row");
      LayoutsPreference.save_to_preferences(edit, java.util.Collections.singletonList(new LayoutsPreference.NamedLayout("miancak_qwerty")));
      edit.commit();
    });
    String component = context.getPackageName() + "/juloo.keyboard2.Keyboard2";
    device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
    device.executeShellCommand("ime enable " + component); device.executeShellCommand("ime set " + component);
    activity = (ImeTestActivity)instrumentation.startActivitySync(new Intent(context, ImeTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.runOnMainSync(() -> {
      activity.first.setOnKeyListener((view, code, event) -> {
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12)
        { received.add(new KeyEvent(event)); return true; }
        return false;
      });
      activity.first.requestFocus();
      ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(activity.first, InputMethodManager.SHOW_IMPLICIT);
    });
    awaitPage("zh");
    try
    {
      for (String base : new String[] {"en", "zh"})
      {
        choose(base); press("fn_page"); awaitPage(base + "_fn");
        assertTrue(keyboard().getContentDescription().contains("Fn: true"));
        for (int function = 1; function <= 12; function++)
        {
          int count = received.size(); press("f" + function);
          long end = SystemClock.uptimeMillis() + 10000;
          while (received.size() < count + 2 && SystemClock.uptimeMillis() < end) SystemClock.sleep(20);
          assertEquals("Exactly one function-key down/up pair", count + 2, received.size());
          assertEquals(KeyEvent.ACTION_DOWN, received.get(count).getAction());
          assertEquals(KeyEvent.ACTION_UP, received.get(count + 1).getAction());
          for (int i = count; i < count + 2; i++)
          {
            assertEquals(KeyEvent.KEYCODE_F1 + function - 1, received.get(i).getKeyCode());
            assertEquals(0, received.get(i).getMetaState() & KeyEvent.META_FUNCTION_ON);
          }
        }
        assertEquals("Function keys must not insert their keycap labels", "", text());
        press("fn_page"); awaitPage(base);
        choose(base + "_fn"); assertTrue(keyboard().getContentDescription().contains("Fn: true"));
        press("language_switch"); awaitPage(base.equals("en") ? "zh_fn" : "en_fn");
      }
      choose("zh_fn"); press("（"); press("shift"); press("（");
      awaitText("（(");
      choose("greek"); press("fn_page"); awaitPage("greek_fn");
      press("ϑ"); awaitText("（(ϑ"); press("fn_page"); awaitPage("greek");
      choose("symbols"); press("fn_page"); awaitPage("symbols");
      assertTrue(keyboard().getContentDescription().contains("Fn: false"));
    }
    finally { device.unfreezeRotation(); activity.finish(); }
  }
  private UiObject2 keyboard()
  { return device.wait(Until.findObject(By.res(context.getPackageName(), "keyboard_view")), 15000); }
  private void awaitPage(String id)
  {
    assertTrue("Expected layout " + id, device.wait(Until.hasObject(By.res(context.getPackageName(), "keyboard_view").descStartsWith("Page: " + id + ";")), 15000));
    page = id;
  }
  private void choose(String id)
  {
    device.wait(Until.findObject(By.res(context.getPackageName(), "miancak_pages")), 15000).click();
    UiObject2 choice = null;
    for (int attempt = 0; attempt < 28; attempt++)
    {
      choice = device.findObject(By.desc("Choose page: " + id));
      if (choice != null && choice.getVisibleBounds().height() > 16) break;
      UiObject2 chooser = device.findObject(By.res(context.getPackageName(), "miancak_page_chooser"));
      assertNotNull(chooser); chooser.setGestureMargin(32);
      chooser.scroll(attempt < 14 ? androidx.test.uiautomator.Direction.DOWN : androidx.test.uiautomator.Direction.UP, .5f);
    }
    assertNotNull(choice); choice.click(); awaitPage(id);
  }
  private void press(String name)
  {
    KeyboardData logical = PageLayouts.base(context.getResources(), page);
    KeyboardData data = PageConfig.base(page).equals("greek") || PageConfig.base(page).equals("numeric")
      ? LayoutModifier.modify_numpad(logical, PageLayouts.base(context.getResources(), "zh"))
      : LayoutModifier.modify_layout(logical, PageConfig.isVariant(page));
    KeyboardData.KeyPos position = data.getKeys().get(KeyValue.getKeyByName(name));
    assertNotNull("Missing actual action " + name + " on " + page, position);
    Config config = Config.globalConfig(); Rect bounds = keyboard().getVisibleBounds();
    float unit = (bounds.width() - 2 * config.horizontal_margin) / data.keysWidth;
    float rowSize = Math.min(config.keyboard_rows_height_pixels, (config.screenHeightPixels - config.keyboard_rows_height_pixels) / data.keysHeight);
    float x = bounds.left + config.horizontal_margin, y = bounds.top + config.marginTop;
    for (int r = 0; r < position.row; r++) y += (data.rows.get(r).height + data.rows.get(r).shift) * rowSize;
    KeyboardData.Row row = data.rows.get(position.row); y += (row.shift + row.height / 2) * rowSize;
    for (int c = 0; c < position.col; c++) x += (row.keys.get(c).width + row.keys.get(c).shift) * unit;
    KeyboardData.Key key = row.keys.get(position.col); x += (key.shift + key.width / 2) * unit;
    int[][] directions = {{0,0},{-1,-1},{1,-1},{-1,1},{1,1},{-1,0},{1,0},{0,-1},{0,1}};
    int[] direction = directions[position.dir];
    if (position.dir == 0) assertTrue(device.click(Math.round(x), Math.round(y)));
    else assertTrue(device.swipe(Math.round(x), Math.round(y), Math.round(x + direction[0] * key.width * unit * .85f), Math.round(y + direction[1] * rowSize * .65f), 12));
    instrumentation.waitForIdleSync();
  }
  private String text()
  {
    final String[] value = new String[1]; instrumentation.runOnMainSync(() -> value[0] = activity.first.getText().toString()); return value[0];
  }
  private void awaitText(String expected)
  {
    long end = SystemClock.uptimeMillis() + 10000;
    while (!expected.equals(text()) && SystemClock.uptimeMillis() < end) SystemClock.sleep(20);
    assertEquals(expected, text());
  }
}
