/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server.master.balancer;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.accumulo.core.client.impl.Namespace;
import org.apache.accumulo.core.client.impl.Table;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ConfigurationCopy;
import org.apache.accumulo.core.conf.DefaultConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.accumulo.core.data.thrift.TKeyExtent;
import org.apache.accumulo.core.master.thrift.TabletServerStatus;
import org.apache.accumulo.core.tabletserver.thrift.TabletStats;
import org.apache.accumulo.fate.util.UtilWaitThread;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.conf.NamespaceConfiguration;
import org.apache.accumulo.server.conf.ServerConfigurationFactory;
import org.apache.accumulo.server.conf.TableConfiguration;
import org.apache.accumulo.server.master.state.TServerInstance;
import org.apache.accumulo.server.master.state.TabletMigration;
import org.apache.thrift.TException;
import org.junit.Test;

public class HostRegexTableLoadBalancerTest extends BaseHostRegexTableLoadBalancerTest {

  public void init() {
    ServerContext context1 = createMockContext();
    replay(context1);
    final TestServerConfigurationFactory factory = new TestServerConfigurationFactory(context1);
    initFactory(factory);
  }

  private void initFactory(ServerConfigurationFactory factory) {
    ServerContext context = createMockContext();
    expect(context.getServerConfFactory()).andReturn(factory).anyTimes();
    replay(context);
    init(context);
  }

  @Test
  public void testInit() {
    init();
    assertEquals("OOB check interval value is incorrect", 7000, this.getOobCheckMillis());
    assertEquals("Max migrations is incorrect", 4, this.getMaxMigrations());
    assertEquals("Max outstanding migrations is incorrect", 10, this.getMaxOutstandingMigrations());
    assertFalse(isIpBasedRegex());
    Map<String,Pattern> patterns = this.getPoolNameToRegexPattern();
    assertEquals(2, patterns.size());
    assertTrue(patterns.containsKey(FOO.getTableName()));
    assertEquals(Pattern.compile("r01.*").pattern(), patterns.get(FOO.getTableName()).pattern());
    assertTrue(patterns.containsKey(BAR.getTableName()));
    assertEquals(Pattern.compile("r02.*").pattern(), patterns.get(BAR.getTableName()).pattern());
    Map<Table.ID,String> tids = this.getTableIdToTableName();
    assertEquals(3, tids.size());
    assertTrue(tids.containsKey(FOO.getId()));
    assertEquals(FOO.getTableName(), tids.get(FOO.getId()));
    assertTrue(tids.containsKey(BAR.getId()));
    assertEquals(BAR.getTableName(), tids.get(BAR.getId()));
    assertTrue(tids.containsKey(BAZ.getId()));
    assertEquals(BAZ.getTableName(), tids.get(BAZ.getId()));
    assertFalse(this.isIpBasedRegex());
  }

  @Test
  public void testBalance() {
    init();
    Set<KeyExtent> migrations = new HashSet<>();
    List<TabletMigration> migrationsOut = new ArrayList<>();
    long wait = this.balance(Collections.unmodifiableSortedMap(createCurrent(15)), migrations,
        migrationsOut);
    assertEquals(20000, wait);
    // should balance four tablets in one of the tables before reaching max
    assertEquals(4, migrationsOut.size());

    // now balance again passing in the new migrations
    for (TabletMigration m : migrationsOut) {
      migrations.add(m.tablet);
    }
    migrationsOut.clear();
    wait = this.balance(Collections.unmodifiableSortedMap(createCurrent(15)), migrations,
        migrationsOut);
    assertEquals(20000, wait);
    // should balance four tablets in one of the other tables before reaching max
    assertEquals(4, migrationsOut.size());

    // now balance again passing in the new migrations
    for (TabletMigration m : migrationsOut) {
      migrations.add(m.tablet);
    }
    migrationsOut.clear();
    wait = this.balance(Collections.unmodifiableSortedMap(createCurrent(15)), migrations,
        migrationsOut);
    assertEquals(20000, wait);
    // should balance four tablets in one of the other tables before reaching max
    assertEquals(4, migrationsOut.size());

    // now balance again passing in the new migrations
    for (TabletMigration m : migrationsOut) {
      migrations.add(m.tablet);
    }
    migrationsOut.clear();
    wait = this.balance(Collections.unmodifiableSortedMap(createCurrent(15)), migrations,
        migrationsOut);
    assertEquals(20000, wait);
    // no more balancing to do
    assertEquals(0, migrationsOut.size());
  }

  @Test
  public void testBalanceWithTooManyOutstandingMigrations() {
    List<TabletMigration> migrationsOut = new ArrayList<>();
    init();
    // lets say we already have migrations ongoing for the FOO and BAR table extends (should be 5 of
    // each of them) for a total of 10
    Set<KeyExtent> migrations = new HashSet<>();
    migrations.addAll(tableExtents.get(FOO.getTableName()));
    migrations.addAll(tableExtents.get(BAR.getTableName()));
    long wait = this.balance(Collections.unmodifiableSortedMap(createCurrent(15)), migrations,
        migrationsOut);
    assertEquals(20000, wait);
    // no migrations should have occurred as 10 is the maxOutstandingMigrations
    assertEquals(0, migrationsOut.size());
  }

  @Test
  public void testSplitCurrentByRegexUsingHostname() {
    init();
    Map<String,SortedMap<TServerInstance,TabletServerStatus>> groups = this
        .splitCurrentByRegex(createCurrent(15));
    assertEquals(3, groups.size());
    assertTrue(groups.containsKey(FOO.getTableName()));
    SortedMap<TServerInstance,TabletServerStatus> fooHosts = groups.get(FOO.getTableName());
    assertEquals(5, fooHosts.size());
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.1:9997", 1)));
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.2:9997", 1)));
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.3:9997", 1)));
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.4:9997", 1)));
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.5:9997", 1)));
    assertTrue(groups.containsKey(BAR.getTableName()));
    SortedMap<TServerInstance,TabletServerStatus> barHosts = groups.get(BAR.getTableName());
    assertEquals(5, barHosts.size());
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.6:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.7:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.8:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.9:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.10:9997", 1)));
    assertTrue(groups.containsKey(DEFAULT_POOL));
    SortedMap<TServerInstance,TabletServerStatus> defHosts = groups.get(DEFAULT_POOL);
    assertEquals(5, defHosts.size());
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.11:9997", 1)));
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.12:9997", 1)));
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.13:9997", 1)));
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.14:9997", 1)));
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.15:9997", 1)));
  }

  @Test
  public void testSplitCurrentByRegexUsingOverlappingPools() {
    ServerContext context = createMockContext();
    replay(context);
    initFactory(new TestServerConfigurationFactory(context) {

      @Override
      public TableConfiguration getTableConfiguration(Table.ID tableId) {
        NamespaceConfiguration defaultConf = new NamespaceConfiguration(Namespace.ID.DEFAULT,
            this.context, DefaultConfiguration.getInstance());
        return new TableConfiguration(this.context, tableId, defaultConf) {
          HashMap<String,String> tableProperties = new HashMap<>();
          {
            tableProperties
                .put(HostRegexTableLoadBalancer.HOST_BALANCER_PREFIX + FOO.getTableName(), "r.*");
            tableProperties.put(
                HostRegexTableLoadBalancer.HOST_BALANCER_PREFIX + BAR.getTableName(),
                "r01.*|r02.*");
          }

          @Override
          public String get(Property property) {
            return tableProperties.get(property.name());
          }

          @Override
          public void getProperties(Map<String,String> props, Predicate<String> filter) {
            for (Entry<String,String> e : tableProperties.entrySet()) {
              if (filter.test(e.getKey())) {
                props.put(e.getKey(), e.getValue());
              }
            }
          }

          @Override
          public long getUpdateCount() {
            return 0;
          }
        };
      }
    });
    Map<String,SortedMap<TServerInstance,TabletServerStatus>> groups = this
        .splitCurrentByRegex(createCurrent(15));

    // Groups foo, bar, and the default pool which contains all known hosts
    assertEquals(3, groups.size());
    assertTrue(groups.containsKey(FOO.getTableName()));
    assertTrue(groups.containsKey(DEFAULT_POOL));
    for (String pool : new String[] {FOO.getTableName(), DEFAULT_POOL}) {
      SortedMap<TServerInstance,TabletServerStatus> fooHosts = groups.get(pool);
      assertEquals(15, fooHosts.size());
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.1:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.2:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.3:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.4:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.5:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.6:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.7:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.8:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.9:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.10:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.11:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.12:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.13:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.14:9997", 1)));
      assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.15:9997", 1)));
    }

    assertTrue(groups.containsKey(BAR.getTableName()));
    SortedMap<TServerInstance,TabletServerStatus> barHosts = groups.get(BAR.getTableName());
    assertEquals(10, barHosts.size());
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.1:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.2:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.3:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.4:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.5:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.6:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.7:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.8:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.9:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.10:9997", 1)));
  }

  @Test
  public void testSplitCurrentByRegexUsingIP() {
    ServerContext context = createMockContext();
    replay(context);
    initFactory(new TestServerConfigurationFactory(context) {
      @Override
      public synchronized AccumuloConfiguration getSystemConfiguration() {
        HashMap<String,String> props = new HashMap<>();
        props.put(HostRegexTableLoadBalancer.HOST_BALANCER_OOB_CHECK_KEY, "30s");
        props.put(HostRegexTableLoadBalancer.HOST_BALANCER_REGEX_USING_IPS_KEY, "true");
        return new ConfigurationCopy(props);
      }

      @Override
      public TableConfiguration getTableConfiguration(Table.ID tableId) {
        NamespaceConfiguration defaultConf = new NamespaceConfiguration(Namespace.ID.DEFAULT,
            this.context, DefaultConfiguration.getInstance());
        return new TableConfiguration(context, tableId, defaultConf) {
          HashMap<String,String> tableProperties = new HashMap<>();
          {
            tableProperties.put(
                HostRegexTableLoadBalancer.HOST_BALANCER_PREFIX + FOO.getTableName(),
                "192\\.168\\.0\\.[1-5]");
            tableProperties.put(
                HostRegexTableLoadBalancer.HOST_BALANCER_PREFIX + BAR.getTableName(),
                "192\\.168\\.0\\.[6-9]|192\\.168\\.0\\.10");
          }

          @Override
          public String get(Property property) {
            return tableProperties.get(property.name());
          }

          @Override
          public void getProperties(Map<String,String> props, Predicate<String> filter) {
            for (Entry<String,String> e : tableProperties.entrySet()) {
              if (filter.test(e.getKey())) {
                props.put(e.getKey(), e.getValue());
              }
            }
          }

          @Override
          public long getUpdateCount() {
            return 0;
          }
        };
      }
    });
    assertTrue(isIpBasedRegex());
    Map<String,SortedMap<TServerInstance,TabletServerStatus>> groups = this
        .splitCurrentByRegex(createCurrent(15));
    assertEquals(3, groups.size());
    assertTrue(groups.containsKey(FOO.getTableName()));
    SortedMap<TServerInstance,TabletServerStatus> fooHosts = groups.get(FOO.getTableName());
    assertEquals(5, fooHosts.size());
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.1:9997", 1)));
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.2:9997", 1)));
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.3:9997", 1)));
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.4:9997", 1)));
    assertTrue(fooHosts.containsKey(new TServerInstance("192.168.0.5:9997", 1)));
    assertTrue(groups.containsKey(BAR.getTableName()));
    SortedMap<TServerInstance,TabletServerStatus> barHosts = groups.get(BAR.getTableName());
    assertEquals(5, barHosts.size());
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.6:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.7:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.8:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.9:9997", 1)));
    assertTrue(barHosts.containsKey(new TServerInstance("192.168.0.10:9997", 1)));
    assertTrue(groups.containsKey(DEFAULT_POOL));
    SortedMap<TServerInstance,TabletServerStatus> defHosts = groups.get(DEFAULT_POOL);
    assertEquals(5, defHosts.size());
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.11:9997", 1)));
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.12:9997", 1)));
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.13:9997", 1)));
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.14:9997", 1)));
    assertTrue(defHosts.containsKey(new TServerInstance("192.168.0.15:9997", 1)));
  }

  @Test
  public void testAllUnassigned() {
    init();
    Map<KeyExtent,TServerInstance> assignments = new HashMap<>();
    Map<KeyExtent,TServerInstance> unassigned = new HashMap<>();
    for (List<KeyExtent> extents : tableExtents.values()) {
      for (KeyExtent ke : extents) {
        unassigned.put(ke, null);
      }
    }
    this.getAssignments(Collections.unmodifiableSortedMap(allTabletServers),
        Collections.unmodifiableMap(unassigned), assignments);
    assertEquals(15, assignments.size());
    // Ensure unique tservers
    for (Entry<KeyExtent,TServerInstance> e : assignments.entrySet()) {
      for (Entry<KeyExtent,TServerInstance> e2 : assignments.entrySet()) {
        if (e.getKey().equals(e2.getKey())) {
          continue;
        }
        if (e.getValue().equals(e2.getValue())) {
          fail("Assignment failure");
        }
      }
    }
    // Ensure assignments are correct
    for (Entry<KeyExtent,TServerInstance> e : assignments.entrySet()) {
      if (!tabletInBounds(e.getKey(), e.getValue())) {
        fail("tablet not in bounds: " + e.getKey() + " -> " + e.getValue().host());
      }
    }
  }

  @Test
  public void testAllAssigned() {
    init();
    Map<KeyExtent,TServerInstance> assignments = new HashMap<>();
    Map<KeyExtent,TServerInstance> unassigned = new HashMap<>();
    this.getAssignments(Collections.unmodifiableSortedMap(allTabletServers),
        Collections.unmodifiableMap(unassigned), assignments);
    assertEquals(0, assignments.size());
  }

  @Test
  public void testPartiallyAssigned() {
    init();
    Map<KeyExtent,TServerInstance> assignments = new HashMap<>();
    Map<KeyExtent,TServerInstance> unassigned = new HashMap<>();
    int i = 0;
    for (List<KeyExtent> extents : tableExtents.values()) {
      for (KeyExtent ke : extents) {
        if ((i % 2) == 0) {
          unassigned.put(ke, null);
        }
        i++;
      }
    }
    this.getAssignments(Collections.unmodifiableSortedMap(allTabletServers),
        Collections.unmodifiableMap(unassigned), assignments);
    assertEquals(unassigned.size(), assignments.size());
    // Ensure unique tservers
    for (Entry<KeyExtent,TServerInstance> e : assignments.entrySet()) {
      for (Entry<KeyExtent,TServerInstance> e2 : assignments.entrySet()) {
        if (e.getKey().equals(e2.getKey())) {
          continue;
        }
        if (e.getValue().equals(e2.getValue())) {
          fail("Assignment failure");
        }
      }
    }
    // Ensure assignments are correct
    for (Entry<KeyExtent,TServerInstance> e : assignments.entrySet()) {
      if (!tabletInBounds(e.getKey(), e.getValue())) {
        fail("tablet not in bounds: " + e.getKey() + " -> " + e.getValue().host());
      }
    }
  }

  @Test
  public void testUnassignedWithNoTServers() {
    init();
    Map<KeyExtent,TServerInstance> assignments = new HashMap<>();
    Map<KeyExtent,TServerInstance> unassigned = new HashMap<>();
    for (KeyExtent ke : tableExtents.get(BAR.getTableName())) {
      unassigned.put(ke, null);
    }
    SortedMap<TServerInstance,TabletServerStatus> current = createCurrent(15);
    // Remove the BAR tablet servers from current
    List<TServerInstance> removals = new ArrayList<>();
    for (Entry<TServerInstance,TabletServerStatus> e : current.entrySet()) {
      if (e.getKey().host().equals("192.168.0.6") || e.getKey().host().equals("192.168.0.7")
          || e.getKey().host().equals("192.168.0.8") || e.getKey().host().equals("192.168.0.9")
          || e.getKey().host().equals("192.168.0.10")) {
        removals.add(e.getKey());
      }
    }
    for (TServerInstance r : removals) {
      current.remove(r);
    }
    this.getAssignments(Collections.unmodifiableSortedMap(current),
        Collections.unmodifiableMap(unassigned), assignments);
    assertEquals(unassigned.size(), assignments.size());
    // Ensure assignments are correct
    // Ensure tablets are assigned in default pool
    for (Entry<KeyExtent,TServerInstance> e : assignments.entrySet()) {
      if (tabletInBounds(e.getKey(), e.getValue())) {
        fail("tablet unexpectedly in bounds: " + e.getKey() + " -> " + e.getValue().host());
      }
    }
  }

  @Test
  public void testUnassignedWithNoDefaultPool() {
    init();
    Map<KeyExtent,TServerInstance> assignments = new HashMap<>();
    Map<KeyExtent,TServerInstance> unassigned = new HashMap<>();
    for (KeyExtent ke : tableExtents.get(BAR.getTableName())) {
      unassigned.put(ke, null);
    }

    SortedMap<TServerInstance,TabletServerStatus> current = createCurrent(15);
    // Remove the BAR tablet servers and default pool from current
    List<TServerInstance> removals = new ArrayList<>();
    for (Entry<TServerInstance,TabletServerStatus> e : current.entrySet()) {
      if (e.getKey().host().equals("192.168.0.6") || e.getKey().host().equals("192.168.0.7")
          || e.getKey().host().equals("192.168.0.8") || e.getKey().host().equals("192.168.0.9")
          || e.getKey().host().equals("192.168.0.10") || e.getKey().host().equals("192.168.0.11")
          || e.getKey().host().equals("192.168.0.12") || e.getKey().host().equals("192.168.0.13")
          || e.getKey().host().equals("192.168.0.14") || e.getKey().host().equals("192.168.0.15")) {
        removals.add(e.getKey());
      }
    }

    for (TServerInstance r : removals) {
      current.remove(r);
    }

    this.getAssignments(Collections.unmodifiableSortedMap(current),
        Collections.unmodifiableMap(unassigned), assignments);
    assertEquals(unassigned.size(), assignments.size());

    // Ensure tablets are assigned in default pool
    for (Entry<KeyExtent,TServerInstance> e : assignments.entrySet()) {
      if (tabletInBounds(e.getKey(), e.getValue())) {
        fail("tablet unexpectedly in bounds: " + e.getKey() + " -> " + e.getValue().host());
      }
    }
  }

  @Test
  public void testOutOfBoundsTablets() {
    init();
    // Wait to trigger the out of bounds check which will call our version of
    // getOnlineTabletsForTable
    UtilWaitThread.sleep(11000);
    Set<KeyExtent> migrations = new HashSet<>();
    List<TabletMigration> migrationsOut = new ArrayList<>();
    this.balance(createCurrent(15), migrations, migrationsOut);
    assertEquals(2, migrationsOut.size());
  }

  @Override
  public List<TabletStats> getOnlineTabletsForTable(TServerInstance tserver, Table.ID tableId)
      throws TException {
    // Report incorrect information so that balance will create an assignment
    List<TabletStats> tablets = new ArrayList<>();
    if (tableId.equals(BAR.getId()) && tserver.host().equals("192.168.0.1")) {
      // Report that we have a bar tablet on this server
      TKeyExtent tke = new TKeyExtent();
      tke.setTable(BAR.getId().getUtf8());
      tke.setEndRow("11".getBytes());
      tke.setPrevEndRow("10".getBytes());
      TabletStats ts = new TabletStats();
      ts.setExtent(tke);
      tablets.add(ts);
    } else if (tableId.equals(FOO.getId()) && tserver.host().equals("192.168.0.6")) {
      // Report that we have a foo tablet on this server
      TKeyExtent tke = new TKeyExtent();
      tke.setTable(FOO.getId().getUtf8());
      tke.setEndRow("1".getBytes());
      tke.setPrevEndRow("0".getBytes());
      TabletStats ts = new TabletStats();
      ts.setExtent(tke);
      tablets.add(ts);
    }
    return tablets;
  }

}
