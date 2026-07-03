package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WasmImportCompiler} parsing and for the module-level wiring of
 * {@code (rontolisp:wasm-import ...)} directives: the injected import entries come first
 * in the import section (function indices 0..K-1), every other function reference is
 * shifted, and unsupported modes are rejected. These run without Docker; the end-to-end
 * {@code wasmtime --preload} checks live in {@link WasmLispCompilerIntegrationTest}.
 */
class WasmImportCompilerTest {

	private static WasmImportCompiler.Decl parse(String source) {
		return WasmImportCompiler.parse((LispCons) LispReader.readFromString(source));
	}

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler().compile(program);
	}

	private static byte[] compileNoWasi(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, true).compile(program);
	}

	private static boolean containsAscii(byte[] bytes, String needle) {
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
	}

	@Test
	void parsesDirectiveWithDefaults() {
		WasmImportCompiler.Decl decl = parse("(rontolisp:wasm-import 'draw :params '(:int :int) :returns :void)");
		assertThat(decl.name()).isEqualTo("draw");
		assertThat(decl.module()).isEqualTo("env");
		assertThat(decl.field()).isEqualTo("draw");
		assertThat(decl.paramTypes()).containsExactly(":int", ":int");
		assertThat(decl.returnType()).isEqualTo(WasmExportCompiler.T_VOID);
	}

	@Test
	void parsesFromAndAsOptions() {
		WasmImportCompiler.Decl decl = parse(
				"(rontolisp:wasm-import 'draw-pixel :from \"gl\" :as \"drawPixel\" :params '(:float) :returns :int)");
		assertThat(decl.name()).isEqualTo("draw-pixel");
		assertThat(decl.module()).isEqualTo("gl");
		assertThat(decl.field()).isEqualTo("drawPixel");
		assertThat(decl.paramTypes()).containsExactly(":float");
		assertThat(decl.returnType()).isEqualTo(":int");
	}

	@Test
	void defaultFieldOfPackageQualifiedNameIsTheUnqualifiedMember() {
		// A directive inside a user package resolves its name to pkg:name; the
		// host-facing import field must default to the bare member name, not the
		// package-qualified spelling.
		WasmImportCompiler.Decl decl = parse("(rontolisp:wasm-import 'gl:enable :params '(:int) :returns :void)");
		assertThat(decl.name()).isEqualTo("gl:enable");
		assertThat(decl.field()).isEqualTo("enable");
		WasmImportCompiler.Decl internal = parse("(rontolisp:wasm-import 'gl::fail :params '(:string))");
		assertThat(internal.name()).isEqualTo("gl::fail");
		assertThat(internal.field()).isEqualTo("fail");
	}

	@Test
	void treatsOmittedReturnsAsVoid() {
		assertThat(parse("(rontolisp:wasm-import 'go :params '(:int))").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
		assertThat(parse("(rontolisp:wasm-import 'go :params '(:int) :returns :void)").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
		assertThat(parse("(rontolisp:wasm-import 'go :params '(:int) :returns nil)").returnType())
			.isEqualTo(WasmExportCompiler.T_VOID);
	}

	@Test
	void rejectsUnknownTypeDesignator() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'g :params '(:widget))"))
			.hasMessageContaining(":widget");
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'g :params '(:int) :returns :widget)"))
			.hasMessageContaining(":widget");
	}

	@Test
	void rejectsUnknownOption() {
		assertThatThrownBy(() -> parse("(rontolisp:wasm-import 'g :wat 1)")).hasMessageContaining(":wat");
	}

	@Test
	void injectedImportsComeFirstInTheImportSection() {
		byte[] module = compile("""
				(rontolisp:wasm-import 'begin-frame :from "gl" :as "beginFrame" :params '(:int))
				(rontolisp:wasm-import 'draw :from "gl" :params '(:float :float) :returns :int)
				(print (draw 1.0 2.0))
				""");
		List<String[]> imports = functionImports(module);
		// The two host imports occupy function indices 0 and 1, ahead of the eight
		// wasi_snapshot_preview1 imports.
		assertThat(imports).hasSize(10);
		assertThat(imports.get(0)).containsExactly("gl", "beginFrame");
		assertThat(imports.get(1)).containsExactly("gl", "draw");
		assertThat(imports.get(2)[0]).isEqualTo("wasi_snapshot_preview1");
	}

	@Test
	void functionExportIndicesShiftPastTheInjectedImports() {
		String source = "(rontolisp:wasm-import 'ping :params '())" + "(print 1)";
		byte[] withImport = compile(source);
		byte[] without = compile("(print 1)");
		// _start sits at the fixed FUNC_START index; injecting one import shifts the
		// exported index up by exactly one.
		assertThat(exportedFunctionIndex(withImport, "_start")).isEqualTo(exportedFunctionIndex(without, "_start") + 1);
	}

	@Test
	void noWasiModuleImportsOnlyTheHostFunctions() {
		byte[] module = compileNoWasi("""
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(defun add10 (n) (add n 10))
				(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
				""");
		assertThat(containsAscii(module, "wasi_snapshot_preview1")).isFalse();
		List<String[]> imports = functionImports(module);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0)).containsExactly("host", "add");
	}

	@Test
	void stringResultExportsTheAllocator() {
		// A :string result is written into linear memory by the host via __ronto_alloc,
		// so the allocator must be exported even without any memory-typed export.
		byte[] module = compile("""
				(rontolisp:wasm-import 'greet :params '(:int) :returns :string)
				(print (greet 1))
				""");
		assertThat(exportedFunctionIndex(module, "__ronto_alloc")).isNotNegative();
	}

	@Test
	void intOnlyImportDoesNotExportTheAllocator() {
		byte[] module = compile("""
				(rontolisp:wasm-import 'add :params '(:int :int) :returns :int)
				(print (add 1 2))
				""");
		assertThat(containsAscii(module, "__ronto_alloc")).isFalse();
	}

	@Test
	void importIsCallableAsAFirstClassFunction() {
		// #'add and eval both route through the regular defun dispatch machinery; this
		// must compile without errors (behavior is covered by the integration tests).
		byte[] module = compile("""
				(rontolisp:wasm-import 'add :params '(:int :int) :returns :int)
				(print (funcall #'add 1 2))
				(print (eval '(add 3 4)))
				""");
		assertThat(functionImports(module).get(0)).containsExactly("env", "add");
	}

	@Test
	void rejectsNameCollidingWithADefun() {
		assertThatThrownBy(() -> compile(
				"(defun add (a b) (+ a b))" + "(rontolisp:wasm-import 'add :params '(:int :int) :returns :int)"))
			.hasMessageContaining("collides");
	}

	@Test
	void rejectsComponentMode() {
		List<LispVal> program = LispReader
			.readAllFromString("(rontolisp:wasm-import 'add :params '(:int :int) :returns :int) (print (add 1 2))");
		assertThatThrownBy(() -> new WasmLispCompiler(false, true).compile(program))
			.hasMessageContaining("--component");
	}

	@Test
	void rejectsNoGcMode() {
		List<LispVal> program = LispReader.readAllFromString("""
				(rontolisp:wasm-import 'add :params '(:int :int) :returns :int)
				(defun add10 (n) (add n 10))
				(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
				""");
		assertThatThrownBy(() -> new ScalarWasmCompiler().compile(program)).hasMessageContaining("--no-gc");
	}

	@Test
	void composesWithTheTreeShaker() {
		// --optimize runs after import injection; the shaken module keeps the used
		// import and stays instantiable (behavior covered by the integration tests).
		List<LispVal> program = LispReader.readAllFromString("""
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(defun add10 (n) (add n 10))
				(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
				""");
		byte[] optimized = new WasmLispCompiler(false, false, true, true).compile(program);
		List<String[]> imports = functionImports(optimized);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0)).containsExactly("host", "add");
		assertThat(exportedFunctionIndex(optimized, "add10")).isNotNegative();
	}

	// --- Minimal module readers (sections / import entries / export indices) ---

	// Returns the (module, name) pairs of the function imports, in index order.
	private static List<String[]> functionImports(byte[] module) {
		byte[] payload = section(module, 2);
		List<String[]> result = new ArrayList<>();
		if (payload == null) {
			return result;
		}
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			String mod = readName(payload, p);
			String name = readName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			switch (kind) {
				case 0x00 -> { // function: typeidx
					readU(payload, p);
					result.add(new String[] { mod, name });
				}
				case 0x02 -> { // memory: limits
					int flag = payload[p[0]++] & 0xff;
					readU(payload, p);
					if ((flag & 0x01) != 0) {
						readU(payload, p);
					}
				}
				default -> throw new IllegalStateException("Unexpected import kind: " + kind);
			}
		}
		return result;
	}

	// Returns the function index exported under the given name, or -1 if absent.
	private static int exportedFunctionIndex(byte[] module, String exportName) {
		byte[] payload = Objects.requireNonNull(section(module, 7));
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			String name = readName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			int index = readU(payload, p);
			if (kind == 0x00 && exportName.equals(name)) {
				return index;
			}
		}
		return -1;
	}

	private static byte @org.jspecify.annotations.Nullable [] section(byte[] module, int id) {
		int[] p = { 8 }; // skip "\0asm" + version
		while (p[0] < module.length) {
			int sectionId = module[p[0]++] & 0xff;
			int size = readU(module, p);
			if (sectionId == id) {
				byte[] payload = new byte[size];
				System.arraycopy(module, p[0], payload, 0, size);
				return payload;
			}
			p[0] += size;
		}
		return null;
	}

	private static String readName(byte[] buf, int[] p) {
		int len = readU(buf, p);
		String s = new String(buf, p[0], len, StandardCharsets.UTF_8);
		p[0] += len;
		return s;
	}

	private static int readU(byte[] buf, int[] p) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = buf[p[0]++] & 0xff;
			result |= (b & 0x7f) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		return result;
	}

}
