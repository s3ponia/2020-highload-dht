package ru.mail.polis.s3ponia;

import one.nio.http.HttpException;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.s3ponia.Table;
import ru.mail.polis.service.s3ponia.AsyncService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class AsyncServiceUtility {
    
    private AsyncServiceUtility() {
    }
    
    /**
     * Process upserting to dao.
     *
     * @param key     key for upserting
     * @param value   value for upserting
     * @param session HttpSession for response
     * @param dao dao to upsert
     * @throws IOException rethrow from sendResponse
     */
    public static void upsertWithTimeStamp(@NotNull final ByteBuffer key,
                                           @NotNull final ByteBuffer value,
                                           @NotNull final HttpSession session,
                                           final long timeStamp,
                                           @NotNull final DAO dao) throws IOException {
        try {
            dao.upsertWithTimeStamp(key, value, timeStamp);
            session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
        } catch (IOException ioException) {
            AsyncService.logger.error("IOException in putting key(size: {}), value(size: {}) from dao",
                    key.capacity(), value.capacity(), ioException);
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }
    
    
    public static void getRaw(@NotNull final ByteBuffer key,
                              @NotNull final HttpSession session,
                              @NotNull final DAO dao) throws IOException {
        try {
            final var val = dao.getRaw(key);
            final var resp = Response.ok(Utility.fromByteBuffer(val.getValue()));
            resp.addHeader(Utility.DEADFLAG_TIMESTAMP_HEADER + ": " + val.getDeadFlagTimeStamp());
            session.sendResponse(resp);
        } catch (NoSuchElementException noSuchElementException) {
            session.sendResponse(new Response(Response.NOT_FOUND, Response.EMPTY));
        } catch (IOException ioException) {
            AsyncService.logger.error("IOException in getting key(size: {}) from dao", key.capacity(), ioException);
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }
    
    /**
     * Get proxy response.
     * @param node destination node
     * @param request proxying request
     * @param service proxying service
     * @return Response from node
     */
    public static Response proxy(
            @NotNull final String node,
            @NotNull final Request request,
            @NotNull final AsyncService service) {
        try {
            request.addHeader(Utility.PROXY_HEADER + ":" + node);
            return service.getUrlToClient().get(node).invoke(request);
        } catch (IOException | InterruptedException | HttpException | PoolException exception) {
            return null;
        }
    }
    
    /**
     * Getting successful responses.
     * @param request request for proxying
     * @param configuration replication configuration
     * @param service AsyncService for proxying
     * @param nodes destination nodes
     * @return count of successful responses
     */
    public static int getCounter(@NotNull final Request request,
                                 @NotNull final Utility.ReplicationConfiguration configuration,
                                 @NotNull final AsyncService service,
                                 @NotNull final String... nodes) {
        final List<Future<Response>> futureResponses = getFutures(request, configuration, service, nodes);
        
        int acceptedCounter = 0;
        
        for (final var resp :
                futureResponses) {
            final Response response;
            try {
                response = resp.get();
            } catch (InterruptedException | ExecutionException e) {
                continue;
            }
            if (response != null
                        && (response.getStatus() == 202 /* ACCEPTED */ || response.getStatus() == 201 /* CREATED */)) {
                ++acceptedCounter;
            }
        }
        return acceptedCounter;
    }
    
    /**
     * GetFutures and GetValuesFromFutures in one step.
     * @param request request for GetFutures and GetValuesFromFutures
     * @param parsed parsed for GetFutures and GetValuesFromFutures
     * @param service service for GetFutures and GetValuesFromFutures
     * @param nodeReplicas nodeReplicas for GetFutures and GetValuesFromFutures
     * @return list of Table.Value
     */
    @NotNull
    public static List<Table.Value> getValues(@NotNull final Request request,
                                              @NotNull final Utility.ReplicationConfiguration parsed,
                                              @NotNull final AsyncService service,
                                              @NotNull final String... nodeReplicas) {
        final List<Future<Response>> futureResponses = getFutures(request, parsed, service, nodeReplicas);
        return Utility.getValuesFromFutures(parsed, futureResponses);
    }
    
    /**
     * Produce list of responses over proxy(node, request, service).
     * @param request request for proxy
     * @param configuration replication configuration
     * @param service AsyncService for proxying
     * @param nodes dest nodes
     * @return list of responses
     */
    @NotNull
    public static List<Future<Response>> getFutures(@NotNull final Request request,
                                                    @NotNull final Utility.ReplicationConfiguration configuration,
                                                    @NotNull final AsyncService service,
                                                    @NotNull final String... nodes) {
        final List<Future<Response>> futureResponses = new ArrayList<>(configuration.from);
        
        for (final var node :
                nodes) {
            
            if (!node.equals(service.getPolicy().homeNode())) {
                futureResponses.add(service.getEs().submit(() -> proxy(node, request, service)));
            }
        }
        return futureResponses;
    }
    
    /**
     * Execute sending response depend on accepted counter.
     * @param es ExecutorService for executing
     * @param configuration replication configuration
     * @param ackCounter counter of ack responses
     * @param resp response to send if ackCounter >= configuration.ack
     * @param session session for sending responses
     */
    public static void sendAckFromResp(@NotNull final ExecutorService es,
                                       @NotNull final Utility.ReplicationConfiguration configuration,
                                       final int ackCounter,
                                       final Response resp,
                                       @NotNull final HttpSession session) {
        if (ackCounter >= configuration.ack) {
            es.execute(
                    () -> {
                        try {
                            session.sendResponse(resp);
                        } catch (IOException ioException) {
                            AsyncService.logger.error("Error in sending resp", ioException);
                        }
                    }
            );
        } else {
            es.execute(
                    () -> {
                        try {
                            session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, AsyncService.EMPTY));
                        } catch (IOException ioException) {
                            AsyncService.logger.error("Error in sending error", ioException);
                        }
                    }
            );
        }
    }
    
    /**
     * Parsing replicas.
     *
     * @param replicas string for parsing
     * @param service  AsyncService with nodes
     * @return replication configuration
     */
    public static Utility.ReplicationConfiguration parseAndValidateReplicas(final String replicas,
                                                                            @NotNull final AsyncService service) {
        final Utility.ReplicationConfiguration parsedReplica;
        final var nodeCount = service.getPolicy().all().length;
        
        parsedReplica = replicas == null ? AsyncService.DEFAULT_CONFIGURATIONS.get(nodeCount - 1) :
                                Utility.ReplicationConfiguration.parse(replicas);
        
        if (parsedReplica == null || parsedReplica.ack <= 0
                    || parsedReplica.ack > parsedReplica.from || parsedReplica.from > nodeCount) {
            return null;
        }
        
        return parsedReplica;
    }
    
    /**
     * Combine parseAndValidateReplicas and handling error in 1 step.
     *
     * @param replicas string for replicas parsing
     * @param session  HttpSession for sending responses
     * @param service  AsyncService for parseAndValidateReplicas
     * @return replication configuration
     * @throws IOException rethrow IOException from session
     */
    @Nullable
    public static Utility.ReplicationConfiguration getReplicationConfiguration(
            @NotNull final String replicas,
            @NotNull final HttpSession session,
            @NotNull final AsyncService service) throws IOException {
        final var parsed = parseAndValidateReplicas(replicas, service);
        
        if (parsed == null) {
            AsyncService.logger.error("Bad replicas param {}", replicas);
            session.sendResponse(new Response(Response.BAD_REQUEST, AsyncService.EMPTY));
            return null;
        }
        return parsed;
    }
}