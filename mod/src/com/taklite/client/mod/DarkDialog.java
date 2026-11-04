package com.taklite.client.mod;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Builds dialogs that match TAK Lite's own dark, rounded dialog layouts
 * (dialog_edit_marker.xml etc.) without needing new XML resources.
 */
public final class DarkDialog {

    public static final int COLOR_LABEL = 0xFFAAAAAA;
    public static final int COLOR_HINT = 0xFF666666;
    public static final int COLOR_FIELD = 0xFF3A3A3A;
    public static final int COLOR_ACCENT = 0xFF4CAF50;
    public static final int COLOR_PRIMARY = 0xFF2A2A2A;
    public static final int COLOR_DANGER = 0xFFF44336;
    public static final int COLOR_SECONDARY = 0xFFB0B0B0;

    public final Context context;
    public final Dialog dialog;
    public final LinearLayout content;
    private final LinearLayout buttonBar;

    public DarkDialog(Context context, String title) {
        this.context = context;
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        setBackground(root, "dialog_rounded_bg", COLOR_PRIMARY);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.WHITE);
        titleView.setPadding(dp(16), dp(16), dp(16), dp(16));
        setBackground(titleView, "dialog_rounded_top", COLOR_PRIMARY);
        root.addView(titleView, matchWrap());

        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(content, matchWrap());
        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        buttonBar = new LinearLayout(context);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttonBar.setPadding(dp(12), dp(12), dp(12), dp(12));
        setBackground(buttonBar, "dialog_rounded_bottom", COLOR_PRIMARY);
        root.addView(buttonBar, matchWrap());

        dialog.setContentView(root);
    }

    public void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    public void dismiss() {
        dialog.dismiss();
    }

    /** Adds a text button to the bottom bar, in the style of the app's CANCEL / SAVE actions. */
    public TextView addAction(String label, int color, View.OnClickListener listener) {
        TextView action = new TextView(context);
        action.setText(label);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setTextColor(color);
        action.setPadding(dp(10), dp(10), dp(10), dp(10));
        action.setClickable(true);
        action.setFocusable(true);
        action.setBackgroundResource(selectableBackground(context));
        action.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(4));
        buttonBar.addView(action, lp);
        return action;
    }

    /** Pushes the actions added after this call to the right, leaving earlier ones on the left. */
    public void addActionSpacer() {
        buttonBar.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    public TextView addLabel(String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(COLOR_LABEL);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = content.getChildCount() == 0 ? 0 : dp(14);
        lp.bottomMargin = dp(4);
        content.addView(label, lp);
        return label;
    }

    public TextView addText(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(COLOR_SECONDARY);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        content.addView(view, lp);
        return view;
    }

    public EditText addField(String label, String hint, String value, int inputType) {
        addLabel(label);
        EditText field = new EditText(context);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(COLOR_HINT);
        field.setBackgroundColor(COLOR_FIELD);
        field.setPadding(dp(12), dp(12), dp(12), dp(12));
        field.setHint(hint);
        field.setInputType(inputType);
        if ((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) field.setSingleLine(true);
        if (value != null) field.setText(value);
        content.addView(field, matchWrap());
        return field;
    }

    public Button addButton(String label, int tint, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundTintList(ColorStateList.valueOf(tint));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(6);
        content.addView(button, lp);
        return button;
    }

    /** A tappable two-line row (title + subtitle) for lists. */
    public LinearLayout addRow(String title, String subtitle, int titleColor, View.OnClickListener listener) {
        LinearLayout row = row(context, title, subtitle, titleColor, listener);
        content.addView(row, matchWrap());
        return row;
    }

    /** The view behind {@link #addRow}, for lists built outside a dialog's content. Child 0 is the title. */
    public static LinearLayout row(Context context, String title, String subtitle, int titleColor,
                                   View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(selectableBackground(context));
        row.setOnClickListener(listener);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(titleColor);
        row.addView(titleView, matchWrap());

        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = new TextView(context);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(COLOR_LABEL);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(sub, matchWrap());
        }
        return row;
    }

    public void addDivider() {
        View line = new View(context);
        line.setBackgroundColor(0xFF000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        content.addView(line, lp);
    }

    public int dp(int value) {
        return dp(context, value);
    }

    public static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int selectableBackground(Context context) {
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    /** Uses one of TAK Lite's own dialog drawables when present, else a flat color. */
    private void setBackground(View view, String drawableName, int fallbackColor) {
        int id = context.getResources().getIdentifier(drawableName, "drawable", context.getPackageName());
        if (id != 0) {
            view.setBackgroundResource(id);
        } else {
            view.setBackgroundColor(fallbackColor);
        }
    }
}
