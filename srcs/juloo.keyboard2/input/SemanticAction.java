package juloo.keyboard2.input;

import java.util.Objects;

/** Input meaning after frontend modifiers, independent of Android key codes. */
public final class SemanticAction
{
  public enum Type
  {
    ENGINE_TEXT, LITERAL_TEXT, BACKSPACE, SPACE, TOGGLE_LANGUAGE
  }

  public static final SemanticAction BACKSPACE =
    new SemanticAction(Type.BACKSPACE, "");
  public static final SemanticAction SPACE =
    new SemanticAction(Type.SPACE, "");
  public static final SemanticAction TOGGLE_LANGUAGE =
    new SemanticAction(Type.TOGGLE_LANGUAGE, "");

  public final Type type;
  /** Exact text for text actions; empty for control actions. */
  public final String text;

  private SemanticAction(Type type, String text)
  {
    this.type = type;
    this.text = Objects.requireNonNull(text, "text");
  }

  /** Deliberate engine input in Chinese; exact direct text in English. */
  public static SemanticAction engineText(String text)
  {
    return new SemanticAction(Type.ENGINE_TEXT, text);
  }

  /** Exact output, including punctuation/digits or explicit shifted ASCII. */
  public static SemanticAction literalText(String text)
  {
    return new SemanticAction(Type.LITERAL_TEXT, text);
  }
}
