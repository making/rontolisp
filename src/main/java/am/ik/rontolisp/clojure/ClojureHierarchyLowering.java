package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Hierarchy forms of the Clojure lowering: the derivation runtime and its operators.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureHierarchyLowering {

	private ClojureHierarchyLowering() {
	}

	/**
	 * The global hierarchy value: a map of {@code :parents}, {@code :ancestors} and
	 * {@code :descendants} tables, rebound by every global {@code derive}/
	 * {@code underive}. A lone {@code %} no mangled identifier spells, so no user
	 * definition can collide with it (like the multimethod helpers).
	 */
	static final String HIERARCHY_GLOBAL = "C%H-GLOBAL";

	static final String HIERARCHY_DISPATCH = "C%H-DISPATCH";

	/** The global hierarchy value as a form. */
	static LispVal hierarchyGlobal() {
		return new LispSymbol(HIERARCHY_GLOBAL);
	}

	/**
	 * A raw call over already-built forms: the hierarchy runtime is Common Lisp, so its
	 * heads name core operations directly (never mangled, never re-lowered).
	 */
	static LispVal hfn(String head, LispVal... args) {
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(head));
		out.addAll(List.of(args));
		return ClojureLowerUtil.list(out);
	}

	/** A hierarchy map key: the keyword wrapper over its spelling. */
	static LispVal hkey(String spelling) {
		return ClojureCollectionLowering.keywordForm(spelling);
	}

	static LispVal hdefun(String name, List<LispVal> params, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(ClojureLowerUtil.sym("defun"));
		form.add(new LispSymbol(name));
		form.add(ClojureLowerUtil.list(params));
		form.addAll(List.of(body));
		return ClojureLowerUtil.list(form);
	}

	/**
	 * A labels binding: {@code (name (params) body)} as one binding form.
	 */
	static LispVal hfnDef(String name, List<LispVal> params, LispVal body) {
		return ClojureLowerUtil.list(new LispSymbol(name), ClojureLowerUtil.list(params), body);
	}

	/**
	 * A labels form over prebuilt bindings: no inline nesting, so the parentheses stay
	 * countable.
	 */
	static LispVal hlabels(List<LispVal> fns, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(ClojureLowerUtil.sym("labels"));
		form.add(ClojureLowerUtil.list(fns));
		form.addAll(List.of(body));
		return ClojureLowerUtil.list(form);
	}

	/**
	 * A let over prebuilt bindings (sequential, like the rest of the lowering).
	 */
	static LispVal hlet(List<LispVal> bindings, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(ClojureLowerUtil.sym("let*"));
		form.add(ClojureLowerUtil.list(bindings));
		form.addAll(List.of(body));
		return ClojureLowerUtil.list(form);
	}

	static LispVal hmiss(LispSymbol miss) {
		return ClojureLowerUtil.list(miss, hfn("LIST", ClojureLowering.NIL_CONST));
	}

	/**
	 * The hierarchy runtime, spliced once behind the false binding when the program uses
	 * hierarchies: set helpers over the wrapped-set shape, the global hierarchy value,
	 * the transitive {@code isa?}, and the multimethod miss search (candidates through
	 * {@code isa?}, the strictly most specific, {@code prefer-method} ties). Pure
	 * lowering over the shared table runtime, so every backend runs it unchanged.
	 */
	static List<LispVal> hierarchyRuntime(ClojureLowering ctx) {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol table = new LispSymbol("table");
		LispSymbol key = new LispSymbol("key");
		LispSymbol setv = new LispSymbol("setv");
		LispSymbol member = new LispSymbol("member");
		LispSymbol members = new LispSymbol("members");
		LispSymbol nt = new LispSymbol("nt");
		LispSymbol miss = new LispSymbol("miss");
		LispSymbol found = new LispSymbol("found");
		LispSymbol pl = new LispSymbol("pl");
		LispSymbol p = new LispSymbol("p");
		LispSymbol acc = new LispSymbol("acc");
		LispSymbol x = new LispSymbol("x");
		LispSymbol lst = new LispSymbol("lst");
		LispSymbol h = new LispSymbol("h");
		LispSymbol child = new LispSymbol("child");
		LispSymbol parent = new LispSymbol("parent");
		LispSymbol parents = new LispSymbol("parents");
		LispSymbol anc = new LispSymbol("anc");
		LispSymbol desc = new LispSymbol("desc");
		LispSymbol done = new LispSymbol("done");
		LispSymbol c = new LispSymbol("c");
		LispSymbol c0 = new LispSymbol("c0");
		LispSymbol as = new LispSymbol("as");
		LispSymbol stack = new LispSymbol("stack");
		LispSymbol ps = new LispSymbol("ps");
		LispSymbol parts = new LispSymbol("parts");
		LispSymbol pc = new LispSymbol("pc");
		LispSymbol d = new LispSymbol("d");
		LispSymbol i = new LispSymbol("i");
		LispSymbol n = new LispSymbol("n");
		LispSymbol m = new LispSymbol("m");
		LispSymbol y = new LispSymbol("y");
		LispSymbol others = new LispSymbol("others");
		LispSymbol cs = new LispSymbol("cs");
		LispSymbol cands = new LispSymbol("cands");
		LispSymbol hier = new LispSymbol("hier");
		LispSymbol prefers = new LispSymbol("prefers");
		LispSymbol methods = new LispSymbol("methods");
		LispSymbol dv = new LispSymbol("dv");
		LispSymbol args = new LispSymbol("args");
		LispSymbol name = new LispSymbol("name");
		LispSymbol def = new LispSymbol("default");
		LispSymbol meth = new LispSymbol("meth");
		LispSymbol surv = new LispSymbol("surv");
		LispSymbol pick = new LispSymbol("pick");
		LispSymbol ss = new LispSymbol("ss");
		LispSymbol s = new LispSymbol("s");
		// (defun c%h-copy-table (table) ...)
		runtime.add(hdefun("C%H-COPY-TABLE", List.of(table),
				ClojureCollectionLowering.tableFromPlist(ClojureCollectionLowering.tablePlist(table))));
		// (defun c%h-empty-set () (list :c%set (make-hash-table ...)))
		runtime.add(hdefun("C%H-EMPTY-SET", List.of(),
				hfn("LIST", ClojureCollectionLowering.SET_TAG, ClojureCollectionLowering.makeTable())));
		// (defun c%h-get-set (table key) ...) with a nil-table guard
		runtime
			.add(hdefun("C%H-GET-SET", List.of(table, key),
					hfn("IF", hfn("NULL", table), hfn("C%H-EMPTY-SET"),
							ClojureLowerUtil.letForm(List.of(hmiss(miss)), List.of(ClojureLowerUtil.letForm(
									List.of(ClojureLowerUtil.list(found, hfn("GETHASH", key, table, miss))),
									List.of(hfn("IF", hfn("EQ", found, miss), hfn("C%H-EMPTY-SET"), found))))))));
		// (defun c%h-set-add (setv member) ...)
		runtime.add(hdefun("C%H-SET-ADD", List.of(setv, member),
				ClojureLowerUtil.letForm(List.of(ClojureLowerUtil.list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", setv)))),
						List.of(hfn("SETF", hfn("GETHASH", member, nt), member),
								hfn("LIST", ClojureCollectionLowering.SET_TAG, nt)))));
		// (defun c%h-add-all (setv members) ...)
		LispVal addAllWalk = hfnDef("WALK", List.of(p),
				hfn("IF", hfn("NULL", p), ClojureLowering.NIL_CONST, hfn("PROGN",
						hfn("SETF", hfn("GETHASH", hfn("CAR", p), nt), hfn("CAR", p)), hfn("WALK", hfn("CDR", p)))));
		runtime.add(hdefun("C%H-ADD-ALL", List.of(setv, members),
				hlet(List.of(ClojureLowerUtil.list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", setv)))),
						hlabels(List.of(addAllWalk), hfn("WALK", members)),
						hfn("LIST", ClojureCollectionLowering.SET_TAG, nt))));
		// (defun c%h-set-list (setv) ...): the members of a set wrapper
		LispVal setListWalk = hfnDef("WALK", List.of(p, acc), hfn("IF", hfn("NULL", p), acc,
				hfn("WALK", hfn("CDR", hfn("CDR", p)), hfn("CONS", hfn("CAR", p), acc))));
		runtime.add(hdefun("C%H-SET-LIST", List.of(setv),
				hlet(List.of(ClojureLowerUtil.list(pl, ClojureCollectionLowering.tablePlist(hfn("CADR", setv)))),
						hlabels(List.of(setListWalk), hfn("WALK", pl, ClojureLowering.NIL_CONST)))));
		// (defun c%h-mem? (x lst) ...): equal membership, t-or-nil
		LispVal memWalk = hfnDef("WALK", List.of(p), hfn("IF", hfn("NULL", p), ClojureLowering.NIL_CONST,
				hfn("IF", hfn("EQUAL", hfn("CAR", p), x), ClojureLowering.TRUE_CONST, hfn("WALK", hfn("CDR", p)))));
		runtime.add(hdefun("C%H-MEM?", List.of(x, lst), hlabels(List.of(memWalk), hfn("WALK", lst))));
		// (defun c%h-rebuild (parents) ...): transitive ancestors and descendants
		LispVal rebuildEach = hfnDef("EACH", List.of(ps),
				hfn("IF", hfn("NULL", ps), ClojureLowering.NIL_CONST,
						hfn("PROGN", hfn("SETF", acc, hfn("C%H-ADD-ALL", acc,
								hfn("CONS", hfn("CAR", ps),
										hfn("C%H-SET-LIST", hfn("WALK", hfn("CAR", ps), hfn("CONS", c, stack)))))),
								hfn("EACH", hfn("CDR", ps)))));
		LispVal walkFn = hfnDef("WALK", List.of(c, stack),
				hfn("IF", hfn("EQ", hfn("GETHASH", c, done, miss), miss),
						hfn("IF", hfn("C%H-MEM?", c, stack), hfn("C%H-EMPTY-SET"),
								hlet(List.of(ClojureLowerUtil.list(acc, hfn("C%H-EMPTY-SET"))),
										hlabels(List.of(rebuildEach),
												hfn("EACH", hfn("C%H-SET-LIST", hfn("C%H-GET-SET", parents, c)))),
										hfn("SETF", hfn("GETHASH", c, done), ClojureLowering.TRUE_CONST),
										hfn("SETF", hfn("GETHASH", c, anc), acc), acc)),
						hfn("C%H-GET-SET", anc, c)));
		LispVal driveFn = hfnDef("DRIVE", List.of(p), hfn("IF", hfn("NULL", p), ClojureLowering.NIL_CONST, hfn("PROGN",
				hfn("WALK", hfn("CAR", p), ClojureLowering.NIL_CONST), hfn("DRIVE", hfn("CDR", hfn("CDR", p))))));
		LispVal invEach = hfnDef("EACH2", List.of(as, c0),
				hfn("IF", hfn("NULL", as), ClojureLowering.NIL_CONST,
						hfn("PROGN",
								hfn("SETF", hfn("GETHASH", hfn("CAR", as), desc),
										hfn("C%H-SET-ADD", hfn("C%H-GET-SET", desc, hfn("CAR", as)), c0)),
								hfn("EACH2", hfn("CDR", as), c0))));
		LispVal invFn = hfnDef("INV", List.of(p), hfn("IF", hfn("NULL", p), ClojureLowering.NIL_CONST,
				hfn("PROGN",
						hlabels(List.of(invEach), hfn("EACH2", hfn("C%H-SET-LIST", hfn("CADR", p)), hfn("CAR", p))),
						hfn("INV", hfn("CDR", hfn("CDR", p))))));
		runtime.add(hdefun("C%H-REBUILD", List.of(parents),
				hlet(List.of(ClojureLowerUtil.list(anc, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(desc, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(done, ClojureCollectionLowering.makeTable()), hmiss(miss)),
						hlabels(List.of(walkFn, driveFn, invFn),
								hfn("DRIVE", ClojureCollectionLowering.tablePlist(parents)),
								hfn("INV", ClojureCollectionLowering.tablePlist(anc))),
						hfn("LIST", anc, desc))));
		// (defun c%h-new-map (parents anc desc) ...): the three tables as a hierarchy
		// value
		LispSymbol newParents = new LispSymbol("new-parents");
		LispSymbol newAnc = new LispSymbol("new-anc");
		LispSymbol newDesc = new LispSymbol("new-desc");
		LispVal newMap = ClojureCollectionLowering.tableFromPlist(
				hfn("LIST", hkey("parents"), newParents, hkey("ancestors"), newAnc, hkey("descendants"), newDesc));
		runtime.add(hdefun("C%H-NEW-MAP", List.of(newParents, newAnc, newDesc), newMap));
		LispVal remake = hfn("C%H-NEW-MAP", parents, hfn("CAR", parts), hfn("CAR", hfn("CDR", parts)));
		// (defun c%h-derive (h child parent) ...)
		runtime.add(hdefun("C%H-DERIVE", List.of(h, child, parent),
				hlet(List.of(ClojureLowerUtil.list(parents, hfn("C%H-COPY-TABLE", hfn("GETHASH", hkey("parents"), h)))),
						hfn("SETF", hfn("GETHASH", child, parents),
								hfn("C%H-SET-ADD", hfn("C%H-GET-SET", parents, child), parent)),
						hlet(List.of(ClojureLowerUtil.list(parts, hfn("C%H-REBUILD", parents))), remake))));
		// (defun c%h-underive (h child parent) ...)
		runtime.add(hdefun("C%H-UNDERIVE", List.of(h, child, parent),
				hlet(List.of(ClojureLowerUtil.list(parents, hfn("C%H-COPY-TABLE", hfn("GETHASH", hkey("parents"), h))),
						hmiss(miss)),
						hlet(List.of(ClojureLowerUtil.list(pc, hfn("GETHASH", child, parents, miss))),
								hfn("IF", hfn("EQ", pc, miss), ClojureLowering.NIL_CONST,
										hlet(List.of(ClojureLowerUtil.list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", pc)))),
												hfn("REMHASH", parent, nt),
												hfn("SETF", hfn("GETHASH", child, parents),
														hfn("LIST", ClojureCollectionLowering.SET_TAG, nt))))),
						hlet(List.of(ClojureLowerUtil.list(parts, hfn("C%H-REBUILD", parents))), remake))));
		// (defun c%h-vec-isa? (h c p i n m) ...): element-wise vector derivation
		runtime.add(hdefun("C%H-VEC-ISA?", List.of(h, c, p, i, n, m),
				hfn("IF", hfn("NOT", hfn("EQL", n, m)), ClojureLowering.NIL_CONST,
						hfn("IF", hfn(">=", i, n), ClojureLowering.TRUE_CONST,
								hfn("IF", hfn("C%H-ISA?", h, hfn("AREF", c, i), hfn("AREF", p, i)),
										hfn("C%H-VEC-ISA?", h, c, p, hfn("+", i, new LispInteger(1)), n, m),
										ClojureLowering.NIL_CONST)))));
		// (defun c%h-not-empty (setv) ...): the oracle's not-empty over a reader's set
		runtime.add(hdefun("C%H-NOT-EMPTY", List.of(setv),
				hfn("IF", hfn("EQL", hfn("HASH-TABLE-COUNT", hfn("CADR", setv)), new LispInteger(0)),
						ClojureLowering.NIL_CONST, setv)));
		// isa? and the parents/ancestors/descendants reads, the class rows' versions
		// where a class is spelled
		if (ctx.usedClassChains) {
			runtime.addAll(classChainRuntime());
			runtime.add(supersForm(ctx.pendingClassRows(), false));
		}
		else {
			runtime.add(isaDefun(false));
			runtime.addAll(readerDefuns(false));
		}
		runtime.add(hdefun("C%H-EMPTY", List.of(),
				ClojureCollectionLowering.tableFromPlist(hfn("LIST", hkey("parents"),
						ClojureCollectionLowering.makeTable(), hkey("ancestors"), ClojureCollectionLowering.makeTable(),
						hkey("descendants"), ClojureCollectionLowering.makeTable()))));
		// (defun c%h-preferred? (x y prefers) ...)
		runtime.add(hdefun("C%H-PREFERRED?", List.of(x, y, prefers),
				hlet(List.of(hmiss(miss)), hfn("IF", hfn("EQ", hfn("GETHASH", hfn("CONS", x, y), prefers, miss), miss),
						ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST))));
		// (defun c%h-more-specific? (c d hier) ...): c descends from d, not vice versa
		runtime.add(hdefun("C%H-MORE-SPECIFIC?", List.of(c, d, hier),
				hfn("IF", hfn("C%H-ISA?", hier, c, d),
						hfn("IF", hfn("C%H-ISA?", hier, d, c), ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST),
						ClojureLowering.NIL_CONST)));
		// (defun c%h-survivors (cands hier prefers) ...): undominated candidates
		LispVal badFn = hfnDef("BAD?", List.of(d, others),
				hfn("IF", hfn("NULL", others), ClojureLowering.NIL_CONST,
						hfn("IF",
								hfn("AND", hfn("C%H-MORE-SPECIFIC?", hfn("CAR", others), d, hier),
										hfn("NOT", hfn("EQUAL", hfn("CAR", others), d)),
										hfn("NOT", hfn("C%H-PREFERRED?", d, hfn("CAR", others), prefers))),
								ClojureLowering.TRUE_CONST, hfn("BAD?", d, hfn("CDR", others)))));
		LispVal keepFn = hfnDef("KEEP", List.of(cs, acc), hfn("IF", hfn("NULL", cs), acc, hfn("KEEP", hfn("CDR", cs),
				hfn("IF", hfn("BAD?", hfn("CAR", cs), cands), acc, hfn("CONS", hfn("CAR", cs), acc)))));
		runtime.add(hdefun("C%H-SURVIVORS", List.of(cands, hier, prefers),
				hlabels(List.of(badFn, keepFn), hfn("KEEP", cands, ClojureLowering.NIL_CONST))));
		// (defun c%h-candidates (methods hier dv) ...): methods the value descends from
		LispVal candWalk = hfnDef("WALK", List.of(p, acc),
				hfn("IF", hfn("NULL", p), acc, hfn("WALK", hfn("CDR", hfn("CDR", p)),
						hfn("IF", hfn("C%H-ISA?", hier, dv, hfn("CAR", p)), hfn("CONS", hfn("CAR", p), acc), acc))));
		runtime.add(hdefun("C%H-CANDIDATES", List.of(methods, hier, dv),
				hlet(List.of(ClojureLowerUtil.list(pl, ClojureCollectionLowering.tablePlist(methods))),
						hlabels(List.of(candWalk), hfn("WALK", pl, ClojureLowering.NIL_CONST)))));
		// (defun c%h-pick (surv prefers) ...): the survivor preferred over every other
		LispVal beatsFn = hfnDef("BEATS-ALL?", List.of(s, others),
				hfn("IF", hfn("NULL", others), ClojureLowering.TRUE_CONST,
						hfn("IF", hfn("EQUAL", hfn("CAR", others), s), hfn("BEATS-ALL?", s, hfn("CDR", others)),
								hfn("IF", hfn("C%H-PREFERRED?", s, hfn("CAR", others), prefers),
										hfn("BEATS-ALL?", s, hfn("CDR", others)), ClojureLowering.NIL_CONST))));
		LispVal findFn = hfnDef("FIND", List.of(ss), hfn("IF", hfn("NULL", ss), ClojureLowering.NIL_CONST,
				hfn("IF", hfn("BEATS-ALL?", hfn("CAR", ss), surv), hfn("CAR", ss), hfn("FIND", hfn("CDR", ss)))));
		runtime.add(hdefun("C%H-PICK", List.of(surv, prefers), hlabels(List.of(beatsFn, findFn), hfn("FIND", surv))));
		// (defun c%h-dispatch (name methods prefers hier default dv args) ...)
		LispVal noMethod = hfn("ERROR",
				hfn("CONCATENATE", ClojureLowerUtil.quoted("string"), LispString.literal("No method in "), name,
						LispString.literal(" for dispatch value: "),
						hfn("RONTOLISP::%CLOJURE-STR-OF", dv, LispString.literal("nil"), ClojureLowering.NIL_CONST)));
		LispVal ambiguous = hfn("ERROR",
				hfn("CONCATENATE", ClojureLowerUtil.quoted("string"),
						LispString.literal("Multiple methods in multimethod '"), name,
						LispString.literal("' match dispatch value: "),
						hfn("RONTOLISP::%CLOJURE-STR-OF", dv, LispString.literal("nil"), ClojureLowering.NIL_CONST),
						LispString.literal(", and neither is preferred")));
		runtime.add(hdefun(HIERARCHY_DISPATCH, List.of(name, methods, prefers, hier, def, dv, args), hlet(
				List.of(ClojureLowerUtil.list(cands, hfn("C%H-CANDIDATES", methods, hier, dv))),
				hfn("IF", hfn("NULL", cands),
						hlet(List.of(hmiss(miss), ClojureLowerUtil.list(meth, hfn("GETHASH", def, methods, miss))),
								hfn("IF", hfn("EQ", meth, miss), noMethod, hfn("APPLY", meth, args))),
						hfn("IF", hfn("NULL", hfn("CDR", cands)),
								hfn("APPLY", hfn("GETHASH", hfn("CAR", cands), methods), args),
								hlet(List.of(ClojureLowerUtil.list(surv, hfn("C%H-SURVIVORS", cands, hier, prefers))),
										hfn("IF", hfn("NULL", surv), ambiguous, hlet(
												List.of(ClojureLowerUtil.list(pick, hfn("C%H-PICK", surv, prefers))),
												hfn("IF", hfn("NULL", pick), ambiguous,
														hfn("APPLY", hfn("GETHASH", pick, methods), args))))))))));
		// the global hierarchy value, bound after its builders
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), hierarchyGlobal(), hfn("C%H-EMPTY")));
		return runtime;
	}

	/**
	 * The class rows: an alist of class name to its bases ({@link ClojureClassBases}),
	 * {@code t} for a class whose bases are unknown ({@link ClojureClassBases#KINDS}).
	 */
	static final String CLASS_SUPERS = "C%H-SUPERS";

	/**
	 * {@code (defun c%h-isa? (h child parent) ...)}: equal, vector-wise, or an ancestor
	 * walk -- and, with {@code classes}, the class rows' walk ({@code clojure.lisp},
	 * {@code %clojure-class-isa}).
	 */
	static LispVal isaDefun(boolean classes) {
		LispSymbol h = new LispSymbol("h");
		LispSymbol child = new LispSymbol("child");
		LispSymbol parent = new LispSymbol("parent");
		return hdefun("C%H-ISA?", List.of(h, child, parent), hfn("IF", hfn("EQUAL", child, parent),
				ClojureLowering.TRUE_CONST,
				hfn("IF", hfn("AND", hfn("VECTORP", child), hfn("VECTORP", parent)),
						hfn("C%H-VEC-ISA?", h, child, parent, new LispInteger(0), hfn("LENGTH", child),
								hfn("LENGTH", parent)),
						hfn("IF",
								hfn("C%H-MEM?", parent,
										hfn("C%H-SET-LIST",
												hfn("C%H-GET-SET", hfn("GETHASH", hkey("ancestors"), h), child))),
								ClojureLowering.TRUE_CONST, classes ? hfn("RONTOLISP::%CLOJURE-CLASS-ISA", h, child,
										parent, new LispSymbol(CLASS_SUPERS)) : ClojureLowering.NIL_CONST))));
	}

	/**
	 * The {@code parents}/{@code ancestors}/{@code descendants} reads: the hierarchy's
	 * sets, nil when empty like the oracle's {@code not-empty}. With {@code classes} a
	 * class keyword adds what its rows say ({@code clojure.lisp}, "Class chains"):
	 * {@code parents} its bases, {@code ancestors} its supers and their ancestors, and
	 * {@code descendants} of one is the oracle's refusal.
	 */
	static List<LispVal> readerDefuns(boolean classes) {
		LispSymbol h = new LispSymbol("h");
		LispSymbol child = new LispSymbol("child");
		LispSymbol rows = new LispSymbol(CLASS_SUPERS);
		LispVal parents = hfn("C%H-GET-SET", hfn("GETHASH", hkey("parents"), h), child);
		LispVal ancestors = hfn("C%H-GET-SET", hfn("GETHASH", hkey("ancestors"), h), child);
		LispVal descendants = hfn("C%H-NOT-EMPTY", hfn("C%H-GET-SET", hfn("GETHASH", hkey("descendants"), h), child));
		if (classes) {
			parents = hfn("C%H-ADD-ALL", parents, hfn("RONTOLISP::%CLOJURE-CLASS-PARENTS", child, rows));
			ancestors = hfn("C%H-ADD-ALL", ancestors, hfn("RONTOLISP::%CLOJURE-CLASS-ANCESTORS", h, child, rows));
			descendants = hfn("IF", hfn("RONTOLISP::%CLOJURE-CLASS-ROWS", child, rows),
					hfn("RONTOLISP::%CLOJURE-CLASS-DESCENDANTS-REFUSAL"), descendants);
		}
		return List.of(hdefun("C%H-PARENTS", List.of(h, child), hfn("C%H-NOT-EMPTY", parents)),
				hdefun("C%H-ANCESTORS", List.of(h, child), hfn("C%H-NOT-EMPTY", ancestors)),
				hdefun("C%H-DESCENDANTS", List.of(h, child), descendants));
	}

	/**
	 * The class rows' versions of {@code c%h-isa?} and the readers, which a session's
	 * earlier hierarchy runtime takes on when a later buffer first spells a class.
	 */
	static List<LispVal> classChainRuntime() {
		List<LispVal> runtime = new ArrayList<>();
		runtime.add(isaDefun(true));
		runtime.addAll(readerDefuns(true));
		return runtime;
	}

	/**
	 * The rows as {@code (setq c%h-supers '((name base ...) ...))}, or with
	 * {@code append} added in front of the ones a session's earlier buffer set.
	 */
	static LispVal supersForm(List<LispVal> rows, boolean append) {
		LispVal quoted = ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(rows));
		LispSymbol global = new LispSymbol(CLASS_SUPERS);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), global,
				append ? hfn("APPEND", quoted, global) : quoted);
	}

	/**
	 * A hierarchy call: {@code derive}/{@code underive} (two forms on the global
	 * hierarchy, three returning an updated hierarchy value), {@code isa?} (two or three,
	 * answering {@code T}-or-false), {@code parents}/{@code ancestors}/
	 * {@code descendants} (one or two, answering sets) and {@code make-hierarchy} (none,
	 * a fresh hierarchy value).
	 */
	static LispVal hierarchyOp(ClojureLowering ctx, List<LispVal> items) {
		String name = ((LispSymbol) items.get(0)).name();
		int n = items.size() - 1;
		ctx.usedHierarchy = true;
		return switch (name) {
			case "derive" -> {
				if (n == 2) {
					yield ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ClojureLowerUtil.list(
							ClojureLowerUtil.sym("setq"), hierarchyGlobal(),
							ClojureLowerUtil.list(new LispSymbol("C%H-DERIVE"), hierarchyGlobal(),
									ClojureDispatchLowering.hierarchyArg(ctx, items.get(1)), ctx.lower(items.get(2)))),
							ClojureLowering.NIL_CONST);
				}
				ClojureLowerUtil.isTrue(n == 3, "derive takes a child and a parent, or a hierarchy and both");
				yield ClojureLowerUtil.list(new LispSymbol("C%H-DERIVE"), ctx.lower(items.get(1)),
						ClojureDispatchLowering.hierarchyArg(ctx, items.get(2)), ctx.lower(items.get(3)));
			}
			case "underive" -> {
				if (n == 2) {
					yield ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ClojureLowerUtil.list(
							ClojureLowerUtil.sym("setq"), hierarchyGlobal(),
							ClojureLowerUtil.list(new LispSymbol("C%H-UNDERIVE"), hierarchyGlobal(),
									ClojureDispatchLowering.hierarchyArg(ctx, items.get(1)), ctx.lower(items.get(2)))),
							ClojureLowering.NIL_CONST);
				}
				ClojureLowerUtil.isTrue(n == 3, "underive takes a child and a parent, or a hierarchy and both");
				yield ClojureLowerUtil.list(new LispSymbol("C%H-UNDERIVE"), ctx.lower(items.get(1)),
						ClojureDispatchLowering.hierarchyArg(ctx, items.get(2)), ctx.lower(items.get(3)));
			}
			case "isa?" -> {
				ClojureLowerUtil.isTrue(n == 2 || n == 3, "isa? takes a child and a parent, or a hierarchy and both");
				LispVal hier = n == 3 ? ctx.lower(items.get(1)) : hierarchyGlobal();
				LispVal child = ClojureDispatchLowering.hierarchyArg(ctx, items.get(n == 3 ? 2 : 1));
				LispVal parent = ClojureDispatchLowering.hierarchyArg(ctx, items.get(n == 3 ? 3 : 2));
				yield ctx.booleanAnswer(ClojureLowerUtil.list(new LispSymbol("C%H-ISA?"), hier, child, parent));
			}
			case "parents", "ancestors", "descendants" -> {
				ClojureLowerUtil.isTrue(n == 1 || n == 2, name + " takes a child, or a hierarchy and a child");
				LispVal hier = n == 2 ? ctx.lower(items.get(1)) : hierarchyGlobal();
				LispVal child = ClojureDispatchLowering.hierarchyArg(ctx, items.get(n == 2 ? 2 : 1));
				// a class keyword may reach the read: every class class answers has its
				// row
				ctx.allClassRows = true;
				ctx.readsDescendants |= name.equals("descendants");
				yield ClojureLowerUtil.list(new LispSymbol("C%H-" + name.toUpperCase(java.util.Locale.ROOT)), hier,
						child);
			}
			case "make-hierarchy" -> {
				ClojureLowerUtil.isTrue(n == 0, "make-hierarchy takes no arguments");
				yield ClojureLowerUtil.list(new LispSymbol("C%H-EMPTY"));
			}
			default -> throw new LispReadException("unknown name: " + name);
		};
	}

	/**
	 * {@code (prefer-method name x y)}: {@code x} wins over {@code y} when both match a
	 * dispatch of {@code name}. The multimethod must be defined -- forward order aside,
	 * the pre-scan declares every {@code defmulti} first -- and answers itself, like
	 * {@code remove-method}.
	 */
	static LispVal preferMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 4, "prefer-method takes a multimethod and two dispatch values");
		String name = ClojureLowerUtil.plainName(items.get(1), "prefer-method");
		String key = ClojureDispatchLowering.multimethodKey(ctx, name);
		LispSymbol prefers = ClojureDispatchLowering.tableGlobal(key, "%prefers");
		ctx.usedHierarchy = true;
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
										ClojureDispatchLowering.dispatchKeyForm(ctx, items.get(2)),
										ClojureDispatchLowering.dispatchKeyForm(ctx, items.get(3))),
								prefers),
						ClojureLowering.TRUE_CONST),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowering.varSym(key)));
	}

	// platform: Java interop over the java: surface

}
