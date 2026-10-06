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
import java.lang.classfile.constantpool.FieldRefEntry;

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
 * answer passed on unchecked, while the value-tail frames on the stack of the thread that
 * owns the count stay under {@link #VALUE_TAIL_LIMIT}; past it, and on any other thread,
 * it answers a BOUNCE instead: one {@code Object[]{marker, designatorValue, arg...}}.
 * Every caller that receives a compiled function's result checks for the array and, on
 * the bounce shape, drives the call it names in ITS OWN frame -- the trampoline loop of
 * the shared helper {@code _tramp}, which re-enters the per-arity dispatchers and loops
 * while the answer is again a bounce, the owner's count held at the limit meanwhile so
 * the chain it drives keeps bouncing. A shallow chain -- an adapter, a composition, a
 * reduce step -- is a plain call the JIT can inline; a deep one unwinds to the nearest
 * checking frame once per limit's worth of frames and then runs in that frame's
 * trampoline, so a state machine calling through values runs on constant stack, while
 * every call the existing analyses already prove direct (the named-let/do loops, the self
 * and mutual tail-call groups) keeps its direct {@code invokestatic}.
 *
 * <p>
 * The real call goes through {@code _vtcd<n>}, a copy of the arity's dispatcher that only
 * value tails call, while the arity's dispatcher fits one segment
 * ({@link #valueTailDispatcherName}). A dispatcher's branch profile is one per method, so
 * a JIT inlining a chain through the shared dispatcher inlines it again at every hop with
 * every target the program's indirect calls reach; through the copy, a hop sees only the
 * targets value tails reach. A routed dispatcher keeps the shared one: the copy would
 * double the largest dispatchers. The copy spends bytes on speed, so
 * {@code --optimize=size} declines it.
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
	 * How many value-tail frames the owner thread keeps on its stack before
	 * {@code _vtc<n>} bounces instead of calling: 64 real calls then a bounce, and
	 * {@code _tramp} drives the rest of the chain. A chain shorter than this never
	 * allocates; a deep one bounces once per trampoline entry. The bound is the thread's
	 * whole stack's, nested non-tail calls included, so it costs any program at most this
	 * many extra frame groups. {@code -Drontolisp.jvm.value-tail-limit=0} at compile time
	 * makes every value tail bounce, for measuring what the real calls buy.
	 */
	static final int VALUE_TAIL_LIMIT = Math.clamp(Integer.getInteger("rontolisp.jvm.value-tail-limit", 64), 0,
			Short.MAX_VALUE);

	/** The static {@code Thread} whose value-tail frames {@link #DEPTH_FIELD} counts. */
	static final String OWNER_FIELD = "_vtcOwner";

	static final String OWNER_DESC = "Ljava/lang/Thread;";

	/**
	 * The static {@code int} count of the owner's value-tail frames, or the limit while
	 * its trampoline drives a chain.
	 */
	static final String DEPTH_FIELD = "_vtcDepth";

	/** The synchronized claim of the count by the first thread to make a value tail. */
	static final String CLAIM_NAME = "_vtcClaim";

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
	 * {@code arity} arguments}
	 * @param arity the call's argument count
	 */
	static String valueTailDesc(int arity) {
		return "(" + "Ljava/lang/Object;".repeat(arity + 1) + ")Ljava/lang/Object;";
	}

	/**
	 * Emits the tail call through a value: the designator and each argument evaluate
	 * once, left to right, and {@code _vtc<n>} makes the call or answers its bounce --
	 * the value that reaches the method's result either way.
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
		ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(className), ctx.cp.utf8Entry(valueTailName(count)),
				ctx.cp.utf8Entry(valueTailDesc(count))));
	}

	/**
	 * Emits a tail {@code apply} through a value: {@code _vtcv(designatorValue,
	 * argumentList)}, the list as the apply would spread it. The call is the raw apply
	 * ({@link #APPLY_RAW_NAME}); its bounce is {@code Object[]{FALSE, designatorValue,
	 * argumentList}}, which the trampoline re-enters the raw apply with, so a chain of
	 * applies through values -- the Clojure front end's every call through a value --
	 * keeps no frame per hop past the limit either.
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
		ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(className), ctx.cp.utf8Entry(VALUE_TAIL_SPREAD_NAME),
				ctx.cp.utf8Entry(valueTailDesc(1))));
	}

	/**
	 * The body of {@code _vtc<n>(fn, a1..an)}, or of the spread {@code _vtcv(fn, list)}.
	 * The count is one static field only the owner thread writes, so no other thread can
	 * make it drift: another thread reads it at most, and bounces whatever it reads. The
	 * owner restores it on every exit, a non-local one included, so a chain left by a
	 * {@code throw} leaves no count behind. The call's answer passes on unchecked: a
	 * bounce from deeper in the chain reaches the nearest frame that checks.
	 *
	 * <pre>
	 * int d = _vtcDepth;
	 * if (d &lt; LIMIT) {
	 *   if (Thread.currentThread() == _vtcOwner) {
	 *     _vtcDepth = d + 1;
	 *     try { r = _vtcd_n(fn, a1..an); } catch (Throwable t) { _vtcDepth = d; throw t; }
	 *     _vtcDepth = d;
	 *     return r;
	 *   }
	 *   if (_vtcOwner == null) _vtcClaim();
	 * }
	 * return new Object[]{TRUE, fn, a1..an};
	 * </pre>
	 * @param arity the argument count, ignored for the spread value tail
	 * @param spread whether to build {@code _vtcv}, which calls the raw apply
	 * @param ownDispatcher whether the arity has the dispatcher copy only value tails
	 * call ({@link #valueTailDispatcherName}), which the real call then goes through
	 * instead of {@code _invoke_n}; ignored for the spread value tail
	 * @param cp the class's constant pool
	 * @param thisClass the class carrying the dispatchers and the count
	 * @return the method body
	 */
	static MethodCode valueTailBody(int arity, boolean spread, boolean ownDispatcher, ConstantPool cp,
			ClassEntry thisClass) {
		int params = spread ? 2 : arity + 1;
		int saved = params;
		var depth = cp.fieldRef(thisClass, cp.utf8Entry(DEPTH_FIELD), cp.utf8Entry("I"));
		var owner = cp.fieldRef(thisClass, cp.utf8Entry(OWNER_FIELD), cp.utf8Entry(OWNER_DESC));
		String callee = spread ? APPLY_RAW_NAME
				: ownDispatcher ? valueTailDispatcherName(arity) : JvmRuntimeBuilder.dispatcherName(arity, false);
		var target = cp.methodRef(thisClass, cp.utf8Entry(callee), cp.utf8Entry(valueTailDesc(params - 1)));
		MethodCode code = new MethodCode();
		MethodCode.Label bounce = code.newLabel();
		MethodCode.Label notOwner = code.newLabel();
		code.getstatic(depth);
		code.istore(saved);
		code.iload(saved);
		code.loadConstant(VALUE_TAIL_LIMIT);
		code.if_icmpge(bounce);
		emitCurrentThread(code, cp);
		code.getstatic(owner);
		code.if_acmpne(notOwner);
		code.iload(saved);
		code.iconst_1();
		code.iadd();
		code.putstatic(depth);
		MethodCode.Label tryStart = code.newBoundLabel();
		for (int i = 0; i < params; i++) {
			code.aload(i);
		}
		code.invokestatic(target);
		MethodCode.Label tryEnd = code.newBoundLabel();
		code.iload(saved);
		code.putstatic(depth);
		code.areturn();
		MethodCode.Label restore = code.newBoundLabel();
		code.iload(saved);
		code.putstatic(depth);
		code.athrow();
		code.exceptionCatch(tryStart, tryEnd, restore, null);
		code.labelBinding(notOwner);
		code.getstatic(owner);
		code.ifnonnull(bounce);
		code.invokestatic(cp.methodRef(thisClass, cp.utf8Entry(CLAIM_NAME), cp.utf8Entry("()V")));
		code.labelBinding(bounce);
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
		code.areturn();
		return code;
	}

	/**
	 * The body of the synchronized {@code _vtcClaim()}: the calling thread owns the count
	 * when no thread does yet. Claimed once and kept: the thread that runs a program's
	 * top level makes its first value tail first, and every other thread bounces.
	 * @param cp the class's constant pool
	 * @param thisClass the class carrying the count
	 * @return the method body
	 */
	static MethodCode claimBody(ConstantPool cp, ClassEntry thisClass) {
		var owner = cp.fieldRef(thisClass, cp.utf8Entry(OWNER_FIELD), cp.utf8Entry(OWNER_DESC));
		MethodCode code = new MethodCode();
		MethodCode.Label done = code.newLabel();
		code.getstatic(owner);
		code.ifnonnull(done);
		emitCurrentThread(code, cp);
		code.putstatic(owner);
		code.labelBinding(done);
		code.return_();
		return code;
	}

	private static void emitCurrentThread(MethodCode code, ConstantPool cp) {
		code.invokestatic(cp.methodRef(cp.classEntry("java/lang/Thread"), "currentThread", "()Ljava/lang/Thread;"));
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
	 * Emits the check a direct call of {@code callee} owes its result, the call just
	 * emitted: none when the callee never answers a bounce, or when the call is this
	 * method's tail and the bounce passes on ({@link #passesThrough}).
	 * @param callee the function called
	 * @param cons the call form, or null for a call no form stands for (never a tail)
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 */
	static void emitDirectCallUnwrap(JvmLispCompiler.FunctionInfo callee, @Nullable LispCons cons,
			JvmLispCompiler.Ctx ctx, String className) {
		if (callee.bounceVisible() && (cons == null || !passesThrough(cons, ctx))) {
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
	 * frame. A real value returns as the answer. On the thread that owns the value-tail
	 * count the loop holds the count at the limit while it runs, so the chain it drives
	 * -- one that already filled the limit with real calls -- keeps bouncing rather than
	 * refilling it every round, and restores the count on every exit.
	 * @param arities the registered dispatch arities, one {@code _invoke_<n>} each; a
	 * count outside them cannot arise (every bounce site registers its own)
	 * @param spread whether an apply bounced anywhere, so the raw apply is re-entered too
	 * @param pins whether the class has value tails, so the owner's count is held
	 * @param cp the class's constant pool
	 * @param thisClass the class whose dispatchers re-enter
	 * @return the method body
	 */
	static MethodCode trampBody(Set<Integer> arities, boolean spread, boolean pins, ConstantPool cp,
			ClassEntry thisClass) {
		ClassEntry objectArray = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry booleanClass = cp.classEntry("java/lang/Boolean");
		List<Integer> sorted = arities.stream().sorted().toList();
		MethodCode code = new MethodCode();
		// The owner's count, saved in local 2 while the loop holds it at the limit; -1 on
		// any other thread, whose count this is not. A class without value tails mints
		// none of these entries: its pool, and every ldc width the size budgets read,
		// stays as it was.
		int saved = 2;
		@Nullable FieldRefEntry depth = null;
		MethodCode.@Nullable Label pinned = null;
		if (pins) {
			depth = cp.fieldRef(thisClass, cp.utf8Entry(DEPTH_FIELD), cp.utf8Entry("I"));
			MethodCode.Label notOwner = code.newLabel();
			code.iconst_m1();
			code.istore(saved);
			emitCurrentThread(code, cp);
			code.getstatic(cp.fieldRef(thisClass, cp.utf8Entry(OWNER_FIELD), cp.utf8Entry(OWNER_DESC)));
			code.if_acmpne(notOwner);
			code.getstatic(depth);
			code.istore(saved);
			code.loadConstant(VALUE_TAIL_LIMIT);
			code.putstatic(depth);
			code.labelBinding(notOwner);
			pinned = code.newBoundLabel();
		}
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
			code.invokestatic(cp.methodRef(thisClass, cp.utf8Entry(APPLY_RAW_NAME),
					cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
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
			code.invokestatic(cp.methodRef(thisClass, cp.utf8Entry("_invoke_" + arity),
					cp.utf8Entry("(" + "Ljava/lang/Object;".repeat(arity + 1) + ")Ljava/lang/Object;")));
			code.astore(0);
			code.goto_(top);
			if (more) {
				code.labelBinding(next);
			}
		}
		code.labelBinding(plain);
		if (pinned != null && depth != null) {
			// The count back to what it was, on the way out and on a non-local exit.
			MethodCode.Label unpinned = code.newBoundLabel();
			MethodCode.Label done = code.newLabel();
			code.iload(saved);
			code.iflt(done);
			code.iload(saved);
			code.putstatic(depth);
			code.labelBinding(done);
			code.aload(0);
			code.areturn();
			MethodCode.Label restore = code.newBoundLabel();
			MethodCode.Label rethrow = code.newLabel();
			code.iload(saved);
			code.iflt(rethrow);
			code.iload(saved);
			code.putstatic(depth);
			code.labelBinding(rethrow);
			code.athrow();
			code.exceptionCatch(pinned, unpinned, restore, null);
			return code;
		}
		code.aload(0);
		code.areturn();
		return code;
	}

}
