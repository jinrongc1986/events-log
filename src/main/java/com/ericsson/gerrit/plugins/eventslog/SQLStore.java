// Copyright (C) 2014 Ericsson
//
// Licensed under the Apache License, Version 2.0 (the "License"),
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

package com.ericsson.gerrit.plugins.eventslog;

import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectControl;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import com.ericsson.gerrit.plugins.eventslog.SQLClient.Result;

@Singleton
public class SQLStore implements EventStore, LifecycleListener {
  private static final Logger log = LoggerFactory.getLogger(SQLStore.class);

  private final ProjectControl.GenericFactory projectControlFactory;
  private final Provider<CurrentUser> userProvider;
  private SQLClient eventsDb;
  private SQLClient localEventsDb;
  private final int maxAge;
  private final int maxTries;
  private final int waitTime;
  private final int connectTime;
  private boolean online = true;
  private final ScheduledThreadPoolExecutor pool;
  private ScheduledFuture<?> task;

  @Inject
  SQLStore(ProjectControl.GenericFactory projectControlFactory,
      Provider<CurrentUser> userProvider,
      EventsLogConfig cfg,
      @EventsDb SQLClient eventsDb,
      @LocalEventsDb SQLClient localEventsDb,
      @EventPool ScheduledThreadPoolExecutor pool ) {
    this.maxAge = cfg.getMaxAge();
    this.maxTries = cfg.getMaxTries();
    this.waitTime = cfg.getWaitTime();
    this.connectTime = cfg.getConnectTime();
    this.projectControlFactory = projectControlFactory;
    this.userProvider = userProvider;
    this.eventsDb = eventsDb;
    this.localEventsDb = localEventsDb;
    this.pool = pool;
  }

  @Override
  public void start() {
    setUp();
  }

  @Override
  public void stop() {
    try {
      eventsDb.close();
    } catch (SQLException e) {
      throw new RuntimeException("Cannot close datasource ", e);
    }
    try {
      localEventsDb.close();
    } catch (SQLException e) {
      throw new RuntimeException("Cannot close datasource ", e);
    }
  }

  /**
   * {@inheritDoc}
   * The events returned are restricted to the projects which are visible to the
   * user.
   */
  @Override
  public List<String> queryChangeEvents(String query)
      throws MalformedQueryException, ServiceUnavailableException {
    if (!online) {
      throw new ServiceUnavailableException();
    }
    List<String> events = new ArrayList<>();
    Project.NameKey project = null;
    for (Entry<String, Collection<String>> entry : eventsDb.getEvents(query)
        .asMap().entrySet()) {
      try {
        project = new Project.NameKey(entry.getKey());
        if (projectControlFactory.controlFor(project, userProvider.get())
            .isVisible()) {
          events.addAll(entry.getValue());
        }
      } catch (NoSuchProjectException e) {
        log.warn("Database contains a non-existing project, " + project.get()
            + ", removing project from database", e);
        removeProjectEvents(project.get());
      } catch (IOException e) {
        log.warn("Cannot get project visibility info for " + project.get()
            + " from cache", e);
      }
    }
    return events;
  }

  /**
   * {@inheritDoc}
   * If storing the event fails due to a connection problem, storage will be
   * re-attempted as specified in gerrit.config. After failing the maximum
   * amount of times, the event will be stored in a local h2 database.
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
        log.warn("Cannot store ChangeEvent for: " + projectName.get(), e);
        if (e.getCause() instanceof ConnectException
            || e.getMessage().contains("terminating connection")) {
          done = false;
          if (failedConnections < maxTries - 1) {
            failedConnections++;
            log.info("Retrying store event");
            try {
              Thread.sleep(waitTime);
            } catch (InterruptedException e1) {
              continue;
            }
          } else {
            log.error("Failed to store event " + maxTries + " times");
            setOnline(false);
          }
        }
      }
    }
  }

  private void setUp() {
    try {
      getEventsDb().createDBIfNotCreated();
    } catch (SQLException e) {
      log.warn("Cannot start the database. Events will be stored locally "
          + "until database connection can be established", e);
      setOnline(false);
    }
    if (online) {
      restoreEventsFromLocal();
    }
    removeOldEvents();
  }

  private void removeOldEvents() {
    try {
      getEventsDb().removeOldEvents(maxAge);
    } catch (SQLException e) {
      log.warn("Cannot remove old entries from database", e);
    }
  }

  private SQLClient getEventsDb() {
    return online ? eventsDb : localEventsDb;
  }

  private void removeProjectEvents(String project) {
    try {
      eventsDb.removeProjectEvents(project);
    } catch (SQLException e) {
      log.warn("Cannot remove project " + project + " from database", e);
    }
  }

  private void setOnline(boolean online) {
    this.online = online;
    setUp();
    if (!online) {
      task = pool.scheduleWithFixedDelay(
          new CheckConnectionTask(), 0, connectTime, TimeUnit.MILLISECONDS);
    } else {
      if (task != null) {
        task.cancel(false);
      }
    }
  }

  private void restoreEventsFromLocal() {
    List<Result> results;
    try {
      results = localEventsDb.getAll();
      for (Result result : results) {
        try {
          eventsDb.storeEvent(result.getName(),
              result.getTimestamp(), result.getEvent());
        } catch (SQLException e) {
          log.warn("Could not restore events from local");
        }
      }
    } catch (SQLException e) {
      log.warn("Could not query all events from local");
    }
    try {
      localEventsDb.removeOldEvents(0);
    } catch (SQLException e) {
      log.warn("Could not destroy local database");
    }
  }

  private boolean checkConnection() {
    log.info("Checking database connection...");
    try {
      eventsDb.queryOne();
      return true;
    } catch (SQLException e) {
      return false;
    }
  }

  class CheckConnectionTask implements Runnable {
    CheckConnectionTask() {
    }

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
  }
}
