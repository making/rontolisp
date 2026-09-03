package am.ik.rontolisp.cli;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.CompileTimeBoundp;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.AppKitLibrary;
import am.ik.rontolisp.eval.DistClient;
import am.ik.rontolisp.eval.EnvironmentLibrary;
import am.ik.rontolisp.eval.ExitLibrary;
import am.ik.rontolisp.eval.FfiInterop;
import am.ik.rontolisp.eval.CheckpointLibrary;
import am.ik.rontolisp.eval.GeomLibrary;
import am.ik.rontolisp.eval.GrayStreamsLibrary;
import am.ik.rontolisp.eval.HostFetchLibrary;
import am.ik.rontolisp.eval.HttpLibrary;
import am.ik.rontolisp.eval.HttpReactorInliner;
import am.ik.rontolisp.eval.HttpReactorLibrary;
import am.ik.rontolisp.eval.HttpServerLibrary;
import am.ik.rontolisp.eval.JsonLibrary;
import am.ik.rontolisp.eval.SafetensorsLibrary;
import am.ik.rontolisp.eval.LibraryDefunPruner;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.MetalLibrary;
import am.ik.rontolisp.eval.SceneLibrary;
import am.ik.rontolisp.eval.SocketsLibrary;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.StdinLibrary;
import am.ik.rontolisp.eval.TlsLibrary;
import am.ik.rontolisp.eval.TorchLibrary;
import am.ik.rontolisp.eval.UnreadCharLibrary;
import am.ik.rontolisp.eval.UrlLibrary;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.eval.UsocketLibrary;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.eval.WaitForLibrary;
import am.ik.rontolisp.eval.WitExportInliner;
import am.ik.rontolisp.eval.WitImportInliner;
import am.ik.rontolisp.eval.WitLibrary;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The compile path's shared FRONT END: everything between the source text and the point
 * where a backend takes over -- the read (with the target's feature set), the
 * {@code (load ...)} inlining, user-macro expansion, the whole library splice chain, the
 * WIT directive lowerings, the compile-time {@code boundp} fold and the library
 * tree-shaker.
 * <p>
 * It is one class rather than a stretch of {@link RontoLispCli} because the passes are
 * ORDER-CRITICAL and are needed by more than one caller: the CLI's four backends, and
 * {@link JvmSourceCompiler}, through which an embedder (the Maven plugin) compiles a
 * library without a command line. Splitting it per backend would duplicate the ordering,
 * which is exactly the thing that must not drift.
 */
final class CompileFrontend {

	private CompileFrontend() {
	}

	/**
	 * What the front end hands the backend: the fully expanded program, plus the two
	 * facts about it a backend re-reads and the feature set it was read with.
	 *
	 * @param program the expanded, spliced and pruned top-level forms
	 * @param serve whether the program is a {@code rontolisp:http-handler} component
	 * @param witWorld whether the program declared a {@code rontolisp:wit-export} world
	 * @param features the feature set the program was read with
	 */
	record Result(List<LispVal> program, boolean serve, boolean witWorld, Features features) {
	}

	/**
	 * Runs the front end.
	 * @param source the program text
	 * @param entryFile the path the text was read from, for diagnostics
	 * @param baseDir the directory relative paths resolve against
	 * @param systemPath the ASDF system search path
	 * @param dists the Quicklisp-format distributions
	 * @param declaredFeatures the read-time features the user declared
	 * ({@code --feature})
	 * @param wasm whether the target is a {@code .wasm} output
	 * @param servlet whether the target is a {@code .war} output (JVM servlet mode)
	 * @param dynamic {@code --dynamic}
	 * @param component {@code --component}
	 * @param noWasi {@code --no-wasi}
	 * @param noGc {@code --no-gc}
	 * @param hostFetch {@code --host-fetch}
	 * @param hostBoundary {@code --host-boundary}, or {@code null} for the default
	 * @param reentrant {@code --reentrant}
	 * @param noPrune {@code --no-prune}
	 * @return the expanded program and what a backend needs to know about it
	 */
	static Result run(String source, @Nullable String entryFile, @Nullable String baseDir, List<String> systemPath,
			DistClient dists, List<String> declaredFeatures, boolean wasm, boolean servlet, boolean dynamic,
			boolean component, boolean noWasi, boolean noGc, boolean hostFetch, @Nullable HostBoundary hostBoundary,
			boolean reentrant, boolean noPrune) {
		HostBoundary boundary = hostBoundary == null ? HostBoundary.ENVELOPE : hostBoundary;
		// Inline top-level (load "path") forms at compile time: the compilers collect
		// defuns in a static pass that a runtime load cannot feed, so a program split
		// across files (a console driver loading a rendering-free core) would otherwise
		// fail to compile. The interpreter loads at runtime instead, so this is
		// compile-path only.
		// Expand user macros (defmacro) after inlining: the definitions are consumed
		// and every call site is fully expanded by the macro-time interpreter, so the
		// compilers see only ordinary forms. The interpreter path expands natively at
		// evaluation time instead.
		// Then splice the Lisp-source JSON library when the program references
		// rontolisp:json-parse / rontolisp:json-stringify, rewriting the call sites
		// to the fixed-arity helpers, the Lisp-source linalg library when the
		// program references the linalg package, and the Lisp-source URL library
		// when the program references rontolisp:url-* / query-param* (the
		// interpreter path instead loads the libraries lazily inside LispEvaluator).
		// The whole frontend reads with the target backend's feature set, so
		// #+rontolisp-jvm / #+rontolisp-wasm conditionals select per-backend code --
		// and #+rontolisp-reactor selects reactor-mode code: the
		// clack-handler-rontolisp shim's run picks the http-handler directive or the
		// %http-reactor marker with it. Reactor mode is --no-wasi (a Preview 1 module
		// with no WASI imports, or -- with --component -- a reactor component that
		// imports nothing) or --no-gc (a pure-compute reactor with or without the
		// component wrap).
		boolean reactor = noWasi || noGc;
		// And a .war output reads with #+rontolisp-servlet active: the reactor
		// precedent, a target-describing feature rather than an internal compiler flag,
		// because the clack-handler-rontolisp shim branches on features and on nothing
		// else (.kb/clack.md).
		Features features = wasm ? (reactor ? Features.WASM_REACTOR : Features.WASM)
				: (servlet ? Features.JVM_SERVLET : Features.JVM);
		// And #+rontolisp-component selects code for the COMPONENT BOUNDARY, which is a
		// different boundary rather than a different backend: a component's host
		// functions cross the canonical ABI, so the core-module directives
		// (rontolisp:wasm-import / a :bytes type) are refused there and a source that
		// wants one needs a way to say "not on this target" -- the reactor features
		// above cannot, since a reactor component carries them too. Additive, like every
		// other target-describing feature.
		if (component && wasm) {
			features = features.with(List.of(Features.COMPONENT));
		}
		// And #+rontolisp-body-imports selects the code a HAND-WRITTEN reactor needs only
		// where the :bytes body imports really exist: the --no-wasi wasm-GC core module,
		// built with the streaming boundary. It follows the FLAG, which is what no
		// combination of target features could do -- and it says the thing itself, where
		// the guard it replaces spelled out the targets that cannot carry the imports and
		// quietly got --no-gc wrong.
		if (wasm && noWasi && !component && !noGc && boundary.bodiesOutOfBand()) {
			features = features.with(List.of(Features.BODY_IMPORTS));
		}
		// And LAST, whatever the user declared with --feature: names a portable library's
		// #+ chain expects from the HOST implementation and no rontolisp target could
		// answer for (RontoLispCli.declaredFeatures). It goes on top of the target set
		// rather than into it, because the target features describe the build and the
		// build -- not the command line -- decides those; the flag refuses to name one.
		// Everything downstream reads with the result, including the (load ...) inlining
		// and the ASDF components below, and the compiled program's run-time *features*
		// is seeded from it (WasmLispCompiler.runtimeFeatures / JvmLispCompiler).
		features = features.with(declaredFeatures);
		WitExportDirective.Backend witBackend = witBackend(wasm, noGc, component);
		// (rontolisp:wit-import "kv.wit" :interface "..."): bind a WIT interface's
		// functions. Unlike wit-export this runs BEFORE UserMacroExpander, because the
		// names it binds live in a package the WIT names -- the (defpackage kv ...) it
		// synthesizes must exist before any pass resolves a kv:get call site, and the
		// macro expander resolves every top-level form through its own PackageResolver.
		// It
		// needs nothing macro expansion produces: a wit-import is checked against a WIT
		// file, not against the program.
		// #. read-time eval on the compile path: the marker read wraps each datum in a
		// (%read-eval datum) marker that UserMacroExpander later resolves against the
		// macro-time evaluator, per top-level form (the interpreter's loadFile timing).
		List<LispVal> read = source.contains("#.") ? LispReader.readAllWithReadEvalMarkers(source, features, entryFile)
				: LispReader.readAllFromString(source, features, entryFile);
		List<LispVal> loaded = LoadInliner.inline(read, SourceLoader.fileSystem(), baseDir, systemPath, features,
				dists);
		// Expand the (rontolisp:async (defun ...)) wrapper before anything scans for
		// definitions: HttpLibrary's handler reachability, WitExportInliner's defun
		// checks and the library pruner all recognize async-defun, never the sugar.
		loaded = LispMacroExpander.rewriteAsyncSugar(loaded);
		// objc:, appkit:, metal: and scene: have no WASM lowering and never will (no
		// foreign function API, no AppKit, no Metal); the JVM backend carries the
		// binding as an embedded blob (JvmObjcRuntimeBuilder). Refuse the WASM outputs
		// here, after load inlining, so a (load ...)-ed file is caught too and the error
		// names the reference rather than an undefined function somewhere inside a
		// spliced library.
		String objcReference = wasm ? AppKitLibrary.firstObjcReference(loaded) : null;
		if (objcReference != null) {
			throw new IllegalArgumentException("Cannot compile: " + objcReference
					+ " -- the objc:, appkit:, metal: and scene: packages run on the interpreter (java -jar, or "
					+ "the rontolisp binary) and in a compiled .class or .jar, not in a .wasm");
		}
		// ffi: has no WASM lowering and never will either (no foreign function API in
		// any WASM runtime); refused the same way, after load inlining, so the error
		// names the reference.
		String ffiReference = wasm ? FfiInterop.firstFfiReference(loaded) : null;
		if (ffiReference != null) {
			throw new IllegalArgumentException("Cannot compile: " + ffiReference
					+ " -- the ffi: package runs on the interpreter (java -jar, or the rontolisp binary) "
					+ "and in a compiled .class or .jar, not in a .wasm");
		}
		// Under --component the inliner also prunes the interface members the program
		// never references -- the core tree shaker cannot do that job even under
		// --optimize, because a WIT member costs a component-level import declaration and
		// a canon lower, not just a core function; --no-prune / --dynamic disable that,
		// like the library defun pruner.
		loaded = WitImportInliner.inline(loaded, baseDir, witBackend, SourceLoader.fileSystem(), !dynamic && !noPrune);
		// The --no-wasi (wasm-GC) reactor legs, BEFORE the serve-mode switch below reads
		// the program: a reactor owns no socket, so the rontolisp:http-handler directive
		// lowers to the host-driven transport (the same leg clack:clackup takes there),
		// which is also what lets the same http-handler source compile as a Worker; and
		// under --host-fetch, rontolisp:fetch gets the env.fetch lowering spliced when
		// the program fetches (before UserMacroExpander, so JsonLibrary and the prelude
		// pick up the splice's own call sites).
		if (wasm && noWasi && !noGc) {
			loaded = HttpReactorInliner.lowerHttpHandler(loaded);
			if (hostFetch) {
				loaded = HostFetchLibrary.process(loaded, boundary, reentrant);
			}
		}
		// Both rontolisp:fetch AND rontolisp:http-handler on the --component path are ONE
		// Lisp-source library (http.lisp) over a wit-imported wasi:http@0.3.0 surface,
		// spliced HERE -- right after WitImportInliner, which http.lisp's own wit-import
		// directives are lowered against (HttpLibrary does that itself), and before
		// UserMacroExpander, which its cond/handler-case bodies need. The splice's member
		// filter follows the reachable half, so a fetch-only program binds no serve
		// member and vice versa. The interpreter/JVM keep java.net.http / HttpServer;
		// Preview 1 has neither.
		boolean serve = component && HttpHandlerInliner.usesHttpHandler(loaded);
		// --no-wasi under --component asks for a component that imports NOTHING, and
		// the wasi:*-binding library splices below exist precisely to give a component
		// its WASI surface (http.lisp / wait.lisp / sockets.lisp / stdin.lisp /
		// environment.lisp are each the wit-imported wasi:* surface of their
		// primitives). ONE decision here gates all five: they see the Preview 1
		// backend, whose primitives already honor the --no-wasi contract (the fd_write
		// sink discards output, the rest trap or signal at call time;
		// .kb/wasm-export-no-wasi.md). wit-import/wit-export lowering keeps the real
		// backend -- a USER wit-import under --no-wasi is rejected by the compiler,
		// with a message naming both sides.
		WitExportDirective.Backend spliceBackend = component && noWasi ? WitExportDirective.Backend.WASM_GC
				: witBackend;
		// serve + rontolisp:wit-export is an error (a serve component's only export is
		// wasi:http/handler); the check fires below on the macro-expanded program.
		// Splicing http.lisp's %serve-handle wasm-export first would surface as a
		// DIFFERENT error (wit-export forbids a hand-written wasm-export beside it), so
		// gate the serve half off when a wit-export world is present and let the clearer
		// guard win.
		boolean serveGlue = serve && !WitExportInliner.usesWitExport(loaded);
		// The :raw-body mode must be read BEFORE HttpLibrary rewrites the directive
		// away (the wasm path drops it); it decides both the synthesized
		// %serve-request-body in there and the splice filter below.
		boolean bufferBody = HttpLibrary.usesBufferedBody(loaded);
		loaded = HttpLibrary.process(loaded, spliceBackend, serveGlue);
		// The host-driven reactor's counterpart of that splice: a Clack handler
		// backend whose run stores the app and leaves a rontolisp::%http-reactor
		// marker (the clack-handler-reactor shim always; the clack-handler-rontolisp
		// shim under #+rontolisp-reactor) gets the marker
		// lowered to nil and the wasm-export of a bridge to its dispatcher
		// synthesized -- so a Worker source is (clack:clackup #'app :server
		// :rontolisp) and nothing else. A no-op on the interpreter and the JVM (the
		// shims do not even read the marker there).
		loaded = HttpReactorInliner.process(loaded, witBackend, noWasi, boundary, reentrant);
		// The shared reactor machinery behind BOTH handler backends
		// (http-reactor.lisp: the one app store, the JSON envelope over
		// %http-make-env / %http-normalize-response): spliced for EVERY backend
		// whenever the program references it -- the synthesized bridge above does,
		// and so do the backends' run/handle/dispatch. Before HttpServerLibrary,
		// whose entry points the machinery calls; JsonLibrary later picks up its
		// json-parse / json-stringify call sites.
		loaded = HttpReactorLibrary.process(loaded);
		// The server-side HTTP value model (http-server.lisp): the Clack environment a
		// handler receives and the Clack response it returns, written once in rontolisp
		// so every backend agrees by construction. Spliced for EVERY backend (unlike
		// http.lisp, which is the --component transport) whenever the program serves,
		// and BEFORE GrayStreamsLibrary below, whose call-site rewrite the library's
		// bivalent :raw-body stream depends on. A default-mode (:raw-body :stream)
		// program gets the library without its buffered-body half.
		loaded = HttpServerLibrary.process(loaded, bufferBody);
		// rontolisp:wait-for on the --component path is the wait.lisp shim over a
		// wit-imported wasi:clocks/monotonic-clock@0.3.0 (a pending future the
		// scheduler settles). Spliced like http.lisp; a no-op elsewhere (the
		// interpreter/JVM keep their CompletableFuture timer, Preview 1 keeps the
		// compile error).
		loaded = WaitForLibrary.process(loaded, spliceBackend);
		// The CLIENT tls built-ins (tls-connect / tls-upgrade) on the --component path
		// are the tls.lisp library over a wit-imported wasi:tls@0.3.0-draft. It rides
		// sockets.lisp's entry table, so it must splice BEFORE SocketsLibrary: the
		// spliced forms reference rontolisp:tcp-connect, which fires the sockets
		// trigger for a program that only names tls. A no-op elsewhere (the
		// interpreter/JVM keep SSLSocket, Preview 1 keeps the compile error; the
		// tls-listen family is a compile error on every WASM target -- the wasi:tls
		// proposal has no server interface).
		loaded = TlsLibrary.process(loaded, spliceBackend);
		// The rontolisp:tcp-* built-ins on the --component path are the sockets.lisp
		// library over a wit-imported wasi:sockets/types@0.3.0 (this splice replaced
		// the hand-written sockets adapter). Spliced like http.lisp; a no-op elsewhere
		// (the interpreter/JVM keep java.net.Socket, Preview 1 keeps the compile
		// error). The trigger includes any usocket: reference: the usocket shim rides
		// tcp-*, and its own splice runs later in this pipeline.
		loaded = SocketsLibrary.process(loaded, spliceBackend);
		// Component stdin over wit-imported wasi:cli/stdin@0.3.0 (stdin.lisp), bound
		// FROM the fixed import block. Two shapes: the %stdin-*-or-raw-f helpers
		// sockets.lisp's dispatchers fall through to (a serve program gets the
		// raw-passthrough stub -- its service world has no stdin), and the full
		// dispatch splice for an ASYNC stdin-reading program, whose async-context
		// reads then promote to awaits. A non-async stdin program is left on the
		// preview1 adapter's stdin branch, byte-identical. Must run AFTER
		// SocketsLibrary (it keys on sockets.lisp's dispatchers being present).
		loaded = StdinLibrary.process(loaded, spliceBackend, serve);
		// The WIT runtime (wit.lisp: the provider registry, rontolisp:wit-provide and the
		// rontolisp:wit-error condition -- the provider MECHANISM, and no provider for
		// any
		// concrete interface) backs the %wit-call bodies the inliner just synthesized for
		// the interpreter/JVM boundary. On the WASM backends it splices nothing: there
		// the
		// bindings ARE rontolisp:wasm-import directives and the host is the provider.
		// GrayStreamsLibrary.process rewrites write-string/write-char call sites onto
		// the Gray dispatch helpers when the program uses the protocol (and splices
		// gray.lisp if no load already did), so a CLOS instance stream reaches the
		// generics in compiled programs like it does on the interpreter.
		// UnreadCharLibrary.process splices the handle-side pushback of unread-char and
		// rewrites the character-read call sites onto it. LAST of the four, so a call
		// site any of them introduced -- a Gray dispatch helper's handle FALLBACK above
		// all -- reaches the cell too; a program that never names unread-char is
		// returned unchanged.
		// TorchLibrary runs BEFORE LinalgLibrary so the linalg: references inside the
		// spliced torch definitions pull the linalg library in too, and GeomLibrary
		// (solid modeling over the same kernels) sits beside it for the same reason.
		// AppKitLibrary splices appkit.lisp (the widget layer over the objc: verbs) the
		// same way, so a JVM class compiled from an appkit: program carries the widgets
		// and, through their objc:send, gates the embedded binding on.
		// The macOS three run in DEPENDENCY order, innermost first: SceneLibrary (the
		// 3-D viewer) before MetalLibrary (the drawing surface it is written over)
		// before GeomLibrary/LinalgLibrary (the model and the kernels both reach for)
		// and before AppKitLibrary (metal:run's clock is appkit:timer).
		// JsonLibrary runs AFTER GeomLibrary (geom:read-gltf parses its JSON chunk
		// through rontolisp:json-parse, so the splice introduces the reference) and
		// still after the HTTP passes above, whose handlers it also rewrites.
		// The checkpoint readers run FIRST of all: SafetensorsLibrary (the reader)
		// before CheckpointLibrary (the staging it is written over), and both before
		// JsonLibrary and the prelude, which supply the json-parse and
		// %octets-to-string / widen-float-bits the spliced definitions reach for.
		List<LispVal> program = UnreadCharLibrary
			.process(WitLibrary.process(UsocketLibrary.process(GrayStreamsLibrary.process(LispPreludeLibrary.process(
					UrlLibrary.process(AppKitLibrary.process(JsonLibrary.process(LinalgLibrary.process(GeomLibrary
						.process(MetalLibrary.process(SceneLibrary.process(TorchLibrary.process(CheckpointLibrary
							.process(SafetensorsLibrary.process(UserMacroExpander.expand(loaded))))))))))),
					features)))));
		// uiop:getenv on the --component path is environment.lisp over a wit-imported
		// wasi:cli/environment@0.3.0 -- bound FROM the fixed import block on the base /
		// sockets variants and as an appended user import under serve, whose service
		// world declares no environment interface. Spliced like the libraries above; a
		// no-op elsewhere (the interpreter/JVM keep System.getenv, Preview 1 keeps the
		// host-filled environ buffer scan). It runs AFTER the whole splice chain (and
		// after user-macro expansion) because a getenv call any of them introduces has
		// to be seen too: the prelude's uiop:default-temporary-directory reads TMPDIR,
		// and with this pass upstream of the splice a smart-buffer program failed the
		// component compile with "compiled without EnvironmentLibrary.process".
		program = EnvironmentLibrary.process(program, spliceBackend);
		// uiop:quit on the WASM backends is exit.lisp over wasi_snapshot_preview1's
		// proc_exit (Preview 1) / wit-imported wasi:cli/exit@0.3.0 (--component, an
		// appended user import: the fixed block does not declare that interface). Same
		// position and same reason as the environment splice above -- a quit any earlier
		// pass introduced has to be seen too -- and a no-op elsewhere (the interpreter
		// raises its exit signal, the JVM emits System.exit).
		program = ExitLibrary.process(program, spliceBackend, features);
		// Splice the Lisp-source vec library (the scalar reference over the packed
		// double-float array type) when the program references the vec package. The
		// --no-gc scalar WASM backend is the exception: it has no general array type and
		// lowers the whole vec: surface to native fixed-width WASM SIMD itself
		// (NoGcWasmCompiler), so it must NOT get the splice.
		if (!(wasm && noGc)) {
			program = VecLibrary.process(program);
		}
		// (rontolisp:wit-export "world.wit"): check the program against the WIT world it
		// claims to implement and expand the directive into the rontolisp:wasm-export
		// directives the world declares -- the backends see nothing new. It runs here
		// because every defun (including a load-spliced or macro-produced one) is now a
		// literal top-level form, and because the synthesized directives must still count
		// as pruning roots below.
		boolean witWorld = WitExportInliner.usesWitExport(program);
		program = WitExportInliner.inline(program, baseDir, witBackend, SourceLoader.fileSystem());
		// Decide the (boundp 'name) probes whose answer the top-level order already fixes
		// (compiler/CompileTimeBoundp), and collapse the guards they were testing. It has
		// to happen BEFORE the tree-shaker below, not just inside the compilers: the
		// portable (unless (boundp '+k+) (defconstant +k+ v)) is what keeps a library's
		// constants from being top-level definers, and an unreachable one the shaker
		// cannot see stays in the artifact. Packages are still unresolved here, so only
		// the "unbound" direction is decided; the compilers run the pass again once the
		// spellings are canonical.
		program = CompileTimeBoundp.fold(program, dynamic, false);
		// Drop spliced library definitions unreachable from the user program (the AST
		// tree-shaker; see LibraryDefunPruner). Skipped under --dynamic (late binding
		// can resolve any name at runtime) and --no-prune (the explicit escape hatch) --
		// but the ASDF provenance markers the pruner reads are dropped either way, so
		// those two flags emit the artifact they emitted before the markers existed.
		program = (!dynamic && !noPrune) ? LibraryDefunPruner.prune(program)
				: LibraryDefunPruner.stripSystemMarkers(program);
		return new Result(program, serve, witWorld, features);
	}

	// The backend a rontolisp:wit-export world is checked against: only the WASM backends
	// impose the export boundary's backend-specific rules (s64 needs --no-gc, an async
	// func cannot be lifted by the --no-gc reactor). Compiling to a .class checks the
	// contract but exports nothing, like the interpreter. wit-export treats WASM_GC and
	// WASM_COMPONENT identically; wit-import lowers them differently (Preview 1 core
	// imports vs the canonical-ABI lower).
	private static WitExportDirective.Backend witBackend(boolean wasm, boolean noGc, boolean component) {
		if (!wasm) {
			return WitExportDirective.Backend.OTHER;
		}
		if (noGc) {
			return WitExportDirective.Backend.WASM_NO_GC;
		}
		return component ? WitExportDirective.Backend.WASM_COMPONENT : WitExportDirective.Backend.WASM_GC;
	}

}
