package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code :octets} import type against a JS host on node: an {@code (unsigned-byte 8)}
 * vector as a value, its octets raw both ways -- what a WIT {@code list<u8>} lowers to
 * under {@code rontolisp:wit-import :octets t} on Preview 1.
 *
 * <ul>
 * <li>a vector argument reaches the host octet for octet ({@code ff 00 41}, no UTF-8), a
 * string argument as its UTF-8 encoding, and two of them on distinct regions;</li>
 * <li>the host's answer comes back as a fresh vector of exactly its octets;</li>
 * <li>the wrapper pops its staging and the host's answer, so a call loop keeps linear
 * memory flat.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WasmBytesBoundaryE2eTest#nodeIsAvailable")
class WasmOctetsBoundaryE2eTest {

	@TempDir
	Path tempDir;

	private static final String MODULE = """
			(rontolisp:wasm-import 'join :from "env" :as "join" :params '(:octets :octets) :returns :octets)

			(defun octets (&rest codes)
			  (make-array (length codes) :element-type '(unsigned-byte 8) :initial-contents codes))

			(defun round-trip ()
			  (let ((v (join (octets 255 0 65) "hé")))
			    (format nil "~A ~A ~{~A~^ ~}" (typep v '(simple-array (unsigned-byte 8) (*))) (length v)
			            (coerce v 'list))))
			(rontolisp:wasm-export 'round-trip :as "roundTrip" :params '() :returns :string)

			(defun pump (k)
			  (let ((total 0))
			    (dotimes (i k) (setq total (+ total (length (join (octets 1 2 3) (octets 4))))))
			    total))
			(rontolisp:wasm-export 'pump :params '(:int) :returns :int)
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			let inst;
			let quiet = false;
			const env = {
			  join: (p0, l0, p1, l1) => {
			    const a = new Uint8Array(inst.exports.memory.buffer.slice(p0, p0 + l0));
			    const b = new Uint8Array(inst.exports.memory.buffer.slice(p1, p1 + l1));
			    if (!quiet) console.log(Array.from(a).join(' ') + ' | ' + Array.from(b).join(' '));
			    const ptr = inst.exports.__ronto_alloc(a.length + b.length);
			    const out = new Uint8Array(inst.exports.memory.buffer, ptr, a.length + b.length);
			    out.set(a);
			    out.set(b, a.length);
			    return [ptr, a.length + b.length];
			  },
			};
			const mod = new WebAssembly.Module(fs.readFileSync(process.argv[2]));
			inst = new WebAssembly.Instance(mod, { env });
			inst.exports._initialize();
			const [ptr, len] = inst.exports.roundTrip();
			console.log(new TextDecoder().decode(new Uint8Array(inst.exports.memory.buffer, ptr, len)));
			quiet = true;
			inst.exports.pump(1);
			const before = inst.exports.memory.buffer.byteLength;
			console.log(inst.exports.pump(10000));
			console.log(inst.exports.memory.buffer.byteLength === before);
			""";

	@Test
	void octetsCrossExactlyBothWaysAndTheCallLoopKeepsMemoryFlat() throws Exception {
		byte[] wasm = WasmLispCompiler.builder().noWasi(true).build().compile(LispReader.readAllFromString(MODULE));
		Path wasmFile = this.tempDir.resolve("octets.wasm");
		Files.write(wasmFile, wasm);
		Path driver = this.tempDir.resolve("driver.js");
		Files.writeString(driver, DRIVER);
		assertThat(runNode(driver, wasmFile).lines().toList()).containsExactly(
				// the vector's octets raw, the string's UTF-8 encoding
				"255 0 65 | 104 195 169",
				// a fresh (unsigned-byte 8) vector holding exactly the host's octets
				"T 6 255 0 65 104 195 169",
				// 10000 calls x 4 octets, memory flat
				"40000", "true");
	}

	private static String runNode(Path driver, Path wasmFile) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(List.of("node", driver.toString(), wasmFile.toString())).start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("node exit code, stderr: %s", stderr).isZero();
		return stdout.trim();
	}

}
