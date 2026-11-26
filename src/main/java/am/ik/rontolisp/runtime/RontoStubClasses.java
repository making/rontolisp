package am.ik.rontolisp.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub classes: a class or interface with a given binary name, access flags, superclass,
 * interfaces and member signatures and no code, defined at run time --
 * {@code rontolisp::%host-stub-class} on the interpreter and the JVM backend. A front end
 * whose values stand for classes the host lacks (the Clojure front end's
 * {@code clojure.lang} classes) makes them real {@code java.lang.Class} objects with it,
 * so the reflection questions a class answers itself -- its name, whether it is an
 * interface, its superclass, interfaces and members, {@code isAssignableFrom} -- are the
 * host's.
 *
 * <p>
 * Every stub lives in ONE loader of the process, a child of the loader of this class, so
 * no class of the program's class path links against a stub (a dependency compiled
 * against the real classes keeps failing to link). A name is defined once: a later call
 * answers the first class. A supertype that is a stub must be defined first; one the
 * loader cannot find fails the definition. The class file is the JVM Specification's
 * {@code ClassFile} structure (section 4.1) of version 52, its members signatures alone
 * (a method is {@code native}).
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside a compiled program that
 * uses it ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoStubClasses {

	/** {@code ACC_PUBLIC}. */
	private static final int PUBLIC = 0x0001;

	/** {@code ACC_FINAL}. */
	private static final int FINAL = 0x0010;

	/** {@code ACC_SUPER}, which every class but an interface carries. */
	private static final int SUPER = 0x0020;

	/** {@code ACC_STATIC}. */
	private static final int STATIC = 0x0008;

	/** {@code ACC_NATIVE}. */
	private static final int NATIVE = 0x0100;

	/** {@code ACC_INTERFACE}. */
	private static final int INTERFACE = 0x0200;

	/** {@code ACC_ABSTRACT}. */
	private static final int ABSTRACT = 0x0400;

	/** The flags a stub may carry. */
	private static final int FLAGS = PUBLIC | FINAL | INTERFACE | ABSTRACT;

	private static final int CONSTANT_UTF8 = 1;

	private static final int CONSTANT_CLASS = 7;

	private static final int MAGIC = 0xCAFEBABE;

	private static final int MAJOR_VERSION = 52;

	/**
	 * The loader every stub is defined in, made at the first definition (so a native
	 * image, which defines none, never makes one).
	 */
	// null until the first definition; this package cannot import the annotation that
	// would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	private static Loader loader;

	/** The stubs defined, by binary name. */
	private static final Map<String, Class<?>> DEFINED = new HashMap<>();

	private RontoStubClasses() {
	}

	/**
	 * The stub class NAME: the one defined before, else a new one.
	 * @param name the binary name ({@code clojure.lang.PersistentVector$TransientVector})
	 * @param flags the access flags: {@code ACC_PUBLIC}, {@code ACC_FINAL},
	 * {@code ACC_INTERFACE} and {@code ACC_ABSTRACT} are kept, as
	 * {@code Class.getModifiers} reports them; {@code ACC_SUPER} is added to a class
	 * @param superName the superclass's binary name; an interface's is
	 * {@code java.lang.Object} whatever this says
	 * @param interfaces the interfaces' binary names, separated by spaces, in the order
	 * {@code getInterfaces} answers them
	 * @param members the members the class declares, separated by spaces, each
	 * {@code kind:name:descriptor}: {@code s} a public static method, {@code m} a public
	 * instance method (abstract in an interface), {@code f} a public static final field,
	 * {@code v} a public static field. A method has no code: it is {@code native}, so
	 * reflection reads its signature and a call of it fails to link.
	 * @return the class, or null where this VM defines no class at run time (a native
	 * image) or a supertype cannot be loaded
	 */
	// null is the answer where no class is defined; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static synchronized Class<?> define(String name, int flags, String superName, String interfaces,
			String members) {
		Class<?> known = DEFINED.get(name);
		if (known != null) {
			return known;
		}
		if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
			return null;
		}
		boolean isInterface = (flags & INTERFACE) != 0;
		byte[] bytes = classFile(name, isInterface ? (flags & FLAGS) | ABSTRACT : (flags & FLAGS) | SUPER,
				isInterface ? "java.lang.Object" : superName, names(interfaces), names(members), isInterface);
		if (loader == null) {
			loader = new Loader(RontoStubClasses.class.getClassLoader());
		}
		Class<?> defined;
		try {
			defined = loader.define(name, bytes);
		}
		catch (LinkageError ex) {
			return null;
		}
		DEFINED.put(name, defined);
		return defined;
	}

	/**
	 * {@link #define(String, int, String, String, String)} on the JVM backend: the
	 * arguments as the compiled representation holds them (a string with its quotes, a
	 * fixnum a {@code Long}).
	 * @param name the binary name
	 * @param flags the access flags
	 * @param superName the superclass's binary name
	 * @param interfaces the interfaces' binary names, separated by spaces
	 * @param members the members, separated by spaces
	 * @return the class, or nil
	 */
	// nil is null in the compiled representation; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static Object stub(Object name, Object flags, Object superName, Object interfaces, Object members) {
		return define(text(name), ((Number) flags).intValue(), text(superName), text(interfaces), text(members));
	}

	// A rontolisp string value carries its quotes.
	private static String text(Object value) {
		String quoted = (String) value;
		return quoted.startsWith("\"") ? quoted.substring(1, quoted.length() - 1) : quoted;
	}

	private static List<String> names(String spaced) {
		List<String> names = new ArrayList<>();
		for (String name : spaced.split(" ")) {
			if (!name.isEmpty()) {
				names.add(name);
			}
		}
		return names;
	}

	/**
	 * The {@code ClassFile} of a stub (JVM Specification 4.1): this class, its
	 * superclass, its interfaces and its members, each method with no attribute
	 * ({@code native}, or {@code abstract} in an interface: 4.6, 4.7.3) and each field
	 * with none (4.5).
	 */
	private static byte[] classFile(String name, int flags, String superName, List<String> interfaces,
			List<String> members, boolean isInterface) {
		Pool pool = new Pool();
		int thisClass = pool.cls(name);
		int superClass = pool.cls(superName);
		List<Integer> interfaceEntries = new ArrayList<>();
		for (String iface : interfaces) {
			interfaceEntries.add(pool.cls(iface));
		}
		ByteArrayOutputStream fields = new ByteArrayOutputStream();
		ByteArrayOutputStream methods = new ByteArrayOutputStream();
		int fieldCount = 0;
		int methodCount = 0;
		try (DataOutputStream fieldOut = new DataOutputStream(fields);
				DataOutputStream methodOut = new DataOutputStream(methods)) {
			for (String member : members) {
				String[] parts = member.split(":", 3);
				int access = switch (parts[0]) {
					case "s" -> PUBLIC | STATIC | NATIVE;
					case "m" -> isInterface ? PUBLIC | ABSTRACT : PUBLIC | NATIVE;
					case "f" -> PUBLIC | STATIC | FINAL;
					case "v" -> PUBLIC | STATIC;
					default -> throw new IllegalArgumentException("no member kind " + parts[0]);
				};
				DataOutputStream out = parts[0].equals("f") || parts[0].equals("v") ? fieldOut : methodOut;
				out.writeShort(access);
				out.writeShort(pool.utf8(parts[1]));
				out.writeShort(pool.utf8(parts[2]));
				out.writeShort(0);
				if (out == fieldOut) {
					fieldCount++;
				}
				else {
					methodCount++;
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC);
			out.writeShort(0);
			out.writeShort(MAJOR_VERSION);
			out.writeShort(pool.count + 1);
			pool.entries.writeTo(out);
			out.writeShort(flags);
			out.writeShort(thisClass);
			out.writeShort(superClass);
			out.writeShort(interfaceEntries.size());
			for (int entry : interfaceEntries) {
				out.writeShort(entry);
			}
			out.writeShort(fieldCount);
			fields.writeTo(out);
			out.writeShort(methodCount);
			methods.writeTo(out);
			// no attribute
			out.writeShort(0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	/**
	 * A constant pool (JVM Specification 4.4) of {@code CONSTANT_Utf8} and
	 * {@code CONSTANT_Class} entries, each written once, counted from 1.
	 */
	private static final class Pool {

		final ByteArrayOutputStream entries = new ByteArrayOutputStream();

		private final DataOutputStream out = new DataOutputStream(this.entries);

		private final Map<String, Integer> utf8s = new HashMap<>();

		private final Map<String, Integer> classes = new HashMap<>();

		int count;

		int utf8(String text) {
			Integer known = this.utf8s.get(text);
			if (known != null) {
				return known;
			}
			try {
				this.out.writeByte(CONSTANT_UTF8);
				// the ASCII a name or descriptor spells here is its own modified UTF-8
				this.out.writeUTF(text);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			this.count++;
			this.utf8s.put(text, this.count);
			return this.count;
		}

		int cls(String binaryName) {
			Integer known = this.classes.get(binaryName);
			if (known != null) {
				return known;
			}
			int name = utf8(binaryName.replace('.', '/'));
			try {
				this.out.writeByte(CONSTANT_CLASS);
				this.out.writeShort(name);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			this.count++;
			this.classes.put(binaryName, this.count);
			return this.count;
		}

	}

	/** The loader every stub is defined in. */
	private static final class Loader extends ClassLoader {

		Loader(ClassLoader parent) {
			super(parent);
		}

		Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}

	}

}
