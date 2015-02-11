package com.twitter.distributedlog.service;

import com.google.common.collect.Sets;
import com.twitter.distributedlog.DistributedLogConfiguration;
import com.twitter.distributedlog.LocalDLMEmulator;
import com.twitter.distributedlog.metadata.BKDLConfig;
import com.twitter.distributedlog.metadata.DLMetadata;
import com.twitter.finagle.builder.Server;
import com.twitter.finagle.thrift.ClientId$;
import com.twitter.finagle.builder.ClientBuilder;
import com.twitter.util.Duration;
import org.apache.bookkeeper.shims.zk.ZooKeeperServerShim;
import org.apache.bookkeeper.stats.NullStatsProvider;
import org.apache.bookkeeper.util.LocalBookKeeper;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public abstract class DistributedLogServerTestCase {

    protected static DistributedLogConfiguration conf =
            new DistributedLogConfiguration().setLockTimeout(10)
                    .setOutputBufferSize(0).setPeriodicFlushFrequencyMilliSeconds(10);
    protected static LocalDLMEmulator bkutil;
    protected static ZooKeeperServerShim zks;
    protected static URI uri;
    protected static int numBookies = 3;

    protected static class DLServer {
        public final InetSocketAddress address;
        public final Pair<DistributedLogServiceImpl, Server> dlServer;

        protected DLServer(int port) throws Exception {
            this(conf, port);
        }

        protected DLServer(DistributedLogConfiguration conf, int port) throws Exception {
            dlServer = DistributedLogServer.runServer(conf, uri, new NullStatsProvider(), port);
            address = DLSocketAddress.getSocketAddress(port);
        }

        public InetSocketAddress getAddress() {
            return address;
        }

        public void shutdown() {
            DistributedLogServer.closeServer(dlServer);
        }
    }

    protected static class DLClient {
        public final LocalRoutingService routingService;
        public final DistributedLogClientBuilder dlClientBuilder;
        public final DistributedLogClientBuilder.DistributedLogClientImpl dlClient;

        protected DLClient(String name) {
            this(name, ".*");
        }

        protected DLClient(String name, String streamNameRegex) {
            routingService = LocalRoutingService.newBuilder().build();
            dlClientBuilder = DistributedLogClientBuilder.newBuilder()
                        .name(name)
                        .clientId(ClientId$.MODULE$.apply(name))
                        .routingService(routingService)
                        .streamNameRegex(streamNameRegex)
                        .handshakeWithClientInfo(true)
                        .clientBuilder(ClientBuilder.get()
                            .hostConnectionLimit(1)
                            .connectionTimeout(Duration.fromSeconds(1))
                            .requestTimeout(Duration.fromSeconds(10)));
            dlClient = (DistributedLogClientBuilder.DistributedLogClientImpl) dlClientBuilder.build();
        }

        public void handshake() {
            dlClient.handshake();
        }

        public void shutdown() {
            dlClient.close();
        }
    }

    protected static class TwoRegionDLClient {

        public final LocalRoutingService localRoutingService;
        public final LocalRoutingService remoteRoutingService;
        public final DistributedLogClientBuilder dlClientBuilder;
        public final DistributedLogClientBuilder.DistributedLogClientImpl dlClient;

        protected TwoRegionDLClient(String name, Map<SocketAddress, String> regionMap) {
            localRoutingService = new LocalRoutingService();
            remoteRoutingService = new LocalRoutingService();
            RegionsRoutingService regionsRoutingService =
                    RegionsRoutingService.of(new TwitterRegionResolver(regionMap),
                            localRoutingService, remoteRoutingService);
            dlClientBuilder = DistributedLogClientBuilder.newBuilder()
                        .name(name)
                        .clientId(ClientId$.MODULE$.apply(name))
                        .routingService(regionsRoutingService)
                        .streamNameRegex(".*")
                        .handshakeWithClientInfo(true)
                        .maxRedirects(2)
                        .clientBuilder(ClientBuilder.get()
                            .hostConnectionLimit(1)
                            .connectionTimeout(Duration.fromSeconds(1))
                            .requestTimeout(Duration.fromSeconds(10)));
            dlClient = (DistributedLogClientBuilder.DistributedLogClientImpl) dlClientBuilder.build();
        }

        public void shutdown() {
            dlClient.close();
        }
    }

    protected DLServer dlServer;
    protected DLClient dlClient;

    @BeforeClass
    public static void setupCluster() throws Exception {
        zks = LocalBookKeeper.runZookeeper(1000, 7000);
        bkutil = new LocalDLMEmulator(numBookies, "127.0.0.1", 7000);
        bkutil.start();
        uri = LocalDLMEmulator.createDLMURI("127.0.0.1:7000", "");
        BKDLConfig bkdlConfig = new BKDLConfig("127.0.0.1:7000", "/ledgers").setACLRootPath(".acl");
        DLMetadata.create(bkdlConfig).update(uri);
    }

    @AfterClass
    public static void teardownCluster() throws Exception {
        bkutil.teardown();
        zks.stop();
    }

    @Before
    public void setup() throws Exception {
        dlServer = createDistributedLogServer(7001);
        dlClient = createDistributedLogClient("test");
    }

    @After
    public void teardown() throws Exception {
        if (null != dlClient) {
            dlClient.shutdown();
        }
        if (null != dlServer) {
            dlServer.shutdown();
        }
    }

    protected DLServer createDistributedLogServer(int port) throws Exception {
        return new DLServer(port);
    }

    protected DLServer createDistributedLogServer(DistributedLogConfiguration conf, int port)
            throws Exception {
        return new DLServer(conf, port);
    }

    protected DLClient createDistributedLogClient(String clientName) throws Exception {
        return createDistributedLogClient(clientName, ".*");
    }

    protected DLClient createDistributedLogClient(String clientName, String streamNameRegex)
            throws Exception {
        return new DLClient(clientName, streamNameRegex);
    }

    protected TwoRegionDLClient createTwoRegionDLClient(String clientName,
                                                        Map<SocketAddress, String> regionMap)
            throws Exception {
        return new TwoRegionDLClient(clientName, regionMap);
    }

    protected static void checkStreams(int numExpectedStreams, DLServer dlServer) {
        Set<String> cachedStreams = dlServer.dlServer.getKey().getCachedStreams().keySet();
        Set<String> acquiredStreams = dlServer.dlServer.getKey().getAcquiredStreams().keySet();

        assertEquals(numExpectedStreams, cachedStreams.size());
        assertEquals(numExpectedStreams, acquiredStreams.size());
    }

    protected static void checkStreams(Set<String> streams, DLServer dlServer) {
        Set<String> cachedStreams = dlServer.dlServer.getKey().getCachedStreams().keySet();
        Set<String> acquiredStreams = dlServer.dlServer.getKey().getAcquiredStreams().keySet();

        assertEquals(streams.size(), cachedStreams.size());
        assertEquals(streams.size(), acquiredStreams.size());
        assertTrue(Sets.difference(streams, cachedStreams).isEmpty());
        assertTrue(Sets.difference(streams, acquiredStreams).isEmpty());
    }

    protected static void checkStream(String name, DLClient dlClient, DLServer dlServer,
                                      int expectedNumProxiesInClient, int expectedClientCacheSize,
                                      int expectedServerCacheSize, boolean existedInServer, boolean existedInClient) {
        Map<SocketAddress, Set<String>> distribution = dlClient.dlClient.getStreamOwnershipDistribution();
        assertEquals(expectedNumProxiesInClient, distribution.size());

        if (expectedNumProxiesInClient > 0) {
            Map.Entry<SocketAddress, Set<String>> localEntry =
                    distribution.entrySet().iterator().next();
            assertEquals(dlServer.getAddress(), localEntry.getKey());
            assertEquals(expectedClientCacheSize, localEntry.getValue().size());
            assertEquals(existedInClient, localEntry.getValue().contains(name));
        }

        Set<String> cachedStreams = dlServer.dlServer.getKey().getCachedStreams().keySet();
        Set<String> acquiredStreams = dlServer.dlServer.getKey().getCachedStreams().keySet();

        assertEquals(expectedServerCacheSize, cachedStreams.size());
        assertEquals(existedInServer, cachedStreams.contains(name));
        assertEquals(expectedServerCacheSize, acquiredStreams.size());
        assertEquals(existedInServer, acquiredStreams.contains(name));
    }

    protected static Map<SocketAddress, Set<String>> getStreamOwnershipDistribution(DLClient dlClient) {
        return dlClient.dlClient.getStreamOwnershipDistribution();
    }

    protected static Set<String> getAllStreamsFromDistribution(Map<SocketAddress, Set<String>> distribution) {
        Set<String> allStreams = new HashSet<String>();
        for (Map.Entry<SocketAddress, Set<String>> entry : distribution.entrySet()) {
            allStreams.addAll(entry.getValue());
        }
        return allStreams;
    }

}
