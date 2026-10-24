package juloo.keyboard2.input;

import org.junit.Test;
import static org.junit.Assert.*;

public class CandidateRowFitterTest
{
  @Test public void exactFitIncludesTheLastWholeCandidate()
  {
    assertEquals(3, CandidateRowFitter.count(200, new int[] {64, 72, 64, 64}));
  }
  @Test public void onePixelLessDoesNotExposeAPartialItem()
  {
    assertEquals(2, CandidateRowFitter.count(199, new int[] {64, 72, 64, 64}));
  }
  @Test public void anOverlongFirstCandidateRemainsReachable()
  {
    assertEquals(1, CandidateRowFitter.count(100, new int[] {400, 64}));
  }
  @Test public void keepsRankingRatherThanSkippingToLaterShortItems()
  {
    assertEquals(1, CandidateRowFitter.count(180, new int[] {64, 140, 64}));
  }
  @Test public void noItemsOrNoSpaceShowsNoCandidates()
  {
    assertEquals(0, CandidateRowFitter.count(100, new int[0]));
    assertEquals(0, CandidateRowFitter.count(0, new int[] {64}));
    assertEquals(0, CandidateRowFitter.count(-1, new int[] {64}));
  }
}
