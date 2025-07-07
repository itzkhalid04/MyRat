package sn.file.recover.activity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sn.file.recover.R;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    public static final String TYPE_SCAN = "type_scan";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final List<String> REQUIRED_PERMISSIONS = Arrays.asList(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
    );

    private ImageView imgMore;
    private InterstitialAd interstitialAd;
    private final List<String> pendingPermissions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!checkPermissions()) {
            return;
        }
        initializeAds();
        initializeViews();
    }

    private void initializeAds() {
        MobileAds.initialize(this, initializationStatus -> {
            // Ads SDK initialized
        });

        AdRequest adRequest = new AdRequest.Builder().build();
        InterstitialAd.load(
                this,
                getString(R.string.ads_full),
                adRequest,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitialAd = ad;
                        setupAdCallbacks();
                        showInterstitialAd();
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        interstitialAd = null;
                        Toast.makeText(MainActivity.this, "Ad failed to load", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void setupAdCallbacks() {
        if (interstitialAd != null) {
            interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    interstitialAd = null;
                    // Load a new ad when the current one is dismissed
                    initializeAds();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                    interstitialAd = null;
                }

                @Override
                public void onAdShowedFullScreenContent() {
                    // Ad showed
                }
            });
        }
    }

    private void showInterstitialAd() {
        if (interstitialAd != null) {
            interstitialAd.show(this);
        }
    }

    private boolean checkPermissions() {
        pendingPermissions.clear();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingPermissions.add(permission);
            }
        }

        if (!pendingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    pendingPermissions.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE
            );
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (checkPermissions()) {
                initializeViews();
            } else {
                Toast.makeText(this,
                        "Permissions required for the app to function properly",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initializeViews() {
        imgMore = findViewById(R.id.img_more);
        RelativeLayout rlScanImage = findViewById(R.id.rl_scan_photo);
        RelativeLayout rlFileSaved = findViewById(R.id.rl_file_saved);
        RelativeLayout rlScanVideo = findViewById(R.id.rl_scan_video);

        imgMore.setOnClickListener(this);
        rlScanImage.setOnClickListener(this);
        rlFileSaved.setOnClickListener(this);
        rlScanVideo.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.img_more) {
            showPopupMenu();
        } else if (id == R.id.rl_file_saved) {
            startActivity(new Intent(this, RestoreResultActivity.class));
        } else if (id == R.id.rl_scan_photo) {
            startScanActivity(0);
        } else if (id == R.id.rl_scan_video) {
            startScanActivity(1);
        }
    }

    private void startScanActivity(int type) {
        Intent intent = new Intent(this, ScanFileActivity.class);
        intent.putExtra(TYPE_SCAN, type);
        startActivity(intent);
    }

    private void showPopupMenu() {
        PopupMenu popupMenu = new PopupMenu(this, imgMore);
        popupMenu.inflate(R.menu.menu_option_main_app);
        popupMenu.setOnMenuItemClickListener(this::handleMenuItemClick);
        popupMenu.show();
    }

    private boolean handleMenuItemClick(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_rate) {
            rateApp();
            return true;
        } else if (itemId == R.id.menu_share_app) {
            shareApp();
            return true;
        }
        return false;
    }

    private void rateApp() {
        try {
            Uri uri = Uri.parse("market://details?id=" + getPackageName());
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY |
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Uri uri = Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName());
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        }
    }

    private void shareApp() {
        try {
            String shareMessage = getString(R.string.share_message, getPackageName());
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name));
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareMessage);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.choose_share_option)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up ad resources
        if (interstitialAd != null) {
            interstitialAd = null;
        }
    }
}