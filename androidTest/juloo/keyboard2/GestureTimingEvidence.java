package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Measurement only: observe the unchanged system-injected synthetic gestures.
 * No repeat settings, gesture steps, pointer flags or receiver expectations change.
 * Callbacks buffer bounded metadata only; observation itself can affect scheduling.
 */
final class GestureTimingEvidence implements AutoCloseable
{
  interface Injection { boolean run(); }
  private static final int MAX_RECORDS = 512, MAX_POINTERS = 4;
  private final Instrumentation instrumentation;
  private final Context context;
  private final String filename;
  private final List<Map<String, Object>> records = new ArrayList<>();
  private final Map<String, Object> configuration = new LinkedHashMap<>();
  private final Map<String, Object> closeState = new LinkedHashMap<>();
  private final Map<String, Integer> seen = new LinkedHashMap<>();
  private volatile String gesture = "not-injecting";
  private volatile boolean injecting;
  private int dropped, recordingFailures, omittedPointers;
  private Config config;
  private Config.IKeyEventHandler originalHandler, observedHandler;
  private Keyboard2View touchView;
  private boolean touchInstalled;
  private String handlerAvailability = "not-installed", touchAvailability = "not-installed";
  private String handlerRestoration = "not-installed", touchRestoration = "not-installed";
  private String installationFailure, cleanupFailure;
  private int rootCount, matchingViews;
  private final long opened = SystemClock.uptimeMillis();
  private long closed;

  static GestureTimingEvidence start(Instrumentation instrumentation, Context context,
      boolean restartOnly)
  {
    GestureTimingEvidence evidence = new GestureTimingEvidence(instrumentation, context,
        restartOnly ? "editor-restart-gesture-timing.json" : "editor-gesture-timing.json");
    try { evidence.main(evidence::install); }
    catch (Throwable failure) { evidence.installationFailure = failure.getClass().getName(); }
    return evidence;
  }

  private GestureTimingEvidence(Instrumentation instrumentation, Context context, String filename)
  { this.instrumentation = instrumentation; this.context = context; this.filename = filename; }

  private void install()
  {
    config = Config.globalConfig();
    if (config == null) handlerAvailability = "unavailable-no-live-config";
    else
    {
      configuration.put("longPressTimeoutMs", config.longPressTimeout);
      configuration.put("longPressIntervalMs", config.longPressInterval);
      configuration.put("keyrepeatEnabled", config.keyrepeat_enabled);
      configuration.put("swipeDistancePx", config.swipe_dist_px);
      configuration.put("circleSensitivity", config.circle_sensitivity);
      configuration.put("screenHeightPx", config.screenHeightPixels);
      configuration.put("keyboardRowHeightPx", config.keyboard_rows_height_pixels);
      configuration.put("horizontalMarginPx", config.horizontal_margin);
      originalHandler = config.handler;
      if (originalHandler == null) handlerAvailability = "unavailable-no-live-handler";
      else
      {
        observedHandler = new Config.IKeyEventHandler() {
          public void key_down(KeyValue value, boolean isSwipe)
          {
            handlerRecord("key_down", value, isSwipe);
            originalHandler.key_down(value, isSwipe);
          }
          public void key_up(KeyValue value, Pointers.Modifiers modifiers)
          {
            handlerRecord("key_up", value, false);
            originalHandler.key_up(value, modifiers);
          }
          public void mods_changed(Pointers.Modifiers modifiers)
          {
            handlerRecord("mods_changed", null, false);
            originalHandler.mods_changed(modifiers);
          }
          public void suggestion_entered(String text)
          {
            // Do not collect arbitrary suggestion contents, even in this fixture.
            handlerRecord("suggestion_entered", null, false);
            originalHandler.suggestion_entered(text);
          }
        };
        config.handler = observedHandler;
        handlerAvailability = "installed-delegating-live-handler";
      }
    }
    if (Build.VERSION.SDK_INT < 29)
    { touchAvailability = "unsupported-public-WindowInspector-before-API29"; return; }
    try
    {
      List<View> roots = Api29.roots();
      rootCount = roots.size();
      List<Keyboard2View> matches = new ArrayList<>();
      for (View root : roots) findKeyboard(root, matches);
      matchingViews = matches.size();
      if (matchingViews != 1)
      { touchAvailability = "unavailable-not-one-shown-Page-en-keyboard"; return; }
      touchView = matches.get(0);
      int[] origin = new int[2]; touchView.getLocationOnScreen(origin);
      configuration.put("touchViewScreenX", origin[0]); configuration.put("touchViewScreenY", origin[1]);
      configuration.put("touchViewWidthPx", touchView.getWidth());
      configuration.put("touchViewHeightPx", touchView.getHeight());
      // Keyboard2View installs itself as its listener. Restore that known listener,
      // never dispatch a second copy of an event or replace the actual input path.
      touchView.setOnTouchListener((view, event) -> {
        Map<String, Object> record = touchRecord(event);
        try
        {
          boolean result = touchView.onTouch(view, event);
          touchOutcome(record, result, null);
          return result;
        }
        catch (RuntimeException | Error failure)
        {
          touchOutcome(record, null, failure);
          throw failure;
        }
      });
      touchInstalled = true;
      touchAvailability = "installed-delegating-known-self-listener";
    }
    catch (RuntimeException | LinkageError failure)
    { touchAvailability = "unavailable-" + failure.getClass().getName(); }
  }

  private static void findKeyboard(View view, List<Keyboard2View> matches)
  {
    CharSequence description = view.getContentDescription();
    if (view instanceof Keyboard2View && view.getId() == R.id.keyboard_view && view.isShown()
        && description != null && description.toString().startsWith("Page: en;"))
      matches.add((Keyboard2View)view);
    if (view instanceof ViewGroup)
    {
      ViewGroup group = (ViewGroup)view;
      for (int i = 0; i < group.getChildCount(); i++) findKeyboard(group.getChildAt(i), matches);
    }
  }

  private static final class Api29
  {
    static List<View> roots()
    { return android.view.inspector.WindowInspector.getGlobalWindowViews(); }
  }

  boolean inject(String name, Point[] points, int steps, Injection injection)
  {
    gesture = name;
    injecting = true;
    long began = SystemClock.uptimeMillis();
    try
    {
      Map<String, Object> start = entry("injection-start");
      List<List<Integer>> path = new ArrayList<>();
      for (Point point : points)
      {
        List<Integer> xy = new ArrayList<>(); xy.add(point.x); xy.add(point.y); path.add(xy);
      }
      start.put("requestedPoints", path); start.put("stepsPerSegment", steps);
      began = SystemClock.uptimeMillis();
      start.put("uptimeMs", began); add(start);
    }
    catch (Throwable ignored) { recordingFailed(); }
    Boolean result = null;
    String failureClass = null;
    try { result = injection.run(); return result; }
    catch (RuntimeException | Error failure)
    { failureClass = failure.getClass().getName(); throw failure; }
    finally
    {
      long ended = SystemClock.uptimeMillis();
      injecting = false;
      try
      {
        Map<String, Object> end = entry("injection-end");
        end.put("uptimeMs", ended); end.put("callDurationMs", ended - began);
        end.put("result", result); end.put("failureClass", failureClass); add(end);
      }
      catch (Throwable ignored) { recordingFailed(); }
    }
  }

  private Map<String, Object> entry(String type)
  {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("type", type); record.put("gesture", gesture);
    record.put("duringInjectionCall", injecting);
    record.put("uptimeMs", SystemClock.uptimeMillis());
    record.put("threadId", android.os.Process.myTid());
    return record;
  }

  private synchronized void add(Map<String, Object> record)
  {
    String type = (String)record.get("type");
    Integer count = seen.get(type); seen.put(type, count == null ? 1 : count + 1);
    if (records.size() < MAX_RECORDS) records.add(record); else dropped++;
  }

  private synchronized void recordingFailed() { recordingFailures++; }

  private Map<String, Object> touchRecord(MotionEvent event)
  {
    try
    {
      Map<String, Object> record = entry("incoming-touch");
      record.put("actionMasked", event.getActionMasked());
      record.put("actionIndex", event.getActionIndex());
      record.put("downTimeMs", event.getDownTime()); record.put("eventTimeMs", event.getEventTime());
      record.put("wrappedHandlerStillCurrent", observedHandler != null && config.handler == observedHandler);
      record.put("globalConfigStillSame", Config.globalConfig() == config);
      record.put("pointerCount", event.getPointerCount()); record.put("historySize", event.getHistorySize());
      record.put("coordinates", "actual-Keyboard2View-local-pixels");
      record.put("historyCoordinatesExpanded", false);
      if (event.getHistorySize() > 0)
      {
        record.put("firstHistoryTimeMs", event.getHistoricalEventTime(0));
        record.put("lastHistoryTimeMs", event.getHistoricalEventTime(event.getHistorySize() - 1));
      }
      List<List<Number>> pointers = new ArrayList<>();
      for (int i = 0; i < Math.min(event.getPointerCount(), MAX_POINTERS); i++)
      {
        List<Number> pointer = new ArrayList<>();
        pointer.add(event.getPointerId(i)); pointer.add(event.getX(i)); pointer.add(event.getY(i));
        pointers.add(pointer);
      }
      record.put("pointersIdXY", pointers);
      omittedPointers += Math.max(0, event.getPointerCount() - MAX_POINTERS);
      add(record); return record;
    }
    catch (Throwable ignored) { recordingFailed(); return null; }
  }

  private void touchOutcome(Map<String, Object> record, Boolean result, Throwable failure)
  {
    try
    {
      if (record == null) return;
      if (failure == null) record.put("delegateResult", result);
      else record.put("delegateFailureClass", failure.getClass().getName());
    }
    catch (Throwable ignored) { recordingFailed(); }
  }

  private void handlerRecord(String method, KeyValue value, boolean isSwipe)
  {
    try
    {
      Map<String, Object> record = entry("handler-" + method);
      record.put("valueKind", value == null ? null : value.getKind().name());
      record.put("valueDisplayText", value == null ? null : bounded(value.getString()));
      if (value != null && (value.getKind() == KeyValue.Kind.Literal || value.getKind() == KeyValue.Kind.Engine))
        record.put("valueSemanticText", bounded(value.getSemanticText()));
      if (method.equals("key_down")) record.put("isSwipe", isSwipe);
      if (method.equals("key_up"))
      {
        String reason = "unattributed-not-proof-of-no-hold";
        for (StackTraceElement frame : Thread.currentThread().getStackTrace())
          if (frame.getClassName().equals(Keyboard2View.class.getName()))
          {
            if (frame.getMethodName().equals("onPointerHold")) { reason = "onPointerHold"; break; }
            if (frame.getMethodName().equals("onPointerUp")) { reason = "onPointerUp"; break; }
          }
        record.put("keyboardCallbackReason", reason);
      }
      add(record);
    }
    catch (Throwable ignored) { recordingFailed(); }
  }

  private static String bounded(String text)
  { return text == null || text.length() <= 128 ? text : text.substring(0, 128); }

  @Override public void close() throws Exception
  {
    Throwable failure = null;
    try
    {
      main(() -> {
        try
        {
          closeState.put("globalConfigStillSame", Config.globalConfig() == config);
          closeState.put("wrappedHandlerStillCurrent", observedHandler != null && config.handler == observedHandler);
          closeState.put("touchViewPresent", touchView != null);
          if (touchView != null)
          {
            closeState.put("touchViewStillShown", touchView.isShown());
            closeState.put("touchViewStillAttached", touchView.isAttachedToWindow());
          }
        }
        catch (Throwable ignored) { recordingFailed(); }
        try
        {
          if (touchInstalled) { touchView.setOnTouchListener(touchView); touchRestoration = "restored-known-self"; }
        }
        finally
        {
          if (observedHandler != null)
          {
            if (config.handler == observedHandler)
            { config.handler = originalHandler; handlerRestoration = "restored-original"; }
            else handlerRestoration = "not-overwritten-live-handler-changed";
          }
        }
      });
    }
    catch (Throwable caught) { failure = caught; cleanupFailure = caught.getClass().getName(); }
    closed = SystemClock.uptimeMillis();
    try { write(); }
    catch (Throwable caught)
    { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
    // A try-with-resources caller keeps its original receiver assertion primary.
    if (failure != null) rethrow(failure);
  }

  private void write() throws Exception
  {
    JSONArray buffered = new JSONArray();
    for (Map<String, Object> record : records) buffered.put(new JSONObject(record));
    JSONObject report = new JSONObject().put("schema", 1).put("api", Build.VERSION.SDK_INT)
      .put("scope", "measurement-only final three synthetic gestures; not a product fix")
      .put("observerMayAffectScheduling", true)
      .put("callDurationIsNotTouchDuration", true)
      .put("unavailableObserverDoesNotEstablishAbsence", true)
      .put("openedUptimeMs", opened).put("closedUptimeMs", closed)
      .put("configuration", new JSONObject(configuration))
      .put("observerStateBeforeRestore", new JSONObject(closeState))
      .put("handlerAvailability", handlerAvailability).put("touchAvailability", touchAvailability)
      .put("windowRootCount", rootCount).put("matchingShownKeyboards", matchingViews)
      .put("handlerRestoration", handlerRestoration).put("touchRestoration", touchRestoration)
      .put("installationFailureClass", installationFailure == null ? JSONObject.NULL : installationFailure)
      .put("cleanupFailureClass", cleanupFailure == null ? JSONObject.NULL : cleanupFailure)
      .put("maxRecords", MAX_RECORDS).put("maxPointersPerEvent", MAX_POINTERS)
      .put("seen", new JSONObject(seen)).put("storedRecords", records.size())
      .put("droppedRecords", dropped).put("recordingFailures", recordingFailures)
      .put("omittedPointerSamples", omittedPointers).put("records", buffered);
    try (FileOutputStream out = new FileOutputStream(new File(TestEvidence.directory(context), filename)))
    { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
  }

  private void main(Runnable action) throws Exception
  {
    Throwable[] failure = {null};
    instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable caught) { failure[0] = caught; } });
    if (failure[0] != null) rethrow(failure[0]);
  }

  private static void rethrow(Throwable failure) throws Exception
  {
    if (failure instanceof Exception) throw (Exception)failure;
    if (failure instanceof Error) throw (Error)failure;
    throw new AssertionError(failure);
  }
}
