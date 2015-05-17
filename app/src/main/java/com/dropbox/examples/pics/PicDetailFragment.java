package com.dropbox.examples.pics;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.Semaphore;

import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.dropbox.sync.android.DbxAccount;
import com.dropbox.sync.android.DbxException;
import com.dropbox.sync.android.DbxFile;
import com.dropbox.sync.android.DbxFileSystem;
import com.dropbox.sync.android.DbxPath;
import 	java.io.FileOutputStream;
import java.io.FileInputStream;

public class PicDetailFragment extends Fragment {
    private static final String TAG = PicDetailFragment.class.getName();

    private static final String ARG_PATH = "path";

    private EditText mText;
    private TextView mErrorMessage;
    private View mOldVersionWarningView;
    private View mLoadingSpinner;
    private ImageView mImage;

    private final DbxLoadHandler mHandler = new DbxLoadHandler(this);

    private DbxFile mFile;
    private final Object mFileLock = new Object();
    private final Semaphore mFileUseSemaphore = new Semaphore(1);
    private boolean mUserHasModifiedText = false;
    private boolean mHasLoadedAnyData = false;
    private Bitmap cameraPic;

    public PicDetailFragment() {}

    public static PicDetailFragment getInstance(DbxPath path) {
        PicDetailFragment fragment = new PicDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PATH, path.toString());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @TargetApi(11)
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_note_detail, container, false);
        mImage = (ImageView)view.findViewById(R.id.image_container);
        mOldVersionWarningView = view.findViewById(R.id.old_version);
        mLoadingSpinner = view.findViewById(R.id.note_loading);
        mErrorMessage = (TextView)view.findViewById(R.id.error_message);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        long f_size =0;
        mImage.setEnabled(false);
        //mUserHasModifiedText = false;
        mHasLoadedAnyData = false;

        DbxPath path = new DbxPath(getArguments().getString(ARG_PATH));

        // Grab the note name from the path:
        String title = Util.stripExtension("jpg", path.getName());

        getActivity().setTitle(title);

        DbxAccount acct = PicAppConfig.getAccountManager(getActivity()).getLinkedAccount();
        if (null == acct) {
            Log.e(TAG, "No linked account.");
            return;
        }

        mErrorMessage.setVisibility(View.GONE);
        mLoadingSpinner.setVisibility(View.VISIBLE);

        /*
         * Since mFile is written asynchronously after onPause, it's possible
         * that the activity is resumed again before a write finishes. This
         * semaphore prevents us from trying to re-open the file while it's
         * still being written in the background - we hold it whenever mFile is
         * in use, and release it when the write is finished and we're done with
         * the file.
         */
        try {
            mFileUseSemaphore.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            DbxFileSystem fs = DbxFileSystem.forAccount(acct);
            try {
                mFile = fs.open(path);
            } catch (DbxException.NotFound e) {
                mFile = fs.create(path);
                mUserHasModifiedText = false;
            }
        } catch (DbxException e) {
            Log.e(TAG, "failed to open or create file.", e);
            return;
        }

        boolean latest;
        try {
            latest = mFile.getSyncStatus().isLatest;
        } catch (DbxException e) {
            Log.w(TAG, "Failed to get sync status", e);
            return;
        }

        try {
            f_size = mFile.getInfo().size;
        } catch (DbxException e) {
            e.printStackTrace();
        }

        if(mImage.getDrawable()==null && f_size<200){
            openCamera();
        }
        mHandler.sendIsShowingLatestMessage(latest);
        mHandler.sendDoUpdateMessage();
    }

    @Override
    public void onPause() {
        super.onPause();
        synchronized(mFileLock) {
            // If the contents have changed, write them back to Dropbox
            if (mUserHasModifiedText && mFile != null) {
                final Bitmap newContents = ((BitmapDrawable)mImage.getDrawable()).getBitmap();
                mUserHasModifiedText = false;

                // Start a thread to do the write.
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "starting write");
                        synchronized (mFileLock) {
                            try {

                                FileOutputStream stream = mFile.getWriteStream();
                                try {
                                    cameraPic.compress(Bitmap.CompressFormat.JPEG,100,stream);
                                }
                                finally {
                                    stream.close();
                                }

                            } catch (IOException e) {
                                Log.e(TAG, "failed to write to file", e);
                            }
                            mFile.close();
                            Log.d(TAG, "write done");
                            mFile = null;
                        }
                        mFileUseSemaphore.release();
                    }
                }).start();
            } else {
                mFile.close();
                mFile = null;
                mFileUseSemaphore.release();
            }
        }
    }

    private void startUpdateOnBackgroundThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (mFileLock) {
                    if (null == mFile) {
                        return;
                    }
                    boolean updated;
                    try {
                        updated = mFile.update();
                    } catch (DbxException e) {
                        Log.e(TAG, "failed to update file", e);
                        mHandler.sendLoadFailedMessage(e.toString());
                        return;
                    }

                    if (!mHasLoadedAnyData || updated) {
                        Log.d(TAG, "starting read");
                          Bitmap contents;
                        try {
                              FileInputStream stream = mFile.getReadStream();
                              try{
                                  contents = BitmapFactory.decodeStream(stream);
                              }
                              finally {
                                  stream.close();
                              }
                        } catch (IOException e) {
                            Log.e(TAG, "failed to read file", e);
                            if (!mHasLoadedAnyData) {
                                mHandler.sendLoadFailedMessage(getString(R.string.error_failed_load));
                            }
                            return;
                        }
                        Log.d(TAG, "read done");

                        if (contents != null) {
                            mHasLoadedAnyData = true;
                        }

                        mHandler.sendUpdateDoneWithChangesMessage(contents);
                    } else {
                        mHandler.sendUpdateDoneWithoutChangesMessage();
                    }
                }
            }
        }).start();
    }

    private void applyNewText(final Bitmap data) {
        if (data == null) {
            return;
        }
        mImage.setImageBitmap(data);
        mImage.setEnabled(true);
    }

    private static class DbxLoadHandler extends Handler {

        private final WeakReference<PicDetailFragment> mFragment;

        public static final int MESSAGE_IS_SHOWING_LATEST = 0;
        public static final int MESSAGE_DO_UPDATE = 1;
        public static final int MESSAGE_UPDATE_DONE = 2;
        public static final int MESSAGE_LOAD_FAILED = 3;

        public DbxLoadHandler(PicDetailFragment containingFragment) {
            mFragment = new WeakReference<PicDetailFragment>(containingFragment);
        }

        @Override
        public void handleMessage(Message msg) {
            PicDetailFragment frag = mFragment.get();
            if (frag == null) {
                return;
            }

            if (msg.what == MESSAGE_IS_SHOWING_LATEST) {
                boolean latest = msg.arg1 != 0;
                frag.mOldVersionWarningView.setVisibility(latest ? View.GONE : View.VISIBLE);
            } else if (msg.what == MESSAGE_DO_UPDATE) {

                frag.mImage.setEnabled(false);

                frag.startUpdateOnBackgroundThread();
            } else if (msg.what == MESSAGE_UPDATE_DONE) {
//                if (frag.mUserHasModifiedText) {
//                    Log.e(TAG, "Somehow user changed text while an update was in progress!");
//                }

                frag.mImage.setVisibility(View.VISIBLE);
                frag.mLoadingSpinner.setVisibility(View.GONE);
                frag.mErrorMessage.setVisibility(View.GONE);

                boolean gotNewData = msg.arg1 != 0;
                if (gotNewData) {
                    Bitmap contents = (Bitmap)msg.obj;
                    frag.applyNewText(contents);
                }

                frag.mImage.requestFocus();
                // reenable the UI
                frag.mImage.setEnabled(true);
            } else if (msg.what == MESSAGE_LOAD_FAILED) {
                String errorText = (String)msg.obj;
                frag.mImage.setVisibility(View.GONE);
                frag.mLoadingSpinner.setVisibility(View.GONE);
                frag.mErrorMessage.setText(errorText);
                frag.mErrorMessage.setVisibility(View.VISIBLE);
            } else {
                throw new RuntimeException("Unknown message");
            }
        }

        public void sendIsShowingLatestMessage(boolean isLatestVersion) {
            sendMessage(Message.obtain(this, MESSAGE_IS_SHOWING_LATEST, isLatestVersion ? 1 : 0, -1));
        }

        public void sendDoUpdateMessage() {
            sendMessage(Message.obtain(this, MESSAGE_DO_UPDATE));
        }

        public void sendUpdateDoneWithChangesMessage(Bitmap newContents) {
            sendMessage(Message.obtain(this, MESSAGE_UPDATE_DONE, 1, -1, newContents));
        }

        public void sendUpdateDoneWithoutChangesMessage() {
            sendMessage(Message.obtain(this, MESSAGE_UPDATE_DONE, 0, -1));
        }

        public void sendLoadFailedMessage(String errorText) {
            sendMessage(Message.obtain(this, MESSAGE_LOAD_FAILED, errorText));
        }
    }

    //OpenCamera
    private void openCamera(){
        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        startActivityForResult(intent, 3);
    }
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "CAMERA RETURN");
        if(requestCode == 3){
            Bundle container = data.getExtras();
           if(container == null){
               return;
           }
           Bitmap imageCamera = (Bitmap)container.get("data");
           cameraPic = (Bitmap)container.get("data");
           mImage.setImageBitmap(imageCamera);
           mUserHasModifiedText = true;
        }
    }

}
