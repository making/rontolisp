package am.ik.rontolisp.codegen.jvm;

import java.util.IdentityHashMap;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaSiteResolver;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java:} sites of one compile attempt, each resolved once against the class
 * files the compile reads ({@link JvmClassFileLookup}) by the resolver the interpreter
 * also uses ({@link JavaSiteResolver}).
 */
final class JvmJavaSites {

	private final JavaSiteResolver resolver;

	private final IdentityHashMap<LispCons, JavaSite> sites = new IdentityHashMap<>();

	/**
	 * @param lookup the classes sites resolve against
	 */
	JvmJavaSites(JavaClassLookup lookup) {
		this.resolver = new JavaSiteResolver(lookup);
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
