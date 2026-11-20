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
import androidx.test.uiautomator.BySelector;
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
import static juloo.keyboard2.ProgressEvidence.Stage.*;

/** Drives the shipped launcher and Android settings. Never writes preferences
 * or enables/selects an IME through shell commands. Run before prepared tests. */
@RunWith(AndroidJUnit4.class)
public final class FirstInstallTest
{
  private Instrumentation instrumentation;
  private Context context;
  private UiDevice device;
  private String pkg;
  private boolean firstCandidate = true;
  private ProgressEvidence progress = ProgressEvidence.NONE;
  private int screenshotId;

  private void mark(ProgressEvidence.Stage stage)
  { progress.mark(stage, screenshotId, 0, -1, -1); }

  @Test public void installEnableAndUseTheDefaultKeyboard() throws Exception
  {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    String phase = InstrumentationRegistry.getArguments().getString("evidencePhase", "clean-install");
    ProgressEvidence.Run run = phase.equals("reopen") ? ProgressEvidence.Run.REOPEN
        : phase.equals("reboot") ? ProgressEvidence.Run.REBOOT : ProgressEvidence.Run.CLEAN_INSTALL;
    progress = AndroidProgressEvidence.start(context, run);
    progress.scope(ProgressEvidence.Scope.FIRST_INSTALL);
    try { runWithProgress(); }
    catch (Exception | Error failure)
    {
      AndroidProgressEvidence.failed(progress, ProgressEvidence.FailurePhase.FLOW, failure);
      throw failure;
    }
    finally { progress.close(); }
  }

  private void runWithProgress() throws Exception
  {
    mark(START);
    pkg = context.getPackageName();
    device = UiDevice.getInstance(instrumentation);
    mark(WAKE_ENTER); device.wakeUp(); mark(WAKE_RETURN);
    setOrientation(android.view.Surface.ROTATION_0);
    boolean revisit = InstrumentationRegistry.getArguments().getString("revisit", "false").equals("true");
    firstCandidate = !revisit;
    String order = InstrumentationRegistry.getArguments().getString("entryOrder", "settings-first");
    Throwable primary = null;
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
          pressBack();
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
      // Android may retain this IME or choose its fallback after a force-stop
      // or reboot. Inspect the real system picker and handle either state.
      launch();
      selectThroughAndroidPicker(revisit);
      launch();
      focusEditor();
      if (!revisit && WanxiangSetupTest.splitResources(context))
      {
        // The same empty install remains offline. Exercise the editor before
        // copying public resources via the real setup screen, without changing preferences.
        awaitLanguage("EN");
        type("hello"); press("2", "en"); awaitEditor("hello2");
        object("launcher_tryhere_area").setText("");
        pressBack(); WanxiangSetupTest.install(context, device, true);
        launch(); focusEditor();
        if ("EN".equals(object("miancak_language").getText())) object("miancak_language").click();
        awaitLanguage("中文");
      }
      screenshot(revisit ? "reopened-default-keyboard" : "first-visible-keyboard");
      assertEquals("中文", object("miancak_language").getText());
      assertTrue("The visible keyboard must use the Chinese main page",
          object("keyboard_view").getContentDescription().startsWith("Page: zh;"));
      assertKeyboardDefaults("zh");
      type("nihao");
      screenshot(revisit ? "reopened-input-pending" : "first-input-pending");
      String candidate = candidate();
      assertEquals("Fresh Chinese input lost or changed typed nihao", "你好", candidate);
      assertTrue(candidate.matches(".*[\\u3400-\\u9fff].*"));
      swipe('c', -1, -1);
      awaitEditor(candidate + "。");
      String expected = candidate + "。";
      // The new-install double-tap setting must work without test setup.
      tap(0.75f, 2); tap(0.75f, 2);
      type("qq"); expected += "QQ";
      awaitEditor(expected);
      tap(0.75f, 2);
      type("nihao"); candidate();
      object("miancak_language").click();
      expected += "nihao";
      awaitEditor(expected);
      awaitLanguage("EN");
      type("hello"); expected += "hello";
      awaitEditor(expected);
      assertKeyboardDefaults("en");
      press("superscript", "en"); press("2", "en"); expected += "²";
      awaitEditor(expected);
      press("subscript", "en"); press("2", "en"); expected += "₂";
      awaitEditor(expected);
      object("miancak_language").click();
      screenshot("first-run-mixed-text");
      // Covers IME-first -> settings, and a later settings visit in either order.
      pressBack();
      openSettings();
      assertDefaultLayout();
      screenshot("settings-after-input");
      pressBack();
      focusEditor();
      type("nihao"); candidate = candidate();
      swipe('v', -1, -1);
      expected += candidate + "，";
      awaitEditor(expected);
      assertKeyboardDefaults("zh");
      press("superscript", "zh"); press("2", "zh"); expected += "²";
      awaitEditor(expected);
      press("subscript", "zh"); press("2", "zh"); expected += "₂";
      awaitEditor(expected);
      screenshot("input-after-settings");
      if (!revisit && order.equals("settings-first")) expected = verifyPartialShiftHandoff(expected);
      // Read the normal first-install state after real view recreation in
      // both landscape directions. Do not prewrite the preference under test.
      for (boolean left : new boolean[] {true, false})
      {
        if (left) setOrientation(android.view.Surface.ROTATION_90); else setOrientation(android.view.Surface.ROTATION_270);
        awaitOrientation(left ? android.view.Surface.ROTATION_90 : android.view.Surface.ROTATION_270);
        assertKeyboardDefaults("zh");
        press("2", "zh"); expected += "2"; awaitEditor(expected);
        screenshot(left ? "default-landscape-left" : "default-landscape-right");
      }
      setOrientation(android.view.Surface.ROTATION_0); awaitOrientation(android.view.Surface.ROTATION_0);
      assertKeyboardDefaults("zh");
      press("2", "zh"); expected += "2"; awaitEditor(expected);
      screenshot("default-restored-portrait");
      // Keep the optional legacy splitting behavior available through the
      // shipped Settings UI, then leave the new default selected for reopen.
      pressBack(); openSettings(); chooseSplitPolicy("landscape");
      pressBack(); focusEditor();
      setOrientation(android.view.Surface.ROTATION_90); awaitOrientation(android.view.Surface.ROTATION_90);
      assertTrue("Manual landscape splitting must remain available", Config.globalConfig().split_layout);
      press("2", "zh"); expected += "2"; awaitEditor(expected);
      screenshot("manual-landscape-split");
      setOrientation(android.view.Surface.ROTATION_0); awaitOrientation(android.view.Surface.ROTATION_0);
      pressBack(); openSettings(); chooseSplitPolicy("never");
      pressBack(); focusEditor();
      setOrientation(android.view.Surface.ROTATION_270); awaitOrientation(android.view.Surface.ROTATION_270); assertKeyboardDefaults("zh");
      press("2", "zh"); expected += "2"; awaitEditor(expected);
      setOrientation(android.view.Surface.ROTATION_0); awaitOrientation(android.view.Surface.ROTATION_0); assertKeyboardDefaults("zh");
    }
    catch (Exception | Error failure)
    {
      primary = failure;
      AndroidProgressEvidence.failed(progress, ProgressEvidence.FailurePhase.FLOW, failure);
      throw failure;
    }
    finally
    {
      try
      {
        ProgressEvidence.cleanup(primary, () -> {
          mark(FINALLY_ENTER);
          mark(EVIDENCE_ENTER); screenshot("first-run-final"); mark(EVIDENCE_RETURN);
          mark(TEARDOWN_ENTER); device.unfreezeRotation(); mark(TEARDOWN_RETURN);
          mark(FINALLY_RETURN);
        });
      }
      catch (Exception | Error cleanup)
      {
        AndroidProgressEvidence.failed(progress, ProgressEvidence.FailurePhase.CLEANUP, cleanup);
        if (primary == null) throw cleanup;
      }
    }
  }

  private void launch()
  {
    mark(LAUNCH_ENTER);
    context.startActivity(new Intent(context, LauncherActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    assertNotNull(object("launcher_enable"));
    mark(LAUNCH_RETURN);
  }

  private void selectThroughAndroidPicker(boolean revisit) throws Exception
  {
    mark(PICKER_ENTER);
    String component = pkg + "/juloo.keyboard2.Keyboard2";
    object("launcher_select").click();
    String appName = context.getString(R.string.app_name);
    assertTrue("Android input-method picker did not list MIANCAK",
        device.wait(Until.hasObject(By.pkg("android").text(appName)), 15000));
    screenshot("system-picker-before-selection");
    UiObject2 row = pickerRow(appName);
    String before = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    if (revisit && component.equals(before))
    {
      // The already-selected case is not a new selection. Confirm retention
      // through Android's actual checked row, cancel the picker, then exercise
      // the same real input assertions below. Never set the IME through shell.
      assertTrue("Retained MIANCAK default is not checked in Android's picker",
          row.findObject(By.res("android", "radio")).isChecked());
      pressBack();
      mark(PICKER_DISMISS_ENTER);
      assertTrue("Cancelling the unchanged system picker must dismiss it",
          device.wait(Until.gone(By.pkg("android").text(appName)), 10000));
      mark(PICKER_DISMISS_RETURN);
    }
    else
    {
      boolean selected = false;
      for (int attempt = 0; attempt < 3 && !selected; attempt++)
      {
        uiIdle();
        try
        {
          // Click the complete current radio row, not a cached second-line
          // label whose bounds can move during the dialog's initial layout.
          row = pickerRow(appName); row.click();
        }
        catch (androidx.test.uiautomator.StaleObjectException changed) { continue; }
        selected = device.wait(Until.gone(By.pkg("android").text(appName)), 4000);
      }
      assertTrue("Android did not select MIANCAK through its visible picker", selected);
    }
    mark(PICKER_CURRENT_IME_ENTER);
    long end = SystemClock.uptimeMillis() + 10000;
    while (!component.equals(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD))
        && SystemClock.uptimeMillis() < end) SystemClock.sleep(30);
    assertEquals("The visible picker must leave MIANCAK as the actual selected IME", component,
        Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
    mark(PICKER_CURRENT_IME_RETURN);
    mark(PICKER_RETURN);
  }

  private UiObject2 pickerRow(String appName)
  {
    UiObject2 row = device.wait(Until.findObject(By.pkg("android").text(appName)), 10000);
    for (int depth = 0; depth < 5 && row != null; depth++, row = row.getParent())
      if (row.findObjects(By.res("android", "radio")).size() == 1) return row;
    throw new AssertionError("MIANCAK's specific Android picker radio row is missing");
  }

  private void openSettings()
  {
    mark(SETTINGS_ENTER);
    UiObject2 settings = object("btnLaunchSettingsActivity");
    assertNotNull("Shipped settings action is missing", settings);
    mark(SETTINGS_CLICK_ENTER); settings.click(); mark(SETTINGS_CLICK_RETURN);
    mark(SETTINGS_WAIT_ENTER);
    // The instrumentation's package Context can retain the old locale while
    // the Settings Activity has adopted Android's per-app Chinese locale.
    assertTrue(device.wait(Until.hasObject(By.res("android", "list").pkg(pkg)), 15000));
    mark(SETTINGS_WAIT_RETURN); mark(SETTINGS_RETURN);
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
    mainSync(() -> {
      SettingsActivity settings = currentSettings();
      android.preference.ListPreference split = (android.preference.ListPreference)settings.findPreference("split_layout");
      assertEquals("The actual settings list must select the runtime default", "never", split.getValue());
    });
  }

  private SettingsActivity currentSettings()
  {
    for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
      if (activity instanceof SettingsActivity) return (SettingsActivity)activity;
    throw new AssertionError("The shipped settings activity is not resumed");
  }

  private void chooseSplitPolicy(String value) throws Exception
  {
    mark(POLICY_ENTER);
    final String[] labels = new String[2];
    mark(POLICY_READ_ENTER);
    instrumentation.runOnMainSync(() -> {
      android.preference.ListPreference preference = (android.preference.ListPreference)currentSettings().findPreference("split_layout");
      labels[0] = preference.getTitle().toString();
      int index = preference.findIndexOfValue(value); assertTrue(index >= 0);
      labels[1] = preference.getEntries()[index].toString();
    });
    mark(POLICY_READ_RETURN);
    mark(POLICY_SCROLL_ENTER);
    new UiScrollable(new UiSelector().packageName(pkg).resourceId("android:id/list"))
      .scrollIntoView(new UiSelector().packageName(pkg).text(labels[0]));
    mark(POLICY_SCROLL_RETURN);
    mark(POLICY_LOOKUP_ENTER);
    UiObject2 row = device.findObject(By.pkg(pkg).text(labels[0])); assertNotNull(row);
    mark(POLICY_LOOKUP_RETURN); mark(POLICY_CLICK_ENTER); row.click(); mark(POLICY_CLICK_RETURN);
    mark(POLICY_LOOKUP_ENTER);
    UiObject2 choice = device.wait(Until.findObject(By.pkg(pkg).text(labels[1])), 10000);
    mark(POLICY_LOOKUP_RETURN);
    assertNotNull("Split policy dialog did not show " + value, choice);
    mark(POLICY_CLICK_ENTER); choice.click(); mark(POLICY_CLICK_RETURN);
    // Input dispatch and the ListPreference callback are different frames.
    // Wait for the actual saved/visible state instead of reading immediately
    // after UiAutomator injects the click (which can still return the old value).
    mark(POLICY_WAIT_ENTER);
    final String[] actual = new String[1];
    long end = SystemClock.uptimeMillis() + 10000;
    do
    {
      mark(POLICY_READ_ENTER);
      instrumentation.runOnMainSync(() -> actual[0] =
          ((android.preference.ListPreference)currentSettings().findPreference("split_layout")).getValue());
      mark(POLICY_READ_RETURN);
      if (value.equals(actual[0])) break;
      SystemClock.sleep(30);
    } while (SystemClock.uptimeMillis() < end);
    assertEquals(value, actual[0]);
    assertEquals(value, DirectBootAwarePreferences.get_shared_preferences(context).getString("split_layout", ""));
    assertTrue("The split-policy dialog must close after applying the choice",
        device.wait(Until.gone(By.res("android", "alertTitle").text(labels[0])), 10000));
    mark(POLICY_WAIT_RETURN); mark(POLICY_RETURN);
  }

  private void awaitOrientation(int rotation)
  {
    boolean landscape = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270;
    long end = SystemClock.uptimeMillis() + 15000;
    while ((rotation() != rotation || Config.globalConfig().orientation_landscape != landscape)
        && SystemClock.uptimeMillis() < end)
      SystemClock.sleep(30);
    assertEquals("The physical display must reach the requested rotation", rotation, rotation());
    assertEquals(landscape, Config.globalConfig().orientation_landscape);
    assertNotNull(object("keyboard_view")); uiIdle();
  }

  private float horizontalMargin(Rect keyboardBounds)
  {
    final float[] margin = {Config.globalConfig().horizontal_margin};
    if (android.os.Build.VERSION.SDK_INT >= 35)
      mainSync(() -> {
        boolean found = false;
        for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
          if (activity instanceof LauncherActivity)
          {
            android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            assertNotNull("The visible receiver must expose current system insets", insets);
            android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                | android.view.WindowInsets.Type.displayCutout());
            margin[0] = Math.max(margin[0], Math.max(safe.left, safe.right));
            found = true;
          }
        assertTrue("The actual launcher receiver must be resumed", found);
      });
    return Math.min(margin[0], keyboardBounds.width() / 3f);
  }

  private void assertKeyboardDefaults(String page)
  {
    assertFalse("Fresh/default configuration must never auto-split", Config.globalConfig().split_layout);
    assertFalse(Config.forPreview(context, DirectBootAwarePreferences.get_shared_preferences(context)).split_layout);
    KeyboardData data = LayoutModifier.modify_layout(PageLayouts.base(context.getResources(), page), false, null, page);
    assertEquals("Main QWERTY must keep its unsplit geometry", 10f, data.keysWidth, .001f);
    for (String name : new String[] {"superscript", "subscript"})
      assertTrue(page + " is missing default " + name, data.getKeys().containsKey(KeyValue.getKeyByName(name)));
  }

  private void press(String name, String page)
  {
    KeyboardData data = LayoutModifier.modify_layout(PageLayouts.base(context.getResources(), page), false, null, page);
    KeyboardData.KeyPos position = data.getKeys().get(KeyValue.getKeyByName(name));
    assertNotNull("Missing default action " + name + " on " + page, position);
    Config config = Config.globalConfig(); Rect bounds = object("keyboard_view").getVisibleBounds();
    float margin = horizontalMargin(bounds);
    float unit = (bounds.width() - 2 * margin) / data.keysWidth;
    float height = Math.min(config.keyboard_rows_height_pixels,
        (config.screenHeightPixels - config.keyboard_rows_height_pixels) / data.keysHeight);
    float x = bounds.left + margin, y = bounds.top + config.marginTop;
    for (int r = 0; r < position.row; r++) y += (data.rows.get(r).height + data.rows.get(r).shift) * height;
    KeyboardData.Row row = data.rows.get(position.row); y += (row.shift + row.height / 2) * height;
    for (int c = 0; c < position.col; c++) x += (row.keys.get(c).width + row.keys.get(c).shift) * unit;
    KeyboardData.Key key = row.keys.get(position.col); x += (key.shift + key.width / 2) * unit;
    int[][] directions = {{0,0},{-1,-1},{1,-1},{-1,1},{1,1},{-1,0},{1,0},{0,-1},{0,1}};
    int[] direction = directions[position.dir];
    if (position.dir == 0) assertTrue(click(Math.round(x), Math.round(y)));
    else assertTrue(swipe(Math.round(x), Math.round(y), Math.round(x + direction[0] * unit * .85f),
        Math.round(y + direction[1] * height * .65f), 12));
    instrumentationIdle();
  }

  private void enableThroughAndroidUi() throws Exception
  {
    mark(ENABLE_ENTER);
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
      uiIdle();
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
        if (toggle != null && toggle.isChecked()) { mark(ENABLE_RETURN); return; }
      }
      catch (androidx.test.uiautomator.StaleObjectException ignored) {}
      SystemClock.sleep(50);
    }
    fail("Android did not enable MIANCAK through its visible settings switch");
  }

  private UiObject2 object(String id)
  {
    boolean keyboard = id.equals("keyboard_view");
    boolean settings = id.equals("btnLaunchSettingsActivity");
    mark(keyboard ? KEYBOARD_LOOKUP_ENTER : settings ? SETTINGS_LOOKUP_ENTER : LOOKUP_ENTER);
    UiObject2 value = device.wait(Until.findObject(By.res(pkg, id)), 15000);
    mark(keyboard ? KEYBOARD_LOOKUP_RETURN : settings ? SETTINGS_LOOKUP_RETURN : LOOKUP_RETURN);
    return value;
  }

  private void focusEditor() throws Exception
  {
    mark(FOCUS_ENTER);
    new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(
        new UiSelector().resourceId(pkg + ":id/launcher_tryhere_area"));
    UiObject2 editor = object("launcher_tryhere_area");
    assertNotNull(editor);
    editor.click();
    assertNotNull("First keyboard did not appear", object("keyboard_view"));
    device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END);
    uiIdle();
    mark(FOCUS_RETURN);
  }

  private void awaitEditor(String expected)
  {
    mark(RECEIVER_WAIT_ENTER);
    assertTrue("Actual editor text differs from: " + expected,
        device.wait(Until.hasObject(By.res(pkg, "launcher_tryhere_area").text(expected)), 20000));
    mark(RECEIVER_WAIT_RETURN);
  }

  private void awaitLanguage(String language)
  {
    assertTrue(device.wait(Until.hasObject(By.res(pkg, "miancak_language").text(language)), 15000));
  }

  private String verifyPartialShiftHandoff(String expected)
  {
    type("nihaoshijie");
    assertTrue("All letters must reach the initial composition before selection",
        device.wait(Until.hasObject(By.res(pkg, "miancak_preedit").desc("Pinyin: ni hao shi jie")), 20000));
    BySelector partial = By.pkg(pkg).descStartsWith("Candidate ").text("你好");
    UiObject2 phrase = device.findObject(partial);
    if (phrase == null)
    {
      object("miancak_expand_candidates").click();
      phrase = device.wait(Until.findObject(partial), 15000);
    }
    assertNotNull("The partial phrase 你好 must be visible", phrase);
    phrase.click();
    assertTrue("The selected prefix must remain in unfinished composition",
        device.wait(Until.hasObject(By.res(pkg, "miancak_preedit").descStartsWith("Pinyin: 你好")), 15000));
    awaitEditor(expected);
    tap(0.75f, 2); // Completed center Shift.
    String mixed = expected + "你好shijie";
    awaitEditor(mixed);
    awaitLanguage("EN");
    assertTrue(device.wait(Until.gone(By.descStartsWith("Pinyin: ")), 15000));
    type("q");
    mixed += "Q";
    awaitEditor(mixed);
    object("miancak_language").click();
    awaitLanguage("中文");
    type("nihao");
    assertEquals("你好", candidate());
    swipe('c', -1, -1);
    mixed += "你好。";
    awaitEditor(mixed);
    return mixed;
  }

  private String launcherState()
  {
    StringBuilder state = new StringBuilder();
    mark(STATE_READ_ENTER);
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
    mark(STATE_READ_RETURN);
    return state.toString();
  }

  private String candidate()
  {
    mark(CANDIDATE_ENTER);
    // Keep typing immediately after showing the keyboard. Only the first
    // offline deployment receives the same 180s cold-start budget as R6;
    // never retype the text or wait for readiness before these real taps.
    long started = SystemClock.uptimeMillis();
    long end = started + (firstCandidate ? 180000 : 20000);
    firstCandidate = false;
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
        if (!value.isEmpty() && SystemClock.uptimeMillis() - stableSince >= 250)
        {
          android.util.Log.i("MIANCAK-Test", "Candidate wait completed in " + (SystemClock.uptimeMillis() - started) + " ms");
          mark(CANDIDATE_RETURN);
          return value;
        }
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
    float margin = horizontalMargin(bounds);
    float width = (bounds.width() - 2 * margin) / 10f;
    float height = Math.min(config.keyboard_rows_height_pixels,
        (config.screenHeightPixels - config.keyboard_rows_height_pixels) / 3.95f);
    return new float[] { bounds.left + margin + column * width,
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
    assertTrue(click(Math.round(p[0]), Math.round(p[1])));
  }

  private void type(String value)
  {
    for (char c : value.toCharArray())
    {
      float[] p = key(c);
      assertTrue(click(Math.round(p[0]), Math.round(p[1])));
    }
  }

  private void swipe(char c, int dx, int dy)
  {
    float[] p = key(c);
    assertTrue(swipe(Math.round(p[0]), Math.round(p[1]),
        Math.round(p[0] + dx * p[2] * 0.85f), Math.round(p[1] + dy * p[3] * 0.65f), 5));
  }

  private void screenshot(String name) throws Exception
  {
    screenshotId = screenshotNumber(name); mark(SCREENSHOT_ENTER);
    name = InstrumentationRegistry.getArguments().getString("evidencePhase", "first-install") + "-" + name;
    instrumentationIdle();
    uiIdle();
    CountDownLatch frames = new CountDownLatch(1);
    mainSync(() -> android.view.Choreographer.getInstance().postFrameCallback(
        first -> android.view.Choreographer.getInstance().postFrameCallback(second -> frames.countDown())));
    mark(FRAME_WAIT_ENTER); assertTrue(frames.await(5, TimeUnit.SECONDS)); mark(FRAME_WAIT_RETURN);
    mark(SCREENSHOT_FILES_ENTER);
    File dir = context.getExternalFilesDir("test-results");
    assertTrue(dir.isDirectory() || dir.mkdirs());
    try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new File(dir, name + "-state.txt")))
    {
      String state = launcherState();
      mark(SCREENSHOT_FILES_ENTER);
      stream.write(state.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    mark(SCREENSHOT_FILES_RETURN);
    mark(PNG_ENTER); assertTrue(device.takeScreenshot(new File(dir, name + ".png"))); mark(PNG_RETURN);
    mark(HIERARCHY_ENTER); device.dumpWindowHierarchy(new File(dir, name + ".xml")); mark(HIERARCHY_RETURN);
    mark(SCREENSHOT_RETURN);
  }
  private static int screenshotNumber(String name)
  {
    switch (name)
    {
      case "settings-first-default": return 1; case "android-keyboard-enabled": return 2;
      case "reopened-default-keyboard": return 3; case "first-visible-keyboard": return 4;
      case "reopened-input-pending": return 5; case "first-input-pending": return 6;
      case "first-run-mixed-text": return 7; case "settings-after-input": return 8;
      case "input-after-settings": return 9; case "default-landscape-left": return 10;
      case "default-landscape-right": return 11; case "default-restored-portrait": return 12;
      case "manual-landscape-split": return 13; case "first-run-final": return 14;
      case "system-picker-before-selection": return 15; case "environment-pixel-launcher-anr": return 16;
      default: return 0;
    }
  }
  private void setOrientation(int rotation) throws Exception
  {
    progress.mark(ORIENTATION_ENTER, screenshotId, 0, rotation, -1);
    if (rotation == android.view.Surface.ROTATION_0) device.setOrientationNatural();
    else if (rotation == android.view.Surface.ROTATION_90) device.setOrientationLeft();
    else device.setOrientationRight();
    progress.mark(ORIENTATION_RETURN, screenshotId, 0, rotation, -1);
  }
  private int rotation()
  {
    mark(ROTATION_ENTER);
    int value = device.getDisplayRotation();
    progress.mark(ROTATION_RETURN, screenshotId, 0, value, -1);
    return value;
  }
  private void pressBack()
  { mark(BACK_ENTER); device.pressBack(); mark(BACK_RETURN); }
  private void instrumentationIdle()
  { mark(INSTRUMENTATION_IDLE_ENTER); instrumentation.waitForIdleSync(); mark(INSTRUMENTATION_IDLE_RETURN); }
  private void uiIdle()
  { mark(UI_IDLE_ENTER); device.waitForIdle(); mark(UI_IDLE_RETURN); }
  private void mainSync(Runnable action)
  { mark(MAIN_SYNC_ENTER); instrumentation.runOnMainSync(action); mark(MAIN_SYNC_RETURN); }
  private boolean click(int x, int y)
  { mark(TOUCH_ENTER); boolean value = device.click(x, y); mark(TOUCH_RETURN); return value; }
  private boolean swipe(int x, int y, int endX, int endY, int steps)
  { mark(TOUCH_ENTER); boolean value = device.swipe(x, y, endX, endY, steps); mark(TOUCH_RETURN); return value; }

}
