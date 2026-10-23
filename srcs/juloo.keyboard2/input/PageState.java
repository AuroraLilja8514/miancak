package juloo.keyboard2.input;

/** Presentation state never owns the engine's language or modifier state. */
public final class PageState
{
  private String active = "zh";
  public String active() { return active; }
  public static String text(InputRouter.Mode mode) { return mode == InputRouter.Mode.CHINESE ? "zh" : "en"; }
  public void reset(InputRouter.Mode mode) { active = text(mode); }
  public void select(String id) { if (PageConfig.IDS.contains(id)) active = id; }
  public void followMode(InputRouter.Mode mode) { if (PageConfig.isText(active)) reset(mode); }
  public String toggle(String target, InputRouter.Mode mode)
  {
    return active.equals(target) ? text(mode) : target;
  }
  public boolean fnActive(PageConfig config, InputRouter.Mode mode)
  {
    String target = config.fn(mode);
    return !target.isEmpty() && active.equals(target);
  }
}
