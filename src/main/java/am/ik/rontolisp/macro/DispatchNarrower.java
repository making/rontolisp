package am.ik.rontolisp.macro;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispVal;

/**
 * The compile-path hook that lets an optimizing backend drop the dispatcher branches of a
 * generic function that NO call site in the program can select, so the tree-shakers then
 * drop the method-body defuns (and everything only they reach) by the reachability they
 * already have.
 *
 * <p>
 * The contract mirrors {@code DeadTypeBranchPruner}'s (compiler package): the analysis
 * must be sound over the WHOLE program -- a generic taken as a value, a designator a
 * runtime symbol could resolve, or a data evaluator in the program must keep every
 * branch. {@link #analyze} runs inside
 * {@link LispMacroExpander#expandTopLevelDefinitions} once the walk is complete (the
 * registry holds every method) and just before the dispatcher slots are filled;
 * {@link #branchSelectable} is then consulted per branch by the dispatcher generator. A
 * {@code null} narrower -- the interpreter, every non-optimizing compile, and
 * {@code --dynamic} -- keeps every dispatcher byte-identical.
 */
public interface DispatchNarrower {

	/**
	 * Analyzes the expanded program. Called once, after the definition walk registered
	 * every class/generic/method and spliced every method-body defun, and before any
	 * dispatcher is generated.
	 * @param program the expanded top-level forms (dispatcher slots still nil)
	 * @param registry the completed registry
	 * @param structAccessors accessor name to slot position (the setf/accessor names
	 * whose call sites are synthesized after this analysis and are therefore invisible to
	 * it)
	 */
	void analyze(List<LispVal> program, ClosRegistry registry, Map<String, Integer> structAccessors);

	/**
	 * Whether some call site the program can execute may select a dispatcher branch with
	 * these specializers. An unanalyzed or escaped generic answers {@code true} for every
	 * branch.
	 * @param genericName the generic function's canonical name
	 * @param specializers the branch's specializer vector (a method's, a meet branch's,
	 * or an exact-tag refinement branch's)
	 * @return false only when no call site can select the branch
	 */
	boolean branchSelectable(String genericName, List<ClosRegistry.Specializer> specializers);

}
