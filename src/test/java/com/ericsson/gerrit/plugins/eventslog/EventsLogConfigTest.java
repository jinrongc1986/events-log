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

import static com.google.common.truth.Truth.assertThat;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_CONN_TIME;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_COPY_LOCAL;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_DRIVER;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_MAX_AGE;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_PASSWORD;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_RETURN_LIMIT;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_URL;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_URL_OPTIONS;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.CONFIG_USERNAME;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.DEFAULT_CONN_TIME;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.DEFAULT_COPY_LOCAL;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.DEFAULT_DRIVER;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.DEFAULT_MAX_AGE;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.DEFAULT_RETURN_LIMIT;
import static com.ericsson.gerrit.plugins.eventslog.EventsLogConfig.DEFAULT_URL;
import static org.easymock.EasyMock.expect;

import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.config.SitePaths;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.FileNotFoundException;

public class EventsLogConfigTest {
  private SitePaths site;
  private EventsLogConfig config;
  private PluginConfigFactory cfgFactoryMock;
  private PluginConfig configMock;
  private PluginConfigFactory cfgFactoryMock2;
  private PluginConfig configMock2;

  @Rule
  public TemporaryFolder gerrit_site = new TemporaryFolder();

  @Before
  public void setUp() throws FileNotFoundException {
    EasyMockSupport easyMock = new EasyMockSupport();
    site = new SitePaths(gerrit_site.getRoot());
    site.etc_dir.mkdirs();
    configMock = easyMock.createNiceMock(PluginConfig.class);
    expect(configMock.getBoolean(CONFIG_COPY_LOCAL, DEFAULT_COPY_LOCAL)).andReturn(DEFAULT_COPY_LOCAL);
    expect(configMock.getInt(CONFIG_MAX_AGE, DEFAULT_MAX_AGE)).andReturn(DEFAULT_MAX_AGE);
    expect(configMock.getInt(CONFIG_RETURN_LIMIT, DEFAULT_RETURN_LIMIT)).andReturn(DEFAULT_RETURN_LIMIT);
    expect(configMock.getInt(CONFIG_CONN_TIME, DEFAULT_CONN_TIME)).andReturn(DEFAULT_CONN_TIME);
    expect(configMock.getString(CONFIG_DRIVER, DEFAULT_DRIVER)).andReturn(DEFAULT_DRIVER);
    expect(configMock.getString(CONFIG_URL, DEFAULT_URL)).andReturn(DEFAULT_URL);
    expect(configMock.getString(CONFIG_URL_OPTIONS, "")).andReturn("");
    expect(configMock.getString(CONFIG_USERNAME)).andReturn(null);
    expect(configMock.getString(CONFIG_PASSWORD)).andReturn(null);

    cfgFactoryMock = easyMock.createNiceMock(PluginConfigFactory.class);
    expect(cfgFactoryMock.getFromGerritConfig(EasyMock.anyString(),
        EasyMock.anyBoolean())).andStubReturn(configMock);

    configMock2 = easyMock.createNiceMock(PluginConfig.class);
    expect(configMock2.getBoolean(CONFIG_COPY_LOCAL, DEFAULT_COPY_LOCAL)).andReturn(true);
    expect(configMock2.getInt(CONFIG_MAX_AGE, DEFAULT_MAX_AGE)).andReturn(20);
    expect(configMock2.getInt(CONFIG_RETURN_LIMIT, DEFAULT_RETURN_LIMIT)).andReturn(10000);
    expect(configMock2.getInt(CONFIG_CONN_TIME, DEFAULT_CONN_TIME)).andReturn(5000);
    expect(configMock2.getString(CONFIG_DRIVER, DEFAULT_DRIVER)).andReturn("org.h2.Driver2");
    expect(configMock2.getString(CONFIG_URL, DEFAULT_URL)).andReturn("jdbc:h2:~/gerrit/db");
    expect(configMock2.getString(CONFIG_URL_OPTIONS, "")).andReturn("DB_CLOSE_DELAY=10");
    expect(configMock2.getString(CONFIG_USERNAME)).andReturn("testUsername");
    expect(configMock2.getString(CONFIG_PASSWORD)).andReturn("testPassword");
    cfgFactoryMock2 = easyMock.createNiceMock(PluginConfigFactory.class);
    expect(cfgFactoryMock2.getFromGerritConfig(EasyMock.anyString(),
        EasyMock.anyBoolean())).andStubReturn(configMock2);
    easyMock.replayAll();
  }

  @Test
  public void shouldReturnDefaultsWhenMissingConfig() {
    config = new EventsLogConfig(cfgFactoryMock, site, null);
    assertThat(config.getCopyLocal()).isFalse();
    assertThat(config.getMaxAge()).isEqualTo(30);
    assertThat(config.getReturnLimit()).isEqualTo(5000);
    assertThat(config.getConnectTime()).isEqualTo(1000);
    assertThat(config.getStoreDriver()).isEqualTo("org.h2.Driver");
    assertThat(config.getStoreUrl()).isEqualTo("jdbc:h2:~/db/");
    assertThat(config.getUrlOptions()).isEmpty();
    assertThat(config.getStoreUsername()).isNull();
    assertThat(config.getStorePassword()).isNull();
  }

  @Test
  public void shouldReturnConfigValues() {
    config = new EventsLogConfig(cfgFactoryMock2, site, null);
    assertThat(config.getCopyLocal()).isTrue();
    assertThat(config.getMaxAge()).isEqualTo(20);
    assertThat(config.getReturnLimit()).isEqualTo(10000);
    assertThat(config.getConnectTime()).isEqualTo(5000);
    assertThat(config.getStoreDriver()).isEqualTo("org.h2.Driver2");
    assertThat(config.getStoreUrl()).isEqualTo("jdbc:h2:~/gerrit/db");
    assertThat(config.getUrlOptions()).isEqualTo("DB_CLOSE_DELAY=10");
    assertThat(config.getStoreUsername()).isEqualTo("testUsername");
    assertThat(config.getStorePassword()).isEqualTo("testPassword");
  }
}
