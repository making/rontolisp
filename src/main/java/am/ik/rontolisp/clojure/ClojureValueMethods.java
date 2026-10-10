package am.ik.rontolisp.clojure;

import java.util.Set;

/**
 * The instance methods, by {@code name/arity}, that the oracle's class of some value kind
 * has and that the Java object the value is here lacks: what an instance call on such a
 * value answers neither through a mapped row nor through that object
 * ({@link ClojureValueMethodLowering}). A call naming one is refused as unsupported,
 * since the receiver's class may have it; any other method no row names and the object
 * lacks is refused in the oracle's words, since no value class has it.
 *
 * <p>
 * Read off clj 1.12.6 on JDK 25 (2026-10-10) as a table, not reflection:
 * {@code clojure.lang} is not on this class path. For each class a value here stands for
 * (the vectors, lists, seqs, maps and sets, keyword, symbol, ratio, the reference types,
 * {@code AFunction}, {@code SeqIterator}, {@code Var}, the transients, {@code Pattern},
 * {@code Matcher}, ...): its public instance methods less those of {@code Object} and of
 * the JDK interfaces the value's Java object implements here ({@code List},
 * {@code RandomAccess} and {@code Comparable} of a vector, {@code List} of a seq,
 * {@code Set}, {@code Map}, {@code Comparable} of a keyword or symbol, {@code Number} and
 * {@code Comparable} of a ratio, {@code Iterator} of a seq iterator), which that object
 * answers. One table for every kind: a method one class has is unsupported on all.
 */
final class ClojureValueMethods {

	private ClojureValueMethods() {
	}

	private static final Set<String> METHODS = Set.of("""
			addAlias/2 addWatch/2 alter/2 alterMeta/2 alterRoot/2 appendReplacement/2 appendTail/1
			applyTo/1 arrayFor/1 asMatchPredicate/0 asPredicate/0 asTransient/0 assoc/2 assocEx/2 assocN/2
			bigIntegerValue/0 bindRoot/1 call/0 capacity/0 chunkedFirst/0 chunkedMore/0 chunkedNext/0
			chunkedSeq/0 commute/2 commuteRoot/1 comparator/0 compare/2 compareAndSet/2 compareTo/1 conj/1
			cons/1 contains/1 containsKey/1 count/0 decimalValue/0 decimalValue/1 depth/0 deref/0 disjoin/1
			dispatch/3 doCompare/2 doReset/1 doSet/1 drop/1 empty/0 end/0 end/1 entryAt/1 entryKey/1
			equiv/1 find/0 find/1 findInternedVar/1 first/0 flags/0 fn/0 fold/7 forceChunk/0 get/0 get/1
			getAliases/0 getAsBoolean/0 getAsDouble/0 getAsInt/0 getAsLong/0 getError/0 getErrorHandler/0
			getErrorMode/0 getHistoryCount/0 getKey/0 getMapping/1 getMappings/0 getMaxHistory/0
			getMinHistory/0 getName/0 getNamespace/0 getQueueCount/0 getRawRoot/0 getRequiredArity/0
			getTag/0 getThreadBinding/0 getValidator/0 getValue/0 getWatches/0 group/0 group/1 groupCount/0
			hasAnchoringBounds/0 hasMatch/0 hasRoot/0 hasTransparentBounds/0 hasheq/0 hitEnd/0
			importClass/1 importClass/2 index/0 intern/1 invoke/0 invoke/1 invoke/2 invoke/3 invoke/4
			invoke/5 invoke/6 invoke/7 invoke/8 invoke/9 invoke/10 invoke/11 invoke/12 invoke/13 invoke/14
			invoke/15 invoke/16 invoke/17 invoke/18 invoke/19 invoke/20 invoke/21 isBound/0 isDynamic/0
			isMacro/0 isPublic/0 isRealized/0 iterator/0 key/0 keyIterator/0 keys/0 keys/1 kvreduce/2
			length/0 lookingAt/0 lookupAlias/1 matcher/1 matches/0 max/0 maxKey/0 meta/0 min/0 minKey/0
			more/0 namedGroups/0 next/0 notifyWatches/2 nth/1 nth/2 pattern/0 peek/0 persistent/0 pop/0
			reduce/1 reduce/2 refer/2 region/2 regionEnd/0 regionStart/0 removeAlias/1 removeWatch/1
			replaceAll/1 replaceFirst/1 requireEnd/0 reset/0 reset/1 resetMeta/1 resetVals/1 restart/2
			results/0 reverseIterator/0 reversed/0 rseq/0 run/0 seq/0 seq/1 seqFrom/2 set/1 setDynamic/0
			setDynamic/1 setErrorHandler/1 setErrorMode/1 setMacro/0 setMaxHistory/1 setMeta/1
			setMinHistory/1 setTag/1 setValidator/1 setValue/1 split/1 split/2 splitAsStream/1
			splitWithDelimiters/2 spliterator/0 start/0 start/1 swap/1 swap/2 swap/3 swap/4 swapVals/1
			swapVals/2 swapVals/3 swapVals/4 thenComparing/1 thenComparing/2 thenComparingDouble/1
			thenComparingInt/1 thenComparingLong/1 throwArity/0 throwArity/1 toMatchResult/0 toSymbol/0
			touch/0 trimHistory/0 unbindRoot/0 unmap/1 useAnchoringBounds/1 usePattern/1
			useTransparentBounds/1 val/0 valAt/1 valAt/2 valIterator/0 vals/0 vals/1 withMeta/1 without/1
			""".trim().split("\\s+"));

	/**
	 * Whether some value class of the oracle has the method at the arity where the Java
	 * object a value is here lacks it.
	 * @param method the method name
	 * @param arity the count of its arguments
	 * @return whether the method is unsupported rather than absent
	 */
	static boolean unsupported(String method, int arity) {
		return METHODS.contains(method + "/" + arity);
	}

}
