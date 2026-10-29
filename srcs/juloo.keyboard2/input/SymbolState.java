package juloo.keyboard2.input;

/** A circular category/page browser, never a language/layout or Fn state. */
public final class SymbolState
{
  private String category;
  private int page;
  public SymbolState() { this("common", 0); }
  public SymbolState(String category, int page)
  {
    this.category = SymbolCatalog.contains(category) ? category : "common";
    this.page = Math.max(0, Math.min(page, SymbolCatalog.pageCount(this.category) - 1));
  }
  public String category() { return category; }
  public int page() { return page; }
  public String scope() { return SymbolCatalog.scope(category, page); }
  public boolean previous() { return true; }
  public boolean next() { return true; }
  public void select(String category)
  { if (SymbolCatalog.contains(category)) { this.category = category; page = 0; } }
  public void turn(int delta)
  {
    int total = 0, offset = page; boolean before = true;
    for (String id : SymbolCatalog.categories())
    {
      if (id.equals(category)) before = false;
      if (before) offset += SymbolCatalog.pageCount(id);
      total += SymbolCatalog.pageCount(id);
    }
    offset = (int)Math.floorMod((long)offset + delta, (long)total);
    for (String id : SymbolCatalog.categories())
    {
      int count = SymbolCatalog.pageCount(id);
      if (offset < count) { category = id; page = offset; return; }
      offset -= count;
    }
  }
}
