package am.ik.objc;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import am.ik.objc.TypeEncoding.Kind;
import am.ik.objc.TypeEncoding.Type;
import org.jspecify.annotations.Nullable;

/**
 * Classes defined at run time whose method bodies are callbacks into the host language --
 * the target of a button, the delegate of a window.
 *
 * <p>
 * {@link #define} registers a class with {@code objc_allocateClassPair} and gives each
 * method an IMP that is an FFM upcall stub. The encoding of a method is looked up, never
 * guessed: the superclass's own method first, then the adopted protocols' declarations,
 * and only for a selector nobody declares the target/action default of one object
 * argument and no result ({@code v@:} plus one {@code @} per colon).
 *
 * <p>
 * <strong>The closed set of callback shapes.</strong> A native image builds an upcall
 * stub only for a shape registered at build time, and every stub here is bound from a
 * CONSTANT {@code findStatic} (which the image folds into a direct handle; a name in a
 * variable needs reflection metadata the image lacks). So there is one static method per
 * supported shape ({@link #SHAPES}) and a method whose encoding maps to any other shape
 * is refused, naming the shape. The shapes cover what a widget layer needs: an action
 * ({@code v@:@}), a delegate notification ({@code v@:} / {@code v@:@} / {@code v@:@@}), a
 * delegate question ({@code B@:@}), an object or integer answer ({@code @@:@} /
 * {@code q@:@}).
 *
 * <p>
 * Every IMP dispatches through {@link #HANDLERS}, keyed by the class the method was
 * defined on and the selector, walking the receiver's superclass chain so a subclass
 * inherits a callback. Re-defining a class this process already defined REPLACES its
 * handlers (and adds any new selector) rather than failing -- a REPL re-evaluates its
 * definitions, and the runtime cannot unregister a class. A handler that throws never
 * unwinds into the native frame above it (that kills the process): the failure goes to
 * the {@linkplain #onError error sink} and the method answers its zero value.
 */
public final class ObjcClasses {

	/**
	 * A callback: receives the object the message was sent to and the method's own
	 * arguments, marshalled as {@link ObjcRuntime#send} marshals a result, and answers a
	 * value marshalled as {@code send} takes an argument ({@code null} for void).
	 */
	@FunctionalInterface
	public interface Method {

		/**
		 * @param self the receiver
		 * @param args the method's own arguments
		 * @return the result, or {@code null} for a void method
		 */
		@Nullable Object invoke(MemorySegment self, @Nullable Object[] args);

	}

	/**
	 * One method of a class being defined.
	 *
	 * @param selector the selector name
	 * @param method the callback
	 */
	public record Spec(String selector, Method method) {
	}

	private record Bound(TypeEncoding encoding, Method method) {
	}

	/** By class address, then by selector address (selectors are interned). */
	private static final Map<Long, Map<Long, Bound>> HANDLERS = new ConcurrentHashMap<>();

	/** The classes this process defined, by name. */
	private static final Map<String, MemorySegment> DEFINED = new ConcurrentHashMap<>();

	private static final Map<String, MemorySegment> STUBS = new ConcurrentHashMap<>();

	private static final Set<FunctionDescriptor> UPCALL_SIGNATURES = new LinkedHashSet<>();

	private static volatile Consumer<Throwable> errorSink = Throwable::printStackTrace;

	private static volatile @Nullable ObjcRuntime dispatchRuntime;

	/**
	 * The supported IMP shapes: the encoding's own argument list after
	 * {@code self, _cmd}, and its return kind, spelled as the static method that serves
	 * it.
	 */
	private static final List<Shape> SHAPES = List.of(new Shape("impVoid", Kind.VOID, List.of()),
			new Shape("impVoidObject", Kind.VOID, List.of(Kind.OBJECT)),
			new Shape("impVoidObjectObject", Kind.VOID, List.of(Kind.OBJECT, Kind.OBJECT)),
			new Shape("impBoolObject", Kind.BOOL, List.of(Kind.OBJECT)),
			new Shape("impObjectObject", Kind.OBJECT, List.of(Kind.OBJECT)),
			new Shape("impLongObject", Kind.INT64, List.of(Kind.OBJECT)));

	private record Shape(String method, Kind ret, List<Kind> args) {

		boolean serves(TypeEncoding encoding) {
			if (!sameFamily(encoding.returnType().kind(), this.ret)) {
				return false;
			}
			List<Type> params = encoding.argumentTypes();
			if (params.size() != this.args.size() + 2) {
				return false;
			}
			for (int i = 0; i < this.args.size(); i++) {
				if (!sameFamily(params.get(i + 2).kind(), this.args.get(i))) {
					return false;
				}
			}
			return true;
		}

		/** Every address kind shares one register class, so one stub serves them all. */
		private static boolean sameFamily(Kind a, Kind b) {
			return a == b || (a.isAddress() && b.isAddress());
		}

		FunctionDescriptor descriptor() {
			List<Type> types = new ArrayList<>();
			types.add(Type.of(Kind.OBJECT));
			types.add(Type.of(Kind.SELECTOR));
			for (Kind arg : this.args) {
				types.add(Type.of(arg));
			}
			return new TypeEncoding(Type.of(this.ret), types).descriptor();
		}

	}

	private ObjcClasses() {
	}

	/**
	 * Where a callback's uncaught failure goes. Default: standard error.
	 * @param sink the reporter
	 */
	public static void onError(Consumer<Throwable> sink) {
		errorSink = sink;
	}

	/**
	 * The upcall shapes bound so far, for the native-image registration test.
	 * @return the shapes
	 */
	public static Set<FunctionDescriptor> upcallSignatures() {
		synchronized (UPCALL_SIGNATURES) {
			return Set.copyOf(UPCALL_SIGNATURES);
		}
	}

	/**
	 * Every shape a callback can have, bound against the linker -- what the registration
	 * test asks for, so the checked-in metadata is compared with every shape a program
	 * could ask for rather than with the ones some test happened to define.
	 * @return the shapes
	 */
	public static Set<FunctionDescriptor> allCallbackShapes() {
		Set<FunctionDescriptor> shapes = new LinkedHashSet<>();
		for (Shape shape : SHAPES) {
			shapes.add(shape.descriptor());
		}
		return shapes;
	}

	/**
	 * Defines (or, for a class this process defined before, re-binds) a class.
	 * @param runtime the binding
	 * @param name the new class's name
	 * @param superclass the superclass name, e.g. {@code NSObject}
	 * @param protocols the protocols to adopt, whose declarations also type the methods
	 * @param methods the methods
	 * @return the class
	 * @throws ObjcException when the name belongs to a class this process did not define,
	 * a protocol or the superclass does not exist, or a method's shape is unsupported
	 */
	public static MemorySegment define(ObjcRuntime runtime, String name, String superclass, List<String> protocols,
			List<Spec> methods) {
		dispatchRuntime = runtime;
		MemorySegment sup = runtime.objcClass(superclass);
		List<MemorySegment> protos = new ArrayList<>();
		for (String protocol : protocols) {
			protos.add(runtime.protocol(protocol));
		}
		MemorySegment existing = DEFINED.get(name);
		boolean fresh = existing == null;
		MemorySegment cls = existing != null ? existing : allocate(runtime, sup, name);
		Map<Long, Bound> bound = HANDLERS.computeIfAbsent(cls.address(), k -> new ConcurrentHashMap<>());
		for (Spec spec : methods) {
			String raw = encodingFor(runtime, sup, protocols, spec.selector());
			TypeEncoding encoding = TypeEncoding.parse(raw);
			Shape shape = SHAPES.stream()
				.filter(s -> s.serves(encoding))
				.findFirst()
				.orElseThrow(() -> new ObjcException(name + " " + spec.selector() + ": the callback shape "
						+ TypeEncoding.spelling(encoding.descriptor()) + " (encoding " + raw
						+ ") is outside the supported set: " + supportedShapes()));
			long sel = runtime.selector(spec.selector()).address();
			if (!bound.containsKey(sel)) {
				MemorySegment imp = stub(shape);
				if (!runtime.addMethod(cls, spec.selector(), imp, raw)) {
					throw new ObjcException("class_addMethod refused " + name + " " + spec.selector());
				}
			}
			bound.put(sel, new Bound(encoding, spec.method()));
		}
		if (fresh) {
			for (MemorySegment proto : protos) {
				runtime.addProtocol(cls, proto);
			}
			runtime.registerClassPair(cls);
			DEFINED.put(name, cls);
		}
		return cls;
	}

	private static MemorySegment allocate(ObjcRuntime runtime, MemorySegment superclass, String name) {
		if (runtime.classOrNull(name) != null) {
			throw new ObjcException("the class " + name + " already exists and was not defined by this process");
		}
		MemorySegment cls = runtime.allocateClassPair(superclass, name);
		if (cls == null) {
			throw new ObjcException("objc_allocateClassPair refused the name " + name);
		}
		return cls;
	}

	private static String supportedShapes() {
		List<String> names = new ArrayList<>();
		for (Shape shape : SHAPES) {
			names.add(TypeEncoding.spelling(shape.descriptor()));
		}
		return String.join(", ", names);
	}

	/** The superclass's declaration, a protocol's, or the target/action default. */
	private static String encodingFor(ObjcRuntime runtime, MemorySegment superclass, List<String> protocols,
			String selector) {
		String declared = runtime.rawEncoding(superclass, selector);
		if (declared != null) {
			return declared;
		}
		for (String protocol : protocols) {
			declared = runtime.protocolEncoding(protocol, selector);
			if (declared != null) {
				return declared;
			}
		}
		StringBuilder types = new StringBuilder("v@:");
		int colons = (int) selector.chars().filter(c -> c == ':').count();
		for (int i = 0; i < colons; i++) {
			types.append('@');
		}
		return types.toString();
	}

	private static MemorySegment stub(Shape shape) {
		return STUBS.computeIfAbsent(shape.method(), key -> {
			MethodHandle target = target(key);
			FunctionDescriptor descriptor = shape.descriptor();
			synchronized (UPCALL_SIGNATURES) {
				UPCALL_SIGNATURES.add(descriptor);
			}
			return ObjcRuntime.upcall(target, descriptor, "the " + key + " callback");
		});
	}

	// One CONSTANT lookup per shape, so a native image folds each into a direct handle.
	private static MethodHandle target(String name) {
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		Class<MemorySegment> seg = MemorySegment.class;
		try {
			return switch (name) {
				case "impVoid" ->
					lookup.findStatic(ObjcClasses.class, "impVoid", MethodType.methodType(void.class, seg, seg));
				case "impVoidObject" -> lookup.findStatic(ObjcClasses.class, "impVoidObject",
						MethodType.methodType(void.class, seg, seg, seg));
				case "impVoidObjectObject" -> lookup.findStatic(ObjcClasses.class, "impVoidObjectObject",
						MethodType.methodType(void.class, seg, seg, seg, seg));
				case "impBoolObject" -> lookup.findStatic(ObjcClasses.class, "impBoolObject",
						MethodType.methodType(boolean.class, seg, seg, seg));
				case "impObjectObject" ->
					lookup.findStatic(ObjcClasses.class, "impObjectObject", MethodType.methodType(seg, seg, seg, seg));
				case "impLongObject" -> lookup.findStatic(ObjcClasses.class, "impLongObject",
						MethodType.methodType(long.class, seg, seg, seg));
				default -> throw new ObjcException("no IMP named " + name);
			};
		}
		catch (ReflectiveOperationException ex) {
			throw new ObjcException("the " + name + " callback cannot be bound", ex);
		}
	}

	// --- the IMPs -------------------------------------------------------------------
	// Public because an upcall stub needs a lookup-visible method; never call them.

	/**
	 * IMP for {@code v@:}.
	 * @param self the receiver
	 * @param cmd the selector
	 */
	public static void impVoid(MemorySegment self, MemorySegment cmd) {
		dispatch(self, cmd, new Object[0]);
	}

	/**
	 * IMP for {@code v@:@}.
	 * @param self the receiver
	 * @param cmd the selector
	 * @param a the argument
	 */
	public static void impVoidObject(MemorySegment self, MemorySegment cmd, MemorySegment a) {
		dispatch(self, cmd, new Object[] { nullable(a) });
	}

	/**
	 * IMP for {@code v@:@@}.
	 * @param self the receiver
	 * @param cmd the selector
	 * @param a the first argument
	 * @param b the second argument
	 */
	public static void impVoidObjectObject(MemorySegment self, MemorySegment cmd, MemorySegment a, MemorySegment b) {
		dispatch(self, cmd, new Object[] { nullable(a), nullable(b) });
	}

	/**
	 * IMP for {@code B@:@}.
	 * @param self the receiver
	 * @param cmd the selector
	 * @param a the argument
	 * @return the callback's answer, false when it failed or answered nil
	 */
	public static boolean impBoolObject(MemorySegment self, MemorySegment cmd, MemorySegment a) {
		Object result = dispatch(self, cmd, new Object[] { nullable(a) });
		return result instanceof Boolean b ? b : result != null;
	}

	/**
	 * IMP for {@code @@:@}.
	 * @param self the receiver
	 * @param cmd the selector
	 * @param a the argument
	 * @return the callback's answer, nil when it failed
	 */
	public static MemorySegment impObjectObject(MemorySegment self, MemorySegment cmd, MemorySegment a) {
		Object result = dispatch(self, cmd, new Object[] { nullable(a) });
		return result instanceof MemorySegment seg ? seg : MemorySegment.NULL;
	}

	/**
	 * IMP for {@code q@:@}.
	 * @param self the receiver
	 * @param cmd the selector
	 * @param a the argument
	 * @return the callback's answer, 0 when it failed
	 */
	public static long impLongObject(MemorySegment self, MemorySegment cmd, MemorySegment a) {
		Object result = dispatch(self, cmd, new Object[] { nullable(a) });
		return result instanceof Number n ? n.longValue() : 0L;
	}

	private static @Nullable MemorySegment nullable(MemorySegment seg) {
		return seg.address() == 0 ? null : seg;
	}

	private static @Nullable Object dispatch(MemorySegment self, MemorySegment cmd, @Nullable Object[] args) {
		try {
			ObjcRuntime runtime = dispatchRuntime;
			if (runtime == null) {
				return null;
			}
			Bound bound = lookup(runtime, self, cmd.address());
			if (bound == null) {
				return null;
			}
			return bound.method().invoke(self, args);
		}
		catch (Throwable ex) {
			try {
				errorSink.accept(ex);
			}
			catch (Throwable ignored) {
				// the sink itself failed; nothing may escape into the native frame
			}
			return null;
		}
	}

	private static @Nullable Bound lookup(ObjcRuntime runtime, MemorySegment self, long selector) {
		MemorySegment cls = runtime.classOf(self);
		while (cls != null) {
			Map<Long, Bound> bound = HANDLERS.get(cls.address());
			if (bound != null) {
				Bound found = bound.get(selector);
				if (found != null) {
					return found;
				}
			}
			cls = runtime.superclassOf(cls);
		}
		return null;
	}

}
