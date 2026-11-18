package juloo.keyboard2.input;

/** Internal worker-confined engine boundary; no Android views or editor references. */
public interface CompositionEngine extends AutoCloseable
{
  EngineResult processText(String text);
  EngineResult backspace();
  EngineResult space();
  EngineResult confirm();
  /** Preserve explicit selections and raw unselected input, never a candidate guess. */
  default EngineResult releaseToEnglish() { throw new UnsupportedOperationException("English handoff"); }
  EngineResult clear();
  EngineResult select(int pageIndex);
  EngineResult page(boolean backward);
  /** Ranked prefix from the start, without changing the composition. */
  default EngineResult candidates(int limit) { throw new UnsupportedOperationException("Candidate browsing"); }
  void setLearning(boolean enabled);
  /** True when preparation failed and this session uses a verified retained deployment. */
  default boolean usesRetainedData() { return false; }
  @Override void close();
}
