package am.ik.jvm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The references one class's method bodies make to the class's OWN methods and fields --
 * the graph {@link JvmClassSplitter} shakes a {@link ClassDefinition} by and checks it
 * against.
 * <p>
 * Identity is by name and descriptor, never by constant-pool index: a pool may hold two
 * entries that spell the same member, and a method is the same method whichever one a
 * call site used.
 * <p>
 * A call made only through a function value ({@link MethodCode#invokestaticThroughValue},
 * a dispatcher's case) is an edge only once a kept body makes a value of its target
 * ({@link MethodCode#makesValueOf}): a value no kept method can make is never called.
 */
final class OwnCallGraph {

	/**
	 * A member of the class, as a reference names it.
	 *
	 * @param name the member name
	 * @param descriptor its descriptor
	 */
	record Member(String name, String descriptor) {
	}

	private final List<String> names = new ArrayList<>();

	private final List<List<Member>> calls = new ArrayList<>();

	private final List<List<Member>> valueCalls = new ArrayList<>();

	private final List<List<Member>> values = new ArrayList<>();

	private final List<List<Member>> fieldUses = new ArrayList<>();

	private final Map<Member, Integer> methodByMember = new HashMap<>();

	/**
	 * Adds the next declared method, in declaration order.
	 * @param method the method
	 * @param calls the own methods its body invokes
	 * @param valueCalls the own methods its body invokes only through a function value
	 * @param values the own methods its body makes a function value of
	 * @param fields the own fields its body reads or writes
	 */
	void method(Member method, List<Member> calls, List<Member> valueCalls, List<Member> values, List<Member> fields) {
		this.methodByMember.putIfAbsent(method, this.names.size());
		this.names.add(method.name());
		this.calls.add(calls);
		this.valueCalls.add(valueCalls);
		this.values.add(values);
		this.fieldUses.add(fields);
	}

	/**
	 * What a shake keeps.
	 *
	 * @param kept per declared method, whether it is kept
	 * @param valued per declared method, whether a kept body makes a function value of it
	 * -- every method a value the kept code can make may call, kept or not (a value no
	 * kept call can apply leaves its method unkept)
	 */
	record Reach(boolean[] kept, boolean[] valued) {
	}

	/**
	 * The methods reachable from the roots: every method named in {@code roots}, plus
	 * {@code <init>} and {@code <clinit>}, and everything they transitively invoke -- a
	 * call through a value once a kept body makes that value.
	 * @param roots the entry-point names, or {@code null} to keep every method
	 * @return per declared method, whether it is kept and whether it is valued (every
	 * method both, when {@code roots} is null)
	 */
	Reach reach(@Nullable Set<String> roots) {
		int count = this.names.size();
		boolean[] kept = new boolean[count];
		boolean[] valued = new boolean[count];
		if (roots == null) {
			Arrays.fill(kept, true);
			Arrays.fill(valued, true);
			return new Reach(kept, valued);
		}
		// Per method, whether a kept body calls it through a value not made yet.
		boolean[] awaited = new boolean[count];
		Deque<Integer> work = new ArrayDeque<>();
		for (int m = 0; m < count; m++) {
			String name = this.names.get(m);
			if (roots.contains(name) || "<init>".equals(name) || "<clinit>".equals(name)) {
				kept[m] = true;
				work.push(m);
			}
		}
		while (!work.isEmpty()) {
			int m = work.pop();
			for (Member call : this.calls.get(m)) {
				this.keep(call, kept, work);
			}
			for (Member call : this.valueCalls.get(m)) {
				Integer target = this.methodByMember.get(call);
				if (target != null) {
					if (valued[target]) {
						this.keep(call, kept, work);
					}
					else {
						awaited[target] = true;
					}
				}
			}
			for (Member value : this.values.get(m)) {
				Integer target = this.methodByMember.get(value);
				if (target != null && !valued[target]) {
					valued[target] = true;
					if (awaited[target]) {
						this.keep(value, kept, work);
					}
				}
			}
		}
		return new Reach(kept, valued);
	}

	private void keep(Member method, boolean[] kept, Deque<Integer> work) {
		Integer target = this.methodByMember.get(method);
		if (target != null && !kept[target]) {
			kept[target] = true;
			work.push(target);
		}
	}

	/**
	 * The own fields the kept methods reference.
	 * @param kept per declared method, whether it is kept
	 * @return the fields
	 */
	Set<Member> usedFields(boolean[] kept) {
		Set<Member> used = new HashSet<>();
		for (int m = 0; m < kept.length; m++) {
			if (kept[m]) {
				used.addAll(this.fieldUses.get(m));
			}
		}
		return used;
	}

	/**
	 * Every own method some body invokes that the class does not declare.
	 * @return each unresolved call mapped to the names of the methods whose bodies make
	 * it, both in first-reference order
	 */
	Map<Member, Set<String>> unresolved() {
		Map<Member, Set<String>> missing = new LinkedHashMap<>();
		for (int m = 0; m < this.names.size(); m++) {
			List<Member> invoked = new ArrayList<>(this.calls.get(m));
			invoked.addAll(this.valueCalls.get(m));
			for (Member call : invoked) {
				if (!this.methodByMember.containsKey(call)) {
					missing.computeIfAbsent(call, k -> new LinkedHashSet<>()).add(this.names.get(m));
				}
			}
		}
		return missing;
	}

}
