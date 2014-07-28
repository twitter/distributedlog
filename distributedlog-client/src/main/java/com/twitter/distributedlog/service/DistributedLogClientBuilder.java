package com.twitter.distributedlog.service;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.twitter.common.zookeeper.ServerSet;
import com.twitter.distributedlog.DLSN;
import com.twitter.distributedlog.exceptions.DLClientClosedException;
import com.twitter.distributedlog.exceptions.DLException;
import com.twitter.distributedlog.exceptions.ServiceUnavailableException;
import com.twitter.distributedlog.exceptions.UnexpectedException;
import com.twitter.distributedlog.thrift.service.BulkWriteResponse;
import com.twitter.distributedlog.thrift.service.DistributedLogService;
import com.twitter.distributedlog.thrift.service.ResponseHeader;
import com.twitter.distributedlog.thrift.service.ServerInfo;
import com.twitter.distributedlog.thrift.service.StatusCode;
import com.twitter.distributedlog.thrift.service.WriteContext;
import com.twitter.distributedlog.thrift.service.WriteResponse;
import com.twitter.finagle.CancelledRequestException;
import com.twitter.finagle.ChannelException;
import com.twitter.finagle.ConnectionFailedException;
import com.twitter.finagle.Name;
import com.twitter.finagle.NoBrokersAvailableException;
import com.twitter.finagle.RequestTimeoutException;
import com.twitter.finagle.Service;
import com.twitter.finagle.ServiceException;
import com.twitter.finagle.ServiceTimeoutException;
import com.twitter.finagle.WriteException;
import com.twitter.finagle.builder.ClientBuilder;
import com.twitter.finagle.stats.Counter;
import com.twitter.finagle.stats.NullStatsReceiver;
import com.twitter.finagle.stats.Stat;
import com.twitter.finagle.stats.StatsReceiver;
import com.twitter.finagle.thrift.ClientId;
import com.twitter.finagle.thrift.ThriftClientFramedCodec;
import com.twitter.finagle.thrift.ThriftClientRequest;
import com.twitter.util.Duration;
import com.twitter.util.Future;
import com.twitter.util.FutureEventListener;
import com.twitter.util.Promise;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.runtime.AbstractFunction1;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DistributedLogClientBuilder {

    private static final int NUM_CONSISTENT_HASH_REPLICAS = 997;

    private String _name = null;
    private ClientId _clientId = null;
    private RoutingService _routingService = null;
    private ClientBuilder _clientBuilder = null;
    private StatsReceiver _statsReceiver = new NullStatsReceiver();
    private StatsReceiver _streamStatsReceiver = new NullStatsReceiver();
    private final ClientConfig _clientConfig = new ClientConfig();

    /**
     * Create a client builder
     *
     * @return client builder
     */
    public static DistributedLogClientBuilder newBuilder() {
        return new DistributedLogClientBuilder();
    }

    // private constructor
    private DistributedLogClientBuilder() {}

    /**
     * Client Name.
     *
     * @param name
     *          client name
     * @return client builder.
     */
    public DistributedLogClientBuilder name(String name) {
        this._name = name;
        return this;
    }

    /**
     * Client ID.
     *
     * @param clientId
     *          client id
     * @return client builder.
     */
    public DistributedLogClientBuilder clientId(ClientId clientId) {
        this._clientId = clientId;
        return this;
    }

    /**
     * Serverset to access proxy services.
     *
     * @param serverSet
     *          server set.
     * @return client builder.
     */
    public DistributedLogClientBuilder serverSet(ServerSet serverSet) {
        this._routingService = ConsistentHashRoutingService.of(serverSet,
                NUM_CONSISTENT_HASH_REPLICAS);
        return this;
    }


    /**
     * Name to access proxy services.
     *
     * @param serverSet
     *          server set.
     * @return client builder.
     */
    public DistributedLogClientBuilder finagleNameStr(String finagleNameStr) {
        if (!finagleNameStr.startsWith("serverset!") && !finagleNameStr.startsWith("inet!")) {
            // We only support serverset based names at the moment
            throw new UnsupportedOperationException("Finagle Name format not supported for name: " + finagleNameStr);
        }

        this._routingService = ConsistentHashRoutingService.of(new NameServerSet(finagleNameStr),
            NUM_CONSISTENT_HASH_REPLICAS);
        return this;
    }

    /**
     * Routing Service to access proxy services.
     *
     * @param routingService
     *          routing service
     * @return client builder.
     */
    DistributedLogClientBuilder routingService(RoutingService routingService) {
        this._routingService = routingService;
        return this;
    }

    /**
     * Stats receiver to expose client stats.
     *
     * @param statsReceiver
     *          stats receiver.
     * @return client builder.
     */
    public DistributedLogClientBuilder statsReceiver(StatsReceiver statsReceiver) {
        this._statsReceiver = statsReceiver;
        return this;
    }

    public DistributedLogClientBuilder clientBuilder(ClientBuilder builder) {
        this._clientBuilder = builder;
        return this;
    }

    /**
     * Backoff time when redirecting to an already retried host.
     *
     * @param ms
     *          backoff time.
     * @return client builder.
     */
    public DistributedLogClientBuilder redirectBackoffStartMs(int ms) {
        this._clientConfig.setRedirectBackoffStartMs(ms);
        return this;
    }

    /**
     * Max backoff time when redirecting to an already retried host.
     *
     * @param ms
     *          backoff time.
     * @return client builder.
     */
    public DistributedLogClientBuilder redirectBackoffMaxMs(int ms) {
        this._clientConfig.setRedirectBackoffMaxMs(ms);
        return this;
    }

    /**
     * Max redirects that is allowed per request. If <i>redirects</i> are
     * exhausted, fail the request immediately.
     *
     * @param redirects
     *          max redirects allowed before failing a request.
     * @return client builder.
     */
    public DistributedLogClientBuilder maxRedirects(int redirects) {
        this._clientConfig.setMaxRedirects(redirects);
        return this;
    }

    /**
     * Timeout per request in millis.
     *
     * @param timeoutMs
     *          timeout per request in millis.
     * @return client builder.
     */
    public DistributedLogClientBuilder requestTimeoutMs(int timeoutMs) {
        this._clientConfig.setRequestTimeoutMs(timeoutMs);
        return this;
    }

    public DistributedLogClient build() {
        return buildClient();
    }

    DistributedLogClientImpl buildClient() {
        Preconditions.checkNotNull(_name, "No name provided.");
        Preconditions.checkNotNull(_clientId, "No client id provided.");
        Preconditions.checkNotNull(_routingService, "No routing service provided.");
        Preconditions.checkNotNull(_statsReceiver, "No stats receiver provided.");
        if (null == _streamStatsReceiver) {
            _streamStatsReceiver = new NullStatsReceiver();
        }

        DistributedLogClientImpl clientImpl =
                new DistributedLogClientImpl(_name, _clientId, _routingService, _clientBuilder, _clientConfig,
                                             _statsReceiver, _streamStatsReceiver);
        _routingService.startService();
        clientImpl.handshake();
        return clientImpl;
    }

    static class ClientConfig {
        int redirectBackoffStartMs = 25;
        int redirectBackoffMaxMs = 100;
        int maxRedirects = -1;
        int requestTimeoutMs = -1;

        ClientConfig setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
            return this;
        }

        int getMaxRedirects() {
            return this.maxRedirects;
        }

        ClientConfig setRequestTimeoutMs(int timeoutInMillis) {
            this.requestTimeoutMs = timeoutInMillis;
            return this;
        }

        int getRequestTimeoutMs() {
            return this.requestTimeoutMs;
        }

        ClientConfig setRedirectBackoffStartMs(int ms) {
            this.redirectBackoffStartMs = ms;
            return this;
        }

        int getRedirectBackoffStartMs() {
            return this.redirectBackoffStartMs;
        }

        ClientConfig setRedirectBackoffMaxMs(int ms) {
            this.redirectBackoffMaxMs = ms;
            return this;
        }

        int getRedirectBackoffMaxMs() {
            return this.redirectBackoffMaxMs;
        }
    }

    static class DistributedLogClientImpl implements DistributedLogClient, MonitorServiceClient, RoutingService.RoutingListener {

        static final Logger logger = LoggerFactory.getLogger(DistributedLogClientImpl.class);

        private static class ServiceWithClient {
            final Service<ThriftClientRequest, byte[]> client;
            final DistributedLogService.ServiceIface service;

            ServiceWithClient(Service<ThriftClientRequest, byte[]> client,
                              DistributedLogService.ServiceIface service) {
                this.client = client;
                this.service = service;
            }
        }

        private final String clientName;
        private final ClientId clientId;
        private final ClientConfig clientConfig;
        private final RoutingService routingService;
        private final ClientBuilder clientBuilder;

        // Timer
        private final HashedWheelTimer dlTimer;

        // Ownership maintenance
        private final ConcurrentHashMap<String, SocketAddress> stream2Addresses =
                new ConcurrentHashMap<String, SocketAddress>();
        private final ConcurrentHashMap<SocketAddress, Set<String>> address2Streams =
                new ConcurrentHashMap<SocketAddress, Set<String>>();
        private final ConcurrentHashMap<SocketAddress, ServiceWithClient> address2Services =
                new ConcurrentHashMap<SocketAddress, ServiceWithClient>();

        // Close Status
        private boolean closed = false;
        private final ReentrantReadWriteLock closeLock =
                new ReentrantReadWriteLock();

        // Stats
        private static class OwnershipStat {
            private final Counter hits;
            private final Counter misses;
            private final Counter removes;
            private final Counter redirects;
            private final Counter adds;

            OwnershipStat(StatsReceiver ownershipStats) {
                hits = ownershipStats.counter0("hits");
                misses = ownershipStats.counter0("misses");
                adds = ownershipStats.counter0("adds");
                removes = ownershipStats.counter0("removes");
                redirects = ownershipStats.counter0("redirects");
            }

            void onHit() {
                hits.incr();
            }

            void onMiss() {
                misses.incr();
            }

            void onAdd() {
                adds.incr();
            }

            void onRemove() {
                removes.incr();
            }

            void onRedirect() {
                redirects.incr();
            }

        }

        private final StatsReceiver statsReceiver;
        private final Stat redirectStat;
        private final StatsReceiver responseStatsReceiver;
        private final ConcurrentMap<StatusCode, Counter> responseStats =
                new ConcurrentHashMap<StatusCode, Counter>();
        private final StatsReceiver exceptionStatsReceiver;
        private final ConcurrentMap<Class<?>, Counter> exceptionStats =
                new ConcurrentHashMap<Class<?>, Counter>();
        private final OwnershipStat ownershipStat;
        private final StatsReceiver ownershipStatsReceiver;
        private final ConcurrentMap<String, OwnershipStat> ownershipStats =
                new ConcurrentHashMap<String, OwnershipStat>();
        private final Stat successLatencyStat;
        private final Stat failureLatencyStat;
        private final Stat proxySuccessLatencyStat;
        private final Stat proxyFailureLatencyStat;

        private Counter getResponseCounter(StatusCode code) {
            Counter counter = responseStats.get(code);
            if (null == counter) {
                Counter newCounter = responseStatsReceiver.counter0(code.name());
                Counter oldCounter = responseStats.putIfAbsent(code, newCounter);
                counter = null != oldCounter ? oldCounter : newCounter;
            }
            return counter;
        }

        private Counter getExceptionCounter(Class<?> cls) {
            Counter counter = exceptionStats.get(cls);
            if (null == counter) {
                Counter newCounter = exceptionStatsReceiver.counter0(cls.getName());
                Counter oldCounter = exceptionStats.putIfAbsent(cls, newCounter);
                counter = null != oldCounter ? oldCounter : newCounter;
            }
            return counter;
        }

        private OwnershipStat getOwnershipStat(String stream) {
            OwnershipStat stat = ownershipStats.get(stream);
            if (null == stat) {
                OwnershipStat newStat = new OwnershipStat(ownershipStatsReceiver.scope(stream));
                OwnershipStat oldStat = ownershipStats.putIfAbsent(stream, newStat);
                stat = null != oldStat ? oldStat : newStat;
            }
            return stat;
        }

        abstract class StreamOp implements TimerTask {
            final String stream;

            final AtomicInteger tries = new AtomicInteger(0);
            final WriteContext ctx = new WriteContext();
            final Stopwatch stopwatch;
            SocketAddress nextAddressToSend;

            StreamOp(final String stream) {
                this.stream = stream;
                this.stopwatch = new Stopwatch().start();
            }

            void send(SocketAddress address) {
                long elapsedMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
                if (clientConfig.getMaxRedirects() > 0 &&
                        tries.get() >= clientConfig.getMaxRedirects()) {
                    fail(new RequestTimeoutException(Duration.fromMilliseconds(elapsedMs),
                            "Exhausted max redirects in " + elapsedMs + " ms"));
                    return;
                } else if (clientConfig.getRequestTimeoutMs() > 0 &&
                        elapsedMs >= clientConfig.getRequestTimeoutMs()) {
                    fail(new RequestTimeoutException(Duration.fromMilliseconds(elapsedMs),
                            "Exhausted max request timeout " + clientConfig.getRequestTimeoutMs()
                                    + " in " + elapsedMs + " ms"));
                    return;
                }
                synchronized (this) {
                    String addrStr = address.toString();
                    if (ctx.isSetTriedHosts() && ctx.getTriedHosts().contains(addrStr)) {
                        nextAddressToSend = address;
                        dlTimer.newTimeout(this,
                                Math.min(clientConfig.getRedirectBackoffMaxMs(),
                                        tries.get() * clientConfig.getRedirectBackoffStartMs()),
                                TimeUnit.MILLISECONDS);
                    } else {
                        doSend(address);
                    }
                }
            }

            abstract Future<ResponseHeader> sendRequest(ServiceWithClient sc);

            void doSend(SocketAddress address) {
                ctx.addToTriedHosts(address.toString());
                tries.incrementAndGet();
                sendWriteRequest(address, this);
            }

            void complete() {
                stopwatch.stop();
                successLatencyStat.add(stopwatch.elapsed(TimeUnit.MICROSECONDS));
                redirectStat.add(tries.get());
            }

            void fail(Throwable t) {
                stopwatch.stop();
                failureLatencyStat.add(stopwatch.elapsed(TimeUnit.MICROSECONDS));
                redirectStat.add(tries.get());
            }

            @Override
            synchronized public void run(Timeout timeout) throws Exception {
                if (!timeout.isCancelled() && null != nextAddressToSend) {
                    doSend(nextAddressToSend);
                } else {
                    fail(new CancelledRequestException());
                }
            }
        }

        class BulkWriteOp extends StreamOp {

            final List<ByteBuffer> data;
            final ArrayList<Promise<DLSN>> results;

            BulkWriteOp(final String name, final List<ByteBuffer> data) {
                super(name);
                this.data = data;
                
                // This could take a while (relatively speaking) for very large inputs. We probably don't want 
                // to go so large for other reasons though.
                this.results = new ArrayList<Promise<DLSN>>(data.size());
                for (int i = 0; i < data.size(); i++) {
                    this.results.add(new Promise<DLSN>());
                } 
            }

            @Override
            Future<ResponseHeader> sendRequest(ServiceWithClient sc) {
                return sc.service.writeBulkWithContext(stream, data, ctx).addEventListener(new FutureEventListener<BulkWriteResponse>() {
                    @Override
                    public void onSuccess(BulkWriteResponse response) {
                        // For non-success case, the ResponseHeader handler (the caller) will handle it.
                        // Note success in this case means no finagle errors have occurred (such as finagle connection issues).
                        // In general code != SUCCESS means there's some error reported by dlog service. The caller will handle such 
                        // errors. 
                        if (response.getHeader().getCode() == StatusCode.SUCCESS) {
                            BulkWriteOp.this.complete(response);
                            if (response.getWriteResponses().size() == 0 && data.size() > 0) {
                                logger.error("non-empty bulk write got back empty response without failure for stream {}", stream);
                            }
                        }
                    }
                    @Override
                    public void onFailure(Throwable cause) {
                        // Handled by the ResponseHeader listener (attached by the caller).
                    }
                }).map(new AbstractFunction1<BulkWriteResponse, ResponseHeader>() {
                    @Override
                    public ResponseHeader apply(BulkWriteResponse response) {
                        // We need to return the ResponseHeader to the caller's listener to process DLOG errors.
                        return response.getHeader();
                    }
                });
            }


            void complete(BulkWriteResponse bulkWriteResponse) {
                super.complete();
                Iterator<WriteResponse> writeResponseIterator = bulkWriteResponse.getWriteResponses().iterator();
                Iterator<Promise<DLSN>> resultIterator = results.iterator();
                
                // Fill in errors from thrift responses.
                while (resultIterator.hasNext() && writeResponseIterator.hasNext()) {
                    Promise<DLSN> result = resultIterator.next();
                    WriteResponse writeResponse = writeResponseIterator.next();
                    if (StatusCode.SUCCESS == writeResponse.getHeader().getCode()) {
                        result.setValue(DLSN.deserialize(writeResponse.getDlsn()));
                    } else {
                        result.setException(DLException.of(writeResponse.getHeader()));
                    }
                } 
                
                // Should never happen, but just in case so there's some record.
                if (bulkWriteResponse.getWriteResponses().size() != data.size()) {
                    logger.error("wrong number of results, response = {} records = ", bulkWriteResponse.getWriteResponses().size(), data.size());
                } 
            }

            @Override
            void fail(Throwable t) {

                // StreamOp.fail is called to fail the overall request. In case of BulkWriteOp we take the request level 
                // exception to apply to the first write. In fact for request level exceptions no request has ever been 
                // attempted, but logically we associate the error with the first write.
                super.fail(t);
                Iterator<Promise<DLSN>> resultIterator = results.iterator();

                // Fail the first write with the batch level failure.
                if (resultIterator.hasNext()) {
                    Promise<DLSN> result = resultIterator.next();
                    result.setException(t);
                } 

                // Fail the remaining writes as cancelled requests. 
                while (resultIterator.hasNext()) {
                    Promise<DLSN> result = resultIterator.next();
                    result.setException(new CancelledRequestException());
                }
            }

            List<Future<DLSN>> result() {
                return (List) results;
            }
        }

        abstract class AbstractWriteOp extends StreamOp {
            
            final Promise<WriteResponse> result = new Promise<WriteResponse>();

            AbstractWriteOp(final String name) {
                super(name);
            }

            void complete(WriteResponse response) {
                super.complete();
                result.setValue(response);
            }

            @Override
            void fail(Throwable t) {
                super.fail(t);
                result.setException(t);
            }

            @Override
            Future<ResponseHeader> sendRequest(ServiceWithClient sc) {
                return this.sendWriteRequest(sc).addEventListener(new FutureEventListener<WriteResponse>() {
                    @Override
                    public void onSuccess(WriteResponse response) {
                        if (response.getHeader().getCode() == StatusCode.SUCCESS) {
                            AbstractWriteOp.this.complete(response); 
                        }
                    }
                    @Override
                    public void onFailure(Throwable cause) {
                        // handled by the ResponseHeader listener
                    }
                }).map(new AbstractFunction1<WriteResponse, ResponseHeader>() {
                    @Override
                    public ResponseHeader apply(WriteResponse response) {
                        return response.getHeader();
                    }
                });
            }

            abstract Future<WriteResponse> sendWriteRequest(ServiceWithClient sc);
        } 

        class WriteOp extends AbstractWriteOp {

            final ByteBuffer data;

            WriteOp(final String name, final ByteBuffer data) {
                super(name);
                this.data = data;
            }

            @Override
            Future<WriteResponse> sendWriteRequest(ServiceWithClient sc) {
                return sc.service.writeWithContext(stream, data, ctx);
            }

            Future<DLSN> result() {
                return result.map(new AbstractFunction1<WriteResponse, DLSN>() {
                    @Override
                    public DLSN apply(WriteResponse response) {
                        return DLSN.deserialize(response.getDlsn());
                    }
                });
            }
        }

        class TruncateOp extends AbstractWriteOp {
            final DLSN dlsn;

            TruncateOp(String name, DLSN dlsn) {
                super(name);
                this.dlsn = dlsn;
            }

            @Override
            Future<WriteResponse> sendWriteRequest(ServiceWithClient sc) {
                return sc.service.truncate(stream, dlsn.serialize(), ctx);
            }

            Future<Boolean> result() {
                return result.map(new AbstractFunction1<WriteResponse, Boolean>() {
                    @Override
                    public Boolean apply(WriteResponse response) {
                        return true;
                    }
                });
            }
        }

        class ReleaseOp extends AbstractWriteOp {

            ReleaseOp(String name) {
                super(name);
            }

            @Override
            Future<WriteResponse> sendWriteRequest(ServiceWithClient sc) {
                return sc.service.release(stream, ctx);
            }

            Future<Void> result() {
                return result.map(new AbstractFunction1<WriteResponse, Void>() {
                    @Override
                    public Void apply(WriteResponse response) {
                        return null;
                    }
                });
            }
        }

        class DeleteOp extends AbstractWriteOp {

            DeleteOp(String name) {
                super(name);
            }

            @Override
            Future<WriteResponse> sendWriteRequest(ServiceWithClient sc) {
                return sc.service.delete(stream, ctx);
            }

            Future<Void> result() {
                return result.map(new AbstractFunction1<WriteResponse, Void>() {
                    @Override
                    public Void apply(WriteResponse v1) {
                        return null;
                    }
                });
            }
        }

        class HeartbeatOp extends AbstractWriteOp {

            HeartbeatOp(String name) {
                super(name);
            }

            @Override
            Future<WriteResponse> sendWriteRequest(ServiceWithClient sc) {
                return sc.service.heartbeat(stream, ctx);
            }

            Future<Void> result() {
                return result.map(new AbstractFunction1<WriteResponse, Void>() {
                    @Override
                    public Void apply(WriteResponse response) {
                        return null;
                    }
                });
            }
        }

        private DistributedLogClientImpl(String name,
                                         ClientId clientId,
                                         RoutingService routingService,
                                         ClientBuilder clientBuilder,
                                         ClientConfig clientConfig,
                                         StatsReceiver statsReceiver,
                                         StatsReceiver streamStatsReceiver) {
            this.clientName = name;
            this.clientId = clientId;
            this.routingService = routingService;
            this.clientConfig = clientConfig;
            this.statsReceiver = statsReceiver;
            // Build the timer
            this.dlTimer = new HashedWheelTimer(
                    new ThreadFactoryBuilder().setNameFormat("DLClient-" + name + "-timer-%d").build(),
                    this.clientConfig.getRedirectBackoffStartMs(),
                    TimeUnit.MILLISECONDS);
            // register routing listener
            this.routingService.registerListener(this);
            // Stats
            ownershipStat = new OwnershipStat(statsReceiver.scope("ownership"));
            ownershipStatsReceiver = streamStatsReceiver.scope("perstream_ownership");
            StatsReceiver redirectStatReceiver = statsReceiver.scope("redirects");
            redirectStat = redirectStatReceiver.stat0("times");
            responseStatsReceiver = statsReceiver.scope("responses");
            exceptionStatsReceiver = statsReceiver.scope("exceptions");
            StatsReceiver latencyStatReceiver = statsReceiver.scope("latency");
            successLatencyStat = latencyStatReceiver.stat0("success");
            failureLatencyStat = latencyStatReceiver.stat0("failure");
            StatsReceiver proxyLatencyStatReceiver = statsReceiver.scope("proxy_request_latency");
            proxySuccessLatencyStat = proxyLatencyStatReceiver.stat0("success");
            proxyFailureLatencyStat = proxyLatencyStatReceiver.stat0("failure");

            // client builder
            this.clientBuilder = setDefaultSettings(null == clientBuilder ? getDefaultClientBuilder() : clientBuilder);
            logger.info("Build distributedlog client : name = {}, client_id = {}, routing_service = {}, stats_receiver = {}",
                        new Object[] { name, clientId, routingService.getClass(), statsReceiver.getClass() });
        }

        private void handshake() {
            Map<SocketAddress, ServiceWithClient> snapshot =
                    new HashMap<SocketAddress, ServiceWithClient>(address2Services);
            final CountDownLatch latch = new CountDownLatch(snapshot.size());
            FutureEventListener<ServerInfo> listener = new FutureEventListener<ServerInfo>() {
                @Override
                public void onSuccess(ServerInfo value) {
                    if (null != value && value.isSetOwnerships()) {
                        Map<String, String> ownerships = value.getOwnerships();
                        for (Map.Entry<String, String> entry : ownerships.entrySet()) {
                            updateOwnership(entry.getKey(), entry.getValue());
                        }
                    }
                    latch.countDown();
                }
                @Override
                public void onFailure(Throwable cause) {
                    latch.countDown();
                }
            };
            for (Map.Entry<SocketAddress, ServiceWithClient> entry : snapshot.entrySet()) {
                logger.info("Handshaking with {}", entry.getKey());
                entry.getValue().service.handshake().addEventListener(listener);
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                logger.warn("Interrupted on handshaking with servers : ", e);
            }
        }

        private ClientBuilder getDefaultClientBuilder() {
            return ClientBuilder.get()
                .hostConnectionLimit(1)
                .connectionTimeout(Duration.fromSeconds(1))
                .requestTimeout(Duration.fromSeconds(1));
        }

        private ClientBuilder setDefaultSettings(ClientBuilder builder) {
            return builder.name(clientName)
                   .codec(ThriftClientFramedCodec.apply(Option.apply(clientId)))
                   .failFast(false)
                   .keepAlive(true)
                   .reportTo(statsReceiver);
        }

        @Override
        public void onServerLeft(SocketAddress address) {
            onServerLeft(address, null);
        }

        private void onServerLeft(SocketAddress address, ServiceWithClient sc) {
            clearAllStreamsForHost(address);
            if (null == sc) {
                removeClient(address);
            } else {
                removeClient(address, sc);
            }
        }

        @Override
        public void onServerJoin(SocketAddress address) {
            buildClient(address);
        }

        private ServiceWithClient buildClient(SocketAddress address) {
            ServiceWithClient sc = address2Services.get(address);
            if (null != sc) {
                return sc;
            }
            // Build factory since DL proxy is kind of stateful service.
            Service<ThriftClientRequest, byte[]> client =
                    ClientBuilder.safeBuildFactory(clientBuilder.hosts(address)).toService();
            DistributedLogService.ServiceIface service =
                    new DistributedLogService.ServiceToClient(client, new TBinaryProtocol.Factory());
            sc = new ServiceWithClient(client, service);
            ServiceWithClient oldSC = address2Services.putIfAbsent(address, sc);
            if (null != oldSC) {
                sc.client.close();
                return oldSC;
            } else {
                // send a ping messaging after creating connections.
                sc.service.handshake();
                return sc;
            }
        }

        private void removeClient(SocketAddress address) {
            ServiceWithClient sc = address2Services.remove(address);
            if (null != sc) {
                logger.info("Removed host {}.", address);
                sc.client.close();
            }
        }

        private void removeClient(SocketAddress address, ServiceWithClient sc) {
            if (address2Services.remove(address, sc)) {
                logger.info("Remove client {} to host {}.", sc, address);
                sc.client.close();
            }
        }

        public void close() {
            closeLock.writeLock().lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
            } finally {
                closeLock.writeLock().unlock();
            }
            for (ServiceWithClient sc: address2Services.values()) {
                sc.client.close();
            }
            routingService.unregisterListener(this);
            routingService.stopService();
            dlTimer.stop();
        }

        @Override
        public Future<Void> check(String stream) {
            final HeartbeatOp op = new HeartbeatOp(stream);
            sendRequest(op);
            return op.result();
        }

        @Override
        public Future<DLSN> write(String stream, ByteBuffer data) {
            final WriteOp op = new WriteOp(stream, data);
            sendRequest(op);
            return op.result();
        }

        @Override
        public List<Future<DLSN>> writeBulk(String stream, List<ByteBuffer> data) {
            if (data.size() > 0) {
                final BulkWriteOp op = new BulkWriteOp(stream, data);
                sendRequest(op);
                return op.result();
            } else {
                return Collections.emptyList();
            }
        }

        @Override
        public Future<Boolean> truncate(String stream, DLSN dlsn) {
            final TruncateOp op = new TruncateOp(stream, dlsn);
            sendRequest(op);
            return op.result();
        }

        @Override
        public Future<Void> delete(String stream) {
            final DeleteOp op = new DeleteOp(stream);
            sendRequest(op);
            return op.result();
        }

        @Override
        public Future<Void> release(String stream) {
            final ReleaseOp op = new ReleaseOp(stream);
            sendRequest(op);
            return op.result();
        }

        private void sendRequest(final StreamOp op) {
            closeLock.readLock().lock();
            try {
                if (closed) {
                    op.fail(new DLClientClosedException("Client " + clientName + " is closed."));
                } else {
                    doSend(op, null);
                }
            } finally {
                closeLock.readLock().unlock();
            }
        }

        private void doSend(final StreamOp op, SocketAddress previousAddr) {
            // Get host first
            SocketAddress address = stream2Addresses.get(op.stream);
            if (null == address || address.equals(previousAddr)) {
                ownershipStat.onMiss();
                getOwnershipStat(op.stream).onMiss();
                // pickup host by hashing
                try {
                    address = routingService.getHost(op.stream, previousAddr);
                } catch (NoBrokersAvailableException nbae) {
                    op.fail(nbae);
                    return;
                }
            } else {
                ownershipStat.onHit();
                getOwnershipStat(op.stream).onHit();
            }
            op.send(address);
        }

        private void sendWriteRequest(final SocketAddress addr, final StreamOp op) {
            // Get corresponding finagle client
            final ServiceWithClient sc = buildClient(addr);
            final long startTimeNanos = System.nanoTime();
            // write the request to that host.
            op.sendRequest(sc).addEventListener(new FutureEventListener<ResponseHeader>() {
                @Override
                public void onSuccess(ResponseHeader header) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Received response; header: {}", header); 
                    }
                    getResponseCounter(header.getCode()).incr();
                    proxySuccessLatencyStat.add(elapsedMicroSec(startTimeNanos)); 
                    switch (header.getCode()) {
                        case SUCCESS:
                            updateOwnership(op.stream, addr);
                            break; 
                        case FOUND:
                            handleRedirectResponse(header, op, addr);
                            break;
                        // for responses that indicate the requests definitely failed,
                        // we should fail them immediately (e.g. BAD_REQUEST, TOO_LARGE_RECORD, METADATA_EXCEPTION)
                        case NOT_IMPLEMENTED:
                        case METADATA_EXCEPTION:
                        case LOG_EMPTY:
                        case LOG_NOT_FOUND:
                        case TRUNCATED_TRANSACTION:
                        case END_OF_STREAM:
                        case TRANSACTION_OUT_OF_ORDER:
                        case INVALID_STREAM_NAME:
                            logger.error("Failed to write request to {} : {}", op.stream, header); 
                            op.fail(DLException.of(header));
                            break;
                        case SERVICE_UNAVAILABLE:
                            // service is unavailable, remove it out of routing service
                            routingService.removeHost(addr, new ServiceUnavailableException(addr + " is unavailable now."));
                            onServerLeft(addr);
                            // redirect the request to other host.
                            redirect(op, addr, null);
                            break;
                        case STREAM_UNAVAILABLE:
                        case ZOOKEEPER_ERROR:
                        case LOCKING_EXCEPTION:
                        case UNEXPECTED:
                        case INTERRUPTED:
                        case BK_TRANSMIT_ERROR:
                        case FLUSH_TIMEOUT:
                        default:
                            // when we are receiving these exceptions from proxy, it means proxy or the stream is closed
                            // redirect the request.
                            clearHostFromStream(op.stream, addr);
                            redirect(op, addr, null);
                            break;
                    }
                }

                @Override
                public void onFailure(Throwable cause) {
                    getExceptionCounter(cause.getClass()).incr();
                    proxyFailureLatencyStat.add(elapsedMicroSec(startTimeNanos));
                    if (cause instanceof ConnectionFailedException) {
                        routingService.removeHost(addr, cause);
                        onServerLeft(addr, sc);
                        // redirect the request to other host.
                        doSend(op, addr);
                    } else if (cause instanceof ChannelException) {
                        // redirect the request to other host.
                        doSend(op, addr);
                    } else if (cause instanceof ServiceTimeoutException) {
                        // redirect the request to itself again, which will backoff for a while
                        doSend(op, null);
                    } else if (cause instanceof WriteException) {
                        // redirect the request to other host.
                        doSend(op, addr);
                    } else if (cause instanceof ServiceException) {
                        // redirect the request to other host.
                        removeClient(addr, sc);
                        doSend(op, addr);
                    } else {
                        // RequestTimeoutException: fail it and let client decide whether to retry or not.

                        // FailedFastException:
                        // We don't actually know when FailedFastException will be thrown
                        // so properly we just throw it back to application to let application
                        // handle it.

                        // Other Exceptions: as we don't know how to handle them properly so throw them to client
                        logger.error("Failed to write request to {} @ {} : {}",
                                new Object[]{op.stream, addr, null != cause.getMessage() ? cause.getMessage() : cause.getClass()});
                        op.fail(cause);
                    }
                }
            });
        }

        // Response Handlers

        void redirect(StreamOp op, SocketAddress oldAddr, SocketAddress newAddr) {
            ownershipStat.onRedirect();
            getOwnershipStat(op.stream).onRedirect();
            if (null != newAddr) {
                logger.debug("Redirect request {} to new owner {}.", op, newAddr);
                op.send(newAddr);
            } else {
                doSend(op, oldAddr);
            }
        }

        void handleRedirectResponse(ResponseHeader header, StreamOp op, SocketAddress curAddr) {
            SocketAddress ownerAddr = null;
            if (header.isSetLocation()) {
                String owner = header.getLocation();
                try {
                    ownerAddr = DLSocketAddress.deserialize(owner).getSocketAddress();
                    // we are receiving a redirect request to same host. this indicates that the proxy
                    // is in bad state, so fail the request.
                    if (curAddr.equals(ownerAddr)) {
                        // throw the exception to the client
                        op.fail(new UnexpectedException(
                                String.format("Request to stream %s is redirected to same server %s!",
                                        op.stream, curAddr)));
                        return;
                    }
                    // update ownership when redirects.
                    updateOwnership(op.stream, ownerAddr);
                } catch (IOException e) {
                    ownerAddr = null;
                }
            }
            redirect(op, curAddr, ownerAddr);
        }

        void updateOwnership(String stream, String location) {
            try {
                SocketAddress ownerAddr = DLSocketAddress.deserialize(location).getSocketAddress();
                // update ownership
                updateOwnership(stream, ownerAddr);
            } catch (IOException e) {
                logger.warn("Invalid ownership {} found for stream {} : ",
                            new Object[] { location, stream, e });
            }
        }

        // Ownership Operations

        /**
         * Update ownership of <i>stream</i> to <i>addr</i>.
         *
         * @param stream
         *          Stream Name.
         * @param addr
         *          Owner Address.
         */
        void updateOwnership(String stream, SocketAddress addr) {
            // update ownership
            SocketAddress oldAddr = stream2Addresses.putIfAbsent(stream, addr);
            if (null != oldAddr && oldAddr.equals(addr)) {
                return;
            }
            if (null != oldAddr) {
                if (stream2Addresses.replace(stream, oldAddr, addr)) {
                    // Store the relevant mappings for this topic and host combination
                    logger.info("Storing ownership for stream : {}, old host : {}, new host : {}.",
                            new Object[] { stream, oldAddr, addr });
                    clearHostFromStream(stream, oldAddr);

                    // update stats
                    ownershipStat.onRemove();
                    ownershipStat.onAdd();
                    getOwnershipStat(stream).onRemove();
                    getOwnershipStat(stream).onAdd();
                } else {
                    logger.warn("Ownership of stream : {} has been changed from {} to {} when storing host : {}.",
                            new Object[] { stream, oldAddr, stream2Addresses.get(stream), addr });
                    return;
                }
            } else {
                logger.info("Storing ownership for stream : {}, host : {}.", stream, addr);
                // update stats
                ownershipStat.onAdd();
                getOwnershipStat(stream).onAdd();
            }

            Set<String> streamsForHost = address2Streams.get(addr);
            if (null == streamsForHost) {
                Set<String> newStreamsForHost = new HashSet<String>();
                streamsForHost = address2Streams.putIfAbsent(addr, newStreamsForHost);
                if (null == streamsForHost) {
                    streamsForHost = newStreamsForHost;
                }
            }
            synchronized (streamsForHost) {
                // check whether the ownership changed, since it might happend after replace succeed
                if (addr.equals(stream2Addresses.get(stream))) {
                    streamsForHost.add(stream);
                }
            }
        }

        /**
         * If a server host goes down or the channel to it gets disconnected, we need to clear out
         * all relevant cached information.
         *
         * @param addr
         *          host goes down
         */
        void clearAllStreamsForHost(SocketAddress addr) {
            Set<String> streamsForHost = address2Streams.get(addr);
            if (null != streamsForHost) {
                synchronized (streamsForHost) {
                    for (String s : streamsForHost) {
                        if (stream2Addresses.remove(s, addr)) {
                            logger.info("Removing mapping for stream : {} from host : {}", s, addr);
                            ownershipStat.onRemove();
                            getOwnershipStat(s).onRemove();
                        }
                    }
                    address2Streams.remove(addr, streamsForHost);
                }
            }
        }

        /**
         * If a stream moved, we only clear out that stream for the host and not all cached information.
         *
         * @param stream
         *          Stream Name.
         * @param addr
         *          Owner Address.
         */
        void clearHostFromStream(String stream, SocketAddress addr) {
            if (stream2Addresses.remove(stream, addr)) {
                logger.info("Removed stream to host mapping for (stream: {} -> host: {}).", stream, addr);
            }
            Set<String> streamsForHost = address2Streams.get(addr);
            if (null != streamsForHost) {
                synchronized (streamsForHost) {
                    if (streamsForHost.remove(stream)) {
                        logger.info("Removed stream : {} from host {}.", stream, addr);
                        if (streamsForHost.isEmpty()) {
                            address2Streams.remove(addr, streamsForHost);
                        }
                        ownershipStat.onRemove();
                        getOwnershipStat(stream).onRemove();
                    }
                }
            }
        }
    }

    static long elapsedMicroSec(long startNanoTime) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanoTime);
    }
}
