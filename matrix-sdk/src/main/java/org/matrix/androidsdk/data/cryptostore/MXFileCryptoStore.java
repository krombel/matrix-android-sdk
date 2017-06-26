/*
 * Copyright 2015 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.data.cryptostore;

import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;

import org.matrix.androidsdk.crypto.data.MXOlmInboundGroupSession;
import org.matrix.androidsdk.util.Log;

import org.matrix.androidsdk.crypto.data.MXDeviceInfo;
import org.matrix.androidsdk.crypto.data.MXOlmInboundGroupSession2;
import org.matrix.androidsdk.crypto.data.MXUsersDevicesMap;
import org.matrix.androidsdk.rest.model.login.Credentials;
import org.matrix.androidsdk.util.ContentUtils;
import org.matrix.olm.OlmAccount;
import org.matrix.olm.OlmSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * the crypto data store
 */
public class MXFileCryptoStore implements IMXCryptoStore {
    private static final String LOG_TAG = "MXFileCryptoStore";

    private static final int MXFILE_CRYPTO_VERSION = 1;

    private static final String MXFILE_CRYPTO_STORE_FOLDER = "MXFileCryptoStore";

    private static final String MXFILE_CRYPTO_STORE_METADATA_FILE = "MXFileCryptoStore";
    private static final String MXFILE_CRYPTO_STORE_METADATA_FILE_TMP = "MXFileCryptoStore.tmp";

    private static final String MXFILE_CRYPTO_STORE_ACCOUNT_FILE = "account";
    private static final String MXFILE_CRYPTO_STORE_ACCOUNT_FILE_TMP = "account.tmp";

    private static final String MXFILE_CRYPTO_STORE_DEVICES_FOLDER = "devicesFolder";
    private static final String MXFILE_CRYPTO_STORE_DEVICES_FILE = "devices";
    private static final String MXFILE_CRYPTO_STORE_DEVICES_FILE_TMP = "devices.tmp";

    private static final String MXFILE_CRYPTO_STORE_TRACKING_STATUSES_FILE = "trackingStatuses";
    private static final String MXFILE_CRYPTO_STORE_TRACKING_STATUSES_FILE_TMP = "trackingStatuses.tmp";

    private static final String MXFILE_CRYPTO_STORE_ALGORITHMS_FILE = "roomsAlgorithms";
    private static final String MXFILE_CRYPTO_STORE_ALGORITHMS_FILE_TMP = "roomsAlgorithms.tmp";

    private static final String MXFILE_CRYPTO_STORE_OLM_SESSIONS_FILE = "sessions";
    private static final String MXFILE_CRYPTO_STORE_OLM_SESSIONS_FILE_TMP = "sessions.tmp";
    private static final String MXFILE_CRYPTO_STORE_OLM_SESSIONS_FOLDER = "olmSessionsFolder";

    private static final String MXFILE_CRYPTO_STORE_INBOUND_GROUP_SESSSIONS_FILE = "inboundGroupSessions";
    private static final String MXFILE_CRYPTO_STORE_INBOUND_GROUP_SESSSIONS_FILE_TMP = "inboundGroupSessions.tmp";
    private static final String MXFILE_CRYPTO_STORE_INBOUND_GROUP_SESSSIONS_FOLDER = "inboundGroupSessionsFolder";

    // The credentials used for this store
    private Credentials mCredentials;

    // Meta data about the store
    private MXFileCryptoStoreMetaData2 mMetaData;

    // The olm account
    private OlmAccount mOlmAccount;

    // All users devices keys
    private MXUsersDevicesMap<MXDeviceInfo> mUsersDevicesInfoMap;
    private final Object mUsersDevicesInfoMapLock = new Object();

    // The algorithms used in rooms
    private HashMap<String, String> mRoomsAlgorithms;

    // the tracking statuses
    private HashMap<String, Integer> mTrackingStatuses;

    // The olm sessions (<device identity key> -> (<olm session id> -> <olm session>)
    private HashMap<String /*deviceKey*/,
            HashMap<String /*olmSessionId*/, OlmSession>> mOlmSessions;
    private static final Object mOlmSessionsLock = new Object();

    // The inbound group megolm sessions (<senderKey> -> (<inbound group session id> -> <inbound group megolm session>)
    private HashMap<String /*senderKey*/,
            HashMap<String /*inboundGroupSessionId*/, MXOlmInboundGroupSession2>> mInboundGroupSessions;
    private final Object mInboundGroupSessionsLock = new Object();


    // The path of the MXFileCryptoStore folder
    private File mStoreFile;

    private File mMetaDataFile;
    private File mMetaDataFileTmp;

    private File mAccountFile;
    private File mAccountFileTmp;

    private File mDevicesFolder;
    private File mDevicesFile;
    private File mDevicesFileTmp;

    private File mAlgorithmsFile;
    private File mAlgorithmsFileTmp;

    private File mTrackingStatusesFile;
    private File mTrackingStatusesFileTmp;

    private File mOlmSessionsFile;
    private File mOlmSessionsFileTmp;
    private File mOlmSessionsFolder;

    private File mInboundGroupSessionsFile;
    private File mInboundGroupSessionsFileTmp;
    private File mInboundGroupSessionsFolder;

    // tell if the store is corrupted
    private boolean mIsCorrupted = false;

    // tell if the store is ready
    private boolean mIsReady = false;

    public MXFileCryptoStore() {
    }

    @Override
    public void initWithCredentials(Context context, Credentials credentials) {
        mCredentials = credentials;

        mStoreFile = new File(new File(context.getApplicationContext().getFilesDir(), MXFILE_CRYPTO_STORE_FOLDER), mCredentials.userId);

        mMetaDataFile = new File(mStoreFile, MXFILE_CRYPTO_STORE_METADATA_FILE);
        mMetaDataFileTmp = new File(mStoreFile, MXFILE_CRYPTO_STORE_METADATA_FILE_TMP);

        mAccountFile = new File(mStoreFile, MXFILE_CRYPTO_STORE_ACCOUNT_FILE);
        mAccountFileTmp = new File(mStoreFile, MXFILE_CRYPTO_STORE_ACCOUNT_FILE_TMP);

        mDevicesFolder = new File(mStoreFile, MXFILE_CRYPTO_STORE_DEVICES_FOLDER);
        mDevicesFile = new File(mStoreFile, MXFILE_CRYPTO_STORE_DEVICES_FILE);
        mDevicesFileTmp = new File(mStoreFile, MXFILE_CRYPTO_STORE_DEVICES_FILE_TMP);

        mAlgorithmsFile = new File(mStoreFile, MXFILE_CRYPTO_STORE_ALGORITHMS_FILE);
        mAlgorithmsFileTmp = new File(mStoreFile, MXFILE_CRYPTO_STORE_ALGORITHMS_FILE_TMP);

        mTrackingStatusesFile = new File(mStoreFile, MXFILE_CRYPTO_STORE_TRACKING_STATUSES_FILE);
        mTrackingStatusesFileTmp = new File(mStoreFile, MXFILE_CRYPTO_STORE_TRACKING_STATUSES_FILE_TMP);

        // backward compatibility : the sessions used to be stored in an unique file
        mOlmSessionsFile = new File(mStoreFile, MXFILE_CRYPTO_STORE_OLM_SESSIONS_FILE);
        mOlmSessionsFileTmp = new File(mStoreFile, MXFILE_CRYPTO_STORE_OLM_SESSIONS_FILE_TMP);
        // each session is now stored in a dedicated file
        mOlmSessionsFolder = new File(mStoreFile, MXFILE_CRYPTO_STORE_OLM_SESSIONS_FOLDER);

        mInboundGroupSessionsFile = new File(mStoreFile, MXFILE_CRYPTO_STORE_INBOUND_GROUP_SESSSIONS_FILE);
        mInboundGroupSessionsFileTmp = new File(mStoreFile, MXFILE_CRYPTO_STORE_INBOUND_GROUP_SESSSIONS_FILE_TMP);
        mInboundGroupSessionsFolder = new File(mStoreFile, MXFILE_CRYPTO_STORE_INBOUND_GROUP_SESSSIONS_FOLDER);

        // Build default metadata
        if ((null == mMetaData)
                && (null != credentials.homeServer)
                && (null != credentials.userId)
                && (null != credentials.accessToken)) {
            mMetaData = new MXFileCryptoStoreMetaData2(mCredentials.userId, mCredentials.deviceId, MXFILE_CRYPTO_VERSION);
        }

        mUsersDevicesInfoMap = new MXUsersDevicesMap<>();
        mRoomsAlgorithms = new HashMap<>();
        mTrackingStatuses = new HashMap<>();
        mOlmSessions = new HashMap<>();
        mInboundGroupSessions = new HashMap<>();
    }

    @Override
    public boolean hasData() {
        boolean result = mStoreFile.exists();

        if (result) {
            // User ids match. Check device ids
            loadMetaData();

            if (null != mMetaData) {
                result = TextUtils.isEmpty(mMetaData.mDeviceId) ||
                        TextUtils.equals(mCredentials.deviceId, mMetaData.mDeviceId);
            }
        }

        return result;
    }


    @Override
    public boolean isCorrupted() {
        return mIsCorrupted;
    }

    @Override
    public void deleteStore() {
        // delete the dedicated directories
        try {
            ContentUtils.deleteDirectory(mStoreFile);
        } catch (Exception e) {
            Log.e(LOG_TAG, "deleteStore failed " + e.getMessage());
        }
    }

    @Override
    public void open() {
        if (mIsReady) {
            Log.e(LOG_TAG, "## open() : the store is already opened");
        } else {
            mMetaData = null;

            loadMetaData();

            // Check if
            if (null == mMetaData) {
                resetData();
            }
            // Check store version
            else if (MXFILE_CRYPTO_VERSION != mMetaData.mVersion) {
                Log.e(LOG_TAG, "## open() : New MXFileCryptoStore version detected");
                resetData();
            }
            // Check credentials
            // The device id may not have been provided in credentials.
            // Check it only if provided, else trust the stored one.
            else if (!TextUtils.equals(mMetaData.mUserId, mCredentials.userId) ||
                    ((null != mCredentials.deviceId) && !TextUtils.equals(mCredentials.deviceId, mMetaData.mDeviceId))
                    ) {
                Log.e(LOG_TAG, "## open() : Credentials do not match");
                resetData();
            }

            // If metaData is still defined, we can load rooms data
            if (null != mMetaData) {
                preloadCryptoData();
            }

            // Else, if credentials is valid, create and store it
            if ((null == mMetaData)
                    && (null != mCredentials.homeServer)
                    && (null != mCredentials.userId)
                    && (null != mCredentials.accessToken)) {
                mMetaData = new MXFileCryptoStoreMetaData2(mCredentials.userId, mCredentials.deviceId, MXFILE_CRYPTO_VERSION);
                saveMetaData();
            }

            mIsReady = true;
        }
    }

    @Override
    public void storeDeviceId(String deviceId) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeDeviceId() : the store is not ready");
            return;
        }

        mMetaData.mDeviceId = deviceId;
        saveMetaData();
    }

    @Override
    public String getDeviceId() {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getDeviceId() : the store is not ready");
            return null;
        }

        return mMetaData.mDeviceId;
    }

    /**
     * Store a serializable object into a dedicated file.
     *
     * @param object      the object to write.
     * @param folder      the folder
     * @param filename    the filename
     * @param description the object description
     */
    private void storeObject(Object object, File folder, String filename, String description) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeObject() : the store is not ready");
            return;
        }

        // sanity checks
        if ((null == object) || (null == folder) || (null == filename)) {
            Log.e(LOG_TAG, "## storeObject() : invalid parameters");
            return;
        }

        // ensure that the folder exists
        // it should always exist but it happened
        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                Log.e(LOG_TAG, "Cannot create the folder " + folder);
            }
        }

        storeObject(object, new File(folder, filename), description);
    }

    /**
     * Store a serializable object into a dedicated file.
     *
     * @param object      the object to write.
     * @param file        the file
     * @param description the object description
     */
    private void storeObject(Object object, File file, String description) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeObject() : the store is not ready");
            return;
        }

        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            Log.e(LOG_TAG, "## storeObject() : should not be called in the UI thread " + description);
        }

        synchronized (LOG_TAG) {
            try {
                long t0 = System.currentTimeMillis();

                if (file.exists()) {
                    file.delete();
                }

                FileOutputStream fos = new FileOutputStream(file);
                GZIPOutputStream gz = new GZIPOutputStream(fos);
                ObjectOutputStream out = new ObjectOutputStream(gz);

                out.writeObject(object);
                out.close();

                Log.d(LOG_TAG, "## storeObject () : " + description + " done in " + (System.currentTimeMillis() - t0) + " ms");
            } catch (OutOfMemoryError oom) {
                Log.e(LOG_TAG, "storeObject failed : " + description + " -- " + oom.getMessage());
            } catch (Exception e) {
                Log.e(LOG_TAG, "storeObject failed : " + description + " -- " + e.getMessage());
            }
        }
    }

    /**
     * Save the metadata into the crypto file store
     */
    private void saveMetaData() {
        if (mMetaDataFileTmp.exists()) {
            mMetaDataFileTmp.delete();
        }

        if (mMetaDataFile.exists()) {
            mMetaDataFile.renameTo(mMetaDataFileTmp);
        }

        storeObject(mMetaData, mMetaDataFile, "saveMetaData");

        if (mMetaDataFileTmp.exists()) {
            mMetaDataFileTmp.delete();
        }
    }

    @Override
    public void storeAccount(OlmAccount account) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeAccount() : the store is not ready");
            return;
        }

        mOlmAccount = account;

        if (mAccountFileTmp.exists()) {
            mAccountFileTmp.delete();
        }

        if (mAccountFile.exists()) {
            mAccountFile.renameTo(mAccountFileTmp);
        }

        storeObject(mOlmAccount, mAccountFile, "storeAccount");

        if (mAccountFileTmp.exists()) {
            mAccountFileTmp.delete();
        }
    }

    @Override
    public OlmAccount getAccount() {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getAccount() : the store is not ready");
            return null;
        }

        return mOlmAccount;
    }

    @Override
    public void storeDeviceAnnounced() {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeDeviceAnnounced() : the store is not ready");
            return;
        }

        mMetaData.mDeviceAnnounced = true;
        saveMetaData();
    }

    @Override
    public boolean deviceAnnounced() {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## deviceAnnounced() : the store is not ready");
            return false;
        }

        return mMetaData.mDeviceAnnounced;
    }

    /**
     * Load the user devices from the filesystem
     * if it is not yet done.
     *
     * @param userId the user id.
     */
    private void loadUserDevices(String userId) {
        if (!TextUtils.isEmpty(userId)) {
            boolean alreadyDone;

            synchronized (mUsersDevicesInfoMapLock) {
                alreadyDone = mUsersDevicesInfoMap.getMap().containsKey(userId);
            }

            if (!alreadyDone) {
                File devicesFile = new File(mDevicesFolder, userId);

                if (devicesFile.exists()) {
                    long t0 = System.currentTimeMillis();

                    // clear the corrupted flag
                    mIsCorrupted = false;

                    Object devicesMapAsVoid = loadObject(devicesFile, "load devices of " + userId);

                    if (null != devicesMapAsVoid) {
                        try {
                            synchronized (mUsersDevicesInfoMapLock) {
                                mUsersDevicesInfoMap.setObjects((Map<String, MXDeviceInfo>) devicesMapAsVoid, userId);
                            }
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## loadUserDevices : mUsersDevicesInfoMap.setObjects failed " + e.getMessage());
                            mIsCorrupted = true;
                        }
                    }

                    // something was wrong (loadObject set this boolean)
                    if (mIsCorrupted) {
                        Log.e(LOG_TAG, "## loadUserDevices : failed to load the device of " + userId);

                        // delete the corrupted file
                        devicesFile.delete();
                        // it is not a blocking thing
                        mIsCorrupted = false;
                    } else {
                        Log.d(LOG_TAG, "## loadUserDevices : Load the devices of " + userId + " in " + (System.currentTimeMillis() - t0) + "ms");
                    }
                }
            }
        }
    }

    @Override
    public void storeUserDevice(String userId, MXDeviceInfo device) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeUserDevice() : the store is not ready");
            return;
        }

        final HashMap<String, MXDeviceInfo> devicesMap;

        loadUserDevices(userId);

        synchronized (mUsersDevicesInfoMapLock) {
            mUsersDevicesInfoMap.setObject(device, userId, device.deviceId);
            devicesMap = new HashMap<>(mUsersDevicesInfoMap.getMap().get(userId));
        }

        storeObject(devicesMap, mDevicesFolder, userId, "storeUserDevice " + userId + " with " + devicesMap.size() + " devices");
    }

    @Override
    public MXDeviceInfo getUserDevice(String deviceId, String userId) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getUserDevice() : the store is not ready");
            return null;
        }

        MXDeviceInfo deviceInfo;

        loadUserDevices(userId);

        synchronized (mUsersDevicesInfoMapLock) {
            deviceInfo = mUsersDevicesInfoMap.getObject(deviceId, userId);
        }

        return deviceInfo;
    }

    @Override
    public void storeUserDevices(String userId, Map<String, MXDeviceInfo> devices) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeUserDevices() : the store is not ready");
            return;
        }

        synchronized (mUsersDevicesInfoMapLock) {
            mUsersDevicesInfoMap.setObjects(devices, userId);
        }

        storeObject(devices, mDevicesFolder, userId, "storeUserDevice " + userId);
    }

    @Override
    public Map<String, MXDeviceInfo> getUserDevices(String userId) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getUserDevices() : the store is not ready");
            return null;
        }

        if (null != userId) {
            Map<String, MXDeviceInfo> devicesMap;

            loadUserDevices(userId);

            synchronized (mUsersDevicesInfoMapLock) {
                devicesMap = mUsersDevicesInfoMap.getMap().get(userId);
            }

            return devicesMap;
        } else {
            return null;
        }
    }

    @Override
    public void storeRoomAlgorithm(String roomId, String algorithm) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeRoomAlgorithm() : the store is not ready");
            return;
        }

        if ((null != roomId) && (null != algorithm)) {
            mRoomsAlgorithms.put(roomId, algorithm);

            // delete the previous tmp
            if (mAlgorithmsFileTmp.exists()) {
                mAlgorithmsFileTmp.delete();
            }

            // copy the existing file
            if (mAlgorithmsFile.exists()) {
                mAlgorithmsFile.renameTo(mAlgorithmsFileTmp);
            }

            storeObject(mRoomsAlgorithms, mAlgorithmsFile, "storeAlgorithmForRoom - in background");

            // remove the tmp file
            if (mAlgorithmsFileTmp.exists()) {
                mAlgorithmsFileTmp.delete();
            }
        }
    }

    @Override
    public String getRoomAlgorithm(String roomId) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getRoomAlgorithm() : the store is not ready");
            return null;
        }

        if (null != roomId) {
            return mRoomsAlgorithms.get(roomId);
        }

        return null;
    }

    @Override
    public int getDeviceTrackingStatus(String userId, int defaultValue) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getDeviceTrackingStatus() : the store is not ready");
            return defaultValue;
        }

        if ((null != userId) && mTrackingStatuses.containsKey(userId)) {
            return mTrackingStatuses.get(userId);
        } else {
            return defaultValue;
        }
    }

    @Override
    public Map<String, Integer> getDeviceTrackingStatuses() {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getDeviceTrackingStatuses() : the store is not ready");
            return null;
        }

        return new HashMap<>(mTrackingStatuses);
    }

    /**
     * Save the tracking statuses map
     */
    private void saveDeviceTrackingStatuses() {
        // delete the previous tmp
        if (mTrackingStatusesFileTmp.exists()) {
            mTrackingStatusesFileTmp.delete();
        }

        // copy the existing file
        if (mTrackingStatusesFile.exists()) {
            mTrackingStatusesFile.renameTo(mTrackingStatusesFileTmp);
        }

        storeObject(mTrackingStatuses, mTrackingStatusesFile, "saveDeviceTrackingStatus - in background");

        // remove the tmp file
        if (mTrackingStatusesFileTmp.exists()) {
            mTrackingStatusesFileTmp.delete();
        }
    }

    @Override
    public void saveDeviceTrackingStatuses(Map<String, Integer> deviceTrackingStatuses) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## saveDeviceTrackingStatuses() : the store is not ready");
            return;
        }

        mTrackingStatuses.clear();
        mTrackingStatuses.putAll(deviceTrackingStatuses);
        saveDeviceTrackingStatuses();
    }

    @Override
    public void storeSession(final OlmSession olmSession, final String deviceKey) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeSession() : the store is not ready");
            return;
        }

        String sessionIdentifier = null;

        if (null != olmSession) {
            try {
                sessionIdentifier = olmSession.sessionIdentifier();
            } catch (Exception e) {
                Log.e(LOG_TAG, "## storeSession : session.sessionIdentifier() failed " + e.getMessage());
            }
        }

        if ((null != deviceKey) && (null != sessionIdentifier)) {
            synchronized (mOlmSessionsLock) {
                if (!mOlmSessions.containsKey(deviceKey)) {
                    mOlmSessions.put(deviceKey, new HashMap<String, OlmSession>());
                }

                OlmSession prevOlmSession = mOlmSessions.get(deviceKey).get(sessionIdentifier);

                // test if the session is a new one
                if (olmSession != prevOlmSession) {
                    if (null != prevOlmSession) {
                        prevOlmSession.releaseSession();
                    }
                    mOlmSessions.get(deviceKey).put(sessionIdentifier, olmSession);
                }
            }

            final File keyFolder = new File(mOlmSessionsFolder, encodeFilename(deviceKey));

            if (!keyFolder.exists()) {
                keyFolder.mkdir();
            }

            storeObject(olmSession, keyFolder, encodeFilename(sessionIdentifier), "Store olm session " + deviceKey + " " + sessionIdentifier);
        }
    }

    @Override
    public Map<String, OlmSession> getDeviceSessions(String deviceKey) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeSession() : the store is not ready");
            return null;
        }

        if (null != deviceKey) {
            Map<String, OlmSession> map;

            synchronized (mOlmSessionsLock) {
                map = mOlmSessions.get(deviceKey);
            }

            return map;
        }

        return null;
    }

    @Override
    public void removeInboundGroupSession(String sessionId, String senderKey) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## removeInboundGroupSession() : the store is not ready");
            return;
        }

        if ((null != sessionId) && (null != senderKey)) {
            synchronized (mInboundGroupSessionsLock) {
                if (mInboundGroupSessions.containsKey(senderKey)) {
                    MXOlmInboundGroupSession2 session = mInboundGroupSessions.get(senderKey).get(sessionId);

                    if (null != session) {
                        mInboundGroupSessions.get(senderKey).remove(sessionId);

                        File senderKeyFolder = new File(mInboundGroupSessionsFolder, encodeFilename(session.mSenderKey));

                        if (senderKeyFolder.exists()) {
                            File inboundSessionFile = new File(senderKeyFolder, encodeFilename(sessionId));

                            if (!inboundSessionFile.delete()) {
                                Log.e(LOG_TAG, "## removeInboundGroupSession() : fail to remove the sessionid " + sessionId);
                            }
                        }

                        // release the memory
                        session.mSession.releaseSession();
                    }
                }
            }
        }
    }

    @Override
    public void storeInboundGroupSession(final MXOlmInboundGroupSession2 session) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## storeInboundGroupSession() : the store is not ready");
            return;
        }

        String sessionIdentifier = null;

        if ((null != session) && (null != session.mSenderKey) && (null != session.mSession)) {
            try {
                sessionIdentifier = session.mSession.sessionIdentifier();
            } catch (Exception e) {
                Log.e(LOG_TAG, "## storeInboundGroupSession() : sessionIdentifier failed " + e.getMessage());
            }
        }

        if (null != sessionIdentifier) {
            synchronized (mInboundGroupSessionsLock) {
                if (!mInboundGroupSessions.containsKey(session.mSenderKey)) {
                    mInboundGroupSessions.put(session.mSenderKey, new HashMap<String, MXOlmInboundGroupSession2>());
                }

                MXOlmInboundGroupSession2 curSession = mInboundGroupSessions.get(session.mSenderKey).get(sessionIdentifier);

                if (curSession != session) {
                    // release memory
                    if (null != curSession) {
                        curSession.mSession.releaseSession();
                    }
                    // update the map
                    mInboundGroupSessions.get(session.mSenderKey).put(sessionIdentifier, session);
                }
            }

            Log.d(LOG_TAG, "## storeInboundGroupSession() : store session " + sessionIdentifier);

            File senderKeyFolder = new File(mInboundGroupSessionsFolder, encodeFilename(session.mSenderKey));

            if (!senderKeyFolder.exists()) {
                senderKeyFolder.mkdir();
            }

            storeObject(session, senderKeyFolder, encodeFilename(sessionIdentifier), "storeInboundGroupSession - in background");
        }
    }

    @Override
    public MXOlmInboundGroupSession2 getInboundGroupSession(String sessionId, String senderKey) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getInboundGroupSession() : the store is not ready");
            return null;
        }

        if ((null != sessionId) && (null != senderKey) && mInboundGroupSessions.containsKey(senderKey)) {
            MXOlmInboundGroupSession2 session;

            synchronized (mInboundGroupSessionsLock) {
                session = mInboundGroupSessions.get(senderKey).get(sessionId);
            }

            return session;
        }
        return null;
    }

    @Override
    public List<MXOlmInboundGroupSession2> getInboundGroupSessions() {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getInboundGroupSessions() : the store is not ready");
            return null;
        }

        ArrayList<MXOlmInboundGroupSession2> inboundGroupSessions = new ArrayList<>();

        synchronized (mInboundGroupSessionsLock) {
            for (String senderKey : mInboundGroupSessions.keySet()) {
                inboundGroupSessions.addAll(mInboundGroupSessions.get(senderKey).values());
            }
        }

        return inboundGroupSessions;
    }

    @Override
    public void close() {
        // release JNI objects
        ArrayList<OlmSession> olmSessions = new ArrayList<>();
        Collection<HashMap<String, OlmSession>> sessionValues = mOlmSessions.values();

        for (HashMap<String, OlmSession> value : sessionValues) {
            olmSessions.addAll(value.values());
        }

        for (OlmSession olmSession : olmSessions) {
            olmSession.releaseSession();
        }
        mOlmSessions.clear();

        ArrayList<MXOlmInboundGroupSession2> groupSessions = new ArrayList<>();
        Collection<HashMap<String, MXOlmInboundGroupSession2>> groupSessionsValues = mInboundGroupSessions.values();

        for (HashMap<String, MXOlmInboundGroupSession2> map : groupSessionsValues) {
            groupSessions.addAll(map.values());
        }

        for (MXOlmInboundGroupSession2 groupSession : groupSessions) {
            if (null != groupSession.mSession) {
                groupSession.mSession.releaseSession();
            }
        }
        mInboundGroupSessions.clear();
    }

    @Override
    public void setGlobalBlacklistUnverifiedDevices(boolean block) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## setGlobalBlacklistUnverifiedDevices() : the store is not ready");
            return;
        }

        mMetaData.mGlobalBlacklistUnverifiedDevices = block;
        saveMetaData();
    }

    @Override
    public boolean getGlobalBlacklistUnverifiedDevices() {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getGlobalBlacklistUnverifiedDevices() : the store is not ready");
            return false;
        }

        return mMetaData.mGlobalBlacklistUnverifiedDevices;
    }

    @Override
    public void setRoomsListBlacklistUnverifiedDevices(List<String> roomIds) {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## setRoomsListBlacklistUnverifiedDevices() : the store is not ready");
            return;
        }

        mMetaData.mBlacklistUnverifiedDevicesRoomIdsList = roomIds;
        saveMetaData();
    }

    @Override
    public List<String> getRoomsListBlacklistUnverifiedDevices() {
        if (!mIsReady) {
            Log.e(LOG_TAG, "## getRoomsListBlacklistUnverifiedDevices() : the store is not ready");
            return null;
        }

        if (null == mMetaData.mBlacklistUnverifiedDevicesRoomIdsList) {
            return new ArrayList<>();
        } else {
            return new ArrayList<>(mMetaData.mBlacklistUnverifiedDevicesRoomIdsList);
        }
    }

    /**
     * Reset the crypto store data
     */
    private void resetData() {
        close();

        // ensure there is background writings while deleting the store
        synchronized (LOG_TAG) {
            deleteStore();
        }

        if (!mStoreFile.exists()) {
            mStoreFile.mkdirs();
        }

        if (!mDevicesFolder.exists()) {
            mDevicesFolder.mkdirs();
        }

        if (!mOlmSessionsFolder.exists()) {
            mOlmSessionsFolder.mkdir();
        }

        if (!mInboundGroupSessionsFolder.exists()) {
            mInboundGroupSessionsFolder.mkdirs();
        }

        mMetaData = null;
    }

    /**
     * Load a file from the crypto store
     *
     * @param file        the file to read
     * @param description the operation description
     * @return the read object, null if it fails
     */
    private Object loadObject(File file, String description) {
        Object object = null;


        if (file.exists()) {
            try {
                // the files are now zipped to reduce saving time
                FileInputStream fis = new FileInputStream(file);
                GZIPInputStream gz = new GZIPInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(gz);
                object = ois.readObject();
                ois.close();
            } catch (Exception e) {
                Log.e(LOG_TAG, description + "failed : " + e.getMessage() + " step 1");

                // if the zip deflating fails, try to use the former file saving method
                try {
                    FileInputStream fis2 = new FileInputStream(file);
                    ObjectInputStream out = new ObjectInputStream(fis2);

                    object = out.readObject();
                    fis2.close();
                } catch (Exception subEx) {
                    // warn that some file loading fails
                    mIsCorrupted = true;
                    Log.e(LOG_TAG, description + "failed : " + subEx.getMessage() + " step 2");
                }
            }
        }
        return object;
    }


    /**
     * Load the metadata from the store
     */
    private void loadMetaData() {
        Object metadataAsVoid;

        if (mMetaDataFileTmp.exists()) {
            metadataAsVoid = loadObject(mMetaDataFileTmp, "loadMetadata");
        } else {
            metadataAsVoid = loadObject(mMetaDataFile, "loadMetadata");
        }

        if (null != metadataAsVoid) {
            try {
                if (metadataAsVoid instanceof MXFileCryptoStoreMetaData2) {
                    mMetaData = (MXFileCryptoStoreMetaData2) metadataAsVoid;
                } else {
                    mMetaData = new MXFileCryptoStoreMetaData2((MXFileCryptoStoreMetaData) metadataAsVoid);
                }
            } catch (Exception e) {
                mIsCorrupted = true;
                Log.e(LOG_TAG, "## loadMetadata() : metadata has been corrupted " + e.getMessage());
            }
        }
    }

    /**
     * Preload the crypto data
     */
    private void preloadCryptoData() {
        Log.d(LOG_TAG, "## preloadCryptoData() starts");

        long t0 = System.currentTimeMillis();
        Object olmAccountAsVoid;

        if (mAccountFileTmp.exists()) {
            olmAccountAsVoid = loadObject(mAccountFileTmp, "preloadCryptoData - mAccountFile - tmp");
        } else {
            olmAccountAsVoid = loadObject(mAccountFile, "preloadCryptoData - mAccountFile");
        }

        if (null != olmAccountAsVoid) {
            try {
                mOlmAccount = (OlmAccount) olmAccountAsVoid;
            } catch (Exception e) {
                mIsCorrupted = true;
                Log.e(LOG_TAG, "## preloadCryptoData() - invalid mAccountFile " + e.getMessage());
            }
        }

        Log.d(LOG_TAG, "## preloadCryptoData() : load mOlmAccount in " + (System.currentTimeMillis() - t0) + " ms");

        // previous store format
        if (!mDevicesFolder.exists()) {
            Object usersDevicesInfoMapAsVoid;

            // if the tmp exists, it means that the latest file backup has been killed / stopped
            if (mDevicesFileTmp.exists()) {
                usersDevicesInfoMapAsVoid = loadObject(mDevicesFileTmp, "preloadCryptoData - mUsersDevicesInfoMap - tmp");
            } else {
                usersDevicesInfoMapAsVoid = loadObject(mDevicesFile, "preloadCryptoData - mUsersDevicesInfoMap");
            }

            if (null != usersDevicesInfoMapAsVoid) {
                try {
                    MXUsersDevicesMap objectAsMap = (MXUsersDevicesMap) usersDevicesInfoMapAsVoid;
                    mUsersDevicesInfoMap = new MXUsersDevicesMap<>(objectAsMap.getMap());
                } catch (Exception e) {
                    mIsCorrupted = true;
                    Log.e(LOG_TAG, "## preloadCryptoData() - invalid mUsersDevicesInfoMap " + e.getMessage());
                }
            } else {
                mIsCorrupted = false;
            }

            mDevicesFolder.mkdirs();

            if (null != mUsersDevicesInfoMap) {
                HashMap<String, HashMap<String, MXDeviceInfo>> map = mUsersDevicesInfoMap.getMap();

                Set<String> userIds = map.keySet();

                for (String userId : userIds) {
                    storeObject(map.get(userId), mDevicesFolder, userId, "convert devices map of " + userId);
                }

                mDevicesFileTmp.delete();
                mDevicesFile.delete();
            }
        } else {
            // the user devices are loaded on demand
            mUsersDevicesInfoMap = new MXUsersDevicesMap<>();
        }

        long t2 = System.currentTimeMillis();
        int algoSize = 0;

        Object algorithmsAsVoid;

        if (mAlgorithmsFileTmp.exists()) {
            algorithmsAsVoid = loadObject(mAlgorithmsFileTmp, "preloadCryptoData - mRoomsAlgorithms - tmp");
        } else {
            algorithmsAsVoid = loadObject(mAlgorithmsFile, "preloadCryptoData - mRoomsAlgorithms");
        }

        if (null != algorithmsAsVoid) {
            try {
                Map<String, String> algorithmsMap = (Map<String, String>) algorithmsAsVoid;
                mRoomsAlgorithms = new HashMap<>(algorithmsMap);
                algoSize = mRoomsAlgorithms.size();
            } catch (Exception e) {
                mIsCorrupted = true;
                Log.e(LOG_TAG, "## preloadCryptoData() - invalid mAlgorithmsFile " + e.getMessage());
            }
        }
        Log.d(LOG_TAG, "## preloadCryptoData() : load mRoomsAlgorithms (" + algoSize + " algos) in " + (System.currentTimeMillis() - t2) + " ms");

        Object trackingStatusesAsVoid;

        if (mTrackingStatusesFileTmp.exists()) {
            trackingStatusesAsVoid = loadObject(mTrackingStatusesFileTmp, "preloadCryptoData - mTrackingStatuses - tmp");
        } else {
            trackingStatusesAsVoid = loadObject(mTrackingStatusesFile, "preloadCryptoData - mTrackingStatuses");
        }

        if (null != trackingStatusesAsVoid) {
            try {
                mTrackingStatuses = new HashMap<>((Map<String, Integer>) trackingStatusesAsVoid);
            } catch (Exception e) {
                Log.e(LOG_TAG, "## preloadCryptoData() - invalid mTrackingStatuses " + e.getMessage());
            }
        }

        if (mOlmSessionsFolder.exists()) {
            long t3 = System.currentTimeMillis();

            mOlmSessions = new HashMap<>();

            String[] olmSessionFiles = mOlmSessionsFolder.list();

            if (null != olmSessionFiles) {
                // build mOlmSessions for the file system
                for (int i = 0; i < olmSessionFiles.length; i++) {
                    String deviceKey = olmSessionFiles[i];

                    HashMap<String, OlmSession> olmSessionSubMap = new HashMap<>();

                    File sessionsDeviceFolder = new File(mOlmSessionsFolder, deviceKey);
                    String[] sessionIds = sessionsDeviceFolder.list();

                    if (null != sessionIds) {
                        for (int j = 0; j < sessionIds.length; j++) {
                            String sessionId = sessionIds[j];
                            OlmSession olmSession = (OlmSession) loadObject(new File(sessionsDeviceFolder, sessionId), "load the olmSession " + deviceKey + " " + sessionId);

                            if (null != olmSession) {
                                olmSessionSubMap.put(decodeFilename(sessionId), olmSession);
                            }
                        }
                    }
                    mOlmSessions.put(decodeFilename(deviceKey), olmSessionSubMap);
                }

                Log.d(LOG_TAG, "## preloadCryptoData() : load " + olmSessionFiles.length + " olmsessions in " + (System.currentTimeMillis() - t3) + " ms");
            }
        } else {
            Object olmSessionsAsVoid;

            if (mOlmSessionsFileTmp.exists()) {
                olmSessionsAsVoid = loadObject(mOlmSessionsFileTmp, "preloadCryptoData - mOlmSessions - tmp");
            } else {
                olmSessionsAsVoid = loadObject(mOlmSessionsFile, "preloadCryptoData - mOlmSessions");
            }

            if (null != olmSessionsAsVoid) {
                try {
                    Map<String, Map<String, OlmSession>> olmSessionMap = (Map<String, Map<String, OlmSession>>) olmSessionsAsVoid;

                    mOlmSessions = new HashMap<>();

                    for (String key : olmSessionMap.keySet()) {
                        mOlmSessions.put(key, new HashMap<>(olmSessionMap.get(key)));
                    }

                    // convert to the new format
                    if (!mOlmSessionsFolder.mkdir()) {
                        Log.e(LOG_TAG, "Cannot create the folder " + mOlmSessionsFolder);
                    }

                    for (String key : olmSessionMap.keySet()) {
                        Map<String, OlmSession> submap = olmSessionMap.get(key);

                        File submapFolder = new File(mOlmSessionsFolder, encodeFilename(key));

                        if (!submapFolder.mkdir()) {
                            Log.e(LOG_TAG, "Cannot create the folder " + submapFolder);
                        }

                        for (String sessionId : submap.keySet()) {
                            storeObject(submap.get(sessionId), submapFolder, encodeFilename(sessionId), "Convert olmSession " + key + " " + sessionId);
                        }
                    }
                } catch (Exception e) {
                    mIsCorrupted = true;
                    Log.e(LOG_TAG, "## preloadCryptoData() - invalid mSessionsFile " + e.getMessage());
                }

                mOlmSessionsFileTmp.delete();
                mOlmSessionsFile.delete();
            }
        }

        if (mInboundGroupSessionsFolder.exists()) {
            long t4 = System.currentTimeMillis();
            mInboundGroupSessions = new HashMap<>();

            int count = 0;

            String[] keysFolder = mInboundGroupSessionsFolder.list();

            if (null != keysFolder) {
                for (int i = 0; i < keysFolder.length; i++) {
                    File keyFolder = new File(mInboundGroupSessionsFolder, keysFolder[i]);

                    HashMap<String, MXOlmInboundGroupSession2> submap = new HashMap<>();

                    String[] sessionIds = keyFolder.list();

                    if (null != sessionIds) {
                        for (int j = 0; j < sessionIds.length; j++) {
                            File inboundSessionFile = new File(keyFolder, sessionIds[j]);
                            try {
                                Object inboundSessionAsVoid = loadObject(inboundSessionFile, "load inboundsession " + sessionIds[j] + " ");
                                MXOlmInboundGroupSession2 inboundSession;

                                if ((null != inboundSessionAsVoid) && (inboundSessionAsVoid instanceof MXOlmInboundGroupSession)) {
                                    inboundSession = new MXOlmInboundGroupSession2((MXOlmInboundGroupSession) inboundSessionAsVoid);
                                } else {
                                    inboundSession = (MXOlmInboundGroupSession2) inboundSessionAsVoid;
                                }

                                if (null != inboundSession) {
                                    submap.put(decodeFilename(sessionIds[j]), inboundSession);
                                } else {
                                    Log.e(LOG_TAG, "## preloadCryptoData() : delete " + inboundSessionFile);
                                    inboundSessionFile.delete();
                                    mIsCorrupted = false;
                                }
                                count++;
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "## preloadCryptoData() - invalid mInboundGroupSessions " + e.getMessage());
                            }
                        }
                    }

                    mInboundGroupSessions.put(decodeFilename(keysFolder[i]), submap);
                }
            }

            Log.d(LOG_TAG, "## preloadCryptoData() : load " + count + " inboundGroupSessions in " + (System.currentTimeMillis() - t4) + " ms");
        } else {
            Object inboundGroupSessionsAsVoid;

            if (mInboundGroupSessionsFileTmp.exists()) {
                inboundGroupSessionsAsVoid = loadObject(mInboundGroupSessionsFileTmp, "preloadCryptoData - mInboundGroupSessions - tmp");
            } else {
                inboundGroupSessionsAsVoid = loadObject(mInboundGroupSessionsFile, "preloadCryptoData - mInboundGroupSessions");
            }

            if (null != inboundGroupSessionsAsVoid) {
                try {
                    Map<String, Map<String, MXOlmInboundGroupSession2>> inboundGroupSessionsMap = (Map<String, Map<String, MXOlmInboundGroupSession2>>) inboundGroupSessionsAsVoid;

                    mInboundGroupSessions = new HashMap<>();

                    for (String key : inboundGroupSessionsMap.keySet()) {
                        mInboundGroupSessions.put(key, new HashMap<>(inboundGroupSessionsMap.get(key)));
                    }
                } catch (Exception e) {
                    mIsCorrupted = true;
                    Log.e(LOG_TAG, "## preloadCryptoData() - invalid mInboundGroupSessions " + e.getMessage());
                }

                if (!mInboundGroupSessionsFolder.mkdirs()) {
                    Log.e(LOG_TAG, "Cannot create the folder " + mInboundGroupSessionsFolder);
                }

                // convert to the new format
                for (String key : mInboundGroupSessions.keySet()) {
                    File keyFolder = new File(mInboundGroupSessionsFolder, encodeFilename(key));

                    if (!keyFolder.mkdirs()) {
                        Log.e(LOG_TAG, "Cannot create the folder " + keyFolder);
                    }

                    Map<String, MXOlmInboundGroupSession2> inboundMaps = mInboundGroupSessions.get(key);

                    for (String sessionId : inboundMaps.keySet()) {
                        storeObject(inboundMaps.get(sessionId), keyFolder, encodeFilename(sessionId), "Convert inboundsession");
                    }
                }
            }

            mInboundGroupSessionsFileTmp.delete();
            mInboundGroupSessionsFile.delete();
        }

        if ((null == mOlmAccount) && (mUsersDevicesInfoMap.getMap().size() > 0)) {
            mIsCorrupted = true;
            Log.e(LOG_TAG, "## preloadCryptoData() - there is no account but some devices are defined");
        }
    }

    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();

    /**
     * Encode the provided filename
     *
     * @param filename the filename to encode
     * @return the encoded filename
     */
    private static String encodeFilename(String filename) {
        if (null == filename) {
            return null;
        }

        try {
            byte[] bytes = filename.getBytes("UTF-8");
            char[] hexChars = new char[bytes.length * 2];

            for (int j = 0; j < bytes.length; j++) {
                int v = bytes[j] & 0xFF;
                hexChars[j * 2] = hexArray[v >>> 4];
                hexChars[j * 2 + 1] = hexArray[v & 0x0F];
            }
            return new String(hexChars);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## encodeFilename() - failed " + e.getMessage());
        }

        return filename;
    }

    /**
     * Decode an encoded filename.
     *
     * @param encodedFilename the encoded filename
     * @return the decodec filename
     */
    private static String decodeFilename(String encodedFilename) {
        if (null == encodedFilename) {
            return null;
        }

        int length = encodedFilename.length();

        byte[] bytes = new byte[length / 2];

        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(encodedFilename.charAt(i), 16) << 4)
                    + Character.digit(encodedFilename.charAt(i + 1), 16));
        }

        try {
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            Log.e(LOG_TAG, "## decodeFilename() - failed " + e.getMessage());
        }

        return encodedFilename;
    }
}
