package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.app.theme.TerminalChromePalette;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.util.Arrays;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in {@link TermuxActivity} that displays the primary terminal.
     */
    TerminalView mTerminalView;

    /**
     * Optional secondary {@link TerminalView} for tablet split panel mode.
     */
    @Nullable
    TerminalView mSecondaryTerminalView;

    /**
     * Points to whichever {@link TerminalView} pane currently has active focus.
     */
    TerminalView mActiveTerminalView;

    private boolean mIsSplitMode = false;
    private float mSplitRatio = 0.5f;
    private boolean mSavedSplitActive = false;
    private boolean mSavedSecondaryActive = false;
    private String mSavedSecondarySessionHandle = null;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;
    private static final int CONTEXT_MENU_GRANT_MICROPHONE_PERMISSION_ID = 12;

    private static boolean sHasRequestedMicrophonePermissionOnLaunch = false;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";
    private static final String ARG_SPLIT_ACTIVE = "split_active";
    private static final String ARG_SPLIT_RATIO = "split_ratio";
    private static final String ARG_SECONDARY_ACTIVE = "secondary_active";
    private static final String ARG_SECONDARY_SESSION_HANDLE = "secondary_session_handle";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null) {
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);
            mSavedSplitActive = savedInstanceState.getBoolean(ARG_SPLIT_ACTIVE, false);
            mSplitRatio = savedInstanceState.getFloat(ARG_SPLIT_RATIO, 0.5f);
            mSavedSecondaryActive = savedInstanceState.getBoolean(ARG_SECONDARY_ACTIVE, false);
            mSavedSecondarySessionHandle = savedInstanceState.getString(ARG_SECONDARY_SESSION_HANDLE, null);
        }

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }
        if (savedInstanceState == null) {
            mSplitRatio = mPreferences.getDefaultSplitRatio() / 100f;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        setOpenDrawerButtonView();

        registerForContextMenu(mTerminalView);
        if (mSecondaryTerminalView != null) {
            registerForContextMenu(mSecondaryTerminalView);
        }

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        reloadProperties();
        if (!isSplitPaneActive() && mPreferences != null) {
            mSplitRatio = mPreferences.getDefaultSplitRatio() / 100f;
        }

        ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager != null && mPreferences != null) {
            terminalToolbarViewPager.setVisibility(mPreferences.shouldShowTerminalToolbar() ? View.VISIBLE : View.GONE);
        }

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        applySplitVisibility(false);
        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        closeDrawersIfPresent();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
        savedInstanceState.putBoolean(ARG_SPLIT_ACTIVE, isSplitPaneActive());
        savedInstanceState.putFloat(ARG_SPLIT_RATIO, mSplitRatio);
        savedInstanceState.putBoolean(ARG_SECONDARY_ACTIVE, isSecondaryPaneFocused());
        if (mSecondaryTerminalView != null && mSecondaryTerminalView.getCurrentSession() != null) {
            savedInstanceState.putString(ARG_SECONDARY_SESSION_HANDLE, mSecondaryTerminalView.getCurrentSession().mHandle);
        }
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                        maybeRequestMicrophonePermissionOnLaunch();
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
            maybeRequestMicrophonePermissionOnLaunch();
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
        restoreSplitStateIfNeeded();
        updateSessionChrome();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);
        mActiveTerminalView = mTerminalView;

        mSecondaryTerminalView = findViewById(R.id.terminal_view_secondary);
        if (mSecondaryTerminalView != null) {
            mSecondaryTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);
        }

        setupSplitPaneInteractions();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        if (termuxSessionsListView == null) return;
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        ViewGroup rootRelative = findViewById(R.id.activity_termux_root_relative_layout);
        if (rootRelative != null && mPreferences.isUiAnimationsEnabled()) {
            TransitionSet transition = new TransitionSet()
                .addTransition(new ChangeBounds())
                .addTransition(new Fade())
                .setDuration(150)
                .setInterpolator(new DecelerateInterpolator());
            TransitionManager.beginDelayedTransition(rootRelative, transition);
        }
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        attachSettingsClickListener(findViewById(R.id.settings_button));
        attachSettingsClickListener(findViewById(R.id.top_settings_button));
    }

    private void attachSettingsClickListener(@Nullable View settingsButton) {
        if (settingsButton == null) return;
        settingsButton.setOnClickListener(v ->
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class))
        );
    }

    private void setNewSessionButtonView() {
        attachNewSessionListeners(findViewById(R.id.new_session_button));
        attachNewSessionListeners(findViewById(R.id.top_new_session_button));
    }

    private void attachNewSessionListeners(@Nullable View newSessionButton) {
        if (newSessionButton == null) return;
        newSessionButton.setOnClickListener(v -> {
            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
        });
        newSessionButton.setOnLongClickListener(v -> {
            if (mTermuxTerminalSessionActivityClient == null) return true;
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        attachKeyboardToggleListeners(findViewById(R.id.toggle_keyboard_button), true);
        attachKeyboardToggleListeners(findViewById(R.id.top_keyboard_button), false);
    }

    private void attachKeyboardToggleListeners(@Nullable View toggleButton, boolean closeDrawerOnClick) {
        if (toggleButton == null) return;
        toggleButton.setOnClickListener(v -> {
            if (mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            if (closeDrawerOnClick)
                closeDrawersIfPresent();
        });
        toggleButton.setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    private void setOpenDrawerButtonView() {
        View openDrawerButton = findViewById(R.id.open_drawer_button);
        if (openDrawerButton != null) {
            openDrawerButton.setOnClickListener(v -> openSessionsDrawer());
        }
        // On phone layouts without a horizontal tab strip, tapping the active session title opens the sessions drawer.
        View activeSessionTitle = findViewById(R.id.active_session_title);
        if (activeSessionTitle != null && findViewById(R.id.session_tabs_container) == null) {
            activeSessionTitle.setOnClickListener(v -> openSessionsDrawer());
        }
    }

    @SuppressLint("RtlHardcoded")
    private void openSessionsDrawer() {
        DrawerLayout drawer = getDrawer();
        if (drawer != null) {
            drawer.openDrawer(Gravity.LEFT);
        }
    }

    public void closeDrawersIfPresent() {
        DrawerLayout drawer = getDrawer();
        if (drawer != null) {
            drawer.closeDrawers();
        }
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        DrawerLayout drawer = getDrawer();
        if (drawer != null && drawer.isDrawerOpen(Gravity.LEFT)) {
            drawer.closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        if (v == mSecondaryTerminalView) {
            setActivePane(true);
        } else if (v == mTerminalView) {
            setActivePane(false);
        }
        TerminalSession currentSession = getCurrentSession();
        TerminalView activeView = getTerminalView();
        if (currentSession == null || activeView == null) return;

        boolean autoFillEnabled = activeView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(activeView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        if (!PermissionUtils.checkMicrophonePermission(this))
            menu.add(Menu.NONE, CONTEXT_MENU_GRANT_MICROPHONE_PERMISSION_ID, Menu.NONE, R.string.action_grant_microphone_permission);
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        TerminalView activeView = getTerminalView();
        if (activeView != null) activeView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();
        TerminalView activeView = getTerminalView();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                if (activeView != null) activeView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                if (activeView != null) activeView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_GRANT_MICROPHONE_PERMISSION_ID:
                requestMicrophonePermission(false, false);
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        TerminalView activeView = getTerminalView();
        if (activeView != null) activeView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        boolean nextKeepScreenOn = !mTerminalView.getKeepScreenOn();
        mTerminalView.setKeepScreenOn(nextKeepScreenOn);
        if (mSecondaryTerminalView != null) {
            mSecondaryTerminalView.setKeepScreenOn(nextKeepScreenOn);
        }
        mPreferences.setKeepScreenOn(nextKeepScreenOn);
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    /**
     * Request {@link android.Manifest.permission#RECORD_AUDIO} once per app process launch if it has
     * not already been granted, so CLI voice tools running inside Termux can capture audio.
     */
    private void maybeRequestMicrophonePermissionOnLaunch() {
        if (sHasRequestedMicrophonePermissionOnLaunch || !mIsVisible) return;
        sHasRequestedMicrophonePermissionOnLaunch = true;
        if (!PermissionUtils.checkMicrophonePermission(this)) {
            requestMicrophonePermission(false, false);
        }
    }

    /**
     * Check or request {@link android.Manifest.permission#RECORD_AUDIO} permission for Termux processes.
     */
    public void requestMicrophonePermission(boolean isPermissionCallback, boolean showErrorMessage) {
        int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_MICROPHONE_PERMISSION;
        if (PermissionUtils.checkAndRequestMicrophonePermission(TermuxActivity.this, requestCode, !isPermissionCallback && showErrorMessage)) {
            if (isPermissionCallback)
                Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                    getString(com.termux.shared.R.string.msg_microphone_permission_granted_on_request));
        } else {
            if (isPermissionCallback)
                Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                    getString(com.termux.shared.R.string.msg_microphone_permission_not_granted_on_request));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        } else if (requestCode == PermissionUtils.REQUEST_GRANT_MICROPHONE_PERMISSION) {
            requestMicrophonePermission(true, false);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        if (mTermuxSessionListViewController != null)
            mTermuxSessionListViewController.notifyDataSetChanged();
        updateSessionChrome();
    }

    /**
     * Computes the active UI chrome palette from the current terminal color scheme and user sync setting.
     */
    @NonNull
    public TerminalChromePalette getActiveChromePalette() {
        TerminalSession session = getCurrentSession();
        int[] colors = (session != null && session.getEmulator() != null)
            ? session.getEmulator().mColors.mCurrentColors
            : com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors;
        int backgroundColor = colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND];
        int foregroundColor = colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND];
        boolean syncChrome = mPreferences == null || mPreferences.isSyncChromeThemeEnabled();
        return TerminalChromePalette.fromTerminalColors(backgroundColor, foregroundColor, syncChrome);
    }

    /**
     * Applies harmonized theme colors across system bars, top chrome bar, tablet tab strip, dividers, and icons.
     */
    public void applyChromePalette(@NonNull TerminalChromePalette palette) {
        if (getWindow() != null) {
            getWindow().setStatusBarColor(palette.statusBg);
            getWindow().setNavigationBarColor(palette.canvasBg);
            WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (insetsController != null) {
                insetsController.setAppearanceLightStatusBars(palette.isLightBackground);
                insetsController.setAppearanceLightNavigationBars(palette.isLightBackground);
            }
        }

        View topChromeBar = findViewById(R.id.top_chrome_bar);
        if (topChromeBar != null) topChromeBar.setBackgroundColor(palette.topChromeBg);

        int hairlineColor = ColorUtils.setAlphaComponent(palette.borderHairline, 153);
        View topDivider = findViewById(R.id.top_chrome_divider);
        if (topDivider != null) topDivider.setBackgroundColor(hairlineColor);

        View tabsDivider = findViewById(R.id.session_tabs_divider);
        if (tabsDivider != null) tabsDivider.setBackgroundColor(hairlineColor);

        View sessionTabsBar = findViewById(R.id.session_tabs_bar);
        if (sessionTabsBar != null) sessionTabsBar.setBackgroundColor(palette.tabStripBg);

        LinearLayout tabsContainer = findViewById(R.id.session_tabs_container);
        if (tabsContainer != null) {
            GradientDrawable dividerDrawable = new GradientDrawable();
            dividerDrawable.setSize(Math.max(1, Math.round(getResources().getDisplayMetrics().density)), 1);
            dividerDrawable.setColor(ColorUtils.setAlphaComponent(palette.borderHairline, 128));
            tabsContainer.setDividerDrawable(dividerDrawable);
        }

        View splitDividerLine = findViewById(R.id.split_divider_line);
        if (splitDividerLine != null) splitDividerLine.setBackgroundColor(palette.dividerColor);

        android.widget.ImageView brandIcon = findViewById(R.id.top_brand_icon);
        if (brandIcon != null) ImageViewCompat.setImageTintList(brandIcon, ColorStateList.valueOf(palette.textSecondary));

        TextView brandLabel = findViewById(R.id.top_brand_label);
        if (brandLabel != null) brandLabel.setTextColor(palette.textSecondary);

        TextView activeTitleView = findViewById(R.id.active_session_title);
        if (activeTitleView != null) activeTitleView.setTextColor(palette.textSecondary);

        ImageButton drawerButton = findViewById(R.id.open_drawer_button);
        if (drawerButton != null) ImageViewCompat.setImageTintList(drawerButton, ColorStateList.valueOf(palette.textSecondary));

        ImageButton keyboardButton = findViewById(R.id.top_keyboard_button);
        if (keyboardButton != null) ImageViewCompat.setImageTintList(keyboardButton, ColorStateList.valueOf(palette.textSecondary));

        ImageButton settingsButton = findViewById(R.id.top_settings_button);
        if (settingsButton != null) ImageViewCompat.setImageTintList(settingsButton, ColorStateList.valueOf(palette.textSecondary));

        ImageButton newSessionButton = findViewById(R.id.top_new_session_button);
        if (newSessionButton != null) {
            ImageViewCompat.setImageTintList(newSessionButton, ColorStateList.valueOf(palette.textSecondary));
            if (sessionTabsBar != null) {
                int topStrokePx = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
                newSessionButton.setBackground(palette.createTabBackground(palette.newTabBg, hairlineColor, topStrokePx));
            }
        }
    }

    /**
     * Updates the centered macOS-style window title with session name/cwd, process title, and cols x rows.
     */
    public void updateWindowTitle() {
        TextView activeTitleView = findViewById(R.id.active_session_title);
        if (activeTitleView == null) return;
        TerminalChromePalette palette = getActiveChromePalette();
        activeTitleView.setText(formatWindowTitle(getCurrentSession()));
        activeTitleView.setTextColor(palette.textSecondary);
    }

    /**
     * Syncs the active session title in the top chrome bar and the tablet horizontal session tabs.
     */
    private void updateSessionChrome() {
        TerminalChromePalette palette = getActiveChromePalette();
        applyChromePalette(palette);

        TerminalSession currentSession = getCurrentSession();
        TextView activeTitleView = findViewById(R.id.active_session_title);
        if (activeTitleView != null) {
            activeTitleView.setText(formatWindowTitle(currentSession));
            activeTitleView.setTextColor(palette.textSecondary);
        }

        LinearLayout tabsContainer = findViewById(R.id.session_tabs_container);
        if (tabsContainer == null || mTermuxService == null) return;

        tabsContainer.removeAllViews();
        java.util.List<TermuxSession> sessions = mTermuxService.getTermuxSessions();
        final HorizontalScrollView tabsScroll = findViewById(R.id.session_tabs_scroll);
        int scrollWidth = tabsScroll != null ? tabsScroll.getWidth() : 0;
        int minTabWidthPx = Math.round(140 * getResources().getDisplayMetrics().density);
        boolean stretchTabs = sessions.isEmpty() || scrollWidth <= 0 || (sessions.size() * minTabWidthPx <= scrollWidth);
        TerminalSession primarySession = mTerminalView != null ? mTerminalView.getCurrentSession() : null;
        TerminalSession secondarySession = isSplitPaneActive() && mSecondaryTerminalView != null
            ? mSecondaryTerminalView.getCurrentSession() : null;
        int topStrokePx = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        int topHairlineColor = ColorUtils.setAlphaComponent(palette.borderHairline, 153);

        for (int i = 0; i < sessions.size(); i++) {
            final TerminalSession session = sessions.get(i).getTerminalSession();
            if (session == null) continue;

            View tabView = getLayoutInflater().inflate(R.layout.item_terminal_session_tab, tabsContainer, false);
            LinearLayout.LayoutParams tabParams = stretchTabs
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(minTabWidthPx, ViewGroup.LayoutParams.MATCH_PARENT);
            tabView.setLayoutParams(tabParams);
            boolean isActive = (session == currentSession);
            boolean isVisibleInOtherPane = !isActive && isSplitPaneActive() &&
                (session == primarySession || session == secondarySession);
            boolean isError = !session.isRunning() && session.getExitStatus() != 0;

            int tabBg = isActive
                ? palette.topChromeBg
                : (isVisibleInOtherPane ? palette.tabOtherPaneBg : palette.tabStripBg);
            tabView.setBackground(palette.createTabBackground(tabBg, topHairlineColor, isActive ? 0 : topStrokePx));

            TextView tabTitle = tabView.findViewById(R.id.session_tab_title);
            tabTitle.setText(formatSessionDisplayLabel(session, -1));
            tabTitle.setTextColor(isError
                ? ContextCompat.getColor(this, R.color.terminal_status_error)
                : (isActive ? palette.textPrimary : palette.textSecondary));

            TextView tabShortcut = tabView.findViewById(R.id.session_tab_shortcut);
            if (tabShortcut != null) {
                if (i < 9) {
                    tabShortcut.setVisibility(View.VISIBLE);
                    tabShortcut.setText("\u2318" + (i + 1));
                    tabShortcut.setTextColor(isActive ? palette.textPrimary : palette.textSecondary);
                } else {
                    tabShortcut.setVisibility(View.GONE);
                }
            }

            ImageButton closeTabButton = tabView.findViewById(R.id.close_tab_button);
            closeTabButton.setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);
            ImageViewCompat.setImageTintList(closeTabButton, ColorStateList.valueOf(isActive ? palette.textPrimary : palette.textSecondary));

            tabView.setOnClickListener(v -> {
                if (isSplitPaneActive()) {
                    if (mTerminalView != null && mTerminalView.getCurrentSession() == session) {
                        setActivePane(false);
                        return;
                    }
                    if (mSecondaryTerminalView != null && mSecondaryTerminalView.getCurrentSession() == session) {
                        setActivePane(true);
                        return;
                    }
                }
                if (mTermuxTerminalSessionActivityClient != null)
                    mTermuxTerminalSessionActivityClient.setCurrentSession(session);
            });
            tabView.setOnLongClickListener(v -> {
                showTabContextMenu(tabView, session);
                return true;
            });

            closeTabButton.setOnClickListener(v -> {
                if (session.isRunning()) {
                    showKillSessionDialog(session);
                } else if (mTermuxTerminalSessionActivityClient != null) {
                    mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
                }
            });

            tabsContainer.addView(tabView);

            if (isActive && tabsScroll != null) {
                tabsScroll.post(() -> {
                    int targetX = tabView.getLeft() - (tabsScroll.getWidth() - tabView.getWidth()) / 2;
                    tabsScroll.smoothScrollTo(Math.max(0, targetX), 0);
                });
            }
        }

        updateSplitPaneChrome();
    }

    private void showTabContextMenu(@NonNull View anchor, @NonNull TerminalSession session) {
        PopupMenu popup = new PopupMenu(this, anchor);
        final int MENU_SPLIT_TAB = 1;
        final int MENU_MOVE_LEFT = 2;
        final int MENU_MOVE_RIGHT = 3;
        final int MENU_CLOSE_SPLIT = 4;
        final int MENU_RENAME = 5;
        final int MENU_CLOSE = 6;

        if (mSecondaryTerminalView != null) {
            if (!isSplitPaneActive()) {
                popup.getMenu().add(0, MENU_SPLIT_TAB, 0, R.string.action_split_tab);
            } else {
                popup.getMenu().add(0, MENU_MOVE_LEFT, 0, R.string.action_open_in_left_pane);
                popup.getMenu().add(0, MENU_MOVE_RIGHT, 1, R.string.action_open_in_right_pane);
                popup.getMenu().add(0, MENU_CLOSE_SPLIT, 2, R.string.action_close_split_pane);
            }
        }
        popup.getMenu().add(0, MENU_RENAME, 3, R.string.action_rename_session);
        popup.getMenu().add(0, MENU_CLOSE, 4, R.string.action_close_tab);

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_SPLIT_TAB:
                    openSplitWithSession(session);
                    return true;
                case MENU_MOVE_LEFT:
                    openSessionInPane(session, false);
                    return true;
                case MENU_MOVE_RIGHT:
                    openSessionInPane(session, true);
                    return true;
                case MENU_CLOSE_SPLIT:
                    closeSplitPanel();
                    return true;
                case MENU_RENAME:
                    if (mTermuxTerminalSessionActivityClient != null)
                        mTermuxTerminalSessionActivityClient.renameSession(session);
                    return true;
                case MENU_CLOSE:
                    if (session.isRunning()) {
                        showKillSessionDialog(session);
                    } else if (mTermuxTerminalSessionActivityClient != null) {
                        mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
                    }
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupSplitPaneInteractions() {
        if (mTerminalView != null) {
            mTerminalView.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    setActivePane(false);
                }
                return false;
            });
        }
        if (mSecondaryTerminalView != null) {
            mSecondaryTerminalView.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    setActivePane(true);
                }
                return false;
            });
        }
        View paneLeftBar = findViewById(R.id.pane_left_bar);
        if (paneLeftBar != null) {
            paneLeftBar.setOnClickListener(v -> setActivePane(false));
        }
        View paneRightBar = findViewById(R.id.pane_right_bar);
        if (paneRightBar != null) {
            paneRightBar.setOnClickListener(v -> setActivePane(true));
        }
        View closeSplitButton = findViewById(R.id.close_split_button);
        if (closeSplitButton != null) {
            closeSplitButton.setOnClickListener(v -> closeSplitPanel());
        }

        final View splitDivider = findViewById(R.id.split_divider);
        final LinearLayout panesContainer = findViewById(R.id.panes_container);
        if (splitDivider != null && panesContainer != null) {
            splitDivider.setOnTouchListener((v, event) -> {
                if (!isSplitPaneActive()) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE: {
                        int totalWidth = panesContainer.getWidth();
                        if (totalWidth > 0) {
                            int[] loc = new int[2];
                            panesContainer.getLocationOnScreen(loc);
                            float ratio = (event.getRawX() - loc[0]) / (float) totalWidth;
                            mSplitRatio = Math.max(0.25f, Math.min(0.75f, ratio));
                            applySplitWeights();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            });
        }
    }

    private void restoreSplitStateIfNeeded() {
        if (mSecondaryTerminalView == null || mTermuxService == null || !mSavedSplitActive) return;
        TerminalSession secondarySession = !TextUtils.isEmpty(mSavedSecondarySessionHandle)
            ? mTermuxService.getTerminalSessionForHandle(mSavedSecondarySessionHandle)
            : null;
        if (secondarySession == null && mTermuxService.getTermuxSessionsSize() > 1) {
            TerminalSession primarySession = mTerminalView.getCurrentSession();
            for (TermuxSession ts : mTermuxService.getTermuxSessions()) {
                if (ts.getTerminalSession() != null && ts.getTerminalSession() != primarySession) {
                    secondarySession = ts.getTerminalSession();
                    break;
                }
            }
        }
        if (secondarySession != null) {
            mIsSplitMode = true;
            mSecondaryTerminalView.attachSession(secondarySession);
            applySplitVisibility(false);
            setActivePane(mSavedSecondaryActive);
        }
        mSavedSplitActive = false;
    }

    public void openSplitWithSession(@NonNull TerminalSession selectedSession) {
        if (mSecondaryTerminalView == null || mTermuxService == null || mTermuxTerminalSessionActivityClient == null)
            return;

        TerminalSession leftSession = mTerminalView.getCurrentSession();
        if (selectedSession == leftSession) {
            // Look for another existing session for the right pane, or create a new one.
            TerminalSession otherSession = null;
            for (TermuxSession ts : mTermuxService.getTermuxSessions()) {
                if (ts.getTerminalSession() != null && ts.getTerminalSession() != leftSession) {
                    otherSession = ts.getTerminalSession();
                    break;
                }
            }
            mIsSplitMode = true;
            applySplitVisibility(true);
            if (otherSession != null) {
                openSessionInPane(otherSession, true);
            } else {
                setActivePane(true);
                mTermuxTerminalSessionActivityClient.addNewSessionInPane(false, null, true);
            }
        } else {
            mIsSplitMode = true;
            applySplitVisibility(true);
            openSessionInPane(selectedSession, true);
        }
    }

    public void openSessionInPane(@NonNull TerminalSession session, boolean secondaryPane) {
        if (secondaryPane && mSecondaryTerminalView != null) {
            mIsSplitMode = true;
            applySplitVisibility(true);
            mSecondaryTerminalView.attachSession(session);
            setActivePane(true);
        } else if (mTerminalView != null) {
            mTerminalView.attachSession(session);
            setActivePane(false);
        }
    }

    public void closeSplitPanel() {
        if (!mIsSplitMode) return;
        if (isSecondaryPaneFocused() && mSecondaryTerminalView != null && mSecondaryTerminalView.getCurrentSession() != null) {
            mTerminalView.attachSession(mSecondaryTerminalView.getCurrentSession());
        }
        mIsSplitMode = false;
        mActiveTerminalView = mTerminalView;
        applySplitVisibility(true);
        if (mTerminalView != null) {
            mTerminalView.requestFocus();
        }
        if (mSecondaryTerminalView != null) {
            mSecondaryTerminalView.setTerminalCursorBlinkerState(false, true);
        }
        if (mTermuxTerminalSessionActivityClient != null) {
            mTermuxTerminalSessionActivityClient.updateBackgroundColor();
        }
        updateSessionChrome();
    }

    private void applySplitVisibility(boolean animate) {
        LinearLayout panesContainer = findViewById(R.id.panes_container);
        View paneLeftBar = findViewById(R.id.pane_left_bar);
        View splitDivider = findViewById(R.id.split_divider);
        View paneRightContainer = findViewById(R.id.pane_right_container);
        if (panesContainer == null) return;

        if (animate && (mPreferences == null || mPreferences.isUiAnimationsEnabled())) {
            TransitionSet transition = new TransitionSet()
                .addTransition(new ChangeBounds())
                .addTransition(new Fade())
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator());
            TransitionManager.beginDelayedTransition(panesContainer, transition);
        }

        boolean show = isSplitPaneActive();
        boolean showHeaders = show && (mPreferences == null || mPreferences.shouldShowSplitPaneHeader());
        if (paneLeftBar != null) paneLeftBar.setVisibility(showHeaders ? View.VISIBLE : View.GONE);
        if (splitDivider != null) splitDivider.setVisibility(show ? View.VISIBLE : View.GONE);
        if (paneRightContainer != null) paneRightContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        applySplitWeights();
    }

    private void applySplitWeights() {
        View paneLeftContainer = findViewById(R.id.pane_left_container);
        View paneRightContainer = findViewById(R.id.pane_right_container);
        if (paneLeftContainer == null || paneRightContainer == null) return;

        LinearLayout.LayoutParams leftLp = (LinearLayout.LayoutParams) paneLeftContainer.getLayoutParams();
        LinearLayout.LayoutParams rightLp = (LinearLayout.LayoutParams) paneRightContainer.getLayoutParams();
        if (isSplitPaneActive()) {
            leftLp.weight = mSplitRatio;
            rightLp.weight = 1.0f - mSplitRatio;
        } else {
            leftLp.weight = 1.0f;
            rightLp.weight = 0.0f;
        }
        paneLeftContainer.setLayoutParams(leftLp);
        paneRightContainer.setLayoutParams(rightLp);
    }

    public void setActivePane(boolean secondary) {
        if (secondary && !isSplitPaneActive()) return;
        TerminalView target = secondary ? mSecondaryTerminalView : mTerminalView;
        if (target == null) return;

        boolean changed = (mActiveTerminalView != target);
        mActiveTerminalView = target;
        target.requestFocus();

        if (mTerminalView != null) {
            mTerminalView.setTerminalCursorBlinkerState(!secondary && mIsVisible, true);
        }
        if (mSecondaryTerminalView != null && isSplitPaneActive()) {
            mSecondaryTerminalView.setTerminalCursorBlinkerState(secondary && mIsVisible, true);
        }

        if (changed) {
            if (mTermuxTerminalSessionActivityClient != null) {
                mTermuxTerminalSessionActivityClient.updateBackgroundColor();
            } else {
                updateSessionChrome();
            }
        }
    }

    private void updateSplitPaneChrome() {
        if (!isSplitPaneActive()) return;
        boolean secondaryFocused = isSecondaryPaneFocused();
        boolean showHeaders = mPreferences == null || mPreferences.shouldShowSplitPaneHeader();
        TerminalChromePalette palette = getActiveChromePalette();
        int dotSize = Math.round(6 * getResources().getDisplayMetrics().density);

        View paneLeftBar = findViewById(R.id.pane_left_bar);
        View paneLeftDot = findViewById(R.id.pane_left_dot);
        TextView paneLeftLabel = findViewById(R.id.pane_left_label);
        TextView paneLeftFocus = findViewById(R.id.pane_left_focus);
        if (paneLeftBar != null && paneLeftDot != null && paneLeftLabel != null && paneLeftFocus != null) {
            paneLeftBar.setVisibility(showHeaders ? View.VISIBLE : View.GONE);
            paneLeftBar.setBackgroundColor(!secondaryFocused ? palette.paneBarActiveBg : palette.paneBarInactiveBg);
            GradientDrawable leftDot = new GradientDrawable();
            leftDot.setShape(GradientDrawable.OVAL);
            leftDot.setSize(dotSize, dotSize);
            leftDot.setColor(!secondaryFocused ? palette.textPrimary : palette.textSecondary);
            paneLeftDot.setBackground(leftDot);
            paneLeftLabel.setText(formatSessionDisplayLabel(mTerminalView != null ? mTerminalView.getCurrentSession() : null, -1));
            paneLeftLabel.setTextColor(!secondaryFocused ? palette.textPrimary : palette.textSecondary);
            paneLeftFocus.setText(!secondaryFocused ? getString(R.string.label_pane_active) : "");
            paneLeftFocus.setTextColor(!secondaryFocused ? palette.textPrimary : palette.textSecondary);
        }

        View paneRightBar = findViewById(R.id.pane_right_bar);
        View paneRightDot = findViewById(R.id.pane_right_dot);
        TextView paneRightLabel = findViewById(R.id.pane_right_label);
        TextView paneRightFocus = findViewById(R.id.pane_right_focus);
        ImageButton closeSplitButton = findViewById(R.id.close_split_button);
        if (paneRightBar != null && paneRightDot != null && paneRightLabel != null && paneRightFocus != null) {
            paneRightBar.setVisibility(showHeaders ? View.VISIBLE : View.GONE);
            paneRightBar.setBackgroundColor(secondaryFocused ? palette.paneBarActiveBg : palette.paneBarInactiveBg);
            GradientDrawable rightDot = new GradientDrawable();
            rightDot.setShape(GradientDrawable.OVAL);
            rightDot.setSize(dotSize, dotSize);
            rightDot.setColor(secondaryFocused ? palette.textPrimary : palette.textSecondary);
            paneRightDot.setBackground(rightDot);
            paneRightLabel.setText(formatSessionDisplayLabel(mSecondaryTerminalView != null ? mSecondaryTerminalView.getCurrentSession() : null, -1));
            paneRightLabel.setTextColor(secondaryFocused ? palette.textPrimary : palette.textSecondary);
            paneRightFocus.setText(secondaryFocused ? getString(R.string.label_pane_active) : "");
            paneRightFocus.setTextColor(secondaryFocused ? palette.textPrimary : palette.textSecondary);
            if (closeSplitButton != null) {
                ImageViewCompat.setImageTintList(closeSplitButton, ColorStateList.valueOf(palette.textSecondary));
            }
        }
    }

    private String formatWindowTitle(@Nullable TerminalSession session) {
        if (session == null) {
            return getString(R.string.application_name);
        }
        TerminalEmulator emulator = session.getEmulator();
        int cols = emulator != null ? emulator.mColumns : 0;
        int rows = emulator != null ? emulator.mRows : 0;
        return buildWindowTitle(session.mSessionName, session.getCwd(), session.getTitle(), cols, rows);
    }

    @NonNull
    static String buildWindowTitle(
        @Nullable String sessionName,
        @Nullable String cwd,
        @Nullable String procTitle,
        int cols,
        int rows
    ) {
        String head = !TextUtils.isEmpty(sessionName) ? sessionName : shortenCwd(cwd);
        StringBuilder sb = new StringBuilder(head);
        if (!TextUtils.isEmpty(procTitle) && !procTitle.equals(head)) {
            sb.append(" \u2014 ").append(procTitle);
        }
        if (cols > 0 && rows > 0) {
            sb.append(" \u2014 ").append(cols).append('\u00D7').append(rows);
        }
        return sb.toString();
    }

    @NonNull
    static String shortenCwd(@Nullable String cwd) {
        if (TextUtils.isEmpty(cwd)) {
            return "~";
        }
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (cwd.equals(home)) {
            return "~";
        }
        if (cwd.startsWith(home + "/")) {
            return "~" + cwd.substring(home.length());
        }
        return cwd;
    }

    private String formatSessionDisplayLabel(@Nullable TerminalSession session, int index) {
        if (session == null) {
            return getString(R.string.application_name);
        }
        String name = session.mSessionName;
        String title = session.getTitle();
        String base;
        if (!TextUtils.isEmpty(name)) {
            base = name;
        } else if (!TextUtils.isEmpty(title)) {
            base = title;
        } else {
            base = getString(R.string.title_default_session);
        }
        return index >= 0 ? "[" + (index + 1) + "] " + base : base;
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mActiveTerminalView != null ? mActiveTerminalView : mTerminalView;
    }

    public TerminalView getPrimaryTerminalView() {
        return mTerminalView;
    }

    @Nullable
    public TerminalView getSecondaryTerminalView() {
        return mSecondaryTerminalView;
    }

    public boolean isSplitPaneActive() {
        return mIsSplitMode && mSecondaryTerminalView != null;
    }

    public boolean isSecondaryPaneFocused() {
        return isSplitPaneActive() && mActiveTerminalView == mSecondaryTerminalView;
    }

    public boolean hasAnyTerminalViewFocus() {
        return (mTerminalView != null && mTerminalView.hasFocus()) ||
            (isSplitPaneActive() && mSecondaryTerminalView.hasFocus());
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        TerminalView activeView = getTerminalView();
        if (activeView != null)
            return activeView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_MICROPHONE_PERMISSION);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_MICROPHONE_PERMISSION:
                        Logger.logDebug(LOG_TAG, "Received intent to request microphone permission");
                        requestMicrophonePermission(false, true);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
