package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Registration;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.HandlesTypes;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import org.jspecify.annotations.Nullable;

/**
 * What an application declares without registering it (Servlet 6.1, 8.1 and 8.2.4): the
 * classes that carry {@code @WebServlet}, {@code @WebFilter} or {@code @WebListener}, and
 * the {@code ServletContainerInitializer}s its services name.
 *
 * <p>
 * The classes are looked for in two places. The class path is read ({@link ClassPath}).
 * And where a program's classes are no files of a class path -- it was compiled ahead of
 * time, or its class loader is not the class path's -- a build lists them: a resource
 * {@code META-INF/annotations/<the annotation type's name>} holds the names of the
 * classes that carry the annotation, a name a line, a {@code #} starting a comment, as a
 * provider-configuration file is written.
 *
 * <p>
 * An initializer that asks for classes ({@code @HandlesTypes}) is handed those of the
 * class path: a program with no class path refuses it.
 */
final class Discovery {

	private Discovery() {
	}

	/**
	 * Registers what the application declares: the annotated classes in the order of
	 * their names, then what each initializer registers, handed the classes it asks for.
	 */
	static void discover(WebContext context, ClassLoader loader) throws ServletException {
		// The initializers first: what they ask for is what the class path is read for.
		List<ServletContainerInitializer> initializers = new ArrayList<>();
		List<HandlesTypes> asked = new ArrayList<>();
		try {
			for (ServletContainerInitializer initializer : ServiceLoader.load(ServletContainerInitializer.class,
					loader)) {
				HandlesTypes handles = initializer.getClass().getAnnotation(HandlesTypes.class);
				// A program that has no class path has none to hand an initializer.
				if (System.getProperty("java.class.path") == null && handles != null && handles.value().length > 0) {
					throw new ServletException("@HandlesTypes needs the class files of a class path: "
							+ initializer.getClass().getName() + " asks for classes");
				}
				initializers.add(initializer);
				asked.add(handles);
			}
		}
		catch (ServiceConfigurationError ex) {
			throw new ServletException("an initializer could not be made", ex);
		}
		List<String> names = new ArrayList<>();
		Map<String, List<String>> supertypes = new HashMap<>();
		List<String> mentioning = new ArrayList<>();
		try {
			listed(loader.getResources("META-INF/annotations/jakarta.servlet.annotation.WebFilter"), names);
			listed(loader.getResources("META-INF/annotations/jakarta.servlet.annotation.WebListener"), names);
			listed(loader.getResources("META-INF/annotations/jakarta.servlet.annotation.WebServlet"), names);
			// A program that has no class path has its classes listed alone.
			if (System.getProperty("java.class.path") != null) {
				read(System.getProperty("java.class.path"), loader, asked, names, supertypes, mentioning);
			}
		}
		catch (IOException ex) {
			throw new ServletException("the classes of the application could not be read", ex);
		}
		for (String name : sorted(names)) {
			register(context, load(name, loader));
		}
		for (int i = 0; i < initializers.size(); i++) {
			Set<Class<?>> classes = null;
			if (System.getProperty("java.class.path") != null) {
				if (asked.get(i) != null) {
					classes = handled(asked.get(i).value(), loader, supertypes, mentioning);
				}
			}
			initializers.get(i).onStartup(classes, context);
		}
	}

	/**
	 * Reads the class files: those that name one of the three annotations are added to
	 * the names; where an initializer asks for classes, the supertypes of every class and
	 * the classes that name an annotation asked for are kept, a class of two roots as the
	 * first has it.
	 */
	private static void read(String classPath, ClassLoader loader, List<HandlesTypes> asked, List<String> names,
			Map<String, List<String>> supertypes, List<String> mentioning) throws IOException {
		boolean handled = false;
		List<byte[]> annotations = new ArrayList<>();
		for (HandlesTypes handles : asked) {
			for (Class<?> type : handles != null ? handles.value() : new Class<?>[0]) {
				handled = true;
				if (type.isAnnotation()) {
					annotations.add(ClassPath.ascii("L" + type.getName().replace('.', '/') + ";"));
				}
			}
		}
		boolean hierarchy = handled;
		for (Root root : ClassPath.read(classPath, loader, () -> new Root(hierarchy, annotations))) {
			names.addAll(root.names);
			root.supertypes.forEach(supertypes::putIfAbsent);
			mentioning.addAll(root.mentioning);
		}
	}

	/** What one root of the class path holds that discovery asks for. */
	private static final class Root implements ClassPath.Reader {

		private static final byte[] WEB = ClassPath.ascii("Ljakarta/servlet/annotation/Web");

		private final boolean hierarchy;

		private final List<byte[]> annotations;

		final List<String> names = new ArrayList<>();

		final Map<String, List<String>> supertypes = new HashMap<>();

		final List<String> mentioning = new ArrayList<>();

		Root(boolean hierarchy, List<byte[]> annotations) {
			this.hierarchy = hierarchy;
			this.annotations = annotations;
		}

		@Override
		public void read(String name, byte[] file) {
			if (ClassPath.holds(file, WEB)) {
				this.names.add(name);
			}
			if (this.hierarchy) {
				this.supertypes.putIfAbsent(name, ClassPath.supertypes(file));
			}
			for (byte[] annotation : this.annotations) {
				if (ClassPath.holds(file, annotation)) {
					this.mentioning.add(name);
					break;
				}
			}
		}

	}

	/**
	 * The classes of the class path an initializer asks for (Servlet 6.1, 8.2.4): those
	 * that extend or implement a type it names, and those that carry an annotation it
	 * names; null where there are none.
	 */
	private static @Nullable Set<Class<?>> handled(Class<?>[] types, ClassLoader loader,
			Map<String, List<String>> supertypes, List<String> mentioning) throws ServletException {
		Set<String> found = new HashSet<>();
		Map<String, Boolean> known = new HashMap<>();
		for (Class<?> type : types) {
			if (type.isAnnotation()) {
				for (String name : mentioning) {
					if (annotated(load(name, loader), type.asSubclass(Annotation.class))) {
						found.add(name);
					}
				}
			}
			else {
				known.clear();
				for (String name : supertypes.keySet()) {
					if (!name.equals(type.getName()) && below(name, type, loader, supertypes, known)) {
						found.add(name);
					}
				}
			}
		}
		if (found.isEmpty()) {
			return null;
		}
		Set<Class<?>> classes = new LinkedHashSet<>();
		for (String name : sorted(new ArrayList<>(found))) {
			classes.add(load(name, loader));
		}
		return classes;
	}

	/**
	 * Whether a class carries an annotation: itself, or one of its fields, constructors
	 * or methods, as Tomcat and Jetty answer (an annotation a superclass carries is not
	 * the class's, as Tomcat answers).
	 */
	private static boolean annotated(Class<?> type, Class<? extends Annotation> annotation) {
		if (type.getDeclaredAnnotation(annotation) != null) {
			return true;
		}
		try {
			for (AnnotatedElement member : type.getDeclaredFields()) {
				if (member.getDeclaredAnnotation(annotation) != null) {
					return true;
				}
			}
			for (AnnotatedElement member : type.getDeclaredConstructors()) {
				if (member.getDeclaredAnnotation(annotation) != null) {
					return true;
				}
			}
			for (AnnotatedElement member : type.getDeclaredMethods()) {
				if (member.getDeclaredAnnotation(annotation) != null) {
					return true;
				}
			}
		}
		catch (LinkageError ex) {
			// A member's type is not there: the class is not the application's to run.
		}
		return false;
	}

	/**
	 * Whether a class extends or implements a type: by the supertypes its file declares,
	 * and a supertype that is no file of the class path by loading it.
	 */
	private static boolean below(String name, Class<?> type, ClassLoader loader, Map<String, List<String>> supertypes,
			Map<String, Boolean> known) {
		if (name.equals(type.getName())) {
			return true;
		}
		Boolean answer = known.get(name);
		if (answer != null) {
			return answer;
		}
		List<String> declared = supertypes.get(name);
		if (declared == null) {
			try {
				answer = type.isAssignableFrom(Class.forName(name, false, loader));
			}
			catch (ClassNotFoundException | LinkageError ex) {
				// A supertype not there: a loader could not load the class either.
				answer = false;
			}
		}
		else {
			// No cycle: a class cannot be its own supertype.
			answer = false;
			for (String supertype : declared) {
				if (below(supertype, type, loader, supertypes, known)) {
					answer = true;
					break;
				}
			}
		}
		known.put(name, answer);
		return answer;
	}

	/** The names in their order, each once. */
	private static List<String> sorted(List<String> names) {
		List<String> ordered = new ArrayList<>();
		for (String name : names) {
			int at = ordered.size();
			while (at > 0 && ordered.get(at - 1).compareTo(name) > 0) {
				at--;
			}
			if (at == 0 || !ordered.get(at - 1).equals(name)) {
				ordered.add(at, name);
			}
		}
		return ordered;
	}

	private static Class<?> load(String name, ClassLoader loader) throws ServletException {
		try {
			return Class.forName(name, false, loader);
		}
		catch (ClassNotFoundException | LinkageError ex) {
			throw new ServletException("the class " + name + " could not be loaded", ex);
		}
	}

	/** Adds the names the indexes list: a name a line, a {@code #} starting a comment. */
	private static void listed(Enumeration<URL> indexes, List<String> names) throws IOException {
		while (indexes.hasMoreElements()) {
			String text;
			try (InputStream in = indexes.nextElement().openStream()) {
				text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			int start = 0;
			while (start < text.length()) {
				int end = text.indexOf('\n', start);
				end = end < 0 ? text.length() : end;
				int comment = text.indexOf('#', start);
				String name = text.substring(start, comment >= 0 && comment < end ? comment : end).strip();
				if (!name.isEmpty()) {
					names.add(name);
				}
				start = end + 1;
			}
		}
	}

	/**
	 * Registers a class as each of its annotations declares it: one instance, a listener,
	 * a filter and a servlet as the case may be. A class that carries none is left.
	 */
	static void register(WebContext context, Class<?> type) throws ServletException {
		Object instance = null;
		if (type.isAnnotationPresent(WebListener.class)) {
			instance = Instances.create(type);
			if (!(instance instanceof EventListener listener)) {
				throw new ServletException("@WebListener is on " + type.getName() + ", which is no listener");
			}
			try {
				context.addListener(listener);
			}
			catch (IllegalArgumentException ex) {
				throw new ServletException("@WebListener is on " + type.getName() + ": " + ex.getMessage(), ex);
			}
		}
		if (type.isAnnotationPresent(WebFilter.class)) {
			instance = instance != null ? instance : Instances.create(type);
			if (!(instance instanceof Filter filter)) {
				throw new ServletException("@WebFilter is on " + type.getName() + ", which is no Filter");
			}
			filter(context, type, type.getAnnotation(WebFilter.class), filter);
		}
		if (type.isAnnotationPresent(WebServlet.class)) {
			instance = instance != null ? instance : Instances.create(type);
			if (!(instance instanceof HttpServlet servlet)) {
				throw new ServletException("@WebServlet is on " + type.getName() + ", which is no HttpServlet");
			}
			if (type.isAnnotationPresent(ServletSecurity.class)) {
				throw new ServletException("@ServletSecurity is not supported: " + type.getName());
			}
			servlet(context, type, type.getAnnotation(WebServlet.class), servlet);
		}
	}

	private static void filter(WebContext context, Class<?> type, WebFilter filter, Filter instance)
			throws ServletException {
		String name = filter.filterName().isEmpty() ? type.getName() : filter.filterName();
		FilterRegistration.Dynamic registration = context.addFilter(name, instance);
		if (registration == null) {
			throw new ServletException("@WebFilter on " + type.getName() + " names a filter there is: " + name);
		}
		EnumSet<DispatcherType> dispatcherTypes = EnumSet.noneOf(DispatcherType.class);
		for (DispatcherType dispatcherType : filter.dispatcherTypes()) {
			dispatcherTypes.add(dispatcherType);
		}
		String[] patterns = patterns("@WebFilter", type, filter.value(), filter.urlPatterns());
		if (patterns.length > 0) {
			registration.addMappingForUrlPatterns(dispatcherTypes, true, patterns);
		}
		if (filter.servletNames().length > 0) {
			registration.addMappingForServletNames(dispatcherTypes, true, filter.servletNames());
		}
		configure(registration, filter.asyncSupported(), filter.initParams());
	}

	private static void servlet(WebContext context, Class<?> type, WebServlet servlet, HttpServlet instance)
			throws ServletException {
		String name = servlet.name().isEmpty() ? type.getName() : servlet.name();
		ServletRegistration.Dynamic registration = context.addServlet(name, instance);
		if (registration == null) {
			throw new ServletException("@WebServlet on " + type.getName() + " names a servlet there is: " + name);
		}
		String[] patterns = patterns("@WebServlet", type, servlet.value(), servlet.urlPatterns());
		if (patterns.length == 0) {
			throw new ServletException("@WebServlet on " + type.getName() + " has no URL pattern");
		}
		Set<String> taken = registration.addMapping(patterns);
		if (!taken.isEmpty()) {
			throw new ServletException("@WebServlet on " + type.getName() + " maps what another servlet has: " + taken);
		}
		registration.setLoadOnStartup(servlet.loadOnStartup());
		configure(registration, servlet.asyncSupported(), servlet.initParams());
		if (type.isAnnotationPresent(MultipartConfig.class)) {
			registration.setMultipartConfig(new MultipartConfigElement(type.getAnnotation(MultipartConfig.class)));
		}
	}

	/**
	 * The URL patterns of an annotation: its {@code value} or its {@code urlPatterns}.
	 */
	private static String[] patterns(String annotation, Class<?> type, String[] value, String[] urlPatterns)
			throws ServletException {
		if (value.length > 0 && urlPatterns.length > 0) {
			throw new ServletException(annotation + " on " + type.getName() + " has both value and urlPatterns");
		}
		return value.length > 0 ? value : urlPatterns;
	}

	private static void configure(Registration.Dynamic registration, boolean asyncSupported,
			WebInitParam[] parameters) {
		registration.setAsyncSupported(asyncSupported);
		for (WebInitParam parameter : parameters) {
			registration.setInitParameter(parameter.name(), parameter.value());
		}
	}

}
