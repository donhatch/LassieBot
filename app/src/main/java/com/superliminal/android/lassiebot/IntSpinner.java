package com.superliminal.android.lassiebot;


import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;


public class IntSpinner extends LinearLayout {
    private int mMin = 0, mMax = 0, mVal = 0;

    public interface IntSpinnerListener {
        public void valueChanged(int new_val);
    }

    private List<IntSpinnerListener> mListeners = new ArrayList<IntSpinnerListener>();

    public void addListener(IntSpinnerListener l) {
        mListeners.add(l);
    }

    public void removeListener(IntSpinnerListener l) {
        mListeners.remove(l);
    }

    protected void fireValueChanged() {
        for(IntSpinnerListener l : mListeners)
            l.valueChanged(mVal);
    }

    public IntSpinner(Context context, AttributeSet atts) {
        super(context, atts);
        // It seems that the XML is not fully inflated at this time
        // because findViewById returns null for objects that should be there.
        // A workaround is to move the constructor code that would normally
        // be performed here into the onFinishInflate() method.
        // Also worthy of note is that it is both required to implement at
        // least one constructor, and that it be this two-arg one.
    }


    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        ImageButton left = (ImageButton) findViewById(R.id.arrow_left);
        // Null checks for left & right are needed for Eclipse graphic layout to work.
        if(left != null)
            left.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(mVal == mMin)
                        return;
                    setVal(mVal - 1);
                }
            });
        ImageButton right = (ImageButton) findViewById(R.id.arrow_right);
        if(right != null)
            right.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(mVal == mMax)
                        return;
                    setVal(mVal + 1);
                }
            });
    }

    public void setAll(int min, int max, int cur) {
        mMin = min;
        mMax = max;
        mVal = cur;
        mVal = Math.min(mMax, mVal);
        mVal = Math.max(mMin, mVal);
        setVal(mVal);
    }

    public int getVal() {
        return mVal;
    }

    public void setVal(int val) {
        if(val < mMin)
            throw new IllegalArgumentException();
        if(val > mMax)
            throw new IllegalArgumentException();
        int old_val = mVal;
        mVal = val;
        ((TextView) findViewById(R.id.val)).setText("" + mVal);
        checkArrowEnables();
        if(mVal != old_val)
            fireValueChanged();
    }

    private void checkArrowEnables() {
        ((ImageButton) findViewById(R.id.arrow_left)).setEnabled(mVal > mMin);
        ((ImageButton) findViewById(R.id.arrow_right)).setEnabled(mVal < mMax);
    }
}