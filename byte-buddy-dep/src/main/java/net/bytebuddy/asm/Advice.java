package net.bytebuddy.asm;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.bytecode.StackSize;
import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.annotation.*;
import java.util.Map;

public class Advice implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {

    private final Dispatcher.Resolved.ForMethodEnter methodEnter;

    private final Dispatcher.Resolved.ForMethodExit methodExit;

    private final byte[] binaryRepresentation;

    protected Advice(Dispatcher.Resolved.ForMethodEnter methodEnter, Dispatcher.Resolved.ForMethodExit methodExit, byte[] binaryRepresentation) {
        this.methodEnter = methodEnter;
        this.methodExit = methodExit;
        this.binaryRepresentation = binaryRepresentation;
    }

    public static AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper to(Class<?> type) {
        return to(type, ClassFileLocator.ForClassLoader.of(type.getClassLoader()));
    }

    public static AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper to(Class<?> type, ClassFileLocator classFileLocator) {
        return to(new TypeDescription.ForLoadedType(type), classFileLocator);
    }

    public static AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper to(TypeDescription typeDescription, ClassFileLocator classFileLocator) {
        try {
            Dispatcher methodEnter = Dispatcher.Inactive.INSTANCE, methodExit = Dispatcher.Inactive.INSTANCE;
            for (MethodDescription.InDefinedShape methodDescription : typeDescription.getDeclaredMethods()) {
                methodEnter = resolve(OnMethodEnter.class, methodEnter, methodDescription);
                methodExit = resolve(OnMethodExit.class, methodExit, methodDescription);
            }
            if (!methodEnter.isAlive() && !methodExit.isAlive()) {
                throw new IllegalArgumentException("No advice defined by " + typeDescription);
            }
            Dispatcher.Resolved.ForMethodEnter resolved = methodEnter.asMethodEnter();
            return new Advice(methodEnter.asMethodEnter(), methodExit.asMethodExitTo(resolved), classFileLocator.locate(typeDescription.getName()).resolve());
        } catch (IOException exception) {
            throw new IllegalStateException("Error reading class file of " + typeDescription, exception);
        }
    }

    private static Dispatcher resolve(Class<? extends Annotation> annotation, Dispatcher dispatcher, MethodDescription.InDefinedShape methodDescription) {
        if (methodDescription.getDeclaredAnnotations().isAnnotationPresent(annotation)) {
            if (dispatcher.isAlive()) {
                throw new IllegalArgumentException("Duplicate advice for " + dispatcher + " and " + methodDescription);
            } else if (!methodDescription.isStatic()) {
                throw new IllegalArgumentException("Advice for " + methodDescription + " is not static");
            }
            return new Dispatcher.ForMethod(methodDescription);
        } else {
            return dispatcher;
        }
    }

    @Override
    public MethodVisitor wrap(TypeDescription instrumentedType, MethodDescription.InDefinedShape methodDescription, MethodVisitor methodVisitor) {
        return new AsmAdvice(methodVisitor, methodDescription);
    }

    protected class AsmAdvice extends MethodVisitor {

        private final MethodDescription.InDefinedShape instrumentedMethod;

        private final ClassReader classReader;

        protected AsmAdvice(MethodVisitor methodVisitor, MethodDescription.InDefinedShape instrumentedMethod) {
            super(Opcodes.ASM5, methodVisitor);
            this.instrumentedMethod = instrumentedMethod;
            classReader = new ClassReader(binaryRepresentation);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            onMethodEntry();
        }

        protected void onMethodEntry() {
            classReader.accept(new CodeCopier(methodEnter), ClassReader.SKIP_DEBUG);
        }

        @Override
        public void visitVarInsn(int opcode, int offset) {
            super.visitVarInsn(opcode, offset < instrumentedMethod.getStackSize()
                    ? offset
                    : offset + methodEnter.getAdditionalSize().getSize());
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                case Opcodes.RETURN:
                    onMethodExit();
                    break;
                case Opcodes.IRETURN:
                    onMethodExit(Opcodes.DUP, Opcodes.ISTORE);
                    break;
                case Opcodes.FRETURN:
                    onMethodExit(Opcodes.DUP, Opcodes.FSTORE);
                    break;
                case Opcodes.DRETURN:
                    onMethodExit(Opcodes.DUP2, Opcodes.DSTORE);
                    break;
                case Opcodes.LRETURN:
                    onMethodExit(Opcodes.DUP2, Opcodes.LSTORE);
                    break;
                case Opcodes.ATHROW:
                    if (methodExit.isSkipException()) {
                        break;
                    }
                    // TODO: Set dummy value for return value!
                    break;
                case Opcodes.ARETURN:
                    onMethodExit(Opcodes.DUP, Opcodes.ASTORE);
                    break;
            }
            super.visitInsn(opcode);
        }

        private void onMethodExit(int duplication, int store) {
            super.visitInsn(duplication);
            super.visitVarInsn(store, instrumentedMethod.getStackSize() + methodEnter.getAdditionalSize().getSize());
            onMethodExit();
        }

        protected void onMethodExit() {
            classReader.accept(new CodeCopier(methodExit), ClassReader.SKIP_DEBUG);
        }

        protected class CodeCopier extends ClassVisitor {

            private final Dispatcher.Resolved dispatcher;

            protected CodeCopier(Dispatcher.Resolved dispatcher) {
                super(Opcodes.ASM5);
                this.dispatcher = dispatcher;
            }

            @Override
            public MethodVisitor visitMethod(int modifiers, String internalName, String descriptor, String signature, String[] exception) {
                return dispatcher.apply(internalName, descriptor, AsmAdvice.this.mv, instrumentedMethod);
            }
        }
    }

    protected interface Dispatcher {

        MethodVisitor IGNORE_METHOD = null;

        boolean isAlive();

        Resolved.ForMethodEnter asMethodEnter();

        Resolved.ForMethodExit asMethodExitTo(Resolved.ForMethodEnter dispatcher);

        interface Resolved {

            MethodVisitor apply(String internalName, String descriptor, MethodVisitor methodVisitor, MethodDescription.InDefinedShape instrumentedMethod);

            interface ForMethodEnter extends Resolved {

                StackSize getAdditionalSize();
            }

            interface ForMethodExit extends Resolved {

                boolean isSkipException();
            }
        }

        enum Inactive implements Dispatcher, Resolved.ForMethodEnter, Resolved.ForMethodExit {

            INSTANCE;

            @Override
            public boolean isAlive() {
                return false;
            }

            @Override
            public boolean isSkipException() {
                return true;
            }

            @Override
            public StackSize getAdditionalSize() {
                return StackSize.SINGLE;
            }

            @Override
            public Resolved.ForMethodEnter asMethodEnter() {
                return this;
            }

            @Override
            public Resolved.ForMethodExit asMethodExitTo(Resolved.ForMethodEnter dispatcher) {
                return this;
            }

            @Override
            public MethodVisitor apply(String internalName, String descriptor, MethodVisitor methodVisitor, MethodDescription.InDefinedShape instrumentedMethod) {
                return IGNORE_METHOD;
            }
        }

        class ForMethod implements Dispatcher {

            protected final MethodDescription.InDefinedShape inlinedMethod;

            protected ForMethod(MethodDescription.InDefinedShape inlinedMethod) {
                this.inlinedMethod = inlinedMethod;
            }

            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public Dispatcher.Resolved.ForMethodEnter asMethodEnter() {
                return new ForMethodEnter(inlinedMethod);
            }

            @Override
            public Dispatcher.Resolved.ForMethodExit asMethodExitTo(Dispatcher.Resolved.ForMethodEnter dispatcher) {
                return new ForMethodExit(inlinedMethod, dispatcher.getAdditionalSize());
            }

            protected abstract static class Resolved implements Dispatcher.Resolved {

                protected final MethodDescription inlinedMethod;

                protected Resolved(MethodDescription inlinedMethod) {
                    this.inlinedMethod = inlinedMethod;
                }

                @Override
                public MethodVisitor apply(String internalName, String descriptor, MethodVisitor methodVisitor, MethodDescription.InDefinedShape instrumentedMethod) {
                    return inlinedMethod.getInternalName().equals(internalName) && inlinedMethod.getDescriptor().equals(descriptor)
                            ? inline(methodVisitor, instrumentedMethod)
                            : IGNORE_METHOD;
                }

                protected abstract MethodVisitor inline(MethodVisitor methodVisitor, MethodDescription.InDefinedShape instrumentedMethod);
            }

            protected static class ForMethodEnter extends Resolved implements Dispatcher.Resolved.ForMethodEnter {

                protected ForMethodEnter(MethodDescription inlinedMethod) {
                    super(inlinedMethod);
                }

                @Override
                public StackSize getAdditionalSize() {
                    return inlinedMethod.getReturnType().getStackSize();
                }

                @Override
                protected MethodVisitor inline(MethodVisitor methodVisitor, MethodDescription.InDefinedShape instrumentedMethod) {
                    return new CodeTranslationVisitor.ReturnValueRetaining(methodVisitor, instrumentedMethod, inlinedMethod, offsetTranslations);
                }
            }

            protected static class ForMethodExit extends Resolved implements Dispatcher.Resolved.ForMethodExit {

                private final StackSize additionalSize;

                protected ForMethodExit(MethodDescription.InDefinedShape inlinedMethod, StackSize additionalSize) {
                    super(inlinedMethod);
                    this.additionalSize = additionalSize;
                }

                @Override
                public boolean isSkipException() {
                    return !inlinedMethod.getDeclaredAnnotations().ofType(OnMethodExit.class).loadSilent().onException();
                }

                @Override
                protected MethodVisitor inline(MethodVisitor methodVisitor, MethodDescription.InDefinedShape instrumentedMethod) {
                    return new CodeTranslationVisitor.ReturnValueDiscarding(methodVisitor, instrumentedMethod, inlinedMethod, offsetTranslations);
                }
            }

            protected abstract static class CodeTranslationVisitor extends MethodVisitor {

                private static final AnnotationVisitor IGNORE_ANNOTATION = null;

                protected final MethodDescription.InDefinedShape instrumentedMethod;

                protected final MethodDescription.InDefinedShape inlinedMethod;

                private final Map<Integer, Integer> offsetTranslations;

                protected final Label endOfMethod;

                protected CodeTranslationVisitor(MethodVisitor methodVisitor,
                                                 MethodDescription.InDefinedShape instrumentedMethod,
                                                 MethodDescription.InDefinedShape inlinedMethod,
                                                 Map<Integer, Integer> offsetTranslations) {
                    super(Opcodes.ASM5, methodVisitor);
                    this.instrumentedMethod = instrumentedMethod;
                    this.inlinedMethod = inlinedMethod;
                    this.offsetTranslations = offsetTranslations;
                    endOfMethod = new Label();
                }

                @Override
                public void visitParameter(String name, int modifiers) {
                    /* do nothing */
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return IGNORE_ANNOTATION;
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return IGNORE_ANNOTATION;
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeReference, TypePath typePath, String descriptor, boolean visible) {
                    return IGNORE_ANNOTATION;
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int index, String descriptor, boolean visible) {
                    return IGNORE_ANNOTATION;
                }

                @Override
                public void visitAttribute(Attribute attr) {
                    /* do nothing */
                }

                @Override
                public void visitCode() {
                    /* do nothing */
                }

                @Override
                public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
                    /* do nothing */
                }

                @Override
                public void visitLineNumber(int line, Label start) {
                    /* do nothing */
                }

                @Override
                public void visitEnd() {
                    mv.visitLabel(endOfMethod);
                }

                @Override
                public void visitMaxs(int maxStack, int maxLocals) {
                    /* do nothing */
                }

                @Override
                public void visitVarInsn(int opcode, int offset) {
                    Integer translated = offsetTranslations.get(offset);
                    mv.visitVarInsn(opcode, translated == null
                            ? adjust(offset + instrumentedMethod.getStackSize() - inlinedMethod.getStackSize())
                            : translated);
                }

                protected abstract int adjust(int offset);

                @Override
                public abstract void visitInsn(int opcode);

                protected static class ReturnValueRetaining extends CodeTranslationVisitor {

                    protected ReturnValueRetaining(MethodVisitor methodVisitor,
                                                   MethodDescription.InDefinedShape instrumentedMethod,
                                                   MethodDescription.InDefinedShape inlinedMethod,
                                                   Map<Integer, Integer> offsetTranslations) {
                        super(methodVisitor, instrumentedMethod, inlinedMethod, offsetTranslations);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        switch (opcode) {
                            case Opcodes.RETURN:
                                break;
                            case Opcodes.IRETURN:
                                mv.visitVarInsn(Opcodes.ISTORE, instrumentedMethod.getStackSize());
                                break;
                            case Opcodes.LRETURN:
                                mv.visitVarInsn(Opcodes.LSTORE, instrumentedMethod.getStackSize());
                                break;
                            case Opcodes.ARETURN:
                                mv.visitVarInsn(Opcodes.ASTORE, instrumentedMethod.getStackSize());
                                break;
                            case Opcodes.FRETURN:
                                mv.visitVarInsn(Opcodes.FSTORE, instrumentedMethod.getStackSize());
                                break;
                            case Opcodes.DRETURN:
                                mv.visitVarInsn(Opcodes.DSTORE, instrumentedMethod.getStackSize());
                                break;
                            default:
                                mv.visitInsn(opcode);
                                return;
                        }
                        mv.visitJumpInsn(Opcodes.GOTO, endOfMethod);
                    }

                    @Override
                    protected int adjust(int offset) {
                        return offset;
                    }
                }

                protected static class ReturnValueDiscarding extends CodeTranslationVisitor {

                    private final StackSize additionalSize;

                    protected ReturnValueDiscarding(MethodVisitor methodVisitor,
                                                    MethodDescription.InDefinedShape instrumentedMethod,
                                                    MethodDescription.InDefinedShape inlinedMethod,
                                                    Map<Integer, Integer> offsetTranslations,
                                                    StackSize additionalSize) {
                        super(methodVisitor, instrumentedMethod, inlinedMethod, offsetTranslations);
                        this.additionalSize = additionalSize;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        switch (opcode) {
                            case Opcodes.RETURN:
                                break;
                            case Opcodes.IRETURN:
                            case Opcodes.ARETURN:
                            case Opcodes.FRETURN:
                                mv.visitInsn(Opcodes.POP);
                                break;
                            case Opcodes.LRETURN:
                            case Opcodes.DRETURN:
                                mv.visitInsn(Opcodes.POP2);
                                break;
                            default:
                                mv.visitInsn(opcode);
                                return;
                        }
                        mv.visitJumpInsn(Opcodes.GOTO, endOfMethod);
                    }

                    @Override
                    protected int adjust(int offset) {
                        return offset + instrumentedMethod.getReturnType().getStackSize().getSize() + additionalSize.getSize();
                    }
                }
            }
        }
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface OnMethodEnter {
        /* empty */
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface OnMethodExit {

        boolean onException() default true;
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Argument {

        int value();
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface This {
        /* empty */
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Enter {
        /* empty */
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Return {
        /* empty */
    }
}
