package erjang.codegen;

import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.IF_ACMPNE;
import static org.objectweb.asm.Opcodes.INSTANCEOF;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import erjang.BIF;
import erjang.EAtom;
import erjang.EFun;
import erjang.EFunHandler;
import erjang.EObject;
import erjang.EOutputStream;
import erjang.EProc;
import erjang.ERT;
import erjang.beam.CompilerVisitor;
import erjang.beam.EUtil;
import kilim.Pausable;
import kilim.analysis.ClassInfo;
import kilim.analysis.ClassWeaver;
import kilim.analysis.KilimContext;

public class EFunCG {

    /*==================== Code generation: ==================*/

    private static final Type EFUN_TYPE = Type.getType(EFun.class);
    private static final String EFUN_NAME = EFUN_TYPE.getInternalName();
    private static final Type EATOM_TYPE = Type.getType(EAtom.class);
    private static final Type EFUNHANDLER_TYPE = Type
            .getType(EFunHandler.class);
    private static final Type EOBJECT_TYPE = Type.getType(EObject.class);
    private static final Type STRING_TYPE = Type.getType(String.class);
    private static final Type EOBJECT_ARR_TYPE = Type.getType(EObject[].class);
    private static final Type EPROC_TYPE = Type.getType(EProc.class);
    static final String GO_DESC = "(" + EPROC_TYPE.getDescriptor() + ")"
            + EOBJECT_TYPE.getDescriptor();
    private static final String EPROC_NAME = EPROC_TYPE.getInternalName();
    private static final String EOBJECT_DESC = EOBJECT_TYPE.getDescriptor();
    private static final String EATOM_DESC = EATOM_TYPE.getDescriptor();
    static final String[] PAUSABLE_EX = new String[] { Type.getType(Pausable.class).getInternalName() };


    private static final HashMap<Method, EFun> method_fun_map = new HashMap<Method, EFun>();

    /* TODO: Using a central database like this to avoid duplicate
      * definitions is a hack, perpetrated for the sake of moving
      * interpreter progress along, and may interfere with module
      * reloading.
      *
      * Treating native functions differently in EModule loading might
      * be a better solution.
      */
    public static synchronized EFun funForMethod(Method method, String module) {
        EFun fun = method_fun_map.get(method);
        if (fun==null) {
            method_fun_map.put(method, fun = createFunForMethod(method, module));
        }
        return fun;
    }

    private static EFun createFunForMethod(Method method, String module) {
        assert (Modifier.isStatic(method.getModifiers()));
        assert (!Modifier.isPrivate(method.getModifiers()));

        Class<?>[] parameterTypes = method.getParameterTypes();
        int ary = parameterTypes.length;
        boolean proc = (ary > 0 && parameterTypes[0].equals(EProc.class));
        if (proc)
            ary -= 1;
        String fname = erlangNameOfMethod(method);
        String mname = EUtil.getJavaName(EAtom.intern(fname), ary);
        boolean is_guard = isGuardBifMethod(method);

        Class<?> declaringClass = method.getDeclaringClass();
        Type type = Type.getType(declaringClass);
        byte[] data = CompilerVisitor.make_invoker(module, fname, type, mname, method
                .getName(), ary, proc, true, is_guard, null, Type.getType(method.getReturnType()), true, true);

        ClassLoader cl = declaringClass.getClassLoader();

        // make sure we have its superclass loaded
        get_fun_class(ary);

        data = weave(data);

        String clname = type.getClassName() + "$FN_" + mname;
        clname = clname.replace('/', '.');
        Class<? extends EFun> res_class = ERT.defineClass(cl, clname, data);

        try {
            return res_class.newInstance();
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    private static String erlangNameOfMethod(Method method) {
        BIF bif_ann = method.getAnnotation(BIF.class);
        if (bif_ann != null) {
            String bif_name = bif_ann.name().equals("__SELFNAME__") ? method.getName() : bif_ann.name();
            if (bif_ann.type() == BIF.Type.GUARD) return bif_name + "\1";
            else return bif_name;
        } else {
            return method.getName();
        }
    }

    private static boolean isGuardBifMethod(Method method) {
        BIF bif_ann = method.getAnnotation(BIF.class);
        return (bif_ann != null &&
                bif_ann.type() == BIF.Type.GUARD);
    }

    /*==================== Code generation of EFun{arity}: ==================*/
    @SuppressWarnings("unchecked")
    public static Class<? extends EFun> get_fun_class(int arity) {

        String className = EFUN_TYPE.getClassName() + arity;
        try {
            return (Class<? extends EFun>) Class.forName(className, true, EFun.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            // that's what we'll do here...
        }

        byte[] data = gen_fun_class_data(arity);

        data = weave(data);

        String self_type = EFUN_TYPE.getInternalName() + arity;
        self_type = self_type.replace('/', '.');
        synchronized (EFunCG.class) {
            try {
                // Was it defined in the meantime?
                return (Class<? extends EFun>) Class.forName(className, true, EFun.class.getClassLoader());
            } catch (ClassNotFoundException ex) {
                return ERT.defineClass(EFun.class.getClassLoader(), self_type, data);
            }
        }
    }

    /**
     * Generates the base for all function objects of arity <em>arity</em>.
     * The template is as follows:
     * public class EFun{arity} {
     *   public &lt;init&gt;() {super();}
     *   @Override public int arity() {return the_arity;}
     *   @Override EObject invoke(EProc proc, EObject[] args) {
     *     return invoke(proc, args[0], ..., args[the_arity-1]);
    }
     *   public EObject invoke_tail(proc, arg1, ..., argN) {
     *     proc.arg0 = args[0]; ... proc.argN_minus_1 = args[N-1];
     *     return TAIL_MARKER;
     *   }
     * }
     */
    static byte[] gen_fun_class_data(int arity) {

        String self_type = EFUN_TYPE.getInternalName() + arity;

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                self_type, null, EFUN_TYPE.getInternalName(), null);

        make_invoke_method(cw, self_type, arity);

        CompilerVisitor.make_invoketail_method(cw, self_type, arity, 0);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "arity", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(new Integer(arity));
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "("
                + EPROC_TYPE.getDescriptor() + EOBJECT_ARR_TYPE.getDescriptor()
                + ")" + EOBJECT_TYPE.getDescriptor(), null, PAUSABLE_EX);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0); // load this
        mv.visitVarInsn(Opcodes.ALOAD, 1); // load proc
        for (int i = 0; i < arity; i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            push_int(mv, i);
            mv.visitInsn(Opcodes.AALOAD);
        }

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self_type, "invoke", EUtil
                .getSignature(arity, true));

        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(arity + 2, arity + 2);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "()V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, EFUN_TYPE.getInternalName(),
                "<init>", "()V");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        make_cast_method(cw, arity);

        cw.visitEnd();

        byte[] data = cw.toByteArray();
        return data;
    }

    static Map<String, Constructor<? extends EFun>> handlers = new HashMap<String, Constructor<? extends EFun>>();
    static final Pattern JAVA_ID = Pattern.compile("([a-z]|[A-Z]|$|_|[0-9])*"); // valid java identifier

    public static EFun get_fun_with_handler(String module0, String function0, int arity, EFunHandler handler, ClassLoader loader) {

        String signature = "EFunHandler" + arity;
        Constructor<? extends EFun> h = handlers.get(signature);

        if (h == null) {

            get_fun_class(arity);

            //String safe_module =  JAVA_ID.matcher(module).matches() ? module : make_valid_java_id(module);
            //String safe_function = JAVA_ID.matcher(function).matches() ? function : make_valid_java_id(function);
            StringBuffer sb = new StringBuffer();
            String self_type = sb.append(EFUN_TYPE.getInternalName())
                    // .append(safe_module).append(safe_function)
                    .append("Handler").append(arity).toString();

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
            String super_class_name = EFUN_TYPE.getInternalName() + arity;
            cw.visit(Opcodes.V1_4, ACC_PUBLIC, self_type, null,
                    super_class_name, null);

            // create handler field
            cw.visitField(ACC_PRIVATE, "handler", EFUNHANDLER_TYPE.getDescriptor(), null, null)
                    .visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "module_name", EATOM_DESC, null, null)
                    .visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "function_name", EATOM_DESC, null, null)
                    .visitEnd();


            // make constructor
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "("
                    + EFUNHANDLER_TYPE.getDescriptor()
                    + EATOM_DESC
                    + EATOM_DESC
                    + ")V", null, null);
            mv.visitCode();

            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, super_class_name, "<init>",
                    "()V");

            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(PUTFIELD, self_type, "handler", EFUNHANDLER_TYPE
                    .getDescriptor());
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitFieldInsn(PUTFIELD, self_type, "module_name", EATOM_DESC);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitFieldInsn(PUTFIELD, self_type, "function_name", EATOM_DESC);

            mv.visitInsn(RETURN);
            mv.visitMaxs(4, 4);
            mv.visitEnd();

            /** forward toString to handler */
            mv = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0); // load self
            mv.visitFieldInsn(GETFIELD, self_type, "handler", EFUNHANDLER_TYPE.getDescriptor());
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;");
            mv.visitInsn(ARETURN);
            mv.visitMaxs(3, 3);
            mv.visitEnd();

            // make invoke_tail method
            //CompilerVisitor.make_invoketail_method(cw, self_type, arity, 0);
            make_invoke_method(cw, self_type, arity);
            make_go_method(cw, self_type, arity);
            make_encode_method(cw, self_type, arity);

            cw.visitEnd();
            byte[] data = cw.toByteArray();

            data = weave(data);

            Class<? extends EFun> clazz = ERT.defineClass(loader, self_type.replace('/', '.'), data);

            try {
                h = clazz.getConstructor(EFunHandler.class, EAtom.class, EAtom.class);
            } catch (Exception e) {
                throw new Error(e);
            }

            handlers.put(signature, h);
        }

        try {
            return h.newInstance(handler, EAtom.intern(module0), EAtom.intern(function0));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    private static void make_cast_method(ClassWriter cw, int n) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC, "cast",
                "(" + EOBJECT_DESC + ")L" + EFUN_NAME + n + ";",
                null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, EFUN_NAME+n);

        Label fail = new Label();

        mv.visitJumpInsn(Opcodes.IFEQ, fail);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, EFUN_NAME+n);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(fail);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }


    private static void make_go_method(ClassWriter cw, String self_type,
                                       int arity) {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "go", GO_DESC, null, PAUSABLE_EX);
        mv.visitCode();

        for (int i = 0; i < arity; i++) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, EPROC_NAME, "arg" + i, EOBJECT_DESC);
            mv.visitVarInsn(ASTORE, i + 2);
        }
        for (int i = 0; i < arity; i++) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ACONST_NULL);
            mv.visitFieldInsn(PUTFIELD, EPROC_NAME, "arg" + i, EOBJECT_DESC);
        }

        // load handler
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, self_type, "handler", EFUNHANDLER_TYPE
                .getDescriptor());

        // load proc
        mv.visitVarInsn(ALOAD, 1);

        // create array
        mv.visitLdcInsn(new Integer(arity));
        mv.visitTypeInsn(ANEWARRAY, EOBJECT_TYPE.getInternalName());

        for (int i = 0; i < arity; i++) {
            mv.visitInsn(DUP);
            mv.visitLdcInsn(new Integer(i));
            mv.visitVarInsn(ALOAD, i + 2);
            mv.visitInsn(AASTORE);
        }

        mv.visitMethodInsn(INVOKEINTERFACE, EFUNHANDLER_TYPE.getInternalName(), "invoke",
                "(" + EPROC_TYPE.getDescriptor() + "[" + EOBJECT_DESC + ")" + EOBJECT_DESC);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(arity + 2, arity + 2);
        mv.visitEnd();

        cw.visitEnd();
    }

    private static void make_invoke_method(ClassWriter cw, String self_type,
                                           int arity) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "invoke", EUtil
                .getSignature(arity, true), null, PAUSABLE_EX);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        for (int i = 0; i < arity; i++) {
            mv.visitVarInsn(ALOAD, i + 2);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, self_type, "invoke_tail", EUtil
                .getSignature(arity, true));
        mv.visitVarInsn(ASTORE, arity + 2);

        Label done = new Label();
        Label loop = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(ALOAD, arity + 2);
        if (EProc.TAIL_MARKER == null) {
            mv.visitJumpInsn(IFNONNULL, done);
        } else {
            mv.visitFieldInsn(GETSTATIC, EPROC_TYPE.getInternalName(),
                    "TAIL_MARKER", EOBJECT_TYPE.getDescriptor());
            mv.visitJumpInsn(IF_ACMPNE, done);
        }

        // load proc
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETFIELD, EPROC_TYPE.getInternalName(), "tail",
                EFUN_TYPE.getDescriptor());
        mv.visitVarInsn(ALOAD, 1);

        mv.visitMethodInsn(INVOKEVIRTUAL, EFUN_TYPE.getInternalName(), "go",
                GO_DESC);
        mv.visitVarInsn(ASTORE, arity + 2);

        mv.visitJumpInsn(GOTO, loop);

        mv.visitLabel(done);
        mv.visitVarInsn(ALOAD, arity + 2);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(arity + 2, arity + 2);
        mv.visitEnd();
    }

    static void make_encode_method(ClassWriter cw, String className, int arity) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "encode", "("+ Type.getDescriptor(EOutputStream.class) +")V", null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "module_name", EATOM_DESC);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "function_name", EATOM_DESC);
        mv.visitLdcInsn(new Integer(arity));

        mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(EOutputStream.class), "write_external_fun",
                "("+EATOM_DESC+EATOM_DESC+"I)V");

        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
    }

    private static String make_valid_java_id(CharSequence seq) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < seq.length(); i++) {
            char ch = seq.charAt(i);
            if ((ch >= 'a' && ch <= 'z') ||
                    (ch >= 'A' && ch <= 'Z') ||
                    (ch >= '0' && ch <= '9') ||
                    ch == '_' || ch == '$') {
                sb.append(ch);
            } else {
                sb.append('_').append('x');
                if (ch < 0x10) sb.append('0');
                sb.append(Integer.toHexString(ch).toUpperCase());
            }
        }

        return sb.toString();
    }
    /*^^^^^^^^^^^^^^^^^^^^ Code generation of EFun{arity}  ^^^^^^^^^^^^^^^^^^*/

    /*==================== Code generation of EFun{arity}Exported: ==========*/
    @SuppressWarnings("unchecked")
    public static Class<? extends EFun> get_exported_fun_class(int arity) {

        String className = EFUN_TYPE.getClassName() + arity + "Exported";
        try {
            return (Class<? extends EFun>) Class.forName(className, true, EFun.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            // that's what we'll do here...
        }

        byte[] data = get_exported_fun_class_data(arity);

        data = weave(data);

        return ERT.defineClass(EFun.class.getClassLoader(), className, data);
    }

    static byte[] get_exported_fun_class_data(int arity) {
        /* Code template:
           * public abstract class EFun{arity}Exported extends EFun{arity} {
           *   protected final EAtom module_name, function_name;
           *   protected EFun{arity}Exported(String m, String f) {
           *     module_name   = EAtom.intern(m);
           *     function_name = EAtom.intern(f);
           *   }
           * }
           */

        ensure(arity); // Ensure presence of superclass.
        String super_type = EFUN_TYPE.getInternalName() + arity;
        String self_type = super_type + "Exported";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                self_type, null, super_type, null);

        cw.visitField(ACC_PROTECTED | ACC_FINAL, "module_name",
                EATOM_TYPE.getDescriptor(), null, null)
                .visitEnd();

        cw.visitField(ACC_PROTECTED | ACC_FINAL, "function_name",
                EATOM_TYPE.getDescriptor(), null, null)
                .visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", null, null);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, super_type, "<init>", "()V");

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EATOM_TYPE.getInternalName(),
                "intern", "(Ljava/lang/String;)Lerjang/EAtom;");
        mv.visitFieldInsn(Opcodes.PUTFIELD, self_type, "module_name", EATOM_DESC);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EATOM_TYPE.getInternalName(),
                "intern", "(Ljava/lang/String;)Lerjang/EAtom;");
        mv.visitFieldInsn(Opcodes.PUTFIELD, self_type, "function_name", EATOM_DESC);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();

        make_encode_method_for_exported(cw, self_type, arity);

        cw.visitEnd();

        byte[] data = cw.toByteArray();
        return data;
    }


    static void make_encode_method_for_exported(ClassWriter cw, String className, int arity) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "encode", "("+ Type.getDescriptor(EOutputStream.class) +")V", null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "module_name", EATOM_DESC);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, "function_name", EATOM_DESC);
        mv.visitLdcInsn(new Integer(arity));

        mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(EOutputStream.class), "write_external_fun",
                "("+EATOM_DESC+EATOM_DESC+"I)V");

        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
    }

    /*^^^^^^^^^^^^^^^^^^^^ Code generation of EFun{arity}Exported ^^^^^^^^^^*/

    /*==================== Code generation of EFun{arity}Guard: ==========*/
    @SuppressWarnings("unchecked")
    private static Class<? extends EFun> get_guard_fun_class(int arity) {
        String className = EFUN_TYPE.getClassName() + arity + "Guard";
        try {
            return (Class<? extends EFun>) Class.forName(className, true, EFun.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            // that's what we'll do here...
        }

        byte[] data = get_guard_fun_class_data(arity);
        data = weave(data);
        return ERT.defineClass(EFun.class.getClassLoader(), className, data);
    }

    private static byte[] get_guard_fun_class_data(int arity) {
        /* Code template:
           * public abstract class EFun{arity}Guard extends EFun{arity} {
           *   public <init>() {super();}
           *
           * // In subclasses: override invoke(), to do a direct call.
           * }
           */

        ensure(arity); // Ensure presence of superclass.
        String super_type = EFUN_TYPE.getInternalName() + arity;
        String self_type = super_type + "Guard";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                self_type, null, super_type, null);

        // TODO: Factor out default-constructor creation.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC/*PROTECTED*/, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, super_type, "<init>", "()V");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        byte[] data = cw.toByteArray();
        return data;
    }

    private static void make_invoke_method_for_guard(ClassWriter cw, String className, int arity) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "invoke", EUtil
                .getSignature(arity, true), null, PAUSABLE_EX);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        for (int i = 0; i < arity; i++) {
            mv.visitVarInsn(ALOAD, i + 2);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, className, "invoke_tail", EUtil
                .getSignature(arity, true));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(arity + 2, arity + 2);
        mv.visitEnd();
    }
    /*^^^^^^^^^^^^^^^^^^^^ Code generation of EFun{arity}Guard: ^^^^^^^^^^*/

    /*==================== Code generation utilities:  ==================*/
    /**
     * @param mv
     * @param i
     */
    private static void push_int(MethodVisitor mv, int i) {
        if (i >= -1 && i <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + i);
        } else {
            mv.visitLdcInsn(new Integer(i));
        }
    }

    public static byte[] weave(byte[] data) {
    	KilimContext context = new KilimContext();
    	context.detector = new erjang.beam.Compiler.ErjangDetector("/xx/", Collections.emptySet());
    	
		try {
			ClassWeaver w = new ClassWeaver(context, new ByteArrayInputStream(data));
			w.weave();
			for (ClassInfo ci : w.getClassInfos()) {
				// ETuple.dump(ci.className, ci.bytes);
				
				if (!ci.className.startsWith("kilim"))
					data = ci.bytes;
			}
		} catch (IOException e) {
			// TODO hackathon kludge
			throw new RuntimeException(e);
		}
        return data;
    }
    /*^^^^^^^^^^^^^^^^^^^^ Code generation utilities:  ^^^^^^^^^^^^^^^^^^*/

    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            get_fun_class(i);
            get_exported_fun_class(i);
        }
    }

    /**
     * @param arity
     */
    public static void ensure(int arity) {
        get_fun_class(arity);
    }
    public static void ensure_exported(int arity) {
        get_exported_fun_class(arity);
    }
    public static void ensure_guard(int arity) {
        get_guard_fun_class(arity);
    }
}
