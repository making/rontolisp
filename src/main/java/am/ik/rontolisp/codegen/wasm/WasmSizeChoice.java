package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.UnsynchronizedByteArrayOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Under {@code --optimize=size}, which of an integer site's two emissions ships: the
 * fused tree ({@link WasmIntFusionCompiler}) or the per-operation path. Both are written
 * into the body, measured and taken back, and the smaller is written for real -- a
 * declared {@code (signed-byte 32)} operation is one i64 instruction fused and calls into
 * the bignum-capable helpers unfused, while a single operation over two plain variables
 * is a helper call unfused and two unboxing guards fused.
 *
 * <p>
 * <strong>A trial compiles no user code.</strong> What the two emissions share is the
 * site's leaves: each compound leaf form is written as a read of a placeholder local in
 * both trials ({@link #placeholder}), so the measurement is of what differs and nothing a
 * leaf's compilation registers (a lambda, a warning) happens twice. A site with a
 * compound leaf the unfused path never compiles -- a form inside a substituted function's
 * body -- has no comparable measurement and stays unfused.
 *
 * <p>
 * A site's choice is remembered per body, by the form and the entry point, so a site
 * nested in another's unfused path is measured once, not once per enclosing trial. A body
 * under {@code --report-locations} keeps every site unfused: a trial would move its
 * frame's line state.
 */
final class WasmSizeChoice {

	/** The entry point a site comes through: each has its own unfused path. */
	enum Kind {

		/** A boxed value ({@code WasmIntFusionCompiler.tryCompile}). */
		VALUE,

		/** A comparison's i32 truth value ({@code tryCompileCompare}). */
		COMPARE,

		/** A packed store's raw value ({@code tryCompileRaw}). */
		RAW

	}

	/** One body's choices and the trial running now. */
	static final class State {

		private final Map<Kind, IdentityHashMap<LispVal, Boolean>> chosen = new EnumMap<>(Kind.class);

		private final IdentityHashMap<LispVal, Placeholder> placeholders = new IdentityHashMap<>();

		private @Nullable Kind trialKind;

		private @Nullable LispVal trialRoot;

		private boolean trialFused;

	}

	/**
	 * The local a trial reads in place of a leaf form, and how many times the trial
	 * running now read it: once in each, or the form was compiled past the placeholder.
	 */
	private static final class Placeholder {

		final int slot;

		int reads;

		Placeholder(int slot) {
			this.slot = slot;
		}

	}

	private WasmSizeChoice() {
	}

	/**
	 * Whether this body chooses per site: the size level, outside the cold copy of a leaf
	 * ({@code Ctx.declinesSpeedTrades}) and outside a located frame.
	 * @param ctx the compile context
	 * @return {@code true} when a site is measured
	 */
	static boolean applies(WasmLispCompiler.Ctx ctx) {
		return ctx.optimize.prefersSizeOverSpeed() && !ctx.declinesSpeedTrades && ctx.ucFrame == null
				&& ctx.bodyStream instanceof UnsynchronizedByteArrayOutputStream;
	}

	/**
	 * Whether this site is the unfused half of the trial running now: its entry point
	 * then emits nothing and its caller writes the per-operation path.
	 * @param kind the entry point
	 * @param root the site's form
	 * @param ctx the compile context
	 * @return {@code true} when the entry point must decline
	 */
	static boolean declines(Kind kind, LispVal root, WasmLispCompiler.Ctx ctx) {
		State state = ctx.sizeChoice;
		return state != null && state.trialKind == kind && state.trialRoot == root && !state.trialFused;
	}

	/**
	 * Whether the site's fused emission is the smaller one, measured once per body.
	 * @param kind the entry point
	 * @param root the site's form, as its entry point received it
	 * @param leafForms the forms of the site's leaves the fused path compiles
	 * @param ctx the compile context
	 * @param fused writes the fused emission (the entry point again, which this trial
	 * lets through); answers whether it did
	 * @param unfused writes the per-operation path, during which the entry point declines
	 * for {@code root}
	 * @return {@code true} when the fused emission is strictly smaller
	 */
	static boolean fusedIsSmaller(Kind kind, LispVal root, List<LispVal> leafForms, WasmLispCompiler.Ctx ctx,
			BooleanSupplier fused, Runnable unfused) {
		State state = ctx.sizeChoice;
		if (state == null) {
			state = new State();
			ctx.sizeChoice = state;
		}
		if (state.trialKind == kind && state.trialRoot == root && state.trialFused) {
			return true;
		}
		IdentityHashMap<LispVal, Boolean> chosen = state.chosen.computeIfAbsent(kind, k -> new IdentityHashMap<>());
		Boolean known = chosen.get(root);
		if (known != null) {
			return known;
		}
		boolean smaller = measure(kind, root, leafForms, ctx, state, fused, unfused);
		chosen.put(root, smaller);
		return smaller;
	}

	private static boolean measure(Kind kind, LispVal root, List<LispVal> leafForms, WasmLispCompiler.Ctx ctx,
			State state, BooleanSupplier fused, Runnable unfused) {
		java.util.Set<LispVal> written = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		collectConses(root, written);
		List<LispVal> placed = new ArrayList<>();
		for (LispVal form : leafForms) {
			if (!(form instanceof LispCons)) {
				// A variable or a literal compiles to the same few bytes wherever it is,
				// registering nothing: both trials write it as it is.
				continue;
			}
			if (!written.contains(form) || namesFunction(form)) {
				// Compiled by the fused path alone, or a function a compiler reaching the
				// form past its placeholder would register twice.
				return false;
			}
			placed.add(form);
		}
		Kind savedKind = state.trialKind;
		LispVal savedRoot = state.trialRoot;
		boolean savedFused = state.trialFused;
		IdentityHashMap<LispVal, @Nullable Placeholder> shadowed = new IdentityHashMap<>();
		List<Placeholder> own = new ArrayList<>();
		Mark start = new Mark(ctx);
		try {
			for (LispVal form : placed) {
				Placeholder placeholder = new Placeholder(ctx.allocTemp());
				own.add(placeholder);
				for (LispVal f = form; f != null; f = theOperand(f)) {
					if (!shadowed.containsKey(f)) {
						shadowed.put(f, state.placeholders.put(f, placeholder));
					}
				}
			}
			state.trialKind = kind;
			state.trialRoot = root;
			Mark before = new Mark(ctx);
			state.trialFused = true;
			boolean wrote = fused.getAsBoolean();
			int fusedBytes = before.bytesSince(ctx);
			before.rewind(ctx);
			if (!wrote || !readOnce(own)) {
				return false;
			}
			state.trialFused = false;
			unfused.run();
			int unfusedBytes = before.bytesSince(ctx);
			boolean comparable = readOnce(own);
			return comparable && fusedBytes < unfusedBytes;
		}
		finally {
			start.rewind(ctx);
			for (var entry : shadowed.entrySet()) {
				if (entry.getValue() == null) {
					state.placeholders.remove(entry.getKey());
				}
				else {
					state.placeholders.put(entry.getKey(), entry.getValue());
				}
			}
			state.trialKind = savedKind;
			state.trialRoot = savedRoot;
			state.trialFused = savedFused;
		}
	}

	/**
	 * Whether the trial just run read each placeholder exactly once, and resets the
	 * counts for the next: a form read twice, or compiled without its placeholder, makes
	 * the two measurements incomparable.
	 */
	private static boolean readOnce(List<Placeholder> placeholders) {
		boolean once = true;
		for (Placeholder placeholder : placeholders) {
			once &= placeholder.reads == 1;
			placeholder.reads = 0;
		}
		return once;
	}

	private static boolean namesFunction(LispVal form) {
		if (form instanceof LispSymbol symbol) {
			return LispNames.LAMBDA.equals(symbol.name()) || LispNames.FLET.equals(symbol.name())
					|| LispNames.LABELS.equals(symbol.name());
		}
		LispVal cur = form;
		while (cur instanceof LispCons cons) {
			if (namesFunction(cons.car())) {
				return true;
			}
			cur = cons.cdr();
		}
		return false;
	}

	/**
	 * Writes the read of {@code form}'s placeholder when a trial stands one in for it.
	 * @param form the form about to compile
	 * @param ctx the compile context
	 * @return {@code true} when the read was written in place of the form
	 */
	static boolean placeholder(LispVal form, WasmLispCompiler.Ctx ctx) {
		State state = ctx.sizeChoice;
		if (state == null || state.placeholders.isEmpty()) {
			return false;
		}
		Placeholder placeholder = state.placeholders.get(form);
		if (placeholder == null) {
			return false;
		}
		placeholder.reads++;
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(placeholder.slot);
		return true;
	}

	/**
	 * The operand of a {@code (the type x)} form, which a compiler may reach directly.
	 */
	private static @Nullable LispVal theOperand(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && LispNames.THE.equals(head.name())
				&& cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			return parts.size() == 3 ? parts.get(2) : null;
		}
		return null;
	}

	private static void collectConses(LispVal form, java.util.Set<LispVal> out) {
		LispVal cur = form;
		while (cur instanceof LispCons cons && out.add(cons)) {
			collectConses(cons.car(), out);
			cur = cons.cdr();
		}
	}

	/**
	 * What a trial can move in the body being written, to be taken back: the bytes, the
	 * locals it allocated and the i64 references it recorded.
	 */
	private static final class Mark {

		private final int size;

		private final int i64Refs;

		private final int nextLocal;

		private final int nextI64Local;

		private final int maxI64Locals;

		private final int fxLitTempSlot;

		private final java.util.EnumSet<WasmLispCompiler.Ctx.Scratch> usedScratch;

		private final int carries;

		private final int lambdas;

		private final int quotedBuildNodes;

		private final boolean tailPosition;

		private final @Nullable String operator;

		Mark(WasmLispCompiler.Ctx ctx) {
			this.size = ctx.bodyStream.size();
			this.i64Refs = ctx.i64LocalRefs.size();
			this.nextLocal = ctx.nextLocal;
			this.nextI64Local = ctx.nextI64Local;
			this.maxI64Locals = ctx.maxI64Locals;
			this.fxLitTempSlot = ctx.fxLitTempSlot;
			this.usedScratch = java.util.EnumSet.copyOf(ctx.usedScratch);
			this.carries = ctx.carries.size();
			this.lambdas = ctx.lambdaDecls.size();
			this.quotedBuildNodes = ctx.quotedBuildNodes;
			this.tailPosition = ctx.tailPosition;
			this.operator = ctx.operator;
		}

		/**
		 * The bytes written since, each i64 reference counted at the width its index will
		 * have rather than its placeholder's.
		 */
		int bytesSince(WasmLispCompiler.Ctx ctx) {
			int bytes = ctx.bodyStream.size() - this.size;
			for (int r = this.i64Refs; r < ctx.i64LocalRefs.size(); r++) {
				int slot = ctx.i64LocalRefs.get(r)[1];
				bytes -= WasmLispCompiler.Ctx.I64_LOCAL_PLACEHOLDER_WIDTH - lebWidth(ctx.nextLocal + Math.max(0, slot));
			}
			return bytes;
		}

		void rewind(WasmLispCompiler.Ctx ctx) {
			if (ctx.lambdaDecls.size() != this.lambdas) {
				// A lambda registered by a trial cannot be taken back: the placeholders
				// exist so that no trial compiles one.
				throw new IllegalStateException("a size trial compiled a lambda");
			}
			((UnsynchronizedByteArrayOutputStream) ctx.bodyStream).truncate(this.size);
			ctx.i64LocalRefs.subList(this.i64Refs, ctx.i64LocalRefs.size()).clear();
			ctx.nextLocal = this.nextLocal;
			ctx.nextI64Local = this.nextI64Local;
			ctx.maxI64Locals = this.maxI64Locals;
			ctx.fxLitTempSlot = this.fxLitTempSlot;
			ctx.usedScratch.retainAll(this.usedScratch);
			ctx.carries.subList(this.carries, ctx.carries.size()).clear();
			ctx.quotedBuildNodes = this.quotedBuildNodes;
			ctx.tailPosition = this.tailPosition;
			ctx.operator = this.operator;
		}

		private static int lebWidth(int value) {
			int width = 1;
			for (int v = value >>> 7; v != 0; v >>>= 7) {
				width++;
			}
			return width;
		}

	}

}
