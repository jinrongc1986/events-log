// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.ericsson.gerrit.plugins.eventslog.sql;

import static com.ericsson.gerrit.plugins.eventslog.sql.SQLTable.TABLE_NAME;
import static java.util.stream.Collectors.toList;

import com.ericsson.gerrit.plugins.eventslog.EventPool;
import com.ericsson.gerrit.plugins.eventslog.EventStore;
import com.ericsson.gerrit.plugins.eventslog.EventsLogConfig;
import com.ericsson.gerrit.plugins.eventslog.EventsLogException;
import com.ericsson.gerrit.plugins.eventslog.ServiceUnavailableException;
import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
class SQLStore implements EventStore, LifecycleListener {
  private static final Logger log = LoggerFactory.getLogger(SQLStore.class);
  private static final String H2_DB_SUFFIX = ".h2.db";

  private final EventsLogCleaner eventsLogCleaner;
  private SQLClient eventsDb;
  private SQLClient localEventsDb;
  private final int maxAge;
  private final int maxTries;
  private final int waitTime;
  private final int connectTime;
  private boolean online = true;
  private boolean copyLocal;
  private final ScheduledExecutorService pool;
  private final PermissionBackend permissionBackend;
  private ScheduledFuture<?> checkConnTask;
  private Path localPath;

  @Inject
  SQLStore(
      EventsLogConfig cfg,
      @EventsDb SQLClient eventsDb,
      @LocalEventsDb SQLClient localEventsDb,
      @EventPool ScheduledExecutorService pool,
      PermissionBackend permissionBackend,
      EventsLogCleaner eventsLogCleaner) {
    this.maxAge = cfg.getMaxAge();
    this.maxTries = cfg.getMaxTries();
    this.waitTime = cfg.getWaitTime();
    this.connectTime = cfg.getConnectTime();
    this.copyLocal = cfg.getCopyLocal();
    this.eventsDb = eventsDb;
    this.localEventsDb = localEventsDb;
    this.eventsLogCleaner = eventsLogCleaner;
    this.pool = pool;
    this.permissionBackend = permissionBackend;
    this.localPath = cfg.getLocalStorePath();
  }

  @Override
  public void start() {
    setUp();
    eventsLogCleaner.scheduleCleaningWith(maxAge);
  }

  @Override
  public void stop() {
    cancelCheckConnectionTaskIfScheduled(true);
    eventsDb.close();
    localEventsDb.close();
  }

  /**
   * {@inheritDoc} The events returned are restricted to the projects which are visible to the user.
   *
   * @throws ServiceUnavailableException if working in offline mode
   */
  @Override
  public List<String> queryChangeEvents(String query) throws EventsLogException {
    if (!online) {
      throw new ServiceUnavailableException();
    }
    List<SQLEntry> entries = new ArrayList<>();

    for (Entry<String, Collection<SQLEntry>> entry : eventsDb.getEvents(query).asMap().entrySet()) {
      String projectName = entry.getKey();
      try {
        permissionBackend
            .currentUser()
            .project(new Project.NameKey(projectName))
            .check(ProjectPermission.ACCESS);
        entries.addAll(entry.getValue());
      } catch (AuthException e) {
        // Ignore
      } catch (PermissionBackendException e) {
        log.warn("Cannot check project access permission", e);
      }
    }
    return entries.stream().sorted().map(SQLEntry::getEvent).collect(toList());
  }

  /**
   * {@inheritDoc} If storing the event fails due to a connection problem, storage will be
   * re-attempted as specified in gerrit.config. After failing the maximum amount of times, the
   * event will be stored in a local h2 database.
   */
  @Override
  public void storeEvent(ProjectEvent event) {
    Project.NameKey projectName = event.getProjectNameKey();
    if (projectName == null) {
      return;
    }
    int failedConnections = 0;
    boolean done = false;
    while (!done) {
      done = true;
      try {
        getEventsDb().storeEvent(event);
      } catch (SQLException e) {
        log.warn("Cannot store ChangeEvent for: {}", projectName.get(), e);
        if (e.getCause() instanceof ConnectException
            || e.getMessage().contains("terminating connection")) {
          done = false;
          try {
            retryIfAllowed(failedConnections);
          } catch (InterruptedException e1) {
            log.warn("Cannot store ChangeEvent for {}: Interrupted", projectName.get());
            Thread.currentThread().interrupt();
            return;
          }
          failedConnections++;
        }
      }
    }
  }

  private void retryIfAllowed(int failedConnections) throws InterruptedException {
    if (failedConnections < maxTries - 1) {
      log.info("Retrying store event");
      Thread.sleep(waitTime);
    } else {
      log.error("Failed to store event {} times", maxTries);
      setOnline(false);
    }
  }

  private void setUp() {
    try {
      getEventsDb().createDBIfNotCreated();
    } catch (SQLException e) {
      log.warn(
          "Cannot start the database. Events will be stored locally"
              + " until database connection can be established",
          e);
      setOnline(false);
    }
    if (online) {
      restoreEventsFromLocal();
    }
  }

  private SQLClient getEventsDb() {
    return online ? eventsDb : localEventsDb;
  }

  private void setOnline(boolean online) {
    this.online = online;
    setUp();
    if (!online) {
      checkConnTask =
          pool.scheduleWithFixedDelay(
              new CheckConnectionTask(), 0, connectTime, TimeUnit.MILLISECONDS);
    } else {
      cancelCheckConnectionTaskIfScheduled(false);
    }
  }

  private void cancelCheckConnectionTaskIfScheduled(boolean mayInterrupt) {
    if (checkConnTask != null) {
      checkConnTask.cancel(mayInterrupt);
    }
  }

  private void restoreEventsFromLocal() {
    if (!localDbExists()) {
      return;
    }
    try {
      List<SQLEntry> entries = localEventsDb.getAll();
      if (entries.isEmpty()) {
        log.debug("No events to restore from local");
        return;
      }
      for (SQLEntry entry : entries) {
        restoreEvent(entry);
      }
    } catch (SQLException e) {
      log.warn("Could not query all events from local", e);
    }
    copyFile(copyLocal);
    localEventsDb.removeOldEvents(0);
  }

  private void restoreEvent(SQLEntry entry) {
    try {
      eventsDb.storeEvent(entry.getName(), entry.getTimestamp(), entry.getEvent());
    } catch (SQLException e) {
      log.warn("Could not restore events from local", e);
    }
  }

  class CheckConnectionTask implements Runnable {
    CheckConnectionTask() {}

    @Override
    public void run() {
      if (checkConnection()) {
        setOnline(true);
        log.info("Connected to database");
      }
    }

    @Override
    public String toString() {
      return "(Events-log) Connect to database";
    }

    private boolean checkConnection() {
      log.debug("Checking database connection...");
      try {
        eventsDb.queryOne();
        return true;
      } catch (SQLException e) {
        log.error("Problem checking database connection", e);
        return false;
      }
    }
  }

  private boolean localDbExists() {
    boolean exists = false;
    try {
      exists = localEventsDb.dbExists();
    } catch (SQLException e) {
      log.warn("Could not check existence of local database, assume that it doesn't exist", e);
    }
    return exists;
  }

  private void copyFile(boolean copyLocal) {
    if (!copyLocal) {
      return;
    }
    Path file = localPath.resolve(TABLE_NAME + H2_DB_SUFFIX);
    Path copyFile =
        localPath.resolve(
            TABLE_NAME + (TimeUnit.MILLISECONDS.toSeconds(TimeUtil.nowMs())) + H2_DB_SUFFIX);
    try {
      Files.copy(file, copyFile);
    } catch (IOException e) {
      log.warn("Could not copy local database file with timestamp", e);
    }
  }
}
