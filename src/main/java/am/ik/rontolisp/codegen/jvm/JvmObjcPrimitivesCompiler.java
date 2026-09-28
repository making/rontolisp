package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * Compiles a call of the new {@code objc} base's primitive layer -- an {@code objc::%}
 * function {@code objc.lisp} is written over, or {@code objc:on-main} -- into a static
 * call on the program's {@link JvmObjcPrimitivesTemplate} copy, preceded by the emitted
 * {@code _objcInit} (which binds it). Arity is fixed per primitive and checked here.
 */
final class JvmObjcPrimitivesCompiler {

	/** {@code objc:on-main}, qualified. */
	static final String ON_MAIN = PackageRegistry.qualify(LispNames.OBJC_PKG, LispNames.OBJC_ON_MAIN);

	private JvmObjcPrimitivesCompiler() {
	}

	/**
	 * Every compiled name, with its arity and the template method it calls.
	 * @return name -> {arity, method}
	 */
	static Map<String, Object[]> table() {
		return TABLE;
	}

	private static final Map<String, Object[]> TABLE = Map.ofEntries(
			Map.entry(LispNames.OBJC_GET_CLASS, new Object[] { 1, "getClass" }),
			Map.entry(LispNames.OBJC_CLASS_NAME_INTERNAL, new Object[] { 1, "className" }),
			Map.entry(LispNames.OBJC_OBJECT_CLASS, new Object[] { 1, "objectClass" }),
			Map.entry(LispNames.OBJC_CLASS_P, new Object[] { 1, "classP" }),
			Map.entry(LispNames.OBJC_REGISTER_SELECTOR, new Object[] { 1, "registerSelector" }),
			Map.entry(LispNames.OBJC_SELECTOR_NAME_INTERNAL, new Object[] { 1, "selectorName" }),
			Map.entry(LispNames.OBJC_METHOD_TYPES, new Object[] { 2, "methodTypes" }),
			Map.entry(LispNames.OBJC_SEND_INTERNAL, new Object[] { 6, "send" }),
			Map.entry(LispNames.OBJC_NEW_HANDLE, new Object[] { 2, "newHandle" }),
			Map.entry(LispNames.OBJC_REFS, new Object[] { 3, "refs" }),
			Map.entry(LispNames.OBJC_INTERNED, new Object[] { 1, "interned" }),
			Map.entry(LispNames.OBJC_INTERN, new Object[] { 2, "intern" }),
			Map.entry(LispNames.OBJC_LOAD_MODULE, new Object[] { 1, "loadModule" }),
			Map.entry(LispNames.OBJC_INITIALIZE, new Object[] { 0, "initialize" }),
			Map.entry(LispNames.OBJC_ALLOCATE_CLASS, new Object[] { 2, "allocateClass" }),
			Map.entry(LispNames.OBJC_ADD_IVAR, new Object[] { 5, "addIvar" }),
			Map.entry(LispNames.OBJC_REGISTER_CLASS, new Object[] { 1, "registerClass" }),
			Map.entry(LispNames.OBJC_ADD_METHOD, new Object[] { 5, "addMethod" }),
			Map.entry(LispNames.OBJC_ADD_PROTOCOL, new Object[] { 2, "addProtocol" }),
			Map.entry(LispNames.OBJC_SUPERCLASS, new Object[] { 1, "superclass" }),
			Map.entry(LispNames.OBJC_SEND_SUPER, new Object[] { 7, "sendSuper" }),
			Map.entry(LispNames.OBJC_IVAR_OFFSET, new Object[] { 2, "ivarOffset" }),
			Map.entry(LispNames.OBJC_IVAR_TYPES, new Object[] { 2, "ivarTypes" }),
			Map.entry(LispNames.OBJC_PEEK, new Object[] { 2, "peek" }),
			Map.entry(LispNames.OBJC_POKE, new Object[] { 3, "poke" }),
			Map.entry(LispNames.OBJC_MAKE_BLOCK, new Object[] { 3, "makeBlock" }),
			Map.entry(LispNames.OBJC_FREE_BLOCK, new Object[] { 1, "freeBlock" }),
			Map.entry(LispNames.OBJC_CALL_FUNCTION, new Object[] { 5, "callFunction" }),
			Map.entry(LispNames.OBJC_SYMBOL_ADDRESS, new Object[] { 1, "symbolAddress" }),
			Map.entry(LispNames.OBJC_RAISED, new Object[] { 0, "raised" }),
			Map.entry(ON_MAIN, new Object[] { 1, "onMain" }));

	/**
	 * Every name this compiler handles, qualified: the primitives and {@code on-main}.
	 * @return the names
	 */
	static List<String> names() {
		List<String> names = new ArrayList<>(LispNames.OBJC_PRIMITIVES);
		names.add(ON_MAIN);
		return names;
	}

	/**
	 * Whether a qualified name is one of the primitives.
	 * @param qualified the canonical symbol name
	 * @return {@code true} when this compiler handles it
	 */
	static boolean handles(String qualified) {
		return TABLE.containsKey(qualified);
	}

	static void compile(String qualified, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> ops = ctx.objcOps;
		if (ops == null) {
			throw new IllegalStateException("objc runtime was not emitted");
		}
		Object[] entry = Objects.requireNonNull(TABLE.get(qualified));
		int arity = (Integer) entry[0];
		List<LispVal> args = cons.toList();
		if (args.size() - 1 != arity) {
			throw new UnsupportedOperationException(qualified.toLowerCase(java.util.Locale.ROOT) + " expects " + arity
					+ " argument(s), got " + (args.size() - 1));
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
		for (int i = 1; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(qualified)).index());
	}

}
