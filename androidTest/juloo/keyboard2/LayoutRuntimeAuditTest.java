package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import juloo.keyboard2.input.PageConfig;
import juloo.keyboard2.input.SymbolCatalog;
import juloo.keyboard2.input.SymbolState;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runtime inventories complement receiver tests; they do not simulate touches. */
@RunWith(AndroidJUnit4.class)
public final class LayoutRuntimeAuditTest
{
  @Test public void recordPresentedSlotsAndRetainedFnActionsAcrossSixConfigurations() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences p = context.getSharedPreferences("layout-runtime-audit", Context.MODE_PRIVATE);
    p.edit().clear().commit(); MiancakPreferences.initialize(p);
    File dir = TestEvidence.directory(context);
    try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "layout-runtime-slots.jsonl")), StandardCharsets.UTF_8)))
    {
      for (String order : Arrays.asList("high_first", "low_first"))
        for (String presentation : Arrays.asList("plain", "number-row", "numpad"))
        {
          p.edit().putString("numpad_layout", order).putString("number_row", presentation.equals("number-row") ? "symbols" : "no_number_row")
            .putString("show_numpad", presentation.equals("numpad") ? "always" : "never").commit();
          Config.initGlobalConfig(p, context.getResources(), false, juloo.keyboard2.dict.Dictionaries.instance(context));
          Config config = Config.globalConfig(); config.device_locales = DeviceLocales.load(context);
          config.extra_keys_subtype = config.device_locales.extra_keys();
          String profile = order + "/" + presentation;
          for (String id : PageConfig.IDS) inventory(out, profile, id, layout(context, id));
          for (String category : SymbolCatalog.categories())
            for (int page = 0; page < SymbolCatalog.pageCount(category); page++)
              if (!SymbolCatalog.scope(category, page).equals("symbols"))
                inventory(out, profile, SymbolCatalog.scope(category, page),
                    LayoutModifier.modify_layout(PageLayouts.symbols(context.getResources(), new SymbolState(category, page)), false));
          // Every actually presented English source action must remain
          // reachable after its upstream Fn transformation. Shared controls
          // and Compose are intentionally excluded from this transformation.
          KeyboardData base = layout(context, "en"), fn = layout(context, "en_fn");
          Set<String> actual = new HashSet<>();
          for (KeyboardData.Row row : fn.rows) for (KeyboardData.Key key : row.keys)
            for (KeyValue value : key.keys) if (value != null) actual.add(action(KeyModifier.preview(value, false, fn.modmap)).toString());
          for (KeyboardData.Row row : base.rows) for (KeyboardData.Key key : row.keys)
            for (int d = 0; d < 9; d++)
            {
              KeyValue source = key.keys[d];
              if (source == null || LayoutControls.shared(key, d) || source.getKind() == KeyValue.Kind.Compose_pending) continue;
              KeyValue expected = KeyModifier.fn_value(source, base.modmap);
              if (expected == null || expected.getString().isEmpty()) continue;
              assertTrue(profile + " lost Fn action for " + source + " => " + expected,
                  actual.contains(action(expected).toString()));
            }
        }
    }
    finally
    {
      // Leave default presentation for the next independent instrumentation;
      // the live IME initializes its own Config when it is started.
      p.edit().putString("number_row", "no_number_row").putString("show_numpad", "never").putString("numpad_layout", "high_first").commit();
      Config.initGlobalConfig(p, context.getResources(), false, juloo.keyboard2.dict.Dictionaries.instance(context));
    }
  }
  private KeyboardData layout(Context context, String id)
  {
    KeyboardData main = PageLayouts.base(context.getResources(), "zh"), data = PageLayouts.base(context.getResources(), id);
    if (PageConfig.base(id).equals("numeric")) return PageLayouts.numeric(context.getResources(), PageConfig.isVariant(id), main);
    if (PageConfig.base(id).equals("greek")) return LayoutModifier.modify_numpad(data, main);
    if (id.equals("emoji") || id.equals("clipboard")) return data;
    return LayoutModifier.modify_layout(data, PageConfig.isVariant(id));
  }
  private void inventory(Writer out, String profile, String id, KeyboardData data) throws Exception
  {
    for (int r = 0; r < data.rows.size(); r++) for (int c = 0; c < data.rows.get(r).keys.size(); c++)
    {
      KeyboardData.Key key = data.rows.get(r).keys.get(c);
      for (int direction = 0; direction < 9; direction++)
      {
        JSONObject row = new JSONObject().put("profile", profile).put("layout", id).put("row", r).put("column", c)
          .put("slot", direction).put("width", key.width).put("shared", LayoutControls.shared(key, direction))
          .put("normal", action(KeyModifier.preview(key.keys[direction], false, data.modmap)))
          .put("shift", action(KeyModifier.preview(key.keys[direction], true, data.modmap)));
        out.write(row.toString()); out.write('\n');
      }
    }
  }
  private JSONObject action(KeyValue value) throws Exception
  {
    JSONObject result = new JSONObject(); if (value == null) return result.put("kind", "empty");
    result.put("kind", value.getKind().name());
    switch (value.getKind())
    {
      case Char: return result.put("text", String.valueOf(value.getChar()));
      case Literal: case Engine: return result.put("text", value.getSemanticText());
      case Keyevent: return result.put("keyCode", value.getKeyevent());
      case Event: return result.put("event", value.getEvent().name());
      case Editing: return result.put("editing", value.getEditing().name());
      case Modifier: return result.put("modifier", value.getModifier().name());
      case Slider: return result.put("slider", value.getSlider().name()).put("repeat", value.getSliderRepeat());
      default: return result.put("label", value.getString());
    }
  }
}
