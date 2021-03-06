package pl.pola_app.ui.fragment;

import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.vision.CameraSource;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CompoundBarcodeView;
import com.journeyapps.barcodescanner.Size;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;
import pl.pola_app.PolaApplication;
import pl.pola_app.R;
import pl.pola_app.helpers.Utils;
import pl.pola_app.ui.activity.ActivityWebView;
import pl.pola_app.ui.activity.CreateReportActivity;
import pl.pola_app.ui.event.ProductItemClickedEvent;
import pl.pola_app.ui.scanner.CameraSourcePreview;
import pl.pola_app.ui.scanner.ScannerBox;
import pl.tajchert.nammu.Nammu;
import pl.tajchert.nammu.PermissionCallback;
import timber.log.Timber;

public class ScannerFragment extends Fragment implements CompoundBarcodeView.TorchListener {
    private static final String TAG = ScannerFragment.class.getSimpleName();

    public interface BarcodeScannedListener {
        void barcodeScanned(String result);
    }

    private BarcodeScannedListener barcodeScannedListener;

    @Inject
    Bus eventBus;

    @Bind(R.id.preview)
    CameraSourcePreview mPreview;//Google Mobile Vision Barcode API
    @Bind(R.id.scannerBox)
    ScannerBox scannerBox;//White rectangle over mPreview View
    @Bind(R.id.textHintScan)
    TextView textHintScan;
    @Bind(R.id.toolbar)
    Toolbar toolbar;
    @Bind(R.id.scanner_view)
    CompoundBarcodeView barcodeScanner;//ZXING this or mPreview should be used

    @Bind(R.id.flash_icon)
    ImageView flashIconView;

    private final boolean isGoogleBarcodeOperational = false;
    private long timestampLastScanned = 0;
    private long timeBetweenScans = TimeUnit.SECONDS.toMillis(1);//To slow down Google Mobile Vision as otherwise it generates few scans per each barcode.


    private CameraSource mCameraSource;
    private boolean isDecoding = true;
    private static final int RC_HANDLE_GMS = 9001;

    private boolean isTorchOn = false;

    public ScannerFragment() {
        // Required empty public constructor
    }

    public void setOnBarcodeScannedListener(BarcodeScannedListener barcodeScannedListener) {
        this.barcodeScannedListener = barcodeScannedListener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View scannerView = inflater.inflate(R.layout.fragment_scanner, container, false);
        ButterKnife.bind(this, scannerView);
        PolaApplication.component(getActivity()).inject(this);

        DisplayMetrics metrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        barcodeScanner.getBarcodeView().setFramingRectSize(new Size((int) (width*0.9f), (int) (height*0.25f)));
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);

        //layoutParams.setMargins(0, (int) (-1*(height*0.2)), 0, 0);
        barcodeScanner.setLayoutParams(layoutParams);

        CameraSettings cameraSettings = barcodeScanner.getBarcodeView().getCameraSettings();
        cameraSettings.setBarcodeSceneModeEnabled(true);
        barcodeScanner.getBarcodeView().setCameraSettings(cameraSettings);

        barcodeScanner.setStatusText(getActivity().getString(R.string.scanner_status_text));
        barcodeScanner.setTorchListener(this);
        barcodeScanner.setTorchOff();

        Nammu.askForPermission(getActivity(), android.Manifest.permission.CAMERA, permissionCameraCallback);
        ((AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
        ((AppCompatActivity) getActivity()).setTitle(getString(R.string.app_name));
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle("");
        ((AppCompatActivity) getActivity()).getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setHomeButtonEnabled(false);
        setHasOptionsMenu(true);

        return scannerView;
    }

    @Override
    public void onResume() {
        super.onResume();
        eventBus.register(this);
        //startCameraSource();//FIXME using Google mobile vision
        if(barcodeScanner != null) {
            barcodeScanner.resume();
        }
        timestampLastScanned = 0;
    }



    @Override
    public void onPause() {
        super.onPause();
        eventBus.unregister(this);
        if(barcodeScanner != null) {
            barcodeScanner.setTorchOff();
            barcodeScanner.pause();
        }
        if (mPreview != null) {
            mPreview.stop();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPreview != null) {
            mPreview.release();
        }
        ButterKnife.unbind(this);
    }

    //Good explanation https://github.com/googlesamples/android-vision/blob/master/visionSamples/barcode-reader/app/src/main/java/com/google/android/gms/samples/vision/barcodereader/BarcodeCaptureActivity.java
    /*//FIXME using Google mobile vision
    private void createCameraSource() {
        Context context = getActivity().getApplicationContext();
        BarcodeDetector barcodeDetector = new BarcodeDetector.Builder(context).build();
        barcodeDetector.setProcessor(frameProcessor);
        if(!barcodeDetector.isOperational()) {
            //Detector dependencies are not yet available.
            IntentFilter lowStorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = getActivity().registerReceiver(null, lowStorageFilter) != null;
            if (hasLowStorage) {
                isGoogleBarcodeOperational = false;//not enough space to download dependencies
            } else {
                isGoogleBarcodeOperational = true;//It will be just wait for download
            }
        } else {
            isGoogleBarcodeOperational = true;
        }
        int width;
        int height;
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        } catch (Exception e) {
            //Something went wrong, use default values  (HD)
            width = 720;
            height = 1280;
        }
        if (width <= 0) {
            width = 720;
        }
        if (height <= 0) {
            height = 1280;
        }
        CameraSource.Builder builder = new CameraSource.Builder(getActivity().getApplicationContext(), barcodeDetector)
                .setFacing(CameraSource.CAMERA_FACING_BACK)
                .setRequestedPreviewSize(width, height)
                .setRequestedFps(15.0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            builder = builder.setAutoFocusEnabled(true);
        }
        mCameraSource = builder.build();
    }*/

    /*//FIXME using Google mobile vision
    private void startCameraSource() throws SecurityException {
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(getActivity().getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg = GoogleApiAvailability.getInstance().getErrorDialog(getActivity(), code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource);
                if(isGoogleBarcodeOperational) {
                    shutDownZxing();
                } else {
                    shutDownGoogleBarcode();
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                shutDownGoogleBarcode();
            }
        }
    }*/

    private void shutDownZxing() {
        if(barcodeScanner != null) {
            barcodeScanner.pause();
            barcodeScanner.setVisibility(View.GONE);
            barcodeScanner = null;
        }
    }

    /*//FIXME using Google mobile vision
    private void shutDownGoogleBarcode() {
        if(mCameraSource != null) {
            mCameraSource.release();
            mCameraSource = null;
        }
        if(barcodeScanner != null) {
            barcodeScanner.setVisibility(View.VISIBLE);
            barcodeScanner.resume();
        }
        if(mPreview != null && scannerBox != null) {
            mPreview.setVisibility(View.GONE);
            scannerBox.setVisibility(View.GONE);
        }
        if(BuildConfig.USE_CRASHLYTICS) {
            Answers.getInstance().logCustom(new CustomEvent("ShutdownGScanner")
                    .putCustomAttribute("deviceId", Utils.getDeviceId(getActivity().getApplicationContext())));
        }
    }*/

    public void resumeScanning() {
        if(barcodeScanner != null) {
            barcodeScanner.decodeContinuous(callback);
        }
        isDecoding = true;
    }

    public void updateBoxPosition(int numberOfCards) {
        //FIXME using Google mobile vision
        /*if(scannerBox.getVisibility() == View.VISIBLE) {
            if (numberOfCards == 0) {
                if (textHintScan != null) {
                    textHintScan.setVisibility(View.VISIBLE);
                }
                scannerBox.setDefaultPosition(getActivity());
            } else if (numberOfCards >= 5) {
                if (textHintScan != null) {
                    textHintScan.setVisibility(View.GONE);
                }
                scannerBox.setMovedPosition(getActivity(), 5);
            } else {
                if (textHintScan != null) {
                    textHintScan.setVisibility(View.GONE);
                }
                scannerBox.setMovedPosition(getActivity(), numberOfCards);
            }
        }*/
    }

    final PermissionCallback permissionCameraCallback = new PermissionCallback() {
        @Override
        public void permissionGranted() {
            resumeScanning();
            //createCameraSource();//FIXME using Google mobile vision
        }

        @Override
        public void permissionRefused() {
            Toast.makeText(getActivity(), getString(R.string.toast_no_camera_access), Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_main, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // handle item selection
        Intent intent;
        switch (item.getItemId()) {
            case R.id.action_about:
                intent = new Intent(getActivity(), ActivityWebView.class);
                intent.putExtra("url", Utils.URL_POLA_ABOUT);
                startActivity(intent);
                return true;
            case R.id.action_metodology:
                intent = new Intent(getActivity(), ActivityWebView.class);
                intent.putExtra("url", Utils.URL_POLA_METHOD);
                startActivity(intent);
                return true;
            case R.id.action_club:
                intent = new Intent(getActivity(), ActivityWebView.class);
                intent.putExtra("url", Utils.URL_POLA_KJ);
                startActivity(intent);
                return true;
            case R.id.action_team:
                intent = new Intent(getActivity(), ActivityWebView.class);
                intent.putExtra("url", Utils.URL_POLA_TEAM);
                startActivity(intent);
                return true;
            case R.id.action_partners:
                intent = new Intent(getActivity(), ActivityWebView.class);
                intent.putExtra("url", Utils.URL_POLA_PARTNERS);
                startActivity(intent);
                return true;
            case R.id.action_bug:
                intent = new Intent(getActivity(), CreateReportActivity.class);
                intent.setAction("product_report");
                startActivity(intent);
                return true;
            case R.id.action_mail:
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", Utils.POLA_MAIL, null));
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Pola");
                startActivity(Intent.createChooser(emailIntent, getString(R.string.send_email_picker)));
                return true;
            case R.id.action_rate:
                Uri uri = Uri.parse("market://details?id=" + getActivity().getPackageName());
                intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY |
                        Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET |
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://play.google.com/store/apps/details?id=" + getActivity().getPackageName())));
                }
                return true;
            case R.id.action_fb:
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Utils.URL_POLA_FB));
                startActivity(intent);
                return true;
            case R.id.action_twitter:
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Utils.URL_POLA_TWITTER));
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    //Handlling Google Mobile Vision Detections if there are any
    //FIXME using Google mobile vision
    /*Detector.Processor frameProcessor = new Detector.Processor<Barcode>() {
        @Override
        public void release() {
            Log.d(TAG, "release: ");
        }

        @Override
        public void receiveDetections(Detector.Detections<Barcode> detections) {
            if (detections != null
                    && detections.getDetectedItems() != null
                    && detections.getDetectedItems().size() > 0
                    && System.currentTimeMillis() - timestampLastScanned > timeBetweenScans
                    && isDecoding) {
                timestampLastScanned = System.currentTimeMillis();
                for (int i = 0; i < detections.getDetectedItems().size(); i++) {
                    int key = detections.getDetectedItems().keyAt(i);
                    Barcode barcode = detections.getDetectedItems().get(key);
                    if (barcode != null) {
                        scannedBarcode(barcode);
                    }
                }
                isDecoding = false;
            }
        }
    };

    //Google Mobile Vision barcode result
    private void scannedBarcode(Barcode barcode) {
        final String result = barcode.displayValue;
        if (result != null) {
            ((Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (barcodeScannedListener != null) {
                        barcodeScannedListener.barcodeScanned(result);
                    }
                }
            });
            Timber.d(result);
        }
    }*/

    //ZXING barcode result
    private BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(final BarcodeResult result) {
            if (result.getText() != null) {
                if(barcodeScanner != null) {
                    barcodeScanner.getBarcodeView().stopDecoding();
                    barcodeScanner.setStatusText("");
                }
                ((Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (barcodeScannedListener != null) {
                            barcodeScannedListener.barcodeScanned(result.getText());
                        }
                    }
                });

                Timber.d(result.getText());
                Timber.d(result.getBarcodeFormat().toString());

            }
        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {
        }
    };

    @OnClick(R.id.flash_icon)
    public void onFlashIconClicked() {
        if(isTorchOn) {
            barcodeScanner.setTorchOff();
        } else {
            barcodeScanner.setTorchOn();
        }
    }

    @Override
    public void onTorchOn() {
        isTorchOn = true;
        if(flashIconView != null) {
            flashIconView.setImageDrawable(ContextCompat.getDrawable(getActivity(), R.drawable.ic_flash_off_white_48dp));
        }
    }

    @Override
    public void onTorchOff() {
        isTorchOn = false;
        if(flashIconView != null) {
            flashIconView.setImageDrawable(ContextCompat.getDrawable(getActivity(), R.drawable.ic_flash_on_white_48dp));
        }
    }

    @Subscribe
    public void productItemClicked(ProductItemClickedEvent event) {
        if(isTorchOn) {
            barcodeScanner.setTorchOff();
        }
    }
}
