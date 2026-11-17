package juloo.keyboard2;

import java.nio.charset.StandardCharsets;

/** Android-free, best-effort experiment. Never inspects another thread or UI. */
final class ProgressEvidence implements AutoCloseable
{
  static final long INTERVAL_MS = 10000, WINDOW_MS = 840000;
  static final int MAX_RECORDS = 96, MAX_BYTES = 64 * 1024;
  static final ProgressEvidence NONE = new ProgressEvidence(null, null, null, null);
  enum Scope { DRIVER, FN, SYMBOL, PAGE_EDITOR, LIFECYCLE, FIRST_INSTALL }
  enum FailurePhase { FLOW, CLEANUP }
  enum FailureKind { ASSERTION, AUTOMATION, TIMEOUT, IO, INTERRUPTED, OTHER }

  enum Run
  {
    INTERACTIONS("interactions", "main"), RESTART("interactions", "restart"),
    CLEAN_INSTALL("first-install", "clean-install"), REOPEN("first-install", "reopen"),
    REBOOT("first-install", "reboot");
    final String scenario, phase;
    Run(String scenario, String phase) { this.scenario = scenario; this.phase = phase; }
  }

  enum Stage
  {
    START, FN_ENTER, FN_RETURN, SYMBOL_ENTER, SYMBOL_RETURN, PAGE_EDITOR_ENTER, PAGE_EDITOR_RETURN,
    LIFECYCLE_ENTER, LIFECYCLE_RETURN, FINALLY_ENTER, FINALLY_RETURN,
    EVIDENCE_ENTER, EVIDENCE_RETURN, TEARDOWN_ENTER, TEARDOWN_RETURN,
    CATEGORY_ENTER, CATEGORY_RETURN, CATEGORY_LOOKUP_ENTER, CATEGORY_LOOKUP_RETURN,
    CATEGORY_BOUNDS_ENTER, CATEGORY_BOUNDS_RETURN, CATEGORY_SCROLL_LOOKUP_ENTER,
    CATEGORY_SCROLL_LOOKUP_RETURN, SCROLL_ENTER, SCROLL_RETURN, CLICK_ENTER, CLICK_RETURN,
    PAGE_WAIT_ENTER, PAGE_WAIT_RETURN, TAB_WAIT_ENTER, TAB_WAIT_RETURN,
    LAYOUT_WAIT_ENTER, LAYOUT_WAIT_RETURN, KEYBOARD_LOOKUP_ENTER, KEYBOARD_LOOKUP_RETURN,
    BOUNDS_ENTER, BOUNDS_RETURN, TOUCH_ENTER, TOUCH_RETURN,
    INSTRUMENTATION_IDLE_ENTER, INSTRUMENTATION_IDLE_RETURN, UI_IDLE_ENTER, UI_IDLE_RETURN,
    MAIN_SYNC_ENTER, MAIN_SYNC_RETURN, RECEIVED_TEXT_ENTER, RECEIVED_TEXT_RETURN,
    SCREENSHOT_ENTER, SCREENSHOT_RETURN, PNG_ENTER, PNG_RETURN, HIERARCHY_ENTER, HIERARCHY_RETURN,
    WAKE_ENTER, WAKE_RETURN, ORIENTATION_ENTER, ORIENTATION_RETURN,
    ROTATION_ENTER, ROTATION_RETURN, BACK_ENTER, BACK_RETURN,
    SETTINGS_ENTER, SETTINGS_RETURN, SETTINGS_LOOKUP_ENTER, SETTINGS_LOOKUP_RETURN,
    SETTINGS_CLICK_ENTER, SETTINGS_CLICK_RETURN, SETTINGS_WAIT_ENTER, SETTINGS_WAIT_RETURN,
    LAUNCH_ENTER, LAUNCH_RETURN, PICKER_ENTER, PICKER_RETURN, ENABLE_ENTER, ENABLE_RETURN,
    FOCUS_ENTER, FOCUS_RETURN, CANDIDATE_ENTER, CANDIDATE_RETURN, LOOKUP_ENTER, LOOKUP_RETURN,
    POLICY_ENTER, POLICY_RETURN, POLICY_SCROLL_ENTER, POLICY_SCROLL_RETURN,
    POLICY_LOOKUP_ENTER, POLICY_LOOKUP_RETURN, POLICY_CLICK_ENTER, POLICY_CLICK_RETURN,
    POLICY_WAIT_ENTER, POLICY_WAIT_RETURN, FRAME_WAIT_ENTER, FRAME_WAIT_RETURN,
    SCREENSHOT_FILES_ENTER, SCREENSHOT_FILES_RETURN, RECEIVER_WAIT_ENTER, RECEIVER_WAIT_RETURN,
    POLICY_READ_ENTER, POLICY_READ_RETURN, STATE_READ_ENTER, STATE_READ_RETURN
  }

  interface Clock { long now(); void sleep(long ms) throws Exception; }
  interface Cleanup { void run() throws Exception; }
  interface Poster { boolean post(Runnable callback) throws Exception; }
  interface Sink
  {
    void open(Run run) throws Exception;
    void write(byte[] record) throws Exception;
    void close() throws Exception;
  }

  /** Process scope in the Android adapter; no marker or callback takes this lock. */
  static final class Guard
  {
    private ProgressEvidence owner;
    synchronized boolean claim(ProgressEvidence next)
    {
      if (owner != null && (!owner.exitRecorded || owner.posting || owner.unresolved())) return false;
      owner = next;
      return true;
    }
  }

  static final class Snapshot
  {
    final Stage stage;
    final Scope scope;
    final long seq, at;
    final int call, category, page, attempt;
    Snapshot(Stage stage, Scope scope, long seq, long at, int call, int category, int page, int attempt)
    {
      this.stage = stage; this.scope = scope; this.seq = seq; this.at = at; this.call = call;
      this.category = category; this.page = page; this.attempt = attempt;
    }
  }

  private static final class Ack
  {
    final long token, at;
    Ack(long token, long at) { this.token = token; this.at = at; }
  }

  private static final class Failure
  {
    final FailurePhase phase;
    final FailureKind kind;
    final Snapshot boundary;
    final long at;
    Failure(FailurePhase phase, FailureKind kind, Snapshot boundary, long at)
    { this.phase = phase; this.kind = kind; this.boundary = boundary; this.at = at; }
  }

  private final Run run;
  private final Clock clock;
  private final Poster poster;
  private final Sink sink;
  private volatile Snapshot snapshot = new Snapshot(Stage.START, Scope.DRIVER, 0, 0, 0, 0, -1, -1);
  private Scope scope = Scope.DRIVER;
  private volatile Ack ack = new Ack(0, 0);
  private volatile Failure failure;
  private boolean failureAttempted;
  private volatile boolean stop, posting, exitRecorded;
  private volatile long pending;
  private long sequence, token, started, postedToken, callAt, returnedAt;
  private String result = "none";
  private int records, bytes;

  ProgressEvidence(Run run, Clock clock, Poster poster, Sink sink)
  { this.run = run; this.clock = clock; this.poster = poster; this.sink = sink; }

  /** Test-thread path: one immutable, bounded value, no I/O or target inspection. */
  void scope(Scope scope) { this.scope = scope; }
  void mark(Stage stage) { mark(stage, 0, 0, -1, -1); }
  void mark(Stage stage, int call, int category, int page, int attempt)
  {
    try
    {
      if (stage == null || call < 0 || call > 256 || category < 0 || category > 7
          || page < -1 || page > 31 || attempt < -1 || attempt > 31) return;
      snapshot = new Snapshot(stage, scope, ++sequence, clock.now(), call, category, page, attempt);
    }
    catch (Throwable ignored) { /* Evidence must not replace the test's exception. */ }
  }

  /** First attempt wins even if its clock/allocation fails: cleanup is not the primary failure. */
  void failed(FailurePhase phase, FailureKind kind)
  {
    if (failureAttempted) return;
    failureAttempted = true;
    try
    {
      if (phase == null || kind == null) return;
      Snapshot boundary = snapshot;
      failure = new Failure(phase, kind, boundary, clock.now());
    }
    catch (Throwable ignored) { /* Unavailable metadata must not replace the original exception. */ }
  }

  static FailureKind kind(Throwable failure)
  {
    if (failure instanceof AssertionError) return FailureKind.ASSERTION;
    if (failure instanceof java.util.concurrent.TimeoutException) return FailureKind.TIMEOUT;
    if (failure instanceof java.io.IOException) return FailureKind.IO;
    if (failure instanceof InterruptedException) return FailureKind.INTERRUPTED;
    return FailureKind.OTHER;
  }

  /** Preserve the in-flight primary object; a first cleanup failure still fails acceptance. */
  static void cleanup(Throwable primary, Cleanup action) throws Exception
  {
    try { action.run(); }
    catch (Exception | Error later) { if (primary == null) throw later; }
  }

  @Override public void close() { stop = true; }

  private boolean unresolved() { return pending != 0 && ack.token != pending; }

  /** Only the worker calls this. A successful exit record and close permit reuse. */
  void work()
  {
    boolean recorded = false;
    try
    {
      started = clock.now();
      sink.open(run);
      write("{\"schema\":2,\"scenario\":\"" + run.scenario + "\",\"phase\":\"" + run.phase
          + "\",\"clock\":\"elapsedRealtime_ms\",\"started_ms\":" + started + ",\"interval_ms\":10000,\"window_ms\":840000,"
          + "\"meaning\":\"ack_only_proves_ordinary_main_callback_ran;missing_is_ambiguous\"}");
      sample("start", clock.now());
      long next = started + INTERVAL_MS;
      while (!stop && clock.now() - started < WINDOW_MS)
      {
        long now = clock.now();
        if (now < next)
        {
          // Poll stop on the worker only. close() never waits or wakes anything.
          clock.sleep(Math.min(250, next - now));
          continue;
        }
        if (!unresolved())
        {
          pending = ++token; // Reserve before post: callback may run before post returns.
          posting = true;
          sample("reserved_before_post", now); // Flush before any potentially stalled post.
          if (stop || clock.now() - started >= WINDOW_MS)
          { pending = 0; posting = false; result = "cancelled_before_post"; break; }
          final long request = pending;
          postedToken = request;
          callAt = clock.now();
          try
          {
            boolean accepted = poster.post(() -> {
              try { ack = new Ack(request, clock.now()); }
              catch (Throwable ignored) { /* Only the former owner's fields, even if late. */ }
            });
            result = accepted ? "accepted" : "rejected";
            if (!accepted) pending = 0;
          }
          catch (Throwable ignored)
          {
            // A throwing poster might already have enqueued: retain the token.
            result = "threw_outcome_unknown";
          }
          finally { returnedAt = clock.now(); posting = false; }
        }
        else sample("pending", now);
        // No catch-up burst, including after slow writes or slow post returns.
        long after = clock.now();
        next = started + ((after - started) / INTERVAL_MS + 1) * INTERVAL_MS;
      }
      recorded = sample(stop ? "exit_stop" : "exit_deadline", clock.now());
    }
    catch (Throwable ignored) { /* Sink/poster/clock failure never changes acceptance. */ }
    finally
    {
      try { sink.close(); exitRecorded = recorded; }
      catch (Throwable ignored) { /* Refuse replacement if exit/close was not recorded. */ }
    }
  }

  private boolean sample(String event, long now) throws Exception
  {
    Snapshot s = snapshot;
    Ack a = ack;
    Failure f = failure;
    return write("{\"event\":\"" + event + "\",\"at_ms\":" + now + ",\"elapsed_ms\":" + (now - started)
        + ",\"scope\":\"" + s.scope.name() + "\",\"stage\":\"" + s.stage.name() + "\",\"seq\":" + s.seq + ",\"stage_ms\":" + s.at
        + ",\"call\":" + s.call + ",\"category\":" + s.category + ",\"page\":" + s.page
        + ",\"attempt\":" + s.attempt + ",\"request\":" + pending + ",\"ack\":" + a.token
        + ",\"ack_ms\":" + a.at + ",\"previous_post\":\"" + result + "\",\"post_token\":" + postedToken + ",\"post_call_ms\":" + callAt
        + ",\"post_return_ms\":" + returnedAt + ",\"failure\":" + failureJson(f) + "}");
  }

  private String failureJson(Failure f)
  {
    if (f == null) return "{\"status\":\"unavailable\"}";
    Snapshot s = f.boundary;
    return "{\"status\":\"observed\",\"phase\":\"" + f.phase.name() + "\",\"kind\":\"" + f.kind.name()
        + "\",\"observed_ms\":" + f.at + ",\"scope\":\"" + s.scope.name() + "\",\"stage\":\"" + s.stage.name()
        + "\",\"seq\":" + s.seq + ",\"stage_ms\":" + s.at + ",\"call\":" + s.call + ",\"category\":" + s.category
        + ",\"page\":" + s.page + ",\"attempt\":" + s.attempt + "}";
  }

  /** Includes header/newlines. All serialization, writes and flushes are worker-only. */
  private boolean write(String line) throws Exception
  {
    byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
    if (records >= MAX_RECORDS || data.length > MAX_BYTES - bytes) return false;
    records++; bytes += data.length;
    sink.write(data);
    return true;
  }
}
