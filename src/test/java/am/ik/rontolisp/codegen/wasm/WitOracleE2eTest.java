package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code --emit-wit} contract against the real tool: the WIT text recorded by
 * a component compile is byte-identical to what {@code wasm-tools component wit} prints
 * for the same bytes (the serve variants differ by exactly the {@code use} clause that
 * tool omits and the template restores -- see {@link WitEmitter}). Runs only where a
 * {@code wasm-tools} binary is on {@code PATH} (locally; the Docker integration image has
 * only wasmtime), so the always-on line-level pins live in {@link WitEmitterTest}.
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WitOracleE2eTest#wasmToolsIsAvailable")
class WitOracleE2eTest {

	@TempDir
	Path tempDir;

	static boolean wasmToolsIsAvailable() {
		try {
			return new ProcessBuilder("wasm-tools", "--version").start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	private String oracle(byte[] component) throws Exception {
		Path file = tempDir.resolve("component.wasm");
		Files.write(file, component);
		Process process = new ProcessBuilder("wasm-tools", "component", "wit", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as(output).isZero();
		return output;
	}

	@Test
	void gcComponentWitMatchesWasmToolsByteForByte() throws Exception {
		// Every export-line shape at once: scalars, :string/:s-expr, :async, :as, void.
		WasmLispCompiler compiler = new WasmLispCompiler(false, true);
		byte[] component = compiler.compile(LispReader.readAllFromString("""
				(defun pure-add (a b) (+ a b))
				(defun scale (x f) (* x f))
				(defun evenp2 (n) (= 0 (mod n 2)))
				(defun greet (s) (concatenate 'string "hello " s))
				(defun swap (e) (list (cadr e) (car e)))
				(defun noisy-mul (a b) (print (* a b)) (* a b))
				(defun side-effect (n) n)
				(defun narrow (a b c d) (+ a b c d))
				(defun wide (a b) (+ a b))
				(rontolisp:wasm-export 'pure-add :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'scale :params '(:float :float) :returns :float)
				(rontolisp:wasm-export 'evenp2 :params '(:int) :returns :bool)
				(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
				(rontolisp:wasm-export 'swap :params '(:s-expr) :returns :s-expr)
				(rontolisp:wasm-export 'noisy-mul :params '(:int :int) :returns :int :async t)
				(rontolisp:wasm-export 'side-effect :params '(:int) :returns nil :as "drop-it")
				(rontolisp:wasm-export 'narrow :params '(:s8 :s16 :u8 :u16) :returns :u8)
				(rontolisp:wasm-export 'wide :params '(:s32 :u32) :returns :u32)
				(print "top level ran")
				"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
	}

	@Test
	void gcInterfaceExportWitMatchesWasmToolsByteForByte() throws Exception {
		// An interface export (`export docs:adder/add@0.1.0`) bundles its functions into
		// an
		// exported component instance and reconstructs into a trailing package block. The
		// whole document -- the `export <id>;` reference plus the package block -- must
		// byte-match what wasm-tools prints for that exported instance. This is what a
		// world
		// with a separated interface (`rontolisp:wit-export`, which lowers into exactly
		// these :interface directives) rides on.
		WasmLispCompiler compiler = new WasmLispCompiler(false, true);
		byte[] component = compiler.compile(LispReader.readAllFromString(
				"""
						(defun add (x y) (+ x y))
						(defun negate (x) (- x))
						(rontolisp:wasm-export 'add :params '(:int :int) :param-names '(x y) :returns :int :interface "docs:adder/add@0.1.0")
						(rontolisp:wasm-export 'negate :params '(:int) :param-names '(x) :returns :int :interface "docs:adder/add@0.1.0")
						"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
	}

	@Test
	void gcMixedFlatAndInterfaceExportsMatchWasmToolsByteForByte() throws Exception {
		// A world can mix a freestanding function export with an interface export: the
		// flat
		// export stays a top-level function, the interface export becomes an instance,
		// and
		// both the export ORDER and the trailing package block must byte-match
		// wasm-tools.
		WasmLispCompiler compiler = new WasmLispCompiler(false, true);
		byte[] component = compiler.compile(LispReader.readAllFromString(
				"""
						(defun ping (n) n)
						(defun add (x y) (+ x y))
						(rontolisp:wasm-export 'ping :params '(:int) :param-names '(n) :returns :int)
						(rontolisp:wasm-export 'add :params '(:int :int) :param-names '(x y) :returns :int :interface "docs:adder/add@0.1.0")
						"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
	}

	@Test
	void socketsWorldReParsesAndCarriesItsWasiSocketsImport() {
		// The rontolisp:tcp-* built-ins are the sockets.lisp library over a
		// canon-lowered wasi:sockets USER import now (the dedicated sockets blob
		// variant is gone), so like fetch its world is emitted by the user-import
		// emitter and checked structurally + by re-parsing rather than byte-for-byte
		// against wasm-tools (see the fetch test's rationale).
		WasmLispCompiler sock = new WasmLispCompiler(false, true);
		sock.compile(am.ik.rontolisp.eval.WitLibrary.process(am.ik.rontolisp.eval.StdinLibrary.process(
				am.ik.rontolisp.eval.SocketsLibrary.process(
						LispReader.readAllFromString("(close (rontolisp:tcp-listen 7777))"),
						am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT),
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, false)));
		String wit = Objects.requireNonNull(sock.componentWit());
		assertThat(wit).contains("import wasi:sockets/types@0.3.0;");
		assertThat(am.ik.wit.WitParser.parse(wit)).isNotNull();
	}

	@Test
	void fetchWorldReParsesAndCarriesItsWasiHttpImports() {
		// rontolisp:fetch is now the fetch.lisp library over canon-lowered wasi:http USER
		// imports, so its world is emitted by the cross-interface user-import emitter,
		// which
		// deliberately keeps the WIT structure that lets it re-parse -- type aliases
		// (`type headers = fields`), fully-qualified `use` clauses. `wasm-tools component
		// wit` expands the aliases and abbreviates the `use`s when it prints a
		// component's
		// raw type, so the two are semantically equal but not byte-identical. Only the
		// fixed
		// blob variants (the sockets test above, WasiWitDefinitionsTest) are pinned to
		// wasm-tools byte-for-byte; the user-import side is checked structurally and by
		// re-parsing, which is the property that actually has to hold.
		WasmLispCompiler http = new WasmLispCompiler(false, true);
		http.compile(am.ik.rontolisp.eval.WitLibrary.process(am.ik.rontolisp.eval.HttpLibrary.process(
				LispReader.readAllFromString("(print (rontolisp:fetch \"http://127.0.0.1:9/\"))"),
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, false)));
		String wit = Objects.requireNonNull(http.componentWit());
		assertThat(wit).contains("import wasi:http/types@0.3.0;").contains("import wasi:http/client@0.3.0;");
		// It re-parses through our own parser -- the deliberate deviation from wasm-tools
		// is
		// that ours stays consumable.
		assertThat(am.ik.wit.WitParser.parse(wit)).isNotNull();
	}

	@Test
	void noGcComponentWitsMatchWasmToolsByteForByte() throws Exception {
		NoGcWasmCompiler plain = new NoGcWasmCompiler(OptimizeLevel.NONE, false, true);
		byte[] plainComponent = plain.compile(LispReader.readAllFromString("""
				(defun big-add (a b) (+ a b))
				(defun shout (s) (concatenate 'string s "!"))
				(defun huge (a b) (+ a b))
				(rontolisp:wasm-export 'big-add :params '(:long :long) :returns :long)
				(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
				(rontolisp:wasm-export 'huge :params '(:u64 :u64) :returns :u64)
				"""));
		assertThat(plain.componentWit()).isEqualTo(oracle(plainComponent));
		NoGcWasmCompiler print = new NoGcWasmCompiler(OptimizeLevel.NONE, false, true);
		byte[] printComponent = print.compile(LispReader.readAllFromString("""
				(defun hello () (print "hi"))
				(rontolisp:wasm-export 'hello)
				"""));
		assertThat(print.componentWit()).isEqualTo(oracle(printComponent));
	}

	@Test
	void noGcInterfaceExportWitMatchesWasmToolsByteForByte() throws Exception {
		// The adapter-free reactor carries an interface export too: its exported instance
		// starts at component instance 0 (no run instance, no imports), and the emitted
		// WIT
		// -- `export docs:calc/ops@1.0.0;` plus its package block -- must byte-match
		// wasm-tools.
		NoGcWasmCompiler compiler = new NoGcWasmCompiler(OptimizeLevel.NONE, false, true);
		byte[] component = compiler.compile(LispReader.readAllFromString(
				"""
						(defun add (x y) (+ x y))
						(rontolisp:wasm-export 'add :params '(:long :long) :param-names '(x y) :returns :long :interface "docs:calc/ops@1.0.0")
						"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
	}

	@Test
	void serveComponentWitMatchesWasmToolsModuloTheRestoredUseClause() throws Exception {
		// wasm-tools drops the handler interface's `use types.{...};` and prints a WIT
		// that does not parse; the template restores it, so the emitted text is the
		// oracle plus exactly that clause -- and, unlike the oracle, round-trips the
		// parser.
		byte[] component = compileServeViaCli("""
				(defun h (env) (list 200 nil (list "x")))
				(rontolisp:http-handler 'h)
				""");
		String restored = "  interface handler {\n    use types.{request, response, error-code};\n\n";
		String wit = Files.readString(this.tempDir.resolve("prog.wit"));
		assertThat(wit).contains(restored);
		// Strip ONLY the restored handler clause -- the client interface's own use
		// clause is genuine and the oracle prints it too.
		assertThat(wit.replace(restored, "  interface handler {\n")).isEqualTo(oracle(component));
	}

	@Test
	void aServedComponentsUserImportIsInItsRealTypeToo() throws Exception {
		// The import side is what --emit-wit uniquely reports (the export side is a
		// fixpoint), so the oracle is the only proof that the WIT text a SERVED component
		// records is the type the component really carries -- the fixed wasi:http surface
		// plus the interface the handler calls.
		Files.writeString(tempDir.resolve("kv.wit"), """
				package wasi:keyvalue@0.2.0-draft;

				interface store {
				    variant error {
				        no-such-store,
				        access-denied,
				        other(string)
				    }
				    open: func(identifier: string) -> result<bucket, error>;
				    resource bucket {
				        get: func(key: string) -> result<option<list<u8>>, error>;
				    }
				}
				""");
		byte[] component = compileServeViaCli("""
				(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0-draft" :package kv)
				(defun handle (request)
				  (list 200 nil (list (kv:bucket-get (kv:open "") (getf request :path-info)))))
				(rontolisp:http-handler 'handle)
				""");
		String restored = "  interface handler {\n    use types.{request, response, error-code};\n\n";
		String wit = Files.readString(this.tempDir.resolve("prog.wit"));
		assertThat(wit).contains("  import wasi:keyvalue/store@0.2.0-draft;");
		assertThat(wit.replace(restored, "  interface handler {\n")).isEqualTo(oracle(component));
	}

	// Compiles source to a --component serve component through the REAL CLI (writing
	// prog.wit via --emit-wit alongside prog.wasm), so the emitted WIT and the bytes are
	// exactly what a user gets -- the pruned, full-pipeline component the
	// WasiWitDefinitions
	// fixtures are generated from. serve.lisp's HTTP glue is spliced by the CLI's
	// ServeLibrary step; a bare in-process pipeline would skip LibraryDefunPruner and
	// leave
	// redundant type declarations wasm-tools then prints twice.
	private byte[] compileServeViaCli(String source) throws Exception {
		Path lisp = this.tempDir.resolve("prog.lisp");
		Files.writeString(lisp, source);
		Path wasm = this.tempDir.resolve("prog.wasm");
		new am.ik.rontolisp.cli.RontoLispCli(new java.io.ByteArrayInputStream(new byte[0]),
				new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
			.run(new String[] { lisp.toString(), "-o", wasm.toString(), "--component", "--emit-wit" });
		return Files.readAllBytes(wasm);
	}

}
