/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;
import androidx.core.content.ContextCompat;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.tgnet.tl.TL_update;
import it.belloworld.mercurygram.MgLiveLocationPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

@SuppressLint("MissingPermission")
public class LocationController extends BaseController implements NotificationCenter.NotificationCenterDelegate, ILocationServiceProvider.IAPIConnectionCallbacks, ILocationServiceProvider.IAPIOnConnectionFailedListener {

    private LongSparseArray<SharingLocationInfo> sharingLocationsMap = new LongSparseArray<>();
    private ArrayList<SharingLocationInfo> sharingLocations = new ArrayList<>();
    public LongSparseArray<ArrayList<TLRPC.Message>> locationsCache = new LongSparseArray<>();
    private LongSparseArray<Integer> lastReadLocationTime = new LongSparseArray<>();
    private LocationManager locationManager;
    private GpsLocationListener gpsLocationListener = new GpsLocationListener();
    private GpsLocationListener networkLocationListener = new GpsLocationListener();
    private GpsLocationListener passiveLocationListener = new GpsLocationListener();
    private FusedLocationListener fusedLocationListener = new FusedLocationListener();
    private Location lastKnownLocation;
    private long lastLocationSendTime;
    private boolean locationSentSinceLastMapUpdate = true;
    private long lastLocationStartTime;
    private boolean started;
    private boolean lastLocationByMaps;
    private SparseIntArray requests = new SparseIntArray();
    private LongSparseArray<Boolean> cacheRequests = new LongSparseArray<>();
    private long locationEndWatchTime;
    private final MgLiveLocationPolicy mgLiveLoc = new MgLiveLocationPolicy();
    private String pendingGpsTrigger;

    public ArrayList<SharingLocationInfo> sharingLocationsUI = new ArrayList<>();
    private LongSparseArray<SharingLocationInfo> sharingLocationsMapUI = new LongSparseArray<>();

    private Boolean servicesAvailable;
    private boolean wasConnectedToPlayServices;
    private ILocationServiceProvider.IMapApiClient apiClient;
    private final static int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    private final static long UPDATE_INTERVAL = 1000, FASTEST_INTERVAL = 1000;
    private final static int BACKGROUD_UPDATE_TIME = 30 * 1000;
    private final static int LOCATION_ACQUIRE_TIME = 10 * 1000;
    private final static int FOREGROUND_UPDATE_TIME = 20 * 1000;
    private final static int WATCH_LOCATION_TIMEOUT = 65 * 1000;
    private final static int SEND_NEW_LOCATION_TIME = 2 * 1000;

    private ILocationServiceProvider.ILocationRequest locationRequest;

    private static volatile LocationController[] Instance = new LocationController[UserConfig.MAX_ACCOUNT_COUNT];

    public static LocationController getInstance(int num) {
        LocationController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (LocationController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new LocationController(num);
                }
            }
        }
        return localInstance;
    }

    public static class SharingLocationInfo {
        public long did;
        public int mid;
        public int stopTime;
        public int period;
        public int account;
        public int proximityMeters;
        public int lastSentProximityMeters;
        public MessageObject messageObject;
    }

    private class GpsLocationListener implements LocationListener {

        @Override
        public void onLocationChanged(Location location) {
            if (location == null) {
                return;
            }
            String src = this == gpsLocationListener ? "gps" : this == networkLocationListener ? "network" : "passive";
            LiveLocationDebug.log("nativeFix src=" + src + " started=" + started + " " + LiveLocationDebug.fixSummary(location));
            if (lastKnownLocation != null && (this == networkLocationListener || this == passiveLocationListener)) {
                if (!started && location.distanceTo(lastKnownLocation) > 20) {
                    setLastKnownLocation(location);
                    lastLocationSendTime = SystemClock.elapsedRealtime() - BACKGROUD_UPDATE_TIME + 5000;
                }
            } else {
                setLastKnownLocation(location);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }

        @Override
        public void onProviderEnabled(String provider) {

        }

        @Override
        public void onProviderDisabled(String provider) {

        }
    }

    private class FusedLocationListener implements ILocationServiceProvider.ILocationListener {

        @Override
        public void onLocationChanged(Location location) {
            if (location == null) {
                return;
            }
            setLastKnownLocation(location);
        }
    }

    public LocationController(int instance) {
        super(instance);

        locationManager = (LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
        apiClient = ApplicationLoader.getLocationServiceProvider().onCreateLocationServicesAPI(ApplicationLoader.applicationContext, this, this);

        locationRequest = ApplicationLoader.getLocationServiceProvider().onCreateLocationRequest();
        locationRequest.setPriority(ILocationServiceProvider.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(UPDATE_INTERVAL);
        locationRequest.setFastestInterval(FASTEST_INTERVAL);

        AndroidUtilities.runOnUIThread(() -> {
            LocationController locationController = getAccountInstance().getLocationController();
            getNotificationCenter().addObserver(locationController, NotificationCenter.didReceiveNewMessages);
            getNotificationCenter().addObserver(locationController, NotificationCenter.messagesDeleted);
            getNotificationCenter().addObserver(locationController, NotificationCenter.replaceMessagesObjects);
        });
        loadSharingLocations();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            boolean scheduled = (Boolean) args[2];
            if (scheduled) {
                return;
            }
            long did = (Long) args[0];
            ArrayList<MessageObject> arr = (ArrayList<MessageObject>) args[1];
            it.belloworld.mercurygram.MgIncomingLiveLocationStore.onNewMessages(currentAccount, did, arr);
            if (!isSharingLocation(did)) {
                return;
            }
            ArrayList<TLRPC.Message> messages = locationsCache.get(did);
            if (messages == null) {
                return;
            }
            boolean added = false;
            for (int a = 0; a < arr.size(); a++) {
                MessageObject messageObject = arr.get(a);
                if (messageObject.isLiveLocation()) {
                    added = true;
                    boolean replaced = false;
                    for (int b = 0; b < messages.size(); b++) {
                        if (MessageObject.getFromChatId(messages.get(b)) == messageObject.getFromChatId()) {
                            replaced = true;
                            messages.set(b, messageObject.messageOwner);
                            break;
                        }
                    }
                    if (!replaced) {
                        messages.add(messageObject.messageOwner);
                    }
                } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionGeoProximityReached) {
                    long dialogId = messageObject.getDialogId();
                    if (DialogObject.isUserDialog(dialogId)) {
                        setProximityLocation(dialogId, 0, false);
                    }
                }
            }
            if (added) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsCacheChanged, did, currentAccount);
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            boolean scheduled = (Boolean) args[2];
            if (scheduled) {
                return;
            }
            if (!sharingLocationsUI.isEmpty()) {
                ArrayList<Integer> markAsDeletedMessages = (ArrayList<Integer>) args[0];
                long channelId = (Long) args[1];
                ArrayList<Long> toRemove = null;
                for (int a = 0; a < sharingLocationsUI.size(); a++) {
                    SharingLocationInfo info = sharingLocationsUI.get(a);
                    long messageChannelId = info.messageObject != null ? info.messageObject.getChannelId() : 0;
                    if (channelId != messageChannelId) {
                        continue;
                    }
                    if (markAsDeletedMessages.contains(info.mid)) {
                        if (toRemove == null) {
                            toRemove = new ArrayList<>();
                        }
                        toRemove.add(info.did);
                    }
                }
                if (toRemove != null) {
                    for (int a = 0; a < toRemove.size(); a++) {
                        removeSharingLocation(toRemove.get(a));
                    }
                }
            }
        } else if (id == NotificationCenter.replaceMessagesObjects) {
            long did = (long) args[0];
            if (!isSharingLocation(did)) {
                return;
            }
            ArrayList<TLRPC.Message> messages = locationsCache.get(did);
            if (messages == null) {
                return;
            }
            boolean updated = false;
            ArrayList<MessageObject> messageObjects = (ArrayList<MessageObject>) args[1];
            for (int a = 0; a < messageObjects.size(); a++) {
                MessageObject messageObject = messageObjects.get(a);
                for (int b = 0; b < messages.size(); b++) {
                    if (MessageObject.getFromChatId(messages.get(b)) == messageObject.getFromChatId()) {
                        if (!messageObject.isLiveLocation()) {
                            messages.remove(b);
                        } else {
                            messages.set(b, messageObject.messageOwner);
                        }
                        updated = true;
                        break;
                    }
                }
            }
            if (updated) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsCacheChanged, did, currentAccount);
            }
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        wasConnectedToPlayServices = true;
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                ApplicationLoader.getLocationServiceProvider().checkLocationSettings(locationRequest, status -> {
                    switch (status) {
                        case ILocationServiceProvider.STATUS_SUCCESS:
                            startFusedLocationRequest(true);
                            break;
                        case ILocationServiceProvider.STATUS_RESOLUTION_REQUIRED:
                            Utilities.stageQueue.postRunnable(() -> {
                                if (!sharingLocations.isEmpty()) {
                                    AndroidUtilities.runOnUIThread(() -> getNotificationCenter().postNotificationName(NotificationCenter.needShowPlayServicesAlert, status));
                                }
                            });
                            break;
                        case ILocationServiceProvider.STATUS_SETTINGS_CHANGE_UNAVAILABLE:
                            Utilities.stageQueue.postRunnable(() -> {
                                servicesAvailable = false;
                                try {
                                    apiClient.disconnect();
                                    start();
                                } catch (Throwable ignore) {}
                            });
                            break;
                    }
                });
            } else {
                startFusedLocationRequest(true);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void startFusedLocationRequest(boolean permissionsGranted) {
        Utilities.stageQueue.postRunnable(() -> {
            if (!permissionsGranted) {
                servicesAvailable = false;
            }
            if (!sharingLocations.isEmpty()) {
                if (permissionsGranted) {
                    try {
                        ApplicationLoader.getLocationServiceProvider().getLastLocation(this::setLastKnownLocation);
                        ApplicationLoader.getLocationServiceProvider().requestLocationUpdates(locationRequest, fusedLocationListener);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                } else {
                    start();
                }
            }
        });
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed() {
        if (wasConnectedToPlayServices) {
            return;
        }
        servicesAvailable = false;
        if (started) {
            started = false;
            start();
        }
    }

    private boolean checkServices() {
        if (servicesAvailable == null) {
            servicesAvailable = ApplicationLoader.getLocationServiceProvider().checkServices();
        }
        return servicesAvailable;
    }

    private void broadcastLastKnownLocation(boolean cancelCurrent) {
        if (lastKnownLocation == null) {
            return;
        }
        if (requests.size() != 0) {
            if (cancelCurrent) {
                for (int a = 0; a < requests.size(); a++) {
                    getConnectionsManager().cancelRequest(requests.keyAt(a), false);
                }
            }
            requests.clear();
        }
        if (!sharingLocations.isEmpty()) {
            int date = getConnectionsManager().getCurrentTime();
            float[] result = new float[1];
            for (int a = 0; a < sharingLocations.size(); a++) {
                final SharingLocationInfo info = sharingLocations.get(a);
                if (info.messageObject.messageOwner.media != null && info.messageObject.messageOwner.media.geo != null && info.lastSentProximityMeters == info.proximityMeters) {
                    int messageDate = info.messageObject.messageOwner.edit_date != 0 ? info.messageObject.messageOwner.edit_date : info.messageObject.messageOwner.date;
                    TLRPC.GeoPoint point = info.messageObject.messageOwner.media.geo;
                    if (Math.abs(date - messageDate) < 10) {
                        Location.distanceBetween(point.lat, point._long, lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude(), result);
                        if (result[0] < 1.0f) {
                            continue;
                        }
                    }
                }
                TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
                req.peer = getMessagesController().getInputPeer(info.did);
                req.id = info.mid;
                req.flags |= 16384;
                req.media = new TLRPC.TL_inputMediaGeoLive();
                req.media.stopped = false;
                req.media.geo_point = new TLRPC.TL_inputGeoPoint();
                req.media.geo_point.lat = AndroidUtilities.fixLocationCoord(lastKnownLocation.getLatitude());
                req.media.geo_point._long = AndroidUtilities.fixLocationCoord(lastKnownLocation.getLongitude());
                req.media.geo_point.accuracy_radius = (int) lastKnownLocation.getAccuracy();
                if (req.media.geo_point.accuracy_radius != 0) {
                    req.media.geo_point.flags |= 1;
                }
                if (info.lastSentProximityMeters != info.proximityMeters) {
                    req.media.proximity_notification_radius = info.proximityMeters;
                    req.media.flags |= 8;
                }
                req.media.heading = getHeading(lastKnownLocation);
                req.media.flags |= 4;
                final int[] reqId = new int[1];
                reqId[0] = getConnectionsManager().sendRequest(req, (response, error) -> {
                    if (error != null) {
                        if (error.text.equals("MESSAGE_ID_INVALID")) {
                            sharingLocations.remove(info);
                            sharingLocationsMap.remove(info.did);
                            saveSharingLocation(info, 1);
                            requests.delete(reqId[0]);
                            AndroidUtilities.runOnUIThread(() -> {
                                sharingLocationsUI.remove(info);
                                sharingLocationsMapUI.remove(info.did);
                                if (sharingLocationsUI.isEmpty()) {
                                    stopService();
                                }
                                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
                            });
                        }
                        return;
                    }
                    if ((req.flags & 8) != 0) {
                        info.lastSentProximityMeters = req.media.proximity_notification_radius;
                    }
                    TLRPC.Updates updates = (TLRPC.Updates) response;
                    boolean updated = false;
                    for (int a1 = 0; a1 < updates.updates.size(); a1++) {
                        TLRPC.Update update = updates.updates.get(a1);
                        if (update instanceof TL_update.TL_updateEditMessage) {
                            updated = true;
                            info.messageObject.messageOwner = ((TL_update.TL_updateEditMessage) update).message;
                        } else if (update instanceof TL_update.TL_updateEditChannelMessage) {
                            updated = true;
                            info.messageObject.messageOwner = ((TL_update.TL_updateEditChannelMessage) update).message;
                        }
                    }
                    if (updated) {
                        saveSharingLocation(info, 0);
                    }
                    getMessagesController().processUpdates(updates, false);
                });
                requests.put(reqId[0], 0);
            }
        }
        getConnectionsManager().resumeNetworkMaybe();
    }

    private boolean shouldStopGps() {
        return mgLiveLoc.shouldStopGps(!sharingLocations.isEmpty(), started);
    }

    private void refreshLiveLocSharePeriod() {
        int minRemaining = 0;
        int now = getConnectionsManager().getCurrentTime();
        for (int a = 0; a < sharingLocations.size(); a++) {
            SharingLocationInfo info = sharingLocations.get(a);
            int remaining;
            if (info.stopTime == Integer.MAX_VALUE || info.period == 0x7FFFFFFF) {
                remaining = 0x7FFFFFFF;
            } else {
                remaining = Math.max(0, info.stopTime - now);
            }
            if (remaining <= 0) {
                continue;
            }
            if (minRemaining == 0 || remaining < minRemaining) {
                minRemaining = remaining;
            }
        }
        mgLiveLoc.setMinShareRemainingSec(minRemaining);
    }

    public void onGeoLiveViewed(TLRPC.Peer peer, int messageId) {
        long peerDialogId = peer != null ? DialogObject.getPeerDialogId(peer) : 0;
        long shareDialogId = 0;
        boolean midMatched = false;
        if (peer != null) {
            for (int a = 0; a < sharingLocations.size(); a++) {
                SharingLocationInfo info = sharingLocations.get(a);
                if (info.mid != messageId) {
                    continue;
                }
                midMatched = true;
                if (info.did == peerDialogId) {
                    shareDialogId = info.did;
                    break;
                }
                // Docs may send the viewer user as peer while the share lives in a group.
                if (shareDialogId == 0) {
                    shareDialogId = info.did;
                }
            }
            if (shareDialogId == 0) {
                shareDialogId = peerDialogId;
            }
            it.belloworld.mercurygram.MgLiveLocationViewerLog.logGeoLiveViewed(
                    currentAccount, peer, messageId, shareDialogId);
        }
        LiveLocationDebug.log("onGeoLiveViewed peer=" + peerDialogId
                + " msg=" + messageId
                + " shareDid=" + shareDialogId
                + " midMatched=" + midMatched
                + " shares=" + sharingLocations.size()
                + " started=" + started
                + " boost=" + SharedConfig.mg_liveLocViewerBoost
                + " batteryBlock=" + mgLiveLoc.viewerBoostBlockedByBatterySaver()
                + " alwaysOff=" + mgLiveLoc.alwaysOff()
                + " alwaysOn=" + mgLiveLoc.alwaysOn()
                + " fgs=" + (ApplicationLoader.applicationContext != null));
        setNewLocationEndWatchTime();
        // processUpdateArray runs on stageQueue; DEBUG_VERSION throws if we notify off-main.
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged));
    }

    private static final int[] mgIncomingLiveCountByAccount = new int[UserConfig.MAX_ACCOUNT_COUNT];

    public static int getIncomingLiveLocationsCount() {
        int total = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            total += mgIncomingLiveCountByAccount[a];
        }
        return total;
    }

    public static int getLiveLocationBannerCount() {
        return getLocationsCount() + getIncomingLiveLocationsCount();
    }

    public static void setIncomingLiveCount(int account, int count) {
        if (account >= 0 && account < mgIncomingLiveCountByAccount.length) {
            mgIncomingLiveCountByAccount[account] = count;
        }
    }

    public static void refreshIncomingLiveLocationsCount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                it.belloworld.mercurygram.MgIncomingLiveLocationStore.ensureScan(a, null);
                mgIncomingLiveCountByAccount[a] = it.belloworld.mercurygram.MgIncomingLiveLocationStore.getForAccount(a).size();
            } else {
                mgIncomingLiveCountByAccount[a] = 0;
            }
        }
    }

    public void loadAllIncomingLiveLocations(Utilities.Callback<ArrayList<it.belloworld.mercurygram.MgIncomingLiveLocation>> callback) {
        ArrayList<it.belloworld.mercurygram.MgIncomingLiveLocation> cached = it.belloworld.mercurygram.MgIncomingLiveLocationStore.getForAccount(currentAccount);
        mgIncomingLiveCountByAccount[currentAccount] = cached.size();
        if (callback != null) {
            callback.run(cached);
        }
        it.belloworld.mercurygram.MgIncomingLiveLocationStore.ensureScan(currentAccount, percent -> {
            mgIncomingLiveCountByAccount[currentAccount] = it.belloworld.mercurygram.MgIncomingLiveLocationStore.getForAccount(currentAccount).size();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
        });
    }

    private void collectIncomingLiveLocationsForDialog(long did, long selfId, ArrayList<it.belloworld.mercurygram.MgIncomingLiveLocation> out) {
        SQLiteCursor cursor = null;
        try {
            int now = getConnectionsManager().getCurrentTime();
            HashMap<Long, TLRPC.Message> bySender = new HashMap<>();
            cursor = getMessagesStorage().getDatabase().queryFinalized(
                    "SELECT data FROM messages_v2 WHERE uid = ? AND media = -1 ORDER BY date DESC", did);
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                data.reuse();
                if (!isActiveLiveLocation(message, now)) {
                    continue;
                }
                long fromId = MessageObject.getFromChatId(message);
                if (fromId == selfId) {
                    continue;
                }
                TLRPC.Message existing = bySender.get(fromId);
                if (existing == null || liveLocationSortKey(message) >= liveLocationSortKey(existing)) {
                    bySender.put(fromId, message);
                }
            }
            for (TLRPC.Message message : bySender.values()) {
                out.add(new it.belloworld.mercurygram.MgIncomingLiveLocation(currentAccount, did, message));
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    protected void setNewLocationEndWatchTime() {
        if (sharingLocations.isEmpty()) {
            LiveLocationDebug.log("viewerSignal skipped: no active shares");
            return;
        }
        locationEndWatchTime = SystemClock.elapsedRealtime() + WATCH_LOCATION_TIMEOUT;
        mgLiveLoc.onWatcher();
        boolean blocked = mgLiveLoc.viewerBoostBlockedByBatterySaver();
        LiveLocationDebug.log("viewerSignal shares=" + sharingLocations.size()
                + " battery=" + mgLiveLoc.batterySaver()
                + " alwaysOn=" + mgLiveLoc.alwaysOn()
                + " alwaysOff=" + mgLiveLoc.alwaysOff()
                + " watcherWants=" + mgLiveLoc.watcherWantsGps()
                + " blocked=" + blocked
                + " started=" + started
                + " lastFix=" + LiveLocationDebug.fixSummary(lastKnownLocation));
        if (!blocked) {
            // Clear last fix so always-off → boost acquire requires a fresh accurate sample.
            if (mgLiveLoc.alwaysOff() || !started) {
                setLastKnownLocation(null);
            }
            if (!started) {
                pendingGpsTrigger = it.belloworld.mercurygram.MgLiveLocationFixLog.TRIGGER_VIEWED;
            }
            start();
            LiveLocationDebug.log("viewerSignal start() invoked started=" + started
                    + " pendingTrigger=" + pendingGpsTrigger);
        } else {
            LiveLocationDebug.log("viewerSignal GPS not started (battery saver block)");
        }
    }

    protected void update() {
        if (!sharingLocations.isEmpty()) {
            for (int a = 0; a < sharingLocations.size(); a++) {
                final SharingLocationInfo info = sharingLocations.get(a);
                int currentTime = getConnectionsManager().getCurrentTime();
                if (info.stopTime <= currentTime) {
                    sharingLocations.remove(a);
                    sharingLocationsMap.remove(info.did);
                    saveSharingLocation(info, 1);
                    AndroidUtilities.runOnUIThread(() -> {
                        sharingLocationsUI.remove(info);
                        sharingLocationsMapUI.remove(info.did);
                        if (sharingLocationsUI.isEmpty()) {
                            stopService();
                        }
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
                    });
                    a--;
                }
            }
            refreshLiveLocSharePeriod();
        }
        boolean sharing = !sharingLocations.isEmpty();
        if (!sharing) {
            if (started) {
                stop(true);
                mgLiveLoc.reset();
            }
            return;
        }
        if (started) {
            boolean canSend = mgLiveLoc.hasAccurateFix()
                    || (mgLiveLoc.alwaysOn() && isFreshEnoughToSend(lastKnownLocation));
            long newTime = SystemClock.elapsedRealtime();
            if (canSend && (lastLocationByMaps || Math.abs(lastLocationStartTime - newTime) > LOCATION_ACQUIRE_TIME || shouldSendLocationNow())) {
                lastLocationByMaps = false;
                locationSentSinceLastMapUpdate = true;
                boolean cancelAll = (SystemClock.elapsedRealtime() - lastLocationSendTime) > 2 * 1000;
                lastLocationStartTime = newTime;
                lastLocationSendTime = SystemClock.elapsedRealtime();
                LiveLocationDebug.log("broadcast try hasFix=" + (lastKnownLocation != null)
                        + " " + LiveLocationDebug.fixSummary(lastKnownLocation)
                        + " accurate=" + mgLiveLoc.hasAccurateFix()
                        + " shares=" + sharingLocations.size());
                broadcastLastKnownLocation(cancelAll);
            }
            if (shouldStopGps()) {
                stop(true);
                mgLiveLoc.onStoppedForSleepOrTimeout(true);
            }
        } else if (mgLiveLoc.shouldStartGps(true, false) || mgLiveLoc.watcherWantsGps()) {
            lastLocationStartTime = SystemClock.elapsedRealtime();
            setLastKnownLocation(null);
            if (pendingGpsTrigger == null) {
                pendingGpsTrigger = mgLiveLoc.inferStartReason();
            }
            start();
        }
    }

    private boolean isFreshEnoughToSend(Location location) {
        if (location == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            long ageSec = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000000L;
            return ageSec >= 0 && ageSec <= 15;
        }
        return true;
    }

    private boolean shouldSendLocationNow() {
        if (!mgLiveLoc.hasAccurateFix()) {
            return false;
        }
        if (Math.abs(lastLocationSendTime - SystemClock.elapsedRealtime()) >= SEND_NEW_LOCATION_TIME) {
            return true;
        }
        return false;
    }

    public void cleanup() {
        sharingLocationsUI.clear();
        sharingLocationsMapUI.clear();
        locationsCache.clear();
        cacheRequests.clear();
        lastReadLocationTime.clear();
        stopService();
        Utilities.stageQueue.postRunnable(() -> {
            locationEndWatchTime = 0;
            requests.clear();
            sharingLocationsMap.clear();
            sharingLocations.clear();
            setLastKnownLocation(null);
            mgLiveLoc.reset();
            stop(true);
        });
    }

    private void setLastKnownLocation(Location location) {
        if (location != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000000 > 60 * 5) {
            return;
        }
        lastKnownLocation = location;
        if (mgLiveLoc.onLocation(location)) {
            lastLocationStartTime = SystemClock.elapsedRealtime() - LOCATION_ACQUIRE_TIME;
        }
        if (lastKnownLocation != null) {
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.newLocationAvailable));
        }
    }

    protected void addSharingLocation(TLRPC.Message message) {
        final SharingLocationInfo info = new SharingLocationInfo();
        info.did = message.dialog_id;
        info.mid = message.id;
        info.period = message.media.period;
        info.lastSentProximityMeters = info.proximityMeters = message.media.proximity_notification_radius;
        info.account = currentAccount;
        info.messageObject = new MessageObject(currentAccount, message, false, false);
        if (info.period == 0x7FFFFFFF) {
            info.stopTime = Integer.MAX_VALUE;
        } else {
            info.stopTime = getConnectionsManager().getCurrentTime() + info.period;
        }
        final SharingLocationInfo old = sharingLocationsMap.get(info.did);
        sharingLocationsMap.put(info.did, info);
        if (old != null) {
            sharingLocations.remove(old);
        }
        sharingLocations.add(info);
        saveSharingLocation(info, 0);
        mgLiveLoc.prepareImmediateAcquire();
        refreshLiveLocSharePeriod();
        lastLocationSendTime = SystemClock.elapsedRealtime() - BACKGROUD_UPDATE_TIME + 5000;
        AndroidUtilities.runOnUIThread(() -> {
            if (old != null) {
                sharingLocationsUI.remove(old);
            }
            sharingLocationsUI.add(info);
            sharingLocationsMapUI.put(info.did, info);
            startService();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
            it.belloworld.mercurygram.MgLiveLocationExtendScheduler.onSharingStarted(currentAccount, info);
        });
    }

    public void persistSharingLocationInfo(SharingLocationInfo info) {
        if (info == null) {
            return;
        }
        saveSharingLocation(info, 0);
    }

    public boolean isSharingLocation(long did) {
        return sharingLocationsMapUI.indexOfKey(did) >= 0;
    }

    public SharingLocationInfo getSharingLocationInfo(long did) {
        return sharingLocationsMapUI.get(did);
    }

    public boolean setProximityLocation(long did, int meters, boolean broadcast) {
        SharingLocationInfo info = sharingLocationsMapUI.get(did);
        if (info != null) {
            info.proximityMeters = meters;
        }
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            try {
                SQLitePreparedStatement state = getMessagesStorage().getDatabase().executeFast("UPDATE sharing_locations SET proximity = ? WHERE uid = ?");
                state.requery();
                state.bindInteger(1, meters);
                state.bindLong(2, did);
                state.step();
                state.dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        if (broadcast) {
            Utilities.stageQueue.postRunnable(() -> broadcastLastKnownLocation(true));
        }
        return info != null;
    }

    public static int getHeading(Location location) {
        float val = location.getBearing();
        if (val > 0 && val < 1.0f) {
            if (val < 0.5f) {
                return 360;
            } else {
                return 1;
            }
        }
        return (int) val;
    }

    private void loadSharingLocations() {
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            final ArrayList<SharingLocationInfo> result = new ArrayList<>();
            final ArrayList<TLRPC.User> users = new ArrayList<>();
            final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            try {
                ArrayList<Long> usersToLoad = new ArrayList<>();
                ArrayList<Long> chatsToLoad = new ArrayList<>();
                SQLiteCursor cursor = getMessagesStorage().getDatabase().queryFinalized("SELECT uid, mid, date, period, message, proximity FROM sharing_locations WHERE 1");
                while (cursor.next()) {
                    SharingLocationInfo info = new SharingLocationInfo();
                    info.did = cursor.longValue(0);
                    info.mid = cursor.intValue(1);
                    info.stopTime = cursor.intValue(2);
                    info.period = cursor.intValue(3);
                    info.proximityMeters = cursor.intValue(5);
                    info.account = currentAccount;
                    NativeByteBuffer data = cursor.byteBufferValue(4);
                    if (data != null) {
                        info.messageObject = new MessageObject(currentAccount, TLRPC.Message.TLdeserialize(data, data.readInt32(false), false), false, false);
                        MessagesStorage.addUsersAndChatsFromMessage(info.messageObject.messageOwner, usersToLoad, chatsToLoad, null);
                        data.reuse();
                    }
                    result.add(info);
                    if (DialogObject.isChatDialog(info.did)) {
                        if (!chatsToLoad.contains(-info.did)) {
                            chatsToLoad.add(-info.did);
                        }
                    } else if (DialogObject.isUserDialog(info.did)) {
                        if (!usersToLoad.contains(info.did)) {
                            usersToLoad.add(info.did);
                        }
                    }
                }
                cursor.dispose();
                if (!chatsToLoad.isEmpty()) {
                    getMessagesStorage().getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
                }
                getMessagesStorage().getUsersInternal(usersToLoad, users);
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (!result.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    getMessagesController().putUsers(users, true);
                    getMessagesController().putChats(chats, true);
                    Utilities.stageQueue.postRunnable(() -> {
                        sharingLocations.addAll(result);
                        for (int a = 0; a < sharingLocations.size(); a++) {
                            SharingLocationInfo info = sharingLocations.get(a);
                            sharingLocationsMap.put(info.did, info);
                        }
                        refreshLiveLocSharePeriod();
                        AndroidUtilities.runOnUIThread(() -> {
                            sharingLocationsUI.addAll(result);
                            for (int a = 0; a < result.size(); a++) {
                                SharingLocationInfo info = result.get(a);
                                sharingLocationsMapUI.put(info.did, info);
                            }
                            startService();
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
                            it.belloworld.mercurygram.MgLiveLocationExtendScheduler.rescheduleAll();
                        });
						// Mercurygram: after kill/update restore, force one GPS acquire ASAP.
						requestAcquireAfterRestore();
                    });
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> it.belloworld.mercurygram.MgLiveLocationExtendScheduler.rescheduleAll());
            }
        });
    }

    private void saveSharingLocation(final SharingLocationInfo info, final int remove) {
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            try {
                if (remove == 2) {
                    getMessagesStorage().getDatabase().executeFast("DELETE FROM sharing_locations WHERE 1").stepThis().dispose();
                } else if (remove == 1) {
                    if (info == null) {
                        return;
                    }
                    getMessagesStorage().getDatabase().executeFast("DELETE FROM sharing_locations WHERE uid = " + info.did).stepThis().dispose();
                } else {
                    if (info == null) {
                        return;
                    }
                    SQLitePreparedStatement state = getMessagesStorage().getDatabase().executeFast("REPLACE INTO sharing_locations VALUES(?, ?, ?, ?, ?, ?)");
                    state.requery();

                    NativeByteBuffer data = new NativeByteBuffer(info.messageObject.messageOwner.getObjectSize());
                    info.messageObject.messageOwner.serializeToStream(data);

                    state.bindLong(1, info.did);
                    state.bindInteger(2, info.mid);
                    state.bindInteger(3, info.stopTime);
                    state.bindInteger(4, info.period);
                    state.bindByteBuffer(5, data);
                    state.bindInteger(6, info.proximityMeters);

                    state.step();
                    state.dispose();
                    data.reuse();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void removeSharingLocation(final long did) {
        Utilities.stageQueue.postRunnable(() -> {
            final SharingLocationInfo info = sharingLocationsMap.get(did);
            sharingLocationsMap.remove(did);
            if (info != null) {
                TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
                req.peer = getMessagesController().getInputPeer(info.did);
                req.id = info.mid;
                req.flags |= 16384;
                req.media = new TLRPC.TL_inputMediaGeoLive();
                req.media.stopped = true;
                req.media.geo_point = new TLRPC.TL_inputGeoPointEmpty();
                getConnectionsManager().sendRequest(req, (response, error) -> {
                    if (error != null) {
                        return;
                    }
                    getMessagesController().processUpdates((TLRPC.Updates) response, false);
                });
                sharingLocations.remove(info);
                saveSharingLocation(info, 1);
                AndroidUtilities.runOnUIThread(() -> {
                    sharingLocationsUI.remove(info);
                    sharingLocationsMapUI.remove(info.did);
                    if (sharingLocationsUI.isEmpty()) {
                        stopService();
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
                    it.belloworld.mercurygram.MgLiveLocationExtendScheduler.onSharingStopped(currentAccount, info.did);
                });
                if (sharingLocations.isEmpty()) {
                    mgLiveLoc.reset();
                    stop(true);
                } else {
                    refreshLiveLocSharePeriod();
                }
            }
        });
    }

    /** Location permission via Context (PermissionRequest needs an Activity and fails on boot). */
    private static boolean hasLocationPermissionForService() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return false;
        }
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startService() {
        LiveLocationDebug.log("startService LocationSharingService sharesUI=" + sharingLocationsUI.size());
        try {
            if (hasLocationPermissionForService()) {
                Intent intent = new Intent(ApplicationLoader.applicationContext, LocationSharingService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    ApplicationLoader.applicationContext.startForegroundService(intent);
                } else {
                    ApplicationLoader.applicationContext.startService(intent);
                }
            } else {
                LiveLocationDebug.log("startService skipped: no location permission");
            }
        } catch (Throwable e) {
            FileLog.e(e);
            LiveLocationDebug.log("startService failed: " + e);
        }
    }

    /**
     * Re-start {@link LocationSharingService} when outgoing shares exist but the FGS
     * is dead (e.g. after install restore skipped start, or service kill). Safe to call
     * from app resume / process bring-up.
     */
    public static void ensureSharingServiceRunning() {
        int shares = 0;
        int started = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            LocationController controller = getInstance(a);
            int n = controller.sharingLocationsUI.size();
            shares += n;
            if (n > 0) {
                controller.startService();
                started++;
            }
        }
        LiveLocationDebug.log("ensureSharingServiceRunning sharesUI=" + shares + " accountsStarted=" + started);
    }

	/**
	 * One-shot duty-cycle bypass after process restore so a fresh fix is sent soon.
	 * Safe if GPS is already running. Call from share load / boot / package-replaced only —
	 * not from ordinary app resume.
	 */
	public void requestAcquireAfterRestore() {
		Utilities.stageQueue.postRunnable(() -> {
			if (sharingLocations.isEmpty()) {
				return;
			}
			mgLiveLoc.requestForceAcquireAfterRestore();
			if (!started) {
				pendingGpsTrigger = it.belloworld.mercurygram.MgLiveLocationFixLog.TRIGGER_RESTART;
				lastLocationStartTime = SystemClock.elapsedRealtime();
				setLastKnownLocation(null);
				start();
				LiveLocationDebug.log("restore acquire started trigger=restart shares=" + sharingLocations.size());
			} else {
				LiveLocationDebug.log("restore acquire skipped (gps already on) shares=" + sharingLocations.size());
			}
		});
	}

	public static void requestAcquireAfterRestoreAll() {
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (!UserConfig.getInstance(a).isClientActivated()) {
				continue;
			}
			LocationController controller = getInstance(a);
			if (!controller.sharingLocationsUI.isEmpty() || !controller.sharingLocations.isEmpty()) {
				controller.requestAcquireAfterRestore();
			}
		}
	}

	/**
	 * Best-effort: publish last good fix and flush Fix Log before process death.
	 * Fix Log uses commit() and is flushed synchronously when possible.
	 */
	public void flushForProcessDeath() {
		final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		Utilities.stageQueue.postRunnable(() -> {
			try {
				LiveLocationDebug.log("flushForProcessDeath started=" + started
						+ " accurate=" + mgLiveLoc.hasAccurateFix()
						+ " " + LiveLocationDebug.fixSummary(lastKnownLocation));
				if (started && lastKnownLocation != null && mgLiveLoc.hasAccurateFix()) {
					try {
						broadcastLastKnownLocation(false);
					} catch (Throwable e) {
						FileLog.e(e);
					}
				}
				mgLiveLoc.flushSessionForProcessDeath();
			} finally {
				latch.countDown();
			}
		});
		try {
			latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public static void flushAllForProcessDeath() {
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (!UserConfig.getInstance(a).isClientActivated()) {
				continue;
			}
			getInstance(a).flushForProcessDeath();
		}
	}

    private void stopService() {
        LiveLocationDebug.log("stopService LocationSharingService");
        ApplicationLoader.applicationContext.stopService(new Intent(ApplicationLoader.applicationContext, LocationSharingService.class));
    }

    public void removeAllLocationSharings() {
        Utilities.stageQueue.postRunnable(() -> {
            for (int a = 0; a < sharingLocations.size(); a++) {
                SharingLocationInfo info = sharingLocations.get(a);
                TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
                req.peer = getMessagesController().getInputPeer(info.did);
                req.id = info.mid;
                req.flags |= 16384;
                req.media = new TLRPC.TL_inputMediaGeoLive();
                req.media.stopped = true;
                req.media.geo_point = new TLRPC.TL_inputGeoPointEmpty();
                getConnectionsManager().sendRequest(req, (response, error) -> {
                    if (error != null) {
                        return;
                    }
                    getMessagesController().processUpdates((TLRPC.Updates) response, false);
                });
            }
            sharingLocations.clear();
            sharingLocationsMap.clear();
            saveSharingLocation(null, 2);
            mgLiveLoc.reset();
            stop(true);
            AndroidUtilities.runOnUIThread(() -> {
                sharingLocationsUI.clear();
                sharingLocationsMapUI.clear();
                stopService();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
                it.belloworld.mercurygram.MgLiveLocationExtendScheduler.cancelAllForAccount(currentAccount);
            });
        });
    }

    public void setMapLocation(Location location, boolean first) {
        if (location == null) {
            return;
        }
        lastLocationByMaps = true;
        if (first || lastKnownLocation != null && lastKnownLocation.distanceTo(location) >= 20) {
            lastLocationSendTime = SystemClock.elapsedRealtime() - BACKGROUD_UPDATE_TIME;
            locationSentSinceLastMapUpdate = false;
        } else if (locationSentSinceLastMapUpdate) {
            lastLocationSendTime = SystemClock.elapsedRealtime() - BACKGROUD_UPDATE_TIME + FOREGROUND_UPDATE_TIME;
            locationSentSinceLastMapUpdate = false;
        }
        setLastKnownLocation(location);
    }

    private void start() {
        if (started) {
            return;
        }
        lastLocationStartTime = SystemClock.elapsedRealtime();
        started = true;
        String trigger = pendingGpsTrigger != null ? pendingGpsTrigger : mgLiveLoc.inferStartReason();
        pendingGpsTrigger = null;
        mgLiveLoc.onListenersStarted(trigger);
        boolean ok = false;
        if (checkServices()) {
            try {
                apiClient.connect();
                ok = true;
                LiveLocationDebug.log("start fused connect()");
            } catch (Throwable e) {
                FileLog.e(e);
                LiveLocationDebug.log("start fused failed: " + e);
            }
        }
        if (!ok) {
            LiveLocationDebug.log("start native GPS+network+passive services=" + checkServices());
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1, 0, gpsLocationListener);
            } catch (Exception e) {
                FileLog.e(e);
                LiveLocationDebug.log("GPS_PROVIDER failed: " + e);
            }
            try {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1, 0, networkLocationListener);
            } catch (Exception e) {
                FileLog.e(e);
                LiveLocationDebug.log("NETWORK_PROVIDER failed: " + e);
            }
            try {
                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1, 0, passiveLocationListener);
            } catch (Exception e) {
                FileLog.e(e);
                LiveLocationDebug.log("PASSIVE_PROVIDER failed: " + e);
            }
        }
    }

    private void stop(boolean empty) {
        LiveLocationDebug.log("stop empty=" + empty + " hadFix=" + (lastKnownLocation != null)
                + " " + LiveLocationDebug.fixSummary(lastKnownLocation));
        started = false;
        if (checkServices()) {
            try {
                ApplicationLoader.getLocationServiceProvider().removeLocationUpdates(fusedLocationListener);
                apiClient.disconnect();
            } catch (Throwable e) {
                FileLog.e(e, false);
            }
        }
        locationManager.removeUpdates(gpsLocationListener);
        if (empty) {
            locationManager.removeUpdates(networkLocationListener);
            locationManager.removeUpdates(passiveLocationListener);
        }
    }

    public Location getLastKnownLocation() {
        return lastKnownLocation;
    }

    public static boolean isActiveLiveLocation(TLRPC.Message message, int now) {
        if (message == null || !(message.media instanceof TLRPC.TL_messageMediaGeoLive)) {
            return false;
        }
        int period = message.media.period;
        if (period == 0x7FFFFFFF) {
            return true;
        }
        return message.date + period > now;
    }

    private static int liveLocationSortKey(TLRPC.Message message) {
        return message.edit_date != 0 ? message.edit_date : message.date;
    }

    public static ArrayList<TLRPC.Message> mergeLiveLocationLists(ArrayList<TLRPC.Message> first, ArrayList<TLRPC.Message> second) {
        HashMap<Long, TLRPC.Message> bySender = new HashMap<>();
        mergeLiveLocationIntoMap(bySender, first);
        mergeLiveLocationIntoMap(bySender, second);
        return new ArrayList<>(bySender.values());
    }

    private static void mergeLiveLocationIntoMap(HashMap<Long, TLRPC.Message> bySender, ArrayList<TLRPC.Message> messages) {
        if (messages == null) {
            return;
        }
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.Message message = messages.get(a);
            if (!MessageObject.isLiveLocationMessage(message)) {
                continue;
            }
            long fromId = MessageObject.getFromChatId(message);
            TLRPC.Message existing = bySender.get(fromId);
            if (existing == null || liveLocationSortKey(message) >= liveLocationSortKey(existing)) {
                bySender.put(fromId, message);
            }
        }
    }

    public void loadLocalActiveLiveLocations(long did, Utilities.Callback<ArrayList<TLRPC.Message>> callback) {
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            HashMap<Long, TLRPC.Message> bySender = new HashMap<>();
            SQLiteCursor cursor = null;
            try {
                int now = getConnectionsManager().getCurrentTime();
                cursor = getMessagesStorage().getDatabase().queryFinalized(
                        "SELECT data FROM messages_v2 WHERE uid = ? AND media = -1 ORDER BY date DESC", did);
                while (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    if (!isActiveLiveLocation(message, now)) {
                        continue;
                    }
                    long fromId = MessageObject.getFromChatId(message);
                    TLRPC.Message existing = bySender.get(fromId);
                    if (existing == null || liveLocationSortKey(message) >= liveLocationSortKey(existing)) {
                        bySender.put(fromId, message);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            ArrayList<TLRPC.Message> result = new ArrayList<>(bySender.values());
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    private void storeMergedLiveLocations(long did, ArrayList<TLRPC.Message> serverMessages, TLRPC.messages_Messages res) {
        loadLocalActiveLiveLocations(did, local -> {
            ArrayList<TLRPC.Message> merged = mergeLiveLocationLists(serverMessages, local);
            int now = getConnectionsManager().getCurrentTime();
            for (int a = 0; a < merged.size(); a++) {
                if (!isActiveLiveLocation(merged.get(a), now)) {
                    merged.remove(a);
                    a--;
                }
            }
            if (res != null) {
                getMessagesStorage().putUsersAndChats(res.users, res.chats, true, true);
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
            }
            locationsCache.put(did, merged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsCacheChanged, did, currentAccount);
        });
    }

    public void loadLiveLocations(long did) {
        if (cacheRequests.indexOfKey(did) >= 0) {
            return;
        }
        cacheRequests.put(did, true);
        TLRPC.TL_messages_getRecentLocations req = new TLRPC.TL_messages_getRecentLocations();
        req.peer = getMessagesController().getInputPeer(did);
        req.limit = 100;
        getConnectionsManager().sendRequest(req, (response, error) -> {
            if (error != null) {
                cacheRequests.delete(did);
                return;
            }
            AndroidUtilities.runOnUIThread(() -> cacheRequests.delete(did));
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            ArrayList<TLRPC.Message> serverMessages = new ArrayList<>();
            for (int a = 0; a < res.messages.size(); a++) {
                if (res.messages.get(a).media instanceof TLRPC.TL_messageMediaGeoLive) {
                    serverMessages.add(res.messages.get(a));
                }
            }
            AndroidUtilities.runOnUIThread(() -> storeMergedLiveLocations(did, serverMessages, res));
        });
    }

    public void markLiveLoactionsAsRead(long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        ArrayList<TLRPC.Message> messages = locationsCache.get(dialogId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Integer date = lastReadLocationTime.get(dialogId);
        int currentDate = (int) (SystemClock.elapsedRealtime() / 1000);
        if (date != null && date + 60 > currentDate) {
            return;
        }
        lastReadLocationTime.put(dialogId, currentDate);
        TLObject request;
        if (DialogObject.isChatDialog(dialogId) && ChatObject.isChannel(-dialogId, currentAccount)) {
            TLRPC.TL_channels_readMessageContents req = new TLRPC.TL_channels_readMessageContents();
            for (int a = 0, N = messages.size(); a < N; a++) {
                req.id.add(messages.get(a).id);
            }
            req.channel = getMessagesController().getInputChannel(-dialogId);
            request = req;
        } else {
            TLRPC.TL_messages_readMessageContents req = new TLRPC.TL_messages_readMessageContents();
            for (int a = 0, N = messages.size(); a < N; a++) {
                req.id.add(messages.get(a).id);
            }
            request = req;
        }
        getConnectionsManager().sendRequest(request, (response, error) -> {
            if (response instanceof TLRPC.TL_messages_affectedMessages) {
                TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                getMessagesController().processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
            }
        });
    }

    public static int getLocationsCount() {
        int count = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            count += LocationController.getInstance(a).sharingLocationsUI.size();
        }
        return count;
    }

    public interface LocationFetchCallback {
        void onLocationAddressAvailable(String address, String displayAddress, TLRPC.TL_messageMediaVenue city, TLRPC.TL_messageMediaVenue street, Location location);
    }

    public static final int TYPE_BIZ = 1;
    public static final int TYPE_STORY = 2;

    // google geocoder thinks that "unnamed road" is a street name
    public static String[] unnamedRoads = {
        "Unnamed Road",
        "Вulicya bez nazvi",
        "Нeizvestnaya doroga",
        "İsimsiz Yol",
        "Ceļš bez nosaukuma",
        "Kelias be pavadinimo",
        "Droga bez nazwy",
        "Cesta bez názvu",
        "Silnice bez názvu",
        "Drum fără nume",
        "Route sans nom",
        "Vía sin nombre",
        "Estrada sem nome",
        "Οdos xoris onomasia",
        "Rrugë pa emër",
        "Пat bez ime",
        "Нeimenovani put",
        "Strada senza nome",
        "Straße ohne Straßennamen"
    };

    private static HashMap<LocationFetchCallback, Runnable> callbacks = new HashMap<>();
    public static void fetchLocationAddress(Location location, LocationFetchCallback callback) {
        fetchLocationAddress(location, 0, callback);
    }
    public static void fetchLocationAddress(Location location, int type, LocationFetchCallback callback) {
        if (callback == null) {
            return;
        }
        Runnable fetchLocationRunnable = callbacks.get(callback);
        if (fetchLocationRunnable != null) {
            Utilities.globalQueue.cancelRunnable(fetchLocationRunnable);
            callbacks.remove(callback);
        }
        if (location == null) {
            callback.onLocationAddressAvailable(null, null, null, null, null);
            return;
        }

        Locale locale;
        Locale englishLocale;
        try {
            locale = LocaleController.getInstance().getCurrentLocale();
        } catch (Exception ignore) {
            locale = LocaleController.getInstance().getSystemDefaultLocale();
        }
        if (locale.getLanguage().contains("en")) {
            englishLocale = locale;
        } else {
            englishLocale = Locale.US;
        }
        final Locale finalLocale = locale;
        Utilities.globalQueue.postRunnable(fetchLocationRunnable = () -> {
            String name, displayName, city, street, countryCode = null, locality = null, feature = null, engFeature = null;
            String engState = null, engCity = null;
            StringBuilder engStreet = new StringBuilder();
            boolean onlyCountry = true;
            TLRPC.TL_messageMediaVenue cityLocation = null;
            TL_stories.TL_geoPointAddress cityAddress = new TL_stories.TL_geoPointAddress();
            TLRPC.TL_messageMediaVenue streetLocation = null;
            TL_stories.TL_geoPointAddress streetAddress = new TL_stories.TL_geoPointAddress();
            try {
                Geocoder gcd = new Geocoder(ApplicationLoader.applicationContext, finalLocale);
                List<Address> addresses = gcd.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                List<Address> engAddresses = null;
                if (type == TYPE_STORY) {
                    if (englishLocale == finalLocale) {
                        engAddresses = addresses;
                    } else {
                        Geocoder gcd2 = new Geocoder(ApplicationLoader.applicationContext, englishLocale);
                        engAddresses = gcd2.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                    }
                }
                if (addresses.size() > 0) {
                    Address address = addresses.get(0);
                    Address engAddress = engAddresses != null && engAddresses.size() >= 1 ? engAddresses.get(0) : null;
                    if (type == TYPE_BIZ) {
                        ArrayList<String> parts = new ArrayList<>();

                        String arg = null;
                        try {
                            arg = address.getAddressLine(0);
                        } catch (Exception ignore) {}
                        if (TextUtils.isEmpty(arg)) {
                            try {
                                parts.add(address.getSubThoroughfare());
                            } catch (Exception ignore) {}
                            try {
                                parts.add(address.getThoroughfare());
                            } catch (Exception ignore) {}
                            try {
                                parts.add(address.getAdminArea());
                            } catch (Exception ignore) {}
                            try {
                                parts.add(address.getCountryName());
                            } catch (Exception ignore) {}
                        } else {
                            parts.add(arg);
                        }

                        for (int i = 0; i < parts.size(); ++i) {
                            if (parts.get(i) != null) {
                                String[] partsInside = parts.get(i).split(", ");
                                if (partsInside.length > 1) {
                                    parts.remove(i);
                                    for (int j = 0; j < partsInside.length; ++j) {
                                        parts.add(i, partsInside[j]);
                                        i++;
                                    }
                                }
                            }
                        }
                        for (int i = 0; i < parts.size(); ++i) {
                            if (TextUtils.isEmpty(parts.get(i)) || parts.indexOf(parts.get(i)) != i || parts.get(i).matches("^\\s*\\d{4,}\\s*$")) {
                                parts.remove(i);
                                i--;
                            }
                        }

                        name = displayName = parts.isEmpty() ? null : TextUtils.join(", ", parts);
                        city = null;
                        street = null;
                        countryCode = null;
                    } else {

                        boolean hasAny = false;
                        String arg;

                        StringBuilder nameBuilder = new StringBuilder();
                        StringBuilder displayNameBuilder = new StringBuilder();
                        StringBuilder cityBuilder = new StringBuilder();
                        StringBuilder streetBuilder = new StringBuilder();

//                    String addressLine = null;
//                    try {
//                        addressLine = address.getAddressLine(0);
//                    } catch (Exception ignore) {}
//                    if (addressLine != null) {
//                        String postalCode = address.getPostalCode();
//                        if (postalCode != null) {
//                            addressLine = addressLine.replace(" " + postalCode, "");
//                            addressLine = addressLine.replace(postalCode, "");
//                        }
//                        String[] parts = addressLine.split(", ");
//                        if (parts.length > 2) {
//                            String _country = parts[parts.length - 1].replace(",", "").trim();
//                            String _city = parts[parts.length - 2].replace(",", "").trim();
////                            if (_city.length() > 3) {
////                                locality = _city;
////                            }
////                            feature = parts[0].replace(",", "").trim();
//                        }
//                    }

                        if (TextUtils.isEmpty(locality)) {
                            locality = address.getLocality();
                        }
                        if (TextUtils.isEmpty(locality)) {
                            locality = address.getAdminArea();
                        }
                        if (TextUtils.isEmpty(locality)) {
                            locality = address.getSubAdminArea();
                        }
                        if (engAddress != null) {
                            if (TextUtils.isEmpty(engCity)) {
                                engCity = engAddress.getLocality();
                            }
                            if (TextUtils.isEmpty(engCity)) {
                                engCity = engAddress.getAdminArea();
                            }
                            if (TextUtils.isEmpty(engCity)) {
                                engCity = engAddress.getSubAdminArea();
                            }

                            engState = engAddress.getAdminArea();
                        }

                        if (TextUtils.isEmpty(feature) && !TextUtils.equals(address.getThoroughfare(), locality) && !TextUtils.equals(address.getThoroughfare(), address.getCountryName())) {
                            feature = address.getThoroughfare();
                        }
                        if (TextUtils.isEmpty(feature) && !TextUtils.equals(address.getSubLocality(), locality) && !TextUtils.equals(address.getSubLocality(), address.getCountryName())) {
                            feature = address.getSubLocality();
                        }
                        if (TextUtils.isEmpty(feature) && !TextUtils.equals(address.getLocality(), locality) && !TextUtils.equals(address.getLocality(), address.getCountryName())) {
                            feature = address.getLocality();
                        }
                        if (!TextUtils.isEmpty(feature) && !TextUtils.equals(feature, locality) && !TextUtils.equals(feature, address.getCountryName())) {
                            if (streetBuilder.length() > 0) {
                                streetBuilder.append(", ");
                            }
                            streetBuilder.append(feature);
                        } else {
                            streetBuilder = null;
                        }

                        if (engAddress != null) {
                            if (TextUtils.isEmpty(engFeature) && !TextUtils.equals(engAddress.getThoroughfare(), locality) && !TextUtils.equals(engAddress.getThoroughfare(), engAddress.getCountryName())) {
                                engFeature = engAddress.getThoroughfare();
                            }
                            if (TextUtils.isEmpty(engFeature) && !TextUtils.equals(engAddress.getSubLocality(), locality) && !TextUtils.equals(engAddress.getSubLocality(), engAddress.getCountryName())) {
                                engFeature = engAddress.getSubLocality();
                            }
                            if (TextUtils.isEmpty(engFeature) && !TextUtils.equals(engAddress.getLocality(), locality) && !TextUtils.equals(engAddress.getLocality(), engAddress.getCountryName())) {
                                engFeature = engAddress.getLocality();
                            }
                            if (!TextUtils.isEmpty(engFeature) && !TextUtils.equals(engFeature, engState) && !TextUtils.equals(engFeature, engAddress.getCountryName())) {
                                if (engStreet.length() > 0) {
                                    engStreet.append(", ");
                                }
                                engStreet.append(engFeature);
                            } else {
                                engStreet = null;
                            }

                            if (!TextUtils.isEmpty(engStreet)) {
                                boolean isUnnamed = false;
                                for (int i = 0; i < unnamedRoads.length; ++i) {
                                    if (unnamedRoads[i].equalsIgnoreCase(engStreet.toString())) {
                                        isUnnamed = true;
                                        break;
                                    }
                                }
                                if (isUnnamed) {
                                    engStreet = null;
                                    streetBuilder = null;
                                }
                            }
                        }

                        if (!TextUtils.isEmpty(locality)) {
                            if (cityBuilder.length() > 0) {
                                cityBuilder.append(", ");
                            }
                            cityBuilder.append(locality);
                            onlyCountry = false;
                            if (streetBuilder != null) {
                                if (streetBuilder.length() > 0) {
                                    streetBuilder.append(", ");
                                }
                                streetBuilder.append(locality);
                            }
                        }

                        arg = address.getSubThoroughfare();
                        if (!TextUtils.isEmpty(arg)) {
                            nameBuilder.append(arg);
                            hasAny = true;
                        }
                        arg = address.getThoroughfare();
                        if (!TextUtils.isEmpty(arg)) {
                            if (nameBuilder.length() > 0) {
                                nameBuilder.append(" ");
                            }
                            nameBuilder.append(arg);
                            hasAny = true;
                        }
                        if (!hasAny) {
                            arg = address.getAdminArea();
                            if (!TextUtils.isEmpty(arg)) {
                                if (nameBuilder.length() > 0) {
                                    nameBuilder.append(", ");
                                }
                                nameBuilder.append(arg);
                            }
                            arg = address.getSubAdminArea();
                            if (!TextUtils.isEmpty(arg)) {
                                if (nameBuilder.length() > 0) {
                                    nameBuilder.append(", ");
                                }
                                nameBuilder.append(arg);
                            }
                        }
                        arg = address.getLocality();
                        if (!TextUtils.isEmpty(arg)) {
                            if (nameBuilder.length() > 0) {
                                nameBuilder.append(", ");
                            }
                            nameBuilder.append(arg);
                        }
                        countryCode = address.getCountryCode();
                        arg = address.getCountryName();
                        if (!TextUtils.isEmpty(arg)) {
                            if (nameBuilder.length() > 0) {
                                nameBuilder.append(", ");
                            }
                            nameBuilder.append(arg);
                            String shortCountry = arg;
                            final String lng = finalLocale.getLanguage();
                            if (("US".equals(address.getCountryCode()) || "AE".equals(address.getCountryCode())) && ("en".equals(lng) || "uk".equals(lng) || "ru".equals(lng)) || "GB".equals(address.getCountryCode()) && "en".equals(lng)) {
                                shortCountry = "";
                                String[] words = arg.split(" ");
                                for (String word : words) {
                                    if (word.length() > 0)
                                        shortCountry += word.charAt(0);
                                }
                            } else if ("US".equals(address.getCountryCode())) {
                                shortCountry = "USA";
                            }
                            if (cityBuilder.length() > 0) {
                                cityBuilder.append(", ");
                            }
                            cityBuilder.append(shortCountry);
                        }

                        arg = address.getCountryName();
                        if (!TextUtils.isEmpty(arg)) {
                            if (displayNameBuilder.length() > 0) {
                                displayNameBuilder.append(", ");
                            }
                            displayNameBuilder.append(arg);
                        }
                        arg = address.getLocality();
                        if (!TextUtils.isEmpty(arg)) {
                            if (displayNameBuilder.length() > 0) {
                                displayNameBuilder.append(", ");
                            }
                            displayNameBuilder.append(arg);
                        }
                        if (!hasAny) {
                            arg = address.getAdminArea();
                            if (!TextUtils.isEmpty(arg)) {
                                if (displayNameBuilder.length() > 0) {
                                    displayNameBuilder.append(", ");
                                }
                                displayNameBuilder.append(arg);
                            }
                            arg = address.getSubAdminArea();
                            if (!TextUtils.isEmpty(arg)) {
                                if (displayNameBuilder.length() > 0) {
                                    displayNameBuilder.append(", ");
                                }
                                displayNameBuilder.append(arg);
                            }
                        }

                        name = nameBuilder.toString();
                        displayName = displayNameBuilder.toString();
                        city = cityBuilder.toString();
                        street = streetBuilder == null ? null : streetBuilder.toString();
                    }
                } else {
                    if (type == TYPE_BIZ) {
                        name = displayName = null;
                    } else {
                        name = displayName = String.format(Locale.US, "Unknown address (%f,%f)", location.getLatitude(), location.getLongitude());
                    }
                    city = null;
                    street = null;
                }
                if (!TextUtils.isEmpty(city)) {
                    cityLocation = new TLRPC.TL_messageMediaVenue();
                    cityLocation.geo = new TLRPC.TL_geoPoint();
                    cityLocation.geo.lat = location.getLatitude();
                    cityLocation.geo._long = location.getLongitude();
                    cityLocation.query_id = -1;
                    cityLocation.title = city;
                    cityLocation.icon = onlyCountry ? "https://ss3.4sqi.net/img/categories_v2/building/government_capitolbuilding_64.png" : "https://ss3.4sqi.net/img/categories_v2/travel/hotel_64.png";
                    cityLocation.emoji = countryCodeToEmoji(countryCode);
                    cityLocation.address = onlyCountry ? LocaleController.getString(R.string.Country) : LocaleController.getString(R.string.PassportCity);

                    cityLocation.geoAddress = cityAddress;
                    cityAddress.country_iso2 = countryCode;
                    if (!onlyCountry) {
                        if (!TextUtils.isEmpty(engState)) {
                            cityAddress.flags |= 1;
                            cityAddress.state = engState;
                        }
                        if (!TextUtils.isEmpty(engCity)) {
                            cityAddress.flags |= 2;
                            cityAddress.city = engCity;
                        }
                    }
                }
                if (!TextUtils.isEmpty(street)) {
                    streetLocation = new TLRPC.TL_messageMediaVenue();
                    streetLocation.geo = new TLRPC.TL_geoPoint();
                    streetLocation.geo.lat = location.getLatitude();
                    streetLocation.geo._long = location.getLongitude();
                    streetLocation.query_id = -1;
                    streetLocation.title = street;
                    streetLocation.icon = "pin";
                    streetLocation.address = LocaleController.getString(R.string.PassportStreet1);

                    streetLocation.geoAddress = streetAddress;
                    streetAddress.country_iso2 = countryCode;
                    if (!TextUtils.isEmpty(engState)) {
                        streetAddress.flags |= 1;
                        streetAddress.state = engState;
                    }
                    if (!TextUtils.isEmpty(engCity)) {
                        streetAddress.flags |= 2;
                        streetAddress.city = engCity;
                    }
                    if (!TextUtils.isEmpty(engStreet)) {
                        streetAddress.flags |= 4;
                        streetAddress.street = engStreet.toString();
                    }
                }
                if (cityLocation == null && streetLocation == null && location != null) {
                    String ocean = detectOcean(location.getLongitude(), location.getLatitude());
                    if (ocean != null) {
                        cityLocation = new TLRPC.TL_messageMediaVenue();
                        cityLocation.geo = new TLRPC.TL_geoPoint();
                        cityLocation.geo.lat = location.getLatitude();
                        cityLocation.geo._long = location.getLongitude();
                        cityLocation.query_id = -1;
                        cityLocation.title = ocean;
                        cityLocation.icon = "pin";
                        cityLocation.emoji = "🌊";
                        cityLocation.address = "Ocean";
                    }
                }
            } catch (Exception ignore) {
                name = displayName = String.format(Locale.US, "Unknown address (%f,%f)", location.getLatitude(), location.getLongitude());
                city = null;
                street = null;
            }
            final String nameFinal = name;
            final String displayNameFinal = displayName;
            final TLRPC.TL_messageMediaVenue finalCityLocation = cityLocation;
            final TLRPC.TL_messageMediaVenue finalStreetLocation = streetLocation;
            AndroidUtilities.runOnUIThread(() -> {
                callbacks.remove(callback);
                callback.onLocationAddressAvailable(nameFinal, displayNameFinal, finalCityLocation, finalStreetLocation, location);
            });
        }, 300);
        callbacks.put(callback, fetchLocationRunnable);
    }

    public static String countryCodeToEmoji(String code) {
        if (code == null) {
            return null;
        }
        code = code.toUpperCase();
        final int count = code.codePointCount(0, code.length());
        if (count > 2) {
            return null;
        }
        StringBuilder flag = new StringBuilder();
        for (int j = 0; j < count; ++j) {
            flag.append(Character.toChars(Character.codePointAt(code, j) - 0x41 + 0x1F1E6));
        }
        return flag.toString();
    }

    public static String detectOcean(double x, double y) {
        if (y > 65) {
            return "Arctic Ocean";
        }
        if (x > -88 && x < 40 && y > 0 || x > -60 && x < 20 && y <= 0) {
            return "Atlantic Ocean";
        }
        if (y <= 30 && x >= 20 && x < 150) {
            return "Indian Ocean";
        }
        if ((x > 106 || x < -60) && y > 0 || (x > 150 || x < -60) && y <= 0) {
            return "Pacific Ocean";
        }
        return null;
    }
}
