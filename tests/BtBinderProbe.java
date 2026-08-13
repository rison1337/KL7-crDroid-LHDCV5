import android.bluetooth.BluetoothAdapter;
import android.os.IBinder;

public final class BtBinderProbe {
    public static void main(String[] args) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class)
                .invoke(null, "bluetooth_manager");
        System.out.println("BINDER=" + binder);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        System.out.println("ADAPTER=" + adapter);
        for (java.lang.reflect.Constructor<?> constructor
                : BluetoothAdapter.class.getDeclaredConstructors()) {
            System.out.println("CTOR=" + constructor);
        }
        Class<?> bsm = Class.forName("android.os.BluetoothServiceManager");
        for (java.lang.reflect.Constructor<?> constructor : bsm.getDeclaredConstructors()) {
            System.out.println("BSM_CTOR=" + constructor);
        }
        Class<?> init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
        for (java.lang.reflect.Method method : init.getDeclaredMethods()) {
            System.out.println("INIT_METHOD=" + method);
        }
        if (adapter != null) {
            System.out.println("STATE=" + adapter.getState() + " ENABLED=" + adapter.isEnabled());
        }
    }
}
