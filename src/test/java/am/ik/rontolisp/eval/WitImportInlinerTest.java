package am.ik.rontolisp.eval;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The acceptance test of {@code rontolisp:wit-import} ({@code .kb/wit.md}): a program
 * that declares a WIT interface must compile, on Preview 1 WASM, to the
 * <strong>byte-identical</strong> module the equivalent hand-written
 * {@code rontolisp:wasm-import} block produces. The directive is a compile-time front-end
 * for machinery that already exists -- if a single byte moved, it would be a new import
 * path instead of a typed front-end for the one we have.
 *
 * <p>
 * Both halves of each A/B pair are compiled in the same JVM, from the same body apart
 * from the directive, so nothing but the directive can explain a difference. The other
 * half of the story -- that the same source calls a <em>provider</em> on the interpreter
 * and the JVM -- is pinned end-to-end in {@code LispEvaluatorTest} and
 * {@code JvmLispCompilerTest}, which run the lowered program.
 */
class WitImportInlinerTest {

	@TempDir
	Path tempDir;

	/**
	 * The body every WASM variant shares: it calls three of the interface's four
	 * functions.
	 */
	private static final String BODY = """
			(defun setup ()
			  (let ((s (gl:create-shader 35633)))
			    (gl:shader-source s "void main() {}")
			    (gl:compile-shader s)
			    s))
			(print (setup))
			""";

	/**
	 * The hand-written import block the WIT below stands for: the WIT label
	 * {@code create-shader} is the Lisp name, and the Preview 1 import field is the
	 * camelCase {@code createShader} a JavaScript host (and {@code jco}) spells it with
	 * -- the {@code :field-style :camel} default.
	 */
	private static final String HAND_WRITTEN = """
			(defpackage gl (:use cl) (:export create-shader shader-source compile-shader clear-color))
			(rontolisp:wasm-import 'gl:create-shader :from "gl" :as "createShader" :params '(:int) :returns :int)
			(rontolisp:wasm-import 'gl:shader-source :from "gl" :as "shaderSource" :params '(:int :string) :returns :void)
			(rontolisp:wasm-import 'gl:compile-shader :from "gl" :as "compileShader" :params '(:int) :returns :void)
			(rontolisp:wasm-import 'gl:clear-color :from "gl" :as "clearColor" :params '(:float :float :float :float) :returns :void)
			""";

	private static final String DIRECTIVE = "(rontolisp:wit-import \"gl.wit\" :interface \"local:webgl/gl\" :package gl)\n";

	private static final String GL_WIT = """
			package local:webgl;

			interface gl {
			  create-shader: func(kind: s32) -> s32;
			  shader-source: func(shader: s32, source: string);
			  compile-shader: func(shader: s32);
			  clear-color: func(r: f32, g: f32, b: f32, a: f32);
			}
			""";

	/**
	 * A wasi:keyvalue-shaped interface: a resource with methods, and a freestanding
	 * opener.
	 */
	private static final String KV_WIT = """
			package wasi:keyvalue@0.2.0;

			interface store {
			  variant error {
			    no-such-store,
			    access-denied,
			    other(string),
			  }

			  resource bucket {
			    get: func(key: string) -> result<option<list<u8>>, error>;
			    set: func(key: string, value: list<u8>) -> result<_, error>;
			  }

			  open: func(identifier: string) -> result<bucket, error>;
			}
			""";

	private static final String KV_DIRECTIVE = "(rontolisp:wit-import \"kv.wit\" "
			+ ":interface \"wasi:keyvalue/store@0.2.0\" :package kv)";

	// A no-filesystem loader, exactly the shape the browser playground backs its uploads
	// with: a map of logical path -> text, and the loader's own error for anything else.
	private static SourceLoader uploads(Map<String, String> files) {
		return path -> {
			String content = files.get(path);
			if (content == null) {
				throw new FileNotFoundException(path + " (upload it first)");
			}
			return content;
		};
	}

	@Test
	void usesWitImportDetectsTheDirective() {
		assertThat(WitImportInliner.usesWitImport(LispReader.readAllFromString(DIRECTIVE))).isTrue();
		assertThat(WitImportInliner
			.usesWitImport(LispReader.readAllFromString("(rontolisp:wasm-import 'f :from \"m\" :params '(:int))")))
			.isFalse();
	}

	@Test
	void aProgramWithoutTheDirectiveIsUntouched() {
		List<LispVal> program = LispReader.readAllFromString("(defun f (x) x)");
		assertThat(
				WitImportInliner.inline(program, null, WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem()))
			.isSameAs(program);
		assertThat(WitImportInliner.inline(program, null, WitExportDirective.Backend.OTHER, SourceLoader.fileSystem()))
			.isSameAs(program);
	}

	/**
	 * An interface binds with no filesystem at all: the WIT text comes from an injected
	 * {@link SourceLoader}, which is what lets the browser playground -- whose "files"
	 * are an in-memory map of uploads and which never touches {@code java.nio.file} --
	 * compile a WIT-importing program its REPL can already run.
	 */
	@Test
	void aWitIsReadThroughTheSourceLoaderSoABrowserCanBindOne() {
		List<LispVal> out = WitImportInliner.inline(LispReader.readAllFromString(DIRECTIVE + BODY), null,
				WitExportDirective.Backend.WASM_GC, uploads(Map.of("gl.wit", GL_WIT)));
		assertThat(String.join("\n", out.stream().map(LispVal::print).toList())).doesNotContain("wit-import")
			.contains("(DEFPACKAGE GL (:USE CL) (:EXPORT create-shader shader-source compile-shader clear-color))")
			.contains("(RONTOLISP:WASM-IMPORT (QUOTE GL:create-shader) :FROM \"gl\" :AS \"createShader\" "
					+ ":PARAMS (QUOTE (:int)) :RETURNS :int)");
	}

	@Test
	void aWitMissingFromTheLoaderNamesTheLoadersOwnError() {
		assertThatThrownBy(() -> WitImportInliner.inline(LispReader.readAllFromString(DIRECTIVE + BODY), null,
				WitExportDirective.Backend.WASM_GC, uploads(Map.of())))
			.hasMessageContaining("cannot read WIT file gl.wit")
			.hasRootCauseInstanceOf(FileNotFoundException.class);
	}

	@Test
	void anUnreadableWitFileIsAClearError() {
		assertThatThrownBy(() -> WitImportInliner.inline(
				LispReader.readAllFromString("(rontolisp:wit-import \"missing.wit\" :interface \"local:webgl/gl\")"),
				this.tempDir.toString(), WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem()))
			.hasMessageContaining("cannot read WIT file")
			.hasMessageContaining("missing.wit");
	}

	@Test
	void theWitPathResolvesAgainstTheSourceFilesDirectory() throws IOException {
		// Like load: a relative path is read relative to the program, not the working
		// directory -- and the resolution is lexical, so it works through any loader.
		Path dir = Files.createDirectories(this.tempDir.resolve("wit"));
		Files.writeString(dir.resolve("gl.wit"), GL_WIT);
		List<LispVal> out = WitImportInliner.inline(
				LispReader.readAllFromString(
						"(rontolisp:wit-import \"wit/gl.wit\" :interface \"local:webgl/gl\" :package gl)\n" + BODY),
				this.tempDir.toString(), WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem());
		assertThat(String.join("\n", out.stream().map(LispVal::print).toList()))
			.contains("(RONTOLISP:WASM-IMPORT (QUOTE GL:create-shader)");
	}

	/**
	 * THE ACCEPTANCE TEST. The Preview 1 lowering is not "equivalent to" a hand-written
	 * import block -- it is the same block, so the module is byte-identical to the one
	 * the hand-written program emits.
	 */
	@Test
	void preview1ImportsAreByteIdenticalToTheHandWrittenImportBlock() throws IOException {
		assertThat(compile(witProgram(), false)).isEqualTo(compile(handWritten(), false));
	}

	/**
	 * And byte-identical again under {@code --optimize}: the tree shaker drops
	 * {@code clear-color} -- the one imported function the body never calls -- out of
	 * BOTH modules, which is only possible because the directive lowered to ordinary
	 * {@code wasm-import} directives the shaker already understands.
	 */
	@Test
	void optimizeShakesTheUncalledImportOutOfBothProgramsIdentically() throws IOException {
		byte[] optimized = compile(witProgram(), true);
		assertThat(optimized).isEqualTo(compile(handWritten(), true));
		// The shaker really ran (and so the byte-identity above is not identity between
		// two un-shaken modules).
		assertThat(optimized.length).isLessThan(compile(witProgram(), false).length);
	}

	/**
	 * On the interpreter and the JVM the same WIT lowers to ordinary defuns dispatching
	 * through the interface's provider: a resource method takes the handle as its first
	 * argument ({@code bucket.get} -> {@code (kv:bucket-get self key)}), and the WIT
	 * parameter names become the lambda list verbatim. Because each binding is an
	 * <em>ordinary defun</em>, {@code #'kv:bucket-get} / {@code funcall} / {@code mapcar}
	 * need no wiring at all -- that end of it is pinned by running the program, in
	 * {@code LispEvaluatorTest} and {@code JvmLispCompilerTest}.
	 */
	@Test
	void theInterpreterAndJvmLoweringIsOneOrdinaryDefunPerWitFunction() {
		List<LispVal> out = WitImportInliner.inline(LispReader.readAllFromString(KV_DIRECTIVE), null,
				WitExportDirective.Backend.OTHER, uploads(Map.of("kv.wit", KV_WIT)));
		assertThat(out.stream().map(LispVal::print).toList()).containsExactly(
				"(DEFPACKAGE KV (:USE CL) (:EXPORT bucket-get bucket-set open))",
				"(DEFUN KV:bucket-get (self key) "
						+ "(RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"bucket-get\" self key))",
				"(DEFUN KV:bucket-set (self key value) "
						+ "(RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"bucket-set\" self key value))",
				"(DEFUN KV:open (identifier) "
						+ "(RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"open\" identifier))");
	}

	/**
	 * The host is the provider on the WASM backends, so a top-level
	 * {@code rontolisp:wit-provide} is inert there -- dropped, not a compile error -- and
	 * one source runs on every backend. Everywhere a provider IS dispatched (the
	 * interpreter, the JVM) the binding is kept.
	 */
	@Test
	void aProviderBindingIsDroppedOnTheWasmBackendsAndKeptWhereItIsDispatched() {
		String program = """
				(defun my-gl (member &rest args) 0)
				(rontolisp:wit-provide "local:webgl/gl" #'my-gl)
				""";
		assertThat(WitImportInliner
			.inline(LispReader.readAllFromString(program), null, WitExportDirective.Backend.WASM_GC,
					SourceLoader.fileSystem())
			.stream()
			.map(LispVal::print)
			.toList()).containsExactly("(DEFUN MY-GL (MEMBER &REST ARGS) 0)");
		assertThat(WitImportInliner
			.inline(LispReader.readAllFromString(program), null, WitExportDirective.Backend.WASM_NO_GC,
					SourceLoader.fileSystem())
			.stream()
			.map(LispVal::print)
			.toList()).containsExactly("(DEFUN MY-GL (MEMBER &REST ARGS) 0)");
		assertThat(WitImportInliner
			.inline(LispReader.readAllFromString(program), null, WitExportDirective.Backend.OTHER,
					SourceLoader.fileSystem())
			.stream()
			.map(LispVal::print)
			.toList()).containsExactly("(DEFUN MY-GL (MEMBER &REST ARGS) 0)",
					"(RONTOLISP:WIT-PROVIDE \"local:webgl/gl\" (FUNCTION MY-GL))");
	}

	/**
	 * The WIT runtime ({@code wit.lisp}: the provider registry, {@code wit-provide} and
	 * the {@code wit-error} condition -- the provider MECHANISM, and nothing else) is
	 * spliced exactly when the lowering produced {@code %wit-call} bodies to back. A
	 * program that binds nothing -- and a WASM program, whose bindings ARE
	 * {@code wasm-import} directives with the host as the provider -- is returned
	 * unchanged, so its output stays byte-identical to a build that never knew about the
	 * library.
	 */
	@Test
	void theWitRuntimeIsSplicedExactlyWhenTheLoweringNeedsIt() {
		List<LispVal> plain = LispReader.readAllFromString("(defun f (x) x)");
		assertThat(WitLibrary.process(plain)).isSameAs(plain);

		List<LispVal> wasm = WitImportInliner.inline(LispReader.readAllFromString(DIRECTIVE + BODY), null,
				WitExportDirective.Backend.WASM_GC, uploads(Map.of("gl.wit", GL_WIT)));
		assertThat(WitLibrary.process(wasm)).isSameAs(wasm);

		List<LispVal> provider = WitImportInliner.inline(LispReader.readAllFromString(KV_DIRECTIVE), null,
				WitExportDirective.Backend.OTHER, uploads(Map.of("kv.wit", KV_WIT)));
		List<LispVal> spliced = WitLibrary.process(provider);
		assertThat(spliced).isNotSameAs(provider)
			.hasSizeGreaterThan(provider.size())
			// The library goes in FRONT of the program it backs; the program is
			// untouched.
			.endsWith(provider.toArray(new LispVal[0]));
		assertThat(String.join("\n", spliced.stream().map(LispVal::print).toList()))
			.contains("(DEFUN RONTOLISP::%WIT-CALL")
			.contains("(DEFUN RONTOLISP:WIT-PROVIDE")
			// ...and the runtime is the MECHANISM only: it binds a provider for NOTHING.
			// The core knows how to dispatch a WIT interface; it does not know what
			// wasi:keyvalue is, and ships an implementation of it (or of any other
			// interface) nowhere -- a store is user code, as in examples/wit/keyvalue.
			// A top-level (rontolisp:wit-provide "<interface>" ...) here would be the
			// core privileging one third-party spec, version-pinned and name-pinned.
			.doesNotContain("(RONTOLISP:WIT-PROVIDE \"");
		// Idempotent: the spliced program already defines %wit-call, so a second pass
		// cannot prepend a second copy of the runtime.
		assertThat(WitLibrary.process(spliced)).isSameAs(spliced);
	}

	// The wit-import program: the directive plus the shared body, lowered for Preview 1
	// WASM through the filesystem loader.
	private List<LispVal> witProgram() throws IOException {
		Files.writeString(this.tempDir.resolve("gl.wit"), GL_WIT);
		return WitImportInliner.inline(LispReader.readAllFromString(DIRECTIVE + BODY), this.tempDir.toString(),
				WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem());
	}

	private List<LispVal> handWritten() {
		return LispReader.readAllFromString(HAND_WRITTEN + BODY);
	}

	private static byte[] compile(List<LispVal> program, boolean optimize) {
		return new WasmLispCompiler(false, false, false, optimize, false, false).compile(program);
	}

}
