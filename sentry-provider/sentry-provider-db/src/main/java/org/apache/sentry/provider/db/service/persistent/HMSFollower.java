/*
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package org.apache.sentry.provider.db.service.persistent;

import static org.apache.sentry.binding.hive.conf.HiveAuthzConf.AuthzConfVars.AUTHZ_SERVER_NAME;
import static org.apache.sentry.binding.hive.conf.HiveAuthzConf.AuthzConfVars.AUTHZ_SERVER_NAME_DEPRECATED;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jdo.JDODataStoreException;

import org.apache.sentry.core.common.exception.SentryOutOfSyncException;
import org.apache.sentry.core.common.utils.PubSub;
import org.apache.sentry.hdfs.ServiceConstants.ServerConfig;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.thrift.TException;
import org.apache.sentry.service.thrift.SentryHMSClient;
import org.apache.sentry.service.thrift.HiveConnectionFactory;
import org.apache.sentry.service.thrift.HiveNotificationFetcher;
import org.apache.sentry.service.thrift.SentryServiceUtil;
import org.apache.sentry.service.thrift.SentryStateBank;
import org.apache.sentry.service.thrift.SentryServiceState;
import org.apache.sentry.service.thrift.HMSFollowerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HMSFollower is the thread which follows the Hive MetaStore state changes from Sentry.
 * It gets the full update and notification logs from HMS and applies it to
 * update permissions stored in Sentry using SentryStore and also update the &lt obj,path &gt state
 * stored for HDFS-Sentry sync.
 */
public class HMSFollower implements Runnable, AutoCloseable, PubSub.Subscriber {

  private static final Logger LOGGER = LoggerFactory.getLogger(HMSFollower.class);
  private static final String FULL_UPDATE_TRIGGER = "FULL UPDATE TRIGGER: ";
  private static boolean connectedToHms = false;

  private SentryHMSClient client;
  private final Configuration authzConf;
  private final SentryStore sentryStore;
  private final NotificationProcessor notificationProcessor;
  private boolean readyToServe;
  private final HiveNotificationFetcher notificationFetcher;
  private final boolean hdfsSyncEnabled;
  private final AtomicBoolean fullUpdateHMS = new AtomicBoolean(false);

  private final LeaderStatusMonitor leaderMonitor;

  /**
   * Current generation of HMS snapshots. HMSFollower is single-threaded, so no need
   * to protect against concurrent modification.
   */
  private long hmsImageId = SentryStore.EMPTY_PATHS_SNAPSHOT_ID;

  /**
   * Configuring Hms Follower thread.
   *
   * @param conf sentry configuration
   * @param store sentry store
   * @param leaderMonitor singleton instance of LeaderStatusMonitor
   */
  public HMSFollower(Configuration conf, SentryStore store, LeaderStatusMonitor leaderMonitor,
              HiveConnectionFactory hiveConnectionFactory) {
    this(conf, store, leaderMonitor, hiveConnectionFactory, null);
  }

  /**
   * Constructor should be used only for testing purposes.
   *
   * @param conf sentry configuration
   * @param store sentry store
   * @param leaderMonitor
   * @param authServerName Server that sentry is Authorizing
   */
  @VisibleForTesting
  public HMSFollower(Configuration conf, SentryStore store, LeaderStatusMonitor leaderMonitor,
              HiveConnectionFactory hiveConnectionFactory, String authServerName) {
    LOGGER.info("HMSFollower is being initialized");
    readyToServe = false;
    authzConf = conf;
    this.leaderMonitor = leaderMonitor;
    sentryStore = store;

    if (authServerName == null) {
      authServerName = conf.get(AUTHZ_SERVER_NAME.getVar(),
        conf.get(AUTHZ_SERVER_NAME_DEPRECATED.getVar(), AUTHZ_SERVER_NAME_DEPRECATED.getDefault()));
    }

    notificationProcessor = new NotificationProcessor(sentryStore, authServerName, authzConf);
    client = new SentryHMSClient(authzConf, hiveConnectionFactory);
    hdfsSyncEnabled = SentryServiceUtil.isHDFSSyncEnabledNoCache(authzConf); // no cache to test different settings for hdfs sync
    notificationFetcher = new HiveNotificationFetcher(sentryStore, hiveConnectionFactory, authzConf);

    // subscribe to full update notification
    if (conf.getBoolean(ServerConfig.SENTRY_SERVICE_FULL_UPDATE_PUBSUB, false)) {
      LOGGER.info(FULL_UPDATE_TRIGGER + "subscribing to topic " + PubSub.Topic.HDFS_SYNC_HMS.getName());
      PubSub.getInstance().subscribe(PubSub.Topic.HDFS_SYNC_HMS, this);
    }

  }

  @VisibleForTesting
  public static boolean isConnectedToHms() {
    return connectedToHms;
  }

  @VisibleForTesting
  void setSentryHmsClient(SentryHMSClient client) {
    this.client = client;
  }

  @Override
  public void close() {
    if (client != null) {
      // Close any outstanding connections to HMS
      try {
        client.disconnect();
        SentryStateBank.disableState(HMSFollowerState.COMPONENT,HMSFollowerState.CONNECTED);
      } catch (Exception failure) {
        LOGGER.error("Failed to close the Sentry Hms Client", failure);
      }
    }

    notificationFetcher.close();
  }

  @Override
  public void run() {
    SentryStateBank.enableState(HMSFollowerState.COMPONENT,HMSFollowerState.STARTED);
    long maxNotificationId;
    try {
      try {
        // Initializing maxNotificationId based on the latest persisted notification ID.
        maxNotificationId = sentryStore.getMaxNotificationID();
      } catch (Exception e) {
        LOGGER.error("Failed to get the last processed notification id from sentry store, "
            + "Skipping the processing", e);
        return;
      }
      // Wake any clients connected to this service waiting for HMS already processed notifications.
      wakeUpWaitingClientsForSync(maxNotificationId);
      // Only the leader should listen to HMS updates
      if (!isLeader()) {
        // Close any outstanding connections to HMS
        close();
        return;
      }
      syncupWithHms(maxNotificationId);
    } finally {
      SentryStateBank.disableState(HMSFollowerState.COMPONENT,HMSFollowerState.STARTED);
    }
  }

  private boolean isLeader() {
    return (leaderMonitor == null) || leaderMonitor.isLeader();
  }

  @VisibleForTesting
  String getAuthServerName() {
    return notificationProcessor.getAuthServerName();
  }

  /**
   * Processes new Hive Metastore notifications.
   *
   * <p>If no notifications are processed yet, then it
   * does a full initial snapshot of the Hive Metastore followed by new notifications updates that
   * could have happened after it.
   *
   * <p>Clients connections waiting for an event notification will be
   * woken up afterwards.
   * @param maxNotificationId Max of all event-id's that sentry has processed.
   */
  private void syncupWithHms(long maxNotificationId) {
    try {
      client.connect();
      connectedToHms = true;
      SentryStateBank.enableState(HMSFollowerState.COMPONENT,HMSFollowerState.CONNECTED);
    } catch (Throwable e) {
      LOGGER.error("HMSFollower cannot connect to HMS!!", e);
      return;
    }

    try {
      Collection<NotificationEvent> notifications;
      /* Before getting notifications, it checks if a full HMS snapshot is required. */
      if (isFullSnapshotRequired(maxNotificationId)) {
        createFullSnapshot();
        return;
      }
      try {
        notifications = notificationFetcher.fetchNotifications(maxNotificationId);
      } catch (SentryOutOfSyncException e) {
        LOGGER.error("An error occurred while fetching HMS notifications: {}",
                e.getMessage());
        createFullSnapshot();
        return;
      }

      if (!readyToServe) {
        // Allow users and/or applications who look into the Sentry console output to see
        // when Sentry is ready to serve.
        System.out.println("Sentry HMS support is ready");
        readyToServe = true;
      }

      // Continue with processing new notifications if no snapshots are done.
      processNotifications(notifications, maxNotificationId);
    } catch (TException e) {
      LOGGER.error("An error occurred while fetching HMS notifications: ", e);
      close();
    } catch (Throwable t) {
      // catching errors to prevent the executor to halt.
      LOGGER.error("Exception in HMSFollower! Caused by: " + t.getMessage(), t);

      close();
    }
  }

  /**
   * Checks if a new full HMS snapshot request is needed by checking if:
   * <ul>
   *   <li>Sentry HMS Notification table is EMPTY</li>
   *   <li>HDFSSync is enabled and Sentry Authz Snapshot table is EMPTY</li>
   *   <li>The current notification Id on the HMS is less than the
   *   latest processed by Sentry.</li>
   *   <li>Full Snapshot Signal is detected</li>
   * </ul>
   *
   * @param latestSentryNotificationId The notification Id to check against the HMS
   * @return True if a full snapshot is required; False otherwise.
   * @throws Exception If an error occurs while checking the SentryStore or the HMS client.
   */
  private boolean isFullSnapshotRequired(long latestSentryNotificationId) throws Exception {
    if (sentryStore.isHmsNotificationEmpty()) {
      LOGGER.debug("Sentry Store has no HMS Notifications. Create Full HMS Snapshot. "
          + "latest sentry notification Id = {}", latestSentryNotificationId);
      return true;
    }

    // Once HDFS sync is enabled, and if MAuthzPathsSnapshotId
    // table is still empty, we need to request a full snapshot
    if(hdfsSyncEnabled && sentryStore.isAuthzPathsSnapshotEmpty()) {
      LOGGER.debug("HDFSSync is enabled and MAuthzPathsSnapshotId table is empty. Need to request a full snapshot");
      return true;
    }

    long currentHmsNotificationId = notificationFetcher.getCurrentNotificationId();
    if (currentHmsNotificationId < latestSentryNotificationId) {
      LOGGER.info("The current notification ID on HMS = {} is less than the latest processed Sentry "
          + "notification ID = {}. Need to request a full HMS snapshot",
          currentHmsNotificationId, latestSentryNotificationId);
      return true;
    }

    // check if forced full update is required, reset update flag to false
    // to only do it once per forced full update request.
    if (fullUpdateHMS.compareAndSet(true, false)) {
      LOGGER.info(FULL_UPDATE_TRIGGER + "initiating full HMS snapshot request");
      return true;
    }

    return false;
  }

  /**
   * Request for full snapshot and persists it if there is no snapshot available in the sentry
   * store. Also, wakes-up any waiting clients.
   *
   * @return ID of last notification processed.
   * @throws Exception if there are failures
   */
  private long createFullSnapshot() throws Exception {
    LOGGER.debug("Attempting to take full HMS snapshot");
    Preconditions.checkState(!SentryStateBank.isEnabled(SentryServiceState.COMPONENT,
        SentryServiceState.FULL_UPDATE_RUNNING),
        "HMSFollower shown loading full snapshot when it should not be.");
    try {
      // Set that the full update is running
      SentryStateBank
          .enableState(SentryServiceState.COMPONENT, SentryServiceState.FULL_UPDATE_RUNNING);

      PathsImage snapshotInfo = client.getFullSnapshot();
      if (snapshotInfo.getPathImage().isEmpty()) {
        LOGGER.debug("Received empty path image from HMS while taking a full snapshot");
        return snapshotInfo.getId();
      }

      // Check we're still the leader before persisting the new snapshot
      if (!isLeader()) {
        LOGGER.info("Not persisting full snapshot since not a leader");
        return SentryStore.EMPTY_NOTIFICATION_ID;
      }
      try {
        if (hdfsSyncEnabled) {
          LOGGER.info("Persisting full snapshot for notification Id = {}", snapshotInfo.getId());
          sentryStore.persistFullPathsImage(snapshotInfo.getPathImage(), snapshotInfo.getId());
        } else {
          // We need to persist latest notificationID for next poll
          LOGGER.info("HDFSSync is disabled. Not Persisting full snapshot, "
              + "but only setting last processed notification Id = {}", snapshotInfo.getId());
          sentryStore.setLastProcessedNotificationID(snapshotInfo.getId());
        }
      } catch (Exception failure) {
        LOGGER.error("Received exception while persisting HMS path full snapshot ");
        throw failure;
      }
      // Wake up any HMS waiters that could have been put on hold before getting the
      // eventIDBefore value.
      wakeUpWaitingClientsForSync(snapshotInfo.getId());
      // HMSFollower connected to HMS and it finished full snapshot if that was required
      // Log this message only once
      LOGGER.info("Sentry HMS support is ready");
      return snapshotInfo.getId();
    } catch(Exception failure) {
      LOGGER.error("Received exception while creating HMS path full snapshot ");
      throw failure;
    } finally {
      SentryStateBank
          .disableState(SentryServiceState.COMPONENT, SentryServiceState.FULL_UPDATE_RUNNING);
    }
  }

  /**
   * Process the collection of notifications and wake up any waiting clients.
   * Also, persists the notification ID regardless of processing result.
   *
   * @param events list of event to be processed
   * @param notificationId Max event-id that sentry processed so far.
   * @throws Exception if the complete notification list is not processed because of JDO Exception
   */
  public void processNotifications(Collection<NotificationEvent> events, long notificationId) throws Exception {
    boolean isNotificationProcessed;
    long eventIdProcessed = notificationId;
    if (events.isEmpty()) {
      return;
    }

    for (NotificationEvent event : events) {
      isNotificationProcessed = false;
      if (eventIdProcessed > 0) {
        if (eventIdProcessed == event.getEventId()) {
          LOGGER.info("Processing event with Duplicate event-id: {}", eventIdProcessed);
        } else if (eventIdProcessed != event.getEventId() - 1) {
          LOGGER.info("Events between ID's " + eventIdProcessed + " and "
                  + event.getEventId() + " are either missing OR out of order");
        }
      }
      eventIdProcessed = event.getEventId();
      try {
        // Only the leader should process the notifications
        if (!isLeader()) {
          LOGGER.debug("Not processing notifications since not a leader");
          return;
        }
        isNotificationProcessed = notificationProcessor.processNotificationEvent(event);
        notificationFetcher.updateCache(event);
      } catch (Exception e) {
        if (e.getCause() instanceof JDODataStoreException) {
          LOGGER.info("Received JDO Storage Exception, Could be because of processing "
              + "duplicate notification");
          if (event.getEventId() <= sentryStore.getMaxNotificationID()) {
            // Rest of the notifications need not be processed.
            LOGGER.error("Received event with Id: {} which is smaller then the ID "
                + "persisted in store", event.getEventId());
            break;
          }
        } else {
          LOGGER.error("Processing the notification with ID:{} failed with exception {}",
              event.getEventId(), e);
        }
      }
      if (!isNotificationProcessed) {
        try {
          // Update the notification ID in the persistent store even when the notification is
          // not processed as the content in in the notification is not valid.
          // Continue processing the next notification.
          LOGGER.debug("Explicitly Persisting Notification ID = {} ", event.getEventId());
          sentryStore.persistLastProcessedNotificationID(event.getEventId());
          notificationFetcher.updateCache(event);
        } catch (Exception failure) {
          LOGGER.error("Received exception while persisting the notification ID = {}", event.getEventId());
          throw failure;
        }
      }
      // Wake up any HMS waiters that are waiting for this ID.
      wakeUpWaitingClientsForSync(event.getEventId());
    }
  }

  /**
   * Wakes up HMS waiters waiting for a specific event notification.<p>
   *
   * Verify that HMS image id didn't change since the last time we looked.
   * If id did, it is possible that notifications jumped backward, so reset
   * the counter to the current value.
   *
   * @param eventId Id of a notification
   */
  private void wakeUpWaitingClientsForSync(long eventId) {
    CounterWait counterWait = sentryStore.getCounterWait();

    LOGGER.debug("wakeUpWaitingClientsForSync: eventId = {}, hmsImageId = {}", eventId, hmsImageId);
    // Wake up any HMS waiters that are waiting for this ID.
    // counterWait should never be null, but tests mock SentryStore and a mocked one
    // doesn't have it.
    if (counterWait == null) {
      return;
    }

    long lastHMSSnapshotId = hmsImageId;
    try {
      // Read actual HMS image ID
      lastHMSSnapshotId = sentryStore.getLastProcessedImageID();
      LOGGER.debug("wakeUpWaitingClientsForSync: lastHMSSnapshotId = {}", lastHMSSnapshotId);
    } catch (Exception e) {
      counterWait.update(eventId);
      LOGGER.error("Failed to get the last processed HMS image id from sentry store");
      return;
    }

    // Reset the counter if the persisted image ID is greater than current image ID
    if (lastHMSSnapshotId > hmsImageId) {
      counterWait.reset(eventId);
      hmsImageId = lastHMSSnapshotId;
      LOGGER.debug("wakeUpWaitingClientsForSync: reset counterWait with eventId = {}, new hmsImageId = {}", eventId, hmsImageId);
    }

    LOGGER.debug("wakeUpWaitingClientsForSync: update counterWait with eventId = {}, hmsImageId = {}", eventId, hmsImageId);
    counterWait.update(eventId);
  }

  /**
   * PubSub.Subscriber callback API
   */
  @Override
  public void onMessage(PubSub.Topic topic, String message) {
    Preconditions.checkArgument(topic == PubSub.Topic.HDFS_SYNC_HMS, "Unexpected topic %s instead of %s", topic, PubSub.Topic.HDFS_SYNC_HMS);
    LOGGER.info(FULL_UPDATE_TRIGGER + "Received [{}, {}] notification", topic, message);
    fullUpdateHMS.set(true);
  }
}
