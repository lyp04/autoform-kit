package com.autoformkit.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class CaptureActivity extends Activity implements SurfaceHolder.Callback {
    private static final int REQ_CAMERA_PERMISSION = 4001;

    private SurfaceView surfaceView;
    private Camera camera;
    private Button captureButton;
    private String fileName;
    private String lang = "zh";
    private boolean takingPicture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lang = getIntent().getStringExtra("lang") == null ? "zh" : getIntent().getStringExtra("lang");
        fileName = safeFileName(getIntent().getStringExtra("fileName"));
        if (fileName.isEmpty()) fileName = "photo-" + System.currentTimeMillis() + ".jpg";
        buildUi();
        if (!hasCameraPermission() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        }
    }

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        frame.addView(surfaceView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(dp(16), dp(20), dp(16), dp(8));
        TextView title = new TextView(this);
        String label = getIntent().getStringExtra("label");
        title.setText(label == null || label.isEmpty() ? t("photo") : label);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        top.addView(title);
        frame.addView(top, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(20));
        captureButton = new Button(this);
        captureButton.setText(t("photo"));
        captureButton.setTextSize(18);
        captureButton.setTextColor(0xFFFFFFFF);
        GradientDrawable captureBg = new GradientDrawable();
        captureBg.setShape(GradientDrawable.OVAL);
        captureBg.setColor(0xFF0F766E);
        captureBg.setStroke(dp(4), 0xFFFFFFFF);
        captureButton.setBackground(captureBg);
        captureButton.setOnClickListener(v -> takePicture());
        LinearLayout.LayoutParams captureParams = new LinearLayout.LayoutParams(dp(96), dp(96));
        captureParams.setMargins(dp(16), 0, dp(16), 0);
        bottom.addView(captureButton, captureParams);
        Button cancel = new Button(this);
        cancel.setText(t("cancel"));
        cancel.setOnClickListener(v -> finish());
        bottom.addView(cancel);
        frame.addView(bottom, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ));
        setContentView(frame);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            startPreview(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void startCamera(SurfaceHolder holder) {
        if (!hasCameraPermission()) return;
        try {
            int cameraId = backCameraId();
            camera = Camera.open(cameraId);
            Camera.Parameters params = camera.getParameters();
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            params.setRotation(photoRotation(cameraId));
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            startPreview(holder);
        } catch (Exception exc) {
            toast(t("camera_open_failed") + ": " + exc.getMessage());
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void startPreview(SurfaceHolder holder) {
        if (camera == null) return;
        try {
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (IOException exc) {
            toast(t("camera_preview_failed") + ": " + exc.getMessage());
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void takePicture() {
        if (camera == null || takingPicture) return;
        takingPicture = true;
        captureButton.setEnabled(false);
        try {
            try {
                camera.autoFocus((success, cam) -> captureJpeg());
            } catch (Exception exc) {
                captureJpeg();
            }
        } catch (Exception exc) {
            takingPicture = false;
            captureButton.setEnabled(true);
            toast(t("photo_capture_failed") + ": " + exc.getMessage());
        }
    }

    private void captureJpeg() {
        try {
            camera.takePicture(null, null, (data, cam) -> {
                try {
                    File file = savePhoto(data);
                    Intent result = new Intent();
                    result.putExtra("photoPath", file.getAbsolutePath());
                    setResult(RESULT_OK, result);
                    finish();
                } catch (Exception exc) {
                    takingPicture = false;
                    captureButton.setEnabled(true);
                    toast(t("photo_save_failed") + ": " + exc.getMessage());
                    try {
                        cam.startPreview();
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception exc) {
            takingPicture = false;
            captureButton.setEnabled(true);
            toast(t("photo_capture_failed") + ": " + exc.getMessage());
        }
    }

    private File savePhoto(byte[] data) throws IOException {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException(t("photo_dir_failed"));
        }
        File file = new File(dir, fileName);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
        return file;
    }

    private int photoRotation(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        if (rotation == Surface.ROTATION_90) degrees = 90;
        else if (rotation == Surface.ROTATION_180) degrees = 180;
        else if (rotation == Surface.ROTATION_270) degrees = 270;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - ((info.orientation + degrees) % 360)) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private int backCameraId() {
        if (Camera.getNumberOfCameras() <= 0) return 0;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceView != null && surfaceView.getHolder().getSurface().isValid() && camera == null && hasCameraPermission()) {
            startCamera(surfaceView.getHolder());
        }
    }

    private void releaseCamera() {
        if (camera == null) return;
        try {
            camera.stopPreview();
        } catch (Exception ignored) {
        }
        camera.release();
        camera = null;
    }

    private static String safeFileName(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String t(String key) {
        if ("en".equals(lang)) {
            if ("photo".equals(key)) return "Photo";
            if ("cancel".equals(key)) return "Cancel";
            if ("camera_open_failed".equals(key)) return "Camera open failed";
            if ("camera_preview_failed".equals(key)) return "Camera preview failed";
            if ("photo_capture_failed".equals(key)) return "Photo capture failed";
            if ("photo_save_failed".equals(key)) return "Failed to save photo";
            if ("photo_dir_failed".equals(key)) return "Cannot create photo folder";
            return key;
        }
        if ("es".equals(lang)) {
            if ("photo".equals(key)) return "Foto";
            if ("cancel".equals(key)) return "Cancelar";
            if ("camera_open_failed".equals(key)) return "Error al abrir cámara";
            if ("camera_preview_failed".equals(key)) return "Error de vista previa";
            if ("photo_capture_failed".equals(key)) return "Error al capturar la foto";
            if ("photo_save_failed".equals(key)) return "Error al guardar la foto";
            if ("photo_dir_failed".equals(key)) return "No se pudo crear la carpeta de fotos";
            return key;
        }
        if ("photo".equals(key)) return "拍照";
        if ("cancel".equals(key)) return "取消";
        if ("camera_open_failed".equals(key)) return "打开相机失败";
        if ("camera_preview_failed".equals(key)) return "相机预览失败";
        if ("photo_capture_failed".equals(key)) return "拍照失败";
        if ("photo_save_failed".equals(key)) return "保存照片失败";
        if ("photo_dir_failed".equals(key)) return "无法创建照片目录";
        return key;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
