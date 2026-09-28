package am.ik.rontolisp.eval;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

import am.ik.objc.ObjcBlocks;
import am.ik.objc.ObjcException;
import am.ik.objc.ObjcMethods;
import am.ik.objc.ObjcReference;
import am.ik.objc.ObjcRuntime;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's primitive layer of the new {@code objc} base: the {@code objc::%}
 * functions {@code objc.lisp} is written over (.kb/objc.md, "The new base"), plus
 * {@code objc:on-main}. Nothing here decides a rule -- which argument converts to what,
 * who owns a reference, when a pool drains -- that is all in {@code objc.lisp}, run
 * unchanged on every target; this class only moves raw values between the interpreter's
 * representation and {@code am.ik.objc}. {@code codegen/jvm/JvmObjcPrimitivesTemplate} is
 * the same layer over the compiled representation, and
 * {@code objc-native-primitives.lisp} over a {@code --native} runner's imports.
 *
 * <p>
 * Reached only through {@link ObjcInterop#registerPrimitives}, so the Web Image
 * substitution cuts this class and the binding with it.
 */
final class ObjcPrimitives {

	private ObjcPrimitives() {
	}

	static void register(Environment globalEnv, BiFunction<LispVal, List<LispVal>, LispVal> apply) {
		define(globalEnv, LispNames.OBJC_GET_CLASS, 1, args -> {
			ObjcRuntime runtime = ObjcRuntime.get();
			return integer(runtime.classOrNullAddress(string(args.get(0))));
		});
		define(globalEnv, LispNames.OBJC_CLASS_NAME_INTERNAL, 1,
				args -> new LispString(ObjcRuntime.get().nameOfClass(address(args.get(0)))));
		define(globalEnv, LispNames.OBJC_OBJECT_CLASS, 1,
				args -> integer(ObjcRuntime.get().classOfAddress(address(args.get(0)))));
		define(globalEnv, LispNames.OBJC_CLASS_P, 1,
				args -> ObjcRuntime.get().isClass(address(args.get(0))) ? LispTrue.INSTANCE : LispNil.INSTANCE);
		define(globalEnv, LispNames.OBJC_REGISTER_SELECTOR, 1,
				args -> integer(ObjcRuntime.get().selector(string(args.get(0))).address()));
		define(globalEnv, LispNames.OBJC_SELECTOR_NAME_INTERNAL, 1, args -> new LispString(
				ObjcRuntime.get().selectorName(java.lang.foreign.MemorySegment.ofAddress(address(args.get(0))))));
		define(globalEnv, LispNames.OBJC_METHOD_TYPES, 2, args -> {
			String types = ObjcRuntime.get().methodTypes(address(args.get(0)), address(args.get(1)));
			return types == null ? LispNil.INSTANCE : new LispString(types);
		});
		define(globalEnv, LispNames.OBJC_SEND_INTERNAL, 6, args -> {
			ObjcRuntime runtime = ObjcRuntime.get();
			List<LispVal> raw = list(args.get(4));
			@Nullable Object[] operands = new @Nullable Object[raw.size()];
			for (int i = 0; i < operands.length; i++) {
				operands[i] = toRaw(raw.get(i));
			}
			Object answer = runtime.sendRawOnMain(address(args.get(0)), address(args.get(1)), string(args.get(2)),
					(int) address(args.get(3)), operands, (int) address(args.get(5)));
			return fromRaw(answer);
		});
		define(globalEnv, LispNames.OBJC_NEW_HANDLE, 2, args -> new LispJavaObject(
				ObjcReference.own(ObjcRuntime.get(), address(args.get(0)), address(args.get(1)))));
		define(globalEnv, LispNames.OBJC_REFS, 3, args -> {
			if (!(args.get(0) instanceof LispJavaObject(ObjcReference handle))) {
				throw new LispEvalException("objc: not a reference handle: " + args.get(0).print());
			}
			return integer(handle.adjust((int) address(args.get(1)), address(args.get(2))));
		});
		define(globalEnv, LispNames.OBJC_INTERNED, 1, args -> {
			Object value = ObjcReference.interned(address(args.get(0)));
			return value instanceof LispVal lisp ? lisp : LispNil.INSTANCE;
		});
		define(globalEnv, LispNames.OBJC_INTERN, 2,
				args -> (LispVal) ObjcReference.intern(address(args.get(0)), args.get(1)));
		define(globalEnv, LispNames.OBJC_LOAD_MODULE, 1, args -> {
			String path = string(args.get(0));
			try {
				SymbolLookup.libraryLookup(path, Arena.global());
			}
			catch (RuntimeException ex) {
				throw new ObjcException("the module " + path + " cannot be loaded: " + ex.getMessage(), ex);
			}
			return LispTrue.INSTANCE;
		});
		define(globalEnv, LispNames.OBJC_INITIALIZE, 0, args -> {
			ObjcRuntime.get();
			return LispTrue.INSTANCE;
		});
		registerClassDefinition(globalEnv, apply);
		registerBlocks(globalEnv, apply);
		String onMain = PackageRegistry.qualify(LispNames.OBJC_PKG, LispNames.OBJC_ON_MAIN);
		globalEnv.defineFunction(onMain, new LispFunction(onMain, args -> {
			if (args.size() != 1) {
				throw new LispEvalException("objc:on-main expects 1 argument, got " + args.size());
			}
			LispVal function = args.get(0);
			try {
				LispVal value = ObjcRuntime.get().mainThread().sync(() -> apply.apply(function, List.of()));
				return value == null ? LispNil.INSTANCE : value;
			}
			catch (ObjcException ex) {
				throw new LispEvalException("objc:on-main: " + ex.getMessage());
			}
		}));
	}

	/**
	 * The class-definition half: the primitives {@code objc-class.lisp} is written over.
	 */
	private static void registerClassDefinition(Environment globalEnv,
			BiFunction<LispVal, List<LispVal>, LispVal> apply) {
		define(globalEnv, LispNames.OBJC_ALLOCATE_CLASS, 2,
				args -> integer(ObjcRuntime.get().allocateClass(address(args.get(0)), string(args.get(1)))));
		define(globalEnv, LispNames.OBJC_ADD_IVAR, 5,
				args -> ObjcRuntime.get()
					.addIvar(address(args.get(0)), string(args.get(1)), address(args.get(2)), address(args.get(3)),
							string(args.get(4))) ? LispTrue.INSTANCE : LispNil.INSTANCE);
		define(globalEnv, LispNames.OBJC_REGISTER_CLASS, 1, args -> {
			ObjcRuntime.get().registerClass(address(args.get(0)));
			return LispTrue.INSTANCE;
		});
		define(globalEnv, LispNames.OBJC_ADD_METHOD, 5, args -> {
			LispVal function = args.get(3);
			ObjcMethods.add(ObjcRuntime.get(), address(args.get(0)), address(args.get(1)), string(args.get(2)),
					(self, raw) -> {
						LispVal list = LispNil.INSTANCE;
						for (int i = raw.length - 1; i >= 0; i--) {
							list = new LispCons(fromRaw(raw[i]), list);
						}
						return toRaw(apply.apply(function, List.of(integer(self), list)));
					}, (int) address(args.get(4)));
			return LispTrue.INSTANCE;
		});
		define(globalEnv, LispNames.OBJC_ADD_PROTOCOL, 2,
				args -> ObjcRuntime.get().addProtocol(address(args.get(0)), string(args.get(1))) ? LispTrue.INSTANCE
						: LispNil.INSTANCE);
		define(globalEnv, LispNames.OBJC_SUPERCLASS, 1,
				args -> integer(ObjcRuntime.get().superclassAddress(address(args.get(0)))));
		define(globalEnv, LispNames.OBJC_SEND_SUPER, 7, args -> {
			List<LispVal> raw = list(args.get(5));
			@Nullable Object[] operands = new @Nullable Object[raw.size()];
			for (int i = 0; i < operands.length; i++) {
				operands[i] = toRaw(raw.get(i));
			}
			return fromRaw(ObjcRuntime.get()
				.sendRawOnMain(address(args.get(0)), address(args.get(1)), address(args.get(2)), string(args.get(3)),
						(int) address(args.get(4)), operands, (int) address(args.get(6))));
		});
		define(globalEnv, LispNames.OBJC_IVAR_OFFSET, 2,
				args -> integer(ObjcRuntime.get().ivarOffset(address(args.get(0)), string(args.get(1)))));
		define(globalEnv, LispNames.OBJC_IVAR_TYPES, 2, args -> {
			String types = ObjcRuntime.get().ivarTypes(address(args.get(0)), string(args.get(1)));
			return types == null ? LispNil.INSTANCE : new LispString(types);
		});
		define(globalEnv, LispNames.OBJC_PEEK, 2,
				args -> fromRaw(ObjcRuntime.get().peek(address(args.get(0)), string(args.get(1)))));
		define(globalEnv, LispNames.OBJC_POKE, 3, args -> {
			ObjcRuntime.get().poke(address(args.get(0)), string(args.get(1)), toRaw(args.get(2)));
			return LispNil.INSTANCE;
		});
	}

	/**
	 * The blocks and C functions half: the primitives {@code objc-block.lisp} and
	 * {@code fli:define-foreign-function} are written over.
	 */
	private static void registerBlocks(Environment globalEnv, BiFunction<LispVal, List<LispVal>, LispVal> apply) {
		define(globalEnv, LispNames.OBJC_MAKE_BLOCK, 3, args -> {
			LispVal function = args.get(2);
			return integer(ObjcBlocks.make(ObjcRuntime.get(), string(args.get(0)), string(args.get(1)), raw -> {
				LispVal list = LispNil.INSTANCE;
				for (int i = raw.length - 1; i >= 0; i--) {
					list = new LispCons(fromRaw(raw[i]), list);
				}
				return toRaw(apply.apply(function, List.of(list)));
			}));
		});
		define(globalEnv, LispNames.OBJC_FREE_BLOCK, 1, args -> {
			ObjcBlocks.free(address(args.get(0)));
			return LispNil.INSTANCE;
		});
		define(globalEnv, LispNames.OBJC_CALL_FUNCTION, 5, args -> {
			List<LispVal> raw = list(args.get(3));
			@Nullable Object[] operands = new @Nullable Object[raw.size()];
			for (int i = 0; i < operands.length; i++) {
				operands[i] = toRaw(raw.get(i));
			}
			return fromRaw(ObjcRuntime.get()
				.callRaw(address(args.get(0)), string(args.get(1)), (int) address(args.get(2)), operands,
						(int) address(args.get(4))));
		});
		define(globalEnv, LispNames.OBJC_SYMBOL_ADDRESS, 1,
				args -> integer(ObjcRuntime.get().symbolAddress(string(args.get(0)))));
	}

	// Every primitive signals a plain error starting with objc: -- the runtime's own
	// exception is the reason, and the Lisp condition is what handler-case catches.
	private static void define(Environment globalEnv, String name, int arity, Function<List<LispVal>, LispVal> body) {
		globalEnv.defineFunction(name, new LispFunction(name, args -> {
			if (args.size() != arity) {
				throw new LispEvalException(
						name.toLowerCase(Locale.ROOT) + " expects " + arity + " argument(s), got " + args.size());
			}
			try {
				return body.apply(args);
			}
			catch (ObjcException ex) {
				throw new LispEvalException("objc: " + ex.getMessage());
			}
		}));
	}

	private static LispVal integer(long value) {
		return new LispInteger(value);
	}

	private static long address(LispVal value) {
		return switch (value) {
			case LispInteger i -> i.value();
			case LispBigInteger b -> b.value().longValue();
			case LispNil ignored -> 0L;
			default -> throw new LispEvalException("objc: expected an integer, got " + value.print());
		};
	}

	private static String string(LispVal value) {
		if (value instanceof LispString s) {
			return s.value();
		}
		throw new LispEvalException("objc: expected a string, got " + value.print());
	}

	private static List<LispVal> list(LispVal value) {
		if (value instanceof LispNil) {
			return List.of();
		}
		if (value instanceof LispCons cons && cons.isProperList()) {
			return cons.toList();
		}
		throw new LispEvalException("objc: expected a list, got " + value.print());
	}

	/** A raw argument: a number, a string, nil, or a list of a struct's leaves. */
	private static @Nullable Object toRaw(LispVal value) {
		return switch (value) {
			case LispNil ignored -> null;
			case LispInteger i -> i.value();
			case LispBigInteger b -> b.value();
			case LispDouble d -> d.value();
			case LispString s -> s.value();
			case LispCons cons when cons.isProperList() -> {
				List<LispVal> items = cons.toList();
				Number[] leaves = new Number[items.size()];
				for (int i = 0; i < leaves.length; i++) {
					if (!(toRaw(items.get(i)) instanceof Number n)) {
						throw new LispEvalException("objc: a struct leaf is a number, got " + items.get(i).print());
					}
					leaves[i] = n;
				}
				yield leaves;
			}
			default -> throw new LispEvalException("objc: cannot pass " + value.print() + " to Objective-C");
		};
	}

	private static LispVal fromRaw(@Nullable Object value) {
		return switch (value) {
			case null -> LispNil.INSTANCE;
			case Long n -> new LispInteger(n);
			case Double d -> new LispDouble(d);
			case String s -> new LispString(s);
			case Number[] leaves -> {
				LispVal list = LispNil.INSTANCE;
				for (int i = leaves.length - 1; i >= 0; i--) {
					LispVal leaf = leaves[i] instanceof Double d ? new LispDouble(d)
							: new LispInteger(leaves[i].longValue());
					list = new LispCons(leaf, list);
				}
				yield list;
			}
			case BigInteger b -> new LispBigInteger(b);
			default -> throw new IllegalStateException("an unexpected raw answer: " + value);
		};
	}

}
