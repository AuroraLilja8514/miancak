package juloo.keyboard2;
public final class TestEvidence
{
  public static java.io.File directory(android.content.Context context)
  {
    java.io.File dir = new java.io.File(context.getFilesDir(), "test-results");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot save test evidence");
    return dir;
  }
  private TestEvidence() {}
}
