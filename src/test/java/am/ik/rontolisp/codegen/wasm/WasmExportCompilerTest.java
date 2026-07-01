package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WasmExportCompiler} parsing and for the module-level wiring of
 * {@code (rontolisp:wasm-export ...)} directives (export names present, error cases
 * rejected). These run without Docker; the end-to-end {@code wasmtime --invoke} checks
 * live in {@link WasmLispCompilerIntegrationTest}.
 */
class WasmExportCompilerTest {

	private static WasmExportCompiler.Decl parse(String source) {
		return WasmExportCompiler.parse((LispCons) LispReader.readFromString(source));
	}

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler().compile(program);
	}

	private static boolean containsAscii(byte[] bytes, String needle) {
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
	}

	@Test
	void parsesScalarDirective() {
		WasmExportCompiler.Decl decl = parse("(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(decl.name()).isEqualTo("fact");
		assertThat(decl.paramTypes()).containsExactly(":int");
		assertThat(decl.returnType()).isEqualTo(":int");
	}

	@Test
	void parsesMultipleParamsAndMemoryTypes() {
		WasmExportCompiler.Decl decl = parse(
				"(rontolisp:wasm-export 'concat :params '(:string :s-expr) :returns :string)");
		assertThat(decl.paramTypes()).containsExactly(":string", ":s-expr");
		assertThat(WasmExportCompiler.usesMemory(decl)).isTrue();
		assertThat(WasmExportCompiler.paramSlotCount(decl)).isEqualTo(4);
	}

	@Test
	void parsesZeroArgDirective() {
		WasmExportCompiler.Decl decl = parse("(rontolisp:wasm-export 'now :params '() :returns :int)");
		assertThat(decl.paramTypes()).isEmpty();
		assertThat(WasmExportCompiler.usesMemory(decl)).isFalse();
	}

	@Test
	void treatsOmittedReturnsAsVoid() {
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int))").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
	}

	@Test
	void treatsExplicitVoidMarkersAsVoid() {
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns :void)").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns nil)").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
		assertThat(parse("(rontolisp:wasm-export 'go :params '(:int) :returns '())").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
	}

	@Test
	void voidReturnHasNoWasmResult() {
		WasmExportCompiler.Decl decl = parse("(rontolisp:wasm-export 'go :params '(:int))");
		assertThat(WasmExportCompiler.resultWasmTypes(decl)).isEmpty();
		assertThat(WasmExportCompiler.usesMemory(decl)).isFalse();
	}

	@Test
	void treatsNilParamsAsNoArguments() {
		assertThat(parse("(rontolisp:wasm-export 'go :params nil :returns :int)").paramTypes()).isEmpty();
		assertThat(parse("(rontolisp:wasm-export 'go :returns :int)").paramTypes()).isEmpty();
	}

	@Test
	void compilesVoidExport() {
		byte[] bytes = compile(
				"(defun ping () (print \"pong\")) (rontolisp:wasm-export 'ping :params '() :returns :void)");
		assertThat(containsAscii(bytes, "ping")).isTrue();
	}

	@Test
	void rejectsUnknownTypeDesignator() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-export 'g :params '(:widget) :returns :int)"))
			.hasMessageContaining(":widget");
	}

	@Test
	void compiledModuleExportsScalarFunctionByName() {
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "fact")).isTrue();
	}

	@Test
	void compiledModuleEmitsAllocatorForMemoryExport() {
		byte[] bytes = compile("(defun shout (s) (string-upcase s))"
				+ "(rontolisp:wasm-export 'shout :params '(:string) :returns :string)");
		assertThat(containsAscii(bytes, "__ronto_alloc")).isTrue();
		assertThat(containsAscii(bytes, "shout")).isTrue();
	}

	@Test
	void scalarExportDoesNotEmitAllocator() {
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "__ronto_alloc")).isFalse();
	}

	@Test
	void rejectsExportOfUnknownFunction() {
		assertThatThrownBy(() -> compile("(rontolisp:wasm-export 'nope :params '(:int) :returns :int)"))
			.hasMessageContaining("unknown function");
	}

	@Test
	void rejectsArityMismatch() {
		assertThatThrownBy(
				() -> compile("(defun f (a b) (+ a b)) (rontolisp:wasm-export 'f :params '(:int) :returns :int)"))
			.hasMessageContaining("arity mismatch");
	}

	@Test
	void noWasiModeOmitsWasiImports() {
		// Reactor mode: no wasi_snapshot_preview1 imports, but the export wrapper stays.
		List<LispVal> program = LispReader.readAllFromString("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		byte[] bytes = new WasmLispCompiler(false, false, true).compile(program);
		assertThat(containsAscii(bytes, "wasi_snapshot_preview1")).isFalse();
		assertThat(containsAscii(bytes, "fact")).isTrue();
	}

	@Test
	void defaultModeKeepsWasiImports() {
		byte[] bytes = compile("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)");
		assertThat(containsAscii(bytes, "wasi_snapshot_preview1")).isTrue();
	}

	@Test
	void noWasiIsIgnoredInComponentMode() {
		// Component mode has its own (lowered) import story; no-wasi must not apply.
		List<LispVal> program = LispReader.readAllFromString("(print (+ 1 2))");
		byte[] component = new WasmLispCompiler(false, true, true).compile(program);
		// The component wraps a core module that still imports the preview1-style
		// functions.
		assertThat(containsAscii(component, "wasi_snapshot_preview1")).isTrue();
	}

	@Test
	void componentModeIgnoresExportDirective() {
		// In component mode no wrapper/allocator is emitted (the directive is a no-op).
		List<LispVal> program = LispReader
			.readAllFromString("(defun shout (s) (string-upcase s)) (rontolisp:wasm-export 'shout :params '(:string)"
					+ " :returns :string) (print \"hi\")");
		byte[] component = new WasmLispCompiler(false, true).compile(program);
		assertThat(containsAscii(component, "__ronto_alloc")).isFalse();
	}

}
