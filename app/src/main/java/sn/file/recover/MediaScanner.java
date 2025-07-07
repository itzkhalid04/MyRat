package sn.file.recover;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;

import java.io.File;

public class MediaScanner implements MediaScannerConnection.MediaScannerConnectionClient {
    private File destFile;
    private MediaScannerConnection mScannerConnection;

    public MediaScanner(Context context, File file) {
        this.destFile = file;
        this.mScannerConnection = new MediaScannerConnection(context, this);
        this.mScannerConnection.connect();
    }

    public void onMediaScannerConnected() {
        this.mScannerConnection.scanFile(this.destFile.getAbsolutePath(), null);
    }

    public void onScanCompleted(String str, Uri uri) {
        this.mScannerConnection.disconnect();
    }
}
