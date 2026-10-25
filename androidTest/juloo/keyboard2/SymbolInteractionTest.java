package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import juloo.keyboard2.input.SymbolState;
import juloo.keyboard2.input.SymbolCatalog;
import static org.junit.Assert.*;

/** Invoked in the same instrumentation as the IME lifecycle acceptance. */
public final class SymbolInteractionTest
{
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();
  private final UiDevice device = UiDevice.getInstance(instrumentation);
  private final String pkg = context.getPackageName();
  private final SymbolState state = new SymbolState();
  private String output = "";

  public void categoriesPagesWidthsAndControlsDeliverActualText() throws Exception
  {
    device.setOrientationNatural();
    ImeTestActivity activity = (ImeTestActivity)instrumentation.startActivitySync(new Intent(context, ImeTestActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.runOnMainSync(() -> {
      activity.first.requestFocus();
      ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(activity.first, InputMethodManager.SHOW_IMPLICIT);
    });
    assertNotNull(keyboard()); choose("symbols");
    try
    {
      // Independent reviewed first-output samples for every content page.
      String[][] cases = {{"common", "，"}, {"punctuation", "，", "‰", "⁉"}, {"brackets", "（", "⟨", "⦓"},
        {"math", "＋", "∯", "⊕", "⋃", "ℕ"}, {"arrows", "←", "↣", "⇇", "⤷"},
        {"currency", "￥", "₹", "㎑"}, {"shapes", "○", "▪", "✕"}};
      for (String[] row : cases)
      {
        assertEquals(row.length - 1, SymbolCatalog.pageCount(row[0]));
        category(row[0]);
        for (int page = 0; page < row.length - 1; page++)
        {
          awaitSymbolPage(row[0], page);
          touch(0, 0, 0); output += row[page + 1]; awaitText();
          touch(3, 2, 2); state.turn(1);
        }
        // Last-page next and Fn are both no-ops, without changing category,
        // policy, Fn indicator, output or a hidden modifier.
        awaitSymbolPage(row[0], row.length - 2);
        touch(3, 1, 0); awaitSymbolPage(row[0], row.length - 2);
        assertTrue(keyboard().getContentDescription().contains("Fn: false")); awaitText();
        category(row[0]); awaitSymbolPage(row[0], 0);
        touch(3, 2, 1); awaitSymbolPage(row[0], 0);
        touch(0, 0, 0); output += row[1]; awaitText();
      }
      category("common"); touch(2, 0, 0); touch(0, 0, 0); output += ","; awaitText();
      touch(2, 0, 0); touch(1, 2, 0); output += "【"; awaitText();
      category("math"); touch(0, 0, 1); output += "±"; awaitText();
      touch(2, 0, 0); touch(0, 0, 0); output += "+"; awaitText();
      touch(2, 0, 0); touch(0, 1, 0); output += "−"; awaitText();
      touch(3, 2, 2); state.turn(1); awaitSymbolPage("math", 1);
      String previousTheme = DirectBootAwarePreferences.get_shared_preferences(context).getString("theme", "system");
      instrumentation.runOnMainSync(() -> DirectBootAwarePreferences.get_shared_preferences(context).edit()
          .putString("theme", previousTheme.equals("light") ? "dark" : "light").commit());
      awaitSymbolPage("math", 1); touch(0, 0, 0); output += "∯"; awaitText(); screenshot("symbols-math");
      // Rotation starts a fresh editor generation. Re-enter symbols and check
      // its independently retained category/page and actual literal output.
      device.setOrientationLeft();
      awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE);
      assertTrue(device.wait(Until.hasObject(By.res(pkg, "keyboard_view")), 15000));
      device.waitForIdle(); choose("symbols"); awaitSymbolPage("math", 1);
      assertAuxiliaryHeaderFits();
      touch(0, 0, 0); output += "∯"; awaitText(); screenshot("symbols-landscape");
      device.setOrientationNatural(); awaitOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT);
      device.waitForIdle(); choose("symbols"); awaitSymbolPage("math", 1);
      // Space up/down always uses the enabled layout registry; it does not
      // browse categories or symbol pages.
      touch(3, 2, 7); awaitLayout("numeric");
      pressNumericPrevious(); awaitLayout("symbols"); awaitSymbolPage("math", 1);
      touch(0, 0, 0); output += "∯"; awaitText();
      category("common"); choose("zh");
    }
    finally
    {
      device.unfreezeRotation();
      java.io.File dir = TestEvidence.directory(context);
      assertTrue(dir.isDirectory() || dir.mkdirs());
      String actual = receivedText();
      org.json.JSONObject report = new org.json.JSONObject().put("expected", output)
        .put("actual", actual == null ? org.json.JSONObject.NULL : actual)
        .put("api", android.os.Build.VERSION.SDK_INT)
        .put("orientation", context.getResources().getConfiguration().orientation);
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "symbol-receiver.json")))
      { out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
      device.takeScreenshot(new java.io.File(dir, "symbol-final-state.png"));
      instrumentation.runOnMainSync(() -> {
        for (android.app.Activity current : new java.util.ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)))
          if (current instanceof ImeTestActivity) current.finish();
      });
    }
  }
  private void category(String id)
  {
    UiObject2 choice = null;
    for (int attempt = 0; attempt < 12; attempt++)
    {
      choice = device.findObject(By.res(pkg, "symbol_category_" + id));
      if (choice != null && choice.getVisibleBounds().width() > 24) break;
      UiObject2 header = device.findObject(By.res(pkg, "miancak_symbol_categories")); assertNotNull(header);
      UiObject2 scroll = header.findObject(By.clazz("android.widget.HorizontalScrollView")); assertNotNull(scroll);
      scroll.scroll(attempt < 6 ? Direction.RIGHT : Direction.LEFT, .75f);
      device.waitForIdle();
    }
    assertNotNull("Unreachable symbol category: " + id, choice); choice.click(); state.select(id);
    awaitSymbolPage(id, 0); device.waitForIdle();
  }
  private void awaitSymbolPage(String id, int page)
  {
    assertTrue(device.wait(Until.hasObject(By.res(pkg, "miancak_symbol_page").text(context.getString(R.string.symbol_page, page + 1, SymbolCatalog.pageCount(id)))), 15000));
    assertTrue(device.wait(Until.hasObject(By.res(pkg, "symbol_category_" + id).selected(true)), 15000));
    awaitLayout("symbols");
  }
  private void awaitLayout(String id)
  { assertTrue(device.wait(Until.hasObject(By.res(pkg, "keyboard_view").descStartsWith("Page: " + id + ";")), 15000)); }
  private void awaitOrientation(int expected)
  {
    long end = SystemClock.uptimeMillis() + 15000;
    while (context.getResources().getConfiguration().orientation != expected && SystemClock.uptimeMillis() < end) SystemClock.sleep(30);
    assertEquals(expected, context.getResources().getConfiguration().orientation);
  }
  private UiObject2 keyboard()
  { return device.wait(Until.findObject(By.res(pkg, "keyboard_view")), 15000); }
  private void assertAuxiliaryHeaderFits()
  {
    assertNull("Empty candidate navigation must not consume auxiliary height",
        device.findObject(By.res(pkg, "miancak_next_candidates")));
    UiObject2 language = device.findObject(By.res(pkg, "miancak_language")); assertNotNull(language);
    int status = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
    int inset = status == 0 ? 0 : context.getResources().getDimensionPixelSize(status);
    assertTrue("Auxiliary header overlaps status bar: " + language.getVisibleBounds(),
        language.getVisibleBounds().top >= inset);
    assertTrue(language.getVisibleBounds().height() > 0);
  }
  private void choose(String id)
  {
    device.wait(Until.findObject(By.res(pkg, "miancak_pages")), 15000).click();
    UiObject2 button = null;
    for (int attempt = 0; attempt < 28; attempt++)
    {
      button = device.findObject(By.desc("Choose page: " + id));
      if (button != null && button.getVisibleBounds().height() > 16) break;
      UiObject2 chooser = device.findObject(By.res(pkg, "miancak_page_chooser")); assertNotNull(chooser);
      chooser.setGestureMargin(Math.round(32 * context.getResources().getDisplayMetrics().density));
      chooser.scroll(attempt < 14 ? Direction.DOWN : Direction.UP, .5f,
          Math.round(120 * context.getResources().getDisplayMetrics().density)); device.waitForIdle();
    }
    assertNotNull(button); button.click(); awaitLayout(id); device.waitForIdle();
  }
  private void touch(int row, int col, int direction)
  { touchData(PageLayouts.symbols(context.getResources(), state), row, col, direction); }
  private void pressNumericPrevious()
  {
    KeyboardData data = PageLayouts.numeric(context.getResources(), false, PageLayouts.base(context.getResources(), "zh"));
    KeyboardData.KeyPos pos = data.getKeys().get(KeyValue.getKeyByName("switch_backward"));
    assertNotNull(pos); touchData(data, pos.row, pos.col, pos.dir);
  }
  private void touchData(KeyboardData logical, int row, int col, int direction)
  {
    KeyboardData data = LayoutModifier.modify_layout(logical);
    Config config = Config.globalConfig(); Rect bounds = keyboard().getVisibleBounds();
    float unit = (bounds.width() - 2 * config.horizontal_margin) / data.keysWidth;
    float rowSize = Math.min(config.keyboard_rows_height_pixels, (config.screenHeightPixels - config.keyboard_rows_height_pixels) / data.keysHeight);
    float x = bounds.left + config.horizontal_margin, y = bounds.top + config.marginTop;
    for (int r = 0; r < row; r++) y += (data.rows.get(r).height + data.rows.get(r).shift) * rowSize;
    KeyboardData.Row line = data.rows.get(row); y += (line.shift + line.height / 2) * rowSize;
    for (int c = 0; c < col; c++) x += (line.keys.get(c).width + line.keys.get(c).shift) * unit;
    KeyboardData.Key key = line.keys.get(col); x += (key.shift + key.width / 2) * unit;
    int[][] vectors = {{0,0},{-1,-1},{1,-1},{-1,1},{1,1},{-1,0},{1,0},{0,-1},{0,1}};
    int[] vector = vectors[direction];
    if (direction == 0) assertTrue(device.click(Math.round(x), Math.round(y)));
    else assertTrue(device.swipe(Math.round(x), Math.round(y), Math.round(x + vector[0] * unit * .85f),
          Math.round(y + vector[1] * rowSize * .65f), 12));
    instrumentation.waitForIdleSync(); device.waitForIdle();
  }
  private void awaitText()
  {
    String actual = ""; long end = SystemClock.uptimeMillis() + 15000;
    do
    {
      String received = receivedText();
      if (received != null) actual = received;
      if (output.equals(actual)) return;
      SystemClock.sleep(30);
    } while (SystemClock.uptimeMillis() < end);
    assertEquals(output, actual);
  }
  /** The resized landscape fixture may scroll the receiver off screen. Read
   * its current live EditText, never a stale pre-rotation Activity reference. */
  private String receivedText()
  {
    final String[] value = {null};
    instrumentation.runOnMainSync(() -> {
      for (android.app.Activity current : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
        if (current instanceof ImeTestActivity && ((ImeTestActivity)current).first.hasFocus())
          value[0] = ((ImeTestActivity)current).first.getText().toString();
    });
    return value[0];
  }
  private void screenshot(String name)
  {
    java.io.File dir = TestEvidence.directory(context); assertTrue(dir.isDirectory() || dir.mkdirs());
    device.waitForIdle(); device.takeScreenshot(new java.io.File(dir, name + ".png"));
  }
}
