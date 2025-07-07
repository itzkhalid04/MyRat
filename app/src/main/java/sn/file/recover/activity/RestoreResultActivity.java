package sn.file.recover.activity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.material.tabs.TabLayout;

import sn.file.recover.R;
import sn.file.recover.adapter.ViewPagerAdapter;

public class RestoreResultActivity extends AppCompatActivity implements View.OnClickListener {
    private ImageView imgBack;
    private ViewPager vpgFileSaved;
    private TabLayout tabLayout;
    private ViewPagerAdapter viewPagerAdapter;
    private AdView adView;
    private InterstitialAd interstitialAd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_restore_result);
        initViews();
        initAds();
    }

    private void initAds() {
        MobileAds.initialize(this, initializationStatus -> {
            // Initialization complete
        });

        // Load banner ad
        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);

        // Load interstitial ad
        InterstitialAd.load(
                this,
                getString(R.string.ads_full),
                adRequest,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitialAd = ad;
                        setAdCallbacks();
                        showInterstitialAd();
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        interstitialAd = null;
                    }
                });
    }

    private void setAdCallbacks() {
        if (interstitialAd != null) {
            interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    // Ad dismissed, load a new one
                    interstitialAd = null;
                    initAds();
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

    private void initViews() {
        imgBack = findViewById(R.id.img_back);
        imgBack.setOnClickListener(this);

        tabLayout = findViewById(R.id.tab_layout);
        vpgFileSaved = findViewById(R.id.vpg_file_recovered);
        adView = findViewById(R.id.adView);

        viewPagerAdapter = new ViewPagerAdapter(
                getSupportFragmentManager(),
                FragmentStatePagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        );
        vpgFileSaved.setAdapter(viewPagerAdapter);
        tabLayout.setupWithViewPager(vpgFileSaved);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.img_back) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        if (adView != null) {
            adView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) {
            adView.resume();
        }
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
        }
        interstitialAd = null;
        super.onDestroy();
    }
}