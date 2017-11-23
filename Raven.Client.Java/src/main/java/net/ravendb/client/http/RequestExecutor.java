package net.ravendb.client.http;

import com.google.common.base.Stopwatch;
import net.ravendb.client.Constants;
import net.ravendb.client.documents.commands.GetStatisticsCommand;
import net.ravendb.client.documents.conventions.DocumentConventions;
import net.ravendb.client.documents.operations.configuration.GetClientConfigurationOperation;
import net.ravendb.client.documents.session.SessionInfo;
import net.ravendb.client.exceptions.AllTopologyNodesDownException;
import net.ravendb.client.exceptions.TimeoutException;
import net.ravendb.client.exceptions.database.DatabaseDoesNotExistException;
import net.ravendb.client.exceptions.ExceptionDispatcher;
import net.ravendb.client.exceptions.security.AuthorizationException;
import net.ravendb.client.extensions.HttpExtensions;
import net.ravendb.client.extensions.JsonExtensions;
import net.ravendb.client.primitives.*;
import net.ravendb.client.primitives.Timer;
import net.ravendb.client.serverwide.commands.GetTopologyCommand;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class RequestExecutor implements CleanCloseable {

    /**
     * Extension point to plug - in request post processing like adding proxy etc.
     */
    public static Consumer<HttpRequestBase> requestPostProcessor = null;

    public static final String CLIENT_VERSION = "4.0.0";

    private static final ConcurrentMap<String, CloseableHttpClient> globalHttpClient = new ConcurrentHashMap<>();

    private static final Duration GLOBAL_HTTP_CLIENT_TIMEOUT = Duration.ofHours(12);

    private final Semaphore _updateTopologySemaphore = new Semaphore(1);

    private final Semaphore _updateClientConfigurationSemaphore = new Semaphore(1);

    private final ConcurrentMap<ServerNode, NodeStatus> _failedNodesTimers = new ConcurrentHashMap<>();

    // TODO: public X509Certificate2 Certificate { get; }

    private final String _databaseName;

    private static final Log logger = LogFactory.getLog(RequestExecutor.class);

    private Date _lastReturnedResponse;
    protected final ReadBalanceBehavior _readBalanceBehavior;

    private final HttpCache cache = new HttpCache();

    public HttpCache getCache() {
        return cache;
    }

    public final ThreadLocal<AggressiveCacheOptions> AggressiveCaching = new ThreadLocal<>();

    public Topology getTopology() {
        return _nodeSelector != null ? _nodeSelector.getTopology() : null;
    }

    private CloseableHttpClient httpClient;

    public CloseableHttpClient getHttpClient() {
        return httpClient;
    }

    public List<ServerNode> getTopologyNodes() {
        return Optional.ofNullable(getTopology())
                .map(x -> x.getNodes())
                .map(x -> Collections.unmodifiableList(x))
                .orElse(null);
    }

    private Timer _updateTopologyTimer;

    protected NodeSelector _nodeSelector;

    private Duration _defaultTimeout;

    public AtomicLong numberOfServerRequests = new AtomicLong(0);

    public String getUrl() {
        if (_nodeSelector == null) {
            return null;
        }

        CurrentIndexAndNode preferredNode = _nodeSelector.getPreferredNode();

        return preferredNode != null ? preferredNode.currentNode.getUrl() : null;
    }

    protected long topologyEtag;

    public long getTopologyEtag() {
        return topologyEtag;
    }

    protected long clientConfigurationEtag;

    public long getClientConfigurationEtag() {
        return clientConfigurationEtag;
    }

    private final DocumentConventions conventions;

    protected boolean _disableTopologyUpdates;

    protected boolean _disableClientConfigurationUpdates;

    public DocumentConventions getConventions() {
        return conventions;
    }

    public Duration getDefaultTimeout() {
        return _defaultTimeout;
    }

    public void setDefaultTimeout(Duration defaultTimeout) {
        if (defaultTimeout != null && defaultTimeout.toMillis() > GLOBAL_HTTP_CLIENT_TIMEOUT.toMillis()) {
            throw new IllegalArgumentException("Maximum request timeout is set to " + GLOBAL_HTTP_CLIENT_TIMEOUT.toMillis() + " but was " + defaultTimeout.toMillis());
        }

        this._defaultTimeout = defaultTimeout;
    }

    protected RequestExecutor(String databaseName, DocumentConventions conventions) { //TBD: X509Certificate2 certificate
        _readBalanceBehavior = conventions.getReadBalanceBehavior();
        _databaseName = databaseName;
        // TBD: Certificate = certificate;

        _lastReturnedResponse = new Date();
        this.conventions = conventions.clone();

        String thumbprint = "";

        //TBD: if (certificate != null)    thumbprint = certificate.Thumbprint;

        httpClient = globalHttpClient.computeIfAbsent(thumbprint, (thumb) -> createClient());
    }

    public static RequestExecutor create(String[] urls, String databaseName, DocumentConventions conventions) { //TBD: X509Certificate2 certificate
        RequestExecutor executor = new RequestExecutor(databaseName, conventions);
        executor._firstTopologyUpdate = executor.firstTopologyUpdate(urls);
        return executor;
    }

    public static RequestExecutor createForSingleNodeWithConfigurationUpdates(String url, String databaseName, DocumentConventions conventions) { //TBD: X509Certificate2 certificate
        RequestExecutor executor = createForSingleNodeWithoutConfigurationUpdates(url, databaseName, conventions);
        executor._disableClientConfigurationUpdates = false;
        return executor;
    }

    public static RequestExecutor createForSingleNodeWithoutConfigurationUpdates(String url, String databaseName, DocumentConventions conventions) { //TBD: X509Certificate2 certificate
        final String[] initialUrls = validateUrls(new String[]{url});

        RequestExecutor executor = new RequestExecutor(databaseName, conventions);

        Topology topology = new Topology();
        topology.setEtag(-1L);

        ServerNode serverNode = new ServerNode();
        serverNode.setDatabase(databaseName);
        serverNode.setUrl(initialUrls[0]);
        topology.setNodes(Arrays.asList(serverNode));

        executor._nodeSelector = new NodeSelector(topology);
        executor.topologyEtag = -2;
        executor._disableTopologyUpdates = true;
        executor._disableClientConfigurationUpdates = true;

        return executor;
    }

    protected CompletableFuture<Void> updateClientConfigurationAsync() {
        if (_disposed) {
            CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                _updateClientConfigurationSemaphore.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            boolean oldDisableClientConfigurationUpdates = _disableClientConfigurationUpdates;
            _disableClientConfigurationUpdates = true;

            try {
                if (_disposed) {
                    return;
                }

                GetClientConfigurationOperation.GetClientConfigurationCommand command = new GetClientConfigurationOperation.GetClientConfigurationCommand();
                CurrentIndexAndNode currentIndexAndNode = chooseNodeForRequest(command, null);
                execute(currentIndexAndNode.currentNode, currentIndexAndNode.currentIndex, command, false, null);

                GetClientConfigurationOperation.Result result = command.getResult();
                if (result == null) {
                    return;
                }

                conventions.updateFrom(result.getConfiguration());
                clientConfigurationEtag = result.getEtag();

            } finally {
                _disableClientConfigurationUpdates = oldDisableClientConfigurationUpdates;
                _updateClientConfigurationSemaphore.release();
            }
        });
    }

    public CompletableFuture<Boolean> updateTopologyAsync(ServerNode node, int timeout) {
        return updateTopologyAsync(node, timeout, false);
    }

    public CompletableFuture<Boolean> updateTopologyAsync(ServerNode node, int timeout, boolean forceUpdate) {
        if (_disposed) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {

            //prevent double topology updates if execution takes too much time
            // --> in cases with transient issues
            try {
                boolean lockTaken = _updateTopologySemaphore.tryAcquire(timeout, TimeUnit.MILLISECONDS);
                if (!lockTaken) {
                    return false;
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            try {

                if (_disposed) {
                    return false;
                }

                GetTopologyCommand command = new GetTopologyCommand();
                execute(node, null, command, false, null);

                if (_nodeSelector == null) {
                    _nodeSelector = new NodeSelector(command.getResult());

                    if (_readBalanceBehavior == ReadBalanceBehavior.FASTEST_NODE) {
                        _nodeSelector.scheduleSpeedTest();
                    }
                } else if (_nodeSelector.onUpdateTopology(command.getResult(), forceUpdate)) {
                    disposeAllFailedNodesTimers();
                    if (_readBalanceBehavior == ReadBalanceBehavior.FASTEST_NODE) {
                        _nodeSelector.scheduleSpeedTest();
                    }
                }

                topologyEtag = _nodeSelector.getTopology().getEtag();

            } finally {
                _updateTopologySemaphore.release();
            }

            return true;
        });

    }

    protected void disposeAllFailedNodesTimers() {
        _failedNodesTimers.forEach((node, status) -> {
            status.close();
        });
        _failedNodesTimers.clear();
    }

    public <TResult> void execute(RavenCommand<TResult> command) {
        execute(command, null);
    }

    public <TResult> void execute(RavenCommand<TResult> command, SessionInfo sessionInfo) {
        CompletableFuture<Void> topologyUpdate = _firstTopologyUpdate;
        if (topologyUpdate != null && topologyUpdate.isDone() || _disableTopologyUpdates) {
            CurrentIndexAndNode currentIndexAndNode = chooseNodeForRequest(command, sessionInfo);
            execute(currentIndexAndNode.currentNode, currentIndexAndNode.currentIndex, command, true, sessionInfo);
            return;
        } else {
            unlikelyExecute(command, topologyUpdate, sessionInfo);
        }
    }

    public <TResult> CurrentIndexAndNode chooseNodeForRequest(RavenCommand<TResult> cmd, SessionInfo sessionInfo) {
        if (!cmd.isReadRequest()) {
            return _nodeSelector.getPreferredNode();
        }

        switch (_readBalanceBehavior) {
            case NONE:
                return _nodeSelector.getPreferredNode();
            case ROUND_ROBIN:
                return _nodeSelector.getNodeBySessionId(sessionInfo != null ? sessionInfo.getSessionId() : 0);
            case FASTEST_NODE:
                return _nodeSelector.getFastestNode();
            default:
                throw new IllegalArgumentException();
        }
    }

    private <TResult> void unlikelyExecute(RavenCommand<TResult> command, CompletableFuture<Void> topologyUpdate, SessionInfo sessionInfo) {
        try {
            if (topologyUpdate == null) {
                synchronized (this) {
                    if (_firstTopologyUpdate == null) {
                        if (_lastKnownUrls == null) {
                            throw new IllegalStateException("No known topology and no previously known one, cannot proceed, likely a bug");
                        }

                        _firstTopologyUpdate = firstTopologyUpdate(_lastKnownUrls);
                    }

                    topologyUpdate = _firstTopologyUpdate;
                }
            }

            topologyUpdate.get();
        } catch (InterruptedException | ExecutionException e) {
            synchronized (this) {
                if (_firstTopologyUpdate == topologyUpdate) {
                    _firstTopologyUpdate = null; // next request will raise it
                }
            }

            throw ExceptionsUtils.unwrapException(e);
        }

        CurrentIndexAndNode currentIndexAndNode = chooseNodeForRequest(command, sessionInfo);
        execute(currentIndexAndNode.currentNode, currentIndexAndNode.currentIndex, command, true, sessionInfo);
    }

    private void updateTopologyCallback() {
        Date time = new Date();
        if (time.getTime() - _lastReturnedResponse.getTime() <= Duration.ofMinutes(5).toMillis()) {
            return;
        }

        ServerNode serverNode;

        try {
            CurrentIndexAndNode preferredNode = _nodeSelector.getPreferredNode();
            serverNode = preferredNode.currentNode;
        } catch (Exception e) {
            if (logger.isInfoEnabled()) {
                logger.info("Couldn't get preferred node Topology from _updateTopologyTimer", e);
            }
            return;
        }

        updateTopologyAsync(serverNode, 0)
                .exceptionally(ex -> { //TODO: test me!
                    if (logger.isInfoEnabled()) {
                        logger.info("Couldn't update topology from _updateTopologyTimer", ex);
                    }
                    return null;
                });
    }

    protected CompletableFuture<Void> firstTopologyUpdate(String[] inputUrls) {
        final String[] initialUrls = validateUrls(inputUrls);

        ArrayList<Tuple<String, Exception>> list = new ArrayList<>();

        return CompletableFuture.runAsync(() -> {

            for (String url : initialUrls) {
                try {
                    ServerNode serverNode = new ServerNode();
                    serverNode.setUrl(url);
                    serverNode.setDatabase(_databaseName);

                    updateTopologyAsync(serverNode, Integer.MAX_VALUE).get();

                    initializeUpdateTopologyTimer();
                    return;
                } catch (Exception e) { //TBD: handle https
                    if (initialUrls.length == 0) {
                        _lastKnownUrls = initialUrls;
                        throw new IllegalStateException("Cannot get topology from server: " + url, e);
                    }

                    list.add(Tuple.create(url, e));
                }
            }

            Topology topology = new Topology();
            topology.setEtag(topologyEtag);

            List<ServerNode> topologyNodes = getTopologyNodes();
            if (topologyNodes == null) {
                topologyNodes = Arrays.stream(initialUrls)
                        .map(url -> {
                            ServerNode serverNode = new ServerNode();
                            serverNode.setUrl(url);
                            serverNode.setDatabase(_databaseName);
                            serverNode.setClusterTag("!");
                            return serverNode;
                        }).collect(Collectors.toList());
            }

            topology.setNodes(topologyNodes);

            _nodeSelector = new NodeSelector(topology);

            for (String url : initialUrls) {
                initializeUpdateTopologyTimer();
                return;
            }

            _lastKnownUrls = initialUrls;
            String details = list.stream().map(x -> x.first + " -> " + Optional.ofNullable(x.second).map(m -> m.getMessage()).orElse("")).collect(Collectors.joining(", "));
            throwExceptions(details);
        });
    }

    protected void throwExceptions(String details) {
        throw new IllegalStateException("Failed to retrieve database topology from all known nodes" + System.lineSeparator() + details);
    }

    protected static String[] validateUrls(String[] initialUrls) { //TBD: certificate
        String[] cleanUrls = new String[initialUrls.length];
        for (int index = 0; index < initialUrls.length; index++) {
            String url = initialUrls[index];
            try {
                new URL(url);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("The url '" + url + "' is not valid");
            }

            cleanUrls[index] = StringUtils.stripEnd(url, "/");
        }
        return cleanUrls;
    }

    private void initializeUpdateTopologyTimer() {
        if (_updateTopologyTimer != null) {
            return;
        }

        synchronized (this) {
            if (_updateTopologyTimer != null) {
                return;
            }

            _updateTopologyTimer = new Timer(this::updateTopologyCallback, Duration.ofMinutes(5), Duration.ofMinutes(5));
        }
    }

    public <TResult> void execute(ServerNode chosenNode, Integer nodeIndex, RavenCommand<TResult> command, boolean shouldRetry, SessionInfo sessionInfo) {
        Reference<String> urlRef = new Reference<>();
        HttpRequestBase request = createRequest(chosenNode, command, urlRef);

        Reference<String> cachedChangeVector = new Reference<>();
        Reference<String> cachedValue = new Reference<>();

        try (HttpCache.ReleaseCacheItem cachedItem = getFromCache(command, urlRef.value, cachedChangeVector, cachedValue)) {
            if (cachedChangeVector.value != null) {
                AggressiveCacheOptions aggressiveCacheOptions = AggressiveCaching.get();
                if (aggressiveCacheOptions != null &&
                        cachedItem.getAge().compareTo(aggressiveCacheOptions.getDuration()) < 0 &&
                        !cachedItem.getMightHaveBeenModified() &&
                        command.canCacheAggressively()) {
                    try {
                        command.setResponse(cachedValue.value, true);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return;
                }

                request.addHeader("If-None-Match", "\"" + cachedChangeVector.value + "\"");
            }

            if (!_disableClientConfigurationUpdates) {
                request.addHeader(Constants.Headers.CLIENT_CONFIGURATION_ETAG, "\"" + clientConfigurationEtag + "\"");
            }

            if (!_disableTopologyUpdates) {
                request.addHeader(Constants.Headers.TOPOLOGY_ETAG, "\"" + topologyEtag + "\"");
            }

            Stopwatch sp = Stopwatch.createStarted();
            CloseableHttpResponse response = null;
            ResponseDisposeHandling responseDispose = ResponseDisposeHandling.AUTOMATIC;

            try {
                numberOfServerRequests.incrementAndGet();

                Duration timeout = command.getTimeout() != null ? command.getTimeout() : _defaultTimeout;

                if (timeout != null) {
                    if (timeout.toMillis() > GLOBAL_HTTP_CLIENT_TIMEOUT.toMillis()) {
                        throwTimeoutTooLarnge(timeout);
                    }

                    try {
                        if (shouldExecuteOnAll(chosenNode, command)) {
                            response = executeOnAllToFigureOutTheFastest(chosenNode, command);
                        } else {
                            RequestConfig.Builder config = request.getConfig() != null ? RequestConfig.copy(request.getConfig()) : RequestConfig.custom();
                            config.setSocketTimeout((int)timeout.toMillis());
                            request.setConfig(config.build());
                            response = command.send(httpClient, request);
                        }
                    } catch (SocketTimeoutException e) { //TODO: check me!

                        TimeoutException timeoutException = new TimeoutException("The request for " + request.getURI() + " failed with theout after " + timeout, e);
                        if (!shouldRetry) {
                            throw timeoutException;
                        }

                        sp.stop();

                        if (!handleServerDown(urlRef.value, chosenNode, nodeIndex, command, request, response, e, sessionInfo)) {
                            throwFailedToContactAllNodes(command, request, e, timeoutException);
                        }
                        return;
                    }
                    //TODO: test me!
                } else {

                    if (shouldExecuteOnAll(chosenNode, command)) {
                        response = executeOnAllToFigureOutTheFastest(chosenNode, command);
                    } else {
                        response = command.send(httpClient, request);
                    }
                }

                sp.stop();
            } catch (IOException e) {
                if (!shouldRetry) {
                    throw ExceptionsUtils.unwrapException(e);
                }
                sp.stop();

                if (!handleServerDown(urlRef.value, chosenNode, nodeIndex, command, request, response, e, sessionInfo)) {
                    throwFailedToContactAllNodes(command, request, e, null);
                }
                return;
            }

            command.statusCode = response.getStatusLine().getStatusCode();

            Boolean refreshTopology = Optional.ofNullable(HttpExtensions.getBooleanHeader(response, Constants.Headers.REFRESH_TOPOLOGY)).orElse(false);
            Boolean refreshClientConfiguration = Optional.ofNullable(HttpExtensions.getBooleanHeader(response, Constants.Headers.REFRESH_CLIENT_CONFIGURATION)).orElse(false);

            try {
                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_MODIFIED) {
                    cachedItem.notModified();

                    try {
                        if (command.getResponseType() == RavenCommandResponseType.OBJECT) {
                            command.setResponse(cachedValue.value, true);
                        }
                    } catch (IOException e) {
                        throw ExceptionsUtils.unwrapException(e);
                    }

                    return;
                }

                if (response.getStatusLine().getStatusCode() >= 400) {
                    if (!handleUnsuccessfulResponse(chosenNode, nodeIndex, command, request, response, urlRef.value, sessionInfo, shouldRetry)) {
                        Header dbMissingHeader = response.getFirstHeader("Database-Missing");
                        if (dbMissingHeader != null && dbMissingHeader.getValue() != null) {
                            throw new DatabaseDoesNotExistException(dbMissingHeader.getValue());
                        }

                        if (command.getFailedNodes().size() == 0) { //precaution, should never happen at this point
                            throw new IllegalStateException("Received unsuccessful response and couldn't recover from it. Also, no record of exceptions per failed nodes. This is weird and should not happen.");
                        }

                        if (command.getFailedNodes().size() == 1) {
                            Collection<Exception> values = command.getFailedNodes().values();
                            values.stream().findFirst().ifPresent(v -> {
                                throw new RuntimeException(v);
                            });
                        }

                        throw new AllTopologyNodesDownException("Received unsuccessful response from all servers and couldn't recover from it.");
                    }
                    return; // we either handled this already in the unsuccessful response or we are throwing
                }

                responseDispose = command.processResponse(cache, response, urlRef.value);
                _lastReturnedResponse = new Date();
            } finally {
                if (responseDispose == ResponseDisposeHandling.AUTOMATIC) {
                    IOUtils.closeQuietly(response);
                }

                if (refreshTopology || refreshClientConfiguration) {

                    ServerNode serverNode = new ServerNode();
                    serverNode.setUrl(chosenNode.getUrl());
                    serverNode.setDatabase(_databaseName);

                    CompletableFuture<Boolean> topologyTask = refreshTopology ? updateTopologyAsync(serverNode, 0) : CompletableFuture.completedFuture(false);
                    CompletableFuture<Void> clientConfiguration = refreshClientConfiguration ? updateClientConfigurationAsync() : CompletableFuture.completedFuture(null);

                    try {
                        CompletableFuture.allOf(topologyTask, clientConfiguration).get();
                    } catch (Exception e) {
                        throw ExceptionsUtils.unwrapException(e);
                    }
                }
            }
        }
    }

    public void setTimeout(HttpRequestBase requestBase, long timeoutInMilis) {
        RequestConfig requestConfig = requestBase.getConfig();
        if (requestConfig == null) {
            requestConfig = RequestConfig.DEFAULT;
        }

        requestConfig = RequestConfig.copy(requestConfig).setSocketTimeout((int) timeoutInMilis).setConnectTimeout((int) timeoutInMilis).build();
        requestBase.setConfig(requestConfig);
    }

    private <TResult> void throwFailedToContactAllNodes(RavenCommand<TResult> command, HttpRequestBase request, Exception e, Exception timeoutException) {
        String message = "Tried to send " + command.resultClass.getName() + " request via " + request.getMethod() + " " + request.getURI() + " to all configured nodes in the topology, " +
                "all of them seem to be down or not responding. I've tried to access the following nodes: ";

        message += Optional.ofNullable(_nodeSelector).map(x -> x.getTopology().getNodes().stream().map(n -> n.getUrl()).collect(Collectors.joining(", "))).orElse("");

        throw new AllTopologyNodesDownException(message, timeoutException != null ? timeoutException : e);
    }

    public boolean inSpeedTestPhase() {
        return Optional.ofNullable(_nodeSelector).map(x -> x.inSpeedTestPhase()).orElse(false);
    }

    private <TResult> boolean shouldExecuteOnAll(ServerNode chosenNode, RavenCommand<TResult> command) {
        return _readBalanceBehavior == ReadBalanceBehavior.FASTEST_NODE &&
                _nodeSelector != null &&
                _nodeSelector.inSpeedTestPhase() &&
                Optional.ofNullable(_nodeSelector).map(x -> x.getTopology()).map(x -> x.getNodes()).map(x -> x.size() > 1).orElse(false) &&
                command.isReadRequest() &&
                command.getResponseType() == RavenCommandResponseType.OBJECT &&
                chosenNode != null;
    }

    private <TResult> CloseableHttpResponse executeOnAllToFigureOutTheFastest(ServerNode chosenNode, RavenCommand<TResult> command) {
        AtomicInteger numberOfFailedTasks = new AtomicInteger();

        CompletableFuture<IndexAndResponse> preferredTask = null;

        List<ServerNode> nodes = _nodeSelector.getTopology().getNodes();
        List<CompletableFuture<IndexAndResponse>> tasks = new ArrayList<>(Collections.nCopies(nodes.size(), null));

        for (int i = 0; i < nodes.size(); i++) {
            final int taskNumber = i;
            numberOfServerRequests.incrementAndGet();

            CompletableFuture<IndexAndResponse> task =  CompletableFuture.supplyAsync(() -> {
                try {
                    Reference<String> strRef = new Reference<>();
                    HttpRequestBase request = createRequest(nodes.get(taskNumber), command, strRef);
                    return new IndexAndResponse(taskNumber, command.send(httpClient, request));
                } catch (Exception e){
                    numberOfFailedTasks.incrementAndGet();
                    tasks.set(taskNumber, null);
                    throw new RuntimeException("Request execution failed", e);
                }
            });

            if (nodes.get(i).getClusterTag() == chosenNode.getClusterTag()) {
                preferredTask = task;
            } else {
                task.thenAcceptAsync(result -> {
                    IOUtils.closeQuietly(result.respose);
                });
            }

            tasks.set(i, task);
        }

        while (numberOfFailedTasks.get() < tasks.size()) {
            try {
                IndexAndResponse fastest = (IndexAndResponse) CompletableFuture.anyOf(tasks.stream().filter(x -> x != null).toArray(CompletableFuture[]::new)).get();
                _nodeSelector.recordFastest(fastest.index, nodes.get(fastest.index));
                break;
            } catch (InterruptedException | ExecutionException e) {
                for (int i = 0; i < nodes.size(); i++) {
                    if (tasks.get(i).isCompletedExceptionally()) {
                        numberOfFailedTasks.incrementAndGet();
                        tasks.set(i, null);
                    }
                }
            }
        }

        // we can reach here if the number of failed task equal to the nuber
        // of the nodes, in which case we have nothing to do

        try {
            return preferredTask.get().respose;
        } catch (InterruptedException | ExecutionException e) {
            throw ExceptionsUtils.unwrapException(e);
        }
    }

    private static void throwTimeoutTooLarnge(Duration duration) {
        throw new IllegalArgumentException("Maximum request timeout is set to " + GLOBAL_HTTP_CLIENT_TIMEOUT + " but was " + duration);
    }

    private <TResult> HttpCache.ReleaseCacheItem getFromCache(RavenCommand<TResult> command, String url, Reference<String> cachedChangeVector, Reference<String> cachedValue) {
        if (command.canCache() && command.isReadRequest() && command.getResponseType() == RavenCommandResponseType.OBJECT) {
            return cache.get(url, cachedChangeVector, cachedValue);
        }

        cachedChangeVector.value = null;
        cachedValue.value = null;
        return new HttpCache.ReleaseCacheItem();
    }

    private <TResult> HttpRequestBase createRequest(ServerNode node, RavenCommand<TResult> command, Reference<String> url) {
        try {
            HttpRequestBase request = command.createRequest(node, url);
            request.setURI(new URI(url.value));

            if (!request.containsHeader("Raven-Client-Version")) {
                request.addHeader("Raven-Client-Version", CLIENT_VERSION);
            }

            if (requestPostProcessor != null) {
                requestPostProcessor.accept(request);
            }

            return request;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unable to parse URL", e);
        }
    }

    private <TResult> boolean handleUnsuccessfulResponse(ServerNode chosenNode, Integer nodeIndex, RavenCommand<TResult> command, HttpRequestBase request, CloseableHttpResponse response, String url, SessionInfo sessionInfo, boolean shouldRetry) {
        try {
            switch (response.getStatusLine().getStatusCode()) {
                case HttpStatus.SC_NOT_FOUND:
                    cache.setNotFound(url);
                    if (command.getResponseType() == RavenCommandResponseType.EMPTY) {
                        return true;
                    } else if (command.getResponseType() == RavenCommandResponseType.OBJECT) {
                        command.setResponse(null, false);
                    } else {
                        command.setResponseRaw(response, null);
                    }
                    return true;

                case HttpStatus.SC_FORBIDDEN: //TBD: include info about certificates
                    throw new AuthorizationException("Forbidden access to " + chosenNode.getDatabase() + "@" + chosenNode.getUrl() + ", " + request.getMethod() + " " + request.getURI());
                case HttpStatus.SC_GONE: // request not relevant for the chosen node - the database has been moved to a different one
                    if (!shouldRetry) {
                        return false;
                    }

                    updateTopologyAsync(chosenNode, Integer.MAX_VALUE, true).get();

                    CurrentIndexAndNode currentIndexAndNode = chooseNodeForRequest(command, sessionInfo);
                    execute(currentIndexAndNode.currentNode, currentIndexAndNode.currentIndex, command, false, sessionInfo);
                    return true;
                case HttpStatus.SC_GATEWAY_TIMEOUT:
                case HttpStatus.SC_REQUEST_TIMEOUT:
                case HttpStatus.SC_BAD_GATEWAY:
                case HttpStatus.SC_SERVICE_UNAVAILABLE:
                    handleServerDown(url, chosenNode, nodeIndex, command, request, response, null, sessionInfo);
                    break;
                case HttpStatus.SC_CONFLICT:
                    handleConflict(response);
                    break;
                default:
                    command.onResponseFailure(response);
                    ExceptionDispatcher.throwException(response);
                    break;
            }
        } catch (IOException | ExecutionException | InterruptedException e) {
            throw ExceptionsUtils.unwrapException(e);
        }

        return false;
    }

    private static void handleConflict(CloseableHttpResponse response) {
        // current implementation is temporary

        ExceptionDispatcher.throwException(response);
    }

    public static InputStream readAsStream(CloseableHttpResponse response) throws IOException {
        return response.getEntity().getContent();
    }

    private <TResult> boolean handleServerDown(String url, ServerNode chosenNode, Integer nodeIndex, RavenCommand<TResult> command, HttpRequestBase request, CloseableHttpResponse response, Exception e, SessionInfo sessionInfo) {
        if (command.getFailedNodes() == null) {
            command.setFailedNodes(new HashMap<>());
        }

        addFailedResponseToCommand(chosenNode, command, request, response, e);

        if (nodeIndex == null) {
            //We executed request over a node not in the topology. This means no failover...
            return false;
        }

        spawnHealthChecks(chosenNode, nodeIndex);

        if (_nodeSelector == null) {
            return false;
        }

        _nodeSelector.onFailedRequest(nodeIndex);

        CurrentIndexAndNode currentIndexAndNode = _nodeSelector.getPreferredNode();
        if (command.getFailedNodes().containsKey(currentIndexAndNode.currentNode)) {
            return false; //we tried all the nodes...nothing left to do
        }

        execute(currentIndexAndNode.currentNode, currentIndexAndNode.currentIndex, command, false, sessionInfo);

        return true;
    }

    private void spawnHealthChecks(ServerNode chosenNode, int nodeIndex) {
        NodeStatus nodeStatus = new NodeStatus(this, nodeIndex, chosenNode);
        if (_failedNodesTimers.putIfAbsent(chosenNode, nodeStatus) == null) {
            nodeStatus.startTimer();
        }
    }

    private void checkNodeStatusCallback(NodeStatus nodeStatus) {
        List<ServerNode> copy = getTopologyNodes();

        if (nodeStatus.nodeIndex >= copy.size()) {
            return; // topology index changed / removed
        }

        ServerNode serverNode = copy.get(nodeStatus.nodeIndex);
        if (serverNode != nodeStatus.node) {
            return;  // topology changed, nothing to check
        }

        try {
            NodeStatus status;

            try {
                performHealthCheck(serverNode, nodeStatus.nodeIndex);
            } catch (Exception e) {
                if (logger.isInfoEnabled()) {
                    logger.info(serverNode.getClusterTag() + " is still down", e);
                }

                status = _failedNodesTimers.get(nodeStatus.node);
                if (status != null) {
                    nodeStatus.updateTimer();
                }

                return; // will wait for the next timer call
            }

            status = _failedNodesTimers.get(nodeStatus.node);
            if (status != null) {
                _failedNodesTimers.remove(status);
                status.close();
            }

            if (_nodeSelector != null) {
                _nodeSelector.restoreNodeIndex(nodeStatus.nodeIndex);
            }

        } catch (Exception e) {
            if (logger.isInfoEnabled()) {
                logger.info("Failed to check node topology, will ignore this node until next topology update", e);
            }
        }
    }

    protected void performHealthCheck(ServerNode serverNode, int nodeIndex) {
        execute(serverNode, nodeIndex, new GetStatisticsCommand("failure=check"), false, null);
    }

    //FIXME: make sure we dispose response in case of failure!
    private static <TResult> void addFailedResponseToCommand(ServerNode chosenNode, RavenCommand<TResult> command, HttpRequestBase request, CloseableHttpResponse response, Exception e) {
        //TODO: make sure we dispose response entity in this case !
        if (response != null && response.getEntity() != null) {
            String responseJson = null;
            try {
                responseJson = IOUtils.toString(response.getEntity().getContent(), "UTF-8");

                Exception readException = ExceptionDispatcher.get(JsonExtensions.getDefaultMapper().readValue(responseJson, ExceptionDispatcher.ExceptionSchema.class), response.getStatusLine().getStatusCode());
                command.getFailedNodes().put(chosenNode, readException);
            } catch (Exception _) {
                ExceptionDispatcher.ExceptionSchema exceptionSchema = new ExceptionDispatcher.ExceptionSchema();
                exceptionSchema.setUrl(request.getURI().toString());
                exceptionSchema.setMessage("Get unrecognized response from the server");
                exceptionSchema.setError(responseJson);
                exceptionSchema.setType("Unparsable Server Response");

                Exception exceptionToUse = ExceptionDispatcher.get(exceptionSchema, response.getStatusLine().getStatusCode());

                command.getFailedNodes().put(chosenNode, exceptionToUse);
            }

            return;
        }

        // this would be connections that didn't have response, such as "couldn't connect to remote server"
        ExceptionDispatcher.ExceptionSchema exceptionSchema = new ExceptionDispatcher.ExceptionSchema();
        exceptionSchema.setUrl(request.getURI().toString());
        exceptionSchema.setMessage(e.getMessage());
        exceptionSchema.setError(e.toString());
        exceptionSchema.setType(e.getClass().getCanonicalName());
        command.getFailedNodes().put(chosenNode, ExceptionDispatcher.get(exceptionSchema, HttpStatus.SC_INTERNAL_SERVER_ERROR));
    }

    protected boolean _disposed;
    protected CompletableFuture<Void> _firstTopologyUpdate;
    protected String[] _lastKnownUrls;

    @Override
    public void close() {
        if (_disposed) {
            return;
        }

        try {
            _updateTopologySemaphore.acquire();
        } catch (InterruptedException e) {
        }

        if (_disposed) {
            return;
        }

        _disposed = true;
        cache.close();

        if (_updateTopologyTimer != null) {
            _updateTopologyTimer.close();
        }
        
        disposeAllFailedNodesTimers();
    }

    private CloseableHttpClient createClient() {
        //TBD: certifciates handling, timeout: GlobalHttpClientTimeout?

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setDefaultMaxPerRoute(10);
        return HttpClients
                .custom()
                .setConnectionManager(cm)
                .disableContentCompression()
                //TODO : .addInterceptorLast(new RavenResponseContentEncoding())
                .setRetryHandler(new StandardHttpRequestRetryHandler(0, false))
                .setDefaultSocketConfig(SocketConfig.custom().setTcpNoDelay(true).build()).
                        build();
    }


    //TBD: ValidateClientKeyUsages

    public static class NodeStatus implements CleanCloseable {

        private Duration _timerPeriod;
        private final RequestExecutor _requestExectutor;
        public final int nodeIndex;
        public final ServerNode node;
        private Timer _timer;

        public NodeStatus(RequestExecutor requestExecutor, int nodeIndex, ServerNode node) {
            _requestExectutor = requestExecutor;
            this.nodeIndex = nodeIndex;
            this.node = node;
            _timerPeriod = Duration.ofMillis(100);
        }

        private Duration nextTimerPeriod() {
            if (_timerPeriod.compareTo(Duration.ofSeconds(5)) >= 0) {
                return Duration.ofSeconds(5);
            }

            _timerPeriod = _timerPeriod.plus(Duration.ofMillis(100));

            return _timerPeriod;
        }

        public void startTimer() {
            _timer = new Timer(this::timerCallback, _timerPeriod);
        }

        public void updateTimer() {
            _timer.change(nextTimerPeriod());
        }

        private void timerCallback() {
            if (!_requestExectutor._disposed) {
                _requestExectutor.checkNodeStatusCallback(this);
            }
        }

        @Override
        public void close() {
            _timer.close();
        }
    }

    public CurrentIndexAndNode getPreferredNode() {
        ensureNodeSelector();

        return _nodeSelector.getPreferredNode();
    }

    public CurrentIndexAndNode getNodeBySessionId(int sessionId) {
        ensureNodeSelector();

        return _nodeSelector.getNodeBySessionId(sessionId);
    }

    public CurrentIndexAndNode getFastestNode() {
        ensureNodeSelector();

        return _nodeSelector.getFastestNode();
    }

    private void ensureNodeSelector() {
        if (_firstTopologyUpdate != null && !_firstTopologyUpdate.isDone()) {
            ExceptionsUtils.accept(() -> _firstTopologyUpdate.get());
        }

        if (_nodeSelector == null) {
            Topology topology = new Topology();

            topology.setNodes(getTopologyNodes());
            topology.setEtag(topologyEtag);

            _nodeSelector = new NodeSelector(topology);
        }
    }

    public static class IndexAndResponse {
        public int index;
        public CloseableHttpResponse respose;

        public IndexAndResponse(int index, CloseableHttpResponse respose) {
            this.index = index;
            this.respose = respose;
        }
    }
}
