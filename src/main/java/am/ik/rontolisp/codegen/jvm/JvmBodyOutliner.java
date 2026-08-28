package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispVal;

/**
 * Splits a function body that would compile past HotSpot's 8000-bytecode
 * {@code HugeMethodLimit} into a chain of tail continuations
 * ({@code .kb/hot-path-method-size.md}).
 *
 * <p>
 * The body is driven from a QUEUE of pending items rather than a nested loop per
 * construct, so the whole tail spine of a function -- its own body forms, and the body of
 * every {@code let}/{@code progn} (hence {@code flet}/{@code labels}/{@code let*}/
 * {@code locally}, which lower to those) that ends it -- is ONE flat sequence with a
 * split point between any two items. Nothing is spliced or reordered: an item is emitted
 * exactly as the construct's own loop would have emitted it, so a body that never crosses
 * the budget is byte-identical to the nested-loop emission it replaced.
 *
 * <p>
 * At a split the remaining items move into a fresh {@code _k$N} static method whose
 * parameters are the live locals; the caller loads them, {@code invokestatic}s it, and
 * the value it answers IS the enclosing method's value. Passing the locals forward is
 * what makes mutation work without returning anything but the value: an item that
 * {@code setq}s a variable does so on the continuation's copy, and no item AFTER the
 * continuation can observe the outer copy, because a split is only taken where every
 * remaining item is in the method's tail (only compile-time scope restores and
 * stack-neutral special-binding restores may follow, and those run in the caller once the
 * continuation returns).
 */
final class JvmBodyOutliner {

	/**
	 * Bytecodes emitted into one method before the remaining body items move to a
	 * continuation. Below the 8000 cliff by enough to absorb the one item that is emitted
	 * past the check (the check happens BETWEEN items, so a single item larger than the
	 * headroom still has no split point -- the same per-form limit the top-level chunker
	 * has).
	 */
	private static final int CODE_BUDGET = 6000;

	/**
	 * The most live locals a continuation may take. The JVM caps a method at 255 argument
	 * slots; the margin leaves room for the closure environment and keeps a pathological
	 * frame from being copied at every split.
	 */
	private static final int MAX_CONTINUATION_PARAMS = 200;

	private JvmBodyOutliner() {
	}

	/** One pending piece of the tail spine. */
	sealed interface Item {

	}

	/** Compile the form in value position (its result is left on the stack). */
	record ValueForm(LispVal form) implements Item {

	}

	/** Compile the form for effect (its result, if any, is discarded). */
	record EffectForm(LispVal form) implements Item {

	}

	/** Discard the value the previous item left. */
	record PopValue() implements Item {

	}

	/**
	 * A construct's own after-the-body work: the compile-time scope restore, plus
	 * whatever stack-neutral code the construct emits over the body's value (a
	 * {@code let}'s dynamic-binding restores). Always a SUFFIX of the queue at a split
	 * point, and always run by the method that opened the scope.
	 */
	record Cleanup(Runnable action) implements Item {

	}

	/** An outlined continuation, ready for class assembly. */
	record OutlinedBody(String name, Utf8Constant nameUtf8, Utf8Constant descUtf8, JvmLispCompiler.Ctx ctx) {

	}

	/** The queue of items still to emit for one method. */
	static final class Tail {

		private final Deque<Item> queue = new ArrayDeque<>();

		/**
		 * Puts a construct's body items at the FRONT, before whatever the enclosing
		 * constructs still owe -- the queue is the spine read outside-in, so the
		 * innermost work is always next.
		 * @param items the items, in emission order
		 */
		void pushFront(List<Item> items) {
			for (int i = items.size() - 1; i >= 0; i--) {
				this.queue.addFirst(items.get(i));
			}
		}

	}

	/**
	 * Compiles a function (or lambda) body as a chain of continuations. The caller emits
	 * the {@code areturn}: the value left on the stack is the body's, whether it came
	 * from the last form here or from a continuation's return.
	 * @param bodyExprs the body forms
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 */
	static void compileFunctionBody(List<LispVal> bodyExprs, JvmLispCompiler.Ctx ctx, String className) {
		Tail tail = new Tail();
		List<Item> seed = new ArrayList<>();
		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				seed.add(new PopValue());
			}
			seed.add(new ValueForm(bodyExprs.get(i)));
		}
		tail.pushFront(seed);
		run(tail, ctx, className);
	}

	private static void run(Tail tail, JvmLispCompiler.Ctx ctx, String className) {
		while (!tail.queue.isEmpty()) {
			if (readyToSplit(tail, ctx)) {
				split(tail, ctx, className);
				continue;
			}
			Item item = tail.queue.removeFirst();
			switch (item) {
				case PopValue ignored -> ctx.emit(Opcode.POP);
				case Cleanup cleanup -> cleanup.action().run();
				case EffectForm effect -> {
					// "Value, then pop" is what compileForEffect does for everything
					// but a statement assignment -- said as two items so a nested body
					// still joins the spine and the pop lands after it.
					if (!JvmExprCompiler.compileStatementSetq(effect.form(), ctx, className)) {
						tail.pushFront(List.of(new ValueForm(effect.form()), new PopValue()));
					}
				}
				case ValueForm value -> {
					ctx.tailBody = tail;
					JvmExprCompiler.compileExpr(value.form(), ctx, className);
					ctx.tailBody = null;
				}
			}
		}
	}

	private static boolean readyToSplit(Tail tail, JvmLispCompiler.Ctx ctx) {
		if (ctx.code.size() < CODE_BUDGET) {
			return false;
		}
		// Every remaining Cleanup must be a suffix: an item AFTER one belongs to a scope
		// this method is about to close, and the continuation -- which knows only the
		// scope live at the split -- could not compile it in the right environment.
		int work = 0;
		boolean cleanupSeen = false;
		for (Item item : tail.queue) {
			if (item instanceof Cleanup) {
				cleanupSeen = true;
			}
			else if (cleanupSeen) {
				return false;
			}
			else {
				work++;
			}
		}
		if (work == 0) {
			return false;
		}
		// The continuation is a fresh frame: nothing that names a position in THIS one
		// may still be open across the call.
		if (!ctx.stack.snapshot().isEmpty() || !ctx.blockTargets.isEmpty() || !ctx.unwindScopes.isEmpty()
				|| !ctx.tagbodyScopes.isEmpty() || !ctx.spillScopes.isEmpty()) {
			return false;
		}
		return liveNames(ctx).size() + (ctx.closureEnvSlot >= 0 ? 1 : 0) <= MAX_CONTINUATION_PARAMS;
	}

	/** The lexical names the continuation has to carry, in a stable order. */
	private static List<String> liveNames(JvmLispCompiler.Ctx ctx) {
		TreeSet<String> names = new TreeSet<>(ctx.locals.keySet());
		names.addAll(ctx.rawLocals.keySet());
		return new ArrayList<>(names);
	}

	private static void split(Tail tail, JvmLispCompiler.Ctx ctx, String className) {
		List<Item> moved = new ArrayList<>();
		while (!tail.queue.isEmpty() && !(tail.queue.peekFirst() instanceof Cleanup)) {
			moved.add(tail.queue.removeFirst());
		}
		List<String> names = liveNames(ctx);
		boolean hasEnv = ctx.closureEnvSlot >= 0;
		StringBuilder desc = new StringBuilder("(");
		if (hasEnv) {
			desc.append("[Ljava/lang/Object;");
		}
		desc.append("Ljava/lang/Object;".repeat(names.size())).append(")Ljava/lang/Object;");
		String methodName = "_k$" + ctx.nextOutlinedBodyId[0]++;
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(methodName);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(desc.toString());
		MethodrefConstant ref = JvmEmitHelper.selfMethod(ctx, className, methodName, desc.toString());
		// The call: the live environment, in the continuation's parameter order.
		if (hasEnv) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
		}
		for (String name : names) {
			JvmIntFusionCompiler.RawLocal raw = ctx.rawLocals.get(name);
			if (raw != null) {
				// A dual-representation local crosses boxed and lands as an ordinary
				// one: the continuation re-derives nothing, it just holds the value.
				JvmIntFusionCompiler.emitRawLocalBoxedRead(raw, ctx);
			}
			else {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(java.util.Objects.requireNonNull(ctx.locals.get(name)));
			}
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
		JvmLispCompiler.Ctx cont = ctx.ctxBuilder.build();
		cont.evalStoreRef = ctx.evalStoreRef;
		int slot = 0;
		if (hasEnv) {
			cont.closureEnvSlot = 0;
			cont.captures = new HashMap<>(ctx.captures);
			slot = 1;
		}
		for (String name : names) {
			cont.locals.put(name, slot++);
		}
		cont.nextLocal = slot;
		cont.maxLocals = slot;
		// A captured name's slot holds the Object[1] cell itself, so the continuation
		// shares the very cell an already-built closure reads and writes.
		HashSet<String> boxed = new HashSet<>();
		for (String name : names) {
			if (ctx.boxedVars.contains(name)) {
				boxed.add(name);
			}
		}
		cont.boxedVars = boxed;
		Tail contTail = new Tail();
		contTail.pushFront(moved);
		run(contTail, cont, className);
		cont.emit(Opcode.ARETURN);
		ctx.outlinedBodies.add(new OutlinedBody(methodName, nameUtf8, descUtf8, cont));
	}

}
