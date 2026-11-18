package com.termux.app.theme;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Computes harmonious UI chrome surface colors from a terminal background/foreground pair.
 */
public final class TerminalChromePalette {

    @ColorInt public final int canvasBg;
    @ColorInt public final int statusBg;
    @ColorInt public final int topChromeBg;
    @ColorInt public final int tabStripBg;
    @ColorInt public final int tabActiveBg;
    @ColorInt public final int tabOtherPaneBg;
    @ColorInt public final int newTabBg;
    @ColorInt public final int paneBarActiveBg;
    @ColorInt public final int paneBarInactiveBg;
    @ColorInt public final int dividerColor;
    @ColorInt public final int borderHairline;
    @ColorInt public final int extraKeysBg;
    @ColorInt public final int extraKeysActiveBg;
    @ColorInt public final int drawerBg;
    @ColorInt public final int textPrimary;
    @ColorInt public final int textSecondary;
    @ColorInt public final int settingsSidebarBg;
    @ColorInt public final int settingsDetailBg;
    @ColorInt public final int settingsCardBg;
    @ColorInt public final int settingsWellBg;
    @ColorInt public final int settingsActiveBg;
    @ColorInt public final int frameBorder;
    @ColorInt public final int rippleColor;
    public final boolean isLightBackground;

    private TerminalChromePalette(
        int canvasBg, int statusBg, int topChromeBg, int tabStripBg, int tabActiveBg,
        int tabOtherPaneBg, int newTabBg, int paneBarActiveBg, int paneBarInactiveBg,
        int dividerColor, int borderHairline, int extraKeysBg, int extraKeysActiveBg,
        int drawerBg, int textPrimary, int textSecondary, int settingsSidebarBg,
        int settingsDetailBg, int settingsCardBg, int settingsWellBg, int settingsActiveBg,
        int frameBorder, int rippleColor, boolean isLightBackground
    ) {
        this.canvasBg = canvasBg;
        this.statusBg = statusBg;
        this.topChromeBg = topChromeBg;
        this.tabStripBg = tabStripBg;
        this.tabActiveBg = tabActiveBg;
        this.tabOtherPaneBg = tabOtherPaneBg;
        this.newTabBg = newTabBg;
        this.paneBarActiveBg = paneBarActiveBg;
        this.paneBarInactiveBg = paneBarInactiveBg;
        this.dividerColor = dividerColor;
        this.borderHairline = borderHairline;
        this.extraKeysBg = extraKeysBg;
        this.extraKeysActiveBg = extraKeysActiveBg;
        this.drawerBg = drawerBg;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.settingsSidebarBg = settingsSidebarBg;
        this.settingsDetailBg = settingsDetailBg;
        this.settingsCardBg = settingsCardBg;
        this.settingsWellBg = settingsWellBg;
        this.settingsActiveBg = settingsActiveBg;
        this.frameBorder = frameBorder;
        this.rippleColor = rippleColor;
        this.isLightBackground = isLightBackground;
    }

    @NonNull
    public static TerminalChromePalette fromTerminalColors(@ColorInt int backgroundColor, @ColorInt int foregroundColor, boolean syncChrome) {
        int bg = backgroundColor | 0xFF000000;
        int fg = foregroundColor | 0xFF000000;
        boolean light = ColorUtils.calculateLuminance(bg) > 0.5;

        if (!syncChrome) {
            return new TerminalChromePalette(
                bg,
                0xFF2E2E2E,
                0xFF323232,
                0xFF252525,
                0xFF383838,
                0xFF2C2C2C,
                0xFF282828,
                0xFF2D2D2D,
                0xFF212121,
                0xFF383838,
                0xFF1A1A1A,
                0xFF252525,
                0xFF383838,
                0xFF1E1E1E,
                0xFFEBEBEB,
                0xFF969696,
                0xFF212121,
                0xFF1A1A1A,
                0xFF232323,
                0xFF161616,
                0xFF333333,
                0xFF383838,
                0x28FFFFFF,
                false
            );
        }

        // Shade relative to canvas bg so custom themes (Nord, Solarized, AMOLED, Light, etc.) harmonize naturally.
        int liftTarget = light ? 0xFF000000 : 0xFFFFFFFF;
        int sinkTarget = light ? 0xFFFFFFFF : 0xFF000000;

        int statusBg = blendOpaque(bg, sinkTarget, 0.14f);
        int topChromeBg = blendOpaque(bg, liftTarget, 0.09f);
        int tabStripBg = blendOpaque(bg, sinkTarget, 0.08f);
        int tabActiveBg = blendOpaque(bg, liftTarget, 0.15f);
        int tabOtherPaneBg = blendOpaque(bg, liftTarget, 0.07f);
        int newTabBg = blendOpaque(bg, liftTarget, 0.03f);
        int paneBarActiveBg = blendOpaque(bg, liftTarget, 0.08f);
        int paneBarInactiveBg = blendOpaque(bg, sinkTarget, 0.06f);
        int dividerColor = blendOpaque(bg, liftTarget, 0.16f);
        int borderHairline = light
            ? blendOpaque(bg, 0xFF000000, 0.14f)
            : (bg == 0xFF000000 ? 0xFF1C1C1C : blendOpaque(bg, 0xFF000000, 0.30f));
        int extraKeysBg = blendOpaque(bg, liftTarget, 0.06f);
        int extraKeysActiveBg = tabActiveBg;
        int drawerBg = blendOpaque(bg, sinkTarget, 0.18f);

        int settingsSidebarBg = blendOpaque(bg, sinkTarget, 0.10f);
        int settingsDetailBg = bg;
        int settingsCardBg = blendOpaque(bg, liftTarget, 0.065f);
        int settingsWellBg = blendOpaque(bg, sinkTarget, 0.22f);
        int settingsActiveBg = blendOpaque(bg, liftTarget, 0.14f);
        int frameBorder = blendOpaque(bg, liftTarget, 0.16f);

        int textPri = ensureReadableText(fg, topChromeBg, light);
        int textSec = blendOpaque(textPri, topChromeBg, 0.36f);
        int ripple = light ? 0x1F000000 : 0x2AFFFFFF;

        return new TerminalChromePalette(
            bg, statusBg, topChromeBg, tabStripBg, tabActiveBg,
            tabOtherPaneBg, newTabBg, paneBarActiveBg, paneBarInactiveBg,
            dividerColor, borderHairline, extraKeysBg, extraKeysActiveBg,
            drawerBg, textPri, textSec, settingsSidebarBg, settingsDetailBg,
            settingsCardBg, settingsWellBg, settingsActiveBg, frameBorder, ripple, light
        );
    }

    @NonNull
    public static TerminalChromePalette fromCurrentColorsFile(boolean syncChrome) {
        int bg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        int fg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND];

        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            if (colorsFile.isFile()) {
                Properties props = new Properties();
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
                String bgHex = props.getProperty("background");
                String fgHex = props.getProperty("foreground");
                if (bgHex != null && !bgHex.trim().isEmpty()) {
                    bg = Color.parseColor(bgHex.trim());
                }
                if (fgHex != null && !fgHex.trim().isEmpty()) {
                    fg = Color.parseColor(fgHex.trim());
                }
            }
        } catch (Exception ignored) {
        }

        if (bg == 0) bg = 0xFF000000;
        if (fg == 0) fg = 0xFFFFFFFF;
        return fromTerminalColors(bg, fg, syncChrome);
    }

    @NonNull
    public Drawable createRippleBackground(@ColorInt int fillColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), new ColorDrawable(fillColor), null);
    }

    @ColorInt
    private static int blendOpaque(@ColorInt int c1, @ColorInt int c2, float ratio) {
        return ColorUtils.blendARGB(c1 | 0xFF000000, c2 | 0xFF000000, ratio) | 0xFF000000;
    }

    private static int ensureReadableText(@ColorInt int fg, @ColorInt int surfaceBg, boolean isLightSurface) {
        int opaqueFg = fg | 0xFF000000;
        int opaqueBg = surfaceBg | 0xFF000000;
        double contrast = ColorUtils.calculateContrast(opaqueFg, opaqueBg);
        if (contrast >= 4.5) return opaqueFg;
        return isLightSurface ? 0xFF1A1A1A : 0xFFEBEBEB;
    }
}
