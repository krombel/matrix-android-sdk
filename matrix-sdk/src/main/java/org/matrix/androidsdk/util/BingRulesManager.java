/* 
 * Copyright 2014 OpenMarket Ltd
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
package org.matrix.androidsdk.util;

import android.text.TextUtils;
import org.matrix.androidsdk.util.Log;

import org.matrix.androidsdk.MXDataHandler;
import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.listeners.IMXNetworkEventListener;
import org.matrix.androidsdk.network.NetworkConnectivityReceiver;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.client.BingRulesRestClient;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.Message;
import org.matrix.androidsdk.rest.model.bingrules.BingRule;
import org.matrix.androidsdk.rest.model.bingrules.BingRuleSet;
import org.matrix.androidsdk.rest.model.bingrules.BingRulesResponse;
import org.matrix.androidsdk.rest.model.bingrules.Condition;
import org.matrix.androidsdk.rest.model.bingrules.ContainsDisplayNameCondition;
import org.matrix.androidsdk.rest.model.bingrules.ContentRule;
import org.matrix.androidsdk.rest.model.bingrules.EventMatchCondition;
import org.matrix.androidsdk.rest.model.bingrules.RoomMemberCountCondition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Object that gets and processes bing rules from the server.
 */
public class BingRulesManager {
    private static final String LOG_TAG = "BingRulesManager";

    /**
     * Bing rule listener
     */
    public interface onBingRuleUpdateListener {
        /**
         * The manager succeeds to update the bingrule enable status.
         */
        void onBingRuleUpdateSuccess();

        /**
         * The manager fails to update the bingrule enable status.
         * @param errorMessage the error message.
         */
        void onBingRuleUpdateFailure(String errorMessage);
    }

    /**
     * Bing rules update
     */
    public interface onBingRulesUpdateListener  {
        /**
         * Warn that some bing rules have been updated
         */
        void onBingRulesUpdate();
    }

    // general members
    private BingRulesRestClient mApiClient;
    private MXSession mSession;
    private String mMyUserId;
    private MXDataHandler mDataHandler;

    // the rules set to apply
    private BingRuleSet mRulesSet = new BingRuleSet();

    // the rules list
    private List<BingRule> mRules = new ArrayList<>();

    // the default bing rule
    private BingRule mDefaultBingRule = new BingRule(true);

    // tell if the bing rules set is initialized
    private boolean mIsInitialized = false;

    // map to check if a room is "mention only"
    private Map<String, Boolean> mIsMentionOnlyMap = new HashMap<>();

    // network management
    private NetworkConnectivityReceiver mNetworkConnectivityReceiver;
    private IMXNetworkEventListener mNetworkListener;
    private ApiCallback<Void> mLoadRulesCallback;

    //  listener
    private Set<onBingRulesUpdateListener> mBingRulesUpdateListeners = new HashSet<>();

    /**
     * Constructor
     * @param session the session
     * @param networkConnectivityReceiver the network events listener
     */
    public BingRulesManager(MXSession session, NetworkConnectivityReceiver networkConnectivityReceiver) {
        mSession = session;
        mApiClient = session.getBingRulesApiClient();
        mMyUserId = session.getCredentials().userId;
        mDataHandler = session.getDataHandler();

        mNetworkListener = new IMXNetworkEventListener() {
            @Override
            public void onNetworkConnectionUpdate(boolean isConnected) {
                // mLoadRulesCallback is set when a loadRules failed
                // so when a network is available, trigger again loadRules
                if (isConnected && (null != mLoadRulesCallback)) {
                    loadRules(mLoadRulesCallback);
                }
            }
        };

        mNetworkConnectivityReceiver = networkConnectivityReceiver;
        networkConnectivityReceiver.addEventListener(mNetworkListener);
    }

    /**
     * @return true if it is ready to be used (i.e initializedà
     */
    public boolean isReady() {
        return mIsInitialized;
    }

    /**
     * Remove the network events listener.
     * This listener is only used to initialize the rules at application launch.
     */
    private void removeNetworkListener() {
        if ((null != mNetworkConnectivityReceiver) && (null != mNetworkListener)) {
            mNetworkConnectivityReceiver.removeEventListener(mNetworkListener);
            mNetworkConnectivityReceiver = null;
            mNetworkListener = null;
        }
    }

    /**
     * Add a listener
     * @param listener the listener
     */
    public void addBingRulesUpdateListener(onBingRulesUpdateListener listener) {
        if (null != listener) {
            mBingRulesUpdateListeners.add(listener);
        }
    }

    /**
     * remove a listener
     * @param listener the listener
     */
    public void removeBingRulesUpdateListener(onBingRulesUpdateListener listener) {
        if (null != listener) {
            mBingRulesUpdateListeners.remove(listener);
        }
    }

    /**
     * Some rules have been updated.
     */
    private void onBingRulesUpdate() {
        for(onBingRulesUpdateListener listener : mBingRulesUpdateListeners) {
            try {
                listener.onBingRulesUpdate();
            } catch (Exception e) {
                Log.e(LOG_TAG, "## onBingRulesUpdate() : onBingRulesUpdate failed " + e.getMessage());
            }
        }
    }

    /**
     * Load the bing rules from the server.
     * @param callback an async callback called when the rules are loaded
     */
    public void loadRules(final ApiCallback<Void> callback) {
        mLoadRulesCallback = null;

        Log.d(LOG_TAG, "## loadRules() : refresh the bing rules");
        mApiClient.getAllBingRules(new ApiCallback<BingRulesResponse>() {
            @Override
            public void onSuccess(BingRulesResponse info) {
                Log.d(LOG_TAG, "## loadRules() : succeeds");

                buildRules(info);
                mIsInitialized = true;

                if (callback != null) {
                    callback.onSuccess(null);
                }

                removeNetworkListener();
            }

            private void onError(String errorMessage) {
                Log.e(LOG_TAG, "## loadRules() : failed " + errorMessage);
                // the callback will be called when the request will succeed
                mLoadRulesCallback = callback;
            }

            @Override
            public void onNetworkError(Exception e) {
                onError(e.getMessage());
            }

            @Override
            public void onMatrixError(MatrixError e) {
                onError(e.getMessage());
            }

            @Override
            public void onUnexpectedError(Exception e) {
                onError(e.getMessage());
            }
        });
    }

    /**
     * Update the rule enable status.
     * @param kind the rule kind.
     * @param ruleId the rule ID.
     * @param status the new enable status.
     * @param callback an async callback.
     */
    public void updateEnableRuleStatus(String kind, String ruleId, boolean status, final ApiCallback<Void> callback) {
        mApiClient.updateEnableRuleStatus(kind, ruleId, status, callback);
    }

    /**
     * Returns whether a string contains an occurrence of another, as a standalone word, regardless of case.
     * @param subString the string to search for
     * @param longString the string to search in
     * @return whether a match was found
     */
    private static boolean caseInsensitiveFind(String subString, String longString) {
        // sanity check
        if (TextUtils.isEmpty(subString) || TextUtils.isEmpty(longString)) {
            return false;
        }

        boolean found = false;

        try {
            Pattern pattern = Pattern.compile("(\\W|^)" + subString + "(\\W|$)", Pattern.CASE_INSENSITIVE);
            found = pattern.matcher(longString).find();
        } catch (Exception e) {
            Log.e(LOG_TAG, "caseInsensitiveFind : pattern.matcher failed with " + e.getLocalizedMessage());
        }

        return found;
    }

    /**
     * Returns the first notifiable bing rule which fulfills its condition with this event.
     * @param event the event
     * @return the first matched bing rule, null if none
     */
    public BingRule fulfilledBingRule(Event event) {
        // sanity check
        if (null == event) {
            Log.e(LOG_TAG, "## fulfilledBingRule() : null event");
            return null;
        }

        if (!mIsInitialized) {
            Log.e(LOG_TAG, "## fulfilledBingRule() : not initialized");
            return null;
        }

        if (0 == mRules.size()) {
            Log.e(LOG_TAG, "## fulfilledBingRule() : no rules");
            return null;
        }

        // do not trigger notification for oneself messages
        if ((null != event.getSender()) && TextUtils.equals(event.getSender(), mMyUserId)) {
            return null;
        }

        String eventType = event.getType();

        // some types are not bingable
        if (TextUtils.equals(eventType, Event.EVENT_TYPE_PRESENCE)
                || TextUtils.equals(eventType, Event.EVENT_TYPE_TYPING)
                || TextUtils.equals(eventType, Event.EVENT_TYPE_REDACTION)
                || TextUtils.equals(eventType, Event.EVENT_TYPE_RECEIPT)
                || TextUtils.equals(eventType, Event.EVENT_TYPE_TAGS)) {
            return null;
        }

        // GA issue
        final ArrayList<BingRule> rules;

        synchronized (this) {
            rules = new ArrayList<>(mRules);
        }

        // Go down the rule list until we find a match
        for (BingRule bingRule : rules) {
            if (bingRule.isEnabled) {
                boolean isFullfilled = false;

                // some rules have no condition
                // so their ruleId defines the method
                if (BingRule.RULE_ID_CONTAIN_USER_NAME.equals(bingRule.ruleId) || BingRule.RULE_ID_CONTAIN_DISPLAY_NAME.equals(bingRule.ruleId)) {
                    if (Event.EVENT_TYPE_MESSAGE.equals(event.getType())) {
                        Message message = JsonUtils.toMessage(event.getContent());
                        MyUser myUser =  mSession.getMyUser();
                        String pattern = myUser.displayname;

                        if (BingRule.RULE_ID_CONTAIN_USER_NAME.equals(bingRule.ruleId)) {
                            if (mMyUserId.indexOf(":") >= 0) {
                                pattern = mMyUserId.substring(1, mMyUserId.indexOf(":"));
                            } else {
                                pattern = mMyUserId;
                            }
                        }

                        if (!TextUtils.isEmpty(pattern)) {
                            isFullfilled = caseInsensitiveFind(pattern, message.body);
                        }
                    }
                }  else if (BingRule.RULE_ID_FALLBACK.equals(bingRule.ruleId)) {
                    isFullfilled = true;
                } else {
                    // some default rules define conditions
                    // so use them instead of doing a custom treatment
                    // RULE_ID_ONE_TO_ONE_ROOM
                    // RULE_ID_SUPPRESS_BOTS_NOTIFICATIONS
                    isFullfilled = eventMatchesConditions(event, bingRule.conditions);
                }

                if (isFullfilled) {
                    return bingRule;
                }
            }
        }

        // no rules are fulfilled
        return null;
    }

    /**
     * Check if an event matches a conditions set
     * @param event the evnt to test
     * @param conditions the conditions set
     * @return true if the event matches all the conditions set.
     */
    private boolean eventMatchesConditions(Event event, List<Condition> conditions) {
        try {
            if ((conditions != null) && (event != null)) {
                for (Condition condition : conditions) {
                    if (condition instanceof EventMatchCondition) {
                        if (!((EventMatchCondition) condition).isSatisfied(event)) {
                            return false;
                        }
                    } else if (condition instanceof ContainsDisplayNameCondition) {
                        if (event.roomId != null) {
                            Room room = mDataHandler.getRoom(event.roomId, false);

                            // sanity checks
                            if ((null != room) && (null != room.getMember(mMyUserId))) {
                                // Best way to get your display name for now
                                String myDisplayName = room.getMember(mMyUserId).displayname;
                                if (!((ContainsDisplayNameCondition) condition).isSatisfied(event, myDisplayName)) {
                                    return false;
                                }
                            }
                        }
                    } else if (condition instanceof RoomMemberCountCondition) {
                        if (event.roomId != null) {
                            Room room = mDataHandler.getRoom(event.roomId, false);

                            if (!((RoomMemberCountCondition) condition).isSatisfied(room)) {
                                return false;
                            }
                        }
                    }
                    // FIXME: Handle device rules
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "## eventMatchesConditions() failed " + e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Build the internal build rules
     * @param bingRulesResponse the server request response.
     */
    public void buildRules(BingRulesResponse bingRulesResponse) {
        if (null != bingRulesResponse) {
            updateRules(bingRulesResponse.global);
            onBingRulesUpdate();
        }
    }

    /**
     * @return the rules set
     */
    public BingRuleSet pushRules() {
        return mRulesSet;
    }

    /**
     * Update mRulesSet with the new one.
     * @param ruleSet the new ruleSet to apply
     */
    private void updateRules(BingRuleSet ruleSet) {
        synchronized (this) {
            // clear the rules list
            // it is
            mRules.clear();

            // sanity check
            if (null == ruleSet) {
                mRulesSet = new BingRuleSet();
                return;
            }

            // Replace the list by ArrayList to be able to add/remove rules
            // Add the rule kind in each rule
            // Ensure that the null pointers are replaced by an empty list
            if (ruleSet.override != null) {
                ruleSet.override = new ArrayList<>(ruleSet.override);
                for (BingRule rule : ruleSet.override) {
                    rule.kind = BingRule.KIND_OVERRIDE;
                }
                mRules.addAll(ruleSet.override);
            } else {
                ruleSet.override = new ArrayList<>(ruleSet.override);
            }

            if (ruleSet.content != null) {
                ruleSet.content = new ArrayList<>(ruleSet.content);
                for (BingRule rule : ruleSet.content) {
                    rule.kind = BingRule.KIND_CONTENT;
                }
                addContentRules(ruleSet.content);
            } else {
                ruleSet.content = new ArrayList<>();
            }

            mIsMentionOnlyMap.clear();
            if (ruleSet.room != null) {
                ruleSet.room = new ArrayList<>(ruleSet.room);

                for (BingRule rule : ruleSet.room) {
                    rule.kind = BingRule.KIND_ROOM;
                }
                addRoomRules(ruleSet.room);
            } else {
                ruleSet.room = new ArrayList<>();
            }

            if (ruleSet.sender != null) {
                ruleSet.sender = new ArrayList<>(ruleSet.sender);

                for (BingRule rule : ruleSet.sender) {
                    rule.kind = BingRule.KIND_SENDER;
                }
                addSenderRules(ruleSet.sender);
            } else {
                ruleSet.sender = new ArrayList<>();
            }

            if (ruleSet.underride != null) {
                ruleSet.underride = new ArrayList<>(ruleSet.underride);
                for (BingRule rule : ruleSet.underride) {
                    rule.kind = BingRule.KIND_UNDERRIDE;
                }
                mRules.addAll(ruleSet.underride);
            } else {
                ruleSet.underride = new ArrayList<>();
            }

            mRulesSet = ruleSet;

            Log.d(LOG_TAG, "## updateRules() : has " + mRules.size() + " rules");
        }
    }

    /**
     * Create a content EventMatchConditions list from a ContentRules list
     * @param rules the ContentRules list
     */
    private void addContentRules(List<ContentRule> rules) {
        // sanity check
        if (null != rules) {
            for (ContentRule rule : rules) {
                EventMatchCondition condition = new EventMatchCondition();
                condition.kind = Condition.KIND_EVENT_MATCH;
                condition.key = "content.body";
                condition.pattern = rule.pattern;

                rule.addCondition(condition);

                mRules.add(rule);
            }
        }
    }

    /**
     * Create a room EventMatchConditions list from a BingRule list
     * @param rules the BingRule list
     */
    private void addRoomRules(List<BingRule> rules) {
        if (null != rules) {
            for (BingRule rule : rules) {
                EventMatchCondition condition = new EventMatchCondition();
                condition.kind = Condition.KIND_EVENT_MATCH;
                condition.key = "room_id";
                condition.pattern = rule.ruleId;

                rule.addCondition(condition);

                mRules.add(rule);
            }
        }
    }

    /**
     * Create a sender EventMatchConditions list from a BingRule list
     * @param rules the BingRule list
     */
    private void addSenderRules(List<BingRule> rules) {
        if (null != rules) {
            for (BingRule rule : rules) {
                EventMatchCondition condition = new EventMatchCondition();
                condition.kind = Condition.KIND_EVENT_MATCH;
                condition.key = "user_id";
                condition.pattern = rule.ruleId;

                rule.addCondition(condition);

                mRules.add(rule);
            }
        }
    }

    /**
     * Toogle a rule.
     * @param rule the bing rule to toggle.
     * @param listener the rule update listener.
     * @return the matched bing rule or null it doesn't exist.
     */
    public BingRule toggleRule(final BingRule rule, final onBingRuleUpdateListener listener) {
        if (null != rule) {
            updateEnableRuleStatus(rule.kind, rule.ruleId, !rule.isEnabled, new SimpleApiCallback<Void>() {
                @Override
                public void onSuccess(Void info) {
                    rule.isEnabled = !rule.isEnabled;
                    updateRules(mRulesSet);
                    onBingRulesUpdate();
                    if (listener != null) {
                        try {
                            listener.onBingRuleUpdateSuccess();
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## toggleRule : onBingRuleUpdateSuccess failed " + e.getMessage());
                        }
                    }
                }

                private void onError(String message) {
                    if (null != listener) {
                        try {
                            listener.onBingRuleUpdateFailure(message);
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## onError : onBingRuleUpdateFailure failed " + e.getMessage());
                        }
                    }
                }

                /**
                 * Called if there is a network error.
                 * @param e the exception
                 */
                @Override
                public void onNetworkError(Exception e) {
                    onError(e.getLocalizedMessage());
                }

                /**
                 * Called in case of a Matrix error.
                 * @param e the Matrix error
                 */
                @Override
                public void onMatrixError(MatrixError e)  {
                    onError(e.getLocalizedMessage());
                }

                /**
                 * Called for some other type of error.
                 * @param e the exception
                 */
                @Override
                public void onUnexpectedError(Exception e) {
                    onError(e.getLocalizedMessage());
                }
            });
        }

        return rule;
    }

    /**
     * Delete the rule.
     * @param rule the rule to delete.
     * @param listener the rule update listener.
     */
    public void deleteRule(final BingRule rule, final onBingRuleUpdateListener listener)  {
        // null case
        if (null == rule) {
            if (listener != null) {
                try {
                    listener.onBingRuleUpdateSuccess();
                } catch (Exception e) {
                    Log.e(LOG_TAG, "## deleteRule : onBingRuleUpdateSuccess failed " + e.getMessage());
                }
            }
            return;
        }

        mApiClient.deleteRule(rule.kind, rule.ruleId, new SimpleApiCallback<Void>() {
            @Override
            public void onSuccess(Void info) {
                if (null != mRulesSet) {
                    mRulesSet.remove(rule);
                    updateRules(mRulesSet);
                    onBingRulesUpdate();
                }
                if (listener != null) {
                    try {
                        listener.onBingRuleUpdateSuccess();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## deleteRule : onBingRuleUpdateSuccess failed " + e.getMessage());
                    }
                }
            }

            private void onError(String message) {
                if (null != listener) {
                    try {
                        listener.onBingRuleUpdateFailure(message);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## onError : onBingRuleUpdateFailure failed " + e.getMessage());
                    }
                }
            }

            /**
             * Called if there is a network error.
             * @param e the exception
             */
            @Override
            public void onNetworkError(Exception e) {
                onError(e.getLocalizedMessage());
            }

            /**
             * Called in case of a Matrix error.
             * @param e the Matrix error
             */
            @Override
            public void onMatrixError(MatrixError e)  {
                onError(e.getLocalizedMessage());
            }

            /**
             * Called for some other type of error.
             * @param e the exception
             */
            @Override
            public void onUnexpectedError(Exception e) {
                onError(e.getLocalizedMessage());
            }
        });
    }

    /**
     * Delete a rules list.
     * @param rules the rules to delete
     * @param listener the listener when the rules are deleted
     */
    public void deleteRules(final List<BingRule> rules, final onBingRuleUpdateListener listener) {
        deleteRules(rules, 0, listener);
    }

    /**
     * Recursive rules deletion method.
     * @param rules the rules to delete
     * @param index the rule index
     * @param listener the listener when the rules are deleted
     */
    private void deleteRules(final List<BingRule> rules, final int index, final onBingRuleUpdateListener listener) {
        // sanity checks
        if ((null == rules) || (index >= rules.size())) {
            onBingRulesUpdate();
            if (null != listener) {
                try {
                    listener.onBingRuleUpdateSuccess();
                } catch (Exception e) {
                    Log.e(LOG_TAG, "## deleteRules() : onBingRuleUpdateSuccess failed " + e.getMessage());
                }
            }

            return;
        }

        // delete the rule
        deleteRule(rules.get(index), new onBingRuleUpdateListener() {
            @Override
            public void onBingRuleUpdateSuccess() {
                deleteRules(rules, index+1, listener);
            }

            @Override
            public void onBingRuleUpdateFailure(String errorMessage) {
                if (null != listener) {
                    try {
                        listener.onBingRuleUpdateFailure(errorMessage);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## deleteRules() : onBingRuleUpdateFailure failed " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Add a rule.
     * @param rule the rule to delete.
     * @param listener the rule update listener.
     */
    public void addRule(final BingRule rule, final onBingRuleUpdateListener listener) {
        // null case
        if (null == rule) {
            if (listener != null) {
                try {
                    listener.onBingRuleUpdateSuccess();
                } catch (Exception e) {
                    Log.e(LOG_TAG, "## addRule : onBingRuleUpdateSuccess failed " + e.getMessage());
                }
            }
            return;
        }

        mApiClient.addRule(rule, new SimpleApiCallback<Void>() {
            @Override
            public void onSuccess(Void info) {
                if (null != mRulesSet) {
                    mRulesSet.addAtTop(rule);
                    updateRules(mRulesSet);
                    onBingRulesUpdate();
                }

                if (listener != null) {
                    try {
                        listener.onBingRuleUpdateSuccess();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## addRule : onBingRuleUpdateSuccess failed " + e.getMessage());
                    }
                }
            }

            private void onError(String message) {
                if (null != listener) {
                    try {
                        listener.onBingRuleUpdateFailure(message);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## addRule : onBingRuleUpdateFailure failed " + e.getMessage());
                    }
                }
            }

            /**
             * Called if there is a network error.
             * @param e the exception
             */
            @Override
            public void onNetworkError(Exception e) {
                onError(e.getLocalizedMessage());
            }

            /**
             * Called in case of a Matrix error.
             * @param e the Matrix error
             */
            @Override
            public void onMatrixError(MatrixError e) {
                onError(e.getLocalizedMessage());
            }

            /**
             * Called for some other type of error.
             * @param e the exception
             */
            @Override
            public void onUnexpectedError(Exception e) {
                onError(e.getLocalizedMessage());
            }
        });
    }

    /**
     * Search the push rules for the room id
     * @param roomId the room id
     * @return the room rules list
     */
    public List<BingRule> getPushRulesForRoomId(String roomId) {
        ArrayList<BingRule> rules = new ArrayList<>();

        // sanity checks
        if (!TextUtils.isEmpty(roomId) && (null != mRulesSet)) {
            // the webclient defines two ways to set a room rule
            // mention only : the user won't have any push for the room except if a content rule is fullfilled
            // mute : no notification for this room

            // mute rules are defined in override groups
            if (null != mRulesSet.override) {
                for (BingRule roomRule : mRulesSet.override) {
                    if (TextUtils.equals(roomRule.ruleId, roomId)) {
                        rules.add(roomRule);
                    }
                }
            }

            // mention only are defined in room group
            if (null != mRulesSet.room) {
                for (BingRule roomRule : mRulesSet.room) {
                    if (TextUtils.equals(roomRule.ruleId, roomId)) {
                        rules.add(roomRule);
                    }
                }
            }
        }

        return rules;
    }

    /**
     * Tell whether the regular notifications are disabled for the room.
     * @param roomId the room id
     * @return true if the regular notifications are disabled (mention only)
     */
    public boolean isRoomMentionOnly(String roomId) {
        // sanity check
        if (!TextUtils.isEmpty(roomId)) {
            if (mIsMentionOnlyMap.containsKey(roomId)) {
                return mIsMentionOnlyMap.get(roomId);
            }

            if (null != mRulesSet.room) {
                for (BingRule roomRule : mRulesSet.room) {
                    if (TextUtils.equals(roomRule.ruleId, roomId)) {
                        List<BingRule> roomRules = getPushRulesForRoomId(roomId);

                        if (0 != roomRules.size()) {
                            for (BingRule rule : roomRules) {
                                if (rule.shouldNotNotify()) {
                                    mIsMentionOnlyMap.put(roomId, rule.isEnabled);
                                    return rule.isEnabled;
                                }
                            }
                        }
                    }
                }
            }

            mIsMentionOnlyMap.put(roomId, false);
        }

        return false;
    }

    /**
     * Test if the room has a dedicated rule which disables notification.
     * @param roomId the roomId
     * @return true if there is a rule to disable notifications.
     */
    public boolean isRoomNotificationsDisabled(String roomId) {
        List<BingRule> roomRules = getPushRulesForRoomId(roomId);

        if (0 != roomRules.size()) {
            for(BingRule rule : roomRules) {
                if (!rule.shouldNotify() && rule.isEnabled) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Mute / unmute the room notifications.
     * Only the room rules are checked.
     *
     * @param roomId the room id to mute / unmute.
     * @param isMuted set to true to mute the notification
     * @param listener the listener.
     */
    public void muteRoomNotifications(final String roomId, final boolean isMuted, final onBingRuleUpdateListener listener) {
        List<BingRule> bingRules = getPushRulesForRoomId(roomId);

        // the mobile client only supports to define a "mention only" rule i.e a rule defined in the room rules set.
        // delete the rule and create a new one
        deleteRules(bingRules, new onBingRuleUpdateListener() {
            @Override
            public void onBingRuleUpdateSuccess() {
                if (isMuted) {
                    addRule(new BingRule(BingRule.KIND_ROOM, roomId, false, false, false), listener);
                } else if (null != listener) {
                    try {
                        listener.onBingRuleUpdateSuccess();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## muteRoomNotifications() : onBingRuleUpdateSuccess failed " + e.getMessage());
                    }
                }
            }

            @Override
            public void onBingRuleUpdateFailure(String errorMessage) {
                if (null != listener) {
                    try {
                        listener.onBingRuleUpdateFailure(errorMessage);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## muteRoomNotifications() : onBingRuleUpdateFailure failed " + e.getMessage());
                    }
                }
            }
        });
    }
}
