// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The server of com.sun.net.httpserver. It serves through the transport of the target
// (the host's http-serve, which the lowered program itself calls, so every transport a
// served application has carries it): open0 binds and hands the transport the function
// it calls for each request, which is answer() below. A request is a task the server's
// executor is handed, and the thread the transport called on goes on; with no executor
// that thread runs it, one request at a time. An exchange lives until its response
// ended, whoever ends it: its handler, or any thread once the handler returned. It
// answers through the transport's responder: a whole response once it is complete, or,
// for a body of a length not told ahead, the head and then each chunk as it is written.
// What a JDK's server was seen to do with a request is written here over that: the
// context a path finds, the filters and the authenticator before a handler, the
// refusals of an exchange, the words of each.
package rontolisp.internal;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Request;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public final class HttpServing {

    private HttpServing() {
    }

    /** The server bound and not stopped: a program serves through one. */
    private static Server bound;

    /** The server bound last, stopped or not: what the program's end waits for. */
    private static Server last;

    public static HttpServer server() {
        return new Server();
    }

    /** Fields nothing changes: a request's, and what Headers.of makes. */
    public static Headers fixed(Headers fields) {
        return new Fixed(fields);
    }

    /**
     * The end of a program: while a server it started runs, the program does. Where the
     * target has one thread the requests are served here.
     */
    public static void serve() {
        Server server = last;
        if (server != null && server.started) {
            run0(server.handle);
        }
    }

    /** A request with one more field, unless it has a field of that name. */
    public static Request with(Request request, String name, List<String> values) {
        if (name == null || values == null) {
            throw new NullPointerException();
        }
        return new Extended(request, name, values);
    }

    /** An exchange a handler reads the request of as a filter adapted it. */
    public static HttpExchange adapted(HttpExchange exchange, Request request) {
        return new Adapted(exchange, request);
    }

    /**
     * One request: the transport's environment, and the responder its response goes
     * through. The executor is handed the request and the thread the transport called
     * on goes on, as a JDK's does; with no executor that thread runs the request itself.
     */
    static void answer(Object served, Object env, Object responder) {
        ((Server) served).dispatch(env, responder);
    }

    /** One request as the executor runs it. */
    private static final class Task implements Runnable {

        private final Server server;

        private final Object env;

        private final Object responder;

        private Exchange exchange;

        Task(Server server, Object env, Object responder) {
            this.server = server;
            this.env = env;
            this.responder = responder;
        }

        public void run() {
            work();
        }

        /**
         * Runs the handler. The exchange outlives it unless the response ended: whoever
         * holds the exchange answers it afterwards. A handler that throws has a JDK
         * close the connection; here a body whose head went out in chunks ends short
         * and any other response not ended is a 500.
         */
        void work() {
            try {
                handle();
            }
            catch (Throwable thrown) {
                if (exchange == null) {
                    refuse();
                }
                else {
                    exchange.failed(thrown);
                }
            }
        }

        /** The 500 of a request refused before it reached a handler. */
        void refuse() {
            if (exchange == null) {
                page(500, null);
            }
        }

        /** A response of the server's own, before any handler: the request is out of hand. */
        private void page(int status, String text) {
            if (text == null) {
                respond0(responder, status, new String[0], new byte[0]);
            }
            else {
                respond0(responder, status, new String[] { "Content-Type", "text/html" },
                        text.getBytes(StandardCharsets.UTF_8));
            }
            server.gave();
        }

        private void handle() throws IOException {
            URI uri;
            try {
                uri = new URI(target0(env));
            }
            catch (Exception refused) {
                page(400, "<h1>400 Bad Request</h1>URISyntaxException thrown");
                return;
            }
            Context context = server.find(uri.getPath());
            if (context == null) {
                page(404, "<h1>404 Not Found</h1>No context found for request");
                return;
            }
            HttpHandler handler = context.handler;
            if (handler == null) {
                page(500, "<h1>500 Internal Server Error</h1>No handler for context");
                return;
            }
            exchange = new Exchange(server, context, env, responder, uri);
            Guarded guarded = context.guarded;
            if (guarded != null) {
                guarded.handle(exchange);
            }
            else {
                handler.handle(exchange);
            }
        }
    }

    /**
     * What a context given a filter or an authenticator runs before its handler: the
     * filters in their order, then the authenticator. Made by such a context alone.
     */
    private static final class Guarded implements HttpHandler {

        final Context context;

        final ArrayList<Filter> filters = new ArrayList<>();

        Authenticator authenticator;

        Guarded(Context context) {
            this.context = context;
        }

        public void handle(HttpExchange exchange) throws IOException {
            new Filter.Chain(filters, this::authenticated).doFilter(exchange);
        }

        private void authenticated(HttpExchange exchange) throws IOException {
            Authenticator asked = authenticator;
            if (asked != null) {
                Authenticator.Result result = asked.authenticate(exchange);
                if (result instanceof Authenticator.Failure) {
                    exchange.sendResponseHeaders(((Authenticator.Failure) result).getResponseCode(), -1);
                    return;
                }
                if (result instanceof Authenticator.Retry) {
                    exchange.sendResponseHeaders(((Authenticator.Retry) result).getResponseCode(), -1);
                    return;
                }
                HttpPrincipal principal = ((Authenticator.Success) result).getPrincipal();
                HttpExchange under = exchange;
                while (under instanceof Adapted) {
                    under = ((Adapted) under).exchange;
                }
                if (under instanceof Exchange) {
                    ((Exchange) under).principal = principal;
                }
            }
            context.handler.handle(exchange);
        }
    }

    private static IOException failure(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("address already in use") || lower.contains("address in use")) {
            return new BindException("Address already in use");
        }
        if (lower.contains("requires --component")) {
            // the words of the transport a plain Preview 1 module lacks
            return new IOException("an HttpServer needs a transport: compile with --component, for `wasmtime serve`,"
                    + " or with --native; a Preview 1 .wasm cannot listen");
        }
        return new IOException(text);
    }

    private static final class Server extends HttpServer {

        InetSocketAddress address;

        Executor executor;

        final ArrayList<Context> contexts = new ArrayList<>();

        /** The transport's server, once bound. */
        Object handle;

        final Object gate = gate0();

        /** Held by the thread that hands the executor a request: one hands them all. */
        final Turn turn = new Turn();

        boolean started;

        boolean stopped;

        /** The requests in hand: taken from the transport, their response not ended. */
        private int inHand;

        private boolean closed;

        /** What getExecutor() answers of a started server given none. */
        private Executor own;

        /**
         * Takes a request from the transport: runs it on this thread when the server has
         * no executor -- a JDK's own thread runs the handlers of such a server, one at a
         * time --, else hands the executor the task, and goes on.
         */
        void dispatch(Object env, Object responder) {
            if (!admitted0(gate)) {
                respond0(responder, 503, new String[0], new byte[0]);
                return;
            }
            Task task = new Task(this, env, responder);
            synchronized (this) {
                inHand++;
            }
            Executor handing = executor;
            if (handing == null) {
                synchronized (turn) {
                    task.work();
                }
                return;
            }
            try {
                // one thread of a JDK's hands the executor every request
                synchronized (turn) {
                    handing.execute(task);
                }
            }
            catch (RuntimeException refused) {
                // an executor that takes no task: a JDK closes the connection
                task.refuse();
            }
        }

        /** A request's response ended: it is out of hand. */
        synchronized void gave() {
            inHand--;
            notifyAll();
        }

        public void bind(InetSocketAddress addr, int backlog) throws IOException {
            if (addr == null) {
                throw new NullPointerException("null address");
            }
            if (address != null) {
                throw new BindException("HttpServer already bound");
            }
            if (bound != null) {
                throw new UnsupportedOperationException("a second HttpServer bound while one is is not supported by"
                        + " the experimental Java subset: a program serves through one server");
            }
            String host = addr.getAddress() != null && addr.getAddress().isAnyLocalAddress() ? null
                    : addr.getHostString();
            shared0();
            Object opened = open0(this, addr.getPort(), host);
            if (failed0(opened)) {
                throw failure(text0(opened));
            }
            handle = opened;
            int port = port0(opened);
            address = port <= 0 || port == addr.getPort() ? addr
                    : addr.isUnresolved() ? InetSocketAddress.createUnresolved(addr.getHostString(), port)
                            : new InetSocketAddress(addr.getAddress(), port);
            bound = this;
            last = this;
        }

        public void start() {
            if (address == null || started || stopped) {
                throw new IllegalStateException("server in wrong state");
            }
            started = true;
            turn0(gate, handle, true);
        }

        public void setExecutor(Executor executor) {
            if (started) {
                throw new IllegalStateException("server already started");
            }
            this.executor = executor;
        }

        public Executor getExecutor() {
            if (executor == null && started) {
                // a started server given none has one of its own
                if (own == null) {
                    own = new Direct();
                }
                return own;
            }
            return executor;
        }

        /**
         * No request is taken from here on, and the transport's server closes once no
         * request is in hand -- the responses it is still writing go out first -- or once
         * the delay has passed: a JDK waits so long for the exchanges in hand, the
         * caller's own among them, and then closes every connection.
         */
        public void stop(int delay) {
            if (delay < 0) {
                throw new IllegalArgumentException("negative delay parameter");
            }
            boolean close;
            boolean answered;
            synchronized (this) {
                if (stopped) {
                    return;
                }
                stopped = true;
                if (address == null) {
                    return;
                }
                if (bound == this) {
                    bound = null;
                }
                turn0(gate, handle, false);
                long deadline = System.nanoTime() + delay * 1000000000L;
                while (inHand > 0) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        wait((left + 999999L) / 1000000L);
                    }
                    catch (InterruptedException interrupted) {
                        break;
                    }
                }
                answered = inHand == 0;
                close = !closed;
                closed = true;
            }
            if (close) {
                close0(handle, answered);
            }
        }

        public HttpContext createContext(String path, HttpHandler handler) {
            if (path == null || handler == null) {
                throw new NullPointerException("null handler, or path parameter");
            }
            return made(path, handler);
        }

        public HttpContext createContext(String path) {
            if (path == null) {
                throw new NullPointerException("null handler, or path parameter");
            }
            return made(path, null);
        }

        private HttpContext made(String path, HttpHandler handler) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Illegal value for path or protocol");
            }
            for (Context context : contexts) {
                if (context.path.equals(path)) {
                    throw new IllegalArgumentException("cannot add context to list");
                }
            }
            Context context = new Context(this, path, handler);
            contexts.add(context);
            return context;
        }

        public void removeContext(String path) throws IllegalArgumentException {
            if (path == null) {
                throw new NullPointerException("null path parameter");
            }
            for (int i = 0; i < contexts.size(); i++) {
                if (contexts.get(i).path.equals(path)) {
                    contexts.remove(i);
                    return;
                }
            }
            throw new IllegalArgumentException("cannot remove element from list");
        }

        public void removeContext(HttpContext context) {
            if (!(context instanceof Context)) {
                throw new IllegalArgumentException("wrong HttpContext type");
            }
            removeContext(context.getPath());
        }

        public InetSocketAddress getAddress() {
            return address;
        }

        /** The context whose path a request's path starts with: the longest such. */
        Context find(String path) {
            Context found = null;
            if (path != null) {
                for (Context context : contexts) {
                    if (path.startsWith(context.path)
                            && (found == null || context.path.length() > found.path.length())) {
                        found = context;
                    }
                }
            }
            return found;
        }
    }

    private static final class Context extends HttpContext {

        final Server server;

        final String path;

        HttpHandler handler;

        final HashMap<String, Object> attributes = new HashMap<>();

        /** The filters and the authenticator, once the context was given either. */
        Guarded guarded;

        Context(Server server, String path, HttpHandler handler) {
            this.server = server;
            this.path = path;
            this.handler = handler;
        }

        public HttpHandler getHandler() {
            return handler;
        }

        public void setHandler(HttpHandler handler) {
            if (handler == null) {
                throw new NullPointerException();
            }
            if (this.handler != null) {
                throw new IllegalArgumentException("handler already set");
            }
            this.handler = handler;
        }

        public String getPath() {
            return path;
        }

        public HttpServer getServer() {
            return server;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        private Guarded guarded() {
            if (guarded == null) {
                guarded = new Guarded(this);
            }
            return guarded;
        }

        public List<Filter> getFilters() {
            return guarded().filters;
        }

        public Authenticator setAuthenticator(Authenticator auth) {
            Guarded held = guarded();
            Authenticator was = held.authenticator;
            held.authenticator = auth;
            return was;
        }

        public Authenticator getAuthenticator() {
            return guarded == null ? null : guarded.authenticator;
        }
    }

    /** What a server's one handing thread holds: no lock of a program's is one. */
    private static final class Turn {
    }

    /** The executor of a started server given none: the calling thread runs the task. */
    private static final class Direct implements Executor {

        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class Exchange extends HttpExchange {

        final Server server;

        final Context context;

        final Object env;

        final Object responder;

        final URI uri;

        final String method;

        Headers requested;

        final Headers responding = new Headers();

        InputStream in;

        OutputStream out;

        int code = -1;

        /** What is left to write of a body whose length was told; -1 for one in chunks. */
        long left;

        /** The body of a told length, kept until all of it is written. */
        ByteArrayOutputStream kept;

        /** The transport's writer of a body in chunks. */
        Object writer;

        /** Whether the body's end was reached: nothing more may be written. */
        boolean ended;

        /** Whether the response went to the transport whole, or failed: it is out of hand. */
        private boolean over;

        boolean closed;

        /** Who the context's authenticator found the request to be from. */
        HttpPrincipal principal;

        Exchange(Server server, Context context, Object env, Object responder, URI uri) {
            this.server = server;
            this.context = context;
            this.env = env;
            this.responder = responder;
            this.uri = uri;
            this.method = method0(env);
        }

        public Headers getRequestHeaders() {
            if (requested == null) {
                String[] flat = fields0(env);
                Headers fields = new Headers();
                for (int i = 0; i < flat.length; i += 2) {
                    fields.add(flat[i], flat[i + 1]);
                }
                requested = new Fixed(fields);
            }
            return requested;
        }

        public Headers getResponseHeaders() {
            return responding;
        }

        public URI getRequestURI() {
            return uri;
        }

        public String getRequestMethod() {
            return method;
        }

        public HttpContext getHttpContext() {
            return context;
        }

        public InputStream getRequestBody() {
            if (in == null) {
                in = new Input(body0(env));
            }
            return in;
        }

        public OutputStream getResponseBody() {
            if (out == null) {
                out = new Output(this);
            }
            return out;
        }

        public void setStreams(InputStream i, OutputStream o) {
            if (i != null) {
                in = i;
            }
            if (o != null) {
                out = o;
            }
        }

        private String[] fields() {
            ArrayList<String> flat = new ArrayList<>();
            for (Map.Entry<String, List<String>> field : responding.entrySet()) {
                for (String value : field.getValue()) {
                    flat.add(field.getKey());
                    flat.add(value);
                }
            }
            return flat.toArray(new String[0]);
        }

        public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
            if (code != -1) {
                throw new IOException("headers already sent");
            }
            if (responseLength < -1) {
                throw new IllegalArgumentException("Content-Length: " + responseLength);
            }
            code = rCode;
            boolean bodiless = responseLength == -1 || (rCode >= 100 && rCode < 200) || rCode == 204 || rCode == 304
                    || method.equals("HEAD");
            if (bodiless) {
                ended = true;
                respond0(responder, rCode, fields(), new byte[0]);
                done();
            }
            else if (responseLength == 0) {
                left = -1;
                writer = stream0(responder, rCode, fields());
            }
            else {
                left = responseLength;
                kept = new ByteArrayOutputStream();
            }
        }

        /**
         * The response went to the transport whole, or failed: the exchange is out of hand.
         * One thread answers an exchange at a time, as a JDK's.
         */
        void done() {
            if (!over) {
                over = true;
                server.gave();
            }
        }

        /**
         * What the handler threw, its response not ended: a JDK closes the connection.
         * Here a body whose head went out in chunks ends short, the transport told what
         * was thrown, and any other response is a 500.
         */
        void failed(Throwable thrown) {
            if (over) {
                return;
            }
            boolean cut = writer != null && !ended;
            ended = true;
            closed = true;
            if (cut) {
                cut0(writer, thrown.toString());
            }
            else {
                respond0(responder, 500, new String[0], new byte[0]);
            }
            done();
        }

        void write(byte[] b, int off, int len) throws IOException {
            if (code == -1) {
                throw new IOException("response headers not sent yet");
            }
            if (ended) {
                throw new IOException("stream closed");
            }
            if (len == 0) {
                return;
            }
            if (left < 0) {
                byte[] chunk = new byte[len];
                System.arraycopy(b, off, chunk, 0, len);
                write0(writer, chunk);
                return;
            }
            if (len > left) {
                throw new IOException("too many bytes to write to stream");
            }
            kept.write(b, off, len);
            left -= len;
            if (left == 0) {
                // all of it is written: the response is whole, as it is on a JDK's wire
                ended = true;
                respond0(responder, code, fields(), kept.toByteArray());
                done();
            }
        }

        void end() throws IOException {
            if (code == -1 || ended) {
                return;
            }
            ended = true;
            if (left < 0) {
                finish0(writer);
                done();
            }
            else {
                throw new IOException("insufficient bytes written to stream");
            }
        }

        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (in instanceof Input) {
                ((Input) in).closed = true;
            }
            try {
                if (out != null && !(out instanceof Output)) {
                    out.close();
                }
                end();
            }
            catch (IOException unfinished) {
                // a body cut short: a JDK closes the connection
            }
            if (!over) {
                // no response, or a body cut short: a JDK closes the connection with
                // nothing sent; here every request closed is answered
                respond0(responder, 500, new String[0], new byte[0]);
                done();
            }
        }

        private InetSocketAddress end(boolean local) {
            Object[] end = end0(env, local);
            String host = (String) end[0];
            Integer port = (Integer) end[1];
            InetSocketAddress own = server.address;
            if (host == null || (local && new InetSocketAddress(host, 0).isUnresolved())) {
                return local ? new InetSocketAddress(own.getAddress(), port == null ? own.getPort() : port)
                        : new InetSocketAddress(port == null ? 0 : port);
            }
            return new InetSocketAddress(host, port == null ? 0 : port);
        }

        public InetSocketAddress getRemoteAddress() {
            return end(false);
        }

        public InetSocketAddress getLocalAddress() {
            return end(true);
        }

        public int getResponseCode() {
            return code;
        }

        public String getProtocol() {
            return protocol0(env);
        }

        public HttpPrincipal getPrincipal() {
            return principal;
        }

        public Object getAttribute(String name) {
            if (name == null) {
                throw new NullPointerException("null name parameter");
            }
            return context.attributes.get(name);
        }

        public void setAttribute(String name, Object value) {
            if (name == null) {
                throw new NullPointerException("null name parameter");
            }
            if (value == null) {
                context.attributes.remove(name);
            }
            else {
                context.attributes.put(name, value);
            }
        }
    }

    /** A request and one more field. */
    private static final class Extended implements Request {

        private final Request request;

        private final Headers fields;

        Extended(Request request, String name, List<String> values) {
            this.request = request;
            Headers all = new Headers(request.getRequestHeaders());
            if (!all.containsKey(name)) {
                all.put(name, new ArrayList<String>(values));
            }
            this.fields = new Fixed(all);
        }

        public URI getRequestURI() {
            return request.getRequestURI();
        }

        public String getRequestMethod() {
            return request.getRequestMethod();
        }

        public Headers getRequestHeaders() {
            return fields;
        }
    }

    /** An exchange whose request is read as a filter adapted it; all else is the exchange's. */
    private static final class Adapted extends HttpExchange {

        final HttpExchange exchange;

        private final Request request;

        Adapted(HttpExchange exchange, Request request) {
            this.exchange = exchange;
            this.request = request;
        }

        public Headers getRequestHeaders() {
            return request.getRequestHeaders();
        }

        public URI getRequestURI() {
            return request.getRequestURI();
        }

        public String getRequestMethod() {
            return request.getRequestMethod();
        }

        public Headers getResponseHeaders() {
            return exchange.getResponseHeaders();
        }

        public HttpContext getHttpContext() {
            return exchange.getHttpContext();
        }

        public void close() {
            exchange.close();
        }

        public InputStream getRequestBody() {
            return exchange.getRequestBody();
        }

        public OutputStream getResponseBody() {
            return exchange.getResponseBody();
        }

        public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
            exchange.sendResponseHeaders(rCode, responseLength);
        }

        public InetSocketAddress getRemoteAddress() {
            return exchange.getRemoteAddress();
        }

        public int getResponseCode() {
            return exchange.getResponseCode();
        }

        public InetSocketAddress getLocalAddress() {
            return exchange.getLocalAddress();
        }

        public String getProtocol() {
            return exchange.getProtocol();
        }

        public Object getAttribute(String name) {
            return exchange.getAttribute(name);
        }

        public void setAttribute(String name, Object value) {
            exchange.setAttribute(name, value);
        }

        public void setStreams(InputStream i, OutputStream o) {
            exchange.setStreams(i, o);
        }

        public HttpPrincipal getPrincipal() {
            return exchange.getPrincipal();
        }
    }

    /** A request's body: its octets, all of them in before the handler runs. */
    private static final class Input extends InputStream {

        private final byte[] octets;

        private int at;

        boolean closed;

        Input(byte[] octets) {
            this.octets = octets;
        }

        public int read() throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            return at < octets.length ? octets[at++] & 0xff : -1;
        }

        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            if (at >= octets.length) {
                return -1;
            }
            int count = Math.min(len, octets.length - at);
            System.arraycopy(octets, at, b, off, count);
            at += count;
            return count;
        }

        public int available() throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            return octets.length - at;
        }

        public void close() {
            closed = true;
        }
    }

    /** A response's body. */
    private static final class Output extends OutputStream {

        private final Exchange exchange;

        Output(Exchange exchange) {
            this.exchange = exchange;
        }

        public void write(int b) throws IOException {
            exchange.write(new byte[] { (byte) b }, 0, 1);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            }
            exchange.write(b, off, len);
        }

        public void close() throws IOException {
            exchange.end();
        }
    }

    /** Fields nothing changes. */
    private static final class Fixed extends Headers {

        Fixed(Headers fields) {
            for (Map.Entry<String, List<String>> field : fields.entrySet()) {
                super.put(field.getKey(), Collections.unmodifiableList(new ArrayList<String>(field.getValue())));
            }
        }

        private static UnsupportedOperationException refused() {
            return new UnsupportedOperationException("unsupported operation");
        }

        public List<String> put(String key, List<String> value) {
            throw refused();
        }

        public void add(String key, String value) {
            throw refused();
        }

        public void set(String key, String value) {
            throw refused();
        }

        public List<String> remove(Object key) {
            throw refused();
        }

        public void putAll(Map<? extends String, ? extends List<String>> t) {
            throw refused();
        }

        public void clear() {
            throw refused();
        }

        public Set<String> keySet() {
            return Collections.unmodifiableSet(super.keySet());
        }

        public Collection<List<String>> values() {
            return Collections.unmodifiableCollection(super.values());
        }

        public Set<Map.Entry<String, List<String>>> entrySet() {
            return Collections.unmodifiableSet(super.entrySet());
        }
    }

    // ---- the transport

    /** Binds, and hands the transport answer() for each request: the server, or what failed as text. */
    private static native Object open0(Object server, int port, String host);

    private static native boolean failed0(Object opened);

    private static native String text0(Object opened);

    /** The port bound, 0 where the transport cannot tell. */
    private static native int port0(Object handle);

    /**
     * Closes the transport's server. Answered: no request is in hand, so what the
     * transport still holds is a response it is writing, which goes out first; else the
     * exchanges in hand are cut.
     */
    private static native void close0(Object handle, boolean answered);

    /** Answers once the server has stopped: where the target has one thread, serves until then. */
    private static native void run0(Object handle);

    /** From here on the transport's threads run the program beside the thread it started on. */
    private static native void shared0();

    private static native Object gate0();

    /** A server is started, or stopped: the requests waiting for its start go on. */
    private static native void turn0(Object gate, Object handle, boolean started);

    /** Whether a request is let in, once the server was started: not once it was stopped. */
    private static native boolean admitted0(Object gate);

    private static native String method0(Object env);

    private static native String target0(Object env);

    private static native String protocol0(Object env);

    /** The request's fields, names and values in turn. */
    private static native String[] fields0(Object env);

    private static native byte[] body0(Object env);

    /** The host and the port of the peer, or of the server as the request names it. */
    private static native Object[] end0(Object env, boolean local);

    /** A whole response. */
    private static native void respond0(Object responder, int status, String[] fields, byte[] body);

    /** The head of a response whose body follows in chunks: the writer of them. */
    private static native Object stream0(Object responder, int status, String[] fields);

    private static native void write0(Object writer, byte[] octets);

    private static native void finish0(Object writer);

    /**
     * The end of a body in chunks whose handler failed, what it threw reported: a JDK
     * closes the connection with no last chunk.
     */
    private static native void cut0(Object writer, String report);
}
