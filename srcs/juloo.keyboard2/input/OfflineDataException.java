package juloo.keyboard2.input;

import java.io.IOException;

/** Typed UI failure, without exposing paths or user configuration contents. */
public final class OfflineDataException extends IOException
{
  public static final String MESSAGE = "Offline Chinese data could not be prepared. Free storage and tap the language button to retry; retained files are kept.";
  OfflineDataException(IOException cause) { super(MESSAGE, cause); }
}
