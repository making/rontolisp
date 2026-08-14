package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.ReactorEnvelope;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The compile-path answer to a HOST-DRIVEN REACTOR served by {@code clack:clackup}: turns
 * the {@code rontolisp::%http-reactor} marker a Clack handler backend's {@code run}
 * carries into the {@code rontolisp:wasm-export} the host actually calls. Two backends
 * carry it: {@code clack-handler-reactor} on every WASM compile (that backend IS the
 * reactor shape, on every target), and {@code clack-handler-rontolisp} under
 * {@code #+rontolisp-reactor} ({@code --no-wasi} / {@code --no-gc}) -- both naming the
 * ONE shared dispatcher, {@code rontolisp::%http-reactor-dispatch}
 * ({@code http-reactor.lisp}), so a program that splices both shims yields two IDENTICAL
 * markers and first-wins below is not a choice.
 *
 * <p>
 * A reactor's entry point is an EXPORT, and {@link LispNames#WASM_EXPORT} needs a literal
 * quoted name at compile time -- so a program whose whole body is
 * {@code (clack:clackup #'app :server :rontolisp)} can no longer declare it: the name
 * only exists inside the handler backend, and the decision to export at all is taken at
 * run time, when clackup applies {@code run}. This pass reads the decision out of the
 * source instead: the shim's {@code run} contains
 * {@code (rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch "handle-request")},
 * which is lowered to {@code nil} (nothing to do at run time; the app store around it
 * stays) and answered with
 *
 * <pre>{@code
 * (defun %reactor-dispatch (%reactor-json) (rontolisp::%http-reactor-dispatch %reactor-json))
 * (rontolisp:wasm-export '%reactor-dispatch :as "handle-request" :params '(:string) :returns :string)
 * }</pre>
 *
 * appended AFTER the program, so a package-qualified dispatcher name resolves against the
 * spliced definitions whatever package the program ended in.
 *
 * <p>
 * On the Preview 1 core-module backend the bridge can also take BOTH BODIES OUT OF THE
 * ENVELOPE ({@code --host-boundary=streaming}, the default), which is what the
 * synthesized {@code env.readRequestBody} / {@code env.writeResponseBody} imports are:
 * the head stays a JSON string and stays small, and the bodies cross as raw octets
 * through {@code :bytes} host imports -- in, into ONE reused buffer the module passes
 * (the {@code read(2)} shape -- {@code .kb/wasm-import.md}); out, as a parameter the host
 * takes before the call returns. Two things follow that the envelope could not give: a
 * BINARY body crosses exactly in either direction (the {@code :string} decoder is
 * non-validating and corrupts arbitrary bytes), and neither body costs linear memory
 * proportional to its own size. Both imports are declared {@code :async t}, so a host
 * that STREAMS -- a {@code WebAssembly.Suspending} wrapper over a {@code ReadableStream}
 * reader, or over a response writer that applies backpressure -- is a declared, supported
 * host rather than a silent re-entrancy hazard; a host that answers synchronously is
 * equally valid and pays nothing (the future is settled at creation on this backend).
 *
 * <p>
 * {@code --host-boundary=envelope} declines all of that and keeps both bodies in the
 * envelope's own {@code "body"} key: the bridge is then the one-argument call, the module
 * imports neither {@code env.*} body function, and no host-side cursor exists to outlive
 * the call that opened it. The Lisp half needs nothing for it -- the dispatcher's body
 * source and sink are {@code &optional} and their absence has always meant the in-band
 * key, which is what {@code --component}, {@code --no-gc}, a WASI command module, the
 * interpreter and the JVM run today. What it costs is the other side of the list above: a
 * binary body is flattened, and a large one is copied.
 *
 * <p>
 * The precedent is exact and deliberate: {@link HttpLibrary} reads the
 * {@code rontolisp:http-handler} directive NESTED in the {@code clack-handler-rontolisp}
 * shim's {@code run} the same way, for the same reason. It is a SEPARATE marker rather
 * than an overload of that directive because {@code http-handler} means "bind a socket"
 * on every other backend, which is precisely what a reactor does not do.
 *
 * <p>
 * A no-op on the interpreter and the JVM backend -- there the shims never even read the
 * marker ({@code #+rontolisp-wasm} / {@code #+rontolisp-reactor}) because the host calls
 * the dispatcher as an ordinary function.
 */
public final class HttpReactorInliner {

	/** The synthesized bridge defun the export names. */
	static final String DISPATCH_BRIDGE = ReactorEnvelope.BRIDGE_FUNCTION;

	/** The synthesized body-pull defun, and the import it calls. */
	static final String CHUNK_BRIDGE = "%REACTOR-READ-CHUNK";

	static final String READ_BODY_IMPORT = "%REACTOR-READ-BODY";

	/** The synthesized body-push defun, and the import it calls. */
	static final String WRITE_BRIDGE = "%REACTOR-WRITE-CHUNK";

	static final String WRITE_BODY_IMPORT = "%REACTOR-WRITE-BODY";

	/**
	 * The export a reactor's host calls, and the name the Clack handler backends'
	 * {@code %http-reactor} marker states: JSON request in, JSON response out.
	 */
	public static final String EXPORT_NAME = ReactorEnvelope.EXPORT_NAME;

	/**
	 * The host module and field of the request-body import: {@code (ptr, cap) -> i32},
	 * "write up to cap octets at ptr and answer how many; 0 is end of stream". The
	 * {@code env} module is where every other injected host hook of this backend lives
	 * ({@code env.fetch}, {@code env.random_get}).
	 */
	static final String READ_BODY_MODULE = ReactorEnvelope.HOST_MODULE;

	static final String READ_BODY_FIELD = ReactorEnvelope.REQUEST_BODY_FIELD;

	/**
	 * The field of the response-body import, the mirror of {@link #READ_BODY_FIELD}:
	 * {@code (ptr, len)}, "take these octets, they are the next chunk". No result -- the
	 * host cannot short-read a write, and a chunk it has not taken by the time the call
	 * returns is a chunk it never gets, since the module reuses the memory behind it.
	 */
	static final String WRITE_BODY_FIELD = ReactorEnvelope.RESPONSE_BODY_FIELD;

	/**
	 * The receive buffer handed to the import, in octets. One buffer serves every chunk
	 * of every request, so this is the whole per-instance cost of the body path -- big
	 * enough that an ordinary request crosses in one read, small enough to be free.
	 */
	static final int CHUNK_BYTES = 65536;

	private HttpReactorInliner() {
	}

	/**
	 * The {@code --no-wasi} lowering of the {@code rontolisp:http-handler} DIRECTIVE
	 * itself: a reactor owns no socket, so "serve this handler" can only mean the
	 * host-driven transport -- exactly the leg {@code clack:clackup} already takes there
	 * ({@code #+rontolisp-reactor}). Every {@code (rontolisp:http-handler 'name ...)}
	 * call becomes
	 *
	 * <pre>{@code
	 * (progn (rontolisp::%http-reactor-register (function name) :buffered)
	 *        (rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch "handle-request"))
	 * }</pre>
	 *
	 * and the existing pipeline does the rest: {@link #process} lowers the marker and
	 * synthesizes the {@code handle-request} wasm-export, {@link HttpReactorLibrary} /
	 * {@code HttpServerLibrary} splice the transport and the server value model, and the
	 * transport resolves an async-defun handler's future at its boundary -- so ONE
	 * {@code http-handler} source serves a socket on the interpreter/JVM, wasi:http under
	 * {@code --component}, and the host envelope on a reactor. The port argument is
	 * dropped unevaluated (a reactor host owns the listening side); the {@code :raw-body}
	 * mode is NOT -- it rides the registration, so the directive's default
	 * ({@code :stream}, rontolisp's own asynchronous body) means on a reactor what it
	 * means everywhere else. Called by the CLI for {@code --no-wasi} WASM builds (both
	 * core-module and reactor component), before the serve-mode switch reads the program.
	 * @param program the top-level forms
	 * @return the program with every directive lowered; unchanged when none is present
	 */
	public static List<LispVal> lowerHttpHandler(List<LispVal> program) {
		boolean[] found = new boolean[1];
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(lowerDirective(form, found));
		}
		return found[0] ? out : program;
	}

	private static LispVal lowerDirective(LispVal form, boolean[] found) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.QUOTE.equals(head.name())) {
			return form;
		}
		if (isHttpHandlerDirective(cons)) {
			found[0] = true;
			return LispReader
				.readAllFromString("""
						(progn (rontolisp::%%http-reactor-register (function %s)%s)
						       (rontolisp::%%http-reactor 'rontolisp::%s "%s"))
						""".formatted(directiveHandlerName(cons), directiveRawBodyMode(cons),
						HttpReactorLibrary.DISPATCH, EXPORT_NAME), Features.INTERPRETER)
				.get(0);
		}
		LispVal car = lowerDirective(cons.car(), found);
		LispVal cdr = lowerDirective(cons.cdr(), found);
		if (car == cons.car() && cdr == cons.cdr()) {
			return form;
		}
		return new LispCons(car, cdr);
	}

	private static boolean isHttpHandlerDirective(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.HTTP_HANDLER.equals(qn.member());
	}

	// The directive's optional (:raw-body :stream|:buffered) pair, as the registration's
	// trailing argument (empty for the default). The pair is a COMPILE-TIME literal on
	// every backend, so reading it here rather than evaluating it is the contract, not a
	// shortcut; an unknown value is left to the transports' own validation, which is
	// where the message a user sees comes from.
	private static String directiveRawBodyMode(LispCons directive) {
		List<LispVal> args = directive.toList();
		for (int i = 2; i + 1 < args.size(); i++) {
			if (args.get(i) instanceof LispSymbol key && key.isKeyword()
					&& LispNames.RAW_BODY_KEYWORD.equalsIgnoreCase(key.name())
					&& args.get(i + 1) instanceof LispSymbol mode
					&& LispNames.BUFFERED_KEYWORD.equalsIgnoreCase(mode.name())) {
				return " " + LispNames.BUFFERED_KEYWORD.toLowerCase(Locale.ROOT);
			}
		}
		return "";
	}

	// The directive's contract everywhere: a QUOTED literal handler name.
	private static String directiveHandlerName(LispCons directive) {
		if (directive.cdr() instanceof LispCons handlerCell && handlerCell.car() instanceof LispCons quote
				&& quote.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& quote.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		throw new UnsupportedOperationException(LispNames.HTTP_HANDLER
				+ " expects a quoted literal handler name (e.g. (rontolisp:http-handler 'handle 8080)), got: "
				+ directive.print());
	}

	/**
	 * Lowers every {@code rontolisp::%http-reactor} marker to {@code nil} and appends the
	 * bridge + {@code wasm-export} the first one asks for. Returns the program unchanged
	 * on a non-WASM backend and when no marker is present.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @param reactor whether the module is a REACTOR -- {@code --no-wasi}, the target
	 * {@code :rontolisp-reactor} names. It decides the BODIES, not the export: a WASI
	 * command module can carry the marker too ({@code clack-handler-reactor} is
	 * host-driven on every backend, so a program may drive its own {@code dispatch}
	 * in-process to develop a Worker), and there the host is {@code wasmtime run}, which
	 * satisfies no {@code env.*} import. Such a module keeps the in-band envelope; only a
	 * reactor, whose whole entry point IS a host call, takes the bodies out of it.
	 * @param boundary which shape of that reactor boundary was asked for
	 * ({@code --host-boundary}). {@link HostBoundary#ENVELOPE} keeps the bodies in band
	 * on a backend that COULD take them out, which is the one thing the two parameters
	 * above cannot express: they say what the target is capable of, this says what was
	 * wanted.
	 * @return the program, with the reactor export synthesized when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend, boolean reactor,
			HostBoundary boundary) {
		if (backend == WitExportDirective.Backend.OTHER) {
			return program;
		}
		String[] marker = new String[2];
		List<LispVal> rewritten = new ArrayList<>(program.size());
		for (LispVal form : program) {
			rewritten.add(lower(form, marker));
		}
		if (marker[0] == null) {
			return program;
		}
		if (declaresExport(program, marker[1])) {
			// The program already exports that name itself -- the pre-clackup shape,
			// where the user writes the wasm-export and calls `handle` from it. It has
			// taken the entry point over, so there is nothing to synthesize; adding the
			// bridge anyway would emit a module with a DUPLICATE export name, which no
			// engine will compile. The marker still has to go (nothing defines it).
			return rewritten;
		}
		List<LispVal> out = new ArrayList<>(rewritten);
		// The head is a JSON request string in, a JSON response string out -- the
		// handler backend's documented API, so the boundary types are fixed here rather
		// than being another thing the marker carries.
		String bodyArgument = bodyOutOfBand(backend, reactor, boundary)
				? " (function " + CHUNK_BRIDGE + ") (function " + WRITE_BRIDGE + ")" : "";
		String bridge = """
				%s(defun %s (%%reactor-json) (%s %%reactor-json%s))
				(rontolisp:wasm-export '%s :as "%s" :params '(:string) :returns :string)
				""".formatted(bodyImport(backend, reactor, boundary), DISPATCH_BRIDGE, marker[0], bodyArgument,
				DISPATCH_BRIDGE, marker[1]);
		out.addAll(LispReader.readAllFromString(bridge, Features.INTERPRETER));
		return out;
	}

	// Whether this build takes the bodies OUT of the envelope, in BOTH directions. Two
	// halves, and they answer different questions.
	//
	// CAN it: Preview 1 core modules only, and the reason is the boundary type, not the
	// transport: a :bytes import is a wasm-import (which --component rejects outright,
	// its host functions going through the canonical ABI instead) over a packed array
	// (which --no-gc has no representation for). So a reactor COMPONENT keeps both
	// bodies inside the envelope's "body" keys -- correct, just paying the copies, and
	// unable to carry binary either way. Re-evaluate when the component path grows a
	// list<u8> lift: `.kb/wit.md` / `.kb/wasm-import.md` say what is missing, and nothing
	// else about the split is Preview-1-specific -- the Lisp half already runs on every
	// backend. A WASI COMMAND module keeps them too, for a different reason -- see the
	// `reactor` parameter above: it has no host to import from.
	//
	// WAS it asked for: --host-boundary. Everything above says the split is possible;
	// only the flag says it is wanted. A Worker that fetches one JSON document and
	// answers one has no use for a cursor, and the boundary it pays for should be the one
	// with nothing in it (`.kb/wasm-import.md`, the boundary table).
	private static boolean bodyOutOfBand(WitExportDirective.Backend backend, boolean reactor, HostBoundary boundary) {
		return reactor && backend == WitExportDirective.Backend.WASM_GC && boundary.bodiesOutOfBand();
	}

	// The two body imports and the thunks over them, or nothing on a backend that keeps
	// the in-band body. Both thunks call their import DIRECTLY rather than taking it as
	// #'value: the build's suspending-import report follows calls, and an escaped import
	// widens its answer to "any export may suspend".
	//
	// The write side is the mirror of the read side and is DELIBERATELY not its
	// symmetric spelling: a chunk crossing out is a :bytes PARAMETER (the module owns the
	// octets and the host takes them), where a chunk crossing in is a :bytes RESULT into
	// a buffer the module passes. Both are the same rule -- the caller owns the memory --
	// applied to the two directions.
	private static String bodyImport(WitExportDirective.Backend backend, boolean reactor, HostBoundary boundary) {
		if (!bodyOutOfBand(backend, reactor, boundary)) {
			return "";
		}
		return """
				(rontolisp:wasm-import '%s :from "%s" :as "%s" :params '() :returns :bytes :async t)
				(defun %s ()
				  (let ((%%reactor-buf (rontolisp::%%http-reactor-buffer %d)))
				    (rontolisp::%%http-reactor-chunk %%reactor-buf (%s %%reactor-buf))))
				(rontolisp:wasm-import '%s :from "%s" :as "%s" :params '(:bytes) :returns :void :async t)
				(defun %s (%%reactor-chunk)
				  (%s (rontolisp::%%http-reactor-octets %%reactor-chunk)))
				""".formatted(READ_BODY_IMPORT, READ_BODY_MODULE, READ_BODY_FIELD, CHUNK_BRIDGE, CHUNK_BYTES,
				READ_BODY_IMPORT, WRITE_BODY_IMPORT, READ_BODY_MODULE, WRITE_BODY_FIELD, WRITE_BRIDGE,
				WRITE_BODY_IMPORT);
	}

	// Rewrites every NESTED (rontolisp::%http-reactor 'name "export") call inside the
	// form to nil, recording the first one's arguments into holder. Quoted data is left
	// untouched; unchanged subtrees keep their identity (no needless rebuild).
	private static LispVal lower(LispVal form, String[] holder) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.QUOTE.equals(sym.name())) {
			return form;
		}
		if (isMarker(cons)) {
			if (holder[0] == null) {
				parse(cons, holder);
			}
			return LispNil.INSTANCE;
		}
		LispVal car = lower(cons.car(), holder);
		LispVal cdr = lower(cons.cdr(), holder);
		if (car == cons.car() && cdr == cons.cdr()) {
			return form;
		}
		return new LispCons(car, cdr);
	}

	// Whether a top-level rontolisp:wasm-export directive already claims the export
	// name. The name derivation mirrors WasmExportCompiler.Decl: the :as alias when
	// present, otherwise the lowercased unqualified member of the quoted Lisp name --
	// duplicated rather than imported because `eval` must not depend on `codegen.wasm`.
	private static boolean declaresExport(List<LispVal> program, String exportName) {
		for (LispVal form : program) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)) {
				continue;
			}
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
			if (qn == null || !LispNames.RONTOLISP_PKG.equals(qn.pkg()) || !LispNames.WASM_EXPORT.equals(qn.member())) {
				continue;
			}
			if (exportName.equals(declaredExportName(cons))) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable String declaredExportName(LispCons directive) {
		List<LispVal> args = directive.toList();
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol key && key.isKeyword() && ":AS".equalsIgnoreCase(key.name())
					&& args.get(i + 1) instanceof LispString alias) {
				return alias.value();
			}
		}
		if (args.size() < 2 || !(args.get(1) instanceof LispCons quote) || !(quote.car() instanceof LispSymbol q)
				|| !LispNames.QUOTE.equals(q.name()) || !(quote.cdr() instanceof LispCons rest)
				|| !(rest.car() instanceof LispSymbol name)) {
			return null;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name.name());
		return (qn == null ? name.name() : qn.member()).toLowerCase(Locale.ROOT);
	}

	private static boolean isMarker(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.HTTP_REACTOR.equals(qn.member());
	}

	// (rontolisp::%http-reactor 'dispatch "export-name") -> holder = {dispatch name,
	// export name}. Both arguments are literals by contract: the whole point of the
	// marker is that the export is decided at compile time.
	private static void parse(LispCons cons, String[] holder) {
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(LispNames.HTTP_REACTOR
					+ " expects a quoted dispatcher name and an export name, got: " + cons.print());
		}
		if (!(args.get(1) instanceof LispCons quote && quote.car() instanceof LispSymbol q
				&& LispNames.QUOTE.equals(q.name()) && quote.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name)) {
			throw new UnsupportedOperationException(LispNames.HTTP_REACTOR
					+ " expects a quoted dispatcher name (e.g. 'dispatch), got: " + args.get(1).print());
		}
		if (!(args.get(2) instanceof LispString exportName)) {
			throw new UnsupportedOperationException(
					LispNames.HTTP_REACTOR + " expects a literal string export name, got: " + args.get(2).print());
		}
		holder[0] = name.name();
		holder[1] = exportName.value();
	}

}
