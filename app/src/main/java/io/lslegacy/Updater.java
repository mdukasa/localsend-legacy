package io.lslegacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;

import io.lslegacy.net.HttpClient;
import io.lslegacy.net.WebClient;

/**
 * "Check for updates": asks GitHub for the latest release, and if it's newer, downloads its APK, checks it
 * against the SHA-256 GitHub lists for it, and opens Android's installer. Android itself only accepts the
 * update if it is signed with the same key as the installed app.
 */
final class Updater {
    private static final String TAG = "LsUpdater";
    private static final String LATEST = "https://api.github.com/repos/mdukasa/localsend-legacy/releases/latest";
    private static final String APK_TYPE = "application/vnd.android.package-archive";

    static final class Release {
        String version;
        String title;
        String notes;
        String apkName;
        String apkUrl;
        long apkSize;
        /** Lower-case hex, or null if GitHub didn't list one. */
        String sha256;
    }

    private static final Handler main = new Handler(Looper.getMainLooper());

    private Updater() {}

    /** Runs the whole flow from Settings: check, offer, download, install. */
    static void checkInteractive(final Activity a) {
        final ProgressDialog wait = ProgressDialog.show(a, null, "Checking GitHub for updates…", true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                Release r = null;
                String error = null;
                try {
                    r = latest(a);
                } catch (Exception e) {
                    Log.w(TAG, "update check failed", e);
                    error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final Release release = r;
                final String why = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        dismiss(wait);
                        if (a.isFinishing()) return;
                        if (release == null) {
                            message(a, "Couldn't check for updates", "Couldn't reach GitHub: " + why);
                        } else if (!isNewer(release.version, current(a))) {
                            message(a, "No update", "You have the latest version (" + current(a) + ").");
                        } else {
                            offer(a, release);
                        }
                    }
                });
            }
        }, "ls-update").start();
    }

    static String current(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private static String userAgent(Activity a) {
        return "LocalSendLegacy/" + current(a);
    }

    static Release latest(Activity a) throws IOException, JSONException {
        byte[] body = WebClient.get(LATEST, "application/vnd.github+json", userAgent(a), 1024 * 1024, BuildConfig.BUILD_TIME);
        JSONObject o = new JSONObject(new String(body, "UTF-8"));
        Release r = new Release();
        String tag = o.optString("tag_name", "");
        r.version = tag.startsWith("v") ? tag.substring(1) : tag;
        r.title = o.optString("name", "LocalSend Legacy " + r.version);
        r.notes = o.optString("body", "");
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            if (!name.endsWith(".apk")) continue;
            r.apkName = name;
            r.apkUrl = asset.optString("browser_download_url", null);
            r.apkSize = asset.optLong("size", -1);
            String digest = asset.optString("digest", "");
            if (digest.startsWith("sha256:")) r.sha256 = digest.substring(7).toLowerCase();
            break;
        }
        if (r.version.length() == 0) throw new IOException("GitHub's answer had no version");
        return r;
    }

    /** Compares dotted version numbers: 0.10.0 is newer than 0.9.1. */
    static boolean isNewer(String candidate, String installed) {
        String[] c = candidate.split("[.-]"), i = installed.split("[.-]");
        for (int k = 0; k < Math.max(c.length, i.length); k++) {
            int x = k < c.length ? number(c[k]) : 0, y = k < i.length ? number(i[k]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void offer(final Activity a, final Release r) {
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(r.title + " is available")
                .setMessage("You have " + current(a) + ".\n\n" + plain(r.notes))
                .setNegativeButton("Later", null);
        if (r.apkUrl != null) {
            b.setPositiveButton("Download" + (r.apkSize > 0 ? " (" + Util.formatSize(r.apkSize) + ")" : ""),
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            download(a, r);
                        }
                    });
        }
        b.show();
    }

    /**
     * GitHub release notes are Markdown, possibly with HTML (an image, say); show them as plain text.
     * The "Install" section is dropped: the app does that part itself.
     */
    static String plain(String md) {
        String s = md.replace("\r", "");
        int install = s.indexOf("\n## Install");
        if (install >= 0) s = s.substring(0, install);
        return s.replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?s)<[^>]*>", "")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&")
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("(?m)^[ \\t]*[-*] ", "• ")
                .replaceAll("(?m)^[ \\t]+", "")
                .replace("**", "")
                .replace("`", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static void download(final Activity a, final Release r) {
        final File dest = destination(a, r.apkName);
        if (dest == null) {
            message(a, "Can't download", "There's no SD card to save the update to.");
            return;
        }
        final HttpClient.Abort abort = new HttpClient.Abort();
        final boolean[] cancelled = {false};
        final ProgressDialog progress = new ProgressDialog(a);
        progress.setTitle("Downloading " + r.version);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setCancelable(false);
        progress.setMax(r.apkSize > 0 ? (int) (r.apkSize / 1024) : 100);
        if (Build.VERSION.SDK_INT >= 11) kbFormat(progress);
        progress.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                cancelled[0] = true;
                abort.abort();
            }
        });
        progress.show();
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                try {
                    fetch(a, r, dest, abort, progress);
                } catch (Exception e) {
                    Log.w(TAG, "update download failed", e);
                    dest.delete();
                    error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final String why = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        dismiss(progress);
                        if (a.isFinishing()) return;
                        if (cancelled[0]) dest.delete();
                        else if (why == null) install(a, dest);
                        else message(a, "Download failed", why);
                    }
                });
            }
        }, "ls-update-dl").start();
    }

    private static void fetch(Activity a, final Release r, File dest, HttpClient.Abort abort,
                              final ProgressDialog progress) throws IOException {
        final MessageDigest sha = Util.sha256();
        OutputStream out = new FileOutputStream(dest);
        long n;
        try {
            final OutputStream file = out;
            OutputStream tee = new OutputStream() {
                @Override public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override public void write(byte[] b, int off, int len) throws IOException {
                    file.write(b, off, len);
                    sha.update(b, off, len);
                }
            };
            n = WebClient.download(r.apkUrl, userAgent(a), tee, new WebClient.Progress() {
                private long last;

                @Override public void onBytes(final long done, final long total) {
                    long now = System.currentTimeMillis();
                    if (now - last < 250) return; // each redraw is slow on an ARMv6 phone
                    last = now;
                    main.post(new Runnable() {
                        @Override public void run() {
                            long size = total > 0 ? total : r.apkSize;
                            if (size > 0) progress.setMax((int) (size / 1024));
                            progress.setProgress((int) (done / 1024));
                        }
                    });
                }
            }, BuildConfig.BUILD_TIME, abort);
            out.close();
            out = null;
        } finally {
            Util.closeQuietly(out);
        }
        if (r.apkSize > 0 && n != r.apkSize) throw new IOException("Download was incomplete");
        if (r.sha256 != null && !Util.hex(sha.digest()).equalsIgnoreCase(r.sha256)) {
            throw new IOException("Download doesn't match GitHub's checksum");
        }
        // The installer runs as another app; on old Android it reads the file directly.
        dest.setReadable(true, false);
    }

    /** Download/ on shared storage; without an SD card on old Android, the app's own storage. */
    private static File destination(Activity a, String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) && Util.hasStoragePermission(a)) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dir.mkdirs();
            return new File(dir, safe);
        }
        // Android 7+ always has shared storage; FilesProvider only serves files from it.
        return Build.VERSION.SDK_INT < 24 ? new File(a.getFilesDir(), safe) : null;
    }

    private static void install(Activity a, File apk) {
        Uri uri = Build.VERSION.SDK_INT >= 24 ? FilesProvider.uriFor(apk) : Uri.fromFile(apk);
        // From 4.0 there's an install action only package installers answer, so file managers that also
        // open APKs (as archives) don't turn this into a chooser.
        String action = Build.VERSION.SDK_INT >= 14 ? "android.intent.action.INSTALL_PACKAGE" : Intent.ACTION_VIEW;
        Intent i = new Intent(action).setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            message(a, "Can't install", "Downloaded to " + apk.getAbsolutePath() + ", but nothing on this phone can install it.");
        }
    }

    @android.annotation.TargetApi(11)
    private static void kbFormat(ProgressDialog d) {
        d.setProgressNumberFormat("%1d / %2d KB");
    }

    private static void message(Activity a, String title, String text) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    private static void dismiss(ProgressDialog d) {
        try {
            d.dismiss();
        } catch (RuntimeException ignored) {
            // The activity went away while it was showing.
        }
    }
}
