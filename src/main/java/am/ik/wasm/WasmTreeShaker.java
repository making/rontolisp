package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import am.ik.wasm.WasmSections.ImportEntry;
import am.ik.wasm.WasmSections.IntList;
import am.ik.wasm.WasmSections.Ref;
import am.ik.wasm.WasmSections.RefKind;
import am.ik.wasm.WasmSections.Section;
import am.ik.wasm.WasmSections.TypeEntry;
import org.jspecify.annotations.Nullable;

/**
 * Language-independent dead-code eliminator (tree shaker) for a core WebAssembly module.
 * <p>
 * The rontolisp code generators emit every runtime helper unconditionally and hold
 * function indices fixed (see the index-stability invariant in {@code CLAUDE.md}), so a
 * compiled module embeds the whole runtime even when the program uses almost none of it.
 * This pass removes that bloat: it builds a call graph from the actual {@code call} (and
 * {@code ref.func}) immediates in every function body, computes the set of functions
 * reachable from the module's roots (its exported functions and an optional start
 * function), drops the rest, and renumbers every surviving function reference so the
 * module stays valid. {@code WasmBodyFolder} then folds survivors whose declared type and
 * code bytes are identical down to one body each.
 * <p>
 * The same walk collects every <strong>type</strong> index the survivors still mention
 * (function signatures, GC-op immediates, block types, locals, globals, tags, imports,
 * plus the type-to-type edges inside the type section itself), so unreferenced type
 * definitions are dropped and the rest renumbered as well. A {@code rec} group is kept or
 * dropped atomically: its members occupy consecutive indices and its structural identity
 * under wasm-GC canonicalization is a property of the whole group.
 * <p>
 * The pass is purely additive: it is a separate call over a finished module, so emission
 * itself never runs it and a caller that does not ask keeps the deterministic output byte
 * for byte (rontolisp asks at every {@code --optimize} level but {@code off}). The
 * module's section order is preserved.
 * <p>
 * Correctness rests on two properties of the rontolisp output that this class verifies by
 * construction: the only function references are {@code call} immediates (the backend
 * uses dispatch functions with direct calls rather than {@code call_indirect}/element
 * segments), and the instruction encoding is the finite subset enumerated in
 * {@code WasmSections.scanInstr}. An unrecognized opcode makes the pass throw rather than
 * silently emit a corrupt module.
 */
public final class WasmTreeShaker {

	private WasmTreeShaker() {
	}

	// Section ids.
	private static final int SEC_CUSTOM = 0;

	private static final int SEC_TYPE = 1;

	private static final int SEC_IMPORT = 2;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_TABLE = 4;

	private static final int SEC_GLOBAL = 6;

	private static final int SEC_EXPORT = 7;

	private static final int SEC_START = 8;

	private static final int SEC_ELEMENT = 9;

	private static final int SEC_CODE = 10;

	private static final int SEC_DATA = 11;

	private static final int SEC_TAG = 13;

	/**
	 * A data segment whose bytes are referenced EXCLUSIVELY by the given functions, so it
	 * may be dropped whenever every one of those functions is unreachable. The claim of
	 * exclusivity is the caller's: this pass cannot see linear-memory references (they
	 * are indistinguishable {@code i32.const} immediates), so it drops the segment purely
	 * on the owners' reachability. Dropping leaves an uninitialized (all-zero) hole in
	 * linear memory at the segment's offset -- sound precisely because nothing reachable
	 * reads it. Segment indices are the positions in the data section; the backend emits
	 * no bulk-memory instructions ({@code memory.init}/{@code data.drop}), so removing a
	 * segment never breaks a {@code dataidx} reference.
	 *
	 * @param segmentIndex index of the segment within the data section
	 * @param ownerFuncIndices global function indices (pre-shake) that own the segment
	 */
	public record OwnedDataSegment(int segmentIndex, int[] ownerFuncIndices) {
	}

	/**
	 * A byte range WITHIN one active data segment that may be dropped when no surviving
	 * function body still addresses it -- the sub-segment counterpart of
	 * {@link OwnedDataSegment}, for a segment holding many independently-referenced
	 * pieces (the string blob). Where {@code OwnedDataSegment} takes the caller's word
	 * for who owns the bytes, this form is decided by OBSERVATION: the pass keeps the
	 * range when any surviving body holds an {@code i32.const} addressing into it -- its
	 * span half-open, so a start or interior pointer keeps it but a bare one-past-the-end
	 * address does not (no emitter produces one, and treating it as a citation pinned
	 * every dead range whose end abutted a live neighbour's start). A linear-memory
	 * reference is an indistinguishable {@code i32.const}, which makes the test
	 * conservative in the safe direction: an unrelated constant that happens to land
	 * inside a range only KEEPS bytes.
	 * <p>
	 * The observed interval may differ from the cut one: a range built with the
	 * five-argument form is cut when {@code [probeStart, probeEnd)} is uncited, which is
	 * how a caller ties a RECORD to the bytes it describes (the runtime intern table's
	 * {@code (offset, length)} row falls with the string it points at). That form is a
	 * caller CLAIM in the {@code OwnedDataSegment} sense: the only reader of the cut
	 * bytes must tolerate them reading as zeros once the probed interval is dead --
	 * citations of the cut interval itself do not keep it.
	 * <p>
	 * What the caller still owes: a range must not be cited from anywhere the scan cannot
	 * see -- notably a word inside another DATA blob. Dropping cuts the range out and
	 * re-emits the segment as one active segment per surviving run, each at the absolute
	 * address it already had, so no surviving reference moves.
	 *
	 * @param segmentIndex index of the segment within the data section
	 * @param start offset of the range within that segment's bytes
	 * @param end end offset (exclusive) of the range within that segment's bytes
	 * @param probeStart offset within the segment's bytes whose citations decide the fate
	 * @param probeEnd end offset (exclusive) of the decided interval
	 */
	public record DroppableDataRange(int segmentIndex, int start, int end, int probeStart, int probeEnd) {

		/**
		 * The self-probed form: the range is cut exactly when its own bytes are uncited.
		 * @param segmentIndex index of the segment within the data section
		 * @param start offset of the range within that segment's bytes
		 * @param end end offset (exclusive) of the range within that segment's bytes
		 */
		public DroppableDataRange(int segmentIndex, int start, int end) {
			this(segmentIndex, start, end, start, end);
		}
	}

	/**
	 * Removes functions unreachable from the module's roots and renumbers the survivors.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return an equivalent module with dead functions removed; the input is returned
	 * unchanged when nothing is dropped
	 */
	public static byte[] shake(byte[] module) {
		return shake(module, List.of());
	}

	/**
	 * Removes functions unreachable from the module's roots and renumbers the survivors,
	 * then drops every {@link OwnedDataSegment} whose owners all died with them.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @param ownedDataSegments data segments to drop when their owning functions are all
	 * unreachable
	 * @return an equivalent module with dead functions and orphaned data segments
	 * removed; the input is returned unchanged when nothing is dropped
	 */
	public static byte[] shake(byte[] module, List<OwnedDataSegment> ownedDataSegments) {
		return shake(module, ownedDataSegments, List.of());
	}

	/**
	 * Removes functions unreachable from the module's roots and renumbers the survivors,
	 * drops every {@link OwnedDataSegment} whose owners all died with them and every
	 * {@link DroppableDataRange} no survivor still addresses, and drops every type
	 * definition the survivors no longer name.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @param ownedDataSegments data segments to drop when their owning functions are all
	 * unreachable
	 * @param droppableDataRanges byte ranges within a data segment to cut out when no
	 * surviving body holds an {@code i32.const} addressing into them
	 * @return an equivalent module with dead functions, orphaned data and unreferenced
	 * types removed; the input is returned unchanged when nothing is dropped
	 */
	public static byte[] shake(byte[] module, List<OwnedDataSegment> ownedDataSegments,
			List<DroppableDataRange> droppableDataRanges) {
		return shakeWithRemap(module, ownedDataSegments, droppableDataRanges).module();
	}

	/**
	 * The result of a {@link #shakeWithRemap} run, carrying the renumbering alongside the
	 * shaken bytes so a caller can correlate the output's function indices with the
	 * input's (a debug dump naming each surviving function is the consumer).
	 *
	 * @param module the shaken module (the input unchanged when nothing was dropped)
	 * @param importedFunctionCount the INPUT module's imported-function count (imports
	 * precede defined functions in the index space)
	 * @param funcRemap old global function index to new global function index, {@code -1}
	 * for a dropped function; a function whose body {@code WasmBodyFolder} folded into an
	 * identical survivor maps to the survivor's index (so several old indices may share
	 * one new index); {@code null} when nothing was dropped (identity)
	 */
	public record ShakeResult(byte[] module, int importedFunctionCount, int @Nullable [] funcRemap) {
	}

	/**
	 * {@link #shake(byte[], List, List)}, additionally reporting the function
	 * renumbering.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @param ownedDataSegments data segments to drop when their owning functions are all
	 * unreachable
	 * @param droppableDataRanges byte ranges within a data segment to cut out when no
	 * surviving body holds an {@code i32.const} addressing into them
	 * @return the shaken module plus the old-to-new function index mapping
	 */
	public static ShakeResult shakeWithRemap(byte[] module, List<OwnedDataSegment> ownedDataSegments,
			List<DroppableDataRange> droppableDataRanges) {
		ShakeResult shaken = dropUnreachable(module, ownedDataSegments, droppableDataRanges);
		WasmBodyFolder.@Nullable Fold fold = WasmBodyFolder.fold(shaken.module());
		if (fold == null) {
			return shaken;
		}
		// Compose the fold's renumbering into the shake's remap so the result still maps
		// the INPUT module's function indices to the emitted ones (a folded function maps
		// to its survivor's index, not to -1).
		int[] foldedRemap;
		if (shaken.funcRemap() == null) {
			foldedRemap = fold.remap();
		}
		else {
			foldedRemap = new int[shaken.funcRemap().length];
			for (int i = 0; i < foldedRemap.length; i++) {
				int base = shaken.funcRemap()[i];
				foldedRemap[i] = base < 0 ? -1 : fold.remap()[base];
			}
		}
		ShakeResult folded = new ShakeResult(fold.module(), shaken.importedFunctionCount(), foldedRemap);
		// A fold can orphan a type entry (a backend that declares one entry per
		// function); re-running the reachability half collects it. Every function is
		// still live -- the fold redirects references, it never removes an edge -- so
		// this only drops types, and the segment/range claims (already applied, and
		// speaking in the ORIGINAL module's indices) must not be re-applied.
		ShakeResult cleaned = dropUnreachable(folded.module(), List.of(), List.of());
		if (cleaned.funcRemap() == null) {
			return new ShakeResult(cleaned.module(), folded.importedFunctionCount(), folded.funcRemap());
		}
		int[] foldRemap = Objects.requireNonNull(folded.funcRemap());
		int[] remap = new int[foldRemap.length];
		for (int i = 0; i < remap.length; i++) {
			remap[i] = foldRemap[i] < 0 ? -1 : cleaned.funcRemap()[foldRemap[i]];
		}
		return new ShakeResult(cleaned.module(), folded.importedFunctionCount(), remap);
	}

	// The reachability half of the pass; shakeWithRemap then folds duplicate bodies
	// among the survivors and composes the fold's renumbering into the remap.
	private static ShakeResult dropUnreachable(byte[] module, List<OwnedDataSegment> ownedDataSegments,
			List<DroppableDataRange> droppableDataRanges) {
		List<Section> sections = WasmSections.parseSections(module);

		@Nullable Section typeSec = WasmSections.find(sections, SEC_TYPE);
		@Nullable Section importSec = WasmSections.find(sections, SEC_IMPORT);
		@Nullable Section functionSec = WasmSections.find(sections, SEC_FUNCTION);
		@Nullable Section globalSec = WasmSections.find(sections, SEC_GLOBAL);
		@Nullable Section tagSec = WasmSections.find(sections, SEC_TAG);
		@Nullable Section codeSec = WasmSections.find(sections, SEC_CODE);
		@Nullable Section dataSec = WasmSections.find(sections, SEC_DATA);
		@Nullable Section exportSec = WasmSections.find(sections, SEC_EXPORT);
		@Nullable Section startSec = WasmSections.find(sections, SEC_START);
		// A table or element section would carry reference types and function indices
		// this pass does not renumber. The backend emits neither (first-class calls go
		// through dispatch functions), so their presence means the module is not the
		// shape this pass verifies by construction.
		for (Section s : sections) {
			if (s.id() == SEC_TABLE || s.id() == SEC_ELEMENT) {
				throw new IllegalStateException("WasmTreeShaker: unhandled section id " + s.id());
			}
		}

		// Imports: record each entry's raw span and, for function imports, their order.
		List<ImportEntry> imports = importSec == null ? List.of() : WasmSections.parseImports(importSec.payload());
		int numImportedFuncs = 0;
		for (ImportEntry e : imports) {
			if (e.kind() == WasmSections.KIND_FUNC) {
				numImportedFuncs++;
			}
		}

		// Defined functions: type indices (function section) aligned with bodies (code
		// section).
		int[] defTypeIdx = functionSec == null ? new int[0] : WasmSections.parseFunctionSection(functionSec.payload());
		List<byte[]> codeEntries = codeSec == null ? new ArrayList<>()
				: WasmSections.parseCodeEntries(codeSec.payload());
		int numDefined = codeEntries.size();
		int totalFuncs = numImportedFuncs + numDefined;

		// Call graph, type references and -- only when a droppable range makes them
		// matter -- the i32.const immediates: one forward walk per defined function.
		List<List<Ref>> bodyRefs = new ArrayList<>(numDefined);
		List<@Nullable IntList> bodyConstants = new ArrayList<>(numDefined);
		boolean needConstants = !droppableDataRanges.isEmpty();
		for (byte[] entry : codeEntries) {
			@Nullable IntList constants = needConstants ? new IntList() : null;
			bodyRefs.add(WasmSections.scanBody(entry, constants));
			bodyConstants.add(constants);
		}

		// Roots: exported functions plus an optional start function.
		boolean[] reachable = new boolean[totalFuncs];
		Deque<Integer> work = new ArrayDeque<>();
		for (int root : exportFuncRoots(exportSec)) {
			if (root >= 0 && root < totalFuncs && !reachable[root]) {
				reachable[root] = true;
				work.push(root);
			}
		}
		if (startSec != null) {
			int[] p = { 0 };
			int root = WasmSections.readU(startSec.payload(), p);
			if (root >= 0 && root < totalFuncs && !reachable[root]) {
				reachable[root] = true;
				work.push(root);
			}
		}
		while (!work.isEmpty()) {
			int fn = work.pop();
			int defIndex = fn - numImportedFuncs;
			if (defIndex < 0) {
				continue; // imported function: no body, no out-edges
			}
			for (Ref r : bodyRefs.get(defIndex)) {
				if (r.kind() == RefKind.FUNC && r.index() >= 0 && r.index() < totalFuncs && !reachable[r.index()]) {
					reachable[r.index()] = true;
					work.push(r.index());
				}
			}
		}

		// Old global function index -> new global function index (kept functions only),
		// preserving order (kept imports first, then kept defined functions).
		int[] funcRemap = new int[totalFuncs];
		int next = 0;
		for (int i = 0; i < totalFuncs; i++) {
			funcRemap[i] = reachable[i] ? next++ : -1;
		}

		// Types still named by the survivors, closed over the type section's own edges.
		List<TypeEntry> typeEntries = typeSec == null ? List.of() : WasmSections.parseTypeSection(typeSec.payload());
		int totalTypes = 0;
		for (TypeEntry e : typeEntries) {
			totalTypes += e.typeCount();
		}
		List<Ref> globalRefs = globalSec == null ? List.of() : WasmSections.scanGlobalSection(globalSec.payload());
		List<Ref> tagRefs = tagSec == null ? List.of() : WasmSections.scanTagSection(tagSec.payload());
		boolean[] typeUsed = markUsedTypes(typeEntries, totalTypes, imports, reachable, numImportedFuncs, defTypeIdx,
				bodyRefs, globalRefs, tagRefs);
		int[] typeRemap = new int[totalTypes];
		int nextType = 0;
		for (TypeEntry e : typeEntries) {
			for (int k = 0; k < e.typeCount(); k++) {
				typeRemap[e.firstTypeIndex() + k] = typeUsed[e.firstTypeIndex()] ? nextType++ : -1;
			}
		}

		// Data segments whose owners all died go with them.
		List<Integer> deadSegments = new ArrayList<>();
		for (OwnedDataSegment owned : ownedDataSegments) {
			if (!anyOwnerAlive(owned.ownerFuncIndices(), reachable, totalFuncs)) {
				deadSegments.add(owned.segmentIndex());
			}
		}
		// ... and so do the ranges inside a surviving segment that no survivor addresses.
		List<DataSegment> dataSegments = dataSec == null ? List.of() : parseDataSection(dataSec.payload());
		List<DroppableDataRange> deadRanges = deadRanges(droppableDataRanges, dataSegments, deadSegments, reachable,
				numImportedFuncs, bodyConstants, globalConstants(globalSec));

		if (next == totalFuncs && nextType == totalTypes && deadSegments.isEmpty() && deadRanges.isEmpty()) {
			return new ShakeResult(module, numImportedFuncs, null); // nothing to drop
		}

		// Rebuild the affected sections in place, preserving section order. A rebuilt
		// section the pass emptied is DROPPED rather than written back as a zero-entry
		// vector: an absent section and an empty one mean the same thing, and a module
		// this pass shook down to nothing would otherwise still carry three bytes of
		// `type`, `function` and `code` saying so.
		List<Section> rebuilt = new ArrayList<>(sections.size());
		for (Section s : sections) {
			switch (s.id()) {
				case SEC_TYPE -> addVector(rebuilt, SEC_TYPE,
						rebuildTypeSection(s.payload(), typeEntries, typeUsed, funcRemap, typeRemap));
				case SEC_IMPORT ->
					addVector(rebuilt, SEC_IMPORT, rebuildImports(imports, reachable, funcRemap, typeRemap));
				case SEC_FUNCTION -> addVector(rebuilt, SEC_FUNCTION,
						rebuildFunctionSection(defTypeIdx, numImportedFuncs, reachable, typeRemap));
				case SEC_GLOBAL ->
					addVector(rebuilt, SEC_GLOBAL, applyRefs(s.payload(), globalRefs, funcRemap, typeRemap));
				case SEC_TAG -> addVector(rebuilt, SEC_TAG, applyRefs(s.payload(), tagRefs, funcRemap, typeRemap));
				case SEC_CODE -> addVector(rebuilt, SEC_CODE,
						rebuildCodeSection(codeEntries, bodyRefs, numImportedFuncs, reachable, funcRemap, typeRemap));
				case SEC_EXPORT ->
					addVector(rebuilt, SEC_EXPORT, WasmSections.rebuildExportSection(s.payload(), funcRemap));
				case SEC_START ->
					rebuilt.add(new Section(SEC_START, WasmSections.rebuildStartSection(s.payload(), funcRemap)));
				case SEC_DATA -> {
					if (deadSegments.isEmpty() && deadRanges.isEmpty()) {
						rebuilt.add(s);
					}
					else {
						addVector(rebuilt, SEC_DATA, rebuildDataSection(dataSegments, deadSegments, deadRanges));
					}
				}
				case SEC_CUSTOM -> {
					// The `name` section maps FUNCTION AND TYPE INDICES to names, and
					// this
					// pass has just renumbered both -- keeping it would describe the
					// module's old shape, which is worse than describing nothing. A
					// hand-written helper module (a WASI adapter assembled from .wat) is
					// where this actually bites: its name section is most of its bytes.
					// Every other custom section is index-free and is copied through.
					if (!"name".equals(WasmSections.customSectionName(s.payload()))) {
						rebuilt.add(s);
					}
				}
				default -> rebuilt.add(s);
			}
		}
		return new ShakeResult(WasmSections.assemble(rebuilt), numImportedFuncs, funcRemap);
	}

	// Keeps a rebuilt vector-shaped section unless it came out empty, in which case the
	// section is dropped: `00` (zero entries) says exactly what no section at all says.
	private static void addVector(List<Section> sections, int id, byte[] payload) {
		if (payload.length == 1 && payload[0] == 0) {
			return;
		}
		sections.add(new Section(id, payload));
	}

	private static boolean anyOwnerAlive(int[] owners, boolean[] reachable, int totalFuncs) {
		for (int owner : owners) {
			if (owner >= 0 && owner < totalFuncs && reachable[owner]) {
				return true;
			}
		}
		return false;
	}

	// Splices a buffer, replacing each recorded immediate with its remapped value. The
	// refs must be ascending and non-overlapping (they come from one forward walk), and
	// the replacement's LEB length may differ from the original's -- exactly as the
	// function renumbering already relies on.
	private static byte[] applyRefs(byte[] buf, List<Ref> refs, int[] funcRemap, int[] typeRemap) {
		if (refs.isEmpty()) {
			return buf;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int cursor = 0;
		for (Ref r : refs) {
			WasmSections.writeRaw(out, WasmSections.slice(buf, cursor, r.start()));
			int[] remap = r.kind() == RefKind.FUNC ? funcRemap : typeRemap;
			// A kept site naming a dropped definition means the reachability walk missed
			// an edge; -1 would encode as a five-byte index and validate as nothing.
			if (remap[r.index()] < 0) {
				throw new IllegalStateException(
						"WasmTreeShaker: surviving " + r.kind() + " reference to dropped index " + r.index());
			}
			switch (r.kind()) {
				case FUNC, TYPE_U -> WasmSections.writeU(out, remap[r.index()]);
				case TYPE_S -> WasmSections.writeS(out, remap[r.index()]);
			}
			cursor = r.end();
		}
		WasmSections.writeRaw(out, WasmSections.slice(buf, cursor, buf.length));
		return out.toByteArray();
	}

	// --- Type section ---

	// Marks every type index the surviving module still names, closed over the type
	// section's own edges. A rec group is atomic: naming one member keeps them all (their
	// indices are consecutive and their structural identity is a property of the group),
	// and every member's own references are then live too.
	private static boolean[] markUsedTypes(List<TypeEntry> typeEntries, int totalTypes, List<ImportEntry> imports,
			boolean[] reachable, int numImportedFuncs, int[] defTypeIdx, List<List<Ref>> bodyRefs, List<Ref> globalRefs,
			List<Ref> tagRefs) {
		boolean[] used = new boolean[totalTypes];
		Deque<Integer> work = new ArrayDeque<>();
		// entryOf[t] = the index of the rectype entry defining type t.
		int[] entryOf = new int[totalTypes];
		for (int i = 0; i < typeEntries.size(); i++) {
			TypeEntry e = typeEntries.get(i);
			for (int k = 0; k < e.typeCount(); k++) {
				entryOf[e.firstTypeIndex() + k] = i;
			}
		}
		int funcOrdinal = 0;
		for (ImportEntry e : imports) {
			boolean kept = e.kind() != WasmSections.KIND_FUNC || reachable[funcOrdinal];
			if (e.kind() == WasmSections.KIND_FUNC) {
				funcOrdinal++;
			}
			if (kept) {
				seed(e.refs(), used, work, totalTypes);
			}
		}
		for (int i = 0; i < defTypeIdx.length; i++) {
			if (reachable[numImportedFuncs + i]) {
				seedIndex(defTypeIdx[i], used, work, totalTypes);
			}
		}
		for (int i = 0; i < bodyRefs.size(); i++) {
			if (reachable[numImportedFuncs + i]) {
				seed(bodyRefs.get(i), used, work, totalTypes);
			}
		}
		seed(globalRefs, used, work, totalTypes);
		seed(tagRefs, used, work, totalTypes);
		while (!work.isEmpty()) {
			TypeEntry e = typeEntries.get(entryOf[work.pop()]);
			for (int k = 0; k < e.typeCount(); k++) {
				used[e.firstTypeIndex() + k] = true;
			}
			for (Ref r : e.refs()) {
				seedIndex(r.index(), used, work, totalTypes);
			}
		}
		return used;
	}

	private static void seed(List<Ref> refs, boolean[] used, Deque<Integer> work, int totalTypes) {
		for (Ref r : refs) {
			if (r.kind() != RefKind.FUNC) {
				seedIndex(r.index(), used, work, totalTypes);
			}
		}
	}

	private static void seedIndex(int index, boolean[] used, Deque<Integer> work, int totalTypes) {
		if (index >= 0 && index < totalTypes && !used[index]) {
			used[index] = true;
			work.push(index);
		}
	}

	private static byte[] rebuildTypeSection(byte[] payload, List<TypeEntry> entries, boolean[] typeUsed,
			int[] funcRemap, int[] typeRemap) {
		List<byte[]> kept = new ArrayList<>();
		for (TypeEntry e : entries) {
			if (!typeUsed[e.firstTypeIndex()]) {
				continue;
			}
			byte[] raw = WasmSections.slice(payload, e.start(), e.end());
			List<Ref> refs = new ArrayList<>(e.refs().size());
			for (Ref r : e.refs()) {
				refs.add(new Ref(r.start() - e.start(), r.end() - e.start(), r.index(), r.kind()));
			}
			kept.add(applyRefs(raw, refs, funcRemap, typeRemap));
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmSections.writeU(body, kept.size());
		for (byte[] entry : kept) {
			WasmSections.writeRaw(body, entry);
		}
		return body.toByteArray();
	}

	// --- Import section ---

	private static byte[] rebuildImports(List<ImportEntry> imports, boolean[] reachable, int[] funcRemap,
			int[] typeRemap) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<ImportEntry> kept = new ArrayList<>();
		int funcOrdinal = 0;
		for (ImportEntry e : imports) {
			if (e.kind() == WasmSections.KIND_FUNC) {
				if (reachable[funcOrdinal]) {
					kept.add(e);
				}
				funcOrdinal++;
			}
			else {
				kept.add(e);
			}
		}
		WasmSections.writeU(body, kept.size());
		for (ImportEntry e : kept) {
			WasmSections.writeRaw(body, applyRefs(e.raw(), e.refs(), funcRemap, typeRemap));
		}
		return body.toByteArray();
	}

	// --- Function section ---

	private static byte[] rebuildFunctionSection(int[] defTypeIdx, int numImportedFuncs, boolean[] reachable,
			int[] typeRemap) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<Integer> kept = new ArrayList<>();
		for (int i = 0; i < defTypeIdx.length; i++) {
			if (reachable[numImportedFuncs + i]) {
				kept.add(typeRemap[defTypeIdx[i]]);
			}
		}
		WasmSections.writeU(body, kept.size());
		for (int t : kept) {
			WasmSections.writeU(body, t);
		}
		return body.toByteArray();
	}

	// --- Code section ---

	private static byte[] rebuildCodeSection(List<byte[]> codeEntries, List<List<Ref>> bodyRefs, int numImportedFuncs,
			boolean[] reachable, int[] funcRemap, int[] typeRemap) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<byte[]> kept = new ArrayList<>();
		for (int i = 0; i < codeEntries.size(); i++) {
			if (reachable[numImportedFuncs + i]) {
				kept.add(applyRefs(codeEntries.get(i), bodyRefs.get(i), funcRemap, typeRemap));
			}
		}
		WasmSections.writeU(body, kept.size());
		for (byte[] entry : kept) {
			WasmSections.writeU(body, entry.length);
			WasmSections.writeRaw(body, entry);
		}
		return body.toByteArray();
	}

	// --- Data section ---

	/**
	 * One active data segment: its linear-memory address, its bytes, and its raw span.
	 */
	private record DataSegment(int offset, byte[] bytes, byte[] raw) {
	}

	// Only active mode-0 segments (flags 0: memory 0, i32.const offset expression) are
	// understood; anything else throws rather than risk mis-framing a segment.
	private static List<DataSegment> parseDataSection(byte[] payload) {
		int[] p = { 0 };
		int count = WasmSections.readU(payload, p);
		List<DataSegment> segments = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int start = p[0];
			int flags = WasmSections.readU(payload, p);
			if (flags != 0) {
				throw new IllegalStateException("WasmTreeShaker: unhandled data segment flags " + flags);
			}
			int op = payload[p[0]++] & 0xff;
			if (op != 0x41) { // i32.const
				throw new IllegalStateException(
						String.format("WasmTreeShaker: unhandled data offset opcode 0x%02X", op));
			}
			int offset = WasmSections.readS(payload, p);
			int end = payload[p[0]++] & 0xff;
			if (end != 0x0B) {
				throw new IllegalStateException(
						String.format("WasmTreeShaker: unterminated data offset expression 0x%02X", end));
			}
			int len = WasmSections.readU(payload, p);
			byte[] bytes = WasmSections.slice(payload, p[0], p[0] + len);
			p[0] += len;
			segments.add(new DataSegment(offset, bytes, WasmSections.slice(payload, start, p[0])));
		}
		return segments;
	}

	// The droppable ranges no surviving function body (nor a global initializer) still
	// addresses. A range survives when some live i32.const lands in its PROBED interval
	// [address, address + length) -- the HALF-OPEN interval: a start or interior pointer
	// keeps its range, a bare one-past-the-end pointer does not. A body cannot use a
	// range from its end alone (it needs the base to read from), so every real consumer
	// holds a const inside the range as well, and an end pointer that is also the next
	// range's start would otherwise pin a genuinely dead neighbour -- which is what kept
	// the printer prologue's " . " alive behind "\n", and a dead builtin-wrapper literal
	// alive behind the one the program actually prints. The probed interval is the
	// range's own bytes unless the caller tied it to another (see DroppableDataRange).
	private static List<DroppableDataRange> deadRanges(List<DroppableDataRange> candidates, List<DataSegment> segments,
			List<Integer> deadSegments, boolean[] reachable, int numImportedFuncs,
			List<@Nullable IntList> bodyConstants, IntList globalConstants) {
		if (candidates.isEmpty()) {
			return List.of();
		}
		IntList live = new IntList();
		live.addAll(globalConstants);
		for (int i = 0; i < bodyConstants.size(); i++) {
			@Nullable IntList constants = bodyConstants.get(i);
			if (constants != null && reachable[numImportedFuncs + i]) {
				live.addAll(constants);
			}
		}
		int[] sorted = live.toSortedArray();
		List<DroppableDataRange> dead = new ArrayList<>();
		for (DroppableDataRange r : candidates) {
			if (r.end() <= r.start() || r.probeEnd() <= r.probeStart() || deadSegments.contains(r.segmentIndex())) {
				continue;
			}
			int address = segments.get(r.segmentIndex()).offset() + r.probeStart();
			if (!containsInRange(sorted, address, address + (r.probeEnd() - r.probeStart()) - 1)) {
				dead.add(r);
			}
		}
		return dead;
	}

	// Whether the sorted array holds a value in [lo, hi] (both inclusive).
	private static boolean containsInRange(int[] sorted, int lo, int hi) {
		int at = java.util.Arrays.binarySearch(sorted, lo);
		int from = at >= 0 ? at : -at - 1;
		return from < sorted.length && sorted[from] <= hi;
	}

	private static IntList globalConstants(@Nullable Section globalSec) {
		IntList constants = new IntList();
		if (globalSec != null) {
			WasmSections.scanGlobalSection(globalSec.payload(), constants);
		}
		return constants;
	}

	// Rebuilds the data section without the segments listed in deadSegments and without
	// the byte ranges listed in deadRanges (a segment carrying one keeps its surviving
	// runs, each re-emitted as its own active segment at the address it already had).
	private static byte[] rebuildDataSection(List<DataSegment> segments, List<Integer> deadSegments,
			List<DroppableDataRange> deadRanges) {
		List<byte[]> kept = new ArrayList<>();
		for (int i = 0; i < segments.size(); i++) {
			DataSegment segment = segments.get(i);
			if (deadSegments.contains(i)) {
				continue;
			}
			List<DroppableDataRange> cuts = new ArrayList<>();
			for (DroppableDataRange r : deadRanges) {
				if (r.segmentIndex() == i) {
					cuts.add(r);
				}
			}
			if (cuts.isEmpty()) {
				kept.add(segment.raw());
				continue;
			}
			cuts.sort((a, b) -> Integer.compare(a.start(), b.start()));
			int len = segment.bytes().length;
			int cursor = 0;
			for (DroppableDataRange cut : cuts) {
				if (cut.start() > cursor) {
					kept.add(activeSegment(segment.offset() + cursor,
							WasmSections.slice(segment.bytes(), cursor, cut.start())));
				}
				cursor = Math.max(cursor, cut.end());
			}
			if (cursor < len) {
				kept.add(activeSegment(segment.offset() + cursor, WasmSections.slice(segment.bytes(), cursor, len)));
			}
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmSections.writeU(body, kept.size());
		for (byte[] segment : kept) {
			WasmSections.writeRaw(body, segment);
		}
		return body.toByteArray();
	}

	// One active mode-0 data segment: flags 0, an i32.const offset expression, then the
	// bytes.
	private static byte[] activeSegment(int offset, byte[] bytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmSections.writeU(out, 0); // flags: active, memory 0
		out.write(0x41); // i32.const
		WasmSections.writeS(out, offset);
		out.write(0x0B); // end
		WasmSections.writeU(out, bytes.length);
		WasmSections.writeRaw(out, bytes);
		return out.toByteArray();
	}

	// --- Export / start sections ---

	private static int[] exportFuncRoots(@Nullable Section exportSec) {
		if (exportSec == null) {
			return new int[0];
		}
		byte[] payload = exportSec.payload();
		int[] p = { 0 };
		int count = WasmSections.readU(payload, p);
		List<Integer> roots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			WasmSections.skipName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			int index = WasmSections.readU(payload, p);
			if (kind == WasmSections.KIND_FUNC) {
				roots.add(index);
			}
		}
		int[] result = new int[roots.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = roots.get(i);
		}
		return result;
	}

}
