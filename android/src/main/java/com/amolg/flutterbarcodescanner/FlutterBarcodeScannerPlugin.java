package com.amolg.flutterbarcodescanner;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.vision.barcode.Barcode;

import java.util.Map;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter;

/**
 * FlutterBarcodeScannerPlugin
 * Updated for Flutter V2 embedding only.
 * Keeps original scanning logic exactly.
 * Able to run with Flutter stable
 */
public class FlutterBarcodeScannerPlugin implements 
        MethodChannel.MethodCallHandler,
        ActivityAware,
        FlutterPlugin,
        EventChannel.StreamHandler,
        io.flutter.plugin.common.PluginRegistry.ActivityResultListener {

    private static final String CHANNEL = "flutter_barcode_scanner";
    private static final String TAG = FlutterBarcodeScannerPlugin.class.getSimpleName();
    private static final int RC_BARCODE_CAPTURE = 9001;

    private static FlutterActivity activity;
    private static Result pendingResult;
    private Map<String, Object> arguments;

    public static String lineColor = "";
    public static boolean isShowFlashIcon = false;
    public static boolean isContinuousScan = false;

    static EventChannel.EventSink barcodeStream;
    private EventChannel eventChannel;
    private MethodChannel channel;

    private FlutterPluginBinding pluginBinding;
    private ActivityPluginBinding activityBinding;
    private Application applicationContext;
    private Lifecycle lifecycle;
    private LifeCycleObserver observer;

    // ===== FlutterPlugin =====
    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = binding;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = null;
    }

    // ===== ActivityAware =====
    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityBinding = binding;
        createPluginSetup(
                pluginBinding.getBinaryMessenger(),
                (Application) pluginBinding.getApplicationContext(),
                binding.getActivity(),
                binding
        );
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        clearPluginSetup();
    }

    // ===== MethodCallHandler =====
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        try {
            pendingResult = result;

            if (call.method.equals("scanBarcode")) {
                if (!(call.arguments instanceof Map)) {
                    throw new IllegalArgumentException("Plugin not passing a map as parameter: " + call.arguments);
                }
                arguments = (Map<String, Object>) call.arguments;
                lineColor = (String) arguments.get("lineColor");
                isShowFlashIcon = (boolean) arguments.get("isShowFlashIcon");

                if (lineColor == null || lineColor.trim().isEmpty()) {
                    lineColor = "#DC143C";
                }

                if (arguments.get("scanMode") != null) {
                    if ((int) arguments.get("scanMode") == BarcodeCaptureActivity.SCAN_MODE_ENUM.DEFAULT.ordinal()) {
                        BarcodeCaptureActivity.SCAN_MODE = BarcodeCaptureActivity.SCAN_MODE_ENUM.QR.ordinal();
                    } else {
                        BarcodeCaptureActivity.SCAN_MODE = (int) arguments.get("scanMode");
                    }
                } else {
                    BarcodeCaptureActivity.SCAN_MODE = BarcodeCaptureActivity.SCAN_MODE_ENUM.QR.ordinal();
                }

                isContinuousScan = (boolean) arguments.get("isContinuousScan");
                startBarcodeScannerActivityView((String) arguments.get("cancelButtonText"), isContinuousScan);
            } else {
                result.notImplemented();
            }
        } catch (Exception e) {
            Log.e(TAG, "onMethodCall: " + e.getLocalizedMessage());
        }
    }

    // ===== Barcode Activity Handling =====
    private void startBarcodeScannerActivityView(String buttonText, boolean isContinuousScan) {
        try {
            Intent intent = new Intent(activity, BarcodeCaptureActivity.class)
                    .putExtra("cancelButtonText", buttonText);

            if (isContinuousScan) {
                activity.startActivity(intent);
            } else {
                activity.startActivityForResult(intent, RC_BARCODE_CAPTURE);
            }
        } catch (Exception e) {
            Log.e(TAG, "startView: " + e.getLocalizedMessage());
        }
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RC_BARCODE_CAPTURE) {
            if (resultCode == CommonStatusCodes.SUCCESS) {
                if (data != null) {
                    try {
                        Barcode barcode = data.getParcelableExtra(BarcodeCaptureActivity.BarcodeObject);
                        pendingResult.success(barcode.rawValue);
                    } catch (Exception e) {
                        pendingResult.success("-1");
                    }
                } else {
                    pendingResult.success("-1");
                }
            } else {
                pendingResult.success("-1");
            }
            pendingResult = null;
            arguments = null;
            return true;
        }
        return false;
    }

    // ===== EventChannel =====
    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        barcodeStream = events;
    }

    @Override
    public void onCancel(Object arguments) {
        barcodeStream = null;
    }

    public static void onBarcodeScanReceiver(final Barcode barcode) {
        if (barcode != null && !barcode.displayValue.isEmpty()) {
            activity.runOnUiThread(() -> barcodeStream.success(barcode.rawValue));
        }
    }

    // ===== Setup/Cleanup =====
    private void createPluginSetup(
            final BinaryMessenger messenger,
            final Application applicationContext,
            final Activity activity,
            final ActivityPluginBinding activityBinding) {

        FlutterBarcodeScannerPlugin.activity = (FlutterActivity) activity;

        eventChannel = new EventChannel(messenger, "flutter_barcode_scanner_receiver");
        eventChannel.setStreamHandler(this);

        this.applicationContext = applicationContext;
        channel = new MethodChannel(messenger, CHANNEL);
        channel.setMethodCallHandler(this);

        activityBinding.addActivityResultListener(this);
        lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(activityBinding);
        observer = new LifeCycleObserver(activity);
        lifecycle.addObserver(observer);
    }

    private void clearPluginSetup() {
        activity = null;
        if (activityBinding != null) {
            activityBinding.removeActivityResultListener(this);
            activityBinding = null;
        }
        if (lifecycle != null && observer != null) {
            lifecycle.removeObserver(observer);
            lifecycle = null;
        }
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
        if (eventChannel != null) {
            eventChannel.setStreamHandler(null);
            eventChannel = null;
        }
        if (applicationContext != null && observer != null) {
            applicationContext.unregisterActivityLifecycleCallbacks(observer);
            applicationContext = null;
        }
    }

    // ===== Lifecycle Observer =====
    private static class LifeCycleObserver implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
        private final Activity thisActivity;

        LifeCycleObserver(Activity activity) {
            this.thisActivity = activity;
        }

        @Override public void onCreate(@NonNull LifecycleOwner owner) {}
        @Override public void onStart(@NonNull LifecycleOwner owner) {}
        @Override public void onResume(@NonNull LifecycleOwner owner) {}
        @Override public void onPause(@NonNull LifecycleOwner owner) {}
        @Override public void onStop(@NonNull LifecycleOwner owner) { onActivityStopped(thisActivity); }
        @Override public void onDestroy(@NonNull LifecycleOwner owner) { onActivityDestroyed(thisActivity); }

        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        @Override public void onActivityDestroyed(Activity activity) {
            if (thisActivity == activity && activity.getApplicationContext() != null) {
                ((Application) activity.getApplicationContext())
                        .unregisterActivityLifecycleCallbacks(this);
            }
        }
        @Override public void onActivityStopped(Activity activity) {}
    }
}
