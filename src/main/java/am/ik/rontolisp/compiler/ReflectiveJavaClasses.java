package am.ik.rontolisp.compiler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The {@link JavaClassLookup} over the running JVM: {@link Class#forName} (without
 * initializing the class) and {@link Class}'s own member queries. It is the interpreter's
 * -- the classes it resolves against are the classes it calls -- and the reference the
 * class-file lookup is tested against.
 * <p>
 * A type is canonical per {@link Class} (so kinds compare by identity), and it answers
 * the reflective member it describes ({@link Type#type()}, {@link Member#executable()},
 * {@link FieldMember#field()}) so the interpreter invokes exactly what was resolved.
 */
public final class ReflectiveJavaClasses implements JavaClassLookup {

	private static final ClassValue<Type> TYPES = new ClassValue<>() {
		@Override
		protected Type computeValue(Class<?> type) {
			return new Type(type);
		}
	};

	private static final ClassValue<JavaImplementationType> IMPLEMENTATIONS = new ClassValue<>() {
		@Override
		protected JavaImplementationType computeValue(Class<?> iface) {
			return new JavaImplementationType(of(iface));
		}
	};

	private static final Map<String, Class<?>> PRIMITIVES = Map.of("boolean", boolean.class, "byte", byte.class, "char",
			char.class, "short", short.class, "int", int.class, "long", long.class, "float", float.class, "double",
			double.class, "void", void.class);

	private static final ReflectiveJavaClasses INSTANCE = new ReflectiveJavaClasses();

	// Class.forName is ~500 ns; a name's answer (including "absent") is remembered.
	private final ConcurrentHashMap<String, Object> byName = new ConcurrentHashMap<>();

	private static final Object ABSENT = new Object();

	private static final int CACHE_LIMIT = 4096;

	private ReflectiveJavaClasses() {
	}

	/**
	 * @return the lookup over the classes visible to this class's loader
	 */
	public static ReflectiveJavaClasses instance() {
		return INSTANCE;
	}

	/**
	 * The type of a loaded class.
	 * @param type the class
	 * @return its canonical type
	 */
	public static Type of(Class<?> type) {
		return TYPES.get(type);
	}

	@Override
	public @Nullable Type find(String name) {
		Object cached = this.byName.get(name);
		if (cached == null) {
			Class<?> type = load(name);
			cached = type == null ? ABSENT : type;
			if (this.byName.size() >= CACHE_LIMIT) {
				this.byName.clear();
			}
			this.byName.put(name, cached);
		}
		return cached instanceof Class<?> type ? of(type) : null;
	}

	@Override
	public JavaImplementationType implementationOf(JavaType iface) {
		return IMPLEMENTATIONS.get(((Type) iface).type());
	}

	private static @Nullable Class<?> load(String name) {
		Class<?> primitive = PRIMITIVES.get(name);
		if (primitive != null) {
			return primitive;
		}
		try {
			return Class.forName(name, false, ReflectiveJavaClasses.class.getClassLoader());
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return null;
		}
	}

	/** A loaded class. */
	public static final class Type implements JavaType {

		private final Class<?> type;

		private final ConcurrentHashMap<String, List<Member>> methods = new ConcurrentHashMap<>();

		private volatile @Nullable List<Member> constructors;

		private volatile @Nullable List<Member> publicMethods;

		private final ConcurrentHashMap<String, java.util.Optional<FieldMember>> fields = new ConcurrentHashMap<>();

		Type(Class<?> type) {
			this.type = type;
		}

		/**
		 * @return the class
		 */
		public Class<?> type() {
			return this.type;
		}

		@Override
		public String name() {
			return this.type.getName();
		}

		@Override
		public boolean isPrimitive() {
			return this.type.isPrimitive();
		}

		@Override
		public boolean isArray() {
			return this.type.isArray();
		}

		@Override
		public @Nullable JavaType componentType() {
			Class<?> component = this.type.getComponentType();
			return component == null ? null : of(component);
		}

		@Override
		public boolean isInterface() {
			return this.type.isInterface();
		}

		@Override
		public boolean isFinal() {
			return this.type.isPrimitive() || this.type.isArray() || Modifier.isFinal(this.type.getModifiers());
		}

		// The class file's ACC_PUBLIC, which getModifiers() does not answer for a member
		// class: javac writes a protected one as public there.
		@Override
		public boolean isPublic() {
			if (this.type.isPrimitive()) {
				return true;
			}
			if (this.type.isArray()) {
				return of(this.type.componentType()).isPublic();
			}
			int modifiers = this.type.getModifiers();
			return Modifier.isPublic(modifiers) || (this.type.isMemberClass() && Modifier.isProtected(modifiers));
		}

		@Override
		public boolean isAbstract() {
			return !this.type.isPrimitive() && !this.type.isArray() && Modifier.isAbstract(this.type.getModifiers());
		}

		@Override
		public boolean isAssignableFrom(JavaType other) {
			if (other instanceof JavaImplementationType implementation) {
				return implementation.isAssignableTo(this);
			}
			return other instanceof Type t && this.type.isAssignableFrom(t.type);
		}

		@Override
		public List<Member> methods(String name) {
			List<Member> cached = this.methods.get(name);
			if (cached == null) {
				List<Member> candidates = new ArrayList<>();
				for (Method method : this.type.getMethods()) {
					if (method.getName().equals(name)) {
						Method accessible = accessibleMethod(method);
						if (accessible != null) {
							candidates.add(new Member(accessible));
						}
					}
				}
				cached = List.copyOf(candidates);
				this.methods.put(name, cached);
			}
			return cached;
		}

		@Override
		public List<Member> publicMethods() {
			List<Member> cached = this.publicMethods;
			if (cached == null) {
				List<Member> list = new ArrayList<>();
				for (Method method : this.type.getMethods()) {
					list.add(new Member(method));
				}
				cached = List.copyOf(list);
				this.publicMethods = cached;
			}
			return cached;
		}

		@Override
		public List<Member> constructors() {
			List<Member> cached = this.constructors;
			if (cached == null) {
				List<Member> list = new ArrayList<>();
				for (Constructor<?> constructor : this.type.getConstructors()) {
					list.add(new Member(constructor));
				}
				cached = List.copyOf(list);
				this.constructors = cached;
			}
			return cached;
		}

		@Override
		public @Nullable FieldMember field(String name) {
			java.util.Optional<FieldMember> cached = this.fields.get(name);
			if (cached == null) {
				FieldMember found;
				try {
					found = new FieldMember(this.type.getField(name));
				}
				catch (NoSuchFieldException ex) {
					found = null;
				}
				cached = java.util.Optional.ofNullable(found);
				this.fields.put(name, cached);
			}
			return cached.orElse(null);
		}

		@Override
		public boolean isAccessible() {
			// Public as the class file says it (what reflective access checks too), so a
			// protected member class of an exported package is accessible.
			Module module = this.type.getModule();
			return !module.isNamed() || (isPublic() && module.isExported(this.type.getPackageName()));
		}

		@Override
		public String toString() {
			return name();
		}

	}

	/** A public method or constructor. */
	public static final class Member implements JavaExecutable {

		private final Executable executable;

		private final List<Type> parameterTypes;

		Member(Executable executable) {
			this.executable = executable;
			Class<?>[] params = executable.getParameterTypes();
			List<Type> types = new ArrayList<>(params.length);
			for (Class<?> p : params) {
				types.add(of(p));
			}
			this.parameterTypes = List.copyOf(types);
		}

		/**
		 * @return the reflective method or constructor
		 */
		public Executable executable() {
			return this.executable;
		}

		@Override
		public JavaType declaringClass() {
			return of(this.executable.getDeclaringClass());
		}

		@Override
		public String name() {
			return this.executable instanceof Constructor<?> ? "<init>" : this.executable.getName();
		}

		@Override
		public List<Type> parameterTypes() {
			return this.parameterTypes;
		}

		@Override
		public JavaType returnType() {
			return this.executable instanceof Method method ? of(method.getReturnType())
					: of(this.executable.getDeclaringClass());
		}

		@Override
		public boolean isVarArgs() {
			return this.executable.isVarArgs();
		}

		@Override
		public boolean isStatic() {
			return Modifier.isStatic(this.executable.getModifiers());
		}

		@Override
		public boolean isConstructor() {
			return this.executable instanceof Constructor<?>;
		}

		@Override
		public boolean isAbstract() {
			return Modifier.isAbstract(this.executable.getModifiers());
		}

		@Override
		public String toString() {
			return this.executable.toString();
		}

	}

	/** A public field. */
	public static final class FieldMember implements JavaField {

		private final Field field;

		FieldMember(Field field) {
			this.field = field;
		}

		/**
		 * @return the reflective field
		 */
		public Field field() {
			return this.field;
		}

		@Override
		public JavaType declaringClass() {
			return of(this.field.getDeclaringClass());
		}

		@Override
		public String name() {
			return this.field.getName();
		}

		@Override
		public JavaType type() {
			return of(this.field.getType());
		}

		@Override
		public boolean isStatic() {
			return Modifier.isStatic(this.field.getModifiers());
		}

	}

	// A public method declared in a non-exported/non-public class (e.g. the List.of
	// result type java.util.ImmutableCollections$ListN) cannot be invoked reflectively;
	// re-resolve it to the same method on an accessible superclass or interface
	// (List.size() instead of ImmutableCollections$ListN.size()).
	private static @Nullable Method accessibleMethod(Method method) {
		if (method.trySetAccessible()) {
			return method;
		}
		for (Class<?> c = method.getDeclaringClass(); c != null; c = c.getSuperclass()) {
			Method onInterface = accessibleOnInterfaces(c, method);
			if (onInterface != null) {
				return onInterface;
			}
			if (c != method.getDeclaringClass()) {
				Method declared = accessibleDeclaration(c, method);
				if (declared != null) {
					return declared;
				}
			}
		}
		return null;
	}

	private static @Nullable Method accessibleOnInterfaces(Class<?> cls, Method method) {
		for (Class<?> iface : cls.getInterfaces()) {
			Method declared = accessibleDeclaration(iface, method);
			if (declared != null) {
				return declared;
			}
			Method nested = accessibleOnInterfaces(iface, method);
			if (nested != null) {
				return nested;
			}
		}
		return null;
	}

	private static @Nullable Method accessibleDeclaration(Class<?> cls, Method method) {
		try {
			Method declared = cls.getMethod(method.getName(), method.getParameterTypes());
			return declared.trySetAccessible() ? declared : null;
		}
		catch (NoSuchMethodException ex) {
			return null;
		}
	}

}
