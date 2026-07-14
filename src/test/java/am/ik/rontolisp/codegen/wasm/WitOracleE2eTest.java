package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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
				(rontolisp:wasm-export 'pure-add :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'scale :params '(:float :float) :returns :float)
				(rontolisp:wasm-export 'evenp2 :params '(:int) :returns :bool)
				(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
				(rontolisp:wasm-export 'swap :params '(:s-expr) :returns :s-expr)
				(rontolisp:wasm-export 'noisy-mul :params '(:int :int) :returns :int :async t)
				(rontolisp:wasm-export 'side-effect :params '(:int) :returns nil :as "drop-it")
				(print "top level ran")
				"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
	}

	@Test
	void fetchAndSocketVariantWitsMatchWasmToolsByteForByte() throws Exception {
		WasmLispCompiler http = new WasmLispCompiler(false, true);
		byte[] httpComponent = http
			.compile(LispReader.readAllFromString("(print (rontolisp:fetch \"http://127.0.0.1:9/\"))"));
		assertThat(http.componentWit()).isEqualTo(oracle(httpComponent));
		WasmLispCompiler sock = new WasmLispCompiler(false, true);
		byte[] sockComponent = sock.compile(LispReader.readAllFromString("(close (rontolisp:tcp-listen 7777))"));
		assertThat(sock.componentWit()).isEqualTo(oracle(sockComponent));
	}

	@Test
	void noGcComponentWitsMatchWasmToolsByteForByte() throws Exception {
		NoGcWasmCompiler plain = new NoGcWasmCompiler(false, false, true);
		byte[] plainComponent = plain.compile(LispReader.readAllFromString("""
				(defun big-add (a b) (+ a b))
				(defun shout (s) (concatenate 'string s "!"))
				(rontolisp:wasm-export 'big-add :params '(:long :long) :returns :long)
				(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
				"""));
		assertThat(plain.componentWit()).isEqualTo(oracle(plainComponent));
		NoGcWasmCompiler print = new NoGcWasmCompiler(false, false, true);
		byte[] printComponent = print.compile(LispReader.readAllFromString("""
				(defun hello () (print "hi"))
				(rontolisp:wasm-export 'hello)
				"""));
		assertThat(print.componentWit()).isEqualTo(oracle(printComponent));
	}

	@Test
	void serveComponentWitMatchesWasmToolsModuloTheRestoredUseClause() throws Exception {
		// wasm-tools drops incoming-handler's `use types.{...};` and prints a WIT that
		// does not parse; the template restores it, so the emitted text is the oracle
		// plus exactly that clause -- and, unlike the oracle, round-trips the parser.
		List<am.ik.rontolisp.LispVal> program = am.ik.rontolisp.cli.HttpHandlerInliner
			.inline(LispReader.readAllFromString("""
					(defun h (r) (list :status 200 :body "x"))
					(rontolisp:http-handler 'h)
					"""));
		WasmLispCompiler compiler = new WasmLispCompiler(false, true, false, false, true);
		byte[] component = compiler.compile(program);
		String useClause = "    use types.{incoming-request, response-outparam};\n\n";
		String wit = Objects.requireNonNull(compiler.componentWit());
		assertThat(wit).contains(useClause);
		assertThat(wit.replace(useClause, "")).isEqualTo(oracle(component));
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
		List<am.ik.rontolisp.LispVal> program = am.ik.rontolisp.eval.WitImportInliner.inline(
				LispReader.readAllFromString("""
						(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0-draft" :package kv)
						(defun handle (request)
						  (list :status 200 :body (kv:bucket-get (kv:open "") (getf request :path))))
						(rontolisp:http-handler 'handle)
						"""), tempDir.toString(), am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT,
				am.ik.rontolisp.eval.SourceLoader.fileSystem());
		program = am.ik.rontolisp.cli.HttpHandlerInliner.inline(am.ik.rontolisp.eval.WitLibrary.process(program));
		WasmLispCompiler compiler = new WasmLispCompiler(false, true, false, false, true);
		byte[] component = compiler.compile(program);
		String useClause = "    use types.{incoming-request, response-outparam};\n\n";
		String wit = Objects.requireNonNull(compiler.componentWit());
		assertThat(wit).contains("  import wasi:keyvalue/store@0.2.0-draft;");
		assertThat(wit.replace(useClause, "")).isEqualTo(oracle(component));
	}

}
