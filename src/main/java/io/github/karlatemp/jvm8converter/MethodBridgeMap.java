package io.github.karlatemp.jvm8converter;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class MethodBridgeMap {
    public static class MethodRedirectInfo {
        public String type;
        public String name;
        public String desc;

        public String redirectedType;
        public String redirectedName;
        public String redirectedDesc;

        @Override
        public String toString() {
            return "MethodRedirectInfo{" +
                    "type='" + type + '\'' +
                    ", name='" + name + '\'' +
                    ", desc='" + desc + '\'' +
                    ", redirectedType='" + redirectedType + '\'' +
                    ", redirectedName='" + redirectedName + '\'' +
                    ", redirectedDesc='" + redirectedDesc + '\'' +
                    '}';
        }
    }

    private ArrayList<ClassNode> nodes = new ArrayList<>();
    private ArrayList<Kit.Pair<ClassNode, Type>> bridges;
    private Map<String, ClassNode> itfs;
    private ClassNode bgGenerator;
    private ArrayList<ClassNode> proxys;
    private ArrayList<MethodRedirectInfo> redirectInfos = new ArrayList<>();

    private static Iterable<Class<?>> cc() {
        ArrayDeque<Class<?>> cwx = new ArrayDeque<>(Arrays.asList(MethodBridges.class.getDeclaredClasses()));
        return Jvm8Converter.II.of(new Iterator<Class<?>>() {
            @Override
            public boolean hasNext() {
                return !cwx.isEmpty();
            }

            @Override
            public Class<?> next() {
                var c = cwx.pollLast();
                assert c != null;
                cwx.addAll(List.of(c.getDeclaredClasses()));
                return c;
            }
        });
    }

    private void genBridges() throws Exception {
        var nodes = this.nodes;
        var bridges = new ArrayList<Kit.Pair<ClassNode, Type>>();
        for (var c : cc()) {
            var node = new ClassNode();
            new ClassReader(c.getName()).accept(node, 0);
            nodes.add(node);
            node.innerClasses.clear();
            node.outerClass = null;
            var shadow = c.getAnnotation(Shadow.class);
            if (shadow != null) {
                bridges.add(new Kit.Pair<>(node, Type.getType(shadow.value())));
            }
            if (c == MethodBridges.BridgeGenerator.class) {
                bgGenerator = node;
            }
            // LDC Lio/github/karlatemp/jvm8converter/MethodBridges;.class
            for (var met : node.methods) {
                if (met.name.equals("<clinit>")) {
                    var n = MethodBridges.class.getName().replace('.', '/');
                    if (met.instructions == null) continue; // ??
                    for (var insn : met.instructions) {
                        if (insn instanceof LdcInsnNode) {
                            var ldc = (LdcInsnNode) insn;
                            var cst = ldc.cst;
                            if (cst instanceof Type) {
                                var tc = (Type) cst;
                                if (tc.getInternalName().equals(n)) {
                                    ldc.cst = Type.getObjectType(node.name);
                                }
                            }
                        }
                    }
                }
            }
        }
        this.nodes = nodes;
        this.bridges = bridges;

    }

    private void genInterfaces() throws Exception {
        var nodes = this.nodes;
        this.itfs = new HashMap<>();
        for (var bridge : bridges) {
            for (var met : bridge.k.methods) {
                if (met.name.equals("<init>") || met.name.equals("<clinit>")) continue;
                var desc = met.desc;
                if (itfs.containsKey(desc)) continue;
                var bg = new ClassNode();
                var name = desc.substring(1)
                        .replace(';', '$')
                        .replace('/', '_')
                        .replace(")", "$_$$");
                bg.visit(Opcodes.V1_8,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                        "c/" + name,
                        null, "java/lang/Object", null
                );
                bg.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                        "invoke", desc, null, null
                );
                nodes.add(bg);
                itfs.put(desc, bg);
            }
        }
    }

    private void genProxy() throws Exception {
        var genbridgeDesc = "(" +
                "Ljava/lang/Class;" +  // Class<?> itf,
                "Ljava/lang/String;" + // String sam0,
                "Ljava/lang/Class;" +  //  Class<?> target,
                "Ljava/lang/String;" + // String name,
                "Ljava/lang/String;" + // String desc,
                "Ljava/lang/Class;" +  // Class<?> bridge,
                "Ljava/lang/String;" + // String bridgeDesc,
                "Z" +                  // boolean isStatic
                "" +
                ")Ljava/lang/Object;";
        var redirectInfos = this.redirectInfos;
        var proxys = this.proxys = new ArrayList<>();
        for (var bridge : bridges) {
            var proxy = new ClassNode();
            proxy.visit(Opcodes.V1_8,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                    "p/" + Kit.simpleName(bridge.k.name),
                    null, "java/lang/Object", null
            );
            nodes.add(proxy);
            proxys.add(proxy);
            var clinit = proxy.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitMaxs(10, 3);

            var counter = 0;
            for (var bmet : bridge.k.methods) {
                if (bmet.name.charAt(0) == '<') continue;
                if ((bmet.access & Opcodes.ACC_PUBLIC) == 0) continue;

                bmet.instructions.insert(assertNotThisNull());
                bmet.maxStack = Math.max(bmet.maxStack, 3);

                var mt = proxy.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        bmet.name, bmet.desc, null, null
                );
                var itfName = "f" + counter++;
                var ifN = itfs.get(bmet.desc).name;
                var itfDesc = "L" + ifN + ";";
                proxy.visitField(
                        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE,
                        itfName, itfDesc,
                        null, null
                );
                /*
                genBridge(
                        Class<?> itf,
                        String sam0,
                        Class<?> target,
                        String name,
                        String desc,
                        Class<?> bridge,
                        String bridgeDesc,
                        boolean isStatic
                )
                 */
                clinit.visitLdcInsn(Type.getObjectType(ifN));
                clinit.visitLdcInsn(bmet.desc);
                clinit.visitLdcInsn(bridge.v);
                clinit.visitLdcInsn(bmet.name);
                boolean isStatic = Kit.isAnnotated(bmet, ShadowStatic.class);
                String odesc;
                if (isStatic) {
                    clinit.visitLdcInsn(odesc = bmet.desc);
                } else {
                    clinit.visitLdcInsn(odesc = "(" + bmet.desc.substring(bmet.desc.indexOf(';') + 1));
                }
                clinit.visitLdcInsn(Type.getObjectType(bridge.k.name));
                clinit.visitLdcInsn(bmet.desc);
                clinit.visitLdcInsn(isStatic);

                clinit.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        bgGenerator.name,
                        "genBridge",
                        genbridgeDesc,
                        false
                );
                clinit.visitTypeInsn(Opcodes.CHECKCAST, ifN);
                clinit.visitFieldInsn(Opcodes.PUTSTATIC, proxy.name, itfName, itfDesc);

                mt.visitFieldInsn(Opcodes.GETSTATIC, proxy.name, itfName, itfDesc);
                var slot = 0;
                for (var type : Type.getArgumentTypes(bmet.desc)) {
                    mt.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
                    slot += type.getSize();
                }
                mt.visitMethodInsn(Opcodes.INVOKEINTERFACE, ifN, "invoke", bmet.desc, true);
                mt.visitInsn(Type.getReturnType(bmet.desc).getOpcode(Opcodes.IRETURN));
                mt.visitMaxs(slot + 1, slot);

                var ri = new MethodRedirectInfo();
                ri.type = bridge.v.getInternalName();
                ri.name = bmet.name;
                ri.desc = odesc;

                ri.redirectedType = proxy.name;
                ri.redirectedDesc = bmet.desc;
                ri.redirectedName = bmet.name;

                redirectInfos.add(ri);
            }
            clinit.visitInsn(Opcodes.RETURN);
        }
    }

    private InsnList assertNotThisNull() {
        var list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new LdcInsnNode("`this` cannot be `null`"));
        list.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Objects",
                "requireNonNull",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                false
        ));
        list.add(new InsnNode(Opcodes.POP));
        return list;
    }

    private void drop() {
        for (var node : nodes) {
            node.visibleAnnotations = null;
            node.invisibleAnnotations = null;
            for (var met : node.methods) {
                met.visibleAnnotations = null;
                met.invisibleAnnotations = null;
            }
        }
    }

    private void obf(String pkg, Kit.NameGenerator nameGenerator) {
        bridges.clear();
        itfs.clear();
        proxys.clear();
        var mappings = new HashMap<String, String>();
        for (var node : nodes) {
            mappings.put(node.name, pkg + nameGenerator.get());
        }
        var newNodes = new ArrayList<ClassNode>(nodes.size());
        var remapper = new SimpleRemapper(mappings);
        for (var node : nodes) {
            var nw = new ClassNode();
            newNodes.add(nw);
            node.accept(new ClassRemapper(nw, remapper));
        }
        nodes.clear();
        nodes.addAll(newNodes);
        for (var info : redirectInfos) {
            info.redirectedType = remapper.map(info.redirectedType);
        }
    }

    private MethodRedirectInfo redirectInfo(String type, String name, String desc) throws Exception {
        var tts = ClassTypeAnalyze.types(type);
        if (tts == null) return null;
        for (var typex : tts) {
            for (var info : redirectInfos) {
                if (info.desc.equals(desc)) {
                    if (info.name.equals(name)) {
                        if (info.type.equals(typex))
                            return info;
                    }
                }
            }
        }
        return null;
    }

    public static void inject(Collection<ClassNode> nodes, String pkg, Kit.NameGenerator nameGenerator) throws Exception {

        var map = new MethodBridgeMap();
        map.genBridges();
        map.genInterfaces();
        map.genProxy();
        map.drop();
        map.obf(pkg, nameGenerator);
        nodes.addAll(map.nodes);

        for (var red : map.redirectInfos) {
            System.out.println(red);
        }
        //Kit.dump(map.nodes, new File("build/dump/s.r.z"));

        for (var node : nodes) {
            for (var met : node.methods) {
                if (met.instructions == null) continue;
                var insnListItr = met.instructions.iterator();
                for (var insn : Jvm8Converter.II.of(insnListItr)) {
                    if (insn instanceof MethodInsnNode) {
                        var min = (MethodInsnNode) insn;
                        var ddsc = map.redirectInfo(min.owner, min.name, min.desc);
                        if (ddsc == null) continue;
                        min.owner = ddsc.redirectedType;
                        min.name = ddsc.redirectedName;
                        min.desc = ddsc.redirectedDesc;
                        min.setOpcode(Opcodes.INVOKESTATIC);
                    } else if (insn instanceof InvokeDynamicInsnNode) {
                        var din = (InvokeDynamicInsnNode) insn;
                        var bargs = din.bsmArgs;
                        for (var i = 0; i < bargs.length; i++) {
                            var oj = bargs[i];
                            if (oj instanceof Handle) {
                                var handle = (Handle) oj;
                                var ddsc = map.redirectInfo(handle.getOwner(), handle.getName(), handle.getDesc());
                                if (ddsc == null) continue;
                                bargs[i] = new Handle(
                                        Opcodes.H_INVOKESTATIC,
                                        ddsc.redirectedType,
                                        ddsc.redirectedName,
                                        ddsc.redirectedDesc,
                                        false
                                );
                            }
                        }
                    }
                }
            }
        }

    }

    public static void main(String[] args) throws Exception {
        var map = new MethodBridgeMap();
        map.genBridges();
        map.genInterfaces();
        map.genProxy();
        map.drop();

        for (var f : map.redirectInfos) {
            System.out.println(f);
        }

    }

    /*
    public static void main(String[] args) throws Throwable {
        var bg = (TwItf) MethodBridges.BridgeGenerator.genBridge(
                TwItf.class,
                MethodType.methodType(boolean.class, String.class),
                String.class,
                "isEmptyz",
                "()Z",
                MethodBridgeMap.class,
                "(Ljava/lang/String;)Z",
                false
        );
    }
     */

}

class ClassTypeAnalyze {
    static final Map<String, Collection<String>> types = new HashMap<>();

    static Collection<String> types(String type) throws Exception {
        {
            var r = types.get(type);
            if (r != null) return r;
        }
        var rsu = ClassLoader.getPlatformClassLoader().getResource(type + ".class");
        if (rsu == null) return null;
        ClassReader reader;
        try (var res = rsu.openStream()) {
            reader = new ClassReader(res);
        }
        var resp = new HashSet<String>();
        resp.add(reader.getClassName());
        addIfNotNull(resp, reader.getSuperName());
        types.put(type, resp);
        var interfaces = reader.getInterfaces();
        if (interfaces != null) {
            for (var itf : interfaces) {
                var rrsp = types(itf);
                if (rrsp != null) resp.addAll(rrsp);
            }
        }
        resp.add("java/lang/Object");

        return resp;
    }

    static void addIfNotNull(Collection<String> c, String v) {
        if (v != null) c.add(v);
    }
}


@Retention(RetentionPolicy.RUNTIME)
@interface Shadow {
    Class<?> value();
}

@Retention(RetentionPolicy.RUNTIME)
@interface ShadowStatic {
}

@SuppressWarnings("DuplicatedCode")
class MethodBridges {
    public static class BridgeGenerator {

        static final MethodHandles.Lookup lk = MethodHandles.lookup();


        public static Object genBridge(
                Class<?> itf,
                String sam0,
                Class<?> target,
                String name,
                String desc,
                Class<?> bridge,
                String bridgeDesc,
                boolean isStatic
        ) throws Throwable {
            var sam = MethodType.fromMethodDescriptorString(sam0, ClassLoader.getSystemClassLoader());
            var bgtype = MethodType.fromMethodDescriptorString(bridgeDesc, ClassLoader.getSystemClassLoader());
            try {
                var mt = MethodType.fromMethodDescriptorString(desc, ClassLoader.getSystemClassLoader());
                MethodHandle handle;
                if (isStatic) {
                    handle = lk.findStatic(target, name, mt);
                } else {
                    handle = lk.findVirtual(target, name, mt);
                }
                return LambdaMetafactory.metafactory(
                        lk, "invoke", MethodType.methodType(itf), sam,
                        handle,
                        sam
                ).getTarget().invoke();
            } catch (NoSuchMethodException ignore) {
                return LambdaMetafactory.metafactory(
                        lk, "invoke", MethodType.methodType(itf), sam,
                        lk.findStatic(bridge, name, bgtype),
                        sam
                ).getTarget().invoke();
            }
        }
    }

    @Shadow(String.class)
    static class StringB {
        public static String strip(String thiz) {
            int start = -1;
            var il = thiz.length();
            for (var i = 0; i < il; i++) {
                if (!Character.isWhitespace(thiz.charAt(i))) {
                    start = i;
                    break;
                }
            }
            if (start == -1) return "";
            int end = -1;
            for (var i = il - 1; i >= 0; i--) {
                if (!Character.isWhitespace(thiz.charAt(i))) {
                    end = i + 1;
                    break;
                }
            }
            assert end != -1;
            return thiz.substring(start, end);
        }

        public static String stripTrailing(String thiz) {
            var il = thiz.length();
            int end = -1;
            for (var i = il - 1; i >= 0; i--) {
                if (!Character.isWhitespace(thiz.charAt(i))) {
                    end = i + 1;
                    break;
                }
            }
            if (end == -1) return "";
            return thiz.substring(0, end);
        }

        public static String stripLeading(String thiz) {
            int start = indexOfNonWhitespace(thiz);
            if (start == -1) return "";
            return thiz.substring(start);
        }

        public static boolean isBlank(String thiz) {
            var il = thiz.length();
            for (var i = 0; i < il; i++) {
                if (!Character.isWhitespace(thiz.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        public static Stream<String> lines(String str) {
            var scanner = new Scanner(str);
            return StreamSupport.stream(new Spliterators.AbstractSpliterator<>(
                    Long.MAX_VALUE,
                    Spliterator.IMMUTABLE | Spliterator.ORDERED
            ) {
                @Override
                public boolean tryAdvance(Consumer<? super String> action) {
                    if (scanner.hasNextLine()) {
                        action.accept(scanner.nextLine());
                        return true;
                    }
                    return false;
                }
            }, false);
        }

        private static int indexOfNonWhitespace(String thiz) {
            int start = -1;
            var il = thiz.length();
            for (var i = 0; i < il; i++) {
                if (!Character.isWhitespace(thiz.charAt(i))) {
                    start = i;
                    break;
                }
            }
            return start;
        }

        private static int lastIndexOfNonWhitespace(String thiz) {
            var i = thiz.length() - 1;
            while (i-- > 0) {
                if (!Character.isWhitespace(thiz.charAt(i)))
                    return i;
            }
            return -1;
        }


        @SuppressWarnings("Convert2MethodRef")
        public static String indent(String thiz, int n) {
            if (thiz.isEmpty()) {
                return "";
            }
            Stream<String> stream = thiz.lines();
            if (n > 0) {
                final String spaces = " ".repeat(n);
                stream = stream.map(s -> spaces + s);
            } else if (n == Integer.MIN_VALUE) {
                stream = stream.map(s -> s.stripLeading());
            } else if (n < 0) {
                stream = stream.map(s -> s.substring(Math.min(-n, indexOfNonWhitespace(s))));
            }
            return stream.collect(Collectors.joining("\n", "", "\n"));
        }

        private static int outdent(List<String> lines) {
            // Note: outdent is guaranteed to be zero or positive number.
            // If there isn't a non-blank line then the last must be blank
            int outdent = Integer.MAX_VALUE;
            for (String line : lines) {
                int leadingWhitespace = indexOfNonWhitespace(line);
                if (leadingWhitespace != line.length()) {
                    outdent = Integer.min(outdent, leadingWhitespace);
                }
            }
            String lastLine = lines.get(lines.size() - 1);
            if (lastLine.isBlank()) {
                outdent = Integer.min(outdent, lastLine.length());
            }
            return outdent;
        }

        public static String stripIndent(String thiz) {
            int length = thiz.length();
            if (length == 0) {
                return "";
            }
            char lastChar = thiz.charAt(length - 1);
            boolean optOut = lastChar == '\n' || lastChar == '\r';
            List<String> lines = thiz.lines().collect(Collectors.toList());
            final int outdent = optOut ? 0 : outdent(lines);
            return lines.stream()
                    .map(line -> {
                        int firstNonWhitespace = indexOfNonWhitespace(line);
                        int lastNonWhitespace = lastIndexOfNonWhitespace(thiz);
                        int incidentalWhitespace = Math.min(outdent, firstNonWhitespace);
                        return firstNonWhitespace > lastNonWhitespace
                                ? "" : line.substring(incidentalWhitespace, lastNonWhitespace);
                    })
                    .collect(Collectors.joining("\n", "", optOut ? "\n" : ""));

        }

        public static String translateEscapes(String thiz) {
            if (thiz.isEmpty()) {
                return "";
            }
            char[] chars = thiz.toCharArray();
            int length = chars.length;
            int from = 0;
            int to = 0;
            while (from < length) {
                char ch = chars[from++];
                if (ch == '\\') {
                    ch = from < length ? chars[from++] : '\0';
                    switch (ch) {
                        case 'b':
                            ch = '\b';
                            break;
                        case 'f':
                            ch = '\f';
                            break;
                        case 'n':
                            ch = '\n';
                            break;
                        case 'r':
                            ch = '\r';
                            break;
                        case 's':
                            ch = ' ';
                            break;
                        case 't':
                            ch = '\t';
                            break;
                        case '\'':
                        case '\"':
                        case '\\':
                            // as is
                            break;
                        case '0':
                        case '1':
                        case '2':
                        case '3':
                        case '4':
                        case '5':
                        case '6':
                        case '7':
                            int limit = Integer.min(from + (ch <= '3' ? 2 : 1), length);
                            int code = ch - '0';
                            while (from < limit) {
                                ch = chars[from];
                                if (ch < '0' || '7' < ch) {
                                    break;
                                }
                                from++;
                                code = (code << 3) | (ch - '0');
                            }
                            ch = (char) code;
                            break;
                        case '\n':
                            continue;
                        case '\r':
                            if (from < length && chars[from] == '\n') {
                                from++;
                            }
                            continue;
                        default: {
                            String msg = String.format(
                                    "Invalid escape sequence: \\%c \\\\u%04X",
                                    ch, (int) ch);
                            throw new IllegalArgumentException(msg);
                        }
                    }
                }

                chars[to++] = ch;
            }

            return new String(chars, 0, to);
        }

        public static <R> R transform(String thiz, Function<? super String, ? extends R> f) {
            return f.apply(thiz);
        }

        public static String formatted(String thiz, Object... args) {
            return String.format(thiz, args);
        }

        public static String repeat(String thiz, int count) {
            if (count < 0) {
                throw new IllegalArgumentException("count is negative: " + count);
            }
            if (count == 1) return thiz;
            if (thiz.isEmpty()) return thiz;
            char[] value = thiz.toCharArray();
            var limit = value.length * count;
            char[] multiple = new char[limit];

            var len = value.length;
            System.arraycopy(value, 0, multiple, 0, len);
            int copied = len;
            for (; copied < limit - copied; copied <<= 1) {
                System.arraycopy(multiple, 0, multiple, copied, copied);
            }
            System.arraycopy(multiple, 0, multiple, copied, limit - copied);

            return new String(multiple);
        }
    }

}
