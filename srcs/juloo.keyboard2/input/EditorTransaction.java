package juloo.keyboard2.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered editor work, applied on the UI thread inside one bounded batch edit. */
public final class EditorTransaction
{
  public static final class Edit
  {
    public final String text;
    public final boolean backspace;
    private Edit(String text, boolean backspace) { this.text = text; this.backspace = backspace; }
    public static Edit text(String text) { return new Edit(text, false); }
    public static Edit backspace() { return new Edit("", true); }
  }

  public final List<Edit> edits;
  public EditorTransaction(List<Edit> edits)
  {
    this.edits = Collections.unmodifiableList(new ArrayList<>(edits));
  }
}
