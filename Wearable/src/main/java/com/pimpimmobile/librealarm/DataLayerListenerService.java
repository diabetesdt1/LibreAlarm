package com.pimpimmobile.librealarm;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.gson.Gson;
import com.pimpimmobile.librealarm.shareddata.PreferencesUtil;
import com.pimpimmobile.librealarm.shareddata.ReadingData;
import com.pimpimmobile.librealarm.shareddata.Status;
import com.pimpimmobile.librealarm.shareddata.Status.Type;
import com.pimpimmobile.librealarm.shareddata.WearableApi;

import java.util.HashMap;

public class DataLayerListenerService extends WearableListenerService {

    private static final String TAG = "LibreAlarmData";

    static public GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        if (libreAlarm.noNFC()) {
            Log.e(TAG, "Device has no NFC - exiting");
            return;
        }
        Log.i(TAG, "create");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
        if (hasBeenRebooted() && PreferencesUtil.getIsStarted(this)) {
            AlarmReceiver.start(this);
            AlarmReceiver.post(this, 120000);
        } else if (System.currentTimeMillis() > AlarmReceiver.getNextCheck(this) &&
                PreferencesUtil.getIsStarted(this)) {
            // startActivity(new Intent(this, WearActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            WearIntentService.startActionDefault(this);
        }
    }

    private boolean hasBeenRebooted() {
        long lastBoot = PreferencesUtil.getLastBoot(this);
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        if (Math.abs(lastBoot - boot) > 1000) {
            PreferencesUtil.setLastBoot(this, boot);
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "destroy");
        super.onDestroy();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        if (libreAlarm.noNFC()) {
            Log.e(TAG, "Device has no NFC - exiting");
            return;
        }
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                // Check the data path
                String path = event.getDataItem().getUri().getPath();
                if (WearableApi.SETTINGS.equals(path)) {
                    HashMap<String, String> newSettings = new HashMap<>();
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    for (String key : dataMap.keySet()) {
                        newSettings.put(key, dataMap.getString(key, null));
                        final String this_value = newSettings.get(key);
                        if (this_value.equals(PreferencesUtil.TRUE_MARKER) || this_value.equals(PreferencesUtil.FALSE_MARKER)) {
                            // its a boolean type
                            PreferencesUtil.putBoolean(this, key, this_value.equals(PreferencesUtil.TRUE_MARKER));
                        } else {
                            PreferencesUtil.putString(this, key, this_value);
                        }
                    }

                    PreferencesUtil.updateBatteryThresholdCache(libreAlarm.getAppContext());

                    WearableApi.sendMessage(mGoogleApiClient, WearableApi.SETTINGS, PreferencesUtil.toString(newSettings), null);

                    sendStatus(mGoogleApiClient);
                }
            }
        }
    }

    private static void sendStatus(GoogleApiClient client) {
        int attempt = PreferencesUtil.getRetries(client.getContext());
        Type type = PreferencesUtil.getCurrentType(client.getContext());
        Status status = new Status(type, attempt, WearActivity.MAX_ATTEMPTS,
                AlarmReceiver.getNextCheck(client.getContext()));
        WearableApi.sendMessage(client, WearableApi.STATUS, new Gson().toJson(status), null);
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (libreAlarm.noNFC()) {
            Log.e(TAG, "Device has no NFC - exiting");
            return;
        }
        handleMessage(mGoogleApiClient, messageEvent);
    }

    public static void handleMessage(GoogleApiClient client, MessageEvent messageEvent) {
        Log.i(TAG, "received message: " + messageEvent.getSourceNodeId() + ", command: " + messageEvent.getPath());
        switch (messageEvent.getPath()) {
            case WearableApi.TRIGGER_GLUCOSE:
                if (JoH.ratelimit("trigger-glucose", 5)) {
                    //  Intent i = new Intent(client.getContext(), WearActivity.class);
                    //  i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    //  client.getContext().startActivity(i);
                    WearIntentService.startActionDefault(client.getContext());
                }

                break;
            case WearableApi.CANCEL_ALARM:
                PreferencesUtil.setCurrentType(client.getContext(), Type.WAITING);
                Intent i = new Intent(client.getContext(), WearActivity.class);
                i.putExtra(WearActivity.EXTRA_CANCEL_ALARM, true);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                client.getContext().startActivity(i);
                sendStatus(client);
                break;

            case WearableApi.STOP:
                PreferencesUtil.setCurrentType(client.getContext(), Type.NOT_RUNNING);
                PreferencesUtil.setIsStarted(client.getContext(), false);
                AlarmReceiver.stop(client.getContext());
                sendStatus(client);
                break;

            case WearableApi.START:
                PreferencesUtil.setCurrentType(client.getContext(), Type.WAITING);
                PreferencesUtil.setIsStarted(client.getContext(), true);
                AlarmReceiver.start(client.getContext());
                AlarmReceiver.post(client.getContext(), 30000);
                sendStatus(client);
                break;

            case WearableApi.GLUCOSE:  // ACK response
                SimpleDatabase database = new SimpleDatabase(client.getContext());
                database.deleteMessage(Long.valueOf(new String(messageEvent.getData())));
                database.close();
                break;

            case WearableApi.GET_UPDATE:
                if (JoH.ratelimit("get-update", 1)) {
                    SimpleDatabase databaseb = new SimpleDatabase(client.getContext());
                    for (ReadingData.TransferObject message : databaseb.getMessages()) {
                        WearableApi.sendMessage(client, WearableApi.GLUCOSE, new Gson().toJson(message), null);
                    }
                    databaseb.close();
                    sendStatus(client);
                }
                break;

            case WearableApi.REBOOT:
                if (JoH.ratelimit("reboot", 30)) {
                    if (RootTools.isHasRoot()) {
                        RootTools.reboot();
                    }
                }
                break;

            case WearableApi.CLEAR_STATS:
                if (JoH.ratelimit("clear_status", 5)) {
                    WearActivity.successes = 0;
                    WearActivity.failures = 0;
                }
                break;
        }
    }

    @Override
    public void onPeerConnected(Node peer) {
        Log.d(TAG, "onPeerConnected: " + peer);
    }

    @Override
    public void onPeerDisconnected(Node peer) {
        Log.d(TAG, "onPeerDisconnected: " + peer);
    }

}
