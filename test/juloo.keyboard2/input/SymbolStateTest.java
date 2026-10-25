package juloo.keyboard2.input;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SymbolStateTest
{
  @Test public void everyCategoryHasBoundedReachablePagesAndCanonicalScopes()
  {
    assertEquals(7, SymbolCatalog.categories().size());
    for (String category : SymbolCatalog.categories())
    {
      SymbolState state = new SymbolState(category, 0);
      assertFalse(state.previous()); state.turn(-1); assertEquals(0, state.page());
      for (int page = 0; page < SymbolCatalog.pageCount(category); page++)
      {
        assertEquals(category, state.category()); assertEquals(page, state.page());
        assertTrue(SymbolCatalog.validScope(state.scope()));
        assertFalse(SymbolCatalog.items(category, page).isEmpty());
        assertTrue(SymbolCatalog.items(category, page).size() <= SymbolCatalog.PAGE_SIZE);
        if (state.next()) state.turn(1);
      }
      int end = state.page(); state.turn(1); assertEquals(end, state.page());
      state.select(category); assertEquals(0, state.page());
    }
  }
  @Test public void invalidStoredStateAndNonCanonicalImportScopesCannotEscape()
  {
    assertEquals("symbols", new SymbolState("missing", 55).scope());
    assertEquals(0, new SymbolState("math", -99).page());
    assertFalse(SymbolCatalog.validScope("symbols/math/999"));
    assertFalse(SymbolCatalog.validScope("symbols/math/-1"));
    assertFalse(SymbolCatalog.validScope("symbols/math/01"));
    assertFalse(SymbolCatalog.validScope("symbols/common/0"));
    assertFalse(SymbolCatalog.validScope("symbols/../0"));
    assertEquals(InputRouter.Mode.ENGLISH, PageConfig.mode("symbols"));
    assertEquals("", new PageConfig(null, null).fn("symbols"));
  }
}
