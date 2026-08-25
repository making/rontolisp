package am.ik.rontolisp.eval;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Function;

import am.ik.objc.MainThread;
import am.ik.objc.ObjcClasses;
import am.ik.objc.ObjcException;
import am.ik.objc.ObjcRuntime;
import am.ik.objc.TypeEncoding;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispObjcObject;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The thin layer between {@link ObjcInterop} and {@code am.ik.objc}: the {@code objc:}
 * function bodies, the marshalling of a Lisp value to the binding's Java-typed protocol
 * and back, the ownership of every wrapped object, and the ONE reference to the library
 * that the Web Image substitution has to be able to cut. Reached only through
 * {@link ObjcInterop}.
 *
 * <h2>Every send hops to thread 0</h2>
 *
 * AppKit belongs to the process's first thread, so every {@code objc:} verb runs its body
 * through {@link MainThread#sync} -- inline when the caller is already there (a
 * callback), else through the main dispatch queue -- and {@code objc:on-main} lets a
 * program batch a whole widget into one hop. A callback's Lisp handler therefore runs on
 * thread 0 with the interpreter's GLOBAL dynamic bindings (thread-local bindings belong
 * to the thread that made them), and an error it does not handle is printed, never
 * thrown: unwinding into the native frame above an upcall ends the process.
 *
 * <h2>Ownership: one retain per wrapper, released on thread 0</h2>
 *
 * A {@link LispObjcObject} owns exactly one reference. An object answered by the
 * {@code alloc} / {@code new} / {@code copy} / {@code mutableCopy} / {@code retain}
 * family arrives at +1 and the wrapper takes it; everything else -- a factory result, an
 * accessor's answer, a callback's argument -- is retained on the way in, INSIDE the same
 * main-thread hop that produced it, because the main queue drains its autorelease pool
 * when the hop returns. A {@link Cleaner} releases the reference when the wrapper is
 * collected, through {@link ObjcRuntime#releaseOnMain} since AppKit deallocates a window
 * or a view on thread 0 only. Hence the one rule a program must honour: a window it makes
 * with {@code objc:} must have {@code setReleasedWhenClosed:} off (the widget layer
 * does), or closing it releases a reference the wrapper still holds.
 *
 * @see ObjcInterop
 */
final class ObjcBridge {

	private static final Cleaner CLEANER = Cleaner.create();

	private ObjcBridge() {
	}

	static boolean available() {
		return ObjcRuntime.available();
	}

	static String description() {
		return ObjcRuntime.description();
	}

	static boolean mainThreadHandOverRequired() {
		return MainThread.handOverRequired();
	}

	static void parkMainThread() {
		MainThread.get().runLoop();
	}

	static void register(Environment globalEnv, ObjcCaller caller) {
		ObjcClasses.onError(ex -> System.err.println("objc: error in a callback: " + message(ex)));
		define(globalEnv, LispNames.OBJC_CLASS, args -> {
			String name = string(LispNames.OBJC_CLASS, args, 0, 1);
			ObjcRuntime runtime = ObjcRuntime.get();
			return wrapClass(runtime, runtime.objcClass(name));
		});
		define(globalEnv, LispNames.OBJC_SEND, args -> {
			if (args.size() < 2 || !(args.get(1) instanceof LispString selector)) {
				throw new LispEvalException("objc:send expects (objc:send receiver \"selector\" args...)");
			}
			LispVal target = args.get(0);
			if (target instanceof LispNil) {
				// Objective-C answers nil to a message sent to nil, even on a machine
				// without the runtime -- no need to require it just to answer nil.
				return LispNil.INSTANCE;
			}
			ObjcRuntime runtime = ObjcRuntime.get();
			@Nullable Object[] operands = new @Nullable Object[args.size() - 2];
			for (int i = 2; i < args.size(); i++) {
				operands[i - 2] = toJava(args.get(i));
			}
			return onMain(runtime, () -> {
				MemorySegment receiver = receiver(runtime, target);
				return fromJava(runtime, runtime.send(receiver, selector.value(), operands), selector.value());
			});
		});
		define(globalEnv, LispNames.OBJC_DEFINE_CLASS, args -> {
			if (args.size() < 3 || args.size() > 4 || !(args.get(0) instanceof LispString name)
					|| !(args.get(1) instanceof LispString superclass)) {
				throw new LispEvalException(
						"objc:define-class expects (objc:define-class \"Name\" \"Superclass\" methods [protocols])");
			}
			List<ObjcClasses.Spec> specs = new ArrayList<>();
			for (LispVal spec : elements(LispNames.OBJC_DEFINE_CLASS, args.get(2), "the method list")) {
				List<LispVal> pair = elements(LispNames.OBJC_DEFINE_CLASS, spec, "a method");
				if (pair.size() != 2 || !(pair.get(0) instanceof LispString selector)) {
					throw new LispEvalException(
							"objc:define-class: a method is (\"selector:\" function), got " + spec.print());
				}
				LispVal function = pair.get(1);
				specs.add(new ObjcClasses.Spec(selector.value(),
						(self, callArgs) -> callback(caller, function, self, callArgs)));
			}
			List<String> protocols = new ArrayList<>();
			if (args.size() == 4) {
				for (LispVal protocol : elements(LispNames.OBJC_DEFINE_CLASS, args.get(3), "the protocol list")) {
					if (!(protocol instanceof LispString p)) {
						throw new LispEvalException(
								"objc:define-class: a protocol is a string, got " + protocol.print());
					}
					protocols.add(p.value());
				}
			}
			ObjcRuntime runtime = ObjcRuntime.get();
			return onMain(runtime, () -> wrapClass(runtime,
					ObjcClasses.define(runtime, name.value(), superclass.value(), protocols, specs)));
		});
		define(globalEnv, LispNames.OBJC_ON_MAIN, args -> {
			if (args.size() != 1) {
				throw new LispEvalException("objc:on-main expects 1 argument, got " + args.size());
			}
			ObjcRuntime runtime = ObjcRuntime.get();
			LispVal function = args.get(0);
			return onMain(runtime, () -> caller.apply(function, List.of()));
		});
		define(globalEnv, LispNames.OBJC_STRING, args -> {
			String text = string(LispNames.OBJC_STRING, args, 0, 1);
			ObjcRuntime runtime = ObjcRuntime.get();
			return onMain(runtime, () -> {
				try (Arena arena = Arena.ofConfined()) {
					return wrapObject(runtime, runtime.nsString(text, arena), true);
				}
			});
		});
		define(globalEnv, LispNames.OBJC_ADDRESS, args -> {
			if (args.size() != 1 || !(args.get(0) instanceof LispObjcObject object)) {
				throw new LispEvalException("objc:address expects an Objective-C object, got "
						+ (args.isEmpty() ? "no arguments" : args.get(0).print()));
			}
			return new LispInteger(object.address());
		});
		define(globalEnv, LispNames.OBJC_OBJECTP, args -> {
			if (args.size() != 1) {
				throw new LispEvalException("objc:objectp expects 1 argument, got " + args.size());
			}
			return args.get(0) instanceof LispObjcObject ? LispTrue.INSTANCE : LispNil.INSTANCE;
		});
	}

	// Every verb signals a plain error whose message names the verb: the runtime's own
	// exception is the reason (absent runtime, unknown class, bad operand, unregistered
	// shape), and the Lisp condition is what handler-case catches.
	private static void define(Environment globalEnv, String member, Function<List<LispVal>, LispVal> body) {
		String name = PackageRegistry.qualify(LispNames.OBJC_PKG, member);
		String spelled = name.toLowerCase(Locale.ROOT);
		globalEnv.defineFunction(name, new LispFunction(name, args -> {
			try {
				return body.apply(args);
			}
			catch (ObjcException ex) {
				throw new LispEvalException(spelled + ": " + ex.getMessage());
			}
		}));
	}

	private static String message(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.toString() : message;
	}

	private static String string(String member, List<LispVal> args, int index, int arity) {
		if (args.size() != arity || !(args.get(index) instanceof LispString s)) {
			throw new LispEvalException("objc:" + member.toLowerCase(Locale.ROOT) + " expects a string, got "
					+ (args.size() <= index ? "no arguments" : args.get(index).print()));
		}
		return s.value();
	}

	private static List<LispVal> elements(String member, LispVal list, String what) {
		if (list instanceof LispNil) {
			return List.of();
		}
		if (list instanceof LispCons cons && cons.isProperList()) {
			return cons.toList();
		}
		throw new LispEvalException(
				"objc:" + member.toLowerCase(Locale.ROOT) + ": " + what + " must be a list, got " + list.print());
	}

	private static <T> T onMain(ObjcRuntime runtime, Callable<T> body) {
		T value = runtime.mainThread().sync(body);
		if (value == null) {
			throw new IllegalStateException("a main-thread body answered null");
		}
		return value;
	}

	private static MemorySegment receiver(ObjcRuntime runtime, LispVal target) {
		return switch (target) {
			case LispObjcObject object -> MemorySegment.ofAddress(object.address());
			case LispString className -> runtime.objcClass(className.value());
			default -> throw new LispEvalException(
					"objc:send: the receiver must be an object or a class name, got " + target.print());
		};
	}

	/**
	 * A callback's body: wrap the receiver and the arguments, apply, marshal the answer.
	 */
	private static @Nullable Object callback(ObjcCaller caller, LispVal function, MemorySegment self,
			@Nullable Object[] args) {
		ObjcRuntime runtime = ObjcRuntime.get();
		List<LispVal> lispArgs = new ArrayList<>(args.length + 1);
		lispArgs.add(wrapObject(runtime, self, true));
		for (Object arg : args) {
			lispArgs.add(arg instanceof MemorySegment seg ? wrapObject(runtime, seg, true) : LispNil.INSTANCE);
		}
		return toJava(caller.apply(function, lispArgs));
	}

	// --- marshalling ----------------------------------------------------------------

	/** A Lisp value in the binding's protocol: what {@link ObjcRuntime#send} takes. */
	private static @Nullable Object toJava(LispVal value) {
		return switch (value) {
			case LispNil ignored -> null;
			case LispTrue ignored -> Boolean.TRUE;
			case LispObjcObject object -> MemorySegment.ofAddress(object.address());
			case LispString s -> s.value();
			case LispInteger i -> i.value();
			case LispDouble d -> d.value();
			case LispCons cons when cons.isProperList() -> {
				List<LispVal> items = cons.toList();
				Number[] leaves = new Number[items.size()];
				for (int i = 0; i < leaves.length; i++) {
					leaves[i] = switch (items.get(i)) {
						case LispInteger n -> n.value();
						case LispDouble n -> n.value();
						default -> throw new LispEvalException(
								"objc:send: a struct is a list of numbers, got " + value.print());
					};
				}
				yield leaves;
			}
			default -> throw new LispEvalException("objc:send: cannot pass " + value.print() + " to Objective-C");
		};
	}

	/** The binding's answer as a Lisp value, by the kind the selector declared. */
	private static LispVal fromJava(ObjcRuntime runtime, ObjcRuntime.Sent sent, String selector) {
		Object value = sent.value();
		TypeEncoding.Type type = sent.type();
		if (value == null) {
			return LispNil.INSTANCE;
		}
		if (selector.startsWith("performSelector")) {
			// The answer is the TARGET method's, whose type this binding cannot see: a
			// void
			// method leaves garbage in the result register, and retaining garbage is a
			// SIGSEGV. Objective-C code ignores it too.
			return LispNil.INSTANCE;
		}
		return switch (type.kind()) {
			case OBJECT -> wrapObject(runtime, (MemorySegment) value, !handsOwnership(selector));
			case CLASS -> wrapClass(runtime, (MemorySegment) value);
			case POINTER -> new LispInteger(((MemorySegment) value).address());
			case SELECTOR, CSTRING -> new LispString((String) value);
			case BOOL -> (Boolean) value ? LispTrue.INSTANCE : LispNil.INSTANCE;
			case INT8, INT16, INT32, INT64 -> new LispInteger((Long) value);
			case FLOAT, DOUBLE -> new LispDouble((Double) value);
			case STRUCT -> {
				LispVal list = LispNil.INSTANCE;
				Number[] leaves = (Number[]) value;
				for (int i = leaves.length - 1; i >= 0; i--) {
					LispVal leaf = leaves[i] instanceof Double d ? new LispDouble(d)
							: new LispInteger(leaves[i].longValue());
					list = new LispCons(leaf, list);
				}
				yield list;
			}
			case VOID -> LispNil.INSTANCE;
		};
	}

	/**
	 * The Cocoa ownership convention: a method whose name begins with {@code alloc},
	 * {@code new}, {@code copy} or {@code mutableCopy}, and {@code retain}, answers an
	 * object the caller already owns.
	 */
	private static boolean handsOwnership(String selector) {
		return selector.startsWith("alloc") || selector.startsWith("new") || selector.startsWith("copy")
				|| selector.startsWith("mutableCopy") || selector.equals("retain");
	}

	private static LispObjcObject wrapObject(ObjcRuntime runtime, MemorySegment object, boolean retain) {
		if (retain) {
			runtime.retain(object);
		}
		LispObjcObject wrapper = new LispObjcObject(object.address(), runtime.className(object));
		CLEANER.register(wrapper, new Release(runtime, object.address()));
		return wrapper;
	}

	/** A class is immortal: no reference is owned and nothing is released. */
	private static LispObjcObject wrapClass(ObjcRuntime runtime, MemorySegment cls) {
		return new LispObjcObject(cls.address(), runtime.className(cls));
	}

	/** The cleaning action; it must not reference the wrapper it is registered for. */
	private record Release(ObjcRuntime runtime, long address) implements Runnable {

		@Override
		public void run() {
			try {
				this.runtime.releaseOnMain(this.address);
			}
			catch (RuntimeException ignored) {
				// the process is on its way out, or the pump is gone: leaking is the
				// safe direction
			}
		}

	}

}
