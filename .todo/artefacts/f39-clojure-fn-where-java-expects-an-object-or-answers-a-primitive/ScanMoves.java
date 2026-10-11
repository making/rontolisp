import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.JavaType;
import am.ik.rontolisp.compiler.ReflectiveJavaClasses;

// Which java.base overload choices a function argument moves now that a function costs
// COST_FUNCTION_OBJECT where Object is expected (and COST_PROXY_NOT_FUNCTIONAL went
// 9 -> 10): every public method and constructor group of a public top-level class in a
// java.* package of java.base, a function alone or beside an integer, a string or a
// function, selected under the table before f39 (oldCost: Object no match, any other
// non-functional interface 9) and under the current JavaOverloads.kindCost.
public class ScanMoves {

	static final ReflectiveJavaClasses CLASSES = ReflectiveJavaClasses.instance();

	static int oldCost(JavaKind kind, JavaType target) {
		if (kind == JavaKind.Lisp.FUNCTION) {
			if (!target.isInterface()) {
				return JavaOverloads.NO_MATCH;
			}
			int now = JavaOverloads.kindCost(kind, target, CLASSES);
			return now == JavaOverloads.COST_PROXY_NOT_FUNCTIONAL ? 9 : now;
		}
		return JavaOverloads.kindCost(kind, target, CLASSES);
	}

	static String describe(JavaOverloads.Overload o) {
		if (o == null) {
			return "none";
		}
		JavaExecutable e = o.executable();
		List<String> names = new ArrayList<>();
		for (JavaType p : e.parameterTypes()) {
			names.add(p.name());
		}
		return e.name() + "(" + String.join(",", names) + ")" + (o.packed() ? "*" : "");
	}

	public static void main(String[] args) throws Exception {
		FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
		Set<String> out = new TreeSet<>();
		int groups = 0;
		try (Stream<Path> paths = Files.walk(jrt.getPath("/modules/java.base"))) {
			for (Path path : (Iterable<Path>) paths::iterator) {
				String s = path.toString();
				if (!s.endsWith(".class") || s.contains("module-info") || s.contains("$")) {
					continue;
				}
				String name = s.substring("/modules/java.base/".length(), s.length() - 6).replace('/', '.');
				if (!name.startsWith("java.")) {
					continue;
				}
				Class<?> c;
				try {
					c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
				}
				catch (Throwable t) {
					continue;
				}
				if (!Modifier.isPublic(c.getModifiers())) {
					continue;
				}
				ReflectiveJavaClasses.Type type = ReflectiveJavaClasses.of(c);
				Set<String> methodNames = new LinkedHashSet<>();
				for (Method m : c.getMethods()) {
					methodNames.add(m.getName());
				}
				List<List<? extends JavaExecutable>> candidateSets = new ArrayList<>();
				for (String m : methodNames) {
					candidateSets.add(type.methods(m));
				}
				if (!Modifier.isAbstract(c.getModifiers())) {
					candidateSets.add(type.constructors());
				}
				List<JavaKind> others = List.of(JavaKind.Lisp.INTEGER, JavaKind.Lisp.STRING, JavaKind.Lisp.FUNCTION);
				for (List<? extends JavaExecutable> candidates : candidateSets) {
					if (candidates.isEmpty()) {
						continue;
					}
					groups++;
					List<List<JavaKind>> shapes = new ArrayList<>();
					shapes.add(List.of(JavaKind.Lisp.FUNCTION));
					for (JavaKind other : others) {
						shapes.add(List.of(JavaKind.Lisp.FUNCTION, other));
						shapes.add(List.of(other, JavaKind.Lisp.FUNCTION));
					}
					for (List<JavaKind> kinds : shapes) {
						JavaOverloads.Overload before = JavaOverloads.select(candidates, kinds.size(),
								(i, target) -> oldCost(kinds.get(i), target));
						JavaOverloads.Overload after = JavaOverloads.select(candidates, kinds.size(),
								(i, target) -> JavaOverloads.kindCost(kinds.get(i), target, CLASSES));
						String b = describe(before);
						String a = describe(after);
						if (!b.equals(a)) {
							out.add(c.getName() + " " + kinds + ": " + b + " -> " + a);
						}
					}
				}
			}
		}
		int fromNone = 0;
		int moved = 0;
		for (String line : out) {
			if (line.contains(": none -> ")) {
				fromNone++;
			}
			else {
				moved++;
				System.out.println("MOVED " + line);
			}
		}
		System.out.println("groups " + groups + ", newly matching " + fromNone + ", moved " + moved);
	}

}
