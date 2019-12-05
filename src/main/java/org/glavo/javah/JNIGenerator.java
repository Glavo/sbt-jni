package org.glavo.javah;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.glavo.javah.Utils.*;

public class JNIGenerator {

    private final PrintWriter errorHandle;
    private final Iterable<SearchPath> searchPaths;
    private final Path outputDir;

    public JNIGenerator(Path outputDir) {
        this(outputDir, null, null);
    }

    public JNIGenerator(Path outputDir, Iterable<SearchPath> searchPaths) {
        this(outputDir, searchPaths, null);
    }

    public JNIGenerator(Path outputDir, Iterable<SearchPath> searchPaths, PrintWriter errorHandle) {
        Objects.requireNonNull(outputDir);

        if (searchPaths == null) {
            searchPaths = Collections.singleton(RuntimeSearchPath.INSTANCE);
        }
        if (errorHandle == null) {
            errorHandle = NOOP_WRITER;
        }

        this.errorHandle = errorHandle;
        this.searchPaths = searchPaths;
        this.outputDir = outputDir;
    }

    public void generate(ClassName name) {
        Objects.requireNonNull(name);
        if (Files.exists(outputDir) && !Files.isDirectory(outputDir)) {
            throw new IllegalArgumentException(outputDir + "is not a directory");
        }
        if (Files.notExists(outputDir)) {
            try {
                Files.createDirectories(outputDir);
            } catch (IOException e) {
                errorHandle.println("error: cannot create directory " + outputDir);
                e.printStackTrace(errorHandle);
                return;
            }
        }
        Path op = outputDir.resolve(name.mangledName() + ".h");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(op))) {
            generateTo(name, out);
        } catch (Exception ex) {
            errorHandle.println("error: cannot write to " + op);
            ex.printStackTrace(errorHandle);
            try {
                Files.deleteIfExists(op);
            } catch (IOException ignored) {
            }
        }
    }

    public void generateTo(ClassName name, Writer writer) throws IOException {
        Objects.requireNonNull(name);
        Objects.requireNonNull(writer);

        ClassMetaInfo meta = new ClassMetaInfo();
        {
            Path f = search(name);
            if (f == null) {
                errorHandle.println("Not found class " + name);
                return;
            }

            try (InputStream in = Files.newInputStream(f)) {
                ClassReader reader = new ClassReader(in);
                reader.accept(meta, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IOException e) {
                errorHandle.println("error: cannot open class file of " + name);
                e.printStackTrace(errorHandle);
                errorHandle.flush();
                throw e;
            }
        }

        PrintWriter out = writer instanceof PrintWriter ? (PrintWriter) writer : new PrintWriter(writer);
        out.println("/* DO NOT EDIT THIS FILE - it is machine generated */");
        out.println("#include <jni.h>");
        out.println("/* Header for class " + name.mangledName() + " */");
        out.println();
        out.println("#ifndef _Included_" + name.mangledName());
        out.println("#define _Included_" + name.mangledName());
        out.println("#ifdef __cplusplus");
        out.println("extern \"C\" {");
        out.println("#endif");

        for (Constant constant : meta.constants) {
            String cn = name.mangledName() + "_" + constant.mangledName();
            out.println("#undef " + cn);
            out.println("#define " + cn + " " + constant.valueToString());
        }

        for (NativeMethod method : meta.methods) {
            String ret = mapTypeToNative(method.type().getReturnType());
            List<String> args = new ArrayList<>();
            args.add("JNIEnv *");
            args.add(method.isStatic() ? "jclass" : "jobject");
            args.addAll(Arrays.asList(mapArgsTypeToNative(method.type())));

            String methodName =
                    "Java_" + name.mangledName() + "_" + (meta.isOverloadMethod(method) ? method.longMangledName() : method.mangledName());

            out.println("/*");
            out.println(" * Class:      " + name.mangledName());
            out.println(" * Method:     " + method.mangledName());
            out.println(" * Signature:  " + escape(method.type().toString()));
            out.println(" */");
            out.println("JNIEXPORT " + ret + " JNICALL " + methodName);
            out.println("  (" + String.join(", ", args) + ");");
            out.println();
        }

        out.println("#ifdef __cplusplus");
        out.println("}");
        out.println("#endif");
        out.println("#endif");

    }

    private Path search(ClassName name) {
        return SearchPath.searchFrom(searchPaths, name);
    }

    private String mapTypeToNative(Type type) {
        Objects.requireNonNull(type);
        String tpe = type.toString();
        if (tpe.startsWith("(")) {
            throw new IllegalArgumentException();
        }

        switch (tpe) {
            case "Z":
                return "jboolean";
            case "B":
                return "jbyte";
            case "C":
                return "jchar";
            case "S":
                return "jshort";
            case "I":
                return "jint";
            case "J":
                return "jlong";
            case "F":
                return "jfloat";
            case "D":
                return "jdouble";
            case "V":
                return "void";
            case "Ljava/lang/Class;":
                return "jclass";
            case "Ljava/lang/String;":
                return "jstring";
            case "Ljava/lang/Throwable;":
                return "jthrowable";
            case "[Z":
                return "jbooleanArray";
            case "[B":
                return "jbyteArray";
            case "[C":
                return "jcharArray";
            case "[S":
                return "jshortArray";
            case "[I":
                return "jintArray";
            case "[J":
                return "jlongArray";
            case "[F":
                return "jfloatArray";
            case "[D":
                return "jdoubleArray";
        }

        if (tpe.startsWith("[")) {
            return "jobjectArray";
        }

        if (tpe.startsWith("L") && tpe.endsWith(";")) {
            ClassName n = ClassName.of(tpe.substring(1, tpe.length() - 1).replace('/', '.'));
            if (isThrowable(n)) {
                return "jthrowable";
            } else {
                return "jobject";
            }
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }

    private String[] mapArgsTypeToNative(Type methodType) {
        Objects.requireNonNull(methodType);
        if (!METHOD_TYPE_PATTERN.matcher(methodType.toString()).matches()) {
            throw new IllegalArgumentException(methodType + " is not a method type");
        }
        Type[] args = methodType.getArgumentTypes();
        String[] ans = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            ans[i] = mapTypeToNative(args[i]);
        }
        return ans;
    }

    private boolean isThrowable(ClassName name) {
        if (name == null) {
            return false;
        }
        switch (name.className()) {
            case "java.lang.Throwable":
            case "java.lang.Error":
            case "java.lang.Exception":
                return true;
            case "java.lang.Object":
                return false;
        }

        try (InputStream in = Files.newInputStream(search(name))) {
            return isThrowable(superClassOf(new ClassReader(in)));
        } catch (Exception ignored) {
            errorHandle.println("warning: class " + name + " not found");
            return false;
        }
    }
}
