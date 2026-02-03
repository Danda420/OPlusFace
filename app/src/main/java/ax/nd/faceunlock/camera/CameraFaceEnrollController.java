package ax.nd.faceunlock.camera;

import android.content.Context;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import ax.nd.faceunlock.camera.listeners.CameraListener;
import ax.nd.faceunlock.camera.listeners.ErrorCallbackListener;

public class CameraFaceEnrollController {
    private static final String TAG = "CameraFaceEnrollController";
    private static CameraFaceEnrollController sInstance;
    private Context mContext;
    private Handler mHandler;
    private HandlerThread mEnrollHandlerThread;
    private Handler mEnrollHandler;
    private volatile CameraCallback mCallback;
    private volatile boolean mIsEnrolling = false;
    private int mSrcWidth = 0;
    private int mSrcHeight = 0;
    private final int mTargetWidth = 640;
    private final int mTargetHeight = 480;
    private int mScaleFactor = 1;
    private byte[] mProcessedBuffer;

    private static final List<ResolutionProfile> KNOWN_DEVICES = new ArrayList<>();

    static {
        KNOWN_DEVICES.add(new ResolutionProfile(2592, 1952, 4));
        KNOWN_DEVICES.add(new ResolutionProfile(2592, 1940, 4));
        KNOWN_DEVICES.add(new ResolutionProfile(2304, 1728, 3));
        KNOWN_DEVICES.add(new ResolutionProfile(1920, 1080, 2));
        KNOWN_DEVICES.add(new ResolutionProfile(1280,  720, 2));
        KNOWN_DEVICES.add(new ResolutionProfile( 640,  480, 1));
    }

    private static class ResolutionProfile {
        int width, height, scale;
        long expectedBytes;

        ResolutionProfile(int w, int h, int s) {
            width = w; height = h; scale = s;
            expectedBytes = (long)(w * h * 1.5);
        }
    }

    public interface CameraCallback {
        int handleSaveFeature(byte[] data, int width, int height, int angle);
        void handleSaveFeatureResult(int res);
        void onFaceDetected();
        void onTimeout();
        void onCameraError();
        void setDetectArea(Camera.Size size);
    }

    public static CameraFaceEnrollController getInstance(Context context) {
        if (sInstance == null) sInstance = new CameraFaceEnrollController(context);
        return sInstance;
    }

    public static CameraFaceEnrollController getInstance() {
        return sInstance;
    }

    private CameraFaceEnrollController(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void start(CameraCallback callback, int cameraId, Surface previewSurface) {
        if (mIsEnrolling) stop(null);
        mIsEnrolling = true;
        mCallback = callback;
        mSrcWidth = 0;

        mEnrollHandlerThread = new HandlerThread("face_enroll_thread");
        mEnrollHandlerThread.start();
        mEnrollHandler = new Handler(mEnrollHandlerThread.getLooper());

        CameraService.openCamera(cameraId, new ErrorCallbackListener() {
            @Override public void onEventCallback(int i, Object value) {
                if (mCallback != null) mCallback.onCameraError();
            }
        }, new CameraListener() {
            @Override public void onComplete(Object value) { startConfiguredPreview(previewSurface); }
            @Override public void onError(Exception e) { if (mCallback != null) mCallback.onCameraError(); }
        });
    }

    private void startConfiguredPreview(Surface surface) {
        CameraService.configureAndStartPreview(surface, new CameraListener() {
            @Override public void onComplete(Object value) { attachPreviewCallback(); }
            @Override public void onError(Exception e) { if (mCallback != null) mCallback.onCameraError(); }
        });
    }

    private void attachPreviewCallback() {
        CameraService.setPreviewCallback((i, obj) -> {
            final CameraCallback callback = mCallback;
            if (!mIsEnrolling || callback == null) return;

            if (obj instanceof byte[]) {
                final byte[] srcData = (byte[]) obj;

                if (mSrcWidth == 0) detectSourceResolution(srcData.length);
                if (mSrcWidth == 0) return;

                if (mEnrollHandler != null) {
                    mEnrollHandler.post(() -> {
                        try {
                            if (callback == null || mSrcWidth == 0) return;

                            if (mProcessedBuffer == null) {
                                mProcessedBuffer = new byte[mTargetWidth * mTargetHeight * 3 / 2];
                            }

                            if (mScaleFactor == 4) {
                                downscale4xNV21(srcData, mSrcWidth, mSrcHeight, mProcessedBuffer, mTargetWidth, mTargetHeight);
                            } else if (mScaleFactor == 3) {
                                downscale3xNV21(srcData, mSrcWidth, mSrcHeight, mProcessedBuffer, mTargetWidth, mTargetHeight);
                            } else if (mScaleFactor == 2) {
                                downscale2xNV21(srcData, mSrcWidth, mSrcHeight, mProcessedBuffer, mTargetWidth, mTargetHeight);
                            } else {
                                cropNV21(srcData, mSrcWidth, mSrcHeight, mProcessedBuffer, mTargetWidth, mTargetHeight);
                            }

                            int res = callback.handleSaveFeature(mProcessedBuffer, mTargetWidth, mTargetHeight, 90);
                            callback.handleSaveFeatureResult(res);

                        } catch (Exception e) {
                            Log.e(TAG, "Enroll processing error", e);
                        }
                    });
                }
            }
        }, false, null);
    }

    private void detectSourceResolution(int dataLength) {
        for (ResolutionProfile profile : KNOWN_DEVICES) {
            if (Math.abs(dataLength - profile.expectedBytes) < 2000) {
                mSrcWidth = profile.width;
                mSrcHeight = profile.height;
                mScaleFactor = profile.scale;
                Log.i(TAG, "Matched Profile: (" + mSrcWidth + "x" + mSrcHeight + ")");
                return;
            }
        }

        int pixels = (int)(dataLength / 1.5);
        int h = (int) Math.sqrt(pixels * 0.75);
        int w = (int) (h * 1.3333333);

        if (w % 2 != 0) w++;
        if (h % 2 != 0) h++;

        mSrcWidth = w;
        mSrcHeight = h;

        if (w >= 2300) mScaleFactor = 4;
        else if (w >= 2000) mScaleFactor = 3;
        else if (w >= 1200) mScaleFactor = 2;
        else mScaleFactor = 1;

        Log.w(TAG, "Unknown Device Detected! Estimated dimension: " + mSrcWidth + "x" + mSrcHeight + " Scale: " + mScaleFactor);
    }

    private void downscale4xNV21(byte[] src, int srcWidth, int srcHeight, byte[] dest, int dstWidth, int dstHeight) {
        int scaledW = srcWidth / 4;
        int scaledH = srcHeight / 4;
        int xOffset = (scaledW - dstWidth) / 2;
        int yOffset = (scaledH - dstHeight) / 2;
        if (xOffset < 0) xOffset = 0;
        if (yOffset < 0) yOffset = 0;

        for (int y = 0; y < dstHeight; y++) {
            int srcY = (y + yOffset) * 4;
            if (srcY >= srcHeight) break;
            for (int x = 0; x < dstWidth; x++) {
                int srcX = (x + xOffset) * 4;
                dest[y * dstWidth + x] = src[srcY * srcWidth + srcX];
            }
        }
        int uvSrcStart = srcWidth * srcHeight;
        int uvDstStart = dstWidth * dstHeight;
        for (int y = 0; y < dstHeight / 2; y++) {
            int srcY = (y + yOffset / 2) * 4;
            if (srcY >= srcHeight / 2) break;
            for (int x = 0; x < dstWidth; x += 2) {
                int srcX = (x + xOffset / 2) * 4;
                int srcIndex = uvSrcStart + srcY * srcWidth + srcX;
                int dstIndex = uvDstStart + y * dstWidth + x;
                dest[dstIndex] = src[srcIndex];
                dest[dstIndex + 1] = src[srcIndex + 1];
            }
        }
    }

    private void downscale3xNV21(byte[] src, int srcWidth, int srcHeight, byte[] dest, int dstWidth, int dstHeight) {
        int scaledW = srcWidth / 3;
        int scaledH = srcHeight / 3;
        int xOffset = (scaledW - dstWidth) / 2;
        int yOffset = (scaledH - dstHeight) / 2;
        if (xOffset < 0) xOffset = 0;
        if (yOffset < 0) yOffset = 0;

        // Y Plane
        for (int y = 0; y < dstHeight; y++) {
            int srcY = (y + yOffset) * 3;
            if (srcY >= srcHeight) break;
            for (int x = 0; x < dstWidth; x++) {
                int srcX = (x + xOffset) * 3;
                dest[y * dstWidth + x] = src[srcY * srcWidth + srcX];
            }
        }

        // UV Plane
        int uvSrcStart = srcWidth * srcHeight;
        int uvDstStart = dstWidth * dstHeight;
        for (int y = 0; y < dstHeight / 2; y++) {
            int srcY = (y + yOffset / 2) * 3;
            if (srcY >= srcHeight / 2) break;
            for (int x = 0; x < dstWidth; x += 2) {
                int srcX = (x + xOffset / 2) * 3;
                int srcIndex = uvSrcStart + srcY * srcWidth + srcX;
                int dstIndex = uvDstStart + y * dstWidth + x;
                dest[dstIndex] = src[srcIndex];
                dest[dstIndex + 1] = src[srcIndex + 1];
            }
        }
    }

    private void downscale2xNV21(byte[] src, int srcWidth, int srcHeight, byte[] dest, int dstWidth, int dstHeight) {
        int scaledW = srcWidth / 2;
        int scaledH = srcHeight / 2;
        int xOffset = (scaledW - dstWidth) / 2;
        int yOffset = (scaledH - dstHeight) / 2;
        if (xOffset < 0) xOffset = 0;
        if (yOffset < 0) yOffset = 0;

        for (int y = 0; y < dstHeight; y++) {
            int srcY = (y + yOffset) * 2;
            if (srcY >= srcHeight) break;
            for (int x = 0; x < dstWidth; x++) {
                int srcX = (x + xOffset) * 2;
                dest[y * dstWidth + x] = src[srcY * srcWidth + srcX];
            }
        }
        int uvSrcStart = srcWidth * srcHeight;
        int uvDstStart = dstWidth * dstHeight;
        for (int y = 0; y < dstHeight / 2; y++) {
            int srcY = (y + yOffset / 2) * 2;
            if (srcY >= srcHeight / 2) break;
            for (int x = 0; x < dstWidth; x += 2) {
                int srcX = (x + xOffset / 2) * 2;
                int srcIndex = uvSrcStart + srcY * srcWidth + srcX;
                int dstIndex = uvDstStart + y * dstWidth + x;
                dest[dstIndex] = src[srcIndex];
                dest[dstIndex + 1] = src[srcIndex + 1];
            }
        }
    }

    private void cropNV21(byte[] src, int srcWidth, int srcHeight, byte[] dest, int dstWidth, int dstHeight) {
        if (src.length < srcWidth * srcHeight * 3 / 2) return;
        int xOffset = (srcWidth - dstWidth) / 2;
        int yOffset = (srcHeight - dstHeight) / 2;
        if (xOffset % 2 != 0) xOffset--;
        if (yOffset % 2 != 0) yOffset--;

        for (int i = 0; i < dstHeight; i++) {
            System.arraycopy(src, (yOffset + i) * srcWidth + xOffset, dest, i * dstWidth, dstWidth);
        }
        int uvSrcStart = srcWidth * srcHeight;
        int uvDstStart = dstWidth * dstHeight;
        for (int i = 0; i < dstHeight / 2; i++) {
            System.arraycopy(src, uvSrcStart + (yOffset / 2 + i) * srcWidth + xOffset, dest, uvDstStart + i * dstWidth, dstWidth);
        }
    }

    public void stop(CameraCallback callback) {
        mIsEnrolling = false;
        mCallback = null;
        CameraService.closeCamera(null);
        if (mEnrollHandlerThread != null) {
            mEnrollHandlerThread.quitSafely();
            mEnrollHandlerThread = null;
        }
        mProcessedBuffer = null;
    }
}