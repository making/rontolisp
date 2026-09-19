package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.FuncType;
import am.ik.wasm.WasmCodeModel.Instr;
import am.ik.wasm.WasmCodeModel.TypeSection;
import am.ik.wasm.WasmCodeModel.ValType;
import am.ik.wasm.WasmSections.Ref;
import am.ik.wasm.WasmSections.RefKind;
import am.ik.wasm.WasmSections.Section;
import org.jspecify.annotations.Nullable;

/**
 * Language-independent inlining of a defined function that the whole module calls from
 * exactly ONE place: the callee's body is moved to that call site and the callee is left
 * unreferenced, so {@link WasmTreeShaker} -- which this pass must run in front of --
 * deletes its code entry, its function-section entry and any type only it named. Nothing
 * is renumbered here, so a claim made in the module's own indices (an
 * {@link WasmTreeShaker.OwnedDataSegment}, a size-dump name map) still reads correctly
 * afterwards.
 * <p>
 * <strong>The move alone is a LOSS.</strong> Handing the arguments over through fresh
 * locals costs a {@code local.set}/{@code local.get} pair the caller never had, plus a
 * second byte on every local index past 127 -- which is how {@code wasm-opt --inlining}
 * makes a large module bigger. The pass is therefore the move PLUS its arithmetic:
 * <ul>
 * <li><strong>Stack hand-over.</strong> A callee that reads each parameter exactly once,
 * in order, as the first instructions of its body -- every forwarder and every thin
 * wrapper -- needs no locals at all: the arguments are already on the stack in that
 * order, so those leading {@code local.get}s are simply dropped.</li>
 * <li><strong>Argument substitution.</strong> When the instructions that push the
 * arguments are re-materializable ({@code local.get} of a local the moved body never
 * writes, or a constant), each parameter read becomes that instruction again and the push
 * is deleted -- no local, no {@code local.set}. Chosen per parameter, by byte count
 * against the local it replaces.</li>
 * <li><strong>A measured decision.</strong> Whatever the modes produce, the rewritten
 * caller is encoded and compared against the caller plus the callee it would replace; an
 * inline that does not come out strictly smaller is abandoned. So the pass cannot grow
 * the code or function section of any module, whatever its local numbering does.</li>
 * </ul>
 * Two things that per-pair arithmetic cannot see, and both made the first version of this
 * pass a net LOSS on {@code zlib}: a body {@link WasmBodyFolder} was going to reclaim
 * anyway (a byte-identical twin exists, so moving it removes nothing and leaves the
 * caller carrying the bytes for good) is declined outright, and the body that IS moved is
 * capped at {@link #MAX_MOVED_BODY} because relocating bytes costs the artifact's
 * compressor the repetition it lives on. Both are measured, in
 * {@code .kb/optimize-dead-code-elimination.md}, "The single-call-site move".
 * <p>
 * Out of scope: a callee that is exported, named by the start section, pinned by the
 * caller, recursive, or its own caller. A callee reached through {@code ref.func} would
 * be too -- {@link WasmCodeModel} refuses to decode one, which takes the whole module
 * out.
 * <p>
 * Chains are handled bottom-up: the candidate set is computed ONCE, from the untouched
 * module, and ordered so that a callee is filled in before it is itself moved. The stale
 * bodies left behind may still name functions the live module no longer calls; that costs
 * nothing, because the shaker counts reachability from the roots rather than call sites.
 */
public final class WasmInliner {

	private WasmInliner() {
	}

	private static final int SEC_TYPE = 1;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_TABLE = 4;

	private static final int SEC_START = 8;

	private static final int SEC_ELEMENT = 9;

	private static final int SEC_CODE = 10;

	private static final int SEC_EXPORT = 7;

	private static final int OP_LOCAL_GET = 0x20;

	private static final int OP_LOCAL_SET = 0x21;

	private static final int OP_LOCAL_TEE = 0x22;

	private static final int OP_BLOCK = 0x02;

	private static final int OP_END = 0x0B;

	private static final int OP_BR = 0x0C;

	private static final int OP_BR_IF = 0x0D;

	private static final int OP_BR_TABLE = 0x0E;

	private static final int OP_RETURN = 0x0F;

	private static final int OP_CALL = 0x10;

	private static final int OP_RETURN_CALL = 0x12;

	private static final int OP_TRY_TABLE = 0x1F;

	/**
	 * The largest code entry this pass will relocate, in bytes. What a move RECLAIMS is
	 * the per-function overhead -- a code-entry size prefix, the locals-vector byte, the
	 * terminating {@code end}, the {@code call} itself and a function-section entry, 6 to
	 * 12 bytes -- plus whatever the argument arithmetic saves; it is never a function of
	 * how big the body is. Relocating a body, on the other hand, costs REPETITION, and
	 * repetition is what the artifact's compressor lives on. Measured over the
	 * size-report corpus, the Cloudflare Worker family and the {@code --no-gc} browser
	 * reactor (23 artifacts, 2026-09-13, the table in
	 * {@code .kb/optimize-dead-code-elimination.md}): at this budget every family
	 * improves on BOTH axes (-911 B raw, -95 B gzipped in total, worst case +0.26%
	 * gzipped); lifting it to 256 buys 4,143 raw bytes for 3,670 gzipped ones, and
	 * lifting it entirely buys 50,722 raw for 16,064 gzipped -- a trade the Worker
	 * family, whose platform limit counts compressed bytes, does not want. The
	 * host-facing {@code --no-gc} modules this pass exists for reach their full win here.
	 */
	private static final int MAX_MOVED_BODY = 64;

	/**
	 * Inlines every single-call-site function the module's shape allows.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the module with those bodies moved to their call sites; the input itself
	 * when nothing paid
	 */
	public static byte[] inline(byte[] module) {
		return inline(module, new int[0]);
	}

	/**
	 * Inlines every single-call-site function the module's shape allows, except the ones
	 * the caller pins.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @param pinnedFuncIndices function indices that must keep their own body -- a
	 * function some out-of-band claim addresses by index, such as the owner of an
	 * {@link WasmTreeShaker.OwnedDataSegment} (moving the body would leave the claim
	 * pointing at a function the shake then kills, dropping data the caller still reads)
	 * @return the module with those bodies moved to their call sites; the input itself
	 * when nothing paid
	 */
	public static byte[] inline(byte[] module, int[] pinnedFuncIndices) {
		List<Section> sections = WasmSections.parseSections(module);
		@Nullable Section typeSec = null;
		@Nullable Section functionSec = null;
		@Nullable Section codeSec = null;
		@Nullable Section exportSec = null;
		@Nullable Section startSec = null;
		for (Section s : sections) {
			if (s.id() == SEC_TABLE || s.id() == SEC_ELEMENT) {
				// A function reachable other than by a direct call: this pass cannot see
				// that call site, so it cannot know a callee has only one.
				return module;
			}
			switch (s.id()) {
				case SEC_TYPE -> typeSec = s;
				case SEC_FUNCTION -> functionSec = s;
				case SEC_CODE -> codeSec = s;
				case SEC_EXPORT -> exportSec = s;
				case SEC_START -> startSec = s;
				default -> {
				}
			}
		}
		if (typeSec == null || functionSec == null || codeSec == null) {
			return module;
		}
		int numImports = WasmSections.importedFunctionCount(module);
		int[] defTypeIdx = WasmSections.parseFunctionSection(functionSec.payload());
		List<byte[]> entries = new ArrayList<>(WasmSections.parseCodeEntries(codeSec.payload()));
		TypeSection types = WasmCodeModel.parseTypeSection(typeSec.payload());
		int total = numImports + entries.size();
		if (defTypeIdx.length != entries.size()) {
			return module;
		}

		// One call-site census over the untouched module: how many `call f` the whole
		// module holds, and -- when that is one -- which defined function holds it.
		int[] callCount = new int[total];
		int[] callerOf = new int[total];
		java.util.Arrays.fill(callerOf, -1);
		for (int d = 0; d < entries.size(); d++) {
			for (Ref r : WasmSections.scanBody(entries.get(d))) {
				if (r.kind() != RefKind.FUNC || r.index() < 0 || r.index() >= total) {
					continue;
				}
				callCount[r.index()]++;
				callerOf[r.index()] = numImports + d;
			}
		}

		boolean[] blocked = new boolean[total];
		for (int root : exportedFunctions(exportSec)) {
			if (root >= 0 && root < total) {
				blocked[root] = true;
			}
		}
		if (startSec != null) {
			int[] p = { 0 };
			int root = WasmSections.readU(startSec.payload(), p);
			if (root >= 0 && root < total) {
				blocked[root] = true;
			}
		}
		for (int pinned : pinnedFuncIndices) {
			if (pinned >= 0 && pinned < total) {
				blocked[pinned] = true;
			}
		}

		// A body the shake's duplicate fold is going to reclaim anyway is worth nothing
		// here and costs the caller its whole size: see the note on WasmBodyFolder above.
		boolean[] twinned = twinnedBodies(entries, defTypeIdx, typeSec.payload(), numImports, total);

		List<Integer> candidates = new ArrayList<>();
		for (int f = numImports; f < total; f++) {
			if (!blocked[f] && !twinned[f] && callCount[f] == 1 && callerOf[f] >= numImports && callerOf[f] != f) {
				candidates.add(f);
			}
		}
		if (candidates.isEmpty()) {
			return module;
		}
		List<Integer> order = bottomUp(candidates, callerOf, total);

		boolean changed = false;
		for (int callee : order) {
			int caller = callerOf[callee];
			byte[] callerEntry = entries.get(caller - numImports);
			byte[] calleeEntry = entries.get(callee - numImports);
			byte @Nullable [] merged;
			try {
				merged = merge(callerEntry, calleeEntry, defTypeIdx[caller - numImports],
						defTypeIdx[callee - numImports], types, callee);
			}
			catch (RuntimeException ex) {
				// An opcode outside the model's subset, in either body: leave the pair
				// alone rather than taking the whole module out of the pass.
				merged = null;
			}
			if (merged == null) {
				continue;
			}
			int was = lebLen(callerEntry.length) + callerEntry.length + lebLen(calleeEntry.length) + calleeEntry.length
					+ lebLen(defTypeIdx[callee - numImports]);
			int now = lebLen(merged.length) + merged.length;
			if (now >= was || calleeEntry.length > MAX_MOVED_BODY) {
				continue;
			}
			entries.set(caller - numImports, merged);
			changed = true;
		}
		if (!changed) {
			return module;
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmSections.writeU(body, entries.size());
		for (byte[] entry : entries) {
			WasmSections.writeU(body, entry.length);
			WasmSections.writeRaw(body, entry);
		}
		List<Section> rebuilt = new ArrayList<>(sections.size());
		for (Section s : sections) {
			rebuilt.add(s.id() == SEC_CODE ? new Section(SEC_CODE, body.toByteArray()) : s);
		}
		return WasmSections.assemble(rebuilt);
	}

	// The functions whose (canonical type, code bytes) pair another defined function
	// repeats. {@link WasmBodyFolder}, which runs inside the shake behind this pass,
	// drops all but one of them and redirects every reference to the survivor -- so
	// moving such a body to its call site reclaims nothing the shake was not already
	// going to reclaim, and leaves the caller carrying the bytes for good. A CALLER
	// never has a twin (a byte-identical body repeats the same call immediate, which
	// would make its callee's call site count two), so only the callee side needs this.
	private static boolean[] twinnedBodies(List<byte[]> entries, int[] defTypeIdx, byte[] typePayload, int numImports,
			int total) {
		String[] typeKeys = WasmBodyFolder.typeEquivalenceKeys(typePayload);
		java.util.Map<String, Integer> firstOf = new java.util.HashMap<>();
		boolean[] twinned = new boolean[total];
		java.util.HexFormat hex = java.util.HexFormat.of();
		for (int d = 0; d < entries.size(); d++) {
			int typeIndex = defTypeIdx[d];
			String key = (typeIndex >= 0 && typeIndex < typeKeys.length ? typeKeys[typeIndex]
					: String.valueOf(typeIndex)) + '#' + hex.formatHex(entries.get(d));
			Integer first = firstOf.putIfAbsent(key, d);
			if (first != null) {
				twinned[numImports + d] = true;
				twinned[numImports + first] = true;
			}
		}
		return twinned;
	}

	// Candidates ordered so that a function is filled in before it is itself moved: an
	// edge runs from a callee to the candidate that holds its call site. Each node has at
	// most one out-edge, so a cycle is a set of mutually recursive functions nothing else
	// calls -- dead by construction, and dropped from the order.
	private static List<Integer> bottomUp(List<Integer> candidates, int[] callerOf, int total) {
		boolean[] isCandidate = new boolean[total];
		for (int c : candidates) {
			isCandidate[c] = true;
		}
		int[] pending = new int[total];
		for (int c : candidates) {
			int up = callerOf[c];
			if (isCandidate[up]) {
				pending[up]++;
			}
		}
		List<Integer> order = new ArrayList<>(candidates.size());
		java.util.Deque<Integer> ready = new java.util.ArrayDeque<>();
		for (int c : candidates) {
			if (pending[c] == 0) {
				ready.add(c);
			}
		}
		while (!ready.isEmpty()) {
			int c = ready.removeFirst();
			order.add(c);
			int up = callerOf[c];
			if (isCandidate[up] && --pending[up] == 0) {
				ready.add(up);
			}
		}
		return order;
	}

	// --- The move itself ---

	// The caller's code entry with the callee's body moved to its one call site, or null
	// when the shape is one this pass declines.
	private static byte @Nullable [] merge(byte[] callerEntry, byte[] calleeEntry, int callerTypeIdx, int calleeTypeIdx,
			TypeSection types, int callee) {
		Body callerBody = WasmCodeModel.decode(callerEntry, types);
		List<Instr> callerCode = callerBody.code();
		int at = -1;
		for (int i = 0; i < callerCode.size(); i++) {
			Instr in = callerCode.get(i);
			if ((in.op == OP_CALL || in.op == OP_RETURN_CALL) && in.a == callee) {
				if (at >= 0) {
					return null; // not the single call site this pass was promised
				}
				at = i;
			}
		}
		if (at < 0) {
			return null;
		}
		// A tail-call site (`return_call callee`) takes the moved body followed by a
		// `return`: what the callee answered is what the caller returns, whatever the
		// caller's code after the site was for -- the emitter's tail sites are followed
		// only by value-passing `end`s and `br`s, but a dispatcher case falls through
		// into the NEXT case's body, which the return keeps out of reach.
		boolean tailSite = callerCode.get(at).op == OP_RETURN_CALL;
		Body calleeBody = WasmCodeModel.decode(calleeEntry, types);
		List<Instr> moved = calleeBody.code();
		FuncType calleeType = types.func(calleeTypeIdx);
		int params = calleeType.params().size();
		Shape shape = shapeOf(moved, params, callee);
		if (shape == null) {
			return null;
		}

		int callerParams = types.func(callerTypeIdx).params().size();
		int base = callerParams + callerBody.locals().size();
		// A parameter the callee never writes may take its argument's instruction back at
		// every read instead of a local -- when that instruction is one this pass can
		// re-materialize, and when doing so is not more bytes than the local would be.
		byte[][] substitute = new byte[params][];
		boolean argsArePushers = at >= params;
		for (int i = 0; argsArePushers && i < params; i++) {
			argsArePushers = isPusher(callerCode.get(at - params + i));
		}
		// The stack hand-over: the arguments are already in order under the call, so the
		// leading reads are simply dropped and no parameter needs anything at all.
		int skip = shape.stackHandOver() ? params : 0;
		if (!shape.stackHandOver() && argsArePushers) {
			int width = lebLen(base + params + calleeBody.locals().size());
			for (int i = 0; i < params; i++) {
				if (shape.writes()[i] > 0) {
					continue;
				}
				Instr arg = callerCode.get(at - params + i);
				int argLen = arg.end - arg.start;
				int asSubstitute = shape.reads()[i] * argLen;
				int asLocal = argLen + (1 + width) + shape.reads()[i] * (1 + width);
				if (asSubstitute <= asLocal) {
					substitute[i] = WasmSections.slice(callerEntry, arg.start, arg.end);
				}
			}
		}
		// Slots: first the parameters still needing one, then the callee's own locals.
		int[] slot = new int[params];
		List<ValType> added = new ArrayList<>();
		for (int i = 0; i < params; i++) {
			if (shape.stackHandOver() || substitute[i] != null) {
				slot[i] = -1;
				continue;
			}
			slot[i] = base + added.size();
			added.add(calleeType.params().get(i));
		}
		int localBase = base + added.size();
		added.addAll(calleeBody.locals());

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeLocals(out, callerBody.locals(), added);
		int instrStart = callerCode.get(0).start;
		boolean rewritesArgs = !shape.stackHandOver() && argsArePushers && params > 0;
		int cut = rewritesArgs ? callerCode.get(at - params).start : callerCode.get(at).start;
		WasmSections.writeRaw(out, WasmSections.slice(callerEntry, instrStart, cut));
		if (rewritesArgs) {
			// The pushes whose parameter kept a local stay, in their original order; the
			// substituted ones go.
			for (int i = 0; i < params; i++) {
				if (substitute[i] == null) {
					Instr arg = callerCode.get(at - params + i);
					WasmSections.writeRaw(out, WasmSections.slice(callerEntry, arg.start, arg.end));
				}
			}
		}
		if (!shape.stackHandOver()) {
			// Top of stack is the last argument, so the hand-over runs backwards.
			for (int i = params - 1; i >= 0; i--) {
				if (slot[i] >= 0) {
					out.write(OP_LOCAL_SET);
					WasmSections.writeU(out, slot[i]);
				}
			}
		}
		if (shape.needsBlock()) {
			// The wrapper's own SIGNATURE is the callee's: under the stack hand-over the
			// arguments are still on the stack and only a block that declares them as
			// parameters may reach them, which is exactly what the callee's type index
			// says. Once they have gone into locals the block takes none, and the
			// one-byte inline form says it -- but only for a result list it can spell.
			if (shape.stackHandOver() && params > 0) {
				out.write(OP_BLOCK);
				WasmSections.writeS(out, calleeTypeIdx);
			}
			else if (calleeType.results().size() > 1) {
				return null;
			}
			else {
				out.write(OP_BLOCK);
				writeBlockType(out, calleeType.results());
			}
		}
		writeMovedBody(out, calleeEntry, moved, skip, params, slot, substitute, localBase, shape.needsBlock());
		if (shape.needsBlock()) {
			out.write(OP_END);
		}
		if (tailSite) {
			out.write(OP_RETURN);
		}
		WasmSections.writeRaw(out, WasmSections.slice(callerEntry, callerCode.get(at).end, callerEntry.length));
		return out.toByteArray();
	}

	// What the callee's body does with its parameters and how it leaves: the two facts
	// the move's cost and correctness hang on.
	private record Shape(int[] reads, int[] writes, boolean stackHandOver, boolean needsBlock) {
	}

	private static @Nullable Shape shapeOf(List<Instr> code, int params, int callee) {
		int[] reads = new int[params];
		int[] writes = new int[params];
		boolean needsBlock = false;
		int depth = 0;
		for (int k = 0; k < code.size(); k++) {
			Instr in = code.get(k);
			if (in.op == OP_END && in.match >= 0) {
				depth--;
			}
			switch (in.op) {
				case OP_LOCAL_GET -> {
					if (in.a < params) {
						reads[(int) in.a]++;
					}
				}
				case OP_LOCAL_SET -> {
					if (in.a < params) {
						writes[(int) in.a]++;
					}
				}
				case OP_LOCAL_TEE -> {
					if (in.a < params) {
						reads[(int) in.a]++;
						writes[(int) in.a]++;
					}
				}
				case OP_RETURN -> needsBlock = true;
				// A tail call leaves the callee's frame as a `return` does; moved into
				// the caller it becomes a plain call and a branch out of the wrapping
				// block (writeMovedBody), which keeps the stack exactly as deep as the
				// tail call kept it: the callee's frame is gone either way.
				case OP_RETURN_CALL -> {
					if (in.a == callee) {
						return null; // recursive: the moved body would call a dead entry
					}
					// A tail call that is the body's LAST instruction (before the final
					// end, at depth 0) falls off the end as a plain call would: no block.
					// Every forwarder is this shape, and the block would cost what the
					// move saves.
					needsBlock |= !isTrailing(code, k, depth);
				}
				case OP_BR, OP_BR_IF -> needsBlock |= in.a >= depth;
				case OP_BR_TABLE -> {
					for (int label : java.util.Objects.requireNonNull(in.labels)) {
						needsBlock |= label >= depth;
					}
				}
				case OP_TRY_TABLE -> {
					for (WasmCodeModel.Catch c : java.util.Objects.requireNonNull(in.catches)) {
						needsBlock |= c.label() >= depth;
					}
				}
				case OP_CALL -> {
					if (in.a == callee) {
						return null; // recursive: the moved body would call a dead entry
					}
				}
				default -> {
				}
			}
			if (in.isOpener()) {
				depth++;
			}
		}
		boolean stackHandOver = params == 0 || code.size() > params;
		for (int i = 0; stackHandOver && i < params; i++) {
			Instr in = code.get(i);
			stackHandOver = in.op == OP_LOCAL_GET && in.a == i && reads[i] == 1 && writes[i] == 0;
		}
		return new Shape(reads, writes, stackHandOver, needsBlock);
	}

	// The callee's instructions, renumbered into the caller: a parameter read becomes its
	// slot or its argument's own instruction, a local becomes its shifted index, and a
	// `return` becomes a branch to the wrapping block. Everything else is copied verbatim
	// -- including a `br` whose label already names the function body, which the wrapper
	// block silently takes over.
	private static void writeMovedBody(ByteArrayOutputStream out, byte[] entry, List<Instr> code, int skip, int params,
			int[] slot, byte[][] substitute, int localBase, boolean needsBlock) {
		int depth = 0;
		for (int k = 0; k < code.size() - 1; k++) {
			Instr in = code.get(k);
			if (in.op == OP_END && in.match >= 0) {
				depth--;
			}
			if (k >= skip) {
				if (in.op == OP_LOCAL_GET || in.op == OP_LOCAL_SET || in.op == OP_LOCAL_TEE) {
					int index = (int) in.a;
					if (index < params) {
						byte[] raw = substitute[index];
						if (raw != null) {
							WasmSections.writeRaw(out, raw);
						}
						else {
							out.write(in.op);
							WasmSections.writeU(out, slot[index]);
						}
					}
					else {
						out.write(in.op);
						WasmSections.writeU(out, localBase + index - params);
					}
				}
				else if (in.op == OP_RETURN && needsBlock) {
					out.write(OP_BR);
					WasmSections.writeU(out, depth);
				}
				else if (in.op == OP_RETURN_CALL) {
					// The callee's frame is gone either way: as a plain call here the
					// stack is exactly as deep as the tail call left it. Only a
					// non-trailing one needs the branch out (shapeOf); a trailing one
					// falls off the end, block or no block.
					out.write(OP_CALL);
					WasmSections.writeU(out, (int) in.a);
					if (needsBlock && !isTrailing(code, k, depth)) {
						out.write(OP_BR);
						WasmSections.writeU(out, depth);
					}
				}
				else {
					WasmSections.writeRaw(out, WasmSections.slice(entry, in.start, in.end));
				}
			}
			if (in.isOpener()) {
				depth++;
			}
		}
	}

	// Whether the instruction at `k` is the body's last one (before the terminating end)
	// at nesting depth 0: what it leaves on the stack is what the body answers.
	private static boolean isTrailing(List<Instr> code, int k, int depth) {
		return depth == 0 && k == code.size() - 2;
	}

	// --- Encoding helpers ---

	private static boolean isPusher(Instr in) {
		// Pushes exactly one value, pops none, and can be written again anywhere: the
		// only two shapes whose argument this pass may delete or repeat.
		return in.op == OP_LOCAL_GET || in.op == 0x41 || in.op == 0x42 || in.op == 0x43 || in.op == 0x44;
	}

	private static void writeLocals(ByteArrayOutputStream out, List<ValType> existing, List<ValType> added) {
		List<ValType> all = new ArrayList<>(existing.size() + added.size());
		all.addAll(existing);
		all.addAll(added);
		WasmCodeModel.writeLocals(out, all);
	}

	private static void writeBlockType(ByteArrayOutputStream out, List<ValType> results) {
		if (results.isEmpty()) {
			out.write(0x40);
			return;
		}
		WasmCodeModel.writeValType(out, results.get(0));
	}

	private static int lebLen(int value) {
		int len = 1;
		int v = value >>> 7;
		while (v != 0) {
			len++;
			v >>>= 7;
		}
		return len;
	}

	private static int[] exportedFunctions(@Nullable Section exportSec) {
		if (exportSec == null) {
			return new int[0];
		}
		byte[] payload = exportSec.payload();
		int[] p = { 0 };
		int count = WasmSections.readU(payload, p);
		int[] roots = new int[count];
		int n = 0;
		for (int i = 0; i < count; i++) {
			WasmSections.skipName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			int index = WasmSections.readU(payload, p);
			if (kind == WasmSections.KIND_FUNC) {
				roots[n++] = index;
			}
		}
		return java.util.Arrays.copyOf(roots, n);
	}

}
