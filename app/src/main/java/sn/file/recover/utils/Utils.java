package sn.file.recover.utils;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;

public class Utils {
    public static String formatSize(long j) {
        if (j <= 0) {
            return "";
        }
        double d = (double) j;
        int log10 = (int) (Math.log10(d) / Math.log10(1024.0d));
        StringBuilder sb = new StringBuilder();
        DecimalFormat decimalFormat = new DecimalFormat("#,##0.#");
        double pow = Math.pow(1024.0d, (double) log10);
        Double.isNaN(d);
        sb.append(decimalFormat.format(d / pow));
        sb.append(" ");
        sb.append(new String[]{"B", "KB", "MB", "GB", "TB"}[log10]);
        return sb.toString();
    }

    public static String getFileName(String str) {
        return str.substring(str.lastIndexOf("/") + 1);
    }

    public static File[] getFileList(String str) {
        File file = new File(str);
        if (!file.isDirectory()) {
            return new File[0];
        }
        return file.listFiles();
    }

    public static boolean checkSelfPermission(Activity activity, String str) {
        if (!isAndroid23() || ContextCompat.checkSelfPermission(activity, str) == 0) {
            return true;
        }
        return false;
    }

    public static boolean isAndroid23() {
        return Build.VERSION.SDK_INT >= 23;
    }

    public static String getFileTitle(String str) {
        return str.substring(str.lastIndexOf("/") + 1);
    }

    public static String getPathSave(Context context, String str) {
        return Environment.getExternalStorageDirectory() + File.separator + str;
    }

    public static String convertDuration(long j) {
        return String.format("%02d:%02d", Long.valueOf(TimeUnit.MILLISECONDS.toMinutes(j)), Long.valueOf(TimeUnit.MILLISECONDS.toSeconds(j) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(j))));
    }
}
