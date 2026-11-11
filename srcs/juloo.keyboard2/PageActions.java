package juloo.keyboard2;

import android.content.res.Resources;
import java.util.ArrayList;
import java.util.List;

/** Typed actions used by both accessible previews and the editor picker. */
public final class PageActions
{
  private static final String[] IDS = {"shift", "backspace", "delete", "enter", "space", "fn_page", "layout_picker", "language_switch",
    "switch_text", "switch_numeric", "switch_greekmath", "switch_emoji", "switch_clipboard", "config", "ctrl", "alt", "meta",
    "left", "right", "up", "down", "pinyin_separator", "switch_pinyin", "switch_pinyin_finals", "switch_zhuyin",
    "switch_symbols", "switch_forward", "switch_backward", "cursor_left", "cursor_right", "cursor_up", "cursor_down",
    "home", "end", "page_up", "page_down", "insert", "esc", "tab", "copy", "cut", "paste", "pasteAsPlainText", "selectAll",
    "selection_mode", "undo", "redo", "delete_word", "forward_delete_word", "symbol_previous", "symbol_next"};
  public static final class Action
  {
    public final String id, label;
    public final KeyValue value;
    Action(String id, String label) { this.id = id; this.label = label; value = KeyValue.getKeyByName(id); }
  }
  public static List<Action> list(Resources resources, boolean symbols)
  {
    String[] labels = resources.getStringArray(R.array.page_action_labels);
    if (labels.length != IDS.length) throw new IllegalStateException("Action labels and IDs differ");
    ArrayList<Action> result = new ArrayList<>();
    for (int i = 0; i < IDS.length; i++)
      if (symbols || !IDS[i].startsWith("symbol_")) result.add(new Action(IDS[i], labels[i]));
    for (int i = 1; i <= 12; i++) result.add(new Action("f" + i, "F" + i));
    return result;
  }
  public static boolean same(KeyValue left, KeyValue right)
  {
    if (left == null || right == null || left.getKind() != right.getKind()) return left == right;
    switch (left.getKind())
    {
      case Modifier: return left.getModifier() == right.getModifier();
      case Event: return left.getEvent() == right.getEvent();
      case Editing: return left.getEditing() == right.getEditing();
      case Keyevent: return left.getKeyevent() == right.getKeyevent();
      case Slider: return left.getSlider() == right.getSlider() && left.getSliderRepeat() == right.getSliderRepeat();
      default: return left.equals(right);
    }
  }
  private PageActions() {}
}
