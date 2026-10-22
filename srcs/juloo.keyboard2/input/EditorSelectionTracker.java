package juloo.keyboard2.input;

import java.util.ArrayDeque;

/** Distinguish our delayed editor acknowledgements from external cursor moves. */
public final class EditorSelectionTracker
{
  private final ArrayDeque<int[]> expected = new ArrayDeque<>();
  private int start, end;
  public void reset(int start, int end) { expected.clear(); this.start = start; this.end = end; }
  public void commit(String text)
  {
    if (start < 0 || end < 0) return;
    start = Math.min(start, end) + text.length();
    end = start;
    remember();
  }
  public void backspace(int utf16Units)
  {
    if (start < 0 || end < 0) return;
    start = start == end ? Math.max(0, start - utf16Units) : Math.min(start, end);
    end = start;
    remember();
  }
  private void remember()
  {
    expected.add(new int[] { start, end });
    if (expected.size() > 64) expected.removeFirst();
  }
  public void expectSelection(int newStart, int newEnd)
  {
    if (newStart < 0 || newEnd < 0) return;
    start = newStart;
    end = newEnd;
    remember();
  }
  public boolean acknowledge(int newStart, int newEnd)
  {
    int index = 0, found = -1;
    for (int[] range : expected)
    {
      if (range[0] == newStart && range[1] == newEnd) found = index;
      index++;
    }
    if (found >= 0)
    {
      for (int i = 0; i <= found; i++) expected.removeFirst();
      return true;
    }
    reset(newStart, newEnd);
    return false;
  }
}
