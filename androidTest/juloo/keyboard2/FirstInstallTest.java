package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Drives the shipped launcher and Android settings. Never writes preferences
 * or enables/selects an IME through shell commands. Run before prepared tests. */
@RunWith(AndroidJUnit4.class)
public final class FirstInstallTest
{
  private Instrumentation instrumentation;
  private Context context;
  private UiDevice device;
  private String pkg;

  @Test public void installEnableAndUseTheDefaultKeyboard() throws Exception
  {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    pkg = context.getPackageName();
    device = UiDevice.getInstance(instrumentation);
    device.wakeUp();
    device.setOrientationNatural();
    boolean revisit = InstrumentationRegistry.getArguments().getString("revisit", "false").equals("true");
    String order = InstrumentationRegistry.getArguments().getString("entryOrder", "settings-first");
    try
    {
      if (!revisit)
      {
        if (order.equals("settings-first"))
        {
          launch();
          openSettings();
          assertDefaultLayout();
          screenshot("settings-first-default");
          device.pressBack();
          object("launcher_enable").click();
        }
        else
        {
          context.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        enableThroughAndroidUi();
        screenshot("android-keyboard-enabled");
      }
        // Android can select its fallback IME after a force-stop (and after
        // instrumentation terminates the app). Re-select through the same
        // visible system picker on reopen; never set the default via shell.
        launch();
        object("launcher_select").click();
        String appName = context.getString(R.string.app_name);
        assertTrue("Android input-method picker did not list MIANCAK",
            device.wait(Until.hasObject(By.pkg("android").text(appName)), 15000));
        // A selector-backed UiObject re-resolves after the dialog's layout;
        // a cached launcher text node can disappear while the picker opens.
        boolean selected = false;
        for (int attempt = 0; attempt < 3 && !selected; attempt++)
        {
          device.waitForIdle();
          try { device.findObject(new UiSelector().packageName("android").text(appName)).click(); }
          catch (androidx.test.uiautomator.UiObjectNotFoundException changed) { continue; }
          selected = device.wait(Until.gone(By.pkg("android").text(appName)), 4000);
        }
        assertTrue("Android did not select MIANCAK through its visible picker", selected);
      launch();
      focusEditor();
      screenshot(revisit ? "reopened-default-keyboard" : "first-visible-keyboard");
      assertEquals("中文", object("miancak_language").getText());
      type("nihao");
      String candidate = candidate();
      assertTrue(candidate.matches(".*[\\u3400-\\u9fff].*"));
      swipe('c', -1, -1);
      awaitEditor(candidate + "。");
      String expected = candidate + "。";
      // The new-install double-tap setting must work without test setup.
      tap(0.75f, 2); tap(0.75f, 2);
      type("qq"); expected += "QQ";
      awaitEditor(expected);
      tap(0.75f, 2);
      type("nihao"); candidate = candidate();
      object("miancak_language").click();
      expected += candidate;
      awaitEditor(expected);
      assertTrue(device.wait(Until.hasObject(By.res(pkg, "miancak_language").text("EN")), 15000));
      type("hello"); expected += "hello";
      awaitEditor(expected);
      object("miancak_language").click();
      screenshot("first-run-mixed-text");
      // Covers IME-first -> settings, and a later settings visit in either order.
      device.pressBack();
      openSettings();
      assertDefaultLayout();
      screenshot("settings-after-input");
      device.pressBack();
      focusEditor();
      type("nihao"); candidate = candidate();
      swipe('v', -1, -1);
      awaitEditor(expected + candidate + "，");
      screenshot("input-after-settings");
    }
    finally
    {
      screenshot("first-run-final");
      device.unfreezeRotation();
    }
  }

  private void launch()
  {
    context.startActivity(new Intent(context, LauncherActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    assertNotNull(object("launcher_enable"));
  }

  private void openSettings()
  {
    UiObject2 settings = object("btnLaunchSettingsActivity");
    assertNotNull("Shipped settings action is missing", settings);
    settings.click();
    // The instrumentation's package Context can retain the old locale while
    // the Settings Activity has adopted Android's per-app Chinese locale.
    assertTrue(device.wait(Until.hasObject(By.res("android", "list").pkg(pkg)), 15000));
  }

  private void assertDefaultLayout()
  {
    assertTrue(device.wait(Until.hasObject(By.res("android", "list").pkg(pkg)), 15000));
    android.content.SharedPreferences preferences = DirectBootAwarePreferences.get_shared_preferences(context);
    assertEquals("[\"miancak_qwerty\"]", preferences.getString("layouts", ""));
    assertEquals(0, preferences.getInt("current_layout_portrait", 0));
    assertEquals(0, preferences.getInt("current_layout_landscape", 0));
    java.util.List<KeyboardData> layouts = juloo.keyboard2.prefs.LayoutsPreference.load_from_preferences(context.getResources(), preferences);
    assertEquals(1, layouts.size());
    assertEquals("MIANCAK QWERTY", layouts.get(0).name);
    assertTrue(layouts.get(0).getKeys().containsKey(KeyValue.getKeyByName("。")));
  }

  private void enableThroughAndroidUi() throws Exception
  {
    String appName = context.getString(R.string.app_name);
    // A cold API 35 Google image can put its own launcher ANR dialog above
    // Settings. Record and close only that named external app once, then take
    // the same ordinary Settings route. Never dismiss a MIANCAK failure.
    long settingsDeadline = SystemClock.uptimeMillis() + 15000;
    boolean recoveredLauncher = false;
    while (!device.hasObject(By.pkg("com.android.settings")) && SystemClock.uptimeMillis() < settingsDeadline)
    {
      UiObject2 blocked = device.findObject(By.res("android", "alertTitle")
          .text("Pixel Launcher isn't responding"));
      if (!recoveredLauncher && blocked != null)
      {
        screenshot("environment-pixel-launcher-anr");
        android.util.Log.w("MIANCAK-Test", "Closing observed Pixel Launcher ANR in emulator onboarding");
        UiObject2 close = device.findObject(By.res("android", "aerr_close")); assertNotNull(close); close.click();
        context.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        recoveredLauncher = true; settingsDeadline = SystemClock.uptimeMillis() + 15000;
      }
      SystemClock.sleep(100);
    }
    assertTrue("Android keyboard settings did not open",
        device.hasObject(By.pkg("com.android.settings")));
    UiObject2 label = device.wait(Until.findObject(By.pkg("com.android.settings").text(appName)), 15000);
    if (label == null)
    {
      new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(appName));
      label = device.findObject(By.pkg("com.android.settings").text(appName));
    }
    assertNotNull("MIANCAK absent from Android's enabled-keyboard settings", label);
    UiObject2 row = label;
    UiObject2 toggle = null;
    for (int i = 0; i < 4 && row != null && toggle == null; i++, row = row.getParent())
      toggle = row.findObject(By.checkable(true));
    assertNotNull("Android keyboard-enable toggle missing", toggle);
    if (!toggle.isChecked())
    {
      toggle.click();
      UiObject2 confirm = device.wait(Until.findObject(By.res("android", "button1")), 5000);
      if (confirm != null) confirm.click();
      device.waitForIdle();
    }
    // The warning dialog's dismissal and Settings' row recreation are separate
    // frames on Android 15. Re-resolve until the actual switch reports enabled.
    long deadline = SystemClock.uptimeMillis() + 15000;
    while (SystemClock.uptimeMillis() < deadline)
    {
      try
      {
        row = device.findObject(By.pkg("com.android.settings").text(appName)); toggle = null;
        for (int i = 0; i < 4 && row != null && toggle == null; i++, row = row.getParent())
          toggle = row.findObject(By.checkable(true));
        if (toggle != null && toggle.isChecked()) return;
      }
      catch (androidx.test.uiautomator.StaleObjectException ignored) {}
      SystemClock.sleep(50);
    }
    fail("Android did not enable MIANCAK through its visible settings switch");
  }

  private UiObject2 object(String id)
  {
    return device.wait(Until.findObject(By.res(pkg, id)), 15000);
  }

  private void focusEditor() throws Exception
  {
    new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(
        new UiSelector().resourceId(pkg + ":id/launcher_tryhere_area"));
    UiObject2 editor = object("launcher_tryhere_area");
    assertNotNull(editor);
    editor.click();
    assertNotNull("First keyboard did not appear", object("keyboard_view"));
    device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END);
    device.waitForIdle();
  }

  private void awaitEditor(String expected)
  {
    assertTrue("Actual editor text differs from: " + expected,
        device.wait(Until.hasObject(By.res(pkg, "launcher_tryhere_area").text(expected)), 20000));
  }

  private String launcherState()
  {
    StringBuilder state = new StringBuilder();
    instrumentation.runOnMainSync(() -> {
      for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
          .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
      {
        state.append(activity.getClass().getName()).append(" softInput=")
          .append(activity.getWindow().getAttributes().softInputMode);
        android.view.View root = activity.findViewById(android.R.id.content);
        Rect visible = new Rect(); root.getWindowVisibleDisplayFrame(visible);
        state.append(" rootHeight=").append(root.getHeight()).append(" visibleFrame=").append(visible);
        android.widget.EditText editor = activity.findViewById(R.id.launcher_tryhere_area);
        if (editor != null)
        {
          Rect rectangle = new Rect(); boolean shown = editor.getGlobalVisibleRect(rectangle);
          state.append(" text=[").append(editor.getText()).append("] shown=").append(shown).append(" editor=").append(rectangle);
          android.view.View viewport = (android.view.View)editor.getParent().getParent();
          state.append(" viewportHeight=").append(viewport.getHeight()).append(" bottomPadding=").append(viewport.getPaddingBottom());
        }
        if (android.os.Build.VERSION.SDK_INT >= 30 && root.getRootWindowInsets() != null)
          state.append(" imeInset=").append(root.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.ime()).bottom);
        state.append('\n');
      }
    });
    return state.toString();
  }

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

  // Read actual rendered geometry only. No configuration is initialized or set.
  private float[] point(float column, int row)
  {
    Rect bounds = object("keyboard_view").getVisibleBounds();
    Config config = Config.globalConfig();
    float width = (bounds.width() - 2 * config.horizontal_margin) / 10f;
    float height = Math.min(config.keyboard_rows_height_pixels,
        (config.screenHeightPixels - config.keyboard_rows_height_pixels) / 3.95f);
    return new float[] { bounds.left + config.horizontal_margin + column * width,
      bounds.top + config.marginTop + (row + 0.5f) * height, width, height };
  }

  private float[] key(char c)
  {
    String[] rows = { "qwertyuiop", "asdfghjkl", "zxcvbnm" };
    for (int row = 0; row < rows.length; row++)
    {
      int i = rows[row].indexOf(c);
      if (i >= 0) return point(i + (row == 0 ? 0.5f : row == 1 ? 1f : 2f), row);
    }
    throw new AssertionError(c);
  }

  private void tap(float column, int row)
  {
    float[] p = point(column, row);
    assertTrue(device.click(Math.round(p[0]), Math.round(p[1])));
  }

  private void type(String value)
  {
    for (char c : value.toCharArray())
    {
      float[] p = key(c);
      assertTrue(device.click(Math.round(p[0]), Math.round(p[1])));
    }
  }

  private void swipe(char c, int dx, int dy)
  {
    float[] p = key(c);
    assertTrue(device.swipe(Math.round(p[0]), Math.round(p[1]),
        Math.round(p[0] + dx * p[2] * 0.85f), Math.round(p[1] + dy * p[3] * 0.65f), 5));
  }

  private void screenshot(String name) throws Exception
  {
    instrumentation.waitForIdleSync();
    device.waitForIdle();
    CountDownLatch frames = new CountDownLatch(1);
    instrumentation.runOnMainSync(() -> android.view.Choreographer.getInstance().postFrameCallback(
        first -> android.view.Choreographer.getInstance().postFrameCallback(second -> frames.countDown())));
    assertTrue(frames.await(5, TimeUnit.SECONDS));
    File dir = context.getExternalFilesDir("test-results");
    assertTrue(dir.isDirectory() || dir.mkdirs());
    try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new File(dir, name + "-state.txt")))
    { stream.write(launcherState().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    assertTrue(device.takeScreenshot(new File(dir, name + ".png")));
    device.dumpWindowHierarchy(new File(dir, name + ".xml"));
  }
}
