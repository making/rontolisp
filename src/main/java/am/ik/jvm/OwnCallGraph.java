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

	private final List<List<Member>> fieldUses = new ArrayList<>();

	private final Map<Member, Integer> methodByMember = new HashMap<>();

	/**
	 * Adds the next declared method, in declaration order.
	 * @param method the method
	 * @param calls the own methods its body invokes
	 * @param fields the own fields its body reads or writes
	 */
	void method(Member method, List<Member> calls, List<Member> fields) {
		this.methodByMember.putIfAbsent(method, this.names.size());
		this.names.add(method.name());
		this.calls.add(calls);
		this.fieldUses.add(fields);
	}

	/**
	 * The methods reachable from the roots: every method named in {@code roots}, plus
	 * {@code <init>} and {@code <clinit>}, and everything they transitively invoke.
	 * @param roots the entry-point names, or {@code null} to keep every method
	 * @return per declared method, whether it is kept
	 */
	boolean[] reachable(@Nullable Set<String> roots) {
		boolean[] kept = new boolean[this.names.size()];
		if (roots == null) {
			Arrays.fill(kept, true);
			return kept;
		}
		Deque<Integer> work = new ArrayDeque<>();
		for (int m = 0; m < kept.length; m++) {
			String name = this.names.get(m);
			if (roots.contains(name) || "<init>".equals(name) || "<clinit>".equals(name)) {
				kept[m] = true;
				work.push(m);
			}
		}
		while (!work.isEmpty()) {
			for (Member call : this.calls.get(work.pop())) {
				Integer target = this.methodByMember.get(call);
				if (target != null && !kept[target]) {
					kept[target] = true;
					work.push(target);
				}
			}
		}
		return kept;
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
			for (Member call : this.calls.get(m)) {
				if (!this.methodByMember.containsKey(call)) {
					missing.computeIfAbsent(call, k -> new LinkedHashSet<>()).add(this.names.get(m));
				}
			}
		}
		return missing;
	}

}
