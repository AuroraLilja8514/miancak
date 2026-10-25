package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PageCustomizationTest
{
  @Test public void legacyFnMigrationPreservesOriginalKeysAndImportSource() throws Exception
  {
    SharedPreferences preferences = prefs();
    String keys = "{\"zh\":{\"0:0:1\":\"q\"}}";
    preferences.edit().putString(PageKeyOverrides.KEY, keys).putString("pages_fn_zh", "pinyin")
      .putString("pages_fn_en", "clipboard").putString("pages_order", "pinyin,en,zh")
      .putString("pages_enabled", "zh,en,pinyin").putInt("pages_version", 1).commit();
    PagePreferences.migrate(preferences);
    JSONObject backup = new JSONObject(preferences.getString("pages_v1_backup", ""));
    assertEquals("pinyin", backup.getString("fn_zh"));
    assertEquals("clipboard", backup.getString("fn_en"));
    assertEquals(keys, backup.getString("pages_raw"));
    assertEquals(keys, preferences.getString(PageKeyOverrides.KEY, ""));
    assertEquals("zh_fn", PagePreferences.load(preferences).fn("zh"));
    assertEquals("en_fn", PagePreferences.load(preferences).fn("en"));
    assertFalse(preferences.contains("pages_fn_zh"));
    String originalBackup = preferences.getString("pages_v1_backup", "");
    PagePreferences.migrate(preferences); assertEquals(originalBackup, preferences.getString("pages_v1_backup", ""));
    JSONObject legacy = new JSONObject().put("version", 1).put("pages", new JSONObject(keys))
      .put("order", "zh,en").put("enabled", "zh,en,pinyin").put("fn_zh", "pinyin").put("fn_en", "numeric");
    PageKeyOverrides.importJson(preferences, context().getResources(), legacy.toString());
    assertEquals(legacy.toString(), preferences.getString(PageKeyOverrides.KEY + "_import_source", ""));
    assertEquals(2, new JSONObject(PageKeyOverrides.export(preferences)).getInt("version"));
    assertEquals("zh_fn", PagePreferences.load(preferences).fn("zh"));
  }

  @Test public void everyBuiltInVariantKeepsSharedDirectionsAndEditsAreIndependent() throws Exception
  {
    for (String id : juloo.keyboard2.input.PageConfig.IDS)
    {
      KeyboardData layout = PageLayouts.base(context().getResources(), id);
      for (KeyboardData.Row row : layout.rows) for (KeyboardData.Key key : row.keys)
        if (KeyValue.getKeyByName("space").equals(key.keys[0]))
        {
          assertEquals(KeyValue.getKeyByName("switch_forward"), key.keys[7]);
          assertEquals(KeyValue.getKeyByName("switch_backward"), key.keys[8]);
          assertEquals(KeyValue.getKeyByName("cursor_left"), key.keys[5]);
          assertEquals(KeyValue.getKeyByName("cursor_right"), key.keys[6]);
        }
    }
    PageKeyOverrides edits = new PageKeyOverrides();
    edits.set("zh_fn", 0, 0, 2, false, "f12");
    KeyboardData fn = edits.apply("zh_fn", PageLayouts.base(context().getResources(), "zh_fn"));
    assertEquals(KeyValue.getKeyByName("f12"), fn.rows.get(0).keys.get(0).keys[2]);
    KeyboardData normal = edits.apply("zh", PageLayouts.base(context().getResources(), "zh"));
    assertEquals(KeyValue.getKeyByName("1"), normal.rows.get(0).keys.get(0).keys[2]);
  }

  private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
  private SharedPreferences prefs()
  {
    SharedPreferences value = context().getSharedPreferences("page-customization-tests", Context.MODE_PRIVATE);
    value.edit().clear().commit(); return value;
  }
  @Test public void equalNormalLabelsKeepIndependentShiftOutputs() throws Exception
  {
    PageKeyOverrides edits = new PageKeyOverrides();
    edits.set("zh", 0, 0, 0, false, PageKeyOverrides.literal("a"));
    edits.set("zh", 0, 1, 0, false, PageKeyOverrides.literal("a"));
    edits.set("zh", 0, 0, 0, true, PageKeyOverrides.literal("甲"));
    edits.set("zh", 0, 1, 0, true, PageKeyOverrides.literal("乙"));
    KeyboardData layout = edits.apply("zh", PageLayouts.base(context().getResources(), "zh"));
    KeyValue first = layout.rows.get(0).keys.get(0).keys[0], second = layout.rows.get(0).keys.get(1).keys[0];
    assertEquals("a", KeyModifier.modify(first, Pointers.Modifiers.EMPTY).getSemanticText());
    Pointers.Modifiers shift = Pointers.Modifiers.EMPTY.with_extra_mod(KeyValue.SHIFT);
    assertEquals("甲", KeyModifier.modify(first, shift).getSemanticText());
    assertEquals("乙", KeyModifier.modify(second, shift).getSemanticText());
    assertNotEquals(first, second);
  }
  @Test public void exactQuotesActionsAndDisabledSlotsSurviveExportImport() throws Exception
  {
    SharedPreferences preferences = prefs(); PageKeyOverrides edits = new PageKeyOverrides();
    String text = "a'\\‘中文’";
    edits.set("zh", 0, 0, 1, false, PageKeyOverrides.literal(text));
    edits.set("zh", 0, 0, 2, false, "fn_page");
    edits.set("zh", 0, 0, 3, false, ""); edits.save(preferences);
    String exported = PageKeyOverrides.export(preferences);
    preferences.edit().clear().commit();
    PageKeyOverrides.importJson(preferences, context().getResources(), exported);
    KeyboardData layout = PageKeyOverrides.load(preferences).apply("zh", PageLayouts.base(context().getResources(), "zh"));
    assertEquals(text, layout.rows.get(0).keys.get(0).keys[1].getSemanticText());
    assertEquals(KeyValue.Event.SWITCH_FN, layout.rows.get(0).keys.get(0).keys[2].getEvent());
    assertNull(layout.rows.get(0).keys.get(0).keys[3]);
    assertEquals(exported, PageKeyOverrides.export(preferences));
  }
  @Test public void malformedImportCannotPartiallyReplaceWorkingSettings() throws Exception
  {
    SharedPreferences preferences = prefs();
    String before = PageKeyOverrides.export(preferences);
    JSONObject invalid = new JSONObject(before);
    invalid.getJSONObject("pages").put("zh", new JSONObject().put("999:0:0", "q"));
    try { PageKeyOverrides.importJson(preferences, context().getResources(), invalid.toString()); fail(); }
    catch (IllegalArgumentException expected) {}
    assertEquals(before, PageKeyOverrides.export(preferences));
    invalid = new JSONObject(before).put("version", 500);
    try { PageKeyOverrides.importJson(preferences, context().getResources(), invalid.toString()); fail(); }
    catch (IllegalArgumentException expected) {}
    assertEquals(before, PageKeyOverrides.export(preferences));
    String huge = new String(new char[PageKeyOverrides.MAX_BYTES + 1]).replace('\0', 'x');
    try { PageKeyOverrides.importJson(preferences, context().getResources(), huge); fail(); }
    catch (IllegalArgumentException expected) {}
    assertEquals(before, PageKeyOverrides.export(preferences));
  }
  @Test public void savedEditsCannotOutgrowTheirOwnPortableFileLimit() throws Exception
  {
    SharedPreferences preferences = prefs(); String before = PageKeyOverrides.export(preferences);
    PageKeyOverrides edits = new PageKeyOverrides();
    String payload = new String(new char[1600]).replace('\0', '中');
    for (int i = 0; i < 60; i++) edits.set("zh", 0, i / 9, i % 9, false, PageKeyOverrides.literal(payload));
    try { edits.save(preferences); fail("Oversized configuration was saved"); }
    catch (IllegalArgumentException expected) {}
    assertEquals(before, PageKeyOverrides.export(preferences));
  }
}
