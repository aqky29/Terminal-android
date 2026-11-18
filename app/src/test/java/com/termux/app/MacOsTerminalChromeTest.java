package com.termux.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import com.termux.R;
import com.termux.app.theme.TerminalChromePalette;
import com.termux.app.theme.TerminalFonts;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MacOsTerminalChromeTest {

    @Test
    public void testBuildWindowTitleAndShortenCwd() {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        Assert.assertEquals("~", TermuxActivity.shortenCwd(home));
        Assert.assertEquals("~/src/app", TermuxActivity.shortenCwd(home + "/src/app"));
        Assert.assertEquals("/tmp", TermuxActivity.shortenCwd("/tmp"));
        Assert.assertEquals("~", TermuxActivity.shortenCwd(null));

        Assert.assertEquals("~ \u2014 bash \u2014 80\u00D724",
            TermuxActivity.buildWindowTitle(null, home, "bash", 80, 24));
        Assert.assertEquals("Server \u2014 vim \u2014 120\u00D740",
            TermuxActivity.buildWindowTitle("Server", home + "/srv", "vim", 120, 40));
        // Avoid duplicating head when shell sets OSC title to cwd
        Assert.assertEquals("~/proj \u2014 90\u00D730",
            TermuxActivity.buildWindowTitle(null, home + "/proj", "~/proj", 90, 30));
        // Omit proc when empty and omit size when 0
        Assert.assertEquals("~",
            TermuxActivity.buildWindowTitle(null, home, null, 0, 0));
    }

    @Test
    public void testTerminalFontsResolutionAndPreferences() {
        Context context = RuntimeEnvironment.getApplication();

        Typeface dejavu = TerminalFonts.getBundled(context, TERMUX_APP.VALUE_TERMINAL_FONT_DEJAVU);
        Typeface jetbrains = TerminalFonts.getBundled(context, TERMUX_APP.VALUE_TERMINAL_FONT_JETBRAINS);
        Typeface system = TerminalFonts.getBundled(context, TERMUX_APP.VALUE_TERMINAL_FONT_SYSTEM);

        Assert.assertNotNull(dejavu);
        Assert.assertNotNull(jetbrains);
        Assert.assertSame(Typeface.MONOSPACE, system);
        Assert.assertNotSame(Typeface.MONOSPACE, dejavu);
        Assert.assertNotSame(Typeface.MONOSPACE, jetbrains);
        Assert.assertNotSame(dejavu, jetbrains);

        Assert.assertEquals("DejaVu Sans Mono", TerminalFonts.getDisplayName(TERMUX_APP.VALUE_TERMINAL_FONT_DEJAVU));
        Assert.assertEquals("JetBrains Mono", TerminalFonts.getDisplayName(TERMUX_APP.VALUE_TERMINAL_FONT_JETBRAINS));
        Assert.assertEquals("System", TerminalFonts.getDisplayName(TERMUX_APP.VALUE_TERMINAL_FONT_SYSTEM));

        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, false);
        Assert.assertNotNull(prefs);
        Assert.assertEquals(TERMUX_APP.DEFAULT_VALUE_TERMINAL_FONT, prefs.getTerminalFont());
        prefs.setTerminalFont(TERMUX_APP.VALUE_TERMINAL_FONT_JETBRAINS);
        Assert.assertEquals(TERMUX_APP.VALUE_TERMINAL_FONT_JETBRAINS, prefs.getTerminalFont());
    }

    @Test
    public void testCreateTabBackgroundTopStroke() {
        TerminalChromePalette palette = TerminalChromePalette.fromTerminalColors(0xFF1E1E1E, 0xFFD4D4D4, true);

        Drawable activeBg = palette.createTabBackground(palette.topChromeBg, palette.borderHairline, 0);
        Assert.assertTrue(activeBg instanceof RippleDrawable);
        Drawable activeContent = ((RippleDrawable) activeBg).getDrawable(0);
        Assert.assertFalse(activeContent instanceof LayerDrawable);

        Drawable inactiveBg = palette.createTabBackground(palette.tabStripBg, palette.borderHairline, 2);
        Assert.assertTrue(inactiveBg instanceof RippleDrawable);
        Drawable inactiveContent = ((RippleDrawable) inactiveBg).getDrawable(0);
        Assert.assertTrue(inactiveContent instanceof LayerDrawable);
        Assert.assertEquals(2, ((LayerDrawable) inactiveContent).getNumberOfLayers());
    }

    @Test
    public void testPhoneAndTabletChromeLayoutsAndTerminalMargins() {
        Context base = new ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_TermuxActivity_DayNight_NoActionBar
        );

        // Phone layout
        View phoneRoot = LayoutInflater.from(base).inflate(R.layout.activity_termux, null);
        Assert.assertTrue(phoneRoot.findViewById(R.id.top_chrome_bar) instanceof RelativeLayout);
        Assert.assertNotNull(phoneRoot.findViewById(R.id.open_drawer_button));
        Assert.assertNotNull(phoneRoot.findViewById(R.id.top_new_session_button));
        Assert.assertNotNull(phoneRoot.findViewById(R.id.active_session_title));
        View phoneTerm = phoneRoot.findViewById(R.id.terminal_view);
        ViewGroup.MarginLayoutParams phoneLp = (ViewGroup.MarginLayoutParams) phoneTerm.getLayoutParams();
        Assert.assertTrue(phoneLp.getMarginStart() > 0);
        Assert.assertTrue(phoneLp.getMarginEnd() > 0);
        Assert.assertTrue(phoneLp.topMargin > 0);

        // Tablet sw600dp layout
        Configuration sw600 = new Configuration(base.getResources().getConfiguration());
        sw600.smallestScreenWidthDp = 600;
        Context tabletContext = new ContextThemeWrapper(
            base.createConfigurationContext(sw600),
            R.style.Theme_TermuxActivity_DayNight_NoActionBar
        );
        View tabletRoot = LayoutInflater.from(tabletContext).inflate(R.layout.activity_termux, null);
        Assert.assertNotNull(tabletRoot.findViewById(R.id.session_tabs_bar));
        Assert.assertEquals(View.VISIBLE, tabletRoot.findViewById(R.id.session_tabs_bar).getVisibility());
        Assert.assertEquals(View.GONE, tabletRoot.findViewById(R.id.top_chrome_divider).getVisibility());
        View primaryTerm = tabletRoot.findViewById(R.id.terminal_view);
        View secondaryTerm = tabletRoot.findViewById(R.id.terminal_view_secondary);
        Assert.assertTrue(((ViewGroup.MarginLayoutParams) primaryTerm.getLayoutParams()).getMarginStart() > 0);
        Assert.assertTrue(((ViewGroup.MarginLayoutParams) secondaryTerm.getLayoutParams()).getMarginStart() > 0);

        // Tab item layout
        View tabItem = LayoutInflater.from(tabletContext).inflate(R.layout.item_terminal_session_tab, null);
        ImageButton closeBtn = tabItem.findViewById(R.id.close_tab_button);
        TextView titleView = tabItem.findViewById(R.id.session_tab_title);
        TextView shortcutView = tabItem.findViewById(R.id.session_tab_shortcut);
        Assert.assertNotNull(closeBtn);
        Assert.assertNotNull(titleView);
        Assert.assertNotNull(shortcutView);
        Assert.assertEquals(View.INVISIBLE, closeBtn.getVisibility());
    }
}
