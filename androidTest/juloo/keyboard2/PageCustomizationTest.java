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
  @Test public void malformedCurrentOverridesAreBackedUpBeforeBeingReplaced() throws Exception
  {
    SharedPreferences p = prefs(); p.edit().putInt("pages_version", 2).putInt(PageKeyOverrides.KEY, 37).commit();
    String first = PageKeyOverrides.raw(p);
    PageKeyOverrides edits = PageKeyOverrides.load(p);
    JSONObject backup = new JSONObject(p.getString(PageKeyOverrides.INVALID_BACKUP, ""));
    assertEquals("java.lang.Integer", backup.getString("type")); assertEquals(37, backup.getInt("value"));
    p.edit().putInt(PageKeyOverrides.KEY, 38).commit(); assertNotEquals(first, PageKeyOverrides.raw(p));
    edits.set("en", 0, 0, 0, false, PageKeyOverrides.literal("改")); edits.save(p);
    assertEquals(37, new JSONObject(p.getString(PageKeyOverrides.INVALID_BACKUP, "")).getInt("value"));
    assertEquals("改", PageKeyOverrides.load(p).apply("en", PageLayouts.base(context().getResources(), "en"))
        .rows.get(0).keys.get(0).keys[0].getSemanticText());
    p.edit().clear().putString(PageKeyOverrides.KEY, "{").commit(); PageKeyOverrides.load(p);
    assertEquals("{", new JSONObject(p.getString(PageKeyOverrides.INVALID_BACKUP, "")).getString("value"));
  }
  @Test public void shiftOnlyEditKeepsTheInheritedNormalLayerUnwrapped() throws Exception
  {
    PageKeyOverrides edits = new PageKeyOverrides(); edits.set("symbols", 0, 0, 0, true, PageKeyOverrides.literal("移"));
    KeyboardData data = edits.apply("symbols", PageLayouts.base(context().getResources(), "symbols"));
    KeyValue value = data.rows.get(0).keys.get(0).keys[0];
    assertEquals(KeyValue.Kind.Literal, value.layer(false).getKind());
    assertEquals("，", KeyModifier.preview(value, false, data.modmap).getSemanticText());
    assertEquals("移", KeyModifier.preview(value, true, data.modmap).getSemanticText());
  }
  @Test public void editorKeepsTypedActionsConfiguredFnAnchorsAndNumericOrdering() throws Exception
  {
    for (PageActions.Action action : PageActions.list(context().getResources(), true))
      assertNotEquals(action.id, KeyValue.Kind.String, action.value.getKind());
    assertEquals(KeyValue.Kind.Slider, KeyValue.getKeyByName("cursor_left").getKind());
    SharedPreferences settings = prefs(); MiancakPreferences.initialize(settings);
    KeyboardData prepared = PageLayouts.forEditor(context(), "en_fn", settings);
    KeyboardData.KeyPos f11 = prepared.getKeys().get(KeyValue.getKeyByName("f11"));
    assertNotNull(f11); assertEquals(0, f11.row); assertEquals(8, f11.col); assertEquals(1, f11.dir);
    PageKeyOverrides edits = new PageKeyOverrides(); edits.set("en_fn", f11.row, f11.col, f11.dir, false, PageKeyOverrides.literal("改"));
    assertEquals("改", edits.apply("en_fn", prepared).rows.get(f11.row).keys.get(f11.col).keys[f11.dir].getSemanticText());
    settings.edit().putString("numpad_layout", "low_first").commit();
    assertEquals(KeyValue.getKeyByName("f1"), PageLayouts.forEditor(context(), "numeric_fn", settings).rows.get(0).keys.get(2).keys[0]);
  }
  @Test public void symbolPagesKeepTheirOwnPortableEditsAndLegacyCoordinates() throws Exception
  {
    SharedPreferences preferences = prefs(); PageKeyOverrides edits = new PageKeyOverrides();
    edits.set("symbols", 0, 0, 0, false, PageKeyOverrides.literal("旧"));
    edits.set("symbols/math/1", 0, 0, 0, false, PageKeyOverrides.literal("新"));
    edits.set("symbols/math/1", 0, 0, 0, true, "f1"); edits.save(preferences);
    String json = PageKeyOverrides.export(preferences);
    preferences.edit().clear().commit(); PageKeyOverrides.importJson(preferences, context().getResources(), json);
    edits = PageKeyOverrides.load(preferences);
    KeyboardData common = edits.apply("symbols", PageLayouts.base(context().getResources(), "symbols"));
    assertEquals("旧", common.rows.get(0).keys.get(0).keys[0].getSemanticText());
    KeyboardData math = edits.apply("symbols/math/1", PageLayouts.base(context().getResources(), "symbols/math/1"));
    assertEquals("新", math.rows.get(0).keys.get(0).keys[0].layer(false).getSemanticText());
    assertEquals(KeyValue.Kind.Keyevent, math.rows.get(0).keys.get(0).keys[0].layer(true).getKind());
    JSONObject invalid = new JSONObject(json);
    invalid.getJSONObject("pages").put("symbols/math/999", new JSONObject());
    try { PageKeyOverrides.importJson(preferences, context().getResources(), invalid.toString()); fail(); }
    catch (IllegalArgumentException expected) {}
    assertEquals(json, PageKeyOverrides.export(preferences));
  }
  @Test public void phoneticLinksSurviveFileRestoreAndDoNotDisplaceFnNbsp() throws Exception
  {
    SharedPreferences preferences = prefs();
    PagePreferences.save(preferences, PagePreferences.load(preferences).withPhoneticEntry("zhuyin"));
    String exported = PageKeyOverrides.export(preferences);
    preferences.edit().clear().commit();
    PageKeyOverrides.importJson(preferences, context().getResources(), exported);
    juloo.keyboard2.input.PageConfig config = PagePreferences.load(preferences);
    assertEquals("zhuyin", config.phoneticEntry);
    for (String id : new String[] {"zh", "zh_fn"})
    {
      KeyboardData layout = PageLayouts.base(context().getResources(), id, config);
      KeyboardData.Key space = null;
      for (KeyboardData.Row row : layout.rows) for (KeyboardData.Key key : row.keys)
        if (KeyValue.getKeyByName("space").equals(key.keys[0])) space = key;
      assertNotNull(space);
      assertEquals(KeyValue.getKeyByName("switch_zhuyin"), space.keys[1]);
      if (id.equals("zh_fn")) assertTrue(layout.getKeys().containsKey(KeyValue.getKeyByName("nbsp")));
    }
    JSONObject invalid = new JSONObject(exported).put("phonetic_entry", "clipboard");
    try { PageKeyOverrides.importJson(preferences, context().getResources(), invalid.toString()); fail(); }
    catch (IllegalArgumentException expected) {}
    assertEquals(exported, PageKeyOverrides.export(preferences));
  }
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
    assertEquals(3, new JSONObject(PageKeyOverrides.export(preferences)).getInt("version"));
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

  @Test public void corruptPreferenceTypesRecoverWithoutLosingTheirBackup() throws Exception
  {
    SharedPreferences preferences = prefs();
    preferences.edit().putInt("pages_order", 9).putBoolean("pages_enabled", false)
      .putInt("pages_fn_zh", 7).putString("pages_version", "invalid").commit();
    PagePreferences.migrate(preferences);
    assertEquals("zh_fn", PagePreferences.load(preferences).fn("zh"));
    JSONObject previous = new JSONObject(preferences.getString("pages_v1_backup", ""));
    assertEquals(9, previous.getInt("order")); assertFalse(previous.getBoolean("enabled"));
    assertEquals(7, previous.getInt("fn_zh"));
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
