package com.sj14apps.robotarmcontroller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.sj14apps.robotarmcontroller.about.AboutActivity;
import com.sjapps.library.customdialog.ListDialog;
import com.sjapps.library.customdialog.ListItemValues;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "RobotArm";
    
    // Throttle: min interval between Bluetooth sends per servo (ms)
    private static final long SEND_THROTTLE_MS = 50;

    // Default (home) angle for all servos
    private static final int CLAW_OPEN = 180;
    private static final int CLAW_CLOSE = 160;

    // ─── BLE GATT ────────────────────────────────────────────
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private volatile boolean isConnected = false;
    
    // Standard BLE Serial UUIDs
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    
    // HM-10 / JDY-08 (Single characteristic for RX and TX)
    private static final UUID HM10_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID HM10_CHAR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    // Nordic UART Service (Separate RX and TX)
    private static final UUID NORDIC_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NORDIC_RX_CHAR = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // App writes to this
    private static final UUID NORDIC_TX_CHAR = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"); // App receives from this

    // ─── UI Views ────────────────────────────────────────────
    private Button btnScan, btnConnect, btnDisconnect, btnPower;
    private Button btnHome, btnOpenClaw, btnCloseClaw;
    private Button btnJoyUp, btnJoyDown, btnJoyLeft, btnJoyRight, btnJoyArm2Up, btnJoyArm2Down;
    private TextView statusText, dataText;


    private ArrayList<BluetoothDevice> devices;
    private BluetoothDevice selectedDevice;
    private Handler mainHandler;

    private Handler repeatHandler = new Handler(Looper.getMainLooper());
    private Runnable repeatRunnable;
    private static final int REPEAT_INTERVAL_MS = 60; // How fast angle changes when holding button



    boolean isMotorsOn;

    private boolean receiverRegistered = false;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> enableBtLauncher;

    ListDialog devicesListDialog = new ListDialog();

    // ─── Bluetooth Discovery Receiver ────────────────────────
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
                btnScan.setText(R.string.btn_select);
                btnScan.setEnabled(true);
                updateStatus("Scan complete — " + devices.size() + " devices found");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        devicesListDialog.Builder(this,true)
                .setTitle("Select Device")
                .dialogWithTwoButtons()
                .setRightButtonText("Scan")
                .hideEmptyListText()
                .onButtonClick(this::scanDevices);

        initViews();
        setupPermissions();
        setupBluetooth();

        devicesListDialog.setItems(devices, new ListItemValues<BluetoothDevice>() {
            @Override
            public String getValue1(BluetoothDevice bluetoothDevice) {
                String name = getDeviceName(bluetoothDevice);
                if (name.toLowerCase().contains("hc") || name.toLowerCase().contains("bt")) {
                    name = "⭐ " + name;
                }
                return name;
            }

            @Override
            public String getValue2(BluetoothDevice bluetoothDevice) {
                return bluetoothDevice.getAddress();
            }
        },(i, bluetoothDevice) -> {
            selectedDevice = bluetoothDevice;
            btnConnect.setEnabled(true);
            updateStatus("Selected: " + getDeviceName(selectedDevice));
            devicesListDialog.dismiss();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && hasBluetoothPermission()) {
            loadPairedDevices();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        unregisterDiscoveryReceiver();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        setContentView(R.layout.activity_main);
        initViews();
        updateUI(isConnected);
    }

    private void initViews() {

        btnScan = findViewById(R.id.btnScan);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnPower = findViewById(R.id.btnPower);
        statusText = findViewById(R.id.statusText);
        dataText = findViewById(R.id.dataText);

        btnJoyUp = findViewById(R.id.btnJoyUp);
        btnJoyDown = findViewById(R.id.btnJoyDown);
        btnJoyLeft = findViewById(R.id.btnJoyLeft);
        btnJoyRight = findViewById(R.id.btnJoyRight);
        btnJoyArm2Up = findViewById(R.id.btnJoyArm2Up);
        btnJoyArm2Down = findViewById(R.id.btnJoyArm2Down);

        btnHome = findViewById(R.id.btnHome);
        btnOpenClaw = findViewById(R.id.btnOpenClaw);
        btnCloseClaw = findViewById(R.id.btnCloseClaw);

        devices = new ArrayList<>();
        mainHandler = new Handler(Looper.getMainLooper());

        btnScan.setOnClickListener(v -> devicesListDialog.show());
        btnConnect.setOnClickListener(v -> connectDevice());
        btnDisconnect.setOnClickListener(v -> disconnect());

        btnPower.setOnClickListener(v -> {
            isMotorsOn = !isMotorsOn;
            sendData("P\n");
            btnPower.setBackgroundColor(isMotorsOn? getColor(R.color.connected_green):getColor(R.color.disconnected_red));
            btnPower.setText(isMotorsOn? R.string.powerMotorsOn:R.string.powerMotorsOff);
        });

        findViewById(R.id.aboutBtn).setOnClickListener(v -> {
            startActivity(new Intent(this,AboutActivity.class));
        });


        btnHome.setOnClickListener(v -> {
            sendData("H\n");
        });

        btnOpenClaw.setOnClickListener(v -> {
            sendData("C" + CLAW_OPEN + "\n");
        });

        btnCloseClaw.setOnClickListener(v -> {
            sendData("C" + CLAW_CLOSE + "\n");
        });

        setupJoystickButton(btnJoyUp, "A+");
        setupJoystickButton(btnJoyDown, "A-");
        setupJoystickButton(btnJoyLeft, "B+");
        setupJoystickButton(btnJoyRight, "B-");
        setupJoystickButton(btnJoyArm2Up, "S+");
        setupJoystickButton(btnJoyArm2Down, "S-");

        updateUI(false);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root),(v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupJoystickButton(Button btn, String command) {
        btn.setOnTouchListener((v, event) -> {
            if (!isConnected) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (repeatRunnable != null) {
                        repeatHandler.removeCallbacks(repeatRunnable);
                    }
                    repeatRunnable = new Runnable() {
                        @Override
                        public void run() {
                            sendData(command + "\n");
                            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS);
                        }
                    };
                    repeatHandler.post(repeatRunnable);
                    v.setPressed(true);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (repeatRunnable != null) {
                        repeatHandler.removeCallbacks(repeatRunnable);
                        repeatRunnable = null;
                    }
                    v.setPressed(false);
                    return true;
            }
            return false;
        });
    }

    private void registerDiscoveryReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ContextCompat.registerReceiver(this, discoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;
    }

    private void unregisterDiscoveryReceiver() {
        if (receiverRegistered) {
            try { unregisterReceiver(discoveryReceiver); } catch (Exception e) {}
            receiverRegistered = false;
        }
    }


    private void setupPermissions() {
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!granted) allGranted = false;
            }
            if (allGranted) onPermissionsReady();
            else showToast("Bluetooth & Location permissions are required");
        });

        enableBtLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) onPermissionsReady();
            else showToast("Bluetooth must be enabled");
        });

        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{ Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION };
        } else {
            permissions = new String[]{ Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION };
        }

        boolean needPermission = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needPermission = true;
                break;
            }
        }

        if (needPermission) permissionLauncher.launch(permissions);
        else onPermissionsReady();
    }

    private void onPermissionsReady() {
        if (bluetoothAdapter == null) return;
        if (!bluetoothAdapter.isEnabled()) {
            enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        registerDiscoveryReceiver();
        loadPairedDevices();
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showToast("Bluetooth not supported");
            finish();
        }
    }

    private void loadPairedDevices() {
        if (!hasBluetoothPermission() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            devices.clear();
            if (pairedDevices != null) devices.addAll(pairedDevices);
            updateStatus("Found " + devices.size() + " paired devices — tap one to select");
            devicesListDialog.getListAdapter().notifyDataSetChanged();
            devicesListDialog.hideEmptyListText();
        } catch (SecurityException e) {
        }
    }

    private void scanDevices() {


        devicesListDialog.show();

        if (!hasBluetoothPermission() || !hasScanPermission()) {
            checkAndRequestPermissions();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            onPermissionsReady();
            return;
        }
        registerDiscoveryReceiver();

        try {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException e) { }

        devices.clear();
        loadPairedDevices();

        devicesListDialog.setMessage(getString(R.string.btn_scanning));


        try {
            if (bluetoothAdapter.startDiscovery()) updateStatus("Scanning for nearby devices...");
            else {
                showToast("Failed to start scanning");
            }
        } catch (SecurityException e) {

        }
    }


    private void connectDevice() {
        if (selectedDevice == null) {
            showToast("Select a device first");
            return;
        }
        if (isConnected) return;
        if (!hasBluetoothPermission()) {
            checkAndRequestPermissions();
            return;
        }

        btnConnect.setEnabled(false);
        updateStatus("Connecting via BLE to " + getDeviceName(selectedDevice) + "...");
        
        try {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException e) {}

        // Connect using BLE (GATT)
        try {
            bluetoothGatt = selectedDevice.connectGatt(this, false, gattCallback);
        } catch (SecurityException e) {
            showToast("Permission denied connecting to GATT");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mainHandler.post(() -> updateStatus("Connected! Discovering BLE services..."));
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                writeCharacteristic = null;
                notifyCharacteristic = null;
                mainHandler.post(() -> {
                    updateUI(false);
                    updateStatus("Disconnected");
                    showToast("Disconnected");
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
                        updateUI(true);
                        updateStatus("Ready! Connected to BLE Serial");
                        showToast("Connected!");
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
                        } catch (SecurityException e) {}
                    }
                } else {
                    mainHandler.post(() -> {
                        updateStatus("Error: Device is not a BLE Serial module");
                        try { gatt.disconnect(); } catch (SecurityException e) {}
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
                    mainHandler.post(() -> dataText.setText("Received: " + str));
                }
            }
        }
        
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
            if (data != null && data.length > 0) {
                String str = new String(data).trim();
                if (!str.isEmpty()) {
                    mainHandler.post(() -> dataText.setText("Received: " + str));
                }
            }
        }
    };

    private void sendData(String data) {
        if (!isConnected || bluetoothGatt == null || writeCharacteristic == null) return; 

        byte[] bytes = data.getBytes();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt.writeCharacteristic(writeCharacteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                writeCharacteristic.setValue(bytes);
                bluetoothGatt.writeCharacteristic(writeCharacteristic);
            }
            mainHandler.post(() -> updateStatus("Sent: " + data.trim()));
        } catch (SecurityException e) {}
    }

    private void disconnect() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) {}
            bluetoothGatt = null;
        }
        isConnected = false;
        writeCharacteristic = null;
        notifyCharacteristic = null;
        
        updateUI(false);
        updateStatus("Disconnected");
    }


    private void updateUI(boolean connected) {
        btnConnect.setEnabled(!connected && selectedDevice != null);
        btnDisconnect.setEnabled(connected);
        btnScan.setEnabled(!connected);
        btnHome.setEnabled(connected);
        btnOpenClaw.setEnabled(connected);
        btnCloseClaw.setEnabled(connected);
        btnPower.setEnabled(connected);

        System.out.println("connected:" + connected);
        System.out.println("motor:" + isMotorsOn);

        btnPower.setBackgroundColor(connected && isMotorsOn ? getColor(R.color.connected_green) : getColor(R.color.disconnected_red));
        btnPower.setText(connected && isMotorsOn? R.string.powerMotorsOn:R.string.powerMotorsOff);
        isMotorsOn = connected && isMotorsOn;

        btnJoyUp.setEnabled(connected);
        btnJoyDown.setEnabled(connected);
        btnJoyLeft.setEnabled(connected);
        btnJoyRight.setEnabled(connected);
        btnJoyArm2Up.setEnabled(connected);
        btnJoyArm2Down.setEnabled(connected);
    }

    private void updateStatus(String status) {
        statusText.setText("Status: " + status);
        devicesListDialog.setMessage(status);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String getDeviceName(BluetoothDevice device) {
        try {
            if (!hasBluetoothPermission()) return device.getAddress();
            String name = device.getName();
            return (name != null && !name.isEmpty()) ? name : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }
}