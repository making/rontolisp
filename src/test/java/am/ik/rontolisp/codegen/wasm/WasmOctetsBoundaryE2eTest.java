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
 * What a C-shaped host asks of a wasm-GC module, against a JS host on node. The
 * {@code :octets} import type is an {@code (unsigned-byte 8)} vector as a value, its
 * octets raw both ways -- what a WIT {@code list<u8>} lowers to under
 * {@code rontolisp:wit-import :octets t} on Preview 1.
 *
 * <ul>
 * <li>a vector argument reaches the host octet for octet ({@code ff 00 41}, no UTF-8), a
 * string argument as its UTF-8 encoding, and two of them on distinct regions;</li>
 * <li>the host's answer comes back as a fresh vector of exactly its octets;</li>
 * <li>the wrapper pops its staging and the host's answer, so a call loop keeps linear
 * memory flat;</li>
 * <li>a host that answers through out-pointers is read back through the reserved
 * {@code rontolisp:memory} module, which adds no import entry, and whose mark/reset
 * restores the bump heap exactly;</li>
 * <li>{@code :s32} and {@code :u32} carry the value exactly or trap: a result past the
 * i31 range arrives whole, and an argument out of the declared range stops the call;</li>
 * <li>{@code "search"} answers what a byte-wise search answers
 * ({@link #SEARCH_CHECK}).</li>
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

			(rontolisp:wasm-import 'mem-alloc :from "rontolisp:memory" :as "alloc" :params '(:u32) :returns :u32)
			(rontolisp:wasm-import 'mem-mark :from "rontolisp:memory" :as "mark" :returns :u32)
			(rontolisp:wasm-import 'mem-reset :from "rontolisp:memory" :as "reset" :params '(:u32))
			(rontolisp:wasm-import 'load8 :from "rontolisp:memory" :as "load8" :params '(:u32) :returns :u32)
			(rontolisp:wasm-import 'load32 :from "rontolisp:memory" :as "load32" :params '(:u32) :returns :u32)
			(rontolisp:wasm-import 'load64 :from "rontolisp:memory" :as "load64" :params '(:u32) :returns :s64)
			(rontolisp:wasm-import 'store8 :from "rontolisp:memory" :as "store8" :params '(:u32 :u32))
			(rontolisp:wasm-import 'store32 :from "rontolisp:memory" :as "store32" :params '(:u32 :u32))
			(rontolisp:wasm-import 'store64 :from "rontolisp:memory" :as "store64" :params '(:u32 :s64))
			(rontolisp:wasm-import 'mem-string :from "rontolisp:memory" :as "string" :params '(:u32 :u32) :returns :string)
			(rontolisp:wasm-import 'mem-octets :from "rontolisp:memory" :as "octets" :params '(:u32 :u32) :returns :octets)
			(rontolisp:wasm-import 'answer :from "env" :as "answer" :params '(:u32 :u32))

			;; the proxy_get_header_map_value shape: two out-cells the host fills
			(defun out-pointers ()
			  (let* ((mark (mem-mark)) (cells (mem-alloc 8)) (scratch (mem-alloc 16)))
			    (answer cells (+ cells 4))
			    (store8 scratch 200)
			    (store32 (+ scratch 4) 4000000000)
			    (store64 (+ scratch 8) 1099511627776)
			    (let ((seen
			           (format nil "~A ~{~A~^ ~} ~A ~A ~A" (mem-string (load32 cells) (load32 (+ cells 4)))
			                   (coerce (mem-octets (load32 cells) 2) 'list) (funcall #'load8 scratch)
			                   (load32 (+ scratch 4)) (load64 (+ scratch 8)))))
			      (mem-reset mark)
			      (format nil "~A ~A" seen (= mark (mem-mark))))))
			(rontolisp:wasm-export 'out-pointers :as "outPointers" :params '() :returns :string)

			(rontolisp:wasm-import 'signed :from "env" :as "signed" :params '(:s32) :returns :s32)
			(rontolisp:wasm-import 'unsigned :from "env" :as "unsigned" :params '(:u32) :returns :u32)
			(defun signed-round-trip (n) (signed n))
			(rontolisp:wasm-export 'signed-round-trip :as "signedRoundTrip" :params '(:s64) :returns :s64)
			(defun unsigned-round-trip (n) (unsigned n))
			(rontolisp:wasm-export 'unsigned-round-trip :as "unsignedRoundTrip" :params '(:s64) :returns :s64)
			(defun peek (address) (load8 address))
			(rontolisp:wasm-export 'peek :params '(:s64) :returns :s64)

			;; literals in the text positions of a call that takes other things too, and a
			;; string a format builds, reach the host as their bytes
			(rontolisp:wasm-import 'pair :from "env" :as "pair" :params '(:s32 :string :octets :u32) :returns :s32)
			(defun literal-pair (n) (pair 7 "name" "välue" n))
			(rontolisp:wasm-export 'literal-pair :as "literalPair" :params '(:s64) :returns :s32)
			(defun formatted-pair (n) (pair 8 (format nil "n=~A" n) (format nil "~A-~A" n "x") n))
			(rontolisp:wasm-export 'formatted-pair :as "formattedPair" :params '(:s64) :returns :s32)

			(rontolisp:wasm-import 'mem-search :from "rontolisp:memory" :as "search" :params '(:string :u32 :u32) :returns :s32)
			(defun search-in (needle needle-size at size)
			  (mem-search (mem-string needle needle-size) at size))
			(rontolisp:wasm-export 'search-in :as "searchIn" :params '(:u32 :u32 :u32 :u32) :returns :s32)
			(defun reserve (size) (mem-alloc size))
			(rontolisp:wasm-export 'reserve :params '(:u32) :returns :u32)
			""";

	/**
	 * A JS function checking a module's {@code "rontolisp:memory"."search"} against a
	 * byte-wise search: random needles and bytes that a word-wise test could mistake (0,
	 * 0x80, 0xFF, a first byte repeated), at every alignment, with the needle again right
	 * past the end, where the search may not look. It answers how many answers differed.
	 */
	static final String SEARCH_CHECK = """
			const searchCheck = (reserve, searchIn) => {
			  const room = reserve(128);
			  const alphabet = [0x73, 0x73, 0x00, 0x01, 0x72, 0x7f, 0x80, 0xff, 0x22];
			  let state = 12345;
			  const next = (n) => { state = (state * 1103515245 + 12345) & 0x7fffffff; return (state >> 8) % n; };
			  let wrong = 0;
			  for (let round = 0; round < 4000; round++) {
			    const size = next(48);
			    const needleSize = next(5);
			    const needle = room;
			    const hay = room + 8 + next(8);
			    const mem = new Uint8Array(inst.exports.memory.buffer);
			    for (let i = 0; i < needleSize; i++) mem[needle + i] = alphabet[next(4)];
			    for (let i = 0; i < size; i++) mem[hay + i] = alphabet[next(alphabet.length)];
			    for (let i = 0; i < needleSize; i++) mem[hay + size + i] = mem[needle + i];
			    let want = -1;
			    for (let at = 0; want < 0 && at + needleSize <= size; at++) {
			      let k = 0;
			      while (k < needleSize && mem[hay + at + k] === mem[needle + k]) k++;
			      if (k === needleSize) want = at;
			    }
			    if (searchIn(needle, needleSize, hay, size) !== want) wrong++;
			  }
			  return wrong;
			};
			""";

	private static final String DRIVER = """
			const fs = require('fs');
			let inst;
			let quiet = false;
			""" + SEARCH_CHECK + """
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
			  answer: (pointerCell, sizeCell) => {
			    const text = new TextEncoder().encode('héllo');
			    const ptr = inst.exports.__ronto_alloc(text.length);
			    new Uint8Array(inst.exports.memory.buffer).set(text, ptr);
			    const view = new DataView(inst.exports.memory.buffer);
			    view.setUint32(pointerCell, ptr, true);
			    view.setUint32(sizeCell, text.length, true);
			  },
			  pair: (n, p0, l0, p1, l1, u) => {
			    const text = (p, l) => new TextDecoder().decode(new Uint8Array(inst.exports.memory.buffer, p, l));
			    console.log(n, text(p0, l0), text(p1, l1), u >>> 0);
			    return n + 1;
			  },
			  signed: (n) => n * 100000000,
			  // the host reads an i32 as signed, so the unsigned value is its own to recover
			  unsigned: (n) => { console.log('unsigned', n >>> 0); return n; },
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
			console.log(WebAssembly.Module.imports(mod).map((i) => i.module + '.' + i.name).join(' '));
			const text = (out) => new TextDecoder().decode(new Uint8Array(inst.exports.memory.buffer, out[0], out[1]));
			console.log(text(inst.exports.outPointers()));
			quiet = false;
			const attempt = (f) => { try { return String(f()); } catch (e) { return 'trap'; } };
			for (const n of [15n, -15n, 1500000000n]) {
			  console.log(attempt(() => inst.exports.signedRoundTrip(n)));
			}
			for (const n of [3000000000n, 4294967295n, -1n, 4294967296n]) {
			  console.log(attempt(() => inst.exports.unsignedRoundTrip(n)));
			}
			console.log(attempt(() => inst.exports.peek(4294967296n)));
			console.log(attempt(() => inst.exports.literalPair(3000000000n)));
			console.log(attempt(() => inst.exports.literalPair(-1n)));
			console.log(attempt(() => inst.exports.formattedPair(1073741824n)));
			console.log(searchCheck(inst.exports.reserve, inst.exports.searchIn));
			""";

	@Test
	void aCShapedHostSeesExactValuesAndTheCallLoopKeepsMemoryFlat() throws Exception {
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
				"40000", "true",
				// no entry for rontolisp:memory
				"env.join env.answer env.signed env.unsigned env.pair",
				// the out-cells' string and octets, 1 byte, 4000000000 and 2^40 back, and
				// the heap back at its mark
				"héllo 104 195 200 4000000000 1099511627776 T",
				// an :s32 result past the i31 range arrives whole, an argument there
				// traps
				"1500000000", "-1500000000", "trap",
				// the whole unsigned range crosses, and nothing past either end does
				"unsigned 3000000000", "3000000000", "unsigned 4294967295", "4294967295", "trap", "trap",
				// an address past the 32 bits stops the access
				"trap",
				// the literals' own bytes beside a :u32 past the i31 range, and a :u32
				// argument at either end of it
				"7 name välue 3000000000", "8", "trap", "8 n=1073741824 1073741824-x 1073741824", "9",
				// what the search answers is what a byte-wise search answers
				"0");
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
