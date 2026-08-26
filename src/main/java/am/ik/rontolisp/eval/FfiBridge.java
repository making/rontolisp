package am.ik.rontolisp.eval;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import am.ik.ffi.FfiException;
import am.ik.ffi.FfiRuntime;
import am.ik.ffi.FfiType;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispForeignPointer;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The thin layer between {@link FfiInterop} and {@code am.ik.ffi}: the {@code ffi:}
 * function bodies, the marshalling of a Lisp value to the binding's Java-typed protocol
 * and back, and the ONE reference to the library that the Web Image substitution has to
 * be able to cut. Reached only through {@link FfiInterop} -- the {@code ObjcBridge}
 * shape, minus the main-thread hop: C has no thread-0 rule, so every verb runs on the
 * caller's thread.
 *
 * <h2>Type designators are data, decided at run time</h2>
 *
 * A type is a CFFI keyword ({@code :int}, {@code :pointer}, ...) or a
 * {@code (:struct member...)} list for a structure passed or returned BY VALUE
 * ({@link FfiType}); {@code ffi:call} accepts a {@code :varargs} marker in its
 * argument-type list where the variadic tail starts. A pointer is its own value
 * ({@link LispForeignPointer}), so {@code ffi:pointerp} answers {@code nil} for an
 * integer and a wrong operand at the boundary is a type error, not a silent
 * reinterpretation; a plain integer is still ACCEPTED wherever an address is expected
 * ({@code ffi:address} turns one into a pointer), because a binding computes addresses.
 *
 * <h2>Ownership is C's</h2>
 *
 * {@code ffi:alloc} is {@code malloc} and memory lives until {@code ffi:free} -- no
 * arena, no cleaner, no scope. That is the contract every C binding is written against
 * ({@code foreign-alloc} / {@code foreign-free}); the only arena anywhere is the
 * call-scoped one inside {@code am.ik.ffi} that {@code :string} arguments marshal
 * through. A callback's stub lives for the program, and an error its Lisp function does
 * not handle is printed ({@code ffi: error in a callback: ...}), never thrown --
 * unwinding into the native frame above an upcall ends the process.
 *
 * @see FfiInterop
 */
final class FfiBridge {

	private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

	private FfiBridge() {
	}

	static boolean available() {
		return FfiRuntime.available();
	}

	static String description() {
		return FfiRuntime.description();
	}

	static void register(Environment globalEnv, FfiCaller caller) {
		FfiRuntime.onError(ex -> System.err.println("ffi: error in a callback: " + message(ex)));
		define(globalEnv, LispNames.FFI_OPEN, args -> {
			if (args.size() > 1) {
				throw new LispEvalException("ffi:open expects at most 1 argument, got " + args.size());
			}
			FfiRuntime runtime = FfiRuntime.get();
			if (args.isEmpty() || args.get(0) instanceof LispNil) {
				return new LispInteger(FfiRuntime.DEFAULT_LIBRARY);
			}
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(
						"ffi:open expects a library name or path string, got " + args.get(0).print());
			}
			return new LispInteger(runtime.openLibrary(path.value()));
		});
		define(globalEnv, LispNames.FFI_SYMBOL, args -> {
			if (args.size() != 2 || !(args.get(0) instanceof LispInteger library)
					|| !(args.get(1) instanceof LispString name)) {
				throw new LispEvalException("ffi:symbol expects (ffi:symbol library \"name\"), got "
						+ (args.isEmpty() ? "no arguments" : args.get(0).print()));
			}
			long address = FfiRuntime.get().symbol(library.value(), name.value());
			return address == 0 ? LispNil.INSTANCE : new LispForeignPointer(address);
		});
		define(globalEnv, LispNames.FFI_CALL, args -> {
			if (args.size() < 3) {
				throw new LispEvalException("ffi:call expects (ffi:call function return-type argument-types args...)");
			}
			long function = address(LispNames.FFI_CALL, args.get(0));
			FfiType returnType = parseType(LispNames.FFI_CALL, args.get(1));
			List<FfiType> argTypes = new ArrayList<>();
			int firstVariadic = -1;
			for (LispVal designator : elements(LispNames.FFI_CALL, args.get(2), "the argument-type list")) {
				if (designator instanceof LispSymbol marker && marker.isKeyword()
						&& "VARARGS".equalsIgnoreCase(LispSymbol.displayName(marker.name()))) {
					if (firstVariadic >= 0) {
						throw new LispEvalException("ffi:call: :varargs may appear once");
					}
					firstVariadic = argTypes.size();
					continue;
				}
				argTypes.add(parseType(LispNames.FFI_CALL, designator));
			}
			if (args.size() - 3 != argTypes.size()) {
				throw new LispEvalException("ffi:call declares " + argTypes.size() + " argument"
						+ (argTypes.size() == 1 ? "" : "s") + " but got " + (args.size() - 3));
			}
			@Nullable Object[] values = new @Nullable Object[argTypes.size()];
			for (int i = 0; i < values.length; i++) {
				values[i] = toProtocol(LispNames.FFI_CALL, argTypes.get(i), args.get(i + 3));
			}
			Object raw = FfiRuntime.get()
				.call(new FfiRuntime.CallRequest(function, returnType, argTypes, firstVariadic, values));
			return fromProtocol(returnType, raw);
		});
		define(globalEnv, LispNames.FFI_CALLBACK, args -> {
			if (args.size() != 3) {
				throw new LispEvalException("ffi:callback expects (ffi:callback function return-type argument-types)");
			}
			LispVal function = args.get(0);
			FfiType returnType = parseType(LispNames.FFI_CALLBACK, args.get(1));
			List<FfiType> argTypes = new ArrayList<>();
			for (LispVal designator : elements(LispNames.FFI_CALLBACK, args.get(2), "the argument-type list")) {
				argTypes.add(parseType(LispNames.FFI_CALLBACK, designator));
			}
			FfiRuntime.Callback target = rawArgs -> {
				List<LispVal> lispArgs = new ArrayList<>(rawArgs.length);
				for (int i = 0; i < rawArgs.length; i++) {
					lispArgs.add(fromProtocol(argTypes.get(i), rawArgs[i]));
				}
				LispVal answer = caller.apply(function, lispArgs);
				return returnType == FfiType.Scalar.VOID ? null
						: toProtocol(LispNames.FFI_CALLBACK, returnType, answer);
			};
			return new LispForeignPointer(FfiRuntime.get().callback(target, returnType, argTypes));
		});
		define(globalEnv, LispNames.FFI_ALLOC, args -> {
			if (args.size() != 1 || !(args.get(0) instanceof LispInteger size)) {
				throw new LispEvalException("ffi:alloc expects a byte count, got "
						+ (args.isEmpty() ? "no arguments" : args.get(0).print()));
			}
			return new LispForeignPointer(FfiRuntime.get().alloc(size.value()));
		});
		define(globalEnv, LispNames.FFI_FREE, args -> {
			if (args.size() != 1) {
				throw new LispEvalException("ffi:free expects 1 argument, got " + args.size());
			}
			FfiRuntime.get().freeMemory(address(LispNames.FFI_FREE, args.get(0)));
			return LispNil.INSTANCE;
		});
		define(globalEnv, LispNames.FFI_PEEK, args -> {
			if (args.size() < 2 || args.size() > 3) {
				throw new LispEvalException("ffi:peek expects (ffi:peek pointer type [offset])");
			}
			long base = address(LispNames.FFI_PEEK, args.get(0));
			FfiType type = parseType(LispNames.FFI_PEEK, args.get(1));
			long offset = args.size() == 3 ? integerArgument(LispNames.FFI_PEEK, args.get(2), "the offset") : 0;
			return fromProtocol(type, FfiRuntime.get().peek(base, offset, type));
		});
		define(globalEnv, LispNames.FFI_POKE, args -> {
			if (args.size() < 3 || args.size() > 4) {
				throw new LispEvalException("ffi:poke expects (ffi:poke pointer type value [offset])");
			}
			long base = address(LispNames.FFI_POKE, args.get(0));
			FfiType type = parseType(LispNames.FFI_POKE, args.get(1));
			LispVal value = args.get(2);
			long offset = args.size() == 4 ? integerArgument(LispNames.FFI_POKE, args.get(3), "the offset") : 0;
			FfiRuntime.get()
				.poke(new FfiRuntime.PokeRequest(base, offset, type, toProtocol(LispNames.FFI_POKE, type, value)));
			return value;
		});
		define(globalEnv, LispNames.FFI_SIZE, args -> {
			if (args.size() != 1) {
				throw new LispEvalException("ffi:size expects a foreign type, got " + args.size() + " arguments");
			}
			return new LispInteger(parseType(LispNames.FFI_SIZE, args.get(0)).size());
		});
		define(globalEnv, LispNames.FFI_ALIGN, args -> {
			if (args.size() != 1) {
				throw new LispEvalException("ffi:align expects a foreign type, got " + args.size() + " arguments");
			}
			return new LispInteger(parseType(LispNames.FFI_ALIGN, args.get(0)).align());
		});
		define(globalEnv, LispNames.FFI_POINTERP, args -> {
			if (args.size() != 1) {
				throw new LispEvalException("ffi:pointerp expects 1 argument, got " + args.size());
			}
			return args.get(0) instanceof LispForeignPointer ? LispTrue.INSTANCE : LispNil.INSTANCE;
		});
		define(globalEnv, LispNames.FFI_ADDRESS, args -> {
			if (args.size() != 1) {
				throw new LispEvalException("ffi:address expects 1 argument, got " + args.size());
			}
			return switch (args.get(0)) {
				case LispForeignPointer pointer -> new LispInteger(pointer.address());
				case LispInteger integer -> new LispForeignPointer(integer.value());
				default -> throw new LispEvalException(
						"ffi:address expects a pointer or an integer address, got " + args.get(0).print());
			};
		});
		define(globalEnv, LispNames.FFI_ERRNO, args -> {
			if (!args.isEmpty()) {
				throw new LispEvalException("ffi:errno expects no arguments, got " + args.size());
			}
			return new LispInteger(FfiRuntime.get().errno());
		});
	}

	// Every verb signals a plain error whose message names the verb: the runtime's own
	// exception is the reason (denied native access, a library that will not open, a
	// missing symbol, an operand that does not fit, an unregistered shape), and the Lisp
	// condition is what handler-case catches.
	private static void define(Environment globalEnv, String member, Function<List<LispVal>, LispVal> body) {
		String name = PackageRegistry.qualify(LispNames.FFI_PKG, member);
		String spelled = name.toLowerCase(Locale.ROOT);
		globalEnv.defineFunction(name, new LispFunction(name, args -> {
			try {
				return body.apply(args);
			}
			catch (FfiException ex) {
				throw new LispEvalException(spelled + ": " + ex.getMessage());
			}
		}));
	}

	private static String message(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.toString() : message;
	}

	private static List<LispVal> elements(String member, LispVal list, String what) {
		if (list instanceof LispNil) {
			return List.of();
		}
		if (list instanceof LispCons cons && cons.isProperList()) {
			return cons.toList();
		}
		throw new LispEvalException(
				"ffi:" + member.toLowerCase(Locale.ROOT) + ": " + what + " must be a list, got " + list.print());
	}

	/**
	 * A type designator as an {@link FfiType}: a CFFI keyword, or a
	 * {@code (:struct member...)} list.
	 */
	private static FfiType parseType(String member, LispVal designator) {
		if (designator instanceof LispSymbol symbol && symbol.isKeyword()) {
			return FfiType.of(LispSymbol.displayName(symbol.name()));
		}
		if (designator instanceof LispCons cons && cons.isProperList()) {
			List<LispVal> items = cons.toList();
			if (!items.isEmpty() && items.get(0) instanceof LispSymbol head && head.isKeyword()
					&& "STRUCT".equalsIgnoreCase(LispSymbol.displayName(head.name()))) {
				List<FfiType> members = new ArrayList<>(items.size() - 1);
				for (LispVal item : items.subList(1, items.size())) {
					members.add(parseType(member, item));
				}
				return new FfiType.Struct(members);
			}
		}
		throw new LispEvalException("ffi:" + member.toLowerCase(Locale.ROOT)
				+ ": a foreign type is a keyword or (:struct member...), got " + designator.print());
	}

	/** An address operand: a pointer, or a plain integer a binding computed. */
	private static long address(String member, LispVal value) {
		return switch (value) {
			case LispForeignPointer pointer -> pointer.address();
			case LispInteger integer -> integer.value();
			default -> throw new LispEvalException("ffi:" + member.toLowerCase(Locale.ROOT)
					+ " expects a pointer or an integer address, got " + value.print());
		};
	}

	private static long integerArgument(String member, LispVal value, String what) {
		if (value instanceof LispInteger integer) {
			return integer.value();
		}
		throw new LispEvalException(
				"ffi:" + member.toLowerCase(Locale.ROOT) + ": " + what + " must be an integer, got " + value.print());
	}

	/** A Lisp value in the binding's protocol: what {@link FfiRuntime} takes. */
	private static @Nullable Object toProtocol(String member, FfiType type, LispVal value) {
		if (type instanceof FfiType.Struct) {
			return address(member, value);
		}
		FfiType.Scalar scalar = (FfiType.Scalar) type;
		return switch (scalar) {
			case INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, UINT64 -> switch (value) {
				case LispInteger integer -> integer.value();
				// The wrap to the raw 64 bits is exactly what an unsigned operand wants.
				case LispBigInteger big -> big.value().longValue();
				default -> operandMismatch(member, scalar, value);
			};
			case FLOAT, DOUBLE -> switch (value) {
				case LispDouble d -> d.value();
				case LispInteger integer -> integer.value();
				default -> operandMismatch(member, scalar, value);
			};
			case POINTER -> switch (value) {
				case LispNil ignored -> null;
				case LispForeignPointer pointer -> pointer.address();
				case LispInteger integer -> integer.value();
				default -> operandMismatch(member, scalar, value);
			};
			case STRING -> switch (value) {
				case LispNil ignored -> null;
				case LispString text -> text.value();
				case LispForeignPointer pointer -> pointer.address();
				default -> operandMismatch(member, scalar, value);
			};
			case VOID ->
				throw new LispEvalException("ffi:" + member.toLowerCase(Locale.ROOT) + ": an operand cannot be :void");
		};
	}

	private static Object operandMismatch(String member, FfiType type, LispVal value) {
		throw new LispEvalException(
				"ffi:" + member.toLowerCase(Locale.ROOT) + ": " + value.print() + " does not fit " + type.spelling());
	}

	/** The binding's answer as a Lisp value, by the declared type. */
	private static LispVal fromProtocol(FfiType type, @Nullable Object value) {
		if (value == null) {
			return LispNil.INSTANCE;
		}
		if (type instanceof FfiType.Struct) {
			return new LispForeignPointer((Long) value);
		}
		FfiType.Scalar scalar = (FfiType.Scalar) type;
		return switch (scalar) {
			case POINTER -> new LispForeignPointer((Long) value);
			case UINT64 -> {
				long raw = (Long) value;
				yield raw < 0 ? new LispBigInteger(BigInteger.valueOf(raw).add(TWO_64)) : new LispInteger(raw);
			}
			case INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64 -> new LispInteger((Long) value);
			case FLOAT, DOUBLE -> new LispDouble((Double) value);
			case STRING -> new LispString((String) value);
			case VOID -> LispNil.INSTANCE;
		};
	}

}
