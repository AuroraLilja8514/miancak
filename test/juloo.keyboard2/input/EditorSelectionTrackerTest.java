package juloo.keyboard2.input;
import org.junit.Test;
import static org.junit.Assert.*;

public class EditorSelectionTrackerTest
{
  @Test public void collapsedBatchAcknowledgementDoesNotLookLikeACursorMove()
  {
    EditorSelectionTracker tracker = new EditorSelectionTracker();
    tracker.reset(0, 0);
    tracker.commit("你好"); tracker.commit("。");
    assertTrue(tracker.acknowledge(3, 3));
    assertFalse(tracker.acknowledge(1, 1));
  }
  @Test public void delayedEnterAcknowledgementsPreserveFollowingInput()
  {
    EditorSelectionTracker tracker = new EditorSelectionTracker();
    tracker.reset(0, 0);
    tracker.commit("你好");
    tracker.expectSelection(3, 3); // legacy Enter
    tracker.commit("q");
    assertTrue(tracker.acknowledge(2, 2));
    assertTrue(tracker.acknowledge(3, 3));
    assertTrue(tracker.acknowledge(4, 4));
    assertFalse(tracker.acknowledge(0, 0));
  }
  @Test public void replacementSelectionAndSupplementaryBackspaceUseUtf16Offsets()
  {
    EditorSelectionTracker tracker = new EditorSelectionTracker();
    tracker.reset(4, 1); tracker.commit("😀");
    assertTrue(tracker.acknowledge(3, 3));
    tracker.backspace(2);
    assertTrue(tracker.acknowledge(1, 1));
  }
}
