package am.ik.rontolisp.codegen.wasm;

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

}
