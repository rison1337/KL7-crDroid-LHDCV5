import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LhdcControlBridge {
    private static final long LHDC_FEATURE_MAGIC = 0x5c000000L;
    private static final long LHDC_500_KBIT = 0x8006L;
    private static final Object AUTO_LOCK = new Object();
    private static final Set<String> autoApplied = new HashSet<>();
    private static final Set<String> autoPending = new HashSet<>();
    private static final Map<String, Integer> autoAttempts = new HashMap<>();
    private static BluetoothA2dp a2dp;
    private static Handler autoHandler;
    private static BluetoothAdapter.BluetoothConnectionCallback autoConnectionCallback;
    private static AudioDeviceCallback autoAudioCallback;

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

    private static BluetoothCodecConfig lhdcV5Capability(BluetoothCodecStatus status) {
        if (status == null) return null;
        for (BluetoothCodecConfig config : status.getCodecsSelectableCapabilities()) {
            String name = codecName(config).toUpperCase(Locale.ROOT).replace(" ", "");
            if (!name.contains("LHDC")) continue;
            if (name.contains("LHDCV5") || name.contains("LHDC5")) return config;
        }
        return null;
    }

    private static int chooseMask(int available, int preferred, int[] fallback) {
        if ((available & preferred) != 0) return preferred;
        for (int value : fallback) if ((available & value) != 0) return value;
        return 0;
    }

    private static boolean autoApply(BluetoothDevice original) {
        try {
            BluetoothDevice device = findDevice(original.getAddress());
            if (device == null) return false;
            BluetoothCodecStatus codecStatus = status(device);
            BluetoothCodecConfig capability = lhdcV5Capability(codecStatus);
            if (capability == null) {
                System.out.println("AUTO_SKIP " + device.getAddress() + " no selectable LHDC");
                return true;
            }

            int rate = chooseMask(capability.getSampleRate(),
                    BluetoothCodecConfig.SAMPLE_RATE_48000,
                    new int[] { BluetoothCodecConfig.SAMPLE_RATE_44100,
                            BluetoothCodecConfig.SAMPLE_RATE_96000,
                            BluetoothCodecConfig.SAMPLE_RATE_88200 });
            int bits = chooseMask(capability.getBitsPerSample(),
                    BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                    new int[] { BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                            BluetoothCodecConfig.BITS_PER_SAMPLE_32 });
            if (rate == 0 || bits == 0) {
                System.out.println("AUTO_SKIP " + device.getAddress() + " no compatible PCM mode");
                return true;
            }

            BluetoothCodecConfig current = codecStatus.getCodecConfig();
            long c3 = capability.getCodecSpecific3();
            long c4 = capability.getCodecSpecific4();
            if (current != null && codecId(current) == codecId(capability)) {
                c3 = current.getCodecSpecific3();
                c4 = current.getCodecSpecific4();
            }
            if ((c3 & 0xff000000L) != LHDC_FEATURE_MAGIC) c3 = LHDC_FEATURE_MAGIC;

            BluetoothCodecConfig.Builder builder = new BluetoothCodecConfig.Builder()
                    .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                    .setSampleRate(rate)
                    .setBitsPerSample(bits)
                    .setChannelMode((capability.getChannelMode()
                            & BluetoothCodecConfig.CHANNEL_MODE_STEREO) != 0
                            ? BluetoothCodecConfig.CHANNEL_MODE_STEREO
                            : BluetoothCodecConfig.CHANNEL_MODE_MONO)
                    .setCodecSpecific1(LHDC_500_KBIT)
                    .setCodecSpecific2(1)
                    .setCodecSpecific3(c3)
                    .setCodecSpecific4(c4);
            BluetoothCodecType type = capability.getExtendedCodecType();
            if (type != null) builder.setExtendedCodecType(type);
            else builder.setCodecType(capability.getCodecType());
            BluetoothCodecConfig request = builder.build();

            if (current != null && codecId(current) == codecId(request)
                    && current.getSampleRate() == rate
                    && current.getBitsPerSample() == bits
                    && current.getCodecSpecific1() == LHDC_500_KBIT
                    && (current.getCodecSpecific2() & 1) != 0) {
                System.out.println("AUTO_ALREADY " + device.getAddress()
                        + " LHDC V5 500 kbit LL");
                return true;
            }

            set(device, request);
            for (int i = 0; i < 40; i++) {
                Thread.sleep(250);
                BluetoothCodecStatus updated = status(device);
                BluetoothCodecConfig actual = updated == null ? null : updated.getCodecConfig();
                if (actual != null && codecId(actual) == codecId(request)
                        && actual.getSampleRate() == rate
                        && actual.getBitsPerSample() == bits
                        && actual.getCodecSpecific1() == LHDC_500_KBIT
                        && (actual.getCodecSpecific2() & 1) != 0) {
                    System.out.println("AUTO_APPLIED " + device.getAddress()
                            + " LHDC V5 500 kbit LL rate=" + rate + " bits=" + bits);
                    return true;
                }
            }
            System.err.println("AUTO_RETRY " + device.getAddress() + " negotiation timeout");
        } catch (Throwable error) {
            System.err.println("AUTO_RETRY " + original.getAddress() + " "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        return false;
    }

    private static void clearAuto(String address) {
        synchronized (AUTO_LOCK) {
            autoApplied.remove(address);
            autoPending.remove(address);
            autoAttempts.remove(address);
        }
        System.out.println("AUTO_DISCONNECTED " + address);
    }

    private static void scheduleAuto(final BluetoothDevice device, long delayMs) {
        if (device == null) return;
        final String address = device.getAddress();
        synchronized (AUTO_LOCK) {
            if (autoApplied.contains(address) || autoPending.contains(address)) return;
            autoPending.add(address);
        }
        autoHandler.postDelayed(() -> new Thread(() -> {
            boolean complete = autoApply(device);
            int attempt;
            synchronized (AUTO_LOCK) {
                autoPending.remove(address);
                attempt = autoAttempts.containsKey(address) ? autoAttempts.get(address) + 1 : 1;
                if (complete) {
                    autoApplied.add(address);
                    autoAttempts.remove(address);
                } else {
                    autoAttempts.put(address, attempt);
                }
            }
            if (!complete && attempt < 5 && findDevice(address) != null)
                scheduleAuto(device, 3000);
        }, "lhdc-auto-" + address.replace(":", "")).start(), delayMs);
    }

    private static void rescanAuto(long delayMs) {
        autoHandler.postDelayed(() -> {
            try {
                List<BluetoothDevice> devices = a2dp.getConnectedDevices();
                Set<String> connected = new HashSet<>();
                for (BluetoothDevice device : devices) connected.add(device.getAddress());
                synchronized (AUTO_LOCK) {
                    autoApplied.removeIf(address -> !connected.contains(address));
                    autoAttempts.keySet().removeIf(address -> !connected.contains(address));
                }
                for (BluetoothDevice device : devices) scheduleAuto(device, 1000);
            } catch (Throwable error) {
                System.err.println("AUTO_RESCAN " + error.getClass().getSimpleName()
                        + ": " + error.getMessage());
            }
        }, delayMs);
    }

    private static void startAuto(Context context, BluetoothAdapter adapter) {
        autoHandler = new Handler(Looper.getMainLooper());
        autoConnectionCallback = new BluetoothAdapter.BluetoothConnectionCallback() {
            @Override public void onDeviceConnected(BluetoothDevice device) {
                System.out.println("AUTO_EVENT connected " + device.getAddress());
                scheduleAuto(device, 2500);
            }

            @Override public void onDeviceDisconnected(BluetoothDevice device, int reason) {
                System.out.println("AUTO_EVENT disconnected " + device.getAddress()
                        + " reason=" + reason);
                clearAuto(device.getAddress());
            }
        };
        boolean callbackRegistered = adapter.registerBluetoothConnectionCallback(
                command -> autoHandler.post(command), autoConnectionCallback);
        if (!callbackRegistered)
            throw new IllegalStateException("Bluetooth connection callback registration failed");

        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) throw new IllegalStateException("AudioManager unavailable");
        autoAudioCallback = new AudioDeviceCallback() {
            private boolean containsA2dp(AudioDeviceInfo[] devices) {
                for (AudioDeviceInfo device : devices)
                    if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true;
                return false;
            }

            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                if (containsA2dp(addedDevices)) {
                    System.out.println("AUTO_AUDIO A2DP added");
                    rescanAuto(500);
                }
            }

            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                if (containsA2dp(removedDevices)) {
                    System.out.println("AUTO_AUDIO A2DP removed");
                    rescanAuto(500);
                }
            }
        };
        audioManager.registerAudioDeviceCallback(autoAudioCallback, autoHandler);
        rescanAuto(0);
        System.out.println("AUTO_READY event-driven LHDC V5 48 kHz 24 bit 500 kbit LL");
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
        final Context context = systemContext();
        final BluetoothAdapter adapter = directAdapter(context);
        final boolean autoMode = args.length == 1 && "auto".equals(args[0]);
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                a2dp = (BluetoothA2dp) proxy;
                if (autoMode) {
                    try { startAuto(context, adapter); }
                    catch (Throwable error) {
                        error.printStackTrace(System.err);
                        System.exit(2);
                    }
                    return;
                }
                try { run(args, adapter); System.exit(0); }
                catch (Throwable error) { error.printStackTrace(System.err); System.exit(2); }
            }
            @Override public void onServiceDisconnected(int profile) {
                if (autoMode) System.exit(4);
            }
        };
        if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP))
            throw new IllegalStateException("getProfileProxy failed");
        new Thread(() -> {
            try { Thread.sleep(15000); } catch (InterruptedException ignored) { }
            if (a2dp == null) {
                System.err.println("A2DP proxy timeout");
                System.exit(3);
            }
        }, "watchdog").start();
        Looper.loop();
    }
}
