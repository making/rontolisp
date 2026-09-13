package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks of {@code rontolisp:wasm-import} on the {@code --no-gc} backend,
 * against a JS host on plain node. The host is what nothing smaller can stand in for: a
 * preloaded wasm host has its own linear memory, so no {@code wasmtime --invoke} run can
 * read a {@code :string} argument's bytes or write a {@code :string} result's.
 *
 * <p>
 * What is pinned:
 * <ul>
 * <li>every boundary type this backend takes crosses in both directions -- the integer
 * family, {@code :float}, {@code :bool}, {@code :string} and a {@code :void} call;</li>
 * <li>the boundary carries the value exactly or TRAPS, in both directions: a house
 * {@code i64} wider than a narrow declared parameter, and a {@code :u64} result at or
 * above 2^63 that the signed house integer cannot state;</li>
 * <li>several {@code :string} arguments in one call each cross as their OWN region --
 * here they are pointers to blocks the module already holds, so there is nothing to stage
 * and nothing to alias;</li>
 * <li>a pull loop over a {@code :string}-returning import keeps linear memory flat (the
 * export wrapper's heap reset covers everything the call allocated);</li>
 * <li>none of it depends on the optimize level.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.NoGcWasmImportE2eTest#nodeIsAvailable")
class NoGcWasmImportE2eTest {

	static boolean nodeIsAvailable() {
		try {
			return new ProcessBuilder("node", "--version").start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	@TempDir
	Path tempDir;

	private static final String MODULE = """
			(rontolisp:wasm-import 'host-add :from "env" :as "add" :params '(:s32 :s32) :returns :s32)
			(rontolisp:wasm-import 'host-wide :from "env" :as "wide" :params '(:s64) :returns :s64)
			(rontolisp:wasm-import 'host-scale :from "env" :as "scale" :params '(:float) :returns :float)
			(rontolisp:wasm-import 'host-even :from "env" :as "even" :params '(:s32) :returns :bool)
			(rontolisp:wasm-import 'host-note :from "env" :as "note" :params '(:bool) :returns :void)
			(rontolisp:wasm-import 'host-join :from "env" :as "join" :params '(:string :string) :returns :string)
			(rontolisp:wasm-import 'host-log :from "env" :as "log" :params '(:string) :returns :void)

			(defun sum (a b) (host-add a b))
			(rontolisp:wasm-export 'sum :params '(:s32 :s32) :returns :s32)

			(defun wide (n) (host-wide n))
			(rontolisp:wasm-export 'wide :params '(:s64) :returns :s64)

			(defun scale (x) (host-scale x))
			(rontolisp:wasm-export 'scale :params '(:float) :returns :float)

			;; a :bool result is normalized to 0/1, so it drives an ordinary if
			(defun classify (n) (if (host-even n) 1 0))
			(rontolisp:wasm-export 'classify :params '(:s32) :returns :s32)

			;; a :void import answers nil, which is the i64 zero
			(defun note (b) (host-note b) 7)
			(rontolisp:wasm-export 'note :params '(:bool) :returns :s32)

			;; two :string arguments plus a :string result, all over blocks this module holds
			(defun greet (who) (host-log who) (length (host-join "hello" who)))
			(rontolisp:wasm-export 'greet :params '(:string) :returns :s32)

			;; a pull loop inside ONE call: the arena bracket pops each answer, so k
			;; iterations cost what one costs
			(defun pump (k)
			  (let ((n 0))
			    (dotimes (i k) (rontolisp:with-arena () (setq n (length (host-join "a" "b")))))
			    n))
			(rontolisp:wasm-export 'pump :params '(:s32) :returns :s32)
			""";

	private static final String HOST = """
			const fs = require('fs');
			const dec = new TextDecoder();
			const enc = new TextEncoder();
			let inst;
			const str = (p, n) => dec.decode(new Uint8Array(inst.exports.memory.buffer, p, n));
			const give = (s) => {
			  const b = enc.encode(s);
			  const p = inst.exports.__ronto_alloc(b.length);
			  new Uint8Array(inst.exports.memory.buffer).set(b, p);
			  return [p, b.length];
			};
			let logged = null;
			const env = {
			  add: (a, b) => a + b,
			  wide: (n) => n * 2n,
			  scale: (x) => x * 2.5,
			  even: (n) => (n % 2 === 0 ? 7 : 0),
			  note: (b) => { logged = b; },
			  // read the LAST argument first: both regions must still be live
			  join: (p1, n1, p2, n2) => { const second = str(p2, n2); return give(str(p1, n1) + ", " + second + "!"); },
			  log: (p, n) => { logged = str(p, n); },
			};
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			inst = new WebAssembly.Instance(mod, { env });
			const withString = (name, s) => {
			  const mark = inst.exports.__ronto_alloc_mark();
			  const [p, n] = give(s);
			  const out = inst.exports[name](p, n);
			  inst.exports.__ronto_alloc_reset(mark);
			  return out;
			};
			""";

	private static final String DRIVER = HOST + """
			console.log(inst.exports.sum(20, 22));
			console.log(String(inst.exports.wide(1000000000000n)));
			console.log(inst.exports.scale(4.0));
			console.log(inst.exports.classify(4), inst.exports.classify(5));
			console.log(inst.exports.note(1), logged);
			console.log(withString('greet', 'world'), logged);
			inst.exports.pump(1);
			const before = inst.exports.memory.buffer.byteLength;
			inst.exports.pump(20000);
			console.log(inst.exports.memory.buffer.byteLength === before);
			for (let i = 0; i < 20000; i++) withString('greet', 'world');
			console.log(inst.exports.memory.buffer.byteLength === before);
			""";

	private static final List<String> EXPECTED = List.of("42", "2000000000000", "10", "1 0", "7 1", "13 world", "true",
			"true");

	@Test
	void everyBoundaryTypeCrossesInBothDirections() throws Exception {
		// greet answers (length (host-join "hello" who)) -- "hello, world!", 13
		// characters -- over a string the HOST allocated in this module's memory and the
		// wrapper copied into a [len][bytes] block. The `logged` beside it is the :void
		// import's argument, and the two `true`s are the flatness of the two loops: k
		// host
		// calls inside ONE export call (the arena bracket) and k export calls from
		// outside
		// (the wrapper's own heap reset).
		assertThat(run(MODULE, DRIVER, OptimizeLevel.NONE, "imports").lines().toList()).isEqualTo(EXPECTED);
	}

	@Test
	void noOptimizeLevelChangesWhatTheHostSees() throws Exception {
		for (OptimizeLevel level : List.of(OptimizeLevel.DEFAULT, OptimizeLevel.SIZE)) {
			assertThat(run(MODULE, DRIVER, level, "imports-" + level.spelling()).lines().toList())
				.as("optimize=%s", level.spelling())
				.isEqualTo(EXPECTED);
		}
	}

	@Test
	void theBoundaryCarriesTheValueExactlyOrTraps() throws Exception {
		// The house integer is i64 and the declared types are narrower or unsigned, so
		// both directions have a value the other side cannot state. Outbound: a :s32
		// parameter handed an i64 past 2^31. Inbound: a :u64 result at 2^63, which the
		// SIGNED house integer would silently deliver as a negative Lisp integer.
		String module = """
				(rontolisp:wasm-import 'narrow :from "env" :as "narrow" :params '(:s32) :returns :s32)
				(rontolisp:wasm-import 'unsigned :from "env" :as "unsigned" :params '() :returns :u64)
				(defun pass (n) (narrow n))
				(rontolisp:wasm-export 'pass :params '(:s64) :returns :s64)
				(defun big () (unsigned))
				(rontolisp:wasm-export 'big :params '() :returns :s64)
				""";
		String driver = """
				const fs = require('fs');
				let big = 0n;
				const env = { narrow: (n) => n + 1, unsigned: () => big };
				const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
				const inst = new WebAssembly.Instance(mod, { env });
				const attempt = (f) => { try { return String(f()); } catch (e) { return 'trap'; } };
				console.log(attempt(() => inst.exports.pass(41n)));
				console.log(attempt(() => inst.exports.pass(1n << 40n)));
				big = (1n << 62n);
				console.log(attempt(() => inst.exports.big()));
				big = (1n << 63n);
				console.log(attempt(() => inst.exports.big()));
				""";
		assertThat(run(module, driver, OptimizeLevel.SIZE, "guards").lines().toList()).containsExactly("42", "trap",
				"4611686018427387904", "trap");
	}

	@Test
	void aLiteralStringArgumentReachesTheHostWithoutTheWrapper() throws Exception {
		// The shape a host-facing reactor is written in: a thin Lisp helper per host
		// function, and the literals at the HELPER's call sites. Every :string argument
		// is a literal, so every site crosses as the two constants its [len][bytes]
		// block already is, and neither the helper nor the import wrapper is emitted at
		// all. What the host must still see is each argument's OWN bytes: the pointers
		// are into the module's permanent literal block, where one spelling used twice
		// is ONE block -- so "status" reaching all three calls intact is the pin, and
		// the scalars beside it show the rest of the marshalling moved with them.
		String module = """
				(rontolisp:wasm-import 'js-set-text :from "env" :as "set_text"
				                       :params '(:string :string) :returns :void)
				(rontolisp:wasm-import 'js-mark :from "env" :as "mark"
				                       :params '(:string :s32 :bool :float) :returns :s32)
				(defun set-text (element-id text) (js-set-text element-id text))
				(defun boot (n)
				  (set-text "status" "ready")
				  (set-text "status" "done")
				  (js-mark "status" n t 0.5))
				(rontolisp:wasm-export 'boot :params '(:s32) :returns :s32)
				""";
		String driver = """
				const fs = require('fs');
				const dec = new TextDecoder();
				let inst;
				const seen = [];
				const str = (p, n) => dec.decode(new Uint8Array(inst.exports.memory.buffer, p, n));
				const env = {
				  set_text: (p1, n1, p2, n2) => seen.push(str(p1, n1) + '=' + str(p2, n2)),
				  mark: (p, len, n, flag, weight) => {
				    seen.push(str(p, len) + ':' + n + ':' + flag + ':' + weight);
				    return n + 1;
				  },
				};
				inst = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(process.argv[2])), { env });
				console.log(inst.exports.boot(41));
				console.log(seen.join(' | '));
				""";
		for (OptimizeLevel level : List.of(OptimizeLevel.NONE, OptimizeLevel.DEFAULT, OptimizeLevel.SIZE)) {
			assertThat(run(module, driver, level, "literal-" + level.spelling()).lines().toList())
				.as("optimize=%s", level.spelling())
				.containsExactly("42", "status=ready | status=done | status:41:1:0.5");
		}
	}

	@Test
	void aWitImportedInterfaceIsTheHandWrittenImportBlock() throws Exception {
		// The same lowering both WASM core-module backends take: a wit-import is exactly
		// the wasm-import block it stands for, so binding an interface from its WIT
		// costs nothing and the two programs compile to the SAME bytes.
		Files.writeString(this.tempDir.resolve("api.wit"), """
				package example:app@0.1.0;

				interface api {
				  add: func(a: s32, b: s32) -> s32;
				  greet: func(name: string) -> string;
				}
				""", StandardCharsets.UTF_8);
		String witProgram = """
				(rontolisp:wit-import "api.wit" :interface "example:app/api@0.1.0" :package api :from "env")
				(defun run (n) (length (api:greet (if (> (api:add n n) 10) "big" "small"))))
				(rontolisp:wasm-export 'run :params '(:s32) :returns :s32)
				""";
		String handWritten = """
				(rontolisp:wasm-import 'add :from "env" :as "add" :params '(:int :int) :returns :int)
				(rontolisp:wasm-import 'greet :from "env" :as "greet" :params '(:string) :returns :string)
				(defun run (n) (length (greet (if (> (add n n) 10) "big" "small"))))
				(rontolisp:wasm-export 'run :params '(:s32) :returns :s32)
				""";
		byte[] fromWit = compileNoGc(CompileFrontendAccess.wasmReactor(witProgram, this.tempDir.toString(), true),
				OptimizeLevel.SIZE);
		byte[] byHand = compileNoGc(LispReader.readAllFromString(handWritten), OptimizeLevel.SIZE);
		assertThat(fromWit).isEqualTo(byHand);

		String driver = """
				const fs = require('fs');
				const dec = new TextDecoder(), enc = new TextEncoder();
				let inst;
				const env = {
				  add: (a, b) => a + b,
				  greet: (p, n) => {
				    const b = enc.encode('hello ' + dec.decode(new Uint8Array(inst.exports.memory.buffer, p, n)));
				    const q = inst.exports.__ronto_alloc(b.length);
				    new Uint8Array(inst.exports.memory.buffer).set(b, q);
				    return [q, b.length];
				  },
				};
				inst = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(process.argv[2])), { env });
				console.log(inst.exports.run(3), inst.exports.run(9));
				""";
		Path wasmFile = this.tempDir.resolve("wit.wasm");
		Files.write(wasmFile, fromWit);
		Path driverFile = this.tempDir.resolve("wit.js");
		Files.writeString(driverFile, driver, StandardCharsets.UTF_8);
		assertThat(runNode(driverFile, wasmFile)).isEqualTo("11 9");
	}

	private static byte[] compileNoGc(List<LispVal> program, OptimizeLevel level) {
		return new NoGcWasmCompiler(level, false, false, true).compile(program);
	}

	private String run(String module, String driverJs, OptimizeLevel level, String name) throws Exception {
		byte[] wasm = compileNoGc(LispReader.readAllFromString(module), level);
		Path wasmFile = this.tempDir.resolve(name + ".wasm");
		Files.write(wasmFile, wasm);
		Path driver = this.tempDir.resolve(name + ".js");
		Files.writeString(driver, driverJs, StandardCharsets.UTF_8);
		return runNode(driver, wasmFile);
	}

	private static String runNode(Path driver, Path wasmFile) throws Exception {
		Process process = new ProcessBuilder("node", driver.toString(), wasmFile.toString()).redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exited %d:%n%s", exit, output).isZero();
		return output.trim();
	}

}
