package am.ik.rontolisp.compiler;

import java.util.List;

/**
 * The fixed names of the host-driven reactor boundary — the JSON envelope
 * {@code http-reactor.lisp} reads and writes, plus the two Lisp names the compile path
 * synthesizes around it. Written down HERE, in the backend-free package both sides can
 * see, for the same reason {@link FetchResponseShape} is: the envelope is an API
 * ({@code .kb/clack.md} documents it as one), and an API spelled twice drifts.
 *
 * <p>
 * Three consumers, none of which may import the others: {@code eval/HttpReactorInliner}
 * synthesizes the bridge and its {@code wasm-export} from {@link #BRIDGE_FUNCTION} /
 * {@link #EXPORT_NAME}; {@code codegen.wasm} recognises that export as the reactor's by
 * the bridge name, because an export is otherwise just a name a program chose; and
 * {@link HostGlueEmitter} maps a {@code Request} onto {@link #REQUEST_KEYS} and a
 * {@code Response} off {@link #RESPONSE_KEYS}, which is transport work rather than
 * program work and is therefore emitted rather than asked for.
 *
 * <p>
 * The KEYS are the contract; what each one is filled FROM is the host's own business and
 * is not derivable here, so a consumer switches over the list and must throw on a name it
 * does not carry — growing the envelope then fails the build loudly instead of silently
 * dropping a key. {@code ReactorEnvelopeTest} pins both lists against
 * {@code http-reactor.lisp}, the file that really reads and writes them.
 */
public final class ReactorEnvelope {

	/**
	 * The Lisp name of the bridge defun {@code eval/HttpReactorInliner} appends: the
	 * one-argument wrapper over {@code rontolisp::%http-reactor-dispatch} that the
	 * {@code wasm-export} below names. An export whose Lisp function is this one IS the
	 * reactor envelope, which is the only sound way for a backend to tell the synthesized
	 * boundary from an export a program happened to call {@code handle-request}.
	 */
	public static final String BRIDGE_FUNCTION = "%REACTOR-DISPATCH";

	/**
	 * The export a reactor's host calls, and the name both Clack handler backends'
	 * {@code %http-reactor} marker states: JSON request head in, JSON response head out.
	 */
	public static final String EXPORT_NAME = "handle-request";

	/**
	 * The request key carrying the CALL's identity, present only in a {@code --reentrant}
	 * streaming build: overlapped calls share the host's body imports, so each envelope
	 * names the call its bodies belong to and the imports take that id as a leading
	 * {@code :int} argument. A host that sends this key declares that the out-of-band
	 * body thunks it passed take the id as their leading argument; every other host
	 * leaves it out and nothing changes shape (the no-handle argument still holds
	 * wherever the id-less protocol exists, because there the re-entry guard or the
	 * serialising queue still does).
	 */
	public static final String CALL_ID_KEY = "call-id";

	/**
	 * The request head's keys, in the order {@code %http-reactor-request-tuple} reads
	 * them. {@code method} defaults to {@code "GET"} and {@code target} to {@code "/"};
	 * {@code target} is RAW (path and query still joined and still percent-encoded — the
	 * shared normalizer owns that split); {@code headers} is a JSON OBJECT, and a request
	 * with a body must carry {@code content-length} in it; {@code body} is the in-band
	 * body, {@code ""} or absent for none; {@code scheme} defaults to {@code "http"} and
	 * {@code remote-addr} to nothing; {@code script-name} is the application's mount
	 * point as a RAW prefix of {@code target}, for a host that mounts the application
	 * under a path -- absent means root-mounted; {@code call-id} is {@link #CALL_ID_KEY},
	 * absent everywhere but a {@code --reentrant} streaming build.
	 */
	public static final List<String> REQUEST_KEYS = List.of("method", "target", "headers", "body", "scheme",
			"remote-addr", "script-name", CALL_ID_KEY);

	/**
	 * The response head's keys, in the order {@code %http-reactor-envelope} writes them.
	 * {@code headers} is an ARRAY of {@code [name, value]} pairs rather than an object (a
	 * name may repeat, and two cookies must stay two), and {@code body} is ABSENT rather
	 * than empty when the caller passed a body sink.
	 */
	public static final List<String> RESPONSE_KEYS = List.of("status", "headers", "body");

	/**
	 * The import-object key every injected host hook of a {@code --no-wasi} reactor lives
	 * under -- the two body fields below, {@code env.fetch} and {@code env.random_get}.
	 */
	public static final String HOST_MODULE = "env";

	/**
	 * The field of the request-body import a {@code --host-boundary=streaming} reactor
	 * declares: {@code (ptr, cap) -> i32}, "write up to cap octets at ptr and answer how
	 * many; 0 is end of stream". Its presence in a module IS that boundary.
	 */
	public static final String REQUEST_BODY_FIELD = "readRequestBody";

	/**
	 * The field of the response-body import, the mirror of {@link #REQUEST_BODY_FIELD}:
	 * {@code (ptr, len)}, "take these octets, they are the next chunk". No result -- a
	 * host cannot short-read a write.
	 */
	public static final String RESPONSE_BODY_FIELD = "writeResponseBody";

	private ReactorEnvelope() {
	}

}
