package sn.file.recover.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;

import sn.file.recover.R;
import sn.file.recover.adapter.AdapterImageRecovered;

public class FragmentVideoRecovered extends Fragment implements AdapterImageRecovered.ClickItemFileSavedCallBack {
    private View view;
    private RecyclerView lvVideo;
    String path = Environment.getExternalStorageDirectory().toString() + "/RestoredPhotos";
    private AdapterImageRecovered adapterImageRecovered;
    private ArrayList<File> arrVideo = new ArrayList<>();
    private LinearLayout llEmptyVideo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (view == null) {
            view = inflater.inflate(R.layout.fragment_video_recovered, container, false);
        }
        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        initViews();
    }

    private void initViews() {
        llEmptyVideo = view.findViewById(R.id.ll_empty_video);
        llEmptyVideo.setVisibility(View.GONE);
        arrVideo.addAll(getAllFileImageSaved());
        lvVideo = view.findViewById(R.id.lv_video_recovered);
        GridLayoutManager manager = new GridLayoutManager(getActivity(), 4);
        lvVideo.setLayoutManager(manager);
        adapterImageRecovered = new AdapterImageRecovered(arrVideo, getActivity(), 2);
        adapterImageRecovered.setCallBack(this);
        lvVideo.setAdapter(adapterImageRecovered);

    }

    private ArrayList<File> getAllFileImageSaved() {
        ArrayList<File> arrFile = new ArrayList<>();
        File directory = new File(path);
        File[] files = directory.listFiles();

        llEmptyVideo.setVisibility(View.GONE);
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().endsWith(".mp4")) {
                arrFile.add(files[i]);
            }
        }

        if (arrFile.size() == 0) {
            llEmptyVideo.setVisibility(View.VISIBLE);
        } else {
            llEmptyVideo.setVisibility(View.GONE);
        }
        return arrFile;
    }

    @Override
    public void clickViewFile(int position) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(arrVideo.get(position).getPath()));
        intent.setDataAndType(Uri.parse(arrVideo.get(position).getPath()), "video/mp4");
        startActivity(intent);
    }

    @Override
    public void clickOptionFile(View view, int position) {
        PopupMenu popupMenu = new PopupMenu(getActivity(), view);
        popupMenu.getMenuInflater().inflate(R.menu.menu_option_file, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.menu_delete:
                        File file = new File(arrVideo.get(position).getPath());
                        file.delete();
                        arrVideo.remove(position);
                        adapterImageRecovered.notifyDataSetChanged();

                        if (arrVideo.size() == 0) {
                            llEmptyVideo.setVisibility(View.VISIBLE);
                        } else {
                            llEmptyVideo.setVisibility(View.GONE);
                        }
                        break;
                    case R.id.menu_share:
                        shareFileVideo(arrVideo.get(position).getPath());
                        break;
                }
                return false;
            }
        });
        popupMenu.show();
    }

    private void shareFileVideo(String path) {

        Uri photoURI = FileProvider.getUriForFile(getActivity(), getActivity().getApplicationContext().getPackageName() + ".provider", new File(path));
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_STREAM, photoURI);
        startActivity(Intent.createChooser(intent, "share"));
    }
}
