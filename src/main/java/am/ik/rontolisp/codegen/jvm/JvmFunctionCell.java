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
 * @param field the cell, the field of the class declaring the function (a fragment's
 * reference to a class before it is linked there, {@code ConstantPool#linkFields})
 * @param named this class's named entry, {@link JvmLinkage#namedEntry}, of the function's
 * descriptor
 */
record JvmFunctionCell(FieldRefEntry field, MethodRefEntry named) {

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
	 * @param fi the function, whose cell this is
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param unsupplied the class's UNSUPPLIED marker
	 * @return the body
	 */
	MethodCode namedEntryBody(JvmLispCompiler.FunctionInfo fi, ConstantPool cp, ClassEntry thisClass,
			JvmUnsupplied unsupplied) {
		MethodCode code = new MethodCode();
		MethodCode.Label redefined = code.newLabel();
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
		code.areturn();
		code.labelBinding(redefined);
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
		code.getstatic(this.field);
		code.aload(list);
		code.invokestatic(
				cp.methodRef(thisClass, "_apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		code.areturn();
		return code;
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
			// afresh and answers a value
			ctx.body.iload(JvmTailBounce.depthSlot(ctx));
		}
		ctx.body.invokestatic(cell.named());
		ctx.body.areturn();
		ctx.body.labelBinding(jump);
	}

	/**
	 * Jumps to {@code set} with the cell's function pushed where a redefinition set it,
	 * else falls through with nothing pushed.
	 * @param ctx the method being emitted
	 * @param set the label to continue at with the cell's function pushed
	 */
	void emitRead(JvmLispCompiler.Ctx ctx, MethodCode.Label set) {
		ctx.body.getstatic(this.field);
		ctx.body.dup();
		ctx.body.ifnonnull(set);
		ctx.body.pop();
	}

}
