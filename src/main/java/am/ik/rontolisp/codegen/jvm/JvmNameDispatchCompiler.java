package am.ik.rontolisp.codegen.jvm;

import java.util.Arrays;
import java.util.Comparator;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.NameDispatch;
import org.jspecify.annotations.Nullable;

/**
 * A name dispatch ({@link NameDispatch}) as a search over the names' hash codes: a symbol
 * is a bare {@code String}, whose {@code hashCode} is specified, so the compiler knows
 * every arm's; the name's is computed once (a non-{@code String} goes straight to the
 * miss), a binary search of {@code if_icmpge}s over the arms' hashes narrows to a run of
 * at most {@link #LEAF_ARMS} arms, and each of those keeps the chain's own exact test,
 * {@code "S".equals(name)} -- which also tells apart names whose hashes collide. The
 * chain called {@code equals} once per arm until the match. The key is the whole hash:
 * its low bits alone would make every pivot a {@code sipush} instead of an {@code ldc} of
 * a pool integer (1.1 KB less on the ci-spec program), and ran a random lookup over 300
 * names twice as slow under Graal (C2: the same).
 *
 * <pre>
 *   aload name  instanceof String  ifeq MISS
 *   aload name  checkcast String  invokevirtual hashCode  istore k
 *   ;; a split:  iload k  ldc PIVOT  if_icmpge UPPER  ...lower...  UPPER: ...upper...
 *   ;; a leaf:   ldc "S"  aload name  invokevirtual equals  ifeq NEXT  ARM  goto END  NEXT: ...
 *   ;;           goto MISS (or fall into it)
 * MISS: MISS-FORM
 * END:
 * </pre>
 */
final class JvmNameDispatchCompiler {

	/**
	 * The most arms a leaf of the search tests; a dispatch with no more arms than this
	 * keeps the chain, whose tests are the leaf's without the hash.
	 */
	static final int LEAF_ARMS = 4;

	private JvmNameDispatchCompiler() {
	}

	/**
	 * The dispatch {@code cons} heads when it is worth a search, else null.
	 * @param cons an {@code if} form
	 * @return the dispatch, more than {@link #LEAF_ARMS} arms
	 */
	static @Nullable NameDispatch match(LispCons cons) {
		NameDispatch d = NameDispatch.match(cons);
		return d != null && d.names().size() > LEAF_ARMS ? d : null;
	}

	/**
	 * Emits the dispatch {@code cons} heads. Every arm and the miss carry the tail and
	 * exit marks when {@code cons} does, as an {@code if}'s arms do.
	 * @param cons the head {@code if}
	 * @param d its dispatch
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 */
	static void compile(LispCons cons, NameDispatch d, JvmLispCompiler.Ctx ctx, String className) {
		int n = d.names().size();
		Integer[] order = new Integer[n];
		int[] hashes = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
			hashes[i] = d.names().get(i).hashCode();
		}
		Arrays.sort(order, Comparator.comparingInt(i -> hashes[i]));
		long[] keys = new long[n];
		for (int i = 0; i < n; i++) {
			keys[i] = hashes[order[i]];
		}
		LispVal savedMark = ctx.tailMark;
		LispVal savedExit = ctx.exitMark;
		boolean tail = savedMark == cons;
		boolean exits = JvmReturnCompiler.onExitChain(cons, ctx);
		MethodCode.Label miss = ctx.body.newLabel();
		MethodCode.Label end = ctx.body.newLabel();
		JvmExprCompiler.compileExpr(d.subject(), ctx, className);
		ctx.body.instanceOf(ctx.stringClass);
		ctx.body.ifeq(miss);
		JvmExprCompiler.compileExpr(d.subject(), ctx, className);
		ctx.body.checkcast(ctx.stringClass);
		ctx.body.invokevirtual(JvmEmitHelper.stringMethod(ctx, "hashCode", "()I"));
		int key = ctx.allocTemp();
		ctx.body.istore(key);
		emitNode(NameDispatch.searchTree(keys, LEAF_ARMS), true, d, order, key, miss, end, tail, exits, ctx, className);
		ctx.body.labelBinding(miss);
		mark(ctx, d.miss(), tail, exits);
		JvmExprCompiler.compileExpr(d.miss(), ctx, className);
		ctx.tailMark = savedMark;
		ctx.exitMark = savedExit;
		ctx.body.labelBinding(end);
	}

	/**
	 * One node of the search. {@code last} when nothing follows it before the miss, so
	 * its final leaf falls into the miss instead of jumping there.
	 */
	private static void emitNode(NameDispatch.Node node, boolean last, NameDispatch d, Integer[] order, int key,
			MethodCode.Label miss, MethodCode.Label end, boolean tail, boolean exits, JvmLispCompiler.Ctx ctx,
			String className) {
		switch (node) {
			case NameDispatch.Split split -> {
				MethodCode.Label upper = ctx.body.newLabel();
				ctx.body.iload(key);
				JvmEmitHelper.emitIntConst(ctx, (int) split.pivot());
				ctx.body.if_icmpge(upper);
				emitNode(split.below(), false, d, order, key, miss, end, tail, exits, ctx, className);
				ctx.body.labelBinding(upper);
				emitNode(split.atOrAbove(), last, d, order, key, miss, end, tail, exits, ctx, className);
			}
			case NameDispatch.Leaf leaf -> {
				for (int p = leaf.from(); p < leaf.to(); p++) {
					int arm = order[p];
					MethodCode.Label next = ctx.body.newLabel();
					JvmEmitHelper.compileUnspelledLiteral(d.names().get(arm), ctx);
					JvmExprCompiler.compileExpr(d.subject(), ctx, className);
					ctx.body.invokevirtual(ctx.objectEquals);
					ctx.body.ifeq(next);
					LispVal form = d.arms().get(arm);
					mark(ctx, form, tail, exits);
					JvmExprCompiler.compileExpr(form, ctx, className);
					ctx.tailMark = null;
					ctx.exitMark = null;
					ctx.body.goto_(end);
					ctx.body.labelBinding(next);
				}
				if (!last) {
					ctx.body.goto_(miss);
				}
			}
		}
	}

	private static void mark(JvmLispCompiler.Ctx ctx, LispVal form, boolean tail, boolean exits) {
		ctx.tailMark = tail ? form : null;
		ctx.exitMark = exits ? form : null;
	}

}
