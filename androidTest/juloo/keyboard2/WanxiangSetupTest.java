package juloo.keyboard2;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
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

  static void install(Context context, UiDevice device, boolean fresh) throws Exception
  { install(context, device, fresh, false); }

  static void install(Context context, UiDevice device, boolean fresh, boolean https) throws Exception
  {
    String pkg = context.getPackageName();
    openSetup(context, device, pkg);
    if (!fresh && ready(context, device, pkg, 1))
    { device.pressBack(); return; }
    if (fresh)
    {
      ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
      assertNull("First-use fixture must be disconnected before the download action", network.getActiveNetwork());
      UiObject2 download = device.findObject(By.res(pkg, "wanxiang_setup_download").enabled(true));
      assertNotNull("The setup screen should offer download before installation", download); download.click();
      assertTrue("Disconnected setup must offer a local recovery path", device.wait(Until.hasObject(By.res(pkg,
          "wanxiang_setup_status").text(context.getString(R.string.wanxiang_setup_unavailable))), 15000));
      choosePair(device, pkg, "miancak-fixture-corrupt.gram");
      assertTrue("A corrupt model must be rejected before the app becomes ready", device.wait(Until.hasObject(By.res(pkg,
          "wanxiang_setup_status").text(context.getString(R.string.wanxiang_setup_integrity))), 15000));
    }
    if (https) download(context, device, pkg);
    else
    {
      choosePair(device, pkg, "miancak-fixture-grammar.gram");
      assertTrue("Exact native setup must become ready offline", ready(context, device, pkg, 180000));
    }
    device.pressBack();
  }

  static void installSignedAppFromNetwork(Context driver, UiDevice device, String pkg) throws Exception
  {
    openSetup(driver, device, pkg);
    if (!ready(driver, device, pkg, 1)) download(driver, device, pkg);
    device.pressBack();
  }

  static void assertReadyFromSettings(UiDevice device, String pkg)
  {
    String title = foreground(SettingsActivity.class).getString(R.string.wanxiang_setup_title);
    UiObject2 entry = device.wait(Until.findObject(By.pkg(pkg)
        .text(title).enabled(true)), 15000);
    assertNotNull("Wanxiang entry must be visible in Settings without scrolling", entry);
    entry.click();
    checkReadyWithoutDownload(foreground(WanxiangSetupActivity.class), device, pkg);
    phase("entry-settings");
    device.pressBack();
  }

  static void installFromFreshLauncher(Context context, UiDevice device, String pkg) throws Exception
  {
    WanxiangSetupActivity setup = tapLauncherEntry(context, device, pkg);
    assertFalse("The fresh guest must need the Wanxiang download", ready(setup, device, pkg, 1));
    download(setup, device, pkg, true);
    device.pressBack();
  }

  static void assertReadyFromLauncher(Context context, UiDevice device, String pkg)
  {
    checkReadyWithoutDownload(tapLauncherEntry(context, device, pkg), device, pkg);
    phase("entry-ready-again");
    device.pressBack();
  }

  private static void checkReadyWithoutDownload(Context context, UiDevice device, String pkg)
  {
    assertNull("Reopening setup must stay offline", context.getSystemService(ConnectivityManager.class).getActiveNetwork());
    assertTrue("Reopened setup did not show the installed pack ready offline", ready(context, device, pkg, 15000));
    assertFalse("Reopened setup offered a duplicate Download", device.hasObject(By.res(pkg, "wanxiang_setup_download")));
    assertFalse("Reopened setup started another transfer", device.hasObject(By.res(pkg, "wanxiang_setup_progress")));
  }

  static void phase(String name)
  {
    Bundle marker = new Bundle();
    marker.putString("miancak_stage", name);
    InstrumentationRegistry.getInstrumentation().sendStatus(2, marker);
  }

  private static <T extends Activity> T foreground(Class<T> activityType)
  {
    Instrumentation test = InstrumentationRegistry.getInstrumentation();
    Activity[] focused = {null};
    long deadline = SystemClock.uptimeMillis() + 15000;
    do
    {
      test.runOnMainSync(() -> {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
          if (activityType.isInstance(activity) && activity.hasWindowFocus()
              && activity.getWindow().getDecorView().isLaidOut()) focused[0] = activity;
      });
      if (focused[0] != null) return activityType.cast(focused[0]);
      SystemClock.sleep(50);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("Expected MIANCAK activity did not gain window focus: " + activityType.getSimpleName());
  }

  private static WanxiangSetupActivity tapLauncherEntry(Context context, UiDevice device, String pkg)
  {
    context.startActivity(Intent.makeMainActivity(new ComponentName(pkg, "juloo.keyboard2.LauncherActivity")));
    LauncherActivity launcher = foreground(LauncherActivity.class);
    assertEquals("Unexpected launcher package", pkg, launcher.getPackageName());
    assertTrue("The launcher user is locked", launcher.getSystemService(UserManager.class).isUserUnlocked());
    phase("entry-activity");

    int[] tap = new int[2];
    String[] resource = new String[1];
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      Rect window = launcher.getWindowManager().getCurrentWindowMetrics().getBounds();
      float density = launcher.getResources().getDisplayMetrics().density;
      assertTrue("Expected a portrait phone viewport", window.width() / density >= 320
          && window.width() / density <= 480 && window.height() / density >= 550);
      Button button = launcher.findViewById(R.id.launcher_wanxiang_setup);
      assertNotNull("The launcher layout is missing its Wanxiang button", button);
      assertTrue("The launcher Wanxiang button is disabled", button.isEnabled());
      assertTrue("The launcher Wanxiang button has no click action", button.isClickable());
      assertEquals("Wanxiang launcher label does not match the active locale",
          launcher.getText(R.string.wanxiang_setup_title).toString(), button.getText().toString());
      Rect visible = new Rect();
      assertTrue("The launcher Wanxiang button is clipped or offscreen", button.isShown()
          && button.getGlobalVisibleRect(visible) && visible.width() == button.getWidth()
          && visible.height() == button.getHeight());
      button.getLocationOnScreen(tap);
      tap[0] += button.getWidth() / 2;
      tap[1] += button.getHeight() / 2;
      resource[0] = launcher.getResources().getResourceName(button.getId());
    });
    phase("entry-viewport");
    phase("entry-launcher-visible");
    if (device.findObject(By.res(resource[0])) == null) phase("entry-selector-unmatched");
    assertTrue("Could not deliver the Wanxiang launcher tap", device.click(tap[0], tap[1]));
    WanxiangSetupActivity setup = foreground(WanxiangSetupActivity.class);
    phase("entry-launcher-tap");
    return setup;
  }

  private static void openSetup(Context context, UiDevice device, String pkg)
  {
    context.startActivity(new Intent().setComponent(new ComponentName(pkg, "juloo.keyboard2.LauncherActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    UiObject2 launcher = device.wait(Until.findObject(By.res(pkg, "launcher_wanxiang_setup")), 15000);
    assertNotNull("Normal Chinese setup button was absent", launcher);
    assertTrue("Normal Chinese setup button was disabled", launcher.isEnabled());
    launcher.click();
    String ready = context.getString(R.string.wanxiang_setup_ready);
    String files = context.getString(R.string.wanxiang_setup_files);
    assertTrue("Setup should finish checking the local resources", device.wait(Until.hasObject(
        By.pkg(pkg).text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(ready)
            + "|" + java.util.regex.Pattern.quote(files)))), 15000));
  }

  private static boolean ready(Context context, UiDevice device, String pkg, long timeout)
  { return device.wait(Until.hasObject(By.res(pkg, "wanxiang_setup_status")
      .text(context.getString(R.string.wanxiang_setup_ready))), timeout); }

  /** Invoked only from the guarded disposable guest drivers. Restore offline
   * operation before checking the actual receiving editor. */
  private static void download(Context context, UiDevice device, String pkg) throws Exception
  { download(context, device, pkg, false); }

  private static void download(Context context, UiDevice device, String pkg, boolean diagnostic) throws Exception
  {
    ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
    assertNotNull(network);
    assertNull("Setup must start with the guest offline", network.getActiveNetwork());
    Throwable primary = null;
    try
    {
      device.executeShellCommand("svc wifi enable");
      device.executeShellCommand("svc data enable");
      assertTrue("No default network for pinned HTTPS setup", waitNetwork(network, true, 30000));
      if (diagnostic) phase("entry-network");
      UiObject2 button = device.wait(Until.findObject(By.res(pkg, "wanxiang_setup_download").enabled(true)), 15000);
      assertNotNull("Normal setup did not offer Download", button); button.click();
      if (diagnostic) phase("entry-download");
      assertTrue("The exact HTTPS pack did not become ready", ready(context, device, pkg, 360000));
      if (diagnostic) phase("entry-ready");
    }
    catch (Exception | Error failure) { primary = failure; throw failure; }
    finally
    {
      ProgressEvidence.cleanup(primary, () -> {
        try { device.executeShellCommand("svc wifi disable"); }
        finally { device.executeShellCommand("svc data disable"); }
        assertTrue("Device must return offline before using the model", waitNetwork(network, false, 5000));
      });
    }
    if (diagnostic) phase("entry-offline");
  }

  private static boolean waitNetwork(ConnectivityManager network, boolean online, long duration)
  {
    long deadline = SystemClock.uptimeMillis() + duration;
    while ((network.getActiveNetwork() != null) != online && SystemClock.uptimeMillis() < deadline)
      SystemClock.sleep(50);
    return (network.getActiveNetwork() != null) == online;
  }

  private static void choosePair(UiDevice device, String pkg, String model)
  {
    UiObject2 pick = device.findObject(By.res(pkg, "wanxiang_setup_files").enabled(true));
    assertNotNull("A pack is missing; need Install from verified files", pick); pick.click();
    choose(device, "miancak-fixture-base.zip");
    choose(device, model);
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
