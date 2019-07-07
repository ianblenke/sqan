package org.sofwerx.sqandr;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.sdr.SdrManet;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqandr.sdr.AbstractSdr;
import org.sofwerx.sqandr.sdr.DataConnectionListener;
import org.sofwerx.sqandr.sdr.SdrException;
import org.sofwerx.sqandr.sdr.UsbStorage;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.serial.SerialConnection;
import org.sofwerx.sqandr.util.PermissionsHelper;
import org.sofwerx.sqandr.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class SqANDRService implements DataConnectionListener {
    private final static String TAG = Config.TAG+".sqandr";
    private final static String SDR_USB_PERMISSION = "org.sofwerx.sqandr.SDR_USB_PERMISSION";
    private final static long PERIODIC_TASK_INTERVAL = 1000l * 10l;
    private Context context;
    private HandlerThread sdrThread;
    private Handler handler;
    private SqANDRListener listener;
    private BroadcastReceiver usbBroadcastReceiver;
    private BroadcastReceiver permissionBroadcastReceiver;
    private AbstractSdr sdrDevice;
    //private SdrSocket sdrSocket;
    //private SerialListener serialListener;
    private DataConnectionListener dataConnectionListener;
    private SdrManet manet;
    private PeripheralStatusListener peripheralStatusListener;

    public SqANDRService(@NonNull Context context, SdrManet manet) {
        this.context = context;
        if (context instanceof SqANDRListener)
            listener = (SqANDRListener) context;
        else if (manet instanceof SqANDRListener)
            listener = manet;
        this.manet = manet;
        if (context instanceof Activity)
            PermissionsHelper.checkForPermissions((Activity)context);
        //SqANDRLog.init(context);
        //SqANDRLog.log("Starting SqANDRService...");
        CommsLog.log(CommsLog.Entry.Category.SDR,"Starting SqANDRService...");
        sdrThread = new HandlerThread("SqANDRSrvc") {
            @Override
            protected void onLooperPrepared() {
                //SqANDRLog.log("SqANDRService started");
                CommsLog.log(CommsLog.Entry.Category.SDR,"SqANDRService started");
                handler = new Handler(sdrThread.getLooper());
                handler.postDelayed(periodicTask,PERIODIC_TASK_INTERVAL);
                init();
            }
        };
        sdrThread.start();
    }

    public boolean isSdrConnectionRecentlyCongested() {
        if (sdrDevice == null)
            return false;
        return sdrDevice.isSdrConnectionRecentlyCongested();
    }

    private Runnable periodicTask = new Runnable() {
        @Override
        public void run() {
            //TODO handle any periodic tasks here
            if (handler != null)
                handler.postDelayed(periodicTask, PERIODIC_TASK_INTERVAL);
        }
    };

    private void post(Runnable runnable) {
        if ((runnable == null) || (handler == null))
            return;
        handler.post(runnable);
    }

    private void updateUsbDevices(final Context context) {
        post(() -> {
            Log.d(TAG,"Updating USB devices...");
            StringBuilder result = new StringBuilder();
            final UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
            int count = 0;
            if ((deviceList != null) && !deviceList.isEmpty()) {
                Iterator it = deviceList.entrySet().iterator();
                while (it.hasNext()) {
                    count++;
                    Map.Entry<String, UsbDevice> pair = (Map.Entry)it.next();
                    if (pair.getValue() == null)
                        continue;
                    String message = "USB Device available: "+pair.getKey()+", "+pair.getValue().getProductName()+" Manufacturer "+pair.getValue().getVendorId()+" product "+pair.getValue().getProductId()+'\n';
                    result.append(message);
                    result.append('\n');
                    Log.d(TAG,message);
                    if (sdrDevice == null) {
                        sdrDevice = AbstractSdr.newFromVendor(pair.getValue().getVendorId());
                        //if (sdrDevice != null)
                        //    sdrDevice.setSerialListener(this);
                        if (sdrDevice != null) {
                            sdrDevice.setDataConnectionListener(this);
                            sdrDevice.setPeripheralStatusListener(peripheralStatusListener);
                        }
                        final UsbDevice device = pair.getValue();
                        post(() -> setUsbDevice(context,manager,device) );
                    }
                }
            }

            if (count == 0) {
                sdrDevice = null;
                result.append("No USB devices detected");
            } else {
                result.append(count+" USB device"+((count==1)?"":"s")+" detected\r\n");
                if (sdrDevice == null)
                    result.append("but no SDR was found");
                else {
                    result.append(sdrDevice.getClass().getSimpleName() + " was found");
                    getInfo();
                }
            }
            if (listener != null)
                listener.onSdrMessage(result.toString());
        });
    }

    private void init() {
        if (usbBroadcastReceiver == null) {
            usbBroadcastReceiver = new UsbBroadcastReceiver();
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            context.registerReceiver(usbBroadcastReceiver, filter);
        }
        updateUsbDevices(context);
    }

    public void shutdown() {
        peripheralStatusListener = null;
        Log.d(TAG,"Shutting down SqANDRService...");
        //SqANDRLog.log("Shutting down SqANDRService...");
        CommsLog.log(CommsLog.Entry.Category.SDR,"Shutting down SqANDRService...");
        if (usbBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(usbBroadcastReceiver);
                Log.d(TAG,"Unregistered USB Broadcast Receiver");
            } catch (Exception e) {
                Log.e(TAG,"Unable to unregister USB Broadcast Receiver: "+e.getMessage());
            }
        }
        if (permissionBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(permissionBroadcastReceiver);
                Log.d(TAG,"Unregistered Permission Broadcast Receiver");
            } catch (Exception e) {
                Log.e(TAG,"Unable to unregister Permission Broadcast Receiver: "+e.getMessage());
            }
        }
        /*if (sdrSocket != null) {
            try {
                sdrSocket.close();
            } catch (Exception e) {
                Log.e(TAG,"Unable to close SdrSocket: "+e.getMessage());
            }
            sdrSocket = null;
        }*/
        SdrSocket.closeIOThreads();
        if (sdrDevice != null) {
            sdrDevice.shutdown();
            sdrDevice = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            sdrThread.quitSafely();
            handler = null;
            sdrThread = null;
        }
        //SqANDRLog.close();
    }

    public SerialConnection getSerialConnection() {
        if (sdrDevice == null)
            return null;
        return sdrDevice.getSerialConnection();
    }

    public void setListener(SqANDRListener listener) { this.listener = listener; }

    @Override
    public void onConnect() {
        if (manet != null)
            manet.setStatus(Status.CONNECTED);
        if (dataConnectionListener != null)
            dataConnectionListener.onConnect();
    }

    @Override
    public void onDisconnect() {
        //TODO
        if (dataConnectionListener != null)
            dataConnectionListener.onDisconnect();
    }

    @Override
    public void onReceiveDataLinkData(byte[] data) {
        Log.d(TAG,"SqANDRService.onReceiveDataLinkData("+((data==null)?"no ":data.length)+"b)");
        if (listener != null) {
            listener.onPacketReceived(data);
        } else
            Log.d(TAG,"...but ignored as there is no SqANDRService Listener");
        if (dataConnectionListener != null)
            dataConnectionListener.onReceiveDataLinkData(data);
    }

    @Override
    public void onReceiveCommandData(byte[] data) {
        if (dataConnectionListener != null)
            dataConnectionListener.onReceiveCommandData(data);
        //TODO on command received
    }

    @Override
    public void onConnectionError(String message) {
        if (listener != null)
            listener.onSdrError(message);
        if (dataConnectionListener != null)
            dataConnectionListener.onConnectionError(message);
    }

    public void setDataConnectionListener(DataConnectionListener dataConnectionListener) { this.dataConnectionListener = dataConnectionListener; }

    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        peripheralStatusListener = listener;
        if (sdrDevice == null) {
            if (listener != null)
                listener.onPeripheralMessage("SDR not connected");
        } else
            sdrDevice.setPeripheralStatusListener(listener);
    }

    private class UsbBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            StringBuilder sb = new StringBuilder();
            sb.append("Action: " + intent.getAction() + "\n");
            sb.append("URI: " + intent.toUri(Intent.URI_INTENT_SCHEME) + "\n");
            String log = sb.toString();
            Log.d(TAG, log);
            updateUsbDevices(context);
        }
    }

    public void setUsbStorage() {
        if ((sdrDevice != null) && sdrDevice.useMassStorage())
            sdrDevice.setUsbStorage(UsbStorage.getInstance());
    }

    private void setUsbDevice(final Context context, final UsbManager manager, UsbDevice device) {
        if (sdrDevice == null) {
            Log.e(TAG,"Cannot set USB Device as sdrDevice has not yet been assigned.");
            return;
        }
        if (permissionBroadcastReceiver == null) {
            permissionBroadcastReceiver = new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    if (SDR_USB_PERMISSION.equals(intent.getAction())) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                            Log.d(TAG, "Permission granted for device " + device.getDeviceName());
                            try {
                                sdrDevice.setUsbDevice(context, manager, device);
                                //startSocket();
                                if (listener != null) {
                                    CommsLog.log("SDR is ready");
                                    listener.onSdrMessage(sdrDevice.getInfo(context));
                                    listener.onSdrReady(true);
                                }
                            } catch (SdrException e) {
                                String errorMessage = "Unable to set the SDR connection: " + e.getMessage();
                                Log.e(TAG, errorMessage);
                                if (listener != null) {
                                    listener.onSdrError(errorMessage);
                                    listener.onSdrReady(false);
                                }
                            }
                        } else {
                            Log.e(TAG, "Permission denied for device " + device.getDeviceName());
                            Toast.makeText(context, "Permission denied to open SDR at " + device.getDeviceName(), Toast.LENGTH_LONG).show();
                            if (listener != null) {
                                listener.onSdrError("Permission denied for device " + device.getDeviceName());
                                listener.onSdrReady(false);
                            }
                        }
                    }
                    try {
                        context.unregisterReceiver(permissionBroadcastReceiver);
                        Log.d(TAG,"Permission Broadcast Receiver unregistered");
                    } catch (Exception e) {
                        Log.e(TAG,"Unable to unregister the Permission Broadcast Receiver");
                    }
                    permissionBroadcastReceiver = null;
                }
            };

            PendingIntent mPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(SDR_USB_PERMISSION), 0);
            IntentFilter filter = new IntentFilter(SDR_USB_PERMISSION);
            context.registerReceiver(permissionBroadcastReceiver, filter);
            Log.d(TAG,"Permission Broadcast Receiver registered");

            // Fire the request:
            manager.requestPermission(device, mPermissionIntent);
            Log.d(TAG, "Permission request for device " + device.getDeviceName() + " was send. waiting...");
        }
    }

    public void getInfo() {
        if (listener != null) {
            if (sdrDevice == null)
                listener.onSdrMessage("No SDR device attached");
            else
                listener.onSdrMessage(sdrDevice.getInfo(context));
        }
    }

    public void burst(final byte[] data) {
        if (data == null)
            return;
        /*if (data.length > SdrSocket.MAX_PACKET_SIZE) {
            Log.e(TAG,"Error, cannot burstPacket a packet bigger than "+SdrSocket.MAX_PACKET_SIZE+"b over SqANDR - this "+data.length+"b packet is being dropped. Consider segmenting or restructuring.");
            return;
        }*/
        if (handler != null) {
            handler.post(() -> {
                /*if ((sdrSocket == null) || !sdrSocket.isActive()) {
                    Log.d(TAG,"Ignoring burstPacket, SdrSocket not ready");
                    return;
                }
                sdrSocket.write(data);*/
                if (sdrDevice == null) {
                    Log.d(TAG,"Ignoring burstPacket, SDR not ready");
                    return;
                }
                sdrDevice.burst(data);
                Log.d(TAG, "burstPacket(" + data.length + "b)");
            });
        }
    }

    public void onPacketReceived(final byte[] data) {
        if (handler != null) {
            handler.post(() -> {
                if (listener != null)
                    listener.onPacketReceived(data);
            });
        }
    }

    @Override
    public void onPacketDropped() {
        if (handler != null) {
            handler.post(() -> {
                if (listener != null)
                    listener.onPacketDropped();
            });
        }
    }
}
