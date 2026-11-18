// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The one client there is makes its requests through the host's fetch
// (rontolisp.internal.Http): a connection, its version and its pool are the
// transport's, so what configures them has no member here.
package java.net.http;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import rontolisp.internal.Http;

public abstract class HttpClient implements AutoCloseable {

    public enum Version {
        HTTP_1_1, HTTP_2
    }

    public enum Redirect {
        NEVER, ALWAYS, NORMAL
    }

    public interface Builder {

        Builder version(Version version);

        Builder followRedirects(Redirect policy);

        Builder executor(Executor executor);

        HttpClient build();
    }

    protected HttpClient() {
    }

    public static HttpClient newHttpClient() {
        return newBuilder().build();
    }

    public static Builder newBuilder() {
        return Http.client();
    }

    public abstract Redirect followRedirects();

    public abstract Version version();

    public abstract Optional<Executor> executor();

    public abstract <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException;

    public abstract <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler);

    public void shutdown() {
    }

    public void shutdownNow() {
        shutdown();
    }

    public boolean isTerminated() {
        return false;
    }

    public void close() {
        shutdown();
    }
}
