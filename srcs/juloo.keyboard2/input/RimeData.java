package juloo.keyboard2.input;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned deployment, called during worker initialization, never for ordinary keys. */
public final class RimeData
{
  public static final class Paths
  {
    public final File shared;
    public final File user;
    public final File build;
    Paths(File shared, File user) { this.shared = shared; this.user = user; this.build = new File(shared, "build"); }
  }

  private RimeData() {}

  public static synchronized Paths deploy(Context context) throws IOException
  { return new WordTableStore(context).currentPaths(); }

  public static synchronized Paths bundled(Context context) throws IOException
  {
    if (android.os.Build.VERSION.SDK_INT >= 24 && context.isDeviceProtectedStorage())
      throw new IOException("Rime user data requires credential-protected storage");
    byte[] manifest;
    try (InputStream in = context.getAssets().open("rime/manifest.json"))
    {
      manifest = read(in);
    }
    File root = new File(context.getFilesDir(), "rime");
    File shared = new File(root, "shared-" + hash(manifest));
    File user = new File(root, "user");
    if ((!shared.isDirectory() && !shared.mkdirs()) || (!user.isDirectory() && !user.mkdirs()))
      throw new IOException("Cannot create Rime data directories");
    try
    {
      JSONObject files = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
      Iterator<String> names = files.keys();
      while (names.hasNext())
      {
        String name = names.next();
        if (name.contains("/") || name.contains("\\") || name.equals(".."))
          throw new IOException("Invalid packaged Rime asset name");
        File target = new File(shared, name);
        String expected = files.getString(name);
        if (target.isFile())
          try (InputStream in = new java.io.FileInputStream(target))
          {
            if (hash(read(in)).equals(expected))
              continue;
          }
        byte[] content;
        try (InputStream in = context.getAssets().open("rime/" + name))
        {
          content = read(in);
        }
        if (!hash(content).equals(expected))
          throw new IOException("Packaged Rime asset failed integrity check");
        File temporary = new File(shared, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary))
        {
          out.write(content);
          out.getFD().sync();
        }
        if (!temporary.renameTo(target))
          throw new IOException("Cannot atomically deploy Rime asset");
      }
    }
    catch (JSONException ex)
    {
      throw new IOException("Invalid Rime asset manifest", ex);
    }
    return new Paths(shared, user);
  }

  private static byte[] read(InputStream in) throws IOException
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[16384];
    int n;
    while ((n = in.read(buffer)) != -1)
      out.write(buffer, 0, n);
    return out.toByteArray();
  }

  private static String hash(byte[] bytes)
  {
    try
    {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder();
      for (byte b : digest)
        result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return result.toString();
    }
    catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
  }
}
