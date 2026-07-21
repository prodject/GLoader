package com.prodject.gloader.phoneinstaller;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class InstallerAssets {
    final String apkName;
    final long apkBytes;
    final String helperName;
    final long helperBytes;

    private InstallerAssets(String apkName, long apkBytes, String helperName, long helperBytes) {
        this.apkName = apkName;
        this.apkBytes = apkBytes;
        this.helperName = helperName;
        this.helperBytes = helperBytes;
    }

    static InstallerAssets from(Context context) throws Exception {
        AssetManager assets = context.getAssets();
        String apk = find(assets, ".apk");
        String helper = find(assets, ".jar");
        return new InstallerAssets(apk, size(assets, apk), helper, size(assets, helper));
    }

    private static String find(AssetManager assets, String suffix) throws Exception {
        for (String name : assets.list("")) {
            if (name.endsWith(suffix)) {
                return name;
            }
        }
        throw new IllegalStateException("Missing bundled " + suffix + " asset");
    }

    private static long size(AssetManager assets, String name) throws Exception {
        try (InputStream input = assets.open(name)) {
            long total = 0;
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
            }
            return total;
        }
    }

    File copyApkToCache(Context context) throws Exception {
        return copyAssetToCache(context, apkName, "GLoader.apk");
    }

    File copyHelperToCache(Context context) throws Exception {
        return copyAssetToCache(context, helperName, "gloader-install-helper.jar");
    }

    private static File copyAssetToCache(Context context, String assetName, String outputName) throws Exception {
        File output = new File(context.getCacheDir(), outputName);
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream fileOutput = new FileOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                fileOutput.write(buffer, 0, count);
            }
        }
        return output;
    }
}
