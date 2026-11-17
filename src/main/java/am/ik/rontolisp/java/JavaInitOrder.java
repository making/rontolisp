package am.ik.rontolisp.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import am.ik.rontolisp.java.JavaClass.Field;
import am.ik.rontolisp.java.JavaClass.Method;

/**
 * When each class's static initialization runs. The language initializes a class at its
 * first use; a lowered program does the same -- a class is LAZY -- unless nothing the
 * program does can tell the difference, and then the class is EAGER: initialized before
 * {@code main}, with no test where it is used. A class is eager when its initialization,
 * with every method and constructor it can run, prints nothing, throws nothing, writes no
 * static field of another class and reads of another class only a final field holding a
 * value nothing can change; when every class it needs initialized first is eager; and
 * when no two such classes need each other. The decision is made after a lowering pass
 * from what the pass recorded ({@link Effects}), and the next pass is lowered with it
 * ({@code .kb/java-frontend.md}, "Static initialization").
 */
final class JavaInitOrder {

	/**
	 * What a function does that the order of static initialization depends on: recorded
	 * by the lowering as it lowers the function.
	 */
	static final class Effects {

		/** Whether it does what a program can see: it prints, or it throws. */
		boolean visible;

		final Set<Field> reads = new LinkedHashSet<>();

		final Set<Field> writes = new LinkedHashSet<>();

		/**
		 * The static fields an initializer reads ahead of their own, by a qualified name.
		 */
		final Set<Field> forward = new LinkedHashSet<>();

		final Set<Method> calls = new LinkedHashSet<>();

		/** The classes it initializes: one it makes, calls a static method of, reads. */
		final Set<JavaClass> uses = new LinkedHashSet<>();

	}

	private Set<JavaClass> lazy = Set.of();

	/**
	 * Whether every class is initialized at its first use: a session's, whose buffers
	 * have no {@code main} to initialize a class before.
	 */
	boolean allLazy;

	private List<JavaClass> eager = List.of();

	/** Whether a class's static initialization runs at its first use. */
	boolean isLazy(JavaClass declaration) {
		return this.lazy.contains(declaration);
	}

	/** The classes initialized before {@code main}, each after the ones it needs. */
	List<JavaClass> eager() {
		return this.eager;
	}

	/**
	 * Decides from a pass's records.
	 * @param inits what each class's static initialization does itself
	 * @param methods what each method and constructor does itself
	 * @param immutable whether nothing can change a value of a type
	 * @return whether the lazy classes are other ones than the pass was lowered with
	 */
	boolean decide(Map<JavaClass, Effects> inits, Map<Method, Effects> methods, Predicate<JavaType> immutable) {
		Map<JavaClass, Set<JavaClass>> needs = new LinkedHashMap<>();
		Set<JavaClass> lazy = new LinkedHashSet<>();
		for (Map.Entry<JavaClass, Effects> init : inits.entrySet()) {
			JavaClass declaration = init.getKey();
			Effects all = reached(init.getValue(), methods);
			boolean shows = all.visible || this.allLazy;
			for (Field written : all.writes) {
				shows |= written.owner != declaration;
			}
			for (Field read : all.reads) {
				shows |= read.owner != declaration && !(read.isFinal && (read.frozen || immutable.test(read.type)));
			}
			if (shows) {
				lazy.add(declaration);
			}
			Set<JavaClass> first = new LinkedHashSet<>();
			Set<JavaClass> used = new LinkedHashSet<>(all.uses);
			if (declaration.superclass != null) {
				used.add(declaration.superclass);
			}
			for (JavaClass use : used) {
				for (JavaClass type = use; type != null; type = type.superclass) {
					if (type != declaration && inits.containsKey(type)) {
						first.add(type);
					}
				}
			}
			needs.put(declaration, first);
		}
		// a class needing a lazy one is lazy; so are classes needing each other
		boolean moved = true;
		while (moved) {
			moved = false;
			for (JavaClass declaration : needs.keySet()) {
				if (lazy.contains(declaration)) {
					continue;
				}
				boolean waits = needs.getOrDefault(declaration, Set.of()).stream().anyMatch(lazy::contains);
				if (waits || needsItself(declaration, needs)) {
					lazy.add(declaration);
					moved = true;
				}
			}
		}
		List<JavaClass> eager = new ArrayList<>();
		Set<JavaClass> placed = new HashSet<>();
		for (JavaClass declaration : needs.keySet()) {
			place(declaration, needs, lazy, placed, eager);
		}
		boolean changed = !lazy.equals(this.lazy);
		this.lazy = lazy;
		this.eager = eager;
		return changed;
	}

	private static void place(JavaClass declaration, Map<JavaClass, Set<JavaClass>> needs, Set<JavaClass> lazy,
			Set<JavaClass> placed, List<JavaClass> into) {
		if (lazy.contains(declaration) || !placed.add(declaration)) {
			return;
		}
		for (JavaClass first : needs.getOrDefault(declaration, Set.of())) {
			place(first, needs, lazy, placed, into);
		}
		into.add(declaration);
	}

	private static boolean needsItself(JavaClass declaration, Map<JavaClass, Set<JavaClass>> needs) {
		Set<JavaClass> seen = new HashSet<>();
		Deque<JavaClass> pending = new ArrayDeque<>(needs.getOrDefault(declaration, Set.of()));
		while (!pending.isEmpty()) {
			JavaClass next = pending.pop();
			if (next == declaration) {
				return true;
			}
			if (seen.add(next)) {
				pending.addAll(needs.getOrDefault(next, Set.of()));
			}
		}
		return false;
	}

	/** What a function does with everything it can call. */
	static Effects reached(Effects from, Map<Method, Effects> methods) {
		Effects all = new Effects();
		Set<Method> seen = new HashSet<>();
		Deque<Effects> pending = new ArrayDeque<>();
		pending.push(from);
		while (!pending.isEmpty()) {
			Effects next = pending.pop();
			all.visible |= next.visible;
			all.reads.addAll(next.reads);
			all.writes.addAll(next.writes);
			all.forward.addAll(next.forward);
			all.uses.addAll(next.uses);
			for (Method called : next.calls) {
				Effects body = methods.get(called);
				if (seen.add(called) && body != null) {
					pending.push(body);
				}
			}
		}
		return all;
	}

}
