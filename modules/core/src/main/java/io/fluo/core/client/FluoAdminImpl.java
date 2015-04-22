/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.core.client;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Preconditions;
import io.fluo.accumulo.format.FluoFormatter;
import io.fluo.accumulo.iterators.GarbageCollectionIterator;
import io.fluo.accumulo.util.AccumuloProps;
import io.fluo.accumulo.util.ColumnConstants;
import io.fluo.accumulo.util.ZookeeperPath;
import io.fluo.accumulo.util.ZookeeperUtil;
import io.fluo.api.client.FluoAdmin;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.api.config.ObserverConfiguration;
import io.fluo.api.data.Column;
import io.fluo.api.exceptions.FluoException;
import io.fluo.api.observer.Observer;
import io.fluo.api.observer.Observer.NotificationType;
import io.fluo.api.observer.Observer.ObservedColumn;
import io.fluo.core.util.AccumuloUtil;
import io.fluo.core.util.ByteUtil;
import io.fluo.core.util.CuratorUtil;
import io.fluo.core.worker.ObserverContext;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.iterators.IteratorUtil;
import org.apache.commons.configuration.ConfigurationUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.io.Text;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fluo Admin Implementation
 */
public class FluoAdminImpl implements FluoAdmin {

  private static Logger logger = LoggerFactory.getLogger(FluoAdminImpl.class);
  private final FluoConfiguration config;
  private final CuratorFramework rootCurator;
  private CuratorFramework fluoCurator = null;

  private final String appRootDir;

  public FluoAdminImpl(FluoConfiguration config) {
    this.config = config;
    if (!config.hasRequiredAdminProps()) {
      throw new IllegalArgumentException("Admin configuration is missing required properties");
    }

    appRootDir = ZookeeperUtil.parseRoot(config.getAppZookeepers());
    rootCurator = CuratorUtil.newRootFluoCurator(config);
    rootCurator.start();
  }

  private synchronized CuratorFramework getFluoCurator() {
    if (fluoCurator == null) {
      fluoCurator = CuratorUtil.newAppCurator(config);
      fluoCurator.start();
    }
    return fluoCurator;
  }

  @Override
  public void initialize(InitOpts opts) throws AlreadyInitializedException, TableExistsException {
    Preconditions.checkArgument(!ZookeeperUtil.parseRoot(config.getInstanceZookeepers())
        .equals("/"),
        "The Zookeeper connection string (set by 'io.fluo.client.zookeeper.connect') "
            + " must have a chroot suffix.");

    if (zookeeperInitialized() && !opts.getClearZookeeper()) {
      throw new AlreadyInitializedException("Fluo application already initialized at "
          + config.getAppZookeepers());
    }

    Connector conn = AccumuloUtil.getConnector(config);

    boolean tableExists = conn.tableOperations().exists(config.getAccumuloTable());
    if (tableExists && !opts.getClearTable()) {
      throw new TableExistsException("Accumulo table already exists " + config.getAccumuloTable());
    }

    // With preconditions met, it's now OK to delete table & zookeeper root (if they exist)

    if (tableExists) {
      logger.info("The Accumulo table '{}' will be dropped and created as requested by user",
          config.getAccumuloTable());
      try {
        conn.tableOperations().delete(config.getAccumuloTable());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    try {
      if (rootCurator.checkExists().forPath(appRootDir) != null) {
        logger.info("Clearing Fluo '{}' application in Zookeeper at {}",
            config.getApplicationName(), config.getAppZookeepers());
        rootCurator.delete().deletingChildrenIfNeeded().forPath(appRootDir);
      }
    } catch (KeeperException.NoNodeException nne) {
    } catch (Exception e) {
      logger.error("An error occurred deleting Zookeeper root of [" + config.getAppZookeepers()
          + "], error=[" + e.getMessage() + "]");
      throw new RuntimeException(e);
    }

    try {
      initialize(conn);
      updateSharedConfig();

      if (!config.getAccumuloClasspath().trim().isEmpty()) {
        // TODO add fluo version to context name to make it unique
        String contextName = "fluo";
        conn.instanceOperations().setProperty(
            AccumuloProps.VFS_CONTEXT_CLASSPATH_PROPERTY + "fluo", config.getAccumuloClasspath());
        conn.tableOperations().setProperty(config.getAccumuloTable(),
            AccumuloProps.TABLE_CLASSPATH, contextName);
      }

      conn.tableOperations().setProperty(config.getAccumuloTable(),
          AccumuloProps.TABLE_BLOCKCACHE_ENABLED, "true");
    } catch (NodeExistsException nee) {
      throw new AlreadyInitializedException();
    } catch (Exception e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new RuntimeException(e);
    }
  }

  private void initialize(Connector conn) throws Exception {

    final String accumuloInstanceName = conn.getInstance().getInstanceName();
    final String accumuloInstanceID = conn.getInstance().getInstanceID();
    final String fluoApplicationID = UUID.randomUUID().toString();

    // Create node specified by chroot suffix of Zookeeper connection string (if it doesn't exist)
    CuratorUtil.putData(rootCurator, appRootDir, new byte[0], CuratorUtil.NodeExistsPolicy.FAIL);

    // Retrieve Fluo curator now that chroot has been created
    CuratorFramework curator = getFluoCurator();

    // Initialize Zookeeper & Accumulo for this Fluo instance
    // TODO set Fluo data version
    CuratorUtil.putData(curator, ZookeeperPath.CONFIG, new byte[0],
        CuratorUtil.NodeExistsPolicy.FAIL);
    CuratorUtil.putData(curator, ZookeeperPath.CONFIG_ACCUMULO_TABLE, config.getAccumuloTable()
        .getBytes("UTF-8"), CuratorUtil.NodeExistsPolicy.FAIL);
    CuratorUtil.putData(curator, ZookeeperPath.CONFIG_ACCUMULO_INSTANCE_NAME,
        accumuloInstanceName.getBytes("UTF-8"), CuratorUtil.NodeExistsPolicy.FAIL);
    CuratorUtil.putData(curator, ZookeeperPath.CONFIG_ACCUMULO_INSTANCE_ID,
        accumuloInstanceID.getBytes("UTF-8"), CuratorUtil.NodeExistsPolicy.FAIL);
    CuratorUtil.putData(curator, ZookeeperPath.CONFIG_FLUO_APPLICATION_ID,
        fluoApplicationID.getBytes("UTF-8"), CuratorUtil.NodeExistsPolicy.FAIL);
    CuratorUtil.putData(curator, ZookeeperPath.ORACLE_SERVER, new byte[0],
        CuratorUtil.NodeExistsPolicy.FAIL);
    CuratorUtil.putData(curator, ZookeeperPath.ORACLE_MAX_TIMESTAMP, new byte[] {'2'},
        CuratorUtil.NodeExistsPolicy.FAIL);
    CuratorUtil.putData(curator, ZookeeperPath.ORACLE_CUR_TIMESTAMP, new byte[] {'0'},
        CuratorUtil.NodeExistsPolicy.FAIL);

    // TODO may need to configure an iterator that squishes multiple notifications to one at
    // compaction time since versioning iterator is not configured for
    // table...

    conn.tableOperations().create(config.getAccumuloTable(), false);
    Map<String, Set<Text>> groups = new HashMap<>();
    groups.put("notify", Collections.singleton(ByteUtil.toText(ColumnConstants.NOTIFY_CF)));
    conn.tableOperations().setLocalityGroups(config.getAccumuloTable(), groups);

    IteratorSetting gcIter = new IteratorSetting(10, GarbageCollectionIterator.class);
    GarbageCollectionIterator.setZookeepers(gcIter, config.getAppZookeepers());

    conn.tableOperations().attachIterator(config.getAccumuloTable(), gcIter,
        EnumSet.of(IteratorUtil.IteratorScope.majc, IteratorUtil.IteratorScope.minc));

    conn.tableOperations().setProperty(config.getAccumuloTable(),
        AccumuloProps.TABLE_FORMATTER_CLASS, FluoFormatter.class.getName());
  }

  @Override
  public void updateSharedConfig() {

    logger.info("Setting up observers using app config: {}",
        ConfigurationUtils.toString(config.subset(FluoConfiguration.APP_PREFIX)));

    Map<Column, ObserverConfiguration> colObservers = new HashMap<>();
    Map<Column, ObserverConfiguration> weakObservers = new HashMap<>();
    for (ObserverConfiguration observerConfig : config.getObserverConfig()) {

      Observer observer;
      try {
        observer =
            Class.forName(observerConfig.getClassName()).asSubclass(Observer.class).newInstance();
      } catch (ClassNotFoundException e1) {
        throw new FluoException("Observer class '" + observerConfig.getClassName() + "' was not "
            + "found.  Check for class name misspellings or failure to include "
            + "the observer jar.", e1);
      } catch (InstantiationException | IllegalAccessException e2) {
        throw new FluoException("Observer class '" + observerConfig.getClassName()
            + "' could not be created.", e2);
      }

      logger.info("Setting up observer {} using params {}.", observer.getClass().getSimpleName(),
          observerConfig.getParameters());
      try {
        observer.init(new ObserverContext(config.subset(FluoConfiguration.APP_PREFIX),
            observerConfig.getParameters()));
      } catch (Exception e) {
        throw new FluoException("Observer '" + observerConfig.getClassName()
            + "' could not be initialized", e);
      }

      ObservedColumn observedCol = observer.getObservedColumn();
      if (observedCol.getType() == NotificationType.STRONG) {
        colObservers.put(observedCol.getColumn(), observerConfig);
      } else {
        weakObservers.put(observedCol.getColumn(), observerConfig);
      }
    }

    Properties sharedProps = new Properties();
    Iterator<String> iter = config.getKeys();
    while (iter.hasNext()) {
      String key = iter.next();
      if (key.equals(FluoConfiguration.TRANSACTION_ROLLBACK_TIME_PROP)) {
        sharedProps.setProperty(key, Long.toString(config.getLong(key)));
      } else if (key.startsWith(FluoConfiguration.APP_PREFIX)) {
        sharedProps.setProperty(key, config.getProperty(key).toString());
      }
    }

    try {
      CuratorFramework curator = getFluoCurator();
      Operations.updateObservers(curator, colObservers, weakObservers);
      Operations.updateSharedConfig(curator, sharedProps);
    } catch (Exception e) {
      throw new FluoException("Failed to update shared configuration in Zookeeper", e);
    }
  }

  @Override
  public void close() {
    rootCurator.close();
    if (fluoCurator != null) {
      fluoCurator.close();
    }
  }

  public boolean oracleExists() {
    CuratorFramework curator = getFluoCurator();
    try {
      return curator.checkExists().forPath(ZookeeperPath.ORACLE_SERVER) != null
          && !curator.getChildren().forPath(ZookeeperPath.ORACLE_SERVER).isEmpty();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public boolean zookeeperInitialized() {
    try {
      return rootCurator.checkExists().forPath(appRootDir) != null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public boolean accumuloTableExists() {
    Connector conn = AccumuloUtil.getConnector(config);
    return conn.tableOperations().exists(config.getAccumuloTable());
  }
}
