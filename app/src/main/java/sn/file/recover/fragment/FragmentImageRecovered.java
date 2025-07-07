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
import sn.file.recover.adapter.AdapterFileDefault;
import sn.file.recover.adapter.AdapterImageRecovered;

public class FragmentImageRecovered extends Fragment implements AdapterImageRecovered.ClickItemFileSavedCallBack {
    private View view;
    private RecyclerView lvImage;
    private ArrayList<File> arrFileImage = new ArrayList<>();
    private String path = Environment.getExternalStorageDirectory().toString() + "/RestoredPhotos";
    private AdapterImageRecovered adapterImageRecovered;
    private LinearLayout llEmptyImage;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (view == null) {
            view = inflater.inflate(R.layout.fragment_image_recovered, container, false);
        }
        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        initViews();
    }

    private void initViews() {
        llEmptyImage = view.findViewById(R.id.ll_empty_image);
        llEmptyImage.setVisibility(View.GONE);
        arrFileImage.addAll(getAllFileImageSaved());
        lvImage = view.findViewById(R.id.lv_image_recovered);
        GridLayoutManager manager = new GridLayoutManager(getActivity(), 4);
        lvImage.setLayoutManager(manager);
        adapterImageRecovered = new AdapterImageRecovered(arrFileImage, getActivity(), 1);
        adapterImageRecovered.setCallBack(this);
        lvImage.setAdapter(adapterImageRecovered);


    }

    private ArrayList<File> getAllFileImageSaved() {
        ArrayList<File> arrFile = new ArrayList<>();
        File directory = new File(path);
        File[] files = directory.listFiles();

        llEmptyImage.setVisibility(View.GONE);
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().endsWith(".jpg") || files[i].getName().endsWith(".png")) {
                arrFile.add(files[i]);
            }
        }

        if (arrFile.size() == 0) {
            llEmptyImage.setVisibility(View.VISIBLE);
        } else {
            llEmptyImage.setVisibility(View.GONE);
        }
        return arrFile;
    }

    @Override
    public void clickViewFile(int position) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(arrFileImage.get(position).getPath()), "image/*");
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
                        File file = new File(arrFileImage.get(position).getPath());
                        file.delete();
                        arrFileImage.remove(position);
                        adapterImageRecovered.notifyDataSetChanged();
                        if (arrFileImage.size() == 0) {
                            llEmptyImage.setVisibility(View.VISIBLE);
                        } else {
                            llEmptyImage.setVisibility(View.GONE);
                        }
                        break;
                    case R.id.menu_share:
                        shareFileImage(arrFileImage.get(position).getPath());
                        break;
                }
                return false;
            }
        });
        popupMenu.show();
    }

    private void shareFileImage(String filePath) {

        Uri photoURI = FileProvider.getUriForFile(getActivity(), getActivity().getApplicationContext().getPackageName() + ".provider", new File(filePath));
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/*");
        Log.e("asdfkhasldfe", String.valueOf(photoURI));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.putExtra(Intent.EXTRA_STREAM, photoURI);
        startActivity(Intent.createChooser(share, "Share via"));
    }
}
