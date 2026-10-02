package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The class of the object a {@code java:reify} or {@code java:proxy} of interfaces
 * {@code I...} makes, as the {@code java:} resolution sees it: a class no program can
 * name, which extends {@code Object} and implements {@code I...} and
 * {@code java.io.Serializable} -- the supertypes of a {@link java.lang.reflect.Proxy}
 * class, the interpreter's, less {@code Proxy} itself; a compiled program's generated
 * class declares exactly these ({@code codegen.jvm.JvmJavaImplementations}).
 * <p>
 * It is the KIND of such an object ({@link JavaKind}), so a call that passes one resolves
 * before it runs: its cost against a parameter type depends on {@code I...} alone
 * ({@link #isAssignableTo}), never on which class made it. Canonical per interface list
 * within a lookup ({@link JavaClassLookup#implementationOf}); both lookups' types answer
 * {@link JavaType#isAssignableFrom} for it through {@link #isAssignableTo}.
 */
public final class JavaImplementationType implements JavaType {

	private final List<JavaType> interfaces;

	/**
	 * @param interfaces the interfaces the object implements, in the form's order: one or
	 * more
	 */
	public JavaImplementationType(List<? extends JavaType> interfaces) {
		if (interfaces.isEmpty()) {
			throw new IllegalArgumentException("an implementation implements an interface");
		}
		this.interfaces = List.copyOf(interfaces);
	}

	/**
	 * @return the interfaces the object implements, in the form's order
	 */
	public List<JavaType> interfaces() {
		return this.interfaces;
	}

	/**
	 * The one interface a call on the object resolves its method against, and a
	 * {@code (java:object "I" :exact)} specifier spells.
	 * @return the interface, or {@code null} when the object implements several: a call
	 * on it is then resolved by the object's class when it runs
	 */
	public @Nullable JavaType single() {
		return this.interfaces.size() == 1 ? this.interfaces.get(0) : null;
	}

	/**
	 * Whether a value of this kind is assignable to {@code target}: {@code Object},
	 * {@code java.io.Serializable}, each interface and its superinterfaces.
	 * @param target a type
	 * @return whether {@code target.isAssignableFrom(this)}
	 */
	public boolean isAssignableTo(JavaType target) {
		if (target == this || "java.lang.Object".equals(target.name())
				|| "java.io.Serializable".equals(target.name())) {
			return true;
		}
		for (JavaType iface : this.interfaces) {
			if (target.isAssignableFrom(iface)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String name() {
		return "implementation of " + JavaImplementation.interfaceNames(this.interfaces);
	}

	@Override
	public boolean isPrimitive() {
		return false;
	}

	@Override
	public boolean isArray() {
		return false;
	}

	@Override
	public @Nullable JavaType componentType() {
		return null;
	}

	@Override
	public boolean isInterface() {
		return false;
	}

	@Override
	public boolean isFinal() {
		return true;
	}

	// No program names the class, so it is never linkable.
	@Override
	public boolean isPublic() {
		return false;
	}

	@Override
	public boolean isAbstract() {
		return false;
	}

	@Override
	public boolean isAssignableFrom(JavaType other) {
		return other == this;
	}

	@Override
	public List<? extends JavaExecutable> methods(String name) {
		if (this.interfaces.size() == 1) {
			return this.interfaces.get(0).methods(name);
		}
		List<JavaExecutable> methods = new ArrayList<>();
		for (JavaType iface : this.interfaces) {
			methods.addAll(iface.methods(name));
		}
		return methods;
	}

	@Override
	public List<? extends JavaExecutable> publicMethods() {
		if (this.interfaces.size() == 1) {
			return this.interfaces.get(0).publicMethods();
		}
		List<JavaExecutable> methods = new ArrayList<>();
		for (JavaType iface : this.interfaces) {
			methods.addAll(iface.publicMethods());
		}
		return methods;
	}

	@Override
	public List<? extends JavaExecutable> constructors() {
		return List.of();
	}

	@Override
	public @Nullable JavaField field(String name) {
		for (JavaType iface : this.interfaces) {
			JavaField field = iface.field(name);
			if (field != null) {
				return field;
			}
		}
		return null;
	}

	@Override
	public boolean isAccessible() {
		for (JavaType iface : this.interfaces) {
			if (!iface.isAccessible()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		return name();
	}

}
