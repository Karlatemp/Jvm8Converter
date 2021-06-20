package io.github.karlatemp.jvm8converter;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.lang.invoke.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class Jvm8Converter {
    public static final int ASMV = Opcodes.ASM9;
    /**
     * Redirect newer jdk methods
     */
    public static boolean redirectNewMethods = true;

    public static void main(String[] args) throws Exception {
        var out = new File("build/dump/tester-1.2.3.jar");
        run(
                new File("tester/build/libs/tester-1.2.3.jar"),
                out
        );
        var loader = new URLClassLoader(new URL[]{out.toURI().toURL()});
        loader.loadClass("twunit.Tester")
                .getMethod("main", String[].class)
                .invoke(null, (Object) args);
    }

    public static void run(File src, File output) throws Exception {
        {
            var p = output.getParentFile();
            if (p != null) p.mkdirs();
        }
        var source = new ZipFile(src);
        var zipOut = new ZipOutputStream(new BufferedOutputStream(new RAFOutputStream(new RandomAccessFile(output, "rw"))));

        var pendingTransferClasses = new ArrayList<ZipEntry>(source.size());
        var resourceEntries = new ArrayList<ZipEntry>(source.size());
        for (var entry : II.of(source.entries().asIterator())) {
            if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                resourceEntries.add(entry);
                continue;
            }
            var res = source.getInputStream(entry);
            var reader = new ClassReader(res);
            res.close();
            var classVisitor = new ClassVisitor(ASMV) {
                int version;

                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    this.version = version;
                }
            };
            reader.accept(classVisitor, 0);
            if (classVisitor.version > Opcodes.V1_8) {
                pendingTransferClasses.add(entry);
            } else {
                resourceEntries.add(entry);
            }
        }

        System.out.println("Coping resources.....");

        for (var entry : resourceEntries) {
            zipOut.putNextEntry(entry);
            if (!entry.isDirectory()) {
                var res = source.getInputStream(entry);
                res.transferTo(zipOut);
                res.close();
            }
        }
        if (!pendingTransferClasses.isEmpty()) {
            var classes = new ArrayList<ClassNode>(pendingTransferClasses.size());
            for (var entry : pendingTransferClasses) {
                var node = new ClassNode();
                var rs = source.getInputStream(entry);
                new ClassReader(rs).accept(node, 0);
                rs.close();
                classes.add(node);
            }

            // Analyze accesses

            class SharedMethod {
                ClassNode declaredClass;
                MethodNode declaredMethod;
                boolean isStatic;
                String desc;
                String accessorName;
                String redirectedClassName;
            }
            class SharedField {
                ClassNode declaredClass;
                FieldNode declaredField;
                boolean isStatic;
                String getAccessName;
                String setAccessName;
                boolean hasGetAccess;
                boolean hasSetAccess;
            }

            var methods = new ArrayList<SharedMethod>(1024);
            var fields = new ArrayList<SharedField>(1024);
            var hasStringFactoryCall = false;

            var toolkit = new Object() {

                SharedMethod methodF(String targetClass, String targetMethod, String targetMethodDesc, boolean isStatic) {
                    for (var met : methods) {
                        if (met.isStatic != isStatic) continue;
                        if (!met.declaredClass.name.equals(targetClass)) continue;
                        if (!met.declaredMethod.name.equals(targetMethod)) continue;
                        if (!met.desc.equals(targetMethodDesc)) continue;
                        return met;
                    }
                    return null;
                }

                @SuppressWarnings("UnusedReturnValue")
                SharedMethod method(String targetClass, String targetMethod, String targetMethodDesc, boolean isStatic) {
                    var f = methodF(targetClass, targetMethod, targetMethodDesc, isStatic);
                    if (f != null) return f;
                    for (var klass : classes) {
                        if (!klass.name.equals(targetClass)) continue;
                        if (klass.methods == null) continue;
                        for (var method : klass.methods) {
                            if (((method.access & Opcodes.ACC_STATIC) == 0) == isStatic) continue;
                            if (!method.name.equals(targetMethod)) continue;
                            if (!method.desc.equals(targetMethodDesc)) continue;
                            var met = new SharedMethod();
                            met.declaredMethod = method;
                            met.declaredClass = klass;
                            met.isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                            met.desc = method.desc;
                            methods.add(met);
                            return met;
                        }
                    }
                    return null;
                }

                SharedField fieldF(String targetClass, String targetField, String targetFieldDesc, boolean isStatic) {
                    for (var fie : fields) {
                        if (fie.isStatic != isStatic) continue;
                        if (!fie.declaredClass.name.equals(targetClass)) continue;
                        if (!fie.declaredField.name.equals(targetField)) continue;
                        if (!fie.declaredField.desc.equals(targetFieldDesc)) continue;
                        return fie;
                    }
                    return null;
                }

                @SuppressWarnings("UnusedReturnValue")
                SharedField field(String targetClass, String targetField, String targetFieldDesc, boolean isStatic) {
                    var f = fieldF(targetClass, targetField, targetFieldDesc, isStatic);
                    if (f != null) return f;
                    for (var klass : classes) {
                        if (!klass.name.equals(targetClass)) continue;
                        if (klass.fields == null) continue;
                        for (var field : klass.fields) {
                            if (((field.access & Opcodes.ACC_STATIC) == 0) == isStatic) continue;
                            if (!field.name.equals(targetField)) continue;
                            if (!field.desc.equals(targetFieldDesc)) continue;
                            var fie = new SharedField();
                            fie.declaredField = field;
                            fie.declaredClass = klass;
                            fie.isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
                            fields.add(fie);
                            return fie;
                        }
                    }
                    return null;
                }
            };

            for (var klass : classes) {
                if (klass.methods == null) continue;
                for (var method : klass.methods) {
                    if (method.instructions == null) continue;
                    for (var insn : method.instructions) {
                        if (insn instanceof MethodInsnNode) {
                            var min = (MethodInsnNode) insn;
                            if (
                                    (klass.access & Opcodes.ACC_INTERFACE) == 0
                                            && min.owner.equals(klass.name)
                            ) continue;
                            toolkit.method(min.owner, min.name, min.desc, min.getOpcode() == Opcodes.INVOKESTATIC);
                        } else if (insn instanceof FieldInsnNode) {
                            var fin = (FieldInsnNode) insn;
                            if (fin.owner.equals(klass.name)) continue;
                            var opcode = fin.getOpcode();
                            var field = toolkit.field(fin.owner, fin.name, fin.desc, opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);
                            if (field == null) continue;
                            field.hasGetAccess |= (opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD);
                            field.hasSetAccess |= (opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD);
                        } else if (insn instanceof InvokeDynamicInsnNode) {
                            var bsm = ((InvokeDynamicInsnNode) insn).bsm;
                            if (bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) {
                                hasStringFactoryCall = true;
                            }
                        }
                    }
                }
            }

            // Drop non-private calls
            methods.removeIf(method -> (method.declaredMethod.access & Opcodes.ACC_PRIVATE) == 0);
            fields.removeIf(field -> (field.declaredField.access & Opcodes.ACC_PRIVATE) == 0);

            // gen accessors
            var counter = 0;
            for (var field : fields) {
                var klass = field.declaredClass;
                var type = Type.getType(field.declaredField.desc);
                if (field.hasGetAccess) {
                    var name = "accessor$" + counter++;
                    field.getAccessName = name;
                    var accessor = klass.visitMethod(
                            (field.isStatic ? Opcodes.ACC_STATIC : 0) | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_FINAL,
                            name, "()" + field.declaredField.desc,
                            null, null
                    );
                    if (!field.isStatic) {
                        accessor.visitVarInsn(Opcodes.ALOAD, 0);
                    }
                    accessor.visitFieldInsn(
                            field.isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD,
                            field.declaredClass.name,
                            field.declaredField.name,
                            field.declaredField.desc
                    );
                    accessor.visitInsn(type.getOpcode(Opcodes.IRETURN));
                    if (field.isStatic) {
                        accessor.visitMaxs(type.getSize() + 1, type.getSize());
                    } else {
                        accessor.visitMaxs(type.getSize() + 2, type.getSize() + 1);
                    }
                }
                if (field.hasSetAccess) {
                    var name = "accessor$" + counter++;
                    field.setAccessName = name;
                    var accessor = klass.visitMethod(
                            (field.isStatic ? Opcodes.ACC_STATIC : 0) | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_FINAL,
                            name, "(" + field.declaredField.desc + ")V",
                            null, null
                    );
                    if (!field.isStatic) {
                        accessor.visitVarInsn(Opcodes.ALOAD, 0);
                    }
                    accessor.visitVarInsn(
                            type.getOpcode(Opcodes.ILOAD),
                            field.isStatic ? 0 : 1
                    );
                    accessor.visitFieldInsn(
                            field.isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD,
                            field.declaredClass.name,
                            field.declaredField.name,
                            field.declaredField.desc
                    );
                    accessor.visitInsn(Opcodes.RETURN);
                    if (field.isStatic) {
                        accessor.visitMaxs(type.getSize() + 1, type.getSize());
                    } else {
                        accessor.visitMaxs(type.getSize() + 2, type.getSize() + 1);
                    }
                }
            }
            {
                class ItfPair {
                    ClassNode node;
                    List<SharedMethod> methods;
                }
                var itfs = new ArrayList<ItfPair>(classes.size() / 3);
                for (var method : methods) {
                    // private interface call
                    var cln = method.declaredClass;
                    if ((cln.access & Opcodes.ACC_INTERFACE) == 0) continue;
                    ItfPair pair = null;
                    for (var p0 : itfs) {
                        if (p0.node == cln) {
                            pair = p0;
                            break;
                        }
                    }
                    if (pair == null) {
                        pair = new ItfPair();
                        pair.node = cln;
                        pair.methods = new ArrayList<>(cln.methods.size());
                        itfs.add(pair);
                    }
                    pair.methods.add(method);
                }
                for (var itf : itfs) {
                    var bridge = new ClassNode();
                    bridge.visit(Opcodes.V1_8,
                            Opcodes.ACC_FINAL,
                            itf.node.name + "$Bridge$" + counter++,
                            null, "java/lang/Object", null
                    );
                    classes.add(bridge);
                    bridge.sourceDebug = itf.node.sourceDebug;
                    bridge.sourceFile = itf.node.sourceFile;
                    for (var met : itf.methods) {
                        var m = met.declaredMethod;
                        itf.node.methods.remove(m);
                        bridge.methods.add(m);
                        m.access &= ~Opcodes.ACC_PRIVATE;
                        m.access |= Opcodes.ACC_STATIC;
                        m.desc = "(L" + itf.node.name + ";" + m.desc.substring(1);
                        met.redirectedClassName = bridge.name;
                        met.accessorName = m.desc;
                    }
                }
            }
            for (var method : methods) {
                if (method.accessorName != null) continue;
                if (method.declaredMethod.name.equals("<init>")) {
                    // java.lang.SuppressWarnings;
                    // )V
                    var desc = method.declaredMethod.desc;
                    desc = desc.substring(0, desc.length() - 2) + "Ljava/lang/SuppressWarnings;)V";
                    method.accessorName = desc;
                    var classNode = method.declaredClass;
                    var accessor = classNode.visitMethod(
                            Opcodes.ACC_SYNTHETIC,
                            "<init>",
                            desc, null, null
                    );
                    accessor.visitVarInsn(Opcodes.ALOAD, 0);
                    var slot = 1;
                    for (var t : Type.getArgumentTypes(method.declaredMethod.desc)) {
                        accessor.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                        slot += t.getSize();
                    }
                    accessor.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            method.declaredClass.name,
                            "<init>",
                            method.declaredMethod.desc,
                            false
                    );
                    accessor.visitInsn(Opcodes.RETURN);
                    slot++;
                    accessor.visitMaxs(slot, slot);

                } else {
                    var name = "accessor$" + counter++;
                    method.accessorName = name;
                    var classNode = method.declaredClass;
                    var desc = method.declaredMethod.desc;
                    var accessor = classNode.visitMethod(
                            (method.isStatic ? Opcodes.ACC_STATIC : 0) | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_FINAL,
                            name,
                            desc,
                            null, null
                    );
                    int slot = 0;
                    if (!method.isStatic) {
                        slot++;
                        accessor.visitVarInsn(Opcodes.ALOAD, 0);
                    }
                    for (var t : Type.getArgumentTypes(desc)) {
                        accessor.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                        slot += t.getSize();
                    }

                    accessor.visitMethodInsn(
                            method.isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                            method.declaredClass.name,
                            method.declaredMethod.name,
                            method.declaredMethod.desc,
                            false
                    );

                    accessor.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));

                    slot++;
                    accessor.visitMaxs(slot, slot);
                }
            }

            String stringFactoryName = null;
            String pkg;
            {
                var pkgedClasses = classes.stream().filter(it -> it.name.indexOf('/') != -1)
                        .toArray(ClassNode[]::new);
                pkg = pkgedClasses.length == 0
                        ? "stasdcj/"
                        : Optional.of(pkgedClasses[Math.abs(new Random().nextInt()) % pkgedClasses.length])
                        .map(it -> it.name.substring(0, it.name.lastIndexOf('/') + 1)).get();
                pkg += UUID.randomUUID() + "/";
            }
            var ngener = new Kit.NameGenerator(() -> UUID.randomUUID().toString());
            if (hasStringFactoryCall) {
                var cfs = List.of(StringCF.class, StringCCF.class, JInvokeCF.class);
                var mappings = new HashMap<String, String>();
                for (var klass : cfs) {
                    mappings.put(klass.getName().replace('.', '/'), pkg + ngener.get());
                }
                var remapper = new SimpleRemapper(mappings);
                for (var klass : cfs) {
                    var node = new ClassNode();
                    new ClassReader(klass.getName())
                            .accept(new ClassRemapper(node, remapper), 0);
                    if (klass == StringCCF.class) {
                        stringFactoryName = node.name;
                    }
                    classes.add(node);
                    node.innerClasses.clear();
                }

            }
            if (redirectNewMethods) {
                MethodBridgeMap.inject(classes, pkg, ngener);
            }
            // replace calls
            for (var klass : classes) {
                klass.version = Opcodes.V1_8;
                klass.module = null;
                klass.nestHostClass = null;
                klass.nestMembers = null;
                klass.permittedSubclasses = null;

                if (klass.methods == null) continue;
                for (var method : klass.methods) {
                    if (method.instructions == null) continue;
                    var insnIterator = method.instructions.iterator();
                    var omittedExpand = false;
                    for (var insn : II.of(insnIterator)) {
                        if (insn instanceof MethodInsnNode) {
                            var min = (MethodInsnNode) insn;
                            var met = toolkit.methodF(min.owner, min.name, min.desc, min.getOpcode() == Opcodes.INVOKESTATIC);
                            if (met == null) continue;
                            if (met.redirectedClassName == null && min.owner.equals(klass.name)) continue;
                            if (min.name.equals("<init>")) {
                                if (met.accessorName != null) {
                                    min.desc = met.accessorName;
                                    insnIterator.previous();
                                    insnIterator.add(new InsnNode(Opcodes.ACONST_NULL));
                                    insnIterator.next();
                                    omittedExpand = true;
                                }
                            } else if (met.accessorName != null) {
                                min.name = met.accessorName;
                                if (met.redirectedClassName != null) {
                                    min.owner = met.redirectedClassName;
                                    min.desc = met.accessorName;
                                    min.name = met.declaredMethod.name;
                                    min.setOpcode(Opcodes.INVOKESTATIC);
                                    min.itf = false;
                                }
                            }
                        } else if (insn instanceof FieldInsnNode) {
                            var fin = (FieldInsnNode) insn;
                            if (fin.owner.equals(klass.name)) continue;
                            var opcode = fin.getOpcode();
                            var field = toolkit.field(fin.owner, fin.name, fin.desc, opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);
                            if (field == null) continue;
                            if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                                var getAccessName = field.getAccessName;
                                insnIterator.set(new MethodInsnNode(
                                        field.isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL,
                                        fin.owner,
                                        getAccessName,
                                        "()" + fin.desc,
                                        false
                                ));
                            } else {
                                var setAccessName = field.setAccessName;
                                insnIterator.set(new MethodInsnNode(
                                        field.isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL,
                                        fin.owner,
                                        setAccessName,
                                        "(" + fin.desc + ")V",
                                        false
                                ));
                            }
                        } else if (insn instanceof InvokeDynamicInsnNode) {
                            var idn = ((InvokeDynamicInsnNode) insn);
                            var bsm = idn.bsm;
                            if (bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) {
                                insnIterator.set(new InvokeDynamicInsnNode(
                                        idn.name,
                                        idn.desc,
                                        new Handle(
                                                bsm.getTag(),
                                                stringFactoryName,
                                                bsm.getName(),
                                                bsm.getDesc(),
                                                bsm.isInterface()
                                        ),
                                        idn.bsmArgs
                                ));
                            }
                        }
                    }

                    if (omittedExpand) {
                        method.maxStack++;
                    }
                }
            }
            // write
            for (var klass : classes) {
                ClassWriter writer = new ClassWriter(0);
                klass.accept(writer);
                zipOut.putNextEntry(new ZipEntry(klass.name + ".class"));
                zipOut.write(writer.toByteArray());
                //klass.accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(System.out)));
            }
        }

        zipOut.close();
    }

    public static class II<T> implements Iterable<T> {
        public static <T> II<T> of(Iterator<T> iterator) {
            return new II<>(iterator);
        }

        private final Iterator<T> iterator;

        public II(Iterator<T> iterator) {
            this.iterator = iterator;
        }

        @Override
        public Iterator<T> iterator() {
            return iterator;
        }
    }

    public static class RAFOutputStream extends OutputStream {
        private final RandomAccessFile raf;

        public RAFOutputStream(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public void write(int b) throws IOException {
            raf.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            raf.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            raf.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            raf.setLength(raf.getFilePointer());
            raf.close();
        }
    }

    public static class StringCF {

        /**
         * Tag used to demarcate an ordinary argument.
         */
        private static final char TAG_ARG = '\u0001';

        /**
         * Tag used to demarcate a constant.
         */
        private static final char TAG_CONST = '\u0002';

        /**
         * Maximum number of argument slots in String Concat call.
         * <p>
         * While the maximum number of argument slots that indy call can handle is 253,
         * we do not use all those slots, to let the strategies with MethodHandle
         * combinators to use some arguments.
         */
        private static final int MAX_INDY_CONCAT_ARG_SLOTS = 200;

        public CallSite makeConcat(
                MethodHandles.Lookup lookup,
                String name,
                MethodType concatType
        ) throws Exception {
            // This bootstrap method is unlikely to be used in practice,
            // avoid optimizing it at the expense of makeConcatWithConstants

            // Mock the recipe to reuse the concat generator code
            char[] c = new char[concatType.parameterCount()];
            Arrays.fill(c, '\u0001');
            String recipe = new String(c);
            return makeConcatWithConstants(lookup, name, concatType, recipe);
        }

        public CallSite makeConcatWithConstants(
                MethodHandles.Lookup lookup,
                String name,
                MethodType concatType,
                String recipe,
                Object... constants
        ) throws Exception {
            Objects.requireNonNull(lookup, "Lookup is null");
            Objects.requireNonNull(name, "Name is null");
            Objects.requireNonNull(concatType, "Concat type is null");
            Objects.requireNonNull(constants, "Constants are null");

            for (Object o : constants) {
                Objects.requireNonNull(o, "Cannot accept null constants");
            }

            if ((lookup.lookupModes() & MethodHandles.Lookup.PRIVATE) == 0) {
                throw new RuntimeException("Invalid caller: " +
                        lookup.lookupClass().getName());
            }

            var recipes = parseRecipe(concatType, recipe, constants);

            if (!concatType.returnType().isAssignableFrom(String.class)) {
                throw new RuntimeException(
                        "The return type should be compatible with String, but it is " +
                                concatType.returnType());
            }

            if (parameterSlotCount(concatType) > MAX_INDY_CONCAT_ARG_SLOTS) {
                throw new RuntimeException("Too many concat argument slots: " +
                        parameterSlotCount(concatType) +
                        ", can only accept " +
                        MAX_INDY_CONCAT_ARG_SLOTS);
            }

            return new ConstantCallSite(genMh(recipes, concatType));
        }

        private static String join(List<String> rec, Object... args) {
            StringBuilder sb = new StringBuilder();
            Iterator<Object> iterator = Arrays.asList(args).iterator();

            for (var s : rec) {
                if (s == null) {
                    sb.append(iterator.next());
                } else {
                    sb.append(s);
                }
            }

            return sb.toString();
        }

        private static MethodHandle JOIN;

        private static MethodHandle genMh(List<String> rec, MethodType mt) {
            if (JOIN == null) {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                try {
                    JOIN = lookup.findStatic(StringCF.class, "join", MethodType.methodType(String.class, List.class, Object[].class));
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new InternalError(e);
                }
            }
            return MethodHandles.insertArguments(JOIN, 0, rec).asVarargsCollector(Object[].class).asType(mt);
        }

        private static int parameterSlotCount(MethodType mt) {
            int size = 0;
            for (int i = 0; i < mt.parameterCount(); i++) {
                size++;
                var type = mt.parameterType(i);
                if (type == double.class || type == long.class) {
                    size++;
                }
            }
            return size;
        }

        private static List<String> parseRecipe(MethodType concatType,
                                                String recipe,
                                                Object[] constants) {
            Objects.requireNonNull(recipe, "Recipe is null");
            // Element list containing String constants, or null for arguments
            List<String> elements = new ArrayList<>();

            int cCount = 0;
            int oCount = 0;

            StringBuilder acc = new StringBuilder();

            for (int i = 0; i < recipe.length(); i++) {
                char c = recipe.charAt(i);

                if (c == TAG_CONST) {
                    if (cCount == constants.length) {
                        // Not enough constants
                        throw constantMismatch(constants, cCount);
                    }
                    // Accumulate constant args along with any constants encoded
                    // into the recipe
                    acc.append(constants[cCount++]);
                } else if (c == TAG_ARG) {
                    // Flush any accumulated characters into a constant
                    if (acc.length() > 0) {
                        elements.add(acc.toString());
                        acc.setLength(0);
                    }
                    elements.add(null);
                    oCount++;
                } else {
                    // Not a special character, this is a constant embedded into
                    // the recipe itself.
                    acc.append(c);
                }
            }

            // Flush the remaining characters as constant:
            if (acc.length() > 0) {
                elements.add(acc.toString());
            }
            if (oCount != concatType.parameterCount()) {
                throw argumentMismatch(concatType, oCount);
            }
            if (cCount < constants.length) {
                throw constantMismatch(constants, cCount);
            }
            return elements;
        }

        private static RuntimeException argumentMismatch(MethodType concatType,
                                                         int oCount) {
            return new RuntimeException(
                    "Mismatched number of concat arguments: recipe wants " +
                            oCount +
                            " arguments, but signature provides " +
                            concatType.parameterCount());
        }

        private static RuntimeException constantMismatch(Object[] constants,
                                                         int cCount) {
            return new RuntimeException(
                    "Mismatched number of concat constants: recipe wants " +
                            cCount +
                            " constants, but only " +
                            constants.length +
                            " are passed");
        }

    }

    public static class JInvokeCF extends StringCF {
        public CallSite makeConcat(
                MethodHandles.Lookup lookup,
                String name,
                MethodType concatType
        ) throws Exception {
            return StringConcatFactory.makeConcat(lookup, name, concatType);
        }

        public CallSite makeConcatWithConstants(
                MethodHandles.Lookup lookup,
                String name,
                MethodType concatType,
                String recipe,
                Object... constants
        ) throws Exception {
            return StringConcatFactory.makeConcatWithConstants(lookup, name, concatType, recipe, constants);
        }
    }

    public static class StringCCF {
        static final StringCF CF;

        static {
            StringCF c;
            try {
                Class.forName("java.lang.invoke.StringConcatFactory");
                c = new JInvokeCF();
            } catch (ClassNotFoundException ignore) {
                c = new StringCF();
            }
            CF = c;
        }

        public static CallSite makeConcatWithConstants(
                MethodHandles.Lookup lookup,
                String name,
                MethodType concatType,
                String recipe,
                Object... constants
        ) throws Exception {
            return CF.makeConcatWithConstants(lookup, name, concatType, recipe, constants);
        }

        public static CallSite makeConcat(
                MethodHandles.Lookup lookup,
                String name,
                MethodType concatType
        ) throws Exception {
            return CF.makeConcat(lookup, name, concatType);
        }
    }

}
