package com.ShotAndGo;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SystemState;
import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.mission.waypoint.Waypoint;
import dji.common.mission.waypoint.WaypointMission;
import dji.common.mission.waypoint.WaypointMissionDownloadEvent;
import dji.common.mission.waypoint.WaypointMissionExecutionEvent;
import dji.common.mission.waypoint.WaypointMissionFinishedAction;
import dji.common.mission.waypoint.WaypointMissionFlightPathMode;
import dji.common.mission.waypoint.WaypointMissionHeadingMode;
import dji.common.mission.waypoint.WaypointMissionUploadEvent;
import dji.common.product.Model;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.log.DJILog;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.useraccount.UserAccountManager;


import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener, GoogleMap.OnMapClickListener, OnMapReadyCallback {

    //Системые объекты
    private static final String TAG = MainActivity.class.getName();
    private static final String[] REQUIRED_PERMISSION_LIST = new String[]{
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
    };
    private List<String> missingPermission = new ArrayList<>();
    private static final int REQUEST_PERMISSION_CODE = 12345;

    //Объект интерейса
    private TextView mSystemMessage;
    private CheckBox mConnectStatusCheckBox;
    private TextView mCountRedHit = null;
    private TextView mCountGreenHit = null;
    private TextView mCountBlueHit = null;
    private TextureView mVideoSurface = null;
    private CheckBox mRedPlayerCheck = null;
    private CheckBox mGreenPlayerCheck = null;
    private CheckBox mBluePlayerCheck = null;

    //Объекты для выовда обработанного потока
    private Canvas canvas;
    private TextureView modifiedVideostreamPreview;

    //Объекты свзязанные с регистрацией и упавлением DJI
    private AtomicBoolean isRegistrationInProgress = new AtomicBoolean(false);
    protected DJICodecManager mCodecManager = null;
    protected VideoFeeder.VideoDataListener mReceivedVideoDataListener = null;
    private boolean registrationSuccess = false;

    //Объекты игроков
    private Integer mRedPlayerHit = 0;
    private Boolean isReadHit = false;
    private Integer mGreenPlayerHit = 0;
    private Boolean isGreenHit = false;
    private Integer mBluePlayerHit = 0;
    private Boolean isBlueHit = false;

    //Объекты для обрабтки видео
    private Mat srcFrame; //Искодный кадр

    private Handler handler;

    //map point and google map and droone locating
    private GoogleMap gMap;
    private final Map<Integer, Marker> mMarkers = new ConcurrentHashMap<>();
    private Marker droneMarker = null;

    private float altutude = 1.0f;
    private float mspeed = 3.0f;

    private double droneLocationLat = 52.280, droneLocationLng = 48.999;
    private FlightController mFlightController;

    private List<Waypoint> waypointList = new ArrayList<>();
    public static WaypointMission.Builder waypointMissionBuilder;
    private FlightController flightController;
    private WaypointMissionOperator instance;
    private WaypointMissionFinishedAction mFinishedAction = WaypointMissionFinishedAction.NO_ACTION;
    private WaypointMissionHeadingMode mHeadingMode = WaypointMissionHeadingMode.AUTO;

    //color filter
    private Mat redHSVFrame;
    private Moments redMoments;
    private final float minMomentHit = 10000;



    private BaseLoaderCallback mLoaderCallback  = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");
                    // Create and set View
                    //mView = new puzzle15View(mAppContext);
                    //setContentView(mView);
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };



    //Метод выполняется при запуске программы
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkAndRequestPermissions();
        setContentView(R.layout.activity_main);
        initUI();

        handler = new Handler();
        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(FPVDemoApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);

        addListener();
    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }

        if(mVideoSurface == null) {
            Log.e(TAG, "mVideoSurface is null");
        }


        srcFrame = new Mat();
        redHSVFrame = new Mat();
        initFlightController();
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        uninitPreviewer();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view){
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        uninitPreviewer();
        removeListener();
        super.onDestroy();
    }

    protected void onProductChange() {
        initPreviewer();
        loginAccount();
    }

    private void loginAccount(){

        UserAccountManager.getInstance().logIntoDJIUserAccount(this,
                new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                    @Override
                    public void onSuccess(final UserAccountState userAccountState) {
                        Log.e(TAG, "Login Success");
                    }
                    @Override
                    public void onFailure(DJIError error) {
                        showToast("Login Error:"
                                + error.getDescription());
                    }
                });
    }


    //Метод инициализации элементов интерфейса
    private void initUI() {
        mCountRedHit = (TextView)findViewById(R.id.countRedHit);
        mCountGreenHit = (TextView)findViewById(R.id.countGreenHit);
        mCountBlueHit = (TextView)findViewById(R.id.countBlueHit);
        mRedPlayerCheck = (CheckBox)findViewById(R.id.redPlayerCheck);
        mGreenPlayerCheck = (CheckBox)findViewById(R.id.greenPlayerCheck);
        mBluePlayerCheck = (CheckBox)findViewById(R.id.bluePlayerCheck);

        mSystemMessage = (TextView)findViewById(R.id.systemMessage);
        mConnectStatusCheckBox = (CheckBox)findViewById(R.id.djiStatusCheck);
        mConnectStatusCheckBox.setClickable(false);
        mVideoSurface = (TextureView)findViewById(R.id.invisibleVideoStream);
        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }

        modifiedVideostreamPreview = findViewById(R.id.modified_livestream_preview_ttv);
    }

    private void initPreviewer() {

        BaseProduct product = FPVDemoApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            showToast(getString(R.string.disconnected));
        } else {
            if (null != mVideoSurface) {
                mVideoSurface.setSurfaceTextureListener(this);
            }
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mReceivedVideoDataListener);
            }
        }
    }

    private void uninitPreviewer() {
        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null){
            // Reset the callback
            VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(null);
        }
    }

    //Проверка и запрос разрешений
    private void checkAndRequestPermissions() {
        // Проверка наличия всех необходимых разрешений
        for (String eachPermission : REQUIRED_PERMISSION_LIST) {
            if (ContextCompat.checkSelfPermission(this, eachPermission) != PackageManager.PERMISSION_GRANTED) {
                missingPermission.add(eachPermission);
            }
        }
        // Запрос недостающих разрешений
        if (!missingPermission.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this, missingPermission.toArray(new String[missingPermission.size()]),
                    REQUEST_PERMISSION_CODE);
        }
    }

    //Метод вызывается после получения разрешений от пользователя
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Check for granted permission and remove from missing list
        if (requestCode == REQUEST_PERMISSION_CODE) {
            for (int i = grantResults.length - 1; i >= 0; i--) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    missingPermission.remove(permissions[i]);
                }
            }
        }
        // If there is enough permission, we will start the registration
        if (missingPermission.isEmpty()) {
            //startSDKRegistration();
            mSystemMessage.setText("All permissions");
        }
        else {
            showToast("Missing permissions!!!");
        }
    }

    private void startSDKRegistration() {
        if (isRegistrationInProgress.compareAndSet(false, true)) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    showToast( "registering, pls wait...");

                    DJISDKManager.getInstance().registerApp(getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
                        @Override
                        public void onRegister(DJIError djiError) {
                            if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                                DJILog.e("App registration", DJISDKError.REGISTRATION_SUCCESS.getDescription());
                                DJISDKManager.getInstance().startConnectionToProduct();
                                showToast("Register Success");
                                registrationSuccess = true;
                            } else {
                                showToast( "Register sdk fails, check network is available");
                                registrationSuccess = false;
                            }
                            Log.v(TAG, djiError.getDescription());
                        }

                        @Override
                        public void onProductDisconnect() {
                            Log.d(TAG, "onProductDisconnect");
                            showToast("Product Disconnected");
                            mConnectStatusCheckBox.setChecked(false);
                        }

                        @Override
                        public void onProductConnect(BaseProduct baseProduct) {
                            Log.d(TAG, String.format("onProductConnect newProduct:%s", baseProduct));
                            showToast("Product Connected");

                        }

                        @Override
                        public void onComponentChange(BaseProduct.ComponentKey componentKey, BaseComponent oldComponent,
                                                      BaseComponent newComponent) {

                            if (newComponent != null) {
                                newComponent.setComponentListener(new BaseComponent.ComponentListener() {

                                    @Override
                                    public void onConnectivityChange(boolean isConnected) {
                                        Log.d(TAG, "onComponentConnectivityChanged: " + isConnected);
                                    }
                                });
                            }
                            Log.d(TAG, String.format("onComponentChange key:%s, oldComponent:%s, newComponent:%s",
                                    componentKey, oldComponent, newComponent));

                        }

                        @Override
                        public void onInitProcess(DJISDKInitEvent djisdkInitEvent, int i) {

                        }
                    });
                }
            });
        }
    }

    public static boolean checkGpsCoordination(double latitude, double longitude) {
        return (latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180) && (latitude != 0f && longitude != 0f);
    }

    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSDKRelativeUI();
            initFlightController();
        }
    };

    private void refreshSDKRelativeUI() {
        BaseProduct mProduct = FPVDemoApplication.getProductInstance();

        if (null != mProduct && mProduct.isConnected()) {

            Log.v(TAG, "refreshSDK: True");

            String str = mProduct instanceof Aircraft ? "DJIAircraft" : "DJIHandHeld";
            mSystemMessage.setText("Status: " + str + " connected");

            if (null != mProduct.getModel()) {
                mSystemMessage.setText("" + mProduct.getModel().getDisplayName());
            } else {
                mSystemMessage.setText(R.string.product_information);
            }

        } else {

            Log.v(TAG, "refreshSDK: False");

            mSystemMessage.setText(R.string.product_information);
            mSystemMessage.setText(R.string.connection_loose);
        }
    }

    public void startButtonClick(View view) {
        mSystemMessage.setText("Start Button is pressed!");
        captureAction();
        uploadWayPointMission();
        mFlightController.startTakeoff(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                showToast("FAIL TAKE OFF");
            }
        });
    }

    public void stopButtonClick(View view) {
        mFlightController.startLanding(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                showToast("Fail landing");
            }
        });
        mSystemMessage.setText("Stop Button is pressed!");
        mRedPlayerHit = 0;
        mCountRedHit.setText(mRedPlayerHit.toString());
        mGreenPlayerHit = 0;
        mCountGreenHit.setText(mGreenPlayerHit.toString());
        mBluePlayerHit = 0;
        mCountBlueHit.setText(mBluePlayerHit.toString());
    }

    public void connectButtonClick(View view) {
        mSystemMessage.setText("Connect Button is pressed!");
        startSDKRegistration();

        if (registrationSuccess == true) {
            mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {

                @Override
                public void onReceive(byte[] videoBuffer, int size) {
                    if (mCodecManager != null) {
                        mCodecManager.sendDataToDecoder(videoBuffer, size);

                    }
                }
            };

            initPreviewer();

            Camera camera = FPVDemoApplication.getCameraInstance();
            if (camera != null) {

                camera.setSystemStateCallback(new SystemState.Callback() {
                    @Override
                    public void onUpdate(SystemState cameraSystemState) {
                        if (null != cameraSystemState) {

                            int recordTime = cameraSystemState.getCurrentVideoRecordingTimeInSeconds();
                            int minutes = (recordTime % 3600) / 60;
                            int seconds = recordTime % 60;

                            final String timeString = String.format("%02d:%02d", minutes, seconds);
                            final boolean isVideoRecording = cameraSystemState.isRecording();

                            MainActivity.this.runOnUiThread(new Runnable() {

                                @Override
                                public void run() {

                                    //recordingTime.setText(timeString);

                                    /*
                                     * Update recordingTime TextView visibility and mRecordBtn's check state
                                     */
                                    if (isVideoRecording) {
                                        //recordingTime.setVisibility(View.VISIBLE);
                                    } else {
                                        //recordingTime.setVisibility(View.INVISIBLE);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        }
    }
    private void captureAction(){

        final Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {

            SettingsDefinitions.ShootPhotoMode photoMode = SettingsDefinitions.ShootPhotoMode.SINGLE; // Set the camera capture mode as Single mode
            camera.setShootPhotoMode(photoMode, new CommonCallbacks.CompletionCallback(){
                @Override
                public void onResult(DJIError djiError) {
                    if (null == djiError) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                camera.startShootPhoto(new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {
                                        if (djiError == null) {
                                            showToast("take photo: success");
                                        } else {
                                            showToast(djiError.getDescription());
                                        }
                                    }
                                });
                            }
                        }, 2000);
                    }
                }
            });
        }
    }


    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            mCodecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureSizeChanged");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG,"onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }

        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        final Bitmap srcBitmap = mVideoSurface.getBitmap();

        if (srcBitmap != null) {
            Utils.bitmapToMat(srcBitmap, srcFrame);

            Imgproc.cvtColor(srcFrame, redHSVFrame, Imgproc.COLOR_BGR2HSV);
            Core.inRange(redHSVFrame, new Scalar(100, 200, 200), new Scalar(180, 255, 255), redHSVFrame);
            redMoments = Imgproc.moments(redHSVFrame);
            if (redMoments.m00 < minMomentHit) {
                isReadHit = true;
            }
            else {
                if (isReadHit == true) {
                    isReadHit = false;
                    mRedPlayerHit++;
                    mCountRedHit.setText(mRedPlayerHit.toString());
                    showToast(String.valueOf(redMoments.m00));
                }
            }

            //Вывод на экран обработанного видео потока
           /* Mat dstFrame = new Mat();
            Imgproc.cvtColor(redHSVFrame, dstFrame, Imgproc.COLOR_GRAY2RGB);
            Bitmap dstBitmap = Bitmap.createBitmap(srcBitmap.getWidth(), srcBitmap.getHeight(), Bitmap.Config.RGB_565);
            Utils.matToBitmap(dstFrame, dstBitmap);
            canvas = modifiedVideostreamPreview.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                if (BuildConfig.DEBUG) {
                    canvas.drawBitmap(dstBitmap,
                            new Rect(0, 0, dstBitmap.getWidth(), dstBitmap.getHeight()),
                            new Rect((canvas.getWidth() - dstBitmap.getWidth()) / 2,
                                    (canvas.getHeight() - dstBitmap.getHeight()) / 2,
                                    (canvas.getWidth() - dstBitmap.getWidth()) / 2 + dstBitmap.getWidth(),
                                    (canvas.getHeight() - dstBitmap.getHeight()) / 2
                                            + dstBitmap.getHeight()),
                            null);
                }

                modifiedVideostreamPreview.unlockCanvasAndPost(canvas);
                canvas.setBitmap(null);
                canvas = null;
                dstBitmap.recycle();
                dstBitmap = null;
            }*/
        }

    }

    public void myFirstMissionAuto(){
        LatLng pos = new LatLng(droneLocationLat, droneLocationLng);
        LatLng pos1 = new LatLng(droneLocationLat+0.00001, droneLocationLng);
        LatLng pos2 = new LatLng(droneLocationLat, droneLocationLng+0.00001);

        List<Waypoint> myMission = new ArrayList<>();
        myMission.add(new Waypoint(pos.latitude+0.00001, pos.longitude, 1.0f));
        myMission.add(new Waypoint(pos.latitude-0.00001, pos.longitude, 1.0f));
        myMission.add(new Waypoint(pos.latitude, pos.longitude+0.00001, 1.0f));
        Waypoint start = new Waypoint(pos.latitude, pos.longitude, 1.0f);
        if (waypointMissionBuilder != null) {
            waypointList.add(start);
            waypointMissionBuilder.waypointList(waypointList).waypointCount(waypointList.size());
        }
        else {
            waypointMissionBuilder = new WaypointMission.Builder();
            waypointList.add(start);
            waypointMissionBuilder.waypointList(waypointList).waypointCount(waypointList.size());
        }

        for (Waypoint p : myMission){
            waypointList.add(p);
            waypointMissionBuilder.waypointList(waypointList).waypointCount(waypointList.size());
        }

    }

    private void startWaypointMission(){

        getWaypointMissionOperator().startMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                showToast("Mission Start: " + (error == null ? "Successfully" : error.getDescription()));
            }
        });
    }

    private void stopWaypointMission(){

        getWaypointMissionOperator().stopMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                showToast("Mission Stop: " + (error == null ? "Successfully" : error.getDescription()));
            }
        });

    }

    //Add Listener for WaypointMissionOperator
    public WaypointMissionOperator getWaypointMissionOperator() {
        if (instance == null) {
            if (DJISDKManager.getInstance().getMissionControl() != null){
                instance = DJISDKManager.getInstance().getMissionControl().getWaypointMissionOperator();
            }
        }
        return instance;
    }

    private WaypointMissionOperatorListener eventNotificationListener = new WaypointMissionOperatorListener() {
        @Override
        public void onDownloadUpdate(WaypointMissionDownloadEvent downloadEvent) {

        }

        @Override
        public void onUploadUpdate(WaypointMissionUploadEvent uploadEvent) {

        }

        @Override
        public void onExecutionUpdate(WaypointMissionExecutionEvent executionEvent) {

        }

        @Override
        public void onExecutionStart() {

        }

        @Override
        public void onExecutionFinish(@Nullable final DJIError error) {
            showToast("Execution finished: " + (error == null ? "Success!" : error.getDescription()));
        }
    };

    @Override
    public void onMapClick(LatLng latLng) {
        mSystemMessage.setText("Map Button is pressed!");
    }

    private void setUpMap() {
        gMap.setOnMapClickListener(this);// add the listener for click for amap object

    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (gMap == null) {
            gMap = googleMap;
            setUpMap();
        }

        LatLng shenzhen = new LatLng(52.280, 48.9454);
        gMap.moveCamera(CameraUpdateFactory.newLatLng(shenzhen));
    }

    private void uploadWayPointMission(){

        if (waypointMissionBuilder == null){

            waypointMissionBuilder = new WaypointMission.Builder().finishedAction(mFinishedAction)
                    .headingMode(mHeadingMode)
                    .autoFlightSpeed(mspeed)
                    .maxFlightSpeed(mspeed)
                    .flightPathMode(WaypointMissionFlightPathMode.NORMAL);

        }else
        {
            waypointMissionBuilder.finishedAction(mFinishedAction)
                    .headingMode(mHeadingMode)
                    .autoFlightSpeed(mspeed)
                    .maxFlightSpeed(mspeed)
                    .flightPathMode(WaypointMissionFlightPathMode.NORMAL);

        }

        DJIError error = getWaypointMissionOperator().loadMission(waypointMissionBuilder.build());
        if (error == null) {
            showToast("loadWaypoint succeeded");
        } else {
            showToast("loadWaypoint failed " + error.getDescription());
        }

        getWaypointMissionOperator().uploadMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    showToast("Mission upload successfully!");
                } else {
                    showToast("Mission upload failed, error: " + error.getDescription() + " retrying...");
                    getWaypointMissionOperator().retryUploadMission(null);
                }
            }
        });

    }

    private void addListener() {
        if (getWaypointMissionOperator() != null){
            getWaypointMissionOperator().addListener(eventNotificationListener);
        }
    }

    private void removeListener() {
        if (getWaypointMissionOperator() != null) {
            getWaypointMissionOperator().removeListener(eventNotificationListener);
        }
    }

    private void showToast(final String toastMsg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), toastMsg, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void clear(View view){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                gMap.clear();
            }

        });
        waypointList.clear();
        try {
            waypointMissionBuilder.waypointList(waypointList);
        }
        catch (Exception e){
            showToast("Fail");
        }
        updateDroneLocation();
    }

    // Update the drone location based on states from MCU.
    private void updateDroneLocation(){

        LatLng pos = new LatLng(droneLocationLat, droneLocationLng);
        //Create MarkerOptions object
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (droneMarker != null) {
                    droneMarker.remove();
                }

                if (checkGpsCoordination(droneLocationLat, droneLocationLng)) {
                }
            }
        });
    }

    private void initFlightController() {

        BaseProduct product = FPVDemoApplication.getProductInstance();
        if (product != null && product.isConnected()) {
            if (product instanceof Aircraft) {
                mFlightController = ((Aircraft) product).getFlightController();
            }
        }

        if (mFlightController != null) {
            mFlightController.setStateCallback(new FlightControllerState.Callback() {

                @Override
                public void onUpdate(FlightControllerState djiFlightControllerCurrentState) {
                    droneLocationLat = djiFlightControllerCurrentState.getAircraftLocation().getLatitude();
                    droneLocationLng = djiFlightControllerCurrentState.getAircraftLocation().getLongitude();
                    updateDroneLocation();
                }
            });
        }
    }

}
