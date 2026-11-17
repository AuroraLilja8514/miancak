package juloo.keyboard2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClipboardHistoryServiceTest
{
  private ClipboardHistoryService history(Runnable clear, String... oldestFirst)
  {
    ClipboardHistoryService service = new ClipboardHistoryService(clear);
    for (String text : oldestFirst)
      service._history.add(0, new ClipboardHistoryService.HistoryEntry(text, Long.MAX_VALUE));
    return service;
  }

  @Test public void pinningOldestHistoryKeepsTheNewerSystemClipboard()
  {
    int[] cleared = {0};
    ClipboardHistoryService service = history(() -> cleared[0]++, "old", "middle", "new");
    service.remove_history_entry("old");
    assertEquals(Arrays.asList("new", "middle"), service.clear_expired_and_get_history());
    assertEquals(0, cleared[0]);
  }

  @Test public void pinningNewestHistoryClearsItsCurrentClipboardOnce()
  {
    int[] cleared = {0};
    ClipboardHistoryService service = history(() -> cleared[0]++, "old", "middle", "new");
    service.remove_history_entry("new");
    assertEquals(Arrays.asList("middle", "old"), service.clear_expired_and_get_history());
    assertEquals(1, cleared[0]);
  }

  @Test public void pinningMiddleHistoryKeepsCurrentClipboardAndOrder()
  {
    int[] cleared = {0};
    ClipboardHistoryService service = history(() -> cleared[0]++, "old", "middle", "new");
    service.remove_history_entry("middle");
    assertEquals(Arrays.asList("new", "old"), service.clear_expired_and_get_history());
    assertEquals(0, cleared[0]);
  }

  @Test public void pinningTheOnlyEntryClearsOnceAndMissingEntryNeverClears()
  {
    int[] cleared = {0};
    ClipboardHistoryService service = history(() -> cleared[0]++, "only");
    service.remove_history_entry("missing");
    assertEquals(0, cleared[0]);
    service.remove_history_entry("only");
    assertTrue(service.clear_expired_and_get_history().isEmpty());
    assertEquals(1, cleared[0]);
    service.remove_history_entry("only");
    assertEquals(1, cleared[0]);
  }

  @Test public void pinningRepeatedOldTextDoesNotClearAnUnrelatedNewClip()
  {
    int[] cleared = {0};
    ClipboardHistoryService service = history(() -> cleared[0]++, "same", "other", "same", "new");
    service.remove_history_entry("same");
    assertEquals(Arrays.asList("new", "other"), service.clear_expired_and_get_history());
    assertEquals(0, cleared[0]);
  }

  @Test public void pinningRepeatedCurrentTextRemovesAllCopiesAndClearsOnlyOnce()
  {
    int[] cleared = {0};
    ClipboardHistoryService service = history(() -> cleared[0]++, "same", "other", "same");
    service.remove_history_entry("same");
    assertEquals(Arrays.asList("other"), service.clear_expired_and_get_history());
    assertEquals(1, cleared[0]);
  }

  @Test public void listenerSeesUpdatedHistoryAfterTheCurrentClipboardIsCleared()
  {
    List<String> events = new ArrayList<>();
    ClipboardHistoryService service = history(() -> events.add("clear"), "old", "new");
    service.set_on_clipboard_history_change(() -> {
      assertEquals(Arrays.asList("old"), service.clear_expired_and_get_history());
      events.add("changed");
    });
    service.remove_history_entry("new");
    assertEquals(Arrays.asList("clear", "changed"), events);
  }

  @Test public void emptyHistoryDoesNotWriteClipboardAndStillNotifiesOnce()
  {
    int[] cleared = {0}, notified = {0};
    ClipboardHistoryService service = history(() -> cleared[0]++);
    service.set_on_clipboard_history_change(() -> notified[0]++);
    service.remove_history_entry("absent");
    assertEquals(0, cleared[0]);
    assertEquals(1, notified[0]);
  }
}
