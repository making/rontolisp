// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net.http;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.function.Supplier;
import rontolisp.internal.Http;

public abstract class HttpRequest {

    public interface Builder {

        Builder uri(URI uri);

        Builder expectContinue(boolean enable);

        Builder version(HttpClient.Version version);

        Builder header(String name, String value);

        Builder headers(String... headers);

        Builder setHeader(String name, String value);

        Builder GET();

        Builder POST(BodyPublisher bodyPublisher);

        Builder PUT(BodyPublisher bodyPublisher);

        Builder DELETE();

        Builder HEAD();

        Builder method(String method, BodyPublisher bodyPublisher);

        HttpRequest build();

        Builder copy();
    }

    /** What a request's body is; the ones BodyPublishers makes are the ones a client sends. */
    public interface BodyPublisher {

        long contentLength();
    }

    public static class BodyPublishers {

        private BodyPublishers() {
        }

        public static BodyPublisher ofString(String body) {
            return Http.body(body.getBytes());
        }

        public static BodyPublisher ofString(String s, Charset charset) {
            return Http.body(s.getBytes(charset));
        }

        public static BodyPublisher ofByteArray(byte[] buf) {
            return Http.body(buf);
        }

        public static BodyPublisher ofByteArray(byte[] buf, int offset, int length) {
            if (offset < 0 || length < 0 || length > buf.length - offset) {
                throw new IndexOutOfBoundsException("Range [" + offset + ", " + offset + " + " + length
                        + ") out of bounds for length " + buf.length);
            }
            byte[] range = new byte[length];
            System.arraycopy(buf, offset, range, 0, length);
            return Http.body(range);
        }

        public static BodyPublisher ofInputStream(Supplier<? extends InputStream> streamSupplier) {
            if (streamSupplier == null) {
                throw new NullPointerException();
            }
            return Http.body(streamSupplier);
        }

        public static BodyPublisher noBody() {
            return Http.body(new byte[0]);
        }
    }

    protected HttpRequest() {
    }

    public static Builder newBuilder() {
        return Http.request();
    }

    public static Builder newBuilder(URI uri) {
        return Http.request().uri(uri);
    }

    public abstract Optional<BodyPublisher> bodyPublisher();

    public abstract String method();

    public abstract boolean expectContinue();

    public abstract URI uri();

    public abstract Optional<HttpClient.Version> version();

    public abstract HttpHeaders headers();

    public final boolean equals(Object obj) {
        return obj instanceof HttpRequest that && that.method().equals(this.method()) && that.uri().equals(this.uri())
                && that.headers().equals(this.headers());
    }

    public final int hashCode() {
        return method().hashCode() + uri().hashCode() + headers().hashCode();
    }
}
