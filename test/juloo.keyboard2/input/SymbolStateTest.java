package juloo.keyboard2.input;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SymbolStateTest
{
  @Test public void pagesCycleAcrossCategoriesInBothDirections()
  {
    assertEquals(7,SymbolCatalog.categories().size());java.util.List<String> scopes=new java.util.ArrayList<>();
    for(String c:SymbolCatalog.categories())for(int p=0;p<SymbolCatalog.pageCount(c);p++)scopes.add(SymbolCatalog.scope(c,p));
    SymbolState state=new SymbolState();
    for(int round=0;round<2;round++)for(String scope:scopes)
    {assertEquals(scope,state.scope());assertTrue(state.previous());assertTrue(state.next());state.turn(1);}
    assertEquals("symbols",state.scope());
    for(int i=scopes.size()-1;i>=0;i--){state.turn(-1);assertEquals(scopes.get(i),state.scope());}
    state.turn(Integer.MIN_VALUE);state.turn(Integer.MAX_VALUE);state.turn(1);assertEquals("symbols",state.scope());
    for(String c:SymbolCatalog.categories()){state.select(c);assertEquals(c,state.category());assertEquals(0,state.page());}
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
