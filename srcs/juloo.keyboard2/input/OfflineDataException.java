package juloo.keyboard2.input;

import java.io.IOException;

/** Typed UI failure, without exposing paths or user configuration contents. */
public final class OfflineDataException extends IOException
{
  public static final String MESSAGE = "Offline Chinese data could not be prepared. Free storage and tap the language button to retry; retained files are kept.";
  public static final String SETUP_MESSAGE = "Complete Chinese input setup in MIANCAK settings. English and direct typing are available.";
  OfflineDataException(IOException cause) { super(cause instanceof WanxiangInstall.SetupRequired ? SETUP_MESSAGE : MESSAGE, cause); }
}
