package juloo.keyboard2;

import android.content.SharedPreferences;
import java.util.Collections;
import juloo.keyboard2.prefs.LayoutsPreference;

/** Run inherited migrations before adding MIANCAK's first-install defaults. */
public final class MiancakPreferences
{
  private MiancakPreferences() {}
  public static void initialize(SharedPreferences preferences)
  {
    boolean fresh = !preferences.contains("version") && !preferences.contains("layouts")
      && !preferences.contains("layout") && !preferences.contains("second_layout")
      && !preferences.contains("custom_layout");
    Config.migrate(preferences);
    if (fresh)
    {
      SharedPreferences.Editor editor = preferences.edit();
      LayoutsPreference.save_to_preferences(editor, Collections.singletonList(
            new LayoutsPreference.NamedLayout("miancak_qwerty")));
      if (!preferences.contains("lock_double_tap"))
        editor.putBoolean("lock_double_tap", true);
      editor.apply();
    }
    retire_main_layouts(preferences);
  }

  /** Retire shipped layouts once, retaining custom XML and the original data. */
  private static void retire_main_layouts(SharedPreferences preferences)
  {
    if (preferences.getInt("miancak_layout_version", 0) >= 1) return;
    String original = preferences.getString("layouts", "[]");
    org.json.JSONArray result = new org.json.JSONArray(); result.put("miancak_qwerty");
    int portrait = 0, landscape = 0;
    try
    {
      org.json.JSONArray old = new org.json.JSONArray(original);
      for (int i = 0; i < old.length(); i++)
      {
        org.json.JSONObject item = old.optJSONObject(i);
        if (item != null && item.optString("kind").equals("custom")
            && LayoutsPreference.CustomLayout.parse(item.optString("xml")).parsed != null)
        {
          int index = result.length(); result.put(item);
          if (i == preferences.getInt("current_layout_portrait", 0)) portrait = index;
          if (i == preferences.getInt("current_layout_landscape", 0)) landscape = index;
        }
      }
    }
    catch (org.json.JSONException ignored) { /* Original is preserved below. */ }
    SharedPreferences.Editor editor = preferences.edit();
    if (!preferences.contains("miancak_previous_layouts")) editor.putString("miancak_previous_layouts", original);
    editor.putString("layouts", result.toString()).putInt("current_layout_portrait", portrait)
      .putInt("current_layout_landscape", landscape).putInt("miancak_layout_version", 1).apply();
  }
}
