package com.termux.app.theme;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Built-in terminal color presets and config file helpers (~/.termux/colors.properties and termux.properties).
 */
public final class TerminalThemePresets {

    private static final String LOG_TAG = "TerminalThemePresets";

    public static final class ThemePreset {
        public final String id;
        public final String name;
        public final String hexLabel;
        @ColorInt public final int bg;
        @ColorInt public final int fg;
        @ColorInt public final int muted;
        @ColorInt public final int accent;
        @ColorInt public final int cursor;
        public final String[] palette16;

        public ThemePreset(
            String id, String name, String hexLabel,
            String bgHex, String fgHex, String mutedHex, String accentHex, String cursorHex,
            String[] palette16
        ) {
            this.id = id;
            this.name = name;
            this.hexLabel = hexLabel;
            this.bg = Color.parseColor(bgHex);
            this.fg = Color.parseColor(fgHex);
            this.muted = Color.parseColor(mutedHex);
            this.accent = Color.parseColor(accentHex);
            this.cursor = Color.parseColor(cursorHex);
            this.palette16 = palette16;
        }
    }

    private static final List<ThemePreset> PRESETS = Collections.unmodifiableList(Arrays.asList(
        new ThemePreset(
            "dark-plus", "Dark+ Charcoal", "#1E1E1E",
            "#1E1E1E", "#E8E8E8", "#8D8D8D", "#D4D4D4", "#EBEBEB",
            new String[]{
                "#1E1E1E", "#F44747", "#6A9955", "#D7BA7D", "#569CD6", "#C586C0", "#4EC9B0", "#D4D4D4",
                "#808080", "#F44747", "#6A9955", "#D7BA7D", "#569CD6", "#C586C0", "#4EC9B0", "#FFFFFF"
            }
        ),
        new ThemePreset(
            "pure-black", "AMOLED Black", "#000000",
            "#000000", "#F0F0F0", "#848484", "#CFCFCF", "#FFFFFF",
            new String[]{
                "#000000", "#FF5555", "#50FA7B", "#F1FA8C", "#6272A4", "#BD93F9", "#8BE9FD", "#DFDFDF",
                "#555555", "#FF6E6E", "#69FF94", "#FFFFA5", "#7B8CC4", "#D6ACFF", "#A4FFFF", "#FFFFFF"
            }
        ),
        new ThemePreset(
            "macos-dark", "macOS Basic", "#232323",
            "#232323", "#F2F2F2", "#9A9A9A", "#DCDCDC", "#D4D4D4",
            new String[]{
                "#232323", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#DCDCDC",
                "#666666", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#FFFFFF"
            }
        ),
        new ThemePreset(
            "solarized-dark", "Solarized Dark", "#002B36",
            "#002B36", "#93A1A1", "#586E75", "#B58900", "#839496",
            new String[]{
                "#073642", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
                "#002B36", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3"
            }
        ),
        new ThemePreset(
            "nord", "Nord Polar", "#2E3440",
            "#2E3440", "#ECEFF4", "#7B88A1", "#88C0D0", "#D8DEE9",
            new String[]{
                "#3B4252", "#BF616A", "#A3BE8C", "#EBCB8B", "#81A1C1", "#B48EAD", "#88C0D0", "#E5E9F0",
                "#4C566A", "#BF616A", "#A3BE8C", "#EBCB8B", "#81A1C1", "#B48EAD", "#8FBCBB", "#ECEFF4"
            }
        ),
        new ThemePreset(
            "one-dark", "One Dark", "#282C34",
            "#282C34", "#ABB2BF", "#6B7280", "#E5C07B", "#DCDFE4",
            new String[]{
                "#282C34", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF",
                "#5C6370", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#FFFFFF"
            }
        )
    ));

    private TerminalThemePresets() {
    }

    @NonNull
    public static List<ThemePreset> getPresets() {
        return PRESETS;
    }

    @NonNull
    public static ThemePreset getPresetOrDefault(@Nullable String id) {
        if (id != null) {
            for (ThemePreset preset : PRESETS) {
                if (preset.id.equals(id)) return preset;
            }
        }
        return PRESETS.get(0);
    }

    public static void applyPreset(@NonNull Context context, @NonNull ThemePreset preset) {
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File parentDir = colorsFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parentDir.mkdirs();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Managed by Terminal Settings (").append(preset.name).append(")\n");
            sb.append("background=").append(toHex(preset.bg)).append('\n');
            sb.append("foreground=").append(toHex(preset.fg)).append('\n');
            sb.append("cursor=").append(toHex(preset.cursor)).append('\n');
            for (int i = 0; i < preset.palette16.length; i++) {
                sb.append("color").append(i).append('=').append(preset.palette16[i]).append('\n');
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(colorsFile, false), StandardCharsets.UTF_8)) {
                writer.write(sb.toString());
            }

            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context);
            if (prefs != null) {
                prefs.setColorPreset(preset.id);
            }
            TermuxActivity.updateTermuxActivityStyling(context, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply theme preset: " + preset.id, e);
        }
    }

    @NonNull
    public static String readTermuxProperty(@NonNull String key, @NonNull String defaultValue) {
        try {
            File file = getPrimaryOrExistingPropertiesFile();
            if (file == null || !file.isFile()) return defaultValue;
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            }
            String val = props.getProperty(key);
            return val != null ? val.trim() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static void writeTermuxProperty(@NonNull Context context, @NonNull String key, @NonNull String value) {
        try {
            File file = getWritablePropertiesFile();
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parentDir.mkdirs();
            }

            List<String> lines = new ArrayList<>();
            boolean updated = false;
            if (file.isFile()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("#") && !trimmed.isEmpty()) {
                            int eqIdx = trimmed.indexOf('=');
                            int colonIdx = trimmed.indexOf(':');
                            int sepIdx = (eqIdx >= 0 && colonIdx >= 0) ? Math.min(eqIdx, colonIdx) : Math.max(eqIdx, colonIdx);
                            if (sepIdx > 0 && trimmed.substring(0, sepIdx).trim().equals(key)) {
                                if (!updated) {
                                    lines.add(key + " = " + value);
                                    updated = true;
                                }
                                continue;
                            }
                        }
                        lines.add(line);
                    }
                }
            }
            if (!updated) {
                lines.add(key + " = " + value);
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
                for (int i = 0; i < lines.size(); i++) {
                    writer.write(lines.get(i));
                    writer.write('\n');
                }
            }
            TermuxActivity.updateTermuxActivityStyling(context, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write termux property: " + key, e);
        }
    }

    public static void resetDefaults(@NonNull Context context, @NonNull TermuxAppSharedPreferences prefs) {
        prefs.setSyncChromeThemeEnabled(TERMUX_APP.DEFAULT_VALUE_SYNC_CHROME_THEME);
        prefs.setUiAnimationsEnabled(TERMUX_APP.DEFAULT_VALUE_UI_ANIMATIONS_ENABLED);
        prefs.setShowSplitPaneHeader(TERMUX_APP.DEFAULT_VALUE_SHOW_SPLIT_PANE_HEADER);
        prefs.setDefaultSplitRatio(TERMUX_APP.DEFAULT_VALUE_DEFAULT_SPLIT_RATIO);
        prefs.setTerminalMarginAdjustment(TERMUX_APP.DEFAULT_TERMINAL_MARGIN_ADJUSTMENT);
        prefs.setShowTerminalToolbar(TERMUX_APP.DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR);
        prefs.setKeepScreenOn(TERMUX_APP.DEFAULT_VALUE_KEEP_SCREEN_ON);
        prefs.setSoftKeyboardEnabled(TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED);
        prefs.setSoftKeyboardEnabledOnlyIfNoHardware(TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE);
        prefs.setFontSize(TermuxAppSharedPreferences.getDefaultFontSizes(context)[0]);
        prefs.setTerminalViewKeyLoggingEnabled(TERMUX_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED);
        prefs.setPluginErrorNotificationsEnabled(TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
        prefs.setCrashReportNotificationsEnabled(TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
        prefs.setLogLevel(context, Logger.DEFAULT_LOG_LEVEL);

        writeTermuxProperty(context, TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, TermuxPropertyConstants.VALUE_TERMINAL_CURSOR_STYLE_BLOCK);
        writeTermuxProperty(context, TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, "0");
        writeTermuxProperty(context, TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, TermuxPropertyConstants.DEFAULT_VALUE_BELL_BEHAVIOUR);
        writeTermuxProperty(context, TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR, TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR);

        applyPreset(context, PRESETS.get(0));
    }

    @Nullable
    private static File getPrimaryOrExistingPropertiesFile() {
        File primary = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        if (primary.isFile()) return primary;
        File secondary = TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE;
        return secondary.isFile() ? secondary : null;
    }

    @NonNull
    private static File getWritablePropertiesFile() {
        File primary = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        if (primary.isFile() || !TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE.isFile()) {
            return primary;
        }
        return TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE;
    }

    @NonNull
    private static String toHex(@ColorInt int color) {
        return String.format("#%06X", (0xFFFFFF & color));
    }
}
