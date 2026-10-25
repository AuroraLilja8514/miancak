package juloo.keyboard2.input;

/** A bounded category browser, never a language/layout or Fn state. */
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
  public boolean previous() { return page > 0; }
  public boolean next() { return page + 1 < SymbolCatalog.pageCount(category); }
  public void select(String category)
  { if (SymbolCatalog.contains(category)) { this.category = category; page = 0; } }
  public void turn(int delta)
  { page = Math.max(0, Math.min(page + delta, SymbolCatalog.pageCount(category) - 1)); }
}
