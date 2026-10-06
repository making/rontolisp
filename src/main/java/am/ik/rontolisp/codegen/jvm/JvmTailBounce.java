package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import java.lang.classfile.constantpool.ClassEntry;

import am.ik.jvm.MethodCode;
import am.ik.jvm.ConstantPool;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * The compile-time half of the trampoline that makes a tail call through a procedure
 * value proper on the JVM ({@code .kb/jvm-tail-bounce.md}).
 *
 * <p>
 * A tail call whose target the compiler cannot name -- {@code (funcall f ...)} over a
 * variable, an argument, any computed designator, and the general indirect
 * {@code (expr arg...)} -- in a defun's or a lambda's body is a VALUE TAIL: the
 * designator and the arguments are evaluated and handed to the class's {@code _vtc<n>},
 * whose answer is the method's result. {@code _vtc<n>} makes the call a real one, its
 * answer passed on unchecked, while fewer than {@link #VALUE_TAIL_LIMIT} value tails have
 * run since the nearest ordinary call; past it, it answers a BOUNCE instead: one
 * {@code Object[]{marker, designatorValue, arg...}}. Every caller that receives a
 * compiled function's result checks for the array and, on the bounce shape, drives the
 * call it names in ITS OWN frame -- the trampoline loop of the shared helper
 * {@code _tramp}, which re-enters the per-arity dispatchers and loops while the answer is
 * again a bounce. A shallow chain -- an adapter, a composition, a reduce step -- is a
 * plain call the JIT can inline; a deep one unwinds to the nearest checking frame once
 * per limit's worth of frames and then runs in that frame's trampoline, so a state
 * machine calling through values runs on constant stack, while every call the existing
 * analyses already prove direct (the named-let/do loops, the self and mutual tail-call
 * groups) keeps its direct {@code invokestatic}.
 *
 * <p>
 * The count is an argument, THE DEPTH: the last parameter of every method that may answer
 * a bounce -- a {@code bounceVisible} defun, a lambda whose body reads it, a continuation
 * split from either -- and of every dispatcher. A value tail passes its method's depth
 * plus one, a named tail call that hands its callee's bounce on passes it unchanged,
 * every other call passes 0, and the trampoline passes the limit, so the chain it drives
 * keeps bouncing. Inlined, the depth is a constant per hop and its check folds; a count
 * in a field could not, its stores and loads staying at every hop. The bound is per
 * ordinary call, on every thread.
 *
 * <p>
 * The real call goes through {@code _vtcd<n>}, a copy of the arity's dispatcher that only
 * value tails call, while the arity's dispatcher fits one segment
 * ({@link #valueTailDispatcherName}). A dispatcher's branch profile is one per method, so
 * a JIT inlining a chain through the shared dispatcher inlines it again at every hop with
 * every target the program's indirect calls reach; through the copy, a hop sees only the
 * targets value tails reach. The copy checks the depth at its entry, so {@code _vtc<n>}
 * stays a forwarder a JIT inlines while it parses the caller. A routed dispatcher keeps
 * the shared one: the copy would double the largest dispatchers. The copy spends bytes on
 * speed, so {@code --optimize=size} declines it.
 *
 * <p>
 * A tail call the compiler CAN name -- a defun by name or through a literal
 * {@code #'name} -- stays that direct call, and when the callee may itself answer a
 * bounce and every caller of this method checks for one ({@code Ctx.passesBounces}) the
 * callee's bounce is handed on as this method's answer rather than driven here: a chain
 * that alternates closures and named functions -- a continuation handed to a defun that
 * calls it -- then keeps no frame per round either.
 *
 * <p>
 * The value tail is emitted only where the value flows to the method's result unchanged
 * -- the mark {@link JvmBodyOutliner} lays on the final spine item, re-laid by every form
 * that hands a sub-form's value on ({@link JvmSelfTailCall}) -- so no enclosing construct
 * consumes the value. A dynamic-binding restore between the call and the method's exit is
 * a spine {@code Cleanup} with runtime code: the tail then keeps a checked call, because
 * the restore must run inside the call's extent.
 *
 * <p>
 * The class carries {@code _tramp} only when a method its shake keeps can bounce
 * ({@link #unwBody}); otherwise every unwrap check answers its argument.
 */
final class JvmTailBounce {

	private JvmTailBounce() {
	}

	/**
	 * Slot 0 of a bounce array: a {@code java.lang.Boolean}, which no compiled function
	 * value carries in slot 0 (a function value's slot 0 is its {@code Integer} funcId,
	 * the one thing the dispatchers read). {@code TRUE} marks a call of the arguments in
	 * slots 2.., {@code FALSE} an apply of the argument list in slot 2.
	 */
	static final String MARKER_DESC = "Ljava/lang/Boolean;";

	/**
	 * The raw apply: {@code _apply}'s body without its answer checked, so a spread value
	 * tail can call it and the trampoline re-enter it for an apply's bounce, and loop on
	 * what it answers ({@link #emitSpreadValueTail}).
	 */
	static final String APPLY_RAW_NAME = "_applyRaw";

	/**
	 * The defuns whose method may answer a bounce, so every caller of theirs checks the
	 * result ({@code FunctionInfo.bounceVisible}): one whose tail mark reaches a call
	 * through a value -- a {@code funcall} of a designator that names no function the
	 * registry has, a computed head, a local function's call -- and, since a direct tail
	 * call hands its callee's bounce on, one whose tail calls such a defun directly or
	 * through a literal {@code #'name}, and every member of a tail group with one among
	 * its members (a member's method runs any member's code). The tail positions are the
	 * mark's ({@link JvmTailGroup#tailCalls}): a form the walk does not know ends it,
	 * which only keeps that call a call -- its defun bounces nowhere it was not found to.
	 * @param defuns the program's defuns
	 * @param groups the defuns' tail groups
	 * @param specials the special variables, whose binding ends a tail
	 * @return the names of the defuns that may answer a bounce
	 */
	static Set<String> bouncingDefuns(List<JvmLispCompiler.DefunDecl> defuns, List<JvmTailGroup> groups,
			Set<String> specials) {
		Map<String, JvmLispCompiler.DefunDecl> byName = new HashMap<>();
		for (JvmLispCompiler.DefunDecl defun : defuns) {
			byName.put(defun.name(), defun);
		}
		// Each defun's callers whose answer its own passes into: a direct tail call, or
		// a sibling in its tail group.
		Map<String, Set<String>> passedTo = new HashMap<>();
		Set<String> bouncing = new LinkedHashSet<>();
		Deque<String> work = new ArrayDeque<>();
		for (JvmLispCompiler.DefunDecl defun : defuns) {
			List<LispVal> body = defun.bodyExprs();
			if (body.isEmpty()) {
				continue;
			}
			boolean[] throughValue = { false };
			JvmTailGroup.tailCalls(body.get(body.size() - 1), specials, Set.of(), (call, locals) -> {
				String direct = directCallee(call, locals, byName, throughValue);
				if (direct != null) {
					passedTo.computeIfAbsent(direct, k -> new LinkedHashSet<>()).add(defun.name());
				}
			});
			if (throughValue[0] && bouncing.add(defun.name())) {
				work.add(defun.name());
			}
		}
		for (JvmTailGroup group : groups) {
			for (JvmTailGroup.Member member : group.members()) {
				for (JvmTailGroup.Member sibling : group.members()) {
					if (sibling != member) {
						passedTo.computeIfAbsent(member.name, k -> new LinkedHashSet<>()).add(sibling.name);
					}
				}
			}
		}
		while (!work.isEmpty()) {
			for (String caller : passedTo.getOrDefault(work.removeFirst(), Set.of())) {
				if (bouncing.add(caller)) {
					work.add(caller);
				}
			}
		}
		return bouncing;
	}

	/**
	 * The defun a call in tail position calls directly -- by name, or through a literal
	 * {@code #'name} its arity can take, as the emitter calls it -- or null, having set
	 * {@code throughValue} when the call goes through a value instead.
	 */
	private static @Nullable String directCallee(LispCons call, Set<String> locals,
			Map<String, JvmLispCompiler.DefunDecl> defuns, boolean[] throughValue) {
		if (!(call.car() instanceof LispSymbol op)) {
			// ((lambda ...) args) compiles inline; any other computed head is a value
			// tail, which may bounce.
			if (!(call.car() instanceof LispCons head && head.car() instanceof LispSymbol lambda
					&& LispNames.LAMBDA.equals(lambda.name()))) {
				throughValue[0] = true;
			}
			return null;
		}
		String name = op.name();
		List<LispVal> parts = call.toList();
		if (locals.contains(name)) {
			// A local function's call goes through the variable holding it.
			throughValue[0] = true;
			return null;
		}
		if (LispNames.FUNCALL.equals(name)) {
			// A literal designator naming a function its count reaches is a direct call
			// (JvmDesignatorCall); any other -- computed, a local function's, a name no
			// function of that arity answers -- is a value tail, which may bounce.
			String literal = parts.size() > 1 ? FunctionDesignators.literalName(parts.get(1)) : null;
			JvmLispCompiler.DefunDecl target = literal == null || locals.contains(literal) ? null : defuns.get(literal);
			if (target != null && JvmDesignatorCall.reaches(
					target.paramNames().size() - target.optionals() - (target.variadic() ? 1 : 0), target.variadic(),
					parts.size() - 2)) {
				return literal;
			}
			throughValue[0] = true;
			return null;
		}
		if (LispNames.APPLY.equals(name)) {
			// A literal target a defun answers is a direct call; any other designator
			// is a spread value tail, which may bounce with the argument list unspread
			// (emitSpreadValueTail).
			String literal = parts.size() > 2 ? LispMacroExpander.applyLiteralTargetName(parts.get(1)) : null;
			if (literal != null && !locals.contains(literal) && defuns.containsKey(literal)) {
				return literal;
			}
			throughValue[0] = true;
			return null;
		}
		return defuns.containsKey(name) ? name : null;
	}

	/**
	 * How many value tails in a row run as real calls before {@code _vtc<n>} bounces
	 * instead: the depth a method receives counts the value tails between it and the
	 * nearest ordinary call, so 64 real calls, then a bounce, and {@code _tramp} drives
	 * the rest of the chain. A chain shorter than this never allocates; a deep one
	 * bounces once per trampoline entry. The bound is per ordinary call: a non-tail
	 * recursion whose every level runs a chain stacks up to this many frame groups a
	 * level. {@code -Drontolisp.jvm.value-tail-limit=0} at compile time makes every value
	 * tail bounce, for measuring what the real calls buy.
	 */
	static final int VALUE_TAIL_LIMIT = Math.clamp(Integer.getInteger("rontolisp.jvm.value-tail-limit", 64), 0,
			Short.MAX_VALUE);

	/** The spread value tail an {@code apply} through a value calls. */
	static final String VALUE_TAIL_SPREAD_NAME = "_vtcv";

	/**
	 * {@return the name of the dispatcher copy {@code _vtc<n>} calls} A value tail of
	 * {@code arity} arguments makes its real call through it when the arity's dispatcher
	 * fits one segment: the copy has the same cases, so its branch profile -- one per
	 * method -- holds only the functions value tails reach, and a chain the JIT inlines
	 * hop by hop is not inlined with the cases of every function the program calls
	 * through values ({@code .kb/jvm-tail-bounce.md}). An arity routed past one segment
	 * keeps the shared dispatcher, whose copy would cost a program its largest
	 * dispatchers twice, and so does every arity at {@code --optimize=size}.
	 * @param arity the call's argument count
	 */
	static String valueTailDispatcherName(int arity) {
		return "_vtcd" + arity;
	}

	/**
	 * {@return the name of the value tail of {@code arity} arguments}
	 * @param arity the call's argument count
	 */
	static String valueTailName(int arity) {
		return "_vtc" + arity;
	}

	/**
	 * {@return the descriptor of a value tail -- or of the dispatcher it calls -- of
	 * {@code arity} arguments} The designator, the arguments, then the depth.
	 * @param arity the call's argument count
	 */
	static String valueTailDesc(int arity) {
		return JvmRuntimeBuilder.dispatcherDesc(arity, false);
	}

	/**
	 * {@return the name of the bounce of a value tail of {@code arity} arguments} The
	 * method that makes the array, for the dispatcher copy's entry check and a checking
	 * {@code _vtc<n>}: kept out of both so neither grows by the allocation.
	 * @param arity the call's argument count
	 */
	static String valueTailBounceName(int arity) {
		return "_vtcb" + arity;
	}

	/**
	 * {@return the descriptor of the bounce of a value tail of {@code arity} arguments}
	 * The designator and the arguments: the bounce carries no depth.
	 * @param arity the call's argument count
	 */
	static String valueTailBounceDesc(int arity) {
		return "(" + "Ljava/lang/Object;".repeat(arity + 1) + ")Ljava/lang/Object;";
	}

	/**
	 * {@return the slot of the method's depth, for a read of it} Every method that makes
	 * a value tail or hands a callee's bounce on has one ({@code Ctx.tailBounce}: a
	 * lambda, a {@code bounceVisible} defun, a continuation of either). A lambda whose
	 * body reads it nowhere is written without the parameter ({@code Ctx.depthRead}).
	 * @param ctx the method being emitted
	 */
	static int depthSlot(JvmLispCompiler.Ctx ctx) {
		if (ctx.depthSlot < 0) {
			throw new IllegalStateException("a value tail in a method without the value-tail depth");
		}
		ctx.depthRead = true;
		return ctx.depthSlot;
	}

	/**
	 * Emits the tail call through a value: the designator and each argument evaluate
	 * once, left to right, and {@code _vtc<n>} makes the call or answers its bounce --
	 * the value that reaches the method's result either way. The method's depth goes with
	 * them: {@code _vtc<n>} hands the callee one more.
	 * @param fnForm the designator, unevaluated -- its VALUE is what the dispatcher takes
	 * as the function, so the same value a {@code funcall} would pass
	 * @param args the call's arguments, the first {@code from} of them designators head
	 * room to skip
	 * @param from the index in {@code args} where the arguments start
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 */
	static void emitValueTail(LispVal fnForm, List<LispVal> args, int from, JvmLispCompiler.Ctx ctx, String className) {
		// The class writes its trampoline only when a body that may bounce survives the
		// shake: this one is such a body.
		ctx.bouncingBodies.add(ctx.body);
		int count = args.size() - from;
		// The arity joins the dispatchers' registry: the call and the trampoline's
		// re-entry go through _invoke_<count>, whose body exists only for a registered
		// arity.
		ctx.indirectCallArities.add(count);
		ctx.valueTailArities.add(count);
		JvmExprCompiler.compileExpr(fnForm, ctx, className);
		for (int i = 0; i < count; i++) {
			JvmExprCompiler.compileExpr(args.get(from + i), ctx, className);
		}
		ctx.body.iload(depthSlot(ctx));
		ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(className), ctx.cp.utf8Entry(valueTailName(count)),
				ctx.cp.utf8Entry(valueTailDesc(count))));
	}

	/**
	 * Emits a tail {@code apply} through a value: {@code _vtcv(designatorValue,
	 * argumentList, depth)}, the list as the apply would spread it. The call is the raw
	 * apply ({@link #APPLY_RAW_NAME}); its bounce is {@code Object[]{FALSE,
	 * designatorValue, argumentList}}, which the trampoline re-enters the raw apply with,
	 * so a chain of applies through values -- the Clojure front end's every call through
	 * a value -- keeps no frame per hop past the limit either.
	 * @param ctx the method being emitted, its tail mark on the apply
	 * @param fnSlot the local holding the designator's value
	 * @param listSlot the local holding the argument list
	 * @param className the class being generated
	 */
	static void emitSpreadValueTail(JvmLispCompiler.Ctx ctx, int fnSlot, int listSlot, String className) {
		ctx.bouncingBodies.add(ctx.body);
		ctx.spreadBounces[0] = true;
		ctx.body.aload(fnSlot);
		ctx.body.aload(listSlot);
		ctx.body.iload(depthSlot(ctx));
		ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(className), ctx.cp.utf8Entry(VALUE_TAIL_SPREAD_NAME),
				ctx.cp.utf8Entry(JvmRuntimeBuilder.dispatcherDesc(0, true))));
	}

	/**
	 * The body of {@code _vtc<n>(fn, a1..an, depth)}, or of the spread
	 * {@code _vtcv(fn, list, depth)}. The call's answer passes on unchecked: a bounce
	 * from deeper in the chain reaches the nearest frame that checks.
	 *
	 * <pre>
	 * return _vtcd_n(fn, a1..an, depth + 1);                  // the copy checks the depth
	 * if (depth &lt; LIMIT) return _invoke_n(fn, a1..an, depth + 1); // a routed arity
	 * return _vtcb_n(fn, a1..an);
	 * if (depth &lt; LIMIT) return _applyRaw(fn, list, depth + 1);  // the spread one
	 * return new Object[]{FALSE, fn, list};
	 * </pre>
	 *
	 * The first is a forwarder of six instructions, which Graal inlines while it parses
	 * the caller; with the check in it, Graal weighs it against its inlining budget like
	 * the copy, and a chain of three hops kept its last hop a call
	 * ({@code .kb/jvm-tail-bounce.md}).
	 * @param arity the argument count, ignored for the spread value tail
	 * @param spread whether to build {@code _vtcv}, which calls the raw apply
	 * @param ownDispatcher whether the arity has the dispatcher copy only value tails
	 * call ({@link #valueTailDispatcherName}), which the real call then goes through
	 * instead of {@code _invoke_n}; ignored for the spread value tail
	 * @param cp the class's constant pool
	 * @param thisClass the class carrying the dispatchers
	 * @return the method body
	 */
	static MethodCode valueTailBody(int arity, boolean spread, boolean ownDispatcher, ConstantPool cp,
			ClassEntry thisClass) {
		int params = spread ? 2 : arity + 1;
		int depth = params;
		String callee = spread ? APPLY_RAW_NAME
				: ownDispatcher ? valueTailDispatcherName(arity) : JvmRuntimeBuilder.dispatcherName(arity, false);
		var target = cp.methodRef(thisClass, cp.utf8Entry(callee),
				cp.utf8Entry(JvmRuntimeBuilder.dispatcherDesc(arity, spread)));
		MethodCode code = new MethodCode();
		MethodCode.Label bounce = code.newLabel();
		boolean checks = spread || !ownDispatcher;
		if (checks) {
			code.iload(depth);
			code.loadConstant(VALUE_TAIL_LIMIT);
			code.if_icmpge(bounce);
		}
		for (int i = 0; i < params; i++) {
			code.aload(i);
		}
		code.iload(depth);
		code.iconst_1();
		code.iadd();
		code.invokestatic(target);
		code.areturn();
		if (!checks) {
			return code;
		}
		code.labelBinding(bounce);
		if (spread) {
			emitBounceArray(code, cp, params, true);
		}
		else {
			for (int i = 0; i < params; i++) {
				code.aload(i);
			}
			code.invokestatic(cp.methodRef(thisClass, cp.utf8Entry(valueTailBounceName(arity)),
					cp.utf8Entry(valueTailBounceDesc(arity))));
		}
		code.areturn();
		return code;
	}

	/**
	 * The body of {@code _vtcb<n>(fn, a1..an)}: {@code new Object[]{TRUE, fn, a1..an}},
	 * the bounce of a value tail past the limit.
	 * @param arity the argument count
	 * @param cp the class's constant pool
	 * @return the method body
	 */
	static MethodCode valueTailBounceBody(int arity, ConstantPool cp) {
		MethodCode code = new MethodCode();
		emitBounceArray(code, cp, arity + 1, false);
		code.areturn();
		return code;
	}

	/**
	 * Emits the entry check of the dispatcher copy only value tails call
	 * ({@link #valueTailDispatcherName}): a depth past the limit answers the call's
	 * bounce ({@code _vtcb<n>}) instead of dispatching it. The depth it receives is the
	 * calling value tail's plus one, so this is {@code _vtc<n>}'s check, moved where a
	 * JIT inlining a chain finds it folded at every hop.
	 * @param code the copy's code, empty
	 * @param arity the copy's argument count
	 * @param cp the class's constant pool
	 * @param thisClass the class carrying the bounce
	 */
	static void emitCopyEntryCheck(MethodCode code, int arity, ConstantPool cp, ClassEntry thisClass) {
		MethodCode.Label within = code.newLabel();
		code.iload(arity + 1);
		code.loadConstant(VALUE_TAIL_LIMIT);
		code.if_icmple(within);
		for (int i = 0; i <= arity; i++) {
			code.aload(i);
		}
		code.invokestatic(cp.methodRef(thisClass, cp.utf8Entry(valueTailBounceName(arity)),
				cp.utf8Entry(valueTailBounceDesc(arity))));
		code.areturn();
		code.labelBinding(within);
	}

	/**
	 * Leaves {@code Object[]{marker, local0..}} on the stack: the bounce of the
	 * designator and arguments in the first {@code params} locals.
	 */
	private static void emitBounceArray(MethodCode code, ConstantPool cp, int params, boolean spread) {
		code.loadConstant(params + 1);
		code.anewarray(cp.classEntry("java/lang/Object"));
		code.dup();
		code.iconst_0();
		code.getstatic(cp.fieldRef(cp.classEntry("java/lang/Boolean"), cp.utf8Entry(spread ? "FALSE" : "TRUE"),
				cp.utf8Entry(MARKER_DESC)));
		code.aastore();
		for (int i = 0; i < params; i++) {
			code.dup();
			code.loadConstant(i + 1);
			code.aload(i);
			code.aastore();
		}
	}

	/**
	 * Emits the check that consumes a possibly-bounced result: the value just computed is
	 * replaced by the trampoline's answer, which is the final value when the check passes
	 * and the value itself when it does not.
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 */
	static void emitUnwrap(JvmLispCompiler.Ctx ctx, String className) {
		unwrapRaw(ctx.body, ctx.cp, ctx.cp.classEntry(className));
	}

	/**
	 * {@return whether the call {@code cons} may hand a bounce its callee answers on as
	 * this method's answer} It carries the tail mark, so its value is the method's result
	 * with nothing in between to run, and every caller of this method checks the result
	 * for a bounce ({@code Ctx.passesBounces}).
	 * @param cons the call form
	 * @param ctx the method being emitted
	 */
	static boolean passesThrough(LispCons cons, JvmLispCompiler.Ctx ctx) {
		return ctx.passesBounces && ctx.tailMark == cons && ctx.unwindScopes.isEmpty() && ctx.spillScopes.isEmpty();
	}

	/**
	 * Emits a direct call of {@code callee}, its physical arguments on the stack, and the
	 * check it owes its result. A callee that may answer a bounce takes the depth: this
	 * method's when the call is its tail and the bounce passes on
	 * ({@link #passesThrough}) -- a named tail call adds no value tail to the chain --
	 * and 0 otherwise, whose result is then checked here. A callee that never bounces
	 * takes neither.
	 * @param callee the function called
	 * @param cons the call form, or null for a call no form stands for (never a tail)
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 */
	static void emitDirectCall(JvmLispCompiler.FunctionInfo callee, @Nullable LispCons cons, JvmLispCompiler.Ctx ctx,
			String className) {
		boolean passes = cons != null && passesThrough(cons, ctx);
		if (callee.bounceVisible()) {
			if (passes) {
				ctx.body.iload(depthSlot(ctx));
			}
			else {
				ctx.body.iconst_0();
			}
		}
		ctx.body.invokestatic(callee.methodref());
		if (callee.bounceVisible() && !passes) {
			emitUnwrap(ctx, className);
		}
	}

	/**
	 * The unwrap check, in the slot-free form the runtime builders emit: the value on the
	 * stack is replaced by the trampoline's answer, or left as it is. One call to the
	 * shared {@code _unw} ({@link #unwBody}): every dispatcher call site carries one, and
	 * the check spelled out inline cost ~22 bytes a site -- enough to push the ci-spec
	 * corpus's largest top-level form past the 64 KB method limit. {@code _unw} is small
	 * enough for HotSpot to inline at every site, and in a class where nothing kept can
	 * bounce it is the identity.
	 * @param a the code being emitted
	 * @param cp the class's constant pool
	 * @param thisClass the class carrying {@code _unw} and {@code _tramp}
	 */
	static void unwrapRaw(MethodCode a, ConstantPool cp, ClassEntry thisClass) {
		a.invokestatic(cp.methodRef(thisClass, cp.utf8Entry(UNW_NAME), cp.utf8Entry(UNW_DESC)));
	}

	/** The shared unwrap check's name. */
	static final String UNW_NAME = "_unw";

	/** The shared unwrap check's descriptor. */
	static final String UNW_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * Writes the body of {@code _unw(Object)Object} into {@code code}, empty until the
	 * class is assembled: a bounce array -- an {@code Object[]} whose slot 0 is the
	 * marker -- goes to {@code _tramp}, anything else returns as it is. When no method
	 * the class keeps can bounce ({@code live} false) it answers its argument and names
	 * no {@code _tramp}, so the shake drops the trampoline and whatever it alone reached
	 * -- every dispatcher arity it re-enters, with the closures only those name.
	 * @param code the body, empty
	 * @param cp the class's constant pool
	 * @param thisClass the class carrying {@code _tramp}
	 * @param live whether a kept method can bounce
	 */
	static void unwBody(MethodCode code, ConstantPool cp, ClassEntry thisClass, boolean live) {
		if (!live) {
			code.aload(0);
			code.areturn();
			return;
		}
		ClassEntry objectArray = cp.classEntry("[Ljava/lang/Object;");
		MethodCode.Label plain = code.newLabel();
		code.aload(0);
		code.instanceOf(objectArray);
		code.ifeq(plain);
		code.aload(0);
		code.checkcast(objectArray);
		code.arraylength();
		code.ifeq(plain);
		code.aload(0);
		code.checkcast(objectArray);
		code.iconst_0();
		code.aaload();
		code.instanceOf(cp.classEntry("java/lang/Boolean"));
		code.ifeq(plain);
		code.aload(0);
		code.invokestatic(cp.methodRef(thisClass, cp.utf8Entry("_tramp"), cp.utf8Entry(UNW_DESC)));
		code.areturn();
		code.labelBinding(plain);
		code.aload(0);
		code.areturn();
	}

	/**
	 * The body of the shared trampoline loop {@code _tramp}: takes the result an unwrap
	 * check found to be a bounce array, and while it is one, re-enters the shared
	 * dispatcher of the array's argument count (not the copy a value tail's real call
	 * goes through) with its designator and arguments -- or, for an apply's bounce, the
	 * raw apply with its argument list -- so the call the bounce deferred runs HERE, in
	 * this frame, and the next bounce comes back to this loop instead of stacking a
	 * frame. A real value returns as the answer. Each re-entry passes the limit as the
	 * depth, so the chain it drives -- one that already filled the limit with real calls
	 * -- keeps bouncing rather than refilling it every round.
	 * @param arities the registered dispatch arities, one {@code _invoke_<n>} each; a
	 * count outside them cannot arise (every bounce site registers its own)
	 * @param spread whether an apply bounced anywhere, so the raw apply is re-entered too
	 * @param cp the class's constant pool
	 * @param thisClass the class whose dispatchers re-enter
	 * @return the method body
	 */
	static MethodCode trampBody(Set<Integer> arities, boolean spread, ConstantPool cp, ClassEntry thisClass) {
		ClassEntry objectArray = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry booleanClass = cp.classEntry("java/lang/Boolean");
		List<Integer> sorted = arities.stream().sorted().toList();
		MethodCode code = new MethodCode();
		MethodCode.Label top = code.newLabel();
		code.labelBinding(top);
		MethodCode.Label plain = code.newLabel();
		code.aload(0);
		code.instanceOf(objectArray);
		code.ifeq(plain);
		code.aload(0);
		code.checkcast(objectArray);
		code.astore(1);
		code.aload(1);
		code.arraylength();
		code.loadConstant(2);
		code.if_icmplt(plain);
		code.aload(1);
		code.iconst_0();
		code.aaload();
		code.instanceOf(booleanClass);
		code.ifeq(plain);
		if (spread) {
			MethodCode.Label call = code.newLabel();
			code.aload(1);
			code.iconst_0();
			code.aaload();
			code.getstatic(cp.fieldRef(booleanClass, cp.utf8Entry("FALSE"), cp.utf8Entry(MARKER_DESC)));
			code.if_acmpne(call);
			code.aload(1);
			code.iconst_1();
			code.aaload();
			code.aload(1);
			code.iconst_2();
			code.aaload();
			code.loadConstant(VALUE_TAIL_LIMIT);
			code.invokestatic(cp.methodRef(thisClass, cp.utf8Entry(APPLY_RAW_NAME),
					cp.utf8Entry(JvmRuntimeBuilder.dispatcherDesc(0, true))));
			code.astore(0);
			code.goto_(top);
			code.labelBinding(call);
		}
		for (int k = 0; k < sorted.size(); k++) {
			int arity = sorted.get(k);
			boolean more = k + 1 < sorted.size();
			MethodCode.Label next = more ? code.newLabel() : plain;
			code.aload(1);
			code.arraylength();
			code.loadConstant(arity + 2);
			code.if_icmpne(next);
			code.aload(1);
			code.iconst_1();
			code.aaload();
			for (int j = 0; j < arity; j++) {
				code.aload(1);
				code.loadConstant(j + 2);
				code.aaload();
			}
			code.loadConstant(VALUE_TAIL_LIMIT);
			code.invokestatic(cp.methodRef(thisClass, cp.utf8Entry(JvmRuntimeBuilder.dispatcherName(arity, false)),
					cp.utf8Entry(JvmRuntimeBuilder.dispatcherDesc(arity, false))));
			code.astore(0);
			code.goto_(top);
			if (more) {
				code.labelBinding(next);
			}
		}
		code.labelBinding(plain);
		code.aload(0);
		code.areturn();
		return code;
	}

}
