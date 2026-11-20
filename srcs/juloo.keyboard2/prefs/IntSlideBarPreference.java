package juloo.keyboard2.prefs;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.SeekBar;
import juloo.keyboard2.R;

/*
 ** IntSlideBarPreference
 ** -
 ** Open a dialog showing a seekbar
 ** -
 ** xml attrs:
 **   android:defaultValue  Default value (int)
 **   min                   min value (int)
 **   max                   max value (int)
 ** -
 ** Summary field allow to show the current value using %s flag
 */
public class IntSlideBarPreference extends DialogPreference
  implements SeekBar.OnSeekBarChangeListener
{
  private View _layout;
  private TextView _textView;
  private SeekBar _seekBar;

  private int _min;

  private String _initialSummary;

  public IntSlideBarPreference(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _initialSummary = getSummary().toString();
    _layout = View.inflate(context, R.layout.pref_slider_dialog, null);
    _textView = _layout.findViewById(R.id.pref_slider_value);
    _seekBar = _layout.findViewById(R.id.pref_slider_control);
    _seekBar.setOnSeekBarChangeListener(this);
    _min = attrs.getAttributeIntValue(null, "min", 0);
    int max = attrs.getAttributeIntValue(null, "max", 0);
    _seekBar.setMax(max - _min);
  }

  @Override
  public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
  {
    updateText();
  }

  @Override
  public void onStartTrackingTouch(SeekBar seekBar)
  {
  }

  @Override
  public void onStopTrackingTouch(SeekBar seekBar)
  {
  }

  @Override
  protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue)
  {
    int value;

    if (restorePersistedValue)
    {
      value = getPersistedInt(_min);
    }
    else
    {
      value = (Integer)defaultValue;
      persistInt(value);
    }
    _seekBar.setProgress(value - _min);
    updateSummary();
  }

  @Override
  protected Object onGetDefaultValue(TypedArray a, int index)
  {
    return (a.getInt(index, _min));
  }

  @Override
  protected void onDialogClosed(boolean positiveResult)
  {
    if (positiveResult)
      persistInt(_seekBar.getProgress() + _min);
    else
      _seekBar.setProgress(getPersistedInt(_min) - _min);

    updateSummary();
  }

  protected View onCreateDialogView()
  {
    ViewGroup parent = (ViewGroup)_layout.getParent();

    if (parent != null)
      parent.removeView(_layout);
    return (_layout);
  }

  private void updateText()
  {
    _textView.setText(String.format(_initialSummary, _seekBar.getProgress() + _min));
  }

  private void updateSummary()
  {
    updateText();
    setSummary(_textView.getText());
  }
}
