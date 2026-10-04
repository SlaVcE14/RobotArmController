package com.sj14apps.robotarmcontroller.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
//Bluetooth Low Energy Android Controller
/**
 * Reusable Bluetooth Low Energy controller for communicating with BLE serial modules
 * (HM-10, AT-09, JDY-08, Nordic UART, etc.).
 *
 * <p>Usage:
 * <pre>
 *   BluetoothController bt = new BluetoothController(callback, permissionCallback);
 *   bt.setupBluetooth();
 *   bt.registerDiscoveryReceiver(context);
 *   bt.scanDevices(context);
 *   bt.selectDevice(device);
 *   bt.connectDevice(context);
 *   bt.sendData("Hello\n");
 * </pre>
 */
public class BluetoothController {

    // ─── BLE UUIDs ───────────────────────────────────────────
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // HM-10 / JDY-08 (Single characteristic for RX and TX)
    private static final UUID HM10_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID HM10_CHAR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    // Nordic UART Service (Separate RX and TX)
    private static final UUID NORDIC_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NORDIC_RX_CHAR = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NORDIC_TX_CHAR = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    // ─── State ───────────────────────────────────────────────
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private volatile boolean isConnected = false;

    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private BluetoothDevice selectedDevice;
    private boolean receiverRegistered = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final CallBack callBack;
    private final CheckPermission checkPermissionCallBack;

    /**
     * Main callback interface for BLE events.
     */
    public interface CallBack {
        /** Called on the main thread when connected and ready to send/receive. */
        void onConnect();

        /** Called on the main thread when the device disconnects. */
        void onDisconnect();

        /** Called on the main thread with status updates (connecting, scanning, errors, etc.). */
        void onStatusUpdate(BluetoothStatus status);

        /** Called on the main thread when data is received from the BLE device. */
        void onDataReceived(String data);
    }

    /**
     * Callback for when paired devices are loaded.
     */
    public interface OnDeviceLoad {
        void onLoad();
    }

    /**
     * Permission checking interface. The host Activity must implement this
     * because permission checks require an Activity context.
     */
    public interface CheckPermission {
        boolean hasBluetoothPermission();
        boolean hasScanPermission();
    }

    /**
     * Creates a new BluetoothController.
     *
     * @param callBack              Callback for BLE events (connect, disconnect, data, status).
     * @param checkPermissionCallBack Callback to check if permissions are granted.
     */
    public BluetoothController(CallBack callBack, CheckPermission checkPermissionCallBack) {
        this.callBack = callBack;
        this.checkPermissionCallBack = checkPermissionCallBack;
    }

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && !devices.contains(device)) {
                    devices.add(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                updateStatus(Status.SCAN_FINISHED, "Scan complete — " + devices.size() + " devices found");
            }
        }
    };


    /**
     * Initialises the BluetoothAdapter. Call this early (e.g. in onCreate).
     */
    public void setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            updateStatus(Status.ERROR, "Bluetooth not supported on this device");
        }
    }

    /**
     * Registers the BroadcastReceiver for Bluetooth discovery events.
     *
     * @param context Application or Activity context.
     */
    public void registerDiscoveryReceiver(Context context) {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ContextCompat.registerReceiver(context, discoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;
    }

    /**
     * Unregisters the discovery BroadcastReceiver.
     *
     * @param context The same context used in {@link #registerDiscoveryReceiver(Context)}.
     */
    public void unregisterDiscoveryReceiver(Context context) {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver);
            } catch (Exception e) { /* receiver may have already been unregistered */ }
            receiverRegistered = false;
        }
    }

    /**
     * Loads already-paired Bluetooth devices into the devices list.
     *
     * @param callback Called after the paired device list is refreshed.
     */
    public void loadPairedDevices(OnDeviceLoad callback) {
        if (!hasBluetoothPermission() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled())
            return;
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            devices.clear();
            if (pairedDevices != null) devices.addAll(pairedDevices);
            callback.onLoad();
        } catch (SecurityException e) { /* permission not granted */ }
    }

    /**
     * Starts scanning for nearby BLE devices. Loads paired devices first,
     * then begins active discovery.
     *
     * @param context Application or Activity context.
     */
    public void scanDevices(Context context) {
        if (!hasBluetoothPermission() || !hasScanPermission()) {
            updateStatus(Status.ERROR, "Bluetooth & Location permissions are required");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            updateStatus(Status.ERROR, "Bluetooth must be enabled");
            return;
        }
        registerDiscoveryReceiver(context);

        try {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException e) { /* ignored */ }

        devices.clear();
        loadPairedDevices(() -> {});

        try {
            if (bluetoothAdapter.startDiscovery()) {
                updateStatus(Status.SCANNING, "Scanning for nearby devices...");
            } else {
                updateStatus(Status.FAILED_SCANNING, "Failed to start scanning");
            }
        } catch (SecurityException e) {
            updateStatus(Status.ERROR, "Permission denied for scanning");
        }
    }

    /**
     * Selects a device for subsequent connection.
     *
     * @param device The BluetoothDevice to connect to.
     */
    public void selectDevice(BluetoothDevice device) {
        selectedDevice = device;
    }

    /**
     * Connects to the currently selected BLE device via GATT.
     *
     * @param context Application or Activity context.
     */
    public void connectDevice(Context context) {
        if (selectedDevice == null) {
            updateStatus(Status.ERROR, "Select a device first");
            return;
        }
        if (isConnected) return;
        if (!hasBluetoothPermission()) {
            updateStatus(Status.ERROR, "Bluetooth permission required");
            return;
        }

        updateStatus(Status.CONNECTING, "Connecting via BLE to " + getDeviceName(selectedDevice) + "...");

        try {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException e) { /* ignored */ }

        try {
            bluetoothGatt = selectedDevice.connectGatt(context, false, gattCallback);
        } catch (SecurityException e) {
            updateStatus(Status.ERROR, "Permission denied connecting to GATT");
        }
    }

    /**
     * Sends a string to the connected BLE device.
     *
     * @param data The string data to send (include \\n if the device expects it).
     */
    public void sendData(String data) {
        if (!isConnected || bluetoothGatt == null || writeCharacteristic == null) return;

        byte[] bytes = data.getBytes();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt.writeCharacteristic(writeCharacteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                writeCharacteristic.setValue(bytes);
                bluetoothGatt.writeCharacteristic(writeCharacteristic);
            }
            mainHandler.post(() -> updateStatus(Status.SEND, data.trim()));
        } catch (SecurityException e) { /* ignored */ }
    }

    /**
     * Disconnects from the BLE device and releases resources.
     */
    public void disconnect() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) { /* ignored */ }
            bluetoothGatt = null;
        }
        isConnected = false;
        writeCharacteristic = null;
        notifyCharacteristic = null;

        updateStatus(Status.DISCONNECTED, "Disconnected");
    }


    /** Returns the human-readable name of a device, or its MAC address as fallback. */
    public String getDeviceName(BluetoothDevice device) {
        try {
            if (!hasBluetoothPermission()) return device.getAddress();
            String name = device.getName();
            return (name != null && !name.isEmpty()) ? name : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    /** Returns whether the BLE connection is currently active. */
    public boolean isConnected() {
        return isConnected;
    }

    /** Returns the list of discovered/paired devices. */
    public ArrayList<BluetoothDevice> getDevices() {
        return devices;
    }

    /** Returns the currently selected device, or null. */
    public BluetoothDevice getSelectedDevice() {
        return selectedDevice;
    }

    /** Returns the BluetoothAdapter, or null if not yet set up. */
    public BluetoothAdapter getBluetoothAdapter() {
        return bluetoothAdapter;
    }

    /** Returns true if Bluetooth is available, enabled, and permitted. */
    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled() && hasBluetoothPermission();
    }


    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mainHandler.post(() -> updateStatus(Status.CONNECTED, "Connected! Discovering BLE services..."));
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) { /* ignored */ }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                writeCharacteristic = null;
                notifyCharacteristic = null;
                mainHandler.post(() -> {
                    updateStatus(Status.DISCONNECTED, "Disconnected");
                    callBack.onDisconnect();
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;

            BluetoothGattService hm10Service = gatt.getService(HM10_SERVICE);
            BluetoothGattService nordicService = gatt.getService(NORDIC_SERVICE);

            if (hm10Service != null) {
                writeCharacteristic = hm10Service.getCharacteristic(HM10_CHAR);
                notifyCharacteristic = writeCharacteristic;
            } else if (nordicService != null) {
                writeCharacteristic = nordicService.getCharacteristic(NORDIC_RX_CHAR);
                notifyCharacteristic = nordicService.getCharacteristic(NORDIC_TX_CHAR);
            } else {
                // Fallback: scan all services for writable/notifiable characteristics
                for (BluetoothGattService service : gatt.getServices()) {
                    for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                        int props = c.getProperties();
                        if ((props & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                            writeCharacteristic = c;
                        }
                        if ((props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                            notifyCharacteristic = c;
                        }
                    }
                }
            }

            if (writeCharacteristic != null) {
                isConnected = true;
                mainHandler.post(() -> {
                    callBack.onConnect();
                    updateStatus(Status.CONNECTED, "Connected!");
                });
                subscribeToNotifications(gatt);
            } else {
                mainHandler.post(() -> {
                    updateStatus(Status.ERROR, "Device is not a BLE Serial module");
                    try { gatt.disconnect(); } catch (SecurityException e) { /* ignored */ }
                });
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            handleReceivedData(data);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
            handleReceivedData(data);
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] data, int status) {
            super.onCharacteristicRead(gatt, characteristic, data, status);
            handleReceivedData(data);
        }
    };

    private void handleReceivedData(byte[] data) {
        if (data != null && data.length > 0) {
            String str = new String(data).trim();
            if (!str.isEmpty()) {
                mainHandler.post(() -> {
                    updateStatus(Status.RECEIVE, str);
                    callBack.onDataReceived(str);
                });
            }
        }
    }

    private void subscribeToNotifications(BluetoothGatt gatt) {
        if (notifyCharacteristic == null) return;
        try {
            gatt.setCharacteristicNotification(notifyCharacteristic, true);
            BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
            }
        } catch (SecurityException e) { /* ignored */ }
    }

    private void updateStatus(Status status, String message) {
        if (callBack != null) {
            callBack.onStatusUpdate(new BluetoothStatus(status, message));
        }
    }

    private boolean hasScanPermission() {
        return checkPermissionCallBack.hasScanPermission();
    }

    private boolean hasBluetoothPermission() {
        return checkPermissionCallBack.hasBluetoothPermission();
    }
}
