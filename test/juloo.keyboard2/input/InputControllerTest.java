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
  @Test public void typingDuringFirstInitializationIsQueuedOnceAndDeliveredInOrder()
  {
    Queue worker = new Queue(), ui = new Queue();
    Editor editor = new Editor(); FakeEngine engine = new FakeEngine();
    int[] creations = {0}; List<InputSnapshot> shown = new ArrayList<>();
    InputController controller = new InputController(worker, ui,
        () -> { creations[0]++; return engine; }, shown::add);
    controller.startEditor(editor, Mode.CHINESE, true, true);
    assertTrue(controller.snapshot().initializing);
    for (char c : "nihao".toCharArray()) controller.submit(engineText(String.valueOf(c)));
    controller.submit(literalText("。"));
    assertEquals(0, creations[0]);
    assertEquals("", editor.text());
    assertTrue(controller.snapshot().initializing);
    worker.all();
    assertEquals("", editor.text()); // Native work cannot overtake UI delivery.
    ui.all();
    assertEquals(1, creations[0]);
    assertEquals(Arrays.asList("text:n", "text:i", "text:h", "text:a", "text:o"), engine.calls);
    assertEquals("你好。", editor.text());
    assertFalse(controller.snapshot().initializing);
    assertFalse(controller.hasPendingRequests());
    assertTrue(shown.stream().anyMatch(s -> s.engine.composing && s.engine.preedit.equals("nihao")));
  }

  @Test public void initializationErrorStopsThePreparingIndicatorAndKeepsEnglishUsable()
  {
    Queue worker = new Queue(), ui = new Queue(); Editor editor = new Editor();
    InputController controller = new InputController(worker, ui,
        () -> { throw new IllegalStateException("test initialization failure"); }, snapshot -> {});
    controller.startEditor(editor, Mode.CHINESE, true, true);
    assertTrue(controller.snapshot().initializing);
    worker.all(); ui.all();
    assertFalse(controller.snapshot().initializing);
    assertFalse(controller.snapshot().error.isEmpty());
    assertEquals(Mode.ENGLISH, controller.snapshot().mode);
    controller.submit(engineText("hello"));
    assertEquals("hello", editor.text());
  }

  @Test public void offlinePreparationErrorIsSpecificAndAnExplicitRetryKeepsEnglishUsable() throws Exception
  {
    Queue worker = new Queue(), ui = new Queue(); Editor editor = new Editor(); int[] starts = {0};
    FakeEngine engine = new FakeEngine();
    InputController controller = new InputController(worker, ui, () -> {
      if (++starts[0] == 1) throw new OfflineDataException(new java.io.IOException("private local path must not be displayed"));
      return engine;
    }, snapshot -> {});
    controller.startEditor(editor, Mode.CHINESE, true, true); worker.all(); ui.all();
    assertEquals(OfflineDataException.MESSAGE, controller.snapshot().error);
    assertEquals(Mode.ENGLISH, controller.snapshot().mode); assertFalse(controller.snapshot().initializing);
    controller.submit(engineText("hello")); assertEquals("hello", editor.text());
    controller.submit(TOGGLE_LANGUAGE); worker.all(); ui.all();
    assertEquals(Mode.CHINESE, controller.snapshot().mode); assertEquals("", controller.snapshot().error); assertEquals(2, starts[0]);
  }

  @Test public void retainedDataNoticeSurvivesDirectAndQueuedInputAndEndsWithTheSession()
  {
    Queue worker = new Queue(), ui = new Queue(); Editor editor = new Editor(); FakeEngine engine = new FakeEngine(); engine.retained = true;
    InputController controller = new InputController(worker, ui, () -> engine, snapshot -> {});
    controller.startEditor(editor, Mode.CHINESE, true, true); worker.all(); ui.all();
    assertTrue(controller.snapshot().retainedData);
    controller.submit(literalText("。")); assertTrue(controller.snapshot().retainedData); assertEquals("。", editor.text());
    controller.submit(engineText("nihao")); worker.all(); ui.all(); assertTrue(controller.snapshot().retainedData);
    controller.submit(TOGGLE_LANGUAGE); worker.all(); ui.all(); assertFalse(controller.snapshot().retainedData);
    controller.finishEditor(); worker.all(); ui.all(); assertFalse(controller.snapshot().retainedData);
  }

  @Test public void oldRetainedSessionCannotPublishNoticeIntoAChangedEditor()
  {
    Queue worker = new Queue(), ui = new Queue(); FakeEngine engine = new FakeEngine(); engine.retained = true;
    InputController controller = new InputController(worker, ui, () -> engine, snapshot -> {});
    controller.startEditor(new Editor(), Mode.CHINESE, true, true); worker.all();
    controller.startEditor(new Editor(), Mode.ENGLISH, false, false); ui.all();
    assertFalse(controller.snapshot().retainedData); assertEquals(Mode.ENGLISH, controller.snapshot().mode);
    worker.all(); ui.all(); assertFalse(controller.snapshot().retainedData);
  }

  @Test public void returningToTheCurrentProfileIsImmediateAndKeepsCandidateRevision()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao")); h.flush();
    InputSnapshot before = h.controller.snapshot();
    int calls = h.engine.calls.size(); int[] applied = {0};
    assertTrue(h.controller.setLanguage(Mode.CHINESE, () -> applied[0]++));
    assertEquals(1, applied[0]);
    assertSame(before, h.controller.snapshot());
    assertEquals(calls, h.engine.calls.size());
    assertEquals("", h.editor.text());
  }
  @Test public void explicitProfileSelectionCannotAccidentallyToggleBack()
  {
    Harness h = new Harness(Mode.CHINESE);
    int[] callbacks = {0};
    h.controller.submit(engineText("nihao"));
    h.controller.setLanguage(Mode.ENGLISH, () -> callbacks[0]++);
    h.controller.setLanguage(Mode.ENGLISH, () -> callbacks[0]++);
    h.flush();
    assertEquals("nihao", h.editor.text());
    assertEquals(Mode.ENGLISH, h.controller.snapshot().mode);
    assertEquals(2, callbacks[0]);
  }
  @Test public void failedEnglishHandoffDoesNotApplyTheSelectedProfile()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.engine.failEnglish = true;
    int[] callbacks = {0};
    h.controller.submit(engineText("nihao"));
    h.controller.setLanguage(Mode.ENGLISH, () -> callbacks[0]++);
    h.flush();
    assertEquals("", h.editor.text());
    assertEquals(Mode.CHINESE, h.controller.snapshot().mode);
    assertEquals(0, callbacks[0]);
  }
  @Test public void separatorUsesQueuedEngineTruthRatherThanTheOldUiPreedit()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("xi"));
    h.controller.submit(PINYIN_SEPARATOR);
    h.controller.submit(engineText("an"));
    h.flush();
    assertEquals("xi'an", h.controller.snapshot().engine.preedit);
    assertEquals("", h.editor.text());
    assertTrue(h.engine.calls.contains("text:'"));
    h.controller.cancelComposition(); h.flush();
    h.controller.submit(PINYIN_SEPARATOR);
    assertEquals("’", h.editor.text());
  }

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

  @Test public void candidateExtensionRejectsOldAndDuplicateSnapshots()
  {
    Harness h=new Harness(Mode.CHINESE);h.engine.lastPage=false;
    h.controller.submit(engineText("shi"));h.flush();InputSnapshot first=h.controller.snapshot();
    h.controller.moreCandidates(first);h.controller.moreCandidates(first);h.flush();
    assertEquals(1,h.engine.calls.stream().filter(x->x.startsWith("candidates:")).count());
    assertEquals(42,h.controller.snapshot().engine.candidates.size());assertEquals("",h.editor.text());
    h.controller.select(first,1);h.flush();assertEquals("",h.editor.text());
    InputSnapshot now=h.controller.snapshot();h.controller.submit(engineText("x"));h.controller.moreCandidates(now);h.flush();
    assertEquals(1,h.engine.calls.stream().filter(x->x.startsWith("candidates:")).count());
    h.controller.startEditor(new Editor(),Mode.CHINESE,true,true);h.controller.moreCandidates(now);h.flush();
    assertEquals(1,h.engine.calls.stream().filter(x->x.startsWith("candidates:")).count());
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

  @Test public void displayedCandidateSurvivesPendingAppendAndCommitsExactlyOnce()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.engine.lastPage = false;
    h.controller.submit(engineText("shi")); h.flush();
    InputSnapshot displayed = h.controller.snapshot();
    h.controller.moreCandidates(displayed);
    h.worker.all(); // The extended list exists on the worker and the old row is still displayed.
    assertSame(displayed, h.controller.snapshot());
    int[] applied = {0};
    Runnable accepted = () -> { assertEquals("候选", h.editor.text()); applied[0]++; };
    h.controller.select(displayed, 1, accepted);
    h.controller.select(displayed, 1, accepted);
    h.worker.all();
    assertEquals(0, applied[0]);
    h.ui.all();
    assertEquals("候选", h.editor.text());
    assertEquals(1, applied[0]);
    assertEquals(1, h.engine.calls.stream().filter(call -> call.equals("select:1")).count());
  }

  @Test public void aLoadThatChangesRankCannotSelectOrCloseTheOldPanel()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.engine.lastPage = false;
    h.engine.changePrefix = true;
    h.controller.submit(engineText("shi")); h.flush();
    InputSnapshot displayed = h.controller.snapshot();
    h.controller.moreCandidates(displayed);
    h.controller.select(displayed, 1, () -> fail("A reordered view cannot close the panel"));
    h.flush();
    assertEquals("", h.editor.text());
    assertFalse(h.engine.calls.contains("select:1"));
    assertEquals("改序", h.controller.snapshot().engine.candidates.get(1));
  }

  @Test public void editorRejectionDoesNotAcknowledgeACandidateTap()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao")); h.flush();
    h.editor.reject = true;
    h.controller.select(h.controller.snapshot(), 0, () -> fail("Rejected delivery cannot close the panel"));
    h.flush();
    assertEquals("", h.editor.text());
    assertFalse(h.controller.snapshot().engineAllowed);
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

  @Test public void failedLiteralConfirmationCanStillReleaseRawInputToEnglish()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.controller.submit(engineText("nihao")); h.flush();
    h.engine.failConfirm = true;
    h.controller.submit(literalText("。"));
    h.flush();
    assertEquals("", h.editor.text());
    assertEquals(Mode.CHINESE, h.controller.snapshot().mode);
    assertTrue(h.controller.snapshot().engine.composing);
    assertFalse(h.controller.snapshot().error.isEmpty());
    h.controller.submit(TOGGLE_LANGUAGE); h.flush();
    assertEquals("nihao", h.editor.text());
    assertEquals(Mode.ENGLISH, h.controller.snapshot().mode);
    assertFalse(h.controller.snapshot().engine.composing);
    assertEquals("", h.controller.snapshot().error);
  }

  @Test public void englishHandoffUsesQueuedRawInputAndDoesNotChooseFirstCandidate()
  {
    Harness h = new Harness(Mode.CHINESE); h.engine.failConfirm = true;
    h.controller.submit(engineText("nihao"));
    h.controller.setLanguage(Mode.ENGLISH, null);
    h.controller.submit(engineText("World"));
    h.worker.all(); assertEquals("", h.editor.text()); h.ui.all();
    assertEquals("nihaoWorld", h.editor.text()); assertEquals(Mode.ENGLISH, h.controller.snapshot().mode);
    assertEquals(Arrays.asList("text:nihao", "english"), h.engine.calls);
  }

  @Test public void completedManualShiftReleasesQueuedChineseInputOnlyOnce()
  {
    Harness h = new Harness(Mode.CHINESE); int[] requests = {0};
    juloo.keyboard2.SemanticKeyAdapter adapter = new juloo.keyboard2.SemanticKeyAdapter(h.controller, () -> {}, () -> {
      requests[0]++; h.controller.setLanguage(Mode.ENGLISH, null);
    });
    h.controller.submit(engineText("nihao")); adapter.shiftActivated(); h.flush();
    assertEquals("nihao", h.editor.text()); assertEquals(1, requests[0]);
    adapter.shiftActivated(); h.flush(); assertEquals(1, requests[0]); assertEquals("nihao", h.editor.text());
  }

  @Test public void idleManualShiftDoesNotChangeChineseModeOrConfirmAnything()
  {
    Harness h = new Harness(Mode.CHINESE); int[] requests = {0};
    juloo.keyboard2.SemanticKeyAdapter adapter = new juloo.keyboard2.SemanticKeyAdapter(h.controller, () -> {}, () -> requests[0]++);
    adapter.shiftActivated(); h.flush(); assertEquals(0, requests[0]); assertEquals(Mode.CHINESE, h.controller.snapshot().mode);
    assertEquals("", h.editor.text()); assertTrue(h.engine.calls.isEmpty());
  }

  @Test public void manualShiftWhileOnlyInitializingRetainsIdleCapitalization()
  {
    Queue worker = new Queue(), ui = new Queue(); int[] requests = {0};
    InputController controller = new InputController(worker, ui, FakeEngine::new, snapshot -> {});
    controller.startEditor(new Editor(), Mode.CHINESE, true, true);
    assertTrue(controller.snapshot().initializing); assertTrue(controller.hasPendingRequests());
    new juloo.keyboard2.SemanticKeyAdapter(controller, () -> {}, () -> requests[0]++).shiftActivated();
    assertEquals(0, requests[0]);
  }

  @Test public void manualShiftInProtectedEditorDoesNotStartChineseOrChangeMode()
  {
    Harness h = new Harness(Mode.ENGLISH); h.controller.startEditor(h.editor, Mode.ENGLISH, false, false); h.flush();
    int[] requests = {0}; juloo.keyboard2.SemanticKeyAdapter adapter = new juloo.keyboard2.SemanticKeyAdapter(h.controller, () -> {}, () -> requests[0]++);
    adapter.shiftActivated(); h.flush(); assertEquals(0, requests[0]); assertEquals(0, h.creations);
  }

  @Test public void explicitPartialSelectionIsPreservedWithOnlyTheRemainingRawLetters()
  {
    Harness h = new Harness(Mode.CHINESE); h.engine.partialSelection = true;
    h.controller.submit(engineText("nihaoshijie")); h.flush();
    h.controller.select(h.controller.snapshot(), 0); h.flush();
    assertEquals("", h.editor.text()); assertTrue(h.controller.snapshot().engine.composing);
    h.controller.setLanguage(Mode.ENGLISH, null); h.flush();
    assertEquals("你好shijie", h.editor.text()); assertEquals(Mode.ENGLISH, h.controller.snapshot().mode);
  }

  @Test public void fullySelectedTextIsNotDuplicatedWhenSwitchingToEnglish()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush();
    h.controller.select(h.controller.snapshot(), 0); h.flush(); assertEquals("你好", h.editor.text());
    h.controller.setLanguage(Mode.ENGLISH, null); h.flush();
    assertEquals("你好", h.editor.text()); assertFalse(h.engine.calls.contains("english"));
  }

  @Test public void rejectedEnglishHandoffCannotPublishTheLayoutCallback()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush();
    int[] applied = {0}; h.editor.reject = true;
    h.controller.setLanguage(Mode.ENGLISH, () -> applied[0]++); h.flush();
    assertEquals(0, applied[0]); assertEquals("", h.editor.text()); assertFalse(h.controller.snapshot().engineAllowed);
  }

  @Test public void changedEditorCannotReceiveOldEnglishHandoffOrLayoutCallback()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush();
    int[] applied = {0}; h.controller.setLanguage(Mode.ENGLISH, () -> applied[0]++); h.worker.all();
    Editor next = new Editor(); h.controller.startEditor(next, Mode.CHINESE, true, false); h.flush();
    assertEquals("", h.editor.text()); assertEquals("", next.text()); assertEquals(0, applied[0]);
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

  @Test public void partialEngineFailurePreservesAlreadyConfirmedText()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.engine.partialFailure = true;
    h.controller.submit(engineText("batch")); h.flush();
    assertEquals("已确认", h.editor.text());
    assertEquals("x", h.controller.snapshot().engine.preedit);
    assertFalse(h.controller.snapshot().error.isEmpty());
    h.controller.cancelComposition(); h.flush();
    assertFalse(h.controller.snapshot().engine.composing);
  }

  @Test public void maintenanceConfirmsAndDeliversBeforeClosingAndUpdating()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao"));
    List<String> completed = new ArrayList<>();
    h.controller.reconfigure(() -> { assertEquals("你好", h.editor.text()); assertEquals(1, h.engine.closes); }, completed::add);
    h.controller.submit(literalText("ignored while applying"));
    h.flush();
    assertEquals(Arrays.asList(""), completed); assertEquals("你好", h.editor.text());
    assertEquals(2, h.creations); assertFalse(h.controller.hasPendingRequests());
    h.controller.submit(engineText("di")); h.controller.submit(SPACE); h.flush();
    assertEquals("你好第", h.editor.text());
  }
  @Test public void maintenanceFailureReopensThePriorFactoryAndReportsFailure()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush();
    List<String> errors = new ArrayList<>();
    h.controller.reconfigure(() -> { throw new java.io.IOException("deployment failed"); }, errors::add); h.flush();
    assertEquals(Arrays.asList("deployment failed"), errors); assertEquals("你好", h.editor.text());
    h.controller.submit(engineText("di")); h.controller.submit(SPACE); h.flush(); assertEquals("你好第", h.editor.text());
  }
  @Test public void failedConfirmationPreventsAnyDeployment()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush(); h.engine.failConfirm = true;
    int[] updates = {0}; List<String> errors = new ArrayList<>();
    h.controller.reconfigure(() -> updates[0]++, errors::add); h.flush();
    assertEquals(0, updates[0]); assertEquals(0, h.engine.closes); assertTrue(h.controller.snapshot().engine.composing);
    assertFalse(errors.get(0).isEmpty()); assertFalse(h.controller.hasPendingRequests());
  }
  @Test public void maintenanceDoesNotDeliverAnOldCommitToAReplacementEditor()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush();
    int[] updates = {0}; List<String> errors = new ArrayList<>();
    h.controller.reconfigure(() -> updates[0]++, errors::add); h.worker.all();
    Editor replacement = new Editor(); h.controller.startEditor(replacement, Mode.CHINESE, true, true);
    h.flush(); assertEquals(1, updates[0]); assertEquals(Arrays.asList(""), errors);
    assertEquals("", h.editor.text()); assertEquals("", replacement.text());
    h.controller.submit(engineText("di")); h.controller.submit(SPACE); h.flush(); assertEquals("第", replacement.text());
  }
  @Test public void oldCandidatesCannotSelectFromTheReplacementDictionary()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush(); InputSnapshot old = h.controller.snapshot();
    h.controller.reconfigure(() -> {}, error -> assertEquals("", error)); h.flush();
    h.controller.submit(engineText("di")); h.flush(); h.controller.select(old, 1); h.flush();
    assertEquals("di", h.controller.snapshot().engine.preedit); assertEquals("你好", h.editor.text());
  }
  @Test public void closingDuringMaintenanceStillCompletesTheAuthorizedUpdate()
  {
    Harness h = new Harness(Mode.CHINESE); int[] updates = {0}; List<String> errors = new ArrayList<>();
    h.controller.reconfigure(() -> updates[0]++, errors::add); h.controller.close(); h.flush();
    assertEquals(1, updates[0]); assertEquals(Arrays.asList(""), errors); assertFalse(h.controller.hasPendingRequests());
  }
  @Test public void rejectingTheOldCommitPreventsDeploymentAndRequiresRefocus()
  {
    Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush(); h.editor.reject = true;
    int[] updates = {0}; List<String> errors = new ArrayList<>();
    h.controller.reconfigure(() -> updates[0]++, errors::add); h.flush();
    assertEquals(0, updates[0]); assertFalse(errors.get(0).isEmpty()); assertFalse(h.controller.snapshot().engineAllowed);
  }

  @Test public void aNewControllerWaitsForAnExistingDeploymentBeforeInitializing()
  {
    Queue worker = new Queue(), ui = new Queue(); Editor editor = new Editor(); int[] creations = {0};
    InputController controller = new InputController(worker, ui, () -> { creations[0]++; return new FakeEngine(); }, snapshot -> {});
    controller.holdForExternalUpdate(); controller.startEditor(editor, Mode.CHINESE, true, true);
    controller.submit(engineText("nihao")); worker.all(); ui.all();
    assertEquals(0, creations[0]); assertEquals("", editor.text()); assertTrue(controller.hasPendingRequests());
    controller.releaseExternalUpdate(); worker.all(); ui.all();
    assertEquals(1, creations[0]); assertFalse(controller.hasPendingRequests());
  }
  @Test public void closingANewControllerDuringExternalMaintenanceDoesNotInitializeIt()
  {
    Queue worker = new Queue(), ui = new Queue(); int[] creations = {0};
    InputController controller = new InputController(worker, ui, () -> { creations[0]++; return new FakeEngine(); }, snapshot -> {});
    controller.holdForExternalUpdate(); controller.startEditor(new Editor(), Mode.CHINESE, true, true);
    controller.close(); controller.releaseExternalUpdate(); worker.all(); ui.all();
    assertEquals(0, creations[0]); assertFalse(controller.hasPendingRequests());
  }

  @Test public void aFailedConfirmationDuringEditorReplacementCannotCarryOldComposition()
  {
    for (boolean learning : new boolean[] {true, false})
    {
      Harness h = new Harness(Mode.CHINESE); h.controller.submit(engineText("nihao")); h.flush(); h.engine.failConfirm = true;
      int[] updates = {0}; List<String> errors = new ArrayList<>();
      h.controller.reconfigure(() -> updates[0]++, errors::add); h.worker.all();
      Editor replacement = new Editor(); h.controller.startEditor(replacement, Mode.CHINESE, true, learning); h.flush();
      assertEquals(0, updates[0]); assertFalse(errors.get(0).isEmpty()); assertEquals("", replacement.text());
      h.engine.failConfirm = false;
      h.controller.submit(engineText("di")); h.controller.submit(SPACE); h.flush();
      assertEquals("第", replacement.text()); assertEquals("", h.editor.text());
    }
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
    String raw = "", chosenPrefix = "";
    final List<String> calls = new ArrayList<>();
    final List<Boolean> learning = new ArrayList<>();
    boolean failConfirm, failEnglish, partialFailure, partialSelection;
    boolean lastPage=true, changePrefix;
    int closes;
    String selected() { return raw.equals("nihao") ? "你好" : raw.equals("di") ? "第" : raw.equals("ni") ? "你" : raw; }
    EngineResult result(boolean handled, String commit)
    {
      return new EngineResult(handled, commit, raw,
          raw.isEmpty() ? new String[0] : new String[] { selected(), "候选" }, 0, !raw.isEmpty(), lastPage);
    }
    @Override public EngineResult processText(String text)
    {
      calls.add("text:" + text);
      if (partialFailure) { raw = "x"; return result(false, "已确认"); }
      raw += text; return result(true, "");
    }
    @Override public EngineResult confirm()
    {
      if (failConfirm) return result(false, "");
      String text = selected(); raw = ""; return result(true, text);
    }
    @Override public EngineResult releaseToEnglish()
    {
      calls.add("english"); if (failEnglish) return result(false, "");
      String text = chosenPrefix + raw; chosenPrefix = ""; raw = ""; return result(true, text);
    }
    @Override public EngineResult backspace() { raw = raw.substring(0, raw.length() - 1); return result(true, ""); }
    @Override public EngineResult space() { return confirm(); }
    @Override public EngineResult clear() { raw = ""; chosenPrefix = ""; return result(true, ""); }
    @Override public EngineResult select(int index)
    {
      calls.add("select:" + index);
      if (partialSelection && index == 0 && raw.equals("nihaoshijie"))
      { chosenPrefix = "你好"; raw = "shijie"; return result(true, ""); }
      if (index == 0) return confirm();
      raw = ""; return result(true, "候选");
    }
    @Override public EngineResult page(boolean backward) { return result(false, ""); }
    @Override public EngineResult candidates(int limit)
    {
      calls.add("candidates:"+limit);String[] values=new String[limit];Arrays.fill(values,"候选");values[0]=selected();
      if (changePrefix) values[1] = "改序";
      return new EngineResult(true,"",raw,values,0,true,false);
    }
    boolean retained;
    @Override public boolean usesRetainedData() { return retained; }
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
