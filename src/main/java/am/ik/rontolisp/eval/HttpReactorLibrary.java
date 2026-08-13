package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The host-driven-reactor transport ({@code http-reactor.lisp}): the ONE application
 * store and the JSON request/response envelope over {@code http-server.lisp}'s
 * {@code %http-make-env} / {@code %http-normalize-response}, shared by BOTH Clack handler
 * backends -- the {@code #+rontolisp-reactor} leg of {@code clack-handler-rontolisp} and
 * the explicit {@code clack-handler-reactor} backend delegate here, which is what keeps a
 * program that mixes the two designators on one store and one dispatcher
 * ({@code .kb/clack.md}).
 *
 * <p>
 * Consumers mirror {@link HttpServerLibrary}: the interpreter lazy-loads {@link #forms()}
 * through its {@code RONTOLISP::%HTTP-REACTOR-} function-lookup hook (every public entry
 * is a function, so the first touch -- {@code %http-reactor-register} from a backend's
 * {@code run}, or {@code %http-reactor-handle} from a direct {@code handle} call --
 * triggers the load); the compile path calls {@link #process(List)} right after
 * {@code HttpReactorInliner} (whose synthesized bridge references
 * {@code %http-reactor-dispatch}) and BEFORE {@code HttpServerLibrary.process}, whose
 * entry points this library's bodies call. Unlike {@code http-server.lisp} it is NOT
 * self-contained: {@code JsonLibrary} later picks up its {@code json-parse} /
 * {@code json-stringify} call sites.
 */
public final class HttpReactorLibrary {

	/** The envelope adapter: {@code (app request-json) -> response-json}. */
	public static final String HANDLE = "%HTTP-REACTOR-HANDLE";

	/** The host's entry point over the stored application. */
	public static final String DISPATCH = "%HTTP-REACTOR-DISPATCH";

	/** The application store a handler backend's {@code run} calls. */
	public static final String REGISTER = "%HTTP-REACTOR-REGISTER";

	// A reference to any %http-reactor-* member (the marker %HTTP-REACTOR itself has no
	// trailing dash and is lowered away before this pass runs).
	private static final String MEMBER_PREFIX = "%HTTP-REACTOR-";

	@Nullable private static volatile List<LispVal> forms;

	private HttpReactorLibrary() {
	}

	/**
	 * Returns the parsed library definitions. The source is written in canonical shape
	 * (internal double-colon names, bare {@code cl} names), so it needs no package
	 * resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (HttpReactorLibrary.class) {
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
		try (InputStream in = HttpReactorLibrary.class.getResourceAsStream("http-reactor.lisp")) {
			if (in == null) {
				throw new IllegalStateException("http-reactor.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The compile-path pre-pass: when the program references the reactor machinery -- a
	 * handler backend's {@code run}/{@code handle}/{@code dispatch} body, or the bridge
	 * {@code HttpReactorInliner} synthesized -- prepends the library definitions. A
	 * program that already defines {@link #HANDLE} and a program that never touches the
	 * machinery are returned unchanged.
	 *
	 * <p>
	 * The library's {@code :raw-body} switch names {@code rontolisp::%stream-new}, which
	 * {@code --no-gc} rejects by name -- and that costs nothing, because the transport
	 * also rides {@code http-server.lisp}, whose {@code %http-drain} /
	 * {@code %http-serve-request} are {@code async-defun}s that backend already refuses:
	 * a {@code --no-gc} reactor cannot carry the HTTP transport at all, with or without a
	 * body stream.
	 * @param program the top-level forms
	 * @return the program with the reactor library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!usesReactor(program) || definesHandle(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	private static @Nullable String defunName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.DEFUN.equals(member(op.name())) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name) {
			return member(name.name());
		}
		return null;
	}

	/**
	 * Returns whether the program references the reactor machinery. Like
	 * {@code HttpServerLibrary.usesServer}, a mention anywhere is enough -- quoted data
	 * included (the {@code %http-reactor} marker's quoted dispatcher name is gone by the
	 * time this runs, but a user's {@code #'rontolisp::%http-reactor-dispatch} must
	 * count).
	 * @param program the top-level forms
	 * @return {@code true} when the reactor library is needed
	 */
	public static boolean usesReactor(List<LispVal> program) {
		Walker walker = new Walker();
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
		}
		return walker.found;
	}

	private static boolean definesHandle(List<LispVal> program) {
		for (LispVal form : program) {
			if (HANDLE.equals(defunName(form))) {
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
					case am.ik.rontolisp.LispString str -> str.value();
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
					if ((rontolispQualified || inRontolisp) && name.startsWith(MEMBER_PREFIX)) {
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
