package com.termux.app.theme;

import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves and caches terminal monospace fonts (custom ~/.termux/font.ttf, bundled fonts, or system fallback).
 */
public final class TerminalFonts {

    private static final String ASSET_DEJAVU = "fonts/DejaVuSansMono.ttf";
    private static final String ASSET_JETBRAINS = "fonts/JetBrainsMono-Regular.ttf";

    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private TerminalFonts() {
    }

    /**
     * Resolves the active terminal typeface in priority order:
     * 1) ~/.termux/font.ttf (if present and non-empty),
     * 2) bundled font matching {@code fontId},
     * 3) {@link Typeface#MONOSPACE}.
     */
    @NonNull
    public static Typeface resolve(@NonNull Context context, @Nullable String fontId) {
        File customFont = TermuxConstants.TERMUX_FONT_FILE;
        if (customFont.isFile() && customFont.length() > 0) {
            try {
                return Typeface.createFromFile(customFont);
            } catch (Exception ignored) {
            }
        }
        return getBundled(context, fontId);
    }

    /**
     * Returns the cached bundled typeface for {@code fontId}, or {@link Typeface#MONOSPACE} for system/unknown.
     */
    @NonNull
    public static Typeface getBundled(@NonNull Context context, @Nullable String fontId) {
        String assetPath = assetFor(fontId);
        if (assetPath == null) {
            return Typeface.MONOSPACE;
        }
        synchronized (CACHE) {
            Typeface cached = CACHE.get(assetPath);
            if (cached == null) {
                try {
                    cached = Typeface.createFromAsset(context.getAssets(), assetPath);
                } catch (Exception e) {
                    cached = Typeface.MONOSPACE;
                }
                CACHE.put(assetPath, cached);
            }
            return cached;
        }
    }

    /** Returns true if a non-empty user custom font exists at {@code ~/.termux/font.ttf}. */
    public static boolean hasCustomFontFile() {
        File customFont = TermuxConstants.TERMUX_FONT_FILE;
        return customFont.isFile() && customFont.length() > 0;
    }

    /** Returns a human-readable display name for {@code fontId}. */
    @NonNull
    public static String getDisplayName(@Nullable String fontId) {
        if (TERMUX_APP.VALUE_TERMINAL_FONT_JETBRAINS.equals(fontId)) {
            return "JetBrains Mono";
        }
        if (TERMUX_APP.VALUE_TERMINAL_FONT_SYSTEM.equals(fontId)) {
            return "System";
        }
        return "DejaVu Sans Mono";
    }

    @Nullable
    private static String assetFor(@Nullable String fontId) {
        if (TERMUX_APP.VALUE_TERMINAL_FONT_SYSTEM.equals(fontId)) {
            return null;
        }
        if (TERMUX_APP.VALUE_TERMINAL_FONT_JETBRAINS.equals(fontId)) {
            return ASSET_JETBRAINS;
        }
        return ASSET_DEJAVU;
    }
}
