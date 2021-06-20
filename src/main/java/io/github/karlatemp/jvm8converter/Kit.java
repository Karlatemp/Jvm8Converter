package io.github.karlatemp.jvm8converter;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@SuppressWarnings("all")
public class Kit {
    public static void write(Collection<ClassNode> nodes, File output) throws Exception {
        try (var zos = new ZipOutputStream(new BufferedOutputStream(new Jvm8Converter.RAFOutputStream(new RandomAccessFile(output, "rw"))))) {
            for (var node : nodes) {
                var writer = new ClassWriter(0);
                node.accept(writer);
                zos.putNextEntry(new ZipEntry(node.name + ".class"));
                zos.write(writer.toByteArray());
            }
        }
    }

    public static void delete(File fos) throws Exception {
        if (fos.isDirectory()) {
            var lf = fos.listFiles();
            if (lf != null) {
                for (var f : lf) {
                    delete(f);
                }
            }
        }
        fos.delete();
    }

    public static void dump(Collection<ClassNode> nodes, File output) throws Exception {
        // delete(output);
        output.mkdirs();
        for (var n : nodes) {
            var os = new File(output, n.name + ".txt");
            os.getParentFile().mkdirs();
            try (var pw = new PrintWriter(new Jvm8Converter.RAFOutputStream(new RandomAccessFile(os, "rw")))) {
                n.accept(new TraceClassVisitor(null, new Textifier(), pw));
            }
        }
    }

    public static String simpleName(String bridge) {
        int lw = Math.max(
                bridge.lastIndexOf('/'),
                bridge.lastIndexOf('$')
        );
        if (lw == -1) return bridge;
        return bridge.substring(lw + 1);
    }

    public static class Pair<K, V> {
        public final K k;
        public final V v;

        public Pair(K k, V v) {
            this.k = k;
            this.v = v;
        }
    }

    public static String annoName(Class<?> n) {
        return "L" + n.getName().replace('.', '/') + ";";
    }

    public static boolean isAnnotated(ClassNode cn, Class<?> n) {
        return isAnnotated(annoName(n), cn.invisibleAnnotations, cn.visibleAnnotations);
    }

    public static boolean isAnnotated(MethodNode cn, Class<?> n) {
        return isAnnotated(annoName(n), cn.invisibleAnnotations, cn.visibleAnnotations);
    }

    public static boolean isAnnotated(FieldNode cn, Class<?> n) {
        return isAnnotated(annoName(n), cn.invisibleAnnotations, cn.visibleAnnotations);
    }

    public static boolean isAnnotated(String n, List<AnnotationNode>... an) {
        return Stream.of(an)
                .filter(Objects::nonNull)
                .anyMatch(it -> it.contains(n));
    }

    public static class NameGenerator {
        private final Supplier<String> getter;
        private final Set<String> used = new HashSet<>();

        public NameGenerator(Supplier<String> getter) {
            this.getter = getter;
        }

        public String get() {
            while (true) {
                var n = getter.get();
                if (used.add(n)) return n;
            }
        }
    }
}
