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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScannerActivity extends ComponentActivity {
    private static final int REQ_CAMERA_PERMISSION = 4101;
    private static final String WHITE_LABEL_MODE_PRIMARY = "primary";
    private static final String WHITE_LABEL_MODE_FALLBACK = "fallback";
    private static final long AUTO_TEXT_PRIMARY_DELAY_MS = 350L;
    private static final long AUTO_TEXT_FALLBACK_DELAY_MS = 1200L;
    private static final long AUTO_TEXT_INTERVAL_MS = 650L;
    private static final long SCAN_RESULT_BUFFER_MS = 1250L;
    private static final long SCAN_CONFIRM_WINDOW_MS = 550L;
    private static final long SCAN_SINGLE_SOURCE_FALLBACK_MS = 1900L;
    private static final int BARCODE_CONFIRM_COUNT = 2;
    private static final int TEXT_CONFIRM_COUNT = 2;
    private static final int CROSS_CONFIRM_COUNT = 2;
    private static final int MAX_SCAN_QUEUE_SIZE = 6;
    private static final long AUTO_ZOOM_DELAY_MS = 1600L;
    private static final long AUTO_ZOOM_STEP_MS = 1900L;
    private static final Pattern SN_AFTER_LABEL = Pattern.compile("(?:^|[^A-Z0-9])S\\s*/?\\s*N\\s*[:：-]?\\s*([A-Z0-9][A-Z0-9\\s._/-]{5,32})");
    private static final Pattern AFC_SN = Pattern.compile("(AFC[A-Z0-9]{7,28})");
    private static final Pattern GENERAL_SN = Pattern.compile("([A-Z]{2,}[A-Z0-9]{6,28})");
    private static final Collection<String> BAD_WORDS = Arrays.asList(
        "WARNING", "AVERTISSEMENT", "MODEL", "MODELE", "INPUT", "OUTPUT", "CONTAINS",
        "INDOOR", "HOUSEHOLD", "INSTRUCTIONS", "VIETNAM",
        "UNITED", "GERMANY", "CANICES", "LITHIUM", "BATTERY"
    );

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

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private BarcodeScanner barcodeScanner;
    private TextRecognizer textRecognizer;
    private boolean finished = false;
    private boolean rejectNumericOnly = true;
    private boolean ignoredNumericScan = false;
    private boolean ignoredWrongLengthScan = false;
    private boolean manualTextRequested = false;
    private boolean ocrOnly = false;
    private boolean zoomed = false;
    private int autoZoomStep = 0;
    private int expectedSnLength = 0;
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
    private String whiteLabelMode = "";
    private String lang = "zh";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        whiteLabelMode = safe(intent.getStringExtra("WHITE_LABEL_MODE"));
        rejectNumericOnly = intent.getBooleanExtra("REJECT_NUMERIC_ONLY", true);
        ocrOnly = intent.getBooleanExtra("OCR_ONLY", false);
        expectedSnLength = Math.max(0, intent.getIntExtra("EXPECTED_SN_LENGTH", 0));
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
        titleText.setText(s("\u626b\u63cf\u673a\u5668 SN", "Scan machine SN", "Escanear SN de la m\u00e1quina"));
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
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        controls.addView(cancelButton, cancelParams);
        cancelButton.setOnClickListener(v -> cancelScan());

        shutterButton = shutterButton();
        FrameLayout.LayoutParams shutterParams = new FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER);
        controls.addView(shutterButton, shutterParams);
        shutterButton.setOnClickListener(v -> requestTextNow());

        zoomButton = controlButton(s("\u653e\u5927", "Zoom", "Acercar"), dp(100), true);
        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(dp(112), dp(64), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
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

    private void bindCameraUseCases() {
        if (cameraProvider == null || finished) return;
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
        camera = cameraProvider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        );
        focusCenterSoon();
        Diagnostics.append(this, "MLKit scanner started mode=" + whiteLabelMode + " ocrOnly=" + ocrOnly);
    }

    @ExperimentalGetImage
    private void analyzeImage(ImageProxy imageProxy) {
        if (finished || !processing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            finishFrame(imageProxy);
            return;
        }
        long now = System.currentTimeMillis();
        InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
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
                if (!finished) maybeAutoZoom(now);
                finishFrame(imageProxy);
            }
        };
        if (runBarcode) {
            barcodeScanner.process(image)
                .addOnSuccessListener(analyzerExecutor, barcodes -> {
                    String value = barcodeResult(barcodes, imageProxy.getWidth(), imageProxy.getHeight());
                    if (!value.isEmpty()) finishWithResult(value, "MLKIT_BARCODE");
                })
                .addOnFailureListener(analyzerExecutor, exc -> Diagnostics.append(this, "MLKit barcode failed: " + concise(exc)))
                .addOnCompleteListener(analyzerExecutor, task -> finishTask.run());
        }
        if (runText) {
            boolean manualText = manualTextRequested;
            manualTextRequested = false;
            lastTextAttemptMs = now;
            textRecognizer.process(image)
                .addOnSuccessListener(analyzerExecutor, text -> {
                    String sn = textResult(text);
                    if (!sn.isEmpty()) finishWithResult(sn, "MLKIT_TEXT");
                    else if (manualText) showToast(s("\u6ca1\u6709\u8bfb\u5230 SN\uff0c\u8bf7\u5bf9\u51c6\u6807\u7b7e\u518d\u8bd5",
                        "No SN detected. Aim at the label and retry.",
                        "No se detect\u00f3 SN. Apunte a la etiqueta e intente de nuevo."));
                })
                .addOnFailureListener(analyzerExecutor, exc -> {
                    Diagnostics.append(this, "MLKit text failed: " + concise(exc));
                    if (manualText) showToast(s("\u6587\u5b57\u8bc6\u522b\u5931\u8d25", "Text recognition failed", "Error de reconocimiento de texto"));
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
        if (manualTextRequested) return true;
        if (!isAutoTextEnabled()) return false;
        long elapsed = now - scannerStartedMs;
        long delay = isWhiteLabelPrimary() || ocrOnly ? AUTO_TEXT_PRIMARY_DELAY_MS : AUTO_TEXT_FALLBACK_DELAY_MS;
        return elapsed >= delay && now - lastTextAttemptMs >= AUTO_TEXT_INTERVAL_MS;
    }

    private String barcodeResult(List<Barcode> barcodes, int imageWidth, int imageHeight) {
        if (barcodes == null || barcodes.isEmpty()) return "";
        String bestValue = "";
        int bestScore = Integer.MIN_VALUE;
        for (Barcode barcode : barcodes) {
            String value = normalizeBarcodePayload(firstNonEmpty(barcode.getRawValue(), barcode.getDisplayValue()));
            if (value.isEmpty()) continue;
            if (rejectNumericOnly && isPureNumeric(value)) {
                ignoredNumericScan = true;
                updateStatus();
                Diagnostics.append(this, "MLKit scanner ignored numeric-only barcode format=" + barcode.getFormat() + " length=" + value.length());
                continue;
            }
            if (expectedSnLength > 0 && value.length() != expectedSnLength) {
                ignoredWrongLengthScan = true;
                updateStatus();
                Diagnostics.append(this, "MLKit scanner ignored wrong-length barcode format=" + barcode.getFormat() + " length=" + value.length() + " expected=" + expectedSnLength);
                continue;
            }
            int score = barcodeScore(value, barcode.getBoundingBox(), imageWidth, imageHeight);
            if (score > bestScore) {
                bestScore = score;
                bestValue = value;
            }
        }
        if (!bestValue.isEmpty()) return bestValue;
        for (Barcode barcode : barcodes) {
            Rect box = barcode.getBoundingBox();
            if (box != null) maybeZoomForBox(box, imageWidth, imageHeight);
        }
        return "";
    }

    private int barcodeScore(String value, Rect box, int imageWidth, int imageHeight) {
        int score = 0;
        if (expectedSnLength > 0 && value.length() == expectedSnLength) score += 1000;
        if (isLikelySn(value)) score += 300;
        if (value.startsWith("AFC")) score += 120;
        if (box != null && imageWidth > 0 && imageHeight > 0) {
            float centerX = box.centerX() / (float) imageWidth - 0.5f;
            float centerY = box.centerY() / (float) imageHeight - 0.5f;
            float distance = Math.abs(centerX) + Math.abs(centerY);
            score += Math.max(0, 180 - Math.round(distance * 280));
        }
        return score;
    }

    private String textResult(Text text) {
        if (text == null) return "";
        List<SnCandidate> candidates = new ArrayList<>();
        addTextCandidates(candidates, text.getText(), 0);
        for (Text.TextBlock block : text.getTextBlocks()) {
            addTextCandidates(candidates, block.getText(), 4);
            for (Text.Line line : block.getLines()) {
                addTextCandidates(candidates, line.getText(), 12);
            }
        }
        if (candidates.isEmpty()) return "";
        candidates.sort(Comparator.comparingInt((SnCandidate c) -> c.score).reversed());
        SnCandidate best = candidates.get(0);
        Diagnostics.append(this, "MLKit text SN candidate length=" + best.value.length() + " score=" + best.score);
        return best.value;
    }

    private void addTextCandidates(List<SnCandidate> candidates, String raw, int lineBonus) {
        String line = raw == null ? "" : raw.toUpperCase(Locale.US);
        if (line.trim().isEmpty()) return;
        Matcher labeled = SN_AFTER_LABEL.matcher(line);
        while (labeled.find()) {
            addCandidate(candidates, labeled.group(1), 80 + lineBonus);
        }
        String compact = normalizeOcrLine(line);
        Matcher afc = AFC_SN.matcher(compact);
        while (afc.find()) {
            addCandidate(candidates, afc.group(1), 58 + lineBonus);
        }
        Matcher general = GENERAL_SN.matcher(compact);
        while (general.find()) {
            addCandidate(candidates, general.group(1), lineBonus);
        }
    }

    private void addCandidate(List<SnCandidate> candidates, String raw, int bonus) {
        String value = normalizeOcrSn(raw);
        if (!isLikelySn(value)) return;
        if (expectedSnLength > 0 && value.length() != expectedSnLength) return;
        int score = bonus + Math.min(24, value.length());
        if (value.startsWith("AFC")) score += 60;
        if (Pattern.compile("\\d").matcher(value).find()) score += 16;
        if (value.length() >= 12 && value.length() <= 20) score += 14;
        candidates.add(new SnCandidate(value, score));
    }

    private boolean isLikelySn(String value) {
        if (value == null || value.length() < 8 || value.length() > 32) return false;
        if (isPureNumeric(value)) return false;
        if (!Pattern.compile("[A-Z]").matcher(value).find()) return false;
        if (!Pattern.compile("\\d").matcher(value).find()) return false;
        for (String bad : BAD_WORDS) {
            if (value.contains(bad)) return false;
        }
        return true;
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

    private void maybeZoomForBox(Rect box, int imageWidth, int imageHeight) {
        if (camera == null || zoomed || box == null || imageWidth <= 0 || imageHeight <= 0) return;
        float imageArea = Math.max(1f, (float) imageWidth * (float) imageHeight);
        float boxArea = Math.max(1f, (float) box.width() * (float) box.height());
        if (boxArea / imageArea < 0.035f) {
            zoomed = true;
            camera.getCameraControl().setLinearZoom(0.36f);
            updateStatus();
            focusCenterSoon();
        }
    }

    private void requestTextNow() {
        manualTextRequested = true;
        setStatus(s("\u6b63\u5728\u8bfb\u53d6 SN...", "Reading SN...", "Leyendo SN..."));
        focusCenterSoon();
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
        if (!pendingScanValue.isEmpty()) {
            return s("\u6b63\u5728\u4ea4\u53c9\u786e\u8ba4 SN ", "Cross-checking SN ", "Verificando SN ")
                + pendingScanCount + "/" + CROSS_CONFIRM_COUNT
                + s("\uff0c\u8bf7\u4fdd\u6301\u5bf9\u51c6", ", keep it aligned", ", mant\u00e9ngalo alineado");
        }
        if (ignoredNumericScan) return s("\u5df2\u5ffd\u7565\u7eaf\u6570\u5b57\u7801\uff0c\u7ee7\u7eed\u627e SN",
            "Ignored numeric-only code, still looking for SN",
            "Se ignor\u00f3 el c\u00f3digo solo num\u00e9rico, buscando SN");
        if (ignoredWrongLengthScan && expectedSnLength > 0) return s("\u5df2\u5ffd\u7565\u975e ", "Ignored SN not ", "Se ignor\u00f3 SN que no tiene ")
            + expectedSnLength + s(" \u4f4d SN\uff0c\u7ee7\u7eed\u626b\u63cf", " chars, still scanning", " caracteres, escaneando");
        if (ocrOnly) return s("\u5bf9\u51c6 SN \u6807\u7b7e\uff0c\u5c06\u76f4\u63a5\u672c\u5730\u8bfb\u53d6\u6587\u5b57",
            "Aim at the SN label; text will be read locally",
            "Apunte a la etiqueta SN; el texto se leer\u00e1 localmente");
        if (zoomed) return s("\u5df2\u653e\u5927\uff0c\u4fdd\u6301\u6807\u7b7e\u6e05\u6670", "Zoomed in, keep the label sharp", "Acercado, mantenga la etiqueta n\u00edtida");
        if (isWhiteLabelPrimary()) return s("\u9ed1\u8272\u673a\u578b\uff1a\u4f18\u5148\u672c\u5730\u8bfb\u53d6\u767d\u6846 SN",
            "Dark model: reading white-box SN locally first",
            "Modelo oscuro: leyendo primero el SN del recuadro blanco");
        if (isWhiteLabelFallback()) return s("\u626b\u4e0d\u5230\u65f6\u4f1a\u672c\u5730\u8bfb\u53d6\u6807\u7b7e SN",
            "If scanning fails, the label SN is read locally",
            "Si no se escanea, el SN de la etiqueta se lee localmente");
        return s("\u5bf9\u51c6\u7801\u9762\u6216 SN \u6807\u7b7e", "Aim at the code or SN label", "Apunte al c\u00f3digo o la etiqueta SN");
    }

    private boolean isAutoTextEnabled() {
        return ocrOnly || isWhiteLabelPrimary() || isWhiteLabelFallback();
    }

    private boolean isWhiteLabelPrimary() {
        return WHITE_LABEL_MODE_PRIMARY.equals(whiteLabelMode);
    }

    private boolean isWhiteLabelFallback() {
        return WHITE_LABEL_MODE_FALLBACK.equals(whiteLabelMode);
    }

    private void finishWithResult(String text, String format) {
        String value = normalizeBarcodePayload(text);
        if (finished || value.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (expectedSnLength > 0 && value.length() != expectedSnLength) {
            ignoredWrongLengthScan = true;
            updateStatus();
            Diagnostics.append(this, "MLKit scanner ignored wrong-length SN format=" + format + " length=" + value.length() + " expected=" + expectedSnLength);
            return;
        }
        if (!confirmStableResult(value, format, now)) return;
        finished = true;
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
        finished = true;
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
        finished = true;
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

    private static String normalizeBarcodePayload(String text) {
        String value = text == null ? "" : text.trim();
        value = value.toUpperCase(Locale.US)
            .replaceAll("\\s+", "")
            .replaceAll("^SN[:\uff1a-]?", "");
        return value;
    }

    private static String normalizeOcrLine(String text) {
        if (text == null) return "";
        return text.toUpperCase(Locale.US).replaceAll("[^A-Z0-9:：/-]", "");
    }

    private static String normalizeOcrSn(String text) {
        if (text == null) return "";
        return text.toUpperCase(Locale.US)
            .replaceAll("[^A-Z0-9]", "")
            .replaceAll("^SN", "");
    }

    private static boolean isPureNumeric(String value) {
        return value != null && value.matches("\\d+");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String concise(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 240 ? message.substring(0, 240) + "..." : message;
    }

    private static final class SnCandidate {
        final String value;
        final int score;

        SnCandidate(String value, int score) {
            this.value = value;
            this.score = score;
        }
    }

    private static final class ScanRead {
        final String value;
        final long seenMs;

        ScanRead(String value, long seenMs) {
            this.value = value;
            this.seenMs = seenMs;
        }
    }

    private static final class SourceConfirmState {
        String value = "";
        int count = 0;
        long firstSeenMs = 0L;
        long lastSeenMs = 0L;
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
            float frameW = w * 0.86f;
            float frameH = h * 0.30f;
            float left = (w - frameW) / 2f;
            float top = h * 0.36f;
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
