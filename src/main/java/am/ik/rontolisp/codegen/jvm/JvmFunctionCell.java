package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

import org.jspecify.annotations.Nullable;

/**
 * The cell of a {@code notinline} function in a linked class -- a JVM host or one of its
 * fragments ({@link JvmLinkage}): a static field, null until a redefinition stores the
 * function every call of the name then reaches
 * ({@code (rontolisp::%run-time-redefine 'name fn)}, {@link JvmRunTimeLinkCompiler}). A
 * call of the function by name goes through the class's named entry for it, which calls
 * the function's own method while the cell is null and applies the cell's function once
 * it is set; {@code #'name} answers the cell's function once it is set; a self or tail
 * group jump to it takes the call instead once it is set. A function VALUE taken before
 * the redefinition keeps calling the method it was made of, as a Clojure fn object keeps
 * its code ({@code .kb/jvm-run-time-lowering.md}, "Redefinition").
 *
 * <p>
 * Common Lisp's {@code notinline} is the declaration that a call must reach the current
 * global definition (CLHS 3.2.2.3); where a Clojure program lowers as it runs on the JVM,
 * its lowering declares so every function a later redefinition may re-point. An ordinary
 * class binds every call when it is compiled, the declaration or not.
 *
 * <p>
 * The same cell serves a function a write by a run-time name can reach in an ordinary
 * class ({@code compiler.RedefinedDefuns}), where it holds the name's binding in
 * {@code _fenv} once the program has made one ({@link JvmFunctionNamespace}), whose value
 * cell a later write changes and whose nil is {@code fmakunbound}'s tombstone.
 *
 * @param field the cell, the field of the class declaring the function (a fragment's
 * reference to a class before it is linked there, {@code ConstantPool#linkFields})
 * @param named this class's named entry, {@link JvmLinkage#namedEntry}, of the function's
 * descriptor
 * @param tailNamed the entry a call takes as the tail of a method whose answer reaches a
 * check for a bounce, {@link #tailEntryBody}, where the cell holds the name's
 * {@code _fenv} binding; null where it holds the function itself
 */
record JvmFunctionCell(FieldRefEntry field, MethodRefEntry named, @Nullable MethodRefEntry tailNamed) {

	/**
	 * {@return whether the cell holds the name's {@code _fenv} binding, not the function
	 * itself}
	 */
	boolean binding() {
		return this.tailNamed != null;
	}

	/**
	 * The names a program declares {@code notinline} at its top level
	 * ({@code (declaim (notinline name...))}, inside a top-level {@code progn} too).
	 * @param program the top-level forms
	 * @return the names, in order
	 */
	static Set<String> notinlineNames(List<LispVal> program) {
		Set<String> names = new LinkedHashSet<>();
		Deque<LispVal> pending = new ArrayDeque<>(program);
		while (!pending.isEmpty()) {
			if (!(pending.pop() instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !cons.isProperList()) {
				continue;
			}
			List<LispVal> items = cons.toList();
			String op = member(head.name());
			if (LispNames.PROGN.equals(op)) {
				for (int i = items.size() - 1; i >= 1; i--) {
					pending.push(items.get(i));
				}
			}
			else if (LispNames.DECLAIM.equals(op)) {
				for (LispVal spec : items.subList(1, items.size())) {
					if (spec instanceof LispCons declaration && declaration.car() instanceof LispSymbol kind
							&& declaration.isProperList() && "NOTINLINE".equals(member(kind.name()))) {
						List<LispVal> named = declaration.toList();
						for (LispVal name : named.subList(1, named.size())) {
							if (name instanceof LispSymbol sym) {
								names.add(sym.name());
							}
						}
					}
				}
			}
		}
		return names;
	}

	/** The package-stripped member of a possibly qualified name. */
	private static String member(String name) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		return qualified == null ? name : qualified.member();
	}

	/**
	 * The body of a named entry, {@code fi}'s descriptor: the function's own method over
	 * the arguments while the cell is null, else the cell's function applied to the
	 * arguments as a call passes them -- the required ones, the optionals passed (an
	 * UNSUPPLIED one ends them) and the rest list -- through {@code _apply}, which a
	 * linked class always carries and which answers a value, never a bounce.
	 *
	 * <p>
	 * A function whose tail calls a function with a cell may answer a bounce once the
	 * program has written a name ({@link #tailEntryBody}), so where its callers check for
	 * none this entry does, while {@code _fenv} holds anything: one read per call until
	 * then.
	 * @param fi the function, whose cell this is
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param unsupplied the class's UNSUPPLIED marker
	 * @param mayBounce whether the function's method hands on a replaced function's
	 * bounce
	 * @return the body
	 */
	MethodCode namedEntryBody(JvmLispCompiler.FunctionInfo fi, ConstantPool cp, ClassEntry thisClass,
			JvmUnsupplied unsupplied, boolean mayBounce) {
		MethodCode code = new MethodCode();
		MethodCode.Label redefined = code.newLabel();
		emitOwnMethodCall(code, fi, redefined);
		if (mayBounce && !fi.bounceVisible()) {
			MethodCode.Label nothingWritten = code.newLabel();
			code.getstatic(cp.fieldRef(thisClass, "_fenv", "Ljava/lang/Object;"));
			code.ifnull(nothingWritten);
			JvmTailBounce.unwrapRaw(code, cp, thisClass);
			code.labelBinding(nothingWritten);
		}
		code.areturn();
		code.labelBinding(redefined);
		int list = emitArgumentList(code, fi, cp, thisClass, unsupplied);
		code.getstatic(this.field);
		if (binding()) {
			code.invokestatic(JvmFunctionNamespace.bindingValueRef(cp, thisClass));
		}
		code.aload(list);
		code.invokestatic(
				cp.methodRef(thisClass, "_apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		code.areturn();
		return code;
	}

	/**
	 * The body of the entry a call takes as the tail of a method whose answer reaches a
	 * check for a bounce ({@link #tailNamed}), of {@code fi}'s descriptor: as the named
	 * entry, but the cell's function is answered as a bounce of the apply, for the
	 * nearest check to drive: a chain of tail calls through a replaced name keeps no
	 * frame per hop ({@link JvmTailBounce}). A hop allocates, only once the name is
	 * replaced.
	 * @param fi the function, whose cell this is
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param unsupplied the class's UNSUPPLIED marker
	 * @return the body
	 */
	MethodCode tailEntryBody(JvmLispCompiler.FunctionInfo fi, ConstantPool cp, ClassEntry thisClass,
			JvmUnsupplied unsupplied) {
		MethodCode code = new MethodCode();
		MethodCode.Label redefined = code.newLabel();
		emitOwnMethodCall(code, fi, redefined);
		code.areturn();
		code.labelBinding(redefined);
		int list = emitArgumentList(code, fi, cp, thisClass, unsupplied);
		code.iconst_3();
		code.anewarray(cp.classEntry("java/lang/Object"));
		code.dup();
		code.iconst_0();
		code.getstatic(cp.fieldRef(cp.classEntry("java/lang/Boolean"), cp.utf8Entry("FALSE"),
				cp.utf8Entry(JvmTailBounce.MARKER_DESC)));
		code.aastore();
		code.dup();
		code.iconst_1();
		code.getstatic(this.field);
		code.invokestatic(JvmFunctionNamespace.bindingValueRef(cp, thisClass));
		code.aastore();
		code.dup();
		code.iconst_2();
		code.aload(list);
		code.aastore();
		code.areturn();
		return code;
	}

	/**
	 * Emits the jump to {@code redefined} where the cell is set, then the call of the
	 * function's own method over the entry's parameters.
	 */
	private void emitOwnMethodCall(MethodCode code, JvmLispCompiler.FunctionInfo fi, MethodCode.Label redefined) {
		int params = fi.paramCount();
		code.getstatic(this.field);
		code.ifnonnull(redefined);
		for (int i = 0; i < params; i++) {
			code.aload(i);
		}
		if (fi.bounceVisible()) {
			code.iload(params);
		}
		code.invokestatic(fi.methodref());
	}

	/**
	 * Emits the argument list of an entry's parameters, the required ones, the optionals
	 * passed and the rest list.
	 * @return the local holding it
	 */
	private static int emitArgumentList(MethodCode code, JvmLispCompiler.FunctionInfo fi, ConstantPool cp,
			ClassEntry thisClass, JvmUnsupplied unsupplied) {
		int params = fi.paramCount();
		int list = params + (fi.bounceVisible() ? 1 : 0);
		if (fi.variadic()) {
			code.aload(params - 1);
		}
		else {
			code.aconst_null();
		}
		code.astore(list);
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		for (int i = fi.positional() - 1; i >= 0; i--) {
			MethodCode.Label skip = null;
			if (i >= fi.required()) {
				// an optional the call did not pass: nothing past it was passed either
				skip = code.newLabel();
				code.aload(i);
				code.invokestatic(unsupplied.ref(cp, thisClass));
				code.if_acmpeq(skip);
			}
			code.iconst_2();
			code.anewarray(objectClass);
			code.dup();
			code.iconst_0();
			code.aload(i);
			code.aastore();
			code.dup();
			code.iconst_1();
			code.aload(list);
			code.aastore();
			code.astore(list);
			if (skip != null) {
				code.labelBinding(skip);
			}
		}
		return list;
	}

	/**
	 * Records that the method being emitted calls this cell's tail entry, which it may
	 * answer a bounce from.
	 * @param ctx the method being emitted
	 */
	void noteTailCall(JvmLispCompiler.Ctx ctx) {
		ctx.bouncingBodies.add(ctx.body);
		ctx.spreadBounces[0] = true;
		ctx.celledTails.add(this);
		if (ctx.ownCell != null) {
			ctx.celledBouncers.add(ctx.ownCell);
		}
	}

	/**
	 * With a self or tail group jump's physical arguments on the stack: where the callee
	 * has a cell and a redefinition set it, the call of the callee's named entry over
	 * them, answered as the method's value -- the jump is the call's tail, so nothing
	 * runs after it -- and otherwise the arguments left for the jump. A function with no
	 * cell emits nothing.
	 * @param callee the function the jump enters
	 * @param ctx the method being emitted, at the jump's tail
	 */
	static void emitRedefinedExit(JvmLispCompiler.FunctionInfo callee, JvmLispCompiler.Ctx ctx) {
		JvmFunctionCell cell = callee.cell();
		if (cell == null) {
			return;
		}
		MethodCode.Label jump = ctx.body.newLabel();
		ctx.body.getstatic(cell.field());
		ctx.body.ifnull(jump);
		if (callee.bounceVisible()) {
			// the entry's own method takes the depth; the cell's function is applied
			// afresh and answers a value, or where this method's answer reaches a check,
			// a bounce
			ctx.body.iload(JvmTailBounce.depthSlot(ctx));
		}
		MethodRefEntry tailNamed = cell.tailNamed();
		if (tailNamed != null && (ctx.passesBounces || ctx.answersCellBounces)) {
			cell.noteTailCall(ctx);
			ctx.body.invokestatic(tailNamed);
		}
		else {
			ctx.body.invokestatic(cell.named());
		}
		ctx.body.areturn();
		ctx.body.labelBinding(jump);
	}

	/**
	 * Jumps to {@code set} with the cell's function pushed where a redefinition set it,
	 * else falls through with nothing pushed.
	 * @param ctx the method being emitted
	 * @param className the class being emitted
	 * @param set the label to continue at with the cell's function pushed
	 */
	void emitRead(JvmLispCompiler.Ctx ctx, String className, MethodCode.Label set) {
		ctx.body.getstatic(this.field);
		ctx.body.dup();
		if (binding()) {
			MethodCode.Label unset = ctx.body.newLabel();
			ctx.body.ifnull(unset);
			ctx.body.invokestatic(JvmFunctionNamespace.bindingValueRef(ctx.cp, ctx.cp.classEntry(className)));
			ctx.body.goto_(set);
			ctx.body.labelBinding(unset);
		}
		else {
			ctx.body.ifnonnull(set);
		}
		ctx.body.pop();
	}

}
