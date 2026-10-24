package juloo.keyboard2.input;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One native worker across IME service recreation and vocabulary settings. */
public final class WordTableRuntime
{
  public interface Operation { void run(WordTableStore store) throws Exception; }
  public static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, "MIANCAK-Rime"));
  private static final Handler UI = new Handler(Looper.getMainLooper());
  private static WeakReference<InputController> controller = new WeakReference<>(null);
  private static final List<Runnable> listeners = new ArrayList<>();
  public static WordTableStore.View view;
  public static boolean busy;
  private static boolean updating;
  public static String error = "";
  public static void register(InputController value)
  { controller = new WeakReference<>(value); if (updating) value.holdForExternalUpdate(); }
  public static void unregister(InputController value) { if (controller.get() == value) controller.clear(); }
  public static void observe(Runnable listener) { listeners.add(listener); listener.run(); }
  public static void forget(Runnable listener) { listeners.remove(listener); }
  private static void publish() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }

  public static CompositionEngine createEngine(Context context) throws Exception
  {
    WordTableStore store = new WordTableStore(context);
    for (int attempt = 0; ; attempt++)
    {
      try { return new RimeEngine(store.currentPaths()); }
      catch (RuntimeException unavailable) { if (attempt == 2) throw unavailable; store.recover(); }
    }
  }
  public static void refresh(Context context) { if (!busy) run(context, false, store -> {}); }
  public static void run(Context context, boolean changesEngine, Operation operation)
  {
    if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Settings API belongs to UI");
    if (busy) return;
    Context app = context.getApplicationContext(); busy = true; updating = changesEngine; error = ""; publish();
    InputController current = controller.get();
    if (changesEngine && current != null)
      current.reconfigure(() -> operation.run(new WordTableStore(app)), failure -> finish(app, failure));
    else WORKER.execute(() -> {
      String failure = "";
      try { operation.run(new WordTableStore(app)); }
      catch (Exception | LinkageError invalid) { failure = detail(invalid); }
      final String result = failure;
      UI.post(() -> finish(app, result));
    });
  }
  private static void finish(Context context, String failure)
  {
    WORKER.execute(() -> {
      WordTableStore.View next = view; String issue = failure;
      try { next = new WordTableStore(context).view(); }
      catch (Exception invalid) { if (issue.isEmpty()) issue = detail(invalid); }
      final WordTableStore.View result = next; final String message = issue;
      UI.post(() -> {
        view = result; error = message; busy = false; updating = false;
        InputController current = controller.get(); if (current != null) current.releaseExternalUpdate();
        publish();
      });
    });
  }
  private static String detail(Throwable failure)
  { String text = failure.getMessage(); return text == null ? failure.getClass().getSimpleName() : text.substring(0, Math.min(text.length(), 1024)); }
  private WordTableRuntime() {}
}
