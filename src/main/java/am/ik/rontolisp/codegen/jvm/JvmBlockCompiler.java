package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the block return boundaries: the internal {@code %block} that the loop macros
 * ({@code do}/{@code dolist}/{@code dotimes}) wrap their expansion in, the user-facing
 * named {@code (block name body...)}, and the internal {@code (%fn-block name body...)}
 * function boundary the compilers wrap a {@code return-from}-containing defun/lambda body
 * in. All three share one shape: the body runs as an implicit {@code progn}; its value is
 * stored into a dedicated local and the block evaluates to that local. An exit form (see
 * {@link JvmReturnCompiler}) stores its value into the same local and jumps straight to
 * the block's exit, skipping the rest of the body and any result form. They differ only
 * in how {@link JvmLispCompiler.BlockTarget} is keyed: {@code %block} and
 * {@code (block nil ...)} catch plain {@code return}; a named block catches the
 * {@code return-from} carrying its name; {@code %fn-block} additionally catches any
 * {@code return-from} whose name matches no enclosing block (the function-boundary
 * fallback).
 *
 * <p>
 * Storing the value into a local and jumping (rather than leaving it on the operand
 * stack) means the exit is reached with the operand stack the block was entered with --
 * recorded here as {@code entryStack} -- on every path, which the version-50
 * type-inference verifier requires. An exit mid-expression discards whatever the body had
 * pushed on top of that (see {@link JvmReturnCompiler}).
 */
final class JvmBlockCompiler {

	private JvmBlockCompiler() {
	}

	/** The internal {@code (%block body...)} boundary: unnamed, catches plain return. */
	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileBody(cons.toList(), 1, ctx, className, null, true, false);
	}

	/**
	 * A user {@code (block name body...)}: a lexical named target. {@code (block nil
	 * ...)} behaves exactly like {@code %block} (it catches plain {@code return}, and a
	 * {@code (return-from nil ...)} compiles to plain {@code return}).
	 */
	static void compileNamed(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.BLOCK + " expects a block name: " + cons.print());
		}
		String name = LispMacroExpander.blockName(parts.get(1));
		compileBody(parts, 2, ctx, className, name, name == null, false);
	}

	/**
	 * The internal {@code (%fn-block name body...)} function boundary: a named target
	 * ({@code name} is the defun's name, nil for a lambda) that is also the fallback for
	 * an unmatched {@code return-from}. It does NOT catch plain {@code return}.
	 */
	static void compileFnBlock(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.FN_BLOCK_INTERNAL + " expects a block name: " + cons.print());
		}
		String name = LispMacroExpander.blockName(parts.get(1));
		compileBody(parts, 2, ctx, className, name, false, true);
	}

	private static void compileBody(List<LispVal> parts, int bodyStart, JvmLispCompiler.Ctx ctx, String className,
			@Nullable String name, boolean catchesPlain, boolean functionBoundary) {
		int savedNextLocal = ctx.nextLocal;
		int rvSlot = ctx.allocTemp();
		ctx.blockTargets.push(new JvmLispCompiler.BlockTarget(rvSlot, new ArrayList<>(), ctx.stack.snapshot(), name,
				catchesPlain, functionBoundary));
		// Body forms run as a progn, leaving the last value on the stack.
		if (parts.size() <= bodyStart) {
			ctx.emit(Opcode.ACONST_NULL);
		}
		else {
			for (int i = bodyStart; i < parts.size(); i++) {
				if (i > bodyStart) {
					ctx.emit(Opcode.POP);
				}
				JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
			}
		}
		// Normal completion: store the body value into the block's slot.
		ctx.emit(Opcode.ASTORE);
		ctx.emit(rvSlot);
		JvmLispCompiler.BlockTarget target = ctx.blockTargets.pop();
		int exit = ctx.code.size();
		for (int patchPos : target.exitPatches()) {
			JvmEmitHelper.patchBranch(ctx, patchPos, exit);
		}
		// The block's value is the slot, populated by either normal completion or an
		// exit jump.
		ctx.emit(Opcode.ALOAD);
		ctx.emit(rvSlot);
		ctx.nextLocal = savedNextLocal;
	}

}
