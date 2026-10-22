package juloo.keyboard2.input;

/** Persistent JNI session. Every method, including close, runs on its creating worker. */
public final class RimeEngine implements CompositionEngine
{
  static { System.loadLibrary("miancak_rime"); }
  private final long owner = Thread.currentThread().getId();
  private long session;

  public RimeEngine(RimeData.Paths paths)
  {
    session = open(paths.shared.getAbsolutePath(), paths.user.getAbsolutePath());
    if (session == 0)
      throw new IllegalStateException("Cannot initialize offline Chinese engine");
  }

  private void check()
  {
    if (owner != Thread.currentThread().getId())
      throw new IllegalStateException("Rime must remain on its serialized worker");
    if (session == 0)
      throw new IllegalStateException("Rime session is closed");
  }

  @Override public EngineResult processText(String text)
  {
    check();
    EngineResult result = EngineResult.EMPTY;
    StringBuilder committed = new StringBuilder();
    for (int i = 0; i < text.length();)
    {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      result = key(session, cp < 256 ? cp : cp | 0x01000000);
      committed.append(result.committed);
      if (!result.handled)
        throw new IllegalStateException("Engine rejected explicitly routed text");
    }
    return result.withCommitted(committed.toString());
  }

  @Override public EngineResult backspace() { check(); return key(session, 0xff08); }
  @Override public EngineResult space() { check(); return key(session, 0x20); }
  @Override public EngineResult confirm() { check(); return command(session, 0, 0); }
  @Override public EngineResult clear() { check(); return command(session, 1, 0); }
  @Override public EngineResult select(int pageIndex) { check(); return command(session, 2, pageIndex); }
  @Override public EngineResult page(boolean backward) { check(); return command(session, 3, backward ? 1 : 0); }
  @Override public void setLearning(boolean enabled) { check(); learning(session, enabled); }
  @Override public void close()
  {
    if (session == 0) return;
    check();
    destroy(session);
    session = 0;
  }

  private static native long open(String shared, String user);
  private static native EngineResult key(long session, int code);
  private static native EngineResult command(long session, int command, int argument);
  private static native void learning(long session, boolean enabled);
  private static native void destroy(long session);
}
