package android.bluetooth;

import android.content.Context;

import java.util.concurrent.Executor;

/** Compile-only declarations for Android SystemAPI omitted from the public SDK jar. */
public final class BluetoothAdapter {
    public boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener,
            int profile) { return false; }

    public void closeProfileProxy(int profile, BluetoothProfile proxy) { }

    public boolean registerBluetoothConnectionCallback(Executor executor,
            BluetoothConnectionCallback callback) { return false; }

    public boolean unregisterBluetoothConnectionCallback(
            BluetoothConnectionCallback callback) { return false; }

    public abstract static class BluetoothConnectionCallback {
        public void onDeviceConnected(BluetoothDevice device) { }
        public void onDeviceDisconnected(BluetoothDevice device, int reason) { }
    }
}
