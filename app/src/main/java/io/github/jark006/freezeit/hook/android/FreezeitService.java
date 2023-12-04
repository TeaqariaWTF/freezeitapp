package io.github.jark006.freezeit.hook.android;import static io.github.jark006.freezeit.hook.XpUtils.log;import android.content.Context;import android.net.LocalServerSocket;import android.os.Build;import android.os.Handler;import java.io.File;import java.io.IOException;import java.io.OutputStream;import java.lang.reflect.Array;import java.lang.reflect.Method;import java.util.ArrayList;import java.util.Arrays;import java.util.Set;import de.robv.android.xposed.XC_MethodHook;import de.robv.android.xposed.XposedHelpers;import io.github.jark006.freezeit.Utils;import io.github.jark006.freezeit.hook.Config;import io.github.jark006.freezeit.hook.Enum;import io.github.jark006.freezeit.hook.XpUtils;public class FreezeitService {    final static String TAG = "[Service]";    final static String CFG_TAG = "[Config]";    final static String AMS_TAG = "[AMS]";    final static String NMS_TAG = "[Netd]";    final static String WAK_TAG = "[AppOps]";    final static String DPC_TAG = "[Display]";    final static String FGD_TAG = "[Foreground]";    final static String PED_TAG = "[Pending]";    final int REPLY_SUCCESS = 2;    final int REPLY_FAILURE = 0;    Config config;    ArrayList<?> mLruProcesses;    Object mPowerState;    Object appOpsService;    Method setUidModeMethod;    Object mNetdService;    Class<?> UidRangeParcelClazz;    LocalSocketServer serverThread = new LocalSocketServer();    ClassLoader classLoader;    public FreezeitService(Config config, ClassLoader classLoader) {        this.config = config;        this.classLoader = classLoader;        // A10-13 ActivityManagerService        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java        XpUtils.hookConstructor(AMS_TAG, classLoader, new XC_MethodHook() {            @Override            protected void afterHookedMethod(MethodHookParam param) {                Object mProcessList = XposedHelpers.getObjectField(param.thisObject, Enum.Field.mProcessList);                mLruProcesses = mProcessList == null ? null :                        (ArrayList<?>) XposedHelpers.getObjectField(mProcessList, Enum.Field.mLruProcesses);                log(AMS_TAG, ((mLruProcesses == null ? "!!! Fail" : "Success")) + " init mLruProcesses");            }        }, Enum.Class.ActivityManagerService, Context.class, Enum.Class.ActivityTaskManagerService);        // A10-A13 NetworkManagementService        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r74:frameworks/base/services/core/java/com/android/server/NetworkManagementService.java        // latest        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/NetworkManagementService.java        UidRangeParcelClazz = XposedHelpers.findClassIfExists(Enum.Class.UidRangeParcel, classLoader);        log(NMS_TAG, ((UidRangeParcelClazz == null ? "!!! Fail" : "Success")) + " init UidRangeParcel");        XpUtils.hookMethod(NMS_TAG, classLoader, new XC_MethodHook() {                    @Override                    protected void afterHookedMethod(MethodHookParam param) {                        mNetdService = XposedHelpers.getObjectField(param.thisObject, Enum.Field.mNetdService);                        log(NMS_TAG, ((mNetdService == null ? "!!! Fail" : "Success")) + " init mNetdService");                    }                },                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?                        Enum.Class.NetworkManagementServiceU : Enum.Class.NetworkManagementService,                Enum.Method.connectNativeNetdService);        // AppOpsService        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/appop/AppOpsService.java;l=1776        setUidModeMethod = XposedHelpers.findMethodExactIfExists(                Enum.Class.AppOpsService, classLoader, Enum.Method.setUidMode,                int.class, int.class, int.class);        log(WAK_TAG, ((setUidModeMethod == null ? "!!! Fail" : "Success")) + " init setUidModeMethod");        XC_MethodHook AppOpsHook = new XC_MethodHook() {            @Override            protected void afterHookedMethod(MethodHookParam param) {                appOpsService = param.thisObject;                log(WAK_TAG, ((appOpsService == null ? "!!! Fail" : "Success")) + " init appOpsService");            }        };        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)            XpUtils.hookConstructor(WAK_TAG, classLoader, AppOpsHook, Enum.Class.AppOpsService,                    File.class, File.class, Handler.class, Context.class);        else // A11-13            XpUtils.hookConstructor(WAK_TAG, classLoader, AppOpsHook, Enum.Class.AppOpsService,                    File.class, Handler.class, Context.class);        // A10-A13 DisplayPowerController        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r74:frameworks/base/services/core/java/com/android/server/display/DisplayPowerState.java;l=145        XpUtils.hookMethod(DPC_TAG, classLoader, new XC_MethodHook() {            @Override            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {                mPowerState = XposedHelpers.getObjectField(param.thisObject, Enum.Field.mPowerState);                log(WAK_TAG, ((mPowerState == null ? "!!! Fail" : "Success")) + " init mPowerState");            }        }, Enum.Class.DisplayPowerController, Enum.Method.initialize, int.class);        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)            XpUtils.hookMethod(DPC_TAG, classLoader, new XC_MethodHook() {                @Override                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {                    mPowerState = XposedHelpers.getObjectField(param.thisObject, Enum.Field.mPowerState);                    log(WAK_TAG, ((mPowerState == null ? "!!! Fail" : "Success")) + " init mPowerState");                }            }, Enum.Class.DisplayPowerController2, Enum.Method.initialize, int.class);        serverThread.start();    }    class LocalSocketServer extends Thread {        // 冻它命令识别码, 1359322925 是字符串"Freezeit"的10进制CRC32值        final int baseCode = 1359322925;        final int GET_FOREGROUND = baseCode + 1;        final int GET_SCREEN_STATE = baseCode + 2;        final int GET_XP_LOG = baseCode + 3;        final int SET_CONFIG = baseCode + 20;        final int SET_WAKEUP_LOCK = baseCode + 21; // 设置唤醒锁权限        final int BREAK_NETWORK = baseCode + 41;        final int UPDATE_PENDING = baseCode + 60;   // 更新待冻结应用        // 有效命令集        final Set<Integer> requestCodeSet = Set.of(                GET_FOREGROUND,                GET_SCREEN_STATE,                GET_XP_LOG,                SET_CONFIG,                SET_WAKEUP_LOCK,                BREAK_NETWORK,                UPDATE_PENDING        );        byte[] buff = new byte[128 * 1024];// 128 KiB        LocalServerSocket mSocketServer;        int[] uidListTemp = new int[128]; // 临时存放UID列表，不可复用        Exception nothingException = new Exception();        @Override        public void run() {            try {                mSocketServer = new LocalServerSocket("FreezeitXposedServer");            } catch (Exception e) {                log(TAG, "创建失败 LocalServerSocket");                return;            }            int i = 5;            while (--i > 0) {                try {                    sleep(3000);                    acceptHandle();                } catch (Exception e) {                    log(TAG, "clientHandle 第" + i + " 次异常: " + e);                    e.printStackTrace();                }            }            log(TAG, "mSocketServer 异常次数过多，已退出");        }        @SuppressWarnings("InfiniteLoopStatement")        void acceptHandle() throws IOException {            while (true) {                var client = mSocketServer.accept();//堵塞,单线程处理                if (client == null) continue;                client.setSoTimeout(3000);                var is = client.getInputStream();                final int recvLen = is.read(buff, 0, 8);                if (recvLen != 8) {                    log(TAG, "非法连接 接收长度 " + recvLen);                    is.close();                    client.close();                    continue;                }                // 前4字节是请求码，后4字节是附加数据长度                final int requestCode = Utils.Byte2Int(buff, 0);                if (!requestCodeSet.contains(requestCode)) {                    log(TAG, "非法请求码 " + requestCode);                    is.close();                    client.close();                    continue;                }                final int payloadLen = Utils.Byte2Int(buff, 4);                if (payloadLen > 0) {                    if (buff.length <= payloadLen) {                        log(TAG, "数据量超过承载范围 " + payloadLen);                        is.close();                        client.close();                        continue;                    }                    int readCnt = 0;                    while (readCnt < payloadLen) { //欲求不满                        int cnt = is.read(buff, readCnt, payloadLen - readCnt);                        if (cnt < 0) {                            log(TAG, "接收完毕或错误 " + cnt);                            break;                        }                        readCnt += cnt;                    }                    if (payloadLen != readCnt) {                        log(TAG, "接收错误 payloadLen" + payloadLen + " readCnt" + readCnt);                        is.close();                        client.close();                        continue;                    }                }                var os = client.getOutputStream();                switch (requestCode) {                    case GET_FOREGROUND:                        handleForeground(os, buff);                        break;                    case GET_SCREEN_STATE:                        handleScreen(os, buff);                        break;                    case GET_XP_LOG:                        handleXpLog(os);                        break;                    case SET_CONFIG:                        handleConfig(os, buff, payloadLen);                        break;                    case SET_WAKEUP_LOCK:                        handleWakeupLock(os, buff, payloadLen);                        break;                    case BREAK_NETWORK:                        handleDestroySocket(os, buff, payloadLen);                        break;                    case UPDATE_PENDING:                        handlePendingApp(os, buff, payloadLen);                        break;                    default:                        log(TAG, "请求码功能暂未实现TODO: " + requestCode);                        break;                }                client.close();            }        }        void handleForeground(OutputStream os, byte[] replyBuff) throws IOException {            config.foregroundUid.clear();            try {                for (int i = (mLruProcesses == null || !config.isCurProcStateInitialized()) ?                        0 : mLruProcesses.size() - 1; i > 10; i--) { //逆序, 最近活跃应用在最后                    var processRecord = mLruProcesses.get(i); // IndexOutOfBoundsException                    if (processRecord == null) continue;                    final int uid = config.getProcessRecordUid(processRecord);// processRecord                    if (!config.managedApp.contains(uid))                        continue;                    var mState = config.getProcessRecordState(processRecord);                    if (mState == null) continue;                    int mCurProcState = config.getCurProcState(mState);                    // 2在顶层 3绑定了顶层应用, 有前台服务:4常驻状态栏 6悬浮窗                    // ProcessStateEnum: https://cs.android.com/android/platform/superproject/main/+/main:out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/xref35/srcjars.xref/android/app/ProcessStateEnum.java;l=10                    if ((0 <= mCurProcState && mCurProcState <= 3) ||                            (4 <= mCurProcState && mCurProcState <= 6 && config.permissive.contains(uid)))                        config.foregroundUid.add(uid);                }            } catch (Exception e) {                log(FGD_TAG, "前台服务错误: " + e);                e.printStackTrace();            }            // 开头的4字节放置UID的个数，往后每4个字节放一个UID  [小端]            int replyLen = (config.foregroundUid.size() + 1) * 4;            Utils.Int2Byte(config.foregroundUid.size(), replyBuff, 0);            config.foregroundUid.toBytes(replyBuff, 4);            os.write(replyBuff, 0, replyLen);            os.close();        }        // 0未知 1息屏 2亮屏 3Doze...        void handleScreen(OutputStream os, byte[] replyBuff) throws IOException {            /*            https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/Display.java;l=387            enum DisplayStateEnum            public static final int DISPLAY_STATE_UNKNOWN = 0;            public static final int DISPLAY_STATE_OFF = 1;            public static final int DISPLAY_STATE_ON = 2;            public static final int DISPLAY_STATE_DOZE = 3; //亮屏但处于Doze的非交互状态状态            public static final int DISPLAY_STATE_DOZE_SUSPEND = 4; // 同上，但CPU不控制显示，由协处理器或其他控制            public static final int DISPLAY_STATE_VR = 5;            public static final int DISPLAY_STATE_ON_SUSPEND = 6; //非Doze, 类似4             */            if (mPowerState == null) {                Utils.Int2Byte(0, replyBuff, 0);                log("屏幕状态", "mPowerState 未初始化");            } else {                final int mScreenState = config.getScreenState(mPowerState);                Utils.Int2Byte(mScreenState, replyBuff, 0);//                log("屏幕状态", String.valueOf(mScreenState));            }            os.write(replyBuff, 0, 4);            os.close();        }        void handleXpLog(OutputStream os) throws IOException {            os.write(XpUtils.xpLogContent.toString().getBytes());            os.close();        }        /**         * 总共 2或3 行内容         * 第一行：冻它设置数据         * 第二行：受冻它管控的应用 只含杀死后台和冻结配置， 不含自由后台、白名单         * 第三行：宽松前台UID列表 只含杀死后台和冻结配置， 不含自由后台、白名单 (此行可能为空)         */        void handleConfig(OutputStream os, byte[] buff, int payloadLen) throws IOException {            var splitLine = new String(buff, 0, payloadLen).split("\n");            if (splitLine.length != 2 && splitLine.length != 3) {                log(CFG_TAG, "Fail splitLine.length:" + splitLine.length);                for (String line : splitLine)                    log(CFG_TAG, "START:" + line);                Utils.Int2Byte(REPLY_FAILURE, buff, 0);                os.write(buff, 0, 4);                os.close();                return;            }            config.managedApp.clear();            config.uidIndex.clear();            config.pkgIndex.clear();            config.permissive.clear();            try {                StringBuilder tmp = new StringBuilder("Parse:");                String[] elementList = splitLine[0].split(" ");                for (int i = 0; i < elementList.length; i++)                    config.settings[i] = Integer.parseInt(elementList[i]);                tmp.append(" settings:").append(elementList.length);                elementList = splitLine[1].split(" ");                for (String element : elementList) {                    if (element.length() <= 5)                        continue;                    // element: "10xxxpackName"                    final int uid = Integer.parseInt(element.substring(0, 5));                    final String packName = element.substring(5);                    config.managedApp.add(uid);                    config.uidIndex.put(packName, uid);                    config.pkgIndex.put(uid, packName);                }                tmp.append(" managedApp:").append(config.managedApp.size());                tmp.append(" uidIndex:").append(config.uidIndex.size());                tmp.append(" pkgIndex:").append(config.pkgIndex.size());                config.pkgIndex.put(1000, "AndroidSystem");                config.pkgIndex.put(-1, "Unknown");                if (splitLine.length == 3) {                    elementList = splitLine[2].split(" ");                    for (String uidStr : elementList)                        config.permissive.add(Integer.parseInt(uidStr));                }                tmp.append(" permissive:").append(config.permissive.size());                log(CFG_TAG, tmp.toString());                Utils.Int2Byte(REPLY_SUCCESS, buff, 0);            } catch (Exception e) {                log(CFG_TAG, "Exception: [" + Arrays.toString(splitLine) + "]: \n" + e);                Utils.Int2Byte(REPLY_FAILURE, buff, 0);            }            os.write(buff, 0, 4);            os.close();            if (!config.initField) {                config.Init(classLoader);                log("Freezeit[InitField]:", config.Init(classLoader));            }        }        /**         * <a href="https://cs.android.com/android/platform/superproject/+/android-mainline-10.0.0_r9:out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/xref35/srcjars.xref/android/app/AppProtoEnums.java;l=84">...</a>         * WAKEUP_LOCK CODE is [40] A10-A13         * public static final String[] MODE_NAMES = new String[] {         * "allow",        // MODE_ALLOWED         * "ignore",       // MODE_IGNORED         * "deny",         // MODE_ERRORED         * "default",      // MODE_DEFAULT         * "foreground",   // MODE_FOREGROUND         * };         */        void handleWakeupLock(OutputStream os, byte[] buff, int payloadLen) throws IOException {            final int WAKEUP_LOCK_IGNORE = 1;            final int WAKEUP_LOCK_DEFAULT = 3;            final int WAKEUP_LOCK_CODE = 40;            try {                if (setUidModeMethod == null || appOpsService == null) {                    log(WAK_TAG, "未初始化 setUidModeMethod appOps");                    throw nothingException;                }                if (payloadLen <= 8 || payloadLen % 4 != 0) {                    log(WAK_TAG, "非法 payloadLen " + payloadLen);                    throw nothingException;                }                final int uidLen = Utils.Byte2Int(buff, 0);                if (payloadLen != (uidLen + 2) * 4) {                    log(WAK_TAG, "非法 payloadLen " + payloadLen + " uidLen " + uidLen);                    throw nothingException;                }                final int mode = Utils.Byte2Int(buff, 4); // 1:ignore  3:default                if (mode != WAKEUP_LOCK_IGNORE && mode != WAKEUP_LOCK_DEFAULT) {                    log(WAK_TAG, "非法mode:" + mode);                    throw nothingException;                }                Utils.Byte2Int(buff, 8, uidLen * 4, uidListTemp, 0);                for (int i = 0; i < uidLen; i++) {                    final int uid = uidListTemp[i];                    if (config.managedApp.contains(uid))                        setUidModeMethod.invoke(appOpsService, WAKEUP_LOCK_CODE, uid, mode);                    else                        log(WAK_TAG, "非法UID:" + uid);                }                if (XpUtils.DEBUG_WAKEUP_LOCK) {                    var tmp = new StringBuilder(mode == WAKEUP_LOCK_IGNORE ? "禁止 WakeLock: " : "恢复 WakeLock: ");                    for (int i = 0; i < uidLen; i++) {                        final int uid = uidListTemp[i];                        tmp.append(config.pkgIndex.getOrDefault(uid, String.valueOf(uid))).append(", ");                    }                    log(WAK_TAG, tmp.toString());                }                if (mode == WAKEUP_LOCK_IGNORE) // 此操作会在息屏超时后触发                    config.foregroundUid.clear();                Utils.Int2Byte(REPLY_SUCCESS, buff, 0);            } catch (Exception e) {                Utils.Int2Byte(REPLY_FAILURE, buff, 0);            }            os.write(buff, 0, 4);            os.close();        }        void handleDestroySocket(OutputStream os, byte[] buff, int payloadLen) throws IOException {            try {                if (payloadLen != 4) {                    log(NMS_TAG, "非法 payloadLen " + payloadLen);                    throw nothingException;                }                final int uid = Utils.Byte2Int(buff, 0);                if (!config.managedApp.contains(uid)) {                    log(NMS_TAG, "非法UID" + uid);                    throw nothingException;                }                if (mNetdService == null) {                    log(NMS_TAG, "mNetdService null");                    throw nothingException;                }                if (UidRangeParcelClazz == null) {                    log(NMS_TAG, "UidRangeParcelClazz null");                    throw nothingException;                }                Object uidRanges = Array.newInstance(UidRangeParcelClazz, 1);                Array.set(uidRanges, 0, XposedHelpers.newInstance(UidRangeParcelClazz, uid, uid));                XposedHelpers.callMethod(mNetdService, Enum.Method.socketDestroy, uidRanges, new int[0]);                Utils.Int2Byte(REPLY_SUCCESS, buff, 0);            } catch (Exception e) {                Utils.Int2Byte(REPLY_FAILURE, buff, 0);            }            os.write(buff, 0, 4);            os.close();        }        void handlePendingApp(OutputStream os, byte[] buff, int payloadLen) throws IOException {            try {                if (payloadLen % 4 != 0) {                    log(PED_TAG, "非法 payloadLen " + payloadLen);                    throw nothingException;                }                final int uidLen = payloadLen / 4;                config.pendingUid.clear();                if (payloadLen > 0) {                    Utils.Byte2Int(buff, 0, payloadLen, uidListTemp, 0);                    for (int i = 0; i < uidLen; i++) {                        final int uid = uidListTemp[i];                        if (config.managedApp.contains(uid))                            config.pendingUid.add(uid);                        else                            log(PED_TAG, "非法UID:" + uid);                    }                }                if (XpUtils.DEBUG_PENDING_UID) {                    var tmp = new StringBuilder("待冻结更新: ");                    for (int i = 0; i < uidLen; i++) {                        final int uid = uidListTemp[i];                        tmp.append(config.pkgIndex.getOrDefault(uid, String.valueOf(uid))).append(", ");                    }                    log(TAG, tmp.toString());                }                Utils.Int2Byte(REPLY_SUCCESS, buff, 0);            } catch (Exception e) {                Utils.Int2Byte(REPLY_FAILURE, buff, 0);            }            os.write(buff, 0, 4);            os.close();        }    }}