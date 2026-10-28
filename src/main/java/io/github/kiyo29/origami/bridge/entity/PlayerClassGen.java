package io.github.kiyo29.origami.bridge.entity;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.Player;

/**
 * Generates "OrigamiPlayer": a class that extends the CraftHumanEntity marker and implements Player.
 * Every method forwards to an InvocationHandler (PlayerBridge), exactly like the former java.lang.reflect.Proxy.
 * The point is that permission plugins (LuckPerms) replace the public "perm" field reflectively,
 * which needs a real CraftHumanEntity subclass instance.
 */
public final class PlayerClassGen {
    /** Read by the generated class (getstatic). */
    public static volatile Method[] METHODS = new Method[0];

    private static final ClassDesc SELF = ClassDesc.of(PlayerClassGen.class.getPackageName(), "OrigamiPlayer");
    private static final ClassDesc GEN = ClassDesc.of(PlayerClassGen.class.getName());
    private static final ClassDesc SUPER = ClassDesc.of(CraftHumanEntity.class.getName());
    private static final ClassDesc HANDLER = ClassDesc.of(InvocationHandler.class.getName());
    private static final ClassDesc METHOD = ClassDesc.of(Method.class.getName());
    private static final ClassDesc METHOD_ARR = METHOD.arrayType();
    private static final ClassDesc OBJECT = ConstantDescs.CD_Object;
    private static final MethodTypeDesc INVOKE =
            MethodTypeDesc.of(OBJECT, OBJECT, METHOD, OBJECT.arrayType());

    private static Constructor<?> ctor;

    private PlayerClassGen() { }

    public static synchronized Player create(InvocationHandler handler) {
        try {
            if (ctor == null) {
                ctor = define();
            }
            return (Player) ctor.newInstance(handler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create the Player implementation class", e);
        }
    }

    private static List<Method> collect() throws NoSuchMethodException {
        Map<String, Method> out = new LinkedHashMap<>();
        for (Method m : Player.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            String key = m.getName() + MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString();
            out.putIfAbsent(key, m);
        }
        for (Method m : new Method[] {
                Object.class.getMethod("equals", Object.class),
                Object.class.getMethod("hashCode"),
                Object.class.getMethod("toString")}) {
            String key = m.getName() + MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString();
            out.putIfAbsent(key, m);
        }
        return new ArrayList<>(out.values());
    }

    private static Constructor<?> define() throws ReflectiveOperationException {
        List<Method> methods = collect();
        METHODS = methods.toArray(new Method[0]);
        byte[] bytes = ClassFile.of().build(SELF, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            cb.withSuperclass(SUPER);
            cb.withInterfaceSymbols(ClassDesc.of(Player.class.getName()));
            cb.withField("h", HANDLER, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            cb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, HANDLER),
                    ClassFile.ACC_PUBLIC, code -> {
                        code.aload(0);
                        code.invokespecial(SUPER, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void));
                        code.aload(0);
                        code.aload(1);
                        code.putfield(SELF, "h", HANDLER);
                        code.return_();
                    });
            for (int i = 0; i < methods.size(); i++) {
                final int idx = i;
                final Method m = methods.get(i);
                MethodTypeDesc desc = MethodTypeDesc.ofDescriptor(
                        MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString());
                cb.withMethodBody(m.getName(), desc, ClassFile.ACC_PUBLIC, code -> emit(code, m, idx));
            }
        });
        Class<?> c = MethodHandles.lookup().defineClass(bytes);
        return c.getConstructor(InvocationHandler.class);
    }

    private static void emit(CodeBuilder code, Method m, int idx) {
        Class<?>[] pt = m.getParameterTypes();
        code.aload(0);
        code.getfield(SELF, "h", HANDLER);
        code.aload(0);
        code.getstatic(GEN, "METHODS", METHOD_ARR);
        code.loadConstant(idx);
        code.aaload();
        code.loadConstant(pt.length);
        code.anewarray(OBJECT);
        int slot = 1;
        for (int j = 0; j < pt.length; j++) {
            Class<?> t = pt[j];
            code.dup();
            code.loadConstant(j);
            if (t == long.class) {
                code.lload(slot);
            } else if (t == double.class) {
                code.dload(slot);
            } else if (t == float.class) {
                code.fload(slot);
            } else if (t.isPrimitive()) {
                code.iload(slot);
            } else {
                code.aload(slot);
            }
            if (t.isPrimitive()) {
                ClassDesc wd = ClassDesc.of(wrapper(t).getName());
                code.invokestatic(wd, "valueOf", MethodTypeDesc.of(wd, ClassDesc.ofDescriptor(t.descriptorString())));
            }
            code.aastore();
            slot += (t == long.class || t == double.class) ? 2 : 1;
        }
        code.invokeinterface(HANDLER, "invoke", INVOKE);
        Class<?> r = m.getReturnType();
        if (r == void.class) {
            code.pop();
            code.return_();
        } else if (r.isPrimitive()) {
            ClassDesc wd = ClassDesc.of(wrapper(r).getName());
            code.checkcast(wd);
            code.invokevirtual(wd, r.getName() + "Value", MethodTypeDesc.of(ClassDesc.ofDescriptor(r.descriptorString())));
            if (r == long.class) {
                code.lreturn();
            } else if (r == double.class) {
                code.dreturn();
            } else if (r == float.class) {
                code.freturn();
            } else {
                code.ireturn();
            }
        } else {
            code.checkcast(ClassDesc.ofDescriptor(r.descriptorString()));
            code.areturn();
        }
    }

    private static Class<?> wrapper(Class<?> primitive) {
        return MethodType.methodType(primitive).wrap().returnType();
    }
}
