package am.ik.rontolisp.clojure;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The classes of a class loader, described by reflection: the lowering's classes on a JVM
 * ({@link ClojureFiles#javaClasses()}). A name is loaded without initializing the class,
 * as {@code Class.forName(name, false, loader)} loads it; a class that does not link is
 * none. A name found is remembered, an absent one is asked again -- the program's class
 * path grows while a lowering resolves its dependencies.
 */
final class ClojureReflectedClasses implements ClojureJavaClasses {

	private final ClassLoader loader;

	private final ConcurrentHashMap<String, Type> found = new ConcurrentHashMap<>();

	ClojureReflectedClasses(ClassLoader loader) {
		this.loader = loader;
	}

	@Override
	public @Nullable JavaClass find(String name) {
		Type known = this.found.get(name);
		if (known != null) {
			return known;
		}
		Class<?> type;
		try {
			type = Class.forName(name, false, this.loader);
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return null;
		}
		Type made = new Type(type);
		Type raced = this.found.putIfAbsent(name, made);
		return raced != null ? raced : made;
	}

	private static Member member(Executable executable) {
		List<String> parameters = new ArrayList<>(executable.getParameterCount());
		for (Class<?> parameter : executable.getParameterTypes()) {
			parameters.add(parameter.getName());
		}
		int modifiers = executable.getModifiers();
		boolean bridge = executable instanceof Method method && method.isBridge();
		String returned = executable instanceof Method method ? method.getReturnType().getName()
				: executable.getDeclaringClass().getName();
		return new Member(executable instanceof Method ? executable.getName() : "<init>",
				executable.getDeclaringClass().getName(), List.copyOf(parameters), returned,
				Modifier.isStatic(modifiers), executable.isVarArgs(), executable.isSynthetic(), bridge,
				Modifier.isAbstract(modifiers));
	}

	/** A loaded class. */
	private static final class Type implements JavaClass {

		private final Class<?> type;

		private volatile @Nullable List<Member> methods;

		private volatile @Nullable List<Member> constructors;

		Type(Class<?> type) {
			this.type = type;
		}

		@Override
		public String name() {
			return this.type.getName();
		}

		@Override
		public boolean isInterface() {
			return this.type.isInterface();
		}

		// The class file's ACC_PUBLIC, as the class-file description reads it: javac
		// writes
		// a protected member class as public there.
		@Override
		public boolean isPublic() {
			int modifiers = this.type.getModifiers();
			return Modifier.isPublic(modifiers) || (this.type.isMemberClass() && Modifier.isProtected(modifiers));
		}

		@Override
		public boolean isAbstract() {
			return Modifier.isAbstract(this.type.getModifiers());
		}

		@Override
		public boolean isFinal() {
			return Modifier.isFinal(this.type.getModifiers());
		}

		@Override
		public @Nullable String superclass() {
			Class<?> superclass = this.type.getSuperclass();
			return superclass == null ? null : superclass.getName();
		}

		@Override
		public List<String> interfaces() {
			List<String> names = new ArrayList<>();
			for (Class<?> each : this.type.getInterfaces()) {
				names.add(each.getName());
			}
			return names;
		}

		@Override
		public List<Member> methods() {
			List<Member> cached = this.methods;
			if (cached == null) {
				List<Member> list = new ArrayList<>();
				for (Method method : this.type.getMethods()) {
					list.add(member(method));
				}
				cached = List.copyOf(list);
				this.methods = cached;
			}
			return cached;
		}

		@Override
		public List<Member> constructors() {
			List<Member> cached = this.constructors;
			if (cached == null) {
				List<Member> list = new ArrayList<>();
				for (Constructor<?> constructor : this.type.getConstructors()) {
					list.add(member(constructor));
				}
				cached = List.copyOf(list);
				this.constructors = cached;
			}
			return cached;
		}

		@Override
		public boolean hasPublicFields() {
			return this.type.getFields().length > 0;
		}

		@Override
		public @Nullable Field field(String name) {
			try {
				java.lang.reflect.Field field = this.type.getField(name);
				return new Field(field.getName(), field.getType().getName(), Modifier.isStatic(field.getModifiers()));
			}
			catch (NoSuchFieldException ex) {
				return null;
			}
		}

		@Override
		public boolean isAssignableFrom(JavaClass other) {
			return other instanceof Type loaded && this.type.isAssignableFrom(loaded.type);
		}

		@Override
		public String toString() {
			return name();
		}

	}

}
