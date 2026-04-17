import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Patches PDFReader.eatSpace() in Multivalent20060102.jar to eliminate a
 * StackOverflowError caused by unbounded mutual recursion.
 *
 * <h2>Root cause</h2>
 * {@code PDFReader} has two overloads of {@code eatSpace()}. Both handle PDF
 * comment lines (bytes starting with {@code %}) by calling {@code readObject()}
 * to consume the comment. However, {@code readObject()} itself calls
 * {@code eatSpace()} at its entry point to skip leading whitespace — creating
 * mutual recursion:
 * <pre>
 *   eatSpace → readObject() → readObject(ra,II) → eatSpace → readObject() → …
 * </pre>
 * For N consecutive {@code %} comment lines, the stack depth grows by 3N,
 * which overflows in constrained JVM environments (e.g., Docker containers
 * with small default thread stack sizes).
 *
 * <h2>Fix</h2>
 * Both {@code eatSpace} overloads are completely rewritten to handle comment
 * lines with a direct inline byte-reading loop instead of delegating to
 * {@code readObject()}. This breaks the mutual recursion while preserving
 * identical observable behaviour:
 * <ul>
 *   <li>Whitespace characters are skipped iteratively (unchanged).</li>
 *   <li>A {@code %} comment is consumed by reading bytes until {@code \n},
 *       {@code \r}, or EOF — no external call made.</li>
 *   <li>Non-whitespace, non-comment bytes are "unread" so the caller sees them
 *       (unchanged).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -cp asm-9.8.jar:asm-tree-9.8.jar:. PatchEatSpace \
 *       Multivalent20060102.jar Multivalent20060102-patched.jar
 * </pre>
 */
public class PatchEatSpace {

    private static final String TARGET_CLASS = "multivalent/std/adaptor/pdf/PDFReader.class";
    private static final String PDF_READER    = "multivalent/std/adaptor/pdf/PDFReader";
    private static final String RA_CLASS      = "com/pt/io/RandomAccess";
    private static final String ISC_CLASS     = "multivalent/std/adaptor/pdf/InputStreamComposite";
    private static final String RA_DESC       = "(L" + RA_CLASS  + ";)V";
    private static final String ISC_DESC      = "(L" + ISC_CLASS + ";)V";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: PatchEatSpace <input.jar> <output.jar>");
            System.exit(1);
        }

        String inputPath  = args[0];
        String outputPath = args[1];
        int totalPatched  = 0;

        try (JarFile jarFile = new JarFile(inputPath);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputPath))) {

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream is = jarFile.getInputStream(entry)) {
                    data = is.readAllBytes();
                }

                if (entry.getName().equals(TARGET_CLASS)) {
                    System.out.println("Patching: " + entry.getName());
                    int[] count = {0};
                    data = patchClass(data, count);
                    totalPatched = count[0];
                    System.out.printf("  Rewrote %d eatSpace overload(s)%n", totalPatched);
                }

                JarEntry out = new JarEntry(entry.getName());
                jos.putNextEntry(out);
                jos.write(data);
                jos.closeEntry();
            }
        }

        if (totalPatched == 0) {
            System.err.println("ERROR: No eatSpace methods found — the class may have changed.");
            System.exit(1);
        }

        System.out.println("Done: " + outputPath);
    }

    // -----------------------------------------------------------------------
    // Class-level patching
    // -----------------------------------------------------------------------

    static byte[] patchClass(byte[] bytes, int[] patchCount) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_FRAMES);

        for (MethodNode mn : cn.methods) {
            if (!"eatSpace".equals(mn.name)) continue;

            if (RA_DESC.equals(mn.desc)) {
                mn.instructions = buildEatSpaceRandomAccess();
                mn.tryCatchBlocks.clear();
                mn.maxStack  = 4;
                mn.maxLocals = 3;
                patchCount[0]++;
                System.out.println("  Rewrote eatSpace(RandomAccess)");
            } else if (ISC_DESC.equals(mn.desc)) {
                mn.instructions = buildEatSpaceISC(mn);
                mn.tryCatchBlocks.clear();
                mn.maxStack  = 3;
                mn.maxLocals = 3;
                patchCount[0]++;
                System.out.println("  Rewrote eatSpace(InputStreamComposite)");
            }
        }

        // COMPUTE_MAXS recalculates operand stack / locals after our edits.
        // Java 1.4 class (version 48) has no StackMapTable, so COMPUTE_FRAMES
        // is not needed (and would require the full classpath to resolve frames).
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // -----------------------------------------------------------------------
    // New eatSpace(RandomAccess ra) instruction list
    //
    // Equivalent Java:
    //
    //   public void eatSpace(RandomAccess ra) throws IOException {
    //     outer: while (true) {
    //       int c;
    //       // skip whitespace
    //       do {
    //         c = ra.read();
    //         if (c == -1) return;          // EOF
    //       } while (WHITESPACE[c]);
    //       // c is the first non-whitespace, non-EOF byte
    //       if (c != '%') {                 // not a comment
    //         ra.seek(ra.getFilePointer() - 1);  // unread it
    //         return;
    //       }
    //       // skip comment: read until '\n', '\r', or EOF
    //       while (true) {
    //         c = ra.read();
    //         if (c == -1)  return;
    //         if (c == '\n' || c == '\r') continue outer;
    //       }
    //     }
    //   }
    // -----------------------------------------------------------------------

    static InsnList buildEatSpaceRandomAccess() {
        InsnList l = new InsnList();

        LabelNode outerLoop   = new LabelNode();
        LabelNode commentLoop = new LabelNode();
        LabelNode unreadLabel = new LabelNode();
        LabelNode returnLabel = new LabelNode();

        // outer loop start
        l.add(outerLoop);

        // c = ra.read()
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RA_CLASS, "read", "()I", true));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new VarInsnNode(Opcodes.ISTORE, 2));
        // if c == -1: return
        l.add(new InsnNode(Opcodes.ICONST_M1));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, returnLabel));
        // if WHITESPACE[c]: loop (skip whitespace)
        l.add(new FieldInsnNode(Opcodes.GETSTATIC, PDF_READER, "WHITESPACE", "[Z"));
        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new InsnNode(Opcodes.BALOAD));
        l.add(new JumpInsnNode(Opcodes.IFNE, outerLoop));

        // c is non-whitespace: check for comment
        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new IntInsnNode(Opcodes.BIPUSH, 37));   // '%'
        l.add(new JumpInsnNode(Opcodes.IF_ICMPNE, unreadLabel));

        // comment: read until '\n', '\r', or EOF
        l.add(commentLoop);
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RA_CLASS, "read", "()I", true));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new VarInsnNode(Opcodes.ISTORE, 2));
        l.add(new InsnNode(Opcodes.ICONST_M1));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, returnLabel));  // EOF
        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new IntInsnNode(Opcodes.BIPUSH, 10));               // '\n'
        l.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, outerLoop));
        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new IntInsnNode(Opcodes.BIPUSH, 13));               // '\r'
        l.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, outerLoop));
        l.add(new JumpInsnNode(Opcodes.GOTO, commentLoop));

        // unread: ra.seek(ra.getFilePointer() - 1)
        l.add(unreadLabel);
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RA_CLASS, "getFilePointer", "()J", true));
        l.add(new InsnNode(Opcodes.LCONST_1));
        l.add(new InsnNode(Opcodes.LSUB));
        l.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RA_CLASS, "seek", "(J)V", true));

        l.add(returnLabel);
        l.add(new InsnNode(Opcodes.RETURN));

        return l;
    }

    // -----------------------------------------------------------------------
    // New eatSpace(InputStreamComposite isc) instruction list
    //
    // Preserves the original assertion check on `isc != null`.
    // Uses isc.read() / isc.unread(int) instead of ra.read() / ra.seek().
    //
    // Equivalent Java:
    //
    //   public void eatSpace(InputStreamComposite isc) throws IOException {
    //     assert isc != null;
    //     outer: while (true) {
    //       int c;
    //       do {
    //         c = isc.read();
    //         if (c == -1) return;
    //       } while (WHITESPACE[c]);
    //       if (c != '%') {
    //         isc.unread(c);
    //         return;
    //       }
    //       while (true) {
    //         c = isc.read();
    //         if (c == -1)  return;
    //         if (c == '\n' || c == '\r') continue outer;
    //       }
    //     }
    //   }
    // -----------------------------------------------------------------------

    static InsnList buildEatSpaceISC(MethodNode original) {
        InsnList l = new InsnList();

        // Rebuild the assertion check from scratch (assert isc != null).
        // Original bytecode pattern:
        //   GETSTATIC PDFReader.$assertionsDisabled Z
        //   IFNE [skip]
        //   ALOAD 1
        //   IFNONNULL [skip]
        //   NEW AssertionError
        //   DUP
        //   INVOKESPECIAL AssertionError.<init>()V
        //   ATHROW
        //   [skip]:
        //
        // We only emit this if the original method actually contains an assertion.
        boolean hasAssertion = false;
        for (AbstractInsnNode n : original.instructions.toArray()) {
            if (n instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode) n;
                if ("$assertionsDisabled".equals(f.name)) { hasAssertion = true; break; }
            }
        }

        LabelNode assertSkip = new LabelNode();
        if (hasAssertion) {
            l.add(new FieldInsnNode(Opcodes.GETSTATIC, PDF_READER, "$assertionsDisabled", "Z"));
            l.add(new JumpInsnNode(Opcodes.IFNE, assertSkip));
            l.add(new VarInsnNode(Opcodes.ALOAD, 1));
            l.add(new JumpInsnNode(Opcodes.IFNONNULL, assertSkip));
            l.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/AssertionError"));
            l.add(new InsnNode(Opcodes.DUP));
            l.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/lang/AssertionError", "<init>", "()V", false));
            l.add(new InsnNode(Opcodes.ATHROW));
            l.add(assertSkip);
        }

        LabelNode outerLoop   = new LabelNode();
        LabelNode commentLoop = new LabelNode();
        LabelNode unreadLabel = new LabelNode();
        LabelNode returnLabel = new LabelNode();

        l.add(outerLoop);

        // c = isc.read()
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ISC_CLASS, "read", "()I", false));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new VarInsnNode(Opcodes.ISTORE, 2));
        l.add(new InsnNode(Opcodes.ICONST_M1));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, returnLabel));
        l.add(new FieldInsnNode(Opcodes.GETSTATIC, PDF_READER, "WHITESPACE", "[Z"));
        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new InsnNode(Opcodes.BALOAD));
        l.add(new JumpInsnNode(Opcodes.IFNE, outerLoop));

        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new IntInsnNode(Opcodes.BIPUSH, 37));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPNE, unreadLabel));

        l.add(commentLoop);
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ISC_CLASS, "read", "()I", false));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new VarInsnNode(Opcodes.ISTORE, 2));
        l.add(new InsnNode(Opcodes.ICONST_M1));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, returnLabel));
        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new IntInsnNode(Opcodes.BIPUSH, 10));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, outerLoop));
        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new IntInsnNode(Opcodes.BIPUSH, 13));
        l.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, outerLoop));
        l.add(new JumpInsnNode(Opcodes.GOTO, commentLoop));

        // unread: isc.unread(c)
        l.add(unreadLabel);
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new VarInsnNode(Opcodes.ILOAD, 2));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ISC_CLASS, "unread", "(I)V", false));

        l.add(returnLabel);
        l.add(new InsnNode(Opcodes.RETURN));

        return l;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean isPseudo(AbstractInsnNode node) {
        return node instanceof LabelNode
                || node instanceof LineNumberNode
                || node instanceof FrameNode;
    }
}
