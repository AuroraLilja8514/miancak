package juloo.keyboard2;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.DisplayMetrics;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import static org.junit.Assert.*;

/** Resource/configuration checks complement the fresh-install UI journeys. */
@RunWith(AndroidJUnit4.class)
public final class SplitLayoutDefaultsTest
{
  private static final String ANDROID = "http://schemas.android.com/apk/res/android";
  private Context context()
  { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
  private SharedPreferences isolated(String name)
  {
    SharedPreferences preferences = context().getSharedPreferences("split-defaults-" + name, Context.MODE_PRIVATE);
    assertTrue(preferences.edit().clear().commit());
    MiancakPreferences.initialize(preferences);
    return preferences;
  }

  @Test public void settingsAndRuntimeUseTheSameNonSplitDefault() throws Exception
  {
    boolean found = false;
    try (XmlResourceParser xml = context().getResources().getXml(R.xml.settings))
    {
      for (int event = xml.getEventType(); event != XmlPullParser.END_DOCUMENT; event = xml.next())
        if (event == XmlPullParser.START_TAG && "split_layout".equals(xml.getAttributeValue(ANDROID, "key")))
        {
          assertEquals("never", xml.getAttributeValue(ANDROID, "defaultValue"));
          found = true;
        }
    }
    assertTrue("The actual settings XML must declare the split preference", found);
    SharedPreferences preferences = isolated("missing");
    assertFalse(preferences.contains("split_layout"));
    assertProfiles(preferences, "never");
    assertFalse("Reading a default must not turn it into a saved choice", preferences.contains("split_layout"));
  }

  @Test public void savedPoliciesIncludingOldAutomaticWideAreNeverRewritten()
  {
    for (String policy : new String[] {"wide", "landscape", "never", "future-policy"})
    {
      SharedPreferences preferences = isolated(policy);
      assertTrue(preferences.edit().putString("split_layout", policy).commit());
      Map<String, ?> before = new HashMap<>(preferences.getAll());
      // Older settings inflation persisted "wide" even without a deliberate
      // choice. It cannot safely be distinguished from a manual setting.
      MiancakPreferences.initialize(preferences);
      assertProfiles(preferences, policy);
      assertEquals(policy, preferences.getString("split_layout", ""));
      assertEquals(before, preferences.getAll());
    }
  }

  @Test public void removingOnlyTheSplitPreferenceRestoresTheNewDefault()
  {
    SharedPreferences preferences = isolated("reset");
    assertTrue(preferences.edit().putString("split_layout", "wide")
        .putBoolean("extra_key_superscript", false).putBoolean("extra_key_subscript", true)
        .putString(PageKeyOverrides.KEY, "{\"en\":{\"0:0:0\":\":literal:'kept'\"}}")
        .putString("default-test-marker", "keep unrelated settings").commit());
    assertProfiles(preferences, "wide");
    assertTrue(preferences.edit().remove("split_layout").commit());
    Map<String, ?> before = new HashMap<>(preferences.getAll());
    MiancakPreferences.initialize(preferences);
    assertProfiles(preferences, "never");
    assertEquals(before, preferences.getAll());
    // The page editor's reset is intentionally not a global preferences reset.
    assertEquals(":literal:'kept'", PageKeyOverrides.load(preferences).action("en", 0, 0, 0, false));
  }

  private void assertProfiles(SharedPreferences preferences, String policy)
  {
    for (int width : new int[] {360, 599, 600, 840})
      for (int orientation : new int[] {Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE})
        for (boolean unfolded : new boolean[] {false, true})
        {
          Context display = displayContext(width, orientation);
          Config.initGlobalConfig(preferences, display.getResources(), unfolded,
              juloo.keyboard2.dict.Dictionaries.instance(context()));
          Config live = Config.globalConfig();
          Config preview = Config.forPreview(display, preferences);
          assertSame("Activity previews must not replace the live IME config", live, Config.globalConfig());
          for (Config config : new Config[] {live, preview})
          {
            assertEquals(width >= Config.WIDE_DEVICE_THRESHOLD, config.wide_screen);
            assertEquals(orientation == Configuration.ORIENTATION_LANDSCAPE, config.orientation_landscape);
            assertEquals(unfolded, config.foldable_unfolded);
            boolean expected = policy.equals("wide") ? config.wide_screen
              : policy.equals("landscape") && config.orientation_landscape;
            assertEquals(policy + " at width=" + width + ", orientation=" + orientation + ", unfolded=" + unfolded,
                expected, config.split_layout);
          }
        }
  }

  @SuppressWarnings("deprecation")
  private Context displayContext(int widthDp, int orientation)
  {
    Resources original = context().getResources();
    DisplayMetrics metrics = new DisplayMetrics();
    metrics.setTo(original.getDisplayMetrics());
    metrics.widthPixels = Math.round(widthDp * metrics.density);
    metrics.heightPixels = Math.round((orientation == Configuration.ORIENTATION_LANDSCAPE ? 360 : 1000) * metrics.density);
    Configuration configuration = new Configuration(original.getConfiguration());
    configuration.orientation = orientation;
    configuration.screenWidthDp = widthDp;
    configuration.screenHeightDp = Math.round(metrics.heightPixels / metrics.density);
    configuration.smallestScreenWidthDp = Math.min(configuration.screenWidthDp, configuration.screenHeightDp);
    final Resources resources = new Resources(original.getAssets(), metrics, configuration);
    return new ContextWrapper(context()) {
      @Override public Resources getResources() { return resources; }
    };
  }
}
