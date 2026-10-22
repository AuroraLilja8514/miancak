package juloo.keyboard2.input;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;
import static juloo.keyboard2.input.SemanticAction.*;
import juloo.keyboard2.input.InputRouter.Mode;

public class InputControllerTest
{
  @Test public void englishIdlePathIsImmediateAndNeverInitializesRime()
  {
    Harness h = new Harness(Mode.ENGLISH);
    h.controller.submit(engineText("Hello😀"));
    assertEquals("Hello😀", h.editor.text());
    assertEquals(0, h.creations);
    assertFalse(h.controller.hasPendingRequests());
    h.controller.submit(BACKSPACE);
    assertEquals("Hello", h.editor.text());
  }

  @Test public void rapidMixedInputUsesWorkerTruthAndOrderedEditorBatches()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao"));
    h.controller.submit(literalText("。"));
    h.controller.submit(TOGGLE_LANGUAGE);
    h.controller.submit(engineText("q"));
    h.flush();
    assertEquals("你好。q", h.editor.text());
    assertTrue(h.editor.batches.contains(Arrays.asList("你好", "。")));
    assertEquals(Mode.ENGLISH, h.controller.snapshot().mode);
    assertEquals(1, h.creations);
  }

  @Test public void directTextCannotOvertakeAnUndeliveredNativeCommit()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao"));
    h.controller.submit(literalText("。"));
    h.worker.all(); // Native work finished, but editor transactions did not.
    assertEquals("", h.editor.text());
    assertTrue(h.controller.hasPendingRequests());
    h.controller.submit(literalText("!"));
    assertEquals("", h.editor.text());
    h.flush();
    assertEquals("你好。!", h.editor.text());
  }

  @Test public void fieldChangeDropsQueuedInputBeforeItRuns()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao"));
    Editor next = new Editor();
    h.controller.startEditor(next, Mode.ENGLISH, true, true);
    h.controller.submit(engineText("new"));
    h.flush();
    assertEquals("", h.editor.text());
    assertEquals("new", next.text());
    assertFalse(h.engine.calls.contains("text:nihao"));
  }

  @Test public void fieldChangeDropsAlreadyComputedOldEditorTransactions()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao"));
    h.controller.submit(literalText("。"));
    h.worker.all();
    Editor next = new Editor();
    h.controller.startEditor(next, Mode.ENGLISH, true, true);
    h.flush();
    assertEquals("", h.editor.text());
    assertEquals("", next.text());
    assertFalse(h.controller.snapshot().engine.composing);
  }

  @Test public void cursorMovementCancelsPendingComposition()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao"));
    h.worker.all();
    h.controller.selectionChanged();
    h.flush();
    assertEquals("", h.editor.text());
    assertFalse(h.controller.snapshot().engine.composing);
  }

  @Test public void staleCandidateTapCannotSelectANewListAtTheOldIndex()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("ni")); h.flush();
    InputSnapshot old = h.controller.snapshot();
    h.controller.submit(engineText("hao"));
    h.controller.select(old, 1);
    h.flush();
    assertEquals("", h.editor.text());
    assertFalse(h.engine.calls.contains("select:1"));
    h.controller.select(h.controller.snapshot(), 0); h.flush();
    assertEquals("你好", h.editor.text());
  }

  @Test public void candidateTapFromAnOldEditorIsIgnored()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("ni")); h.flush();
    InputSnapshot old = h.controller.snapshot();
    h.controller.startEditor(h.editor, Mode.CHINESE, true, true); h.flush();
    h.controller.submit(engineText("di")); h.flush();
    h.controller.select(old, 0); h.flush();
    assertEquals("", h.editor.text());
    assertTrue(h.controller.snapshot().engine.composing);
  }

  @Test public void failedConfirmationDoesNotAppendLiteralOrSwitchLanguage()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao")); h.flush();
    h.engine.failConfirm = true;
    h.controller.submit(literalText("。"));
    h.controller.submit(TOGGLE_LANGUAGE); h.flush();
    assertEquals("", h.editor.text());
    assertEquals(Mode.CHINESE, h.controller.snapshot().mode);
    assertTrue(h.controller.snapshot().engine.composing);
    assertFalse(h.controller.snapshot().error.isEmpty());
  }

  @Test public void enterAndFollowingTextKeepTheirPlaceAfterConfirmation()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao"));
    h.controller.afterConfirmation(() -> h.editor.output.append('\n'));
    h.controller.submit(literalText("q"));
    h.worker.all(); h.ui.all();
    assertEquals("你好\nq", h.editor.text());
  }

  @Test public void protectedFieldsCannotStartTheEngineEvenAfterToggle()
  {
    Harness h = new Harness(Mode.ENGLISH);
    h.controller.startEditor(h.editor, Mode.CHINESE, false, false); h.flush();
    h.controller.submit(TOGGLE_LANGUAGE);
    h.controller.submit(engineText("secret3")); h.flush();
    assertEquals("secret3", h.editor.text());
    assertEquals(0, h.creations);
    assertEquals(Mode.ENGLISH, h.controller.snapshot().mode);
  }

  @Test public void learningPolicyChangesAtEditorBoundariesNotPerKey()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao")); h.controller.submit(literalText("。")); h.flush();
    h.controller.submit(engineText("di")); h.controller.submit(SPACE); h.flush();
    assertEquals(Arrays.asList(true), h.engine.learning);
    h.controller.startEditor(h.editor, Mode.CHINESE, true, false); h.flush();
    h.controller.submit(engineText("ni")); h.flush();
    assertEquals(Arrays.asList(true, false), h.engine.learning);
    assertEquals(1, h.creations);
  }

  @Test public void initializationFailureKeepsEnglishUsableAndToggleCanRetry()
  {
    Harness h = new Harness(Mode.ENGLISH);
    h.failInitialization = true;
    h.controller.submit(TOGGLE_LANGUAGE); h.flush();
    assertEquals(Mode.ENGLISH, h.controller.snapshot().mode);
    assertFalse(h.controller.snapshot().error.isEmpty());
    h.controller.submit(engineText("usable"));
    assertEquals("usable", h.editor.text());
    h.failInitialization = false;
    h.controller.submit(TOGGLE_LANGUAGE); h.flush();
    h.controller.submit(engineText("nihao")); h.controller.submit(SPACE); h.flush();
    assertEquals("usable你好", h.editor.text());
  }

  @Test public void finishAndShutdownNeverDeliverOldResults()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao"));
    h.controller.submit(SPACE); h.worker.all();
    h.controller.finishEditor();
    h.controller.close(); h.flush();
    assertEquals("", h.editor.text());
    assertEquals(1, h.engine.closes);
    assertFalse(h.controller.hasPendingRequests());
  }

  @Test public void rejectedEditorTransactionInvalidatesFurtherQueuedResults()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao")); h.controller.submit(SPACE);
    h.controller.submit(literalText("q")); h.worker.all();
    h.editor.reject = true; h.ui.all(); h.flush();
    assertEquals("", h.editor.text());
    assertFalse(h.controller.snapshot().error.isEmpty());
    assertFalse(h.controller.snapshot().engineAllowed);
  }

  @Test public void pagingAtTheBoundaryIsHarmlessAndCancelKeepsMode()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("ni")); h.flush();
    h.controller.page(h.controller.snapshot(), true); h.flush();
    assertTrue(h.controller.snapshot().error.isEmpty());
    h.controller.cancelComposition(); h.flush();
    assertFalse(h.controller.snapshot().engine.composing);
    assertEquals(Mode.CHINESE, h.controller.snapshot().mode);
  }

  @Test public void escapeCancelsQueuedCompositionAndPassesThroughWhenIdle()
  {
    Harness h = new Harness(Mode.CHINESE);
    int[] escaped = { 0 };
    h.controller.submit(engineText("nihao"));
    h.controller.cancelOrRun(() -> escaped[0]++); h.flush();
    assertEquals(0, escaped[0]);
    assertFalse(h.controller.snapshot().engine.composing);
    assertEquals("", h.editor.text());
    h.controller.cancelOrRun(() -> escaped[0]++);
    assertEquals(1, escaped[0]);
  }

  private static final class Queue implements Executor
  {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    @Override public void execute(Runnable task) { tasks.add(task); }
    void all() { while (!tasks.isEmpty()) tasks.remove().run(); }
  }

  private static final class Editor implements InputController.Editor
  {
    final StringBuilder output = new StringBuilder();
    final List<List<String>> batches = new ArrayList<>();
    boolean reject;
    String text() { return output.toString(); }
    @Override public boolean apply(EditorTransaction transaction)
    {
      if (reject) return false;
      List<String> batch = new ArrayList<>();
      for (EditorTransaction.Edit edit : transaction.edits)
        if (edit.backspace)
        {
          if (output.length() > 0)
            output.delete(output.offsetByCodePoints(output.length(), -1), output.length());
          batch.add("<backspace>");
        }
        else { output.append(edit.text); batch.add(edit.text); }
      if (!batch.isEmpty()) batches.add(batch);
      return true;
    }
  }

  private static final class FakeEngine implements CompositionEngine
  {
    String raw = "";
    final List<String> calls = new ArrayList<>();
    final List<Boolean> learning = new ArrayList<>();
    boolean failConfirm;
    int closes;
    String selected() { return raw.equals("nihao") ? "你好" : raw.equals("di") ? "第" : raw.equals("ni") ? "你" : raw; }
    EngineResult result(boolean handled, String commit)
    {
      return new EngineResult(handled, commit, raw,
          raw.isEmpty() ? new String[0] : new String[] { selected(), "候选" }, 0, !raw.isEmpty(), true);
    }
    @Override public EngineResult processText(String text) { calls.add("text:" + text); raw += text; return result(true, ""); }
    @Override public EngineResult confirm()
    {
      if (failConfirm) return result(false, "");
      String text = selected(); raw = ""; return result(true, text);
    }
    @Override public EngineResult backspace() { raw = raw.substring(0, raw.length() - 1); return result(true, ""); }
    @Override public EngineResult space() { return confirm(); }
    @Override public EngineResult clear() { raw = ""; return result(true, ""); }
    @Override public EngineResult select(int index)
    {
      calls.add("select:" + index);
      if (index == 0) return confirm();
      raw = ""; return result(true, "候选");
    }
    @Override public EngineResult page(boolean backward) { return result(false, ""); }
    @Override public void setLearning(boolean enabled) { learning.add(enabled); }
    @Override public void close() { closes++; }
  }

  private static final class Harness
  {
    final Queue worker = new Queue(), ui = new Queue();
    final Editor editor = new Editor();
    final FakeEngine engine = new FakeEngine();
    final InputController controller;
    int creations;
    boolean failInitialization;
    Harness(Mode mode)
    {
      controller = new InputController(worker, ui, () -> {
        creations++;
        if (failInitialization) throw new IllegalStateException("test initialization failure");
        return engine;
      }, snapshot -> {});
      controller.startEditor(editor, mode, true, true);
      flush();
    }
    void flush()
    {
      while (!worker.tasks.isEmpty() || !ui.tasks.isEmpty()) { worker.all(); ui.all(); }
    }
  }
}
