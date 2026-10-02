package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import java.lang.classfile.constantpool.ClassEntry;

import am.ik.jvm.MethodCode;
import am.ik.jvm.ConstantPool;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The compile-time half of the trampoline that makes a tail call through a procedure
 * value proper on the JVM.
 *
 * <p>
 * A tail call whose target the compiler cannot name -- {@code (funcall f ...)} over a
 * variable, an argument, any computed designator, and the general indirect
 * {@code (expr arg...)} -- is emitted as a BOUNCE: the arguments are evaluated, the
 * designator and the arguments are packed into one {@code Object[]{marker,
 * designatorValue, arg...}} and that array is the method's result. Every caller that
 * receives a compiled function's result checks for the array and, on the bounce shape,
 * drives the call it names in ITS OWN frame -- the trampoline loop of the shared helper
 * {@code _tramp}, which re-enters the per-arity dispatchers and loops while the answer is
 * again a bounce. One frame per tail chain plus the dispatcher frame, so a state machine
 * calling through values runs on constant stack, while every call the existing analyses
 * already prove direct (the named-let/do loops, the self and mutual tail-call groups)
 * keeps its direct {@code invokestatic} and pays nothing.
 *
 * <p>
 * The bounce is emitted only where the value flows to the method's result unchanged --
 * the mark {@link JvmBodyOutliner} lays on the final spine item, re-laid by {@code if},
 * {@code progn} and the plain {@code let}/{@code let*} arms -- so no enclosing construct
 * consumes the value. A dynamic-binding restore between the call and the method's exit is
 * a spine {@code Cleanup} with runtime code: the tail then keeps a real call, because the
 * restore must run inside the call's extent.
 */
final class JvmTailBounce {

	private JvmTailBounce() {
	}

	/**
	 * Slot 0 of a bounce array: a {@code java.lang.Boolean}, which no compiled function
	 * value carries in slot 0 (a function value's slot 0 is its {@code Integer} funcId,
	 * the one thing the dispatchers read).
	 */
	static final String MARKER_DESC = "Ljava/lang/Boolean;";

	/**
	 * {@return whether the true tail reachable from this form may end in a call through a
	 * procedure value} The chain passes the last body form of {@code progn} / {@code let}
	 * / {@code let*} and either arm of {@code if} -- exactly the forms the emitter
	 * re-marks; every other compound leaf may itself be the call, so the answer is yes
	 * and the callers check.
	 */
	static boolean bounceVisible(@Nullable LispVal form, Predicate<String> defun) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		String head = cons.car() instanceof LispSymbol sym ? sym.name() : null;
		if (head == null) {
			// A general indirect call: the head is computed, the call goes through the
			// dispatcher, the tail bounces.
			return true;
		}
		return switch (head) {
			case "PROGN" -> {
				List<LispVal> parts = cons.toList();
				yield !parts.isEmpty() && bounceVisible(parts.get(parts.size() - 1), defun);
			}
			case "IF" -> {
				List<LispVal> parts = cons.toList();
				yield parts.size() > 2 && (bounceVisible(parts.get(2), defun)
						|| (parts.size() > 3 && bounceVisible(parts.get(3), defun)));
			}
			case "LET", "LET*" -> {
				List<LispVal> parts = cons.toList();
				yield parts.size() > 2 && bounceVisible(parts.get(parts.size() - 1), defun);
			}
			// The funcall the mark reaches: the emitter compiles it into the bounce.
			case "FUNCALL" -> true;
			// A head the function registry names is a DIRECT call: its maybe-bounced
			// result is unwrapped right here, so the value this tail answers is clean.
			// A quoted datum never calls. ANY other symbol head lowers through a
			// variable -- a local function's Lisp-2 call -- and that call bounces.
			default -> !defun.test(head) && !"QUOTE".equals(head);
		};
	}

	/**
	 * {@return whether a body's true tail ends in a call through a procedure value}
	 */
	static boolean bounceVisibleBody(List<LispVal> body, Predicate<String> defun) {
		return !body.isEmpty() && bounceVisible(body.get(body.size() - 1), defun);
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
	 * Emits the check that consumes a possibly-bounced result: the value just computed is
	 * replaced by the trampoline's answer, which is the final value when the check passes
	 * and the value itself when it does not. A no-op when the class carries no bounce at
	 * all, which is every program whose tails the direct-call proofs already cover --
	 * those compile byte-identically to the pre-trampoline emitter.
	 * @param ctx the method, whose next local takes the value for the test
	 */
	static void emitUnwrap(JvmLispCompiler.Ctx ctx, String className) {
		unwrapRaw(ctx.body, ctx.cp, ctx.cp.classEntry(className), ctx.objectArrayClass, ctx.hasTr);
	}

	/**
	 * The unwrap check, in the slot-free form the runtime builders emit: the value on the
	 * stack is replaced by the trampoline's answer, or left as it is. Pure stack work, so
	 * a builder with a fixed local layout needs no slot of its own.
	 * @param a the code being emitted
	 * @param cp the class's constant pool
	 * @param thisClass the class carrying {@code _tramp}
	 * @param objectArrayClass {@code Object[]}
	 * @param hasTr whether the class has a trampoline at all; without one this emits
	 * nothing
	 */
	static void unwrapRaw(MethodCode a, ConstantPool cp, ClassEntry thisClass, ClassEntry objectArrayClass,
			boolean hasTr) {
		if (!hasTr || System.getenv("RL_NO_UNWRAP") != null) {
			return;
		}
		MethodCode.Label plain = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.dup();
		a.instanceOf(objectArrayClass);
		a.ifeq(plain);
		a.dup();
		a.checkcast(objectArrayClass);
		a.iconst_0();
		a.aaload();
		a.instanceOf(cp.classEntry("java/lang/Boolean"));
		a.ifeq(plain);
		a.invokestatic(cp.methodRef(thisClass, cp.utf8Entry("_tramp"),
				cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/Object;")));
		a.goto_(done);
		a.labelBinding(plain);
		a.labelBinding(done);
	}

	/**
	 * The body of the shared trampoline loop {@code _tramp}: takes the result an unwrap
	 * check found to be a bounce array, and while it is one, re-enters the dispatcher of
	 * the array's argument count with its designator and arguments -- the call the bounce
	 * deferred runs HERE, in this frame, and the next bounce comes back to this loop
	 * instead of stacking a frame. A real value returns as the answer.
	 * @param arities the registered dispatch arities, one {@code _invoke_<n>} each; a
	 * count outside them cannot arise (every bounce site registers its own)
	 * @param cp the class's constant pool
	 * @param thisClass the class whose dispatchers re-enter
	 * @return the method body
	 */
	static MethodCode trampBody(Set<Integer> arities, ConstantPool cp, ClassEntry thisClass) {
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
