package com.termux.app.activities;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;

import com.termux.R;
import com.termux.app.theme.TerminalChromePalette;
import com.termux.app.theme.TerminalThemePresets.ThemePreset;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.List;

/**
 * Builds themed Settings UI sections, cards, switches, segmented controls, and visual previews.
 */
final class SettingsUiBuilder {

    interface ToggleListener {
        void onToggle(boolean checked);
    }

    interface OptionListener {
        void onSelected(@NonNull String value);
    }

    interface IntListener {
        void onChanged(int value);
    }

    static final class SegmentedOption {
        final String value;
        final String label;

        SegmentedOption(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    private final Context mContext;
    private final float mDensity;

    SettingsUiBuilder(@NonNull Context context) {
        mContext = context;
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    int dp(int value) {
        return Math.round(value * mDensity);
    }

    GradientDrawable roundedRect(@ColorInt int fillColor, @ColorInt int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(radiusDp));
        d.setColor(fillColor);
        if (strokeColor != Color.TRANSPARENT) {
            d.setStroke(Math.max(1, dp(1)), strokeColor);
        }
        return d;
    }

    TextView createSectionTitle(@NonNull String title, @NonNull TerminalChromePalette palette) {
        TextView tv = new TextView(mContext);
        tv.setText(title.toUpperCase());
        tv.setTextColor(palette.textSecondary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(14), dp(4), dp(6));
        tv.setLayoutParams(lp);
        return tv;
    }

    LinearLayout createCard(@NonNull TerminalChromePalette palette) {
        LinearLayout card = new LinearLayout(mContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(palette.settingsCardBg, ColorUtils.setAlphaComponent(palette.textPrimary, 20), 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);
        return card;
    }

    View createDivider(@NonNull TerminalChromePalette palette) {
        View v = new View(mContext);
        v.setBackgroundColor(ColorUtils.setAlphaComponent(palette.textPrimary, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.setMargins(dp(16), 0, dp(16), 0);
        v.setLayoutParams(lp);
        return v;
    }

    LinearLayout createSwitchRow(
        @NonNull String title,
        @NonNull String subtitle,
        boolean checked,
        @NonNull TerminalChromePalette palette,
        @NonNull ToggleListener listener
    ) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textCol = new LinearLayout(mContext);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginEnd(dp(12));
        textCol.setLayoutParams(textLp);

        TextView titleView = new TextView(mContext);
        titleView.setText(title);
        titleView.setTextColor(palette.textPrimary);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleView.setTypeface(null, Typeface.BOLD);

        TextView subView = new TextView(mContext);
        subView.setText(subtitle);
        subView.setTextColor(palette.textSecondary);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        subView.setLayoutParams(subLp);

        textCol.addView(titleView);
        textCol.addView(subView);

        SwitchCompat sw = new SwitchCompat(mContext);
        sw.setChecked(checked);
        styleSwitch(sw, palette);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onToggle(isChecked));

        row.setOnClickListener(v -> sw.toggle());
        row.addView(textCol);
        row.addView(sw);
        return row;
    }

    void styleSwitch(@NonNull SwitchCompat sw, @NonNull TerminalChromePalette palette) {
        int[][] states = new int[][]{
            new int[]{android.R.attr.state_checked},
            new int[]{-android.R.attr.state_checked}
        };
        ColorStateList trackColors = new ColorStateList(states, new int[]{
            palette.textPrimary,
            ColorUtils.blendARGB(palette.settingsWellBg, palette.textPrimary, 0.16f)
        });
        ColorStateList thumbColors = new ColorStateList(states, new int[]{
            palette.canvasBg,
            palette.textSecondary
        });
        DrawableCompat.setTintList(sw.getTrackDrawable(), trackColors);
        DrawableCompat.setTintList(sw.getThumbDrawable(), thumbColors);
    }

    LinearLayout createSegmentedRow(
        @NonNull String title,
        @Nullable String subtitle,
        @NonNull List<SegmentedOption> options,
        @NonNull String selectedValue,
        @NonNull TerminalChromePalette palette,
        @NonNull OptionListener listener
    ) {
        LinearLayout container = new LinearLayout(mContext);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView titleView = new TextView(mContext);
        titleView.setText(title);
        titleView.setTextColor(palette.textPrimary);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleView.setTypeface(null, Typeface.BOLD);
        container.addView(titleView);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView subView = new TextView(mContext);
            subView.setText(subtitle);
            subView.setTextColor(palette.textSecondary);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2);
            subView.setLayoutParams(subLp);
            container.addView(subView);
        }

        LinearLayout well = new LinearLayout(mContext);
        well.setOrientation(LinearLayout.HORIZONTAL);
        well.setPadding(dp(4), dp(4), dp(4), dp(4));
        well.setBackground(roundedRect(palette.settingsWellBg, ColorUtils.setAlphaComponent(palette.textPrimary, 18), 11));
        LinearLayout.LayoutParams wellLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wellLp.topMargin = dp(10);
        well.setLayoutParams(wellLp);

        for (int i = 0; i < options.size(); i++) {
            SegmentedOption opt = options.get(i);
            boolean active = opt.value.equals(selectedValue);

            TextView btn = new TextView(mContext);
            btn.setText(opt.label);
            btn.setGravity(Gravity.CENTER);
            btn.setSingleLine(true);
            btn.setEllipsize(TextUtils.TruncateAt.END);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            btn.setTextColor(active ? palette.canvasBg : palette.textSecondary);
            btn.setMinimumHeight(dp(36));
            btn.setPadding(dp(6), dp(8), dp(6), dp(8));
            if (active) {
                btn.setBackground(roundedRect(palette.textPrimary, Color.TRANSPARENT, 8));
            } else {
                btn.setBackground(palette.createRippleBackground(Color.TRANSPARENT));
            }
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setOnClickListener(v -> listener.onSelected(opt.value));

            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) btnLp.setMarginStart(dp(4));
            btn.setLayoutParams(btnLp);
            well.addView(btn);
        }

        container.addView(well);
        return container;
    }

    View createLiveTerminalPreviewCard(
        @NonNull ThemePreset preset,
        int fontSizeSp,
        @NonNull String cursorStyle,
        boolean cursorBlink,
        @NonNull TerminalChromePalette palette
    ) {
        LinearLayout frame = new LinearLayout(mContext);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setClipToOutline(true);
        frame.setBackground(roundedRect(preset.bg, ColorUtils.setAlphaComponent(palette.textPrimary, 28), 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frame.setLayoutParams(lp);

        // Mini window chrome header
        LinearLayout header = new LinearLayout(mContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(7), dp(12), dp(7));
        header.setBackgroundColor(palette.topChromeBg);

        ImageView icon = new ImageView(mContext);
        icon.setImageResource(R.drawable.ic_terminal_prompt);
        icon.setColorFilter(palette.textSecondary);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        iconLp.setMarginEnd(dp(6));
        icon.setLayoutParams(iconLp);

        TextView title = new TextView(mContext);
        title.setText("zsh -- " + preset.name);
        title.setTextColor(palette.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);

        TextView badge = new TextView(mContext);
        badge.setText(fontSizeSp + "sp \u2022 " + cursorStyle + (cursorBlink ? " \u2022 blink" : ""));
        badge.setTextColor(palette.textSecondary);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        badge.setTypeface(Typeface.MONOSPACE);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setBackground(roundedRect(palette.settingsWellBg, Color.TRANSPARENT, 6));

        header.addView(icon);
        header.addView(title);
        header.addView(badge);
        frame.addView(header);

        View topHairline = new View(mContext);
        topHairline.setBackgroundColor(palette.borderHairline);
        topHairline.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        frame.addView(topHairline);

        // Terminal sample body
        LinearLayout body = new LinearLayout(mContext);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(preset.bg);
        body.setPadding(dp(14), dp(12), dp(14), dp(12));

        Typeface termTypeface = Typeface.MONOSPACE;
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        if (fontFile.exists() && fontFile.length() > 0) {
            try {
                termTypeface = Typeface.createFromFile(fontFile);
            } catch (Exception ignored) {
            }
        }

        int previewSp = Math.max(10, Math.min(18, fontSizeSp));

        TextView line1 = new TextView(mContext);
        line1.setTypeface(termTypeface);
        line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, previewSp);
        line1.setTextColor(preset.muted);
        line1.setText("# ~/.termux/colors.properties & font.ttf");

        SpannableStringBuilder line2Text = new SpannableStringBuilder("aqky@Terminal ~/src % git status -sb");
        line2Text.setSpan(new ForegroundColorSpan(preset.accent), 0, 13, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView line2 = new TextView(mContext);
        line2.setTypeface(termTypeface);
        line2.setTextSize(TypedValue.COMPLEX_UNIT_SP, previewSp);
        line2.setTextColor(preset.fg);
        line2.setText(line2Text);

        TextView line3 = new TextView(mContext);
        line3.setTypeface(termTypeface);
        line3.setTextSize(TypedValue.COMPLEX_UNIT_SP, previewSp);
        line3.setTextColor(preset.muted);
        line3.setText("## master...origin/master");

        LinearLayout line4 = new LinearLayout(mContext);
        line4.setOrientation(LinearLayout.HORIZONTAL);
        line4.setGravity(Gravity.CENTER_VERTICAL);
        SpannableStringBuilder promptText = new SpannableStringBuilder("aqky@Terminal ~/src % ");
        promptText.setSpan(new ForegroundColorSpan(preset.accent), 0, 13, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView promptView = new TextView(mContext);
        promptView.setTypeface(termTypeface);
        promptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, previewSp);
        promptView.setTextColor(preset.fg);
        promptView.setText(promptText);

        View cursorView = new View(mContext);
        cursorView.setBackgroundColor(preset.cursor);
        LinearLayout.LayoutParams cursorLp;
        if ("underline".equals(cursorStyle)) {
            cursorLp = new LinearLayout.LayoutParams(dp(8), dp(2));
            cursorLp.gravity = Gravity.BOTTOM;
            cursorLp.bottomMargin = dp(2);
        } else if ("bar".equals(cursorStyle)) {
            cursorLp = new LinearLayout.LayoutParams(dp(2), dp(14));
        } else {
            cursorLp = new LinearLayout.LayoutParams(dp(8), dp(14));
        }
        cursorView.setLayoutParams(cursorLp);
        line4.addView(promptView);
        line4.addView(cursorView);

        body.addView(line1);
        body.addView(line2);
        body.addView(line3);
        body.addView(line4);
        frame.addView(body);

        // Mini Extra Keys preview bar matching chrome palette
        View bottomHairline = new View(mContext);
        bottomHairline.setBackgroundColor(palette.borderHairline);
        bottomHairline.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        frame.addView(bottomHairline);

        LinearLayout miniExtra = new LinearLayout(mContext);
        miniExtra.setOrientation(LinearLayout.HORIZONTAL);
        miniExtra.setBackgroundColor(palette.extraKeysBg);
        miniExtra.setPadding(dp(8), dp(5), dp(8), dp(5));
        String[] keys = {"ESC", "/", "CTRL", "\u2191", "\u2193"};
        for (String key : keys) {
            TextView k = new TextView(mContext);
            k.setText(key);
            k.setGravity(Gravity.CENTER);
            k.setTextColor(palette.textPrimary);
            k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            k.setTypeface(Typeface.MONOSPACE);
            k.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            miniExtra.addView(k);
        }
        frame.addView(miniExtra);
        return frame;
    }

    View createThemeSwatchCard(
        @NonNull ThemePreset preset,
        boolean selected,
        @NonNull TerminalChromePalette palette,
        @NonNull View.OnClickListener onClick
    ) {
        LinearLayout card = new LinearLayout(mContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setClickable(true);
        card.setFocusable(true);
        card.setMinimumHeight(dp(44));
        card.setBackground(roundedRect(
            selected ? palette.settingsActiveBg : palette.settingsWellBg,
            selected ? palette.textPrimary : ColorUtils.setAlphaComponent(palette.textPrimary, 22),
            12
        ));
        card.setOnClickListener(onClick);

        // Mini terminal thumbnail
        LinearLayout thumb = new LinearLayout(mContext);
        thumb.setOrientation(LinearLayout.VERTICAL);
        thumb.setPadding(dp(8), dp(7), dp(8), dp(7));
        thumb.setBackground(roundedRect(preset.bg, ColorUtils.setAlphaComponent(0xFFFFFFFF, 26), 8));
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        thumbLp.bottomMargin = dp(8);
        thumb.setLayoutParams(thumbLp);

        LinearLayout dotsRow = new LinearLayout(mContext);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        View d1 = new View(mContext);
        d1.setBackground(roundedRect(preset.accent, Color.TRANSPARENT, 2));
        LinearLayout.LayoutParams d1Lp = new LinearLayout.LayoutParams(dp(14), dp(5));
        d1Lp.setMarginEnd(dp(4));
        d1.setLayoutParams(d1Lp);
        View d2 = new View(mContext);
        d2.setBackground(roundedRect(preset.fg, Color.TRANSPARENT, 2));
        LinearLayout.LayoutParams d2Lp = new LinearLayout.LayoutParams(dp(22), dp(5));
        d2Lp.setMarginEnd(dp(4));
        d2.setLayoutParams(d2Lp);
        View d3 = new View(mContext);
        d3.setBackground(roundedRect(preset.muted, Color.TRANSPARENT, 2));
        d3.setLayoutParams(new LinearLayout.LayoutParams(dp(14), dp(5)));
        dotsRow.addView(d1);
        dotsRow.addView(d2);
        dotsRow.addView(d3);

        View cur = new View(mContext);
        cur.setBackgroundColor(preset.cursor);
        LinearLayout.LayoutParams curLp = new LinearLayout.LayoutParams(dp(6), dp(10));
        curLp.topMargin = dp(8);
        cur.setLayoutParams(curLp);

        thumb.addView(dotsRow);
        thumb.addView(cur);
        card.addView(thumb);

        LinearLayout labelRow = new LinearLayout(mContext);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(mContext);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(mContext);
        nameTv.setText(preset.name);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        nameTv.setTextColor(palette.textPrimary);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        nameTv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);

        TextView hexTv = new TextView(mContext);
        hexTv.setText(preset.hexLabel);
        hexTv.setTextColor(palette.textSecondary);
        hexTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        hexTv.setTypeface(Typeface.MONOSPACE);

        labels.addView(nameTv);
        labels.addView(hexTv);
        labelRow.addView(labels);

        if (selected) {
            View activeDot = new View(mContext);
            activeDot.setBackground(roundedRect(palette.textPrimary, Color.TRANSPARENT, 4));
            activeDot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
            labelRow.addView(activeDot);
        }

        card.addView(labelRow);
        return card;
    }

    View createFontSizeControl(
        int currentSize,
        int minSize,
        int maxSize,
        @NonNull TerminalChromePalette palette,
        @NonNull IntListener listener
    ) {
        LinearLayout container = new LinearLayout(mContext);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout topRow = new LinearLayout(mContext);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(mContext);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(mContext);
        titleView.setText("Font Size");
        titleView.setTextColor(palette.textPrimary);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleView.setTypeface(null, Typeface.BOLD);

        TextView subView = new TextView(mContext);
        subView.setText("Slide or pinch-zoom inside terminal");
        subView.setTextColor(palette.textSecondary);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        textCol.addView(titleView);
        textCol.addView(subView);

        LinearLayout stepper = new LinearLayout(mContext);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER_VERTICAL);

        TextView decBtn = createStepButton("\u2212", palette, v -> listener.onChanged(Math.max(minSize, currentSize - 2)));
        TextView valueTv = new TextView(mContext);
        valueTv.setText(currentSize + "sp");
        valueTv.setTextColor(palette.textPrimary);
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        valueTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        valueTv.setGravity(Gravity.CENTER);
        valueTv.setLayoutParams(new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView incBtn = createStepButton("+", palette, v -> listener.onChanged(Math.min(maxSize, currentSize + 2)));

        stepper.addView(decBtn);
        stepper.addView(valueTv);
        stepper.addView(incBtn);

        topRow.addView(textCol);
        topRow.addView(stepper);
        container.addView(topRow);

        SeekBar seekBar = new SeekBar(mContext);
        seekBar.setMax(maxSize - minSize);
        seekBar.setProgress(Math.max(0, Math.min(maxSize - minSize, currentSize - minSize)));
        seekBar.setProgressTintList(ColorStateList.valueOf(palette.textPrimary));
        seekBar.setThumbTintList(ColorStateList.valueOf(palette.textPrimary));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.textSecondary, 90)));
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seekLp.topMargin = dp(10);
        seekBar.setLayoutParams(seekLp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int newVal = minSize + progress;
                valueTv.setText(newVal + "sp");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                listener.onChanged(minSize + sb.getProgress());
            }
        });
        container.addView(seekBar);
        return container;
    }

    private TextView createStepButton(@NonNull String symbol, @NonNull TerminalChromePalette palette, @NonNull View.OnClickListener onClick) {
        TextView btn = new TextView(mContext);
        btn.setText(symbol);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(palette.textPrimary);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setBackground(roundedRect(palette.settingsActiveBg, ColorUtils.setAlphaComponent(palette.textPrimary, 24), 8));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(40)));
        btn.setOnClickListener(onClick);
        return btn;
    }

    View createSplitPreviewCard(int leftPct, @NonNull TerminalChromePalette palette) {
        int rightPct = 100 - leftPct;
        LinearLayout box = new LinearLayout(mContext);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(roundedRect(palette.settingsWellBg, ColorUtils.setAlphaComponent(palette.textPrimary, 18), 12));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.setMargins(dp(16), dp(14), dp(16), 0);
        box.setLayoutParams(boxLp);

        LinearLayout labels = new LinearLayout(mContext);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        TextView leftMeta = new TextView(mContext);
        leftMeta.setText("Left Pane: " + leftPct + "%");
        leftMeta.setTextColor(palette.textSecondary);
        leftMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        leftMeta.setTypeface(Typeface.MONOSPACE);
        leftMeta.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rightMeta = new TextView(mContext);
        rightMeta.setText("Right Pane: " + rightPct + "%");
        rightMeta.setTextColor(palette.textSecondary);
        rightMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        rightMeta.setTypeface(Typeface.MONOSPACE);
        labels.addView(leftMeta);
        labels.addView(rightMeta);
        box.addView(labels);

        LinearLayout panes = new LinearLayout(mContext);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams panesLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        panesLp.topMargin = dp(8);
        panes.setLayoutParams(panesLp);

        TextView leftPane = new TextView(mContext);
        leftPane.setText("zsh (" + leftPct + "%)");
        leftPane.setGravity(Gravity.CENTER);
        leftPane.setTextColor(palette.textPrimary);
        leftPane.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        leftPane.setTypeface(Typeface.MONOSPACE);
        leftPane.setBackground(roundedRect(palette.settingsActiveBg, ColorUtils.setAlphaComponent(palette.textPrimary, 45), 8));
        leftPane.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, leftPct));

        View divider = new View(mContext);
        divider.setBackground(roundedRect(palette.dividerColor, Color.TRANSPARENT, 2));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT);
        divLp.setMargins(dp(5), dp(4), dp(5), dp(4));
        divider.setLayoutParams(divLp);

        TextView rightPane = new TextView(mContext);
        rightPane.setText("htop (" + rightPct + "%)");
        rightPane.setGravity(Gravity.CENTER);
        rightPane.setTextColor(palette.textSecondary);
        rightPane.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        rightPane.setTypeface(Typeface.MONOSPACE);
        rightPane.setBackground(roundedRect(palette.settingsCardBg, ColorUtils.setAlphaComponent(palette.textPrimary, 20), 8));
        rightPane.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, rightPct));

        panes.addView(leftPane);
        panes.addView(divider);
        panes.addView(rightPane);
        box.addView(panes);
        return box;
    }

    LinearLayout createKeyValueRow(
        @NonNull String key,
        @NonNull String value,
        @NonNull TerminalChromePalette palette
    ) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView kTv = new TextView(mContext);
        kTv.setText(key);
        kTv.setTextColor(palette.textSecondary);
        kTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f);
        kTv.setLayoutParams(kLp);

        TextView vTv = new TextView(mContext);
        vTv.setText(value);
        vTv.setTextColor(palette.textPrimary);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        vTv.setTypeface(Typeface.MONOSPACE);
        vTv.setGravity(Gravity.END);
        vTv.setSingleLine(true);
        vTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        vLp.setMarginStart(dp(8));
        vTv.setLayoutParams(vLp);

        row.addView(kTv);
        row.addView(vTv);
        return row;
    }

    FrameLayout createCategoryIconPill(@androidx.annotation.DrawableRes int iconRes, boolean active, @NonNull TerminalChromePalette palette) {
        FrameLayout box = new FrameLayout(mContext);
        box.setBackground(roundedRect(
            active ? palette.textPrimary : palette.settingsActiveBg,
            Color.TRANSPARENT,
            8
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.setMarginEnd(dp(10));
        box.setLayoutParams(lp);

        ImageView iv = new ImageView(mContext);
        iv.setImageResource(iconRes);
        iv.setColorFilter(active ? palette.canvasBg : palette.textPrimary);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER);
        iv.setLayoutParams(ivLp);
        box.addView(iv);
        return box;
    }
}
