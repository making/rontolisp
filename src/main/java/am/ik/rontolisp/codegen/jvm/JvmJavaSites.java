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
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaImplementation;
import am.ik.rontolisp.compiler.JavaImplementations;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaSiteResolver;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java:} sites of one compile attempt, each resolved once against the class
 * files the compile reads ({@link JvmClassFileLookup}) by the resolver the interpreter
 * also uses ({@link JavaSiteResolver}); a resolved site is compiled to a direct call
 * ({@link JvmJavaDirectSites}), any other one through the embedded bridge. A
 * {@code java:reify} or {@code java:proxy} whose interface resolves
 * ({@link JavaImplementations}) makes an object of a class generated for it
 * ({@link JvmJavaImplementations}), as does a function value passed where an interface is
 * expected; any other one is the bridge's {@code java.lang.reflect.Proxy}. Under
 * {@code --java-static} a site that would need the bridge is refused instead, and the
 * attempt fails naming every such site ({@link #refusals()}).
 */
final class JvmJavaSites {

	private final JavaSiteResolver resolver;

	private final IdentityHashMap<LispCons, JavaSite> sites = new IdentityHashMap<>();

	private final IdentityHashMap<LispCons, JavaImplementation> implementationForms = new IdentityHashMap<>();

	private final JavaClassLookup lookup;

	private final JvmJavaDirectSites direct;

	private final JvmJavaImplementations implementations;

	private final boolean javaStatic;

	private final IdentityHashMap<LispCons, Boolean> refused = new IdentityHashMap<>();

	private final List<String> refusals = new ArrayList<>();

	/**
	 * @param lookup the classes sites resolve against
	 * @param cp the class's constant pool
	 * @param thisClass the class
	 * @param programInternalName the class's internal name, which the generated
	 * implementation classes are named after
	 * @param lispToString the program's {@code _lispToString}
	 * @param javaStatic whether a site that needs the bridge is refused
	 * ({@code --java-static})
	 */
	JvmJavaSites(JavaClassLookup lookup, ConstantPool cp, ClassConstant thisClass, String programInternalName,
			MethodrefConstant lispToString, boolean javaStatic) {
		this.resolver = new JavaSiteResolver(lookup);
		this.lookup = lookup;
		this.direct = new JvmJavaDirectSites(cp, thisClass, lookup, lispToString);
		this.implementations = new JvmJavaImplementations(cp, thisClass, programInternalName, lookup, this.direct,
				lispToString);
		this.direct.implementations(this.implementations);
		this.javaStatic = javaStatic;
	}

	/**
	 * How a {@code java:reify} or {@code java:proxy} form implements its interface.
	 * @param form the form
	 * @return the implementation, resolved or not
	 */
	JavaImplementation implementation(LispCons form) {
		JavaImplementation cached = this.implementationForms.get(form);
		if (cached == null) {
			cached = JavaImplementations.resolve(form, this.lookup);
			this.implementationForms.put(form, cached);
		}
		return cached;
	}

	/**
	 * @return the classes this attempt implements interfaces with
	 */
	JvmJavaImplementations implementations() {
		return this.implementations;
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
	 * Why a site needs the reflective bridge, or {@code null} when it compiles to code
	 * that needs nothing else: a {@code java:reify} / {@code java:proxy} whose interface
	 * is not resolved when it is compiled, or a site left to run time. A function value
	 * passed where an interface is expected needs nothing: it becomes the interface's
	 * generated proxy class.
	 * @param site a {@code java:} form
	 * @return the reason, or {@code null}
	 */
	@Nullable String bridgeReason(LispCons site) {
		if (JavaImplementations.isImplementationForm(site)) {
			JavaImplementation implementation = implementation(site);
			if (implementation.resolved()) {
				return null;
			}
			return "it implements its interface with java.lang.reflect.Proxy: " + implementation.reason();
		}
		JavaSite resolution = resolve(site);
		if (!resolution.resolved()) {
			return "it is resolved by reflection at run time: " + resolution.reason();
		}
		return null;
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

	// The java: sites of a form, java:reify and java:proxy ones included.
	private static List<LispCons> proxySitesAnd(LispVal form) {
		List<LispCons> found = new ArrayList<>(JavaSiteResolver.sitesIn(form));
		found.addAll(JavaImplementations.formsIn(form));
		return found;
	}

	/**
	 * Whether the program makes an object of a generated implementation class -- a
	 * {@code java:reify} / {@code java:proxy} whose interface resolves, a resolved site
	 * passing a function where an interface is expected: its functions are applied
	 * through {@code _apply}, which the program then carries. Read off the forms before
	 * they are compiled; a misprediction is caught by the helper-gate check.
	 * @param program the package-resolved top-level forms
	 * @return whether the apply runtime is needed
	 */
	boolean needsApply(List<LispVal> program) {
		for (LispVal form : program) {
			for (LispCons site : proxySitesAnd(form)) {
				if (JavaImplementations.isImplementationForm(site)) {
					if (implementation(site).resolved()) {
						return true;
					}
					continue;
				}
				if (passesAFunction(resolve(site))) {
					return true;
				}
			}
		}
		return false;
	}

	// Whether a resolved site may convert an argument that is a function to an
	// interface -- directly, or as an element of a sequence made an array -- which the
	// interface's generated proxy class then applies.
	private static boolean passesAFunction(JavaSite site) {
		if (!site.resolved() || site.arguments().isEmpty()) {
			return false;
		}
		List<JavaOverloads.Overload> overloads = site.dispatched() ? site.overloads() : site.executable() == null
				? List.of() : List.of(new JavaOverloads.Overload(site.executable(), site.packed()));
		List<JavaSite.Argument> arguments = site.arguments();
		for (int i = 0; i < arguments.size(); i++) {
			if (!arguments.get(i).mayBeFunction()) {
				continue;
			}
			for (JavaOverloads.Overload overload : overloads) {
				JavaType type = JavaOverloads.parameterAt(overload, i);
				while (type.componentType() != null) {
					type = java.util.Objects.requireNonNull(type.componentType());
				}
				if (type.isInterface()) {
					return true;
				}
			}
		}
		return false;
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
		if (JavaImplementations.isImplementationForm(site)) {
			return JavaImplementations.describe(site);
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
	 * Reports, as compile warnings, the sites left to run time -- and the
	 * {@code java:reify} / {@code java:proxy} forms whose interface is implemented by
	 * reflection then -- in source order, from the top-level form on which reporting is
	 * on: from the start under {@code --warn-java-reflection}, else from after a
	 * top-level {@code (setq java:*warn-on-reflection* t)} (and off again after one
	 * setting it to {@code nil}), which is when the interpreter, loading the same forms,
	 * reports them.
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
			for (LispCons implementationForm : JavaImplementations.formsIn(form)) {
				JavaImplementation implementation = implementation(implementationForm);
				if (!implementation.resolved()) {
					CompileWarnings.warn(SourceProvenance.prefix(implementationForm) + "warning: "
							+ JavaImplementations.reflectionWarning(implementationForm, implementation));
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
