package com.autoformkit.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/** App-owned still-photo flow; it deliberately avoids vendor ACTION_IMAGE_CAPTURE Activities. */
public class CaptureActivity extends ComponentActivity {
    static final String EXTRA_CONTINUOUS_CAPTURE = "continuousCapture";
    static final String EXTRA_OPENING_NOTICE = "openingNotice";
    private static final String STATE_REVIEWING = "captureReviewing";
    private static final String STATE_NOTICE_SHOWN = "captureNoticeShown";
    private static final String STATE_TARGET_ROTATION = "captureTargetRotation";
    private static final String STATE_CAPTURED_ROTATION = "capturedPhotoRotation";
    private static final int REQ_CAMERA_PERMISSION = 4001;
    private static final long ORIENTATION_SAMPLE_WAIT_MS = 450L;

    private PreviewView previewView;
    private ImageView reviewImage;
    private FrameLayout reviewStage;
    private FrameLayout reviewControlsStage;
    private FrameLayout cameraRoot;
    private FrameLayout orientedChrome;
    private FrameLayout openingNoticeOverlay;
    private FrameLayout captureButton;
    private LinearLayout reviewActions;
    private ImageButton retakeButton;
    private ImageButton confirmButton;
    private TextView statusText;
    private OrientationEventListener orientationListener;
    private ProcessCameraProvider cameraProvider;
    private Preview previewUseCase;
    private ImageCapture imageCapture;
    private File outputFile;
    private Uri outputUri;
    private OutputStream outputStream;
    private Bitmap reviewBitmap;
    private String lang = "zh";
    private String openingNotice = "";
    private boolean captureInFlight;
    private boolean reviewing;
    private boolean continuousCapture;
    private boolean openingNoticeShown;
    private boolean hasFreshOrientationSample;
    private boolean cameraActivityStarted;
    private boolean cameraBindWaitingForLayout;
    private boolean cameraViewportRebindPending;
    private boolean finished;
    private int boundViewportWidth;
    private int boundViewportHeight;
    private int boundDisplayRotationDegrees = -1;
    private int baseDisplayRotation = -1;
    private int stableCaptureRotation = -1;
    private int capturedRotation = -1;
    private int reviewGeneration;
    private int orientationSessionGeneration;
    private ViewTreeObserver.OnPreDrawListener cameraPreDrawListener;
    private ViewTreeObserver cameraPreDrawObserver;
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener =
        new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (previewView == null) return;
                previewView.post(() -> {
                    updateBaseDisplayRotation();
                    applyOrientedChrome();
                    handleDisplayGeometryChanged(displayId);
                });
            }
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        Intent intent = getIntent();
        lang = intent == null || intent.getStringExtra("lang") == null
            ? "zh" : intent.getStringExtra("lang");
        continuousCapture = intent != null
            && intent.getBooleanExtra(EXTRA_CONTINUOUS_CAPTURE, false);
        openingNotice = intent == null ? "" : intent.getStringExtra(EXTRA_OPENING_NOTICE);
        if (openingNotice == null) openingNotice = "";
        openingNoticeShown = savedInstanceState != null
            && savedInstanceState.getBoolean(STATE_NOTICE_SHOWN, false);
        stableCaptureRotation = savedInstanceState == null ? -1
            : savedInstanceState.getInt(STATE_TARGET_ROTATION, -1);
        capturedRotation = savedInstanceState == null ? -1
            : savedInstanceState.getInt(STATE_CAPTURED_ROTATION, -1);
        String fileName = safeFileName(intent == null
            ? "" : intent.getStringExtra("fileName"));
        outputUri = intent == null ? null
            : intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        String uriName = outputUri == null ? "" : safeFileName(outputUri.getLastPathSegment());
        if (fileName.isEmpty() || !fileName.equals(uriName)
                || !"content".equals(outputUri.getScheme())
                || !(getPackageName() + ".photo").equals(outputUri.getAuthority())) {
            toast(t("photo_target_invalid"));
            finishCanceled();
            return;
        }
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            toast(t("photo_dir_failed"));
            finishCanceled();
            return;
        }
        outputFile = new File(dir, fileName);
        buildUi(intent == null ? "" : intent.getStringExtra("label"));
        createOrientationListener();
        boolean restoreReview = savedInstanceState != null
            && savedInstanceState.getBoolean(STATE_REVIEWING, false)
            && outputFile.isFile() && outputFile.length() > 0L;
        if (restoreReview) {
            showReview();
        } else if (hasCameraPermission()) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        }
    }

    private void buildUi(String label) {
        FrameLayout root = new FrameLayout(this);
        cameraRoot = root;
        root.setBackgroundColor(0xff000000);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        reviewStage = new FrameLayout(this);
        reviewStage.setVisibility(View.GONE);
        root.addView(reviewStage, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ));

        reviewImage = new ImageView(this);
        reviewImage.setBackgroundColor(0xff000000);
        reviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        reviewImage.setContentDescription(t("review_image"));
        reviewStage.addView(reviewImage, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FrameLayout header = new FrameLayout(this);
        header.setBackground(scrim(true));
        root.addView(header, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(116), Gravity.TOP));

        FrameLayout footer = new FrameLayout(this);
        footer.setBackground(scrim(false));
        root.addView(footer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(184), Gravity.BOTTOM));

        captureButton = new FrameLayout(this);
        captureButton.setContentDescription(t("photo"));
        captureButton.setClickable(true);
        captureButton.setFocusable(true);
        captureButton.setContentDescription(t("capture_action"));
        captureButton.setBackground(circle(0x18000000, 0xffffffff, 4));
        captureButton.setElevation(dp(6));
        View shutter = new View(this);
        shutter.setBackground(circle(0xffffffff, 0x00ffffff, 0));
        captureButton.addView(shutter, new FrameLayout.LayoutParams(
            dp(68), dp(68), Gravity.CENTER));
        captureButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            takePicture();
        });
        FrameLayout.LayoutParams captureParams = new FrameLayout.LayoutParams(
            dp(88), dp(88), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        captureParams.bottomMargin = dp(24);
        footer.addView(captureButton, captureParams);

        reviewControlsStage = new FrameLayout(this);
        reviewControlsStage.setClickable(false);
        reviewControlsStage.setFocusable(false);
        root.addView(reviewControlsStage, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ));

        reviewActions = new LinearLayout(this);
        reviewActions.setOrientation(LinearLayout.HORIZONTAL);
        reviewActions.setGravity(Gravity.CENTER);
        reviewActions.setBackground(reviewBarBackground());
        reviewActions.setClipToOutline(true);
        reviewActions.setElevation(dp(2));
        retakeButton = reviewButton(R.drawable.ic_retake_camera, false,
            t("retake"), v -> retakePhoto());
        confirmButton = reviewButton(R.drawable.ic_confirm_camera, true,
            t("confirm_photo"), v -> confirmPhoto());
        LinearLayout.LayoutParams retakeParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        reviewActions.addView(retakeButton, retakeParams);

        View divider = new View(this);
        divider.setBackgroundColor(0x29ffffff);
        reviewActions.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(36)));

        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        reviewActions.addView(confirmButton, confirmParams);
        FrameLayout.LayoutParams reviewParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(84),
            Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        reviewParams.leftMargin = dp(18);
        reviewParams.rightMargin = dp(18);
        reviewParams.bottomMargin = dp(18);
        reviewControlsStage.addView(reviewActions, reviewParams);
        reviewActions.setVisibility(View.GONE);

        orientedChrome = new FrameLayout(this);
        orientedChrome.setClipChildren(false);
        orientedChrome.setClipToPadding(false);
        orientedChrome.setClickable(false);
        orientedChrome.setFocusable(false);
        root.addView(orientedChrome, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ));

        ImageButton close = new ImageButton(this);
        close.setContentDescription(t("cancel"));
        close.setImageResource(R.drawable.ic_close_camera);
        close.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        close.setPadding(dp(14), dp(14), dp(14), dp(14));
        close.setBackground(circle(0x42000000, 0x55ffffff, 1));
        close.setOnClickListener(v -> finishCanceled());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
            dp(52), dp(52), Gravity.LEFT | Gravity.TOP);
        closeParams.leftMargin = dp(14);
        closeParams.topMargin = dp(28);
        orientedChrome.addView(close, closeParams);

        TextView title = new TextView(this);
        title.setText(label == null || label.isEmpty() ? t("photo") : label);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setShadowLayer(dp(2), 0, dp(1), 0xaa000000);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP);
        titleParams.leftMargin = dp(78);
        titleParams.rightMargin = dp(78);
        titleParams.topMargin = dp(24);
        orientedChrome.addView(title, titleParams);

        statusText = new TextView(this);
        statusText.setText(t("camera_starting"));
        statusText.setTextColor(0xeeffffff);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackground(roundedRect(0x70000000, 18));
        statusText.setPadding(dp(14), 0, dp(14), 0);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38),
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintParams.leftMargin = dp(20);
        hintParams.rightMargin = dp(20);
        hintParams.bottomMargin = dp(136);
        orientedChrome.addView(statusText, hintParams);

        setCaptureEnabled(false);

        SystemBarInsets.reserveCameraBars(
            root, header, footer, orientedChrome, reviewControlsStage);
        setContentView(root);
        SystemBarInsets.requestWhenAttached(root);
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left == oldRight - oldLeft
                    && bottom - top == oldBottom - oldTop) return;
            updateBaseDisplayRotation();
            applyOrientedChrome();
        });
        root.post(() -> {
            updateBaseDisplayRotation();
            applyOrientedChrome();
        });
    }

    /** Rotates only lightweight chrome; preview, shutter, and review controls never move. */
    private void applyOrientedChrome() {
        if (cameraRoot == null || orientedChrome == null || reviewStage == null
                || reviewControlsStage == null || statusText == null || cameraRoot.getWidth() <= 0
                || cameraRoot.getHeight() <= 0 || baseDisplayRotation < 0) return;
        int targetRotation = validSurfaceRotation(stableCaptureRotation)
            ? stableCaptureRotation : baseDisplayRotation;
        int delta = relativeRotationDegrees(targetRotation, baseDisplayRotation);
        boolean quarterTurn = delta == 90 || delta == 270;
        int width = quarterTurn ? cameraRoot.getHeight() : cameraRoot.getWidth();
        int height = quarterTurn ? cameraRoot.getWidth() : cameraRoot.getHeight();
        applyRotatedStage(orientedChrome, width, height, delta);
        applyRotatedStage(reviewControlsStage, width, height, delta);
        SystemBarInsets.rotateCameraOverlayInsets(orientedChrome, delta);
        SystemBarInsets.rotateCameraOverlayInsets(reviewControlsStage, delta);

        int reviewTarget = reviewing && validSurfaceRotation(capturedRotation)
            ? capturedRotation : targetRotation;
        int reviewDelta = relativeRotationDegrees(reviewTarget, baseDisplayRotation);
        boolean reviewQuarterTurn = reviewDelta == 90 || reviewDelta == 270;
        applyRotatedStage(reviewStage,
            reviewQuarterTurn ? cameraRoot.getHeight() : cameraRoot.getWidth(),
            reviewQuarterTurn ? cameraRoot.getWidth() : cameraRoot.getHeight(),
            reviewDelta);

        FrameLayout.LayoutParams hintParams =
            (FrameLayout.LayoutParams) statusText.getLayoutParams();
        boolean physicalLandscape = width > height;
        int desiredGravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        int bottomMargin = dp(reviewing ? 120
            : delta == 0 && !physicalLandscape ? 136 : 12);
        if (!reviewing && delta == 0 && physicalLandscape) {
            desiredGravity = Gravity.BOTTOM | Gravity.LEFT;
        }
        int leftMargin = dp(20);
        int rightMargin = desiredGravity == (Gravity.BOTTOM | Gravity.LEFT) ? 0 : dp(20);
        if (hintParams.gravity != desiredGravity
                || hintParams.bottomMargin != bottomMargin
                || hintParams.leftMargin != leftMargin
                || hintParams.rightMargin != rightMargin) {
            hintParams.gravity = desiredGravity;
            hintParams.topMargin = 0;
            hintParams.bottomMargin = bottomMargin;
            hintParams.leftMargin = leftMargin;
            hintParams.rightMargin = rightMargin;
            statusText.setLayoutParams(hintParams);
        }
    }

    private void applyRotatedStage(View stage, int width, int height, int delta) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) stage.getLayoutParams();
        if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) {
            params.width = width;
            params.height = height;
            params.gravity = Gravity.CENTER;
            stage.setLayoutParams(params);
        }
        float rotation = delta == 270 ? -90f : delta;
        if (stage.getRotation() != rotation) stage.setRotation(rotation);
    }

    private void createOrientationListener() {
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (!cameraActivityStarted || orientation == ORIENTATION_UNKNOWN) return;
                boolean firstFreshSample = !hasFreshOrientationSample;
                hasFreshOrientationSample = true;
                int next = snapTargetRotation(orientation, stableCaptureRotation);
                if (validSurfaceRotation(next) && next != stableCaptureRotation) {
                    stableCaptureRotation = next;
                    applyOrientedChrome();
                }
                if (!captureInFlight && imageCapture != null) {
                    setImageCaptureTargetRotation(imageCapture, currentCaptureTargetRotation());
                    if (firstFreshSample && !cameraViewportRebindPending && !reviewing) {
                        setStatus("capture_hint", false);
                        showOpeningNoticeIfNeeded();
                    }
                }
            }
        };
    }

    private void updateBaseDisplayRotation() {
        Display display = previewView == null ? null : previewView.getDisplay();
        if (display == null) return;
        int rotation = display.getRotation();
        if (!validSurfaceRotation(rotation)) return;
        boolean changed = baseDisplayRotation != rotation;
        baseDisplayRotation = rotation;
        if (!validSurfaceRotation(stableCaptureRotation)) {
            stableCaptureRotation = rotation;
            changed = true;
        }
        if (changed) applyOrientedChrome();
    }

    private void allowOrientationFallbackIfNeeded(int generation) {
        if (!cameraActivityStarted || generation != orientationSessionGeneration
                || hasFreshOrientationSample) return;
        // A flat phone can legitimately produce ORIENTATION_UNKNOWN indefinitely.
        hasFreshOrientationSample = true;
        if (!captureInFlight && !reviewing && imageCapture != null
                && !cameraViewportRebindPending) {
            setImageCaptureTargetRotation(imageCapture, currentCaptureTargetRotation());
            setStatus("capture_hint", false);
            showOpeningNoticeIfNeeded();
        }
    }

    private int currentCaptureTargetRotation() {
        if (validSurfaceRotation(stableCaptureRotation)) return stableCaptureRotation;
        if (validSurfaceRotation(baseDisplayRotation)) return baseDisplayRotation;
        Display display = previewView == null ? null : previewView.getDisplay();
        return display == null ? Surface.ROTATION_0 : display.getRotation();
    }

    private void setImageCaptureTargetRotation(ImageCapture capture, int rotation) {
        if (capture == null) return;
        if (rotation == Surface.ROTATION_90) {
            capture.setTargetRotation(Surface.ROTATION_90);
        } else if (rotation == Surface.ROTATION_180) {
            capture.setTargetRotation(Surface.ROTATION_180);
        } else if (rotation == Surface.ROTATION_270) {
            capture.setTargetRotation(Surface.ROTATION_270);
        } else {
            capture.setTargetRotation(Surface.ROTATION_0);
        }
    }

    static int snapTargetRotation(int orientation, int currentRotation) {
        return CameraOrientationRules.snapSurfaceRotation(orientation, currentRotation);
    }

    static int relativeRotationDegrees(int targetRotation, int baseRotation) {
        return CameraOrientationRules.relativeDegrees(targetRotation, baseRotation);
    }

    private static boolean validSurfaceRotation(int rotation) {
        return CameraOrientationRules.isSurfaceRotation(rotation);
    }

    private GradientDrawable circle(int fill, int stroke, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(fill);
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), stroke);
        return drawable;
    }

    private ImageButton reviewButton(int icon, boolean primary, String description,
                                     View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(48), dp(24), dp(48), dp(24));
        button.setContentDescription(description);
        button.setColorFilter(primary ? 0xffa7f3d0 : 0xfff8fafc);
        button.setBackground(reviewButtonBackground());
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(listener);
        return button;
    }

    private StateListDrawable reviewButtonBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
            roundedRect(0x26ffffff, 14));
        states.addState(new int[]{android.R.attr.state_focused},
            roundedRect(0x1cffffff, 14));
        states.addState(new int[]{}, roundedRect(0x00000000, 14));
        return states;
    }

    private GradientDrawable reviewBarBackground() {
        GradientDrawable drawable = roundedRect(0xb20b0f14, 16);
        drawable.setStroke(dp(1), 0x29ffffff);
        return drawable;
    }

    private GradientDrawable roundedRect(int fill, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable scrim(boolean top) {
        int[] colors = top
            ? new int[]{0xb8000000, 0x00000000}
            : new int[]{0x00000000, 0xc0000000};
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
    }

    private void setStatus(String key, boolean announce) {
        if (statusText == null) return;
        statusText.setText(t(key));
        if (announce) statusText.announceForAccessibility(statusText.getText());
    }

    private void setCaptureEnabled(boolean enabled) {
        if (captureButton == null) return;
        captureButton.setEnabled(enabled);
        captureButton.setAlpha(enabled ? 1f : 0.45f);
        captureButton.setContentDescription(enabled
            ? t("capture_action")
            : t(captureInFlight ? "saving_photo" : "camera_starting"));
    }

    private void setReviewActionsEnabled(boolean enabled) {
        if (retakeButton != null) {
            retakeButton.setEnabled(enabled);
            retakeButton.setAlpha(enabled ? 1f : 0.5f);
        }
        if (confirmButton != null) {
            confirmButton.setEnabled(enabled);
            confirmButton.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private void showReview() {
        if (finished || outputFile == null || !outputFile.isFile()
                || outputFile.length() <= 0L) {
            captureFailed("empty output");
            return;
        }
        reviewing = true;
        captureInFlight = false;
        cameraViewportRebindPending = false;
        applyOrientedChrome();
        unbindOwnCameraUseCases();
        captureButton.setVisibility(View.GONE);
        reviewActions.setVisibility(View.VISIBLE);
        setReviewActionsEnabled(false);
        setStatus("preparing_review", true);
        int generation = ++reviewGeneration;
        new Thread(() -> {
            Bitmap decoded = null;
            String failure = "";
            try {
                decoded = PrivateJpegImporter.decodePreview(outputFile);
            } catch (Exception error) {
                failure = concise(error);
            }
            Bitmap ready = decoded;
            String errorText = failure;
            runOnUiThread(() -> {
                if (finished || !reviewing || generation != reviewGeneration) {
                    if (ready != null && !ready.isRecycled()) ready.recycle();
                    return;
                }
                if (ready == null) {
                    reviewing = false;
                    capturedRotation = -1;
                    applyOrientedChrome();
                    reviewActions.setVisibility(View.GONE);
                    captureButton.setVisibility(View.VISIBLE);
                    reviewStage.setVisibility(View.GONE);
                    deleteOutput();
                    String reason = errorText.isEmpty() ? "preview unavailable" : errorText;
                    Diagnostics.append(CaptureActivity.this,
                        "Internal photo review failed: " + reason);
                    toast(t("photo_preview_failed") + ": " + reason);
                    setStatus("camera_starting", false);
                    setCaptureEnabled(false);
                    if (cameraProvider == null) startCamera();
                    else scheduleCameraViewportRebind();
                    return;
                }
                clearReviewBitmap();
                reviewBitmap = ready;
                reviewImage.setImageBitmap(reviewBitmap);
                reviewStage.setVisibility(View.VISIBLE);
                applyOrientedChrome();
                setStatus("review_photo", true);
                setReviewActionsEnabled(true);
                confirmButton.requestFocus();
            });
        }, "photo-review-decode").start();
    }

    private void retakePhoto() {
        if (finished || !reviewing) return;
        reviewing = false;
        capturedRotation = -1;
        applyOrientedChrome();
        reviewGeneration++;
        setReviewActionsEnabled(false);
        reviewActions.setVisibility(View.GONE);
        clearReviewBitmap();
        reviewStage.setVisibility(View.GONE);
        deleteOutput();
        captureButton.setVisibility(View.VISIBLE);
        setStatus("camera_starting", false);
        setCaptureEnabled(false);
        if (cameraProvider == null) startCamera();
        else scheduleCameraViewportRebind();
    }

    private void confirmPhoto() {
        if (finished || !reviewing || outputFile == null
                || !outputFile.isFile() || outputFile.length() <= 0L) return;
        finished = true;
        setReviewActionsEnabled(false);
        Intent data = new Intent().setData(outputUri)
            .putExtra("photoPath", outputFile.getAbsolutePath())
            .putExtra(EXTRA_CONTINUOUS_CAPTURE, continuousCapture);
        setResult(RESULT_OK, data);
        Diagnostics.append(this,
            "Internal CameraX photo confirmed bytes=" + outputFile.length());
        finishWithoutAnimation();
    }

    private void clearReviewBitmap() {
        if (reviewImage != null) reviewImage.setImageDrawable(null);
        Bitmap bitmap = reviewBitmap;
        reviewBitmap = null;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private void showOpeningNoticeIfNeeded() {
        if (finished || reviewing) return;
        if (!hasFreshOrientationSample) {
            setCaptureEnabled(false);
            return;
        }
        if (openingNoticeOverlay != null) {
            setCaptureEnabled(false);
            return;
        }
        if (openingNoticeShown || openingNotice.trim().isEmpty()) {
            setCaptureEnabled(imageCapture != null && !cameraViewportRebindPending);
            return;
        }
        openingNoticeShown = true;
        setCaptureEnabled(false);
        showOpeningNoticeOverlay();
    }

    private void showOpeningNoticeOverlay() {
        if (orientedChrome == null || openingNoticeOverlay != null) return;
        FrameLayout shade = new FrameLayout(this);
        openingNoticeOverlay = shade;
        shade.setBackgroundColor(0xc7000000);
        shade.setClickable(true);
        shade.setFocusable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(20));
        card.setBackground(roundedRect(0xf20b0f14, 18));
        card.setElevation(dp(8));

        TextView noticeTitle = new TextView(this);
        noticeTitle.setText(t("photo_notice"));
        noticeTitle.setTextColor(0xffffffff);
        noticeTitle.setTextSize(20);
        noticeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(noticeTitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView noticeMessage = new TextView(this);
        noticeMessage.setText(openingNotice);
        noticeMessage.setTextColor(0xe8ffffff);
        noticeMessage.setTextSize(16);
        noticeMessage.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(12);
        messageParams.bottomMargin = dp(20);
        card.addView(noticeMessage, messageParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button finishPhotos = noticeButton(t("finish_photos"), false);
        Button continuePhotos = noticeButton(t("continue_photo"), true);
        finishPhotos.setOnClickListener(v -> finishCanceled());
        continuePhotos.setOnClickListener(v -> {
            removeOpeningNoticeOverlay();
            if (!finished && !reviewing && hasFreshOrientationSample
                    && imageCapture != null && !cameraViewportRebindPending) {
                setCaptureEnabled(true);
            }
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
            0, dp(52), 1f);
        LinearLayout.LayoutParams continueParams = new LinearLayout.LayoutParams(
            0, dp(52), 1f);
        continueParams.leftMargin = dp(10);
        actions.addView(finishPhotos, actionParams);
        actions.addView(continuePhotos, continueParams);
        card.addView(actions, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        cardParams.leftMargin = dp(28);
        cardParams.rightMargin = dp(28);
        shade.addView(card, cardParams);
        orientedChrome.addView(shade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        noticeTitle.announceForAccessibility(
            noticeTitle.getText() + ". " + noticeMessage.getText());
        continuePhotos.requestFocus();
    }

    private Button noticeButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(primary ? 0xff052e2b : 0xfff8fafc);
        button.setBackground(roundedRect(primary ? 0xffa7f3d0 : 0x24ffffff, 12));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private void removeOpeningNoticeOverlay() {
        FrameLayout overlay = openingNoticeOverlay;
        openingNoticeOverlay = null;
        if (overlay != null && overlay.getParent() == orientedChrome) {
            orientedChrome.removeView(overlay);
        }
    }

    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (finished) return;
            try {
                cameraProvider = future.get();
                scheduleCameraViewportRebind();
            } catch (Exception error) {
                Diagnostics.append(this,
                    "Internal photo camera start failed: " + concise(error));
                toast(t("camera_open_failed") + ": " + concise(error));
                finishCanceled();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** Defers binding until the rotated PreviewView has its final measured geometry. */
    private void scheduleCameraViewportRebind() {
        if (previewView == null || finished || reviewing) return;
        cameraViewportRebindPending = true;
        if (cameraProvider == null || !cameraActivityStarted || captureInFlight) return;
        setStatus("camera_starting", false);
        setCaptureEnabled(false);
        if (cameraBindWaitingForLayout) return;

        cameraBindWaitingForLayout = true;
        cameraPreDrawListener = () -> {
            if (finished || reviewing || captureInFlight) {
                clearCameraBindWaiter();
                return true;
            }
            if (!isCameraViewReady()) return true;
            clearCameraBindWaiter();
            try {
                bindCameraUseCases();
            } catch (Exception error) {
                Diagnostics.append(CaptureActivity.this,
                    "Internal photo viewport bind failed: " + concise(error));
                toast(t("camera_open_failed") + ": " + concise(error));
                finishCanceled();
            }
            return true;
        };
        ViewTreeObserver observer = previewView.getViewTreeObserver();
        if (observer.isAlive()) {
            cameraPreDrawObserver = observer;
            observer.addOnPreDrawListener(cameraPreDrawListener);
            previewView.postInvalidateOnAnimation();
        } else {
            cameraBindWaitingForLayout = false;
            cameraPreDrawListener = null;
            cameraPreDrawObserver = null;
            previewView.post(this::scheduleCameraViewportRebind);
        }
    }

    private boolean isCameraViewReady() {
        return previewView != null && previewView.isAttachedToWindow()
            && previewView.getWidth() > 0 && previewView.getHeight() > 0
            && previewView.getDisplay() != null;
    }

    private void clearCameraBindWaiter() {
        ViewTreeObserver.OnPreDrawListener listener = cameraPreDrawListener;
        ViewTreeObserver observer = cameraPreDrawObserver;
        cameraPreDrawListener = null;
        cameraPreDrawObserver = null;
        cameraBindWaitingForLayout = false;
        if (listener != null && observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(listener);
        }
    }

    private void bindCameraUseCases() throws CameraInfoUnavailableException {
        if (cameraProvider == null || finished || reviewing) return;
        if (!isCameraViewReady()) {
            scheduleCameraViewportRebind();
            return;
        }
        Display display = previewView.getDisplay();
        if (display == null) {
            scheduleCameraViewportRebind();
            return;
        }
        int width = previewView.getWidth();
        int height = previewView.getHeight();
        int rotation = display.getRotation();
        int rotationDegrees = rotationDegrees(rotation);
        if (baseDisplayRotation != rotation) {
            baseDisplayRotation = rotation;
            if (!validSurfaceRotation(stableCaptureRotation)) {
                stableCaptureRotation = rotation;
            }
            applyOrientedChrome();
        }
        if (previewUseCase != null && imageCapture != null
                && width == boundViewportWidth && height == boundViewportHeight
                && rotationDegrees == boundDisplayRotationDegrees) {
            cameraViewportRebindPending = false;
            if (!captureInFlight) {
                setImageCaptureTargetRotation(imageCapture, currentCaptureTargetRotation());
            }
            setStatus("capture_hint", false);
            showOpeningNoticeIfNeeded();
            return;
        }
        ViewPort viewPort = previewView.getViewPort(rotation);
        if (viewPort == null) {
            scheduleCameraViewportRebind();
            return;
        }
        Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
        ImageCapture capture = new ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(92)
            .build();
        setImageCaptureTargetRotation(capture, currentCaptureTargetRotation());
        CameraSelector selector;
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            selector = CameraSelector.DEFAULT_BACK_CAMERA;
        } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            selector = CameraSelector.DEFAULT_FRONT_CAMERA;
        } else {
            throw new IllegalStateException("No camera available");
        }
        unbindOwnCameraUseCases();
        UseCaseGroup useCases = new UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(capture)
            .build();
        cameraProvider.bindToLifecycle(this, selector, useCases);
        previewUseCase = preview;
        imageCapture = capture;
        boundViewportWidth = width;
        boundViewportHeight = height;
        boundDisplayRotationDegrees = rotationDegrees;
        cameraViewportRebindPending = false;
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        setStatus("capture_hint", false);
        showOpeningNoticeIfNeeded();
        Diagnostics.append(this, "Internal CameraX photo preview started");
    }

    private void takePicture() {
        if (finished || captureInFlight || imageCapture == null || outputFile == null) return;
        Display display = previewView == null ? null : previewView.getDisplay();
        if (cameraViewportRebindPending || display == null
                || previewView.getWidth() != boundViewportWidth
                || previewView.getHeight() != boundViewportHeight
                || rotationDegrees(display.getRotation())
                    != boundDisplayRotationDegrees) {
            cameraViewportRebindPending = true;
            scheduleCameraViewportRebind();
            return;
        }
        if (outputFile.exists() && !outputFile.delete()) {
            toast(t("photo_save_failed"));
            return;
        }
        try {
            outputStream = getContentResolver().openOutputStream(outputUri, "w");
            if (outputStream == null) throw new IOException("output stream missing");
        } catch (Exception error) {
            captureFailed(concise(error));
            return;
        }
        capturedRotation = currentCaptureTargetRotation();
        captureInFlight = true;
        setImageCaptureTargetRotation(imageCapture, capturedRotation);
        setStatus("saving_photo", true);
        setCaptureEnabled(false);
        ImageCapture.OutputFileOptions options =
            new ImageCapture.OutputFileOptions.Builder(outputStream).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
            new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(ImageCapture.OutputFileResults result) {
                    captureInFlight = false;
                    if (finished) {
                        closeOutputStream();
                        deleteOutput();
                        return;
                    }
                    String closeFailure = closeOutputStream();
                    if (!closeFailure.isEmpty()) {
                        captureFailed(closeFailure);
                        return;
                    }
                    if (!outputFile.isFile() || outputFile.length() <= 0) {
                        captureFailed("empty output");
                        return;
                    }
                    Diagnostics.append(CaptureActivity.this,
                        "Internal CameraX photo ready for review bytes=" + outputFile.length());
                    showReview();
                }

                @Override
                public void onError(ImageCaptureException error) {
                    captureFailed(concise(error));
                }
            });
    }

    private void captureFailed(String reason) {
        captureInFlight = false;
        capturedRotation = -1;
        closeOutputStream();
        deleteOutput();
        if (finished) return;
        reviewing = false;
        applyOrientedChrome();
        reviewGeneration++;
        clearReviewBitmap();
        if (reviewStage != null) reviewStage.setVisibility(View.GONE);
        if (reviewActions != null) reviewActions.setVisibility(View.GONE);
        if (captureButton != null) captureButton.setVisibility(View.VISIBLE);
        if (cameraViewportRebindPending) {
            setStatus("camera_starting", false);
            setCaptureEnabled(false);
            scheduleCameraViewportRebind();
        } else {
            if (imageCapture != null) {
                setImageCaptureTargetRotation(imageCapture, currentCaptureTargetRotation());
            }
            setStatus("capture_hint", false);
            setCaptureEnabled(true);
        }
        Diagnostics.append(this, "Internal CameraX photo failed: " + reason);
        toast(t("photo_capture_failed") + ": " + reason);
    }

    private void finishCanceled() {
        if (finished) return;
        finished = true;
        setResult(RESULT_CANCELED);
        reviewing = false;
        reviewGeneration++;
        closeOutputStream();
        clearReviewBitmap();
        deleteOutput();
        finishWithoutAnimation();
    }

    private void deleteOutput() {
        if (outputFile != null && outputFile.exists() && !outputFile.delete()) {
            Diagnostics.append(this,
                "Canceled internal photo output could not be deleted");
        }
    }

    /** Unbind only this Activity's use cases; unbindAll could tear down the next camera screen. */
    private void unbindOwnCameraUseCases() {
        Preview preview = previewUseCase;
        ImageCapture capture = imageCapture;
        previewUseCase = null;
        imageCapture = null;
        boundViewportWidth = 0;
        boundViewportHeight = 0;
        boundDisplayRotationDegrees = -1;
        if (cameraProvider == null) return;
        if (preview != null) cameraProvider.unbind(preview);
        if (capture != null) cameraProvider.unbind(capture);
    }

    private String closeOutputStream() {
        OutputStream stream = outputStream;
        outputStream = null;
        if (stream == null) return "";
        try {
            stream.close();
            return "";
        } catch (IOException error) {
            return concise(error);
        }
    }

    private void handleDisplayGeometryChanged(int displayId) {
        if (finished || reviewing || previewView == null) return;
        Display display = previewView.getDisplay();
        if (display == null || display.getDisplayId() != displayId) return;
        if (!cameraViewportRebindPending && previewUseCase != null
                && previewView.getWidth() == boundViewportWidth
                && previewView.getHeight() == boundViewportHeight
                && rotationDegrees(display.getRotation())
                    == boundDisplayRotationDegrees) return;
        cameraViewportRebindPending = true;
        if (!captureInFlight) scheduleCameraViewportRebind();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        SystemBarInsets.requestWhenAttached(cameraRoot);
        if (cameraRoot != null) {
            cameraRoot.post(() -> {
                updateBaseDisplayRotation();
                applyOrientedChrome();
            });
        }
        if (finished || reviewing) return;
        cameraViewportRebindPending = true;
        if (!captureInFlight) scheduleCameraViewportRebind();
    }

    @Override
    protected void onStart() {
        super.onStart();
        cameraActivityStarted = true;
        updateBaseDisplayRotation();
        boolean canDetectOrientation = orientationListener != null
            && orientationListener.canDetectOrientation();
        hasFreshOrientationSample = !canDetectOrientation;
        int orientationGeneration = ++orientationSessionGeneration;
        if (canDetectOrientation) {
            orientationListener.enable();
            if (cameraRoot != null) {
                cameraRoot.postDelayed(
                    () -> allowOrientationFallbackIfNeeded(orientationGeneration),
                    ORIENTATION_SAMPLE_WAIT_MS);
            }
        }
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) displayManager.registerDisplayListener(displayListener, null);
        Display display = previewView == null ? null : previewView.getDisplay();
        if (display != null) handleDisplayGeometryChanged(display.getDisplayId());
        if (!finished && !reviewing && cameraProvider != null
                && (cameraViewportRebindPending || previewUseCase == null)) {
            scheduleCameraViewportRebind();
        } else if (!finished && !reviewing && hasFreshOrientationSample
                && imageCapture != null && !cameraViewportRebindPending) {
            setStatus("capture_hint", false);
            showOpeningNoticeIfNeeded();
        }
    }

    @Override
    protected void onStop() {
        cameraActivityStarted = false;
        orientationSessionGeneration++;
        if (orientationListener != null) orientationListener.disable();
        clearCameraBindWaiter();
        if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
        displayManager = null;
        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finishCanceled();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_REVIEWING,
            reviewing && outputFile != null && outputFile.isFile()
                && outputFile.length() > 0L);
        outState.putBoolean(STATE_NOTICE_SHOWN, openingNoticeShown);
        outState.putInt(STATE_TARGET_ROTATION, stableCaptureRotation);
        outState.putInt(STATE_CAPTURED_ROTATION, capturedRotation);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        finished = true;
        reviewGeneration++;
        if (orientationListener != null) orientationListener.disable();
        clearCameraBindWaiter();
        closeOutputStream();
        clearReviewBitmap();
        unbindOwnCameraUseCases();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
            return;
        }
        toast(t("camera_permission_required"));
        finishCanceled();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    private static String safeFileName(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int rotationDegrees(int rotation) {
        if (rotation == Surface.ROTATION_90) return 90;
        if (rotation == Surface.ROTATION_180) return 180;
        if (rotation == Surface.ROTATION_270) return 270;
        return 0;
    }

    private static String concise(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 240 ? message.substring(0, 240) + "..." : message;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String t(String key) {
        if ("en".equals(lang)) {
            if ("photo".equals(key)) return "Photo";
            if ("cancel".equals(key)) return "Cancel";
            if ("camera_open_failed".equals(key)) return "Camera open failed";
            if ("photo_capture_failed".equals(key)) return "Photo capture failed";
            if ("photo_save_failed".equals(key)) return "Failed to save photo";
            if ("photo_dir_failed".equals(key)) return "Cannot create photo folder";
            if ("camera_permission_required".equals(key)) return "Camera permission is required";
            if ("photo_target_invalid".equals(key)) return "Photo target is invalid";
            if ("camera_starting".equals(key)) return "Starting camera…";
            if ("capture_hint".equals(key)) return "Center the item, then press the shutter";
            if ("capture_action".equals(key)) return "Take photo";
            if ("saving_photo".equals(key)) return "Saving photo…";
            if ("preparing_review".equals(key)) return "Preparing preview…";
            if ("review_photo".equals(key)) return "Check the photo before confirming";
            if ("review_image".equals(key)) return "Captured photo preview";
            if ("retake".equals(key)) return "Retake";
            if ("confirm_photo".equals(key)) return "Use photo";
            if ("photo_preview_failed".equals(key)) return "Photo preview failed";
            if ("photo_notice".equals(key)) return "Photo reminder";
            if ("continue_photo".equals(key)) return "Continue";
            if ("finish_photos".equals(key)) return "Finish photos";
            return key;
        }
        if ("es".equals(lang)) {
            if ("photo".equals(key)) return "Foto";
            if ("cancel".equals(key)) return "Cancelar";
            if ("camera_open_failed".equals(key)) return "Error al abrir cámara";
            if ("photo_capture_failed".equals(key)) return "Error al capturar la foto";
            if ("photo_save_failed".equals(key)) return "Error al guardar la foto";
            if ("photo_dir_failed".equals(key)) return "No se pudo crear la carpeta de fotos";
            if ("camera_permission_required".equals(key)) return "Se requiere permiso de cámara";
            if ("photo_target_invalid".equals(key)) return "El destino de la foto no es válido";
            if ("camera_starting".equals(key)) return "Iniciando cámara…";
            if ("capture_hint".equals(key)) return "Centre el artículo y pulse el obturador";
            if ("capture_action".equals(key)) return "Tomar foto";
            if ("saving_photo".equals(key)) return "Guardando foto…";
            if ("preparing_review".equals(key)) return "Preparando vista previa…";
            if ("review_photo".equals(key)) return "Revise la foto antes de confirmar";
            if ("review_image".equals(key)) return "Vista previa de la foto capturada";
            if ("retake".equals(key)) return "Repetir";
            if ("confirm_photo".equals(key)) return "Usar foto";
            if ("photo_preview_failed".equals(key)) return "Error de vista previa";
            if ("photo_notice".equals(key)) return "Aviso de foto";
            if ("continue_photo".equals(key)) return "Continuar";
            if ("finish_photos".equals(key)) return "Terminar fotos";
            return key;
        }
        if ("photo".equals(key)) return "拍照";
        if ("cancel".equals(key)) return "取消";
        if ("camera_open_failed".equals(key)) return "打开相机失败";
        if ("photo_capture_failed".equals(key)) return "拍照失败";
        if ("photo_save_failed".equals(key)) return "保存照片失败";
        if ("photo_dir_failed".equals(key)) return "无法创建照片目录";
        if ("camera_permission_required".equals(key)) return "需要相机权限";
        if ("photo_target_invalid".equals(key)) return "照片保存目标无效";
        if ("camera_starting".equals(key)) return "相机启动中…";
        if ("capture_hint".equals(key)) return "把物品放在画面中央，再按下快门";
        if ("capture_action".equals(key)) return "拍摄照片";
        if ("saving_photo".equals(key)) return "正在保存照片…";
        if ("preparing_review".equals(key)) return "正在生成预览…";
        if ("review_photo".equals(key)) return "请检查照片，确认后才会记录";
        if ("review_image".equals(key)) return "刚拍摄的照片预览";
        if ("retake".equals(key)) return "重拍";
        if ("confirm_photo".equals(key)) return "确认使用";
        if ("photo_preview_failed".equals(key)) return "照片预览失败";
        if ("photo_notice".equals(key)) return "拍照提示";
        if ("continue_photo".equals(key)) return "继续拍照";
        if ("finish_photos".equals(key)) return "结束拍照";
        return key;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
