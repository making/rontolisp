package am.ik.rontolisp.clojure;

import java.util.Set;

/**
 * Every class of {@code clojure.lang} in the oracle's jar (clj 1.12.6, read off
 * {@code clojure-1.12.6.jar} 2026-10-29, nested classes included, anonymous ones not).
 * None loads here, so this table tells a spelling of one from a typo: a class no value
 * here has reads as its class keyword, which no value answers, so a {@code defmethod} on
 * it never matches (tools.reader's {@code clojure.lang.PersistentVector$ChunkedSeq}).
 */
final class ClojureLangJar {

	private static final String PACKAGE = "clojure.lang.";

	/**
	 * The simple names, the primitive {@code IFn} interfaces aside: {@code IFn$} and one
	 * to five of {@code O}, {@code L}, {@code D} (the parameters, then the answer), not
	 * all {@code O} -- the jar's 358 ({@link #isPrimitiveFn}).
	 */
	private static final Set<String> SIMPLE_NAMES = Set.of("""
			AFn AFunction Agent Agent$Action Agent$ActionQueue AMapEntry APersistentMap APersistentMap$KeySeq
			APersistentMap$ValSeq APersistentSet APersistentVector APersistentVector$RSeq APersistentVector$Seq
			APersistentVector$SubVector ARef AReference ArityException ArrayChunk ArrayIter
			ArrayIter$ArrayIter_boolean ArrayIter$ArrayIter_byte ArrayIter$ArrayIter_char
			ArrayIter$ArrayIter_double ArrayIter$ArrayIter_float ArrayIter$ArrayIter_int ArrayIter$ArrayIter_long
			ArrayIter$ArrayIter_short ArraySeq ArraySeq$ArraySeq_boolean ArraySeq$ArraySeq_byte
			ArraySeq$ArraySeq_char ArraySeq$ArraySeq_double ArraySeq$ArraySeq_float ArraySeq$ArraySeq_int
			ArraySeq$ArraySeq_long ArraySeq$ArraySeq_short ASeq Associative Atom ATransientMap ATransientSet BigInt
			Binding Box ChunkBuffer ChunkedCons Compile Compiler Compiler$AssignableExpr Compiler$AssignExpr
			Compiler$AssignExpr$Parser Compiler$BindingInit Compiler$BodyExpr Compiler$BodyExpr$Parser
			Compiler$BooleanExpr Compiler$C Compiler$CaseExpr Compiler$CaseExpr$Parser Compiler$CompilerException
			Compiler$ConstantExpr Compiler$ConstantExpr$Parser Compiler$DefExpr Compiler$DefExpr$Parser
			Compiler$EmptyExpr Compiler$Expr Compiler$FieldExpr Compiler$FISupport Compiler$FnExpr
			Compiler$FnMethod Compiler$HostExpr Compiler$HostExpr$Parser Compiler$IfExpr Compiler$IfExpr$Parser
			Compiler$ImportExpr Compiler$ImportExpr$Parser Compiler$InstanceFieldExpr Compiler$InstanceMethodExpr
			Compiler$InstanceOfExpr Compiler$InvokeExpr Compiler$IParser Compiler$KeywordExpr
			Compiler$KeywordInvokeExpr Compiler$LetExpr Compiler$LetExpr$Parser Compiler$LetFnExpr
			Compiler$LetFnExpr$Parser Compiler$ListExpr Compiler$LiteralExpr Compiler$LocalBinding
			Compiler$LocalBindingExpr Compiler$MapExpr Compiler$MaybePrimitiveExpr Compiler$MetaExpr
			Compiler$MethodExpr Compiler$MethodParamExpr Compiler$MonitorEnterExpr Compiler$MonitorEnterExpr$Parser
			Compiler$MonitorExitExpr Compiler$MonitorExitExpr$Parser Compiler$NewExpr Compiler$NewExpr$Parser
			Compiler$NewInstanceExpr Compiler$NewInstanceExpr$DeftypeParser Compiler$NewInstanceExpr$ReifyParser
			Compiler$NewInstanceMethod Compiler$NilExpr Compiler$NumberExpr Compiler$ObjExpr Compiler$ObjMethod
			Compiler$PathNode Compiler$PATHTYPE Compiler$PSTATE Compiler$QualifiedMethodExpr
			Compiler$QualifiedMethodExpr$MethodKind Compiler$Recur Compiler$RecurExpr Compiler$RecurExpr$Parser
			Compiler$SetExpr Compiler$SourceDebugExtensionAttribute Compiler$StaticFieldExpr
			Compiler$StaticInvokeExpr Compiler$StaticMethodExpr Compiler$StringExpr Compiler$TheVarExpr
			Compiler$TheVarExpr$Parser Compiler$ThrowExpr Compiler$ThrowExpr$Parser Compiler$TryExpr
			Compiler$TryExpr$CatchClause Compiler$TryExpr$Parser Compiler$UnresolvedVarExpr Compiler$UntypedExpr
			Compiler$VarExpr Compiler$VectorExpr Cons Counted Cycle Delay DynamicClassLoader EdnReader
			EdnReader$CharacterReader EdnReader$CommentReader EdnReader$DiscardReader EdnReader$DispatchReader
			EdnReader$ListReader EdnReader$MapReader EdnReader$MetaReader EdnReader$NamespaceMapReader
			EdnReader$ReaderException EdnReader$SetReader EdnReader$StringReader EdnReader$SymbolicValueReader
			EdnReader$TaggedReader EdnReader$UnmatchedDelimiterReader EdnReader$UnreadableReader
			EdnReader$VectorReader EnumerationSeq EnumerationSeq$State ExceptionInfo Fn FnInvokers FnLoaderThunk
			IAtom IAtom2 IBlockingDeref IChunk IChunkedSeq IDeref IDrop IEditableCollection IExceptionInfo IFn
			IHashEq IKeywordLookup IKVReduce ILookup ILookupSite ILookupThunk IMapEntry IMapIterable IMeta Indexed
			IndexedSeq Intrinsics IObj IPending IPersistentCollection IPersistentList IPersistentMap IPersistentSet
			IPersistentStack IPersistentVector IProxy IRecord IReduce IReduceInit IRef IReference ISeq Iterate
			IteratorSeq IteratorSeq$State ITransientAssociative ITransientAssociative2 ITransientCollection
			ITransientMap ITransientSet ITransientVector IType Keyword KeywordLookupSite LazilyPersistentVector
			LazySeq LineNumberingPushbackReader LispReader LispReader$ArgReader LispReader$CharacterReader
			LispReader$CommentReader LispReader$ConditionalReader LispReader$CtorReader
			LispReader$DeprecatedWrappingReader LispReader$DiscardReader LispReader$DispatchReader
			LispReader$EvalReader LispReader$FnReader LispReader$ListReader LispReader$MapReader
			LispReader$MetaReader LispReader$NamespaceMapReader LispReader$ReaderException LispReader$RegexReader
			LispReader$Resolver LispReader$SetReader LispReader$StringReader LispReader$SymbolicValueReader
			LispReader$SyntaxQuoteReader LispReader$UnmatchedDelimiterReader LispReader$UnquoteReader
			LispReader$UnreadableReader LispReader$VarReader LispReader$VectorReader LispReader$WrappingReader
			LockingTransaction LockingTransaction$AbortException LockingTransaction$CFn LockingTransaction$Info
			LockingTransaction$Notify LockingTransaction$RetryEx LongRange LongRange$LongChunk
			LongRange$LongRangeIterator MapEntry MapEquivalence MethodImplCache MethodImplCache$Entry MultiFn
			Murmur3 Named Namespace Numbers Numbers$BigDecimalOps Numbers$BigIntOps Numbers$Category
			Numbers$DoubleOps Numbers$LongOps Numbers$Ops Numbers$OpsP Numbers$RatioOps Obj PersistentArrayMap
			PersistentArrayMap$Iter PersistentArrayMap$Seq PersistentArrayMap$TransientArrayMap PersistentHashMap
			PersistentHashMap$ArrayNode PersistentHashMap$ArrayNode$Iter PersistentHashMap$ArrayNode$Seq
			PersistentHashMap$BitmapIndexedNode PersistentHashMap$HashCollisionNode PersistentHashMap$INode
			PersistentHashMap$NodeIter PersistentHashMap$NodeSeq PersistentHashMap$TransientHashMap
			PersistentHashSet PersistentHashSet$TransientHashSet PersistentList PersistentList$EmptyList
			PersistentList$Primordial PersistentQueue PersistentQueue$Seq PersistentStructMap
			PersistentStructMap$Def PersistentStructMap$Seq PersistentTreeMap PersistentTreeMap$Black
			PersistentTreeMap$BlackBranch PersistentTreeMap$BlackBranchVal PersistentTreeMap$BlackVal
			PersistentTreeMap$KeyIterator PersistentTreeMap$Node PersistentTreeMap$NodeIterator
			PersistentTreeMap$Red PersistentTreeMap$RedBranch PersistentTreeMap$RedBranchVal
			PersistentTreeMap$RedVal PersistentTreeMap$Seq PersistentTreeMap$ValIterator PersistentTreeSet
			PersistentVector PersistentVector$ChunkedSeq PersistentVector$Node PersistentVector$TransientVector
			ProxyHandler Range Range$BoundsCheck Range$RangeIterator Ratio ReaderConditional RecordIterator Reduced
			Ref Ref$TVal Reflector Repeat Repl RestFn Reversible RT RT$DefaultComparator Script Seqable
			SeqEnumeration SeqIterator Sequential Settable Sorted StringSeq Symbol TaggedLiteral
			TransactionalHashMap TransformerIterator TransformerIterator$Buffer TransformerIterator$Empty
			TransformerIterator$Many TransformerIterator$MultiIterator TransformerIterator$Single Tuple Util
			Util$EquivPred Var Var$Frame Var$Serialized Var$TBox Var$Unbound Volatile WarnBoxedMath XMLHandler
			""".strip().split("\\s+"));

	private ClojureLangJar() {
	}

	/**
	 * Whether the binary name is a class of {@code clojure.lang} in the oracle's jar.
	 * @param fqn the binary name ({@code clojure.lang.PersistentVector$ChunkedSeq})
	 * @return {@code true} for one of the jar's classes
	 */
	static boolean holds(String fqn) {
		if (!fqn.startsWith(PACKAGE)) {
			return false;
		}
		String simple = fqn.substring(PACKAGE.length());
		return SIMPLE_NAMES.contains(simple) || isPrimitiveFn(simple);
	}

	private static boolean isPrimitiveFn(String simple) {
		if (!simple.startsWith("IFn$")) {
			return false;
		}
		String sig = simple.substring("IFn$".length());
		return !sig.isEmpty() && sig.length() <= 5 && sig.chars().allMatch(c -> c == 'O' || c == 'L' || c == 'D')
				&& sig.chars().anyMatch(c -> c != 'O');
	}

}
