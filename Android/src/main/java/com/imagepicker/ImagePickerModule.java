package com.imagepicker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.database.Cursor;
import android.util.Base64;
import android.app.AlertDialog;
import android.widget.ArrayAdapter;
import android.content.DialogInterface;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.media.ExifInterface;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.ArrayList;
import java.io.FileOutputStream;
import java.util.UUID;

public class ImagePickerModule extends ReactContextBaseJavaModule {
  static final int REQUEST_LAUNCH_CAMERA = 1;
  static final int REQUEST_LAUNCH_IMAGE_LIBRARY = 2;

  private final ReactApplicationContext mReactContext;
  private final Activity mMainActivity;

  private Uri mCameraCaptureURI;
  private Callback mCallback;
  private Boolean noData = false;
  private int maxWidth = 0;
  private int maxHeight = 0;
  private int quality = 100;

  public ImagePickerModule(ReactApplicationContext reactContext, Activity mainActivity) {
    super(reactContext);

    mReactContext = reactContext;
    mMainActivity = mainActivity;
  }

  @Override
  public String getName() {
    return "UIImagePickerManager"; // To coincide with the iOS native module name
  }

  @ReactMethod
  public void showImagePicker(final ReadableMap options, final Callback callback) {
      List<String> mTitles = new ArrayList<String>();
      List<String> mActions = new ArrayList<String>();

      String cancelButtonTitle = "Cancel";

      if (options.hasKey("takePhotoButtonTitle")
              && !options.getString("takePhotoButtonTitle").isEmpty()) {
          mTitles.add(options.getString("takePhotoButtonTitle"));
          mActions.add("photo");
      }
      if (options.hasKey("chooseFromLibraryButtonTitle")
              && !options.getString("chooseFromLibraryButtonTitle").isEmpty()) {
          mTitles.add(options.getString("chooseFromLibraryButtonTitle"));
          mActions.add("library");
      }
      if (options.hasKey("cancelButtonTitle")
              && !options.getString("cancelButtonTitle").isEmpty()) {
          cancelButtonTitle = options.getString("cancelButtonTitle");
      }
      mTitles.add(cancelButtonTitle);
      mActions.add("cancel");

      String[] option = new String[mTitles.size()];
      option = mTitles.toArray(option);

      String[] action = new String[mActions.size()];
      action = mActions.toArray(action);
      final String[] act = action;

      ArrayAdapter<String> adapter = new ArrayAdapter<String>(mMainActivity,
                           android.R.layout.select_dialog_item, option);
       AlertDialog.Builder builder = new AlertDialog.Builder(mMainActivity);
       if (options.hasKey("title") && options.getString("title") != null && !options.getString("title").isEmpty()) {
          builder.setTitle(options.getString("title"));
       }

       builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
           public void onClick(DialogInterface dialog, int index) {
               if (act[index].equals("photo")) {
                   launchCamera(options, callback);
               } else if (act[index].equals("library")) {
                   launchImageLibrary(options, callback);
               } else {
                   callback.invoke(true, Arguments.createMap());
               }
           }
       });

       final AlertDialog dialog = builder.create();
       /**
        * override onCancel method to callback cancel in case of a touch
        * outside of the dialog or the BACK key pressed
        */
       dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                dialog.dismiss();
                callback.invoke(true, Arguments.createMap());
            }
        });
       dialog.show();
   }

  // NOTE: Currently not reentrant / doesn't support concurrent requests
  @ReactMethod
  public void launchCamera(final ReadableMap options, final Callback callback) {
    if (options.hasKey("noData")) {
        noData = options.getBoolean("noData");
    }
    if (options.hasKey("maxWidth")) {
        maxWidth = options.getInt("maxWidth");
    }
    if (options.hasKey("maxHeight")) {
        maxHeight = options.getInt("maxHeight");
    }
    if (options.hasKey("quality")) {
        quality = (int)(options.getDouble("quality") * 100);
    }

    Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
    if (cameraIntent.resolveActivity(mMainActivity.getPackageManager()) == null) {
        callback.invoke(true, "error resolving activity");
        return;
    }

    // we create a tmp file to save the result
    File imageFile;
    try {
    imageFile = File.createTempFile("exponent_capture_", ".jpg",
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
    } catch (IOException e) {
        e.printStackTrace();
        return;
    }
    if (imageFile == null) {
        callback.invoke(true, "error file not created");
        return;
    }
    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(imageFile));
    mCameraCaptureURI = Uri.fromFile(imageFile);
    mCallback = callback;
    mMainActivity.startActivityForResult(cameraIntent, REQUEST_LAUNCH_CAMERA);
  }

  // NOTE: Currently not reentrant / doesn't support concurrent requests
  @ReactMethod
  public void launchImageLibrary(final ReadableMap options, final Callback callback) {
    if (options.hasKey("noData")) {
        noData = options.getBoolean("noData");
    }
    if (options.hasKey("maxWidth")) {
        maxWidth = options.getInt("maxWidth");
    }
    if (options.hasKey("maxHeight")) {
        maxHeight = options.getInt("maxHeight");
    }
    if (options.hasKey("quality")) {
        quality = (int)(options.getDouble("quality") * 100);
    }

    Intent libraryIntent = new Intent();
    libraryIntent.setType("image/");
    libraryIntent.setAction(Intent.ACTION_GET_CONTENT);
    mCallback = callback;
    mMainActivity.startActivityForResult(libraryIntent, REQUEST_LAUNCH_IMAGE_LIBRARY);
  }

  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    //robustness code
    if (mCallback == null || (mCameraCaptureURI == null && requestCode == REQUEST_LAUNCH_CAMERA)
            || (requestCode != REQUEST_LAUNCH_CAMERA && requestCode != REQUEST_LAUNCH_IMAGE_LIBRARY)) {
      return;
    }

    // user cancel
    if (resultCode != Activity.RESULT_OK) {
      mCallback.invoke(true, Arguments.createMap());
      return;
    }

    WritableMap response = Arguments.createMap();
    Uri uri = (requestCode == REQUEST_LAUNCH_CAMERA)
    ? mCameraCaptureURI
    : data.getData();

    // let's set data
    String realPath = getRealPathFromURI(uri);

    try {
        ExifInterface exif = new ExifInterface(realPath);
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        boolean isVertical = true ;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_270:
                isVertical = false ;
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                isVertical = false ;
                break;
        }
        response.putBoolean("isVertical", isVertical);
    } catch (IOException e) {
        e.printStackTrace();
    }

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    Bitmap photo = BitmapFactory.decodeFile(realPath, options);
    int initialWidth = options.outWidth;
    int initialHeight = options.outHeight;

    // don't create a new file if contraint are respected
    if (((initialWidth < maxWidth && maxWidth > 0) || maxWidth == 0)
            && ((initialHeight < maxHeight && maxHeight > 0) || maxHeight == 0)
            && quality == 100) {
        response.putInt("width", initialWidth);
        response.putInt("height", initialHeight);
    } else {
        realPath = getResizedImage(realPath, initialWidth, initialHeight);
        photo = BitmapFactory.decodeFile(realPath, options);
        response.putInt("width", options.outWidth);
        response.putInt("height", options.outHeight);
    }

    response.putString("uri", realPath);

    if (!noData) {
        response.putString("data", getBase64StringFromFile(realPath));
    }
    mCallback.invoke(false, response);
  }

  private String getRealPathFromURI(Uri uri) {
    String result;
    Cursor cursor = mMainActivity.getContentResolver().query(uri, null, null, null, null);
    if (cursor == null) { // Source is Dropbox or other similar local file path
        result = uri.getPath();
    } else {
        cursor.moveToFirst();
        int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
        result = cursor.getString(idx);
        cursor.close();
    }
    return result;
  }

  private String getBase64StringFromFile (String absoluteFilePath) {
    InputStream inputStream = null;
    try {
      inputStream = new FileInputStream(absoluteFilePath);
    } catch (FileNotFoundException e) {
        e.printStackTrace();
    }

    byte[] bytes;
    byte[] buffer = new byte[8192];
    int bytesRead;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    } catch (IOException e) {
        e.printStackTrace();
    }
    bytes = output.toByteArray();
    return Base64.encodeToString(bytes, Base64.NO_WRAP);
  }

  /**
   * Create a resized image to fill the maxWidth and maxHeight values and the
   * quality value
   *
   * @param realpath
   * @param initialWidth
   * @param initialHeight
   * @return absolute path of resized file
   */
  private String getResizedImage (final String realPath, final int initialWidth, final int initialHeight) {
        Bitmap photo = BitmapFactory.decodeFile(realPath);

        Bitmap scaledphoto = null;
        if (maxWidth == 0) {
            maxWidth  = initialWidth;
        }
        if (maxHeight == 0) {
            maxHeight = initialHeight;
        }
        double widthRatio = (double)maxWidth / initialWidth;
        double heightRatio = (double)maxHeight / initialHeight;

        double ratio = (widthRatio < heightRatio)
                ? widthRatio
                : heightRatio;

        int newWidth = (int)(initialWidth * ratio);
        int newHeight = (int)(initialHeight * ratio);

        scaledphoto = Bitmap.createScaledBitmap(photo, newWidth, newHeight, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        scaledphoto.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
        String filname = UUID.randomUUID().toString();
        File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + File.separator + filname +".jpg");
        try {
            f.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        FileOutputStream fo;
        try {
            fo = new FileOutputStream(f);
            try {
                fo.write(bytes.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return f.getAbsolutePath();
  }
}
