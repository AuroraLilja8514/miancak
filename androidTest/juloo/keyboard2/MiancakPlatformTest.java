package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import juloo.keyboard2.input.EditorPolicy;
import juloo.keyboard2.prefs.LayoutsPreference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MiancakPlatformTest
{
  private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
  private SharedPreferences isolated(String name)
  {
    SharedPreferences prefs = context().getSharedPreferences("miancak-test-" + name, Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
    return prefs;
  }

  @Test public void firstInstallGetsMiancakLayoutAfterInheritedMigrations()
  {
    SharedPreferences prefs = isolated("fresh");
    MiancakPreferences.initialize(prefs);
    assertTrue(prefs.getInt("version", 0) > 0);
    assertEquals("no_number_row", prefs.getString("number_row", ""));
    assertTrue(prefs.getString("layouts", "").contains("miancak_qwerty"));
    assertTrue(prefs.getBoolean("lock_double_tap", false));
  }

  @Test public void explicitShiftLockPreferenceSurvivesInitialization()
  {
    SharedPreferences prefs = isolated("shift-preference");
    prefs.edit().putBoolean("lock_double_tap", false).commit();
    MiancakPreferences.initialize(prefs);
    assertFalse(prefs.getBoolean("lock_double_tap", true));
  }

  @Test public void legacyLayoutsAndBooleanNumberRowArePreservedByMigration()
  {
    SharedPreferences prefs = isolated("legacy");
    prefs.edit().putString("layout", "latn_dvorak").putBoolean("number_row", true).commit();
    MiancakPreferences.initialize(prefs);
    assertTrue(prefs.getString("layouts", "").contains("latn_dvorak"));
    assertFalse(prefs.getString("layouts", "").contains("miancak_qwerty"));
    assertEquals("no_symbols", prefs.getString("number_row", ""));
  }

  @Test public void modernSelectedLayoutIsNotOverwritten()
  {
    SharedPreferences prefs = isolated("modern");
    MiancakPreferences.initialize(prefs);
    SharedPreferences.Editor editor = prefs.edit();
    LayoutsPreference.save_to_preferences(editor, Collections.singletonList(new LayoutsPreference.NamedLayout("latn_colemak")));
    editor.commit();
    String layout = prefs.getString("layouts", "");
    MiancakPreferences.initialize(prefs);
    assertEquals(layout, prefs.getString("layouts", ""));
  }

  @Test public void protectedAndNoLearningFlagsUseTheExpectedPolicy()
  {
    EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT;
    assertTrue(EditorPolicy.from(info, context()).engineAllowed);
    assertTrue(EditorPolicy.from(info, context()).learning);
    info.imeOptions = 0x1000000;
    assertTrue(EditorPolicy.from(info, context()).engineAllowed);
    assertFalse(EditorPolicy.from(info, context()).learning);
    info.imeOptions = 0;
    info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    assertTrue(EditorPolicy.from(info, context()).engineAllowed);
    assertFalse(EditorPolicy.from(info, context()).learning);
    info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    assertFalse(EditorPolicy.from(info, context()).engineAllowed);
    info.inputType = InputType.TYPE_CLASS_NUMBER;
    assertFalse(EditorPolicy.from(info, context()).engineAllowed);
    info.inputType = InputType.TYPE_NULL;
    assertFalse(EditorPolicy.from(info, context()).engineAllowed);
  }
}
