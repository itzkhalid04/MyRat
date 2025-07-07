package sn.file.recover.model;

public class ItemFileScan {
    private boolean check;
    private String pathFile;
    private long dateCreate;
    private long sizeFile;

    public ItemFileScan(boolean check, String pathFile, long dateCreate, long sizeFile) {
        this.check = check;
        this.pathFile = pathFile;
        this.dateCreate = dateCreate;
        this.sizeFile = sizeFile;
    }

    public boolean isCheck() {
        return check;
    }

    public void setCheck(boolean check) {
        this.check = check;
    }

    public String getPathFile() {
        return pathFile;
    }

    public void setPathFile(String pathFile) {
        this.pathFile = pathFile;
    }

    public long getDateCreate() {
        return dateCreate;
    }

    public void setDateCreate(long dateCreate) {
        this.dateCreate = dateCreate;
    }

    public long getSizeFile() {
        return sizeFile;
    }

    public void setSizeFile(long sizeFile) {
        this.sizeFile = sizeFile;
    }
}
