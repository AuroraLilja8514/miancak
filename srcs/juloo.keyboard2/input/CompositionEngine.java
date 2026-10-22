package juloo.keyboard2.input;

/** Internal worker-confined engine boundary; no Android views or editor references. */
public interface CompositionEngine extends AutoCloseable
{
  EngineResult processText(String text);
  EngineResult backspace();
  EngineResult space();
  EngineResult confirm();
  EngineResult clear();
  EngineResult select(int pageIndex);
  EngineResult page(boolean backward);
  void setLearning(boolean enabled);
  @Override void close();
}
