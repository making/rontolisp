package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.wasm.WasmSections.Ref;
import am.ik.wasm.WasmSections.RefKind;
import am.ik.wasm.WasmSections.Section;
import org.jspecify.annotations.Nullable;

/**
 * Deletes the writes of a global no reachable function reads. A compiler keeps state for
 * a reader the module may turn out not to have -- a register a report would read, a
 * counter a signal would read, a variable a program defines and never asks for -- and the
 * reader's absence is only known once the bodies are done.
 *
 * <p>
 * A write becomes a {@code drop} of what it stored, and the update {@code global.get G;
 * i32.const K; i32.add|i32.sub; global.set G}, whose read only feeds that write, goes
 * whole: neither counts as a read. Nothing is renumbered. The peepholes behind this pass
 * collect the {@code drop}s of pure values, and the tree shaker drops a global no
 * surviving body names. An exported or imported global is left alone, since a host reads
 * it. Only bodies the roots reach count and change: a dead body's read keeps nothing, and
 * the shake deletes the body.
 */
public final class WasmWriteOnlyGlobals {

	private static final int SEC_IMPORT = 2;

	private static final int SEC_EXPORT = 7;

	private static final int SEC_CODE = 10;

	private static final int OP_DROP = 0x1A;

	private static final int OP_GLOBAL_GET = 0x23;

	private static final int OP_GLOBAL_SET = 0x24;

	private static final int OP_I32_CONST = 0x41;

	private static final int OP_I32_ADD = 0x6A;

	private static final int OP_I32_SUB = 0x6B;

	private WasmWriteOnlyGlobals() {
	}

	/**
	 * Deletes the writes of every global the module's reachable bodies only write.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the module with those writes gone; the input itself when there are none
	 */
	public static byte[] rewrite(byte[] module) {
		List<Section> sections = WasmSections.parseSections(module);
		@Nullable Section codeSec = WasmSections.find(sections, SEC_CODE);
		if (codeSec == null) {
			return module;
		}
		List<byte[]> bodies = WasmSections.parseCodeEntries(codeSec.payload());
		boolean[] reachable = WasmTreeShaker.reachableBodies(sections, bodies);
		List<List<Ref>> refs = new ArrayList<>(bodies.size());
		Set<Integer> reads = new HashSet<>();
		Set<Integer> writes = new HashSet<>();
		for (int d = 0; d < bodies.size(); d++) {
			List<Ref> bodyRefs = reachable[d] ? globalRefs(bodies.get(d)) : List.of();
			refs.add(bodyRefs);
			collect(bodies.get(d), bodyRefs, reads, writes);
		}
		writes.removeAll(reads);
		for (int exported : WasmTreeShaker.exportGlobalRoots(WasmSections.find(sections, SEC_EXPORT))) {
			writes.remove(exported);
		}
		int imported = importedGlobalCount(WasmSections.find(sections, SEC_IMPORT));
		writes.removeIf(g -> g < imported);
		if (writes.isEmpty()) {
			return module;
		}
		ByteArrayOutputStream code = new UnsynchronizedByteArrayOutputStream();
		WasmSections.writeU(code, bodies.size());
		for (int d = 0; d < bodies.size(); d++) {
			byte[] entry = rewriteBody(bodies.get(d), refs.get(d), writes);
			WasmSections.writeU(code, entry.length);
			WasmSections.writeRaw(code, entry);
		}
		List<Section> rebuilt = new ArrayList<>(sections.size());
		for (Section s : sections) {
			rebuilt.add(s.id() == SEC_CODE ? new Section(SEC_CODE, code.toByteArray()) : s);
		}
		return WasmSections.assemble(rebuilt);
	}

	private static List<Ref> globalRefs(byte[] body) {
		List<Ref> out = new ArrayList<>();
		for (Ref r : WasmSections.scanBody(body)) {
			if (r.kind() == RefKind.GLOBAL) {
				out.add(r);
			}
		}
		return out;
	}

	private static void collect(byte[] body, List<Ref> refs, Set<Integer> reads, Set<Integer> writes) {
		for (int i = 0; i < refs.size(); i++) {
			Ref r = refs.get(i);
			if ((body[r.start() - 1] & 0xff) == OP_GLOBAL_SET) {
				writes.add(r.index());
			}
			else if (updateEnd(body, r) >= 0) {
				// The write it feeds counts as the write it is, one element on.
				writes.add(r.index());
				i++;
			}
			else {
				reads.add(r.index());
			}
		}
	}

	/**
	 * Where the update starting at this {@code global.get} ends, or -1 when the read
	 * feeds something else: {@code i32.const K; i32.add|i32.sub; global.set} of the same
	 * global, the operands read through to the immediate of the set.
	 */
	private static int updateEnd(byte[] body, Ref get) {
		int[] p = { get.end() };
		if (p[0] >= body.length || (body[p[0]++] & 0xff) != OP_I32_CONST) {
			return -1;
		}
		WasmSections.readS(body, p);
		if (p[0] + 1 >= body.length) {
			return -1;
		}
		int op = body[p[0]++] & 0xff;
		if ((op != OP_I32_ADD && op != OP_I32_SUB) || (body[p[0]++] & 0xff) != OP_GLOBAL_SET) {
			return -1;
		}
		return WasmSections.readU(body, p) == get.index() ? p[0] : -1;
	}

	private static byte[] rewriteBody(byte[] body, List<Ref> refs, Set<Integer> writeOnly) {
		ByteArrayOutputStream out = null;
		int copied = 0;
		for (int i = 0; i < refs.size(); i++) {
			Ref r = refs.get(i);
			if (!writeOnly.contains(r.index())) {
				continue;
			}
			if (out == null) {
				out = new UnsynchronizedByteArrayOutputStream(body.length);
			}
			WasmSections.writeRaw(out, WasmSections.slice(body, copied, r.start() - 1));
			if ((body[r.start() - 1] & 0xff) == OP_GLOBAL_SET) {
				out.write(OP_DROP);
				copied = r.end();
			}
			else {
				copied = updateEnd(body, r);
				i++;
			}
		}
		if (out == null) {
			return body;
		}
		WasmSections.writeRaw(out, WasmSections.slice(body, copied, body.length));
		return out.toByteArray();
	}

	private static int importedGlobalCount(@Nullable Section importSec) {
		int count = 0;
		if (importSec != null) {
			for (WasmSections.ImportEntry e : WasmSections.parseImports(importSec.payload())) {
				if (e.kind() == WasmSections.KIND_GLOBAL) {
					count++;
				}
			}
		}
		return count;
	}

}
