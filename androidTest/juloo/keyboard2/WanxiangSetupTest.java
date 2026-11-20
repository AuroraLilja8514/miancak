package juloo.keyboard2;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the normal DocumentsUI route with the exact pinned public assets
 * staged by the disposable-guest driver. No app settings or files are injected. */
@RunWith(AndroidJUnit4.class)
public final class WanxiangSetupTest
{
  static boolean splitResources(Context context) throws IOException
  { return Arrays.asList(context.getAssets().list("rime")).contains("delivery.json"); }

  @Test public void installExactResourcesWhileOffline() throws Exception
  {
    android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    if (splitResources(context)) install(context, UiDevice.getInstance(instrumentation), false);
  }

  static void install(Context context, UiDevice device, boolean fresh)
  {
    String pkg = context.getPackageName();
    context.startActivity(new Intent(context, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    UiObject2 setup = device.wait(Until.findObject(By.res(pkg, "launcher_wanxiang_setup")), 15000);
    assertNotNull("Normal Chinese setup button was absent", setup); setup.click();
    String ready = context.getString(R.string.wanxiang_setup_ready);
    String files = context.getString(R.string.wanxiang_setup_files);
    assertTrue("Setup should finish checking the local resources", device.wait(Until.hasObject(
        By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(ready) + "|" + java.util.regex.Pattern.quote(files)))), 15000));
    if (!fresh && device.hasObject(By.res(pkg, "wanxiang_setup_status").text(ready)))
    { device.pressBack(); return; }
    UiObject2 pick = device.findObject(By.res(pkg, "wanxiang_setup_files").enabled(true));
    assertNotNull("A pack is missing; need Install from verified files", pick); pick.click();
    choose(device, "miancak-fixture-base.zip");
    choose(device, "miancak-fixture-grammar.gram");
    assertTrue("Exact native setup must become ready offline", device.wait(Until.hasObject(
        By.res(pkg, "wanxiang_setup_status").text(ready)), 180000));
    device.pressBack();
  }

  private static void choose(UiDevice device, String name)
  {
    UiObject2 file = device.wait(Until.findObject(By.text(name)), 5000);
    if (file == null)
    {
      UiObject2 downloads = device.findObject(By.text("Downloads"));
      if (downloads == null)
      {
        UiObject2 roots = device.findObject(By.desc("Show roots"));
        if (roots == null) roots = device.findObject(By.desc("Open navigation drawer"));
        assertNotNull("Android document roots list is unavailable", roots); roots.click();
        downloads = device.wait(Until.findObject(By.text("Downloads")), 15000);
      }
      assertNotNull("Android Downloads root is unavailable", downloads); downloads.click();
      file = device.wait(Until.findObject(By.text(name)), 15000);
    }
    assertNotNull("Exact public resource fixture is unavailable in Android Downloads: " + name, file); file.click();
  }
}
