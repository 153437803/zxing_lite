package lib.kalu.zxing.camerax;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.detector.MathUtils;

import lib.kalu.zxing.analyze.AnalyzerImpl;
import lib.kalu.zxing.analyze.AnalyzerQrcode;
import lib.kalu.zxing.impl.ICameraImpl;
import lib.kalu.zxing.listener.OnCameraScanChangeListener;
import lib.kalu.zxing.util.LogUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;

import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.TorchState;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;

import org.jetbrains.annotations.NotNull;

/**
 * @description:
 * @date: 2021-05-07 14:55
 */
public final class CameraManager implements ICameraImpl {

    /**
     * Defines the maximum duration in milliseconds between a touch pad
     * touch and release for a given touch to be considered a tap (click) as
     * opposed to a hover movement gesture.
     */
    private static final int HOVER_TAP_TIMEOUT = 150;

    /**
     * Defines the maximum distance in pixels that a touch pad touch can move
     * before being released for it to be considered a tap (click) as opposed
     * to a hover movement gesture.
     */
    private static final int HOVER_TAP_SLOP = 20;

    private FragmentActivity mFragmentActivity;
    //    private Context mContext;
    private LifecycleOwner mLifecycleOwner;
    private PreviewView mPreviewView;

    private ListenableFuture<ProcessCameraProvider> mCameraProviderFuture;
    private Camera mCamera;

    private CameraConfig mCameraConfig;
    private AnalyzerImpl mAnalyzer;

    /**
     * 是否分析
     */
    private volatile boolean isAnalyze = true;

    /**
     * 是否已经分析出结果
     */
    private volatile boolean isAnalyzeResult;

    private View flashlightView;

    private MutableLiveData<Result> mResultLiveData;

    private OnCameraScanChangeListener mOnScanResultCallback;

    private BeepManager mBeepManager;
    private AmbientLightManager mAmbientLightManager;

    private int mOrientation;
    private int mScreenWidth;
    private int mScreenHeight;
    private long mLastAutoZoomTime;
    private long mLastHoveTapTime;
    private boolean isClickTap;
    private float mDownX;
    private float mDownY;

    public CameraManager(FragmentActivity activity, PreviewView previewView) {
        this.mFragmentActivity = activity;
        this.mLifecycleOwner = activity;
        this.mPreviewView = previewView;
        Context context = activity.getApplicationContext();
        initData(context);
    }

    public CameraManager(Fragment fragment, PreviewView previewView) {
        this.mFragmentActivity = fragment.getActivity();
        this.mLifecycleOwner = fragment;
        this.mPreviewView = previewView;
        Context context = fragment.getContext().getApplicationContext();
        initData(context);
    }

    private ScaleGestureDetector.OnScaleGestureListener mOnScaleGestureListener = new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scale = detector.getScaleFactor();
            if (mCamera != null) {
                float ratio = mCamera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                zoomTo(ratio * scale);
            }
            return true;
        }

    };

    private void initData(@NonNull Context context) {
        mResultLiveData = new MutableLiveData<>();
        mResultLiveData.observe(mLifecycleOwner, result -> {
            handleAnalyzeResult(result);
        });

        mOrientation = context.getResources().getConfiguration().orientation;
        ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(context, mOnScaleGestureListener);
        mPreviewView.setOnTouchListener((v, event) -> {
            handlePreviewViewClickTap(event);
            if (isNeedTouchZoom()) {
                return scaleGestureDetector.onTouchEvent(event);
            }
            return false;
        });

        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        mScreenWidth = displayMetrics.widthPixels;
        mScreenHeight = displayMetrics.heightPixels;
        mBeepManager = new BeepManager(context);
        mAmbientLightManager = new AmbientLightManager(context);
        if (mAmbientLightManager != null) {
            mAmbientLightManager.register();
            mAmbientLightManager.setOnLightSensorEventListener((dark, lightLux) -> {
                if (flashlightView != null) {
                    if (dark) {
                        if (flashlightView.getVisibility() != View.VISIBLE) {
                            flashlightView.setVisibility(View.VISIBLE);
                            flashlightView.setSelected(isTorchEnabled());
                        }
                    } else if (flashlightView.getVisibility() == View.VISIBLE && !isTorchEnabled()) {
                        flashlightView.setVisibility(View.INVISIBLE);
                        flashlightView.setSelected(false);
                    }

                }
            });
        }
    }

    private void handlePreviewViewClickTap(MotionEvent event) {
        if (event.getPointerCount() == 1) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isClickTap = true;
                    mDownX = event.getX();
                    mDownY = event.getY();
                    mLastHoveTapTime = System.currentTimeMillis();
                    break;
                case MotionEvent.ACTION_MOVE:
                    isClickTap = MathUtils.distance(mDownX, mDownY, event.getX(), event.getY()) < HOVER_TAP_SLOP;
                    break;
                case MotionEvent.ACTION_UP:
                    if (isClickTap && mLastHoveTapTime + HOVER_TAP_TIMEOUT > System.currentTimeMillis()) {
                        startFocusAndMetering(event.getX(), event.getY());
                    }
                    break;
            }
        }
    }

    private void startFocusAndMetering(float x, float y) {
        if (mCamera != null) {
            LogUtil.log("startFocusAndMetering:" + x + "," + y);
            MeteringPoint point = mPreviewView.getMeteringPointFactory().createPoint(x, y);
            mCamera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point).build());
        }
    }


    private void initConfig() {
        if (mCameraConfig == null) {
            mCameraConfig = new CameraConfig();
        }
        if (mAnalyzer == null) {
            mAnalyzer = new AnalyzerQrcode();
        }
    }


    @Override
    public ICameraImpl setCameraConfig(CameraConfig cameraConfig) {
        if (cameraConfig != null) {
            this.mCameraConfig = cameraConfig;
        }
        return this;
    }

    @Override
    public void start(@NonNull Context context) {
        initConfig();
        mCameraProviderFuture = ProcessCameraProvider.getInstance(context);
        mCameraProviderFuture.addListener(() -> {

            try {
                Preview preview = mCameraConfig.options(new Preview.Builder());

                //相机选择器
                CameraSelector cameraSelector = mCameraConfig.options(new CameraSelector.Builder()
                        .requireLensFacing(LENS_FACING_BACK));
                //设置SurfaceProvider
                preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

                //图像分析
                ImageAnalysis imageAnalysis = mCameraConfig.options(new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST));

                // 分析
                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull @NotNull ImageProxy image) {

                        if (isAnalyze && !isAnalyzeResult && mAnalyzer != null) {
                            Result result = mAnalyzer.analyze(image, mOrientation);
                            if (result != null) {
                                mResultLiveData.postValue(result);
                            }
                        }
                        image.close();
                    }
                });
                if (mCamera != null) {
                    mCameraProviderFuture.get().unbindAll();
                }
                //绑定到生命周期
                mCamera = mCameraProviderFuture.get().bindToLifecycle(mLifecycleOwner, cameraSelector, preview, imageAnalysis);
            } catch (Exception e) {
                LogUtil.log(e.getMessage());
            }

        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * 处理分析结果
     *
     * @param result
     */
    private synchronized void handleAnalyzeResult(Result result) {
        if (isAnalyzeResult || !isAnalyze) {
            return;
        }
        isAnalyzeResult = true;
        if (mBeepManager != null) {
            mBeepManager.playBeepSoundAndVibrate();
        }

        if (result.getBarcodeFormat() == BarcodeFormat.QR_CODE && isNeedAutoZoom() && mLastAutoZoomTime + 100 < System.currentTimeMillis()) {
            ResultPoint[] points = result.getResultPoints();
            if (points != null && points.length >= 2) {
                float distance1 = ResultPoint.distance(points[0], points[1]);
                float maxDistance = distance1;
                if (points.length >= 3) {
                    float distance2 = ResultPoint.distance(points[1], points[2]);
                    float distance3 = ResultPoint.distance(points[0], points[2]);
                    maxDistance = Math.max(Math.max(distance1, distance2), distance3);
                }
                if (handleAutoZoom((int) maxDistance, result)) {
                    return;
                }
            }
        }

        scanResultCallback(result);
    }

    private boolean handleAutoZoom(int distance, Result result) {
        int size = Math.min(mScreenWidth, mScreenHeight);
        if (distance * 4 < size) {
            mLastAutoZoomTime = System.currentTimeMillis();
            zoomIn();
            scanResultCallback(result);
            return true;
        }
        return false;
    }

    private void scanResultCallback(Result result) {
        if (mOnScanResultCallback != null && mOnScanResultCallback.onResult(result)) {
            //如果拦截了结果，则重置分析结果状态，直接可以连扫
            isAnalyzeResult = false;
            return;
        }

        if (mFragmentActivity != null) {
            Intent intent = new Intent();
            intent.putExtra(SCAN_RESULT, result.getText());
            mFragmentActivity.setResult(Activity.RESULT_OK, intent);
            mFragmentActivity.finish();
        }
    }


    @Override
    public void stop(@NonNull Context context) {
        if (mCameraProviderFuture != null) {
            try {
                mCameraProviderFuture.get().unbindAll();
            } catch (Exception e) {
                LogUtil.log(e.getMessage());
            }
        }
    }

    @Override
    public ICameraImpl setAnalyzeImage(boolean analyze) {
        isAnalyze = analyze;
        return this;
    }

    /**
     * 设置分析器，如果内置的一些分析器不满足您的需求，你也可以自定义{@link AnalyzerImpl}，
     * 自定义时，切记需在{@link #start()}之前调用才有效
     *
     * @param analyzer
     */
    @Override
    public ICameraImpl setAnalyzer(AnalyzerImpl analyzer) {
        mAnalyzer = analyzer;
        return this;
    }

    @Override
    public void zoomIn() {
        if (mCamera != null) {
            float ratio = mCamera.getCameraInfo().getZoomState().getValue().getZoomRatio() + 0.1f;
            float maxRatio = mCamera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
            if (ratio <= maxRatio) {
                mCamera.getCameraControl().setZoomRatio(ratio);
            }
        }
    }

    @Override
    public void zoomOut() {
        if (mCamera != null) {
            float ratio = mCamera.getCameraInfo().getZoomState().getValue().getZoomRatio() - 0.1f;
            float minRatio = mCamera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
            if (ratio >= minRatio) {
                mCamera.getCameraControl().setZoomRatio(ratio);
            }
        }
    }


    @Override
    public void zoomTo(float ratio) {
        if (mCamera != null) {
            ZoomState zoomState = mCamera.getCameraInfo().getZoomState().getValue();
            float maxRatio = zoomState.getMaxZoomRatio();
            float minRatio = zoomState.getMinZoomRatio();
            float zoom = Math.max(Math.min(ratio, maxRatio), minRatio);
            mCamera.getCameraControl().setZoomRatio(zoom);
        }
    }

    @Override
    public void lineZoomIn() {
        if (mCamera != null) {
            float zoom = mCamera.getCameraInfo().getZoomState().getValue().getLinearZoom() + 0.1f;
            if (zoom <= 1f) {
                mCamera.getCameraControl().setLinearZoom(zoom);
            }
        }
    }

    @Override
    public void lineZoomOut() {
        if (mCamera != null) {
            float zoom = mCamera.getCameraInfo().getZoomState().getValue().getLinearZoom() - 0.1f;
            if (zoom >= 0f) {
                mCamera.getCameraControl().setLinearZoom(zoom);
            }
        }
    }

    @Override
    public void lineZoomTo(@FloatRange(from = 0.0, to = 1.0) float linearZoom) {
        if (mCamera != null) {
            mCamera.getCameraControl().setLinearZoom(linearZoom);
        }
    }

    @Override
    public void enableTorch(boolean torch) {
        if (mCamera != null && hasFlashUnit()) {
            mCamera.getCameraControl().enableTorch(torch);
        }
    }

    @Override
    public boolean isTorchEnabled() {
        if (mCamera != null) {
            return mCamera.getCameraInfo().getTorchState().getValue() == TorchState.ON;
        }
        return false;
    }

    /**
     * 是否支持闪光灯
     *
     * @return
     */
    @Override
    public boolean hasFlashUnit() {
        if (mCamera != null) {
            return mCamera.getCameraInfo().hasFlashUnit();
        }
        return false;
    }

    @Override
    public ICameraImpl setVibrate(boolean vibrate) {
        if (mBeepManager != null) {
            mBeepManager.setVibrate(vibrate);
        }
        return this;
    }

    @Override
    public ICameraImpl setPlayBeep(boolean playBeep) {
        if (mBeepManager != null) {
            mBeepManager.setPlayBeep(playBeep);
        }
        return this;
    }

    @Nullable
    @Override
    public Camera getCamera() {
        return mCamera;
    }


    @Override
    public void release(@NonNull Context context) {
        isAnalyze = false;
        flashlightView = null;
        if (mAmbientLightManager != null) {
            mAmbientLightManager.unregister();
        }
        if (mBeepManager != null) {
            mBeepManager.close();
        }
        stop(context);
    }

    @Override
    public ICameraImpl bindFlashlightView(@Nullable View v) {
        flashlightView = v;
        if (mAmbientLightManager != null) {
            mAmbientLightManager.setLightSensorEnabled(v != null);
        }
        return this;
    }

    public ICameraImpl setDarkLightLux(float lightLux) {
        if (mAmbientLightManager != null) {
            mAmbientLightManager.setDarkLightLux(lightLux);
        }
        return this;
    }

    public ICameraImpl setBrightLightLux(float lightLux) {
        if (mAmbientLightManager != null) {
            mAmbientLightManager.setBrightLightLux(lightLux);
        }
        return this;
    }

    @Override
    public ICameraImpl setOnCameraScanChangeListener(@NonNull OnCameraScanChangeListener callback) {
        this.mOnScanResultCallback = callback;
        return this;
    }
}