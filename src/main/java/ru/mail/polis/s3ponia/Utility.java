package ru.mail.polis.s3ponia;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServerConfig;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.s3ponia.Value;
import ru.mail.polis.service.s3ponia.Header;
import ru.mail.polis.service.s3ponia.ReplicationConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class Utility {
    public static final String DEADFLAG_TIMESTAMP_HEADER = "XDeadFlagTimestamp";
    public static final String PROXY_HEADER = "X-Proxy-From";
    public static final String TIME_HEADER = "XTime";

    private Utility() {
    }

    public static boolean validateId(@NotNull final String id) {
        return !id.isEmpty();
    }

    public static ByteBuffer byteBufferFromString(@NotNull final String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks is homeNode in nodeReplicas.
     *
     * @param homeNode     homeNode
     * @param nodeReplicas array of replicas
     * @return is home in replicas
     */
    public static boolean isHomeInReplicas(@NotNull final String homeNode,
                                           @NotNull final String... nodeReplicas) {
        boolean homeInReplicas = false;

        for (final var node :
                nodeReplicas) {
            if (node.equals(homeNode)) {
                homeInReplicas = true;
                break;
            }
        }
        return homeInReplicas;
    }

    /**
     * Getting DeadFlagTimeStamp from Table.Value.
     *
     * @param response response
     * @return DeadFlagTimeStamp
     */
    private static long getDeadFlagTimeStamp(@NotNull final Response response) {
        final var header = Header.getHeader(DEADFLAG_TIMESTAMP_HEADER, response);
        assert header != null;
        return Long.parseLong(header.value);
    }

    /**
     * Get Future values and store to list.
     *
     * @param configuration replication configuration
     * @param responses     list of future responses
     * @return list of Table.Value
     */
    @NotNull
    public static List<Value> getValuesFromResponses(@NotNull final ReplicationConfiguration configuration,
                                                     @NotNull final List<Response> responses) {
        final List<Value> values = new ArrayList<>(configuration.replicas);
        for (final var resp :
                responses) {
            final Response response;
            response = resp;
            if (response != null && response.getStatus() == 200 /* OK */) {
                final var val = Value.of(ByteBuffer.wrap(response.getBody()),
                        getDeadFlagTimeStamp(response), -1);
                values.add(val);
            }
        }
        return values;
    }

    /**
     * Configuring http server from port.
     *
     * @param port http server's port
     * @return HttpServerConfig
     */
    @NotNull
    public static HttpServerConfig configFrom(final int port) {
        final AcceptorConfig ac = new AcceptorConfig();
        ac.port = port;

        final HttpServerConfig config = new HttpServerConfig();
        config.acceptors = new AcceptorConfig[1];
        config.acceptors[0] = ac;
        return config;
    }

    private static Response proxy(
            @NotNull final Request request,
            @NotNull final HttpClient client) {
        try {
            request.addHeader(Utility.PROXY_HEADER + ":" + "proxied");
            return client.invoke(request);
        } catch (IOException | InterruptedException | HttpException | PoolException exception) {
            return null;
        }
    }

    /**
     * Get list of success responses from nodes.
     *
     * @param request      proxying request
     * @param toHttpClient function for mapping url to HttpClient
     * @param skipNode     node that should be skipped
     * @param nodes        array of nodes for proxy dest
     * @return list of sucess responses
     */
    @NotNull
    public static List<Response> proxyReplicas(@NotNull final Request request,
                                               @NotNull final Function<String, HttpClient> toHttpClient,
                                               @NotNull final String skipNode,
                                               @NotNull final String... nodes) {
        final List<Response> futureResponses = new ArrayList<>(nodes.length);

        for (final var node : nodes) {

            if (!node.equals(skipNode)) {
                final var response = proxy(request, toHttpClient.apply(node));
                if (response == null) {
                    continue;
                }
                if (response.getStatus() != 202 /* ACCEPTED */
                        && response.getStatus() != 201 /* CREATED */
                        && response.getStatus() != 200 /* OK */
                        && response.getStatus() != 404 /* NOT FOUND */) {
                    continue;
                }
                futureResponses.add(response);
            }
        }
        return futureResponses;
    }

    /**
     * Make map String to HttpClient from nodes list.
     *
     * @param homeNode node ignored in nodes
     * @param nodes    list of nodes
     * @return map string to httpclient
     */
    public static Map<String, HttpClient> urltoClientFromSet(@NotNull final String homeNode,
                                                             @NotNull final String... nodes) {
        final Map<String, HttpClient> result = new HashMap<>();
        for (final var url : nodes) {
            if (url.equals(homeNode)) {
                continue;
            }
            if (result.put(url, new HttpClient(new ConnectionString(url))) != null) {
                throw new RuntimeException("Duplicated url in nodes.");
            }
        }
        return result;
    }

    /**
     * Make byte array from ByteBuffer.
     *
     * @param b ByteBuffer
     * @return byte array
     */
    @NotNull
    public static byte[] fromByteBuffer(@NotNull final ByteBuffer b) {
        final byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }
}
