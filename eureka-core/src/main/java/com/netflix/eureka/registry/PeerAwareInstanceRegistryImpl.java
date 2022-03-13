/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import com.netflix.appinfo.*;
import com.netflix.appinfo.AmazonInfo.MetaDataKey;
import com.netflix.appinfo.DataCenterInfo.Name;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.Version;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.rule.*;
import com.netflix.eureka.resources.ASGResource.ASGStatus;
import com.netflix.eureka.resources.CurrentRequestVersion;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.util.*;

import static com.netflix.eureka.Names.METRIC_REGISTRY_PREFIX;

/**
 * Handles replication of all operations to {@link AbstractInstanceRegistry} to peer <em>Eureka</em>
 * nodes to keep them all in sync.
 *
 * <p>Primary operations that are replicated are the <em>Registers,Renewals,Cancels,Expirations and
 * Status Changes</em>
 *
 * <p>When the eureka server starts up it tries to fetch all the registry information from the peer
 * eureka nodes.If for some reason this operation fails, the server does not allow the user to get
 * the registry information for a period specified in {@link
 * com.netflix.eureka.EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}.
 *
 * <p>One important thing to note about <em>renewals</em>.If the renewal drops more than the
 * specified threshold as specified in {@link
 * com.netflix.eureka.EurekaServerConfig#getRenewalPercentThreshold()} within a period of {@link
 * com.netflix.eureka.EurekaServerConfig#getRenewalThresholdUpdateIntervalMs()}, eureka perceives
 * this as a danger and stops expiring instances.
 *
 * @author Karthik Ranganathan, Greg Kim
 */
@Singleton
public class PeerAwareInstanceRegistryImpl extends AbstractInstanceRegistry
    implements PeerAwareInstanceRegistry {
  private static final Comparator<Application> APP_COMPARATOR =
      new Comparator<Application>() {
        public int compare(Application l, Application r) {
          return l.getName().compareTo(r.getName());
        }
      };
  private static final int PRIME_PEER_NODES_RETRY_MS = 30000;
  private static final String US_EAST_1 = "us-east-1";
  private static final Logger logger = LoggerFactory.getLogger(PeerAwareInstanceRegistryImpl.class);
  protected final EurekaClient eurekaClient;
  private final InstanceStatusOverrideRule instanceStatusOverrideRule;
  private final MeasuredRate numberOfReplicationsLastMin;
  protected volatile PeerEurekaNodes peerEurekaNodes;
  private boolean peerInstancesTransferEmptyOnStartup = true;
  private long startupTime = 0;
  private Timer timer = new Timer("ReplicaAwareInstanceRegistry - RenewalThresholdUpdater", true);

  @Inject
  public PeerAwareInstanceRegistryImpl(
      EurekaServerConfig serverConfig,
      EurekaClientConfig clientConfig,
      ServerCodecs serverCodecs,
      EurekaClient eurekaClient) {
    super(serverConfig, clientConfig, serverCodecs);
    this.eurekaClient = eurekaClient;
    this.numberOfReplicationsLastMin = new MeasuredRate(1000 * 60 * 1);
    // 我们首先检查实例是 STARTING 还是 DOWN，然后我们检查显式覆盖，
    // 然后我们检查潜在现有租约的状态。
    this.instanceStatusOverrideRule =
        new FirstMatchWinsCompositeRule(
            new DownOrStartingRule(),
            new OverrideExistsRule(overriddenInstanceStatusMap),
            new LeaseExistsRule());
  }

  /*
   * (non-Javadoc)
   *
   * @see com.netflix.eureka.registry.InstanceRegistry#cancel(java.lang.String,
   * java.lang.String, long, boolean)
   */
  @Override
  public boolean cancel(final String appName, final String id, final boolean isReplication) {
    if (super.cancel(appName, id, isReplication)) {
      replicateToPeers(Action.Cancel, appName, id, null, null, isReplication);

      return true;
    }
    return false;
  }

  /*
   * (non-Javadoc)
   *
   * @see com.netflix.eureka.registry.InstanceRegistry#renew(java.lang.String,
   * java.lang.String, long, boolean)
   */
  public boolean renew(final String appName, final String id, final boolean isReplication) {
    if (super.renew(appName, id, isReplication)) {
      replicateToPeers(Action.Heartbeat, appName, id, null, null, isReplication);
      return true;
    }
    return false;
  }

  @Override
  @com.netflix.servo.annotations.Monitor(
      name = "localRegistrySize",
      description = "Current registry size",
      type = DataSourceType.GAUGE)
  public long getLocalRegistrySize() {
    return super.getLocalRegistrySize();
  }

  @Override
  protected InstanceStatusOverrideRule getInstanceInfoOverrideRule() {
    return this.instanceStatusOverrideRule;
  }

  /** Perform all cleanup and shutdown operations. */
  @Override
  public void shutdown() {
    try {
      DefaultMonitorRegistry.getInstance().unregister(Monitors.newObjectMonitor(this));
    } catch (Throwable t) {
      logger.error("Cannot shutdown monitor registry", t);
    }
    try {
      peerEurekaNodes.shutdown();
    } catch (Throwable t) {
      logger.error("Cannot shutdown ReplicaAwareInstanceRegistry", t);
    }
    numberOfReplicationsLastMin.stop();
    timer.cancel();

    super.shutdown();
  }

  /*
   * (non-Javadoc)
   *
   * @see com.netflix.eureka.registry.InstanceRegistry#statusUpdate(java.lang.String,
   * java.lang.String, com.netflix.appinfo.InstanceInfo.InstanceStatus,
   * java.lang.String, boolean)
   */
  @Override
  public boolean statusUpdate(
      final String appName,
      final String id,
      final InstanceStatus newStatus,
      String lastDirtyTimestamp,
      final boolean isReplication) {
    if (super.statusUpdate(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
      replicateToPeers(Action.StatusUpdate, appName, id, null, newStatus, isReplication);
      return true;
    }
    return false;
  }

  @Override
  public boolean deleteStatusOverride(
      String appName,
      String id,
      InstanceStatus newStatus,
      String lastDirtyTimestamp,
      boolean isReplication) {
    if (super.deleteStatusOverride(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
      replicateToPeers(Action.DeleteStatusOverride, appName, id, null, null, isReplication);
      return true;
    }
    return false;
  }

  /**
   * Replicates all eureka actions to peer eureka nodes except for replication traffic to this node.
   */
  private void replicateToPeers(
      Action action,
      String appName,
      String id,
      InstanceInfo info /* optional */,
      InstanceStatus newStatus /* optional */,
      boolean isReplication) {
    Stopwatch tracer = action.getTimer().start();
    try {
      if (isReplication) {
        numberOfReplicationsLastMin.increment();
      }
      // 如果它已经是复制，请不要再次复制，因为这将重复复制
      if (peerEurekaNodes == Collections.EMPTY_LIST || isReplication) {
        return;
      }

      for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
        // 如果 url 代表此主机，请不要复制给自己。
        if (peerEurekaNodes.isThisMyUrl(node.getServiceUrl())) {
          continue;
        }
        replicateInstanceActionsToPeers(action, appName, id, info, newStatus, node);
      }
    } finally {
      tracer.stop();
    }
  }

  /**
   * Replicates all instance changes to peer eureka nodes except for replication traffic to this
   * node.
   */
  private void replicateInstanceActionsToPeers(
      Action action,
      String appName,
      String id,
      InstanceInfo info,
      InstanceStatus newStatus,
      PeerEurekaNode node) {
    try {
      InstanceInfo infoFromRegistry;
      CurrentRequestVersion.set(Version.V2);
      switch (action) {
        case Cancel:
          node.cancel(appName, id);
          break;
        case Heartbeat:
          InstanceStatus overriddenStatus = overriddenInstanceStatusMap.get(id);
          infoFromRegistry = getInstanceByAppAndId(appName, id, false);
          node.heartbeat(appName, id, infoFromRegistry, overriddenStatus, false);
          break;
        case Register:
          node.register(info);
          break;
        case StatusUpdate:
          infoFromRegistry = getInstanceByAppAndId(appName, id, false);
          node.statusUpdate(appName, id, newStatus, infoFromRegistry);
          break;
        case DeleteStatusOverride:
          infoFromRegistry = getInstanceByAppAndId(appName, id, false);
          node.deleteStatusOverride(appName, id, infoFromRegistry);
          break;
      }
    } catch (Throwable t) {
      logger.error(
          "Cannot replicate information to {} for action {}",
          node.getServiceUrl(),
          action.name(),
          t);
    } finally {
      CurrentRequestVersion.remove();
    }
  }

  @Override
  public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
    // TODO Auto-generated method stub
    return null;
  }

  /**
   * Gets the number of <em>renewals</em> in the last minute.
   *
   * @return a long value representing the number of <em>renewals</em> in the last minute.
   */
  @com.netflix.servo.annotations.Monitor(
      name = "numOfReplicationsInLastMin",
      description = "Number of total replications received in the last minute",
      type = com.netflix.servo.annotations.DataSourceType.GAUGE)
  public long getNumOfReplicationsInLastMin() {
    return numberOfReplicationsLastMin.getCount();
  }

  /**
   * @deprecated use {@link com.netflix.eureka.cluster.PeerEurekaNodes#getPeerEurekaNodes()}
   *     directly.
   *     <p>Gets the list of peer eureka nodes which is the list to replicate information to.
   * @return the list of replica nodes.
   */
  @Deprecated
  public List<PeerEurekaNode> getReplicaNodes() {
    return Collections.unmodifiableList(peerEurekaNodes.getPeerEurekaNodes());
  }

  @Override
  public void init(PeerEurekaNodes peerEurekaNodes) throws Exception {
    this.numberOfReplicationsLastMin.start();
    this.peerEurekaNodes = peerEurekaNodes;
    initializedResponseCache();
    scheduleRenewalThresholdUpdateTask();
    initRemoteRegionRegistry();

    try {
      Monitors.registerObject(this);
    } catch (Throwable e) {
      logger.warn("Cannot register the JMX monitor for the InstanceRegistry :", e);
    }
  }

  /**
   * Schedule the task that updates <em>renewal threshold</em> periodically. The renewal threshold
   * would be used to determine if the renewals drop dramatically because of network partition and
   * to protect expiring too many instances at a time.
   */
  private void scheduleRenewalThresholdUpdateTask() {
    timer.schedule(
        new TimerTask() {
          @Override
          public void run() {
            updateRenewalThreshold();
          }
        },
        serverConfig.getRenewalThresholdUpdateIntervalMs(),
        serverConfig.getRenewalThresholdUpdateIntervalMs());
  }

  /**
   * Updates the <em>renewal threshold</em> based on the current number of renewals. The threshold
   * is a percentage as specified in {@link EurekaServerConfig#getRenewalPercentThreshold()} of
   * renewals received per minute {@link #getNumOfRenewsInLastMin()}.
   */
  private void updateRenewalThreshold() {
    try {
      Applications apps = eurekaClient.getApplications();
      int count = 0;
      for (Application app : apps.getRegisteredApplications()) {
        for (InstanceInfo instance : app.getInstances()) {
          if (this.isRegisterable(instance)) {
            ++count;
          }
        }
      }
      synchronized (lock) {
        // Update threshold only if the threshold is greater than the
        // current expected threshold or if self preservation is disabled.
        if ((count)
                > (serverConfig.getRenewalPercentThreshold() * expectedNumberOfClientsSendingRenews)
            || (!this.isSelfPreservationModeEnabled())) {
          this.expectedNumberOfClientsSendingRenews = count;
          updateRenewsPerMinThreshold();
        }
      }
      logger.info("Current renewal threshold is : {}", numberOfRenewsPerMinThreshold);
    } catch (Throwable e) {
      logger.error("Cannot update renewal threshold", e);
    }
  }

  /**
   * Checks if an instance is registerable in this region. Instances from other regions are
   * rejected.
   *
   * @param instanceInfo th instance info information of the instance
   * @return true, if it can be registered in this server, false otherwise.
   */
  public boolean isRegisterable(InstanceInfo instanceInfo) {
    DataCenterInfo datacenterInfo = instanceInfo.getDataCenterInfo();
    String serverRegion = clientConfig.getRegion();
    if (AmazonInfo.class.isInstance(datacenterInfo)) {
      AmazonInfo info = AmazonInfo.class.cast(instanceInfo.getDataCenterInfo());
      String availabilityZone = info.get(MetaDataKey.availabilityZone);
      // Can be null for dev environments in non-AWS data center
      if (availabilityZone == null && US_EAST_1.equalsIgnoreCase(serverRegion)) {
        return true;
      } else if ((availabilityZone != null) && (availabilityZone.contains(serverRegion))) {
        // If in the same region as server, then consider it registerable
        return true;
      }
    }
    return true; // Everything non-amazon is registrable.
  }

  /**
   * Populates the registry information from a peer eureka node. This operation fails over to other
   * nodes until the list is exhausted if the communication fails.
   */
  @Override
  public int syncUp() {
    // Copy entire entry from neighboring DS node
    int count = 0;

    for (int i = 0; ((i < serverConfig.getRegistrySyncRetries()) && (count == 0)); i++) {
      if (i > 0) {
        try {
          Thread.sleep(serverConfig.getRegistrySyncRetryWaitMs());
        } catch (InterruptedException e) {
          logger.warn("Interrupted during registry transfer..");
          break;
        }
      }
      Applications apps = eurekaClient.getApplications();
      for (Application app : apps.getRegisteredApplications()) {
        for (InstanceInfo instance : app.getInstances()) {
          try {
            if (isRegisterable(instance)) {
              register(instance, instance.getLeaseInfo().getDurationInSecs(), true);
              count++;
            }
          } catch (Throwable t) {
            logger.error("During DS init copy", t);
          }
        }
      }
    }
    return count;
  }

  /**
   * Checks to see if the registry access is allowed or the server is in a situation where it does
   * not all getting registry information. The server does not return registry information for a
   * period specified in {@link EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}, if it cannot get
   * the registry information from the peer eureka nodes at start up.
   *
   * @return false - if the instances count from a replica transfer returned zero and if the wait
   *     time has not elapsed, otherwise returns true
   */
  @Override
  public boolean shouldAllowAccess(boolean remoteRegionRequired) {
    if (this.peerInstancesTransferEmptyOnStartup) {
      // 若当前时间 不大于 启动时间与等待同步时间的和，则当前server禁止访问，即返回false
      if (!(System.currentTimeMillis()
          > this.startupTime + serverConfig.getWaitTimeInMsWhenSyncEmpty())) {
        return false;
      }
    }
    // 若需要访问远程region，这遍历所有远程region，只要有一个没有准备就绪，则禁止访问，即返回false
    if (remoteRegionRequired) {
      for (RemoteRegionRegistry remoteRegionRegistry : this.regionNameVSRemoteRegistry.values()) {
        if (!remoteRegionRegistry.isReadyForServingData()) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Registers the information about the {@link InstanceInfo} and replicates this information to all
   * peer eureka nodes. If this is replication event from other replica nodes then it is not
   * replicated.
   *
   * @param info the {@link InstanceInfo} to be registered and replicated.
   * @param isReplication true if this is a replication event from other replica nodes, false
   *     otherwise.
   */
  @Override
  public void register(final InstanceInfo info, final boolean isReplication) {
    int leaseDuration = Lease.DEFAULT_DURATION_IN_SECS;
    if (info.getLeaseInfo() != null && info.getLeaseInfo().getDurationInSecs() > 0) {
      leaseDuration = info.getLeaseInfo().getDurationInSecs();
    }
    super.register(info, leaseDuration, isReplication);
    replicateToPeers(Action.Register, info.getAppName(), info.getId(), info, null, isReplication);
  }

  /**
   * Replicate the <em>ASG status</em> updates to peer eureka nodes. If this event is a replication
   * from other nodes, then it is not replicated to other nodes.
   *
   * @param asgName the asg name for which the status needs to be replicated.
   * @param newStatus the {@link ASGStatus} information that needs to be replicated.
   * @param isReplication true if this is a replication event from other nodes, false otherwise.
   */
  @Override
  public void statusUpdate(
      final String asgName, final ASGStatus newStatus, final boolean isReplication) {
    // If this is replicated from an other node, do not try to replicate again.
    if (isReplication) {
      return;
    }
    for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
      replicateASGInfoToReplicaNodes(asgName, newStatus, node);
    }
  }

  /**
   * Replicates all ASG status changes to peer eureka nodes except for replication traffic to this
   * node.
   */
  private void replicateASGInfoToReplicaNodes(
      final String asgName, final ASGStatus newStatus, final PeerEurekaNode node) {
    CurrentRequestVersion.set(Version.V2);
    try {
      node.statusUpdate(asgName, newStatus);
    } catch (Throwable e) {
      logger.error("Cannot replicate ASG status information to {}", node.getServiceUrl(), e);
    } finally {
      CurrentRequestVersion.remove();
    }
  }

  @com.netflix.servo.annotations.Monitor(
      name = METRIC_REGISTRY_PREFIX + "isLeaseExpirationEnabled",
      type = DataSourceType.GAUGE)
  public int isLeaseExpirationEnabledMetric() {
    return isLeaseExpirationEnabled() ? 1 : 0;
  }

  @com.netflix.servo.annotations.Monitor(
      name = METRIC_REGISTRY_PREFIX + "isSelfPreservationModeEnabled",
      type = DataSourceType.GAUGE)
  public int isSelfPreservationModeEnabledMetric() {
    return isSelfPreservationModeEnabled() ? 1 : 0;
  }

  @Override
  public void openForTraffic(ApplicationInfoManager applicationInfoManager, int count) {
    // Renewals happen every 30 seconds and for a minute it should be a factor of 2.
    this.expectedNumberOfClientsSendingRenews = count;
    updateRenewsPerMinThreshold();
    logger.info("Got {} instances from neighboring DS node", count);
    logger.info("Renew threshold is: {}", numberOfRenewsPerMinThreshold);
    this.startupTime = System.currentTimeMillis();
    if (count > 0) {
      this.peerInstancesTransferEmptyOnStartup = false;
    }
    DataCenterInfo.Name selfName = applicationInfoManager.getInfo().getDataCenterInfo().getName();
    boolean isAws = Name.Amazon == selfName;
    if (isAws && serverConfig.shouldPrimeAwsReplicaConnections()) {
      logger.info("Priming AWS connections for all replicas..");
      primeAwsReplicas(applicationInfoManager);
    }
    logger.info("Changing status to UP");
    applicationInfoManager.setInstanceStatus(InstanceStatus.UP);
    super.postInit();
  }

  /**
   * Prime connections for Aws replicas.
   *
   * <p>Sometimes when the eureka servers comes up, AWS firewall may not allow the network
   * connections immediately. This will cause the outbound connections to fail, but the inbound
   * connections continue to work. What this means is the clients would have switched to this node
   * (after EIP binding) and so the other eureka nodes will expire all instances that have been
   * switched because of the lack of outgoing heartbeats from this instance.
   *
   * <p>The best protection in this scenario is to block and wait until we are able to ping all
   * eureka nodes successfully atleast once. Until then we won't open up the traffic.
   */
  private void primeAwsReplicas(ApplicationInfoManager applicationInfoManager) {
    boolean areAllPeerNodesPrimed = false;
    while (!areAllPeerNodesPrimed) {
      String peerHostName = null;
      try {
        Application eurekaApps =
            this.getApplication(applicationInfoManager.getInfo().getAppName(), false);
        if (eurekaApps == null) {
          areAllPeerNodesPrimed = true;
          logger.info("No peers needed to prime.");
          return;
        }
        for (PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
          for (InstanceInfo peerInstanceInfo : eurekaApps.getInstances()) {
            LeaseInfo leaseInfo = peerInstanceInfo.getLeaseInfo();
            // If the lease is expired - do not worry about priming
            if (System.currentTimeMillis()
                > (leaseInfo.getRenewalTimestamp() + (leaseInfo.getDurationInSecs() * 1000))
                    + (2 * 60 * 1000)) {
              continue;
            }
            peerHostName = peerInstanceInfo.getHostName();
            logger.info(
                "Trying to send heartbeat for the eureka server at {} to make sure the "
                    + "network channels are open",
                peerHostName);
            // Only try to contact the eureka nodes that are in this instance's registry - because
            // the other instances may be legitimately down
            if (peerHostName.equalsIgnoreCase(new URI(node.getServiceUrl()).getHost())) {
              node.heartbeat(
                  peerInstanceInfo.getAppName(),
                  peerInstanceInfo.getId(),
                  peerInstanceInfo,
                  null,
                  true);
            }
          }
        }
        areAllPeerNodesPrimed = true;
      } catch (Throwable e) {
        logger.error("Could not contact {}", peerHostName, e);
        try {
          Thread.sleep(PRIME_PEER_NODES_RETRY_MS);
        } catch (InterruptedException e1) {
          logger.warn("Interrupted while priming : ", e1);
          areAllPeerNodesPrimed = true;
        }
      }
    }
  }

  /**
   * Gets the list of all {@link Applications} from the registry in sorted lexical order of {@link
   * Application#getName()}.
   *
   * @return the list of {@link Applications} in lexical order.
   */
  @Override
  public List<Application> getSortedApplications() {
    List<Application> apps = new ArrayList<>(getApplications().getRegisteredApplications());
    Collections.sort(apps, APP_COMPARATOR);
    return apps;
  }

  /**
   * Checks if the number of renewals is lesser than threshold.
   *
   * @return 0 if the renewals are greater than threshold, 1 otherwise.
   */
  @com.netflix.servo.annotations.Monitor(
      name = "isBelowRenewThreshold",
      description = "0 = false, 1 = true",
      type = com.netflix.servo.annotations.DataSourceType.GAUGE)
  @Override
  public int isBelowRenewThresold() {
    if ((getNumOfRenewsInLastMin() <= numberOfRenewsPerMinThreshold)
        && ((this.startupTime > 0)
            && (System.currentTimeMillis()
                > this.startupTime + (serverConfig.getWaitTimeInMsWhenSyncEmpty())))) {
      return 1;
    } else {
      return 0;
    }
  }

  @Override
  public boolean isLeaseExpirationEnabled() {
    // 只要自我保护机制关闭了，client就会过期，直接返回为true
    if (!isSelfPreservationModeEnabled()) {
      // The self preservation mode is disabled, hence allowing the instances to expire.
      return true;
    }
    // 代码走这里说明自我保护机制是开启的。
    // 那么此时的client时候过期，就取决于:
    // numberOfRenewsPerMinThreshold 是开启client过期模式的阈值，平均每分钟收到的续约数量。
    // 若最后一分钟收到的续约数量 大于 这个阈值，说明现在的client数量很多，不用考虑可用性问题
    // 只要出现过期的client，直接干掉。若小于这个阈值，则开启保护，为了保证可用性，出现过期client，也不将其从注册表中干掉
    return numberOfRenewsPerMinThreshold > 0
        &&
        // 获取最后一分钟的续约次数
        getNumOfRenewsInLastMin() > numberOfRenewsPerMinThreshold;
  }

  /**
   * Checks to see if the self-preservation mode is enabled.
   *
   * <p>The self-preservation mode is enabled if the expected number of renewals per minute {@link
   * #getNumOfRenewsInLastMin()} is lesser than the expected threshold which is determined by {@link
   * #getNumOfRenewsPerMinThreshold()} . Eureka perceives this as a danger and stops expiring
   * instances as this is most likely because of a network event. The mode is disabled only when the
   * renewals get back to above the threshold or if the flag {@link
   * EurekaServerConfig#shouldEnableSelfPreservation()} is set to false.
   *
   * @return true if the self-preservation mode is enabled, false otherwise.
   */
  @Override
  public boolean isSelfPreservationModeEnabled() {
    return serverConfig.shouldEnableSelfPreservation();
  }

  @com.netflix.servo.annotations.Monitor(
      name = METRIC_REGISTRY_PREFIX + "shouldAllowAccess",
      type = DataSourceType.GAUGE)
  public int shouldAllowAccessMetric() {
    return shouldAllowAccess() ? 1 : 0;
  }

  public boolean shouldAllowAccess() {
    return shouldAllowAccess(true);
  }

  public enum Action {
    Heartbeat,
    Register,
    Cancel,
    StatusUpdate,
    DeleteStatusOverride;

    private com.netflix.servo.monitor.Timer timer = Monitors.newTimer(this.name());

    public com.netflix.servo.monitor.Timer getTimer() {
      return this.timer;
    }
  }
}
