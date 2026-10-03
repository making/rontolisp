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
 * {@code (expr arg...)} -- in a defun's or a lambda's body is emitted as a BOUNCE: the
 * arguments are evaluated, the designator and the arguments are packed into one
 * {@code Object[]{marker, designatorValue, arg...}} and that array is the method's
 * result. Every caller that receives a compiled function's result checks for the array
 * and, on the bounce shape, drives the call it names in ITS OWN frame -- the trampoline
 * loop of the shared helper {@code _tramp}, which re-enters the per-arity dispatchers and
 * loops while the answer is again a bounce. One frame per tail chain plus the dispatcher
 * frame, so a state machine calling through values runs on constant stack, while every
 * call the existing analyses already prove direct (the named-let/do loops, the self and
 * mutual tail-call groups) keeps its direct {@code invokestatic}.
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
 * The bounce is emitted only where the value flows to the method's result unchanged --
 * the mark {@link JvmBodyOutliner} lays on the final spine item, re-laid by every form
 * that hands a sub-form's value on ({@link JvmSelfTailCall}) -- so no enclosing construct
 * consumes the value. A dynamic-binding restore between the call and the method's exit is
 * a spine {@code Cleanup} with runtime code: the tail then keeps a real call, because the
 * restore must run inside the call's extent.
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
	 * The raw apply: {@code _apply}'s body without its answer checked, so the trampoline
	 * can re-enter it for an apply's bounce and loop on what it answers
	 * ({@link #emitSpreadBounce}).
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
			// ((lambda ...) args) compiles inline; any other computed head bounces.
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
			// function of that arity answers -- the emitter bounces.
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
			// the emitter bounces with the argument list unspread (emitSpreadBounce).
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
	 * Emits the tail call as a bounce: the designator and each argument evaluate once,
	 * left to right, into temporaries, and the marker array is the value that reaches the
	 * method's result.
	 * @param fnForm the designator, unevaluated -- its VALUE is what the dispatcher takes
	 * as the function, so the same value a {@code funcall} would pass
	 * @param args the call's arguments, the first {@code from} of them designators head
	 * room to skip
	 * @param from the index in {@code args} where the arguments start
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 */
	static void emitBounce(LispVal fnForm, List<LispVal> args, int from, JvmLispCompiler.Ctx ctx, String className) {
		// The class writes its trampoline only when a body that bounces survives the
		// shake: this one is such a body.
		ctx.bouncingBodies.add(ctx.body);
		int count = args.size() - from;
		// The arity joins the dispatchers' registry: the trampoline re-enters through
		// _invoke_<count>, whose body exists only for a registered arity.
		ctx.indirectCallArities.add(count);
		int fnSlot = ctx.allocTemp();
		JvmExprCompiler.compileExpr(fnForm, ctx, className);
		ctx.body.astore(fnSlot);
		int[] argSlots = new int[count];
		for (int i = 0; i < count; i++) {
			argSlots[i] = ctx.allocTemp();
			JvmExprCompiler.compileExpr(args.get(from + i), ctx, className);
			ctx.body.astore(argSlots[i]);
		}
		ctx.body.loadConstant(count + 2);
		ctx.body.anewarray(ctx.objectClass);
		int arr = ctx.allocTemp();
		ctx.body.astore(arr);
		ctx.body.aload(arr);
		ctx.body.iconst_0();
		ctx.body.getstatic(ctx.booleanMarker());
		ctx.body.aastore();
		ctx.body.aload(arr);
		ctx.body.iconst_1();
		ctx.body.aload(fnSlot);
		ctx.body.aastore();
		for (int i = 0; i < count; i++) {
			ctx.body.aload(arr);
			ctx.body.loadConstant(i + 2);
			ctx.body.aload(argSlots[i]);
			ctx.body.aastore();
		}
		// The array is the bounce: aastore leaves nothing, so load it as the value.
		ctx.body.aload(arr);
	}

	/**
	 * Emits a tail {@code apply} through a value as a bounce: {@code Object[]{FALSE,
	 * designatorValue, argumentList}}, the list as the apply would spread it. The
	 * trampoline re-enters the raw apply with it ({@link #APPLY_RAW_NAME}), so a chain of
	 * applies through values -- the Clojure front end's every call through a value --
	 * keeps no frame per hop.
	 * @param ctx the method being emitted, its tail mark on the apply
	 * @param fnSlot the local holding the designator's value
	 * @param listSlot the local holding the argument list
	 */
	static void emitSpreadBounce(JvmLispCompiler.Ctx ctx, int fnSlot, int listSlot) {
		ctx.bouncingBodies.add(ctx.body);
		ctx.spreadBounces[0] = true;
		ctx.body.loadConstant(3);
		ctx.body.anewarray(ctx.objectClass);
		ctx.body.dup();
		ctx.body.iconst_0();
		ctx.body.getstatic(ctx.cp.fieldRef(ctx.booleanClass(), "FALSE", MARKER_DESC));
		ctx.body.aastore();
		ctx.body.dup();
		ctx.body.iconst_1();
		ctx.body.aload(fnSlot);
		ctx.body.aastore();
		ctx.body.dup();
		ctx.body.iconst_2();
		ctx.body.aload(listSlot);
		ctx.body.aastore();
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
	 * check found to be a bounce array, and while it is one, re-enters the dispatcher of
	 * the array's argument count with its designator and arguments -- or, for an apply's
	 * bounce, the raw apply with its argument list -- so the call the bounce deferred
	 * runs HERE, in this frame, and the next bounce comes back to this loop instead of
	 * stacking a frame. A real value returns as the answer.
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
		code.aload(0);
		code.areturn();
		return code;
	}

}
