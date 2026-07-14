package com.autoformkit.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.InputType;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.autoformkit.app.report.FailureReporter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE_PHOTO = 2003;
    private static final int REQ_PERMISSION = 2004;
    private static final int REQ_SCAN_SN = 2005;
    private static final int REQ_SCAN_BASE = 2006;
    private static final int REQ_OCR_SN = 2007;
    private static final int REQ_OCR_BASE = 2008;
    private static final int REQ_RESCAN_UNIT_SN = 2009;
    private static final int REQ_RESCAN_UNIT_BASE_SN = 2010;
    private static final int REQ_PICK_A_STEP_PHOTO = 2011;
    private static final int REQ_CAPTURE_A_STEP_PHOTO = 2012;
    private static final int REQ_SCAN_A_STEP_ENTRY_SN = 2013;
    private static final int REQ_CAPTURE_A_STEP_ENTRY_PHOTO = 2014;
    private static final String CHANNEL_ID = "material_shortage";
    private static final String DEFAULT_API_BASE = "";
    private static final String DRAFT_KEY = "pending_form_draft_json";
    private static final String DRAFT_STORE_KEY = "pending_form_draft_store_json";
    // Manual, durable queue snapshot the user saves on purpose (kept until overwritten,
    // unlike the auto-draft which clears once everything is submitted or is discarded).
    private static final String MANUAL_QUEUE_KEY = "manual_saved_queue_json";
    // Local per-round ledger: each submit batch appends one round {ts, profileId, units:[{sn,submit,printed}]}.
    // This is the source of truth for print reconciliation — we read "what this round contained + its submit/
    // print outcome" from here, instead of reverse-inferring the round from the cloud's print-job list.
    private static final String ROUND_LEDGER_KEY = "round_ledger_json";
    private static final long ROUND_LEDGER_RETAIN_MS = 3L * 24 * 60 * 60 * 1000; // keep the last 3 days of rounds
    private static final String LAST_PROFILE_ID_KEY = "last_profile_id";
    private static final String DAILY_STATS_PREFIX = "daily_stats_";
    private static final String PENDING_PHOTO_INDEX_KEY = "pending_photo_index";
    private static final String PENDING_PHOTO_SIDE_KEY = "pending_photo_side";
    private static final String PENDING_PHOTO_PATH_KEY = "pending_photo_path";
    private static final String PENDING_PHOTO_FIELD_KEY = "pending_photo_field";
    private static final String PENDING_OCR_PHOTO_PATH_KEY = "pending_ocr_photo_path";
    private static final String PENDING_A_STEP_PHOTO_PATH_KEY = "pending_a_step_photo_path";
    private static final String PENDING_A_STEP_PHOTO_SEQ_KEY = "pending_a_step_photo_seq";
    private static final String PENDING_A_STEP_ENTRY_PHOTO_PATH_KEY = "pending_a_step_entry_photo_path";
    private static final String PENDING_RESCAN_SEQUENCE_KEY = "pending_rescan_sequence";
    private static final String WEB_FINGERPRINT_KEY = "web_client_fingerprint";
    private static final String EXTRA_EXPECTED_SN_LENGTH = "EXPECTED_SN_LENGTH";
    private static final String STATE_PENDING_PHOTO_INDEX = "state_pending_photo_index";
    private static final String STATE_PENDING_PHOTO_SIDE = "state_pending_photo_side";
    private static final String STATE_PENDING_PHOTO_FIELD = "state_pending_photo_field";
    private static final String STATE_PENDING_PHOTO_PATH = "state_pending_photo_path";
    private static final String STATE_PENDING_OCR_PHOTO_PATH = "state_pending_ocr_photo_path";
    private static final String STATE_PENDING_A_STEP_PHOTO_PATH = "state_pending_a_step_photo_path";
    private static final String STATE_PENDING_RESCAN_SEQUENCE = "state_pending_rescan_sequence";
    private static final int MAX_SN_CORRECTION_CANDIDATES = 32;
    private static final int MAX_SCAN_PRECHECK_CORRECTION_CANDIDATES = 16;
    private static final int SCAN_PRECHECK_CONNECT_TIMEOUT_MS = 2000;
    private static final int SCAN_PRECHECK_READ_TIMEOUT_MS = 4000;
    private static final int SCAN_PRECHECK_CORRECTION_THREADS = 4;
    private static final int SCAN_PRECHECK_CORRECTION_BUDGET_MS = 4000;
    private static final long SUBMIT_UNIT_INTERVAL_MS = 3000L;
    // One bad unit must not strand the rest of the batch: keep going, but bail if many fail in a row (systemic).
    private static final int MAX_CONSECUTIVE_SUBMIT_FAILURES = 3;
    private static final long SUBMIT_NETWORK_RETRY_BASE_MS = 3000L;
    private static final long SUBMIT_NETWORK_RETRY_MAX_MS = 30000L;
    private static final Pattern LOG_SEQUENCE_PATTERN = Pattern.compile("#\\d+");
    private static final Pattern LOG_SN_ASSIGNMENT_PATTERN = Pattern.compile("SN=([A-Z0-9]{8,32})");
    private static final Pattern LOG_SN_TOKEN_PATTERN = Pattern.compile("\\b[A-Z0-9]{8,32}\\b");
    private static final Pattern MATERIAL_CODE_PATTERN = Pattern.compile("\\bMR_[A-Za-z0-9_-]+\\b");

    private JSONArray profiles;      // 良品表单 only — what the model spinners list
    private JSONArray allProfiles;   // every published profile (incl. -defective) — for sibling lookup
    private JSONObject profile;
    private JSONObject catalogSettings; // global catalog `settings` block (notifyWebhook 等)；null=未迁移的旧安装
    // Panel-provided backend config ({backendApiBase,notifyWebhook,brand}) cached from <panelBase>/api/config.
    // null = unconfigured / not yet fetched; volatile because background Api threads read it via apiBase().
    private volatile JSONObject appConfig;
    private final List<UnitRecord> units = new ArrayList<>();
    private final Set<String> cachedMissingMaterialCodes = new HashSet<>();
    private final Set<String> notifiedMissingMaterialCodes = new HashSet<>();
    private final Map<String, Integer> scanPrecheckMissingCounts = new HashMap<>();
    private final LinkedHashMap<String, Integer> dnsAffectedUnits = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> roundMissingMaterials = new LinkedHashMap<>();
    static final ThreadLocal<DnsContext> currentDnsContext = new ThreadLocal<>();

    static final class DnsContext {
        final MainActivity activity;
        final UnitRecord unit;
        final int position;
        DnsContext(MainActivity activity, UnitRecord unit, int position) {
            this.activity = activity;
            this.unit = unit;
            this.position = position;
        }
    }
    private boolean missingMaterialNoticeShown = false;
    private boolean restoringDraft = false;
    private boolean draftPromptShown = false;
    private boolean profileSelectionReady = false;
    private boolean submitting = false;
    // Cross-app session: foreground auth-probe loop + live broadcast receiver (see SessionBridge).
    private final Handler authHandler = new Handler(Looper.getMainLooper());
    private Runnable authPoller;
    private long lastAuthCheckMs = 0L;
    private BroadcastReceiver sessionReceiver;
    private boolean printReconcileCloudVerify = false; // print-reconcile: false = local ledger view, true = cloud-verify view
    private volatile boolean reconcileDialogOpen = false; // true while the reconcile dialog shows — closing it stops the cloud-verify walk
    private int chineseTapCount = 0;
    private long chineseTapWindowStarted = 0L;

    private SharedPreferences prefs;
    private String lang = "zh";
    private String captchaClient = "";
    private String photoOrder = "fronts_then_backs";
    private int pendingPhotoIndex = -1;
    private String pendingPhotoSide = "";
    private String pendingPhotoField = "";
    private String pendingOutputPhotoPath = "";
    private Uri pendingOutputPhotoUri;
    private String pendingOcrPhotoPath = "";
    private Uri pendingOcrPhotoUri;
    private String pendingAStepPhotoOutputPath = "";
    private Uri pendingAStepPhotoOutputUri;
    private int pendingRescanUnitSequence = -1;
    private int pendingAStepPhotoUnitSequence = -1;
    private JSONObject cachedAClassStepOneTemplate;
    private JSONObject cachedAClassStepTwoTemplate;
    private String aStepEntrySn = "";
    private final List<String> aStepEntryPhotos = new ArrayList<>();
    private String pendingAStepEntryPhotoOutputPath = "";
    private Uri pendingAStepEntryPhotoOutputUri;
    private boolean aStepEntrySubmitting = false;
    // 报废录入「加功能不良」勾选：提交时把功能不良一并勾上、操作内容由未做任何操作换成检测。
    // 勾一次一直有效（连续报废不用逐台再勾），退出报废录入页或重启 App 才复位。
    private boolean aStepEntryFunctionalDefect = false;
    // 勾选「加功能不良」时按需拉取的 -defective 模板详情缓存（按模板 id）。勾选瞬间有后台预拉，
    // 与提交线程并发写同一 key，用并发 Map。
    private final Map<Integer, JSONObject> defectiveTemplateCache = new java.util.concurrent.ConcurrentHashMap<>();

    private TextView loginStatus;
    private TextView updateChannelText;
    private EditText accountEdit;
    private EditText passwordEdit;
    private EditText captchaEdit;
    private ImageView captchaView;
    private Spinner profileSpinner;
    private EditText snEdit;
    private EditText baseSnEdit;
    // snPlugins extras (彩盒SN/装箱号/…) beyond 机器SN(primary)/基站SN(secondary): field id -> its input box.
    // Rebuilt each showFormPage; empty for legacy profiles (no snPlugins) so nothing changes there.
    private final java.util.LinkedHashMap<String, EditText> pluginSnEdits = new java.util.LinkedHashMap<>();
    private RadioGroup gradeGroup;
    private TextView gradeLabel;
    private TextView baseLabel;
    private TextView basePrompt;
    private LinearLayout baseRow;
    private LinearLayout baseActionRow;
    private LinearLayout aStepPhotoPanel;
    private TextView aStepPhotoText;
    private Button aStepPhotoViewButton;
    private Spinner aStepEntrySpinner;
    private EditText aStepEntrySnEdit;
    private TextView aStepEntrySnDisplay;
    private TextView aStepEntryPhotoText;
    private LinearLayout aStepEntryPhotoList;
    private CheckBox aStepEntryFunctionalCheck;
    private TextView photoPrompt;
    private TextView summaryText;
    private TextView logText;
    private TextView missingMaterialsText;
    private TextView crashLogText;
    private LinearLayout unitList;
    private AlertDialog submitProgressDialog;
    private TextView submitProgressMessage;
    private ProgressBar submitProgressBar;
    private TextView submitProgressLabel;
    private int submitProgressTotal;
    private int submitProgressCompleted;
    private UpdateManager updateManager;
    private FormCatalogManager formCatalogManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        lang = prefs.getString("lang", "zh");
        restorePendingPhotoOutput(savedInstanceState);
        restorePendingOcrOutput(savedInstanceState);
        restorePendingAStepCapture(savedInstanceState);
        Diagnostics.append(this, "MainActivity onCreate");
        createNotificationChannel();
        requestRuntimePermissions();
        try {
            allProfiles = FormCatalog.loadProfiles(this);
            catalogSettings = FormCatalog.loadSettings(this); // 面板下发的全局设置（通知 webhook 等）；缺失=null，级联回退 prefs/默认
            appConfig = AppConfig.load(this); // 面板下发的后端配置（backendApiBase 等）；null=未配置/未缓存，登录/请求走「未配置」态
            // 自动分类：机型下拉/报废录入只列「良品」表单；不良品(-defective)表单不混进来，报废时按机型自动取
            // 它的 -defective 兄弟（defectiveProfileFor）。allProfiles 保留全部供兄弟查找用。
            profiles = filterGoodProfiles(allProfiles);
            profile = profiles.length() > 0 ? profiles.getJSONObject(0) : allProfiles.getJSONObject(0);
        } catch (Exception exc) {
            fatal("Profile load failed: " + exc.getMessage());
            return;
        }
        showSettingsPage();
        updateManager = new UpdateManager(this);
        updateManager.checkOnStartup();
        formCatalogManager = new FormCatalogManager(this);
        formCatalogManager.checkOnStartup();
        refreshAppConfigOnStartup(); // pull the latest panel backend config into cache + hot-swap (async, no-op if unconfigured)
        if (savedToken().isEmpty()) {
            refreshCaptcha();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PENDING_PHOTO_INDEX, pendingPhotoIndex);
        outState.putString(STATE_PENDING_PHOTO_SIDE, pendingPhotoSide);
        outState.putString(STATE_PENDING_PHOTO_FIELD, pendingPhotoField);
        outState.putString(STATE_PENDING_PHOTO_PATH, pendingOutputPhotoPath);
        outState.putString(STATE_PENDING_OCR_PHOTO_PATH, pendingOcrPhotoPath);
        outState.putString(STATE_PENDING_A_STEP_PHOTO_PATH, pendingAStepPhotoOutputPath);
        outState.putInt(STATE_PENDING_RESCAN_SEQUENCE, pendingRescanUnitSequence);
        Diagnostics.append(this, "MainActivity state saved");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Diagnostics.append(this, "Configuration changed: keyboard=" + newConfig.keyboard
            + " keyboardHidden=" + newConfig.keyboardHidden
            + " hardKeyboardHidden=" + newConfig.hardKeyboardHidden
            + " navigation=" + newConfig.navigation);
        if (!units.isEmpty()) saveDraft();
        if (baseSnEdit != null && baseSnEdit.hasFocus()) {
            refocusBaseInput();
        } else {
            refocusSnInput();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updateManager != null) {
            updateManager.resumePendingInstall();
            updateManager.checkOnForeground();
        }
        if (formCatalogManager != null) {
            formCatalogManager.checkOnForeground();
        }
        registerSessionReceiver();
        if (prefs.getBoolean(SessionEventReceiver.PENDING_LOGOUT_KEY, false)) {
            // A peer logged us out while we were backgrounded (the static receiver left a flag).
            handleRemoteLogout(false);
        } else {
            refreshLoginStatus();
            // Foreground active-probe: catches a server-side kick (another device logged in) that no
            // broadcast can surface, since that device isn't on this machine's IPC bus.
            startAuthPolling();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAuthPolling();
        unregisterSessionReceiver();
    }

    private void showSettingsPage() {
        missingMaterialsText = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF1F5F9);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        TextView title = text(applyBrand(t("settings_title")), 26, true);
        title.setTextColor(0xFF0F172A);
        root.addView(title);
        TextView subtitle = text(t("settings_subtitle"), 13, false);
        subtitle.setTextColor(0xFF64748B);
        subtitle.setPadding(0, dp(3), 0, dp(10));
        root.addView(subtitle);

        // Unconfigured banner: no panel address yet → the app can't reach any backend. Make the
        // requirement impossible to miss and keep it here until a panel config is successfully loaded.
        if (!backendConfigured()) {
            TextView banner = text(t("panel_required_detail"), 13, true);
            banner.setTextColor(0xFF92400E);
            banner.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable bannerBg = new GradientDrawable();
            bannerBg.setColor(0xFFFEF3C7);
            bannerBg.setStroke(dp(1), 0xFFFCD34D);
            bannerBg.setCornerRadius(dp(8));
            banner.setBackground(bannerBg);
            LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bannerParams.setMargins(0, dp(6), 0, dp(2));
            banner.setLayoutParams(bannerParams);
            root.addView(banner);
        }

        LinearLayout languagePanel = panel();
        languagePanel.addView(compactLabel(t("language")));
        LinearLayout langRow = row();
        langRow.addView(button(languageLabel("zh"), v -> {
            handleChineseLanguageTap();
            if (!"zh".equals(lang)) switchLanguage("zh");
        }));
        langRow.addView(button(languageLabel("en"), v -> switchLanguage("en")));
        langRow.addView(button(languageLabel("es"), v -> switchLanguage("es")));
        languagePanel.addView(langRow);
        updateChannelText = text(updateChannelStatusText(), 12, false);
        updateChannelText.setTextColor(0xFF64748B);
        updateChannelText.setPadding(0, dp(6), 0, 0);
        languagePanel.addView(updateChannelText);
        root.addView(languagePanel);

        LinearLayout loginPanel = panel();
        loginPanel.addView(compactLabel(t("login")));
        accountEdit = edit(t("account"));
        accountEdit.setText(prefs.getString("account", ""));
        loginPanel.addView(accountEdit);
        passwordEdit = edit(t("password"));
        passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordEdit.setText(SecureTokenStore.getPassword(prefs));   // remembered from the last sign-in
        loginPanel.addView(passwordEdit);

        // The captcha is only needed for a fresh sign-in. While we still hold a session token (i.e. not
        // kicked offline) the captcha image / input / refresh button are hidden and never fetched — the
        // single button below just re-enters the form. A kick clears the token, and showSettingsPage()
        // then rebuilds this panel with the captcha shown + auto-refreshed.
        boolean needLogin = savedToken().isEmpty();
        if (needLogin) {
            LinearLayout captchaRow = row();
            captchaView = new ImageView(this);
            captchaView.setAdjustViewBounds(true);
            captchaRow.addView(captchaView, new LinearLayout.LayoutParams(0, dp(64), 1));
            captchaRow.addView(button(t("refresh_captcha"), v -> refreshCaptcha()));
            loginPanel.addView(captchaRow);
            captchaEdit = edit(t("captcha"));
            loginPanel.addView(captchaEdit);
        } else {
            captchaView = null;
            captchaEdit = null;
        }

        // One primary action, right-aligned. Fresh sign-in → "登录并进入" runs login() (which enters the
        // form on success); already signed in → "进入录表单" verifies the token is still live, then enters.
        LinearLayout loginRow = row();
        loginRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        loginRow.addView(button(t(needLogin ? "login_save" : "enter_form"), v -> {
            if (savedToken().isEmpty()) {
                login();
            } else {
                verifyAccessThenShowForm();
            }
        }));
        loginPanel.addView(loginRow);
        root.addView(loginPanel);

        root.addView(dailyStatsView());

        // Panel connection: the app points at a form system by its panel address (+ access key).
        // Both default to empty — the user must fill them in; the backend base, catalog and notify
        // webhook are then all driven by that panel. Saving persists + reconnects (fetch + re-sync).
        LinearLayout panelPanel = panel();
        panelPanel.addView(compactLabel(t("panel_connection")));
        TextView panelHint = text(t("panel_connection_hint"), 12, false);
        panelHint.setTextColor(0xFF64748B);
        panelPanel.addView(panelHint);

        panelPanel.addView(compactLabel(t("panel_base")));
        final EditText panelBaseEdit = edit(t("panel_base_hint"));
        panelBaseEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        panelBaseEdit.setText(AppConfig.panelBase(this));
        panelPanel.addView(panelBaseEdit);

        panelPanel.addView(compactLabel(t("catalog_key")));
        final EditText catalogKeyEdit = edit(t("catalog_key_hint"));
        catalogKeyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        catalogKeyEdit.setText(AppConfig.catalogKey(this));
        panelPanel.addView(catalogKeyEdit);

        panelPanel.addView(button(t("panel_save"), v -> savePanelConnection(
            panelBaseEdit.getText().toString(), catalogKeyEdit.getText().toString())));

        // Read-only: which backend is actually in effect right now (from the cached panel config).
        TextView panelCurrent = text(t("panel_current_api")
            + (backendConfigured() ? apiBase() : t("panel_unconfigured")), 12, false);
        panelCurrent.setTextColor(0xFF64748B);
        panelCurrent.setTextIsSelectable(true);
        panelCurrent.setPadding(0, dp(6), 0, 0);
        panelPanel.addView(panelCurrent);
        root.addView(panelPanel);

        logText = text("", 12, false);
        logText.setTextColor(0xFF475569);
        root.addView(logText);
        root.addView(compactLabel(t("last_crash_title")));
        crashLogText = text(lastCrashText(), 12, false);
        crashLogText.setTextColor(0xFF475569);
        crashLogText.setTextIsSelectable(true);
        root.addView(crashLogText);
        setContentView(scroll);
        refreshLoginStatus();
    }

    private void showFormPage() {
        showFormPage(true);
    }

    /** Re-read the cached catalog (freshly synced from the panel) into the in-memory profile list, so a
     *  fresh install or a new publish shows the real forms without needing an app restart. Keeps the
     *  current selection if its id still exists. No-op if nothing usable is cached. */
    private void reloadCatalogProfiles() {
        try {
            JSONArray reloaded = FormCatalog.loadProfiles(this);
            if (reloaded == null || reloaded.length() == 0) return;
            String currentId = (profile != null) ? profile.optString("id", "") : "";
            allProfiles = reloaded;
            catalogSettings = FormCatalog.loadSettings(this);
            profiles = filterGoodProfiles(allProfiles);
            JSONObject keep = null;
            for (int i = 0; i < profiles.length(); i++) {
                if (profiles.getJSONObject(i).optString("id", "").equals(currentId)) { keep = profiles.getJSONObject(i); break; }
            }
            profile = keep != null ? keep : (profiles.length() > 0 ? profiles.getJSONObject(0)
                : (allProfiles.length() > 0 ? allProfiles.getJSONObject(0) : profile));
        } catch (Exception ignored) {
            // keep the currently loaded profiles on any error
        }
    }

    private void showFormPage(boolean promptSavedDraft) {
        if (savedToken().isEmpty()) {
            showSettingsPage();
            return;
        }
        reloadCatalogProfiles(); // pick up a freshly-synced catalog (fresh install / new publish) without an app restart
        profileSelectionReady = false;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF1F5F9);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        String account = prefs.getString("userName", "");
        if (account.isEmpty()) account = prefs.getString("account", "");
        LinearLayout headerPanel = panel();
        TextView title = text(t("form_title"), 24, true);
        title.setTextColor(0xFF0F172A);
        headerPanel.addView(title);
        TextView accountText = text(account, 16, true);
        accountText.setTextColor(0xFF0F766E);
        accountText.setPadding(0, dp(4), 0, dp(8));
        headerPanel.addView(accountText);
        LinearLayout headerActions = row();
        // Logout takes the flexible (weight) slot so that on narrow screens it shrinks first \u2014 the
        // "\u7b2c\u4e00\u6b65\u5f55\u5165" entry and the settings gear stay fully visible and never get pushed off-screen.
        headerActions.addView(button(t("logout"), v -> logoutToSettings()),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerActions.addView(button(t("a_step_entry_title"), v -> showAStepEntryPage()));
        headerActions.addView(iconButton("\u2699", v -> showFormSettingsDialog()));
        headerPanel.addView(headerActions);
        missingMaterialsText = text("", 12, false);
        missingMaterialsText.setTextColor(0xFFB45309);
        missingMaterialsText.setPadding(0, dp(8), 0, 0);
        missingMaterialsText.setVisibility(View.GONE);
        headerPanel.addView(missingMaterialsText);
        root.addView(headerPanel);

        LinearLayout setupPanel = panel();
        setupPanel.addView(compactLabel(t("form")));
        profileSpinner = new Spinner(this);
        profileSpinner.setAdapter(new ProfileSpinnerAdapter());
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try {
                    if (profileSelectionReady && !restoringDraft && hasUnsubmittedUnits()) saveDraft();
                    profile = profiles.getJSONObject(position);
                    if (!profileSelectionReady) return;
                    if (restoringDraft) {
                        refreshFormUi();
                        return;
                    }
                    saveLastProfile();
                    restoreCurrentProfileDraftOrEmpty();
                } catch (JSONException exc) {
                    toast(exc.getMessage());
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        setupPanel.addView(profileSpinner);
        applyLastProfileSelection();

        gradeLabel = compactLabel(t("grade_class"));
        setupPanel.addView(gradeLabel);
        gradeGroup = new RadioGroup(this);
        gradeGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (String grade : new String[]{"A", "B", "C"}) {
            RadioButton radio = new RadioButton(this);
            radio.setText(grade);
            radio.setTextSize(24);
            radio.setMinHeight(dp(72));
            radio.setGravity(Gravity.CENTER);
            radio.setPadding(dp(12), dp(8), dp(12), dp(8));
            radio.setButtonDrawable(null);
            radio.setId(grade.charAt(0));
            gradeGroup.addView(radio, new RadioGroup.LayoutParams(0, dp(76), 1f));
        }
        gradeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            updateGradeButtons();
            refreshAStepPhotoUi();
            refocusSnInput();
        });
        gradeGroup.clearCheck();
        updateGradeButtons();
        setupPanel.addView(gradeGroup);
        root.addView(setupPanel);

        LinearLayout capturePanel = panel();
        capturePanel.addView(compactLabel(t("robot_sn")));
        LinearLayout snRow = row();
        snEdit = edit("SN");
        snEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        snEdit.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
                handleSnEnter();
                return true;
            }
            return false;
        });
        snEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_NEXT
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                handleSnEnter();
                return true;
            }
            return false;
        });
        snRow.addView(snEdit, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        capturePanel.addView(snRow);
        LinearLayout snActionRow = row();
        snActionRow.addView(button(t("scan_sn"), v -> startSnScan(false)));
        snActionRow.addView(button(t("add"), v -> addTypedSn()));
        capturePanel.addView(snActionRow);

        baseLabel = compactLabel(t("base_sn"));
        capturePanel.addView(baseLabel);
        basePrompt = text("", 14, true);
        basePrompt.setTextColor(0xFF334155);
        capturePanel.addView(basePrompt);
        baseRow = row();
        baseSnEdit = edit(t("base_sn"));
        baseSnEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        baseSnEdit.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
                handleBaseEnter();
                return true;
            }
            return false;
        });
        baseSnEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_NEXT
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                handleBaseEnter();
                return true;
            }
            return false;
        });
        baseRow.addView(baseSnEdit, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        capturePanel.addView(baseRow);
        baseActionRow = row();
        baseActionRow.addView(button(t("scan_base"), v -> startSnScan(true)));
        baseActionRow.addView(button(t("match"), v -> addBaseSn()));
        capturePanel.addView(baseActionRow);

        // snPlugins extras (彩盒SN/装箱号/…) beyond 机器SN/基站SN. Rendered as input rows here; each value
        // is snapshotted onto a unit when it's added (addSnRecord) and sent as data[field] at submit.
        // Legacy profiles (snPlugins==null) skip this entirely. (Scan-into-extra-box is a follow-up.)
        pluginSnEdits.clear();
        JSONArray extraPlugins = snPlugins();
        for (int pi = 0; extraPlugins != null && pi < extraPlugins.length(); pi++) {
            JSONObject pl = extraPlugins.optJSONObject(pi);
            if (pl == null || !isExtraPluginKey(pl.optString("key"))) continue;
            String pField = pl.optString("field");
            if (pField.isEmpty()) continue;
            // en/es via labelI18n sibling map; missing translation (or old profile) falls back to the
            // zh label, and a wholly absent label falls back to the field id (original behavior).
            String pLabel = localized(pl, "label", "labelI18n");
            if (pLabel.isEmpty()) pLabel = pField;
            capturePanel.addView(compactLabel(pLabel + (pl.optBoolean("required") ? " *" : "")));
            LinearLayout plRow = row();
            EditText plEdit = edit(pl.optString("placeholder", pLabel));
            if ("carton".equals(pl.optString("key"))) plEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            plRow.addView(plEdit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            capturePanel.addView(plRow);
            pluginSnEdits.put(pField, plEdit);
        }

        aStepPhotoPanel = aStepPhotoBox();
        capturePanel.addView(aStepPhotoPanel);
        capturePanel.addView(compactLabel(t("photos")));
        photoPrompt = text("", 14, true);
        photoPrompt.setTextColor(0xFF334155);
        capturePanel.addView(photoPrompt);
        capturePanel.addView(button(t("take_next_photo"), v -> captureNextPhoto()));
        root.addView(capturePanel);

        LinearLayout submitPanel = panel();
        submitPanel.addView(compactLabel(t("submit")));
        LinearLayout submitRow = row();
        // "Check first two steps" button removed — the previous steps are already checked at SN entry.
        submitRow.addView(button(t("submit_batch"), v -> submitBatch()));
        submitRow.addView(button(t("print_reconcile_open"), v -> showPrintReconcileDialog()));
        submitPanel.addView(submitRow);

        summaryText = text("", 13, false);
        summaryText.setTextColor(0xFF475569);
        summaryText.setPadding(0, dp(8), 0, dp(4));
        submitPanel.addView(summaryText);
        unitList = new LinearLayout(this);
        unitList.setOrientation(LinearLayout.VERTICAL);
        submitPanel.addView(unitList);
        logText = text("", 12, false);
        logText.setTextColor(0xFF475569);
        submitPanel.addView(logText);
        root.addView(submitPanel);
        setContentView(scroll);
        refreshFormUi();
        resetGradeSelection();
        refocusSnInput();
        if (promptSavedDraft) maybePromptSavedDraft();
        if (profileSpinner != null) {
            profileSpinner.post(() -> profileSelectionReady = true);
        } else {
            profileSelectionReady = true;
        }
    }

    private void switchLanguage(String value) {
        lang = value;
        prefs.edit().putString("lang", value).apply();
        if (unitList == null) {
            showSettingsPage();
        } else {
            showFormPage();
        }
    }

    // ===== 第一步录入 (standalone appearance-reject step-one entry) =====
    // A dedicated page reachable from the form header (the button left of the gear). The operator
    // picks the model, scans/types ONE SN, takes ONE photo, then submits a single A-class step-one
    // record carrying the fixed "外观不良/报废" verdict — 外观不通过 / 划痕 / 脏污 / 无法维修 /
    // 不良品 / 未做任何操作. It reuses the current login session and the same step-one template
    // discovery as autoCreateGradeAPreviousSteps, but fills the FAIL options instead of PASS.

    private void showAStepEntryPage() {
        if (savedToken().isEmpty()) {
            showSettingsPage();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF1F5F9);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        LinearLayout headerPanel = panel();
        LinearLayout headerRow = row();
        headerRow.addView(button(t("go_back"), v -> {
            // The entry spinner may have left `profile` (and the step-one template cache) pointing at a
            // different model than the form's. Drop the cache so the form re-fetches for its own profile.
            cachedAClassStepOneTemplate = null;
            cachedAClassStepTwoTemplate = null;
            // 「加功能不良」只在报废录入页内保持，离开页面即复位。
            aStepEntryFunctionalDefect = false;
            showFormPage(false);
        }));
        View spacer = new View(this);
        headerRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        headerPanel.addView(headerRow);
        TextView title = text(t("a_step_entry_title"), 24, true);
        title.setTextColor(0xFF0F172A);
        headerPanel.addView(title);
        TextView subtitle = text(t("a_step_entry_subtitle"), 13, false);
        subtitle.setTextColor(0xFF64748B);
        subtitle.setPadding(0, dp(3), 0, 0);
        headerPanel.addView(subtitle);
        root.addView(headerPanel);

        LinearLayout setupPanel = panel();
        setupPanel.addView(compactLabel(t("form")));
        aStepEntrySpinner = new Spinner(this);
        aStepEntrySpinner.setAdapter(new ProfileSpinnerAdapter());
        aStepEntrySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try {
                    JSONObject next = profiles.getJSONObject(position);
                    boolean changed = profile == null || !profile.optString("id").equals(next.optString("id"));
                    profile = next;
                    if (changed) {
                        // model switched: drop the cached step-one template so submit re-fetches the right one
                        cachedAClassStepOneTemplate = null;
                        cachedAClassStepTwoTemplate = null;
                    }
                } catch (JSONException exc) {
                    toast(exc.getMessage());
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        int currentIndex = findProfileIndex(currentProfileId());
        if (currentIndex >= 0) aStepEntrySpinner.setSelection(currentIndex);
        setupPanel.addView(aStepEntrySpinner);
        root.addView(setupPanel);

        LinearLayout capturePanel = panel();
        capturePanel.addView(compactLabel(t("robot_sn")));
        aStepEntrySnEdit = edit("SN");
        aStepEntrySnEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        aStepEntrySnEdit.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
                applyTypedAStepEntrySn();
                return true;
            }
            return false;
        });
        aStepEntrySnEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_NEXT
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                applyTypedAStepEntrySn();
                return true;
            }
            return false;
        });
        capturePanel.addView(aStepEntrySnEdit);
        LinearLayout snActionRow = row();
        snActionRow.addView(button(t("scan_sn"), v -> startAStepEntryScan()));
        snActionRow.addView(button(t("add"), v -> applyTypedAStepEntrySn()));
        snActionRow.addView(button(t("a_step_entry_clear_sn"), v -> clearAStepEntrySn()));
        capturePanel.addView(snActionRow);
        aStepEntrySnDisplay = text("", 20, true);
        aStepEntrySnDisplay.setTextColor(0xFF0F766E);
        aStepEntrySnDisplay.setPadding(0, dp(6), 0, dp(4));
        capturePanel.addView(aStepEntrySnDisplay);

        capturePanel.addView(compactLabel(t("a_step_entry_photo")));
        aStepEntryPhotoText = text("", 13, false);
        aStepEntryPhotoText.setTextColor(0xFF334155);
        capturePanel.addView(aStepEntryPhotoText);
        aStepEntryPhotoList = new LinearLayout(this);
        aStepEntryPhotoList.setOrientation(LinearLayout.VERTICAL);
        capturePanel.addView(aStepEntryPhotoList);
        capturePanel.addView(button(t("a_step_entry_add_photo"), v -> captureAStepEntryPhoto()));
        root.addView(capturePanel);

        LinearLayout submitPanel = panel();
        submitPanel.addView(compactLabel(t("submit")));
        TextView verdict = text(t("a_step_entry_verdict"), 12, false);
        verdict.setTextColor(0xFF92400E);
        submitPanel.addView(verdict);
        aStepEntryFunctionalCheck = new CheckBox(this);
        aStepEntryFunctionalCheck.setText(t("a_step_entry_functional"));
        aStepEntryFunctionalCheck.setTextSize(13);
        aStepEntryFunctionalCheck.setTextColor(0xFF334155);
        aStepEntryFunctionalCheck.setChecked(aStepEntryFunctionalDefect);
        aStepEntryFunctionalCheck.setOnCheckedChangeListener((btn, checked) -> {
            aStepEntryFunctionalDefect = checked;
            // 勾选瞬间就后台预拉 -defective 模板详情（带缓存），把首次提交要付的那次模板请求提前做掉。
            if (checked) prefetchDefectiveTemplateDetail();
        });
        submitPanel.addView(aStepEntryFunctionalCheck);
        submitPanel.addView(button(t("a_step_entry_submit"), v -> submitAStepEntry()));
        root.addView(submitPanel);

        setContentView(scroll);
        refreshAStepEntryUi();
    }

    private void refreshAStepEntryUi() {
        if (aStepEntrySnDisplay != null) {
            aStepEntrySnDisplay.setText(aStepEntrySn.isEmpty() ? t("a_step_entry_sn_empty") : aStepEntrySn);
        }
        if (aStepEntryPhotoText != null) {
            int count = aStepEntryPhotos.size();
            aStepEntryPhotoText.setText(count == 0 ? t("a_step_entry_no_photo") : t("a_step_entry_photo_count") + count);
        }
        if (aStepEntryPhotoList != null) {
            aStepEntryPhotoList.removeAllViews();
            for (int i = 0; i < aStepEntryPhotos.size(); i++) {
                final String path = aStepEntryPhotos.get(i);
                LinearLayout rowView = row();
                TextView nameView = text(t("a_step_entry_photo_item") + (i + 1), 14, false);
                nameView.setTextColor(0xFF334155);
                rowView.addView(nameView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                rowView.addView(button(t("view_photo"), v -> showPhotoPreview(t("a_step_entry_photo"), path)));
                rowView.addView(button(t("delete_photo"), v -> {
                    aStepEntryPhotos.remove(path);
                    deleteFileQuietly(path);
                    refreshAStepEntryUi();
                }));
                aStepEntryPhotoList.addView(rowView);
            }
        }
    }

    private void applyTypedAStepEntrySn() {
        if (aStepEntrySnEdit == null) return;
        String sn = normalize(aStepEntrySnEdit.getText().toString());
        if (sn.isEmpty()) {
            toast(t("sn_required"));
            return;
        }
        if (isPureNumeric(sn)) {
            alert(t("scan_not_sn_title"), t("scan_not_sn_detail"));
            return;
        }
        setAStepEntrySn(sn);
        aStepEntrySnEdit.setText("");
    }

    private void setAStepEntrySn(String sn) {
        aStepEntrySn = sn;
        showScannedSnPreview(sn, t("robot_sn"));
        refreshAStepEntryUi();
    }

    private void startAStepEntryScan() {
        if (!ensureCameraPermission()) return;
        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.setClass(this, ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", t("scan_robot_sn"));
        intent.putExtra("lang", lang);
        intent.putExtra("WHITE_LABEL_MODE", scannerWhiteLabelMode(false));
        intent.putExtra("REJECT_NUMERIC_ONLY", true);
        intent.putExtra("OCR_ONLY", false);
        intent.putExtra(EXTRA_EXPECTED_SN_LENGTH, expectedRobotSnLength());
        intent.putExtra("SCAN_CAMERA_ID", 0);
        intent.putExtra("SCAN_ORIENTATION_LOCKED", true);
        intent.putExtra("BEEP_ENABLED", false);
        intent.putExtra("BARCODE_IMAGE_ENABLED", false);
        intent.putExtra("SHOW_MISSING_CAMERA_PERMISSION_DIALOG", false);
        try {
            startActivityForResult(intent, REQ_SCAN_A_STEP_ENTRY_SN);
        } catch (ActivityNotFoundException exc) {
            alert(t("scanner_missing_title"), t("scanner_missing_detail"));
        } catch (Exception exc) {
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private void handleAStepEntryScanResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return;
        String ocrPhotoPath = data.getStringExtra("OCR_PHOTO_PATH");
        if (ocrPhotoPath != null && !ocrPhotoPath.trim().isEmpty()) {
            recognizeAStepEntrySnFromPhoto(new File(ocrPhotoPath));
            return;
        }
        String value = normalize(data.getStringExtra("SCAN_RESULT"));
        if (value.isEmpty()) {
            toast(t("sn_required"));
            return;
        }
        if (isPureNumeric(value)) {
            alert(t("scan_not_sn_title"), t("scan_not_sn_detail"));
            return;
        }
        setAStepEntrySn(value);
    }

    // Mirrors the main form's in-scanner OCR fallback, but targets only the entry SN field.
    private void recognizeAStepEntrySnFromPhoto(File photoFile) {
        String recognizeTextUrl = prefs.getString("recognizeTextUrl", "").trim();
        if (recognizeTextUrl.isEmpty()) {
            toast(t("ocr_auto_no_text"));
            return;
        }
        appendLog(t("ocr_running"));
        new Thread(() -> {
            if (!backendConfigured()) { notifyBackendUnconfigured(); return; }
            try {
                Api api = new Api(apiBase(), savedToken(), webFingerprint(), webOrigin(), webReferer(), endpoints());
                List<File> images = prepareSnRecognitionImages(photoFile);
                for (File image : images) {
                    try {
                        JSONObject body = api.recognizeText(recognizeTextUrl, image);
                        List<String> candidates = extractOcrCandidates(body);
                        if (!candidates.isEmpty()) {
                            runOnUiThread(() -> showAStepEntryOcrCandidates(candidates));
                            return;
                        }
                    } catch (Exception imageExc) {
                        Diagnostics.append(this, "A-step entry OCR image skipped: " + conciseError(imageExc));
                    }
                }
                runOnUiThread(() -> alert(t("ocr_no_text_title"), t("ocr_no_text_detail")));
            } catch (Exception exc) {
                runOnUiThread(() -> alert(t("ocr_failed"), conciseError(exc)));
            }
        }).start();
    }

    private void showAStepEntryOcrCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            alert(t("ocr_no_text_title"), t("ocr_no_text_detail"));
            return;
        }
        String[] items = candidates.toArray(new String[0]);
        if (!activityAlive()) return;
        new AlertDialog.Builder(this)
            .setTitle(t("ocr_choose_title"))
            .setItems(items, (dialog, which) -> {
                String value = normalize(items[which]);
                if (value.isEmpty() || isPureNumeric(value)) {
                    alert(t("scan_not_sn_title"), t("scan_not_sn_detail"));
                    return;
                }
                setAStepEntrySn(value);
            })
            .setNegativeButton(t("cancel"), null)
            .show();
    }

    private void captureAStepEntryPhoto() {
        if (!ensureCameraPermission()) return;
        try {
            File outputFile = createAStepEntryPhotoOutputFile();
            pendingAStepEntryPhotoOutputPath = outputFile.getAbsolutePath();
            pendingAStepEntryPhotoOutputUri = SimplePhotoProvider.uriForFile(this, outputFile);
            prefs.edit().putString(PENDING_A_STEP_ENTRY_PHOTO_PATH_KEY, pendingAStepEntryPhotoOutputPath).apply();
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingAStepEntryPhotoOutputUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<ResolveInfo> cameraApps = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (intent.resolveActivity(getPackageManager()) != null && !cameraApps.isEmpty()) {
                for (ResolveInfo cameraApp : cameraApps) {
                    grantUriPermission(
                        cameraApp.activityInfo.packageName,
                        pendingAStepEntryPhotoOutputUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                }
                startActivityForResult(intent, REQ_CAPTURE_A_STEP_ENTRY_PHOTO);
                return;
            }
            Intent fallback = new Intent(this, CaptureActivity.class);
            fallback.putExtra("fileName", outputFile.getName());
            fallback.putExtra("label", t("a_step_entry_photo"));
            fallback.putExtra("lang", lang);
            startActivityForResult(fallback, REQ_CAPTURE_A_STEP_ENTRY_PHOTO);
        } catch (Exception exc) {
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private File createAStepEntryPhotoOutputFile() throws IOException {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create photo directory");
        }
        String sn = aStepEntrySn.isEmpty() ? "unit" : aStepEntrySn;
        return new File(dir, safePhotoFileName("a-step-entry-" + sn + "-" + System.currentTimeMillis() + ".jpg"));
    }

    private void handleAStepEntryPhotoResult(int resultCode, Intent data) {
        if (pendingAStepEntryPhotoOutputUri != null) {
            try {
                revokeUriPermission(
                    pendingAStepEntryPhotoOutputUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
        }
        pendingAStepEntryPhotoOutputUri = null;
        String pendingPath = pendingAStepEntryPhotoOutputPath;
        if (pendingPath == null || pendingPath.isEmpty()) {
            pendingPath = prefs.getString(PENDING_A_STEP_ENTRY_PHOTO_PATH_KEY, "");
        }
        pendingAStepEntryPhotoOutputPath = "";
        prefs.edit().remove(PENDING_A_STEP_ENTRY_PHOTO_PATH_KEY).apply();
        if (resultCode != RESULT_OK) return;
        String path = data == null ? "" : data.getStringExtra("photoPath");
        if (path == null || path.isEmpty()) path = pendingPath;
        File photoFile = path == null || path.isEmpty() ? null : new File(path);
        if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0) {
            alert(t("photo_save_failed"), t("photo_full_file_missing"));
            return;
        }
        aStepEntryPhotos.add(path);
        refreshAStepEntryUi();
        toast(t("a_step_photo_selected"));
    }

    private void submitAStepEntry() {
        if (aStepEntrySubmitting) {
            toast(t("submit_running"));
            return;
        }
        String token = savedToken();
        if (token.isEmpty()) {
            alert(t("login_required"), t("login_required_detail"));
            return;
        }
        if (profile == null) {
            toast(t("a_step_entry_need_model"));
            return;
        }
        if (aStepEntrySn.isEmpty()) {
            toast(t("a_step_entry_need_sn"));
            return;
        }
        final List<String> photos = new ArrayList<>();
        for (String p : aStepEntryPhotos) {
            if (hasFile(p)) photos.add(p);
        }
        if (photos.isEmpty()) {
            toast(t("a_step_entry_need_photo"));
            return;
        }
        final String sn = aStepEntrySn;
        final boolean functionalDefect = aStepEntryFunctionalDefect;
        aStepEntrySubmitting = true;
        showSubmitLoading(1);
        setSubmitProgressMessage(formatSubmitProgressUnit(1, 1, sn));
        new Thread(() -> {
            String error = null;
            if (!backendConfigured()) {
                runOnUiThread(() -> { aStepEntrySubmitting = false; hideSubmitLoading(); notifyBackendUnconfigured(); });
                return;
            }
            // Pre-submit auth gate: an expired/kicked token becomes a clear re-login instead of a
            // confusing upload failure. UNKNOWN (network blip) proceeds.
            if (new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints()).checkAuth() == Api.AuthState.INVALID) {
                runOnUiThread(() -> {
                    aStepEntrySubmitting = false;
                    hideSubmitLoading();
                    handleRemoteLogout(true);
                });
                return;
            }
            try {
                Api api = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints());
                // 报废照片并行上传(原来逐张串行是提交最慢的点);保持顺序,任一张失败则整体失败。
                List<String> urls = uploadImagesParallel(api, photos, sn + "-a-step-entry-");
                String imageUrls = join(urls, ",");
                // Profile-driven 报废: if the selected model has a 不良品 (-defective) profile, build the
                // payload from ITS panel-editable fixed values; otherwise fall back to the hardcoded verdict.
                JSONObject defProfile = defectiveProfileFor(profile);
                JSONObject payload = (defProfile != null)
                    ? buildDefectiveProfilePayload(defProfile, sn, imageUrls, functionalDefect)
                    : buildAStepEntryRejectPayload(aClassStepTemplate(api, 1), sn, imageUrls, functionalDefect);
                // 勾了「加功能不良」且走 profile 路径：profile 固定值里既没有功能测试字段的值也没有
                // 「检测」的真实选项值（发布的 profile 不带完整选项表），必须拉一次模板详情按真实选项补。
                if (functionalDefect && defProfile != null) overlayFunctionalDefectFromTemplate(api, defProfile, payload);
                if (functionalDefect) applyFunctionalDefectOverrides(defProfile, payload);
                submitAutoStepPayload(api, payload, sn + " " + t("a_step_one"));
            } catch (Exception exc) {
                error = conciseError(exc);
                reportSubmitFailure(null, 0, exc);
            }
            final String finalError = error;
            runOnUiThread(() -> {
                aStepEntrySubmitting = false;
                hideSubmitLoading();
                if (finalError == null) {
                    setSubmitProgress(1);
                    for (String p : photos) deleteFileQuietly(p);
                    aStepEntryPhotos.clear();
                    aStepEntrySn = "";
                    refreshAStepEntryUi();
                    if (aStepEntrySnEdit != null) {
                        aStepEntrySnEdit.setText("");
                        aStepEntrySnEdit.requestFocus();
                    }
                    autoDismissAlert(t("done"), t("a_step_entry_done") + "\n" + sn, 2500);
                } else {
                    alert(t("submit_failed"), finalError);
                }
            });
        }).start();
    }

    // Builds the step-one payload carrying the fixed "外观不良" verdict. Mirrors buildAutoStepPayload
    // but fills the FAIL options and routes the photo into every upload field (the unit is 不良品, so
    // the optional 不良品照片 should also carry it).
    // 并行上传多张图片,保持原顺序返回 URL。任一张失败 → 抛第一个异常(整体失败,与原串行语义一致)。
    // 报废/良品提交原来逐张串行上传是最大耗时点;并发上限 4,每张各自带 withTransientRetry。
    private List<String> uploadImagesParallel(Api api, List<String> paths, String namePrefix) throws Exception {
        int n = paths == null ? 0 : paths.size();
        if (n == 0) return new ArrayList<>();
        if (n == 1) {
            List<String> one = new ArrayList<>();
            one.add(uploadCompressed(api, new File(paths.get(0)), namePrefix + "1.jpg"));
            return one;
        }
        final String[] out = new String[n];
        int threads = Math.min(4, n);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    out[idx] = uploadCompressed(api, new File(paths.get(idx)), namePrefix + (idx + 1) + ".jpg");
                    return null;
                }));
            }
            Exception failure = null;
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (failure == null) failure = (c instanceof Exception) ? (Exception) c : new Exception(c);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (failure == null) failure = ie;
                }
            }
            if (failure != null) throw failure;
        } finally {
            executor.shutdownNow();
        }
        return new ArrayList<>(java.util.Arrays.asList(out));
    }

    // 报废照片来自系统相机原图（动辄 3-12MB），原样上传是「提交慢」的大头。上传前压到长边 1920 /
    // 质量 88（证据照片足够清晰，通常只剩几百 KB，快一个数量级）。压缩失败一律回退传原图，绝不丢单。
    private static final int UPLOAD_MAX_EDGE = 1920;
    private static final int UPLOAD_JPEG_QUALITY = 88;

    private String uploadCompressed(Api api, File source, String uploadName) throws Exception {
        File prepared = compressForUpload(source);
        try {
            return api.uploadImage(prepared, uploadName);
        } finally {
            if (!prepared.equals(source)) deleteFileQuietly(prepared.getAbsolutePath());
        }
    }

    private File compressForUpload(File source) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0) return source;
            int rotation = exifRotationDegrees(source);
            boolean fits = width <= UPLOAD_MAX_EDGE && height <= UPLOAD_MAX_EDGE;
            // 已经小图且不需要转方向：原样上传，省一次重编码。
            if (fits && rotation == 0 && source.length() <= 900 * 1024) return source;
            BitmapFactory.Options options = new BitmapFactory.Options();
            // 先按 2 的幂粗采样到 ≤2×目标，再精确缩到长边 1920，画质比直接深采样好。
            options.inSampleSize = sampleSize(width, height, UPLOAD_MAX_EDGE * 2, UPLOAD_MAX_EDGE * 2);
            Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (bitmap == null) return source;
            float scale = Math.min(1f, (float) UPLOAD_MAX_EDGE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (scale < 1f || rotation != 0) {
                Matrix matrix = new Matrix();
                if (scale < 1f) matrix.postScale(scale, scale);
                if (rotation != 0) matrix.postRotate(rotation);
                Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (transformed != bitmap) bitmap.recycle();
                bitmap = transformed;
            }
            File dir = new File(getCacheDir(), "upload-tmp");
            if (!dir.exists() && !dir.mkdirs()) {
                bitmap.recycle();
                return source;
            }
            File output = new File(dir, source.getName().replace(".jpg", "") + "-up.jpg");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, stream);
            }
            bitmap.recycle();
            // 罕见情况压完反而更大（如源图本就高压缩），传原图。
            if (output.length() <= 0 || output.length() >= source.length()) {
                deleteFileQuietly(output.getAbsolutePath());
                return source;
            }
            return output;
        } catch (Throwable error) {
            Diagnostics.append(this, "upload compress fallback: " + error);
            return source;
        }
    }

    private int exifRotationDegrees(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception exc) {
            return 0;
        }
    }

    // 勾选「加功能不良」瞬间后台预拉 -defective 模板详情进缓存；失败无所谓，提交时会正常再拉。
    private void prefetchDefectiveTemplateDetail() {
        JSONObject defProfile = defectiveProfileFor(profile);
        JSONObject template = defProfile == null ? null : defProfile.optJSONObject("template");
        int templateId = template == null ? 0 : template.optInt("id", 0);
        if (templateId <= 0 || defectiveTemplateCache.containsKey(templateId)) return;
        String token = savedToken();
        if (token.isEmpty() || !backendConfigured()) return;
        new Thread(() -> {
            try {
                Api api = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints());
                JSONObject detail = loadTemplateDetail(api, templateId);
                if (detail != null) defectiveTemplateCache.put(templateId, detail);
            } catch (Exception ignored) {
            }
        }, "defective-template-prefetch").start();
    }

    private void clearAStepEntrySn() {
        aStepEntrySn = "";
        if (aStepEntrySnEdit != null) aStepEntrySnEdit.setText("");
        refreshAStepEntryUi();
    }

    private JSONObject buildAStepEntryRejectPayload(JSONObject template, String sn, String imageUrls, boolean functionalDefect) throws JSONException {
        JSONObject data = new JSONObject();
        JSONArray fields = templateFields(template);
        boolean hasSn = false;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null) continue;
            String name = field.optString("field", "");
            if (name.isEmpty()) continue;
            if (isPrimarySnField(field)) {
                data.put(name, sn);
                hasSn = true;
                continue;
            }
            if (isUploadField(field)) {
                // 报废：照片只进「不良品照片」字段；机器电量/破损部件等其它上传框选填不传（真实报废单里
                // 只有不良品照片带图）。isOptionalDefectivePhotoField 认标题含 不良品/defective/reject 的框。
                if (!imageUrls.isEmpty() && isOptionalDefectivePhotoField(field)) data.put(name, imageUrls);
                continue;
            }
            Object value = aStepEntryFieldValue(field, functionalDefect);
            if (value != null) data.put(name, value);
        }
        if (!hasSn) data.put("sn", sn);

        JSONObject payload = new JSONObject();
        payload.put("template_id", optIntAny(template, "id", "template_id", "templateId"));
        payload.put("warehouse_id", optIntAny(template, "warehouse_id", "warehouseId"));
        payload.put("sku", template.optString("sku", profile.getJSONObject("template").optString("sku", "")));
        payload.put("data", data);
        payload.put("video_data_id", "");
        return payload;
    }

    // The 不良品 (-defective) profile for the model selected in 报废录入, or null. If the user already
    // picked a -defective profile it IS the one; otherwise look up its <id>-defective sibling.
    private JSONObject defectiveProfileFor(JSONObject prof) {
        if (prof == null) return null;
        String id = prof.optString("id", "");
        if (id.endsWith("-defective")) return prof;
        return findProfileById(id + "-defective");
    }

    // 自动分类：一个 profile 是「不良品表单」= id 以 -defective 结尾，或整个 gradeMap 都是不良品/报废档。
    private boolean isDefectiveProfile(JSONObject p) {
        if (p == null) return false;
        if (p.optString("id", "").endsWith("-defective")) return true;
        JSONObject gm = p.optJSONObject("gradeMap");
        if (gm == null) return false;
        JSONArray names = gm.names();
        if (names == null || names.length() == 0) return false;
        for (int i = 0; i < names.length(); i++) {
            JSONObject g = gm.optJSONObject(names.optString(i));
            String label = g == null ? "" : (g.optString("label", "") + " " + (g.optJSONObject("value") != null ? g.optJSONObject("value").optString("name", "") : ""));
            if (!label.contains("不良") && !label.contains("报废") && !label.toLowerCase(java.util.Locale.US).contains("defective") && !label.toLowerCase(java.util.Locale.US).contains("reject")) return false;
        }
        return true;
    }

    private JSONArray filterGoodProfiles(JSONArray all) {
        JSONArray good = new JSONArray();
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p != null && !isDefectiveProfile(p)) good.put(p);
        }
        return good;
    }

    private JSONObject findProfileById(String id) {
        JSONArray src = allProfiles != null ? allProfiles : profiles;
        if (src == null || id == null || id.isEmpty()) return null;
        for (int i = 0; i < src.length(); i++) {
            JSONObject p = src.optJSONObject(i);
            if (p != null && id.equals(p.optString("id", ""))) return p;
        }
        return null;
    }

    // Build the 报废 payload straight from a 不良品 profile's PANEL-EDITABLE fixed values: 不良品 结果
    // (gradeMap) + 外观检测状况/不通过原因/是否进行下一步 (choiceFields) + 操作内容 (operationFields) +
    // 不良品照片 (photoSlots). Editing the profile in the panel changes exactly what this submits.
    private JSONObject buildDefectiveProfilePayload(JSONObject prof, String sn, String imageUrls, boolean functionalDefect) throws JSONException {
        JSONObject data = new JSONObject();
        JSONObject snFields = prof.optJSONObject("snFields");
        String snField = snFields != null ? snFields.optString("primary", "sn") : "sn";
        data.put(snField.isEmpty() ? "sn" : snField, sn);
        // 不良品 结果 (gradeMap: prefer A, else the first grade)
        JSONObject gradeMap = prof.optJSONObject("gradeMap");
        if (gradeMap != null) {
            String gk = gradeMap.has("A") ? "A" : (gradeMap.names() != null && gradeMap.names().length() > 0 ? gradeMap.names().optString(0) : null);
            JSONObject g = gk != null ? gradeMap.optJSONObject(gk) : null;
            if (g != null && g.has("field") && g.has("value")) data.put(g.getString("field"), g.get("value"));
        }
        // choiceFields — fixed 外观检测/不通过原因/是否下一步 values (visible ones)
        JSONArray choices = prof.optJSONArray("choiceFields");
        for (int i = 0; choices != null && i < choices.length(); i++) {
            JSONObject f = choices.optJSONObject(i);
            if (f == null || !f.optBoolean("visible", true)) continue;
            String fid = f.optString("field", "");
            if (fid.isEmpty() || !f.has("value")) continue;
            Object value = f.get("value");
            // 勾了「加功能不良」：多选字段（不通过原因类）的选项里若有功能不良项，补勾进提交值。
            if (functionalDefect) value = withFunctionalDefectChoice(f, value);
            data.put(fid, value);
        }
        // operationFields — 操作内容
        JSONArray ops = prof.optJSONArray("operationFields");
        for (int i = 0; ops != null && i < ops.length(); i++) {
            JSONObject f = ops.optJSONObject(i);
            if (f == null) continue;
            String fid = f.optString("field", "");
            if (fid.isEmpty() || !f.has("value")) continue;
            Object value = f.get("value");
            // 勾了「加功能不良」：面板固定值里有检测类条目就只报检测（未做任何操作不再上报）。
            // 固定值里没有检测项时只能保持面板原值——已发布的 profile 不带完整选项表，无从取检测的真实值。
            if (functionalDefect) {
                JSONArray detectOnly = detectOnlyOperationValue(value);
                if (detectOnly != null) value = detectOnly;
            }
            data.put(fid, value);
        }
        // photoSlots — all 报废 photos into the defective photo slot(s) (usually just 不良品照片); the
        // optional 机器电量 lives in optionalSlots, not here, so it stays empty (选填不传).
        JSONArray slots = prof.optJSONArray("photoSlots");
        for (int i = 0; slots != null && i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null) continue;
            String fid = s.optString("field", "");
            if (!fid.isEmpty() && !imageUrls.isEmpty()) data.put(fid, imageUrls);
        }
        JSONObject template = prof.getJSONObject("template");
        JSONObject payload = new JSONObject();
        payload.put("template_id", template.getInt("id"));
        payload.put("warehouse_id", template.optInt("warehouseId", template.optInt("warehouse_id", 0)));
        payload.put("sku", template.optString("sku", ""));
        payload.put("data", data);
        payload.put("video_data_id", "");
        return payload;
    }

    // 「加功能不良」勾选时：多选 choiceField 的 options 里有功能不良项就补进提交数组（已有则不重复）。
    // 单选不动（那是面板定死的判定，替换会破坏面板契约）；返回新数组，绝不改动 profile 缓存里的原值。
    private Object withFunctionalDefectChoice(JSONObject field, Object value) throws JSONException {
        if (!(value instanceof JSONArray)) return value;
        JSONArray options = field.optJSONArray("options");
        if (options == null || options.length() == 0) return value;
        JSONObject pick = findAStepEntryOption(options, "functional");
        if (pick == null) return value;
        Object pickValue = pick.has("value") ? pick.get("value") : pick.optString("label", "");
        JSONArray current = (JSONArray) value;
        JSONArray out = new JSONArray();
        for (int i = 0; i < current.length(); i++) {
            if (String.valueOf(current.opt(i)).equals(String.valueOf(pickValue))) return value;
            out.put(current.opt(i));
        }
        out.put(pickValue);
        return out;
    }

    // 「加功能不良」勾选时：从操作内容固定值（条目即选项文案）里筛出检测类条目；没有则返回 null 保持原值。
    private JSONArray detectOnlyOperationValue(Object value) {
        if (!(value instanceof JSONArray)) return null;
        JSONArray all = (JSONArray) value;
        JSONArray detect = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            String s = String.valueOf(all.opt(i)).toLowerCase(java.util.Locale.US);
            if (s.contains("检测") || s.contains("检查") || s.contains("detect") || s.contains("inspect") || s.contains("test")) detect.put(all.opt(i));
        }
        return detect.length() > 0 ? detect : null;
    }

    // 勾了「加功能不良」的 profile 路径补齐：按 -defective 模板的真实字段/选项，把功能测试类字段填成
    // 不良/不通过（后端把 功能测试=不通过 归为功能不良），并把带「未做任何操作」选项的操作内容字段
    // 换成「检测」选项——提交值必须取自模板选项本身（发布的 profile 只有固定值、没有完整选项表，
    // 选项的提交值未必等于其文案）。模板拉取失败不阻塞提交——保持 profile 固定值原样，只记日志。
    private void overlayFunctionalDefectFromTemplate(Api api, JSONObject defProfile, JSONObject payload) {
        try {
            int templateId = defProfile.getJSONObject("template").getInt("id");
            JSONObject detail = defectiveTemplateCache.get(templateId);
            if (detail == null) {
                detail = loadTemplateDetail(api, templateId);
                if (detail != null) defectiveTemplateCache.put(templateId, detail);
            }
            if (detail == null) {
                appendLog(t("a_step_entry_functional_fallback"));
                return;
            }
            JSONObject data = payload.getJSONObject("data");
            JSONArray fields = templateFields(detail);
            for (int i = 0; i < fields.length(); i++) {
                JSONObject field = fields.optJSONObject(i);
                if (field == null) continue;
                String name = field.optString("field", "");
                if (name.isEmpty()) continue;
                JSONArray options = field.optJSONArray("option_list");
                if (options == null || options.length() == 0) options = field.optJSONArray("optionList");
                if (options == null || options.length() == 0) continue;
                if (field.optString("title", "").contains("功能测试")) {
                    // 功能测试状况 → 不通过；若还有功能测试不通过结果类字段则挑不良项。
                    JSONObject pick = findAStepEntryOption(options, "defective");
                    if (pick == null) pick = findAStepEntryOption(options, "fail");
                    if (pick != null) data.put(name, wrapForFieldType(field, optionValueForSubmit(field, pick)));
                    continue;
                }
                if (findAStepEntryOption(options, "no_op") != null) {
                    JSONObject detect = findAStepEntryOption(options, "detect");
                    if (detect != null) data.put(name, wrapForFieldType(field, optionValueForSubmit(field, detect)));
                }
            }
        } catch (Exception exc) {
            appendLog(t("a_step_entry_functional_fallback") + " " + conciseError(exc));
        }
    }

    private Object wrapForFieldType(JSONObject field, Object value) {
        if (!"checkbox".equals(field.optString("type", ""))) return value;
        JSONArray array = new JSONArray();
        array.put(value);
        return array;
    }

    // 面板可配置模块：profile 里可选的 functionalDefect.data —— 勾选「加功能不良」提交时，把这里
    // 列出的字段值原样覆盖进 payload.data（例如不良原因数组补上功能不良、操作内容换成 ["检测"]）。
    // 配了它就以面板为准（内置启发式先算、再被覆盖），没配就纯走启发式。这样勾选提交什么只需在
    // 面板 JSON 编辑器里改 profile 并发布，App 无需重新打包。优先读 -defective profile 上的配置，
    // 它没有再读机型自己 profile 上的（两条提交路径都生效）。
    private void applyFunctionalDefectOverrides(JSONObject defProfile, JSONObject payload) throws JSONException {
        JSONObject cfg = defProfile != null ? defProfile.optJSONObject("functionalDefect") : null;
        if (cfg == null && profile != null) cfg = profile.optJSONObject("functionalDefect");
        JSONObject overrides = cfg == null ? null : cfg.optJSONObject("data");
        JSONObject data = payload.optJSONObject("data");
        if (overrides == null || data == null) return;
        JSONArray names = overrides.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.optString(i);
            if (!key.isEmpty()) data.put(key, overrides.get(key));
        }
    }

    // Decide each step-one field's value by the OPTIONS the field actually offers (title-agnostic).
    // Earlier this matched on field titles, which silently missed the 不良品 verdict field when its
    // title didn't contain the expected words; matching on the available options is unambiguous.
    private Object aStepEntryFieldValue(JSONObject field, boolean functionalDefect) throws JSONException {
        // 报废走「外观不良」路径：功能测试不做 → 功能测试状况类字段不提交（真实报废单里没有它），
        // 否则它也是「通过/不通过」单选，会被下面的 fail 规则误填成「不通过」。用中文 title 判断——
        // 不能用 en_title，因为『外观测试不通过结果』字段的英文标题里也含 "function test"，会误伤退货结果字段。
        // 勾了「加功能不良」也一样跳过：那只改不良原因勾选和操作内容，不动功能测试字段。
        if (field.optString("title", "").contains("功能测试")) return null;
        JSONArray options = field.optJSONArray("option_list");
        if (options == null || options.length() == 0) options = field.optJSONArray("optionList");
        if (options == null || options.length() == 0) return null;

        // 1) Defect REASON: a field offering 划痕/脏污 → multi-select all such defects.
        //    勾了「加功能不良」时把功能不良也一并勾上（该字段没有这一项就照旧）。
        if (findAStepEntryOption(options, "scratch") != null || findAStepEntryOption(options, "dirt") != null) {
            JSONArray picked = new JSONArray();
            addAStepEntryOptionMatches(picked, field, options, "scratch");
            addAStepEntryOptionMatches(picked, field, options, "dirt");
            if (functionalDefect) addAStepEntryOptionMatches(picked, field, options, "functional");
            if (picked.length() > 0) return picked;
        }
        // 2) Quality verdict: any field offering a 不良品 option (this is the one that was being missed).
        JSONObject option = findAStepEntryOption(options, "defective");
        // 3) Appearance/inspection result: 外观不通过 / 不通过.
        if (option == null) option = findAStepEntryOption(options, "fail");
        // 4) Repair result: 无法维修.
        if (option == null) option = findAStepEntryOption(options, "unrepairable");
        // 5) Operation: 未做任何操作；勾了「加功能不良」则换成检测。
        if (option == null && functionalDefect) option = findAStepEntryOption(options, "detect");
        if (option == null) option = findAStepEntryOption(options, "no_op");
        if (option == null) {
            // Not a verdict-bearing field. Fall back to the auto step-one default so any incidental
            // required field still gets a valid value (keeps the submit from failing on empty-required).
            return autoStepOneFieldValue(field);
        }
        Object value = optionValueForSubmit(field, option);
        if ("checkbox".equals(field.optString("type", ""))) {
            JSONArray array = new JSONArray();
            array.put(value);
            return array;
        }
        return value;
    }

    private void addAStepEntryOptionMatches(JSONArray out, JSONObject field, JSONArray options, String kind) throws JSONException {
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && aStepEntryOptionMatches(option, kind)) {
                out.put(optionValueForSubmit(field, option));
            }
        }
    }

    private JSONObject findAStepEntryOption(JSONArray options, String kind) {
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && aStepEntryOptionMatches(option, kind)) return option;
        }
        return null;
    }

    private boolean aStepEntryOptionMatches(JSONObject option, String kind) {
        String text = (option.optString("name", "") + " "
            + option.optString("en_name", "") + " "
            + option.optString("label", "") + " "
            + option.optString("value", "")).toLowerCase(java.util.Locale.US);
        switch (kind) {
            case "fail": // 外观不通过 / 不通过 / 不合格
                return text.contains("不通过") || text.contains("不合格") || text.contains("外观不")
                    || text.contains("fail") || text.contains("not pass") || text.contains("unqualified");
            case "scratch": // 划痕
                return text.contains("划痕") || text.contains("scratch");
            case "dirt": // 脏污
                return text.contains("脏污") || text.contains("污渍") || text.contains("dirt") || text.contains("stain") || text.contains("dirty");
            case "unrepairable": // 无法维修 / 不可维修 / 不能维修
                return text.contains("无法维修") || text.contains("不可维修") || text.contains("不能维修")
                    || text.contains("cannot repair") || text.contains("not repairable") || text.contains("unrepairable") || text.contains("irreparable");
            case "defective": // 不良品 / 报废
                return text.contains("不良") || text.contains("报废") || text.contains("defective") || text.contains("reject") || text.contains("scrap");
            case "no_op": // 未做任何操作 / 无操作
                return text.contains("未做") || text.contains("无操作") || text.contains("未操作") || text.contains("不操作")
                    || text.contains("no operation") || text.contains("no action") || text.contains("none");
            case "functional": // 功能不良 / 功能故障 —— 只认「功能+坏」组合，避免误勾「功能正常/功能测试通过」类选项
                return text.contains("功能不良") || text.contains("功能故障") || text.contains("功能异常")
                    || text.contains("malfunction")
                    || (text.contains("function") && (text.contains("defect") || text.contains("fail") || text.contains("bad")));
            case "detect": // 检测 / 检查 —— 与良品流程 optionMatches 的 detect 同款
                return text.contains("检测") || text.contains("检查") || text.contains("detect") || text.contains("inspect") || text.contains("test");
            default:
                return false;
        }
    }

    private void showFormSettingsDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(6), 0, dp(6), 0);
        final AlertDialog[] dialogRef = new AlertDialog[1];

        content.addView(compactLabel(t("language")));
        RadioGroup languageGroup = settingsRadioGroup();
        addSettingsRadio(languageGroup, 101, languageName("zh"));
        addSettingsRadio(languageGroup, 102, languageName("en"));
        addSettingsRadio(languageGroup, 103, languageName("es"));
        languageGroup.check(languageRadioId(lang));
        content.addView(languageGroup);

        content.addView(compactLabel(t("photo_order")));
        RadioGroup photoGroup = settingsRadioGroup();
        addSettingsRadio(photoGroup, 201, t("fronts_then_backs"));
        addSettingsRadio(photoGroup, 202, t("front_back_per_unit"));
        photoGroup.check("front_back_per_unit".equals(photoOrder) ? 202 : 201);
        content.addView(photoGroup);

        content.addView(compactLabel(t("payload_display")));
        content.addView(button(t("preview_payload"), v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            previewPayload();
        }));

        content.addView(compactLabel(t("queue_backup")));
        TextView queueBackupInfo = text(queueBackupInfoText(), 13, false);
        queueBackupInfo.setTextColor(0xFF475569);
        queueBackupInfo.setPadding(0, 0, 0, dp(4));
        content.addView(queueBackupInfo);
        LinearLayout queueBackupRow = row();
        queueBackupRow.addView(button(t("queue_backup_save"), v -> {
            saveQueueSnapshot();
            queueBackupInfo.setText(queueBackupInfoText());
        }));
        queueBackupRow.addView(button(t("queue_backup_restore"), v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            restoreQueueSnapshot();
        }));
        content.addView(queueBackupRow);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(t("form_settings"))
            .setView(content)
            .setPositiveButton(t("close"), null)
            .create();
        dialogRef[0] = dialog;

        languageGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String next = languageFromRadioId(checkedId);
            if (next.isEmpty() || next.equals(lang)) return;
            dialog.dismiss();
            switchLanguage(next);
        });
        photoGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String next = checkedId == 202 ? "front_back_per_unit" : "fronts_then_backs";
            if (next.equals(photoOrder)) return;
            photoOrder = next;
            saveDraft();
            refreshFormUi();
        });
        dialog.show();
    }

    private LinearLayout aStepPhotoBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(6), 0, dp(8));
        box.setLayoutParams(params);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFBEB);
        bg.setStroke(dp(1), 0xFFFBBF24);
        bg.setCornerRadius(dp(8));
        box.setBackground(bg);

        box.addView(compactLabel(t("a_step_photo")));
        aStepPhotoText = text("", 13, false);
        aStepPhotoText.setTextColor(0xFF92400E);
        aStepPhotoText.setPadding(0, 0, 0, dp(4));
        box.addView(aStepPhotoText);

        LinearLayout actions = row();
        actions.addView(button(t("a_step_take_photo"), v -> captureAStepPhoto()));
        actions.addView(button(t("choose_gallery_photo"), v -> pickAStepPhotoFromGallery()));
        aStepPhotoViewButton = button(t("view_photo"), v -> {});
        actions.addView(aStepPhotoViewButton);
        box.addView(actions);
        return box;
    }

    private RadioGroup settingsRadioGroup() {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, 0, 0, dp(8));
        return group;
    }

    private void addSettingsRadio(RadioGroup group, int id, String title) {
        RadioButton radio = new RadioButton(this);
        radio.setId(id);
        radio.setText(title);
        radio.setTextSize(18);
        radio.setMinHeight(dp(44));
        radio.setPadding(dp(8), 0, dp(8), 0);
        group.addView(radio, new RadioGroup.LayoutParams(
            RadioGroup.LayoutParams.MATCH_PARENT,
            RadioGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private int languageRadioId(String value) {
        if ("en".equals(value)) return 102;
        if ("es".equals(value)) return 103;
        return 101;
    }

    private String languageFromRadioId(int id) {
        if (id == 102) return "en";
        if (id == 103) return "es";
        if (id == 101) return "zh";
        return "";
    }

    private void handleChineseLanguageTap() {
        long now = System.currentTimeMillis();
        if (now - chineseTapWindowStarted > 2500L) {
            chineseTapWindowStarted = now;
            chineseTapCount = 0;
        }
        chineseTapCount++;
        if (chineseTapCount < 5) return;

        chineseTapCount = 0;
        chineseTapWindowStarted = 0L;
        String channel = UpdateManager.toggleChannel(this);
        refreshUpdateChannelText();
        toast("beta".equals(channel) ? t("update_channel_beta_toast") : t("update_channel_stable_toast"));
        if (updateManager != null) {
            updateManager.checkNow();
        }
    }

    private void refreshUpdateChannelText() {
        if (updateChannelText != null) updateChannelText.setText(updateChannelStatusText());
    }

    private String updateChannelStatusText() {
        return t("update_channel") + ("beta".equals(UpdateManager.currentChannel(this)) ? t("update_channel_beta") : t("update_channel_stable"));
    }

    private void refreshCaptcha() {
        if (!backendConfigured()) {
            // Unconfigured: there is no backend to fetch a captcha from. Skip silently — the settings
            // screen shows the "set the panel address" banner, so no need for an extra popup here.
            return;
        }
        appendLog(t("captcha_loading"));
        new Thread(() -> {
            try {
                Api.Captcha captcha = new Api(apiBase(), "", webFingerprint(), webOrigin(), webReferer(), endpoints()).getCaptcha();
                captchaClient = captcha.client;
                Bitmap bitmap = decodeCaptcha(captcha.captcha);
                runOnUiThread(() -> {
                    if (captchaView != null && bitmap != null) captchaView.setImageBitmap(bitmap);
                    if (captchaEdit != null) captchaEdit.setText("");
                    appendLog(t("captcha_ready"));
                });
            } catch (Exception exc) {
                runOnUiThread(() -> alert(t("captcha_failed"), exc.getMessage()));
            }
        }).start();
    }

    private void login() {
        if (!backendConfigured()) {
            // No panel/backend configured → block login outright. Never attempt a backend call with an
            // empty base; point the user at Settings instead.
            alert(t("panel_required_title"), t("panel_required_detail"));
            return;
        }
        if (captchaEdit == null) {
            // Captcha UI is hidden because we thought we were still signed in — rebuild the login panel
            // so it reappears (defensive: token-clearing paths already rebuild before any tap is possible).
            showSettingsPage();
            return;
        }
        String account = accountEdit.getText().toString().trim();
        String password = passwordEdit.getText().toString();
        String captcha = captchaEdit.getText().toString().trim();
        if (account.isEmpty() || password.isEmpty() || captcha.isEmpty() || captchaClient.isEmpty()) {
            toast(t("login_missing"));
            return;
        }
        appendLog(t("login_running"));
        new Thread(() -> {
            try {
                Api.LoginResult result = new Api(apiBase(), "", webFingerprint(), webOrigin(), webReferer(), endpoints()).login(account, password, captcha, captchaClient);
                SecureTokenStore.put(prefs, result.token);
                SecureTokenStore.putPassword(prefs, password);   // remember it for the next sign-in
                prefs.edit()
                    .putString("account", account)
                    .putString("userName", result.userName)
                    .putString("recognizeTextUrl", result.recognizeTextUrl)
                    .apply();
                SessionBridge.propagateLogin(getApplicationContext(), result.token, webFingerprint());
                runOnUiThread(() -> showFormPage());
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    alert(t("login_failed"), exc.getMessage());
                    refreshCaptcha();
                });
            }
        }).start();
    }

    private void logoutToSettings() {
        logoutToSettings(true);
    }

    // propagate=true: a first-hand local logout (user tapped logout / IP denied) — tell peers too.
    // propagate=false: reacting to a peer that already logged us out — clear local only (R2, no echo).
    private void logoutToSettings(boolean propagate) {
        boolean had = !savedToken().isEmpty();
        SecureTokenStore.clear(prefs);
        prefs.edit()
            .remove("userName")
            .remove("recognizeTextUrl")
            .apply();
        units.clear();
        cachedMissingMaterialCodes.clear();
        notifiedMissingMaterialCodes.clear();
        scanPrecheckMissingCounts.clear();
        missingMaterialNoticeShown = false;
        unitList = null;
        if (propagate && had) {
            SessionBridge.propagateLogout(getApplicationContext(), null);
        }
        showSettingsPage();
        refreshCaptcha();
    }

    /**
     * React to a session that ended outside this screen: our own checkAuth=INVALID (firstHand=true,
     * propagate to peers) or a peer's logout broadcast (firstHand=false, R2 — don't re-propagate).
     * Clears the pending flag, logs out locally, and returns to the login page with a notice.
     */
    private void handleRemoteLogout(boolean firstHand) {
        prefs.edit().remove(SessionEventReceiver.PENDING_LOGOUT_KEY).apply();
        boolean had = !savedToken().isEmpty();
        logoutToSettings(firstHand);
        if (had) {
            alert(t("session_expired_title"), t("session_expired_detail"));
        }
    }

    /**
     * Probe that our token is still accepted; if kicked/expired, log out + propagate + prompt re-login.
     * Debounced (30s) so resume + poll + pre-submit don't hammer the endpoint. UNKNOWN does nothing.
     * onValid runs (UI thread) when the token is still good — or immediately if we probed recently.
     */
    private void checkAuthThen(Runnable onValid) {
        final String token = savedToken();
        if (token.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAuthCheckMs < 30000L) {
            if (onValid != null) onValid.run();
            return;
        }
        lastAuthCheckMs = now;
        new Thread(() -> {
            if (!backendConfigured()) return; // background auth probe: unconfigured → nothing to check
            Api.AuthState state = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints()).checkAuth();
            runOnUiThread(() -> {
                if (state == Api.AuthState.INVALID) {
                    handleRemoteLogout(true);
                } else if (onValid != null) {
                    onValid.run();
                }
            });
        }).start();
    }

    // Live receiver while in the foreground: reflect a peer login/logout immediately. Never
    // re-propagates (R2) — it only re-reads our own token, which a peer's logout has already cleared.
    private void registerSessionReceiver() {
        if (sessionReceiver != null) return;
        sessionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String source = intent.getStringExtra("source");
                if (source != null && source.equals(getPackageName())) return;
                if (savedToken().isEmpty()) {
                    handleRemoteLogout(false);
                } else {
                    refreshLoginStatus();
                }
            }
        };
        IntentFilter filter = new IntentFilter(SessionBridge.ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(sessionReceiver, filter);
        }
    }

    private void unregisterSessionReceiver() {
        if (sessionReceiver != null) {
            try {
                unregisterReceiver(sessionReceiver);
            } catch (Exception ignored) {
            }
            sessionReceiver = null;
        }
    }

    private void startAuthPolling() {
        stopAuthPolling();
        if (savedToken().isEmpty()) return;
        authPoller = new Runnable() {
            @Override
            public void run() {
                checkAuthThen(null);
                authHandler.postDelayed(this, 45000L);
            }
        };
        authHandler.postDelayed(authPoller, 45000L);
    }

    private void stopAuthPolling() {
        if (authPoller != null) {
            authHandler.removeCallbacks(authPoller);
            authPoller = null;
        }
    }

    private void refreshLoginStatus() {
        if (loginStatus == null) return;
        String token = savedToken();
        String name = prefs.getString("userName", "");
        if (name.isEmpty()) name = prefs.getString("account", "");
        loginStatus.setVisibility(token.isEmpty() ? View.GONE : View.VISIBLE);
        loginStatus.setText(token.isEmpty() ? "" : name);
    }

    private void verifyAccessThenShowForm() {
        new Thread(() -> {
            // Already-logged-in user re-entering the form: verify the token is still live (it may have
            // been kicked while idle). INVALID → logout + re-login prompt; UNKNOWN/VALID → proceed.
            Api.AuthState auth = savedToken().isEmpty()
                ? Api.AuthState.UNKNOWN
                : new Api(apiBase(), savedToken(), webFingerprint(), webOrigin(), webReferer(), endpoints()).checkAuth();
            runOnUiThread(() -> {
                if (auth == Api.AuthState.INVALID) {
                    handleRemoteLogout(true);
                } else {
                    showFormPage();
                }
            });
        }).start();
    }

    private void addTypedSn() {
        String sn = normalize(snEdit.getText().toString());
        if (addSnValue(sn, selectedGrade())) {
            snEdit.setText("");
            resetGradeSelection();
        }
        refocusSnInput();
    }

    private void startSnScan(boolean baseSn) {
        if (baseSn && firstMissingBaseSn() == null) {
            toast(t("no_base_needed"));
            refocusBaseInput();
            return;
        }
        if (!baseSn && hasMultipleGradeChoices() && selectedGrade().isEmpty()) {
            toast(t("choose_grade"));
            refocusSnInput();
            return;
        }
        if (!ensureCameraPermission()) return;

        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.setClass(this, ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", baseSn ? t("scan_base_sn") : t("scan_robot_sn"));
        intent.putExtra("lang", lang);
        String whiteLabelMode = scannerWhiteLabelMode(baseSn);
        intent.putExtra("WHITE_LABEL_MODE", whiteLabelMode);
        intent.putExtra("REJECT_NUMERIC_ONLY", true);
        intent.putExtra("OCR_ONLY", false);
        if (!baseSn) intent.putExtra(EXTRA_EXPECTED_SN_LENGTH, expectedRobotSnLength());
        intent.putExtra("SCAN_CAMERA_ID", 0);
        intent.putExtra("SCAN_ORIENTATION_LOCKED", true);
        intent.putExtra("BEEP_ENABLED", false);
        intent.putExtra("BARCODE_IMAGE_ENABLED", false);
        intent.putExtra("SHOW_MISSING_CAMERA_PERMISSION_DIALOG", false);
        try {
            Diagnostics.append(this, "Starting MLKit scanner target=" + (baseSn ? "base" : "robot") + " whiteLabelMode=" + whiteLabelMode + " profile=" + currentProfileId());
            startActivityForResult(intent, baseSn ? REQ_SCAN_BASE : REQ_SCAN_SN);
        } catch (ActivityNotFoundException exc) {
            alert(t("scanner_missing_title"), t("scanner_missing_detail"));
        } catch (Exception exc) {
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private String scannerWhiteLabelMode(boolean baseSn) {
        if (baseSn || profile == null) return "";
        String fingerprint = profileScanFingerprint();
        boolean black = fingerprint.contains("black") || fingerprint.contains("\u9ed1");
        boolean white = fingerprint.contains("white") || fingerprint.contains("\u767d");
        if (black && !white) return "primary";
        return "fallback";
    }

    private String profileScanFingerprint() {
        if (profile == null) return "";
        StringBuilder text = new StringBuilder();
        text.append(profile.optString("id", "")).append(' ');
        text.append(profile.optString("brand", "")).append(' ');
        text.append(profile.optString("displayName", "")).append(' ');
        text.append(profile.optString("searchText", "")).append(' ');
        text.append(profile.optString("model", "")).append(' ');
        text.append(profile.optString("color", "")).append(' ');
        JSONObject template = profile.optJSONObject("template");
        if (template != null) {
            text.append(template.optString("sku", "")).append(' ');
        }
        return text.toString().toLowerCase(java.util.Locale.US);
    }

    /** Expected primary-SN length for the active profile, from its {@code expectedSnLength} field
     *  (0 = no length check). Purely config-driven; no built-in per-model defaults. */
    private int expectedRobotSnLength() {
        if (profile == null) return 0;
        return profile.optInt("expectedSnLength", 0);
    }

    private boolean validateRobotSnLength(String sn) {
        int expected = expectedRobotSnLength();
        if (expected <= 0 || sn.length() == expected) return true;
        toastLong(robotSnLengthMessage(expected, sn.length()));
        return false;
    }

    private String robotSnLengthMessage(int expected, int actual) {
        if ("en".equals(lang)) return "Robot SN must be " + expected + " characters. Current: " + actual + ".";
        if ("es".equals(lang)) return "El SN del robot debe tener " + expected + " caracteres. Actual: " + actual + ".";
        return "\u673a\u5668 SN \u5e94\u4e3a " + expected + " \u4f4d\uff0c\u5f53\u524d " + actual + " \u4f4d\u3002";
    }

    private String robotSnExpectedOnlyMessage(int expected) {
        if ("en".equals(lang)) return "No " + expected + "-character robot SN was found.";
        if ("es".equals(lang)) return "No se encontro un SN de robot de " + expected + " caracteres.";
        return "\u672a\u8bc6\u522b\u5230 " + expected + " \u4f4d\u673a\u5668 SN\u3002";
    }

    private String currentProfileId() {
        return profile == null ? "" : profile.optString("id", "");
    }

    private void startUnitSnRescan(UnitRecord unit, boolean baseSn) {
        if (unit == null) return;
        if (!ensureCameraPermission()) return;
        pendingRescanUnitSequence = unit.sequence;
        prefs.edit().putInt(PENDING_RESCAN_SEQUENCE_KEY, pendingRescanUnitSequence).apply();
        saveDraft();
        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.setClass(this, ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", baseSn ? t("scan_base_sn") : t("scan_robot_sn"));
        intent.putExtra("lang", lang);
        intent.putExtra("WHITE_LABEL_MODE", scannerWhiteLabelMode(baseSn));
        intent.putExtra("REJECT_NUMERIC_ONLY", true);
        intent.putExtra("OCR_ONLY", false);
        if (!baseSn) intent.putExtra(EXTRA_EXPECTED_SN_LENGTH, expectedRobotSnLength());
        intent.putExtra("SCAN_CAMERA_ID", 0);
        intent.putExtra("SCAN_ORIENTATION_LOCKED", true);
        intent.putExtra("BEEP_ENABLED", false);
        intent.putExtra("BARCODE_IMAGE_ENABLED", false);
        intent.putExtra("SHOW_MISSING_CAMERA_PERMISSION_DIALOG", false);
        try {
            String oldValue = baseSn ? (unit.baseSn == null ? "" : unit.baseSn) : (unit.sn == null ? "" : unit.sn);
            Diagnostics.append(this, "Starting MLKit scanner for unit rescan target=" + (baseSn ? "base" : "robot") + " sequence=" + unit.sequence + " oldLength=" + oldValue.length());
            startActivityForResult(intent, baseSn ? REQ_RESCAN_UNIT_BASE_SN : REQ_RESCAN_UNIT_SN);
        } catch (Exception exc) {
            clearPendingRescan();
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private void startSnOcr(boolean baseSn) {
        if (baseSn && firstMissingBaseSn() == null) {
            toast(t("no_base_needed"));
            refocusBaseInput();
            return;
        }
        if (!baseSn && hasMultipleGradeChoices() && selectedGrade().isEmpty()) {
            toast(t("choose_grade"));
            refocusSnInput();
            return;
        }
        if (!ensureCameraPermission()) return;
        Intent intent = new Intent(this, ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", baseSn ? t("scan_base_sn") : t("scan_robot_sn"));
        intent.putExtra("lang", lang);
        intent.putExtra("WHITE_LABEL_MODE", "primary");
        intent.putExtra("REJECT_NUMERIC_ONLY", true);
        intent.putExtra("OCR_ONLY", true);
        if (!baseSn) intent.putExtra(EXTRA_EXPECTED_SN_LENGTH, expectedRobotSnLength());
        try {
            Diagnostics.append(this, "Starting local MLKit SN text scanner target=" + (baseSn ? "base" : "robot"));
            startActivityForResult(intent, baseSn ? REQ_SCAN_BASE : REQ_SCAN_SN);
        } catch (Exception exc) {
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private void ensureOcrUrlThenStartCamera(boolean baseSn) {
        String recognizeTextUrl = prefs.getString("recognizeTextUrl", "").trim();
        if (!recognizeTextUrl.isEmpty()) {
            startCameraForOcr(baseSn);
            return;
        }
        String token = savedToken();
        if (token.isEmpty()) {
            alert(t("login_required"), t("login_required_detail"));
            return;
        }
        appendLog(t("ocr_url_refreshing"));
        new Thread(() -> {
            if (!backendConfigured()) { notifyBackendUnconfigured(); return; }
            try {
                Api.UserProfile user = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints()).fetchUserInfo();
                if (!user.userName.isEmpty() || !user.recognizeTextUrl.isEmpty()) {
                    SharedPreferences.Editor editor = prefs.edit();
                    if (!user.userName.isEmpty()) editor.putString("userName", user.userName);
                    if (!user.recognizeTextUrl.isEmpty()) editor.putString("recognizeTextUrl", user.recognizeTextUrl);
                    editor.apply();
                }
                runOnUiThread(() -> {
                    if (user.recognizeTextUrl.isEmpty()) {
                        alert(t("ocr_unavailable_title"), t("ocr_unavailable_detail"));
                        if (baseSn) refocusBaseInput(); else refocusSnInput();
                    } else {
                        startCameraForOcr(baseSn);
                    }
                });
            } catch (Exception exc) {
                runOnUiThread(() -> alert(t("ocr_failed"), exc.getMessage()));
            }
        }).start();
    }

    private void startCameraForOcr(boolean baseSn) {
        try {
            File outputFile = createOcrPhotoOutputFile(baseSn);
            pendingOcrPhotoPath = outputFile.getAbsolutePath();
            pendingOcrPhotoUri = SimplePhotoProvider.uriForFile(this, outputFile);
            savePendingOcrOutput();
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingOcrPhotoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<ResolveInfo> cameraApps = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (intent.resolveActivity(getPackageManager()) != null && !cameraApps.isEmpty()) {
                for (ResolveInfo cameraApp : cameraApps) {
                    grantUriPermission(
                        cameraApp.activityInfo.packageName,
                        pendingOcrPhotoUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                }
                Diagnostics.append(this, "Starting system camera for OCR target=" + (baseSn ? "base" : "robot"));
                startActivityForResult(intent, baseSn ? REQ_OCR_BASE : REQ_OCR_SN);
                return;
            }
            startInternalOcrCamera(outputFile, baseSn);
        } catch (Exception exc) {
            clearPendingOcrOutput();
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private void startInternalOcrCamera(File outputFile, boolean baseSn) {
        Intent intent = new Intent(this, CaptureActivity.class);
        intent.putExtra("fileName", outputFile.getName());
        intent.putExtra("label", baseSn ? t("ocr_base") : t("ocr_sn"));
        intent.putExtra("lang", lang);
        Diagnostics.append(this, "Starting internal camera fallback for OCR target=" + (baseSn ? "base" : "robot"));
        startActivityForResult(intent, baseSn ? REQ_OCR_BASE : REQ_OCR_SN);
    }

    private void handleScanResult(int requestCode, int resultCode, Intent data) {
        boolean baseSn = requestCode == REQ_SCAN_BASE;
        if (resultCode != RESULT_OK || data == null) {
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        String ocrPhotoPath = data.getStringExtra("OCR_PHOTO_PATH");
        if (ocrPhotoPath != null && !ocrPhotoPath.trim().isEmpty()) {
            recognizeSnFromPhoto(baseSn, new File(ocrPhotoPath), data.getBooleanExtra("OCR_AUTO_CAPTURE", false));
            return;
        }
        String value = normalize(data.getStringExtra("SCAN_RESULT"));
        String format = data.getStringExtra("SCAN_RESULT_FORMAT");
        Diagnostics.append(this, "Scan result target=" + (baseSn ? "base" : "robot") + " format=" + emptyDash(format) + " length=" + value.length());
        if (value.isEmpty()) {
            toast(baseSn ? t("base_required") : t("sn_required"));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        if (isPureNumeric(value)) {
            Diagnostics.append(this, "Rejected numeric-only scan target=" + (baseSn ? "base" : "robot") + " format=" + emptyDash(format) + " value=" + value);
            alert(t("scan_not_sn_title"), t("scan_not_sn_detail"));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        if (baseSn) {
            showScannedSnPreview(value, t("base_sn"));
            if (baseSnEdit == null) showFormPage(false);
            if (baseSnEdit != null) baseSnEdit.setText(value);
            addBaseSnValue(value);
            refocusBaseInput();
            return;
        }
        showScannedSnPreview(value, t("robot_sn"));
        if (snEdit == null) showFormPage(false);
        if (snEdit != null) snEdit.setText(value);
        UnitRecord added = addSnRecord(value, selectedGrade());
        if (added != null) {
            if (snEdit != null) snEdit.setText("");
            resetGradeSelection();
            checkScannedUnitPreviousSteps(added);
        }
        refocusSnInput();
    }

    private void handleUnitSnRescanResult(int resultCode, Intent data, boolean baseSn) {
        restorePendingRescan(null);
        int sequence = pendingRescanUnitSequence;
        clearPendingRescan();
        if (resultCode != RESULT_OK || data == null) {
            refreshFormUi();
            return;
        }
        String value = normalize(data.getStringExtra("SCAN_RESULT"));
        String format = data.getStringExtra("SCAN_RESULT_FORMAT");
        Diagnostics.append(this, "Unit SN rescan result target=" + (baseSn ? "base" : "robot") + " sequence=" + sequence + " format=" + emptyDash(format) + " length=" + value.length());
        if (value.isEmpty()) {
            toast(baseSn ? t("base_required") : t("sn_required"));
            return;
        }
        if (isPureNumeric(value)) {
            alert(t("scan_not_sn_title"), t("scan_not_sn_detail"));
            return;
        }
        if (!baseSn && !validateRobotSnLength(value)) {
            refreshFormUi();
            return;
        }
        showScannedSnPreview(value, baseSn ? t("base_sn") : t("robot_sn"));
        UnitRecord unit = unitBySequence(sequence);
        if (unit == null && ensureFormStateForRescanResult(sequence)) {
            unit = unitBySequence(sequence);
        }
        if (unit == null) {
            toast(t("photo_target_missing"));
            refreshFormUi();
            return;
        }
        if (!baseSn) { // base station SN duplicates are allowed; only main SN is dedup-checked
            for (UnitRecord item : units) {
                if (item != unit && value.equals(item.sn)) {
                    toast(t("duplicate_sn") + value);
                    showUnitDetails(unit);
                    return;
                }
            }
        }
        String oldValue = baseSn ? (unit.baseSn == null ? "" : unit.baseSn) : (unit.sn == null ? "" : unit.sn);
        if (baseSn) {
            unit.baseSn = value;
        } else {
            unit.sn = value;
        }
        if (!oldValue.equals(value)) {
            unit.precheckStatus = "unchecked";
            unit.status = "pending";
        }
        refreshFormUi();
        saveDraft();
        toast(t("rescan_saved"));
        showUnitDetails(unit);
    }

    private void handleSnEnter() {
        addTypedSn();
        refocusSnInput();
    }

    private boolean addSnValue(String sn, String grade) {
        return addSnRecord(sn, grade) != null;
    }

    private UnitRecord addSnRecord(String sn, String grade) {
        if (sn.isEmpty()) {
            toast(t("sn_required"));
            return null;
        }
        if (hasMultipleGradeChoices() && !hasGrade(grade)) {
            toast(t("choose_grade"));
            return null;
        }
        if (!validateRobotSnLength(sn)) return null;
        for (UnitRecord unit : units) {
            if (unit.sn.equals(sn)) {
                toast(t("duplicate_sn") + sn);
                return null;
            }
        }
        UnitRecord unit = new UnitRecord(nextUnitSequence(), sn, grade);
        // Snapshot the current 彩盒SN/装箱号/… inputs onto this unit (per-unit capture). Empty for legacy.
        for (Map.Entry<String, EditText> e : pluginSnEdits.entrySet()) {
            String val = normalize(e.getValue().getText().toString());
            if (!val.isEmpty()) unit.pluginSns.put(e.getKey(), val);
        }
        units.add(unit);
        refreshFormUi();
        saveDraft();
        return unit;
    }

    private void addBaseSn() {
        addBaseSnValue(normalize(baseSnEdit.getText().toString()));
        refocusBaseInput();
    }

    private void handleBaseEnter() {
        addBaseSn();
        refocusBaseInput();
    }

    private void addBaseSnValue(String baseSn) {
        UnitRecord unit = firstMissingBaseSn();
        if (unit == null) {
            toast(t("no_base_needed"));
            return;
        }
        if (baseSn.isEmpty()) {
            toast(t("base_required"));
            return;
        }
        // base station SN duplicate check intentionally removed
        unit.baseSn = baseSn;
        baseSnEdit.setText("");
        refreshFormUi();
        saveDraft();
    }

    private void refocusSnInput() {
        if (snEdit == null) return;
        snEdit.post(() -> {
            snEdit.requestFocus();
            snEdit.setSelection(snEdit.getText().length());
        });
    }

    private void refocusBaseInput() {
        if (baseSnEdit == null) return;
        baseSnEdit.post(() -> {
            baseSnEdit.requestFocus();
            baseSnEdit.setSelection(baseSnEdit.getText().length());
        });
    }

    private void pickAStepPhotoFromGallery() {
        UnitRecord unit = nextAStepPhotoUnit();
        if (unit == null) {
            toast(units.isEmpty() ? t("add_sn_first") : t("a_step_photos_done"));
            refocusSnInput();
            return;
        }
        pendingAStepPhotoUnitSequence = unit.sequence;
        startAStepPhotoPicker();
    }

    private void startAStepPhotoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_A_STEP_PHOTO);
        } catch (ActivityNotFoundException exc) {
            Intent fallback = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            fallback.setType("image/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivityForResult(fallback, REQ_PICK_A_STEP_PHOTO);
            } catch (ActivityNotFoundException fallbackExc) {
                alert(t("gallery_missing_title"), t("gallery_missing_detail"));
            }
        }
    }

    private void handleAStepPhotoResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingAStepPhotoUnitSequence = -1;
            refocusSnInput();
            return;
        }
        try {
            String path = copyGalleryPhoto(data.getData());
            UnitRecord unit = pendingAStepPhotoUnitSequence > 0 ? unitBySequence(pendingAStepPhotoUnitSequence) : nextAStepPhotoUnit();
            if (unit == null || !needsPreviousStepPhoto(unit)) {
                deleteFileQuietly(path);
                toast(units.isEmpty() ? t("add_sn_first") : t("a_step_photos_done"));
                return;
            }
            String previous = unit.aStepPhotoPath;
            unit.aStepPhotoPath = path;
            if (!previous.isEmpty() && !previous.equals(unit.aStepPhotoPath)) deleteFileQuietly(previous);
            Diagnostics.append(this, "Previous-step photo selected for SN=" + unit.sn + " path=" + unit.aStepPhotoPath);
            refreshAStepPhotoUi();
            saveDraft();
            toast(t("a_step_photo_selected"));
        } catch (Exception exc) {
            alert(t("photo_save_failed"), conciseError(exc));
        } finally {
            pendingAStepPhotoUnitSequence = -1;
        }
        refocusSnInput();
    }

    private void captureAStepPhoto() {
        UnitRecord unit = nextAStepPhotoUnit();
        if (unit == null) {
            toast(units.isEmpty() ? t("add_sn_first") : t("a_step_photos_done"));
            refocusSnInput();
            return;
        }
        if (!ensureCameraPermission()) return;
        pendingAStepPhotoUnitSequence = unit.sequence;
        startCameraForAStepPhoto(unit);
    }

    private void startCameraForAStepPhoto(UnitRecord unit) {
        try {
            File outputFile = createAStepPhotoOutputFile(unit);
            pendingAStepPhotoOutputPath = outputFile.getAbsolutePath();
            pendingAStepPhotoOutputUri = SimplePhotoProvider.uriForFile(this, outputFile);
            savePendingAStepCapture();
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingAStepPhotoOutputUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<ResolveInfo> cameraApps = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (intent.resolveActivity(getPackageManager()) != null && !cameraApps.isEmpty()) {
                for (ResolveInfo cameraApp : cameraApps) {
                    grantUriPermission(
                        cameraApp.activityInfo.packageName,
                        pendingAStepPhotoOutputUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                }
                Diagnostics.append(this, "Starting system camera for A-step photo SN=" + unit.sn);
                startActivityForResult(intent, REQ_CAPTURE_A_STEP_PHOTO);
                return;
            }
            startInternalAStepCamera(outputFile);
        } catch (Exception exc) {
            clearPendingAStepCapture();
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private void startInternalAStepCamera(File outputFile) {
        Intent intent = new Intent(this, CaptureActivity.class);
        intent.putExtra("fileName", outputFile.getName());
        intent.putExtra("label", t("a_step_photo"));
        intent.putExtra("lang", lang);
        Diagnostics.append(this, "Starting internal camera fallback for A-step photo");
        startActivityForResult(intent, REQ_CAPTURE_A_STEP_PHOTO);
    }

    private void handleAStepPhotoCaptureResult(int resultCode, Intent data) {
        restorePendingAStepCapture(null);
        Diagnostics.append(this, "A-step photo capture result resultCode=" + resultCode + " seq=" + pendingAStepPhotoUnitSequence);
        if (resultCode != RESULT_OK) {
            clearPendingAStepCapture();
            refocusSnInput();
            return;
        }
        try {
            String path = data == null ? "" : data.getStringExtra("photoPath");
            if (path == null || path.isEmpty()) path = pendingAStepPhotoOutputPath;
            File photoFile = path == null || path.isEmpty() ? null : new File(path);
            if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0) {
                alert(t("photo_save_failed"), t("photo_full_file_missing"));
                return;
            }
            UnitRecord unit = pendingAStepPhotoUnitSequence > 0 ? unitBySequence(pendingAStepPhotoUnitSequence) : null;
            if (unit == null) unit = nextAStepPhotoUnit();
            if (unit == null || !needsPreviousStepPhoto(unit)) {
                deleteFileQuietly(path);
                toast(units.isEmpty() ? t("add_sn_first") : t("a_step_photos_done"));
                return;
            }
            String previous = unit.aStepPhotoPath;
            unit.aStepPhotoPath = path;
            if (!previous.isEmpty() && !previous.equals(unit.aStepPhotoPath)) deleteFileQuietly(previous);
            Diagnostics.append(this, "Previous-step photo captured for SN=" + unit.sn + " path=" + unit.aStepPhotoPath);
            refreshAStepPhotoUi();
            saveDraft();
            toast(t("a_step_photo_selected"));
        } catch (Exception exc) {
            alert(t("photo_save_failed"), conciseError(exc));
        } finally {
            clearPendingAStepCapture();
        }
        refocusSnInput();
    }

    private File createAStepPhotoOutputFile(UnitRecord unit) throws IOException {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create photo directory");
        }
        String sn = unit == null ? "unit" : unit.sn;
        return new File(dir, safePhotoFileName("a-step-" + sn + "-" + System.currentTimeMillis() + ".jpg"));
    }

    private void savePendingAStepCapture() {
        prefs.edit()
            .putString(PENDING_A_STEP_PHOTO_PATH_KEY, pendingAStepPhotoOutputPath)
            .putInt(PENDING_A_STEP_PHOTO_SEQ_KEY, pendingAStepPhotoUnitSequence)
            .apply();
        Diagnostics.append(this, "A-step photo capture started seq=" + pendingAStepPhotoUnitSequence + " path=" + pendingAStepPhotoOutputPath);
    }

    private void restorePendingAStepCapture(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            pendingAStepPhotoOutputPath = savedInstanceState.getString(STATE_PENDING_A_STEP_PHOTO_PATH, pendingAStepPhotoOutputPath);
        }
        if (pendingAStepPhotoOutputPath == null || pendingAStepPhotoOutputPath.isEmpty()) {
            pendingAStepPhotoOutputPath = prefs.getString(PENDING_A_STEP_PHOTO_PATH_KEY, pendingAStepPhotoOutputPath);
        }
        if (pendingAStepPhotoOutputPath == null) pendingAStepPhotoOutputPath = "";
        if (pendingAStepPhotoUnitSequence <= 0) {
            pendingAStepPhotoUnitSequence = prefs.getInt(PENDING_A_STEP_PHOTO_SEQ_KEY, pendingAStepPhotoUnitSequence);
        }
    }

    private void clearPendingAStepCapture() {
        if (pendingAStepPhotoOutputUri != null) {
            try {
                revokeUriPermission(
                    pendingAStepPhotoOutputUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
        }
        pendingAStepPhotoOutputUri = null;
        pendingAStepPhotoOutputPath = "";
        pendingAStepPhotoUnitSequence = -1;
        prefs.edit()
            .remove(PENDING_A_STEP_PHOTO_PATH_KEY)
            .remove(PENDING_A_STEP_PHOTO_SEQ_KEY)
            .apply();
    }

    private String copyGalleryPhoto(Uri uri) throws IOException {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create photo directory");
        }
        File outputFile = new File(dir, safePhotoFileName("a-step-" + System.currentTimeMillis() + ".jpg"));
        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(outputFile)) {
            if (input == null) throw new IOException("Cannot open selected image");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
        if (!outputFile.exists() || outputFile.length() <= 0) {
            throw new IOException("Selected image is empty");
        }
        return outputFile.getAbsolutePath();
    }

    private void captureNextPhoto() {
        if (isSlotMode()) {
            captureNextSlotPhoto();
            return;
        }
        PhotoStep step = nextPhotoStep();
        if (step == null) {
            toast(t("no_photo_needed"));
            return;
        }
        if (!ensureCameraPermission()) return;
        pendingPhotoIndex = step.index;
        pendingPhotoSide = step.side;
        pendingPhotoField = "";
        startCameraForPendingPhoto();
    }

    private void captureNextSlotPhoto() {
        int[] next = nextSlotStep();
        if (next == null) {
            toast(t("no_photo_needed"));
            return;
        }
        if (!ensureCameraPermission()) return;
        beginSlotCapture(next[0], next[1]);
    }

    private void captureSlotPhotoFor(UnitRecord unit, int slotIndex) {
        int index = units.indexOf(unit);
        if (index < 0) return;
        if (!ensureCameraPermission()) return;
        beginSlotCapture(index, slotIndex);
    }

    private void beginSlotCapture(int unitIndex, int slotIndex) {
        JSONArray slots = photoSlots();
        JSONObject slot = slots == null ? null : slots.optJSONObject(slotIndex);
        if (slot == null) return;
        pendingPhotoIndex = unitIndex;
        pendingPhotoSide = "slot";
        pendingPhotoField = slot.optString("field");
        startCameraForPendingPhoto();
    }

    private void captureSupplementalPhoto(UnitRecord unit) {
        int index = units.indexOf(unit);
        if (index < 0) return;
        if (!ensureCameraPermission()) return;
        pendingPhotoIndex = index;
        pendingPhotoSide = "supplemental";
        pendingPhotoField = "";
        startCameraForPendingPhoto();
    }

    private void startCameraForPendingPhoto() {
        try {
            File outputFile = createPendingPhotoOutputFile();
            pendingOutputPhotoPath = outputFile.getAbsolutePath();
            pendingOutputPhotoUri = SimplePhotoProvider.uriForFile(this, outputFile);
            savePendingPhotoOutput();
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingOutputPhotoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<ResolveInfo> cameraApps = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (intent.resolveActivity(getPackageManager()) != null && !cameraApps.isEmpty()) {
                for (ResolveInfo cameraApp : cameraApps) {
                    grantUriPermission(
                        cameraApp.activityInfo.packageName,
                        pendingOutputPhotoUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                }
                Diagnostics.append(this, "Starting system camera for original photo file");
                startActivityForResult(intent, REQ_CAPTURE_PHOTO);
                return;
            }
            startInternalCamera(outputFile);
        } catch (Exception exc) {
            clearPendingPhotoOutput();
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private void startInternalCamera(File outputFile) {
        Intent intent = new Intent(this, CaptureActivity.class);
        intent.putExtra("fileName", outputFile.getName());
        intent.putExtra("label", photoCaptureLabel());
        intent.putExtra("lang", lang);
        Diagnostics.append(this, "Starting internal camera fallback for original photo bytes");
        startActivityForResult(intent, REQ_CAPTURE_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN_SN || requestCode == REQ_SCAN_BASE) {
            handleScanResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == REQ_RESCAN_UNIT_SN || requestCode == REQ_RESCAN_UNIT_BASE_SN) {
            handleUnitSnRescanResult(resultCode, data, requestCode == REQ_RESCAN_UNIT_BASE_SN);
            return;
        }
        if (requestCode == REQ_OCR_SN || requestCode == REQ_OCR_BASE) {
            handleOcrPhotoResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == REQ_PICK_A_STEP_PHOTO) {
            handleAStepPhotoResult(resultCode, data);
            return;
        }
        if (requestCode == REQ_CAPTURE_A_STEP_PHOTO) {
            handleAStepPhotoCaptureResult(resultCode, data);
            return;
        }
        if (requestCode == REQ_SCAN_A_STEP_ENTRY_SN) {
            handleAStepEntryScanResult(resultCode, data);
            return;
        }
        if (requestCode == REQ_CAPTURE_A_STEP_ENTRY_PHOTO) {
            handleAStepEntryPhotoResult(resultCode, data);
            return;
        }
        if (requestCode != REQ_CAPTURE_PHOTO) {
            return;
        }
        restorePendingPhotoOutput(null);
        Diagnostics.append(this, "Photo result received resultCode=" + resultCode + " index=" + pendingPhotoIndex + " side=" + pendingPhotoSide);
        if (resultCode != RESULT_OK) {
            clearPendingPhotoOutput();
            return;
        }
        if (!ensureFormStateForPhotoResult()) {
            clearPendingPhotoOutput();
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        String path = data == null ? "" : data.getStringExtra("photoPath");
        if (path == null || path.isEmpty()) path = pendingOutputPhotoPath;
        File photoFile = path == null || path.isEmpty() ? null : new File(path);
        if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0) {
            clearPendingPhotoOutput();
            alert(t("photo_save_failed"), t("photo_full_file_missing"));
            return;
        }
        try {
            UnitRecord unit = units.get(pendingPhotoIndex);
            if ("front".equals(pendingPhotoSide)) {
                unit.frontPhoto = path;
            } else if ("back".equals(pendingPhotoSide)) {
                unit.backPhoto = path;
            } else if ("supplemental".equals(pendingPhotoSide)) {
                unit.supplementalPhotos.add(path);
            } else if ("slot".equals(pendingPhotoSide)) {
                List<String> photos = unit.slotPhotos.get(pendingPhotoField);
                if (photos == null) {
                    photos = new ArrayList<>();
                    unit.slotPhotos.put(pendingPhotoField, photos);
                }
                photos.add(path);
            }
            Diagnostics.append(this, "Photo saved for SN=" + unit.sn + " side=" + pendingPhotoSide + " path=" + path + " bytes=" + photoFile.length());
            if (!isSlotMode() && !"supplemental".equals(pendingPhotoSide)) {
                PhotoStep next = nextPhotoStep();
                if (next != null && next.frontsCompleteTransition) {
                    alert(t("photo_notice"), t("start_back_photos"));
                }
            }
            refreshFormUi();
            saveDraft();
        } catch (Exception exc) {
            alert(t("photo_save_failed"), exc.getMessage());
        } finally {
            clearPendingPhotoOutput();
        }
    }

    private void handleOcrPhotoResult(int requestCode, int resultCode, Intent data) {
        boolean baseSn = requestCode == REQ_OCR_BASE;
        restorePendingOcrOutput(null);
        Diagnostics.append(this, "OCR photo result received resultCode=" + resultCode + " target=" + (baseSn ? "base" : "robot"));
        if (resultCode != RESULT_OK) {
            clearPendingOcrOutput();
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        String path = data == null ? "" : data.getStringExtra("photoPath");
        if (path == null || path.isEmpty()) path = pendingOcrPhotoPath;
        File photoFile = path == null || path.isEmpty() ? null : new File(path);
        if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0) {
            clearPendingOcrOutput();
            alert(t("photo_save_failed"), t("photo_full_file_missing"));
            return;
        }
        clearPendingOcrOutput();
        recognizeSnFromPhoto(baseSn, photoFile, false);
    }

    private void recognizeSnFromPhoto(boolean baseSn, File photoFile) {
        recognizeSnFromPhoto(baseSn, photoFile, false);
    }

    private void recognizeSnFromPhoto(boolean baseSn, File photoFile, boolean autoCapture) {
        String recognizeTextUrl = prefs.getString("recognizeTextUrl", "").trim();
        if (recognizeTextUrl.isEmpty()) {
            ensureOcrUrlThenRecognize(baseSn, photoFile, autoCapture);
            return;
        }
        appendLog(t("ocr_running"));
        new Thread(() -> {
            if (!backendConfigured()) { notifyBackendUnconfigured(); return; }
            try {
                Api api = new Api(apiBase(), savedToken(), webFingerprint(), webOrigin(), webReferer(), endpoints());
                List<File> images = prepareSnRecognitionImages(photoFile);
                for (File image : images) {
                    try {
                        JSONObject body = api.recognizeText(recognizeTextUrl, image);
                        List<String> candidates = extractOcrCandidates(body);
                        if (!candidates.isEmpty()) {
                            runOnUiThread(() -> showOcrCandidates(baseSn, candidates));
                            return;
                        }
                    } catch (Exception imageExc) {
                        Diagnostics.append(this, "OCR image skipped: " + conciseError(imageExc));
                    }
                }
                runOnUiThread(() -> handleOcrNoText(baseSn, autoCapture));
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    if (autoCapture) {
                        toast(t("ocr_auto_no_text"));
                        if (baseSn) refocusBaseInput(); else refocusSnInput();
                    } else {
                        alert(t("ocr_failed"), conciseError(exc));
                    }
                });
            }
        }).start();
    }

    private void ensureOcrUrlThenRecognize(boolean baseSn, File photoFile) {
        ensureOcrUrlThenRecognize(baseSn, photoFile, false);
    }

    private void ensureOcrUrlThenRecognize(boolean baseSn, File photoFile, boolean autoCapture) {
        String token = savedToken();
        if (token.isEmpty()) {
            alert(t("login_required"), t("login_required_detail"));
            return;
        }
        appendLog(t("ocr_url_refreshing"));
        new Thread(() -> {
            if (!backendConfigured()) { notifyBackendUnconfigured(); return; }
            try {
                Api.UserProfile user = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints()).fetchUserInfo();
                if (!user.userName.isEmpty() || !user.recognizeTextUrl.isEmpty()) {
                    SharedPreferences.Editor editor = prefs.edit();
                    if (!user.userName.isEmpty()) editor.putString("userName", user.userName);
                    if (!user.recognizeTextUrl.isEmpty()) editor.putString("recognizeTextUrl", user.recognizeTextUrl);
                    editor.apply();
                }
                runOnUiThread(() -> {
                    if (user.recognizeTextUrl.isEmpty()) {
                        alert(t("ocr_unavailable_title"), t("ocr_unavailable_detail"));
                        if (baseSn) refocusBaseInput(); else refocusSnInput();
                    } else {
                        recognizeSnFromPhoto(baseSn, photoFile, autoCapture);
                    }
                });
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    if (autoCapture) {
                        toast(t("ocr_auto_no_text"));
                        if (baseSn) refocusBaseInput(); else refocusSnInput();
                    } else {
                        alert(t("ocr_failed"), conciseError(exc));
                    }
                });
            }
        }).start();
    }

    private void handleOcrNoText(boolean baseSn, boolean autoCapture) {
        if (autoCapture) {
            toast(t("ocr_auto_no_text"));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        alert(t("ocr_no_text_title"), t("ocr_no_text_detail"));
    }

    private void showOcrCandidates(boolean baseSn, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            alert(t("ocr_no_text_title"), t("ocr_no_text_detail"));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        if (!baseSn) {
            int expected = expectedRobotSnLength();
            if (expected > 0) {
                List<String> filtered = new ArrayList<>();
                for (String candidate : candidates) {
                    if (normalize(candidate).length() == expected) filtered.add(candidate);
                }
                candidates = filtered;
                if (candidates.isEmpty()) {
                    alert(t("ocr_no_text_title"), robotSnExpectedOnlyMessage(expected));
                    refocusSnInput();
                    return;
                }
            }
        }
        String[] items = candidates.toArray(new String[0]);
        if (!activityAlive()) return;
        new AlertDialog.Builder(this)
            .setTitle(t("ocr_choose_title"))
            .setItems(items, (dialog, which) -> applyRecognizedSn(baseSn, items[which]))
            .setNegativeButton(t("cancel"), (dialog, which) -> {
                if (baseSn) refocusBaseInput(); else refocusSnInput();
            })
            .show();
    }

    private void applyRecognizedSn(boolean baseSn, String candidate) {
        String value = normalize(candidate);
        if (value.isEmpty()) {
            toast(baseSn ? t("base_required") : t("sn_required"));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        if (isPureNumeric(value)) {
            alert(t("scan_not_sn_title"), t("scan_not_sn_detail"));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        if (!baseSn && !validateRobotSnLength(value)) {
            refocusSnInput();
            return;
        }
        Diagnostics.append(this, "OCR selected target=" + (baseSn ? "base" : "robot") + " length=" + value.length());
        showScannedSnPreview(value, baseSn ? t("base_sn") : t("robot_sn"));
        if (baseSn) {
            if (baseSnEdit == null) showFormPage(false);
            if (baseSnEdit != null) baseSnEdit.setText(value);
            addBaseSnValue(value);
            refocusBaseInput();
            return;
        }
        if (snEdit == null) showFormPage(false);
        if (snEdit != null) snEdit.setText(value);
        UnitRecord added = addSnRecord(value, selectedGrade());
        if (added != null) {
            if (snEdit != null) snEdit.setText("");
            resetGradeSelection();
            checkScannedUnitPreviousSteps(added);
        }
        refocusSnInput();
    }

    private List<File> prepareSnRecognitionImages(File original) throws IOException {
        List<File> images = new ArrayList<>();
        Bitmap bitmap = decodeRecognitionBitmap(original, 1800);
        if (bitmap != null) {
            try {
                images.addAll(saveWhiteLabelCrops(bitmap));
                images.addAll(saveLightLabelCrops(bitmap));
                images.addAll(saveScannerCenterCrops(bitmap));
            } finally {
                bitmap.recycle();
            }
        }
        images.add(original);
        return images;
    }

    private Bitmap decodeRecognitionBitmap(File file, int maxSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= maxSize) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception exc) {
            Diagnostics.append(this, "OCR bitmap decode failed: " + exc.getMessage());
            return null;
        }
    }

    private List<File> saveWhiteLabelCrops(Bitmap bitmap) throws IOException {
        List<WhiteBox> boxes = detectWhiteLabelBoxes(bitmap);
        List<File> crops = new ArrayList<>();
        File dir = new File(getCacheDir(), "ocr");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create OCR cache");
        int count = Math.min(4, boxes.size());
        for (int i = 0; i < count; i++) {
            WhiteBox box = boxes.get(i);
            int marginX = Math.max(8, box.height / 2);
            int marginY = Math.max(6, box.height / 3);
            int left = Math.max(0, box.left - marginX);
            int top = Math.max(0, box.top - marginY);
            int right = Math.min(bitmap.getWidth(), box.left + box.width + marginX);
            int bottom = Math.min(bitmap.getHeight(), box.top + box.height + marginY);
            if (right <= left || bottom <= top) continue;
            Bitmap crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
            File out = new File(dir, "sn-white-box-" + System.currentTimeMillis() + "-" + i + ".jpg");
            try (FileOutputStream output = new FileOutputStream(out)) {
                crop.compress(Bitmap.CompressFormat.JPEG, 96, output);
            } finally {
                crop.recycle();
            }
            crops.add(out);
        }
        Diagnostics.append(this, "OCR white label crops=" + crops.size());
        return crops;
    }

    private List<File> saveLightLabelCrops(Bitmap bitmap) throws IOException {
        List<WhiteBox> boxes = detectLightLabelPanels(bitmap);
        List<File> crops = new ArrayList<>();
        File dir = ocrCacheDir();
        int count = Math.min(2, boxes.size());
        for (int i = 0; i < count; i++) {
            WhiteBox box = boxes.get(i);
            int marginX = Math.max(12, box.width / 12);
            int marginY = Math.max(10, box.height / 10);
            crops.add(saveBitmapCrop(
                bitmap,
                Math.max(0, box.left - marginX),
                Math.max(0, box.top - marginY),
                Math.min(bitmap.getWidth(), box.left + box.width + marginX),
                Math.min(bitmap.getHeight(), box.top + box.height + marginY),
                dir,
                "sn-label-panel-" + i
            ));
            if (box.width > bitmap.getWidth() * 0.22f && box.height > bitmap.getHeight() * 0.08f) {
                crops.add(saveBitmapCrop(
                    bitmap,
                    Math.max(0, box.left - marginX),
                    Math.max(0, box.top + box.height / 4 - marginY),
                    Math.min(bitmap.getWidth(), box.left + Math.round(box.width * 0.78f) + marginX),
                    Math.min(bitmap.getHeight(), box.top + Math.round(box.height * 0.72f) + marginY),
                    dir,
                    "sn-label-line-" + i
                ));
            }
        }
        Diagnostics.append(this, "OCR light label crops=" + crops.size());
        return crops;
    }

    private List<File> saveScannerCenterCrops(Bitmap bitmap) throws IOException {
        List<File> crops = new ArrayList<>();
        File dir = ocrCacheDir();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        crops.add(saveBitmapCrop(
            bitmap,
            Math.round(width * 0.04f),
            Math.round(height * 0.22f),
            Math.round(width * 0.96f),
            Math.round(height * 0.84f),
            dir,
            "sn-center-label"
        ));
        crops.add(saveBitmapCrop(
            bitmap,
            Math.round(width * 0.10f),
            Math.round(height * 0.34f),
            Math.round(width * 0.90f),
            Math.round(height * 0.68f),
            dir,
            "sn-center-line"
        ));
        return crops;
    }

    private File saveBitmapCrop(Bitmap bitmap, int left, int top, int right, int bottom, File dir, String prefix) throws IOException {
        left = Math.max(0, Math.min(bitmap.getWidth() - 1, left));
        top = Math.max(0, Math.min(bitmap.getHeight() - 1, top));
        right = Math.max(left + 1, Math.min(bitmap.getWidth(), right));
        bottom = Math.max(top + 1, Math.min(bitmap.getHeight(), bottom));
        Bitmap crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
        File out = new File(dir, prefix + "-" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream output = new FileOutputStream(out)) {
            crop.compress(Bitmap.CompressFormat.JPEG, 96, output);
        } finally {
            crop.recycle();
        }
        return out;
    }

    private File ocrCacheDir() throws IOException {
        File dir = new File(getCacheDir(), "ocr");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create OCR cache");
        return dir;
    }

    private List<WhiteBox> detectWhiteLabelBoxes(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int size = width * height;
        byte[] mask = new byte[size];
        byte[] seen = new byte[size];
        for (int y = Math.max(0, height / 4); y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int color = bitmap.getPixel(x, y);
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int bright = (r + g + b) / 3;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (bright >= 185 && max - min <= 90) mask[offset + x] = 1;
            }
        }

        int[] queue = new int[size];
        List<WhiteBox> boxes = new ArrayList<>();
        int minArea = Math.max(500, size / 2500);
        for (int i = 0; i < size; i++) {
            if (mask[i] == 0 || seen[i] != 0) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = i;
            seen[i] = 1;
            int minX = width;
            int minY = height;
            int maxX = 0;
            int maxY = 0;
            int area = 0;
            while (head < tail) {
                int p = queue[head++];
                int y = p / width;
                int x = p - y * width;
                area += 1;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                int left = p - 1;
                int right = p + 1;
                int up = p - width;
                int down = p + width;
                if (x > 0 && mask[left] != 0 && seen[left] == 0) { seen[left] = 1; queue[tail++] = left; }
                if (x + 1 < width && mask[right] != 0 && seen[right] == 0) { seen[right] = 1; queue[tail++] = right; }
                if (y > height / 4 && mask[up] != 0 && seen[up] == 0) { seen[up] = 1; queue[tail++] = up; }
                if (y + 1 < height && mask[down] != 0 && seen[down] == 0) { seen[down] = 1; queue[tail++] = down; }
            }
            int boxWidth = maxX - minX + 1;
            int boxHeight = maxY - minY + 1;
            float aspect = boxHeight <= 0 ? 0f : (float) boxWidth / (float) boxHeight;
            if (area >= minArea
                && minY > height * 0.35f
                && boxWidth > width * 0.08f
                && boxHeight > height * 0.008f
                && aspect >= 2.2f
                && aspect <= 12f) {
                float score = minY + area * 0.05f + (aspect >= 3f && aspect <= 7f ? 300f : 0f);
                boxes.add(new WhiteBox(minX, minY, boxWidth, boxHeight, area, score));
            }
        }
        Collections.sort(boxes, (a, b) -> Float.compare(b.score, a.score));
        return boxes;
    }

    private List<WhiteBox> detectLightLabelPanels(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int size = width * height;
        byte[] mask = new byte[size];
        byte[] seen = new byte[size];
        for (int y = Math.max(0, height / 5); y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int color = bitmap.getPixel(x, y);
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int bright = (r + g + b) / 3;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (bright >= 150 && max - min <= 105) mask[offset + x] = 1;
            }
        }

        int[] queue = new int[size];
        List<WhiteBox> boxes = new ArrayList<>();
        int minArea = Math.max(1500, size / 900);
        for (int i = 0; i < size; i++) {
            if (mask[i] == 0 || seen[i] != 0) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = i;
            seen[i] = 1;
            int minX = width;
            int minY = height;
            int maxX = 0;
            int maxY = 0;
            int area = 0;
            while (head < tail) {
                int p = queue[head++];
                int y = p / width;
                int x = p - y * width;
                area += 1;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                int left = p - 1;
                int right = p + 1;
                int up = p - width;
                int down = p + width;
                if (x > 0 && mask[left] != 0 && seen[left] == 0) { seen[left] = 1; queue[tail++] = left; }
                if (x + 1 < width && mask[right] != 0 && seen[right] == 0) { seen[right] = 1; queue[tail++] = right; }
                if (y > height / 5 && mask[up] != 0 && seen[up] == 0) { seen[up] = 1; queue[tail++] = up; }
                if (y + 1 < height && mask[down] != 0 && seen[down] == 0) { seen[down] = 1; queue[tail++] = down; }
            }
            int boxWidth = maxX - minX + 1;
            int boxHeight = maxY - minY + 1;
            float aspect = boxHeight <= 0 ? 0f : (float) boxWidth / (float) boxHeight;
            float fill = boxWidth * boxHeight == 0 ? 0f : (float) area / (float) (boxWidth * boxHeight);
            float darkDensity = darkPixelDensity(bitmap, minX, minY, maxX + 1, maxY + 1);
            if (area >= minArea
                && minY > height * 0.18f
                && boxWidth > width * 0.16f
                && boxHeight > height * 0.035f
                && aspect >= 0.45f
                && aspect <= 10f
                && fill >= 0.12f
                && darkDensity >= 0.008f
                && darkDensity <= 0.45f
                && !(boxWidth > width * 0.94f && boxHeight > height * 0.60f)) {
                float score = area * 0.03f + darkDensity * 1200f + minY * 0.15f;
                boxes.add(new WhiteBox(minX, minY, boxWidth, boxHeight, area, score));
            }
        }
        Collections.sort(boxes, (a, b) -> Float.compare(b.score, a.score));
        return boxes;
    }

    private float darkPixelDensity(Bitmap bitmap, int left, int top, int right, int bottom) {
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(bitmap.getWidth(), right);
        bottom = Math.min(bitmap.getHeight(), bottom);
        int step = Math.max(1, Math.min(right - left, bottom - top) / 80);
        int dark = 0;
        int total = 0;
        for (int y = top; y < bottom; y += step) {
            for (int x = left; x < right; x += step) {
                int color = bitmap.getPixel(x, y);
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int bright = (r + g + b) / 3;
                if (bright <= 120) dark += 1;
                total += 1;
            }
        }
        return total == 0 ? 0f : (float) dark / (float) total;
    }

    private File createPendingPhotoOutputFile() throws IOException {
        if (pendingPhotoIndex < 0 || pendingPhotoIndex >= units.size()) {
            throw new IOException("Photo target missing");
        }
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create photo directory");
        }
        UnitRecord unit = units.get(pendingPhotoIndex);
        String baseName = pendingPhotoFileName(unit);
        int dot = baseName.lastIndexOf('.');
        String stamp = "-" + System.currentTimeMillis();
        String name = dot >= 0
            ? baseName.substring(0, dot) + stamp + baseName.substring(dot)
            : baseName + stamp + ".jpg";
        return new File(dir, safePhotoFileName(name));
    }

    private File createOcrPhotoOutputFile(boolean baseSn) throws IOException {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create photo directory");
        }
        String prefix = baseSn ? "base-ocr-" : "sn-ocr-";
        return new File(dir, safePhotoFileName(prefix + System.currentTimeMillis() + ".jpg"));
    }

    private void clearPendingPhotoOutput() {
        if (pendingOutputPhotoUri != null) {
            try {
                revokeUriPermission(
                    pendingOutputPhotoUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
        }
        pendingOutputPhotoUri = null;
        pendingPhotoIndex = -1;
        pendingPhotoSide = "";
        pendingPhotoField = "";
        pendingOutputPhotoPath = "";
        prefs.edit()
            .remove(PENDING_PHOTO_INDEX_KEY)
            .remove(PENDING_PHOTO_SIDE_KEY)
            .remove(PENDING_PHOTO_FIELD_KEY)
            .remove(PENDING_PHOTO_PATH_KEY)
            .apply();
    }

    private void savePendingPhotoOutput() {
        prefs.edit()
            .putInt(PENDING_PHOTO_INDEX_KEY, pendingPhotoIndex)
            .putString(PENDING_PHOTO_SIDE_KEY, pendingPhotoSide)
            .putString(PENDING_PHOTO_FIELD_KEY, pendingPhotoField)
            .putString(PENDING_PHOTO_PATH_KEY, pendingOutputPhotoPath)
            .apply();
        Diagnostics.append(this, "Photo capture started index=" + pendingPhotoIndex + " side=" + pendingPhotoSide + " path=" + pendingOutputPhotoPath);
    }

    private void clearPendingOcrOutput() {
        if (pendingOcrPhotoUri != null) {
            try {
                revokeUriPermission(
                    pendingOcrPhotoUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
        }
        pendingOcrPhotoUri = null;
        pendingOcrPhotoPath = "";
        prefs.edit().remove(PENDING_OCR_PHOTO_PATH_KEY).apply();
    }

    private void savePendingOcrOutput() {
        prefs.edit()
            .putString(PENDING_OCR_PHOTO_PATH_KEY, pendingOcrPhotoPath)
            .apply();
        Diagnostics.append(this, "OCR photo capture started path=" + pendingOcrPhotoPath);
    }

    private void restorePendingPhotoOutput(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            pendingPhotoIndex = savedInstanceState.getInt(STATE_PENDING_PHOTO_INDEX, pendingPhotoIndex);
            pendingPhotoSide = savedInstanceState.getString(STATE_PENDING_PHOTO_SIDE, pendingPhotoSide);
            pendingPhotoField = savedInstanceState.getString(STATE_PENDING_PHOTO_FIELD, pendingPhotoField);
            pendingOutputPhotoPath = savedInstanceState.getString(STATE_PENDING_PHOTO_PATH, pendingOutputPhotoPath);
        }
        if (pendingPhotoIndex < 0 || pendingOutputPhotoPath == null || pendingOutputPhotoPath.isEmpty()) {
            pendingPhotoIndex = prefs.getInt(PENDING_PHOTO_INDEX_KEY, pendingPhotoIndex);
            pendingPhotoSide = prefs.getString(PENDING_PHOTO_SIDE_KEY, pendingPhotoSide);
            pendingPhotoField = prefs.getString(PENDING_PHOTO_FIELD_KEY, pendingPhotoField);
            pendingOutputPhotoPath = prefs.getString(PENDING_PHOTO_PATH_KEY, pendingOutputPhotoPath);
        }
    }

    private void restorePendingOcrOutput(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            pendingOcrPhotoPath = savedInstanceState.getString(STATE_PENDING_OCR_PHOTO_PATH, pendingOcrPhotoPath);
        }
        if (pendingOcrPhotoPath == null || pendingOcrPhotoPath.isEmpty()) {
            pendingOcrPhotoPath = prefs.getString(PENDING_OCR_PHOTO_PATH_KEY, pendingOcrPhotoPath);
        }
        if (pendingOcrPhotoPath == null) pendingOcrPhotoPath = "";
    }

    private void restorePendingRescan(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            pendingRescanUnitSequence = savedInstanceState.getInt(STATE_PENDING_RESCAN_SEQUENCE, pendingRescanUnitSequence);
        }
        if (pendingRescanUnitSequence < 0) {
            pendingRescanUnitSequence = prefs.getInt(PENDING_RESCAN_SEQUENCE_KEY, pendingRescanUnitSequence);
        }
    }

    private void clearPendingRescan() {
        pendingRescanUnitSequence = -1;
        prefs.edit().remove(PENDING_RESCAN_SEQUENCE_KEY).apply();
    }

    private boolean ensureFormStateForPhotoResult() {
        if (pendingPhotoIndex >= 0 && pendingPhotoIndex < units.size()) return true;
        if (savedToken().isEmpty()) return false;
        JSONObject draft = draftForPhotoResult(pendingPhotoIndex);
        if (draft == null) return false;
        try {
            showFormPage(false);
            restoreDraft(draft);
            return pendingPhotoIndex >= 0 && pendingPhotoIndex < units.size();
        } catch (Exception exc) {
            Diagnostics.append(this, "Photo result draft restore failed: " + exc.getMessage());
            return false;
        }
    }

    private boolean ensureFormStateForRescanResult(int sequence) {
        if (unitBySequence(sequence) != null) return true;
        if (savedToken().isEmpty()) return false;
        JSONObject draft = draftForUnitSequence(sequence);
        if (draft == null) return false;
        try {
            showFormPage(false);
            restoreDraft(draft);
            return unitBySequence(sequence) != null;
        } catch (Exception exc) {
            Diagnostics.append(this, "Rescan result draft restore failed: " + exc.getMessage());
            return false;
        }
    }

    private String photoCaptureLabel() {
        if (pendingPhotoIndex >= 0 && pendingPhotoIndex < units.size()) {
            UnitRecord unit = units.get(pendingPhotoIndex);
            String label = "slot".equals(pendingPhotoSide) ? slotTitleForField(pendingPhotoField) : sideName(pendingPhotoSide);
            return "#" + unit.sequence + " " + unit.sn + " " + label;
        }
        return t("take_next_photo");
    }

    private String pendingPhotoFileName(UnitRecord unit) {
        if ("supplemental".equals(pendingPhotoSide)) {
            return unit.sn + "-supplemental-" + (unit.supplementalPhotos.size() + 1) + ".jpg";
        }
        if ("slot".equals(pendingPhotoSide)) {
            return unit.sn + "-" + safePhotoFileName(pendingPhotoField) + "-" + (slotPhotoCount(unit, pendingPhotoField) + 1) + ".jpg";
        }
        return unit.sn + "-" + pendingPhotoSide + ".jpg";
    }

    private static String safePhotoFileName(String value) {
        return value == null ? "photo.jpg" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void previewPayload() {
        if (units.isEmpty()) {
            toast(t("no_sn"));
            return;
        }
        new Thread(() -> {
            try {
                String token = savedToken();
                if (!token.isEmpty() && backendConfigured()) {
                    refreshProfileMaterials(new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints()));
                }
                StringBuilder builder = new StringBuilder();
                for (UnitRecord unit : units) {
                    JSONObject payload = buildPayload(unit, "front-url", "back-url", supplementalPlaceholders(unit), new HashSet<>(), isSlotMode() ? slotPlaceholders(unit) : null);
                    builder.append(unit.sn).append("\n").append(payload.toString(2)).append("\n\n");
                }
                runOnUiThread(() -> log(builder.toString()));
            } catch (Exception exc) {
                runOnUiThread(() -> alert(t("payload_failed"), exc.getMessage()));
            }
        }).start();
    }

    private void checkPreviousStepsForBatch() {
        String token = savedToken();
        if (token.isEmpty()) {
            alert(t("login_required"), t("login_required_detail"));
            return;
        }
        if (units.isEmpty()) {
            toast(t("no_sn"));
            return;
        }
        appendLog(t("checking_steps"));
        new Thread(() -> {
            List<String> errors = new ArrayList<>();
            if (!backendConfigured()) { notifyBackendUnconfigured(); return; }
            try {
                Api api = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints());
                for (UnitRecord unit : units) {
                    try {
                        ensurePreviousSteps(api, unit);
                    } catch (Exception exc) {
                        String message = conciseError(exc);
                        errors.add(unitLogLine(unit, message));
                        appendUnitLog(unit, message);
                    }
                }
                runOnUiThread(() -> {
                    refreshFormUi();
                    if (errors.isEmpty()) {
                        alert(t("check_done"), t("steps_ok"));
                    } else {
                        alert(t("steps_missing_title"), join(errors, "\n"));
                    }
                });
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    refreshFormUi();
                    alert(t("steps_missing_title"), conciseError(exc));
                });
            }
        }).start();
    }

    private void checkScannedUnitPreviousSteps(UnitRecord unit) {
        if (unit == null || isGradeAUnit(unit)) return;
        String token = savedToken();
        if (token.isEmpty()) return;
        appendUnitLog(unit, t("checking_steps"));
        new Thread(() -> {
            if (!backendConfigured()) return; // per-scan precheck: unconfigured → skip quietly
            try {
                Api api = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints());
                JSONObject body = previousStepsResponse(api, unit, unit.sn, true);
                if (Api.isSuccess(body)) {
                    clearScanPrecheckMissingCount(unit.sn);
                    markPreviousStepsOk(unit);
                    return;
                }
                String originalSn = unit.sn;
                if (tryCorrectScannedSnFromPreviousSteps(api, unit)) {
                    clearScanPrecheckMissingCount(originalSn);
                    clearScanPrecheckMissingCount(unit.sn);
                    markPreviousStepsOk(unit);
                    return;
                }
                String sn = unit.sn;
                int attempt = recordScanPrecheckMissing(sn);
                if (attempt >= 3) {
                    markUnitNeedsStepPhoto(unit);
                    runOnUiThread(() -> alert(scanPrecheckMissingTitle(attempt), sn + "\n" + t("scan_precheck_need_run_photo")));
                } else {
                    removeScannedUnitAfterPrecheckMissing(unit);
                    runOnUiThread(() -> alert(scanPrecheckMissingTitle(attempt), sn + "\n" + scanPrecheckMissingMessage(attempt)));
                }
            } catch (Exception exc) {
                appendUnitLog(unit, t("scan_precheck_failed") + conciseError(exc));
            }
        }).start();
    }

    private synchronized int recordScanPrecheckMissing(String sn) {
        String key = scanPrecheckKey(sn);
        if (key.isEmpty()) return 1;
        int count = scanPrecheckMissingCounts.containsKey(key) ? scanPrecheckMissingCounts.get(key) + 1 : 1;
        scanPrecheckMissingCounts.put(key, count);
        return count;
    }

    private synchronized void clearScanPrecheckMissingCount(String sn) {
        scanPrecheckMissingCounts.remove(scanPrecheckKey(sn));
    }

    private String scanPrecheckKey(String sn) {
        return normalize(sn).replace('O', '0').replace('I', '1');
    }

    private String scanPrecheckMissingMessage(int attempt) {
        if (attempt <= 1) return t("scan_precheck_retry_first");
        if (attempt == 2) return t("scan_precheck_retry_second");
        return t("scan_precheck_need_run_photo");
    }

    private String scanPrecheckMissingTitle(int attempt) {
        return attempt >= 3 ? t("steps_missing_title") : t("scan_precheck_retry_title");
    }

    private void markUnitNeedsStepPhoto(UnitRecord unit) {
        unit.stepPhotoRequired = true;
        unit.precheckStatus = t("failed");
        saveDraft();
        runOnUiThread(() -> {
            refreshFormUi();
            refocusSnInput();
        });
    }

    private void submitBatch() {
        if (submitting) {
            toast(t("submit_running"));
            return;
        }
        String token = savedToken();
        List<String> validationErrors = validateBatch(token);
        if (!validationErrors.isEmpty()) {
            alert(t("cannot_submit"), join(validationErrors, "\n"));
            return;
        }
        missingMaterialNoticeShown = false;
        notifiedMissingMaterialCodes.clear();
        synchronized (dnsAffectedUnits) {
            dnsAffectedUnits.clear();
        }
        synchronized (roundMissingMaterials) {
            roundMissingMaterials.clear();
        }
        submitting = true;
        int submittableTotal = 0;
        for (UnitRecord u : units) {
            if (!isSubmittedStatus(u.status)) submittableTotal++;
        }
        final int totalSubmittable = submittableTotal;
        showSubmitLoading(totalSubmittable);
        new Thread(() -> {
            if (!backendConfigured()) {
                runOnUiThread(() -> { submitting = false; hideSubmitLoading(); notifyBackendUnconfigured(); });
                return;
            }
            // Pre-submit auth gate: probe the inherited/cached token once up front so an expired login
            // (e.g. another device logged in) becomes a clear re-login prompt rather than a confusing
            // mid-batch upload failure. UNKNOWN (network blip) proceeds; the submit reports any failure.
            if (new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints()).checkAuth() == Api.AuthState.INVALID) {
                runOnUiThread(() -> {
                    submitting = false;
                    hideSubmitLoading();
                    handleRemoteLogout(true);
                });
                return;
            }
            boolean success = false;
            boolean abortedForPrinter = false;
            int removedDuringSubmit = 0;
            int submittedSoFar = 0;
            int consecutiveFailures = 0;
            List<String> errors = new ArrayList<>();
            List<String> inlineFailedSns = new ArrayList<>();
            List<JSONObject> roundLedger = new ArrayList<>(); // per-unit outcome (submit + print) for the local ledger
            try {
                Api api = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints());
                abortedForPrinter = !ensureCloudPrinterReady(api);
                if (!abortedForPrinter) {
                runWithSubmissionNetworkRetry(() -> refreshProfileMaterials(api), "");
                List<UnitRecord> queue = new ArrayList<>(units);
                boolean hasSubmittedUnitInBatch = false;
                int position = 0;
                for (UnitRecord unit : queue) {
                    position++;
                    if (!units.contains(unit)) continue;
                    if (isSubmittedStatus(unit.status)) {
                        appendUnitLog(unit, t("submitted_removed_log"));
                        if (removeSubmittedUnitFromQueue(unit)) removedDuringSubmit++;
                        continue;
                    }
                    int upcomingIndex = submittedSoFar + 1;
                    setSubmitProgressMessage(formatSubmitProgressUnit(upcomingIndex, totalSubmittable, unit.sn));
                    try {
                        final UnitRecord currentUnit = unit;
                        final int currentPosition = position;
                        currentDnsContext.set(new DnsContext(MainActivity.this, currentUnit, currentPosition));
                        try {
                            runWithSubmissionNetworkRetry(() -> submitUnit(api, currentUnit), unitLogLine(unit, ""), currentUnit, currentPosition);
                        } finally {
                            currentDnsContext.remove();
                        }
                        hasSubmittedUnitInBatch = true;
                        submittedSoFar++;
                        setSubmitProgress(submittedSoFar);
                        if (removeSubmittedUnitFromQueue(unit)) removedDuringSubmit++;
                        consecutiveFailures = 0;
                        // Confirm this unit's label printed; reprint inline (in order) before the next unit.
                        // Done here — not at the end — so reprinted labels stay in sequence. Also serves
                        // as the inter-unit spacing (replaces the old fixed wait).
                        confirmPrintInline(api, currentUnit, inlineFailedSns);
                        // record this unit locally: submit OK; printed unless inline-confirm gave up on its label
                        roundLedger.add(ledgerUnit(currentUnit.sn, true, !inlineFailedSns.contains(currentUnit.sn), currentUnit.grade));
                    } catch (Exception exc) {
                        String message = conciseError(exc);
                        errors.add(unitLogLine(unit, message));
                        appendUnitLog(unit, message);
                        unit.status = "failed";
                        roundLedger.add(ledgerUnit(unit.sn, false, false, unit.grade)); // submit failed: not uploaded, no label
                        saveDraft();
                        runOnUiThread(this::refreshFormUi);
                        reportSubmitFailure(unit, position, exc);
                        consecutiveFailures++;
                        if (consecutiveFailures >= MAX_CONSECUTIVE_SUBMIT_FAILURES) {
                            errors.add(t("submit_aborted_consecutive"));
                            break;
                        }
                        // keep going: one bad unit must not strand the rest of the batch
                    }
                }
                // One merged report for the whole batch (not per-unit) so it stacks on a single issue.
                if (!inlineFailedSns.isEmpty()) reportInlinePrintFailures(inlineFailedSns);
                if (!roundLedger.isEmpty()) saveRoundToLedger(roundLedger);
                success = errors.isEmpty();
                }
            } catch (Exception exc) {
                errors.add(t("submit_warmup_failed") + conciseError(exc));
                reportSubmitFailure(null, 0, exc);
            } finally {
                boolean finalSuccess = success;
                boolean finalAborted = abortedForPrinter;
                int finalSubmittedSoFar = submittedSoFar;
                int finalRemovedDuringSubmit = removedDuringSubmit;
                int finalSubmitted = submittedSoFar;
                List<String> finalErrors = new ArrayList<>(errors);
                List<String> finalInlineFailed = new ArrayList<>(inlineFailedSns); // SNs we could not confirm printed
                runOnUiThread(() -> {
                    submitting = false;
                    hideSubmitLoading();
                    if (finalAborted) {
                        toast(t("submit_cancelled_printer_offline"));
                        return;
                    }
                    int removed = finalRemovedDuringSubmit + pruneSubmittedUnits();
                    String dnsWarning = buildDnsAffectedMessage();
                    boolean offerReconcile = finalSubmittedSoFar > 0;
                    if (finalSuccess) {
                        String message = removed > 0 ? t("submit_done_queue_cleared") : t("submit_done");
                        if (offerReconcile) message += "\n\n" + t("submit_done_check_print");
                        if (!dnsWarning.isEmpty()) message += "\n\n" + dnsWarning;
                        if (!finalInlineFailed.isEmpty()) message += "\n\n" + t("inline_unconfirmed_prefix") + finalInlineFailed.size() + "\n" + join(finalInlineFailed, ", ");
                        showBatchResultDialog(t("done"), message, offerReconcile);
                    } else {
                        String message = join(finalErrors, "\n");
                        if (removed > 0) message += "\n" + t("submitted_removed_note") + removed;
                        message += "\n" + t("submit_failed_queue_kept");
                        if (!dnsWarning.isEmpty()) message += "\n\n" + dnsWarning;
                        if (!finalInlineFailed.isEmpty()) message += "\n\n" + t("inline_unconfirmed_prefix") + finalInlineFailed.size() + "\n" + join(finalInlineFailed, ", ");
                        showBatchResultDialog(t("submit_failed"), message, offerReconcile);
                    }
                    notifyRoundToNotify(finalSuccess, finalSubmitted, finalErrors, finalInlineFailed, dnsWarning);
                });
            }
        }).start();
    }

    // ===== Cloud-box print reconciliation & reprint (the "丢单" safety net) =====
    // A successful submit (code 200) only means the backend saved the record. The label is printed
    // asynchronously by a cloud box; if that print fails/offline the operator never sees it and
    // hand-writes the unit. These helpers surface the real print status, allow one-tap reprint,
    // and expose the label PDF as a last resort instead of hand-writing.

    private static final int PRINT_STATUS_MISSING = -1; // synthetic: submitted this round but no print job found at all

    // Blocking yes/no shown on the UI thread; safe to call from a background (submit) thread.
    private boolean confirmOnUiThread(String title, String message, String okText, String cancelText) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] result = {false};
        runOnUiThread(() -> {
            if (!activityAlive()) { latch.countDown(); return; } // activity gone: unblock caller, default to "no"
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(okText, (d, w) -> { result[0] = true; latch.countDown(); })
                .setNegativeButton(cancelText, (d, w) -> { result[0] = false; latch.countDown(); })
                .create();
            dialog.setOnDismissListener(d -> latch.countDown()); // never hang the caller
            dialog.show();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    // Returns true to go ahead with the batch. If the cloud box is not online, asks the operator
    // (proceed anyway vs fix the printer first) so we don't dump a whole batch into a dead printer.
    private boolean ensureCloudPrinterReady(Api api) {
        String note;
        try {
            JSONObject st = api.cloudPrinterState();
            JSONObject data = Api.apiDataObject(st);
            if (Api.isSuccess(st) && data != null && data.optInt("status", 0) == 1) {
                return true;
            }
            note = Api.isSuccess(st) ? t("printer_offline") : Api.apiErrorMessage(st);
        } catch (Exception e) {
            note = t("printer_check_failed") + conciseError(e);
        }
        // Printer not confirmed online before a batch — log + report so we have a trail if it causes 丢单.
        appendLog("cloudPrinterState not-online at submit: " + note);
        FailureReporter.get().report("print", "printer_not_ready", "pre_submit", note,
                FailureReporter.ctx("note", note), null);
        return confirmOnUiThread(t("printer_warn_title"),
            t("printer_warn_msg") + "\n\n" + note,
            t("printer_warn_proceed"), t("printer_warn_fix"));
    }

    private void showBatchResultDialog(String title, String message, boolean offerReconcile) {
        if (!activityAlive()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setMessage(message);
        if (offerReconcile) {
            b.setPositiveButton(t("print_reconcile_open"), (d, w) -> showPrintReconcileDialog());
            b.setNegativeButton(t("close"), null);
        } else {
            b.setPositiveButton("OK", null);
        }
        b.show();
    }

    // TEMP DIAGNOSTIC (not for beta): dump raw API responses to logcat tag PRINTDIAG so we can see
    // the real refurb-record vs print-job shapes against live operator data, then design correctly.
    // ===== Cloud-box print reconciliation & reprint (the "丢单" safety net) =====
    // Validated 2026-06-01 against live operator data:
    //  - getUserMessageList is scoped to the logged-in operator; for a refurb operator every item is a
    //    cloud-box label print where order_no == the machine SN (e.g. AOTDH11F12600634).
    //  - cloudPrinterState returns the operator's bound refurb printer {id,name,status}. We filter the
    //    list to type==1 (label prints) AND that printer's id, so other projects'/printers' jobs
    //    (warehouse, shipping, UGREEN handled by other users) can never appear.
    //  - retreadResultTodayDetailList returns 500 via the API, so it is NOT used.

    private void showPrintReconcileDialog() {
        if (savedToken().isEmpty()) {
            alert(t("print_reconcile_title"), t("token_required_reconcile"));
            return;
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        scroll.addView(root);

        TextView header = text(t("print_reconcile_loading"), 14, true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Button modeToggle = button(printReconcileCloudVerify ? t("reconcile_back_local") : t("reconcile_go_cloud"), v -> {
            printReconcileCloudVerify = !printReconcileCloudVerify;
            ((Button) v).setText(printReconcileCloudVerify ? t("reconcile_back_local") : t("reconcile_go_cloud"));
            loadPrintReconcile(header, list);
        });
        root.addView(modeToggle);
        root.addView(header);
        root.addView(list);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(t("print_reconcile_title"))
            .setView(scroll)
            .setPositiveButton(t("close"), null)
            .setNeutralButton(t("refresh"), null)
            .create();
        reconcileDialogOpen = true;
        dialog.setOnDismissListener(d -> reconcileDialogOpen = false); // closing the dialog stops the cloud-verify walk
        dialog.show();
        Button refresh = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (refresh != null) refresh.setOnClickListener(v -> loadPrintReconcile(header, list));
        loadPrintReconcile(header, list);
    }

    // Print reconciliation, ledger-driven: read the last 3 local rounds and show each unit's recorded
    // submit/print outcome (instant, offline). The toggle flips to cloud-verify, which re-queries each
    // SN's real print status and enables reprint. Local is the source of truth for "what each round was";
    // the cloud is consulted on demand to confirm/repair, not to reconstruct the round.
    private void loadPrintReconcile(TextView header, LinearLayout list) {
        runOnUiThread(() -> { header.setText(t("print_reconcile_loading")); list.removeAllViews(); });
        final String token = savedToken();
        final boolean cloud = printReconcileCloudVerify;
        new Thread(() -> {
            if (!backendConfigured()) { notifyBackendUnconfigured(); return; }
            boolean online = false; String note = "";
            Api api = null;
            try {
                api = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints());
                try {
                    JSONObject st = api.cloudPrinterState();
                    JSONObject d = Api.apiDataObject(st);
                    if (Api.isSuccess(st) && d != null) {
                        online = d.optInt("status", 0) == 1;
                        note = online ? t("printer_online") : t("printer_offline");
                    } else {
                        note = Api.isSuccess(st) ? t("printer_offline") : Api.apiErrorMessage(st);
                    }
                } catch (Exception e) { note = conciseError(e); }
            } catch (Exception e) { note = conciseError(e); }

            List<JSONObject> rounds = loadRecentRounds(3);
            // Confirm print outcomes against the cloud. Cloud-verify view re-checks every unit; the default
            // view checks only the still-"unconfirmed" ones (submitted but no confirmed label) — so just
            // opening/refreshing reconciliation resolves them to printed/failed, no manual toggle needed.
            if (api != null) {
                final boolean unconfirmedOnly = !cloud;
                int total = 0;
                for (JSONObject r : rounds) {
                    JSONArray us = r.optJSONArray("units");
                    for (int i = 0; us != null && i < us.length(); i++) {
                        JSONObject u = us.optJSONObject(i);
                        if (u == null || "failed".equals(u.optString("submit"))) continue;
                        if (unconfirmedOnly && "ok".equals(u.optString("printed"))) continue;
                        total++;
                    }
                }
                if (total > 0) {
                    int[] progress = {0};
                    for (JSONObject r : rounds) {
                        if (!activityAlive() || !reconcileDialogOpen) return; // activity gone or dialog closed — stop the per-SN cloud walk
                        verifyRoundAgainstCloud(api, r, header, progress, total, unconfirmedOnly);
                    }
                    persistLedgerPrintedOk(rounds); // a confirmed-printed (status 1) result sticks so it won't be re-queried
                }
            }
            final boolean fonline = online; final String fnote = note;
            final List<JSONObject> frounds = rounds;
            runOnUiThread(() -> renderReconcile(header, list, fonline, fnote, frounds, cloud));
        }).start();
    }

    private void renderReconcile(TextView header, LinearLayout list, boolean online, String note,
                                 List<JSONObject> rounds, boolean cloud) {
        header.setText((online ? "🟢 " : "🔴 ") + t("printer_label") + note
            + "\n" + (cloud ? t("reconcile_mode_cloud") : t("reconcile_mode_local")));
        list.removeAllViews();
        if (rounds == null || rounds.isEmpty()) {
            TextView empty = text(t("reconcile_no_rounds"), 14, false);
            empty.setTextColor(0xFF64748B);
            empty.setPadding(0, dp(18), 0, dp(18));
            list.addView(empty);
            return;
        }
        int roundNo = rounds.size(); // newest first -> highest number sits on top
        for (JSONObject r : rounds) list.addView(buildRoundCard(roundNo--, r, cloud, header, list));
    }

    // True when this unit counts as labeled. Whenever a cloud status was stamped (by the verify pass over
    // every unit in cloud view, or just the unconfirmed ones on a normal open) we trust that live status;
    // otherwise we fall back to the local ledger — the SAME rule the per-row renderer (buildUnitRow) uses,
    // so the round tally and the rows can never disagree. (cloud param kept for call-site stability.)
    private boolean unitLabeledOk(JSONObject u, boolean cloud) {
        if (u.has("cloudStatus")) return u.optInt("cloudStatus", -99) == 1;
        return "ok".equals(u.optString("printed"));
    }

    // One rounded card per round: a colored header strip (round #, time, submitted/labeled tally + a slim
    // progress bar) over one row per unit — red problems standing out from the calm green of printed units.
    private View buildRoundCard(int roundNo, JSONObject round, boolean cloud, TextView header, LinearLayout list) {
        JSONArray us = round.optJSONArray("units");
        int total = us == null ? 0 : us.length();
        int submitted = 0, labeled = 0;
        for (int i = 0; i < total; i++) {
            JSONObject u = us.optJSONObject(i); if (u == null) continue;
            boolean sok = "ok".equals(u.optString("submit"));
            if (sok) submitted++;
            boolean pOk = unitLabeledOk(u, cloud);
            if (sok && pOk) labeled++;
        }
        boolean allLabeled = submitted > 0 && labeled >= submitted;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(cp);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setStroke(dp(1), 0xFFE2E8F0);
        cardBg.setCornerRadius(dp(12));
        card.setBackground(cardBg);

        // header strip (rounded only on top so it sits flush inside the card)
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable headBg = new GradientDrawable();
        headBg.setColor(allLabeled ? 0xFFF0FDF4 : 0xFFFFF7ED); // green when fully labeled, amber when anything is missing
        headBg.setCornerRadii(new float[]{dp(12), dp(12), dp(12), dp(12), 0, 0, 0, 0});
        head.setBackground(headBg);

        TextView title = text("📦  " + t("round_word") + roundNo + "     " + round.optString("tsText", ""), 15, true);
        title.setTextColor(0xFF0F172A);
        head.addView(title);

        TextView tally = text(t("round_submitted") + submitted + "      " + t("round_labeled") + labeled + "/" + submitted, 13, true);
        tally.setTextColor(allLabeled ? 0xFF15803D : 0xFFB91C1C);
        tally.setPadding(0, dp(3), 0, 0);
        head.addView(tally);
        head.addView(thinProgressBar(labeled, submitted));
        card.addView(head);

        // unit rows: problems (red/gray) always shown; printed-OK units collapse behind a tap so opening
        // reconciliation lands you straight on "which ones still need a label".
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(8), dp(6), dp(8), dp(8));
        LinearLayout successBox = new LinearLayout(this);
        successBox.setOrientation(LinearLayout.VERTICAL);
        successBox.setVisibility(View.GONE);
        int seq = 1, okCount = 0;
        for (int i = 0; i < total; i++) {
            JSONObject u = us.optJSONObject(i); if (u == null) continue;
            boolean sok = "ok".equals(u.optString("submit"));
            boolean pOk = unitLabeledOk(u, cloud);
            View row = buildUnitRow(u, seq++, cloud, header, list);
            if (sok && pOk) { successBox.addView(row); okCount++; } else body.addView(row);
        }
        if (okCount > 0) {
            final int fOk = okCount;
            TextView toggle = text("✓ " + fOk + " " + t("ledger_labeled_collapsed"), 13, true);
            toggle.setTextColor(0xFF15803D);
            toggle.setPadding(dp(10), dp(8), dp(10), dp(8));
            toggle.setOnClickListener(v -> {
                boolean show = successBox.getVisibility() == View.GONE;
                successBox.setVisibility(show ? View.VISIBLE : View.GONE);
                toggle.setText((show ? "▾ " : "✓ ") + fOk + " " + t("ledger_labeled_collapsed"));
            });
            body.addView(toggle);
            body.addView(successBox);
        }
        card.addView(body);
        return card;
    }

    private View buildUnitRow(JSONObject u, int seq, boolean cloud, TextView header, LinearLayout list) {
        String sn = u.optString("sn", "");
        boolean submitFailed = "failed".equals(u.optString("submit"));
        int cloudStatus = u.optInt("cloudStatus", Integer.MIN_VALUE);
        long cloudId = u.optLong("cloudId", 0);

        String dot, label; int bgc, txtc; boolean canReprint = false;
        if (submitFailed) {
            dot = "⚪"; label = t("ledger_submit_failed"); bgc = 0xFFF8FAFC; txtc = 0xFF64748B;
        } else if (cloudStatus != Integer.MIN_VALUE) {
            if (cloudStatus == 1) { dot = "🟢"; label = t("print_status_ok"); bgc = 0xFFF0FDF4; txtc = 0xFF15803D; }
            else if (cloudStatus == 2) { dot = "🔴"; label = t("print_status_fail"); bgc = 0xFFFFF1F2; txtc = 0xFFB91C1C; canReprint = cloudId > 0; }
            else if (cloudStatus == PRINT_STATUS_MISSING) { dot = "🔴"; label = t("print_status_missing"); bgc = 0xFFFFF1F2; txtc = 0xFFB91C1C; }
            else { dot = "🟡"; label = t("print_status_ongoing"); bgc = 0xFFFFFBEB; txtc = 0xFFB45309; canReprint = cloudId > 0; }
        } else {
            boolean pOk = "ok".equals(u.optString("printed"));
            if (pOk) { dot = "🟢"; label = t("ledger_printed_ok"); bgc = 0xFFF0FDF4; txtc = 0xFF15803D; }
            else { dot = "🔴"; label = t("ledger_printed_unconfirmed"); bgc = 0xFFFFF1F2; txtc = 0xFFB91C1C; }
        }

        LinearLayout rowL = new LinearLayout(this);
        rowL.setOrientation(LinearLayout.HORIZONTAL);
        rowL.setGravity(Gravity.CENTER_VERTICAL);
        rowL.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, dp(4), 0, 0);
        rowL.setLayoutParams(rp);
        GradientDrawable rb = new GradientDrawable();
        rb.setColor(bgc);
        rb.setCornerRadius(dp(8));
        rowL.setBackground(rb);

        TextView seqTv = text("#" + seq, 13, true);
        seqTv.setTextColor(0xFF94A3B8);
        seqTv.setMinWidth(dp(34));
        rowL.addView(seqTv);

        TextView st = text(dot + " " + label, 13, true);
        st.setTextColor(txtc);
        st.setMinWidth(dp(104));
        rowL.addView(st);

        LinearLayout snWrap = new LinearLayout(this);
        snWrap.setOrientation(LinearLayout.HORIZONTAL);
        snWrap.setGravity(Gravity.CENTER_VERTICAL);
        snWrap.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView snTv = text(sn, 13, false);
        snTv.setTextColor(0xFF334155);
        snWrap.addView(snTv);
        // Grade (A/B/C) right after the SN — so a hand-written unit doesn't need a system lookup.
        String grade = u.optString("grade", "");
        if (!grade.isEmpty()) {
            TextView gradeTag = text(grade + " " + t("grade_class"), 13, true);
            gradeTag.setTextColor(0xFF7C3AED);
            gradeTag.setPadding(dp(8), 0, 0, 0);
            snWrap.addView(gradeTag);
        }
        rowL.addView(snWrap);

        if (canReprint) rowL.addView(button(t("reprint"), v -> doLabelPrinterRetry(cloudId, header, list)));
        // A "查无打印任务" row is otherwise a dead end — surface the how-to-recover hint right under it.
        if (cloudStatus == PRINT_STATUS_MISSING) {
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setLayoutParams(rp);
            rowL.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            wrap.addView(rowL);
            TextView hint = text(t("print_missing_hint"), 12, false);
            hint.setTextColor(0xFFB91C1C);
            hint.setPadding(dp(10), dp(2), dp(10), dp(4));
            wrap.addView(hint);
            return wrap;
        }
        return rowL;
    }

    // Slim two-segment bar: filled portion = labeled (green when full, red when partial), track = remainder.
    private View thinProgressBar(int done, int total) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        bp.setMargins(0, dp(8), 0, dp(2));
        bar.setLayoutParams(bp);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0xFFE2E8F0);
        track.setCornerRadius(dp(3));
        bar.setBackground(track);
        if (total <= 0) return bar;
        int remain = Math.max(0, total - done);
        boolean full = done >= total;
        if (done > 0) {
            View fill = new View(this);
            GradientDrawable fg = new GradientDrawable();
            fg.setColor(full ? 0xFF22C55E : 0xFFEF4444);
            fg.setCornerRadius(dp(3));
            fill.setBackground(fg);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (float) done));
            bar.addView(fill);
        }
        if (remain > 0) {
            View gap = new View(this);
            gap.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (float) remain));
            bar.addView(gap);
        }
        return bar;
    }

    // Cloud-verify pass: for each submitted SN, re-query its real print job and stamp cloudStatus/cloudId
    // onto the ledger unit so the row can show the live state and offer reprint. Submit-failed units are
    // skipped — the cloud never had them.
    private void verifyRoundAgainstCloud(Api api, JSONObject round, TextView header, int[] progress, int total, boolean unconfirmedOnly) {
        JSONArray us = round.optJSONArray("units");
        if (us == null) return;
        for (int i = 0; i < us.length(); i++) {
            if (!activityAlive() || !reconcileDialogOpen) return; // activity gone or dialog closed — stop walking SNs
            JSONObject u = us.optJSONObject(i);
            if (u == null || "failed".equals(u.optString("submit"))) continue;
            if (unconfirmedOnly && "ok".equals(u.optString("printed"))) continue; // already confirmed printed — skip the re-query
            String sn = u.optString("sn", "");
            if (sn.isEmpty()) continue;
            try {
                JSONObject job = latestPrintJobForSn(api, sn);
                if (job == null) { u.put("cloudStatus", PRINT_STATUS_MISSING); u.put("cloudId", 0); }
                else { u.put("cloudStatus", job.optInt("status", 0)); u.put("cloudId", job.optLong("id", 0)); }
            } catch (Exception ignored) {}
            final int d = ++progress[0];
            runOnUiThread(() -> { if (header != null) header.setText(t("reconcile_verifying") + " " + d + "/" + total); });
        }
    }

    private JSONArray printMessageArray(JSONObject body) {
        if (body == null) return null;
        Object data = body.opt("data");
        return data instanceof JSONArray ? (JSONArray) data : null;
    }

    private void doLabelPrinterRetry(long id, TextView header, LinearLayout list) {
        final String token = savedToken();
        toast(t("reprint_sending"));
        new Thread(() -> {
            if (!backendConfigured()) { notifyBackendUnconfigured(); return; }
            String msg;
            try {
                Api api = new Api(apiBase(), token, webFingerprint(), webOrigin(), webReferer(), endpoints());
                JSONObject r = api.labelPrinterRetry(id);
                if (Api.isSuccess(r)) {
                    Object data = Api.apiData(r);
                    String body = data == null ? "" : String.valueOf(data).trim();
                    msg = (body.isEmpty() || "null".equals(body)) ? t("reprint_done") : body;
                } else {
                    msg = t("reprint_failed") + Api.apiErrorMessage(r);
                    appendLog("labelPrinterRetry id=" + id + " not-ok: " + Api.apiErrorMessage(r));
                    FailureReporter.get().report("print", "reprint_api_failed", "labelPrinterRetry",
                            "labelPrinterRetry id=" + id + " => " + Api.apiErrorMessage(r),
                            FailureReporter.ctx("message_id", String.valueOf(id)), null);
                }
            } catch (Exception e) {
                msg = t("reprint_failed") + conciseError(e);
                appendLog("labelPrinterRetry id=" + id + " err: " + conciseError(e));
                FailureReporter.get().report("print", "reprint_api_error", "labelPrinterRetry",
                        "labelPrinterRetry id=" + id + " threw", FailureReporter.ctx("message_id", String.valueOf(id)), e);
            }
            final String fmsg = msg;
            runOnUiThread(() -> { toast(fmsg); loadPrintReconcile(header, list); });
        }).start();
    }

    private static final int AUTO_REPRINT_ROUNDS = 3;   // max inline reprints for a status==2 (failed) label
    // Poll the cloud box this many times for the label to land before giving up. Decoupled from (and
    // larger than) AUTO_REPRINT_ROUNDS so a merely SLOW cloud box — label created/printed a bit after the
    // old 3×2.5s≈7.5s window — is no longer reported as a lost unit (issue #5). A genuinely offline
    // printer (label never appears) still fails, just after a longer grace. Only unconfirmed units pay
    // the extra wait; a label that prints OK confirms on the first poll and the loop exits immediately.
    private static final int PRINT_CONFIRM_POLLS = 6;
    private static final long PRINT_CONFIRM_POLL_MS = 2500L;

    // Called inline during the submit loop, right after a unit submits. Polls for that unit's label to
    // resolve and reprints it (in order) if it failed, up to AUTO_REPRINT_ROUNDS. SNs that still fail
    // are collected so the batch can report them once at the end.
    private void confirmPrintInline(Api api, UnitRecord unit, List<String> failedSns) {
        if (unit == null || unit.sn == null || unit.sn.isEmpty()) return;
        String sn = unit.sn;
        boolean confirmedPrinted = false; // only a real status==1 clears this — everything else is a potential lost unit
        boolean jobEverSeen = false;      // distinguishes "printer offline, job never created" from "job exists but failed"
        int reprints = 0;                 // status==2 reprints issued so far, capped at AUTO_REPRINT_ROUNDS
        try {
            for (int poll = 1; poll <= PRINT_CONFIRM_POLLS; poll++) {
                setSubmitProgressMessage(t("confirming_print") + " " + sn);
                Thread.sleep(PRINT_CONFIRM_POLL_MS); // let the cloud box attempt before checking
                JSONObject job = latestPrintJobForSn(api, sn);
                if (job == null) continue; // print job not created yet — keep polling (cloud box may be slow)
                jobEverSeen = true;
                int status = job.optInt("status", 0);
                if (status == 1) { confirmedPrinted = true; break; } // printed OK
                if (status == 2 && reprints < AUTO_REPRINT_ROUNDS) { // failed → reprint, in order, capped
                    long id = job.optLong("id", 0);
                    if (id > 0) {
                        reprints++;
                        appendUnitLog(unit, t("inline_reprint_log") + reprints);
                        try { api.labelPrinterRetry(id); } catch (Exception ignored) {}
                    }
                }
                // status 3 (ongoing) or just reprinted -> keep polling for it to finish
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {}
        // CRITICAL: never silently pass a unit we could not confirm as printed. The old code only counted
        // status==2 as failed, so an offline printer (job never created -> latestPrintJobForSn always null)
        // slipped through reported as nothing — the exact "lost half the batch, nobody noticed" failure.
        // Now anything that isn't a confirmed status==1 is collected so the batch reports it and the
        // operator is told which SNs to reprint/check.
        if (!confirmedPrinted) {
            failedSns.add(sn);
            appendUnitLog(unit, jobEverSeen ? t("inline_reprint_gaveup") : t("inline_print_no_job"));
        }
    }

    // Newest type==1 print job whose order_no == sn (order_no is the SN for refurb labels).
    private JSONObject latestPrintJobForSn(Api api, String sn) throws Exception {
        JSONObject body = api.getJson(AppConfig.endpoint(this, "messageList", "/engineer/message/getUserMessageList"), "order_no=" + enc(sn) + "&page=1");
        JSONArray data = printMessageArray(body);
        if (data == null) return null;
        JSONObject best = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it == null || it.optInt("type", -1) != 1) continue;
            if (!sn.equals(it.optString("order_no", ""))) continue;
            if (best == null || it.optLong("id", 0) > best.optLong("id", 0)) best = it;
        }
        return best;
    }

    // One merged report per batch for labels that still failed after inline reprints. The reporter
    // fingerprints by stage/errCode (no SN), so repeats PATCH the SAME GitHub issue (count + timestamps)
    // instead of spawning duplicates; FailureReporter auto-attaches the recent in-app log + diagnostics.
    private void reportInlinePrintFailures(List<String> sns) {
        if (sns == null || sns.isEmpty()) return;
        String snList = join(sns, ", ");
        appendLog("PRINT FAILED after inline retry: " + snList);
        Map<String, String> ctx = FailureReporter.ctx(
                "failed_count", String.valueOf(sns.size()),
                "failed_sns", snList);
        FailureReporter.get().report("print", "label_failed_after_retry", "cloud_box",
                "Labels still failed after " + AUTO_REPRINT_ROUNDS + " inline reprints: " + snList,
                ctx, null);
    }

    private void showSubmitLoading(int total) {
        if (submitProgressDialog != null && submitProgressDialog.isShowing()) return;
        int safeTotal = Math.max(1, total);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(safeTotal);
        bar.setProgress(0);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        barParams.setMargins(0, 0, dp(12), 0);
        headerRow.addView(bar, barParams);

        TextView label = text("0/" + safeTotal, 14, true);
        headerRow.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(headerRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView message = text(t("submit_loading"), 14, false);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgParams.setMargins(0, dp(12), 0, 0);
        root.addView(message, msgParams);

        submitProgressBar = bar;
        submitProgressLabel = label;
        submitProgressMessage = message;
        submitProgressTotal = safeTotal;
        submitProgressCompleted = 0;

        submitProgressDialog = new AlertDialog.Builder(this)
            .setTitle(t("submit"))
            .setView(root)
            .create();
        submitProgressDialog.setCancelable(false);
        submitProgressDialog.setCanceledOnTouchOutside(false);
        submitProgressDialog.show();
    }

    private void hideSubmitLoading() {
        if (submitProgressDialog != null) {
            try {
                submitProgressDialog.dismiss();
            } catch (Exception ignored) {
            }
            submitProgressDialog = null;
        }
        submitProgressMessage = null;
        submitProgressBar = null;
        submitProgressLabel = null;
        submitProgressTotal = 0;
        submitProgressCompleted = 0;
    }

    private void setSubmitProgressMessage(String message) {
        runOnUiThread(() -> {
            if (submitProgressMessage != null) submitProgressMessage.setText(message);
        });
    }

    private void setSubmitProgress(int completed) {
        runOnUiThread(() -> {
            submitProgressCompleted = completed;
            if (submitProgressBar != null) submitProgressBar.setProgress(completed);
            if (submitProgressLabel != null) submitProgressLabel.setText(completed + "/" + submitProgressTotal);
        });
    }

    private String formatSubmitProgressUnit(int idx, int total, String sn) {
        String label = sn == null ? "" : sn;
        if ("en".equals(lang)) return "Submitting " + idx + "/" + total + ": " + label;
        if ("es".equals(lang)) return "Enviando " + idx + "/" + total + ": " + label;
        return "正在提交 " + idx + "/" + total + "：" + label;
    }

    private String formatSubmitProgressWait(long secs, int upcoming, int total) {
        if ("en".equals(lang)) return "Waiting " + secs + "s before next unit (" + upcoming + "/" + total + ")";
        if ("es".equals(lang)) return "Esperando " + secs + " s antes de la siguiente unidad (" + upcoming + "/" + total + ")";
        return "等待 " + secs + " 秒后提交下一台（" + upcoming + "/" + total + "）";
    }

    private interface SubmissionAction {
        void run() throws Exception;
    }

    private void runWithSubmissionNetworkRetry(SubmissionAction action, String label) throws Exception {
        runWithSubmissionNetworkRetry(action, label, null, 0);
    }

    private void runWithSubmissionNetworkRetry(SubmissionAction action, String label, UnitRecord unit, int position) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                action.run();
                if (attempt > 0) {
                    setSubmitProgressMessage(t("submit_loading"));
                }
                return;
            } catch (Exception exc) {
                if (!Api.isTransientApiNetworkError(exc)) {
                    if (attempt > 0) {
                        setSubmitProgressMessage(t("submit_loading"));
                    }
                    throw exc;
                }
                if (unit != null && Api.isDnsResolveError(exc)) {
                    recordDnsAffected(unit, position, exc);
                }
                attempt++;
                long delay = computeSubmissionNetworkRetryDelay(attempt);
                long seconds = Math.max(1L, delay / 1000L);
                String prefix = label == null || label.isEmpty() ? "" : label + " ";
                appendLog(prefix + t("network_retry_log_prefix") + "#" + attempt + " (" + seconds + "s) " + conciseError(exc));
                setSubmitProgressMessage(t("network_retrying_status") + " #" + attempt);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }

    private void recordDnsAffected(UnitRecord unit, int position) {
        recordDnsAffected(unit, position, null);
    }

    private void recordDnsAffected(UnitRecord unit, int position, Throwable throwable) {
        if (unit != null && unit.sn != null && !unit.sn.isEmpty()) {
            synchronized (dnsAffectedUnits) {
                if (!dnsAffectedUnits.containsKey(unit.sn)) {
                    dnsAffectedUnits.put(unit.sn, position);
                }
            }
        }
        if (throwable != null) {
            Map<String, String> ctx = FailureReporter.ctx(
                    "sn", unit == null || unit.sn == null ? "" : unit.sn,
                    "position", String.valueOf(position),
                    "dns_target", extractDnsTarget(throwable));
            FailureReporter.get().report("dns", "unknown_host", "api_request",
                    throwable.getMessage(), ctx, throwable);
        }
    }

    private void reportSubmitFailure(UnitRecord unit, int position, Throwable throwable) {
        if (throwable == null) return;
        String stage;
        if (Api.isDnsResolveError(throwable)) {
            stage = "dns";
        } else if (Api.isTransientApiNetworkError(throwable)) {
            stage = "network";
        } else {
            stage = "submit";
        }
        String errCode = throwable.getClass().getSimpleName();
        Map<String, String> ctx = FailureReporter.ctx(
                "sn", unit == null || unit.sn == null ? "" : unit.sn,
                "position", String.valueOf(position),
                "dns_target", extractDnsTarget(throwable));
        FailureReporter.get().report(stage, errCode, "submit_unit",
                throwable.getMessage(), ctx, throwable);
    }

    private static String extractDnsTarget(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            int s = message.indexOf('"');
            int e = s >= 0 ? message.indexOf('"', s + 1) : -1;
            if (s >= 0 && e > s) return message.substring(s + 1, e);
        }
        return "";
    }

    private String buildDnsAffectedMessage() {
        List<Map.Entry<String, Integer>> entries;
        synchronized (dnsAffectedUnits) {
            if (dnsAffectedUnits.isEmpty()) return "";
            entries = new ArrayList<>(dnsAffectedUnits.entrySet());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t("dns_warning_header"));
        for (Map.Entry<String, Integer> entry : entries) {
            sb.append("\n  ").append(formatUnitPosition(entry.getValue()))
              .append(" SN=").append(entry.getKey());
        }
        return sb.toString();
    }

    private String formatUnitPosition(int position) {
        if ("zh".equals(lang)) return "第" + position + "台";
        return "#" + position;
    }

    private long computeSubmissionNetworkRetryDelay(int attempt) {
        long delay = SUBMIT_NETWORK_RETRY_BASE_MS;
        for (int i = 1; i < attempt && delay < SUBMIT_NETWORK_RETRY_MAX_MS; i++) {
            delay *= 2;
        }
        return Math.min(delay, SUBMIT_NETWORK_RETRY_MAX_MS);
    }

    private void submitUnit(Api api, UnitRecord unit) throws Exception {
        ensurePreviousSteps(api, unit);
        appendUnitLog(unit, t("checking_duplicate"));
        JSONArray existing = checkDuplicate(api, unit.sn);
        if (existing.length() > 0) {
            DuplicateHistory history = duplicateHistory(existing);
            if (!isOlderThanOneMonth(history.latestMillis)) {
                appendUnitLog(unit, t("already_submitted") + duplicateHistoryLogSuffix(history));
                unit.status = "already_submitted";
                recordDailyOutput(unit);
                saveDraft();
                runOnUiThread(this::refreshFormUi);
                return;
            }
            String returnName = duplicateReturnName(existing.length() + 1);
            appendUnitLog(unit, returnName + " " + t("duplicate_found") + duplicateHistoryLogSuffix(history));
            notifyDuplicateReturn(unit.sn, returnName, history);
            appendUnitLog(unit, duplicateAutoContinueText());
        }

        String frontUrl = null;
        String backUrl = null;
        List<String> supplementalUrls = null;
        Map<String, List<String>> slotUrls = null;
        if (isSlotMode()) {
            slotUrls = uploadSlotPhotos(api, unit);
        } else {
            frontUrl = api.uploadImage(new File(unit.frontPhoto), unit.sn + "-front.jpg");
            backUrl = api.uploadImage(new File(unit.backPhoto), unit.sn + "-back.jpg");
            supplementalUrls = new ArrayList<>();
            for (int i = 0; i < unit.supplementalPhotos.size(); i++) {
                supplementalUrls.add(api.uploadImage(new File(unit.supplementalPhotos.get(i)), unit.sn + "-supplemental-" + (i + 1) + ".jpg"));
            }
        }
        Set<String> removed = new HashSet<>();
        Set<String> submittedMissingCandidates = new HashSet<>(cachedMissingMaterialCodes);
        submittedMissingCandidates.retainAll(materialCodeSet());
        JSONObject payload = buildPayload(unit, frontUrl, backUrl, supplementalUrls, removed, slotUrls);

        for (int attempt = 1; attempt <= 4; attempt++) {
            appendUnitLog(unit, t("submit_attempt") + "#" + attempt);
            JSONObject response = api.postJson(AppConfig.endpoint(this, "submitEntry", "/retreadOptimized/addProcessTemplateData"), payload);
            if (Api.isSuccess(response)) {
                unit.status = "success";
                recordDailyOutput(unit);
                appendUnitLog(unit, t("submitted"));
                removeResolvedSubmittedMissingMaterials(removed, submittedMissingCandidates);
                saveDraft();
                runOnUiThread(this::refreshFormUi);
                return;
            }
            String text = response.toString();
            if (text.contains("only once within 3 seconds")) {
                Thread.sleep(4000);
                continue;
            }
            List<String> missing = missingMaterials(text, removed);
            if (!missing.isEmpty()) {
                removed.addAll(missing);
                recordRoundMissing(unit.sn, missing);
                rememberMissingMaterials(missing);
                payload = buildPayload(unit, frontUrl, backUrl, supplementalUrls, removed, slotUrls);
                List<String> firstTime = firstTimeMissingMaterials(missing);
                if (!firstTime.isEmpty()) {
                    notifyMissing(unit.sn, firstTime);
                } else {
                    appendLog(t("missing_already_notified") + join(missing, ", "));
                }
                Thread.sleep(4000);
                continue;
            }
            throw new IOException(Api.apiErrorMessage(response));
        }
        throw new IOException(t("submit_retry_failed") + unit.sn);
    }

    private void ensurePreviousSteps(Api api, UnitRecord unit) throws Exception {
        appendUnitLog(unit, t("checking_steps"));
        JSONObject body = previousStepsResponse(api, unit);
        if (Api.isSuccess(body)) {
            alignSnCaseToPreviousSteps(unit, body);
            markPreviousStepsOk(unit);
            return;
        }
        if (tryCorrectSnFromPreviousSteps(api, unit)) {
            markPreviousStepsOk(unit);
            return;
        }
        if (canAutoCreatePreviousSteps(unit)) {
            if (!hasFile(unit.aStepPhotoPath)) {
                unit.precheckStatus = t("failed");
                saveDraft();
                throw new IOException(unit.sn + " " + t("a_step_photo_required"));
            }
            appendUnitLog(unit, t("a_steps_creating"));
            autoCreateGradeAPreviousSteps(api, unit);
            for (int attempt = 1; attempt <= 3; attempt++) {
                Thread.sleep(1500);
                body = previousStepsResponse(api, unit);
                if (Api.isSuccess(body)) {
                    markPreviousStepsOk(unit);
                    return;
                }
            }
        }
        unit.precheckStatus = t("failed");
        saveDraft();
        throw new IOException(unit.sn + " " + t("steps_missing_detail") + " " + Api.apiErrorMessage(body));
    }

    // 良品结单要求「包装(第4步)」与已有的「检测/维修」步骤 SN 逐字节一致——服务器按精确字符串(区分大小写)
    // 把工序链接起来。App 扫码统一 normalize 成大写,但若前置步骤是别处建的(网页端手工录入 autocapitalize→
    // 「Testtest…」、或旧版App没规范化)、大小写不同,大写的第4步就跟它们对不上→链断开→不结单→系统里按SN搜不到。
    // 这里在确认前置步骤存在后,把 unit.sn 对齐成前置步骤里真正存的那个写法,确保第4步能挂到同一条链上。
    private void alignSnCaseToPreviousSteps(UnitRecord unit, JSONObject body) {
        String stored = firstPreviousStepSn(body);
        if (stored == null || stored.isEmpty()) return;
        if (stored.equals(unit.sn)) return;
        if (!stored.equalsIgnoreCase(unit.sn)) return; // 只在「同一个SN、仅大小写不同」时对齐,绝不改成别的SN
        appendUnitLog(unit, unit.sn + " → " + stored + " (对齐前置步骤SN大小写)");
        unit.sn = stored;
    }

    private String firstPreviousStepSn(JSONObject body) {
        JSONObject data = body == null ? null : body.optJSONObject("data");
        JSONArray steps = data == null ? null : data.optJSONArray("retreadData");
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            JSONObject dl = step == null ? null : step.optJSONObject("data_list");
            String sn = dl == null ? "" : dl.optString("sn", "");
            if (!sn.isEmpty()) return sn;
        }
        return null;
    }

    private JSONObject previousStepsResponse(Api api, UnitRecord unit) throws Exception {
        return previousStepsResponse(api, unit, unit.sn);
    }

    private JSONObject previousStepsResponse(Api api, UnitRecord unit, String sn) throws Exception {
        return previousStepsResponse(api, unit, sn, false);
    }

    private JSONObject previousStepsResponse(Api api, UnitRecord unit, String sn, boolean fast) throws Exception {
        JSONObject template = profile.getJSONObject("template");
        String query = "template_id=" + template.getInt("id")
            + "&warehouse_id=" + template.getInt("warehouseId")
            + "&sku=" + enc(template.getString("sku"))
            + "&sn=" + enc(sn);
        if (fast) {
            return api.getJson(
                AppConfig.endpoint(this, "detectionData", "/retreadOptimized/detectionProcessTemplateData"),
                query,
                true,
                SCAN_PRECHECK_CONNECT_TIMEOUT_MS,
                SCAN_PRECHECK_READ_TIMEOUT_MS
            );
        }
        return api.getJson(AppConfig.endpoint(this, "detectionData", "/retreadOptimized/detectionProcessTemplateData"), query);
    }

    private boolean tryCorrectSnFromPreviousSteps(Api api, UnitRecord unit) throws Exception {
        if (!shouldTrySnCorrection(unit)) return false;
        String original = unit.sn == null ? "" : unit.sn;
        List<String> candidates = snCorrectionCandidates(original);
        if (candidates.isEmpty()) return false;
        appendUnitLog(unit, original + " " + t("sn_correction_try"));
        for (String candidate : candidates) {
            if (candidate.equals(original) || snExistsInOtherUnit(unit, candidate)) continue;
            JSONObject body = previousStepsResponse(api, unit, candidate);
            if (!Api.isSuccess(body)) continue;
            applySnCorrection(unit, original, candidate);
            return true;
        }
        return false;
    }

    private boolean tryCorrectScannedSnFromPreviousSteps(Api api, UnitRecord unit) throws Exception {
        if (!shouldTrySnCorrection(unit)) return false;
        String original = unit.sn == null ? "" : unit.sn;
        List<String> candidates = snCorrectionCandidates(original);
        if (candidates.isEmpty()) return false;
        appendUnitLog(unit, original + " " + t("sn_correction_try"));

        List<String> fastCandidates = new ArrayList<>();
        for (String candidate : candidates) {
            if (fastCandidates.size() >= MAX_SCAN_PRECHECK_CORRECTION_CANDIDATES) break;
            if (candidate.equals(original) || snExistsInOtherUnit(unit, candidate)) continue;
            fastCandidates.add(candidate);
        }
        if (fastCandidates.isEmpty()) return false;

        int threadCount = Math.min(SCAN_PRECHECK_CORRECTION_THREADS, fastCandidates.size());
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CompletionService<String> completion = new java.util.concurrent.ExecutorCompletionService<>(executor);
        int submitted = 0;
        for (String candidate : fastCandidates) {
            submitted++;
            completion.submit(() -> {
                JSONObject body = previousStepsResponse(api, unit, candidate, true);
                return Api.isSuccess(body) ? candidate : "";
            });
        }

        long deadline = System.currentTimeMillis() + SCAN_PRECHECK_CORRECTION_BUDGET_MS;
        try {
            for (int i = 0; i < submitted; i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                java.util.concurrent.Future<String> future = completion.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (future == null) break;
                String candidate;
                try {
                    candidate = future.get();
                } catch (Exception ignored) {
                    continue;
                }
                if (candidate == null || candidate.isEmpty()) continue;
                applySnCorrection(unit, original, candidate);
                return true;
            }
            appendUnitLog(unit, original + " " + t("sn_correction_fast_timeout"));
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    private void applySnCorrection(UnitRecord unit, String original, String candidate) {
        unit.sn = candidate;
        unit.stepPhotoRequired = false;
        unit.precheckStatus = "unchecked";
        unit.status = "pending";
        clearScanPrecheckMissingCount(original);
        clearScanPrecheckMissingCount(candidate);
        saveDraft();
        appendUnitLog(unit, original + " -> " + candidate + " " + t("sn_correction_applied"));
        runOnUiThread(this::refreshFormUi);
    }

    private boolean shouldTrySnCorrection(UnitRecord unit) {
        if (unit == null) return false;
        String grade = unit.grade == null ? "" : unit.grade.trim().toUpperCase(java.util.Locale.US);
        return "B".equals(grade) || "C".equals(grade);
    }

    private boolean isGradeAUnit(UnitRecord unit) {
        return unit != null && "A".equals(unit.grade == null ? "" : unit.grade.trim().toUpperCase(java.util.Locale.US));
    }

    private boolean snExistsInOtherUnit(UnitRecord unit, String sn) {
        for (UnitRecord item : units) {
            if (item != unit && sn.equals(item.sn)) return true;
        }
        return false;
    }

    private List<String> snCorrectionCandidates(String sn) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (sn == null || sn.isEmpty() || !hasAmbiguousSnChars(sn)) return new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < sn.length(); i++) {
            if (isAmbiguousSnChar(sn.charAt(i))) positions.add(i);
        }
        char[] chars = sn.toCharArray();
        for (int changes = 1; changes <= positions.size() && out.size() < MAX_SN_CORRECTION_CANDIDATES; changes++) {
            collectSnCorrectionCandidates(chars, positions, 0, changes, out, sn);
        }
        return new ArrayList<>(out);
    }

    private void collectSnCorrectionCandidates(char[] chars, List<Integer> positions, int start, int changesLeft, LinkedHashSet<String> out, String original) {
        if (out.size() >= MAX_SN_CORRECTION_CANDIDATES) return;
        if (changesLeft == 0) {
            String candidate = new String(chars);
            if (!candidate.equals(original)) out.add(candidate);
            return;
        }
        for (int i = start; i <= positions.size() - changesLeft && out.size() < MAX_SN_CORRECTION_CANDIDATES; i++) {
            int pos = positions.get(i);
            char old = chars[pos];
            chars[pos] = swappedAmbiguousSnChar(old);
            collectSnCorrectionCandidates(chars, positions, i + 1, changesLeft - 1, out, original);
            chars[pos] = old;
        }
    }

    private boolean hasAmbiguousSnChars(String value) {
        for (int i = 0; value != null && i < value.length(); i++) {
            if (isAmbiguousSnChar(value.charAt(i))) return true;
        }
        return false;
    }

    private boolean isAmbiguousSnChar(char value) {
        return value == 'O' || value == '0' || value == '1' || value == 'I';
    }

    private char swappedAmbiguousSnChar(char value) {
        if (value == 'O') return '0';
        if (value == '0') return 'O';
        if (value == '1') return 'I';
        if (value == 'I') return '1';
        return value;
    }

    private void markPreviousStepsOk(UnitRecord unit) {
        unit.stepPhotoRequired = false;
        unit.precheckStatus = t("ok");
        clearScanPrecheckMissingCount(unit.sn);
        appendUnitLog(unit, t("steps_ok_short"));
        saveDraft();
        runOnUiThread(this::refreshFormUi);
    }

    private void autoCreateGradeAPreviousSteps(Api api, UnitRecord unit) throws Exception {
        String photoUrl = api.uploadImage(new File(unit.aStepPhotoPath), unit.sn + "-a-step.jpg");
        JSONObject stepOne = aClassStepTemplate(api, 1);
        JSONObject stepTwo = aClassStepTemplate(api, 2);
        submitAutoStepPayload(api, buildAutoStepPayload(stepOne, unit, photoUrl, 1), unit.sn + " " + t("a_step_one"));
        Thread.sleep(4000);
        submitAutoStepPayload(api, buildAutoStepPayload(stepTwo, unit, "", 2), unit.sn + " " + t("a_step_two"));
        appendUnitLog(unit, t("a_steps_created"));
    }

    private JSONObject aClassStepTemplate(Api api, int processStep) throws Exception {
        if (processStep == 1 && cachedAClassStepOneTemplate != null) return cachedAClassStepOneTemplate;
        if (processStep == 2 && cachedAClassStepTwoTemplate != null) return cachedAClassStepTwoTemplate;

        JSONObject detail = findAClassStepTemplate(api, processStep);
        if (detail == null) {
            throw new IOException(t("a_step_template_missing") + processStep);
        }
        if (processStep == 1) cachedAClassStepOneTemplate = detail;
        if (processStep == 2) cachedAClassStepTwoTemplate = detail;
        return detail;
    }

    private JSONObject findAClassStepTemplate(Api api, int processStep) throws Exception {
        List<JSONObject> candidates = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        addKnownAClassTemplateCandidate(candidates, seen, processStep);
        addNearbyAClassTemplateCandidates(candidates, seen, processStep);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String keyword : aClassTemplateKeywords()) {
            if (keyword.isEmpty()) continue;
            JSONObject body = api.getJson(AppConfig.endpoint(this, "templateList", "/retread/myTemplateListPage"), "page=1&pageSize=100&keyword=" + enc(keyword) + "&status=1");
            if (!Api.isSuccess(body)) continue;
            JSONArray items = arrayFromApiData(Api.apiData(body));
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                int id = optIntAny(item, "id", "template_id", "templateId");
                if (id <= 0 || seen.contains(id)) continue;
                seen.add(id);
                int listedStep = optIntAny(item, "process_id", "processId", "step");
                if (listedStep > 0 && listedStep != processStep) continue;
                candidates.add(item);
            }
        }

        for (JSONObject candidate : candidates) {
            int id = optIntAny(candidate, "id", "template_id", "templateId");
            JSONObject detail = loadTemplateDetail(api, id);
            if (detail == null) continue;
            int score = templateCandidateScore(candidate, detail, processStep);
            Diagnostics.append(this, "A class step template candidate step=" + processStep
                + " id=" + id
                + " process=" + optIntAny(detail, "process_id", "processId", "step")
                + " score=" + score);
            if (score < 80) continue;
            if (score > bestScore) {
                best = detail;
                bestScore = score;
            }
            if (bestScore >= 220) break;
        }
        return best;
    }

    private void addKnownAClassTemplateCandidate(List<JSONObject> candidates, Set<Integer> seen, int processStep) {
        JSONObject current = profile == null ? null : profile.optJSONObject("template");
        int currentId = current == null ? 0 : optIntAny(current, "id", "template_id", "templateId");
        int id = knownAClassStepTemplateId(currentId, processStep);
        if (id <= 0 || seen.contains(id)) return;
        try {
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("_known", true);
            candidates.add(item);
            seen.add(id);
        } catch (JSONException ignored) {
        }
    }

    private int knownAClassStepTemplateId(int currentTemplateId, int processStep) {
        // Config-driven module: a profile may carry its own previous-step template ids.
        JSONObject cfg = autoPreviousStepsConfig();
        if (cfg != null) {
            int configured = processStep == 1 ? cfg.optInt("step1TemplateId", 0)
                : (processStep == 2 ? cfg.optInt("step2TemplateId", 0) : 0);
            if (configured > 0) return configured;
        }
        return 0;
    }

    private void addNearbyAClassTemplateCandidates(List<JSONObject> candidates, Set<Integer> seen, int processStep) {
        JSONObject current = profile == null ? null : profile.optJSONObject("template");
        int currentId = current == null ? 0 : optIntAny(current, "id", "template_id", "templateId");
        int currentStep = current == null ? 0 : optIntAny(current, "process_id", "processId", "step");
        if (currentId <= 0) return;
        if (currentStep <= processStep) currentStep = 4;
        addAClassTemplateCandidate(candidates, seen, currentId - (currentStep - processStep));
        addAClassTemplateCandidate(candidates, seen, currentId - (4 - processStep));
    }

    private void addAClassTemplateCandidate(List<JSONObject> candidates, Set<Integer> seen, int id) {
        if (id <= 0 || seen.contains(id)) return;
        try {
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("_nearby", true);
            candidates.add(item);
            seen.add(id);
        } catch (JSONException ignored) {
        }
    }

    private JSONObject loadTemplateDetail(Api api, int id) throws Exception {
        if (id <= 0) return null;
        JSONObject body = api.getJson(AppConfig.endpoint(this, "templateDetail", "/retread/templateDetail"), "id=" + id);
        JSONObject detail = Api.apiDataObject(body);
        if (!Api.isSuccess(body) || detail == null) {
            Diagnostics.append(this, "A class step template detail failed id=" + id + " error=" + Api.apiErrorMessage(body));
            return null;
        }
        return detail;
    }

    private List<String> aClassTemplateKeywords() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        JSONObject template = profile == null ? null : profile.optJSONObject("template");
        if (template != null) values.add(template.optString("sku", ""));
        if (profile != null) {
            values.add(profile.optString("searchText", ""));
            values.add(profile.optString("model", ""));
            values.add(profile.optString("displayName", ""));
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty()) out.add(text);
        }
        return out;
    }

    private int templateCandidateScore(JSONObject item, JSONObject detail, int processStep) {
        int score = 0;
        JSONObject current = profile == null ? null : profile.optJSONObject("template");
        String currentSku = current == null ? "" : current.optString("sku", "");
        int currentWarehouse = current == null ? 0 : current.optInt("warehouseId", 0);
        String sku = Api.firstNonEmpty(detail.optString("sku", ""), item.optString("sku", ""));
        if (!currentSku.isEmpty() && currentSku.equals(sku)) score += 120;
        int warehouse = optIntAny(detail, "warehouse_id", "warehouseId");
        if (warehouse <= 0) warehouse = optIntAny(item, "warehouse_id", "warehouseId");
        if (currentWarehouse > 0 && currentWarehouse == warehouse) score += 10;
        int step = optIntAny(detail, "process_id", "processId", "step");
        if (step > 0 && step != processStep) return -1000;
        if (step == processStep) score += 100;
        if (item.optBoolean("_known", false)) score += 300;
        if (item.optBoolean("_nearby", false)) score += 20;
        JSONArray fields = templateFields(detail);
        if (processStep == 1 && hasUploadTemplateField(fields)) score += 15;
        if (processStep == 1 && hasOptionTemplateField(fields)) score += 10;
        if (processStep == 2 && !hasUploadTemplateField(fields)) score += 10;
        if (processStep == 2 && fields.length() <= 3) score += 10;
        if (hasMainSubmitTemplateField(fields)) score -= 120;
        return score;
    }

    private boolean hasUploadTemplateField(JSONArray fields) {
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field != null && isUploadField(field)) return true;
        }
        return false;
    }

    private boolean hasOptionTemplateField(JSONArray fields) {
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null) continue;
            JSONArray options = field.optJSONArray("option_list");
            if (options == null || options.length() == 0) options = field.optJSONArray("optionList");
            if (options != null && options.length() > 0) return true;
        }
        return false;
    }

    private boolean hasMainSubmitTemplateField(JSONArray fields) {
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null) continue;
            String type = field.optString("type", "");
            String typeName = field.optString("type_name", "");
            if ("retread_result".equals(type) || "part".equals(type) || "parts".equals(typeName)) return true;
        }
        return false;
    }

    private JSONObject buildAutoStepPayload(JSONObject template, UnitRecord unit, String imageUrl, int processStep) throws JSONException {
        JSONObject data = new JSONObject();
        JSONArray fields = templateFields(template);
        boolean hasSn = false;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null) continue;
            String name = field.optString("field", "");
            if (name.isEmpty()) continue;
            if (isPrimarySnField(field)) {
                data.put(name, unit.sn);
                hasSn = true;
                continue;
            }
            if (processStep == 1 && isUploadField(field)) {
                // The A-step photo only belongs in the REQUIRED machine battery/damaged-parts upload
                // field (机器电量破损部件照片). Any other upload field — notably the optional defective-
                // product photo (不良品照片) — must be left empty instead of getting the same image.
                if (!imageUrl.isEmpty() && !isOptionalDefectivePhotoField(field)) {
                    data.put(name, imageUrl);
                }
                continue;
            }
            if (processStep == 1) {
                if (shouldSkipAutoStepOneField(field)) continue;
                Object value = autoStepOneFieldValue(field);
                if (value != null) data.put(name, value);
            }
        }
        if (!hasSn) data.put("sn", unit.sn);

        JSONObject payload = new JSONObject();
        payload.put("template_id", optIntAny(template, "id", "template_id", "templateId"));
        payload.put("warehouse_id", optIntAny(template, "warehouse_id", "warehouseId"));
        payload.put("sku", template.optString("sku", profile.getJSONObject("template").optString("sku", "")));
        payload.put("data", data);
        payload.put("video_data_id", "");
        return payload;
    }

    private JSONArray templateFields(JSONObject template) {
        JSONArray fields = template.optJSONArray("field_list");
        if (fields == null) fields = template.optJSONArray("fieldList");
        return fields == null ? new JSONArray() : fields;
    }

    private boolean isPrimarySnField(JSONObject field) {
        String name = field.optString("field", "");
        String type = field.optString("type", "");
        String text = fieldText(field);
        if (text.contains("base station") || text.contains("\u57fa\u7ad9")) return false;
        return "sn".equals(name) || "sn".equals(type) || text.contains("robot sn") || text.contains("\u673a\u5668 sn");
    }

    private boolean isUploadField(JSONObject field) {
        return "upload".equals(field.optString("type", "")) || "upload".equals(field.optString("type_name", ""));
    }

    // The optional 不良品照片 (defective-product photo) is its own upload field and must stay empty;
    // only the required 机器电量破损部件照片 (battery/damaged-parts photo) receives the A-step image.
    private boolean isOptionalDefectivePhotoField(JSONObject field) {
        String text = fieldText(field);
        return text.contains("不良品") // 不良品
            || text.contains("defective")
            || text.contains("reject");
    }

    private boolean shouldSkipAutoStepOneField(JSONObject field) {
        String text = fieldText(field);
        return text.contains("\u4e0d\u901a\u8fc7\u539f\u56e0")
            || text.contains("\u4e0d\u901a\u8fc7\u63cf\u8ff0")
            || text.contains("fail reason")
            || text.contains("failure reason")
            || text.contains("defect reason");
    }

    private Object autoStepOneFieldValue(JSONObject field) throws JSONException {
        JSONArray options = field.optJSONArray("option_list");
        if (options == null || options.length() == 0) options = field.optJSONArray("optionList");
        if (options == null || options.length() == 0) return null;
        JSONObject option = chooseAutoStepOneOption(field, options);
        if (option == null) return null;
        Object value = optionValueForSubmit(field, option);
        if ("checkbox".equals(field.optString("type", ""))) {
            JSONArray array = new JSONArray();
            array.put(value);
            return array;
        }
        return value;
    }

    private JSONObject chooseAutoStepOneOption(JSONObject field, JSONArray options) {
        String text = fieldText(field);
        JSONObject option = null;
        if (text.contains("\u7ef4\u4fee") || text.contains("repair")) {
            option = findOption(options, "repairable");
        } else if (text.contains("\u64cd\u4f5c") || text.contains("\u5185\u5bb9") || text.contains("operation") || text.contains("action") || text.contains("item")) {
            option = findOption(options, "detect");
        } else if (text.contains("\u7ed3\u679c") || text.contains("\u68c0") || text.contains("result") || text.contains("inspect") || text.contains("test")) {
            option = findOption(options, "pass");
        }
        if (option == null) option = findOption(options, "pass");
        if (option == null) option = findOption(options, "repairable");
        if (option == null) option = findOption(options, "detect");
        return option;
    }

    private JSONObject findOption(JSONArray options, String kind) {
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && optionMatches(option, kind)) return option;
        }
        return null;
    }

    private boolean optionMatches(JSONObject option, String kind) {
        String text = (option.optString("name", "") + " "
            + option.optString("en_name", "") + " "
            + option.optString("label", "") + " "
            + option.optString("value", "")).toLowerCase(java.util.Locale.US);
        if ("pass".equals(kind)) {
            return text.contains("\u901a\u8fc7") || text.contains("pass") || text.contains("qualified") || text.contains("ok");
        }
        if ("repairable".equals(kind)) {
            return (text.contains("\u53ef\u7ef4\u4fee") || text.contains("repairable") || text.contains("repair"))
                && !text.contains("\u4e0d\u53ef") && !text.contains("not repair");
        }
        if ("detect".equals(kind)) {
            return text.contains("\u68c0\u6d4b") || text.contains("\u68c0\u67e5") || text.contains("detect") || text.contains("inspect") || text.contains("test");
        }
        return false;
    }

    private Object optionValueForSubmit(JSONObject field, JSONObject option) throws JSONException {
        Object raw = option.has("value") ? option.get("value") : option.optString("name", "");
        String type = field.optString("type", "");
        if ("retread_result".equals(type) || fieldText(field).contains("retread")) {
            JSONObject value = new JSONObject();
            String name = Api.firstNonEmpty(option.optString("name", ""), option.optString("en_name", ""), String.valueOf(raw));
            // 不良品/报废 结果没有真实 SKU → sku 置空（与真实报废单一致）；好成色档(九五/九成/五成)保留其 SKU。
            String probe = (name + " " + String.valueOf(raw)).toLowerCase(java.util.Locale.US);
            boolean defective = probe.contains("不良") || probe.contains("报废") || probe.contains("defective") || probe.contains("scrap") || probe.contains("reject");
            value.put("sku", defective ? "" : raw);
            value.put("name", name);
            value.put("num", option.optInt("num", 1));
            return value;
        }
        return raw;
    }

    private void submitAutoStepPayload(Api api, JSONObject payload, String label) throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            JSONObject response = api.postJson(AppConfig.endpoint(this, "submitEntry", "/retreadOptimized/addProcessTemplateData"), payload);
            if (Api.isSuccess(response) || isAlreadyCreatedResponse(response)) {
                appendLog(label + " " + t("submitted"));
                return;
            }
            String text = response.toString();
            if (text.contains("only once within 3 seconds")) {
                Thread.sleep(4000);
                continue;
            }
            throw new IOException(label + " " + Api.apiErrorMessage(response));
        }
        throw new IOException(label + " " + t("submit_retry_failed"));
    }

    private boolean isAlreadyCreatedResponse(JSONObject response) {
        String text = response == null ? "" : response.toString();
        return text.contains("\u91cd\u590d") || text.contains("\u5df2\u5b58\u5728") || text.toLowerCase(java.util.Locale.US).contains("already");
    }

    private String fieldText(JSONObject field) {
        return (field.optString("title", "") + " "
            + field.optString("en_title", "") + " "
            + field.optString("name", "") + " "
            + field.optString("field", "") + " "
            + field.optString("type", "")).toLowerCase(java.util.Locale.US);
    }

    private JSONArray arrayFromApiData(Object data) {
        if (data instanceof JSONArray) return (JSONArray) data;
        if (data instanceof JSONObject) {
            JSONObject object = (JSONObject) data;
            for (String key : new String[]{"list", "records", "items", "data"}) {
                JSONArray array = object.optJSONArray(key);
                if (array != null) return array;
            }
        }
        return new JSONArray();
    }

    private int optIntAny(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) {
                try {
                    return Integer.parseInt(((String) value).trim());
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private JSONArray checkDuplicate(Api api, String sn) throws Exception {
        JSONObject body = api.getJson(AppConfig.endpoint(this, "snRepetition", "/retread/getTemplateSnRepetitionList"), "template_id=" + templateId() + "&sn=" + enc(sn));
        if (!Api.isSuccess(body)) {
            throw new IOException(t("duplicate_check_failed") + Api.apiErrorMessage(body));
        }
        Object data = body.opt("data");
        if (data instanceof JSONArray) return (JSONArray) data;
        JSONArray array = new JSONArray();
        if (data != null && data != JSONObject.NULL) array.put(data);
        return array;
    }

    private void notifyDuplicateReturn(String sn, String returnName, DuplicateHistory history) {
        StringBuilder message = new StringBuilder();
        message.append(sn).append(" ").append(returnName).append(" ").append(duplicateAutoContinueText());
        if (!history.latestText.isEmpty()) {
            message.append(" ").append(t("duplicate_return_last_date")).append(history.latestText);
        }
        runOnUiThread(() -> toastLong(message.toString()));
    }

    private String duplicateAutoContinueText() {
        if ("en".equals(lang)) return "notified; continuing submit automatically.";
        if ("es".equals(lang)) return "notificado; continua el envio automaticamente.";
        return "\u5df2\u63d0\u793a\uff0c\u81ea\u52a8\u7ee7\u7eed\u63d0\u4ea4\u3002";
    }

    private String duplicateReturnName(int submitNumber) {
        // submitNumber = how many times this SN has come through the refurb line,
        // counting this pass. Chinese reads naturally with native numerals (二反/三反…).
        // en/es spell it out as an ordinal "refurb return" so it can't be misread as a
        // queue index — the old "Return #2" / "Retorno #2" was ambiguous.
        if ("zh".equals(lang)) {
            switch (submitNumber) {
                case 2: return "二反";
                case 3: return "三反";
                case 4: return "四反";
                case 5: return "五反";
                case 6: return "六反";
                case 7: return "七反";
                case 8: return "八反";
                case 9: return "九反";
                case 10: return "十反";
                default: return "第" + submitNumber + "反";
            }
        }
        if ("es".equals(lang)) {
            return submitNumber + ".º retorno de reacondicionado";
        }
        return ordinalEn(submitNumber) + " refurb return";
    }

    private String ordinalEn(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return n + "th";
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    private DuplicateHistory duplicateHistory(JSONArray existing) {
        List<String> dates = new ArrayList<>();
        for (int i = 0; i < existing.length(); i++) {
            Object item = existing.opt(i);
            if (item instanceof JSONObject) {
                collectDateCandidates((JSONObject) item, dates, 0);
            } else if (isDateLikeValue(item)) {
                String formatted = duplicateDateText(item);
                if (!formatted.isEmpty()) dates.add(formatted);
            }
        }
        String latestText = "";
        long latestMillis = Long.MIN_VALUE;
        for (String date : dates) {
            long millis = parseDateMillis(date);
            if (millis > latestMillis) {
                latestMillis = millis;
                latestText = date;
            } else if (latestText.isEmpty()) {
                latestText = date;
            }
        }
        if (latestMillis == Long.MIN_VALUE) {
            Diagnostics.append(this, "Duplicate history date not parsed. records=" + existing.toString());
        }
        return new DuplicateHistory(latestText, latestMillis);
    }

    private boolean isOlderThanOneMonth(long millis) {
        if (millis == Long.MIN_VALUE) return false;
        java.util.Calendar threshold = java.util.Calendar.getInstance();
        threshold.add(java.util.Calendar.MONTH, -1);
        return millis <= threshold.getTimeInMillis();
    }

    private String duplicateHistoryLogSuffix(DuplicateHistory history) {
        if (history.latestText.isEmpty()) return "";
        return " " + t("duplicate_return_last_date") + history.latestText;
    }

    private void collectDateCandidates(JSONObject object, List<String> dates, int depth) {
        if (object == null || depth > 3) return;
        JSONArray names = object.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.optString(i);
            Object value = object.opt(key);
            if (isDateFieldName(key) || isDateLikeValue(value)) {
                String formatted = duplicateDateText(value);
                if (!formatted.isEmpty()) dates.add(formatted);
            }
            if (value instanceof JSONObject) {
                collectDateCandidates((JSONObject) value, dates, depth + 1);
            } else if (value instanceof JSONArray && depth < 3) {
                JSONArray array = (JSONArray) value;
                for (int j = 0; j < array.length(); j++) {
                    Object child = array.opt(j);
                    if (child instanceof JSONObject) {
                        collectDateCandidates((JSONObject) child, dates, depth + 1);
                    } else if (isDateLikeValue(child)) {
                        String formatted = duplicateDateText(child);
                        if (!formatted.isEmpty()) dates.add(formatted);
                    }
                }
            }
        }
    }

    private boolean isDateFieldName(String key) {
        String lower = key == null ? "" : key.toLowerCase(java.util.Locale.US);
        return lower.contains("time")
            || lower.contains("date")
            || lower.contains("created")
            || lower.contains("updated")
            || lower.contains("create")
            || lower.contains("submit")
            || lower.contains("时间")
            || lower.contains("日期");
    }

    private boolean isDateLikeValue(Object value) {
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof Number) return timestampMillis((Number) value) != Long.MIN_VALUE;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return false;
        if (text.matches("\\d{10}|\\d{13}")) return timestampMillis(text) != Long.MIN_VALUE;
        if (text.matches(".*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")) return true;
        if (text.matches(".*\\d{4}年\\d{1,2}月\\d{1,2}日.*")) return true;
        return text.matches(".*\\d{4}-\\d{1,2}-\\d{1,2}T.*");
    }

    private String duplicateDateText(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof Number) {
            long millis = timestampMillis((Number) value);
            return millis == Long.MIN_VALUE ? "" : formatDateMillis(millis);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return "";
        long millis = timestampMillis(text);
        if (millis != Long.MIN_VALUE) return formatDateMillis(millis);
        if (text.matches(".*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")
            || text.matches(".*\\d{4}年\\d{1,2}月\\d{1,2}日.*")
            || text.matches(".*\\d{4}-\\d{1,2}-\\d{1,2}T.*")) {
            return text;
        }
        return "";
    }

    private long parseDateMillis(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return Long.MIN_VALUE;
        long timestamp = timestampMillis(value);
        if (timestamp != Long.MIN_VALUE) return timestamp;
        String normalized = value
            .replace('T', ' ')
            .replace("年", "-")
            .replace("月", "-")
            .replace("日", " ");
        int dot = normalized.indexOf('.');
        if (dot > 0) normalized = normalized.substring(0, dot);
        if (normalized.endsWith("Z")) normalized = normalized.substring(0, normalized.length() - 1).trim();
        if (normalized.length() > 19 && normalized.charAt(10) == ' ') normalized = normalized.substring(0, 19);
        normalized = normalized.trim();
        String[] patterns = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd"
        };
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                format.setLenient(false);
                java.util.Date parsed = format.parse(normalized);
                if (parsed != null) return parsed.getTime();
            } catch (java.text.ParseException ignored) {
            }
        }
        return Long.MIN_VALUE;
    }

    private long timestampMillis(Number value) {
        return value == null ? Long.MIN_VALUE : timestampMillis(String.valueOf(value.longValue()));
    }

    private long timestampMillis(String value) {
        try {
            String text = value == null ? "" : value.trim();
            if (!text.matches("\\d{10}|\\d{13}")) return Long.MIN_VALUE;
            long raw = Long.parseLong(text);
            long millis = text.length() == 10 ? raw * 1000L : raw;
            java.util.Calendar min = java.util.Calendar.getInstance();
            min.set(2000, java.util.Calendar.JANUARY, 1, 0, 0, 0);
            min.set(java.util.Calendar.MILLISECOND, 0);
            java.util.Calendar max = java.util.Calendar.getInstance();
            max.add(java.util.Calendar.YEAR, 1);
            return millis >= min.getTimeInMillis() && millis <= max.getTimeInMillis() ? millis : Long.MIN_VALUE;
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }

    private String formatDateMillis(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(millis));
    }

    private JSONObject buildPayload(UnitRecord unit, String frontUrl, String backUrl, List<String> supplementalUrls, Set<String> removedMaterials, Map<String, List<String>> slotUrls) throws JSONException {
        JSONObject data = new JSONObject();
        JSONObject snFields = profile.getJSONObject("snFields");
        data.put(snFields.optString("primary", "sn"), unit.sn);
        if (requiresSecondSn()) {
            String secondary = snFields.optString("secondary", "");
            if (!secondary.isEmpty()) data.put(secondary, unit.baseSn);
        }
        // snPlugins extras (彩盒SN/装箱号/…): send each captured value under its real field id. Empty map
        // for legacy profiles, so the payload is byte-for-byte unchanged there.
        for (Map.Entry<String, String> e : unit.pluginSns.entrySet()) {
            if (e.getKey() != null && !e.getKey().isEmpty()) data.put(e.getKey(), e.getValue());
        }

        // Grade is optional: a profile without a (non-empty) gradeMap — e.g. a model that isn't
        // A/B/C graded — simply omits the grade field instead of crashing. Graded profiles behave
        // exactly as before.
        JSONObject gradeMap = profile.optJSONObject("gradeMap");
        if (gradeMap != null && gradeMap.length() > 0) {
            String gradeKey = gradeMap.has(unit.grade) ? unit.grade : firstGradeKey();
            JSONObject grade = gradeMap.optJSONObject(gradeKey);
            if (grade != null) {
                data.put(grade.getString("field"), grade.get("value"));
            }
        }

        JSONArray slotDefs = photoSlots();
        if (slotDefs != null) {
            // Slot mode: each box maps straight to its backend field; join its uploaded URLs.
            for (int i = 0; i < slotDefs.length(); i++) {
                String field = slotDefs.getJSONObject(i).getString("field");
                List<String> urls = slotUrls == null ? null : slotUrls.get(field);
                data.put(field, urls == null ? "" : join(urls, ","));
            }
        } else {
            JSONArray uploadFields = profile.optJSONArray("uploadFields");
            for (int i = 0; uploadFields != null && i < uploadFields.length(); i++) {
                JSONObject field = uploadFields.getJSONObject(i);
                data.put(field.getString("field"), uploadValueForField(field, i, frontUrl, backUrl, supplementalUrls, uploadFields.length()));
            }
        }

        JSONArray conditional = profile.optJSONArray("conditionalFields");
        for (int i = 0; conditional != null && i < conditional.length(); i++) {
            JSONObject field = conditional.getJSONObject(i);
            Object value = conditionalFieldValue(unit, field);
            if (value != null) data.put(field.getString("field"), value);
        }

        JSONArray operation = profile.optJSONArray("operationFields");
        for (int i = 0; operation != null && i < operation.length(); i++) {
            JSONObject field = operation.getJSONObject(i);
            data.put(field.getString("field"), field.get("value"));
        }

        // choiceFields: every other radio/checkbox the template carries. The panel fixes each field's
        // submitted value (String for 单选, JSONArray for 多选); the app echoes it verbatim — same shape
        // as operationFields, so org.json puts the right JSON type. This stops the app silently dropping
        // radio/checkbox fields the old code never modeled.
        JSONArray choices = profile.optJSONArray("choiceFields");
        for (int i = 0; choices != null && i < choices.length(); i++) {
            JSONObject field = choices.optJSONObject(i);
            if (field == null) continue;
            // Hidden (visible:false) choiceFields are conditional — only collected in a triggered branch the
            // app doesn't model here; echoing their value would poison the non-triggered branch, so skip.
            if (!field.optBoolean("visible", true)) continue;
            // optString (not getString): a single malformed field must never throw and fail the whole unit.
            String fid = field.optString("field", "");
            if (!fid.isEmpty() && field.has("value")) data.put(fid, field.get("value"));
        }

        // `materials` is the SELECTED subset the app submits (the full picker list lives in `allMaterials`,
        // display-only). 不良品表单 不换料 = materials:[] → submits nothing; good forms have materials=全部.
        JSONArray groups = profile.optJSONArray("materialGroups");
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            JSONArray materialPayload = new JSONArray();
            JSONArray materials = group.optJSONArray("materials");
            for (int j = 0; materials != null && j < materials.length(); j++) {
                JSONObject material = materials.getJSONObject(j);
                String code = material.optString("code");
                if (removedMaterials.contains(code)) continue;
                JSONObject item = new JSONObject();
                item.put("sku", code);
                item.put("name", material.optString("name", code));
                item.put("num", material.optInt("defaultQty", 1));
                materialPayload.put(item);
            }
            data.put(group.getString("field"), materialPayload);
        }

        JSONObject payload = new JSONObject();
        JSONObject template = profile.getJSONObject("template");
        payload.put("template_id", template.getInt("id"));
        payload.put("warehouse_id", template.getInt("warehouseId"));
        payload.put("sku", template.getString("sku"));
        payload.put("data", data);
        payload.put("video_data_id", "");
        return payload;
    }

    /** Slot mode: upload each box's photos and return fieldId -> uploaded URLs (insertion order). */
    private Map<String, List<String>> uploadSlotPhotos(Api api, UnitRecord unit) throws Exception {
        Map<String, List<String>> slotUrls = new LinkedHashMap<>();
        JSONArray slots = photoSlots();
        for (int s = 0; slots != null && s < slots.length(); s++) {
            String field = slots.getJSONObject(s).getString("field");
            List<String> photos = unit.slotPhotos.get(field);
            List<String> urls = new ArrayList<>();
            for (int p = 0; photos != null && p < photos.size(); p++) {
                urls.add(api.uploadImage(new File(photos.get(p)), unit.sn + "-" + (s + 1) + "-" + (p + 1) + ".jpg"));
            }
            slotUrls.put(field, urls);
        }
        return slotUrls;
    }

    /** Placeholder slot URLs for the payload preview (one per captured photo, at least one per slot). */
    private Map<String, List<String>> slotPlaceholders(UnitRecord unit) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        JSONArray slots = photoSlots();
        for (int s = 0; slots != null && s < slots.length(); s++) {
            String field = slots.optJSONObject(s).optString("field");
            List<String> photos = unit.slotPhotos.get(field);
            int count = photos == null ? 0 : photos.size();
            List<String> urls = new ArrayList<>();
            for (int p = 0; p < Math.max(count, 1); p++) urls.add("slot-url-" + (p + 1));
            map.put(field, urls);
        }
        return map;
    }

    private Object conditionalFieldValue(UnitRecord unit, JSONObject field) throws JSONException {
        // Profile-driven per-grade values (panel-configurable, works for ANY template). New profiles carry
        // perGrade={A:[...],B:[...],C:[...]}: submit exactly this grade's list. Old profiles fall through to
        // the legacy grade-A filter below.
        JSONObject perGrade = field.optJSONObject("perGrade");
        if (perGrade != null && unit != null && unit.grade != null && perGrade.has(unit.grade)) {
            return perGrade.opt(unit.grade);
        }
        Object value = field.opt("value");
        if (!isGradeASpecialUnit(unit) || !isNonNewReasonField(field)) return value;
        JSONArray source = value instanceof JSONArray ? (JSONArray) value : null;
        if (source == null) return value;
        JSONArray filtered = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            String item = source.optString(i, "");
            if (isAllowedGradeANonNewReason(item)) filtered.put(item);
        }
        return filtered;
    }

    private boolean isNonNewReasonField(JSONObject field) {
        String text = (field.optString("title", "") + " "
            + field.optString("en_title", "") + " "
            + field.optString("field", "")).toLowerCase(java.util.Locale.US);
        return text.contains("\u975e\u65b0\u673a\u539f\u56e0") || text.contains("non-new");
    }

    private boolean isAllowedGradeANonNewReason(String value) {
        return value != null && (value.contains("\u914d\u4ef6\u6709\u4f7f\u7528\u75d5\u8ff9")
            || value.contains("\u5305\u6750\u6709\u4f7f\u7528\u75d5\u8ff9"));
    }

    private String uploadValueForField(JSONObject field, int index, String frontUrl, String backUrl, List<String> supplementalUrls, int fieldCount) {
        List<String> urls = new ArrayList<>();
        JSONArray sources = field.optJSONArray("sources");
        if (sources != null && sources.length() > 0) {
            for (int i = 0; i < sources.length(); i++) {
                String source = sources.optString(i);
                if ("front".equals(source)) urls.add(frontUrl);
                if ("back".equals(source)) urls.add(backUrl);
            }
        } else if (fieldCount >= 2) {
            urls.add(index == 0 ? frontUrl : backUrl);
        } else {
            urls.add(frontUrl);
            urls.add(backUrl);
        }
        if (index == 1 && supplementalUrls != null) {
            urls.addAll(supplementalUrls);
        }
        return join(urls, ",");
    }

    private void refreshProfileMaterials(Api api) throws Exception {
        appendLog(t("materials_refreshing"));
        JSONObject body = api.getJson(AppConfig.endpoint(this, "templateDetail", "/retread/templateDetail"), "id=" + templateId());
        JSONObject data = Api.apiDataObject(body);
        if (!Api.isSuccess(body) || data == null) {
            throw new IOException(t("materials_refresh_failed") + Api.apiErrorMessage(body));
        }
        JSONArray fields = data.optJSONArray("field_list");
        JSONArray groups = new JSONArray();
        int materialCount = 0;
        for (int i = 0; fields != null && i < fields.length(); i++) {
            JSONObject field = fields.getJSONObject(i);
            if (!isMaterialField(field)) continue;
            JSONObject group = new JSONObject();
            group.put("field", field.optString("field"));
            group.put("title", Api.firstNonEmpty(field.optString("title"), field.optString("en_title"), field.optString("field")));
            group.put("selectAll", true);
            JSONArray materials = new JSONArray();
            JSONArray options = field.optJSONArray("option_list");
            for (int j = 0; options != null && j < options.length(); j++) {
                JSONObject option = options.getJSONObject(j);
                String code = option.optString("value");
                if (code.isEmpty()) continue;
                JSONObject material = new JSONObject();
                material.put("code", code);
                material.put("name", Api.firstNonEmpty(option.optString("name"), option.optString("en_name"), code));
                JSONArray aliases = new JSONArray();
                if (!option.optString("name").isEmpty()) aliases.put(option.optString("name"));
                if (!option.optString("en_name").isEmpty()) aliases.put(option.optString("en_name"));
                material.put("aliases", aliases);
                material.put("defaultQty", defaultMaterialQty(option));
                materials.put(material);
                materialCount++;
            }
            group.put("materials", materials);
            groups.put(group);
        }
        profile.put("materialGroups", groups);
        appendLog(t("materials_refreshed") + materialCount);
        refreshMissingMaterialsUi();
    }

    private boolean isMaterialField(JSONObject field) {
        return "part".equals(field.optString("type"))
            || "part".equals(field.optString("parent_type"))
            || "parts".equals(field.optString("type_name"));
    }

    private int defaultMaterialQty(JSONObject option) {
        String name = headBeforePipe(option.optString("name"));
        String enName = headBeforePipe(option.optString("en_name")).toLowerCase();
        String text = (name + " " + enName).toLowerCase();
        if (name.contains("\u6eda\u7b52") || name.contains("\u62b9\u5e03") || Pattern.compile("\\b(rolling|roller)\\s+mop\\b", Pattern.CASE_INSENSITIVE).matcher(enName).find()) {
            return 1;
        }
        if (name.contains("\u62d6\u5e03\u652f\u67b6") || name.endsWith("\u62d6\u5e03") || Pattern.compile("\\bmop\\s*(pad|bracket|holder|support)?\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
            return 2;
        }
        return 1;
    }

    private String headBeforePipe(String value) {
        String text = value == null ? "" : value.trim();
        int pipe = text.indexOf('|');
        return pipe >= 0 ? text.substring(0, pipe).trim() : text;
    }

    private List<String> supplementalPlaceholders(UnitRecord unit) {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < unit.supplementalPhotos.size(); i++) {
            urls.add("supplemental-url-" + (i + 1));
        }
        return urls;
    }

    private Set<String> materialCodeSet() {
        Set<String> known = new HashSet<>();
        JSONArray groups = profile.optJSONArray("materialGroups");
        try {
            for (int i = 0; groups != null && i < groups.length(); i++) {
                JSONArray materials = groups.getJSONObject(i).optJSONArray("materials");
                for (int j = 0; materials != null && j < materials.length(); j++) {
                    String code = materials.getJSONObject(j).optString("code");
                    if (!code.isEmpty()) known.add(code);
                }
            }
        } catch (JSONException ignored) {
        }
        return known;
    }

    private List<String> missingMaterials(String text, Set<String> alreadyRemoved) throws JSONException {
        Set<String> known = materialCodeSet();
        Set<String> skip = notifySkipMaterialCodes(); // 缺料不通知：面板勾的料，缺了也不算「缺料」、不通知
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\bMR_[A-Za-z0-9_-]+\\b").matcher(text);
        while (matcher.find()) {
            String code = matcher.group();
            if (known.contains(code) && !skip.contains(code) && !alreadyRemoved.contains(code) && !out.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    // 缺料不通知：profile.notifySkipMaterials 里列的物料 code——缺了不发通知（面板「设置」里勾选）。
    private Set<String> notifySkipMaterialCodes() {
        Set<String> skip = new HashSet<>();
        if (profile == null) return skip;
        JSONArray arr = profile.optJSONArray("notifySkipMaterials");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            String c = arr.optString(i, "");
            if (!c.isEmpty()) skip.add(c);
        }
        return skip;
    }

    private void recordRoundMissing(String sn, List<String> codes) {
        synchronized (roundMissingMaterials) {
            for (String code : codes) {
                if (code == null || code.isEmpty()) continue;
                LinkedHashSet<String> sns = roundMissingMaterials.get(code);
                if (sns == null) {
                    sns = new LinkedHashSet<>();
                    roundMissingMaterials.put(code, sns);
                }
                if (sn != null && !sn.isEmpty()) sns.add(sn);
            }
        }
    }

    private void rememberMissingMaterials(List<String> codes) {
        boolean changed = false;
        for (String code : codes) {
            if (code != null && !code.isEmpty()) changed |= cachedMissingMaterialCodes.add(code);
        }
        if (!changed) return;
        refreshMissingMaterialsUi();
        saveDraft();
    }

    private void removeResolvedSubmittedMissingMaterials(Set<String> removed, Set<String> submittedMissingCandidates) {
        if (submittedMissingCandidates.isEmpty()) return;
        List<String> resolved = new ArrayList<>();
        for (String code : submittedMissingCandidates) {
            if (removed.contains(code)) continue;
            if (!cachedMissingMaterialCodes.remove(code)) continue;
            notifiedMissingMaterialCodes.remove(code);
            resolved.add(code);
        }
        if (resolved.isEmpty()) return;
        Collections.sort(resolved);
        appendLog(t("missing_material_resolved") + join(materialLabels(resolved), ", "));
        refreshMissingMaterialsUi();
    }

    private List<String> materialLabels(List<String> codes) {
        List<String> labels = new ArrayList<>();
        for (String code : codes) labels.add(materialLabel(code));
        return labels;
    }

    private List<String> firstTimeMissingMaterials(List<String> codes) {
        List<String> out = new ArrayList<>();
        for (String code : codes) {
            if (notifiedMissingMaterialCodes.add(code)) out.add(code);
        }
        return out;
    }

    private List<String> validateBatch(String token) {
        List<String> errors = new ArrayList<>();
        if (token.isEmpty()) errors.add(t("login_required_detail"));
        if (units.isEmpty()) errors.add(t("need_one_sn"));
        int expectedSnLength = expectedRobotSnLength();
        for (UnitRecord unit : units) {
            if (expectedSnLength > 0 && unit.sn.length() != expectedSnLength) errors.add("#" + unit.sequence + " " + robotSnLengthMessage(expectedSnLength, unit.sn.length()));
            if (requiresSecondSn() && unit.baseSn.isEmpty()) errors.add("#" + unit.sequence + " " + t("missing_base"));
            if (needsPreviousStepPhoto(unit) && !hasFile(unit.aStepPhotoPath)) errors.add("#" + unit.sequence + " " + t("a_step_photo_required"));
            if (isSlotMode()) {
                JSONArray slots = photoSlots();
                for (int s = 0; slots != null && s < slots.length(); s++) {
                    JSONObject slot = slots.optJSONObject(s);
                    if (slot == null) continue;
                    int min = slot.optInt("minPhotos", 1);
                    if (slotPhotoCount(unit, slot.optString("field")) < min) {
                        errors.add("#" + unit.sequence + " " + slotTitleForField(slot.optString("field")) + " ≥" + min);
                    }
                }
            } else {
                if (unit.frontPhoto.isEmpty()) errors.add("#" + unit.sequence + " " + t("missing_front"));
                if (unit.backPhoto.isEmpty()) errors.add("#" + unit.sequence + " " + t("missing_back"));
            }
        }
        return errors;
    }

    private PhotoStep nextPhotoStep() {
        if ("front_back_per_unit".equals(photoOrder)) {
            for (int i = 0; i < units.size(); i++) {
                UnitRecord unit = units.get(i);
                if (unit.frontPhoto.isEmpty()) return new PhotoStep(i, "front", false);
                if (unit.backPhoto.isEmpty()) return new PhotoStep(i, "back", false);
            }
            return null;
        }
        for (int i = 0; i < units.size(); i++) {
            if (units.get(i).frontPhoto.isEmpty()) return new PhotoStep(i, "front", false);
        }
        boolean noBacks = true;
        for (UnitRecord unit : units) {
            if (!unit.backPhoto.isEmpty()) {
                noBacks = false;
                break;
            }
        }
        for (int i = 0; i < units.size(); i++) {
            if (units.get(i).backPhoto.isEmpty()) return new PhotoStep(i, "back", noBacks);
        }
        return null;
    }

    private UnitRecord firstMissingBaseSn() {
        if (!requiresSecondSn()) return null;
        for (UnitRecord unit : units) {
            if (unit.baseSn.isEmpty()) return unit;
        }
        return null;
    }

    private void refreshAStepPhotoUi() {
        if (aStepPhotoPanel == null) return;
        boolean show = shouldShowAStepPhotoBox();
        aStepPhotoPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        UnitRecord unit = nextAStepPhotoUnit();
        if (aStepPhotoText != null) {
            if (unit != null) {
                aStepPhotoText.setText(t("next_a_step_photo") + "#" + unit.sequence + " " + unit.sn);
            } else {
                aStepPhotoText.setText(units.isEmpty() ? t("add_sn_first") : t("a_step_photos_done"));
            }
        }
        if (aStepPhotoViewButton != null) {
            aStepPhotoViewButton.setVisibility(View.GONE);
        }
    }

    private boolean shouldShowAStepPhotoBox() {
        return nextAStepPhotoUnit() != null || (usesGradeASpecialHandling() && hasGrade("A") && "A".equals(selectedGrade()));
    }

    private UnitRecord nextAStepPhotoUnit() {
        for (UnitRecord unit : units) {
            if (needsPreviousStepPhoto(unit) && !hasFile(unit.aStepPhotoPath)) return unit;
        }
        return null;
    }

    private boolean needsPreviousStepPhoto(UnitRecord unit) {
        return unit != null && (unitTriggersAutoPreviousSteps(unit) || unit.stepPhotoRequired);
    }

    private boolean canAutoCreatePreviousSteps(UnitRecord unit) {
        return needsPreviousStepPhoto(unit);
    }

    /** Profile's auto-previous-steps module config, or null when the profile doesn't opt in. */
    private JSONObject autoPreviousStepsConfig() {
        JSONObject cfg = profile == null ? null : profile.optJSONObject("autoCreatePreviousSteps");
        return (cfg != null && cfg.optBoolean("enabled", true)) ? cfg : null;
    }

    /**
     * Whether a unit should auto-create previous steps. Config-driven when the profile declares
     * {@code autoCreatePreviousSteps} (fires for its {@code grades}, default ["A"]); otherwise falls
     * back to the profile's {@code gradeASpecialHandling} opt-in (grade A) so existing profiles are
     * unchanged.
     */
    private boolean unitTriggersAutoPreviousSteps(UnitRecord unit) {
        if (unit == null) return false;
        JSONObject cfg = autoPreviousStepsConfig();
        if (cfg != null) {
            JSONArray grades = cfg.optJSONArray("grades");
            if (grades == null || grades.length() == 0) {
                return "A".equals(unit.grade);
            }
            for (int i = 0; i < grades.length(); i++) {
                if (grades.optString(i).equals(unit.grade)) return true;
            }
            return false;
        }
        return usesGradeASpecialHandling() && "A".equals(unit.grade);
    }

    private boolean isGradeASpecialUnit(UnitRecord unit) {
        return unit != null && usesGradeASpecialHandling() && "A".equals(unit.grade);
    }

    /** Whether the active profile opts into grade-A special handling (auto previous steps, step
     *  photos, restricted non-new reasons) via its {@code gradeASpecialHandling} flag. */
    private boolean usesGradeASpecialHandling() {
        return profile != null && profile.optBoolean("gradeASpecialHandling", false);
    }

    private boolean hasFile(String path) {
        return path != null && !path.isEmpty() && new File(path).exists();
    }

    private void refreshFormUi() {
        refreshMissingMaterialsUi();
        if (unitList == null || basePrompt == null || photoPrompt == null || summaryText == null) return;
        boolean needsBase = requiresSecondSn();
        boolean hasGradeChoices = hasMultipleGradeChoices();
        if (gradeLabel != null) gradeLabel.setVisibility(hasGradeChoices ? View.VISIBLE : View.GONE);
        if (gradeGroup != null) gradeGroup.setVisibility(hasGradeChoices ? View.VISIBLE : View.GONE);
        updateGradeButtons();
        if (baseLabel != null) baseLabel.setVisibility(needsBase ? View.VISIBLE : View.GONE);
        if (basePrompt != null) basePrompt.setVisibility(needsBase ? View.VISIBLE : View.GONE);
        if (baseRow != null) baseRow.setVisibility(needsBase ? View.VISIBLE : View.GONE);
        if (baseActionRow != null) baseActionRow.setVisibility(needsBase ? View.VISIBLE : View.GONE);

        UnitRecord base = firstMissingBaseSn();
        basePrompt.setText(!needsBase ? "" : base == null ? t("base_done") : t("base_for") + "#" + base.sequence + " " + base.sn);
        refreshAStepPhotoUi();

        if (isSlotMode()) {
            int[] slotStep = nextSlotStep();
            if (slotStep == null) {
                photoPrompt.setText(units.isEmpty() ? t("add_sn_first") : t("photos_done"));
            } else {
                UnitRecord unit = units.get(slotStep[0]);
                JSONObject slot = photoSlots().optJSONObject(slotStep[1]);
                photoPrompt.setText(t("next_photo") + "#" + unit.sequence + " " + unit.sn + " "
                    + slotTitleForField(slot == null ? "" : slot.optString("field")));
            }
            summaryText.setText(t("count") + units.size());
        } else {
            PhotoStep step = nextPhotoStep();
            if (step == null) {
                photoPrompt.setText(units.isEmpty() ? t("add_sn_first") : t("photos_done"));
            } else {
                UnitRecord unit = units.get(step.index);
                photoPrompt.setText(t("next_photo") + "#" + unit.sequence + " " + unit.sn + " " + sideName(step.side));
            }

            int fronts = 0;
            int backs = 0;
            for (UnitRecord unit : units) {
                if (!unit.frontPhoto.isEmpty()) fronts++;
                if (!unit.backPhoto.isEmpty()) backs++;
            }
            summaryText.setText(t("count") + units.size() + " | " + t("front") + " " + fronts + "/" + units.size() + " | " + t("back") + " " + backs + "/" + units.size());
        }

        unitList.removeAllViews();
        unitList.addView(profileSectionHeader(t("current_model") + ": " + currentProfileName() + " (" + units.size() + ")", profileDotColorForId(currentProfileId()), true));
        for (UnitRecord unit : units) {
            unitList.addView(unitCard(unit, needsBase, hasGradeChoices));
        }
        addOtherProfileDraftSections();
    }

    private void refreshMissingMaterialsUi() {
        List<String> codes = new ArrayList<>(cachedMissingMaterialCodes);
        Collections.sort(codes);
        runOnUiThread(() -> {
            if (missingMaterialsText == null) return;
            if (codes.isEmpty()) {
                missingMaterialsText.setText("");
                missingMaterialsText.setVisibility(View.GONE);
                return;
            }
            missingMaterialsText.setVisibility(View.VISIBLE);
            missingMaterialsText.setText(styledLogText(missingMaterialsDisplayText(codes)));
        });
    }

    private String missingMaterialsDisplayText(List<String> codes) {
        List<String> lines = new ArrayList<>();
        lines.add(t("missing_material_list_title") + ":");
        for (String code : codes) {
            lines.add("- " + materialLabel(code));
        }
        return join(lines, "\n");
    }

    private View unitCard(UnitRecord unit, boolean needsBase, boolean hasGradeChoices) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(cardParams);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setStroke(dp(1), 0xFFE2E8F0);
        cardBg.setCornerRadius(dp(10));
        card.setBackground(cardBg);

        // ── 头部:序号 + SN(粗体标题) ──────────────── 状态徽章(右) ──
        LinearLayout header = row();
        TextView title = text("#" + unit.sequence + "  " + unit.sn, 15, true);
        title.setTextColor(0xFF0F172A);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(statusBadge(unit));
        card.addView(header);

        // ── 副信息一行:成色 · 基站 · A步 · 照片 · 预检(灰色小字,不再挤成一大坨) ──
        java.util.List<String> bits = new java.util.ArrayList<>();
        if (hasGradeChoices) bits.add(t("grade") + " " + unit.grade);
        if (needsBase) bits.add(t("base") + " " + emptyDash(unit.baseSn));
        if (needsPreviousStepPhoto(unit)) bits.add(t("a_step_short") + " " + okDash(unit.aStepPhotoPath));
        String photos = isSlotMode() ? slotSummaryText(unit).trim()
            : t("front") + okDash(unit.frontPhoto) + " " + t("back") + okDash(unit.backPhoto) + " " + t("supplemental") + unit.supplementalPhotos.size();
        if (photos != null && !photos.isEmpty()) bits.add(photos);
        if (unit.precheckStatus != null && !unit.precheckStatus.isEmpty()) bits.add(t("precheck") + " " + unit.precheckStatus);
        TextView sub = text(join(bits, "   ·   "), 12, false);
        sub.setTextColor(0xFF64748B);
        LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subP.setMargins(0, dp(5), 0, 0);
        sub.setLayoutParams(subP);
        card.addView(sub);

        // ── 操作:就「拍照 + 详情」两个按钮、右对齐,永远不会超宽。逐张查看/删除都在详情里 ──
        LinearLayout actions = row();
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actP.setMargins(0, dp(4), 0, 0);
        actions.setLayoutParams(actP);
        actions.addView(button("📷 " + t("photo"), v -> captureNextSlotForUnit(unit)));
        actions.addView(button(t("details"), v -> showUnitDetails(unit)));
        card.addView(actions);
        return card;
    }

    // 卡片上的「📷 拍照」:拍这台机器下一个还没拍满的照片框;非slot旧表单则补拍一张。相机在主界面弹,
    // 拍完 refreshFormUi 会重建卡片、张数即时更新——不放进详情弹窗是为了避开「相机返回后弹窗内容不刷新」的坑。
    private void captureNextSlotForUnit(UnitRecord unit) {
        if (!isSlotMode()) { captureSupplementalPhoto(unit); return; }
        JSONArray slots = photoSlots();
        for (int s = 0; slots != null && s < slots.length(); s++) {
            JSONObject slot = slots.optJSONObject(s);
            if (slot == null) continue;
            String field = slot.optString("field");
            int max = slot.optInt("maxPhotos", 0);
            if (max <= 0 || slotPhotoCount(unit, field) < max) { captureSlotPhotoFor(unit, s); return; }
        }
        toast(t("all_photos_done"));
    }

    // 状态小徽章(药丸):代替原来「status=pending」那种裸字段,颜色区分 待提交/已提交/已存在/失败。多语言走 t()。
    private TextView statusBadge(UnitRecord unit) {
        String s = unit.status == null ? "" : unit.status;
        String label; int bg; int fg;
        switch (s) {
            case "success": label = t("q_status_submitted"); bg = 0xFFDCFCE7; fg = 0xFF166534; break;
            case "already_submitted": label = t("q_status_exists"); bg = 0xFFDBEAFE; fg = 0xFF1E40AF; break;
            case "failed": label = t("q_status_failed"); bg = 0xFFFEE2E2; fg = 0xFF991B1B; break;
            default: label = t("q_status_pending"); bg = 0xFFFEF3C7; fg = 0xFF92400E; break;
        }
        TextView b = text(label, 11, true);
        b.setTextColor(fg);
        b.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(11));
        b.setBackground(d);
        return b;
    }

    private View profileSectionHeader(String title, int color, boolean active) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.HORIZONTAL);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.setPadding(0, dp(12), 0, dp(4));

        int lineColor = isLightColor(color) ? 0xFF94A3B8 : color;
        View left = new View(this);
        left.setBackgroundColor(lineColor);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(dp(28), dp(2));
        leftParams.setMargins(0, 0, dp(8), 0);
        section.addView(left, leftParams);

        TextView label = text(title, active ? 14 : 13, true);
        label.setTextColor(active ? 0xFF0F172A : 0xFF475569);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        section.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        View right = new View(this);
        right.setBackgroundColor(lineColor);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(dp(44), dp(2));
        rightParams.setMargins(dp(8), 0, 0, 0);
        section.addView(right, rightParams);
        return section;
    }

    private void addOtherProfileDraftSections() {
        if (unitList == null) return;
        try {
            JSONObject drafts = draftMap(loadDraftStore());
            JSONArray names = drafts.names();
            String activeProfileId = currentProfileId();
            for (int i = 0; names != null && i < names.length(); i++) {
                String profileId = names.optString(i, "");
                if (profileId.isEmpty() || profileId.equals(activeProfileId)) continue;
                JSONObject draft = drafts.optJSONObject(profileId);
                int count = unsubmittedDraftUnitCount(draft);
                if (count <= 0) continue;
                String title = t("saved_model") + ": " + profileNameById(profileId) + " (" + count + ")";
                unitList.addView(profileSectionHeader(title, profileDotColorForId(profileId), false));
                TextView summary = text(draftUnitSummary(draft), 13, false);
                summary.setTextColor(0xFF475569);
                summary.setPadding(dp(10), dp(4), dp(10), dp(8));
                unitList.addView(summary);
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Other profile draft render failed: " + exc.getMessage());
        }
    }

    private String draftUnitSummary(JSONObject draft) {
        List<String> lines = new ArrayList<>();
        JSONArray array = draft == null ? null : draft.optJSONArray("units");
        int shown = 0;
        int total = 0;
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || isSubmittedStatus(item.optString("status", "pending"))) continue;
            String sn = item.optString("sn", "").trim();
            if (sn.isEmpty()) continue;
            total++;
            if (shown < 5) {
                String base = item.optString("baseSn", "").trim();
                String baseText = base.isEmpty() ? "" : " " + t("base") + "=" + base;
                lines.add("#" + item.optInt("sequence", i + 1) + " SN=" + sn + baseText + " " + t("status") + "=" + item.optString("status", "pending"));
                shown++;
            }
        }
        if (total > shown) lines.add("+" + (total - shown) + " ...");
        lines.add(t("switch_model_to_continue"));
        return join(lines, "\n");
    }

    private void deletePhoto(UnitRecord unit, String side) {
        String path = "front".equals(side) ? unit.frontPhoto : unit.backPhoto;
        deleteFileQuietly(path);
        if ("front".equals(side)) unit.frontPhoto = "";
        else unit.backPhoto = "";
        refreshFormUi();
        saveDraft();
    }

    private void deleteUnit(UnitRecord unit) {
        if (unit == null || !units.remove(unit)) return;
        deleteUnitFiles(unit);
        refreshFormUi();
        saveDraft();
        refocusSnInput();
    }

    private int pruneSubmittedUnits() {
        int removed = 0;
        for (int i = units.size() - 1; i >= 0; i--) {
            UnitRecord unit = units.get(i);
            if (!isSubmittedStatus(unit.status)) continue;
            deleteUnitFiles(unit);
            units.remove(i);
            removed++;
        }
        if (removed == 0) {
            refreshFormUi();
            saveDraft();
            return 0;
        }
        if (units.isEmpty()) {
            cachedMissingMaterialCodes.clear();
            notifiedMissingMaterialCodes.clear();
            missingMaterialNoticeShown = false;
            clearDraft();
        } else {
            saveDraft();
        }
        refreshFormUi();
        refocusSnInput();
        return removed;
    }

    private boolean removeSubmittedUnitFromQueue(UnitRecord unit) {
        if (unit == null || !isSubmittedStatus(unit.status) || !units.remove(unit)) return false;
        deleteUnitFiles(unit);
        if (units.isEmpty()) {
            cachedMissingMaterialCodes.clear();
            notifiedMissingMaterialCodes.clear();
            missingMaterialNoticeShown = false;
            clearDraft();
        } else {
            saveDraft();
        }
        runOnUiThread(() -> {
            refreshFormUi();
            refocusSnInput();
        });
        return true;
    }

    private void removeScannedUnitAfterPrecheckMissing(UnitRecord unit) {
        if (unit == null || !units.remove(unit)) return;
        deleteUnitFiles(unit);
        if (units.isEmpty()) {
            clearDraft();
        } else {
            saveDraft();
        }
        runOnUiThread(() -> {
            refreshFormUi();
            refocusSnInput();
        });
    }

    private void deleteUnitFiles(UnitRecord unit) {
        deleteFileQuietly(unit.frontPhoto);
        deleteFileQuietly(unit.backPhoto);
        deleteFileQuietly(unit.aStepPhotoPath);
        for (String path : unit.supplementalPhotos) {
            deleteFileQuietly(path);
        }
    }

    private void deleteFileQuietly(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            new File(path).delete();
        } catch (Exception ignored) {
        }
    }

    private int nextUnitSequence() {
        int max = 0;
        for (UnitRecord unit : units) {
            if (unit.sequence > max) max = unit.sequence;
        }
        return max + 1;
    }

    private UnitRecord unitBySequence(int sequence) {
        for (UnitRecord unit : units) {
            if (unit.sequence == sequence) return unit;
        }
        return null;
    }

    private void showUnitDetails(UnitRecord unit) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        root.addView(text("#" + unit.sequence + " " + unit.sn, 20, true));
        root.addView(label(t("robot_sn")));
        LinearLayout snRow = row();
        EditText snInput = edit("SN");
        snInput.setText(unit.sn);
        snRow.addView(snInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        View rescanButton = scanIconButton(v -> {});
        snRow.addView(rescanButton);
        root.addView(snRow);

        Spinner gradeSpinner = null;
        List<String> grades = availableGrades();
        if (grades.size() > 1) {
            root.addView(label(t("grade_class")));
            gradeSpinner = new Spinner(this);
            gradeSpinner.setAdapter(largeSpinnerAdapter(grades));
            int selected = Math.max(0, grades.indexOf(unit.grade));
            gradeSpinner.setSelection(selected);
            root.addView(gradeSpinner);
        }

        EditText baseInput = null;
        View baseRescanButton = null;
        if (requiresSecondSn()) {
            root.addView(label(t("base_sn")));
            LinearLayout baseSnRow = row();
            baseInput = edit(t("base_sn"));
            baseInput.setText(unit.baseSn == null ? "" : unit.baseSn);
            baseSnRow.addView(baseInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            baseRescanButton = scanIconButton(v -> {});
            baseSnRow.addView(baseRescanButton);
            root.addView(baseSnRow);
        }

        root.addView(label(t("photos")));
        LinearLayout photoBox = new LinearLayout(this);
        photoBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(photoBox);
        renderDetailsPhotos(photoBox, unit);

        root.addView(label(t("status")));
        root.addView(text(t("precheck") + "=" + unit.precheckStatus + "  " + t("status") + "=" + unit.status, 16, false));

        Spinner finalGradeSpinner = gradeSpinner;
        EditText finalBaseInput = baseInput;
        View finalBaseRescanButton = baseRescanButton;
        final AlertDialog[] dialogRef = new AlertDialog[1];
        rescanButton.setOnClickListener(v -> {
            startUnitSnRescan(unit, false);
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        if (finalBaseRescanButton != null) {
            finalBaseRescanButton.setOnClickListener(v -> {
                startUnitSnRescan(unit, true);
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(t("details"))
            .setView(scroll)
            .setNeutralButton(t("delete_unit"), null)
            .setNegativeButton(t("cancel"), null)
            .setPositiveButton(t("save"), null)
            .create();
        dialogRef[0] = dialog;
        dialog.setOnShowListener(d -> {
            Button delete = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (delete != null) {
                delete.setTextColor(0xFFDC2626);
                delete.setOnClickListener(v -> {
                    deleteUnit(unit);
                    dialog.dismiss();
                });
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String nextSn = normalize(snInput.getText().toString());
                if (nextSn.isEmpty()) {
                    toast(t("sn_required"));
                    return;
                }
                if (!validateRobotSnLength(nextSn)) return;
                for (UnitRecord item : units) {
                    if (item != unit && item.sn.equals(nextSn)) {
                        toast(t("duplicate_sn") + nextSn);
                        return;
                    }
                }
                String oldSn = unit.sn;
                unit.sn = nextSn;
                if (!oldSn.equals(nextSn)) {
                    unit.precheckStatus = "unchecked";
                    unit.status = "pending";
                }
                if (finalGradeSpinner != null) {
                    unit.grade = String.valueOf(finalGradeSpinner.getSelectedItem());
                }
                if (finalBaseInput != null) {
                    String nextBase = normalize(finalBaseInput.getText().toString());
                    // base station SN duplicate check intentionally removed
                    String oldBase = unit.baseSn == null ? "" : unit.baseSn;
                    unit.baseSn = nextBase;
                    if (!oldBase.equals(nextBase)) {
                        unit.precheckStatus = "unchecked";
                        unit.status = "pending";
                    }
                }
                refreshFormUi();
                saveDraft();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    // 详情里的照片区:逐张「查看照片」按钮,点开图片后左下角可删除;删完就地重绘这块、不用关弹窗。
    // A步照片沿用原有行(自带删除)。拍照仍在卡片/引导流里(相机弹窗生命周期在主界面才稳)。
    private void renderDetailsPhotos(LinearLayout box, UnitRecord unit) {
        box.removeAllViews();
        if (needsPreviousStepPhoto(unit)) addAStepPhotoRow(box, unit);
        if (isSlotMode()) {
            JSONArray slots = photoSlots();
            for (int s = 0; slots != null && s < slots.length(); s++) {
                JSONObject slot = slots.optJSONObject(s);
                if (slot == null) continue;
                final String field = slot.optString("field");
                List<String> photos = unit.slotPhotos.get(field);
                for (int i = 0; photos != null && i < photos.size(); i++) {
                    final int idx = i;
                    addPhotoViewButton(box, slotTitleForField(field) + " " + (i + 1), photos.get(i), () -> {
                        List<String> ps = unit.slotPhotos.get(field);
                        if (ps != null && idx < ps.size()) { deleteFileQuietly(ps.get(idx)); ps.remove(idx); }
                        refreshFormUi();
                        saveDraft();
                        renderDetailsPhotos(box, unit);
                    });
                }
            }
        } else {
            addPhotoViewButton(box, t("front"), unit.frontPhoto, unit.frontPhoto.isEmpty() ? null : () -> {
                deletePhoto(unit, "front");
                renderDetailsPhotos(box, unit);
            });
            addPhotoViewButton(box, t("back"), unit.backPhoto, unit.backPhoto.isEmpty() ? null : () -> {
                deletePhoto(unit, "back");
                renderDetailsPhotos(box, unit);
            });
            for (int i = 0; i < unit.supplementalPhotos.size(); i++) {
                final int idx = i;
                addPhotoViewButton(box, t("supplemental") + " " + (i + 1), unit.supplementalPhotos.get(i), () -> {
                    if (idx < unit.supplementalPhotos.size()) { deleteFileQuietly(unit.supplementalPhotos.get(idx)); unit.supplementalPhotos.remove(idx); }
                    refreshFormUi();
                    saveDraft();
                    renderDetailsPhotos(box, unit);
                });
            }
        }
    }

    private void addPhotoViewButton(LinearLayout root, String title, String path) {
        addPhotoViewButton(root, title, path, null);
    }

    private void addPhotoViewButton(LinearLayout root, String title, String path, Runnable onDelete) {
        if (path == null || path.isEmpty()) {
            root.addView(text(title + ": -", 15, false));
            return;
        }
        Button view = button(t("view_photo") + " " + title, v -> showPhotoPreview(title, path, onDelete));
        root.addView(view);
    }

    // A-step (前两步补录/跑机测试) photo: like front/back, give it a view button AND a red delete button.
    private void addAStepPhotoRow(LinearLayout root, UnitRecord unit) {
        LinearLayout rowView = row();
        root.addView(rowView);
        renderAStepPhotoRow(rowView, unit);
    }

    private void renderAStepPhotoRow(LinearLayout rowView, UnitRecord unit) {
        rowView.removeAllViews();
        String title = t("a_step_photo");
        String path = unit.aStepPhotoPath;
        if (path == null || path.isEmpty()) {
            rowView.addView(text(title + ": -", 15, false));
            return;
        }
        Button view = button(t("view_photo") + " " + title, v -> showPhotoPreview(title, path));
        rowView.addView(view, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button delete = button(t("delete_a_step"), v -> {
            deleteAStepPhoto(unit);
            renderAStepPhotoRow(rowView, unit); // refresh in place → row now shows "title: -"
        });
        delete.setTextColor(0xFFDC2626); // red, same as the delete-unit action
        rowView.addView(delete);
    }

    private void deleteAStepPhoto(UnitRecord unit) {
        deleteFileQuietly(unit.aStepPhotoPath);
        unit.aStepPhotoPath = "";
        refreshFormUi();
        saveDraft();
    }

    private void showPhotoPreview(String title, String path) {
        showPhotoPreview(title, path, null);
    }

    // 图片预览:onDelete 非空时,左下角(neutral 按钮)出现红色「删除照片」——删完回调里删文件+重绘详情列表。
    private void showPhotoPreview(String title, String path, Runnable onDelete) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int height = Math.max(dp(360), getResources().getDisplayMetrics().heightPixels - dp(180));
        int width = Math.max(dp(240), getResources().getDisplayMetrics().widthPixels - dp(32));
        Bitmap preview = decodeBitmapForDisplay(path, width, height);
        if (preview == null) {
            alert(t("photo_save_failed"), t("photo_preview_failed"));
            return;
        }
        image.setImageBitmap(preview);
        image.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(image)
            .setPositiveButton(t("close"), null);
        if (onDelete != null) builder.setNeutralButton(t("delete_photo"), (d, w) -> onDelete.run()); // neutral = 左下角
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button del = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (del != null) del.setTextColor(0xFFDC2626);
        });
        dialog.setOnDismissListener(d -> image.setImageDrawable(null));
        dialog.show();
    }

    private Bitmap decodeBitmapForDisplay(String path, int maxWidth, int maxHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError error) {
            Diagnostics.append(this, "Photo preview out of memory: " + error.getMessage());
            return null;
        } catch (Exception exc) {
            Diagnostics.append(this, "Photo preview failed: " + exc.getMessage());
            return null;
        }
    }

    private int sampleSize(int width, int height, int maxWidth, int maxHeight) {
        int sample = 1;
        while ((width / sample) > maxWidth || (height / sample) > maxHeight) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private void maybePromptSavedDraft() {
        if (draftPromptShown || !units.isEmpty()) return;
        JSONObject store = loadDraftStore();
        int count = totalUnsubmittedDraftUnitCount(store);
        if (count <= 0) {
            clearAllDrafts();
            return;
        }
        JSONObject draft = preferredDraft(store);
        if (draft == null) return;
        draftPromptShown = true;
        String savedAt = latestDraftSavedAtText(store);
        new AlertDialog.Builder(this)
            .setTitle(t("draft_found"))
            .setMessage(t("draft_found_detail") + count + (savedAt.isEmpty() ? "" : "\n" + savedAt))
            .setNegativeButton(t("discard_draft"), (dialog, which) -> {
                discardAllDraftsAndResetForm();
            })
            .setPositiveButton(t("continue_draft"), (dialog, which) -> {
                try {
                    restoreDraft(draft);
                } catch (Exception exc) {
                    alert(t("draft_restore_failed"), exc.getMessage());
                    clearDraftForProfile(draft.optString("profileId", ""));
                }
            })
            .show();
    }

    private boolean draftHasUnsubmittedUnits(JSONObject draft) {
        return unsubmittedDraftUnitCount(draft) > 0;
    }

    private int unsubmittedDraftUnitCount(JSONObject draft) {
        if (draft == null) return 0;
        JSONArray array = draft.optJSONArray("units");
        int count = 0;
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null
                && !item.optString("sn", "").trim().isEmpty()
                && !isSubmittedStatus(item.optString("status", "pending"))) {
                count++;
            }
        }
        return count;
    }

    private int totalUnsubmittedDraftUnitCount(JSONObject store) {
        int count = 0;
        try {
            JSONObject drafts = draftMap(store);
            JSONArray names = drafts.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                count += unsubmittedDraftUnitCount(drafts.optJSONObject(names.optString(i)));
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Draft count failed: " + exc.getMessage());
        }
        return count;
    }

    private String latestDraftSavedAtText(JSONObject store) {
        String savedAtText = "";
        long latest = -1L;
        try {
            JSONObject drafts = draftMap(store);
            JSONArray names = drafts.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                JSONObject draft = drafts.optJSONObject(names.optString(i));
                if (unsubmittedDraftUnitCount(draft) <= 0) continue;
                long savedAt = draft.optLong("savedAt", 0L);
                if (savedAt >= latest) {
                    latest = savedAt;
                    savedAtText = draft.optString("savedAtText", savedAtText);
                }
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Draft saved time failed: " + exc.getMessage());
        }
        return savedAtText;
    }

    private JSONObject preferredDraft(JSONObject store) {
        JSONObject draft = draftForProfileFromStore(store, currentProfileId());
        if (draftHasUnsubmittedUnits(draft)) return draft;
        draft = draftForProfileFromStore(store, prefs.getString(LAST_PROFILE_ID_KEY, ""));
        if (draftHasUnsubmittedUnits(draft)) return draft;
        try {
            JSONObject drafts = draftMap(store);
            JSONArray names = drafts.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                draft = drafts.optJSONObject(names.optString(i));
                if (draftHasUnsubmittedUnits(draft)) return draft;
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Preferred draft lookup failed: " + exc.getMessage());
        }
        return null;
    }

    private JSONObject draftForProfile(String profileId) {
        return draftForProfileFromStore(loadDraftStore(), profileId);
    }

    private JSONObject draftForProfileFromStore(JSONObject store, String profileId) {
        if (profileId == null || profileId.isEmpty()) return null;
        try {
            return draftMap(store).optJSONObject(profileId);
        } catch (Exception exc) {
            Diagnostics.append(this, "Draft lookup failed: " + exc.getMessage());
            return null;
        }
    }

    private JSONObject draftForPhotoResult(int index) {
        if (index < 0) return null;
        JSONObject store = loadDraftStore();
        JSONObject draft = draftForProfileFromStore(store, currentProfileId());
        if (draftUnitCount(draft) > index) return draft;
        draft = draftForProfileFromStore(store, prefs.getString(LAST_PROFILE_ID_KEY, ""));
        if (draftUnitCount(draft) > index) return draft;
        try {
            JSONObject drafts = draftMap(store);
            JSONArray names = drafts.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                draft = drafts.optJSONObject(names.optString(i));
                if (draftUnitCount(draft) > index) return draft;
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Photo draft lookup failed: " + exc.getMessage());
        }
        return null;
    }

    private JSONObject draftForUnitSequence(int sequence) {
        if (sequence < 0) return null;
        JSONObject store = loadDraftStore();
        JSONObject draft = draftForProfileFromStore(store, currentProfileId());
        if (draftContainsUnitSequence(draft, sequence)) return draft;
        draft = draftForProfileFromStore(store, prefs.getString(LAST_PROFILE_ID_KEY, ""));
        if (draftContainsUnitSequence(draft, sequence)) return draft;
        try {
            JSONObject drafts = draftMap(store);
            JSONArray names = drafts.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                draft = drafts.optJSONObject(names.optString(i));
                if (draftContainsUnitSequence(draft, sequence)) return draft;
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Rescan draft lookup failed: " + exc.getMessage());
        }
        return null;
    }

    private int draftUnitCount(JSONObject draft) {
        JSONArray array = draft == null ? null : draft.optJSONArray("units");
        return array == null ? 0 : array.length();
    }

    private boolean draftContainsUnitSequence(JSONObject draft, int sequence) {
        JSONArray array = draft == null ? null : draft.optJSONArray("units");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && item.optInt("sequence", -1) == sequence) return true;
        }
        return false;
    }

    private void discardAllDraftsAndResetForm() {
        clearAllDrafts();
        units.clear();
        clearProfileScopedState();
        refreshFormUi();
        resetGradeSelection();
        refocusSnInput();
    }

    private void restoreDraft(JSONObject draft) throws JSONException {
        restoringDraft = true;
        int restored = 0;
        try {
            String profileId = draft.optString("profileId", "");
            int profileIndex = findProfileIndex(profileId);
            if (profileIndex >= 0) {
                profile = profiles.getJSONObject(profileIndex);
                if (profileSpinner != null) profileSpinner.setSelection(profileIndex);
            }
            restored = restoreDraftContents(draft);
            saveLastProfile();
            saveDraft();
            Diagnostics.append(this, "Draft restored units=" + restored);
        } finally {
            if (profileSpinner != null) {
                profileSpinner.post(() -> restoringDraft = false);
            } else {
                restoringDraft = false;
            }
        }
    }

    private int restoreDraftContents(JSONObject draft) throws JSONException {
        int restored = 0;
        photoOrder = draft.optString("photoOrder", photoOrder);
        units.clear();
        clearProfileScopedState();
        restoreMissingMaterialCodes(draft.optJSONArray("missingMaterialCodes"));
        JSONArray array = draft.optJSONArray("units");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String sn = item.optString("sn", "").trim();
            if (sn.isEmpty()) continue;
            UnitRecord unit = new UnitRecord(item.optInt("sequence", i + 1), sn, item.optString("grade", firstGradeKey()));
            unit.baseSn = item.optString("baseSn", "");
            unit.aStepPhotoPath = item.optString("aStepPhotoPath", "");
            unit.stepPhotoRequired = item.optBoolean("stepPhotoRequired", false);
            unit.frontPhoto = item.optString("frontPhoto", "");
            unit.backPhoto = item.optString("backPhoto", "");
            unit.precheckStatus = item.optString("precheckStatus", "unchecked");
            unit.status = item.optString("status", "pending");
            JSONArray supplemental = item.optJSONArray("supplementalPhotos");
            for (int j = 0; supplemental != null && j < supplemental.length(); j++) {
                unit.supplementalPhotos.add(supplemental.optString(j));
            }
            JSONObject slotPhotos = item.optJSONObject("slotPhotos");
            JSONArray slotFields = slotPhotos == null ? null : slotPhotos.names();
            for (int j = 0; slotFields != null && j < slotFields.length(); j++) {
                String field = slotFields.optString(j);
                JSONArray paths = slotPhotos.optJSONArray(field);
                List<String> list = new ArrayList<>();
                for (int k = 0; paths != null && k < paths.length(); k++) list.add(paths.optString(k));
                unit.slotPhotos.put(field, list);
            }
            JSONObject pluginSns = item.optJSONObject("pluginSns");
            JSONArray pluginFields = pluginSns == null ? null : pluginSns.names();
            for (int j = 0; pluginFields != null && j < pluginFields.length(); j++) {
                String field = pluginFields.optString(j);
                unit.pluginSns.put(field, pluginSns.optString(field));
            }
            unit.defective = item.optBoolean("defective", false);
            units.add(unit);
            restored++;
        }
        missingMaterialNoticeShown = draft.optBoolean("missingMaterialNoticeShown", false);
        refreshFormUi();
        resetGradeSelection();
        refocusSnInput();
        return restored;
    }

    private void restoreCurrentProfileDraftOrEmpty() {
        clearProfileScopedState();
        JSONObject draft = draftForProfile(currentProfileId());
        if (draftHasUnsubmittedUnits(draft)) {
            try {
                int restored = restoreDraftContents(draft);
                saveDraft();
                Diagnostics.append(this, "Profile draft restored profile=" + currentProfileId() + " units=" + restored);
                return;
            } catch (Exception exc) {
                Diagnostics.append(this, "Profile draft restore failed: " + exc.getMessage());
                clearDraftForProfile(currentProfileId());
            }
        } else if (draft != null) {
            clearDraftForProfile(currentProfileId());
        }
        units.clear();
        refreshFormUi();
        resetGradeSelection();
        refocusSnInput();
    }

    private void clearProfileScopedState() {
        cachedMissingMaterialCodes.clear();
        notifiedMissingMaterialCodes.clear();
        missingMaterialNoticeShown = false;
        pendingAStepPhotoUnitSequence = -1;
        cachedAClassStepOneTemplate = null;
        cachedAClassStepTwoTemplate = null;
    }

    private void restoreMissingMaterialCodes(JSONArray codes) {
        for (int i = 0; codes != null && i < codes.length(); i++) {
            String code = codes.optString(i, "").trim();
            if (!code.isEmpty()) cachedMissingMaterialCodes.add(code);
        }
    }

    private JSONArray jsonArrayFromStrings(Set<String> values) {
        JSONArray array = new JSONArray();
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        for (String value : sorted) array.put(value);
        return array;
    }

    private int findProfileIndex(String profileId) {
        for (int i = 0; profiles != null && i < profiles.length(); i++) {
            try {
                if (profileId.equals(profiles.getJSONObject(i).optString("id"))) return i;
            } catch (JSONException ignored) {
            }
        }
        return -1;
    }

    private void applyLastProfileSelection() {
        String profileId = prefs.getString(LAST_PROFILE_ID_KEY, "");
        int index = findProfileIndex(profileId);
        if (index < 0) return;
        try {
            profile = profiles.getJSONObject(index);
            if (profileSpinner != null && profileSpinner.getSelectedItemPosition() != index) {
                profileSpinner.setSelection(index);
            }
        } catch (JSONException ignored) {
        }
    }

    private void saveLastProfile() {
        if (profile == null) return;
        String id = profile.optString("id", "");
        if (!id.isEmpty()) prefs.edit().putString(LAST_PROFILE_ID_KEY, id).apply();
    }

    private JSONObject buildDraftJson(String profileId) throws JSONException {
        JSONObject draft = new JSONObject();
        draft.put("version", 2);
        draft.put("profileId", profileId);
        draft.put("photoOrder", photoOrder);
        draft.put("savedAt", System.currentTimeMillis());
        draft.put("savedAtText", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date()));
        draft.put("missingMaterialNoticeShown", missingMaterialNoticeShown);
        draft.put("missingMaterialCodes", jsonArrayFromStrings(cachedMissingMaterialCodes));
        JSONArray array = new JSONArray();
        for (UnitRecord unit : units) {
            JSONObject item = new JSONObject();
            item.put("sequence", unit.sequence);
            item.put("sn", unit.sn);
            item.put("grade", unit.grade);
            item.put("baseSn", unit.baseSn);
            item.put("aStepPhotoPath", unit.aStepPhotoPath);
            item.put("stepPhotoRequired", unit.stepPhotoRequired);
            item.put("frontPhoto", unit.frontPhoto);
            item.put("backPhoto", unit.backPhoto);
            item.put("precheckStatus", unit.precheckStatus);
            item.put("status", unit.status);
            JSONArray supplemental = new JSONArray();
            for (String path : unit.supplementalPhotos) supplemental.put(path);
            item.put("supplementalPhotos", supplemental);
            if (!unit.slotPhotos.isEmpty()) {
                JSONObject slotPhotos = new JSONObject();
                for (Map.Entry<String, List<String>> entry : unit.slotPhotos.entrySet()) {
                    JSONArray paths = new JSONArray();
                    for (String path : entry.getValue()) paths.put(path);
                    slotPhotos.put(entry.getKey(), paths);
                }
                item.put("slotPhotos", slotPhotos);
            }
            if (!unit.pluginSns.isEmpty()) {
                JSONObject pluginSns = new JSONObject();
                for (Map.Entry<String, String> entry : unit.pluginSns.entrySet()) pluginSns.put(entry.getKey(), entry.getValue());
                item.put("pluginSns", pluginSns);
            }
            if (unit.defective) item.put("defective", true);
            array.put(item);
        }
        draft.put("units", array);
        return draft;
    }

    private void saveDraft() {
        try {
            String profileId = currentProfileId();
            if (profileId.isEmpty()) return;
            JSONObject store = loadDraftStore();
            JSONObject drafts = draftMap(store);
            if (!hasUnsubmittedUnits()) {
                drafts.remove(profileId);
                writeDraftStore(store);
                return;
            }
            drafts.put(profileId, buildDraftJson(profileId));
            writeDraftStore(store);
        } catch (Exception exc) {
            appendLog(t("draft_save_failed") + exc.getMessage());
        }
    }

    // --- Manual queue backup: save the current queue on purpose, reload it another day to keep uploading. ---
    // Durability is the whole point here, so we keep TWO independent on-disk copies and only report
    // success after reading the data back: (1) a dedicated file in internal storage written atomically and
    // fsync'd (survives crash/kill/power loss), and (2) a synchronous SharedPreferences commit() as a mirror.
    private File queueBackupFile() {
        return new File(getFilesDir(), "queue-backup.json");
    }

    private boolean writeQueueBackupFileAtomic(String json) {
        File target = queueBackupFile();
        File tmp = new File(getFilesDir(), "queue-backup.json.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync(); // force bytes to physical disk before we swap the file in
        } catch (Exception exc) {
            Diagnostics.append(this, "Queue backup tmp write failed: " + exc.getMessage());
            return false;
        }
        // rename(2) over an existing file is atomic on Android's filesystem: a reader always sees either
        // the complete old file or the complete new one, never a half-written file.
        if (tmp.renameTo(target)) return true;
        if (target.delete() && tmp.renameTo(target)) return true;
        Diagnostics.append(this, "Queue backup rename failed");
        return false;
    }

    private String readTextFile(File file) throws IOException {
        if (file == null || !file.exists()) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private void saveQueueSnapshot() {
        if (units.isEmpty()) {
            toast(t("queue_backup_empty"));
            return;
        }
        final int count = units.size();
        String json;
        try {
            json = buildDraftJson(currentProfileId()).toString();
        } catch (Exception exc) {
            alert(t("queue_backup_save_failed"), exc.getMessage());
            return;
        }
        boolean fileOk = writeQueueBackupFileAtomic(json);
        boolean prefOk = prefs.edit().putString(MANUAL_QUEUE_KEY, json).commit(); // commit() = synchronous flush
        // Prove it: read the durable copy back and confirm the unit count survived the round trip.
        boolean verified = false;
        JSONObject readBack = loadQueueSnapshot();
        if (readBack != null) {
            JSONArray arr = readBack.optJSONArray("units");
            verified = arr != null && arr.length() == count;
        }
        Diagnostics.append(this, "Queue snapshot saved units=" + count
            + " file=" + fileOk + " pref=" + prefOk + " verified=" + verified);
        if ((fileOk || prefOk) && verified) {
            toast(t("queue_backup_saved") + count);
        } else {
            // Never let the user walk away believing a save stuck when it did not.
            alert(t("queue_backup_save_failed"), "file=" + fileOk + " pref=" + prefOk + " verify=" + verified);
        }
    }

    private JSONObject loadQueueSnapshot() {
        // Primary copy: the dedicated fsync'd file.
        try {
            String fileRaw = readTextFile(queueBackupFile());
            if (!fileRaw.isEmpty()) return new JSONObject(fileRaw);
        } catch (Exception exc) {
            Diagnostics.append(this, "Queue backup file unreadable, falling back to prefs: " + exc.getMessage());
        }
        // Fallback copy: SharedPreferences. Never delete on failure — keep whatever bytes exist so the
        // data can still be recovered manually rather than being thrown away on a transient parse error.
        String raw = prefs.getString(MANUAL_QUEUE_KEY, "");
        if (raw.isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception exc) {
            Diagnostics.append(this, "Queue backup pref copy unparseable (kept, not deleted): " + exc.getMessage());
            return null;
        }
    }

    private void restoreQueueSnapshot() {
        JSONObject snapshot = loadQueueSnapshot();
        if (snapshot == null) {
            toast(t("queue_backup_none"));
            return;
        }
        JSONArray array = snapshot.optJSONArray("units");
        int count = array == null ? 0 : array.length();
        Runnable doRestore = () -> {
            try {
                restoreDraft(snapshot);
                toast(t("queue_backup_restored") + count);
            } catch (Exception exc) {
                alert(t("draft_restore_failed"), exc.getMessage());
            }
        };
        if (units.isEmpty()) {
            doRestore.run();
        } else {
            new AlertDialog.Builder(this)
                .setTitle(t("queue_backup_restore"))
                .setMessage(t("queue_backup_overwrite_confirm"))
                .setNegativeButton(t("cancel"), null)
                .setPositiveButton(t("queue_backup_restore"), (d, w) -> doRestore.run())
                .show();
        }
    }

    private String queueBackupInfoText() {
        JSONObject snapshot = loadQueueSnapshot();
        if (snapshot == null) return t("queue_backup_none");
        JSONArray array = snapshot.optJSONArray("units");
        int count = array == null ? 0 : array.length();
        StringBuilder sb = new StringBuilder(t("queue_backup_saved_info")).append(count);
        String profileName = profileDisplayNameForId(snapshot.optString("profileId", ""));
        if (!profileName.isEmpty()) sb.append(" · ").append(profileName);
        String savedAt = snapshot.optString("savedAtText", "");
        if (!savedAt.isEmpty()) sb.append(" · ").append(savedAt);
        return sb.toString();
    }

    private String profileDisplayNameForId(String profileId) {
        int index = findProfileIndex(profileId);
        if (index < 0) return "";
        try {
            return profiles.getJSONObject(index).optString("displayName", "");
        } catch (JSONException exc) {
            return "";
        }
    }

    private void clearDraft() {
        clearDraftForProfile(currentProfileId());
    }

    private void clearDraftForProfile(String profileId) {
        if (profileId == null || profileId.isEmpty()) return;
        try {
            JSONObject store = loadDraftStore();
            draftMap(store).remove(profileId);
            writeDraftStore(store);
        } catch (Exception exc) {
            Diagnostics.append(this, "Draft clear failed: " + exc.getMessage());
        }
    }

    private void clearAllDrafts() {
        prefs.edit().remove(DRAFT_KEY).remove(DRAFT_STORE_KEY).commit();
    }

    private JSONObject loadDraftStore() {
        JSONObject store = new JSONObject();
        String raw = prefs.getString(DRAFT_STORE_KEY, "");
        if (!raw.isEmpty()) {
            try {
                store = new JSONObject(raw);
            } catch (Exception exc) {
                Diagnostics.append(this, "Discarding unreadable draft store: " + exc.getMessage());
                prefs.edit().remove(DRAFT_STORE_KEY).apply();
                store = new JSONObject();
            }
        }
        try {
            draftMap(store);
            migrateLegacyDraft(store);
        } catch (Exception exc) {
            Diagnostics.append(this, "Draft store load failed: " + exc.getMessage());
        }
        return store;
    }

    private JSONObject draftMap(JSONObject store) throws JSONException {
        JSONObject drafts = store.optJSONObject("drafts");
        if (drafts == null) {
            drafts = new JSONObject();
            store.put("drafts", drafts);
        }
        return drafts;
    }

    private void migrateLegacyDraft(JSONObject store) {
        String raw = prefs.getString(DRAFT_KEY, "");
        if (raw.isEmpty()) return;
        try {
            JSONObject legacy = new JSONObject(raw);
            if (draftHasUnsubmittedUnits(legacy)) {
                String profileId = legacy.optString("profileId", "");
                if (profileId.isEmpty()) profileId = prefs.getString(LAST_PROFILE_ID_KEY, "");
                if (profileId.isEmpty()) profileId = currentProfileId();
                if (!profileId.isEmpty()) {
                    legacy.put("profileId", profileId);
                    draftMap(store).put(profileId, legacy);
                    writeDraftStore(store);
                    Diagnostics.append(this, "Legacy draft migrated profile=" + profileId);
                    return;
                }
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Discarding unreadable saved draft: " + exc.getMessage());
        }
        prefs.edit().remove(DRAFT_KEY).apply();
    }

    private void writeDraftStore(JSONObject store) throws JSONException {
        JSONObject drafts = draftMap(store);
        if (drafts.length() <= 0) {
            prefs.edit().remove(DRAFT_STORE_KEY).remove(DRAFT_KEY).apply();
            return;
        }
        store.put("version", 2);
        prefs.edit().putString(DRAFT_STORE_KEY, store.toString()).remove(DRAFT_KEY).apply();
    }

    private boolean hasUnsubmittedUnits() {
        for (UnitRecord unit : units) {
            if (!isSubmittedStatus(unit.status)) return true;
        }
        return false;
    }

    private boolean isSubmittedStatus(String status) {
        return "success".equals(status) || "already_submitted".equals(status) || "duplicate_skipped".equals(status);
    }

    private List<String> availableGrades() {
        List<String> grades = new ArrayList<>();
        for (String grade : new String[]{"A", "B", "C"}) {
            if (hasGrade(grade)) grades.add(grade);
        }
        if (grades.isEmpty()) grades.add("A");
        return grades;
    }

    private List<String> profileNames() {
        List<String> names = new ArrayList<>();
        try {
            for (int i = 0; i < profiles.length(); i++) {
                names.add(profiles.getJSONObject(i).optString("displayName", "Profile " + (i + 1)));
            }
        } catch (JSONException exc) {
            names.add("Profile load error");
        }
        return names;
    }

    private View profileSpinnerView(int position, boolean dropdown) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dropdown ? dp(12) : dp(8), dp(10), dropdown ? dp(12) : dp(8));
        row.setMinimumHeight(dp(dropdown ? 52 : 46));

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        int color = profileDotColor(position);
        dotBg.setColor(color);
        dotBg.setStroke(dp(2), isLightColor(color) ? 0xFF777777 : 0x33000000);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        dotParams.setMargins(0, 0, dp(12), 0);
        row.addView(dot, dotParams);

        TextView name = text(profileName(position), dropdown ? 20 : 18, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private String profileName(int position) {
        try {
            if (position >= 0 && position < profiles.length()) {
                return profiles.getJSONObject(position).optString("displayName", "Profile " + (position + 1));
            }
        } catch (JSONException ignored) {
        }
        return "Profile " + (position + 1);
    }

    private String currentProfileName() {
        return profileNameById(currentProfileId());
    }

    private String profileNameById(String profileId) {
        int index = findProfileIndex(profileId);
        return index >= 0 ? profileName(index) : emptyDash(profileId);
    }

    private int profileDotColorForId(String profileId) {
        int index = findProfileIndex(profileId);
        return index >= 0 ? profileDotColor(index) : 0xFF64748B;
    }

    private int profileDotColor(int position) {
        JSONObject item;
        try {
            item = profiles.getJSONObject(position);
        } catch (Exception exc) {
            return 0xFF64748B;
        }
        String explicit = item.optString("uiColor", "").trim();
        if (!explicit.isEmpty()) {
            Integer parsed = parseColor(explicit);
            if (parsed != null) return parsed;
        }
        String color = (item.optString("color", "") + " " + item.optString("displayName", "")).toLowerCase();
        if (color.contains("black") || color.contains("黑")) return 0xFF111827;
        if (color.contains("white") || color.contains("白")) return 0xFFFFFFFF;
        if (color.contains("red") || color.contains("红")) return 0xFFDC2626;
        if (color.contains("blue") || color.contains("蓝")) return 0xFF2563EB;
        if (color.contains("green") || color.contains("绿")) return 0xFF16A34A;
        if (color.contains("yellow") || color.contains("黄")) return 0xFFEAB308;
        if (color.contains("gray") || color.contains("grey") || color.contains("灰")) return 0xFF6B7280;
        int[] palette = new int[]{
            0xFF0F766E, 0xFF7C3AED, 0xFFEA580C, 0xFF0284C7,
            0xFFBE123C, 0xFF65A30D, 0xFF9333EA, 0xFF0891B2
        };
        String seed = item.optString("id", item.optString("displayName", String.valueOf(position)));
        int hash = seed.hashCode();
        if (hash == Integer.MIN_VALUE) hash = 0;
        return palette[Math.abs(hash) % palette.length];
    }

    private Integer parseColor(String value) {
        String color = value.trim();
        if (color.matches("#?[0-9A-Fa-f]{6}")) {
            if (!color.startsWith("#")) color = "#" + color;
            return 0xFF000000 | Integer.parseInt(color.substring(1), 16);
        }
        if (color.matches("#?[0-9A-Fa-f]{8}")) {
            if (color.startsWith("#")) color = color.substring(1);
            return (int) Long.parseLong(color, 16);
        }
        return null;
    }

    private boolean isLightColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) > 186000;
    }

    private class ProfileSpinnerAdapter extends BaseAdapter {
        @Override public int getCount() {
            return profiles == null ? 0 : profiles.length();
        }

        @Override public Object getItem(int position) {
            try {
                return profiles.getJSONObject(position);
            } catch (JSONException exc) {
                return null;
            }
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return profileSpinnerView(position, false);
        }

        @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return profileSpinnerView(position, true);
        }
    }

    private int templateId() throws JSONException {
        return profile.getJSONObject("template").getInt("id");
    }

    private String selectedGrade() {
        if (!hasMultipleGradeChoices()) return firstGradeKey();
        int id = gradeGroup.getCheckedRadioButtonId();
        if (id == -1) return "";
        String grade = id == 'B' ? "B" : id == 'C' ? "C" : "A";
        return hasGrade(grade) ? grade : firstGradeKey();
    }

    private void resetGradeSelection() {
        if (gradeGroup == null || !hasMultipleGradeChoices()) return;
        gradeGroup.clearCheck();
        updateGradeButtons();
    }

    private void updateGradeButtons() {
        if (gradeGroup == null) return;
        int checkedId = gradeGroup.getCheckedRadioButtonId();
        for (int i = 0; i < gradeGroup.getChildCount(); i++) {
            View child = gradeGroup.getChildAt(i);
            if (!(child instanceof RadioButton)) continue;
            RadioButton radio = (RadioButton) child;
            boolean enabled = hasGrade(String.valueOf((char) radio.getId()));
            boolean selected = enabled && checkedId == radio.getId();
            radio.setEnabled(enabled);
            radio.setVisibility(enabled ? View.VISIBLE : View.GONE);
            styleGradeButton(radio, selected);
        }
    }

    private void styleGradeButton(RadioButton radio, boolean selected) {
        String grade = String.valueOf((char) radio.getId());
        int color = gradeColor(grade);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(8));
        bg.setColor(selected ? color : gradeBgColor(grade));
        bg.setStroke(dp(2), selected ? color : lightenColor(color));
        radio.setBackground(bg);
        radio.setTextColor(selected ? 0xFFFFFFFF : 0xFF334155);
    }

    private int gradeColor(String grade) {
        if ("A".equals(grade)) return 0xFFDC2626;
        if ("B".equals(grade)) return 0xFF16A34A;
        if ("C".equals(grade)) return 0xFF64748B;
        return 0xFF0F766E;
    }

    private int gradeBgColor(String grade) {
        if ("A".equals(grade)) return 0xFFFFEEEE;
        if ("B".equals(grade)) return 0xFFEAF7EE;
        if ("C".equals(grade)) return 0xFFF1F5F9;
        return 0xFFF0FDFA;
    }

    private boolean requiresSecondSn() {
        return profile != null && profile.optBoolean("requiresSecondSn", false);
    }

    /** Slot-mode profiles describe N upload boxes via "photoSlots"; null means legacy front/back. */
    private JSONArray photoSlots() {
        JSONArray slots = profile == null ? null : profile.optJSONArray("photoSlots");
        return slots != null && slots.length() > 0 ? slots : null;
    }

    /** snPlugins: the ordered top scan/input boxes (机器SN/基站SN/彩盒SN/装箱号/…). Null for legacy
     *  profiles, which keep the hardcoded 机器SN + optional 基站SN rendering. */
    private JSONArray snPlugins() {
        JSONArray a = profile == null ? null : profile.optJSONArray("snPlugins");
        return a != null && a.length() > 0 ? a : null;
    }
    /** Extra plugins beyond primary(机器SN)/secondary(基站SN) — i.e. 彩盒SN/装箱号/custom. These are the
     *  ones the app didn't render before; primary/secondary keep their existing dedicated rows. */
    private boolean isExtraPluginKey(String key) {
        return key != null && !"primary".equals(key) && !"secondary".equals(key);
    }

    private boolean isSlotMode() {
        return photoSlots() != null;
    }

    // Pick the operator's language for a title-bearing JSON object: the zh source string lives in
    // baseKey (e.g. "title"/"name"/"label"); an OPTIONAL sibling map in i18nKey (e.g. "titleI18n")
    // holds {"en":…,"es":…}. Old profiles have no i18n map → fall back to the zh string. Only the
    // active non-zh language is consulted; an empty/absent translation also falls back to zh.
    private String localized(JSONObject obj, String baseKey, String i18nKey) {
        String zh = obj.optString(baseKey, "");
        if (!"zh".equals(lang)) {
            JSONObject m = obj.optJSONObject(i18nKey);
            String v = m == null ? "" : m.optString(lang, "");
            if (!v.isEmpty()) return v;
        }
        return zh;
    }

    private String slotTitleForField(String field) {
        JSONArray slots = photoSlots();
        for (int s = 0; slots != null && s < slots.length(); s++) {
            JSONObject slot = slots.optJSONObject(s);
            if (slot != null && field.equals(slot.optString("field"))) {
                String title = localized(slot, "title", "titleI18n");
                return title.isEmpty() ? field : title;
            }
        }
        return field;
    }

    // Short label for the 队列 photo buttons — the full slot title (e.g.「请上传机器人和基站外观图片（最多可
    // 上传十张）」) is far too long for a button. Strip「请上传」前缀、「（…）」括注、结尾「图片/照片」。
    private String shortSlotLabel(String field) {
        String t = slotTitleForField(field);
        // The stripping regex targets zh shapes (请上传…/（…）/图片|照片); for en/es it would at most
        // trim a Latin "(...)" note but never the CJK 图片/照片 suffix, so just return the localized
        // title as-is. Skipping keeps en/es labels intact and untouched by Chinese-specific rules.
        if (!"zh".equals(lang)) return t;
        String s = t.replaceAll("^请?上传", "").replaceAll("（[^）]*）", "").replaceAll("\\([^)]*\\)", "")
            .replaceAll("(图片|照片)\\s*$", "").trim();
        return s.isEmpty() ? t : s;
    }

    private int slotPhotoCount(UnitRecord unit, String field) {
        List<String> photos = unit.slotPhotos.get(field);
        return photos == null ? 0 : photos.size();
    }

    /** First (unit, slotIndex) whose captured count is below the slot's minPhotos; null when all met. */
    private int[] nextSlotStep() {
        JSONArray slots = photoSlots();
        if (slots == null) return null;
        // 「正正反反 / 正反正反」现在对 slot 模式也生效(和旧版 front/back 同义,只是从2个框推广到N个框):
        //  正正反反(fronts_then_backs):一个框拍完所有机器再下一个框——1机1框,2机1框,…,1机2框,2机2框(框在外层)
        //  正反正反(front_back_per_unit):一台机器拍完所有框再下一台——1机1框,1机2框,…,2机1框,2机2框(机器在外层)
        boolean perSlot = "fronts_then_backs".equals(photoOrder);
        if (perSlot) {
            for (int s = 0; s < slots.length(); s++) {
                JSONObject slot = slots.optJSONObject(s);
                if (slot == null) continue;
                int min = slot.optInt("minPhotos", 1);
                String field = slot.optString("field");
                for (int u = 0; u < units.size(); u++) {
                    if (slotPhotoCount(units.get(u), field) < min) return new int[]{u, s};
                }
            }
            return null;
        }
        for (int u = 0; u < units.size(); u++) {
            UnitRecord unit = units.get(u);
            for (int s = 0; s < slots.length(); s++) {
                JSONObject slot = slots.optJSONObject(s);
                if (slot == null) continue;
                int min = slot.optInt("minPhotos", 1);
                if (slotPhotoCount(unit, slot.optString("field")) < min) return new int[]{u, s};
            }
        }
        return null;
    }

    private String slotSummaryText(UnitRecord unit) {
        JSONArray slots = photoSlots();
        StringBuilder sb = new StringBuilder();
        for (int s = 0; slots != null && s < slots.length(); s++) {
            JSONObject slot = slots.optJSONObject(s);
            if (slot == null) continue;
            String field = slot.optString("field");
            int min = slot.optInt("minPhotos", 1);
            int max = slot.optInt("maxPhotos", 0);
            sb.append(" ").append(slotTitleForField(field)).append("=").append(slotPhotoCount(unit, field)).append("/").append(min);
            if (max > 0) sb.append("~").append(max);
        }
        return sb.toString();
    }

    private void clearSlotPhotos(UnitRecord unit, String field) {
        List<String> photos = unit.slotPhotos.remove(field);
        if (photos != null) {
            for (String path : photos) deleteFileQuietly(path);
        }
        refreshFormUi();
        saveDraft();
    }

    private boolean hasMultipleGradeChoices() {
        int count = 0;
        for (String grade : new String[]{"A", "B", "C"}) {
            if (hasGrade(grade)) count++;
        }
        return count > 1;
    }

    private boolean hasGrade(String grade) {
        JSONObject gradeMap = profile == null ? null : profile.optJSONObject("gradeMap");
        return gradeMap != null && gradeMap.has(grade);
    }

    private String firstGradeKey() {
        for (String grade : new String[]{"A", "B", "C"}) {
            if (hasGrade(grade)) return grade;
        }
        // No grade defined (ungraded profile): empty, not "A" — otherwise an ungraded
        // unit would look like an A-class unit and wrongly trigger the A-step previous-steps flow.
        return "";
    }

    private String savedToken() {
        return SecureTokenStore.get(prefs).trim();
    }

    private String webFingerprint() {
        String value = prefs.getString(WEB_FINGERPRINT_KEY, "").trim();
        if (value.length() >= 16) return value;
        value = java.util.UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(WEB_FINGERPRINT_KEY, value).apply();
        return value;
    }

    /** Optional web-client Origin header from the cached panel config, or "" when unset. Never null. */
    private String webOrigin() {
        JSONObject config = appConfig;
        return config == null ? "" : config.optString("webOrigin", "").trim();
    }

    /** Optional web-client Referer header from the cached panel config, or "" when unset. Never null. */
    private String webReferer() {
        JSONObject config = appConfig;
        return config == null ? "" : config.optString("webReferer", "").trim();
    }

    /**
     * Resolve the backend REST paths the {@link Api} client uses, from the panel-provided
     * {@code endpoints} config, with the current literals as defaults (so behavior is unchanged until
     * a deployer overrides one in the panel). Loaded once here — {@code Api} has no Context — and
     * handed to every {@code Api} instance. Reads the on-disk cache via {@link AppConfig}; a missing
     * or blank override falls back to the default, and the whole thing never throws.
     */
    private Api.Endpoints endpoints() {
        JSONObject e = AppConfig.endpoints(this);
        return new Api.Endpoints(
            AppConfig.endpoint(e, "captcha", "/account/getCaptcha"),
            AppConfig.endpoint(e, "loginVerify", "/account/userLoginVerify"),
            AppConfig.endpoint(e, "login", "/account/adminLogin"),
            AppConfig.endpoint(e, "userInfo", "/users/userInfo"),
            AppConfig.endpoint(e, "printerState", "/engineer/message/cloudPrinterState"),
            AppConfig.endpoint(e, "messageList", "/engineer/message/getUserMessageList"),
            AppConfig.endpoint(e, "labelRetry", "/engineer/message/labelPrinterRetry"),
            AppConfig.endpoint(e, "uploadFile", "/images/uploadFile"));
    }

    // ---- Panel backend configuration -----------------------------------------------------------
    // The app has no built-in backend. The backend base comes ONLY from the panel-provided config
    // that was fetched from <panelBase>/api/config and cached to disk (loaded into `appConfig` on
    // start, hot-swapped after a successful refresh). There is NO hardcoded fallback: when it isn't
    // configured yet, apiBase() returns "" and every Api call site skips + prompts instead of
    // hitting a bogus host. This is why login can never silently talk to the wrong server, and why
    // an unconfigured install starts cleanly instead of crashing.

    /** Backend base from the cached panel config, or "" when unconfigured. Never null. */
    private String apiBase() {
        JSONObject config = appConfig;
        if (config != null) {
            String base = config.optString("backendApiBase", "").trim();
            if (!base.isEmpty()) return base;
        }
        return "";
    }

    /** True once a usable backend base is known — the precondition for login and every backend call. */
    private boolean backendConfigured() {
        return !apiBase().isEmpty();
    }

    /** Tell the user no panel/backend is configured yet. Safe to call from any thread; never crashes. */
    private void notifyBackendUnconfigured() {
        runOnUiThread(() -> {
            if (activityAlive()) alert(t("panel_required_title"), t("panel_required_detail"));
        });
    }

    /** Startup: silently pull the latest panel config into cache and hot-swap the in-memory copy. */
    private void refreshAppConfigOnStartup() {
        AppConfig.refresh(this, AppConfig.panelBase(this), AppConfig.catalogKey(this), result -> {
            if (result == null) return;
            appConfig = result; // form is untouched — this only affects base/notify/brand
            // Cold-cache case: onCreate loaded a null appConfig, so the login screen rendered the
            // stale "unconfigured" banner and refreshCaptcha() skipped (backend not yet usable). Now
            // that the config landed while we're logged out with no captcha fetched, re-render (clears
            // the banner) and load the captcha — mirroring refreshAppConfigInteractive(). The
            // captchaClient guard avoids clobbering a captcha the user already fetched manually.
            runOnUiThread(() -> {
                if (activityAlive() && savedToken().isEmpty() && captchaClient.isEmpty()) {
                    showSettingsPage();
                    refreshCaptcha();
                }
            });
        });
    }

    /**
     * After the user saves a new panel address: pull the config, and the moment it lands make the
     * login screen usable (no app restart needed) by hot-swapping `appConfig` and reloading the
     * captcha. A failed fetch just reports back — the app stays in the safe "unconfigured" state.
     */
    private void refreshAppConfigInteractive() {
        AppConfig.refresh(this, AppConfig.panelBase(this), AppConfig.catalogKey(this), result -> {
            if (result != null) appConfig = result;
            runOnUiThread(() -> {
                if (!activityAlive()) return;
                if (result != null) {
                    toast(t("panel_connected"));
                    if (savedToken().isEmpty()) {
                        showSettingsPage(); // re-render: the "unconfigured" banner clears
                        refreshCaptcha();   // now backendConfigured() → the captcha actually loads
                    }
                } else {
                    toast(t("panel_connect_failed"));
                }
            });
        });
    }

    /** Persist the panel address + access key, then (re)connect: fetch config + re-sync the catalog. */
    private void savePanelConnection(String panelBaseInput, String catalogKeyInput) {
        String base = panelBaseInput == null ? "" : panelBaseInput.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String key = catalogKeyInput == null ? "" : catalogKeyInput.trim();
        SharedPreferences.Editor editor = prefs.edit();
        if (base.isEmpty()) editor.remove(AppConfig.KEY_PANEL_BASE); else editor.putString(AppConfig.KEY_PANEL_BASE, base);
        if (key.isEmpty()) editor.remove(AppConfig.KEY_CATALOG_KEY); else editor.putString(AppConfig.KEY_CATALOG_KEY, key);
        editor.apply();
        toast(t("saved"));
        if (base.isEmpty()) {
            // Cleared: drop the in-memory backend and return to the unconfigured state immediately.
            appConfig = null;
            showSettingsPage();
            return;
        }
        appendLog(t("panel_connecting"));
        refreshAppConfigInteractive();
        // Re-sync the catalog against the new panel. A fresh instance bypasses the once-per-process
        // guard so the change takes effect now; on any failure it keeps the cached/seed catalog.
        formCatalogManager = new FormCatalogManager(this);
        formCatalogManager.checkOnStartup();
    }

    /** Brand shown in the UI: the panel's {@code brand} if provided, else "" (no built-in brand). */
    private String brandName() {
        JSONObject config = appConfig;
        return config == null ? "" : config.optString("brand", "").trim();
    }

    /** Prefix a display string with the panel's brand when one is configured. Null-safe; when no
     *  brand is set the value is returned unchanged. */
    private String applyBrand(String value) {
        if (value == null) return null;
        String brand = brandName();
        return brand.isEmpty() ? value : brand + " " + value;
    }

    private boolean ensureCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMISSION);
            toast(t("allow_camera"));
            return false;
        }
        return true;
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSION);
    }

    private Bitmap decodeCaptcha(String captcha) {
        try {
            String encoded = captcha == null ? "" : captcha;
            int comma = encoded.indexOf(',');
            if (comma >= 0) encoded = encoded.substring(comma + 1);
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception exc) {
            appendLog(t("captcha_decode_failed") + exc.getMessage());
            return null;
        }
    }

    private String lastCrashText() {
        String crash = Diagnostics.readCrash(this);
        String recent = Diagnostics.readLog(this);
        if (crash.isEmpty() && recent.isEmpty()) return t("no_last_crash");
        if (crash.isEmpty()) return t("no_last_crash") + "\n\n" + t("diagnostic_log_title") + "\n" + recent;
        if (recent.isEmpty()) return crash;
        return crash + "\n\n" + t("diagnostic_log_title") + "\n" + recent;
    }

    private void notifyMissing(String sn, List<String> codes) {
        List<String> labels = new ArrayList<>();
        for (String code : codes) labels.add(materialLabel(code));
        String message = sn + " " + t("missing_material") + ": " + join(labels, ", ");
        appendLog(message);
        if (missingMaterialNoticeShown) {
            appendLog(t("missing_notice_once"));
            return;
        }
        missingMaterialNoticeShown = true;
        runOnUiThread(() -> autoDismissAlert(t("missing_material_notice"), message + "\n" + t("missing_retry_note"), 3000));
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        builder.setContentTitle(t("missing_material_notice"))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true);
        manager.notify(1, builder.build());
    }

    private String materialLabel(String code) {
        try {
            JSONArray groups = profile.optJSONArray("materialGroups");
            for (int i = 0; groups != null && i < groups.length(); i++) {
                JSONArray materials = groups.getJSONObject(i).optJSONArray("materials");
                for (int j = 0; materials != null && j < materials.length(); j++) {
                    JSONObject material = materials.getJSONObject(j);
                    if (code.equals(material.optString("code"))) {
                        // 物料 name 已含料号（如「滚刷 毛刷 | T2351V11-92」），不再附 (code)——
                        // 那是带 MR_ 前缀的同一料号，重复。en/es 走 nameI18n 兄弟映射，缺失回退 zh name。
                        String name = localized(material, "name", "nameI18n");
                        return name.isEmpty() ? code : name;
                    }
                }
            }
        } catch (JSONException ignored) {
        }
        return code;
    }

    // Effective notify webhook, in precedence order: (1) the panel-managed catalog
    // settings.notifyWebhook (single source of truth once migrated), (2) the panel /api/config
    // notifyWebhook (AppConfig), (3) the legacy per-device prefs value (offline before first sync).
    // Any empty level falls through; there is NO hardcoded default (empty → notifications skipped).
    private String savedNotifyWebhook() {
        if (catalogSettings != null) {
            String fromCatalog = catalogSettings.optString("notifyWebhook", "").trim();
            if (!fromCatalog.isEmpty()) return fromCatalog;
        }
        JSONObject config = appConfig;
        if (config != null) {
            String fromPanel = config.optString("notifyWebhook", "").trim();
            if (!fromPanel.isEmpty()) return fromPanel;
        }
        String fromPrefs = prefs.getString("notifyWebhook", "").trim();
        if (!fromPrefs.isEmpty()) return fromPrefs;
        return "";
    }

    // Mask a webhook for read-only display: keep everything up to and including "…/hook/" plus the
    // last 4 chars of the token, star out the middle. Short strings just keep head+tail.
    private String maskWebhook(String url) {
        if (url == null || url.isEmpty()) return "—";
        int hook = url.indexOf("/hook/");
        String head = hook >= 0 ? url.substring(0, hook + "/hook/".length()) : "";
        String token = hook >= 0 ? url.substring(hook + "/hook/".length()) : url;
        if (token.length() <= 4) return head + token;
        return head + "****" + token.substring(token.length() - 4);
    }

    // 一轮结束后向通知 webhook 推送：录入汇总（每轮必发）+ 报错（仅本轮有失败/网络故障时单独再发一条）。消息正文固定中文。
    private void notifyRoundToNotify(boolean success, int submitted, List<String> errors, List<String> inlineFailed, String dnsWarning) {
        if (savedNotifyWebhook().isEmpty()) return;
        boolean hasUnprinted = inlineFailed != null && !inlineFailed.isEmpty();
        String profileId = profile == null ? "" : profile.optString("id", "");
        String model = profile == null ? "" : profile.optString("model", profileId);
        if (model.isEmpty()) model = "未选机型";

        LinkedHashMap<String, LinkedHashSet<String>> snapshot = new LinkedHashMap<>();
        synchronized (roundMissingMaterials) {
            for (Map.Entry<String, LinkedHashSet<String>> entry : roundMissingMaterials.entrySet()) {
                snapshot.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }
        Set<String> thisRound = snapshot.keySet();
        Set<String> prevRound = loadPrevRoundMissing(profileId);
        List<String> added = new ArrayList<>();
        for (String code : thisRound) if (!prevRound.contains(code)) added.add(code);
        List<String> recovered = new ArrayList<>();
        for (String code : prevRound) if (!thisRound.contains(code)) recovered.add(code);
        savePrevRoundMissing(profileId, thisRound);

        String divider = "————————————";
        StringBuilder sb = new StringBuilder();
        sb.append("本轮（").append(model).append("）录入 ").append(submitted).append(" 台");
        if (hasUnprinted) sb.append("\n⚠️ 其中 ").append(inlineFailed.size()).append(" 台提交成功但没出标签，需补打/手写");
        sb.append("\n").append(divider);
        sb.append("\n缺少的物料：");
        if (snapshot.isEmpty()) {
            sb.append("无");
        } else {
            // 按缺料台数分组：台数相同的物料归到同一个「缺 N 台」标题下，下面用序号列物料名
            // （不再每行重复台数）。台数多的组排在前面。
            LinkedHashMap<Integer, List<String>> byCount = new LinkedHashMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> entry : snapshot.entrySet()) {
                int n = entry.getValue().size();
                List<String> mats = byCount.get(n);
                if (mats == null) {
                    mats = new ArrayList<>();
                    byCount.put(n, mats);
                }
                mats.add(materialLabel(entry.getKey()));
            }
            List<Integer> counts = new ArrayList<>(byCount.keySet());
            counts.sort((a, b) -> b - a);
            for (int n : counts) {
                sb.append("\n缺 ").append(n).append(" 台：");
                List<String> mats = byCount.get(n);
                for (int i = 0; i < mats.size(); i++) {
                    sb.append("\n  ").append(i + 1).append(". ").append(mats.get(i));
                }
            }
        }
        sb.append("\n").append(divider);
        sb.append("\n跟上一轮比：");
        sb.append("\n  新增缺料：").append(added.isEmpty() ? "无" : labelList(added));
        sb.append("\n  已恢复：").append(recovered.isEmpty() ? "无" : labelList(recovered));
        sb.append("\n").append(divider);
        sb.append("\n").append(notifyFooter());
        postNotify(sb.toString());

        if (!success || !dnsWarning.isEmpty() || hasUnprinted) {
            StringBuilder err = new StringBuilder();
            err.append("本轮（").append(model).append("）报错");
            err.append("\n").append(divider);
            if (errors != null && !errors.isEmpty()) {
                err.append("\n失败：");
                for (String line : errors) err.append("\n  ").append(line);
            }
            if (hasUnprinted) {
                err.append("\n提交成功但没出标签（需补打/手写）：");
                for (String sn : inlineFailed) err.append("\n  ").append(sn);
            }
            if (!dnsWarning.isEmpty()) {
                err.append("\nDNS/网络受影响：\n").append(dnsWarning);
            }
            err.append("\n").append(divider);
            err.append("\n").append(notifyFooter());
            postNotify(err.toString());
        }
    }

    private String labelList(List<String> codes) {
        List<String> labels = new ArrayList<>();
        for (String code : codes) labels.add(materialLabel(code));
        return join(labels, "、");
    }

    private String notifyFooter() {
        String account = prefs.getString("userName", "");
        if (account.isEmpty()) account = prefs.getString("account", "");
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        return account.isEmpty() ? time : "账号：" + account + "  " + time;
    }

    private Set<String> loadPrevRoundMissing(String profileId) {
        Set<String> out = new HashSet<>();
        try {
            String raw = prefs.getString("prevRoundMissing_" + profileId, "");
            if (raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String code = arr.optString(i, "");
                if (!code.isEmpty()) out.add(code);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void savePrevRoundMissing(String profileId, Set<String> codes) {
        JSONArray arr = new JSONArray();
        for (String code : codes) arr.put(code);
        prefs.edit().putString("prevRoundMissing_" + profileId, arr.toString()).apply();
    }

    // ---- Local round ledger: source of truth for print reconciliation (see ROUND_LEDGER_KEY) ----
    private JSONObject ledgerUnit(String sn, boolean submitOk, boolean printed, String grade) {
        JSONObject o = new JSONObject();
        try {
            o.put("sn", sn == null ? "" : sn);
            o.put("submit", submitOk ? "ok" : "failed");
            o.put("printed", submitOk ? (printed ? "ok" : "unconfirmed") : "na");
            // Persist the unit's grade so the print-check ledger can show A/B/C next to the SN —
            // a "查无打印任务" unit gets hand-written, and the writer shouldn't have to look the grade up.
            if (grade != null && !grade.trim().isEmpty()) o.put("grade", grade.trim().toUpperCase(java.util.Locale.US));
        } catch (JSONException ignored) {}
        return o;
    }

    private void saveRoundToLedger(List<JSONObject> roundUnits) {
        if (roundUnits == null || roundUnits.isEmpty()) return;
        try {
            long now = System.currentTimeMillis();
            JSONObject round = new JSONObject();
            round.put("ts", now);
            round.put("tsText", new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(now)));
            round.put("profileId", currentProfileId());
            JSONArray arr = new JSONArray();
            for (JSONObject u : roundUnits) arr.put(u);
            round.put("units", arr);
            JSONArray ledger = loadLedgerArray();
            ledger.put(round);
            prefs.edit().putString(ROUND_LEDGER_KEY, pruneLedger(ledger, now).toString()).apply();
        } catch (Exception exc) {
            appendLog("round ledger save failed: " + exc.getMessage());
        }
    }

    private JSONArray loadLedgerArray() {
        try { return new JSONArray(prefs.getString(ROUND_LEDGER_KEY, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    // Drop rounds older than the 3-day retention window so the ledger can't grow without bound.
    private JSONArray pruneLedger(JSONArray ledger, long now) {
        JSONArray kept = new JSONArray();
        for (int i = 0; ledger != null && i < ledger.length(); i++) {
            JSONObject r = ledger.optJSONObject(i);
            if (r == null) continue;
            if (now - r.optLong("ts", 0) <= ROUND_LEDGER_RETAIN_MS) kept.put(r);
        }
        return kept;
    }

    // Most-recent rounds first, capped at `max` (e.g. 3 for the reconcile view).
    private List<JSONObject> loadRecentRounds(int max) {
        JSONArray ledger = pruneLedger(loadLedgerArray(), System.currentTimeMillis());
        List<JSONObject> out = new ArrayList<>();
        for (int i = ledger.length() - 1; i >= 0 && out.size() < max; i--) {
            JSONObject r = ledger.optJSONObject(i);
            if (r != null) out.add(r);
        }
        return out;
    }

    // After a cloud verify resolves a still-"unconfirmed" unit to confirmed-printed (cloudStatus==1), upgrade
    // it to printed="ok" in the persisted ledger so it stays green and isn't re-queried on the next open.
    // Only ever upgrades unconfirmed -> ok on a definitive status==1; never downgrades, never touches
    // submit/na — a monotonic, lossless resolution. Matches the in-memory rounds back to the ledger by ts+sn.
    private void persistLedgerPrintedOk(List<JSONObject> rounds) {
        if (rounds == null || rounds.isEmpty()) return;
        try {
            JSONArray ledger = loadLedgerArray();
            boolean changed = false;
            for (JSONObject r : rounds) {
                long ts = r.optLong("ts", 0);
                JSONArray us = r.optJSONArray("units");
                for (int i = 0; us != null && i < us.length(); i++) {
                    JSONObject u = us.optJSONObject(i);
                    if (u == null || u.optInt("cloudStatus", Integer.MIN_VALUE) != 1) continue;
                    if ("ok".equals(u.optString("printed"))) continue;
                    u.put("printed", "ok"); // keep the in-memory round consistent with what we persist
                    if (markLedgerUnitPrinted(ledger, ts, u.optString("sn", ""))) changed = true;
                }
            }
            if (changed) prefs.edit().putString(ROUND_LEDGER_KEY, ledger.toString()).apply();
        } catch (Exception exc) {
            appendLog("ledger resolve save failed: " + exc.getMessage());
        }
    }

    private boolean markLedgerUnitPrinted(JSONArray ledger, long ts, String sn) {
        if (sn == null || sn.isEmpty()) return false;
        try {
            for (int i = 0; ledger != null && i < ledger.length(); i++) {
                JSONObject r = ledger.optJSONObject(i);
                if (r == null || r.optLong("ts", 0) != ts) continue;
                JSONArray us = r.optJSONArray("units");
                for (int j = 0; us != null && j < us.length(); j++) {
                    JSONObject u = us.optJSONObject(j);
                    if (u == null || !sn.equals(u.optString("sn", ""))) continue;
                    if ("ok".equals(u.optString("printed"))) return false;
                    u.put("printed", "ok");
                    return true;
                }
            }
        } catch (JSONException ignored) {}
        return false;
    }

    private void postNotify(String text) {
        String url = savedNotifyWebhook();
        if (url.isEmpty()) {
            appendLog(t("notify_disabled"));
            return;
        }
        new Thread(() -> {
            try {
                JSONObject content = new JSONObject();
                content.put("text", text);
                JSONObject body = new JSONObject();
                body.put("msg_type", "text");
                body.put("content", content);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
                int code = conn.getResponseCode();
                InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
                String resp = readStream(stream);
                conn.disconnect();
                if (code == 200 && resp.contains("\"code\":0")) {
                    appendLog(t("notify_sent"));
                } else {
                    appendLog(t("notify_failed") + " HTTP " + code + " " + resp);
                    Diagnostics.append(this, "Notify send failed HTTP " + code + ": " + resp);
                }
            } catch (Exception exc) {
                appendLog(t("notify_failed") + " " + conciseError(exc));
                Diagnostics.append(this, "Notify send error: " + exc);
            }
        }).start();
    }

    private static String readStream(InputStream stream) {
        if (stream == null) return "";
        try (InputStream in = stream) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } catch (Exception exc) {
            return "";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Material shortage", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private View dailyStatsView() {
        String date = todayStatsDate();
        JSONObject stats = loadDailyStats(date);
        int a = stats.optInt("A", 0);
        int b = stats.optInt("B", 0);
        int c = stats.optInt("C", 0);
        int total = a + b + c;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelParams.setMargins(0, dp(14), 0, 0);
        panel.setLayoutParams(panelParams);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF8FAFC);
        bg.setStroke(dp(1), 0xFFE2E8F0);
        bg.setCornerRadius(dp(10));
        panel.setBackground(bg);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(t("today_stats_title"), 18, true);
        title.setTextColor(0xFF0F172A);
        heading.addView(title);

        TextView subtitle = text(date + "  " + t("today_total") + total, 12, false);
        subtitle.setTextColor(0xFF64748B);
        subtitle.setPadding(0, dp(2), 0, 0);
        heading.addView(subtitle);
        header.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        loginStatus = text("", 16, true);
        loginStatus.setGravity(Gravity.CENTER);
        loginStatus.setTextColor(0xFF0F766E);
        loginStatus.setPadding(dp(12), 0, dp(12), 0);
        loginStatus.setMinHeight(dp(34));
        loginStatus.setSingleLine(true);
        loginStatus.setEllipsize(TextUtils.TruncateAt.END);
        loginStatus.setMaxWidth(dp(190));
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(0xFFECFDF5);
        statusBg.setStroke(dp(1), 0xFF99F6E4);
        statusBg.setCornerRadius(dp(15));
        loginStatus.setBackground(statusBg);
        header.addView(loginStatus);
        panel.addView(header);

        LinearLayout cards = row();
        LinearLayout.LayoutParams cardsParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardsParams.setMargins(0, dp(12), 0, 0);
        cards.addView(statCard("A", a, 0xFFDC2626, 0xFFFFEEEE), statCardParams());
        cards.addView(statCard("B", b, 0xFF16A34A, 0xFFEAF7EE), statCardParams());
        cards.addView(statCard("C", c, 0xFF64748B, 0xFFF1F5F9), statCardParams());
        panel.addView(cards, cardsParams);
        return panel;
    }

    private LinearLayout.LayoutParams statCardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(76), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private View statCard(String grade, int count, int color, int bgColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(4), dp(6), dp(4), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setStroke(dp(1), lightenColor(color));
        bg.setCornerRadius(dp(8));
        card.setBackground(bg);

        TextView countText = text(String.valueOf(count), 26, true);
        countText.setTextColor(color);
        countText.setGravity(Gravity.CENTER);
        card.addView(countText);

        TextView labelText = text(grade + t("grade_suffix"), 13, true);
        labelText.setTextColor(0xFF334155);
        labelText.setGravity(Gravity.CENTER);
        card.addView(labelText);
        return card;
    }

    private int lightenColor(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        r = r + (int) ((255 - r) * 0.55f);
        g = g + (int) ((255 - g) * 0.55f);
        b = b + (int) ((255 - b) * 0.55f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void recordDailyOutput(UnitRecord unit) {
        if (unit == null || unit.sn == null || unit.sn.isEmpty()) return;
        String grade = unit.grade == null ? "" : unit.grade.trim().toUpperCase();
        if (!"A".equals(grade) && !"B".equals(grade) && !"C".equals(grade)) return;
        try {
            String date = todayStatsDate();
            JSONObject stats = loadDailyStats(date);
            JSONArray counted = stats.optJSONArray("counted");
            if (counted == null) counted = new JSONArray();
            String profileId = profile == null ? "" : profile.optString("id", "");
            String key = profileId + "|" + unit.sn;
            if (jsonArrayContains(counted, key)) return;
            counted.put(key);
            stats.put("counted", counted);
            stats.put(grade, stats.optInt(grade, 0) + 1);
            prefs.edit().putString(DAILY_STATS_PREFIX + date, stats.toString()).apply();
        } catch (Exception exc) {
            appendLog(t("daily_stats_save_failed") + exc.getMessage());
        }
    }

    private JSONObject loadDailyStats(String date) {
        String raw = prefs.getString(DAILY_STATS_PREFIX + date, "");
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException exc) {
            return new JSONObject();
        }
    }

    private boolean jsonArrayContains(JSONArray array, String value) {
        for (int i = 0; array != null && i < array.length(); i++) {
            if (value.equals(array.optString(i))) return true;
        }
        return false;
    }

    private String todayStatsDate() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    private LinearLayout rootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(24));
        return root;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(10), 0, 0);
        panel.setLayoutParams(params);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(dp(1), 0xFFE2E8F0);
        bg.setCornerRadius(dp(10));
        panel.setBackground(bg);
        return panel;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView label(String value) {
        TextView text = text(value, 17, true);
        text.setTextColor(0xFF0F172A);
        text.setPadding(0, dp(16), 0, dp(6));
        return text;
    }

    private TextView compactLabel(String value) {
        TextView text = text(value, 15, true);
        text.setTextColor(0xFF0F172A);
        text.setPadding(0, dp(6), 0, dp(6));
        return text;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        if (bold) text.setTypeface(Typeface.DEFAULT_BOLD);
        return text;
    }

    private EditText edit(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextSize(18);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setSelectAllOnFocus(true);
        edit.setMinHeight(dp(48));
        edit.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(4), 0, dp(8));
        edit.setLayoutParams(params);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF8FAFC);
        bg.setStroke(dp(1), 0xFFCBD5E1);
        bg.setCornerRadius(dp(8));
        edit.setBackground(bg);
        return edit;
    }

    private Button button(String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(16);
        button.setTextColor(0xFF0F172A);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        button.setLayoutParams(params);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFEFF6FF);
        bg.setStroke(dp(1), 0xFFBFDBFE);
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);
        button.setOnClickListener(listener);
        return button;
    }

    private Button iconButton(String title, View.OnClickListener listener) {
        Button button = button(title, listener);
        button.setTextSize(22);
        button.setMinWidth(dp(48));
        button.setMinHeight(dp(44));
        button.setPadding(0, 0, 0, dp(2));
        return button;
    }

    private View scanIconButton(View.OnClickListener listener) {
        ScanIconButton button = new ScanIconButton(this);
        button.setContentDescription(t("rescan_sn"));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(54), dp(48));
        params.setMargins(dp(8), dp(4), 0, dp(8));
        button.setLayoutParams(params);
        return button;
    }

    private ArrayAdapter<String> largeSpinnerAdapter(String[] items) {
        return largeSpinnerAdapter(java.util.Arrays.asList(items));
    }

    private ArrayAdapter<String> largeSpinnerAdapter(List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(18);
                view.setMinHeight(dp(48));
                view.setGravity(Gravity.CENTER_VERTICAL);
                return view;
            }

            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextSize(20);
                view.setMinHeight(dp(52));
                return view;
            }
        };
    }

    /** Many dialogs are shown from {@code runOnUiThread} callbacks that fire after a
     *  background thread finishes. If the activity finished/was destroyed meanwhile,
     *  {@code AlertDialog.show()} throws {@link android.view.WindowManager.BadTokenException}.
     *  Guard every post-async dialog with this. */
    private boolean activityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private void alert(String title, String message) {
        if (!activityAlive()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    private void autoDismissAlert(String title, String message, long millis) {
        if (!activityAlive()) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        View root = getWindow() == null ? null : getWindow().getDecorView();
        if (root != null) {
            root.postDelayed(() -> {
                try {
                    if (dialog.isShowing()) dialog.dismiss();
                } catch (Exception ignored) {
                }
            }, millis);
        }
    }

    private void showScannedSnPreview(String sn, String label) {
        if (sn == null || sn.isEmpty()) return;
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF20F172A);
        bg.setStroke(dp(1), 0xFF22C55E);
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);

        TextView title = text(label, 12, true);
        title.setTextColor(0xFFBBF7D0);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView value = text(sn, 19, true);
        value.setTextColor(0xFFFFFFFF);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(false);
        value.setTextIsSelectable(false);
        box.addView(value);

        PopupWindow popup = new PopupWindow(
            box,
            Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(360)),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            false
        );
        popup.setClippingEnabled(true);
        try {
            popup.showAtLocation(decor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(72));
        } catch (Exception exc) {
            Diagnostics.append(this, "SN preview failed: " + exc.getMessage());
            return;
        }
        decor.postDelayed(() -> {
            try {
                if (popup.isShowing()) popup.dismiss();
            } catch (Exception ignored) {
            }
        }, 3000);
    }

    private void fatal(String message) {
        setContentView(text(message, 16, true));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void log(String message) {
        runOnUiThread(() -> setLogText(message));
    }

    private void appendLog(String message) {
        FailureReporter.breadcrumb(message);
        runOnUiThread(() -> {
            if (logText == null) return;
            CharSequence currentText = logText.getText();
            String current = currentText == null ? "" : currentText.toString();
            setLogText(current.isEmpty() ? message : current + "\n" + message);
        });
    }

    private void appendUnitLog(UnitRecord unit, String message) {
        appendLog(unitLogLine(unit, message));
    }

    private String unitLogLine(UnitRecord unit, String message) {
        if (unit == null) return message == null ? "" : message;
        String sn = unit.sn == null ? "" : unit.sn;
        return "#" + unit.sequence + " SN=" + sn + " | " + (message == null ? "" : message);
    }

    private void setLogText(String message) {
        if (logText != null) logText.setText(styledLogText(message));
    }

    private CharSequence styledLogText(String message) {
        SpannableStringBuilder styled = new SpannableStringBuilder(message == null ? "" : message);
        applyLogPatternSpan(styled, LOG_SEQUENCE_PATTERN, 0, 0xFF2563EB, true);
        applyLogPatternSpan(styled, LOG_SN_ASSIGNMENT_PATTERN, 1, 0xFFDC2626, true);
        applyLogPatternSpan(styled, MATERIAL_CODE_PATTERN, 0, 0xFFB45309, true);
        Matcher matcher = LOG_SN_TOKEN_PATTERN.matcher(styled.toString());
        while (matcher.find()) {
            String value = matcher.group();
            if (!isLikelyLogSn(value)) continue;
            styled.setSpan(new ForegroundColorSpan(0xFFDC2626), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    private void applyLogPatternSpan(SpannableStringBuilder styled, Pattern pattern, int group, int color, boolean bold) {
        Matcher matcher = pattern.matcher(styled.toString());
        while (matcher.find()) {
            int start = group <= 0 ? matcher.start() : matcher.start(group);
            int end = group <= 0 ? matcher.end() : matcher.end(group);
            if (start < 0 || end <= start) continue;
            styled.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) styled.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private boolean isLikelyLogSn(String value) {
        if (value == null || value.length() < 8 || value.length() > 32) return false;
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 'A' && ch <= 'Z') hasLetter = true;
            if (ch >= '0' && ch <= '9') hasDigit = true;
        }
        return hasLetter && hasDigit;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String languageLabel(String value) {
        if ("zh".equals(value)) return ("zh".equals(lang) ? "✓ " : "") + "中文";
        if ("en".equals(value)) return ("en".equals(lang) ? "✓ " : "") + "English";
        return ("es".equals(lang) ? "✓ " : "") + "Español";
    }

    private String languageName(String value) {
        if ("zh".equals(value)) return "\u4e2d\u6587";
        if ("en".equals(value)) return "English";
        return "Espa\u00f1ol";
    }

    private String sideName(String side) {
        return "front".equals(side) ? t("front") : t("back");
    }

    private String t(String key) {
        if ("en".equals(lang)) return en(key);
        if ("es".equals(lang)) return es(key);
        return zh(key);
    }

    private String zh(String key) {
        switch (key) {
            case "session_expired_title": return "\u767b\u5f55\u5df2\u5931\u6548";
            case "session_expired_detail": return "\u540e\u7aef\u8d26\u53f7\u5df2\u5728\u522b\u5904\u767b\u5f55\u6216\u767b\u5f55\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55\u3002";
            case "delete_unit": return "\u5220\u9664\u672c\u53f0";
            case "q_status_pending": return "\u5f85\u63d0\u4ea4";
            case "q_status_submitted": return "\u5df2\u63d0\u4ea4";
            case "q_status_exists": return "\u5df2\u5b58\u5728";
            case "q_status_failed": return "\u5931\u8d25";
            case "photo": return "\u62cd\u7167";
            case "all_photos_done": return "\u7167\u7247\u5df2\u62cd\u6ee1";
            case "form_settings": return "\u8bbe\u7f6e";
            case "payload_display": return "Payload \u663e\u793a";
            case "queue_backup": return "\u961f\u5217\u5907\u4efd\uff08\u4fdd\u5b58/\u6062\u590d\uff09";
            case "queue_backup_save": return "\u4fdd\u5b58\u5f53\u524d\u961f\u5217";
            case "queue_backup_restore": return "\u6062\u590d\u961f\u5217";
            case "queue_backup_empty": return "\u5f53\u524d\u961f\u5217\u4e3a\u7a7a\uff0c\u65e0\u6cd5\u4fdd\u5b58";
            case "queue_backup_save_failed": return "\u961f\u5217\u4fdd\u5b58\u5931\u8d25\uff1a";
            case "queue_backup_saved": return "\u5df2\u4fdd\u5b58\u5f53\u524d\u961f\u5217\uff0c\u53f0\u6570\uff1a";
            case "queue_backup_restored": return "\u5df2\u6062\u590d\u961f\u5217\uff0c\u53f0\u6570\uff1a";
            case "queue_backup_none": return "\u5c1a\u672a\u4fdd\u5b58\u961f\u5217";
            case "queue_backup_saved_info": return "\u5df2\u4fdd\u5b58\u961f\u5217\uff0c\u53f0\u6570\uff1a";
            case "queue_backup_overwrite_confirm": return "\u5f53\u524d\u5df2\u6709\u672a\u63d0\u4ea4\u7684\u961f\u5217\uff0c\u6062\u590d\u4f1a\u8986\u76d6\u73b0\u6709\u961f\u5217\uff0c\u786e\u5b9a\u7ee7\u7eed\uff1f";
            case "close": return "\u5173\u95ed";
            case "submit_batch": return "\u63d0\u4ea4";
            case "submit_loading": return "\u6b63\u5728\u63d0\u4ea4\uff0c\u8bf7\u52ff\u64cd\u4f5c...";
            case "submit_running": return "\u6b63\u5728\u63d0\u4ea4\uff0c\u8bf7\u7a0d\u5019";
            case "current_model": return "\u5f53\u524d\u673a\u578b";
            case "saved_model": return "\u5df2\u4fdd\u5b58\u673a\u578b";
            case "switch_model_to_continue": return "\u5207\u6362\u5230\u8be5\u8868\u5355\u53ef\u7ee7\u7eed\u5f55\u5165";
            case "photo_target_missing": return "\u62cd\u7167\u72b6\u6001\u4e22\u5931\uff0c\u5df2\u4fdd\u7559\u8349\u7a3f\uff0c\u8bf7\u56de\u5230\u5f55\u8868\u9875\u91cd\u8bd5\u3002";
            case "photo_preview_failed": return "\u7167\u7247\u9884\u89c8\u5931\u8d25\uff0c\u539f\u56fe\u6587\u4ef6\u4ecd\u4fdd\u7559\uff0c\u53ef\u7ee7\u7eed\u63d0\u4ea4\u3002";
            case "diagnostic_log_title": return "\u6700\u8fd1\u8bca\u65ad\u8bb0\u5f55";
            case "settings_title": return "自动录表";
            case "saved": return "已保存";
            case "panel_connection": return "面板连接";
            case "panel_connection_hint": return "填写表单系统的面板地址与访问密钥（示例：https://your-panel.example.com），保存后自动连接；两项都留空表示未配置，需先配置才能登录。";
            case "panel_base": return "面板地址";
            case "panel_base_hint": return "例如 https://your-panel.example.com";
            case "catalog_key": return "访问密钥";
            case "catalog_key_hint": return "面板提供的访问密钥（Bearer）";
            case "panel_save": return "保存";
            case "panel_current_api": return "当前后端：";
            case "panel_unconfigured": return "未配置";
            case "panel_required_title": return "请先配置面板";
            case "panel_required_detail": return "请先在设置里填写面板地址和访问密钥，再登录。";
            case "panel_connecting": return "正在连接面板…";
            case "panel_connected": return "面板已连接";
            case "panel_connect_failed": return "面板连接失败，请检查地址和访问密钥";
            case "notify_sent": return "通知已发送";
            case "notify_failed": return "通知发送失败：";
            case "notify_disabled": return "未配置通知 webhook，跳过通知。";
            case "settings_subtitle": return "先选择语言并登录公司账号，再进入录表单。";
            case "language": return "语言";
            case "update_channel": return "更新通道：";
            case "update_channel_stable": return "正式版";
            case "update_channel_beta": return "Beta";
            case "update_channel_beta_toast": return "已切换到 Beta 更新通道，正在检查更新";
            case "update_channel_stable_toast": return "已切换到正式版更新通道，正在检查更新";
            case "login": return "登录";
            case "account": return "公司账号";
            case "password": return "公司密码";
            case "captcha": return "验证码";
            case "refresh_captcha": return "刷新验证码";
            case "login_save": return "登录并进入";
            case "clear_login": return "清除登录";
            case "enter_form": return "进入录表单";
            case "form_title": return "录表单";
            case "current_user": return "当前账号：";
            case "logout": return "登出并返回设置";
            case "form": return "表单";
            case "photo_order": return "照片顺序";
            case "fronts_then_backs": return "正正反反";
            case "front_back_per_unit": return "正反正反";
            case "robot_sn": return "机器 SN";
            case "scan_sn": return "\u626b\u7801/\u8bc6\u522b SN";
            case "ocr_sn": return "拍照识别";
            case "add": return "加入";
            case "base_sn": return "基站SN";
            case "scan_base": return "\u626b\u7801/\u8bc6\u522b\u57fa\u7ad9";
            case "ocr_base": return "拍照识别";
            case "match": return "匹配";
            case "photos": return "拍照";
            case "take_next_photo": return "拍下一张";
            case "a_step_photo": return "\u524d\u4e24\u6b65\u8865\u5f55/\u8dd1\u673a\u6d4b\u8bd5\u7167\u7247";
            case "a_step_short": return "\u524d\u4e24\u6b65\u8865\u56fe";
            case "a_step_take_photo": return "\u62cd\u7167";
            case "choose_gallery_photo": return "\u4ece\u76f8\u518c\u6dfb\u52a0\u56fe\u7247";
            case "gallery_missing_title": return "\u76f8\u518c\u4e0d\u53ef\u7528";
            case "gallery_missing_detail": return "\u7cfb\u7edf\u6ca1\u6709\u53ef\u7528\u7684\u56fe\u7247\u9009\u62e9\u5668\u3002";
            case "a_step_photo_selected": return "\u8dd1\u673a\u6d4b\u8bd5\u7167\u7247\u5df2\u6dfb\u52a0";
            case "next_a_step_photo": return "\u4e0b\u4e00\u5f20\u8dd1\u673a\u6d4b\u8bd5\u7167\u7247 ";
            case "a_step_photos_done": return "\u524d\u4e24\u6b65\u8865\u56fe\u5df2\u5b8c\u6210";
            case "a_step_photo_ready": return "\u5df2\u6dfb\u52a0\uff0c\u524d\u4e24\u6b65\u7f3a\u5931\u65f6\u4f1a\u81ea\u52a8\u8865\u5f55\u3002";
            case "a_step_photo_missing": return "\u8bf7\u5148\u6dfb\u52a0\u8dd1\u673a\u6d4b\u8bd5\u7167\u7247\uff08\u62cd\u7167\u6216\u4ece\u76f8\u518c\uff09\uff0c\u524d\u4e24\u6b65\u7f3a\u5931\u65f6\u9700\u8981\u7528\u5b83\u4e0a\u4f20\u3002";
            case "a_step_entry_title": return "报废录入";
            case "a_step_entry_subtitle": return "外观不良报废：选机型 → 扫码 → 拍照 → 提交";
            case "a_step_entry_photo": return "\u4e0a\u4f20\u7167\u7247\uff08\u53ef\u591a\u5f20\uff09";
            case "a_step_entry_add_photo": return "\u62cd\u7167\u6dfb\u52a0";
            case "a_step_entry_photo_count": return "\u5df2\u6dfb\u52a0\u7167\u7247\uff1a";
            case "a_step_entry_photo_item": return "\u7167\u7247 ";
            case "delete_photo": return "\u5220\u9664";
            case "a_step_entry_submit": return "提交报废录入";
            case "a_step_entry_verdict": return "\u56fa\u5b9a\u63d0\u4ea4\u5185\u5bb9\uff1a\u5916\u89c2\u4e0d\u901a\u8fc7 \u00b7 \u5212\u75d5 \u00b7 \u810f\u6c61 \u00b7 \u65e0\u6cd5\u7ef4\u4fee \u00b7 \u4e0d\u826f\u54c1 \u00b7 \u672a\u505a\u4efb\u4f55\u64cd\u4f5c";
            case "a_step_entry_functional": return "\u52a0\u529f\u80fd\u4e0d\u826f\uff08\u64cd\u4f5c\u6362\u6210\u68c0\u6d4b\uff09";
            case "a_step_entry_functional_fallback": return "\u529f\u80fd\u4e0d\u826f\u8865\u9f50\u5931\u8d25\uff1a\u6a21\u677f\u8be6\u60c5\u4e0d\u53ef\u7528\uff0c\u672c\u5355\u6309\u539f\u56fa\u5b9a\u5185\u5bb9\u63d0\u4ea4";
            case "a_step_entry_clear_sn": return "\u6e05\u9664SN";
            case "a_step_entry_sn_empty": return "\uff08\u672a\u5f55\u5165 SN\uff0c\u626b\u7801\u6216\u624b\u8f93\u540e\u663e\u793a\u5728\u6b64\uff09";
            case "a_step_entry_no_photo": return "\u5c1a\u672a\u62cd\u7167";
            case "a_step_entry_photo_ready": return "\u7167\u7247\u5df2\u5c31\u7eea";
            case "a_step_entry_need_model": return "\u8bf7\u5148\u9009\u62e9\u673a\u578b";
            case "a_step_entry_need_sn": return "\u8bf7\u5148\u626b\u7801\u6216\u8f93\u5165 SN";
            case "a_step_entry_need_photo": return "\u8bf7\u5148\u81f3\u5c11\u62cd\u4e00\u5f20\u7167\u7247";
            case "a_step_entry_done": return "报废录入已提交";
            case "go_back": return "返回";
            case "submit": return "提交";
            case "preview_payload": return "预览 Payload";
            case "check_steps": return "检查前两步";
            case "dry_run": return "只生成 Payload，不提交";
            case "not_logged_in": return "未登录：真实提交前请先登录";
            case "logged_in": return "已登录：";
            case "login_required": return "需要登录";
            case "login_required_detail": return "真实提交前需要先登录公司账号。";
            case "captcha_loading": return "正在获取验证码...";
            case "captcha_ready": return "验证码已刷新。";
            case "captcha_failed": return "验证码获取失败";
            case "login_missing": return "请填写账号、密码、验证码，并先刷新验证码";
            case "login_running": return "正在登录...";
            case "login_failed": return "登录失败";
            case "scan_robot_sn": return "扫描机器 SN";
            case "scan_base_sn": return "扫描基站 SN";
            case "scan_not_sn_title": return "识别到的不像 SN";
            case "scan_not_sn_detail": return "刚才识别到纯数字，很可能是黑色机型上的条码误识别。请用拍照识别或扫码枪重试。";
            case "ocr_unavailable_title": return "图片识别暂不可用";
            case "ocr_unavailable_detail": return "公司用户信息接口没有返回图片识别地址，请确认账号权限或联系维护人员检查 recognizeTextUrl。";
            case "ocr_url_refreshing": return "正在同步图片识别地址...";
            case "ocr_running": return "正在识别图片文字...";
            case "ocr_failed": return "图片文字识别失败";
            case "ocr_no_text_title": return "没有找到 SN";
            case "ocr_no_text_detail": return "未识别到像 SN 的文字，请让 SN 标签更清晰、减少反光后重拍。";
            case "ocr_auto_no_text": return "自动识别没读到 SN，请对准标签后重试或点中间拍照键";
            case "ocr_choose_title": return "选择识别到的 SN";
            case "choose_grade": return "选择等级";
            case "cancel": return "取消";
            case "sn_required": return "SN 不能为空";
            case "duplicate_sn": return "重复 SN: ";
            case "no_base_needed": return "没有需要匹配基站SN的机器";
            case "base_required": return "基站SN 不能为空";
            case "duplicate_base": return "重复基站SN: ";
            case "no_photo_needed": return "没有需要拍的照片";
            case "photo_no_file": return "拍照没有返回文件";
            case "photo_full_file_missing": return "系统相机没有保存原图，请重拍或更换系统相机。";
            case "photo_notice": return "拍照提示";
            case "start_back_photos": return "正面已拍完，开始拍反面。";
            case "photo_save_failed": return "照片保存失败";
            case "no_sn": return "还没有 SN";
            case "payload_failed": return "Payload 生成失败";
            case "checking_steps": return "开始检查前两步...";
            case "check_done": return "检查完成";
            case "steps_ok": return "当前批次前两步检查通过。";
            case "steps_missing_title": return "前两步缺失";
            case "cannot_submit": return "还不能提交";
            case "scan_precheck_missing_detail": return "前两步未录入或SN错误，请重试。";
            case "scan_precheck_retry_title": return "扫码未找到";
            case "scan_precheck_retry_first": return "SN可能错误，请重试。";
            case "scan_precheck_retry_second": return "SN错误或未录第一第二步，请重试。";
            case "scan_precheck_need_run_photo": return "第一步第二步不存在，请上传跑机测试照片。";
            case "scan_precheck_failed": return "前两步即时检查失败: ";
            case "done": return "完成";
            case "dry_run_done": return "Payload 已生成，未提交。";
            case "submit_done": return "批次提交完成。";
            case "submit_done_queue_cleared": return "批次提交完成，设备队列已清空。";
            case "submit_done_check_print": return "提示：标签由云盒打印机异步打印，请点『打印对账』确认是否都出单、给失败的补打。";
            case "submit_aborted_consecutive": return "连续多台失败，已中止本批，请检查网络/账号后重试。";
            case "submit_cancelled_printer_offline": return "已取消提交（打印机未就绪）。";
            case "print_reconcile_title": return "打印对账";
            case "print_reconcile_open": return "打印对账/补打";
            case "auto_reprint_button": return "自动补打失败项（最多3次）";
            case "auto_retry_running": return "正在自动补打失败项…";
            case "retread_section_loading": return "正在拉取今日记录…";
            case "retread_section_title": return "📋 今日记录";
            case "retread_section_empty": return "今日暂无提交记录。";
            case "retread_section_error": return "拉取记录失败：";
            case "print_queue_title": return "🖨️ 云盒打印队列（全部）";
            case "print_queue_loading": return "正在拉取打印队列…";
            case "print_queue_failed": return "拉取打印队列失败：";
            case "print_reconcile_loading": return "正在拉取打印记录…";
            case "confirming_print": return "确认打印";
            case "inline_reprint_log": return "打印失败，补打第";
            case "inline_reprint_gaveup": return "补打3次仍失败，已记录上报";
            case "inline_print_no_job": return "提交成功，但未查到打印任务（未确认出标签，可能离线或延迟）";
            case "inline_unconfirmed_prefix": return "⚠️ 以下台数未确认出标签，请尽快补打/核对，台数：";
            case "print_reconcile_failed": return "拉取打印记录失败：";
            case "print_recent_note": return "只显示最近的打印任务；下面列出失败/未完成的，可补打或查看标签。";
            case "print_all_ok": return "最近的标签都打印成功 ✅";
            case "print_count_ok": return "成功 ";
            case "print_count_fail": return "失败 ";
            case "print_count_ongoing": return "进行中 ";
            case "print_status_ok": return "已打印";
            case "print_none_today": return "最近没有打印记录。";
            case "print_status_fail": return "打印失败";
            case "print_status_ongoing": return "打印中/未完成";
            case "print_status_unknown": return "⚠️ 状态未知";
            case "print_status_missing": return "未出单";
            case "print_count_missing": return "未出 ";
            case "print_missing_hint": return "本轮已提交，但查无打印任务——很可能没出标签。请在打印机恢复后重新出单，或先人工补、后台处理。";
            case "reconcile_go_cloud": return "☁️ 云端核实";
            case "reconcile_back_local": return "📋 本地台账";
            case "reconcile_mode_local": return "本地台账 · 提交时记录，离线可看";
            case "reconcile_mode_cloud": return "云端核实 · 实时查打印状态，可补打";
            case "reconcile_verifying": return "云端核实中…";
            case "reconcile_no_rounds": return "近 3 天还没有提交记录";
            case "round_word": return "轮次 ";
            case "round_submitted": return "提交 ";
            case "round_labeled": return "出标签 ";
            case "ledger_submit_failed": return "提交失败";
            case "ledger_printed_ok": return "已出标签";
            case "ledger_printed_unconfirmed": return "未确认出标签";
            case "ledger_labeled_collapsed": return "台已出标签";
            case "print_created_at": return "时间：";
            case "print_retry_count": return "重试次数：";
            case "printer_label": return "云盒打印机：";
            case "printer_online": return "在线";
            case "printer_offline": return "离线（提交后可能不出标签，会丢单）";
            case "printer_warn_title": return "打印机未就绪";
            case "printer_warn_msg": return "云盒打印机似乎不在线，现在提交可能不会出标签（会丢单）。建议先处理好打印机再提交。";
            case "printer_warn_proceed": return "仍然提交";
            case "printer_warn_fix": return "先去处理";
            case "printer_check_failed": return "打印机状态检查失败：";
            case "reprint": return "补打";
            case "reprint_sending": return "正在补打…";
            case "reprint_hint": return "已打印成功；若标签丢失或损坏，点这里可补打。";
            case "reprint_done": return "已发送补打指令";
            case "reprint_failed": return "补打失败：";
            case "view_label_pdf": return "查看标签PDF";
            case "open_pdf_failed": return "打开PDF失败：";
            case "refresh": return "刷新";
            case "reconcile_view_all": return "▸ 看全部打印记录";
            case "reconcile_view_round": return "▸ 只看本轮";
            case "token_required_reconcile": return "请先登录公司账号。";
            case "submitted_removed_note": return "已提交设备已移出队列: ";
            case "submitted_removed_log": return "已提交，移出队列。";
            case "submit_failed": return "提交失败";
            case "submit_failed_queue_kept": return "失败的设备已保留在队列（保留原序号），处理后可重新提交；已成功提交但没出标签的，用『打印对账』补打。";
            case "submit_warmup_failed": return "提交准备阶段失败（拉取材料清单），尚未尝试任何设备：";
            case "submit_unit_prefix": return "第";
            case "submit_unit_suffix": return "台失败：";
            case "checking_duplicate": return "检查重复 SN: ";
            case "already_submitted": return "已存在，按成功处理。";
            case "duplicate_found": return "有超过一个月的历史记录。";
            case "duplicate_skipped": return "已选择不提交，继续下一台。";
            case "duplicate_continue_log": return "已确认继续提交。";
            case "duplicate_return_title": return "重复 SN ";
            case "duplicate_skip_button": return "不提交这台";
            case "duplicate_continue_button": return "继续提交";
            case "duplicate_return_sn": return "SN: ";
            case "duplicate_return_count": return "已有记录数: ";
            case "duplicate_return_type": return "本次为: ";
            case "duplicate_return_last_date": return "最近录入时间: ";
            case "duplicate_return_over_one_month": return "最近一次录入已超过一个月。";
            case "duplicate_return_question": return "是否继续提交这台？";
            case "submit_attempt": return "提交 ";
            case "submitted": return "已提交。";
            case "missing_already_notified": return "缺料已提示过，本次自动移除并重试: ";
            case "submit_retry_failed": return "重试后仍提交失败: ";
            case "network_retry_log_prefix": return "网络异常，自动重试 ";
            case "network_retrying_status": return "网络异常，正在自动重试...";
            case "dns_warning_header": return "以下设备提交时遇到DNS解析失败，可能没出单，请去后端核对：";
            case "checking_steps_for": return "检查前两步: ";
            case "ok": return "通过";
            case "failed": return "失败";
            case "steps_ok_short": return "前两步检查通过。";
            case "steps_missing_detail": return "前两步不存在或 SN 录入错误，请先在公司系统里点放大镜确认前两步存在。接口返回:";
            case "sn_correction_try": return "前两步未找到，正在尝试 O/0、1/I 纠正...";
            case "sn_correction_applied": return "已自动纠正 SN。";
            case "sn_correction_fast_timeout": return "快速纠正未命中，提示重试。";
            case "a_step_photo_required": return "\u524d\u4e24\u6b65\u7f3a\u5931\uff0c\u8bf7\u5148\u6dfb\u52a0\u8dd1\u673a\u6d4b\u8bd5\u7167\u7247\uff08\u62cd\u7167\u6216\u4ece\u76f8\u518c\uff09\u3002";
            case "a_steps_creating": return "\u6b63\u5728\u81ea\u52a8\u8865\u5f55\u524d\u4e24\u6b65...";
            case "a_steps_created": return "\u524d\u4e24\u6b65\u5df2\u8865\u5f55\u3002";
            case "a_step_one": return "\u7b2c\u4e00\u6b65";
            case "a_step_two": return "\u7b2c\u4e8c\u6b65";
            case "a_step_template_missing": return "\u627e\u4e0d\u5230\u524d\u4e24\u6b65\u6b65\u9aa4\u6a21\u677f: ";
            case "a_step_template_failed": return "\u524d\u4e24\u6b65\u6b65\u9aa4\u6a21\u677f\u83b7\u53d6\u5931\u8d25: ";
            case "duplicate_check_failed": return "重复 SN 检查失败: ";
            case "need_one_sn": return "至少需要一个 SN。";
            case "missing_base": return "缺少基站SN";
            case "missing_front": return "缺少正面照片";
            case "missing_back": return "缺少反面照片";
            case "base_done": return "基站SN已完成";
            case "base_for": return "请录入 ";
            case "add_sn_first": return "先加入 SN";
            case "photos_done": return "照片已完成";
            case "next_photo": return "下一张 ";
            case "count": return "数量 ";
            case "front": return "正面";
            case "back": return "反面";
            case "base": return "基站";
            case "grade": return "等级";
            case "grade_class": return "类";
            case "precheck": return "前两步";
            case "status": return "状态";
            case "delete_front": return "删除正面";
            case "delete_back": return "删除反面";
            case "delete_a_step": return "删除前两步照片";
            case "supplemental": return "补录";
            case "supplemental_photo": return "补录照片";
            case "details": return "详情";
            case "rescan_sn": return "\u91cd\u65b0\u626b\u63cf";
            case "rescan_saved": return "\u7f16\u53f7\u5df2\u66f4\u65b0\uff0c\u672a\u63d0\u4ea4\u8bb0\u5f55\u4ecd\u4fdd\u7559\u5728\u961f\u5217\u4e2d";
            case "save": return "保存";
            case "view_photo": return "查看图片";
            case "allow_camera": return "请允许相机权限后再试";
            case "scanner_missing_title": return "扫码组件不可用";
            case "scanner_missing_detail": return "内置扫码组件未能启动，请先用键盘或扫码枪输入 SN，并联系维护人员检查安装包。";
            case "camera_missing_title": return "没有可用相机";
            case "camera_missing_detail": return "系统没有可用相机 App，无法拍照。";
            case "camera_open_failed": return "打开相机失败";
            case "last_crash_title": return "上次闪退日志";
            case "no_last_crash": return "暂无上次闪退日志";
            case "last_crash_read_failed": return "读取上次闪退日志失败";
            case "captcha_decode_failed": return "验证码图片解析失败: ";
            case "missing_material": return "缺料";
            case "missing_material_notice": return "缺料提示";
            case "missing_material_list_title": return "缺少的物料";
            case "missing_material_resolved": return "物料已恢复，已从缺料列表移除: ";
            case "missing_retry_note": return "系统已临时移除这些物料并重试提交。";
            case "missing_notice_once": return "本轮已弹出过缺料通知，后续缺料只记录日志。";
            case "draft_found": return "发现未提交数据";
            case "draft_found_detail": return "本机保存了未提交记录，是否继续录入？数量: ";
            case "continue_draft": return "继续录入";
            case "discard_draft": return "丢弃";
            case "draft_restore_failed": return "恢复草稿失败";
            case "draft_save_failed": return "草稿保存失败: ";
            case "materials_refreshing": return "正在刷新最新物料...";
            case "materials_refreshed": return "最新物料已刷新，数量: ";
            case "materials_refresh_failed": return "刷新最新物料失败: ";
            case "today_stats_title": return "今日 ABC 统计";
            case "today_total": return "合计 ";
            case "grade_suffix": return "类";
            case "daily_stats_save_failed": return "今日统计保存失败: ";
            default: return key;
        }
    }

    private String en(String key) {
        switch (key) {
            case "session_expired_title": return "Session expired";
            case "session_expired_detail": return "Your backend account was logged in elsewhere or the session expired. Please log in again.";
            case "delete_unit": return "Delete unit";
            case "q_status_pending": return "Pending";
            case "q_status_submitted": return "Submitted";
            case "q_status_exists": return "Exists";
            case "q_status_failed": return "Failed";
            case "photo": return "Photo";
            case "all_photos_done": return "All photos taken";
            case "form_settings": return "Settings";
            case "payload_display": return "Payload display";
            case "queue_backup": return "Queue backup (save / restore)";
            case "queue_backup_save": return "Save current queue";
            case "queue_backup_restore": return "Restore queue";
            case "queue_backup_empty": return "Queue is empty, nothing to save";
            case "queue_backup_save_failed": return "Queue save failed: ";
            case "queue_backup_saved": return "Current queue saved. Units: ";
            case "queue_backup_restored": return "Queue restored. Units: ";
            case "queue_backup_none": return "No saved queue yet";
            case "queue_backup_saved_info": return "Saved queue. Units: ";
            case "queue_backup_overwrite_confirm": return "There is already an unsubmitted queue. Restoring will overwrite it. Continue?";
            case "close": return "Close";
            case "submit_batch": return "Submit";
            case "submit_loading": return "Submitting. Please wait...";
            case "submit_running": return "Submitting. Please wait.";
            case "current_model": return "Current model";
            case "saved_model": return "Saved model";
            case "switch_model_to_continue": return "Switch to this form to continue entry.";
            case "photo_target_missing": return "Photo state was lost. The draft was kept; return to the form and retry.";
            case "photo_preview_failed": return "Photo preview failed. The original file is still saved and can be submitted.";
            case "diagnostic_log_title": return "Recent diagnostic log";
            case "settings_title": return "Auto Form";
            case "saved": return "Saved";
            case "panel_connection": return "Panel connection";
            case "panel_connection_hint": return "Enter your form system's panel address and access key (e.g. https://your-panel.example.com). Saving connects automatically; leave both blank to stay unconfigured — you must configure it before logging in.";
            case "panel_base": return "Panel address";
            case "panel_base_hint": return "e.g. https://your-panel.example.com";
            case "catalog_key": return "Access key";
            case "catalog_key_hint": return "Access key (Bearer) provided by the panel";
            case "panel_save": return "Save";
            case "panel_current_api": return "Backend in effect: ";
            case "panel_unconfigured": return "Not configured";
            case "panel_required_title": return "Configure the panel first";
            case "panel_required_detail": return "Enter the panel address and access key in Settings before logging in.";
            case "panel_connecting": return "Connecting to the panel…";
            case "panel_connected": return "Panel connected";
            case "panel_connect_failed": return "Panel connection failed. Check the address and access key.";
            case "notify_sent": return "Notification sent";
            case "notify_failed": return "Notification failed: ";
            case "notify_disabled": return "Notify webhook not configured; notification skipped.";
            case "settings_subtitle": return "Choose a language and login before entering the form workflow.";
            case "language": return "Language";
            case "update_channel": return "Update channel: ";
            case "update_channel_stable": return "Stable";
            case "update_channel_beta": return "Beta";
            case "update_channel_beta_toast": return "Switched to Beta updates. Checking now.";
            case "update_channel_stable_toast": return "Switched to Stable updates. Checking now.";
            case "login": return "Login";
            case "account": return "Company account";
            case "password": return "Password";
            case "captcha": return "Captcha";
            case "refresh_captcha": return "Refresh captcha";
            case "login_save": return "Login and enter";
            case "clear_login": return "Clear login";
            case "enter_form": return "Enter form workflow";
            case "form_title": return "Form workflow";
            case "current_user": return "Current account: ";
            case "logout": return "Log out and return";
            case "form": return "Form";
            case "photo_order": return "Photo order";
            case "fronts_then_backs": return "Fronts then backs";
            case "front_back_per_unit": return "Front/back per unit";
            case "robot_sn": return "Robot SN";
            case "scan_sn": return "Scan/read SN";
            case "ocr_sn": return "Photo OCR";
            case "add": return "Add";
            case "base_sn": return "Base station SN";
            case "scan_base": return "Scan/read base";
            case "ocr_base": return "Photo OCR";
            case "match": return "Match";
            case "photos": return "Photos";
            case "take_next_photo": return "Take next photo";
            case "a_step_photo": return "Step 1/2 backfill / run-test photo";
            case "a_step_short": return "Step 1/2 photo";
            case "a_step_take_photo": return "Take photo";
            case "choose_gallery_photo": return "Add from gallery";
            case "gallery_missing_title": return "Gallery unavailable";
            case "gallery_missing_detail": return "No image picker is available on this device.";
            case "a_step_photo_selected": return "Run-test photo added";
            case "next_a_step_photo": return "Next run-test photo ";
            case "a_step_photos_done": return "Step 1/2 photos complete";
            case "a_step_photo_ready": return "Added. Missing step 1/2 records will be created automatically.";
            case "a_step_photo_missing": return "Add a run-test photo (camera or gallery) first; missing step 1/2 records need it.";
            case "a_step_entry_title": return "Scrap entry";
            case "a_step_entry_subtitle": return "Appearance reject: pick model → scan → photo → submit";
            case "a_step_entry_photo": return "Upload photos (multiple)";
            case "a_step_entry_add_photo": return "Add photo";
            case "a_step_entry_photo_count": return "Photos added: ";
            case "a_step_entry_photo_item": return "Photo ";
            case "delete_photo": return "Delete";
            case "a_step_entry_submit": return "Submit scrap entry";
            case "a_step_entry_verdict": return "Fixed payload: appearance fail · scratch · dirt · unrepairable · defective · no operation";
            case "a_step_entry_functional": return "Add functional defect (operation: detection)";
            case "a_step_entry_functional_fallback": return "Functional-defect fill failed: template detail unavailable, submitted fixed content as-is";
            case "a_step_entry_clear_sn": return "Clear SN";
            case "a_step_entry_sn_empty": return "(No SN yet — scan or type, it shows here)";
            case "a_step_entry_no_photo": return "No photo yet";
            case "a_step_entry_photo_ready": return "Photo ready";
            case "a_step_entry_need_model": return "Pick a model first";
            case "a_step_entry_need_sn": return "Scan or enter an SN first";
            case "a_step_entry_need_photo": return "Take at least one photo first";
            case "a_step_entry_done": return "Scrap entry submitted";
            case "go_back": return "Back";
            case "submit": return "Submit";
            case "preview_payload": return "Preview Payload";
            case "check_steps": return "Check first two steps";
            case "dry_run": return "Dry run only";
            case "not_logged_in": return "Not logged in. Login before real submit.";
            case "logged_in": return "Logged in: ";
            case "login_required": return "Login required";
            case "login_required_detail": return "Login before real submission.";
            case "captcha_loading": return "Loading captcha...";
            case "captcha_ready": return "Captcha refreshed.";
            case "captcha_failed": return "Captcha failed";
            case "login_missing": return "Enter account, password and captcha first.";
            case "login_running": return "Logging in...";
            case "login_failed": return "Login failed";
            case "scan_robot_sn": return "Scan robot SN";
            case "scan_base_sn": return "Scan base station SN";
            case "scan_not_sn_title": return "This does not look like an SN";
            case "scan_not_sn_detail": return "The result was numeric-only, which is usually a false barcode read on black models. Try Photo OCR or the scan gun.";
            case "ocr_unavailable_title": return "Photo OCR unavailable";
            case "ocr_unavailable_detail": return "The user info API did not return an OCR URL. Check account permission or recognizeTextUrl setup.";
            case "ocr_url_refreshing": return "Syncing photo OCR URL...";
            case "ocr_running": return "Recognizing photo text...";
            case "ocr_failed": return "Photo OCR failed";
            case "ocr_no_text_title": return "No SN found";
            case "ocr_no_text_detail": return "No SN-like text was recognized. Retake the photo with a clearer label and less glare.";
            case "ocr_auto_no_text": return "Auto OCR did not read an SN. Align the label and try again, or tap the shutter.";
            case "ocr_choose_title": return "Choose recognized SN";
            case "choose_grade": return "Choose grade";
            case "cancel": return "Cancel";
            case "sn_required": return "SN is required";
            case "duplicate_sn": return "Duplicate SN: ";
            case "no_base_needed": return "No unit needs a base SN";
            case "base_required": return "Base station SN is required";
            case "duplicate_base": return "Duplicate base SN: ";
            case "no_photo_needed": return "No photo needed";
            case "photo_no_file": return "Camera returned no file";
            case "photo_full_file_missing": return "The camera did not save the full-size photo. Retake it or switch camera apps.";
            case "photo_notice": return "Photo notice";
            case "start_back_photos": return "All fronts are done. Start back photos.";
            case "photo_save_failed": return "Photo save failed";
            case "no_sn": return "No SN yet";
            case "payload_failed": return "Payload failed";
            case "checking_steps": return "Checking first two steps...";
            case "check_done": return "Check complete";
            case "steps_ok": return "First two steps are present.";
            case "steps_missing_title": return "First two steps missing";
            case "cannot_submit": return "Cannot submit yet";
            case "scan_precheck_missing_detail": return "First two steps are missing or the SN is incorrect. Please retry.";
            case "scan_precheck_retry_title": return "SN not found";
            case "scan_precheck_retry_first": return "The SN may be incorrect. Please retry.";
            case "scan_precheck_retry_second": return "The SN is incorrect or step 1/2 has not been recorded. Please retry.";
            case "scan_precheck_need_run_photo": return "Step 1/2 does not exist. Please upload a run-test photo.";
            case "scan_precheck_failed": return "Immediate first-two-step check failed: ";
            case "done": return "Done";
            case "dry_run_done": return "Payload generated, not submitted.";
            case "submit_done": return "Batch submitted.";
            case "submit_done_queue_cleared": return "Batch submitted. Queue cleared.";
            case "submit_done_check_print": return "Note: labels print asynchronously on the cloud box. Tap 'Print check' to confirm they all printed and reprint any failures.";
            case "submit_aborted_consecutive": return "Aborted this batch after repeated failures. Check network/login and try again.";
            case "submit_cancelled_printer_offline": return "Submit cancelled (printer not ready).";
            case "print_reconcile_title": return "Print check";
            case "print_reconcile_open": return "Print check / Reprint";
            case "auto_reprint_button": return "Auto-reprint failed (max 3×)";
            case "auto_retry_running": return "Auto-reprinting failed labels…";
            case "retread_section_loading": return "Loading today's records…";
            case "retread_section_title": return "📋 Today's records";
            case "retread_section_empty": return "No submissions today.";
            case "retread_section_error": return "Failed to load records: ";
            case "print_queue_title": return "🖨️ Cloud printer queue (all)";
            case "print_queue_loading": return "Loading print queue…";
            case "print_queue_failed": return "Failed to load print queue: ";
            case "print_reconcile_loading": return "Loading print jobs…";
            case "confirming_print": return "Confirming print";
            case "inline_reprint_log": return "print failed, reprint #";
            case "inline_reprint_gaveup": return "still failed after 3 reprints, logged";
            case "inline_print_no_job": return "submitted OK, but no print job found (label unconfirmed — offline or delayed)";
            case "inline_unconfirmed_prefix": return "⚠️ Labels NOT confirmed printed — reprint/check these. Count: ";
            case "print_reconcile_failed": return "Failed to load print jobs: ";
            case "print_recent_note": return "Showing recent print jobs; failed/unfinished ones are listed below — reprint or view the label.";
            case "print_all_ok": return "All recent labels printed ✅";
            case "print_count_ok": return "OK ";
            case "print_count_fail": return "Fail ";
            case "print_count_ongoing": return "Ongoing ";
            case "print_status_ok": return "Printed";
            case "print_none_today": return "No recent label prints.";
            case "print_status_fail": return "Print failed";
            case "print_status_ongoing": return "Printing / unfinished";
            case "print_status_unknown": return "⚠️ Unknown status";
            case "print_status_missing": return "Not printed";
            case "print_count_missing": return "Missing ";
            case "print_missing_hint": return "Submitted this round, but no print job exists — the label almost certainly didn't print. Reprint once the printer is back, or handle it manually / in the backend.";
            case "reconcile_go_cloud": return "☁️ Verify in cloud";
            case "reconcile_back_local": return "📋 Local ledger";
            case "reconcile_mode_local": return "Local ledger · recorded at submit, works offline";
            case "reconcile_mode_cloud": return "Cloud verify · live print status, reprint enabled";
            case "reconcile_verifying": return "Verifying in cloud…";
            case "reconcile_no_rounds": return "No submit rounds in the last 3 days";
            case "round_word": return "Round ";
            case "round_submitted": return "Submitted ";
            case "round_labeled": return "Labeled ";
            case "ledger_submit_failed": return "Submit failed";
            case "ledger_printed_ok": return "Labeled";
            case "ledger_printed_unconfirmed": return "Label unconfirmed";
            case "ledger_labeled_collapsed": return "labeled";
            case "print_created_at": return "Time: ";
            case "print_retry_count": return "Retries: ";
            case "printer_label": return "Cloud printer: ";
            case "printer_online": return "online";
            case "printer_offline": return "offline (labels may not print → lost units)";
            case "printer_warn_title": return "Printer not ready";
            case "printer_warn_msg": return "The cloud label printer seems offline. Submitting now may not print labels (lost units). Better to fix the printer first.";
            case "printer_warn_proceed": return "Submit anyway";
            case "printer_warn_fix": return "Fix first";
            case "printer_check_failed": return "Printer status check failed: ";
            case "reprint": return "Reprint";
            case "reprint_sending": return "Reprinting…";
            case "reprint_hint": return "Printed OK — if the label is lost or damaged, tap to reprint.";
            case "reprint_done": return "Reprint command sent";
            case "reprint_failed": return "Reprint failed: ";
            case "view_label_pdf": return "View label PDF";
            case "open_pdf_failed": return "Open PDF failed: ";
            case "refresh": return "Refresh";
            case "reconcile_view_all": return "▸ Show all prints";
            case "reconcile_view_round": return "▸ This round only";
            case "token_required_reconcile": return "Please log in to the company account first.";
            case "submitted_removed_note": return "Submitted units removed from queue: ";
            case "submitted_removed_log": return "Submitted; removed from queue.";
            case "submit_failed": return "Submit failed";
            case "submit_failed_queue_kept": return "Failed units stayed in the queue (original numbers kept) — fix and submit again. For units that submitted but didn't print, use 'Print check' to reprint.";
            case "submit_warmup_failed": return "Pre-submit warmup failed (fetching material list); no unit was attempted: ";
            case "submit_unit_prefix": return "Unit #";
            case "submit_unit_suffix": return " failed: ";
            case "checking_duplicate": return "Checking duplicate SN: ";
            case "already_submitted": return "already exists, treated as success.";
            case "duplicate_found": return "has existing records older than one month.";
            case "duplicate_skipped": return "not submitted by choice; continuing with the next unit.";
            case "duplicate_continue_log": return "confirmed to continue submitting.";
            case "duplicate_return_title": return "Duplicate SN ";
            case "duplicate_skip_button": return "Do not submit this unit";
            case "duplicate_continue_button": return "Continue submit";
            case "duplicate_return_sn": return "SN: ";
            case "duplicate_return_count": return "Existing records: ";
            case "duplicate_return_type": return "This submit is: ";
            case "duplicate_return_last_date": return "Latest entry date: ";
            case "duplicate_return_over_one_month": return "The latest entry is older than one month.";
            case "duplicate_return_question": return "Continue submitting this unit?";
            case "submit_attempt": return "Submit ";
            case "submitted": return "submitted.";
            case "missing_already_notified": return "Already notified for missing material, auto retrying: ";
            case "submit_retry_failed": return "Submit failed after retries: ";
            case "network_retry_log_prefix": return "Network issue, auto retry ";
            case "network_retrying_status": return "Network issue. Retrying automatically...";
            case "dns_warning_header": return "These units hit DNS resolution errors during submit and may not have printed. Verify in the backend:";
            case "checking_steps_for": return "Checking steps: ";
            case "ok": return "OK";
            case "failed": return "Failed";
            case "steps_ok_short": return "first two steps OK.";
            case "steps_missing_detail": return "may have missing step 1/2 records or an incorrect SN. Use the search icon in the company system to confirm first. API:";
            case "sn_correction_try": return "first two steps missing; trying O/0 and 1/I correction...";
            case "sn_correction_applied": return "SN corrected automatically.";
            case "sn_correction_fast_timeout": return "Fast correction missed; prompting retry.";
            case "a_step_photo_required": return "Step 1/2 records are missing. Add a run-test photo (camera or gallery) first.";
            case "a_steps_creating": return "Creating missing step 1/2 records...";
            case "a_steps_created": return "Step 1/2 records created.";
            case "a_step_one": return "Step 1";
            case "a_step_two": return "Step 2";
            case "a_step_template_missing": return "Cannot find step 1/2 template: ";
            case "a_step_template_failed": return "Failed to load step 1/2 template: ";
            case "duplicate_check_failed": return "Duplicate check failed: ";
            case "need_one_sn": return "At least one SN is required.";
            case "missing_base": return "missing base SN";
            case "missing_front": return "missing front photo";
            case "missing_back": return "missing back photo";
            case "base_done": return "Base SN complete";
            case "base_for": return "Enter base SN for ";
            case "add_sn_first": return "Add SN first";
            case "photos_done": return "Photos complete";
            case "next_photo": return "Next photo ";
            case "count": return "Count ";
            case "front": return "Front";
            case "back": return "Back";
            case "base": return "Base";
            case "grade": return "Grade";
            case "grade_class": return "Class";
            case "precheck": return "Precheck";
            case "status": return "Status";
            case "delete_front": return "Delete front";
            case "delete_back": return "Delete back";
            case "delete_a_step": return "Delete backfill photo";
            case "supplemental": return "Extra";
            case "supplemental_photo": return "Supplement photo";
            case "details": return "Details";
            case "rescan_sn": return "Rescan";
            case "rescan_saved": return "Value updated. Unsubmitted records remain in the queue.";
            case "save": return "Save";
            case "view_photo": return "View photo";
            case "allow_camera": return "Allow camera permission and retry";
            case "scanner_missing_title": return "Scanner unavailable";
            case "scanner_missing_detail": return "The bundled scanner could not start. Use the keyboard or scanner gun for now and ask support to check this APK.";
            case "camera_missing_title": return "No camera available";
            case "camera_missing_detail": return "No system camera app is available for photos.";
            case "camera_open_failed": return "Camera open failed";
            case "last_crash_title": return "Last crash log";
            case "no_last_crash": return "No previous crash log";
            case "last_crash_read_failed": return "Failed to read previous crash log";
            case "captcha_decode_failed": return "Captcha image decode failed: ";
            case "missing_material": return "missing material";
            case "missing_material_notice": return "Missing material";
            case "missing_material_list_title": return "Missing materials";
            case "missing_material_resolved": return "Material restored; removed from missing list: ";
            case "missing_retry_note": return "The app removed these materials temporarily and retried.";
            case "missing_notice_once": return "Missing-material popup already shown for this round; later shortages are logged only.";
            case "draft_found": return "Unsubmitted data found";
            case "draft_found_detail": return "Saved unsubmitted records were found on this device. Continue? Count: ";
            case "continue_draft": return "Continue";
            case "discard_draft": return "Discard";
            case "draft_restore_failed": return "Draft restore failed";
            case "draft_save_failed": return "Draft save failed: ";
            case "materials_refreshing": return "Refreshing latest materials...";
            case "materials_refreshed": return "Latest materials refreshed, count: ";
            case "materials_refresh_failed": return "Latest material refresh failed: ";
            case "today_stats_title": return "Today's ABC stats";
            case "today_total": return "Total ";
            case "grade_suffix": return " class";
            case "daily_stats_save_failed": return "Daily stats save failed: ";
            default: return zh(key);
        }
    }

    private String es(String key) {
        switch (key) {
            case "session_expired_title": return "Sesión caducada";
            case "session_expired_detail": return "Su cuenta de backend inició sesión en otro lugar o la sesión caducó. Inicie sesión de nuevo.";
            case "delete_unit": return "Eliminar unidad";
            case "q_status_pending": return "Pendiente";
            case "q_status_submitted": return "Enviado";
            case "q_status_exists": return "Existe";
            case "q_status_failed": return "Fallido";
            case "photo": return "Foto";
            case "all_photos_done": return "Fotos completas";
            case "form_settings": return "Configuraci\u00f3n";
            case "payload_display": return "Vista del payload";
            case "queue_backup": return "Copia de la cola (guardar / restaurar)";
            case "queue_backup_save": return "Guardar cola actual";
            case "queue_backup_restore": return "Restaurar cola";
            case "queue_backup_empty": return "La cola está vacía, nada que guardar";
            case "queue_backup_save_failed": return "Error al guardar la cola: ";
            case "queue_backup_saved": return "Cola actual guardada. Unidades: ";
            case "queue_backup_restored": return "Cola restaurada. Unidades: ";
            case "queue_backup_none": return "Aún no hay cola guardada";
            case "queue_backup_saved_info": return "Cola guardada. Unidades: ";
            case "queue_backup_overwrite_confirm": return "Ya hay una cola sin enviar. Restaurarla la sobrescribirá. ¿Continuar?";
            case "close": return "Cerrar";
            case "submit_batch": return "Enviar";
            case "submit_loading": return "Enviando. Espere...";
            case "submit_running": return "Enviando. Espere.";
            case "current_model": return "Modelo actual";
            case "saved_model": return "Modelo guardado";
            case "switch_model_to_continue": return "Cambie a este formulario para continuar.";
            case "photo_target_missing": return "Se perdió el estado de la foto. El borrador se conservó; vuelva al formulario e inténtelo de nuevo.";
            case "photo_preview_failed": return "No se pudo mostrar la vista previa de la foto. El archivo original sigue guardado y se puede enviar.";
            case "diagnostic_log_title": return "Registro de diagnóstico reciente";
            case "settings_title": return "Formulario automático";
            case "saved": return "Guardado";
            case "panel_connection": return "Conexión del panel";
            case "panel_connection_hint": return "Introduzca la dirección del panel y la clave de acceso de su sistema de formularios (p. ej. https://your-panel.example.com). Al guardar se conecta automáticamente; deje ambos vacíos para no configurarlo: debe configurarlo antes de iniciar sesión.";
            case "panel_base": return "Dirección del panel";
            case "panel_base_hint": return "p. ej. https://your-panel.example.com";
            case "catalog_key": return "Clave de acceso";
            case "catalog_key_hint": return "Clave de acceso (Bearer) proporcionada por el panel";
            case "panel_save": return "Guardar";
            case "panel_current_api": return "Backend en vigor: ";
            case "panel_unconfigured": return "Sin configurar";
            case "panel_required_title": return "Configure el panel primero";
            case "panel_required_detail": return "Introduzca la dirección del panel y la clave de acceso en Ajustes antes de iniciar sesión.";
            case "panel_connecting": return "Conectando con el panel…";
            case "panel_connected": return "Panel conectado";
            case "panel_connect_failed": return "Error de conexión del panel. Verifique la dirección y la clave de acceso.";
            case "notify_sent": return "Notificación enviada";
            case "notify_failed": return "Error al enviar la notificación: ";
            case "notify_disabled": return "Webhook de notificación no configurado; notificación omitida.";
            case "settings_subtitle": return "Seleccione el idioma e inicie sesión antes de capturar datos.";
            case "language": return "Idioma";
            case "update_channel": return "Canal de actualización: ";
            case "update_channel_stable": return "Estable";
            case "update_channel_beta": return "Beta";
            case "update_channel_beta_toast": return "Canal Beta activado. Revisando actualización.";
            case "update_channel_stable_toast": return "Canal estable activado. Revisando actualización.";
            case "login": return "Inicio de sesión";
            case "account": return "Cuenta de la empresa";
            case "password": return "Contraseña";
            case "captcha": return "Captcha";
            case "refresh_captcha": return "Actualizar captcha";
            case "login_save": return "Iniciar sesión";
            case "clear_login": return "Limpiar sesión";
            case "enter_form": return "Ir al formulario";
            case "form_title": return "Captura del formulario";
            case "current_user": return "Cuenta actual: ";
            case "logout": return "Cerrar sesión y volver";
            case "form": return "Formulario";
            case "photo_order": return "Orden de fotos";
            case "fronts_then_backs": return "Frentes y después reversos";
            case "front_back_per_unit": return "Frente/reverso por unidad";
            case "robot_sn": return "SN del robot";
            case "scan_sn": return "Escanear/leer SN";
            case "ocr_sn": return "OCR foto";
            case "add": return "Agregar";
            case "base_sn": return "SN de la base";
            case "scan_base": return "Escanear/leer base";
            case "ocr_base": return "OCR foto";
            case "match": return "Asignar";
            case "photos": return "Fotos";
            case "take_next_photo": return "Tomar siguiente foto";
            case "a_step_photo": return "Foto para pasos 1/2 / prueba";
            case "a_step_short": return "Foto pasos 1/2";
            case "a_step_take_photo": return "Tomar foto";
            case "choose_gallery_photo": return "Agregar de galeria";
            case "gallery_missing_title": return "Galeria no disponible";
            case "gallery_missing_detail": return "No hay selector de imagenes disponible en este dispositivo.";
            case "a_step_photo_selected": return "Foto de prueba agregada";
            case "next_a_step_photo": return "Siguiente foto de prueba ";
            case "a_step_photos_done": return "Fotos de pasos 1/2 completas";
            case "a_step_photo_ready": return "Agregada. Los pasos 1/2 faltantes se crearan automaticamente.";
            case "a_step_photo_missing": return "Agregue primero una foto de prueba (camara o galeria); los pasos 1/2 faltantes la necesitan.";
            case "a_step_entry_title": return "Registro de descarte";
            case "a_step_entry_subtitle": return "Rechazo por apariencia: elija modelo → escanear → foto → enviar";
            case "a_step_entry_photo": return "Subir fotos (varias)";
            case "a_step_entry_add_photo": return "Agregar foto";
            case "a_step_entry_photo_count": return "Fotos agregadas: ";
            case "a_step_entry_photo_item": return "Foto ";
            case "delete_photo": return "Eliminar";
            case "a_step_entry_submit": return "Enviar descarte";
            case "a_step_entry_verdict": return "Envio fijo: apariencia no apta · rayon · suciedad · irreparable · defectuoso · sin operacion";
            case "a_step_entry_functional": return "Agregar defecto funcional (operacion: deteccion)";
            case "a_step_entry_functional_fallback": return "No se pudo completar defecto funcional: plantilla no disponible, se envio el contenido fijo";
            case "a_step_entry_clear_sn": return "Borrar SN";
            case "a_step_entry_sn_empty": return "(Sin SN aun: escanee o escriba, aparece aqui)";
            case "a_step_entry_no_photo": return "Sin foto aun";
            case "a_step_entry_photo_ready": return "Foto lista";
            case "a_step_entry_need_model": return "Elija un modelo primero";
            case "a_step_entry_need_sn": return "Escanee o ingrese un SN primero";
            case "a_step_entry_need_photo": return "Tome al menos una foto primero";
            case "a_step_entry_done": return "Descarte enviado";
            case "go_back": return "Volver";
            case "submit": return "Enviar";
            case "preview_payload": return "Vista previa del payload";
            case "check_steps": return "Revisar pasos 1/2";
            case "dry_run": return "Solo generar payload";
            case "not_logged_in": return "Sin sesión. Inicie sesión antes de enviar.";
            case "logged_in": return "Sesión: ";
            case "login_required": return "Inicio de sesión requerido";
            case "login_required_detail": return "Inicie sesión antes del envío real.";
            case "captcha_loading": return "Cargando captcha...";
            case "captcha_ready": return "Captcha actualizado.";
            case "captcha_failed": return "Error de captcha";
            case "login_missing": return "Ingrese cuenta, contraseña y captcha.";
            case "login_running": return "Iniciando sesión...";
            case "login_failed": return "Error al iniciar sesión";
            case "scan_robot_sn": return "Escanear SN del robot";
            case "scan_base_sn": return "Escanear SN de base";
            case "scan_not_sn_title": return "No parece un SN";
            case "scan_not_sn_detail": return "El resultado solo tiene numeros; suele ser una lectura falsa en modelos negros. Use OCR foto o la pistola escaner.";
            case "ocr_unavailable_title": return "OCR no disponible";
            case "ocr_unavailable_detail": return "La API de usuario no devolvio la URL de OCR. Revise permisos o recognizeTextUrl.";
            case "ocr_url_refreshing": return "Sincronizando URL de OCR...";
            case "ocr_running": return "Reconociendo texto de la foto...";
            case "ocr_failed": return "Fallo el OCR de foto";
            case "ocr_no_text_title": return "No se encontro SN";
            case "ocr_no_text_detail": return "No se reconocio texto parecido a SN. Repita la foto con la etiqueta mas clara y menos reflejo.";
            case "ocr_auto_no_text": return "El OCR automatico no leyo el SN. Alinee la etiqueta e intente de nuevo, o toque el disparador.";
            case "ocr_choose_title": return "Elegir SN reconocido";
            case "choose_grade": return "Elija la clase";
            case "cancel": return "Cancelar";
            case "sn_required": return "Se requiere SN";
            case "duplicate_sn": return "SN duplicado: ";
            case "no_base_needed": return "No hay unidad pendiente de base";
            case "base_required": return "Se requiere SN de la base";
            case "duplicate_base": return "SN de base duplicado: ";
            case "no_photo_needed": return "No se necesita foto";
            case "photo_no_file": return "La cámara no devolvió archivo";
            case "photo_full_file_missing": return "La cámara no guardó la foto completa. Repítala o cambie la app de cámara.";
            case "photo_notice": return "Aviso de foto";
            case "start_back_photos": return "Frentes completos. Empiece con reversos.";
            case "photo_save_failed": return "Error al guardar foto";
            case "no_sn": return "Aún no hay SN";
            case "payload_failed": return "Error al generar payload";
            case "checking_steps": return "Revisando pasos 1/2...";
            case "check_done": return "Revisión completa";
            case "steps_ok": return "Pasos 1/2 presentes.";
            case "steps_missing_title": return "Faltan pasos 1/2";
            case "cannot_submit": return "Aún no se puede enviar";
            case "scan_precheck_missing_detail": return "Faltan pasos 1/2 o el SN es incorrecto. Intente de nuevo.";
            case "scan_precheck_retry_title": return "SN no encontrado";
            case "scan_precheck_retry_first": return "El SN puede ser incorrecto. Intente de nuevo.";
            case "scan_precheck_retry_second": return "El SN es incorrecto o no se registraron pasos 1/2. Intente de nuevo.";
            case "scan_precheck_need_run_photo": return "Los pasos 1/2 no existen. Suba una foto de prueba.";
            case "scan_precheck_failed": return "Error de revision inmediata de pasos 1/2: ";
            case "done": return "Listo";
            case "dry_run_done": return "Payload generado, no enviado.";
            case "submit_done": return "Lote enviado.";
            case "submit_done_queue_cleared": return "Lote enviado. Cola vacía.";
            case "submit_done_check_print": return "Nota: las etiquetas se imprimen de forma asíncrona en la caja en la nube. Toca 'Verificar impresión' para confirmar que todas se imprimieron y reimprimir las fallidas.";
            case "submit_aborted_consecutive": return "Lote cancelado tras varios fallos seguidos. Revisa la red/sesión e inténtalo de nuevo.";
            case "submit_cancelled_printer_offline": return "Envío cancelado (impresora no lista).";
            case "print_reconcile_title": return "Verificar impresión";
            case "print_reconcile_open": return "Verificar / Reimprimir";
            case "auto_reprint_button": return "Auto-reimprimir fallidas (máx 3)";
            case "auto_retry_running": return "Reimprimiendo fallidas…";
            case "retread_section_loading": return "Cargando registros de hoy…";
            case "retread_section_title": return "📋 Registros de hoy";
            case "retread_section_empty": return "Sin envíos hoy.";
            case "retread_section_error": return "Error al cargar registros: ";
            case "print_queue_title": return "🖨️ Cola de impresión (todo)";
            case "print_queue_loading": return "Cargando cola de impresión…";
            case "print_queue_failed": return "Error al cargar cola: ";
            case "print_reconcile_loading": return "Cargando trabajos de impresión…";
            case "confirming_print": return "Confirmando impresión";
            case "inline_reprint_log": return "impresión fallida, reimpresión #";
            case "inline_reprint_gaveup": return "sigue fallando tras 3 reimpresiones, registrado";
            case "inline_print_no_job": return "enviado OK, pero sin tarea de impresión (etiqueta no confirmada — sin conexión o retraso)";
            case "inline_unconfirmed_prefix": return "⚠️ Etiquetas SIN confirmar — reimprime/verifica. Cantidad: ";
            case "print_reconcile_failed": return "Error al cargar trabajos: ";
            case "print_recent_note": return "Mostrando trabajos recientes; los fallidos/sin terminar se listan abajo — reimprime o ver la etiqueta.";
            case "print_all_ok": return "Todas las etiquetas recientes se imprimieron ✅";
            case "print_count_ok": return "OK ";
            case "print_count_fail": return "Fallo ";
            case "print_count_ongoing": return "En curso ";
            case "print_status_ok": return "Impreso";
            case "print_none_today": return "Sin impresiones recientes.";
            case "print_status_fail": return "Impresión fallida";
            case "print_status_ongoing": return "Imprimiendo / sin terminar";
            case "print_status_unknown": return "⚠️ Estado desconocido";
            case "print_status_missing": return "Sin imprimir";
            case "print_count_missing": return "Faltan ";
            case "print_missing_hint": return "Enviado en esta ronda, pero no existe tarea de impresión — la etiqueta casi seguro no se imprimió. Reimprime cuando la impresora vuelva, o gestiónalo manualmente / en el backend.";
            case "reconcile_go_cloud": return "☁️ Verificar en nube";
            case "reconcile_back_local": return "📋 Registro local";
            case "reconcile_mode_local": return "Registro local · guardado al enviar, sin conexión";
            case "reconcile_mode_cloud": return "Verificación en nube · estado real, reimpresión";
            case "reconcile_verifying": return "Verificando en nube…";
            case "reconcile_no_rounds": return "Sin rondas de envío en los últimos 3 días";
            case "round_word": return "Ronda ";
            case "round_submitted": return "Enviados ";
            case "round_labeled": return "Etiquetas ";
            case "ledger_submit_failed": return "Envío fallido";
            case "ledger_printed_ok": return "Impreso";
            case "ledger_printed_unconfirmed": return "Etiqueta sin confirmar";
            case "ledger_labeled_collapsed": return "impresas";
            case "print_created_at": return "Hora: ";
            case "print_retry_count": return "Reintentos: ";
            case "printer_label": return "Impresora en la nube: ";
            case "printer_online": return "en línea";
            case "printer_offline": return "sin conexión (puede que no imprima → unidades perdidas)";
            case "printer_warn_title": return "Impresora no lista";
            case "printer_warn_msg": return "La impresora en la nube parece estar sin conexión. Enviar ahora puede no imprimir etiquetas (unidades perdidas). Mejor arregla la impresora primero.";
            case "printer_warn_proceed": return "Enviar de todos modos";
            case "printer_warn_fix": return "Arreglar primero";
            case "printer_check_failed": return "Error al comprobar la impresora: ";
            case "reprint": return "Reimprimir";
            case "reprint_sending": return "Reimprimiendo…";
            case "reprint_hint": return "Impreso OK — si la etiqueta se perdió o dañó, toca para reimprimir.";
            case "reprint_done": return "Orden de reimpresión enviada";
            case "reprint_failed": return "Reimpresión fallida: ";
            case "view_label_pdf": return "Ver PDF de etiqueta";
            case "open_pdf_failed": return "Error al abrir PDF: ";
            case "refresh": return "Actualizar";
            case "reconcile_view_all": return "▸ Ver todas las impresiones";
            case "reconcile_view_round": return "▸ Solo esta ronda";
            case "token_required_reconcile": return "Inicie sesión en la cuenta de la empresa primero.";
            case "submitted_removed_note": return "Unidades enviadas eliminadas de la cola: ";
            case "submitted_removed_log": return "Enviado; eliminado de la cola.";
            case "submit_failed": return "Error de envío";
            case "submit_failed_queue_kept": return "Las unidades fallidas quedaron en la cola (con su numero original); corrige y envia de nuevo. Para las que se enviaron pero no se imprimieron, usa 'Verificar impresion' para reimprimir.";
            case "submit_warmup_failed": return "Fallo el preparativo (lista de materiales); ninguna unidad intentada: ";
            case "submit_unit_prefix": return "Unidad #";
            case "submit_unit_suffix": return " fallo: ";
            case "checking_duplicate": return "Revisando SN duplicado: ";
            case "already_submitted": return "ya existe; se toma como éxito.";
            case "duplicate_found": return "tiene registros de hace mas de un mes.";
            case "duplicate_skipped": return "no enviado por decision; continua la siguiente unidad.";
            case "duplicate_continue_log": return "confirmado para continuar el envio.";
            case "duplicate_return_title": return "SN duplicado ";
            case "duplicate_skip_button": return "No enviar esta unidad";
            case "duplicate_continue_button": return "Continuar envio";
            case "duplicate_return_sn": return "SN: ";
            case "duplicate_return_count": return "Registros existentes: ";
            case "duplicate_return_type": return "Este envio es: ";
            case "duplicate_return_last_date": return "Ultima fecha: ";
            case "duplicate_return_over_one_month": return "La ultima entrada tiene mas de un mes.";
            case "duplicate_return_question": return "Continuar enviando esta unidad?";
            case "submit_attempt": return "Enviar ";
            case "submitted": return "enviado.";
            case "missing_already_notified": return "Material faltante ya notificado; reintentando: ";
            case "submit_retry_failed": return "Error después de reintentos: ";
            case "network_retry_log_prefix": return "Error de red, reintento ";
            case "network_retrying_status": return "Error de red. Reintentando...";
            case "dns_warning_header": return "Estas unidades tuvieron errores DNS al enviar y pueden no haberse impreso. Verifique en el backend:";
            case "checking_steps_for": return "Revisando pasos: ";
            case "ok": return "OK";
            case "failed": return "Falló";
            case "steps_ok_short": return "pasos 1/2 OK.";
            case "steps_missing_detail": return "puede faltar el paso 1/2 o el SN puede estar incorrecto. Use la lupa en el sistema para confirmar. API:";
            case "sn_correction_try": return "faltan pasos 1/2; probando correccion O/0 y 1/I...";
            case "sn_correction_applied": return "SN corregido automaticamente.";
            case "sn_correction_fast_timeout": return "Correccion rapida sin resultado; reintente.";
            case "a_step_photo_required": return "Faltan los pasos 1/2. Agregue primero una foto de prueba (camara o galeria).";
            case "a_steps_creating": return "Creando pasos 1/2 faltantes...";
            case "a_steps_created": return "Pasos 1/2 creados.";
            case "a_step_one": return "Paso 1";
            case "a_step_two": return "Paso 2";
            case "a_step_template_missing": return "No se encontro plantilla de pasos 1/2: ";
            case "a_step_template_failed": return "No se pudo cargar plantilla de pasos 1/2: ";
            case "duplicate_check_failed": return "Error al revisar duplicado: ";
            case "need_one_sn": return "Se requiere al menos un SN.";
            case "missing_base": return "falta SN de la base";
            case "missing_front": return "falta foto frontal";
            case "missing_back": return "falta foto reversa";
            case "base_done": return "SN de la base completo";
            case "base_for": return "Ingrese base para ";
            case "add_sn_first": return "Agregue SN primero";
            case "photos_done": return "Fotos completas";
            case "next_photo": return "Siguiente foto ";
            case "count": return "Cantidad ";
            case "front": return "Frente";
            case "back": return "Reverso";
            case "base": return "Base";
            case "grade": return "Grado";
            case "grade_class": return "Clase";
            case "precheck": return "Revisión";
            case "status": return "Estado";
            case "delete_front": return "Borrar frente";
            case "delete_back": return "Borrar reverso";
            case "delete_a_step": return "Borrar foto de pasos";
            case "supplemental": return "Extra";
            case "supplemental_photo": return "Foto adicional";
            case "details": return "Detalles";
            case "rescan_sn": return "Reescanear";
            case "rescan_saved": return "Valor actualizado. Los registros no enviados siguen en la cola.";
            case "save": return "Guardar";
            case "view_photo": return "Ver foto";
            case "allow_camera": return "Permita la cámara y vuelva a intentar";
            case "scanner_missing_title": return "Escáner no disponible";
            case "scanner_missing_detail": return "El escaner integrado no pudo abrirse. Use el teclado o la pistola escaner por ahora y pida revisar este APK.";
            case "camera_missing_title": return "No hay cámara disponible";
            case "camera_missing_detail": return "No hay app de cámara del sistema para fotos.";
            case "camera_open_failed": return "Error al abrir cámara";
            case "last_crash_title": return "Registro del último cierre";
            case "no_last_crash": return "No hay registro de cierre anterior";
            case "last_crash_read_failed": return "No se pudo leer el registro anterior";
            case "captcha_decode_failed": return "Error al leer captcha: ";
            case "missing_material": return "material faltante";
            case "missing_material_notice": return "Material faltante";
            case "missing_material_list_title": return "Materiales faltantes";
            case "missing_material_resolved": return "Material disponible; eliminado de faltantes: ";
            case "missing_retry_note": return "La app quitó temporalmente esos materiales y reintentó.";
            case "missing_notice_once": return "La alerta de material faltante ya apareció en esta ronda; lo siguiente solo quedará en el registro.";
            case "draft_found": return "Datos sin enviar";
            case "draft_found_detail": return "Hay registros guardados sin enviar en este dispositivo. ¿Continuar? Cantidad: ";
            case "continue_draft": return "Continuar";
            case "discard_draft": return "Descartar";
            case "draft_restore_failed": return "Error al restaurar borrador";
            case "draft_save_failed": return "Error al guardar borrador: ";
            case "materials_refreshing": return "Actualizando materiales...";
            case "materials_refreshed": return "Materiales actualizados, cantidad: ";
            case "materials_refresh_failed": return "Error al actualizar materiales: ";
            case "today_stats_title": return "ABC de hoy";
            case "today_total": return "Total ";
            case "grade_suffix": return "";
            case "daily_stats_save_failed": return "Error al guardar estadística diaria: ";
            default: return en(key);
        }
    }

    private static List<String> extractOcrCandidates(JSONObject body) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (body == null) return new ArrayList<>(values);
        JSONObject data = body.optJSONObject("data");
        if (data == null) data = body;
        addOcrValues(values, data.opt("recommend"));
        addOcrValues(values, data.opt("line_detail"));
        addOcrValues(values, data.opt("lineData"));
        addOcrValues(values, data.opt("lines"));
        addOcrValues(values, data.opt("words"));
        return new ArrayList<>(values);
    }

    private static void addOcrValues(Set<String> values, Object source) {
        if (source == null || source == JSONObject.NULL) return;
        if (source instanceof JSONArray) {
            JSONArray array = (JSONArray) source;
            for (int i = 0; i < array.length(); i++) addOcrValues(values, array.opt(i));
            return;
        }
        if (source instanceof JSONObject) {
            JSONObject object = (JSONObject) source;
            addOcrString(values, Api.firstNonEmpty(
                object.optString("Text"),
                object.optString("text"),
                object.optString("words"),
                object.optString("value"),
                object.optString("result"),
                object.optString("content"),
                object.optString("sn"),
                object.optString("SN")
            ));
            addOcrValues(values, object.opt("items"));
            addOcrValues(values, object.opt("children"));
            addOcrValues(values, object.opt("lines"));
            return;
        }
        addOcrString(values, String.valueOf(source));
    }

    private static void addOcrString(Set<String> values, String raw) {
        String compact = normalize(raw);
        if (compact.isEmpty()) return;
        int colon = Math.max(compact.lastIndexOf(':'), compact.lastIndexOf('\uff1a'));
        if (colon >= 0 && colon + 1 < compact.length()) addOcrCandidate(values, compact.substring(colon + 1));
        Matcher matcher = Pattern.compile("[A-Z0-9][A-Z0-9_.%/-]{5,}").matcher(compact);
        while (matcher.find()) addOcrCandidate(values, matcher.group());
        addOcrCandidate(values, compact);
    }

    private static void addOcrCandidate(Set<String> values, String value) {
        String candidate = normalize(value);
        if (isLikelySnCandidate(candidate)) values.add(candidate);
    }

    private static boolean isLikelySnCandidate(String value) {
        if (value == null || value.length() < 5 || value.length() > 80) return false;
        return !isPureNumeric(value) && Pattern.compile("[A-Z]").matcher(value).find();
    }

    private static boolean isPureNumeric(String value) {
        return value != null && value.matches("\\d+");
    }

    private static String conciseError(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 360 ? message.substring(0, 360) + "..." : message;
    }

    private static String extractRecognizeTextUrl(Object source) {
        if (source == null || source == JSONObject.NULL) return "";
        if (source instanceof JSONObject) {
            JSONObject object = (JSONObject) source;
            String value = Api.firstNonEmpty(
                object.optString("recognizeTextUrl"),
                object.optString("recognize_text_url"),
                object.optString("recognize_text"),
                object.optString("ocrUrl"),
                object.optString("ocr_url"),
                object.optString("textRecognitionUrl"),
                object.optString("text_recognition_url")
            );
            if (!value.isEmpty()) return value;
            JSONArray names = object.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String nested = extractRecognizeTextUrl(object.opt(names.optString(i)));
                if (!nested.isEmpty()) return nested;
            }
            return "";
        }
        if (source instanceof JSONArray) {
            JSONArray array = (JSONArray) source;
            for (int i = 0; i < array.length(); i++) {
                String nested = extractRecognizeTextUrl(array.opt(i));
                if (!nested.isEmpty()) return nested;
            }
        }
        return "";
    }

    private static String extractUserName(Object source, String fallback) {
        if (!(source instanceof JSONObject)) return fallback == null ? "" : fallback;
        JSONObject object = (JSONObject) source;
        JSONObject user = object.optJSONObject("userInfo");
        if (user == null) user = object.optJSONObject("user");
        if (user == null) user = object;
        return Api.firstNonEmpty(
            user.optString("name"),
            user.optString("account"),
            user.optString("username"),
            user.optString("userName"),
            fallback
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toUpperCase();
    }

    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String join(List<String> values, String sep) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(sep);
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static String emptyDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String okDash(String value) {
        return value == null || value.isEmpty() ? "-" : "OK";
    }

    private class ScanIconButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        ScanIconButton(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setMinimumWidth(dp(54));
            setMinimumHeight(dp(48));
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float pad = dp(6);
            rect.set(pad, pad, width - pad, height - pad);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isPressed() ? 0xFFE0F2FE : 0xFFEFF6FF);
            canvas.drawRoundRect(rect, dp(10), dp(10), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xFF93C5FD);
            canvas.drawRoundRect(rect, dp(10), dp(10), paint);

            float left = width * 0.30f;
            float right = width * 0.70f;
            float top = height * 0.28f;
            float bottom = height * 0.72f;
            float corner = dp(7);
            paint.setColor(0xFF0F172A);
            paint.setStrokeWidth(dp(2));
            canvas.drawLine(left, top, left + corner, top, paint);
            canvas.drawLine(left, top, left, top + corner, paint);
            canvas.drawLine(right, top, right - corner, top, paint);
            canvas.drawLine(right, top, right, top + corner, paint);
            canvas.drawLine(left, bottom, left + corner, bottom, paint);
            canvas.drawLine(left, bottom, left, bottom - corner, paint);
            canvas.drawLine(right, bottom, right - corner, bottom, paint);
            canvas.drawLine(right, bottom, right, bottom - corner, paint);

            paint.setColor(0xFF2563EB);
            paint.setStrokeWidth(dp(2));
            canvas.drawLine(left - dp(2), height * 0.50f, right + dp(2), height * 0.50f, paint);
        }
    }

    private static class DuplicateHistory {
        final String latestText;
        final long latestMillis;

        DuplicateHistory(String latestText, long latestMillis) {
            this.latestText = latestText == null ? "" : latestText;
            this.latestMillis = latestMillis;
        }
    }

    private static class UnitRecord {
        int sequence;
        String sn;
        String grade;
        String baseSn = "";
        String aStepPhotoPath = "";
        boolean stepPhotoRequired = false;
        String frontPhoto = "";
        String backPhoto = "";
        final List<String> supplementalPhotos = new ArrayList<>();
        // Slot mode (photoSlots profiles): fieldId -> captured photo paths. Stays empty for legacy
        // front/back profiles, so the legacy pipeline and existing drafts are byte-for-byte unaffected.
        final Map<String, List<String>> slotPhotos = new LinkedHashMap<>();
        // snPlugins profiles: extra scan/input boxes (彩盒SN/装箱号/…) beyond primary/base. fieldId ->
        // value. Empty for legacy profiles (no snPlugins) so nothing changes there.
        final Map<String, String> pluginSns = new LinkedHashMap<>();
        // True when this unit's grade is the 不良品 result (profile.defective). Drives the defective
        // photo set + payload. Defaults false so every legacy grade check ("A".equals(grade)…) is unaffected.
        boolean defective = false;
        String precheckStatus = "unchecked";
        String status = "pending";

        UnitRecord(int sequence, String sn, String grade) {
            this.sequence = sequence;
            this.sn = sn;
            this.grade = grade;
        }
    }

    private static class PhotoStep {
        final int index;
        final String side;
        final boolean frontsCompleteTransition;

        PhotoStep(int index, String side, boolean frontsCompleteTransition) {
            this.index = index;
            this.side = side;
            this.frontsCompleteTransition = frontsCompleteTransition;
        }
    }

    private static class WhiteBox {
        final int left;
        final int top;
        final int width;
        final int height;
        final int area;
        final float score;

        WhiteBox(int left, int top, int width, int height, int area, float score) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.area = area;
            this.score = score;
        }
    }

    private static class Api {
        private static final String WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36";
        private static final int TRANSIENT_API_RETRIES = 1;
        private static final long TRANSIENT_API_RETRY_DELAY_MS = 3000L;

        // A 5xx gateway/upstream error (502/503/504) that returned an HTML error page instead of JSON.
        // Tagged so withTransientRetry/isTransientApiNetworkError retry it like other transient infra
        // failures instead of failing a submit outright on a one-off flaky-gateway blip.
        static final class TransientHttpException extends IOException {
            TransientHttpException(String message) { super(message); }
        }
        final String base;
        final String token;
        final String webFingerprint;
        // Optional web-client Origin/Referer, supplied from the panel config. Empty → header omitted.
        final String webOrigin;
        final String webReferer;
        // Backend REST paths this client hits. Resolved once (from the panel config, with the current
        // literals as defaults) by MainActivity.endpoints() and injected here; the Api itself has no
        // Context, so it never touches config — it just uses whatever paths it was handed.
        final Endpoints endpoints;

        /** Panel-configurable backend paths used inside {@link Api}. Defaults equal the current literals. */
        static final class Endpoints {
            final String captcha;
            final String loginVerify;
            final String login;
            final String userInfo;
            final String printerState;
            final String messageList;
            final String labelRetry;
            final String uploadFile;

            Endpoints(String captcha, String loginVerify, String login, String userInfo,
                      String printerState, String messageList, String labelRetry, String uploadFile) {
                this.captcha = captcha;
                this.loginVerify = loginVerify;
                this.login = login;
                this.userInfo = userInfo;
                this.printerState = printerState;
                this.messageList = messageList;
                this.labelRetry = labelRetry;
                this.uploadFile = uploadFile;
            }

            /** The current backend paths — used when no panel override exists, so behavior is unchanged. */
            static Endpoints defaults() {
                return new Endpoints(
                    "/account/getCaptcha",
                    "/account/userLoginVerify",
                    "/account/adminLogin",
                    "/users/userInfo",
                    "/engineer/message/cloudPrinterState",
                    "/engineer/message/getUserMessageList",
                    "/engineer/message/labelPrinterRetry",
                    "/images/uploadFile");
            }
        }

        private interface ApiCall<T> {
            T run() throws Exception;
        }

        Api(String base, String token) {
            this(base, token, "", "", "", Endpoints.defaults());
        }

        Api(String base, String token, String webFingerprint) {
            this(base, token, webFingerprint, "", "", Endpoints.defaults());
        }

        Api(String base, String token, String webFingerprint, String webOrigin, String webReferer) {
            this(base, token, webFingerprint, webOrigin, webReferer, Endpoints.defaults());
        }

        Api(String base, String token, String webFingerprint, String webOrigin, String webReferer, Endpoints endpoints) {
            this.base = base.replaceAll("/+$", "");
            this.token = token == null ? "" : token;
            this.webFingerprint = webFingerprint == null ? "" : webFingerprint.trim();
            this.webOrigin = webOrigin == null ? "" : webOrigin.trim();
            this.webReferer = webReferer == null ? "" : webReferer.trim();
            this.endpoints = endpoints == null ? Endpoints.defaults() : endpoints;
        }

        Captcha getCaptcha() throws Exception {
            JSONObject body = getJson(endpoints.captcha, "", true);
            if (!isSuccess(body)) throw new IOException(apiErrorMessage(body));
            JSONObject data = apiDataObject(body);
            if (data == null) throw new IOException("Captcha response has no data");
            return new Captcha(data.optString("client"), data.optString("captcha"));
        }

        LoginResult login(String account, String password, String captcha, String client) throws Exception {
            JSONObject form = new JSONObject();
            form.put("account", account);
            form.put("password", password);
            form.put("captcha", captcha);
            form.put("client", client);
            JSONObject verify = postForm(endpoints.loginVerify, form, true);
            if (!isSuccess(verify)) throw new IOException(apiErrorMessage(verify));
            JSONObject body = postForm(endpoints.login, form, true);
            if (!isSuccess(body)) throw new IOException(apiErrorMessage(body));
            JSONObject data = apiDataObject(body);
            if (data == null) throw new IOException("Login response has no data");
            String token = firstNonEmpty(extractToken(data), extractToken(body));
            if (token.isEmpty()) throw new IOException("Login response has no token");
            String userName = extractUserName(data, account);
            String recognizeTextUrl = extractRecognizeTextUrl(data);
            if (recognizeTextUrl.isEmpty()) {
                UserProfile profile = new Api(base, token, webFingerprint, webOrigin, webReferer, endpoints).fetchUserInfo();
                userName = firstNonEmpty(profile.userName, userName);
                recognizeTextUrl = profile.recognizeTextUrl;
            }
            return new LoginResult(token, userName, recognizeTextUrl);
        }

        UserProfile fetchUserInfo() throws Exception {
            JSONObject body = getJson(endpoints.userInfo, "");
            if (!isSuccess(body)) throw new IOException(apiErrorMessage(body));
            Object data = apiData(body);
            return new UserProfile(extractUserName(data, ""), extractRecognizeTextUrl(data));
        }

        enum AuthState { VALID, INVALID, UNKNOWN }

        /**
         * Probe whether {@link #token} is still accepted by the backend instead of trusting a token
         * that may have been inherited from a peer app (or our local cache) and has since been
         * kicked/expired, so a peer and this app judge a kick the same way.
         *
         * <p>VALID = /users/userInfo succeeded. INVALID = a clear auth rejection (HTTP 401/403 or an
         * auth-worded body) → caller should log out and prompt re-login. UNKNOWN = network/transport
         * blip → don't act on it. Deliberately NOT routed through getJson: that swallows 401/403 into
         * a generic IOException, losing the three-way distinction. Uses web headers (same session
         * identity as the rest of the app) so the probe never itself trips an "elsewhere" kick.
         */
        AuthState checkAuth() {
            if (token.isEmpty()) return AuthState.INVALID;
            try {
                URL url = new URL(base + endpoints.userInfo);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                addHeaders(conn, true, 15000, 20000);
                int status = conn.getResponseCode();
                if (status == 401 || status == 403) return AuthState.INVALID;
                JSONObject body;
                try {
                    body = readJson(conn);
                } catch (Exception parse) {
                    return AuthState.UNKNOWN;
                }
                if (isSuccess(body)) return AuthState.VALID;
                String text = body == null ? "" : body.toString().toLowerCase(java.util.Locale.US);
                if (text.contains("unauth") || text.contains("登录") || text.contains("登陆")
                        || text.contains("token") || text.contains("expire") || text.contains("401")) {
                    return AuthState.INVALID;
                }
                return AuthState.UNKNOWN;
            } catch (Exception e) {
                return AuthState.UNKNOWN;
            }
        }

        // ----- Cloud-box label printer (engineer/message) -----
        // Submitting a record only saves it; the physical label is printed asynchronously by a cloud
        // label printer. A failed/offline print is invisible to this app unless we ask the server.
        // These endpoints back the backend's exception-handling screen. All calls use the web-client
        // headers (true) so they share one session identity and never trip an "elsewhere login" kick.

        // code 200 + data.status == 1 => the bound cloud box is online. code 30006 => no box bound.
        JSONObject cloudPrinterState() throws Exception {
            return getJson(endpoints.printerState, "");
        }

        // Paginated list of cloud-printer label jobs (Laravel paginator: data[] + meta{current_page,last_page,total}).
        // Item fields: id, order_no, pdf_url, describe, status (1 ok / 2 fail / 3 ongoing), retry_frequency, created_at.
        JSONObject getUserMessageList(int page) throws Exception {
            return getJson(endpoints.messageList, "page=" + page);
        }

        // Re-send a failed/ongoing label print to the cloud box. id = message id from getUserMessageList.
        JSONObject labelPrinterRetry(long id) throws Exception {
            JSONObject payload = new JSONObject();
            payload.put("id", id);
            return postJson(endpoints.labelRetry, payload);
        }

        JSONObject getJson(String path, String query) throws Exception {
            return getJson(path, query, true);
        }

        JSONObject getJson(String path, String query, boolean webLoginClient) throws Exception {
            return getJson(path, query, webLoginClient, 30000, 120000);
        }

        JSONObject getJson(String path, String query, boolean webLoginClient, int connectTimeoutMs, int readTimeoutMs) throws Exception {
            return withTransientRetry(() -> {
                URL url = new URL(base + path + (query == null || query.isEmpty() ? "" : "?" + query));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                addHeaders(conn, webLoginClient, connectTimeoutMs, readTimeoutMs);
                return readJson(conn);
            });
        }

        JSONObject postJson(String path, JSONObject payload) throws Exception {
            return withTransientRetry(() -> {
                URL url = new URL(base + path);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                addHeaders(conn, true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = conn.getOutputStream()) {
                    output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                return readJson(conn);
            });
        }

        JSONObject postForm(String path, JSONObject form) throws Exception {
            return postForm(path, form, true);
        }

        JSONObject postForm(String path, JSONObject form, boolean webLoginClient) throws Exception {
            return withTransientRetry(() -> {
                URL url = new URL(base + path);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                addHeaders(conn, webLoginClient);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                StringBuilder body = new StringBuilder();
                JSONArray names = form.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    String name = names.getString(i);
                    if (i > 0) body.append('&');
                    body.append(URLEncoder.encode(name, "UTF-8"));
                    body.append('=');
                    body.append(URLEncoder.encode(form.optString(name), "UTF-8"));
                }
                try (OutputStream output = conn.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                return readJson(conn);
            });
        }

        String uploadImage(File file, String uploadName) throws Exception {
            return withTransientRetry(() -> {
                String boundary = "----AutoFormKit" + System.currentTimeMillis();
                URL url = new URL(base + endpoints.uploadFile);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                addHeaders(conn, true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream output = conn.getOutputStream(); InputStream input = new java.io.FileInputStream(file)) {
                    output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + uploadName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write("Content-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                    output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                JSONObject body = readJson(conn);
                if (!isSuccess(body) || apiData(body) == null) {
                    throw new IOException("Image upload failed: " + apiErrorMessage(body));
                }
                return String.valueOf(apiData(body));
            });
        }

        JSONObject recognizeText(String recognizeTextUrl, File file) throws Exception {
            return withTransientRetry(() -> {
                String boundary = "----AutoFormKit" + System.currentTimeMillis();
                URL url = absoluteUrl(recognizeTextUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream output = conn.getOutputStream(); InputStream input = new java.io.FileInputStream(file)) {
                    output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"sn-ocr.jpg\"\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write("Content-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                    output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                JSONObject body = readJson(conn);
                if (!isSuccess(body)) {
                    throw new IOException("OCR failed: " + apiErrorMessage(body));
                }
                return body;
            });
        }

        private <T> T withTransientRetry(ApiCall<T> call) throws Exception {
            Exception last = null;
            for (int attempt = 0; attempt <= TRANSIENT_API_RETRIES; attempt++) {
                try {
                    return call.run();
                } catch (Exception exc) {
                    if (isDnsResolveError(exc)) {
                        DnsContext ctx = currentDnsContext.get();
                        if (ctx != null) ctx.activity.recordDnsAffected(ctx.unit, ctx.position, exc);
                    }
                    if (attempt >= TRANSIENT_API_RETRIES || !isTransientApiNetworkError(exc)) throw exc;
                    last = exc;
                    try {
                        Thread.sleep(TRANSIENT_API_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
            if (last != null) throw last;
            throw new IOException("Request failed");
        }

        static boolean isTransientApiNetworkError(Throwable exc) {
            for (Throwable current = exc; current != null; current = current.getCause()) {
                if (current instanceof TransientHttpException) return true;
                if (current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.NoRouteToHostException
                    || current instanceof javax.net.ssl.SSLException) {
                    return true;
                }
                String message = current.getMessage();
                if (message == null) continue;
                String lower = message.toLowerCase(java.util.Locale.US);
                if (lower.contains("unable to resolve host")
                    || lower.contains("no address associated")
                    || lower.contains("failed to connect")
                    || lower.contains("connection reset")
                    || lower.contains("connection refused")
                    || lower.contains("network is unreachable")
                    || lower.contains("timed out")
                    || lower.contains("timeout")) {
                    return true;
                }
            }
            return false;
        }

        static boolean isDnsResolveError(Throwable exc) {
            for (Throwable current = exc; current != null; current = current.getCause()) {
                if (current instanceof java.net.UnknownHostException) return true;
                String message = current.getMessage();
                if (message == null) continue;
                String lower = message.toLowerCase(java.util.Locale.US);
                if (lower.contains("unable to resolve host")
                    || lower.contains("no address associated")) {
                    return true;
                }
            }
            return false;
        }

        URL absoluteUrl(String pathOrUrl) throws IOException {
            String value = pathOrUrl == null ? "" : pathOrUrl.trim();
            if (value.startsWith("http://") || value.startsWith("https://")) return new URL(value);
            if (value.startsWith("/")) return new URL(base + value);
            return new URL(base + "/" + value);
        }

        void addHeaders(HttpURLConnection conn) {
            addHeaders(conn, false);
        }

        void addHeaders(HttpURLConnection conn, boolean webLoginClient) {
            addHeaders(conn, webLoginClient, 30000, 120000);
        }

        void addHeaders(HttpURLConnection conn, boolean webLoginClient, int connectTimeoutMs, int readTimeoutMs) {
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (webLoginClient) {
                // Origin/Referer identify the web front-end the session was established with. They are
                // optional and come from the panel config; when unset, the headers are simply omitted.
                if (!webOrigin.isEmpty()) conn.setRequestProperty("Origin", webOrigin);
                if (!webReferer.isEmpty()) conn.setRequestProperty("Referer", webReferer);
                if (!webFingerprint.isEmpty()) conn.setRequestProperty("X-Browser-Fingerprint", webFingerprint);
                conn.setRequestProperty("User-Agent", WEB_USER_AGENT);
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            }
            if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        }

        JSONObject readJson(HttpURLConnection conn) throws Exception {
            int status = conn.getResponseCode();
            InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (input == null) throw new IOException("HTTP " + status);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            String text = output.toString("UTF-8");
            try {
                return new JSONObject(text);
            } catch (JSONException exc) {
                // A flaky gateway/upstream (e.g. nginx "502 Bad Gateway") serves an HTML error page,
                // not JSON. Treat 502/503/504 as transient so withTransientRetry retries it instead of
                // failing the submit; a persistent gateway error still surfaces (as a network failure).
                if (status == 502 || status == 503 || status == 504) {
                    throw new TransientHttpException("HTTP " + status + " gateway error: " + conciseText(text));
                }
                throw new IOException("Response is not JSON: " + conciseText(text));
            }
        }

        static String conciseText(String text) {
            String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
            return value.length() > 360 ? value.substring(0, 360) + "..." : value;
        }

        static boolean isSuccess(JSONObject body) {
            if (body == null) return false;
            Object code = body.opt("code");
            if (code == null || code == JSONObject.NULL) {
                return body.has("data") || body.has("token") || body.has("access_token");
            }
            if (code instanceof Number) return ((Number) code).intValue() == 200;
            return "200".equals(String.valueOf(code).trim());
        }

        static Object apiData(JSONObject body) {
            if (body == null) return null;
            Object data = body.opt("data");
            if (data != null && data != JSONObject.NULL) return data;
            if (!body.has("code")) return body;
            return null;
        }

        static JSONObject apiDataObject(JSONObject body) {
            Object data = apiData(body);
            return data instanceof JSONObject ? (JSONObject) data : null;
        }

        static String apiErrorMessage(JSONObject body) {
            if (body == null) return "Empty response";
            String message = firstNonEmpty(
                body.optString("message"),
                body.optString("msg"),
                body.optString("error")
            );
            if (!message.isEmpty()) return conciseText(message);
            Object code = body.opt("code");
            if (code != null && code != JSONObject.NULL) return "API error code " + code;
            return "Unexpected API response: " + conciseText(body.toString());
        }

        static String extractToken(JSONObject data) {
            Object value = data.opt("token");
            if (value instanceof JSONObject) {
                String access = ((JSONObject) value).optString("access_token");
                if (!access.isEmpty()) return access;
            }
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
            return firstNonEmpty(data.optString("access_token"), data.optString("token"));
        }

        static String firstNonEmpty(String... values) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) return value;
            }
            return "";
        }

        static class Captcha {
            final String client;
            final String captcha;

            Captcha(String client, String captcha) {
                this.client = client;
                this.captcha = captcha;
            }
        }

        static class UserProfile {
            final String userName;
            final String recognizeTextUrl;

            UserProfile(String userName, String recognizeTextUrl) {
                this.userName = userName == null ? "" : userName;
                this.recognizeTextUrl = recognizeTextUrl == null ? "" : recognizeTextUrl;
            }
        }

        static class LoginResult {
            final String token;
            final String userName;
            final String recognizeTextUrl;

            LoginResult(String token, String userName, String recognizeTextUrl) {
                this.token = token;
                this.userName = userName;
                this.recognizeTextUrl = recognizeTextUrl == null ? "" : recognizeTextUrl;
            }
        }
    }
}
