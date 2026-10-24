package juloo.keyboard2;

import juloo.keyboard2.input.InputController;
import juloo.keyboard2.input.SemanticAction;

/** Small boundary after upstream gesture/modifier processing. */
public final class SemanticKeyAdapter
{
  private final InputController controller;
  private final Runnable editorActionCompleted;
  private int legacyDepth;
  public SemanticKeyAdapter(InputController controller, Runnable editorActionCompleted)
  {
    this.controller = controller;
    this.editorActionCompleted = editorActionCompleted;
  }
  public boolean inLegacy() { return legacyDepth != 0; }

  public boolean handle(KeyValue key, Pointers.Modifiers mods)
  {
    if (mods.has(KeyValue.Modifier.CTRL) || mods.has(KeyValue.Modifier.ALT) || mods.has(KeyValue.Modifier.META))
      return false;
    switch (key.getKind())
    {
      case Engine:
        engine(key.getSemanticText());
        return true;
      case Literal:
        literal(key.getSemanticText());
        return true;
      case Char:
        char c = key.getChar();
        controller.submit(c >= 'a' && c <= 'z' ? SemanticAction.engineText(String.valueOf(c))
            : SemanticAction.literalText(String.valueOf(c)));
        return true;
      case String:
        literal(key.getString());
        return true;
      case Editing:
        if (key.getEditing() == KeyValue.Editing.SPACE_BAR)
          controller.submit(SemanticAction.SPACE);
        else if (key.getEditing() == KeyValue.Editing.BACKSPACE)
          controller.submit(SemanticAction.BACKSPACE);
        else return false;
        return true;
      case Event:
        if (key.getEvent() == KeyValue.Event.TOGGLE_LANGUAGE)
          controller.submit(SemanticAction.TOGGLE_LANGUAGE);
        else if (key.getEvent() == KeyValue.Event.PINYIN_SEPARATOR)
          controller.submit(SemanticAction.PINYIN_SEPARATOR);
        else return false;
        return true;
      default: return false;
    }
  }

  public void literal(String text) { controller.submit(SemanticAction.literalText(text)); }
  public void engine(String text) { controller.submit(SemanticAction.engineText(text)); }
  public void afterLegacy(Runnable action)
  {
    controller.afterConfirmation(wrap(action));
  }
  public void escape(Runnable action) { controller.cancelOrRun(wrap(action)); }
  private Runnable wrap(Runnable action)
  {
    return () -> {
      legacyDepth++;
      try { action.run(); editorActionCompleted.run(); }
      finally { legacyDepth--; }
    };
  }
}
