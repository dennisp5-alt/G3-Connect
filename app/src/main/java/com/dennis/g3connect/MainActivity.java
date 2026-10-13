package com.dennis.g3connect;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
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
import android.text.InputType;
import android.widget.EditText;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothStatusCodes;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Arrays;
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
    private static final UUID G3_WRITE = UUID.fromString("6e400002-0000-0000-006e-696e65626f74");
    private static final UUID G3_NOTIFY = UUID.fromString("6e400004-0000-0000-006e-696e65626f74");
    private static final UUID NORDIC_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NORDIC_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private final NinebotProbe protocol = new NinebotProbe();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<BluetoothGattDescriptor> subscriptions = new ArrayDeque<>();
    private final ByteArrayOutputStream recvBuffer = new ByteArrayOutputStream();
    private BluetoothGattCharacteristic g3Write, nordicWrite, activeWrite;
    private UUID activeNotification = G3_NOTIFY;
    private int activeWriteType=BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    private boolean notifyReady, g3NotifyReady, nordicNotifyReady;
    private boolean probeActive;
    private int probeAttempt=-1, mtuSize=23, g3Rx, nordicRx;
    private boolean subscribing;
    private String selectedName;
    private int waitingCommand=-1;
    private int waitingIndex=-1;
    private int readStep;
    private final int[] readIndexes = {0x55, 0x62, 0x5F};
    private final int[] readLengths = {2, 4, 2};
    private final Runnable operationTimeout = () -> {
        if(waitingCommand<0)return;
        int failed=waitingCommand;
        waitingCommand=-1;
        record("Timeout waiting for 0x"+Integer.toHexString(failed)+
               "; G3 RX="+g3Rx+" Nordic RX="+nordicRx);
        if(failed==NinebotProbe.PRE_COMM && probeActive) {
            handler.post(this::nextProbeAttempt);
        }else if(failed==NinebotProbe.AUTH) {
            setProtocolStatus("AUTH unanswered: existing key may be wrong or firmware needs reassertion; no pairing attempted.");
        }else {
            setProtocolStatus("Read timed out; G3 may have disconnected.");
        }
    };
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
    private TextView status, details, log, protocolStatus, rideData;
    private EditText pairingKey;
    private Button probeButton, authButton, readButton;
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
        record("G3 Connect v0.2.1 — diagnostic mode");
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

        TextView protTitle = text("G3 PROTOCOL — READ ONLY",13,muted,true);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);
        pp.topMargin=dp(22);root.addView(protTitle,pp);
        protocolStatus=text("Select your G3 to inspect the service, then tap PROBE.",13,fg,false);
        protocolStatus.setBackgroundColor(panel);
        protocolStatus.setPadding(dp(12),dp(12),dp(12),dp(12));
        root.addView(protocolStatus);
        LinearLayout protocolActions=new LinearLayout(this);
        LinearLayout.LayoutParams pa=new LinearLayout.LayoutParams(-1,-2);
        pa.topMargin=dp(8);root.addView(protocolActions,pa);
        probeButton=addButton(protocolActions,"PROBE",this::probeG3);
        readButton=addButton(protocolActions,"READ DATA",this::readG3);
        probeButton.setEnabled(false);
        readButton.setEnabled(false);
        pairingKey=new EditText(this);
        pairingKey.setTextColor(fg);pairingKey.setHintTextColor(muted);
        pairingKey.setSingleLine(true);pairingKey.setTextSize(14);
        pairingKey.setHint("Optional 16-byte pairing key (32 hex characters)");
        pairingKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pairingKey);
        authButton=new Button(this);authButton.setAllCaps(false);authButton.setText("AUTHENTICATE USING EXISTING KEY");
        authButton.setTextSize(12);authButton.setEnabled(false);
        authButton.setOnClickListener(v->authenticateG3());
        root.addView(authButton);
        rideData=text("Battery, mileage and range appear here after successful authentication.",14,fg,false);
        rideData.setPadding(dp(12),dp(12),dp(12),dp(12));rideData.setBackgroundColor(panel);
        root.addView(rideData);

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
        protocol.reset();
        selectedName=name.equals("Unnamed BLE device")?null:name;
        g3Write=null;nordicWrite=null;activeWrite=null;
        probeActive=false;probeAttempt=-1;mtuSize=23;g3Rx=0;nordicRx=0;
        notifyReady=false;g3NotifyReady=false;nordicNotifyReady=false;
        recvBuffer.reset();subscriptions.clear();subscribing=false;
        probeButton.setEnabled(false);authButton.setEnabled(false);readButton.setEnabled(false);
        setProtocolStatus("Connecting BLE; scooter authentication not started.");
        rideData.setText("No scooter telemetry yet.");
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
        handler.removeCallbacks(operationTimeout);
        waitingCommand=-1;
        probeActive=false;
        BluetoothGatt previous=gatt; gatt=null;
        if(previous!=null) {
            try {previous.disconnect();previous.close();} catch(RuntimeException ignored){}
        }
        protocol.reset();notifyReady=false;g3NotifyReady=false;nordicNotifyReady=false;
        g3Write=null;nordicWrite=null;activeWrite=null;subscribing=false;
        if(disconnectButton!=null) disconnectButton.setEnabled(false);
        if(probeButton!=null)probeButton.setEnabled(false);
        if(authButton!=null)authButton.setEnabled(false);
        if(readButton!=null)readButton.setEnabled(false);
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
                protocol.reset();notifyReady=false;g3Write=null;nordicWrite=null;
                probeActive=false;activeWrite=null;subscribing=false;
                handler.removeCallbacks(operationTimeout);waitingCommand=-1;
                runOnUiThread(() -> {
                    probeButton.setEnabled(false);authButton.setEnabled(false);readButton.setEnabled(false);
                    setProtocolStatus("Disconnected");
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
            BluetoothGattService g3service=client.getService(G3_UART);
            g3Write=g3service==null?null:g3service.getCharacteristic(G3_WRITE);
            BluetoothGattService nordicService=client.getService(NORDIC_UART);
            nordicWrite=nordicService==null?null:nordicService.getCharacteristic(NORDIC_WRITE);
            record("Gen3 write present="+(g3Write!=null)+", Nordic write present="+(nordicWrite!=null));
            if(g3Write==null&&nordicWrite==null)
                setProtocolStatus("Selected device has no supported scooter write characteristic");
            try {
                if(!client.requestMtu(247)) {
                    record("MTU request failed to start; try standard notification subscription");
                    subscribe(client);
                }
            } catch (SecurityException e) {record("MTU request permission denied");}
        }
        @Override public void onMtuChanged(BluetoothGatt client,int mtu,int code) {
            if(client!=gatt) return;
            if(code==BluetoothGatt.GATT_SUCCESS)mtuSize=mtu;
            record("Negotiated MTU: "+mtu+" (status "+code+")");
            subscribe(client);
        }
        @Override public void onDescriptorWrite(BluetoothGatt client,BluetoothGattDescriptor d,int code) {
            if(client!=gatt)return;
            if(d.getCharacteristic().getUuid().equals(G3_NOTIFY) && code==BluetoothGatt.GATT_SUCCESS)
                g3NotifyReady=true;
            if(d.getCharacteristic().getUuid().equals(NORDIC_NOTIFY) && code==BluetoothGatt.GATT_SUCCESS)
                nordicNotifyReady=true;
            notifyReady=g3NotifyReady||nordicNotifyReady;
            record("Notify "+d.getCharacteristic().getUuid()+": "+code);
            subscribeNext(client);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt client,
                BluetoothGattCharacteristic characteristic,int statusCode) {
            if(client!=gatt)return;
            if(statusCode!=BluetoothGatt.GATT_SUCCESS) {
                record("Protocol write rejected (GATT status="+statusCode+")");
                int failed=waitingCommand;
                handler.removeCallbacks(operationTimeout);waitingCommand=-1;
                if(failed==NinebotProbe.PRE_COMM && probeActive) handler.post(MainActivity.this::nextProbeAttempt);
                else setProtocolStatus("Bluetooth write failed (status "+statusCode+")");
            }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt client,BluetoothGattCharacteristic ch,byte[] data) {
            if(client==gatt) receive(data,ch.getUuid());
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt client,BluetoothGattCharacteristic ch) {
            if(Build.VERSION.SDK_INT<33 && client==gatt) receive(ch.getValue(),ch.getUuid());
        }
    };
    private void setProtocolStatus(String message) {
        runOnUiThread(()->{ if(protocolStatus!=null)protocolStatus.setText(message); });
    }
    private void subscribe(BluetoothGatt client) {
        if(subscribing)return;
        subscribing=true;subscriptions.clear();
        notifyReady=false;g3NotifyReady=false;nordicNotifyReady=false;
        addNotification(client,G3_UART,G3_NOTIFY);
        addNotification(client,NORDIC_UART,NORDIC_NOTIFY);
        if(subscriptions.isEmpty()) {
            subscribing=false;
            setProtocolStatus("No known G3 or Nordic notification channel found");
            record("Cannot probe without a notification channel");
            return;
        }
        subscribeNext(client);
    }
    private void addNotification(BluetoothGatt client,UUID serviceUuid,UUID notifyUuid) {
        BluetoothGattService service=client.getService(serviceUuid);
        BluetoothGattCharacteristic ch=service==null?null:service.getCharacteristic(notifyUuid);
        if(ch==null || (ch.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0)return;
        BluetoothGattDescriptor descriptor=ch.getDescriptor(CCCD);
        if(descriptor!=null)subscriptions.add(descriptor);
    }
    @SuppressWarnings("deprecation")
    private void subscribeNext(BluetoothGatt client) {
        if(client!=gatt)return;
        BluetoothGattDescriptor d=subscriptions.poll();
        if(d==null) {
            subscribing=false;
            record("Subscription summary: Gen3="+g3NotifyReady+", Nordic="+nordicNotifyReady+", MTU="+mtuSize);
            runOnUiThread(()->{
                probeButton.setEnabled((g3Write!=null && g3NotifyReady) ||
                                       (nordicWrite!=null && nordicNotifyReady));
                setProtocolStatus((g3NotifyReady||nordicNotifyReady)
                    ? "Notification channels ready. Tap PROBE (never changes pairing)."
                    : "Notification setup failed — cannot test encrypted protocol.");
            });
            return;
        }
        try {
            if(!client.setCharacteristicNotification(d.getCharacteristic(),true)) {
                record("Could not enable local notifications");subscribeNext(client);return;
            }
            boolean started;
            if(Build.VERSION.SDK_INT>=33)
                started=client.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS;
            else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                started=client.writeDescriptor(d);
            }
            if(!started) {record("CCCD write failed to start");subscribeNext(client);}
        }catch(SecurityException e) {subscribing=false;record("Bluetooth permission denied subscribing");}
    }
    private void probeG3() {
        if(gatt==null || !notifyReady) {
            setProtocolStatus("No scooter BLE connection yet");return;
        }
        if(selectedName==null||selectedName.isEmpty()) {
            setProtocolStatus("Scooter advertised no name: cannot derive encryption key");return;
        }
        if(waitingCommand>=0 || probeActive)return;
        probeActive=true;probeAttempt=-1;
        g3Rx=0;nordicRx=0;
        authButton.setEnabled(false);readButton.setEnabled(false);
        setProtocolStatus("Trying read-only PRE_COMM on available Bluetooth channels…");
        nextProbeAttempt();
    }
    private void nextProbeAttempt() {
        if(!probeActive || gatt==null)return;
        handler.removeCallbacks(operationTimeout);
        waitingCommand=-1;
        probeAttempt++;
        if(probeAttempt>=4) {
            probeActive=false;
            setProtocolStatus("No PRE_COMM reply after 4 transport attempts. Share LOG; no pairing was modified.");
            record("All four PRE_COMM probes timed out. Name length="+
                (selectedName==null?0:selectedName.length())+
                "; G3 notifications="+g3Rx+" Nordic notifications="+nordicRx+"; MTU="+mtuSize);
            return;
        }
        boolean gen2=probeAttempt>=2;
        boolean withResponse=(probeAttempt%2)==1;
        if(gen2 && (nordicWrite==null||!nordicNotifyReady)) {
            record("Skipping Nordic protocol probe: missing characteristic/subscription");
            probeAttempt=3;nextProbeAttempt();return;
        }
        if(!gen2 && (g3Write==null||!g3NotifyReady)) {
            record("Skipping proprietary G3 probe: missing characteristic/subscription");
            probeAttempt=1;nextProbeAttempt();return;
        }
        protocol.setGeneration(gen2);
        activeWrite=gen2?nordicWrite:g3Write;
        activeNotification=gen2?NORDIC_NOTIFY:G3_NOTIFY;
        activeWriteType=withResponse
            ?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            :BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        recvBuffer.reset();
        try {
            byte[] encrypted=protocol.beginProbe(selectedName);
            String channel=gen2?"Gen2 Nordic":"Gen3 Ninebot";
            record("PRE_COMM attempt "+(probeAttempt+1)+"/4: "+channel+", write "+
                (withResponse?"with response":"without response"));
            setProtocolStatus("Probe "+(probeAttempt+1)+"/4: "+channel+
                    ", "+(withResponse?"acknowledged write":"unacknowledged write"));
            send(encrypted,NinebotProbe.PRE_COMM,0);
        }catch(Exception e) {
            record("Probe setup error: "+e.getClass().getSimpleName());
            nextProbeAttempt();
        }
    }
    private void authenticateG3() {
        if(gatt==null||!protocol.hasChallenge()) {
            setProtocolStatus("PROBE must succeed before authentication");return;
        }
        if(waitingCommand>=0)return;
        try {
            String key=pairingKey.getText().toString().trim();
            // The key is NEVER sent to logs or stored in preferences.
            byte[] frame=protocol.beginAuthentication(key);
            pairingKey.setText("");
            setProtocolStatus("Sending AUTH using existing key. No re-pairing will be attempted.");
            send(frame,NinebotProbe.AUTH,0);
        }catch(Exception e){setProtocolStatus("Authentication not started: "+e.getMessage());}
    }
    private void readG3() {
        if(!protocol.isAuthenticated()){setProtocolStatus("Authenticate first");return;}
        if(waitingCommand>=0)return;
        readStep=0;rideData.setText("Reading live scooter data…");
        readNext();
    }
    private void readNext() {
        if(readStep>=readIndexes.length) {
            setProtocolStatus("Read-only telemetry complete (no configuration changes).");
            return;
        }
        try {
            int idx=readIndexes[readStep];
            send(protocol.readRegister(idx,readLengths[readStep]),NinebotProbe.READ_RESP,idx);
        }catch(Exception e){setProtocolStatus("Read could not start: "+e.getMessage());}
    }
    private void send(byte[] payload,int expected,int index) {
        if(gatt==null||activeWrite==null||!notifyReady) {
            setProtocolStatus("G3 communication channel not ready");return;
        }
        try {
            if(payload.length>mtuSize-3) {
                waitingCommand=-1;
                setProtocolStatus("MTU "+mtuSize+" cannot carry "+payload.length+
                        "-byte packet in one write. No fragmented packets will be sent.");
                record("Packet blocked by MTU: "+payload.length+" bytes vs "+(mtuSize-3)+" max");
                probeActive=false;
                return;
            }
            waitingCommand=expected;waitingIndex=index;
            handler.removeCallbacks(operationTimeout);
            boolean started;
            if(Build.VERSION.SDK_INT>=33) {
                started = gatt.writeCharacteristic(activeWrite,payload,activeWriteType)==BluetoothStatusCodes.SUCCESS;
            }else{
                activeWrite.setWriteType(activeWriteType);
                activeWrite.setValue(payload);
                started=gatt.writeCharacteristic(activeWrite);
            }
            if(!started) {
                waitingCommand=-1;
                record("ATT write could not be initiated");
                if(probeActive)handler.post(this::nextProbeAttempt);
                else setProtocolStatus("BLE write could not start");
                return;
            }
            record("Protocol command 0x"+Integer.toHexString(expected)+
                " queued, "+payload.length+" bytes, "+(activeWriteType==BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT?"ack":"no-ack"));
            handler.postDelayed(operationTimeout,expected==NinebotProbe.PRE_COMM?3200:6000);
        }catch(Exception e) {
            waitingCommand=-1;
            record("Bluetooth write error: "+e.getClass().getSimpleName());
            if(probeActive)handler.post(this::nextProbeAttempt);
            else setProtocolStatus("Bluetooth write error: "+e.getClass().getSimpleName());
        }
    }
    private synchronized void receive(byte[] data,UUID uuid) {
        if(data==null)return;
        if(uuid.equals(G3_NOTIFY))g3Rx++;
        else if(uuid.equals(NORDIC_NOTIFY))nordicRx++;
        else return;
        if(waitingCommand<0||!uuid.equals(activeNotification)) {
            if(g3Rx+nordicRx<=6)record("Notification on "+(uuid.equals(G3_NOTIFY)?"Gen3":"Nordic")+
                                   " ignored outside active probe ("+data.length+" bytes)");
            return;
        }
        if(recvBuffer.size()+data.length>8192)recvBuffer.reset();
        recvBuffer.write(data,0,data.length);
        byte[] bytes=recvBuffer.toByteArray();
        int offset=0;
        while(offset<bytes.length) {
            int start=-1;
            for(int i=offset;i+1<bytes.length;i++)
                if((bytes[i]&255)==0x5A && bytes[i+1]==protocol.expectedSync2()){start=i;break;}
            if(start<0){
                recvBuffer.reset();
                if(bytes.length>0&&(bytes[bytes.length-1]&255)==0x5A)recvBuffer.write(0x5A);
                return;
            }
            if(bytes.length-start<3) {offset=start;break;}
            int size=(bytes[start+2]&255)+13;
            if(size>275){offset=start+1;continue;}
            if(bytes.length-start<size){offset=start;break;}
            byte[] frame=Arrays.copyOfRange(bytes,start,start+size);
            offset=start+size;
            try {
                NinebotProbe.Packet packet=protocol.decrypt(frame);
                if(packet.error!=0){
                    record("Reply could not be decrypted; status="+packet.error);
                    continue;
                }
                NinebotProbe.Reply reply=protocol.parse(packet.plaintext);
                handleReply(reply);
            }catch(Exception e) {
                record("Reply rejected: "+e.getClass().getSimpleName());
            }
        }
        recvBuffer.reset();
        if(offset<bytes.length)recvBuffer.write(bytes,offset,bytes.length-offset);
    }
    private void handleReply(NinebotProbe.Reply r) throws GeneralSecurityException {
        if(r.command!=waitingCommand) {
            record("Received unsolicited command 0x"+Integer.toHexString(r.command));
            return;
        }
        if(r.command==NinebotProbe.READ_RESP && r.index!=waitingIndex) {
            record("Received response for different register");return;
        }
        handler.removeCallbacks(operationTimeout);
        waitingCommand=-1;
        if(r.command==NinebotProbe.PRE_COMM) {
            probeActive=false;
            String result=protocol.onPreComm(r);
            record(result);
            runOnUiThread(()->{
                setProtocolStatus(result+ (protocol.hasStoredPassword()
                    ? ". Enter the EXISTING 16-byte key to authenticate, without re-pairing."
                    : ". No stored key was reported; this diagnostic build will NOT establish a new pairing."));
                authButton.setEnabled(protocol.hasStoredPassword());
            });
        } else if(r.command==NinebotProbe.AUTH) {
            protocol.onAuth(r);
            record("AUTH confirmed by scooter");
            setProtocolStatus("Authenticated successfully — tap READ DATA.");
            runOnUiThread(()->readButton.setEnabled(true));
        } else if(r.command==NinebotProbe.READ_RESP) {
            long value=NinebotProbe.unsignedLE(r.payload);
            int step=readStep;
            String line;
            if(step==0)line="Battery: "+value+"%";
            else if(step==1)line="Total mileage: "+String.format(Locale.US,"%.1f",value/10.0)+" km";
            else line="Remaining range: "+String.format(Locale.US,"%.2f",value/100.0)+" km";
            record("Read response "+line);
            runOnUiThread(()->{
                String old=rideData.getText().toString();
                if(step==0||old.startsWith("Reading"))rideData.setText(line);
                else rideData.append("\n"+line);
            });
            readStep++;
            handler.postDelayed(this::readNext,180);
        }
    }
    private void shareLog() {
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,"G3 Connect diagnostic log");
        String safe=diagnostics.toString().replaceAll(
            "(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}","[BLE ADDRESS REDACTED]");
        if(selectedName!=null && !selectedName.isEmpty())
            safe=safe.replace(selectedName,"[DEVICE NAME REDACTED]");
        if(!protocol.getSerial().isEmpty())
            safe=safe.replace(protocol.getSerial(),"[SERIAL REDACTED]");
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
