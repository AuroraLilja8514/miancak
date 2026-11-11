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
            if (hash(in).equals(expected))
              continue;
          }
        File temporary = new File(shared, name + ".tmp");
        boolean installed = false;
        try
        {
          MessageDigest digest = digest();
          try (InputStream in = context.getAssets().open("rime/" + name);
               FileOutputStream out = new FileOutputStream(temporary))
          {
            byte[] buffer = new byte[16384]; int count;
            while ((count = in.read(buffer)) != -1)
            { digest.update(buffer, 0, count); out.write(buffer, 0, count); }
            out.getFD().sync();
          }
          if (!hex(digest.digest()).equals(expected))
            throw new IOException("Packaged Rime asset failed integrity check");
          if (!temporary.renameTo(target))
            throw new IOException("Cannot atomically deploy Rime asset");
          installed = true;
        }
        finally { if (!installed) temporary.delete(); }
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
    return hex(digest().digest(bytes));
  }
  private static String hash(InputStream in) throws IOException
  {
    MessageDigest digest = digest(); byte[] buffer = new byte[16384]; int count;
    while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
    return hex(digest.digest());
  }
  private static MessageDigest digest()
  {
    try
    { return MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
  }
  private static String hex(byte[] bytes)
  {
    StringBuilder result = new StringBuilder(); String hex = "0123456789abcdef";
    for (byte b : bytes) result.append(hex.charAt((b & 255) >>> 4)).append(hex.charAt(b & 15));
    return result.toString();
  }
}
