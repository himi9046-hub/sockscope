package sockscope;

import java.util.Locale;

public final class Bytes {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Bytes() {}

    public static String format(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, UNITS[unit]);
    }

    public static String rate(long bytesPerSecond) {
        return bytesPerSecond == 0 ? "" : format(bytesPerSecond) + "/s";
    }
}
