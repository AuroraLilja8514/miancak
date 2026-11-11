package juloo.keyboard2;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Test fixture is present only in debug APKs; it is not exported. */
public final class ImeTestActivity extends Activity
{
  public static final class RecordingEditText extends EditText
  {
    public final java.util.List<Integer> menuActions = new java.util.concurrent.CopyOnWriteArrayList<>();
    public RecordingEditText(android.content.Context context) { super(context); }
    @Override public boolean onTextContextMenuItem(int id)
    { menuActions.add(id); return super.onTextContextMenuItem(id); }
  }
  public EditText first, second, password, number, privateField;
  public WebView web;
  public volatile boolean webReady;
  @Override public void onCreate(Bundle saved)
  {
    super.onCreate(saved);
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(12 * getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding * 2, padding, padding);
    scroll.addView(root);
    first = editor(root, R.id.test_first_editor, "first_editor", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    first.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    second = editor(root, R.id.test_second_editor, "second_editor", InputType.TYPE_CLASS_TEXT);
    password = editor(root, R.id.test_password_editor, "password_editor", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    number = editor(root, R.id.test_number_editor, "number_editor", InputType.TYPE_CLASS_NUMBER);
    privateField = editor(root, R.id.test_private_editor, "private_editor", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    privateField.setImeOptions(0x1000000 | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    Button webButton = new Button(this);
    webButton.setText("Web editor");
    webButton.setContentDescription("open_web_editor");
    root.addView(webButton);
    webButton.setOnClickListener(view -> openWebEditor());
    setContentView(scroll);
  }
  public void openWebEditor()
  {
      webReady = false;
      web = new WebView(this);
      web.getSettings().setJavaScriptEnabled(true);
      web.setWebViewClient(new WebViewClient() {
        @Override public void onPageFinished(WebView view, String url) { webReady = true; }
      });
      setContentView(web);
      web.loadDataWithBaseURL("https://miancak.invalid/", "<meta name='viewport' content='width=device-width,initial-scale=1'><div style='padding-top:48px'><textarea id='t' aria-label='web_editor' style='font-size:24px;width:90%;height:120px'></textarea></div>", "text/html", "UTF-8", null);
  }
  private EditText editor(LinearLayout root, int id, String name, int type)
  {
    EditText edit = new RecordingEditText(this);
    edit.setId(id);
    edit.setContentDescription(name);
    edit.setHint(name);
    edit.setInputType(type);
    edit.setTextSize(18);
    edit.setMinLines(1);
    root.addView(edit, new LinearLayout.LayoutParams(-1, Math.round(48 * getResources().getDisplayMetrics().density)));
    return edit;
  }
}
