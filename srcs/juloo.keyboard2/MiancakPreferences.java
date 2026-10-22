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
      editor.apply();
    }
  }
}
