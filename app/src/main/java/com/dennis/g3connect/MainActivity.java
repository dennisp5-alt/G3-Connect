package com.dennis.g3connect;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.location.LocationManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only transport diagnostic. This application deliberately does NOT send
 * scooter authentication or configuration commands.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_BLE = 7;
    private static final UUID G3_UART = UUID.fromString("6e400001-0000-0000-006e-696e65626f74");
    private static final UUID NORDIC_UART = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> addresses = new HashSet<>();
    private final StringBuilder diagnostics = new StringBuilder();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning;
    private boolean scanFailed;
    private int scanCallbacks;
    private final Runnable scanTimeout = this::stopScan;
    private int packetCount;
    private TextView status, details, log;
    private LinearLayout deviceList;
    private Button scanButton, disconnectButton;

    private int dp(float x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setStatusBarColor(Color.rgb(14, 19, 28));
        getWindow().setNavigationBarColor(Color.rgb(14, 19, 28));
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        record("G3 Connect v0.1.2 — diagnostic mode");
        if (adapter == null) setStatus("Bluetooth unavailable");
        systemCheck();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextColor(color); t.setTextSize(size);
        t.setLineSpacing(dp(2), 1f);
        if (bold) t.setTypeface(null, 1);
        return t;
    }
    private Button addButton(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(44, 94, 142)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.rightMargin = dp(4);
        parent.addView(b, p);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private void buildUi() {
        int bg = Color.rgb(14,19,28), panel = Color.rgb(27,35,48);
        int fg = Color.rgb(239,244,250), muted = Color.rgb(168,181,197);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(32));
        scroll.addView(root); setContentView(scroll);

        root.addView(text("G3 CONNECT",25,fg,true));
        root.addView(text("Ninebot MAX G3 • Bluetooth diagnostics",13,muted,false));

        status = text("Disconnected",17,fg,true);
        status.setPadding(dp(14),dp(15),dp(14),dp(15)); status.setBackgroundColor(panel);
        LinearLayout.LayoutParams statusP = new LinearLayout.LayoutParams(-1,-2);
        statusP.topMargin = dp(22); root.addView(status,statusP);

        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams actionP = new LinearLayout.LayoutParams(-1,-2);
        actionP.topMargin = dp(10); root.addView(actions,actionP);
        scanButton = addButton(actions,"SCAN",this::startScan);
        disconnectButton = addButton(actions,"DISCONNECT",this::disconnect);
        disconnectButton.setEnabled(false);
        addButton(actions,"SHARE LOG",this::shareLog);
        LinearLayout tools = new LinearLayout(this);
        LinearLayout.LayoutParams toolsP = new LinearLayout.LayoutParams(-1,-2);
        toolsP.topMargin = dp(6); root.addView(tools,toolsP);
        addButton(tools,"SYSTEM CHECK",this::systemCheck);
        addButton(tools,"PAIRED",this::showBondedDevices);
        addButton(tools,"LOCATION",() -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));

        TextView near = text("NEARBY DEVICES",13,muted,true);
        LinearLayout.LayoutParams nearP = new LinearLayout.LayoutParams(-1,-2);
        nearP.topMargin = dp(24); root.addView(near,nearP);
        deviceList = new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.addView(text("Turn on the scooter and tap Scan. Unnamed BLE devices are common.",13,muted,false));
        root.addView(deviceList);

        TextView title = text("BLUETOOTH CONNECTION",13,muted,true);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-1,-2);
        titleP.topMargin=dp(22); root.addView(title,titleP);
        details = text("No device connected.",13,fg,false);
        details.setPadding(dp(12),dp(12),dp(12),dp(12));
        details.setTextIsSelectable(true); details.setBackgroundColor(panel);
        root.addView(details);

        TextView settings = text("SETTINGS — DISABLED UNTIL VERIFIED",13,muted,true);
        LinearLayout.LayoutParams settingsP = new LinearLayout.LayoutParams(-1,-2);
        settingsP.topMargin=dp(22); root.addView(settings,settingsP);
        TextView preview = text("○ Energy recovery\n○ Kick-start speed\n○ Charge limit\n○ Auto power-off\n○ Sound volume\n\nThe scooter's encrypted login must work before we activate these controls.",14,muted,false);
        preview.setPadding(dp(12),dp(12),dp(12),dp(12)); preview.setBackgroundColor(panel);
        root.addView(preview);

        TextView logTitle = text("LOCAL DIAGNOSTIC LOG",13,muted,true);
        LinearLayout.LayoutParams logP = new LinearLayout.LayoutParams(-1,-2);
        logP.topMargin=dp(24); root.addView(logTitle,logP);
        log = text("",12,muted,false); log.setTextIsSelectable(true); root.addView(log);
    }

    private void setStatus(String message) { runOnUiThread(() -> { if(status!=null) status.setText(message); }); }
    private void record(String message) {
        runOnUiThread(() -> {
            String now = DateFormat.getTimeInstance(DateFormat.MEDIUM,Locale.getDefault()).format(new Date());
            diagnostics.append(now).append("  ").append(message).append('\n');
            if (diagnostics.length() > 24000) diagnostics.delete(0, diagnostics.length()-18000);
            String shown = diagnostics.toString();
            if (shown.length() > 7000) shown = shown.substring(shown.length()-7000);
            if (log!=null) log.setText(shown);
        });
    }
    private boolean locationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        try {
            return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE)
                    != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException e) { return false; }
    }
    private void systemCheck() {
        boolean enabled = false;
        try { enabled = adapter != null && adapter.isEnabled(); }
        catch (SecurityException ignored) {}
        boolean loc = locationEnabled();
        record("System: " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        record("BLE supported=" + getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                + ", Bluetooth on=" + enabled + ", Location on=" + loc);
        record("Permission Nearby scan="
                + (Build.VERSION.SDK_INT < 31 ? "pre-Android-12" : checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED)
                + ", Nearby connect=" +
                (Build.VERSION.SDK_INT < 31 ? "pre-Android-12" : checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)
                + ", Precise location=" + (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED));
        if (!loc) {
            setStatus("Location is OFF — turn it ON for BLE scanning");
            record("Location OFF: open LOCATION button and enable global Location switch");
        }
    }
    private void showBondedDevices() {
        if(adapter == null) {record("Bluetooth unavailable");return;}
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions(), REQUEST_BLE); return;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            record("Paired system Bluetooth devices: " + bonded.size() +
                   " (paired is not the same as G3 authorization)");
            deviceList.removeAllViews();
            addresses.clear();
            for(BluetoothDevice device : bonded) {
                final String address = device.getAddress();
                final String name = device.getName()==null?"Unnamed paired device":device.getName();
                Button b = new Button(this);
                b.setAllCaps(false); b.setTextSize(12);
                b.setText(name + " [PAIRED]\\n" + address);
                b.setOnClickListener(v -> connect(device,name,address));
                deviceList.addView(b);
            }
            if(bonded.isEmpty()) deviceList.addView(text("No system-paired devices. This is normal for many BLE scooters.", 13, Color.LTGRAY, false));
        } catch (SecurityException e) {record("Could not read paired devices: permission denied");}
    }
    private String[] permissions() {
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private boolean granted() {
        for (String p : permissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private void startScan() {
        if (adapter == null) {setStatus("Bluetooth unavailable"); return;}
        if (!granted()) {requestPermissions(permissions(),REQUEST_BLE);return;}
        systemCheck();
        if (!locationEnabled()) {
            record("Scan not started: Location services OFF; use LOCATION button");
            setStatus("Enable Location, then try SCAN");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                record("Turn Bluetooth on, then tap Scan again"); return;
            }
            stopScan();
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {setStatus("BLE scanner unavailable"); return;}
            deviceList.removeAllViews(); addresses.clear(); packetCount=0;
            scanCallbacks=0;scanFailed=false;
            setStatus("Scanning BLE advertisements (25 seconds)…");
            scanning=true; scanButton.setEnabled(false);
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .setLegacy(false)
                .build();
            scanner.startScan(null, settings,scanCallback);
            record("Scan started (25 seconds, unfiltered, legacy+extended BLE; keep screen ON)");
            handler.removeCallbacks(scanTimeout);
            handler.postDelayed(scanTimeout,25000);
        } catch (SecurityException e) {scanning=false;scanButton.setEnabled(true);setStatus("Bluetooth permission denied");record("Bluetooth permission denied");}
          catch (RuntimeException e) {scanning=false;scanButton.setEnabled(true);setStatus("Scan error");record("Scan error: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if(!scanning) return;
            scanCallbacks++;
            BluetoothDevice device=result.getDevice(); if(device==null) return;
            final String address;
            try {address=device.getAddress();} catch(SecurityException e){return;}
            String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();
            final String display=(name==null || name.isEmpty())?"Unnamed BLE device":name;
            int rssi=result.getRssi();
            runOnUiThread(() -> {
                if(!scanning || !addresses.add(address)) return;
                Button b=new Button(MainActivity.this);
                b.setAllCaps(false); b.setTextSize(12);
                b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
                b.setText(display+"  •  "+rssi+" dBm\n"+address);
                b.setMinHeight(dp(60));
                b.setOnClickListener(v->connect(device,display,address));
                deviceList.addView(b);
            });
        }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            record("Received BLE batch of " + results.size() + " packets");
            for (ScanResult result:results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,result);
        }
        @Override public void onScanFailed(int code) {
            scanFailed=true;
            record("BLE scan failed: "+code+" (1=already scanning, 2=registration, 3=internal, 4=unsupported)");
            setStatus("Scan failed ("+code+")");
            runOnUiThread(MainActivity.this::stopScan);
        }
    };
    private void stopScan() {
        if(!scanning) return;
        scanning=false;
        handler.removeCallbacks(scanTimeout);
        try {if(scanner!=null && granted()) scanner.stopScan(scanCallback);}
        catch(RuntimeException e){record("Scan stop: "+e.getClass().getSimpleName());}
        scanButton.setEnabled(true);
        record("Scan finished: "+scanCallbacks+" advertisement callback(s), "+addresses.size()+" unique device(s)");
        if (gatt==null && !scanFailed) {
            if (addresses.isEmpty()) {
                setStatus("No BLE devices found — check Location and scooter advertising");
                record("Zero results: if Location and permissions are ON, check G3 power/Segway connection; test with another BLE scanner");
            } else setStatus("Found "+addresses.size()+" devices — select scooter");
        }
    }
    private void connect(BluetoothDevice device,String name,String address) {
        stopScan(); disconnect();
        details.setText("Device: "+name+"\nAddress: "+address+"\nConnecting…");
        setStatus("Connecting to "+name+"…"); packetCount=0;
        record("GATT connect requested for "+name+" ("+address+")");
        try {
            gatt=device.connectGatt(this,false,callbacks,BluetoothDevice.TRANSPORT_LE);
            disconnectButton.setEnabled(gatt!=null);
            if(gatt==null) setStatus("Connection could not start");
        } catch (SecurityException e) {setStatus("Bluetooth connect denied");record("Permission error");}
          catch (RuntimeException e) {setStatus("Connection failed");record("Connect error: "+e.getClass().getSimpleName());}
    }
    private void disconnect() {
        BluetoothGatt previous=gatt; gatt=null;
        if(previous!=null) {
            try {previous.disconnect();previous.close();} catch(RuntimeException ignored){}
        }
        if(disconnectButton!=null) disconnectButton.setEnabled(false);
        setStatus("Disconnected");
    }
    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt client,int code,int state) {
            if(client!=gatt) return;
            if(code!=BluetoothGatt.GATT_SUCCESS || state==BluetoothProfile.STATE_DISCONNECTED) {
                record("Disconnected, GATT status="+code);
                setStatus("Disconnected (status "+code+")");
                runOnUiThread(() -> {
                    if(gatt==client) gatt=null;
                    disconnectButton.setEnabled(false);
                });
                client.close(); return;
            }
            if(state==BluetoothProfile.STATE_CONNECTED) {
                record("BLE transport connected — scooter NOT authenticated");
                setStatus("BLE connected — not authenticated");
                try {
                    if(!client.discoverServices()) record("Service discovery could not start");
                } catch (SecurityException e) {record("Service discovery permission denied");}
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt client,int statusCode) {
            if(client!=gatt) return;
            if(statusCode!=BluetoothGatt.GATT_SUCCESS) {
                record("Service discovery failed: "+statusCode);return;
            }
            boolean uart=false;
            StringBuilder info=new StringBuilder("BLE GATT connected (not scooter login)\n\nGATT services:\n");
            for(BluetoothGattService svc:client.getServices()) {
                boolean candidate=svc.getUuid().equals(G3_UART)||svc.getUuid().equals(NORDIC_UART);
                uart|=candidate;
                info.append(candidate?"★ ":"• ").append(svc.getUuid()).append('\n');
                for(BluetoothGattCharacteristic ch:svc.getCharacteristics()) {
                    info.append("   ↳ ").append(ch.getUuid())
                        .append("  flags=").append(ch.getProperties()).append('\n');
                }
            }
            info.append("\nKnown UART service: ").append(uart?"FOUND":"not identified");
            info.append("\nScooter authentication: NOT STARTED");
            String result=info.toString();
            runOnUiThread(() -> details.setText(result));
            record("Discovered "+client.getServices().size()+" GATT services; UART="+uart);
            try {
                if(!client.requestMtu(247)) {
                    record("MTU request failed to start; try standard notification subscription");
                    subscribe(client);
                }
            } catch (SecurityException e) {record("MTU request permission denied");}
        }
        @Override public void onMtuChanged(BluetoothGatt client,int mtu,int code) {
            if(client!=gatt) return;
            record("Negotiated MTU: "+mtu+" (status "+code+")");
            subscribe(client);
        }
        @Override public void onDescriptorWrite(BluetoothGatt client,BluetoothGattDescriptor d,int code) {
            if(client==gatt) record("Notify subscription result: "+code);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt client,BluetoothGattCharacteristic ch,byte[] data) {
            if(client==gatt) packet(data);
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt client,BluetoothGattCharacteristic ch) {
            if(Build.VERSION.SDK_INT<33 && client==gatt) packet(ch.getValue());
        }
    };
    private void packet(byte[] data) {
        int count=++packetCount;
        if(count<=5 || count%50==0)
            record("Undecoded BLE notification #"+count+" ("+(data==null?0:data.length)+" bytes)");
    }
    @SuppressWarnings("deprecation")
    private void subscribe(BluetoothGatt client) {
        try {
            for(BluetoothGattService service:client.getServices()) {
                if(!service.getUuid().equals(G3_UART) && !service.getUuid().equals(NORDIC_UART)) continue;
                for(BluetoothGattCharacteristic ch:service.getCharacteristics()) {
                    if((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0) continue;
                    if(!client.setCharacteristicNotification(ch,true)) continue;
                    BluetoothGattDescriptor descriptor=ch.getDescriptor(CCCD);
                    if(descriptor==null) continue;
                    if(Build.VERSION.SDK_INT>=33) {
                        client.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        client.writeDescriptor(descriptor);
                    }
                    record("Notification subscription requested: "+ch.getUuid());
                    return;
                }
            }
            record("No known UART notification characteristic available");
        } catch (SecurityException e) {record("Notification permission denied");}
    }
    private void shareLog() {
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,"G3 Connect diagnostic log");
        String safe=diagnostics.toString().replaceAll(
            "(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}","[BLE ADDRESS REDACTED]");
        send.putExtra(Intent.EXTRA_TEXT,safe);
        startActivity(Intent.createChooser(send,"Share diagnostics"));
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] outcomes) {
        super.onRequestPermissionsResult(request,p,outcomes);
        if(request==REQUEST_BLE) {
            if(granted()) startScan();
            else {record("BLE permissions declined; grant Nearby devices and Precise location in Android App info");setStatus("Bluetooth + Location permissions required");}
        }
    }
    @Override protected void onDestroy() {
        stopScan(); disconnect(); handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
