package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks of a {@code rontolisp:wasm-import} whose parameter list holds MORE
 * THAN ONE memory-typed parameter ({@code :string}/{@code :s-expr}), against a JS host on
 * plain node. Nothing smaller proves it: {@code wasmtime --invoke} cannot read two
 * pointers apart, and the defect was silent -- the module validated, instantiated and ran
 * while the host saw the LAST argument's bytes under EVERY pointer, with the earlier
 * argument's length.
 *
 * <p>
 * What is pinned:
 * <ul>
 * <li>every memory-typed parameter crosses as its OWN {@code (ptr, len)}, in any
 * combination and interleaved with scalars and {@code :bytes};</li>
 * <li>the regions all stay live ACROSS the host call, so a host that reads its arguments
 * in any order sees them all;</li>
 * <li>the staged run is released afterwards: a loop over the same call keeps linear
 * memory flat (the wrapper pops back to its mark; {@code --reentrant} frees park blocks
 * instead);</li>
 * <li>a runtime-built string (not a literal in the data segment) crosses the same way;
 * </li>
 * <li>the advance between regions counts BYTES: two multi-byte arguments in a row do not
 * overlap, a zero-length argument does not collapse the region after it, and the same
 * VALUE passed twice still crosses as two regions;</li>
 * <li>a MUTABLE CHARACTER VECTOR argument crosses as its own region too -- it is rendered
 * by {@code _charvec_to_str} into the same scratch the staging bumps, so it is the one
 * argument kind that writes there twice;</li>
 * <li>none of it depends on the optimize level.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmStringParamBoundaryE2eTest#nodeIsAvailable")
class WasmStringParamBoundaryE2eTest {

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
			(rontolisp:wasm-import 'two :from "env" :as "two" :params '(:string :string) :returns :int)
			(rontolisp:wasm-import 'mixed :from "env" :as "mixed"
			                       :params '(:int :string :s-expr :float :string) :returns :int)
			(rontolisp:wasm-import 'one :from "env" :as "one" :params '(:string) :returns :int)
			(rontolisp:wasm-import 'with-bytes :from "env" :as "withBytes"
			                       :params '(:string :bytes :s-expr) :returns :int)

			;; two :string parameters, both literals
			(defun go-two () (two "AAAAAAAAAAAA" "BBBB"))
			(rontolisp:wasm-export 'go-two :as "goTwo" :params '() :returns :int)

			;; the same, built at RUNTIME: the bytes come off the GC heap, not the data segment
			(defun go-runtime () (two (subseq "abcdefgh" 0 5) (subseq "0123456789" 3 7)))
			(rontolisp:wasm-export 'go-runtime :as "goRuntime" :params '() :returns :int)

			;; memory-typed parameters interleaved with scalars, and an :s-expr among them
			(defun go-mixed () (mixed 7 "hello" (list 1 2 3) 1.5 "world"))
			(rontolisp:wasm-export 'go-mixed :as "goMixed" :params '() :returns :int)

			;; ONE memory-typed parameter keeps the un-advanced scratch -- unchanged
			(defun go-one () (one "solo"))
			(rontolisp:wasm-export 'go-one :as "goOne" :params '() :returns :int)

			;; a :bytes parameter stages alongside them
			(defvar *b* (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(255 254 65)))
			(defun go-bytes () (with-bytes "left" *b* (list 1 2)))
			(rontolisp:wasm-export 'go-bytes :as "goBytes" :params '() :returns :int)

			;; the staged run must be RELEASED: k calls must not grow linear memory
			(defun pump (k) (dotimes (i k) (two "AAAAAAAAAAAA" "BBBB")) k)
			(rontolisp:wasm-export 'pump :params '(:int) :returns :int)
			""";

	// The same two-:string call through the --reentrant staging (park blocks instead of
	// the bump-and-pop), which is what has to survive a park. The import must be able to
	// suspend for the flag to apply at all; this host answers it synchronously, which is
	// the module's own affair.
	private static final String REENTRANT_MODULE = """
			(rontolisp:wasm-import 'two :from "env" :as "two" :params '(:string :s-expr) :returns :int :async t)
			(defun go-two () (rontolisp::%future-force (two "AAAAAAAAAAAA" (list 1 2 3))))
			(rontolisp:wasm-export 'go-two :as "goTwo" :params '() :returns :int)
			(defun pump (k) (dotimes (i k) (rontolisp::%future-force (two "AAAAAAAAAAAA" (list 1 2 3)))) k)
			(rontolisp:wasm-export 'pump :params '(:int) :returns :int)
			""";

	// Shapes that say something about the ADVANCE between regions rather than about
	// their existence: a byte length that is not a character count, a zero-length
	// region, the same value twice, more of them than any wrapper had before, and the
	// one argument kind that writes into the staging scratch twice.
	private static final String EDGE_MODULE = """
			(rontolisp:wasm-import 'two :from "env" :as "two" :params '(:string :string) :returns :int)
			(rontolisp:wasm-import 'five :from "env" :as "five"
			                       :params '(:string :string :string :string :string) :returns :int)

			;; a length in BYTES, not characters
			(defun go-utf8 () (two "日本語テキスト" "αβγδ"))
			(rontolisp:wasm-export 'go-utf8 :as "goUtf8" :params '() :returns :int)

			;; a zero-length region in front of a live one
			(defun go-empty () (two "" "x"))
			(rontolisp:wasm-export 'go-empty :as "goEmpty" :params '() :returns :int)

			;; the same VALUE twice -- sharing the bytes would give one region two lengths
			(defun go-same () (two "same" "same"))
			(rontolisp:wasm-export 'go-same :as "goSame" :params '() :returns :int)

			;; five of them
			(defun go-five () (five "a" "bb" "ccc" "dddd" "eeeee"))
			(rontolisp:wasm-export 'go-five :as "goFive" :params '() :returns :int)

			;; a mutable character vector: _str_to_mem renders it through _charvec_to_str
			;; into the scratch the staging bumps, so this argument writes there twice
			(defun as-charvec (s)
			  (let ((v (make-array 0 :element-type 'character :fill-pointer 0 :adjustable t)))
			    (dotimes (i (length s)) (vector-push-extend (char s i) v))
			    v))
			(defun go-charvec () (two (as-charvec "CHARVECTOR") "plainliteral"))
			(rontolisp:wasm-export 'go-charvec :as "goCharvec" :params '() :returns :int)
			(defun go-two-charvecs () (two (as-charvec "firstCV") (as-charvec "secondCVCV")))
			(rontolisp:wasm-export 'go-two-charvecs :as "goTwoCharvecs" :params '() :returns :int)
			""";

	private static final String HOST = """
			const fs = require('fs');
			const dec = new TextDecoder();
			let inst;
			let seen = null;
			const str = (p, n) => dec.decode(new Uint8Array(inst.exports.memory.buffer, p, n));
			const raw = (p, n) => Array.from(new Uint8Array(inst.exports.memory.buffer, p, n));
			const env = {
			  two: (p1, n1, p2, n2) => { seen = [str(p1, n1), str(p2, n2)]; return 0; },
			  mixed: (i, p1, n1, p2, n2, f, p3, n3) => {
			    // read the LAST argument first: every region must still be live
			    const last = str(p3, n3);
			    seen = [i, str(p1, n1), str(p2, n2), f, last];
			    return 0;
			  },
			  one: (p, n) => { seen = [str(p, n)]; return 0; },
			  withBytes: (p1, n1, bp, bn, p2, n2) => { seen = [str(p1, n1), raw(bp, bn), str(p2, n2)]; return 0; },
			};
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			inst = new WebAssembly.Instance(mod, { env });
			inst.exports._initialize();
			const call = (name) => { seen = null; inst.exports[name](); return JSON.stringify(seen); };
			""";

	private static final String DRIVER = HOST + """
			console.log(call('goTwo'));
			console.log(call('goRuntime'));
			console.log(call('goMixed'));
			console.log(call('goOne'));
			console.log(call('goBytes'));
			inst.exports.pump(1);
			const before = inst.exports.memory.buffer.byteLength;
			inst.exports.pump(10000);
			console.log(inst.exports.memory.buffer.byteLength === before);
			""";

	private static final String EDGE_HOST = """
			const fs = require('fs');
			const dec = new TextDecoder();
			let inst;
			let seen = null;
			const str = (p, n) => dec.decode(new Uint8Array(inst.exports.memory.buffer, p, n));
			// Every host below reads its LAST argument first, then walks back.
			const back = (...a) => {
			  const out = [];
			  for (let i = a.length - 2; i >= 0; i -= 2) out.unshift(str(a[i], a[i + 1]));
			  return out;
			};
			const env = {
			  two: (...a) => { seen = back(...a); return 0; },
			  five: (...a) => { seen = back(...a); return 0; },
			};
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			inst = new WebAssembly.Instance(mod, { env });
			inst.exports._initialize();
			const call = (name) => { seen = null; inst.exports[name](); return JSON.stringify(seen); };
			console.log(call('goUtf8'));
			console.log(call('goEmpty'));
			console.log(call('goSame'));
			console.log(call('goFive'));
			console.log(call('goCharvec'));
			console.log(call('goTwoCharvecs'));
			""";

	private static final String REENTRANT_DRIVER = HOST + """
			console.log(call('goTwo'));
			inst.exports.pump(1);
			const before = inst.exports.memory.buffer.byteLength;
			inst.exports.pump(10000);
			console.log(inst.exports.memory.buffer.byteLength === before);
			""";

	@Test
	void everyMemoryTypedParameterCrossesAsItsOwnRegion() throws Exception {
		String stdout = run(MODULE, DRIVER, false);
		assertThat(stdout.lines().toList()).containsExactly("[\"AAAAAAAAAAAA\",\"BBBB\"]", "[\"abcde\",\"3456\"]",
				"[7,\"hello\",\"(1 2 3)\",1.5,\"world\"]", "[\"solo\"]", "[\"left\",[255,254,65],\"(1 2)\"]", "true");
	}

	@Test
	void theSameHoldsThroughTheReentrantParkStaging() throws Exception {
		String stdout = run(REENTRANT_MODULE, REENTRANT_DRIVER, true);
		assertThat(stdout.lines().toList()).containsExactly("[\"AAAAAAAAAAAA\",\"(1 2 3)\"]", "true");
	}

	@Test
	void theAdvanceBetweenRegionsCountsBytesAndSurvivesEveryArgumentKind() throws Exception {
		String stdout = run(EDGE_MODULE, EDGE_HOST, false, OptimizeLevel.NONE, "edge");
		assertThat(stdout.lines().toList()).containsExactly("[\"日本語テキスト\",\"αβγδ\"]", "[\"\",\"x\"]",
				"[\"same\",\"same\"]", "[\"a\",\"bb\",\"ccc\",\"dddd\",\"eeeee\"]", "[\"CHARVECTOR\",\"plainliteral\"]",
				"[\"firstCV\",\"secondCVCV\"]");
	}

	/**
	 * The staging lives in the import wrapper, which no optimize level rewrites -- but
	 * the levels do change what surrounds it (the tree shaker at
	 * {@link OptimizeLevel#DEFAULT}, the speed-for-size trades at
	 * {@link OptimizeLevel#SIZE}), and the defect this class exists for was invisible in
	 * the module's shape.
	 */
	@Test
	void noOptimizeLevelChangesWhatTheHostSees() throws Exception {
		for (OptimizeLevel level : List.of(OptimizeLevel.DEFAULT, OptimizeLevel.SIZE)) {
			String stdout = run(MODULE, DRIVER, false, level, "strings-" + level.spelling());
			assertThat(stdout.lines().toList()).as("optimize=%s", level.spelling())
				.containsExactly("[\"AAAAAAAAAAAA\",\"BBBB\"]", "[\"abcde\",\"3456\"]",
						"[7,\"hello\",\"(1 2 3)\",1.5,\"world\"]", "[\"solo\"]", "[\"left\",[255,254,65],\"(1 2)\"]",
						"true");
		}
	}

	// .todo/793: a user (defun subseq ...) on a `cl` name the backend intercepts closed
	// the charvec gate (defunNames trusted the definition) even though the call site
	// still compiles to the STANDARD subseq operator -- the definition never runs
	// (ClRedefinitionWarnings) -- which reaches %SUBSEQ-RUNTIME assuming a charvec is
	// possible. The module must both build and answer "bc", the standard operator's
	// result: shadowing `subseq` must not change what crosses the :string boundary.
	@Test
	void aUserDefunOnAClInterceptedNameStillBuildsAndRendersTheStandardResult() throws Exception {
		String module = """
				(rontolisp:wasm-import 'emit :from "env" :as "emit" :params '(:string) :returns nil)
				(defun subseq (s a b) (if (< a b) s s))
				(defun go () (emit (subseq "abcdef" 1 3)))
				(rontolisp:wasm-export 'go :as "Go" :params '() :returns nil)
				""";
		String host = """
				const fs = require('fs');
				const dec = new TextDecoder();
				let inst;
				let seen = null;
				const str = (p, n) => dec.decode(new Uint8Array(inst.exports.memory.buffer, p, n));
				const env = { emit: (p, n) => { seen = str(p, n); } };
				const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
				inst = new WebAssembly.Instance(mod, { env });
				inst.exports._initialize();
				inst.exports.Go();
				console.log(seen);
				""";
		assertThat(run(module, host, false, OptimizeLevel.SIZE, "gate793")).isEqualTo("bc");
	}

	private String run(String module, String driverJs, boolean reentrant) throws Exception {
		return run(module, driverJs, reentrant, OptimizeLevel.NONE, reentrant ? "reentrant" : "strings");
	}

	private String run(String module, String driverJs, boolean reentrant, OptimizeLevel level, String name)
			throws Exception {
		List<LispVal> program = LispReader.readAllFromString(module);
		byte[] wasm = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(level)
			.reentrant(reentrant)
			.build()
			.compile(program);
		Path wasmFile = this.tempDir.resolve(name + ".wasm");
		Files.write(wasmFile, wasm);
		Path driver = this.tempDir.resolve(name + ".js");
		Files.writeString(driver, driverJs, StandardCharsets.UTF_8);
		return runNode(driver, wasmFile);
	}

	private static String runNode(Path driver, Path wasmFile) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("node", driver.toString(), wasmFile.toString()).redirectErrorStream(false)
			.start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exit code, stderr: %s", stderr).isZero();
		return stdout.trim();
	}

}
