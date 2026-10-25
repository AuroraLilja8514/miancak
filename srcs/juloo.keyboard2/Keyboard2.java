package juloo.keyboard2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.util.LogPrinter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.dict.DictionariesActivity;
import juloo.keyboard2.dict.DictionarySwitcher;
import juloo.keyboard2.prefs.LayoutsPreference;
import juloo.keyboard2.suggestions.CandidatesView;
import juloo.keyboard2.suggestions.Suggestions;
import juloo.keyboard2.input.*;
import java.util.concurrent.ExecutorService;

public class Keyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  /** The view containing the keyboard and candidates view. */
  private ViewGroup _keyboard_container_view;
  private View _activeInputView;
  private Keyboard2View _keyboard_layout_view;
  private CandidatesView _candidates_view;
  private Suggestions _suggestions;
  private KeyEventHandler _keyeventhandler;
  /** If not 'null', the layout to use instead of [_config.current_layout]. */
  private KeyboardData _currentSpecialLayout;
  /** Layout associated with the currently selected locale. Not 'null'. */
  private KeyboardData _localeTextLayout;
  /** Installed and current locales. */
  private Dictionaries _dictionaries;
  private ViewGroup _emojiPane = null;
  private ViewGroup _clipboard_pane = null;
  private Handler _handler;

  private Config _config;
  private InputController _inputController;
  private ExecutorService _rimeWorker;
  private RimeCandidatesView _rimeCandidates;
  private final EditorSelectionTracker _editorSelection = new EditorSelectionTracker();
  private EditorInfo _inputEditorInfo;
  private boolean _editorAttached;
  private boolean _editorAllowsEngine;
  private String _preferredLayout = "zh";
  private final PageState _page = new PageState();
  private PageConfig _pages;
  private FrameLayout _surface;
  private View _pageChooser;
  private long _pageRequest;
  private boolean _shiftLock;
  private PageKeyOverrides _keyOverrides;

  private FoldStateTracker _foldStateTracker;

  /** Layout currently visible before it has been modified. */
  KeyboardData current_layout_unmodified()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData layout = null;
    int layout_i = _config.get_current_layout();
    if (layout_i >= _config.layouts.size())
      layout_i = 0;
    if (layout_i < _config.layouts.size())
      layout = _config.layouts.get(layout_i);
    if (layout == null)
      layout = _localeTextLayout;
    return layout;
  }

  /** Layout currently visible. */
  KeyboardData current_layout()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData data = PageLayouts.complete(getResources(), current_layout_unmodified());
    data = LayoutControls.normalize(data);
    if (PageConfig.isVariant(_page.active())) data = FnLayout.materialize(data, _page.active().equals("zh_fn"));
    if (_keyOverrides != null) data = _keyOverrides.apply(_page.active(), data);
    return LayoutModifier.modify_layout(data, PageConfig.isVariant(_page.active()));
  }

  void setTextLayout(int l)
  {
    _config.set_current_layout(l);
    _currentSpecialLayout = null;
    // The active dictionary depends on the current layout.
    refresh_current_dictionary();
    refresh_candidates_view();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    _keyboard_layout_view.setKeyboard(l);
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  /** Load a layout that contains a numpad. */
  KeyboardData loadNumpad(int layout_id)
  {
    return LayoutModifier.modify_numpad(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  KeyboardData loadNumericLayout()
  {
    return _keyOverrides.apply("numeric", PageLayouts.numeric(getResources(), false, _localeTextLayout));
  }

  KeyboardData loadPinentry(int layout_id)
  {
    return LayoutModifier.modify_pinentry(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    MiancakPreferences.initialize(prefs);
    _handler = new Handler(getMainLooper());
    _foldStateTracker = new FoldStateTracker(this);
    _dictionaries = Dictionaries.instance(this);
    Config.initGlobalConfig(prefs, getResources(),
        _foldStateTracker.isUnfolded(), _dictionaries);
    _config = Config.globalConfig();
    Receiver recvr = this.new Receiver();
    _suggestions = new Suggestions(recvr, _config);
    _keyeventhandler = new KeyEventHandler(_config, recvr, _suggestions);
    _preferredLayout = prefs.getString("pages_last_text", prefs.getBoolean("miancak_chinese", true) ? "zh" : "en");
    if (!PageConfig.isText(_preferredLayout)) _preferredLayout = "zh";
    _rimeWorker = WordTableRuntime.WORKER;
    _inputController = new InputController(_rimeWorker, runnable -> _handler.post(runnable),
        () -> WordTableRuntime.createEngine(Keyboard2.this), this::input_snapshot_changed);
    WordTableRuntime.register(_inputController);
    _keyeventhandler.set_semantic_adapter(new SemanticKeyAdapter(_inputController, this::sync_editor_selection));
    KeyValue.Stateful._handler = recvr;
    _config.handler = _keyeventhandler;
    prefs.registerOnSharedPreferenceChangeListener(this);
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    refreshSubtypeImm();
    create_keyboard_view();
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
  }

  @Override
  public void onDestroy() {
    if (_inputController != null) _inputController.close();
    WordTableRuntime.unregister(_inputController);
    DirectBootAwarePreferences.get_shared_preferences(this).unregisterOnSharedPreferenceChangeListener(this);
    super.onDestroy();
    _foldStateTracker.close();
  }

  private void create_keyboard_view()
  {
    _keyboard_container_view = (ViewGroup)inflate_view(R.layout.keyboard);
    _keyboard_layout_view = (Keyboard2View)_keyboard_container_view.findViewById(R.id.keyboard_view);
    _candidates_view = (CandidatesView)_keyboard_container_view.findViewById(R.id.candidates_view);
    _rimeCandidates = (RimeCandidatesView)_keyboard_container_view.findViewById(R.id.miancak_candidates);
    _rimeCandidates.attachSurface((FrameLayout)_keyboard_container_view.findViewById(R.id.miancak_keyboard_surface), _keyboard_layout_view);
    _surface = (FrameLayout)_keyboard_container_view.findViewById(R.id.miancak_keyboard_surface);
    _pages = PagePreferences.load(DirectBootAwarePreferences.get_shared_preferences(this));
    _keyOverrides = PageKeyOverrides.load(DirectBootAwarePreferences.get_shared_preferences(this));
    _rimeCandidates.setLanguageAction(this::toggle_language_page);
    _rimeCandidates.setPageAction(this::show_page_chooser);
    _rimeCandidates.setExpansionListener(this::page_visibility);
    if (_inputController != null) _rimeCandidates.attach(_inputController);
  }

  InputMethodManager get_imm()
  {
    return (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
  }

  private void refreshSubtypeImm()
  {
    _config.shouldOfferVoiceTyping = true;
    KeyboardData default_layout = null;
    _config.device_locales = DeviceLocales.load(this);
    if (_config.device_locales.default_ != null)
    {
      String layout_name = _config.device_locales.default_.default_layout;
      if (layout_name != null)
        default_layout = LayoutsPreference.layout_of_string(getResources(), layout_name);
    }
    _config.extra_keys_subtype = _config.device_locales.extra_keys();
    if (default_layout == null)
      default_layout = loadLayout(R.xml.miancak_qwerty);
    _localeTextLayout = default_layout;
  }

  private void refresh_current_dictionary()
  {
    _config.should_show_dictionary_switch =
      (_config.device_locales.installed.size() > 0);
    String dict_name = _dictionaries.get_selected(_config);
    if (dict_name == null)
      dict_name = (_config.device_locales.default_ != null) ?
        _config.device_locales.default_.dictionary : null;
    _dictionaries.set_current_dictionary(_config, dict_name);
  }

  /** Remember and apply the dictionary chosen by the user for the current
      context. */
  private void select_dictionary(String dict_name)
  {
    _dictionaries.set_selected(_config, dict_name);
    refresh_current_dictionary();
    refresh_candidates_view();
  }

  private void refresh_candidates_view()
  {
    boolean should_show =
      _inputController == null && _config.suggestions_enabled
      && _config.editor_config.should_show_candidates_view
      && !_config.split_layout;
    if (should_show)
    {
      _candidates_view.refresh_config(_config);
      _keyeventhandler.dictionary_changed();
    }
    _candidates_view.setVisibility(should_show ? View.VISIBLE : View.GONE);
  }

  /** Might re-create the keyboard view. [_keyboard_layout_view.setKeyboard()] and
      [setInputView()] must be called soon after. */
  private void refresh_config()
  {
    int prev_theme = _config.theme;
    _config.refresh(getResources(), _foldStateTracker.isUnfolded(), _dictionaries);
    refresh_current_dictionary();
    // Refreshing the theme config requires re-creating the views
    if (prev_theme != _config.theme)
    {
      create_keyboard_view();
      _emojiPane = null;
      _clipboard_pane = null;
      setInputView(_keyboard_container_view);
    }
    // Set keyboard background opacity
    Drawable bg = _keyboard_container_view.getBackground().mutate();
    bg.setAlpha(_config.keyboardOpacity);
    _keyboard_container_view.setBackground(bg);
    _keyboard_layout_view.reset();
    refresh_candidates_view();
  }

  private KeyboardData refresh_special_layout()
  {
    if (_config.editor_config.numeric_layout)
    {
      switch (_config.selected_number_layout)
      {
        case PIN:
          return loadPinentry(_config.orientation_landscape ?
              R.xml.pin_landscape : R.xml.pin);
        case NUMBER:
          return loadNumericLayout();
      }
    }
    return null;
  }

  @Override
  public void onStartInput(EditorInfo info, boolean restarting)
  {
    super.onStartInput(info, restarting);
    _inputEditorInfo = info;
    _editorAttached = false;
    _editorSelection.reset(info.initialSelStart, info.initialSelEnd);
    attach_input_editor();
  }

  private void attach_input_editor()
  {
    if (_editorAttached || _inputEditorInfo == null || _inputController == null) return;
    final InputConnection connection = getCurrentInputConnection();
    if (connection == null) return;
    EditorPolicy policy = EditorPolicy.from(_inputEditorInfo, this);
    _editorAllowsEngine = policy.engineAllowed;
    _editorAttached = true;
    _page.select(policy.engineAllowed ? _preferredLayout : "en");
    _inputController.startEditor(transaction -> apply_input_transaction(connection, transaction),
        _page.mode(), policy.engineAllowed, policy.learning);
  }

  private boolean apply_input_transaction(InputConnection connection, EditorTransaction transaction)
  {
    if (transaction.edits.isEmpty()) return true;
    connection.beginBatchEdit();
    boolean success = true;
    try
    {
      for (EditorTransaction.Edit edit : transaction.edits)
      {
        if (edit.backspace)
        {
          CharSequence before = connection.getTextBeforeCursor(2, 0);
          int units = before != null && before.length() >= 2
            && Character.isSurrogatePair(before.charAt(before.length() - 2), before.charAt(before.length() - 1)) ? 2 : 1;
          _editorSelection.backspace(units);
          success = connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            && connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
        }
        else
        {
          _editorSelection.commit(edit.text);
          success = connection.commitText(edit.text, 1);
        }
        if (!success) break;
      }
    }
    finally { connection.endBatchEdit(); }
    return success;
  }

  private void input_snapshot_changed(InputSnapshot snapshot)
  {
    if (_rimeCandidates != null) _rimeCandidates.show(snapshot);
    // A failed engine initialization may fall back to English. Reflect that in
    // the actual layout rather than retaining a misleading Chinese layout.
    if (!snapshot.error.isEmpty() && snapshot.mode != _page.mode())
    { _page.select("en"); if (_keyboard_layout_view != null) apply_page(); }
    if (_keyboard_layout_view != null) page_indicators();
  }

  private void sync_editor_selection()
  {
    InputConnection connection = getCurrentInputConnection();
    if (connection == null) return;
    android.view.inputmethod.ExtractedTextRequest request = new android.view.inputmethod.ExtractedTextRequest();
    request.hintMaxChars = 0;
    android.view.inputmethod.ExtractedText text = connection.getExtractedText(request, 0);
    if (text != null)
      _editorSelection.expectSelection(text.startOffset + text.selectionStart, text.startOffset + text.selectionEnd);
  }

  @Override
  public void onFinishInput()
  {
    if (_inputController != null) _inputController.finishEditor();
    _inputEditorInfo = null;
    _editorAttached = false;
    super.onFinishInput();
  }

  @Override
  public void onStartInputView(EditorInfo info, boolean restarting)
  {
    if (_rimeCandidates != null) _rimeCandidates.collapse();
    attach_input_editor();
    _config.editor_config.refresh(info, getResources());
    refresh_config();
    _pageRequest++;
    close_page_chooser();
    _shiftLock = false;
    _currentSpecialLayout = refresh_special_layout();
    if (_currentSpecialLayout != null) _page.select("numeric");
    _keyboard_layout_view.setKeyboard(current_layout());
    page_visibility(); page_indicators();
    _keyeventhandler.started();
    setInputView(_keyboard_container_view);
    Logs.debug_startup_input_view(info, _config);
  }

  @Override
  public void setExtractViewShown(boolean shown){
      super.setExtractViewShown(false);
  }

  /** Report the actual keyboard top. The full-height IME host frame is not
   * the occupied content area, especially with Android 15 edge-to-edge. */
  @Override public void onComputeInsets(android.inputmethodservice.InputMethodService.Insets insets)
  {
    super.onComputeInsets(insets);
    if (_activeInputView != null && _activeInputView.getHeight() > 0 && isInputViewShown())
    {
      int[] location = new int[2]; _activeInputView.getLocationInWindow(location);
      insets.contentTopInsets = location[1];
      insets.visibleTopInsets = location[1];
      insets.touchableInsets = android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT;
    }
  }

  @Override
  public void setInputView(View v)
  {
    _activeInputView = v;
    ViewParent parent = v.getParent();
    if (parent != null && parent instanceof ViewGroup)
      ((ViewGroup)parent).removeView(v);
    super.setInputView(v);
    updateSoftInputWindowLayoutParams();
    v.requestApplyInsets();
  }

  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParams();
  }

  private void updateSoftInputWindowLayoutParams() {
    final Window window = getWindow().getWindow();
    // On API >= 35, Keyboard2View behaves as edge-to-edge
    // APIs 30 to 34 have visual artifact when edge-to-edge is enabled
    if (VERSION.SDK_INT >= 35)
    {
      WindowManager.LayoutParams wattrs = window.getAttributes();
      wattrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      // Allow to draw behind system bars
      wattrs.setFitInsetsTypes(0);
      window.setDecorFitsSystemWindows(false);
    }
    updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
    final View inputArea = window.findViewById(android.R.id.inputArea);

    updateLayoutHeightOf(
            (View) inputArea.getParent(),
            isFullscreenMode()
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT);
    updateLayoutGravityOf((View) inputArea.getParent(), Gravity.BOTTOM);

  }

  private static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    refresh_current_dictionary();
    refresh_candidates_view();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd)
  {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    _keyeventhandler.selection_updated(oldSelStart, newSelStart, newSelEnd);
    if ((oldSelStart != newSelStart || oldSelEnd != newSelEnd)
        && !_editorSelection.acknowledge(newSelStart, newSelEnd)
        && _inputController != null
        && (_inputController.snapshot().engine.composing || _inputController.hasPendingRequests()))
      _inputController.selectionChanged();
    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd))
      _keyboard_layout_view.set_selection_state(newSelStart != newSelEnd);
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    if (_rimeCandidates != null) _rimeCandidates.collapse();
    if (_inputController != null && !finishingInput) _inputController.cancelComposition();
    super.onFinishInputView(finishingInput);
    _keyboard_layout_view.reset();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    if (_key.equals("pages_last_text"))
    {
      String id = _prefs.getString("pages_last_text", "zh");
      if (PageConfig.isText(id)) _preferredLayout = id;
      return;
    }
    if (_key.equals("miancak_chinese")) return;
    refresh_config();
    _pages = PagePreferences.load(_prefs);
    _keyOverrides = PageKeyOverrides.load(_prefs);
    if (!_pages.enabled.contains(_page.active())) { select_page("zh"); return; }
    apply_page();
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    /* Entirely disable fullscreen mode. */
    return false;
  }

  @Override public boolean onKeyDown(int code, KeyEvent event)
  {
    if (code == KeyEvent.KEYCODE_BACK && _pageChooser != null)
    { close_page_chooser(); return true; }
    if (code == KeyEvent.KEYCODE_BACK && _rimeCandidates != null && _rimeCandidates.isExpanded())
    {
      _rimeCandidates.collapse();
      return true;
    }
    if (code == KeyEvent.KEYCODE_BACK && (!_page.active().equals("zh") && !_page.active().equals("en")) && !_config.editor_config.numeric_layout)
    { select_page(PageConfig.returnTarget(_page.active())); return true; }
    return super.onKeyDown(code, event);
  }

  @Override
  public boolean onEvaluateInputViewShown()
  {
    // Since Android 16, this method returns [false] for unknown reasons.
    if (super.onEvaluateInputViewShown())
      return true;
    if (getResources().getConfiguration().hardKeyboardHidden
        == Configuration.HARDKEYBOARDHIDDEN_NO
        && _config.physical_keyboard_hide)
    {
      Logs.debug("Physical keyboard is present");
      return false;
    }
    return true;
  }

  public void launch_dictionaries_activity()
  {
    start_activity(DictionariesActivity.class);
  }

  /** Called from [onClick] attributes. */
  public void launch_dictionaries_activity(View v)
  {
    launch_dictionaries_activity();
  }

  void start_activity(Class cls)
  {
    Intent intent = new Intent(this, cls);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  private void toggle_language_page()
  {
    select_page(PageConfig.languageTarget(_page.active()));
  }

  private void toggle_page(String target)
  {
    select_page(_page.active().equals(target) ? PageConfig.returnTarget(target) : target);
  }

  private void cycle_page(int delta)
  {
    List<String> ids = _pages.visible();
    int current = ids.indexOf(_page.active());
    for (int step = 1; step <= ids.size(); step++)
    {
      String id = ids.get((current + delta * step + ids.size() * 2) % ids.size());
      if (PageConfig.mode(id) != InputRouter.Mode.CHINESE || _editorAllowsEngine) { select_page(id); return; }
    }
  }

  private void select_page(String id)
  {
    if (id.equals("zh") && !_editorAllowsEngine) id = "en";
    if (!_pages.enabled.contains(id) || PageConfig.mode(id) == InputRouter.Mode.CHINESE && !_editorAllowsEngine) return;
    final String target = id;
    final long request = ++_pageRequest;
    boolean accepted = _inputController.setLanguage(PageConfig.mode(target), () -> {
        if (request != _pageRequest) return;
        if (!_pages.enabled.contains(target)) { select_page("zh"); return; }
        boolean sameGeometry = PageConfig.isText(_page.active());
        _page.select(target); close_page_chooser();
        boolean latched = _keyboard_layout_view.shiftLatched(), locked = _keyboard_layout_view.shiftLocked();
        apply_page();
        if (sameGeometry && PageConfig.isText(target) && latched) _keyboard_layout_view.set_shift_state(true, locked);
        if (_editorAllowsEngine && PageConfig.isText(target))
        {
          _preferredLayout = target;
          DirectBootAwarePreferences.get_shared_preferences(this).edit().putString("pages_last_text", target).apply();
        }
      });
    if (!accepted && _pageRequest == request) _pageRequest--;
  }

  private void apply_page()
  {
    _rimeCandidates.collapse();
    _shiftLock = _keyboard_layout_view.shiftLocked();
    switch (PageConfig.base(_page.active()))
    {
      case "numeric":
        _currentSpecialLayout = _keyOverrides.apply(_page.active(), PageLayouts.numeric(getResources(), PageConfig.isVariant(_page.active()), _localeTextLayout)); break;
      case "greek":
        _currentSpecialLayout = LayoutModifier.modify_numpad(_keyOverrides.apply(_page.active(), PageLayouts.base(getResources(), _page.active())), _localeTextLayout); break;
      case "symbols": _currentSpecialLayout = LayoutModifier.modify_layout(_keyOverrides.apply("symbols", PageLayouts.base(getResources(), "symbols"))); break;
      case "pinyin": case "pinyin_finals": case "zhuyin":
        _currentSpecialLayout = LayoutModifier.modify_layout(_keyOverrides.apply(_page.active(), PageLayouts.base(getResources(), _page.active()))); break;
      default: _currentSpecialLayout = null; break;
    }
    _keyboard_layout_view.setKeyboard(current_layout());
    if (_shiftLock) _keyboard_layout_view.set_shift_state(true, true);
    if (_page.active().equals("emoji") && _emojiPane == null)
      _emojiPane = create_auxiliary_pane(R.layout.emoji_pane);
    if (_page.active().equals("clipboard") && _clipboard_pane == null)
      _clipboard_pane = create_auxiliary_pane(R.layout.clipboard_pane);
    if (_page.active().equals("emoji")) update_pane_controls(_emojiPane, "emoji");
    if (_page.active().equals("clipboard")) update_pane_controls(_clipboard_pane, "clipboard");
    page_visibility(); page_indicators();
  }

  private ViewGroup create_auxiliary_pane(int resource)
  {
    ViewGroup pane = (ViewGroup)inflate_view(resource);
    for (int i = 0; i < pane.getChildCount(); i++)
      if (pane.getChildAt(i) instanceof Keyboard2View)
        ((Keyboard2View)pane.getChildAt(i)).setEmbeddedControls();
    android.widget.Button picker = new android.widget.Button(this);
    picker.setText(R.string.pages_title); picker.setContentDescription("Open layout chooser");
    picker.setOnClickListener(view -> show_page_chooser());
    pane.addView(picker, 0, new LinearLayout.LayoutParams(-1, -2));
    _surface.addView(pane, new FrameLayout.LayoutParams(-1, -1));
    return pane;
  }

  private void update_pane_controls(ViewGroup pane, String id)
  {
    for (int i = 0; i < pane.getChildCount(); i++)
      if (pane.getChildAt(i) instanceof Keyboard2View)
        ((Keyboard2View)pane.getChildAt(i)).setKeyboard(_keyOverrides.apply(id, PageLayouts.base(getResources(), id)));
  }

  private void page_visibility()
  {
    if (_keyboard_layout_view == null || _rimeCandidates == null) return;
    boolean overlay = _pageChooser != null || _rimeCandidates.isExpanded();
    boolean emoji = _page.active().equals("emoji"), clipboard = _page.active().equals("clipboard");
    _keyboard_layout_view.setVisibility(overlay || emoji || clipboard ? View.INVISIBLE : View.VISIBLE);
    if (_emojiPane != null) _emojiPane.setVisibility(!overlay && emoji ? View.VISIBLE : View.GONE);
    if (_clipboard_pane != null) _clipboard_pane.setVisibility(!overlay && clipboard ? View.VISIBLE : View.GONE);
  }

  private void page_indicators()
  {
    if (_pages == null) return;
    Set<KeyValue.Event> events = new HashSet<>();
    if (_page.fnActive()) events.add(KeyValue.Event.SWITCH_FN);
    if (PageConfig.base(_page.active()).equals("numeric")) events.add(KeyValue.Event.SWITCH_NUMERIC);
    if (PageConfig.base(_page.active()).equals("greek")) events.add(KeyValue.Event.SWITCH_GREEKMATH);
    if (_pageChooser != null) events.add(KeyValue.Event.LAYOUT_PICKER);
    _keyboard_layout_view.setActivePageEvents(events);
    _rimeCandidates.setPageLabel(PageConfig.isText(_page.active()) ? null : getString(PagePreferences.label(_page.active())));
    _keyboard_layout_view.setContentDescription("Page: " + _page.active()
        + "; Fn: " + events.contains(KeyValue.Event.SWITCH_FN));
  }

  private void close_page_chooser()
  {
    if (_pageChooser != null) { _surface.removeView(_pageChooser); _pageChooser = null; }
    page_visibility();
    if (_inputController != null) page_indicators();
  }

  private void show_page_chooser()
  {
    if (_pageChooser != null) { close_page_chooser(); return; }
    _rimeCandidates.collapse();
    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.setId(R.id.miancak_page_chooser);
    scroll.setBackground(_keyboard_container_view.getBackground().getConstantState().newDrawable());
    LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); scroll.addView(rows);
    for (String id : _pages.visible())
    {
      android.widget.Button button = new android.widget.Button(this);
      button.setText((id.equals(_page.active()) ? "✓ " : "") + getString(PagePreferences.label(id)));
      button.setContentDescription("Choose page: " + id); button.setSelected(id.equals(_page.active()));
      button.setEnabled(PageConfig.mode(id) != InputRouter.Mode.CHINESE || _editorAllowsEngine);
      button.setOnClickListener(view -> select_page(id)); rows.addView(button);
    }
    android.widget.Button cancel = new android.widget.Button(this); cancel.setText(android.R.string.cancel);
    cancel.setOnClickListener(view -> close_page_chooser()); rows.addView(cancel);
    _pageChooser = scroll;
    _surface.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    page_visibility(); page_indicators();
  }

  /** Not static */
  public class Receiver implements KeyEventHandler.IReceiver,
         KeyValue.Stateful.Symbol_provider, DictionarySwitcher.Callback
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          start_activity(SettingsActivity.class);
          break;

        case SWITCH_TEXT:
          select_page(PageConfig.returnTarget(_page.active()));
          break;

        case SWITCH_NUMERIC:
          toggle_page("numeric");
          break;

        case SWITCH_EMOJI:
          toggle_page("emoji");
          break;

        case SWITCH_CLIPBOARD:
          toggle_page("clipboard");
          break;

        case SWITCH_BACK_EMOJI:
        case SWITCH_BACK_CLIPBOARD:
          select_page(PageConfig.returnTarget(_page.active()));
          break;

        case CHANGE_METHOD_PICKER:
          get_imm().showInputMethodPicker();
          break;

        case CHANGE_METHOD_PREV:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToLastInputMethod(getConnectionToken());
          else
            switchToPreviousInputMethod();
          break;

        case CHANGE_METHOD_NEXT:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToNextInputMethod(getConnectionToken(), false);
          else
            switchToNextInputMethod(false);
          break;

        case ACTION:
          InputConnection conn = getCurrentInputConnection();
          if (conn != null)
            conn.performEditorAction(_config.editor_config.actionId);
          break;

        case SWITCH_FORWARD:
          cycle_page(1);
          break;

        case SWITCH_BACKWARD:
          cycle_page(-1);
          break;

        case SWITCH_GREEKMATH:
          toggle_page("greek");
          break;

        case SWITCH_FN:
          String target = _pages.fn(_page.active());
          if (!target.isEmpty()) select_page(target);
          break;

        case LAYOUT_PICKER:
          show_page_chooser();
          break;

        case SWITCH_PINYIN: toggle_page("pinyin"); break;
        case SWITCH_PINYIN_FINALS: toggle_page("pinyin_finals"); break;
        case SWITCH_ZHUYIN: toggle_page("zhuyin"); break;

        case CAPS_LOCK:
          set_shift_state(true, true);
          break;

        case SWITCH_VOICE_TYPING:
          if (!VoiceImeSwitcher.switch_to_voice_ime(Keyboard2.this, get_imm(),
                Config.globalPrefs()))
            _config.shouldOfferVoiceTyping = false;
          break;

        case SWITCH_VOICE_TYPING_CHOOSER:
          VoiceImeSwitcher.choose_voice_ime(Keyboard2.this, get_imm(),
              Config.globalPrefs());
          break;

        case HIDE_SELF:
          Keyboard2.this.requestHideSelf(0);
          break;

        case TOGGLE_LANGUAGE:
          toggle_language_page();
          break;

        case CHANGE_DICTIONARY:
          new DictionarySwitcher(Keyboard2.this, _dictionaries, this).choose();
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      _keyboard_layout_view.set_shift_state(state, lock);
    }

    public void set_compose_pending(boolean pending)
    {
      _keyboard_layout_view.set_compose_pending(pending);
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      _keyboard_layout_view.set_selection_state(selection_is_ongoing);
    }

    public InputConnection getCurrentInputConnection()
    {
      return Keyboard2.this.getCurrentInputConnection();
    }

    public Handler getHandler()
    {
      return _handler;
    }

    public void set_suggestions(Suggestions suggestions)
    {
      _candidates_view.set_candidates(suggestions);
    }

    public String provide_stateful_key_symbol(KeyValue.Stateful q)
    {
      switch (q)
      {
        case Complete_first: return _suggestions.suggestions[0];
        case Complete_second: return _suggestions.suggestions[1];
        case Complete_third: return _suggestions.suggestions[2];
        case Complete_emoji: return _suggestions.emoji_suggestion;
      }
      return "";
    }

    public void on_change_dictionary(String dict_name)
    {
      select_dictionary(dict_name);
    }

    public void launch_dictionaries_activity()
    {
      Keyboard2.this.launch_dictionaries_activity();
    }
  }

  private IBinder getConnectionToken()
  {
    return getWindow().getWindow().getAttributes().token;
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new ContextThemeWrapper(this, _config.theme), layout, null);
  }
}
