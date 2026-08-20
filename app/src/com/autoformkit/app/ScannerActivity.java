package com.autoformkit.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ScannerActivity extends ComponentActivity {
    private static final int REQ_CAMERA_PERMISSION = 4101;
    private static final long SCAN_RESULT_BUFFER_MS = 1250L;
    private static final long SCAN_CONFIRM_WINDOW_MS = 550L;
    private static final long SCAN_SINGLE_SOURCE_FALLBACK_MS = 1900L;
    private static final int BARCODE_CONFIRM_COUNT = 2;
    private static final int TEXT_CONFIRM_COUNT = 2;
    private static final int CROSS_CONFIRM_COUNT = 2;
    private static final int MAX_SCAN_QUEUE_SIZE = 6;
    private static final long AUTO_ZOOM_DELAY_MS = 1600L;
    private static final long AUTO_ZOOM_STEP_MS = 1900L;
    private static final long MANUAL_TEXT_SESSION_MS = 4200L;
    private static final long MANUAL_TEXT_SAMPLE_INTERVAL_MS = 420L;
    private static final long CAMERA_VIEWPORT_RETRY_MS = 80L;
    private static final float GUIDE_WIDTH_FRACTION = 0.86f;
    private static final float GUIDE_HEIGHT_FRACTION = 0.30f;
    private static final float GUIDE_TOP_FRACTION = 0.36f;
    private static final int TEXT_GUIDE_BONUS = 240;
    private static final int TEXT_CENTER_BONUS = 180;
    private static final int TEXT_LINE_GEOMETRY_BONUS = 24;
    private static final int TEXT_MAX_ANGLE_PENALTY = 72;

    private PreviewView previewView;
    private GuideOverlay guideOverlay;
    private TextView titleText;
    private TextView statusText;
    private TextView cancelButton;
    private TextView shutterButton;
    private TextView zoomButton;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final Object manualSessionLock = new Object();

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private BarcodeScanner barcodeScanner;
    private TextRecognizer textRecognizer;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private boolean cameraBindRetryPosted = false;
    private boolean ignoredNumericScan = false;
    private boolean ignoredWrongLengthScan = false;
    private String ignoredWrongLengthSource = "";
    private final AtomicLong manualTextDeadlineMs = new AtomicLong(0L);
    private final AtomicLong manualTextGeneration = new AtomicLong(0L);
    // These are analyzer-thread state. The deadline is atomic because the shutter runs on main.
    private boolean manualTextSawCandidate = false;
    private boolean manualTextFailureSeen = false;
    private boolean ocrOnly = false;
    private boolean zoomed = false;
    private int autoZoomStep = 0;
    private long scannerStartedMs = 0L;
    private long lastTextAttemptMs = 0L;
    private long lastAutoZoomMs = 0L;
    private final Deque<ScanRead> barcodeQueue = new ArrayDeque<>();
    private final Deque<ScanRead> textQueue = new ArrayDeque<>();
    private final SourceConfirmState barcodeConfirm = new SourceConfirmState();
    private final SourceConfirmState textConfirm = new SourceConfirmState();
    private String pendingScanValue = "";
    private int pendingScanCount = 0;
    private long pendingScanFirstSeenMs = 0L;
    private long pendingScanLastSeenMs = 0L;
    private SnScanRules.Policy scannerPolicy = SnScanRules.Policy.from(new JSONObject());
    private String promptMessage = "";
    private String identifierLabel = "";
    private String lang = "zh";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readIntent();
        barcodeScanner = BarcodeScanning.getClient(
            new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93
                )
                .enableAllPotentialBarcodes()
                .build()
        );
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        setContentView(createContentView());
        scannerStartedMs = System.currentTimeMillis();
        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        }
    }

    private void readIntent() {
        Intent intent = getIntent();
        if (intent == null) return;
        promptMessage = safe(intent.getStringExtra("PROMPT_MESSAGE"));
        identifierLabel = safe(intent.getStringExtra("IDENTIFIER_LABEL"));
        ocrOnly = intent.getBooleanExtra("OCR_ONLY", false);
        JSONObject configured = null;
        String serialized = safe(intent.getStringExtra("SCANNER_POLICY_JSON"));
        if (!serialized.isEmpty()) {
            try {
                configured = new JSONObject(serialized);
            } catch (Exception ignored) {
                // A malformed configured policy is represented as an invalid expectedLength type;
                // Policy then rejects every result instead of falling back to permissive defaults.
                configured = invalidPolicyJson();
            }
        }
        if (configured == null) {
            // Compatibility for callers using the original intent extras.
            configured = new JSONObject();
            try {
                configured.put("autoTextMode", safe(intent.getStringExtra("AUTO_TEXT_MODE")));
                configured.put("rejectNumericOnly",
                    intent.getBooleanExtra("REJECT_NUMERIC_ONLY", false));
                int expected = Math.max(0, intent.getIntExtra("EXPECTED_SN_LENGTH", 0));
                if (expected > 0) configured.put("expectedLength", expected);
                String[] prefixes = intent.getStringArrayExtra("PREFERRED_SN_PREFIXES");
                if (prefixes != null) configured.put("preferredPrefixes", new JSONArray(prefixes));
            } catch (Exception ignored) {
                configured = invalidPolicyJson();
            }
        }
        scannerPolicy = SnScanRules.Policy.from(configured);
        String passed = safe(intent.getStringExtra("lang"));
        if (!passed.isEmpty()) lang = passed;
    }

    /** Localize scanner UI to the language MainActivity passed via the "lang" extra. */
    private String s(String zh, String en, String es) {
        if ("en".equals(lang)) return en;
        if ("es".equals(lang)) return es;
        return zh;
    }

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        guideOverlay = new GuideOverlay(this);
        root.addView(guideOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FrameLayout header = new FrameLayout(this);
        header.setPadding(dp(18), dp(34), dp(18), 0);
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(130),
            Gravity.TOP
        );
        root.addView(header, headerParams);

        titleText = new TextView(this);
        titleText.setText(promptMessage.isEmpty()
            ? s("\u626b\u63cf\u6807\u8bc6", "Scan identifier", "Escanear identificador")
            : promptMessage);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(30);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setGravity(Gravity.CENTER);
        header.addView(titleText, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(46),
            Gravity.TOP
        ));

        statusText = new TextView(this);
        statusText.setText(statusMessage());
        statusText.setTextColor(0xe6ffffff);
        statusText.setTextSize(18);
        statusText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(40),
            Gravity.TOP
        );
        statusParams.topMargin = dp(50);
        header.addView(statusText, statusParams);

        FrameLayout controls = new FrameLayout(this);
        controls.setPadding(dp(28), 0, dp(28), dp(28));
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(130),
            Gravity.BOTTOM
        );
        root.addView(controls, controlsParams);

        cancelButton = controlButton("\u00d7", dp(72), false);
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
            dp(72), dp(72), Gravity.START | Gravity.CENTER_VERTICAL);
        controls.addView(cancelButton, cancelParams);
        cancelButton.setOnClickListener(v -> cancelScan());

        shutterButton = shutterButton();
        FrameLayout.LayoutParams shutterParams = new FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER);
        controls.addView(shutterButton, shutterParams);
        shutterButton.setOnClickListener(v -> requestTextNow());

        zoomButton = controlButton(s("\u653e\u5927", "Zoom", "Acercar"), dp(100), true);
        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(
            dp(112), dp(64), Gravity.END | Gravity.CENTER_VERTICAL);
        controls.addView(zoomButton, zoomParams);
        zoomButton.setOnClickListener(v -> manualToggleZoom());

        return root;
    }

    private TextView controlButton(String label, int size, boolean pill) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(pill ? 21 : 42);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x2b000000);
        bg.setStroke(dp(1), 0x70ffffff);
        bg.setCornerRadius(size / 2f);
        view.setBackground(bg);
        return view;
    }

    private TextView shutterButton() {
        TextView view = new TextView(this);
        view.setText("");
        android.graphics.drawable.GradientDrawable outer = new android.graphics.drawable.GradientDrawable();
        outer.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        outer.setColor(0xeeffffff);
        outer.setStroke(dp(6), 0x88ffffff);
        view.setBackground(outer);
        return view;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception exc) {
                showToast(s("\u6253\u5f00\u76f8\u673a\u5931\u8d25", "Camera open failed", "Error al abrir c\u00e1mara") + ": " + exc.getMessage());
                Diagnostics.append(this, "MLKit scanner camera start failed: " + exc.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void bindCameraUseCases() {
        if (cameraProvider == null || finished.get()) return;
        ViewPort viewPort = previewView == null ? null : previewView.getViewPort();
        if (viewPort == null) {
            scheduleCameraBindRetry();
            return;
        }
        cameraBindRetryPosted = false;
        Preview preview = new Preview.Builder()
            .setTargetResolution(new Size(1280, 720))
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        analysis.setAnalyzer(analyzerExecutor, this::analyzeImage);

        cameraProvider.unbindAll();
        UseCaseGroup useCases = new UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .build();
        camera = cameraProvider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            useCases
        );
        focusCenterSoon();
        Diagnostics.append(this, "MLKit scanner started autoTextMode="
            + scannerPolicy.autoTextMode + " policyValid=" + scannerPolicy.valid
            + " ocrOnly=" + ocrOnly);
    }

    private void scheduleCameraBindRetry() {
        if (cameraBindRetryPosted || finished.get() || previewView == null) return;
        cameraBindRetryPosted = true;
        previewView.postDelayed(() -> {
            cameraBindRetryPosted = false;
            if (!finished.get()) bindCameraUseCases();
        }, CAMERA_VIEWPORT_RETRY_MS);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        if (finished.get() || !processing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        long now = System.currentTimeMillis();
        expireManualTextSession(now);
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            finishFrame(imageProxy);
            return;
        }
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        UprightFrameGeometry frameGeometry = uprightFrameGeometry(
            imageProxy.getWidth(), imageProxy.getHeight(), rotation,
            imageProxy.getCropRect());
        InputImage image = InputImage.fromMediaImage(mediaImage, rotation);
        boolean runBarcode = !ocrOnly;
        boolean runText = shouldReadText(now);
        if (!runBarcode && !runText) {
            maybeAutoZoom(now);
            finishFrame(imageProxy);
            return;
        }
        AtomicInteger remaining = new AtomicInteger((runBarcode ? 1 : 0) + (runText ? 1 : 0));
        Runnable finishTask = () -> {
            if (remaining.decrementAndGet() == 0) {
                if (!finished.get()) maybeAutoZoom(now);
                finishFrame(imageProxy);
            }
        };
        if (runBarcode) {
            barcodeScanner.process(image)
                .addOnSuccessListener(analyzerExecutor, barcodes -> {
                    String value = barcodeResult(barcodes, frameGeometry);
                    if (!value.isEmpty()) finishWithResult(value, "MLKIT_BARCODE");
                })
                .addOnFailureListener(analyzerExecutor, exc -> Diagnostics.append(this, "MLKit barcode failed: " + concise(exc)))
                .addOnCompleteListener(analyzerExecutor, task -> finishTask.run());
        }
        if (runText) {
            boolean manualText = manualTextSessionActive(now);
            long recognitionGeneration = manualTextGeneration.get();
            lastTextAttemptMs = now;
            textRecognizer.process(image)
                .addOnSuccessListener(analyzerExecutor, text -> {
                    synchronized (manualSessionLock) {
                        if (!textRecognitionCallbackCurrent(manualText,
                                recognitionGeneration, System.currentTimeMillis())) return;
                        String sn = textResult(text, frameGeometry);
                        if (!sn.isEmpty()) {
                            if (manualText) manualTextSawCandidate = true;
                            finishWithResult(sn, "MLKIT_TEXT");
                        }
                    }
                })
                .addOnFailureListener(analyzerExecutor, exc -> {
                    synchronized (manualSessionLock) {
                        if (!textRecognitionCallbackCurrent(manualText,
                                recognitionGeneration, System.currentTimeMillis())) return;
                        Diagnostics.append(this, "MLKit text failed: " + concise(exc));
                        if (manualText) manualTextFailureSeen = true;
                    }
                })
                .addOnCompleteListener(analyzerExecutor, done -> finishTask.run());
        }
    }

    private void finishFrame(ImageProxy imageProxy) {
        try {
            imageProxy.close();
        } finally {
            processing.set(false);
        }
    }

    private boolean shouldReadText(long now) {
        long elapsed = now - scannerStartedMs;
        boolean manualSampleDue = manualTextSampleDue(
            manualTextDeadlineMs.get(), now, lastTextAttemptMs);
        return SnScanRules.shouldReadText(scannerPolicy, ocrOnly, manualSampleDue,
            elapsed, now - lastTextAttemptMs);
    }

    private String barcodeResult(List<Barcode> barcodes, UprightFrameGeometry frameGeometry) {
        if (barcodes == null || barcodes.isEmpty()) return "";
        String bestValue = "";
        int bestScore = Integer.MIN_VALUE;
        for (Barcode barcode : barcodes) {
            String value = scannerPolicy.normalizeForSource(
                firstNonEmpty(barcode.getRawValue(), barcode.getDisplayValue()),
                SnScanRules.SOURCE_BARCODE);
            if (value.isEmpty()) continue;
            SnScanRules.Rejection rejection = scannerPolicy.barcodeRejection(value);
            if (rejection == SnScanRules.Rejection.NUMERIC_ONLY) {
                ignoredNumericScan = true;
                updateStatus();
                Diagnostics.append(this, "MLKit scanner ignored numeric-only barcode format=" + barcode.getFormat() + " length=" + value.length());
                continue;
            }
            if (rejection == SnScanRules.Rejection.WRONG_LENGTH
                    || rejection == SnScanRules.Rejection.TOO_SHORT
                    || rejection == SnScanRules.Rejection.TOO_LONG) {
                if (rejection == SnScanRules.Rejection.WRONG_LENGTH
                        && !scannerPolicy.requiredLengthsForSource(
                            SnScanRules.SOURCE_BARCODE).isEmpty()) {
                    ignoredWrongLengthScan = true;
                    ignoredWrongLengthSource = SnScanRules.SOURCE_BARCODE;
                    updateStatus();
                }
                Diagnostics.append(this, "MLKit scanner ignored barcode length format="
                    + barcode.getFormat() + " length=" + value.length()
                    + " reason=" + rejection.name()
                    + (rejection == SnScanRules.Rejection.WRONG_LENGTH
                        ? " expected=" + scannerPolicy.requiredLengthsForSource(
                            SnScanRules.SOURCE_BARCODE) : ""));
                continue;
            }
            if (rejection != SnScanRules.Rejection.NONE) {
                Diagnostics.append(this, "MLKit scanner ignored barcode by configured policy format="
                    + barcode.getFormat() + " reason=" + rejection.name());
                continue;
            }
            int score = barcodeScore(value, barcode.getBoundingBox(), frameGeometry);
            if (score > bestScore) {
                bestScore = score;
                bestValue = value;
            }
        }
        if (!bestValue.isEmpty()) return bestValue;
        for (Barcode barcode : barcodes) {
            Rect box = barcode.getBoundingBox();
            if (box != null) maybeZoomForBox(box, frameGeometry);
        }
        return "";
    }

    private int barcodeScore(String value, Rect box, UprightFrameGeometry frameGeometry) {
        int score = 0;
        if (!scannerPolicy.requiredLengthsForSource(
                SnScanRules.SOURCE_BARCODE).isEmpty()
                && scannerPolicy.matchesConfiguredLength(
                    SnScanRules.SOURCE_BARCODE, value.length())) score += 1000;
        if (isLikelyIdentifier(value, SnScanRules.SOURCE_BARCODE)) score += 300;
        if (SnScanRules.hasPreferredPrefix(value, scannerPolicy.preferredPrefixes)) score += 120;
        if (validTextGeometry(box, frameGeometry)) {
            float centerX = frameGeometry.normalizedX(box.centerX()) - 0.5f;
            float centerY = frameGeometry.normalizedY(box.centerY()) - 0.5f;
            float distance = Math.abs(centerX) + Math.abs(centerY);
            score += Math.max(0, 180 - Math.round(distance * 280));
        }
        return score;
    }

    private String textResult(Text text, UprightFrameGeometry frameGeometry) {
        if (text == null) return "";
        List<LocatedTextCandidate> located = new ArrayList<>();
        boolean hasLineGeometry = false;
        for (Text.TextBlock block : text.getTextBlocks()) {
            List<Text.Line> lines = block.getLines();
            for (int index = 0; index < lines.size(); index++) {
                Text.Line line = lines.get(index);
                if (validTextGeometry(line.getBoundingBox(), frameGeometry)) {
                    hasLineGeometry = true;
                }
                addLocatedTextCandidates(located, line.getText(), 12,
                    line.getBoundingBox(), line.getAngle(), true,
                    frameGeometry, null);
                if (index + 1 < lines.size()) {
                    addAdjacentLineCandidates(located, line, lines.get(index + 1),
                        frameGeometry);
                }
            }
        }
        String best;
        if (located.isEmpty() && !hasLineGeometry) {
            // Defensive compatibility for recognizer results without block/line geometry.
            List<SnScanRules.Candidate> fallback = new ArrayList<>();
            addTextCandidates(fallback, text.getText(), 0);
            best = SnScanRules.selectBest(fallback, scannerPolicy);
        } else if (located.isEmpty()) {
            best = "";
        } else {
            best = selectLocatedTextCandidate(located);
        }
        if (!best.isEmpty()) {
            Diagnostics.append(this, "MLKit identifier candidate length=" + best.length());
        }
        return best;
    }

    private void addTextCandidates(List<SnScanRules.Candidate> candidates, String raw,
                                   int lineBonus) {
        SnScanRules.addTextCandidates(candidates, raw, lineBonus, scannerPolicy);
    }

    private void addLocatedTextCandidates(List<LocatedTextCandidate> located, String raw,
                                          int lineBonus, Rect box, float angle,
                                          boolean lineGeometry,
                                          UprightFrameGeometry frameGeometry,
                                          Boolean guideEligibility) {
        List<SnScanRules.Candidate> added = new ArrayList<>();
        addTextCandidates(added, raw, lineBonus);
        if (added.isEmpty()) return;
        boolean inGuide = guideEligibility == null
            ? textCandidateInsideGuide(box, frameGeometry) : guideEligibility;
        int geometryScore = textCandidateGeometryScore(
            box, angle, lineGeometry, frameGeometry);
        for (SnScanRules.Candidate candidate : added) {
            located.add(new LocatedTextCandidate(candidate, geometryScore, inGuide,
                located.size()));
        }
    }

    private void addAdjacentLineCandidates(List<LocatedTextCandidate> located,
                                           Text.Line first, Text.Line second,
                                           UprightFrameGeometry frameGeometry) {
        if (first == null || second == null) return;
        Rect firstBox = first.getBoundingBox();
        Rect secondBox = second.getBoundingBox();
        if (!adjacentLinesEligible(firstBox, secondBox, frameGeometry)) return;
        Rect combinedBox = new Rect(firstBox);
        combinedBox.union(secondBox);
        String combinedText = safe(first.getText()) + "\n" + safe(second.getText());
        float combinedAngle = Math.max(
            absoluteTextAngle(first.getAngle()), absoluteTextAngle(second.getAngle()));
        addLocatedTextCandidates(located, combinedText, 4, combinedBox, combinedAngle,
            false, frameGeometry, Boolean.TRUE);
    }

    private String selectLocatedTextCandidate(List<LocatedTextCandidate> located) {
        boolean hasGuideCandidate = false;
        for (LocatedTextCandidate candidate : located) {
            if (candidate.inGuide) {
                hasGuideCandidate = true;
                break;
            }
        }
        List<LocatedTextCandidate> eligible = new ArrayList<>();
        for (LocatedTextCandidate candidate : located) {
            if (!hasGuideCandidate || candidate.inGuide) eligible.add(candidate);
        }
        Collections.sort(eligible, (left, right) -> {
            if (left.geometryScore != right.geometryScore) {
                return Integer.compare(right.geometryScore, left.geometryScore);
            }
            return Integer.compare(left.encounter, right.encounter);
        });
        List<SnScanRules.Candidate> ranked = new ArrayList<>();
        for (int index = 0; index < eligible.size(); index++) {
            LocatedTextCandidate locatedCandidate = eligible.get(index);
            SnScanRules.Candidate candidate = locatedCandidate.candidate;
            ranked.add(new SnScanRules.Candidate(candidate.value, candidate.source,
                candidate.score + locatedCandidate.geometryScore, index));
        }
        return SnScanRules.selectBest(ranked, scannerPolicy);
    }

    static boolean textCandidateInsideGuide(Rect box, int imageWidth, int imageHeight) {
        return textCandidateInsideGuide(box,
            UprightFrameGeometry.fullFrame(imageWidth, imageHeight));
    }

    static boolean textCandidateInsideGuide(float left, float top, float right, float bottom,
                                            int imageWidth, int imageHeight) {
        return textCandidateInsideGuide(left, top, right, bottom,
            UprightFrameGeometry.fullFrame(imageWidth, imageHeight));
    }

    static boolean textCandidateInsideGuide(Rect box,
                                            UprightFrameGeometry frameGeometry) {
        if (!validTextGeometry(box, frameGeometry)) return false;
        return textCandidateInsideGuide(box.left, box.top, box.right, box.bottom,
            frameGeometry);
    }

    static boolean textCandidateInsideGuide(float left, float top, float right, float bottom,
                                            UprightFrameGeometry frameGeometry) {
        if (!validTextGeometry(left, top, right, bottom, frameGeometry)) return false;
        float centerX = frameGeometry.normalizedX((left + right) / 2f);
        float centerY = frameGeometry.normalizedY((top + bottom) / 2f);
        float guideLeft = (1f - GUIDE_WIDTH_FRACTION) / 2f;
        float guideRight = guideLeft + GUIDE_WIDTH_FRACTION;
        float guideBottom = GUIDE_TOP_FRACTION + GUIDE_HEIGHT_FRACTION;
        return centerX >= guideLeft && centerX <= guideRight
            && centerY >= GUIDE_TOP_FRACTION && centerY <= guideBottom;
    }

    static boolean textCandidateIntersectsGuide(Rect box,
                                                UprightFrameGeometry frameGeometry) {
        return box != null && textCandidateIntersectsGuide(
            box.left, box.top, box.right, box.bottom, frameGeometry);
    }

    static boolean adjacentLinesEligible(Rect first, Rect second,
                                         UprightFrameGeometry frameGeometry) {
        return textCandidateIntersectsGuide(first, frameGeometry)
            && textCandidateIntersectsGuide(second, frameGeometry);
    }

    static boolean adjacentLinesEligible(float firstLeft, float firstTop,
                                         float firstRight, float firstBottom,
                                         float secondLeft, float secondTop,
                                         float secondRight, float secondBottom,
                                         UprightFrameGeometry frameGeometry) {
        return textCandidateIntersectsGuide(firstLeft, firstTop, firstRight, firstBottom,
                frameGeometry)
            && textCandidateIntersectsGuide(secondLeft, secondTop, secondRight, secondBottom,
                frameGeometry);
    }

    private static boolean textCandidateIntersectsGuide(float left, float top,
                                                        float right, float bottom,
                                                        UprightFrameGeometry frameGeometry) {
        if (!validTextGeometry(left, top, right, bottom, frameGeometry)) return false;
        float guideLeft = frameGeometry.cropLeft
            + frameGeometry.cropWidth * (1f - GUIDE_WIDTH_FRACTION) / 2f;
        float guideRight = guideLeft + frameGeometry.cropWidth * GUIDE_WIDTH_FRACTION;
        float guideTop = frameGeometry.cropTop
            + frameGeometry.cropHeight * GUIDE_TOP_FRACTION;
        float guideBottom = guideTop + frameGeometry.cropHeight * GUIDE_HEIGHT_FRACTION;
        return right >= guideLeft && left <= guideRight
            && bottom >= guideTop && top <= guideBottom;
    }

    static int textCandidateGeometryScore(Rect box, float angle, boolean lineGeometry,
                                          int imageWidth, int imageHeight) {
        return textCandidateGeometryScore(box, angle, lineGeometry,
            UprightFrameGeometry.fullFrame(imageWidth, imageHeight));
    }

    static int textCandidateGeometryScore(float left, float top, float right, float bottom,
                                          float angle, boolean lineGeometry,
                                          int imageWidth, int imageHeight) {
        return textCandidateGeometryScore(left, top, right, bottom, angle,
            lineGeometry, UprightFrameGeometry.fullFrame(imageWidth, imageHeight));
    }

    private static int textCandidateGeometryScore(Rect box, float angle,
                                                  boolean lineGeometry,
                                                  UprightFrameGeometry frameGeometry) {
        if (!validTextGeometry(box, frameGeometry)) return 0;
        return textCandidateGeometryScore(box.left, box.top, box.right, box.bottom,
            angle, lineGeometry, frameGeometry);
    }

    private static int textCandidateGeometryScore(float left, float top,
                                                  float right, float bottom,
                                                  float angle, boolean lineGeometry,
                                                  UprightFrameGeometry frameGeometry) {
        if (!validTextGeometry(left, top, right, bottom, frameGeometry)) return 0;
        float centerX = frameGeometry.normalizedX((left + right) / 2f);
        float centerY = frameGeometry.normalizedY((top + bottom) / 2f);
        float guideHalfWidth = GUIDE_WIDTH_FRACTION / 2f;
        float guideHalfHeight = GUIDE_HEIGHT_FRACTION / 2f;
        float guideCenterY = GUIDE_TOP_FRACTION + guideHalfHeight;
        float distanceX = Math.abs(centerX - 0.5f) / guideHalfWidth;
        float distanceY = Math.abs(centerY - guideCenterY) / guideHalfHeight;
        float normalizedDistance = Math.min(1f, (distanceX + distanceY) / 2f);
        int centerBonus = Math.round((1f - normalizedDistance) * TEXT_CENTER_BONUS);
        int guideBonus = textCandidateInsideGuide(left, top, right, bottom, frameGeometry)
            ? TEXT_GUIDE_BONUS : 0;
        float boundedAngle = Math.min(45f, absoluteTextAngle(angle));
        int anglePenalty = Math.round(
            boundedAngle / 45f * TEXT_MAX_ANGLE_PENALTY);
        return guideBonus + centerBonus
            + (lineGeometry ? TEXT_LINE_GEOMETRY_BONUS : 0) - anglePenalty;
    }

    private static boolean validTextGeometry(Rect box,
                                             UprightFrameGeometry frameGeometry) {
        return box != null && validTextGeometry(box.left, box.top, box.right, box.bottom,
            frameGeometry);
    }

    private static boolean validTextGeometry(float left, float top,
                                             float right, float bottom,
                                             UprightFrameGeometry frameGeometry) {
        return frameGeometry != null && frameGeometry.valid
            && !Float.isNaN(left) && !Float.isNaN(top)
            && !Float.isNaN(right) && !Float.isNaN(bottom)
            && !Float.isInfinite(left) && !Float.isInfinite(top)
            && !Float.isInfinite(right) && !Float.isInfinite(bottom)
            && right > left && bottom > top;
    }

    private static float absoluteTextAngle(float angle) {
        if (Float.isNaN(angle) || Float.isInfinite(angle)) return 0f;
        float normalized = Math.abs(angle % 360f);
        return normalized > 180f ? 360f - normalized : normalized;
    }

    static int orientedImageWidth(int width, int height, int rotationDegrees) {
        int rotation = ((rotationDegrees % 360) + 360) % 360;
        return rotation == 90 || rotation == 270 ? height : width;
    }

    static int orientedImageHeight(int width, int height, int rotationDegrees) {
        int rotation = ((rotationDegrees % 360) + 360) % 360;
        return rotation == 90 || rotation == 270 ? width : height;
    }

    static UprightFrameGeometry uprightFrameGeometry(int imageWidth, int imageHeight,
                                                      int rotationDegrees, Rect cropRect) {
        return cropRect == null
            ? uprightFrameGeometry(imageWidth, imageHeight, rotationDegrees,
                0f, 0f, imageWidth, imageHeight)
            : uprightFrameGeometry(imageWidth, imageHeight, rotationDegrees,
                cropRect.left, cropRect.top, cropRect.right, cropRect.bottom);
    }

    static UprightFrameGeometry uprightFrameGeometry(int imageWidth, int imageHeight,
                                                      int rotationDegrees,
                                                      float cropLeft, float cropTop,
                                                      float cropRight, float cropBottom) {
        if (imageWidth <= 0 || imageHeight <= 0) return UprightFrameGeometry.invalid();
        float left = Math.max(0f, Math.min(imageWidth, cropLeft));
        float top = Math.max(0f, Math.min(imageHeight, cropTop));
        float right = Math.max(0f, Math.min(imageWidth, cropRight));
        float bottom = Math.max(0f, Math.min(imageHeight, cropBottom));
        if (right <= left || bottom <= top) {
            left = 0f;
            top = 0f;
            right = imageWidth;
            bottom = imageHeight;
        }
        int rotation = ((rotationDegrees % 360) + 360) % 360;
        switch (rotation) {
            case 0:
                return new UprightFrameGeometry(left, top, right, bottom);
            case 90:
                return new UprightFrameGeometry(imageHeight - bottom, left,
                    imageHeight - top, right);
            case 180:
                return new UprightFrameGeometry(imageWidth - right,
                    imageHeight - bottom, imageWidth - left,
                    imageHeight - top);
            case 270:
                return new UprightFrameGeometry(top, imageWidth - right,
                    bottom, imageWidth - left);
            default:
                return UprightFrameGeometry.invalid();
        }
    }

    private boolean isLikelyIdentifier(String value, String source) {
        return scannerPolicy.rejectionForSource(value, source)
            == SnScanRules.Rejection.NONE;
    }

    private void maybeAutoZoom(long now) {
        if (camera == null || zoomed) return;
        long elapsed = now - scannerStartedMs;
        if (elapsed < AUTO_ZOOM_DELAY_MS || now - lastAutoZoomMs < AUTO_ZOOM_STEP_MS) return;
        autoZoomStep += 1;
        lastAutoZoomMs = now;
        float linear = autoZoomStep == 1 ? 0.22f : 0.42f;
        if (autoZoomStep >= 2) zoomed = true;
        camera.getCameraControl().setLinearZoom(linear);
        updateStatus();
        focusCenterSoon();
    }

    private void maybeZoomForBox(Rect box, UprightFrameGeometry frameGeometry) {
        if (camera == null || zoomed || !validTextGeometry(box, frameGeometry)) return;
        float imageArea = Math.max(1f,
            frameGeometry.cropWidth * frameGeometry.cropHeight);
        float boxArea = Math.max(1f, (float) box.width() * (float) box.height());
        if (boxArea / imageArea < 0.035f) {
            zoomed = true;
            camera.getCameraControl().setLinearZoom(0.36f);
            updateStatus();
            focusCenterSoon();
        }
    }

    private void requestTextNow() {
        long now = System.currentTimeMillis();
        long requestedDeadline = safeDeadline(now, MANUAL_TEXT_SESSION_MS);
        long activeDeadline;
        boolean newSession;
        long generation = 0L;
        synchronized (manualSessionLock) {
            while (true) {
                long current = manualTextDeadlineMs.get();
                long next = Math.max(current, requestedDeadline);
                if (manualTextDeadlineMs.compareAndSet(current, next)) {
                    activeDeadline = next;
                    newSession = current <= now;
                    if (newSession) generation = manualTextGeneration.incrementAndGet();
                    break;
                }
            }
        }
        if (newSession) beginManualTextSession(generation);
        scheduleManualTextTimeout(activeDeadline, now);
        setStatus(readingIdentifierMessage());
        focusCenterSoon();
    }

    private void beginManualTextSession(long generation) {
        try {
            analyzerExecutor.execute(() -> {
                if (finished.get() || manualTextGeneration.get() != generation
                        || !manualTextSessionActive(System.currentTimeMillis())) return;
                manualTextSawCandidate = false;
                manualTextFailureSeen = false;
                resetConfirmationState();
            });
        } catch (RejectedExecutionException ignored) {
            // A click racing Activity destruction cannot start a new scanner session.
        }
    }

    private void scheduleManualTextTimeout(long deadline, long now) {
        long delay = Math.max(1L, deadline - now + 16L);
        mainHandler.postDelayed(() -> {
            if (finished.get() || manualTextDeadlineMs.get() != deadline) return;
            try {
                analyzerExecutor.execute(() -> expireManualTextSession(
                    System.currentTimeMillis()));
            } catch (RejectedExecutionException ignored) {
                // Activity destruction owns executor shutdown; there is no UI left to notify.
            }
        }, delay);
    }

    private boolean manualTextSessionActive(long now) {
        long deadline = manualTextDeadlineMs.get();
        return deadline > 0L && now <= deadline;
    }

    private boolean textRecognitionCallbackCurrent(boolean manualText,
                                                   long recognitionGeneration,
                                                   long now) {
        return textRecognitionCallbackCurrent(finished.get(), manualText,
            recognitionGeneration, manualTextGeneration.get(),
            manualTextDeadlineMs.get(), now);
    }

    static boolean textRecognitionCallbackCurrent(boolean finished, boolean manualText,
                                                  long recognitionGeneration,
                                                  long currentGeneration,
                                                  long manualDeadline,
                                                  long now) {
        if (finished || recognitionGeneration != currentGeneration) return false;
        return !manualText || (manualDeadline > 0L && now <= manualDeadline);
    }

    static boolean manualTextSampleDue(long deadline, long now, long lastAttempt) {
        if (deadline <= 0L || now > deadline) return false;
        return lastAttempt <= 0L || now - lastAttempt >= MANUAL_TEXT_SAMPLE_INTERVAL_MS;
    }

    private void expireManualTextSession(long now) {
        long deadline = manualTextDeadlineMs.get();
        if (deadline <= 0L || now <= deadline
                || !manualTextDeadlineMs.compareAndSet(deadline, 0L)) return;
        boolean sawCandidate = manualTextSawCandidate;
        boolean sawFailure = manualTextFailureSeen;
        manualTextSawCandidate = false;
        manualTextFailureSeen = false;
        resetConfirmationState();
        showToast(manualTextTimeoutMessage(sawCandidate, sawFailure));
        updateStatus();
    }

    private String manualTextTimeoutMessage(boolean sawCandidate, boolean sawFailure) {
        if (sawCandidate) {
            return s("\u672a\u80fd\u7a33\u5b9a\u786e\u8ba4\u6807\u8bc6\uff0c\u8bf7\u4fdd\u6301\u4e2d\u95f4\u6807\u7b7e\u6e05\u6670\u540e\u518d\u8bd5",
                "Could not confirm the identifier. Keep the center label clear and retry.",
                "No se pudo confirmar el identificador. Mantenga clara la etiqueta central e intente de nuevo.");
        }
        if (sawFailure) {
            return s("\u6587\u5b57\u8bc6\u522b\u5931\u8d25", "Text recognition failed",
                "Error de reconocimiento de texto");
        }
        return noIdentifierDetectedMessage();
    }

    private void resetConfirmationState() {
        barcodeQueue.clear();
        textQueue.clear();
        barcodeConfirm.clear();
        textConfirm.clear();
        pendingScanValue = "";
        pendingScanCount = 0;
        pendingScanFirstSeenMs = 0L;
        pendingScanLastSeenMs = 0L;
    }

    private static long safeDeadline(long now, long duration) {
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    private void manualToggleZoom() {
        if (camera == null) return;
        zoomed = !zoomed;
        autoZoomStep = zoomed ? 2 : 0;
        camera.getCameraControl().setLinearZoom(zoomed ? 0.45f : 0f);
        updateStatus();
        focusCenterSoon();
    }

    private void focusCenterSoon() {
        mainHandler.postDelayed(this::focusCenter, 250L);
    }

    private void focusCenter() {
        if (camera == null || previewView == null || previewView.getWidth() <= 0 || previewView.getHeight() <= 0) return;
        try {
            MeteringPointFactory factory = previewView.getMeteringPointFactory();
            MeteringPoint point = factory.createPoint(previewView.getWidth() / 2f, previewView.getHeight() / 2f);
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB
            ).setAutoCancelDuration(2, TimeUnit.SECONDS).build();
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (Exception exc) {
            Diagnostics.append(this, "Scanner focus skipped: " + exc.getMessage());
        }
    }

    private void updateStatus() {
        mainHandler.post(() -> setStatus(statusMessage()));
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private String statusMessage() {
        String label = displayIdentifierLabel();
        if (!pendingScanValue.isEmpty()) {
            return s("\u6b63\u5728\u4ea4\u53c9\u786e\u8ba4 ", "Cross-checking ", "Verificando ") + label + " "
                + pendingScanCount + "/" + CROSS_CONFIRM_COUNT
                + s("\uff0c\u8bf7\u4fdd\u6301\u5bf9\u51c6", ", keep it aligned", ", mant\u00e9ngalo alineado");
        }
        if (ignoredNumericScan) return s("\u5df2\u5ffd\u7565\u7eaf\u6570\u5b57\u7801\uff0c\u7ee7\u7eed\u67e5\u627e ",
            "Ignored numeric-only code; still looking for ",
            "Se ignor\u00f3 el c\u00f3digo solo num\u00e9rico; buscando ") + label;
        if (ignoredWrongLengthScan) {
            List<Integer> required = scannerPolicy.requiredLengthsForSource(
                ignoredWrongLengthSource);
            if (!required.isEmpty()) return wrongLengthStatus(label, required);
        }
        if (ocrOnly) {
            if ("en".equals(lang)) return withAllowedLengthHint(
                "Aim at the " + label + " label; text will be read locally");
            if ("es".equals(lang)) return withAllowedLengthHint(
                "Apunte a la etiqueta de " + label + "; el texto se leer\u00e1 localmente");
            return withAllowedLengthHint(
                "\u5bf9\u51c6" + label + "\u6807\u7b7e\uff0c\u5c06\u76f4\u63a5\u672c\u5730\u8bfb\u53d6\u6587\u5b57");
        }
        if (zoomed) return withAllowedLengthHint(s(
            "\u5df2\u653e\u5927\uff0c\u4fdd\u6301\u6807\u7b7e\u6e05\u6670",
            "Zoomed in, keep the label sharp",
            "Acercado, mantenga la etiqueta n\u00edtida"));
        if (isAutoTextAlways()) return withAllowedLengthHint(s(
            "\u6b63\u5728\u540c\u65f6\u8bfb\u53d6\u6761\u7801\u548c\u6587\u5b57",
            "Reading both codes and text", "Leyendo c\u00f3digos y texto"));
        if (isAutoTextFallback()) return withAllowedLengthHint(s(
            "\u6761\u7801\u672a\u8bfb\u53d6\u65f6\u5c06\u5c1d\u8bd5\u6587\u5b57\u8bc6\u522b",
            "Text recognition will be tried if code scanning fails",
            "Se intentar\u00e1 reconocer texto si falla el c\u00f3digo"));
        return withAllowedLengthHint(s(
            "\u5bf9\u51c6\u6761\u7801\u6216\u6587\u5b57\u6807\u8bc6",
            "Aim at the code or text identifier",
            "Apunte al c\u00f3digo o identificador de texto"));
    }

    private String wrongLengthStatus(String label, List<Integer> required) {
        String lengths = localizedLengths(required);
        if (required.size() == 1) {
            if ("en".equals(lang)) return "Ignored " + label + " that is not "
                + lengths + " characters; still scanning";
            if ("es".equals(lang)) return "Se ignor\u00f3 " + label + " que no tiene "
                + lengths + " caracteres; escaneando";
            return "\u5df2\u5ffd\u7565\u975e " + lengths + " \u4f4d" + label
                + "\uff0c\u7ee7\u7eed\u626b\u63cf";
        }
        if ("en".equals(lang)) return "Ignored " + label + " whose length is not "
            + lengths + "; still scanning";
        if ("es".equals(lang)) return "Se ignor\u00f3 " + label + " cuya longitud no es "
            + lengths + "; escaneando";
        return "\u5df2\u5ffd\u7565\u957f\u5ea6\u4e0d\u662f " + lengths + " \u4f4d\u7684"
            + label + "\uff0c\u7ee7\u7eed\u626b\u63cf";
    }

    private String withAllowedLengthHint(String message) {
        List<Integer> barcode = scannerPolicy.requiredLengthsForSource(
            SnScanRules.SOURCE_BARCODE);
        List<Integer> ocr = scannerPolicy.requiredLengthsForSource(SnScanRules.SOURCE_OCR);
        List<Integer> shown = ocrOnly
            ? ocr : (barcode.equals(ocr) ? barcode : Collections.emptyList());
        if (shown.size() < 2) return message;
        String lengths = localizedLengths(shown);
        if ("en".equals(lang)) return message + " \u00b7 Length: " + lengths;
        if ("es".equals(lang)) return message + " \u00b7 Longitud: " + lengths;
        return message + " \u00b7 \u957f\u5ea6 " + lengths + " \u4f4d";
    }

    private String localizedLengths(List<Integer> lengths) {
        return SnScanRules.formatLengths(lengths,
            "en".equals(lang) ? "or" : ("es".equals(lang) ? "o" : "\u6216"));
    }

    private String displayIdentifierLabel() {
        return identifierLabel.isEmpty()
            ? s("\u6807\u8bc6\u7b26", "identifier", "identificador")
            : identifierLabel;
    }

    private String readingIdentifierMessage() {
        String label = displayIdentifierLabel();
        if ("en".equals(lang)) return "Reading " + label + "...";
        if ("es".equals(lang)) return "Leyendo " + label + "...";
        return "\u6b63\u5728\u8bfb\u53d6" + label + "...";
    }

    private String noIdentifierDetectedMessage() {
        String label = displayIdentifierLabel();
        if ("en".equals(lang)) return "No " + label + " detected. Aim at the label and retry.";
        if ("es".equals(lang)) return "No se detect\u00f3 " + label
            + ". Apunte a la etiqueta e intente de nuevo.";
        return "\u6ca1\u6709\u8bfb\u5230" + label + "\uff0c\u8bf7\u5bf9\u51c6\u6807\u7b7e\u518d\u8bd5";
    }

    private boolean isAutoTextEnabled() {
        return ocrOnly || isAutoTextAlways() || isAutoTextFallback();
    }

    private boolean isAutoTextAlways() {
        return SnScanRules.AUTO_TEXT_ALWAYS.equals(scannerPolicy.autoTextMode);
    }

    private boolean isAutoTextFallback() {
        return SnScanRules.AUTO_TEXT_FALLBACK.equals(scannerPolicy.autoTextMode);
    }

    private void finishWithResult(String text, String format) {
        String source;
        if ("MLKIT_BARCODE".equals(format)) {
            source = SnScanRules.SOURCE_BARCODE;
        } else if ("MLKIT_TEXT".equals(format)) {
            source = SnScanRules.SOURCE_OCR;
        } else {
            Diagnostics.append(this,
                "MLKit scanner rejected result with unknown format=" + safe(format));
            return;
        }
        // barcodeResult/textResult already normalized exactly once for this source. Reapplying
        // label stripping can corrupt a legitimate value whose prefix equals the configured label.
        String value = text == null ? "" : text;
        if (finished.get() || value.isEmpty()) return;
        long now = System.currentTimeMillis();
        SnScanRules.Rejection rejection = scannerPolicy.rejectionForSource(value, source);
        if (rejection != SnScanRules.Rejection.NONE) {
            if (rejection == SnScanRules.Rejection.WRONG_LENGTH
                    && !scannerPolicy.requiredLengthsForSource(source).isEmpty()) {
                ignoredWrongLengthScan = true;
                ignoredWrongLengthSource = source;
                updateStatus();
            }
            Diagnostics.append(this, "MLKit scanner ignored result by configured policy format="
                + format + " length=" + value.length() + " reason=" + rejection.name());
            return;
        }
        if (!confirmStableResult(value, format, now)) return;
        if (!finished.compareAndSet(false, true)) return;
        manualTextDeadlineMs.set(0L);
        Intent data = new Intent();
        data.putExtra("SCAN_RESULT", value);
        data.putExtra("SCAN_RESULT_FORMAT", format);
        setResult(RESULT_OK, data);
        finish();
    }

    private boolean confirmStableResult(String value, String format, long now) {
        if (ocrOnly || !isAutoTextEnabled()) {
            return confirmSingleSourceResult(value, format, now);
        }
        boolean textSource = "MLKIT_TEXT".equals(format);
        SourceConfirmState source = textSource ? textConfirm : barcodeConfirm;
        addScanRead(textSource ? textQueue : barcodeQueue, value, now);
        updateSourceConfirm(source, value, now);
        Diagnostics.append(this, "MLKit scanner candidate format=" + format + " length=" + value.length());
        boolean confirmed = matchCrossSourceQueues(now);
        if (!confirmed) confirmed = isSingleSourceFallbackConfirmed(source, textSource ? TEXT_CONFIRM_COUNT : BARCODE_CONFIRM_COUNT, now);
        updateStatus();
        return confirmed;
    }

    private boolean confirmSingleSourceResult(String value, String format, long now) {
        if (!value.equals(pendingScanValue)) {
            pendingScanValue = value;
            pendingScanCount = 1;
            pendingScanFirstSeenMs = now;
            pendingScanLastSeenMs = now;
            updateStatus();
            Diagnostics.append(this, "MLKit scanner candidate format=" + format + " length=" + value.length());
            return false;
        }
        pendingScanCount++;
        pendingScanLastSeenMs = now;
        updateStatus();
        int required = "MLKIT_TEXT".equals(format) ? TEXT_CONFIRM_COUNT : BARCODE_CONFIRM_COUNT;
        return pendingScanCount >= required
            && now - scannerStartedMs >= SCAN_RESULT_BUFFER_MS
            && now - pendingScanFirstSeenMs >= SCAN_CONFIRM_WINDOW_MS;
    }

    private void updateSourceConfirm(SourceConfirmState state, String value, long now) {
        if (!value.equals(state.value)) {
            state.value = value;
            state.count = 1;
            state.firstSeenMs = now;
            state.lastSeenMs = now;
            return;
        }
        state.count++;
        state.lastSeenMs = now;
    }

    private boolean isSingleSourceFallbackConfirmed(SourceConfirmState state, int required, long now) {
        if (state.count < required) return false;
        if (now - scannerStartedMs < SCAN_SINGLE_SOURCE_FALLBACK_MS) return false;
        if (now - state.firstSeenMs < SCAN_CONFIRM_WINDOW_MS) return false;
        pendingScanValue = state.value;
        pendingScanCount = Math.min(state.count, required);
        pendingScanFirstSeenMs = state.firstSeenMs;
        pendingScanLastSeenMs = state.lastSeenMs;
        Diagnostics.append(this, "MLKit scanner single-source fallback length=" + state.value.length() + " count=" + state.count);
        return true;
    }

    private void addScanRead(Deque<ScanRead> queue, String value, long now) {
        queue.addLast(new ScanRead(value, now));
        while (queue.size() > MAX_SCAN_QUEUE_SIZE) queue.removeFirst();
    }

    private boolean matchCrossSourceQueues(long now) {
        while (!barcodeQueue.isEmpty() && !textQueue.isEmpty()) {
            ScanRead barcode = barcodeQueue.peekFirst();
            ScanRead text = textQueue.peekFirst();
            if (barcode.value.equals(text.value)) {
                barcodeQueue.removeFirst();
                textQueue.removeFirst();
                if (barcode.value.equals(pendingScanValue)) {
                    pendingScanCount++;
                    pendingScanLastSeenMs = now;
                } else {
                    pendingScanValue = barcode.value;
                    pendingScanCount = 1;
                    pendingScanFirstSeenMs = Math.min(barcode.seenMs, text.seenMs);
                    pendingScanLastSeenMs = now;
                }
                Diagnostics.append(this, "MLKit scanner cross-confirmed length=" + barcode.value.length() + " count=" + pendingScanCount);
                if (pendingScanCount >= CROSS_CONFIRM_COUNT
                    && now - scannerStartedMs >= SCAN_RESULT_BUFFER_MS
                    && now - pendingScanFirstSeenMs >= SCAN_CONFIRM_WINDOW_MS) {
                    return true;
                }
                continue;
            }
            if (barcode.seenMs <= text.seenMs) {
                barcodeQueue.removeFirst();
            } else {
                textQueue.removeFirst();
            }
        }
        return false;
    }

    private void cancelScan() {
        finished.set(true);
        manualTextDeadlineMs.set(0L);
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelScan();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        finished.set(true);
        manualTextDeadlineMs.set(0L);
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (barcodeScanner != null) barcodeScanner.close();
        if (textRecognizer != null) textRecognizer.close();
        analyzerExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            showToast(s("\u8bf7\u5141\u8bb8\u76f8\u673a\u6743\u9650\u540e\u518d\u8bd5",
                "Please grant camera permission and retry",
                "Conceda el permiso de c\u00e1mara e intente de nuevo"));
            cancelScan();
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void showToast(String text) {
        mainHandler.post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String firstNonEmpty(String first, String second) {
        String a = safe(first);
        return a.isEmpty() ? safe(second) : a;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static JSONObject invalidPolicyJson() {
        JSONObject invalid = new JSONObject();
        try {
            invalid.put("expectedLength", "invalid");
        } catch (Exception ignored) {
            // A new in-memory object and a string value cannot fail on supported Android JSON.
        }
        return invalid;
    }

    private static String concise(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 240 ? message.substring(0, 240) + "..." : message;
    }

    private static final class ScanRead {
        final String value;
        final long seenMs;

        ScanRead(String value, long seenMs) {
            this.value = value;
            this.seenMs = seenMs;
        }
    }

    static final class UprightFrameGeometry {
        final float cropLeft;
        final float cropTop;
        final float cropRight;
        final float cropBottom;
        final float cropWidth;
        final float cropHeight;
        final boolean valid;

        UprightFrameGeometry(float cropLeft, float cropTop,
                             float cropRight, float cropBottom) {
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropRight = cropRight;
            this.cropBottom = cropBottom;
            this.cropWidth = cropRight - cropLeft;
            this.cropHeight = cropBottom - cropTop;
            this.valid = cropWidth > 0f && cropHeight > 0f;
        }

        private UprightFrameGeometry() {
            cropLeft = 0f;
            cropTop = 0f;
            cropRight = 0f;
            cropBottom = 0f;
            cropWidth = 0f;
            cropHeight = 0f;
            valid = false;
        }

        static UprightFrameGeometry fullFrame(int width, int height) {
            return width > 0 && height > 0
                ? new UprightFrameGeometry(0f, 0f, width, height) : invalid();
        }

        static UprightFrameGeometry invalid() {
            return new UprightFrameGeometry();
        }

        float normalizedX(float x) {
            return (x - cropLeft) / cropWidth;
        }

        float normalizedY(float y) {
            return (y - cropTop) / cropHeight;
        }
    }

    private static final class LocatedTextCandidate {
        final SnScanRules.Candidate candidate;
        final int geometryScore;
        final boolean inGuide;
        final int encounter;

        LocatedTextCandidate(SnScanRules.Candidate candidate, int geometryScore,
                             boolean inGuide, int encounter) {
            this.candidate = candidate;
            this.geometryScore = geometryScore;
            this.inGuide = inGuide;
            this.encounter = encounter;
        }
    }

    private static final class SourceConfirmState {
        String value = "";
        int count = 0;
        long firstSeenMs = 0L;
        long lastSeenMs = 0L;

        void clear() {
            value = "";
            count = 0;
            firstSeenMs = 0L;
            lastSeenMs = 0L;
        }
    }

    private final class GuideOverlay extends View {
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF();

        GuideOverlay(android.content.Context context) {
            super(context);
            dimPaint.setColor(0x72000000);
            dotPaint.setColor(Color.WHITE);
            dotPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float frameW = w * GUIDE_WIDTH_FRACTION;
            float frameH = h * GUIDE_HEIGHT_FRACTION;
            float left = (w - frameW) / 2f;
            float top = h * GUIDE_TOP_FRACTION;
            frame.set(left, top, left + frameW, top + frameH);

            canvas.drawRect(0, 0, w, frame.top, dimPaint);
            canvas.drawRect(0, frame.bottom, w, h, dimPaint);
            canvas.drawRect(0, frame.top, frame.left, frame.bottom, dimPaint);
            canvas.drawRect(frame.right, frame.top, w, frame.bottom, dimPaint);

            float radius = dp(5);
            float inset = dp(4);
            canvas.drawCircle(frame.left + inset, frame.top + inset, radius, dotPaint);
            canvas.drawCircle(frame.right - inset, frame.top + inset, radius, dotPaint);
            canvas.drawCircle(frame.left + inset, frame.bottom - inset, radius, dotPaint);
            canvas.drawCircle(frame.right - inset, frame.bottom - inset, radius, dotPaint);
        }
    }
}
