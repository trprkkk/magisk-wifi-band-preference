import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Applies android.net.wifi.WifiNetworkSelectionConfig through IWifiManager.
 * Intended to be run by root via app_process:
 *
 *   CLASSPATH=/data/adb/modules/wifi_band_preference/wbp.dex \
 *       app_process /system/bin WifiBandPref <mode>
 *
 * Modes:
 *   auto        framework defaults (clear overrides)
 *   5g-prefer   5GHz frequency weight HIGH, 2.4GHz LOW
 *   5g-force    ignore all 2.4GHz candidates (entry threshold above any real RSSI)
 *   2g-prefer   2.4GHz frequency weight HIGH, 5GHz LOW
 *   2g-force    ignore all 5GHz candidates
 */
public final class WifiBandPref {

    private static final String CFG_CLASS = "android.net.wifi.WifiNetworkSelectionConfig";
    private static final String BUILDER_CLASS = CFG_CLASS + "$Builder";
    private static final String IWIFI_CLASS = "android.net.wifi.IWifiManager";
    private static final String SPARSE_CLASS = "android.util.SparseArray";

    // ScanResult.WIFI_BAND_24_GHZ / WIFI_BAND_5_GHZ
    private static final int BAND_24 = 1;
    private static final int BAND_5 = 2;

    // {exit, entry, sufficient, good}
    // entry = -3 dBm filters out every real access point of that band.
    private static final int[] BLOCK_BAND = {-4, -3, -2, -1};
    private static final int[] RESET_BAND = {0, 0, 0, 0};

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0].trim().toLowerCase() : "auto";
        boolean block24 = false;
        boolean block5 = false;
        boolean boost5 = false;
        boolean boost24 = false;

        switch (mode) {
            case "auto":
            case "reset":
                break;
            case "5g":
            case "5g-prefer":
                boost5 = true;
                break;
            case "5g-force":
            case "5g-only":
                block24 = true;
                boost5 = true;
                break;
            case "2g":
            case "2.4g":
            case "2g-prefer":
                boost24 = true;
                break;
            case "2g-force":
            case "2g-only":
            case "2.4g-force":
                block5 = true;
                boost24 = true;
                break;
            default:
                System.err.println("unknown mode: " + mode);
                System.err.println("modes: auto | 5g-prefer | 5g-force | 2g-prefer | 2g-force");
                System.exit(2);
                return;
        }

        try {
            exemptHiddenApi();
            Object config = buildConfig(block24, block5, boost5, boost24);
            setNetworkSelectionConfig(config);
            System.out.println("OK: " + mode);
        } catch (Throwable t) {
            Throwable cause = (t instanceof InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            System.err.println("FAILED: " + cause);
            cause.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Object buildConfig(boolean block24, boolean block5,
                                      boolean boost5, boolean boost24) throws Exception {
        Class<?> sparseClass = Class.forName(SPARSE_CLASS);
        Class<?> builderClass = Class.forName(BUILDER_CLASS);
        Object builder = builderClass.getConstructor().newInstance();

        Method setRssi = builderClass.getMethod("setRssiThresholds", int.class, int[].class);
        setRssi.invoke(builder, BAND_24, block24 ? BLOCK_BAND : RESET_BAND);
        setRssi.invoke(builder, BAND_5, block5 ? BLOCK_BAND : RESET_BAND);

        if (boost5 || boost24) {
            Object weights = sparseClass.getConstructor().newInstance();
            Method put = sparseClass.getMethod("put", int.class, Object.class);
            // 5GHz: 4900..5925 MHz, 2.4GHz: 2400..2500 MHz.
            // Use 1MHz steps: 2.4GHz channel centers (2412/2437/2462) are not
            // multiples of 5, and the framework looks up the exact frequency.
            fillBand(put, weights, 4900, 5925, boost5 ? Integer.valueOf(1) : Integer.valueOf(0));
            fillBand(put, weights, 2400, 2500, boost24 ? Integer.valueOf(1) : Integer.valueOf(0));
            Method setWeights = builderClass.getMethod("setFrequencyWeights", sparseClass);
            setWeights.invoke(builder, weights);
        }

        Method build = builderClass.getMethod("build");
        return build.invoke(builder);
    }

    private static void fillBand(Method put, Object weights, int from, int to, Integer value)
            throws Exception {
        for (int freq = from; freq <= to; freq += 1) {
            put.invoke(weights, freq, value);
        }
    }

    private static void setNetworkSelectionConfig(Object config) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Object binder = serviceManager.getMethod("getService", String.class)
                .invoke(null, "wifi");
        if (binder == null) {
            throw new IllegalStateException("wifi service is not available");
        }
        Class<?> iBinder = Class.forName("android.os.IBinder");
        Class<?> stub = Class.forName(IWIFI_CLASS + "$Stub");
        Object wifiManager = stub.getMethod("asInterface", iBinder).invoke(null, binder);
        Class<?> iWifi = Class.forName(IWIFI_CLASS);
        Class<?> configClass = Class.forName(CFG_CLASS);
        Method set = iWifi.getMethod("setNetworkSelectionConfig", configClass);
        set.invoke(wifiManager, config);
    }

    /**
     * app_process runs this class in the application class loader, so make sure
     * hidden framework APIs can be resolved. Harmless if it is unsupported.
     */
    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
            vmRuntime.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
            // Hidden API enforcement may already be disabled for app_process.
        }
    }
}
