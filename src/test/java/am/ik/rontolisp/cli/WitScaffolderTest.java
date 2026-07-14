package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.WitExportInliner;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WitScaffolder} and the {@code --scaffold-wit} CLI mode: "someone
 * handed me a {@code .wit}, now what".
 *
 * <p>
 * The definition-of-done item the last test pins: the generated file must <strong>compile
 * unchanged</strong> -- the stubs signal at run time, not at compile time, so the
 * contract check passes and the world can be filled in one export at a time.
 */
class WitScaffolderTest {

	@TempDir
	Path tempDir;

	private static final String WORLD = """
			package root:component;

			world analyzer {
			  /// Count the vowels in the given text.
			  /// Case-insensitive.
			  export count-vowels: func(s: string) -> s32;
			  export shout: async func(text: string, loud: bool) -> string;
			  export ping: func();
			}
			""";

	private String runCli(String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out));
		cli.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void scaffoldsOneStubPerExportWithTheWitParameterNames() {
		String lisp = WitScaffolder.scaffold(WORLD, "analyzer.wit", null);
		// The directive names the world, so the generated file is checked against it.
		assertThat(lisp).contains("(rontolisp:wit-export \"analyzer.wit\" :world analyzer)");
		// One stub per export, parameters spelled as the WIT spells them.
		assertThat(lisp).contains("(defun count-vowels (s)")
			.contains("(defun shout (text loud)")
			.contains("(defun ping ()");
		// The bodies signal rather than fail to compile.
		assertThat(lisp).contains("(error \"count-vowels is not implemented yet\"))")
			.contains("(error \"ping is not implemented yet\"))");
		assertThat(lisp).endsWith("\n");
	}

	@Test
	void carriesTheWitDocCommentsAndTheSignature() {
		String lisp = WitScaffolder.scaffold(WORLD, "analyzer.wit", null);
		assertThat(lisp).contains("""
				;;; Count the vowels in the given text.
				;;; Case-insensitive.
				;;; WIT: count-vowels: func(s: string) -> s32
				(defun count-vowels (s)
				""");
		// An async func is rendered as such: the contract states the lift instead of
		// leaving it to be guessed.
		assertThat(lisp).contains(";;; WIT: shout: async func(text: string, loud: bool) -> string");
		assertThat(lisp).contains(";;; WIT: ping: func()");
	}

	@Test
	void namesTheWorldItImplementsInTheHeader() {
		assertThat(WitScaffolder.scaffold(WORLD, "wit/analyzer.wit", "analyzer"))
			.startsWith(";;;; Implementation of the WIT world 'analyzer' (wit/analyzer.wit).\n");
	}

	@Test
	void scaffoldsTheNamedWorldOfAMultiWorldFile() {
		String wit = """
				package root:component;

				world first {
				  export a: func();
				}

				world second {
				  export b: func();
				}
				""";
		assertThat(WitScaffolder.scaffold(wit, "w.wit", "second")).contains("(defun b ()")
			.contains(":world second)")
			.doesNotContain("(defun a ()");
	}

	@Test
	void rejectsAMultiWorldFileWithoutAWorldOption() {
		String wit = """
				package root:component;

				world first {
				  export a: func();
				}

				world second {
				  export b: func();
				}
				""";
		assertThatThrownBy(() -> WitScaffolder.scaffold(wit, "w.wit", null))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("--scaffold-wit: w.wit declares 2 worlds (first, second); name one with --world");
		assertThatThrownBy(() -> WitScaffolder.scaffold(wit, "w.wit", "third"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("--scaffold-wit: w.wit has no world named 'third' (found: first, second)");
	}

	@Test
	void rejectsAFileWithNoWorld() {
		assertThatThrownBy(() -> WitScaffolder.scaffold("package root:component;\n", "w.wit", null))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("--scaffold-wit: w.wit declares no world");
	}

	@Test
	void cliWritesTheSkeletonToTheOutputFile() throws Exception {
		Path wit = this.tempDir.resolve("analyzer.wit");
		Files.writeString(wit, WORLD);
		Path lisp = this.tempDir.resolve("analyzer.lisp");
		runCli("--scaffold-wit", wit.toString(), "-o", lisp.toString());
		// The path the generated source names resolves against the generated file's own
		// directory (both are in tempDir here), the way wit-export -- like load -- reads
		// it.
		assertThat(Files.readString(lisp)).contains("(rontolisp:wit-export \"analyzer.wit\" :world analyzer)")
			.contains("(defun count-vowels (s)");
	}

	@Test
	void cliPrintsTheSkeletonWithoutAnOutputFile() throws Exception {
		Path wit = this.tempDir.resolve("analyzer.wit");
		Files.writeString(wit, WORLD);
		String output = runCli("--scaffold-wit", wit.toString());
		assertThat(output).contains("(defun count-vowels (s)")
			.contains("(rontolisp:wit-export \"" + wit + "\" :world analyzer)");
	}

	@Test
	void cliReportsAMultiWorldFile() throws Exception {
		Path wit = this.tempDir.resolve("two.wit");
		Files.writeString(wit, """
				package root:component;

				world first {
				  export a: func();
				}

				world second {
				  export b: func();
				}
				""");
		assertThatThrownBy(() -> runCli("--scaffold-wit", wit.toString()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("declares 2 worlds (first, second); name one with --world");
	}

	@Test
	void theScaffoldedProgramCompilesUnchanged() throws Exception {
		// The definition-of-done item: feed the generated source straight back through
		// the
		// compiler. It must pass its own contract check (every export has a defun of the
		// right arity) and compile -- the stubs signal at RUN time.
		Path wit = this.tempDir.resolve("analyzer.wit");
		Files.writeString(wit, """
				package root:component;

				world analyzer {
				  /// Count the vowels in the given text.
				  export count-vowels: func(s: string) -> s32;
				  export ping: func();
				}
				""");
		Path lisp = this.tempDir.resolve("analyzer.lisp");
		runCli("--scaffold-wit", wit.toString(), "-o", lisp.toString());
		assertThatCode(() -> new WasmLispCompiler(false, true, false, false, false, false)
			.compile(WitExportInliner.inline(LispReader.readAllFromString(Files.readString(lisp)),
					this.tempDir.toString(), WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem())))
			.doesNotThrowAnyException();
		// And end-to-end through the CLI, which is how a user meets it.
		Path wasm = this.tempDir.resolve("analyzer.wasm");
		runCli(lisp.toString(), "-o", wasm.toString(), "--component");
		assertThat(Files.exists(wasm)).isTrue();
		assertThat(Files.readAllBytes(wasm)).startsWith(new byte[] { 0x00, 0x61, 0x73, 0x6D });
	}

}
