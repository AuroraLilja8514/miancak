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
 ** SideBarPreference
 ** -
 ** Open a dialog showing a seekbar
 ** -
 ** xml attrs:
 **   android:defaultValue  Default value (float)
 **   min                   min value (float)
 **   max                   max value (float)
 ** -
 ** Summary field allow to show the current value using %f or %s flag
 */
public class SlideBarPreference extends DialogPreference
  implements SeekBar.OnSeekBarChangeListener
{
  private static final int STEPS = 100;

  private View _layout;
  private TextView _textView;
  private SeekBar _seekBar;

  private float _min;
  private float _max;
  private float _value;

  private String _initialSummary;

  public SlideBarPreference(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _initialSummary = getSummary().toString();
    _layout = View.inflate(context, R.layout.pref_slider_dialog, null);
    _textView = _layout.findViewById(R.id.pref_slider_value);
    _seekBar = _layout.findViewById(R.id.pref_slider_control);
    _seekBar.setOnSeekBarChangeListener(this);
    _seekBar.setMax(STEPS);
    _min = float_of_string(attrs.getAttributeValue(null, "min"));
    _value = _min;
    _max = Math.max(1f, float_of_string(attrs.getAttributeValue(null, "max")));
  }

  @Override
  public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
  {
    _value = Math.round(progress * (_max - _min)) / (float)STEPS + _min;
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
    float value = restorePersistedValue ? getPersistedFloat(_min) : (Float)defaultValue;
    if (!restorePersistedValue) persistFloat(value);
    restoreProgress(value);
    updateSummary();
  }

  @Override
  protected Object onGetDefaultValue(TypedArray a, int index)
  {
    return (a.getFloat(index, _min));
  }

  @Override
  protected void onDialogClosed(boolean positiveResult)
  {
    if (positiveResult)
      persistFloat(_value);
    else
      restoreProgress(getPersistedFloat(_min));

    updateSummary();
  }

  private void restoreProgress(float value)
  {
    _seekBar.setProgress((int)((value - _min) * STEPS / (_max - _min)));
    // Restoring the slider position can trigger its listener and quantize the
    // display. Keep the actual saved value until the next slider adjustment.
    _value = value;
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
    _textView.setText(String.format(_initialSummary, _value));
  }

  private void updateSummary()
  {
    updateText();
    setSummary(_textView.getText());
  }

  private static float float_of_string(String str)
  {
    if (str == null)
      return (0f);
    return (Float.parseFloat(str));
  }
}
