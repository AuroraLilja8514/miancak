package juloo.keyboard2.input;

/** Candidate taps carry this exact generation/revision, never a bare index. */
public final class InputSnapshot
{
  public final long generation;
  public final long revision;
  public final InputRouter.Mode mode;
  public final boolean engineAllowed;
  public final boolean initializing;
  public final EngineResult engine;
  public final String error;

  public InputSnapshot(long generation, long revision, InputRouter.Mode mode,
      boolean engineAllowed, EngineResult engine, String error)
  { this(generation, revision, mode, engineAllowed, engine, error, false); }

  public InputSnapshot(long generation, long revision, InputRouter.Mode mode,
      boolean engineAllowed, EngineResult engine, String error, boolean initializing)
  {
    this.generation = generation;
    this.revision = revision;
    this.mode = mode;
    this.engineAllowed = engineAllowed;
    this.initializing = initializing;
    this.engine = engine;
    this.error = error;
  }
}
