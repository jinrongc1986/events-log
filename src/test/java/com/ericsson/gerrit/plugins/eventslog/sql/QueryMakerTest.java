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

package com.ericsson.gerrit.plugins.eventslog.sql;

import static com.google.common.truth.Truth.assertThat;
import static org.easymock.EasyMock.expect;

import com.ericsson.gerrit.plugins.eventslog.EventsLogConfig;
import com.ericsson.gerrit.plugins.eventslog.MalformedQueryException;
import com.ericsson.gerrit.plugins.eventslog.QueryMaker;
import com.ericsson.gerrit.plugins.eventslog.sql.SQLQueryMaker;

import org.easymock.EasyMockSupport;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class QueryMakerTest {
  private static QueryMaker queryMaker;
  private static String defaultQuery;

  @BeforeClass
  public static void setUp() throws Exception {
    EasyMockSupport easyMock = new EasyMockSupport();
    EventsLogConfig cfgMock = easyMock.createMock(EventsLogConfig.class);
    expect(cfgMock.getReturnLimit()).andReturn(10);
    easyMock.replayAll();
    queryMaker = new SQLQueryMaker(cfgMock);
    defaultQuery = queryMaker.getDefaultQuery();
  }

  @Test
  public void returnDefaultforNullMap() throws Exception {
    assertThat(queryMaker.formQueryFromRequestParameters(null)).isEqualTo(defaultQuery);
  }

  @Test(expected = MalformedQueryException.class)
  public void badParameters() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("t1", "bad format");
    params.put("t2", "bad format");
    queryMaker.formQueryFromRequestParameters(params);
  }

  @Test
  public void dateOneOnly() throws Exception {
    Map<String, String> params = new HashMap<>();
    String oldDate = "1990-10-10 10:00:00";
    params.put("t1", oldDate);
    String query = queryMaker.formQueryFromRequestParameters(params);
    assertThat(query).contains(String.format("'%s' and ", oldDate));
  }

  @Test
  public void dateTwoOnly() throws Exception {
    Map<String, String> params = new HashMap<>();
    String oldDate = "1990-10-10 10:00:00";
    params.put("t2", oldDate);
    String query = queryMaker.formQueryFromRequestParameters(params);
    assertThat(query).contains(String.format("'%s' and ", oldDate));
  }

  @Test(expected = MalformedQueryException.class)
  public void noDate() throws Exception {
    Map<String, String> params = new HashMap<>();
    queryMaker.formQueryFromRequestParameters(params);
  }

  @Test
  public void dateOrdering() throws Exception {
    String query;
    Map<String, String> params = new HashMap<>();
    String olderDate = "2013-10-10 10:00:00";
    String newerDate = "2014-10-10 10:00:00";

    params.put("t1", olderDate);
    params.put("t2", newerDate);
    query = queryMaker.formQueryFromRequestParameters(params);
    assertThat(query).contains(String.format("'%s' and '%s'",
        olderDate, newerDate));

    params.put("t1", newerDate);
    params.put("t2", olderDate);
    query = queryMaker.formQueryFromRequestParameters(params);
    assertThat(query).contains(String.format("'%s' and '%s'",
        olderDate, newerDate));
  }

  @Test
  public void bothDateTime() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("t1", "2013-10-10 10:00:00");
    params.put("t2", "2014-10-10 10:00:00");
    String query = queryMaker.formQueryFromRequestParameters(params);
    assertThat(query).isNotEqualTo(defaultQuery);
  }

  @Test
  public void onlyDateNoTime() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("t1", "2013-10-10");
    params.put("t2", "2014-10-10");
    String query = queryMaker.formQueryFromRequestParameters(params);
    assertThat(query).isNotEqualTo(defaultQuery);
  }
}