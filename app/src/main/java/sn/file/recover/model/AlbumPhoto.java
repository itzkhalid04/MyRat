package sn.file.recover.model;

import java.util.ArrayList;

public class AlbumPhoto {
    long lastModified;
    ArrayList<ItemFileScan> listPhoto;
    String str_folder;

    public ArrayList<ItemFileScan> getListPhoto() {
        return listPhoto;
    }

    public void setListPhoto(ArrayList<ItemFileScan> listPhoto) {
        this.listPhoto = listPhoto;
    }

    public long getLastModified() {
        return this.lastModified;
    }

    public void setLastModified(long j) {
        this.lastModified = j;
    }

    public String getStr_folder() {
        return this.str_folder;
    }

    public void setStr_folder(String str) {
        this.str_folder = str;
    }


}
