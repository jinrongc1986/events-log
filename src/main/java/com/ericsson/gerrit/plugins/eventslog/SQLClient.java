// Copyright (C) 2015 Ericsson
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

import static com.ericsson.gerrit.plugins.eventslog.SQLTable.DATE_ENTRY;
import static com.ericsson.gerrit.plugins.eventslog.SQLTable.EVENT_ENTRY;
import static com.ericsson.gerrit.plugins.eventslog.SQLTable.PRIMARY_ENTRY;
import static com.ericsson.gerrit.plugins.eventslog.SQLTable.PROJECT_ENTRY;
import static com.ericsson.gerrit.plugins.eventslog.SQLTable.TABLE_NAME;
import static java.lang.String.format;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.dbcp.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gson.Gson;
import com.google.inject.Inject;

public class SQLClient {
  private static final Logger log = LoggerFactory.getLogger(SQLClient.class);

  private BasicDataSource ds;
  private final Gson gson = new Gson();

  @Inject
  SQLClient(String storeDriver, String storeUrl, String urlOptions) {
    ds = new BasicDataSource();
    ds.setDriverClassName(storeDriver);
    ds.setUrl(storeUrl + TABLE_NAME + ";" + urlOptions);
  }

  /**
   * Set the username to connect to the database
   * @param username
   */
  public void setUsername(String username) {
    ds.setUsername(username);
  }

  /**
   * Set the password to connect to the database
   * @param password
   */
  public void setPassword(String password) {
    ds.setPassword(password);
  }

  /**
   * Create the database if it has not yet been created.
   *
   * @throws SQLException
   */
  public void createDBIfNotCreated() throws SQLException {
    Connection conn = ds.getConnection();

    StringBuilder query = new StringBuilder();
    query.append(format("CREATE TABLE IF NOT EXISTS %s(", TABLE_NAME));
    if (ds.getDriverClassName().contains("postgresql")) {
      query.append(format("%s SERIAL PRIMARY KEY,", PRIMARY_ENTRY));
    } else {
      query.append(format("%s INT AUTO_INCREMENT PRIMARY KEY,", PRIMARY_ENTRY));
    }
    query.append(format("%s VARCHAR(255),", PROJECT_ENTRY));
    query.append(format("%s TIMESTAMP DEFAULT NOW(),", DATE_ENTRY));
    query.append(format("%s TEXT)", EVENT_ENTRY));

    Statement stat = conn.createStatement();
    try {
      stat.execute(query.toString());
    } finally {
      closeStatement(stat);
      closeConnection(conn);
    }
  }

  public void close() throws SQLException {
    ds.close();
  }

  /**
   * Get events as a multimap list of Strings and SQLEntries. The String
   * represents the project name, and the SQLEntry is the event information.
   *
   * @param query
   * @return multimap list of Strings (project names) and SQLEntries (events)
   * @throws EventsLogException if there was an problem with the database
   * @throws MalformedQueryException if there was a bad query request
   */
  public ListMultimap<String, SQLEntry> getEvents(String query)
      throws EventsLogException {
    Connection conn = null;
    Statement stat = null;
    try {
      conn = ds.getConnection();
      stat = conn.createStatement();
      return listEvents(stat, query);
    } catch (SQLException e) {
      throw new EventsLogException("Cannot query database", e);
    } finally {
      closeStatement(stat);
      closeConnection(conn);
    }
  }

  /**
   * Store the event in the database
   *
   * @param event The event to store
   * @throws SQLException If there was a problem with the database
   */
  public void storeEvent(ProjectEvent event) throws SQLException {
    String json = gson.toJson(event);
    Connection conn = ds.getConnection();
    Statement stat = conn.createStatement();
    try {
      stat.execute(format("INSERT INTO %s(%s, %s, %s) ", TABLE_NAME,
          PROJECT_ENTRY, DATE_ENTRY, EVENT_ENTRY)
          + format("VALUES('%s', '%s', '%s')", event.getProjectNameKey().get(),
              new Timestamp(TimeUnit.SECONDS.toMillis(event.eventCreatedOn)), json));
    } finally {
      closeStatement(stat);
      closeConnection(conn);
    }
  }

  /**
   * Store the event in the database
   *
   * @param projectName The project in which this event happened
   * @param timestamp The time at which this event took place
   * @param event The event as a string
   * @throws SQLException If there was a problem with the database
   */
  public void storeEvent(String projectName, Timestamp timestamp, String event)
      throws SQLException {
    Connection conn = ds.getConnection();
    Statement stat = conn.createStatement();
    try {
      stat.execute(format("INSERT INTO %s(%s, %s, %s) ", TABLE_NAME,
          PROJECT_ENTRY, DATE_ENTRY, EVENT_ENTRY)
          + format("VALUES('%s', '%s', '%s')", projectName, timestamp, event));
    } finally {
      closeStatement(stat);
      closeConnection(conn);
    }
  }

  /**
   * Remove all events that are older than maxAge
   *
   * @param maxAge The maximum age to keep events
   * @throws SQLException If there was a problem with the database
   */
  public void removeOldEvents(int maxAge) throws SQLException {
    Connection conn = ds.getConnection();
    Statement stat = conn.createStatement();
    try {
      stat.execute(format("DELETE FROM %s WHERE %s < '%s'", TABLE_NAME,
          DATE_ENTRY, new Timestamp(System.currentTimeMillis()
              - TimeUnit.MILLISECONDS.convert(maxAge, TimeUnit.DAYS))));
    } finally {
      closeStatement(stat);
      closeConnection(conn);
    }
  }

  /**
   * Remove all events corresponding to this project
   *
   * @param project Events attributed to this project should be removed
   * @throws SQLException If there was a problem with the database
   */
  public void removeProjectEvents(String project) throws SQLException {
    Connection conn = ds.getConnection();
    Statement stat = conn.createStatement();
    try {
      stat.execute(String.format("DELETE FROM %s WHERE project = '%s'",
          TABLE_NAME, project));
    } finally {
      closeStatement(stat);
      closeConnection(conn);
    }
  }

  /**
   * Do a simple query on the database. This is used to determine whether or not
   * the main database is online.
   *
   * @throws SQLException If there was a problem with the database
   */
  public void queryOne() throws SQLException {
    Connection conn = null;
    Statement stat = null;
    try {
      conn = ds.getConnection();
      stat = conn.createStatement();
      stat.executeQuery("SELECT * FROM " + TABLE_NAME + " LIMIT 1");
    } finally {
      closeStatement(stat);
      closeConnection(conn);
    }
  }

  /**
   * Get all events from the database as a list of database entries.
   *
   * @return List of all events retrieved from the database
   * @throws SQLException If there was a problem with the database
   */
  public List<SQLEntry> getAll() throws SQLException {
    List<SQLEntry> entries = new ArrayList<>();
    Connection conn = null;
    Statement stat = null;
    ResultSet rs = null;
    try {
      conn = ds.getConnection();
      stat = conn.createStatement();
      rs = stat.executeQuery("SELECT * FROM " + TABLE_NAME);
      while (rs.next()) {
        entries.add(new SQLEntry(rs.getString(PROJECT_ENTRY), rs
            .getTimestamp(DATE_ENTRY), rs.getString(EVENT_ENTRY), rs
            .getInt(PRIMARY_ENTRY)));
      }
      return entries;
    } finally {
      closeResultSet(rs);
      closeStatement(stat);
      closeConnection(conn);
    }
  }

  private ListMultimap<String, SQLEntry> listEvents(Statement stat, String query)
      throws MalformedQueryException {
    ResultSet rs = null;
    try {
      rs = stat.executeQuery(query);
      ListMultimap<String, SQLEntry> result = ArrayListMultimap.create();
      while (rs.next()) {
        SQLEntry entry =
            new SQLEntry(rs.getString(PROJECT_ENTRY),
                rs.getTimestamp(DATE_ENTRY), rs.getString(EVENT_ENTRY),
                rs.getInt(PRIMARY_ENTRY));
        result.put(rs.getString(PROJECT_ENTRY), entry);
      }
      return result;
    } catch (SQLException e) {
      throw new MalformedQueryException(e);
    } finally {
      closeResultSet(rs);
    }
  }

  private void closeResultSet(ResultSet resultSet) {
    if (resultSet != null) {
      try {
        resultSet.close();
      } catch (SQLException e) {
        log.warn("Cannot close result set", e);
      }
    }
  }

  private void closeStatement(Statement stat) {
    if (stat != null) {
      try {
        stat.close();
      } catch (SQLException e) {
        log.warn("Cannot close statement", e);
      }
    }
  }

  private void closeConnection(Connection conn) {
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException e) {
        log.warn("Cannot close connection", e);
      }
    }
  }
}
