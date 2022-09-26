package ok.dht.test.nadutkin;

import jdk.incubator.foreign.MemorySegment;
import ok.dht.Service;
import ok.dht.ServiceConfig;
import ok.dht.test.ServiceFactory;
import ok.dht.test.nadutkin.database.BaseEntry;
import ok.dht.test.nadutkin.database.Config;
import ok.dht.test.nadutkin.database.Entry;
import ok.dht.test.nadutkin.database.impl.MemorySegmentDao;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
import one.nio.server.SelectorThread;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private final ServiceConfig config;
    private HttpServer server;
    private MemorySegmentDao dao;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
    }

    private byte[] getBytes(String message) {
        return message.getBytes(StandardCharsets.UTF_8);
    }
    @Override
    public CompletableFuture<?> start() throws IOException {
        long FLUSH_THRESHOLD_BYTES = 1_000_000_000;
        dao = new MemorySegmentDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        server = new HttpServer(createConfigFromPort(config.selfPort())) {
            @Override
            public void handleDefault(Request request, HttpSession session) throws IOException {
                Response response = new Response(Response.BAD_REQUEST, getBytes("Incorrect request path"));
                session.sendResponse(response);
            }

            @Override
            public synchronized void stop() {
                for (SelectorThread selector : selectors) {
                    for (Session session : selector.selector) {
                        session.close();
                    }
                }
                super.stop();
            }
        };
        server.addRequestHandlers(this);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> stop() {
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    private MemorySegment getKey(String id) {
        return MemorySegment.ofArray(getBytes(id));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, getBytes("Id can not be null or empty!"));
        } else {
            Entry<MemorySegment> value = dao.get(getKey(id));
            if (value == null) {
                return new Response(Response.NOT_FOUND, getBytes("Can't find any value, for id %1$s".formatted(id)));
            } else {
                return new Response(Response.OK, value.value().toByteArray());
            }
        }
    }

    private Response upsert(String id, MemorySegment value, String goodResponse) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, getBytes("Id can not be null or empty!"));
        } else {
            MemorySegment key = getKey(id);
            Entry<MemorySegment> entry = new BaseEntry<>(key, value);
            dao.upsert(entry);
            return new Response(goodResponse, Response.EMPTY);
        }
    }
    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param(value = "id", required = true) String id,
                        @Param(value = "request", required = true) Request request) {
        return upsert(id, MemorySegment.ofArray(request.getBody()), Response.CREATED);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) {
        return upsert(id, null, Response.ACCEPTED);
    }
    private static HttpServerConfig createConfigFromPort(int port) {
        HttpServerConfig httpConfig = new HttpServerConfig();
        AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        httpConfig.acceptors = new AcceptorConfig[]{acceptor};
        return httpConfig;
    }

    @ServiceFactory(stage = 1, week = 1, bonuses = {"SingleNodeTest#respectFileFolder"})
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
