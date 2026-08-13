import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class LhdcControlBridge {
    private static BluetoothA2dp a2dp;

    private static Context systemContext() throws Exception {
        Class<?> cls = Class.forName("android.app.ActivityThread");
        Object thread = cls.getMethod("systemMain").invoke(null);
        return (Context) cls.getMethod("getSystemContext").invoke(thread);
    }

    private static BluetoothAdapter directAdapter(Context context) throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.BluetoothServiceManager");
        Object serviceManager = serviceManagerClass.getConstructor().newInstance();
        Class<?> initializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
        initializer.getMethod("setBluetoothServiceManager", serviceManagerClass)
                .invoke(null, serviceManager);
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class)
                .invoke(null, "bluetooth_manager");
        Class<?> managerClass = Class.forName("android.bluetooth.IBluetoothManager");
        Class<?> stubClass = Class.forName("android.bluetooth.IBluetoothManager$Stub");
        Object manager = stubClass.getMethod("asInterface", IBinder.class).invoke(null, binder);
        AttributionSource attribution = (AttributionSource) AttributionSource.class
                .getMethod("myAttributionSource").invoke(null);
        Constructor<BluetoothAdapter> constructor = BluetoothAdapter.class
                .getDeclaredConstructor(managerClass, Context.class, AttributionSource.class);
        constructor.setAccessible(true);
        return constructor.newInstance(manager, context, attribution);
    }

    private static BluetoothCodecStatus status(BluetoothDevice device) throws Exception {
        Method method = BluetoothA2dp.class.getMethod("getCodecStatus", BluetoothDevice.class);
        return (BluetoothCodecStatus) method.invoke(a2dp, device);
    }

    private static void set(BluetoothDevice device, BluetoothCodecConfig config) throws Exception {
        Method method = BluetoothA2dp.class.getMethod("setCodecConfigPreference",
                BluetoothDevice.class, BluetoothCodecConfig.class);
        method.invoke(a2dp, device, config);
    }

    private static long codecId(BluetoothCodecConfig config) {
        BluetoothCodecType type = config.getExtendedCodecType();
        return type == null ? config.getCodecType() : type.getCodecId();
    }

    private static String codecName(BluetoothCodecConfig config) {
        BluetoothCodecType type = config.getExtendedCodecType();
        if (type != null && type.getCodecName() != null) return type.getCodecName();
        return "Codec " + config.getCodecType();
    }

    private static String encode(String value) {
        if (value == null || value.isEmpty()) value = "Bluetooth-устройство";
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static void printConfig(String kind, BluetoothDevice device,
            BluetoothCodecConfig config) {
        System.out.println(kind + "\t" + device.getAddress() + "\t" + codecId(config)
                + "\t" + encode(codecName(config)) + "\t" + config.getCodecType()
                + "\t" + config.getSampleRate() + "\t" + config.getBitsPerSample()
                + "\t" + config.getChannelMode() + "\t" + config.getCodecSpecific1()
                + "\t" + config.getCodecSpecific2() + "\t" + config.getCodecSpecific3()
                + "\t" + config.getCodecSpecific4() + "\t0");
    }

    private static void list() throws Exception {
        for (BluetoothDevice device : a2dp.getConnectedDevices()) {
            System.out.println("DEVICE\t" + device.getAddress() + "\t" + encode(device.getName()));
            BluetoothCodecStatus status = status(device);
            if (status == null) continue;
            if (status.getCodecConfig() != null) printConfig("CURRENT", device, status.getCodecConfig());
            for (BluetoothCodecConfig config : status.getCodecsSelectableCapabilities())
                printConfig("CAP", device, config);
        }
        System.out.println("DONE");
    }

    private static BluetoothDevice findDevice(String address) {
        for (BluetoothDevice device : a2dp.getConnectedDevices())
            if (address.equalsIgnoreCase(device.getAddress())) return device;
        return null;
    }

    private static void apply(String[] args) throws Exception {
        if (args.length != 9) throw new IllegalArgumentException(
                "set address codecId rate bits c1 c2 c3 c4");
        BluetoothDevice device = findDevice(args[1]);
        if (device == null) throw new IllegalStateException("A2DP device not found");
        long wantedId = Long.parseLong(args[2]);
        BluetoothCodecStatus status = status(device);
        BluetoothCodecConfig capability = null;
        for (BluetoothCodecConfig config : status.getCodecsSelectableCapabilities())
            if (codecId(config) == wantedId) { capability = config; break; }
        if (capability == null) throw new IllegalArgumentException("Codec is not selectable");

        BluetoothCodecConfig.Builder builder = new BluetoothCodecConfig.Builder()
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .setSampleRate(Integer.parseInt(args[3]))
                .setBitsPerSample(Integer.parseInt(args[4]))
                .setChannelMode((capability.getChannelMode() & BluetoothCodecConfig.CHANNEL_MODE_STEREO) != 0
                        ? BluetoothCodecConfig.CHANNEL_MODE_STEREO
                        : BluetoothCodecConfig.CHANNEL_MODE_MONO)
                .setCodecSpecific1(Long.parseLong(args[5]))
                .setCodecSpecific2(Long.parseLong(args[6]))
                .setCodecSpecific3(Long.parseLong(args[7]))
                .setCodecSpecific4(Long.parseLong(args[8]));
        BluetoothCodecType type = capability.getExtendedCodecType();
        if (type != null) builder.setExtendedCodecType(type);
        else builder.setCodecType(capability.getCodecType());
        BluetoothCodecConfig request = builder.build();
        set(device, request);
        for (int i = 0; i < 32; i++) {
            Thread.sleep(250);
            BluetoothCodecConfig actual = status(device).getCodecConfig();
            boolean lhdc = codecName(request).toUpperCase().contains("LHDC");
            if (actual != null && codecId(actual) == codecId(request)
                    && actual.getSampleRate() == request.getSampleRate()
                    && actual.getBitsPerSample() == request.getBitsPerSample()
                    && (!lhdc || (actual.getCodecSpecific1() == request.getCodecSpecific1()
                    && (actual.getCodecSpecific2() & 1) == (request.getCodecSpecific2() & 1)))) {
                printConfig("APPLIED", device, actual);
                return;
            }
        }
        BluetoothCodecConfig actual = status(device).getCodecConfig();
        if (actual != null) printConfig("TIMEOUT", device, actual);
        throw new IllegalStateException("Negotiation timeout");
    }

    private static void run(String[] args, BluetoothAdapter adapter) throws Exception {
        try {
            if (args.length == 0 || "list".equals(args[0])) list();
            else if ("set".equals(args[0])) apply(args);
            else throw new IllegalArgumentException("Unknown command");
        } finally {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        }
    }

    public static void main(final String[] args) throws Exception {
        Looper.prepareMainLooper();
        Context context = systemContext();
        final BluetoothAdapter adapter = directAdapter(context);
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                a2dp = (BluetoothA2dp) proxy;
                try { run(args, adapter); System.exit(0); }
                catch (Throwable error) { error.printStackTrace(System.err); System.exit(2); }
            }
            @Override public void onServiceDisconnected(int profile) { }
        };
        if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP))
            throw new IllegalStateException("getProfileProxy failed");
        new Thread(() -> {
            try { Thread.sleep(15000); } catch (InterruptedException ignored) { }
            System.err.println("A2DP proxy timeout"); System.exit(3);
        }, "watchdog").start();
        Looper.loop();
    }
}
