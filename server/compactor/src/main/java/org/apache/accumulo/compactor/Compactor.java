/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.compactor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.accumulo.fate.util.UtilWaitThread.sleepUninterruptibly;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.clientImpl.thrift.SecurityErrorCode;
import org.apache.accumulo.core.clientImpl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.compaction.thrift.CompactionCoordinator;
import org.apache.accumulo.core.compaction.thrift.Compactor.Iface;
import org.apache.accumulo.core.compaction.thrift.TCompactionState;
import org.apache.accumulo.core.compaction.thrift.UnknownCompactionIdException;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.iteratorsImpl.system.SystemIteratorUtil;
import org.apache.accumulo.core.metadata.StoredTabletFile;
import org.apache.accumulo.core.metadata.TabletFile;
import org.apache.accumulo.core.metadata.schema.DataFileValue;
import org.apache.accumulo.core.metadata.schema.ExternalCompactionId;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.securityImpl.thrift.TCredentials;
import org.apache.accumulo.core.tabletserver.thrift.ActiveCompaction;
import org.apache.accumulo.core.tabletserver.thrift.TCompactionKind;
import org.apache.accumulo.core.tabletserver.thrift.TCompactionStats;
import org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.trace.thrift.TInfo;
import org.apache.accumulo.core.util.Halt;
import org.apache.accumulo.core.util.HostAndPort;
import org.apache.accumulo.core.util.ServerServices;
import org.apache.accumulo.core.util.ServerServices.Service;
import org.apache.accumulo.core.util.compaction.ExternalCompactionUtil;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.accumulo.fate.util.UtilWaitThread;
import org.apache.accumulo.fate.zookeeper.ServiceLock;
import org.apache.accumulo.fate.zookeeper.ServiceLock.LockLossReason;
import org.apache.accumulo.fate.zookeeper.ServiceLock.LockWatcher;
import org.apache.accumulo.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.server.AbstractServer;
import org.apache.accumulo.server.GarbageCollectionLogger;
import org.apache.accumulo.server.ServerOpts;
import org.apache.accumulo.server.compaction.CompactionInfo;
import org.apache.accumulo.server.compaction.RetryableThriftCall;
import org.apache.accumulo.server.compaction.RetryableThriftCall.RetriesExceededException;
import org.apache.accumulo.server.compaction.RetryableThriftFunction;
import org.apache.accumulo.server.conf.TableConfiguration;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.rpc.ServerAddress;
import org.apache.accumulo.server.rpc.TCredentialsUpdatingWrapper;
import org.apache.accumulo.server.rpc.TServerUtils;
import org.apache.accumulo.server.rpc.ThriftServerType;
import org.apache.accumulo.server.security.AuditedSecurityOperation;
import org.apache.accumulo.server.security.SecurityOperation;
import org.apache.hadoop.fs.Path;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;

public class Compactor extends AbstractServer
    implements org.apache.accumulo.core.compaction.thrift.Compactor.Iface {

  public static class CompactorServerOpts extends ServerOpts {
    @Parameter(required = true, names = {"-q", "--queue"}, description = "compaction queue name")
    private String queueName = null;

    public String getQueueName() {
      return queueName;
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(Compactor.class);
  private static final long TIME_BETWEEN_GC_CHECKS = 5000;
  private static final long TIME_BETWEEN_CANCEL_CHECKS = 5 * 60 * 1000;

  private static final long TEN_MEGABYTES = 10485760;
  private static final CompactionCoordinator.Client.Factory COORDINATOR_CLIENT_FACTORY =
      new CompactionCoordinator.Client.Factory();

  protected static final CompactionJobHolder JOB_HOLDER = new CompactionJobHolder();

  private final GarbageCollectionLogger gcLogger = new GarbageCollectionLogger();
  private final UUID compactorId = UUID.randomUUID();
  private final AccumuloConfiguration aconf;
  private final String queueName;
  private final AtomicReference<CompactionCoordinator.Client> coordinatorClient =
      new AtomicReference<>();
  protected final AtomicReference<ExternalCompactionId> currentCompactionId =
      new AtomicReference<>();

  private SecurityOperation security;
  private ServiceLock compactorLock;
  private ServerAddress compactorAddress = null;

  // Exposed for tests
  protected volatile Boolean shutdown = false;

  protected Compactor(CompactorServerOpts opts, String[] args) {
    super("compactor", opts, args);
    queueName = opts.getQueueName();
    aconf = getConfiguration();
    setupSecurity();
    var schedExecutor = ThreadPools.createGeneralScheduledExecutorService(aconf);
    startGCLogger(schedExecutor);
    startCancelChecker(schedExecutor, TIME_BETWEEN_CANCEL_CHECKS);
    printStartupMsg();
  }

  protected Compactor(CompactorServerOpts opts, String[] args, AccumuloConfiguration conf) {
    super("compactor", opts, args);
    queueName = opts.getQueueName();
    aconf = conf;
    setupSecurity();
    var schedExecutor = ThreadPools.createGeneralScheduledExecutorService(aconf);
    startGCLogger(schedExecutor);
    startCancelChecker(schedExecutor, TIME_BETWEEN_CANCEL_CHECKS);
    printStartupMsg();
  }

  protected void setupSecurity() {
    getContext().setupCrypto();
    security = AuditedSecurityOperation.getInstance(getContext());
  }

  protected void startGCLogger(ScheduledThreadPoolExecutor schedExecutor) {
    schedExecutor.scheduleWithFixedDelay(() -> gcLogger.logGCInfo(getConfiguration()), 0,
        TIME_BETWEEN_GC_CHECKS, TimeUnit.MILLISECONDS);
  }

  protected void startCancelChecker(ScheduledThreadPoolExecutor schedExecutor,
      long timeBetweenChecks) {
    schedExecutor.scheduleWithFixedDelay(() -> checkIfCanceled(), 0, timeBetweenChecks,
        TimeUnit.MILLISECONDS);
  }

  protected void checkIfCanceled() {
    TExternalCompactionJob job = JOB_HOLDER.getJob();
    if (job != null) {
      try {
        var extent = KeyExtent.fromThrift(job.getExtent());
        var ecid = ExternalCompactionId.of(job.getExternalCompactionId());

        TabletMetadata tabletMeta =
            getContext().getAmple().readTablet(extent, ColumnType.ECOMP, ColumnType.PREV_ROW);
        if (tabletMeta == null || !tabletMeta.getExtent().equals(extent)
            || !tabletMeta.getExternalCompactions().containsKey(ecid)) {
          // table was deleted OR tablet was split or merged OR tablet no longer thinks compaction
          // is running for some reason
          LOG.info("Cancelling compaction {} that no longer has a metadata entry at {}", ecid,
              extent);
          JOB_HOLDER.cancel(job.getExternalCompactionId());
          return;
        }

        if (job.getKind() == TCompactionKind.USER) {
          String zTablePath = Constants.ZROOT + "/" + getContext().getInstanceID()
              + Constants.ZTABLES + "/" + extent.tableId() + Constants.ZTABLE_COMPACT_CANCEL_ID;
          byte[] id = getContext().getZooCache().get(zTablePath);
          if (id == null) {
            // table probably deleted
            LOG.info("Cancelling compaction {} for table that no longer exists {}", ecid, extent);
            JOB_HOLDER.cancel(job.getExternalCompactionId());
            return;
          } else {
            var cancelId = Long.parseLong(new String(id, UTF_8));

            if (cancelId >= job.getUserCompactionId()) {
              LOG.info("Cancelling compaction {} because user compaction was canceled");
              JOB_HOLDER.cancel(job.getExternalCompactionId());
              return;
            }
          }
        }
      } catch (RuntimeException e) {
        LOG.warn("Failed to check if compaction {} for {} was canceled.",
            job.getExternalCompactionId(), KeyExtent.fromThrift(job.getExtent()), e);
      }
    }
  }

  protected void printStartupMsg() {
    LOG.info("Version " + Constants.VERSION);
    LOG.info("Instance " + getContext().getInstanceID());
  }

  /**
   * Set up nodes and locks in ZooKeeper for this Compactor
   *
   * @param clientAddress
   *          address of this Compactor
   *
   * @throws KeeperException
   *           zookeeper error
   * @throws InterruptedException
   *           thread interrupted
   */
  protected void announceExistence(HostAndPort clientAddress)
      throws KeeperException, InterruptedException {

    String hostPort = ExternalCompactionUtil.getHostPortString(clientAddress);

    ZooReaderWriter zoo = getContext().getZooReaderWriter();
    String compactorQueuePath =
        getContext().getZooKeeperRoot() + Constants.ZCOMPACTORS + "/" + this.queueName;
    String zPath = compactorQueuePath + "/" + hostPort;

    try {
      zoo.mkdirs(compactorQueuePath);
      // CBUG may be able to put just an ephemeral node here w/ no lock
      zoo.putPersistentData(zPath, new byte[] {}, NodeExistsPolicy.SKIP);
    } catch (KeeperException e) {
      if (e.code() == KeeperException.Code.NOAUTH) {
        LOG.error("Failed to write to ZooKeeper. Ensure that"
            + " accumulo.properties, specifically instance.secret, is consistent.");
      }
      throw e;
    }

    compactorLock = new ServiceLock(getContext().getZooReaderWriter().getZooKeeper(),
        ServiceLock.path(zPath), compactorId);
    LockWatcher lw = new LockWatcher() {
      @Override
      public void lostLock(final LockLossReason reason) {
        Halt.halt(1, () -> {
          LOG.error("Compactor lost lock (reason = {}), exiting.", reason);
          gcLogger.logGCInfo(getConfiguration());
        });
      }

      @Override
      public void unableToMonitorLockNode(final Exception e) {
        Halt.halt(1, () -> LOG.error("Lost ability to monitor Compactor lock, exiting.", e));
      }
    };

    try {
      byte[] lockContent =
          new ServerServices(hostPort, Service.COMPACTOR_CLIENT).toString().getBytes(UTF_8);
      for (int i = 0; i < 25; i++) {
        zoo.putPersistentData(zPath, new byte[0], NodeExistsPolicy.SKIP);

        if (compactorLock.tryLock(lw, lockContent)) {
          LOG.debug("Obtained Compactor lock {}", compactorLock.getLockPath());
          return;
        }
        LOG.info("Waiting for Compactor lock");
        sleepUninterruptibly(5, TimeUnit.SECONDS);
      }
      String msg = "Too many retries, exiting.";
      LOG.info(msg);
      throw new RuntimeException(msg);
    } catch (Exception e) {
      LOG.info("Could not obtain tablet server lock, exiting.", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Start this Compactors thrift service to handle incoming client requests
   *
   * @return address of this compactor client service
   * @throws UnknownHostException
   *           host unknown
   */
  protected ServerAddress startCompactorClientService() throws UnknownHostException {
    Iface rpcProxy = TraceUtil.wrapService(this);
    if (getContext().getThriftServerType() == ThriftServerType.SASL) {
      rpcProxy = TCredentialsUpdatingWrapper.service(rpcProxy, getClass(), getConfiguration());
    }
    final org.apache.accumulo.core.compaction.thrift.Compactor.Processor<Iface> processor =
        new org.apache.accumulo.core.compaction.thrift.Compactor.Processor<>(rpcProxy);
    Property maxMessageSizeProperty = (aconf.get(Property.COMPACTOR_MAX_MESSAGE_SIZE) != null
        ? Property.COMPACTOR_MAX_MESSAGE_SIZE : Property.GENERAL_MAX_MESSAGE_SIZE);
    ServerAddress sp = TServerUtils.startServer(getMetricsSystem(), getContext(), getHostname(),
        Property.COMPACTOR_CLIENTPORT, processor, this.getClass().getSimpleName(),
        "Thrift Client Server", Property.COMPACTOR_PORTSEARCH, Property.COMPACTOR_MINTHREADS,
        Property.COMPACTOR_MINTHREADS_TIMEOUT, Property.COMPACTOR_THREADCHECK,
        maxMessageSizeProperty);
    LOG.info("address = {}", sp.address);
    return sp;
  }

  /**
   * Called by a CompactionCoordinator to cancel the currently running compaction
   *
   * @param tinfo
   *          trace info
   * @param credentials
   *          caller credentials
   * @param externalCompactionId
   *          compaction id
   * @throws UnknownCompactionIdException
   *           if the externalCompactionId does not match the currently executing compaction
   */
  @Override
  public void cancel(TInfo tinfo, TCredentials credentials, String externalCompactionId)
      throws TException {
    // do not expect users to call this directly, expect other tservers to call this method
    if (!security.canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }
    cancel(externalCompactionId);
  }

  /**
   * Cancel the compaction with this id.
   *
   * @param externalCompactionId
   *          compaction id
   * @throws UnknownCompactionIdException
   *           if the externalCompactionId does not match the currently executing compaction
   * @throws TException
   *           thrift error
   */
  private void cancel(String externalCompactionId) throws TException {
    if (JOB_HOLDER.cancel(externalCompactionId)) {
      LOG.info("Cancel requested for compaction job {}", externalCompactionId);
    } else {
      throw new UnknownCompactionIdException();
    }
  }

  /**
   * Send an update to the CompactionCoordinator for this job
   *
   * @param job
   *          compactionJob
   * @param state
   *          updated state
   * @param message
   *          updated message
   * @throws RetriesExceededException
   *           thrown when retries have been exceeded
   */
  protected void updateCompactionState(TExternalCompactionJob job, TCompactionState state,
      String message) throws RetriesExceededException {
    // CBUG the return type was changed from Void to String just to make this work. When type was
    // Void and returned null, it would retry forever. Could specialize RetryableThriftCall for case
    // w/ no return type.
    RetryableThriftCall<String> thriftCall = new RetryableThriftCall<>(1000,
        RetryableThriftCall.MAX_WAIT_TIME, 25, new RetryableThriftFunction<String>() {
          @Override
          public String execute() throws TException {
            try {
              coordinatorClient.compareAndSet(null, getCoordinatorClient());
              coordinatorClient.get().updateCompactionStatus(TraceUtil.traceInfo(),
                  getContext().rpcCreds(), job.getExternalCompactionId(), state, message,
                  System.currentTimeMillis());
              return "";
            } finally {
              ThriftUtil.returnClient(coordinatorClient.getAndSet(null));
            }
          }
        });
    thriftCall.run();
  }

  /**
   * Notify the CompactionCoordinator the job failed
   *
   * @param job
   *          current compaction job
   * @throws RetriesExceededException
   *           thrown when retries have been exceeded
   */
  protected void updateCompactionFailed(TExternalCompactionJob job)
      throws RetriesExceededException {
    RetryableThriftCall<String> thriftCall = new RetryableThriftCall<>(1000,
        RetryableThriftCall.MAX_WAIT_TIME, 25, new RetryableThriftFunction<String>() {
          @Override
          public String execute() throws TException {
            try {
              coordinatorClient.compareAndSet(null, getCoordinatorClient());
              coordinatorClient.get().compactionFailed(TraceUtil.traceInfo(),
                  getContext().rpcCreds(), job.getExternalCompactionId(), job.extent);
              return "";
            } finally {
              ThriftUtil.returnClient(coordinatorClient.getAndSet(null));
            }
          }
        });
    thriftCall.run();
  }

  /**
   * Update the CompactionCoordinator with the stats from the completed job
   *
   * @param job
   *          current compaction job
   * @param stats
   *          compaction stats
   * @throws RetriesExceededException
   *           thrown when retries have been exceeded
   */
  protected void updateCompactionCompleted(TExternalCompactionJob job, TCompactionStats stats)
      throws RetriesExceededException {
    RetryableThriftCall<String> thriftCall = new RetryableThriftCall<>(1000,
        RetryableThriftCall.MAX_WAIT_TIME, 25, new RetryableThriftFunction<String>() {
          @Override
          public String execute() throws TException {
            try {
              coordinatorClient.compareAndSet(null, getCoordinatorClient());
              coordinatorClient.get().compactionCompleted(TraceUtil.traceInfo(),
                  getContext().rpcCreds(), job.getExternalCompactionId(), job.extent, stats);
              return "";
            } finally {
              ThriftUtil.returnClient(coordinatorClient.getAndSet(null));
            }
          }
        });
    thriftCall.run();
  }

  /**
   * Get the next job to run
   *
   * @param uuid
   *          uuid supplier
   * @return CompactionJob
   * @throws RetriesExceededException
   *           thrown when retries have been exceeded
   */
  protected TExternalCompactionJob getNextJob(Supplier<UUID> uuid) throws RetriesExceededException {
    RetryableThriftCall<TExternalCompactionJob> nextJobThriftCall =
        new RetryableThriftCall<>(1000, RetryableThriftCall.MAX_WAIT_TIME, 0,
            new RetryableThriftFunction<TExternalCompactionJob>() {
              @Override
              public TExternalCompactionJob execute() throws TException {
                try {
                  ExternalCompactionId eci = ExternalCompactionId.generate(uuid.get());
                  coordinatorClient.compareAndSet(null, getCoordinatorClient());
                  LOG.info("Attempting to get next job, eci = {}", eci);
                  currentCompactionId.set(eci);
                  return coordinatorClient.get().getCompactionJob(TraceUtil.traceInfo(),
                      getContext().rpcCreds(), queueName,
                      ExternalCompactionUtil.getHostPortString(compactorAddress.getAddress()),
                      eci.toString());
                } catch (TException e) {
                  currentCompactionId.set(null);
                  throw e;
                } finally {
                  ThriftUtil.returnClient(coordinatorClient.getAndSet(null));
                }
              }
            });
    return nextJobThriftCall.run();
  }

  /**
   * Get the client to the CompactionCoordinator
   *
   * @return compaction coordinator client
   * @throws TTransportException
   *           when unable to get client
   */
  protected CompactionCoordinator.Client getCoordinatorClient() throws TTransportException {
    HostAndPort coordinatorHost = ExternalCompactionUtil.findCompactionCoordinator(getContext());
    if (null == coordinatorHost) {
      throw new TTransportException("Unable to get CompactionCoordinator address from ZooKeeper");
    }
    LOG.info("CompactionCoordinator address is: {}", coordinatorHost);
    return ThriftUtil.getClient(COORDINATOR_CLIENT_FACTORY, coordinatorHost, getContext());
  }

  /**
   * Create compaction runnable
   *
   * @param job
   *          compaction job
   * @param totalInputEntries
   *          object to capture total entries
   * @param totalInputBytes
   *          object to capture input file size
   * @param started
   *          started latch
   * @param stopped
   *          stopped latch
   * @param err
   *          reference to error
   * @return Runnable compaction job
   */
  protected Runnable createCompactionJob(final TExternalCompactionJob job,
      final LongAdder totalInputEntries, final LongAdder totalInputBytes,
      final CountDownLatch started, final CountDownLatch stopped,
      final AtomicReference<Throwable> err) {

    return new Runnable() {
      @Override
      public void run() {
        try {
          LOG.info("Starting up compaction runnable for job: {}", job);
          updateCompactionState(job, TCompactionState.STARTED, "Compaction started");

          final TableId tableId = TableId.of(new String(job.getExtent().getTable(), UTF_8));
          final TableConfiguration tConfig = getContext().getTableConfiguration(tableId);
          final TabletFile outputFile = new TabletFile(new Path(job.getOutputFile()));

          final Map<StoredTabletFile,DataFileValue> files = new TreeMap<>();
          job.getFiles().forEach(f -> {
            files.put(new StoredTabletFile(f.getMetadataFileEntry()),
                new DataFileValue(f.getSize(), f.getEntries(), f.getTimestamp()));
            totalInputEntries.add(f.getEntries());
            totalInputBytes.add(f.getSize());
          });

          final List<IteratorSetting> iters = new ArrayList<>();
          job.getIteratorSettings().getIterators()
              .forEach(tis -> iters.add(SystemIteratorUtil.toIteratorSetting(tis)));

          try (CompactionEnvironment cenv =
              new CompactionEnvironment(getContext(), JOB_HOLDER, queueName)) {
            org.apache.accumulo.server.compaction.Compactor compactor =
                new org.apache.accumulo.server.compaction.Compactor(getContext(),
                    KeyExtent.fromThrift(job.getExtent()), files, outputFile,
                    job.isPropagateDeletes(), cenv, iters, tConfig);

            LOG.info("Starting compactor");
            started.countDown();

            org.apache.accumulo.server.compaction.CompactionStats stat = compactor.call();
            TCompactionStats cs = new TCompactionStats();
            cs.setEntriesRead(stat.getEntriesRead());
            cs.setEntriesWritten(stat.getEntriesWritten());
            cs.setFileSize(stat.getFileSize());
            JOB_HOLDER.setStats(cs);
          }
          LOG.info("Compaction completed successfully {} ", job.getExternalCompactionId());
          // Update state when completed
          updateCompactionState(job, TCompactionState.SUCCEEDED,
              "Compaction completed successfully");
        } catch (Exception e) {
          LOG.error("Compaction failed", e);
          err.set(e);
          throw new RuntimeException("Compaction failed", e);
        } finally {
          stopped.countDown();
          // CBUG Any cleanup
        }
      }
    };
  }

  /**
   * Returns the number of seconds to wait in between progress checks based on input file sizes
   *
   * @param numBytes
   *          number of bytes in input file
   * @return number of seconds to wait between progress checks
   */
  protected long calculateProgressCheckTime(long numBytes) {
    return Math.max(1, (numBytes / TEN_MEGABYTES));
  }

  protected Supplier<UUID> getNextId() {
    Supplier<UUID> supplier = () -> {
      return UUID.randomUUID();
    };
    return supplier;
  }

  protected long getWaitTimeBetweenCompactionChecks() {
    return 3000;
  }

  @Override
  public void run() {

    try {
      compactorAddress = startCompactorClientService();
    } catch (UnknownHostException e1) {
      throw new RuntimeException("Failed to start the compactor client service", e1);
    }
    final HostAndPort clientAddress = compactorAddress.getAddress();

    try {
      announceExistence(clientAddress);
    } catch (KeeperException | InterruptedException e) {
      throw new RuntimeException("Erroring registering in ZooKeeper", e);
    }

    LOG.info("Compactor started, waiting for work");
    try {

      final AtomicReference<Throwable> err = new AtomicReference<>();

      while (!shutdown) {
        currentCompactionId.set(null);
        err.set(null);
        JOB_HOLDER.reset();

        TExternalCompactionJob job;
        try {
          job = getNextJob(getNextId());
          if (!job.isSetExternalCompactionId()) {
            LOG.info("No external compactions in queue {}", this.queueName);
            UtilWaitThread.sleep(getWaitTimeBetweenCompactionChecks());
            continue;
          }
          if (!job.getExternalCompactionId().equals(currentCompactionId.get().toString())) {
            throw new IllegalStateException("Returned eci " + job.getExternalCompactionId()
                + " does not match supplied eci " + currentCompactionId.get());
          }
        } catch (RetriesExceededException e2) {
          LOG.warn("Retries exceeded getting next job. Retrying...");
          continue;
        }
        LOG.info("Received next compaction job: {}", job);

        final LongAdder totalInputEntries = new LongAdder();
        final LongAdder totalInputBytes = new LongAdder();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);

        final Thread compactionThread = Threads.createThread(
            "Compaction job for tablet " + job.getExtent().toString(),
            createCompactionJob(job, totalInputEntries, totalInputBytes, started, stopped, err));

        JOB_HOLDER.set(job, compactionThread);

        try {
          compactionThread.start(); // start the compactionThread
          started.await(); // wait until the compactor is started
          final long inputEntries = totalInputEntries.sum();
          final long waitTime = calculateProgressCheckTime(totalInputBytes.sum());
          LOG.info("Progress checks will occur every {} seconds", waitTime);
          String percentComplete = "unknown";

          while (!stopped.await(waitTime, TimeUnit.SECONDS)) {
            List<CompactionInfo> running =
                org.apache.accumulo.server.compaction.Compactor.getRunningCompactions();
            if (!running.isEmpty()) {
              // Compaction has started. There should only be one in the list
              CompactionInfo info = running.get(0);
              if (info != null) {
                if (inputEntries > 0) {
                  percentComplete = Float.toString((info.getEntriesRead() / inputEntries) * 100);
                }
                String message = String.format(
                    "Compaction in progress, read %d of %d input entries ( %s %s ), written %d entries",
                    info.getEntriesRead(), inputEntries, percentComplete, "%",
                    info.getEntriesWritten());
                LOG.info(message);
                try {
                  LOG.info("Updating coordinator with compaction progress: {}.", message);
                  updateCompactionState(job, TCompactionState.IN_PROGRESS, message);
                } catch (RetriesExceededException e) {
                  LOG.warn("Error updating coordinator with compaction progress, error: {}",
                      e.getMessage());
                }
              }
            } else {
              LOG.warn("Waiting on compaction thread to finish, but no RUNNING compaction");
            }
          }
          compactionThread.join();
          LOG.info("Compaction thread finished.");

          if (compactionThread.isInterrupted() || JOB_HOLDER.isCancelled()
              || ((err.get() != null && err.get().getClass().equals(InterruptedException.class)))) {
            LOG.warn("Compaction thread was interrupted, sending CANCELLED state");
            try {
              updateCompactionState(job, TCompactionState.CANCELLED, "Compaction cancelled");
              updateCompactionFailed(job);
            } catch (RetriesExceededException e) {
              LOG.error("Error updating coordinator with compaction cancellation.", e);
            } finally {
              currentCompactionId.set(null);
            }
          } else if (err.get() != null) {
            try {
              LOG.info("Updating coordinator with compaction failure.");
              updateCompactionState(job, TCompactionState.FAILED,
                  "Compaction failed due to: " + err.get().getMessage());
              updateCompactionFailed(job);
            } catch (RetriesExceededException e) {
              LOG.error("Error updating coordinator with compaction failure.", e);
            } finally {
              currentCompactionId.set(null);
            }
          } else {
            try {
              LOG.info("Updating coordinator with compaction completion.");
              updateCompactionCompleted(job, JOB_HOLDER.getStats());
            } catch (RetriesExceededException e) {
              LOG.error(
                  "Error updating coordinator with compaction completion, cancelling compaction.",
                  e);
              try {
                cancel(job.getExternalCompactionId());
              } catch (TException e1) {
                LOG.error("Error cancelling compaction.", e1);
              }
            } finally {
              currentCompactionId.set(null);
            }
          }
        } catch (RuntimeException e1) {
          LOG.error(
              "Compactor thread was interrupted waiting for compaction to start, cancelling job",
              e1);
          try {
            cancel(job.getExternalCompactionId());
          } catch (TException e2) {
            LOG.error("Error cancelling compaction.", e2);
          }
        } finally {
          currentCompactionId.set(null);
        }

      }

    } catch (Exception e) {
      LOG.error("Unhandled error occurred in Compactor", e);
    } finally {
      // close connection to coordinator
      if (null != coordinatorClient.get()) {
        ThriftUtil.returnClient(coordinatorClient.get());
      }

      // Shutdown local thrift server
      LOG.info("Stopping Thrift Servers");
      TServerUtils.stopTServer(compactorAddress.server);

      try {
        LOG.debug("Closing filesystems");
        VolumeManager mgr = getContext().getVolumeManager();
        if (null != mgr) {
          mgr.close();
        }
      } catch (IOException e) {
        LOG.warn("Failed to close filesystem : {}", e.getMessage(), e);
      }

      gcLogger.logGCInfo(getConfiguration());
      LOG.info("stop requested. exiting ... ");
      try {
        if (null != compactorLock) {
          compactorLock.unlock();
        }
      } catch (Exception e) {
        LOG.warn("Failed to release compactor lock", e);
      }
    }

  }

  public static void main(String[] args) throws Exception {
    try (Compactor compactor = new Compactor(new CompactorServerOpts(), args)) {
      compactor.runServer();
    }
  }

  @Override
  public List<ActiveCompaction> getActiveCompactions(TInfo tinfo, TCredentials credentials)
      throws ThriftSecurityException, TException {
    if (!security.canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    List<CompactionInfo> compactions =
        org.apache.accumulo.server.compaction.Compactor.getRunningCompactions();
    List<ActiveCompaction> ret = new ArrayList<>(compactions.size());

    for (CompactionInfo compactionInfo : compactions) {
      ret.add(compactionInfo.toThrift());
    }

    return ret;
  }

  /**
   * Called by a CompactionCoordinator to get the running compaction
   *
   * @param tinfo
   *          trace info
   * @param credentials
   *          caller credentials
   * @return current compaction job or empty compaction job is none running
   */
  @Override
  public TExternalCompactionJob getRunningCompaction(TInfo tinfo, TCredentials credentials)
      throws ThriftSecurityException, TException {
    // do not expect users to call this directly, expect other tservers to call this method
    if (!security.canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    // Return what is currently running, does not wait for jobs in the process of reserving. This
    // method is called by a coordinator starting up to determine what is currently running on all
    // compactors.

    TExternalCompactionJob job = null;
    synchronized (JOB_HOLDER) {
      job = JOB_HOLDER.getJob();
    }

    if (null == job) {
      return new TExternalCompactionJob();
    } else {
      return job;
    }
  }

  @Override
  public String getRunningCompactionId(TInfo tinfo, TCredentials credentials)
      throws ThriftSecurityException, TException {
    // do not expect users to call this directly, expect other tservers to call this method
    if (!security.canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    // Any returned id must cover the time period from before a job is reserved until after it
    // commits. This method is called to detect dead compactions and depends on this behavior.
    // For the purpose of detecting dead compactions its ok if ids are returned that never end up
    // being related to a running compaction.
    ExternalCompactionId eci = currentCompactionId.get();
    if (null == eci) {
      return "";
    } else {
      return eci.canonical();
    }
  }
}
