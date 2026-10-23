package juloo.keyboard2.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure routing rules, not a composition engine or an Android editor adapter.
 *
 * The future executor must plan against authoritative composition state after
 * preceding actions finish, execute operations in order, and apply resultingMode
 * only after success. A cached UI snapshot is not authoritative when engine work
 * is queued. CONFIRM_COMPOSITION means committing the engine's selected result,
 * not copying the visible preedit. It must finish before following literal text.
 */
public final class InputRouter
{
  public enum Mode { CHINESE, ENGLISH }

  public enum OperationType
  {
    ENGINE_TEXT, ENGINE_BACKSPACE, ENGINE_SPACE,
    CONFIRM_COMPOSITION, COMMIT_TEXT, EDITOR_BACKSPACE
  }

  public static final class Operation
  {
    public final OperationType type;
    /** Exact payload for ENGINE_TEXT/COMMIT_TEXT; empty otherwise. */
    public final String text;

    private Operation(OperationType type, String text)
    {
      this.type = type;
      this.text = text;
    }
  }

  public static final class Plan
  {
    public final Mode resultingMode;
    public final List<Operation> operations;

    private Plan(Mode resultingMode, List<Operation> operations)
    {
      this.resultingMode = resultingMode;
      this.operations = Collections.unmodifiableList(new ArrayList<>(operations));
    }
  }

  private InputRouter() {}

  public static Plan route(Mode mode, boolean hasComposition, SemanticAction action)
  {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(action, "action");
    List<Operation> operations = new ArrayList<>();
    Mode resultingMode = mode;
    switch (action.type)
    {
      case ENGINE_TEXT:
        if (mode == Mode.CHINESE)
        {
          if (!action.text.isEmpty())
            operations.add(new Operation(OperationType.ENGINE_TEXT, action.text));
        }
        else
          appendLiteral(operations, hasComposition, action.text);
        break;
      case LITERAL_TEXT:
        appendLiteral(operations, hasComposition, action.text);
        break;
      case PINYIN_SEPARATOR:
        if (mode == Mode.CHINESE && hasComposition)
          operations.add(new Operation(OperationType.ENGINE_TEXT, "'"));
        else
          appendLiteral(operations, hasComposition, "’");
        break;
      case BACKSPACE:
        operations.add(new Operation(hasComposition ?
              OperationType.ENGINE_BACKSPACE : OperationType.EDITOR_BACKSPACE, ""));
        break;
      case SPACE:
        operations.add(new Operation(hasComposition ?
              OperationType.ENGINE_SPACE : OperationType.COMMIT_TEXT,
              hasComposition ? "" : " "));
        break;
      case TOGGLE_LANGUAGE:
        if (hasComposition)
          operations.add(new Operation(OperationType.CONFIRM_COMPOSITION, ""));
        resultingMode = mode == Mode.CHINESE ? Mode.ENGLISH : Mode.CHINESE;
        break;
      default:
        throw new AssertionError(action.type);
    }
    return new Plan(resultingMode, operations);
  }

  private static void appendLiteral(List<Operation> operations,
      boolean hasComposition, String text)
  {
    if (text.isEmpty())
      return;
    if (hasComposition)
      operations.add(new Operation(OperationType.CONFIRM_COMPOSITION, ""));
    operations.add(new Operation(OperationType.COMMIT_TEXT, text));
  }
}
