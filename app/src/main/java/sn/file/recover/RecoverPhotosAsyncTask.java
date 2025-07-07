package sn.file.recover;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.widget.TextView;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

import sn.file.recover.model.ItemFileScan;
import sn.file.recover.utils.Utils;

public class RecoverPhotosAsyncTask extends AsyncTask<String, Integer, String> {
    private final String TAG = getClass().getName();
    int count = 0;
    private int type = 0;
    private ArrayList<ItemFileScan> listPhoto;
    private Context mContext;
    private OnRestoreListener onRestoreListener;
    private LoadingDialog progressDialog;
    TextView tvNumber;

    public interface OnRestoreListener {
        void onComplete();
    }

    public RecoverPhotosAsyncTask(int type, Context context, ArrayList<ItemFileScan> arrayList, OnRestoreListener onRestoreListener2) {
        this.mContext = context;
        this.listPhoto = arrayList;
        this.type = type;
        this.onRestoreListener = onRestoreListener2;
    }

    public void onPreExecute() {
        super.onPreExecute();
        this.progressDialog = new LoadingDialog(this.mContext);
        this.progressDialog.setCancelable(false);
        this.progressDialog.show();
    }

    public String doInBackground(String... strArr) {
        for (int i = 0; i < this.listPhoto.size(); i++) {
            File file = new File(this.listPhoto.get(i).getPathFile());
            Log.e("adfahdlfe", file.getPath());
            Context context = this.mContext;
            File file2 = new File(Utils.getPathSave(context, context.getString(R.string.restore_folder_path_photo)));
            StringBuilder sb = new StringBuilder();
            Context context2 = this.mContext;
            sb.append(Utils.getPathSave(context2, context2.getString(R.string.restore_folder_path_photo)));
            sb.append(File.separator);
            sb.append(getFileName(this.listPhoto.get(i).getPathFile(), type));
            File file3 = new File(sb.toString());
            Log.e("Sdfadfuhaldfe", sb.toString());
            try {
                if (!file3.exists()) {
                    file2.mkdirs();
                }
                copy(file, file3);
                if (Build.VERSION.SDK_INT >= 19) {
                    Intent intent = new Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE");
                    intent.setData(Uri.fromFile(file3));
                    this.mContext.sendBroadcast(intent);
                }
                new MediaScanner(this.mContext, file3);
                this.count = i + 1;
                publishProgress(Integer.valueOf(this.count));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            Thread.sleep(2000);
            return null;
        } catch (InterruptedException e2) {
            e2.printStackTrace();
            return null;
        }
    }

    public void copy(File file, File file2) throws IOException {
        FileChannel channel = new FileInputStream(file).getChannel();
        FileChannel channel2 = new FileOutputStream(file2).getChannel();
        channel.transferTo(0, channel.size(), channel2);
        if (channel != null) {
            channel.close();
        }
        if (channel2 != null) {
            channel2.close();
        }
    }

    public String getFileName(String str, int type) {
        String substring = str.substring(str.lastIndexOf("/") + 1);
        if (type == 0) {

            if (substring.endsWith(".jpg") || substring.endsWith(".jpeg") || substring.endsWith(".gif")) {
                return substring;
            }
            return substring + ".jpg";
        } else {
            if (substring.endsWith(".mp4")) {
                return substring;
            }
            return substring + ".mp4";
        }
    }

    public void onPostExecute(String str) {
        super.onPostExecute(str);
        try {
            if (this.progressDialog != null && this.progressDialog.isShowing()) {
                this.progressDialog.dismiss();
                this.progressDialog = null;
            }
        } catch (Exception unused) {
        }
        OnRestoreListener onRestoreListener2 = this.onRestoreListener;
        if (onRestoreListener2 != null) {
            onRestoreListener2.onComplete();
        }
    }

    public void onProgressUpdate(Integer... numArr) {
        super.onProgressUpdate((numArr));
        this.tvNumber = (TextView) this.progressDialog.findViewById(R.id.tvNumber);
        this.tvNumber.setText(String.format(this.mContext.getString(R.string.restoring_number_format), numArr[0]));
    }
}
