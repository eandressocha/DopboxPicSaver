package com.dropbox.examples.pics;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;

import com.dropbox.sync.android.DbxPath;

public class PicListActivity extends FragmentActivity
        implements PicListFragment.Callbacks {

    private boolean mTwoPane;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note_list);

        if (findViewById(R.id.note_detail_container) != null) {
            mTwoPane = true;
            ((PicListFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.note_list))
                    .setActivateOnItemClick(true);
        }
    }

    @Override
    public void onItemSelected(DbxPath path) {
        if (mTwoPane) {
            PicDetailFragment fragment = PicDetailFragment.getInstance(path);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.note_detail_container, fragment)
                    .commit();
        } else {
            Intent detailIntent = new Intent(this, PicDetailActivity.class);
            detailIntent.putExtra(PicDetailActivity.EXTRA_PATH, path.toString());
            startActivity(detailIntent);
        }
    }
}
