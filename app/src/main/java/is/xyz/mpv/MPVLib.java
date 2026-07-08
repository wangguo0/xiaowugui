package is.xyz.mpv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class MPVLib {

    private static final String TAG = "mpv";
    private static final String ASSET_ROOT = "mpv-libs";
    private static final String[] LOAD_ORDER = {
            "c++_shared",
            "mvutil",
            "mwresample",
            "mwscale",
            "mvcodec",
            "mvformat",
            "mvfilter",
            "mvdevice",
            "mpv",
            "player"
    };

    private static final List<EventObserver> OBSERVERS = new ArrayList<>();
    private static final List<LogObserver> LOG_OBSERVERS = new ArrayList<>();
    private static boolean loaded;
    private static Throwable loadError;
    private static String loadedAbi;

    private MPVLib() {
    }

    public static synchronized boolean ensureLoaded(Context context) {
        if (loaded) return true;
        if (loadError != null) return false;
        try {
            Context app = context.getApplicationContext();
            String abi = chooseAbi(app.getAssets());
            if (abi == null) throw new UnsatisfiedLinkError("No bundled MPV native libraries for " + String.join(",", Build.SUPPORTED_ABIS));
            File dir = new File(app.getDir("mpv-libs", Context.MODE_PRIVATE), abi);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Unable to create " + dir);
            for (String lib : LOAD_ORDER) copyLibrary(app.getAssets(), abi, lib, dir);
            for (String lib : LOAD_ORDER) System.load(new File(dir, System.mapLibraryName(lib)).getAbsolutePath());
            loadedAbi = abi;
            loaded = true;
            return true;
        } catch (Throwable e) {
            loadError = e;
            Log.e(TAG, "Unable to load bundled MPV native libraries", e);
            return false;
        }
    }

    public static synchronized Throwable getLoadError() {
        return loadError;
    }

    public static synchronized String getLoadedAbi() {
        return loadedAbi;
    }

    private static String chooseAbi(AssetManager assets) {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (assetExists(assets, abi, "mpv")) return abi;
        }
        return assetExists(assets, "armeabi-v7a", "mpv") ? "armeabi-v7a" : null;
    }

    private static boolean assetExists(AssetManager assets, String abi, String lib) {
        try (InputStream ignored = assets.open(assetPath(abi, lib), AssetManager.ACCESS_STREAMING)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void copyLibrary(AssetManager assets, String abi, String lib, File dir) throws IOException {
        File outFile = new File(dir, System.mapLibraryName(lib));
        try (InputStream in = assets.open(assetPath(abi, lib), AssetManager.ACCESS_STREAMING)) {
            long size = in.available();
            if (outFile.length() == size && size > 0) return;
            try (OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
        }
    }

    private static String assetPath(String abi, String lib) {
        return ASSET_ROOT + "/" + abi + "/" + System.mapLibraryName(lib);
    }

    public static native void create(Context appctx);

    public static native void init();

    public static native void destroy();

    public static native void attachSurface(Surface surface);

    public static native void detachSurface();

    public static native void command(String[] cmd);

    public static native int setOptionString(String name, String value);

    public static native Bitmap grabThumbnail(int dimension);

    public static native Integer getPropertyInt(String property);

    public static native void setPropertyInt(String property, int value);

    public static native Double getPropertyDouble(String property);

    public static native void setPropertyDouble(String property, double value);

    public static native Boolean getPropertyBoolean(String property);

    public static native void setPropertyBoolean(String property, boolean value);

    public static native String getPropertyString(String property);

    public static native void setPropertyString(String property, String value);

    public static native void observeProperty(String property, int format);

    public static void addObserver(EventObserver observer) {
        synchronized (OBSERVERS) {
            OBSERVERS.add(observer);
        }
    }

    public static void removeObserver(EventObserver observer) {
        synchronized (OBSERVERS) {
            OBSERVERS.remove(observer);
        }
    }

    public static void eventProperty(String property, long value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, boolean value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, double value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, String value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property);
        }
    }

    public static void event(int eventId) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.event(eventId);
        }
    }

    public static void endFile(int reason, int error, String errorText) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.endFile(reason, error, errorText);
        }
    }

    public static void addLogObserver(LogObserver observer) {
        synchronized (LOG_OBSERVERS) {
            LOG_OBSERVERS.add(observer);
        }
    }

    public static void removeLogObserver(LogObserver observer) {
        synchronized (LOG_OBSERVERS) {
            LOG_OBSERVERS.remove(observer);
        }
    }

    public static void logMessage(String prefix, int level, String text) {
        synchronized (LOG_OBSERVERS) {
            for (LogObserver observer : LOG_OBSERVERS) observer.logMessage(prefix, level, text);
        }
    }

    public interface EventObserver {
        void eventProperty(String property);

        void eventProperty(String property, long value);

        void eventProperty(String property, boolean value);

        void eventProperty(String property, String value);

        void eventProperty(String property, double value);

        void event(int eventId);

        default void endFile(int reason, int error, String errorText) {
            event(MpvEvent.MPV_EVENT_END_FILE);
        }
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    public static final class MpvFormat {
        public static final int MPV_FORMAT_NONE = 0;
        public static final int MPV_FORMAT_STRING = 1;
        public static final int MPV_FORMAT_OSD_STRING = 2;
        public static final int MPV_FORMAT_FLAG = 3;
        public static final int MPV_FORMAT_INT64 = 4;
        public static final int MPV_FORMAT_DOUBLE = 5;
        public static final int MPV_FORMAT_NODE = 6;
        public static final int MPV_FORMAT_NODE_ARRAY = 7;
        public static final int MPV_FORMAT_NODE_MAP = 8;
        public static final int MPV_FORMAT_BYTE_ARRAY = 9;

        private MpvFormat() {
        }
    }

    public static final class MpvEvent {
        public static final int MPV_EVENT_NONE = 0;
        public static final int MPV_EVENT_SHUTDOWN = 1;
        public static final int MPV_EVENT_LOG_MESSAGE = 2;
        public static final int MPV_EVENT_GET_PROPERTY_REPLY = 3;
        public static final int MPV_EVENT_SET_PROPERTY_REPLY = 4;
        public static final int MPV_EVENT_COMMAND_REPLY = 5;
        public static final int MPV_EVENT_START_FILE = 6;
        public static final int MPV_EVENT_END_FILE = 7;
        public static final int MPV_EVENT_FILE_LOADED = 8;
        public static final int MPV_EVENT_IDLE = 11;
        public static final int MPV_EVENT_TICK = 14;
        public static final int MPV_EVENT_CLIENT_MESSAGE = 16;
        public static final int MPV_EVENT_VIDEO_RECONFIG = 17;
        public static final int MPV_EVENT_AUDIO_RECONFIG = 18;
        public static final int MPV_EVENT_SEEK = 20;
        public static final int MPV_EVENT_PLAYBACK_RESTART = 21;
        public static final int MPV_EVENT_PROPERTY_CHANGE = 22;
        public static final int MPV_EVENT_QUEUE_OVERFLOW = 24;
        public static final int MPV_EVENT_HOOK = 25;

        private MpvEvent() {
        }
    }

    public static final class MpvEndFileReason {
        public static final int MPV_END_FILE_REASON_UNKNOWN = -1;
        public static final int MPV_END_FILE_REASON_EOF = 0;
        public static final int MPV_END_FILE_REASON_STOP = 2;
        public static final int MPV_END_FILE_REASON_QUIT = 3;
        public static final int MPV_END_FILE_REASON_ERROR = 4;
        public static final int MPV_END_FILE_REASON_REDIRECT = 5;

        private MpvEndFileReason() {
        }
    }

    public static final class MpvError {
        public static final int MPV_ERROR_SUCCESS = 0;
        public static final int MPV_ERROR_LOADING_FAILED = -13;
        public static final int MPV_ERROR_AO_INIT_FAILED = -14;
        public static final int MPV_ERROR_VO_INIT_FAILED = -15;
        public static final int MPV_ERROR_NOTHING_TO_PLAY = -16;
        public static final int MPV_ERROR_UNKNOWN_FORMAT = -17;
        public static final int MPV_ERROR_UNSUPPORTED = -18;
        public static final int MPV_ERROR_GENERIC = -20;

        private MpvError() {
        }
    }
}
