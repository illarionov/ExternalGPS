package ru0xdc.externalgps;

import android.os.Environment;

import java.io.File;
import java.io.IOException;

public class DataLoggerConfiguration {

    public static final String APPLICATION_STORAGE_DIR = "ExternalGps";

    public static final String DEFAULT_FILE_PREFIX = "log";

    public static enum Format {

        RAW(1, "raw"),

        NMEA(2, "nmea")

        ;

        private final int mNativeCode;
        private final String mPrefsEntryValue;

        private Format(int nativeCode, String prefsEntryValue) {
            mNativeCode = nativeCode;
            mPrefsEntryValue = prefsEntryValue;
        }

        public int getNativeCode() {
            return mNativeCode;
        }

        public String getPrefsEntryValue() {
            return mPrefsEntryValue;
        }

        public static Format valueOfPrefsEntry(String entryValue) {
            for (Format f: values()) {
                if (f.getPrefsEntryValue().equals(entryValue)) return f;
            }
            throw new IllegalArgumentException();
        }
    }

    private boolean mEnabled;

    private Format mFormat;

    private String mStorageDir;

    private String mFilePrefix;

    public DataLoggerConfiguration() {
        mEnabled = true;
        mFormat = Format.RAW;
        mStorageDir = new File(Environment.getExternalStorageDirectory(), APPLICATION_STORAGE_DIR).getAbsolutePath();
        mFilePrefix = DEFAULT_FILE_PREFIX;
    }

    public DataLoggerConfiguration(final DataLoggerConfiguration src) {
        this();
        set(src);
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public Format getFormat() {
        return mFormat;
    }

    public String getStorageDir() {
        return mStorageDir;
    }

    public String getFilePrefix() {
        return mFilePrefix;
    }

    public DataLoggerConfiguration setEnabled(boolean enabled) {
        mEnabled = enabled;
        return this;
    }

    public DataLoggerConfiguration setFormat(Format format) {
        mFormat = format;
        return this;
    }

    public DataLoggerConfiguration setStorageDir(String dir) {
        mStorageDir = dir;
        return this;
    }

    public DataLoggerConfiguration setFilePrefix(String prefix) {
        mFilePrefix = prefix;
        return this;
    }

    public DataLoggerConfiguration set(final DataLoggerConfiguration src) {
        return setEnabled(src.mEnabled)
                .setFormat(src.mFormat)
                .setStorageDir(src.mStorageDir)
                .setFilePrefix(src.mFilePrefix);
    }

    // XXX
    public void createStorageDir() {
        if (new File(mStorageDir).mkdirs()) {
            try {
                new File(mStorageDir, ".nomedia").createNewFile();
            } catch (IOException ignore) {
            }
        }
    }

    // XXX
    public boolean checkStorageDirPermissions() {
        File tmpFile = null;
        try {
            tmpFile = File.createTempFile("test", null, new File(mStorageDir));
            return tmpFile.canWrite();
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if (tmpFile != null) tmpFile.delete();
        }
        return false;
    }
}
