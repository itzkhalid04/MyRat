package sn.file.recover.adapter;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.theophrast.ui.widget.SquareImageView;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;

import sn.file.recover.R;
import sn.file.recover.model.AlbumPhoto;
import sn.file.recover.model.ItemFileScan;

public class AdapterFileDefault extends RecyclerView.Adapter<AdapterFileDefault.ViewHolder> {
    private Context context;
    private LayoutInflater inflater;
    private ArrayList<ItemFileScan> arrayList;
    private ClickItemFileCallBack callBack;

    public void setCallBack(ClickItemFileCallBack callBack) {
        this.callBack = callBack;
    }

    public AdapterFileDefault(Context context, ArrayList<ItemFileScan> arrayList) {
        this.context = context;
        this.arrayList = arrayList;
        inflater = LayoutInflater.from(context);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = inflater.inflate(R.layout.item_file_grid, parent, false);
        ViewHolder viewHolder = new ViewHolder(view);
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ItemFileScan itemFileScan = arrayList.get(position);
        holder.binData(itemFileScan);
        if (callBack != null) {
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    callBack.checkItemFile(position);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return arrayList.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        private TextView tvFileSize;
        private SquareImageView imgAvatar;
        private ImageView imgCheck;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFileSize = itemView.findViewById(R.id.tv_size_file);
            imgAvatar = itemView.findViewById(R.id.img_avatar);
            imgCheck = itemView.findViewById(R.id.img_check);
        }

        public void binData(ItemFileScan itemFileScan) {
            if (itemFileScan.isCheck() == true) {
                imgCheck.setVisibility(View.VISIBLE);
            } else {
                imgCheck.setVisibility(View.GONE);
            }
            tvFileSize.setText(humanReadableByteCountSI(itemFileScan.getSizeFile()));
            Glide.with(context).load(itemFileScan.getPathFile()).into(imgAvatar);
            Log.e("afasdfef", itemFileScan.getPathFile() + "  sss");
        }
    }

    public interface ClickItemFileCallBack {
        void checkItemFile(int position);
    }

    public static String humanReadableByteCountSI(long bytes) {
        if (-1000 < bytes && bytes < 1000) {
            return bytes + " B";
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000;
            ci.next();
        }
        return String.format("%.1f %cB", bytes / 1000.0, ci.current());
    }
}
