package juloo.keyboard2;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** On-device component traces through the shipped pointer recognizer. These
 * complement system-injected IME touches; they do not claim editor KeyEvents. */
@RunWith(AndroidJUnit4.class)
public final class PointerTraceTest
{
  private static File report;
  private Config config;
  private final List<Trace> traces = new ArrayList<>();
  private interface Step { void run() throws Exception; }

  @BeforeClass public static void resetReport() throws Exception
  {
    report = new File(TestEvidence.directory(context()), "pointer-traces.jsonl");
    try (FileOutputStream out = new FileOutputStream(report)) { out.getFD().sync(); }
  }

  private static Context context()
  { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

  @Before public void prepare() throws Exception
  {
    main(() -> {
      android.content.SharedPreferences prefs = context().getSharedPreferences("pointer-traces", Context.MODE_PRIVATE);
      prefs.edit().clear().commit(); MiancakPreferences.initialize(prefs);
      Config.initGlobalConfig(prefs, context().getResources(), false,
          juloo.keyboard2.dict.Dictionaries.instance(context()));
      config = Config.globalConfig();
      config.swipe_dist_px = 20; config.slide_step_px = 10;
      config.circle_sensitivity = 2;
      config.longPressTimeout = 60000;
      KeyModifier.set_modmap(null);
    });
  }

  @After public void cleanup() throws Exception
  {
    main(() -> {
      for (Trace trace : traces) trace.pointers.clear();
      KeyModifier.set_modmap(null);
      Config.initGlobalConfig(DirectBootAwarePreferences.get_shared_preferences(context()),
          context().getResources(), false, juloo.keyboard2.dict.Dictionaries.instance(context()));
    });
  }

  @Test public void tapAndAllEightCompassDirections() throws Exception
  {
    main(() -> {
      expect("tap", trace(key()), letter('a'));
      int[] slots = {7, 2, 6, 4, 8, 3, 5, 1}; // Documented compass order, starting north.
      for (int i = 0; i < slots.length; i++)
        expect("compass-" + i, trace(key(), polar(i * 45)), letter((char)('a' + slots[i])));
    });
  }

  @Test public void bothSidesOfEveryDirectionBoundary() throws Exception
  {
    main(() -> {
      int[] slots = {7, 2, 6, 4, 8, 3, 5, 1};
      for (int i = 0; i < slots.length; i++)
      {
        float boundary = 22.5f + i * 45;
        expect("boundary-" + i + "-before", trace(key(), polar(boundary - 2)), letter((char)('a' + slots[i])));
        expect("boundary-" + i + "-after", trace(key(), polar(boundary + 2)), letter((char)('a' + slots[(i + 1) % slots.length])));
      }
    });
  }

  @Test public void swipeDistanceChangesTheSamePhysicalTrace() throws Exception
  {
    main(() -> {
      expect("distance-below", trace(key(), new float[] {19, 0}), letter('a'));
      expect("distance-at", trace(key(), new float[] {20, 0}), letter('g'));
      config.swipe_dist_px = 60;
      expect("distance-raised", trace(key(), new float[] {40, 0}), letter('a'));
      config.swipe_dist_px = 20;
      expect("distance-lowered", trace(key(), new float[] {40, 0}), letter('g'));
    });
  }

  @Test public void roundtripUsesTheDirectionAndRetainsExplicitShift() throws Exception
  {
    main(() -> {
      expect("roundtrip-inherited", trace(key(), new float[] {0, -100}, new float[] {0, 0}), letter('H'));
      KeyboardData.Key edited = key();
      edited.keys[7] = KeyValue.layered(letter('h'), literal("上"));
      expect("roundtrip-explicit", trace(edited, new float[] {0, -100}, new float[] {0, 0}), literal("上"));
      edited.keys[2] = letter('1');
      expect("roundtrip-fn-fallback", trace(edited, polar(45), new float[] {0, 0}), KeyValue.getKeyByName("f1"));
    });
  }

  @Test public void clockwiseUsesTheCenterAndAnticlockwiseHasItsOwnAction() throws Exception
  {
    main(() -> {
      KeyboardData.Key edited = key(); edited.keys[0] = KeyValue.layered(letter('a'), literal("转"));
      expect("clockwise-center", trace(edited, polar(0), polar(90), polar(180), polar(270), new float[] {0, 0}), literal("转"));
      expect("anticlockwise-independent", trace(edited, polar(0), polar(270), polar(180), polar(90), new float[] {0, 0}), literal("逆"));
    });
  }

  @Test public void rotationSensitivityWrapAndReversalAreExplicit() throws Exception
  {
    main(() -> {
      config.circle_sensitivity = 4;
      expect("rotation-high-threshold", trace(key(), polar(0), polar(55)), letter('h'));
      config.circle_sensitivity = 2;
      expect("rotation-low-threshold", trace(key(), polar(0), polar(55)), letter('A'));
      expect("rotation-north-wrap", trace(key(), polar(355), polar(10), polar(40)), letter('A'));
      Gesture gesture = new Gesture(0);
      assertTrue(gesture.changed_direction(4)); assertEquals(Gesture.Name.Circle, gesture.get_gesture());
      assertTrue(gesture.changed_direction(2)); assertEquals(Gesture.Name.None, gesture.get_gesture());
      assertFalse(gesture.is_in_progress());
      append(new JSONObject().put("case", "rotation-reversal-recognizer").put("actual", gesture.get_gesture().name()));
    });
  }

  @Test public void anExistingShiftLatchAppliesOnceWithoutGettingStranded() throws Exception
  {
    main(() -> {
      Trace trace = create(key());
      trace.pointers.set_fake_pointer_state(single(KeyValue.SHIFT), KeyValue.SHIFT, true, false);
      trace.start(); trace.move(0, -100); trace.finish();
      assertTrue(trace.releasedModifiers.get(0).has(KeyValue.Modifier.SHIFT));
      assertFalse(trace.pointers.getModifiers().has(KeyValue.Modifier.SHIFT));
      expect("latched-shift", trace, letter('H'));
    });
  }

  @Test public void cancelledTouchesProduceNoReleaseOrPendingPointer() throws Exception
  {
    main(() -> {
      Trace trace = create(key()); trace.start(); trace.move(0, -100);
      trace.pointers.onTouchCancel(); trace.finish();
      assertFalse(trace.pointers.isKeyDown(trace.key));
      expect("touch-cancel", trace);
      // A cancelled contact must not poison the next ordinary tap.
      trace.start(); trace.finish(); expect("tap-after-cancel", trace, letter('a'));
    });
  }

  @Test public void slidersRejectOffAxisCaptureAndKeepRepeatAndModifierSemantics() throws Exception
  {
    main(() -> {
      KeyValue[] values = new KeyValue[9]; values[0] = letter('a'); values[6] = KeyValue.getKeyByName("cursor_right");
      KeyboardData.Key sliderKey = new KeyboardData.Key(values, literal("逆"), 0, 1, 0, null, KeyboardData.Key.Role.Normal);
      Trace offAxis = trace(sliderKey, polar(30));
      assertFalse(offAxis.everSliding); expect("slider-off-axis", offAxis, letter('a'));
      Trace circle = trace(sliderKey, polar(0), polar(90), polar(180), new float[] {0, 0});
      assertFalse(circle.everSliding); expect("circle-past-slider", circle, letter('A'));
      Trace normal = slide(sliderKey, false), control = slide(sliderKey, true);
      int normalUnits = units(normal), controlUnits = units(control);
      assertTrue(normalUnits > controlUnits); assertTrue(controlUnits > 0);
      expect("slider-repeat", normal); expect("slider-control-repeat", control);
    });
  }

  private Trace slide(KeyboardData.Key key, boolean control) throws Exception
  {
    Trace trace = create(key);
    if (control) trace.pointers.set_fake_pointer_state(single(KeyValue.getKeyByName("ctrl")), KeyValue.getKeyByName("ctrl"), true, false);
    trace.start(); trace.move(100, 0); assertTrue(trace.pointers.isSliding());
    // While sliding, an unrelated contact must not generate a typed key.
    trace.pointers.onTouchDown(600, 600, 2, key()); trace.pointers.onTouchUp(2);
    trace.move(450, 0); trace.finish();
    assertFalse(trace.pointers.isSliding());
    for (Pointers.Modifiers mods : trace.heldModifiers) assertEquals(control, mods.has(KeyValue.Modifier.CTRL));
    return trace;
  }

  private int units(Trace trace)
  {
    assertFalse(trace.holds.isEmpty()); int total = 0;
    for (KeyValue action : trace.holds)
    {
      assertEquals(KeyValue.Kind.Slider, action.getKind());
      assertEquals(KeyValue.Slider.Cursor_right, action.getSlider());
      total += action.getSliderRepeat();
    }
    return total;
  }

  private Trace trace(KeyboardData.Key key, float[]... points) throws Exception
  { Trace trace = create(key); trace.start(); for (float[] point : points) trace.move(point[0], point[1]); trace.finish(); return trace; }
  private Trace create(KeyboardData.Key key)
  { Trace trace = new Trace(key); traces.add(trace); return trace; }
  private static KeyValue letter(char c) { return KeyValue.makeCharKey(c); }
  private static KeyValue literal(String text) { return KeyValue.getKeyByName(":literal:'" + text + "'"); }
  private static KeyboardData.Key key()
  {
    KeyValue[] values = new KeyValue[9]; for (int i = 0; i < 9; i++) values[i] = letter((char)('a' + i));
    return new KeyboardData.Key(values, literal("逆"), 0, 1, 0, null, KeyboardData.Key.Role.Normal);
  }
  private static KeyboardData.Key single(KeyValue value)
  { KeyboardData.Key key = key(); Arrays.fill(key.keys, null); key.keys[0] = value; return key; }
  private static float[] polar(float degrees)
  { double angle = Math.toRadians(degrees); return new float[] {(float)(100 * Math.sin(angle)), (float)(-100 * Math.cos(angle))}; }

  private void expect(String name, Trace trace, KeyValue... expected) throws Exception
  {
    JSONObject row = new JSONObject().put("case", name).put("points", trace.points)
      .put("expected", describe(Arrays.asList(expected))).put("actual", describe(trace.ups))
      .put("holds", describe(trace.holds)).put("everSliding", trace.everSliding);
    append(row); assertEquals(name, Arrays.asList(expected), trace.ups);
  }
  private static JSONArray describe(List<KeyValue> actions) throws Exception
  {
    JSONArray array = new JSONArray();
    for (KeyValue value : actions)
    {
      JSONObject action = new JSONObject().put("kind", value == null ? "empty" : value.getKind().name());
      if (value != null)
      {
        action.put("label", value.getString());
        if (value.getKind() == KeyValue.Kind.Keyevent) action.put("keyCode", value.getKeyevent());
        if (value.getKind() == KeyValue.Kind.Slider) action.put("slider", value.getSlider().name()).put("repeat", value.getSliderRepeat());
      }
      array.put(action);
    }
    return array;
  }
  private static void append(JSONObject row) throws Exception
  {
    row.put("api", android.os.Build.VERSION.SDK_INT).put("scope", "on-device pointer component; not editor events");
    try (FileOutputStream out = new FileOutputStream(report, true))
    { out.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8)); }
  }
  private static void main(Step step) throws Exception
  {
    Throwable[] error = {null};
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { step.run(); } catch (Throwable caught) { error[0] = caught; } });
    if (error[0] != null) throw new AssertionError("Pointer trace failed", error[0]);
  }

  private final class Trace implements Pointers.IPointerEventHandler
  {
    final KeyboardData.Key key;
    final Pointers pointers;
    final List<KeyValue> ups = new ArrayList<>(), holds = new ArrayList<>();
    final List<Pointers.Modifiers> releasedModifiers = new ArrayList<>(), heldModifiers = new ArrayList<>();
    final JSONArray points = new JSONArray();
    boolean everSliding;
    Trace(KeyboardData.Key key) { this.key = key; pointers = new Pointers(this, config); }
    void start() { pointers.onTouchDown(500, 500, 1, key); }
    void move(float dx, float dy) throws Exception
    {
      points.put(new JSONArray().put(dx).put(dy));
      pointers.onTouchMove(500 + dx, 500 + dy, 1); everSliding |= pointers.isSliding();
    }
    void finish() { pointers.onTouchUp(1); }
    @Override public KeyValue modifyKey(KeyValue key, Pointers.Modifiers mods) { return KeyModifier.modify(key, mods); }
    @Override public void onPointerDown(KeyValue key, boolean swipe) {}
    @Override public void onPointerUp(KeyValue key, Pointers.Modifiers mods) { ups.add(key); releasedModifiers.add(mods); }
    @Override public void onPointerHold(KeyValue key, Pointers.Modifiers mods) { holds.add(key); heldModifiers.add(mods); }
    @Override public void onPointerFlagsChanged(boolean vibrate) {}
  }
}
