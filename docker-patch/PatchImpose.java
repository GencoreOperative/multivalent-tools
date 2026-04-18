import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Patches phelps/util/Units.getLength() to eliminate NullPointerException.
 * 
 * Bug: When input string has no unit (e.g., "10" instead of "10bp"), 
 * matcher.group(2) returns null. This null is then passed to convertLength()
 * which tries to call null.trim(), causing NullPointerException.
 *
 * Fix: Replace the unit extraction logic with a null-safe version that
 * defaults to "px" when matcher.group(2) is null.
 */
public class PatchImpose {

    private static final String TARGET_CLASS = "phelps/util/Units.class";
    private static final String UNITS_CLASS   = "phelps/util/Units";
    
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: PatchImpose <input.jar> <output.jar>");
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
                    System.out.printf("  Patched %d method(s)%n", totalPatched);
                }

                JarEntry out = new JarEntry(entry.getName());
                jos.putNextEntry(out);
                jos.write(data);
                jos.closeEntry();
            }
        }

        if (totalPatched == 0) {
            System.err.println("ERROR: No methods patched — the class may have changed.");
            System.exit(1);
        }

        System.out.println("Done: " + outputPath);
    }

    static byte[] patchClass(byte[] bytes, int[] patchCount) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_FRAMES);

        for (MethodNode mn : cn.methods) {
            // Patch the method that has the null check issue
            if ("getLength".equals(mn.name) && "(Ljava/lang/String;Ljava/lang/String;)D".equals(mn.desc)) {
                // Simply replace the entire instruction list with fixed version
                mn.instructions = buildFixedGetLength();
                mn.maxStack = 4;
                mn.maxLocals = 8;
                mn.tryCatchBlocks.clear();
                patchCount[0]++;
                System.out.println("  Rewrote getLength() with null-safe logic");
            }
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Completely rewritten getLength that safely handles the case where
     * matcher.group(2) returns null (when no unit is specified).
     * 
     * New implementation:
     *   1. Get matcher and reset it with input string
     *   2. If matcher doesn't find anything, return MIN_DOUBLE
     *   3. Get group 1 (numeric part) and parse it
     *   4. Get group 2 (unit part), but DEFAULT to "px" if null
     *   5. Call convertLength with the numeric value and unit
     *   6. Return the converted result
     */
    static InsnList buildFixedGetLength() {
        InsnList l = new InsnList();
        
        // Get MATCHER_LENGTH field
        l.add(new FieldInsnNode(Opcodes.GETSTATIC, UNITS_CLASS, "MATCHER_LENGTH", 
                                 "Ljava/util/regex/Matcher;"));
        l.add(new VarInsnNode(Opcodes.ASTORE, 2));  // store in local 2
        
        // matcher.reset(aload_0)  
        l.add(new VarInsnNode(Opcodes.ALOAD, 2));
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/regex/Matcher", 
                                  "reset", "(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;", false));
        l.add(new InsnNode(Opcodes.POP));  // discard result
        
        // if (!matcher.find()) return MIN_DOUBLE
        LabelNode foundLabel = new LabelNode();
        l.add(new VarInsnNode(Opcodes.ALOAD, 2));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/regex/Matcher", 
                                  "find", "()Z", false));
        l.add(new JumpInsnNode(Opcodes.IFNE, foundLabel));
        l.add(new LdcInsnNode(4.9E-324d));  // MIN_DOUBLE
        l.add(new InsnNode(Opcodes.DRETURN));
        
        // Found match
        l.add(foundLabel);
        
        // Get numeric part: dload_3 = Double.parseDouble(matcher.group(1))
        l.add(new VarInsnNode(Opcodes.ALOAD, 2));
        l.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 1));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/regex/Matcher", 
                                  "group", "(I)Ljava/lang/String;", false));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", 
                                  "parseDouble", "(Ljava/lang/String;)D", false));
        l.add(new VarInsnNode(Opcodes.DSTORE, 3));  // Store in locals 3-4 (double takes 2 slots)
        
        // Get unit part: aload_5 = (matcher.group(2) != null) ? matcher.group(2) : "px"
        l.add(new VarInsnNode(Opcodes.ALOAD, 2));
        l.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 2));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/regex/Matcher", 
                                  "group", "(I)Ljava/lang/String;", false));
        
        // Null check: if group(2) is not null, use it; otherwise use "px"
        LabelNode hasUnit = new LabelNode();
        l.add(new InsnNode(Opcodes.DUP));  // Duplicate the result of group(2)
        l.add(new JumpInsnNode(Opcodes.IFNONNULL, hasUnit));
        l.add(new InsnNode(Opcodes.POP));  // Pop the null
        l.add(new LdcInsnNode("px"));      // Load default unit
        l.add(hasUnit);
        l.add(new VarInsnNode(Opcodes.ASTORE, 5));  // Store unit in local 5
        
        // Call convertLength(unit, "bp") and store result
        l.add(new VarInsnNode(Opcodes.ALOAD, 5));
        l.add(new LdcInsnNode("bp"));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, UNITS_CLASS, 
                                  "convertLength", "(Ljava/lang/String;Ljava/lang/String;)D", false));
        l.add(new VarInsnNode(Opcodes.DSTORE, 6));  // Store in locals 6-7 (double takes 2 slots)
        
        // Return numeric * conversion_factor
        l.add(new VarInsnNode(Opcodes.DLOAD, 3));
        l.add(new VarInsnNode(Opcodes.DLOAD, 6));
        l.add(new InsnNode(Opcodes.DMUL));
        l.add(new InsnNode(Opcodes.DRETURN));
        
        return l;
    }
}
