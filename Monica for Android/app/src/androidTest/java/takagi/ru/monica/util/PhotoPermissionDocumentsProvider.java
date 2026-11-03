package takagi.ru.monica.util;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/** Two synthetic images; access is guarded by MANAGE_DOCUMENTS until the chooser grants a URI. */
public final class PhotoPermissionDocumentsProvider extends DocumentsProvider {
    private static final String ROOT="permission-photos";
    private static final String[] ROOT_COLUMNS={"root_id","document_id","title","flags","icon","mime_types"};
    private static final String[] DOC_COLUMNS={"document_id","_display_name","mime_type","flags","_size","last_modified"};
    private File file(String id)throws FileNotFoundException {
        if(!id.equals("selected.png")&&!id.equals("unselected.png"))throw new FileNotFoundException("Fixture only");
        return new File(getContext().getFilesDir(),"permission-photo-"+id);
    }
    @Override public boolean onCreate() {
        for(String id:new String[]{"selected.png","unselected.png"})try {
            Bitmap b=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);b.eraseColor(id.equals("selected.png")?Color.RED:Color.BLUE);
            try(FileOutputStream out=new FileOutputStream(file(id))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        }catch(Exception e){return false;}
        return true;
    }
    @Override public Cursor queryRoots(String[] projection){
        MatrixCursor c=new MatrixCursor(projection==null?ROOT_COLUMNS:projection);
        c.newRow().add("root_id",ROOT).add("document_id",ROOT).add("title","Monica Photo Permission Tests")
            .add("flags",DocumentsContract.Root.FLAG_LOCAL_ONLY).add("icon",android.R.drawable.ic_menu_gallery).add("mime_types","image/png");return c;
    }
    private void add(MatrixCursor c,String id)throws FileNotFoundException{
        boolean root=ROOT.equals(id);File f=root?getContext().getFilesDir():file(id);
        c.newRow().add("document_id",id).add("_display_name",root?"Monica Photo Permission Tests":id)
            .add("mime_type",root?DocumentsContract.Document.MIME_TYPE_DIR:"image/png").add("flags",0)
            .add("_size",root?0:f.length()).add("last_modified",f.lastModified());
    }
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{
        MatrixCursor c=new MatrixCursor(projection==null?DOC_COLUMNS:projection);add(c,id);return c;
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sortOrder)throws FileNotFoundException{
        if(!ROOT.equals(parent))throw new FileNotFoundException("Fixture root only");
        MatrixCursor c=new MatrixCursor(projection==null?DOC_COLUMNS:projection);add(c,"selected.png");add(c,"unselected.png");return c;
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{
        if(!mode.equals("r"))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public boolean isChildDocument(String parent,String child){return ROOT.equals(parent)&&(child.equals("selected.png")||child.equals("unselected.png"));}
}
