package am.ik.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites branches whose offset outgrew the signed 16-bit encoding: a {@code goto}
 * becomes a {@code goto_w}, and a conditional branch becomes its inverted-condition short
 * branch over a {@code goto_w}. Widening moves every later instruction, which can push
 * further branches out of range, so the sizing loop runs to a fixpoint before the body is
 * rewritten once.
 *
 * <p>
 * The emitter patches an in-range branch directly ({@code JvmRuntimeBuilder.patchBranch}
 * on the caller's side); only an out-of-range patch is deferred to this pass, so a method
 * whose branches all fit is returned untouched, byte for byte. The decoder understands
 * exactly the instruction set the emitters produce -- like {@link StackMapAugmenter},
 * {@code tableswitch}/{@code lookupswitch}/{@code wide}/ {@code jsr} are rejected loudly
 * rather than mis-measured.
 *
 * <p>
 * The 65535-byte method code limit is NOT lifted by this pass ({@code goto_w} cannot
 * rescue it); {@link ByteCodeWriter#writeCode} keeps that guard.
 */
public final class BranchRelaxer {

	private BranchRelaxer() {
	}

	/**
	 * Relaxes {@code code} in place. No-op when {@code deferredBranches} is empty -- the
	 * emitted body stays byte-identical.
	 * @param code the method body bytes, one int per byte
	 * @param deferredBranches the branches whose patch overflowed the short encoding:
	 * {@code {branchPos, targetPos}} pairs recorded instead of being written
	 * @param exceptionTable the method's exception table entries; start/end/handler
	 * positions are remapped in place
	 */
	public static void relax(List<Integer> code, List<int[]> deferredBranches,
			List<ByteCodeWriter.ExceptionTableEntry> exceptionTable) {
		if (deferredBranches.isEmpty()) {
			return;
		}
		Map<Integer, Integer> deferredTargets = new HashMap<>();
		for (int[] pair : deferredBranches) {
			deferredTargets.put(pair[0], pair[1]);
		}

		// Decode instruction boundaries and branch targets from the emitted bytes; a
		// deferred branch carries a placeholder offset, so its recorded target wins.
		List<Integer> starts = new ArrayList<>();
		Map<Integer, Integer> branchTarget = new HashMap<>();
		int n = code.size();
		int pos = 0;
		while (pos < n) {
			starts.add(pos);
			int op = code.get(pos) & 0xff;
			if (isShortBranch(op)) {
				Integer deferred = deferredTargets.get(pos);
				int target = (deferred != null) ? deferred
						: pos + (short) (((code.get(pos + 1) & 0xff) << 8) | (code.get(pos + 2) & 0xff));
				branchTarget.put(pos, target);
			}
			pos += 1 + operandLength(op, pos);
		}

		// Fixpoint sizing: widening one branch moves every later offset, which can push
		// another branch out of range.
		Set<Integer> widened = new HashSet<>();
		Map<Integer, Integer> newPos = new HashMap<>();
		while (true) {
			int shift = 0;
			for (int start : starts) {
				newPos.put(start, start + shift);
				if (widened.contains(start)) {
					shift += (code.get(start) & 0xff) == Opcode.GOTO ? 2 : 5;
				}
			}
			newPos.put(n, n + shift);
			boolean changed = false;
			for (Map.Entry<Integer, Integer> br : branchTarget.entrySet()) {
				if (widened.contains(br.getKey())) {
					continue;
				}
				int offset = remap(newPos, br.getValue()) - remap(newPos, br.getKey());
				if (offset < Short.MIN_VALUE || offset > Short.MAX_VALUE) {
					widened.add(br.getKey());
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}

		// Rewrite: copy instructions, re-encoding every branch against the new layout.
		List<Integer> out = new ArrayList<>(n + widened.size() * 5);
		for (int start : starts) {
			int op = code.get(start) & 0xff;
			int len = 1 + operandLength(op, start);
			if (!branchTarget.containsKey(start)) {
				for (int i = 0; i < len; i++) {
					out.add(code.get(start + i));
				}
				continue;
			}
			int from = remap(newPos, start);
			int to = remap(newPos, java.util.Objects.requireNonNull(branchTarget.get(start)));
			if (!widened.contains(start)) {
				out.add(op);
				addU2(out, to - from);
			}
			else if (op == Opcode.GOTO) {
				out.add(Opcode.GOTO_W);
				addU4(out, to - from);
			}
			else {
				// Inverted short branch over the goto_w that does the real jump: the
				// inverted condition falls through to the goto_w's successor, i.e.
				// skips 8 bytes (3 for itself + 5 for the goto_w).
				out.add(invert(op));
				addU2(out, 8);
				out.add(Opcode.GOTO_W);
				addU4(out, to - (from + 3));
			}
		}
		code.clear();
		code.addAll(out);

		for (int i = 0; i < exceptionTable.size(); i++) {
			ByteCodeWriter.ExceptionTableEntry e = exceptionTable.get(i);
			exceptionTable.set(i, new ByteCodeWriter.ExceptionTableEntry(remap(newPos, e.startPc()),
					remap(newPos, e.endPc()), remap(newPos, e.handlerPc()), e.catchType()));
		}
	}

	private static int remap(Map<Integer, Integer> newPos, int oldPc) {
		Integer mapped = newPos.get(oldPc);
		if (mapped == null) {
			throw new IllegalStateException("pc " + oldPc + " is not an instruction boundary");
		}
		return mapped;
	}

	private static void addU2(List<Integer> out, int value) {
		out.add((value >> 8) & 0xff);
		out.add(value & 0xff);
	}

	private static void addU4(List<Integer> out, int value) {
		out.add((value >> 24) & 0xff);
		out.add((value >> 16) & 0xff);
		out.add((value >> 8) & 0xff);
		out.add(value & 0xff);
	}

	private static boolean isShortBranch(int op) {
		return (op >= Opcode.IFEQ && op <= Opcode.IF_ACMPNE) || op == Opcode.GOTO || op == Opcode.IFNULL
				|| op == Opcode.IFNONNULL;
	}

	// The inverted condition falls through exactly when the original would jump.
	private static int invert(int op) {
		return switch (op) {
			case Opcode.IFEQ -> Opcode.IFNE;
			case Opcode.IFNE -> Opcode.IFEQ;
			case Opcode.IFLT -> Opcode.IFGE;
			case Opcode.IFGE -> Opcode.IFLT;
			case Opcode.IFGT -> Opcode.IFLE;
			case Opcode.IFLE -> Opcode.IFGT;
			case Opcode.IF_ICMPEQ -> Opcode.IF_ICMPNE;
			case Opcode.IF_ICMPNE -> Opcode.IF_ICMPEQ;
			case Opcode.IF_ICMPLT -> Opcode.IF_ICMPGE;
			case Opcode.IF_ICMPGE -> Opcode.IF_ICMPLT;
			case Opcode.IF_ICMPGT -> Opcode.IF_ICMPLE;
			case Opcode.IF_ICMPLE -> Opcode.IF_ICMPGT;
			case Opcode.IF_ACMPEQ -> Opcode.IF_ACMPNE;
			case Opcode.IF_ACMPNE -> Opcode.IF_ACMPEQ;
			case Opcode.IFNULL -> Opcode.IFNONNULL;
			case Opcode.IFNONNULL -> Opcode.IFNULL;
			default -> throw new IllegalStateException("not an invertible branch: 0x" + Integer.toHexString(op));
		};
	}

	/**
	 * Operand byte count -- the same instruction subset {@link StackMapAugmenter} walks.
	 */
	private static int operandLength(int op, int pc) {
		return switch (op) {
			case Opcode.BIPUSH, Opcode.LDC, Opcode.ILOAD, Opcode.LLOAD, Opcode.FLOAD, Opcode.DLOAD, Opcode.ALOAD,
					Opcode.ISTORE, Opcode.LSTORE, Opcode.FSTORE, Opcode.DSTORE, Opcode.ASTORE, Opcode.NEWARRAY ->
				1;
			case Opcode.SIPUSH, Opcode.LDC_W, Opcode.LDC2_W, Opcode.IINC, Opcode.IFEQ, Opcode.IFNE, Opcode.IFLT,
					Opcode.IFGE, Opcode.IFGT, Opcode.IFLE, Opcode.IF_ICMPEQ, Opcode.IF_ICMPNE, Opcode.IF_ICMPLT,
					Opcode.IF_ICMPGE, Opcode.IF_ICMPGT, Opcode.IF_ICMPLE, Opcode.IF_ACMPEQ, Opcode.IF_ACMPNE,
					Opcode.GOTO, Opcode.GETSTATIC, Opcode.PUTSTATIC, Opcode.GETFIELD, Opcode.PUTFIELD,
					Opcode.INVOKEVIRTUAL, Opcode.INVOKESPECIAL, Opcode.INVOKESTATIC, Opcode.NEW, Opcode.ANEWARRAY,
					Opcode.CHECKCAST, Opcode.INSTANCEOF, Opcode.IFNULL, Opcode.IFNONNULL ->
				2;
			case Opcode.MULTIANEWARRAY -> 3;
			case Opcode.INVOKEINTERFACE, Opcode.GOTO_W -> 4;
			case Opcode.TABLESWITCH, Opcode.LOOKUPSWITCH, Opcode.WIDE, Opcode.JSR, Opcode.JSR_W, Opcode.RET ->
				throw new IllegalStateException("unsupported opcode 0x" + Integer.toHexString(op) + " at " + pc);
			default -> 0;
		};
	}

}
