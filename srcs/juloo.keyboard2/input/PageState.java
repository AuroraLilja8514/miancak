package juloo.keyboard2.input;

/** Sole UI layout state; the engine receives this layout's derived input policy. */
public final class PageState
{
  private String active = "zh";
  public String active() { return active; }
  public static String text(InputRouter.Mode mode) { return mode == InputRouter.Mode.CHINESE ? "zh" : "en"; }
  public void reset(InputRouter.Mode mode) { active = text(mode); }
  public void select(String id) { if (PageConfig.IDS.contains(id)) active = id; }
  public InputRouter.Mode mode() { return PageConfig.mode(active); }
  public boolean fnActive() { return PageConfig.isVariant(active); }
}
