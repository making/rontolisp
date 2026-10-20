package am.ik.rontolisp.clojure;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The core seq/map/higher-order backlog of the Clojure lowering ({@code drop-last},
 * {@code split-at}, {@code juxt}, {@code reduce-kv}, ...): each verb is one call to its
 * spliced {@code rontolisp::%clojure-} worker in {@code clojure.lisp}, after an arity
 * check worded like the oracle's, and names that worker's {@code -v} entry as a value
 * (which checks the count at run time with the same wording). {@code pmap} is
 * {@code map}: there is no thread pool on any backend, and the printed answer is the
 * same.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureCoreLowering {

	private ClojureCoreLowering() {
	}

	/**
	 * A backlog verb in call position, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		// The dispatch is sliced across callSeqHead..callTail below because one
		// switch over every verb made this method past HotSpot's HugeMethodLimit
		// (8,000 B): past it a method is never JIT-compiled, and this one runs once
		// per lowered call. HugeMethodTest pins every slice under the limit. Each
		// slice answers null for a name outside it, so the router tries them in the
		// switch's old order; every verb keeps its old arm. A checked-arithmetic or
		// other new verb joins the slice of its neighbours.
		LispVal answered = callSeqHead(ctx, name, items);
		if (answered != null) {
			return answered;
		}
		answered = callNumbers(ctx, name, items);
		if (answered != null) {
			return answered;
		}
		answered = callSeqRest(ctx, name, items);
		if (answered != null) {
			return answered;
		}
		answered = callPrintAndNs(ctx, name, items);
		if (answered != null) {
			return answered;
		}
		answered = callLoadAndRefusals(ctx, name, items);
		if (answered != null) {
			return answered;
		}
		return callTail(ctx, name, items);
	}

	/**
	 * One slice of the backlog-verb dispatch of {@link #callOf} (see there): the seq
	 * backlog's head and the numeric and array constructors.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null when {@code name} is none of this slice's verbs
	 */
	private static @Nullable LispVal callSeqHead(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "drop-last":
				arity(name, n, 1, 2);
				return n == 1 ? worker(name, new LispInteger(1), ctx.lower(items.get(1)))
						: worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "split-at", "take-last", "nthnext", "nthrest", "update-keys", "update-vals":
				arity(name, n, 2, 2);
				if (name.startsWith("update-")) {
					return worker(name, ctx.lower(items.get(1)), ClojureBindingLowering.realFnValue(ctx, items.get(2)));
				}
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "split-with":
				arity(name, n, 2, 2);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)));
			case "peek", "pop", "not-empty", "seq-to-map-for-destructuring":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "empty", "double", "float", "byte", "short", "num", "bigint", "biginteger", "bigdec", "rationalize",
					"numerator", "denominator", "unchecked-int", "unchecked-long", "unchecked-short", "unchecked-byte",
					"unchecked-char", "unchecked-double", "unchecked-float":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "int", "long":
				arity(name, n, 1, 1);
				return castOf(ctx, name, items.get(1));
			case "byte-array":
				// a byte array (ClojureBytesLowering): of a size or a seq, or of a size
				// and a byte or a seq
				arity(name, n, 1, 2);
				return n == 1 ? worker(name, ctx.lower(items.get(1)))
						: worker("byte-array-2", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "bytes", "aclone":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "parse-long", "parse-double", "parse-boolean", "object-array", "to-array":
				// the portable parsers and the object constructors: one call to the
				// spliced worker, which runs on every backend (no host to call)
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "into-array":
				// the component is the first member's class, or the class TYPE
				// spells: a class name the lowering resolves to a (:C%CLASS name)
				// designator, so every backend reads it -- a computed type travels
				// as its value, a host Class by its name
				arity(name, n, 1, 2);
				return n == 1 ? worker(name, ctx.lower(items.get(1)))
						: worker("into-array-2", intoArrayType(ctx, items.get(1)), ctx.lower(items.get(2)));
			case "int-array", "long-array", "double-array", "float-array", "short-array", "char-array", "boolean-array":
				// a primitive array as a vector: of a size or a seq, or of a size
				// and an init value or a seq
				arity(name, n, 1, 2);
				return n == 1 ? worker(name, ctx.lower(items.get(1)))
						: worker(name + "-2", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "aset-byte":
				arity(name, n, 3, 3);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
			case "to-array-2d":
				// a 2-dimensional Object array as a vector of vectors: each row
				// through to-array
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "aset-int", "aset-long", "aset-double", "aset-float", "aset-short", "aset-boolean", "aset-char":
				// a checked store into a vector: the value cast as the named
				// primitive casts it. Of more than one index the intermediate
				// levels read like aget, as the oracle reads them through (aget
				// array idx). A nil array and a past-the-end index refuse
				// without a message, through the program's exception runtime
				ctx.usedExInfo = true;
				arity(name, n, 3, -1);
				if (n == 3) {
					return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
				}
				LispVal inner = ctx.lower(items.get(1));
				for (int k = 2; k < items.size() - 2; k++) {
					inner = ClojureLowerUtil.list(new LispSymbol(ClojureBytesLowering.AGET), inner,
							ctx.lower(items.get(k)));
				}
				return worker(name, inner, ctx.lower(items.get(items.size() - 2)),
						ctx.lower(items.get(items.size() - 1)));
			case "ints", "longs", "doubles", "floats", "shorts", "chars", "booleans":
				// a cast to the primitive array: nil itself, a vector narrowed
				// like its constructor narrows it, anything else refused
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			default:
				return null;
		}
	}

	/**
	 * One slice of the backlog-verb dispatch of {@link #callOf} (see there): the
	 * unchecked arithmetic, the compare and hash helpers, the bit operations, the
	 * map-entry reader, the sorted walk, the iterator seq and {@code find-keyword}.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null when {@code name} is none of this slice's verbs
	 */
	private static @Nullable LispVal callNumbers(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "unchecked-inc", "unchecked-dec", "unchecked-negate", "unchecked-inc-int", "unchecked-dec-int",
					"unchecked-negate-int":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "unchecked-add", "unchecked-subtract", "unchecked-multiply", "unchecked-add-int",
					"unchecked-subtract-int", "unchecked-multiply-int", "unchecked-divide-int",
					"unchecked-remainder-int":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "comparator":
				arity(name, n, 1, 1);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)));
			case "hash-set":
				return worker("set-of", ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1)));
			case "hash":
				// Util.hasheq, the hash consistent with =
				arity(name, n, 1, 1);
				return worker("hasheq", ctx.lower(items.get(1)));
			case "hash-ordered-coll", "hash-unordered-coll":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "mix-collection-hash", "hash-combine":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "find":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "bit-and", "bit-or", "bit-xor", "bit-and-not":
				// the oracle's on longs, two's complement over 64 bits (clojure.lisp
				// "Bit operations"): variadic, two or more arguments
				arity(name, n, 2, -1);
				return worker(name, ctx.lowers(items, 1).toArray(new LispVal[0]));
			case "bit-not":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "bit-shift-left", "bit-shift-right", "unsigned-bit-shift-right", "bit-set", "bit-clear", "bit-flip",
					"bit-test":
				// a shift count or bit index masked to 6 bits, the answer wrapped
				// to 64 bits; bit-test answers T-or-false
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "ref-history-count":
				// the committed transactions that wrote the ref, capped by its
				// min history; anything but a ref is the oracle's cast failure
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "ref-min-history", "ref-max-history":
				// the bound, or the bound stored (the oracle's intCast),
				// answering the ref
				arity(name, n, 1, 2);
				return n == 1 ? worker(name, ctx.lower(items.get(1)))
						: worker(name + "-set", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "create-struct":
				// the key vector struct and struct-map read; of no keys the
				// oracle's refusal, at run time so a catch takes it
				return worker(name, ctx.lowers(items, 1).toArray(new LispVal[0]));
			case "accessor":
				// the function reading the key out of a struct map
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "prefers":
				// each dispatch value to the set preferred over it
				arity(name, n, 1, 1);
				return prefersOf(ctx, items);
			case "remove-all-methods":
				// every method and preference gone, answering the multimethod
				arity(name, n, 1, 1);
				return removeAllMethodsOf(ctx, items);
			case "extenders":
				// the extended tags as a seq, :object last for the default
				arity(name, n, 1, 1);
				return extendersOf(ctx, items);
			case "find-protocol-impl":
				// the tag row's map, else the Object default's, else nil
				arity(name, n, 2, 2);
				return findImplOf(ctx, items);
			case "find-protocol-method":
				// the tag row's function, else the Object default's, else nil
				arity(name, n, 3, 3);
				return findMethodOf(ctx, items);
			case "cast":
				// X when it is an instance of the class, else the oracle's refusal
				arity(name, n, 2, 2);
				return castCallOf(ctx, items.get(1), items.get(2));
			case "key", "val", "map-entry?":
				arity(name, n, 1, 1);
				return worker("map-entry?".equals(name) ? "map-entry-p" : name, ctx.lower(items.get(1)));
			case "rseq":
				// a sorted map or set walks backwards through its items vector (a view a
				// program building no sorted collection sheds, ClojureArms)
				arity(name, n, 1, 1);
				return worker(name, worker("sorted-items", ctx.lower(items.get(1))));
			case "iterator-seq":
				// the seq of what an iterator steps: a type implementing
				// java.util.Iterator, the iterator over a seq .iterator answers, a host
				// Iterator (ClojureInterfaces)
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "find-keyword":
				arity(name, n, 1, 2);
				return n == 1 ? worker(name, ctx.lower(items.get(1)))
						: worker("find-keyword-2", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			default:
				return null;
		}
	}

	/**
	 * One slice of the backlog-verb dispatch of {@link #callOf} (see there): the seq
	 * backlog's rest.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null when {@code name} is none of this slice's verbs
	 */
	private static @Nullable LispVal callSeqRest(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "subvec":
				arity(name, n, 2, 3);
				return n == 2 ? worker("subvec-from", ctx.lower(items.get(1)), ctx.lower(items.get(2)))
						: worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
			case "dedupe":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "replace":
				arity(name, n, 1, 2);
				return n == 1 ? worker("xf-replace", ctx.lower(items.get(1)))
						: worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "partition-all":
				arity(name, n, 2, 3);
				if (n == 2) {
					// the size is also the step: one evaluation, bound once
					LispSymbol size = ctx.freshTemp();
					return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
							ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(size, ctx.lower(items.get(1))))),
							worker(name, size, size, ctx.lower(items.get(2))));
				}
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
			case "partition-by":
				arity(name, n, 2, 2);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)));
			case "reductions":
				// the running reductions, stopping past a reduced answer's; of
				// one collection the reductions from its first member
				arity(name, n, 2, 3);
				return n == 2
						? worker("reductions-2", ClojureBindingLowering.realFnValue(ctx, items.get(1)),
								ctx.lower(items.get(2)))
						: worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)),
								ctx.lower(items.get(3)));
			case "random-sample":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "bounded-count":
				arity(name, n, 2, 2);
				return boundedCountForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "partitionv":
				// partition's runs, each as a vector, in a vector
				arity(name, n, 2, 4);
				if (n == 2) {
					// the size is also the step: one evaluation, bound once
					LispSymbol size = ctx.freshTemp();
					return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
							ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(size, ctx.lower(items.get(1))))),
							worker(name, size, size, ctx.lower(items.get(2))));
				}
				return n == 3 ? worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)))
						: worker("partitionv-pad", ctx.lower(items.get(1)), ctx.lower(items.get(2)),
								ctx.lower(items.get(3)), ctx.lower(items.get(4)));
			case "partitionv-all":
				arity(name, n, 2, 3);
				if (n == 2) {
					LispSymbol size = ctx.freshTemp();
					return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
							ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(size, ctx.lower(items.get(1))))),
							worker("partitionv-all", size, size, ctx.lower(items.get(2))));
				}
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
			case "splitv-at":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "replicate":
				// N copies of X, like repeat
				arity(name, n, 2, 2);
				return worker("repeat-n", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "xml-seq":
				// the tree-seq over the :content of maps holding it
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			default:
				return null;
		}
	}

	/**
	 * One slice of the backlog-verb dispatch of {@link #callOf} (see there): the printing
	 * verbs and the namespace mappings.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null when {@code name} is none of this slice's verbs
	 */
	private static @Nullable LispVal callPrintAndNs(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "printf":
				// the format printed, answering nil
				arity(name, n, 1, -1);
				return printfOf(ctx, items);
			case "char-escape-string", "char-name-string", "munge", "namespace-munge":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "print-simple", "print-dup":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "print-ctor":
				arity(name, n, 3, 3);
				return worker(name, ctx.lower(items.get(1)), ClojureBindingLowering.realFnValue(ctx, items.get(2)),
						ctx.lower(items.get(3)));
			case "all-ns":
				arity(name, n, 0, 0);
				return worker(name, ctx.knownNamespaces());
			case "create-ns":
				// the namespace made at run time; of a literal, later lower-time
				// lookups find it too
				arity(name, n, 1, 1);
				noteCreatedNamespace(ctx, items.get(1));
				return worker(name, ctx.lower(items.get(1)));
			case "remove-ns":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "loaded-libs":
				arity(name, n, 0, 0);
				return worker(name, ctx.knownLoadedLibs());
			case "intern":
				// over a cell of its own; of a literal namespace, later
				// lower-time lookups find the namespace too
				arity(name, n, 2, 3);
				noteCreatedNamespace(ctx, items.get(1));
				return n == 2 ? worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)))
						: worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)),
								ClojureLowering.TRUE_CONST);
			case "var-set", "with-redefs-fn":
				arity(name, n, 2, 2);
				if (name.equals("with-redefs-fn")) {
					// a computed store may reach the map's vars: the program
					// lowers again with every value-cell var site carrying its
					// root setter, like a computed alter-var-root's
					ctx.needRootSetters();
				}
				return name.equals("var-set") ? worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)))
						: worker(name, ctx.lower(items.get(1)), ClojureBindingLowering.realFnValue(ctx, items.get(2)));
			case "ns-publics", "ns-interns", "ns-map", "ns-refers", "ns-aliases", "ns-imports":
				// what the lowering knows of the namespace: anything computed
				// it cannot know refuses at run time
				arity(name, n, 1, 1);
				return ClojureNamespaceLowering.mappingsOf(ctx, name, items.get(1));
			default:
				return null;
		}
	}

	/**
	 * One slice of the backlog-verb dispatch of {@link #callOf} (see there): the loading
	 * verbs, the host-only refusals and the thread bindings.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null when {@code name} is none of this slice's verbs
	 */
	private static @Nullable LispVal callLoadAndRefusals(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "requiring-resolve":
				arity(name, n, 1, 1);
				return requiringResolveOf(ctx, items);
			case "load-string":
				arity(name, n, 1, 1);
				return items.get(1) instanceof LispString text ? loadStringOf(ctx, text.value())
						: worker("load-string-here", ctx.lower(items.get(1)));
			case "load-file":
				arity(name, n, 1, 1);
				return items.get(1) instanceof LispString path
						? loadStringOf(ctx, ClojureNamespaceLowering.fileTextOf(ctx, path.value()))
						: worker("load-file-here", ctx.lower(items.get(1)));
			case "load-reader":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "compile":
				throw new LispReadException(
						"compile is not supported yet: namespaces compile ahead of time, never at run time");
			case "bean", "supers", "bases":
				throw new LispReadException(
						name + " is not supported yet: host classes are not answered on every backend");
			case "clojure-version":
				throw new LispReadException("clojure-version is not supported yet: no release of Clojure runs here");
			case "destructure":
				throw new LispReadException("destructure is not supported yet: the destructuring map has no lowering");
			case "proxy-mappings", "proxy-name", "proxy-super", "proxy-call-with-super", "construct-proxy",
					"init-proxy", "update-proxy", "get-proxy-class", "method-sig":
				throw new LispReadException(name + " is not supported yet: proxy construction is not reified here");
			case "chunk", "chunk-append", "chunk-buffer", "chunk-cons", "chunk-first", "chunk-next", "chunk-rest",
					"->ArrayChunk":
				throw new LispReadException(name + " is not supported yet: sequences chunk nowhere here");
			case "->Vec", "->VecNode", "->VecSeq", "->Eduction", "-cache-protocol-fn", "-reset-methods":
				throw new LispReadException(
						name + " is not supported yet: construction of clojure.lang internals is not supported");
			case "stream-reduce!", "stream-into!", "stream-seq!", "stream-transduce!":
				throw new LispReadException(name + " is not supported yet: streaming reduction has no lowering");
			case "future-call", "pcalls", "seque", "await1":
				throw new LispReadException(name + " is not supported yet: there is no thread pool on any backend");
			case "send-via", "release-pending-sends", "set-agent-send-executor!", "set-agent-send-off-executor!":
				throw new LispReadException(
						name + " is not supported yet: agents take no custom executor on any backend");
			case "tap>", "add-tap", "remove-tap":
				throw new LispReadException(name + " is not supported yet: taps have no rendezvous design");
			case "enumeration-seq":
				throw new LispReadException(
						"enumeration-seq is not supported yet: host enumerations stop at the host boundary");
			case "resultset-seq":
				throw new LispReadException("resultset-seq is not supported yet: JDBC stops at the host boundary");
			case "get-thread-bindings":
				throw new LispReadException(
						"get-thread-bindings is not supported yet: thread bindings are not reified at run time");
			case "bound-fn*":
				return ClojureStateLowering.boundFnStarOf(ctx, items);
			case "read+string":
				throw new LispReadException(
						"read+string is not supported yet: reading the consumed text needs a position-tracking reader");
			case "with-bindings*":
				return ClojureStateLowering.withBindingsStarOf(ctx, items);
			case "push-thread-bindings", "pop-thread-bindings":
				return ClojureStateLowering.unpairedThreadBindings(name);
			default:
				return null;
		}
	}

	/**
	 * One slice of the backlog-verb dispatch of {@link #callOf} (see there): the var,
	 * metadata, namespace and reader verbs and the higher-order rest.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null when {@code name} is none of this slice's verbs
	 */
	private static @Nullable LispVal callTail(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "find-var":
				return ClojureVarLowering.findVarOf(ctx, items);
			case "tree-seq":
				arity(name, n, 3, 3);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)),
						ClojureBindingLowering.realFnValue(ctx, items.get(2)), ctx.lower(items.get(3)));
			case "flatten":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "min-key", "max-key":
				arity(name, n, 2, -1);
				return ClojureLowerUtil.list(runtime("extreme-key"),
						ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)),
						name.equals("max-key") ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST);
			case "juxt", "every-pred", "some-fn":
				arity(name, n, 1, -1);
				return worker(name, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), fnValues(ctx, items, 1)));
			case "fnil":
				arity(name, n, 2, 4);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)));
			case "reduce-kv":
				arity(name, n, 3, 3);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)),
						ctx.lower(items.get(3)));
			case "iteration":
				// the options stay a run-time list: one map, or keyword arguments the
				// worker reads like the oracle's destructuring of them
				arity(name, n, 1, -1);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)));
			case "run!":
				arity(name, n, 2, 2);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)));
			case "pmap":
				arity(name, n, 2, -1);
				return ClojureSeqLowering.mapForm(ctx, ClojureBindingLowering.realFnValue(ctx, items.get(1)),
						ctx.lowers(items, 2));
			case "with-meta":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "meta":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "type":
				arity(name, n, 1, 1);
				return ClojureDispatchLowering.typeForm(ctx, ctx.lower(items.get(1)));
			case "print-method":
				// the type's print-method a program defined, else the value written as pr
				// writes it ("print-method", clojure.lisp)
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "test":
				arity(name, n, 1, 1);
				return ClojureVarLowering.testOf(ctx.lower(items.get(1)));
			case "var-get":
				arity(name, n, 1, 1);
				return ClojureVarLowering.getOf(ctx.lower(items.get(1)));
			case "alter-var-root":
				return ClojureVarLowering.alterRootOf(ctx, items);
			case "alter-meta!":
				return ClojureVarLowering.alterMetaOf(ctx, items);
			case "reset-meta!":
				return ClojureVarLowering.resetMetaOf(ctx, items);
			case "the-ns", "find-ns", "ns-name":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)), ctx.knownNamespaces());
			case "vary-meta":
				arity(name, n, 2, -1);
				return worker(name, ctx.lower(items.get(1)), ClojureBindingLowering.realFnValue(ctx, items.get(2)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)));
			case "read-string", "read":
				return ClojureReadLowering.callOf(ctx, name, items);
			case "reader-conditional", "tagged-literal":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "inst-ms", "inst-ms*":
				// the Inst protocol's one method: a Date's or a Timestamp's
				// milliseconds, a host Date's or Instant's
				arity(name, n, 1, 1);
				return worker("inst-ms", ctx.lower(items.get(1)));
			case "parse-uuid":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "random-uuid":
				arity(name, n, 0, 0);
				return worker(name);
			case "read-line":
				// the next line of *in*, nil past the end, a closed one's IOException,
				// like the oracle's
				arity(name, n, 0, 0);
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("read-line"),
						ClojureStringLowering.openReader(new LispSymbol("*STANDARD-INPUT*")), ClojureLowering.NIL_CONST,
						ClojureLowering.NIL_CONST);
			default:
				return null;
		}
	}

	/**
	 * A backlog verb as a function value, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(ClojureLowering ctx, String name) {
		return switch (name) {
			case "drop-last", "split-at", "split-with", "take-last", "nthnext", "nthrest", "peek", "pop", "not-empty",
					"dedupe", "replace", "find", "subvec", "key", "val", "rseq", "find-keyword", "partition-all",
					"partition-by", "min-key", "max-key", "juxt", "fnil", "every-pred", "some-fn", "update-keys",
					"update-vals", "reduce-kv", "with-meta", "meta", "vary-meta", "empty", "comparator", "hash-set",
					"bit-and", "bit-or", "bit-xor", "bit-and-not", "bit-not", "bit-shift-left", "bit-shift-right",
					"unsigned-bit-shift-right", "bit-set", "bit-clear", "bit-flip", "bit-test", "int", "long", "double",
					"float", "byte", "short", "num", "bigint", "biginteger", "bigdec", "rationalize", "numerator",
					"denominator", "unchecked-int", "unchecked-long", "unchecked-short", "unchecked-byte",
					"unchecked-char", "unchecked-double", "unchecked-float", "unchecked-inc", "unchecked-dec",
					"unchecked-negate", "unchecked-inc-int", "unchecked-dec-int", "unchecked-negate-int",
					"unchecked-add", "unchecked-subtract", "unchecked-multiply", "unchecked-add-int",
					"unchecked-subtract-int", "unchecked-multiply-int", "unchecked-divide-int",
					"unchecked-remainder-int", "run!", "iteration", "seq-to-map-for-destructuring", "println", "print",
					"prn", "pr", "flush", "read-line", "reader-conditional", "tagged-literal", "inst-ms", "parse-uuid",
					"random-uuid", "iterator-seq", "hash-ordered-coll", "hash-unordered-coll", "mix-collection-hash",
					"hash-combine", "byte-array", "bytes", "aclone", "aset-byte", "print-method", "tree-seq", "flatten",
					"reductions", "random-sample", "partitionv", "partitionv-all", "splitv-at", "replicate", "xml-seq",
					"printf", "char-escape-string", "char-name-string", "print-simple", "print-dup", "print-ctor",
					"munge", "namespace-munge", "create-ns", "remove-ns", "intern", "var-set", "load-string",
					"load-file", "load-reader", "requiring-resolve", "parse-long", "parse-double", "parse-boolean",
					"object-array", "to-array", "to-array-2d", "into-array", "int-array", "long-array", "double-array",
					"float-array", "short-array", "char-array", "boolean-array", "ints", "longs", "doubles", "floats",
					"shorts", "chars", "booleans", "ref-history-count", "ref-min-history", "ref-max-history",
					"create-struct", "accessor", "extenders", "find-protocol-impl", "find-protocol-method", "cast" ->
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime(name + "-v"));
			case "aset-int", "aset-long", "aset-double", "aset-float", "aset-short", "aset-boolean", "aset-char" -> {
				// a checked store as a value: the exception runtime travels, as
				// for the call
				ctx.usedExInfo = true;
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime(name + "-v"));
			}
			case "with-redefs-fn" -> {
				ctx.needRootSetters();
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("with-redefs-fn-v"));
			}
			case "hash" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("hasheq-v"));
			case "inst-ms*" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("inst-ms-v"));
			case "map-entry?" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("map-entry-p-v"));
			case "pmap" -> ClojureSeqLowering.mapValue(ctx);
			case "with-bindings*" -> ClojureStateLowering.withBindingsStarValue(ctx);
			case "bound-fn*" -> ClojureStateLowering.boundFnStarValue(ctx);
			case "find-var" -> ClojureVarLowering.findVarValue();
			case "test" -> ClojureVarLowering.testValue();
			case "type" -> ClojureDispatchLowering.typeValue(ctx);
			case "var-get" -> ClojureVarLowering.getValue();
			case "alter-var-root" -> ClojureVarLowering.alterRootValue(ctx);
			case "alter-meta!", "reset-meta!" -> ClojureVarLowering.metaWriterValue(name);
			case "the-ns", "find-ns", "ns-name" -> {
				LispSymbol arg = ctx.freshTemp();
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(arg),
						worker(name, arg, ctx.knownNamespaces()));
			}
			case "bounded-count" -> {
				// count has no worker of its own: the value is a two-argument
				// lambda over the inlined count, like the call
				LispSymbol count = ctx.freshTemp();
				LispSymbol coll = ctx.freshTemp();
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(count, coll),
						boundedCountForm(ctx, count, coll));
			}
			case "all-ns", "loaded-libs" -> {
				LispVal known = name.equals("all-ns") ? ctx.knownNamespaces() : ctx.knownLoadedLibs();
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(),
						worker(name, known));
			}
			case "ns-publics", "ns-interns", "ns-map", "ns-refers", "ns-aliases", "ns-imports" -> {
				// only what the lowering knows maps; anything computed refuses
				LispSymbol designator = ctx.freshTemp();
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(designator),
						worker("ns-computed-here", LispString.literal(name), designator));
			}
			case "read-string", "read" -> ClojureReadLowering.valueOf(ctx, name);
			default -> null;
		};
	}

	/**
	 * Reader metadata ({@code ^meta form}) in value position: on a vector, map or set
	 * literal it attaches to the fresh collection, like the oracle's reader; on a
	 * {@code fn} or {@code reify} form to the function or instance it builds (malli's
	 * {@code ^{:type ::schema} (reify ...)}); anywhere else (a type hint on a local, a
	 * flag on a call) it parses and drops. Nested layers merge with the outer one
	 * winning, like the oracle's reader; a keyword is {@code {:k true}}, a symbol or
	 * string {@code {:tag x}}.
	 * @param ctx the hub
	 * @param form the {@code (%with-meta form meta)} datum
	 * @return the lowered value
	 */
	static LispVal readerMetaOf(ClojureLowering ctx, LispVal form) {
		List<LispVal> layers = new ArrayList<>();
		LispVal target = form;
		List<LispVal> parts = ClojureLowerUtil.items(target);
		while (parts != null && parts.size() == 3
				&& ClojureLowerUtil.isSymbolNamed(parts.get(0), ClojureLowerUtil.READER_META)) {
			layers.add(parts.get(2));
			target = parts.get(1);
			parts = ClojureLowerUtil.items(target);
		}
		boolean literal = parts != null && !parts.isEmpty()
				&& (parts.get(0) == ClojureReader.VECTOR || ClojureLowerUtil.isSymbolNamed(parts.get(0), "%hash-map")
						|| ClojureLowerUtil.isSymbolNamed(parts.get(0), "%hash-set") || isMetaCarryingForm(ctx, parts));
		if (!literal) {
			LispVal tags = ClojureInteropLowering.paramTags(form);
			if (tags != null && target instanceof LispSymbol member && ctx.hostMemberName(member.name())) {
				// ^[types] Class/member as a value: the tags name the overload
				LispVal tagged = ClojureInteropLowering.memberValue(ctx, member.name(), tags);
				if (tagged != null) {
					return tagged;
				}
			}
			return ctx.lower(target);
		}
		List<LispVal> entries = new ArrayList<>();
		for (int i = layers.size() - 1; i >= 0; i--) {
			entries.addAll(metaEntries(layers.get(i)));
		}
		return ClojureLowerUtil.list(runtime("put-meta"), ctx.lower(target),
				ClojureCollectionLowering.mapBuild(ctx.lowers(entries, 0)));
	}

	/**
	 * Whether the form builds a value reader metadata attaches to: {@code fn*} or
	 * {@code reify*}, always, and {@code fn} or {@code reify} unless a local or program
	 * definition shadows the macro.
	 */
	private static boolean isMetaCarryingForm(ClojureLowering ctx, List<LispVal> parts) {
		if (!(parts.get(0) instanceof LispSymbol head)) {
			return false;
		}
		if (head.name().equals("fn*") || head.name().equals("reify*")) {
			return true;
		}
		return (head.name().equals("fn") || head.name().equals("reify")) && !ctx.isLocal(head.name())
				&& ctx.resolveVar(head.name()) == null;
	}

	/** One reader metadata datum as map entries (key and value datums). */
	static List<LispVal> metaEntries(LispVal meta) {
		if (meta instanceof LispSymbol s && s.name().startsWith(":")) {
			return List.of(s, new LispSymbol("true"));
		}
		if (meta instanceof LispSymbol || meta instanceof LispString) {
			return List.of(new LispSymbol(":tag"), ClojureLowerUtil.list(new LispSymbol("quote"), meta));
		}
		List<LispVal> parts = ClojureLowerUtil.items(meta);
		if (parts != null && !parts.isEmpty() && ClojureLowerUtil.isSymbolNamed(parts.get(0), "%hash-map")) {
			return parts.subList(1, parts.size());
		}
		throw new LispReadException("Metadata must be Symbol,Keyword,String or Map");
	}

	/**
	 * {@code (int x)} / {@code (long x)}: the oracle's {@code RT.intCast} /
	 * {@code RT.longCast} of an object ({@code %clojure-int-cast} /
	 * {@code %clojure-long-cast}). A literal argument folds here, through the overload
	 * the oracle's compiler picks for its type: a double literal takes the {@code double}
	 * cast, so {@code (int 1e10)} refuses with {@code Value out of range for int:
	 * 1.0E10} where the object cast of the same value refuses with {@code integer
	 * overflow}. A {@code count} is an int already and is not cast.
	 * @param ctx the hub
	 * @param name {@code int} or {@code long}
	 * @param arg the argument datum
	 * @return the folded value or refusal, or the cast call
	 */
	static LispVal castOf(ClojureLowering ctx, String name, LispVal arg) {
		boolean toInt = name.equals("int");
		LispVal folded = foldedCast(toInt, arg);
		if (folded != null) {
			return folded;
		}
		LispVal lowered = ctx.lower(arg);
		return isCoreCall(ctx, arg, "count") ? lowered : worker(toInt ? "int-cast" : "long-cast", lowered);
	}

	/** Whether {@code form} is a one-argument call of the core function {@code name}. */
	private static boolean isCoreCall(ClojureLowering ctx, LispVal form, String name) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		if (parts == null || parts.size() != 2 || !(parts.get(0) instanceof LispSymbol head)) {
			return false;
		}
		return name.equals(ctx.coreSpelling(head.name())) || (head.name().equals(name) && !ctx.known(name));
	}

	/**
	 * The cast of a literal number or character, or null when {@code arg} is none:
	 * {@code RT.longCast} / {@code RT.intCast} of the literal's type ({@code long},
	 * {@code double}, or the object casts of a bigint and a ratio).
	 */
	private static @Nullable LispVal foldedCast(boolean toInt, LispVal arg) {
		if (arg instanceof

		LispDouble(double x)) {
			if (toInt) {
				return (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) ? outOfRange("int", Double.toString(x))
						: new LispInteger((int) x);
			}
			return (x < Long.MIN_VALUE || x > Long.MAX_VALUE) ? outOfRange("long", Double.toString(x))
					: new LispInteger((long) x);
		}
		BigInteger n;
		if (arg instanceof

		LispInteger(long value)) {
			n = BigInteger.valueOf(value);
		}
		else if (arg instanceof

		LispBigInteger(BigInteger value)) {
			n = value;
		}
		else if (arg instanceof

		LispRatio(BigInteger numerator, BigInteger denominator)) {
			n = numerator.divide(denominator);
		}
		else if (arg instanceof

		LispChar(int codePoint)) {
			n = BigInteger.valueOf(codePoint);
		}
		else {
			return null;
		}
		if (n.bitLength() >= 64) {
			return outOfRange("long", n.toString());
		}
		long value = n.longValue();
		if (toInt && (int) value != value) {
			return ClojureRefusals.refusal(ClojureRefusals.ARITHMETIC, LispString.literal("integer overflow"));
		}
		return new LispInteger(value);
	}

	private static LispVal outOfRange(String kind, String spelled) {
		return ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				LispString.literal("Value out of range for " + kind + ": " + spelled));
	}

	/**
	 * {@code (prefers multifn)}: each dispatch value to the set preferred over it, like
	 * the oracle's map. The multimethod's name, like {@code methods}: a local bound to
	 * one is refused at lowering.
	 */
	private static LispVal prefersOf(ClojureLowering ctx, List<LispVal> items) {
		String name = ClojureLowerUtil.plainName(items.get(1), "prefers");
		if (ClojureDispatchLowering.isPrintMethod(ctx, name)) {
			// print-method keeps no prefers table: nothing is preferred
			return ClojureCollectionLowering.mapBuild(List.of());
		}
		String key = ClojureDispatchLowering.multimethodKey(ctx, name);
		return worker("prefers", ClojureDispatchLowering.tableGlobal(key, "%prefers"));
	}

	/**
	 * {@code (remove-all-methods multifn)}: every method and preference gone, answering
	 * the multimethod, like the oracle's.
	 */
	private static LispVal removeAllMethodsOf(ClojureLowering ctx, List<LispVal> items) {
		String name = ClojureLowerUtil.plainName(items.get(1), "remove-all-methods");
		if (ClojureDispatchLowering.isPrintMethod(ctx, name)) {
			// every type's print-method gone, answering print-method itself
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("clrhash"),
							new LispSymbol("RONTOLISP::%CLOJURE-PRINT-METHODS")),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("function"),
							new LispSymbol("RONTOLISP::%CLOJURE-PRINT-METHOD-V")));
		}
		String key = ClojureDispatchLowering.multimethodKey(ctx, name);
		LispSymbol methods = ClojureDispatchLowering.tableGlobal(key, "%methods");
		LispSymbol prefers = ClojureDispatchLowering.tableGlobal(key, "%prefers");
		LispSymbol object = ClojureDispatchLowering.tableGlobal(key, "%object");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("clrhash"), methods),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("clrhash"), prefers),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, ClojureLowering.NIL_CONST),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowering.varSym(key)));
	}

	/**
	 * {@code (extenders protocol)}: the extended tags as a seq, like the oracle's
	 * classes. Of a protocol name the method and default tables; of anything else the
	 * value itself, which no table answers nil for, like the oracle's nil of a
	 * non-protocol.
	 */
	private static LispVal extendersOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowering.ProtocolDef def = protocolDefOf(ctx, items.get(1));
		if (def != null) {
			return worker("extenders", def.methodsVar(), def.defaultVar());
		}
		return worker("extenders", ctx.lower(items.get(1)), ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code (find-protocol-impl protocol x)}: the tag row's map, else the Object
	 * default's, else nil.
	 */
	private static LispVal findImplOf(ClojureLowering ctx, List<LispVal> items) {
		ctx.usedProtocols = true;
		ClojureLowering.ProtocolDef def = protocolDefOf(ctx, items.get(1));
		if (def != null) {
			return worker("find-protocol-impl", def.methodsVar(), def.defaultVar(), ctx.lower(items.get(2)));
		}
		return worker("find-protocol-impl", ctx.lower(items.get(1)), ClojureLowering.NIL_CONST,
				ctx.lower(items.get(2)));
	}

	/**
	 * {@code (find-protocol-method protocol method x)}: the tag row's function for the
	 * method key, else the Object default's, else nil.
	 */
	private static LispVal findMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ctx.usedProtocols = true;
		ClojureLowering.ProtocolDef def = protocolDefOf(ctx, items.get(1));
		if (def != null) {
			return worker("find-protocol-method", def.methodsVar(), def.defaultVar(), ctx.lower(items.get(2)),
					ctx.lower(items.get(3)));
		}
		return worker("find-protocol-method", ctx.lower(items.get(1)), ClojureLowering.NIL_CONST,
				ctx.lower(items.get(2)), ctx.lower(items.get(3)));
	}

	/** The protocol a datum names, or null for anything else. */
	private static ClojureLowering.@Nullable ProtocolDef protocolDefOf(ClojureLowering ctx, LispVal datum) {
		if (!(datum instanceof LispSymbol s) || ctx.isLocal(s.name())) {
			return null;
		}
		return ClojureProtocolLowering.protocolOf(ctx, s.name());
	}

	/**
	 * {@code (cast class x)}: X when it is an instance of the class, nil of nil, else the
	 * oracle's refusal. Of a class the lowering names the instance test is inline; of
	 * anything else the value travels to the worker, which refuses what is no class as
	 * the oracle's cast does.
	 * @param ctx the hub
	 * @param classDatum the class datum, as written
	 * @param valueDatum the value datum, as written
	 * @return the cast call
	 */
	private static LispVal castCallOf(ClojureLowering ctx, LispVal classDatum, LispVal valueDatum) {
		if (classDatum instanceof LispSymbol s && !s.name().startsWith(":") && !s.name().equals("nil")
				&& !ctx.isLocal(s.name()) && ctx.lookupVar(s.name()) == null
				&& (ctx.typeDefOf(s.name()) != null || ClojureNamespaceLowering.isClasslike(ctx, s.name()))) {
			LispSymbol value = ctx.freshTemp();
			LispVal test = ClojureDispatchLowering.instanceTest(ctx, s.name(), value);
			ClojureLowering.TypeDef known = ctx.typeDefOf(s.name());
			String target = known != null ? known.className() : ClojureNamespaceLowering.resolveClass(ctx, s.name());
			LispVal message = ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("string")),
					LispString.literal("Cannot cast "),
					ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CLASS-NAME-OF"), value),
					LispString.literal(" to " + target));
			LispVal check = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), test, value,
					ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST, message));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(value, ctx.lower(valueDatum)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), value), ClojureLowering.NIL_CONST,
							check));
		}
		ctx.usedProtocols = true;
		return worker("cast", ctx.lower(classDatum), ctx.lower(valueDatum));
	}

	private static LispVal worker(String name, LispVal... args) {
		List<LispVal> out = new ArrayList<>();
		out.add(runtime(name));
		out.addAll(List.of(args));
		return ClojureLowerUtil.list(out);
	}

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name.toUpperCase(java.util.Locale.ROOT));
	}

	private static List<LispVal> fnValues(ClojureLowering ctx, List<LispVal> items, int from) {
		List<LispVal> out = new ArrayList<>();
		for (int i = from; i < items.size(); i++) {
			out.add(ClojureBindingLowering.realFnValue(ctx, items.get(i)));
		}
		return out;
	}

	/** The oracle's arity refusal when {@code n} falls outside {@code min..max}. */
	static void arity(String name, int n, int min, int max) {
		if (n < min || (max >= 0 && n > max)) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: clojure.core/" + name);
		}
	}

	/**
	 * {@code (bounded-count n coll)} over already-lowered forms: the count when the
	 * collection is counted, else the seq walked up to N -- count has no worker of its
	 * own, so the inlined count answers the counted arm. Both forms evaluate in order,
	 * bound once.
	 * @param ctx the hub
	 * @param count the lowered bound
	 * @param coll the lowered collection
	 * @return the lowered call
	 */
	static LispVal boundedCountForm(ClojureLowering ctx, LispVal count, LispVal coll) {
		LispSymbol bound = ctx.freshTemp();
		LispSymbol target = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(bound, count), ClojureLowerUtil.list(target, coll))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), worker("is-counted", target),
						ClojureCollectionLowering.countForm(ctx, target),
						worker("bounded-count-walk", bound, ClojureSeqLowering.seqForm(ctx, target))));
	}

	/**
	 * {@code (printf fmt args...)}: the format printed to {@code *out*}, answering nil --
	 * the {@code format} lowering's text through {@code princ}.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal printfOf(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> formatItems = new ArrayList<>();
		formatItems.add(new LispSymbol("format"));
		formatItems.addAll(items.subList(1, items.size()));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("princ"), ClojureFormatLowering.formatOf(ctx, formatItems)),
				ClojureLowering.NIL_CONST);
	}

	/**
	 * The namespace a {@code create-ns} or {@code intern} names literally, recorded where
	 * the program lowers, so a later lower-time lookup finds what the call makes at run
	 * time.
	 * @param ctx the hub
	 * @param designator the namespace designator datum
	 */
	static void noteCreatedNamespace(ClojureLowering ctx, LispVal designator) {
		String ns = ClojureNamespaceLowering.literalSymbol(designator);
		if (ns != null) {
			ctx.createdNamespaces.add(ns);
		}
	}

	/**
	 * {@code (requiring-resolve sym)} of a quoted symbol: the namespace loaded where it
	 * is a project one, then the var {@code resolve} answers -- nil for a name that names
	 * nothing, like the oracle's. Any other argument resolves at run time, which refuses.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal requiringResolveOf(ClojureLowering ctx, List<LispVal> items) {
		String name = ClojureNamespaceLowering.literalSymbol(items.get(1));
		if (name == null || name.startsWith(":")) {
			return worker("requiring-resolve-here", ctx.lower(items.get(1)));
		}
		int slash = ClojureLowering.qualifierSlash(name);
		if (slash > 0) {
			String ns = name.substring(0, slash);
			if (!ClojureNamespaceLowering.isKnownNamespace(ns) && !ns.equals(ClojureCoreNames.NAMESPACE)) {
				ClojureNamespaceLowering.loadNamespace(ctx, ns);
				LispVal init = ctx.requireCall(ns, ClojureLowering.LoadMode.GUARDED);
				if (init != null) {
					ctx.hoisted.add(init);
				}
			}
		}
		return ClojureVarLowering.resolved(ctx, name);
	}

	/**
	 * {@code (load-string text)} of a literal string: its datums lowered where the call
	 * stands, in the current namespace -- definitions declare ahead like a file's, macros
	 * hand over for the rest of the string -- answering the last datum's value, nil of an
	 * empty string. A namespace the text switches to is restored for the lowering after
	 * the call (its switch still runs).
	 * @param ctx the hub
	 * @param text the string
	 * @return the lowered call
	 */
	static LispVal loadStringOf(ClojureLowering ctx, String text) {
		ClojureReader reader = new ClojureReader(text, ctx.loadingFile);
		List<LispVal> datums = reader.readAll();
		ctx.declare(datums);
		String outerNs = ctx.currentNs;
		boolean top = ctx.atTopLevel();
		List<LispVal> forms = new ArrayList<>();
		try {
			for (LispVal datum : datums) {
				if (top) {
					List<LispVal> own = ctx.topLevels(datum);
					for (LispVal one : own) {
						ClojureMacroLowering.handOver(ctx, datum, List.of(one));
					}
					forms.addAll(own);
				}
				else {
					forms.add(ctx.lowerDetached(datum));
				}
			}
		}
		finally {
			ctx.currentNs = outerNs;
		}
		if (forms.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		if (forms.size() == 1) {
			return forms.get(0);
		}
		List<LispVal> progn = new ArrayList<>();
		progn.add(ClojureLowerUtil.sym("progn"));
		progn.addAll(forms);
		return ClojureLowerUtil.list(progn);

	}

	/**
	 * The component an {@code into-array} class spelling names: a {@code (:C%CLASS name)}
	 * designator over the resolved class, a primitive name ({@code "int"}, ...) for an
	 * {@code X/TYPE} spelling of a boxed primitive, so every backend reads it without the
	 * host. A spelling no program definition claims that names no class, and any other
	 * datum, travels as its lowered value: the worker reads a host Class by its name and
	 * refuses anything else as no Class.
	 * @param ctx the hub
	 * @param datum the class argument as written
	 * @return the designator, or the lowered value
	 */
	private static LispVal intoArrayType(ClojureLowering ctx, LispVal datum) {
		if (datum instanceof LispSymbol s && !ctx.known(s.name())) {
			String spelling = s.name();
			if (spelling.endsWith("/TYPE")) {
				String base = spelling.substring(0, spelling.length() - "/TYPE".length());
				if (ClojureInteropLowering.isClassSpelling(base)) {
					String primitive = primitiveComponent(ClojureNamespaceLowering.resolveClass(ctx, base));
					if (primitive != null) {
						return classDesignator(primitive);
					}
				}
			}
			else if (ClojureInteropLowering.isClassSpelling(spelling)
					&& ClojureNamespaceLowering.isClasslike(ctx, spelling)) {
				return classDesignator(ClojureNamespaceLowering.resolveClass(ctx, spelling));
			}
		}
		return ctx.lower(datum);
	}

	/** The primitive component a resolved boxed class spells, or null. */
	private static @Nullable String primitiveComponent(String cls) {
		return switch (cls) {
			case "java.lang.Byte" -> "byte";
			case "java.lang.Short" -> "short";
			case "java.lang.Integer" -> "int";
			case "java.lang.Long" -> "long";
			case "java.lang.Float" -> "float";
			case "java.lang.Double" -> "double";
			case "java.lang.Character" -> "char";
			case "java.lang.Boolean" -> "boolean";
			default -> null;
		};
	}

	/** A component designator: {@code (list :C%CLASS name)} building the data. */
	static LispVal classDesignator(String name) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), new LispSymbol(":C%CLASS"),
				LispString.literal(name));
	}

}
