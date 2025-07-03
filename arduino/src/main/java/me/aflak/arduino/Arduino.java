package me.aflak.arduino;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Created by Omar on 21/05/2017.
 */

public class Arduino implements UsbSerialInterface.UsbReadCallback {
    private Context context;
    private ArduinoListener listener;

    private UsbDeviceConnection connection;
    private UsbSerialDevice serialPort;
    private UsbReceiver usbReceiver;
    private UsbManager usbManager;
    private UsbDevice lastArduinoAttached;

    private int baudRate;
    private boolean isOpened;
    private List<String> vendorIds;
    private List<Byte> bytesReceived;
    private byte delimiter;

    private static final String ACTION_USB_DEVICE_PERMISSION = "me.aflak.arduino.USB_PERMISSION";
    private static final int DEFAULT_BAUD_RATE = 9600;
    private static final byte DEFAULT_DELIMITER = '\n';

    public Arduino(Context context, int baudRate) {
        init(context, baudRate);
    }

    public Arduino(Context context) {
        init(context, DEFAULT_BAUD_RATE);
    }

    private void init(Context context, int baudRate) {
        this.context = context;
        this.usbReceiver = new UsbReceiver();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.baudRate = baudRate;
        this.isOpened = false;
        this.vendorIds = new ArrayList<>();
        this.vendorIds.add("9025");
        this.vendorIds.add("1027");
        this.vendorIds.add("5824");
        this.vendorIds.add("4292");
        this.vendorIds.add("1659");
        this.vendorIds.add("4966");
        this.vendorIds.add("1A86");

        this.bytesReceived = new ArrayList<>();
        this.delimiter = DEFAULT_DELIMITER;
    }

    @SuppressLint("NewApi")
    public void setArduinoListener(ArduinoListener listener) {
        this.listener = listener;

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        intentFilter.addAction(ACTION_USB_DEVICE_PERMISSION);
        
        // Android 13+ kompatible Receiver-Registrierung
        if (Build.VERSION.SDK_INT >= 33) { // TIRAMISU = API 33
            context.registerReceiver(usbReceiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, intentFilter);
        }

        lastArduinoAttached = getAttachedArduino();
        if (lastArduinoAttached != null && listener != null) {
            listener.onArduinoAttached(lastArduinoAttached);
        }
    }

    public void unsetArduinoListener() {
        this.listener = null;
    }

    @SuppressLint("NewApi")
    public void open(UsbDevice device) {
        Log.i("Arduino", "open() called for device: " + device.getDeviceName());
        
        // Prüfe ob Permission bereits erteilt ist
        if (usbManager.hasPermission(device)) {
            Log.i("Arduino", "Permission already granted, proceeding with connection...");
            // Direkt verbinden ohne Permission-Request
            processUsbConnection(device);
            return;
        }
        
        Log.i("Arduino", "No permission yet, requesting permission...");
        
        // Android 12+ kompatible PendingIntent-Flags
        int flags;
        if (Build.VERSION.SDK_INT >= 31) { // S = API 31
            flags = PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
            context, 
            0, 
            new Intent(ACTION_USB_DEVICE_PERMISSION), 
            flags
        );
        
        Log.i("Arduino", "Requesting USB permission for device: " + device.getDeviceName());
        usbManager.requestPermission(device, permissionIntent);
        Log.i("Arduino", "Permission requested - waiting for user response...");
    }

    public void reopen() {
        open(lastArduinoAttached);
    }
    
    private void processUsbConnection(UsbDevice device) {
        Log.i("Arduino", "Processing USB connection for device: " + device.getDeviceName());
        
        if (hasId(String.valueOf(device.getVendorId()))) {
            Log.i("Arduino", "Opening USB connection...");
            connection = usbManager.openDevice(device);
            if (connection != null) {
                Log.i("Arduino", "USB connection established, creating serial device...");
                serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
                if (serialPort != null) {
                    Log.i("Arduino", "Serial device created, attempting to open...");
                    if (serialPort.open()) {
                        Log.i("Arduino", "Serial port opened successfully, configuring...");
                        
                        Log.i("Arduino", "Configuring serial parameters...");
                        serialPort.setBaudRate(baudRate);
                        serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                        serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                        serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                        serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                        
                        Log.i("Arduino", "Registering read callback: " + Arduino.this);
                        serialPort.read(Arduino.this);
                        Log.i("Arduino", "Read callback registered successfully");

                        isOpened = true;
                        Log.i("Arduino", "Serial port configuration complete, calling onArduinoOpened");

                        if (listener != null) {
                            listener.onArduinoOpened();
                        }
                    } else {
                        Log.e("Arduino", "Failed to open serial port");
                    }
                } else {
                    Log.e("Arduino", "Failed to create UsbSerialDevice");
                }
            } else {
                Log.e("Arduino", "Failed to open USB device connection");
            }
        } else {
            Log.w("Arduino", "Device vendor ID not recognized: " + device.getVendorId());
        }
    }

    public void close() {
        if (serialPort != null) {
            serialPort.close();
            serialPort = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }

        isOpened = false;
        
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException e) {
            Log.w("Arduino", "Receiver was not registered: " + e.getMessage());
        }
    }

    public void send(byte[] bytes) {
        if (serialPort != null) {
            serialPort.write(bytes);
        }
    }

    public void setDelimiter(byte delimiter) {
        this.delimiter = delimiter;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    public void addVendorId(String id) {
        vendorIds.add(id);
    }

    private class UsbReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("Arduino", "UsbReceiver.onReceive() called with action: " + intent.getAction());
            UsbDevice device;
            if (intent.getAction() != null) {
                switch (intent.getAction()) {
                    case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                        Log.i("Arduino", "USB_DEVICE_ATTACHED received");
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        Log.i("Arduino", "Device vendor ID: " + device.getVendorId());
                        if (hasId(String.valueOf(device.getVendorId()))) {
                            lastArduinoAttached = device;
                            if (listener != null) {
                                Log.i("Arduino", "Calling onArduinoAttached");
                                listener.onArduinoAttached(device);
                            }
                        } else {
                            Log.w("Arduino", "Device vendor ID not recognized: " + device.getVendorId());
                        }
                        break;
                    case UsbManager.ACTION_USB_DEVICE_DETACHED:
                        Log.i("Arduino", "USB_DEVICE_DETACHED received");
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (hasId(String.valueOf(device.getVendorId()))) {
                            if (listener != null) {
                                listener.onArduinoDetached();
                            }
                        }
                        break;
                    case ACTION_USB_DEVICE_PERMISSION:
                        Log.i("Arduino", "USB_DEVICE_PERMISSION received");
                        boolean permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                        Log.i("Arduino", "Permission granted: " + permissionGranted);
                        if (permissionGranted) {
                            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                            Log.i("Arduino", "Processing permission for device: " + device.getDeviceName());
                            if (hasId(String.valueOf(device.getVendorId()))) {
                                Log.i("Arduino", "Opening USB connection...");
                                connection = usbManager.openDevice(device);
                                if (connection != null) {
                                    Log.i("Arduino", "USB connection established, creating serial device...");
                                    serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
                                    if (serialPort != null) {
                                        Log.i("Arduino", "Serial device created, attempting to open...");
                                        if (serialPort.open()) {
                                            Log.i("Arduino", "Serial port opened successfully, configuring...");
                                            
                                            Log.i("Arduino", "Configuring serial parameters...");
                                            serialPort.setBaudRate(baudRate);
                                            serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                                            serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                                            serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                                            serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                                            
                                            Log.i("Arduino", "Registering read callback: " + Arduino.this);
                                            serialPort.read(Arduino.this);
                                            Log.i("Arduino", "Read callback registered successfully");

                                            isOpened = true;
                                            Log.i("Arduino", "Serial port configuration complete, calling onArduinoOpened");

                                            if (listener != null) {
                                                listener.onArduinoOpened();
                                            }
                                        } else {
                                            Log.e("Arduino", "Failed to open serial port");
                                        }
                                    } else {
                                        Log.e("Arduino", "Failed to create UsbSerialDevice");
                                    }
                                } else {
                                    Log.e("Arduino", "Failed to open USB device connection");
                                }
                            } else {
                                Log.w("Arduino", "Device vendor ID not recognized during permission handling: " + device.getVendorId());
                            }
                        } else {
                            Log.w("Arduino", "USB permission denied");
                            if (listener != null) {
                                listener.onUsbPermissionDenied();
                            }
                        }
                        break;
                    default:
                        Log.w("Arduino", "Unknown intent action: " + intent.getAction());
                }
            } else {
                Log.w("Arduino", "Intent action is null");
            }
        }
    }

    private UsbDevice getAttachedArduino() {
        HashMap<String, UsbDevice> map = usbManager.getDeviceList();
        for (UsbDevice device : map.values()) {
            if (hasId(String.valueOf(device.getVendorId()))) {
                return device;
            }
        }
        return null;
    }

    private List<Integer> indexOf(byte[] bytes, byte b) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == b) {
                idx.add(i);
            }
        }
        return idx;
    }

    private List<Byte> toByteList(byte[] bytes) {
        List<Byte> list = new ArrayList<>();
        for (byte b : bytes) {
            list.add(b);
        }
        return list;
    }

    private byte[] toByteArray(List<Byte> bytes) {
        byte[] array = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            array[i] = bytes.get(i);
        }
        return array;
    }

    @Override
    public void onReceivedData(byte[] bytes) {
        Log.i("Arduino", "************ onReceivedData CALLED! ************");
        Log.i("Arduino", "Raw USB data received: " + Arrays.toString(bytes));
        Log.i("Arduino", "Data length: " + (bytes != null ? bytes.length : 0));
        
        if (bytes.length != 0) {
            List<Integer> idx = indexOf(bytes, delimiter);
            if (idx.isEmpty()) {
                Log.i("Arduino", "No delimiter found, buffering " + bytes.length + " bytes");
                bytesReceived.addAll(toByteList(bytes));
            } else {
                Log.i("Arduino", "Delimiter found at positions: " + idx);
                int offset = 0;
                for (int index : idx) {
                    byte[] tmp = Arrays.copyOfRange(bytes, offset, index);
                    bytesReceived.addAll(toByteList(tmp));
                    Log.i("Arduino", "Calling listener.onArduinoMessage with " + bytesReceived.size() + " bytes");
                    if (listener != null) {
                        listener.onArduinoMessage(toByteArray(bytesReceived));
                    } else {
                        Log.w("Arduino", "Listener is null! Cannot deliver message");
                    }
                    bytesReceived.clear();
                    offset = index + 1;
                }

                if (offset < bytes.length) {
                    byte[] tmp = Arrays.copyOfRange(bytes, offset, bytes.length);
                    Log.i("Arduino", "Buffering remaining " + tmp.length + " bytes after delimiter");
                    bytesReceived.addAll(toByteList(tmp));
                }
            }
        } else {
            Log.w("Arduino", "Received empty data array");
        }
    }

    public boolean isOpened() {
        return isOpened;
    }

    private boolean hasId(String id) {
        Log.i(getClass().getSimpleName(), "Vendor id : " + id);
        for (String vendorId : vendorIds) {
            if (Objects.equals(vendorId, id)) {
                return true;
            }
        }
        return false;
    }
}
