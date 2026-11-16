package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.CheckBoxPreference;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import juloo.keyboard2.input.PageConfig;
import juloo.keyboard2.input.SymbolCatalog;
import juloo.keyboard2.prefs.ExtraKeysPreference;
import juloo.keyboard2.prefs.LayoutsPreference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Component/Settings checks complement the independent first-install receiver. */
@RunWith(AndroidJUnit4.class)
public final class ExtraKeyDefaultsTest
{
  private static final String[] MODIFIERS = {"superscript", "subscript"};
  private static final String[] TEXT_PAGES = {"zh", "en", "zh_fn", "en_fn"};
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();

  @Test public void missingFieldsAdoptOnlyTheTwoNewDefaultsWithoutBeingWritten()
  {
    SharedPreferences prefs = isolated("missing");
    Set<KeyValue> expected = new HashSet<>();
    for (String name : new String[] {"voice_typing", "change_method", "switch_clipboard",
        "compose", "tab", "esc", "f11_placeholder", "f12_placeholder", "superscript", "subscript"})
      expected.add(KeyValue.getKeyByName(name));
    assertEquals(expected, ExtraKeysPreference.get_extra_keys(prefs).keySet());
    assertTrue("Reading defaults must not write a migration", prefs.getAll().isEmpty());
    MiancakPreferences.initialize(prefs);
    Map<String, ?> before = prefs.getAll();
    assertEquals(expected, ExtraKeysPreference.get_extra_keys(prefs).keySet());
    assertEquals(before, prefs.getAll());
    for (String name : MODIFIERS) assertFalse(prefs.contains(prefKey(name)));
  }

  @Test public void eachMissingTrueAndFalseCombinationSurvivesInitializationAndPreview()
  {
    for (Boolean sup : new Boolean[] {null, true, false})
      for (Boolean sub : new Boolean[] {null, true, false})
      {
        SharedPreferences prefs = isolated("stored");
        putChoice(prefs, "superscript", sup); putChoice(prefs, "subscript", sub);
        assertEnabled(prefs, "superscript", sup == null || sup);
        assertEnabled(prefs, "subscript", sub == null || sub);
        MiancakPreferences.initialize(prefs);
        Map<String, ?> before = prefs.getAll();
        Config preview = Config.forPreview(context, prefs);
        for (String page : TEXT_PAGES)
        {
          KeyboardData data = PageLayouts.forEditor(context, page, prefs);
          assertEquals(page + ": Sup", sup == null || sup, data.getKeys().containsKey(key("superscript")));
          assertEquals(page + ": Sub", sub == null || sub, data.getKeys().containsKey(key("subscript")));
        }
        assertEquals(sup == null || sup, preview.extra_keys_param.containsKey(key("superscript")));
        assertEquals(sub == null || sub, preview.extra_keys_param.containsKey(key("subscript")));
        MiancakPreferences.initialize(prefs);
        assertEquals("No saved false may be guessed to be an old automatic default", before, prefs.getAll());
      }
  }

  @Test public void shippedSettingsCheckboxesUseTheSameDefaultsAndPreserveStoredChoices()
  {
    SharedPreferences actual = DirectBootAwarePreferences.get_shared_preferences(context);
    SharedPreferences backup = isolated("settings-backup");
    DirectBootAwarePreferences.copy_shared_preferences(actual, backup);
    try
    {
      for (Boolean choice : new Boolean[] {null, true, false})
      {
        // Do not recopy a credential-protected store into this isolated case.
        assertTrue(actual.edit().clear().putBoolean("need_migration", false).commit());
        for (String name : MODIFIERS) putChoice(actual, name, choice);
        SettingsActivity activity = (SettingsActivity)instrumentation.startActivitySync(
            new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try
        {
          instrumentation.runOnMainSync(() -> {
            for (String name : MODIFIERS)
            {
              assertTrue(activity.findPreference(prefKey(name)) instanceof CheckBoxPreference);
              CheckBoxPreference checkbox = (CheckBoxPreference)activity.findPreference(prefKey(name));
              assertEquals(name, choice == null || choice, checkbox.isChecked());
              assertEnabled(actual, name, checkbox.isChecked());
              // Preference inflation persists its initial value. In particular,
              // a false written by an older Settings screen must stay false.
              assertEquals(checkbox.isChecked(), actual.getBoolean(prefKey(name), !checkbox.isChecked()));
            }
          });
        }
        finally
        {
          instrumentation.runOnMainSync(activity::finish);
          instrumentation.waitForIdleSync();
        }
      }
    }
    finally
    {
      assertTrue(actual.edit().clear().commit());
      DirectBootAwarePreferences.copy_shared_preferences(backup, actual);
      assertTrue(actual.edit().commit());
      assertEquals(backup.getAll(), actual.getAll());
    }
  }

  @Test public void mainAndPairedFnLayoutsKeepExistingActionsAndBothShiftLayers()
  {
    SharedPreferences prefs = initialized("main-slots");
    initConfig(prefs);
    for (String page : TEXT_PAGES)
    {
      KeyboardData source = PageLayouts.base(context.getResources(), page);
      KeyboardData runtime = LayoutModifier.modify_layout(source, PageConfig.isVariant(page), null, page);
      KeyboardData editor = PageLayouts.forEditor(context, page, prefs);
      assertOccupiedSlotsRetained(source, runtime, true);
      assertOccupiedSlotsRetained(source, editor);
      for (String name : MODIFIERS)
      {
        for (KeyboardData data : Arrays.asList(runtime, editor))
        {
          KeyboardData.KeyPos pos = data.getKeys().get(key(name));
          assertNotNull(page + ": " + name, pos);
          assertFalse("An extra must not replace a shared control", LayoutControls.shared(
              source.rows.get(pos.row).keys.get(pos.col), pos.dir));
          assertEquals(KeyValue.Kind.Modifier, at(data, pos).getKind());
          for (boolean shift : new boolean[] {false, true})
          {
            assertEquals(key(name), KeyModifier.preview(at(data, pos), shift, data.modmap));
            Pointers.Modifiers mods = Pointers.Modifiers.EMPTY.with_extra_mod(key(name));
            if (shift) mods = mods.with_extra_mod(KeyValue.SHIFT);
            KeyboardData.KeyPos n = data.getKeys().get(key("n")); assertNotNull(n);
            // Existing order applies Sup/Sub before Shift; shift.json maps
            // superscript n to its uppercase form, but leaves subscript n.
            assertEquals(name.equals("superscript") ? (shift ? "ᴺ" : "ⁿ") : "ₙ",
                KeyModifier.modify(at(data, n), mods, data.modmap).getString());
            if (!PageConfig.isVariant(page))
              assertEquals(name.equals("superscript") ? "²" : "₂",
                  KeyModifier.modify(at(data, data.getKeys().get(key("2"))), mods, data.modmap).getString());
          }
        }
      }
      if (PageConfig.isVariant(page))
        for (int function = 1; function <= 12; function++)
          assertTrue(page + ": F" + function, runtime.getKeys().containsKey(key("f" + function)));
    }
  }

  @Test public void denseCustomLayoutNeverLosesAnExistingSlotToTheDefaults() throws Exception
  {
    SharedPreferences prefs = initialized("dense");
    String xml = "<keyboard name=\"Dense\" bottom_row=\"false\" locale_extra_keys=\"false\">"
      + "<row><key c=\"q\" nw=\"f1\" ne=\"f2\" sw=\"f3\" se=\"f4\""
      + " w=\"left\" e=\"right\" n=\"up\" s=\"down\" anticircle=\"copy\"/></row></keyboard>";
    selectCustom(prefs, xml); initConfig(prefs);
    Map<String, ?> before = prefs.getAll();
    KeyboardData source = KeyboardData.load_string_exn(xml);
    KeyboardData runtime = LayoutModifier.modify_layout(source, false, null, "en");
    KeyboardData editor = PageLayouts.forEditor(context, "en", prefs);
    assertOccupiedSlotsRetained(source, runtime, true); assertOccupiedSlotsRetained(source, editor);
    for (String name : MODIFIERS)
    {
      assertFalse("A full custom layout has no slot to inject " + name, runtime.getKeys().containsKey(key(name)));
      assertFalse(editor.getKeys().containsKey(key(name)));
    }
    assertEquals(before, prefs.getAll());
  }

  @Test public void customXmlAndPerLayerOverridesRemainAuthoritative() throws Exception
  {
    SharedPreferences prefs = initialized("custom-edits");
    String xml = "<keyboard name=\"Custom\" bottom_row=\"false\" locale_extra_keys=\"false\">"
      + "<row><key c=\"q\" nw=\"copy\"/><key c=\"w\" ne=\"paste\"/></row>"
      + "<row><key c=\"n\"/><key c=\"m\"/></row></keyboard>";
    selectCustom(prefs, xml);
    assertTrue(prefs.edit().putString("custom_extra_keys", "[\"undo\"]").commit());
    KeyboardData prepared = PageLayouts.forEditor(context, "en", prefs);
    KeyboardData.KeyPos sup = prepared.getKeys().get(key("superscript"));
    KeyboardData.KeyPos sub = prepared.getKeys().get(key("subscript"));
    assertNotNull(sup); assertNotNull(sub); assertTrue(prepared.getKeys().containsKey(key("undo")));
    PageKeyOverrides overrides = new PageKeyOverrides();
    overrides.set("en", sup.row, sup.col, sup.dir, false, PageKeyOverrides.literal("保留"));
    overrides.set("en", sup.row, sup.col, sup.dir, true, "f12");
    overrides.set("en", sub.row, sub.col, sub.dir, false, ""); overrides.save(prefs);
    Map<String, ?> before = prefs.getAll();
    MiancakPreferences.initialize(prefs); initConfig(prefs);
    KeyboardData source = KeyboardData.load_string_exn(xml);
    KeyboardData runtime = LayoutModifier.modify_layout(source, false, PageKeyOverrides.load(prefs), "en");
    assertEquals("保留", KeyModifier.preview(at(runtime, sup), false, runtime.modmap).getSemanticText());
    assertEquals(key("f12"), KeyModifier.preview(at(runtime, sup), true, runtime.modmap));
    assertNull(at(runtime, sub));
    assertEquals(key("copy"), runtime.rows.get(0).keys.get(0).keys[1]);
    assertEquals(key("paste"), runtime.rows.get(0).keys.get(1).keys[2]);
    assertEquals("Custom", PageLayouts.forEditor(context, "en", prefs).name);
    assertEquals(before, prefs.getAll());
  }

  @Test public void symbolEmojiAndClipboardLayoutsNeverInheritTheDefaults()
  {
    SharedPreferences prefs = initialized("auxiliary"); initConfig(prefs);
    for (String category : SymbolCatalog.categories())
      for (int page = 0; page < SymbolCatalog.pageCount(category); page++)
      {
        String scope = SymbolCatalog.scope(category, page);
        KeyboardData runtime = LayoutModifier.modify_layout(PageLayouts.base(context.getResources(), scope), false, null, scope);
        for (String name : MODIFIERS) assertFalse(scope, runtime.getKeys().containsKey(key(name)));
        for (String name : MODIFIERS) assertFalse(scope, PageLayouts.forEditor(context, scope, prefs).getKeys().containsKey(key(name)));
      }
    // Dynamic panes use their own controls rather than modify_layout().
    for (String page : new String[] {"emoji", "clipboard"})
      for (String name : MODIFIERS)
      {
        assertFalse(page, PageLayouts.base(context.getResources(), page).getKeys().containsKey(key(name)));
        assertFalse(page, PageLayouts.forEditor(context, page, prefs).getKeys().containsKey(key(name)));
      }
  }

  private SharedPreferences isolated(String name)
  {
    SharedPreferences prefs = context.getSharedPreferences("extra-key-defaults-" + name, Context.MODE_PRIVATE);
    assertTrue(prefs.edit().clear().commit()); return prefs;
  }
  private SharedPreferences initialized(String name)
  { SharedPreferences prefs = isolated(name); MiancakPreferences.initialize(prefs); return prefs; }
  private void initConfig(SharedPreferences prefs)
  {
    Config.initGlobalConfig(prefs, context.getResources(), false, juloo.keyboard2.dict.Dictionaries.instance(context));
    Config config = Config.globalConfig();
    config.split_layout = false; config.show_numpad = false; config.add_number_row = false;
  }
  private static KeyValue key(String name) { return KeyValue.getKeyByName(name); }
  private static String prefKey(String name) { return ExtraKeysPreference.pref_key_of_key_name(name); }
  private static void putChoice(SharedPreferences prefs, String name, Boolean value)
  {
    SharedPreferences.Editor editor = prefs.edit();
    if (value == null) editor.remove(prefKey(name)); else editor.putBoolean(prefKey(name), value);
    assertTrue(editor.commit());
  }
  private static void assertEnabled(SharedPreferences prefs, String name, boolean expected)
  { assertEquals(name, expected, ExtraKeysPreference.get_extra_keys(prefs).containsKey(key(name))); }
  private static KeyValue at(KeyboardData data, KeyboardData.KeyPos pos)
  { assertNotNull(pos); return data.rows.get(pos.row).keys.get(pos.col).keys[pos.dir]; }
  private static void selectCustom(SharedPreferences prefs, String xml)
  {
    SharedPreferences.Editor editor = prefs.edit();
    LayoutsPreference.save_to_preferences(editor, Collections.singletonList(LayoutsPreference.CustomLayout.parse(xml)));
    assertTrue(editor.putInt("current_layout_portrait", 0).putInt("current_layout_landscape", 0).commit());
  }
  private static void assertOccupiedSlotsRetained(KeyboardData before, KeyboardData after)
  { assertOccupiedSlotsRetained(before, after, false); }
  private static void assertOccupiedSlotsRetained(KeyboardData before, KeyboardData after, boolean presented)
  {
    assertEquals(before.rows.size(), after.rows.size());
    for (int row = 0; row < before.rows.size(); row++)
    {
      assertEquals(before.rows.get(row).keys.size(), after.rows.get(row).keys.size());
      for (int col = 0; col < before.rows.get(row).keys.size(); col++)
      {
        KeyboardData.Key original = before.rows.get(row).keys.get(col), actual = after.rows.get(row).keys.get(col);
        assertEquals(original.width, actual.width, 0f); assertEquals(original.shift, actual.shift, 0f);
        assertEquals(original.role, actual.role); assertEquals(original.anticircle, actual.anticircle);
        for (int direction = 0; direction < 9; direction++)
          if (original.keys[direction] != null && !original.keyHasFlag(direction, KeyboardData.Key.F_LOC))
          {
            // Runtime presentation may replace ACTION/Enter and voice keys
            // for the current editor; extra injection must not displace them.
            KeyValue expected = presented ? LayoutModifier.modify_key(original.keys[direction]) : original.keys[direction];
            for (boolean shift : new boolean[] {false, true})
              assertEquals(row + ":" + col + ":" + direction + ":shift=" + shift,
                  KeyModifier.preview(expected, shift, before.modmap),
                  KeyModifier.preview(actual.keys[direction], shift, after.modmap));
          }
      }
    }
  }
}
