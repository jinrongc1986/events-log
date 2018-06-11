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

import static java.lang.String.format;

final class SQLTable {
  static final String TABLE_NAME = "ChangeEvents";
  static final String PRIMARY_ENTRY = "id";
  static final String PROJECT_ENTRY = "project";
  static final String DATE_ENTRY = "date_created";
  static final String EVENT_ENTRY = "event_info";

  private SQLTable() {}

  static String createTableQuery(boolean postgresql) {
    StringBuilder query = new StringBuilder();
    query.append(format("CREATE TABLE IF NOT EXISTS %s(", TABLE_NAME));
    if (postgresql) {
      query.append(format("%s SERIAL PRIMARY KEY,", PRIMARY_ENTRY));
    } else {
      query.append(format("%s INT AUTO_INCREMENT PRIMARY KEY,", PRIMARY_ENTRY));
    }
    query.append(format("%s VARCHAR(255),", PROJECT_ENTRY));
    query.append(format("%s TIMESTAMP DEFAULT NOW(),", DATE_ENTRY));
    query.append(format("%s TEXT)", EVENT_ENTRY));
    return query.toString();
  }
}
