package com.shen1991;

import android.app.ActivityThread;
import android.app.Application;
import android.util.Log;
import dalvik.system.DexFile;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class XX {
    private static final String TAG = "XX-DUMPER";

    public static Object invokeStaticMethod(String class_name,
                                            String method_name, Class<?>[] pareTyple, Object[] pareVaules) {

        try {
            Class<?> obj_class = Class.forName(class_name);
            Method method = obj_class.getMethod(method_name, pareTyple);
            return method.invoke(null, pareVaules);
        } catch (SecurityException | IllegalArgumentException | IllegalAccessException |
                 NoSuchMethodException | InvocationTargetException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;

    }

    public static Object getFieldObject(String class_name, Object obj,
                                        String filedName) {
        try {
            Class<?> obj_class = Class.forName(class_name);
            Field field = obj_class.getDeclaredField(filedName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (SecurityException | IllegalArgumentException | NoSuchFieldException |
                 IllegalAccessException | ClassNotFoundException | NullPointerException e) {
            e.printStackTrace();
        }
        return null;

    }

    public static Application getCurrentApplication() {
        Object currentActivityThread = invokeStaticMethod(
                "android.app.ActivityThread", "currentActivityThread",
                new Class[]{}, new Object[]{});
        Object mBoundApplication = getFieldObject(
                "android.app.ActivityThread", currentActivityThread,
                "mBoundApplication");
        Application mInitialApplication = (Application) getFieldObject("android.app.ActivityThread",
                currentActivityThread, "mInitialApplication");
        Object loadedApkInfo = getFieldObject(
                "android.app.ActivityThread$AppBindData",
                mBoundApplication, "info");
        return (Application) getFieldObject("android.app.LoadedApk", loadedApkInfo, "mApplication");
    }

    public static ClassLoader getClassloader() {
        ClassLoader resultClassloader = null;
        Object currentActivityThread = invokeStaticMethod(
                "android.app.ActivityThread", "currentActivityThread",
                new Class[]{}, new Object[]{});
        Object mBoundApplication = getFieldObject(
                "android.app.ActivityThread", currentActivityThread,
                "mBoundApplication");
        Application mInitialApplication = (Application) getFieldObject("android.app.ActivityThread",
                currentActivityThread, "mInitialApplication");
        Object loadedApkInfo = getFieldObject(
                "android.app.ActivityThread$AppBindData",
                mBoundApplication, "info");
        Application mApplication = (Application) getFieldObject("android.app.LoadedApk", loadedApkInfo, "mApplication");
        if (mApplication != null) {
            Log.e(TAG, "go into app->" + "package name:" + mApplication.getPackageName());
            resultClassloader = mApplication.getClassLoader();
        }
        return resultClassloader;
    }

    /**
     * 根据classloader 获取 pathList
     */
    static Field getPathListField(ClassLoader classLoader) {
        Field pathListField = null;
        Class<?> classLoaderClass = classLoader.getClass();
        int maxFindLength = 5;
        while (classLoaderClass != null && pathListField == null && (maxFindLength--) > 0) {
            try {
                pathListField = classLoaderClass.getDeclaredField("pathList");
            } catch (Throwable e) {
                classLoaderClass = classLoaderClass.getSuperclass();
            }
        }
        return pathListField;
    }

    /**
     * 获取指定 Classloader Element数组
     */
    static Object[] getClassLoaderElements(ClassLoader loader) {
        try {
            Field pathListField = getPathListField(loader);
            if (pathListField == null) {
                Log.e(TAG, "getClassLoaderElements: pathList == null");
                return null;
            }
            pathListField.setAccessible(true);
            Object dexPathList = pathListField.get(loader);
            if (dexPathList == null)
                return null;
            Field dexElementsField = dexPathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(dexPathList);
            if (dexElements != null) {
                return dexElements;
            } else {
                Log.e(TAG, "getClassLoaderElements: dexElements == null");
            }

        } catch (NoSuchFieldException e) {
            Log.e(TAG, "getClassLoaderElements: NoSuchFieldException:", e);
        } catch (IllegalAccessException e) {
            Log.e(TAG, "getClassLoaderElements: IllegalAccessException:", e);
        }
        return null;
    }


    static int getDexFileClassSize(DexFile dexFile) {
        int size = 0;
        Enumeration<String> entries = dexFile.entries();
        while (entries.hasMoreElements()) {
            //必须调用 nextElement 不然会卡死
            entries.nextElement();
            size++;
        }
        return size;
    }

    static synchronized Class<?> initAllClass(DexFile dexFile, final ClassLoader loader) {
        int classSize = getDexFileClassSize(dexFile);
        Log.e(TAG, "需要加载的 getDexFileClassName 个数:" + classSize);
        final List<Class<?>> initialClasses = new ArrayList<>();

        try {

            Enumeration<String> enumeration = dexFile.entries();
            while (enumeration.hasMoreElements()) {//遍历
                final String className = enumeration.nextElement();
                try {
                    if(className.contains("com.shen1991")){
                        continue;
                    }
                    // 对每个 classloader都进行遍历
                    // 是否初始化需要根据对应加固手段确定，例如爱加密会设置炸弹类
                    // fart会主动调用, 暂时保险不不调用
                    // 我们可以通过点击功能配合延迟dump的方式稳妥的触发
                    initialClasses.add(Class.forName(className, false, loader));
                } catch (Throwable ignored) {

                }
            }
            if (!initialClasses.isEmpty()) {
                return initialClasses.get(0);
            }
            return null;
        } catch (Throwable e) {
            Log.e(TAG, "init all class error:", e);
            return null;
        }
    }

    //根据classLoader->pathList->dexElements拿到dexFile
    //然后拿到mCookie后，使用getClassNameList获取到所有类名。
    //loadClassAndInvoke处理所有类名导出所有函数
    //dumpMethodCode这个函数是fart自己加在DexFile中的
    @SuppressWarnings("all")
    public static void unpackWithClassLoader(ClassLoader appClassloader) {
        Log.e(TAG, "unpacking " + appClassloader.toString());
        Object[] dexElements = getClassLoaderElements(appClassloader);
        Method dumpMethodCode_method = null;

        if (dexElements != null) {
            for (Object dexElement : dexElements) {
                try {
                    //每一个 element 里面都有一个 dex文件
                    Field dexFileField = dexElement.getClass().getDeclaredField("dexFile");
                    dexFileField.setAccessible(true);
                    DexFile dexFile = (DexFile) dexFileField.get(dexElement); // 这个对象类型实际为DexFile
                    if (dexFile == null) {
                        Log.e(TAG, "dexFile is null........");
                        continue;
                    }
                    // 初始化每个类，防止填充式类抽取
                    Class<?> firstClass = initAllClass(dexFile, appClassloader);
                    if (firstClass != null) {
                        Field mCookieField = dexFile.getClass().getDeclaredField("mCookie");
                        mCookieField.setAccessible(true);
                        Object mCookie = mCookieField.get(dexFile);
                        Log.e(TAG,"get mCookie:" + mCookie);
                        DexFile.xxDump("/data/data/" + getCurrentApplication().getPackageName() + "/", mCookie); // TODO
                        Log.e(TAG,"HAPPY! dump dex with mCookie:" + mCookie);
                    }
                } catch (Exception ignored){

                }

            }
        }

    }

    public static void xxDumper() {
        ClassLoader appClassloader = getClassloader();
        if (appClassloader == null) {
            Log.e(TAG, "classloader is null");
            return;
        }
        // 遍历出所有非BootClassLoader进行脱壳
        ClassLoader parentClassloader = appClassloader.getParent();
        if (!appClassloader.toString().contains("java.lang.BootClassLoader")) {
            unpackWithClassLoader(appClassloader);
        }
        while (parentClassloader != null) {
            if (!parentClassloader.toString().contains("java.lang.BootClassLoader")) {
                unpackWithClassLoader(parentClassloader);
            }
            parentClassloader = parentClassloader.getParent();
        }
    }

    public static boolean shouldUnpack() {
        boolean shouldUnpack = false;
        String processName = ActivityThread.currentProcessName();
        BufferedReader br = null;
        String configPath = "/data/local/tmp/xx.config";
        try {
            br = new BufferedReader(new FileReader(configPath));
            String line;
            while ((line = br.readLine()) != null) {
                if (processName != null && processName.equals(line)) {
                    shouldUnpack = true;
                    break;
                }
            }
            br.close();
        } catch (Exception ignored) {
        }
        return shouldUnpack;
    }


    public static void xxThread() {
        if (!shouldUnpack()) {
            return;
        }

        new Thread(() -> {
            // TODO Auto-generated method stub
            try {
                Thread.sleep(60 * 1000);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            xxDumper();

        }).start();
    }
}
