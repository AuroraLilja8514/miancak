package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Android's real DocumentsUI export/import, with a cancel/reset round trip. */
@RunWith(AndroidJUnit4.class)
public final class PageFilesUiTest
{
  private Instrumentation instrumentation;
  private Context context;
  private UiDevice device;
  private SharedPreferences preferences;
  @Test public void exportCancelResetDisableAndImportThroughAndroidFiles() throws Exception
  {
    instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
    device = UiDevice.getInstance(instrumentation); device.wakeUp();
    preferences = DirectBootAwarePreferences.get_shared_preferences(context);
    String before = PageKeyOverrides.export(preferences);
    assertNotNull("Touch test must have saved its real key edit", PageKeyOverrides.load(preferences).action("zh", 0, 0, 1, false));
    context.startActivity(new Intent(context, PageSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try
    {
      clickText(R.string.page_export);
      UiObject2 filename = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15000);
      assertNotNull(filename);
      String name = "miancak-layouts-api" + android.os.Build.VERSION.SDK_INT + ".json";
      filename.setText(name); screenshot("layout-file-export-picker");
      UiObject2 save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)^save$"))), 15000);
      assertNotNull("Android file picker Save missing", save); assertTrue(save.isEnabled()); save.click();
      assertTrue(device.wait(Until.hasObject(By.desc("Edit layout: zh")), 15000));
      // Reset in a draft then cancel: no preference may change.
      resetDraft();
      device.wait(Until.findObject(By.desc("Cancel layout edit")), 15000).click();
      assertEquals(before, PageKeyOverrides.export(preferences));
      // Saving the same reset removes the edit. File import must restore it.
      resetDraft();
      device.wait(Until.findObject(By.desc("Save layout")), 15000).click();
      assertNull(PageKeyOverrides.load(preferences).action("zh", 0, 0, 1, false));
      new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().description("page-enabled-pinyin"));
      device.findObject(By.desc("page-enabled-pinyin")).click();
      assertEquals("", PagePreferences.load(preferences).chineseFn);
      clickText(R.string.page_import);
      UiObject2 file = device.wait(Until.findObject(By.text(name)), 15000);
      if (file == null)
      {
        UiObject2 downloads = device.findObject(By.text("Downloads"));
        if (downloads != null) downloads.click();
        file = device.wait(Until.findObject(By.text(name)), 15000);
      }
      assertNotNull("Exported file missing from Android document picker", file); file.click();
      assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.page_import_confirm))), 15000));
      device.findObject(By.res("android", "button1")).click();
      long deadline = SystemClock.uptimeMillis() + 10000;
      while (!before.equals(PageKeyOverrides.export(preferences)) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
      assertEquals(before, PageKeyOverrides.export(preferences));
      screenshot("layout-file-import-restored");
    }
    finally { screenshot("layout-files-final"); device.pressBack(); }
  }
  private void resetDraft() throws Exception
  {
    new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().description("Edit layout: zh"));
    device.findObject(By.desc("Edit layout: zh")).click();
    new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().description("Reset this layout"));
    device.findObject(By.desc("Reset this layout")).click();
    device.wait(Until.findObject(By.res("android", "button1")), 15000).click();
  }
  private void clickText(int resource) throws Exception
  {
    String text = context.getString(resource);
    new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(text));
    UiObject2 button = device.wait(Until.findObject(By.text(text)), 15000); assertNotNull(button); button.click();
  }
  private void screenshot(String name) throws Exception
  {
    instrumentation.waitForIdleSync(); device.waitForIdle();
    File dir = context.getExternalFilesDir("test-results"); assertTrue(dir.isDirectory() || dir.mkdirs());
    assertTrue(device.takeScreenshot(new File(dir, name + ".png")));
    device.dumpWindowHierarchy(new File(dir, name + ".xml"));
  }
}
