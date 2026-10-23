package juloo.keyboard2.input;

/** Keeps the ranked prefix that fits; only an overlong first item is clipped. */
public final class CandidateRowFitter
{
  private CandidateRowFitter() {}
  public static int count(int available, int[] widths)
  {
    if (available <= 0 || widths.length == 0) return 0;
    int used = 0;
    for (int i = 0; i < widths.length; i++)
    {
      int width = Math.max(0, widths[i]);
      if (i > 0 && width > available - used) return i;
      used += Math.min(width, available);
    }
    return widths.length;
  }
}
