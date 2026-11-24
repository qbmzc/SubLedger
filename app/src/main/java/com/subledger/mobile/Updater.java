package com.subledger.mobile;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App updates from the latest GitHub Release of the public repository. The requests carry no
 * ledger data, only a generic User-Agent. The APK is streamed into a {@link PackageInstaller}
 * session; Android asks the user to confirm and refuses an APK signed with a different key.
 */
final class Updater {
    static final String REPO = "qbmzc/SubLedger";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOADS = "https://github.com/" + REPO + "/releases/download/";
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final int MAX_JSON = 512 * 1024;
    private static final long MAX_APK = 64L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");

    private static final String PREFS = "updates";
    private static final String AUTO = "auto";
    private static final String CHECKED = "checkedAt";
    private static final String SKIP = "skip";

    private static final AtomicBoolean BUSY = new AtomicBoolean();

    interface Progress {
        void update(long done, long total);
    }

    /** The latest release and its APK. */
    static final class Release {
        final String tag;
        /** {@code tag} without the leading "v". */
        final String version;
        final String notes;
        final String apk;
        /** Bytes, or −1 when unknown. */
        final long size;
        /** Lower-case hex SHA-256 published by GitHub, or null. */
        final String sha256;

        Release(String tag, String notes, String apk, long size, String sha256) {
            this.tag = tag;
            this.version = tag.startsWith("v") ? tag.substring(1) : tag;
            this.notes = notes;
            this.apk = apk;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private Updater() {
    }

    // --- Settings -----------------------------------------------------------------------------

    static boolean autoEnabled(Context context) {
        return prefs(context).getBoolean(AUTO, true);
    }

    static void setAuto(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(AUTO, enabled).apply();
    }

    /** True when automatic checks are on and the last successful one is a day old. */
    static boolean due(Context context) {
        long checked = prefs(context).getLong(CHECKED, 0);
        long now = System.currentTimeMillis();
        return autoEnabled(context) && (now - checked >= DAY || checked > now);
    }

    static void markChecked(Context context) {
        prefs(context).edit().putLong(CHECKED, System.currentTimeMillis()).apply();
    }

    /** The tag the user chose to skip; automatic checks stay quiet about it. */
    static String skipped(Context context) {
        return prefs(context).getString(SKIP, "");
    }

    static void skip(Context context, String tag) {
        prefs(context).edit().putString(SKIP, tag).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String installedVersion(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "0" : name;
        } catch (PackageManager.NameNotFoundException ex) {
            return "0";
        }
    }

    /** Only one check or download at a time; release with {@link #done()}. */
    static boolean begin() {
        return BUSY.compareAndSet(false, true);
    }

    static void done() {
        BUSY.set(false);
    }

    // --- Versions -----------------------------------------------------------------------------

    /** Compares the leading dotted numbers ("v0.4.10" &gt; "0.4.9"); anything after them is ignored. */
    static boolean newer(String candidate, String current) {
        int[] a = numbers(candidate);
        int[] b = numbers(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] numbers(String version) {
        Matcher matcher = VERSION.matcher(version == null ? "" : version);
        if (!matcher.find()) {
            return new int[0];
        }
        String[] parts = matcher.group().split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                numbers[i] = Integer.MAX_VALUE;
            }
        }
        return numbers;
    }

    /** Release notes as plain text: one "• " line per change, markdown emphasis removed. */
    static String plainNotes(String markdown) {
        StringBuilder text = new StringBuilder();
        for (String line : markdown.replace("\r", "").split("\n")) {
            String clean = line.trim().replace("**", "");
            if (clean.isEmpty()) {
                continue;
            }
            if (clean.startsWith("- ") || clean.startsWith("* ")) {
                clean = "• " + clean.substring(2);
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(clean);
        }
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text.toString();
    }

    // --- Network (call off the UI thread) -------------------------------------------------------

    /** The latest published release, or null when it has no APK. */
    static Release latest() throws IOException {
        HttpURLConnection connection = open(LATEST);
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub 暂时不可用（HTTP " + code + "）");
            }
            try (InputStream stream = connection.getInputStream()) {
                return parse(new String(read(stream), StandardCharsets.UTF_8));
            }
        } catch (JSONException ex) {
            throw new IOException("无法识别发布信息");
        } finally {
            connection.disconnect();
        }
    }

    private static Release parse(String text) throws JSONException {
        JSONObject release = new JSONObject(text);
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
            return null;
        }
        String tag = release.getString("tag_name");
        String notes = release.isNull("body") ? "" : release.optString("body");
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String url = asset.optString("browser_download_url");
            if (!asset.optString("name").endsWith(".apk") || !url.startsWith(DOWNLOADS)) {
                continue;
            }
            String digest = asset.isNull("digest") ? "" : asset.optString("digest");
            String sha256 = digest.startsWith("sha256:")
                    ? digest.substring("sha256:".length()).toLowerCase(Locale.ROOT) : null;
            return new Release(tag, notes, url, asset.optLong("size", -1), sha256);
        }
        return null;
    }

    /**
     * Downloads the release into an install session and commits it. Android then asks the user
     * to confirm through {@link UpdateStatus}. Throws with a user-facing message on failure.
     */
    static void install(Context context, Release release, Progress progress) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (release.size > 0) {
            params.setSize(release.size);
        }
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        boolean committed = false;
        try {
            MessageDigest sha = sha256();
            HttpURLConnection connection = open(release.apk);
            try {
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("下载失败（HTTP " + code + "）");
                }
                long total = release.size > 0 ? release.size : connection.getContentLengthLong();
                try (InputStream in = connection.getInputStream();
                     OutputStream out = session.openWrite("base.apk", 0, total > 0 ? total : -1)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        done += count;
                        if (done > MAX_APK || (release.size > 0 && done > release.size)) {
                            throw new IOException("安装包大小与发布信息不符");
                        }
                        sha.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                        progress.update(done, total);
                    }
                    if (release.size > 0 && done != release.size) {
                        throw new IOException("安装包下载不完整");
                    }
                    session.fsync(out);
                }
            } finally {
                connection.disconnect();
            }
            if (release.sha256 != null && !release.sha256.equals(hex(sha.digest()))) {
                throw new IOException("安装包校验失败");
            }
            Intent status = new Intent(context, UpdateStatus.class).setAction(UpdateStatus.ACTION);
            // The installer fills in the status extras, so the intent must be mutable on 12+.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(context, id, status, flags).getIntentSender());
            committed = true;
        } finally {
            if (!committed) {
                try {
                    session.abandon();
                } catch (RuntimeException ignored) {
                    // Already gone.
                }
            }
            session.close();
        }
    }

    private static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setUseCaches(false);
        // Generic, so the request does not reveal the device model like Dalvik's default.
        connection.setRequestProperty("User-Agent", "SubLedger-Android");
        return connection;
    }

    private static byte[] read(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_JSON) {
                throw new IOException("发布信息过大");
            }
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        return text.toString();
    }
}
