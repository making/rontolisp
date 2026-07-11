package am.ik.rontolisp.codegen.wasm;

import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.LispVal;
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
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		return new WasmLispCompiler(false, true).compile(program);
	}

	@Test
	void fetchInPreview1ModeIsCompileError() {
		assertThatThrownBy(() -> compile("(rontolisp:fetch \"http://x/\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
	}

	@Test
	void httpHandlerInPreview1ModeRequiresComponent() {
		// In Preview 1 (no --component) the http-handler directive reaches the compiler
		// and
		// is rejected; the component path (via the CLI's HttpHandlerInliner + serve mode)
		// is exercised end to end in WasmLispCompilerIntegrationTest.
		assertThatThrownBy(() -> compile("(defun h (r) nil) (rontolisp:http-handler 'h)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--component");
	}

	@Test
	void promiseOpsCompileInEveryMode() {
		// await/then/promisep are generic promise operations; unlike fetch they compile
		// in Preview 1 mode too
		assertThat(compile("(print (rontolisp:await 42))")).isNotEmpty();
		assertThat(compile("(print (rontolisp:await (rontolisp:then 21 (lambda (x) (* x 2)))))")).isNotEmpty();
		assertThat(compile("(print (rontolisp:promisep 1))")).isNotEmpty();
	}

	@Test
	void awaitOfFetchCompilesInComponentMode() {
		assertThat(compileComponent("(print (getf (rontolisp:await (rontolisp:fetch \"http://x/\")) :status))"))
			.isNotEmpty();
		assertThat(compileComponent("(let ((p (rontolisp:fetch \"http://x/\"))) (rontolisp:await p))")).isNotEmpty();
		assertThat(compileComponent("(rontolisp:await (rontolisp:then (rontolisp:fetch \"http://x/\")"
				+ " (lambda (r) (getf r :status))))"))
			.isNotEmpty();
	}

	@Test
	void tcpBuiltinsInPreview1ModeAreCompileErrors() {
		assertThatThrownBy(() -> compile("(rontolisp:tcp-connect \"127.0.0.1\" 7777)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
		assertThatThrownBy(() -> compile("(rontolisp:tcp-listen 7777)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
		assertThatThrownBy(() -> compile("(rontolisp:tcp-accept 0)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
		assertThatThrownBy(() -> compile("(rontolisp:tcp-local-port 0)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
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
	void tcpRejectsWrongArgCounts() {
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-connect \"127.0.0.1\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tcp-connect expects 2 arguments");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-listen)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tcp-listen expects 1 or 2 arguments");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-accept)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tcp-accept expects 1 arguments");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-local-port 1 2)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tcp-local-port expects 1 arguments");
	}

	@Test
	void tcpAddressAccessorsInPreview1ModeAreCompileErrors() {
		assertThatThrownBy(() -> compile("(rontolisp:tcp-peer-address 0)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
		assertThatThrownBy(() -> compile("(rontolisp:tcp-peer-port 0)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
		assertThatThrownBy(() -> compile("(rontolisp:tcp-local-address 0)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
	}

	@Test
	void tcpAddressAccessorsCompileToNilInComponentMode() {
		// Not wired through the sockets adapter: they compile (drop the handle, yield
		// nil) so a spliced usocket.lisp works on the component target even though the
		// accessors themselves report nothing there.
		assertThat(compileComponent("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (client (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port listener))))
				  (print (rontolisp:tcp-peer-address client))
				  (print (rontolisp:tcp-peer-port client))
				  (print (rontolisp:tcp-local-address listener)))
				""")).isNotEmpty();
		assertThatThrownBy(() -> compileComponent("(rontolisp:tcp-peer-address 1 2)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tcp-peer-address expects 1 arguments");
	}

	@Test
	void usocketSpliceIsCompileErrorInPreview1Mode() {
		// The usocket shim (usocket.lisp, spliced by UsocketLibrary.process) calls
		// rontolisp:tcp-connect in its defun bodies, so a Preview 1 build fails with
		// the existing component-only tcp error -- the same behavior as direct tcp use.
		List<LispVal> program = am.ik.rontolisp.eval.UsocketLibrary
			.process(LispReader.readAllFromString("(print (usocket:socket-stream 1))"));
		assertThatThrownBy(() -> new WasmLispCompiler().compile(program))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("component");
	}

	@Test
	void usocketSpliceCompilesInComponentMode() {
		List<LispVal> program = am.ik.rontolisp.eval.UsocketLibrary.process(LispReader.readAllFromString("""
				(usocket:with-socket-listener (listener "127.0.0.1" 0)
				  (usocket:with-client-socket (client stream "127.0.0.1" (usocket:get-local-port listener)
				                               :element-type '(unsigned-byte 8))
				    (write-line "hi" stream)
				    (usocket:with-connected-socket (server (usocket:socket-accept listener))
				      (print (read-line server))
				      (print (usocket:get-peer-address server)))))
				"""));
		assertThat(new WasmLispCompiler(false, true).compile(program)).isNotEmpty();
	}

	@Test
	void tlsConnectIsCompileErrorInBothWasmModes() {
		// Unlike the plain tcp built-ins there is no component fallback: wasmtime hosts
		// no TLS for WASI 0.3 components, so the tls built-ins are interpreter/JVM only.
		assertThatThrownBy(() -> compile("(rontolisp:tls-connect \"example.com\" 443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tls-connect is not supported on the WASM backend");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tls-connect \"example.com\" 443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tls-connect is not supported on the WASM backend");
	}

	@Test
	void tlsListenIsCompileErrorInBothWasmModes() {
		assertThatThrownBy(() -> compile("(rontolisp:tls-listen \"ks.p12\" \"pw\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tls-listen is not supported on the WASM backend");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tls-listen \"ks.p12\" \"pw\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tls-listen is not supported on the WASM backend");
	}

	@Test
	void tlsListenPemIsCompileErrorInBothWasmModes() {
		assertThatThrownBy(() -> compile("(rontolisp:tls-listen-pem \"cert.pem\" \"key.pem\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tls-listen-pem is not supported on the WASM backend");
		assertThatThrownBy(() -> compileComponent("(rontolisp:tls-listen-pem \"cert.pem\" \"key.pem\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tls-listen-pem is not supported on the WASM backend");
		// The internal %tls-listen-p12 shape (were the inliner ever run for WASM) also
		// reports as tls-listen-pem.
		assertThatThrownBy(() -> compile("(rontolisp:%tls-listen-p12 \"blob\" \"pw\" 8443)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("tls-listen-pem is not supported on the WASM backend");
	}

	@Test
	void httpHandlerWithFetchCompilesInServeMode() {
		// fetch inside a served handler compiles to the serve+fetch component variant
		// (WasmServeComponentBuilder.buildHttp); the round trip under `wasmtime serve
		// -S http=y` is exercised in WasmLispCompilerIntegrationTest.
		List<LispVal> program = am.ik.rontolisp.cli.HttpHandlerInliner.inline(LispReader.readAllFromString("""
				(defun h (r)
				  (list :status 200
				        :body (getf (rontolisp:await (rontolisp:fetch "http://127.0.0.1:9/")) :body)))
				(rontolisp:http-handler 'h)
				"""));
		assertThat(new WasmLispCompiler(false, true, false, false, true).compile(program)).isNotEmpty();
	}

	@Test
	void httpHandlerWithTcpIsCompileErrorInServeMode() {
		// There is no serve blob variant with wasi:sockets: fail at compile time
		// instead of emitting a component that cannot instantiate.
		List<LispVal> program = am.ik.rontolisp.cli.HttpHandlerInliner.inline(LispReader.readAllFromString("""
				(defun h (r) (list :status 200 :body "x"))
				(rontolisp:http-handler 'h)
				(rontolisp:tcp-listen 7777)
				"""));
		assertThatThrownBy(() -> new WasmLispCompiler(false, true, false, false, true).compile(program))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("rontolisp:tcp-* cannot be used in a rontolisp:http-handler");
	}

	@Test
	void fetchAndTcpInOneComponentProgramIsCompileError() {
		// fetch (a wasi:http 0.2 hybrid) and tcp sockets (wasi:sockets 0.3) need
		// different component blob variants; combining them is not supported yet.
		assertThatThrownBy(
				() -> compileComponent("(rontolisp:fetch \"http://x/\") (rontolisp:tcp-connect \"127.0.0.1\" 7777)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("cannot be combined");
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
		// todo-97: single-float (#f) linalg output compiles in Preview 1 and component
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

	// --- --simd (todo-105): v128 kernels over GC (array (mut v128)) packed arrays ------

	private static byte[] compileVec(String source, boolean simd) {
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary.process(LispReader.readAllFromString(source));
		return new WasmLispCompiler(false, false, false, false, false, simd).compile(program);
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
				// TYPE_VBLOCK: rec { sub final struct {i32, i32, (ref null eq)} }
				0x4E, 0x01, 0x50, 0x00, 0x5F, 0x03, 0x7F, 0x00, 0x7F, 0x00, 0x63, 0x6D, 0x00,
				// TYPE_V_GET: (func (param (ref null eq) i32) (result f64))
				0x60, 0x02, 0x63, 0x6D, 0x7F, 0x01, 0x7C,
				// TYPE_V_SET: (func (param (ref null eq) i32 f64) (result f64))
				0x60, 0x03, 0x63, 0x6D, 0x7F, 0x7C, 0x01, 0x7C });
		// --simd emits TWO function blocks: the vec: kernels, then the linalg: ones
		// (todo-107). Both are absent from a default module -- this delta is the only
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
		assertThat(new WasmLispCompiler(false, false, false, false, false, true).userFuncBase())
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
		assertThat(new WasmLispCompiler(false, true, false, false, false, true).compile(program)).isNotEmpty();
		assertThat(new WasmLispCompiler(false, false, false, true, false, true).compile(program)).isNotEmpty();
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
