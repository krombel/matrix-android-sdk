/*
 * Copyright 2014 OpenMarket Ltd
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
package org.matrix.androidsdk.listeners;

import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.rest.model.bingrules.BingRule;

import java.util.List;

public interface IMXEventListener {
    /**
     * The store is ready.
     */
    void onStoreReady();

    /**
     * User presence was updated.
     *
     * @param event The presence event.
     * @param user  The new user value.
     */
    void onPresenceUpdate(Event event, User user);

    /**
     * The self user has been updated (display name, avatar url...).
     *
     * @param myUser The updated myUser
     */
    void onAccountInfoUpdate(MyUser myUser);

    /**
     * The ignored users list has been updated.
     */
    void onIgnoredUsersListUpdate();

    /**
     * The direct chat rooms list have been updated.
     */
    void onDirectMessageChatRoomsListUpdate();

    /**
     * A live room event was received.
     *
     * @param event     the event
     * @param roomState the room state right before the event
     */
    void onLiveEvent(Event event, RoomState roomState);

    /**
     * The live events from a chunk are performed.
     * @param fromToken the start sync token
     * @param toToken the up-to sync token
     */
    void onLiveEventsChunkProcessed(String fromToken, String toToken);

    /**
     * A received event fulfills the bing rules
     * The first matched bing rule is provided in paramater to perform
     * dedicated action like playing a notification sound.
     *
     * @param event     the event
     * @param roomState the room state right before the event
     * @param bingRule  the bing rule
     */
    void onBingEvent(Event event, RoomState roomState, BingRule bingRule);

    /**
     * An event has been successfully encrypted.
     *
     * @param event the encrypted event
     */
    void onEventEncrypted(Event event);

    /**
     * An event has been decrypted
     *
     * @param event the decrypted event
     */
    void onEventDecrypted(Event event);

    /**
     * An event has been sent.
     *
     * @param event the event
     */
    void onSentEvent(Event event);

    /**
     * The event fails to be sent.
     *
     * @param event the event
     */
    void onFailedSendingEvent(Event event);

    /**
     * The bing rules have been updated
     */
    void onBingRulesUpdate();

    /**
     * The initial sync is complete and the store can be queried for current state.
     * @param toToken the up-to sync token
     */
    void onInitialSyncComplete(String toToken);

    /**
     * The crypto sync is complete
     */
    void onCryptoSyncComplete();

    /**
     * A new room has been created.
     *
     * @param roomId the roomID
     */
    void onNewRoom(String roomId);

    /**
     * The user joined a room.
     *
     * @param roomId the roomID
     */
    void onJoinRoom(String roomId);

    /**
     * The messages of an existing room has been flushed during server sync.
     * This flush may be due to a limited timeline in the room sync, or the redaction of a state event.
     *
     * @param roomId the room Id
     */
    void onRoomFlush(String roomId);

    /**
     * The room initial sync is completed.
     * It is triggered after retrieving the room info and performing a first requestHistory
     *
     * @param roomId the roomID
     */
    void onRoomInitialSyncComplete(String roomId);

    /**
     * The room data has been internally updated.
     * It could be triggered when a request failed.
     *
     * @param roomId the roomID
     */
    void onRoomInternalUpdate(String roomId);

    /**
     * The user left the room.
     *
     * @param roomId the roomID
     */
    void onLeaveRoom(String roomId);

    /**
     * A receipt event has been received.
     * It could be triggered when a request failed.
     *
     * @param roomId    the roomID
     * @param senderIds the list of the
     */
    void onReceiptEvent(String roomId, List<String> senderIds);

    /**
     * A Room Tag event has been received.
     *
     * @param roomId the roomID
     */
    void onRoomTagEvent(String roomId);

    /**
     * A read marker has been updated
     *
     * @param roomId
     */
    void onReadMarkerEvent(String roomId);

    /**
     * An event was sent to the current device.
     *
     * @param event the event
     */
    void onToDeviceEvent(Event event);
}

