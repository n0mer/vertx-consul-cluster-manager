package io.vertx.spi.cluster.consul.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.spi.cluster.NodeListener;
import io.vertx.ext.consul.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Main manager is accountable for:
 * <p>
 * - Node registration within the cluster. Every new consul service corresponds to new vertx node, ie - mapping is: vertx.node <-> consul service. Don't confuse
 * vertx node with consul (native) node - these are completely different things.
 * <p>
 * - Node discovery. Nodes discovery happens at the stage where new node is joining the cluster and then it discovers other nodes (consul services).
 * <p>
 * - HaInfo pre-initialization. We need to pre-build haInfo map at the "node is joining" stage to be able later on to properly
 * initialize the consul sync map. We can't block the event loop thread that gets the consul sync map. (Consul sync map holds haInfo.)
 * <p>
 * - Creating dummy TCP server to receive and acknowledge heart beats messages from consul.
 * <p>
 * - Creating TCP check that is to be mapped to ONLY vertx node (consul service). TCP check "tells" consul agent to send heartbeat message to vertx nodes.
 * <p>
 * - Creating consul session. Consul session is used to make consul map entries ephemeral (every entry with that is created with special KV options referencing session id gets automatically deleted
 * from the consul cluster once session gets invalidated).
 * In this cluster manager case session will get invalidated when:
 * <li>health check gets unregistered.</li>
 * <li>health check goes to critical state (when this.netserver doesn't acknowledge the consul's heartbeat message).</li>
 * <li>session is explicitly destroyed.</li>
 *
 * @author Roman Levytskyi
 */
public class NodeManager {

    private static final Logger log = LoggerFactory.getLogger(NodeManager.class);
    private static final String TCP_CHECK_INTERVAL = "10s";
    private static final String NODE_COMMON_TAG = "vertx-clustering";


    private final Vertx vertx;
    private final ConsulClient consulClient;
    private final ConsulClientOptions consulClientOptions;
    private final NodeListener nodeListener;
    private final String nodeId;
    private final String checkId;
    private final String sessionName;

    private NetServer netServer;

    // dedicated cache to keep session id where node id is the key. Consul session id used to lock map entries.
    private final Map<String, String> sessionCache = new ConcurrentHashMap<>();
    // dedicated cache to initialize and keep haInfo.
    private final Map haInfoMap = new ConcurrentHashMap<>();
    // local cache of all vertx cluster nodes.
    private Set<String> nodes;

    public NodeManager(Vertx vertx,
                       ConsulClient consulClient,
                       ConsulClientOptions consulClientOptions,
                       NodeListener nodeListener,
                       String nodeId) {
        this.vertx = vertx;
        this.consulClient = consulClient;
        this.consulClientOptions = consulClientOptions;
        this.nodeListener = nodeListener;
        this.nodeId = nodeId;
        this.checkId = "tcpCheckFor-" + nodeId;
        this.sessionName = "sessionFor-" + nodeId;
        watchNewNodes().start();
        printLocalNodeMap();

    }

    /**
     * Asynchronously joins the vertx node with consul cluster.
     *
     * @param resultHandler - holds the result of async join operation.
     */
    public void join(Handler<AsyncResult<Void>> resultHandler) {
        getTcpAddress()
                .compose(this::createTcpServer)
                .compose(this::registerService)
                .compose(this::registerTcpCheck)
                .compose(aVoid -> registerSession())
                .compose(aVoid -> discoverNodes())
                .compose(aVoid -> initHaInfo())
                .setHandler(resultHandler);
    }

    /**
     * @return available nodes in cluster.
     */
    public List<String> getNodes() {
        return new ArrayList<>(nodes);
    }

    /**
     * @return session id - used to make consul map entries ephemeral.
     */
    public String getSessionId() {
        return sessionCache.get(nodeId);
    }

    /**
     * @param <K> represents key type (in haIfoMap it is a simple string)
     * @param <V> represents value type (in haIfoMap it is a simple string)
     * @return pre-initialized cache that is later used to build consul sync map.
     */
    public <K, V> Map<K, V> getHaInfo() {
        return haInfoMap;
    }

    /**
     * Listens for a new nodes within the cluster.
     */
    public Watch watchNewNodes() {
        // - tricky !!! watchers are always executed  within the event loop context !!!
        // - nodeAdded() call must NEVER be called within event loop context ???!!!.
        return Watch.services(vertx, consulClientOptions).setHandler(event -> {
            if (event.succeeded()) {
                vertx.executeBlocking(blockingEvent -> {
                    synchronized (this) {
                        ServiceList prevServiceList = event.prevResult();
                        ServiceList nextServiceList = event.nextResult();
                        // remove of the node (service) happens where next conditions are satisfied.



                        if (prevServiceList != null && prevServiceList.getList() != null) {
                            getNodeStream(prevServiceList.getList()).forEach(deletedNodeId -> {
                                log.trace("Node: '{}' has left the cluster.", deletedNodeId);
                                nodes.remove(deletedNodeId);
                                if (nodeListener != null) {
                                    log.trace("Removing : '{}' from local node listener.", deletedNodeId);
                                    nodeListener.nodeLeft(deletedNodeId);
                                }
                            });
                        }
                        // adding nodes to local cache.
                        if (event.nextResult() != null && event.nextResult().getList() != null) {
                            getNodeStream(prevServiceList.getList()).forEach(newNodeId -> {
                                log.trace("New node: '{}' has joined  the cluster.", newNodeId);
                                nodes.add(newNodeId);
                                if (nodeListener != null) {
                                    log.trace("Adding new node: '{}' to nodeListener.", newNodeId);
                                    nodeListener.nodeAdded(nodeId);
                                }
                            });

                        }
                        blockingEvent.complete();
                    }
                }, result -> {
                });
            } else {
                log.error("Couldn't register watcher for service: '{}'. Details: '{}'", nodeId, event.cause().getMessage());
            }
        });
    }

    /**
     * Filters out only the services tagged with NODE_COMMON_TAG - vertx nodes within the cluster.
     *
     * @param services holds all the services available within the consul cluster.
     * @return filtered stream by common tag containing vertx node ids.
     */
    private Stream<String> getNodeStream(List<Service> services) {
        // TODO: is filtering by this nodeId needed ?
        return services.stream().filter(service -> service.getTags().contains(NODE_COMMON_TAG)).map(Service::getName);
    }

    /**
     * Initializes haInfo map.
     *
     * @param <K> represents key type (in haIfoMap it is a simple string)
     * @param <V> represents value type (in haIfoMap it is a simple string)
     * @return completed future if haInfo is initialized successfully, failed future - otherwise.
     */
    private <K, V> Future<Void> initHaInfo() {
        log.trace("Initializing: '{}' internal cache ... ", ClusterManagerMaps.VERTX_HA_INFO.getName());
        Future<Void> futureHaInfoCache = Future.future();
        consulClient.getValues(ClusterManagerMaps.VERTX_HA_INFO.getName(), futureMap -> {
            if (futureMap.succeeded()) {
                if (futureMap.result().getList() != null) {
                    // TODO : is casting here sufficient here ???
                    Map<K, V> collectedMap = futureMap.result().getList().stream().collect(Collectors.toMap(o -> (K) o.getKey(), o -> (V) o.getValue()));
                    haInfoMap.putAll(collectedMap);
                    log.trace("'{}' internal cache is pre-built now: '{}'", ClusterManagerMaps.VERTX_HA_INFO.getName(), Json.encodePrettily(haInfoMap));
                } else {
                    log.trace("'{}' seems to be empty.", ClusterManagerMaps.VERTX_HA_INFO.getName());
                }
                futureHaInfoCache.complete();
            } else {
                log.trace("Can't initialize the : '{}' due to: '{}'", ClusterManagerMaps.VERTX_HA_INFO.getName(), futureMap.cause().toString());
                futureHaInfoCache.fail(futureMap.cause());
            }
        });
        return futureHaInfoCache;
    }

    /**
     * Gets the vertx node registered within consul cluster.
     *
     * @return completed future if vertx node has been successfully registered in consul cluster, failed future - otherwise.
     */
    private Future<TcpAddress> registerService(TcpAddress tcpAddress) {
        Future<TcpAddress> future = Future.future();
        ServiceOptions serviceOptions = new ServiceOptions();
        serviceOptions.setName(nodeId);
        serviceOptions.setTags(Arrays.asList(NODE_COMMON_TAG));
        serviceOptions.setId(nodeId);

        consulClient.registerService(serviceOptions, event -> {
            if (event.succeeded()) {
                log.trace("Node: {} has been registered.", nodeId);
                future.complete(tcpAddress);
            } else {
                log.error("Node: '{}' failed to register due to: '{}'", future.cause().toString());
                // closing the net server here.
                netServer.close();
                future.fail(event.cause());
            }
        });
        return future;
    }

    /**
     * Gets the vertx node de-registered from consul cluster.
     *
     * @return completed future if vertx node has been successfully de-registered from consul cluster, failed future - otherwise.
     */
    private Future<Void> deregisterService() {
        Future<Void> future = Future.future();
        consulClient.deregisterService(nodeId, event -> {
            if (event.succeeded()) {
                log.trace("'{}' has been unregistered.");
                future.complete();
            } else {
                log.trace("Couldn't unregister service: '{}' due to: '{}'", nodeId, event.cause().toString());
                future.fail(event.cause());
            }
        });
        return future;
    }

    /**
     * Gets the tcp check registered within consul.
     *
     * @param tcpAddress
     * @return completed future if tcp check has been successfully registered in consul cluster, failed future - otherwise.
     */
    private Future<Void> registerTcpCheck(TcpAddress tcpAddress) {
        Future<Void> future = Future.future();
        CheckOptions checkOptions = new CheckOptions()
                .setName(checkId)
                .setNotes("This check is dedicated to service with id :" + nodeId)
                .setId(checkId)
                .setTcp(tcpAddress.getHost() + ":" + tcpAddress.getPort())
                .setServiceId(nodeId)
                .setInterval(TCP_CHECK_INTERVAL)
                .setDeregisterAfter("10s") // it is still going to be 1 minute.
                .setStatus(CheckStatus.PASSING);

        consulClient.registerCheck(checkOptions, result -> {
            if (result.succeeded()) {
                log.trace("Check has been registered : '{}'", checkOptions.getId());
                future.complete();
            } else {
                log.trace("Can't register check: '{}' due to: '{}'", checkOptions.getId(), result.cause().toString());
                // try to de-register the node from consul cluster
                deregisterService();
                future.fail(result.cause());
            }
        });

        return future;
    }

    /**
     * Discovers nodes that are currently available within the cluster.
     *
     * @return completed future if nodes (consul services) have been successfully fetched from consul cluster,
     * failed future - otherwise.
     */
    private Future<Void> discoverNodes() {
        log.trace("Trying to fetch all the nodes that are available within the consul cluster.");
        Future<Void> futureNodes = Future.future();
        consulClient.catalogServices(result -> {
            if (result.succeeded()) {
                if (result.result().getList() != null) {
                    nodes = getNodeStream(result.result().getList()).collect(Collectors.toSet());
                }
                log.trace("List of fetched nodes is: '{}'", nodes);
                futureNodes.complete();
            } else {
                futureNodes.fail(result.cause());
            }
        });
        return futureNodes;
    }

    /**
     * Creates consul session. Consul session is used (in context of vertx cluster manager) to create ephemeral map entries.
     *
     * @return completed future if consul session (consul services) has been successfully registered in consul cluster,
     * failed future - otherwise.
     */
    private Future<Void> registerSession() {
        Future<Void> future = Future.future();
        SessionOptions sessionOptions = new SessionOptions()
                .setBehavior(SessionBehavior.DELETE)
                .setName(sessionName)
                .setChecks(Arrays.asList(checkId, "serfHealth"));

        consulClient.createSessionWithOptions(sessionOptions, session -> {
            if (session.succeeded()) {
                log.trace("Session : '{}' has been registered.", session.result());
                sessionCache.putIfAbsent(nodeId, session.result());
                future.complete();
            } else {
                log.error("Couldn't register the session due to: {}", session.cause().toString());
                future.fail(session.cause());
            }
        });
        return future;
    }

    /**
     * Creates simple tcp server used to receive heart beat messages from consul cluster.
     *
     * @param tcpAddress represents host and port of tcp server.
     * @return in case tcp server is created and it listens for heart beat messages -> future with tcp address, otherwise -> future with message
     * indicating the cause of the failure.
     */
    private Future<TcpAddress> createTcpServer(final TcpAddress tcpAddress) {
        Future<TcpAddress> future = Future.future();
        netServer = vertx.createNetServer(new NetServerOptions().setHost(tcpAddress.getHost()).setPort(tcpAddress.getPort()));
        netServer.connectHandler(event -> log.trace("Health heart beat message sent back to Consul"));
        netServer.listen(listenEvent -> {
            if (listenEvent.succeeded()) future.complete(tcpAddress);
            else future.fail(listenEvent.cause());
        });
        return future;
    }

    /**
     * @return tcp address that later on gets exposed to acknowledge heartbeats messages from consul (tcp checker sends them).
     */
    private Future<TcpAddress> getTcpAddress() {
        Future<TcpAddress> futureTcp = Future.future();
        try {
            int port = AvailablePortFinder.find(2000, 64000);
            futureTcp.complete(new TcpAddress(InetAddress.getLocalHost().getHostAddress(), port));
        } catch (UnknownHostException e) {
            log.error("Can't get the host address: '{}'", e.getCause().toString());
            futureTcp.fail(e);
        }
        return futureTcp;
    }

    /**
     * Simple representation of tcp address.
     */
    private final class TcpAddress {
        private final String host;
        private final int port;

        public TcpAddress(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            return "TcpAddress{" +
                    "host='" + host + '\'' +
                    ", port=" + port +
                    '}';
        }
    }

    private void printLocalNodeMap() {
        vertx.setPeriodic(10000, handler -> log.trace("Nodes are: '{}'", Json.encodePrettily(nodes)));
    }
}
