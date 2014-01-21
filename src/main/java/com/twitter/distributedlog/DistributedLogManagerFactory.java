package com.twitter.distributedlog;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.twitter.distributedlog.bk.LedgerAllocator;
import com.twitter.distributedlog.bk.LedgerAllocatorUtils;
import com.twitter.distributedlog.exceptions.InvalidStreamNameException;
import com.twitter.distributedlog.metadata.BKDLConfig;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class DistributedLogManagerFactory {
    static final Logger LOG = LoggerFactory.getLogger(DistributedLogManagerFactory.class);

    static interface ZooKeeperClientHandler<T> {
        T handle(ZooKeeperClient zkc) throws IOException;
    }

    /**
     * Run given <i>handler</i> by providing an available new zookeeper client
     *
     * @param handler
     *          Handler to process with provided zookeeper client.
     * @param conf
     *          Distributedlog Configuration.
     * @param namespace
     *          Distributedlog Namespace.
     */
    private static <T> T withZooKeeperClient(ZooKeeperClientHandler<T> handler,
                                             DistributedLogConfiguration conf,
                                             URI namespace) throws IOException {
        ZooKeeperClient zkc = ZooKeeperClientBuilder.newBuilder()
                .sessionTimeoutMs(conf.getZKSessionTimeoutMilliseconds()).uri(namespace).buildNew();
        try {
            return handler.handle(zkc);
        } finally {
            zkc.close();
        }
    }

    static boolean isReservedStreamName(String name) {
        return name.startsWith(".");
    }

    private final String clientId;
    private DistributedLogConfiguration conf;
    private URI namespace;
    private final StatsLogger statsLogger;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ClientSocketChannelFactory channelFactory;
    // zk & bk client
    private final ZooKeeperClientBuilder zooKeeperClientBuilder;
    private final ZooKeeperClient zooKeeperClient;
    private final BookKeeperClientBuilder bookKeeperClientBuilder;
    private BookKeeperClient bookKeeperClient = null;
    // ledger allocator
    private final LedgerAllocator allocator;

    public DistributedLogManagerFactory(DistributedLogConfiguration conf, URI uri) throws IOException, IllegalArgumentException {
        this(conf, uri, NullStatsLogger.INSTANCE);
    }

    public DistributedLogManagerFactory(DistributedLogConfiguration conf, URI uri,
                                        StatsLogger statsLogger) throws IOException, IllegalArgumentException {
        this(conf, uri, statsLogger, DistributedLogConstants.UNKNOWN_CLIENT_ID);
    }

    public DistributedLogManagerFactory(DistributedLogConfiguration conf, URI uri,
                                        StatsLogger statsLogger, String clientId) throws IOException, IllegalArgumentException {
        validateConfAndURI(conf, uri);
        this.conf = conf;
        this.namespace = uri;
        this.statsLogger = statsLogger;
        this.clientId = clientId;
        this.scheduledExecutorService = Executors.newScheduledThreadPool(
                conf.getNumWorkerThreads(),
                new ThreadFactoryBuilder().setNameFormat("DLM-" + uri.getPath() + "-executor-%d").build()
        );
        this.channelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
        // Build zookeeper client
        this.zooKeeperClientBuilder = ZooKeeperClientBuilder.newBuilder()
                .sessionTimeoutMs(conf.getZKSessionTimeoutMilliseconds()).uri(uri)
                .buildNew(conf.getSeparateZKClients());
        this.zooKeeperClient = this.zooKeeperClientBuilder.build();
        // Resolve uri to get bk dl config
        BKDLConfig bkdlConfig = resolveBKDLConfig();
        // Build bookkeeper client
        this.bookKeeperClientBuilder = BookKeeperClientBuilder.newBuilder()
                .dlConfig(conf).bkdlConfig(bkdlConfig).name(String.format("%s:shared", namespace))
                .channelFactory(channelFactory).statsLogger(statsLogger);

        // propagate bkdlConfig to configuration
        BKDLConfig.propagateConfiguration(bkdlConfig, conf);

        // Build the allocator
        if (conf.getEnableLedgerAllocatorPool()) {
            String poolName = conf.getLedgerAllocatorPoolName();
            if (null == poolName) {
                LOG.error("No ledger allocator pool name specified when enabling ledger allocator pool.");
                throw new IOException("No ledger allocator name specified when enabling ledger allocator pool.");
            }
            String rootPath = uri.getPath() + "/" + DistributedLogConstants.ALLOCATION_POOL_NODE + "/" + poolName;
            getBookKeeperClientBuilder();
            allocator = LedgerAllocatorUtils.createLedgerAllocatorPool(rootPath, conf.getLedgerAllocatorPoolCoreSize(), conf,
                    zooKeeperClient, getBookKeeperClient());
            LOG.info("Created ledger allocator pool under {} with size {}.", rootPath, conf.getLedgerAllocatorPoolCoreSize());
        } else {
            allocator = null;
        }
    }

    synchronized BookKeeperClientBuilder getBookKeeperClientBuilder() throws IOException {
        if (null == bookKeeperClient) {
            // get a reference of shared bookkeeper client
            try {
                bookKeeperClient = bookKeeperClientBuilder.build();
            } catch (InterruptedException ie) {
                LOG.error("Interrupted while building bookkeeper client", ie);
                throw new IOException("Error building bookkeeper client", ie);
            } catch (KeeperException ke) {
                LOG.error("Error building bookkeeper client", ke);
                throw new IOException("Error building bookkeeper client", ke);
            }
        }
        return bookKeeperClientBuilder;
    }

    synchronized BookKeeperClient getBookKeeperClient() {
        return bookKeeperClient;
    }

    /**
     * Run given <i>handler</i> by providing an available zookeeper client.
     *
     * @param handler
     *          Handler to process with provided zookeeper client.
     * @return result processed by handler.
     * @throws IOException
     */
    private <T> T withZooKeeperClient(ZooKeeperClientHandler<T> handler) throws IOException {
        return handler.handle(zooKeeperClient);
    }

    private BKDLConfig resolveBKDLConfig() throws IOException {
        return withZooKeeperClient(new ZooKeeperClientHandler<BKDLConfig>() {
            @Override
            public BKDLConfig handle(ZooKeeperClient zkc) throws IOException {
                return BKDLConfig.resolveDLConfig(zkc, namespace);
            }
        });
    }

    /**
     * Create a DistributedLogManager as <i>nameOfLogStream</i>.
     *
     * <p>
     * For zookeeper session expire handling purpose, we don't use zookeeper client & bookkeeper client builder inside factory.
     * We managed the shared executor service for all the {@link DistributedLogManager}s created by this factory, so we don't
     * spawn too much threads.
     * </p>
     *
     * @param nameOfLogStream
     *          name of log stream.
     * @return distributedlog manager instance.
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public DistributedLogManager createDistributedLogManager(String nameOfLogStream) throws IOException, IllegalArgumentException {
        DistributedLogManagerFactory.validateName(nameOfLogStream);
        BKDistributedLogManager distLogMgr = new BKDistributedLogManager(nameOfLogStream, conf, namespace,
            null, null, scheduledExecutorService, channelFactory, statsLogger);
        distLogMgr.setClientId(clientId);
        distLogMgr.setLedgerAllocator(allocator);
        return distLogMgr;
    }

    /**
     * Create a DistributedLogManager as <i>nameOfLogStream</i>, which shared the zookeeper & bookkeeper builder
     * used by the factory.
     *
     * @param nameOfLogStream
     *          name of log stream.
     * @return distributedlog manager instance.
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public DistributedLogManager createDistributedLogManagerWithSharedClients(String nameOfLogStream)
        throws IOException, IllegalArgumentException {
        DistributedLogManagerFactory.validateName(nameOfLogStream);
        BKDistributedLogManager distLogMgr = new BKDistributedLogManager(nameOfLogStream, conf, namespace,
            zooKeeperClientBuilder, getBookKeeperClientBuilder(), scheduledExecutorService, channelFactory, statsLogger);
        distLogMgr.setClientId(clientId);
        distLogMgr.setLedgerAllocator(allocator);
        return distLogMgr;
    }

    public MetadataAccessor createMetadataAccessor(String nameOfMetadataNode) throws IOException, IllegalArgumentException {
        DistributedLogManagerFactory.validateName(nameOfMetadataNode);
        return new ZKMetadataAccessor(nameOfMetadataNode, namespace, conf.getZKSessionTimeoutMilliseconds(), zooKeeperClientBuilder);
    }

    public boolean checkIfLogExists(String nameOfLogStream)
        throws IOException, IllegalArgumentException {
        return DistributedLogManagerFactory.checkIfLogExists(conf, namespace, nameOfLogStream);
    }

    public Collection<String> enumerateAllLogsInNamespace()
        throws IOException, IllegalArgumentException {
        return withZooKeeperClient(new ZooKeeperClientHandler<Collection<String>>() {
            @Override
            public Collection<String> handle(ZooKeeperClient zkc) throws IOException {
                return DistributedLogManagerFactory.enumerateAllLogsInternal(zkc, conf, namespace);
            }
        });
    }

    public Map<String, byte[]> enumerateLogsWithMetadataInNamespace()
        throws IOException, IllegalArgumentException {
        return withZooKeeperClient(new ZooKeeperClientHandler<Map<String, byte[]>>() {
            @Override
            public Map<String, byte[]> handle(ZooKeeperClient zkc) throws IOException {
                return DistributedLogManagerFactory.enumerateLogsWithMetadataInternal(zkc, conf, namespace);
            }
        });
    }

    public static DistributedLogManager createDistributedLogManager(String name, URI uri) throws IOException, IllegalArgumentException {
        return createDistributedLogManager(name, new DistributedLogConfiguration(), uri);
    }

    private static void validateInput(DistributedLogConfiguration conf, URI uri, String nameOfStream)
        throws IllegalArgumentException, InvalidStreamNameException {
        validateConfAndURI(conf, uri);
        validateName(nameOfStream);
    }

    private static void validateConfAndURI(DistributedLogConfiguration conf, URI uri)
        throws IllegalArgumentException {
        // input validation
        //
        if (null == conf) {
            throw new IllegalArgumentException("Incorrect Configuration");
        }

        if ((null == uri) || (null == uri.getAuthority()) || (null == uri.getPath())) {
            throw new IllegalArgumentException("Incorrect ZK URI");
        }
    }

    private static void validateName(String nameOfStream) throws InvalidStreamNameException {
        if (nameOfStream.contains("/")) {
            throw new InvalidStreamNameException(nameOfStream, "Stream Name contains invalid characters");
        }
        if (isReservedStreamName(nameOfStream)) {
            throw new InvalidStreamNameException(nameOfStream, "Stream Name is reserved");
        }
    }

    public static DistributedLogManager createDistributedLogManager(
            String name,
            DistributedLogConfiguration conf,
            URI uri)
        throws IOException, IllegalArgumentException {
        return createDistributedLogManager(name, conf, uri, (ZooKeeperClientBuilder) null, (BookKeeperClientBuilder) null,
                NullStatsLogger.INSTANCE);
    }

    public static DistributedLogManager createDistributedLogManager(
            String name,
            DistributedLogConfiguration conf,
            URI uri,
            ZooKeeperClient zkc,
            BookKeeperClient bkc)
        throws IOException, IllegalArgumentException {
        return createDistributedLogManager(name, conf, uri, ZooKeeperClientBuilder.newBuilder().zkc(zkc),
                BookKeeperClientBuilder.newBuilder().bkc(bkc), NullStatsLogger.INSTANCE);
    }

    public static DistributedLogManager createDistributedLogManager(String name, DistributedLogConfiguration conf, URI uri,
                                                                    ZooKeeperClient zkc, BookKeeperClient bkc, StatsLogger statsLogger)
        throws IOException, IllegalArgumentException {
        return createDistributedLogManager(name, conf, uri, ZooKeeperClientBuilder.newBuilder().zkc(zkc),
                BookKeeperClientBuilder.newBuilder().bkc(bkc).statsLogger(statsLogger), statsLogger);
    }

    public static DistributedLogManager createDistributedLogManager(String name, DistributedLogConfiguration conf, URI uri,
                ZooKeeperClientBuilder zkcBuilder, BookKeeperClientBuilder bkcBuilder, StatsLogger statsLogger)
        throws IOException, IllegalArgumentException {
        validateInput(conf, uri, name);
        return new BKDistributedLogManager(name, conf, uri, zkcBuilder, bkcBuilder, statsLogger);
    }

    public static MetadataAccessor createMetadataAccessor(
            String name,
            URI uri,
            DistributedLogConfiguration conf)
        throws IOException, IllegalArgumentException {
        return createMetadataAccessor(name, uri, conf, (ZooKeeperClientBuilder) null);
    }

    public static MetadataAccessor createMetadataAccessor(
            String name,
            URI uri,
            DistributedLogConfiguration conf,
            ZooKeeperClient zkc)
        throws IOException, IllegalArgumentException {
        return createMetadataAccessor(name, uri, conf, ZooKeeperClientBuilder.newBuilder().zkc(zkc));
    }

    public static MetadataAccessor createMetadataAccessor(
            String name,
            URI uri,
            DistributedLogConfiguration conf,
            ZooKeeperClientBuilder zkcBuilder)
        throws IOException, IllegalArgumentException {
        validateInput(conf, uri, name);
        return new ZKMetadataAccessor(name, uri, conf.getZKSessionTimeoutMilliseconds(), zkcBuilder);
    }


    public static boolean checkIfLogExists(DistributedLogConfiguration conf, URI uri, String name)
        throws IOException, IllegalArgumentException {
        validateInput(conf, uri, name);
        final String logRootPath = uri.getPath() + String.format("/%s", name);
        return withZooKeeperClient(new ZooKeeperClientHandler<Boolean>() {
            @Override
            public Boolean handle(ZooKeeperClient zkc) throws IOException {
                try {
                    if (null != zkc.get().exists(logRootPath, false)) {
                        return true;
                    }
                } catch (InterruptedException ie) {
                    LOG.error("Interrupted checkIfLogExists  " + logRootPath, ie);
                } catch (KeeperException ke) {
                    LOG.error("Error reading" + logRootPath + "entry in zookeeper", ke);
                }
                return false;
            }
        }, conf, uri);
    }

    public static Collection<String> enumerateAllLogsInNamespace(final DistributedLogConfiguration conf, final URI uri)
        throws IOException, IllegalArgumentException {
        return withZooKeeperClient(new ZooKeeperClientHandler<Collection<String>>() {
            @Override
            public Collection<String> handle(ZooKeeperClient zkc) throws IOException {
                return enumerateAllLogsInternal(zkc, conf, uri);
            }
        }, conf, uri);
    }

    private static Collection<String> enumerateAllLogsInternal(ZooKeeperClient zkc, DistributedLogConfiguration conf, URI uri)
        throws IOException, IllegalArgumentException {
        validateConfAndURI(conf, uri);
        String namespaceRootPath = uri.getPath();
        try {
            Stat currentStat = zkc.get().exists(namespaceRootPath, false);
            if (currentStat == null) {
                return new LinkedList<String>();
            }
            List<String> children = zkc.get().getChildren(namespaceRootPath, false);
            List<String> result = new ArrayList<String>(children.size());
            for (String child : children) {
                if (!isReservedStreamName(child)) {
                    result.add(child);
                }
            }
            return result;
        } catch (InterruptedException ie) {
            LOG.error("Interrupted while deleting " + namespaceRootPath, ie);
            throw new IOException("Interrupted while deleting " + namespaceRootPath, ie);
        } catch (KeeperException ke) {
            LOG.error("Error reading" + namespaceRootPath + "entry in zookeeper", ke);
            throw new IOException("Error reading" + namespaceRootPath + "entry in zookeeper", ke);
        }
    }

    public static Map<String, byte[]> enumerateLogsWithMetadataInNamespace(final DistributedLogConfiguration conf, final URI uri)
        throws IOException, IllegalArgumentException {
        return withZooKeeperClient(new ZooKeeperClientHandler<Map<String, byte[]>>() {
            @Override
            public Map<String, byte[]> handle(ZooKeeperClient zkc) throws IOException {
                return enumerateLogsWithMetadataInternal(zkc, conf, uri);
            }
        }, conf, uri);
    }

    private static Map<String, byte[]> enumerateLogsWithMetadataInternal(ZooKeeperClient zkc,
                                                                         DistributedLogConfiguration conf, URI uri)
        throws IOException, IllegalArgumentException {
        validateConfAndURI(conf, uri);
        String namespaceRootPath = uri.getPath();
        HashMap<String, byte[]> result = new HashMap<String, byte[]>();
        try {
            Stat currentStat = zkc.get().exists(namespaceRootPath, false);
            if (currentStat == null) {
                return result;
            }
            List<String> children = zkc.get().getChildren(namespaceRootPath, false);
            for(String child: children) {
                if (isReservedStreamName(child)) {
                    continue;
                }
                String zkPath = String.format("%s/%s", namespaceRootPath, child);
                currentStat = zkc.get().exists(zkPath, false);
                if (currentStat == null) {
                    result.put(child, new byte[0]);
                } else {
                    result.put(child, zkc.get().getData(zkPath, false, currentStat));
                }
            }
        } catch (InterruptedException ie) {
            LOG.error("Interrupted while deleting " + namespaceRootPath, ie);
            throw new IOException("Interrupted while reading " + namespaceRootPath, ie);
        } catch (KeeperException ke) {
            LOG.error("Error reading" + namespaceRootPath + "entry in zookeeper", ke);
            throw new IOException("Error reading" + namespaceRootPath + "entry in zookeeper", ke);
        }
        return result;
    }

    /**
     * Close the distributed log manager factory, freeing any resources it may hold.
     */
    public void close() {
        scheduledExecutorService.shutdown();
        LOG.info("Executor Service Stopped.");
        if (null != allocator) {
            allocator.close();
            LOG.info("Ledger Allocator stopped.");
        }
        try {
            BookKeeperClient bkc = getBookKeeperClient();
            if (null != bkc) {
                bkc.release();
            }
            zooKeeperClient.close();
        } catch (Exception e) {
            LOG.warn("Exception while closing distributed log manager factory", e);
        }
        channelFactory.releaseExternalResources();
    }
}
