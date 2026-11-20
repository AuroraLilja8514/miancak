package juloo.keyboard2.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable result of one serialized engine operation. Candidate indices belong to this displayed window. */
public final class EngineResult
{
  public static final EngineResult EMPTY = new EngineResult(true, "", "", new String[0], 0, false, true);
  public final boolean handled;
  public final String committed;
  public final String preedit;
  public final List<String> candidates;
  public final int highlighted;
  public final boolean composing;
  public final boolean lastPage;
  private final boolean sameNativePrefix;

  public EngineResult(boolean handled, String committed, String preedit,
      String[] candidates, int highlighted, boolean composing, boolean lastPage)
  { this(handled, committed, preedit, candidates, highlighted, composing, lastPage, false); }

  public EngineResult(boolean handled, String committed, String preedit,
      String[] candidates, int highlighted, boolean composing, boolean lastPage, boolean sameNativePrefix)
  {
    this.handled = handled;
    this.committed = Objects.requireNonNull(committed);
    this.preedit = Objects.requireNonNull(preedit);
    this.candidates = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(candidates)));
    this.highlighted = highlighted;
    this.composing = composing;
    this.lastPage = lastPage;
    this.sameNativePrefix = sameNativePrefix;
  }

  public EngineResult withCommitted(String text)
  {
    return new EngineResult(handled, text, preedit, candidates.toArray(new String[0]),
        highlighted, composing, lastPage, sameNativePrefix);
  }

  boolean extendsCandidatePrefix(EngineResult before)
  {
    return (this == before || sameNativePrefix) && handled && composing && before.composing && committed.isEmpty()
        && preedit.equals(before.preedit) && highlighted == before.highlighted
        && candidates.size() >= before.candidates.size()
        && candidates.subList(0, before.candidates.size()).equals(before.candidates);
  }
}
