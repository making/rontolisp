package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The chain of a generic function's dispatcher: its branches, most specific first, and
 * what answers when no branch is taken. A generic with generations ({@code .kb/clos.md},
 * "Method definitions take effect in program order") gets one chain over the branches of
 * every generation's method table, each guarded by the generations it exists in, so a
 * method is in its dispatcher once however many generations there are.
 *
 * <p>
 * A state is how many generation flags are set. The flags are set in program order, so in
 * state {@code k} the first {@code k} are set and a call dispatches over the {@code k}-th
 * table, the last table in the last state; "state {@code k} or later" is one read of flag
 * {@code k-1}.
 */
final class DispatchBranches {

	/**
	 * One branch: {@code value} when {@code guard} (null for none) and {@code test} hold.
	 * {@code key} makes it the same branch in two generations' tables.
	 *
	 * @param key the branch's identity across tables
	 * @param test the specializer test
	 * @param guard what else must hold, or null
	 * @param value the branch's effective method
	 */
	record Branch(Object key, LispVal test, @Nullable LispVal guard, LispVal value) {
	}

	/**
	 * A chain over one method table.
	 *
	 * @param branches the branches, most specific first
	 * @param base what answers when no branch is taken
	 */
	record Chain(List<Branch> branches, LispVal base) {

		Chain {
			branches = List.copyOf(branches);
		}

		/**
		 * The chain as nested {@code if}s.
		 * @return the form
		 */
		LispVal form() {
			LispVal chain = this.base;
			for (Branch branch : this.branches.reversed()) {
				LispVal guard = branch.guard();
				chain = ifForm(guard == null ? branch.test() : call(LispNames.AND, guard, branch.test()),
						branch.value(), chain);
			}
			return chain;
		}

	}

	private final List<String> flags;

	private final int floor;

	/**
	 * Creates the states of a generic's generations.
	 * @param flags the generation flags, oldest first
	 * @param floor the first state a call can be made in: before it the dispatcher does
	 * not get this far
	 */
	DispatchBranches(List<String> flags, int floor) {
		this.flags = List.copyOf(flags);
		this.floor = floor;
	}

	/**
	 * The test that the current state is one of {@code states}.
	 * @param states the states
	 * @return the test, or null when every state a call can be made in is one of them
	 */
	@Nullable LispVal guard(BitSet states) {
		return guard(states, possibleStates());
	}

	/**
	 * {@code test}, which also holds only in {@code states}.
	 * @param test the test
	 * @param states the states it can hold in
	 * @return the guarded test
	 */
	LispVal guarded(LispVal test, BitSet states) {
		LispVal guard = guard(states);
		return guard == null ? test : call(LispNames.AND, test, guard);
	}

	/**
	 * Every item of the per-state lists once, in an order each list keeps where the lists
	 * allow one (the last list's always), with the states it is in.
	 * @param <T> the item type
	 * @param perState the lists, one per state from the floor on
	 * @return each item with its states
	 */
	<T> LinkedHashMap<T, BitSet> union(List<List<T>> perState) {
		LinkedHashMap<T, BitSet> union = new LinkedHashMap<>();
		for (T item : merge(perState)) {
			union.put(item, new BitSet());
		}
		for (int i = 0; i < perState.size(); i++) {
			for (T item : perState.get(i)) {
				java.util.Objects.requireNonNull(union.get(item)).set(this.floor + i);
			}
		}
		return union;
	}

	/**
	 * The one chain over the chains of every state a call can be made in: each distinct
	 * branch once, taken in the states whose chain has it, its value chosen by the state
	 * where the tables disagree.
	 * @param chains the chains, one per state from the floor on
	 * @return the form
	 */
	LispVal form(List<Chain> chains) {
		List<List<Object>> orders = new ArrayList<>();
		Map<Object, Branch[]> branches = new HashMap<>();
		for (int i = 0; i < chains.size(); i++) {
			int state = this.floor + i;
			List<Object> order = new ArrayList<>();
			Map<Object, Integer> seen = new HashMap<>();
			for (Branch branch : chains.get(i).branches()) {
				List<Object> identity = List.of(branch.key(), branch.test());
				Object id = List.of(identity, seen.merge(identity, 1, Integer::sum));
				branches.computeIfAbsent(id, k -> new Branch[this.flags.size() + 1])[state] = branch;
				order.add(id);
			}
			orders.add(order);
		}
		LinkedHashMap<List<@Nullable LispVal>, BitSet> bases = new LinkedHashMap<>();
		for (int i = 0; i < chains.size(); i++) {
			bases.computeIfAbsent(Arrays.asList(null, chains.get(i).base()), k -> new BitSet()).set(this.floor + i);
		}
		LispVal chain = switchOver(newestFirst(bases), possibleStates());
		List<Object> union = merge(orders);
		for (Object id : union.reversed()) {
			chain = unionBranch(java.util.Objects.requireNonNull(branches.get(id)), chain);
		}
		return chain;
	}

	/** One distinct branch over every state: its groups of equal values, newest first. */
	private LispVal unionBranch(@Nullable Branch[] perState, LispVal rest) {
		BitSet present = new BitSet();
		LinkedHashMap<List<@Nullable LispVal>, BitSet> values = new LinkedHashMap<>();
		@Nullable LispVal found = null;
		for (int state = perState.length - 1; state >= 0; state--) {
			Branch branch = perState[state];
			if (branch != null) {
				present.set(state);
				found = branch.test();
				values.computeIfAbsent(Arrays.asList(branch.guard(), branch.value()), k -> new BitSet()).set(state);
			}
		}
		LispVal test = java.util.Objects.requireNonNull(found);
		List<Group> groups = newestFirst(values);
		if (groups.stream().allMatch(group -> group.guard() == null)) {
			LispVal exists = guard(present);
			LispVal value = groups.size() == 1 ? groups.get(0).value() : switchOver(groups, present);
			return ifForm(exists == null ? test : call(LispNames.AND, test, exists), value, rest);
		}
		// A nested method's guard may fail in a state the branch exists in, which then
		// falls through: each group is a branch of its own.
		LispVal chain = rest;
		for (Group group : groups.reversed()) {
			List<LispVal> conjuncts = new ArrayList<>();
			conjuncts.add(new LispSymbol(LispNames.AND));
			if (group.guard() != null) {
				conjuncts.add(group.guard());
			}
			conjuncts.add(test);
			LispVal states = guard(group.states());
			if (states != null) {
				conjuncts.add(states);
			}
			chain = ifForm(conjuncts.size() == 2 ? conjuncts.get(1) : list(conjuncts), group.value(), chain);
		}
		return chain;
	}

	/**
	 * A value per group of states, the newest group tested first; only the {@code within}
	 * states can occur.
	 */
	private LispVal switchOver(List<Group> groups, BitSet within) {
		List<@Nullable LispVal> guards = new ArrayList<>();
		BitSet claimed = new BitSet();
		for (int i = 0; i < groups.size() - 1; i++) {
			BitSet care = (BitSet) within.clone();
			care.andNot(claimed);
			guards.add(guard(groups.get(i).states(), care));
			claimed.or(groups.get(i).states());
		}
		LispVal form = groups.getLast().value();
		for (int i = groups.size() - 2; i >= 0; i--) {
			LispVal guard = guards.get(i);
			form = guard == null ? groups.get(i).value() : ifForm(guard, groups.get(i).value(), form);
		}
		return form;
	}

	/**
	 * The test that the state is one of {@code members}, where only the {@code care}
	 * states must answer right: one interval per run of members no other cared-for state
	 * interrupts, its bounds a flag read each. Null when it always holds, nil when it
	 * never does.
	 */
	private @Nullable LispVal guard(BitSet members, BitSet care) {
		int last = this.flags.size();
		List<@Nullable LispVal> runs = new ArrayList<>();
		int start = 0;
		boolean member = false;
		for (int state = 0; state <= last; state++) {
			if (members.get(state)) {
				member = true;
			}
			else if (care.get(state)) {
				if (member) {
					runs.add(interval(start, state - 1));
				}
				start = state + 1;
				member = false;
			}
		}
		if (member) {
			runs.add(interval(start, last));
		}
		if (runs.isEmpty()) {
			return LispNil.INSTANCE;
		}
		if (runs.size() == 1) {
			return runs.get(0);
		}
		List<LispVal> or = new ArrayList<>();
		or.add(new LispSymbol(LispNames.OR));
		for (LispVal run : runs) {
			or.add(java.util.Objects.requireNonNull(run));
		}
		return list(or);
	}

	/** States {@code from} to {@code to}: null for all of them. */
	private @Nullable LispVal interval(int from, int to) {
		LispVal lower = from > 0 ? new LispSymbol(this.flags.get(from - 1)) : null;
		LispVal upper = to < this.flags.size() ? call(LispNames.NOT, new LispSymbol(this.flags.get(to))) : null;
		if (lower == null) {
			return upper;
		}
		return upper == null ? lower : call(LispNames.AND, lower, upper);
	}

	private BitSet possibleStates() {
		BitSet states = new BitSet();
		states.set(this.floor, this.flags.size() + 1);
		return states;
	}

	/** A group of states sharing a guard and a value. */
	private record Group(BitSet states, @Nullable LispVal guard, LispVal value) {
	}

	private static List<Group> newestFirst(LinkedHashMap<List<@Nullable LispVal>, BitSet> values) {
		List<Group> groups = new ArrayList<>();
		values.forEach((guardAndValue, states) -> groups
			.add(new Group(states, guardAndValue.get(0), java.util.Objects.requireNonNull(guardAndValue.get(1)))));
		groups.sort(Comparator.comparingInt((Group group) -> group.states().length()).reversed());
		return groups;
	}

	/**
	 * One order of every item of the lists that keeps each list's, ties broken by the
	 * last list's order and then by the newer lists'; where the lists disagree, the items
	 * left go in that order.
	 */
	private static <T> List<T> merge(List<List<T>> orders) {
		Map<T, Integer> rank = new LinkedHashMap<>();
		for (List<T> order : orders.reversed()) {
			for (T item : order) {
				rank.putIfAbsent(item, rank.size());
			}
		}
		Map<T, List<T>> successors = new HashMap<>();
		Map<T, Integer> predecessors = new HashMap<>();
		for (List<T> order : orders) {
			for (int i = 0; i + 1 < order.size(); i++) {
				successors.computeIfAbsent(order.get(i), item -> new ArrayList<>()).add(order.get(i + 1));
				predecessors.merge(order.get(i + 1), 1, Integer::sum);
			}
		}
		PriorityQueue<T> ready = new PriorityQueue<>(Comparator.comparingInt(rank::get));
		for (T item : rank.keySet()) {
			if (!predecessors.containsKey(item)) {
				ready.add(item);
			}
		}
		List<T> merged = new ArrayList<>(rank.size());
		Set<T> placed = new HashSet<>();
		while (merged.size() < rank.size()) {
			if (ready.isEmpty()) {
				rank.keySet().stream().filter(item -> !placed.contains(item)).findFirst().ifPresent(ready::add);
			}
			T item = ready.poll();
			if (!placed.add(item)) {
				continue;
			}
			merged.add(item);
			for (T next : successors.getOrDefault(item, List.of())) {
				if (predecessors.merge(next, -1, Integer::sum) == 0 && !placed.contains(next)) {
					ready.add(next);
				}
			}
		}
		return merged;
	}

	private static LispVal ifForm(LispVal test, LispVal then, LispVal otherwise) {
		return call(LispNames.IF, test, then, otherwise);
	}

	private static LispVal call(String operator, LispVal... arguments) {
		List<LispVal> elements = new ArrayList<>();
		elements.add(new LispSymbol(operator));
		elements.addAll(Arrays.asList(arguments));
		return list(elements);
	}

	private static LispVal list(List<LispVal> elements) {
		LispVal list = LispNil.INSTANCE;
		for (LispVal element : elements.reversed()) {
			list = new LispCons(element, list);
		}
		return list;
	}

}
