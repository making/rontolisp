package am.ik.rontolisp.codegen.wasm;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import org.jspecify.annotations.Nullable;

/**
 * The landing-pad discipline every {@code try_table} region with user-visible landing
 * code follows on the wasm-GC backend ({@code .kb/wasm-landing-pad-refresh.md}).
 *
 * <p>
 * A wasm local read in a landing pad -- or anywhere control continues after one -- is a
 * Cranelift SSA variable whose value the frontend resolves through the catch block's
 * predecessors, one per call in the protected body. When those predecessors do not agree
 * on the value (a loop with a two-way merge inside the body leaves a redundant block
 * parameter behind, so calls before the loop see one SSA value and calls after it
 * another), the catch block keeps a block parameter for the local and each
 * {@code try_call} passes its value as an exceptional-edge ARGUMENT -- evaluated before
 * the call. The moving collector then updates every stack-mapped slot during the call,
 * but the argument travels in a register or regalloc spill slot the stack map never
 * lists, so the handler receives the pre-collection reference of an object that has moved
 * (wasmtime 47 through 49.0.0-rc.1, the copying collector; {@code drc} does not move and
 * hides it).
 *
 * <p>
 * The discipline sidesteps the variable resolution entirely: the region pushes every live
 * local onto the operand stack right before its landing block ({@link #keepLocalsAlive}),
 * so the values cross the body as plain SSA values -- which the frontend spills to
 * stack-mapped slots and reloads at their use -- and the landing pad's first act is to
 * pop them back into the locals ({@link #refreshLocals}). Every read after that resolves
 * to a definition inside the pad, so the catch block carries no local at all. The normal
 * exit and the return trampolines branch out of the block, which discards the pushed
 * values (wasm unwinds the operand stack to the target's height), so the non-exceptional
 * paths cost nothing at run time.
 *
 * <p>
 * The snapshot is taken at region ENTRY, so a variable the protected body assigns would
 * be refreshed to its entry value -- wrong for a cleanup that must see the latest
 * assignment. Such variables are boxed instead ({@link #regionAssignedVars}, folded into
 * every binder's boxed set beside the closure-capture answer): the local then holds a
 * heap cell the refresh restores unchanged, and the cell's content is read fresh from the
 * heap.
 */
final class WasmLandingPad {

	private WasmLandingPad() {
	}

	/**
	 * Pushes every local declared so far onto the operand stack, so each stays a live SSA
	 * value across the protected body. Emit immediately before the landing block (inside
	 * the innermost block enclosing the landing code), and hand the count to
	 * {@link #refreshLocals}.
	 * @param ctx the compilation context
	 * @return the number of locals pushed
	 */
	static int keepLocalsAlive(WasmLispCompiler.Ctx ctx) {
		int count = ctx.nextLocal;
		for (int slot = 0; slot < count; slot++) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
		}
		return count;
	}

	/** Every local declared so far -- what {@link #keepLocalsAlive} pushes. */
	static int[] allSlots(WasmLispCompiler.Ctx ctx) {
		int[] slots = new int[ctx.nextLocal];
		for (int slot = 0; slot < slots.length; slot++) {
			slots[slot] = slot;
		}
		return slots;
	}

	/**
	 * {@link #keepLocalsAlive} over an explicit slot list: the locals a landing pad
	 * READS. A region whose pad reads nothing but its own save slots (a special
	 * {@code let}'s binding restores, which then rethrow -- no user code runs in or after
	 * the pad) keeps only those, and the invariant holds all the same: no local the pad
	 * reads reaches it through the catch block.
	 * @param ctx the compilation context
	 * @param slots the locals to keep, in push order
	 */
	static void keepSlotsAlive(WasmLispCompiler.Ctx ctx, int[] slots) {
		for (int slot : slots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
		}
	}

	/**
	 * Pops the values {@link #keepSlotsAlive} pushed back into their locals, in reverse
	 * order.
	 * @param ctx the compilation context
	 * @param slots the locals pushed
	 */
	static void refreshSlots(WasmLispCompiler.Ctx ctx, int[] slots) {
		for (int i = slots.length - 1; i >= 0; i--) {
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
	}

	/**
	 * Pops the values {@link #keepLocalsAlive} pushed back into their locals, in reverse
	 * order. The caller has already moved the landing block's own result (the payload)
	 * off the stack, so the pushed values are on top.
	 * @param ctx the compilation context
	 * @param count the number of locals pushed
	 */
	static void refreshLocals(WasmLispCompiler.Ctx ctx, int count) {
		for (int slot = count - 1; slot >= 0; slot--) {
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
		}
	}

	/**
	 * The variables among {@code localVars} that some region-bearing form inside
	 * {@code body} assigns -- with {@code setq} or any modify operator whose place is the
	 * bare variable -- and which therefore must be boxed. Blind to lexical scope on
	 * purpose: an inner binding's assignment of the same name also lands here, which only
	 * adds a cell.
	 * <p>
	 * Every binder asks about its whole body, so {@code memo} keeps what each form
	 * assigns (whichever names are asked about): a nest of lets used to rescan its
	 * innermost forms once per level.
	 * @param body the forms a binder is about to compile
	 * @param localVars the names the binder binds
	 * @param memo the answers so far, one per compilation
	 * @return the subset to box
	 */
	static Set<String> regionAssignedVars(List<LispVal> body, Set<String> localVars, RegionMemo memo) {
		Set<String> found = new HashSet<>();
		if (localVars.isEmpty()) {
			return found;
		}
		for (LispVal form : body) {
			for (String name : assignedRoot(form, false, memo)) {
				if (localVars.contains(name)) {
					found.add(name);
				}
			}
		}
		return found;
	}

	/**
	 * What {@link #regionAssignedVars} has scanned, per form (by identity) and per
	 * whether it sits inside a region. Not thread-safe: one per compilation.
	 */
	static final class RegionMemo {

		final IdentityHashMap<LispCons, List<String>> outside = new IdentityHashMap<>();

		final IdentityHashMap<LispCons, List<String>> inside = new IdentityHashMap<>();

	}

	// assigned, through the memo: for a form a binder will ask about on its own -- one
	// statement of a let, lambda or defun body -- so the memo holds those rather than
	// every cons the scan passes.
	private static List<String> assignedRoot(LispVal form, boolean inRegion, RegionMemo memo) {
		if (!(form instanceof LispCons cons)) {
			return List.of();
		}
		IdentityHashMap<LispCons, List<String>> answers = inRegion ? memo.inside : memo.outside;
		List<String> known = answers.get(cons);
		if (known != null) {
			return known;
		}
		List<String> found = assigned(cons, inRegion, memo);
		answers.put(cons, found);
		return found;
	}

	// Every bare-symbol place a modify form inside a region assigns under this form, in
	// order of first occurrence -- whichever locals are asked about, so the answer can
	// be kept per form.
	private static List<String> assigned(LispVal form, boolean inRegion, RegionMemo memo) {
		if (!(form instanceof LispCons cons)) {
			return List.of();
		}
		List<String> single = List.of();
		@Nullable LinkedHashSet<String> merged = null;
		boolean region = inRegion;
		boolean quoted = false;
		// The first body statement of a binder, counting the operator as element 0.
		int firstStatement = Integer.MAX_VALUE;
		if (cons.car() instanceof LispSymbol head) {
			String name = head.name();
			firstStatement = switch (name) {
				case LispNames.LET, LispNames.LET_STAR, LispNames.LAMBDA -> 2;
				case LispNames.DEFUN -> 3;
				default -> Integer.MAX_VALUE;
			};
			quoted = LispNames.QUOTE.equals(name);
			if (!quoted && inRegion && WasmCountedLoopCompiler.MODIFY_OPERATORS.contains(name) && cons.isProperList()) {
				merged = new LinkedHashSet<>();
				for (LispVal place : symbolPlaces(name, cons.toList())) {
					merged.add(((LispSymbol) place).name());
				}
			}
			region = inRegion || isRegionHead(name);
		}
		LispVal cur = quoted ? LispNil.INSTANCE : cons;
		int element = 0;
		while (cur instanceof LispCons cell) {
			List<String> names = element++ >= firstStatement ? assignedRoot(cell.car(), region, memo)
					: assigned(cell.car(), region, memo);
			if (!names.isEmpty()) {
				if (merged != null) {
					merged.addAll(names);
				}
				else if (single.isEmpty()) {
					single = names;
				}
				else {
					merged = new LinkedHashSet<>(single);
					merged.addAll(names);
				}
			}
			cur = cell.cdr();
		}
		return merged != null ? List.copyOf(merged) : single;
	}

	/**
	 * Whether a form with this head compiles to a {@code try_table} whose landing runs
	 * user code (or continues into it): the special forms the region compilers own, the
	 * condition-system macros that expand to them, and every {@code with-} resource
	 * macro, whose expansions wrap the body in {@code unwind-protect}. An over-answer
	 * only boxes a variable that did not need it.
	 */
	private static boolean isRegionHead(String name) {
		switch (name) {
			case LispNames.UNWIND_PROTECT, LispNames.HANDLER_CASE, LispNames.IGNORE_ERRORS, LispNames.CATCH,
					LispNames.NLX_CATCH_INTERNAL, LispNames.HB_GUARD_INTERNAL, LispNames.HANDLER_BIND,
					LispNames.RESTART_CASE, LispNames.RESTART_BIND, LispNames.WITH_SIMPLE_RESTART, LispNames.PROGV -> {
				return true;
			}
			default -> {
				int colon = name.lastIndexOf(':');
				return name.startsWith("WITH-", colon + 1);
			}
		}
	}

	/** The bare-symbol places of a modify form, by operator. */
	private static List<LispVal> symbolPlaces(String op, List<LispVal> parts) {
		List<LispVal> candidates = switch (op) {
			case LispNames.SETQ, LispNames.PSETQ, LispNames.SETF, LispNames.PSETF -> {
				List<LispVal> out = new java.util.ArrayList<>();
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					out.add(parts.get(i));
				}
				yield out;
			}
			case LispNames.MULTIPLE_VALUE_SETQ ->
				parts.size() > 1 && parts.get(1) instanceof LispCons names ? names.toList() : List.of();
			case LispNames.PUSH, LispNames.PUSHNEW -> parts.size() > 2 ? List.of(parts.get(2)) : List.of();
			case LispNames.ROTATEF -> parts.subList(1, parts.size());
			case LispNames.SHIFTF -> parts.size() > 2 ? parts.subList(1, parts.size() - 1) : List.<LispVal>of();
			default -> parts.size() > 1 ? List.of(parts.get(1)) : List.of();
		};
		return candidates.stream().filter(place -> place instanceof LispSymbol).toList();
	}

}
