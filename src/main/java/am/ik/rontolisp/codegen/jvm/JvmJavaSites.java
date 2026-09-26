package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaSiteResolver;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java:} sites of one compile attempt, each resolved once against the class
 * files the compile reads ({@link JvmClassFileLookup}) by the resolver the interpreter
 * also uses ({@link JavaSiteResolver}); a resolved site is compiled to a direct call
 * ({@link JvmJavaDirectSites}), any other one through the embedded bridge. Under
 * {@code --java-static} a site that would need the bridge is refused instead, and the
 * attempt fails naming every such site ({@link #refusals()}).
 */
final class JvmJavaSites {

	private final JavaSiteResolver resolver;

	private final IdentityHashMap<LispCons, JavaSite> sites = new IdentityHashMap<>();

	private final JvmJavaDirectSites direct;

	private final boolean javaStatic;

	private final IdentityHashMap<LispCons, Boolean> refused = new IdentityHashMap<>();

	private final List<String> refusals = new ArrayList<>();

	/**
	 * @param lookup the classes sites resolve against
	 * @param cp the class's constant pool
	 * @param thisClass the class
	 * @param lispToString the program's {@code _lispToString}
	 * @param javaStatic whether a site that needs the bridge is refused
	 * ({@code --java-static})
	 */
	JvmJavaSites(JavaClassLookup lookup, ConstantPool cp, ClassConstant thisClass, MethodrefConstant lispToString,
			boolean javaStatic) {
		this.resolver = new JavaSiteResolver(lookup);
		this.direct = new JvmJavaDirectSites(cp, thisClass, lookup, lispToString);
		this.javaStatic = javaStatic;
	}

	/**
	 * How a site resolves.
	 * @param site a {@code java:new} / {@code java:call} / {@code java:static} /
	 * {@code java:field} form
	 * @return the resolution
	 */
	JavaSite resolve(LispCons site) {
		JavaSite cached = this.sites.get(site);
		if (cached == null) {
			cached = this.resolver.resolve(site);
			this.sites.put(site, cached);
		}
		return cached;
	}

	/**
	 * @return the direct calls of this attempt
	 */
	JvmJavaDirectSites direct() {
		return this.direct;
	}

	/**
	 * @return whether a site that needs the bridge is refused ({@code --java-static})
	 */
	boolean javaStatic() {
		return this.javaStatic;
	}

	/**
	 * Why a site needs the reflective bridge, or {@code null} when it compiles to a
	 * direct call that needs nothing else: a {@code java:proxy}; a site left to run time;
	 * a resolved site with an argument that may be a function value, which becomes a
	 * {@code java.lang.reflect.Proxy} of its interface parameter.
	 * @param site a {@code java:} form
	 * @return the reason, or {@code null}
	 */
	@Nullable String bridgeReason(LispCons site) {
		if (site.car() instanceof LispSymbol head && LispNames.JAVA_PROXY_QUALIFIED.equals(head.name())) {
			return "java:proxy implements its interface with java.lang.reflect.Proxy";
		}
		JavaSite resolution = resolve(site);
		if (!resolution.resolved()) {
			return "it is resolved by reflection at run time: " + resolution.reason();
		}
		List<JavaSite.Argument> arguments = resolution.arguments();
		for (int i = 0; i < arguments.size(); i++) {
			if (arguments.get(i).kinds().contains(JavaKind.Lisp.FUNCTION)) {
				return "argument " + (i + 1) + " may be a function, which becomes a java.lang.reflect.Proxy of "
						+ parameterType(resolution, i).name();
			}
		}
		return null;
	}

	// The parameter an argument is converted to: a packed varargs tail's component.
	private static JavaType parameterType(JavaSite site, int argument) {
		JavaExecutable executable = java.util.Objects.requireNonNull(site.executable());
		List<? extends JavaType> params = executable.parameterTypes();
		int last = params.size() - 1;
		if (site.packed() && argument >= last) {
			return java.util.Objects.requireNonNull(params.get(last).componentType());
		}
		return params.get(argument);
	}

	/**
	 * Whether the program has a site that needs the bridge, read off the forms before
	 * they are compiled: what decides whether the attempt embeds it. A site a pass
	 * rebuilds later is resolved again where it is compiled; one that turns out to need
	 * the bridge after all calls the absent {@code _javaInit}, and the helper-gate check
	 * retries with the bridge.
	 * @param program the package-resolved top-level forms
	 * @return whether some site needs the bridge
	 */
	boolean needsBridge(List<LispVal> program) {
		for (LispVal form : program) {
			for (LispCons site : proxySitesAnd(form)) {
				if (bridgeReason(site) != null) {
					return true;
				}
			}
		}
		return false;
	}

	// The java: sites of a form, java:proxy ones included.
	private static List<LispCons> proxySitesAnd(LispVal form) {
		List<LispCons> found = new ArrayList<>(JavaSiteResolver.sitesIn(form));
		collectProxies(form, found, new IdentityHashMap<>());
		return found;
	}

	private static void collectProxies(LispVal form, List<LispCons> found, IdentityHashMap<LispCons, Boolean> seen) {
		LispVal current = form;
		boolean head = true;
		while (current instanceof LispCons cons && seen.put(cons, Boolean.TRUE) == null) {
			if (head) {
				if (cons.car() instanceof LispSymbol sym) {
					if (LispNames.QUOTE.equals(sym.name())) {
						return;
					}
					if (LispNames.JAVA_PROXY_QUALIFIED.equals(sym.name())) {
						found.add(cons);
					}
				}
				head = false;
			}
			if (cons.car() instanceof LispCons inner) {
				collectProxies(inner, found, seen);
			}
			current = cons.cdr();
		}
	}

	/**
	 * Records a site {@code --java-static} refuses; the attempt fails with every one.
	 * @param site the site
	 * @param reason why it needs the bridge
	 */
	void refuse(LispCons site, String reason) {
		if (this.refused.put(site, Boolean.TRUE) == null) {
			this.refusals.add(SourceProvenance.prefix(site) + describe(site) + ": " + reason);
		}
	}

	private static String describe(LispCons site) {
		if (site.car() instanceof LispSymbol head && LispNames.JAVA_PROXY_QUALIFIED.equals(head.name())
				&& site.cdr() instanceof LispCons rest && rest.car() instanceof LispString iface) {
			return "java:proxy " + iface.print();
		}
		return JavaSiteResolver.describe(site);
	}

	/**
	 * @return the sites {@code --java-static} refused, in the order they were met
	 */
	List<String> refusals() {
		return List.copyOf(this.refusals);
	}

	/**
	 * Reports, as compile warnings, the sites left to run time -- in source order, from
	 * the top-level form on which reporting is on: from the start under
	 * {@code --warn-java-reflection}, else from after a top-level
	 * {@code (setq java:*warn-on-reflection* t)} (and off again after one setting it to
	 * {@code nil}), which is when the interpreter, loading the same forms, reports them.
	 * @param program the package-resolved top-level forms
	 * @param on whether reporting is on from the start
	 */
	void report(List<LispVal> program, boolean on) {
		boolean reporting = on;
		for (LispVal form : program) {
			Boolean set = warnOnReflectionSetting(form);
			if (set != null) {
				reporting = on || set;
				continue;
			}
			if (!reporting) {
				continue;
			}
			for (LispCons site : JavaSiteResolver.sitesIn(form)) {
				JavaSite resolution = resolve(site);
				if (!resolution.resolved()) {
					CompileWarnings.warn(SourceProvenance.prefix(site) + "warning: "
							+ JavaSiteResolver.reflectionWarning(site, resolution));
				}
			}
		}
	}

	/**
	 * The value a top-level {@code (setq java:*warn-on-reflection* x)} (or {@code setf},
	 * {@code defparameter}, {@code defvar}) gives the switch, or {@code null} when the
	 * form is no such assignment.
	 */
	private static @Nullable Boolean warnOnReflectionSetting(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& (LispNames.SETQ.equals(op.name()) || LispNames.SETF.equals(op.name())
						|| LispNames.DEFPARAMETER.equals(op.name()) || LispNames.DEFVAR.equals(op.name()))
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol var
				&& LispNames.JAVA_WARN_ON_REFLECTION_QUALIFIED.equals(var.name())) {
			return rest.cdr() instanceof LispCons value && !(value.car() instanceof LispNil);
		}
		return null;
	}

}
