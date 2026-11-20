package juloo.keyboard2.input;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Explicit setup owns networking; no engine or typing entry point calls it. */
public final class WanxiangSetup
{
  public enum Phase { CHECKING, NEEDED, COPYING, UNPACKING, STAGED, STOPPING, VALIDATING, READY, CANCELLED,
                      UNAVAILABLE, INTEGRITY, SPACE, FAILED }
  public static final class Status
  {
    public final Phase phase;
    public final String version;
    public final long bytes, transferred;
    Status(Phase phase, String version, long bytes, long transferred)
    { this.phase = phase; this.version = version; this.bytes = bytes; this.transferred = transferred; }
    public boolean busy()
    {
      return phase == Phase.CHECKING || phase == Phase.COPYING || phase == Phase.UNPACKING
          || phase == Phase.STAGED || phase == Phase.STOPPING || phase == Phase.VALIDATING;
    }
  }

  private static final Handler UI = new Handler(Looper.getMainLooper());
  private static final List<Runnable> listeners = new ArrayList<>();
  private static Status status = new Status(Phase.NEEDED, "", 0, 0);
  private static Thread transfer;
  private static Context foreground;
  private static WanxiangInstall.Staged awaitingActivation;

  public static Status status() { return status; }
  public static void observe(Runnable listener) { listeners.add(listener); listener.run(); }
  public static void forget(Runnable listener) { listeners.remove(listener); }
  public static void resume(Context activity)
  { foreground = activity; activateIfVisible(); }
  public static void pause(Context activity)
  { if (foreground == activity) foreground = null; }

  public static void check(Context context)
  {
    if (status.busy() || status.bytes != 0) return;
    Context app = context.getApplicationContext();
    update(Phase.CHECKING, 0);
    worker(() -> {
      try
      {
        WanxiangInstall install = WanxiangInstall.forApp(app);
        boolean ready = install.installed();
        UI.post(() -> publish(new Status(ready ? Phase.READY : Phase.NEEDED, install.version, install.transferBytes, 0)));
      }
      catch (IOException invalid) { UI.post(() -> update(Phase.FAILED, 0)); }
    });
  }

  public static void download(Context context)
  {
    if (status.busy() || status.phase == Phase.READY) return;
    android.net.ConnectivityManager connectivity = context.getSystemService(android.net.ConnectivityManager.class);
    if (connectivity != null && connectivity.getActiveNetwork() == null)
    { update(Phase.UNAVAILABLE, 0); return; }
    begin(context, null, null);
  }
  public static void installFiles(Context context, Uri base, Uri model) { begin(context, base, model); }

  private static void begin(Context context, Uri base, Uri model)
  {
    if (status.busy() || status.phase == Phase.READY) return;
    Context app = context.getApplicationContext();
    update(Phase.COPYING, 0);
    transfer = worker(() -> {
      try
      {
        WanxiangInstall install = WanxiangInstall.forApp(app);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        WanxiangInstall.Source source = base == null
            ? artifact -> openPinned(artifact, deadline)
            : artifact -> localFile(app, artifact == install.base ? base : model, deadline);
        int[] previousPercent = {-1};
        WanxiangInstall.Staged staged = install.prepare(source, name -> app.getAssets().open("rime/" + name), bytes -> {
          int percent = (int)(100 * bytes / install.transferBytes);
          if (percent != previousPercent[0])
          {
            previousPercent[0] = percent;
            UI.post(() -> { if (status.phase == Phase.COPYING) publish(new Status(Phase.COPYING, install.version, install.transferBytes, bytes)); });
          }
        }, () -> UI.post(() -> { if (status.phase == Phase.COPYING) update(Phase.UNPACKING, status.bytes); }));
        UI.post(() -> finishTransfer(staged));
      }
      catch (IOException invalid)
      {
        Phase phase = phaseFor(invalid, base == null);
        UI.post(() -> { transfer = null; update(status.phase == Phase.STOPPING ? Phase.CANCELLED : phase, 0); });
      }
      catch (RuntimeException invalid)
      { UI.post(() -> { transfer = null; update(Phase.FAILED, 0); }); }
    });
  }

  private static Phase phaseFor(IOException failure, boolean network)
  {
    if (failure instanceof WanxiangInstall.InsufficientSpace) return Phase.SPACE;
    if (failure instanceof WanxiangInstall.IntegrityFailure || failure instanceof java.util.zip.ZipException) return Phase.INTEGRITY;
    if (Thread.currentThread().isInterrupted()) return Phase.CANCELLED;
    return network ? Phase.UNAVAILABLE : Phase.FAILED;
  }

  public static void cancel()
  {
    if (transfer != null && (status.phase == Phase.COPYING || status.phase == Phase.UNPACKING))
    { update(Phase.STOPPING, status.transferred); transfer.interrupt(); }
    else if (status.phase == Phase.STAGED)
    {
      WanxiangInstall.Staged staged = awaitingActivation; awaitingActivation = null;
      update(Phase.STOPPING, status.bytes); discard(staged, Phase.CANCELLED);
    }
  }

  private static void finishTransfer(WanxiangInstall.Staged staged)
  {
    transfer = null;
    if (status.phase == Phase.STOPPING) { discard(staged, Phase.CANCELLED); return; }
    if (staged == null) { update(Phase.READY, status.bytes); return; }
    awaitingActivation = staged;
    update(Phase.STAGED, status.bytes);
    activateIfVisible();
  }

  /** Do not close the IME in another app just because a background transfer finished. */
  private static void activateIfVisible()
  {
    if (foreground == null || awaitingActivation == null) return;
    Context app = foreground.getApplicationContext();
    WanxiangInstall.Staged staged = awaitingActivation; awaitingActivation = null;
    update(Phase.VALIDATING, status.bytes);
    WordTableRuntime.activateResources(app, () -> staged.activate(WanxiangInstall::verifyNative),
        failure -> discard(staged, failure.isEmpty() ? Phase.READY : Phase.FAILED));
  }

  private static void discard(WanxiangInstall.Staged staged, Phase next)
  {
    if (staged == null) { update(next, status.bytes); return; }
    worker(() -> {
      try { staged.close(); }
      catch (IOException failure) { android.util.Log.w("MIANCAK-Rime", "Resource staging cleanup deferred"); }
      UI.post(() -> update(next, next == Phase.READY ? status.bytes : 0));
    });
  }

  private static Thread worker(Runnable operation)
  { Thread thread = new Thread(operation, "MIANCAK-ResourceSetup"); thread.start(); return thread; }

  private static void update(Phase phase, long transferred)
  { publish(new Status(phase, status.version, status.bytes, transferred)); }

  private static void publish(Status next)
  { status = next; for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }

  private static InputStream localFile(Context context, Uri uri, long deadline) throws IOException
  {
    checkDeadline(deadline);
    if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("File must be selected through Android documents");
    InputStream stream = context.getContentResolver().openInputStream(uri);
    if (stream == null) throw new IOException("Cannot open selected resource");
    return new BoundedStream(stream, deadline, null);
  }

  private static InputStream openPinned(WanxiangInstall.Artifact artifact, long deadline) throws IOException
  {
    URL url = new URL(artifact.url);
    for (int redirects = 0; redirects <= 3; redirects++)
    {
      checkDeadline(deadline);
      HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
      connection.setConnectTimeout(15_000);
      connection.setReadTimeout(30_000);
      connection.setUseCaches(false);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Accept", "application/octet-stream");
      connection.setRequestProperty("User-Agent", "MIANCAK-resource-setup");
      boolean returningStream = false;
      try
      {
        int code = connection.getResponseCode();
        if (code == 200)
        {
          long size = connection.getContentLengthLong();
          if (size != -1 && size != artifact.bytes) throw new IOException("Unexpected resource transfer length");
          InputStream stream = new BoundedStream(connection.getInputStream(), deadline, connection);
          returningStream = true;
          return stream;
        }
        if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308)
          throw new IOException("Pinned release asset unavailable");
        String location = connection.getHeaderField("Location");
        if (location == null) throw new IOException("Missing asset redirect");
        url = new URL(url, location);
        String host = url.getHost();
        if (!url.getProtocol().equals("https") || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
            || (!host.equals("release-assets.githubusercontent.com") && !host.equals("objects.githubusercontent.com")))
          throw new IOException("Unexpected asset redirect");
      }
      finally { if (!returningStream) connection.disconnect(); }
    }
    throw new IOException("Too many asset redirects");
  }

  private static void checkDeadline(long deadline) throws IOException
  {
    WanxiangInstall.interrupted();
    if (System.nanoTime() - deadline >= 0) throw new InterruptedIOException("Resource transfer timed out");
  }

  private static final class BoundedStream extends FilterInputStream
  {
    private final long deadline;
    private final HttpsURLConnection connection;
    BoundedStream(InputStream stream, long deadline, HttpsURLConnection connection)
    { super(stream); this.deadline = deadline; this.connection = connection; }
    @Override public int read(byte[] bytes, int offset, int count) throws IOException
    { checkDeadline(deadline); int length = super.read(bytes, offset, count); checkDeadline(deadline); return length; }
    @Override public int read() throws IOException
    { checkDeadline(deadline); int value = super.read(); checkDeadline(deadline); return value; }
    @Override public void close() throws IOException
    { try { super.close(); } finally { if (connection != null) connection.disconnect(); } }
  }

  private WanxiangSetup() {}
}
