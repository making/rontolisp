package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The server-side HTTP value model ({@code http-server.lisp}): the Clack environment a
 * {@code rontolisp:http-handler} handler receives and the Clack response it returns,
 * written ONCE in rontolisp itself so all four backends agree by construction rather than
 * by four parallel implementations.
 *
 * <p>
 * Every transport (the interpreter's and the JVM backend's JDK {@code HttpServer}, the
 * WASI 0.3 component's {@code handler.handle}) hands
 * {@code rontolisp::%http-serve-request} a positional RAW TUPLE of the facts only it can
 * know and gets back the canonical {@code (status header-alist body-string)} triple its
 * writer already understands. Percent-decoding, the {@code ?} split, header lowercasing
 * and comma-joining, the method keyword, the {@code Host} split, {@code content-length}
 * parsing, the {@code :raw-body} stream, running the application and normalizing its
 * Clack response all live in the library, not in the transports.
 *
 * <p>
 * Consumers:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment EAGERLY when
 * a server starts (never lazily on the first request: a served request runs on its own
 * virtual thread, {@code .kb/concurrent-served-requests.md});</li>
 * <li>the compile path ({@code RontoLispCli}, and the tests that drive the compilers
 * directly) calls {@link #process(List, boolean)} before
 * {@code GrayStreamsLibrary.process}, whose call-site rewrite the library's
 * {@code :raw-body} stream depends on.</li>
 * </ul>
 *
 * <p>
 * The library is self-contained by rule -- core built-ins and its own defuns only. The
 * WASM serve harnesses splice it with nothing else beside {@code http.lisp}, so a
 * reference to the prelude, {@code url.lisp} or {@code json.lisp} from it would break
 * tests that look unrelated.
 */
public final class HttpServerLibrary {

	/**
	 * The shared entry point every transport calls: {@code (app raw)} in, the canonical
	 * {@code (status header-alist body-string)} triple out.
	 */
	public static final String SERVE_REQUEST = "%HTTP-SERVE-REQUEST";

	/**
	 * The environment builder -- the shape declaration, called by the tests and ci-spec.
	 */
	public static final String MAKE_ENV = "%HTTP-MAKE-ENV";

	/** The Clack response normalizer. */
	public static final String NORMALIZE_RESPONSE = "%HTTP-NORMALIZE-RESPONSE";

	/**
	 * The buffered {@code :raw-body} constructor. The ONE value no backend builds
	 * natively: a bivalent Gray input stream is a CLOS instance, which hand-written
	 * bytecode cannot make, so every backend calls this -- and only when the request has
	 * a body.
	 */
	public static final String BODY_STREAM = "%HTTP-BODY-STREAM";

	/** The cold response-body arms (an {@code (unsigned-byte 8)} vector today). */
	public static final String BODY_STRING = "%HTTP-BODY-STRING";

	/**
	 * The normalized body rendered as the STRING a text transport writes -- an
	 * {@code (unsigned-byte 8)} body one character per octet, a string unchanged. The
	 * normalizer deliberately hands octets through (only a transport knows whether it can
	 * write bytes), so every transport that cannot flattens here.
	 */
	public static final String BODY_TEXT = "%HTTP-BODY-TEXT";

	// A reference to any of these (or to the http-handler directive / the stoppable
	// server seam) means the program serves, so the library has to be present.
	private static final Set<String> ENTRY_POINTS = Set.of(SERVE_REQUEST, MAKE_ENV, NORMALIZE_RESPONSE,
			"%HTTP-PERCENT-DECODE", "%HTTP-HEADERS-TABLE", "%HTTP-BODY-STREAM", "%HTTP-DRAIN",
			"%HTTP-RESPONSE-HEADERS-ALIST", "%HTTP-BODY-STRING");

	@Nullable private static volatile List<LispVal> forms;

	private HttpServerLibrary() {
	}

	/**
	 * Returns the parsed library definitions. The source is written in canonical shape
	 * (external single-colon public names, internal double-colon helpers, bare {@code cl}
	 * names), so it needs no package resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (HttpServerLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource(), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = HttpServerLibrary.class.getResourceAsStream("http-server.lisp")) {
			if (in == null) {
				throw new IllegalStateException("http-server.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The compile-path pre-pass: when the program serves -- it uses the
	 * {@code rontolisp:http-handler} directive (at top level or nested in a defun, the
	 * clack shim's shape), calls the internal {@code rontolisp::%http-server-start} seam,
	 * or names one of the library's own entry points -- prepends the library definitions.
	 * A program that already defines {@link #SERVE_REQUEST} (a load spliced it) and a
	 * program that never serves are returned unchanged.
	 *
	 * <p>
	 * The {@code bufferBody} flag is the program's {@code :raw-body} mode
	 * ({@code HttpLibrary.usesBufferedBody}, read BEFORE the directive is rewritten
	 * away): a default-mode ({@code :stream}) program gets the library WITHOUT the
	 * buffered-body half -- the Gray stream class, {@code %http-body-stream} and the
	 * UTF-8 encoder -- which on a WASM serve component was a measured 35% of the module
	 * size for machinery no request could ever reach.
	 * @param program the top-level forms
	 * @param bufferBody whether the program asked for {@code :raw-body :buffered}
	 * @return the program with the server library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, boolean bufferBody) {
		if (!usesServer(program) || definesServeRequest(program)) {
			return program;
		}
		// A program naming the buffered-body machinery DIRECTLY (the ci-spec shape
		// cases call %http-body-stream without any directive) keeps it regardless of
		// the :raw-body mode.
		boolean keepBuffered = bufferBody || referencesBufferedBody(program);
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : forms()) {
			if (!keepBuffered && isBufferedBodyForm(form)) {
				continue;
			}
			out.add(form);
		}
		out.addAll(program);
		return out;
	}

	private static boolean referencesBufferedBody(List<LispVal> program) {
		for (LispVal form : program) {
			if (mentionsBodyStreamClass(form) || mentionsBufferedConstructor(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentionsBufferedConstructor(LispVal form) {
		return switch (form) {
			case LispSymbol sym ->
				BODY_STREAM.equals(member(sym.name())) || "%HTTP-UTF8-ENCODE".equals(member(sym.name()));
			case LispCons cons -> mentionsBufferedConstructor(cons.car()) || mentionsBufferedConstructor(cons.cdr());
			default -> false;
		};
	}

	// The buffered-body half of the library: the Gray stream class with its methods and
	// constructor (everything that mentions the class name mentions nothing else's), and
	// the UTF-8 encoder only that constructor calls.
	private static boolean isBufferedBodyForm(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.DEFUN.equals(member(op.name())) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name && "%HTTP-UTF8-ENCODE".equals(member(name.name()))) {
			return true;
		}
		return mentionsBodyStreamClass(form);
	}

	private static boolean mentionsBodyStreamClass(LispVal form) {
		return switch (form) {
			case LispSymbol sym -> "HTTP-REQUEST-BODY-STREAM".equals(member(sym.name()));
			case LispCons cons -> mentionsBodyStreamClass(cons.car()) || mentionsBodyStreamClass(cons.cdr());
			default -> false;
		};
	}

	/**
	 * Returns whether the program serves HTTP: the {@code rontolisp:http-handler}
	 * directive anywhere (quoted data excluded is NOT attempted here -- a mention is
	 * enough to need the library), the stoppable-server seam, or one of the library's own
	 * entry points.
	 * @param program the top-level forms
	 * @return {@code true} when the server library is needed
	 */
	public static boolean usesServer(List<LispVal> program) {
		Walker walker = new Walker();
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
		}
		return walker.found;
	}

	private static boolean definesServeRequest(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& (LispNames.DEFUN.equals(member(op.name())) || LispNames.ASYNC_DEFUN.equals(member(op.name())))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& SERVE_REQUEST.equals(member(name.name()))) {
				return true;
			}
		}
		return false;
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static final class Walker {

		private boolean found;

		private String currentPackage = LispNames.CL_USER_PKG;

		private void trackTopLevelInPackage(LispVal form) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.IN_PACKAGE.equals(member(op.name())) && cons.cdr() instanceof LispCons argCell) {
				String name = switch (argCell.car()) {
					case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
					case LispString str -> str.value();
					default -> this.currentPackage;
				};
				this.currentPackage = PackageRegistry.canonicalBuiltinName(name);
			}
		}

		private void detect(LispVal form) {
			if (this.found) {
				return;
			}
			switch (form) {
				case LispSymbol sym -> {
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
					String name = qn == null ? sym.name() : qn.member();
					boolean rontolispQualified = qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg());
					boolean inRontolisp = qn == null && LispNames.RONTOLISP_PKG.equals(this.currentPackage);
					if ((rontolispQualified || inRontolisp) && (LispNames.HTTP_HANDLER.equals(name)
							|| LispNames.HTTP_SERVER_START.equals(name) || ENTRY_POINTS.contains(name))) {
						this.found = true;
					}
				}
				case LispCons cons -> {
					detect(cons.car());
					detect(cons.cdr());
				}
				default -> {
				}
			}
		}

	}

}
