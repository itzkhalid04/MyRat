package sn.file.recover;

import android.app.Dialog;
import android.content.Context;

public class LoadingDialog extends Dialog {
    private Context mContext;

    public LoadingDialog(Context context) {
        super(context);
        this.mContext = context;

        setContentView(R.layout.layout_loading_dialog);

        setCancelable(false);
        setCanceledOnTouchOutside(false);
    }
}
