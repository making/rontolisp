package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Language-independent duplicate-body folder for a core WebAssembly module: when two or
 * more defined functions declare the same type index and carry byte-for-byte identical
 * code entries, all but the first are dropped and every reference to a dropped one is
 * redirected to the survivor. Runs as the tail of {@link WasmTreeShaker#shakeWithRemap},
 * so every artifact the tree shaker touches gets it and the composed renumbering still
 * describes the emitted module.
 * <p>
 * Folding shares CODE, never identity. A dropped function's index disappears, but nothing
 * in the module shapes this pass accepts can observe a function's identity through its
 * code index: the only function references are {@code call} / {@code ref.func} immediates
 * (redirecting a call to an identical body is behavior-preserving by construction, and
 * nothing can compare {@code funcref} values), and a first-class function value is a
 * struct whose dispatch id is plain {@code i32} DATA -- two definitions that fold keep
 * distinct ids and distinct structs, their dispatch arms just {@code call} the same body.
 * Exports and the start section are redirected the same way; two exports aliasing one
 * function index are valid, and a component wrapper reaches core functions by export name
 * only.
 * <p>
 * The pass iterates to a fixpoint: redirecting calls can make two caller bodies that
 * differed only in their (now folded) targets identical in turn. Each pass strictly
 * reduces the defined-function count, so termination is structural. Two declared types
 * fold only when they are CANONICALLY the same under wasm-GC type canonicalization -- the
 * same index, or the same position in byte-identical {@code rec}-group entries neither of
 * which references its own members (byte-identical entries reference identical EXTERNAL
 * indices, so their closures are equal; an internal reference resolves relative to its
 * own group, where byte equality proves nothing) -- which keeps every redirected
 * {@code call} typed exactly as before. The distinction matters on a backend that
 * declares one type entry per function ({@code --no-gc}), where duplicate signatures are
 * duplicate entries.
 */
final class WasmBodyFolder {

	private WasmBodyFolder() {
	}

	// Section ids (subset this pass rewrites; everything else is copied through).
	private static final int SEC_CUSTOM = 0;

	private static final int SEC_TYPE = 1;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_TABLE = 4;

	private static final int SEC_GLOBAL = 6;

	private static final int SEC_EXPORT = 7;

	private static final int SEC_START = 8;

	private static final int SEC_ELEMENT = 9;

	private static final int SEC_CODE = 10;

	/**
	 * Folds duplicate bodies in a shaken module, composing the fold's renumbering into
	 * the shake's remap so the result still maps the INPUT module's function indices to
	 * the emitted ones (a folded function maps to its survivor's index, not to -1).
	 * @param shaken a {@link WasmTreeShaker#shakeWithRemap} result
	 * @return the result with duplicate bodies folded; the input unchanged when the
	 * module has none
	 */
	static WasmTreeShaker.ShakeResult fold(WasmTreeShaker.ShakeResult shaken) {
		byte[] module = shaken.module();
		int @Nullable [] folded = null; // shaken-module index -> folded-module index
		while (true) {
			@Nullable Pass pass = foldOnce(module);
			if (pass == null) {
				break;
			}
			module = pass.module();
			folded = folded == null ? pass.remap() : compose(folded, pass.remap());
		}
		if (folded == null) {
			return shaken;
		}
		int[] remap;
		if (shaken.funcRemap() == null) {
			remap = folded;
		}
		else {
			remap = new int[shaken.funcRemap().length];
			for (int i = 0; i < remap.length; i++) {
				int base = shaken.funcRemap()[i];
				remap[i] = base < 0 ? -1 : folded[base];
			}
		}
		return new WasmTreeShaker.ShakeResult(module, shaken.importedFunctionCount(), remap);
	}

	private static int[] compose(int[] first, int[] second) {
		int[] composed = new int[first.length];
		for (int i = 0; i < first.length; i++) {
			composed[i] = second[first[i]];
		}
		return composed;
	}

	/** One fold pass: the rewritten module and its old-to-new function index mapping. */
	private record Pass(byte[] module, int[] remap) {
	}

	// Performs one fold pass, or returns null when no two defined functions share a
	// canonically-equal declared type and identical code bytes.
	private static @Nullable Pass foldOnce(byte[] module) {
		List<WasmTreeShaker.Section> sections = WasmTreeShaker.parseSections(module);
		WasmTreeShaker.@Nullable Section typeSec = null;
		WasmTreeShaker.@Nullable Section functionSec = null;
		WasmTreeShaker.@Nullable Section codeSec = null;
		for (WasmTreeShaker.Section s : sections) {
			// A table or element section holds function references this pass does not
			// rewrite (same guard, same reason as the tree shaker's).
			if (s.id() == SEC_TABLE || s.id() == SEC_ELEMENT) {
				throw new IllegalStateException("WasmBodyFolder: unhandled section id " + s.id());
			}
			if (s.id() == SEC_TYPE) {
				typeSec = s;
			}
			else if (s.id() == SEC_FUNCTION) {
				functionSec = s;
			}
			else if (s.id() == SEC_CODE) {
				codeSec = s;
			}
		}
		if (typeSec == null || functionSec == null || codeSec == null) {
			return null;
		}
		int numImports = WasmTreeShaker.importedFunctionCount(module);
		int[] defTypeIdx = WasmTreeShaker.parseFunctionSection(functionSec.payload());
		List<byte[]> codeEntries = WasmTreeShaker.parseCodeEntries(codeSec.payload());
		String[] typeKeys = typeEquivalenceKeys(typeSec.payload());
		int numDefined = codeEntries.size();
		// survivor[i] = the first defined function with i's declared type and body.
		Map<BodyKey, Integer> firstOf = new HashMap<>();
		int[] survivor = new int[numDefined];
		boolean any = false;
		for (int i = 0; i < numDefined; i++) {
			@Nullable Integer first = firstOf.putIfAbsent(new BodyKey(typeKeys[defTypeIdx[i]], codeEntries.get(i)), i);
			survivor[i] = first == null ? i : first;
			any |= first != null;
		}
		if (!any) {
			return null;
		}
		int totalFuncs = numImports + numDefined;
		int[] remap = new int[totalFuncs];
		for (int i = 0; i < numImports; i++) {
			remap[i] = i;
		}
		int next = numImports;
		for (int i = 0; i < numDefined; i++) {
			if (survivor[i] == i) {
				remap[numImports + i] = next++;
			}
		}
		for (int i = 0; i < numDefined; i++) {
			if (survivor[i] != i) {
				remap[numImports + i] = remap[numImports + survivor[i]];
			}
		}
		List<WasmTreeShaker.Section> rebuilt = new ArrayList<>(sections.size());
		for (WasmTreeShaker.Section s : sections) {
			switch (s.id()) {
				case SEC_FUNCTION ->
					rebuilt.add(new WasmTreeShaker.Section(SEC_FUNCTION, rebuildFunctionSection(defTypeIdx, survivor)));
				case SEC_CODE ->
					rebuilt.add(new WasmTreeShaker.Section(SEC_CODE, rebuildCodeSection(codeEntries, survivor, remap)));
				case SEC_EXPORT -> rebuilt.add(new WasmTreeShaker.Section(SEC_EXPORT,
						WasmTreeShaker.rebuildExportSection(s.payload(), remap)));
				case SEC_START -> rebuilt
					.add(new WasmTreeShaker.Section(SEC_START, WasmTreeShaker.rebuildStartSection(s.payload(), remap)));
				case SEC_GLOBAL -> rebuilt.add(new WasmTreeShaker.Section(SEC_GLOBAL,
						redirectFuncRefs(s.payload(), WasmTreeShaker.scanGlobalSection(s.payload()), remap)));
				case SEC_CUSTOM -> {
					// A `name` section maps function indices to names, and this pass has
					// just renumbered them (same rationale as the tree shaker's drop).
					if (!"name".equals(WasmTreeShaker.customSectionName(s.payload()))) {
						rebuilt.add(s);
					}
				}
				default -> rebuilt.add(s);
			}
		}
		return new Pass(WasmTreeShaker.assemble(rebuilt), remap);
	}

	private static byte[] rebuildFunctionSection(int[] defTypeIdx, int[] survivor) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		int count = 0;
		for (int i = 0; i < defTypeIdx.length; i++) {
			if (survivor[i] == i) {
				count++;
			}
		}
		WasmTreeShaker.writeU(body, count);
		for (int i = 0; i < defTypeIdx.length; i++) {
			if (survivor[i] == i) {
				WasmTreeShaker.writeU(body, defTypeIdx[i]);
			}
		}
		return body.toByteArray();
	}

	private static byte[] rebuildCodeSection(List<byte[]> codeEntries, int[] survivor, int[] remap) {
		List<byte[]> kept = new ArrayList<>();
		for (int i = 0; i < codeEntries.size(); i++) {
			if (survivor[i] != i) {
				continue;
			}
			byte[] entry = codeEntries.get(i);
			kept.add(redirectFuncRefs(entry, WasmTreeShaker.scanBody(entry), remap));
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmTreeShaker.writeU(body, kept.size());
		for (byte[] entry : kept) {
			WasmTreeShaker.writeU(body, entry.length);
			WasmTreeShaker.writeRaw(body, entry);
		}
		return body.toByteArray();
	}

	// Splices only the FUNCTION references; type immediates keep their bytes verbatim
	// (folding drops no type, so there is nothing to renumber there).
	private static byte[] redirectFuncRefs(byte[] buf, List<WasmTreeShaker.Ref> refs, int[] remap) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int cursor = 0;
		for (WasmTreeShaker.Ref r : refs) {
			if (r.kind() != WasmTreeShaker.RefKind.FUNC) {
				continue;
			}
			WasmTreeShaker.writeRaw(out, WasmTreeShaker.slice(buf, cursor, r.start()));
			WasmTreeShaker.writeU(out, remap[r.index()]);
			cursor = r.end();
		}
		if (cursor == 0) {
			return buf;
		}
		WasmTreeShaker.writeRaw(out, WasmTreeShaker.slice(buf, cursor, buf.length));
		return out.toByteArray();
	}

	/**
	 * One canonical-identity key per type index. Same key means the two indices expand to
	 * the same canonical type, so a {@code call} validated against one is validated
	 * identically against the other: a {@code rec}-group entry that never references its
	 * own members is keyed by its raw bytes and the member's position (byte-identical
	 * entries name identical external indices, so equal bytes close over equal types),
	 * while a self-referential group is keyed by its own first index -- within it, byte
	 * equality cannot prove canonical equality, so only the index itself matches.
	 */
	private static String[] typeEquivalenceKeys(byte[] payload) {
		List<WasmTreeShaker.TypeEntry> entries = WasmTreeShaker.parseTypeSection(payload);
		int totalTypes = 0;
		for (WasmTreeShaker.TypeEntry e : entries) {
			totalTypes += e.typeCount();
		}
		String[] keys = new String[totalTypes];
		for (WasmTreeShaker.TypeEntry e : entries) {
			boolean selfReferential = false;
			for (WasmTreeShaker.Ref r : e.refs()) {
				if (r.index() >= e.firstTypeIndex() && r.index() < e.firstTypeIndex() + e.typeCount()) {
					selfReferential = true;
					break;
				}
			}
			String group = selfReferential ? "@" + e.firstTypeIndex()
					: HexFormat.of().formatHex(payload, e.start(), e.end());
			for (int k = 0; k < e.typeCount(); k++) {
				keys[e.firstTypeIndex() + k] = group + "#" + k;
			}
		}
		return keys;
	}

	// Equality = the declared type's canonical-identity key plus the raw code-entry bytes
	// (locals and instruction stream).
	private record BodyKey(String typeKey, byte[] body) {

		@Override
		public boolean equals(Object other) {
			return other instanceof BodyKey k && k.typeKey.equals(this.typeKey) && Arrays.equals(k.body, this.body);
		}

		@Override
		public int hashCode() {
			return 31 * this.typeKey.hashCode() + Arrays.hashCode(this.body);
		}

	}

}
