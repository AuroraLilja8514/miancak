package juloo.keyboard2;

import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceActivity;

public class SettingsActivity extends PreferenceActivity
{
  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    // Settings and the IME must use the same store. Copying the activity's
    // credential-protected defaults back on stop overwrote IME-first setup.
    if (Build.VERSION.SDK_INT >= 24)
      getPreferenceManager().setStorageDeviceProtected();
    try
    {
      MiancakPreferences.initialize(
          DirectBootAwarePreferences.get_shared_preferences(this));
    }
    catch (Exception _e) { fallbackEncrypted(); return; }
    addPreferencesFromResource(R.xml.settings);
    findPreference("pages").setOnPreferenceClickListener(preference -> {
      startActivity(new android.content.Intent(this, PageSettingsActivity.class)); return true;
    });
    findPreference("word_tables").setOnPreferenceClickListener(preference -> {
      startActivity(new android.content.Intent(this, WordTableActivity.class)); return true;
    });
    if (Build.VERSION.SDK_INT >= 24 && !((android.os.UserManager)getSystemService(USER_SERVICE)).isUserUnlocked())
    { findPreference("word_tables").setEnabled(false); findPreference("word_tables").setSummary(R.string.words_unlock); }

    boolean foldableDevice = FoldStateTracker.isFoldableDevice(this);
    findPreference("margin_bottom_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("margin_bottom_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_landscape_unfolded").setEnabled(foldableDevice);
  }

  void fallbackEncrypted()
  {
    // Can't communicate with the user here.
    finish();
  }

}
