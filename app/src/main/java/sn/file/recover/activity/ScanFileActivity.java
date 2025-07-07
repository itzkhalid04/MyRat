package sn.file.recover.activity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.os.EnvironmentCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import sn.file.recover.R;
import sn.file.recover.RecoverPhotosAsyncTask;
import sn.file.recover.adapter.AdapterFileDefault;
import sn.file.recover.model.AlbumPhoto;
import sn.file.recover.model.ItemFileScan;
import sn.file.recover.utils.Utils;

public class ScanFileActivity extends AppCompatActivity implements View.OnClickListener, AdapterFileDefault.ClickItemFileCallBack {
    private ImageView imgBack;
    private TextView tvSelectAll, tvNumberScan;
    private RecyclerView lvFileScan;
    private int type = 1;
    private ImageView imgPauAndPlay;
    private ProgressBar progressBar;
    public static ArrayList<AlbumPhoto> mAlbumPhoto = new ArrayList<>();
    private ArrayList<ItemFileScan> arrFileScan = new ArrayList<>();
    private AdapterFileDefault adapterFileDefault;
    private LinearLayout llRecoverControl, llCleanUp, llRecoverNow;
    private boolean checkPause = false;
    private RelativeLayout rlScanProgress;
    private TextView tvRecoverSelected, tvCleanSelected;
    private RecoverPhotosAsyncTask mRecoverPhotosAsyncTask;
    private AdView adView;
    private InterstitialAd interstitialAd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_file);
        initViews();
        initAds();
    }

    private void initAds() {
        MobileAds.initialize(this, initializationStatus -> {});

        // Load banner ad
        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);

        // Load interstitial ad
        InterstitialAd.load(this, getString(R.string.ads_full), adRequest,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitialAd = ad;
                        setAdCallbacks();
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
                    interstitialAd = null;
                    initAds(); // Load a new ad
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                    interstitialAd = null;
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
        tvRecoverSelected = findViewById(R.id.tv_recover_number);
        tvCleanSelected = findViewById(R.id.tv_clean_number);
        rlScanProgress = findViewById(R.id.rl_control_data);
        llRecoverControl = findViewById(R.id.ll_recover_control);
        llRecoverNow = findViewById(R.id.ll_recover_now);
        llRecoverNow.setOnClickListener(this);
        llCleanUp = findViewById(R.id.ll_clean_recover);
        llCleanUp.setOnClickListener(this);
        adView = findViewById(R.id.adView);

        type = getIntent().getIntExtra(MainActivity.TYPE_SCAN, 0);
        progressBar = findViewById(R.id.progress_scan);
        progressBar.setMax(15000);
        progressBar.setProgress(0);
        imgPauAndPlay = findViewById(R.id.pause_and_play_scan);
        imgPauAndPlay.setOnClickListener(this);
        lvFileScan = findViewById(R.id.lv_file);
        adapterFileDefault = new AdapterFileDefault(this, arrFileScan);
        adapterFileDefault.setCallBack(this);
        lvFileScan.setLayoutManager(new GridLayoutManager(this, 4));
        lvFileScan.setAdapter(adapterFileDefault);
        tvNumberScan = findViewById(R.id.tv_number_scan);
        tvSelectAll = findViewById(R.id.tv_select_all);
        tvSelectAll.setOnClickListener(this);
        imgBack = findViewById(R.id.img_back);
        imgBack.setOnClickListener(this);

        new ScanAsyncTask(type).execute();
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
        if (mRecoverPhotosAsyncTask != null) {
            mRecoverPhotosAsyncTask.cancel(true);
        }
        interstitialAd = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mRecoverPhotosAsyncTask != null) {
            mRecoverPhotosAsyncTask.cancel(true);
        }
        super.onBackPressed();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.tv_select_all) {
            selectAllFiles();
        } else if (id == R.id.img_back) {
            finish();
        } else if (id == R.id.pause_and_play_scan) {
            checkPause = !checkPause;
            imgPauAndPlay.setImageResource(checkPause ? R.drawable.ic_play : R.drawable.ic_pause);
        } else if (id == R.id.ll_recover_now) {
            recoverSelectedFiles();
        } else if (id == R.id.ll_clean_recover) {
            clearSelection();
        }
    }

    private void selectAllFiles() {
        for (ItemFileScan file : arrFileScan) {
            file.setCheck(true);
        }
        adapterFileDefault.notifyDataSetChanged();
        updateNumberFile();
    }

    private void recoverSelectedFiles() {
        ArrayList<ItemFileScan> selectedFiles = new ArrayList<>();
        for (ItemFileScan file : arrFileScan) {
            if (file.isCheck()) {
                selectedFiles.add(file);
            }
        }

        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.warning_select_image), Toast.LENGTH_SHORT).show();
            return;
        }

        mRecoverPhotosAsyncTask = new RecoverPhotosAsyncTask(type, this, selectedFiles, () -> {
            showInterstitialAd();
            Intent intent = new Intent(this, RestoreResultActivity.class);
            intent.putExtra("value", selectedFiles.size());
            intent.putExtra("type", 0);
            startActivity(intent);
        });
        mRecoverPhotosAsyncTask.execute();
    }

    private void clearSelection() {
        for (ItemFileScan file : arrFileScan) {
            file.setCheck(false);
        }
        adapterFileDefault.notifyDataSetChanged();
        tvCleanSelected.setVisibility(View.GONE);
        tvRecoverSelected.setVisibility(View.GONE);
    }

    @Override
    public void checkItemFile(int position) {
        ItemFileScan file = arrFileScan.get(position);
        file.setCheck(!file.isCheck());
        updateNumberFile();
    }

    private void updateNumberFile() {
        int selectedCount = 0;
        for (ItemFileScan file : arrFileScan) {
            if (file.isCheck()) {
                selectedCount++;
            }
        }

        if (selectedCount == 0) {
            tvRecoverSelected.setVisibility(View.GONE);
            tvCleanSelected.setVisibility(View.GONE);
        } else {
            tvRecoverSelected.setVisibility(View.VISIBLE);
            tvCleanSelected.setVisibility(View.VISIBLE);
            tvRecoverSelected.setText(getString(R.string.selected_count, selectedCount));
            tvCleanSelected.setText(getString(R.string.selected_count, selectedCount));
        }
        adapterFileDefault.notifyDataSetChanged();
    }

    private class ScanAsyncTask extends AsyncTask<Void, Integer, Void> {
        private final int typeScan;
        private final List<ItemFileScan> listPhoto = new ArrayList<>();
        private final List<ItemFileScan> listVideo = new ArrayList<>();
        private int number = 0;

        public ScanAsyncTask(int typeScan) {
            this.typeScan = typeScan;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            number = 0;
            llRecoverControl.setVisibility(View.GONE);
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            progressBar.setProgress(15000);
            progressBar.setProgress(0);
            llRecoverControl.setVisibility(View.VISIBLE);
            rlScanProgress.setVisibility(View.GONE);

            Collections.sort(arrFileScan, (f1, f2) -> Long.compare(f2.getSizeFile(), f1.getSizeFile()));
            adapterFileDefault.notifyDataSetChanged();
            Toast.makeText(ScanFileActivity.this, "Scan completed", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
            progressBar.setProgress(values[0]);
            tvNumberScan.setText(getString(R.string.all_files_count, values[0]));

            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    adapterFileDefault.notifyDataSetChanged(), 1000);
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                Thread.sleep(2000); // Initial delay
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (checkPause) {
                return null;
            }

            String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();

            if (typeScan == 0) {
                scanImages(rootPath);
            } else if (typeScan == 1) {
                scanVideos(rootPath);
            }

            return null;
        }

        private void scanImages(String rootPath) {
            try {
                getSdCardImages();
                checkFileOfDirectoryImage(rootPath, Utils.getFileList(rootPath));
                Collections.sort(mAlbumPhoto, (p1, p2) ->
                        Long.compare(p2.getLastModified(), p1.getLastModified()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void scanVideos(String rootPath) {
            getSdCardVideos();
            checkFileOfDirectoryVideo(rootPath, Utils.getFileList(rootPath));
            Collections.sort(mAlbumPhoto, (v1, v2) ->
                    Long.compare(v2.getLastModified(), v1.getLastModified()));
        }

        private void checkFileOfDirectoryImage(String path, File[] files) {
            if (files == null) return;

            for (File file : files) {
                if (checkPause) break;

                if (file.isDirectory()) {
                    checkFileOfDirectoryImage(file.getPath(), Utils.getFileList(file.getPath()));
                } else {
                    processImageFile(file);
                }
            }
        }

        private void processImageFile(File file) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);

            if (options.outWidth != -1 && options.outHeight != -1) {
                String path = file.getPath();
                long size = file.length();
                long lastModified = file.lastModified();

                if (path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                        path.endsWith(".png") || path.endsWith(".gif")) {
                    if (size > 10000) {
                        addFileToList(path, lastModified, size);
                    }
                } else if (size > 50000) {
                    addFileToList(path, lastModified, size);
                }
            }
        }

        private void checkFileOfDirectoryVideo(String path, File[] files) {
            if (files == null) return;

            for (File file : files) {
                if (checkPause) break;

                if (file.isDirectory()) {
                    checkFileOfDirectoryVideo(file.getPath(), Utils.getFileList(file.getPath()));
                } else if (file.getPath().endsWith(".3gp") || file.getPath().endsWith(".mp4") ||
                        file.getPath().endsWith(".mkv") || file.getPath().endsWith(".flv")) {
                    processVideoFile(file);
                }
            }
        }

        private void processVideoFile(File file) {
            long duration = 0;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getPath());
                String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (dur != null) duration = Long.parseLong(dur);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                retriever.release();
            }

            addFileToList(file.getPath(), file.lastModified(), file.length());
        }

        private void addFileToList(String path, long lastModified, long size) {
            ItemFileScan file = new ItemFileScan(false, path, lastModified, size);
            listPhoto.add(file);
            arrFileScan.add(file);
            publishProgress(++number);
        }

        private void getSdCardImages() {
            String[] storageDirs = getExternalStorageDirectories();
            if (storageDirs != null) {
                for (String dir : storageDirs) {
                    File file = new File(dir);
                    if (file.exists()) {
                        checkFileOfDirectoryImage(dir, file.listFiles());
                    }
                }
            }
        }

        private void getSdCardVideos() {
            String[] storageDirs = getExternalStorageDirectories();
            if (storageDirs != null) {
                for (String dir : storageDirs) {
                    File file = new File(dir);
                    if (file.exists()) {
                        checkFileOfDirectoryVideo(dir, file.listFiles());
                    }
                }
            }
        }
    }

    private String[] getExternalStorageDirectories() {
        List<String> directories = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            File[] externalFilesDirs = getExternalFilesDirs(null);
            if (externalFilesDirs != null) {
                for (File file : externalFilesDirs) {
                    if (file != null) {
                        String path = file.getPath().split("/Android")[0];
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            if (Environment.isExternalStorageRemovable(file)) {
                                directories.add(path);
                            }
                        } else if ("mounted".equals(EnvironmentCompat.getStorageState(file))) {
                            directories.add(path);
                        }
                    }
                }
            }
        }

        if (directories.isEmpty()) {
            String mountOutput = getMountOutput();
            if (!mountOutput.trim().isEmpty()) {
                String[] lines = mountOutput.split("\n");
                for (String line : lines) {
                    String[] parts = line.split(" ");
                    if (parts.length > 2) {
                        directories.add(parts[2]);
                    }
                }
            }
        }

        return directories.toArray(new String[0]);
    }

    private String getMountOutput() {
        Process process = null;
        InputStream inputStream = null;
        try {
            process = new ProcessBuilder()
                    .command("mount")
                    .redirectErrorStream(true)
                    .start();
            process.waitFor();
            inputStream = process.getInputStream();
            byte[] buffer = new byte[1024];
            StringBuilder output = new StringBuilder();
            while (inputStream.read(buffer) != -1) {
                output.append(new String(buffer));
            }
            return output.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (process != null) {
                process.destroy();
            }
        }
    }
}