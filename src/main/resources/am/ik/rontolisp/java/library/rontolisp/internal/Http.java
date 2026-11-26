// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The client of java.net.http. A request is the host's fetch (rontolisp:fetch, which
// the lowered program itself calls, so every transport fetch has carries it): start0
// answers the exchange with the request on its way (timed0 under fetch's two
// timeouts), reply0 waits for all of its reply.
// What a JDK's client was seen to do with a request is written here over the two: the
// refusals of a builder, the redirects, the body a handler makes.
package rontolisp.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Http {

    private Http() {
    }

    public static HttpClient.Builder client() {
        return new ClientBuilder();
    }

    public static HttpRequest.Builder request() {
        return new RequestBuilder();
    }

    public static HttpRequest.BodyPublisher body(byte[] octets) {
        if (octets == null) {
            throw new NullPointerException();
        }
        return new Body(octets, null);
    }

    public static HttpRequest.BodyPublisher body(Supplier<? extends InputStream> stream) {
        return new Body(null, stream);
    }

    public static <T> HttpResponse.BodySubscriber<T> reader(Function<byte[], T> made) {
        return new Reader<T>(made);
    }

    /** The body a subscriber makes of a reply's octets. */
    public static <T> T read(HttpResponse.BodySubscriber<T> subscriber, byte[] octets) {
        if (subscriber == null) {
            throw new NullPointerException();
        }
        if (!(subscriber instanceof Reader)) {
            throw new UnsupportedOperationException("a BodySubscriber of a program's own class is not supported by"
                    + " the experimental Java subset: HttpResponse.BodySubscribers makes the ones a client reads");
        }
        Reader<T> reader = (Reader<T>) subscriber;
        return reader.made.apply(octets);
    }

    public static InputStream stream(byte[] octets) {
        return new ByteArrayInputStream(octets);
    }

    /** The charset a reply's Content-Type names, UTF-8 when it names none read here. */
    public static Charset charset(HttpHeaders headers) {
        Optional<String> type = headers.firstValue("Content-Type");
        if (type.isPresent()) {
            String lower = type.get().toLowerCase();
            int at = lower.indexOf("charset=");
            if (at >= 0) {
                int end = lower.indexOf(';', at);
                String name = lower.substring(at + 8, end < 0 ? lower.length() : end).trim();
                if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                    name = name.substring(1, name.length() - 1);
                }
                if (name.equals("iso-8859-1") || name.equals("latin1")) {
                    return StandardCharsets.ISO_8859_1;
                }
                if (name.equals("us-ascii")) {
                    return StandardCharsets.US_ASCII;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Whether a text is a token of the protocol: what a field's name and a method are. */
    static boolean token(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!letter && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * How a request leaves and what its failure is: fetch with its three options. A
     * program that gives a client or a request a time limit makes the wire that hands
     * fetch the two timeouts and knows a time limit passed, so only such a program
     * holds what a transport carries for them.
     */
    private static class Wire {

        Object start(String url, String method, String[] fields, byte[] body, Duration connect, Duration reply) {
            if (reply != null) {
                // no builder was given a time limit: the request is of a program's own class
                throw new UnsupportedOperationException("the timeout of an HttpRequest of a program's own class"
                        + " is not supported by the experimental Java subset: HttpRequest.Builder makes the"
                        + " requests a client times");
            }
            return start0(url, method, fields, body);
        }

        /**
         * What a transport's failure is to a program: a connection refused, or the
         * transport's own words. Each is told by the texts the transports give.
         */
        IOException failure(String text) {
            if (text.contains("ConnectException") || text.contains("CONNECTION-REFUSED")
                    || text.contains("cannot connect")) {
                return new ConnectException();
            }
            return new IOException(text);
        }

        /** What a blocking send throws for a failure: what failed is its cause. */
        IOException thrown(IOException failed) {
            if (failed instanceof ConnectException) {
                ConnectException thrown = new ConnectException(failed.getMessage());
                thrown.initCause(failed);
                return thrown;
            }
            return failed;
        }
    }

    private static final class TimedWire extends Wire {

        /**
         * A time limit in the milliseconds fetch counts, 0 for none: a part of a
         * millisecond is one, and what passes an int is the longest there is.
         */
        private static long millis(Duration limit) {
            if (limit == null) {
                return 0;
            }
            long millis = limit.getSeconds() >= Integer.MAX_VALUE / 1000 ? Integer.MAX_VALUE : limit.toMillis();
            return millis == 0 ? 1 : millis;
        }

        Object start(String url, String method, String[] fields, byte[] body, Duration connect, Duration reply) {
            return timed0(url, method, fields, body, millis(connect), millis(reply));
        }

        IOException failure(String text) {
            if (text.contains("connect timed out") || text.contains("CONNECTION-TIMEOUT")) {
                HttpConnectTimeoutException late = new HttpConnectTimeoutException("HTTP connect timed out");
                late.initCause(new ConnectException("HTTP connect timed out"));
                return late;
            }
            if (text.contains("request timed out") || text.contains("CONNECTION-READ-TIMEOUT")
                    || text.contains("HTTP-RESPONSE-TIMEOUT")) {
                return new HttpTimeoutException("request timed out");
            }
            return super.failure(text);
        }

        IOException thrown(IOException failed) {
            if (failed instanceof HttpConnectTimeoutException) {
                HttpConnectTimeoutException thrown = new HttpConnectTimeoutException(failed.getMessage());
                thrown.initCause(failed);
                return thrown;
            }
            return super.thrown(failed);
        }
    }

    private static Wire wire = new Wire();

    /**
     * A time limit a builder is given. From here on the program's requests leave by the
     * wire that hands fetch its timeouts.
     */
    private static Duration limit(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Invalid duration: " + duration);
        }
        wire = new TimedWire();
        return duration;
    }

    private static final class Body implements HttpRequest.BodyPublisher {

        private final byte[] octets;

        private final Supplier<? extends InputStream> stream;

        Body(byte[] octets, Supplier<? extends InputStream> stream) {
            this.octets = octets;
            this.stream = stream;
        }

        public long contentLength() {
            return octets == null ? -1 : octets.length;
        }

        byte[] octets() throws IOException {
            if (octets != null) {
                return octets;
            }
            InputStream in = stream.get();
            if (in == null) {
                throw new IOException("streamSupplier returned null");
            }
            try {
                return in.readAllBytes();
            }
            finally {
                in.close();
            }
        }
    }

    private static final class Reader<T> implements HttpResponse.BodySubscriber<T> {

        final Function<byte[], T> made;

        private final CompletableFuture<T> body = new CompletableFuture<>();

        Reader(Function<byte[], T> made) {
            this.made = made;
        }

        public CompletionStage<T> getBody() {
            return body;
        }
    }

    private static final class Request extends HttpRequest {

        private final String method;

        private final URI uri;

        private final HttpHeaders headers;

        private final BodyPublisher body;

        private final boolean expect;

        private final HttpClient.Version version;

        private final Duration timeout;

        Request(String method, URI uri, HttpHeaders headers, BodyPublisher body, boolean expect,
                HttpClient.Version version, Duration timeout) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            this.expect = expect;
            this.version = version;
            this.timeout = timeout;
        }

        public Optional<Duration> timeout() {
            return Optional.ofNullable(timeout);
        }

        public Optional<BodyPublisher> bodyPublisher() {
            return Optional.ofNullable(body);
        }

        public String method() {
            return method;
        }

        public boolean expectContinue() {
            return expect;
        }

        public URI uri() {
            return uri;
        }

        public Optional<HttpClient.Version> version() {
            return Optional.ofNullable(version);
        }

        public HttpHeaders headers() {
            return headers;
        }

        public String toString() {
            return uri + " " + method;
        }
    }

    private static final class RequestBuilder implements HttpRequest.Builder {

        private URI uri;

        private String method = "GET";

        private HttpRequest.BodyPublisher body;

        private boolean expect;

        private HttpClient.Version version;

        private Duration timeout;

        /** The names as first written, and the values of each. */
        private final ArrayList<String> names = new ArrayList<>();

        private final ArrayList<ArrayList<String>> values = new ArrayList<>();

        public RequestBuilder uri(URI uri) {
            if (uri == null) {
                throw new NullPointerException("uri must be non-null");
            }
            String scheme = uri.getScheme();
            if (scheme == null) {
                throw new IllegalArgumentException("URI with undefined scheme");
            }
            scheme = scheme.toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("invalid URI scheme " + scheme);
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported URI " + uri);
            }
            this.uri = uri;
            return this;
        }

        public RequestBuilder expectContinue(boolean enable) {
            this.expect = enable;
            return this;
        }

        public RequestBuilder version(HttpClient.Version version) {
            if (version == null) {
                throw new NullPointerException();
            }
            this.version = version;
            return this;
        }

        private int named(String name) {
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equalsIgnoreCase(name)) {
                    return i;
                }
            }
            return -1;
        }

        private String checked(String name, String value) {
            if (name == null) {
                throw new NullPointerException("name");
            }
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (!token(name)) {
                throw new IllegalArgumentException("invalid header name: \"" + name + "\"");
            }
            String lower = name.toLowerCase();
            if (lower.equals("connection") || lower.equals("content-length") || lower.equals("expect")
                    || lower.equals("host") || lower.equals("upgrade")) {
                throw new IllegalArgumentException("restricted header name: \"" + name + "\"");
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c != '\t' && (c < ' ' || c == 127 || c > 255)) {
                    throw new IllegalArgumentException("invalid header value: \"" + value + "\"");
                }
            }
            return value.trim();
        }

        public RequestBuilder header(String name, String value) {
            String kept = checked(name, value);
            int at = named(name);
            if (at < 0) {
                names.add(name);
                values.add(new ArrayList<String>());
                at = names.size() - 1;
            }
            values.get(at).add(kept);
            return this;
        }

        public RequestBuilder headers(String... headers) {
            if (headers == null) {
                throw new NullPointerException();
            }
            if (headers.length == 0 || headers.length % 2 != 0) {
                throw new IllegalArgumentException("wrong number, " + headers.length + ", of parameters");
            }
            for (int i = 0; i < headers.length; i += 2) {
                header(headers[i], headers[i + 1]);
            }
            return this;
        }

        public RequestBuilder setHeader(String name, String value) {
            String kept = checked(name, value);
            int at = named(name);
            if (at < 0) {
                names.add(name);
                values.add(new ArrayList<String>());
                at = names.size() - 1;
            }
            values.get(at).clear();
            values.get(at).add(kept);
            return this;
        }

        public RequestBuilder timeout(Duration duration) {
            this.timeout = limit(duration);
            return this;
        }

        public RequestBuilder GET() {
            this.method = "GET";
            this.body = null;
            return this;
        }

        public RequestBuilder POST(HttpRequest.BodyPublisher bodyPublisher) {
            return method("POST", bodyPublisher);
        }

        public RequestBuilder PUT(HttpRequest.BodyPublisher bodyPublisher) {
            return method("PUT", bodyPublisher);
        }

        public RequestBuilder DELETE() {
            this.method = "DELETE";
            this.body = null;
            return this;
        }

        public RequestBuilder HEAD() {
            this.method = "HEAD";
            this.body = null;
            return this;
        }

        public RequestBuilder method(String method, HttpRequest.BodyPublisher bodyPublisher) {
            if (method == null || bodyPublisher == null) {
                throw new NullPointerException();
            }
            if (method.isEmpty()) {
                throw new IllegalArgumentException("illegal method <empty string>");
            }
            if (method.equals("CONNECT")) {
                throw new IllegalArgumentException("method CONNECT is not supported");
            }
            if (!token(method)) {
                throw new IllegalArgumentException("illegal method \"" + method + "\"");
            }
            this.method = method;
            this.body = bodyPublisher;
            return this;
        }

        public HttpRequest build() {
            if (uri == null) {
                throw new IllegalStateException("uri is null");
            }
            HashMap<String, List<String>> fields = new HashMap<>();
            for (int i = 0; i < names.size(); i++) {
                fields.put(names.get(i), new ArrayList<String>(values.get(i)));
            }
            return new Request(method, uri, HttpHeaders.of(fields, (String name, String value) -> true), body, expect,
                    version, timeout);
        }

        public RequestBuilder copy() {
            RequestBuilder made = new RequestBuilder();
            made.uri = this.uri;
            made.method = this.method;
            made.body = this.body;
            made.expect = this.expect;
            made.version = this.version;
            made.timeout = this.timeout;
            for (int i = 0; i < names.size(); i++) {
                made.names.add(names.get(i));
                made.values.add(new ArrayList<String>(values.get(i)));
            }
            return made;
        }
    }

    private static final class Info implements HttpResponse.ResponseInfo {

        private final int status;

        private final HttpHeaders headers;

        Info(int status, HttpHeaders headers) {
            this.status = status;
            this.headers = headers;
        }

        public int statusCode() {
            return status;
        }

        public HttpHeaders headers() {
            return headers;
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class Response<T> implements HttpResponse<T> {

        private final HttpRequest request;

        private final int status;

        private final HttpHeaders headers;

        private final T body;

        private final Response<T> previous;

        Response(HttpRequest request, int status, HttpHeaders headers, T body, Response<T> previous) {
            this.request = request;
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.previous = previous;
        }

        public int statusCode() {
            return status;
        }

        public HttpRequest request() {
            return request;
        }

        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.ofNullable(previous);
        }

        public HttpHeaders headers() {
            return headers;
        }

        public T body() {
            return body;
        }

        public URI uri() {
            return request.uri();
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        public String toString() {
            return "(" + request.method() + " " + request.uri() + ") " + status;
        }
    }

    private static final class ClientBuilder implements HttpClient.Builder {

        private HttpClient.Redirect redirects = HttpClient.Redirect.NEVER;

        private HttpClient.Version version = HttpClient.Version.HTTP_2;

        private Executor executor;

        private Duration connect;

        public ClientBuilder connectTimeout(Duration duration) {
            this.connect = limit(duration);
            return this;
        }

        public ClientBuilder version(HttpClient.Version version) {
            if (version == null) {
                throw new NullPointerException();
            }
            this.version = version;
            return this;
        }

        public ClientBuilder followRedirects(HttpClient.Redirect policy) {
            if (policy == null) {
                throw new NullPointerException();
            }
            this.redirects = policy;
            return this;
        }

        public ClientBuilder executor(Executor executor) {
            if (executor == null) {
                throw new NullPointerException();
            }
            this.executor = executor;
            return this;
        }

        public HttpClient build() {
            return new Client(redirects, version, executor, connect);
        }
    }

    private static final class Client extends HttpClient {

        private final Redirect redirects;

        private final Version version;

        private final Executor executor;

        private final Duration connect;

        private volatile boolean closed;

        Client(Redirect redirects, Version version, Executor executor, Duration connect) {
            this.redirects = redirects;
            this.version = version;
            this.executor = executor;
            this.connect = connect;
        }

        public Optional<Duration> connectTimeout() {
            return Optional.ofNullable(connect);
        }

        public Redirect followRedirects() {
            return redirects;
        }

        public Version version() {
            return version;
        }

        public Optional<Executor> executor() {
            return Optional.ofNullable(executor);
        }

        public void shutdown() {
            this.closed = true;
        }

        public boolean isTerminated() {
            return closed;
        }

        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            if (request == null || responseBodyHandler == null) {
                throw new NullPointerException();
            }
            if (closed) {
                throw new IOException("closed");
            }
            try {
                return answered(request, responseBodyHandler, started(request));
            }
            catch (IOException failed) {
                throw wire.thrown(failed);
            }
        }

        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            if (request == null || responseBodyHandler == null) {
                throw new NullPointerException();
            }
            if (closed) {
                return CompletableFuture.failedFuture(new IOException("closed"));
            }
            Object exchange;
            try {
                // the request is on its way when this returns
                exchange = started(request);
            }
            catch (IOException failed) {
                return CompletableFuture.failedFuture(failed);
            }
            Supplier<HttpResponse<T>> rest = () -> {
                try {
                    return answered(request, responseBodyHandler, exchange);
                }
                catch (IOException failed) {
                    throw new CompletionException(failed);
                }
            };
            return executor == null ? CompletableFuture.supplyAsync(rest) : CompletableFuture.supplyAsync(rest, executor);
        }

        /** The exchange of a request sent: what fetch answered, or the text of its refusal. */
        private Object started(HttpRequest request) throws IOException {
            String method = request.method();
            if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("POST") && !method.equals("PUT")
                    && !method.equals("DELETE") && !method.equals("OPTIONS") && !method.equals("PATCH")) {
                throw new UnsupportedOperationException("the method " + method + " is not supported by the"
                        + " experimental Java subset: a request is sent as GET, HEAD, POST, PUT, DELETE, OPTIONS"
                        + " or PATCH");
            }
            if (request.expectContinue()) {
                throw new UnsupportedOperationException(
                        "expectContinue(true) is not supported by the experimental Java subset");
            }
            ArrayList<String> flat = new ArrayList<>();
            for (Map.Entry<String, List<String>> field : request.headers().map().entrySet()) {
                for (String value : field.getValue()) {
                    flat.add(field.getKey());
                    flat.add(value);
                }
            }
            byte[] octets = null;
            Optional<HttpRequest.BodyPublisher> publisher = request.bodyPublisher();
            if (publisher.isPresent()) {
                if (!(publisher.get() instanceof Body)) {
                    throw new UnsupportedOperationException("a BodyPublisher of a program's own class is not"
                            + " supported by the experimental Java subset: HttpRequest.BodyPublishers makes the"
                            + " ones a client sends");
                }
                octets = ((Body) publisher.get()).octets();
                if (octets.length == 0) {
                    octets = null;
                }
            }
            // a fragment is the client's own: it is not sent
            String url = request.uri().toString();
            int fragment = url.indexOf('#');
            return wire.start(fragment < 0 ? url : url.substring(0, fragment), method, flat.toArray(new String[0]),
                    octets, connect, request.timeout().orElse(null));
        }

        /** The reply to a request sent, and to the requests its redirects lead to. */
        private <T> HttpResponse<T> answered(HttpRequest request, HttpResponse.BodyHandler<T> handler, Object exchange)
                throws IOException {
            HttpRequest current = request;
            Response<T> previous = null;
            int hops = 0;
            while (true) {
                Object reply = reply0(exchange);
                if (failed0(reply)) {
                    throw wire.failure(text0(reply));
                }
                int status = status0(reply);
                String[] flat = fields0(reply);
                HashMap<String, List<String>> fields = new HashMap<>();
                for (int i = 0; i < flat.length; i += 2) {
                    List<String> values = fields.get(flat[i]);
                    if (values == null) {
                        values = new ArrayList<String>();
                        fields.put(flat[i], values);
                    }
                    values.add(flat[i + 1]);
                }
                HttpHeaders headers = HttpHeaders.of(fields, (String name, String value) -> true);
                HttpRequest next = hops < 4 ? redirected(current, status, headers) : null;
                if (next == null) {
                    T body = read(handler.apply(new Info(status, headers)), octets0(reply));
                    return new Response<T>(current, status, headers, body, previous);
                }
                previous = new Response<T>(current, status, headers, null, previous);
                current = next;
                hops++;
                exchange = started(current);
            }
        }

        /**
         * The request a reply leads to under the client's policy, or null when the reply is
         * the answer: as a JDK's client was seen to follow a 301, 302, 303, 307 and 308.
         */
        private HttpRequest redirected(HttpRequest request, int status, HttpHeaders headers) throws IOException {
            if (redirects == Redirect.NEVER
                    || (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)) {
                return null;
            }
            Optional<String> location = headers.firstValue("Location");
            URI target;
            try {
                target = request.uri().resolve(location.get());
            }
            catch (RuntimeException invalid) {
                throw new IOException(new UncheckedIOException(new IOException("Invalid redirection")));
            }
            URI from = request.uri();
            String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase();
            boolean same = scheme.equalsIgnoreCase(from.getScheme());
            if (!scheme.equals("https") && !(scheme.equals("http") && (same || redirects == Redirect.ALWAYS))) {
                return null;
            }
            if (target.getHost() == null) {
                return null;
            }
            boolean get = status == 303 || (status < 303 && request.method().equals("POST"));
            HttpHeaders kept = request.headers();
            if (!same || !String.valueOf(target.getRawAuthority()).equals(String.valueOf(from.getRawAuthority()))) {
                // off the origin the credentials stay behind
                kept = HttpHeaders.of(kept.map(), (String name, String value) -> !name.equalsIgnoreCase("authorization")
                        && !name.equalsIgnoreCase("cookie") && !name.equalsIgnoreCase("origin")
                        && !name.equalsIgnoreCase("referer"));
            }
            return new Request(get ? "GET" : request.method(), target, kept,
                    get ? null : request.bodyPublisher().orElse(null), false, request.version().orElse(null),
                    request.timeout().orElse(null));
        }
    }

    private static native Object start0(String url, String method, String[] fields, byte[] body);

    /** start0 under time limits in milliseconds, 0 for none. */
    private static native Object timed0(String url, String method, String[] fields, byte[] body, long connect,
            long reply);

    private static native Object reply0(Object exchange);

    private static native boolean failed0(Object reply);

    private static native String text0(Object reply);

    private static native int status0(Object reply);

    private static native String[] fields0(Object reply);

    private static native byte[] octets0(Object reply);
}
