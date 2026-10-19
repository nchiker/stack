package org.stackdocs.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Main-thread camera ownership. No camera remains open when the activity pauses. */
final class CameraController {
    interface Listener {
        void ready(boolean flashAvailable);
        default void light(boolean enabled) {}
        void photo(byte[] jpeg);
        void error(String message);
    }

    private final Activity activity;
    private final SurfaceView preview;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder repeating;
    private CameraCharacteristics characteristics;
    private Size previewSize;
    private int generation;
    private boolean opening;
    private boolean capturing;
    private boolean waitingForFocus;
    private boolean torch;
    private boolean hasFlash;
    private int autofocus;

    CameraController(Activity activity, SurfaceView preview, Listener listener) {
        this.activity = activity;
        this.preview = preview;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission") // ScanActivity checks permission before opening.
    void open() {
        if (opening || device != null || !preview.getHolder().getSurface().isValid()) return;
        opening = true;
        int ticket = ++generation;
        try {
            CameraManager manager = activity.getSystemService(CameraManager.class);
            String cameraId = null;
            for (String candidate : manager.getCameraIdList()) {
                CameraCharacteristics info = manager.getCameraCharacteristics(candidate);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = candidate;
                    characteristics = info;
                    break;
                }
            }
            if (cameraId == null) throw new IllegalStateException("A rear camera is not available. You can still import photos and PDFs.");
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("This camera cannot provide a document preview.");
            Size photoSize = choosePhoto(map.getOutputSizes(ImageFormat.JPEG));
            previewSize = choosePreview(map.getOutputSizes(SurfaceHolder.class), photoSize);
            hasFlash = Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            autofocus = CaptureRequest.CONTROL_AF_MODE_OFF;
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (modes != null) for (int mode : modes) {
                if (mode == CaptureRequest.CONTROL_AF_MODE_AUTO && autofocus == CaptureRequest.CONTROL_AF_MODE_OFF) autofocus = mode;
                if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) autofocus = mode;
            }
            reader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(source -> {
                if (ticket != generation) return;
                try (Image image = source.acquireLatestImage()) {
                    if (image == null || !capturing) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    capturing = false;
                    waitingForFocus = false;
                    listener.photo(bytes);
                } catch (RuntimeException error) {
                    fail(ticket, "The photo could not be received. Please try again.");
                }
            }, handler);
            preview.getHolder().setFixedSize(previewSize.getWidth(), previewSize.getHeight());
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {
                    if (ticket != generation) { opened.close(); return; }
                    device = opened;
                    configure(ticket);
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    fail(ticket, "The camera became unavailable. Tap Retry camera.");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    fail(ticket, "The camera could not open. Close other camera apps and tap Retry camera.");
                }
            }, handler);
            handler.postDelayed(() -> {
                if (ticket == generation && opening) fail(ticket, "The camera did not respond. Tap Retry camera.");
            }, 8000);
        } catch (Exception error) {
            fail(ticket, error.getMessage() == null ? "The camera could not open. Tap Retry camera." : error.getMessage());
        }
    }

    private void configure(int ticket) {
        try {
            Surface surface = preview.getHolder().getSurface();
            repeating = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            repeating.addTarget(surface);
            repeating.set(CaptureRequest.CONTROL_AF_MODE, autofocus);
            repeating.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            device.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (ticket != generation || device == null) { configured.close(); return; }
                    session = configured;
                    opening = false;
                    try {
                        session.setRepeatingRequest(repeating.build(), lightState, handler);
                        fitPreview();
                        listener.ready(hasFlash);
                    } catch (Exception error) { fail(ticket, "The camera preview could not start. Tap Retry camera."); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    fail(ticket, "This camera could not configure a photo. Tap Retry camera.");
                }
            }, handler);
        } catch (Exception error) { fail(ticket, "The camera preview could not start. Tap Retry camera."); }
    }

    void fitPreview() {
        if (characteristics == null || previewSize == null || preview.getWidth() == 0 || preview.getHeight() == 0) return;
        int rotation = (sensorOrientation() + displayDegrees()) % 360;
        float width = rotation % 180 == 0 ? previewSize.getWidth() : previewSize.getHeight();
        float height = rotation % 180 == 0 ? previewSize.getHeight() : previewSize.getWidth();
        float x = preview.getWidth() / width, y = preview.getHeight() / height;
        float fit = Math.min(x, y);
        preview.setScaleX(fit / x);
        preview.setScaleY(fit / y);
    }

    private final CameraCaptureSession.CaptureCallback lightState=new CameraCaptureSession.CaptureCallback(){
        @Override public void onCaptureCompleted(CameraCaptureSession owner,CaptureRequest request,TotalCaptureResult result){
            if(owner!=session)return;Integer state=result.get(CaptureResult.FLASH_STATE);
            if(hasFlash)listener.light(torch&&state!=null&&state==CaptureResult.FLASH_STATE_FIRED);
        }
    };
    void torch(boolean enabled) {
        torch = enabled && hasFlash;
        if (repeating == null || session == null) return;
        try {
            repeating.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(repeating.build(), lightState, handler);
        } catch (Exception error) { fail(generation, "The camera light could not change. Tap Retry camera."); }
    }

    void capture() {
        if (session == null || capturing) return;
        capturing = true;
        waitingForFocus = true;
        int ticket = generation;
        try {
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(repeating.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession camera, CaptureRequest request, TotalCaptureResult result) {
                    if (ticket != generation || !waitingForFocus) return;
                    Integer state = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (state == null || state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) takePhoto(ticket);
                }
            }, handler);
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            // Continuous-focus cameras may not emit a locked state on the trigger result.
            handler.postDelayed(() -> { if (ticket == generation && waitingForFocus) takePhoto(ticket); }, 1200);
            handler.postDelayed(() -> {
                if (ticket == generation && capturing) fail(ticket, "The photo timed out. Your other pages are safe; tap Retry camera.");
            }, 10_000);
        } catch (Exception error) { fail(ticket, "The photo could not be taken. Tap Retry camera."); }
    }

    private void takePhoto(int ticket) {
        waitingForFocus = false;
        if (ticket != generation || device == null || session == null) return;
        try {
            CaptureRequest.Builder photo = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            photo.addTarget(reader.getSurface());
            photo.set(CaptureRequest.CONTROL_AF_MODE, autofocus);
            photo.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            photo.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            photo.set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation() - displayDegrees() + 360) % 360);
            photo.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            session.capture(photo.build(), null, handler);
        } catch (Exception error) { fail(ticket, "The photo could not be taken. Tap Retry camera."); }
    }

    private int sensorOrientation() {
        Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        return sensor == null ? 90 : sensor;
    }

    private int displayDegrees() { return activity.getWindowManager().getDefaultDisplay().getRotation() * 90; }

    private void fail(int ticket, String message) {
        if (ticket != generation) return;
        close();
        listener.error(message);
    }

    void close() {
        generation++;
        opening = false;
        capturing = false;
        waitingForFocus = false;
        torch = false;
        listener.light(false);
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (reader != null) { reader.close(); reader = null; }
        repeating = null;
    }

    private static Size choosePhoto(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("JPEG capture is not supported by this camera.");
        Size best = sizes[0];
        long score = Long.MAX_VALUE;
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            long candidate = Math.abs(pixels - 8_000_000L) + (pixels > 12_500_000L ? 100_000_000L : 0);
            if (candidate < score) { best = size; score = candidate; }
        }
        return best;
    }

    private static Size choosePreview(Size[] sizes, Size photo) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("Camera preview is not supported.");
        Size best = sizes[0];
        double score = Double.MAX_VALUE;
        for (Size size : sizes) {
            double aspect = (double) size.getWidth() / size.getHeight();
            double candidate = Math.abs(aspect - (double) photo.getWidth() / photo.getHeight()) * 10000
                    + Math.abs(size.getWidth() - 1280) + (size.getWidth() > 1920 ? 10000 : 0);
            if (candidate < score) { best = size; score = candidate; }
        }
        return best;
    }
}
