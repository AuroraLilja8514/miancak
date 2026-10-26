package juloo.keyboard2.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import juloo.keyboard2.input.EditorTransaction.Edit;
import juloo.keyboard2.input.InputRouter.Mode;

/**
 * UI-owned public API, one serialized worker, ordered FIFO UI delivery.
 * A request stays pending until its UI result is applied: a completed engine
 * call alone must never let new direct text overtake an undelivered commit.
 */
public final class InputController implements AutoCloseable
{
  public interface EngineFactory { CompositionEngine create() throws Exception; }
  public interface Editor { boolean apply(EditorTransaction transaction); }
  public interface Listener { void changed(InputSnapshot snapshot); }
  public interface Maintenance { void run() throws Exception; }
  public interface MaintenanceListener { void completed(String error); }
  private interface Work { void run(List<Edit> edits) throws Exception; }

  private static final class Handle
  {
    final long generation;
    final Editor editor;
    final boolean allowed;
    final boolean learning;
    Handle(long generation, Editor editor, boolean allowed, boolean learning)
    {
      this.generation = generation;
      this.editor = editor;
      this.allowed = allowed;
      this.learning = learning;
    }
  }

  private final Executor worker;
  private final Executor ui;
  private final EngineFactory factory;
  private final Listener listener;
  private final Thread uiThread = Thread.currentThread();
  private final AtomicLong generation = new AtomicLong();
  private final AtomicLong revisions = new AtomicLong();
  private final AtomicInteger pending = new AtomicInteger();
  private volatile Handle active;
  private volatile boolean closed;
  private volatile boolean maintenance;
  private boolean externalHold;
  private volatile InputSnapshot snapshot = new InputSnapshot(0, 0, Mode.ENGLISH, false, EngineResult.EMPTY, "");

  // Worker-confined state. Never derive this from the possibly older UI snapshot.
  private CompositionEngine engine;
  private Boolean engineLearning;
  private EngineResult state = EngineResult.EMPTY;
  private Mode mode = Mode.ENGLISH;
  private long workerRevision;

  public InputController(Executor worker, Executor ui, EngineFactory factory, Listener listener)
  {
    this.worker = Objects.requireNonNull(worker);
    this.ui = Objects.requireNonNull(ui);
    this.factory = Objects.requireNonNull(factory);
    this.listener = Objects.requireNonNull(listener);
  }

  public InputSnapshot snapshot() { return snapshot; }
  public boolean hasPendingRequests() { return maintenance || pending.get() != 0; }

  /** A newly created IME waits for an already-running vocabulary transaction. */
  public void holdForExternalUpdate()
  { checkUi(); if (!closed) { externalHold = true; maintenance = true; } }
  public void releaseExternalUpdate()
  {
    checkUi(); if (!externalHold) return; externalHold = false; maintenance = false;
    Handle now = active;
    if (current(now)) startEditor(now.editor, snapshot.mode, now.allowed, now.learning);
  }

  public void startEditor(Editor editor, Mode initialMode, boolean engineAllowed, boolean learning)
  {
    checkUi();
    if (closed) return;
    Handle handle = new Handle(generation.incrementAndGet(), Objects.requireNonNull(editor), engineAllowed, learning);
    active = handle;
    Mode initial = engineAllowed ? initialMode : Mode.ENGLISH;
    snapshot = new InputSnapshot(handle.generation, revisions.incrementAndGet(), initial,
        engineAllowed, EngineResult.EMPTY, "", initial == Mode.CHINESE && !maintenance);
    listener.changed(snapshot);
    if (maintenance) return;
    queue(handle, edits -> {
      mode = initial;
      state = EngineResult.EMPTY;
      if (engine != null) engine.clear();
      try
      {
        if (mode == Mode.CHINESE) getEngine(handle);
        else if (engine != null) { engine.setLearning(false); engineLearning = false; }
      }
      catch (Exception | LinkageError ex)
      {
        mode = Mode.ENGLISH;
        throw ex;
      }
    }, null);
  }

  public void submit(SemanticAction action)
  {
    checkUi();
    Handle handle = active;
    if (closed || maintenance || handle == null) return;
    Objects.requireNonNull(action);
    InputSnapshot current = snapshot;
    // This fast path has neither engine work nor an outstanding UI transaction.
    if (!hasPendingRequests() && !current.engine.composing
        && !action.changesLanguage()
        && (action.type != SemanticAction.Type.ENGINE_TEXT || current.mode == Mode.ENGLISH))
    {
      List<Edit> edits = directEdits(InputRouter.route(current.mode, false, action));
      deliver(handle, new InputSnapshot(handle.generation, revisions.incrementAndGet(), current.mode,
            handle.allowed, current.engine, current.error), edits, null);
      return;
    }
    queue(handle, edits -> executeAction(handle, action, edits), null);
  }

  /** Explicit profile selection is idempotent; callback runs after successful
   * confirmation and editor delivery, and is rejected with an old editor. */
  public boolean setLanguage(Mode target, Runnable applied)
  {
    checkUi();
    Handle handle = active;
    if (maintenance || !current(handle) || (target == Mode.CHINESE && !handle.allowed)) return false;
    if (!hasPendingRequests() && snapshot.mode == target)
    {
      if (applied != null) applied.run();
      return true;
    }
    SemanticAction action = target == Mode.CHINESE ? SemanticAction.SET_CHINESE : SemanticAction.SET_ENGLISH;
    queue(handle, edits -> executeAction(handle, action, edits), applied);
    return true;
  }

  private void executeAction(Handle handle, SemanticAction action, List<Edit> edits) throws Exception
  {
      InputRouter.Plan plan = InputRouter.route(mode, state.composing, action);
      if (plan.resultingMode == Mode.CHINESE && mode != Mode.CHINESE)
      {
        if (!handle.allowed) return;
        getEngine(handle); // Failed initialization leaves the old mode usable.
      }
      for (InputRouter.Operation op : plan.operations)
        switch (op.type)
        {
          case COMMIT_TEXT: if (!op.text.isEmpty()) edits.add(Edit.text(op.text)); break;
          case EDITOR_BACKSPACE: edits.add(Edit.backspace()); break;
          case ENGINE_TEXT: accept(getEngine(handle).processText(op.text), edits, false); break;
          case ENGINE_SPACE: accept(getEngine(handle).space(), edits, false); break;
          case ENGINE_BACKSPACE: accept(getEngine(handle).backspace(), edits, false); break;
          case CONFIRM_COMPOSITION: accept(getEngine(handle).confirm(), edits, true); break;
          default: throw new AssertionError(op.type);
        }
      mode = plan.resultingMode;
  }

  public void select(InputSnapshot displayed, int index)
  {
    checkUi();
    Handle handle = active;
    if (maintenance || !current(handle) || displayed.generation != handle.generation) return;
    queue(handle, edits -> {
      if (displayed.revision != workerRevision || !state.composing
          || index < 0 || index >= state.candidates.size()) return;
      accept(getEngine(handle).select(index), edits, false);
    }, null);
  }

  public void page(InputSnapshot displayed, boolean backward)
  {
    checkUi();
    Handle handle = active;
    if (maintenance || !current(handle) || displayed.generation != handle.generation) return;
    queue(handle, edits -> {
      if (displayed.revision != workerRevision || !state.composing) return;
      EngineResult result = getEngine(handle).page(backward);
      state = result;
      if (!result.committed.isEmpty()) edits.add(Edit.text(result.committed));
    }, null);
  }

  public void cancelComposition()
  {
    checkUi();
    Handle handle = active;
    if (!maintenance && current(handle))
      queue(handle, edits -> { if (engine != null) state = engine.clear(); }, null);
  }

  /** Escape cancels a composition; otherwise retain the editor's Escape key. */
  public void cancelOrRun(Runnable action)
  {
    checkUi();
    Handle handle = active;
    if (maintenance || !current(handle)) return;
    if (!hasPendingRequests() && !snapshot.engine.composing) { action.run(); return; }
    final boolean[] idle = { false };
    queue(handle, edits -> {
      if (state.composing) state = getEngine(handle).clear();
      else idle[0] = true;
    }, () -> { if (idle[0]) action.run(); });
  }

  /** For Enter, shortcuts and inherited editor actions; callback runs on the UI. */
  public void afterConfirmation(Runnable action)
  {
    checkUi();
    Handle handle = active;
    if (maintenance || !current(handle)) return;
    if (!hasPendingRequests() && !snapshot.engine.composing)
    {
      action.run();
      return;
    }
    queue(handle, edits -> {
      if (state.composing) accept(getEngine(handle).confirm(), edits, true);
    }, action);
  }

  /** Cursor/selection movement cancels pending composition in this same editor. */
  public void selectionChanged()
  {
    checkUi();
    Handle handle = active;
    if (current(handle)) startEditor(handle.editor, snapshot.mode, handle.allowed, handle.learning);
  }

  /** Confirm in the old editor, release the engine, update on the same worker,
   * then attach a fresh editor generation. Input is paused for the transaction.
   * A changed editor never receives an old commit; completion still runs. */
  public void reconfigure(Maintenance update, MaintenanceListener completion)
  {
    checkUi();
    if (closed || maintenance) { completion.completed("Input update already running / 输入更新正在进行"); return; }
    maintenance = true;
    Handle before = active;
    worker.execute(() -> {
      List<Edit> edits = new ArrayList<>(); String error = "";
      try { if (current(before) && state.composing) accept(getEngine(before).confirm(), edits, true); }
      catch (Exception | LinkageError failed) { error = message(failed); }
      final String confirmationError = error;
      workerRevision = revisions.incrementAndGet();
      InputSnapshot result = new InputSnapshot(before == null ? generation.get() : before.generation,
          workerRevision, mode, before != null && before.allowed, state, error);
      ui.execute(() -> {
        boolean wasCurrent = current(before);
        if (wasCurrent) deliver(before, result, edits, null);
        if (!confirmationError.isEmpty() || (wasCurrent && !current(before)))
        {
          maintenance = false;
          Handle now = active;
          if (now != before && current(now)) startEditor(now.editor, snapshot.mode, now.allowed, now.learning);
          completion.completed(confirmationError.isEmpty() ? "Editor rejected composition / 编辑器未接受组词" : confirmationError);
          return;
        }
        worker.execute(() -> {
          String failure = "";
          try
          {
            CompositionEngine previous = engine;
            engine = null; engineLearning = null; state = EngineResult.EMPTY;
            if (previous != null) previous.close();
            update.run();
          }
          catch (Exception | LinkageError failed) { failure = message(failed); }
          final String updateError = failure;
          ui.execute(() -> {
            maintenance = false;
            Handle now = active;
            if (current(now)) startEditor(now.editor, snapshot.mode, now.allowed, now.learning);
            completion.completed(updateError);
          });
        });
      });
    });
  }

  private static String message(Throwable failure)
  { String message = failure.getMessage(); return message == null ? failure.getClass().getSimpleName() : message; }

  public void finishEditor()
  {
    checkUi();
    if (closed) return;
    generation.incrementAndGet();
    active = null;
    snapshot = new InputSnapshot(generation.get(), revisions.incrementAndGet(), snapshot.mode,
        false, EngineResult.EMPTY, "");
    listener.changed(snapshot);
    worker.execute(() -> {
      clearEngine();
      state = EngineResult.EMPTY;
    });
  }

  @Override public void close()
  {
    checkUi();
    if (closed) return;
    closed = true;
    if (externalHold) { externalHold = false; maintenance = false; }
    active = null;
    generation.incrementAndGet();
    worker.execute(() -> {
      if (engine != null) engine.close();
      engine = null;
      engineLearning = null;
      state = EngineResult.EMPTY;
    });
  }

  private CompositionEngine getEngine(Handle handle) throws Exception
  {
    if (!handle.allowed) throw new IllegalStateException("Engine disabled for this editor");
    if (engine == null)
      engine = factory.create();
    if (engineLearning == null || engineLearning != handle.learning)
    {
      if (state.composing) throw new IllegalStateException("Learning policy requires an idle engine");
      engine.setLearning(handle.learning);
      engineLearning = handle.learning;
    }
    return engine;
  }

  private void accept(EngineResult result, List<Edit> edits, boolean confirming)
  {
    state = Objects.requireNonNull(result);
    if (!result.committed.isEmpty()) edits.add(Edit.text(result.committed));
    if (!result.handled || (confirming && result.composing))
      throw new IllegalStateException("Engine could not complete the requested operation");
  }

  private void queue(Handle handle, Work work, Runnable after)
  {
    pending.incrementAndGet();
    worker.execute(() -> {
      if (!current(handle))
      {
        ui.execute(() -> pending.decrementAndGet());
        return;
      }
      List<Edit> edits = new ArrayList<>();
      String error = "";
      try { work.run(edits); }
      catch (Exception | LinkageError ex) { error = "Chinese input is unavailable. Tap the language button to retry."; }
      workerRevision = revisions.incrementAndGet();
      InputSnapshot result = new InputSnapshot(handle.generation, workerRevision, mode,
          handle.allowed, state, error);
      ui.execute(() -> {
        try { deliver(handle, result, edits, after); }
        finally { pending.decrementAndGet(); }
      });
    });
  }

  private void deliver(Handle handle, InputSnapshot result, List<Edit> edits, Runnable after)
  {
    if (!current(handle)) return;
    if (!handle.editor.apply(new EditorTransaction(edits)))
    {
      // Invalidate queued native results before another transaction can land.
      generation.incrementAndGet();
      active = null;
      snapshot = new InputSnapshot(generation.get(), revisions.incrementAndGet(), result.mode,
          false, EngineResult.EMPTY, "The editor rejected input. Refocus the field to continue.");
      listener.changed(snapshot);
      worker.execute(() -> { clearEngine(); state = EngineResult.EMPTY; });
      return;
    }
    snapshot = result;
    listener.changed(result);
    if (after != null && result.error.isEmpty() && current(handle)) after.run();
  }

  private List<Edit> directEdits(InputRouter.Plan plan)
  {
    List<Edit> edits = new ArrayList<>();
    for (InputRouter.Operation op : plan.operations)
      if (op.type == InputRouter.OperationType.COMMIT_TEXT)
      {
        if (!op.text.isEmpty()) edits.add(Edit.text(op.text));
      }
      else if (op.type == InputRouter.OperationType.EDITOR_BACKSPACE)
        edits.add(Edit.backspace());
      else throw new AssertionError("Non-direct operation on idle path");
    return edits;
  }

  private boolean current(Handle handle)
  {
    return !closed && handle != null && active == handle && generation.get() == handle.generation;
  }

  private void clearEngine()
  {
    if (engine == null) return;
    try { engine.clear(); }
    catch (RuntimeException | LinkageError ex)
    {
      try { engine.close(); } catch (RuntimeException | LinkageError ignored) {}
      engine = null;
      engineLearning = null;
    }
  }

  private void checkUi()
  {
    if (Thread.currentThread() != uiThread)
      throw new IllegalStateException("InputController API belongs to its UI thread");
  }
}
