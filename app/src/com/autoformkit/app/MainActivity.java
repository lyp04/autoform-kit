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

import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

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
import java.security.MessageDigest;
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
    // Permanently reserved by signed v1.0.4/v1.0.6. An activity result can outlive the APK
    // process which launched it, so these codes must never acquire a different meaning.
    private static final int REQ_LEGACY_PICK_A_STEP_PHOTO = 2011;
    private static final int REQ_LEGACY_CAPTURE_A_STEP_PHOTO = 2012;
    private static final int REQ_LEGACY_SCAN_A_STEP_ENTRY_SN = 2013;
    private static final int REQ_LEGACY_CAPTURE_A_STEP_ENTRY_PHOTO = 2014;
    private static final int REQ_SCAN_ALTERNATE_ENTRY_SN = 2015;
    private static final int REQ_CAPTURE_ALTERNATE_ENTRY_PHOTO = 2016;
    private static final String CHANNEL_ID = "material_shortage";
    private static final String DRAFT_KEY = "pending_form_draft_json";
    private static final String DRAFT_STORE_KEY = "pending_form_draft_store_json";
    // Manual, durable queue snapshot the user saves on purpose (kept until overwritten,
    // unlike the auto-draft which clears once everything is submitted or is discarded).
    private static final String MANUAL_QUEUE_KEY = "manual_saved_queue_json";
    // Local per-round ledger: each submit batch appends one round {ts, profileId, units:[{sn,submit,printed}]}.
    // This is the source of truth for print reconciliation — we read "what this round contained + its submit/
    // print outcome" from here, instead of reverse-inferring the round from the cloud's print-job list.
    private static final String ROUND_LEDGER_KEY = "round_ledger_json";
    private static final String REPRINT_ATTEMPTS_KEY = "pending_reprint_attempts_v1_json";
    private static final Object REPRINT_JOURNAL_LOCK = new Object();
    private static final String LAST_PROFILE_ID_KEY = "last_profile_id";
    private static final String DAILY_STATS_PREFIX = "daily_stats_";
    // Independent-entry counters intentionally live outside the signed-v1 daily_stats_* mirror.
    // Old Apps reject object-valued unknown keys in that mirror, so mixing the two schemas would
    // make an intentional rollback lose the whole day's otherwise compatible main-form totals.
    private static final String ALTERNATE_DAILY_STATS_PREFIX =
        "alternate_daily_stats_v1_";
    private static final String ROLLBACK_GLOBAL_OWNER_KEY =
        "rollback_global_owner_namespace_v1";
    private static final String PENDING_PHOTO_INDEX_KEY = "pending_photo_index";
    private static final String PENDING_PHOTO_SIDE_KEY = "pending_photo_side";
    private static final String PENDING_PHOTO_PATH_KEY = "pending_photo_path";
    private static final String PENDING_PHOTO_FIELD_KEY = "pending_photo_field";
    private static final String PENDING_OCR_PHOTO_PATH_KEY = "pending_ocr_photo_path";
    private static final String PENDING_RESCAN_SEQUENCE_KEY = "pending_rescan_sequence";
    private static final String PENDING_MAIN_FORM_OPERATION_KEY =
        "pending_main_form_operation_v1";
    private static final String BOUND_OCR_URL_KEY_PREFIX =
        "recognize_text_url_bound_v1_";
    private static final String PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY =
        "pending_alternate_entry_photo_path";
    private static final String PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY =
        "pending_alternate_entry_photo_guard";
    private static final String PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY =
        "pending_alternate_entry_scan_guard";
    private static final String PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY =
        "pending_alternate_entry_scan_reservation_v1_json";
    private static final String PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY =
        "pending_alternate_entry_photo_reservation_v1_json";
    private static final String ALTERNATE_ENTRY_DRAFT_KEY =
        "pending_alternate_entry_draft_json";
    private static final String ALTERNATE_ENTRY_CONTINUATION_PROOF_KEY =
        "pending_alternate_entry_continuation_proof_v1_json";
    private static final String ALTERNATE_SUBMISSION_ATTEMPT_KEY =
        "pending_alternate_submission_attempt_json";
    private static final String MAIN_SUBMISSION_ATTEMPT_KEY =
        "pending_main_submission_attempt_json";
    private static final String PREVIOUS_STEP_SUBMISSION_ATTEMPT_KEY =
        "pending_previous_step_submission_attempt_json";
    static final String UPLOAD_REPLAY_BARRIER_KEY =
        "pending_upload_replay_barrier_v1_json";
    // Image uploads do not create form records, so a lost image response must not permanently lock
    // production, Panel refresh, or App update.
    private static final boolean DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED = false;
    // Production explicitly permits an operator retry after an unconfirmed final form POST. Every
    // retry still runs the configured duplicate lookup first, so an already-visible write is
    // classified as submitted instead of issuing the final POST again.
    private static final boolean DURABLE_FINAL_SUBMISSION_REPLAY_BARRIER_ENABLED = false;
    // A newly downloaded Panel pair stays staged until the existing immutable workflow reaches a
    // safe install boundary, but its mere presence must not make the current pair inaccessible.
    private static final boolean BLOCK_ACTIVE_USE_ON_STAGED_PANEL_PAIR = false;
    private static final String LOCAL_PREVIEW_FINGERPRINT_KEY =
        "local_preview_web_fingerprint_v1";
    private static final String EXTRA_EXPECTED_SN_LENGTH = "EXPECTED_SN_LENGTH";
    private static final String STATE_PENDING_PHOTO_INDEX = "state_pending_photo_index";
    private static final String STATE_PENDING_PHOTO_SIDE = "state_pending_photo_side";
    private static final String STATE_PENDING_PHOTO_FIELD = "state_pending_photo_field";
    private static final String STATE_PENDING_PHOTO_PATH = "state_pending_photo_path";
    private static final String STATE_PENDING_OCR_PHOTO_PATH = "state_pending_ocr_photo_path";
    private static final String STATE_PENDING_RESCAN_SEQUENCE = "state_pending_rescan_sequence";
    private static final String STATE_ALTERNATE_ENTRY_OPEN = "state_alternate_entry_open";
    private static final String STATE_ALTERNATE_ENTRY_ID = "state_alternate_entry_id";
    private static final String STATE_ALTERNATE_ENTRY_SOURCE_ID = "state_alternate_entry_source_id";
    private static final String STATE_ALTERNATE_ENTRY_RETURN_PROFILE_ID =
        "state_alternate_entry_return_profile_id";
    private static final String STATE_ALTERNATE_ENTRY_SERIAL = "state_alternate_entry_serial";
    private static final String STATE_ALTERNATE_ENTRY_SERIAL_SOURCE =
        "state_alternate_entry_serial_source";
    private static final String STATE_ALTERNATE_ENTRY_PHOTOS = "state_alternate_entry_photos";
    private static final String STATE_ALTERNATE_ENTRY_TOGGLES = "state_alternate_entry_toggles";
    private static final String STATE_ALTERNATE_ENTRY_CONNECTION =
        "state_alternate_entry_connection";
    private static final String STATE_ALTERNATE_ENTRY_BINDING = "state_alternate_entry_binding";
    private static final String STATE_ALTERNATE_ENTRY_BACKEND = "state_alternate_entry_backend";
    private static final String STATE_ALTERNATE_ENTRY_SESSION_NONCE =
        "state_alternate_entry_session_nonce";
    private static final String STATE_ALTERNATE_ENTRY_PHOTO_GUARD =
        "state_alternate_entry_photo_guard";
    private static final String STATE_ALTERNATE_ENTRY_SCAN_GUARD =
        "state_alternate_entry_scan_guard";
    private static final int MAX_SN_CORRECTION_CANDIDATES = 32;
    private static final int MAX_SCAN_PRECHECK_CORRECTION_CANDIDATES = 16;
    private static final int SCAN_PRECHECK_CONNECT_TIMEOUT_MS = 2000;
    private static final int SCAN_PRECHECK_READ_TIMEOUT_MS = 4000;
    private static final int SCAN_PRECHECK_CORRECTION_THREADS = 4;
    private static final int SCAN_PRECHECK_CORRECTION_BUDGET_MS = 4000;
    // One bad unit must not strand the rest of the batch: keep going, but bail if many fail in a row (systemic).
    private static final Pattern LOG_SEQUENCE_PATTERN = Pattern.compile("#\\d+");
    private static final Pattern LOG_SN_ASSIGNMENT_PATTERN = Pattern.compile("SN=([A-Z0-9]{8,32})");
    private static final Pattern LOG_SN_TOKEN_PATTERN = Pattern.compile("\\b[A-Z0-9]{8,32}\\b");

    private JSONArray profiles;      // profiles explicitly visible in the main picker
    private JSONArray allProfiles;   // every published profile, including linked/hidden profiles
    private JSONObject profile;
    private JSONObject catalogSettings; // global catalog settings; null for an old/unavailable cache
    // Revision of the catalog currently paired with appConfig/allProfiles in memory. Disk refreshes
    // may advance independently; active form state continues using this immutable-by-replacement
    // pair until a safe Settings boundary installs a matching new pair.
    private volatile int activeCatalogVersion = 0;
    // Canonical hash of that same active pair. Keep it in memory so an independently refreshed
    // disk cache cannot change the legacy-draft decision for an already active snapshot.
    private volatile String activePanelPairSha256 = "";
    // Panel-provided runtime config cached from <panelBase>/api/config.
    // null = unconfigured / not yet fetched; volatile because background Api threads read it via apiBase().
    private volatile JSONObject appConfig;
    // Notification transport is captured from the same active config/catalog revision. It never
    // rereads independently refreshed disk caches while an older workflow is still in progress.
    private volatile NotificationClient.Snapshot notificationSnapshot;
    // Immutable two-download gate for the exact current Panel/key connection. A configured Panel
    // never falls through to the bundled preview while either bound cache is missing.
    private volatile PanelBootstrapRules.State panelBootstrapState;
    // An unsafe staged candidate may not turn an open page into an unlimited old-pair session.
    // This immutable lease contains only work that existed at the first observed barrier.
    private volatile UnsafeCandidateContinuationRules.Lease
        unsafeCandidateContinuationLease;
    private final List<UnitRecord> units = new ArrayList<>();
    private final Set<String> cachedMissingMaterialCodes = new HashSet<>();
    private final Set<String> notifiedMissingMaterialCodes = new HashSet<>();
    private final Map<String, Integer> scanPrecheckMissingCounts = new HashMap<>();
    private final LinkedHashMap<String, Integer> dnsAffectedUnits = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashSet<String>> roundMissingMaterials = new LinkedHashMap<>();
    // A conflict never gets "repaired" by a normal save: retaining both copies is safer than
    // silently replacing a valid rollback edit or reassigning it to another Panel.
    private final Set<String> blockedRollbackMirrors =
        Collections.synchronizedSet(new HashSet<>());
    /** Separate from mirror-shape conflicts so a later successful transaction recovery can clear it. */
    private volatile boolean manualQueueDeleteRecoveryBlocked;
    static final ThreadLocal<DnsContext> currentDnsContext = new ThreadLocal<>();
    private final ThreadLocal<ActiveMainUploadBarrier>
        activeMainUploadBarrier = new ThreadLocal<>();
    /** Keeps the process fail-closed if SharedPreferences reports an ambiguous removal failure. */
    private volatile UploadReplayBarrier uploadReplayBarrierClearFailure;

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

    /** One immutable upload context for one logical main-form unit on one worker thread. */
    private static final class ActiveMainUploadBarrier {
        final UnitRecord unit;
        final MainDraftSnapshotRules.Binding draftBinding;
        final String connectionNamespace;
        final int catalogVersion;
        final String panelPairSha256;
        final String bindingFingerprintSha256;
        final String backendFingerprintSha256;
        final String sessionFingerprintSha256;
        final String operationId;
        final SubmissionPolicyRules.NetworkRetryGate networkRetryGate =
            new SubmissionPolicyRules.NetworkRetryGate();
        UploadReplayBarrier.Identity startedIdentity;

        ActiveMainUploadBarrier(
                UnitRecord unit, MainDraftSnapshotRules.Binding draftBinding,
                String connectionNamespace, int catalogVersion,
                String panelPairSha256, String bindingFingerprintSha256,
                String backendFingerprintSha256, String sessionFingerprintSha256,
                String operationId) {
            this.unit = unit;
            this.draftBinding = draftBinding;
            this.connectionNamespace = connectionNamespace;
            this.catalogVersion = catalogVersion;
            this.panelPairSha256 = panelPairSha256;
            this.bindingFingerprintSha256 = bindingFingerprintSha256;
            this.backendFingerprintSha256 = backendFingerprintSha256;
            this.sessionFingerprintSha256 = sessionFingerprintSha256;
            this.operationId = operationId;
        }
    }
    private boolean missingMaterialNoticeShown = false;
    private boolean restoringDraft = false;
    private boolean draftPromptShown = false;
    private boolean profileSelectionReady = false;
    // Non-submit previous-step workers also read profile-owned recipes. While one is active the
    // picker must not replace the global immutable profile reference between a guard and a call.
    private int mainDraftRemoteWorkerCount = 0;
    private RemoteSideEffectGate.WorkerLease mainDraftRemoteWorkerLease;
    private final Map<String, String> activeOperationNonces =
        Collections.synchronizedMap(new HashMap<>());
    private volatile OperationBindingRules.Binding acceptedCaptchaBinding;
    // Print reconciliation is ledger-driven and can run with an empty main draft, so it has its
    // own worker count.  Profile/Panel replacement is blocked while either kind of worker is live.
    private int printRemoteWorkerCount = 0;
    private RemoteSideEffectGate.WorkerLease printRemoteWorkerLease;
    private volatile boolean settingsPageOpen = false;
    private boolean rebuildingResultButtons = false;
    private boolean submitting = false;
    // Cross-app session: foreground auth-probe loop + live broadcast receiver (see SessionBridge).
    private final Handler authHandler = new Handler(Looper.getMainLooper());
    private final Handler panelSyncHandler = new Handler(Looper.getMainLooper());
    private Runnable panelPairRetryTask;
    private String panelPairRetryConnection = "";
    private int panelPairRetryCount = 0;
    private long panelSyncRound = 0L;
    private Runnable authPoller;
    private long lastAuthCheckMs = 0L;
    private BroadcastReceiver sessionReceiver;
    private boolean printReconcileCloudVerify = false; // print-reconcile: false = local ledger view, true = cloud-verify view
    private volatile boolean reconcileDialogOpen = false; // true while the reconcile dialog shows — closing it stops the cloud-verify walk
    private int chineseTapCount = 0;
    private long chineseTapWindowStarted = 0L;

    private SharedPreferences prefs;
    private volatile String activeSessionRealmSha256 = "";
    private String lang = "zh";
    private String captchaClient = "";
    private String photoOrder = PhotoOrderRules.GROUPED;
    private int pendingPhotoIndex = -1;
    private String pendingPhotoSide = "";
    private String pendingPhotoField = "";
    private String pendingOutputPhotoPath = "";
    private Uri pendingOutputPhotoUri;
    private String pendingOcrPhotoPath = "";
    private Uri pendingOcrPhotoUri;
    private int pendingRescanUnitSequence = -1;
    private PendingFormOperationRules.Target pendingMainFormTarget;

    // Panel-configured alternate-entry state. It is intentionally separate from the main draft:
    // the old dedicated page kept one serial and a small photo list in memory until submission.
    private boolean alternateEntryPageOpen = false;
    private boolean alternateEntrySubmitting = false;
    private String alternateEntryId = "";
    private String alternateEntryStateProfileId = "";
    private String alternateEntryReturnProfileId = "";
    private String alternateEntryConnectionNamespace = "";
    private String alternateEntryBindingFingerprint = "";
    private String alternateEntryBackendFingerprint = "";
    private volatile String alternateEntrySessionNonce = "";
    private JSONObject alternateEntrySourceProfile;
    private JSONObject alternateEntryConfig;
    private JSONArray alternateEntryCatalogSnapshot = new JSONArray();
    private JSONObject alternateEntryAppConfigSnapshot;
    private JSONObject alternateEntryCatalogSettingsSnapshot;
    private JSONArray alternateEntrySourceProfiles = new JSONArray();
    private String alternateEntrySerial = "";
    private String alternateEntrySerialSource = SnScanRules.SOURCE_ENTERED;
    private final List<String> alternateEntryPhotos = new ArrayList<>();
    private final LinkedHashMap<String, Boolean> alternateEntryToggleStates =
        new LinkedHashMap<>();
    private String pendingAlternateEntryPhotoPath = "";
    private Uri pendingAlternateEntryPhotoUri;
    private String pendingAlternateEntryPhotoGuard = "";
    private String pendingAlternateEntryScanGuard = "";
    private AlternateEntryAsyncReservation pendingAlternateEntryPhotoReservation;
    private AlternateEntryAsyncReservation pendingAlternateEntryScanReservation;
    // SharedPreferences may update its in-memory map even when commit() reports false. Keep every
    // edit/submit path closed until an exact successful cleanup or process-level restore resolves it.
    private volatile boolean alternateEntryReservationStorageAmbiguous;
    // A committed Panel switch is not usable until its durable alternate-photo cleanup receipt has
    // been replayed completely. Startup sets this before any update, Panel, captcha, or login work.
    private volatile boolean panelBoundaryCleanupBlocked;
    // Created only by a state mutation that already rechecked candidates while holding
    // HANDOFF_LOCK. An open page or a later non-empty observation can never mint this proof.
    private String alternateEntryContinuationToken = "";

    private TextView loginStatus;
    private TextView updateChannelText;
    private EditText accountEdit;
    private EditText passwordEdit;
    private EditText captchaEdit;
    private ImageView captchaView;
    private Spinner profileSpinner;
    private EditText snEdit;
    private EditText baseSnEdit;
    // Additional configured identifier inputs, rebuilt for each form.
    private final java.util.LinkedHashMap<String, EditText> pluginSnEdits = new java.util.LinkedHashMap<>();
    private RadioGroup gradeGroup;
    private TextView gradeLabel;
    private TextView baseLabel;
    private TextView basePrompt;
    private LinearLayout baseRow;
    private LinearLayout baseActionRow;
    private LinearLayout workflowArtifactPanel;
    private TextView workflowArtifactText;
    private TextView photoPrompt;
    private TextView summaryText;
    private TextView logText;
    private TextView missingMaterialsText;
    private TextView crashLogText;
    private LinearLayout unitList;
    private Spinner alternateEntryProfileSpinner;
    private EditText alternateEntrySerialEdit;
    private TextView alternateEntrySerialText;
    private TextView alternateEntryPhotoText;
    private LinearLayout alternateEntryPhotoList;
    private LinearLayout alternateEntryToggleList;
    private AlertDialog submitProgressDialog;
    private TextView submitProgressMessage;
    private ProgressBar submitProgressBar;
    private TextView submitProgressLabel;
    private int submitProgressTotal;
    private int submitProgressCompleted;
    private UpdateManager updateManager;
    private FormCatalogManager formCatalogManager;
    private ScrollView insetAwarePageView;
    // Browser pairing is memory-only in MainActivity. The short-lived ticket never enters normal
    // settings, saved-instance state, diagnostics, drafts, or the long-term Panel key slot.
    private PanelPairingLinkRules.Request pendingPanelPairingRequest;
    private AlertDialog panelPairingDialog;
    private boolean pendingPanelPairingLinkInvalid;
    private boolean panelPairingRedeemInFlight;
    private int panelPairingGeneration;
    private boolean panelPairingBrokerOwner;
    private PanelPairingRedeemer.Attempt panelPairingAttempt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panelPairingBrokerOwner = PanelPairingBroker.mainActivityCreated();
        if (!panelPairingBrokerOwner) {
            // The stable exported launcher alias may be invoked again while Capture/Scanner owns
            // the task top. Never create a second in-memory form owner behind that live operation.
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Android 15 enforces edge-to-edge for targetSdk 35. Own the insets explicitly there,
            // while API 23-34 retain the production app's existing decor and IME behavior.
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        discardDisabledFinalSubmissionReplayBarriers();
        // A successful Panel switch may have crashed after its atomic preference commit but before
        // deleting old alternate-entry photos. Replay that receipt before any code can start an
        // update, Panel sync, captcha, login, notification flush, or other bound operation.
        panelBoundaryCleanupBlocked = true;
        if (recoverPanelConnectionAlternateCleanupReceipt()) {
            // Application deliberately leaves the reporter as a no-op while a receipt exists.
            // Initialization is idempotent when there was no receipt and starts only after cleanup.
            FailureReporter.init(this);
        } else {
            Diagnostics.append(this,
                "Panel connection alternate cleanup recovery remains blocked");
        }
        // Resolve an interrupted explicit queue deletion before any Panel pair can be loaded,
        // published to diagnostics, or promoted. The helper owns HANDOFF_LOCK, preserving the
        // process-wide lock order used by every queue mutation and candidate handoff.
        recoverManualQueueDeleteTransaction();
        recoverReprintAttemptsAfterProcessDeath();
        lang = prefs.getString("lang", "zh");
        Diagnostics.append(this, "MainActivity onCreate");
        createNotificationChannel();
        requestRuntimePermissions();
        boolean restoredAlternateEntry = false;
        try {
            String panelBase = AppConfig.panelBase(this);
            String catalogKey = AppConfig.catalogKey(this);
            PanelPairCacheCoordinator.ActivePair activePair =
                PanelPairCacheCoordinator.loadActivePair(this);
            appConfig = activePair == null ? null : activePair.config;
            FormCatalog.BoundSnapshot boundCatalog =
                activePair == null ? null : activePair.catalog;
            if (boundCatalog != null) {
                allProfiles = boundCatalog.profiles;
                catalogSettings = boundCatalog.settings;
                activeCatalogVersion = boundCatalog.version;
                activePanelPairSha256 = activePair.pairSha256;
            } else {
                // Keep the fictional seed renderable for an unconfigured installation and for the
                // Settings preview. panelBootstrapState prevents a configured device from entering
                // or operating that fallback while its real catalog is still synchronizing.
                FormCatalog.PreviewSnapshot preview =
                    FormCatalog.loadBundledPreviewSnapshot(this);
                allProfiles = preview.profiles;
                catalogSettings = preview.settings;
                // Only an unconfigured install may operate this identity, and only locally. A
                // configured-but-unsynchronized Panel keeps zero/empty identity and fails closed.
                if (panelBase.isEmpty() && catalogKey.isEmpty()) {
                    activeCatalogVersion = preview.version;
                    activePanelPairSha256 = preview.pairSha256;
                }
            }
            activateSessionRealm(activePair);
            panelBootstrapState = PanelBootstrapRules.begin(
                AppConfig.connectionNamespaceId(panelBase, catalogKey),
                !panelBase.isEmpty(), appConfig != null,
                AppConfig.catalogVersion(appConfig), boundCatalog != null,
                boundCatalog == null ? 0 : boundCatalog.version);
            publishActiveNotificationSnapshot();
            profiles = filterPickerProfiles(allProfiles);
            if (profiles.length() == 0) {
                throw new JSONException("No profile has pickerVisible=true");
            }
            profile = profiles.getJSONObject(0);
            backfillCompletedAlternateDailyOutputAtStartup();
            restorePendingMainFormTarget();
            restoredAlternateEntry = restoreAlternateEntryState(savedInstanceState);
        } catch (Exception exc) {
            notificationSnapshot = null;
            NotificationClient.clearActiveSnapshot();
            fatal("Profile load failed: " + exc.getMessage());
            return;
        }
        UploadReplayBarrier.RestoreResult restoredAlternateUploadBarrier =
            restoredAlternateEntry ? blockingUploadReplayBarrier() : null;
        if (restoredAlternateEntry && restoredAlternateUploadBarrier == null
                && !savedToken().isEmpty()
                && (activeWorkflowCanContinue() || !panelUseBlocked())) {
            showAlternateEntryPage(alternateEntryId);
        } else {
            if (restoredAlternateEntry) {
                // A process recreation while signed out or while the exact Panel caches are not
                // ready must not delete现场 photos. Persist the bound draft and unload only memory.
                if (saveAlternateEntryDraft(true)) {
                    clearAlternateEntrySession(false);
                } else {
                    suspendAlternateEntrySession();
                }
            }
            showSettingsPage();
            if (restoredAlternateUploadBarrier != null) {
                showUploadReplayBarrierBlock(restoredAlternateUploadBarrier);
            }
        }
        updateManager = new UpdateManager(this);
        formCatalogManager = new FormCatalogManager(this);
        if (!panelBoundaryCleanupBlocked) {
            updateManager.checkOnStartup();
            synchronizePanelConnection(false);
            if (savedToken().isEmpty()) {
                refreshCaptcha();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!panelPairingBrokerOwner) return;
        if (hasFocus) {
            acceptPendingPanelPairingDelivery();
            maybeShowPanelPairingPrompt();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!panelPairingBrokerOwner) return;
        outState.putInt(STATE_PENDING_PHOTO_INDEX, pendingPhotoIndex);
        outState.putString(STATE_PENDING_PHOTO_SIDE, pendingPhotoSide);
        outState.putString(STATE_PENDING_PHOTO_FIELD, pendingPhotoField);
        outState.putString(STATE_PENDING_PHOTO_PATH, pendingOutputPhotoPath);
        outState.putString(STATE_PENDING_OCR_PHOTO_PATH, pendingOcrPhotoPath);
        outState.putInt(STATE_PENDING_RESCAN_SEQUENCE, pendingRescanUnitSequence);
        saveAlternateEntryState(outState);
        // A Bundle is not durable across process death. Flush the exact-bound alternate draft so
        // a camera result or freshly entered identifier is not lost if Android kills the process.
        saveAlternateEntryDraft(true);
        Diagnostics.append(this, "MainActivity state saved");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!panelPairingBrokerOwner) return;
        SystemBarInsets.requestWhenAttached(insetAwarePageView);
        Diagnostics.append(this, "Configuration changed: keyboard=" + newConfig.keyboard
            + " keyboardHidden=" + newConfig.keyboardHidden
            + " hardKeyboardHidden=" + newConfig.hardKeyboardHidden
            + " navigation=" + newConfig.navigation);
        if (!units.isEmpty()) saveDraft();
        if (alternateEntryPageOpen && alternateEntrySerialEdit != null) {
            alternateEntrySerialEdit.requestFocus();
            return;
        }
        if (baseSnEdit != null && baseSnEdit.hasFocus()) {
            refocusBaseInput();
        } else {
            refocusSnInput();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!panelPairingBrokerOwner) return;
        acceptPendingPanelPairingDelivery();
        if (!panelBoundaryCleanupBlocked && updateManager != null) {
            updateManager.resumePendingInstall();
            updateManager.checkOnForeground();
        }
        if (formCatalogManager != null) {
            // Config and catalog share one throttle window and one revision gate. Refreshing only
            // one half here could leave a publish-raced pair permanently mismatched.
            if (formCatalogManager.foregroundCheckDue()) {
                synchronizePanelConnection(false, true);
            }
        }
        if (BuildConfig.CROSS_APP_SESSION_ENABLED) registerSessionReceiver();
        if (BuildConfig.CROSS_APP_SESSION_ENABLED
                && prefs.getBoolean(SessionEventReceiver.PENDING_LOGOUT_KEY, false)) {
            // A peer logged us out while we were backgrounded (the static receiver left a flag).
            handleRemoteLogout(false);
        } else {
            refreshLoginStatus();
            // Foreground active-probe: catches a server-side kick (another device logged in) that no
            // broadcast can surface, since that device isn't on this app's local IPC bus.
            startAuthPolling();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!panelPairingBrokerOwner) return;
        stopAuthPolling();
        unregisterSessionReceiver();
    }

    @Override
    protected void onDestroy() {
        if (!panelPairingBrokerOwner) {
            super.onDestroy();
            return;
        }
        panelPairingGeneration++;
        pendingPanelPairingRequest = null;
        pendingPanelPairingLinkInvalid = false;
        panelPairingRedeemInFlight = false;
        if (panelPairingAttempt != null) {
            panelPairingAttempt.cancel();
            panelPairingAttempt = null;
        }
        if (panelPairingDialog != null) {
            try {
                panelPairingDialog.dismiss();
            } catch (Exception ignored) {
            }
            panelPairingDialog = null;
        }
        if (panelPairingBrokerOwner) PanelPairingBroker.mainActivityDestroyed();
        cancelPanelPairRetry(false);
        super.onDestroy();
    }

    private void showSettingsPage() {
        settingsPageOpen = true;
        alternateEntryPageOpen = false;
        // Recovery evidence must be classified before a candidate can replace the pair that owns
        // it. This is intentionally read-only here; file-only/.bak/.tmp survivors still pin the
        // old pair even when no SharedPreferences mirror survived a prior process crash.
        recoverManualQueueDeleteTransaction();
        reconcileManualQueueCopies(false);
        maybeInstallBoundPanelSnapshotAtSafeBoundary();
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

        // A saved Panel with either bound cache missing is a distinct, fail-locked synchronization
        // state. Do not describe it as an editable sample or silently reuse a legacy cache.
        if (panelUseBlocked() || !backendConfigured()) {
            TextView banner = text(panelConnectionSyncBlocked()
                ? t("panel_syncing_detail")
                : (panelUseBlocked()
                    ? t("panel_active_pair_pending_detail") : t("panel_required_detail")),
                13, true);
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
        if (!panelUseBlocked() && isSampleCatalog()) {
            TextView banner = text(t("sample_catalog_detail"), 13, true);
            banner.setTextColor(0xFF92400E);
            banner.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable bannerBg = new GradientDrawable();
            bannerBg.setColor(0xFFFFFBEB);
            bannerBg.setStroke(dp(1), 0xFFF59E0B);
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
        accountEdit.setText(savedAccount());
        loginPanel.addView(accountEdit);
        passwordEdit = edit(t("password"));
        passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordEdit.setText(savedPassword());   // remembered only inside the exact session realm
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
        // notification route are then all driven by that panel. Saving persists + reconnects.
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
            + (backendConfigured() ? apiBase()
                : (panelConnectionSyncBlocked() ? t("panel_syncing_short")
                    : (panelUseBlocked()
                        ? t("panel_pair_pending_short") : t("panel_unconfigured")))), 12, false);
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
        setPageContentView(scroll);
        refreshLoginStatus();
        scroll.post(this::maybeShowPanelPairingPrompt);
    }

    private void showFormPage() {
        showFormPage(true);
    }

    private void showFormPage(boolean promptSavedDraft) {
        showFormPage(promptSavedDraft, true);
    }

    /** Re-read the cached catalog (freshly synced from the panel) into the in-memory profile list, so a
     *  fresh install or a new publish shows the real forms without needing an app restart. Keeps the
     *  current selection if its id still exists. No-op if nothing usable is cached. */
    private void reloadCatalogProfiles() {
        try {
            boolean configured = !AppConfig.panelBase(this).isEmpty();
            if (configured) {
                // Config and catalog may only advance together. The safe-boundary helper installs
                // both or neither; never replace profiles/settings independently here.
                maybeInstallBoundPanelSnapshotAtSafeBoundary();
                return;
            }
            FormCatalog.PreviewSnapshot preview =
                FormCatalog.loadBundledPreviewSnapshot(this);
            JSONArray reloaded = preview.profiles;
            if (reloaded.length() == 0) return;
            String currentId = (profile != null) ? profile.optString("id", "") : "";
            allProfiles = reloaded;
            catalogSettings = preview.settings;
            activeCatalogVersion = preview.version;
            activePanelPairSha256 = preview.pairSha256;
            profiles = filterPickerProfiles(allProfiles);
            JSONObject keep = null;
            for (int i = 0; i < profiles.length(); i++) {
                if (profiles.getJSONObject(i).optString("id", "").equals(currentId)) { keep = profiles.getJSONObject(i); break; }
            }
            profile = keep != null ? keep : (profiles.length() > 0 ? profiles.getJSONObject(0) : null);
        } catch (Exception ignored) {
            // keep the currently loaded profiles on any error
        }
    }

    /** Main-picker membership is panel-owned, with one catalog-level fallback for pre-field
     * catalogs. IDs, labels, and links are never interpreted as visibility hints. */
    private JSONArray filterPickerProfiles(JSONArray all) {
        return ProfilePickerRules.visibleProfiles(all);
    }

    private void showFormPage(boolean promptSavedDraft, boolean reloadCatalog) {
        alternateEntryPageOpen = false;
        if (savedToken().isEmpty() && !localSamplePreviewEnabled()) {
            showSettingsPage();
            return;
        }
        if (panelConnectionSyncBlocked() && !activeWorkflowCanContinue()
                && !unsafeContinuationCanResumeMainForm()) {
            notifyBackendUnconfigured();
            showSettingsPage();
            return;
        }
        // Normal navigation picks up a freshly-synced catalog without an app restart. An internal
        // presentation-only rebuild must keep the already selected, already validated profile so a
        // draft cannot cross a catalog-semantics boundary between validation and restore.
        if (reloadCatalog) reloadCatalogProfiles();
        if (!AppConfig.panelBase(this).isEmpty() && !activePanelPairCompatible()) {
            notifyBackendUnconfigured();
            showSettingsPage();
            return;
        }
        if (profiles == null || profiles.length() == 0 || profile == null) {
            alert(t("panel_required_title"), t("no_picker_profiles"));
            showSettingsPage();
            return;
        }
        settingsPageOpen = false;
        profileSelectionReady = false;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF1F5F9);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        String account = savedUserName();
        if (account.isEmpty()) account = savedAccount();
        LinearLayout headerPanel = panel();
        TextView title = text(t("form_title"), 24, true);
        title.setTextColor(0xFF0F172A);
        headerPanel.addView(title);
        TextView accountText = text(account, 16, true);
        accountText.setTextColor(0xFF0F766E);
        accountText.setPadding(0, dp(4), 0, dp(8));
        headerPanel.addView(accountText);
        LinearLayout headerActions = row();
        // Logout takes the flexible slot so the settings control stays visible on narrow screens.
        headerActions.addView(button(t("logout"), v -> logoutToSettings()),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        JSONArray alternateEntries = validAlternateEntries(profile);
        for (int i = 0; i < alternateEntries.length(); i++) {
            JSONObject entry = alternateEntries.optJSONObject(i);
            if (entry == null) continue;
            String entryId = entry.optString("id", "");
            String entryTitle = localized(entry, "title", "titleI18n");
            if (!entryId.isEmpty() && !entryTitle.isEmpty()) {
                headerActions.addView(button(entryTitle,
                    v -> showAlternateEntryPage(entryId)));
            }
        }
        headerActions.addView(iconButton("\u2699", v -> showFormSettingsDialog()));
        headerPanel.addView(headerActions);
        missingMaterialsText = text("", 12, false);
        missingMaterialsText.setTextColor(0xFFB45309);
        missingMaterialsText.setPadding(0, dp(8), 0, 0);
        missingMaterialsText.setVisibility(View.GONE);
        headerPanel.addView(missingMaterialsText);
        root.addView(headerPanel);
        if (isSampleCatalog()) {
            TextView sampleNotice = text(t("sample_catalog_detail"), 13, true);
            sampleNotice.setTextColor(0xFF92400E);
            sampleNotice.setPadding(dp(12), dp(10), dp(12), dp(10));
            root.addView(sampleNotice);
        }

        LinearLayout setupPanel = panel();
        setupPanel.addView(compactLabel(t("form")));
        profileSpinner = new Spinner(this);
        profileSpinner.setAdapter(new ProfileSpinnerAdapter(profiles));
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try {
                    int locked = findProfileIndex(currentProfileId());
                    boolean changing = locked >= 0 && position != locked;
                    if (changing && (submitting || profileOwnedRemoteWorkerActive()
                            || mainFormBoundWorkerActive()
                            || hasPendingMainFormOperation()
                            || hasStoredOrUnreadableReprintAttempt()
                            || hasStoredUploadReplayBarrier()
                            || hasStoredPreviousStepSubmissionAttempt())) {
                        bounceProfileSelection(locked);
                        return;
                    }
                    if (changing && profileSelectionReady && !restoringDraft
                            && hasUnsubmittedUnits() && !saveDraft(true)) {
                        bounceProfileSelection(locked);
                        alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
                        return;
                    }
                    // Android may re-fire the current selection while rebuilding the Spinner.
                    // That callback must not restore/reset a draft or mutate the active profile.
                    if (!changing && profileSelectionReady && !restoringDraft) return;
                    profile = profiles.getJSONObject(position);
                    if (!profileSelectionReady) return;
                    if (restoringDraft) {
                        refreshFormUi();
                        return;
                    }
                    saveLastProfile();
                    // Labels, placeholders, scan controls, extra identifiers and alternate-entry
                    // actions are all owned by the selected Panel profile. Rebuild before restoring
                    // its queue so no view from the previous profile can survive the switch.
                    showFormPage(false, false);
                    restoreCurrentProfileDraftOrEmpty();
                } catch (JSONException exc) {
                    toast(exc.getMessage());
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        setupPanel.addView(profileSpinner);
        applyLastProfileSelection();
        photoOrder = PhotoOrderRules.profileDefault(profile);

        gradeLabel = compactLabel(t("grade_class"));
        setupPanel.addView(gradeLabel);
        gradeGroup = new RadioGroup(this);
        gradeGroup.setOrientation(RadioGroup.HORIZONTAL);
        gradeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            updateGradeButtons();
            refocusSnInput();
        });
        ensureResultButtons();
        updateGradeButtons();
        setupPanel.addView(gradeGroup);
        root.addView(setupPanel);

        LinearLayout capturePanel = panel();
        capturePanel.addView(compactLabel(primaryInputLabel()));
        LinearLayout snRow = row();
        snEdit = edit(inputPlaceholder(false));
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
        if (identifierScanEnabled(false)) {
            snActionRow.addView(button(scanPrompt(false), v -> startSnScan(false)));
        }
        snActionRow.addView(button(t("add"), v -> addTypedSn()));
        capturePanel.addView(snActionRow);

        baseLabel = compactLabel(secondaryInputLabel());
        capturePanel.addView(baseLabel);
        basePrompt = text("", 14, true);
        basePrompt.setTextColor(0xFF334155);
        capturePanel.addView(basePrompt);
        baseRow = row();
        baseSnEdit = edit(inputPlaceholder(true));
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
        if (identifierScanEnabled(true)) {
            baseActionRow.addView(button(scanPrompt(true), v -> startSnScan(true)));
        }
        baseActionRow.addView(button(t("match"), v -> addBaseSn()));
        capturePanel.addView(baseActionRow);

        // Additional profile-owned inputs are snapshotted onto each queued record.
        pluginSnEdits.clear();
        JSONArray extraPlugins = snPlugins();
        for (int pi = 0; extraPlugins != null && pi < extraPlugins.length(); pi++) {
            JSONObject pl = extraPlugins.optJSONObject(pi);
            if (!ProfileFieldRules.isVisible(pl)
                    || !isExtraPluginKey(pl.optString("key"))) continue;
            String pField = pl.optString("field");
            if (pField.isEmpty()) continue;
            // en/es via labelI18n sibling map; missing translation (or old profile) falls back to the
            // zh label, and a wholly absent label falls back to the field id (original behavior).
            String pLabel = localized(pl, "label", "labelI18n");
            if (pLabel.isEmpty()) pLabel = pField;
            capturePanel.addView(compactLabel(pLabel + (pl.optBoolean("required") ? " *" : "")));
            LinearLayout plRow = row();
            EditText plEdit = edit(pl.optString("placeholder", pLabel));
            if ("number".equals(pl.optString("inputType", "text"))) {
                plEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            }
            plRow.addView(plEdit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            capturePanel.addView(plRow);
            pluginSnEdits.put(pField, plEdit);
        }

        workflowArtifactPanel = workflowArtifactBox();
        capturePanel.addView(workflowArtifactPanel);
        capturePanel.addView(compactLabel(t("photos")));
        photoPrompt = text("", 14, true);
        photoPrompt.setTextColor(0xFF334155);
        capturePanel.addView(photoPrompt);
        capturePanel.addView(button(t("take_next_photo"), v -> captureNextPhoto()));
        root.addView(capturePanel);

        LinearLayout submitPanel = panel();
        submitPanel.addView(compactLabel(t("submit")));
        LinearLayout submitRow = row();
        // Previous-record checks already run when the primary identifier is added.
        submitRow.addView(button(t("submit_batch"), v -> submitBatch()));
        if (printingConfiguredForProfile()) {
            submitRow.addView(button(t("print_reconcile_open"), v -> showPrintReconcileDialog()));
        }
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
        setPageContentView(scroll);
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
        if (alternateEntryPageOpen && !alternateEntryId.isEmpty()) {
            showAlternateEntryPage(alternateEntryId);
        } else if (unitList == null) {
            showSettingsPage();
        } else {
            showFormPage();
        }
    }

    // ===== Panel-configured alternate entry =====
    // This restores the old dedicated one-record entry without restoring any deployment-specific
    // names, suffix rules or option-text heuristics. The visible source profile owns the entry and
    // points explicitly at one hidden target profile. AlternateEntryRules validates the complete
    // destination and payload before the first upload and again with the real uploaded URLs.

    private String currentConnectionNamespace() {
        return AppConfig.connectionNamespaceId(
            AppConfig.panelBase(this), AppConfig.catalogKey(this));
    }

    private void bounceProfileSelection(int lockedIndex) {
        if (lockedIndex >= 0 && profileSpinner != null
                && profileSpinner.getSelectedItemPosition() != lockedIndex) {
            profileSpinner.post(() -> profileSpinner.setSelection(lockedIndex));
        }
    }

    private boolean hasPendingMainFormOperation() {
        try {
            // Presence is the lock. Empty, non-string and malformed values are deliberately not
            // treated as absence: only the strict parser at the result boundary may consume them.
            return prefs.getAll().containsKey(PENDING_MAIN_FORM_OPERATION_KEY);
        } catch (RuntimeException error) {
            // An unreadable new-format target is itself a lock. Do not let a profile switch make it
            // still harder to recover. Legacy index/path keys are intentionally ignored here.
            return true;
        }
    }

    /**
     * Presence of an unresolved reprint POST pins the Panel/profile/catalog boundary. Malformed or
     * wrong-typed bytes fail closed and are deliberately preserved for manual recovery.
     */
    private boolean hasStoredOrUnreadableReprintAttempt() {
        try {
            Object raw = prefs.getAll().get(REPRINT_ATTEMPTS_KEY);
            if (raw == null) return false;
            if (!(raw instanceof String)) return true;
            return !PrintReprintAttempt.Store.parse((String) raw).attempts.isEmpty();
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    private boolean mainFormBoundWorkerActive() {
        synchronized (activeOperationNonces) {
            return activeOperationNonces.containsKey(OperationBindingRules.USER_INFO)
                || activeOperationNonces.containsKey(OperationBindingRules.OCR);
        }
    }

    private OperationBindingRules.Binding beginBoundOperation(String kind,
                                                               String tokenSnapshot) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            // A candidate write and a bound remote operation have one total order. Existing OCR or
            // user-info work may finish through the captured continuation lease; no new operation
            // nonce can be created behind an unsafe barrier.
            if (panelConnectionSyncBlocked()) {
                throw new IllegalStateException("Panel candidate blocks new bound operation");
            }
            String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
            OperationBindingRules.Binding binding = OperationBindingRules.capture(
                currentConnectionNamespace(), activeCatalogVersion, currentPanelPairSha256(),
                webFingerprint(), tokenSnapshot, nonce, kind);
            synchronized (activeOperationNonces) {
                activeOperationNonces.put(kind, nonce);
            }
            return binding;
        }
    }

    /** A pre-barrier scan reservation may start its one bound OCR continuation after the barrier. */
    private OperationBindingRules.Binding beginReservedAlternateEntryBoundOperation(
            String kind, String tokenSnapshot,
            AlternateEntryAsyncReservation reservation) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!alternateEntryReservationMayMaterializeLocked(reservation)) {
                throw new IllegalStateException(
                    "Panel candidate blocks unreserved alternate-entry operation");
            }
            String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
            OperationBindingRules.Binding binding = OperationBindingRules.capture(
                currentConnectionNamespace(), activeCatalogVersion, currentPanelPairSha256(),
                webFingerprint(), tokenSnapshot, nonce, kind);
            synchronized (activeOperationNonces) {
                activeOperationNonces.put(kind, nonce);
            }
            return binding;
        }
    }

    private boolean boundOperationMatches(OperationBindingRules.Binding binding,
                                           String tokenSnapshot) {
        if (binding == null) return false;
        String activeNonce;
        synchronized (activeOperationNonces) {
            activeNonce = activeOperationNonces.get(binding.kind);
        }
        return binding.nonce.equals(activeNonce)
            && binding.matchesContext(currentConnectionNamespace(), activeCatalogVersion,
                currentPanelPairSha256(), webFingerprint(), tokenSnapshot, binding.kind)
            && OperationBindingRules.sessionFingerprint(webFingerprint(), savedToken())
                .equals(binding.sessionFingerprint);
    }

    private void requireBoundOperation(OperationBindingRules.Binding binding,
                                       String tokenSnapshot, String phase)
            throws IOException {
        if (!boundOperationMatches(binding, tokenSnapshot)) {
            throw new IOException("stale operation before " + phase);
        }
    }

    private void finishBoundOperation(OperationBindingRules.Binding binding) {
        if (binding == null) return;
        synchronized (activeOperationNonces) {
            if (binding.nonce.equals(activeOperationNonces.get(binding.kind))) {
                activeOperationNonces.remove(binding.kind);
            }
        }
    }

    private boolean hasAlternateEntryPendingData() {
        // A camera output path is a reservation, not form data. Treating its mere observation as
        // a draft would let a lifecycle restart mint old-pair work before the camera returned.
        return !alternateEntrySerial.isEmpty() || !alternateEntryPhotos.isEmpty();
    }

    /** Must be called under HANDOFF_LOCK immediately after the first durable/in-memory data write. */
    private void markAlternateEntryWorkEstablishedLocked() {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            throw new IllegalStateException("alternate-entry boundary lock is required");
        }
        if (!hasAlternateEntryPendingData()) {
            alternateEntryContinuationToken = "";
            return;
        }
        if (!UnsafeCandidateContinuationRules.validAlternateEntryToken(
                alternateEntryContinuationToken)) {
            alternateEntryContinuationToken = newAlternateEntryToken();
        }
    }

    /** Empty/finished state cannot retain a token which a later input could borrow. */
    private void retireAlternateEntryWorkTokenIfEmptyLocked() {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            throw new IllegalStateException("alternate-entry boundary lock is required");
        }
        if (!hasAlternateEntryPendingData()) alternateEntryContinuationToken = "";
    }

    private String newAlternateEntryToken() {
        return java.util.UUID.randomUUID().toString().replace("-", "")
            .toLowerCase(java.util.Locale.US);
    }

    private static void appendAlternateEntryStatePart(StringBuilder target,
                                                       String name, String value) {
        String safe = value == null ? "" : value;
        target.append(name.length()).append(':').append(name)
            .append('=').append(safe.length()).append(':').append(safe).append('\n');
    }

    /** Exact logical base state to which one async scan/photo result is reserved. */
    private String alternateEntryReservationBaseStateSha256() {
        StringBuilder canonical = new StringBuilder();
        appendAlternateEntryStatePart(canonical, "binding", alternateEntryBindingFingerprint);
        appendAlternateEntryStatePart(canonical, "backend", alternateEntryBackendFingerprint);
        appendAlternateEntryStatePart(canonical, "entry",
            alternateEntryConfig == null ? "" : alternateEntryConfig.optString("id", ""));
        appendAlternateEntryStatePart(canonical, "source",
            alternateEntrySourceProfile == null
                ? "" : alternateEntrySourceProfile.optString("id", ""));
        appendAlternateEntryStatePart(canonical, "serial", alternateEntrySerial);
        appendAlternateEntryStatePart(canonical, "serialSource", alternateEntrySerialSource);
        appendAlternateEntryStatePart(canonical, "continuationToken",
            alternateEntryContinuationToken);
        appendAlternateEntryStatePart(canonical, "photoCount",
            String.valueOf(alternateEntryPhotos.size()));
        for (String path : alternateEntryPhotos) {
            appendAlternateEntryStatePart(canonical, "photoPath", path);
        }
        List<String> toggleKeys = new ArrayList<>(alternateEntryToggleStates.keySet());
        Collections.sort(toggleKeys);
        appendAlternateEntryStatePart(canonical, "toggleCount",
            String.valueOf(toggleKeys.size()));
        for (String key : toggleKeys) {
            appendAlternateEntryStatePart(canonical, "toggleKey", key);
            appendAlternateEntryStatePart(canonical, "toggleValue",
                String.valueOf(Boolean.TRUE.equals(alternateEntryToggleStates.get(key))));
        }
        return AlternateEntryAsyncReservation.sha256(canonical.toString());
    }

    private String alternateEntryReservationPreferenceKey(String kind) {
        return AlternateEntryAsyncReservation.KIND_PHOTO.equals(kind)
            ? PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY
            : PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY;
    }

    private String alternateEntryReservationGuardPreferenceKey(String kind) {
        return AlternateEntryAsyncReservation.KIND_PHOTO.equals(kind)
            ? PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY
            : PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY;
    }

    /** Reads only a strict, exact preallocated reservation; legacy pending observations return null. */
    private AlternateEntryAsyncReservation exactStoredAlternateEntryReservationLocked(
            String kind) {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)
                || alternateEntryReservationStorageAmbiguous) return null;
        try {
            Map<String, ?> stored = prefs.getAll();
            Object raw = stored.get(alternateEntryReservationPreferenceKey(kind));
            Object rawGuard = stored.get(alternateEntryReservationGuardPreferenceKey(kind));
            if (!(raw instanceof String) || !(rawGuard instanceof String)) return null;
            String guard = (String) rawGuard;
            String outputPath = "";
            if (AlternateEntryAsyncReservation.KIND_PHOTO.equals(kind)) {
                Object rawPath = stored.get(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
                if (!(rawPath instanceof String)) return null;
                outputPath = (String) rawPath;
            }
            AlternateEntryAsyncReservation reservation =
                AlternateEntryAsyncReservation.parse((String) raw);
            if (!alternateEntryOperationMatches(guard)
                    || !reservation.matches(kind,
                        AlternateEntryDraftState.accountFingerprint(
                            savedAccount()),
                        currentConnectionNamespace(), activeCatalogVersion,
                        currentPanelPairSha256(), alternateEntryBindingFingerprint,
                        alternateEntryBackendFingerprint, guard,
                        alternateEntryReservationBaseStateSha256(), outputPath)) {
                return null;
            }
            if (AlternateEntryAsyncReservation.KIND_PHOTO.equals(kind)) {
                pendingAlternateEntryPhotoReservation = reservation;
            } else {
                pendingAlternateEntryScanReservation = reservation;
            }
            return reservation;
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Alternate-entry " + kind
                + " reservation rejected: " + conciseError(invalid));
            return null;
        }
    }

    private List<AlternateEntryScanRecoveryRules.Binding>
            activeAlternateEntryScanRecoveryBindings(String entryId) {
        List<AlternateEntryScanRecoveryRules.Binding> bindings = new ArrayList<>();
        if (entryId == null || entryId.isEmpty()) return bindings;
        for (int index = 0; profiles != null && index < profiles.length(); index++) {
            JSONObject source = profiles.optJSONObject(index);
            JSONObject entry = alternateEntryById(source, entryId);
            if (source == null || entry == null) continue;
            String fingerprint = alternateEntryBindingFingerprint(source, entry, allProfiles);
            if (fingerprint.isEmpty()) continue;
            bindings.add(new AlternateEntryScanRecoveryRules.Binding(
                source.optString("id", ""), entryId, fingerprint));
        }
        return bindings;
    }

    /**
     * Accepts an exact current-base reservation, or the same exact side-effect-free scan after a
     * cold start when source/toggle-only state was intentionally not a durable draft. Cancellation
     * never requires the dead Activity nonce, but still binds account, Panel pair, catalog, backend,
     * stored guard and a unique active-catalog entry binding.
     */
    private AlternateEntryAsyncReservation cancelableStoredAlternateEntryScanLocked() {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)
                || alternateEntryReservationStorageAmbiguous) return null;
        try {
            Map<String, ?> stored = prefs.getAll();
            if (stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY)
                    || stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY)
                    || stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY)
                    || !pendingAlternateEntryPhotoPath.isEmpty()
                    || !pendingAlternateEntryPhotoGuard.isEmpty()
                    || pendingAlternateEntryPhotoReservation != null) {
                return null;
            }
            Object raw = stored.get(PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY);
            Object rawGuard = stored.get(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY);
            if (!(raw instanceof String) || !(rawGuard instanceof String)) return null;
            String guard = (String) rawGuard;
            AlternateEntryAsyncReservation reservation =
                AlternateEntryAsyncReservation.parse((String) raw);
            boolean exactCurrentBase = reservation.matches(
                    AlternateEntryAsyncReservation.KIND_SCAN,
                    AlternateEntryDraftState.accountFingerprint(savedAccount()),
                    currentConnectionNamespace(), activeCatalogVersion,
                    currentPanelPairSha256(), alternateEntryBindingFingerprint,
                    alternateEntryBackendFingerprint, guard,
                    alternateEntryReservationBaseStateSha256(), "");
            boolean sideEffectFreeColdStart = !exactCurrentBase
                && AlternateEntryScanRecoveryRules.canCancelSideEffectFreeScan(
                    reservation,
                    AlternateEntryDraftState.accountFingerprint(savedAccount()),
                    currentConnectionNamespace(), activeCatalogVersion,
                    currentPanelPairSha256(), currentBackendAdapterFingerprint(), guard,
                    alternateEntryId,
                    activeAlternateEntryScanRecoveryBindings(alternateEntryId),
                    stored.containsKey(alternateEntryDraftPreferenceKey())
                        || stored.containsKey(
                            alternateEntryContinuationProofPreferenceKey()),
                    hasAlternateEntryPendingData(), false,
                    UnsafeCandidateContinuationRules.validAlternateEntryToken(
                        alternateEntryContinuationToken));
            if (!exactCurrentBase && !sideEffectFreeColdStart) {
                return null;
            }
            pendingAlternateEntryScanGuard = guard;
            pendingAlternateEntryScanReservation = reservation;
            return reservation;
        } catch (RuntimeException invalid) {
            Diagnostics.append(this,
                "Cancelable independent-entry scan reservation rejected: "
                    + conciseError(invalid));
            return null;
        }
    }

    /** Clears only the re-read exact scan token; submission and upload journals are untouched. */
    private boolean cancelStoredAlternateEntryScan(
            AlternateEntryAsyncReservation expected) {
        if (expected == null) return false;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            AlternateEntryAsyncReservation exact =
                cancelableStoredAlternateEntryScanLocked();
            if (!AlternateEntryScanRecoveryRules.sameReservation(expected, exact)) {
                return false;
            }
            if (!prefs.edit()
                    .remove(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY)
                    .remove(PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY)
                    .commit()) {
                alternateEntryReservationStorageAmbiguous = true;
                return false;
            }
            alternateEntryReservationStorageAmbiguous = false;
            pendingAlternateEntryScanGuard = "";
            pendingAlternateEntryScanReservation = null;
            return true;
        }
    }

    private Set<String> liveAlternateEntryReservationTokensLocked() {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                return liveAlternateEntryReservationTokensLocked();
            }
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        AlternateEntryAsyncReservation scan = exactStoredAlternateEntryReservationLocked(
            AlternateEntryAsyncReservation.KIND_SCAN);
        AlternateEntryAsyncReservation photo = exactStoredAlternateEntryReservationLocked(
            AlternateEntryAsyncReservation.KIND_PHOTO);
        if (scan != null) tokens.add(scan.reservationToken);
        if (photo != null) tokens.add(photo.reservationToken);
        return tokens;
    }

    /** Captures each pre-barrier activity token together with its already allocated result token. */
    private List<UnsafeCandidateContinuationRules.AlternateReservation>
            liveAlternateEntryReservationPermitsLocked() {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                return liveAlternateEntryReservationPermitsLocked();
            }
        }
        List<UnsafeCandidateContinuationRules.AlternateReservation> reservations =
            new ArrayList<>();
        AlternateEntryAsyncReservation scan = exactStoredAlternateEntryReservationLocked(
            AlternateEntryAsyncReservation.KIND_SCAN);
        AlternateEntryAsyncReservation photo = exactStoredAlternateEntryReservationLocked(
            AlternateEntryAsyncReservation.KIND_PHOTO);
        if (scan != null) {
            reservations.add(UnsafeCandidateContinuationRules.alternateReservation(
                scan.reservationToken, scan.resultContinuationToken));
        }
        if (photo != null) {
            reservations.add(UnsafeCandidateContinuationRules.alternateReservation(
                photo.reservationToken, photo.resultContinuationToken));
        }
        return reservations;
    }

    private AlternateEntryAsyncReservation createAlternateEntryReservationLocked(
            String kind, String operationGuard, String outputPath) {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            throw new IllegalStateException("alternate-entry boundary lock is required");
        }
        if (!alternateEntryExpansionAllowedLocked()
                || !alternateEntryOperationMatches(operationGuard)) return null;
        // If the reservation extends an existing draft, flush its exact state+token first. The
        // reservation must never outlive the proof of the base state it names.
        if (hasAlternateEntryPendingData() && !saveAlternateEntryDraft(true)) return null;
        String resultToken = UnsafeCandidateContinuationRules.validAlternateEntryToken(
            alternateEntryContinuationToken)
                ? alternateEntryContinuationToken : newAlternateEntryToken();
        return AlternateEntryAsyncReservation.create(kind, newAlternateEntryToken(), resultToken,
            AlternateEntryDraftState.accountFingerprint(savedAccount()),
            currentConnectionNamespace(), activeCatalogVersion, currentPanelPairSha256(),
            alternateEntryBindingFingerprint, alternateEntryBackendFingerprint,
            operationGuard, alternateEntryReservationBaseStateSha256(), outputPath);
    }

    private boolean alternateEntryReservationMayMaterializeLocked(
            AlternateEntryAsyncReservation expected) {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK) || expected == null) return false;
        AlternateEntryAsyncReservation exact =
            exactStoredAlternateEntryReservationLocked(expected.kind);
        if (exact == null || !exact.reservationToken.equals(expected.reservationToken)
                || !exact.resultContinuationToken.equals(
                    expected.resultContinuationToken)) return false;
        if (!unsafeCandidatesBlockActiveUse()) return true;
        return UnsafeCandidateContinuationRules.permitsAlternateReservation(
            unsafeCandidateContinuationLease, exact.reservationToken,
            currentConnectionNamespace(), activeCatalogVersion, currentPanelPairSha256());
    }

    /**
     * Linearizes an expanding alternate-entry edit against candidate staging. The mutation itself
     * must remain in the caller's same HANDOFF_LOCK block; a successful check is not a transferable
     * permission.
     */
    private boolean alternateEntryExpansionAllowedLocked() {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            throw new IllegalStateException("alternate-entry boundary lock is required");
        }
        return !panelBoundaryCleanupBlocked && !unsafeCandidatesBlockActiveUse();
    }

    private String optionalStringPreference(String key) {
        try {
            Object value = prefs.getAll().get(key);
            return value instanceof String ? (String) value : "";
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Preference read failed for " + key + ": "
                + conciseError(error));
            return "";
        }
    }

    private boolean hasPendingAlternateEntryAsyncReservationEvidence() {
        if (alternateEntryReservationStorageAmbiguous
                || !pendingAlternateEntryPhotoPath.isEmpty()
                || !pendingAlternateEntryPhotoGuard.isEmpty()
                || !pendingAlternateEntryScanGuard.isEmpty()
                || pendingAlternateEntryPhotoReservation != null
                || pendingAlternateEntryScanReservation != null) return true;
        try {
            Map<String, ?> stored = prefs.getAll();
            return stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY)
                || stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY)
                || stored.containsKey(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY)
                || stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY)
                || stored.containsKey(PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY);
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    private AlternateEntryDraftState storedAlternateEntryDraftStrict() {
        String key = alternateEntryDraftPreferenceKey();
        Map<String, ?> stored = prefs.getAll();
        if (!stored.containsKey(key) || !(stored.get(key) instanceof String)) {
            throw new IllegalStateException("alternate-entry draft is missing or unreadable");
        }
        return AlternateEntryDraftState.parse((String) stored.get(key));
    }

    private List<AlternateEntryDraftState.PhotoEvidence> alternateEntryPhotoEvidence(
            List<String> paths) {
        List<AlternateEntryDraftState.PhotoEvidence> evidence = new ArrayList<>();
        for (String path : paths == null ? Collections.<String>emptyList() : paths) {
            File file = new File(path);
            if (!file.isFile() || file.length() <= 0L) {
                throw new IllegalStateException("alternate-entry photo is missing");
            }
            evidence.add(AlternateEntryDraftState.PhotoEvidence.of(
                path, file.length(), Math.max(0L, file.lastModified())));
        }
        return evidence;
    }

    private String alternateEntryDraftFingerprint(AlternateEntryDraftState draft) {
        if (draft == null) throw new IllegalStateException("alternate-entry draft is required");
        return draft.sourceSnapshotSha256(alternateEntryPhotoEvidence(draft.photos));
    }

    private AlternateEntryDraftState inMemoryAlternateEntryDraftState() {
        if (alternateEntrySourceProfile == null || alternateEntryConfig == null
                || alternateEntryConnectionNamespace.isEmpty()
                || alternateEntryBindingFingerprint.isEmpty()
                || alternateEntryBackendFingerprint.isEmpty()) {
            throw new IllegalStateException("alternate-entry binding is unavailable");
        }
        List<String> photos = new ArrayList<>();
        for (String path : alternateEntryPhotos) if (hasFile(path)) photos.add(path);
        return AlternateEntryDraftState.create(
            AlternateEntryDraftState.accountFingerprint(savedAccount()),
            alternateEntryConnectionNamespace, alternateEntryBindingFingerprint,
            alternateEntryBackendFingerprint, alternateEntryConfig.optString("id", ""),
            alternateEntrySourceProfile.optString("id", ""),
            alternateEntryReturnProfileId, alternateEntrySerial,
            alternateEntrySerialSource, photos, alternateEntryToggleStates);
    }

    private String alternateEntryDraftPreferenceKey() {
        return panelStatePreferenceKey(ALTERNATE_ENTRY_DRAFT_KEY);
    }

    private String alternateEntryContinuationProofPreferenceKey() {
        return panelStatePreferenceKey(ALTERNATE_ENTRY_CONTINUATION_PROOF_KEY);
    }

    /** Restores only a proof for this exact durable draft and active immutable Panel pair. */
    private String exactStoredAlternateEntryContinuationTokenLocked(
            AlternateEntryDraftState draft) {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK) || draft == null) return "";
        try {
            String key = alternateEntryContinuationProofPreferenceKey();
            Map<String, ?> stored = prefs.getAll();
            if (!stored.containsKey(key) || !(stored.get(key) instanceof String)) return "";
            AlternateEntryContinuationProof proof = AlternateEntryContinuationProof.parse(
                (String) stored.get(key));
            return proof.matches(currentConnectionNamespace(), activeCatalogVersion,
                    currentPanelPairSha256(), alternateEntryBindingFingerprint,
                    draft.continuationStateSha256())
                ? proof.token : "";
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Alternate-entry continuation proof rejected: "
                + conciseError(invalid));
            return "";
        }
    }

    private boolean hasStoredAlternateEntryDraft() {
        return prefs.contains(alternateEntryDraftPreferenceKey());
    }

    private String alternateSubmissionAttemptPreferenceKey() {
        return panelStatePreferenceKey(ALTERNATE_SUBMISSION_ATTEMPT_KEY);
    }

    private AlternateSubmissionAttempt.RestoreResult restoreAlternateSubmissionAttempt() {
        return restoreSubmissionAttempt(
            alternateSubmissionAttemptPreferenceKey(), "Alternate");
    }

    private AlternateSubmissionAttempt.RestoreResult restoreSubmissionAttempt(
            String key, String diagnosticLabel) {
        AlternateSubmissionAttempt.RestoreResult result;
        try {
            Map<String, ?> stored = prefs.getAll();
            result = AlternateSubmissionAttempt.restoreStoredValue(
                stored.containsKey(key), stored.get(key));
        } catch (RuntimeException error) {
            Diagnostics.append(this, diagnosticLabel + " submission journal read failed: "
                + conciseError(error));
            result = AlternateSubmissionAttempt.restoreStoredValue(true, Boolean.TRUE);
        }
        if (result.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                && result.requiresWriteBack && result.attempt != null) {
            // Persist POSTING -> UNCERTAIN recovery before any UI can offer another submission.
            if (!prefs.edit().putString(key, result.attempt.toJsonString()).commit()) {
                Diagnostics.append(this,
                    "Could not persist recovered UNCERTAIN " + diagnosticLabel
                        + " submission journal");
            }
        }
        return result;
    }

    private boolean writeAlternateSubmissionAttempt(AlternateSubmissionAttempt attempt) {
        return attempt != null && prefs.edit().putString(
            alternateSubmissionAttemptPreferenceKey(), attempt.toJsonString()).commit();
    }

    private boolean clearAlternateSubmissionAttempt() {
        return prefs.edit().remove(alternateSubmissionAttemptPreferenceKey()).commit();
    }

    private String mainSubmissionAttemptPreferenceKey() {
        return panelStatePreferenceKey(MAIN_SUBMISSION_ATTEMPT_KEY);
    }

    private AlternateSubmissionAttempt.RestoreResult restoreMainSubmissionAttempt() {
        return restoreSubmissionAttempt(mainSubmissionAttemptPreferenceKey(), "Main");
    }

    private boolean writeMainSubmissionAttempt(AlternateSubmissionAttempt attempt) {
        return attempt != null && prefs.edit().putString(
            mainSubmissionAttemptPreferenceKey(), attempt.toJsonString()).commit();
    }

    private boolean clearMainSubmissionAttempt() {
        return prefs.edit().remove(mainSubmissionAttemptPreferenceKey()).commit();
    }

    private String previousStepSubmissionAttemptPreferenceKey() {
        return panelStatePreferenceKey(PREVIOUS_STEP_SUBMISSION_ATTEMPT_KEY);
    }

    private boolean hasStoredPreviousStepSubmissionAttempt() {
        return prefs.contains(previousStepSubmissionAttemptPreferenceKey());
    }

    private PreviousStepSubmissionAttempt.RestoreResult
            restorePreviousStepSubmissionAttempt() {
        String key = previousStepSubmissionAttemptPreferenceKey();
        PreviousStepSubmissionAttempt.RestoreResult result;
        try {
            Map<String, ?> stored = prefs.getAll();
            result = PreviousStepSubmissionAttempt.restoreStoredValue(
                stored.containsKey(key), stored.get(key));
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Previous-step submission journal read failed: "
                + conciseError(error));
            result = PreviousStepSubmissionAttempt.restoreStoredValue(true, Boolean.TRUE);
        }
        if (result.kind == PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                && result.requiresWriteBack && result.attempt != null) {
            // A crash with POSTING on disk has an unknowable server outcome. Make the lock durable
            // before any restored draft can reach another submit path.
            if (!prefs.edit().putString(
                    key, result.attempt.toJsonString()).commit()) {
                Diagnostics.append(this,
                    "Could not persist recovered UNCERTAIN previous-step journal");
            }
        }
        return result;
    }

    private boolean writePreviousStepSubmissionAttempt(
            PreviousStepSubmissionAttempt attempt) {
        return attempt != null && prefs.edit().putString(
            previousStepSubmissionAttemptPreferenceKey(),
            attempt.toJsonString()).commit();
    }

    private boolean clearPreviousStepSubmissionAttempt() {
        return prefs.edit().remove(
            previousStepSubmissionAttemptPreferenceKey()).commit();
    }

    /** Best-effort upgrade cleanup for final-POST records which no longer block operator retry. */
    private void discardDisabledFinalSubmissionReplayBarriers() {
        if (DURABLE_FINAL_SUBMISSION_REPLAY_BARRIER_ENABLED) return;
        discardReplayableFinalSubmissionAttempt(
            restoreMainSubmissionAttempt(), true);
        discardReplayableFinalSubmissionAttempt(
            restoreAlternateSubmissionAttempt(), false);
    }

    private void discardReplayableFinalSubmissionAttempt(
            AlternateSubmissionAttempt.RestoreResult restored, boolean main) {
        if (restored.kind == AlternateSubmissionAttempt.RestoreKind.NONE) return;
        // Preserve an acknowledged write so existing local completion recovery can finish. Only
        // PREPARED/POSTING/UNCERTAIN/rejected or malformed slots lose their blocking authority.
        if (restored.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                && restored.attempt != null
                && restored.attempt.state == AlternateSubmissionAttempt.State.COMPLETED) {
            return;
        }
        boolean cleared;
        try {
            cleared = main ? clearMainSubmissionAttempt()
                : clearAlternateSubmissionAttempt();
        } catch (RuntimeException error) {
            cleared = false;
        }
        if (!cleared) {
            Diagnostics.append(this,
                "Disabled final submission lock cleanup will retry");
        }
    }

    private boolean hasStoredUploadReplayBarrier() {
        return blockingUploadReplayBarrier() != null;
    }

    private UploadReplayBarrier.RestoreResult restoreUploadReplayBarrier() {
        UploadReplayBarrier retained = uploadReplayBarrierClearFailure;
        if (retained != null) {
            return UploadReplayBarrier.restoreStoredValue(
                true, retained.toJsonString());
        }
        try {
            Map<String, ?> stored = prefs.getAll();
            return UploadReplayBarrier.restoreStoredValue(
                stored.containsKey(UPLOAD_REPLAY_BARRIER_KEY),
                stored.get(UPLOAD_REPLAY_BARRIER_KEY));
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Upload replay barrier read failed: "
                + conciseError(error));
            return UploadReplayBarrier.restoreStoredValue(true, Boolean.TRUE);
        }
    }

    private boolean writeUploadReplayBarrier(UploadReplayBarrier barrier) {
        if (barrier == null || !prefs.edit().putString(
                UPLOAD_REPLAY_BARRIER_KEY, barrier.toJsonString()).commit()) {
            return false;
        }
        UploadReplayBarrier.RestoreResult readBack = restoreUploadReplayBarrier();
        return readBack.kind == UploadReplayBarrier.RestoreKind.RESTORED
            && readBack.barrier != null
            && readBack.barrier.matches(barrier.identity);
    }

    private boolean beginUploadReplayBarrier(UploadReplayBarrier.Identity identity) {
        if (identity == null) return false;
        if (!DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED) {
            discardDisabledUploadReplayBarrier();
            return true;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                if (UpdateManager.installerHandoffActive(this)) {
                    throw new IllegalStateException(
                        "installer handoff is already active");
                }
                UploadReplayBarrier barrier = UploadReplayBarrier.prepare(
                    identity, restoreUploadReplayBarrier());
                return writeUploadReplayBarrier(barrier);
            } catch (RuntimeException blocked) {
                Diagnostics.append(this, "Upload replay barrier start blocked: "
                    + conciseError(blocked));
                return false;
            }
        }
    }

    /** Clear only the exact in-memory operation which durably reached its local terminal state. */
    private boolean clearUploadReplayBarrier(UploadReplayBarrier.Identity expected) {
        if (!DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED) {
            discardDisabledUploadReplayBarrier();
            return expected != null;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            UploadReplayBarrier.RestoreResult restored = restoreUploadReplayBarrier();
            if (expected == null
                    || restored.kind != UploadReplayBarrier.RestoreKind.RESTORED
                    || restored.barrier == null
                    || !restored.barrier.matches(expected)) {
                return false;
            }
            UploadReplayBarrier retained = restored.barrier;
            String retainedJson = retained.toJsonString();
            // commit(false) may already have removed the key from SharedPreferences' in-process
            // map even though the disk transaction failed. Publish the retained record as a
            // process-local fail-closed guard before attempting removal, then put the exact bytes
            // back into the map on every ambiguous outcome.
            uploadReplayBarrierClearFailure = retained;
            boolean removed;
            try {
                removed = prefs.edit().remove(UPLOAD_REPLAY_BARRIER_KEY).commit();
            } catch (RuntimeException error) {
                removed = false;
                Diagnostics.append(this, "Upload replay barrier removal failed: "
                    + conciseError(error));
            }
            if (!removed) {
                restoreUploadReplayBarrierAfterFailedRemoval(retainedJson);
                return false;
            }
            try {
                if (prefs.contains(UPLOAD_REPLAY_BARRIER_KEY)) {
                    restoreUploadReplayBarrierAfterFailedRemoval(retainedJson);
                    return false;
                }
            } catch (RuntimeException error) {
                Diagnostics.append(this, "Upload replay barrier read-back failed: "
                    + conciseError(error));
                restoreUploadReplayBarrierAfterFailedRemoval(retainedJson);
                return false;
            }
            uploadReplayBarrierClearFailure = null;
            return true;
        }
    }

    private void restoreUploadReplayBarrierAfterFailedRemoval(String retainedJson) {
        try {
            // apply() updates the in-process map before returning. AtomicFile-backed preferences
            // retain the prior on-disk value when the failed remove could not be committed.
            prefs.edit().putString(UPLOAD_REPLAY_BARRIER_KEY, retainedJson).apply();
        } catch (RuntimeException restoreFailure) {
            Diagnostics.append(this, "Upload replay barrier fail-closed restore failed: "
                + conciseError(restoreFailure));
        }
    }

    private boolean uploadReplayBarrierMatches(
            UploadReplayBarrier.Identity expected) {
        if (!DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED) return expected != null;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            UploadReplayBarrier.RestoreResult restored = restoreUploadReplayBarrier();
            return expected != null
                && restored.kind == UploadReplayBarrier.RestoreKind.RESTORED
                && restored.barrier != null
                && restored.barrier.matches(expected);
        }
    }

    private UploadReplayBarrier.RestoreResult blockingUploadReplayBarrier() {
        if (!DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED) {
            discardDisabledUploadReplayBarrier();
            return null;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            UploadReplayBarrier.RestoreResult restored = restoreUploadReplayBarrier();
            if (restored.kind == UploadReplayBarrier.RestoreKind.NONE) return null;
            if (retireExactlyCompletedUploadReplayBarrier(restored)) return null;
            return restored;
        }
    }

    /** Best-effort migration for devices which were stranded by the removed upload-only lock. */
    private void discardDisabledUploadReplayBarrier() {
        uploadReplayBarrierClearFailure = null;
        try {
            if (prefs.contains(UPLOAD_REPLAY_BARRIER_KEY)
                    && !prefs.edit().remove(UPLOAD_REPLAY_BARRIER_KEY).commit()) {
                Diagnostics.append(this,
                    "Disabled upload-only lock cleanup will retry");
            }
        } catch (RuntimeException error) {
            Diagnostics.append(this,
                "Disabled upload-only lock cleanup will retry: "
                    + conciseError(error));
        }
    }

    /**
     * A positive, durably restored POST receipt bearing the same one-time operation id is the only
     * automatic proof strong enough to retire a barrier after process death. The receipt remains in
     * place so its normal local convergence path can finish after the barrier is gone.
     */
    private boolean retireExactlyCompletedUploadReplayBarrier(
            UploadReplayBarrier.RestoreResult restored) {
        if (restored == null
                || restored.kind != UploadReplayBarrier.RestoreKind.RESTORED
                || restored.barrier == null) return false;
        UploadReplayBarrier.Identity identity = restored.barrier.identity;
        if (identity.flow == UploadReplayBarrier.Flow.MAIN) {
            AlternateSubmissionAttempt.RestoreResult receipt =
                restoreMainSubmissionAttempt();
            return receipt.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                && UploadReplayRecoveryRules.completedMain(identity, receipt.attempt)
                && clearUploadReplayBarrier(identity);
        }
        AlternateSubmissionAttempt.RestoreResult receipt =
            restoreAlternateSubmissionAttempt();
        if (receipt.kind != AlternateSubmissionAttempt.RestoreKind.RESTORED
                || receipt.attempt == null) return false;
        try {
            AlternateEntryDraftState draft = storedAlternateEntryDraftStrict();
            String source = alternateEntryDraftFingerprint(draft);
            return UploadReplayRecoveryRules.completedAlternate(
                    identity, receipt.attempt, draft, source)
                && clearUploadReplayBarrier(identity);
        } catch (RuntimeException mismatch) {
            Diagnostics.append(this, "Completed upload barrier recovery rejected: "
                + conciseError(mismatch));
            return false;
        }
    }

    private void showUploadReplayBarrierBlock(
            UploadReplayBarrier.RestoreResult ignored) {
        alert(t("upload_result_uncertain_title"),
            t("upload_result_uncertain_detail"));
    }

    /** Resolve only an exact active unit; stale, ambiguous and outcome-uncertain slots block. */
    private PreviousStepSubmissionAttempt.RestoreResult
            blockingPreviousStepSubmissionAttempt() {
        PreviousStepSubmissionAttempt.RestoreResult result =
            restorePreviousStepSubmissionAttempt();
        if (result.kind == PreviousStepSubmissionAttempt.RestoreKind.NONE) return null;
        if (result.kind != PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                || result.attempt == null
                || result.attempt.state == PreviousStepSubmissionAttempt.State.UNCERTAIN
                || result.attempt.state == PreviousStepSubmissionAttempt.State.POSTING) {
            return result;
        }
        try {
            ProfileWorkflow workflow = profileWorkflow();
            MainDraftSnapshotRules.Binding binding =
                mainDraftBindingForProfile(currentProfileId());
            List<PreviousStepExecutionOrderRules.Step> executionPlan =
                PreviousStepExecutionOrderRules.plan(workflow);
            JSONArray recipeSnapshot = catalogPreviousStepRecipeSnapshot();
            requirePreviousStepAttemptMatchesPlan(
                result.attempt, executionPlan, recipeSnapshot);
            List<UnitRecord> matches = new ArrayList<>();
            PreviousStepSubmissionAttempt.ChainIdentity matchedChain = null;
            for (UnitRecord unit : units) {
                PreviousStepSubmissionAttempt.ChainIdentity candidate =
                    previousStepChainIdentity(
                        unit, binding, executionPlan, recipeSnapshot,
                        result.attempt.key.chain.dynamicResolvedSemanticsSha256);
                if (result.attempt.chainMatches(candidate)) {
                    matches.add(unit);
                    matchedChain = candidate;
                }
            }
            if (matches.size() > 1) return result;
            if (matches.size() == 1 && matchedChain != null) {
                UnitRecord matched = matches.get(0);
                if (isSubmittedStatus(matched.status)) {
                    boolean durable = persistExactPreviousStepTerminal(
                        matched, binding, matchedChain, workflow);
                    return durable
                            && clearPreviousStepSubmissionAttemptForResolvedChain(matchedChain)
                        ? null : result;
                }
                // PREPARED and explicit rejection prove no accepted POST; COMPLETED is a durable
                // prefix receipt. Recipe execution may resume only for this exact active unit.
                return null;
            }
            UnitRecord durableTerminal = exactStoredPreviousStepTerminalUnit(
                result, binding, executionPlan, recipeSnapshot, workflow);
            if (durableTerminal == null) return result;
            PreviousStepSubmissionAttempt.ChainIdentity durableChain =
                previousStepChainIdentity(durableTerminal, binding,
                    executionPlan, recipeSnapshot,
                    result.attempt.key.chain.dynamicResolvedSemanticsSha256);
            return clearPreviousStepSubmissionAttemptForResolvedChain(durableChain)
                ? null : result;
        } catch (Exception mismatch) {
            Diagnostics.append(this,
                "Previous-step submission journal association failed: "
                    + conciseError(mismatch));
            return result;
        }
    }

    private void showPreviousStepSubmissionBlock(
            PreviousStepSubmissionAttempt.RestoreResult ignored) {
        alert(t("alternate_entry_result_uncertain_title"),
            t("previous_step_result_uncertain_detail"));
    }

    private boolean blockDraftMutationForPreviousStepJournal() {
        UploadReplayBarrier.RestoreResult uploadBarrier =
            blockingUploadReplayBarrier();
        if (uploadBarrier != null) {
            showUploadReplayBarrierBlock(uploadBarrier);
            return true;
        }
        if (!hasStoredPreviousStepSubmissionAttempt()) return false;
        showPreviousStepSubmissionBlock(restorePreviousStepSubmissionAttempt());
        return true;
    }

    private String mainSubmissionBindingFingerprint() {
        if (profile == null || activeCatalogVersion <= 0) return "";
        String backend = currentBackendAdapterFingerprint();
        if (backend.isEmpty()) return "";
        // Material refreshes are deliberately runtime-only. Bind the request journal to the
        // immutable Panel catalog profile so the same active catalog produces the same recovery
        // identity after a process restart.
        JSONObject catalogProfile = uniqueProfile(allProfiles, currentProfileId());
        if (catalogProfile == null) return "";
        return AlternateSubmissionAttempt.payloadSha256(
            activeCatalogVersion + "\n" + backend + "\n" + catalogProfile.toString());
    }

    private AlternateSubmissionAttempt.TargetIdentity mainSubmissionTargetIdentity() {
        JSONObject template = profile == null ? null : profile.optJSONObject("template");
        if (template == null) {
            throw new IllegalArgumentException("profile.template is required");
        }
        return AlternateSubmissionAttempt.TargetIdentity.of(
            currentProfileId(), template.opt("id"), template.opt("warehouseId"),
            template.optString("sku", ""));
    }

    private String mainSubmissionSourceSnapshotSha256(UnitRecord unit) {
        if (unit == null) throw new IllegalArgumentException("unit is required");
        try {
            JSONObject snapshot = new JSONObject()
                .put("profileId", currentProfileId())
                .put("sequence", unit.sequence)
                .put("sn", unit.sn == null ? "" : unit.sn)
                .put("snSource", unit.snSource == null ? "" : unit.snSource)
                .put("grade", unit.grade == null ? "" : unit.grade)
                .put("baseSn", unit.baseSn == null ? "" : unit.baseSn)
                .put("baseSnSource", unit.baseSnSource == null ? "" : unit.baseSnSource)
                .put("frontPhoto", unit.frontPhoto == null ? "" : unit.frontPhoto)
                .put("backPhoto", unit.backPhoto == null ? "" : unit.backPhoto)
                .put("precheckStatus",
                    unit.precheckStatus == null ? "" : unit.precheckStatus)
                .put("workflowArtifactRequired", unit.workflowArtifactRequired)
                .put("legacyWorkflowArtifactPath", unit.legacyWorkflowArtifactPath == null
                    ? "" : unit.legacyWorkflowArtifactPath)
                .put("supplementalPhotos", stringArray(unit.supplementalPhotos))
                .put("slotPhotos", sortedStringListMap(unit.slotPhotos))
                .put("workflowArtifacts", sortedStringMap(unit.workflowArtifacts))
                .put("pluginSns", sortedStringMap(unit.pluginSns));
            return AlternateSubmissionAttempt.payloadSha256(snapshot.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("cannot serialize submission source", impossible);
        }
    }

    /** Stable previous-step source identity; excludes status flags changed by verification itself. */
    private String previousStepSourceSnapshotSha256(UnitRecord unit) {
        if (unit == null) throw new IllegalArgumentException("unit is required");
        try {
            JSONObject snapshot = new JSONObject()
                .put("profileId", currentProfileId())
                .put("sequence", unit.sequence)
                .put("sn", unit.sn == null ? "" : unit.sn)
                .put("snSource", unit.snSource == null ? "" : unit.snSource)
                .put("grade", unit.grade == null ? "" : unit.grade)
                .put("baseSn", unit.baseSn == null ? "" : unit.baseSn)
                .put("baseSnSource", unit.baseSnSource == null ? "" : unit.baseSnSource)
                .put("frontPhoto", unit.frontPhoto == null ? "" : unit.frontPhoto)
                .put("backPhoto", unit.backPhoto == null ? "" : unit.backPhoto)
                .put("legacyWorkflowArtifactPath", unit.legacyWorkflowArtifactPath == null
                    ? "" : unit.legacyWorkflowArtifactPath)
                .put("supplementalPhotos", stringArray(unit.supplementalPhotos))
                .put("slotPhotos", sortedStringListMap(unit.slotPhotos))
                .put("workflowArtifacts", sortedStringMap(unit.workflowArtifacts))
                .put("pluginSns", sortedStringMap(unit.pluginSns));
            return AlternateSubmissionAttempt.payloadSha256(snapshot.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException(
                "cannot serialize previous-step source", impossible);
        }
    }

    private JSONArray stringArray(List<String> values) {
        JSONArray out = new JSONArray();
        if (values != null) for (String value : values) out.put(value == null ? "" : value);
        return out;
    }

    private JSONObject sortedStringMap(Map<String, String> values) throws JSONException {
        JSONObject out = new JSONObject();
        List<String> keys = new ArrayList<>();
        if (values != null) keys.addAll(values.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            String value = values.get(key);
            out.put(key, value == null ? "" : value);
        }
        return out;
    }

    private JSONObject sortedStringListMap(Map<String, List<String>> values)
            throws JSONException {
        JSONObject out = new JSONObject();
        List<String> keys = new ArrayList<>();
        if (values != null) keys.addAll(values.keySet());
        Collections.sort(keys);
        for (String key : keys) out.put(key, stringArray(values.get(key)));
        return out;
    }

    /** Returns a journal that blocks another main POST, or null after safe local recovery. */
    private AlternateSubmissionAttempt.RestoreResult blockingMainSubmissionAttempt() {
        UploadReplayBarrier.RestoreResult uploadBarrier =
            blockingUploadReplayBarrier();
        AlternateSubmissionAttempt.RestoreResult result = restoreMainSubmissionAttempt();
        if (!DURABLE_FINAL_SUBMISSION_REPLAY_BARRIER_ENABLED
                && !(result.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                    && result.attempt != null
                    && result.attempt.state == AlternateSubmissionAttempt.State.COMPLETED)) {
            discardReplayableFinalSubmissionAttempt(result, true);
            return null;
        }
        if (uploadBarrier != null
                && result.kind != AlternateSubmissionAttempt.RestoreKind.NONE) {
            // Never destroy the strongest completed receipt while an unretired upload barrier may
            // still need it for exact process-restart convergence.
            return result;
        }
        if (result.kind == AlternateSubmissionAttempt.RestoreKind.NONE) return null;
        if (result.kind != AlternateSubmissionAttempt.RestoreKind.RESTORED
                || result.attempt == null) return result;
        AlternateSubmissionAttempt attempt = result.attempt;
        if (attempt.state == AlternateSubmissionAttempt.State.PREPARED
                || attempt.state == AlternateSubmissionAttempt.State.CONFIRMED_NOT_WRITTEN) {
            return clearMainSubmissionAttempt() ? null : result;
        }
        if (attempt.state != AlternateSubmissionAttempt.State.COMPLETED) return result;
        if (!attempt.key.connectionNamespace.equals(currentConnectionNamespace())
                || !attempt.key.bindingFingerprint.equals(
                    mainSubmissionBindingFingerprint())
                || !attempt.key.target.profileId.equals(currentProfileId())) {
            return result;
        }
        ProfileWorkflow recoveryWorkflow = profileWorkflow();
        List<UnitRecord> matches = new ArrayList<>();
        try {
            for (UnitRecord unit : units) {
                if (attempt.key.serial.equals(unit.sn)
                        && attempt.key.sourceSnapshotSha256.equals(
                            mainSubmissionSourceSnapshotSha256(unit))) {
                    matches.add(unit);
                }
            }
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Main submission recovery comparison failed: "
                + conciseError(error));
            return result;
        }
        if (matches.size() > 1) return result;
        if (matches.size() == 1) {
            UnitRecord unit = matches.get(0);
            PreviousStepSubmissionAttempt.ChainIdentity previousStepChain;
            MainDraftSnapshotRules.Binding terminalBinding;
            try {
                terminalBinding = mainDraftBindingForProfile(currentProfileId());
                previousStepChain = previousStepSubmissionChainForResolvedUnit(
                    unit, terminalBinding, recoveryWorkflow);
            } catch (Exception error) {
                Diagnostics.append(this,
                    "Completed main submission previous-step recovery failed: "
                        + conciseError(error));
                return result;
            }
            unit.status = "success";
            recordDailyOutput(unit);
            if (!persistExactPreviousStepTerminal(
                    unit, terminalBinding, previousStepChain,
                    recoveryWorkflow)) return result;
            if (!clearPreviousStepSubmissionAttemptForResolvedChain(
                    previousStepChain)) return result;
        } else {
            if (hasStoredPreviousStepSubmissionAttempt()) {
                try {
                    PreviousStepSubmissionAttempt.RestoreResult previous =
                        restorePreviousStepSubmissionAttempt();
                    MainDraftSnapshotRules.Binding binding =
                        mainDraftBindingForProfile(currentProfileId());
                    List<PreviousStepExecutionOrderRules.Step> executionPlan =
                        PreviousStepExecutionOrderRules.plan(recoveryWorkflow);
                    JSONArray recipeSnapshot = catalogPreviousStepRecipeSnapshot();
                    UnitRecord terminal = exactStoredPreviousStepTerminalUnit(
                        previous, binding, executionPlan, recipeSnapshot,
                        recoveryWorkflow);
                    if (terminal == null || !"success".equals(terminal.status)
                            || !attempt.key.serial.equals(terminal.sn)
                            || !attempt.key.sourceSnapshotSha256.equals(
                                mainSubmissionSourceSnapshotSha256(terminal))) {
                        return result;
                    }
                    PreviousStepSubmissionAttempt.ChainIdentity terminalChain =
                        previousStepChainIdentity(terminal, binding,
                            executionPlan, recipeSnapshot,
                            previous.attempt.key.chain.dynamicResolvedSemanticsSha256);
                    if (!clearPreviousStepSubmissionAttemptForResolvedChain(
                            terminalChain)) return result;
                } catch (Exception error) {
                    Diagnostics.append(this,
                        "Stored main/previous-step terminal recovery failed: "
                            + conciseError(error));
                    return result;
                }
            }
            try {
                JSONObject stored = draftMap(loadDraftStore()).optJSONObject(
                    attempt.key.target.profileId);
                if (stored != null && draftHasUnsubmittedUnits(stored)) return result;
            } catch (Exception error) {
                Diagnostics.append(this, "Completed main submission recovery failed: "
                    + conciseError(error));
                return result;
            }
        }
        if (!clearMainSubmissionAttempt()) return result;
        if (matches.size() == 1) removeSubmittedUnitFromQueue(matches.get(0));
        return null;
    }

    private void showMainSubmissionBlock(
            AlternateSubmissionAttempt.RestoreResult result) {
        boolean storageLocked = result == null
            || result.kind == AlternateSubmissionAttempt.RestoreKind.LOCKED
            || (result.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                && result.attempt != null
                && (result.attempt.state == AlternateSubmissionAttempt.State.PREPARED
                    || result.attempt.state
                        == AlternateSubmissionAttempt.State.CONFIRMED_NOT_WRITTEN));
        alert(t("alternate_entry_result_uncertain_title"), storageLocked
            ? t("alternate_entry_storage_locked_detail")
            : t("alternate_entry_result_uncertain_detail"));
    }

    private enum CompletedLocalCopyKind {
        NONE,
        MATCHES_COMPLETED,
        DIFFERENT,
        LOCKED
    }

    private static final class AlternateDailyStatsIdentity {
        final String sourceProfileId;
        final String entryId;

        AlternateDailyStatsIdentity(String sourceProfileId, String entryId) {
            this.sourceProfileId = sourceProfileId;
            this.entryId = entryId;
        }
    }

    private CompletedLocalCopyKind completedLocalCopyKind(
            AlternateSubmissionAttempt completed) {
        if (completed == null || completed.state != AlternateSubmissionAttempt.State.COMPLETED) {
            return CompletedLocalCopyKind.LOCKED;
        }
        boolean matching = false;
        boolean different = false;
        String draftKey = alternateEntryDraftPreferenceKey();
        try {
            Map<String, ?> stored = prefs.getAll();
            if (stored.containsKey(draftKey)) {
                Object raw = stored.get(draftKey);
                if (!(raw instanceof String)) return CompletedLocalCopyKind.LOCKED;
                AlternateEntryDraftState draft = AlternateEntryDraftState.parse((String) raw);
                boolean exact = alternateEntryDraftFingerprint(draft).equals(
                    completed.key.sourceSnapshotSha256);
                matching |= exact;
                different |= !exact;
            }
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Completed submission draft check failed: "
                + conciseError(error));
            return CompletedLocalCopyKind.LOCKED;
        }
        if (hasAlternateEntryPendingData()) {
            boolean exact;
            try {
                AlternateEntryDraftState active = inMemoryAlternateEntryDraftState();
                exact = alternateEntryDraftFingerprint(active).equals(
                    completed.key.sourceSnapshotSha256);
            } catch (RuntimeException error) {
                Diagnostics.append(this, "Completed submission active-copy check failed: "
                    + conciseError(error));
                return CompletedLocalCopyKind.LOCKED;
            }
            matching |= exact;
            different |= !exact;
        }
        if (matching && different) return CompletedLocalCopyKind.LOCKED;
        if (matching) return CompletedLocalCopyKind.MATCHES_COMPLETED;
        if (different) return CompletedLocalCopyKind.DIFFERENT;
        return CompletedLocalCopyKind.NONE;
    }

    /**
     * Resolves a completed journal only against the exact active source/entry/target binding. A
     * durable draft narrows the match; an already-cleaned tombstone can still be backfilled by a
     * unique scan of the active catalog.
     */
    private AlternateDailyStatsIdentity completedAlternateDailyStatsIdentity(
            AlternateSubmissionAttempt completed, AlternateEntryDraftState exactDraft) {
        if (completed == null
                || completed.state != AlternateSubmissionAttempt.State.COMPLETED
                || !completed.key.connectionNamespace.equals(currentConnectionNamespace())
                || allProfiles == null) {
            return null;
        }
        if (exactDraft != null
                && (!exactDraft.connectionNamespace.equals(
                        completed.key.connectionNamespace)
                    || !exactDraft.bindingFingerprint.equals(
                        completed.key.bindingFingerprint)
                    || !exactDraft.serial.equals(completed.key.serial))) {
            return null;
        }

        AlternateDailyStatsIdentity match = null;
        int matches = 0;
        for (int profileIndex = 0; profileIndex < allProfiles.length(); profileIndex++) {
            JSONObject source = allProfiles.optJSONObject(profileIndex);
            if (source == null || !Boolean.TRUE.equals(source.opt("pickerVisible"))) continue;
            String sourceProfileId = source.optString("id", "");
            if (sourceProfileId.isEmpty()
                    || (exactDraft != null
                        && !sourceProfileId.equals(exactDraft.sourceProfileId))) {
                continue;
            }
            JSONArray entries = configuredAlternateEntries(source);
            for (int entryIndex = 0; entryIndex < entries.length(); entryIndex++) {
                JSONObject entry = entries.optJSONObject(entryIndex);
                String entryId = entry == null ? "" : entry.optString("id", "");
                if (entryId.isEmpty()
                        || (exactDraft != null && !entryId.equals(exactDraft.entryId))
                        || !completed.key.target.profileId.equals(
                            entry.optString("targetProfileId", ""))) {
                    continue;
                }
                String binding = alternateEntryBindingFingerprint(source, entry, allProfiles);
                if (binding.isEmpty()
                        || !binding.equals(completed.key.bindingFingerprint)) continue;
                match = new AlternateDailyStatsIdentity(sourceProfileId, entryId);
                matches++;
            }
        }
        return matches == 1 ? match : null;
    }

    /**
     * Returns false only when an exact identity was resolved but could not be durably recorded.
     * An old/unresolvable catalog binding is diagnosed but never blocks acknowledged-work cleanup.
     */
    private boolean recordCompletedAlternateDailyOutput(
            AlternateSubmissionAttempt completed, AlternateEntryDraftState exactDraft) {
        AlternateDailyStatsIdentity identity = completedAlternateDailyStatsIdentity(
            completed, exactDraft);
        if (identity == null) {
            Diagnostics.append(this,
                "Completed independent-entry stats identity was not uniquely resolvable");
            return true;
        }
        return recordDailyAlternateOutput(identity.sourceProfileId, identity.entryId,
            completed.key.serial);
    }

    /** Best-effort upgrade backfill while the exact active catalog is already loaded. */
    private void backfillCompletedAlternateDailyOutputAtStartup() {
        if (blockingUploadReplayBarrier() != null) return;
        AlternateSubmissionAttempt.RestoreResult restored =
            restoreAlternateSubmissionAttempt();
        if (restored.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                && restored.attempt != null
                && restored.attempt.state == AlternateSubmissionAttempt.State.COMPLETED) {
            // The COMPLETED tombstone remains available for another best-effort retry. Statistics
            // are presentation-only and must never turn an acknowledged backend write into a
            // production submission lock.
            recordCompletedAlternateDailyOutput(restored.attempt, null);
        }
    }

    /** Returns the journal that blocks a new POST, or null when the slot is provably reusable. */
    private AlternateSubmissionAttempt.RestoreResult blockingAlternateSubmissionAttempt() {
        UploadReplayBarrier.RestoreResult uploadBarrier =
            blockingUploadReplayBarrier();
        AlternateSubmissionAttempt.RestoreResult result =
            restoreAlternateSubmissionAttempt();
        if (uploadBarrier != null
                && result.kind != AlternateSubmissionAttempt.RestoreKind.NONE) {
            return result;
        }
        if (result.kind == AlternateSubmissionAttempt.RestoreKind.NONE) return null;
        if (result.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                && result.attempt != null && result.attempt.canClearLocallyWithoutRemoteConfirmation()) {
            if (result.attempt.state == AlternateSubmissionAttempt.State.PREPARED
                    || result.attempt.state
                        == AlternateSubmissionAttempt.State.CONFIRMED_NOT_WRITTEN) {
                if (clearAlternateSubmissionAttempt()) return null;
            } else {
                boolean statsRecorded =
                    recordCompletedAlternateDailyOutput(result.attempt, null);
                CompletedLocalCopyKind local = completedLocalCopyKind(result.attempt);
                if (local == CompletedLocalCopyKind.DIFFERENT) {
                    // Keep the completed receipt until prepare() atomically replaces it. A stale
                    // saved-instance state matching the old record therefore remains detectable.
                    return null;
                }
                if (local == CompletedLocalCopyKind.NONE) {
                    if (!statsRecorded) {
                        // Preserve the tombstone for a later best-effort backfill, but do not make
                        // an auxiliary counter failure block a new production submission. A new
                        // prepare() may safely replace this already-COMPLETED attempt.
                        return null;
                    }
                    if (clearAlternateSubmissionAttempt()) return null;
                }
            }
        }
        return result;
    }

    private void showAlternateSubmissionBlock(
            AlternateSubmissionAttempt.RestoreResult result) {
        if (result != null
                && result.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                && result.attempt != null
                && result.attempt.state == AlternateSubmissionAttempt.State.COMPLETED) {
            AlternateSubmissionAttempt completed = result.attempt;
            new AlertDialog.Builder(this)
                .setTitle(t("alternate_entry_completed_cleanup_title"))
                .setMessage(t("alternate_entry_completed_cleanup_detail"))
                .setNegativeButton(t("cancel"), null)
                .setPositiveButton(t("alternate_entry_cleanup_local"), (dialog, which) -> {
                    if (!finalizeCompletedAlternateSubmission(completed)) {
                        alert(t("alternate_entry_completed_cleanup_title"),
                            t("alternate_entry_cleanup_failed"));
                        return;
                    }
                    resetAlternateEntryTogglesAfterSubmit();
                    if (alternateEntrySerialEdit != null) {
                        alternateEntrySerialEdit.setText("");
                        alternateEntrySerialEdit.requestFocus();
                    }
                    refreshAlternateEntryUi();
                    toast(t("saved"));
                })
                .show();
            return;
        }
        boolean storageLocked = result == null
            || result.kind == AlternateSubmissionAttempt.RestoreKind.LOCKED
            || (result.kind == AlternateSubmissionAttempt.RestoreKind.RESTORED
                && result.attempt != null
                && (result.attempt.state == AlternateSubmissionAttempt.State.PREPARED
                    || result.attempt.state
                        == AlternateSubmissionAttempt.State.CONFIRMED_NOT_WRITTEN));
        alert(t("alternate_entry_result_uncertain_title"), storageLocked
            ? t("alternate_entry_storage_locked_detail")
            : t("alternate_entry_result_uncertain_detail"));
    }

    /**
     * Finishes local cleanup after an acknowledged POST. The draft is removed synchronously and
     * photos are deleted only after that transaction commits. The COMPLETED receipt is retained as
     * a tombstone so a stale Android saved-instance bundle cannot resurrect and resubmit this copy.
     */
    private boolean finalizeCompletedAlternateSubmission(
            AlternateSubmissionAttempt expected) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (blockingUploadReplayBarrier() != null) return false;
            return finalizeCompletedAlternateSubmissionLocked(expected);
        }
    }

    private boolean finalizeCompletedAlternateSubmissionLocked(
            AlternateSubmissionAttempt expected) {
        if (expected == null || expected.state != AlternateSubmissionAttempt.State.COMPLETED) {
            return false;
        }
        AlternateSubmissionAttempt.RestoreResult restored =
            restoreAlternateSubmissionAttempt();
        if (restored.kind != AlternateSubmissionAttempt.RestoreKind.RESTORED
                || restored.attempt == null
                || restored.attempt.state != AlternateSubmissionAttempt.State.COMPLETED
                || !expected.key.equals(restored.attempt.key)) {
            return false;
        }

        String draftKey = alternateEntryDraftPreferenceKey();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        AlternateEntryDraftState completedDraft = null;
        try {
            Map<String, ?> stored = prefs.getAll();
            if (stored.containsKey(draftKey)) {
                Object raw = stored.get(draftKey);
                if (!(raw instanceof String)) return false;
                AlternateEntryDraftState draft = AlternateEntryDraftState.parse((String) raw);
                if (!alternateEntryDraftFingerprint(draft).equals(
                        expected.key.sourceSnapshotSha256)) {
                    return false;
                }
                completedDraft = draft;
                paths.addAll(draft.photos);
            }
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Completed alternate submission cleanup blocked: "
                + conciseError(error));
            return false;
        }

        if (hasAlternateEntryPendingData()) {
            try {
                AlternateEntryDraftState active = inMemoryAlternateEntryDraftState();
                if (!alternateEntryDraftFingerprint(active).equals(
                        expected.key.sourceSnapshotSha256)) {
                    return false;
                }
                if (completedDraft == null) completedDraft = active;
            } catch (RuntimeException error) {
                Diagnostics.append(this, "Completed active-copy cleanup blocked: "
                    + conciseError(error));
                return false;
            }
            paths.addAll(alternateEntryPhotos);
            String pending = pendingAlternateEntryPhotoPath;
            if (pending == null || pending.isEmpty()) {
                pending = optionalStringPreference(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
            }
            if (pending != null && !pending.isEmpty()) paths.add(pending);
        }

        // Try while the exact draft identity is still available. Failure is diagnosed and the
        // retained COMPLETED tombstone can retry later, but statistics must not prevent cleanup of
        // a backend-acknowledged production record.
        recordCompletedAlternateDailyOutput(expected, completedDraft);

        if (!prefs.edit()
                .remove(draftKey)
                .remove(alternateEntryContinuationProofPreferenceKey())
                .commit()) return false;
        for (String path : paths) deleteFileQuietly(path);
        clearPendingAlternateEntryPhoto();
        alternateEntryPhotos.clear();
        alternateEntrySerial = "";
        alternateEntrySerialSource = SnScanRules.SOURCE_ENTERED;
        alternateEntryContinuationToken = "";
        return true;
    }

    private void resetAlternateEntryTogglesAfterSubmit() {
        if (alternateEntrySourceProfile == null || alternateEntryConfig == null) return;
        try {
            AlternateEntryRules.Resolution resolution = preflightAlternateEntry(
                alternateEntrySourceProfile, alternateEntryCatalogSnapshot,
                alternateEntryConfig, alternateEntryToggleStates);
            for (AlternateEntryRules.TogglePolicy policy : resolution.togglePolicies) {
                if (!policy.retainUntilExit) {
                    alternateEntryToggleStates.put(policy.key, policy.defaultValue);
                }
            }
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Alternate-entry toggle reset failed: "
                + conciseError(error));
        }
    }

    private boolean saveAlternateEntryDraft(boolean durable) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            String key = alternateEntryDraftPreferenceKey();
            String proofKey = alternateEntryContinuationProofPreferenceKey();
            if (!hasAlternateEntryPendingData()) {
                // Do not erase an unreadable or differently bound persisted draft merely because a
                // lifecycle callback ran while no alternate-entry session was loaded in memory.
                if (alternateEntrySourceProfile == null || alternateEntryConfig == null
                        || alternateEntryId.isEmpty()) return true;
                alternateEntryContinuationToken = "";
                SharedPreferences.Editor empty = prefs.edit()
                    .remove(key)
                    .remove(proofKey);
                if (durable) return empty.commit();
                empty.apply();
                return true;
            }
            try {
                if (savedAccount().isEmpty()) return false;
                AlternateEntryDraftState draft = inMemoryAlternateEntryDraftState();
                SharedPreferences.Editor editor = prefs.edit().putString(key,
                    draft.toJson().toString());
                // Keep the page's presentation choice in the same transaction as the record. A
                // crash/logout cannot retain the SN/photos while retaining another form's choice.
                stageAlternateEntrySelection(editor, draft.entryId, draft.sourceProfileId);
                if (UnsafeCandidateContinuationRules.validAlternateEntryToken(
                        alternateEntryContinuationToken)) {
                    AlternateEntryContinuationProof proof =
                        AlternateEntryContinuationProof.create(
                            alternateEntryContinuationToken,
                            alternateEntryConnectionNamespace, activeCatalogVersion,
                            currentPanelPairSha256(), alternateEntryBindingFingerprint,
                            draft.continuationStateSha256());
                    editor.putString(proofKey, proof.toJson().toString());
                } else {
                    editor.remove(proofKey);
                }
                if (durable && !editor.commit()) return false;
                if (!durable) editor.apply();
                return true;
            } catch (Exception error) {
                Diagnostics.append(this, "Alternate-entry draft save failed: "
                    + conciseError(error));
                return false;
            }
        }
    }

    private void persistAlternateEntryDraftBestEffort() {
        saveAlternateEntryDraft(false);
    }

    /**
     * Commits one reserved result and consumes its durable reservation in the same preference
     * transaction. The caller has already changed only in-memory fields and still owns
     * {@code HANDOFF_LOCK}; a failed commit is therefore fully rollbackable by the caller.
     */
    private boolean commitMaterializedAlternateEntryReservationLocked(
            AlternateEntryAsyncReservation reservation) {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK) || reservation == null
                || !reservation.resultContinuationToken.equals(
                    alternateEntryContinuationToken)) return false;
        UnsafeCandidateContinuationRules.Lease consumedLease = null;
        boolean unsafeCandidate = unsafeCandidatesBlockActiveUse();
        if (unsafeCandidate) {
            consumedLease = UnsafeCandidateContinuationRules.consumeAlternateReservation(
                unsafeCandidateContinuationLease, reservation.reservationToken,
                reservation.resultContinuationToken, currentConnectionNamespace(),
                activeCatalogVersion, currentPanelPairSha256());
            if (consumedLease == null) return false;
        }
        try {
            AlternateEntryDraftState draft = inMemoryAlternateEntryDraftState();
            AlternateEntryContinuationProof proof = AlternateEntryContinuationProof.create(
                alternateEntryContinuationToken, alternateEntryConnectionNamespace,
                activeCatalogVersion, currentPanelPairSha256(),
                alternateEntryBindingFingerprint, draft.continuationStateSha256());
            SharedPreferences.Editor editor = prefs.edit()
                .putString(alternateEntryDraftPreferenceKey(), draft.toJson().toString())
                .putString(alternateEntryContinuationProofPreferenceKey(),
                    proof.toJson().toString())
                .remove(alternateEntryReservationPreferenceKey(reservation.kind))
                .remove(alternateEntryReservationGuardPreferenceKey(reservation.kind));
            // The scanner/photo result, its exact draft binding and the source shown by the
            // independent-entry selector become durable together. A process death after this
            // commit can therefore never pair retained SN/photos with an older form choice.
            stageAlternateEntrySelection(editor, draft.entryId, draft.sourceProfileId);
            if (AlternateEntryAsyncReservation.KIND_PHOTO.equals(reservation.kind)) {
                editor.remove(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
            }
            if (!editor.commit()) {
                alternateEntryReservationStorageAmbiguous = true;
                return false;
            }
            alternateEntryReservationStorageAmbiguous = false;
            if (unsafeCandidate) unsafeCandidateContinuationLease = consumedLease;
            if (AlternateEntryAsyncReservation.KIND_PHOTO.equals(reservation.kind)) {
                pendingAlternateEntryPhotoReservation = null;
                pendingAlternateEntryPhotoPath = "";
                pendingAlternateEntryPhotoGuard = "";
            } else {
                pendingAlternateEntryScanReservation = null;
                pendingAlternateEntryScanGuard = "";
            }
            return true;
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Alternate-entry reserved result save failed: "
                + conciseError(error));
            return false;
        }
    }

    private boolean materializeAlternateEntrySerial(
            AlternateEntryAsyncReservation reservation, String serial, String source) {
        if (reservation == null || !AlternateEntryAsyncReservation.KIND_SCAN.equals(
                reservation.kind) || !isIdentifierValueSource(source)) return false;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!alternateEntryReservationMayMaterializeLocked(reservation)) return false;
            String oldSerial = alternateEntrySerial;
            String oldSource = alternateEntrySerialSource;
            String oldToken = alternateEntryContinuationToken;
            alternateEntrySerial = serial == null ? "" : serial;
            alternateEntrySerialSource = source;
            alternateEntryContinuationToken = reservation.resultContinuationToken;
            if (!commitMaterializedAlternateEntryReservationLocked(reservation)) {
                alternateEntrySerial = oldSerial;
                alternateEntrySerialSource = oldSource;
                alternateEntryContinuationToken = oldToken;
                return false;
            }
        }
        showScannedSnPreview(alternateEntrySerial, primaryInputLabel());
        refreshAlternateEntryUi();
        return true;
    }

    private boolean materializeAlternateEntryPhoto(
            AlternateEntryAsyncReservation reservation, String path) {
        if (reservation == null || !AlternateEntryAsyncReservation.KIND_PHOTO.equals(
                reservation.kind) || !reservation.outputPath.equals(path)) return false;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!alternateEntryReservationMayMaterializeLocked(reservation)) return false;
            String oldToken = alternateEntryContinuationToken;
            boolean added = !alternateEntryPhotos.contains(path);
            if (added) alternateEntryPhotos.add(path);
            alternateEntryContinuationToken = reservation.resultContinuationToken;
            if (!commitMaterializedAlternateEntryReservationLocked(reservation)) {
                if (added) alternateEntryPhotos.remove(path);
                alternateEntryContinuationToken = oldToken;
                return false;
            }
        }
        return true;
    }

    /** Clears only the same exact reservation; it can never erase a newer activity launch. */
    private boolean clearAlternateEntryReservation(
            AlternateEntryAsyncReservation expected, boolean deletePhoto) {
        if (expected == null) return false;
        String pathToDelete = "";
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            AlternateEntryAsyncReservation exact =
                exactStoredAlternateEntryReservationLocked(expected.kind);
            if (exact == null || !exact.reservationToken.equals(expected.reservationToken)) {
                return false;
            }
            SharedPreferences.Editor editor = prefs.edit()
                .remove(alternateEntryReservationPreferenceKey(expected.kind))
                .remove(alternateEntryReservationGuardPreferenceKey(expected.kind));
            if (AlternateEntryAsyncReservation.KIND_PHOTO.equals(expected.kind)) {
                editor.remove(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
                if (deletePhoto) pathToDelete = exact.outputPath;
            }
            if (!editor.commit()) {
                alternateEntryReservationStorageAmbiguous = true;
                return false;
            }
            alternateEntryReservationStorageAmbiguous = false;
            if (AlternateEntryAsyncReservation.KIND_PHOTO.equals(expected.kind)) {
                pendingAlternateEntryPhotoReservation = null;
                pendingAlternateEntryPhotoPath = "";
                pendingAlternateEntryPhotoGuard = "";
            } else {
                pendingAlternateEntryScanReservation = null;
                pendingAlternateEntryScanGuard = "";
            }
        }
        if (!pathToDelete.isEmpty()) deleteFileQuietly(pathToDelete);
        return true;
    }

    private boolean clearStoredAlternateEntryDraft(boolean deletePhotos) {
        String key = alternateEntryDraftPreferenceKey();
        List<String> paths = new ArrayList<>();
        Object value = null;
        try {
            value = prefs.getAll().get(key);
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Alternate-entry draft read failed during discard: "
                + conciseError(error));
            return false;
        }
        if (deletePhotos && value instanceof String && !((String) value).isEmpty()) {
            try {
                AlternateEntryDraftState draft = AlternateEntryDraftState.parse((String) value);
                paths.addAll(draft.photos);
            } catch (Exception error) {
                Diagnostics.append(this, "Alternate-entry draft discard could not inspect paths: "
                    + conciseError(error));
            }
        }
        // Remove the durable reference first. If the commit fails, preserve every photo.
        if (!prefs.edit()
                .remove(key)
                .remove(alternateEntryContinuationProofPreferenceKey())
                .commit()) return false;
        for (String path : paths) deleteFileQuietly(path);
        return true;
    }

    // 0 = no stored draft, 1 = restored, -1 = a stored draft exists but cannot be safely rebound.
    private int restoreStoredAlternateEntryDraft(String requestedEntryId) {
        String key = alternateEntryDraftPreferenceKey();
        Object value;
        try {
            Map<String, ?> stored = prefs.getAll();
            if (!stored.containsKey(key)) return 0;
            value = stored.get(key);
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Alternate-entry draft read failed: "
                + conciseError(error));
            clearAlternateEntrySession(false);
            return -1;
        }
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            Diagnostics.append(this, "Alternate-entry draft has an unreadable storage type");
            clearAlternateEntrySession(false);
            return -1;
        }
        String raw = (String) value;
        try {
            AlternateEntryDraftState draft = AlternateEntryDraftState.parse(raw);
            String accountFingerprint = AlternateEntryDraftState.accountFingerprint(
                savedAccount());
            if (!draft.entryId.equals(requestedEntryId)
                    || !draft.accountFingerprint.equals(accountFingerprint)
                    || !draft.connectionNamespace.equals(currentConnectionNamespace())
                    || !draft.backendFingerprint.equals(currentBackendAdapterFingerprint())) {
                return -1;
            }
            JSONObject source = uniqueProfile(allProfiles, draft.sourceProfileId);
            JSONObject entry = alternateEntryById(source, draft.entryId);
            if (source == null || entry == null) return -1;
            String currentBinding = alternateEntryBindingFingerprint(source, entry, allProfiles);
            if (!draft.matches(accountFingerprint, currentConnectionNamespace(), currentBinding,
                    currentBackendAdapterFingerprint(), requestedEntryId)) {
                return -1;
            }
            JSONArray sources = alternateEntrySources(requestedEntryId);
            if (alternateEntrySourceIndex(sources, draft.sourceProfileId) < 0) return -1;

            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                boolean unsafeCandidate =
                    PanelPairCacheCoordinator.pendingCandidatesBlockActiveUse(
                        this, currentConnectionNamespace());
                alternateEntryContinuationToken = "";
                alternateEntryId = draft.entryId;
                alternateEntryReturnProfileId = draft.returnProfileId;
                alternateEntrySourceProfiles = new JSONArray(sources.toString());
                bindAlternateEntry(source, entry, allProfiles);
                alternateEntrySerial = draft.serial;
                alternateEntrySerialSource = draft.serialSource;
                String pendingPhoto = optionalStringPreference(
                    PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
                pendingAlternateEntryPhotoPath = pendingPhoto;
                pendingAlternateEntryPhotoGuard = optionalStringPreference(
                    PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY);
                alternateEntryPhotos.clear();
                for (String path : draft.photos) {
                    if (hasFile(path) && !path.equals(pendingPhoto)) {
                        alternateEntryPhotos.add(path);
                    }
                }
                alternateEntryToggleStates.clear();
                alternateEntryToggleStates.putAll(draft.toggles);
                alternateEntryStateProfileId = draft.sourceProfileId;
                alternateEntryPageOpen = false;
                preflightAlternateEntry(alternateEntrySourceProfile,
                    alternateEntryCatalogSnapshot, alternateEntryConfig,
                    alternateEntryToggleStates);
                if (!hasAlternateEntryPendingData()) {
                    if (!clearStoredAlternateEntryDraft(false)) return -1;
                    clearAlternateEntrySession(false);
                    return 0;
                }
                AlternateEntryDraftState live = inMemoryAlternateEntryDraftState();
                String storedContinuationToken =
                    exactStoredAlternateEntryContinuationTokenLocked(live);
                if (unsafeCandidate) {
                    // A non-empty restored draft is not itself evidence that it predates the
                    // candidate. Only the exact durable pair+state proof may restore the token.
                    alternateEntryContinuationToken = storedContinuationToken;
                    if (alternateEntryContinuationToken.isEmpty()) return -1;
                } else {
                    alternateEntryContinuationToken = storedContinuationToken;
                    if (alternateEntryContinuationToken.isEmpty()) {
                        markAlternateEntryWorkEstablishedLocked();
                    }
                    if (!saveAlternateEntryDraft(true)) {
                        alternateEntryContinuationToken = "";
                        return -1;
                    }
                }
            }
            // A draft binding is stronger than any older presentation preference.
            rememberAlternateEntrySelection(draft.entryId, draft.sourceProfileId);
            return 1;
        } catch (Exception error) {
            Diagnostics.append(this, "Alternate-entry draft restore blocked: "
                + conciseError(error));
            clearAlternateEntrySession(false);
            return -1;
        }
    }

    private void suspendAlternateEntrySession() {
        alternateEntryPageOpen = false;
        alternateEntrySessionNonce = "";
        clearPendingAlternateEntryScanGuard();
    }

    private String alternateEntryOperationGuard() {
        if (!alternateEntryPageOpen || alternateEntrySessionNonce.isEmpty()
                || alternateEntryBindingFingerprint.isEmpty()
                || alternateEntrySourceProfile == null || alternateEntryConfig == null) {
            return "";
        }
        return alternateEntrySessionNonce + "\n"
            + alternateEntryConnectionNamespace + "\n"
            + alternateEntryBindingFingerprint + "\n"
            + alternateEntryBackendFingerprint + "\n"
            + alternateEntrySourceProfile.optString("id", "") + "\n"
            + alternateEntryConfig.optString("id", "");
    }

    private boolean alternateEntryOperationMatches(String guard) {
        return guard != null && !guard.isEmpty()
            && guard.equals(alternateEntryOperationGuard());
    }

    private JSONObject uniqueProfile(JSONArray catalog, String profileId) {
        if (catalog == null || profileId == null || profileId.isEmpty()) return null;
        JSONObject found = null;
        int matches = 0;
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject candidate = catalog.optJSONObject(i);
            if (candidate == null || !profileId.equals(candidate.optString("id", ""))) {
                continue;
            }
            found = candidate;
            matches++;
        }
        return matches == 1 ? found : null;
    }

    private void selectVisibleProfile(String profileId) {
        JSONObject selected = uniqueProfile(profiles, profileId);
        if (selected != null) profile = selected;
    }

    private String alternateEntryBindingFingerprint(JSONObject source, JSONObject entry,
                                                      JSONArray catalog) {
        try {
            if (source == null || entry == null || catalog == null) return "";
            String targetId = entry.optString("targetProfileId", "");
            JSONObject target = uniqueProfile(catalog, targetId);
            if (target == null) return "";
            String value = source.toString() + "\n" + entry.toString() + "\n"
                + target.toString();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private JSONObject copyJsonObject(JSONObject value) {
        if (value == null) return null;
        try {
            return new JSONObject(value.toString());
        } catch (JSONException ignored) {
            return null;
        }
    }

    private String backendAdapterFingerprint(JSONObject config, JSONObject settings) {
        JSONObject adapter = config == null ? null : config.optJSONObject("backendAdapter");
        if (adapter == null && settings != null) {
            adapter = settings.optJSONObject("backendAdapter");
        }
        if (adapter == null) return "";
        try {
            String value = adapter.toString() + "\n"
                + (config == null || config.optJSONObject("endpoints") == null ? ""
                    : config.optJSONObject("endpoints").toString()) + "\n"
                + (config == null ? "" : config.optString("webOrigin", "").trim())
                + "\n"
                + (config == null ? "" : config.optString("webReferer", "").trim())
                + "\n"
                + (settings == null ? "" : settings.toString());
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String currentBackendAdapterFingerprint() {
        return backendAdapterFingerprint(appConfig, catalogSettings);
    }

    private void bindAlternateEntry(JSONObject source, JSONObject entry, JSONArray catalog)
            throws JSONException {
        JSONObject sourceCopy = new JSONObject(source.toString());
        JSONObject entryCopy = new JSONObject(entry.toString());
        JSONArray catalogCopy = new JSONArray(catalog.toString());
        JSONObject configCopy = copyJsonObject(appConfig);
        JSONObject settingsCopy = copyJsonObject(catalogSettings);
        BackendAdapter adapterCopy = BackendAdapter.from(configCopy, settingsCopy);
        JSONObject targetCopy = AlternateEntryRules.targetProfile(catalogCopy, entryCopy);
        List<String> capabilityErrors =
            RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
                sourceCopy, targetCopy, adapterCopy);
        if (!capabilityErrors.isEmpty()) {
            throw new JSONException("alternate entry capability is incomplete: "
                + join(capabilityErrors, ", "));
        }
        String binding = alternateEntryBindingFingerprint(sourceCopy, entryCopy, catalogCopy);
        if (binding.isEmpty()) throw new JSONException("alternate entry binding is invalid");
        alternateEntrySourceProfile = sourceCopy;
        alternateEntryConfig = entryCopy;
        alternateEntryCatalogSnapshot = catalogCopy;
        alternateEntryConnectionNamespace = currentConnectionNamespace();
        alternateEntryBindingFingerprint = binding;
        alternateEntryAppConfigSnapshot = configCopy;
        alternateEntryCatalogSettingsSnapshot = settingsCopy;
        alternateEntryBackendFingerprint = backendAdapterFingerprint(
            alternateEntryAppConfigSnapshot, alternateEntryCatalogSettingsSnapshot);
        alternateEntrySessionNonce = java.util.UUID.randomUUID().toString();
        profile = sourceCopy;
    }

    /** New/rebound entry identity is itself a state expansion and shares the candidate write lock. */
    private void bindAlternateEntryForNewWork(JSONObject source, JSONObject entry,
                                              JSONArray catalog) throws JSONException {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!alternateEntryExpansionAllowedLocked()) {
                throw new JSONException("Panel candidate blocks alternate-entry binding");
            }
            alternateEntryContinuationToken = "";
            bindAlternateEntry(source, entry, catalog);
        }
    }

    private boolean alternateEntryBindingStillCurrent(JSONObject sourceSnapshot,
                                                       JSONObject configSnapshot) {
        try {
            if (!currentConnectionNamespace().equals(alternateEntryConnectionNamespace)) {
                return false;
            }
            // Validate against the immutable active pair, not independently refreshed disk files.
            // This lets an already-open v7 workflow finish while a publish is temporarily v8/v7.
            if (!activeWorkflowCanContinue()
                    || !backendAdapterFingerprint(appConfig, catalogSettings).equals(
                    alternateEntryBackendFingerprint)) return false;
            JSONArray latest = allProfiles;
            JSONObject latestSource = uniqueProfile(latest,
                sourceSnapshot.optString("id", ""));
            if (latestSource == null) return false;
            JSONObject latestEntry = alternateEntryById(latestSource,
                configSnapshot.optString("id", ""));
            if (latestEntry == null) return false;
            String latestBinding = alternateEntryBindingFingerprint(
                latestSource, latestEntry, latest);
            return !latestBinding.isEmpty()
                && latestBinding.equals(alternateEntryBindingFingerprint);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void saveAlternateEntryState(Bundle state) {
        if (state == null || !alternateEntryPageOpen || alternateEntryId.isEmpty()
                || alternateEntrySourceProfile == null || alternateEntryConfig == null
                || alternateEntryBindingFingerprint.isEmpty()) {
            return;
        }
        state.putBoolean(STATE_ALTERNATE_ENTRY_OPEN, true);
        state.putString(STATE_ALTERNATE_ENTRY_ID, alternateEntryId);
        state.putString(STATE_ALTERNATE_ENTRY_SOURCE_ID,
            alternateEntrySourceProfile.optString("id", ""));
        state.putString(STATE_ALTERNATE_ENTRY_RETURN_PROFILE_ID,
            alternateEntryReturnProfileId);
        state.putString(STATE_ALTERNATE_ENTRY_SERIAL, alternateEntrySerial);
        state.putString(STATE_ALTERNATE_ENTRY_SERIAL_SOURCE,
            alternateEntrySerialSource);
        state.putStringArrayList(STATE_ALTERNATE_ENTRY_PHOTOS,
            new ArrayList<>(alternateEntryPhotos));
        JSONObject toggles = new JSONObject();
        try {
            for (Map.Entry<String, Boolean> toggle : alternateEntryToggleStates.entrySet()) {
                toggles.put(toggle.getKey(), Boolean.TRUE.equals(toggle.getValue()));
            }
        } catch (JSONException ignored) {}
        state.putString(STATE_ALTERNATE_ENTRY_TOGGLES, toggles.toString());
        state.putString(STATE_ALTERNATE_ENTRY_CONNECTION,
            alternateEntryConnectionNamespace);
        state.putString(STATE_ALTERNATE_ENTRY_BINDING,
            alternateEntryBindingFingerprint);
        state.putString(STATE_ALTERNATE_ENTRY_BACKEND,
            alternateEntryBackendFingerprint);
        state.putString(STATE_ALTERNATE_ENTRY_SESSION_NONCE,
            alternateEntrySessionNonce);
        state.putString(STATE_ALTERNATE_ENTRY_PHOTO_GUARD,
            pendingAlternateEntryPhotoGuard);
        state.putString(STATE_ALTERNATE_ENTRY_SCAN_GUARD,
            pendingAlternateEntryScanGuard);
    }

    private boolean restoreAlternateEntryState(Bundle state) {
        if (state == null || !state.getBoolean(STATE_ALTERNATE_ENTRY_OPEN, false)) {
            return false;
        }
        try {
            String entryId = state.getString(STATE_ALTERNATE_ENTRY_ID, "").trim();
            String sourceId = state.getString(STATE_ALTERNATE_ENTRY_SOURCE_ID, "").trim();
            String connection = state.getString(STATE_ALTERNATE_ENTRY_CONNECTION, "");
            String binding = state.getString(STATE_ALTERNATE_ENTRY_BINDING, "");
            String backend = state.getString(STATE_ALTERNATE_ENTRY_BACKEND, "");
            if (entryId.isEmpty() || sourceId.isEmpty() || binding.isEmpty()
                    || !currentConnectionNamespace().equals(connection)
                    || !currentBackendAdapterFingerprint().equals(backend)) {
                return false;
            }
            JSONObject source = uniqueProfile(profiles, sourceId);
            JSONObject entry = alternateEntryById(source, entryId);
            String currentBinding = alternateEntryBindingFingerprint(source, entry, allProfiles);
            if (source == null || entry == null || !binding.equals(currentBinding)) {
                return false;
            }
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                boolean unsafeCandidate =
                    PanelPairCacheCoordinator.pendingCandidatesBlockActiveUse(
                        this, currentConnectionNamespace());
                alternateEntryContinuationToken = "";
                alternateEntryId = entryId;
                alternateEntryReturnProfileId = state.getString(
                    STATE_ALTERNATE_ENTRY_RETURN_PROFILE_ID, sourceId);
                alternateEntrySourceProfiles = alternateEntrySources(entryId);
                bindAlternateEntry(source, entry, allProfiles);
                String savedNonce = state.getString(
                    STATE_ALTERNATE_ENTRY_SESSION_NONCE, "");
                if (savedNonce.isEmpty()) return false;
                alternateEntrySessionNonce = savedNonce;
                alternateEntrySerial = state.getString(STATE_ALTERNATE_ENTRY_SERIAL, "");
                // Bundles produced before source tracking contained no source. Those values had
                // already passed the legacy all-source scanner policy, so migrate only that missing
                // field as a typed value. Any explicit unknown source is rejected instead.
                String savedSerialSource = state.containsKey(STATE_ALTERNATE_ENTRY_SERIAL_SOURCE)
                    ? state.getString(STATE_ALTERNATE_ENTRY_SERIAL_SOURCE, "")
                    : SnScanRules.SOURCE_ENTERED;
                if (!isIdentifierValueSource(savedSerialSource)) return false;
                alternateEntrySerialSource = savedSerialSource;
                pendingAlternateEntryPhotoPath = optionalStringPreference(
                    PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
                pendingAlternateEntryPhotoGuard = state.getString(
                    STATE_ALTERNATE_ENTRY_PHOTO_GUARD,
                    optionalStringPreference(PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY));
                ArrayList<String> savedPhotos = state.getStringArrayList(
                    STATE_ALTERNATE_ENTRY_PHOTOS);
                alternateEntryPhotos.clear();
                for (String path : savedPhotos == null
                        ? Collections.<String>emptyList() : savedPhotos) {
                    if (hasFile(path) && !path.equals(pendingAlternateEntryPhotoPath)) {
                        alternateEntryPhotos.add(path);
                    }
                }
                alternateEntryToggleStates.clear();
                JSONObject toggles = new JSONObject(state.getString(
                    STATE_ALTERNATE_ENTRY_TOGGLES, "{}"));
                JSONArray names = toggles.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    String key = names.optString(i, "");
                    Object value = toggles.opt(key);
                    if (!key.isEmpty() && value instanceof Boolean) {
                        alternateEntryToggleStates.put(key, (Boolean) value);
                    }
                }
                alternateEntryStateProfileId = sourceId;
                pendingAlternateEntryScanGuard = state.getString(
                    STATE_ALTERNATE_ENTRY_SCAN_GUARD,
                    optionalStringPreference(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY));
                alternateEntryPageOpen = true;
                // Full preflight also validates restored toggle keys against the bound entry.
                preflightAlternateEntry(alternateEntrySourceProfile,
                    alternateEntryCatalogSnapshot, alternateEntryConfig,
                    alternateEntryToggleStates);
                if (hasAlternateEntryPendingData()) {
                    AlternateEntryDraftState live = inMemoryAlternateEntryDraftState();
                    String storedContinuationToken =
                        exactStoredAlternateEntryContinuationTokenLocked(live);
                    if (unsafeCandidate) {
                        alternateEntryContinuationToken = storedContinuationToken;
                        if (alternateEntryContinuationToken.isEmpty()) return false;
                    } else {
                        alternateEntryContinuationToken = storedContinuationToken;
                        if (alternateEntryContinuationToken.isEmpty()) {
                            markAlternateEntryWorkEstablishedLocked();
                        }
                    }
                    if (!saveAlternateEntryDraft(true)) {
                        alternateEntryContinuationToken = "";
                        return false;
                    }
                }
                if (unsafeCandidate && !hasAlternateEntryPendingData()
                        && liveAlternateEntryReservationTokensLocked().isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void clearAlternateEntrySession(boolean deletePhotos) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
        // Invalidate scanner/OCR/camera callbacks before clearing any other field.
        alternateEntrySessionNonce = "";
        alternateEntryContinuationToken = "";
        if (deletePhotos) {
            for (String path : new ArrayList<>(alternateEntryPhotos)) {
                deleteFileQuietly(path);
            }
            String pending = pendingAlternateEntryPhotoPath;
            if (pending == null || pending.isEmpty()) {
                pending = optionalStringPreference(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
            }
            if (pending != null && !pending.isEmpty()) deleteFileQuietly(pending);
        }
        clearPendingAlternateEntryPhoto();
        alternateEntryPageOpen = false;
        alternateEntryId = "";
        alternateEntryStateProfileId = "";
        alternateEntryReturnProfileId = "";
        alternateEntryConnectionNamespace = "";
        alternateEntryBindingFingerprint = "";
        alternateEntryBackendFingerprint = "";
        alternateEntrySourceProfile = null;
        alternateEntryConfig = null;
        alternateEntryCatalogSnapshot = new JSONArray();
        alternateEntryAppConfigSnapshot = null;
        alternateEntryCatalogSettingsSnapshot = null;
        alternateEntrySourceProfiles = new JSONArray();
        alternateEntrySerial = "";
        alternateEntrySerialSource = SnScanRules.SOURCE_ENTERED;
        alternateEntryPhotos.clear();
        alternateEntryToggleStates.clear();
        pendingAlternateEntryScanGuard = "";
        pendingAlternateEntryScanReservation = null;
        pendingAlternateEntryPhotoReservation = null;
        boolean clearedReservations = prefs.edit()
            .remove(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY)
            .remove(PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY)
            .remove(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY)
            .remove(PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY)
            .remove(PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY)
            .commit();
        alternateEntryReservationStorageAmbiguous = !clearedReservations;
        }
    }

    private JSONArray configuredAlternateEntries(JSONObject sourceProfile) {
        JSONObject workflow = sourceProfile == null
            ? null : sourceProfile.optJSONObject("workflow");
        try {
            return AlternateEntryRules.configuredEntries(workflow);
        } catch (IllegalArgumentException invalid) {
            return new JSONArray();
        }
    }

    private JSONObject alternateEntryById(JSONObject sourceProfile, String entryId) {
        if (entryId == null || entryId.isEmpty()) return null;
        JSONArray entries = configuredAlternateEntries(sourceProfile);
        JSONObject found = null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !entryId.equals(entry.optString("id", ""))) continue;
            if (found != null) return null; // duplicate ids fail closed at runtime too
            found = entry;
        }
        return found;
    }

    private JSONArray validAlternateEntries(JSONObject sourceProfile) {
        JSONArray valid = new JSONArray();
        JSONArray entries = configuredAlternateEntries(sourceProfile);
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id", "");
            if (id.isEmpty() || !ids.add(id)) continue;
            try {
                preflightAlternateEntry(sourceProfile, entry, Collections.emptyMap());
                valid.put(entry);
            } catch (RuntimeException ignored) {
                // Invalid entries are never rendered. Panel validation reports the exact problem.
            }
        }
        return valid;
    }

    private AlternateEntryRules.Resolution preflightAlternateEntry(
            JSONObject sourceProfile, JSONObject entry,
            Map<String, Boolean> requestedToggleStates) {
        return preflightAlternateEntry(sourceProfile, allProfiles, entry,
            requestedToggleStates);
    }

    private AlternateEntryRules.Resolution preflightAlternateEntry(
            JSONObject sourceProfile, JSONArray catalog, JSONObject entry,
            Map<String, Boolean> requestedToggleStates) {
        JSONObject targetProfile = AlternateEntryRules.targetProfile(catalog, entry);
        List<String> capabilityErrors =
            RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
                sourceProfile, targetProfile, endpoints());
        if (!capabilityErrors.isEmpty()) {
            throw new IllegalArgumentException("alternate entry rejected: "
                + join(capabilityErrors, ", "));
        }
        int count = entry == null ? -1 : entry.optInt("minPhotos", -1);
        if (count < 1 || count > 20) {
            throw new IllegalArgumentException("alternate entry rejected: invalid photo bounds");
        }
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            placeholders.add("https://example.invalid/alternate-photo-" + (i + 1));
        }
        AlternateEntryRules.Resolution resolution = AlternateEntryRules.resolveForUiPreflight(
            sourceProfile, catalog, entry, "SAMPLE-ENTRY-001", placeholders,
            requestedToggleStates);
        if (!alternateEntryScannerPolicy(sourceProfile, entry).valid) {
            throw new IllegalArgumentException(
                "alternate entry rejected: invalid scanner policy");
        }
        return resolution;
    }

    private JSONArray alternateEntrySources(String entryId) {
        JSONArray sources = new JSONArray();
        for (int i = 0; profiles != null && i < profiles.length(); i++) {
            JSONObject source = profiles.optJSONObject(i);
            JSONObject entry = alternateEntryById(source, entryId);
            if (entry == null) continue;
            try {
                preflightAlternateEntry(source, entry, Collections.emptyMap());
                sources.put(source);
            } catch (RuntimeException ignored) {
                // Keep invalid profile/entry pairs out of the selector.
            }
        }
        return sources;
    }

    private int alternateEntrySourceIndex(JSONArray sources, String profileId) {
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject candidate = sources.optJSONObject(i);
            if (candidate != null && profileId != null
                    && profileId.equals(candidate.optString("id", ""))) return i;
        }
        return -1;
    }

    private AlternateEntrySelectionState storedAlternateEntrySelection(String entryId) {
        try {
            String connection = currentConnectionNamespace();
            String key = AlternateEntrySelectionState.preferenceKey(connection, entryId);
            Object raw = prefs.getAll().get(key);
            if (!(raw instanceof String)) return null;
            AlternateEntrySelectionState state =
                AlternateEntrySelectionState.parse((String) raw);
            return state.matches(connection, entryId) ? state : null;
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Independent-entry selection preference rejected");
            return null;
        }
    }

    private void stageAlternateEntrySelection(SharedPreferences.Editor editor,
                                              String entryId, String sourceProfileId) {
        if (editor == null) throw new IllegalArgumentException("preference editor is required");
        AlternateEntrySelectionState state = AlternateEntrySelectionState.create(
            currentConnectionNamespace(), entryId, sourceProfileId);
        String key = AlternateEntrySelectionState.preferenceKey(
            state.connectionNamespace, state.entryId);
        editor.putString(key, state.toJson().toString());
    }

    /** Save only a validated presentation choice; failure never authorizes or blocks a workflow. */
    private void rememberAlternateEntrySelection(String entryId, String sourceProfileId) {
        try {
            SharedPreferences.Editor editor = prefs.edit();
            stageAlternateEntrySelection(editor, entryId, sourceProfileId);
            if (!editor.commit()) {
                Diagnostics.append(this,
                    "Independent-entry selection preference commit failed");
            }
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Independent-entry selection preference not saved");
        }
    }

    private void showAlternateEntryPage(String entryId) {
        if (alternateEntrySubmitting || submitting
                || profileOwnedRemoteWorkerActive()) {
            toast(t("submit_running"));
            return;
        }
        UploadReplayBarrier.RestoreResult blockingUpload =
            blockingUploadReplayBarrier();
        if (blockingUpload != null) {
            showUploadReplayBarrierBlock(blockingUpload);
            return;
        }
        if (savedToken().isEmpty()) {
            showSettingsPage();
            return;
        }
        if (panelUseBlocked() && !activeWorkflowCanContinue()
                && !unsafeContinuationCanResumeAlternateEntry()) {
            notifyBackendUnconfigured();
            showSettingsPage();
            return;
        }
        if (entryId == null || entryId.trim().isEmpty()) {
            alert(t("panel_required_title"), t("alternate_entry_invalid"));
            return;
        }
        String requestedId = entryId.trim();
        if (!hasAlternateEntryPendingData() && alternateEntryId.isEmpty()) {
            int restored = restoreStoredAlternateEntryDraft(requestedId);
            if (restored < 0) {
                showLockedAlternateEntryDraftDialog(requestedId);
                return;
            }
        }
        if (!alternateEntryId.isEmpty() && !alternateEntryId.equals(requestedId)
                && hasAlternateEntryPendingData()) {
            alert(t("alternate_entry_pending_title"), t("alternate_entry_pending_detail"));
            return;
        }
        boolean reuseBinding = (alternateEntryPageOpen || hasAlternateEntryPendingData())
            && requestedId.equals(alternateEntryId)
            && alternateEntrySourceProfile != null
            && alternateEntryConfig != null
            && !alternateEntryBindingFingerprint.isEmpty();
        JSONArray sources;
        JSONObject source;
        JSONObject entry;
        int selected;
        if (reuseBinding) {
            sources = alternateEntrySourceProfiles;
            source = alternateEntrySourceProfile;
            entry = alternateEntryConfig;
            selected = AlternateEntrySelectionState.pageSourceIndex(
                sources, source.optString("id", ""),
                storedAlternateEntrySelection(requestedId),
                currentConnectionNamespace(), requestedId, currentProfileId());
            if (selected < 0) {
                showLockedAlternateEntryDraftDialog(requestedId);
                return;
            }
        } else {
            String returnId = alternateEntryPageOpen
                ? alternateEntryReturnProfileId : currentProfileId();
            if (!alternateEntryPageOpen && !units.isEmpty()) {
                // Persist against the currently rendered profile before any catalog reload can
                // replace the global profile object. Only then may this page unload the queue.
                if (!saveDraft(true)) {
                    alert(t("draft_save_failed"), t("alternate_entry_queue_save_failed"));
                    return;
                }
                units.clear();
            }
            reloadCatalogProfiles();
            sources = alternateEntrySources(requestedId);
            if (sources.length() == 0) {
                alert(t("panel_required_title"), t("alternate_entry_invalid"));
                selectVisibleProfile(returnId);
                showFormPage(false);
                restoreCurrentProfileDraftOrEmpty();
                return;
            }
            selected = AlternateEntrySelectionState.pageSourceIndex(
                sources, "", storedAlternateEntrySelection(requestedId),
                currentConnectionNamespace(), requestedId, currentProfileId());
            if (selected < 0) selected = 0;
            source = sources.optJSONObject(selected);
            entry = alternateEntryById(source, requestedId);
            if (source == null || entry == null) {
                alert(t("panel_required_title"), t("alternate_entry_invalid"));
                selectVisibleProfile(returnId);
                showFormPage(false);
                restoreCurrentProfileDraftOrEmpty();
                return;
            }
            try {
                alternateEntryId = requestedId;
                alternateEntryReturnProfileId = returnId;
                alternateEntrySourceProfiles = new JSONArray(sources.toString());
                alternateEntryToggleStates.clear();
                alternateEntryStateProfileId = "";
                bindAlternateEntryForNewWork(source, entry, allProfiles);
                source = alternateEntrySourceProfile;
                entry = alternateEntryConfig;
                sources = alternateEntrySourceProfiles;
                selected = AlternateEntrySelectionState.pageSourceIndex(
                    sources, source.optString("id", ""),
                    storedAlternateEntrySelection(requestedId),
                    currentConnectionNamespace(), requestedId, currentProfileId());
                if (selected < 0) {
                    throw new JSONException("bound independent-entry source is unavailable");
                }
                rememberAlternateEntrySelection(requestedId,
                    source.optString("id", ""));
            } catch (Exception invalid) {
                clearAlternateEntrySession(false);
                selectVisibleProfile(returnId);
                alert(t("panel_required_title"), t("alternate_entry_invalid"));
                showFormPage(false);
                restoreCurrentProfileDraftOrEmpty();
                return;
            }
        }
        profile = source; // scanner policy and display labels remain bound to the selected source
        initializeAlternateEntryToggles(source, entry);
        if (alternateEntrySessionNonce.isEmpty()) {
            alternateEntrySessionNonce = java.util.UUID.randomUUID().toString();
        }
        alternateEntryPageOpen = true;
        settingsPageOpen = false;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF1F5F9);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        LinearLayout headerPanel = panel();
        LinearLayout headerRow = row();
        headerRow.addView(button(t("go_back"), v -> exitAlternateEntryPage()));
        headerRow.addView(new View(this),
            new LinearLayout.LayoutParams(0, 1, 1f));
        headerPanel.addView(headerRow);
        String titleText = localized(entry, "title", "titleI18n");
        TextView title = text(titleText, 24, true);
        title.setTextColor(0xFF0F172A);
        headerPanel.addView(title);
        TextView subtitle = text(t("alternate_entry_subtitle"), 13, false);
        subtitle.setTextColor(0xFF64748B);
        subtitle.setPadding(0, dp(3), 0, 0);
        headerPanel.addView(subtitle);
        root.addView(headerPanel);

        LinearLayout setupPanel = panel();
        setupPanel.addView(compactLabel(t("form")));
        alternateEntryProfileSpinner = new Spinner(this);
        alternateEntryProfileSpinner.setAdapter(
            new ProfileSpinnerAdapter(alternateEntrySourceProfiles));
        alternateEntryProfileSpinner.setSelection(selected);
        final boolean[] selectionReady = {false};
        alternateEntryProfileSpinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                      int position, long id) {
                    if (!selectionReady[0]) return;
                    if (alternateEntryEditingBlocked()) {
                        int current = alternateEntrySourceIndex(
                            alternateEntrySourceProfiles,
                            alternateEntrySourceProfile == null ? ""
                                : alternateEntrySourceProfile.optString("id", ""));
                        if (current >= 0
                                && alternateEntryProfileSpinner.getSelectedItemPosition()
                                    != current) {
                            alternateEntryProfileSpinner.setSelection(current);
                        }
                        return;
                    }
                    JSONObject next = alternateEntrySourceProfiles.optJSONObject(position);
                    if (next == null || (alternateEntrySourceProfile != null
                            && next.optString("id", "").equals(
                                alternateEntrySourceProfile.optString("id", "")))) return;
                    if (hasAlternateEntryPendingData()) {
                        alert(t("alternate_entry_pending_title"),
                            t("alternate_entry_pending_detail"));
                        int current = alternateEntrySourceIndex(
                            alternateEntrySourceProfiles,
                            alternateEntrySourceProfile == null ? ""
                                : alternateEntrySourceProfile.optString("id", ""));
                        if (current >= 0
                                && alternateEntryProfileSpinner.getSelectedItemPosition()
                                    != current) {
                            alternateEntryProfileSpinner.setSelection(current);
                        }
                        return;
                    }
                    JSONObject nextEntry = alternateEntryById(next, alternateEntryId);
                    if (nextEntry == null) {
                        alert(t("panel_required_title"), t("alternate_entry_invalid"));
                        return;
                    }
                    try {
                        alternateEntryStateProfileId = "";
                        bindAlternateEntryForNewWork(next, nextEntry,
                            alternateEntryCatalogSnapshot);
                        rememberAlternateEntrySelection(alternateEntryId,
                            next.optString("id", ""));
                        showAlternateEntryPage(alternateEntryId);
                    } catch (Exception invalid) {
                        alert(t("panel_required_title"), t("alternate_entry_invalid"));
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        alternateEntryProfileSpinner.post(() -> selectionReady[0] = true);
        setupPanel.addView(alternateEntryProfileSpinner);
        root.addView(setupPanel);

        LinearLayout capturePanel = panel();
        capturePanel.addView(compactLabel(primaryInputLabel()));
        alternateEntrySerialEdit = edit(inputPlaceholder(false));
        alternateEntrySerialEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alternateEntrySerialEdit.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP) {
                applyTypedAlternateEntrySerial();
                return true;
            }
            return false;
        });
        alternateEntrySerialEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_NEXT
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_UP)) {
                applyTypedAlternateEntrySerial();
                return true;
            }
            return false;
        });
        capturePanel.addView(alternateEntrySerialEdit);
        LinearLayout serialActions = row();
        if (identifierScanEnabled(false)) {
            serialActions.addView(button(scanPrompt(false),
                v -> startAlternateEntryScan()));
        }
        serialActions.addView(button(t("add"), v -> applyTypedAlternateEntrySerial()));
        serialActions.addView(button(t("alternate_entry_clear_serial"),
            v -> clearAlternateEntrySerial()));
        capturePanel.addView(serialActions);
        alternateEntrySerialText = text("", 20, true);
        alternateEntrySerialText.setTextColor(0xFF0F766E);
        alternateEntrySerialText.setPadding(0, dp(6), 0, dp(4));
        capturePanel.addView(alternateEntrySerialText);

        capturePanel.addView(compactLabel(t("alternate_entry_photo")));
        alternateEntryPhotoText = text("", 13, false);
        alternateEntryPhotoText.setTextColor(0xFF334155);
        capturePanel.addView(alternateEntryPhotoText);
        alternateEntryPhotoList = new LinearLayout(this);
        alternateEntryPhotoList.setOrientation(LinearLayout.VERTICAL);
        capturePanel.addView(alternateEntryPhotoList);
        capturePanel.addView(button(t("alternate_entry_add_photo"),
            v -> captureAlternateEntryPhoto()));
        root.addView(capturePanel);

        LinearLayout submitPanel = panel();
        alternateEntryToggleList = new LinearLayout(this);
        alternateEntryToggleList.setOrientation(LinearLayout.VERTICAL);
        submitPanel.addView(alternateEntryToggleList);
        submitPanel.addView(button(t("alternate_entry_submit"),
            v -> submitAlternateEntry()));
        root.addView(submitPanel);

        setPageContentView(scroll);
        refreshAlternateEntryUi();
        alternateEntrySerialEdit.requestFocus();
    }

    private void showLockedAlternateEntryDraftDialog(String requestedEntryId) {
        new AlertDialog.Builder(this)
            .setTitle(t("alternate_entry_pending_title"))
            .setMessage(t("alternate_entry_draft_locked_detail"))
            .setNegativeButton(t("cancel"), null)
            .setPositiveButton(t("discard_draft"), (dialog, which) -> {
                if (!clearStoredAlternateEntryDraft(true)) {
                    alert(t("alternate_entry_pending_title"),
                        t("alternate_entry_discard_failed"));
                    return;
                }
                clearAlternateEntrySession(true);
                showAlternateEntryPage(requestedEntryId);
            })
            .show();
    }

    private void initializeAlternateEntryToggles(JSONObject source, JSONObject entry) {
        String sourceId = source == null ? "" : source.optString("id", "");
        if (sourceId.equals(alternateEntryStateProfileId)) return;
        Map<String, Boolean> previousStates = new LinkedHashMap<>(
            alternateEntryToggleStates);
        AlternateEntryRules.Resolution resolution = preflightAlternateEntry(
            source, alternateEntryCatalogSnapshot, entry, Collections.emptyMap());
        alternateEntryToggleStates.clear();
        alternateEntryToggleStates.putAll(resolution.effectiveToggleStates);
        // A toggle explicitly marked retainUntilExit follows the old dedicated page across source
        // changes, but only when the newly bound source declares the same key. Unknown keys never
        // cross the boundary.
        for (AlternateEntryRules.TogglePolicy policy : resolution.togglePolicies) {
            if (policy.retainUntilExit && previousStates.containsKey(policy.key)) {
                alternateEntryToggleStates.put(policy.key,
                    Boolean.TRUE.equals(previousStates.get(policy.key)));
            }
        }
        // Re-run the resolver with the carried values before rendering them.
        preflightAlternateEntry(source, alternateEntryCatalogSnapshot, entry,
            alternateEntryToggleStates);
        alternateEntryStateProfileId = sourceId;
    }

    private void refreshAlternateEntryUi() {
        if (alternateEntrySerialText != null) {
            alternateEntrySerialText.setText(alternateEntrySerial.isEmpty()
                ? t("alternate_entry_serial_empty") : alternateEntrySerial);
        }
        if (alternateEntryPhotoText != null) {
            int count = alternateEntryPhotos.size();
            alternateEntryPhotoText.setText(count == 0
                ? t("alternate_entry_no_photo")
                : t("alternate_entry_photo_count") + count);
        }
        if (alternateEntryPhotoList != null) {
            alternateEntryPhotoList.removeAllViews();
            for (int i = 0; i < alternateEntryPhotos.size(); i++) {
                final String path = alternateEntryPhotos.get(i);
                LinearLayout photoRow = row();
                TextView label = text(t("alternate_entry_photo_item") + (i + 1),
                    14, false);
                label.setTextColor(0xFF334155);
                photoRow.addView(label,
                    new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                photoRow.addView(button(t("view_photo"),
                    v -> showPhotoPreview(t("alternate_entry_photo"), path)));
                photoRow.addView(button(t("delete_photo"), v -> {
                    if (alternateEntryEditingBlocked()) return;
                    synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                        if (!alternateEntryExpansionAllowedLocked()) return;
                        alternateEntryPhotos.remove(path);
                        retireAlternateEntryWorkTokenIfEmptyLocked();
                        persistAlternateEntryDraftBestEffort();
                    }
                    deleteFileQuietly(path);
                    refreshAlternateEntryUi();
                }));
                alternateEntryPhotoList.addView(photoRow);
            }
        }
        if (alternateEntryToggleList != null && alternateEntryConfig != null
                && alternateEntrySourceProfile != null) {
            alternateEntryToggleList.removeAllViews();
            try {
                AlternateEntryRules.Resolution resolution = preflightAlternateEntry(
                    alternateEntrySourceProfile, alternateEntryCatalogSnapshot,
                    alternateEntryConfig,
                    alternateEntryToggleStates);
                for (AlternateEntryRules.TogglePolicy policy : resolution.togglePolicies) {
                    CheckBox toggle = new CheckBox(this);
                    toggle.setText(policy.localizedLabel(lang));
                    toggle.setTextSize(13);
                    toggle.setTextColor(0xFF334155);
                    toggle.setChecked(Boolean.TRUE.equals(
                        resolution.effectiveToggleStates.get(policy.key)));
                    toggle.setOnCheckedChangeListener((button, checked) -> {
                        if (alternateEntryEditingBlocked()) {
                            // Rebuild from the retained map; do not recursively fire this listener.
                            refreshAlternateEntryUi();
                            return;
                        }
                        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                            if (!alternateEntryExpansionAllowedLocked()) {
                                refreshAlternateEntryUi();
                                return;
                            }
                            alternateEntryToggleStates.put(policy.key, checked);
                            persistAlternateEntryDraftBestEffort();
                        }
                    });
                    alternateEntryToggleList.addView(toggle);
                }
            } catch (RuntimeException error) {
                TextView invalid = text(t("alternate_entry_invalid"), 13, true);
                invalid.setTextColor(0xFFB91C1C);
                alternateEntryToggleList.addView(invalid);
            }
        }
    }

    private void exitAlternateEntryPage() {
        String returnProfileId = alternateEntryReturnProfileId;
        if (alternateEntrySubmitting) {
            toast(t("submit_running"));
            return;
        }
        UploadReplayBarrier.RestoreResult blockingUpload =
            blockingUploadReplayBarrier();
        if (blockingUpload != null) {
            showUploadReplayBarrierBlock(blockingUpload);
            return;
        }
        AlternateSubmissionAttempt.RestoreResult blockingAttempt =
            blockingAlternateSubmissionAttempt();
        if (blockingAttempt != null) {
            // Keep the exact source/target profile active until the unresolved remote outcome is
            // reconciled. Even a direct profile switch would reinterpret global workflow state.
            showAlternateSubmissionBlock(blockingAttempt);
            return;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            // Exiting clears non-retained toggle state. That is a payload mutation, so an old-pair
            // draft may not cross this boundary after an unsafe candidate appears.
            if (unsafeCandidatesBlockActiveUse()
                    && (hasAlternateEntryPendingData()
                        || !liveAlternateEntryReservationTokensLocked().isEmpty()
                        || UnsafeCandidateContinuationRules.validAlternateEntryToken(
                            alternateEntryContinuationToken))) {
                toast(t("panel_syncing_short"));
                return;
            }
            boolean keepBoundPendingData = hasAlternateEntryPendingData();
            alternateEntryPageOpen = false;
            alternateEntryToggleStates.clear();
            alternateEntryStateProfileId = "";
            persistAlternateEntryDraftBestEffort();
            if (!keepBoundPendingData) clearAlternateEntrySession(false);
        }
        selectVisibleProfile(returnProfileId);
        saveLastProfile();
        showFormPage(false);
        restoreCurrentProfileDraftOrEmpty();
    }

    private void applyTypedAlternateEntrySerial() {
        if (alternateEntryEditingBlocked()) return;
        if (alternateEntrySerialEdit == null) return;
        String serial = normalizeAlternateEntryIdentifier(
            alternateEntrySerialEdit.getText().toString(), SnScanRules.SOURCE_ENTERED);
        if (!validateAlternateEntryIdentifier(serial, SnScanRules.SOURCE_ENTERED)) return;
        setAlternateEntrySerial(serial, SnScanRules.SOURCE_ENTERED);
        alternateEntrySerialEdit.setText("");
    }

    private void clearAlternateEntrySerial() {
        if (alternateEntryEditingBlocked()) return;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!alternateEntryExpansionAllowedLocked()) return;
            alternateEntrySerial = "";
            alternateEntrySerialSource = SnScanRules.SOURCE_ENTERED;
            retireAlternateEntryWorkTokenIfEmptyLocked();
            persistAlternateEntryDraftBestEffort();
        }
        if (alternateEntrySerialEdit != null) {
            alternateEntrySerialEdit.setText("");
            alternateEntrySerialEdit.requestFocus();
        }
        refreshAlternateEntryUi();
    }

    private void setAlternateEntrySerial(String serial, String source) {
        if (alternateEntryEditingBlocked()) return;
        if (!isIdentifierValueSource(source)) {
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(false));
            return;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!alternateEntryExpansionAllowedLocked()) return;
            alternateEntrySerial = serial == null ? "" : serial;
            alternateEntrySerialSource = source;
            markAlternateEntryWorkEstablishedLocked();
            persistAlternateEntryDraftBestEffort();
        }
        showScannedSnPreview(alternateEntrySerial, primaryInputLabel());
        refreshAlternateEntryUi();
    }

    private void startAlternateEntryScan() {
        final AlternateEntryAsyncReservation cancelable;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            cancelable = cancelableStoredAlternateEntryScanLocked();
        }
        if (cancelable != null) {
            new AlertDialog.Builder(this)
                .setTitle(t("alternate_entry_cancel_scan_title"))
                .setMessage(t("alternate_entry_cancel_scan_detail"))
                .setNegativeButton(t("cancel"), null)
                .setPositiveButton(t("alternate_entry_cancel_scan_action"),
                    (dialog, which) -> {
                        if (!cancelStoredAlternateEntryScan(cancelable)) {
                            alert(t("draft_save_failed"),
                                t("alternate_entry_storage_locked_detail"));
                            return;
                        }
                        toast(t("alternate_entry_scan_cancelled"));
                        startAlternateEntryScan();
                    })
                .show();
            return;
        }
        if (alternateEntryEditingBlocked()) return;
        if (hasPendingAlternateEntryAsyncReservationEvidence()) {
            toast(t("alternate_entry_async_pending"));
            return;
        }
        if (!identifierScanEnabled(false)) {
            toast(scanDisabledMessage(false));
            return;
        }
        SnScanRules.Policy entryScannerPolicy = alternateEntryScannerPolicy();
        if (!entryScannerPolicy.valid) {
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(false));
            return;
        }
        if (!ensureCameraPermission()) return;
        String operationGuard = alternateEntryOperationGuard();
        if (operationGuard.isEmpty()) {
            alert(t("panel_required_title"), t("alternate_entry_invalid"));
            return;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            AlternateEntryAsyncReservation reservation =
                createAlternateEntryReservationLocked(
                    AlternateEntryAsyncReservation.KIND_SCAN, operationGuard, "");
            if (reservation == null || !prefs.edit()
                    .putString(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY, operationGuard)
                    .putString(PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY,
                        reservation.toJson().toString())
                    .commit()) {
                if (reservation != null) {
                    alternateEntryReservationStorageAmbiguous = true;
                }
                alert(t("draft_save_failed"), t("alternate_entry_storage_locked_detail"));
                return;
            }
            alternateEntryReservationStorageAmbiguous = false;
            pendingAlternateEntryScanGuard = operationGuard;
            pendingAlternateEntryScanReservation = reservation;
        }
        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.setClass(this, ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", scanPrompt(false));
        intent.putExtra("IDENTIFIER_LABEL", inputLabel(false));
        intent.putExtra("lang", lang);
        intent.putExtra("AUTO_TEXT_MODE", entryScannerPolicy.autoTextMode);
        intent.putExtra("REJECT_NUMERIC_ONLY", entryScannerPolicy.rejectNumericOnly);
        intent.putExtra("SCANNER_POLICY_JSON",
            effectiveAlternateEntryScannerConfig().toString());
        intent.putExtra("OCR_ONLY", false);
        intent.putExtra(EXTRA_EXPECTED_SN_LENGTH, entryScannerPolicy.expectedLength);
        intent.putExtra("PREFERRED_SN_PREFIXES",
            entryScannerPolicy.preferredPrefixes.toArray(new String[0]));
        intent.putExtra("SCAN_CAMERA_ID", 0);
        intent.putExtra("SCAN_ORIENTATION_LOCKED", true);
        intent.putExtra("BEEP_ENABLED", false);
        intent.putExtra("BARCODE_IMAGE_ENABLED", false);
        intent.putExtra("SHOW_MISSING_CAMERA_PERMISSION_DIALOG", false);
        try {
            startActivityForResult(intent, REQ_SCAN_ALTERNATE_ENTRY_SN);
        } catch (ActivityNotFoundException error) {
            clearPendingAlternateEntryScanGuard();
            alert(t("scanner_missing_title"), t("scanner_missing_detail"));
        } catch (Exception error) {
            clearPendingAlternateEntryScanGuard();
            alert(t("camera_open_failed"), conciseError(error));
        }
    }

    private void handleAlternateEntryScanResult(int resultCode, Intent data) {
        final AlternateEntryAsyncReservation reservation;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            reservation = exactStoredAlternateEntryReservationLocked(
                AlternateEntryAsyncReservation.KIND_SCAN);
        }
        if (reservation == null) {
            if (data != null) {
                String stalePath = data.getStringExtra("OCR_PHOTO_PATH");
                if (stalePath != null && !stalePath.trim().isEmpty()) {
                    deleteFileQuietly(stalePath);
                }
            }
            clearPendingAlternateEntryScanGuard();
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            if (data != null) {
                String stalePath = data.getStringExtra("OCR_PHOTO_PATH");
                if (stalePath != null && !stalePath.trim().isEmpty()) {
                    deleteFileQuietly(stalePath);
                }
            }
            clearAlternateEntryReservation(reservation, false);
            return;
        }
        String ocrPhotoPath = data.getStringExtra("OCR_PHOTO_PATH");
        if (ocrPhotoPath != null && !ocrPhotoPath.trim().isEmpty()) {
            recognizeAlternateEntrySerialFromPhoto(new File(ocrPhotoPath),
                pendingAlternateEntryScanGuard, reservation);
            return;
        }
        String source = scanResultSource(data);
        if (source.isEmpty()) {
            clearAlternateEntryReservation(reservation, false);
            rejectUnknownScanResultFormat(data.getStringExtra("SCAN_RESULT_FORMAT"), false);
            return;
        }
        // ScannerActivity returns the already source-normalized value. Reapplying label stripping
        // here could remove a legitimate identifier prefix that happens to match the label.
        String serial = data.getStringExtra("SCAN_RESULT");
        if (serial == null) serial = "";
        if (!validateAlternateEntryIdentifier(serial, source)) {
            clearAlternateEntryReservation(reservation, false);
            return;
        }
        if (!materializeAlternateEntrySerial(reservation, serial, source)) {
            alert(t("draft_save_failed"), t("alternate_entry_storage_locked_detail"));
        }
    }

    private void clearPendingAlternateEntryScanGuard() {
        pendingAlternateEntryScanGuard = "";
        pendingAlternateEntryScanReservation = null;
        boolean cleared = prefs.edit()
            .remove(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY)
            .remove(PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY)
            .commit();
        alternateEntryReservationStorageAmbiguous = !cleared;
    }

    private void recognizeAlternateEntrySerialFromPhoto(File photoFile,
                                                         String operationGuard,
                                                         AlternateEntryAsyncReservation reservation) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!alternateEntryReservationMayMaterializeLocked(reservation)) {
                if (photoFile != null) deleteFileQuietly(photoFile.getAbsolutePath());
                clearAlternateEntryReservation(reservation, false);
                return;
            }
        }
        if (!alternateEntryOperationMatches(operationGuard)) {
            if (photoFile != null) deleteFileQuietly(photoFile.getAbsolutePath());
            clearAlternateEntryReservation(reservation, false);
            return;
        }
        final String tokenSnapshot = savedToken();
        String recognizeTextUrl = boundRecognizeTextUrl(tokenSnapshot);
        if (recognizeTextUrl.isEmpty()) {
            clearAlternateEntryReservation(reservation, false);
            toast(t("ocr_auto_no_text"));
            return;
        }
        final BackendAdapter adapterSnapshot = BackendAdapter.from(
            alternateEntryAppConfigSnapshot, alternateEntryCatalogSettingsSnapshot);
        final JSONObject sourceProfileSnapshot;
        final JSONObject targetProfileSnapshot;
        try {
            sourceProfileSnapshot = alternateEntrySourceProfile == null ? null
                : new JSONObject(alternateEntrySourceProfile.toString());
            JSONObject targetProfile = AlternateEntryRules.targetProfile(
                alternateEntryCatalogSnapshot, alternateEntryConfig);
            targetProfileSnapshot = targetProfile == null ? null
                : new JSONObject(targetProfile.toString());
            List<String> capabilityErrors =
                RemoteSideEffectSafetyRules.alternateEntryOcrCapabilityErrors(
                    sourceProfileSnapshot, targetProfileSnapshot, adapterSnapshot);
            if (!capabilityErrors.isEmpty()) {
                throw new IllegalStateException(join(capabilityErrors, ", "));
            }
        } catch (Exception blocked) {
            Diagnostics.append(this, "Alternate-entry OCR capability blocked: "
                + conciseError(blocked));
            clearAlternateEntryReservation(reservation, false);
            alert(t("panel_required_title"), t("panel_missing_config"));
            return;
        }
        final OperationBindingRules.Binding boundOperation;
        try {
            boundOperation = beginReservedAlternateEntryBoundOperation(
                OperationBindingRules.OCR, tokenSnapshot, reservation);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Alternate-entry OCR binding unavailable: "
                + conciseError(invalid));
            clearAlternateEntryReservation(reservation, false);
            return;
        }
        final Api apiSnapshot = api(tokenSnapshot, alternateEntryAppConfigSnapshot,
            alternateEntryCatalogSettingsSnapshot);
        new Thread(() -> {
            if (!alternateEntryOperationMatches(operationGuard)
                    || !boundOperationMatches(boundOperation, tokenSnapshot)
                    || tokenSnapshot.isEmpty()) {
                finishBoundOperation(boundOperation);
                clearAlternateEntryReservation(reservation, false);
                return;
            }
            try {
                List<File> images = prepareSnRecognitionImages(photoFile);
                for (File image : images) {
                    if (!alternateEntryOperationMatches(operationGuard)
                            || !boundOperationMatches(boundOperation, tokenSnapshot)) {
                        finishBoundOperation(boundOperation);
                        clearAlternateEntryReservation(reservation, false);
                        return;
                    }
                    try {
                        JSONObject body =
                            RemoteSideEffectSafetyRules.executeAlternateEntryOcr(
                                sourceProfileSnapshot, targetProfileSnapshot,
                                apiSnapshot.endpoints,
                                () -> apiSnapshot.recognizeText(recognizeTextUrl, image,
                                phase -> requireBoundOperation(
                                    boundOperation, tokenSnapshot, phase)));
                        List<String> candidates = extractOcrCandidates(apiSnapshot, body);
                        if (!candidates.isEmpty()) {
                            runOnUiThread(() -> {
                                if (alternateEntryOperationMatches(operationGuard)
                                        && boundOperationMatches(
                                            boundOperation, tokenSnapshot)) {
                                    showAlternateEntryOcrCandidates(candidates,
                                        operationGuard, boundOperation, tokenSnapshot,
                                        reservation);
                                } else {
                                    finishBoundOperation(boundOperation);
                                    clearAlternateEntryReservation(reservation, false);
                                }
                            });
                            return;
                        }
                    } catch (Exception imageError) {
                        if (!boundOperationMatches(boundOperation, tokenSnapshot)) {
                            finishBoundOperation(boundOperation);
                            clearAlternateEntryReservation(reservation, false);
                            return;
                        }
                        Diagnostics.append(this,
                            "Alternate-entry OCR image skipped: " + conciseError(imageError));
                    }
                }
                runOnUiThread(() -> {
                    if (alternateEntryOperationMatches(operationGuard)
                            && boundOperationMatches(boundOperation, tokenSnapshot)) {
                        finishBoundOperation(boundOperation);
                        clearAlternateEntryReservation(reservation, false);
                        alert(t("ocr_no_text_title"), t("ocr_no_text_detail"));
                    } else {
                        finishBoundOperation(boundOperation);
                        clearAlternateEntryReservation(reservation, false);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (alternateEntryOperationMatches(operationGuard)
                            && boundOperationMatches(boundOperation, tokenSnapshot)) {
                        finishBoundOperation(boundOperation);
                        clearAlternateEntryReservation(reservation, false);
                        alert(t("ocr_failed"), conciseError(error));
                    } else {
                        finishBoundOperation(boundOperation);
                        clearAlternateEntryReservation(reservation, false);
                    }
                });
            }
        }).start();
    }

    private void showAlternateEntryOcrCandidates(List<String> candidates,
                                                 String operationGuard,
                                                 OperationBindingRules.Binding boundOperation,
                                                 String tokenSnapshot,
                                                 AlternateEntryAsyncReservation reservation) {
        if (!alternateEntryOperationMatches(operationGuard)
                || !boundOperationMatches(boundOperation, tokenSnapshot)) {
            finishBoundOperation(boundOperation);
            clearAlternateEntryReservation(reservation, false);
            return;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!alternateEntryReservationMayMaterializeLocked(reservation)) {
                finishBoundOperation(boundOperation);
                return;
            }
        }
        if (candidates == null || candidates.isEmpty() || !activityAlive()) {
            finishBoundOperation(boundOperation);
            clearAlternateEntryReservation(reservation, false);
            alert(t("ocr_no_text_title"), t("ocr_no_text_detail"));
            return;
        }
        SnScanRules.Policy policy = alternateEntryScannerPolicy();
        if (!policy.valid) {
            finishBoundOperation(boundOperation);
            clearAlternateEntryReservation(reservation, false);
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(false));
            return;
        }
        List<String> filtered = new ArrayList<>();
        for (String candidate : candidates) {
            String normalized = policy.normalizeForSource(candidate, SnScanRules.SOURCE_OCR);
            if (policy.rejectionForSource(normalized, SnScanRules.SOURCE_OCR)
                    == SnScanRules.Rejection.NONE) {
                filtered.add(normalized);
            }
        }
        if (filtered.isEmpty()) {
            finishBoundOperation(boundOperation);
            clearAlternateEntryReservation(reservation, false);
            List<Integer> required = policy.requiredLengthsForSource(
                SnScanRules.SOURCE_OCR);
            alert(t("ocr_no_text_title"), !required.isEmpty()
                ? identifierExpectedOnlyMessage(false, required)
                : identifierPolicyRejectedMessage(false, policy));
            return;
        }
        String[] items = filtered.toArray(new String[0]);
        AlertDialog chooser = new AlertDialog.Builder(this)
            .setTitle(t("ocr_choose_title"))
            .setItems(items, (dialog, which) -> {
                if (!alternateEntryOperationMatches(operationGuard)
                        || !boundOperationMatches(boundOperation, tokenSnapshot)) return;
                finishBoundOperation(boundOperation);
                String serial = items[which];
                if (!validateAlternateEntryIdentifier(
                        serial, SnScanRules.SOURCE_OCR)) {
                    clearAlternateEntryReservation(reservation, false);
                    return;
                }
                if (!materializeAlternateEntrySerial(
                        reservation, serial, SnScanRules.SOURCE_OCR)) {
                    alert(t("draft_save_failed"),
                        t("alternate_entry_storage_locked_detail"));
                }
            })
            .setNegativeButton(t("cancel"), (dialog, which) -> {
                if (boundOperationMatches(boundOperation, tokenSnapshot)) {
                    finishBoundOperation(boundOperation);
                }
                clearAlternateEntryReservation(reservation, false);
            })
            .create();
        chooser.setOnCancelListener(dialog -> {
            finishBoundOperation(boundOperation);
            clearAlternateEntryReservation(reservation, false);
        });
        chooser.setOnDismissListener(dialog -> finishBoundOperation(boundOperation));
        chooser.show();
    }

    private void captureAlternateEntryPhoto() {
        if (!ensurePanelReadyForUse()) return;
        if (alternateEntryEditingBlocked()) return;
        if (hasPendingAlternateEntryAsyncReservationEvidence()) {
            toast(t("alternate_entry_async_pending"));
            return;
        }
        if (alternateEntryConfig == null) {
            alert(t("panel_required_title"), t("alternate_entry_invalid"));
            return;
        }
        int maxPhotos = alternateEntryConfig.optInt("maxPhotos", 0);
        // maxPhotos=0 preserves the legacy dedicated entry's unlimited capture behavior.
        if (maxPhotos > 0 && alternateEntryPhotos.size() >= maxPhotos) {
            toast(t("alternate_entry_photo_limit"));
            return;
        }
        if (!ensureCameraPermission()) return;
        String operationGuard = alternateEntryOperationGuard();
        if (operationGuard.isEmpty()) {
            alert(t("panel_required_title"), t("alternate_entry_invalid"));
            return;
        }
        try {
            final File outputFile;
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                outputFile = createAlternateEntryPhotoOutputFile();
                AlternateEntryAsyncReservation reservation =
                    createAlternateEntryReservationLocked(
                        AlternateEntryAsyncReservation.KIND_PHOTO, operationGuard,
                        outputFile.getAbsolutePath());
                if (reservation == null || !prefs.edit()
                    .putString(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY,
                        outputFile.getAbsolutePath())
                    .putString(PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY,
                        operationGuard)
                    .putString(PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY,
                        reservation.toJson().toString())
                    .commit()) {
                    if (reservation != null) {
                        alternateEntryReservationStorageAmbiguous = true;
                    }
                    alert(t("draft_save_failed"),
                        t("alternate_entry_storage_locked_detail"));
                    return;
                }
                alternateEntryReservationStorageAmbiguous = false;
                pendingAlternateEntryPhotoPath = outputFile.getAbsolutePath();
                pendingAlternateEntryPhotoUri = SimplePhotoProvider.uriForFile(this, outputFile);
                pendingAlternateEntryPhotoGuard = operationGuard;
                pendingAlternateEntryPhotoReservation = reservation;
            }
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingAlternateEntryPhotoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<ResolveInfo> cameraApps = getPackageManager().queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (intent.resolveActivity(getPackageManager()) != null
                    && !cameraApps.isEmpty()) {
                for (ResolveInfo cameraApp : cameraApps) {
                    grantUriPermission(cameraApp.activityInfo.packageName,
                        pendingAlternateEntryPhotoUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                startActivityForResult(intent, REQ_CAPTURE_ALTERNATE_ENTRY_PHOTO);
                return;
            }
            Intent fallback = new Intent(this, CaptureActivity.class);
            fallback.putExtra("fileName", outputFile.getName());
            fallback.putExtra("label", t("alternate_entry_photo"));
            fallback.putExtra("lang", lang);
            startActivityForResult(fallback, REQ_CAPTURE_ALTERNATE_ENTRY_PHOTO);
        } catch (Exception error) {
            clearPendingAlternateEntryPhoto();
            alert(t("camera_open_failed"), conciseError(error));
        }
    }

    private File createAlternateEntryPhotoOutputFile() throws IOException {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create photo directory");
        }
        String serial = alternateEntrySerial.isEmpty() ? "unit" : alternateEntrySerial;
        return new File(dir, safePhotoFileName("alternate-entry-" + serial + "-"
            + System.currentTimeMillis() + ".jpg"));
    }

    private void handleAlternateEntryPhotoResult(int resultCode, Intent data) {
        if (pendingAlternateEntryPhotoUri != null) {
            try {
                revokeUriPermission(pendingAlternateEntryPhotoUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        pendingAlternateEntryPhotoUri = null;
        final AlternateEntryAsyncReservation reservation;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            reservation = exactStoredAlternateEntryReservationLocked(
                AlternateEntryAsyncReservation.KIND_PHOTO);
        }
        if (reservation == null) {
            // A raw path/guard is not authority. Preserve the file as recovery evidence but never
            // add it to a POST-able draft.
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        if (resultCode != RESULT_OK) {
            clearAlternateEntryReservation(reservation, true);
            return;
        }
        String path = data == null ? "" : data.getStringExtra("photoPath");
        if (path == null || path.isEmpty()) path = reservation.outputPath;
        File photoFile = path == null || path.isEmpty() ? null : new File(path);
        if (!reservation.outputPath.equals(path)
                || !alternateEntryPageOpen || alternateEntryConfig == null) {
            clearAlternateEntryReservation(reservation, true);
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0) {
            clearAlternateEntryReservation(reservation, true);
            alert(t("photo_save_failed"), t("photo_full_file_missing"));
            return;
        }
        if (!materializeAlternateEntryPhoto(reservation, photoFile.getAbsolutePath())) {
            alert(t("photo_save_failed"), t("alternate_entry_storage_locked_detail"));
            return;
        }
        refreshAlternateEntryUi();
    }

    private void clearPendingAlternateEntryPhoto() {
        if (pendingAlternateEntryPhotoUri != null) {
            try {
                revokeUriPermission(pendingAlternateEntryPhotoUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        pendingAlternateEntryPhotoPath = "";
        pendingAlternateEntryPhotoUri = null;
        pendingAlternateEntryPhotoGuard = "";
        pendingAlternateEntryPhotoReservation = null;
        boolean cleared = prefs.edit()
            .remove(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY)
            .remove(PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY)
            .remove(PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY)
            .commit();
        alternateEntryReservationStorageAmbiguous = !cleared;
    }

    private boolean alternateEntryEditingBlocked() {
        if (alternateEntrySubmitting) {
            toast(t("submit_running"));
            return true;
        }
        if (alternateEntryReservationStorageAmbiguous) {
            alert(t("draft_save_failed"), t("alternate_entry_storage_locked_detail"));
            return true;
        }
        if (hasPendingAlternateEntryAsyncReservationEvidence()) {
            // Keep the exact base-state hash immutable until the reserved scan/photo either
            // materializes or is cancelled. A later typed/toggle/rebind edit cannot borrow it.
            toast(t("alternate_entry_async_pending"));
            return true;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (unsafeCandidatesBlockActiveUse()) {
                toast(t("panel_syncing_short"));
                return true;
            }
        }
        UploadReplayBarrier.RestoreResult blockingUpload =
            blockingUploadReplayBarrier();
        if (blockingUpload != null) {
            showUploadReplayBarrierBlock(blockingUpload);
            return true;
        }
        AlternateSubmissionAttempt.RestoreResult blockingAttempt =
            blockingAlternateSubmissionAttempt();
        if (blockingAttempt != null) {
            showAlternateSubmissionBlock(blockingAttempt);
            return true;
        }
        return false;
    }

    private void submitAlternateEntry() {
        if (alternateEntrySubmitting || submitting
                || profileOwnedRemoteWorkerActive()) {
            toast(t("submit_running"));
            return;
        }
        if (hasPendingAlternateEntryAsyncReservationEvidence()) {
            // A prestarted scan/photo has not yet either materialized or been cancelled. Submitting
            // the base draft here would create an ambiguous payload boundary and strand its result.
            toast(t("alternate_entry_async_pending"));
            return;
        }
        if (!ensurePanelReadyForUse()) return;
        UploadReplayBarrier.RestoreResult blockingUpload =
            blockingUploadReplayBarrier();
        if (blockingUpload != null) {
            showUploadReplayBarrierBlock(blockingUpload);
            return;
        }
        AlternateSubmissionAttempt.RestoreResult blockingAttempt =
            blockingAlternateSubmissionAttempt();
        if (blockingAttempt != null) {
            showAlternateSubmissionBlock(blockingAttempt);
            return;
        }
        final String token = savedToken();
        if (token.isEmpty()) {
            alert(t("login_required"), t("login_required_detail"));
            return;
        }
        if (alternateEntrySourceProfile == null || alternateEntryConfig == null) {
            alert(t("panel_required_title"), t("alternate_entry_invalid"));
            return;
        }
        if (!alternateEntryBindingStillCurrent(
                alternateEntrySourceProfile, alternateEntryConfig)) {
            alert(t("panel_required_title"), t("alternate_entry_invalid"));
            return;
        }
        final String serial = alternateEntrySerial;
        final String serialSource = alternateEntrySerialSource;
        if (!validateAlternateEntryIdentifier(serial, serialSource)) return;
        final List<String> photos = new ArrayList<>();
        for (String path : alternateEntryPhotos) if (hasFile(path)) photos.add(path);
        final JSONObject sourceSnapshot;
        final JSONObject configSnapshot;
        final JSONArray catalogSnapshot;
        final Map<String, Boolean> toggleSnapshot = new LinkedHashMap<>(
            alternateEntryToggleStates);
        try {
            sourceSnapshot = new JSONObject(alternateEntrySourceProfile.toString());
            configSnapshot = new JSONObject(alternateEntryConfig.toString());
            catalogSnapshot = new JSONArray(alternateEntryCatalogSnapshot.toString());
        } catch (Exception error) {
            alert(t("panel_required_title"), t("alternate_entry_invalid") + "\n"
                + conciseError(error));
            return;
        }
        if (!backendAdapterFingerprint(alternateEntryAppConfigSnapshot,
                alternateEntryCatalogSettingsSnapshot).equals(
                    alternateEntryBackendFingerprint)) {
            alert(t("panel_required_title"), t("alternate_entry_invalid"));
            return;
        }
        final BackendAdapter adapterSnapshot = BackendAdapter.from(
            alternateEntryAppConfigSnapshot, alternateEntryCatalogSettingsSnapshot);
        final JSONObject targetSnapshot;
        final List<String> missingSubmitConfig;
        try {
            targetSnapshot = AlternateEntryRules.targetProfile(
                catalogSnapshot, configSnapshot);
            missingSubmitConfig = new ArrayList<>(
                RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
                    sourceSnapshot, targetSnapshot, adapterSnapshot));
        } catch (Exception invalid) {
            alert(t("panel_required_title"), t("alternate_entry_invalid") + "\n"
                + conciseError(invalid));
            return;
        }
        final Api api = api(token, alternateEntryAppConfigSnapshot,
            alternateEntryCatalogSettingsSnapshot);
        if (!api.remoteOperationsAllowed) {
            missingSubmitConfig.add("catalog.remoteOperationsAllowed");
        }
        if (!missingSubmitConfig.isEmpty()) {
            alert(t("panel_required_title"),
                t("panel_missing_config") + join(missingSubmitConfig, ", "));
            return;
        }
        final BackendAdapter.AlternateEntryDynamicOverrideConfig dynamicOverrideConfig;
        try {
            dynamicOverrideConfig = api.endpoints.alternateEntryDynamicOverrideConfig(
                configSnapshot, targetSnapshot, toggleSnapshot);
            // A no-provider entry can be fully validated now. Active providers are validated after
            // their read-only template lookups, still before the first upload.
            if (dynamicOverrideConfig.requests().isEmpty()) {
                List<String> placeholders = new ArrayList<>();
                for (int i = 0; i < photos.size(); i++) {
                    placeholders.add("https://example.invalid/alternate-photo-" + (i + 1));
                }
                AlternateEntryRules.resolve(sourceSnapshot, catalogSnapshot,
                    configSnapshot, serial, placeholders, toggleSnapshot,
                    dynamicOverrideConfig.resolve(new JSONObject()));
            }
        } catch (Exception error) {
            alert(t("panel_required_title"), t("alternate_entry_invalid") + "\n"
                + conciseError(error));
            return;
        }
        final String connectionSnapshot = alternateEntryConnectionNamespace;
        final String bindingSnapshot = alternateEntryBindingFingerprint;
        final String backendSnapshot = alternateEntryBackendFingerprint;
        final int catalogVersionSnapshot = activeCatalogVersion;
        final String panelPairSnapshot = currentPanelPairSha256();
        final String sessionFingerprintSnapshot =
            OperationBindingRules.sessionFingerprint(api.webFingerprint, api.token);
        final String entryIdSnapshot = alternateEntryConfig.optString("id", "");
        final String sourceProfileIdSnapshot =
            alternateEntrySourceProfile.optString("id", "");
        final String targetProfileIdSnapshot = targetSnapshot.optString("id", "");
        // The local source copy is part of recovery. If it cannot be flushed, do not upload and do
        // not create a POST journal: a later login/process must still recover the exact same input.
        if (!saveAlternateEntryDraft(true)) {
            alert(t("draft_save_failed"), t("alternate_entry_storage_locked_detail"));
            return;
        }
        final String sourceSnapshotSha256;
        try {
            AlternateEntryDraftState savedDraft = storedAlternateEntryDraftStrict();
            if (!savedDraft.connectionNamespace.equals(connectionSnapshot)
                    || !savedDraft.bindingFingerprint.equals(bindingSnapshot)
                    || !savedDraft.backendFingerprint.equals(backendSnapshot)
                    || !savedDraft.entryId.equals(entryIdSnapshot)
                    || !savedDraft.sourceProfileId.equals(sourceProfileIdSnapshot)
                    || !savedDraft.serial.equals(serial)
                    || !savedDraft.serialSource.equals(serialSource)
                    || !savedDraft.photos.equals(photos)
                    || !savedDraft.toggles.equals(toggleSnapshot)) {
                throw new IllegalStateException("saved alternate-entry snapshot changed");
            }
            sourceSnapshotSha256 = alternateEntryDraftFingerprint(savedDraft);
        } catch (RuntimeException error) {
            Diagnostics.append(this, "Alternate-entry source snapshot failed: "
                + conciseError(error));
            alert(t("draft_save_failed"), t("alternate_entry_storage_locked_detail"));
            return;
        }
        final UploadReplayBarrier.Identity uploadIdentity;
        final String alternateOperationId = java.util.UUID.randomUUID().toString();
        try {
            uploadIdentity = UploadReplayBarrier.Identity.alternate(
                connectionSnapshot, catalogVersionSnapshot,
                sourceProfileIdSnapshot, targetProfileIdSnapshot,
                panelPairSnapshot, bindingSnapshot, backendSnapshot,
                sessionFingerprintSnapshot, sourceSnapshotSha256,
                alternateOperationId);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Alternate upload barrier identity failed: "
                + conciseError(invalid));
            alert(t("draft_save_failed"), t("alternate_entry_storage_locked_detail"));
            return;
        }
        alternateEntrySubmitting = true;
        showSubmitLoading(1);
        setSubmitProgressMessage(formatSubmitProgressUnit(1, 1, serial));
        new Thread(() -> {
            String errorText = null;
            boolean uncertainResult = false;
            boolean uploadLockedResult = false;
            boolean sessionExpired = false;
            AlternateEntryRules.Resolution resolution = null;
            AlternateSubmissionAttempt completedAttempt = null;
            if (checkAuthBoundNow(api, token) == Api.AuthState.INVALID) {
                runOnUiThread(() -> {
                    alternateEntrySubmitting = false;
                    hideSubmitLoading();
                    handleRemoteLogout(true);
                });
                return;
            }
            if (!authorizeAlternateWorkerForUnsafeCandidate()) {
                runOnUiThread(() -> {
                    alternateEntrySubmitting = false;
                    hideSubmitLoading();
                    alert(t("cannot_submit"),
                        t("alternate_entry_storage_locked_detail"));
                });
                return;
            }
            RemoteSideEffectGate.WorkerLease alternateWorkerLease =
                RemoteSideEffectGate.tryAcquireWorker(this);
            if (alternateWorkerLease == null) {
                runOnUiThread(() -> {
                    alternateEntrySubmitting = false;
                    hideSubmitLoading();
                    alert(t("cannot_submit"),
                        t("alternate_entry_storage_locked_detail"));
                });
                return;
            }
            try {
                // Resolve every active Panel-owned live provider before the first upload. The
                // result is an exact allow-listed field map bound to the frozen adapter/catalog
                // pair. Missing, extra, ambiguous, or identity-mismatched templates fail here.
                JSONObject dynamicOverrides = resolveAlternateEntryDynamicOverrides(
                    api, dynamicOverrideConfig);
                List<String> placeholders = new ArrayList<>();
                for (int i = 0; i < photos.size(); i++) {
                    placeholders.add("https://example.invalid/alternate-photo-" + (i + 1));
                }
                AlternateEntryRules.resolve(sourceSnapshot, catalogSnapshot,
                    configSnapshot, serial, placeholders, toggleSnapshot,
                    dynamicOverrides);
                // The single barrier covers the whole bounded parallel upload group. Persist it
                // before starting the executor so a partial success, timeout, or process death can
                // never turn an ordinary second press into another upload batch.
                if (!beginUploadReplayBarrier(uploadIdentity)) {
                    throw new SubmissionJournalLockedException(
                        "Could not persist alternate upload replay barrier");
                }
                // Preserve the legacy standalone entry's upload behavior: photos are compressed,
                // uploaded with bounded parallelism, and returned in capture order. One failed
                // upload fails the whole entry before the submit request is built.
                List<String> uploadedUrls = uploadAlternateEntryImages(
                    api, photos, configSnapshot, serial);
                resolution = AlternateEntryRules.resolve(sourceSnapshot, catalogSnapshot,
                    configSnapshot, serial, uploadedUrls, toggleSnapshot, dynamicOverrides);
                // Use the exact adapter snapshot that owns this Api for both envelope construction
                // and POST. A hot Panel refresh cannot mix two contracts in one submission.
                JSONObject payload = api.endpoints.operations.submit.wrap(
                    resolution.identity.templateId, resolution.identity.warehouseId,
                    resolution.identity.sku, resolution.data);
                byte[] exactRequestBody = payload.toString().getBytes(StandardCharsets.UTF_8);
                AlternateSubmissionAttempt.Key attemptKey = AlternateSubmissionAttempt.Key.of(
                    connectionSnapshot, bindingSnapshot,
                    AlternateSubmissionAttempt.TargetIdentity.of(
                        resolution.targetProfile.optString("id", ""),
                        resolution.identity.templateId, resolution.identity.warehouseId,
                        resolution.identity.sku), serial,
                    sourceSnapshotSha256,
                    AlternateSubmissionAttempt.payloadSha256(exactRequestBody),
                    alternateOperationId);
                AlternateSubmissionAttempt attempt = AlternateSubmissionAttempt.prepare(
                    attemptKey, restoreAlternateSubmissionAttempt());
                if (!writeAlternateSubmissionAttempt(attempt)) {
                    throw new IOException("Could not persist submission intent");
                }
                // The uploaded URLs and exact request bytes are frozen above this loop. Only an
                // explicit Panel-classified rejection may enter another iteration; transport,
                // parse, gateway and unclassified responses become UNCERTAIN immediately.
                for (int postAttempt = 1;
                        postAttempt <= resolution.submissionRetry.maxAttempts;
                        postAttempt++) {
                    AlternateSubmissionAttempt posting = attempt.beginPosting(attemptKey);
                    if (!writeAlternateSubmissionAttempt(posting)) {
                        throw new IOException("Could not persist submission start");
                    }
                    final JSONObject response;
                    try {
                        response = api.postEndpointJsonExact(
                            BackendAdapter.ENDPOINT_SUBMIT_ENTRY, exactRequestBody);
                    } catch (Exception postError) {
                        if (BackendSessionErrors.isSessionInvalid(postError)) {
                            // Authentication loss does not prove whether this side-effecting POST
                            // reached the backend. Keep the exact request blocked across re-login.
                            writeAlternateSubmissionAttempt(
                                posting.markUncertain(attemptKey));
                            uncertainResult = true;
                            sessionExpired = true;
                        } else {
                            writeAlternateSubmissionAttempt(
                                posting.markUncertain(attemptKey));
                            uncertainResult = true;
                        }
                        throw postError;
                    }
                    if (api.isSuccess(response)) {
                        AlternateSubmissionAttempt completed =
                            posting.markPostAcknowledged(attemptKey);
                        if (!writeAlternateSubmissionAttempt(completed)) {
                            uncertainResult = true;
                            throw new IOException(
                                "Submission succeeded but local confirmation was not saved");
                        }
                        completedAttempt = completed;
                        break;
                    }

                    boolean retryableRejection =
                        api.endpoints.operations.submit.isRetryableResponse(
                            response, api.endpoints.response);
                    boolean explicitlyRejected = retryableRejection
                        || api.endpoints.operations.submit
                            .isMissingMaterialResponse(response, api.endpoints.response);
                    if (!explicitlyRejected) {
                        writeAlternateSubmissionAttempt(
                            posting.markUncertain(attemptKey));
                        uncertainResult = true;
                        throw new IOException(serial + " " + resolution.title + " "
                            + api.apiErrorMessage(response));
                    }

                    AlternateSubmissionAttempt rejected =
                        posting.markServerRejected(attemptKey);
                    if (!writeAlternateSubmissionAttempt(rejected)) {
                        uncertainResult = true;
                        throw new IOException(
                            "Could not persist explicit submission rejection");
                    }
                    if (retryableRejection
                            && postAttempt < resolution.submissionRetry.maxAttempts) {
                        attempt = rejected;
                        if (resolution.submissionRetry.retryDelayMs > 0L) {
                            Thread.sleep(resolution.submissionRetry.retryDelayMs);
                        }
                        continue;
                    }
                    boolean cleared = clearAlternateSubmissionAttempt();
                    uncertainResult = !cleared;
                    throw new IOException(serial + " " + resolution.title + " "
                        + api.apiErrorMessage(response));
                }
            } catch (Exception error) {
                errorText = conciseError(error);
                uploadLockedResult = hasStoredUploadReplayBarrier();
                reportSubmitFailure(null, 0, error);
            } finally {
                alternateWorkerLease.close();
            }
            final String finalError = errorText;
            final boolean finalUncertainResult = uncertainResult;
            final boolean finalUploadLockedResult = uploadLockedResult;
            final boolean finalSessionExpired = sessionExpired;
            final AlternateEntryRules.Resolution finalResolution = resolution;
            final AlternateSubmissionAttempt finalCompletedAttempt = completedAttempt;
            runOnUiThread(() -> {
                alternateEntrySubmitting = false;
                if (finalError == null) setSubmitProgress(1);
                hideSubmitLoading();
                if (finalSessionExpired) {
                    handleRemoteLogout(true);
                    return;
                }
                if (finalError != null) {
                    String title = finalUploadLockedResult
                        ? t("upload_result_uncertain_title")
                        : (finalUncertainResult
                            ? t("alternate_entry_result_uncertain_title")
                            : t("submit_failed"));
                    String detail = finalUploadLockedResult
                        ? t("upload_result_uncertain_detail") + "\n"
                        : (finalUncertainResult
                            ? t("alternate_entry_result_uncertain_detail") + "\n"
                            : "");
                    alert(title, detail + finalError);
                    return;
                }
                if (!clearUploadReplayBarrier(uploadIdentity)) {
                    alert(t("upload_result_uncertain_title"),
                        t("upload_result_uncertain_detail"));
                    return;
                }
                // Retire the exact upload barrier while the durable COMPLETED receipt and source
                // draft still exist. A crash before this point can therefore converge by the shared
                // operation id; a crash after it is handled by the ordinary completed-journal path.
                if (!finalizeCompletedAlternateSubmission(finalCompletedAttempt)) {
                    alert(t("alternate_entry_completed_cleanup_title"),
                        t("alternate_entry_completed_cleanup_detail"));
                    return;
                }
                if (finalResolution != null) {
                    for (AlternateEntryRules.TogglePolicy policy
                            : finalResolution.togglePolicies) {
                        if (!policy.retainUntilExit) {
                            alternateEntryToggleStates.put(policy.key,
                                policy.defaultValue);
                        }
                    }
                }
                if (alternateEntrySerialEdit != null) {
                    alternateEntrySerialEdit.setText("");
                    alternateEntrySerialEdit.requestFocus();
                }
                refreshAlternateEntryUi();
                autoDismissAlert(t("done"), t("alternate_entry_done") + "\n"
                    + serial, 2500);
            });
        }).start();
    }

    private JSONObject resolveAlternateEntryDynamicOverrides(
            Api api, BackendAdapter.AlternateEntryDynamicOverrideConfig config)
            throws Exception {
        JSONObject liveTemplates = new JSONObject();
        for (AlternateEntryDynamicOverrideRules.Request request : config.requests()) {
            String query = enc(config.templateDetailIdParam) + "="
                + enc(String.valueOf(request.templateId));
            JSONObject body = api.getEndpointJson(
                BackendAdapter.ENDPOINT_TEMPLATE_DETAIL, query);
            if (!api.isSuccess(body)) {
                throw new IOException(api.apiErrorMessage(body));
            }
            Object unwrapped = api.apiData(body);
            if (!(unwrapped instanceof JSONObject)) {
                throw new BackendAdapter.ConfigurationException(
                    "backendAdapter.operations.templateDetail.response.data");
            }
            liveTemplates.put(request.providerId,
                new JSONObject(((JSONObject) unwrapped).toString()));
        }
        return config.resolve(liveTemplates);
    }

    private static final int ALTERNATE_UPLOAD_MAX_EDGE = 1920;
    private static final int ALTERNATE_UPLOAD_JPEG_QUALITY = 88;

    private List<String> uploadAlternateEntryImages(Api api, List<String> paths,
                                                     JSONObject entryConfig,
                                                     String identifier) throws Exception {
        int count = paths == null ? 0 : paths.size();
        if (count == 0) return new ArrayList<>();
        if (count == 1) {
            return Collections.singletonList(uploadCompressedAlternateEntryPhoto(
                api, new File(paths.get(0)), safePhotoFileName(
                    AlternateEntryRules.formatUploadName(entryConfig, identifier, 1))));
        }
        final String[] orderedUrls = new String[count];
        java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, count));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    orderedUrls[index] = uploadCompressedAlternateEntryPhoto(
                        api, new File(paths.get(index)), safePhotoFileName(
                            AlternateEntryRules.formatUploadName(
                                entryConfig, identifier, index + 1)));
                    return null;
                }));
            }
            Exception firstFailure = null;
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (firstFailure == null) {
                        firstFailure = cause instanceof Exception
                            ? (Exception) cause : new Exception(cause);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    if (firstFailure == null) firstFailure = error;
                }
            }
            if (firstFailure != null) throw firstFailure;
        } finally {
            executor.shutdownNow();
        }
        return new ArrayList<>(java.util.Arrays.asList(orderedUrls));
    }

    private String uploadCompressedAlternateEntryPhoto(Api api, File source,
                                                        String uploadName) throws Exception {
        File prepared = prepareAlternateEntryUpload(source);
        try {
            return api.uploadImage(prepared, uploadName);
        } finally {
            if (!prepared.equals(source)) deleteFileQuietly(prepared.getAbsolutePath());
        }
    }

    private File prepareAlternateEntryUpload(File source) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0) return source;
            int rotation = alternateEntryExifRotation(source);
            boolean fits = width <= ALTERNATE_UPLOAD_MAX_EDGE
                && height <= ALTERNATE_UPLOAD_MAX_EDGE;
            if (fits && rotation == 0 && source.length() <= 900 * 1024) return source;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(width, height,
                ALTERNATE_UPLOAD_MAX_EDGE * 2, ALTERNATE_UPLOAD_MAX_EDGE * 2);
            Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (bitmap == null) return source;
            float scale = Math.min(1f, (float) ALTERNATE_UPLOAD_MAX_EDGE
                / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (scale < 1f || rotation != 0) {
                Matrix matrix = new Matrix();
                if (scale < 1f) matrix.postScale(scale, scale);
                if (rotation != 0) matrix.postRotate(rotation);
                Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (transformed != bitmap) bitmap.recycle();
                bitmap = transformed;
            }
            File directory = new File(getCacheDir(), "upload-tmp");
            if (!directory.exists() && !directory.mkdirs()) {
                bitmap.recycle();
                return source;
            }
            File output = new File(directory,
                source.getName().replace(".jpg", "") + "-alternate-upload.jpg");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG,
                    ALTERNATE_UPLOAD_JPEG_QUALITY, stream);
            }
            bitmap.recycle();
            if (output.length() <= 0 || output.length() >= source.length()) {
                deleteFileQuietly(output.getAbsolutePath());
                return source;
            }
            return output;
        } catch (Throwable error) {
            Diagnostics.append(this, "alternate upload compress fallback: " + error);
            return source;
        }
    }

    private int alternateEntryExifRotation(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            switch (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void showFormSettingsDialog() {
        if (submitting || profileOwnedRemoteWorkerActive()) {
            toast(t("submit_running"));
            return;
        }
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
        content.addView(button(t("queue_backup_delete"), v -> {
            JSONObject existing = loadQueueSnapshot();
            if (existing == null) {
                toast(t("queue_backup_none"));
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle(t("queue_backup_delete"))
                .setMessage(t("queue_backup_delete_confirm"))
                .setNegativeButton(t("cancel"), null)
                .setPositiveButton(t("queue_backup_delete"), (d, w) -> {
                    if (deleteQueueSnapshot()) {
                        queueBackupInfo.setText(queueBackupInfoText());
                        toast(t("queue_backup_deleted"));
                    } else {
                        alert(t("queue_backup_delete_failed"),
                            t("queue_backup_delete_kept"));
                    }
                })
                .show();
        }));

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
        dialog.show();
    }

    private LinearLayout workflowArtifactBox() {
        LinearLayout box = panel();
        box.addView(compactLabel(t("workflow_artifacts")));
        workflowArtifactText = text("", 13, false);
        workflowArtifactText.setTextColor(0xFF475569);
        box.addView(workflowArtifactText);
        box.addView(button(t("capture_workflow_artifact"), v -> captureNextWorkflowArtifact()));
        box.setVisibility(View.GONE);
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
        if (!panelBoundaryCleanupBlocked && updateManager != null) {
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
        if (panelUseBlocked()) return;
        if (isSampleCatalog()) return;
        if (!backendConfigured()) {
            // Unconfigured: there is no backend to fetch a captcha from. Skip silently — the settings
            // screen shows the "set the panel address" banner, so no need for an extra popup here.
            return;
        }
        appendLog(t("captcha_loading"));
        final String tokenSnapshot = savedToken();
        final OperationBindingRules.Binding operation;
        final Api apiSnapshot;
        try {
            operation = beginBoundOperation(OperationBindingRules.CAPTCHA, tokenSnapshot);
            apiSnapshot = api(tokenSnapshot);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Captcha binding unavailable: " + conciseError(invalid));
            return;
        }
        acceptedCaptchaBinding = null;
        captchaClient = "";
        new Thread(() -> {
            try {
                Api.Captcha captcha = apiSnapshot.getCaptcha(
                    phase -> requireBoundOperation(operation, tokenSnapshot, phase));
                requireBoundOperation(operation, tokenSnapshot, "captcha decode");
                Bitmap bitmap = decodeCaptcha(captcha.captcha);
                runOnUiThread(() -> {
                    if (!boundOperationMatches(operation, tokenSnapshot)) {
                        finishBoundOperation(operation);
                        return;
                    }
                    acceptedCaptchaBinding = operation;
                    captchaClient = captcha.client;
                    if (captchaView != null && bitmap != null) captchaView.setImageBitmap(bitmap);
                    if (captchaEdit != null) captchaEdit.setText("");
                    appendLog(t("captcha_ready"));
                });
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    if (!boundOperationMatches(operation, tokenSnapshot)) {
                        finishBoundOperation(operation);
                        return;
                    }
                    finishBoundOperation(operation);
                    acceptedCaptchaBinding = null;
                    captchaClient = "";
                    alert(t("captcha_failed"), exc.getMessage());
                });
            }
        }).start();
    }

    private void login() {
        maybeInstallBoundPanelSnapshotAtSafeBoundary();
        if (panelUseBlocked()) {
            notifyBackendUnconfigured();
            return;
        }
        if (localSamplePreviewEnabled()) {
            showFormPage();
            return;
        }
        if (isSampleCatalog()) {
            alert(t("sample_catalog_title"), t("sample_catalog_detail"));
            return;
        }
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
        final String tokenSnapshot = savedToken();
        final String loginRealmSnapshot = currentSessionRealmFingerprint();
        final String loginFingerprintSnapshot = webFingerprint();
        if (!SessionRealmRules.validDigest(loginRealmSnapshot)
                || loginFingerprintSnapshot.isEmpty()) {
            alert(t("login_failed"), t("panel_connect_failed"));
            return;
        }
        final OperationBindingRules.Binding captchaBinding = acceptedCaptchaBinding;
        if (!boundOperationMatches(captchaBinding, tokenSnapshot)) {
            captchaClient = "";
            acceptedCaptchaBinding = null;
            toast(t("login_missing"));
            refreshCaptcha();
            return;
        }
        final String captchaClientSnapshot = captchaClient;
        // Capture each target's v2 CAS state before the login HTTP request. A delayed result can
        // never overwrite a peer whose state changed while the request was in flight.
        final SessionBridge.PeerStateSnapshot peerStateSnapshot =
            SessionBridge.capturePeerStates(
                getApplicationContext(), loginRealmSnapshot);
        final OperationBindingRules.Binding operation;
        final Api apiSnapshot;
        try {
            operation = beginBoundOperation(OperationBindingRules.LOGIN, tokenSnapshot);
            apiSnapshot = api(tokenSnapshot);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Login binding unavailable: " + conciseError(invalid));
            return;
        }
        finishBoundOperation(captchaBinding);
        acceptedCaptchaBinding = null;
        appendLog(t("login_running"));
        new Thread(() -> {
            try {
                Api.LoginResult result = apiSnapshot.login(account, password, captcha,
                    captchaClientSnapshot,
                    phase -> requireBoundOperation(operation, tokenSnapshot, phase));
                runOnUiThread(() -> {
                    final boolean stale;
                    final boolean stored;
                    synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                        stale = !boundOperationMatches(operation, tokenSnapshot)
                            || !loginRealmSnapshot.equals(currentSessionRealmFingerprint())
                            || !loginRealmSnapshot.equals(
                                SessionRealmResolver.activeFingerprint(this))
                            || !loginFingerprintSnapshot.equals(
                                SecureTokenStore.getBoundWebFingerprint(
                                    prefs, loginRealmSnapshot));
                        stored = !stale && SecureTokenStore.putLoginForBinding(
                            prefs, result.token, password, account, result.userName,
                            loginRealmSnapshot, loginFingerprintSnapshot);
                        // The exact active-pair/realm recheck and v2 bundle commit above share this
                        // same HANDOFF_LOCK critical section with candidate promotion.
                        finishBoundOperation(operation);
                    }
                    if (stale) {
                        return;
                    }
                    if (!stored) {
                        alert(t("login_failed"), t("panel_connect_failed"));
                        refreshCaptcha();
                        return;
                    }
                    if (!result.recognizeTextUrl.isEmpty()) {
                        saveBoundRecognizeTextUrl(result.recognizeTextUrl, result.token);
                    }
                    SessionBridge.propagateLogin(getApplicationContext(), result.token,
                        loginFingerprintSnapshot, loginRealmSnapshot, peerStateSnapshot);
                    showFormPage();
                });
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    if (!boundOperationMatches(operation, tokenSnapshot)) {
                        finishBoundOperation(operation);
                        return;
                    }
                    finishBoundOperation(operation);
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
        // Session expiry must never clear an upload queue that exists only in memory. Force the
        // draft to disk synchronously before clearing it; if that rare disk commit fails, keep the
        // in-memory units intact while still clearing the invalid login.
        boolean queueSafeToClear = units.isEmpty() || saveDraft(true);
        final SessionBridge.LogoutCapability logoutCapability;
        final boolean had;
        final boolean capabilityValid;
        final boolean sessionClearDurable;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            // Capture and clear one exact disk session atomically. A peer login cannot land between
            // the proof used for DELETE propagation and this App's local CAS clear.
            logoutCapability =
                SessionBridge.captureLogoutCapability(getApplicationContext());
            had = logoutCapability.tokenPresent;
            capabilityValid = logoutCapability.valid();
            sessionClearDurable = capabilityValid && had
                && SecureTokenStore.clearTokenForBinding(
                    prefs, logoutCapability.realm, logoutCapability.fingerprint,
                    logoutCapability.sessionId, logoutCapability.stateId);
        }
        if (!capabilityValid || !had) {
            // A forged/stale signal or an already-cleared peer session has no authority to discard
            // local form state. Navigation alone is safe; all queues/targets remain untouched.
            Diagnostics.append(this, "Logout ignored without an active v2 session capability");
            showSettingsPage();
            return;
        }
        if (!sessionClearDurable) {
            Diagnostics.append(this, "Bound session logout was not durable");
            alert(t("login_failed"), t("panel_connect_failed"));
            return;
        }
        synchronized (activeOperationNonces) {
            activeOperationNonces.clear();
        }
        acceptedCaptchaBinding = null;
        captchaClient = "";
        // Only a durable logout may invalidate a readable camera/scanner result target.
        abandonReadablePendingMainFormTarget();
        if (queueSafeToClear) {
            units.clear();
        } else {
            Diagnostics.append(this, "Logout kept in-memory queue because durable draft save failed");
        }
        draftPromptShown = false; // offer the just-saved queue again after re-login
        cachedMissingMaterialCodes.clear();
        notifiedMissingMaterialCodes.clear();
        scanPrecheckMissingCounts.clear();
        missingMaterialNoticeShown = false;
        boolean alternateDraftSafeToUnload = !hasAlternateEntryPendingData()
            || saveAlternateEntryDraft(true);
        if (alternateDraftSafeToUnload) {
            clearAlternateEntrySession(false);
        } else {
            // Keep the in-memory copy if durable persistence failed, but invalidate all callbacks
            // and hide the page until the same Panel/account logs in again.
            suspendAlternateEntrySession();
        }
        unitList = null;
        if (propagate && had && sessionClearDurable) {
            SessionBridge.propagateLogout(
                getApplicationContext(), null, logoutCapability);
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
        handleRemoteLogout(firstHand, Collections.emptyList());
    }

    private void handleRemoteLogout(boolean firstHand, List<String> unconfirmedPrintSns) {
        prefs.edit().remove(SessionEventReceiver.PENDING_LOGOUT_KEY).apply();
        boolean had = !savedToken().isEmpty();
        boolean hasUnconfirmedPrints = unconfirmedPrintSns != null && !unconfirmedPrintSns.isEmpty();
        logoutToSettings(firstHand);
        if (PrintConfirmationRules.shouldShowSessionExpiredNotice(had, hasUnconfirmedPrints)) {
            String message = t("session_expired_detail");
            if (hasUnconfirmedPrints) {
                message += "\n\n" + t("inline_unconfirmed_prefix") + unconfirmedPrintSns.size()
                    + "\n" + join(unconfirmedPrintSns, ", ");
            }
            alert(t("session_expired_title"), message);
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
        if (isSampleCatalog()) {
            if (onValid != null) onValid.run();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAuthCheckMs < 30000L) {
            if (onValid != null) onValid.run();
            return;
        }
        lastAuthCheckMs = now;
        final OperationBindingRules.Binding operation;
        final Api apiSnapshot;
        try {
            operation = beginBoundOperation(OperationBindingRules.AUTH_PROBE, token);
            apiSnapshot = api(token);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Auth probe binding unavailable: "
                + conciseError(invalid));
            return;
        }
        new Thread(() -> {
            if (!backendConfigured()) {
                finishBoundOperation(operation);
                return; // background auth probe: unconfigured → nothing to check
            }
            Api.AuthState state = apiSnapshot.checkAuth(
                phase -> requireBoundOperation(operation, token, phase));
            runOnUiThread(() -> {
                if (!boundOperationMatches(operation, token)) {
                    finishBoundOperation(operation);
                    return;
                }
                finishBoundOperation(operation);
                if (state == Api.AuthState.INVALID) {
                    handleRemoteLogout(true);
                } else if (onValid != null) {
                    onValid.run();
                }
            });
        }).start();
    }

    private Api.AuthState checkAuthBoundNow(Api apiSnapshot, String tokenSnapshot) {
        if (apiSnapshot == null || tokenSnapshot == null || tokenSnapshot.trim().isEmpty()) {
            return Api.AuthState.INVALID;
        }
        final OperationBindingRules.Binding operation;
        try {
            operation = beginBoundOperation(OperationBindingRules.AUTH_PROBE,
                tokenSnapshot);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Auth probe binding failed: "
                + conciseError(invalid));
            return Api.AuthState.UNKNOWN;
        }
        try {
            Api.AuthState state = apiSnapshot.checkAuth(
                phase -> requireBoundOperation(operation, tokenSnapshot, phase));
            return boundOperationMatches(operation, tokenSnapshot)
                ? state : Api.AuthState.UNKNOWN;
        } finally {
            finishBoundOperation(operation);
        }
    }

    // Live receiver while in the foreground: reflect a peer login/logout immediately. Never
    // re-propagates (R2) — it only re-reads our own token, which a peer's logout has already cleared.
    private void registerSessionReceiver() {
        if (!BuildConfig.CROSS_APP_SESSION_ENABLED) return;
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
        ContextCompat.registerReceiver(
            this,
            sessionReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        );
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
        String name = savedUserName();
        if (name.isEmpty()) name = savedAccount();
        loginStatus.setVisibility(token.isEmpty() ? View.GONE : View.VISIBLE);
        loginStatus.setText(token.isEmpty() ? "" : name);
    }

    private void verifyAccessThenShowForm() {
        maybeInstallBoundPanelSnapshotAtSafeBoundary();
        if (panelUseBlocked()) {
            notifyBackendUnconfigured();
            return;
        }
        if (isSampleCatalog()) {
            alert(t("sample_catalog_title"), t("sample_catalog_detail"));
            showFormPage();
            return;
        }
        final String token = savedToken();
        final OperationBindingRules.Binding operation;
        final Api apiSnapshot;
        try {
            operation = beginBoundOperation(OperationBindingRules.AUTH_PROBE, token);
            apiSnapshot = api(token);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Enter-form auth binding unavailable: "
                + conciseError(invalid));
            return;
        }
        new Thread(() -> {
            // Already-logged-in user re-entering the form: verify the token is still live (it may have
            // been kicked while idle). INVALID → logout + re-login prompt; UNKNOWN/VALID → proceed.
            Api.AuthState auth = token.isEmpty()
                ? Api.AuthState.UNKNOWN
                : apiSnapshot.checkAuth(
                    phase -> requireBoundOperation(operation, token, phase));
            runOnUiThread(() -> {
                if (!boundOperationMatches(operation, token)) {
                    finishBoundOperation(operation);
                    return;
                }
                finishBoundOperation(operation);
                if (auth == Api.AuthState.INVALID) {
                    handleRemoteLogout(true);
                } else {
                    showFormPage();
                }
            });
        }).start();
    }

    private void addTypedSn() {
        String sn = normalizeIdentifier(snEdit.getText().toString(), false,
            SnScanRules.SOURCE_ENTERED);
        if (addSnValue(sn, selectedGrade(), SnScanRules.SOURCE_ENTERED)) {
            snEdit.setText("");
            resetGradeSelection();
        }
        refocusSnInput();
    }

    private void startSnScan(boolean baseSn) {
        if (blockDraftMutationForPreviousStepJournal()) return;
        if (!identifierScanEnabled(baseSn)) {
            toast(scanDisabledMessage(baseSn));
            return;
        }
        if (baseSn && firstMissingBaseSn() == null) {
            toast(noSecondaryInputNeededMessage());
            refocusBaseInput();
            return;
        }
        if (!baseSn && hasMultipleGradeChoices() && selectedGrade().isEmpty()) {
            toast(t("choose_grade"));
            refocusSnInput();
            return;
        }
        if (!scannerPolicy(baseSn).valid) {
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(baseSn));
            return;
        }
        if (!ensureCameraPermission()) return;
        UnitRecord targetUnit = baseSn ? firstMissingBaseSn() : null;
        int targetSequence = baseSn ? targetUnit.sequence : nextUnitSequence();
        String targetRole = baseSn ? PendingFormOperationRules.ROLE_SECONDARY
            : PendingFormOperationRules.ROLE_PRIMARY;
        PendingFormOperationRules.Target pending = preparePendingMainFormTarget(
            PendingFormOperationRules.SCAN, targetSequence, targetRole,
            "", "", "", baseSn ? "" : selectedGrade(), -1);
        if (pending == null) {
            alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
            return;
        }

        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.setClass(this, ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", scanPrompt(baseSn));
        intent.putExtra("IDENTIFIER_LABEL", inputLabel(baseSn));
        intent.putExtra("lang", lang);
        String autoTextMode = scannerAutoTextMode(baseSn);
        intent.putExtra("AUTO_TEXT_MODE", autoTextMode);
        intent.putExtra("REJECT_NUMERIC_ONLY", scannerRejectsNumericOnly(baseSn));
        intent.putExtra("SCANNER_POLICY_JSON", effectiveScannerConfig(baseSn).toString());
        intent.putExtra("OCR_ONLY", false);
        intent.putExtra(EXTRA_EXPECTED_SN_LENGTH, expectedIdentifierLength(baseSn));
        intent.putExtra("PREFERRED_SN_PREFIXES", preferredSnPrefixes(baseSn));
        intent.putExtra("SCAN_CAMERA_ID", 0);
        intent.putExtra("SCAN_ORIENTATION_LOCKED", true);
        intent.putExtra("BEEP_ENABLED", false);
        intent.putExtra("BARCODE_IMAGE_ENABLED", false);
        intent.putExtra("SHOW_MISSING_CAMERA_PERMISSION_DIALOG", false);
        try {
            Diagnostics.append(this, "Starting scanner role=" + (baseSn ? "secondary" : "primary")
                + " autoTextMode=" + autoTextMode + " profile=" + currentProfileId());
            startActivityForResult(intent, baseSn ? REQ_SCAN_BASE : REQ_SCAN_SN);
        } catch (ActivityNotFoundException exc) {
            clearPendingMainFormTarget();
            alert(t("scanner_missing_title"), t("scanner_missing_detail"));
        } catch (Exception exc) {
            clearPendingMainFormTarget();
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private int expectedPrimaryIdentifierLength() {
        return expectedIdentifierLength(false);
    }

    private int expectedIdentifierLength(boolean secondary) {
        return scannerPolicy(secondary).expectedLength;
    }

    private String[] preferredSnPrefixes(boolean secondary) {
        return scannerPolicy(secondary).preferredPrefixes.toArray(new String[0]);
    }

    private JSONObject scannerConfig(boolean secondary) {
        return scannerConfig(profile, secondary);
    }

    private JSONObject scannerConfig(JSONObject sourceProfile, boolean secondary) {
        JSONObject plugin = snPlugin(sourceProfile, secondary ? "secondary" : "primary");
        if (plugin != null && plugin.has("scanner")) {
            JSONObject configured = plugin.optJSONObject("scanner");
            return configured == null
                ? invalidScannerConfig() : configured;
        }
        if (!secondary && sourceProfile != null && sourceProfile.has("scanner")) {
            JSONObject configured = sourceProfile.optJSONObject("scanner");
            return configured == null
                ? invalidScannerConfig() : configured;
        }
        return new JSONObject();
    }

    private JSONObject effectiveScannerConfig(boolean secondary) {
        return effectiveScannerConfig(profile, secondary);
    }

    private JSONObject effectiveScannerConfig(JSONObject sourceProfile, boolean secondary) {
        if (scannerPolicyRequiredButMissing(sourceProfile, secondary)) {
            return invalidScannerConfig();
        }
        JSONObject configured;
        try {
            configured = new JSONObject(scannerConfig(sourceProfile, secondary).toString());
        } catch (Exception exc) {
            configured = invalidScannerConfig();
        }
        if (!secondary && !configured.has("expectedLength") && sourceProfile != null
                && sourceProfile.has("expectedSnLength")) {
            try {
                configured.put("expectedLength", sourceProfile.opt("expectedSnLength"));
            } catch (Exception exc) {
                return invalidScannerConfig();
            }
        }
        return configured;
    }

    private JSONObject invalidScannerConfig() {
        JSONObject invalid = new JSONObject();
        try {
            invalid.put("expectedLength", "invalid");
        } catch (Exception ignored) {
            // A new in-memory object and a string value cannot fail on supported Android JSON.
        }
        return invalid;
    }

    private boolean scannerPolicyRequiredButMissing(JSONObject sourceProfile,
                                                     boolean secondary) {
        JSONObject plugin = snPlugin(sourceProfile, secondary ? "secondary" : "primary");
        if (plugin == null || !plugin.has("scan") || !SnScanRules.cameraScanEnabled(plugin)) return false;
        if (plugin.has("scanner")) {
            JSONObject configured = plugin.optJSONObject("scanner");
            return configured == null || configured.length() == 0;
        }
        if (secondary || sourceProfile == null || !sourceProfile.has("scanner")) return true;
        JSONObject fallback = sourceProfile.optJSONObject("scanner");
        return fallback == null || fallback.length() == 0;
    }

    private SnScanRules.Policy scannerPolicy(boolean secondary) {
        return SnScanRules.Policy.from(effectiveScannerConfig(secondary));
    }

    /** The independent entry shares all primary scanner behavior with its source profile except
     * the exact-length source scope. That one production distinction is owned by the entry itself
     * so the main form can keep its own typed-entry behavior without an App-side exception. */
    private JSONObject effectiveAlternateEntryScannerConfig() {
        return effectiveAlternateEntryScannerConfig(
            alternateEntrySourceProfile, alternateEntryConfig);
    }

    private JSONObject effectiveAlternateEntryScannerConfig(JSONObject sourceProfile,
                                                             JSONObject entryConfig) {
        if (sourceProfile == null || entryConfig == null) return invalidScannerConfig();
        try {
            return AlternateEntryRules.applyScannerScopeOverrides(
                effectiveScannerConfig(sourceProfile, false), entryConfig);
        } catch (RuntimeException invalid) {
            return invalidScannerConfig();
        }
    }

    private SnScanRules.Policy alternateEntryScannerPolicy() {
        return SnScanRules.Policy.from(effectiveAlternateEntryScannerConfig());
    }

    private SnScanRules.Policy alternateEntryScannerPolicy(JSONObject sourceProfile,
                                                           JSONObject entryConfig) {
        return SnScanRules.Policy.from(
            effectiveAlternateEntryScannerConfig(sourceProfile, entryConfig));
    }

    private String normalizeAlternateEntryIdentifier(String raw, String source) {
        if (!isIdentifierValueSource(source)) return "";
        return alternateEntryScannerPolicy().normalizeForSource(raw, source);
    }

    private boolean validateAlternateEntryIdentifier(String value, String source) {
        return validateIdentifierValue(value, false, source, alternateEntryScannerPolicy());
    }

    private boolean identifierScanEnabled(boolean secondary) {
        JSONObject plugin = snPlugin(secondary ? "secondary" : "primary");
        // Legacy profiles had scanner buttons before snPlugins exposed the switch. Preserve that
        // default, but once the Panel writes scan:false the App must not provide any camera entry.
        return SnScanRules.cameraScanEnabled(plugin);
    }

    private String scannerAutoTextMode(boolean secondary) {
        return scannerPolicy(secondary).autoTextMode;
    }

    private boolean scannerRejectsNumericOnly(boolean secondary) {
        return scannerPolicy(secondary).rejectNumericOnly;
    }

    private String scanPrompt(boolean secondary) {
        JSONObject scanner = scannerConfig(secondary);
        String prompt = localized(scanner, "prompt", "promptI18n");
        if (!prompt.isEmpty()) return prompt;
        if ("en".equals(lang)) return "Scan " + inputLabel(secondary);
        if ("es".equals(lang)) return "Escanear " + inputLabel(secondary);
        return "\u626b\u63cf " + inputLabel(secondary);
    }

    private String normalizeIdentifier(String raw, boolean secondary, String source) {
        if (!isIdentifierValueSource(source)) return "";
        return scannerPolicy(secondary).normalizeForSource(raw, source);
    }

    private boolean validateIdentifierValue(String value, boolean secondary, String source) {
        return validateIdentifierValue(value, secondary, source, scannerPolicy(secondary));
    }

    private boolean validateIdentifierValue(String value, boolean secondary, String source,
                                            SnScanRules.Policy policy) {
        SnScanRules.Rejection rejection = policy.rejectionForSource(value, source);
        if (rejection == SnScanRules.Rejection.NONE) return true;
        if (rejection == SnScanRules.Rejection.EMPTY) {
            toast(requiredInputMessage(secondary));
            return false;
        }
        if (rejection == SnScanRules.Rejection.INVALID_POLICY) {
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(secondary));
            return false;
        }
        if (rejection == SnScanRules.Rejection.NUMERIC_ONLY) {
            alert(t("scan_not_sn_title"), t("scan_not_sn_detail"));
            return false;
        }
        if (rejection == SnScanRules.Rejection.WRONG_LENGTH) {
            List<Integer> required = policy.requiredLengthsForSource(source);
            if (!required.isEmpty()) {
                toastLong(identifierLengthMessage(secondary, required, value.length()));
                return false;
            }
        }
        toastLong(identifierPolicyRejectedMessage(secondary, policy));
        return false;
    }

    private static boolean isIdentifierValueSource(String source) {
        return SnScanRules.SOURCE_OCR.equals(source)
            || SnScanRules.SOURCE_BARCODE.equals(source)
            || SnScanRules.SOURCE_ENTERED.equals(source);
    }

    private String scanResultSource(Intent data) {
        String format = data == null ? "" : data.getStringExtra("SCAN_RESULT_FORMAT");
        if ("MLKIT_BARCODE".equals(format)) return SnScanRules.SOURCE_BARCODE;
        if ("MLKIT_TEXT".equals(format)) return SnScanRules.SOURCE_OCR;
        return "";
    }

    private void rejectUnknownScanResultFormat(String format, boolean secondary) {
        Diagnostics.append(this, "Rejected scanner result with unknown format role="
            + (secondary ? "secondary" : "primary") + " format=" + emptyDash(format));
        alert(t("scan_result_invalid_title"), t("scan_result_invalid_detail"));
    }

    private String primaryIdentifierLengthMessage(int expected, int actual) {
        return identifierLengthMessage(false, expected, actual);
    }

    private String identifierLengthMessage(boolean secondary, int expected, int actual) {
        return identifierLengthMessage(
            secondary, Collections.singletonList(expected), actual);
    }

    private String identifierLengthMessage(boolean secondary, List<Integer> expected,
                                           int actual) {
        String label = inputLabel(secondary);
        String lengths = localizedIdentifierLengths(expected);
        if ("en".equals(lang)) return label + " must be " + lengths
            + " characters. Current: " + actual + ".";
        if ("es".equals(lang)) return label + " debe tener " + lengths
            + " caracteres. Actual: " + actual + ".";
        return label + " \u5e94\u4e3a " + lengths + " \u4f4d\uff0c\u5f53\u524d "
            + actual + " \u4f4d\u3002";
    }

    private String identifierPolicyRejectedMessage(boolean secondary, SnScanRules.Policy policy) {
        String label = inputLabel(secondary);
        String bounds = policy.minLengthConfigured || policy.maxLengthConfigured
            ? " (" + policy.minLength + "-" + policy.maxLength + ")" : "";
        if ("en".equals(lang)) return label + " does not match the Panel scanner policy" + bounds + ".";
        if ("es".equals(lang)) return label + " no cumple la pol\u00edtica de escaneo del Panel" + bounds + ".";
        return label + " \u4e0d\u7b26\u5408 Panel \u914d\u7f6e\u7684\u626b\u7801\u89c4\u5219" + bounds + "\u3002";
    }

    private String scannerPolicyInvalidMessage(boolean secondary) {
        String label = inputLabel(secondary);
        if ("en".equals(lang)) return "The scanner policy for " + label + " is invalid. Correct it in Panel before entry.";
        if ("es".equals(lang)) return "La pol\u00edtica de escaneo de " + label + " no es v\u00e1lida. Corr\u00edjala en Panel antes de capturar datos.";
        return label + " \u7684\u626b\u7801\u7b56\u7565\u65e0\u6548\uff0c\u8bf7\u5148\u5728 Panel \u4fee\u6b63\u540e\u518d\u5f55\u5165\u3002";
    }

    private String scanDisabledMessage(boolean secondary) {
        String label = inputLabel(secondary);
        if ("en".equals(lang)) return "Camera scanning is disabled for " + label + " by Panel.";
        if ("es".equals(lang)) return "Panel desactiv\u00f3 el escaneo por c\u00e1mara para " + label + ".";
        return "Panel \u5df2\u5173\u95ed " + label + " \u7684\u76f8\u673a\u626b\u7801\u3002";
    }

    private String identifierExpectedOnlyMessage(boolean secondary, int expected) {
        return identifierExpectedOnlyMessage(
            secondary, Collections.singletonList(expected));
    }

    private String identifierExpectedOnlyMessage(boolean secondary,
                                                 List<Integer> expected) {
        String label = inputLabel(secondary);
        String lengths = localizedIdentifierLengths(expected);
        if ("en".equals(lang)) return "No " + lengths + "-character " + label
            + " was found.";
        if ("es".equals(lang)) return "No se encontr\u00f3 " + label + " de "
            + lengths + " caracteres.";
        return "\u672a\u8bc6\u522b\u5230 " + lengths + " \u4f4d " + label + "\u3002";
    }

    private String localizedIdentifierLengths(List<Integer> lengths) {
        return SnScanRules.formatLengths(lengths,
            "en".equals(lang) ? "or" : ("es".equals(lang) ? "o" : "\u6216"));
    }

    private String currentProfileId() {
        return profile == null ? "" : profile.optString("id", "");
    }

    private void startUnitSnRescan(UnitRecord unit, boolean baseSn) {
        if (unit == null) return;
        if (blockDraftMutationForPreviousStepJournal()) return;
        if (!identifierScanEnabled(baseSn)) {
            toast(scanDisabledMessage(baseSn));
            return;
        }
        if (!scannerPolicy(baseSn).valid) {
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(baseSn));
            return;
        }
        if (!ensureCameraPermission()) return;
        String targetRole = baseSn ? PendingFormOperationRules.ROLE_SECONDARY
            : PendingFormOperationRules.ROLE_PRIMARY;
        if (preparePendingMainFormTarget(PendingFormOperationRules.RESCAN,
                unit.sequence, targetRole, "", "", "", "", -1) == null) {
            alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
            return;
        }
        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.setClass(this, ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", scanPrompt(baseSn));
        intent.putExtra("IDENTIFIER_LABEL", inputLabel(baseSn));
        intent.putExtra("lang", lang);
        intent.putExtra("AUTO_TEXT_MODE", scannerAutoTextMode(baseSn));
        intent.putExtra("REJECT_NUMERIC_ONLY", scannerRejectsNumericOnly(baseSn));
        intent.putExtra("SCANNER_POLICY_JSON", effectiveScannerConfig(baseSn).toString());
        intent.putExtra("OCR_ONLY", false);
        intent.putExtra(EXTRA_EXPECTED_SN_LENGTH, expectedIdentifierLength(baseSn));
        intent.putExtra("PREFERRED_SN_PREFIXES", preferredSnPrefixes(baseSn));
        intent.putExtra("SCAN_CAMERA_ID", 0);
        intent.putExtra("SCAN_ORIENTATION_LOCKED", true);
        intent.putExtra("BEEP_ENABLED", false);
        intent.putExtra("BARCODE_IMAGE_ENABLED", false);
        intent.putExtra("SHOW_MISSING_CAMERA_PERMISSION_DIALOG", false);
        try {
            String oldValue = baseSn ? (unit.baseSn == null ? "" : unit.baseSn) : (unit.sn == null ? "" : unit.sn);
            Diagnostics.append(this, "Starting scanner for rescan role=" + (baseSn ? "secondary" : "primary")
                + " sequence=" + unit.sequence + " oldLength=" + oldValue.length());
            startActivityForResult(intent, baseSn ? REQ_RESCAN_UNIT_BASE_SN : REQ_RESCAN_UNIT_SN);
        } catch (Exception exc) {
            clearPendingRescan();
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private void startSnOcr(boolean baseSn) {
        if (blockDraftMutationForPreviousStepJournal()) return;
        if (!identifierScanEnabled(baseSn)) {
            toast(scanDisabledMessage(baseSn));
            return;
        }
        if (!scannerPolicy(baseSn).valid) {
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(baseSn));
            return;
        }
        if (baseSn && firstMissingBaseSn() == null) {
            toast(noSecondaryInputNeededMessage());
            refocusBaseInput();
            return;
        }
        if (!baseSn && hasMultipleGradeChoices() && selectedGrade().isEmpty()) {
            toast(t("choose_grade"));
            refocusSnInput();
            return;
        }
        if (!ensureCameraPermission()) return;
        UnitRecord targetUnit = baseSn ? firstMissingBaseSn() : null;
        int targetSequence = baseSn ? targetUnit.sequence : nextUnitSequence();
        String targetRole = baseSn ? PendingFormOperationRules.ROLE_SECONDARY
            : PendingFormOperationRules.ROLE_PRIMARY;
        if (preparePendingMainFormTarget(PendingFormOperationRules.SCAN,
                targetSequence, targetRole, "", "", "",
                baseSn ? "" : selectedGrade(), -1) == null) {
            alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
            return;
        }
        Intent intent = new Intent(this, ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", scanPrompt(baseSn));
        intent.putExtra("IDENTIFIER_LABEL", inputLabel(baseSn));
        intent.putExtra("lang", lang);
        intent.putExtra("AUTO_TEXT_MODE", "always");
        intent.putExtra("REJECT_NUMERIC_ONLY", scannerRejectsNumericOnly(baseSn));
        JSONObject ocrPolicy = effectiveScannerConfig(baseSn);
        try {
            ocrPolicy.put("autoTextMode", "always");
        } catch (Exception exc) {
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(baseSn));
            return;
        }
        intent.putExtra("SCANNER_POLICY_JSON", ocrPolicy.toString());
        intent.putExtra("OCR_ONLY", true);
        intent.putExtra(EXTRA_EXPECTED_SN_LENGTH, expectedIdentifierLength(baseSn));
        intent.putExtra("PREFERRED_SN_PREFIXES", preferredSnPrefixes(baseSn));
        try {
            Diagnostics.append(this, "Starting local text scanner role=" + (baseSn ? "secondary" : "primary"));
            startActivityForResult(intent, baseSn ? REQ_SCAN_BASE : REQ_SCAN_SN);
        } catch (Exception exc) {
            clearPendingMainFormTarget();
            alert(t("camera_open_failed"), exc.getMessage());
        }
    }

    private void ensureOcrUrlThenStartCamera(boolean baseSn) {
        if (blockDraftMutationForPreviousStepJournal()) return;
        if (!identifierScanEnabled(baseSn)) {
            toast(scanDisabledMessage(baseSn));
            return;
        }
        if (!scannerPolicy(baseSn).valid) {
            alert(t("panel_required_title"), scannerPolicyInvalidMessage(baseSn));
            return;
        }
        if (!ensureOcrConfigured()) return;
        String token = savedToken();
        String recognizeTextUrl = boundRecognizeTextUrl(token);
        if (!recognizeTextUrl.isEmpty()) {
            startCameraForOcr(baseSn);
            return;
        }
        if (token.isEmpty()) {
            alert(t("login_required"), t("login_required_detail"));
            return;
        }
        fetchAndBindOcrUrl(token, baseSn, false, () -> startCameraForOcr(baseSn), null);
    }

    private void startCameraForOcr(boolean baseSn) {
        if (!identifierScanEnabled(baseSn)) {
            toast(scanDisabledMessage(baseSn));
            return;
        }
        try {
            UnitRecord targetUnit = baseSn ? firstMissingBaseSn() : null;
            if (baseSn && targetUnit == null) {
                toast(noSecondaryInputNeededMessage());
                return;
            }
            if (!baseSn && hasMultipleGradeChoices() && selectedGrade().isEmpty()) {
                toast(t("choose_grade"));
                return;
            }
            File outputFile = createOcrPhotoOutputFile(baseSn);
            pendingOcrPhotoPath = outputFile.getAbsolutePath();
            pendingOcrPhotoUri = SimplePhotoProvider.uriForFile(this, outputFile);
            int targetSequence = baseSn ? targetUnit.sequence : nextUnitSequence();
            String targetRole = baseSn ? PendingFormOperationRules.ROLE_SECONDARY
                : PendingFormOperationRules.ROLE_PRIMARY;
            if (preparePendingMainFormTarget(PendingFormOperationRules.OCR_PHOTO,
                    targetSequence, targetRole, "", "", pendingOcrPhotoPath,
                    baseSn ? "" : selectedGrade(), -1) == null) {
                pendingOcrPhotoUri = null;
                pendingOcrPhotoPath = "";
                alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
                return;
            }
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
                Diagnostics.append(this, "Starting system camera for OCR role=" + (baseSn ? "secondary" : "primary"));
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
        intent.putExtra("label", inputLabel(baseSn));
        intent.putExtra("lang", lang);
        Diagnostics.append(this, "Starting internal camera fallback for OCR role=" + (baseSn ? "secondary" : "primary"));
        startActivityForResult(intent, baseSn ? REQ_OCR_BASE : REQ_OCR_SN);
    }

    private void handleScanResult(int requestCode, int resultCode, Intent data) {
        boolean baseSn = requestCode == REQ_SCAN_BASE;
        String role = baseSn ? PendingFormOperationRules.ROLE_SECONDARY
            : PendingFormOperationRules.ROLE_PRIMARY;
        PendingFormOperationRules.Target target = pendingMainFormTargetForResult(
            PendingFormOperationRules.SCAN, role);
        if (target == null || !ensureFormStateForPendingTarget(target, baseSn)
                || (!baseSn && nextUnitSequence() != target.unitSequence)) {
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            clearPendingMainFormTarget();
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        String ocrPhotoPath = data.getStringExtra("OCR_PHOTO_PATH");
        if (ocrPhotoPath != null && !ocrPhotoPath.trim().isEmpty()) {
            // ScannerActivity has no predeclared output path. A returned file therefore cannot be
            // proven to be this exact durable target and is never sent to remote OCR.
            deleteFileQuietly(ocrPhotoPath);
            clearPendingMainFormTarget();
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        String format = data.getStringExtra("SCAN_RESULT_FORMAT");
        String source = scanResultSource(data);
        if (source.isEmpty()) {
            clearPendingMainFormTarget();
            rejectUnknownScanResultFormat(format, baseSn);
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        // ScannerActivity already normalized this exact value for its reported source.
        String value = data.getStringExtra("SCAN_RESULT");
        if (value == null) value = "";
        Diagnostics.append(this, "Scan result role=" + (baseSn ? "secondary" : "primary") + " format=" + emptyDash(format) + " length=" + value.length());
        if (value.isEmpty()) {
            clearPendingMainFormTarget();
            toast(requiredInputMessage(baseSn));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        if (!validateIdentifierValue(value, baseSn, source)) {
            clearPendingMainFormTarget();
            Diagnostics.append(this, "Rejected scan by configured policy role="
                + (baseSn ? "secondary" : "primary") + " format=" + emptyDash(format));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        if (baseSn) {
            showScannedSnPreview(value, secondaryInputLabel());
            if (baseSnEdit == null) showFormPage(false);
            if (baseSnEdit != null) baseSnEdit.setText(value);
            applySecondaryScanTarget(target, value, source);
            refocusBaseInput();
            return;
        }
        showScannedSnPreview(value, primaryInputLabel());
        if (snEdit == null) showFormPage(false);
        if (snEdit != null) snEdit.setText(value);
        for (UnitRecord item : units) {
            if (value.equals(item.sn)) {
                clearPendingMainFormTarget();
                toast(t("duplicate_sn") + value);
                refocusSnInput();
                return;
            }
        }
        UnitRecord added = addSnRecord(value, target.grade, source);
        if (added != null) {
            if (added.sequence != target.unitSequence) {
                Diagnostics.append(this, "Scanner target sequence changed before save");
                alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
                return;
            }
            clearPendingMainFormTarget();
            if (snEdit != null) snEdit.setText("");
            resetGradeSelection();
            checkScannedUnitPreviousSteps(added);
        }
        refocusSnInput();
    }

    private void handleUnitSnRescanResult(int resultCode, Intent data, boolean baseSn) {
        String role = baseSn ? PendingFormOperationRules.ROLE_SECONDARY
            : PendingFormOperationRules.ROLE_PRIMARY;
        PendingFormOperationRules.Target target = pendingMainFormTargetForResult(
            PendingFormOperationRules.RESCAN, role);
        if (target == null || !ensureFormStateForPendingTarget(target, true)) {
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        int sequence = target.unitSequence;
        if (resultCode != RESULT_OK || data == null) {
            clearPendingMainFormTarget();
            refreshFormUi();
            return;
        }
        String format = data.getStringExtra("SCAN_RESULT_FORMAT");
        String source = scanResultSource(data);
        if (source.isEmpty()) {
            clearPendingMainFormTarget();
            rejectUnknownScanResultFormat(format, baseSn);
            refreshFormUi();
            return;
        }
        // ScannerActivity already normalized this exact value for its reported source.
        String value = data.getStringExtra("SCAN_RESULT");
        if (value == null) value = "";
        Diagnostics.append(this, "Unit identifier rescan role=" + (baseSn ? "secondary" : "primary") + " sequence=" + sequence + " format=" + emptyDash(format) + " length=" + value.length());
        if (value.isEmpty()) {
            clearPendingMainFormTarget();
            toast(requiredInputMessage(baseSn));
            return;
        }
        if (!validateIdentifierValue(value, baseSn, source)) {
            clearPendingMainFormTarget();
            refreshFormUi();
            return;
        }
        showScannedSnPreview(value, inputLabel(baseSn));
        UnitRecord unit = unitBySequence(sequence);
        if (unit == null) {
            toast(t("photo_target_missing"));
            refreshFormUi();
            return;
        }
        if (!baseSn) {
            for (UnitRecord item : units) {
                if (item != unit && value.equals(item.sn)) {
                    clearPendingMainFormTarget();
                    toast(t("duplicate_sn") + value);
                    showUnitDetails(unit);
                    return;
                }
            }
        }
        String oldValue = baseSn ? (unit.baseSn == null ? "" : unit.baseSn) : (unit.sn == null ? "" : unit.sn);
        String oldSource = baseSn ? unit.baseSnSource : unit.snSource;
        String oldPrecheck = unit.precheckStatus;
        String oldStatus = unit.status;
        if (baseSn) {
            unit.baseSn = value;
            unit.baseSnSource = source;
        } else {
            unit.sn = value;
            unit.snSource = source;
        }
        if (!oldValue.equals(value)) {
            unit.precheckStatus = "unchecked";
            unit.status = "pending";
        }
        if (!saveDraft(true)) {
            if (baseSn) {
                unit.baseSn = oldValue;
                unit.baseSnSource = oldSource;
            } else {
                unit.sn = oldValue;
                unit.snSource = oldSource;
            }
            unit.precheckStatus = oldPrecheck;
            unit.status = oldStatus;
            refreshFormUi();
            alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
            return;
        }
        clearPendingMainFormTarget();
        refreshFormUi();
        toast(t("rescan_saved"));
        showUnitDetails(unit);
    }

    private void applySecondaryScanTarget(PendingFormOperationRules.Target target,
                                          String value, String source) {
        UnitRecord unit = target == null ? null : unitBySequence(target.unitSequence);
        if (unit == null) {
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        String oldValue = unit.baseSn == null ? "" : unit.baseSn;
        String oldSource = unit.baseSnSource;
        unit.baseSn = value;
        unit.baseSnSource = source;
        if (!saveDraft(true)) {
            unit.baseSn = oldValue;
            unit.baseSnSource = oldSource;
            refreshFormUi();
            alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
            return;
        }
        clearPendingMainFormTarget();
        if (baseSnEdit != null) baseSnEdit.setText("");
        refreshFormUi();
    }

    private void handleSnEnter() {
        addTypedSn();
        refocusSnInput();
    }

    private boolean addSnValue(String sn, String grade, String source) {
        return addSnRecord(sn, grade, source) != null;
    }

    private UnitRecord addSnRecord(String sn, String grade, String source) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            // Candidate staging owns this same lock. Once a blocking candidate exists, an open
            // page may finish only its captured records and can never append another batch unit.
            if (panelConnectionSyncBlocked()) {
                notifyBackendUnconfigured();
                return null;
            }
            return addSnRecordAtReadyBoundary(sn, grade, source);
        }
    }

    private UnitRecord addSnRecordAtReadyBoundary(String sn, String grade, String source) {
        if (blockDraftMutationForPreviousStepJournal()) return null;
        if (sn.isEmpty()) {
            toast(requiredInputMessage(false));
            return null;
        }
        if (hasMultipleGradeChoices() && !hasGrade(grade)) {
            toast(t("choose_grade"));
            return null;
        }
        if (!validateIdentifierValue(sn, false, source)) return null;
        for (UnitRecord unit : units) {
            if (unit.sn.equals(sn)) {
                toast(t("duplicate_sn") + sn);
                return null;
            }
        }
        UnitRecord unit = new UnitRecord(nextUnitSequence(), sn, grade);
        unit.snSource = source;
        // Snapshot additional configured identifiers onto this record.
        for (Map.Entry<String, EditText> e : pluginSnEdits.entrySet()) {
            String val = normalize(e.getValue().getText().toString());
            if (!val.isEmpty()) unit.pluginSns.put(e.getKey(), val);
        }
        units.add(unit);
        // A scanned record may immediately launch a previous-step precheck. Make its exact Panel
        // binding durable first; on any storage/binding failure roll back the in-memory add so no
        // remote worker can observe an unprotected record.
        if (!saveDraft(true)) {
            units.remove(unit);
            refreshFormUi();
            alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
            return null;
        }
        refreshFormUi();
        return unit;
    }

    private void addBaseSn() {
        addBaseSnValue(normalizeIdentifier(baseSnEdit.getText().toString(), true,
            SnScanRules.SOURCE_ENTERED), SnScanRules.SOURCE_ENTERED);
        refocusBaseInput();
    }

    private void handleBaseEnter() {
        addBaseSn();
        refocusBaseInput();
    }

    private void addBaseSnValue(String baseSn, String source) {
        if (blockDraftMutationForPreviousStepJournal()) return;
        UnitRecord unit = firstMissingBaseSn();
        if (unit == null) {
            toast(noSecondaryInputNeededMessage());
            return;
        }
        if (baseSn.isEmpty()) {
            toast(requiredInputMessage(true));
            return;
        }
        if (!validateIdentifierValue(baseSn, true, source)) return;
        unit.baseSn = baseSn;
        unit.baseSnSource = source;
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

    private void captureNextPhoto() {
        if (blockDraftMutationForPreviousStepJournal()) return;
        if (!ensurePanelReadyForUse()) return;
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
        if (blockDraftMutationForPreviousStepJournal()) return;
        if (!ensurePanelReadyForUse()) return;
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
        if (blockDraftMutationForPreviousStepJournal()) return;
        if (!ensurePanelReadyForUse()) return;
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
            UnitRecord targetUnit = pendingPhotoIndex >= 0
                && pendingPhotoIndex < units.size() ? units.get(pendingPhotoIndex) : null;
            if (targetUnit == null || preparePendingMainFormTarget(
                    PendingFormOperationRules.PHOTO, targetUnit.sequence,
                    PendingFormOperationRules.ROLE_PHOTO, pendingPhotoSide,
                    pendingPhotoField, pendingOutputPhotoPath, "", pendingPhotoIndex) == null) {
                pendingOutputPhotoUri = null;
                pendingOutputPhotoPath = "";
                alert(t("draft_save_failed"), t("draft_binding_locked_detail"));
                return;
            }
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
        if (requestCode == REQ_SCAN_ALTERNATE_ENTRY_SN) {
            handleAlternateEntryScanResult(resultCode, data);
            return;
        }
        if (requestCode == REQ_CAPTURE_ALTERNATE_ENTRY_PHOTO) {
            handleAlternateEntryPhotoResult(resultCode, data);
            return;
        }
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
        if (requestCode != REQ_CAPTURE_PHOTO) {
            return;
        }
        PendingFormOperationRules.Target target = pendingMainFormTargetForResult(
            PendingFormOperationRules.PHOTO, PendingFormOperationRules.ROLE_PHOTO);
        if (target == null || !ensureFormStateForPendingTarget(target, true)) {
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        UnitRecord unit = unitBySequence(target.unitSequence);
        if (unit == null) {
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        applyPendingMainFormTargetToLegacyMemory(target, units.indexOf(unit));
        Diagnostics.append(this, "Photo result received resultCode=" + resultCode
            + " sequence=" + target.unitSequence + " side=" + target.side);
        if (resultCode != RESULT_OK) {
            clearPendingPhotoOutput();
            return;
        }
        String path = data == null ? "" : data.getStringExtra("photoPath");
        if (path == null || path.isEmpty()) path = target.outputPath;
        if (!target.outputPath.equals(path)) {
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        File photoFile = path == null || path.isEmpty() ? null : new File(path);
        if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0) {
            alert(t("photo_save_failed"), t("photo_full_file_missing"));
            return;
        }
        String oldFront = unit.frontPhoto;
        String oldBack = unit.backPhoto;
        List<String> oldSupplemental = new ArrayList<>(unit.supplementalPhotos);
        List<String> oldSlot = unit.slotPhotos.containsKey(target.field)
            ? new ArrayList<>(unit.slotPhotos.get(target.field)) : null;
        String oldArtifact = unit.workflowArtifacts.get(target.field);
        String oldLegacyArtifact = unit.legacyWorkflowArtifactPath;
        try {
            int[] slotStepBeforeSave = "slot".equals(target.side) ? nextSlotStep() : null;
            if ("front".equals(target.side)) {
                unit.frontPhoto = path;
            } else if ("back".equals(target.side)) {
                unit.backPhoto = path;
            } else if ("supplemental".equals(target.side)) {
                unit.supplementalPhotos.add(path);
            } else if ("slot".equals(target.side)) {
                List<String> photos = unit.slotPhotos.get(target.field);
                if (photos == null) {
                    photos = new ArrayList<>();
                    unit.slotPhotos.put(target.field, photos);
                }
                if (!photos.contains(path)) photos.add(path);
            } else if ("artifact".equals(target.side)) {
                unit.workflowArtifacts.put(target.field, path);
                unit.legacyWorkflowArtifactPath = LegacyDraftArtifactRules.afterArtifactChange(
                    profileWorkflow(), target.field, path,
                    unit.legacyWorkflowArtifactPath);
            }
            if (!saveDraft(true)) {
                unit.frontPhoto = oldFront;
                unit.backPhoto = oldBack;
                unit.supplementalPhotos.clear();
                unit.supplementalPhotos.addAll(oldSupplemental);
                if (oldSlot == null) unit.slotPhotos.remove(target.field);
                else unit.slotPhotos.put(target.field, oldSlot);
                if (oldArtifact == null) unit.workflowArtifacts.remove(target.field);
                else unit.workflowArtifacts.put(target.field, oldArtifact);
                unit.legacyWorkflowArtifactPath = oldLegacyArtifact;
                refreshFormUi();
                alert(t("photo_save_failed"), t("draft_binding_locked_detail"));
                return;
            }
            if ("artifact".equals(target.side) && oldArtifact != null
                    && !oldArtifact.equals(path)) {
                deleteFileQuietly(oldArtifact);
            }
            clearPendingMainFormTarget();
            Diagnostics.append(this, "Photo saved for SN=" + unit.sn + " side="
                + target.side + " path=" + path + " bytes=" + photoFile.length());
            if ("slot".equals(target.side)) {
                int[] slotStepAfterSave = nextSlotStep();
                boolean nextSlotAlreadyStarted = slotStepAfterSave != null
                    && slotHasAnyPhotos(slotStepAfterSave[1]);
                if (PhotoTransitionRules.shouldShowSlotTransitionNotice(
                    photoOrder, slotStepBeforeSave, slotStepAfterSave, nextSlotAlreadyStarted)) {
                    alert(t("photo_notice"), photoSlotTransitionNotice(
                        slotTitleForStep(slotStepBeforeSave),
                        slotTitleForStep(slotStepAfterSave)));
                }
            } else if (!isSlotMode() && !"supplemental".equals(target.side)
                    && !"artifact".equals(target.side)) {
                PhotoStep next = nextPhotoStep();
                if (next != null && next.frontsCompleteTransition) {
                    alert(t("photo_notice"), photoSlotTransitionNotice(
                        legacyPhotoSlotTitle(0, "front"),
                        legacyPhotoSlotTitle(1, "back")));
                }
            }
            refreshFormUi();
        } catch (Exception exc) {
            unit.frontPhoto = oldFront;
            unit.backPhoto = oldBack;
            unit.supplementalPhotos.clear();
            unit.supplementalPhotos.addAll(oldSupplemental);
            if (oldSlot == null) unit.slotPhotos.remove(target.field);
            else unit.slotPhotos.put(target.field, oldSlot);
            if (oldArtifact == null) unit.workflowArtifacts.remove(target.field);
            else unit.workflowArtifacts.put(target.field, oldArtifact);
            unit.legacyWorkflowArtifactPath = oldLegacyArtifact;
            refreshFormUi();
            alert(t("photo_save_failed"), exc.getMessage());
        }
    }

    private void handleOcrPhotoResult(int requestCode, int resultCode, Intent data) {
        boolean baseSn = requestCode == REQ_OCR_BASE;
        String role = baseSn ? PendingFormOperationRules.ROLE_SECONDARY
            : PendingFormOperationRules.ROLE_PRIMARY;
        PendingFormOperationRules.Target target = pendingMainFormTargetForResult(
            PendingFormOperationRules.OCR_PHOTO, role);
        if (target == null) {
            // A role is not an operation identity. If the exact persisted target cannot be
            // proven, preserve it as recovery evidence instead of clearing a different OCR round.
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        if (!ensureFormStateForPendingTarget(target, baseSn)
                || (!baseSn && nextUnitSequence() != target.unitSequence)) {
            clearPendingTargetAfterOcr(target);
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        Diagnostics.append(this, "OCR photo result received resultCode=" + resultCode + " role=" + (baseSn ? "secondary" : "primary"));
        if (resultCode != RESULT_OK) {
            clearPendingOcrOutput();
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        String path = data == null ? "" : data.getStringExtra("photoPath");
        if (path == null || path.isEmpty()) path = target.outputPath;
        if (!target.outputPath.equals(path)) {
            clearPendingTargetAfterOcr(target);
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        File photoFile = path == null || path.isEmpty() ? null : new File(path);
        if (photoFile == null || !photoFile.exists() || photoFile.length() <= 0) {
            clearPendingTargetAfterOcr(target);
            alert(t("photo_save_failed"), t("photo_full_file_missing"));
            return;
        }
        recognizeSnFromPhoto(baseSn, photoFile, false, target);
    }

    private void recognizeSnFromPhoto(boolean baseSn, File photoFile) {
        recognizeSnFromPhoto(baseSn, photoFile, false, null);
    }

    private void recognizeSnFromPhoto(boolean baseSn, File photoFile, boolean autoCapture) {
        recognizeSnFromPhoto(baseSn, photoFile, autoCapture, null);
    }

    private void recognizeSnFromPhoto(boolean baseSn, File photoFile, boolean autoCapture,
                                      PendingFormOperationRules.Target pendingTarget) {
        final JSONObject configSnapshot = appConfig;
        final JSONObject settingsSnapshot = catalogSettings;
        final ProfileWorkflow ocrWorkflow = profileWorkflow();
        final BackendAdapter adapterSnapshot = BackendAdapter.from(
            configSnapshot, settingsSnapshot);
        if (!ensureOcrConfigured(ocrWorkflow, adapterSnapshot)) {
            clearPendingTargetAfterOcr(pendingTarget);
            return;
        }
        final String tokenSnapshot = savedToken();
        String recognizeTextUrl = boundRecognizeTextUrl(tokenSnapshot);
        if (recognizeTextUrl.isEmpty()) {
            ensureOcrUrlThenRecognize(baseSn, photoFile, autoCapture, pendingTarget);
            return;
        }
        final OperationBindingRules.Binding operation;
        final Api apiSnapshot;
        try {
            operation = beginBoundOperation(OperationBindingRules.OCR, tokenSnapshot);
            apiSnapshot = api(tokenSnapshot, configSnapshot, settingsSnapshot);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "OCR binding unavailable: " + conciseError(invalid));
            clearPendingTargetAfterOcr(pendingTarget);
            return;
        }
        appendLog(t("ocr_running"));
        new Thread(() -> {
            if (!backendConfigured()) {
                finishBoundOperation(operation);
                clearPendingTargetAfterOcr(pendingTarget);
                notifyBackendUnconfigured();
                return;
            }
            try {
                List<File> images = prepareSnRecognitionImages(photoFile);
                for (File image : images) {
                    requireBoundOperation(operation, tokenSnapshot, "OCR image request");
                    try {
                        JSONObject body = RemoteSideEffectSafetyRules.executeOcr(
                            ocrWorkflow, apiSnapshot.endpoints,
                            () -> apiSnapshot.recognizeText(recognizeTextUrl,
                                image,
                                phase -> requireBoundOperation(
                                    operation, tokenSnapshot, phase)));
                        requireBoundOperation(operation, tokenSnapshot,
                            "OCR candidate extraction");
                        List<String> candidates = extractOcrCandidates(apiSnapshot, body);
                        if (!candidates.isEmpty()) {
                            runOnUiThread(() -> {
                                if (boundOperationMatches(operation, tokenSnapshot)) {
                                    showOcrCandidates(baseSn, candidates, operation,
                                        tokenSnapshot, pendingTarget);
                                } else {
                                    finishBoundOperation(operation);
                                    clearPendingTargetAfterOcr(pendingTarget);
                                }
                            });
                            return;
                        }
                    } catch (Exception imageExc) {
                        if (!boundOperationMatches(operation, tokenSnapshot)) {
                            finishBoundOperation(operation);
                            clearPendingTargetAfterOcr(pendingTarget);
                            return;
                        }
                        Diagnostics.append(this, "OCR image skipped: " + conciseError(imageExc));
                    }
                }
                runOnUiThread(() -> {
                    if (!boundOperationMatches(operation, tokenSnapshot)) {
                        finishBoundOperation(operation);
                        clearPendingTargetAfterOcr(pendingTarget);
                        return;
                    }
                    finishBoundOperation(operation);
                    clearPendingTargetAfterOcr(pendingTarget);
                    handleOcrNoText(baseSn, autoCapture);
                });
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    if (!boundOperationMatches(operation, tokenSnapshot)) {
                        finishBoundOperation(operation);
                        clearPendingTargetAfterOcr(pendingTarget);
                        return;
                    }
                    finishBoundOperation(operation);
                    clearPendingTargetAfterOcr(pendingTarget);
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
        ensureOcrUrlThenRecognize(baseSn, photoFile, false, null);
    }

    private void ensureOcrUrlThenRecognize(boolean baseSn, File photoFile, boolean autoCapture) {
        ensureOcrUrlThenRecognize(baseSn, photoFile, autoCapture, null);
    }

    private void ensureOcrUrlThenRecognize(boolean baseSn, File photoFile,
                                           boolean autoCapture,
                                           PendingFormOperationRules.Target pendingTarget) {
        String token = savedToken();
        if (token.isEmpty()) {
            clearPendingTargetAfterOcr(pendingTarget);
            alert(t("login_required"), t("login_required_detail"));
            return;
        }
        fetchAndBindOcrUrl(token, baseSn, autoCapture,
            () -> recognizeSnFromPhoto(baseSn, photoFile, autoCapture, pendingTarget),
            pendingTarget);
    }

    private void fetchAndBindOcrUrl(String tokenSnapshot, boolean baseSn,
                                    boolean autoCapture, Runnable onAvailable,
                                    PendingFormOperationRules.Target pendingTarget) {
        final JSONObject configSnapshot = appConfig;
        final JSONObject settingsSnapshot = catalogSettings;
        final ProfileWorkflow ocrWorkflow = profileWorkflow();
        final BackendAdapter adapterSnapshot = BackendAdapter.from(
            configSnapshot, settingsSnapshot);
        if (!ensureOcrConfigured(ocrWorkflow, adapterSnapshot)) {
            clearPendingTargetAfterOcr(pendingTarget);
            return;
        }
        appendLog(t("ocr_url_refreshing"));
        final OperationBindingRules.Binding operation;
        final Api apiSnapshot;
        try {
            operation = beginBoundOperation(OperationBindingRules.USER_INFO, tokenSnapshot);
            apiSnapshot = api(tokenSnapshot, configSnapshot, settingsSnapshot);
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "User-info binding unavailable: "
                + conciseError(invalid));
            clearPendingTargetAfterOcr(pendingTarget);
            return;
        }
        new Thread(() -> {
            if (!backendConfigured()) {
                finishBoundOperation(operation);
                clearPendingTargetAfterOcr(pendingTarget);
                notifyBackendUnconfigured();
                return;
            }
            try {
                Api.UserProfile user = apiSnapshot.fetchUserInfo(
                    phase -> requireBoundOperation(operation, tokenSnapshot, phase));
                runOnUiThread(() -> {
                    if (!boundOperationMatches(operation, tokenSnapshot)) {
                        finishBoundOperation(operation);
                        clearPendingTargetAfterOcr(pendingTarget);
                        return;
                    }
                    finishBoundOperation(operation);
                    if (!user.userName.isEmpty()) {
                        String realm = currentSessionRealmFingerprint();
                        String fingerprint =
                            SecureTokenStore.getBoundWebFingerprint(prefs, realm);
                        SecureTokenStore.updateUserNameForBinding(
                            prefs, user.userName, realm, fingerprint, tokenSnapshot);
                    }
                    if (user.recognizeTextUrl.isEmpty()
                            || !saveBoundRecognizeTextUrl(
                                user.recognizeTextUrl, tokenSnapshot)) {
                        clearPendingTargetAfterOcr(pendingTarget);
                        alert(t("ocr_unavailable_title"), t("ocr_unavailable_detail"));
                        if (baseSn) refocusBaseInput(); else refocusSnInput();
                    } else if (onAvailable != null) {
                        onAvailable.run();
                    }
                });
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    if (!boundOperationMatches(operation, tokenSnapshot)) {
                        finishBoundOperation(operation);
                        clearPendingTargetAfterOcr(pendingTarget);
                        return;
                    }
                    finishBoundOperation(operation);
                    clearPendingTargetAfterOcr(pendingTarget);
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

    private boolean ensureOcrConfigured() {
        return ensureOcrConfigured(profileWorkflow(), endpoints());
    }

    private boolean ensureOcrConfigured(ProfileWorkflow workflow,
                                        BackendAdapter adapter) {
        List<String> missing = RemoteSideEffectSafetyRules.ocrCapabilityErrors(
            workflow, adapter);
        if (missing.isEmpty()) return true;
        alert(t("panel_required_title"), t("panel_missing_config") + join(missing, ", "));
        return false;
    }

    private void showOcrCandidates(boolean baseSn, List<String> candidates,
                                   OperationBindingRules.Binding operation,
                                   String tokenSnapshot,
                                   PendingFormOperationRules.Target pendingTarget) {
        if (!boundOperationMatches(operation, tokenSnapshot)) {
            finishBoundOperation(operation);
            clearPendingTargetAfterOcr(pendingTarget);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            finishBoundOperation(operation);
            clearPendingTargetAfterOcr(pendingTarget);
            alert(t("ocr_no_text_title"), t("ocr_no_text_detail"));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        SnScanRules.Policy policy = scannerPolicy(baseSn);
        List<String> filtered = new ArrayList<>();
        for (String candidate : candidates) {
            String normalized = policy.normalizeForSource(candidate, SnScanRules.SOURCE_OCR);
            if (policy.acceptsCapture(normalized)) filtered.add(normalized);
        }
        candidates = filtered;
        if (candidates.isEmpty()) {
            finishBoundOperation(operation);
            clearPendingTargetAfterOcr(pendingTarget);
            List<Integer> required = policy.requiredLengthsForSource(
                SnScanRules.SOURCE_OCR);
            alert(t("ocr_no_text_title"), !required.isEmpty()
                ? identifierExpectedOnlyMessage(baseSn, required)
                : identifierPolicyRejectedMessage(baseSn, policy));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        String[] items = candidates.toArray(new String[0]);
        if (!activityAlive()) {
            finishBoundOperation(operation);
            clearPendingTargetAfterOcr(pendingTarget);
            return;
        }
        final boolean[] chooserActionHandled = {false};
        AlertDialog chooser = new AlertDialog.Builder(this)
            .setTitle(t("ocr_choose_title"))
            .setItems(items, (dialog, which) -> {
                chooserActionHandled[0] = true;
                if (!boundOperationMatches(operation, tokenSnapshot)) {
                    finishBoundOperation(operation);
                    clearPendingTargetAfterOcr(pendingTarget);
                    return;
                }
                finishBoundOperation(operation);
                applyRecognizedSn(baseSn, items[which], pendingTarget);
            })
            .setNegativeButton(t("cancel"), (dialog, which) -> {
                chooserActionHandled[0] = true;
                if (!boundOperationMatches(operation, tokenSnapshot)) {
                    finishBoundOperation(operation);
                    clearPendingTargetAfterOcr(pendingTarget);
                    return;
                }
                finishBoundOperation(operation);
                clearPendingTargetAfterOcr(pendingTarget);
                if (baseSn) refocusBaseInput(); else refocusSnInput();
            })
            .create();
        chooser.setOnCancelListener(dialog -> {
            chooserActionHandled[0] = true;
            finishBoundOperation(operation);
            clearPendingTargetAfterOcr(pendingTarget);
        });
        chooser.setOnDismissListener(dialog -> {
            finishBoundOperation(operation);
            if (!chooserActionHandled[0]) clearPendingTargetAfterOcr(pendingTarget);
        });
        chooser.show();
    }

    private void applyRecognizedSn(boolean baseSn, String candidate) {
        applyRecognizedSn(baseSn, candidate, null);
    }

    private void applyRecognizedSn(boolean baseSn, String candidate,
                                   PendingFormOperationRules.Target pendingTarget) {
        // The chooser receives source-normalized candidates from showOcrCandidates.
        String value = candidate == null ? "" : candidate;
        if (value.isEmpty()) {
            clearPendingTargetAfterOcr(pendingTarget);
            toast(requiredInputMessage(baseSn));
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        if (pendingTarget != null
                && (!ensureFormStateForPendingTarget(pendingTarget, baseSn)
                    || (!baseSn && nextUnitSequence() != pendingTarget.unitSequence))) {
            clearPendingTargetAfterOcr(pendingTarget);
            alert(t("photo_save_failed"), t("photo_target_missing"));
            return;
        }
        if (!validateIdentifierValue(value, baseSn, SnScanRules.SOURCE_OCR)) {
            clearPendingTargetAfterOcr(pendingTarget);
            if (baseSn) refocusBaseInput(); else refocusSnInput();
            return;
        }
        Diagnostics.append(this, "OCR selected role=" + (baseSn ? "secondary" : "primary") + " length=" + value.length());
        showScannedSnPreview(value, inputLabel(baseSn));
        if (baseSn) {
            if (baseSnEdit == null) showFormPage(false);
            if (baseSnEdit != null) baseSnEdit.setText(value);
            if (pendingTarget != null) {
                applySecondaryScanTarget(pendingTarget, value, SnScanRules.SOURCE_OCR);
            } else {
                addBaseSnValue(value, SnScanRules.SOURCE_OCR);
            }
            refocusBaseInput();
            return;
        }
        if (snEdit == null) showFormPage(false);
        if (snEdit != null) snEdit.setText(value);
        if (pendingTarget != null) {
            for (UnitRecord item : units) {
                if (value.equals(item.sn)) {
                    clearPendingTargetAfterOcr(pendingTarget);
                    toast(t("duplicate_sn") + value);
                    return;
                }
            }
        }
        UnitRecord added = addSnRecord(value, pendingTarget == null
            ? selectedGrade() : pendingTarget.grade, SnScanRules.SOURCE_OCR);
        if (added != null) {
            if (pendingTarget != null) clearPendingMainFormTarget();
            if (snEdit != null) snEdit.setText("");
            resetGradeSelection();
            checkScannedUnitPreviousSteps(added);
        }
        refocusSnInput();
    }

    private void clearPendingTargetAfterOcr(
            PendingFormOperationRules.Target pendingTarget) {
        if (pendingTarget == null) return;
        try {
            Object raw = prefs.getAll().get(PENDING_MAIN_FORM_OPERATION_KEY);
            if (!(raw instanceof String)) return;
            PendingFormOperationRules.Target current =
                PendingFormOperationRules.parse((String) raw);
            if (current.operationId.equals(pendingTarget.operationId)) {
                clearPendingMainFormTarget();
            }
        } catch (RuntimeException ignored) {
            // Preserve unreadable target bytes; never clear by a guessed legacy path.
        }
    }

    private List<File> prepareSnRecognitionImages(File original) throws IOException {
        List<File> images = new ArrayList<>();
        Bitmap bitmap = decodeRecognitionBitmap(original, 1800);
        if (bitmap != null) {
            try {
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

    private boolean persistPendingMainFormTarget(PendingFormOperationRules.Target target,
                                                 int legacyIndex) {
        if (target == null) return false;
        SharedPreferences.Editor editor = prefs.edit()
            .putString(PENDING_MAIN_FORM_OPERATION_KEY, target.toJson().toString())
            .remove(PENDING_PHOTO_INDEX_KEY)
            .remove(PENDING_PHOTO_SIDE_KEY)
            .remove(PENDING_PHOTO_FIELD_KEY)
            .remove(PENDING_PHOTO_PATH_KEY)
            .remove(PENDING_OCR_PHOTO_PATH_KEY)
            .remove(PENDING_RESCAN_SEQUENCE_KEY);
        // These fields are a write-only rollback mirror for a signed old App. This release restores
        // exclusively from PENDING_MAIN_FORM_OPERATION_KEY and never guesses from an index.
        if (PendingFormOperationRules.PHOTO.equals(target.kind)) {
            editor.putInt(PENDING_PHOTO_INDEX_KEY, legacyIndex)
                .putString(PENDING_PHOTO_SIDE_KEY, target.side)
                .putString(PENDING_PHOTO_FIELD_KEY, target.field)
                .putString(PENDING_PHOTO_PATH_KEY, target.outputPath);
        } else if (PendingFormOperationRules.OCR_PHOTO.equals(target.kind)) {
            editor.putString(PENDING_OCR_PHOTO_PATH_KEY, target.outputPath);
        } else if (PendingFormOperationRules.RESCAN.equals(target.kind)) {
            editor.putInt(PENDING_RESCAN_SEQUENCE_KEY, target.unitSequence);
        }
        if (!editor.commit()) return false;
        pendingMainFormTarget = target;
        applyPendingMainFormTargetToLegacyMemory(target, legacyIndex);
        Diagnostics.append(this, "Bound main-form operation started kind=" + target.kind
            + " profile=" + target.profileId + " sequence=" + target.unitSequence);
        return true;
    }

    private PendingFormOperationRules.Target preparePendingMainFormTarget(
            String kind, int unitSequence, String role, String side, String field,
            String outputPath, String grade, int legacyIndex) {
        if (hasStoredUploadReplayBarrier()
                || hasStoredPreviousStepSubmissionAttempt()
                || hasPendingMainFormOperation()) return null;
        if (!saveDraft(true)) return null;
        try {
            MainDraftSnapshotRules.Binding draftBinding =
                mainDraftBindingForProfile(currentProfileId());
            String operationId = java.util.UUID.randomUUID().toString().replace("-", "");
            OperationBindingRules.Binding operation = OperationBindingRules.capture(
                draftBinding.connectionNamespace, draftBinding.catalogVersion,
                currentPanelPairSha256(), webFingerprint(), savedToken(), operationId, kind);
            PendingFormOperationRules.Target target = PendingFormOperationRules.create(
                kind, operationId, draftBinding, currentPanelPairSha256(), unitSequence,
                role, side, field, outputPath, grade, operation);
            return persistPendingMainFormTarget(target, legacyIndex) ? target : null;
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Pending main-form binding failed: "
                + conciseError(invalid));
            return null;
        }
    }

    private void restorePendingMainFormTarget() {
        pendingMainFormTarget = null;
        try {
            Object raw = prefs.getAll().get(PENDING_MAIN_FORM_OPERATION_KEY);
            if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) return;
            PendingFormOperationRules.Target target =
                PendingFormOperationRules.parse((String) raw);
            pendingMainFormTarget = target;
            applyPendingMainFormTargetToLegacyMemory(target, -1);
        } catch (RuntimeException invalid) {
            // Preserve malformed bytes for manual recovery; never fall back to the legacy mirrors.
            Diagnostics.append(this, "Pending main-form target locked: "
                + conciseError(invalid));
        }
    }

    private void applyPendingMainFormTargetToLegacyMemory(
            PendingFormOperationRules.Target target, int legacyIndex) {
        pendingPhotoIndex = -1;
        pendingPhotoSide = "";
        pendingPhotoField = "";
        pendingOutputPhotoPath = "";
        pendingOcrPhotoPath = "";
        pendingRescanUnitSequence = -1;
        if (target == null) return;
        if (PendingFormOperationRules.PHOTO.equals(target.kind)) {
            UnitRecord unit = unitBySequence(target.unitSequence);
            pendingPhotoIndex = legacyIndex >= 0 ? legacyIndex : units.indexOf(unit);
            pendingPhotoSide = target.side;
            pendingPhotoField = target.field;
            pendingOutputPhotoPath = target.outputPath;
        } else if (PendingFormOperationRules.OCR_PHOTO.equals(target.kind)) {
            pendingOcrPhotoPath = target.outputPath;
        } else if (PendingFormOperationRules.RESCAN.equals(target.kind)) {
            pendingRescanUnitSequence = target.unitSequence;
        }
    }

    private PendingFormOperationRules.Target pendingMainFormTargetForResult(
            String kind, String role) {
        try {
            Object raw = prefs.getAll().get(PENDING_MAIN_FORM_OPERATION_KEY);
            if (!(raw instanceof String)) return null;
            PendingFormOperationRules.Target target =
                PendingFormOperationRules.parse((String) raw);
            if (!kind.equals(target.kind) || !role.equals(target.role)) return null;
            MainDraftSnapshotRules.Binding current =
                mainDraftBindingForProfile(target.profileId);
            if (!target.matches(current, currentPanelPairSha256(), webFingerprint(),
                    savedToken())) return null;
            pendingMainFormTarget = target;
            applyPendingMainFormTargetToLegacyMemory(target, -1);
            return target;
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Pending main-form result rejected: "
                + conciseError(invalid));
            return null;
        }
    }

    private boolean clearPendingMainFormTarget() {
        if (!prefs.edit()
                .remove(PENDING_MAIN_FORM_OPERATION_KEY)
                .remove(PENDING_PHOTO_INDEX_KEY)
                .remove(PENDING_PHOTO_SIDE_KEY)
                .remove(PENDING_PHOTO_FIELD_KEY)
                .remove(PENDING_PHOTO_PATH_KEY)
                .remove(PENDING_OCR_PHOTO_PATH_KEY)
                .remove(PENDING_RESCAN_SEQUENCE_KEY)
                .commit()) {
            return false;
        }
        if (pendingOutputPhotoUri != null) {
            try {
                revokeUriPermission(
                    pendingOutputPhotoUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
        }
        if (pendingOcrPhotoUri != null) {
            try {
                revokeUriPermission(
                    pendingOcrPhotoUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
        }
        pendingOutputPhotoUri = null;
        pendingOcrPhotoUri = null;
        pendingMainFormTarget = null;
        pendingPhotoIndex = -1;
        pendingPhotoSide = "";
        pendingPhotoField = "";
        pendingOutputPhotoPath = "";
        pendingOcrPhotoPath = "";
        pendingRescanUnitSequence = -1;
        return true;
    }

    private void abandonReadablePendingMainFormTarget() {
        try {
            Object raw = prefs.getAll().get(PENDING_MAIN_FORM_OPERATION_KEY);
            if (!(raw instanceof String)) return;
            PendingFormOperationRules.parse((String) raw);
            clearPendingMainFormTarget();
        } catch (RuntimeException ignored) {
            // Unreadable target storage is evidence, not an absent operation.
        }
    }

    private void clearPendingPhotoOutput() {
        clearPendingMainFormTarget();
    }

    private void clearPendingOcrOutput() {
        clearPendingMainFormTarget();
    }

    private void clearPendingRescan() {
        clearPendingMainFormTarget();
    }

    private boolean ensureFormStateForPendingTarget(
            PendingFormOperationRules.Target target, boolean unitRequired) {
        if (target == null
                || (savedToken().isEmpty() && !localSamplePreviewEnabled())) return false;
        try {
            MainDraftSnapshotRules.Binding current =
                mainDraftBindingForProfile(target.profileId);
            if (!target.matches(current, currentPanelPairSha256(), webFingerprint(),
                    savedToken())) return false;
            boolean profileMatches = target.profileId.equals(currentProfileId());
            boolean unitMatches = unitBySequence(target.unitSequence) != null;
            if (profileMatches && (!unitRequired || unitMatches)) return true;

            int profileIndex = findProfileIndex(target.profileId);
            if (profileIndex < 0) return false;
            JSONObject exactDraft = draftForProfile(target.profileId);
            boolean hasDraftUnits = draftHasUnsubmittedUnits(exactDraft);
            if (unitRequired && (!hasDraftUnits
                    || !draftContainsUnitSequence(exactDraft, target.unitSequence))) {
                return false;
            }
            profile = profiles.getJSONObject(profileIndex);
            units.clear();
            showFormPage(false);
            if (hasDraftUnits) restoreDraft(exactDraft);
            MainDraftSnapshotRules.Binding after =
                mainDraftBindingForProfile(target.profileId);
            return target.profileId.equals(currentProfileId())
                && target.matches(after, currentPanelPairSha256(), webFingerprint(),
                    savedToken())
                && (!unitRequired || unitBySequence(target.unitSequence) != null);
        } catch (Exception exc) {
            Diagnostics.append(this, "Pending target draft restore failed: "
                + conciseError(exc));
            return false;
        }
    }

    private String photoCaptureLabel() {
        if (pendingPhotoIndex >= 0 && pendingPhotoIndex < units.size()) {
            UnitRecord unit = units.get(pendingPhotoIndex);
            String label;
            if ("slot".equals(pendingPhotoSide)) label = slotTitleForField(pendingPhotoField);
            else if ("artifact".equals(pendingPhotoSide)) label = workflowArtifactTitle(pendingPhotoField);
            else label = sideName(pendingPhotoSide);
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
        if ("artifact".equals(pendingPhotoSide)) {
            return unit.sn + "-artifact-" + safePhotoFileName(pendingPhotoField) + ".jpg";
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
        if (submitting || alternateEntrySubmitting) {
            toast(t("submit_running"));
            return;
        }
        final ProfileWorkflow workflow = profileWorkflow();
        if (!workflow.previousStepsEnabled) {
            alert(t("check_steps"), t("workflow_previous_steps_disabled"));
            return;
        }
        UploadReplayBarrier.RestoreResult blockingUpload =
            blockingUploadReplayBarrier();
        if (blockingUpload != null) {
            showUploadReplayBarrierBlock(blockingUpload);
            return;
        }
        String token = savedToken();
        if (token.isEmpty()) {
            alert(t("login_required"), t("login_required_detail"));
            return;
        }
        if (units.isEmpty()) {
            toast(t("no_sn"));
            return;
        }
        final MainDraftSnapshotRules.Binding expectedDraftBinding;
        try {
            expectedDraftBinding = captureMainDraftSubmissionBinding();
        } catch (Exception error) {
            Diagnostics.append(this, "Previous-step batch blocked by draft binding: "
                + conciseError(error));
            alert(t("cannot_submit"), t("draft_binding_locked_detail"));
            return;
        }
        if (!beginMainDraftRemoteWorker(expectedDraftBinding)) {
            alert(t("cannot_submit"), t("draft_binding_locked_detail"));
            return;
        }
        appendLog(t("checking_steps"));
        new Thread(() -> {
            try {
                List<String> errors = new ArrayList<>();
                if (!mainDraftSubmissionAllowed(expectedDraftBinding)) {
                    runOnUiThread(() -> alert(
                        t("cannot_submit"), t("draft_binding_locked_detail")));
                    return;
                }
                if (!backendConfigured()) { notifyBackendUnconfigured(); return; }
                Api api = api(token);
                for (UnitRecord unit : units) {
                    try {
                        runWithMainUploadBarrier(
                            api, unit, expectedDraftBinding,
                            () -> ensurePreviousSteps(
                                api, unit, expectedDraftBinding, workflow));
                    } catch (Exception exc) {
                        String message = conciseError(exc);
                        errors.add(unitLogLine(unit, message));
                        appendUnitLog(unit, message);
                        // Once any upload started, its durable barrier intentionally outlives this
                        // worker. Do not inspect or mutate a later unit in the same manual batch.
                        if (hasStoredUploadReplayBarrier()) break;
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
            } finally {
                endMainDraftRemoteWorker();
            }
        }).start();
    }

    private void checkScannedUnitPreviousSteps(UnitRecord unit) {
        final ProfileWorkflow workflow = profileWorkflow();
        if (unit == null || !workflow.scanPrecheckEnabled) return;
        if (!workflow.operationalPoliciesExplicit) {
            alert(t("cannot_submit"), t("profile_policy_migration_required"));
            return;
        }
        if (!workflow.shouldScanPrecheck(unit.grade)) return;
        String token = savedToken();
        if (token.isEmpty()) return;
        final MainDraftSnapshotRules.Binding expectedDraftBinding;
        try {
            expectedDraftBinding = captureMainDraftSubmissionBinding();
        } catch (Exception error) {
            Diagnostics.append(this, "Scan precheck blocked by draft binding: "
                + conciseError(error));
            alert(t("cannot_submit"), t("draft_binding_locked_detail"));
            return;
        }
        if (!beginMainDraftRemoteWorker(expectedDraftBinding)) {
            alert(t("cannot_submit"), t("draft_binding_locked_detail"));
            return;
        }
        appendUnitLog(unit, t("checking_steps"));
        new Thread(() -> {
            try {
                if (!mainDraftSubmissionAllowed(expectedDraftBinding)) {
                    runOnUiThread(() -> alert(
                        t("cannot_submit"), t("draft_binding_locked_detail")));
                    return;
                }
                if (!backendConfigured()) return; // unconfigured → skip quietly
                Api api = api(token);
                requirePreviousStepLookupCapability(api, workflow);
                requireMainDraftRemoteBinding(
                    expectedDraftBinding, "scan previous-step lookup");
                JSONObject body = previousStepsResponse(
                    api, unit, unit.sn, true, expectedDraftBinding);
                if (api.isSuccess(body)) {
                    clearScanPrecheckMissingCount(unit.sn, workflow);
                    markPreviousStepsOk(unit, workflow, expectedDraftBinding);
                    return;
                }
                requireConfiguredPreviousStepMissing(api, body);
                String originalSn = unit.sn;
                if (tryCorrectScannedSnFromPreviousSteps(
                        api, unit, expectedDraftBinding, workflow)) {
                    clearScanPrecheckMissingCount(originalSn, workflow);
                    clearScanPrecheckMissingCount(unit.sn, workflow);
                    markPreviousStepsOk(unit, workflow, expectedDraftBinding);
                    return;
                }
                requireMainDraftRemoteBinding(
                    expectedDraftBinding, "scan previous-step missing action");
                String sn = unit.sn;
                int attempt = recordScanPrecheckMissing(sn, workflow);
                int maximum = workflow.scanPrecheckMaxMissingAttempts;
                boolean atLimit = attempt >= maximum;
                String action = atLimit
                    ? workflow.scanPrecheckAtLimitAction
                    : workflow.scanPrecheckBeforeLimitAction;
                if (!atLimit && ProfileWorkflow.ACTION_REMOVE.equals(action)) {
                    removeScannedUnitAfterPrecheckMissing(unit);
                } else if (ProfileWorkflow.ACTION_REQUIRE_ARTIFACT.equals(action)) {
                    markUnitNeedsStepPhoto(unit);
                } else {
                    unit.workflowArtifactRequired = false;
                    markUnitPrecheckBlocked(unit);
                }
                runOnUiThread(() -> alert(
                    scanPrecheckMissingTitle(attempt, maximum),
                    sn + "\n" + scanPrecheckMissingMessage(attempt, maximum, action)));
            } catch (Exception exc) {
                appendUnitLog(unit, t("scan_precheck_failed") + conciseError(exc));
            } finally {
                endMainDraftRemoteWorker();
            }
        }).start();
    }

    private synchronized int recordScanPrecheckMissing(String sn, ProfileWorkflow workflow) {
        String key = scanPrecheckKey(sn, workflow);
        if (key.isEmpty()) return 1;
        int count = scanPrecheckMissingCounts.containsKey(key) ? scanPrecheckMissingCounts.get(key) + 1 : 1;
        scanPrecheckMissingCounts.put(key, count);
        return count;
    }

    private synchronized void clearScanPrecheckMissingCount(
            String sn, ProfileWorkflow workflow) {
        scanPrecheckMissingCounts.remove(scanPrecheckKey(sn, workflow));
    }

    private String scanPrecheckKey(String sn, ProfileWorkflow workflow) {
        return normalize(workflow.canonicalizeIdentifier(sn));
    }

    private String scanPrecheckMissingMessage(int attempt, int maximum, String action) {
        if (ProfileWorkflow.ACTION_REQUIRE_ARTIFACT.equals(action)) {
            return t("scan_precheck_need_run_photo");
        }
        if (ProfileWorkflow.ACTION_REMOVE.equals(action)) {
            return String.format(java.util.Locale.ROOT,
                t("scan_precheck_retry_progress"), attempt, maximum);
        }
        return t("scan_precheck_blocked");
    }

    private String scanPrecheckMissingTitle(int attempt, int maximum) {
        return attempt >= maximum ? t("steps_missing_title") : t("scan_precheck_retry_title");
    }

    private void markUnitNeedsStepPhoto(UnitRecord unit) {
        unit.workflowArtifactRequired = true;
        markUnitPrecheckBlocked(unit);
    }

    private void markUnitPrecheckBlocked(UnitRecord unit) {
        unit.precheckStatus = t("failed");
        saveDraft();
        runOnUiThread(() -> {
            refreshFormUi();
            refocusSnInput();
        });
    }

    private MainDraftSnapshotRules.Binding captureMainDraftSubmissionBinding()
            throws Exception {
        String profileId = currentProfileId();
        MainDraftSnapshotRules.Binding current = mainDraftBindingForProfile(profileId);
        JSONObject stored = draftForProfile(profileId);
        if (!draftHasUnsubmittedUnits(stored)) {
            if (!saveDraft(true)) throw new IllegalStateException("draft persistence failed");
            stored = draftForProfile(profileId);
        }
        MainDraftSnapshotRules.RestoreDecision before = MainDraftSnapshotRules.evaluate(
            stored, current, null, BuildConfig.VERSION_CODE, "");
        if (before.kind != MainDraftSnapshotRules.RestoreKind.EXACT) {
            throw new IllegalStateException("stored draft binding mismatch");
        }
        // Flush the latest queue bytes synchronously, then prove the exact binding survived the
        // round trip. Submission never relies on an apply() that may still be pending at POST time.
        if (!saveDraft(true)) throw new IllegalStateException("draft persistence failed");
        stored = draftForProfile(profileId);
        MainDraftSnapshotRules.RestoreDecision after = MainDraftSnapshotRules.evaluate(
            stored, current, null, BuildConfig.VERSION_CODE, "");
        if (after.kind != MainDraftSnapshotRules.RestoreKind.EXACT) {
            throw new IllegalStateException("persisted draft binding mismatch");
        }
        return current;
    }

    private boolean mainDraftSubmissionAllowed(MainDraftSnapshotRules.Binding expected) {
        try {
            String profileId = currentProfileId();
            JSONObject catalogProfile = uniqueProfile(allProfiles, profileId);
            if (!MainDraftSnapshotRules.runtimeProfileMatchesCatalog(
                    profile, catalogProfile)) {
                return false;
            }
            MainDraftSnapshotRules.Binding current =
                mainDraftBindingForProfile(profileId);
            if (expected == null || !expected.sameAs(current)) return false;
            JSONObject stored = draftForProfile(profileId);
            return MainDraftSnapshotRules.evaluate(stored, current, null,
                BuildConfig.VERSION_CODE, "").kind
                    == MainDraftSnapshotRules.RestoreKind.EXACT;
        } catch (Exception error) {
            Diagnostics.append(this, "Submission draft binding check failed: "
                + conciseError(error));
            return false;
        }
    }

    private void requireMainDraftRemoteBinding(
            MainDraftSnapshotRules.Binding expected, String phase)
            throws SubmissionJournalLockedException {
        if (!mainDraftSubmissionAllowed(expected)) {
            throw new SubmissionJournalLockedException(
                "Draft binding changed before " + phase);
        }
    }

    private synchronized boolean beginMainDraftRemoteWorker(
            MainDraftSnapshotRules.Binding expected) {
        if (submitting || mainDraftRemoteWorkerCount > 0 || printRemoteWorkerCount > 0
                || !mainDraftSubmissionAllowed(expected)
                || hasStoredUploadReplayBarrier()
                || blockingPreviousStepSubmissionAttempt() != null
                || !authorizeMainWorkerForUnsafeCandidate()) return false;
        RemoteSideEffectGate.WorkerLease lease =
            RemoteSideEffectGate.tryAcquireWorker(this);
        if (lease == null) return false;
        mainDraftRemoteWorkerLease = lease;
        mainDraftRemoteWorkerCount++;
        return true;
    }

    private synchronized void endMainDraftRemoteWorker() {
        if (mainDraftRemoteWorkerCount > 0) mainDraftRemoteWorkerCount--;
        if (mainDraftRemoteWorkerCount == 0 && mainDraftRemoteWorkerLease != null) {
            mainDraftRemoteWorkerLease.close();
            mainDraftRemoteWorkerLease = null;
        }
    }

    private synchronized boolean mainDraftRemoteWorkerActive() {
        return mainDraftRemoteWorkerCount > 0;
    }

    private synchronized boolean profileOwnedRemoteWorkerActive() {
        return mainDraftRemoteWorkerCount > 0 || printRemoteWorkerCount > 0;
    }

    private boolean mainDraftRestoreBlocked() {
        return submitting || profileOwnedRemoteWorkerActive();
    }

    private void submitBatch() {
        if (submitting || alternateEntrySubmitting
                || profileOwnedRemoteWorkerActive()) {
            toast(t("submit_running"));
            return;
        }
        UploadReplayBarrier.RestoreResult blockingUpload =
            blockingUploadReplayBarrier();
        if (blockingUpload != null) {
            showUploadReplayBarrierBlock(blockingUpload);
            return;
        }
        AlternateSubmissionAttempt.RestoreResult blockingMainAttempt =
            blockingMainSubmissionAttempt();
        if (blockingMainAttempt != null) {
            showMainSubmissionBlock(blockingMainAttempt);
            return;
        }
        PreviousStepSubmissionAttempt.RestoreResult blockingPreviousStepAttempt =
            blockingPreviousStepSubmissionAttempt();
        if (blockingPreviousStepAttempt != null) {
            showPreviousStepSubmissionBlock(blockingPreviousStepAttempt);
            return;
        }
        final MainDraftSnapshotRules.Binding submittedDraftBinding;
        if (!units.isEmpty()) {
            try {
                submittedDraftBinding = captureMainDraftSubmissionBinding();
            } catch (Exception error) {
                Diagnostics.append(this, "Submission blocked by draft binding: "
                    + conciseError(error));
                alert(t("cannot_submit"), t("draft_binding_locked_detail"));
                return;
            }
        } else {
            submittedDraftBinding = null;
        }
        String token = savedToken();
        List<String> validationErrors = validateBatch(token);
        if (!validationErrors.isEmpty()) {
            alert(t("cannot_submit"), join(validationErrors, "\n"));
            return;
        }
        final boolean printingEnabled = printingConfiguredForProfile();
        final ProfileWorkflow submittedWorkflow = profileWorkflow();
        final PrintRemoteContext submittedPrintContext;
        if (printingEnabled) {
            try {
                // One immutable print execution context covers preflight, every status GET, every
                // reprint POST/response and the final ledger decision for this whole batch.
                submittedPrintContext = capturePrintRemoteContext(0L, "batch");
            } catch (Exception bindingError) {
                alert(t("cannot_submit"), t("print_reconcile_binding_changed"));
                return;
            }
        } else {
            submittedPrintContext = null;
        }
        missingMaterialNoticeShown = false;
        notifiedMissingMaterialCodes.clear();
        synchronized (dnsAffectedUnits) {
            dnsAffectedUnits.clear();
        }
        synchronized (roundMissingMaterials) {
            roundMissingMaterials.clear();
        }
        if (!authorizeMainWorkerForUnsafeCandidate()) {
            alert(t("cannot_submit"), t("draft_binding_locked_detail"));
            return;
        }
        final RemoteSideEffectGate.WorkerLease submitWorkerLease =
            RemoteSideEffectGate.tryAcquireWorker(this);
        if (submitWorkerLease == null) {
            alert(t("cannot_submit"), t("alternate_entry_storage_locked_detail"));
            return;
        }
        submitting = true;
        int submittableTotal = 0;
        for (UnitRecord u : units) {
            if (!isSubmittedStatus(u.status)) submittableTotal++;
        }
        final int totalSubmittable = submittableTotal;
        final long configuredInterUnitDelayMs = submittedWorkflow.submissionInterUnitDelayMs;
        final int configuredRoundRetentionDays = submittedWorkflow.roundLedgerRetentionDays;
        final String submittedProfileId = currentProfileId();
        showSubmitLoading(totalSubmittable);
        new Thread(() -> {
            try {
            if (!mainDraftSubmissionAllowed(submittedDraftBinding)) {
                runOnUiThread(() -> {
                    submitting = false;
                    hideSubmitLoading();
                    alert(t("cannot_submit"), t("draft_binding_locked_detail"));
                });
                return;
            }
            if (!backendConfigured()) {
                runOnUiThread(() -> { submitting = false; hideSubmitLoading(); notifyBackendUnconfigured(); });
                return;
            }
            // Pre-submit auth gate: probe the inherited/cached token once up front so an expired login
            // (e.g. another device logged in) becomes a clear re-login prompt rather than a confusing
            // mid-batch upload failure. UNKNOWN (network blip) proceeds; the submit reports any failure.
            if (checkAuthBoundNow(api(token), token) == Api.AuthState.INVALID) {
                runOnUiThread(() -> {
                    submitting = false;
                    hideSubmitLoading();
                    handleRemoteLogout(true);
                });
                return;
            }
            boolean success = false;
            boolean abortedForPrinter = false;
            boolean sessionExpiredDuringSubmit = false;
            int removedDuringSubmit = 0;
            int submittedSoFar = 0;
            int consecutiveFailures = 0;
            List<String> errors = new ArrayList<>();
            List<String> inlineFailedSns = new ArrayList<>();
            List<UnitRecord> deferredNoJobUnits = new ArrayList<>();
            List<JSONObject> roundLedger = new ArrayList<>(); // per-unit outcome (submit + print) for the local ledger
            try {
                Api api = submittedPrintContext == null ? api(token) : submittedPrintContext.api;
                abortedForPrinter = printingEnabled && !ensurePrinterReady(submittedPrintContext);
                if (!abortedForPrinter) {
                // One logical refresh per batch, after the legacy-compatible printer preflight but
                // before any upload, previous-step recipe or submit request. The parser builds a
                // complete replacement first, so a failed response leaves profile/queue untouched.
                runWithPreUploadNetworkRetry(
                    () -> refreshProfileMaterialsBeforeSubmit(api, submittedWorkflow),
                    submittedWorkflow);
                List<UnitRecord> queue = new ArrayList<>(units);
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
                    boolean submittedThisUnit = false;
                    boolean stopAfterUnconfirmedPrint = false;
                    try {
                        final UnitRecord currentUnit = unit;
                        final int currentPosition = position;
                        currentDnsContext.set(new DnsContext(MainActivity.this, currentUnit, currentPosition));
                        try {
                            runWithSubmissionNetworkRetry(() -> submitUnit(
                                api, currentUnit, submittedDraftBinding, submittedWorkflow),
                                unitLogLine(unit, ""), currentUnit, currentPosition,
                                submittedWorkflow, api, submittedDraftBinding);
                        } finally {
                            currentDnsContext.remove();
                        }
                        submittedThisUnit = "success".equals(currentUnit.status);
                        submittedSoFar++;
                        setSubmitProgress(submittedSoFar);
                        if (removeSubmittedUnitFromQueue(unit)) removedDuringSubmit++;
                        consecutiveFailures = 0;
                        // Confirm this unit's label printed; reprint inline (in order) before the next unit.
                        // Done here — not at the end — so reprinted labels stay in sequence. The explicit
                        // profile-owned inter-unit delay is applied separately below.
                        if (printingEnabled && submittedThisUnit) {
                            boolean stopOnUnconfirmed = "stop".equals(
                                submittedPrintContext.workflow.printingOnUnconfirmed);
                            boolean deferredMissingTwoPass = submittedPrintContext.workflow
                                .usesDeferredMissingTwoPassRecheck();
                            PrintConfirmationRules.Result printResult = confirmPrintInline(
                                submittedPrintContext, currentUnit,
                                !stopOnUnconfirmed && deferredMissingTwoPass);
                            if (stopOnUnconfirmed
                                    && printResult == PrintConfirmationRules.Result.MISSING) {
                                printResult = finalPrintCheckBeforeStopping(
                                    submittedPrintContext, currentUnit);
                            }
                            if (PrintConfirmationRules.shouldAlertAfterInline(printResult)) {
                                inlineFailedSns.add(currentUnit.sn);
                            } else if (PrintConfirmationRules.shouldDeferUntilBatchEnd(
                                    printResult, deferredMissingTwoPass)) {
                                if (stopOnUnconfirmed) inlineFailedSns.add(currentUnit.sn);
                                else deferredNoJobUnits.add(currentUnit);
                            } else if (printResult == PrintConfirmationRules.Result.MISSING) {
                                // Legacy-compatible inline-only mode reports this unit immediately.
                                // Manual recovery remains protected by the fresh status/binding check.
                                inlineFailedSns.add(currentUnit.sn);
                            }
                            // Only a printed result under the still-current binding can turn green.
                            if (!printRemoteBindingStillCurrent(submittedPrintContext)) {
                                printResult = PrintConfirmationRules.Result.UNCERTAIN;
                            }
                            roundLedger.add(ledgerUnit(currentUnit.sn, true,
                                    printResult == PrintConfirmationRules.Result.PRINTED, currentUnit.grade));
                            if (stopOnUnconfirmed
                                    && printResult != PrintConfirmationRules.Result.PRINTED) {
                                errors.add(unitLogLine(currentUnit, t("print_unconfirmed_stop")));
                                stopAfterUnconfirmedPrint = true;
                            }
                        }
                    } catch (Exception exc) {
                        if (BackendSessionErrors.isSessionInvalid(exc)) {
                            // submitUnit already returned OK, but auth expired while checking its label.
                            // The unit has intentionally left the upload queue; persist it as submitted
                            // + print-unconfirmed so re-login reconciliation can find it instead of
                            // silently losing it from both the draft and the local ledger.
                            if (printingEnabled && submittedThisUnit) {
                                roundLedger.add(ledgerUnit(unit.sn, true, false, unit.grade));
                                appendLog("session expired during print confirmation; ledger kept unconfirmed sn="
                                        + unit.sn);
                            }
                            sessionExpiredDuringSubmit = true;
                            break; // the whole token is invalid; do not fail/report every remaining unit
                        }
                        if (exc instanceof SubmissionTerminalRecoveryException) {
                            String message = t("alternate_entry_completed_cleanup_detail");
                            errors.add(unitLogLine(unit, message));
                            appendUnitLog(unit, message);
                            runOnUiThread(this::refreshFormUi);
                            reportSubmitFailure(unit, position, exc);
                            // The terminal status is kept in memory and, when possible, on disk.
                            // Do not overwrite it with "failed" or remove the unit until the
                            // previous-step receipt can be retired safely.
                            break;
                        }
                        if (exc instanceof UploadReplayBarrierRetirementException) {
                            boolean newlySubmitted = "success".equals(unit.status);
                            if (newlySubmitted) {
                                submittedThisUnit = true;
                                submittedSoFar++;
                                setSubmitProgress(submittedSoFar);
                            }
                            String message = t("upload_result_uncertain_detail");
                            errors.add(unitLogLine(unit, message));
                            appendUnitLog(unit, message);
                            if (printingEnabled && newlySubmitted) {
                                roundLedger.add(ledgerUnit(
                                    unit.sn, true, false, unit.grade));
                                inlineFailedSns.add(unit.sn);
                            }
                            runOnUiThread(this::refreshFormUi);
                            reportSubmitFailure(unit, position, exc);
                            // Preserve the terminal classification exactly. In particular,
                            // already_submitted/duplicate_skipped are not counted as a new POST or
                            // print candidate merely because local barrier retirement failed.
                            break;
                        }
                        if (exc instanceof SubmissionAcknowledgedRecoveryException) {
                            submittedThisUnit = true;
                            submittedSoFar++;
                            setSubmitProgress(submittedSoFar);
                            String message = t("alternate_entry_completed_cleanup_detail");
                            errors.add(unitLogLine(unit, message));
                            appendUnitLog(unit, message);
                            if (printingEnabled) {
                                roundLedger.add(ledgerUnit(
                                    unit.sn, true, false, unit.grade));
                                inlineFailedSns.add(unit.sn);
                            }
                            reportSubmitFailure(unit, position, exc);
                            // The server acknowledged this unit, but its local completion receipt
                            // could not be closed. Keep the unit successful and stop before another
                            // POST can overwrite the recovery slot.
                            break;
                        }
                        if (exc instanceof SubmissionJournalLockedException) {
                            String message = t("alternate_entry_storage_locked_detail");
                            errors.add(unitLogLine(unit, message));
                            appendUnitLog(unit, message);
                            unit.status = "failed";
                            if (printingEnabled) {
                                roundLedger.add(ledgerUnit(
                                    unit.sn, false, false, unit.grade));
                            }
                            saveDraft(true);
                            runOnUiThread(this::refreshFormUi);
                            reportSubmitFailure(unit, position, exc);
                            break;
                        }
                        if (exc instanceof SubmissionOutcomeUncertainException
                                || exc instanceof
                                    PreviousStepSubmissionOutcomeUncertainException) {
                            String message = exc instanceof
                                    PreviousStepSubmissionOutcomeUncertainException
                                ? t("previous_step_result_uncertain_detail")
                                : t("alternate_entry_result_uncertain_detail");
                            errors.add(unitLogLine(unit, message));
                            appendUnitLog(unit, message);
                            unit.status = "failed";
                            if (printingEnabled) {
                                roundLedger.add(ledgerUnit(
                                    unit.sn, false, false, unit.grade));
                            }
                            saveDraft(true);
                            runOnUiThread(this::refreshFormUi);
                            reportSubmitFailure(unit, position, exc);
                            // One unresolved POST locks the connection/profile slot. Do not upload
                            // or prepare any later unit in this batch.
                            break;
                        }
                        if (hasStoredUploadReplayBarrier()) {
                            String message = t("upload_result_uncertain_detail");
                            errors.add(unitLogLine(unit, message));
                            appendUnitLog(unit, message);
                            unit.status = "failed";
                            if (printingEnabled) {
                                roundLedger.add(ledgerUnit(
                                    unit.sn, false, false, unit.grade));
                            }
                            saveDraft(true);
                            runOnUiThread(this::refreshFormUi);
                            reportSubmitFailure(unit, position, exc);
                            // A multipart request may already have reached the backend. Keep its
                            // durable lock and stop before any later unit performs a remote action.
                            break;
                        }
                        String message = conciseError(exc);
                        errors.add(unitLogLine(unit, message));
                        appendUnitLog(unit, message);
                        unit.status = "failed";
                        if (printingEnabled) {
                            roundLedger.add(ledgerUnit(unit.sn, false, false, unit.grade));
                        }
                        saveDraft();
                        runOnUiThread(this::refreshFormUi);
                        reportSubmitFailure(unit, position, exc);
                        consecutiveFailures++;
                        if (consecutiveFailures
                                >= submittedWorkflow.submissionMaxConsecutiveFailures) {
                            errors.add(t("submit_aborted_consecutive"));
                            break;
                        }
                        // keep going: one bad unit must not strand the rest of the batch
                    }
                    if (stopAfterUnconfirmedPrint) break;
                    long interUnitDelayMs = SubmissionPolicyRules.delayBeforeNext(
                        configuredInterUnitDelayMs,
                        hasRemainingSubmittableUnit(queue, position));
                    if (interUnitDelayMs > 0L) Thread.sleep(interUnitDelayMs);
                }
                // In the Panel-owned two-pass mode, an asynchronous job can appear after the per-unit
                // window. Recheck missing jobs after the final submission; only SNs still missing then
                // wait once (on this worker thread) for the configured grace period before the last check.
                if (printingEnabled && !sessionExpiredDuringSubmit
                        && submittedPrintContext.workflow.usesDeferredMissingTwoPassRecheck()
                        && !deferredNoJobUnits.isEmpty()) {
                    try {
                        recheckDeferredPrintsAtBatchEnd(submittedPrintContext,
                            deferredNoJobUnits, inlineFailedSns, roundLedger);
                    } catch (BackendSessionErrors.SessionInvalidException invalid) {
                        sessionExpiredDuringSubmit = true;
                    }
                }
                // Session expiry can interrupt the confirmation pass before deferred SNs reach the
                // failure list. The round ledger already contains every successfully submitted unit,
                // so merge all still-unconfirmed entries before reporting or returning to login.
                if (printingEnabled
                        && !printRemoteBindingStillCurrent(submittedPrintContext)) {
                    // Never persist a green remote outcome after its exact Panel/session/policy
                    // binding went stale. Keep the submitted fact, downgrade only print proof, and
                    // force operator reconciliation under a fresh context.
                    downgradeRoundLedgerPrintProof(roundLedger);
                }
                if (printingEnabled) mergeUnconfirmedRoundLedgerSns(roundLedger, inlineFailedSns);
                // One merged report for the whole batch (not per-unit) so it stacks on a single issue.
                if (!inlineFailedSns.isEmpty()) {
                    reportInlinePrintFailures(inlineFailedSns);
                }
                if (!roundLedger.isEmpty()) saveRoundToLedger(
                    submittedPrintContext, roundLedger, submittedProfileId,
                    configuredRoundRetentionDays);
                success = errors.isEmpty();
                }
            } catch (Exception exc) {
                if (BackendSessionErrors.isSessionInvalid(exc)) {
                    sessionExpiredDuringSubmit = true;
                } else {
                    errors.add(t("submit_warmup_failed") + conciseError(exc));
                    reportSubmitFailure(null, 0, exc);
                }
            } finally {
                boolean finalSuccess = success;
                boolean finalAborted = abortedForPrinter;
                boolean finalSessionExpired = sessionExpiredDuringSubmit;
                int finalSubmittedSoFar = submittedSoFar;
                int finalRemovedDuringSubmit = removedDuringSubmit;
                int finalSubmitted = submittedSoFar;
                List<String> finalErrors = new ArrayList<>(errors);
                List<String> finalInlineFailed = new ArrayList<>(inlineFailedSns); // SNs we could not confirm printed
                runOnUiThread(() -> {
                    submitting = false;
                    hideSubmitLoading();
                    if (finalSessionExpired) {
                        String dnsWarning = buildDnsAffectedMessage();
                        if (finalSubmitted > 0 || !finalErrors.isEmpty() || !finalInlineFailed.isEmpty()) {
                            notifyRoundToNotify(false, finalSubmitted, finalErrors, finalInlineFailed);
                        }
                        handleRemoteLogout(true, finalInlineFailed);
                        return;
                    }
                    if (finalAborted) {
                        toast(t("submit_cancelled_printer_offline"));
                        return;
                    }
                    int removed = finalRemovedDuringSubmit + pruneSubmittedUnits();
                    String dnsWarning = buildDnsAffectedMessage();
                    boolean offerReconcile = printingEnabled && finalSubmittedSoFar > 0;
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
                    notifyRoundToNotify(finalSuccess, finalSubmitted, finalErrors, finalInlineFailed);
                });
            }
            } finally {
                // This outer scope includes every preflight early-return above. The process-wide
                // side-effect gate must never remain occupied after a rejected binding/backend/
                // session check.
                submitWorkerLease.close();
            }
        }).start();
    }

    private boolean hasRemainingSubmittableUnit(List<UnitRecord> queue, int nextIndex) {
        for (int i = Math.max(0, nextIndex); queue != null && i < queue.size(); i++) {
            UnitRecord candidate = queue.get(i);
            if (units.contains(candidate) && !isSubmittedStatus(candidate.status)) return true;
        }
        return false;
    }

    private void refreshProfileMaterialsBeforeSubmit(Api api, ProfileWorkflow workflow)
            throws Exception {
        if (workflow == null || !workflow.refreshMaterialsBeforeSubmit) return;
        appendLog(t("materials_refreshing"));
        BackendAdapter.MaterialRefresh mapping = api.endpoints.materialRefresh;
        String query = mapping.idParam + "=" + enc(String.valueOf(templateId()));
        JSONObject body = api.getEndpointJson(BackendAdapter.ENDPOINT_TEMPLATE_DETAIL, query);
        if (!api.isSuccess(body)) {
            throw new IOException(t("materials_refresh_failed") + api.apiErrorMessage(body));
        }
        Object templateData = api.apiData(body);
        JSONArray refreshed = MaterialRefreshRules.refreshedGroups(
            profile, templateData, mapping);

        // Commit only after the complete response passed all mapping, group, code and quantity
        // checks. Unknown backend fields were ignored by MaterialRefreshRules and cannot enter the
        // payload without a Panel-declared materialGroups entry.
        // Never mutate the active catalog object: it is the stable Panel-owned definition used by
        // submission recovery. The refreshed material list belongs only to this open workflow.
        JSONObject runtimeProfile = new JSONObject(profile.toString());
        runtimeProfile.put("materialGroups", refreshed);
        profile = runtimeProfile;
        int count = 0;
        for (int i = 0; i < refreshed.length(); i++) {
            JSONArray items = refreshed.optJSONObject(i).optJSONArray("materials");
            count += items == null ? 0 : items.length();
        }
        appendLog(t("materials_refreshed") + count);
        runOnUiThread(this::refreshMissingMaterialsUi);
    }

    // ===== Optional asynchronous-print reconciliation =====
    // The panel adapter decides whether this feature exists and maps every remote field/status.

    // Internal UI states. Backend status values are mapped into these by backendAdapter.printing.
    private static final int PRINT_STATUS_MISSING = -1;
    private static final int PRINT_STATUS_PRINTED = 1;
    private static final int PRINT_STATUS_FAILED = 2;
    private static final int PRINT_STATUS_ONGOING = 3;
    private static final int PRINT_STATUS_UNKNOWN = 4;
    private static final String REMOTE_PRINT_STATUS_KEY = "remotePrintStatus";
    private static final String REMOTE_PRINT_ID_KEY = "remotePrintId";
    // Read-only compatibility for ledgers written by v1.0.6; new writes use neutral names above.
    private static final String LEGACY_REMOTE_STATUS_KEY = "cloud" + "Status";
    private static final String LEGACY_REMOTE_ID_KEY = "cloud" + "Id";

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

    // Returns true to go ahead with the batch. If the configured printer is not online, asks the
    // operator whether to proceed or fix it first.
    private boolean ensurePrinterReady(PrintRemoteContext context) throws Exception {
        if (context == null) throw new IOException("Print context is missing");
        PrintRemoteBinding target = context.binding;
        String note;
        try {
            requirePrintRemoteBinding(context, target, 0L, "batch",
                "printer preflight GET");
            JSONObject st = context.api.printerState();
            requirePrintRemoteBinding(context, target, 0L, "batch",
                "printer preflight response");
            JSONObject data = context.api.apiDataObject(st);
            if (context.api.isSuccess(st) && context.api.endpoints.printing.isOnline(st)) {
                return true;
            }
            note = context.api.isSuccess(st)
                ? t("printer_offline") : context.api.apiErrorMessage(st);
        } catch (Exception e) {
            BackendSessionErrors.SessionInvalidException invalid = BackendSessionErrors.find(e);
            if (invalid != null) throw invalid;
            if (!printRemoteBindingStillCurrent(context)) throw e;
            note = t("printer_check_failed") + conciseError(e);
        }
        // Printer not confirmed online before a batch — log + report so we have a trail if it causes 丢单.
        appendLog("configured printer not online at submit: " + note);
        FailureReporter.get().report("print", "printer_not_ready", "pre_submit", null);
        String action = context.workflow.printingPreflightAction;
        if (ProfileWorkflow.ACTION_CONTINUE.equals(action)) {
            appendLog("printing preflight policy: continue");
            return true;
        }
        if (!ProfileWorkflow.ACTION_CONFIRM.equals(action)) {
            appendLog("printing preflight policy: block");
            return false;
        }
        boolean proceed = confirmOnUiThread(t("printer_warn_title"),
            t("printer_warn_msg") + "\n\n" + note,
            t("printer_warn_proceed"), t("printer_warn_fix"));
        requirePrintRemoteBinding(context, target, 0L, "batch",
            "printer preflight decision");
        return proceed;
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

    // ===== Label-print reconciliation & reprint safety net =====
    // The message list is scoped to the logged-in operator and print jobs are matched by the submitted
    // serial number. Printer status is also restricted to the operator's bound label printer so jobs
    // from unrelated workflows cannot enter this reconciliation view.

    private void showPrintReconcileDialog() {
        if (!printingConfiguredForProfile()) {
            alert(t("print_reconcile_title"), t("workflow_printing_disabled"));
            return;
        }
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
            if (profileOwnedRemoteWorkerActive()) {
                toast(t("print_reconcile_loading"));
                return;
            }
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
        final PrintRemoteContext context;
        try {
            context = capturePrintRemoteContext(0L, "ledger");
        } catch (Exception error) {
            toast(t("print_reconcile_binding_changed"));
            return;
        }
        if (!beginPrintRemoteWorker(context)) {
            toast(t("print_reconcile_binding_changed"));
            return;
        }
        runOnUiThread(() -> {
            if (!printRemoteBindingStillCurrent(context)) return;
            header.setText(t("print_reconcile_loading"));
            list.removeAllViews();
        });
        final boolean cloud = printReconcileCloudVerify;
        new Thread(() -> {
            try {
                // Resolve/adopt the device-local rollback ledger before the first remote read.
                // A malformed, cross-Panel or partially committed mirror must not cause even a
                // printer-state/SN lookup under the current connection.
                requirePrintRemoteBinding(context, context.binding, 0L, "ledger",
                    "ledger read");
                List<JSONObject> rounds = loadRecentRounds(3, context.binding.profileId);
                if (blockedRollbackMirrors.contains(ROUND_LEDGER_KEY)) {
                    throw new IOException("Round ledger rollback mirror is unresolved");
                }
                requirePrintRemoteBinding(context, context.binding, 0L, "ledger",
                    "printer-state GET");
                boolean online = false;
                String note = "";
                try {
                    JSONObject st = context.api.printerState();
                    requirePrintRemoteBinding(context, context.binding, 0L, "ledger",
                        "printer-state response");
                    JSONObject d = context.api.apiDataObject(st);
                    if (context.api.isSuccess(st) && d != null) {
                        online = context.api.endpoints.printing.isOnline(st);
                        note = online ? t("printer_online") : t("printer_offline");
                    } else {
                        note = context.api.isSuccess(st)
                            ? t("printer_offline") : context.api.apiErrorMessage(st);
                    }
                } catch (Exception e) { note = conciseError(e); }
                // Confirm print outcomes against the cloud. Cloud-verify view re-checks every unit; the default
                // view checks only the still-"unconfirmed" ones (submitted but no confirmed label) — so just
                // opening/refreshing reconciliation resolves them to printed/failed, no manual toggle needed.
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
                        verifyRoundAgainstCloud(context, r, header, progress, total,
                            unconfirmedOnly);
                    }
                    requirePrintRemoteBinding(context, context.binding, 0L, "ledger",
                        "ledger persist");
                    // A confirmed-printed (status 1) result sticks so it won't be re-queried.
                    persistLedgerPrintedOk(context, rounds);
                }
                final boolean fonline = online;
                final String fnote = note;
                final List<JSONObject> frounds = rounds;
                runOnUiThread(() -> {
                    if (!printRemoteBindingStillCurrent(context)) return;
                    renderReconcile(header, list, fonline, fnote, frounds, cloud);
                });
            } catch (Exception error) {
                Diagnostics.append(this, "Print reconciliation stopped: "
                    + conciseError(error));
                runOnUiThread(() -> {
                    if (!activityAlive() || !printRemoteBindingStillCurrent(context)) return;
                    toast(t("print_reconcile_binding_changed"));
                });
            } finally {
                endPrintRemoteWorker();
            }
        }, "print-reconcile").start();
    }

    private static final class PrintRemoteContext {
        final PrintRemoteBinding binding;
        final ProfileWorkflow workflow;
        final Api api;

        PrintRemoteContext(PrintRemoteBinding binding, ProfileWorkflow workflow, Api api) {
            this.binding = binding;
            this.workflow = workflow;
            this.api = api;
        }
    }

    private static final class PrintJobLookup {
        final JSONObject response;
        final JSONObject job;

        PrintJobLookup(JSONObject response, JSONObject job) {
            this.response = response;
            this.job = job;
        }
    }

    private static final class ReprintJournalLockedException extends IOException {
        ReprintJournalLockedException(String message) {
            super(message);
        }

        ReprintJournalLockedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ReprintOutcomeUncertainException extends IOException {
        ReprintOutcomeUncertainException(String message) {
            super(message);
        }

        ReprintOutcomeUncertainException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private PrintReprintAttempt.Store readReprintAttemptStore()
            throws ReprintJournalLockedException {
        synchronized (REPRINT_JOURNAL_LOCK) {
            try {
                Object raw = prefs.getAll().get(REPRINT_ATTEMPTS_KEY);
                if (raw == null) return PrintReprintAttempt.Store.empty();
                if (!(raw instanceof String)) {
                    throw new IllegalArgumentException("reprint journal type is invalid");
                }
                return PrintReprintAttempt.Store.parse((String) raw);
            } catch (Exception invalid) {
                throw new ReprintJournalLockedException(
                    "Durable reprint journal is unreadable", invalid);
            }
        }
    }

    private boolean writeReprintAttemptStore(PrintReprintAttempt.Store store) {
        synchronized (REPRINT_JOURNAL_LOCK) {
            if (store == null) return false;
            SharedPreferences.Editor editor = prefs.edit();
            if (store.attempts.isEmpty()) editor.remove(REPRINT_ATTEMPTS_KEY);
            else editor.putString(REPRINT_ATTEMPTS_KEY, store.serialize());
            return editor.commit();
        }
    }

    private void recoverReprintAttemptsAfterProcessDeath() {
        synchronized (REPRINT_JOURNAL_LOCK) {
            try {
                PrintReprintAttempt.Store store = readReprintAttemptStore();
                if (!store.hasPosting()) return;
                PrintReprintAttempt.Store recovered = store.recoverPosting(
                    System.currentTimeMillis());
                if (!writeReprintAttemptStore(recovered)) {
                    Diagnostics.append(this,
                        "Reprint journal recovery commit failed; POSTING remains blocking");
                } else {
                    Diagnostics.append(this,
                        "Recovered interrupted reprint POST as outcome-uncertain");
                }
            } catch (ReprintJournalLockedException locked) {
                // Preserve the exact unreadable bytes. Any future reprint fails closed rather than
                // replacing evidence that a POST may already have left this device.
                Diagnostics.append(this, "Reprint journal remains locked: "
                    + conciseError(locked));
            }
        }
    }

    private PrintReprintAttempt.Attempt beginReprintAttempt(
            PrintRemoteBinding target, byte[] exactPayload)
            throws ReprintJournalLockedException, ReprintOutcomeUncertainException {
        synchronized (REPRINT_JOURNAL_LOCK) {
            try {
                PrintReprintAttempt.Store store = readReprintAttemptStore();
                if (store.blocking(target) != null) {
                    throw new ReprintOutcomeUncertainException(
                        "This print job already has an unresolved reprint POST");
                }
                String operationId = java.util.UUID.randomUUID().toString().replace("-", "");
                PrintReprintAttempt.Attempt attempt = PrintReprintAttempt.Attempt.posting(
                    operationId, System.currentTimeMillis(),
                    PrintRemoteBinding.sha256(exactPayload),
                    target);
                if (!writeReprintAttemptStore(store.add(attempt))) {
                    throw new ReprintJournalLockedException(
                        "Cannot persist reprint attempt before POST");
                }
                return attempt;
            } catch (ReprintOutcomeUncertainException | ReprintJournalLockedException expected) {
                throw expected;
            } catch (Exception invalid) {
                throw new ReprintJournalLockedException(
                    "Cannot prepare durable reprint attempt", invalid);
            }
        }
    }

    private boolean markReprintAttemptUncertain(
            PrintReprintAttempt.Attempt attempt) {
        synchronized (REPRINT_JOURNAL_LOCK) {
            if (attempt == null) return false;
            try {
                PrintReprintAttempt.Store store = readReprintAttemptStore();
                if (store.operation(attempt.operationId) == null) return false;
                return writeReprintAttemptStore(store.markUncertain(
                    attempt.operationId, System.currentTimeMillis()));
            } catch (Exception locked) {
                // The durable POSTING record written before the request remains a blocking state even
                // when this best-effort state promotion cannot be committed.
                Diagnostics.append(this, "Reprint uncertain-state commit failed: "
                    + conciseError(locked));
                return false;
            }
        }
    }

    private boolean finishReprintAttempt(
            PrintReprintAttempt.Attempt attempt) {
        synchronized (REPRINT_JOURNAL_LOCK) {
            if (attempt == null) return false;
            try {
                PrintReprintAttempt.Store store = readReprintAttemptStore();
                if (store.operation(attempt.operationId) == null) return false;
                if (writeReprintAttemptStore(store.remove(attempt.operationId))) return true;
                // SharedPreferences updates its process-memory map before commit() reports the
                // disk result. A failed removal must therefore restore the already-durable
                // blocking record in memory; otherwise this process could issue a duplicate even
                // though the old POSTING/UNCERTAIN bytes still survive on disk.
                prefs.edit().putString(REPRINT_ATTEMPTS_KEY, store.serialize()).apply();
                return false;
            } catch (Exception locked) {
                Diagnostics.append(this, "Reprint terminal cleanup failed: "
                    + conciseError(locked));
                return false;
            }
        }
    }

    private boolean reprintTargetBlocked(PrintRemoteBinding target) {
        try {
            return readReprintAttemptStore().blocking(target) != null;
        } catch (ReprintJournalLockedException locked) {
            return true;
        }
    }

    /** One and only one non-idempotent POST, shared by manual and inline reprint paths. */
    private void executeBoundReprint(
            PrintRemoteContext context, PrintRemoteBinding target,
            String requestPhase, String responsePhase) throws Exception {
        requirePrintRemoteBinding(context, target, target.jobId, target.serial, requestPhase);
        byte[] exactPayload = context.api.retryPrintPayload(target.jobId);
        PrintReprintAttempt.Attempt attempt = beginReprintAttempt(target, exactPayload);
        boolean postStarted = false;
        try {
            // The durable POSTING record exists now. Recheck immediately before the socket call so
            // a stale context cannot send merely because journal persistence took time.
            requirePrintRemoteBinding(context, target, target.jobId, target.serial, requestPhase);
            postStarted = true;
            JSONObject response = context.api.retryPrintExact(exactPayload);
            requirePrintRemoteBinding(context, target, target.jobId, target.serial, responsePhase);
            boolean configuredSuccess = context.api.isSuccess(response);
            if (PrintReprintAttempt.responseDisposition(configuredSuccess)
                    != PrintReprintAttempt.ResponseDisposition.CONFIRMED_SUCCESS) {
                // The shared response contract can prove success, but a generic non-success does
                // not prove this non-idempotent POST had no server-side effect. Keep it blocked
                // until a print-specific, Panel-owned definitive-rejection contract exists.
                markReprintAttemptUncertain(attempt);
                throw new ReprintOutcomeUncertainException(
                    "Reprint POST returned an unclassified non-success response: "
                        + context.api.apiErrorMessage(response));
            }
            if (!finishReprintAttempt(attempt)) {
                markReprintAttemptUncertain(attempt);
                throw new ReprintOutcomeUncertainException(
                    "Reprint response arrived but durable terminal cleanup failed");
            }
            return;
        } catch (ReprintOutcomeUncertainException uncertain) {
            throw uncertain;
        } catch (Exception error) {
            if (postStarted) {
                markReprintAttemptUncertain(attempt);
                throw new ReprintOutcomeUncertainException(
                    "Reprint POST outcome is uncertain", error);
            }
            if (!finishReprintAttempt(attempt)) {
                markReprintAttemptUncertain(attempt);
                throw new ReprintJournalLockedException(
                    "Unsent reprint attempt could not be retired", error);
            }
            throw error;
        }
    }

    private String printPolicySha256(ProfileWorkflow workflow) {
        // Fold the review gate into the immutable execution identity. If a hot catalog refresh
        // revokes compatibility review, every in-flight GET/POST guard observes a changed policy.
        return PrintRemoteBinding.policySha256(
            workflow.operationalPoliciesExplicit && workflow.printingEnabled,
            workflow.printingPreflightAction,
            workflow.printingManualReprintEnabled,
            workflow.printingManualReprintRequiresConfirmation,
            workflow.printingManualReprintStatuses,
            workflow.printingConfirmationPolls,
            workflow.printingConfirmationPollIntervalMs,
            workflow.printingMaxAutoReprints,
            workflow.printingFinalRecheckDelayMs,
            workflow.printingOnUnconfirmed,
            workflow.printingBatchEndRecheckMode);
    }

    private PrintRemoteContext capturePrintRemoteContext(long jobId, String sn) {
        String token = savedToken();
        ProfileWorkflow workflow = profileWorkflow();
        JSONObject configSnapshot = copyJsonObject(appConfig);
        JSONObject settingsSnapshot = copyJsonObject(catalogSettings);
        BackendAdapter adapterSnapshot = BackendAdapter.from(
            configSnapshot, settingsSnapshot);
        List<String> capabilityErrors =
            RemoteSideEffectSafetyRules.printingCapabilityErrors(
                workflow, adapterSnapshot);
        if (!capabilityErrors.isEmpty()) {
            throw new IllegalStateException("printing capability is incomplete: "
                + join(capabilityErrors, ", "));
        }
        String backend = backendAdapterFingerprint(configSnapshot, settingsSnapshot);
        PrintRemoteBinding binding = PrintRemoteBinding.capture(
            currentConnectionNamespace(), activeCatalogVersion, currentPanelPairSha256(),
            currentProfileId(), backend, webFingerprint(), token,
            printPolicySha256(workflow), jobId, sn);
        Api capturedApi = api(token, configSnapshot, settingsSnapshot);
        return new PrintRemoteContext(binding, workflow, capturedApi);
    }

    private synchronized boolean printRemoteBindingStillCurrent(PrintRemoteContext context) {
        if (context == null || context.binding == null) return false;
        ProfileWorkflow currentWorkflow = profileWorkflow();
        if (!RemoteSideEffectSafetyRules.printingCapabilityErrors(
                currentWorkflow, endpoints()).isEmpty()) {
            return false;
        }
        return context.binding.sameExecutionContext(
            currentConnectionNamespace(), activeCatalogVersion, currentPanelPairSha256(),
            currentProfileId(), currentBackendAdapterFingerprint(), webFingerprint(), savedToken(),
            printPolicySha256(currentWorkflow));
    }

    private void requirePrintRemoteBinding(PrintRemoteContext context,
                                           PrintRemoteBinding target,
                                           long expectedJobId,
                                           String expectedSerial,
                                           String phase) throws IOException {
        if (target == null || !target.identifies(expectedJobId, expectedSerial)
                || !target.sameExecutionContext(
                context.binding.connectionNamespace, context.binding.catalogVersion,
                context.binding.panelPairSha256, context.binding.profileId,
                context.binding.backendSemanticsSha256, webFingerprint(), savedToken(),
                context.binding.policySha256)
                || !printRemoteBindingStillCurrent(context)) {
            throw new IOException("Print binding changed before " + phase);
        }
    }

    private synchronized boolean beginPrintRemoteWorker(PrintRemoteContext context) {
        if (submitting || mainDraftRemoteWorkerCount > 0 || printRemoteWorkerCount > 0
                || !printRemoteBindingStillCurrent(context)
                || unsafeCandidatesBlockActiveUse()) return false;
        RemoteSideEffectGate.WorkerLease lease =
            RemoteSideEffectGate.tryAcquireWorker(this);
        if (lease == null) return false;
        printRemoteWorkerLease = lease;
        printRemoteWorkerCount++;
        return true;
    }

    private synchronized void endPrintRemoteWorker() {
        if (printRemoteWorkerCount > 0) printRemoteWorkerCount--;
        if (printRemoteWorkerCount == 0 && printRemoteWorkerLease != null) {
            printRemoteWorkerLease.close();
            printRemoteWorkerLease = null;
        }
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
        int remoteStatus = remotePrintStatus(u);
        if (remoteStatus != Integer.MIN_VALUE) return remoteStatus == PRINT_STATUS_PRINTED;
        return "ok".equals(u.optString("printed"));
    }

    private int remotePrintStatus(JSONObject unit) {
        if (unit == null) return Integer.MIN_VALUE;
        if (unit.has(REMOTE_PRINT_STATUS_KEY)) {
            return unit.optInt(REMOTE_PRINT_STATUS_KEY, Integer.MIN_VALUE);
        }
        return unit.optInt(LEGACY_REMOTE_STATUS_KEY, Integer.MIN_VALUE);
    }

    private long remotePrintId(JSONObject unit) {
        if (unit == null) return 0L;
        if (unit.has(REMOTE_PRINT_ID_KEY)) return unit.optLong(REMOTE_PRINT_ID_KEY, 0L);
        return unit.optLong(LEGACY_REMOTE_ID_KEY, 0L);
    }

    private int canonicalPrintStatus(Api api, JSONObject job) {
        BackendAdapter.Printing printing = api.endpoints.printing;
        if (printing.isPrinted(job)) return PRINT_STATUS_PRINTED;
        if (printing.isFailed(job)) return PRINT_STATUS_FAILED;
        if (printing.isOngoing(job)) return PRINT_STATUS_ONGOING;
        return PRINT_STATUS_UNKNOWN;
    }

    private String manualReprintStatusKey(int status) {
        if (status == PRINT_STATUS_FAILED) return ProfileWorkflow.PRINT_STATUS_FAILED;
        if (status == PRINT_STATUS_ONGOING) return ProfileWorkflow.PRINT_STATUS_ONGOING;
        if (status == PRINT_STATUS_UNKNOWN) return ProfileWorkflow.PRINT_STATUS_UNKNOWN;
        return "";
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
        int remoteStatus = remotePrintStatus(u);
        long remoteId = remotePrintId(u);

        String dot, label; int bgc, txtc; boolean canReprint = false;
        if (submitFailed) {
            dot = "⚪"; label = t("ledger_submit_failed"); bgc = 0xFFF8FAFC; txtc = 0xFF64748B;
        } else if (remoteStatus != Integer.MIN_VALUE) {
            if (remoteStatus == PRINT_STATUS_PRINTED) { dot = "🟢"; label = t("print_status_ok"); bgc = 0xFFF0FDF4; txtc = 0xFF15803D; }
            else if (remoteStatus == PRINT_STATUS_FAILED) { dot = "🔴"; label = t("print_status_fail"); bgc = 0xFFFFF1F2; txtc = 0xFFB91C1C; canReprint = remoteId > 0 && profileWorkflow().allowsManualReprint(ProfileWorkflow.PRINT_STATUS_FAILED); }
            else if (remoteStatus == PRINT_STATUS_MISSING) { dot = "🔴"; label = t("print_status_missing"); bgc = 0xFFFFF1F2; txtc = 0xFFB91C1C; }
            else if (remoteStatus == PRINT_STATUS_ONGOING) { dot = "🟡"; label = t("print_status_ongoing"); bgc = 0xFFFFFBEB; txtc = 0xFFB45309; canReprint = remoteId > 0 && profileWorkflow().allowsManualReprint(ProfileWorkflow.PRINT_STATUS_ONGOING); }
            else {
                boolean presentAsOngoing = profileWorkflow()
                    .presentsUnknownPrintStatusAsOngoing();
                dot = presentAsOngoing ? "🟡" : "⚪";
                label = t(presentAsOngoing ? "print_status_ongoing" : "print_status_unknown");
                bgc = presentAsOngoing ? 0xFFFFFBEB : 0xFFF8FAFC;
                txtc = presentAsOngoing ? 0xFFB45309 : 0xFF64748B;
                // Presentation never changes the canonical UNKNOWN policy key.
                canReprint = remoteId > 0 && profileWorkflow().allowsManualReprint(
                    ProfileWorkflow.PRINT_STATUS_UNKNOWN);
            }
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
        // Keep the configured result beside the identifier in the local reconciliation view.
        String grade = u.optString("grade", "");
        if (!grade.isEmpty()) {
            TextView gradeTag = text(resultLabel(grade), 13, true);
            gradeTag.setTextColor(0xFF7C3AED);
            gradeTag.setPadding(dp(8), 0, 0, 0);
            snWrap.addView(gradeTag);
        }
        rowL.addView(snWrap);

        if (canReprint) rowL.addView(button(t("reprint"),
            v -> confirmAndRetryPrint(remoteId, sn, header, list)));
        // A "查无打印任务" row is otherwise a dead end — surface the how-to-recover hint right under it.
        if (remoteStatus == PRINT_STATUS_MISSING) {
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
    private void verifyRoundAgainstCloud(PrintRemoteContext context, JSONObject round,
                                         TextView header, int[] progress, int total,
                                         boolean unconfirmedOnly) throws IOException {
        JSONArray us = round.optJSONArray("units");
        if (us == null) return;
        for (int i = 0; i < us.length(); i++) {
            if (!activityAlive() || !reconcileDialogOpen) return; // activity gone or dialog closed — stop walking SNs
            JSONObject u = us.optJSONObject(i);
            if (u == null || "failed".equals(u.optString("submit"))) continue;
            if (unconfirmedOnly && "ok".equals(u.optString("printed"))) continue; // already confirmed printed — skip the re-query
            String sn = u.optString("sn", "");
            if (sn.isEmpty()) continue;
            long previousJobId = remotePrintId(u);
            PrintRemoteBinding request = context.binding.forJob(previousJobId, sn);
            try {
                requirePrintRemoteBinding(context, request, previousJobId, sn,
                    "print-job GET");
                JSONObject job = latestPrintJobForSnBound(
                    context, previousJobId, request.serial, "print-job");
                if (job == null) {
                    PrintRemoteBinding missing = context.binding.forJob(0L, sn);
                    requirePrintRemoteBinding(context, missing, 0L, sn,
                        "missing print-job ledger write");
                    u.put(REMOTE_PRINT_STATUS_KEY, PRINT_STATUS_MISSING);
                    u.put(REMOTE_PRINT_ID_KEY, 0);
                } else {
                    long returnedJobId = context.api.endpoints.printing.id(job);
                    PrintRemoteBinding response = context.binding.forJob(returnedJobId, sn);
                    requirePrintRemoteBinding(context, response, returnedJobId, sn,
                        "print-job ledger write");
                    u.put(REMOTE_PRINT_STATUS_KEY, canonicalPrintStatus(context.api, job));
                    u.put(REMOTE_PRINT_ID_KEY, returnedJobId);
                }
            } catch (IOException bindingChanged) {
                throw bindingChanged;
            } catch (Exception ignored) {
            }
            final int d = ++progress[0];
            runOnUiThread(() -> {
                if (header != null && printRemoteBindingStillCurrent(context)) {
                    header.setText(t("reconcile_verifying") + " " + d + "/" + total);
                }
            });
        }
    }

    private void confirmAndRetryPrint(long id, String sn, TextView header, LinearLayout list) {
        final PrintRemoteContext context;
        try {
            context = capturePrintRemoteContext(id, sn);
        } catch (Exception error) {
            toast(t("reprint_not_allowed"));
            return;
        }
        if (!context.workflow.printingManualReprintEnabled) {
            toast(t("reprint_not_allowed"));
            return;
        }
        if (!context.workflow.printingManualReprintRequiresConfirmation) {
            retryPrint(context, header, list);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(t("reprint_confirm_title"))
            .setMessage(sn + "\n\n" + t("reprint_confirm_message"))
            .setNegativeButton(t("close"), null)
            .setPositiveButton(t("reprint"), (dialog, which) ->
                retryPrint(context, header, list))
            .show();
    }

    private void retryPrint(PrintRemoteContext context, TextView header, LinearLayout list) {
        if (context == null || !context.workflow.printingManualReprintEnabled
                || !beginPrintRemoteWorker(context)) {
            toast(t("reprint_not_allowed"));
            return;
        }
        toast(t("reprint_sending"));
        new Thread(() -> {
            String msg;
            boolean outcomeUncertain = false;
            try {
                PrintRemoteBinding target = context.binding;
                // A blocked attempt may still perform a read-only exact status query. If that
                // proves this exact job is printed, the shared convergence path retires it; no
                // unresolved attempt is ever allowed to reach another POST.
                JSONObject latest = latestPrintJobForSnBound(
                    context, target.jobId, target.serial, "manual reprint status");
                if (reprintTargetBlocked(target)) {
                    throw new ReprintOutcomeUncertainException(
                        "This print job already has an unresolved reprint POST");
                }
                int currentStatus = latest == null
                    ? PRINT_STATUS_MISSING : canonicalPrintStatus(context.api, latest);
                if (latest == null
                        || context.api.endpoints.printing.id(latest) != target.jobId
                        || !context.workflow.allowsManualReprint(
                            manualReprintStatusKey(currentStatus))) {
                    runOnUiThread(() -> {
                        if (printRemoteBindingStillCurrent(context)) {
                            toast(t("reprint_not_allowed"));
                        }
                    });
                    endPrintRemoteWorker();
                    return;
                }
                requirePrintRemoteBinding(context, target, target.jobId, target.serial,
                    "manual reprint POST");
                executeBoundReprint(context, target,
                    "manual reprint POST", "manual reprint response");
                msg = t("reprint_done");
            } catch (ReprintOutcomeUncertainException uncertain) {
                outcomeUncertain = true;
                msg = t("reprint_result_uncertain");
                appendLog("print retry result uncertain; manual verification required: "
                    + conciseError(uncertain));
                FailureReporter.get().report(
                        "print", "reprint_api_error", "print_adapter", uncertain);
            } catch (Exception e) {
                msg = e instanceof ReprintJournalLockedException
                    ? t("reprint_result_uncertain") : t("print_reconcile_binding_changed");
                outcomeUncertain = e instanceof ReprintJournalLockedException;
                appendLog("print retry stopped before POST: "
                    + conciseError(e));
                FailureReporter.get().report(
                        "print", "reprint_api_error", "print_adapter", e);
            }
            final String fmsg = msg;
            final boolean funcertain = outcomeUncertain;
            final boolean current = printRemoteBindingStillCurrent(context);
            endPrintRemoteWorker();
            runOnUiThread(() -> {
                if (!activityAlive()) return;
                // Outcome-uncertain is a safety warning, not stale operational UI. Show it even if
                // the Panel/session changed after the POST; suppressing it would invite a duplicate.
                toast(fmsg);
                if (!funcertain && current && printRemoteBindingStillCurrent(context)
                        && reconcileDialogOpen) {
                    loadPrintReconcile(header, list);
                }
            });
        }, "manual-reprint").start();
    }

    // Called inline during the submit loop, right after a unit submits. Polls for that unit's label to
    // resolve and reprints it only within the selected profile's explicit safety-bounded policy.
    // A job that never appears can be deferred to the batch-end pass instead of being reported prematurely.
    private PrintConfirmationRules.Result confirmPrintInline(
            PrintRemoteContext context, UnitRecord unit, boolean deferMissingJob)
            throws BackendSessionErrors.SessionInvalidException {
        if (context == null || unit == null || unit.sn == null || unit.sn.isEmpty()) {
            return PrintConfirmationRules.Result.MISSING;
        }
        String sn = unit.sn;
        ProfileWorkflow workflow = context.workflow;
        Api api = context.api;
        boolean confirmedPrinted = false; // only a real status==1 clears this — everything else is a potential lost unit
        boolean jobEverSeen = false;      // distinguishes "printer offline, job never created" from "job exists but failed"
        boolean reprintOutcomeUncertain = false;
        int reprints = 0;
        try {
            for (int poll = 1; poll <= workflow.printingConfirmationPolls; poll++) {
                setSubmitProgressMessage(t("confirming_print") + " " + sn);
                Thread.sleep(workflow.printingConfirmationPollIntervalMs);
                JSONObject job = latestPrintJobForSnBound(
                    context, 0L, sn, "inline print-job");
                if (job == null) continue; // print job not created yet — keep polling
                jobEverSeen = true;
                long id = api.endpoints.printing.id(job);
                PrintRemoteBinding target = context.binding.forJob(id, sn);
                requirePrintRemoteBinding(context, target, id, sn,
                    "inline print status classification");
                if (api.endpoints.printing.isPrinted(job)) {
                    confirmedPrinted = true;
                    break;
                }
                if (api.endpoints.printing.isFailed(job)
                        && reprints < workflow.printingMaxAutoReprints) {
                    if (id > 0) {
                        reprints++;
                        appendUnitLog(unit, t("inline_reprint_log") + reprints);
                        try {
                            executeBoundReprint(
                                context, target, "inline reprint POST",
                                "inline reprint response");
                        } catch (ReprintOutcomeUncertainException
                                 | ReprintJournalLockedException uncertain) {
                            reprintOutcomeUncertain = true;
                            appendUnitLog(unit, t("inline_reprint_uncertain"));
                            break;
                        } catch (Exception error) {
                            BackendSessionErrors.SessionInvalidException invalid =
                                    BackendSessionErrors.find(error);
                            if (invalid != null) throw invalid;
                            appendUnitLog(unit, t("print_reconcile_binding_changed"));
                            break;
                        }
                    }
                }
                // status 3 (ongoing) or just reprinted -> keep polling for it to finish
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            BackendSessionErrors.SessionInvalidException invalid = BackendSessionErrors.find(error);
            if (invalid != null) throw invalid;
            appendUnitLog(unit, t("print_reconcile_binding_changed"));
        }
        if (confirmedPrinted && !printRemoteBindingStillCurrent(context)) {
            // A stale callback cannot turn the durable ledger green.
            confirmedPrinted = false;
            jobEverSeen = true;
        }
        PrintConfirmationRules.Result result = PrintConfirmationRules.classify(
            confirmedPrinted, jobEverSeen, reprintOutcomeUncertain);
        if (result == PrintConfirmationRules.Result.FAILED) {
            appendUnitLog(unit, String.format(java.util.Locale.ROOT,
                t("inline_reprint_gaveup"), workflow.printingMaxAutoReprints));
        } else if (result == PrintConfirmationRules.Result.MISSING) {
            appendUnitLog(unit, deferMissingJob ? t("inline_print_deferred") : t("inline_print_no_job"));
        }
        return result;
    }

    // Recheck only the units whose print job never appeared inline. The first pass is immediate after
    // the final submission. If any jobs are still missing, wait once for the whole batch on this worker
    // thread, then check only that subset again. A late status==1 upgrades the in-memory ledger before
    // it is persisted; a late failed/ongoing job re-enters the normal confirmation/reprint loop; only
    // SNs still unconfirmed after the delayed pass reach the final batch alert.
    private void recheckDeferredPrintsAtBatchEnd(
                                                  PrintRemoteContext context,
                                                  List<UnitRecord> deferredUnits,
                                                  List<String> failedSns, List<JSONObject> roundLedger)
            throws BackendSessionErrors.SessionInvalidException {
        if (deferredUnits == null || deferredUnits.isEmpty()) return;

        List<UnitRecord> stillMissing = new ArrayList<>();
        for (UnitRecord unit : deferredUnits) {
            if (unit == null || unit.sn == null || unit.sn.isEmpty()) continue;
            String sn = unit.sn;
            setSubmitProgressMessage(t("final_print_recheck") + " " + sn);
            PrintConfirmationRules.Result result = recheckDeferredPrintUnit(
                context, unit, false);
            if (result == PrintConfirmationRules.Result.PRINTED) {
                markRoundLedgerPrinted(context, roundLedger, sn);
            } else if (PrintConfirmationRules.shouldWaitForDelayedBatchCheck(result)) {
                stillMissing.add(unit);
            } else if (!failedSns.contains(sn)) {
                failedSns.add(sn);
            }
        }

        if (stillMissing.isEmpty()) return;

        long delay = context.workflow.printingFinalRecheckDelayMs;
        setSubmitProgressMessage(String.format(java.util.Locale.ROOT,
            t("final_print_recheck_wait"), delay));
        if (delay > 0L) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (!printRemoteBindingStillCurrent(context)) {
            for (UnitRecord unit : stillMissing) {
                if (unit != null && unit.sn != null && !failedSns.contains(unit.sn)) {
                    failedSns.add(unit.sn);
                }
            }
            return;
        }

        for (UnitRecord unit : stillMissing) {
            if (unit == null || unit.sn == null || unit.sn.isEmpty()) continue;
            String sn = unit.sn;
            setSubmitProgressMessage(t("final_print_recheck_after_wait") + " " + sn);
            PrintConfirmationRules.Result result = recheckDeferredPrintUnit(
                context, unit, true);

            if (result == PrintConfirmationRules.Result.PRINTED) {
                markRoundLedgerPrinted(context, roundLedger, sn);
            } else if (PrintConfirmationRules.shouldAlertAfterFinalBatchCheck(result)
                    && !failedSns.contains(sn)) {
                failedSns.add(sn);
            }
        }
    }

    private PrintConfirmationRules.Result finalPrintCheckBeforeStopping(
            PrintRemoteContext context, UnitRecord unit)
            throws BackendSessionErrors.SessionInvalidException {
        long delay = context.workflow.printingFinalRecheckDelayMs;
        if (delay > 0L) {
            setSubmitProgressMessage(String.format(java.util.Locale.ROOT,
                t("final_print_recheck_wait"), delay));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return PrintConfirmationRules.Result.MISSING;
            }
        }
        if (!printRemoteBindingStillCurrent(context)) {
            return PrintConfirmationRules.Result.UNCERTAIN;
        }
        return recheckDeferredPrintUnit(context, unit, true);
    }

    private PrintConfirmationRules.Result recheckDeferredPrintUnit(
                                                                     PrintRemoteContext context,
                                                                     UnitRecord unit,
                                                                     boolean finalAttempt)
            throws BackendSessionErrors.SessionInvalidException {
        try {
            JSONObject job = latestPrintJobForSnBound(
                context, 0L, unit.sn, "deferred print-job");
            if (job == null) {
                if (finalAttempt) appendUnitLog(unit, t("inline_print_no_job"));
                return PrintConfirmationRules.Result.MISSING;
            }
            if (context.api.endpoints.printing.isPrinted(job)) {
                long id = context.api.endpoints.printing.id(job);
                requirePrintRemoteBinding(context, context.binding.forJob(id, unit.sn),
                    id, unit.sn, "deferred print ledger result");
                appendUnitLog(unit, t("inline_print_late_confirmed"));
                return PrintConfirmationRules.Result.PRINTED;
            }
            // The job appeared late but is failed/ongoing. Give the existing retry loop its normal
            // opportunity instead of turning the late appearance into an immediate alert.
            return confirmPrintInline(context, unit, !finalAttempt);
        } catch (Exception error) {
            BackendSessionErrors.SessionInvalidException invalid = BackendSessionErrors.find(error);
            if (invalid != null) throw invalid;
            if (finalAttempt) {
                appendUnitLog(unit, t("print_reconcile_failed") + conciseError(error));
            }
            return printRemoteBindingStillCurrent(context)
                ? PrintConfirmationRules.Result.MISSING
                : PrintConfirmationRules.Result.UNCERTAIN;
        }
    }

    private void markRoundLedgerPrinted(PrintRemoteContext context,
                                        List<JSONObject> roundLedger, String sn) {
        if (roundLedger == null || sn == null || sn.isEmpty()) return;
        try {
            PrintRemoteBinding target = context.binding.forJob(0L, sn);
            requirePrintRemoteBinding(context, target, 0L, sn,
                "inline ledger printed write");
        } catch (Exception stale) {
            return;
        }
        for (JSONObject unit : roundLedger) {
            if (unit == null || !sn.equals(unit.optString("sn", ""))) continue;
            if (!"ok".equals(unit.optString("submit", ""))) return;
            try {
                unit.put("printed", "ok");
            } catch (JSONException ignored) {
            }
            return;
        }
    }

    private void downgradeRoundLedgerPrintProof(List<JSONObject> roundLedger) {
        if (roundLedger == null) return;
        for (JSONObject unit : roundLedger) {
            if (unit == null || !"ok".equals(unit.optString("submit", ""))) continue;
            try {
                unit.put("printed", "unconfirmed");
            } catch (JSONException ignored) {
            }
        }
    }

    private void mergeUnconfirmedRoundLedgerSns(List<JSONObject> roundLedger, List<String> failedSns) {
        if (roundLedger == null || failedSns == null) return;
        for (JSONObject unit : roundLedger) {
            if (unit == null || !PrintConfirmationRules.isSubmittedButUnconfirmed(
                    unit.optString("submit", ""), unit.optString("printed", ""))) {
                continue;
            }
            String sn = unit.optString("sn", "");
            if (!sn.isEmpty() && !failedSns.contains(sn)) failedSns.add(sn);
        }
    }

    // Newest accepted print job whose configured serial field matches this record.
    private JSONObject latestPrintJobForSnBound(PrintRemoteContext context,
                                                long expectedJobId, String sn,
                                                String phase) throws Exception {
        PrintRemoteBinding request = context.binding.forJob(expectedJobId, sn);
        requirePrintRemoteBinding(
            context, request, expectedJobId, sn, phase + " GET");
        PrintJobLookup lookup = latestPrintJobForSn(context.api, request.serial);
        requirePrintRemoteBinding(
            context, request, expectedJobId, sn, phase + " response");
        JSONObject job = lookup.job;
        if (job != null) {
            long returnedJobId = context.api.endpoints.printing.id(job);
            PrintRemoteBinding response = context.binding.forJob(returnedJobId, sn);
            requirePrintRemoteBinding(context, response, returnedJobId, sn,
                phase + " classification");
            resolveConfirmedPrintedReprint(
                context, response, lookup.response, job, phase);
        }
        return job;
    }

    private PrintJobLookup latestPrintJobForSn(Api api, String sn) throws Exception {
        JSONObject body = api.getEndpointJson(BackendAdapter.ENDPOINT_MESSAGE_LIST,
            api.endpoints.printing.jobQuery(enc(sn)));
        if (!api.isSuccess(body)) {
            throw new IOException(api.apiErrorMessage(body));
        }
        JSONArray data = api.endpoints.printing.jobs(body);
        if (data == null) return new PrintJobLookup(body, null);
        JSONObject best = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it == null || !api.endpoints.printing.accepts(it)) continue;
            if (!api.endpoints.printing.serialMatches(it, sn)) continue;
            if (best == null || api.endpoints.printing.id(it) > api.endpoints.printing.id(best)) best = it;
        }
        return new PrintJobLookup(body, best);
    }

    private boolean resolveConfirmedPrintedReprint(
            PrintRemoteContext context, PrintRemoteBinding observedTarget,
            JSONObject response, JSONObject job, String phase) throws IOException {
        if (context == null || observedTarget == null || job == null
                || observedTarget.jobId <= 0L
                || !context.api.endpoints.printing.serialMatches(
                    job, observedTarget.serial)) return false;
        boolean responseSuccess = context.api.isSuccess(response);
        boolean printed = context.api.endpoints.printing.isPrinted(job);
        if (!responseSuccess || !printed) return false;
        requirePrintRemoteBinding(context, observedTarget, observedTarget.jobId,
            observedTarget.serial, phase + " confirmed-printed journal cleanup");
        synchronized (REPRINT_JOURNAL_LOCK) {
            try {
                PrintReprintAttempt.Store store = readReprintAttemptStore();
                PrintReprintAttempt.Attempt attempt = store.confirmedPrintedResolution(
                    observedTarget, responseSuccess, printed);
                if (attempt == null) return false;
                // Recheck immediately before the synchronous removal. A cleanup failure restores
                // the blocking in-memory record and the durable journal remains unresolved.
                requirePrintRemoteBinding(context, observedTarget, observedTarget.jobId,
                    observedTarget.serial, phase + " confirmed-printed journal commit");
                if (!finishReprintAttempt(attempt)) return false;
                Diagnostics.append(this,
                    "Resolved uncertain reprint from exact confirmed-printed status");
                return true;
            } catch (ReprintJournalLockedException locked) {
                Diagnostics.append(this, "Confirmed-printed reprint remains locked: "
                    + conciseError(locked));
                return false;
            }
        }
    }

    // One merged report per batch for labels that remain unconfirmed after inline checks plus the
    // final batch pass. The stable stage/error category lets the notification receiver group repeats.
    // Record identifiers remain only in the local operator log; FailureReporter sends aggregate-safe
    // structured runtime state and never receives this list.
    private void reportInlinePrintFailures(List<String> sns) {
        if (sns == null || sns.isEmpty()) return;
        String snList = join(sns, ", ");
        appendLog("PRINT UNCONFIRMED after final batch check: " + snList);
        FailureReporter.get().report(
                "print", "label_failed_after_retry", "print_adapter", null);
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

    private static final class SubmissionOutcomeUncertainException extends IOException {
        SubmissionOutcomeUncertainException() {
            // Deliberately omit the transport exception/cause. The outer retry classifier must not
            // automatically replay a POST whose server outcome is unknown.
            super("submission result requires exact confirmation");
        }
    }

    /** A parsed backend response which the active profile declares as definitely not written. */
    private static final class SubmissionExplicitlyRejectedException extends IOException {
        SubmissionExplicitlyRejectedException(String message) {
            super(message == null || message.trim().isEmpty()
                ? "submission was rejected" : message);
        }
    }

    private static final class PreviousStepSubmissionOutcomeUncertainException
            extends IOException {
        PreviousStepSubmissionOutcomeUncertainException() {
            super("previous-step result requires exact manual confirmation");
        }
    }

    static final class PreviousStepLookupUnclassifiedException extends IOException {
        PreviousStepLookupUnclassifiedException(String backendMessage) {
            super("previous-step lookup response is not classified as missing: "
                + (backendMessage == null ? "" : backendMessage));
        }
    }

    private static final class SubmissionAcknowledgedRecoveryException extends IOException {
        SubmissionAcknowledgedRecoveryException() {
            super("submission acknowledged; local recovery required");
        }
    }

    private static final class UploadReplayBarrierRetirementException extends IOException {
        UploadReplayBarrierRetirementException() {
            super("upload replay barrier retirement requires local recovery");
        }
    }

    private static final class SubmissionTerminalRecoveryException extends IOException {
        SubmissionTerminalRecoveryException() {
            super("terminal unit state requires local recovery");
        }
    }

    private static final class SubmissionJournalLockedException extends IOException {
        SubmissionJournalLockedException(String message) {
            super(message);
        }
    }

    private static final class JournaledSubmissionResponse {
        final JSONObject response;
        final AlternateSubmissionAttempt posting;
        final AlternateSubmissionAttempt.Key key;

        JournaledSubmissionResponse(JSONObject response,
                                    AlternateSubmissionAttempt posting,
                                    AlternateSubmissionAttempt.Key key) {
            this.response = response;
            this.posting = posting;
            this.key = key;
        }
    }

    private JournaledSubmissionResponse postMainSubmissionOnce(
            Api api, UnitRecord unit, JSONObject payload,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        if (!mainDraftSubmissionAllowed(expectedDraftBinding)) {
            throw new SubmissionJournalLockedException(
                "Draft binding changed before POST");
        }
        if (!saveDraft(true)) {
            throw new SubmissionJournalLockedException(
                "Could not persist submission source");
        }
        if (!mainDraftSubmissionAllowed(expectedDraftBinding)) {
            throw new SubmissionJournalLockedException(
                "Persisted draft binding changed before POST");
        }
        String binding = mainSubmissionBindingFingerprint();
        if (binding.isEmpty()) {
            throw new BackendAdapter.ConfigurationException(
                "active submission binding fingerprint");
        }
        byte[] exactRequestBody = payload.toString().getBytes(StandardCharsets.UTF_8);
        ActiveMainUploadBarrier uploadContext = activeMainUploadBarrier.get();
        String operationId = uploadContext == null
            ? java.util.UUID.randomUUID().toString() : uploadContext.operationId;
        AlternateSubmissionAttempt.Key key = AlternateSubmissionAttempt.Key.of(
            currentConnectionNamespace(), binding, mainSubmissionTargetIdentity(), unit.sn,
            mainSubmissionSourceSnapshotSha256(unit),
            AlternateSubmissionAttempt.payloadSha256(exactRequestBody),
            operationId);
        AlternateSubmissionAttempt.RestoreResult slot = restoreMainSubmissionAttempt();
        if (!DURABLE_FINAL_SUBMISSION_REPLAY_BARRIER_ENABLED) {
            discardReplayableFinalSubmissionAttempt(slot, true);
            slot = AlternateSubmissionAttempt.restoreStoredValue(false, null);
        }
        AlternateSubmissionAttempt attempt = AlternateSubmissionAttempt.prepare(key, slot);
        if (!writeMainSubmissionAttempt(attempt)) {
            throw new SubmissionJournalLockedException(
                "Could not persist submission intent");
        }
        attempt = attempt.beginPosting(key);
        if (!writeMainSubmissionAttempt(attempt)) {
            throw new SubmissionJournalLockedException(
                "Could not persist submission start");
        }
        try {
            JSONObject response = api.postEndpointJsonExact(
                BackendAdapter.ENDPOINT_SUBMIT_ENTRY, exactRequestBody);
            return new JournaledSubmissionResponse(response, attempt, key);
        } catch (Exception transportOrResponseError) {
            if (!DURABLE_FINAL_SUBMISSION_REPLAY_BARRIER_ENABLED) {
                clearMainSubmissionAttempt();
                // Preserve the original exception so the normal UI reports the real network,
                // HTTP or JSON problem and the next operator submit remains available.
                throw transportOrResponseError;
            }
            if (BackendSessionErrors.isSessionInvalid(transportOrResponseError)) {
                // A session rejection proves only that credentials are no longer usable. It does
                // not prove that this exact POST was not committed before the rejection arrived.
                writeMainSubmissionAttempt(attempt.markUncertain(key));
                throw transportOrResponseError;
            }
            // POSTING was durably written first. Even if this write fails, process restore turns the
            // retained POSTING record into UNCERTAIN before any future submission can start.
            writeMainSubmissionAttempt(attempt.markUncertain(key));
            throw new SubmissionOutcomeUncertainException();
        }
    }

    private void confirmMainSubmissionRejected(
            JournaledSubmissionResponse result) throws SubmissionJournalLockedException {
        AlternateSubmissionAttempt rejected = result.posting.markServerRejected(result.key);
        if (!writeMainSubmissionAttempt(rejected) || !clearMainSubmissionAttempt()) {
            throw new SubmissionJournalLockedException(
                "Could not persist explicit submission rejection");
        }
    }

    private void markMainSubmissionUncertain(
            JournaledSubmissionResponse result) throws SubmissionOutcomeUncertainException {
        writeMainSubmissionAttempt(result.posting.markUncertain(result.key));
        throw new SubmissionOutcomeUncertainException();
    }

    private void completeMainSubmission(
            JournaledSubmissionResponse result, UnitRecord unit,
            Set<String> removed, Set<String> submittedMissingCandidates,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            ProfileWorkflow workflow)
            throws SubmissionAcknowledgedRecoveryException {
        AlternateSubmissionAttempt completed =
            result.posting.markPostAcknowledged(result.key);
        boolean receiptSaved = writeMainSubmissionAttempt(completed);
        PreviousStepSubmissionAttempt.ChainIdentity previousStepChain = null;
        boolean previousStepChainReady = receiptSaved;
        if (receiptSaved) {
            try {
                previousStepChain = previousStepSubmissionChainForResolvedUnit(
                    unit, expectedDraftBinding, workflow);
            } catch (Exception error) {
                previousStepChainReady = false;
                Diagnostics.append(this,
                    "Completed main submission previous-step identity failed: "
                        + conciseError(error));
            }
        }
        unit.status = "success";
        recordDailyOutput(unit);
        appendUnitLog(unit, t("submitted"));
        removeResolvedSubmittedMissingMaterials(removed, submittedMissingCandidates);
        boolean sourceSaved = persistExactPreviousStepTerminal(
            unit, expectedDraftBinding, previousStepChain, workflow);
        // Keep the completed-prefix receipt until both the final POST acknowledgement and the
        // terminal local unit state are durable. A crash at any earlier point therefore retains at
        // least one recovery record which prevents a recipe replay.
        boolean previousStepReceiptCleared = receiptSaved && sourceSaved
            && previousStepChainReady
            && clearPreviousStepSubmissionAttemptForResolvedChain(previousStepChain);
        ActiveMainUploadBarrier uploadContext = activeMainUploadBarrier.get();
        boolean uploadBarrierStarted = uploadContext != null
            && uploadContext.startedIdentity != null;
        boolean mainReceiptHandled;
        if (uploadBarrierStarted) {
            // Keep COMPLETED until the exact linked upload barrier is retired. A process death in
            // this interval therefore leaves two independent records sharing one operation id.
            mainReceiptHandled = receiptSaved
                && UploadReplayRecoveryRules.completedMain(
                    uploadContext.startedIdentity, completed);
        } else {
            mainReceiptHandled = receiptSaved && clearMainSubmissionAttempt();
        }
        // Clearing is safe only after the local queue durably records success. If any write fails,
        // retain the strongest available receipt; restore then blocks or completes cleanup without
        // issuing another POST.
        boolean cleared = sourceSaved && previousStepReceiptCleared
            && mainReceiptHandled;
        if (!cleared) {
            Diagnostics.append(this, "Completed main submission needs local recovery"
                + " receipt=" + receiptSaved + " source=" + sourceSaved
                + " previousStep=" + previousStepReceiptCleared);
            runOnUiThread(this::refreshFormUi);
            throw new SubmissionAcknowledgedRecoveryException();
        }
        runOnUiThread(this::refreshFormUi);
    }

    private ActiveMainUploadBarrier captureMainUploadBarrier(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        if (blockingUploadReplayBarrier() != null) {
            throw new SubmissionJournalLockedException(
                "An upload replay barrier is already present");
        }
        if (api == null || unit == null || expectedDraftBinding == null
                || !mainDraftSubmissionAllowed(expectedDraftBinding)) {
            throw new SubmissionJournalLockedException(
                "Upload binding is unavailable");
        }
        String connection = currentConnectionNamespace();
        int catalogVersion = activeCatalogVersion;
        String pair = currentPanelPairSha256();
        String binding = mainSubmissionBindingFingerprint();
        String backend = currentBackendAdapterFingerprint();
        String session = OperationBindingRules.sessionFingerprint(
            api.webFingerprint, api.token);
        String operationId = java.util.UUID.randomUUID().toString();
        // Constructing the final identity at the first upload validates the source snapshot too.
        // Validate every already-frozen digest now so a malformed context fails before read-only
        // preparation can accidentally be treated as a reusable upload operation.
        UploadReplayBarrier.Identity.main(connection, catalogVersion,
            currentProfileId(), pair, binding, backend, session,
            AlternateSubmissionAttempt.payloadSha256("upload-context-placeholder"),
            operationId);
        return new ActiveMainUploadBarrier(
            unit, expectedDraftBinding, connection, catalogVersion, pair, binding,
            backend, session, operationId);
    }

    private void beginActiveMainUploadBarrier(ActiveMainUploadBarrier context)
            throws Exception {
        if (context == null || context != activeMainUploadBarrier.get()) {
            throw new SubmissionJournalLockedException(
                "Upload context is not active");
        }
        if (context.startedIdentity != null) {
            if (!uploadReplayBarrierMatches(context.startedIdentity)) {
                throw new SubmissionJournalLockedException(
                    "Upload replay barrier is no longer exact");
            }
            return;
        }
        requireMainDraftRemoteBinding(
            context.draftBinding, "upload replay barrier persistence");
        if (!saveDraft(true)) {
            throw new SubmissionJournalLockedException(
                "Could not persist upload source draft");
        }
        requireMainDraftRemoteBinding(
            context.draftBinding, "upload replay barrier start");
        UploadReplayBarrier.Identity identity = UploadReplayBarrier.Identity.main(
            context.connectionNamespace, context.catalogVersion,
            context.draftBinding.profileId, context.panelPairSha256,
            context.bindingFingerprintSha256, context.backendFingerprintSha256,
            context.sessionFingerprintSha256,
            previousStepSourceSnapshotSha256(context.unit), context.operationId);
        if (!beginUploadReplayBarrier(identity)) {
            throw new SubmissionJournalLockedException(
                "Could not persist upload replay barrier");
        }
        context.startedIdentity = identity;
        context.networkRetryGate.markUploadStarted();
    }

    private void finishActiveMainUploadBarrier(ActiveMainUploadBarrier context)
            throws SubmissionAcknowledgedRecoveryException,
                   UploadReplayBarrierRetirementException {
        if (context == null || context.startedIdentity == null) return;
        if (!clearUploadReplayBarrier(context.startedIdentity)) {
            throw new UploadReplayBarrierRetirementException();
        }
        if ("success".equals(context.unit.status)) {
            AlternateSubmissionAttempt.RestoreResult receipt =
                restoreMainSubmissionAttempt();
            if (receipt.kind != AlternateSubmissionAttempt.RestoreKind.RESTORED
                    || !UploadReplayRecoveryRules.completedMain(
                        context.startedIdentity, receipt.attempt)
                    || !clearMainSubmissionAttempt()) {
                throw new SubmissionAcknowledgedRecoveryException();
            }
        }
    }

    private void runWithMainUploadBarrier(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            SubmissionAction action) throws Exception {
        if (activeMainUploadBarrier.get() != null) {
            throw new IllegalStateException("nested upload replay context");
        }
        ActiveMainUploadBarrier context = captureMainUploadBarrier(
            api, unit, expectedDraftBinding);
        activeMainUploadBarrier.set(context);
        try {
            action.run();
            finishActiveMainUploadBarrier(context);
        } finally {
            activeMainUploadBarrier.remove();
        }
    }

    private void runWithSubmissionNetworkRetry(
            SubmissionAction action, String label, UnitRecord unit, int position,
            ProfileWorkflow workflow, Api api,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        if (activeMainUploadBarrier.get() != null) {
            throw new IllegalStateException("nested whole-unit network retry");
        }
        ActiveMainUploadBarrier context = captureMainUploadBarrier(
            api, unit, expectedDraftBinding);
        activeMainUploadBarrier.set(context);
        try {
            int retries = 0;
            while (true) {
                try {
                    action.run();
                    finishActiveMainUploadBarrier(context);
                    if (retries > 0) {
                        setSubmitProgressMessage(t("submit_loading"));
                    }
                    return;
                } catch (Exception exc) {
                    if (exc instanceof SubmissionExplicitlyRejectedException) {
                        // All upload calls completed and the final parsed response is declared
                        // not-written by this profile. The URLs are not persisted for a later run,
                        // so retire the exact upload barrier and keep the source draft retryable.
                        finishActiveMainUploadBarrier(context);
                        throw exc;
                    }
                    if (!Api.isTransientApiNetworkError(exc)) {
                        if (retries > 0) {
                            setSubmitProgressMessage(t("submit_loading"));
                        }
                        throw exc;
                    }
                    if (unit != null && Api.isDnsResolveError(exc)) {
                        recordDnsAffected(unit, position, exc);
                    }
                    // An upload may have reached the server even when its response was lost.  The
                    // caller may retry read-only preparation, but must never replay the whole unit
                    // after the first upload attempt has begun.
                    if (!context.networkRetryGate.canRetryWholeUnit()
                            || retries >= workflow.submissionNetworkRetryMaxAttempts) {
                        throw exc;
                    }
                    retries++;
                    long delay = computeSubmissionNetworkRetryDelay(retries, workflow);
                    long seconds = Math.max(1L, delay / 1000L);
                    String prefix = label == null || label.isEmpty() ? "" : label + " ";
                    appendLog(prefix + t("network_retry_log_prefix") + "#" + retries + " (" + seconds + "s) " + conciseError(exc));
                    setSubmitProgressMessage(t("network_retrying_status") + " #" + retries);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        } finally {
            activeMainUploadBarrier.remove();
        }
    }

    /**
     * Applies the profile-owned finite network retry budget to the one read-only material refresh
     * that runs before a batch.  This deliberately has no upload context: the only caller is the
     * template-detail GET, and a failed attempt cannot have created a remote side effect.
     */
    private void runWithPreUploadNetworkRetry(
            SubmissionAction action, ProfileWorkflow workflow) throws Exception {
        int retries = 0;
        while (true) {
            try {
                action.run();
                if (retries > 0) setSubmitProgressMessage(t("submit_loading"));
                return;
            } catch (Exception exc) {
                if (!Api.isTransientApiNetworkError(exc)
                        || retries >= workflow.submissionNetworkRetryMaxAttempts) {
                    if (retries > 0) setSubmitProgressMessage(t("submit_loading"));
                    throw exc;
                }
                retries++;
                long delay = computeSubmissionNetworkRetryDelay(retries, workflow);
                long seconds = Math.max(1L, delay / 1000L);
                appendLog(t("network_retry_log_prefix") + "#" + retries + " ("
                    + seconds + "s) " + conciseError(exc));
                setSubmitProgressMessage(t("network_retrying_status") + " #" + retries);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }

    private String uploadImageWithReplayBarrier(
            Api api, File file, String uploadName) throws Exception {
        ActiveMainUploadBarrier context = activeMainUploadBarrier.get();
        if (context == null) {
            throw new SubmissionJournalLockedException(
                "Main upload has no replay barrier context");
        }
        beginActiveMainUploadBarrier(context);
        return api.uploadImage(file, uploadName);
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
        // This method runs on every inner retry. Do not file a failure here: a later retry may (and
        // often does) succeed. The affected unit remains visible in the final operator dialog; only
        // an exception that ultimately escapes the retry path is eligible for reportSubmitFailure.
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
        FailureReporter.get().report(stage, errCode, "submit_unit", throwable);
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

    private long computeSubmissionNetworkRetryDelay(int attempt, ProfileWorkflow workflow) {
        long delay = workflow.submissionNetworkRetryBaseDelayMs;
        for (int i = 1; i < attempt && delay < workflow.submissionNetworkRetryMaxDelayMs; i++) {
            delay = Math.min(delay * 2L, workflow.submissionNetworkRetryMaxDelayMs);
        }
        return Math.min(delay, workflow.submissionNetworkRetryMaxDelayMs);
    }

    private void submitUnit(Api api, UnitRecord unit,
                            MainDraftSnapshotRules.Binding expectedDraftBinding,
                            ProfileWorkflow workflow)
            throws Exception {
        if (!mainDraftSubmissionAllowed(expectedDraftBinding)) {
            throw new SubmissionJournalLockedException(
                "Draft binding changed before remote preparation");
        }
        if (workflow.previousStepsEnabled) {
            ensurePreviousSteps(api, unit, expectedDraftBinding, workflow);
        }
        if (workflow.duplicateCheckEnabled) {
            appendUnitLog(unit, t("checking_duplicate"));
            JSONArray existing = checkDuplicate(api, unit.sn);
            if (existing.length() > 0) {
                DuplicateHistory history = duplicateHistory(existing,
                    api.endpoints.operations.duplicateCheck);
                boolean dateKnown = history.latestMillis != Long.MIN_VALUE;
                boolean eligible = dateKnown && workflow.isDuplicateEligible(
                    history.latestMillis, System.currentTimeMillis(),
                    api.endpoints.operations.duplicateCheck.duplicateAgeTimeZone());
                String action = dateKnown
                    ? (eligible ? workflow.duplicateEligibleAction : workflow.duplicateRecentAction)
                    : workflow.duplicateUnknownDateAction;
                if (!eligible && ProfileWorkflow.ACTION_SKIP_AS_SUBMITTED.equals(action)) {
                    PreviousStepSubmissionAttempt.ChainIdentity terminalChain =
                        previousStepSubmissionChainForResolvedUnit(
                            unit, expectedDraftBinding, workflow);
                    appendUnitLog(unit, t("already_submitted") + duplicateHistoryLogSuffix(history));
                    unit.status = "already_submitted";
                    recordDailyOutput(unit);
                    boolean terminalSaved = persistExactPreviousStepTerminal(
                        unit, expectedDraftBinding, terminalChain, workflow);
                    boolean receiptCleared = terminalSaved
                        && clearPreviousStepSubmissionAttemptForResolvedChain(
                            terminalChain);
                    runOnUiThread(this::refreshFormUi);
                    if (!terminalSaved || !receiptCleared) {
                        throw new SubmissionTerminalRecoveryException();
                    }
                    return;
                }
                if (ProfileWorkflow.ACTION_BLOCK.equals(action)) {
                    if (!dateKnown) throw new IOException(t("duplicate_date_unavailable"));
                    throw new IOException(t("duplicate_blocked") + duplicateHistoryLogSuffix(history));
                }
                String submissionName = duplicateSubmissionName(existing.length() + 1);
                if (ProfileWorkflow.ACTION_CONFIRM.equals(action)
                        && !confirmDuplicateSubmission(unit.sn, submissionName, history)) {
                    PreviousStepSubmissionAttempt.ChainIdentity terminalChain =
                        previousStepSubmissionChainForResolvedUnit(
                            unit, expectedDraftBinding, workflow);
                    appendUnitLog(unit, t("duplicate_skipped"));
                    unit.status = "duplicate_skipped";
                    boolean terminalSaved = persistExactPreviousStepTerminal(
                        unit, expectedDraftBinding, terminalChain, workflow);
                    boolean receiptCleared = terminalSaved
                        && clearPreviousStepSubmissionAttemptForResolvedChain(
                            terminalChain);
                    runOnUiThread(this::refreshFormUi);
                    if (!terminalSaved || !receiptCleared) {
                        throw new SubmissionTerminalRecoveryException();
                    }
                    return;
                }
                String duplicateReason = dateKnown
                    ? duplicateFoundText(workflow, eligible)
                    : t("duplicate_found_date_unavailable");
                appendUnitLog(unit, submissionName + " " + duplicateReason
                    + duplicateHistoryLogSuffix(history));
                if (ProfileWorkflow.ACTION_CONTINUE.equals(action)) {
                    notifyDuplicateSubmission(unit.sn, submissionName, history);
                    appendUnitLog(unit, duplicateAutoContinueText());
                } else {
                    appendUnitLog(unit, t("duplicate_continue_log"));
                }
            }
        }

        if (!mainDraftSubmissionAllowed(expectedDraftBinding)) {
            throw new SubmissionJournalLockedException(
                "Draft binding changed before upload");
        }
        String frontUrl = null;
        String backUrl = null;
        List<String> supplementalUrls = null;
        Map<String, List<String>> slotUrls = null;
        if (isSlotMode()) {
            slotUrls = uploadSlotPhotos(api, unit, expectedDraftBinding);
        } else {
            requireMainDraftRemoteBinding(expectedDraftBinding, "front-photo upload");
            frontUrl = uploadImageWithReplayBarrier(
                api, new File(unit.frontPhoto), unit.sn + "-front.jpg");
            requireMainDraftRemoteBinding(expectedDraftBinding, "back-photo upload");
            backUrl = uploadImageWithReplayBarrier(
                api, new File(unit.backPhoto), unit.sn + "-back.jpg");
            supplementalUrls = new ArrayList<>();
            for (int i = 0; i < unit.supplementalPhotos.size(); i++) {
                requireMainDraftRemoteBinding(
                    expectedDraftBinding, "supplemental-photo upload");
                supplementalUrls.add(uploadImageWithReplayBarrier(
                    api, new File(unit.supplementalPhotos.get(i)),
                    unit.sn + "-supplemental-" + (i + 1) + ".jpg"));
            }
        }
        Set<String> removed = new HashSet<>();
        Set<String> submittedMissingCandidates = new HashSet<>(cachedMissingMaterialCodes);
        submittedMissingCandidates.retainAll(materialCodeSet());
        JSONObject payload = buildPayload(api.endpoints, unit, frontUrl, backUrl,
            supplementalUrls, removed, slotUrls);

        // Material forms have always used the production-compatible sequence: submit the complete
        // Panel-selected list, remove only the codes named by a parsed rejection, then try again.
        // Keep that behavior for drafts opened under an older cached Panel revision whose optional
        // workflow flags predate the current missingRecovery/submission policy fields.
        boolean materialCompatibility = hasConfiguredMaterialItems();
        boolean materialRecoveryEnabled =
            workflow.missingRecoveryEnabled || materialCompatibility;
        boolean materialRecoveryLocalNotice =
            workflow.missingRecoveryLocalNotice || materialCompatibility;
        int submissionMaxAttempts = materialCompatibility
            ? Math.max(4, workflow.submissionMaxAttempts)
            : workflow.submissionMaxAttempts;
        long submissionRetryDelayMs = materialCompatibility
            ? Math.max(4000L, workflow.submissionRetryDelayMs)
            : workflow.submissionRetryDelayMs;

        for (int attempt = 1; attempt <= submissionMaxAttempts; attempt++) {
            appendUnitLog(unit, t("submit_attempt") + "#" + attempt);
            JournaledSubmissionResponse journaled =
                postMainSubmissionOnce(api, unit, payload, expectedDraftBinding);
            JSONObject response = journaled.response;
            if (api.isSuccess(response)) {
                completeMainSubmission(
                    journaled, unit, removed, submittedMissingCandidates,
                    expectedDraftBinding, workflow);
                return;
            }
            String text = api.endpoints.response.configuredMessage(response);
            boolean retryableResponse =
                api.endpoints.operations.submit.isRetryableResponse(
                    response, api.endpoints.response);
            boolean missingResponse = api.endpoints.operations.submit.isMissingMaterialResponse(
                response, api.endpoints.response);
            List<String> missing = materialRecoveryEnabled
                ? missingMaterials(text, removed) : Collections.emptyList();
            boolean structuredResponseRejected =
                ProfileWorkflow.STRUCTURED_NON_SUCCESS_REJECT_AS_NOT_WRITTEN.equals(
                    workflow.submissionStructuredNonSuccessAction);
            boolean recoverableMissing =
                SubmissionPolicyRules.shouldRecoverMissingMaterials(
                    materialRecoveryEnabled,
                    missingResponse || materialCompatibility,
                    structuredResponseRejected, missing);
            // Only these two Panel-owned response classifiers prove that the backend rejected the
            // record. A profile-wide structured rejection declaration is equivalent proof for a
            // parsed response; it never applies to transport, HTTP or JSON failures.
            if (retryableResponse || missingResponse || recoverableMissing) {
                confirmMainSubmissionRejected(journaled);
            } else if (structuredResponseRejected) {
                // This compatibility behavior is explicitly profile-owned. Transport, parse and
                // response-loss errors never reach this parsed-response branch.
                confirmMainSubmissionRejected(journaled);
                throw new SubmissionExplicitlyRejectedException(
                    api.apiErrorMessage(response));
            } else if (!DURABLE_FINAL_SUBMISSION_REPLAY_BARRIER_ENABLED) {
                confirmMainSubmissionRejected(journaled);
                throw new SubmissionExplicitlyRejectedException(
                    api.apiErrorMessage(response));
            } else {
                markMainSubmissionUncertain(journaled);
            }
            if (retryableResponse) {
                if (attempt < submissionMaxAttempts
                        && submissionRetryDelayMs > 0L) {
                    Thread.sleep(submissionRetryDelayMs);
                }
                continue;
            }
            if (recoverableMissing) {
                boolean willRetry = attempt < submissionMaxAttempts;
                // Preserve the last classified missing response too.  Even when no attempt remains,
                // the operator-facing cache and the round summary must describe the backend's final
                // known-not-written result rather than silently dropping its last set of codes.
                recordRoundMissing(unit.sn, missing);
                rememberMissingMaterials(missing);
                if (materialRecoveryLocalNotice) {
                    List<String> firstTime = firstTimeMissingMaterials(missing);
                    if (!firstTime.isEmpty()) {
                        notifyMissing(unit.sn, firstTime, willRetry);
                    } else {
                        appendLog(t(willRetry
                            ? "missing_already_notified"
                            : "missing_final_already_notified") + join(missing, ", "));
                    }
                }
                if (!willRetry) {
                    throw new SubmissionExplicitlyRejectedException(
                        api.apiErrorMessage(response));
                }
                removed.addAll(missing);
                payload = buildPayload(api.endpoints, unit, frontUrl, backUrl,
                    supplementalUrls, removed, slotUrls);
                if (submissionRetryDelayMs > 0L) {
                    Thread.sleep(submissionRetryDelayMs);
                }
                continue;
            }
            // A recognized missing-material rejection with no resolvable configured code is still
            // known not-written, but cannot be changed or retried safely.
            throw new SubmissionExplicitlyRejectedException(
                api.apiErrorMessage(response));
        }
        throw new SubmissionExplicitlyRejectedException(
            t("submit_retry_failed") + unit.sn);
    }

    private void ensurePreviousSteps(Api api, UnitRecord unit,
                                     MainDraftSnapshotRules.Binding expectedDraftBinding,
                                     ProfileWorkflow workflow)
            throws Exception {
        requireMainDraftRemoteBinding(expectedDraftBinding, "previous-step lookup");
        requirePreviousStepSideEffectCapability(api, workflow);
        final boolean directCreate = workflow.shouldDirectCreatePreviousSteps(unit.grade);
        if (!directCreate) appendUnitLog(unit, t("checking_steps"));
        PreviousStepSubmissionAttempt retained =
            validateVerifiedPreviousStepSubmissionAttempt(
                unit, expectedDraftBinding, workflow);
        if (retained != null && retained.requiresRecipeContinuation()) {
            // A durable prefix is stronger than the coarse existence lookup. In particular, the
            // first acknowledged recipe may make that lookup return success even though recipes
            // two through N have not run. Resume the exact chain before consulting that shortcut.
            appendUnitLog(unit, t("previous_steps_creating"));
            requireMainDraftRemoteBinding(
                expectedDraftBinding, "previous-step recipe continuation");
            JSONObject resumed = runPreviousStepRecipesAndVerify(
                api, unit, expectedDraftBinding, workflow);
            if (resumed != null && api.isSuccess(resumed)) {
                validateVerifiedPreviousStepSubmissionAttempt(
                    unit, expectedDraftBinding, workflow);
                markPreviousStepsOk(unit, workflow, expectedDraftBinding);
                return;
            }
            requireMainDraftRemoteBinding(
                expectedDraftBinding, "previous-step continuation failure");
            unit.precheckStatus = t("failed");
            saveDraft();
            throw new IOException(unit.sn + " " + t("steps_missing_detail") + " "
                + api.apiErrorMessage(resumed));
        }
        if (directCreate) {
            // A Panel may declare that this result starts with no previous-step chain. Do not issue
            // the ordinary existence lookup before recipe one: create the exact journaled chain
            // immediately, then retain the normal verification lookup. A complete exact receipt
            // needs only that lookup; do not resolve live templates or revisit any recipe POST.
            final JSONObject direct;
            if (retained == null) {
                if (!shouldAutoCreateAnyPreviousSteps(unit.grade, workflow)) {
                    throw new BackendAdapter.ConfigurationException(
                        "profile.workflow.previousSteps.directCreateResultKeys");
                }
                if (!hasRequiredWorkflowArtifacts(unit, workflow)) {
                    throw new IOException(t("workflow_artifacts_required"));
                }
                appendUnitLog(unit, t("previous_steps_creating"));
                requireMainDraftRemoteBinding(
                    expectedDraftBinding, "direct previous-step recipe");
                direct = runPreviousStepRecipesAndVerify(
                    api, unit, expectedDraftBinding, workflow);
            } else {
                requireMainDraftRemoteBinding(
                    expectedDraftBinding, "direct previous-step verification");
                direct = verifyPreviousSteps(
                    api, unit, expectedDraftBinding, workflow);
            }
            if (direct != null && api.isSuccess(direct)) {
                validateVerifiedPreviousStepSubmissionAttempt(
                    unit, expectedDraftBinding, workflow);
                markPreviousStepsOk(unit, workflow, expectedDraftBinding);
                return;
            }
            requireMainDraftRemoteBinding(
                expectedDraftBinding, "direct previous-step failure");
            unit.precheckStatus = t("failed");
            saveDraft();
            throw new IOException(unit.sn + " " + t("steps_missing_detail") + " "
                + api.apiErrorMessage(direct));
        }
        JSONObject body = previousStepsResponse(api, unit, expectedDraftBinding);
        if (api.isSuccess(body)) {
            // A durable recipe receipt owns the exact serial spelling in its source identity.
            // Existing-record case alignment is allowed only before any recipe journal exists.
            if (retained == null) {
                alignSnCaseToPreviousSteps(
                    api, unit, body, workflow, expectedDraftBinding);
            }
            validateVerifiedPreviousStepSubmissionAttempt(
                unit, expectedDraftBinding, workflow);
            markPreviousStepsOk(unit, workflow, expectedDraftBinding);
            return;
        }
        requireConfiguredPreviousStepMissing(api, body);
        if (retained == null
                && tryCorrectSnFromPreviousSteps(
                    api, unit, expectedDraftBinding, workflow)) {
            validateVerifiedPreviousStepSubmissionAttempt(
                unit, expectedDraftBinding, workflow);
            markPreviousStepsOk(unit, workflow, expectedDraftBinding);
            return;
        }
        if (canAutoCreatePreviousSteps(unit, workflow)) {
            appendUnitLog(unit, t("previous_steps_creating"));
            requireMainDraftRemoteBinding(
                expectedDraftBinding, "previous-step recipe");
            body = runPreviousStepRecipesAndVerify(
                api, unit, expectedDraftBinding, workflow);
            if (body != null && api.isSuccess(body)) {
                validateVerifiedPreviousStepSubmissionAttempt(
                    unit, expectedDraftBinding, workflow);
                markPreviousStepsOk(unit, workflow, expectedDraftBinding);
                return;
            }
        }
        requireMainDraftRemoteBinding(expectedDraftBinding, "previous-step failure");
        unit.precheckStatus = t("failed");
        saveDraft();
        throw new IOException(unit.sn + " " + t("steps_missing_detail") + " " + api.apiErrorMessage(body));
    }

    private void requirePreviousStepSideEffectCapability(
            Api api, ProfileWorkflow workflow) throws BackendAdapter.ConfigurationException {
        List<String> missing = PreviousStepSafetyRules.sideEffectCapabilityErrors(
            workflow, api == null ? null : api.endpoints);
        if (!missing.isEmpty()) {
            throw new BackendAdapter.ConfigurationException(missing.get(0));
        }
    }

    private void requirePreviousStepLookupCapability(Api api, ProfileWorkflow workflow)
            throws BackendAdapter.ConfigurationException {
        List<String> missing = PreviousStepSafetyRules.lookupCapabilityErrors(
            workflow, api == null ? null : api.endpoints);
        if (!missing.isEmpty()) {
            throw new BackendAdapter.ConfigurationException(missing.get(0));
        }
    }

    private JSONObject runPreviousStepRecipesAndVerify(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            ProfileWorkflow workflow) throws Exception {
        runConfiguredPreviousStepRecipes(
            api, unit, expectedDraftBinding, workflow);
        return verifyPreviousSteps(api, unit, expectedDraftBinding, workflow);
    }

    private JSONObject verifyPreviousSteps(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            ProfileWorkflow workflow) throws Exception {
        JSONObject body = null;
        for (int attempt = 1; attempt <= workflow.previousStepVerifyAttempts; attempt++) {
            if (workflow.previousStepVerifyDelayMs > 0L) {
                Thread.sleep(workflow.previousStepVerifyDelayMs);
            }
            body = previousStepsResponse(api, unit, expectedDraftBinding);
            if (api.isSuccess(body)) return body;
        }
        return body;
    }

    // Preserve the exact identifier spelling returned by an existing linked record. Only casing is
    // adjusted, and only when the values are otherwise identical.
    private void alignSnCaseToPreviousSteps(
            Api api, UnitRecord unit, JSONObject body, ProfileWorkflow workflow,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        if (!workflow.shouldMatchExistingIdentifierCase()) return;
        String stored = firstPreviousStepSn(api, body);
        if (stored == null || stored.isEmpty()) return;
        if (stored.equals(unit.sn)) return;
        if (!stored.equalsIgnoreCase(unit.sn)) return;
        requireMainDraftRemoteBinding(
            expectedDraftBinding, "previous-step identifier case alignment");
        appendUnitLog(unit, unit.sn + " → " + stored + " " + t("sn_case_aligned"));
        unit.sn = stored;
    }

    private String firstPreviousStepSn(Api api, JSONObject body) {
        JSONArray steps = api.endpoints.operations.previousSteps.items(api.apiData(body));
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            String sn = step == null ? "" : api.endpoints.operations.previousSteps.serial(step);
            if (!sn.isEmpty()) return sn;
        }
        return null;
    }

    private void requireConfiguredPreviousStepMissing(Api api, JSONObject body)
            throws PreviousStepLookupUnclassifiedException {
        BackendAdapter.PreviousSteps operation = api.endpoints.operations.previousSteps;
        if (operation.isMissingResponse(
                api.endpoints.response.code(body),
                api.endpoints.response.configuredMessage(body))) {
            return;
        }
        throw new PreviousStepLookupUnclassifiedException(api.apiErrorMessage(body));
    }

    private JSONObject previousStepsResponse(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        return previousStepsResponse(api, unit, unit.sn, false, expectedDraftBinding);
    }

    private JSONObject previousStepsResponse(
            Api api, UnitRecord unit, String sn,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        return previousStepsResponse(api, unit, sn, false, expectedDraftBinding);
    }

    private JSONObject previousStepsResponse(
            Api api, UnitRecord unit, String sn, boolean fast,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        requireMainDraftRemoteBinding(
            expectedDraftBinding, "previous-step query construction");
        JSONObject profileSnapshot = profile;
        JSONObject template = profileSnapshot.getJSONObject("template");
        BackendAdapter.PreviousSteps operation = api.endpoints.operations.previousSteps;
        String query = enc(operation.queryField("templateId")) + "=" + template.getInt("id")
            + "&" + enc(operation.queryField("warehouseId")) + "=" + template.getInt("warehouseId")
            + "&" + enc(operation.queryField("sku")) + "=" + enc(template.getString("sku"))
            + "&" + enc(operation.queryField("serial")) + "=" + enc(sn);
        requireMainDraftRemoteBinding(expectedDraftBinding, "previous-step GET");
        if (fast) {
            return api.getEndpointJson(
                BackendAdapter.ENDPOINT_DETECTION_DATA,
                query,
                true,
                SCAN_PRECHECK_CONNECT_TIMEOUT_MS,
                SCAN_PRECHECK_READ_TIMEOUT_MS
            );
        }
        return api.getEndpointJson(BackendAdapter.ENDPOINT_DETECTION_DATA, query);
    }

    private boolean tryCorrectSnFromPreviousSteps(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            ProfileWorkflow workflow) throws Exception {
        if (!shouldTrySnCorrection(unit, workflow)) return false;
        String original = unit.sn == null ? "" : unit.sn;
        List<String> candidates = snCorrectionCandidates(original, workflow);
        if (candidates.isEmpty()) return false;
        appendUnitLog(unit, original + " " + t("sn_correction_try"));
        for (String candidate : candidates) {
            if (candidate.equals(original) || snExistsInOtherUnit(unit, candidate)) continue;
            JSONObject body = previousStepsResponse(
                api, unit, candidate, expectedDraftBinding);
            if (!api.isSuccess(body)) {
                requireConfiguredPreviousStepMissing(api, body);
                continue;
            }
            String target = identifierCorrectionTarget(api, body, candidate, workflow);
            return applySnCorrectionByPolicy(
                unit, original, target, workflow, expectedDraftBinding);
        }
        return false;
    }

    private boolean tryCorrectScannedSnFromPreviousSteps(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            ProfileWorkflow workflow) throws Exception {
        if (!shouldTrySnCorrection(unit, workflow)) return false;
        String original = unit.sn == null ? "" : unit.sn;
        List<String> candidates = snCorrectionCandidates(original, workflow);
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
        List<java.util.concurrent.Future<PreviousStepSafetyRules.CandidateOutcome>> futures =
            new ArrayList<>();
        for (String candidate : fastCandidates) {
            futures.add(executor.submit(() -> {
                JSONObject body = previousStepsResponse(
                    api, unit, candidate, true, expectedDraftBinding);
                if (api.isSuccess(body)) {
                    return PreviousStepSafetyRules.CandidateOutcome.found(
                        identifierCorrectionTarget(api, body, candidate, workflow));
                }
                requireConfiguredPreviousStepMissing(api, body);
                return PreviousStepSafetyRules.CandidateOutcome.missing();
            }));
        }

        try {
            PreviousStepSafetyRules.CandidateOutcome ordered =
                PreviousStepSafetyRules.awaitFirstFoundInPanelOrder(
                    futures, SCAN_PRECHECK_CORRECTION_BUDGET_MS);
            if (ordered != null) {
                return applySnCorrectionByPolicy(
                    unit, original, ordered.target, workflow, expectedDraftBinding);
            }
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    private void applySnCorrection(
            UnitRecord unit, String original, String candidate, ProfileWorkflow workflow,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        requireMainDraftRemoteBinding(
            expectedDraftBinding, "previous-step identifier correction");
        unit.sn = candidate;
        unit.precheckStatus = "unchecked";
        unit.status = "pending";
        clearScanPrecheckMissingCount(original, workflow);
        clearScanPrecheckMissingCount(candidate, workflow);
        saveDraft();
        appendUnitLog(unit, original + " -> " + candidate + " " + t("sn_correction_applied"));
        runOnUiThread(this::refreshFormUi);
    }

    private boolean applySnCorrectionByPolicy(
            UnitRecord unit, String original, String candidate, ProfileWorkflow workflow,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        String action = workflow.identifierCorrectionApplyAction;
        if (ProfileWorkflow.ACTION_BLOCK.equals(action)) {
            appendUnitLog(unit, original + " " + t("sn_correction_blocked"));
            return false;
        }
        if (ProfileWorkflow.ACTION_CONFIRM.equals(action)
                && !confirmOnUiThread(
                    t("sn_correction_confirm_title"),
                    original + " → " + candidate + "\n\n" + t("sn_correction_confirm_message"),
                    t("sn_correction_confirm_apply"), t("sn_correction_confirm_cancel"))) {
            appendUnitLog(unit, original + " " + t("sn_correction_declined"));
            return false;
        }
        if (!ProfileWorkflow.ACTION_AUTO.equals(action)
                && !ProfileWorkflow.ACTION_CONFIRM.equals(action)) {
            return false;
        }
        applySnCorrection(unit, original, candidate, workflow, expectedDraftBinding);
        return true;
    }

    private String identifierCorrectionTarget(
            Api api, JSONObject body, String candidate, ProfileWorkflow workflow) {
        if (!workflow.shouldMatchExistingIdentifierCase()) return candidate;
        String stored = firstPreviousStepSn(api, body);
        return stored != null && stored.equalsIgnoreCase(candidate) ? stored : candidate;
    }

    private boolean shouldTrySnCorrection(UnitRecord unit, ProfileWorkflow workflow) {
        return unit != null && workflow.shouldAttemptIdentifierCorrection(unit.grade);
    }

    private boolean snExistsInOtherUnit(UnitRecord unit, String sn) {
        for (UnitRecord item : units) {
            if (item != unit && sn.equals(item.sn)) return true;
        }
        return false;
    }

    private List<String> snCorrectionCandidates(String sn, ProfileWorkflow workflow) {
        return workflow.identifierCorrectionCandidates(
            sn, MAX_SN_CORRECTION_CANDIDATES);
    }

    private void markPreviousStepsOk(
            UnitRecord unit, ProfileWorkflow workflow,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        requireMainDraftRemoteBinding(expectedDraftBinding, "previous-step success");
        unit.workflowArtifactRequired = false;
        unit.precheckStatus = t("ok");
        clearScanPrecheckMissingCount(unit.sn, workflow);
        appendUnitLog(unit, t("steps_ok_short"));
        saveDraft();
        runOnUiThread(this::refreshFormUi);
    }

    private JSONArray catalogPreviousStepRecipeSnapshot() throws Exception {
        JSONObject catalogProfile = uniqueProfile(allProfiles, currentProfileId());
        JSONObject workflow = catalogProfile == null
            ? null : catalogProfile.optJSONObject("workflow");
        JSONObject previousSteps = workflow == null
            ? null : workflow.optJSONObject("previousSteps");
        JSONArray recipes = previousSteps == null
            ? null : previousSteps.optJSONArray("templates");
        if (recipes == null || recipes.length() <= 0) {
            throw new BackendAdapter.ConfigurationException(
                "profile.workflow.previousSteps.templates");
        }
        return new JSONArray(recipes.toString());
    }

    private PreviousStepSubmissionAttempt.ChainIdentity previousStepChainIdentity(
            UnitRecord unit, MainDraftSnapshotRules.Binding expectedDraftBinding,
            List<PreviousStepExecutionOrderRules.Step> executionPlan,
            JSONArray recipeSnapshot,
            String dynamicResolvedSemanticsSha256) throws Exception {
        if (unit == null || expectedDraftBinding == null
                || executionPlan == null || executionPlan.isEmpty()
                || recipeSnapshot == null
                || recipeSnapshot.length() != executionPlan.size()
                || dynamicResolvedSemanticsSha256 == null
                || dynamicResolvedSemanticsSha256.isEmpty()) {
            throw new BackendAdapter.ConfigurationException(
                "profile.workflow.previousSteps.templates");
        }
        MainDraftSnapshotRules.Binding current =
            mainDraftBindingForProfile(currentProfileId());
        if (!expectedDraftBinding.sameAs(current)) {
            throw new SubmissionJournalLockedException(
                "Previous-step terminal binding changed");
        }
        for (int order = 0; order < executionPlan.size(); order++) {
            PreviousStepExecutionOrderRules.Step step = executionPlan.get(order);
            if (step == null || step.sourceIndex != order
                    || recipeSnapshot.optJSONObject(step.sourceIndex) == null) {
                throw new BackendAdapter.ConfigurationException(
                    "profile.workflow.previousSteps.templates[" + order + "]");
            }
        }
        String chainSha256 = MainDraftSnapshotRules.semanticSha256(recipeSnapshot);
        if (chainSha256.isEmpty()) {
            throw new BackendAdapter.ConfigurationException(
                "profile.workflow.previousSteps.templates");
        }
        return PreviousStepSubmissionAttempt.ChainIdentity.of(
            expectedDraftBinding.connectionNamespace,
            expectedDraftBinding.catalogVersion,
            expectedDraftBinding.profileId,
            expectedDraftBinding.semanticsSha256,
            unit.sequence,
            unit.sn,
            previousStepSourceSnapshotSha256(unit),
            chainSha256,
            dynamicResolvedSemanticsSha256,
            executionPlan.size());
    }

    private PreviousStepSubmissionAttempt.RecipeIdentity previousStepRecipeIdentity(
            PreviousStepExecutionOrderRules.Step step, int order,
            JSONArray recipeSnapshot) throws Exception {
        JSONObject rawRecipe = step == null || recipeSnapshot == null
            ? null : recipeSnapshot.optJSONObject(step.sourceIndex);
        String identitySha256 = MainDraftSnapshotRules.semanticSha256(rawRecipe);
        if (step == null || rawRecipe == null || identitySha256.isEmpty()) {
            throw new BackendAdapter.ConfigurationException(
                "profile.workflow.previousSteps.templates[" + (order - 1) + "]");
        }
        return PreviousStepSubmissionAttempt.RecipeIdentity.of(
            order, step.sourceIndex,
            step.isDynamic() ? PreviousStepSubmissionAttempt.RecipeKind.DYNAMIC
                : PreviousStepSubmissionAttempt.RecipeKind.STATIC,
            identitySha256);
    }

    private void requirePreviousStepAttemptMatchesPlan(
            PreviousStepSubmissionAttempt attempt,
            List<PreviousStepExecutionOrderRules.Step> executionPlan,
            JSONArray recipeSnapshot) throws Exception {
        if (attempt == null || executionPlan == null || recipeSnapshot == null) {
            throw new SubmissionJournalLockedException(
                "Previous-step journal recipe identity is unavailable");
        }
        int order = attempt.key.recipe.order;
        if (order <= 0 || order > executionPlan.size()
                || recipeSnapshot.length() != executionPlan.size()) {
            throw new SubmissionJournalLockedException(
                "Previous-step journal recipe position is invalid");
        }
        PreviousStepSubmissionAttempt.RecipeIdentity expected =
            previousStepRecipeIdentity(
                executionPlan.get(order - 1), order, recipeSnapshot);
        if (!attempt.recipeMatches(expected)) {
            throw new SubmissionJournalLockedException(
                "Previous-step journal recipe identity changed");
        }
    }

    /** A lookup validates the receipt, but final submit still owns its lifetime. */
    private PreviousStepSubmissionAttempt validateVerifiedPreviousStepSubmissionAttempt(
            UnitRecord unit, MainDraftSnapshotRules.Binding expectedDraftBinding,
            ProfileWorkflow workflow)
            throws Exception {
        PreviousStepSubmissionAttempt.RestoreResult stored =
            restorePreviousStepSubmissionAttempt();
        if (stored.kind == PreviousStepSubmissionAttempt.RestoreKind.NONE) return null;
        if (stored.kind != PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                || stored.attempt == null
                || stored.attempt.state == PreviousStepSubmissionAttempt.State.UNCERTAIN
                || stored.attempt.state == PreviousStepSubmissionAttempt.State.POSTING) {
            throw new SubmissionJournalLockedException(
                "Previous-step POST still requires manual confirmation");
        }
        List<PreviousStepExecutionOrderRules.Step> executionPlan =
            PreviousStepExecutionOrderRules.plan(workflow);
        JSONArray recipeSnapshot = catalogPreviousStepRecipeSnapshot();
        PreviousStepSubmissionAttempt.ChainIdentity expected =
            previousStepChainIdentity(
                unit, expectedDraftBinding, executionPlan, recipeSnapshot,
                stored.attempt.key.chain.dynamicResolvedSemanticsSha256);
        if (!stored.attempt.chainMatches(expected)) {
            throw new SubmissionJournalLockedException(
                "Previous-step journal does not match verified draft");
        }
        requirePreviousStepAttemptMatchesPlan(
            stored.attempt, executionPlan, recipeSnapshot);
        return stored.attempt;
    }

    /** Capture the exact receipt identity while the unit still has its pre-terminal source state. */
    private PreviousStepSubmissionAttempt.ChainIdentity
            previousStepSubmissionChainForResolvedUnit(
                UnitRecord unit,
                MainDraftSnapshotRules.Binding expectedDraftBinding,
                ProfileWorkflow workflow) throws Exception {
        PreviousStepSubmissionAttempt.RestoreResult stored =
            restorePreviousStepSubmissionAttempt();
        if (stored.kind == PreviousStepSubmissionAttempt.RestoreKind.NONE) return null;
        if (stored.kind != PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                || stored.attempt == null
                || stored.attempt.state == PreviousStepSubmissionAttempt.State.UNCERTAIN
                || stored.attempt.state == PreviousStepSubmissionAttempt.State.POSTING) {
            throw new SubmissionJournalLockedException(
                "Previous-step terminal receipt is unresolved");
        }
        List<PreviousStepExecutionOrderRules.Step> executionPlan =
            PreviousStepExecutionOrderRules.plan(workflow);
        JSONArray recipeSnapshot = catalogPreviousStepRecipeSnapshot();
        PreviousStepSubmissionAttempt.ChainIdentity expected =
            previousStepChainIdentity(unit, expectedDraftBinding,
                executionPlan, recipeSnapshot,
                stored.attempt.key.chain.dynamicResolvedSemanticsSha256);
        if (!stored.attempt.chainMatches(expected)) {
            throw new SubmissionJournalLockedException(
                "Previous-step terminal receipt belongs to another unit");
        }
        requirePreviousStepAttemptMatchesPlan(
            stored.attempt, executionPlan, recipeSnapshot);
        return expected;
    }

    /** Call only after the unit's terminal outcome (or final POST receipt) is durable. */
    private boolean clearPreviousStepSubmissionAttemptForResolvedChain(
            PreviousStepSubmissionAttempt.ChainIdentity expected) {
        PreviousStepSubmissionAttempt.RestoreResult stored =
            restorePreviousStepSubmissionAttempt();
        if (stored.kind == PreviousStepSubmissionAttempt.RestoreKind.NONE) {
            return expected == null;
        }
        return expected != null
            && stored.kind == PreviousStepSubmissionAttempt.RestoreKind.RESTORED
            && stored.attempt != null
            && stored.attempt.state != PreviousStepSubmissionAttempt.State.UNCERTAIN
            && stored.attempt.state != PreviousStepSubmissionAttempt.State.POSTING
            && !stored.attempt.requiresRecipeContinuation()
            && stored.attempt.chainMatches(expected)
            && clearPreviousStepSubmissionAttempt();
    }

    private UnitRecord previousStepUnitFromStoredDraftItem(
            JSONObject item, ProfileWorkflow workflow) throws Exception {
        if (item == null) throw new JSONException("stored terminal unit is missing");
        Object sequenceRaw = item.opt("sequence");
        if (!(sequenceRaw instanceof Byte || sequenceRaw instanceof Short
                || sequenceRaw instanceof Integer || sequenceRaw instanceof Long)) {
            throw new JSONException("stored terminal sequence is invalid");
        }
        long sequenceLong = ((Number) sequenceRaw).longValue();
        if (sequenceLong <= 0L || sequenceLong > Integer.MAX_VALUE) {
            throw new JSONException("stored terminal sequence is invalid");
        }
        Object snRaw = item.opt("sn");
        Object gradeRaw = item.opt("grade");
        Object statusRaw = item.opt("status");
        if (!(snRaw instanceof String) || ((String) snRaw).isEmpty()
                || !(gradeRaw instanceof String)
                || !(statusRaw instanceof String)) {
            throw new JSONException("stored terminal identity is invalid");
        }
        UnitRecord unit = new UnitRecord((int) sequenceLong,
            (String) snRaw, (String) gradeRaw);
        unit.status = (String) statusRaw;
        if (!isSubmittedStatus(unit.status)) {
            throw new JSONException("stored unit is not terminal");
        }
        unit.snSource = item.has("snSource")
            ? item.getString("snSource") : SnScanRules.SOURCE_ENTERED;
        unit.baseSn = item.optString("baseSn", "");
        unit.baseSnSource = item.has("baseSnSource")
            ? item.getString("baseSnSource") : SnScanRules.SOURCE_ENTERED;
        unit.frontPhoto = item.optString("frontPhoto", "");
        unit.backPhoto = item.optString("backPhoto", "");
        unit.precheckStatus = item.optString("precheckStatus", "unchecked");
        unit.workflowArtifactRequired = item.optBoolean(
            "workflowArtifactRequired", item.optBoolean("stepPhotoRequired", false));

        JSONArray supplemental = item.optJSONArray("supplementalPhotos");
        if (item.has("supplementalPhotos") && supplemental == null) {
            throw new JSONException("stored supplemental photos are invalid");
        }
        for (int i = 0; supplemental != null && i < supplemental.length(); i++) {
            Object value = supplemental.opt(i);
            if (!(value instanceof String)) {
                throw new JSONException("stored supplemental photo is invalid");
            }
            unit.supplementalPhotos.add((String) value);
        }

        JSONObject slotPhotos = item.optJSONObject("slotPhotos");
        if (item.has("slotPhotos") && slotPhotos == null) {
            throw new JSONException("stored slot photos are invalid");
        }
        JSONArray slotNames = slotPhotos == null ? null : slotPhotos.names();
        for (int i = 0; slotNames != null && i < slotNames.length(); i++) {
            String field = slotNames.getString(i);
            JSONArray paths = slotPhotos.optJSONArray(field);
            if (field.isEmpty() || paths == null) {
                throw new JSONException("stored slot photo field is invalid");
            }
            List<String> values = new ArrayList<>();
            for (int j = 0; j < paths.length(); j++) {
                Object value = paths.opt(j);
                if (!(value instanceof String)) {
                    throw new JSONException("stored slot photo path is invalid");
                }
                values.add((String) value);
            }
            unit.slotPhotos.put(field, values);
        }

        JSONObject workflowArtifacts = item.optJSONObject("workflowArtifacts");
        if (item.has("workflowArtifacts") && workflowArtifacts == null) {
            throw new JSONException("stored workflow artifacts are invalid");
        }
        JSONArray artifactNames = workflowArtifacts == null
            ? null : workflowArtifacts.names();
        for (int i = 0; artifactNames != null && i < artifactNames.length(); i++) {
            String field = artifactNames.getString(i);
            Object value = workflowArtifacts.opt(field);
            if (field.isEmpty() || !(value instanceof String)
                    || ((String) value).isEmpty()) {
                throw new JSONException("stored workflow artifact is invalid");
            }
            unit.workflowArtifacts.put(field, (String) value);
        }
        unit.legacyWorkflowArtifactPath = LegacyDraftArtifactRules.restore(
            item, unit.workflowArtifacts, workflow);

        JSONObject pluginSns = item.optJSONObject("pluginSns");
        if (item.has("pluginSns") && pluginSns == null) {
            throw new JSONException("stored extra identifiers are invalid");
        }
        JSONArray pluginNames = pluginSns == null ? null : pluginSns.names();
        for (int i = 0; pluginNames != null && i < pluginNames.length(); i++) {
            String field = pluginNames.getString(i);
            Object value = pluginSns.opt(field);
            if (field.isEmpty() || !(value instanceof String)) {
                throw new JSONException("stored extra identifier is invalid");
            }
            unit.pluginSns.put(field, (String) value);
        }
        return unit;
    }

    /** Read-back proof for a terminal unit retained solely to retire an exact recipe receipt. */
    private UnitRecord exactStoredPreviousStepTerminalUnit(
            PreviousStepSubmissionAttempt.RestoreResult stored,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            List<PreviousStepExecutionOrderRules.Step> executionPlan,
            JSONArray recipeSnapshot, ProfileWorkflow workflow) {
        if (stored == null
                || stored.kind != PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                || stored.attempt == null || expectedDraftBinding == null) return null;
        try {
            requirePreviousStepAttemptMatchesPlan(
                stored.attempt, executionPlan, recipeSnapshot);
            JSONObject draft = draftForProfile(expectedDraftBinding.profileId);
            if (draft == null || MainDraftSnapshotRules.evaluate(
                    draft, expectedDraftBinding, null,
                    BuildConfig.VERSION_CODE, "").kind
                    != MainDraftSnapshotRules.RestoreKind.EXACT) return null;
            JSONArray items = draft.optJSONArray("units");
            if (items == null) return null;
            List<UnitRecord> matches = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !isSubmittedStatus(
                        item.optString("status", "pending"))) continue;
                UnitRecord candidate = previousStepUnitFromStoredDraftItem(item, workflow);
                PreviousStepSubmissionAttempt.ChainIdentity candidateChain =
                    previousStepChainIdentity(candidate, expectedDraftBinding,
                        executionPlan, recipeSnapshot,
                        stored.attempt.key.chain.dynamicResolvedSemanticsSha256);
                if (stored.attempt.chainMatches(candidateChain)) matches.add(candidate);
            }
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (Exception error) {
            Diagnostics.append(this,
                "Stored previous-step terminal recovery rejected: "
                    + conciseError(error));
            return null;
        }
    }

    private boolean persistExactPreviousStepTerminal(
            UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            PreviousStepSubmissionAttempt.ChainIdentity expectedChain,
            ProfileWorkflow workflow) {
        if (expectedChain == null) return saveDraft(true);
        if (unit == null || !isSubmittedStatus(unit.status)) return false;
        PreviousStepSubmissionAttempt.RestoreResult stored =
            restorePreviousStepSubmissionAttempt();
        if (stored.kind != PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                || stored.attempt == null
                || !stored.attempt.chainMatches(expectedChain)) return false;
        try {
            List<PreviousStepExecutionOrderRules.Step> executionPlan =
                PreviousStepExecutionOrderRules.plan(workflow);
            JSONArray recipeSnapshot = catalogPreviousStepRecipeSnapshot();
            PreviousStepSubmissionAttempt.ChainIdentity current =
                previousStepChainIdentity(unit, expectedDraftBinding,
                    executionPlan, recipeSnapshot,
                    stored.attempt.key.chain.dynamicResolvedSemanticsSha256);
            if (!expectedChain.equals(current)
                    || !saveDraft(true)) return false;
            UnitRecord durable = exactStoredPreviousStepTerminalUnit(
                restorePreviousStepSubmissionAttempt(), expectedDraftBinding,
                executionPlan, recipeSnapshot, workflow);
            return durable != null && unit.status.equals(durable.status);
        } catch (Exception error) {
            Diagnostics.append(this,
                "Previous-step terminal persistence failed: "
                    + conciseError(error));
            return false;
        }
    }

    private void runConfiguredPreviousStepRecipes(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            ProfileWorkflow workflow) throws Exception {
        requireMainDraftRemoteBinding(expectedDraftBinding, "previous-step recipe resolution");
        if (workflow == null) {
            throw new BackendAdapter.ConfigurationException(
                "profile.workflow.previousSteps");
        }
        List<PreviousStepExecutionOrderRules.Step> executionPlan =
            PreviousStepExecutionOrderRules.plan(workflow);
        JSONArray recipeSnapshot = catalogPreviousStepRecipeSnapshot();
        PreviousStepSubmissionAttempt.RestoreResult retained =
            restorePreviousStepSubmissionAttempt();
        if (retained.kind == PreviousStepSubmissionAttempt.RestoreKind.LOCKED
                || (retained.kind == PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                    && (retained.attempt == null
                        || retained.attempt.state
                            == PreviousStepSubmissionAttempt.State.UNCERTAIN
                        || retained.attempt.state
                            == PreviousStepSubmissionAttempt.State.POSTING))) {
            throw new SubmissionJournalLockedException(
                "Previous-step POST journal is unresolved or belongs to another draft");
        }
        if (retained.kind == PreviousStepSubmissionAttempt.RestoreKind.RESTORED) {
            PreviousStepSubmissionAttempt.ChainIdentity baseCandidate =
                previousStepChainIdentity(unit, expectedDraftBinding,
                    executionPlan, recipeSnapshot,
                    retained.attempt.key.chain.dynamicResolvedSemanticsSha256);
            if (!retained.attempt.chainMatches(baseCandidate)) {
                throw new SubmissionJournalLockedException(
                    "Previous-step POST journal belongs to another draft");
            }
            requirePreviousStepAttemptMatchesPlan(
                retained.attempt, executionPlan, recipeSnapshot);
        }
        Map<Integer, BackendAdapter.DynamicPreviousStepConfig> dynamicConfigs =
            new LinkedHashMap<>();
        Map<Integer, DynamicPreviousStepRules.CompiledPlan> dynamicPlans =
            new LinkedHashMap<>();
        JSONArray dynamicResolvedSnapshots = new JSONArray();

        // Resolve every dynamic recipe before the first upload or submit. A malformed adapter,
        // resolver, or live template therefore fails the unit without creating a partial chain.
        if (!workflow.dynamicPreviousStepRecipes.isEmpty()
                || !workflow.dynamicPreviousStepErrors.isEmpty()) {
            List<String> missing = api.endpoints.missingForDynamicPreviousSteps(workflow);
            if (!missing.isEmpty()) {
                throw new BackendAdapter.ConfigurationException(missing.get(0));
            }
            for (PreviousStepExecutionOrderRules.Step step : executionPlan) {
                if (!step.isDynamic()) continue;
                requireMainDraftRemoteBinding(
                    expectedDraftBinding, "dynamic previous-step template lookup");
                BackendAdapter.DynamicPreviousStepConfig config =
                    api.endpoints.dynamicPreviousStepConfig(step.dynamicRecipe);
                String query = enc(config.templateDetailIdParam) + "="
                    + enc(String.valueOf(config.templateId));
                JSONObject body = api.getEndpointJson(
                    BackendAdapter.ENDPOINT_TEMPLATE_DETAIL, query);
                if (!api.isSuccess(body)) {
                    throw new IOException(api.apiErrorMessage(body));
                }
                Object unwrapped = api.apiData(body);
                if (!(unwrapped instanceof JSONObject)) {
                    throw new BackendAdapter.ConfigurationException(
                        "backendAdapter.operations.templateDetail.response.data");
                }
                DynamicPreviousStepRules.CompiledPlan compiled =
                    config.compile((JSONObject) unwrapped, unit.sn);
                dynamicConfigs.put(step.sourceIndex, config);
                dynamicPlans.put(step.sourceIndex, compiled);
                dynamicResolvedSnapshots.put(new JSONObject()
                    .put("sourceIndex", step.sourceIndex)
                    .put("templateData",
                        new JSONObject(((JSONObject) unwrapped).toString())));
            }
        }

        requireMainDraftRemoteBinding(
            expectedDraftBinding, "resolved previous-step journal binding");
        String dynamicResolvedSemanticsSha256 =
            MainDraftSnapshotRules.semanticSha256(new JSONObject()
                .put("dynamicRecipes", dynamicResolvedSnapshots));
        if (dynamicResolvedSemanticsSha256.isEmpty()) {
            throw new SubmissionJournalLockedException(
                "Dynamic previous-step semantics cannot be fingerprinted");
        }
        PreviousStepSubmissionAttempt.ChainIdentity chain =
            previousStepChainIdentity(unit, expectedDraftBinding,
                executionPlan, recipeSnapshot, dynamicResolvedSemanticsSha256);
        if (retained.kind == PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                && !retained.attempt.chainMatches(chain)) {
            // A completed prefix may continue only under the exact live template-detail snapshot
            // resolved before its first POST. Never create a mixed-version recipe chain.
            throw new SubmissionJournalLockedException(
                "Dynamic previous-step semantics changed during journal recovery");
        }
        int completedRecipeCount = retained.kind
                == PreviousStepSubmissionAttempt.RestoreKind.RESTORED
            ? retained.attempt.completedRecipeCount() : 0;
        if (completedRecipeCount < 0
                || completedRecipeCount > executionPlan.size()) {
            throw new SubmissionJournalLockedException(
                "Previous-step completed prefix is invalid");
        }

        Map<String, List<String>> uploadedBySource = new LinkedHashMap<>();
        for (int planIndex = 0; planIndex < executionPlan.size(); planIndex++) {
            PreviousStepExecutionOrderRules.Step step = executionPlan.get(planIndex);
            int executionOrder = planIndex + 1;
            int displayIndex = step.sourceIndex + 1;
            PreviousStepSubmissionAttempt.RecipeIdentity recipeIdentity =
                previousStepRecipeIdentity(
                    step, executionOrder, recipeSnapshot);
            if (executionOrder <= completedRecipeCount) {
                long retainedDelay = step.isDynamic()
                    ? dynamicConfigs.get(step.sourceIndex).delayAfterMs
                    : step.staticRecipe.delayAfterMs;
                if (retainedDelay > 0L) Thread.sleep(retainedDelay);
                continue;
            }
            if (executionOrder != completedRecipeCount + 1) {
                throw new SubmissionJournalLockedException(
                    "Previous-step recipe order cannot skip a journaled prefix");
            }
            if (step.isDynamic()) {
                BackendAdapter.DynamicPreviousStepConfig config =
                    dynamicConfigs.get(step.sourceIndex);
                DynamicPreviousStepRules.CompiledPlan compiled =
                    dynamicPlans.get(step.sourceIndex);
                if (config == null || compiled == null) {
                    throw new BackendAdapter.ConfigurationException(
                        "profile.workflow.previousSteps.templates[" + step.sourceIndex + "]");
                }
                JSONObject uploadedSourceUrls = new JSONObject();
                for (String sourceKey : config.sourceKeys) {
                    List<String> urls = uploadedBySource.get(sourceKey);
                    if (urls == null) {
                        urls = uploadPreviousStepSource(
                            api, workflow, unit, sourceKey, expectedDraftBinding);
                        uploadedBySource.put(sourceKey, urls);
                    }
                    JSONArray values = new JSONArray();
                    for (String url : urls) values.put(url);
                    uploadedSourceUrls.put(sourceKey, values);
                }
                DynamicPreviousStepRules.CompiledPayload compiledPayload =
                    compiled.materialize(uploadedSourceUrls);
                JSONObject payload = submitEnvelope(api.endpoints,
                    compiledPayload.templateId(), compiledPayload.warehouseId(),
                    compiledPayload.sku(), compiledPayload.data());
                submitAutoStepPayload(api, unit, payload,
                    unit.sn + " " + t("previous_step_recipe") + " " + displayIndex,
                    expectedDraftBinding, chain, recipeIdentity,
                    completedRecipeCount, workflow);
                completedRecipeCount = executionOrder;
                if (config.delayAfterMs > 0L) Thread.sleep(config.delayAfterMs);
                continue;
            }

            ProfileWorkflow.PreviousStepRecipe recipe = step.staticRecipe;
            JSONObject data = new JSONObject(recipe.fixedData.toString());
            data.put(recipe.serialField, unit.sn);
            for (ProfileWorkflow.PhotoBinding binding : recipe.photoBindings) {
                List<String> urls = uploadedBySource.get(binding.source);
                if (urls == null) {
                    urls = uploadPreviousStepSource(
                        api, workflow, unit, binding.source, expectedDraftBinding);
                    uploadedBySource.put(binding.source, urls);
                }
                data.put(binding.targetField, join(urls, ","));
            }
            JSONObject payload = submitEnvelope(api.endpoints,
                recipe.templateId, recipe.warehouseId, recipe.sku, data);
            submitAutoStepPayload(api, unit, payload,
                unit.sn + " " + t("previous_step_recipe") + " " + displayIndex,
                expectedDraftBinding, chain, recipeIdentity,
                completedRecipeCount, workflow);
            completedRecipeCount = executionOrder;
            if (recipe.delayAfterMs > 0L) Thread.sleep(recipe.delayAfterMs);
        }
        appendUnitLog(unit, t("previous_steps_created"));
    }

    private List<String> uploadPreviousStepSource(
            Api api, ProfileWorkflow workflow, UnitRecord unit, String source,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        List<String> paths = previousStepSourcePaths(unit, source);
        if (paths.isEmpty()) {
            throw new IOException(t("workflow_artifact_missing") + source);
        }
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            requireMainDraftRemoteBinding(
                expectedDraftBinding, "previous-step upload");
            String path = paths.get(i);
            if (!hasFile(path)) throw new IOException(t("workflow_artifact_missing") + source);
            String uploadName = workflow.workflowArtifactUploadName(
                source, unit.sn, i + 1);
            if (uploadName.isEmpty()) {
                uploadName = unit.sn + "-workflow-" + safePhotoFileName(source)
                    + "-" + (i + 1) + ".jpg";
            }
            urls.add(uploadImageWithReplayBarrier(api, new File(path), uploadName));
        }
        return urls;
    }

    private List<String> previousStepSourcePaths(UnitRecord unit, String source) {
        List<String> out = new ArrayList<>();
        String artifact = unit.workflowArtifacts.get(source);
        if (hasFile(artifact)) {
            out.add(artifact);
            return out;
        }
        List<String> slot = unit.slotPhotos.get(source);
        if (slot != null) {
            for (String path : slot) if (hasFile(path)) out.add(path);
            return out;
        }
        JSONArray fields = profile == null ? null : profile.optJSONArray("uploadFields");
        for (int i = 0; fields != null && i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null || !source.equals(field.optString("field", ""))) continue;
            JSONArray sources = field.optJSONArray("sources");
            for (int j = 0; sources != null && j < sources.length(); j++) {
                String channel = sources.optString(j, "");
                if ("front".equals(channel) && hasFile(unit.frontPhoto)) out.add(unit.frontPhoto);
                else if ("back".equals(channel) && hasFile(unit.backPhoto)) out.add(unit.backPhoto);
                else if ("supplemental".equals(channel)) {
                    for (String path : unit.supplementalPhotos) if (hasFile(path)) out.add(path);
                }
            }
            break;
        }
        return out;
    }

    private void submitAutoStepPayload(
            Api api, UnitRecord unit, JSONObject payload, String label,
            MainDraftSnapshotRules.Binding expectedDraftBinding,
            PreviousStepSubmissionAttempt.ChainIdentity chain,
            PreviousStepSubmissionAttempt.RecipeIdentity recipeIdentity,
            int completedRecipeCount, ProfileWorkflow workflow) throws Exception {
        BackendAdapter.PreviousSteps policy = api.endpoints.operations.previousSteps;
        // Older Panels remain readable, but their substring lists cannot prove that a recipe POST
        // was not written. Until the independent recipe evidence policy is present, the new App
        // performs exactly one attempt and treats every non-success as outcome-uncertain.
        int effectiveRecipeMaxAttempts = policy.hasRecipeRetryableNotWrittenRules()
            ? workflow.previousStepRecipeMaxAttempts : 1;
        byte[] exactRequestBody = payload.toString().getBytes(StandardCharsets.UTF_8);
        PreviousStepSubmissionAttempt.RestoreResult retained =
            restorePreviousStepSubmissionAttempt();
        int firstAttempt = 1;
        if (retained.kind == PreviousStepSubmissionAttempt.RestoreKind.LOCKED
                || (retained.kind == PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                    && (retained.attempt == null
                        || retained.attempt.state
                            == PreviousStepSubmissionAttempt.State.UNCERTAIN
                        || retained.attempt.state
                            == PreviousStepSubmissionAttempt.State.POSTING))) {
            throw new SubmissionJournalLockedException(
                "Previous-step POST outcome is unresolved");
        }
        if (retained.kind == PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                && retained.attempt.state
                    == PreviousStepSubmissionAttempt.State.EXPLICITLY_REJECTED) {
            firstAttempt = retained.attempt.key.attemptNumber + 1;
        } else if (retained.kind == PreviousStepSubmissionAttempt.RestoreKind.RESTORED
                && retained.attempt.state
                    == PreviousStepSubmissionAttempt.State.PREPARED) {
            firstAttempt = retained.attempt.key.attemptNumber;
        }
        // recipeMaxAttempts is a persistent per-recipe total. Restoring an explicit rejection
        // must not grant a fresh retry window merely because the process or submit action changed.
        for (int attempt = firstAttempt;
                attempt <= effectiveRecipeMaxAttempts;
                attempt++) {
            requireMainDraftRemoteBinding(
                expectedDraftBinding, "previous-step POST");
            // The source draft must be durable before the intent journal. The payload is finalized
            // only after uploads, so its immutable bytes contain the real returned URLs.
            if (!saveDraft(true)) {
                throw new SubmissionJournalLockedException(
                    "Could not persist previous-step source draft");
            }
            requireMainDraftRemoteBinding(
                expectedDraftBinding, "previous-step journal persistence");
            PreviousStepSubmissionAttempt.Key key =
                PreviousStepSubmissionAttempt.Key.of(
                    chain, completedRecipeCount, recipeIdentity,
                    AlternateSubmissionAttempt.payloadSha256(exactRequestBody),
                    attempt, java.util.UUID.randomUUID().toString());
            final PreviousStepSubmissionAttempt prepared;
            try {
                prepared = PreviousStepSubmissionAttempt.prepare(
                    key, restorePreviousStepSubmissionAttempt());
            } catch (IllegalArgumentException mismatch) {
                throw new SubmissionJournalLockedException(
                    "Previous-step request does not match its durable journal");
            }
            if (!writePreviousStepSubmissionAttempt(prepared)) {
                throw new SubmissionJournalLockedException(
                    "Could not persist previous-step POST intent");
            }
            PreviousStepSubmissionAttempt posting = prepared.beginPosting(key);
            if (!writePreviousStepSubmissionAttempt(posting)) {
                throw new SubmissionJournalLockedException(
                    "Could not persist previous-step POST start");
            }
            final JSONObject response;
            try {
                response = api.postEndpointJsonExact(
                    BackendAdapter.ENDPOINT_SUBMIT_ENTRY, exactRequestBody);
            } catch (Exception transportOrResponseError) {
                if (BackendSessionErrors.isSessionInvalid(transportOrResponseError)) {
                    // Re-login must not make a possibly committed recipe POST replayable.
                    writePreviousStepSubmissionAttempt(posting.markUncertain(key));
                    throw transportOrResponseError;
                }
                // No cause is propagated to the outer network classifier. A timeout, I/O error,
                // malformed body or other unknown response must never replay the whole unit.
                writePreviousStepSubmissionAttempt(posting.markUncertain(key));
                throw new PreviousStepSubmissionOutcomeUncertainException();
            }
            BackendAdapter.PreviousSteps.RecipeResponseDisposition disposition =
                policy.recipeResponseDisposition(response, api.endpoints.response);
            if (api.isSuccess(response)
                    || disposition == BackendAdapter.PreviousSteps
                        .RecipeResponseDisposition.ALREADY_EXISTS_ACKNOWLEDGED) {
                if (!writePreviousStepSubmissionAttempt(
                        posting.markAcknowledged(key))) {
                    // POSTING remains the strongest durable state and restores as UNCERTAIN.
                    throw new PreviousStepSubmissionOutcomeUncertainException();
                }
                appendLog(label + " " + t("submitted"));
                return;
            }
            if (disposition == BackendAdapter.PreviousSteps
                    .RecipeResponseDisposition.RETRYABLE_NOT_WRITTEN) {
                if (!writePreviousStepSubmissionAttempt(
                        posting.markExplicitlyRejected(key))) {
                    throw new PreviousStepSubmissionOutcomeUncertainException();
                }
                if (attempt < effectiveRecipeMaxAttempts
                        && workflow.previousStepRecipeRetryDelayMs > 0L) {
                    Thread.sleep(workflow.previousStepRecipeRetryDelayMs);
                }
                continue;
            }
            // An unclassified non-success body does not prove whether the recipe was accepted.
            writePreviousStepSubmissionAttempt(posting.markUncertain(key));
            throw new PreviousStepSubmissionOutcomeUncertainException();
        }
        throw new SubmissionJournalLockedException(
            "Previous-step explicit retry limit exhausted");
    }

    private JSONArray checkDuplicate(Api api, String sn) throws Exception {
        BackendAdapter.DuplicateCheck operation = api.endpoints.operations.duplicateCheck;
        JSONObject body = api.getEndpointJson(BackendAdapter.ENDPOINT_SN_REPETITION,
            enc(operation.queryField("templateId")) + "=" + templateId()
                + "&" + enc(operation.queryField("serial")) + "=" + enc(sn));
        if (!api.isSuccess(body)) {
            throw new IOException(t("duplicate_check_failed") + api.apiErrorMessage(body));
        }
        return operation.items(api.apiData(body));
    }

    private void notifyDuplicateSubmission(String sn, String submissionName, DuplicateHistory history) {
        StringBuilder message = new StringBuilder();
        message.append(sn).append(" ").append(submissionName).append(" ").append(duplicateAutoContinueText());
        if (!history.latestText.isEmpty()) {
            message.append(" ").append(t("duplicate_return_last_date")).append(history.latestText);
        }
        runOnUiThread(() -> toastLong(message.toString()));
    }

    private boolean confirmDuplicateSubmission(String sn, String submissionName,
                                                DuplicateHistory history) {
        StringBuilder message = new StringBuilder();
        message.append(t("duplicate_return_sn")).append(sn)
            .append("\n").append(t("duplicate_return_type")).append(submissionName);
        if (!history.latestText.isEmpty()) {
            message.append("\n").append(t("duplicate_return_last_date")).append(history.latestText);
        }
        message.append("\n\n").append(t("duplicate_return_question"));
        return confirmOnUiThread(t("duplicate_return_title") + sn, message.toString(),
            t("duplicate_continue_button"), t("duplicate_skip_button"));
    }

    private String duplicateAutoContinueText() {
        if ("en".equals(lang)) return "notified; continuing submit automatically.";
        if ("es".equals(lang)) return "notificado; continua el envio automaticamente.";
        return "\u5df2\u63d0\u793a\uff0c\u81ea\u52a8\u7ee7\u7eed\u63d0\u4ea4\u3002";
    }

    private String duplicateFoundText(ProfileWorkflow workflow, boolean eligible) {
        boolean calendarMonths = ProfileWorkflow.DUPLICATE_AGE_CALENDAR_MONTHS.equals(
            workflow.duplicateAgeUnit);
        String key;
        if (eligible) {
            key = calendarMonths ? "duplicate_found_calendar_months" : "duplicate_found";
        } else {
            key = calendarMonths ? "duplicate_found_recent_calendar_months"
                : "duplicate_found_recent";
        }
        return String.format(java.util.Locale.ROOT, t(key), Math.max(0, workflow.duplicateAgeValue));
    }

    private String duplicateSubmissionName(int submitNumber) {
        if ("en".equals(lang)) return "submission #" + submitNumber;
        if ("es".equals(lang)) return "envío #" + submitNumber;
        return "第 " + submitNumber + " 次提交";
    }

    private DuplicateHistory duplicateHistory(JSONArray existing,
                                              BackendAdapter.DuplicateCheck operation) {
        List<String> configuredDateFields = operation.dateFields;
        long latestMillis = Long.MIN_VALUE;
        for (int i = 0; i < existing.length(); i++) {
            Object item = existing.opt(i);
            if (item instanceof JSONObject) {
                for (String path : configuredDateFields) {
                    Object raw = DuplicateDateRules.ROOT_VALUE_PATH.equals(path)
                        ? item : BackendAdapter.valueAt(item, path);
                    long millis = configuredDuplicateDateMillis(raw, path, operation);
                    if (millis > latestMillis) latestMillis = millis;
                }
            } else {
                for (String path : configuredDateFields) {
                    if (!DuplicateDateRules.ROOT_VALUE_PATH.equals(path)) continue;
                    long millis = configuredDuplicateDateMillis(item, path, operation);
                    if (millis > latestMillis) latestMillis = millis;
                }
            }
        }
        if (latestMillis == Long.MIN_VALUE) {
            Diagnostics.append(this, "duplicate_history_date_unparsed recordCount=" + existing.length());
        }
        return new DuplicateHistory(
            latestMillis == Long.MIN_VALUE ? "" : formatDateMillis(latestMillis), latestMillis);
    }

    private String duplicateHistoryLogSuffix(DuplicateHistory history) {
        if (history.latestText.isEmpty()) return "";
        return " " + t("duplicate_return_last_date") + history.latestText;
    }

    private long configuredDuplicateDateMillis(Object rawValue, String configuredPath,
                                               BackendAdapter.DuplicateCheck operation) {
        if (operation.dateParsePolicy != null) {
            if (DuplicateDateRules.ROOT_VALUE_PATH.equals(configuredPath)) {
                return DuplicateDateRules.parseRootValue(
                    rawValue == JSONObject.NULL ? null : rawValue,
                    configuredPath,
                    operation.epochUnits,
                    operation.dateTransforms,
                    operation.dateFormats,
                    operation.timeZone,
                    System.currentTimeMillis(),
                    operation.dateParsePolicy);
            }
            return DuplicateDateRules.parse(
                rawValue == JSONObject.NULL ? null : rawValue,
                operation.epochUnits,
                operation.dateTransforms,
                operation.dateFormats,
                operation.timeZone,
                System.currentTimeMillis(),
                operation.dateParsePolicy);
        }
        return DuplicateDateRules.parse(
            rawValue == JSONObject.NULL ? null : rawValue,
            operation.epochUnits,
            operation.dateTransforms,
            operation.dateFormats,
            operation.timeZone,
            System.currentTimeMillis());
    }

    private String formatDateMillis(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(millis));
    }

    private JSONObject buildPayload(UnitRecord unit, String frontUrl, String backUrl, List<String> supplementalUrls, Set<String> removedMaterials, Map<String, List<String>> slotUrls) throws JSONException {
        return buildPayload(endpoints(), unit, frontUrl, backUrl, supplementalUrls,
            removedMaterials, slotUrls);
    }

    private JSONObject buildPayload(BackendAdapter adapter, UnitRecord unit, String frontUrl,
                                    String backUrl, List<String> supplementalUrls,
                                    Set<String> removedMaterials,
                                    Map<String, List<String>> slotUrls) throws JSONException {
        JSONObject data = new JSONObject();
        String primaryIdentifierField = ProfileFieldRules.primaryIdentifierField(profile);
        if (primaryIdentifierField.isEmpty()) {
            throw new JSONException("profile.snFields.primary is required");
        }
        data.put(primaryIdentifierField, unit.sn);
        if (requiresSecondSn()) {
            String secondary = ProfileFieldRules.secondaryIdentifierField(profile);
            if (secondary.isEmpty()) {
                throw new JSONException("profile.snFields.secondary is required");
            }
            data.put(secondary, unit.baseSn);
        }
        // Iterate the active profile allow-list, never draft-owned keys. Validation rejects stale
        // keys before upload, and this second boundary makes it impossible for an unknown key to
        // overwrite the primary/result/photo fields even if a caller skips that preflight.
        for (Map.Entry<String, String> e
                : ProfileFieldRules.boundVisibleExtraIdentifierValues(
                    profile, unit.pluginSns).entrySet()) {
            data.put(e.getKey(), e.getValue());
        }

        // Resolve now, but write last so another configured field cannot overwrite the result.
        JSONObject gradeMap = profile.optJSONObject("gradeMap");
        JSONObject gradeForSubmission = null;
        if (gradeMap != null && gradeMap.length() > 0) {
            gradeForSubmission = ProfileFieldRules.resultMapping(profile, unit.grade);
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

        // Additional profile-configured choices are copied verbatim using their JSON value type.
        JSONArray choices = profile.optJSONArray("choiceFields");
        for (int i = 0; choices != null && i < choices.length(); i++) {
            JSONObject field = choices.optJSONObject(i);
            if (field == null) continue;
            // Hidden fields belong to a panel-controlled branch and are omitted unless made visible.
            if (!field.optBoolean("visible", true)) continue;
            // optString (not getString): a single malformed field must never throw and fail the whole unit.
            String fid = field.optString("field", "");
            if (!fid.isEmpty() && field.has("value")) data.put(fid, field.get("value"));
        }

        // materials is the profile-selected subset; allMaterials remains display-only.
        JSONArray groups = profile.optJSONArray("materialGroups");
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            JSONArray materialPayload = new JSONArray();
            JSONArray materials = group.optJSONArray("materials");
            for (int j = 0; materials != null && j < materials.length(); j++) {
                JSONObject material = materials.getJSONObject(j);
                String code = material.optString("code");
                if (removedMaterials.contains(code)) continue;
                // Keep every field in this payload on the immutable adapter snapshot captured by
                // the submission worker.  Re-reading the activity's current adapter here could
                // otherwise combine an old endpoint/envelope with new material item field names.
                materialPayload.put(adapter.operations.submit.materialItemMapping.item(
                    code, material.optString("name", code), material.optInt("defaultQty", 1)));
            }
            data.put(group.getString("field"), materialPayload);
        }

        // Grade is optional for truly ungraded profiles. For graded profiles, the resolved value is
        // deliberately applied last so no conditional/choice/material field can silently replace it.
        if (gradeForSubmission != null) {
            data.put(gradeForSubmission.getString("field"), gradeForSubmission.get("value"));
        }

        JSONObject template = profile.getJSONObject("template");
        return submitEnvelope(adapter, template.getInt("id"), template.getInt("warehouseId"),
            template.getString("sku"), data);
    }

    /** Wrap canonical profile values using deployment field names owned by the panel. */
    private JSONObject submitEnvelope(Object templateId, Object warehouseId, Object sku,
                                      JSONObject data) {
        return submitEnvelope(endpoints(), templateId, warehouseId, sku, data);
    }

    private JSONObject submitEnvelope(BackendAdapter adapter, Object templateId,
                                      Object warehouseId, Object sku, JSONObject data) {
        if (adapter == null) {
            throw new IllegalArgumentException("captured backend adapter is required");
        }
        return adapter.operations.submit.wrap(templateId, warehouseId, sku, data);
    }

    /** Slot mode: upload each box's photos and return fieldId -> uploaded URLs (insertion order). */
    private Map<String, List<String>> uploadSlotPhotos(
            Api api, UnitRecord unit,
            MainDraftSnapshotRules.Binding expectedDraftBinding) throws Exception {
        Map<String, List<String>> slotUrls = new LinkedHashMap<>();
        JSONArray slots = photoSlots();
        for (int s = 0; slots != null && s < slots.length(); s++) {
            String field = slots.getJSONObject(s).getString("field");
            List<String> photos = unit.slotPhotos.get(field);
            List<String> urls = new ArrayList<>();
            for (int p = 0; photos != null && p < photos.size(); p++) {
                requireMainDraftRemoteBinding(
                    expectedDraftBinding, "photo-slot upload");
                urls.add(uploadImageWithReplayBarrier(
                    api, new File(photos.get(p)),
                    unit.sn + "-" + (s + 1) + "-" + (p + 1) + ".jpg"));
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
        return ProfileFieldRules.conditionalFieldValue(
            field, unit == null ? null : unit.grade);
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

    private boolean hasConfiguredMaterialItems() {
        JSONArray groups = profile == null ? null : profile.optJSONArray("materialGroups");
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            JSONArray items = group == null ? null : group.optJSONArray("materials");
            if (items != null && items.length() > 0) return true;
        }
        return false;
    }

    private List<String> missingMaterials(String text, Set<String> alreadyRemoved) throws JSONException {
        Set<String> known = materialCodeSet();
        Set<String> excluded = new HashSet<>(alreadyRemoved);
        excluded.addAll(notifySkipMaterialCodes());
        String configuredPattern = profile == null ? "" : profile.optString("materialCodePattern", "");
        return MaterialCodeRules.findKnownCodesForAutomaticRecoveryCompatible(
            text, known, excluded, configuredPattern);
    }

    // Legacy-compatible ignore policy. These Panel-selected codes do not authorize automatic
    // removal/retry and therefore also do not enter local or round missing-item notices.
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
        if (panelConnectionSyncBlocked() && !activeWorkflowCanContinue()) {
            errors.add(t("panel_syncing_detail"));
        }
        if (isSampleCatalog()) errors.add(t("sample_catalog_detail"));
        if (token.isEmpty()) errors.add(t("login_required_detail"));
        if (units.isEmpty()) errors.add(t("need_one_sn"));
        if (ProfileFieldRules.primaryIdentifierField(profile).isEmpty()) {
            errors.add(t("panel_missing_config") + "profile.snFields.primary");
        }
        if (requiresSecondSn() && ProfileFieldRules.secondaryIdentifierField(profile).isEmpty()) {
            errors.add(t("panel_missing_config") + "profile.snFields.secondary");
        }
        ProfileWorkflow workflow = profileWorkflow();
        if (!workflow.operationalPoliciesExplicit) {
            errors.add(t("profile_policy_migration_required"));
        }
        if (workflow.refreshMaterialsBeforeSubmit && !hasConfiguredMaterialItems()) {
            errors.add(t("panel_missing_config") + "profile.materialGroups");
        }
        List<String> missingConfig = endpoints().missingForSubmit(
            workflow.previousStepsEnabled,
            workflow.duplicateCheckEnabled,
            hasConfiguredMaterialItems(),
            workflow.printingEnabled,
            workflow.refreshMaterialsBeforeSubmit);
        missingConfig.addAll(endpoints().missingForDynamicPreviousSteps(workflow));
        if (apiBase().isEmpty()) missingConfig.add("backendAdapter.baseUrl");
        if (!missingConfig.isEmpty()) {
            errors.add(t("panel_missing_config") + join(missingConfig, ", "));
        }
        SnScanRules.Policy primaryScannerPolicy = scannerPolicy(false);
        SnScanRules.Policy secondaryScannerPolicy = scannerPolicy(true);
        Set<String> workflowArtifactFields = new LinkedHashSet<>();
        for (ProfileWorkflow.WorkflowArtifact artifact : workflow.workflowArtifacts) {
            workflowArtifactFields.add(artifact.key);
        }
        for (UnitRecord unit : units) {
            if (!ProfileFieldRules.resultSelectionValid(profile, unit.grade)) {
                errors.add("#" + unit.sequence + " " + t("choose_grade"));
            }
            if (!ProfileFieldRules.unexpectedExtraIdentifierFields(
                    profile, unit.pluginSns).isEmpty()) {
                errors.add("#" + unit.sequence + " "
                    + t("panel_missing_config") + "profile.snPlugins");
            }
            boolean unknownArtifact = false;
            for (String field : unit.workflowArtifacts.keySet()) {
                if (field == null || !workflowArtifactFields.contains(field)) {
                    unknownArtifact = true;
                    break;
                }
            }
            if (unknownArtifact) {
                errors.add("#" + unit.sequence + " " + t("panel_missing_config")
                    + "profile.workflow.previousSteps.artifacts");
            }
            SnScanRules.Rejection primaryRejection = primaryScannerPolicy.rejectionForSource(
                unit.sn, unit.snSource);
            if (primaryRejection != SnScanRules.Rejection.NONE) {
                errors.add("#" + unit.sequence + " "
                    + identifierPolicyErrorText(false, primaryScannerPolicy,
                        primaryRejection, unit.sn.length(), unit.snSource));
            }
            if (requiresSecondSn()) {
                SnScanRules.Rejection secondaryRejection = secondaryScannerPolicy.rejectionForSource(
                    unit.baseSn, unit.baseSnSource);
                if (secondaryRejection != SnScanRules.Rejection.NONE) {
                    errors.add("#" + unit.sequence + " "
                        + identifierPolicyErrorText(true, secondaryScannerPolicy,
                            secondaryRejection, unit.baseSn.length(), unit.baseSnSource));
                }
            }
            for (String field : ProfileFieldRules.missingRequiredVisibleExtraFields(
                    profile, unit.pluginSns)) {
                errors.add("#" + unit.sequence + " "
                    + requiredFieldMessage(snPluginLabelForField(field)));
            }
            if (!hasRequiredWorkflowArtifacts(unit)) {
                errors.add("#" + unit.sequence + " " + t("workflow_artifacts_required"));
            }
            if (isSlotMode()) {
                JSONArray slots = photoSlots();
                if (!ProfileFieldRules.unexpectedPhotoSlotFields(
                        profile, workflow.includeOptionalPhotoSlots,
                        unit.slotPhotos).isEmpty()
                        || !unit.frontPhoto.isEmpty() || !unit.backPhoto.isEmpty()
                        || !unit.supplementalPhotos.isEmpty()) {
                    errors.add("#" + unit.sequence + " "
                        + t("panel_missing_config") + "profile.photoSlots");
                }
                for (int s = 0; slots != null && s < slots.length(); s++) {
                    JSONObject slot = slots.optJSONObject(s);
                    if (slot == null) continue;
                    int min = slot.optInt("minPhotos", 1);
                    int max = slot.optInt("maxPhotos", 0);
                    int count = slotPhotoCount(unit, slot.optString("field"));
                    if (count < min) {
                        errors.add("#" + unit.sequence + " "
                            + slotTitleForField(slot.optString("field")) + " ≥" + min);
                    }
                    if (max > 0 && count > max) {
                        errors.add("#" + unit.sequence + " "
                            + slotTitleForField(slot.optString("field")) + " ≤" + max);
                    }
                }
            } else {
                if (!unit.slotPhotos.isEmpty()) {
                    errors.add("#" + unit.sequence + " "
                        + t("panel_missing_config") + "profile.uploadFields");
                }
                if (unit.frontPhoto.isEmpty()) errors.add("#" + unit.sequence + " " + t("missing_front"));
                if (unit.backPhoto.isEmpty()) errors.add("#" + unit.sequence + " " + t("missing_back"));
            }
        }
        return errors;
    }

    private String identifierPolicyErrorText(boolean secondary, SnScanRules.Policy policy,
                                             SnScanRules.Rejection rejection, int actualLength,
                                             String source) {
        if (rejection == SnScanRules.Rejection.EMPTY) return requiredInputMessage(secondary);
        if (rejection == SnScanRules.Rejection.INVALID_POLICY) return scannerPolicyInvalidMessage(secondary);
        if (rejection == SnScanRules.Rejection.WRONG_LENGTH) {
            List<Integer> required = policy.requiredLengthsForSource(source);
            if (!required.isEmpty()) {
                return identifierLengthMessage(secondary, required, actualLength);
            }
        }
        return identifierPolicyRejectedMessage(secondary, policy);
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

    private void refreshWorkflowArtifactUi() {
        if (workflowArtifactPanel == null || workflowArtifactText == null) return;
        WorkflowArtifactTarget next = nextWorkflowArtifactTarget();
        boolean show = !profileWorkflow().workflowArtifacts.isEmpty()
            && (next != null || shouldAutoCreateAnyPreviousSteps(selectedGrade()));
        workflowArtifactPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        if (next == null) {
            workflowArtifactText.setText(units.isEmpty()
                ? t("add_sn_first") : t("workflow_artifacts_done"));
        } else {
            workflowArtifactText.setText("#" + next.unit.sequence + " " + next.unit.sn
                + " · " + next.artifact.localizedTitle(lang));
        }
    }

    private WorkflowArtifactTarget nextWorkflowArtifactTarget() {
        ProfileWorkflow workflow = profileWorkflow();
        for (UnitRecord unit : units) {
            if (!previousStepCreationTriggered(unit)) continue;
            for (ProfileWorkflow.WorkflowArtifact artifact : workflow.workflowArtifacts) {
                if (artifact.required && !hasFile(unit.workflowArtifacts.get(artifact.key))) {
                    return new WorkflowArtifactTarget(unit, artifact);
                }
            }
        }
        return null;
    }

    private void captureNextWorkflowArtifact() {
        if (!ensurePanelReadyForUse()) return;
        WorkflowArtifactTarget target = nextWorkflowArtifactTarget();
        if (target == null) {
            toast(units.isEmpty() ? t("add_sn_first") : t("workflow_artifacts_done"));
            return;
        }
        int index = units.indexOf(target.unit);
        if (index < 0 || !ensureCameraPermission()) return;
        pendingPhotoIndex = index;
        pendingPhotoSide = "artifact";
        pendingPhotoField = target.artifact.key;
        startCameraForPendingPhoto();
    }

    private boolean hasRequiredWorkflowArtifacts(UnitRecord unit) {
        return hasRequiredWorkflowArtifacts(unit, profileWorkflow());
    }

    private boolean hasRequiredWorkflowArtifacts(
            UnitRecord unit, ProfileWorkflow workflow) {
        if (!previousStepCreationTriggered(unit, workflow)) return true;
        for (ProfileWorkflow.WorkflowArtifact artifact : workflow.workflowArtifacts) {
            if (artifact.required && !hasFile(unit.workflowArtifacts.get(artifact.key))) return false;
        }
        return true;
    }

    private boolean canAutoCreatePreviousSteps(UnitRecord unit, ProfileWorkflow workflow) {
        return previousStepCreationTriggered(unit, workflow)
            && (!workflow.previousStepRecipes.isEmpty()
                || !workflow.dynamicPreviousStepRecipes.isEmpty())
            && hasRequiredWorkflowArtifacts(unit, workflow);
    }

    private boolean previousStepCreationTriggered(UnitRecord unit) {
        return previousStepCreationTriggered(unit, profileWorkflow());
    }

    private boolean previousStepCreationTriggered(
            UnitRecord unit, ProfileWorkflow workflow) {
        return unit != null && (unit.workflowArtifactRequired
            || shouldAutoCreateAnyPreviousSteps(unit.grade, workflow));
    }

    private boolean shouldAutoCreateAnyPreviousSteps(String resultKey) {
        return shouldAutoCreateAnyPreviousSteps(resultKey, profileWorkflow());
    }

    private boolean shouldAutoCreateAnyPreviousSteps(
            String resultKey, ProfileWorkflow workflow) {
        return workflow.shouldAutoCreatePreviousSteps(resultKey)
            || workflow.shouldAutoCreateDynamicPreviousSteps(resultKey);
    }


    private boolean hasFile(String path) {
        return path != null && !path.isEmpty() && new File(path).exists();
    }

    private void refreshFormUi() {
        refreshMissingMaterialsUi();
        if (unitList == null || basePrompt == null || photoPrompt == null || summaryText == null) return;
        ensureResultButtons();
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
        basePrompt.setText(!needsBase ? "" : base == null
            ? secondaryInputLabel() + " " + t("done")
            : secondaryInputLabel() + " → #" + base.sequence + " " + base.sn);
        refreshWorkflowArtifactUi();

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

        // Compact secondary details for the configured identifiers, result, photos and precheck.
        java.util.List<String> bits = new java.util.ArrayList<>();
        if (hasGradeChoices) bits.add(t("grade") + " " + resultLabel(unit.grade));
        if (needsBase) bits.add(secondaryInputLabel() + " " + emptyDash(unit.baseSn));
        for (ProfileWorkflow.WorkflowArtifact artifact : profileWorkflow().workflowArtifacts) {
            if (!previousStepCreationTriggered(unit)) break;
            bits.add(artifact.localizedTitle(lang) + " "
                + okDash(unit.workflowArtifacts.get(artifact.key)));
        }
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

    // Capture the next configured photo for this item. The card is rebuilt after the camera returns.
    private void captureNextSlotForUnit(UnitRecord unit) {
        if (!isSlotMode()) { captureSupplementalPhoto(unit); return; }
        JSONArray slots = photoSlots();
        if (!profileWorkflow().includeOptionalPhotoSlots) {
            // Preserve the established card-button behavior for migrated profiles. The chooser
            // below is activated only with the explicit Panel-owned optional-slot switch.
            for (int s = 0; slots != null && s < slots.length(); s++) {
                JSONObject slot = slots.optJSONObject(s);
                if (slot == null) continue;
                String field = slot.optString("field");
                int max = slot.optInt("maxPhotos", 0);
                if (max <= 0 || slotPhotoCount(unit, field) < max) {
                    captureSlotPhotoFor(unit, s);
                    return;
                }
            }
            toast(t("all_photos_done"));
            return;
        }
        int slotCount = slots == null ? 0 : slots.length();
        int[] counts = new int[slotCount];
        int[] minimums = new int[slotCount];
        int[] maximums = new int[slotCount];
        for (int s = 0; s < slotCount; s++) {
            JSONObject slot = slots.optJSONObject(s);
            if (slot == null) continue;
            String field = slot.optString("field");
            counts[s] = slotPhotoCount(unit, field);
            minimums[s] = slot.optInt("minPhotos", 1);
            maximums[s] = slot.optInt("maxPhotos", 0);
        }
        // First satisfy every configured minimum in order. Once required capture is complete,
        // let the operator choose any box that still has capacity, including minPhotos=0 boxes.
        int requiredSlot = PhotoSlotCaptureRules.nextBelowMinimum(counts, minimums);
        if (requiredSlot >= 0) {
            captureSlotPhotoFor(unit, requiredSlot);
            return;
        }
        List<Integer> available = new ArrayList<>();
        for (int candidate : PhotoSlotCaptureRules.slotsWithCapacity(counts, maximums)) {
            if (slots.optJSONObject(candidate) != null) available.add(candidate);
        }
        List<String> labels = new ArrayList<>();
        for (int s : available) {
            JSONObject slot = slots.optJSONObject(s);
            String field = slot.optString("field");
            int max = maximums[s];
            labels.add(slotTitleForField(field) + " (" + counts[s] + "/"
                + (max <= 0 ? "∞" : max) + ")");
        }
        if (available.isEmpty()) {
            toast(t("all_photos_done"));
        } else if (available.size() == 1) {
            captureSlotPhotoFor(unit, available.get(0));
        } else {
            new AlertDialog.Builder(this)
                .setTitle(t("choose_photo_slot"))
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                    captureSlotPhotoFor(unit, available.get(which)))
                .setNegativeButton(t("cancel"), null)
                .show();
        }
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
        String draftProfileId = draft == null ? "" : draft.optString("profileId", "");
        JSONObject draftProfile = uniqueProfile(allProfiles, draftProfileId);
        if (draftProfile == null) draftProfile = uniqueProfile(profiles, draftProfileId);
        String draftPrimaryLabel = inputLabel(draftProfile, false);
        String draftSecondaryLabel = inputLabel(draftProfile, true);
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
                String baseText = base.isEmpty() ? "" : " " + draftSecondaryLabel + "=" + base;
                lines.add("#" + item.optInt("sequence", i + 1) + " "
                    + draftPrimaryLabel + "=" + sn + baseText + " " + t("status") + "="
                    + item.optString("status", "pending"));
                shown++;
            }
        }
        if (total > shown) lines.add("+" + (total - shown) + " ...");
        lines.add(t("switch_model_to_continue"));
        return join(lines, "\n");
    }

    private void deletePhoto(UnitRecord unit, String side) {
        if (blockDraftMutationForPreviousStepJournal()) return;
        String path = "front".equals(side) ? unit.frontPhoto : unit.backPhoto;
        deleteFileQuietly(path);
        if ("front".equals(side)) unit.frontPhoto = "";
        else unit.backPhoto = "";
        refreshFormUi();
        saveDraft();
    }

    private void deleteUnit(UnitRecord unit) {
        if (blockDraftMutationForPreviousStepJournal()) return;
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
        for (String path : unit.supplementalPhotos) {
            deleteFileQuietly(path);
        }
        for (List<String> paths : unit.slotPhotos.values()) {
            for (String path : paths) deleteFileQuietly(path);
        }
        for (String path : unit.workflowArtifacts.values()) deleteFileQuietly(path);
        deleteFileQuietly(unit.legacyWorkflowArtifactPath);
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
        root.addView(label(primaryInputLabel()));
        LinearLayout snRow = row();
        EditText snInput = edit("SN");
        snInput.setText(unit.sn);
        snRow.addView(snInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        View rescanButton = identifierScanEnabled(false) ? scanIconButton(v -> {}) : null;
        if (rescanButton != null) snRow.addView(rescanButton);
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
            root.addView(label(secondaryInputLabel()));
            LinearLayout baseSnRow = row();
            baseInput = edit(inputPlaceholder(true));
            baseInput.setText(unit.baseSn == null ? "" : unit.baseSn);
            baseSnRow.addView(baseInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (identifierScanEnabled(true)) {
                baseRescanButton = scanIconButton(v -> {});
                baseSnRow.addView(baseRescanButton);
            }
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
        if (rescanButton != null) {
            rescanButton.setOnClickListener(v -> {
                startUnitSnRescan(unit, false);
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
        }
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
                if (blockDraftMutationForPreviousStepJournal()) return;
                String oldSn = unit.sn;
                String rawNextSn = snInput.getText().toString();
                String nextSn = oldSn.equals(rawNextSn) ? oldSn
                    : normalizeIdentifier(rawNextSn, false, SnScanRules.SOURCE_ENTERED);
                if (nextSn.isEmpty()) {
                    toast(requiredInputMessage(false));
                    return;
                }
                String nextSnSource = oldSn.equals(nextSn)
                    ? unit.snSource : SnScanRules.SOURCE_ENTERED;
                if (!validateIdentifierValue(nextSn, false, nextSnSource)) return;
                for (UnitRecord item : units) {
                    if (item != unit && item.sn.equals(nextSn)) {
                        toast(t("duplicate_sn") + nextSn);
                        return;
                    }
                }
                unit.sn = nextSn;
                if (!oldSn.equals(nextSn)) {
                    unit.snSource = nextSnSource;
                    unit.precheckStatus = "unchecked";
                    unit.status = "pending";
                }
                if (finalGradeSpinner != null) {
                    unit.grade = String.valueOf(finalGradeSpinner.getSelectedItem());
                }
                if (finalBaseInput != null) {
                    String oldBase = unit.baseSn == null ? "" : unit.baseSn;
                    String rawNextBase = finalBaseInput.getText().toString();
                    String nextBase = oldBase.equals(rawNextBase) ? oldBase
                        : normalizeIdentifier(rawNextBase, true, SnScanRules.SOURCE_ENTERED);
                    String nextBaseSource = oldBase.equals(nextBase)
                        ? unit.baseSnSource : SnScanRules.SOURCE_ENTERED;
                    if (!nextBase.isEmpty()
                            && !validateIdentifierValue(nextBase, true, nextBaseSource)) return;
                    unit.baseSn = nextBase;
                    if (!oldBase.equals(nextBase)) {
                        unit.baseSnSource = nextBaseSource;
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

    // Render every configured or captured photo with an in-place delete action.
    private void renderDetailsPhotos(LinearLayout box, UnitRecord unit) {
        box.removeAllViews();
        for (ProfileWorkflow.WorkflowArtifact artifact : profileWorkflow().workflowArtifacts) {
            String path = unit.workflowArtifacts.get(artifact.key);
            if (!previousStepCreationTriggered(unit) && !hasFile(path)) continue;
            addPhotoViewButton(box, artifact.localizedTitle(lang), path,
                !hasFile(path) ? null : () -> {
                    if (blockDraftMutationForPreviousStepJournal()) return;
                    String removed = unit.workflowArtifacts.remove(artifact.key);
                    unit.legacyWorkflowArtifactPath = LegacyDraftArtifactRules.afterArtifactChange(
                        profileWorkflow(), artifact.key, "", unit.legacyWorkflowArtifactPath);
                    deleteFileQuietly(removed);
                    refreshFormUi();
                    saveDraft();
                    renderDetailsPhotos(box, unit);
                });
        }
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
                        if (blockDraftMutationForPreviousStepJournal()) return;
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
                    if (blockDraftMutationForPreviousStepJournal()) return;
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
        if (mainDraftRestoreBlocked() || draftPromptShown || !units.isEmpty()) return;
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
                    // A binding/version mismatch is evidence that this queue belongs to other
                    // Panel semantics, not evidence that its production data may be deleted.
                    Diagnostics.append(this, "Draft restore kept locked snapshot: "
                        + conciseError(exc));
                    alert(t("draft_restore_failed"), t("draft_binding_locked_detail"));
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

    private boolean draftContainsUnitSequence(JSONObject draft, int sequence) {
        JSONArray array = draft == null ? null : draft.optJSONArray("units");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && item.optInt("sequence", -1) == sequence) return true;
        }
        return false;
    }

    private void discardAllDraftsAndResetForm() {
        if (blockDraftMutationForPreviousStepJournal()) return;
        clearAllDrafts();
        photoOrder = PhotoOrderRules.profileDefault(profile);
        units.clear();
        clearProfileScopedState();
        refreshFormUi();
        resetGradeSelection();
        refocusSnInput();
    }

    private MainDraftSnapshotRules.Binding mainDraftBindingForProfile(String profileId) {
        JSONObject catalogProfile = uniqueProfile(allProfiles, profileId);
        return MainDraftSnapshotRules.currentBinding(currentConnectionNamespace(),
            activeCatalogVersion, profileId, catalogProfile, appConfig, catalogSettings);
    }

    private String currentPanelPairSha256() {
        return activePanelPairSha256;
    }

    private JSONObject legacyMainDraftMigrationReceipt() {
        try {
            String key = MainDraftSnapshotRules.legacyReceiptPreferenceKey(
                currentConnectionNamespace());
            Object raw = prefs.getAll().get(key);
            return raw instanceof String ? new JSONObject((String) raw) : null;
        } catch (Exception error) {
            Diagnostics.append(this, "Legacy draft migration receipt unreadable: "
                + conciseError(error));
            return null;
        }
    }

    /**
     * Returns an exact-bound draft, or throws without changing/deleting the original bytes.
     * Legacy v1/v2 queues are upgraded only after the cache migrator persisted a receipt bound to
     * this release, connection, catalog revision and complete logical config/catalog pair hash.
     */
    private JSONObject prepareMainDraftForRestore(JSONObject draft) throws JSONException {
        if (mainDraftRestoreBlocked()) {
            throw new JSONException("draft restore blocked during remote work");
        }
        String profileId = draft == null ? "" : draft.optString("profileId", "");
        MainDraftSnapshotRules.Binding current;
        MainDraftSnapshotRules.RestoreDecision decision;
        try {
            current = mainDraftBindingForProfile(profileId);
            decision = MainDraftSnapshotRules.evaluate(draft, current,
                legacyMainDraftMigrationReceipt(), BuildConfig.VERSION_CODE,
                currentPanelPairSha256());
        } catch (Exception error) {
            throw new JSONException("draft binding unavailable");
        }
        if (!decision.allowed()) {
            Diagnostics.append(this, "Draft binding blocked restore profile=" + profileId
                + " reason=" + decision.reason);
            throw new JSONException("draft binding does not match active Panel semantics");
        }
        if (decision.kind == MainDraftSnapshotRules.RestoreKind.EXACT) return draft;

        JSONObject bound;
        try {
            bound = MainDraftSnapshotRules.bindVerifiedLegacy(draft, current);
            JSONObject store = loadDraftStore();
            draftMap(store).put(profileId, bound);
            // Bind before exposing any legacy unit in memory. A crash or rollback cannot leave a
            // queue that this release already interpreted but failed to identify durably.
            writeDraftStore(store, true);
        } catch (Exception error) {
            Diagnostics.append(this, "Verified legacy draft binding write failed: "
                + conciseError(error));
            throw new JSONException("legacy draft binding could not be persisted");
        }
        return bound;
    }

    private void restoreDraft(JSONObject draft) throws JSONException {
        JSONObject prepared = prepareMainDraftForRestore(draft);
        restoringDraft = true;
        int restored = 0;
        try {
            String enteringProfileId = currentProfileId();
            String profileId = prepared.optString("profileId", "");
            int profileIndex = findProfileIndex(profileId);
            if (profileIndex < 0) throw new JSONException("draft profile is not selectable");
            boolean profileChanged = !profileId.equals(enteringProfileId);
            profile = profiles.getJSONObject(profileIndex);
            // Saved-draft and queue-snapshot restore can jump directly to another profile without
            // passing through the Spinner's normal change branch. Rebuild first so every
            // Panel-owned input is bound to the restored profile; restore then reapplies the
            // draft's snapshotted photo order and contents.
            if (profileChanged) {
                saveLastProfile();
                showFormPage(false, false);
                profileIndex = findProfileIndex(profileId);
                if (profileIndex < 0) throw new JSONException("draft profile is not selectable");
                profile = profiles.getJSONObject(profileIndex);
            }
            if (profileSpinner != null) profileSpinner.setSelection(profileIndex);
            restored = restorePreparedDraftContents(prepared);
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
        return restorePreparedDraftContents(prepareMainDraftForRestore(draft));
    }

    private int restorePreparedDraftContents(JSONObject draft) throws JSONException {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            // Restoring a durable queue is a new in-memory workflow. Serialize it with candidate
            // staging so an unsafe barrier sees either the complete restored queue or none of it.
            if (panelConnectionSyncBlocked()) {
                throw new JSONException("draft restore blocked by Panel candidate");
            }
            return restorePreparedDraftContentsAtReadyBoundary(draft);
        }
    }

    private int restorePreparedDraftContentsAtReadyBoundary(JSONObject draft)
            throws JSONException {
        int restored = 0;
        JSONArray array = draft.optJSONArray("units");
        // The Panel owns the policy for new batches. An in-progress draft snapshots the order under
        // which its photos already began, so an upgrade/catalog refresh cannot reinterpret them.
        photoOrder = PhotoOrderRules.restoreForDraft(
            profile, draft.optString("photoOrder", ""), array != null && array.length() > 0);
        units.clear();
        clearProfileScopedState();
        restoreMissingMaterialCodes(draft.optJSONArray("missingMaterialCodes"));
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String sn = item.optString("sn", "").trim();
            if (sn.isEmpty()) continue;
            UnitRecord unit = new UnitRecord(item.optInt("sequence", i + 1), sn, item.optString("grade", firstGradeKey()));
            unit.baseSn = item.optString("baseSn", "");
            // Drafts written before capture-source tracking had already passed the legacy
            // all-source policy. Preserve those queues by migrating only absent fields to entered;
            // explicit unknown values remain invalid and fail closed at final validation.
            unit.snSource = item.has("snSource")
                ? item.optString("snSource", "") : SnScanRules.SOURCE_ENTERED;
            unit.baseSnSource = item.has("baseSnSource")
                ? item.optString("baseSnSource", "") : SnScanRules.SOURCE_ENTERED;
            unit.frontPhoto = item.optString("frontPhoto", "");
            unit.backPhoto = item.optString("backPhoto", "");
            unit.precheckStatus = item.optString("precheckStatus", "unchecked");
            unit.workflowArtifactRequired = item.has("workflowArtifactRequired")
                ? item.optBoolean("workflowArtifactRequired", false)
                : item.optBoolean("stepPhotoRequired", false);
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
            JSONObject workflowArtifacts = item.optJSONObject("workflowArtifacts");
            JSONArray artifactKeys = workflowArtifacts == null ? null : workflowArtifacts.names();
            for (int j = 0; artifactKeys != null && j < artifactKeys.length(); j++) {
                String key = artifactKeys.optString(j, "");
                String path = workflowArtifacts.optString(key, "");
                if (!key.isEmpty() && !path.isEmpty()) unit.workflowArtifacts.put(key, path);
            }
            // Migrate the sole legacy workflow-photo field only when the Panel gives it one
            // unambiguous destination. Otherwise preserve the original key without submitting it.
            unit.legacyWorkflowArtifactPath = LegacyDraftArtifactRules.restore(
                item, unit.workflowArtifacts, profileWorkflow());
            // Signed v1 stored this flag in drafts. Keep it only as a rollback-compatible
            // round-trip value; current submission and UI behavior do not read it.
            unit.legacyDefective = item.optBoolean("defective", false);
            JSONObject pluginSns = item.optJSONObject("pluginSns");
            JSONArray pluginFields = pluginSns == null ? null : pluginSns.names();
            for (int j = 0; pluginFields != null && j < pluginFields.length(); j++) {
                String field = pluginFields.optString(j);
                unit.pluginSns.put(field, pluginSns.optString(field));
            }
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
        if (mainDraftRestoreBlocked()) return;
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
                alert(t("draft_restore_failed"), t("draft_binding_locked_detail"));
            }
        } else if (draft != null) {
            clearDraftForProfile(currentProfileId());
        }
        photoOrder = PhotoOrderRules.profileDefault(profile);
        units.clear();
        refreshFormUi();
        resetGradeSelection();
        refocusSnInput();
    }

    private void clearProfileScopedState() {
        cachedMissingMaterialCodes.clear();
        notifiedMissingMaterialCodes.clear();
        missingMaterialNoticeShown = false;
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
        return buildDraftJson(profileId, mainDraftBindingForProfile(profileId));
    }

    private JSONObject buildDraftJson(String profileId,
                                      MainDraftSnapshotRules.Binding binding)
            throws JSONException {
        JSONObject draft = new JSONObject();
        draft.put("version", MainDraftSnapshotRules.DRAFT_VERSION);
        draft.put("profileId", profileId);
        draft.put(MainDraftSnapshotRules.BINDING_FIELD, binding.toJson());
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
            item.put("snSource", unit.snSource);
            item.put("grade", unit.grade);
            item.put("baseSn", unit.baseSn);
            item.put("baseSnSource", unit.baseSnSource);
            item.put("frontPhoto", unit.frontPhoto);
            item.put("backPhoto", unit.backPhoto);
            item.put("precheckStatus", unit.precheckStatus);
            item.put("workflowArtifactRequired", unit.workflowArtifactRequired);
            // Preserve the complete signed-v1 draft view for a high-versionCode rollback build.
            // The current runtime owns the neutral field and submits only configured artifacts.
            LegacyDraftArtifactRules.write(item, unit.legacyWorkflowArtifactPath,
                unit.workflowArtifactRequired);
            if (unit.legacyDefective) item.put("defective", true);
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
            if (!unit.workflowArtifacts.isEmpty()) {
                JSONObject workflowArtifacts = new JSONObject();
                for (Map.Entry<String, String> entry : unit.workflowArtifacts.entrySet()) {
                    workflowArtifacts.put(entry.getKey(), entry.getValue());
                }
                item.put("workflowArtifacts", workflowArtifacts);
            }
            if (!unit.pluginSns.isEmpty()) {
                JSONObject pluginSns = new JSONObject();
                for (Map.Entry<String, String> entry : unit.pluginSns.entrySet()) pluginSns.put(entry.getKey(), entry.getValue());
                item.put("pluginSns", pluginSns);
            }
            array.put(item);
        }
        draft.put("units", array);
        return draft;
    }

    private boolean saveDraft() {
        return saveDraft(false);
    }

    private boolean saveDraft(boolean durable) {
        try {
            String profileId = currentProfileId();
            if (profileId.isEmpty()) return false;
            MainDraftSnapshotRules.Binding current = mainDraftBindingForProfile(profileId);
            JSONObject store = loadDraftStore();
            JSONObject drafts = draftMap(store);
            JSONObject existing = drafts.optJSONObject(profileId);
            boolean previousStepReceiptPresent =
                hasStoredPreviousStepSubmissionAttempt();
            boolean uploadBarrierPresent = hasStoredUploadReplayBarrier();
            if (existing != null && (draftHasUnsubmittedUnits(existing)
                    || previousStepReceiptPresent || uploadBarrierPresent)) {
                MainDraftSnapshotRules.RestoreDecision decision =
                    MainDraftSnapshotRules.evaluate(existing, current, null,
                        BuildConfig.VERSION_CODE, "");
                if (decision.kind != MainDraftSnapshotRules.RestoreKind.EXACT) {
                    // Never overwrite or remove a queue merely because current in-memory state
                    // happens to share its profile id. It remains recoverable under its exact Panel.
                    Diagnostics.append(this, "Draft save blocked by stored binding profile="
                        + profileId + " reason=" + decision.reason);
                    return false;
                }
            }
            // A previous-step receipt may still need the exact terminal unit after a crash. Keep
            // that terminal snapshot until the receipt is retired; ordinary queue cleanup removes
            // it immediately afterwards. Without this exception, the last unit would disappear
            // before recovery could prove which chain reached a terminal state.
            boolean retainTerminalForRemoteRecovery =
                (previousStepReceiptPresent || uploadBarrierPresent) && !units.isEmpty();
            if (!hasUnsubmittedUnits() && !retainTerminalForRemoteRecovery) {
                drafts.remove(profileId);
                writeDraftStore(store, durable);
                return true;
            }
            drafts.put(profileId, buildDraftJson(profileId, current));
            writeDraftStore(store, durable);
            return true;
        } catch (Exception exc) {
            appendLog(t("draft_save_failed") + exc.getMessage());
            return false;
        }
    }

    // --- Manual queue backup: save the current queue on purpose, reload it another day to keep uploading. ---
    // Durability is the whole point here. AtomicFile-backed scoped/rollback files and synchronous
    // scoped/rollback preferences are reconciled by exact Panel ownership + savedAt before use.
    private File legacyQueueBackupFile() {
        return new File(getFilesDir(), "queue-backup.json");
    }

    private File scopedQueueBackupFile() {
        return new File(getFilesDir(), "queue-backup-" + panelStateNamespace() + ".json");
    }

    private ManualQueueDeleteStorage manualQueueDeleteStorage() {
        return new ManualQueueDeleteStorage(
            this, prefs, MANUAL_QUEUE_KEY, ROLLBACK_GLOBAL_OWNER_KEY);
    }

    /** Recover PREPARED/COMMITTED queue deletion state before any consumer observes its mirrors. */
    private boolean recoverManualQueueDeleteTransaction() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                ManualQueueDeleteTransaction.recover(
                    manualQueueDeleteStorage(), currentConnectionNamespace(), MANUAL_QUEUE_KEY);
                manualQueueDeleteRecoveryBlocked = false;
                return true;
            } catch (Exception failure) {
                manualQueueDeleteRecoveryBlocked = true;
                Diagnostics.append(this, "Queue delete recovery blocked: "
                    + conciseError(failure));
                return false;
            }
        }
    }

    /** Retire only a verified completed-delete marker, before the first byte of a new save. */
    private boolean clearManualQueueDeleteTombstoneForSave() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!recoverManualQueueDeleteTransaction()) return false;
            try {
                ManualQueueDeleteTransaction.clearCommittedTombstoneForNewSave(
                    manualQueueDeleteStorage(), currentConnectionNamespace(), MANUAL_QUEUE_KEY);
                manualQueueDeleteRecoveryBlocked = false;
                return true;
            } catch (Exception failure) {
                manualQueueDeleteRecoveryBlocked = true;
                Diagnostics.append(this, "Queue delete tombstone clear blocked: "
                    + conciseError(failure));
                return false;
            }
        }
    }

    private boolean migrateLegacyQueueBackupFile() {
        if (!recoverManualQueueDeleteTransaction()) return false;
        reconcileManualQueueCopies(true);
        return !manualQueueDeleteRecoveryBlocked
            && !blockedRollbackMirrors.contains(MANUAL_QUEUE_KEY);
    }

    private boolean writeQueueBackupFileAtomic(String json) {
        if (blockedRollbackMirrors.contains(MANUAL_QUEUE_KEY)) return false;
        boolean scoped = writeQueueBackupFileAtomic(scopedQueueBackupFile(), json);
        boolean legacy = writeQueueBackupFileAtomic(legacyQueueBackupFile(), json);
        if (!scoped || !legacy) return false;
        try {
            return json.equals(AtomicCacheFile.readUtf8(scopedQueueBackupFile()))
                && json.equals(AtomicCacheFile.readUtf8(legacyQueueBackupFile()));
        } catch (Exception verifyFailure) {
            Diagnostics.append(this, "Queue backup verification failed: "
                + verifyFailure.getMessage());
            return false;
        }
    }

    private boolean writeQueueBackupFileAtomic(File target, String json) {
        try {
            AtomicCacheFile.write(target, json.getBytes(StandardCharsets.UTF_8));
            return json.equals(AtomicCacheFile.readUtf8(target));
        } catch (Exception exc) {
            Diagnostics.append(this, "Queue backup atomic write failed: " + exc.getMessage());
            return false;
        }
    }

    private boolean mirrorManualQueueCopies(String json) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!clearManualQueueDeleteTombstoneForSave()) return false;
            if (!writeQueueBackupFileAtomic(json)) return false;
            return putMirroredRollbackPreference(
                prefs.edit(), MANUAL_QUEUE_KEY, json).commit();
        }
    }

    private RollbackMirrorRules.Candidate manualQueueFileCandidate(
            String source, File file) {
        if (!AtomicCacheFile.hasRecoverableCopy(file)) {
            return RollbackMirrorRules.Candidate.absent(source);
        }
        try {
            return manualQueueCandidate(source, AtomicCacheFile.readUtf8(file));
        } catch (Exception unreadable) {
            return RollbackMirrorRules.Candidate.of(
                source, "", false, false, false, 0L);
        }
    }

    private RollbackMirrorRules.Candidate manualQueuePreferenceCandidate(
            String source, String key) {
        if (!prefs.contains(key)) return RollbackMirrorRules.Candidate.absent(source);
        return manualQueueCandidate(source, prefs.getString(key, ""));
    }

    private RollbackMirrorRules.Candidate manualQueueCandidate(String source, String raw) {
        try {
            JSONObject snapshot = new JSONObject(raw);
            int versionNumber = RollbackMirrorRules.exactInteger(snapshot.opt("version"));
            JSONArray snapshotUnits = snapshot.optJSONArray("units");
            boolean shapeValid = (versionNumber == 1 || versionNumber == 2
                    || versionNumber == MainDraftSnapshotRules.DRAFT_VERSION)
                && !snapshot.optString("profileId", "").isEmpty()
                && validRollbackDraftUnits(snapshotUnits)
                && RollbackMirrorRules.exactSavedAt(snapshot) > 0L;
            boolean selfBound = shapeValid
                && MainDraftSnapshotRules.hasSelfBindingForConnection(
                    snapshot, currentConnectionNamespace());
            boolean receiptOwnedLegacy = shapeValid && versionNumber <
                MainDraftSnapshotRules.DRAFT_VERSION
                && RollbackMirrorRules.receiptIdentifies(
                    activeRollbackMirrorReceipt(MANUAL_QUEUE_KEY),
                    currentConnectionNamespace(), MANUAL_QUEUE_KEY);
            boolean owned = shapeValid && (selfBound || receiptOwnedLegacy
                || MainDraftSnapshotRules.belongsToConnection(
                    snapshot, currentConnectionNamespace(), activeCatalogVersion,
                    legacyMainDraftMigrationReceipt(), BuildConfig.VERSION_CODE,
                    currentPanelPairSha256()));
            return RollbackMirrorRules.Candidate.of(source, raw, shapeValid,
                owned, selfBound, RollbackMirrorRules.exactSavedAt(snapshot));
        } catch (Exception invalid) {
            return RollbackMirrorRules.Candidate.of(
                source, raw, false, false, false, 0L);
        }
    }

    private boolean validRollbackDraftUnits(JSONArray draftUnits) {
        if (draftUnits == null || draftUnits.length() == 0) return false;
        for (int index = 0; index < draftUnits.length(); index++) {
            JSONObject unit = draftUnits.optJSONObject(index);
            if (unit == null || unit.optString("sn", "").trim().isEmpty()
                    || RollbackMirrorRules.exactInteger(unit.opt("sequence")) <= 0) {
                return false;
            }
        }
        return true;
    }

    private String reconcileManualQueueCopies(boolean mirror) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!recoverManualQueueDeleteTransaction()) return "";
            try {
                if (manualQueueDeleteStorage().auxiliaryRecoveryEvidencePresent(
                        currentConnectionNamespace())) {
                    manualQueueDeleteRecoveryBlocked = true;
                    blockedRollbackMirrors.add(MANUAL_QUEUE_KEY);
                    Diagnostics.append(this,
                        "Queue backup storage has unresolved recovery residue");
                    return "";
                }
            } catch (Exception unreadableResidue) {
                manualQueueDeleteRecoveryBlocked = true;
                blockedRollbackMirrors.add(MANUAL_QUEUE_KEY);
                Diagnostics.append(this, "Queue backup recovery residue unreadable: "
                    + conciseError(unreadableResidue));
                return "";
            }
            List<RollbackMirrorRules.Candidate> copies = new ArrayList<>();
            copies.add(manualQueueFileCandidate("scoped-file", scopedQueueBackupFile()));
            copies.add(manualQueueFileCandidate("legacy-file", legacyQueueBackupFile()));
            addLegacyQueueTempCandidate(copies, "scoped-tmp", scopedQueueBackupFile());
            addLegacyQueueTempCandidate(copies, "legacy-tmp", legacyQueueBackupFile());
            copies.add(manualQueuePreferenceCandidate("scoped-pref",
                panelStatePreferenceKey(MANUAL_QUEUE_KEY)));
            copies.add(manualQueuePreferenceCandidate("legacy-pref", MANUAL_QUEUE_KEY));
            RollbackMirrorRules.Decision decision = RollbackMirrorRules.chooseNewestSnapshot(
                copies, activeRollbackMirrorReceipt(MANUAL_QUEUE_KEY),
                currentConnectionNamespace(), MANUAL_QUEUE_KEY);
            if (decision.blocked()) {
                blockedRollbackMirrors.add(MANUAL_QUEUE_KEY);
                Diagnostics.append(this, "Queue backup mirror blocked: " + decision.reason);
                return "";
            }
            blockedRollbackMirrors.remove(MANUAL_QUEUE_KEY);
            if (decision.source == RollbackMirrorRules.Source.NONE) return "";
            if (mirror && decision.mirrorAllowed && !mirrorManualQueueCopies(decision.value)) {
                blockedRollbackMirrors.add(MANUAL_QUEUE_KEY);
                Diagnostics.append(this, "Queue backup mirror commit failed");
                return "";
            }
            return decision.value;
        }
    }

    private void addLegacyQueueTempCandidate(
            List<RollbackMirrorRules.Candidate> copies, String source, File target) {
        if (AtomicCacheFile.hasRecoverableCopy(target)) return;
        try {
            copies.add(manualQueueCandidate(
                source, AtomicCacheFile.readLegacyTempUtf8IfUncommitted(target)));
        } catch (Exception absentOrUnreadable) {
            // No complete old temp is a normal absence. Invalid non-empty temps are deliberately
            // added as blocked candidates so another copy cannot silently overwrite the evidence.
            File temp = new File(target.getPath() + ".tmp");
            if (temp.exists()) {
                copies.add(RollbackMirrorRules.Candidate.of(
                    source, "", false, false, false, 0L));
            }
        }
    }

    private void saveQueueSnapshot() {
        if (units.isEmpty()) {
            toast(t("queue_backup_empty"));
            return;
        }
        reconcileManualQueueCopies(false);
        if (manualQueueDeleteRecoveryBlocked
                || blockedRollbackMirrors.contains(MANUAL_QUEUE_KEY)) {
            alert(t("queue_backup_save_failed"), t("draft_binding_locked_detail"));
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
        boolean saved = mirrorManualQueueCopies(json);
        // Prove it: read the durable copy back and confirm the unit count survived the round trip.
        boolean verified = false;
        JSONObject readBack = loadQueueSnapshot();
        if (readBack != null) {
            JSONArray arr = readBack.optJSONArray("units");
            verified = arr != null && arr.length() == count;
        }
        Diagnostics.append(this, "Queue snapshot saved units=" + count
            + " mirrored=" + saved + " verified=" + verified);
        if (saved && verified) {
            toast(t("queue_backup_saved") + count);
        } else {
            // Never let the user walk away believing a save stuck when it did not.
            alert(t("queue_backup_save_failed"), "mirror=" + saved + " verify=" + verified);
        }
    }

    private JSONObject loadQueueSnapshot() {
        String raw = reconcileManualQueueCopies(true);
        if (raw.isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception exc) {
            Diagnostics.append(this, "Queue backup selected copy unparseable (all copies kept): "
                + exc.getMessage());
            return null;
        }
    }

    /** Explicitly removes only the current Panel's queue through the crash-recovery transaction. */
    private boolean deleteQueueSnapshot() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            String raw = reconcileManualQueueCopies(true);
            if (raw.isEmpty() || manualQueueDeleteRecoveryBlocked
                    || blockedRollbackMirrors.contains(MANUAL_QUEUE_KEY)) return false;
            ManualQueueDeleteStorage storage = manualQueueDeleteStorage();
            String connection = currentConnectionNamespace();
            try {
                ManualQueueDeleteTransaction.delete(
                    storage, connection, MANUAL_QUEUE_KEY, raw, false);
                if (!ManualQueueDeleteTransaction.committedDeletionComplete(
                        storage, connection, MANUAL_QUEUE_KEY)) {
                    throw new IOException("Queue delete commit is not durably complete");
                }
                manualQueueDeleteRecoveryBlocked = false;
                blockedRollbackMirrors.remove(MANUAL_QUEUE_KEY);
                return true;
            } catch (Exception failure) {
                // delete() already attempts synchronous convergence. Re-run restart recovery so an
                // I/O failure which actually crossed COMMITTED is reported as success, while a
                // PREPARED failure is reported as a failed delete with the exact queue restored.
                boolean recovered = recoverManualQueueDeleteTransaction();
                if (recovered) {
                    try {
                        if (ManualQueueDeleteTransaction.committedDeletionComplete(
                                storage, connection, MANUAL_QUEUE_KEY)) {
                            blockedRollbackMirrors.remove(MANUAL_QUEUE_KEY);
                            return true;
                        }
                    } catch (Exception ignored) {
                        // A malformed/uncertain marker is handled by reconciliation below.
                    }
                    String restored = reconcileManualQueueCopies(false);
                    if (raw.equals(restored)
                            && !blockedRollbackMirrors.contains(MANUAL_QUEUE_KEY)) {
                        Diagnostics.append(this,
                            "Queue backup delete failed; exact mirrors restored");
                        return false;
                    }
                }
                manualQueueDeleteRecoveryBlocked = true;
                Diagnostics.append(this, "Queue backup delete blocked: "
                    + conciseError(failure));
                return false;
            }
        }
    }

    /** Any current-connection queue artifact is durable workload, even if it is unreadable. */
    private boolean manualQueueRecoveryEvidencePresent() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!recoverManualQueueDeleteTransaction()) return true;
            try {
                return ManualQueueDeleteTransaction.blocksPanelPromotion(
                    manualQueueDeleteStorage(), currentConnectionNamespace(),
                    MANUAL_QUEUE_KEY,
                    blockedRollbackMirrors.contains(MANUAL_QUEUE_KEY));
            } catch (Exception unreadable) {
                manualQueueDeleteRecoveryBlocked = true;
                Diagnostics.append(this, "Queue promotion evidence unreadable: "
                    + conciseError(unreadable));
                return true;
            }
        }
    }

    private void restoreQueueSnapshot() {
        UploadReplayBarrier.RestoreResult uploadBarrier =
            blockingUploadReplayBarrier();
        if (uploadBarrier != null) {
            showUploadReplayBarrierBlock(uploadBarrier);
            return;
        }
        if (mainDraftRestoreBlocked() || hasStoredPreviousStepSubmissionAttempt()) {
            toast(t("submit_running"));
            return;
        }
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
        if (hasStoredUploadReplayBarrier()
                || hasStoredPreviousStepSubmissionAttempt()) {
            Diagnostics.append(this,
                "Draft clear blocked by remote safety journal");
            return;
        }
        try {
            JSONObject store = loadDraftStore();
            JSONObject drafts = draftMap(store);
            JSONObject existing = drafts.optJSONObject(profileId);
            if (draftHasUnsubmittedUnits(existing)) {
                MainDraftSnapshotRules.Binding current =
                    mainDraftBindingForProfile(profileId);
                if (MainDraftSnapshotRules.evaluate(existing, current, null,
                        BuildConfig.VERSION_CODE, "").kind
                        != MainDraftSnapshotRules.RestoreKind.EXACT) {
                    Diagnostics.append(this,
                        "Draft clear kept mismatched snapshot profile=" + profileId);
                    return;
                }
            }
            drafts.remove(profileId);
            writeDraftStore(store);
        } catch (Exception exc) {
            Diagnostics.append(this, "Draft clear failed: " + exc.getMessage());
        }
    }

    private void clearAllDrafts() {
        if (hasStoredUploadReplayBarrier()
                || hasStoredPreviousStepSubmissionAttempt()) {
            Diagnostics.append(this,
                "All-draft clear blocked by remote safety journal");
            return;
        }
        boolean cleared = prefs.edit().remove(DRAFT_KEY).remove(DRAFT_STORE_KEY)
            .remove(draftStorePreferenceKey())
            .remove(rollbackMirrorReceiptPreferenceKey(DRAFT_STORE_KEY)).commit();
        if (cleared) blockedRollbackMirrors.remove(DRAFT_STORE_KEY);
        else blockedRollbackMirrors.add(DRAFT_STORE_KEY);
    }

    private JSONObject loadDraftStore() {
        JSONObject store = new JSONObject();
        String storeKey = draftStorePreferenceKey();
        RollbackMirrorRules.Candidate scoped =
            draftStoreCandidate("scoped-pref", storeKey);
        RollbackMirrorRules.Candidate legacy =
            draftStoreCandidate("legacy-pref", DRAFT_STORE_KEY);
        RollbackMirrorRules.Decision decision = RollbackMirrorRules.chooseDraftStore(
            scoped, legacy, activeRollbackMirrorReceipt(DRAFT_STORE_KEY),
            currentConnectionNamespace(), DRAFT_STORE_KEY);
        String raw = decision.value;
        if (decision.tombstone()) {
            boolean removed = prefs.edit().remove(storeKey).remove(DRAFT_STORE_KEY)
                .remove(DRAFT_KEY)
                .remove(rollbackMirrorReceiptPreferenceKey(DRAFT_STORE_KEY)).commit();
            if (removed) {
                blockedRollbackMirrors.remove(DRAFT_STORE_KEY);
                Diagnostics.append(this, "Applied receipt-proven signed-v1 draft tombstone");
            } else {
                blockedRollbackMirrors.add(DRAFT_STORE_KEY);
                Diagnostics.append(this, "Draft tombstone commit failed; scoped copy retained");
            }
        } else if (decision.blocked()) {
            blockedRollbackMirrors.add(DRAFT_STORE_KEY);
            Diagnostics.append(this, "Draft rollback mirror blocked: " + decision.reason);
        } else {
            blockedRollbackMirrors.remove(DRAFT_STORE_KEY);
            if (decision.source != RollbackMirrorRules.Source.NONE
                    && decision.mirrorAllowed
                    && !putMirroredRollbackPreference(
                        prefs.edit(), DRAFT_STORE_KEY, raw).commit()) {
                blockedRollbackMirrors.add(DRAFT_STORE_KEY);
                Diagnostics.append(this, "Draft rollback mirror commit failed");
            }
        }
        if (!raw.isEmpty()) {
            try {
                store = new JSONObject(raw);
            } catch (Exception exc) {
                // Do not delete either copy. A later recovery build or operator can still inspect it.
                Diagnostics.append(this, "Keeping unreadable draft store: " + exc.getMessage());
                store = new JSONObject();
            }
        }
        try {
            draftMap(store);
            if (!blockedRollbackMirrors.contains(DRAFT_STORE_KEY)) {
                migrateLegacyDraft(store);
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Draft store load failed: " + exc.getMessage());
        }
        return store;
    }

    private RollbackMirrorRules.Candidate draftStoreCandidate(String source, String key) {
        if (!prefs.contains(key)) return RollbackMirrorRules.Candidate.absent(source);
        String raw = prefs.getString(key, "");
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject drafts = root.optJSONObject("drafts");
            boolean valid = drafts != null
                && RollbackMirrorRules.exactInteger(root.opt("version")) == 2;
            boolean owned = valid;
            boolean selfBound = valid;
            long latestSavedAt = 0L;
            JSONArray names = drafts == null ? null : drafts.names();
            if (names == null || names.length() == 0) valid = false;
            boolean rollbackReceiptOwned = RollbackMirrorRules.receiptIdentifies(
                activeRollbackMirrorReceipt(DRAFT_STORE_KEY), currentConnectionNamespace(),
                DRAFT_STORE_KEY);
            for (int index = 0; names != null && index < names.length(); index++) {
                String profileId = names.optString(index, "");
                JSONObject draft = drafts.optJSONObject(profileId);
                long savedAt = RollbackMirrorRules.exactSavedAt(draft);
                boolean shape = draft != null && !profileId.isEmpty()
                    && profileId.equals(draft.optString("profileId", ""))
                    && validRollbackDraftUnits(draft.optJSONArray("units"))
                    && savedAt > 0L;
                boolean draftSelfBound = shape
                    && MainDraftSnapshotRules.hasSelfBindingForConnection(
                        draft, currentConnectionNamespace());
                boolean draftOwned = shape && (draftSelfBound
                    || MainDraftSnapshotRules.belongsToConnection(
                        draft, currentConnectionNamespace(), activeCatalogVersion,
                        legacyMainDraftMigrationReceipt(), BuildConfig.VERSION_CODE,
                        currentPanelPairSha256())
                    || (rollbackReceiptOwned && !draft.has(
                        MainDraftSnapshotRules.BINDING_FIELD)));
                valid &= shape;
                owned &= draftOwned;
                selfBound &= draftSelfBound;
                latestSavedAt = Math.max(latestSavedAt, savedAt);
            }
            return RollbackMirrorRules.Candidate.of(source, raw, valid, owned,
                selfBound, latestSavedAt);
        } catch (Exception invalid) {
            return RollbackMirrorRules.Candidate.of(
                source, raw, false, false, false, 0L);
        }
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
                    if (!MainDraftSnapshotRules.belongsToConnection(
                            legacy, currentConnectionNamespace(), activeCatalogVersion,
                            legacyMainDraftMigrationReceipt(), BuildConfig.VERSION_CODE,
                            currentPanelPairSha256())) {
                        blockedRollbackMirrors.add(DRAFT_STORE_KEY);
                        Diagnostics.append(this,
                            "Legacy single-draft ownership could not be proven; bytes kept");
                        return;
                    }
                    draftMap(store).put(profileId, legacy);
                    writeDraftStore(store, true);
                    Diagnostics.append(this, "Legacy draft migrated profile=" + profileId);
                    return;
                }
            }
        } catch (Exception exc) {
            Diagnostics.append(this, "Keeping unreadable saved draft: " + exc.getMessage());
        }
        // Malformed, empty, or ownerless legacy bytes remain untouched for explicit recovery.
        blockedRollbackMirrors.add(DRAFT_STORE_KEY);
    }

    private void writeDraftStore(JSONObject store) throws JSONException {
        writeDraftStore(store, false);
    }

    private void writeDraftStore(JSONObject store, boolean durable) throws JSONException {
        if (blockedRollbackMirrors.contains(DRAFT_STORE_KEY)) {
            throw new JSONException("draft rollback mirror is unresolved");
        }
        JSONObject drafts = draftMap(store);
        String storeKey = draftStorePreferenceKey();
        SharedPreferences.Editor editor;
        if (drafts.length() <= 0) {
            editor = prefs.edit().remove(storeKey).remove(DRAFT_STORE_KEY).remove(DRAFT_KEY)
                .remove(rollbackMirrorReceiptPreferenceKey(DRAFT_STORE_KEY));
        } else {
            store.put("version", 2);
            String serialized = store.toString();
            // Scoped storage prevents one Panel from reading another Panel's queue. The exact
            // global mirror is retained only for rollback to v1.0.4-v1.0.6, which reads this key.
            // migrateLegacyPanelBoundState removes it before the Panel/key can change.
            editor = putMirroredRollbackPreference(
                prefs.edit(), DRAFT_STORE_KEY, serialized).remove(DRAFT_KEY);
        }
        if (durable) {
            if (!editor.commit()) {
                throw new IllegalStateException("draft SharedPreferences commit failed");
            }
        } else {
            editor.apply();
        }
    }

    private String draftStorePreferenceKey() {
        return panelStatePreferenceKey(DRAFT_STORE_KEY);
    }

    private String panelStateNamespace() {
        return AppConfig.connectionNamespaceId(
            AppConfig.panelBase(this), AppConfig.catalogKey(this));
    }

    private String panelStatePreferenceKey(String baseKey) {
        return baseKey + "_" + panelStateNamespace();
    }

    private String dailyStatsPreferenceKey(String date) {
        return panelStatePreferenceKey(DAILY_STATS_PREFIX + date);
    }

    private String rollbackMirrorReceiptPreferenceKey(String logicalKey) {
        return RollbackMirrorRules.receiptPreferenceKey(
            currentConnectionNamespace(), logicalKey);
    }

    private JSONObject rollbackMirrorReceipt(String logicalKey) {
        try {
            String raw = prefs.getString(
                rollbackMirrorReceiptPreferenceKey(logicalKey), "");
            return raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private boolean rollbackGlobalOwnerMatches() {
        return currentConnectionNamespace().equals(
            prefs.getString(ROLLBACK_GLOBAL_OWNER_KEY, ""));
    }

    private JSONObject activeRollbackMirrorReceipt(String logicalKey) {
        return rollbackGlobalOwnerMatches() ? rollbackMirrorReceipt(logicalKey) : null;
    }

    private SharedPreferences.Editor putMirroredRollbackPreference(
            SharedPreferences.Editor editor, String logicalKey, String value) {
        JSONObject receipt = RollbackMirrorRules.newReceipt(
            currentConnectionNamespace(), logicalKey, value);
        return editor.putString(panelStatePreferenceKey(logicalKey), value)
            .putString(logicalKey, value)
            .putString(ROLLBACK_GLOBAL_OWNER_KEY, currentConnectionNamespace())
            .putString(rollbackMirrorReceiptPreferenceKey(logicalKey), receipt.toString());
    }

    private boolean validRollbackPreference(String logicalKey, String raw) {
        if (ROUND_LEDGER_KEY.equals(logicalKey)) {
            return LegacyPanelStateMigrationRules.validRoundLedger(raw);
        }
        if (logicalKey.startsWith("prevRoundMissing_")) {
            return RollbackMirrorRules.validStringArray(raw);
        }
        if (logicalKey.startsWith(DAILY_STATS_PREFIX)) {
            return LegacyPanelStateMigrationRules.validDailyStats(raw);
        }
        return RollbackMirrorRules.validJsonObject(raw);
    }

    private boolean legacyRollbackPreferenceOwnedByActiveCatalog(
            String logicalKey, String raw) {
        if (ROUND_LEDGER_KEY.equals(logicalKey)) {
            return LegacyPanelStateMigrationRules.validRoundLedger(raw, allProfiles);
        }
        if (logicalKey.startsWith("prevRoundMissing_")) {
            return LegacyPanelStateMigrationRules.validPreviousRoundKey(
                logicalKey, allProfiles);
        }
        if (logicalKey.startsWith(DAILY_STATS_PREFIX)) {
            return LegacyPanelStateMigrationRules.validDailyStats(raw, allProfiles);
        }
        return false;
    }

    /** Adopt only an exact receipt-proven signed-v1 change for this active Panel. */
    private String readAndMirrorRollbackPreference(String legacyKey, String fallback) {
        String scopedKey = panelStatePreferenceKey(legacyKey);
        boolean hasScoped = prefs.contains(scopedKey);
        boolean hasLegacy = prefs.contains(legacyKey);
        if (!hasScoped && !hasLegacy) return fallback;
        String scopedRaw = hasScoped ? prefs.getString(scopedKey, fallback) : "";
        String legacyRaw = hasLegacy ? prefs.getString(legacyKey, fallback) : "";
        String receiptKey = rollbackMirrorReceiptPreferenceKey(legacyKey);
        boolean receiptPresent = prefs.contains(receiptKey);
        JSONObject persistedReceipt = activeRollbackMirrorReceipt(legacyKey);
        boolean initialLegacyKey = ROUND_LEDGER_KEY.equals(legacyKey)
            || legacyKey.startsWith("prevRoundMissing_")
            || legacyKey.startsWith(DAILY_STATS_PREFIX);
        boolean legacyValid = validRollbackPreference(legacyKey, legacyRaw);
        boolean legacyOwnedByActiveCatalog = initialLegacyKey && legacyValid
            && legacyRollbackPreferenceOwnedByActiveCatalog(legacyKey, legacyRaw);
        boolean exactPairReceipt = initialLegacyKey
            && MainDraftSnapshotRules.verifiedLegacyMigrationReceipt(
                legacyMainDraftMigrationReceipt(), currentConnectionNamespace(),
                activeCatalogVersion, BuildConfig.VERSION_CODE,
                currentPanelPairSha256());
        JSONObject adoptionReceipt = initialLegacyKey
            ? RollbackMirrorRules.initialLegacyAdoptionReceipt(
                hasScoped, hasLegacy, legacyRaw, legacyOwnedByActiveCatalog,
                receiptPresent,
                !prefs.contains(ROLLBACK_GLOBAL_OWNER_KEY)
                    || rollbackGlobalOwnerMatches(),
                exactPairReceipt, currentConnectionNamespace(), legacyKey)
            : null;
        JSONObject receipt = persistedReceipt != null
            ? persistedReceipt : adoptionReceipt;
        boolean receiptOwned = RollbackMirrorRules.receiptIdentifies(
            receipt, currentConnectionNamespace(), legacyKey);
        RollbackMirrorRules.Candidate scoped = hasScoped
            ? RollbackMirrorRules.Candidate.of("scoped-pref", scopedRaw,
                validRollbackPreference(legacyKey, scopedRaw), true, false, 0L)
            : RollbackMirrorRules.Candidate.absent("scoped-pref");
        boolean legacyOwned = receiptOwned
            || (hasScoped && scopedRaw.equals(legacyRaw));
        RollbackMirrorRules.Candidate legacy = hasLegacy
            ? RollbackMirrorRules.Candidate.of("legacy-pref", legacyRaw,
                legacyValid, legacyOwned, false, 0L)
            : RollbackMirrorRules.Candidate.absent("legacy-pref");
        RollbackMirrorRules.Decision decision =
            RollbackMirrorRules.chooseReceiptBoundValue(
                scoped, legacy, receipt, currentConnectionNamespace(), legacyKey);
        if (decision.blocked()) {
            blockedRollbackMirrors.add(legacyKey);
            Diagnostics.append(this, "Rollback preference blocked keyHash="
                + RollbackMirrorRules.sha256(legacyKey).substring(0, 12)
                + " reason=" + decision.reason);
            return fallback;
        }
        blockedRollbackMirrors.remove(legacyKey);
        if (decision.source != RollbackMirrorRules.Source.NONE
                && decision.mirrorAllowed) {
            boolean committed = putMirroredRollbackPreference(
                prefs.edit(), legacyKey, decision.value).commit();
            if (!committed || !rollbackPreferenceMirrored(legacyKey, fallback)) {
                blockedRollbackMirrors.add(legacyKey);
                Diagnostics.append(this, "Rollback preference mirror verification failed keyHash="
                    + RollbackMirrorRules.sha256(legacyKey).substring(0, 12));
                return fallback;
            }
        }
        return decision.source == RollbackMirrorRules.Source.NONE
            ? fallback : decision.value;
    }

    private boolean rollbackPreferenceMirrored(String legacyKey, String fallback) {
        String scopedKey = panelStatePreferenceKey(legacyKey);
        boolean hasScoped = prefs.contains(scopedKey);
        boolean hasLegacy = prefs.contains(legacyKey);
        if (!hasScoped && !hasLegacy) return true;
        if (!hasScoped || !hasLegacy || blockedRollbackMirrors.contains(legacyKey)) {
            return false;
        }
        String scoped = prefs.getString(scopedKey, fallback);
        String legacy = prefs.getString(legacyKey, fallback);
        return scoped.equals(legacy)
            && validRollbackPreference(legacyKey, scoped)
            && rollbackGlobalOwnerMatches()
            && RollbackMirrorRules.receiptMatches(activeRollbackMirrorReceipt(legacyKey),
                currentConnectionNamespace(), legacyKey, scoped);
    }

    /** Bind legacy global state to the old/current Panel before cache or connection ownership moves. */
    private boolean reconcileLegacyPanelBoundState(boolean retireLegacyQueueFile) {
        loadDraftStore();
        readAndMirrorRollbackPreference(ROUND_LEDGER_KEY, "[]");
        if (!migrateLegacyQueueBackupFile()) return false;
        List<String> dynamicKeys = new ArrayList<>();
        String scopedSuffix = "_" + currentConnectionNamespace();
        for (String prefKey : prefs.getAll().keySet()) {
            String logicalKey = prefKey.endsWith(scopedSuffix)
                ? prefKey.substring(0, prefKey.length() - scopedSuffix.length()) : prefKey;
            String dailySuffix = logicalKey.startsWith(DAILY_STATS_PREFIX)
                ? logicalKey.substring(DAILY_STATS_PREFIX.length()) : "";
            boolean legacyPreviousRound = logicalKey.startsWith("prevRoundMissing_")
                && !logicalKey.matches(".*_[0-9a-f]{20}$");
            if (dailySuffix.matches("\\d{4}-\\d{2}-\\d{2}") || legacyPreviousRound) {
                if (!dynamicKeys.contains(logicalKey)) dynamicKeys.add(logicalKey);
            }
        }
        for (String prefKey : dynamicKeys) readAndMirrorRollbackPreference(prefKey, "");
        if (!blockedRollbackMirrors.isEmpty()) return false;
        if (!rollbackPreferenceMirrored(DRAFT_STORE_KEY, "")
                || !rollbackPreferenceMirrored(MANUAL_QUEUE_KEY, "")
                || !rollbackPreferenceMirrored(ROUND_LEDGER_KEY, "[]")) return false;
        for (String prefKey : dynamicKeys) {
            if (!rollbackPreferenceMirrored(prefKey, "")) return false;
        }
        // Ordinary same-connection cache promotion must retain every signed-v1 recovery copy.
        // Only a Panel/key change retires the legacy filename after proving an exact global fallback;
        // otherwise a failed/no-prewarm upgrade could destroy the last old-reader-compatible file.
        if (!retireLegacyQueueFile) return true;
        File legacyQueue = legacyQueueBackupFile();
        File scopedQueue = scopedQueueBackupFile();
        if (AtomicCacheFile.hasRecoverableCopy(legacyQueue)
                || AtomicCacheFile.hasRecoverableCopy(scopedQueue)) {
            try {
                String scopedRaw = AtomicCacheFile.readUtf8(scopedQueue);
                String legacyRaw = AtomicCacheFile.readUtf8(legacyQueue);
                if (scopedRaw.isEmpty() || !scopedRaw.equals(legacyRaw)) return false;
                if (!putMirroredRollbackPreference(
                        prefs.edit(), MANUAL_QUEUE_KEY, scopedRaw).commit()) return false;
            } catch (Exception unreadable) {
                return false;
            }
        }
        AtomicCacheFile.delete(legacyQueue);
        return !AtomicCacheFile.hasRecoverableCopy(legacyQueue)
            && !new File(legacyQueue.getPath() + ".tmp").exists();
    }

    /** Bind and retire cross-Panel legacy mirrors immediately before changing Panel/key. */
    private boolean migrateLegacyPanelBoundState() {
        return reconcileLegacyPanelBoundState(true);
    }

    /**
     * Promotion is destructive to an unbound active cache: once overwritten, its legacy state can
     * no longer acquire the exact prewarm receipt. Resolve every old mirror first and preserve all
     * copies; an unsupported signed-v1 camera continuation also pins the old cache unchanged.
     */
    private boolean legacyPanelStateReadyForCachePromotion() {
        boolean resolved = reconcileLegacyPanelBoundState(false);
        Map<String, ?> settings;
        try {
            settings = prefs.getAll();
        } catch (RuntimeException unreadable) {
            Diagnostics.append(this, "Legacy upgrade state unreadable; cache promotion blocked");
            return false;
        }
        boolean allowed = LegacyUpgradeSafetyRules.cachePromotionAllowed(resolved, settings);
        if (!allowed) {
            Diagnostics.append(this,
                LegacyUpgradeSafetyRules.pendingAStepEvidence(settings)
                    ? "Legacy A-step continuation retained; cache promotion blocked"
                    : "Unbound legacy Panel state retained; cache promotion blocked");
        }
        return allowed;
    }

    private boolean legacyAStepContinuationPresent() {
        try {
            return LegacyUpgradeSafetyRules.pendingAStepEvidence(prefs.getAll());
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    private Set<String> removeRollbackMirrors(SharedPreferences.Editor editor) {
        Set<String> removed = new LinkedHashSet<>();
        editor.remove(DRAFT_KEY).remove(DRAFT_STORE_KEY)
            .remove(MANUAL_QUEUE_KEY).remove(ROUND_LEDGER_KEY)
            .remove(ROLLBACK_GLOBAL_OWNER_KEY);
        Collections.addAll(removed, DRAFT_KEY, DRAFT_STORE_KEY,
            MANUAL_QUEUE_KEY, ROUND_LEDGER_KEY, ROLLBACK_GLOBAL_OWNER_KEY);
        for (String prefKey : prefs.getAll().keySet()) {
            String dailySuffix = prefKey.startsWith(DAILY_STATS_PREFIX)
                ? prefKey.substring(DAILY_STATS_PREFIX.length()) : "";
            boolean legacyPreviousRound = prefKey.startsWith("prevRoundMissing_")
                && !prefKey.matches(".*_[0-9a-f]{20}$");
            if (dailySuffix.matches("\\d{4}-\\d{2}-\\d{2}") || legacyPreviousRound) {
                editor.remove(prefKey);
                removed.add(prefKey);
            }
        }
        return removed;
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
        List<String> keys = new ArrayList<>();
        JSONObject resultMap = profile == null ? null : profile.optJSONObject("gradeMap");
        JSONArray names = resultMap == null ? null : resultMap.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.optString(i, "").trim();
            if (!key.isEmpty() && resultMap.optJSONObject(key) != null) keys.add(key);
        }
        return keys;
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

    private View profileSpinnerView(JSONArray spinnerProfiles, int position,
                                    boolean dropdown) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dropdown ? dp(12) : dp(8), dp(10), dropdown ? dp(12) : dp(8));
        row.setMinimumHeight(dp(dropdown ? 52 : 46));

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        int color = profileDotColor(spinnerProfiles, position);
        dotBg.setColor(color);
        dotBg.setStroke(dp(2), isLightColor(color) ? 0xFF777777 : 0x33000000);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        dotParams.setMargins(0, 0, dp(12), 0);
        row.addView(dot, dotParams);

        TextView name = text(profileName(spinnerProfiles, position),
            dropdown ? 20 : 18, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private String profileName(int position) {
        return profileName(profiles, position);
    }

    private String profileName(JSONArray spinnerProfiles, int position) {
        try {
            if (spinnerProfiles != null && position >= 0
                    && position < spinnerProfiles.length()) {
                return spinnerProfiles.getJSONObject(position).optString(
                    "displayName", "Profile " + (position + 1));
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
        return profileDotColor(profiles, position);
    }

    private int profileDotColor(JSONArray spinnerProfiles, int position) {
        JSONObject item;
        try {
            item = spinnerProfiles.getJSONObject(position);
        } catch (Exception exc) {
            return 0xFF64748B;
        }
        String explicit = item.optString("uiColor", "").trim();
        if (!explicit.isEmpty()) {
            Integer parsed = parseColor(explicit);
            if (parsed != null) return parsed;
        }
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
        private final JSONArray spinnerProfiles;

        ProfileSpinnerAdapter(JSONArray spinnerProfiles) {
            this.spinnerProfiles = spinnerProfiles == null
                ? new JSONArray() : spinnerProfiles;
        }

        @Override public int getCount() {
            return spinnerProfiles.length();
        }

        @Override public Object getItem(int position) {
            try {
                return spinnerProfiles.getJSONObject(position);
            } catch (JSONException exc) {
                return null;
            }
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return profileSpinnerView(spinnerProfiles, position, false);
        }

        @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return profileSpinnerView(spinnerProfiles, position, true);
        }
    }

    private int templateId() throws JSONException {
        return profile.getJSONObject("template").getInt("id");
    }

    private String selectedGrade() {
        if (!hasMultipleGradeChoices()) return firstGradeKey();
        int id = gradeGroup.getCheckedRadioButtonId();
        if (id == -1) return "";
        View checked = gradeGroup.findViewById(id);
        String key = checked == null || checked.getTag() == null ? "" : String.valueOf(checked.getTag());
        return hasGrade(key) ? key : "";
    }

    private void resetGradeSelection() {
        if (gradeGroup == null || !hasMultipleGradeChoices()) return;
        gradeGroup.clearCheck();
        updateGradeButtons();
    }

    private void ensureResultButtons() {
        if (gradeGroup == null || rebuildingResultButtons) return;
        List<String> keys = availableGrades();
        boolean current = gradeGroup.getChildCount() == keys.size();
        for (int i = 0; current && i < keys.size(); i++) {
            View child = gradeGroup.getChildAt(i);
            current = child instanceof RadioButton && keys.get(i).equals(String.valueOf(child.getTag()));
        }
        if (current) {
            for (int i = 0; i < keys.size(); i++) {
                ((RadioButton) gradeGroup.getChildAt(i)).setText(resultLabel(keys.get(i)));
            }
            return;
        }
        rebuildingResultButtons = true;
        try {
            gradeGroup.removeAllViews();
            boolean stacked = keys.size() > 3;
            gradeGroup.setOrientation(stacked ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
            for (String key : keys) {
                RadioButton radio = new RadioButton(this);
                radio.setId(View.generateViewId());
                radio.setTag(key);
                radio.setText(resultLabel(key));
                radio.setTextSize(18);
                radio.setMinHeight(dp(72));
                radio.setGravity(Gravity.CENTER);
                radio.setPadding(dp(10), dp(8), dp(10), dp(8));
                radio.setButtonDrawable(null);
                RadioGroup.LayoutParams params = stacked
                    ? new RadioGroup.LayoutParams(
                        RadioGroup.LayoutParams.MATCH_PARENT, dp(64))
                    : new RadioGroup.LayoutParams(0, dp(76), 1f);
                gradeGroup.addView(radio, params);
            }
            gradeGroup.clearCheck();
        } finally {
            rebuildingResultButtons = false;
        }
    }

    private JSONObject resultEntry(String key) {
        JSONObject map = profile == null ? null : profile.optJSONObject("gradeMap");
        return map == null || key == null ? null : map.optJSONObject(key);
    }

    private String resultLabel(String key) {
        JSONObject entry = resultEntry(key);
        if (entry == null) return key == null ? "" : key;
        String operatorLabel = localized(entry, "operatorLabel", "operatorLabelI18n");
        if (!operatorLabel.isEmpty()) return operatorLabel;
        String label = localized(entry, "label", "labelI18n");
        return label.isEmpty() ? key : label;
    }

    private void updateGradeButtons() {
        if (gradeGroup == null) return;
        ensureResultButtons();
        int checkedId = gradeGroup.getCheckedRadioButtonId();
        for (int i = 0; i < gradeGroup.getChildCount(); i++) {
            View child = gradeGroup.getChildAt(i);
            if (!(child instanceof RadioButton)) continue;
            RadioButton radio = (RadioButton) child;
            String key = radio.getTag() == null ? "" : String.valueOf(radio.getTag());
            boolean enabled = hasGrade(key);
            boolean selected = enabled && checkedId == radio.getId();
            radio.setEnabled(enabled);
            radio.setVisibility(enabled ? View.VISIBLE : View.GONE);
            styleGradeButton(radio, selected);
        }
    }

    private void styleGradeButton(RadioButton radio, boolean selected) {
        String grade = radio.getTag() == null ? "" : String.valueOf(radio.getTag());
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
        JSONObject entry = resultEntry(grade);
        Integer explicit = parseColor(entry == null ? "" : entry.optString("uiColor", ""));
        if (explicit != null) return explicit;
        explicit = parseColor(profile == null ? "" : profile.optString("uiColor", ""));
        if (explicit != null) return explicit;
        int[] palette = new int[]{0xFF0F766E, 0xFF7C3AED, 0xFFEA580C, 0xFF0284C7,
            0xFFBE123C, 0xFF65A30D, 0xFF9333EA, 0xFF0891B2};
        int hash = grade == null ? 0 : grade.hashCode();
        if (hash == Integer.MIN_VALUE) hash = 0;
        return palette[Math.abs(hash) % palette.length];
    }

    private int gradeBgColor(String grade) {
        return lightenColor(gradeColor(grade));
    }

    private boolean requiresSecondSn() {
        return profile != null && profile.optBoolean("requiresSecondSn", false);
    }

    /** Slot-mode profiles describe ordered required + optional upload boxes; null means legacy. */
    private JSONArray photoSlots() {
        return ProfileFieldRules.photoSlots(profile, profileWorkflow().includeOptionalPhotoSlots);
    }

    /** Ordered, profile-owned identifier inputs. */
    private JSONArray snPlugins() {
        JSONArray a = profile == null ? null : profile.optJSONArray("snPlugins");
        return a != null && a.length() > 0 ? a : null;
    }
    private JSONObject snPlugin(String key) {
        return snPlugin(profile, key);
    }

    private JSONObject snPlugin(JSONObject sourceProfile, String key) {
        JSONArray plugins = sourceProfile == null ? null : sourceProfile.optJSONArray("snPlugins");
        for (int i = 0; plugins != null && i < plugins.length(); i++) {
            JSONObject item = plugins.optJSONObject(i);
            if (item != null && key.equals(item.optString("key", ""))) return item;
        }
        return null;
    }

    private String inputLabel(JSONObject sourceProfile, boolean secondary) {
        JSONObject plugin = snPlugin(sourceProfile, secondary ? "secondary" : "primary");
        String configured = plugin == null ? "" : localized(plugin, "label", "labelI18n");
        if (!configured.isEmpty()) return configured;
        if ("en".equals(lang)) return secondary ? "Secondary identifier" : "Primary identifier";
        if ("es".equals(lang)) return secondary ? "Identificador secundario" : "Identificador principal";
        return secondary ? "次要标识" : "主要标识";
    }

    private String inputLabel(boolean secondary) {
        return inputLabel(profile, secondary);
    }

    private String primaryInputLabel() { return inputLabel(false); }
    private String secondaryInputLabel() { return inputLabel(true); }

    private String inputPlaceholder(boolean secondary) {
        JSONObject plugin = snPlugin(secondary ? "secondary" : "primary");
        String value = plugin == null ? "" : plugin.optString("placeholder", "").trim();
        return value.isEmpty() ? inputLabel(secondary) : value;
    }

    private String requiredInputMessage(boolean secondary) {
        return requiredFieldMessage(inputLabel(secondary));
    }

    private String requiredFieldMessage(String label) {
        if ("en".equals(lang)) return label + " is required";
        if ("es".equals(lang)) return "Se requiere " + label;
        return label + " 不能为空";
    }

    private String snPluginLabelForField(String field) {
        JSONArray plugins = snPlugins();
        for (int i = 0; plugins != null && i < plugins.length(); i++) {
            JSONObject plugin = plugins.optJSONObject(i);
            if (plugin == null || !field.equals(plugin.optString("field", ""))) continue;
            String label = localized(plugin, "label", "labelI18n").trim();
            return label.isEmpty() ? field : label;
        }
        return field;
    }

    private String noSecondaryInputNeededMessage() {
        if ("en".equals(lang)) return "No queued record needs " + secondaryInputLabel();
        if ("es".equals(lang)) return "Ningún registro en cola necesita " + secondaryInputLabel();
        return "没有待录入 " + secondaryInputLabel() + " 的记录";
    }

    /** Inputs beyond the two dedicated primary/secondary rows. */
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

    private String workflowArtifactTitle(String key) {
        for (ProfileWorkflow.WorkflowArtifact artifact : profileWorkflow().workflowArtifacts) {
            if (artifact.key.equals(key)) return artifact.localizedTitle(lang);
        }
        return key == null ? "" : key;
    }

    private int slotPhotoCount(UnitRecord unit, String field) {
        List<String> photos = unit.slotPhotos.get(field);
        return photos == null ? 0 : photos.size();
    }

    private boolean slotHasAnyPhotos(int slotIndex) {
        JSONArray slots = photoSlots();
        JSONObject slot = slots == null ? null : slots.optJSONObject(slotIndex);
        if (slot == null) return false;
        String field = slot.optString("field");
        for (UnitRecord unit : units) {
            if (slotPhotoCount(unit, field) > 0) return true;
        }
        return false;
    }

    private String slotTitleForStep(int[] step) {
        if (step == null || step.length < 2) return "";
        JSONArray slots = photoSlots();
        JSONObject slot = slots == null ? null : slots.optJSONObject(step[1]);
        return slot == null ? "" : slotTitleForField(slot.optString("field"));
    }

    private String legacyPhotoSlotTitle(int slotIndex, String fallbackSide) {
        JSONArray fields = profile == null ? null : profile.optJSONArray("uploadFields");
        JSONObject field = fields == null ? null : fields.optJSONObject(slotIndex);
        if (field == null) return sideName(fallbackSide);
        String title = localized(field, "title", "titleI18n");
        return title.isEmpty() ? sideName(fallbackSide) : title;
    }

    private String photoSlotTransitionNotice(String completedSlotTitle, String nextSlotTitle) {
        return PhotoTransitionRules.formatSlotTransitionNotice(
            t("photo_slot_transition"), completedSlotTitle, nextSlotTitle);
    }

    /** First (unit, slotIndex) whose captured count is below the slot's minPhotos; null when all met. */
    private int[] nextSlotStep() {
        JSONArray slots = photoSlots();
        if (slots == null) return null;
        // fronts_then_backs completes each configured box across records before moving on;
        // front_back_per_unit completes every configured box for one record before the next.
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
        if (blockDraftMutationForPreviousStepJournal()) return;
        List<String> photos = unit.slotPhotos.remove(field);
        if (photos != null) {
            for (String path : photos) deleteFileQuietly(path);
        }
        refreshFormUi();
        saveDraft();
    }

    private boolean hasMultipleGradeChoices() {
        return availableGrades().size() > 1;
    }

    private boolean hasGrade(String grade) {
        JSONObject gradeMap = profile == null ? null : profile.optJSONObject("gradeMap");
        return gradeMap != null && gradeMap.has(grade);
    }

    private String firstGradeKey() {
        List<String> keys = availableGrades();
        return keys.isEmpty() ? "" : keys.get(0);
    }

    private String savedToken() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            String realm = activeSessionRealmSha256;
            String fingerprint = SecureTokenStore.getBoundWebFingerprint(prefs, realm);
            return SecureTokenStore.getForBinding(prefs, realm, fingerprint).trim();
        }
    }

    private String savedPassword() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            String realm = activeSessionRealmSha256;
            String fingerprint = SecureTokenStore.getBoundWebFingerprint(prefs, realm);
            return SecureTokenStore.getPasswordForBinding(prefs, realm, fingerprint);
        }
    }

    private String savedAccount() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            String realm = activeSessionRealmSha256;
            String fingerprint = SecureTokenStore.getBoundWebFingerprint(prefs, realm);
            return SecureTokenStore.getAccountForBinding(prefs, realm, fingerprint).trim();
        }
    }

    private String savedUserName() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            String realm = activeSessionRealmSha256;
            String fingerprint = SecureTokenStore.getBoundWebFingerprint(prefs, realm);
            return SecureTokenStore.getUserNameForBinding(prefs, realm, fingerprint).trim();
        }
    }

    private String currentSessionRealmFingerprint() {
        String value = activeSessionRealmSha256;
        return SessionRealmRules.validDigest(value) ? value : "";
    }

    private void activateSessionRealm(PanelPairCacheCoordinator.ActivePair pair) {
        String realm = SessionRealmResolver.forPair(this, pair);
        if (SessionRealmRules.validDigest(realm)
                && SecureTokenStore.ensureSessionStateForRealm(prefs, realm).isEmpty()) {
            realm = "";
        }
        activeSessionRealmSha256 = realm;
        String fingerprint = SecureTokenStore.getBoundWebFingerprint(prefs, realm);
        SecureTokenStore.reconcileForBinding(prefs, realm, fingerprint);
    }

    private String webFingerprint() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            String realm = activeSessionRealmSha256;
            if (SessionRealmRules.validDigest(realm)) {
                return SecureTokenStore.webFingerprintForRealm(prefs, realm);
            }
            // The bundled sample never opens a socket. Keep its camera/draft operation identity
            // stable without placing that local-only value in the network fingerprint slot.
            if (!localSamplePreviewEnabled()) return "";
            String value = prefs.getString(LOCAL_PREVIEW_FINGERPRINT_KEY, "").trim();
            if (value.length() >= 16) return value;
            value = java.util.UUID.randomUUID().toString().replace("-", "");
            return prefs.edit().putString(LOCAL_PREVIEW_FINGERPRINT_KEY, value).commit()
                ? value : "";
        }
    }

    private String boundOcrUrlPreferenceKey(String token) {
        return OperationBindingRules.scopedValuePreferenceKey(
            BOUND_OCR_URL_KEY_PREFIX, currentConnectionNamespace(), activeCatalogVersion,
            currentPanelPairSha256(), webFingerprint(), token);
    }

    /** Current code never trusts the legacy global recognizeTextUrl mirror. */
    private String boundRecognizeTextUrl(String token) {
        if (token == null || token.trim().isEmpty()) return "";
        try {
            Object raw = prefs.getAll().get(boundOcrUrlPreferenceKey(token));
            if (!(raw instanceof String)) return "";
            OperationBindingRules.BoundValue stored =
                OperationBindingRules.parseBoundValue((String) raw);
            return stored.binding.matchesContext(currentConnectionNamespace(),
                activeCatalogVersion, currentPanelPairSha256(), webFingerprint(), token,
                OperationBindingRules.OCR_ENDPOINT) ? stored.value : "";
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Bound OCR endpoint ignored: " + conciseError(invalid));
            return "";
        }
    }

    private boolean saveBoundRecognizeTextUrl(String url, String token) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty() || token == null || token.trim().isEmpty()) return false;
        try {
            OperationBindingRules.Binding binding = OperationBindingRules.capture(
                currentConnectionNamespace(), activeCatalogVersion,
                currentPanelPairSha256(), webFingerprint(), token,
                java.util.UUID.randomUUID().toString().replace("-", ""),
                OperationBindingRules.OCR_ENDPOINT);
            String serialized = OperationBindingRules.bindValue(value, binding)
                .toJson().toString();
            // The global value is written only so a signed rollback build keeps its old behavior.
            // This release reads exclusively from the exact pair/session-bound key above.
            return prefs.edit()
                .putString(boundOcrUrlPreferenceKey(token), serialized)
                .putString("recognizeTextUrl", value)
                .commit();
        } catch (RuntimeException invalid) {
            Diagnostics.append(this, "Bound OCR endpoint save failed: "
                + conciseError(invalid));
            return false;
        }
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

    /** The panel-owned, versioned backend contract. It never supplies path fallbacks. */
    private BackendAdapter endpoints() {
        return BackendAdapter.from(appConfig, catalogSettings);
    }

    /** Every backend call is created through this gate; bundled examples can never open a socket. */
    private Api api(String token) {
        // Capture both immutable-by-replacement JSON roots once. A background Panel refresh may
        // replace appConfig after this point, but it cannot mix base URL, headers and adapter fields
        // inside an already-created Api.
        return api(token, appConfig, catalogSettings);
    }

    private Api api(String token, JSONObject configSnapshot,
                    JSONObject settingsSnapshot) {
        BackendAdapter adapterSnapshot = BackendAdapter.from(
            configSnapshot, settingsSnapshot);
        String origin = configSnapshot == null ? ""
            : configSnapshot.optString("webOrigin", "").trim();
        String referer = configSnapshot == null ? ""
            : configSnapshot.optString("webReferer", "").trim();
        final String connectionSnapshot = currentConnectionNamespace();
        final String requestRealm = SessionRealmRules.fingerprint(
            AppConfig.connectionSecurityId(
                AppConfig.panelBase(this), AppConfig.catalogKey(this)),
            configSnapshot, settingsSnapshot);
        final String requestWebFingerprint;
        final boolean tokenReadableAtCreation;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            requestWebFingerprint = webFingerprint();
            tokenReadableAtCreation = token == null || token.trim().isEmpty()
                || token.trim().equals(SecureTokenStore.getForBinding(
                    prefs, requestRealm, requestWebFingerprint));
        }
        final int requestCatalogVersion = AppConfig.catalogVersion(configSnapshot);
        final boolean requestMatchesActivePair = requestCatalogVersion > 0
            && requestCatalogVersion == activeCatalogVersion
            && activePanelPairCompatible();
        // A form that was already open may finish with its immutable v7/v7 pair while the two
        // on-disk downloads are temporarily v8/v7. Login and every other newly-created Settings
        // flow still require the disk pair to be ready. Capture that distinction once: a later
        // navigation must never silently turn a blocked new-flow Api into an active-flow Api.
        final boolean continuingActiveWorkflow = requestMatchesActivePair
            && activeWorkflowCanContinue();
        final boolean allowedAtCreation = requestMatchesActivePair
            && requestRealm.equals(currentSessionRealmFingerprint())
            && !requestWebFingerprint.isEmpty()
            && tokenReadableAtCreation
            && (continuingActiveWorkflow || !panelConnectionSyncBlocked())
            && CatalogSafetyRules.allowsRemoteOperations(settingsSnapshot);
        Api.RemoteOperationGate liveGate = () -> {
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                if (!connectionSnapshot.equals(currentConnectionNamespace())
                        || !requestRealm.equals(currentSessionRealmFingerprint())
                        || !requestWebFingerprint.equals(
                            SecureTokenStore.getBoundWebFingerprint(prefs, requestRealm))
                        || (token != null && !token.trim().isEmpty()
                            && !token.trim().equals(SecureTokenStore.getForBinding(
                                prefs, requestRealm, requestWebFingerprint)))
                        || requestCatalogVersion <= 0
                        || requestCatalogVersion != activeCatalogVersion
                        || !activePanelPairCompatible()) {
                    return false;
                }
                return continuingActiveWorkflow
                    ? activeWorkflowCanContinue()
                    : !panelConnectionSyncBlocked();
            }
        };
        return new Api(adapterSnapshot.baseUrl, token, requestWebFingerprint, origin, referer,
            adapterSnapshot, allowedAtCreation, liveGate);
    }

    /** Workflow behavior belongs to the selected profile; absent means every optional workflow is off. */
    private ProfileWorkflow profileWorkflow() {
        return ProfileWorkflow.from(profile);
    }

    /** Printing requires both a profile policy and an adapter capability; neither implies the other. */
    private boolean printingConfiguredForProfile() {
        return RemoteSideEffectSafetyRules.printingCapabilityErrors(
            profileWorkflow(), endpoints()).isEmpty();
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
        return endpoints().baseUrl;
    }

    /** True only when login can be attempted without guessing any backend path. */
    private boolean backendConfigured() {
        return panelRemoteOperationsAllowed()
            && !apiBase().isEmpty() && endpoints().missingForLogin().isEmpty();
    }

    /** A configured Panel cannot be used until both exact-connection caches are ready. */
    private boolean panelConnectionSyncBlocked() {
        String base = AppConfig.panelBase(this);
        String key = AppConfig.catalogKey(this);
        if (panelBoundaryCleanupBlocked || base.isEmpty() != key.isEmpty()) return true;
        if (base.isEmpty()) return false;
        String connection = currentConnectionNamespace();
        if (unsafeCandidatesBlockActiveUse()) return true;
        PanelBootstrapRules.State state = panelBootstrapState;
        if (state == null || state.blocksConfiguredUse(connection)) return true;
        // Recheck disk at the safety boundary. A deleted/corrupt cache must fail closed even if an
        // earlier listener had marked the immutable in-memory state ready.
        PanelPairCacheCoordinator.ActivePair pair =
            PanelPairCacheCoordinator.loadActivePairOrNull(this);
        return pair == null;
    }

    private boolean panelRemoteOperationsAllowed() {
        return !panelBoundaryCleanupBlocked
            && !panelConnectionTupleIncomplete()
            && !AppConfig.panelBase(this).isEmpty()
            && activePanelPairCompatible()
            && (activeWorkflowCanContinue() || !panelConnectionSyncBlocked());
    }

    private boolean panelUseBlocked() {
        return panelBoundaryCleanupBlocked || panelConnectionTupleIncomplete()
            || (!AppConfig.panelBase(this).isEmpty()
                && (panelConnectionSyncBlocked() || !activePanelPairCompatible()));
    }

    private boolean panelConnectionTupleIncomplete() {
        return AppConfig.panelBase(this).isEmpty() != AppConfig.catalogKey(this).isEmpty();
    }

    private boolean activePanelPairCompatible() {
        return PanelBootstrapRules.pairCompatible(appConfig != null,
            AppConfig.catalogVersion(appConfig), allProfiles != null, activeCatalogVersion);
    }

    /** Snapshot only stable record identities; visible text and production values never enter it. */
    private List<Integer> liveMainUnitSequences() {
        UnitRecord[] snapshot = units.toArray(new UnitRecord[0]);
        List<Integer> sequences = new ArrayList<>(snapshot.length);
        for (UnitRecord unit : snapshot) {
            if (unit != null && unit.sequence > 0) sequences.add(unit.sequence);
        }
        return sequences;
    }

    /**
     * Classifies candidates and captures the finite old-pair workload in the same process-wide
     * critical section used by candidate staging. New unit creation also owns this lock, so a unit
     * is unambiguously either before the barrier (leased) or after it (rejected).
     */
    private boolean unsafeCandidatesBlockActiveUse() {
        if (!BLOCK_ACTIVE_USE_ON_STAGED_PANEL_PAIR) {
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                unsafeCandidateContinuationLease = null;
            }
            return false;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (AppConfig.panelBase(this).isEmpty()) {
                unsafeCandidateContinuationLease = null;
                return false;
            }
            String connection = currentConnectionNamespace();
            boolean blocked = PanelPairCacheCoordinator.pendingCandidatesBlockActiveUse(
                this, connection);
            if (!blocked) {
                unsafeCandidateContinuationLease = null;
                return false;
            }
            UnsafeCandidateContinuationRules.Lease existing =
                unsafeCandidateContinuationLease;
            if (!UnsafeCandidateContinuationRules.matches(existing, connection,
                    activeCatalogVersion, currentPanelPairSha256())) {
                unsafeCandidateContinuationLease =
                    UnsafeCandidateContinuationRules.capture(
                        connection, activeCatalogVersion, currentPanelPairSha256(),
                        liveMainUnitSequences(), alternateEntryContinuationToken,
                        liveAlternateEntryReservationPermitsLocked(),
                        submitting || mainDraftRemoteWorkerCount > 0
                            || mainFormBoundWorkerActive(),
                        printRemoteWorkerCount > 0, alternateEntrySubmitting);
            }
            return true;
        }
    }

    private boolean unsafeContinuationAllowsCurrentWork() {
        return UnsafeCandidateContinuationRules.permitsCurrentWork(
            unsafeCandidateContinuationLease, liveMainUnitSequences(),
            !settingsPageOpen && !alternateEntryPageOpen,
            submitting || mainDraftRemoteWorkerCount > 0 || mainFormBoundWorkerActive(),
            printRemoteWorkerCount > 0,
            alternateEntryPageOpen && hasAlternateEntryPendingData(),
            alternateEntrySubmitting,
            alternateEntryContinuationToken,
            liveAlternateEntryReservationTokensLocked(),
            currentConnectionNamespace(), activeCatalogVersion,
            currentPanelPairSha256());
    }

    private boolean unsafeContinuationCanResumeMainForm() {
        return UnsafeCandidateContinuationRules.hasAllowedMainUnit(
            unsafeCandidateContinuationLease, liveMainUnitSequences(),
            currentConnectionNamespace(), activeCatalogVersion,
            currentPanelPairSha256());
    }

    private boolean unsafeContinuationCanResumeAlternateEntry() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            boolean draftAllowed = (alternateEntrySubmitting || hasAlternateEntryPendingData())
                && UnsafeCandidateContinuationRules.permitsAlternateEntry(
                    unsafeCandidateContinuationLease, alternateEntryContinuationToken,
                    currentConnectionNamespace(), activeCatalogVersion,
                    currentPanelPairSha256());
            return draftAllowed
                || UnsafeCandidateContinuationRules.hasAllowedAlternateReservation(
                    unsafeCandidateContinuationLease,
                    liveAlternateEntryReservationTokensLocked(),
                    currentConnectionNamespace(), activeCatalogVersion,
                    currentPanelPairSha256());
        }
    }

    /** Authorizes one bounded worker only when it operates on a pre-barrier main-form unit. */
    private boolean authorizeMainWorkerForUnsafeCandidate() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!unsafeCandidatesBlockActiveUse()) return true;
            UnsafeCandidateContinuationRules.Lease authorized =
                UnsafeCandidateContinuationRules.authorizeMainWorker(
                    unsafeCandidateContinuationLease, liveMainUnitSequences(),
                    currentConnectionNamespace(), activeCatalogVersion,
                    currentPanelPairSha256());
            if (authorized == null) return false;
            unsafeCandidateContinuationLease = authorized;
            return true;
        }
    }

    /** Authorizes the one already-populated alternate entry, never a newly opened empty entry. */
    private boolean authorizeAlternateWorkerForUnsafeCandidate() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!unsafeCandidatesBlockActiveUse()) return true;
            UnsafeCandidateContinuationRules.Lease authorized =
                UnsafeCandidateContinuationRules.authorizeAlternateWorker(
                    unsafeCandidateContinuationLease, alternateEntryContinuationToken,
                    currentConnectionNamespace(),
                    activeCatalogVersion, currentPanelPairSha256());
            if (authorized == null) return false;
            unsafeCandidateContinuationLease = authorized;
            return true;
        }
    }

    /** Publishes notifications only from the same complete pair used by the active form engine. */
    private void publishActiveNotificationSnapshot() {
        if (panelBoundaryCleanupBlocked || !activePanelPairCompatible()) {
            notificationSnapshot = null;
            NotificationClient.clearActiveSnapshot();
            return;
        }
        notificationSnapshot = NotificationClient.installActiveSnapshot(
            this, appConfig, catalogSettings, activeCatalogVersion,
            currentPanelPairSha256());
        // A null snapshot (for example after an unsafe candidate appears) must also wake the
        // reporter so it immediately invalidates the old pair partition; without a valid snapshot
        // that wake-up performs no network request.
        FailureReporter.get().requestFlush();
    }

    private boolean activeWorkflowCanContinue() {
        if (panelBoundaryCleanupBlocked || panelConnectionTupleIncomplete()) return false;
        if (!PanelBootstrapRules.allowsActiveWorkflow(
                panelBootstrapState, currentConnectionNamespace(), appConfig != null,
                AppConfig.catalogVersion(appConfig), allProfiles != null,
                activeCatalogVersion)) return false;
        boolean unsafeCandidate = unsafeCandidatesBlockActiveUse();
        PanelBootstrapRules.State state = panelBootstrapState;
        if (!unsafeCandidate && state != null
                && state.mode == PanelBootstrapRules.Mode.READY) {
            return !settingsPageOpen;
        }
        return unsafeContinuationAllowsCurrentWork();
    }

    private boolean ensurePanelReadyForUse() {
        if (activeWorkflowCanContinue() || !panelUseBlocked()) return true;
        notifyBackendUnconfigured();
        return false;
    }

    private boolean isSampleCatalog() {
        // Use only the active in-memory pair. Reading newly downloaded settings independently here
        // could mix them with an older appConfig/profile while a form or draft is in progress.
        return CatalogSafetyRules.isSampleCatalog(catalogSettings);
    }

    /** The tracked fictional seed may exercise local form/camera behavior, never a socket. */
    private boolean localSamplePreviewEnabled() {
        return AppConfig.panelBase(this).isEmpty()
            && AppConfig.catalogKey(this).isEmpty()
            && isSampleCatalog()
            && activeCatalogVersion > 0
            && activePanelPairSha256.matches("[0-9a-f]{64}");
    }

    /** Tell the user no panel/backend is configured yet. Safe to call from any thread; never crashes. */
    private void notifyBackendUnconfigured() {
        runOnUiThread(() -> {
            if (!activityAlive()) return;
            List<String> missing = endpoints().missingForLogin();
            String detail = panelConnectionSyncBlocked()
                ? t("panel_syncing_detail")
                : (panelUseBlocked()
                    ? t("panel_active_pair_pending_detail") : t("panel_required_detail"));
            if (apiBase().isEmpty()) missing.add("backendApiBase");
            if (!missing.isEmpty()) detail += "\n\n" + t("panel_missing_config") + join(missing, ", ");
            alert(t("panel_required_title"), detail);
        });
    }

    /**
     * Starts both independent Panel downloads for one captured connection. A missing-cache device
     * remains locked until both listeners have finished and fresh disk reads prove that config and
     * catalog are bound to that same connection.
     */
    private void synchronizePanelConnection(boolean interactive) {
        synchronizePanelConnection(interactive, false, false, "");
    }

    private void synchronizePanelConnection(boolean interactive, boolean foreground) {
        synchronizePanelConnection(interactive, foreground, false, "");
    }

    private void synchronizePanelConnection(boolean interactive, boolean foreground,
                                            boolean pairedRetry,
                                            String expectedConnection) {
        final String base = AppConfig.panelBase(this);
        final String key = AppConfig.catalogKey(this);
        // Old versions could persist only one half of the tuple. Treat it as corrupt local state:
        // do not inspect caches or start either AppConfig/FormCatalog network path until repaired.
        if (panelBoundaryCleanupBlocked || base.isEmpty() != key.isEmpty()) return;
        final String connection = AppConfig.connectionNamespaceId(base, key);
        if (pairedRetry && !connection.equals(expectedConnection)) return;
        PanelPairCacheCoordinator.ActivePair activePair =
            PanelPairCacheCoordinator.loadActivePairOrNull(this);
        JSONObject boundConfig = activePair == null ? null : activePair.config;
        FormCatalog.BoundSnapshot boundCatalog =
            activePair == null ? null : activePair.catalog;
        PanelBootstrapRules.State initialState = PanelBootstrapRules.begin(
            connection, !base.isEmpty(),
            boundConfig != null, AppConfig.catalogVersion(boundConfig),
            boundCatalog != null, boundCatalog == null ? 0 : boundCatalog.version);
        if (unsafeCandidatesBlockActiveUse()) {
            initialState = PanelBootstrapRules.awaitingCandidatePromotion(initialState);
        }
        final long syncRound;
        synchronized (this) {
            syncRound = ++panelSyncRound;
            panelBootstrapState = initialState;
        }
        if (base.isEmpty()) return;

        FormCatalogManager.Listener catalogListener = resultConnection ->
            handlePanelRefreshFinished(PanelBootstrapRules.Source.CATALOG,
                resultConnection, syncRound, interactive);
        if (pairedRetry) {
            if (!formCatalogManager.checkPairedRetry(catalogListener)) return;
        } else if (foreground) {
            if (!formCatalogManager.checkOnForeground(catalogListener)) return;
        } else {
            formCatalogManager.checkOnStartup(catalogListener);
        }
        AppConfig.refresh(this, base, key, result ->
            handlePanelRefreshFinished(PanelBootstrapRules.Source.CONFIG,
                connection, syncRound, interactive));
    }

    private void handlePanelRefreshFinished(PanelBootstrapRules.Source source,
                                            String listenerConnection,
                                            long listenerRound,
                                            boolean interactive) {
        runOnUiThread(() -> {
            if (!activityAlive()) return;
            synchronized (this) {
                if (listenerRound != panelSyncRound) return;
            }
            final String current = currentConnectionNamespace();
            PanelPairCacheCoordinator.ActivePair activePair =
                PanelPairCacheCoordinator.loadActivePairOrNull(this);
            final PanelBootstrapRules.State before;
            PanelBootstrapRules.State progressed;
            synchronized (this) {
                if (listenerRound != panelSyncRound) return;
                before = panelBootstrapState;
                progressed = PanelBootstrapRules.onRefreshFinished(before, source,
                    listenerConnection, current,
                    activePair != null, activePair == null ? 0 : activePair.version,
                    activePair != null, activePair == null ? 0 : activePair.version);
                if (progressed == before) return; // stale Panel/key callback
            }
            final boolean roundFinished = !before.allRefreshesFinished()
                && progressed.allRefreshesFinished();

            PanelPairCacheCoordinator.Promotion promotion =
                promotePanelPairCandidatesAtSafeBoundary();
            activePair = PanelPairCacheCoordinator.loadActivePairOrNull(this);
            PanelBootstrapRules.State after = progressed;
            if (promotion == PanelPairCacheCoordinator.Promotion.PROMOTED
                    && activePair != null) {
                // A committed exact pair is stronger evidence than two independent callback flags.
                after = PanelBootstrapRules.begin(current, true,
                    true, activePair.version, true, activePair.version);
            } else if (unsafeCandidatesBlockActiveUse()) {
                after = PanelBootstrapRules.awaitingCandidatePromotion(progressed);
            }
            synchronized (this) {
                if (panelBootstrapState != before
                        && panelBootstrapState != progressed) return;
                panelBootstrapState = after;
            }

            final boolean newlyReady = before.mode != PanelBootstrapRules.Mode.READY
                && after.mode == PanelBootstrapRules.Mode.READY;
            boolean retryCandidatePair = roundFinished
                && PanelPairCacheCoordinator.needsPairedRetry(
                    this, after.connectionNamespace);
            if (retryCandidatePair) {
                schedulePanelPairRetry(after.connectionNamespace);
            } else if (after.mode == PanelBootstrapRules.Mode.READY) {
                cancelPanelPairRetry(true);
            }

            if (after.mode == PanelBootstrapRules.Mode.READY
                    && activePair != null && safeToInstallBoundPanelSnapshot()) {
                installBoundPanelSnapshot(activePair);
                if (newlyReady) {
                    showSettingsPage();
                    if (savedToken().isEmpty() && captchaClient.isEmpty()) refreshCaptcha();
                }
            }
            if (after.mode == PanelBootstrapRules.Mode.READY
                    && activePair != null && updateManager != null) {
                updateManager.checkAfterPanelReady();
            }
            if (interactive && roundFinished) {
                toast(after.mode == PanelBootstrapRules.Mode.READY
                    ? t("panel_connected") : t("panel_connect_failed"));
            }
        });
    }

    /**
     * A publish can expose config v8 a moment before manifest/catalog v8. Retry both halves
     * together on a short bounded schedule so the foreground 10-minute throttle cannot strand the
     * device at v8/v7. The active in-memory v7/v7 workflow is untouched throughout these retries.
     */
    private void schedulePanelPairRetry(String connection) {
        final String expected = connection == null ? "" : connection;
        final long delay;
        synchronized (this) {
            if (!expected.equals(currentConnectionNamespace())) return;
            if (!expected.equals(panelPairRetryConnection)) {
                cancelPanelPairRetryLocked();
                panelPairRetryConnection = expected;
                panelPairRetryCount = 0;
            }
            if (panelPairRetryTask != null) return;
            delay = PanelBootstrapRules.pairRetryDelayMillis(panelPairRetryCount);
            if (delay < 0L) return;
            panelPairRetryCount++;
            panelPairRetryTask = () -> {
                synchronized (MainActivity.this) {
                    panelPairRetryTask = null;
                }
                if (!activityAlive() || !expected.equals(currentConnectionNamespace())) return;
                synchronizePanelConnection(false, false, true, expected);
            };
            panelSyncHandler.postDelayed(panelPairRetryTask, delay);
        }
    }

    private void cancelPanelPairRetry(boolean resetBudget) {
        synchronized (this) {
            cancelPanelPairRetryLocked();
            if (resetBudget) {
                panelPairRetryConnection = "";
                panelPairRetryCount = 0;
            }
        }
    }

    private void cancelPanelPairRetryLocked() {
        if (panelPairRetryTask != null) {
            panelSyncHandler.removeCallbacks(panelPairRetryTask);
            panelPairRetryTask = null;
        }
    }

    /** Installs config + profiles/settings from one verified current-connection cache pair. */
    private void installBoundPanelSnapshot(PanelPairCacheCoordinator.ActivePair pair) {
        if (pair == null || pair.config == null || pair.catalog == null
                || !pair.pairSha256.matches("[0-9a-f]{64}")) return;
        try {
            // Advance the credential gate before exposing any field from the new pair. Same-realm
            // profile-only publishes retain the session; a transport change makes v2 unreadable.
            activateSessionRealm(pair);
            if (!pair.pairSha256.equals(activePanelPairSha256)
                    || pair.version != activeCatalogVersion) {
                unsafeCandidateContinuationLease = null;
            }
            JSONObject config = pair.config;
            FormCatalog.BoundSnapshot catalog = pair.catalog;
            String selectedId = profile == null ? "" : profile.optString("id", "");
            JSONArray visible = filterPickerProfiles(catalog.profiles);
            JSONObject selected = uniqueProfile(visible, selectedId);
            appConfig = config;
            allProfiles = catalog.profiles;
            catalogSettings = catalog.settings;
            activeCatalogVersion = catalog.version;
            activePanelPairSha256 = pair.pairSha256;
            profiles = visible;
            profile = selected != null ? selected
                : (visible.length() > 0 ? visible.getJSONObject(0) : null);
            publishActiveNotificationSnapshot();
        } catch (Exception error) {
            Diagnostics.append(this, "Panel hot-load failed: " + conciseError(error));
        }
    }

    /** True only at a boundary where replacing the active config/profile pair cannot reinterpret data. */
    private boolean safeToInstallBoundPanelSnapshot() {
        if (panelBoundaryCleanupBlocked || panelConnectionTupleIncomplete()
                || !settingsPageOpen || submitting || profileOwnedRemoteWorkerActive()
                || RemoteSideEffectGate.blockingStatePresent(this)
                || UpdateManager.installerHandoffActive(this)
                || mainFormBoundWorkerActive() || hasPendingMainFormOperation()
                || hasStoredOrUnreadableReprintAttempt()
                || hasStoredUploadReplayBarrier()
                || alternateEntrySubmitting
                || alternateEntryPageOpen || !units.isEmpty()
                || hasAlternateEntryPendingData() || hasStoredAlternateEntryDraft()
                || prefs.contains(mainSubmissionAttemptPreferenceKey())
                || prefs.contains(previousStepSubmissionAttemptPreferenceKey())
                || prefs.contains(alternateSubmissionAttemptPreferenceKey())
                || !pendingOutputPhotoPath.isEmpty() || !pendingOcrPhotoPath.isEmpty()
                || hasPendingAlternateEntryAsyncReservationEvidence()) {
            return false;
        }
        // This check must precede candidate promotion. Without an exact prewarm receipt, replacing
        // the old unbound cache would make its draft/queue/ledger permanently impossible to bind.
        // It also leaves every signed-v1 A-step continuation key/path byte-for-byte untouched.
        if (!legacyPanelStateReadyForCachePromotion()) return false;
        if (manualQueueRecoveryEvidencePresent()) return false;
        return totalUnsubmittedDraftUnitCount(loadDraftStore()) == 0;
    }

    /**
     * UI state is checked before taking HANDOFF_LOCK to preserve the existing Activity-monitor →
     * handoff lock order used by worker acquisition. The UI thread cannot start a new workflow in
     * between; once inside the lock we recheck the exact connection, every durable/active remote
     * side effect and installer handoff before touching the pair transaction.
     */
    private PanelPairCacheCoordinator.Promotion
            promotePanelPairCandidatesAtSafeBoundary() {
        if (!safeToInstallBoundPanelSnapshot()) {
            return PanelPairCacheCoordinator.Promotion.NONE;
        }
        final String expectedConnection = currentConnectionNamespace();
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!expectedConnection.equals(currentConnectionNamespace())
                    || RemoteSideEffectGate.blockingStatePresent(this)
                    || UpdateManager.installerHandoffActive(this)
                    || manualQueueRecoveryEvidencePresent()) {
                return PanelPairCacheCoordinator.Promotion.NONE;
            }
            try {
                PanelPairCacheCoordinator.Promotion promotion =
                    PanelPairCacheCoordinator.promoteCandidates(this, expectedConnection);
                if (promotion == PanelPairCacheCoordinator.Promotion.PROMOTED) {
                    // Still under HANDOFF_LOCK: no request gate can observe promoted disk bytes
                    // while the in-process realm remains the old one.
                    activateSessionRealm(
                        PanelPairCacheCoordinator.loadActivePairOrNull(this));
                }
                return promotion;
            } catch (Exception failure) {
                Diagnostics.append(this, "Panel pair promotion blocked: "
                    + conciseError(failure));
                return PanelPairCacheCoordinator.Promotion.INVALID;
            }
        }
    }

    private boolean maybeInstallBoundPanelSnapshotAtSafeBoundary() {
        if (!safeToInstallBoundPanelSnapshot()) return false;
        PanelPairCacheCoordinator.Promotion promotion =
            promotePanelPairCandidatesAtSafeBoundary();
        PanelPairCacheCoordinator.ActivePair pair =
            PanelPairCacheCoordinator.loadActivePairOrNull(this);
        if (pair == null) return false;
        if (promotion == PanelPairCacheCoordinator.Promotion.PROMOTED) {
            panelBootstrapState = PanelBootstrapRules.begin(
                currentConnectionNamespace(), !AppConfig.panelBase(this).isEmpty(),
                true, pair.version, true, pair.version);
        } else if (unsafeCandidatesBlockActiveUse()) {
            panelBootstrapState = PanelBootstrapRules.awaitingCandidatePromotion(
                panelBootstrapState);
            return false;
        }
        if (pair.pairSha256.equals(activePanelPairSha256)
                && activeCatalogVersion == pair.version) return true;
        installBoundPanelSnapshot(pair);
        return activePanelPairCompatible()
            && activeCatalogVersion == pair.version
            && activePanelPairSha256.equals(pair.pairSha256);
    }

    private static final class PanelConnectionAlternateEvidence {
        final boolean present;
        final String sha256;

        PanelConnectionAlternateEvidence(boolean present, String sha256) {
            this.present = present;
            this.sha256 = sha256 == null ? "" : sha256;
        }
    }

    private PanelConnectionAlternateEvidence
            alternateEntryPanelChangeCleanupEvidencePresent() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                Map<String, ?> stored = PanelConnectionPreferenceTransaction.snapshot(prefs);
                return alternateEntryPanelChangeCleanupEvidenceSnapshotLocked(
                    stored, currentConnectionNamespace());
            } catch (RuntimeException unreadable) {
                return new PanelConnectionAlternateEvidence(true, "");
            }
        }
    }

    private PanelConnectionAlternateEvidence
            alternateEntryPanelChangeCleanupEvidenceSnapshotLocked(
                Map<String, ?> stored, String oldNamespace) {
        boolean present = alternateEntryPanelChangeCleanupEvidencePresentLocked(
            stored, oldNamespace);
        StringBuilder canonical = new StringBuilder();
        appendAlternateEntryStatePart(canonical, "present", String.valueOf(present));
        appendAlternateEntryStatePart(canonical, "namespace", oldNamespace);
        String[] keys = {
            ALTERNATE_ENTRY_DRAFT_KEY + "_" + oldNamespace,
            ALTERNATE_ENTRY_CONTINUATION_PROOF_KEY + "_" + oldNamespace,
            PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY,
            PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY,
            PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY,
            PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY,
            PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY
        };
        for (String preferenceKey : keys) {
            appendAlternateEntryStatePart(canonical, "preferenceKey", preferenceKey);
            appendAlternateEntryStatePart(canonical, "preferenceValue",
                stablePanelConnectionEvidenceValue(stored, preferenceKey));
        }
        appendAlternateEntryStatePart(canonical, "storageAmbiguous",
            String.valueOf(alternateEntryReservationStorageAmbiguous));
        appendAlternateEntryStatePart(canonical, "memoryNamespace",
            alternateEntryConnectionNamespace);
        appendAlternateEntryStatePart(canonical, "entryId", alternateEntryId);
        appendAlternateEntryStatePart(canonical, "serial", alternateEntrySerial);
        appendAlternateEntryStatePart(canonical, "serialSource", alternateEntrySerialSource);
        appendAlternateEntryStatePart(canonical, "continuationToken",
            alternateEntryContinuationToken);
        appendAlternateEntryStatePart(canonical, "pendingPhotoPath",
            pendingAlternateEntryPhotoPath);
        appendAlternateEntryStatePart(canonical, "pendingPhotoGuard",
            pendingAlternateEntryPhotoGuard);
        appendAlternateEntryStatePart(canonical, "pendingScanGuard",
            pendingAlternateEntryScanGuard);
        for (String path : alternateEntryPhotos) {
            appendAlternateEntryStatePart(canonical, "memoryPhoto", path);
        }
        List<String> toggleKeys = new ArrayList<>(alternateEntryToggleStates.keySet());
        Collections.sort(toggleKeys);
        for (String toggleKey : toggleKeys) {
            appendAlternateEntryStatePart(canonical, "toggleKey", toggleKey);
            appendAlternateEntryStatePart(canonical, "toggleValue",
                String.valueOf(alternateEntryToggleStates.get(toggleKey)));
        }
        appendAlternateEntryStatePart(canonical, "photoReservation",
            pendingAlternateEntryPhotoReservation == null ? ""
                : pendingAlternateEntryPhotoReservation.toJson().toString());
        appendAlternateEntryStatePart(canonical, "scanReservation",
            pendingAlternateEntryScanReservation == null ? ""
                : pendingAlternateEntryScanReservation.toJson().toString());
        return new PanelConnectionAlternateEvidence(present,
            AlternateEntryAsyncReservation.sha256(canonical.toString()));
    }

    private String stablePanelConnectionEvidenceValue(Map<String, ?> stored, String key) {
        if (stored == null || !stored.containsKey(key)) return "absent";
        Object value = stored.get(key);
        if (value == null) return "null";
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value.getClass().getName() + ":" + value;
        }
        if (value instanceof Set) {
            List<String> items = new ArrayList<>();
            for (Object item : (Set<?>) value) {
                items.add(item == null ? "null"
                    : item.getClass().getName() + ":" + item);
            }
            Collections.sort(items);
            return value.getClass().getName() + ":" + join(items, "|");
        }
        return value.getClass().getName() + ":unsupported";
    }

    private boolean alternateEntryPanelChangeCleanupEvidencePresentLocked(
            Map<String, ?> stored, String oldNamespace) {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            throw new IllegalStateException("alternate-entry boundary lock is required");
        }
        if (alternateEntryReservationStorageAmbiguous
                || hasAlternateEntryPendingData()
                || !alternateEntryPhotos.isEmpty()
                || !pendingAlternateEntryPhotoPath.isEmpty()
                || pendingAlternateEntryPhotoReservation != null
                || pendingAlternateEntryScanReservation != null
                || !pendingAlternateEntryPhotoGuard.isEmpty()
                || !pendingAlternateEntryScanGuard.isEmpty()) return true;
        if (stored == null) return true;
        String draftKey = ALTERNATE_ENTRY_DRAFT_KEY + "_" + oldNamespace;
        String proofKey = ALTERNATE_ENTRY_CONTINUATION_PROOF_KEY + "_" + oldNamespace;
        return stored.containsKey(draftKey) || stored.containsKey(proofKey)
            || stored.containsKey(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY)
            || stored.containsKey(PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY)
            || stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY)
            || stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY)
            || stored.containsKey(PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY);
    }

    /**
     * Captures every old-Panel alternate-entry artifact while the handoff lock still proves that
     * no callback can add another one. No preference or file is modified here.
     */
    private PanelConnectionAlternateCleanupReceipt
            capturePanelConnectionAlternateCleanupReceiptLocked(
                Map<String, ?> stored, String oldNamespace,
                String newPanelBase, String newCatalogKey) throws IOException {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            throw new IllegalStateException("alternate-entry boundary lock is required");
        }
        if (stored == null) throw new IllegalStateException("preferences are unavailable");
        LinkedHashSet<String> absolutePhotos = new LinkedHashSet<>();
        String draftKey = ALTERNATE_ENTRY_DRAFT_KEY + "_" + oldNamespace;
        String proofKey = ALTERNATE_ENTRY_CONTINUATION_PROOF_KEY + "_" + oldNamespace;

        String rawDraft = strictOptionalPreferenceString(stored, draftKey);
        if (rawDraft != null) {
            AlternateEntryDraftState draft = AlternateEntryDraftState.parse(rawDraft);
            if (!oldNamespace.equals(draft.connectionNamespace)) {
                throw new IllegalStateException("alternate-entry draft belongs to another Panel");
            }
            absolutePhotos.addAll(draft.photos);
        }
        String rawProof = strictOptionalPreferenceString(stored, proofKey);
        if (rawProof != null) {
            AlternateEntryContinuationProof proof =
                AlternateEntryContinuationProof.parse(rawProof);
            if (!oldNamespace.equals(proof.connectionNamespace)) {
                throw new IllegalStateException("alternate-entry proof belongs to another Panel");
            }
        }

        // Guards and the pending output path are part of the captured old state even when a legacy
        // build did not yet write a reservation. Wrong preference types are never silently erased.
        strictOptionalPreferenceString(stored, PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY);
        strictOptionalPreferenceString(stored, PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY);
        String storedPhotoPath = strictOptionalPreferenceString(
            stored, PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
        if (storedPhotoPath != null && !storedPhotoPath.isEmpty()) {
            absolutePhotos.add(storedPhotoPath);
        }

        String rawScanReservation = strictOptionalPreferenceString(
            stored, PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY);
        if (rawScanReservation != null) {
            AlternateEntryAsyncReservation reservation =
                AlternateEntryAsyncReservation.parse(rawScanReservation);
            requirePanelSwitchReservation(
                reservation, AlternateEntryAsyncReservation.KIND_SCAN, oldNamespace);
        }
        String rawPhotoReservation = strictOptionalPreferenceString(
            stored, PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY);
        if (rawPhotoReservation != null) {
            AlternateEntryAsyncReservation reservation =
                AlternateEntryAsyncReservation.parse(rawPhotoReservation);
            requirePanelSwitchReservation(
                reservation, AlternateEntryAsyncReservation.KIND_PHOTO, oldNamespace);
            absolutePhotos.add(reservation.outputPath);
        }

        if ((!alternateEntryPhotos.isEmpty() || !pendingAlternateEntryPhotoPath.isEmpty()
                || pendingAlternateEntryPhotoReservation != null
                || pendingAlternateEntryScanReservation != null)
                && !alternateEntryConnectionNamespace.isEmpty()
                && !oldNamespace.equals(alternateEntryConnectionNamespace)) {
            throw new IllegalStateException("alternate-entry memory belongs to another Panel");
        }
        absolutePhotos.addAll(alternateEntryPhotos);
        if (!pendingAlternateEntryPhotoPath.isEmpty()) {
            absolutePhotos.add(pendingAlternateEntryPhotoPath);
        }
        if (pendingAlternateEntryScanReservation != null) {
            requirePanelSwitchReservation(pendingAlternateEntryScanReservation,
                AlternateEntryAsyncReservation.KIND_SCAN, oldNamespace);
        }
        if (pendingAlternateEntryPhotoReservation != null) {
            requirePanelSwitchReservation(pendingAlternateEntryPhotoReservation,
                AlternateEntryAsyncReservation.KIND_PHOTO, oldNamespace);
            absolutePhotos.add(pendingAlternateEntryPhotoReservation.outputPath);
        }

        LinkedHashSet<String> relativePhotos = new LinkedHashSet<>();
        for (String path : absolutePhotos) {
            relativePhotos.add(panelConnectionCleanupRelativePhotoPath(path));
        }
        return PanelConnectionAlternateCleanupReceipt.validate(
            java.util.UUID.randomUUID().toString().replace("-", "")
                .toLowerCase(java.util.Locale.US),
            oldNamespace,
            AppConfig.connectionSecurityId(newPanelBase, newCatalogKey),
            relativePhotos);
    }

    private String strictOptionalPreferenceString(Map<String, ?> stored, String key) {
        if (!stored.containsKey(key)) return null;
        Object value = stored.get(key);
        if (!(value instanceof String)) {
            throw new IllegalStateException("alternate-entry preference has wrong type");
        }
        return (String) value;
    }

    private void requirePanelSwitchReservation(AlternateEntryAsyncReservation reservation,
                                               String kind, String oldNamespace) {
        if (reservation == null || !kind.equals(reservation.kind)
                || !oldNamespace.equals(reservation.connectionNamespace)) {
            throw new IllegalStateException("alternate-entry reservation is not old-Panel bound");
        }
    }

    private String panelConnectionCleanupRelativePhotoPath(String absolutePath)
            throws IOException {
        if (absolutePath == null || absolutePath.isEmpty()) {
            throw new IOException("alternate-entry photo path is empty");
        }
        File input = new File(absolutePath);
        if (!input.isAbsolute()) {
            throw new IOException("alternate-entry photo path is not absolute");
        }
        File root = new File(getFilesDir(), "photos").getCanonicalFile();
        File photo = input.getCanonicalFile();
        String prefix = root.getPath() + File.separator;
        if (!photo.getPath().startsWith(prefix)) {
            throw new IOException("alternate-entry photo is outside private photo storage");
        }
        return photo.getPath().substring(prefix.length())
            .replace(File.separatorChar, '/');
    }

    private Set<String> stagePanelConnectionAlternateCleanup(
            SharedPreferences.Editor editor, String oldNamespace,
            PanelConnectionAlternateCleanupReceipt receipt) {
        LinkedHashSet<String> touched = new LinkedHashSet<>();
        touched.add(ALTERNATE_ENTRY_DRAFT_KEY + "_" + oldNamespace);
        touched.add(ALTERNATE_ENTRY_CONTINUATION_PROOF_KEY + "_" + oldNamespace);
        touched.add(PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY);
        touched.add(PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY);
        touched.add(PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY);
        touched.add(PENDING_ALTERNATE_ENTRY_PHOTO_GUARD_KEY);
        touched.add(PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY);
        touched.add(PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY);
        for (String preferenceKey : touched) editor.remove(preferenceKey);
        editor.putString(PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY,
            receipt.toJson().toString());
        return touched;
    }

    /** Idempotent startup/post-commit replay. Missing files count as an already completed delete. */
    private boolean recoverPanelConnectionAlternateCleanupReceipt() {
        panelBoundaryCleanupBlocked = true;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                Map<String, ?> stored = prefs.getAll();
                if (!stored.containsKey(
                        PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY)) {
                    panelBoundaryCleanupBlocked = false;
                    return true;
                }
                Object raw = stored.get(
                    PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY);
                if (!(raw instanceof String)) return false;
                PanelConnectionAlternateCleanupReceipt receipt =
                    PanelConnectionAlternateCleanupReceipt.parse((String) raw);
                String base = AppConfig.panelBase(this);
                String key = AppConfig.catalogKey(this);
                // Tuple digest and all seven captured-source removals must be proven before the
                // first delete. The helper also resolves each receipt path canonically under root.
                if (!PanelConnectionAlternateCleanupRecovery.deleteCapturedPhotos(
                        stored, receipt, base, key,
                        new File(getFilesDir(), "photos"))) return false;
                if (!prefs.edit()
                        .remove(PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY)
                        .commit()) {
                    // SharedPreferences can mutate its process map even when disk commit fails.
                    // Put the receipt back into memory so Application's delayed reporter gate also
                    // remains closed; the original durable receipt is still authoritative.
                    prefs.edit().putString(
                        PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY,
                        (String) raw).commit();
                    return false;
                }
                panelBoundaryCleanupBlocked = false;
                return true;
            } catch (RuntimeException invalid) {
                Diagnostics.append(this,
                    "Panel alternate cleanup receipt rejected: " + conciseError(invalid));
                return false;
            }
        }
    }

    /** Clears only process memory after the new connection commit; no preference/file mutation. */
    private void resetAlternateEntryMemoryForPanelConnectionChangeLocked() {
        if (!Thread.holdsLock(UpdateInstallRules.HANDOFF_LOCK)) {
            throw new IllegalStateException("alternate-entry boundary lock is required");
        }
        if (pendingAlternateEntryPhotoUri != null) {
            try {
                revokeUriPermission(pendingAlternateEntryPhotoUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        alternateEntrySessionNonce = "";
        alternateEntryContinuationToken = "";
        alternateEntryPageOpen = false;
        alternateEntrySubmitting = false;
        alternateEntryId = "";
        alternateEntryStateProfileId = "";
        alternateEntryReturnProfileId = "";
        alternateEntryConnectionNamespace = "";
        alternateEntryBindingFingerprint = "";
        alternateEntryBackendFingerprint = "";
        alternateEntrySourceProfile = null;
        alternateEntryConfig = null;
        alternateEntryCatalogSnapshot = new JSONArray();
        alternateEntryAppConfigSnapshot = null;
        alternateEntryCatalogSettingsSnapshot = null;
        alternateEntrySourceProfiles = new JSONArray();
        alternateEntrySerial = "";
        alternateEntrySerialSource = SnScanRules.SOURCE_ENTERED;
        alternateEntryPhotos.clear();
        alternateEntryToggleStates.clear();
        pendingAlternateEntryPhotoPath = "";
        pendingAlternateEntryPhotoUri = null;
        pendingAlternateEntryPhotoGuard = "";
        pendingAlternateEntryScanGuard = "";
        pendingAlternateEntryPhotoReservation = null;
        pendingAlternateEntryScanReservation = null;
        alternateEntryReservationStorageAmbiguous = false;
    }

    /** Consumes a process-memory delivery when MainActivity naturally becomes interactive. */
    private void acceptPendingPanelPairingDelivery() {
        PanelPairingBroker.Delivery delivery = PanelPairingBroker.take();
        if (delivery == null) return;
        if (panelPairingRedeemInFlight) {
            // Do not invalidate the active generation or start a second redemption. The newly
            // clicked ticket was never sent and can safely be regenerated later.
            toastLong(t("download_pair_in_progress"));
            return;
        }
        panelPairingGeneration++;
        pendingPanelPairingRequest = null;
        pendingPanelPairingLinkInvalid = false;
        if (panelPairingDialog != null) {
            try {
                panelPairingDialog.dismiss();
            } catch (Exception ignored) {
            }
            panelPairingDialog = null;
        }
        pendingPanelPairingRequest = delivery.request;
        if (delivery.invalid || !PanelPairingLinkRules.isUsableAt(
                pendingPanelPairingRequest, System.currentTimeMillis() / 1000L)) {
            pendingPanelPairingRequest = null;
            pendingPanelPairingLinkInvalid = true;
        }
        maybeShowPanelPairingPrompt();
    }

    private void maybeShowPanelPairingPrompt() {
        if (!activityAlive() || !hasWindowFocus() || panelPairingDialog != null
                || panelPairingRedeemInFlight) return;
        // Never interrupt form/camera/scanner return with a modal connection switch. The in-memory
        // request waits until the operator naturally returns to a safe Settings boundary.
        if (!settingsPageOpen) return;
        if (pendingPanelPairingLinkInvalid) {
            pendingPanelPairingLinkInvalid = false;
            alert(t("download_pair_invalid_title"), t("download_pair_invalid_detail"));
            return;
        }
        final PanelPairingLinkRules.Request request = pendingPanelPairingRequest;
        if (request == null) return;
        if (!PanelPairingLinkRules.isUsableAt(
                request, System.currentTimeMillis() / 1000L)) {
            pendingPanelPairingRequest = null;
            alert(t("download_pair_invalid_title"), t("download_pair_expired"));
            return;
        }
        if (!safeToInstallBoundPanelSnapshot()) return;
        pendingPanelPairingRequest = null;

        String current = AppConfig.panelBase(this);
        String detail = t(current.isEmpty()
            ? "download_pair_confirm_new" : "download_pair_confirm_replace")
            + "\n\n" + request.panelBase;
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(t("download_pair_title"))
            .setMessage(detail)
            .setNegativeButton(t("cancel"), null)
            .setPositiveButton(t("download_pair_connect"), null)
            .create();
        panelPairingDialog = dialog;
        dialog.setOnShowListener(ignored -> {
            Button connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            connect.setFilterTouchesWhenObscured(true);
            connect.setOnTouchListener((view, event) -> {
                if (PanelPairingTouchRules.reject(event.getFlags(), Build.VERSION.SDK_INT)) {
                    toastLong(t("download_pair_obscured"));
                    return true;
                }
                return false;
            });
            connect.setOnClickListener(view -> {
                // Be more conservative than manual editing: do not burn a ticket while any form,
                // capture, draft, uncertain write, or installer handoff belongs to the old Panel.
                // savePanelConnection repeats its checks after the response to close the race.
                if (!safeToInstallBoundPanelSnapshot()) {
                    toastLong(t("download_pair_busy"));
                    return;
                }
                beginPanelPairingRedemption(request, dialog);
            });
        });
        dialog.setOnDismissListener(ignored -> {
            if (panelPairingDialog == dialog) panelPairingDialog = null;
        });
        dialog.show();
    }

    private void beginPanelPairingRedemption(
            PanelPairingLinkRules.Request request, AlertDialog dialog) {
        if (panelPairingRedeemInFlight) return;
        panelPairingRedeemInFlight = true;
        final int generation = panelPairingGeneration;
        final String expectedOldBase = AppConfig.panelBase(this);
        final String expectedOldKey = AppConfig.catalogKey(this);
        dialog.dismiss();
        toastLong(t("download_pair_redeeming"));
        panelPairingAttempt = PanelPairingRedeemer.redeem(
            request, result -> runOnUiThread(() -> {
            if (generation != panelPairingGeneration) return;
            panelPairingAttempt = null;
            panelPairingRedeemInFlight = false;
            if (!activityAlive()) return;
            if (result == null || !result.succeeded()) {
                String detail = result != null
                        && result.error == PanelPairingRedeemer.Error.EXPIRED
                    ? t("download_pair_expired") : t("download_pair_failed");
                alert(t("download_pair_failed_title"), detail);
                return;
            }
            if (!AppConfig.connectionMatches(this, expectedOldBase, expectedOldKey)) {
                alert(t("download_pair_failed_title"), t("download_pair_connection_changed"));
                return;
            }
            // The network exchange is not a lease on the old UI state. Re-run the exact strict
            // boundary after the response; if anything appeared meanwhile, discard this returned
            // key and require a fresh one-time ticket instead of entering manual-save prompts.
            if (!safeToInstallBoundPanelSnapshot()) {
                alert(t("download_pair_failed_title"), t("download_pair_busy"));
                return;
            }
            // Never write settings here. This existing path owns draft/remote-side-effect checks,
            // compare-and-commit under HANDOFF_LOCK, old-session cleanup and exact-pair resync.
            savePanelConnection(request.panelBase, result.accessKey,
                expectedOldBase, expectedOldKey);
            if (AppConfig.connectionMatches(this, request.panelBase, result.accessKey)) {
                // Unlike manual editing, the visible fields still show the old values. Rebuild the
                // Settings page from committed storage and show the normal synchronization gate.
                showSettingsPage();
            }
        }));
    }

    /** Persist the panel address + access key, then (re)connect: fetch config + re-sync the catalog. */
    private void savePanelConnection(String panelBaseInput, String catalogKeyInput) {
        savePanelConnection(panelBaseInput, catalogKeyInput, null, null,
            PanelConnectionInputRules.Source.MANUAL, false, "");
    }

    /** Pairing supplies the exact old connection; manual editing uses null expectations. */
    private void savePanelConnection(String panelBaseInput, String catalogKeyInput,
                                     String expectedOldBase, String expectedOldKey) {
        savePanelConnection(panelBaseInput, catalogKeyInput, expectedOldBase, expectedOldKey,
            PanelConnectionInputRules.Source.PAIRING, false, "");
    }

    private void savePanelConnection(String panelBaseInput, String catalogKeyInput,
                                     String expectedOldBase, String expectedOldKey,
                                     PanelConnectionInputRules.Source source,
                                     boolean discardConfirmed,
                                     String approvedAlternateEvidenceSha256) {
        String normalizedBase = panelBaseInput == null ? "" : panelBaseInput.trim();
        while (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }
        final String base = normalizedBase;
        final String key = catalogKeyInput == null ? "" : catalogKeyInput.trim();
        String oldBase = AppConfig.panelBase(this);
        String oldKey = AppConfig.catalogKey(this);
        PanelConnectionInputRules.Decision inputDecision =
            PanelConnectionInputRules.validate(source, oldBase, oldKey, base, key);
        if (!inputDecision.allowed()) {
            alert(t("panel_connection"), t("panel_connection_invalid_tuple_detail"));
            return;
        }
        if (panelBoundaryCleanupBlocked) {
            alert(t("alternate_entry_pending_title"),
                t("alternate_entry_storage_locked_detail"));
            return;
        }
        final boolean exactOldConnectionRequired =
            expectedOldBase != null && expectedOldKey != null;
        if (exactOldConnectionRequired
                && (!oldBase.equals(expectedOldBase) || !oldKey.equals(expectedOldKey))) {
            alert(t("download_pair_failed_title"), t("download_pair_connection_changed"));
            return;
        }
        boolean connectionChanged = !base.equals(oldBase) || !key.equals(oldKey);
        // Current-version reads never touch rollback v1 bytes. The atomic switch below removes
        // both historical v1 and current realm-bound v2 storage by explicit user intent.
        if (connectionChanged && legacyAStepContinuationPresent()) {
            alert(t("draft_save_failed"), t("legacy_a_step_upgrade_blocked_detail"));
            return;
        }
        if (connectionChanged
                && (submitting || profileOwnedRemoteWorkerActive()
                    || hasPendingMainFormOperation() || mainFormBoundWorkerActive()
                    || hasStoredOrUnreadableReprintAttempt()
                    || hasStoredUploadReplayBarrier()
                    || UpdateManager.installerHandoffActive(this))) {
            alert(t("draft_save_failed"), t("panel_connect_failed"));
            return;
        }
        if (connectionChanged) {
            UploadReplayBarrier.RestoreResult blockingUpload =
                blockingUploadReplayBarrier();
            if (blockingUpload != null) {
                showUploadReplayBarrierBlock(blockingUpload);
                return;
            }
            AlternateSubmissionAttempt.RestoreResult blockingMainAttempt =
                blockingMainSubmissionAttempt();
            if (blockingMainAttempt != null) {
                showMainSubmissionBlock(blockingMainAttempt);
                return;
            }
            if (hasStoredPreviousStepSubmissionAttempt()) {
                showPreviousStepSubmissionBlock(
                    restorePreviousStepSubmissionAttempt());
                return;
            }
            AlternateSubmissionAttempt.RestoreResult blockingAttempt =
                blockingAlternateSubmissionAttempt();
            if (blockingAttempt != null) {
                showAlternateSubmissionBlock(blockingAttempt);
                return;
            }
            if (RemoteSideEffectGate.blockingStatePresent(this)) {
                alert(t("draft_save_failed"), t("alternate_entry_storage_locked_detail"));
                return;
            }
        }
        if (connectionChanged && !discardConfirmed) {
            PanelConnectionAlternateEvidence evidence =
                alternateEntryPanelChangeCleanupEvidencePresent();
            if (evidence.present) {
                promptPanelConnectionAlternateDiscard(base, key,
                    expectedOldBase, expectedOldKey, source, evidence.sha256);
                return;
            }
        }
        if (connectionChanged && !units.isEmpty() && !saveDraft(true)) {
            alert(t("draft_save_failed"), t("panel_connect_failed"));
            return;
        }
        if (connectionChanged && !migrateLegacyPanelBoundState()) {
            alert(t("draft_save_failed"), t("panel_connect_failed"));
            return;
        }
        Map<String, ?> preferencesBeforeSwitch = Collections.emptyMap();
        final Set<String> switchedPreferenceKeys = new LinkedHashSet<>();
        final boolean saved;
        boolean restoredAfterFailedCommit = true;
        SessionBridge.LogoutCapability oldLogoutCapability = null;
        PanelConnectionAlternateCleanupReceipt alternateCleanupReceipt = null;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            // Close the gap between the last old-connection check and the preference commit. Old
            // refresh workers also need this lock before their final connection recheck/write.
            if ((exactOldConnectionRequired
                    && (!expectedOldBase.equals(AppConfig.panelBase(this))
                        || !expectedOldKey.equals(AppConfig.catalogKey(this))))
                    || (connectionChanged
                        && (!oldBase.equals(AppConfig.panelBase(this))
                            || !oldKey.equals(AppConfig.catalogKey(this))
                            || RemoteSideEffectGate.blockingStatePresent(this)
                            || UpdateManager.installerHandoffActive(this)))) {
                alert(t("draft_save_failed"), t("panel_connect_failed"));
                return;
            }
            if (connectionChanged) {
                try {
                    // Snapshot after the final locked recheck. A peer login/logout therefore
                    // cannot be lost or resurrected if the connection commit reports failure.
                    preferencesBeforeSwitch =
                        PanelConnectionPreferenceTransaction.snapshot(prefs);
                } catch (RuntimeException unreadable) {
                    alert(t("draft_save_failed"), t("panel_connect_failed"));
                    return;
                }
            }
            final String oldNamespace = AppConfig.connectionNamespaceId(oldBase, oldKey);
            PanelConnectionAlternateEvidence currentAlternateEvidence = connectionChanged
                ? alternateEntryPanelChangeCleanupEvidenceSnapshotLocked(
                    preferencesBeforeSwitch, oldNamespace)
                : new PanelConnectionAlternateEvidence(false, "");
            boolean alternateCleanupEvidence = currentAlternateEvidence.present;
            if (alternateCleanupEvidence && !discardConfirmed) {
                promptPanelConnectionAlternateDiscard(base, key,
                    expectedOldBase, expectedOldKey, source,
                    currentAlternateEvidence.sha256);
                return;
            }
            if (discardConfirmed && !currentAlternateEvidence.sha256.equals(
                    approvedAlternateEvidenceSha256)) {
                // The operator approved one exact snapshot. A camera/scan result or other edit that
                // arrived while the dialog was open belongs to a new snapshot and needs new consent.
                alert(t("alternate_entry_pending_title"),
                    t("alternate_entry_discard_failed"));
                return;
            }
            if (connectionChanged
                    && preferencesBeforeSwitch.containsKey(
                        PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY)) {
                alert(t("alternate_entry_pending_title"),
                    t("alternate_entry_storage_locked_detail"));
                return;
            }
            if (alternateCleanupEvidence) {
                try {
                    alternateCleanupReceipt =
                        capturePanelConnectionAlternateCleanupReceiptLocked(
                            preferencesBeforeSwitch, oldNamespace, base, key);
                } catch (RuntimeException | IOException unsafeCleanup) {
                    Diagnostics.append(this,
                        "Panel switch alternate cleanup capture blocked: "
                            + conciseError(unsafeCleanup));
                    alert(t("alternate_entry_pending_title"),
                        t("alternate_entry_discard_failed"));
                    return;
                }
            }
            SharedPreferences.Editor editor = prefs.edit();
            if (base.isEmpty()) editor.remove(AppConfig.KEY_PANEL_BASE);
            else editor.putString(AppConfig.KEY_PANEL_BASE, base);
            if (key.isEmpty()) editor.remove(AppConfig.KEY_CATALOG_KEY);
            else editor.putString(AppConfig.KEY_CATALOG_KEY, key);
            if (connectionChanged) {
                switchedPreferenceKeys.add(AppConfig.KEY_PANEL_BASE);
                switchedPreferenceKeys.add(AppConfig.KEY_CATALOG_KEY);
                switchedPreferenceKeys.addAll(removeRollbackMirrors(editor));
                // New Panel identity and old session/password/account invalidation are one durable
                // snapshot. A crash can therefore restore either the complete old connection or
                // the complete new connection with no old login credential available to send.
                switchedPreferenceKeys.addAll(
                    SecureTokenStore.stageClearForPanelConnectionChange(editor));
                switchedPreferenceKeys.addAll(
                    SecureTokenStore.stageSessionStateRotationForPanelConnectionChange(editor));
                if (alternateCleanupReceipt != null) {
                    switchedPreferenceKeys.addAll(stagePanelConnectionAlternateCleanup(
                        editor, oldNamespace, alternateCleanupReceipt));
                    panelBoundaryCleanupBlocked = true;
                }
            }
            // Capture the exact old session in the same critical section as the connection/key
            // commit. A concurrent peer login cannot replace it between capture and the wipe.
            if (connectionChanged) {
                oldLogoutCapability =
                    SessionBridge.captureLogoutCapability(getApplicationContext());
            }
            saved = editor.commit();
            if (!saved && connectionChanged) {
                // Android may update its in-memory map before commit() reports disk failure. Keep
                // the lock through rollback so no peer session mutation can be overwritten by it.
                restoredAfterFailedCommit = PanelConnectionPreferenceTransaction.restore(
                    prefs, preferencesBeforeSwitch, switchedPreferenceKeys);
            }
            if (!saved) panelBoundaryCleanupBlocked = false;
            if (saved && connectionChanged) {
                resetAlternateEntryMemoryForPanelConnectionChangeLocked();
            }
        }
        if (!saved) {
            if (connectionChanged && !restoredAfterFailedCommit) {
                Diagnostics.append(this,
                    "Panel connection preference commit failed; old snapshot restore was not durable");
            }
            toast(t("panel_connect_failed"));
            return;
        }
        if (connectionChanged) resetPanelBoundState(
            oldLogoutCapability != null && oldLogoutCapability.tokenPresent,
            oldLogoutCapability);
        if (alternateCleanupReceipt != null
                && !recoverPanelConnectionAlternateCleanupReceipt()) {
            showSettingsPage();
            alert(t("alternate_entry_pending_title"),
                t("alternate_entry_discard_failed"));
            return;
        }
        toast(t("saved"));
        if (base.isEmpty()) {
            showSettingsPage();
            return;
        }
        appendLog(t("panel_connecting"));
        // A fresh manager bypasses the once-per-process catalog guard. The shared bootstrap round
        // waits for both config and catalog before enabling login or any production action.
        formCatalogManager = new FormCatalogManager(this);
        synchronizePanelConnection(true);
    }

    private void promptPanelConnectionAlternateDiscard(
            String base, String key, String expectedOldBase, String expectedOldKey,
            PanelConnectionInputRules.Source source,
            String approvedAlternateEvidenceSha256) {
        new AlertDialog.Builder(this)
            .setTitle(t("alternate_entry_pending_title"))
            .setMessage(t("alternate_entry_panel_change_discard_detail"))
            .setNegativeButton(t("cancel"), null)
            .setPositiveButton(t("discard_draft"), (dialog, which) ->
                savePanelConnection(base, key, expectedOldBase, expectedOldKey,
                    source, true, approvedAlternateEvidenceSha256))
            .show();
    }

    /** A panel URL or key defines a security boundary; no cache/session crosses that boundary. */
    private void resetPanelBoundState(boolean hadTokenBeforeConnectionChange,
                                      SessionBridge.LogoutCapability oldLogoutCapability) {
        activeSessionRealmSha256 = "";
        unsafeCandidateContinuationLease = null;
        blockedRollbackMirrors.clear();
        manualQueueDeleteRecoveryBlocked = false;
        cancelPanelPairRetry(true);
        // Alternate-entry memory was invalidated inside the successful connection commit's lock.
        // Its captured preferences/photos are owned by the receipt and are never cleared here.
        appConfig = null;
        notificationSnapshot = null;
        NotificationClient.clearActiveSnapshot();
        FailureReporter.get().clearForPanelConnectionChange();
        if (!FormCatalogManager.clearConnectionState(this)) {
            Diagnostics.append(this,
                "Panel cache discard incomplete; new connection remains fail-closed");
        }
        synchronized (this) {
            panelSyncRound++;
            panelBootstrapState = PanelBootstrapRules.begin(
                currentConnectionNamespace(), !AppConfig.panelBase(this).isEmpty(),
                false, 0, false, 0);
        }
        activeCatalogVersion = 0;
        activePanelPairSha256 = "";
        // The successful connection commit already removed token/password/account in the same
        // durable SharedPreferences transaction as the new Panel URL/read key. Do not move those
        // removals back into asynchronous post-commit cleanup.
        captchaClient = "";
        lastAuthCheckMs = 0L;
        units.clear();
        draftPromptShown = false;
        try {
            FormCatalog.PreviewSnapshot preview =
                FormCatalog.loadBundledPreviewSnapshot(this);
            allProfiles = preview.profiles;
            catalogSettings = preview.settings;
            if (AppConfig.panelBase(this).isEmpty()
                    && AppConfig.catalogKey(this).isEmpty()) {
                activeCatalogVersion = preview.version;
                activePanelPairSha256 = preview.pairSha256;
            }
            profiles = filterPickerProfiles(allProfiles);
            profile = profiles.length() > 0 ? profiles.getJSONObject(0) : null;
        } catch (Exception error) {
            allProfiles = new JSONArray();
            profiles = new JSONArray();
            profile = null;
            catalogSettings = null;
        }
        if (hadTokenBeforeConnectionChange) {
            SessionBridge.propagateLogout(
                getApplicationContext(), null, oldLogoutCapability);
        }
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

    private void notifyMissing(String sn, List<String> codes, boolean willRetry) {
        List<String> labels = new ArrayList<>();
        for (String code : codes) labels.add(materialLabel(code));
        String message = sn + " " + t("missing_material") + ": " + join(labels, ", ");
        appendLog(message);
        if (missingMaterialNoticeShown) {
            appendLog(t("missing_notice_once"));
            return;
        }
        missingMaterialNoticeShown = true;
        String detail = t(willRetry ? "missing_retry_note" : "missing_retry_exhausted_note");
        runOnUiThread(() -> autoDismissAlert(
            t("missing_material_notice"), message + "\n" + detail, 3000));
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
                        // The localized name is panel-owned and may already include the code, so do
                        // not append another deployment-specific representation here.
                        String name = localized(material, "name", "nameI18n");
                        return name.isEmpty() ? code : name;
                    }
                }
            }
        } catch (JSONException ignored) {
        }
        return code;
    }

    // The profile explicitly opts into round reporting. v2 keeps its aggregate-only payload; v3
    // restores the legacy round details while the private Panel still owns all visible templates.
    private void notifyRoundToNotify(boolean success, int submitted, List<String> errors,
                                     List<String> inlineFailed) {
        ProfileWorkflow notificationWorkflow = profileWorkflow();
        if (!notificationWorkflow.submissionSummaryNotificationEnabled) return;
        final NotificationClient.Snapshot notifySnapshot = notificationSnapshot;
        boolean roundV3 = NotificationClient.isConfigured(
            this, notifySnapshot, NotificationClient.EVENT_SUBMISSION_ROUND);
        boolean summaryV2 = NotificationClient.isConfigured(
            this, notifySnapshot, NotificationClient.EVENT_SUBMISSION_SUMMARY);
        if (!roundV3 && !summaryV2) return;
        // Check the Panel-owned v3 display name before mutating the previous-round comparison
        // ledger. A half-migrated profile behaves as notification-disabled rather than consuming
        // a round and changing what a later correctly configured notification reports.
        if (roundV3 && notificationWorkflow.notificationProfileLabel.isEmpty()) {
            appendLog(t("notify_disabled"));
            return;
        }
        String profileId = profile == null ? "" : profile.optString("id", "");

        LinkedHashMap<String, LinkedHashSet<String>> snapshot = new LinkedHashMap<>();
        synchronized (roundMissingMaterials) {
            for (Map.Entry<String, LinkedHashSet<String>> entry : roundMissingMaterials.entrySet()) {
                snapshot.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }
        Set<String> thisRound = snapshot.keySet();
        String previousRoundKey = "prevRoundMissing_" + profileId;
        Set<String> prevRound = loadPrevRoundMissing(profileId);
        if (blockedRollbackMirrors.contains(previousRoundKey)) {
            // The old comparison set may belong to another Panel or may not have reached disk with
            // its receipt. Keep its bytes untouched and suppress the whole outbound event: treating
            // fallback-empty as real history would publish false added/recovered material changes.
            Diagnostics.append(this,
                "Round notification blocked by unresolved previous-round mirror");
            appendLog(t("notify_disabled"));
            return;
        }
        List<String> added = new ArrayList<>();
        for (String code : thisRound) if (!prevRound.contains(code)) added.add(code);
        List<String> recovered = new ArrayList<>();
        for (String code : prevRound) if (!thisRound.contains(code)) recovered.add(code);
        if (!savePrevRoundMissing(profileId, thisRound)) {
            Diagnostics.append(this,
                "Round notification blocked because previous-round state was not durable");
            appendLog(t("notify_disabled"));
            return;
        }
        List<Map.Entry<String, Integer>> networkAffected = new ArrayList<>();
        synchronized (dnsAffectedUnits) {
            networkAffected.addAll(dnsAffectedUnits.entrySet());
        }

        if (roundV3) {
            try {
                List<NotificationEventData.MissingItem> missingItems = new ArrayList<>();
                for (Map.Entry<String, LinkedHashSet<String>> entry : snapshot.entrySet()) {
                    missingItems.add(NotificationEventData.missingItem(
                        materialLabel(entry.getKey()), entry.getValue().size()));
                }
                List<String> addedLabels = new ArrayList<>();
                for (String code : added) addedLabels.add(materialLabel(code));
                List<String> recoveredLabels = new ArrayList<>();
                for (String code : recovered) recoveredLabels.add(materialLabel(code));
                List<String> networkIdentifiers = new ArrayList<>();
                for (Map.Entry<String, Integer> entry : networkAffected) {
                    networkIdentifiers.add(formatUnitPosition(entry.getValue())
                        + " SN=" + entry.getKey());
                }
                String operator = savedUserName();
                if (operator.isEmpty()) operator = savedAccount();
                String completedAt = NotificationEventData.formatCompletedAt(
                    System.currentTimeMillis(), java.util.TimeZone.getDefault());
                JSONObject data = NotificationEventData.submissionRound(
                    success,
                    notificationWorkflow.notificationProfileLabel,
                    operator,
                    completedAt,
                    submitted,
                    missingItems,
                    addedLabels,
                    recoveredLabels,
                    errors == null ? Collections.emptyList() : new ArrayList<>(errors),
                    inlineFailed == null
                        ? Collections.emptyList() : new ArrayList<>(inlineFailed),
                    networkIdentifiers);
                postNotifyEvent(notifySnapshot,
                    NotificationClient.EVENT_SUBMISSION_ROUND, data);
            } catch (IllegalArgumentException invalid) {
                appendLog(t("notify_failed") + " " + invalid.getMessage());
                Diagnostics.append(this,
                    "Submission round notification rejected before send: " + invalid.getMessage());
            }
            return;
        }

        postNotifyEvent(notifySnapshot, NotificationClient.EVENT_SUBMISSION_SUMMARY,
            NotificationEventData.submissionSummary(
                success,
                submitted,
                errors == null ? 0 : errors.size(),
                inlineFailed == null ? 0 : inlineFailed.size(),
                snapshot.size(),
                added.size(),
                recovered.size(),
                networkAffected.size()));
    }

    private Set<String> loadPrevRoundMissing(String profileId) {
        Set<String> out = new HashSet<>();
        try {
            String raw = readAndMirrorRollbackPreference(
                "prevRoundMissing_" + profileId, "");
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

    private boolean savePrevRoundMissing(String profileId, Set<String> codes) {
        JSONArray arr = new JSONArray();
        for (String code : codes) arr.put(code);
        String key = "prevRoundMissing_" + profileId;
        if (blockedRollbackMirrors.contains(key)) {
            Diagnostics.append(this, "Previous-round mirror remains locked");
            return false;
        }
        boolean committed = putMirroredRollbackPreference(
            prefs.edit(), key, arr.toString()).commit();
        if (!committed || !rollbackPreferenceMirrored(key, "")) {
            blockedRollbackMirrors.add(key);
            Diagnostics.append(this, "Previous-round mirror commit verification failed");
            return false;
        }
        blockedRollbackMirrors.remove(key);
        return true;
    }

    // ---- Local round ledger: source of truth for print reconciliation (see ROUND_LEDGER_KEY) ----
    private JSONObject ledgerUnit(String sn, boolean submitOk, boolean printed, String grade) {
        JSONObject o = new JSONObject();
        try {
            o.put("sn", sn == null ? "" : sn);
            o.put("submit", submitOk ? "ok" : "failed");
            o.put("printed", submitOk ? (printed ? "ok" : "unconfirmed") : "na");
            if (grade != null && !grade.trim().isEmpty()) o.put("grade", grade.trim());
        } catch (JSONException ignored) {}
        return o;
    }

    private void saveRoundToLedger(PrintRemoteContext context,
                                   List<JSONObject> roundUnits, String profileId,
                                   int retentionDays) {
        if (roundUnits == null || roundUnits.isEmpty()) return;
        try {
            requirePrintRemoteBinding(context, context.binding, 0L, "batch",
                "batch ledger build");
            long now = System.currentTimeMillis();
            JSONObject round = new JSONObject();
            round.put("ts", now);
            round.put("tsText", new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(now)));
            round.put("profileId", profileId == null ? "" : profileId);
            round.put("retentionDays", SubmissionPolicyRules.retentionDays(retentionDays, 1));
            JSONArray arr = new JSONArray();
            for (JSONObject u : roundUnits) arr.put(u);
            round.put("units", arr);
            JSONArray ledger = loadLedgerArray();
            ledger.put(round);
            // This runs on the submit worker, so a synchronous disk commit does not block the UI. It
            // closes the crash window between removing an uploaded unit from the queue and recording
            // its label as confirmed/unconfirmed for post-login reconciliation.
            String serialized = pruneLedger(ledger, now).toString();
            requirePrintRemoteBinding(context, context.binding, 0L, "batch",
                "batch ledger commit");
            if (blockedRollbackMirrors.contains(ROUND_LEDGER_KEY)
                    || !putMirroredRollbackPreference(
                        prefs.edit(), ROUND_LEDGER_KEY, serialized).commit()) {
                throw new IllegalStateException("round ledger SharedPreferences commit failed");
            }
        } catch (Exception exc) {
            appendLog("round ledger save failed: " + exc.getMessage());
        }
    }

    private JSONArray loadLedgerArray() {
        try { return new JSONArray(readAndMirrorRollbackPreference(ROUND_LEDGER_KEY, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private int roundLedgerRetentionDaysForProfile(String profileId) {
        JSONObject configured = null;
        for (int i = 0; allProfiles != null && i < allProfiles.length(); i++) {
            JSONObject candidate = allProfiles.optJSONObject(i);
            if (candidate != null && profileId != null
                    && profileId.equals(candidate.optString("id", ""))) {
                configured = candidate;
                break;
            }
        }
        return ProfileWorkflow.from(configured).roundLedgerRetentionDays;
    }

    // Apply each round's profile-owned retention window so the ledger cannot grow without bound.
    private JSONArray pruneLedger(JSONArray ledger, long now) {
        JSONArray kept = new JSONArray();
        for (int i = 0; ledger != null && i < ledger.length(); i++) {
            JSONObject r = ledger.optJSONObject(i);
            if (r == null) continue;
            int retentionDays = SubmissionPolicyRules.retentionDays(
                r.optInt("retentionDays", 0),
                roundLedgerRetentionDaysForProfile(r.optString("profileId", "")));
            if (SubmissionPolicyRules.retainsRound(
                    r.optLong("ts", 0), now, retentionDays)) kept.put(r);
        }
        return kept;
    }

    // Most-recent rounds first, capped at `max` (e.g. 3 for the reconcile view).
    private List<JSONObject> loadRecentRounds(int max, String profileId) {
        JSONArray ledger = pruneLedger(loadLedgerArray(), System.currentTimeMillis());
        List<JSONObject> out = new ArrayList<>();
        for (int i = ledger.length() - 1; i >= 0 && out.size() < max; i--) {
            JSONObject r = ledger.optJSONObject(i);
            if (r != null && profileId != null
                    && profileId.equals(r.optString("profileId", ""))) out.add(r);
        }
        return out;
    }

    // After a remote verify resolves a still-unconfirmed unit, upgrade it to printed="ok" in the
    // persisted ledger so it stays green and is not re-queried on the next open. Never downgrades or touches
    // submit/na — a monotonic, lossless resolution. Matches the in-memory rounds back to the ledger by ts+sn.
    private void persistLedgerPrintedOk(PrintRemoteContext context,
                                        List<JSONObject> rounds) {
        if (rounds == null || rounds.isEmpty()) return;
        try {
            requirePrintRemoteBinding(context, context.binding, 0L, "ledger",
                "printed ledger read");
            JSONArray ledger = loadLedgerArray();
            boolean changed = false;
            for (JSONObject r : rounds) {
                long ts = r.optLong("ts", 0);
                String profileId = r.optString("profileId", "");
                if (profileId.isEmpty()) continue;
                JSONArray us = r.optJSONArray("units");
                for (int i = 0; us != null && i < us.length(); i++) {
                    JSONObject u = us.optJSONObject(i);
                    if (u == null || remotePrintStatus(u) != PRINT_STATUS_PRINTED) continue;
                    if ("ok".equals(u.optString("printed"))) continue;
                    u.put("printed", "ok"); // keep the in-memory round consistent with what we persist
                    if (markLedgerUnitPrinted(
                            ledger, profileId, ts, u.optString("sn", ""))) changed = true;
                }
            }
            if (changed && !blockedRollbackMirrors.contains(ROUND_LEDGER_KEY)) {
                requirePrintRemoteBinding(context, context.binding, 0L, "ledger",
                    "printed ledger commit");
                if (!putMirroredRollbackPreference(
                        prefs.edit(), ROUND_LEDGER_KEY, ledger.toString()).commit()) {
                    throw new IOException("printed ledger commit failed");
                }
                if (!printRemoteBindingStillCurrent(context)) {
                    Diagnostics.append(this,
                        "Print binding changed after an exact ledger commit");
                }
            }
        } catch (Exception exc) {
            appendLog("ledger resolve save failed: " + exc.getMessage());
        }
    }

    private boolean markLedgerUnitPrinted(JSONArray ledger, String profileId, long ts, String sn) {
        if (sn == null || sn.isEmpty()) return false;
        try {
            for (int i = 0; ledger != null && i < ledger.length(); i++) {
                JSONObject r = ledger.optJSONObject(i);
                if (r == null || r.optLong("ts", 0) != ts
                        || !profileId.equals(r.optString("profileId", ""))) continue;
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

    private void postNotifyEvent(NotificationClient.Snapshot notifySnapshot,
                                 String type, JSONObject data) {
        if (!NotificationClient.isConfigured(this, notifySnapshot, type)) {
            appendLog(t("notify_disabled"));
            return;
        }
        new Thread(() -> {
            NotificationClient.Result result = NotificationClient.postEvent(
                this, notifySnapshot, type, data);
            if (result.success) {
                appendLog(t("notify_sent"));
            } else {
                String detail = result.statusCode > 0
                    ? "HTTP " + result.statusCode : result.error;
                appendLog(t("notify_failed") + " " + detail);
                Diagnostics.append(this, "Notification send failed: " + detail);
            }
        }).start();
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
        JSONObject alternateStats = loadDailyAlternateStats(date);
        List<String> statLabels = new ArrayList<>();
        List<Integer> statCounts = new ArrayList<>();
        List<Integer> statColors = new ArrayList<>();
        List<String> flatStatLabels = new ArrayList<>();
        List<Integer> flatStatCounts = new ArrayList<>();
        List<Integer> flatStatColors = new ArrayList<>();
        int total = 0;
        JSONObject configuredV2 = DailyStatsRules.allProfilesV2(
            catalogSettings, profiles, allProfiles);
        JSONObject configuredAlternateEntries = configuredV2 == null ? null
            : DailyStatsRules.allProfilesAlternateEntries(
                catalogSettings, allProfiles, configuredV2);
        JSONArray configuredAlternateGroups = configuredAlternateEntries == null ? null
            : configuredAlternateEntries.optJSONArray("groups");
        JSONArray configuredAlternateFlatSummaries = configuredAlternateEntries == null ? null
            : configuredAlternateEntries.optJSONArray("flatSummaries");
        JSONArray configuredGroups = configuredV2 == null
            ? DailyStatsRules.allProfilesGroups(catalogSettings)
            : configuredV2.optJSONArray("groups");
        if (configuredV2 != null) {
            for (int index = 0; index < configuredGroups.length(); index++) {
                JSONObject group = configuredGroups.optJSONObject(index);
                if (group == null) continue;
                int count = DailyStatsRules.displayedSelectedCount(
                    stats, group.optJSONArray("selectors"),
                    group.optJSONArray("legacyResultKeys"));
                count = saturatedAdd(count, DailyStatsRules.displayedAlternateCount(
                    alternateStats, configuredAlternateGroups, group.optString("id", "")));
                Integer color = parseColor(group.optString("uiColor", ""));
                statLabels.add(localized(group, "label", "labelI18n"));
                statCounts.add(count);
                statColors.add(color == null ? 0xFF64748B : color);
                total = saturatedAdd(total, count);
            }
            JSONArray flatSummaries = configuredV2.optJSONArray("flatSummaries");
            for (int index = 0; flatSummaries != null
                    && index < flatSummaries.length(); index++) {
                JSONObject summary = flatSummaries.optJSONObject(index);
                if (summary == null) continue;
                int count = DailyStatsRules.displayedSelectedCount(
                    stats, summary.optJSONArray("selectors"), null);
                count = saturatedAdd(count, DailyStatsRules.displayedAlternateCount(
                    alternateStats, configuredAlternateFlatSummaries,
                    summary.optString("id", "")));
                Integer color = parseColor(summary.optString("uiColor", ""));
                flatStatLabels.add(localized(summary, "label", "labelI18n"));
                flatStatCounts.add(count);
                flatStatColors.add(color == null ? 0xFF64748B : color);
            }
        } else if (configuredGroups != null) {
            for (int index = 0; index < configuredGroups.length(); index++) {
                JSONObject group = configuredGroups.optJSONObject(index);
                if (group == null) continue;
                int count = DailyStatsRules.displayedAllProfilesCount(
                    stats, profiles, group.optJSONArray("resultKeys"));
                Integer color = parseColor(group.optString("uiColor", ""));
                statLabels.add(localized(group, "label", "labelI18n"));
                statCounts.add(count);
                statColors.add(color == null ? 0xFF64748B : color);
                total = saturatedAdd(total, count);
            }
        } else {
            JSONObject byProfile = stats.optJSONObject("results");
            JSONObject resultCounts = byProfile == null
                ? null : byProfile.optJSONObject(currentProfileId());
            if (resultCounts == null) resultCounts = new JSONObject();
            for (String key : availableGrades()) {
                int count = DailyStatsRules.displayedCount(stats, resultCounts, key);
                statLabels.add(resultLabel(key));
                statCounts.add(count);
                statColors.add(gradeColor(key));
                total = saturatedAdd(total, count);
            }
        }

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

        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = row();
        for (int index = 0; index < statLabels.size(); index++) {
            int color = statColors.get(index);
            cards.addView(statCard(statLabels.get(index), statCounts.get(index), color,
                lightenColor(color)), statCardParams());
        }
        scroller.addView(cards);
        LinearLayout.LayoutParams cardsParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(scroller, cardsParams);
        for (int index = 0; index < flatStatLabels.size(); index++) {
            panel.addView(flatStatRow(flatStatLabels.get(index), flatStatCounts.get(index),
                flatStatColors.get(index)));
        }
        return panel;
    }

    private LinearLayout.LayoutParams statCardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(120), dp(76));
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private View statCard(String label, int count, int color, int bgColor) {
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

        TextView labelText = text(label, 13, true);
        labelText.setTextColor(0xFF334155);
        labelText.setGravity(Gravity.CENTER);
        card.addView(labelText);
        return card;
    }

    private View flatStatRow(String label, int count, int color) {
        LinearLayout summary = row();
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(3), dp(9), dp(3), 0);
        summary.setLayoutParams(params);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(lightenColor(color));
        bg.setStroke(dp(1), color);
        bg.setCornerRadius(dp(8));
        summary.setBackground(bg);

        TextView labelText = text(label, 14, true);
        labelText.setTextColor(0xFF334155);
        summary.addView(labelText,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView countText = text(String.valueOf(count), 20, true);
        countText.setTextColor(color);
        countText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        summary.addView(countText);
        return summary;
    }

    private int saturatedAdd(int left, int right) {
        long total = (long) Math.max(0, left) + Math.max(0, right);
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
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
        String grade = unit.grade == null ? "" : unit.grade.trim();
        if (grade.isEmpty() || !hasGrade(grade)) return;
        try {
            String date = todayStatsDate();
            JSONObject stats = loadDailyStats(date);
            JSONArray counted = stats.optJSONArray("counted");
            if (counted == null) counted = new JSONArray();
            String profileId = profile == null ? "" : profile.optString("id", "");
            String key = DailyStatsRules.mainCountedToken(profileId, unit.sn);
            String legacyKeyToken = DailyStatsRules.legacyMainCountedToken(
                profileId, unit.sn);
            if (jsonArrayContains(counted, key)
                    || jsonArrayContains(counted, legacyKeyToken)) return;
            counted.put(key);
            // Keep the signed-v1 token too so an intentional rollback remains idempotent.
            counted.put(legacyKeyToken);
            stats.put("counted", counted);
            JSONObject allResults = stats.optJSONObject("results");
            if (allResults == null) allResults = new JSONObject();
            JSONObject profileResults = allResults.optJSONObject(profileId);
            if (profileResults == null) profileResults = new JSONObject();
            profileResults.put(grade, profileResults.optInt(grade, 0) + 1);
            allResults.put(profileId, profileResults);
            stats.put("results", allResults);
            String legacyKey = DAILY_STATS_PREFIX + date;
            if (blockedRollbackMirrors.contains(legacyKey)) {
                throw new IllegalStateException("daily stats rollback mirror is unresolved");
            }
            putMirroredRollbackPreference(
                prefs.edit(), legacyKey, stats.toString()).apply();
        } catch (Exception exc) {
            appendLog(t("daily_stats_save_failed") + exc.getMessage());
        }
    }

    /** Durable, idempotent local acknowledgement for one exact independent-entry completion. */
    private boolean recordDailyAlternateOutput(String sourceProfileId, String entryId,
                                               String serial) {
        try {
            String date = todayStatsDate();
            JSONObject stats = loadDailyAlternateStats(date);
            if (!DailyStatsRules.recordAlternateEntry(
                    stats, sourceProfileId, entryId, serial)) {
                Diagnostics.append(this,
                    "Independent-entry daily stats rejected invalid identity");
                return false;
            }
            boolean committed = prefs.edit().putString(
                dailyAlternateStatsPreferenceKey(date), stats.toString()).commit();
            if (!committed) {
                Diagnostics.append(this,
                    "Independent-entry daily stats durable commit failed");
            }
            return committed;
        } catch (Exception error) {
            Diagnostics.append(this, "Independent-entry daily stats failed: "
                + conciseError(error));
            return false;
        }
    }

    private String dailyAlternateStatsPreferenceKey(String date) {
        return panelStatePreferenceKey(ALTERNATE_DAILY_STATS_PREFIX + date);
    }

    private JSONObject loadDailyAlternateStats(String date) {
        try {
            String raw = prefs.getString(dailyAlternateStatsPreferenceKey(date), "");
            if (raw == null || raw.isEmpty()) return new JSONObject();
            JSONObject stats = new JSONObject(raw);
            return DailyStatsRules.validAlternateEntryStats(stats)
                ? stats : new JSONObject();
        } catch (Exception invalid) {
            Diagnostics.append(this, "Independent-entry daily stats are unreadable");
            return new JSONObject();
        }
    }

    private JSONObject loadDailyStats(String date) {
        String legacyKey = DAILY_STATS_PREFIX + date;
        String raw = readAndMirrorRollbackPreference(legacyKey, "");
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            JSONObject stats = new JSONObject(raw);
            if (DailyStatsRules.migrateLegacyRootCounts(stats)) {
                if (!blockedRollbackMirrors.contains(legacyKey)) {
                    putMirroredRollbackPreference(
                        prefs.edit(), legacyKey, stats.toString()).apply();
                }
            }
            return stats;
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

    private void setPageContentView(ScrollView scroll) {
        SystemBarInsets.reserveSystemBars(scroll);
        setContentView(scroll);
        insetAwarePageView = scroll;
        SystemBarInsets.requestWhenAttached(scroll);
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
        Pattern materialPattern = MaterialCodeRules.highlightPattern(materialCodeSet());
        if (materialPattern != null) {
            applyLogPatternSpan(styled, materialPattern, 0, 0xFFB45309, true);
        }
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
            case "queue_backup_delete": return "\u5220\u9664\u961f\u5217\u5907\u4efd";
            case "queue_backup_delete_confirm": return "\u53ea\u6709\u5728\u786e\u5b9a\u4e0d\u518d\u9700\u8981\u8fd9\u4efd\u961f\u5217\u65f6\u624d\u80fd\u5220\u9664\u3002\u5220\u9664\u540e\u65e0\u6cd5\u6062\u590d\uff0c\u5e76\u53ef\u80fd\u5141\u8bb8\u5df2\u4e0b\u8f7d\u7684\u65b0\u9762\u677f\u914d\u7f6e\u5728\u4e0b\u4e00\u4e2a\u5b89\u5168\u8fb9\u754c\u751f\u6548\u3002\u786e\u5b9a\u5220\u9664\uff1f";
            case "queue_backup_deleted": return "\u961f\u5217\u5907\u4efd\u5df2\u5220\u9664";
            case "queue_backup_delete_failed": return "\u961f\u5217\u5907\u4efd\u5220\u9664\u5931\u8d25";
            case "queue_backup_delete_kept": return "\u5df2\u4fdd\u7559\u6216\u6062\u590d\u539f\u5907\u4efd\uff0c\u8bf7\u91cd\u8bd5\u3002";
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
            case "current_model": return "当前表单";
            case "saved_model": return "已保存表单";
            case "switch_model_to_continue": return "\u5207\u6362\u5230\u8be5\u8868\u5355\u53ef\u7ee7\u7eed\u5f55\u5165";
            case "photo_target_missing": return "\u62cd\u7167\u72b6\u6001\u4e22\u5931\uff0c\u5df2\u4fdd\u7559\u8349\u7a3f\uff0c\u8bf7\u56de\u5230\u5f55\u8868\u9875\u91cd\u8bd5\u3002";
            case "photo_preview_failed": return "\u7167\u7247\u9884\u89c8\u5931\u8d25\uff0c\u539f\u56fe\u6587\u4ef6\u4ecd\u4fdd\u7559\uff0c\u53ef\u7ee7\u7eed\u63d0\u4ea4\u3002";
            case "diagnostic_log_title": return "\u6700\u8fd1\u8bca\u65ad\u8bb0\u5f55";
            case "settings_title": return "自动录表";
            case "saved": return "已保存";
            case "panel_connection": return "面板连接";
            case "panel_connection_invalid_tuple_detail": return "面板地址和访问密钥必须同时填写或同时清空。手动更换面板地址时，请同时输入该面板的新访问密钥，不能沿用原地址已保存的密钥。";
            case "panel_connection_hint": return "填写表单系统的面板地址与访问密钥（示例：https://your-panel.example.com），保存后自动连接；两项都留空表示未配置，需先配置才能登录。";
            case "panel_base": return "面板地址";
            case "panel_base_hint": return "例如 https://your-panel.example.com";
            case "catalog_key": return "访问密钥";
            case "catalog_key_hint": return "面板提供的访问密钥（Bearer）";
            case "panel_save": return "保存";
            case "panel_current_api": return "当前后端：";
            case "panel_unconfigured": return "未配置";
            case "panel_syncing_short": return "正在同步";
            case "panel_pair_pending_short": return "等待安全切换";
            case "panel_required_title": return "请先配置面板";
            case "panel_required_detail": return "请先在设置里填写面板地址和访问密钥，再登录。";
            case "panel_syncing_detail": return "正在同步当前面板的配置和表单；两项校验完成前不能登录、进入表单、拍照或提交。";
            case "panel_active_pair_pending_detail": return "新的面板配置已就绪，但当前仍有未完成记录、草稿或拍照任务。为避免新旧表单混用，请先用当前版本完成或明确清理这些内容，再返回设置应用更新。";
            case "sample_catalog_title": return "当前是示例配置";
            case "sample_catalog_detail": return "内置示例仅用于预览，不能登录或提交。请连接面板并发布实际表单。";
            case "no_picker_profiles": return "面板未发布任何 pickerVisible=true 的表单。";
            case "panel_missing_config": return "面板配置不完整，缺少：";
            case "profile_policy_migration_required": return "当前表单仍是旧策略格式，请先在 Panel 完成运行策略迁移后再提交。";
            case "profile_workflow_missing": return "当前表单缺少 workflow 配置，已阻止提交。请先在面板发布完整配置。";
            case "workflow_previous_steps_disabled": return "当前表单未启用前置步骤检查。";
            case "panel_connecting": return "正在连接面板…";
            case "panel_connected": return "面板已连接";
            case "panel_connect_failed": return "面板连接失败，请检查地址和访问密钥";
            case "download_pair_title": return "连接下载页面板";
            case "download_pair_confirm_new": return "将从下面的面板兑换一次性凭证并保存连接。确认域名无误后再继续：";
            case "download_pair_confirm_replace": return "将更新或切换到下面的面板；成功后会退出当前账号并清理旧面板缓存。确认域名无误后再继续：";
            case "download_pair_connect": return "确认并连接";
            case "download_pair_redeeming": return "正在安全获取面板连接…";
            case "download_pair_busy": return "当前还有表单、照片、草稿或未确认操作，请先处理完成，再重新打开下载页连接。";
            case "download_pair_invalid_title": return "连接链接无效";
            case "download_pair_invalid_detail": return "此链接无效、已过期或不是为当前 App 生成的。现有面板连接未改变。";
            case "download_pair_failed_title": return "一键连接未完成";
            case "download_pair_expired": return "一次性凭证已过期，请回到下载页重新生成。现有面板连接未改变。";
            case "download_pair_failed": return "该面板尚未启用此接口，或本次兑换失败。请回到下载页重试；现有面板连接未改变。";
            case "download_pair_connection_changed": return "兑换期间面板连接已被修改，本次结果已丢弃。请确认当前连接后回到下载页重新生成。";
            case "download_pair_in_progress": return "已有一项面板连接正在处理，请等待完成后再试。";
            case "download_pair_obscured": return "检测到其他窗口遮挡，请关闭悬浮窗后重新确认面板域名。";
            case "notify_sent": return "通知已发送";
            case "notify_failed": return "通知发送失败：";
            case "notify_disabled": return "未配置面板通知端点，跳过通知。";
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
            case "scan_sn": return "\u626b\u7801/\u8bc6\u522b SN";
            case "ocr_sn": return "拍照识别";
            case "add": return "加入";
            case "scan_base": return "\u626b\u7801/\u8bc6\u522b\u6b21\u8981\u6807\u8bc6";
            case "ocr_base": return "拍照识别";
            case "match": return "匹配";
            case "photos": return "拍照";
            case "take_next_photo": return "拍下一张";
            case "choose_gallery_photo": return "\u4ece\u76f8\u518c\u6dfb\u52a0\u56fe\u7247";
            case "gallery_missing_title": return "\u76f8\u518c\u4e0d\u53ef\u7528";
            case "gallery_missing_detail": return "\u7cfb\u7edf\u6ca1\u6709\u53ef\u7528\u7684\u56fe\u7247\u9009\u62e9\u5668\u3002";
            case "delete_photo": return "\u5220\u9664";
            case "go_back": return "返回";
            case "alternate_entry_subtitle": return "选择表单、录入一个标识并拍照后提交。提交目标和字段全部由面板配置。";
            case "alternate_entry_clear_serial": return "清除";
            case "alternate_entry_serial_empty": return "尚未录入标识";
            case "alternate_entry_photo": return "附件照片";
            case "alternate_entry_add_photo": return "添加照片";
            case "alternate_entry_no_photo": return "尚未拍照";
            case "alternate_entry_photo_count": return "照片数量：";
            case "alternate_entry_photo_item": return "照片 ";
            case "alternate_entry_photo_limit": return "已达到面板配置的照片数量上限。";
            case "alternate_entry_submit": return "提交这一条";
            case "alternate_entry_done": return "提交完成";
            case "alternate_entry_invalid": return "独立入口配置无效，已阻止打开或提交。请在面板修正。";
            case "alternate_entry_pending_title": return "仍有未提交内容";
            case "alternate_entry_pending_detail": return "请先返回原独立入口提交或清空标识和照片，再切换到其他入口。";
            case "alternate_entry_draft_locked_detail": return "设备上有一个绑定到原面板、原账号和原目标的未完成草稿。为防止传错，当前配置下不会重新解释它；请恢复原配置后继续，或确认后丢弃本地草稿。";
            case "alternate_entry_panel_change_discard_detail": return "切换面板会丢弃当前独立入口的标识和照片。只有确认这些内容不再需要时才继续。";
            case "alternate_entry_result_uncertain_title": return "提交结果待确认";
            case "alternate_entry_result_uncertain_detail": return "请求已经发出，但设备没有获得可证明成功或失败的结果。为防止重复或传错，当前记录已锁定，不能直接重提或切换面板；必须按原目标精确确认后再处理。";
            case "upload_result_uncertain_title": return "上传状态待确认";
            case "upload_result_uncertain_detail": return "设备已经开始上传图片，但没有完成可验证的本地终态。为防止重复上传或传错位置，相关记录、面板切换和应用更新已锁定；请先在原后端核对，不能直接重提。";
            case "previous_step_result_uncertain_detail": return "前置记录请求已经发出，但设备无法确认结果。为防止重复创建，相关草稿、后续提交和面板切换已锁定；请先在原目标人工核对该前置记录，不能直接重提。";
            case "alternate_entry_storage_locked_detail": return "本地提交安全记录无法读取或清除，系统无法证明可以再次提交，因此没有发送新请求。请保留设备数据和诊断日志并联系维护人员处理。";
            case "alternate_entry_completed_cleanup_title": return "上次提交已确认成功";
            case "alternate_entry_completed_cleanup_detail": return "后端已确认收到上次提交，但本地草稿尚未安全清理。请勿再次提交；可清理这份已提交的本地副本后继续。";
            case "alternate_entry_cleanup_local": return "清理本地副本";
            case "alternate_entry_cleanup_failed": return "本地副本清理失败，成功记录仍保持锁定；请勿重提并联系维护人员。";
            case "alternate_entry_discard_failed": return "草稿未能从设备安全删除，照片仍保留，未切换配置。请重试或联系维护人员。";
            case "alternate_entry_queue_save_failed": return "主表单队列无法安全保存，因此没有打开独立入口。";
            case "alternate_entry_async_pending": return "上次扫码或拍照尚未结束；如需重扫，请再次点击扫码并确认取消上次扫码。";
            case "alternate_entry_cancel_scan_title": return "取消上次扫码？";
            case "alternate_entry_cancel_scan_detail": return "只取消未完成的扫码预留；已录入标识、照片以及提交和上传安全记录都不会删除。确认后会立即重新打开扫码。";
            case "alternate_entry_cancel_scan_action": return "取消并重扫";
            case "alternate_entry_scan_cancelled": return "已取消上次未完成的扫码。";
            case "submit": return "提交";
            case "preview_payload": return "预览 Payload";
            case "check_steps": return "检查前置记录";
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
            case "scan_not_sn_title": return "识别到的不像 SN";
            case "scan_not_sn_detail": return "当前表单不接受纯数字识别结果，请重新扫描或使用拍照识别。";
            case "scan_result_invalid_title": return "扫码结果无效";
            case "scan_result_invalid_detail": return "扫码组件没有返回可验证的识别来源，结果已拒绝，请重新扫描。";
            case "ocr_unavailable_title": return "图片识别暂不可用";
            case "ocr_unavailable_detail": return "公司用户信息接口没有返回图片识别地址，请确认账号权限或联系维护人员检查 recognizeTextUrl。";
            case "ocr_url_refreshing": return "正在同步图片识别地址...";
            case "ocr_running": return "正在识别图片文字...";
            case "ocr_failed": return "图片文字识别失败";
            case "ocr_no_text_title": return "没有找到 SN";
            case "ocr_no_text_detail": return "未识别到像 SN 的文字，请让 SN 标签更清晰、减少反光后重拍。";
            case "ocr_auto_no_text": return "自动识别没读到 SN，请对准标签后重试或点中间拍照键";
            case "ocr_choose_title": return "选择识别到的 SN";
            case "choose_grade": return "请选择结果";
            case "cancel": return "取消";
            case "sn_required": return "SN 不能为空";
            case "duplicate_sn": return "重复 SN: ";
            case "no_photo_needed": return "没有需要拍的照片";
            case "photo_no_file": return "拍照没有返回文件";
            case "photo_full_file_missing": return "系统相机没有保存原图，请重拍或更换系统相机。";
            case "photo_notice": return "拍照提示";
            case "photo_slot_transition": return "%1$s已拍完，开始拍%2$s。";
            case "choose_photo_slot": return "选择照片框";
            case "photo_save_failed": return "照片保存失败";
            case "no_sn": return "还没有 SN";
            case "payload_failed": return "Payload 生成失败";
            case "checking_steps": return "开始检查配置的前置记录...";
            case "previous_steps_creating": return "正在执行面板配置的前置记录流程...";
            case "previous_steps_created": return "配置的前置记录流程已完成。";
            case "previous_step_recipe": return "前置记录配方";
            case "workflow_artifacts": return "流程附件";
            case "capture_workflow_artifact": return "拍摄下一项流程附件";
            case "workflow_artifacts_done": return "所需流程附件已完成";
            case "workflow_artifacts_required": return "请先完成面板配置的必需流程附件。";
            case "workflow_artifact_missing": return "缺少流程附件来源: ";
            case "check_done": return "检查完成";
            case "steps_ok": return "当前批次的前置记录检查通过。";
            case "steps_missing_title": return "前置记录缺失";
            case "cannot_submit": return "还不能提交";
            case "scan_precheck_missing_detail": return "配置的前置记录未找到或编号有误，请重试。";
            case "scan_precheck_retry_title": return "扫码未找到";
            case "scan_precheck_retry_progress": return "未找到配置的前置记录（第 %1$d/%2$d 次），请重新扫码。";
            case "scan_precheck_blocked": return "未找到配置的前置记录，已按面板策略阻止继续。";
            case "scan_precheck_need_run_photo": return "配置的前置记录不存在，请完成面板要求的附件后重试。";
            case "scan_precheck_failed": return "前置记录即时检查失败: ";
            case "done": return "完成";
            case "dry_run_done": return "Payload 已生成，未提交。";
            case "submit_done": return "批次提交完成。";
            case "submit_done_queue_cleared": return "批次提交完成，设备队列已清空。";
            case "submit_done_check_print": return "提示：标签由已配置的打印服务异步处理，请点『打印对账』确认状态并补打失败项。";
            case "submit_aborted_consecutive": return "连续多台失败，已中止本批，请检查网络/账号后重试。";
            case "submit_cancelled_printer_offline": return "已取消提交（打印机未就绪）。";
            case "print_reconcile_title": return "打印对账";
            case "print_reconcile_open": return "打印对账/补打";
            case "auto_reprint_button": return "自动补打失败项";
            case "auto_retry_running": return "正在自动补打失败项…";
            case "print_queue_title": return "🖨️ 打印队列（全部）";
            case "print_queue_loading": return "正在拉取打印队列…";
            case "print_queue_failed": return "拉取打印队列失败：";
            case "print_reconcile_loading": return "正在拉取打印记录…";
            case "confirming_print": return "确认打印";
            case "final_print_recheck_wait": return "仍有打印任务未查到，后台等待 %1$d 毫秒后再查…";
            case "final_print_recheck": return "批次提交完成，立即复查打印";
            case "final_print_recheck_after_wait": return "等待后最终复查打印";
            case "inline_reprint_log": return "打印失败，补打第";
            case "inline_reprint_gaveup": return "补打 %1$d 次后仍失败，已记录上报";
            case "inline_reprint_uncertain": return "补打请求结果不确定，已停止自动补打；请人工核对标签和后台状态。";
            case "inline_print_deferred": return "提交成功，暂未查到打印任务；先继续，批次结束后复查";
            case "inline_print_no_job": return "提交成功，但未查到打印任务（未确认出标签，可能离线或延迟）";
            case "inline_print_late_confirmed": return "批次结束复查：已确认打印成功";
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
            case "print_status_unknown": return "未知状态（禁止补打）";
            case "print_status_missing": return "未出单";
            case "print_count_missing": return "未出 ";
            case "print_missing_hint": return "本轮已提交，但查无打印任务——很可能没出标签。请在打印机恢复后重新出单，或先人工补、后台处理。";
            case "reconcile_go_cloud": return "↻ 远端核实";
            case "reconcile_back_local": return "📋 本地台账";
            case "reconcile_mode_local": return "本地台账 · 提交时记录，离线可看";
            case "reconcile_mode_cloud": return "远端核实 · 实时查打印状态，可补打";
            case "reconcile_verifying": return "远端核实中…";
            case "reconcile_no_rounds": return "配置的保留期内没有提交记录";
            case "round_word": return "轮次 ";
            case "round_submitted": return "提交 ";
            case "round_labeled": return "出标签 ";
            case "ledger_submit_failed": return "提交失败";
            case "ledger_printed_ok": return "已出标签";
            case "ledger_printed_unconfirmed": return "未确认出标签";
            case "ledger_labeled_collapsed": return "台已出标签";
            case "print_created_at": return "时间：";
            case "print_retry_count": return "重试次数：";
            case "printer_label": return "打印服务：";
            case "printer_online": return "在线";
            case "printer_offline": return "离线（提交后可能不出标签，会丢单）";
            case "printer_warn_title": return "打印机未就绪";
            case "printer_warn_msg": return "已配置的打印服务似乎不在线，现在提交可能不会出标签。建议先处理好打印机再提交。";
            case "printer_warn_proceed": return "仍然提交";
            case "printer_warn_fix": return "先去处理";
            case "printer_check_failed": return "打印机状态检查失败：";
            case "reprint": return "补打";
            case "reprint_sending": return "正在补打…";
            case "reprint_hint": return "已打印成功；若标签丢失或损坏，点这里可补打。";
            case "reprint_done": return "已发送补打指令";
            case "reprint_failed": return "补打失败：";
            case "reprint_result_uncertain": return "补打请求已发出但结果不确定，请先人工核对标签和后台状态，不要直接再次补打。";
            case "reprint_confirm_title": return "确认补打";
            case "reprint_confirm_message": return "仅当标签确定未打印时继续，重复补打可能产生多张标签。";
            case "reprint_not_allowed": return "任务不是明确失败状态，或当前表单未启用手动补打。";
            case "print_reconcile_binding_changed": return "表单、面板、登录或打印策略已变化，本次打印操作已安全停止。";
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
            case "duplicate_found": return "有至少 %1$d 天的历史记录。";
            case "duplicate_found_calendar_months": return "有至少 %1$d 个日历月的历史记录。";
            case "duplicate_found_recent": return "有未达到 %1$d 天的历史记录。";
            case "duplicate_found_recent_calendar_months": return "有未达到 %1$d 个日历月的历史记录。";
            case "duplicate_found_date_unavailable": return "存在重复记录，但其时间无法按面板配置解析。";
            case "duplicate_skipped": return "已选择不提交，继续下一台。";
            case "duplicate_continue_log": return "已确认继续提交。";
            case "duplicate_return_title": return "重复 SN ";
            case "duplicate_skip_button": return "不提交这台";
            case "duplicate_continue_button": return "继续提交";
            case "duplicate_return_sn": return "SN: ";
            case "duplicate_return_count": return "已有记录数: ";
            case "duplicate_return_type": return "本次为: ";
            case "duplicate_return_last_date": return "最近录入时间: ";
            case "duplicate_return_question": return "是否继续提交这台？";
            case "submit_attempt": return "提交 ";
            case "submitted": return "已提交。";
            case "missing_already_notified": return "缺料已提示过，本次自动移除并重试: ";
            case "missing_final_already_notified": return "缺料已提示过，最后一次响应仍缺少: ";
            case "submit_retry_failed": return "重试后仍提交失败: ";
            case "network_retry_log_prefix": return "网络异常，自动重试 ";
            case "network_retrying_status": return "网络异常，正在自动重试...";
            case "dns_warning_header": return "以下设备提交时遇到DNS解析失败，可能没出单，请去后端核对：";
            case "checking_steps_for": return "检查前置记录: ";
            case "ok": return "通过";
            case "failed": return "失败";
            case "steps_ok_short": return "前置记录检查通过。";
            case "steps_missing_detail": return "配置的前置记录不存在或编号录入错误。接口返回:";
            case "sn_correction_try": return "前置记录未找到，正在尝试面板配置的编号纠正...";
            case "sn_correction_applied": return "已按面板策略纠正编号。";
            case "sn_correction_fast_timeout": return "快速纠正未命中，提示重试。";
            case "sn_correction_blocked": return "发现可纠正的编号，但面板策略禁止应用。";
            case "sn_correction_declined": return "操作员未应用编号纠正。";
            case "sn_correction_confirm_title": return "确认编号纠正";
            case "sn_correction_confirm_message": return "已找到对应的前置记录，是否使用面板配置的纠正结果？";
            case "sn_correction_confirm_apply": return "使用纠正结果";
            case "sn_correction_confirm_cancel": return "保持原编号";
            case "sn_case_aligned": return "已按面板策略匹配已有记录的大小写。";
            case "duplicate_check_failed": return "重复 SN 检查失败: ";
            case "duplicate_date_unavailable": return "重复记录缺少可解析的时间，已按面板安全策略阻止提交。";
            case "duplicate_blocked": return "面板策略已阻止重复提交。";
            case "print_unconfirmed_stop": return "打印未确认，已按面板策略停止后续提交。";
            case "workflow_printing_disabled": return "当前表单未在面板中启用并完整配置打印。";
            case "need_one_sn": return "至少需要一个 SN。";
            case "missing_front": return "缺少正面照片";
            case "missing_back": return "缺少反面照片";
            case "base_for": return "请录入 ";
            case "add_sn_first": return "先加入 SN";
            case "photos_done": return "照片已完成";
            case "next_photo": return "下一张 ";
            case "count": return "数量 ";
            case "front": return "正面";
            case "back": return "反面";
            case "grade": return "结果";
            case "grade_class": return "结果";
            case "precheck": return "前置检查";
            case "status": return "状态";
            case "delete_front": return "删除正面";
            case "delete_back": return "删除反面";
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
            case "missing_retry_exhausted_note": return "已达到提交次数上限；本次不会再次提交，记录仍保留在队列中。";
            case "missing_notice_once": return "本轮已弹出过缺料通知，后续缺料只记录日志。";
            case "draft_found": return "发现未提交数据";
            case "draft_found_detail": return "本机保存了未提交记录，是否继续录入？数量: ";
            case "continue_draft": return "继续录入";
            case "discard_draft": return "丢弃";
            case "draft_restore_failed": return "恢复草稿失败";
            case "draft_binding_locked_detail": return "该草稿与当前面板连接、目录版本或提交配置不一致。为防止传错位置，系统已禁止恢复和上传，并完整保留草稿；请恢复原面板配置，或确认后手动丢弃。";
            case "legacy_a_step_upgrade_blocked_detail": return "检测到旧版本尚未完成的上一工序拍照返回状态。为保留原照片路径并避免套用到错误工序，当前版本不会切换面板或安装更新；请先使用兼容旧版本完成或恢复该拍照流程。";
            case "draft_save_failed": return "草稿保存失败: ";
            case "materials_refreshing": return "正在刷新最新物料...";
            case "materials_refreshed": return "最新物料已刷新，数量: ";
            case "materials_refresh_failed": return "刷新最新物料失败: ";
            case "today_stats_title": return "今日结果统计";
            case "today_total": return "合计 ";
            case "grade_suffix": return "";
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
            case "queue_backup_delete": return "Delete queue backup";
            case "queue_backup_delete_confirm": return "Delete this backup only when it is no longer needed. This cannot be undone and may allow a downloaded Panel configuration to take effect at the next safe boundary. Delete it?";
            case "queue_backup_deleted": return "Queue backup deleted";
            case "queue_backup_delete_failed": return "Queue backup delete failed";
            case "queue_backup_delete_kept": return "The original backup was kept or restored. Please retry.";
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
            case "current_model": return "Current form";
            case "saved_model": return "Saved form";
            case "switch_model_to_continue": return "Switch to this form to continue entry.";
            case "photo_target_missing": return "Photo state was lost. The draft was kept; return to the form and retry.";
            case "photo_preview_failed": return "Photo preview failed. The original file is still saved and can be submitted.";
            case "diagnostic_log_title": return "Recent diagnostic log";
            case "settings_title": return "Auto Form";
            case "saved": return "Saved";
            case "panel_connection": return "Panel connection";
            case "panel_connection_invalid_tuple_detail": return "The Panel address and access key must both be filled or both be cleared. When changing the address manually, enter the new Panel's access key instead of reusing the key saved for the old address.";
            case "panel_connection_hint": return "Enter your form system's panel address and access key (e.g. https://your-panel.example.com). Saving connects automatically; leave both blank to stay unconfigured — you must configure it before logging in.";
            case "panel_base": return "Panel address";
            case "panel_base_hint": return "e.g. https://your-panel.example.com";
            case "catalog_key": return "Access key";
            case "catalog_key_hint": return "Access key (Bearer) provided by the panel";
            case "panel_save": return "Save";
            case "panel_current_api": return "Backend in effect: ";
            case "panel_unconfigured": return "Not configured";
            case "panel_syncing_short": return "Synchronizing";
            case "panel_pair_pending_short": return "Waiting for safe switch";
            case "panel_required_title": return "Configure the panel first";
            case "panel_required_detail": return "Enter the panel address and access key in Settings before logging in.";
            case "panel_syncing_detail": return "Synchronizing this Panel's configuration and forms. Login, form entry, capture, and submission stay disabled until both are verified.";
            case "panel_active_pair_pending_detail": return "A new Panel revision is ready, but unfinished records, drafts, or capture work still exist. Finish them with the active revision or explicitly clear them, then return to Settings to apply the update without mixing revisions.";
            case "sample_catalog_title": return "Sample configuration";
            case "sample_catalog_detail": return "The bundled sample is preview-only and cannot sign in or submit. Connect a panel and publish your catalog.";
            case "no_picker_profiles": return "The panel has not published any profile with pickerVisible=true.";
            case "panel_missing_config": return "Panel configuration is incomplete. Missing: ";
            case "profile_policy_migration_required": return "This form still uses the legacy policy format. Migrate its runtime policies in the Panel before submitting.";
            case "profile_workflow_missing": return "This form has no workflow configuration. Submission is blocked until the panel publishes one.";
            case "workflow_previous_steps_disabled": return "Previous-step checks are disabled for this form.";
            case "panel_connecting": return "Connecting to the panel…";
            case "panel_connected": return "Panel connected";
            case "panel_connect_failed": return "Panel connection failed. Check the address and access key.";
            case "download_pair_title": return "Connect the download-page Panel";
            case "download_pair_confirm_new": return "The App will redeem a one-time ticket from the Panel below and save the connection. Verify the domain before continuing:";
            case "download_pair_confirm_replace": return "The App will update or switch to the Panel below. Success signs out the current account and clears the old Panel cache. Verify the domain before continuing:";
            case "download_pair_connect": return "Confirm and connect";
            case "download_pair_redeeming": return "Securely retrieving the Panel connection…";
            case "download_pair_busy": return "A form, photo, draft, or uncertain operation is still active. Finish it, then open the connection again from the download page.";
            case "download_pair_invalid_title": return "Invalid connection link";
            case "download_pair_invalid_detail": return "This link is invalid, expired, or was not made for this App. The existing Panel connection was not changed.";
            case "download_pair_failed_title": return "One-tap connection not completed";
            case "download_pair_expired": return "The one-time ticket expired. Generate a new one on the download page. The existing Panel connection was not changed.";
            case "download_pair_failed": return "This Panel has not enabled the endpoint yet, or this redemption failed. Retry from the download page; the existing Panel connection was not changed.";
            case "download_pair_connection_changed": return "The Panel connection changed during redemption, so this result was discarded. Verify the current connection and generate a new ticket.";
            case "download_pair_in_progress": return "A Panel connection is already being processed. Wait for it to finish before trying again.";
            case "download_pair_obscured": return "Another window is covering this confirmation. Close overlays, then verify the Panel domain again.";
            case "notify_sent": return "Notification sent";
            case "notify_failed": return "Notification failed: ";
            case "notify_disabled": return "Panel notification endpoint is not configured; notification skipped.";
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
            case "scan_sn": return "Scan/read SN";
            case "ocr_sn": return "Photo OCR";
            case "add": return "Add";
            case "scan_base": return "Scan/read secondary identifier";
            case "ocr_base": return "Photo OCR";
            case "match": return "Match";
            case "photos": return "Photos";
            case "take_next_photo": return "Take next photo";
            case "choose_gallery_photo": return "Add from gallery";
            case "gallery_missing_title": return "Gallery unavailable";
            case "gallery_missing_detail": return "No image picker is available on this device.";
            case "delete_photo": return "Delete";
            case "go_back": return "Back";
            case "alternate_entry_subtitle": return "Choose a form, enter one identifier, capture photos, and submit. The Panel owns every target and field.";
            case "alternate_entry_clear_serial": return "Clear";
            case "alternate_entry_serial_empty": return "No identifier entered";
            case "alternate_entry_photo": return "Attachment photos";
            case "alternate_entry_add_photo": return "Add photo";
            case "alternate_entry_no_photo": return "No photos yet";
            case "alternate_entry_photo_count": return "Photo count: ";
            case "alternate_entry_photo_item": return "Photo ";
            case "alternate_entry_photo_limit": return "The Panel-configured photo limit has been reached.";
            case "alternate_entry_submit": return "Submit this record";
            case "alternate_entry_done": return "Submission complete";
            case "alternate_entry_invalid": return "The alternate-entry configuration is invalid, so opening or submission was blocked. Correct it in Panel.";
            case "alternate_entry_pending_title": return "Unsubmitted content remains";
            case "alternate_entry_pending_detail": return "Submit or clear the identifier and photos in the original alternate entry before switching entries.";
            case "alternate_entry_draft_locked_detail": return "This device has an unfinished draft bound to its original Panel, account, and target. To prevent a wrong submission, it will not be reinterpreted under the current configuration. Restore the original configuration or explicitly discard the local draft.";
            case "alternate_entry_panel_change_discard_detail": return "Changing Panel will discard the current alternate-entry identifier and photos. Continue only after confirming they are no longer needed.";
            case "alternate_entry_result_uncertain_title": return "Submission result needs confirmation";
            case "alternate_entry_result_uncertain_detail": return "The request was sent, but the device did not receive a provable success or failure result. To prevent duplicates or a wrong target, this record is locked and cannot be resubmitted or moved to another Panel until the original target is confirmed exactly.";
            case "upload_result_uncertain_title": return "Upload status needs confirmation";
            case "upload_result_uncertain_detail": return "The device started uploading images but did not reach a verifiable local terminal state. To prevent duplicate uploads or a wrong destination, the record, Panel switch, and app update are locked until the original backend is checked; do not resubmit it directly.";
            case "previous_step_result_uncertain_detail": return "The previous-step request was sent, but its result cannot be proven. To prevent a duplicate, its draft, later submission, and Panel switch are locked until an operator checks the original target; do not resubmit it directly.";
            case "alternate_entry_storage_locked_detail": return "The local submission safety record cannot be read or cleared, so the app cannot prove that another submission is safe and has not sent a new request. Preserve the device data and diagnostics and contact support.";
            case "alternate_entry_completed_cleanup_title": return "Previous submission confirmed";
            case "alternate_entry_completed_cleanup_detail": return "The backend confirmed the previous submission, but its local draft was not cleaned up safely. Do not submit it again; clean up this already-submitted local copy to continue.";
            case "alternate_entry_cleanup_local": return "Clean local copy";
            case "alternate_entry_cleanup_failed": return "Local cleanup failed and the confirmed-success record remains locked. Do not resubmit; contact support.";
            case "alternate_entry_discard_failed": return "The draft could not be removed safely. Its photos were kept and the configuration was not changed. Retry or contact support.";
            case "alternate_entry_queue_save_failed": return "The main-form queue could not be saved safely, so the alternate entry was not opened.";
            case "alternate_entry_async_pending": return "The previous scan or photo has not finished. To rescan, tap Scan again and confirm cancellation of the previous scan.";
            case "alternate_entry_cancel_scan_title": return "Cancel the previous scan?";
            case "alternate_entry_cancel_scan_detail": return "Only the unfinished scan reservation will be cancelled. The entered identifier, photos, and all submission or upload safety records are kept. Scanning reopens immediately after confirmation.";
            case "alternate_entry_cancel_scan_action": return "Cancel and rescan";
            case "alternate_entry_scan_cancelled": return "The unfinished previous scan was cancelled.";
            case "submit": return "Submit";
            case "preview_payload": return "Preview Payload";
            case "check_steps": return "Check previous records";
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
            case "scan_not_sn_title": return "This does not look like an SN";
            case "scan_not_sn_detail": return "This form rejects numeric-only results. Scan again or use Photo OCR.";
            case "scan_result_invalid_title": return "Invalid scan result";
            case "scan_result_invalid_detail": return "The scanner did not return a verifiable result source. The value was rejected; scan again.";
            case "ocr_unavailable_title": return "Photo OCR unavailable";
            case "ocr_unavailable_detail": return "The user info API did not return an OCR URL. Check account permission or recognizeTextUrl setup.";
            case "ocr_url_refreshing": return "Syncing photo OCR URL...";
            case "ocr_running": return "Recognizing photo text...";
            case "ocr_failed": return "Photo OCR failed";
            case "ocr_no_text_title": return "No SN found";
            case "ocr_no_text_detail": return "No SN-like text was recognized. Retake the photo with a clearer label and less glare.";
            case "ocr_auto_no_text": return "Auto OCR did not read an SN. Align the label and try again, or tap the shutter.";
            case "ocr_choose_title": return "Choose recognized SN";
            case "choose_grade": return "Choose a result";
            case "cancel": return "Cancel";
            case "sn_required": return "SN is required";
            case "duplicate_sn": return "Duplicate SN: ";
            case "no_photo_needed": return "No photo needed";
            case "photo_no_file": return "Camera returned no file";
            case "photo_full_file_missing": return "The camera did not save the full-size photo. Retake it or switch camera apps.";
            case "photo_notice": return "Photo notice";
            case "photo_slot_transition": return "%1$s is complete. Start %2$s.";
            case "choose_photo_slot": return "Choose a photo box";
            case "photo_save_failed": return "Photo save failed";
            case "no_sn": return "No SN yet";
            case "payload_failed": return "Payload failed";
            case "checking_steps": return "Checking configured previous records...";
            case "previous_steps_creating": return "Running the panel-configured previous-record workflow...";
            case "previous_steps_created": return "Configured previous-record workflow completed.";
            case "previous_step_recipe": return "previous-record recipe";
            case "workflow_artifacts": return "Workflow attachments";
            case "capture_workflow_artifact": return "Capture next workflow attachment";
            case "workflow_artifacts_done": return "Required workflow attachments complete";
            case "workflow_artifacts_required": return "Complete the panel-required workflow attachments first.";
            case "workflow_artifact_missing": return "Missing workflow attachment source: ";
            case "check_done": return "Check complete";
            case "steps_ok": return "Configured previous records are present.";
            case "steps_missing_title": return "Previous records missing";
            case "cannot_submit": return "Cannot submit yet";
            case "scan_precheck_missing_detail": return "Configured previous records are missing or the identifier is incorrect. Retry.";
            case "scan_precheck_retry_title": return "SN not found";
            case "scan_precheck_retry_progress": return "Configured previous records were not found (attempt %1$d/%2$d). Scan again.";
            case "scan_precheck_blocked": return "Configured previous records were not found. The Panel policy blocked continuation.";
            case "scan_precheck_need_run_photo": return "Configured previous records are missing. Complete the panel-required attachments and retry.";
            case "scan_precheck_failed": return "Immediate first-two-step check failed: ";
            case "done": return "Done";
            case "dry_run_done": return "Payload generated, not submitted.";
            case "submit_done": return "Batch submitted.";
            case "submit_done_queue_cleared": return "Batch submitted. Queue cleared.";
            case "submit_done_check_print": return "Labels are handled asynchronously by the configured print service. Tap 'Print check' to verify and retry failures.";
            case "submit_aborted_consecutive": return "Aborted this batch after repeated failures. Check network/login and try again.";
            case "submit_cancelled_printer_offline": return "Submit cancelled (printer not ready).";
            case "print_reconcile_title": return "Print check";
            case "print_reconcile_open": return "Print check / Reprint";
            case "auto_reprint_button": return "Auto-reprint failed jobs";
            case "auto_retry_running": return "Auto-reprinting failed labels…";
            case "print_queue_title": return "🖨️ Print queue (all)";
            case "print_queue_loading": return "Loading print queue…";
            case "print_queue_failed": return "Failed to load print queue: ";
            case "print_reconcile_loading": return "Loading print jobs…";
            case "confirming_print": return "Confirming print";
            case "final_print_recheck_wait": return "Print jobs are still missing; waiting %1$d ms before checking again…";
            case "final_print_recheck": return "Batch submitted; checking prints now";
            case "final_print_recheck_after_wait": return "Final print check after waiting";
            case "inline_reprint_log": return "print failed, reprint #";
            case "inline_reprint_gaveup": return "still failed after %1$d reprints; logged";
            case "inline_reprint_uncertain": return "The reprint result is uncertain. Automatic reprinting stopped; verify the label and backend manually.";
            case "inline_print_deferred": return "submitted OK, print job not visible yet; continuing and rechecking after the batch";
            case "inline_print_no_job": return "submitted OK, but no print job found (label unconfirmed — offline or delayed)";
            case "inline_print_late_confirmed": return "final batch check: print confirmed";
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
            case "print_status_unknown": return "Unknown status (reprint disabled)";
            case "print_status_missing": return "Not printed";
            case "print_count_missing": return "Missing ";
            case "print_missing_hint": return "Submitted this round, but no print job exists — the label almost certainly didn't print. Reprint once the printer is back, or handle it manually / in the backend.";
            case "reconcile_go_cloud": return "↻ Verify remotely";
            case "reconcile_back_local": return "📋 Local ledger";
            case "reconcile_mode_local": return "Local ledger · recorded at submit, works offline";
            case "reconcile_mode_cloud": return "Remote verify · live print status, reprint enabled";
            case "reconcile_verifying": return "Verifying remotely…";
            case "reconcile_no_rounds": return "No submit rounds in the configured retention window";
            case "round_word": return "Round ";
            case "round_submitted": return "Submitted ";
            case "round_labeled": return "Labeled ";
            case "ledger_submit_failed": return "Submit failed";
            case "ledger_printed_ok": return "Labeled";
            case "ledger_printed_unconfirmed": return "Label unconfirmed";
            case "ledger_labeled_collapsed": return "labeled";
            case "print_created_at": return "Time: ";
            case "print_retry_count": return "Retries: ";
            case "printer_label": return "Print service: ";
            case "printer_online": return "online";
            case "printer_offline": return "offline (labels may not print → lost units)";
            case "printer_warn_title": return "Printer not ready";
            case "printer_warn_msg": return "The configured print service seems offline. Submitting now may not print labels. Fixing it first is recommended.";
            case "printer_warn_proceed": return "Submit anyway";
            case "printer_warn_fix": return "Fix first";
            case "printer_check_failed": return "Printer status check failed: ";
            case "reprint": return "Reprint";
            case "reprint_sending": return "Reprinting…";
            case "reprint_hint": return "Printed OK — if the label is lost or damaged, tap to reprint.";
            case "reprint_done": return "Reprint command sent";
            case "reprint_failed": return "Reprint failed: ";
            case "reprint_result_uncertain": return "The reprint request was sent but its result is uncertain. Verify the label and backend before trying again.";
            case "reprint_confirm_title": return "Confirm reprint";
            case "reprint_confirm_message": return "Continue only when the label definitely did not print; reprinting can create duplicate labels.";
            case "reprint_not_allowed": return "The job is not explicitly failed, or manual reprint is disabled for this form.";
            case "print_reconcile_binding_changed": return "The form, Panel, login, or print policy changed. This print operation stopped safely.";
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
            case "duplicate_found": return "has existing records at least %1$d days old.";
            case "duplicate_found_calendar_months": return "has existing records at least %1$d calendar months old.";
            case "duplicate_found_recent": return "has existing records less than %1$d days old.";
            case "duplicate_found_recent_calendar_months": return "has existing records less than %1$d calendar months old.";
            case "duplicate_found_date_unavailable": return "has a duplicate record whose timestamp could not be parsed with the Panel configuration.";
            case "duplicate_skipped": return "not submitted by choice; continuing with the next unit.";
            case "duplicate_continue_log": return "confirmed to continue submitting.";
            case "duplicate_return_title": return "Duplicate SN ";
            case "duplicate_skip_button": return "Do not submit this unit";
            case "duplicate_continue_button": return "Continue submit";
            case "duplicate_return_sn": return "SN: ";
            case "duplicate_return_count": return "Existing records: ";
            case "duplicate_return_type": return "This submit is: ";
            case "duplicate_return_last_date": return "Latest entry date: ";
            case "duplicate_return_question": return "Continue submitting this unit?";
            case "submit_attempt": return "Submit ";
            case "submitted": return "submitted.";
            case "missing_already_notified": return "Already notified for missing material, auto retrying: ";
            case "missing_final_already_notified": return "Already notified; the final response still reports missing material: ";
            case "submit_retry_failed": return "Submit failed after retries: ";
            case "network_retry_log_prefix": return "Network issue, auto retry ";
            case "network_retrying_status": return "Network issue. Retrying automatically...";
            case "dns_warning_header": return "These units hit DNS resolution errors during submit and may not have printed. Verify in the backend:";
            case "checking_steps_for": return "Checking steps: ";
            case "ok": return "OK";
            case "failed": return "Failed";
            case "steps_ok_short": return "previous-record check passed.";
            case "steps_missing_detail": return "configured previous records may be missing or the identifier may be incorrect. API:";
            case "sn_correction_try": return "previous records missing; trying the Panel-configured identifier corrections...";
            case "sn_correction_applied": return "Identifier corrected according to the Panel policy.";
            case "sn_correction_fast_timeout": return "Fast correction missed; prompting retry.";
            case "sn_correction_blocked": return "A correction matched, but the Panel policy forbids applying it.";
            case "sn_correction_declined": return "The operator did not apply the identifier correction.";
            case "sn_correction_confirm_title": return "Confirm identifier correction";
            case "sn_correction_confirm_message": return "A matching previous record was found. Apply the Panel-configured correction?";
            case "sn_correction_confirm_apply": return "Apply correction";
            case "sn_correction_confirm_cancel": return "Keep original";
            case "sn_case_aligned": return "Case matched to the existing record according to the Panel policy.";
            case "duplicate_check_failed": return "Duplicate check failed: ";
            case "duplicate_date_unavailable": return "The duplicate record has no parseable timestamp; submission was blocked by the Panel safety policy.";
            case "duplicate_blocked": return "The Panel policy blocked this duplicate submission.";
            case "print_unconfirmed_stop": return "Printing was not confirmed; later submissions were stopped by the Panel policy.";
            case "workflow_printing_disabled": return "Printing is not enabled and fully configured for this form in the Panel.";
            case "need_one_sn": return "At least one SN is required.";
            case "missing_front": return "missing front photo";
            case "missing_back": return "missing back photo";
            case "base_for": return "Enter base SN for ";
            case "add_sn_first": return "Add SN first";
            case "photos_done": return "Photos complete";
            case "next_photo": return "Next photo ";
            case "count": return "Count ";
            case "front": return "Front";
            case "back": return "Back";
            case "grade": return "Result";
            case "grade_class": return "Result";
            case "precheck": return "Precheck";
            case "status": return "Status";
            case "delete_front": return "Delete front";
            case "delete_back": return "Delete back";
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
            case "missing_retry_exhausted_note": return "The submit limit was reached. No further submit was made, and the record remains in the queue.";
            case "missing_notice_once": return "Missing-material popup already shown for this round; later shortages are logged only.";
            case "draft_found": return "Unsubmitted data found";
            case "draft_found_detail": return "Saved unsubmitted records were found on this device. Continue? Count: ";
            case "continue_draft": return "Continue";
            case "discard_draft": return "Discard";
            case "draft_restore_failed": return "Draft restore failed";
            case "draft_binding_locked_detail": return "This draft does not match the current Panel connection, catalog revision, or submission contract. To prevent a wrong destination, restore and upload are blocked and the draft is preserved. Restore the original Panel configuration or discard it explicitly.";
            case "legacy_a_step_upgrade_blocked_detail": return "An unfinished previous-step camera return from an older app version was found. To preserve its original photo path and avoid assigning it to the wrong step, this version will not switch Panels or install another update. Finish or recover that camera flow with a compatible older version first.";
            case "draft_save_failed": return "Draft save failed: ";
            case "materials_refreshing": return "Refreshing latest materials...";
            case "materials_refreshed": return "Latest materials refreshed, count: ";
            case "materials_refresh_failed": return "Latest material refresh failed: ";
            case "today_stats_title": return "Today's results";
            case "today_total": return "Total ";
            case "grade_suffix": return "";
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
            case "queue_backup_delete": return "Eliminar copia de la cola";
            case "queue_backup_delete_confirm": return "Elimine esta copia solo cuando ya no sea necesaria. No se puede deshacer y puede permitir que una configuración del Panel descargada se active en el siguiente límite seguro. ¿Eliminarla?";
            case "queue_backup_deleted": return "Copia de la cola eliminada";
            case "queue_backup_delete_failed": return "No se pudo eliminar la copia de la cola";
            case "queue_backup_delete_kept": return "La copia original se conservó o restauró. Inténtelo de nuevo.";
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
            case "current_model": return "Formulario actual";
            case "saved_model": return "Formulario guardado";
            case "switch_model_to_continue": return "Cambie a este formulario para continuar.";
            case "photo_target_missing": return "Se perdió el estado de la foto. El borrador se conservó; vuelva al formulario e inténtelo de nuevo.";
            case "photo_preview_failed": return "No se pudo mostrar la vista previa de la foto. El archivo original sigue guardado y se puede enviar.";
            case "diagnostic_log_title": return "Registro de diagnóstico reciente";
            case "settings_title": return "Formulario automático";
            case "saved": return "Guardado";
            case "panel_connection": return "Conexión del panel";
            case "panel_connection_invalid_tuple_detail": return "La dirección del Panel y la clave de acceso deben completarse ambas o dejarse ambas vacías. Al cambiar la dirección manualmente, introduzca la clave del nuevo Panel en vez de reutilizar la guardada para la dirección anterior.";
            case "panel_connection_hint": return "Introduzca la dirección del panel y la clave de acceso de su sistema de formularios (p. ej. https://your-panel.example.com). Al guardar se conecta automáticamente; deje ambos vacíos para no configurarlo: debe configurarlo antes de iniciar sesión.";
            case "panel_base": return "Dirección del panel";
            case "panel_base_hint": return "p. ej. https://your-panel.example.com";
            case "catalog_key": return "Clave de acceso";
            case "catalog_key_hint": return "Clave de acceso (Bearer) proporcionada por el panel";
            case "panel_save": return "Guardar";
            case "panel_current_api": return "Backend en vigor: ";
            case "panel_unconfigured": return "Sin configurar";
            case "panel_syncing_short": return "Sincronizando";
            case "panel_pair_pending_short": return "Esperando cambio seguro";
            case "panel_required_title": return "Configure el panel primero";
            case "panel_required_detail": return "Introduzca la dirección del panel y la clave de acceso en Ajustes antes de iniciar sesión.";
            case "panel_syncing_detail": return "Se están sincronizando la configuración y los formularios de este panel. El inicio de sesión, el formulario, la captura y el envío permanecen bloqueados hasta verificar ambos.";
            case "panel_active_pair_pending_detail": return "Hay una nueva revisión del panel lista, pero aún existen registros, borradores o capturas sin terminar. Complételos con la revisión activa o elimínelos explícitamente y vuelva a Ajustes para aplicar la actualización sin mezclar revisiones.";
            case "sample_catalog_title": return "Configuración de ejemplo";
            case "sample_catalog_detail": return "El ejemplo incluido es solo para vista previa; no permite iniciar sesión ni enviar. Conecte un panel y publique su catálogo.";
            case "no_picker_profiles": return "El panel no publicó ningún perfil con pickerVisible=true.";
            case "panel_missing_config": return "La configuración del panel está incompleta. Falta: ";
            case "profile_policy_migration_required": return "Este formulario usa el formato de politica anterior. Migre sus politicas en el Panel antes de enviarlo.";
            case "profile_workflow_missing": return "Este formulario no tiene configuración workflow. El envío está bloqueado hasta publicarla en el panel.";
            case "workflow_previous_steps_disabled": return "La comprobación de pasos previos está desactivada para este formulario.";
            case "panel_connecting": return "Conectando con el panel…";
            case "panel_connected": return "Panel conectado";
            case "panel_connect_failed": return "Error de conexión del panel. Verifique la dirección y la clave de acceso.";
            case "download_pair_title": return "Conectar el panel de la página de descarga";
            case "download_pair_confirm_new": return "La App canjeará un ticket de un solo uso del panel siguiente y guardará la conexión. Verifique el dominio antes de continuar:";
            case "download_pair_confirm_replace": return "La App actualizará o cambiará al panel siguiente. Al completarse, cerrará la sesión actual y limpiará la caché del panel anterior. Verifique el dominio:";
            case "download_pair_connect": return "Confirmar y conectar";
            case "download_pair_redeeming": return "Obteniendo de forma segura la conexión del panel…";
            case "download_pair_busy": return "Aún hay un formulario, una foto, un borrador o una operación incierta. Termínelos y vuelva a abrir la conexión desde la página de descarga.";
            case "download_pair_invalid_title": return "Enlace de conexión no válido";
            case "download_pair_invalid_detail": return "El enlace no es válido, ha caducado o no fue creado para esta App. La conexión actual no cambió.";
            case "download_pair_failed_title": return "No se completó la conexión";
            case "download_pair_expired": return "El ticket de un solo uso caducó. Genere otro en la página de descarga. La conexión actual no cambió.";
            case "download_pair_failed": return "Este panel aún no habilitó el endpoint o falló el canje. Reinténtelo desde la página de descarga; la conexión actual no cambió.";
            case "download_pair_connection_changed": return "La conexión del panel cambió durante el canje, por lo que se descartó el resultado. Verifique la conexión actual y genere otro ticket.";
            case "download_pair_in_progress": return "Ya se está procesando una conexión del panel. Espere a que termine antes de reintentarlo.";
            case "download_pair_obscured": return "Otra ventana cubre esta confirmación. Cierre las superposiciones y vuelva a verificar el dominio del panel.";
            case "notify_sent": return "Notificación enviada";
            case "notify_failed": return "Error al enviar la notificación: ";
            case "notify_disabled": return "El endpoint de notificación del panel no está configurado; se omite la notificación.";
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
            case "scan_sn": return "Escanear/leer SN";
            case "ocr_sn": return "OCR foto";
            case "add": return "Agregar";
            case "scan_base": return "Escanear/leer identificador secundario";
            case "ocr_base": return "OCR foto";
            case "match": return "Asignar";
            case "photos": return "Fotos";
            case "take_next_photo": return "Tomar siguiente foto";
            case "choose_gallery_photo": return "Agregar de galeria";
            case "gallery_missing_title": return "Galeria no disponible";
            case "gallery_missing_detail": return "No hay selector de imagenes disponible en este dispositivo.";
            case "delete_photo": return "Eliminar";
            case "go_back": return "Volver";
            case "alternate_entry_subtitle": return "Elija un formulario, introduzca un identificador, tome fotos y envíe. Panel define todos los destinos y campos.";
            case "alternate_entry_clear_serial": return "Borrar";
            case "alternate_entry_serial_empty": return "Sin identificador";
            case "alternate_entry_photo": return "Fotos adjuntas";
            case "alternate_entry_add_photo": return "Agregar foto";
            case "alternate_entry_no_photo": return "Aún no hay fotos";
            case "alternate_entry_photo_count": return "Cantidad de fotos: ";
            case "alternate_entry_photo_item": return "Foto ";
            case "alternate_entry_photo_limit": return "Se alcanzó el límite de fotos configurado en Panel.";
            case "alternate_entry_submit": return "Enviar este registro";
            case "alternate_entry_done": return "Envío completado";
            case "alternate_entry_invalid": return "La configuración de la entrada alternativa no es válida; se bloqueó la apertura o el envío. Corríjala en Panel.";
            case "alternate_entry_pending_title": return "Queda contenido sin enviar";
            case "alternate_entry_pending_detail": return "Envíe o borre el identificador y las fotos de la entrada original antes de cambiar.";
            case "alternate_entry_draft_locked_detail": return "Hay un borrador sin terminar vinculado al Panel, la cuenta y el destino originales. Para evitar un envío incorrecto, no se reinterpretará con la configuración actual. Restaure la configuración original o descarte explícitamente el borrador local.";
            case "alternate_entry_panel_change_discard_detail": return "Cambiar de Panel descartará el identificador y las fotos de la entrada alternativa actual. Continúe solo si confirma que ya no se necesitan.";
            case "alternate_entry_result_uncertain_title": return "El resultado del envío requiere confirmación";
            case "alternate_entry_result_uncertain_detail": return "La solicitud se envió, pero el dispositivo no recibió un resultado que demuestre éxito o fallo. Para evitar duplicados o un destino incorrecto, este registro queda bloqueado y no puede reenviarse ni moverse a otro Panel hasta confirmar exactamente el destino original.";
            case "upload_result_uncertain_title": return "El estado de carga requiere confirmación";
            case "upload_result_uncertain_detail": return "El dispositivo comenzó a cargar imágenes, pero no alcanzó un estado local final verificable. Para evitar cargas duplicadas o un destino incorrecto, el registro, el cambio de Panel y la actualización quedan bloqueados hasta comprobar el backend original; no lo reenvíe directamente.";
            case "previous_step_result_uncertain_detail": return "La solicitud del paso previo se envió, pero no se puede demostrar su resultado. Para evitar duplicados, el borrador, el envío posterior y el cambio de Panel quedan bloqueados hasta que un operador compruebe el destino original; no la reenvíe directamente.";
            case "alternate_entry_storage_locked_detail": return "No se puede leer o borrar el registro local de seguridad, por lo que la aplicación no puede demostrar que otro envío sea seguro y no envió una solicitud nueva. Conserve los datos y diagnósticos del dispositivo y contacte con soporte.";
            case "alternate_entry_completed_cleanup_title": return "Envío anterior confirmado";
            case "alternate_entry_completed_cleanup_detail": return "El backend confirmó el envío anterior, pero su borrador local no se limpió de forma segura. No vuelva a enviarlo; limpie esta copia local ya enviada para continuar.";
            case "alternate_entry_cleanup_local": return "Limpiar copia local";
            case "alternate_entry_cleanup_failed": return "Falló la limpieza local y el registro de éxito confirmado sigue bloqueado. No vuelva a enviarlo; contacte con soporte.";
            case "alternate_entry_discard_failed": return "No se pudo eliminar el borrador de forma segura. Se conservaron las fotos y no se cambió la configuración. Reintente o contacte con soporte.";
            case "alternate_entry_queue_save_failed": return "No se pudo guardar de forma segura la cola principal, por lo que no se abrió la entrada alternativa.";
            case "alternate_entry_async_pending": return "El escaneo o la foto anterior no ha terminado. Para volver a escanear, pulse Escanear y confirme la cancelación del escaneo anterior.";
            case "alternate_entry_cancel_scan_title": return "¿Cancelar el escaneo anterior?";
            case "alternate_entry_cancel_scan_detail": return "Solo se cancelará la reserva del escaneo sin terminar. Se conservarán el identificador, las fotos y todos los registros de seguridad de envío o carga. El escaneo se abrirá de nuevo tras confirmar.";
            case "alternate_entry_cancel_scan_action": return "Cancelar y escanear";
            case "alternate_entry_scan_cancelled": return "Se canceló el escaneo anterior sin terminar.";
            case "submit": return "Enviar";
            case "preview_payload": return "Vista previa del payload";
            case "check_steps": return "Revisar registros previos";
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
            case "scan_not_sn_title": return "No parece un SN";
            case "scan_not_sn_detail": return "Este formulario rechaza resultados solo numéricos. Escanee de nuevo o use OCR de foto.";
            case "scan_result_invalid_title": return "Resultado de escaneo no válido";
            case "scan_result_invalid_detail": return "El escáner no devolvió una fuente verificable. Se rechazó el valor; vuelva a escanear.";
            case "ocr_unavailable_title": return "OCR no disponible";
            case "ocr_unavailable_detail": return "La API de usuario no devolvio la URL de OCR. Revise permisos o recognizeTextUrl.";
            case "ocr_url_refreshing": return "Sincronizando URL de OCR...";
            case "ocr_running": return "Reconociendo texto de la foto...";
            case "ocr_failed": return "Fallo el OCR de foto";
            case "ocr_no_text_title": return "No se encontro SN";
            case "ocr_no_text_detail": return "No se reconocio texto parecido a SN. Repita la foto con la etiqueta mas clara y menos reflejo.";
            case "ocr_auto_no_text": return "El OCR automatico no leyo el SN. Alinee la etiqueta e intente de nuevo, o toque el disparador.";
            case "ocr_choose_title": return "Elegir SN reconocido";
            case "choose_grade": return "Elija un resultado";
            case "cancel": return "Cancelar";
            case "sn_required": return "Se requiere SN";
            case "duplicate_sn": return "SN duplicado: ";
            case "no_photo_needed": return "No se necesita foto";
            case "photo_no_file": return "La cámara no devolvió archivo";
            case "photo_full_file_missing": return "La cámara no guardó la foto completa. Repítala o cambie la app de cámara.";
            case "photo_notice": return "Aviso de foto";
            case "photo_slot_transition": return "%1$s completado. Empiece con %2$s.";
            case "choose_photo_slot": return "Elija un cuadro de foto";
            case "photo_save_failed": return "Error al guardar foto";
            case "no_sn": return "Aún no hay SN";
            case "payload_failed": return "Error al generar payload";
            case "checking_steps": return "Revisando registros previos configurados...";
            case "previous_steps_creating": return "Ejecutando el flujo de registros previos configurado en el panel...";
            case "previous_steps_created": return "Flujo de registros previos completado.";
            case "previous_step_recipe": return "receta de registro previo";
            case "workflow_artifacts": return "Adjuntos del flujo";
            case "capture_workflow_artifact": return "Capturar el siguiente adjunto";
            case "workflow_artifacts_done": return "Adjuntos requeridos completos";
            case "workflow_artifacts_required": return "Complete primero los adjuntos requeridos por el panel.";
            case "workflow_artifact_missing": return "Falta la fuente del adjunto: ";
            case "check_done": return "Revisión completa";
            case "steps_ok": return "Los registros previos configurados están presentes.";
            case "steps_missing_title": return "Faltan registros previos";
            case "cannot_submit": return "Aún no se puede enviar";
            case "scan_precheck_missing_detail": return "Faltan registros previos configurados o el identificador es incorrecto. Intente de nuevo.";
            case "scan_precheck_retry_title": return "SN no encontrado";
            case "scan_precheck_retry_progress": return "No se encontraron registros previos (intento %1$d/%2$d). Escanee de nuevo.";
            case "scan_precheck_blocked": return "No se encontraron registros previos. La politica del Panel impidio continuar.";
            case "scan_precheck_need_run_photo": return "Faltan los registros previos configurados. Complete los adjuntos requeridos por el panel y vuelva a intentar.";
            case "scan_precheck_failed": return "Error de revisión inmediata de registros previos: ";
            case "done": return "Listo";
            case "dry_run_done": return "Payload generado, no enviado.";
            case "submit_done": return "Lote enviado.";
            case "submit_done_queue_cleared": return "Lote enviado. Cola vacía.";
            case "submit_done_check_print": return "El servicio de impresión configurado procesa las etiquetas de forma asíncrona. Verifique el estado y reintente los fallos.";
            case "submit_aborted_consecutive": return "Lote cancelado tras varios fallos seguidos. Revisa la red/sesión e inténtalo de nuevo.";
            case "submit_cancelled_printer_offline": return "Envío cancelado (impresora no lista).";
            case "print_reconcile_title": return "Verificar impresión";
            case "print_reconcile_open": return "Verificar / Reimprimir";
            case "auto_reprint_button": return "Auto-reimprimir trabajos fallidos";
            case "auto_retry_running": return "Reimprimiendo fallidas…";
            case "print_queue_title": return "🖨️ Cola de impresión (todo)";
            case "print_queue_loading": return "Cargando cola de impresión…";
            case "print_queue_failed": return "Error al cargar cola: ";
            case "print_reconcile_loading": return "Cargando trabajos de impresión…";
            case "confirming_print": return "Confirmando impresión";
            case "final_print_recheck_wait": return "Aun faltan trabajos; esperando %1$d ms antes de verificar de nuevo…";
            case "final_print_recheck": return "Lote enviado; verificando impresiones ahora";
            case "final_print_recheck_after_wait": return "Verificación final tras la espera";
            case "inline_reprint_log": return "impresión fallida, reimpresión #";
            case "inline_reprint_gaveup": return "sigue fallando tras %1$d reimpresiones; registrado";
            case "inline_reprint_uncertain": return "El resultado de la reimpresión es incierto. Se detuvo la reimpresión automática; verifique manualmente la etiqueta y el backend.";
            case "inline_print_deferred": return "envío correcto, trabajo aún no visible; se verificará al terminar el lote";
            case "inline_print_no_job": return "enviado OK, pero sin tarea de impresión (etiqueta no confirmada — sin conexión o retraso)";
            case "inline_print_late_confirmed": return "verificación final: impresión confirmada";
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
            case "print_status_unknown": return "Estado desconocido (reimpresion desactivada)";
            case "print_status_missing": return "Sin imprimir";
            case "print_count_missing": return "Faltan ";
            case "print_missing_hint": return "Enviado en esta ronda, pero no existe tarea de impresión — la etiqueta casi seguro no se imprimió. Reimprime cuando la impresora vuelva, o gestiónalo manualmente / en el backend.";
            case "reconcile_go_cloud": return "↻ Verificar remotamente";
            case "reconcile_back_local": return "📋 Registro local";
            case "reconcile_mode_local": return "Registro local · guardado al enviar, sin conexión";
            case "reconcile_mode_cloud": return "Verificación remota · estado real, reimpresión";
            case "reconcile_verifying": return "Verificando remotamente…";
            case "reconcile_no_rounds": return "No hay envíos en el periodo de retención configurado";
            case "round_word": return "Ronda ";
            case "round_submitted": return "Enviados ";
            case "round_labeled": return "Etiquetas ";
            case "ledger_submit_failed": return "Envío fallido";
            case "ledger_printed_ok": return "Impreso";
            case "ledger_printed_unconfirmed": return "Etiqueta sin confirmar";
            case "ledger_labeled_collapsed": return "impresas";
            case "print_created_at": return "Hora: ";
            case "print_retry_count": return "Reintentos: ";
            case "printer_label": return "Servicio de impresión: ";
            case "printer_online": return "en línea";
            case "printer_offline": return "sin conexión (puede que no imprima → unidades perdidas)";
            case "printer_warn_title": return "Impresora no lista";
            case "printer_warn_msg": return "El servicio de impresión configurado parece estar sin conexión. Enviar ahora puede no imprimir etiquetas; conviene repararlo primero.";
            case "printer_warn_proceed": return "Enviar de todos modos";
            case "printer_warn_fix": return "Arreglar primero";
            case "printer_check_failed": return "Error al comprobar la impresora: ";
            case "reprint": return "Reimprimir";
            case "reprint_sending": return "Reimprimiendo…";
            case "reprint_hint": return "Impreso OK — si la etiqueta se perdió o dañó, toca para reimprimir.";
            case "reprint_done": return "Orden de reimpresión enviada";
            case "reprint_failed": return "Reimpresión fallida: ";
            case "reprint_result_uncertain": return "Se envió la reimpresión pero el resultado es incierto. Verifique la etiqueta y el backend antes de intentarlo otra vez.";
            case "reprint_confirm_title": return "Confirmar reimpresion";
            case "reprint_confirm_message": return "Continua solo si la etiqueta no se imprimio; reimprimir puede crear etiquetas duplicadas.";
            case "reprint_not_allowed": return "El trabajo no esta en fallo explicito o la reimpresion manual esta desactivada.";
            case "print_reconcile_binding_changed": return "Cambió el formulario, Panel, inicio de sesión o política de impresión. La operación se detuvo de forma segura.";
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
            case "duplicate_found": return "tiene registros de al menos %1$d dias.";
            case "duplicate_found_calendar_months": return "tiene registros de al menos %1$d meses calendario.";
            case "duplicate_found_recent": return "tiene registros de menos de %1$d dias.";
            case "duplicate_found_recent_calendar_months": return "tiene registros de menos de %1$d meses calendario.";
            case "duplicate_found_date_unavailable": return "tiene un registro duplicado cuya fecha no pudo interpretarse con la configuracion del Panel.";
            case "duplicate_skipped": return "no enviado por decision; continua la siguiente unidad.";
            case "duplicate_continue_log": return "confirmado para continuar el envio.";
            case "duplicate_return_title": return "SN duplicado ";
            case "duplicate_skip_button": return "No enviar esta unidad";
            case "duplicate_continue_button": return "Continuar envio";
            case "duplicate_return_sn": return "SN: ";
            case "duplicate_return_count": return "Registros existentes: ";
            case "duplicate_return_type": return "Este envio es: ";
            case "duplicate_return_last_date": return "Ultima fecha: ";
            case "duplicate_return_question": return "Continuar enviando esta unidad?";
            case "submit_attempt": return "Enviar ";
            case "submitted": return "enviado.";
            case "missing_already_notified": return "Material faltante ya notificado; reintentando: ";
            case "missing_final_already_notified": return "Ya notificado; la respuesta final aún indica material faltante: ";
            case "submit_retry_failed": return "Error después de reintentos: ";
            case "network_retry_log_prefix": return "Error de red, reintento ";
            case "network_retrying_status": return "Error de red. Reintentando...";
            case "dns_warning_header": return "Estas unidades tuvieron errores DNS al enviar y pueden no haberse impreso. Verifique en el backend:";
            case "checking_steps_for": return "Revisando pasos: ";
            case "ok": return "OK";
            case "failed": return "Falló";
            case "steps_ok_short": return "revisión de registros previos correcta.";
            case "steps_missing_detail": return "pueden faltar registros previos configurados o el identificador puede ser incorrecto. API:";
            case "sn_correction_try": return "faltan registros previos; probando las correcciones configuradas en el Panel...";
            case "sn_correction_applied": return "Identificador corregido segun la politica del Panel.";
            case "sn_correction_fast_timeout": return "Correccion rapida sin resultado; reintente.";
            case "sn_correction_blocked": return "Se encontro una correccion, pero la politica del Panel impide aplicarla.";
            case "sn_correction_declined": return "El operador no aplico la correccion del identificador.";
            case "sn_correction_confirm_title": return "Confirmar correccion";
            case "sn_correction_confirm_message": return "Se encontro un registro previo. Aplicar la correccion configurada en el Panel?";
            case "sn_correction_confirm_apply": return "Aplicar correccion";
            case "sn_correction_confirm_cancel": return "Mantener original";
            case "sn_case_aligned": return "Mayusculas/minusculas ajustadas al registro existente segun la politica del Panel.";
            case "duplicate_check_failed": return "Error al revisar duplicado: ";
            case "duplicate_date_unavailable": return "El registro duplicado no tiene una fecha valida; la politica segura del Panel bloqueo el envio.";
            case "duplicate_blocked": return "La politica del Panel bloqueo este envio duplicado.";
            case "print_unconfirmed_stop": return "No se confirmo la impresion; la politica del Panel detuvo los envios siguientes.";
            case "workflow_printing_disabled": return "La impresion no esta habilitada y configurada por completo para este formulario en el Panel.";
            case "need_one_sn": return "Se requiere al menos un SN.";
            case "missing_front": return "falta foto frontal";
            case "missing_back": return "falta foto reversa";
            case "base_for": return "Ingrese base para ";
            case "add_sn_first": return "Agregue SN primero";
            case "photos_done": return "Fotos completas";
            case "next_photo": return "Siguiente foto ";
            case "count": return "Cantidad ";
            case "front": return "Frente";
            case "back": return "Reverso";
            case "grade": return "Resultado";
            case "grade_class": return "Resultado";
            case "precheck": return "Revisión";
            case "status": return "Estado";
            case "delete_front": return "Borrar frente";
            case "delete_back": return "Borrar reverso";
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
            case "missing_retry_exhausted_note": return "Se alcanzó el límite de envíos. No se volvió a enviar y el registro permanece en la cola.";
            case "missing_notice_once": return "La alerta de material faltante ya apareció en esta ronda; lo siguiente solo quedará en el registro.";
            case "draft_found": return "Datos sin enviar";
            case "draft_found_detail": return "Hay registros guardados sin enviar en este dispositivo. ¿Continuar? Cantidad: ";
            case "continue_draft": return "Continuar";
            case "discard_draft": return "Descartar";
            case "draft_restore_failed": return "Error al restaurar borrador";
            case "draft_binding_locked_detail": return "Este borrador no coincide con la conexión, revisión del catálogo o contrato de envío actuales del Panel. Para evitar un destino incorrecto, se bloquearon la restauración y la carga, y el borrador se conservó. Restaure la configuración original del Panel o descártelo explícitamente.";
            case "legacy_a_step_upgrade_blocked_detail": return "Se encontró un retorno de cámara de una operación previa sin terminar, creado por una versión anterior. Para conservar la ruta original de la foto y evitar asignarla al paso equivocado, esta versión no cambiará de Panel ni instalará otra actualización. Termine o recupere primero ese flujo con una versión anterior compatible.";
            case "draft_save_failed": return "Error al guardar borrador: ";
            case "materials_refreshing": return "Actualizando materiales...";
            case "materials_refreshed": return "Materiales actualizados, cantidad: ";
            case "materials_refresh_failed": return "Error al actualizar materiales: ";
            case "today_stats_title": return "Resultados de hoy";
            case "today_total": return "Total ";
            case "grade_suffix": return "";
            case "daily_stats_save_failed": return "Error al guardar estadística diaria: ";
            default: return en(key);
        }
    }

    private static List<String> extractOcrCandidates(Api api, JSONObject body) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (api == null || body == null) return new ArrayList<>(values);
        Object data = api.apiData(body);
        for (String path : api.endpoints.operations.ocr.resultPaths) {
            addOcrValues(values, BackendAdapter.valueAt(data, path));
        }
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
            JSONArray names = object.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                addOcrValues(values, object.opt(names.optString(i)));
            }
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
        if (isLikelyIdentifierCandidate(candidate)) values.add(candidate);
    }

    private static boolean isLikelyIdentifierCandidate(String value) {
        if (value == null || value.length() < 5 || value.length() > 80) return false;
        return Pattern.compile("[A-Z0-9]").matcher(value).find();
    }

    private static String conciseError(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 360 ? message.substring(0, 360) + "..." : message;
    }

    private static String normalize(String value) {
        return value == null
            ? ""
            : value.trim().replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
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
        String snSource = SnScanRules.SOURCE_ENTERED;
        String grade;
        String baseSn = "";
        String baseSnSource = SnScanRules.SOURCE_ENTERED;
        String frontPhoto = "";
        String backPhoto = "";
        final List<String> supplementalPhotos = new ArrayList<>();
        // Configured photo field -> captured local paths.
        final Map<String, List<String>> slotPhotos = new LinkedHashMap<>();
        final Map<String, String> workflowArtifacts = new LinkedHashMap<>();
        // v1 evidence compatibility copy: round-tripped under aStepPhotoPath. Current submission
        // reads only workflowArtifacts and only after an exact Panel-owned mapping.
        String legacyWorkflowArtifactPath = "";
        // Signed-v1 draft-only compatibility copy. It is deliberately absent from payload logic.
        boolean legacyDefective = false;
        // Additional configured identifier field -> value.
        final Map<String, String> pluginSns = new LinkedHashMap<>();
        String precheckStatus = "unchecked";
        boolean workflowArtifactRequired = false;
        String status = "pending";

        UnitRecord(int sequence, String sn, String grade) {
            this.sequence = sequence;
            this.sn = sn;
            this.grade = grade;
        }
    }

    private static class WorkflowArtifactTarget {
        final UnitRecord unit;
        final ProfileWorkflow.WorkflowArtifact artifact;

        WorkflowArtifactTarget(UnitRecord unit, ProfileWorkflow.WorkflowArtifact artifact) {
            this.unit = unit;
            this.artifact = artifact;
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

    static class Api {

        // A 5xx gateway/upstream error (502/503/504) that returned an HTML error page instead of JSON.
        // Tagged so the explicit profile-owned submission policy can classify it as transient. The
        // transport helper still executes each HTTP request exactly once.
        static final class TransientHttpException extends IOException {
            TransientHttpException(String message) { super(message); }
        }
        final String base;
        final String token;
        final String webFingerprint;
        // Optional web-client Origin/Referer, supplied from the panel config. Empty → header omitted.
        final String webOrigin;
        final String webReferer;
        // Complete backend contract injected from the panel. It contains no app-side path defaults.
        final BackendAdapter endpoints;
        final boolean remoteOperationsAllowed;
        final RemoteOperationGate remoteOperationGate;

        private interface RemoteOperationGate {
            boolean allowed();
        }

        private interface ApiCall<T> {
            T run() throws Exception;
        }

        private interface OperationStageGuard {
            void require(String phase) throws Exception;
        }

        private static void requireStage(OperationStageGuard guard, String phase)
                throws Exception {
            if (guard != null) guard.require(phase);
        }

        Api(String base, String token, String webFingerprint, String webOrigin, String webReferer,
            BackendAdapter endpoints, boolean remoteOperationsAllowed,
            RemoteOperationGate remoteOperationGate) {
            this.base = base.replaceAll("/+$", "");
            this.token = token == null ? "" : token;
            this.webFingerprint = webFingerprint == null ? "" : webFingerprint.trim();
            this.webOrigin = webOrigin == null ? "" : webOrigin.trim();
            this.webReferer = webReferer == null ? "" : webReferer.trim();
            this.endpoints = endpoints == null ? BackendAdapter.from(null) : endpoints;
            this.remoteOperationsAllowed = remoteOperationsAllowed;
            this.remoteOperationGate = remoteOperationGate;
        }

        Captcha getCaptcha(OperationStageGuard guard) throws Exception {
            requireStage(guard, "captcha request");
            JSONObject body = getJson(endpoints.requireEndpoint(BackendAdapter.ENDPOINT_CAPTCHA), "", true);
            requireStage(guard, "captcha response");
            if (!isAuthSuccess(body)) throw new IOException(apiErrorMessage(body));
            JSONObject data = authDataObject(body);
            if (data == null) throw new IOException("Captcha response has no data");
            Object client = BackendAdapter.valueAt(data, endpoints.fields.captchaClient);
            Object image = BackendAdapter.valueAt(data, endpoints.fields.captchaImage);
            return new Captcha(client == null ? "" : String.valueOf(client),
                image == null ? "" : String.valueOf(image));
        }

        LoginResult login(String account, String password, String captcha, String client,
                          OperationStageGuard guard) throws Exception {
            JSONObject form = endpoints.auth.loginBody(account, password, captcha, client);
            String verifyPath = endpoints.endpoint(BackendAdapter.ENDPOINT_LOGIN_VERIFY);
            if (!verifyPath.isEmpty()) {
                requireStage(guard, "login verify request");
                JSONObject verify = postConfigured(verifyPath, form, true);
                requireStage(guard, "login verify response");
                if (!isAuthSuccess(verify)) throw new IOException(apiErrorMessage(verify));
            }
            requireStage(guard, "login request");
            JSONObject body = postConfigured(
                endpoints.requireEndpoint(BackendAdapter.ENDPOINT_LOGIN), form, true);
            requireStage(guard, "login response");
            if (!isAuthSuccess(body)) throw new IOException(apiErrorMessage(body));
            JSONObject data = authDataObject(body);
            if (data == null) throw new IOException("Login response has no data");
            String token = firstNonEmpty(
                endpoints.auth.firstString(data, endpoints.auth.tokenFields),
                endpoints.auth.firstString(body, endpoints.auth.tokenFields));
            if (token.isEmpty()) throw new IOException("Login response has no token");
            String userName = firstNonEmpty(
                endpoints.auth.firstString(data, endpoints.auth.userNameFields), account);
            String recognizeTextUrl = endpoints.auth.firstString(
                data, endpoints.operations.ocr.userInfoUrlFields);
            if (recognizeTextUrl.isEmpty()) {
                UserProfile profile = new Api(base, token, webFingerprint, webOrigin, webReferer,
                    endpoints, remoteOperationsAllowed, remoteOperationGate)
                    .fetchUserInfo(guard);
                userName = firstNonEmpty(profile.userName, userName);
                recognizeTextUrl = profile.recognizeTextUrl;
            }
            requireStage(guard, "login result");
            return new LoginResult(token, userName, recognizeTextUrl);
        }

        UserProfile fetchUserInfo(OperationStageGuard guard) throws Exception {
            requireStage(guard, "user-info request");
            JSONObject body = getJson(endpoints.requireEndpoint(BackendAdapter.ENDPOINT_USER_INFO), "");
            requireStage(guard, "user-info response");
            if (!isAuthSuccess(body)) throw new IOException(apiErrorMessage(body));
            Object data = authData(body);
            return new UserProfile(endpoints.auth.firstString(data, endpoints.auth.userNameFields),
                endpoints.auth.firstString(data, endpoints.operations.ocr.userInfoUrlFields));
        }

        enum AuthState { VALID, INVALID, UNKNOWN }

        /**
         * Probe whether {@link #token} is still accepted by the backend instead of trusting a token
         * that may have been inherited from a peer app (or our local cache) and has since been
         * kicked/expired, so a peer and this app judge a kick the same way.
         *
         * <p>VALID = the configured user-info probe succeeded. INVALID = a clear auth rejection (HTTP 401/403 or an
         * auth-worded body) → caller should log out and prompt re-login. UNKNOWN = network/transport
         * blip → don't act on it. Deliberately NOT routed through getJson: that swallows 401/403 into
         * a generic IOException, losing the three-way distinction. Uses web headers (same session
         * identity as the rest of the app) so the probe never itself trips an "elsewhere" kick.
         */
        AuthState checkAuth(OperationStageGuard guard) {
            if (token.isEmpty()) return AuthState.INVALID;
            if (!operationsAllowedNow()) return AuthState.UNKNOWN;
            try {
                requireStage(guard, "auth probe request");
                URL url = absoluteUrl(endpoints.requireEndpoint(BackendAdapter.ENDPOINT_USER_INFO));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                addHeaders(conn, true, 15000, 20000);
                int status = conn.getResponseCode();
                requireStage(guard, "auth probe status");
                if (status == 401 || status == 403) return AuthState.INVALID;
                JSONObject body;
                try {
                    body = readJson(conn);
                    requireStage(guard, "auth probe response");
                } catch (BackendSessionErrors.SessionInvalidException invalid) {
                    return AuthState.INVALID;
                } catch (Exception parse) {
                    return AuthState.UNKNOWN;
                }
                if (isAuthSuccess(body)) return AuthState.VALID;
                Object proofCode = BackendAdapter.valueAt(body, endpoints.response.codeField);
                if (proofCode != null && endpoints.auth.sessionProofCodes.contains(
                        String.valueOf(proofCode).trim())) {
                    return AuthState.VALID;
                }
                String text = body == null ? ""
                    : endpoints.response.configuredMessage(body);
                if (BackendSessionErrors.isInvalidMessage(text, endpoints.sessionInvalidPolicy)) {
                    return AuthState.INVALID;
                }
                return AuthState.UNKNOWN;
            } catch (Exception e) {
                return AuthState.UNKNOWN;
            }
        }

        // ----- Optional asynchronous print adapter -----
        // Paths, response fields, accepted job types, and status values all come from backendAdapter.
        JSONObject printerState() throws Exception {
            return getJson(endpoints.requireEndpoint(BackendAdapter.ENDPOINT_PRINTER_STATE), "");
        }

        byte[] retryPrintPayload(long id) throws Exception {
            if (id <= 0L) throw new IllegalArgumentException("positive print job id is required");
            return endpoints.printing.retryPayload(id).toString()
                .getBytes(StandardCharsets.UTF_8);
        }

        JSONObject retryPrintExact(byte[] exactPayload) throws Exception {
            return postEndpointJsonExact(
                BackendAdapter.ENDPOINT_LABEL_RETRY, exactPayload);
        }

        JSONObject getJson(String path, String query) throws Exception {
            return getJson(path, query, true);
        }

        JSONObject getEndpointJson(String endpointName, String query) throws Exception {
            return getJson(endpoints.requireEndpoint(endpointName), query, true);
        }

        JSONObject getEndpointJson(String endpointName, String query, boolean webLoginClient,
                                     int connectTimeoutMs, int readTimeoutMs) throws Exception {
            return getJson(endpoints.requireEndpoint(endpointName), query, webLoginClient,
                connectTimeoutMs, readTimeoutMs);
        }

        JSONObject getJson(String path, String query, boolean webLoginClient) throws Exception {
            return getJson(path, query, webLoginClient, 30000, 120000);
        }

        JSONObject getJson(String path, String query, boolean webLoginClient, int connectTimeoutMs, int readTimeoutMs) throws Exception {
            return executeReadOnlyWithTransientRetry(() -> {
                URL url = absoluteUrl(path, query);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                addHeaders(conn, webLoginClient, connectTimeoutMs, readTimeoutMs);
                return readJson(conn);
            });
        }

        JSONObject postJson(String path, JSONObject payload) throws Exception {
            return executeOnce(() -> {
                URL url = absoluteUrl(path);
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

        JSONObject postEndpointJson(String endpointName, JSONObject payload) throws Exception {
            return postJson(endpoints.requireEndpoint(endpointName), payload);
        }

        JSONObject postEndpointJsonExact(String endpointName, byte[] exactPayload) throws Exception {
            if (exactPayload == null) throw new IllegalArgumentException("payload is required");
            return executeOnce(() -> {
                URL url = absoluteUrl(endpoints.requireEndpoint(endpointName));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                addHeaders(conn, true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = conn.getOutputStream()) {
                    // This is the same immutable byte array whose SHA-256 is stored in the journal.
                    output.write(exactPayload);
                }
                return readJson(conn);
            });
        }

        JSONObject postConfigured(String path, JSONObject payload, boolean webLoginClient) throws Exception {
            return "json".equals(endpoints.request.bodyEncoding)
                ? postJson(path, payload)
                : postForm(path, payload, webLoginClient);
        }

        JSONObject postForm(String path, JSONObject form) throws Exception {
            return postForm(path, form, true);
        }

        JSONObject postForm(String path, JSONObject form, boolean webLoginClient) throws Exception {
            return executeOnce(() -> {
                URL url = absoluteUrl(path);
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
            return executeOnce(() -> {
                String boundary = "----AutoFormKit" + System.currentTimeMillis();
                URL url = absoluteUrl(endpoints.requireEndpoint(BackendAdapter.ENDPOINT_UPLOAD_FILE));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                addHeaders(conn, true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream output = conn.getOutputStream(); InputStream input = new java.io.FileInputStream(file)) {
                    output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(("Content-Disposition: form-data; name=\""
                        + endpoints.operations.upload.multipartField + "\"; filename=\""
                        + uploadName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write("Content-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                    output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                JSONObject body = readJson(conn);
                Object result = endpoints.operations.upload.result(apiData(body));
                if (!isSuccess(body) || result == null || result == JSONObject.NULL) {
                    throw new IOException("Image upload failed: " + apiErrorMessage(body));
                }
                return String.valueOf(result);
            });
        }

        JSONObject recognizeText(String recognizeTextUrl, File file) throws Exception {
            return recognizeText(recognizeTextUrl, file, null);
        }

        JSONObject recognizeText(String recognizeTextUrl, File file,
                                 OperationStageGuard guard) throws Exception {
            return executeOnce(() -> {
                requireStage(guard, "OCR request");
                String boundary = "----AutoFormKit" + System.currentTimeMillis();
                URL url = absoluteUrl(recognizeTextUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                // The dynamic OCR URL is an uncredentialed capability endpoint. Never follow a
                // redirect with the photo body, and deliberately do not call addHeaders() here.
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream output = conn.getOutputStream(); InputStream input = new java.io.FileInputStream(file)) {
                    output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(("Content-Disposition: form-data; name=\""
                        + endpoints.operations.ocr.multipartField
                        + "\"; filename=\"sn-ocr.jpg\"\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write("Content-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                    output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                JSONObject body = readJson(conn);
                requireStage(guard, "OCR response");
                if (!isSuccess(body)) {
                    throw new IOException("OCR failed: " + apiErrorMessage(body));
                }
                return body;
            });
        }

        /**
         * Preserve the signed v1 transport experience for GET without extending it to any upload
         * or POST. Each attempt rechecks the live Panel/worker gate through {@link #executeOnce}; a
         * transient first failure waits once, then performs one final read-only attempt.
         */
        private <T> T executeReadOnlyWithTransientRetry(ApiCall<T> call) throws Exception {
            int completedAttempts = 0;
            while (true) {
                try {
                    completedAttempts++;
                    return executeOnce(call);
                } catch (Exception error) {
                    if (!ReadOnlyRetryRules.shouldRetry(
                            completedAttempts, isTransientApiNetworkError(error))) {
                        throw error;
                    }
                    try {
                        Thread.sleep(ReadOnlyRetryRules.RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }

        private <T> T executeOnce(ApiCall<T> call) throws Exception {
            if (!operationsAllowedNow()) {
                throw new IOException("Panel connection is not ready");
            }
            try {
                return call.run();
            } catch (Exception exc) {
                if (isDnsResolveError(exc)) {
                    DnsContext ctx = currentDnsContext.get();
                    if (ctx != null) ctx.activity.recordDnsAffected(ctx.unit, ctx.position, exc);
                }
                // No side-effect transport replay: POST and upload calls may be non-idempotent.
                // GET alone is wrapped by executeReadOnlyWithTransientRetry; every call still
                // passes this gate independently.
                throw exc;
            }
        }

        private boolean operationsAllowedNow() {
            if (!remoteOperationsAllowed) return false;
            try {
                return remoteOperationGate == null || remoteOperationGate.allowed();
            } catch (Exception ignored) {
                return false;
            }
        }

        static boolean isTransientApiNetworkError(Throwable exc) {
            // Submission state-machine failures are hard no-replay signals even if a future
            // wrapper attaches a timeout or socket exception as their cause.
            for (Throwable current = exc; current != null; current = current.getCause()) {
                if (current instanceof SubmissionOutcomeUncertainException
                        || current instanceof
                            PreviousStepSubmissionOutcomeUncertainException
                        || current instanceof PreviousStepLookupUnclassifiedException
                        || current instanceof SubmissionAcknowledgedRecoveryException
                        || current instanceof UploadReplayBarrierRetirementException
                        || current instanceof SubmissionTerminalRecoveryException
                        || current instanceof SubmissionJournalLockedException) {
                    return false;
                }
            }
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
            return BackendAdapter.resolveEndpointUrl(base, pathOrUrl);
        }

        URL absoluteUrl(String pathOrUrl, String query) throws IOException {
            return BackendAdapter.resolveEndpointUrl(base, pathOrUrl, query);
        }

        void addHeaders(HttpURLConnection conn) {
            addHeaders(conn, false);
        }

        void addHeaders(HttpURLConnection conn, boolean webLoginClient) {
            addHeaders(conn, webLoginClient, 30000, 120000);
        }

        void addHeaders(HttpURLConnection conn, boolean webLoginClient, int connectTimeoutMs, int readTimeoutMs) {
            // A configured endpoint is the complete credential destination. Following even a
            // same-origin redirect could replay a login body, submission, or photo without the
            // operation gate getting another chance to validate its captured realm.
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (webLoginClient) {
                // Origin/Referer identify the web front-end the session was established with. They are
                // optional and come from the panel config; when unset, the headers are simply omitted.
                if (!webOrigin.isEmpty()) conn.setRequestProperty("Origin", webOrigin);
                if (!webReferer.isEmpty()) conn.setRequestProperty("Referer", webReferer);
                if (!webFingerprint.isEmpty() && !endpoints.request.fingerprintHeader.isEmpty()) {
                    conn.setRequestProperty(endpoints.request.fingerprintHeader, webFingerprint);
                }
                if (!endpoints.request.webUserAgent.isEmpty()) {
                    conn.setRequestProperty("User-Agent", endpoints.request.webUserAgent);
                }
                if (!endpoints.request.webAcceptLanguage.isEmpty()) {
                    conn.setRequestProperty("Accept-Language", endpoints.request.webAcceptLanguage);
                }
            }
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", endpoints.request.authScheme + " " + token);
            }
        }

        JSONObject readJson(HttpURLConnection conn) throws Exception {
            int status = conn.getResponseCode();
            InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            HttpResponseStatusRules.Action statusAction = HttpResponseStatusRules.beforeJson(
                status,
                BackendSessionErrors.isInvalidHttpStatus(
                    status, endpoints.sessionInvalidPolicy),
                input != null);
            if (statusAction == HttpResponseStatusRules.Action.REDIRECT) {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
                throw new IOException("HTTP " + status + " redirect rejected");
            }
            if (input == null) {
                if (statusAction == HttpResponseStatusRules.Action.SESSION_INVALID) {
                    throw new BackendSessionErrors.SessionInvalidException("HTTP " + status);
                }
                if (statusAction == HttpResponseStatusRules.Action.TRANSIENT_GATEWAY) {
                    throw new TransientHttpException("HTTP " + status + " gateway error");
                }
                throw new IOException("HTTP " + status);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            String text = output.toString("UTF-8");
            String snippet = conciseText(text);
            if (statusAction == HttpResponseStatusRules.Action.SESSION_INVALID) {
                throw new BackendSessionErrors.SessionInvalidException(
                        "HTTP " + status + (snippet.isEmpty() ? "" : ": " + snippet));
            }
            // Classify gateway failures by status before JSON parsing. nginx may return HTML, while
            // another proxy may return JSON; both are the same transient 502/503/504 incident.
            if (statusAction == HttpResponseStatusRules.Action.TRANSIENT_GATEWAY) {
                throw new TransientHttpException(
                        "HTTP " + status + " gateway error: " + snippet);
            }
            try {
                JSONObject body = new JSONObject(text);
                if (!HttpResponseStatusRules.allowsConfiguredSuccess(status)
                        && isSuccess(body)) {
                    // Keep the status/body contradiction fail-closed. Structured HTTP errors are
                    // still returned below when they are non-success so Panel-owned rejection
                    // classifiers can distinguish a definite business rejection from uncertainty.
                    throw new IOException("HTTP " + status
                        + " conflicts with the configured success response");
                }
                if (!isSuccess(body) && BackendSessionErrors.isInvalidStructuredResponse(
                        body, endpoints.response, endpoints.sessionInvalidPolicy)) {
                    throw new BackendSessionErrors.SessionInvalidException(apiErrorMessage(body));
                }
                return body;
            } catch (JSONException exc) {
                throw new IOException("Response is not JSON: " + snippet);
            }
        }

        static boolean isTransientHttpStatus(int status) {
            return HttpResponseStatusRules.isTransientGateway(status);
        }

        static String conciseText(String text) {
            String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
            return value.length() > 360 ? value.substring(0, 360) + "..." : value;
        }

        boolean isSuccess(JSONObject body) {
            return endpoints.response.isSuccess(body);
        }

        boolean isAuthSuccess(JSONObject body) {
            return endpoints.auth.isSuccess(body, endpoints.response);
        }

        Object apiData(JSONObject body) {
            return endpoints.response.data(body);
        }

        Object authData(JSONObject body) {
            return endpoints.auth.data(body, endpoints.response);
        }

        JSONObject apiDataObject(JSONObject body) {
            Object data = apiData(body);
            return data instanceof JSONObject ? (JSONObject) data : null;
        }

        JSONObject authDataObject(JSONObject body) {
            Object data = authData(body);
            return data instanceof JSONObject ? (JSONObject) data : null;
        }

        String apiErrorMessage(JSONObject body) {
            if (body == null) return "Empty response";
            return conciseText(endpoints.response.errorMessage(body));
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
