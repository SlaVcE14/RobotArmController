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

public class BluetoothController {
    public BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    public volatile boolean isConnected = false;

    // Standard BLE Serial UUIDs
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // HM-10 / JDY-08 (Single characteristic for RX and TX)
    private static final UUID HM10_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID HM10_CHAR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    // Nordic UART Service (Separate RX and TX)
    private static final UUID NORDIC_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NORDIC_RX_CHAR = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // App writes to this
    private static final UUID NORDIC_TX_CHAR = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"); // App receives from this


    public ArrayList<BluetoothDevice> devices = new ArrayList<>();
    public BluetoothDevice selectedDevice;

    private boolean receiverRegistered = false;

    private Handler mainHandler;

    CallBack callBack;

    CheckPermission checkPermissionCallBack;

    public interface CallBack {
        void onConnect();

        void onDisconnect();

        void onStatusUpdate(BluetoothStatus status);
    }

    public interface OnDeviceLoad {
        void onLoad();
    }

    public interface CheckPermission {
        boolean hasBluetoothPermission();

        boolean hasScanPermission();
    }


    {
        mainHandler = new Handler(Looper.getMainLooper());
    }


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
                updateStatus(Status.SCAN_FINISHED,"Scan completed");
            }
        }
    };


    public void registerDiscoveryReceiver(Context context) {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ContextCompat.registerReceiver(context, discoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;
    }

    public void unregisterDiscoveryReceiver(Context context) {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver);
            } catch (Exception e) {
            }
            receiverRegistered = false;
        }
    }

    public void setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            //todo Bluetooth not supported
        }
    }

    public void loadPairedDevices(OnDeviceLoad callback) {
        if (!hasBluetoothPermission() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            devices.clear();
            if (pairedDevices != null) devices.addAll(pairedDevices);
            callback.onLoad();
        } catch (SecurityException e) {
        }
    }


    public void scanDevices(Context context) {


        if (!hasBluetoothPermission() || !hasScanPermission()) {
            //            checkAndRequestPermissions();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            //            onPermissionsReady();
            return;
        }
        registerDiscoveryReceiver(context); // todo do I need this?????

        try {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException e) {
        }

        devices.clear();
        loadPairedDevices(() -> {});

        //        devicesListDialog.setMessage(getString(R.string.btn_scanning)); todo scanning


        try {
            if (bluetoothAdapter.startDiscovery()) {
                updateStatus(Status.SCANNING, "Scanning for nearby devices...");
            } else {
                updateStatus(Status.FAILED_SCANNING, "Failed to start scanning");
            }
        } catch (SecurityException e) {

        }
    }

    public void selectDevice(BluetoothDevice device) {
        selectedDevice = device;
    }

    public void connectDevice(Context context) {
        if (selectedDevice == null) {
            //            showToast("Select a device first"); todo

            return;
        }
        if (isConnected) return;
        if (!hasBluetoothPermission()) {
            //            checkAndRequestPermissions();
            return;
        }

        updateStatus(Status.CONNECTING,"Connecting via BLE to " + getDeviceName(selectedDevice) + "...");

        try {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException e) {
        }

        // Connect using BLE (GATT)
        try {
            bluetoothGatt = selectedDevice.connectGatt(context, false, gattCallback);
        } catch (SecurityException e) {
            //            showToast("Permission denied connecting to GATT"); todo
        }
    }


    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mainHandler.post(() -> updateStatus(Status.CONNECTED, "Connected! Discovering BLE services..."));
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                writeCharacteristic = null;
                notifyCharacteristic = null;
                mainHandler.post(() -> {
                    updateStatus(Status.DISCONNECTED, "Disconnected");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService hm10Service = gatt.getService(HM10_SERVICE);
                BluetoothGattService nordicService = gatt.getService(NORDIC_SERVICE);

                if (hm10Service != null) {
                    writeCharacteristic = hm10Service.getCharacteristic(HM10_CHAR);
                    notifyCharacteristic = writeCharacteristic;
                } else if (nordicService != null) {
                    writeCharacteristic = nordicService.getCharacteristic(NORDIC_RX_CHAR);
                    notifyCharacteristic = nordicService.getCharacteristic(NORDIC_TX_CHAR);
                } else {
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
                        updateStatus(Status.CONNECTED,"Connected!");
                    });

                    if (notifyCharacteristic != null) {
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
                        } catch (SecurityException e) {
                        }
                    }
                } else {
                    mainHandler.post(() -> {
                        updateStatus(Status.ERROR, "Device is not a BLE Serial module");
                        try {
                            gatt.disconnect();
                        } catch (SecurityException e) {
                        }
                    });
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                String str = new String(data).trim();
                if (!str.isEmpty()) {
                    mainHandler.post(() -> updateStatus(Status.RECEIVE, str));
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
            if (data != null && data.length > 0) {
                String str = new String(data).trim();
                if (!str.isEmpty()) {
                    mainHandler.post(() -> updateStatus(Status.RECEIVE, str));
                }
            }
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] data, int status) {
            super.onCharacteristicRead(gatt, characteristic, data, status);
            System.out.println("Bluetooth status: " + data);
            if (data != null && data.length > 0) {
                String str = new String(data).trim();
                if (!str.isEmpty()) {
                    mainHandler.post(() -> updateStatus(Status.RECEIVE, str));
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
        }
    };


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
        } catch (SecurityException e) {
        }
    }

    public String getDeviceName(BluetoothDevice device) {
        try {
            if (!hasBluetoothPermission()) return device.getAddress();
            String name = device.getName();
            return (name != null && !name.isEmpty()) ? name : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) {
            }
            bluetoothGatt = null;
        }
        isConnected = false;
        writeCharacteristic = null;
        notifyCharacteristic = null;

        updateStatus(Status.DISCONNECTED, "Disconnected");
    }

    private void updateStatus(Status status, String message) {
        if (callBack != null)
            callBack.onStatusUpdate(new BluetoothStatus(status, message));
    }

    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled() && hasBluetoothPermission();
    }

    private boolean hasScanPermission() {return checkPermissionCallBack.hasScanPermission();}


    private boolean hasBluetoothPermission() {return checkPermissionCallBack.hasBluetoothPermission();}

}
