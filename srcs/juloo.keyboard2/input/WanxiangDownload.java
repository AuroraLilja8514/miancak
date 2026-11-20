package juloo.keyboard2.input;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Anonymous downloads from the APK's fixed asset IDs and GitHub's release CDN. */
final class WanxiangDownload
{
  static InputStream open(WanxiangInstall.Artifact artifact, long deadline) throws IOException
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
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Accept", "application/octet-stream");
      connection.setRequestProperty("Accept-Encoding", "identity");
      connection.setRequestProperty("User-Agent", "MIANCAK-resource-setup");
      boolean returningStream = false;
      try
      {
        int code = connection.getResponseCode();
        if (code == 200)
        {
          String type = connection.getContentType();
          if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("json"))
            throw new IOException("Release asset endpoint returned metadata instead of bytes");
          long size = connection.getContentLengthLong();
          if (size != -1 && size != artifact.bytes) throw new IOException("Unexpected resource transfer length");
          InputStream stream = new BoundedStream(connection.getInputStream(), deadline, connection);
          returningStream = true;
          return stream;
        }
        if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308)
          throw new IOException("Pinned release asset unavailable (HTTP " + code + ")");
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

  static void checkDeadline(long deadline) throws IOException
  {
    WanxiangInstall.interrupted();
    if (System.nanoTime() - deadline >= 0) throw new InterruptedIOException("Resource transfer timed out");
  }

  static InputStream bound(InputStream stream, long deadline)
  { return new BoundedStream(stream, deadline, null); }

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

  private WanxiangDownload() {}
}
