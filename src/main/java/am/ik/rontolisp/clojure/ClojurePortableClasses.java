package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * A dependency's Java class where no host is (wasm), read as the Clojure it amounts to
 * ({@link ClojureJavaClassReading}; {@code .kb/clojure-frontend.md}, "Portable reading of
 * a dependency's Java class"): a class a source root of the program holds as a class file
 * -- a library shipping its helper classes beside its sources, specter's
 * {@code MutableCell} and {@code Util} -- loads as the namespace its reading defines
 * where the program first names it (an {@code import}, a construction, a static member, a
 * receiver's hint), and a construction, a static call and a static field read lower to
 * that namespace's functions and vars; an instance call reaches the type's methods
 * through its interface, like any {@code definterface} row. Where the host is, nothing
 * changes: the class is the host's. A class the reading does not cover stays the host's
 * too, refused when called where no host is, as before.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableClasses {

	private ClojurePortableClasses() {
	}

	/**
	 * Whether a namespace is a reading's: its type is found by its class name, but a
	 * construction outside the namespace takes the reading's constructors
	 * ({@link #typeConstruction}), and the simple-name leniency of
	 * {@link ClojureLowering#typeKeyOf} skips it.
	 * @param ns the namespace
	 * @return whether a reading defines it
	 */
	static boolean isReadingNamespace(String ns) {
		return ns.startsWith(ClojureJavaClassReading.NAMESPACE_PREFIX);
	}

	/**
	 * Whether a record or deftype is a reading's: the class a program names, whose
	 * statics and constructors are the class's.
	 * @param typeKey the type's var key
	 * @return whether a reading defines it
	 */
	static boolean isReadingType(String typeKey) {
		return isReadingNamespace(typeKey.substring(0, typeKey.lastIndexOf('/')));
	}

	/**
	 * A construction of a reading's type outside the reading's own namespace: the
	 * reading's constructor of the count -- the class's, not the type's fields -- or the
	 * oracle's refusal of a count no constructor takes. Null for any other type, and
	 * inside the reading's namespace, whose own constructor builds the type over its
	 * fields.
	 * @param ctx the hub
	 * @param typeKey the type's var key
	 * @param argDatums the argument datums
	 * @return the construction, or null
	 */
	static @Nullable LispVal typeConstruction(ClojureLowering ctx, String typeKey, List<LispVal> argDatums) {
		if (!isReadingType(typeKey) || ctx.currentNs.equals(typeKey.substring(0, typeKey.lastIndexOf('/')))) {
			return null;
		}
		ClojureLowering.TypeDef def = Objects.requireNonNull(ctx.types.get(typeKey));
		ClojureJavaClassReading.Reading reading = ctx.sourcePath.classReading(def.className());
		if (reading == null || !reading.constructors().contains(argDatums.size())) {
			throw new LispReadException("No matching ctor found for class " + def.className());
		}
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(reading.namespace() + "/" + ClojureJavaClassReading.INIT));
		call.addAll(argDatums);
		return ctx.lower(ClojureLowerUtil.list(call));
	}

	/**
	 * The reading of a class where no host is, its namespace loaded: null where the host
	 * is, outside a top-level datum (a run-time lowering loads no namespace), for a class
	 * of the JDK or of the language, and for a class no source root holds or the reading
	 * does not cover.
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @return the reading, or null
	 */
	static ClojureJavaClassReading.@Nullable Reading loaded(ClojureLowering ctx, @Nullable String cls) {
		if (cls == null || ctx.hostTarget || !ctx.atTopLevel() || platform(cls)) {
			return null;
		}
		ClojureJavaClassReading.Reading reading = ctx.sourcePath.classReading(cls);
		if (reading == null) {
			return null;
		}
		String ns = reading.namespace();
		if (!ctx.loadedNamespaces.contains(ns) && !ctx.loadingNamespaces.contains(ns)) {
			ClojureNamespaceLowering.preload(ctx, ns);
		}
		return reading;
	}

	/** A class of the JDK or the language, which the reading never reads. */
	private static boolean platform(String cls) {
		return cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.") || cls.startsWith("sun.")
				|| cls.startsWith("com.sun.") || cls.startsWith("clojure.") || cls.indexOf('.') < 0;
	}

	/**
	 * An untagged construction of a read class where no host is: the reading's
	 * constructor of the count, or null (the host's construction, refused when called).
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @param argDatums the argument datums
	 * @return the construction, or null
	 */
	static @Nullable LispVal construction(ClojureLowering ctx, String cls, List<LispVal> argDatums) {
		ClojureJavaClassReading.Reading reading = loaded(ctx, cls);
		if (reading == null || !reading.constructors().contains(argDatums.size())) {
			return null;
		}
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(reading.namespace() + "/" + ClojureJavaClassReading.INIT));
		call.addAll(argDatums);
		return ctx.lower(ClojureLowerUtil.list(call));
	}

	/**
	 * A static call of a read class where no host is: the reading's function of the name
	 * and count, over the lowered arguments, or null.
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @param member the method's name
	 * @param args the lowered arguments
	 * @return the call, or null
	 */
	static @Nullable LispVal staticCall(ClojureLowering ctx, String cls, String member, List<LispVal> args) {
		ClojureJavaClassReading.Reading reading = loaded(ctx, cls);
		if (reading == null || !reading.statics().getOrDefault(member, Set.of()).contains(args.size())) {
			return null;
		}
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(reading.namespace() + "/" + member));
		for (LispVal arg : args) {
			call.add(ClojureLowerUtil.list(ClojureRunTime.EMBEDDED, arg));
		}
		return ctx.lower(ClojureLowerUtil.list(call));
	}

	/**
	 * A static field of a read class where no host is: a constant's var, another field's
	 * cell dereferenced, or null.
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @param member the field's name
	 * @return the read, or null
	 */
	static @Nullable LispVal staticField(ClojureLowering ctx, String cls, String member) {
		ClojureJavaClassReading.Reading reading = loaded(ctx, cls);
		Boolean constant = reading == null ? null : reading.fields().get(member);
		if (reading == null || constant == null) {
			return null;
		}
		LispSymbol var = new LispSymbol(reading.namespace() + "/" + ClojureJavaClassReading.fieldVar(member));
		return ctx.lower(constant ? var : ClojureLowerUtil.list(new LispSymbol("clojure.core/deref"), var));
	}

}
