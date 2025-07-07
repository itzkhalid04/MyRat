package sn.file.recover.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;

import sn.file.recover.R;

public class AdapterImageRecovered extends RecyclerView.Adapter<AdapterImageRecovered.ViewHolder> {
    public ArrayList<File> arrayList;
    private Context context;
    private LayoutInflater inflater;
    private int type = 1;
    private ClickItemFileSavedCallBack callBack;

    public void setCallBack(ClickItemFileSavedCallBack callBack) {
        this.callBack = callBack;
    }

    public AdapterImageRecovered(ArrayList<File> arrayList, Context context, int type) {
        this.arrayList = arrayList;
        this.type = type;
        this.context = context;
        inflater = LayoutInflater.from(context);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = inflater.inflate(R.layout.item_image_saved, parent, false);
        ViewHolder viewHolder = new ViewHolder(view);
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = arrayList.get(position);
        holder.binData(file);
        if (callBack != null) {
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    callBack.clickViewFile(position);
                }
            });
            holder.imgOptionFile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    callBack.clickOptionFile(holder.itemView, position);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return arrayList.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        private ImageView imgAvatarFile;
        private ImageView imgOptionFile;
        private ImageView imgPlayVideo;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgAvatarFile = itemView.findViewById(R.id.img_avatar_file);
            imgOptionFile = itemView.findViewById(R.id.img_option_file);
            imgPlayVideo = itemView.findViewById(R.id.img_play_video);
        }

        public void binData(File file) {
            Glide.with(context).load(file.getPath()).into(imgAvatarFile);
            if (type == 1) {
                imgPlayVideo.setVisibility(View.GONE);
            } else {
                imgPlayVideo.setVisibility(View.VISIBLE);
            }
        }
    }

    public interface ClickItemFileSavedCallBack {
        void clickViewFile(int position);

        void clickOptionFile(View view, int position);
    }
}
