package am.ik.rontolisp.eval;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.codegen.wasm.NoGcWasmCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The acceptance test of {@code rontolisp:wit-export} ({@code .kb/wit.md}): a program
 * that implements a WIT world must compile to the <strong>byte-identical</strong>
 * artifact the equivalent hand-written {@code rontolisp:wasm-export} program produces.
 * The directive is a compile-time front-end for machinery that already exists -- if a
 * single byte moved, it would be a new export path instead.
 *
 * <p>
 * Both halves of each A/B pair are compiled in the same JVM, from the same source apart
 * from the directive, so nothing but the directive can explain a difference.
 */
class WitExportInlinerTest {

	@TempDir
	Path tempDir;

	/**
	 * A world compiles with no filesystem at all: the WIT text comes from an injected
	 * {@link SourceLoader}, which is what lets the browser playground -- whose "files"
	 * are an in-memory map of uploads and which never touches {@code java.nio.file} --
	 * run the same contract check its REPL does. Before this the compile buttons met the
	 * directive itself and died with an unhelpful "Cannot compile: rontolisp:wit-export".
	 */
	@Test
	void aWorldIsReadThroughTheSourceLoaderSoABrowserCanCompileOne() {
		Map<String, String> uploads = Map.of("world.wit", WORLD);
		List<LispVal> out = WitExportInliner.inline(
				LispReader.readAllFromString(BODY + "(rontolisp:wit-export \"world.wit\")"), null,
				WitExportDirective.Backend.WASM_GC, path -> {
					String content = uploads.get(path);
					if (content == null) {
						throw new FileNotFoundException(path + " (upload it first)");
					}
					return content;
				});
		assertThat(String.join("\n", out.stream().map(LispVal::print).toList())).doesNotContain("wit-export")
			.contains("(RONTOLISP:WASM-EXPORT (QUOTE COUNT-VOWELS) :PARAMS (QUOTE (:STRING)) "
					+ ":PARAM-NAMES (QUOTE (s)) :RETURNS :S32)");
	}

	@Test
	void aWorldMissingFromTheLoaderNamesTheLoadersOwnError() {
		assertThatThrownBy(() -> WitExportInliner.inline(
				LispReader.readAllFromString(BODY + "(rontolisp:wit-export \"world.wit\")"), null,
				WitExportDirective.Backend.WASM_GC, path -> {
					throw new FileNotFoundException(path + " (upload it first; available: )");
				}))
			.hasMessageContaining("cannot read WIT file world.wit");
	}

	/**
	 * The body every variant shares: one exported defun taking a string, returning an
	 * int.
	 */
	private static final String BODY = """
			(defun vowelp (c)
			  (or (char= c #\\a) (char= c #\\e) (char= c #\\i) (char= c #\\o) (char= c #\\u)))
			(defun count-vowels (s)
			  (let ((n 0))
			    (dotimes (i (length s))
			      (when (vowelp (char s i))
			        (setq n (+ n 1))))
			    n))
			""";

	/** The hand-written export the world below stands for, parameter name included. */
	private static final String HAND_WRITTEN = "(rontolisp:wasm-export 'count-vowels :params '(:string) "
			+ ":param-names '(s) :returns :int)";

	private static final String WORLD = """
			package root:component;

			world root {
			  export count-vowels: func(s: string) -> s32;
			}
			""";

	/**
	 * Compiles the wit-export program and the hand-written one, and asserts the two
	 * artifacts are byte-identical.
	 * @param prelude extra top-level forms both programs carry (they select the component
	 * blob variant)
	 * @param compile the backend under test
	 * @param backend the backend the world is checked against
	 */
	private void assertByteIdentical(String prelude, Function<List<LispVal>, byte[]> compile,
			WitExportDirective.Backend backend) throws IOException {
		assertByteIdentical(prelude, compile, backend, WORLD, HAND_WRITTEN);
	}

	private void assertByteIdentical(String prelude, Function<List<LispVal>, byte[]> compile,
			WitExportDirective.Backend backend, String world, String handWrittenExport) throws IOException {
		Path wit = this.tempDir.resolve("world.wit");
		Files.writeString(wit, world);
		List<LispVal> witProgram = WitExportInliner.inline(
				LispReader.readAllFromString(prelude + BODY + "(rontolisp:wit-export \"world.wit\")"),
				this.tempDir.toString(), backend, SourceLoader.fileSystem());
		List<LispVal> handWritten = LispReader.readAllFromString(prelude + BODY + handWrittenExport);
		assertThat(compile.apply(witProgram)).isEqualTo(compile.apply(handWritten));
	}

	@Test
	void usesWitExportDetectsTheDirective() {
		assertThat(WitExportInliner.usesWitExport(LispReader.readAllFromString("(rontolisp:wit-export \"w.wit\")")))
			.isTrue();
		assertThat(WitExportInliner
			.usesWitExport(LispReader.readAllFromString("(rontolisp:wasm-export 'f :params '(:int))"))).isFalse();
	}

	@Test
	void aProgramWithoutTheDirectiveIsUntouched() {
		List<LispVal> program = LispReader.readAllFromString("(defun f (x) x)");
		assertThat(
				WitExportInliner.inline(program, null, WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem()))
			.isSameAs(program);
	}

	@Test
	void inliningReplacesTheDirectiveWithTheWorldsExports() throws IOException {
		Path wit = this.tempDir.resolve("world.wit");
		Files.writeString(wit, WORLD);
		List<LispVal> out = WitExportInliner.inline(
				LispReader.readAllFromString(BODY + "(rontolisp:wit-export \"world.wit\")"), this.tempDir.toString(),
				WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem());
		String printed = String.join("\n", out.stream().map(LispVal::print).toList());
		assertThat(printed).doesNotContain("wit-export")
			.contains("(RONTOLISP:WASM-EXPORT (QUOTE COUNT-VOWELS) :PARAMS (QUOTE (:STRING)) "
					+ ":PARAM-NAMES (QUOTE (s)) :RETURNS :S32)")
			.contains("(DEFUN COUNT-VOWELS");
	}

	@Test
	void gcComponentIsByteIdenticalToTheHandWrittenExport() throws IOException {
		// The base component blob variant.
		assertByteIdentical("",
				program -> new WasmLispCompiler(false, true, false, false, false, false).compile(program),
				WitExportDirective.Backend.WASM_GC);
	}

	@Test
	void gcSocketsComponentIsByteIdenticalToTheHandWrittenExport() throws IOException {
		// rontolisp:tcp-* rides the sockets.lisp user import (wasi:sockets@0.3.0);
		// both programs get the identical splice, so the wit-export byte-identity
		// property holds on a socket-importing component too.
		assertByteIdentical("(defun listen () (rontolisp:tcp-listen 7777))\n",
				program -> new WasmLispCompiler(false, true, false, false, false, false).compile(WitLibrary.process(
						StdinLibrary.process(SocketsLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT),
								WitExportDirective.Backend.WASM_COMPONENT, false))),
				WitExportDirective.Backend.WASM_GC);
	}

	@Test
	void preview1ModuleIsByteIdenticalToTheHandWrittenExport() throws IOException {
		// No component: the export is a plain core export, whose WASM parameters have no
		// names at all -- so the world's parameter names must not leak into the bytes.
		assertByteIdentical("",
				program -> new WasmLispCompiler(false, false, false, false, false, false).compile(program),
				WitExportDirective.Backend.WASM_GC);
	}

	@Test
	void noGcModuleIsByteIdenticalToTheHandWrittenExport() throws IOException {
		assertByteIdentical("", program -> new NoGcWasmCompiler(false, false, false).compile(program),
				WitExportDirective.Backend.WASM_NO_GC);
	}

	@Test
	void noGcComponentIsByteIdenticalToTheHandWrittenExport() throws IOException {
		assertByteIdentical("", program -> new NoGcWasmCompiler(false, false, true).compile(program),
				WitExportDirective.Backend.WASM_NO_GC);
	}

	@Test
	void anUnsignedWorldIsByteIdenticalToTheHandWrittenExport() throws IOException {
		// wit-export stays a pure front-end for the widened vocabulary too: a world
		// declaring u32 lowers into exactly the wasm-export a hand-written program
		// carries, so the new types add no emit path of their own.
		String world = """
				package root:component;

				world root {
				  export count-vowels: func(s: string) -> u32;
				}
				""";
		String handWritten = "(rontolisp:wasm-export 'count-vowels :params '(:string) "
				+ ":param-names '(s) :returns :u32)";
		assertByteIdentical("",
				program -> new WasmLispCompiler(false, true, false, false, false, false).compile(program),
				WitExportDirective.Backend.WASM_GC, world, handWritten);
		assertByteIdentical("", program -> new NoGcWasmCompiler(false, false, true).compile(program),
				WitExportDirective.Backend.WASM_NO_GC, world, handWritten);
	}

	@Test
	void theLegacyIntSpellingCompilesToTheSameBytesAsItsWitSpelling() throws IOException {
		// :int and :long are permanent aliases of :s32 / :s64, normalized at parse time.
		// That is the whole reason every program written against the pre-WIT vocabulary
		// keeps producing the artifact it always did.
		assertByteIdentical("",
				program -> new WasmLispCompiler(false, true, false, false, false, false).compile(program),
				WitExportDirective.Backend.WASM_GC, WORLD,
				"(rontolisp:wasm-export 'count-vowels :params '(:string) " + ":param-names '(s) :returns :s32)");
	}

	@Test
	void theEmittedWitReproducesTheWorldItWasHanded() throws IOException {
		// --emit-wit becomes a consistency check rather than a generator: emitting the
		// world
		// we
		// were handed must reproduce it (parameter names included -- that is what
		// :param-names is for).
		Path wit = this.tempDir.resolve("world.wit");
		Files.writeString(wit, WORLD);
		WasmLispCompiler compiler = new WasmLispCompiler(false, true, false, false, false, false);
		compiler.compile(
				WitExportInliner.inline(LispReader.readAllFromString(BODY + "(rontolisp:wit-export \"world.wit\")"),
						this.tempDir.toString(), WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem()));
		assertThat(compiler.componentWit()).contains("  export count-vowels: func(s: string) -> s32;");
	}

	@Test
	void aHandWrittenWasmExportAlongsideAWorldIsACompileError() throws IOException {
		// The world is the program's authoritative export list; a second, hand-maintained
		// one is exactly the drift the directive exists to prevent.
		Path wit = this.tempDir.resolve("world.wit");
		Files.writeString(wit, WORLD);
		List<LispVal> program = LispReader
			.readAllFromString(BODY + HAND_WRITTEN + "\n(rontolisp:wit-export \"world.wit\")");
		assertThatThrownBy(() -> WitExportInliner.inline(program, this.tempDir.toString(),
				WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("rontolisp:wasm-export cannot be combined with rontolisp:wit-export")
			.hasMessageContaining("COUNT-VOWELS");
	}

	@Test
	void aServeComponentCannotAlsoImplementAWorld() throws Exception {
		// A serve-mode component's only export is wasi:http/incoming-handler (the builder
		// lifts no user exports there), so a world of function exports could not be
		// honored: say so instead of dropping it. The check lives in the CLI, where the
		// serve mode is decided.
		Path wit = this.tempDir.resolve("world.wit");
		Files.writeString(wit, WORLD);
		Path lisp = this.tempDir.resolve("serve.lisp");
		Files.writeString(lisp, BODY + """
				(defun handle (env) (list 200 nil (list "hi")))
				(rontolisp:http-handler 'handle)
				(rontolisp:wit-export "world.wit")
				""");
		RontoLispCli cli = new RontoLispCli(new java.io.ByteArrayInputStream(new byte[0]),
				new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
		assertThatThrownBy(() -> cli
			.run(new String[] { lisp.toString(), "-o", this.tempDir.resolve("serve.wasm").toString(), "--component" }))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("rontolisp:wit-export cannot be combined with rontolisp:http-handler");
	}

	@Test
	void checksDefunsThatOnlyExistAfterMacroExpansion() throws IOException {
		// The pass runs after LoadInliner and UserMacroExpander (which also flattens
		// top-level progn/eval-when), so every defun the program has is a literal
		// top-level form by the time the world is checked. A defun produced by a macro,
		// or
		// wrapped in eval-when, must satisfy the contract like any other -- were the
		// order
		// reversed, this program would fail with "no matching (defun ...)".
		Path wit = this.tempDir.resolve("world.wit");
		Files.writeString(wit, WORLD);
		List<LispVal> expanded = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro define-counter (name) `(defun ,name (s) (length s)))
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (define-counter count-vowels))
				(rontolisp:wit-export "world.wit")
				"""));
		List<LispVal> out = WitExportInliner.inline(expanded, this.tempDir.toString(),
				WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem());
		assertThat(String.join("\n", out.stream().map(LispVal::print).toList()))
			.contains("(RONTOLISP:WASM-EXPORT (QUOTE COUNT-VOWELS) :PARAMS (QUOTE (:STRING)) "
					+ ":PARAM-NAMES (QUOTE (s)) :RETURNS :S32)");
	}

	@Test
	void anUnreadableWitFileIsAClearError() {
		List<LispVal> program = LispReader.readAllFromString(BODY + "(rontolisp:wit-export \"missing.wit\")");
		assertThatThrownBy(() -> WitExportInliner.inline(program, this.tempDir.toString(),
				WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem()))
			.hasMessageContaining("cannot read WIT file")
			.hasMessageContaining("missing.wit");
	}

	@Test
	void theWitPathResolvesAgainstTheSourceFilesDirectory() throws IOException {
		// Like load: a relative path is read relative to the program, not the working
		// directory.
		Path dir = Files.createDirectories(this.tempDir.resolve("wit"));
		Files.writeString(dir.resolve("world.wit"), WORLD);
		List<LispVal> out = WitExportInliner.inline(
				LispReader.readAllFromString(BODY + "(rontolisp:wit-export \"wit/world.wit\")"),
				this.tempDir.toString(), WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem());
		assertThat(String.join("\n", out.stream().map(LispVal::print).toList()))
			.contains("(RONTOLISP:WASM-EXPORT (QUOTE COUNT-VOWELS)");
	}

	@Test
	void theLoweredProgramStillPrunesCorrectly() throws IOException {
		// The directive is inlined BEFORE LibraryDefunPruner runs, so the synthesized
		// wasm-export directives and the defuns they name are pruning roots like any
		// hand-written ones: the exported defun and the library functions it reaches
		// survive, and an unreachable library defun still goes.
		Path wit = this.tempDir.resolve("world.wit");
		Files.writeString(wit, """
				package root:component;

				world root {
				  export greet: func(name: string) -> string;
				}
				""");
		List<LispVal> program = UrlLibrary.process(LispReader.readAllFromString(
				"(defun greet (name) (rontolisp:url-encode name))\n" + "(rontolisp:wit-export \"world.wit\")"));
		List<LispVal> lowered = WitExportInliner.inline(program, this.tempDir.toString(),
				WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem());
		String printed = String.join("\n", LibraryDefunPruner.prune(lowered).stream().map(LispVal::print).toList());
		assertThat(printed).contains("(DEFUN GREET")
			.contains("(RONTOLISP:WASM-EXPORT (QUOTE GREET)")
			.contains("(DEFUN RONTOLISP:URL-ENCODE")
			// url-decode is spliced with the library but unreachable from the export.
			.doesNotContain("(defun rontolisp:url-decode");
		// And the pruned program still compiles as a component.
		assertThat(new WasmLispCompiler(false, true, false, false, false, false)
			.compile(LibraryDefunPruner.prune(lowered))).isNotEmpty();
	}

}
