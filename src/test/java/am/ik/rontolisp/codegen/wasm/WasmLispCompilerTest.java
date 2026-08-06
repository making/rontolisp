package am.ik.rontolisp.codegen.wasm;

import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.HttpLibrary;
import am.ik.rontolisp.eval.SocketsLibrary;
import am.ik.rontolisp.eval.StdinLibrary;
import am.ik.rontolisp.eval.WitLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WasmLispCompilerTest {

	private byte[] compile(String lispCode) {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		return new WasmLispCompiler().compile(program);
	}

	private byte[] compileComponent(String lispCode) {
		// Splice fetch.lisp when the program references rontolisp:fetch, mirroring the
		// CLI:
		// on the --component path fetch is a Lisp library over wit-imported wasi:http,
		// not a
		// special form, so a raw compile of a fetch program would fail to resolve it. A
		// no-op for every non-fetch program.
		List<LispVal> program = HttpLibrary.process(LispReader.readAllFromString(lispCode),
				WitExportDirective.Backend.WASM_COMPONENT, false);
		// The tcp built-ins are the sockets.lisp library over wit-imported
		// wasi:sockets the same way (a no-op for every non-socket program), followed
		// by the stdin machinery its dispatchers fall through to.
		program = SocketsLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		program = StdinLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, false);
		// uiop:getenv is environment.lisp over wit-imported wasi:cli/environment on this
		// path (a no-op for every program that never reads the environment).
		program = am.ik.rontolisp.eval.EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		// fetch.lisp's result wrappers call rontolisp::%wit-result, backed by wit.lisp --
		// spliced by WitLibrary, the same order the CLI runs them in.
		program = WitLibrary.process(program);
		return new WasmLispCompiler(false, true).compile(program);
	}

	private byte[] compileComponentOptimized(String lispCode) {
		List<LispVal> program = HttpLibrary.process(LispReader.readAllFromString(lispCode),
				WitExportDirective.Backend.WASM_COMPONENT, false);
		program = SocketsLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		program = StdinLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, false);
		program = am.ik.rontolisp.eval.EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		program = WitLibrary.process(program);
		return new WasmLispCompiler(false, true, false, OptimizeLevel.DEFAULT).compile(program);
	}

	/** The names of every instance the component imports, in declaration order. */
	private static List<String> componentImportNames(byte[] component) {
		List<String> names = new java.util.ArrayList<>();
		int[] p = { 8 };
		while (p[0] < component.length) {
			int id = component[p[0]++] & 0xff;
			int size = readLeb(component, p);
			int end = p[0] + size;
			if (id == 10) { // component import section
				int count = readLeb(component, p);
				for (int i = 0; i < count; i++) {
					p[0]++; // extern-name tag
					int len = readLeb(component, p);
					names.add(new String(component, p[0], len, java.nio.charset.StandardCharsets.UTF_8));
					p[0] += len;
					p[0]++; // extern descriptor sort
					readLeb(component, p); // its index
				}
			}
			p[0] = end;
		}
		return names;
	}

	private static int readLeb(byte[] buf, int[] p) {
		int value = 0;
		int shift = 0;
		while (true) {
			int b = buf[p[0]++] & 0xff;
			value |= (b & 0x7f) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
			shift += 7;
		}
	}

	@Test
	void aComponentAlwaysDeclaresTheWholeFixedWasiSurface() {
		// Without --optimize the core keeps all nine preview1 imports, so the adapter
		// keeps
		// every branch and the block keeps every interface -- the shape this builder has
		// always emitted.
		assertThat(componentImportNames(compileComponent("(print 1)"))).containsExactly("wasi:cli/types@0.3.0",
				"wasi:cli/stdout@0.3.0", "wasi:cli/stdin@0.3.0", "wasi:cli/environment@0.3.0",
				"wasi:clocks/types@0.3.0", "wasi:clocks/system-clock@0.3.0", "wasi:clocks/monotonic-clock@0.3.0",
				"wasi:filesystem/types@0.3.0", "wasi:filesystem/preopens@0.3.0", "wasi:random/random@0.3.0",
				"wasi:cli/stderr@0.3.0");
	}

	@Test
	void anOptimizedComponentImportsOnlyTheWasiInterfacesItCanReach() {
		// A printing program's core imports fd_write alone; the adapter is narrowed to
		// the
		// stdio-only implementation, so nothing reaches wasi:filesystem, wasi:clocks,
		// wasi:random, wasi:cli/environment or wasi:cli/stdin. stderr stays: fd 2 is a
		// runtime value, not an edge the shaker can follow.
		assertThat(componentImportNames(compileComponentOptimized("(print 1)"))).containsExactly("wasi:cli/types@0.3.0",
				"wasi:cli/stdout@0.3.0", "wasi:cli/stderr@0.3.0");
	}

	@Test
	void anOptimizedComponentThatOpensAFileKeepsTheFilesystemSurface() {
		// path_open is the only writer of the adapter's fd table, so importing it is what
		// makes the file arms of fd_write / fd_read live -- and with them
		// wasi:filesystem.
		assertThat(componentImportNames(compileComponentOptimized("""
				(with-open-file (s "x.txt" :direction :output) (format s "hi~%"))
				"""))).contains("wasi:filesystem/types@0.3.0", "wasi:filesystem/preopens@0.3.0")
			.doesNotContain("wasi:random/random@0.3.0", "wasi:clocks/system-clock@0.3.0");
	}

	@Test
	void anOptimizedComponentsEmittedWitDescribesThePrunedSurface() {
		// The emitted world and the emitted bytes come from ONE computation: a WIT that
		// still advertised the dropped interfaces would describe a component that does
		// not
		// exist. (WitOracleE2eTest byte-diffs the same text against wasm-tools.)
		List<LispVal> program = LispReader.readAllFromString("(print 1)");
		WasmLispCompiler compiler = new WasmLispCompiler(false, true, false, OptimizeLevel.DEFAULT);
		compiler.compile(program);

		assertThat(compiler.componentWit()).isEqualTo("""
				package root:component;

				world root {
				  import wasi:cli/types@0.3.0;
				  import wasi:cli/stdout@0.3.0;
				  import wasi:cli/stderr@0.3.0;

				  export wasi:cli/run@0.3.0;
				}
				package wasi:cli@0.3.0 {
				  interface types {
				    enum error-code {
				      io,
				      illegal-byte-sequence,
				      pipe,
				    }
				  }
				  interface stdout {
				    use types.{error-code};

				    write-via-stream: func(data: stream<u8>) -> future<result<_, error-code>>;
				  }
				  interface stderr {
				    use types.{error-code};

				    write-via-stream: func(data: stream<u8>) -> future<result<_, error-code>>;
				  }
				  interface run {
				    run: async func() -> result;
				  }
				}
				""");
	}

	@Test
	void conditionFormsCompileOnWasm() {
		// define-condition (top-level, spliced like defclass), make-condition,
		// typecase-on-conditions, with-slots and signal (unhandled -> nil) all compile;
		// a typed error traps like a plain %error.
		assertThat(compile("""
				(define-condition my-cond (error) ((v :initarg :v)))
				(print (signal "quiet"))
				(print (typecase (make-condition 'my-cond :v 1) (warning 'w) (error 'e) (t 'o)))
				(print (with-slots (v) (make-condition 'my-cond :v 9) v))
				""")).isNotEmpty();
	}

	@Test
	void unwindProtectCompilesInEhMode() {
		// The wasm exception-handling proposal: unwind-protect compiles into
		// a try_table (catch_all_ref) whose landing runs the cleanups and rethrows.
		// Running the output needs `wasmtime -W exceptions=y` (37+).
		assertThat(compile("(unwind-protect 1 2)")).isNotEmpty();
		assertThat(compileComponent("(unwind-protect 1 2)")).isNotEmpty();
	}

	@Test
	void handlerCaseAndIgnoreErrorsCompileInEhMode() {
		assertThat(compile("(handler-case 1 (error (e) 2))")).isNotEmpty();
		assertThat(compile("(ignore-errors 1)")).isNotEmpty();
		assertThat(compileComponent("(handler-case 1 (error (e) 2))")).isNotEmpty();
	}

	@Test
	void ehModeEmitsTagSectionAndPlainModuleDoesNot() {
		// The EH machinery (tag section id 13) is emitted ONLY when the program uses a
		// catching/cleanup form; a program without them stays byte-identical to a
		// build that never knew about EH and in particular carries no tag section.
		byte[] plain = compile("(print 1)");
		byte[] eh = compile("(print (ignore-errors 1))");
		assertThat(containsSection(plain, 13)).isFalse();
		assertThat(containsSection(eh, 13)).isTrue();
	}

	@Test
	void withStarFormsRideUnwindProtectAndFlipEhMode() {
		// The with-* expansions ride unwind-protect on WASM too (close on EVERY exit,
		// interpreter/JVM parity), so a with-* program
		// flips into EH mode (tag section present) and needs `wasmtime -W
		// exceptions=y` to run.
		byte[] wof = compile("(with-open-file (s \"f.txt\") (read-line s))");
		assertThat(wof).isNotEmpty();
		assertThat(containsSection(wof, 13)).isTrue();
		byte[] wots = compile("(print (with-output-to-string (s) (princ \"x\" s)))");
		assertThat(containsSection(wots, 13)).isTrue();
		assertThat(compile("(with-input-from-string (s \"a\") (read-line s))")).isNotEmpty();
	}

	@Test
	void typedErrorWithLambdaReportCompilesOutsideEhMode() {
		// A :report lambda's rendering rides an internally-generated
		// with-output-to-string; in a module without any literal catching/with-*
		// form it must keep the close-after-body shape and compile WITHOUT the tag
		// section (the gate scans the pre-expansion program).
		byte[] module = compile("""
				(define-condition rep-err (error) ((v :initarg :v :reader rep-err-v))
				  (:report (lambda (c s) (format s "bad ~a" (rep-err-v c)))))
				(error 'rep-err :v 1)
				""");
		assertThat(module).isNotEmpty();
		assertThat(containsSection(module, 13)).isFalse();
	}

	/**
	 * Returns whether the core module contains a top-level section with the given id
	 * (skipping section payloads, so an id byte inside a payload cannot false-match).
	 */
	private static boolean containsSection(byte[] module, int sectionId) {
		int p = 8;
		while (p < module.length) {
			int id = module[p++] & 0xff;
			int size = 0;
			int shift = 0;
			while (true) {
				int b = module[p++] & 0xff;
				size |= (b & 0x7f) << shift;
				if ((b & 0x80) == 0) {
					break;
				}
				shift += 7;
			}
			if (id == sectionId) {
				return true;
			}
			p += size;
		}
		return false;
	}

	@Test
	void fetchInPreview1ModeIsCompileError() {
		assertThatThrownBy(() -> compile("(rontolisp:fetch \"http://x/\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
	}

	@Test
	void httpHandlerInPreview1ModeCompilesToACallTimeError() {
		// In Preview 1 (no --component) the http-handler directive compiles to a
		// CALL-time error stub since todo-228 (the todo-195 socket policy: the
		// clack-handler-rontolisp shim carries the directive as dead code there,
		// and rejecting it would fail the whole clack graph). The "requires
		// --component" message is pinned end to end by ClackE2eTest's Preview 1
		// leg through handler-case; the component path is exercised in
		// WasmLispCompilerIntegrationTest.
		assertThat(compile("(defun h (r) nil) (rontolisp:http-handler 'h)")).isNotEmpty();
	}

	@Test
	void awaitCompilesInEveryMode() {
		// await compiles in Preview 1 mode too; unlike fetch it is not component-only.
		assertThat(compile("(print (rontolisp:await 42))")).isNotEmpty();
	}

	@Test
	void awaitOfFetchCompilesInComponentMode() {
		assertThat(compileComponent("(print (getf (rontolisp:await (rontolisp:fetch \"http://x/\")) :status))"))
			.isNotEmpty();
		assertThat(compileComponent("(let ((p (rontolisp:fetch \"http://x/\"))) (rontolisp:await p))")).isNotEmpty();
	}

	@Test
	void tcpBuiltinsInPreview1ModeAreCallTimeErrors() {
		// Preview 1 has no host sockets, but the call sites compile to CALL-TIME
		// errors (not compile errors) so a spliced library whose socket layer is
		// dead code still builds -- s-sql drags in cl-postgres without ever
		// opening a connection, and the pruner cannot drop cl-postgres'
		// defmethod-anchored socket chain.
		assertThat(compile("(rontolisp:tcp-connect \"127.0.0.1\" 7777)")).isNotEmpty();
		assertThat(compile("(rontolisp:tcp-listen 7777)")).isNotEmpty();
		assertThat(compile("(rontolisp:tcp-accept 0)")).isNotEmpty();
		assertThat(compile("(rontolisp:tcp-local-port 0)")).isNotEmpty();
	}

	@Test
	void tcpBuiltinsCompileInComponentMode() {
		assertThat(compileComponent("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (write-line "hi" client)
				  (print (read-line server))
				  (close server)
				  (close client)
				  (close listener))
				""")).isNotEmpty();
		// tcp-listen without a host (bind all interfaces) compiles too
		assertThat(compileComponent("(rontolisp:tcp-listen 7777)")).isNotEmpty();
	}

	@Test
	void promotedSocketReadHoistsOutOfADispatchDefunArgument() {
		// In an async context a socket read is promoted to (await (%read-line-future s))
		// while write-line redirects onto the library's %io-write-line dispatch defun, so
		// the await lands in that call's argument. %io-write-line is an ordinary defun
		// whose arguments are all value positions, so the await must hoist to a spine
		// position exactly as it does for a head the rewrite leaves alone (princ below).
		assertThat(compileComponent("""
				(let ((s (rontolisp:tcp-connect "127.0.0.1" 7777)))
				  (write-line (read-line s)))
				""")).isNotEmpty();
		assertThat(compileComponent("""
				(let ((s (rontolisp:tcp-connect "127.0.0.1" 7777)))
				  (princ (read-line s)))
				""")).isNotEmpty();
	}

	@Test
	void tcpRejectsWrongArgCounts() {
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-connect \"127.0.0.1\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-CONNECT expects 2 arguments");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-listen)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-LISTEN expects at least 1 argument");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-accept)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-ACCEPT expects 1 argument");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-local-port 1 2)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-LOCAL-PORT expects 1 argument");
	}

	@Test
	void tcpAddressAccessorsInPreview1ModeAreCallTimeErrors() {
		// Same call-time policy as the tcp builtins above.
		assertThat(compile("(rontolisp:tcp-peer-address 0)")).isNotEmpty();
		assertThat(compile("(rontolisp:tcp-peer-port 0)")).isNotEmpty();
		assertThat(compile("(rontolisp:tcp-local-address 0)")).isNotEmpty();
	}

	@Test
	void tcpAddressAccessorsCompileInComponentMode() {
		// REAL on the component now: sockets.lisp reads get-local-address /
		// get-remote-address, so the accessors report actual addresses (the old
		// adapter-era nil stubs are gone).
		assertThat(compileComponent("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (client (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port listener))))
				  (print (rontolisp:tcp-peer-address client))
				  (print (rontolisp:tcp-peer-port client))
				  (print (rontolisp:tcp-local-address listener)))
				""")).isNotEmpty();
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-peer-address 1 2)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-PEER-ADDRESS expects 1 argument");
	}

	@Test
	void usocketSpliceCompilesInPreview1ModeWithCallTimeSocketErrors() {
		// The usocket shim (usocket.lisp, spliced by UsocketLibrary.process) calls
		// rontolisp:tcp-connect in its defun bodies; on Preview 1 those calls
		// compile to call-time errors -- the same policy as direct tcp use -- so a
		// program that never reaches a socket still builds.
		List<LispVal> program = am.ik.rontolisp.eval.UsocketLibrary
			.process(LispReader.readAllFromString("(print (usocket:socket-stream 1))"));
		assertThat(new WasmLispCompiler().compile(program)).isNotEmpty();
	}

	@Test
	void usocketSpliceCompilesInComponentMode() {
		List<LispVal> program = compileChainForUsocket("""
				(usocket:with-socket-listener (listener "127.0.0.1" 0)
				  (usocket:with-client-socket (client stream "127.0.0.1" (usocket:get-local-port listener)
				                               :element-type '(unsigned-byte 8))
				    (write-line "hi" stream)
				    (usocket:with-connected-socket (server (usocket:socket-accept listener))
				      (print (read-line server))
				      (print (usocket:get-peer-address server)))))
				""");
		assertThat(new WasmLispCompiler(false, true).compile(program)).isNotEmpty();
	}

	// The CLI order for a usocket component program: the usocket shim, then the
	// sockets.lisp splice its tcp-* calls resolve against, then the wit runtime the
	// binding wrappers reference.
	private static List<LispVal> compileChainForUsocket(String source) {
		List<LispVal> program = am.ik.rontolisp.eval.UsocketLibrary.process(LispReader.readAllFromString(source));
		program = SocketsLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		program = StdinLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, false);
		return WitLibrary.process(program);
	}

	@Test
	void asyncStdinReadCompilesAsComponent() {
		// The stdin.lisp + stdin-dispatch.lisp splice: read-line in an async body
		// promotes to an await over the wit-imported wasi:cli/stdin stream, bound
		// FROM the fixed import block.
		assertThat(compileComponent("""
				(rontolisp:async-defun main () (print (read-line)))
				(rontolisp:await (main))
				""")).isNotEmpty();
	}

	@Test
	void nonAsyncStdinComponentIsByteIdenticalWithTheStdinLibraryInTheChain() {
		// The byte-stability contract end-to-end: a synchronous stdin program's
		// component must not move a byte because of the stdin machinery's existence
		// (it keeps the preview1 adapter's stdin branch and its wasmtime flags).
		String source = "(print (read-line))";
		byte[] without = new WasmLispCompiler(false, true)
			.compile(WitLibrary.process(LispReader.readAllFromString(source)));
		assertThat(compileComponent(source)).isEqualTo(without);
	}

	@Test
	void tcpProgramReadingStdinCompilesAsComponent() {
		// sockets.lisp's dispatchers fall through to the real stdin machinery, so a
		// socket program reading stdin compiles with BOTH splices present (one
		// %io-read-line definition).
		assertThat(compileComponent("""
				(rontolisp:async-defun main ()
				  (let ((l (rontolisp:tcp-listen 7777)))
				    (print (read-line))
				    (close l)))
				(rontolisp:await (main))
				""")).isNotEmpty();
	}

	@Test
	void tlsConnectIsCompileErrorInBothWasmModes() {
		// Unlike the plain tcp built-ins there is no component fallback: wasmtime hosts
		// no TLS for WASI 0.3 components, so the tls built-ins are interpreter/JVM only.
		assertThatThrownBy(() -> compile("(rontolisp:tls-connect \"example.com\" 443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-CONNECT is not supported on the WASM backend");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tls-connect \"example.com\" 443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-CONNECT is not supported on the WASM backend");
	}

	@Test
	void tlsListenIsCompileErrorInBothWasmModes() {
		assertThatThrownBy(() -> compile("(rontolisp:tls-listen \"ks.p12\" \"pw\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-LISTEN is not supported on the WASM backend");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tls-listen \"ks.p12\" \"pw\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-LISTEN is not supported on the WASM backend");
	}

	@Test
	void tlsListenPemIsCompileErrorInBothWasmModes() {
		assertThatThrownBy(() -> compile("(rontolisp:tls-listen-pem \"cert.pem\" \"key.pem\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-LISTEN-PEM is not supported on the WASM backend");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tls-listen-pem \"cert.pem\" \"key.pem\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-LISTEN-PEM is not supported on the WASM backend");
		// The internal %tls-listen-p12 shape (were the inliner ever run for WASM) also
		// reports as tls-listen-pem.
		assertThatThrownBy(() -> compile("(rontolisp:%tls-listen-p12 \"blob\" \"pw\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-LISTEN-PEM is not supported on the WASM backend");
	}

	// The serve library pipeline the CLI runs for a --component rontolisp:http-handler:
	// fetch
	// first (a served handler that fetches carries fetch.lisp too), then serve.lisp, then
	// the
	// macro expansion serve.lisp's cond/handler-case bodies need.
	private static List<LispVal> serveProgram(String source) {
		List<LispVal> read = LispReader.readAllFromString(source);
		boolean bufferBody = am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(read);
		List<LispVal> loaded = am.ik.rontolisp.eval.HttpLibrary.process(read,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, true);
		loaded = am.ik.rontolisp.eval.HttpServerLibrary.process(loaded, bufferBody);
		loaded = SocketsLibrary.process(loaded, WitExportDirective.Backend.WASM_COMPONENT);
		loaded = StdinLibrary.process(loaded, WitExportDirective.Backend.WASM_COMPONENT, true);
		loaded = am.ik.rontolisp.eval.EnvironmentLibrary.process(loaded, WitExportDirective.Backend.WASM_COMPONENT);
		return am.ik.rontolisp.eval.WitLibrary.process(
				am.ik.rontolisp.eval.GrayStreamsLibrary.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded)));
	}

	@Test
	void getenvInServeModeImportsWasiCliEnvironment() {
		// A served handler reads the environment through environment.lisp's wit-imported
		// wasi:cli/environment@0.3.0. The serve import block declares no environment
		// interface (the wasi:http service world carries none -- which is why the
		// preview1 bridge answers environ_* with a zero environment), so the binding
		// joins as an APPENDED USER IMPORT, and the emitted WIT must say so. The
		// `wasmtime serve --env` round trip is
		// WasmLispCompilerIntegrationTest#httpHandlerReadsTheEnvironmentUnderWasmtimeServe.
		List<LispVal> program = serveProgram("""
				(defun handle (env)
				  (list 200 nil (list (or (uiop:getenv "RLENV") "unset"))))
				(rontolisp:http-handler 'handle)
				""");
		WasmLispCompiler compiler = new WasmLispCompiler(false, true, false, OptimizeLevel.NONE, true);
		byte[] component = compiler.compile(program);
		assertThat(new String(component, java.nio.charset.StandardCharsets.ISO_8859_1))
			.contains("wasi:cli/environment@0.3.0");
		assertThat(compiler.componentWit()).contains("import wasi:cli/environment@0.3.0;");
	}

	@Test
	void getenvInBaseComponentBindsTheBlocksEnvironmentInstance() {
		// Off serve the import block ALREADY declares wasi:cli/environment (the preview1
		// adapter's own get-environment alias rides it), so the very same binding is
		// lowered FROM the block instead of re-imported: a component importing one
		// interface name twice is invalid. So the emitted world declares the import
		// EXACTLY ONCE -- the unchanged fixed world, with nothing appended for the
		// binding -- and the component still instantiates (the wasmtime leg is
		// WasmLispCompilerIntegrationTest#componentGetenvFromWasiEnvironment).
		List<LispVal> program = am.ik.rontolisp.eval.EnvironmentLibrary.process(
				LispReader.readAllFromString("(print (uiop:getenv \"RLENV\"))"),
				WitExportDirective.Backend.WASM_COMPONENT);
		WasmLispCompiler compiler = new WasmLispCompiler(false, true);
		assertThat(compiler.compile(program)).isNotEmpty();
		String wit = compiler.componentWit();
		assertThat(wit).isNotNull();
		assertThat(wit.split("import wasi:cli/environment@0\\.3\\.0;", -1)).hasSize(2);
	}

	@Test
	void httpHandlerWithFetchCompilesInServeMode() {
		// fetch inside a served handler compiles: serve.lisp and fetch.lisp are spliced
		// together over the wider serve+fetch block (no hand-written adapter); the round
		// trip
		// under `wasmtime serve -W exceptions=y -S http=y` is exercised in
		// WasmLispCompilerIntegrationTest.
		List<LispVal> program = serveProgram("""
				(rontolisp:async-defun h (env)
				  (list 200 nil
				        (getf (rontolisp:await (rontolisp:fetch "http://127.0.0.1:9/")) :body)))
				(rontolisp:http-handler 'h)
				""");
		assertThat(new WasmLispCompiler(false, true, false, OptimizeLevel.NONE, true).compile(program)).isNotEmpty();
	}

	@Test
	void httpHandlerWithTcpCompilesInServeMode() {
		// tcp inside a served handler compiles now: sockets.lisp is one more user WIT
		// import beside the fixed wasi:http surface (the dedicated sockets blob
		// variant and its adapter are gone).
		List<LispVal> program = serveProgram("""
				(defun h (env) (list 200 nil (list "x")))
				(rontolisp:http-handler 'h)
				(rontolisp:tcp-listen 7777)
				""");
		assertThat(new WasmLispCompiler(false, true, false, OptimizeLevel.NONE, true).compile(program)).isNotEmpty();
	}

	@Test
	void fetchAndTcpInOneComponentProgramCompiles() {
		// fetch and tcp compose now: both are user WIT imports of different
		// interfaces (wasi:http vs wasi:sockets) on the one base variant.
		assertThat(compileComponent("(rontolisp:fetch \"http://x/\") (rontolisp:tcp-connect \"127.0.0.1\" 7777)"))
			.isNotEmpty();
	}

	@Test
	void fetchWithLiteralUnsupportedMethodIsCompileError() {
		// A method outside the supported set is rejected at compile time (only literal
		// methods are checked; a runtime-computed one is treated as GET).
		assertThatThrownBy(() -> compileComponent("(rontolisp:fetch \"http://x/\" (list :method \"CONNECT\"))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("unsupported method");
		assertThatThrownBy(() -> compileComponent("(rontolisp:fetch \"http://x/\" '(:method \"FOO\"))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("unsupported method");
	}

	@Test
	void fetchWithSupportedMethodsAndBodyCompilesInComponentMode() {
		// literal GET (any case), the other supported methods, a request :body, and a
		// runtime-computed options value all compile
		assertThat(compileComponent("(rontolisp:fetch \"http://x/\" (list :method \"get\"))")).isNotEmpty();
		assertThat(compileComponent("(rontolisp:fetch \"http://x/\" (list :method \"POST\" :body \"data\"))"))
			.isNotEmpty();
		assertThat(compileComponent("(rontolisp:fetch \"http://x/\" '(:method \"DELETE\"))")).isNotEmpty();
		assertThat(compileComponent("(let ((opts (list :method \"PUT\"))) (rontolisp:fetch \"http://x/\" opts))"))
			.isNotEmpty();
	}

	@Test
	void componentModeEmitsComponentPreamble() {
		// Component preamble: magic "\0asm" then version 0x000d, layer 0x0001.
		byte[] component = compileComponent("(print (+ 1 2))");
		assertThat(component[0]).isEqualTo((byte) 0x00);
		assertThat(component[1]).isEqualTo((byte) 'a');
		assertThat(component[2]).isEqualTo((byte) 's');
		assertThat(component[3]).isEqualTo((byte) 'm');
		assertThat(component[4]).isEqualTo((byte) 0x0d);
		assertThat(component[5]).isEqualTo((byte) 0x00);
		assertThat(component[6]).isEqualTo((byte) 0x01);
		assertThat(component[7]).isEqualTo((byte) 0x00);
	}

	@Test
	void componentModeWrapsAndExceedsCoreModule() {
		// The component embeds the core module plus the memory/adapter modules and
		// wiring,
		// so it is strictly larger than the Preview 1 core module for the same program.
		assertThat(compileComponent("(print (+ 1 2))").length).isGreaterThan(compile("(print (+ 1 2))").length);
	}

	@Test
	void componentModeDeclaresWasiCliRunExport() {
		// The assembled component must export the wasi:cli/run interface so `wasmtime
		// run`
		// can drive it.
		byte[] component = compileComponent("(print 1)");
		assertThat(new String(component, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("wasi:cli/run@0.3.0");
	}

	@Test
	void defaultModeStillEmitsCoreModuleVersion1() {
		// Regression: the default Preview 1 path keeps the core-module preamble.
		byte[] core = compile("(print (+ 1 2))");
		assertThat(core[4]).isEqualTo((byte) 0x01);
		assertThat(core[5]).isEqualTo((byte) 0x00);
	}

	@Test
	void wasmMagicNumber() {
		byte[] wasm = compile("(print 1)");
		assertThat(wasm[0]).isEqualTo((byte) 0x00); // \0
		assertThat(wasm[1]).isEqualTo((byte) 'a');
		assertThat(wasm[2]).isEqualTo((byte) 's');
		assertThat(wasm[3]).isEqualTo((byte) 'm');
	}

	@Test
	void wasmVersion() {
		byte[] wasm = compile("(print 1)");
		assertThat(wasm[4]).isEqualTo((byte) 0x01);
		assertThat(wasm[5]).isEqualTo((byte) 0x00);
		assertThat(wasm[6]).isEqualTo((byte) 0x00);
		assertThat(wasm[7]).isEqualTo((byte) 0x00);
	}

	@Test
	void wasmContainsTypeSection() {
		byte[] wasm = compile("(print 1)");
		assertThat(containsSectionId(wasm, 0x01)).isTrue();
	}

	@Test
	void wasmContainsRecTypeGroup() {
		byte[] wasm = compile("(print 1)");
		// rec group marker 0x4E should be present in the type section
		assertThat(containsByte(wasm, (byte) 0x4E)).isTrue();
	}

	@Test
	void wasmContainsExportSection() {
		byte[] wasm = compile("(print 1)");
		String wasmStr = new String(wasm);
		assertThat(wasmStr).contains("memory");
		assertThat(wasmStr).contains("_start");
	}

	@Test
	void wasmContainsImportSection() {
		byte[] wasm = compile("(print 1)");
		String wasmStr = new String(wasm);
		assertThat(wasmStr).contains("wasi_snapshot_preview1");
	}

	@Test
	void wasmContainsGcInstructions() {
		byte[] wasm = compile("(+ 1 2)");
		// GC prefix byte 0xFB should be present (for i31.get_s, ref.i31, etc.)
		assertThat(containsByte(wasm, (byte) 0xFB)).isTrue();
	}

	@Test
	void wasmArithmeticProducesValidBinary() {
		byte[] wasm = compile("(print (+ 1 2))");
		assertThat(wasm).isNotEmpty();
		assertThat(wasm.length).isGreaterThan(8);
	}

	@Test
	void wasmNestedArithmeticProducesValidBinary() {
		byte[] wasm = compile("(print (* 3 (+ 1 2)))");
		assertThat(wasm).isNotEmpty();
		assertThat(wasm.length).isGreaterThan(8);
	}

	@Test
	void wasmIfProducesValidBinary() {
		byte[] wasm = compile("(print (if t 1 2))");
		assertThat(wasm).isNotEmpty();
		assertThat(wasm.length).isGreaterThan(8);
	}

	private boolean containsSectionId(byte[] wasm, int sectionId) {
		// Section IDs appear after the 8-byte header
		for (int i = 8; i < wasm.length; i++) {
			if ((wasm[i] & 0xFF) == sectionId) {
				return true;
			}
		}
		return false;
	}

	private boolean containsByte(byte[] wasm, byte target) {
		for (byte b : wasm) {
			if (b == target) {
				return true;
			}
		}
		return false;
	}

	@Test
	void jsonOpsCompileInEveryMode() {
		// The spliced JSON library compiles in Preview 1, component and no-WASI modes
		// (it is plain Lisp source, so no backend-specific lowering is involved).
		String source = "(print (rontolisp:json-stringify (rontolisp:json-parse \"{\\\"a\\\": [1, 2.5]}\")))";
		java.util.List<am.ik.rontolisp.LispVal> program = am.ik.rontolisp.eval.JsonLibrary
			.process(LispReader.readAllFromString(source));
		assertThat(new WasmLispCompiler().compile(program)).isNotEmpty();
		assertThat(new WasmLispCompiler(false, true).compile(program)).isNotEmpty();
	}

	@Test
	void linalgOpsCompileInEveryMode() {
		// The spliced linalg library compiles in Preview 1 and component modes (it is
		// plain Lisp source over the array built-ins, so no backend-specific lowering
		// is involved).
		String source = "(print (linalg:solve (linalg:from-list '((2 1) (1 3))) (linalg:from-list '(3 5))))";
		java.util.List<am.ik.rontolisp.LispVal> program = am.ik.rontolisp.eval.LinalgLibrary
			.process(LispReader.readAllFromString(source));
		assertThat(new WasmLispCompiler().compile(program)).isNotEmpty();
		assertThat(new WasmLispCompiler(false, true).compile(program)).isNotEmpty();
	}

	@Test
	void linalgSingleFloatCompilesInEveryMode() {
		// Single-float (#f) linalg output compiles in Preview 1 and component
		// modes. Both linalg::%la-make branches take a literal :element-type, so the
		// wasm-GC backend picks the TYPE_F32ARR/F64ARR repr statically (no reader
		// conditional needed -- unlike the earlier vec::%make-like assumption, wasm-GC
		// produces #f directly).
		String source = "(print (linalg:sub (linalg:ones '(2 2) 'single-float) "
				+ "(linalg:full '(2 2) 0.5 'single-float)))";
		java.util.List<am.ik.rontolisp.LispVal> program = am.ik.rontolisp.eval.LinalgLibrary
			.process(LispReader.readAllFromString(source));
		assertThat(new WasmLispCompiler().compile(program)).isNotEmpty();
		assertThat(new WasmLispCompiler(false, true).compile(program)).isNotEmpty();
	}

	@Test
	void urlOpsCompileInEveryMode() {
		// The spliced URL library compiles in Preview 1 and component modes (it is
		// plain Lisp source, so no backend-specific lowering is involved).
		String source = "(print (rontolisp:query-param (rontolisp:url-query \"/get?q=%E3%81%82\") \"q\"))";
		java.util.List<am.ik.rontolisp.LispVal> program = am.ik.rontolisp.eval.UrlLibrary
			.process(LispReader.readAllFromString(source));
		assertThat(new WasmLispCompiler().compile(program)).isNotEmpty();
		assertThat(new WasmLispCompiler(false, true).compile(program)).isNotEmpty();
	}

	// --- --simd: v128 kernels over GC (array (mut v128)) packed arrays ----------------

	private static byte[] compileVec(String source, boolean simd) {
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary.process(LispReader.readAllFromString(source));
		return new WasmLispCompiler(false, false, false, OptimizeLevel.NONE, false, simd).compile(program);
	}

	@Test
	void simdEmitsV128LocalsAndTheDefaultBuildDeclaresNone() {
		// The dead-flag guard. Correctness alone cannot prove the interception fired: a
		// --simd module computes exactly what the scalar vec.lisp defuns do. A v128 local
		// can only come from a lane loop, and local declarations are the one part of a
		// code section that decodes without a full opcode walker (an opcode-byte scan
		// false-positives on immediates), so assert on those. The runnable half of the
		// guard is in WasmLispCompilerIntegrationTest: a --simd module is REJECTED by a
		// wasmtime with the SIMD proposal turned off, a default one runs.
		String source = "(print (vec:dot (vec:ones 5) (vec:ones 5)))";
		assertThat(declaresV128Local(compileVec(source, true))).as("v128 locals in the --simd kernels").isTrue();
		assertThat(declaresV128Local(compileVec(source, false))).as("no v128 local in the default wasm-GC module")
			.isFalse();
	}

	@Test
	void simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks() {
		// --simd appends its four types AFTER the last fixed one (TYPE_F32ARR) and its
		// two function blocks after the last fixed function, so every fixed TYPE_*/FUNC_*
		// index
		// -- and with it the byte-identical component blobs -- keeps its value, and only
		// the export/import wrapper bases and FUNC_USER_BASE shift. The default module's
		// type section is a strict PREFIX of the --simd one: an (array (mut v128)) type
		// needs the SIMD proposal, so it must not appear unless --simd asked for it.
		String source = "(print (aref #d(1.0 2.0 3.0) 1))";
		byte[] scalar = compileVec(source, false);
		byte[] simd = compileVec(source, true);
		byte[] scalarTypes = typeSectionEntries(scalar);
		byte[] simdTypes = typeSectionEntries(simd);
		assertThat(typeSectionCount(simd) - typeSectionCount(scalar)).as("--simd appends four type entries")
			.isEqualTo(WasmLispCompiler.SIMD_TYPE_COUNT);
		assertThat(simdTypes).as("the default types are a prefix of the --simd types").startsWith(scalarTypes);
		// The four appended entries, in TYPE_V128ARR .. TYPE_V_SET order.
		byte[] appended = Arrays.copyOfRange(simdTypes, scalarTypes.length, simdTypes.length);
		assertThat(appended).isEqualTo(new byte[] {
				// TYPE_V128ARR: (array (mut v128)) -- the type that needs the SIMD
				// proposal
				0x5E, 0x7B, 0x01,
				// TYPE_VBLOCK: rec { sub final struct {i32, i32, (ref null eq)} } --
				// 0x4F is the spec's `sub final`, .kb/wasm-gc-final-types.md
				0x4E, 0x01, 0x4F, 0x00, 0x5F, 0x03, 0x7F, 0x00, 0x7F, 0x00, 0x63, 0x6D, 0x00,
				// TYPE_V_GET: (func (param (ref null eq) i32) (result f64))
				0x60, 0x02, 0x63, 0x6D, 0x7F, 0x01, 0x7C,
				// TYPE_V_SET: (func (param (ref null eq) i32 f64) (result f64))
				0x60, 0x03, 0x63, 0x6D, 0x7F, 0x7C, 0x01, 0x7C });
		// --simd emits TWO function blocks: the vec: kernels, then the linalg: ones.
		// Both are absent from a default module -- this delta is the only
		// structural guard that a build without the flag stays byte-identical to one that
		// never knew about it, so it must count BOTH blocks rather than be relaxed.
		assertThat(functionCount(simd) - functionCount(scalar)).as("the vec: block plus the linalg: block")
			.isEqualTo(WasmVecSimdRuntimeBuilder.FUNC_COUNT + WasmLinalgSimdRuntimeBuilder.FUNC_COUNT);
		// The linalg: block sits immediately after the vec: one, and the user defuns
		// after
		// both, so every fixed FUNC_* index below FUNC_USER_BASE keeps its value.
		assertThat(WasmLispCompiler.linalgFuncBase())
			.isEqualTo(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.FUNC_COUNT);
		assertThat(new WasmLispCompiler().userFuncBase()).isEqualTo(WasmLispCompiler.FUNC_USER_BASE);
		assertThat(new WasmLispCompiler(false, false, false, OptimizeLevel.NONE, false, true).userFuncBase())
			.isEqualTo(WasmLispCompiler.FUNC_USER_BASE + WasmVecSimdRuntimeBuilder.FUNC_COUNT
					+ WasmLinalgSimdRuntimeBuilder.FUNC_COUNT);
	}

	@Test
	void simdComposesWithComponentAndOptimize() {
		// --simd is orthogonal to the output mode: the packed arrays are ordinary GC
		// objects, so a component core needs no extra pages, and the tree shaker decodes
		// the 0xFD prefix (including v128.const and i8x16.shuffle's 16 immediate bytes).
		String source = "(print (vec:sum (vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0))))";
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary.process(LispReader.readAllFromString(source));
		assertThat(new WasmLispCompiler(false, true, false, OptimizeLevel.NONE, false, true).compile(program))
			.isNotEmpty();
		assertThat(new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT, false, true).compile(program))
			.isNotEmpty();
	}

	// --- minimal module reader (sections + code-section local declarations) -----------

	// The payload of the given section id, or an empty array when absent.
	private static byte[] section(byte[] module, int wanted) {
		int[] p = { 8 }; // past the magic + version
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xFF;
			int size = readUleb(module, p);
			if (id == wanted) {
				byte[] payload = new byte[size];
				System.arraycopy(module, p[0], payload, 0, size);
				return payload;
			}
			p[0] += size;
		}
		return new byte[0];
	}

	// The number of entries in the function section (id 3).
	private static int functionCount(byte[] module) {
		return readUleb(section(module, 3), new int[] { 0 });
	}

	// The number of entries in the type section (id 1). A rec group counts as one entry
	// even when it declares several type indices.
	private static int typeSectionCount(byte[] module) {
		return readUleb(section(module, 1), new int[] { 0 });
	}

	// The type section (id 1) with its leading entry count stripped, so two sections can
	// be compared for a common prefix.
	private static byte[] typeSectionEntries(byte[] module) {
		byte[] payload = section(module, 1);
		int[] p = { 0 };
		readUleb(payload, p);
		return Arrays.copyOfRange(payload, p[0], payload.length);
	}

	// Whether any function body in the code section (id 10) declares a v128 local. The
	// local declarations are a decodable prefix of each body -- unlike its instructions,
	// which need a full opcode walker to scan safely.
	private static boolean declaresV128Local(byte[] module) {
		byte[] code = section(module, 10);
		int[] p = { 0 };
		int bodies = readUleb(code, p);
		for (int i = 0; i < bodies; i++) {
			int size = readUleb(code, p);
			int end = p[0] + size;
			int groups = readUleb(code, p);
			for (int g = 0; g < groups; g++) {
				readUleb(code, p); // the group's local count
				int valType = code[p[0]++] & 0xFF;
				if (valType == 0x7B) { // v128
					return true;
				}
				if (valType == 0x63 || valType == 0x64) { // (ref null ht) / (ref ht)
					readUleb(code, p);
				}
			}
			p[0] = end;
		}
		return false;
	}

	@Test
	void theSizeLevelShrinksTheModuleAndTheDefaultLevelIsTheBareFlag() {
		// The Docker-free half of the level's coverage (behavior parity under wasmtime
		// is WasmLispCompilerIntegrationTest's). Two things are pinned here: an
		// integer-hot program is SMALLER at --optimize=size, because the fused sites
		// that emit their tree twice are gone; and DEFAULT is byte-for-byte what the
		// bare --optimize has always emitted, which is the whole reason the flag took a
		// value instead of growing a second flag.
		List<LispVal> program = LispReader.readAllFromString("""
				(defun rol32d (x s) (logand (logior (ash x s) (ash x (- s 32))) 4294967295))
				(defun mixd (a b)
				  (let ((acc 0) (i 0))
				    (tagbody top
				      (setq acc (logand (+ (rol32d acc 7) (* a b) i) 4294967295))
				      (setq i (+ i 1))
				      (if (< i 64) (go top)))
				    acc))
				(print (mixd 12345 6789))
				""");
		byte[] none = new WasmLispCompiler(false, false, false, OptimizeLevel.NONE).compile(program);
		byte[] fast = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new WasmLispCompiler(false, false, false, OptimizeLevel.SIZE).compile(program);
		assertThat(fast.length).isLessThan(none.length);
		assertThat(small.length).isLessThan(fast.length);
	}

	private static int readUleb(byte[] buf, int[] p) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = buf[p[0]++] & 0xFF;
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return result;
			}
			shift += 7;
		}
	}

}
