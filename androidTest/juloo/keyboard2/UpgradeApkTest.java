package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Same driver runs against old and new APKs. Only the upgrade fixture seeds
 * a retiring selection; fresh-install tests never seed preferences. */
@RunWith(AndroidJUnit4.class)
public final class UpgradeApkTest
{
  private Instrumentation instrumentation;
  private Context context;
  private UiDevice device;
  private String pkg;
  private SharedPreferences prefs(Context ctx)
  { return ctx.getSharedPreferences(pkg + "_preferences", Context.MODE_PRIVATE); }
  private SharedPreferences canonical()
  { return prefs(Build.VERSION.SDK_INT >= 24 ? context.createDeviceProtectedStorageContext() : context); }
  private Context[] stores()
  { return Build.VERSION.SDK_INT >= 24 ? new Context[] {context, context.createDeviceProtectedStorageContext()} : new Context[] {context}; }
  private File marker() { return new File(context.getFilesDir(), "rime/user/upgrade-marker"); }

  @Test public void migrationAndRealInputAcrossApkReplacement() throws Exception
  {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext(); pkg = context.getPackageName();
    device = UiDevice.getInstance(instrumentation); device.wakeUp(); device.setOrientationNatural();
    String phase = InstrumentationRegistry.getArguments().getString("upgradePhase", "verify");
    assertEquals(phase.equals("prepare") ? 1 : 2, context.getPackageManager().getPackageInfo(pkg, 0).versionCode);
    try
    {
      if (phase.equals("prepare"))
      {
        for (Context store : stores())
          prefs(store).edit().putInt("version", 4).putString("layouts", "[\"miancak_qwerty\"]")
            .putBoolean("need_migration", false).putBoolean("miancak_chinese", true)
            .putString("physical_keyboard_behavior", "show").putString("number_row", "no_number_row")
            .putBoolean("lock_double_tap", false).commit();
      }
      else
      {
        assertEquals("baseline-c939", canonical().getString("upgrade_marker", ""));
        assertFalse(canonical().getBoolean("lock_double_tap", true));
        byte[] bytes = new byte[4];
        try (FileInputStream input = new FileInputStream(marker())) { assertEquals(4, input.read(bytes)); }
        assertEquals("kept", new String(bytes, StandardCharsets.UTF_8));
      }
      selectThroughShippedLauncher();
      instrumentation.startActivitySync(new Intent().setClassName(pkg, "juloo.keyboard2.ImeTestActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      UiObject2 editor = device.wait(Until.findObject(By.desc("first_editor")), 15000);
      assertNotNull(editor); editor.click();
      assertNotNull(device.wait(Until.findObject(By.res(pkg, "keyboard_view")), 15000));
      for (char c : "nihao".toCharArray()) tap(c, false);
      UiObject2 candidate = device.wait(Until.findObject(By.descStartsWith("Candidate 1: ")), 20000);
      assertNotNull(candidate); SystemClock.sleep(400);
      candidate = device.findObject(By.descStartsWith("Candidate 1: "));
      String text = candidate.getText(); assertTrue(text.matches(".*[\\u3400-\\u9fff].*"));
      candidate.click(); tap('c', true);
      assertTrue(device.wait(Until.hasObject(By.desc("first_editor").text(text + "。")), 15000));
      screenshot("upgrade-" + phase + "-input");
      assertTrue(new File(context.getFilesDir(), "rime/user").isDirectory());
      if (phase.equals("prepare"))
      {
        try (FileOutputStream out = new FileOutputStream(marker())) { out.write("kept".getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        for (Context store : stores())
          prefs(store).edit().putString("layouts", "[\"latn_dvorak\"]").putString("upgrade_marker", "baseline-c939")
            .putInt("current_layout_portrait", 0).putInt("current_layout_landscape", 0).putInt("miancak_layout_version", 0).commit();
        screenshot("upgrade-old-retired-selection");
      }
      else
      {
        assertTrue(canonical().getString("miancak_previous_layouts", "").contains("latn_dvorak"));
        assertEquals("[\"miancak_qwerty\"]", canonical().getString("layouts", ""));
        assertEquals(1, canonical().getInt("miancak_layout_version", 0));
        assertFalse(canonical().getBoolean("lock_double_tap", true));
      }
    }
    finally { screenshot("upgrade-" + phase + "-final"); device.unfreezeRotation(); }
  }
  private void selectThroughShippedLauncher() throws Exception
  {
    boolean restored = !InstrumentationRegistry.getArguments().getString("upgradePhase", "verify").equals("prepare");
    if (restored)
    {
      // am instrument force-stops its target. The host has already asserted
      // retained default/enabled IME state before starting this instrumentation.
      String component = pkg + "/juloo.keyboard2.Keyboard2";
      android.view.inputmethod.InputMethodManager manager = (android.view.inputmethod.InputMethodManager)
          context.getSystemService(Context.INPUT_METHOD_SERVICE);
      String alternate = null;
      for (android.view.inputmethod.InputMethodInfo method : manager.getEnabledInputMethodList())
        if (!method.getId().equals(component) && !method.isAuxiliaryIme()
            && method.getId().matches("[a-zA-Z0-9_./]+")) { alternate = method.getId(); break; }
      assertNotNull("Upgrade fixture needs an enabled non-auxiliary fallback IME", alternate);
      // Selecting the same id can retain a stale pending binding after the
      // instrumentation force-stop. Change away and back to rebuild it.
      device.executeShellCommand("ime set " + alternate);
      device.executeShellCommand("ime set " + component);
      assertEquals(component, device.executeShellCommand("settings get secure default_input_method").trim());
    }
    instrumentation.startActivitySync(new Intent().setClassName(pkg, "juloo.keyboard2.LauncherActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    if (restored)
    {
      UiObject2 field = device.wait(Until.findObject(By.res(pkg, "launcher_tryhere_area")), 15000);
      assertNotNull(field); field.click();
      assertNotNull("Rebound IME did not serve the real launcher editor",
          device.wait(Until.findObject(By.res(pkg, "keyboard_view")), 15000));
    }
    int label = context.getResources().getIdentifier("launcher_button_imepicker", "string", pkg);
    String name = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
    // Use the same selector-backed, ordinary task route as the independent
    // fresh-install/reopen suite, after waiting for the launcher to resume.
    UiObject2 select = device.wait(Until.findObject(By.pkg(pkg).text(context.getString(label))), 15000);
    assertNotNull("Shipped input-method picker action missing", select); select.click();
    assertTrue("Updated IME missing from Android's picker",
        device.wait(Until.hasObject(By.pkg("android").text(name)), 15000));
    screenshot("upgrade-" + InstrumentationRegistry.getArguments().getString("upgradePhase", "verify") + "-picker");
    try
    {
      device.findObject(new androidx.test.uiautomator.UiSelector().packageName("android").text(name)).click();
    }
    catch (androidx.test.uiautomator.UiObjectNotFoundException missing) { throw new AssertionError(missing); }
    assertTrue("Android's picker did not close after selecting the updated IME",
        device.wait(Until.gone(By.pkg("android").text(name)), 15000));
  }

  private void tap(char letter, boolean northwest)
  {
    String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    Rect bounds = device.findObject(By.res(pkg, "keyboard_view")).getVisibleBounds();
    Config config = Config.globalConfig();
    float unit = (bounds.width() - 2 * config.horizontal_margin) / 10f;
    float height = Math.min(config.keyboard_rows_height_pixels, (config.screenHeightPixels - config.keyboard_rows_height_pixels) / 3.95f);
    for (int r = 0; r < rows.length; r++)
    {
      int c = rows[r].indexOf(letter); if (c < 0) continue;
      int x = Math.round(bounds.left + config.horizontal_margin + (c + (r == 0 ? 0.5f : r == 1 ? 1f : 2f)) * unit);
      int y = Math.round(bounds.top + config.marginTop + (r + 0.5f) * height);
      if (northwest) assertTrue(device.swipe(x, y, Math.round(x - unit * 0.85f), Math.round(y - height * 0.65f), 12));
      else assertTrue(device.click(x, y));
      return;
    }
    throw new AssertionError(letter);
  }
  private void screenshot(String name) throws Exception
  {
    instrumentation.waitForIdleSync(); device.waitForIdle();
    File dir = context.getExternalFilesDir("test-results"); assertTrue(dir.isDirectory() || dir.mkdirs());
    assertTrue(device.takeScreenshot(new File(dir, name + ".png")));
    device.dumpWindowHierarchy(new File(dir, name + ".xml"));
    for (String service : new String[] {"window", "input_method"})
      try (FileOutputStream output = new FileOutputStream(new File(dir, name + "-" + service + ".txt")))
      { output.write(device.executeShellCommand("dumpsys " + service).getBytes(StandardCharsets.UTF_8)); }
  }
}
