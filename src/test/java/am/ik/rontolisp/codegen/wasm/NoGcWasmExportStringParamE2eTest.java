package am.ik.rontolisp.codegen.wasm;

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
 * End-to-end checks of a {@code rontolisp:wasm-export} whose parameter list holds a
 * {@code :string} on the {@code --no-gc} backend, against a JS host on plain node. The
 * host is what nothing smaller can stand in for: the bytes arrive through the exported
 * memory, reserved with the exported {@code __ronto_alloc}.
 *
 * <p>
 * What is pinned:
 * <ul>
 * <li>the host's block becomes the string in place -- the wrapper only stores the
 * {@code [len]} header at {@code ptr - 4} into the four bytes {@code __ronto_alloc} held
 * back, with no second allocation and no copy;</li>
 * <li>an empty string (via {@code __ronto_alloc(0)}), a UTF-8 string whose header length
 * is in BYTES while {@code length} counts characters, the same buffer passed twice, and a
 * call that allocates again between two calls;</li>
 * <li>a pull loop over the export keeps linear memory flat (the host's mark/reset bracket
 * around each call);</li>
 * <li>stdout matches the interpreter line for line -- the probe prints, so the module
 * keeps WASI and the driver serves {@code fd_write} itself;</li>
 * <li>none of it depends on the optimize level.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.NoGcWasmExportStringParamE2eTest#nodeIsAvailable")
class NoGcWasmExportStringParamE2eTest {

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

	// The probe prints, so it is compiled WITH wasi (noWasi = false) and the driver
	// serves fd_write itself (one iovec per call, exactly what __write_stdout
	// passes). `show` takes one :string, `show2` takes two.
	private static final String MODULE = """
			(defun show (s) (print s) (print (length s)) (length s))
			(rontolisp:wasm-export 'show :params '(:string) :returns :int)
			(defun show2 (a b) (print (concatenate 'string a b)) (length (concatenate 'string a b)))
			(rontolisp:wasm-export 'show2 :params '(:string :string) :returns :int)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			const dec = new TextDecoder(), enc = new TextEncoder();
			let inst;
			const printed = [];
			const mem = () => new DataView(inst.exports.memory.buffer);
			const str = (p, n) => dec.decode(new Uint8Array(inst.exports.memory.buffer, p, n));
			const wasi = {
			  fd_write: (fd, iovs, iovsLen, nwritten) => {
			    let total = 0;
			    for (let i = 0; i < iovsLen; i++) {
			      const p = mem().getUint32(iovs + i * 8, true);
			      const n = mem().getUint32(iovs + i * 8 + 4, true);
			      printed.push(str(p, n));
			      total += n;
			    }
			    mem().setUint32(nwritten, total, true);
			    return 0;
			  },
			};
			inst = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(process.argv[2])),
			  { wasi_snapshot_preview1: wasi });
			const give = (s) => {
			  const b = enc.encode(s);
			  const p = inst.exports.__ronto_alloc(b.length);
			  new Uint8Array(inst.exports.memory.buffer).set(b, p);
			  return [p, b.length];
			};
			const withStrings = (name, ...ss) => {
			  const mark = inst.exports.__ronto_alloc_mark();
			  const args = ss.flatMap((s) => give(s));
			  const out = inst.exports[name](...args);
			  inst.exports.__ronto_alloc_reset(mark);
			  return out;
			};
			const rets = [];
			rets.push(withStrings('show', ''));
			rets.push(withStrings('show', 'hello'));
			rets.push(withStrings('show', '日本語'));
			rets.push(withStrings('show2', 'ab', 'cd'));
			// the same buffer passed twice: the header is stored at ptr - 4 twice over
			// the same value, and the call reads the one string twice
			{
			  const mark = inst.exports.__ronto_alloc_mark();
			  const [p, n] = give('abc');
			  rets.push(inst.exports.show2(p, n, p, n));
			  inst.exports.__ronto_alloc_reset(mark);
			}
			console.log(JSON.stringify(printed.join('').split('\\n').slice(0, -1)));
			console.log(JSON.stringify(rets));
			// a call that allocates again between two calls, then a pull loop: every
			// iteration brackets its own input buffer, so linear memory stays flat
			withStrings('show', 'a');
			withStrings('show', 'bb');
			withStrings('show', 'x');
			const before = inst.exports.memory.buffer.byteLength;
			for (let i = 0; i < 20000; i++) withStrings('show', 'x');
			console.log(inst.exports.memory.buffer.byteLength === before);
			""";

	// The interpreter's answer for the same calls (oracle.lisp: the two defuns above
	// plus top-level calls printing (show "") (show "hello") (show "日本語")
	// (show2 "ab" "cd") (show2 "abc" "abc")), verified by hand with
	// `java -jar rontolisp-*-exec.jar oracle.lisp`. The return values ride beside
	// it: show answers the character length, show2 the concatenation's.
	private static final List<String> EXPECTED = List.of(
			"[\"\\\"\\\"\",\"0\",\"\\\"hello\\\"\",\"5\",\"\\\"日本語\\\"\",\"3\",\"\\\"abcd\\\"\",\"\\\"abcabc\\\"\"]",
			"[0,5,3,4,6]", "true");

	@Test
	void aStringExportParameterRoundTripsThroughTheHostsOwnBlock() throws Exception {
		assertThat(run(MODULE, DRIVER, OptimizeLevel.NONE, "params").lines().toList()).isEqualTo(EXPECTED);
	}

	@Test
	void noOptimizeLevelChangesWhatTheHostSees() throws Exception {
		assertThat(run(MODULE, DRIVER, OptimizeLevel.SIZE, "params-size").lines().toList())
			.as("optimize=%s", OptimizeLevel.SIZE.spelling())
			.isEqualTo(EXPECTED);
	}

	private String run(String module, String driverJs, OptimizeLevel level, String name) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(module);
		byte[] wasm = NoGcWasmCompiler.builder().optimize(level).build().compile(program);
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
