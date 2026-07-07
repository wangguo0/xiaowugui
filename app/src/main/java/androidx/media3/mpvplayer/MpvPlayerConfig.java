package androidx.media3.mpvplayer;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MpvPlayerConfig {

    private static final long DEFAULT_DEMUXER_BYTES = 64L * 1024L * 1024L;

    private final File configDir;
    private final File cacheDir;
    private final File caFile;
    private final String userAgent;
    private final String referer;
    private final String hwdec;
    private final String vo;
    private final String ao;
    private final String logLevel;
    private final boolean tlsVerify;
    private final long demuxerMaxBytes;
    private final long demuxerMaxBackBytes;
    private final Map<String, String> extraOptions;

    private MpvPlayerConfig(Builder builder) {
        configDir = builder.configDir;
        cacheDir = builder.cacheDir;
        caFile = builder.caFile;
        userAgent = builder.userAgent;
        referer = builder.referer;
        hwdec = builder.hwdec;
        vo = builder.vo;
        ao = builder.ao;
        logLevel = builder.logLevel;
        tlsVerify = builder.tlsVerify;
        demuxerMaxBytes = builder.demuxerMaxBytes;
        demuxerMaxBackBytes = builder.demuxerMaxBackBytes;
        extraOptions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extraOptions));
    }

    public static Builder builder(Context context) {
        return new Builder(context);
    }

    public File configDir() {
        return configDir;
    }

    public File cacheDir() {
        return cacheDir;
    }

    public File caFile() {
        return caFile;
    }

    @Nullable
    public String userAgent() {
        return userAgent;
    }

    @Nullable
    public String referer() {
        return referer;
    }

    public String hwdec() {
        return hwdec;
    }

    public String vo() {
        return vo;
    }

    public String ao() {
        return ao;
    }

    public String logLevel() {
        return logLevel;
    }

    public boolean tlsVerify() {
        return tlsVerify;
    }

    public long demuxerMaxBytes() {
        return demuxerMaxBytes;
    }

    public long demuxerMaxBackBytes() {
        return demuxerMaxBackBytes;
    }

    public Map<String, String> extraOptions() {
        return extraOptions;
    }

    public static final class Builder {

        private final Map<String, String> extraOptions = new LinkedHashMap<>();
        private File configDir;
        private File cacheDir;
        private File caFile;
        private String userAgent;
        private String referer;
        private String hwdec = "mediacodec,mediacodec-copy";
        private String vo = "gpu";
        private String ao = "audiotrack,opensles";
        private String logLevel = "all=v";
        private boolean tlsVerify = true;
        private long demuxerMaxBytes = DEFAULT_DEMUXER_BYTES;
        private long demuxerMaxBackBytes = DEFAULT_DEMUXER_BYTES;

        private Builder(Context context) {
            Context app = context.getApplicationContext();
            configDir = app.getFilesDir();
            cacheDir = app.getCacheDir();
            caFile = new File(app.getFilesDir(), "cacert.pem");
        }

        public Builder configDir(File configDir) {
            this.configDir = configDir;
            return this;
        }

        public Builder cacheDir(File cacheDir) {
            this.cacheDir = cacheDir;
            return this;
        }

        public Builder caFile(File caFile) {
            this.caFile = caFile;
            return this;
        }

        public Builder userAgent(@Nullable String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder referer(@Nullable String referer) {
            this.referer = referer;
            return this;
        }

        public Builder hwdec(String hwdec) {
            this.hwdec = hwdec;
            return this;
        }

        public Builder vo(String vo) {
            this.vo = vo;
            return this;
        }

        public Builder ao(String ao) {
            this.ao = ao;
            return this;
        }

        public Builder logLevel(String logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        public Builder tlsVerify(boolean tlsVerify) {
            this.tlsVerify = tlsVerify;
            return this;
        }

        public Builder demuxerMaxBytes(long demuxerMaxBytes) {
            this.demuxerMaxBytes = demuxerMaxBytes;
            return this;
        }

        public Builder demuxerMaxBackBytes(long demuxerMaxBackBytes) {
            this.demuxerMaxBackBytes = demuxerMaxBackBytes;
            return this;
        }

        public Builder option(String name, String value) {
            extraOptions.put(name, value);
            return this;
        }

        public MpvPlayerConfig build() {
            return new MpvPlayerConfig(this);
        }
    }
}
