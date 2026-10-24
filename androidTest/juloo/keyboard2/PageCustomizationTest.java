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
