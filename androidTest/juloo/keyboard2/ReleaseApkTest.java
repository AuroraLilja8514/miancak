package juloo.keyboard2;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** An independently debug-signed driver touches the real, non-debuggable APK.
 * Never uses the formal key, internal release objects, or release preference writes. */
@RunWith(AndroidJUnit4.class)
public final class ReleaseApkTest
{
  private static final String APP = "org.miancak.keyboard";
  private Context driver;
  private UiDevice device;
  private Config geometry;

  @Test public void actualSignedRelease() throws Exception
  {
    driver = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals(APP + ".debug", driver.getPackageName());
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    device.wakeUp(); device.setOrientationNatural();
    // Coordinate oracle uses identical compiled resource defaults in the driver.
    // It does not initialize or modify any preferences in the release package.
    geometry = Config.forPreview(driver, driver.getSharedPreferences("release-geometry", 0));
    String phase = InstrumentationRegistry.getArguments().getString("releasePhase", "verify");
    try
    {
      launch();
      if (phase.equals("prepare")) setPreferenceMarkers();
      else assertPreferenceMarkers();
      launch();
      focusEditor();
      assertEquals("中文", node("miancak_language").getText());
      type("nihao");
      UiObject2 candidate = device.wait(Until.findObject(By.descStartsWith("Candidate 1: ").text("你好")), 180000);
      assertNotNull("Real release did not deploy offline Rime or produce 你好", candidate);
      candidate.click();
      awaitEditor("你好");
      node("miancak_language").click();
      assertTrue(device.wait(Until.hasObject(By.res(APP, "miancak_language").text("EN")), 15000));
      type("hello");
      awaitEditor("你好hello");
      node("miancak_language").click();
      assertTrue(device.wait(Until.hasObject(By.res(APP, "miancak_language").text("中文")), 15000));
      screenshot(phase + "-actual-release-input");
      device.pressBack();
      assertPreferenceMarkers();
    }
    finally
    {
      screenshot(phase + "-final");
      device.unfreezeRotation();
    }
  }

  private void launch()
  {
    driver.startActivity(new Intent().setComponent(new ComponentName(APP, "juloo.keyboard2.LauncherActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    assertNotNull(node("launcher_enable"));
    device.waitForIdle();
  }

  private void settings() throws Exception
  {
    launch();
    assertNotNull(node("btnLaunchSettingsActivity"));
    // Re-resolve at click time: APK replacement can recreate the toolbar
    // after the first accessibility snapshot.
    device.findObject(new UiSelector().packageName(APP)
        .resourceId(APP + ":id/btnLaunchSettingsActivity")).click();
    assertTrue(device.wait(Until.hasObject(By.res("android", "list").pkg(APP)), 15000));
  }

  private void scrollTo(String text) throws Exception
  {
    if (!device.hasObject(By.pkg(APP).text(text)))
      new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(text));
    assertNotNull("Release preference missing: " + text, device.findObject(By.pkg(APP).text(text)));
  }

  private void setPreferenceMarkers() throws Exception
  {
    settings();
    String theme = driver.getString(R.string.pref_theme);
    scrollTo(theme); device.findObject(By.pkg(APP).text(theme)).click();
    UiObject2 light = device.wait(Until.findObject(By.text(driver.getString(R.string.pref_theme_e_light))), 15000);
    assertNotNull(light); light.click();
    // Explicitly disable Sup even when it is the old version's default. This
    // writes a real user choice, which the later default change must preserve.
    openExtraKeys();
    String title = superscriptTitle();
    scrollTo(title);
    UiObject2 toggle = checkbox(title);
    if (!toggle.isChecked()) { toggle.click(); device.waitForIdle(); }
    toggle = checkbox(title);
    assertTrue(toggle.isChecked()); toggle.click(); device.waitForIdle();
    assertFalse(checkbox(title).isChecked());
    device.pressBack(); device.pressBack();
  }

  private UiObject2 checkbox(String title)
  {
    UiObject2 row = device.findObject(By.pkg(APP).text(title)), box = null;
    for (int i=0; i<5 && row!=null && box==null; i++,row=row.getParent())
      box=row.findObject(By.checkable(true));
    assertNotNull("Release checkbox missing: " + title,box);
    return box;
  }

  private void assertPreferenceMarkers() throws Exception
  {
    settings();
    String theme = driver.getString(R.string.pref_theme);
    scrollTo(theme);
    // A preference title can be visible while its summary is below the
    // viewport (notably Android 6). Inspect the actual selected list value.
    device.findObject(By.pkg(APP).text(theme)).click();
    assertTrue("Explicit theme lost across release installation/reboot",
        device.wait(Until.hasObject(By.text(driver.getString(R.string.pref_theme_e_light))
            .checked(true)), 15000));
    device.pressBack();
    openExtraKeys();
    String title = superscriptTitle();
    scrollTo(title);
    assertFalse("Explicitly disabled Sup was reset by a later default", checkbox(title).isChecked());
    device.pressBack(); device.pressBack();
  }

  private void openExtraKeys() throws Exception
  {
    String entry=driver.getString(R.string.pref_extra_keys_title);
    scrollTo(entry);device.findObject(By.pkg(APP).text(entry)).click();
    assertTrue(device.wait(Until.hasObject(By.res("android","list").pkg(APP)),15000));
  }

  private String superscriptTitle()
  {
    return KeyValue.getKeyByName("superscript").getString()+" ("+
        driver.getString(R.string.key_descr_superscript)+")";
  }

  private UiObject2 node(String id)
  { return device.wait(Until.findObject(By.res(APP,id)),15000); }

  private void focusEditor() throws Exception
  {
    if (device.findObject(By.res(APP,"launcher_tryhere_area"))==null)
      new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(
          new UiSelector().resourceId(APP + ":id/launcher_tryhere_area"));
    UiObject2 editor=node("launcher_tryhere_area"); assertNotNull(editor); editor.click();
    assertNotNull("Actual release keyboard absent",node("keyboard_view"));
  }

  private void type(String text)
  {
    String[] rows={"qwertyuiop","asdfghjkl","zxcvbnm"};
    for(char character:text.toCharArray())
    {
      boolean found=false;
      for(int row=0;row<rows.length;row++)
      {
        int column=rows[row].indexOf(character);if(column<0)continue;
        Rect bounds=node("keyboard_view").getVisibleBounds();
        float width=(bounds.width()-2*geometry.horizontal_margin)/10f;
        float height=Math.min(geometry.keyboard_rows_height_pixels,
            (geometry.screenHeightPixels-geometry.keyboard_rows_height_pixels)/3.95f);
        float x=bounds.left+geometry.horizontal_margin+(column+(row==0?.5f:row==1?1f:2f))*width;
        float y=bounds.top+geometry.marginTop+(row+.5f)*height;
        assertTrue(device.click(Math.round(x),Math.round(y)));found=true;break;
      }
      assertTrue("Unsupported synthetic key",found);
    }
  }

  private void awaitEditor(String expected)
  {
    assertTrue("Actual signed APK did not commit expected text: "+expected,
        device.wait(Until.hasObject(By.res(APP,"launcher_tryhere_area").text(expected)),20000));
  }

  private void screenshot(String name) throws Exception
  {
    File directory=driver.getExternalFilesDir("release-results");
    assertTrue(directory.isDirectory()||directory.mkdirs());
    device.waitForIdle();
    assertTrue(device.takeScreenshot(new File(directory,name+".png")));
    device.dumpWindowHierarchy(new File(directory,name+".xml"));
  }
}
