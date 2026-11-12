package net.kdt.pojavlaunch.lilac;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Sets up and updates the "Lilac" profile every time the launcher opens: Fabric for Minecraft 1.21.11 in its own
 * game folder, with Fabric API and the newest Lilac Client from the Lilac update feed. Offline it keeps what it has.
 */
public final class LilacSetup {
    public static final String PROFILE_NAME = "Lilac";
    public static final String MINECRAFT = "1.21.11";
    private static final String GAME_DIR = "./custom_instances/lilac";
    private static final String FEED = "https://github.com/yutyy65h45h65h645/lilac-downloads/releases/latest/download/latest.json";
    private static final String TAG = "LilacSetup";

    private LilacSetup() {}

    public static void start(Activity activity) {
        PojavApplication.sExecutorService.execute(() -> {
            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.lilac_setup_progress);
            String error = null;
            try {
                String key = ensureProfile();
                File mods = new File(Tools.DIR_GAME_NEW, "custom_instances/lilac/mods");
                FileUtils.ensureDirectory(mods);
                ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 30, R.string.lilac_setup_progress);
                ensureFabricApi(mods);
                ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.lilac_setup_progress);
                ensureLilac(mods);
                activity.runOnUiThread(() -> {
                    mcVersionSpinner spinner = activity.findViewById(R.id.mc_version_spinner);
                    if (spinner != null) spinner.reloadProfiles();
                });
                Log.i(TAG, "Lilac profile ready: " + key);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Lilac setup could not finish", e);
                error = e.getMessage();
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            }
            if (error != null) {
                String message = activity.getString(R.string.lilac_setup_failed);
                activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** The Lilac profile on the newest stable Fabric Loader; made (and selected) when missing. Returns its key. */
    private static String ensureProfile() throws IOException, JSONException {
        JSONArray loaders = new JSONArray(DownloadUtils.downloadString(
                "https://meta.fabricmc.net/v2/versions/loader/" + MINECRAFT));
        String loader = null;
        for (int i = 0; i < loaders.length() && loader == null; i++) {
            JSONObject l = loaders.getJSONObject(i).getJSONObject("loader");
            if (l.optBoolean("stable", false)) loader = l.getString("version");
        }
        if (loader == null) throw new IOException("No Fabric Loader for " + MINECRAFT);
        String versionId = "fabric-loader-" + loader + "-" + MINECRAFT;
        File versionJson = new File(Tools.DIR_HOME_VERSION, versionId + "/" + versionId + ".json");
        if (!versionJson.isFile()) {
            String json = DownloadUtils.downloadString(FabriclikeUtils.FABRIC_UTILS.createJsonDownloadUrl(MINECRAFT, loader));
            versionId = new JSONObject(json).getString("id");
            versionJson = new File(Tools.DIR_HOME_VERSION, versionId + "/" + versionId + ".json");
            FileUtils.ensureParentDirectory(versionJson);
            Tools.write(versionJson.getAbsolutePath(), json);
        }

        LauncherProfiles.load();
        String key = null;
        for (Map.Entry<String, MinecraftProfile> e : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            if (PROFILE_NAME.equals(e.getValue().name)) key = e.getKey();
        }
        boolean created = key == null;
        MinecraftProfile profile;
        if (created) {
            profile = new MinecraftProfile();
            profile.name = PROFILE_NAME;
            profile.gameDir = GAME_DIR;
            key = LauncherProfiles.getFreeProfileKey();
            LauncherProfiles.mainProfileJson.profiles.put(key, profile);
        } else {
            profile = LauncherProfiles.mainProfileJson.profiles.get(key);
        }
        profile.lastVersionId = versionId;
        LauncherProfiles.write();
        if (created) {
            LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
        }
        return key;
    }

    /** The newest Fabric API for 1.21.11 from Modrinth; older copies are removed. */
    private static void ensureFabricApi(File mods) throws IOException, JSONException {
        String query = "game_versions=" + URLEncoder.encode("[\"" + MINECRAFT + "\"]", "UTF-8")
                + "&loaders=" + URLEncoder.encode("[\"fabric\"]", "UTF-8");
        JSONArray versions = new JSONArray(DownloadUtils.downloadString(
                "https://api.modrinth.com/v2/project/fabric-api/version?" + query));
        if (versions.length() == 0) throw new IOException("No Fabric API for " + MINECRAFT);
        JSONArray files = versions.getJSONObject(0).getJSONArray("files");
        JSONObject file = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) if (files.getJSONObject(i).optBoolean("primary")) file = files.getJSONObject(i);
        String name = file.getString("filename");
        String sha1 = file.getJSONObject("hashes").getString("sha1");
        replace(mods, "fabric-api-", name, file.getString("url"), "SHA-1", sha1);
    }

    /** The newest Lilac Client from the update feed, checked against the feed's SHA-256. */
    private static void ensureLilac(File mods) throws IOException, JSONException {
        JSONObject feed = new JSONObject(DownloadUtils.downloadString(FEED));
        if (!MINECRAFT.equals(feed.optString("minecraft", MINECRAFT))) throw new IOException("Lilac feed is for another Minecraft");
        String url = feed.getString("url");
        String name = url.substring(url.lastIndexOf('/') + 1);
        replace(mods, "lilac-", name, url, "SHA-256", feed.getString("sha256"));
    }

    /** Download `name` into `mods` unless it is already there and intact, then remove other jars starting with `prefix`. */
    private static void replace(File mods, String prefix, String name, String url, String algorithm, String hash) throws IOException {
        File target = new File(mods, name);
        if (!target.isFile() || !hash.equalsIgnoreCase(digest(target, algorithm))) {
            File part = new File(mods, name + ".part");
            DownloadUtils.downloadFileMonitored(url, part, new byte[65536], (curr, max) ->
                    ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, (int) (100f * curr / Math.max(1, max)),
                            R.string.lilac_setup_downloading, name));
            if (!hash.equalsIgnoreCase(digest(part, algorithm))) {
                part.delete();
                throw new IOException("Download of " + name + " was damaged");
            }
            if (target.exists() && !target.delete()) throw new IOException("Could not replace " + name);
            if (!part.renameTo(target)) throw new IOException("Could not save " + name);
        }
        File[] old = mods.listFiles((dir, n) -> n.startsWith(prefix) && n.endsWith(".jar") && !n.equals(name));
        if (old != null) for (File f : old) f.delete();
    }

    private static String digest(File file, String algorithm) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) md.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
