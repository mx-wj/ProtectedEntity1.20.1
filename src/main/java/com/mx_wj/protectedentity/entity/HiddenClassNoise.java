package com.mx_wj.protectedentity.entity;

import java.util.concurrent.ThreadLocalRandom;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class HiddenClassNoise implements Opcodes {
    private static final int METHOD_COUNT = 128;
    private static final String[] DESCRIPTORS = {
            "()V",
            "(IJFDLjava/lang/Object;[I[[Ljava/lang/String;)Ljava/lang/Object;",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;IJD)J",
            "([Ljava/lang/Object;ZBCS)D",
            "(J[BI)I",
            "(DFLjava/lang/String;)F",
            "([[ILjava/lang/Object;J)[I"
    };
    private static final String CALL_DESCRIPTOR = "(IJFDLjava/lang/Object;[I)Ljava/lang/Object;";

    private HiddenClassNoise() {
    }

    static byte[] addTo(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        // Copy existing methods and frames verbatim; only the unused methods need new frames.
        ClassWriter writer = new ClassWriter(reader, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String prefix = "$noise$" + Long.toUnsignedString(random.nextLong(), 36) + "$";
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public void visitEnd() {
                for (int i = 0; i < METHOD_COUNT; i++) {
                    addMethod(writer, prefix + i + "!", DESCRIPTORS[i % DESCRIPTORS.length], random);
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void addMethod(ClassWriter writer, String name, String descriptor, ThreadLocalRandom random) {
        MethodVisitor method = writer.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                name, descriptor, null, null);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        method.visitCode();
        method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        method.visitLabel(start);

        // Invalid linkage is deliberately deferred until execution. Invalid opcodes,
        // descriptors or stack types would reject the entire hidden class during define.
        Handle bootstrap = bogusBootstrap(name, random);
        method.visitLdcInsn(random.nextInt());
        method.visitLdcInsn(random.nextLong());
        method.visitLdcInsn(random.nextFloat());
        method.visitLdcInsn(random.nextDouble());
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ACONST_NULL);
        method.visitInvokeDynamicInsn(name, CALL_DESCRIPTOR, bootstrap,
                random.nextInt(), random.nextLong(), random.nextFloat(), random.nextDouble(), name,
                Type.getType("Ljava/lang/Object;"), Type.getMethodType(descriptor),
                new Handle(H_INVOKESTATIC, "java/lang/System", name, "(JLjava/lang/Object;I)D", false),
                new ConstantDynamic(name + "$arg", "Ljava/lang/Object;", bootstrap, random.nextInt()));
        method.visitInsn(POP);
        method.visitLdcInsn(new ConstantDynamic(name + "$constant", "J", bootstrap,
                name, random.nextDouble(), Type.getMethodType(CALL_DESCRIPTOR)));
        method.visitInsn(POP2);

        addBrokenReferences(method, name, random);
        addArraysAndArithmetic(method, random);
        addSwitch(method, name, random);

        method.visitInsn(ACONST_NULL);
        method.visitInsn(MONITORENTER);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(MONITOREXIT);
        method.visitLabel(end);
        addReturn(method, Type.getReturnType(descriptor));
        method.visitLabel(handler);
        method.visitFrame(F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
        method.visitInsn(ATHROW);
        method.visitMaxs(16, (Type.getArgumentsAndReturnSizes(descriptor) >> 2) - 1);
        method.visitEnd();
    }

    private static Handle bogusBootstrap(String name, ThreadLocalRandom random) {
        return switch (random.nextInt(4)) {
            case 0 -> new Handle(H_INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);
            case 1 -> new Handle(H_INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            case 2 -> new Handle(H_NEWINVOKESPECIAL, "java/lang/String", "<init>", "()V", false);
            default -> new Handle(H_INVOKESTATIC, "java/lang/System", name, "(FD)Ljava/lang/Object;", false);
        };
    }

    private static void addBrokenReferences(MethodVisitor method, String name, ThreadLocalRandom random) {
        method.visitFieldInsn(GETSTATIC, "java/lang/System", name, "J");
        method.visitInsn(POP2);
        method.visitLdcInsn(random.nextLong());
        method.visitFieldInsn(PUTSTATIC, "java/lang/System", name, "J");
        method.visitInsn(ACONST_NULL);
        method.visitFieldInsn(GETFIELD, "java/lang/Object", name, "Ljava/lang/Object;");
        method.visitInsn(POP);
        method.visitInsn(ACONST_NULL);
        method.visitLdcInsn(random.nextDouble());
        method.visitFieldInsn(PUTFIELD, "java/lang/Object", name, "D");

        method.visitLdcInsn(random.nextLong());
        method.visitInsn(ACONST_NULL);
        method.visitLdcInsn(random.nextInt());
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", name, "(JLjava/lang/Object;I)Ljava/lang/Object;", false);
        method.visitInsn(POP);
        method.visitInsn(ACONST_NULL);
        method.visitLdcInsn(random.nextFloat());
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", name, "(F)D", false);
        method.visitInsn(POP2);
        method.visitInsn(ACONST_NULL);
        method.visitLdcInsn(random.nextInt());
        method.visitMethodInsn(INVOKEINTERFACE, "java/lang/Runnable", name, "(I)J", true);
        method.visitInsn(POP2);
        method.visitTypeInsn(NEW, "java/lang/String");
        method.visitInsn(DUP);
        method.visitLdcInsn(random.nextInt());
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "(I)V", false);
        method.visitInsn(POP);
    }

    private static void addArraysAndArithmetic(MethodVisitor method, ThreadLocalRandom random) {
        method.visitInsn(ICONST_M1);
        method.visitIntInsn(NEWARRAY, T_INT);
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitLdcInsn(random.nextInt());
        method.visitInsn(IASTORE);
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitInsn(IALOAD);
        method.visitInsn(POP);
        method.visitInsn(ARRAYLENGTH);
        method.visitInsn(POP);

        method.visitInsn(ICONST_M1);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(AASTORE);
        method.visitInsn(DUP);
        method.visitInsn(ICONST_0);
        method.visitInsn(AALOAD);
        method.visitTypeInsn(CHECKCAST, "java/lang/String");
        method.visitTypeInsn(INSTANCEOF, "java/lang/Runnable");
        method.visitInsn(POP);
        method.visitInsn(ARRAYLENGTH);
        method.visitInsn(POP);
        method.visitInsn(ICONST_M1);
        method.visitInsn(ICONST_M1);
        method.visitMultiANewArrayInsn("[[Ljava/lang/String;", 2);
        method.visitInsn(POP);

        method.visitLdcInsn(random.nextLong());
        method.visitInsn(DUP2);
        method.visitInsn(LXOR);
        method.visitLdcInsn(random.nextInt());
        method.visitInsn(LUSHR);
        method.visitInsn(L2D);
        method.visitLdcInsn(random.nextDouble());
        method.visitInsn(DREM);
        method.visitInsn(D2F);
        method.visitLdcInsn(random.nextFloat());
        method.visitInsn(FDIV);
        method.visitInsn(F2I);
        method.visitInsn(ICONST_0);
        method.visitInsn(IDIV);
        method.visitInsn(POP);
    }

    private static void addSwitch(MethodVisitor method, String name, ThreadLocalRandom random) {
        Label first = new Label();
        Label second = new Label();
        Label fallback = new Label();
        Label done = new Label();
        method.visitLdcInsn(random.nextInt());
        if (random.nextBoolean()) {
            method.visitLookupSwitchInsn(fallback, new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE},
                    new Label[] {first, second});
        } else {
            method.visitTableSwitchInsn(-1, 0, fallback, first, second);
        }
        method.visitLabel(first);
        method.visitFrame(F_SAME, 0, null, 0, null);
        method.visitLdcInsn(Type.getMethodType(CALL_DESCRIPTOR));
        method.visitInsn(POP);
        method.visitJumpInsn(GOTO, done);
        method.visitLabel(second);
        method.visitFrame(F_SAME, 0, null, 0, null);
        method.visitLdcInsn(new Handle(H_GETSTATIC, "java/lang/System", name, "J", false));
        method.visitInsn(POP);
        method.visitJumpInsn(GOTO, done);
        method.visitLabel(fallback);
        method.visitFrame(F_SAME, 0, null, 0, null);
        method.visitLdcInsn(new Handle(H_INVOKEINTERFACE, "java/lang/Runnable", name, "(D)I", true));
        method.visitInsn(POP);
        method.visitLabel(done);
        method.visitFrame(F_SAME, 0, null, 0, null);
    }

    private static void addReturn(MethodVisitor method, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> method.visitInsn(RETURN);
            case Type.INT -> {
                method.visitInsn(ICONST_0);
                method.visitInsn(IRETURN);
            }
            case Type.LONG -> {
                method.visitInsn(LCONST_0);
                method.visitInsn(LRETURN);
            }
            case Type.FLOAT -> {
                method.visitInsn(FCONST_0);
                method.visitInsn(FRETURN);
            }
            case Type.DOUBLE -> {
                method.visitInsn(DCONST_0);
                method.visitInsn(DRETURN);
            }
            default -> {
                method.visitInsn(ACONST_NULL);
                method.visitInsn(ARETURN);
            }
        }
    }
}
