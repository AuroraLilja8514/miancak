package juloo.keyboard2.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static juloo.keyboard2.input.SemanticAction.*;
import juloo.keyboard2.input.InputRouter.Mode;

public class InputRouterTest
{
  @Test public void chineseSingleQuoteSeparatesOnlyAnActiveChineseComposition()
  {
    InputRouter.Plan composing = InputRouter.route(Mode.CHINESE, true, PINYIN_SEPARATOR);
    assertEquals(1, composing.operations.size());
    assertEquals(InputRouter.OperationType.ENGINE_TEXT, composing.operations.get(0).type);
    assertEquals("'", composing.operations.get(0).text);
    for (Mode mode : Mode.values())
    {
      InputRouter.Plan idle = InputRouter.route(mode, false, PINYIN_SEPARATOR);
      assertEquals(1, idle.operations.size());
      assertEquals(InputRouter.OperationType.COMMIT_TEXT, idle.operations.get(0).type);
      assertEquals("’", idle.operations.get(0).text);
    }
    InputRouter.Plan literal = InputRouter.route(Mode.CHINESE, true, literalText("’"));
    assertEquals(InputRouter.OperationType.CONFIRM_COMPOSITION, literal.operations.get(0).type);
    assertEquals("’", literal.operations.get(1).text);
  }

  @Test
  public void englishTextCommitsWithoutEngineOrSuggestions()
  {
    Harness h = new Harness(Mode.ENGLISH);
    h.send(engineText("qQé😀"));
    h.send(literalText("。3"));
    assertEquals("qQé😀。3", h.output());
    assertEquals(Arrays.asList("commit:qQé😀", "commit:。3"), h.events);
    assertEquals("", h.preedit.toString());
  }

  @Test
  public void chineseEngineTextWaitsForConfirmation()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.send(engineText("nihao"));
    assertEquals("nihao", h.preedit.toString());
    assertEquals("", h.output());
    assertEquals(Arrays.asList("engine:nihao"), h.events);
  }

  @Test
  public void literalFollowsSelectedCandidateInOneOrderedPlan()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.send(engineText("nihao"));
    h.send(literalText("。"));
    assertEquals("你好。", h.output());
    assertEquals("", h.preedit.toString());
    assertEquals(Arrays.asList("engine:nihao", "confirm", "commit:。"), h.events);
  }

  @Test
  public void punctuationDigitsAndUnicodeStayExactInBothModes()
  {
    for (Mode mode : Mode.values())
      for (String literal : new String[] { ".", "。", ",", "，", "3", "$", "😀" })
      {
        Harness h = new Harness(mode);
        if (mode == Mode.CHINESE)
          h.send(engineText("di"));
        h.send(literalText(literal));
        assertEquals((mode == Mode.CHINESE ? "第" : "") + literal, h.output());
        assertEquals("", h.preedit.toString());
        assertEquals("commit:" + literal, h.events.get(h.events.size() - 1));
      }
  }

  @Test
  public void explicitlyDeclaredEnginePunctuationIsNotReclassified()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.send(engineText("."));
    assertEquals(".", h.preedit.toString());
    assertEquals("", h.output());
    assertEquals(Arrays.asList("engine:."), h.events);
  }

  @Test
  public void spaceConfirmsCompositionThenIdleSpaceIsLiteral()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.send(engineText("nihao"));
    h.send(SPACE);
    assertEquals("你好", h.output());
    h.send(SPACE);
    assertEquals("你好 ", h.output());
    assertEquals(Arrays.asList("engine:nihao", "engine:space", "commit: "), h.events);
  }

  @Test
  public void backspaceEditsCompositionBeforeTheEditor()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.send(literalText("😀"));
    h.send(engineText("q"));
    h.send(BACKSPACE);
    assertEquals("", h.preedit.toString());
    assertEquals("😀", h.output());
    h.send(BACKSPACE);
    assertEquals("", h.output());
    assertEquals(Arrays.asList("commit:😀", "engine:q", "engine:backspace",
          "editor:backspace"), h.events);
  }

  @Test
  public void languageToggleConfirmsBeforeEnglishInput()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.send(engineText("nihao"));
    h.send(TOGGLE_LANGUAGE);
    assertEquals(Mode.ENGLISH, h.mode);
    assertEquals("你好", h.output());
    h.send(engineText("Q"));
    assertEquals("你好Q", h.output());
    assertEquals(Arrays.asList("engine:nihao", "confirm", "commit:Q"), h.events);
    h.send(TOGGLE_LANGUAGE);
    assertEquals(Mode.CHINESE, h.mode);
    h.send(engineText("di"));
    assertEquals("di", h.preedit.toString());
  }

  @Test
  public void idleLanguageToggleDoesNotSendEngineOrEditorOperations()
  {
    Harness h = new Harness(Mode.ENGLISH);
    h.send(TOGGLE_LANGUAGE);
    assertEquals(Mode.CHINESE, h.mode);
    h.send(TOGGLE_LANGUAGE);
    assertEquals(Mode.ENGLISH, h.mode);
    assertTrue(h.events.isEmpty());
  }

  @Test
  public void explicitShiftedAsciiBypassKeepsChineseMode()
  {
    Harness h = new Harness(Mode.CHINESE);
    h.send(engineText("di"));
    // Frontend keeps ownership of Shift latch/lock and classifies this action.
    h.send(literalText("Q"));
    assertEquals("第Q", h.output());
    assertEquals(Mode.CHINESE, h.mode);
    h.send(engineText("nihao"));
    assertEquals("nihao", h.preedit.toString());
  }

  @Test
  public void emptyTextDoesNotConfirmOrDisturbComposition()
  {
    for (Mode mode : Mode.values())
    {
      InputRouter.Plan engine = InputRouter.route(mode, true, engineText(""));
      InputRouter.Plan literal = InputRouter.route(mode, true, literalText(""));
      assertTrue(engine.operations.isEmpty());
      assertTrue(literal.operations.isEmpty());
      assertEquals(mode, engine.resultingMode);
      assertEquals(mode, literal.resultingMode);
    }
  }

  @Test
  public void outstandingCompositionCannotBeOvertakenByEnglishDirectText()
  {
    // Defensively preserve ordering even if an adapter supplies this state.
    Harness h = new Harness(Mode.ENGLISH);
    h.preedit.append("nihao");
    h.send(engineText("x"));
    assertEquals("你好x", h.output());
    assertEquals(Arrays.asList("confirm", "commit:x"), h.events);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void plansCannotBeMutated()
  {
    InputRouter.route(Mode.CHINESE, true, literalText("3")).operations.clear();
  }

  @Test(expected = NullPointerException.class)
  public void nullLiteralIsRejected()
  {
    literalText(null);
  }

  /** Deterministic engine/editor substitute, with no Android or Rime dependency. */
  private static final class Harness
  {
    Mode mode;
    final StringBuilder preedit = new StringBuilder();
    final StringBuilder committed = new StringBuilder();
    final List<String> events = new ArrayList<>();

    Harness(Mode mode) { this.mode = mode; }
    String output() { return committed.toString(); }

    void send(SemanticAction action)
    {
      InputRouter.Plan plan = InputRouter.route(mode, preedit.length() > 0, action);
      for (InputRouter.Operation op : plan.operations)
        switch (op.type)
        {
          case ENGINE_TEXT:
            events.add("engine:" + op.text);
            preedit.append(op.text);
            break;
          case CONFIRM_COMPOSITION:
            events.add("confirm");
            confirm();
            break;
          case COMMIT_TEXT:
            events.add("commit:" + op.text);
            committed.append(op.text);
            break;
          case ENGINE_SPACE:
            events.add("engine:space");
            confirm();
            break;
          case ENGINE_BACKSPACE:
            events.add("engine:backspace");
            deleteLastCodePoint(preedit);
            break;
          case EDITOR_BACKSPACE:
            events.add("editor:backspace");
            deleteLastCodePoint(committed);
            break;
          default:
            throw new AssertionError(op.type);
        }
      mode = plan.resultingMode;
    }

    void confirm()
    {
      String raw = preedit.toString();
      committed.append(raw.equals("nihao") ? "你好" : raw.equals("di") ? "第" : raw);
      preedit.setLength(0);
    }

    void deleteLastCodePoint(StringBuilder text)
    {
      if (text.length() > 0)
        text.delete(text.offsetByCodePoints(text.length(), -1), text.length());
    }
  }
}
