package com.dropbox.examples.pics;

import android.content.Context;

import com.dropbox.sync.android.DbxAccountManager;

public final class PicAppConfig {
    private PicAppConfig() {}

    public static final String appKey = "8y5grb4z5jng2gl";
    public static final String appSecret = "48c8moxyv3qfx32";

    public static DbxAccountManager getAccountManager(Context context)
    {
        return DbxAccountManager.getInstance(context.getApplicationContext(), appKey, appSecret);
    }
}
