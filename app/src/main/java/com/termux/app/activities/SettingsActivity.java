package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.Fade;
import android.transition.Slide;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.ImageViewCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.SettingsUiBuilder.SegmentedOption;
import com.termux.app.models.UserAction;
import com.termux.app.theme.TerminalChromePalette;
import com.termux.app.theme.TerminalFonts;
import com.termux.app.theme.TerminalThemePresets;
import com.termux.app.theme.TerminalThemePresets.ThemePreset;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.models.ReportInfo;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.theme.NightMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Responsive Settings screen: Master-Detail on sw600dp tablets, Drill-Down on phones.
 * Harmonizes dynamically with the active terminal color scheme.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String STATE_CATEGORY = "settings_category";
    private static final String STATE_IN_DETAIL = "settings_phone_in_detail";
    private static final String STATE_QUERY = "settings_query";

    private static final class CategoryItem {
        final String id;
        final String group;
        final String title;
        final String subtitle;
        @DrawableRes final int iconRes;
        final String keywords;

        CategoryItem(String id, String group, String title, String subtitle, @DrawableRes int iconRes, String keywords) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.subtitle = subtitle;
            this.iconRes = iconRes;
            this.keywords = keywords;
        }
    }

    private final List<CategoryItem> mCategories = Arrays.asList(
        new CategoryItem(
            "appearance", "Workspace", "Appearance", "Color theme, font & cursor",
            R.drawable.ic_settings_appearance,
            "theme colors background foreground dark nord solarized amoled font dejavu jetbrains menlo size cursor style blink colors.properties font.ttf"
        ),
        new CategoryItem(
            "terminal-view", "Workspace", "Terminal View", "Margin, Extra Keys & bell",
            R.drawable.ic_settings_terminal_view,
            "margin adjustment extra keys toolbar keep screen on bell vibrate beep"
        ),
        new CategoryItem(
            "keyboard-io", "Input & Layout", "Keyboard & IO", "Soft keyboard, hardware & back key",
            R.drawable.ic_keyboard,
            "soft keyboard hardware bluetooth usb back key escape input"
        ),
        new CategoryItem(
            "tabs-split", "Input & Layout", "Tabs, Split & Motion", "Animations & tablet split ratio",
            R.drawable.ic_settings_split,
            "split panel ratio divider animations motion transition active pane header"
        ),
        new CategoryItem(
            "debugging", "System", "Debugging", "Log level, key logs & crash alerts",
            R.drawable.ic_settings_debug,
            "log level logcat verbose key logging plugin error crash report notifications"
        ),
        new CategoryItem(
            "about", "System", "About Terminal", "Version, package & config paths",
            R.drawable.ic_settings_about,
            "about version com.termux bootstrap github license report paths"
        )
    );

    private TermuxAppSharedPreferences mPreferences;
    private SettingsUiBuilder mUi;
    private TerminalChromePalette mPalette;

    private String mActiveCategoryId = "appearance";
    private boolean mPhoneInDetail = false;
    private String mSearchQuery = "";
    private boolean mIsTabletLayout = false;

    private View mSettingsRoot;
    private View mSidebarView;
    private LinearLayout mNavList;
    private View mDetailColumn;
    private ScrollView mDetailScroll;
    private LinearLayout mDetailContainer;
    private EditText mSearchInput;

    // Tablet-only views
    @Nullable private TextView mSidebarVersion;
    @Nullable private View mSidebarTopDivider;
    @Nullable private View mSidebarDivider;
    @Nullable private View mTabletDetailHeader;
    @Nullable private View mDetailHeaderDivider;
    @Nullable private View mDetailHeaderIconBox;
    @Nullable private ImageView mDetailHeaderIcon;
    @Nullable private TextView mDetailHeaderTitle;
    @Nullable private TextView mDetailHeaderSubtitle;

    // Phone-only views
    @Nullable private View mPhoneTopBar;
    @Nullable private View mPhoneTopDivider;
    @Nullable private ImageButton mPhoneBackButton;
    @Nullable private TextView mPhoneTitle;

    // Shared header controls
    @Nullable private TextView mResetButton;
    @Nullable private TextView mDoneButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        setContentView(R.layout.activity_settings);

        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            finish();
            return;
        }
        mUi = new SettingsUiBuilder(this);

        if (savedInstanceState != null) {
            mActiveCategoryId = savedInstanceState.getString(STATE_CATEGORY, "appearance");
            mPhoneInDetail = savedInstanceState.getBoolean(STATE_IN_DETAIL, false);
            mSearchQuery = savedInstanceState.getString(STATE_QUERY, "");
        }

        bindViews();
        wireEvents();
        refreshAll(false);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CATEGORY, mActiveCategoryId);
        outState.putBoolean(STATE_IN_DETAIL, mPhoneInDetail);
        outState.putString(STATE_QUERY, mSearchQuery);
    }

    @Override
    public void onBackPressed() {
        if (!mIsTabletLayout && mPhoneInDetail) {
            mPhoneInDetail = false;
            refreshAll(true);
        } else {
            super.onBackPressed();
        }
    }

    private void bindViews() {
        mSettingsRoot = findViewById(R.id.settings_root);
        mSidebarView = findViewById(R.id.settings_sidebar);
        mNavList = findViewById(R.id.settings_nav_list);
        mDetailColumn = findViewById(R.id.settings_detail_column);
        mDetailScroll = findViewById(R.id.settings_detail_scroll);
        mDetailContainer = findViewById(R.id.settings_detail_container);
        mSearchInput = findViewById(R.id.settings_search);

        mTabletDetailHeader = findViewById(R.id.settings_tablet_detail_header);
        mIsTabletLayout = (mTabletDetailHeader != null);

        mSidebarVersion = findViewById(R.id.settings_sidebar_version);
        mSidebarTopDivider = findViewById(R.id.settings_sidebar_top_divider);
        mSidebarDivider = findViewById(R.id.settings_sidebar_divider);
        mDetailHeaderDivider = findViewById(R.id.settings_detail_header_divider);
        mDetailHeaderIconBox = findViewById(R.id.settings_detail_header_icon_box);
        mDetailHeaderIcon = findViewById(R.id.settings_detail_header_icon);
        mDetailHeaderTitle = findViewById(R.id.settings_detail_header_title);
        mDetailHeaderSubtitle = findViewById(R.id.settings_detail_header_subtitle);

        mPhoneTopBar = findViewById(R.id.settings_phone_topbar);
        mPhoneTopDivider = findViewById(R.id.settings_topbar_divider);
        mPhoneBackButton = findViewById(R.id.settings_phone_back);
        mPhoneTitle = findViewById(R.id.settings_phone_title);

        mResetButton = findViewById(R.id.settings_reset_button);
        mDoneButton = findViewById(R.id.settings_done_button);

        if (mSidebarVersion != null) {
            mSidebarVersion.setText("v" + BuildConfig.VERSION_NAME);
        }
        if (mSearchInput != null && !TextUtils.isEmpty(mSearchQuery)) {
            mSearchInput.setText(mSearchQuery);
        }
    }

    private void wireEvents() {
        if (mPhoneBackButton != null) {
            mPhoneBackButton.setOnClickListener(v -> onBackPressed());
        }
        if (mDoneButton != null) {
            mDoneButton.setOnClickListener(v -> finish());
        }
        if (mResetButton != null) {
            mResetButton.setOnClickListener(v -> confirmResetDefaults());
        }
        if (mSearchInput != null) {
            mSearchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    mSearchQuery = s != null ? s.toString() : "";
                    renderNavigation();
                    if (mIsTabletLayout) {
                        renderDetail();
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }
    }

    private void refreshAll(boolean animateTransition) {
        mPalette = TerminalChromePalette.fromCurrentColorsFile(mPreferences.isSyncChromeThemeEnabled());
        applyShellChrome();

        if (!mIsTabletLayout && animateTransition && mPreferences.isUiAnimationsEnabled()) {
            ViewGroup contentFrame = findViewById(R.id.settings_content_frame);
            if (contentFrame != null) {
                TransitionSet ts = new TransitionSet()
                    .addTransition(new Fade())
                    .addTransition(new Slide(mPhoneInDetail ? Gravity.END : Gravity.START))
                    .setDuration(160)
                    .setInterpolator(new DecelerateInterpolator());
                TransitionManager.beginDelayedTransition(contentFrame, ts);
            }
        }

        if (!mIsTabletLayout) {
            mSidebarView.setVisibility(mPhoneInDetail ? View.GONE : View.VISIBLE);
            mDetailColumn.setVisibility(mPhoneInDetail ? View.VISIBLE : View.GONE);
            CategoryItem active = getActiveCategory();
            if (mPhoneTitle != null) {
                mPhoneTitle.setText(mPhoneInDetail ? active.title : getString(R.string.settings_header_title));
            }
        }

        renderNavigation();
        renderDetail();
    }

    private void applyShellChrome() {
        if (getWindow() != null) {
            getWindow().setStatusBarColor(mPalette.statusBg);
            getWindow().setNavigationBarColor(mPalette.settingsDetailBg);
            WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (insets != null) {
                insets.setAppearanceLightStatusBars(mPalette.isLightBackground);
                insets.setAppearanceLightNavigationBars(mPalette.isLightBackground);
            }
        }

        if (mSettingsRoot != null) mSettingsRoot.setBackgroundColor(mPalette.settingsDetailBg);
        if (mSidebarView != null) {
            mSidebarView.setBackgroundColor(mIsTabletLayout ? mPalette.settingsSidebarBg : mPalette.settingsDetailBg);
        }
        if (mDetailColumn != null) mDetailColumn.setBackgroundColor(mPalette.settingsDetailBg);

        if (mSidebarTopDivider != null) mSidebarTopDivider.setBackgroundColor(mPalette.borderHairline);
        if (mSidebarDivider != null) mSidebarDivider.setBackgroundColor(mPalette.borderHairline);
        if (mDetailHeaderDivider != null) mDetailHeaderDivider.setBackgroundColor(mPalette.borderHairline);
        if (mPhoneTopDivider != null) mPhoneTopDivider.setBackgroundColor(mPalette.borderHairline);

        if (mTabletDetailHeader != null) mTabletDetailHeader.setBackgroundColor(mPalette.topChromeBg);
        if (mPhoneTopBar != null) mPhoneTopBar.setBackgroundColor(mPalette.topChromeBg);

        TextView sidebarTitle = findViewById(R.id.settings_sidebar_title);
        if (sidebarTitle != null) sidebarTitle.setTextColor(mPalette.textPrimary);
        ImageView brandIcon = findViewById(R.id.settings_sidebar_brand_icon);
        if (brandIcon != null) ImageViewCompat.setImageTintList(brandIcon, ColorStateList.valueOf(mPalette.textPrimary));
        View brandBox = findViewById(R.id.settings_sidebar_brand_icon_box);
        if (brandBox != null) brandBox.setBackground(mUi.roundedRect(mPalette.settingsActiveBg, ColorUtils.setAlphaComponent(mPalette.textPrimary, 24), 7));
        if (mSidebarVersion != null) {
            mSidebarVersion.setTextColor(mPalette.textSecondary);
            mSidebarVersion.setBackground(mUi.roundedRect(mPalette.settingsCardBg, Color.TRANSPARENT, 6));
        }

        View searchBox = findViewById(R.id.settings_search_box);
        if (searchBox != null) {
            searchBox.setBackground(mUi.roundedRect(mPalette.settingsWellBg, ColorUtils.setAlphaComponent(mPalette.textPrimary, 22), 10));
        }
        ImageView searchIcon = findViewById(R.id.settings_search_icon);
        if (searchIcon != null) ImageViewCompat.setImageTintList(searchIcon, ColorStateList.valueOf(mPalette.textSecondary));
        if (mSearchInput != null) {
            mSearchInput.setTextColor(mPalette.textPrimary);
            mSearchInput.setHintTextColor(mPalette.textSecondary);
        }

        if (mPhoneBackButton != null) {
            ImageViewCompat.setImageTintList(mPhoneBackButton, ColorStateList.valueOf(mPalette.textPrimary));
        }
        if (mPhoneTitle != null) mPhoneTitle.setTextColor(mPalette.textPrimary);

        if (mDetailHeaderIconBox != null) {
            mDetailHeaderIconBox.setBackground(mUi.roundedRect(mPalette.settingsActiveBg, ColorUtils.setAlphaComponent(mPalette.textPrimary, 24), 8));
        }
        if (mDetailHeaderIcon != null) {
            ImageViewCompat.setImageTintList(mDetailHeaderIcon, ColorStateList.valueOf(mPalette.textPrimary));
        }
        if (mDetailHeaderTitle != null) mDetailHeaderTitle.setTextColor(mPalette.textPrimary);
        if (mDetailHeaderSubtitle != null) mDetailHeaderSubtitle.setTextColor(mPalette.textSecondary);

        if (mResetButton != null) {
            mResetButton.setTextColor(mPalette.textSecondary);
            mResetButton.setBackground(mUi.roundedRect(mPalette.settingsCardBg, ColorUtils.setAlphaComponent(mPalette.textPrimary, 24), 8));
        }
        if (mDoneButton != null) {
            mDoneButton.setTextColor(mPalette.canvasBg);
            mDoneButton.setBackground(mUi.roundedRect(mPalette.textPrimary, Color.TRANSPARENT, 8));
        }
    }

    private CategoryItem getActiveCategory() {
        List<CategoryItem> filtered = getFilteredCategories();
        for (CategoryItem item : filtered) {
            if (item.id.equals(mActiveCategoryId)) return item;
        }
        if (!filtered.isEmpty()) {
            mActiveCategoryId = filtered.get(0).id;
            return filtered.get(0);
        }
        return mCategories.get(0);
    }

    private List<CategoryItem> getFilteredCategories() {
        String q = mSearchQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return mCategories;
        List<CategoryItem> out = new ArrayList<>();
        for (CategoryItem item : mCategories) {
            if (item.title.toLowerCase(Locale.ROOT).contains(q)
                || item.subtitle.toLowerCase(Locale.ROOT).contains(q)
                || item.keywords.contains(q)) {
                out.add(item);
            }
        }
        return out;
    }

    private String getCategorySummaryBadge(@NonNull String id) {
        switch (id) {
            case "appearance": {
                ThemePreset preset = TerminalThemePresets.getPresetOrDefault(mPreferences.getColorPreset());
                String firstWord = preset.name.split(" ")[0];
                String fontShort = TerminalFonts.hasCustomFontFile()
                    ? "font.ttf"
                    : TerminalFonts.getDisplayName(mPreferences.getTerminalFont()).split(" ")[0];
                return firstWord + " \u2022 " + fontShort + " \u2022 " + mPreferences.getFontSize() + "sp";
            }
            case "terminal-view":
                return mPreferences.shouldShowTerminalToolbar() ? "Extra Keys On" : "Extra Keys Off";
            case "keyboard-io":
                if (!mPreferences.isSoftKeyboardEnabled()) return "Off";
                return mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware() ? "Auto HW" : "Soft On";
            case "tabs-split": {
                int ratio = mPreferences.getDefaultSplitRatio();
                return "Split " + ratio + "/" + (100 - ratio);
            }
            case "debugging": {
                int lvl = mPreferences.getLogLevel();
                if (lvl <= 0) return "Off";
                if (lvl == 1) return "Normal";
                if (lvl == 2) return "Verbose";
                return "Full";
            }
            case "about":
            default:
                return TermuxConstants.TERMUX_PACKAGE_NAME;
        }
    }

    private void renderNavigation() {
        mNavList.removeAllViews();
        List<CategoryItem> filtered = getFilteredCategories();
        CategoryItem active = getActiveCategory();

        if (filtered.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No matching settings found.");
            empty.setTextColor(mPalette.textSecondary);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(mUi.dp(12), mUi.dp(32), mUi.dp(12), mUi.dp(32));
            mNavList.addView(empty);
            return;
        }

        String[] groups = {"Workspace", "Input & Layout", "System"};
        for (String groupName : groups) {
            List<CategoryItem> groupItems = new ArrayList<>();
            for (CategoryItem item : filtered) {
                if (groupName.equals(item.group)) groupItems.add(item);
            }
            if (groupItems.isEmpty()) continue;

            mNavList.addView(mUi.createSectionTitle(groupName, mPalette));

            LinearLayout container = mIsTabletLayout ? new LinearLayout(this) : mUi.createCard(mPalette);
            if (mIsTabletLayout) {
                container.setOrientation(LinearLayout.VERTICAL);
            }

            for (int i = 0; i < groupItems.size(); i++) {
                CategoryItem item = groupItems.get(i);
                boolean selected = mIsTabletLayout && item.id.equals(active.id);
                if (!mIsTabletLayout && i > 0) {
                    container.addView(mUi.createDivider(mPalette));
                }

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(mUi.dp(mIsTabletLayout ? 50 : 58));
                row.setPadding(mUi.dp(12), mUi.dp(8), mUi.dp(12), mUi.dp(8));
                row.setClickable(true);
                row.setFocusable(true);
                if (mIsTabletLayout) {
                    LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rLp.bottomMargin = mUi.dp(3);
                    row.setLayoutParams(rLp);
                    if (selected) {
                        row.setBackground(mUi.roundedRect(mPalette.settingsActiveBg, ColorUtils.setAlphaComponent(mPalette.textPrimary, 28), 11));
                    } else {
                        row.setBackground(mPalette.createRippleBackground(Color.TRANSPARENT));
                    }
                } else {
                    row.setBackground(mPalette.createRippleBackground(Color.TRANSPARENT));
                }

                row.addView(mUi.createCategoryIconPill(item.iconRes, selected, mPalette));

                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView tTv = new TextView(this);
                tTv.setText(item.title);
                tTv.setTextColor(mPalette.textPrimary);
                tTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tTv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
                tTv.setSingleLine(true);
                tTv.setEllipsize(TextUtils.TruncateAt.END);

                TextView sTv = new TextView(this);
                sTv.setText(mIsTabletLayout ? getCategorySummaryBadge(item.id) : item.subtitle);
                sTv.setTextColor(mPalette.textSecondary);
                sTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                sTv.setSingleLine(true);
                sTv.setEllipsize(TextUtils.TruncateAt.END);

                textCol.addView(tTv);
                textCol.addView(sTv);
                row.addView(textCol);

                if (!mIsTabletLayout) {
                    TextView badgeTv = new TextView(this);
                    badgeTv.setText(getCategorySummaryBadge(item.id));
                    badgeTv.setTextColor(mPalette.textSecondary);
                    badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    bLp.setMarginStart(mUi.dp(8));
                    bLp.setMarginEnd(mUi.dp(4));
                    badgeTv.setLayoutParams(bLp);
                    row.addView(badgeTv);

                    ImageView chevron = new ImageView(this);
                    chevron.setImageResource(R.drawable.ic_chevron_right);
                    chevron.setColorFilter(mPalette.textSecondary);
                    chevron.setLayoutParams(new LinearLayout.LayoutParams(mUi.dp(16), mUi.dp(16)));
                    row.addView(chevron);
                }

                row.setOnClickListener(v -> {
                    mActiveCategoryId = item.id;
                    if (!mIsTabletLayout) {
                        mPhoneInDetail = true;
                        refreshAll(true);
                    } else {
                        renderNavigation();
                        renderDetail();
                    }
                });

                container.addView(row);
            }

            mNavList.addView(container);
        }
    }

    private void renderDetail() {
        CategoryItem active = getActiveCategory();

        if (mDetailHeaderIcon != null) mDetailHeaderIcon.setImageResource(active.iconRes);
        if (mDetailHeaderTitle != null) mDetailHeaderTitle.setText(active.title);
        if (mDetailHeaderSubtitle != null) mDetailHeaderSubtitle.setText(active.subtitle);
        if (!mIsTabletLayout && mPhoneTitle != null && mPhoneInDetail) {
            mPhoneTitle.setText(active.title);
        }

        mDetailContainer.removeAllViews();
        if (mIsTabletLayout) {
            LinearLayout wrapper = new LinearLayout(this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wrapper.setLayoutParams(wLp);
            populateCategoryViews(active.id, wrapper);
            mDetailContainer.addView(wrapper);
        } else {
            populateCategoryViews(active.id, mDetailContainer);
        }

        if (mDetailScroll != null) {
            mDetailScroll.post(() -> mDetailScroll.scrollTo(0, 0));
        }
    }

    private void populateCategoryViews(@NonNull String catId, @NonNull LinearLayout target) {
        switch (catId) {
            case "appearance":
                buildAppearanceSection(target);
                break;
            case "terminal-view":
                buildTerminalViewSection(target);
                break;
            case "keyboard-io":
                buildKeyboardIoSection(target);
                break;
            case "tabs-split":
                buildTabsSplitSection(target);
                break;
            case "debugging":
                buildDebuggingSection(target);
                break;
            case "about":
            default:
                buildAboutSection(target);
                break;
        }
    }

    private void buildAppearanceSection(@NonNull LinearLayout target) {
        ThemePreset currentPreset = TerminalThemePresets.getPresetOrDefault(mPreferences.getColorPreset());
        int fontSize = mPreferences.getFontSize();
        String cursorStyle = TerminalThemePresets.readTermuxProperty(
            TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE,
            TermuxPropertyConstants.VALUE_TERMINAL_CURSOR_STYLE_BLOCK
        );
        int blinkRate = 0;
        try {
            blinkRate = Integer.parseInt(TerminalThemePresets.readTermuxProperty(
                TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, "0"));
        } catch (NumberFormatException ignored) {
        }
        boolean cursorBlink = blinkRate > 0;

        // Live preview card
        String selectedFont = mPreferences.getTerminalFont();
        Typeface previewTypeface = TerminalFonts.resolve(this, selectedFont);
        target.addView(mUi.createLiveTerminalPreviewCard(currentPreset, previewTypeface, fontSize, cursorStyle, cursorBlink, mPalette));

        // Color Themes Card
        target.addView(mUi.createSectionTitle("Color Theme (colors.properties)", mPalette));
        LinearLayout themeCard = mUi.createCard(mPalette);

        LinearLayout gridWrap = new LinearLayout(this);
        gridWrap.setOrientation(LinearLayout.VERTICAL);
        gridWrap.setPadding(mUi.dp(12), mUi.dp(12), mUi.dp(12), mUi.dp(12));

        List<ThemePreset> presets = TerminalThemePresets.getPresets();
        int cols = mIsTabletLayout ? 3 : 2;
        for (int i = 0; i < presets.size(); i += cols) {
            LinearLayout gridRow = new LinearLayout(this);
            gridRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams grLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i + cols < presets.size()) grLp.bottomMargin = mUi.dp(8);
            gridRow.setLayoutParams(grLp);

            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < presets.size()) {
                    ThemePreset preset = presets.get(idx);
                    boolean selected = preset.id.equals(currentPreset.id);
                    View swatch = mUi.createThemeSwatchCard(preset, selected, mPalette, v -> {
                        TerminalThemePresets.applyPreset(this, preset);
                        refreshAll(false);
                    });
                    LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (c > 0) sLp.setMarginStart(mUi.dp(8));
                    swatch.setLayoutParams(sLp);
                    gridRow.addView(swatch);
                } else {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                    spLp.setMarginStart(mUi.dp(8));
                    spacer.setLayoutParams(spLp);
                    gridRow.addView(spacer);
                }
            }
            gridWrap.addView(gridRow);
        }
        themeCard.addView(gridWrap);
        themeCard.addView(mUi.createDivider(mPalette));
        themeCard.addView(mUi.createSwitchRow(
            "Sync UI Chrome with Theme",
            "Harmonize status bar, title bar, tabs, split headers, Extra Keys, and Settings colors with active theme",
            mPreferences.isSyncChromeThemeEnabled(),
            mPalette,
            checked -> {
                mPreferences.setSyncChromeThemeEnabled(checked);
                refreshAll(false);
            }
        ));
        target.addView(themeCard);

        // Typography Card
        target.addView(mUi.createSectionTitle("Typography", mPalette));
        LinearLayout typoCard = mUi.createCard(mPalette);
        typoCard.addView(mUi.createSegmentedRow(
            "Font",
            TerminalFonts.hasCustomFontFile()
                ? "~/.termux/font.ttf active \u2014 selection overridden"
                : TerminalFonts.getDisplayName(selectedFont) + " (or ~/.termux/font.ttf)",
            Arrays.asList(
                new SegmentedOption(TERMUX_APP.VALUE_TERMINAL_FONT_DEJAVU, "DejaVu"),
                new SegmentedOption(TERMUX_APP.VALUE_TERMINAL_FONT_JETBRAINS, "JetBrains"),
                new SegmentedOption(TERMUX_APP.VALUE_TERMINAL_FONT_SYSTEM, "System")
            ),
            selectedFont,
            mPalette,
            value -> {
                mPreferences.setTerminalFont(value);
                TermuxActivity.updateTermuxActivityStyling(this, false);
                renderNavigation();
                renderDetail();
            }
        ));
        typoCard.addView(mUi.createDivider(mPalette));
        int[] defaults = TermuxAppSharedPreferences.getDefaultFontSizes(this);
        int minFont = Math.max(8, defaults[1]);
        int maxFont = Math.min(48, Math.max(24, defaults[0] * 2));
        typoCard.addView(mUi.createFontSizeControl(fontSize, minFont, maxFont, mPalette, nextSize -> {
            mPreferences.setFontSize(nextSize);
            renderNavigation();
            renderDetail();
        }));
        target.addView(typoCard);

        // Cursor Card
        target.addView(mUi.createSectionTitle("Cursor", mPalette));
        LinearLayout cursorCard = mUi.createCard(mPalette);
        cursorCard.addView(mUi.createSegmentedRow(
            "Cursor Style",
            null,
            Arrays.asList(
                new SegmentedOption(TermuxPropertyConstants.VALUE_TERMINAL_CURSOR_STYLE_BLOCK, "Block"),
                new SegmentedOption(TermuxPropertyConstants.VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE, "Underline"),
                new SegmentedOption(TermuxPropertyConstants.VALUE_TERMINAL_CURSOR_STYLE_BAR, "Bar")
            ),
            cursorStyle,
            mPalette,
            value -> {
                TerminalThemePresets.writeTermuxProperty(this, TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, value);
                renderDetail();
            }
        ));
        cursorCard.addView(mUi.createDivider(mPalette));
        cursorCard.addView(mUi.createSwitchRow(
            "Blinking Cursor",
            "Blink terminal cursor when focused",
            cursorBlink,
            mPalette,
            checked -> {
                TerminalThemePresets.writeTermuxProperty(this, TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, checked ? "500" : "0");
                renderDetail();
            }
        ));
        target.addView(cursorCard);
    }

    private void buildTerminalViewSection(@NonNull LinearLayout target) {
        target.addView(mUi.createSectionTitle("View & Toolbar", mPalette));
        LinearLayout viewCard = mUi.createCard(mPalette);
        viewCard.addView(mUi.createSwitchRow(
            getString(R.string.termux_terminal_view_terminal_margin_adjustment_title),
            "Adjust bottom margin automatically when soft keyboard or toolbar opens",
            mPreferences.isTerminalMarginAdjustmentEnabled(),
            mPalette,
            checked -> mPreferences.setTerminalMarginAdjustment(checked)
        ));
        viewCard.addView(mUi.createDivider(mPalette));
        viewCard.addView(mUi.createSwitchRow(
            "Show Extra Keys bar",
            "Display ESC, /, -, HOME, arrows, CTRL, and ALT toolbar below terminal",
            mPreferences.shouldShowTerminalToolbar(),
            mPalette,
            checked -> {
                mPreferences.setShowTerminalToolbar(checked);
                renderNavigation();
            }
        ));
        viewCard.addView(mUi.createDivider(mPalette));
        viewCard.addView(mUi.createSwitchRow(
            getString(R.string.action_toggle_keep_screen_on),
            "Prevent display from sleeping while terminal activity is active",
            mPreferences.shouldKeepScreenOn(),
            mPalette,
            checked -> mPreferences.setKeepScreenOn(checked)
        ));
        target.addView(viewCard);

        String bell = TerminalThemePresets.readTermuxProperty(
            TermuxPropertyConstants.KEY_BELL_BEHAVIOUR,
            TermuxPropertyConstants.DEFAULT_VALUE_BELL_BEHAVIOUR
        );
        target.addView(mUi.createSectionTitle("Terminal Bell (bell-character)", mPalette));
        LinearLayout bellCard = mUi.createCard(mPalette);
        bellCard.addView(mUi.createSegmentedRow(
            "Bell Feedback",
            "Device feedback when a terminal program triggers BEL (^G)",
            Arrays.asList(
                new SegmentedOption(TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_VIBRATE, "Vibrate"),
                new SegmentedOption(TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_BEEP, "Beep"),
                new SegmentedOption(TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_IGNORE, "Ignore")
            ),
            bell,
            mPalette,
            value -> {
                TerminalThemePresets.writeTermuxProperty(this, TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, value);
                renderDetail();
            }
        ));
        target.addView(bellCard);
    }

    private void buildKeyboardIoSection(@NonNull LinearLayout target) {
        String backBehaviour = TerminalThemePresets.readTermuxProperty(
            TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR,
            TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR
        );
        boolean backAsEscape = TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE.equalsIgnoreCase(backBehaviour);

        target.addView(mUi.createSectionTitle("Soft & Hardware Keyboard", mPalette));
        LinearLayout kbCard = mUi.createCard(mPalette);
        kbCard.addView(mUi.createSwitchRow(
            getString(R.string.termux_soft_keyboard_enabled_title),
            "Enable on-screen virtual keyboard when tapping terminal view",
            mPreferences.isSoftKeyboardEnabled(),
            mPalette,
            checked -> {
                mPreferences.setSoftKeyboardEnabled(checked);
                renderNavigation();
            }
        ));
        kbCard.addView(mUi.createDivider(mPalette));
        kbCard.addView(mUi.createSwitchRow(
            getString(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_title),
            "Hide on-screen keyboard automatically when physical keyboard is attached",
            mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware(),
            mPalette,
            checked -> {
                mPreferences.setSoftKeyboardEnabledOnlyIfNoHardware(checked);
                renderNavigation();
            }
        ));
        kbCard.addView(mUi.createDivider(mPalette));
        kbCard.addView(mUi.createSwitchRow(
            "Back key as Escape",
            "Send ESC key instead of back navigation for Vim/Neovim workflows",
            backAsEscape,
            mPalette,
            checked -> TerminalThemePresets.writeTermuxProperty(
                this,
                TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR,
                checked ? TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE : TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_BACK
            )
        ));
        target.addView(kbCard);

        // Shortcut tip card
        LinearLayout tipCard = mUi.createCard(mPalette);
        LinearLayout tipRow = new LinearLayout(this);
        tipRow.setOrientation(LinearLayout.HORIZONTAL);
        tipRow.setGravity(Gravity.TOP);
        tipRow.setPadding(mUi.dp(16), mUi.dp(14), mUi.dp(16), mUi.dp(14));
        tipRow.addView(mUi.createCategoryIconPill(R.drawable.ic_keyboard, false, mPalette));

        TextView tipText = new TextView(this);
        tipText.setText("Top bar Keyboard button shortcut: tap once to toggle the virtual keyboard, or long-press to toggle the Extra Keys bar.");
        tipText.setTextColor(mPalette.textSecondary);
        tipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tipText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tipRow.addView(tipText);
        tipCard.addView(tipRow);

        LinearLayout.LayoutParams tipLp = (LinearLayout.LayoutParams) tipCard.getLayoutParams();
        tipLp.topMargin = mUi.dp(12);
        tipCard.setLayoutParams(tipLp);
        target.addView(tipCard);
    }

    private void buildTabsSplitSection(@NonNull LinearLayout target) {
        int currentRatio = mPreferences.getDefaultSplitRatio();
        String ratioStr = String.valueOf(currentRatio);

        target.addView(mUi.createSectionTitle("Default Split Ratio (Tablet)", mPalette));
        LinearLayout ratioCard = mUi.createCard(mPalette);
        ratioCard.addView(mUi.createSplitPreviewCard(currentRatio, mPalette));
        ratioCard.addView(mUi.createSegmentedRow(
            "Initial Split Proportion",
            "Starting width split when opening Split Tab (divider can still be dragged 25% to 75%)",
            Arrays.asList(
                new SegmentedOption("40", "40 / 60"),
                new SegmentedOption("50", "50 / 50"),
                new SegmentedOption("60", "60 / 40")
            ),
            ratioStr,
            mPalette,
            value -> {
                mPreferences.setDefaultSplitRatio(Integer.parseInt(value));
                renderNavigation();
                renderDetail();
            }
        ));
        target.addView(ratioCard);

        target.addView(mUi.createSectionTitle("Motion & Chrome", mPalette));
        LinearLayout motionCard = mUi.createCard(mPalette);
        motionCard.addView(mUi.createSwitchRow(
            "UI Transition Animations",
            "Smooth 150ms to 180ms transitions when opening split panes and toggling Extra Keys",
            mPreferences.isUiAnimationsEnabled(),
            mPalette,
            checked -> mPreferences.setUiAnimationsEnabled(checked)
        ));
        motionCard.addView(mUi.createDivider(mPalette));
        motionCard.addView(mUi.createSwitchRow(
            "Show Active Pane Indicator",
            "Display session name, Active badge, and close button header above each split pane",
            mPreferences.shouldShowSplitPaneHeader(),
            mPalette,
            checked -> mPreferences.setShowSplitPaneHeader(checked)
        ));
        target.addView(motionCard);
    }

    private void buildDebuggingSection(@NonNull LinearLayout target) {
        int currentLevel = mPreferences.getLogLevel();
        String[] descriptions = {
            "Off: Application logging disabled.",
            "Normal: Standard warnings and errors recorded.",
            "Verbose: Session, plugin, and view lifecycle events recorded.",
            "Very Verbose: Full internal diagnostic payloads recorded."
        };
        int descIdx = Math.max(0, Math.min(descriptions.length - 1, currentLevel));

        target.addView(mUi.createSectionTitle(getString(R.string.termux_logging_header), mPalette));
        LinearLayout logCard = mUi.createCard(mPalette);
        logCard.addView(mUi.createSegmentedRow(
            getString(R.string.termux_log_level_title),
            descriptions[descIdx],
            Arrays.asList(
                new SegmentedOption("0", "Off"),
                new SegmentedOption("1", "Normal"),
                new SegmentedOption("2", "Verbose"),
                new SegmentedOption("3", "Full")
            ),
            String.valueOf(currentLevel),
            mPalette,
            value -> {
                mPreferences.setLogLevel(this, Integer.parseInt(value));
                renderNavigation();
                renderDetail();
            }
        ));
        target.addView(logCard);

        target.addView(mUi.createSectionTitle("Diagnostics & Alerts", mPalette));
        LinearLayout diagCard = mUi.createCard(mPalette);
        diagCard.addView(mUi.createSwitchRow(
            getString(R.string.termux_terminal_view_key_logging_enabled_title),
            "Log raw terminal key events to logcat for hardware keyboard debugging",
            mPreferences.isTerminalViewKeyLoggingEnabled(),
            mPalette,
            checked -> mPreferences.setTerminalViewKeyLoggingEnabled(checked)
        ));
        diagCard.addView(mUi.createDivider(mPalette));
        diagCard.addView(mUi.createSwitchRow(
            getString(R.string.termux_plugin_error_notifications_enabled_title),
            "Show notifications when plugin commands or Intents report errors",
            mPreferences.arePluginErrorNotificationsEnabled(false),
            mPalette,
            checked -> mPreferences.setPluginErrorNotificationsEnabled(checked)
        ));
        diagCard.addView(mUi.createDivider(mPalette));
        diagCard.addView(mUi.createSwitchRow(
            getString(R.string.termux_crash_report_notifications_enabled_title),
            "Prompt crash report details when a previous session terminated abnormally",
            mPreferences.areCrashReportNotificationsEnabled(false),
            mPalette,
            checked -> mPreferences.setCrashReportNotificationsEnabled(checked)
        ));
        target.addView(diagCard);
    }

    private void buildAboutSection(@NonNull LinearLayout target) {
        LinearLayout heroCard = mUi.createCard(mPalette);
        LinearLayout heroRow = new LinearLayout(this);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.CENTER_VERTICAL);
        heroRow.setPadding(mUi.dp(16), mUi.dp(16), mUi.dp(16), mUi.dp(16));

        View logoBox = new View(this);
        android.widget.FrameLayout iconFrame = new android.widget.FrameLayout(this);
        iconFrame.setBackground(mUi.roundedRect(mPalette.settingsWellBg, ColorUtils.setAlphaComponent(mPalette.textPrimary, 30), 12));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(mUi.dp(46), mUi.dp(46));
        iconLp.setMarginEnd(mUi.dp(12));
        iconFrame.setLayoutParams(iconLp);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_terminal_prompt);
        iv.setColorFilter(mPalette.textPrimary);
        iconFrame.addView(iv, new android.widget.FrameLayout.LayoutParams(mUi.dp(24), mUi.dp(24), Gravity.CENTER));
        heroRow.addView(iconFrame);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView appTitle = new TextView(this);
        appTitle.setText(getString(R.string.application_name));
        appTitle.setTextColor(mPalette.textPrimary);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        appTitle.setTypeface(null, Typeface.BOLD);

        TextView appSub = new TextView(this);
        appSub.setText("Terminal-android (Custom Responsive Build)");
        appSub.setTextColor(mPalette.textSecondary);
        appSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        TextView appMeta = new TextView(this);
        appMeta.setText(TermuxConstants.TERMUX_PACKAGE_NAME + " \u2022 v" + BuildConfig.VERSION_NAME);
        appMeta.setTextColor(mPalette.textSecondary);
        appMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        appMeta.setTypeface(Typeface.MONOSPACE);

        infoCol.addView(appTitle);
        infoCol.addView(appSub);
        infoCol.addView(appMeta);
        heroRow.addView(infoCol);

        TextView copyBtn = new TextView(this);
        copyBtn.setText("Copy Info");
        copyBtn.setTextColor(mPalette.textPrimary);
        copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        copyBtn.setTypeface(null, Typeface.BOLD);
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setMinimumHeight(mUi.dp(38));
        copyBtn.setPadding(mUi.dp(12), mUi.dp(8), mUi.dp(12), mUi.dp(8));
        copyBtn.setBackground(mUi.roundedRect(mPalette.settingsActiveBg, ColorUtils.setAlphaComponent(mPalette.textPrimary, 26), 9));
        copyBtn.setClickable(true);
        copyBtn.setFocusable(true);
        copyBtn.setOnClickListener(v -> copyBuildInfo());
        heroRow.addView(copyBtn);

        heroCard.addView(heroRow);
        target.addView(heroCard);

        target.addView(mUi.createSectionTitle("Environment & Paths", mPalette));
        LinearLayout specCard = mUi.createCard(mPalette);
        specCard.addView(mUi.createKeyValueRow("Repository", "aqky29/Terminal-android", mPalette));
        specCard.addView(mUi.createDivider(mPalette));
        specCard.addView(mUi.createKeyValueRow("Theme Config", "~/.termux/colors.properties", mPalette));
        specCard.addView(mUi.createDivider(mPalette));
        specCard.addView(mUi.createKeyValueRow("Font Config", "~/.termux/font.ttf", mPalette));
        specCard.addView(mUi.createDivider(mPalette));
        specCard.addView(mUi.createKeyValueRow("Bootstrap Prefix", TermuxConstants.TERMUX_PREFIX_DIR_PATH, mPalette));
        specCard.addView(mUi.createDivider(mPalette));

        // Open full Markdown diagnostic report (preserves original About report capability)
        LinearLayout reportRow = new LinearLayout(this);
        reportRow.setOrientation(LinearLayout.HORIZONTAL);
        reportRow.setGravity(Gravity.CENTER_VERTICAL);
        reportRow.setMinimumHeight(mUi.dp(48));
        reportRow.setPadding(mUi.dp(16), mUi.dp(10), mUi.dp(14), mUi.dp(10));
        reportRow.setClickable(true);
        reportRow.setFocusable(true);
        reportRow.setBackground(mPalette.createRippleBackground(Color.TRANSPARENT));

        TextView reportTv = new TextView(this);
        reportTv.setText("Open Full Device & Package Report");
        reportTv.setTextColor(mPalette.textPrimary);
        reportTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        reportTv.setTypeface(null, Typeface.BOLD);
        reportTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        reportRow.addView(reportTv);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(mPalette.textSecondary);
        chev.setLayoutParams(new LinearLayout.LayoutParams(mUi.dp(16), mUi.dp(16)));
        reportRow.addView(chev);
        reportRow.setOnClickListener(v -> openFullAboutReport());

        specCard.addView(reportRow);
        target.addView(specCard);
    }

    private void copyBuildInfo() {
        String text = String.format(
            Locale.ROOT,
            "Terminal (%s) v%s\nRepo: aqky29/Terminal-android\nTheme: %s (%dsp)",
            TermuxConstants.TERMUX_PACKAGE_NAME,
            BuildConfig.VERSION_NAME,
            mPreferences.getColorPreset(),
            mPreferences.getFontSize()
        );
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Terminal Build Info", text));
        }
        Toast.makeText(this, R.string.settings_copied_info, Toast.LENGTH_SHORT).show();
    }

    private void openFullAboutReport() {
        final Context context = this;
        new Thread() {
            @Override
            public void run() {
                String title = "About";
                StringBuilder aboutString = new StringBuilder();
                aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));
                aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context));

                String userActionName = UserAction.ABOUT.getName();
                ReportInfo reportInfo = new ReportInfo(userActionName,
                    TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title);
                reportInfo.setReportString(aboutString.toString());
                reportInfo.setReportSaveFileLabelAndPath(userActionName,
                    Environment.getExternalStorageDirectory() + "/" +
                        FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));
                ReportActivity.startReportActivity(context, reportInfo);
            }
        }.start();
    }

    private void confirmResetDefaults() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset_confirm_title)
            .setMessage(R.string.settings_reset_confirm_message)
            .setPositiveButton(R.string.settings_reset_short, (dialog, which) -> {
                TerminalThemePresets.resetDefaults(this, mPreferences);
                refreshAll(false);
                Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
