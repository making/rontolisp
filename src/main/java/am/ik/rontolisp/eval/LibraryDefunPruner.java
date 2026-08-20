package am.ik.rontolisp.eval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.FormatRenderer;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispPackageException;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import org.jspecify.annotations.Nullable;

/**
 * The compile-path library tree-shaker: an AST-level pre-pass that drops spliced
 * definitions ({@code defun}/{@code defparameter}/{@code defvar}/{@code defconstant})
 * unreachable from the user program. Two sources are in scope -- the bundled Lisp
 * libraries ({@link LinalgLibrary}, {@link TorchLibrary}, {@link VecLibrary},
 * {@link JsonLibrary}, {@link UrlLibrary}, {@link LispPreludeLibrary}) and any
 * third-party ASDF system {@code LoadInliner} spliced, which it marks with
 * {@code %begin-system} brackets. The splices are <em>whole</em> (linalg.lisp alone is
 * ~100 defuns of which a typical program calls a handful; cl-ppcre is ~300 definitions),
 * and the {@code --optimize} bytecode shakers can only trim after the complete
 * class/module has been serialized once -- and cannot reach a dead Lisp defun at all,
 * since every one of them is reachable from the funcall dispatcher. That is how the
 * ci-spec corpus class approached the JVM 65535 constant-pool ceiling
 * ({@code .kb/library-defun-pruning.md}). Pruning before Pass 1 keeps the pool (and the
 * un-optimized artifact) small for every program.
 *
 * <p>
 * Reachability is deliberately over-approximate ("carve-out" semantics):
 * <ul>
 * <li>a reference is ANY occurrence of a definition's name anywhere in a kept form --
 * operator position, argument position, inside {@code quote}d data, inside
 * {@code (function ...)} -- plus any string literal that contains the name as a substring
 * (so {@code (intern "linalg:norm")} / {@code read-from-string} idioms keep their
 * target);</li>
 * <li>a third-party definition is additionally referenced by an uninterned {@code '#:foo}
 * designator with its member name, and by a string literal whose WHOLE content is its
 * canonical or member name ({@code (find-symbol "FOO" :pkg)}, folded at codegen time --
 * after this pass). The substring rule above is deliberately NOT extended to them:
 * measured over the vendored trees its only hits there are docstring coincidences;</li>
 * <li>the fixpoint starts from every non-library top-level form (the user program is
 * never pruned) and follows references transitively through kept library
 * definitions;</li>
 * <li>the one reference the later macro expansion synthesizes out of thin air,
 * {@code (setf (vec:aref ...))} -> {@code vec:aset}, is a hardcoded edge; usocket is
 * excluded from pruning entirely because its {@code with-*} built-in macros synthesize
 * {@code usocket:socket-close}/{@code usocket::%usock-guard} calls that are not textually
 * present, and so is every other system {@link BuiltinSystems} stands in for;</li>
 * <li>the pass bails (prunes nothing) when a runtime {@code load}/{@code require}
 * survives the {@code LoadInliner} -- loaded code can call anything by name. The CLI
 * additionally skips the pass under {@code --dynamic} (late binding can resolve any name
 * at runtime) and {@code --no-prune}.</li>
 * </ul>
 *
 * The documented limitation of the carve-out: a program that forges a library function's
 * qualified name at runtime from computed strings and invokes it through the eval family
 * ({@code eval}/{@code apply}/computed {@code fboundp}) gets the ordinary "undefined
 * function" error for a pruned function; compile with {@code --dynamic} (or
 * {@code --no-prune}) to keep every library definition.
 *
 * <p>
 * Analysis runs on a {@link PackageResolver#resolveProgram(List) resolved} copy of the
 * program (index-aligned 1:1) so user-source spellings ({@code in-package}, nicknames,
 * bare exported names) -- and the definition names themselves -- read as the canonical
 * ones; forms are removed from the pre-resolution list, so surviving forms stay
 * byte-identical (the resolver fixed-point invariants of the library sources are
 * untouched). The one structural change is that a top-level {@code progn}/{@code
 * eval-when} a macro produced is spliced first, which every backend does anyway as the
 * first step of {@code compile()}. The interpreter path is unaffected: it lazy-loads
 * libraries whole at runtime, where nothing is pruned.
 */
public final class LibraryDefunPruner {

	@Nullable private static volatile Set<String> prunableNames;

	private LibraryDefunPruner() {
	}

	/**
	 * Drops spliced library definitions unreachable from the user program. Returns the
	 * program unchanged when nothing can be pruned (no library definitions present, or a
	 * runtime {@code load}/{@code require} survives).
	 * @param program the top-level forms, after load inlining, user-macro expansion and
	 * every library splice pre-pass
	 * @return the program with unreachable library definitions removed
	 */
	public static List<LispVal> prune(List<LispVal> program) {
		// A macro-produced top-level (progn (defun ...) ...) hides its definitions from
		// this pass -- definitionName returns null for a progn, so the whole block is a
		// root. Every backend flattens progn/locally/eval-when as the first step of
		// compile(), so flattening here only makes the pruner see the same top level the
		// backends do. A program with nothing to splice comes back unchanged.
		List<LispVal> forms = LispMacroExpander.flattenTopLevel(program);
		Provenance provenance = Provenance.scan(forms);
		Set<String> bundled = prunableNames();
		if (!provenance.hasSystems() && !hasBundledDefinition(forms, bundled) && !hasBundledDefstruct(forms)) {
			// Nothing this pass could remove (the common case: a program that splices no
			// library at all). Return the caller's list, not the flattened copy.
			return program;
		}
		// A runtime load/require (a non-literal one the LoadInliner could not inline)
		// can call any function by name, so reachability is unknowable: keep everything
		// except the markers, which are this pass's own bookkeeping.
		if (usesAnySymbol(forms, Set.of(LispNames.LOAD, LispNames.REQUIRE))) {
			return provenance.withoutMarkers(forms);
		}
		// Analyze a resolved copy (index-aligned 1:1 with the original) so user-source
		// spellings match the definitions' canonical names. A resolution error is not
		// this pass's to report: the compiler runs the identical resolution first thing.
		PackageResolver resolver = new PackageResolver();
		List<LispVal> resolved;
		try {
			resolved = resolver.resolveProgram(forms);
		}
		catch (LispPackageException ex) {
			return provenance.withoutMarkers(forms);
		}
		// A BUNDLED library's defstruct is expanded into its generated defuns HERE,
		// before reachability, so each one prunes individually -- as a whole form it
		// would be a root, and a root that spells every accessor. A %struct-definition
		// marker rides in the stream in its place: the compilers consume it to re-run
		// the expansion's registration side effects (setf places, the struct
		// layout/predicate registrations) against their own state. Third-party
		// defstructs stay on the Candidates keyed-unit path (their defmethod
		// specializer gate reads the un-expanded form), and user defstructs stay on the
		// compilers' expansion (which alone has the program's export oracle at the
		// right time).
		BundledStructs structs = BundledStructs.expand(forms, resolved, provenance, resolver);
		if (structs != null) {
			forms = structs.forms();
			resolved = structs.resolved();
			provenance = Provenance.scan(forms);
			Set<String> widened = new HashSet<>(bundled);
			widened.addAll(structs.definedNames());
			bundled = Set.copyOf(widened);
		}
		// Stage A of the bzip2-tree elimination: fold case/ecase arms no possible
		// subject value can reach OUT of third-party definitions, BEFORE reference
		// collection -- a folded arm's references never anchor a definition. Runs only
		// under this pass's own guards (not --dynamic/--no-prune, no surviving runtime
		// load), which are exactly the carve-outs its own soundness needs.
		if (provenance.hasSystems()) {
			ConstantCaseArmPruner.Result folded = ConstantCaseArmPruner.fold(forms, resolved,
					provenance::isPrunableSystem);
			forms = folded.forms();
			resolved = folded.resolved();
		}
		// Definition names come from the RESOLVED copy: a bundled library form is a
		// resolver fixed point and reads the same either way, but a third-party
		// (defun scan ...) under (in-package :cl-ppcre) is only CL-PPCRE:SCAN there --
		// which is also the spelling every reference to it resolves to.
		Map<String, List<Integer>> defsByName = new HashMap<>();
		Map<Integer, List<String>> keysByIndex = new HashMap<>();
		Set<String> thirdParty = new HashSet<>();
		Candidates closCandidates = Candidates.collect(resolved, provenance);
		for (int i = 0; i < resolved.size(); i++) {
			List<String> keys;
			String name = definitionName(resolved.get(i));
			List<String> printerKeys = structs == null ? null : structs.printerKeysAt(i);
			if (printerKeys != null) {
				// A bundled struct's generated print-object method: keyed under the
				// struct's CONSTRUCTORS, so it lives exactly when an instance of that
				// struct can exist. Without it the method is a nameless root and anchors
				// its printer defun -- and through it the accessors it reads -- in every
				// program that splices the library, whether or not it ever builds one.
				keys = printerKeys;
			}
			else if (name != null) {
				boolean fromSystem = provenance.isPrunableSystem(i);
				if (!fromSystem && !bundled.contains(name)) {
					continue;
				}
				if (fromSystem) {
					if (!hasPrunableInitform(resolved.get(i))) {
						continue;
					}
					thirdParty.add(name);
				}
				// A generated %setf- writer of a bundled typed-vector struct is also
				// keyed under its accessor: (setf (acc x) v) and #'(setf acc) spell only
				// ACC textually -- the %setf-acc call is synthesized after this pass.
				keys = structs != null ? structs.keysFor(name) : List.of(name);
			}
			else {
				keys = closCandidates.keysAt(i);
				if (keys == null) {
					continue;
				}
				thirdParty.addAll(keys);
			}
			for (String key : keys) {
				defsByName.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
			}
			keysByIndex.put(i, keys);
		}
		if (defsByName.isEmpty() && closCandidates.methodGates().isEmpty()) {
			return provenance.withoutMarkers(forms);
		}
		Set<String> exact = new HashSet<>(defsByName.keySet());
		for (MethodGates gates : closCandidates.methodGates().values()) {
			exact.addAll(gates.allGateNames());
			thirdParty.addAll(gates.genericNames());
		}
		Prunable prunable = Prunable.of(exact, bundled, thirdParty);
		// Fixpoint from the roots (every top-level form that is not a prunable
		// definition): a definition is kept iff its name is referenced from a kept form,
		// transitively; a defmethod is kept iff its gates are satisfied (see
		// MethodGates).
		Map<String, List<Integer>> methodsByGateName = new HashMap<>();
		for (Map.Entry<Integer, MethodGates> method : closCandidates.methodGates().entrySet()) {
			for (String gateName : method.getValue().allGateNames()) {
				methodsByGateName.computeIfAbsent(gateName, k -> new ArrayList<>()).add(method.getKey());
			}
		}
		Set<String> live = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		Set<String> roots = new LinkedHashSet<>();
		Set<Integer> scanned = new HashSet<>();
		Set<Integer> keptMethods = new HashSet<>();
		// Stage B state: typecase arms gated on an instantiator (see GatedArm).
		Map<String, List<GatedArm>> armsByGate = new HashMap<>();
		List<GatedArm> allArms = new ArrayList<>();
		Map<String, List<String>> instantiatorGates = closCandidates.instantiatorGates();
		List<GatedArm> rootArms = new ArrayList<>();
		for (int i = 0; i < resolved.size(); i++) {
			// A %struct-definition marker is this pass's own bookkeeping: it spells the
			// struct's slot names and initforms, and scanning it would anchor exactly
			// the generated defuns the expansion made prunable. It is kept in the
			// output (the compilers consume it) but contributes no references.
			if (!keysByIndex.containsKey(i) && !closCandidates.methodGates().containsKey(i) && !provenance.isMarker(i)
					&& !isDeclamation(resolved.get(i))
					&& LispMacroExpander.structDefinitionPayload(resolved.get(i)) == null) {
				GateContext ctx = gateContext(i, provenance, instantiatorGates);
				collectReferences(resolved.get(i), prunable, roots, ctx);
				if (ctx != null) {
					rootArms.addAll(ctx.collected);
				}
			}
		}
		// %make-broadcast-stream is reached from a call the expression compilers
		// SYNTHESIZE
		// (LispMacroExpander.expandMakeBroadcastStream's multi-argument branch), so the
		// reference this walk looks for does not exist yet. Root it on the same surface
		// form LispPreludeLibrary selects it by, or the tree-shaker drops the very entry
		// that pass just spliced.
		// uiop:with-temporary-file's two expansion callees are the same case: the macro
		// expands inside the expression compilers, after this walk.
		// %synonym-target is the same case twice over: both compile-path stream seams
		// insert the call inside the expression compilers, and gray.lisp's dispatch
		// helpers -- which call it -- are spliced after this walk.
		// %print-cased is reached from the printing operators, rewritten onto it inside
		// the expression compilers as well.
		// probe-file is reached from uiop:file-exists-p's lowering and from the
		// :if-does-not-exist guard LispMacroExpander.lowerLoadOptions builds -- both
		// inside the expression compilers, after this walk.
		for (String synthesized : List.of(LispNames.MAKE_BROADCAST_STREAM_INTERNAL, LispNames.TEMP_FILE_NAME,
				LispNames.DELETE_FILE_IF_EXISTS, LispNames.SYNONYM_TARGET, LispNames.PRINT_CASED_INTERNAL,
				LispNames.PROBE_FILE)) {
			if (LispPreludeLibrary.referencedBySurfaceForm(synthesized, resolved, true)) {
				roots.add(LispPreludeLibrary.definedName(synthesized));
			}
		}
		for (String name : roots) {
			if (live.add(name)) {
				queue.add(name);
			}
		}
		registerArms(rootArms, Set.of(), armsByGate, allArms, live, queue);
		// A defmethod with no gate at all (an ungated method on a protocol name with no
		// prunable specializer) is a root: kept and scanned like any other root form.
		for (Map.Entry<Integer, MethodGates> method : closCandidates.methodGates().entrySet()) {
			if (method.getValue().satisfiedBy(live) && keptMethods.add(method.getKey())) {
				enqueueReferences(resolved.get(method.getKey()), prunable, live, queue, Set.of(),
						gateContext(method.getKey(), provenance, instantiatorGates), armsByGate, allArms);
			}
		}
		while (!queue.isEmpty()) {
			String name = queue.remove();
			List<Integer> indexes = defsByName.get(name);
			if (indexes != null) {
				for (int index : indexes) {
					if (scanned.add(index)) {
						// A kept form's references go live -- except a CLOS
						// definition's OWN keys: its header spells its class name and
						// accessors, and counting those as references would satisfy its
						// own instantiator gate (an accessor-kept class would read as
						// instantiable). References BETWEEN forms (a superclass list, an
						// :include parent, a specializer) are not the form's own keys
						// and still count.
						List<String> ownKeys = closCandidates.keysAt(index);
						enqueueReferences(resolved.get(index), prunable, live, queue,
								ownKeys == null ? Set.of() : Set.copyOf(ownKeys),
								gateContext(index, provenance, instantiatorGates), armsByGate, allArms);
					}
				}
			}
			List<Integer> gated = methodsByGateName.get(name);
			if (gated != null) {
				for (int index : gated) {
					MethodGates gates = closCandidates.methodGates().get(index);
					if (gates != null && !keptMethods.contains(index) && gates.satisfiedBy(live)) {
						keptMethods.add(index);
						enqueueReferences(resolved.get(index), prunable, live, queue, Set.of(),
								gateContext(index, provenance, instantiatorGates), armsByGate, allArms);
					}
				}
			}
			List<GatedArm> armsOpened = armsByGate.get(name);
			if (armsOpened != null) {
				for (GatedArm arm : armsOpened) {
					openArm(arm, live, queue);
				}
			}
		}
		// An arm still closed at the end never runs (no instantiator of its head is
		// live): delete it from its surviving form, because its body may name pruned
		// definitions that would no longer compile.
		Map<Integer, List<LispCons>> closedArmsByForm = new HashMap<>();
		for (GatedArm arm : allArms) {
			if (!arm.open) {
				closedArmsByForm.computeIfAbsent(arm.formIndex, k -> new ArrayList<>()).add(arm.clause);
			}
		}
		boolean rewritten = false;
		List<LispVal> out = new ArrayList<>(forms.size());
		for (int i = 0; i < forms.size(); i++) {
			if (provenance.isMarker(i)) {
				continue;
			}
			List<String> keys = keysByIndex.get(i);
			if (keys != null && keys.stream().noneMatch(live::contains)) {
				continue;
			}
			if (closCandidates.methodGates().containsKey(i) && !keptMethods.contains(i)) {
				continue;
			}
			List<LispCons> closedArms = closedArmsByForm.get(i);
			if (closedArms == null) {
				out.add(forms.get(i));
			}
			else {
				Set<LispCons> dead = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
				dead.addAll(closedArms);
				out.add(ConstantCaseArmPruner.FormRewriter.withoutArmsParallel(forms.get(i), resolved.get(i), dead));
				rewritten = true;
			}
		}
		return out.size() == forms.size() && !rewritten ? forms : out;
	}

	/** The gate-scan context for a form, or null when arm gating cannot apply to it. */
	private static @Nullable GateContext gateContext(int index, Provenance provenance,
			Map<String, List<String>> instantiatorGates) {
		if (instantiatorGates.isEmpty() || !provenance.isPrunableSystem(index)) {
			return null;
		}
		return new GateContext(index, instantiatorGates);
	}

	private static void registerArms(List<GatedArm> arms, Set<String> excludedOwnKeys,
			Map<String, List<GatedArm>> armsByGate, List<GatedArm> allArms, Set<String> live, Deque<String> queue) {
		for (GatedArm arm : arms) {
			arm.refs.removeAll(excludedOwnKeys);
			allArms.add(arm);
			if (arm.gateNames.stream().anyMatch(live::contains)) {
				openArm(arm, live, queue);
			}
			else {
				for (String gate : arm.gateNames) {
					armsByGate.computeIfAbsent(gate, k -> new ArrayList<>()).add(arm);
				}
			}
		}
	}

	private static void openArm(GatedArm arm, Set<String> live, Deque<String> queue) {
		if (arm.open) {
			return;
		}
		arm.open = true;
		for (String ref : arm.refs) {
			if (live.add(ref)) {
				queue.add(ref);
			}
		}
	}

	private static void enqueueReferences(LispVal form, Prunable prunable, Set<String> live, Deque<String> queue,
			Set<String> excludedOwnKeys) {
		enqueueReferences(form, prunable, live, queue, excludedOwnKeys, null, new HashMap<>(), new ArrayList<>());
	}

	private static void enqueueReferences(LispVal form, Prunable prunable, Set<String> live, Deque<String> queue,
			Set<String> excludedOwnKeys, @Nullable GateContext ctx, Map<String, List<GatedArm>> armsByGate,
			List<GatedArm> allArms) {
		Set<String> refs = new LinkedHashSet<>();
		collectReferences(form, prunable, refs, ctx);
		refs.removeAll(excludedOwnKeys);
		for (String ref : refs) {
			if (live.add(ref)) {
				queue.add(ref);
			}
		}
		if (ctx != null) {
			registerArms(ctx.collected, excludedOwnKeys, armsByGate, allArms, live, queue);
		}
	}

	/**
	 * Drops the {@code %begin-system}/{@code %end-system} provenance markers without
	 * pruning anything -- what the CLI runs instead of {@link #prune} under
	 * {@code --dynamic}/{@code --no-prune}, so those escape hatches emit exactly the
	 * artifact they emitted before the markers existed.
	 * @param program the top-level forms, after the splice chain
	 * @return the program with the markers removed (the same list when it has none)
	 */
	public static List<LispVal> stripSystemMarkers(List<LispVal> program) {
		return Provenance.scan(program).withoutMarkers(program);
	}

	/**
	 * Whether any top-level form defines a bundled library name. Bundled library forms
	 * are resolver fixed points, so this pre-check needs no resolution -- it only decides
	 * whether the pass has anything to do at all.
	 */
	private static boolean hasBundledDefinition(List<LispVal> forms, Set<String> bundled) {
		for (LispVal form : forms) {
			String name = definitionName(form);
			if (name != null && bundled.contains(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The packages whose top-level {@code defstruct}s this pass expands ahead of pruning
	 * (see {@link BundledStructs}) -- the bundled prunable libraries that own a package
	 * of their own. Membership is decided by the STRUCT NAME's package, the same
	 * name-not-origin philosophy the defun rule already has (a user defun named
	 * {@code linalg:norm} is prunable today); these packages are the libraries' reserved
	 * namespaces, so a user definition inside one is already library-space.
	 * json/url/prelude define their helpers in {@code rontolisp::}/{@code cl} and have no
	 * defstruct -- extend this set (never widen to {@code RONTOLISP}) if one ever gains a
	 * package and a record.
	 */
	private static final Set<String> BUNDLED_STRUCT_PACKAGES = Set.of(LispNames.TORCH_PKG, LispNames.LINALG_PKG,
			LispNames.VEC_PKG);

	/**
	 * Whether any top-level form is a bundled-library {@code defstruct} (a defstruct
	 * whose struct name lives in one of {@link #BUNDLED_STRUCT_PACKAGES}). Bundled
	 * library sources are canonical, so like {@link #hasBundledDefinition} this pre-check
	 * needs no resolution.
	 */
	private static boolean hasBundledDefstruct(List<LispVal> forms) {
		for (LispVal form : forms) {
			if (isBundledDefstruct(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isBundledDefstruct(LispVal form) {
		LispMacroExpander.StructDefinedNames summary = LispMacroExpander.defstructDefinedNames(form);
		if (summary == null) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(summary.structName());
		return qn != null && BUNDLED_STRUCT_PACKAGES.contains(PackageRegistry.canonicalBuiltinName(qn.pkg()));
	}

	/**
	 * The early splice of the BUNDLED libraries' {@code defstruct}s: each one is expanded
	 * into the generated defuns the compilers would produce anyway
	 * ({@code LispMacroExpander.expandDefstruct} -- a defstruct has no backend codegen at
	 * all, {@code .kb/defstruct.md}), so every constructor, predicate, copier and
	 * accessor becomes an individually prunable definition instead of one unprunable
	 * root. A {@code (%struct-definition (defstruct ...))} marker takes the original
	 * form's place in BOTH the pre-resolution and the resolved list (index-aligned),
	 * because the expansion is not free-standing: the compilers must re-run its
	 * registration side effects against their own {@code structAccessors}/
	 * {@link ClosRegistry} state, and the marker is the bookkeeping that survives the
	 * cons-rebuilding passes in between ({@code expandTopLevelDefinitions} consumes it).
	 * The export oracle is this pass's resolver, which agrees with the compilers' (both
	 * are post-resolution resolvers of the same program), so the regenerated names match
	 * the spliced defuns exactly.
	 *
	 * <p>
	 * A defstruct the expansion cannot take (a malformed form, an {@code :include} parent
	 * outside the expanded set) stays an unexpanded root and the compilers report the
	 * real error. Third-party ({@code %begin-system}-bracketed) defstructs are never
	 * expanded here.
	 *
	 * @param forms the pre-resolution list with each bundled defstruct replaced by marker
	 * + generated defuns
	 * @param resolved the resolved twin, index-aligned
	 * @param definedNames every generated definition name (joins the bundled prunable set
	 * for this run)
	 * @param keyAliases generated {@code %setf-} writer name -> its keep-keys (the
	 * writer's own name plus the accessor whose synthesized call sites spell only the
	 * accessor)
	 * @param printerKeys index into {@code forms} of a generated {@code print-object}
	 * method -> the keep-keys gating it (its struct's constructors)
	 */
	private record BundledStructs(List<LispVal> forms, List<LispVal> resolved, Set<String> definedNames,
			Map<String, List<String>> keyAliases, Map<Integer, List<String>> printerKeys) {

		List<String> keysFor(String name) {
			List<String> aliased = this.keyAliases.get(name);
			return aliased != null ? aliased : List.of(name);
		}

		/**
		 * The keep-keys of the generated {@code print-object} method at this index, or
		 * null when the form is not one. A {@code (:print-object ...)} struct's method
		 * has no definition name of its own, so without this it would be a ROOT -- kept
		 * unconditionally, anchoring the printer defun and every accessor it reads. It is
		 * keyed under the struct's CONSTRUCTORS instead: the method can only ever apply
		 * to an instance, an instance can only come from a constructor call the reference
		 * scan sees, so a struct nothing builds takes its printer with it. Same soundness
		 * argument as {@code Candidates}' defmethod specializer gate, expressed with the
		 * keyed mechanism the bundled path already has.
		 * @param index the index into {@link #forms}
		 * @return the keep-keys, or null
		 */
		@Nullable List<String> printerKeysAt(int index) {
			return this.printerKeys.get(index);
		}

		@Nullable static BundledStructs expand(List<LispVal> forms, List<LispVal> resolved, Provenance provenance,
				PackageResolver resolver) {
			Set<Integer> targets = new HashSet<>();
			for (int i = 0; i < resolved.size(); i++) {
				if (!provenance.isMarker(i) && !provenance.insideSystem(i) && isBundledDefstruct(resolved.get(i))) {
					targets.add(i);
				}
			}
			if (targets.isEmpty()) {
				return null;
			}
			List<LispVal> outForms = new ArrayList<>(forms.size());
			List<LispVal> outResolved = new ArrayList<>(forms.size());
			Set<String> defined = new HashSet<>();
			Map<String, List<String>> aliases = new HashMap<>();
			Map<Integer, List<String>> printers = new HashMap<>();
			Map<String, Integer> accessors = new HashMap<>();
			ClosRegistry registry = new ClosRegistry();
			for (int i = 0; i < forms.size(); i++) {
				if (!targets.contains(i) || !(resolved.get(i) instanceof LispCons resolvedCons)
						|| !(forms.get(i) instanceof LispCons original)) {
					outForms.add(forms.get(i));
					outResolved.add(resolved.get(i));
					continue;
				}
				List<LispVal> generated;
				try {
					generated = LispMacroExpander.expandDefstruct(resolvedCons, accessors, registry,
							resolver::spellsAsExternal);
				}
				catch (RuntimeException ex) {
					// Not expandable here (e.g. an :include parent this pass has not
					// registered): keep the form as a root; the compilers -- which run
					// the same expansion -- report the real error.
					outForms.add(forms.get(i));
					outResolved.add(resolved.get(i));
					continue;
				}
				outForms.add(LispMacroExpander.structDefinitionMarker(original));
				outResolved.add(LispMacroExpander.structDefinitionMarker(resolvedCons));
				List<String> ctors = constructorNames(resolvedCons, generated);
				for (LispVal g : generated) {
					// The generated forms are canonical, so the same object serves both
					// lists (the resolver is a fixed point on them).
					int index = outForms.size();
					outForms.add(g);
					outResolved.add(g);
					String name = definitionName(g);
					if (name != null) {
						defined.add(name);
						if (name.startsWith("%setf-")) {
							aliases.put(name, List.of(name, name.substring("%setf-".length())));
						}
					}
					else if (!ctors.isEmpty()) {
						// The generated print-object method -- the one nameless form the
						// expansion emits. Gated on the struct's constructors instead of
						// standing as a root; see printerKeysAt.
						printers.put(index, ctors);
					}
				}
			}
			return new BundledStructs(outForms, outResolved, Set.copyOf(defined), Map.copyOf(aliases),
					Map.copyOf(printers));
		}

		/**
		 * The struct's constructor names, in the canonical spelling the expansion just
		 * defined them under: the instantiator summary intersected with the names the
		 * expansion actually generated (which drops the struct name itself -- no defun is
		 * defined under it -- and the spelling the export oracle did not pick).
		 */
		private static List<String> constructorNames(LispCons defstruct, List<LispVal> generated) {
			LispMacroExpander.StructDefinedNames summary = LispMacroExpander.defstructDefinedNames(defstruct);
			if (summary == null) {
				return List.of();
			}
			Set<String> instantiators = Set.copyOf(summary.instantiatorNames());
			List<String> ctors = new ArrayList<>();
			for (LispVal g : generated) {
				String name = definitionName(g);
				if (name != null && instantiators.contains(name)) {
					ctors.add(name);
				}
			}
			return List.copyOf(ctors);
		}
	}

	/**
	 * Whether a third-party definition may be dropped on the strength of its own body. A
	 * {@code defun} always may -- removing it can only ever produce the loud "undefined
	 * function" error the carve-out documents. A {@code defvar}/{@code defparameter}/
	 * {@code defconstant} may only when its initform cannot do anything but compute a
	 * value: dropping the definition drops the initform with it, and a third-party
	 * {@code (defvar *registered* (register-all-types))} whose VALUE nobody reads would
	 * lose the registration silently -- the one way this pass could produce wrong output
	 * instead of a loud error.
	 *
	 * <p>
	 * The judgment is syntactic and denies by default: a literal, a variable read, a
	 * {@code quote}, or a call to one of a small set of allocating/arithmetic operators
	 * with pure arguments. Measured over the vendored trees this keeps a handful of
	 * definitions that are in fact pure ({@code md5::*t*} builds its table with a
	 * {@code loop}) and costs none of the large wins -- the 61 dead {@code defconstant}s
	 * of the cl-postgres stack are literal integers. The bundled libraries keep the
	 * unconditional rule they were audited under.
	 */
	private static boolean hasPrunableInitform(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !(cons.cdr() instanceof LispCons rest)) {
			return false;
		}
		if (LispNames.DEFUN.equals(op.name())) {
			return true;
		}
		// (defvar *x*) with no initform defines nothing to run.
		return !(rest.cdr() instanceof LispCons valueCell) || isPureValue(valueCell.car());
	}

	private static boolean isPureValue(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			// A literal, or a symbol read (including nil/t and a keyword).
			return true;
		}
		if (!(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return false;
		}
		if (LispNames.QUOTE.equals(op.name()) || LispNames.FUNCTION.equals(op.name())) {
			return true;
		}
		if (!PURE_INITFORM_OPERATORS.contains(member(op.name()))) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (!isPureValue(parts.get(i))) {
				return false;
			}
		}
		return true;
	}

	// Operators an initform may call and still be droppable: allocation, arithmetic,
	// comparison and non-destructive readers. Deliberately EXCLUDES anything that could
	// touch state a later form depends on -- setf/push/pushnew, I/O, funcall/apply, and
	// any call to a library-defined function (absent from this set by construction).
	private static final Set<String> PURE_INITFORM_OPERATORS = Set.of("LIST", "LIST*", "CONS", "VECTOR", "MAKE-ARRAY",
			"MAKE-HASH-TABLE", "MAKE-STRING", "MAKE-LIST", "APPEND", "REVERSE", "COPY-LIST", "COPY-SEQ", "COPY-TREE",
			"CONCATENATE", "SUBSEQ", "LENGTH", "IF", "AND", "OR", "NOT", "+", "-", "*", "/", "1+", "1-", "EXPT", "MOD",
			"REM", "FLOOR", "CEILING", "ROUND", "TRUNCATE", "ABS", "MIN", "MAX", "SQRT", "ISQRT", "ASH", "LOGAND",
			"LOGIOR", "LOGXOR", "LOGNOT", "BYTE", "=", "/=", "<", ">", "<=", ">=", "EQ", "EQL", "EQUAL", "EQUALP",
			"CODE-CHAR", "CHAR-CODE", "CHAR", "COERCE", "STRING", "SYMBOL-NAME", "CAR", "CDR", "FIRST", "REST", "NTH",
			"ELT", "AREF", "GETHASH", "NULL", "ZEROP", "PLUSP", "MINUSP");

	/**
	 * Whether the top-level form is a {@code declaim}/{@code proclaim}. Both expand to
	 * {@code nil} on every backend, so nothing they name can be called through them --
	 * yet an {@code (inline F)} / {@code (ftype (function ...) F)} specifier would
	 * otherwise anchor F as a root. The form itself STAYS in the program (a top-level
	 * {@code (declaim (special ...))} is read by {@code SpecialVarCollector}); only its
	 * symbol occurrences stop counting as references.
	 */
	private static boolean isDeclamation(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)) {
			return false;
		}
		String member = member(op.name());
		return LispNames.DECLAIM.equals(member) || LispNames.PROCLAIM.equals(member);
	}

	/**
	 * Which ASDF system each top-level form was spliced from, read from the
	 * {@code %begin-system}/{@code %end-system} brackets {@code LoadInliner} emits.
	 * Brackets nest with {@code :depends-on} and the innermost one wins, which is what
	 * keeps a built-in system spliced as a dependency (usocket) out of a third-party
	 * system's provenance.
	 *
	 * @param systemAt the innermost enclosing system name per index, {@code null} outside
	 * every bracket
	 * @param marker whether the form at that index is a bracket marker (dropped from the
	 * output)
	 * @param balanced whether every bracket was closed -- when it is not, provenance is
	 * discarded rather than guessed
	 */
	private record Provenance(@Nullable String[] systemAt, boolean[] marker, boolean balanced) {

		static Provenance scan(List<LispVal> forms) {
			String[] systemAt = new String[forms.size()];
			boolean[] marker = new boolean[forms.size()];
			Deque<String> open = new ArrayDeque<>();
			boolean balanced = true;
			for (int i = 0; i < forms.size(); i++) {
				String begun = beginSystemName(forms.get(i));
				if (begun != null) {
					marker[i] = true;
					open.addLast(begun);
					continue;
				}
				if (isOperator(forms.get(i), LispNames.END_SYSTEM)) {
					marker[i] = true;
					if (open.isEmpty()) {
						balanced = false;
					}
					else {
						open.removeLast();
					}
					continue;
				}
				systemAt[i] = open.peekLast();
			}
			return new Provenance(systemAt, marker, balanced && open.isEmpty());
		}

		boolean isMarker(int index) {
			return this.marker[index];
		}

		boolean hasSystems() {
			for (boolean m : this.marker) {
				if (m) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Whether the form at the index was spliced from a system whose definitions may
		 * be pruned. A built-in system is excluded: {@code usocket}'s {@code with-*}
		 * built-in macros synthesize
		 * {@code usocket:socket-close}/{@code usocket::%usock-guard} calls that are not
		 * textually present in the pre-expansion AST, and the same reasoning covers every
		 * other shim {@code BuiltinSystems} stands in for.
		 */
		boolean isPrunableSystem(int index) {
			String system = this.systemAt[index];
			return this.balanced && system != null && !BuiltinSystems.isBuiltin(system);
		}

		/**
		 * Whether the form sits inside ANY system bracket, built-in ones included -- the
		 * guard that keeps the bundled-defstruct early splice off every spliced system's
		 * forms.
		 */
		boolean insideSystem(int index) {
			return this.systemAt[index] != null;
		}

		List<LispVal> withoutMarkers(List<LispVal> forms) {
			if (!hasSystems()) {
				return forms;
			}
			List<LispVal> out = new ArrayList<>(forms.size());
			for (int i = 0; i < forms.size(); i++) {
				if (!this.marker[i]) {
					out.add(forms.get(i));
				}
			}
			return out;
		}

		@Nullable private static String beginSystemName(LispVal form) {
			if (!isOperator(form, LispNames.BEGIN_SYSTEM) || !(form instanceof LispCons cons)
					|| !(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispString name)) {
				return null;
			}
			return name.value();
		}

		private static boolean isOperator(LispVal form, String operator) {
			return form instanceof LispCons cons && cons.car() instanceof LispSymbol op && operator.equals(op.name());
		}
	}

	/**
	 * The CLOS-definition candidates of the third-party trees: which
	 * {@code defclass}/{@code define-condition}/{@code defstruct}/{@code defgeneric}
	 * forms are keyed (kept iff any defined name is referenced), and which
	 * {@code defmethod} forms are gated (see {@link MethodGates}). Everything the
	 * summaries cannot read stays a root: a malformed form, a {@code defclass} carrying
	 * {@code (:metaclass ...)} (its ensure-class driver runs user {@code :around} code at
	 * load time), a {@code defstruct} whose {@code :include} chain leaves the candidate
	 * set (its inherited accessor names cannot be computed here), and the top-level
	 * {@code let}-over-{@code defmethod} idiom (not a definition form at all --
	 * cl-ppcre's {@code build-replacement-template}; its binding initforms run at load
	 * time).
	 *
	 * @param keyedNames keyed candidate index -> the names any reference to which keeps
	 * the form
	 * @param methodGates defmethod candidate index -> its keep-gates
	 */
	private record Candidates(Map<Integer, List<String>> keyedNames, Map<Integer, MethodGates> methodGates,
			Map<String, List<String>> instantiatorGates) {

		@Nullable List<String> keysAt(int index) {
			return this.keyedNames.get(index);
		}

		static Candidates collect(List<LispVal> resolved, Provenance provenance) {
			Map<Integer, List<String>> keyedNames = new HashMap<>();
			Map<Integer, MethodGates> methodGates = new HashMap<>();
			// A program that ENUMERATES subclasses can reach a class no name ever
			// spells: cl-dbi's find-driver matches (c2mop:class-direct-subclasses
			// (find-class 'dbi-driver)) against a forged string and make-instances the
			// metaobject it finds. Name-level reachability is unsound for classes then,
			// so they all stay roots; defstructs (not enumerable) and the method/generic
			// gates keep working.
			boolean subclassEnumeration = usesAnySymbol(resolved,
					Set.of(LispNames.CLASS_DIRECT_SUBCLASSES, LispNames.CLASS_DIRECT_SUBCLASSES_INTERNAL));
			// Sweep 1: the class-shaped definitions, their instantiator gates, and the
			// generics the trees own (a defgeneric present in prunable provenance).
			Map<String, LispMacroExpander.StructDefinedNames> structBySpelling = new HashMap<>();
			Map<Integer, LispMacroExpander.StructDefinedNames> structAt = new HashMap<>();
			Map<String, List<String>> gateBySpelling = new HashMap<>();
			Set<String> ownedGenerics = new HashSet<>();
			for (int i = 0; i < resolved.size(); i++) {
				if (!provenance.isPrunableSystem(i)) {
					continue;
				}
				LispVal form = resolved.get(i);
				if (isOperatorForm(form, LispNames.DEFSTRUCT)) {
					LispMacroExpander.StructDefinedNames summary = LispMacroExpander.defstructDefinedNames(form);
					if (summary != null) {
						structAt.put(i, summary);
						for (String spelling : spellingsOf(summary.structName())) {
							structBySpelling.put(spelling, summary);
							gateBySpelling.put(spelling, summary.instantiatorNames());
						}
					}
				}
				else if (isOperatorForm(form, LispNames.DEFCLASS) || isOperatorForm(form, LispNames.DEFINE_CONDITION)) {
					if (subclassEnumeration || (isOperatorForm(form, LispNames.DEFCLASS)
							&& LispMacroExpander.defclassHasOption(form, ":METACLASS"))) {
						continue;
					}
					List<String> names = LispMacroExpander.classDefinedNames(form);
					if (names != null) {
						keyedNames.put(i, names);
						// Instantiating a class spells its NAME (make-instance 'c, a
						// subclass's superclass list, error 'c) -- an accessor reference
						// keeps the form for compilability but proves no instance.
						// classDefinedNames puts the name's spellings first.
						List<String> nameSpellings = List.copyOf(spellingsOf(names.get(0)));
						for (String spelling : nameSpellings) {
							gateBySpelling.put(spelling, nameSpellings);
						}
					}
				}
				else if (isOperatorForm(form, LispNames.DEFGENERIC)) {
					String generic = LispMacroExpander.prunableGenericName(form);
					if (generic != null && !PackageRegistry.isClSymbol(LispSymbol.memberName(generic))) {
						List<String> spellings = List.copyOf(spellingsOf(generic));
						keyedNames.put(i, spellings);
						ownedGenerics.addAll(spellings);
					}
				}
			}
			// A defstruct whose accessor keys need an :include parent OUTSIDE the
			// candidate set (or a cycle) stays a root -- its inherited accessor names
			// cannot be derived from the forms at hand.
			for (Map.Entry<Integer, LispMacroExpander.StructDefinedNames> struct : structAt.entrySet()) {
				List<String> slotBases = resolveStructSlotBases(struct.getValue(), structBySpelling);
				if (slotBases == null) {
					continue;
				}
				List<String> keys = new ArrayList<>(struct.getValue().definedNames());
				for (String slotBase : slotBases) {
					keys.addAll(struct.getValue().accessorSpellings(slotBase));
				}
				keyedNames.put(struct.getKey(), keys);
			}
			// Sweep 2: the defmethod gates, now that ownership and the class gates are
			// known.
			for (int i = 0; i < resolved.size(); i++) {
				if (!provenance.isPrunableSystem(i) || !isOperatorForm(resolved.get(i), LispNames.DEFMETHOD)) {
					continue;
				}
				String generic = LispMacroExpander.prunableGenericName(resolved.get(i));
				if (generic == null) {
					continue;
				}
				boolean clProtocolName = PackageRegistry.isClSymbol(LispSymbol.memberName(generic));
				List<String> genericGate = clProtocolName ? List.of() : List.copyOf(spellingsOf(generic));
				// The specializer gate needs the generic's method set to be closed: it
				// holds for a generic the trees own (its defgeneric is a candidate, so a
				// live name keeps a dispatcher even when every method is gated away) and
				// for a CL protocol name (the built-in behavior is the dispatcher of
				// last resort). A method-only local generic keeps its methods once the
				// name is live, or a live call site would compile against no definition
				// at all.
				List<List<String>> specializerGates = new ArrayList<>();
				if (clProtocolName || spellingsOf(generic).stream().anyMatch(ownedGenerics::contains)) {
					for (String specializer : LispMacroExpander.defmethodSpecializerNames(resolved.get(i))) {
						List<String> gate = gateBySpelling.get(specializer);
						if (gate != null) {
							specializerGates.add(gate);
						}
					}
				}
				methodGates.put(i, new MethodGates(genericGate, List.copyOf(specializerGates)));
			}
			return new Candidates(Map.copyOf(keyedNames), Map.copyOf(methodGates), Map.copyOf(gateBySpelling));
		}

		/**
		 * The struct's full slot-base list -- its {@code :include} ancestors' slots
		 * first, then its own, the same order {@code expandDefstruct} merges -- or null
		 * when the chain leaves the candidate set or cycles.
		 */
		@Nullable private static List<String> resolveStructSlotBases(LispMacroExpander.StructDefinedNames struct,
				Map<String, LispMacroExpander.StructDefinedNames> structBySpelling) {
			List<String> slotBases = new ArrayList<>();
			Set<String> visited = new HashSet<>();
			LispMacroExpander.StructDefinedNames current = struct;
			while (true) {
				if (!visited.add(current.structName())) {
					return null;
				}
				slotBases.addAll(0, current.ownSlotBases());
				String parent = current.includeParent();
				if (parent == null) {
					return slotBases;
				}
				LispMacroExpander.StructDefinedNames parentStruct = spellingsOf(parent).stream()
					.map(structBySpelling::get)
					.filter(java.util.Objects::nonNull)
					.findFirst()
					.orElse(null);
				if (parentStruct == null) {
					return null;
				}
				current = parentStruct;
			}
		}

		private static boolean isOperatorForm(LispVal form, String operator) {
			return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& operator.equals(LispSymbol.memberName(op.name())) && cons.isProperList();
		}

		/** Both colon spellings of a qualified name; the name itself otherwise. */
		private static Set<String> spellingsOf(String name) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
			if (qn == null) {
				return Set.of(name);
			}
			return Set.of(PackageRegistry.qualify(qn.pkg(), qn.member()),
					PackageRegistry.qualifyInternal(qn.pkg(), qn.member()));
		}
	}

	/**
	 * When a third-party {@code defmethod} is kept. The GENERIC gate: some spelling of
	 * the generic's name is live -- absent for a CL protocol name
	 * ({@code initialize-instance}, {@code print-object}, {@code close}, ...), whose
	 * calls the expansions synthesize with no textual reference. The SPECIALIZER gates,
	 * one per required parameter specializing on a prunable class/condition/defstruct:
	 * some INSTANTIATOR name of that definition is live -- an instance the method could
	 * apply to can only be made through a reference the scan sees (the class name for
	 * {@code make-instance}/{@code error}/a subclass's superclass list, a struct's
	 * constructors), so a definition none of whose instantiators is live has no
	 * instances, and every method specializing on it is unreachable whatever its generic
	 * does. All gates must hold; a method with no gates at all is a root.
	 *
	 * @param genericGate the generic-name spellings, any of which being live satisfies
	 * the gate; empty = no generic gate
	 * @param specializerGates per specialized parameter, the instantiator names any of
	 * which being live satisfies that parameter's gate
	 */
	private record MethodGates(List<String> genericGate, List<List<String>> specializerGates) {

		boolean satisfiedBy(Set<String> live) {
			if (!this.genericGate.isEmpty() && this.genericGate.stream().noneMatch(live::contains)) {
				return false;
			}
			for (List<String> gate : this.specializerGates) {
				if (gate.stream().noneMatch(live::contains)) {
					return false;
				}
			}
			return true;
		}

		List<String> allGateNames() {
			List<String> names = new ArrayList<>(this.genericGate);
			for (List<String> gate : this.specializerGates) {
				names.addAll(gate);
			}
			return names;
		}

		List<String> genericNames() {
			return this.genericGate;
		}
	}

	/**
	 * Stage B of the bzip2-tree elimination: a {@code typecase}/{@code etypecase} arm
	 * (inside a third-party form) whose clause head names a candidate
	 * struct/class/condition contributes its references only once an INSTANTIATOR of that
	 * definition is live -- the same soundness argument as the defmethod specializer
	 * gate: the arm can only run on an instance, an instance can only be made through a
	 * reference the scan sees, and when an instantiator goes live the arm's references
	 * join the fixpoint (monotone). An arm still closed at the end is DELETED from the
	 * surviving form -- it must be, because its body may name pruned definitions
	 * ({@code #'chipz::%bzip2-decompress}) that would no longer compile. Deliberately NOT
	 * extended to {@code case}: a case key is a symbol, and a symbol needs no
	 * instantiator ({@code ConstantCaseArmPruner} is the sound mechanism there).
	 *
	 * @param formIndex the top-level form the arm sits in
	 * @param clause the arm's clause cons, identified by IDENTITY in the resolved tree
	 * @param gateNames the instantiator names of the head's definition, any of which
	 * being live opens the arm
	 * @param refs the references the arm contributes once open
	 */
	private static final class GatedArm {

		final int formIndex;

		final LispCons clause;

		final List<String> gateNames;

		final Set<String> refs;

		boolean open;

		GatedArm(int formIndex, LispCons clause, List<String> gateNames, Set<String> refs) {
			this.formIndex = formIndex;
			this.clause = clause;
			this.gateNames = gateNames;
			this.refs = refs;
		}

	}

	/** The scan context that turns typecase-arm gating on for a third-party form. */
	private static final class GateContext {

		final int formIndex;

		final Map<String, List<String>> gates;

		final List<GatedArm> collected = new ArrayList<>();

		GateContext(int formIndex, Map<String, List<String>> gates) {
			this.formIndex = formIndex;
			this.gates = gates;
		}

	}

	/**
	 * The name sets the reachability scan matches against, split by how a reference to
	 * them may be spelled.
	 *
	 * @param exact every prunable definition name, matched against a symbol occurrence
	 * verbatim
	 * @param substringScanned the bundled-library names only -- the original carve-out,
	 * where a string literal CONTAINING the name counts. It is deliberately not extended
	 * to third-party names: measured over the vendored trees its only hits there are
	 * docstring coincidences ("...use md5sum-string instead..." would keep
	 * {@code md5:md5sum-string} and, transitively, sixteen more definitions), and keeping
	 * it at ~230 names is also what keeps the scan linear
	 * @param thirdPartyByMember member name -> the third-party definitions with that
	 * member, for the two spellings that name a symbol without naming its package: an
	 * uninterned {@code '#:foo} designator and a whole string literal
	 * ({@code (find-symbol
	 * "FOO" :pkg)}, which is folded at codegen time, after this pass)
	 */
	private record Prunable(Set<String> exact, Set<String> substringScanned,
			Map<String, List<String>> thirdPartyByMember) {

		static Prunable of(Set<String> all, Set<String> bundled, Set<String> thirdParty) {
			Set<String> scanned = new HashSet<>(bundled);
			scanned.retainAll(all);
			Map<String, List<String>> byMember = new HashMap<>();
			for (String name : thirdParty) {
				byMember.computeIfAbsent(LispSymbol.memberName(name), k -> new ArrayList<>()).add(name);
			}
			return new Prunable(Set.copyOf(all), scanned, byMember);
		}
	}

	/**
	 * The union of every definition name in the prunable libraries (linalg, torch, vec,
	 * json + its {@code #'} wrappers, url, prelude). usocket is deliberately absent: its
	 * {@code with-*} built-in macros synthesize calls not textually present in the
	 * pre-expansion AST; torch's one built-in macro ({@code torch:no-grad}) synthesizes
	 * only {@code torch::*grad-enabled*}, which is a hardcoded edge of the macro name
	 * (see {@code collectReferences}), so the rest of the library stays prunable.
	 */
	private static Set<String> prunableNames() {
		Set<String> cached = prunableNames;
		if (cached == null) {
			synchronized (LibraryDefunPruner.class) {
				cached = prunableNames;
				if (cached == null) {
					Set<String> names = new HashSet<>();
					collectDefinitionNames(LinalgLibrary.forms(), names);
					collectDefinitionNames(TorchLibrary.forms(), names);
					collectDefinitionNames(VecLibrary.forms(), names);
					collectDefinitionNames(JsonLibrary.forms(), names);
					collectDefinitionNames(JsonLibrary.wrapperForms(), names);
					collectDefinitionNames(UrlLibrary.forms(), names);
					for (String name : LispPreludeLibrary.names()) {
						collectDefinitionNames(LispPreludeLibrary.formsFor(name), names);
					}
					prunableNames = Set.copyOf(names);
					cached = prunableNames;
				}
			}
		}
		return cached;
	}

	private static void collectDefinitionNames(List<LispVal> forms, Set<String> out) {
		for (LispVal form : forms) {
			String name = definitionName(form);
			if (name != null) {
				out.add(name);
			}
		}
	}

	/**
	 * Returns the defined name of a top-level {@code defun}/{@code defparameter}/
	 * {@code defvar}/{@code defconstant} form, or {@code null} for anything else. A
	 * {@code (defun (setf N) ...)} writer is keyed under N, the same name every
	 * {@code (setf (N ...))} place and {@code #'(setf N)} reference contains textually.
	 *
	 * <p>
	 * The CLOS definition kinds -- {@code defclass}/{@code defgeneric}/
	 * {@code defmethod}/{@code define-condition}/{@code defstruct} -- are not keyed here:
	 * for third-party trees they are {@link Candidates} with their own name sets and
	 * gates (a defmethod is reachable through its generic and its specializers, not
	 * through one name), and outside those trees they stay roots. {@code deftype} stays a
	 * root everywhere (worth 0-13 definitions across the vendored corpus).
	 * {@code defmacro}, {@code define-compiler-macro}, {@code define-modify-macro},
	 * {@code defsetf} and {@code define-setf-expander} never reach this pass at all:
	 * {@code UserMacroExpander} registers and drops them.
	 */
	@Nullable private static String definitionName(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !(cons.cdr() instanceof LispCons rest)) {
			return null;
		}
		String operator = op.name();
		// defconstant rides with defparameter/defvar: the backends compile all three
		// through the same case (a global variable definition), and so do
		// GlobalVarCollector and SpecialVarCollector.
		if (!LispNames.DEFUN.equals(operator) && !LispNames.DEFPARAMETER.equals(operator)
				&& !LispNames.DEFVAR.equals(operator) && !LispNames.DEFCONSTANT.equals(operator)) {
			return null;
		}
		return switch (rest.car()) {
			case LispSymbol name -> name.name();
			// (defun (setf N) ...) -> keyed under N (kept iff N is referenced).
			case LispCons designator when LispNames.DEFUN.equals(operator)
					&& designator.car() instanceof LispSymbol setf && LispNames.SETF.equals(setf.name())
					&& designator.cdr() instanceof LispCons placeCell && placeCell.car() instanceof LispSymbol place ->
				place.name();
			default -> null;
		};
	}

	/**
	 * Collects every reference to a prunable name inside {@code form}: any symbol
	 * occurrence (any position, including quoted data) plus any string literal containing
	 * a prunable name as a substring. The one synthesized edge -- the later
	 * {@code (setf (vec:aref ...))} expansion calls {@code vec:aset} -- is added
	 * alongside {@code vec:aref}.
	 */
	private static void collectReferences(LispVal form, Prunable prunable, Set<String> out) {
		collectReferences(form, prunable, out, null);
	}

	private static void collectReferences(LispVal form, Prunable prunable, Set<String> out, @Nullable GateContext ctx) {
		switch (form) {
			case LispSymbol sym -> {
				String name = sym.name();
				if (prunable.exact().contains(name)) {
					out.add(name);
				}
				if (name.startsWith("#:")) {
					// An uninterned symbol is never a variable or a call; it is a string
					// designator on its way to intern/find-symbol/format, and it names no
					// package (cl-postgres writes (intern (string
					// '#:make-ssl-client-stream)
					// :cl+ssl)). Match it by member name against every third-party
					// definition spelled that way. A KEYWORD deliberately does NOT widen
					// like this: in third-party CL a keyword is overwhelmingly data
					// (plist
					// keys, loop keywords, case labels), and allowing it was measured to
					// rescue exactly one definition across the whole vendored corpus
					// while
					// colliding with seven unrelated keywords.
					addAll(prunable.thirdPartyByMember().get(name.substring(2)), out);
				}
				if (LispNames.VEC_QUALIFIED_AREF.equals(name)) {
					out.add(LispNames.VEC_QUALIFIED_ASET);
				}
				if (LispNames.TORCH_NO_GRAD_QUALIFIED.equals(name)) {
					// (torch:no-grad ...) expands to a let over torch::*grad-enabled*
					// AFTER the pruner runs (LispMacroExpander.expandTorchNoGrad), so
					// the variable is a hardcoded edge of the macro name -- the
					// vec:aref -> vec:aset pattern.
					out.add(LispNames.TORCH_GRAD_ENABLED_QUALIFIED);
				}
			}
			case LispString str -> {
				// Match case-insensitively: the reader upcases, so a lowercase source
				// string like "linalg:ndim" fed to read-from-string names LINALG:NDIM.
				String value = str.value().toUpperCase(java.util.Locale.ROOT);
				for (String name : prunable.substringScanned()) {
					if (value.contains(name)) {
						out.add(name);
					}
				}
				// A WHOLE string literal naming a definition, qualified or not: the
				// (find-symbol "NAME" :pkg) / (intern "PKG:NAME") idioms. find-symbol is
				// folded at codegen time -- after this pass -- so the literal is the only
				// trace of the call it becomes.
				if (prunable.exact().contains(value)) {
					out.add(value);
				}
				addAll(prunable.thirdPartyByMember().get(value), out);
				// A ~/name/ directive inside a FORMAT control is a function reference and
				// the only trace of one: the renderer resolves the name at run time
				// (.kb/format.md), so nothing else in the program mentions it. esrap's
				// parse-error report is built entirely out of ~/esrap:print-terminal/ and
				// ~/esrap::print-result/. Matched like the uninterned-designator case
				// above -- the whole spelling and, for a qualified one, the member name.
				// The scanner is the renderer's own, so "the pruner kept this function"
				// and "the renderer's arm that calls it was injected" cannot disagree.
				for (String directive : FormatRenderer.functionDesignatorNames(value)) {
					if (prunable.exact().contains(directive)) {
						out.add(directive);
					}
					String member = LispSymbol.memberName(directive);
					if (prunable.exact().contains(member)) {
						out.add(member);
					}
					addAll(prunable.thirdPartyByMember().get(member), out);
				}
			}
			case LispCons cons when ctx != null && isTypecaseForm(cons) -> {
				List<LispVal> parts = cons.toList();
				// Head and subject scan normally; each clause whose head names a
				// candidate becomes a GatedArm (see the class comment), the rest scan
				// normally. Nested gatable arms inside an arm's body register
				// independently -- an inner arm's own gate still applies even when the
				// outer one opens.
				collectReferences(parts.get(0), prunable, out, ctx);
				if (parts.size() > 1) {
					collectReferences(parts.get(1), prunable, out, ctx);
				}
				for (int i = 2; i < parts.size(); i++) {
					LispVal clause = parts.get(i);
					if (clause instanceof LispCons clauseCons && clauseCons.car() instanceof LispSymbol head
							&& !isTypecaseDefaultHead(head) && ctx.gates.containsKey(head.name())) {
						Set<String> armRefs = new LinkedHashSet<>();
						collectReferences(clause, prunable, armRefs, ctx);
						ctx.collected.add(new GatedArm(ctx.formIndex, clauseCons, ctx.gates.get(head.name()), armRefs));
					}
					else {
						collectReferences(clause, prunable, out, ctx);
					}
				}
			}
			case LispCons cons when isNameForgingCall(cons) -> {
				// (intern (concatenate 'string "MAKE-" (symbol-name x) suffix) pkg) --
				// sxql's find-constructor -- assembles a NAME out of literal pieces and
				// computed holes and resolves it at run time. The literal pieces form a
				// template; every third-party member name the template can produce
				// counts as referenced. A piece-less assembly (all holes) stays the
				// documented computed-name carve-out.
				java.util.regex.Pattern template = cons.cdr() instanceof LispCons argCell ? nameTemplate(argCell.car())
						: null;
				if (template != null) {
					for (Map.Entry<String, List<String>> entry : prunable.thirdPartyByMember().entrySet()) {
						if (template.matcher(entry.getKey()).matches()) {
							out.addAll(entry.getValue());
						}
					}
				}
				collectReferences(cons.car(), prunable, out, ctx);
				collectReferences(cons.cdr(), prunable, out, ctx);
			}
			case LispCons cons when LispMacroExpander.isReadtableHookRegistration(cons) && cons.isProperList() -> {
				// A reader hook rontolisp's reader can never fire: the registration
				// lowers to a no-op that does not even evaluate the hook, so the #'name
				// naming it is not a reference. ironclad registers its #@ reader this
				// way, and that one defun -- whose body calls read -- was the only read
				// in a whole postmodern program.
				List<LispVal> parts = cons.toList();
				for (LispVal arg : parts.subList(1, parts.size())) {
					if (!LispMacroExpander.isDeadReadtableHook(arg)) {
						collectReferences(arg, prunable, out, ctx);
					}
				}
			}
			case LispCons cons -> {
				collectReferences(cons.car(), prunable, out, ctx);
				collectReferences(cons.cdr(), prunable, out, ctx);
			}
			default -> {
			}
		}
	}

	private static void addAll(@Nullable List<String> names, Set<String> out) {
		if (names != null) {
			out.addAll(names);
		}
	}

	/** Whether the form is a {@code typecase}/{@code etypecase} whose arms may gate. */
	private static boolean isTypecaseForm(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return false;
		}
		String member = member(op.name());
		return LispNames.TYPECASE.equals(member) || LispNames.ETYPECASE.equals(member)
				|| LispNames.CTYPECASE.equals(member);
	}

	private static boolean isTypecaseDefaultHead(LispSymbol head) {
		String member = member(head.name());
		return "T".equals(member) || LispNames.OTHERWISE.equals(member);
	}

	/** Whether the call resolves a string it is handed into a symbol at run time. */
	private static boolean isNameForgingCall(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return false;
		}
		String member = member(op.name());
		return LispNames.INTERN.equals(member) || LispNames.FIND_SYMBOL.equals(member);
	}

	/**
	 * The name pattern a string-assembling argument of {@code intern}/{@code
	 * find-symbol} can produce: literal pieces stay literal, computed pieces become
	 * holes. Handles {@code (concatenate 'string piece...)} and {@code (format nil
	 * "control" args...)} (every directive is a hole). Null when the argument is not such
	 * an assembly or carries no literal piece at all -- a fully computed name is the
	 * documented carve-out.
	 */
	private static java.util.regex.@Nullable Pattern nameTemplate(LispVal arg) {
		if (!(arg instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		String member = member(op.name());
		StringBuilder regex = new StringBuilder();
		boolean anyLiteral = false;
		if ("CONCATENATE".equals(member) && parts.size() >= 3) {
			for (LispVal piece : parts.subList(2, parts.size())) {
				if (piece instanceof LispString s) {
					regex.append(java.util.regex.Pattern.quote(s.value()));
					anyLiteral = true;
				}
				else {
					regex.append(".*");
				}
			}
		}
		else if (LispNames.FORMAT.equals(member) && parts.size() >= 3 && parts.get(2) instanceof LispString control) {
			String text = control.value();
			StringBuilder literal = new StringBuilder();
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c != '~') {
					literal.append(c);
					continue;
				}
				// A directive: skip its prefix parameters and one directive character
				// (a ~/name/ runs to the closing slash), then emit a hole. ~~ is the
				// literal tilde.
				int j = i + 1;
				while (j < text.length()
						&& (Character.isDigit(text.charAt(j)) || ",'+-:@vV#".indexOf(text.charAt(j)) >= 0)) {
					j++;
				}
				if (j >= text.length()) {
					break;
				}
				if (text.charAt(j) == '~') {
					literal.append('~');
					i = j;
					continue;
				}
				if (text.charAt(j) == '/') {
					int close = text.indexOf('/', j + 1);
					j = close < 0 ? text.length() - 1 : close;
				}
				if (literal.length() > 0) {
					regex.append(java.util.regex.Pattern.quote(literal.toString()));
					anyLiteral = true;
					literal.setLength(0);
				}
				regex.append(".*");
				i = j;
			}
			if (literal.length() > 0) {
				regex.append(java.util.regex.Pattern.quote(literal.toString()));
				anyLiteral = true;
			}
		}
		else {
			return null;
		}
		return anyLiteral ? java.util.regex.Pattern.compile(regex.toString()) : null;
	}

	/**
	 * Returns whether any of the given symbol names occurs anywhere in the program,
	 * compared by member name so pre-resolution spellings like {@code cl:load} match.
	 */
	private static boolean usesAnySymbol(List<LispVal> program, Set<String> names) {
		for (LispVal form : program) {
			if (usesAnySymbol(form, names)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesAnySymbol(LispVal form, Set<String> names) {
		return switch (form) {
			case LispSymbol sym -> names.contains(member(sym.name()));
			case LispCons cons -> usesAnySymbol(cons.car(), names) || usesAnySymbol(cons.cdr(), names);
			default -> false;
		};
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
