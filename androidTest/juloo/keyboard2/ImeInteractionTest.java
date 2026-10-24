package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import juloo.keyboard2.prefs.LayoutsPreference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImeInteractionTest
{
  private Instrumentation instrumentation;
  private UiDevice device;
  private Context context;
  private ImeTestActivity activity;
  private String pkg;
  private float keyWidth, rowHeight, left, top;

  @Test public void touchMixedInputAndEditorLifecycles() throws Exception
  {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    pkg = context.getPackageName();
    device = UiDevice.getInstance(instrumentation);
    device.wakeUp();
    device.setOrientationNatural();
    instrumentation.runOnMainSync(() -> {
      SharedPreferences settings = DirectBootAwarePreferences.get_shared_preferences(context);
      MiancakPreferences.initialize(settings);
      SharedPreferences.Editor prefs = settings.edit();
      prefs.putString("physical_keyboard_behavior", "show").putBoolean("miancak_chinese", true)
        .putString("number_row", "no_number_row").putString("number_entry_layout", "number");
      LayoutsPreference.save_to_preferences(prefs,
          Collections.singletonList(new LayoutsPreference.NamedLayout("miancak_qwerty")));
      prefs.commit();
    });
    device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
    device.executeShellCommand("ime enable " + pkg + "/juloo.keyboard2.Keyboard2");
    device.executeShellCommand("ime set " + pkg + "/juloo.keyboard2.Keyboard2");
    activity = (ImeTestActivity)instrumentation.startActivitySync(new Intent(context, ImeTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    focus(activity.first);
    String output = "";
    try
    {
      ensureChinese();
      Rect chineseGeometry = keyboard().getVisibleBounds();
      long began = SystemClock.elapsedRealtime();
      type("nihao");
      String first = candidate();
      assertTrue(first.matches(".*[\\u3400-\\u9fff].*"));
      screenshot("chinese-candidates");
      if (InstrumentationRegistry.getArguments().getString("restartOnly", "false").equals("true"))
      {
        gesture('c', -1, -1);
        awaitText(activity.first, first + "。");
        gesture('q', -1, -1); awaitText(activity.first, first + "。测试");
        screenshot("process-restart");
        return;
      }
      gesture('c', -1, -1); // Explicit full-width full stop, NW of c.
      output += first + "。";
      awaitText(activity.first, output);
      type("nihao"); first = candidate();
      gesture('c', -1, 1); // ASCII period, SW of the same key.
      output += first + ".";
      awaitText(activity.first, output);
      type("di"); first = candidate();
      gesture('e', 1, -1); // Literal 3, never candidate number 3.
      output += first + "3";
      awaitText(activity.first, output);
      // Default Chinese NW punctuation and sequential, one-finger Shift pairs.
      gesture('z', -1, -1); gesture('x', -1, -1); gesture('b', -1, -1);
      gesture('n', -1, -1); tap(0.75f, 2); gesture('n', -1, -1);
      gesture(0.75f, 2, -1, -1); gesture(9.25f, 2, -1, -1);
      output += "、！？；：（）"; awaitText(activity.first, output);
      tap(0.75f, 2); gesture(0.75f, 2, -1, -1);
      tap(0.75f, 2); gesture(9.25f, 2, -1, -1);
      tap(0.75f, 2); gesture('x', -1, -1);
      tap(0.75f, 2); gesture('b', -1, -1);
      output += "()!?"; awaitText(activity.first, output);
      gesture('m', -1, -1);
      tap(0.75f, 2); gesture('m', -1, -1);
      tap(0.75f, 2); gesture('m', -1, 1);
      tap(0.75f, 2); gesture('m', 1, -1);
      gesture('m', 1, 1);
      output += "’'‘“”"; awaitText(activity.first, output);
      // The visible Chinese quote separates xi'an without committing xi.
      type("xi"); candidate(); gesture('m', -1, -1); type("an");
      first = candidate();
      assertEquals("西安", first);
      assertEquals(output, text(activity.first));
      gesture(0.75f, 2, 1, -1);
      output += first; awaitText(activity.first, output); awaitMode("EN");
      type("ok"); output += "ok"; awaitText(activity.first, output);
      gesture(0.75f, 2, 1, -1); awaitMode("中文");
      screenshot("chinese-punctuation-and-separator");
      // Opening the chooser and auxiliary pages preserves preedit. Selecting
      // the Fn target through the chooser must light Fn just as its key does.
      type("nihao"); String beforePages = candidate();
      gesture(9.15f, 3, 1, 1);
      assertNotNull(find("miancak_page_chooser"));
      assertEquals(output, text(activity.first));
      screenshot("page-chooser");
      choosePage("symbols");
      assertTrue(keyboard().getContentDescription().contains("Fn: true"));
      assertEquals(beforePages, candidate());
      tap(2.25f, 3); // Fn target returns to Chinese main page.
      assertTrue(keyboard().getContentDescription().startsWith("Page: zh;"));
      assertEquals(beforePages, candidate());
      tap(2.25f, 3); // Direct Fn reaches exactly the same symbols page.
      assertTrue(keyboard().getContentDescription().contains("Fn: true"));
      screenshot("fn-symbols-active");
      device.pressBack();
      assertTrue(keyboard().getContentDescription().startsWith("Page: zh;"));
      assertEquals(beforePages, candidate());
      find("miancak_cancel").click(); awaitIdlePreedit();
      gesture(9.15f, 3, 1, 1); choosePage("greek");
      assertTrue(keyboard().getContentDescription().startsWith("Page: greek;"));
      tap(0.5f, 0); output += "θ"; awaitText(activity.first, output);
      screenshot("greek-page");
      device.pressBack();
      gesture(9.15f, 3, 1, 1); choosePage("emoji");
      UiObject2 dynamicPicker = device.wait(Until.findObject(By.desc("Open layout chooser")), 15000);
      assertNotNull(dynamicPicker); screenshot("emoji-page");
      dynamicPicker.click(); choosePage("clipboard");
      dynamicPicker = device.wait(Until.findObject(By.desc("Open layout chooser")), 15000);
      assertNotNull(dynamicPicker); screenshot("clipboard-page");
      dynamicPicker.click(); choosePage("zh");
      assertTrue(keyboard().getContentDescription().startsWith("Page: zh;"));
      // Edit through the shipped settings and visual editor, then use the
      // resulting actual key on the IME. The restart phase reuses this edit.
      device.pressBack();
      instrumentation.startActivitySync(new Intent(context, PageSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      UiObject2 editLayout = device.wait(Until.findObject(By.desc("Edit layout: zh")), 15000);
      assertNotNull(editLayout); editLayout.click();
      device.wait(Until.findObject(By.desc("Edit key: zh:0:0")), 15000).click();
      screenshot("visual-key-editor");
      device.wait(Until.findObject(By.desc("Edit position: 1")), 15000).click();
      device.wait(Until.findObject(By.text(context.getString(R.string.page_literal))), 15000).click();
      UiObject2 value = device.wait(Until.findObject(By.desc("Key output text")), 15000);
      assertNotNull(value); value.setText("测试");
      device.findObject(By.res("android", "button1")).click();
      device.wait(Until.findObject(By.desc("Save layout")), 15000).click();
      resumeEditor(); ensureChinese();
      gesture('q', -1, -1); output += "测试"; awaitText(activity.first, output);
      screenshot("edited-key-output");
      // Candidate paging uses the displayed snapshot; returning restores page one.
      type("shi"); String pageOne = candidate();
      UiObject2 compact = find("miancak_compact_candidates");
      assertTrue("Compact row must contain a fitting prefix", compact.getChildren().size() > 0);
      assertTrue("The entire ten-item engine page must not overflow one phone row", compact.getChildren().size() < 8);
      Rect inputBounds = find("miancak_input_surface").getVisibleBounds();
      find("miancak_expand_candidates").click();
      assertNotNull(find("miancak_expanded_candidates"));
      assertEquals("Expansion must keep the allocated keyboard height", inputBounds, find("miancak_input_surface").getVisibleBounds());
      screenshot("expanded-candidates");
      assertTrue(find("miancak_next_candidates").isEnabled());
      find("miancak_next_candidates").click();
      awaitCandidate(pageOne, false);
      find("miancak_previous_candidates").click();
      awaitCandidate(pageOne, true);
      UiObject2 eighth = device.wait(Until.findObject(By.descStartsWith("Candidate 8: ")), 15000);
      assertNotNull("Candidates omitted from the compact row must remain reachable", eighth);
      String eighthText = eighth.getText();
      eighth.click(); output += eighthText; awaitText(activity.first, output);
      assertTrue(device.wait(Until.gone(By.res(pkg, "miancak_expanded_candidates")), 15000));
      type("shi"); candidate();
      find("miancak_expand_candidates").click();
      assertNotNull(find("miancak_expanded_candidates"));
      device.pressBack();
      assertTrue(device.wait(Until.gone(By.res(pkg, "miancak_expanded_candidates")), 15000));
      assertNotNull(keyboard());
      find("miancak_cancel").click(); awaitIdlePreedit();
      assertEquals(output, text(activity.first));
      // Space confirms once, while Backspace edits preedit before editor text.
      type("nihao"); first = candidate(); tap(5f, 3);
      output += first; awaitText(activity.first, output); awaitIdlePreedit();
      type("q"); candidate(); tap(9.25f, 2); awaitIdlePreedit();
      assertEquals(output, text(activity.first));
      // Multiline Enter commits the candidate before the editor newline.
      type("nihao"); first = candidate(); tap(9.15f, 3);
      output += first + "\n"; awaitText(activity.first, output); awaitIdlePreedit();
      tap(0.75f, 2); tap('q'); // One-finger Shift latch, then q.
      output += "Q"; awaitText(activity.first, output);
      assertEquals("中文", language().getText());
      type("nihao"); candidate();
      find("miancak_cancel").click();
      awaitIdlePreedit();
      assertEquals(output, text(activity.first));
      // One-finger long-press locks Shift; a later tap unlocks it.
      pointGeometry();
      int sx = Math.round(left + keyWidth * 0.75f), sy = Math.round(top + rowHeight * 2.5f);
      device.swipe(sx, sy, sx, sy, 170);
      tap('q'); tap('q'); output += "QQ"; awaitText(activity.first, output);
      tap(0.75f, 2);
      type("nihao"); first = candidate();
      language().click(); // Active composition confirms before changing mode.
      output += first; awaitText(activity.first, output);
      awaitMode("EN");
      Rect englishGeometry = keyboard().getVisibleBounds();
      assertEquals(chineseGeometry, englishGeometry);
      type("hello"); tap(5f, 3); output += "hello "; awaitText(activity.first, output);
      tap(9.25f, 2); output = output.substring(0, output.length() - 1); awaitText(activity.first, output);
      screenshot("mixed-input");
      language().click(); awaitMode("中文");
      // An existing composition survives keyboard-view recreation for a theme change.
      type("nihao"); first = candidate();
      instrumentation.runOnMainSync(() -> DirectBootAwarePreferences.get_shared_preferences(context).edit().putString("theme", "light").commit());
      assertEquals(first, candidate());
      find("miancak_cancel").click(); awaitIdlePreedit();
      // A field switch cancels uncommitted text rather than leaking it into either editor.
      type("nihao");
      focus(activity.second);
      assertEquals(output, text(activity.first));
      type("di"); first = candidate();
      device.findObject(By.descStartsWith("Candidate 1: ")).click();
      awaitText(activity.second, first);
      type("nihao"); candidate();
      instrumentation.runOnMainSync(() -> activity.second.setSelection(0));
      awaitIdlePreedit();
      assertEquals(first, text(activity.second));
      // Password/numeric editors disable the engine, including explicit language toggles.
      focus(activity.password); awaitMode("EN");
      assertFalse(language().isEnabled());
      type("secret"); awaitText(activity.password, "secret");
      focus(activity.number); awaitMode("EN");
      assertFalse(language().isEnabled());
      // Numeric layout has six width units and four rows: 1/2/3 are row three.
      Rect numberBounds = keyboard().getVisibleBounds();
      Config numericConfig = Config.globalConfig();
      float numericWidth = (numberBounds.width() - 2 * numericConfig.horizontal_margin) / 6f;
      float numericRow = Math.min(numericConfig.keyboard_rows_height_pixels,
          (numericConfig.screenHeightPixels - numericConfig.keyboard_rows_height_pixels) / 3.95f);
      for (float column : new float[] { 2f, 3f, 4f })
        device.click(Math.round(numberBounds.left + numericConfig.horizontal_margin + column * numericWidth),
            Math.round(numberBounds.top + numericConfig.marginTop + 2.5f * numericRow));
      awaitText(activity.number, "123");
      // A no-learning text field still supports Chinese composition.
      focus(activity.privateField); ensureChinese();
      type("nihao"); first = candidate(); gesture('c', -1, -1);
      awaitText(activity.privateField, first + "。");
      screenshot("private-field");
      // Rotation creates a fresh editor generation and cancels uncommitted preedit.
      type("nihao"); candidate();
      find("miancak_expand_candidates").click();
      assertNotNull(find("miancak_expanded_candidates"));
      device.setOrientationLeft();
      awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE);
      assertTrue(device.wait(Until.hasObject(By.res(pkg, "keyboard_view")), 15000));
      awaitIdlePreedit();
      Rect landscapeChinese = keyboard().getVisibleBounds();
      screenshot("rotation-landscape");
      language().click(); awaitMode("EN");
      assertEquals(landscapeChinese, keyboard().getVisibleBounds());
      screenshot("rotation-landscape-english");
      language().click(); awaitMode("中文");
      device.setOrientationNatural();
      awaitOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT);
      assertTrue(device.wait(Until.hasObject(By.res(pkg, "keyboard_view")), 15000));
      screenshot("rotation");
      // Exercise a real WebView InputConnection as well as standard EditText.
      // The original Activity reference may be obsolete after rotation: reopen a fresh fixture.
      activity = (ImeTestActivity)instrumentation.startActivitySync(new Intent(context, ImeTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
      instrumentation.runOnMainSync(() -> activity.openWebEditor());
      long loadDeadline = SystemClock.uptimeMillis() + 15000;
      while (!activity.webReady && SystemClock.uptimeMillis() < loadDeadline) SystemClock.sleep(30);
      assertTrue(activity.webReady);
      UiObject2 web = device.wait(Until.findObject(By.clazz("android.webkit.WebView")), 15000);
      assertNotNull(web);
      Rect webBounds = web.getVisibleBounds();
      float density = context.getResources().getDisplayMetrics().density;
      device.click(webBounds.left + Math.round(40 * density), webBounds.top + Math.round(80 * density));
      assertTrue(device.wait(Until.hasObject(By.res(pkg, "keyboard_view")), 15000));
      ensureChinese();
      type("nihao"); first = candidate(); gesture('c', -1, -1);
      awaitWeb(first + "。");
      screenshot("web-editor");
      File metrics = new File(context.getExternalFilesDir("test-results"), "interaction-metrics.txt");
      try (FileOutputStream stream = new FileOutputStream(metrics))
      {
        stream.write(("Complete touch/lifecycle scenario ms=" + (SystemClock.elapsedRealtime() - began) + "\nEmulator API=" + android.os.Build.VERSION.SDK_INT + "\n").getBytes(StandardCharsets.UTF_8));
      }
    }
    finally
    {
      screenshot("final-state");
      device.unfreezeRotation();
    }
  }

  private UiObject2 find(String id) { return device.wait(Until.findObject(By.res(pkg, id)), 10000); }
  private void choosePage(String id) throws Exception
  {
    // Scroll the same IME window node used by the visible-page assertions.
    UiObject2 choice = null;
    for (int attempt = 0; attempt < 12; attempt++)
    {
      choice = device.findObject(By.desc("Choose page: " + id));
      if (choice != null && choice.getVisibleBounds().height() > 16) break;
      UiObject2 chooser = find("miancak_page_chooser"); assertNotNull(chooser);
      chooser.setGestureMargin(Math.round(40 * context.getResources().getDisplayMetrics().density));
      chooser.scroll(androidx.test.uiautomator.Direction.DOWN, 0.65f);
    }
    assertNotNull("Enabled page is unreachable: " + id, choice); choice.click();
    assertTrue(device.wait(Until.gone(By.res(pkg, "miancak_page_chooser")), 15000));
  }
  private UiObject2 language() { return find("miancak_language"); }
  private UiObject2 keyboard() { return find("keyboard_view"); }
  private void awaitMode(String mode) { assertTrue(device.wait(Until.hasObject(By.res(pkg, "miancak_language").text(mode)), 15000)); }
  private void ensureChinese() { if (!language().getText().equals("中文")) language().click(); awaitMode("中文"); }
  private String candidate()
  {
    long end = SystemClock.uptimeMillis() + 20000;
    long stableSince = 0;
    String previous = "", status = "";
    while (SystemClock.uptimeMillis() < end)
    {
      try
      {
        UiObject2 node = device.findObject(By.descStartsWith("Candidate 1: "));
        UiObject2 preedit = device.findObject(By.res(pkg, "miancak_preedit"));
        status = preedit == null ? "keyboard is absent" : preedit.getText();
        String value = node == null ? "" : node.getText();
        if (!value.equals(previous)) { previous = value; stableSince = SystemClock.uptimeMillis(); }
        if (!value.isEmpty() && SystemClock.uptimeMillis() - stableSince >= 250) return value;
      }
      catch (androidx.test.uiautomator.StaleObjectException ex)
      {
        stableSince = SystemClock.uptimeMillis();
      }
      SystemClock.sleep(30);
    }
    fail("No stable Chinese candidate; preedit=" + status);
    return "";
  }
  private void awaitCandidate(String text, boolean equal)
  {
    long end = SystemClock.uptimeMillis() + 15000;
    String value = candidate();
    while (value.equals(text) != equal && SystemClock.uptimeMillis() < end)
    {
      SystemClock.sleep(30);
      value = candidate();
    }
    assertEquals("Unexpected candidate page", equal, value.equals(text));
  }
  private void awaitIdlePreedit() { assertTrue(device.wait(Until.gone(By.descStartsWith("Pinyin: ")), 15000)); }
  private void awaitOrientation(int expected)
  {
    long end = SystemClock.uptimeMillis() + 15000;
    while (context.getResources().getConfiguration().orientation != expected && SystemClock.uptimeMillis() < end) SystemClock.sleep(30);
    assertEquals(expected, context.getResources().getConfiguration().orientation);
  }
  private void resumeEditor()
  {
    context.startActivity(new Intent(context, ImeTestActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    assertTrue("Editor activity did not resume after settings", device.wait(Until.hasObject(By.desc("first_editor")), 15000));
    device.waitForIdle();
    instrumentation.runOnMainSync(() -> {
      for (android.app.Activity resumed : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
          .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
        if (resumed instanceof ImeTestActivity) activity = (ImeTestActivity)resumed;
    });
    focus(activity.first);
  }
  private void focus(EditText edit)
  {
    instrumentation.runOnMainSync(() -> {
      edit.requestFocus();
      ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
    });
    assertNotNull(keyboard());
    device.waitForIdle();
  }
  private String text(EditText edit)
  {
    String[] result = { "" };
    instrumentation.runOnMainSync(() -> result[0] = edit.getText().toString());
    return result[0];
  }
  private void awaitText(EditText edit, String expected)
  {
    long end = SystemClock.uptimeMillis() + 15000;
    while (SystemClock.uptimeMillis() < end && !text(edit).equals(expected)) SystemClock.sleep(30);
    assertEquals(expected, text(edit));
  }
  private void pointGeometry()
  {
    Rect bounds = keyboard().getVisibleBounds();
    Config config = Config.globalConfig();
    keyWidth = (bounds.width() - 2 * config.horizontal_margin) / 10f;
    left = bounds.left + config.horizontal_margin;
    top = bounds.top + config.marginTop;
    rowHeight = Math.min(config.keyboard_rows_height_pixels,
        (config.screenHeightPixels - config.keyboard_rows_height_pixels) / 3.95f);
  }
  private float[] position(char c)
  {
    String[] rows = { "qwertyuiop", "asdfghjkl", "zxcvbnm" };
    for (int row = 0; row < rows.length; row++)
    {
      int index = rows[row].indexOf(c);
      if (index >= 0) return new float[] { index + (row == 0 ? 0.5f : row == 1 ? 1f : 2f), row };
    }
    throw new AssertionError(c);
  }
  private void tap(char c) { float[] p = position(c); tap(p[0], (int)p[1]); }
  private void tap(float column, int row)
  {
    pointGeometry();
    assertTrue(device.click(Math.round(left + column * keyWidth), Math.round(top + (row + 0.5f) * rowHeight)));
  }
  private void type(String text) { for (char c : text.toCharArray()) tap(c); }
  private void gesture(char c, int dx, int dy)
  {
    float[] p = position(c);
    gesture(p[0], (int)p[1], dx, dy);
  }
  private void gesture(float column, int row, int dx, int dy)
  {
    pointGeometry();
    int x = Math.round(left + column * keyWidth), y = Math.round(top + (row + 0.5f) * rowHeight);
    assertTrue(device.swipe(x, y, Math.round(x + dx * keyWidth * 0.85f), Math.round(y + dy * rowHeight * 0.65f), 12));
  }
  private void awaitWeb(String expected) throws Exception
  {
    long end = SystemClock.uptimeMillis() + 15000;
    String value = "";
    while (SystemClock.uptimeMillis() < end)
    {
      CountDownLatch latch = new CountDownLatch(1);
      String[] result = { "" };
      instrumentation.runOnMainSync(() -> activity.web.evaluateJavascript("document.getElementById('t').value", text -> { result[0] = text; latch.countDown(); }));
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      value = result[0];
      if (value.equals(org.json.JSONObject.quote(expected))) return;
      SystemClock.sleep(30);
    }
    assertEquals(org.json.JSONObject.quote(expected), value);
  }
  private void screenshot(String name) throws Exception
  {
    // Editor/DOM assertions can finish before their next frame is presented.
    // Use Choreographer rather than the cached Activity, which rotation replaces.
    instrumentation.waitForIdleSync();
    device.waitForIdle();
    CountDownLatch rendered = new CountDownLatch(1);
    instrumentation.runOnMainSync(() -> {
      android.view.Choreographer frames = android.view.Choreographer.getInstance();
      frames.postFrameCallback(first -> frames.postFrameCallback(second -> rendered.countDown()));
    });
    assertTrue("UI frames did not advance before screenshot", rendered.await(5, TimeUnit.SECONDS));
    instrumentation.waitForIdleSync();
    device.waitForIdle();
    File directory = context.getExternalFilesDir("test-results");
    assertTrue(directory.isDirectory() || directory.mkdirs());
    assertTrue("Screenshot capture failed: " + name, device.takeScreenshot(new File(directory, name + ".png")));
  }
}
