package com.example.pickupwinder;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;

    private int deviceId, portNum, baudRate;
    private boolean withIoManager;

    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;
    private ControlLines controlLines;

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    private TextView winderStateText;
    private WinderState winderState = WinderState.unknown;
    private TextView windCountText;
    private Integer windCount = 0;
    private int traversePosition = 0;

    private TextView traversePositionText;
    private TextView winderFaceText;
    private TextView maxSpeedText;

    private TextView leftLimitText;
    private TextView rightLimitText;
    private TextView traverseIncrementText;
    private TextView windTargetText;
    private TextView directionText;

    private String jsonData = "";

    private MachineConfig machineConfig = new MachineConfig();
    private WindConfig windConfig = new WindConfig();


    public class MachineConfig{
        private int maxSpeed;
        private int winderFace;
    }

    public class WindConfig{
        private int leftLimit;
        private int rightLimit;
        private int traverseIncrement;
        private int windTarget;
        private int direction;
    }

    private enum WinderCommand {
        start(0),
        pause(1),
        reset(2),
        enterCalibration(3),
        home(4),
        traversePlus(5),
        traverseMinus(6),
        leaveCalibration(7),
        sendWindConfig(8),
        sendMachineConfig(9),
        sendTraversePos(10),
        winderFace(11);

        int value;
        private static Map map = new HashMap<>();
        WinderCommand (int p) {
            value = p;
        }

        static {
            for (WinderCommand command : WinderCommand.values()) {
                map.put(command.value, command);
            }
        }

        public static WinderCommand valueOf(int command) {
            return (WinderCommand) map.get(command);
        }

        public int getValue() {
            return value;
        }
    }

    private enum WinderState{
        ready(0),
        start(1),
        run(2),
        pause(3),
        reset(4),
        calibrate(5),
        home(6),
        idle(7),
        unknown(8);

        int value;
        private static Map map = new HashMap<>();
        WinderState (int p){
            value = p;
        }

        static {
            for (WinderState state : WinderState.values()) {
                map.put(state.value, state);
            }
        }

        public static WinderState valueOf(int state) {
            return (WinderState) map.get(state);
        }

        public int getValue() {
            return value;
        }
    }

    private enum WinderDirection{
        clockwise(0),
        counterclockwise(1);

        int value;
        private static Map map = new HashMap<>();
        WinderDirection (int p){
            value = p;
        }

        static {
            for (WinderDirection state : WinderDirection.values()) {
                map.put(state.value, state);
            }
        }

        public static WinderDirection valueOf(int state) {
            return (WinderDirection) map.get(state);
        }

        public int getValue() {
            return value;
        }
    }
    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));

        if(usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
            mainLooper.post(this::connect);
    }

    @Override
    public void onPause() {
        if(connected) {
            status("disconnected");
            disconnect();
        }
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        winderStateText = view.findViewById(R.id.winder_state);
        //winderStateText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        winderStateText.setText(getString(R.string.winder_state) + winderState);

        windCountText = view.findViewById(R.id.wind_count);
        //windCountText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        windCountText.setText(getString(R.string.wind_count) + windCount);

        View startBtn = view.findViewById(R.id.start_btn);
        startBtn.setOnClickListener(v -> sendCommand(WinderCommand.start));
        View pauseBtn = view.findViewById(R.id.pause_btn);
        pauseBtn.setOnClickListener(v -> sendCommand(WinderCommand.pause));
        View resetBtn = view.findViewById(R.id.reset_btn);
        resetBtn.setOnClickListener(v -> sendCommand(WinderCommand.reset));
        View homeBtn = view.findViewById(R.id.home_btn);
        homeBtn.setOnClickListener(v -> sendCommand(WinderCommand.home));

        View machineSettingsBtn = view.findViewById(R.id.machine_settings_btn);
        machineSettingsBtn.setOnClickListener(v -> machineSettings(view));
        View windSettingsBtn = view.findViewById(R.id.wind_settings_btn);
        windSettingsBtn.setOnClickListener(v -> windSettings(view));

        traversePositionText = view.findViewById(R.id.traverse_position);
        traversePositionText.setText(getString(R.string.traverse_position) + traversePosition);
        winderFaceText = view.findViewById(R.id.winder_face);
        winderFaceText.setText(getString(R.string.winder_face) + machineConfig.winderFace);
        maxSpeedText = view.findViewById(R.id.max_speed);
        maxSpeedText.setText(getString(R.string.max_speed) + machineConfig.maxSpeed);

        leftLimitText = view.findViewById(R.id.left_limit);
        leftLimitText.setText(getString(R.string.left_limit) + windConfig.leftLimit);
        rightLimitText = view.findViewById(R.id.right_limit);
        rightLimitText.setText(getString(R.string.right_limit) + windConfig.rightLimit);
        traverseIncrementText = view.findViewById(R.id.traverse_increment);
        traverseIncrementText.setText(getString(R.string.traverse_increment) + windConfig.traverseIncrement);
        windTargetText = view.findViewById(R.id.wind_target);
        windTargetText.setText(getString(R.string.wind_target) + windConfig.windTarget);
        directionText = view.findViewById(R.id.direction);
        directionText.setText(getString(R.string.direction) + WinderDirection.valueOf(windConfig.direction));

        View settingsDoneBtn = view.findViewById(R.id.settings_done_btn);
        settingsDoneBtn.setOnClickListener(v -> settingsDone(view));

        View consoleBtn = view.findViewById(R.id.console_btn);
        consoleBtn.setOnClickListener(v -> toggleConsole(view));

        TextView sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        View receiveBtn = view.findViewById(R.id.receive_btn);
        controlLines = new ControlLines(view);
        if(withIoManager) {
            receiveBtn.setVisibility(View.GONE);
        } else {
            receiveBtn.setOnClickListener(v -> read());
        }
        return view;
    }


    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if( id == R.id.send_break) {
            if(!connected) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    usbSerialPort.setBreak(true);
                    Thread.sleep(100); // should show progress bar instead of blocking UI thread
                    usbSerialPort.setBreak(false);
                    SpannableStringBuilder spn = new SpannableStringBuilder();
                    spn.append("send <break>\n");
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                } catch(UnsupportedOperationException ignored) {
                    Toast.makeText(getActivity(), "BREAK not supported", Toast.LENGTH_SHORT).show();
                } catch(Exception e) {
                    Toast.makeText(getActivity(), "BREAK failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial
     */
    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> {
            receive(data);
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    /*
     * Serial + UI
     */
    private void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            if(withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status("connected");
            connected = true;
            controlLines.start();
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        controlLines.stop();
        if(usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    private void sendCommand(WinderCommand command){
        send("{'command':" + command.getValue() + "}");
    }

    private void settingsDone(View view){
        sendCommand(WinderCommand.leaveCalibration);
        LinearLayout settingsLayout = view.findViewById(R.id.settings_layout);
        settingsLayout.setVisibility(View.GONE);
        view.findViewById(R.id.winder_controls_layout).setVisibility(View.VISIBLE);

    }

    private void machineSettings(View view){
        sendCommand(WinderCommand.enterCalibration);
        sendCommand(WinderCommand.sendMachineConfig);
        sendCommand(WinderCommand.sendWindConfig);
        sendCommand(WinderCommand.sendTraversePos);
        view.findViewById(R.id.settings_layout).setVisibility(View.VISIBLE);
        view.findViewById(R.id.machine_settings_layout).setVisibility(View.VISIBLE);
        view.findViewById(R.id.wind_settings_layout).setVisibility(View.GONE);
        view.findViewById(R.id.winder_controls_layout).setVisibility(View.GONE);

    }

    private void windSettings(View view){
        sendCommand(WinderCommand.enterCalibration);
        sendCommand(WinderCommand.sendMachineConfig);
        sendCommand(WinderCommand.sendWindConfig);
        sendCommand(WinderCommand.sendTraversePos);
        view.findViewById(R.id.settings_layout).setVisibility(View.VISIBLE);
        view.findViewById(R.id.machine_settings_layout).setVisibility(View.GONE);
        view.findViewById(R.id.wind_settings_layout).setVisibility(View.VISIBLE);
        view.findViewById(R.id.winder_controls_layout).setVisibility(View.GONE);
    }
    private void toggleConsole(View view) {
        LinearLayout consoleLayout = view.findViewById(R.id.console_layout);
        int vis = consoleLayout.getVisibility();
        consoleLayout.setVisibility(vis == View.GONE? View.VISIBLE : View.GONE);
    }


    private void send(String str) {
        if(!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            //byte[] data = (str + '\n').getBytes();
            byte[] data = (str).getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("send " + data.length + " bytes\n");
            spn.append(HexDump.dumpHexString(data)).append("\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void read() {
        if(!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                receive(Arrays.copyOf(buffer, len));
            }
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
            status("connection lost: " + e.getMessage());
            disconnect();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private void receive(byte[] data) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
//        spn.append("receive " + data.length + " bytes\n");
        String receivedStuff = new String(data, StandardCharsets.UTF_8);
        if(data.length > 0) {
            //spn.append(HexDump.dumpHexString(data)).append("\n");
            //spn.append(receivedStuff + '\n');
            for(int i = 0; i < receivedStuff.length(); i++){
                if(data[i] == '\n'){
                    spn.append(jsonData + '\n');
                    try {
                        JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                        if(json.has("winderState")){
                            int state = json.get("winderState").getAsInt();
                            winderState = WinderState.valueOf(state);
                            spn.append("Info: winder state: " + winderState + '\n');
                            winderStateText.setText(getString(R.string.winder_state) + winderState);
                        }else if(json.has("count")){
                            windCount = json.get("count").getAsInt();
                            windCountText.setText(getString(R.string.wind_count) + windCount);
                        }else if(json.has("debug")){
                            ;
                        }else if(json.has("leftLimit")){
                            Gson gson = new Gson();
                            windConfig = gson.fromJson(json, WindConfig.class);
                            leftLimitText.setText(getString(R.string.left_limit) + windConfig.leftLimit);
                            rightLimitText.setText(getString(R.string.right_limit) + windConfig.rightLimit);
                            traverseIncrementText.setText(getString(R.string.traverse_increment) + windConfig.traverseIncrement);
                            windTargetText.setText(getString(R.string.wind_target) + windConfig.windTarget);
                            directionText.setText(getString(R.string.direction) + WinderDirection.valueOf(windConfig.direction));
                        }else if(json.has("maxSpeed")){
                            Gson gson = new Gson();
                            machineConfig = gson.fromJson(json, MachineConfig.class);
                            winderFaceText.setText(getString(R.string.winder_face) + machineConfig.winderFace);
                            maxSpeedText.setText(getString(R.string.max_speed) + machineConfig.maxSpeed);
                        }else if(json.has("traversePosition")){
                            traversePosition = json.get("traversePosition").getAsInt();
                            traversePositionText.setText(getString(R.string.traverse_position) + traversePosition);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    jsonData = "";
                }else {
                    jsonData += receivedStuff.charAt(i);
                }
            }
        }
        receiveText.append(spn);
    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;
        private final ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        ControlLines(View view) {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks

            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (!connected) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + "() failed: " + e.getMessage());
            }
        }

        private void run() {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getControlLines();
                rtsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RTS));
                ctsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CTS));
                dtrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DTR));
                dsrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DSR));
                cdBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CD));
                riBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RI));
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        void start() {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getSupportedControlLines();
                if (!controlLines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CD))   cdBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.RI))   riBtn.setVisibility(View.INVISIBLE);
                run();
            } catch (IOException e) {
                Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }
    }
}
