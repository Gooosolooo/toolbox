package com.example.floatingclock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** 背景预设色与 HEX、RGB 双向同步输入。 */
public final class BackgroundColorControl extends LinearLayout {
    private final EditText hexInput;
    private final EditText[] rgbInputs = new EditText[3];
    private final TextView preview;
    private boolean synchronizing;
    private final Button expandButton;
    private final LinearLayout content;
    private final ColorPaletteView colorPalette;

    public BackgroundColorControl(Context context) {
        super(context);
        setOrientation(VERTICAL);
        expandButton = new Button(context);
        addView(expandButton);
        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        addView(content);
        content.setVisibility(GONE);
        expandButton.setOnClickListener(view -> {
            boolean opening = content.getVisibility() != VISIBLE;
            content.setVisibility(opening ? VISIBLE : GONE);
            applyColor(FloatingClockPreferences.readBackgroundColor(context), null);
        });
        colorPalette = new ColorPaletteView(context);
        content.addView(colorPalette, new LayoutParams(LayoutParams.MATCH_PARENT, dp(190)));
        colorPalette.setOnColorSelected(color -> applyColor(color, null));
        String[] names = {"中性灰", "紫色", "蓝色", "绿色", "暖色"};
        String[][] palettes = {
                {"#2D2D2C", "#424242", "#616161", "#858585"},
                {"#312B46", "#51416B", "#7C5CFC", "#A98BFF"},
                {"#203448", "#315B7C", "#427DA8", "#78A9CA"},
                {"#263C32", "#3E6350", "#608873", "#97B29A"},
                {"#49332E", "#805246", "#AD7460", "#D6A68A"}
        };
        for (int group = 0; group < names.length; group++) {
            addLabel(names[group]);
            LinearLayout row = new LinearLayout(context);
            for (String value : palettes[group]) {
                int color = Color.parseColor(value);
                Button swatch = new Button(context);
                swatch.setContentDescription(names[group] + " " + value);
                GradientDrawable background = new GradientDrawable();
                background.setColor(color);
                background.setCornerRadius(dp(8));
                swatch.setBackground(background);
                LayoutParams params = new LayoutParams(0, dp(40), 1);
                params.setMargins(dp(3), dp(4), dp(3), dp(4));
                row.addView(swatch, params);
                swatch.setOnClickListener(view -> applyColor(color, null));
            }
            content.addView(row);
        }
        preview = new TextView(context);
        preview.setPadding(dp(10), dp(12), dp(10), dp(12));
        preview.setTextColor(Color.WHITE);
        content.addView(preview);
        hexInput = new EditText(context);
        hexInput.setSingleLine(true);
        hexInput.setHint("HEX，例如 #7C5CFC 或 #ABC");
        content.addView(hexInput);
        LinearLayout rgbRow = new LinearLayout(context);
        String[] channels = {"R", "G", "B"};
        for (int index = 0; index < rgbInputs.length; index++) {
            EditText input = new EditText(context);
            input.setSingleLine(true);
            input.setHint(channels[index] + "：0–255");
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            rgbInputs[index] = input;
            rgbRow.addView(input, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }
        content.addView(rgbRow);
        Button reset = new Button(context);
        reset.setText("恢复默认色");
        reset.setOnClickListener(view -> applyColor(
                FloatingClockPreferences.DEFAULT_BACKGROUND_COLOR, null));
        content.addView(reset);
        applyColor(FloatingClockPreferences.readBackgroundColor(context), null);
        hexInput.addTextChangedListener(watcher(() -> readHex()));
        for (EditText input : rgbInputs) {
            input.addTextChangedListener(watcher(() -> readRgb()));
        }
    }

    private void readHex() {
        String value = hexInput.getText().toString().trim().replaceFirst("^#", "");
        if (!value.matches("[0-9a-fA-F]{3}|[0-9a-fA-F]{6}")) {
            hexInput.setError("请输入 3 位或 6 位十六进制颜色");
            return;
        }
        if (value.length() == 3) {
            value = "" + value.charAt(0) + value.charAt(0) + value.charAt(1)
                    + value.charAt(1) + value.charAt(2) + value.charAt(2);
        }
        hexInput.setError(null);
        applyColor(Color.parseColor("#" + value), hexInput);
    }

    private void readRgb() {
        int[] channels = new int[3];
        boolean valid = true;
        for (int index = 0; index < rgbInputs.length; index++) {
            try {
                channels[index] = Integer.parseInt(rgbInputs[index].getText().toString());
                if (channels[index] < 0 || channels[index] > 255) {
                    throw new NumberFormatException("Channel outside 0–255");
                }
                rgbInputs[index].setError(null);
            } catch (NumberFormatException exception) {
                rgbInputs[index].setError("请输入 0–255");
                valid = false;
            }
        }
        if (valid) {
            applyColor(Color.rgb(channels[0], channels[1], channels[2]), rgbInputs[0]);
        }
    }

    private void applyColor(int color, EditText source) {
        synchronizing = true;
        String hex = String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
        expandButton.setText("背景颜色  " + hex
                + (content.getVisibility() == VISIBLE ? "  ▴" : "  ▾"));
        colorPalette.setColor(color);
        if (source != hexInput) {
            hexInput.setText(hex);
            hexInput.setError(null);
        }
        if (source != rgbInputs[0]) {
            int[] channels = {Color.red(color), Color.green(color), Color.blue(color)};
            for (int index = 0; index < rgbInputs.length; index++) {
                rgbInputs[index].setText(String.valueOf(channels[index]));
                rgbInputs[index].setError(null);
            }
        }
        preview.setBackgroundColor(color);
        preview.setText("当前颜色  " + hex);
        preview.setTextColor(Color.red(color) * 0.299 + Color.green(color) * 0.587
                + Color.blue(color) * 0.114 > 150 ? Color.BLACK : Color.WHITE);
        FloatingClockPreferences.saveBackgroundColor(getContext(), color);
        FloatingClockService.updateBackgroundOpacity(getContext(),
                FloatingClockPreferences.readBackgroundOpacity(getContext()));
        synchronizing = false;
    }

    private TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable text) {
                if (!synchronizing) {
                    action.run();
                }
            }
        };
    }

    private void addLabel(String text) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextColor(0xFF252525);
        label.setPadding(0, dp(12), 0, dp(4));
        content.addView(label);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
