// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net.http;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import rontolisp.internal.Http;

public interface HttpResponse<T> {

    int statusCode();

    HttpRequest request();

    Optional<HttpResponse<T>> previousResponse();

    HttpHeaders headers();

    T body();

    URI uri();

    HttpClient.Version version();

    /** What a body handler is told of a reply before its body is read. */
    interface ResponseInfo {

        int statusCode();

        HttpHeaders headers();

        HttpClient.Version version();
    }

    @FunctionalInterface
    interface BodyHandler<T> {

        BodySubscriber<T> apply(ResponseInfo responseInfo);
    }

    /** What makes a body of a reply's octets; the ones BodySubscribers makes are the ones a client reads through. */
    interface BodySubscriber<T> {

        CompletionStage<T> getBody();
    }

    class BodyHandlers {

        private BodyHandlers() {
        }

        public static BodyHandler<String> ofString() {
            return (ResponseInfo info) -> BodySubscribers.ofString(Http.charset(info.headers()));
        }

        public static BodyHandler<String> ofString(Charset charset) {
            if (charset == null) {
                throw new NullPointerException();
            }
            return (ResponseInfo info) -> BodySubscribers.ofString(charset);
        }

        public static BodyHandler<byte[]> ofByteArray() {
            return (ResponseInfo info) -> BodySubscribers.ofByteArray();
        }

        public static BodyHandler<InputStream> ofInputStream() {
            return (ResponseInfo info) -> BodySubscribers.ofInputStream();
        }

        public static BodyHandler<Void> discarding() {
            return (ResponseInfo info) -> BodySubscribers.discarding();
        }

        public static <U> BodyHandler<U> replacing(U value) {
            return (ResponseInfo info) -> BodySubscribers.replacing(value);
        }
    }

    class BodySubscribers {

        private BodySubscribers() {
        }

        public static BodySubscriber<String> ofString(Charset charset) {
            if (charset == null) {
                throw new NullPointerException();
            }
            return Http.reader((byte[] octets) -> new String(octets, charset));
        }

        public static BodySubscriber<byte[]> ofByteArray() {
            return Http.reader((byte[] octets) -> octets);
        }

        public static BodySubscriber<InputStream> ofInputStream() {
            return Http.reader((byte[] octets) -> Http.stream(octets));
        }

        public static BodySubscriber<Void> discarding() {
            return replacing(null);
        }

        public static <U> BodySubscriber<U> replacing(U value) {
            return Http.reader((byte[] octets) -> value);
        }

        public static <T, U> BodySubscriber<U> mapping(BodySubscriber<T> upstream, Function<? super T, ? extends U> mapper) {
            if (mapper == null) {
                throw new NullPointerException();
            }
            return Http.reader((byte[] octets) -> mapper.apply(Http.read(upstream, octets)));
        }
    }
}
