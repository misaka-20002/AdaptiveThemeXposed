package com.misaka20002.AdaptiveThemeXposed;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 运行在 system_server 中。
 * Hook AutomaticBrightnessController 拿到自动亮度输出和环境光 lux，
 * 在自己的 HandlerThread 里做判断，再通过 UiModeManager 切换深浅色。
 *
 * hook 回调只做三件事：确认采样来自主屏、带时间戳入队、唤醒处理线程。
 * 不做耗时操作，也不持有会与系统服务交叉的锁。
 *
 * 切换规则：
 *  - 数值 <= darkBelow 为"暗区"，>= lightAbove 为"亮区"，中间是滞回区。
 *  - 每个采样都按到达顺序逐个处理（不合并），所以哪怕读数只离开某个区一瞬间，
 *    计时也会从重新进入的那个采样的时间戳重新开始。
 *  - 必须连续 delayMs 都停留在同一个区才会处理；每进入一个区只处理一次。
 *    之后手动改主题不会被改回去，直到环境真正切换到另一个区。
 *  - 开启"仅在桌面切换"时，条件满足但无法确认前台是桌面/最近任务，就一直等
 *    （每 2 秒查一次）。任何"不确定"都按"不在桌面"处理，绝不放行。
 *  - 真正切换前一定会重新检查配置文件，已排队的切换不会沿用旧配置。
 *  - 读数只在"来源还活着"时有效：系统停用光线传感器（关闭自动亮度、熄屏）或采样队列
 *    丢过数据时，缓存的亮度和 lux 立即作废，必须等到新的采样才会重新开始计时。
 *
 *  - 只有"所选来源"的新采样才推进状态机：亮度模式下 lux 采样只更新缓存，反之亦然。
 *  - 调用系统接口前的顺序按"越可能阻塞越靠前"排列：检查配置文件（磁盘 I/O）→ 查询系统状态
 *   （电源、主题、前台任务，最后是前台用户）→ 看一眼采样队列和丢失计数（纯内存）→ 立即调用。
 *    任何一步发现新情况就先处理，然后整套重来。
 *  - 配置变化有内存里的信号，不靠"再读一次文件"来发现：设置页保存时先发"要保存了"，
 *    等本模块的接收线程确认收到之后才写文件，写完再发"保存结束"。接收线程独立于处理线程，
 *    只改两个内存变量。于是最后那一眼（纯内存）同时覆盖采样和配置：
 *    凡是在这一眼之前写完的保存，一定已经被看到；正在保存则先等它结束。
 *    "正在保存"能等多久不靠猜：通知里带着设置页进程的存活凭证（一个 Binder），
 *    进程一死系统立刻回调，等待随即解除；进程活着就一直等到"保存结束"，另设 2 分钟的硬上限兜底。
 *  - 倒计时的起点取三者中最晚的：读数生效的时刻、当前用户到前台的时刻、当前规则生效的时刻。
 *    所以排在队列里的旧采样不会让新用户或新配置下的倒计时"倒推"成已到期。
 *  - "本区已处理"按用户分别记录：机主触发过深色，不影响副用户在同样光线下被处理。
 *    倒计时也只属于当时的前台用户：前台用户一变，旧倒计时立即作废，新用户从切换那一刻起重新等满延迟。
 *  - 重读配置时，只有切换规则真的变了才重新判断；只改调试开关不会推翻手动选择的主题。
 *  - 前台是副用户时，用按"当前用户"保存的接口切换；系统没有这种接口就不切，
 *    绝不把副用户的环境写进用户 0 的保存值。
 *
 * 不变式：pendingTarget != 0 时，有且只有一个 FIRE 排在 pendingDue 这个时刻。
 * 每次排队或作废都会让 pendingGen 自增，用来判断"我正在执行的这次等待是否还是同一次"。
 */
final class ThemeController {
    private static final String TAG = "[AdaptiveTheme] ";
    private static final String ABC = "com.android.server.display.AutomaticBrightnessController";
    private static final String KEY_ACCEPT = "adaptive_theme_accept";

    private static final int NIGHT_AUTO = UiModeManager.MODE_NIGHT_AUTO;  // 0
    private static final int NIGHT_NO = UiModeManager.MODE_NIGHT_NO;      // 1
    private static final int NIGHT_YES = UiModeManager.MODE_NIGHT_YES;    // 2
    private static final int NIGHT_CUSTOM = 3;                            // API 30+
    private static final int USER_CURRENT = -2;
    private static final int DEFAULT_DISPLAY = 0;
    /** system_server 自己所属的用户（0）。UiModeManager.setNightMode 按调用方用户保存 */
    private static final int SELF_USER = android.os.Process.myUid() / 100000;
    private static final int ACTIVITY_TYPE_HOME = 2;      // WindowConfiguration
    private static final int ACTIVITY_TYPE_RECENTS = 3;
    private static final long HOME_POLL_MS = 2000L;
    private static final long ERROR_RETRY_MS = 60_000L;
    private static final long RELOAD_COALESCE_MS = 250L;
    private static final long SAVE_HOLD_MS = 3000L;       // 通知里没带存活凭证时（旧版设置页）最多等这么久
    private static final long SAVE_HOLD_MAX_MS = 120_000L; // 带凭证时的硬上限：设置页活着却始终不结束保存时的兜底
    private static final long SAVE_HOLD_POLL_MS = 250L;
    private static final long USER_QUERY_INTERVAL_MS = 2000L;
    private static final int MAX_APPLY_ATTEMPTS = 3;
    private static final String ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static final String EXTRA_USER_HANDLE = "android.intent.extra.user_handle";
    private static final int KIND_BRIGHTNESS = 0, KIND_LUX = 1, KIND_INVALIDATE = 2;
    private static final long HOME_WARN_INTERVAL_MS = 10 * 60_000L;

    private static XSharedPreferences prefs;
    private static Handler handler;
    private static Handler rxHandler;   // 只用来接收设置页的广播，不做任何可能阻塞的事
    private static Context ctx;

    // ---- hook 线程 → 处理线程 ----
    private static final class Sample {
        final int kind;
        final float value;
        final long time;
        /** 入队时已经丢过多少个采样。处理线程靠它知道"丢失"发生在哪两个采样之间 */
        final int dropSeq;

        Sample(int kind, float value, long time, int dropSeq) {
            this.kind = kind;
            this.value = value;
            this.time = time;
            this.dropSeq = dropSeq;
        }
    }

    private static final ArrayBlockingQueue<Sample> samples = new ArrayBlockingQueue<>(256);
    private static final AtomicInteger drops = new AtomicInteger();   // 因队列满而丢弃的采样总数
    private static final AtomicBoolean drainQueued = new AtomicBoolean();
    private static volatile int sensorState = -1;                     // -1 未知 / 0 停用 / 1 启用
    /** 设置页每发一次"要保存了"或"保存结束"就加一。处理线程据此知道配置可能变了 */
    private static final AtomicInteger prefsEpoch = new AtomicInteger();
    /** 设置页正在保存：在此时刻之前不要动手。"保存结束"或设置页进程死亡时清零 */
    private static volatile long saveHoldUntil;
    /** 正在监视的那次保存。只在接收线程和 Binder 死亡回调里读写，用 SAVE_LOCK 保护 */
    private static SaveWatcher saveWatcher;
    private static final Object SAVE_LOCK = new Object();

    /** 设置页进程的死亡回调：进程在保存途中被杀时，立刻解除等待并重读配置 */
    private static final class SaveWatcher implements IBinder.DeathRecipient {
        final IBinder token;

        SaveWatcher(IBinder token) {
            this.token = token;
        }

        @Override
        public void binderDied() {
            synchronized (SAVE_LOCK) {
                if (saveWatcher != this) return;   // 已经是更早一次保存的凭证了，与当前的等待无关
                saveWatcher = null;
            }
            releaseSaveHold();
        }
    }
    /** 是否 hook 到了传感器启停。没有的话无法得知读数何时过期，进入保守模式 */
    private static volatile boolean sensorLifecycleHooked;

    // ---- 以下全部只在处理线程读写 ----
    private static boolean prefsLoaded;
    private static long lastPrefsCheck;
    private static boolean enabled = true, useLux, requireAuto = true, respectAuto = true, debug;
    private static boolean homeOnly = true;
    private static float darkBelow = Prefs.DEF_B_DARK, lightAbove = Prefs.DEF_B_LIGHT;
    private static long delayMs = 10_000L;

    private static boolean rangeLoaded, reloadReceiverRegistered, userReceiverRegistered;
    private static long nextReceiverAttempt;
    private static float rangeMin = 0f, rangeMax = 1f;

    private static float rawBrightness = Float.NaN;  // 线性亮度 0..1
    private static float lux = Float.NaN;

    private static int pendingTarget;    // 正在等待的目标，0 = 无
    private static long pendingDue;      // FIRE 应当执行的时刻
    private static int pendingGen;       // 每次排队/作废自增
    /** 每个用户"当前所在区已处理过的目标"。按用户分开，互不影响 */
    private static final HashMap<Integer, Integer> handled = new HashMap<>();
    private static int cachedUser = -1;  // 前台用户（由用户切换广播维护；收不到广播时定期查询）
    private static long lastUserQuery = Long.MIN_VALUE / 2;
    private static long userSince = Long.MIN_VALUE / 2;   // 当前前台用户从何时起在前台（倒计时不早于此刻起算）
    private static long rulesSince = Long.MIN_VALUE / 2;  // 当前切换配置从何时起生效（倒计时不早于此刻起算）
    private static int seenDrops;        // 已经处理过的丢失计数
    private static int seenPrefsEpoch;   // 上一次检查配置文件时的 prefsEpoch
    private static boolean waitingHome;  // 条件已满足，正在等回到桌面
    private static int homeProbe;        // 0 未探测 / 1 getActivityType() / 2 configuration 路径
    private static long lastDebugLog, lastHomeWarn = Long.MIN_VALUE / 2, lastUserWarn = Long.MIN_VALUE / 2;

    private static final Runnable DRAIN = ThemeController::drain;
    private static final Runnable FIRE = ThemeController::fire;
    private static final Runnable RELOAD = ThemeController::onReloadRequested;

    static void init(ClassLoader cl) {
        prefs = new XSharedPreferences(Prefs.PKG, Prefs.FILE);
        HandlerThread t = new HandlerThread("AdaptiveTheme");
        t.start();
        handler = new Handler(t.getLooper());
        HandlerThread rx = new HandlerThread("AdaptiveTheme-rx");
        rx.start();
        rxHandler = new Handler(rx.getLooper());

        Class<?> abc = XposedHelpers.findClassIfExists(ABC, cl);
        if (abc == null) {
            log("未找到 " + ABC + "，此 ROM 可能使用了自定义自动亮度实现");
            return;
        }

        // Android 9-10 返回 int(0-255)，11+ 返回 float(0-1)，14+ 有带 BrightnessEvent 的重载
        int n1 = XposedBridge.hookAllMethods(abc, "getAutomaticScreenBrightness", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object r = p.getResult();
                float v;
                if (r instanceof Float) v = (Float) r;
                else if (r instanceof Integer) v = (Integer) r / 255f;
                else return;
                if (Float.isNaN(v) || v < 0) return;  // 未就绪时为 NaN / -1
                offer(p.thisObject, KIND_BRIGHTNESS, v);
            }
        }).size();

        // handleLightSensorEvent(long time, float lux)
        int n2 = XposedBridge.hookAllMethods(abc, "handleLightSensorEvent", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                for (Object a : p.args) {
                    if (a instanceof Float) {
                        float v = (Float) a;
                        if (v >= 0) offer(p.thisObject, KIND_LUX, v);
                        return;
                    }
                }
            }
        }).size();

        // setLightSensorEnabled(boolean)：关闭自动亮度、熄屏、控制器被替换时系统都会用 false 调它
        int n3 = XposedBridge.hookAllMethods(abc, "setLightSensorEnabled", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof Boolean) {
                    onSensorState(p.thisObject, (Boolean) p.args[0]);
                }
            }
        }).size();
        sensorLifecycleHooked = n3 > 0;

        log("已 hook: getAutomaticScreenBrightness x" + n1 + ", handleLightSensorEvent x" + n2
                + ", setLightSensorEnabled x" + n3);
        if (!sensorLifecycleHooked) {
            log("监听不到光线传感器启停：改为始终要求自动亮度开启，且保存配置后等下一次新读数才生效");
        }
    }

    // ===================== hook 线程 =====================

    private static void offer(Object abc, int kind, float v) {
        if (!accept(abc)) return;
        Sample s = new Sample(kind, v, SystemClock.elapsedRealtime(), drops.get());
        if (!samples.offer(s)) drops.incrementAndGet();   // 队列满：记一次丢失（丢的若是"作废"标记也一样处理）
        if (drainQueued.compareAndSet(false, true)) handler.post(DRAIN);
    }

    /** 传感器从启用变为停用时，往队列里放一个"读数作废"标记，与采样保持先后顺序 */
    private static void onSensorState(Object abc, boolean enabled) {
        if (!accept(abc)) return;
        int s = enabled ? 1 : 0;
        if (sensorState == s) return;    // configure() 会反复用同一个值调用，只关心变化
        sensorState = s;
        if (!enabled) offer(abc, KIND_INVALIDATE, Float.NaN);
    }

    /**
     * 每块屏幕有自己的 AutomaticBrightnessController。只接受默认显示屏（displayId 0）的那个，
     * 否则副屏的亮度会驱动全局主题。结论缓存在实例上（弱引用，控制器被系统重建后会重新判定）。
     * 读不到 displayId 的系统（Android 10 及以下只有一个控制器，或字段被 ROM 改名）全部接受。
     */
    private static boolean accept(Object abc) {
        if (abc == null) return true;
        Object cached = XposedHelpers.getAdditionalInstanceField(abc, KEY_ACCEPT);
        if (cached != null) return (Boolean) cached;

        boolean ok;
        String note;
        try {
            Object owner = XposedHelpers.getObjectField(abc, "mCallbacks");   // DisplayPowerController
            int displayId = XposedHelpers.getIntField(owner, "mDisplayId");
            ok = displayId == DEFAULT_DISPLAY;
            note = "displayId=" + displayId + (ok ? "，采用" : "，忽略");
        } catch (Throwable t) {
            ok = true;
            note = "读不到 displayId，采用（多屏设备上副屏可能影响结果）";
        }
        XposedHelpers.setAdditionalInstanceField(abc, KEY_ACCEPT, ok);
        final String msg = "亮度控制器 @" + Integer.toHexString(System.identityHashCode(abc)) + " " + note;
        handler.post(() -> log(msg));
        return ok;
    }

    // ===================== 处理线程 =====================

    private static void drain() {
        drainQueued.set(false);
        setup();
        long now = SystemClock.elapsedRealtime();
        if (reloadPrefs(false)) restep(now);
        processSamples();

        if (debug && now - lastDebugLog > 5000) {
            lastDebugLog = now;
            log(String.format(Locale.US, "亮度条≈%.0f%% (线性 %.2f%%) lux=%.1f pending=%d handled=%d%s",
                    sliderPercent(), rawBrightness * 100f, lux, pendingTarget, handledFor(currentUserCached()),
                    waitingHome ? " 等待桌面" : ""));
        }
    }

    /**
     * 按到达顺序逐个处理采样，每个采样用它自己的时间戳推进状态机。
     *
     * 丢失的处理：被丢弃的采样一定比当时队列里的所有采样都新。每个采样带着入队时的丢失计数，
     * 所以能准确知道丢失发生在哪里：
     *  - 采样的计数比已处理的大 → 它之前丢过数据：先作废读数，再把它当作全新的读数处理；
     *  - 采样的计数比已处理的小 → 它早于一次已知的丢失：已经过期，直接跳过；
     *  - 队列排空后计数仍有增长 → 最后一个采样之后又丢过：作废读数，等下一个新采样。
     * 最后这步检查在排空之后才做，所以排空期间发生的丢失也不会漏掉。
     */
    private static void processSamples() {
        Sample s;
        while ((s = samples.poll()) != null) {
            int d = s.dropSeq - seenDrops;
            if (d < 0) continue;
            if (d > 0) {
                seenDrops = s.dropSeq;
                invalidate("采样队列溢出");
            }
            if (s.kind == KIND_INVALIDATE) {
                invalidate("光线传感器已停用");
            } else {
                boolean isLux = s.kind == KIND_LUX;
                if (isLux) lux = s.value;
                else rawBrightness = s.value;
                // 只有所选来源的新读数才推进状态机。否则另一个来源的采样会让
                // 所选来源的旧缓存"借尸还魂"，重新开始计时
                if (isLux == useLux) step(s.time);
            }
        }
        int total = drops.get();
        if (total != seenDrops) {
            seenDrops = total;
            invalidate("采样队列溢出");
        }
    }

    /** 缓存的读数不再可信：清空并作废等待，直到来源给出新的采样 */
    private static void invalidate(String why) {
        boolean had = !Float.isNaN(rawBrightness) || !Float.isNaN(lux);
        rawBrightness = Float.NaN;
        lux = Float.NaN;
        if (pendingTarget != 0) cancel();
        if (had && debug) log("读数作废（" + why + "），等待新的采样");
    }

    /**
     * 配置变化后的重新判断。读数来自缓存而不是新采样，所以只有在能监听传感器启停
     *（缓存一旦过期就会被清空）时才这样做；否则等下一个新采样。
     */
    private static void restep(long now) {
        if (sensorLifecycleHooked) step(now);
        else invalidate("配置已变更，且无法确认缓存读数仍然有效");
    }

    /** 用当前数值推进状态机；t 是这个数值生效的时刻 */
    private static void step(long t) {
        int user = currentUserCached();   // 先确认前台用户：若发现换了人，上一位的倒计时在这里就被作废
        int target = enabled ? targetFor(currentValue()) : 0;
        if (target == 0 || target == handledFor(user)) {   // 滞回区 / 当前用户在本区已处理 → 作废等待
            if (pendingTarget != 0) cancel();
            return;
        }
        if (target == pendingTarget) return;
        // 延迟从"这个读数生效""这位用户到前台""当前配置生效"三者中最晚的时刻起算
        long start = Math.max(t, Math.max(userSince, rulesSince));
        long due = start + delayMs;
        if (due < start) due = Long.MAX_VALUE / 4;   // 防溢出（delayMs 已有上限，这里只是兜底）
        schedule(target, due);
    }

    private static void schedule(int target, long due) {
        pendingTarget = target;
        pendingDue = due;
        pendingGen++;
        handler.removeCallbacks(FIRE);
        handler.postDelayed(FIRE, Math.max(0L, due - SystemClock.elapsedRealtime()));
    }

    private static void cancel() {
        pendingTarget = 0;
        waitingHome = false;
        pendingGen++;
        handler.removeCallbacks(FIRE);
    }

    private static void fire() {
        long now = SystemClock.elapsedRealtime();

        // 1. 配置可能在排队期间被改过（比如刚保存了"禁用"）：先重读，变了就按新配置从头判断
        if (reloadPrefs(true)) {
            restep(now);
            return;
        }
        // 2. 把还没处理的采样（以及丢失记录、传感器停用标记）处理完；它们可能已让这次等待作废或改期
        processSamples();
        if (pendingTarget == 0 || now < pendingDue) return;

        final int target = pendingTarget;
        final int gen = pendingGen;
        if (!enabled || targetFor(currentValue()) != target) { cancel(); return; }

        try {
            Context c = context();
            for (int attempt = 0; attempt < MAX_APPLY_ATTEMPTS; attempt++) {
                // 3. 配置文件最先查：它可能有磁盘 I/O，是这一轮里最可能阻塞的一步，
                //    所以后面的系统状态都在它之后才查。第 0 轮刚在第 1 步查过，不重复
                if (attempt > 0 && reloadPrefs(true)) {
                    restep(SystemClock.elapsedRealtime());
                    return;
                }

                // 4. 查询系统状态。每一轮都全部现查，不复用上一轮的结果；前台用户放在最后查
                PowerManager pm = c.getSystemService(PowerManager.class);
                if (pm != null && !pm.isInteractive()) { cancel(); return; }   // 熄屏：等亮屏后的新采样
                // 监听不到传感器启停时，自动亮度开关是判断"读数还在更新"的唯一依据，所以强制检查
                if ((requireAuto || !sensorLifecycleHooked) && !autoBrightnessOn(c)) { cancel(); return; }

                UiModeManager um = c.getSystemService(UiModeManager.class);
                if (um == null) { cancel(); return; }
                int cur = um.getNightMode();
                // 系统处于自动/定时模式且设置为不干预，或者已经是目标主题：不需要切
                boolean needSwitch = cur != target
                        && !(respectAuto && (cur == NIGHT_AUTO || cur == NIGHT_CUSTOM));

                if (needSwitch && homeOnly && !onHome(c)) {
                    // 前台不是（或无法确认是）桌面：保持等待，稍后再查
                    if (!waitingHome && debug) log("条件已满足，等待回到桌面后切换");
                    waitingHome = true;
                    schedule(target, now + HOME_POLL_MS);
                    return;
                }

                int user = queryForegroundUser();
                if (user >= 0 && setForegroundUser(user, SystemClock.elapsedRealtime())) {
                    // 前台用户换了：正在执行的倒计时属于上一位用户，已作废。为新用户从现在起重新计时
                    step(SystemClock.elapsedRealtime());
                    return;
                }
                if (!needSwitch) {
                    markHandled(user, target);
                    cancel();
                    return;
                }
                boolean secondary = user != SELF_USER;
                if (user < 0 || (secondary && !canSetForCurrentUser(cur))) {
                    // 不知道前台是谁，或者只能按用户 0 保存：宁可不切，稍后再看
                    warnUser(user);
                    schedule(target, now + ERROR_RETRY_MS);
                    return;
                }

                // 5. 最后一眼，全部是内存操作，和下面的系统调用之间不再有任何阻塞步骤。
                //    a) 设置页正在保存（已通知、还没写完）：现在读文件读不到新内容，先不动手，等它结束
                long look = SystemClock.elapsedRealtime();
                if (look < saveHoldUntil) {
                    schedule(target, look + SAVE_HOLD_POLL_MS);
                    return;
                }
                //    b) 第 3 步之后设置页保存过：回到第 3 步重读文件
                if (prefsEpoch.get() != seenPrefsEpoch) continue;
                //    c) 上面几步期间到达的采样、丢失、传感器停用标记：先处理，这次等待若仍然有效就整套重来
                if (queueDirty()) {
                    processSamples();
                    if (pendingGen != gen) return;   // 这次等待已被作废或改期，状态机已自行处理
                    continue;
                }

                // 6. 立即动手
                if (secondary) {
                    // setNightModeActivated 按"当前前台用户"保存（Android 12+）
                    Object ok = XposedHelpers.callMethod(um, "setNightModeActivated", target == NIGHT_YES);
                    if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException("setNightModeActivated 被系统拒绝");
                    int after = um.getNightMode();
                    if (after != target) {
                        // 极短间隙里系统恰好变成了自动/定时：这个接口此时只会设临时覆盖。无法撤销，如实记录
                        log("注意：切换瞬间系统深色模式已变为 " + after + "，本次只产生了临时覆盖");
                    }
                } else {
                    um.setNightMode(target);
                }
                log(String.format(Locale.US, "切换到%s (亮度条≈%.0f%% lux=%.1f%s)",
                        target == NIGHT_YES ? "深色" : "浅色", sliderPercent(), lux,
                        secondary ? " 用户 " + user : ""));
                markHandled(user, target);
                cancel();
                return;
            }
            // 连续几轮都在查询期间被新情况打断：先不切，稍后再试
            schedule(target, now + HOME_POLL_MS);
        } catch (Throwable t) {
            // 保留等待并定时重试；即使之后没有新的亮度事件也会重试。离开该区时照常作废
            schedule(target, now + ERROR_RETRY_MS);
            log("切换失败，60 秒后重试: " + t);
        }
    }

    /** 纯内存、不阻塞：队列里有没有东西、有没有丢过采样 */
    private static boolean queueDirty() {
        return !samples.isEmpty() || drops.get() != seenDrops;
    }

    private static int handledFor(int user) {
        Integer h = handled.get(user);
        return h == null ? 0 : h;
    }

    private static void markHandled(int user, int target) {
        handled.put(user, target);
    }

    /** 状态机用的前台用户：有用户切换广播时用广播维护的值，否则最多每 2 秒查一次 */
    private static int currentUserCached() {
        if (userReceiverRegistered && cachedUser >= 0) return cachedUser;
        long now = SystemClock.elapsedRealtime();
        if (now - lastUserQuery >= USER_QUERY_INTERVAL_MS) {
            lastUserQuery = now;
            setForegroundUser(queryForegroundUser(), now);
        }
        return cachedUser;
    }

    /**
     * 记录前台用户。换了人就作废正在进行的倒计时（它是上一位用户的），并记下新用户到前台的时刻。
     * @return true 表示前台用户变了
     */
    private static boolean setForegroundUser(int user, long now) {
        if (user == cachedUser) return false;
        cachedUser = user;
        userSince = now;
        if (pendingTarget != 0) cancel();
        return true;
    }

    private static void onUserSwitched(int user) {
        long now = SystemClock.elapsedRealtime();
        lastUserQuery = now;
        processSamples();                                    // 切换前到达的采样，先算在上一位用户头上处理完
        setForegroundUser(user >= 0 ? user : queryForegroundUser(), now);   // 作废上一位用户的倒计时
        // 新用户在当前光线下可能还没处理过：用现有读数重新判断，延迟从现在起算
        //（读数是否可信的规则与重读配置时相同）
        if (sensorLifecycleHooked) step(now);
    }

    private static int queryForegroundUser() {
        try {
            return (Integer) XposedHelpers.callStaticMethod(ActivityManager.class, "getCurrentUser");
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 前台是副用户时能否正确切换。setNightModeActivated 只在固定深/浅色之间翻转，
     * 系统处于自动/定时模式时它只会设一个临时覆盖，所以那种情况不处理。
     */
    private static boolean canSetForCurrentUser(int cur) {
        return Build.VERSION.SDK_INT >= 31 && (cur == NIGHT_YES || cur == NIGHT_NO);
    }

    private static void warnUser(int user) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUserWarn < HOME_WARN_INTERVAL_MS) return;
        lastUserWarn = now;
        log(user < 0 ? "无法得知当前前台用户，暂不切换"
                : "前台是用户 " + user + "，此系统上无法只为该用户保存主题，暂不切换（不会改动用户 0 的设置）");
    }

    private static boolean autoBrightnessOn(Context c) {
        ContentResolver cr = c.getContentResolver();
        try {   // 多用户下读取当前用户的设置
            return (Integer) XposedHelpers.callStaticMethod(Settings.System.class, "getIntForUser",
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, USER_CURRENT)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Throwable t) {
            return Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        }
    }

    /**
     * 前台任务是否确定为桌面或最近任务界面。
     * 只有明确读到类型才返回 true；列表为空、查询失败、类型读不到一律返回 false（继续等待）。
     */
    @SuppressWarnings("deprecation")
    private static boolean onHome(Context c) {
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) {
                warnHome("前台任务列表为空");
                return false;
            }
            int type = activityType(tasks.get(0));
            return type == ACTIVITY_TYPE_HOME || type == ACTIVITY_TYPE_RECENTS;
        } catch (Throwable t) {
            warnHome(String.valueOf(t));
            return false;
        }
    }

    private static void warnHome(String why) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastHomeWarn < HOME_WARN_INTERVAL_MS) return;
        lastHomeWarn = now;
        log("无法确认前台是否为桌面（" + why + "），暂不切换。若一直如此，请在模块设置里关闭「仅在桌面切换」");
    }

    private static int activityType(Object task) {
        if (homeProbe != 2) {
            try {
                int v = (Integer) XposedHelpers.callMethod(task, "getActivityType");
                homeProbe = 1;
                return v;
            } catch (Throwable ignored) {
                // 旧版本没有这个方法，走下面的字段路径
            }
        }
        Object cfg = XposedHelpers.getObjectField(task, "configuration");
        Object wc = XposedHelpers.getObjectField(cfg, "windowConfiguration");
        int v = (Integer) XposedHelpers.callMethod(wc, "getActivityType");
        homeProbe = 2;
        return v;
    }

    private static float currentValue() {
        return useLux ? lux : sliderPercent();
    }

    private static float sliderPercent() {
        return linearToSliderPercent(rawBrightness, rangeMin, rangeMax);
    }

    /**
     * 自动亮度输出的是线性背光值，而亮度条是感知曲线（与 SettingsLib BrightnessUtils 相同的
     * HLG 曲线）。线性 8% 实际约等于亮度条 49%，所以必须换算后再和阈值比较。
     */
    static float linearToSliderPercent(float v, float min, float max) {
        if (Float.isNaN(v)) return Float.NaN;
        float n = (v - min) / (max - min);
        n = Math.max(0f, Math.min(1f, n)) * 12f;
        float g = n <= 1f
                ? (float) Math.sqrt(n) * 0.5f
                : 0.17883277f * (float) Math.log(n - 0.28466892f) + 0.55991073f;
        return Math.max(0f, Math.min(1f, g)) * 100f;
    }

    private static int targetFor(float v) {
        if (Float.isNaN(v)) return 0;
        if (v <= darkBelow) return NIGHT_YES;
        if (v >= lightAbove) return NIGHT_NO;
        return 0;
    }

    // ---------- 一次性初始化（需要系统服务就绪，所以放在首个采样到来时） ----------

    private static void setup() {
        if (!rangeLoaded) loadRange();
        if (!reloadReceiverRegistered || !userReceiverRegistered) registerReceivers();
    }

    /** 读取系统亮度条的最小/最大线性值；失败时按 0..1 估算 */
    private static void loadRange() {
        rangeLoaded = true;
        try {
            PowerManager pm = context().getSystemService(PowerManager.class);
            float min, max;
            if (Build.VERSION.SDK_INT >= 30) {
                min = (Float) XposedHelpers.callMethod(pm, "getBrightnessConstraint", 0);
                max = (Float) XposedHelpers.callMethod(pm, "getBrightnessConstraint", 1);
            } else {
                min = (Integer) XposedHelpers.callMethod(pm, "getMinimumScreenBrightnessSetting") / 255f;
                max = (Integer) XposedHelpers.callMethod(pm, "getMaximumScreenBrightnessSetting") / 255f;
            }
            if (min >= 0f && max > min && max <= 1f) {
                rangeMin = min;
                rangeMax = max;
            }
            log(String.format(Locale.US, "亮度范围 min=%.4f max=%.4f", rangeMin, rangeMax));
        } catch (Throwable t) {
            log("读取亮度范围失败，按 0–1 估算: " + t);
        }
    }

    /**
     * 两个接收器：
     *  - 配置保存：设置页保存前发"要保存了"、保存后发"保存结束"。要求发送方持有 App 声明的签名级权限，
     *    其他应用的广播不会送达。这个接收器在独立的接收线程上回调，只改内存变量；
     *    真正的重读仍在处理线程上做，且连发会合并成一次。
     *  - 用户切换：系统广播，在处理线程上回调。用来维护前台用户，并让新用户在当前光线下得到处理。
     * 注册失败时每 30 秒重试；期间配置在下次亮度变化时生效，前台用户改为定期查询。
     */
    private static void registerReceivers() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextReceiverAttempt) return;
        nextReceiverAttempt = now + 30_000;

        if (!reloadReceiverRegistered) {
            try {
                BroadcastReceiver r = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        // 在接收线程上执行，处理线程此刻可能正卡在 fire() 的某次系统查询里。
                        // 这里只改内存变量、投递任务，绝不做会阻塞的事
                        if (i != null && Prefs.ACTION_PREPARE.equals(i.getAction())) {
                            // 设置页即将写文件。它会等这个回调返回之后才写，所以这里的赋值一定先于文件变化
                            onSavePrepared(saveTokenOf(i));
                        } else {
                            // 保存结束（成功或失败）
                            synchronized (SAVE_LOCK) {
                                unwatchLocked();
                            }
                            releaseSaveHold();
                        }
                    }
                };
                IntentFilter f = new IntentFilter(Prefs.ACTION_RELOAD);
                f.addAction(Prefs.ACTION_PREPARE);
                if (Build.VERSION.SDK_INT >= 33) {
                    context().registerReceiver(r, f, Prefs.PERMISSION_RELOAD, rxHandler, Context.RECEIVER_EXPORTED);
                } else {
                    context().registerReceiver(r, f, Prefs.PERMISSION_RELOAD, rxHandler);
                }
                reloadReceiverRegistered = true;
            } catch (Throwable t) {
                log("注册配置广播失败（配置仍会在下次亮度变化时生效）: " + t);
            }
        }

        if (!userReceiverRegistered) {
            try {
                BroadcastReceiver r = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        onUserSwitched(i != null ? i.getIntExtra(EXTRA_USER_HANDLE, -1) : -1);
                    }
                };
                IntentFilter f = new IntentFilter(ACTION_USER_SWITCHED);
                if (Build.VERSION.SDK_INT >= 33) {
                    context().registerReceiver(r, f, null, handler, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context().registerReceiver(r, f, null, handler);
                }
                setForegroundUser(queryForegroundUser(), now);
                userReceiverRegistered = true;
            } catch (Throwable t) {
                log("注册用户切换广播失败（改为定期查询前台用户）: " + t);
            }
        }
    }

    private static IBinder saveTokenOf(Intent i) {
        try {
            Bundle b = i.getBundleExtra(Prefs.EXTRA_SAVE_TOKEN);
            return b != null ? b.getBinder(Prefs.KEY_SAVE_TOKEN) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 接收线程：设置页说"要保存了" */
    private static void onSavePrepared(IBinder token) {
        long hold = SAVE_HOLD_MS;
        synchronized (SAVE_LOCK) {
            unwatchLocked();
            if (token != null) {
                SaveWatcher w = new SaveWatcher(token);
                try {
                    token.linkToDeath(w, 0);
                    saveWatcher = w;
                    hold = SAVE_HOLD_MAX_MS;   // 有存活凭证：进程死了会立刻得到通知，所以可以放心等久一点
                } catch (Throwable dead) {
                    // 通知送到时设置页已经死了：这次保存不会发生，不进入等待，但保险起见重读一次
                    releaseSaveHold();
                    return;
                }
            }
        }
        saveHoldUntil = SystemClock.elapsedRealtime() + hold;
        prefsEpoch.incrementAndGet();
    }

    private static void unwatchLocked() {
        SaveWatcher w = saveWatcher;
        saveWatcher = null;
        if (w != null) {
            try {
                w.token.unlinkToDeath(w, 0);
            } catch (Throwable ignored) {
                // 对方已死或从未登记成功，都无所谓
            }
        }
    }

    /**
     * 保存结束（成功、失败，或设置页进程已死）。可能在接收线程或 Binder 线程上调用，只改内存并投递任务。
     * 先加计数再解除等待，保证处理线程要么看到"仍在等待"、要么看到"计数变了"，不会两头落空。
     */
    private static void releaseSaveHold() {
        prefsEpoch.incrementAndGet();
        saveHoldUntil = 0;
        handler.removeCallbacks(RELOAD);
        handler.postDelayed(RELOAD, RELOAD_COALESCE_MS);
    }

    private static void onReloadRequested() {
        long now = SystemClock.elapsedRealtime();
        boolean changed = reloadPrefs(true);
        processSamples();
        if (changed) restep(now);
    }

    /**
     * @param skipThrottle false 时最多每 2 秒检查一次文件（采样路径用）；true 时立即检查
     * @return true 表示切换行为相关的配置值变了，等待已作废，调用方应当 restep() 重新判断。
     *         读到的值与当前相同（或只有调试开关变化）时返回 false，状态原样保留。
     */
    private static boolean reloadPrefs(boolean skipThrottle) {
        long now = SystemClock.elapsedRealtime();
        if (prefsLoaded && !skipThrottle && now - lastPrefsCheck < 2000) return false;
        lastPrefsCheck = now;
        // 在碰文件之前取计数：这次检查只能保证看到"此刻之前"写完的保存
        seenPrefsEpoch = prefsEpoch.get();
        if (prefsLoaded && !prefs.hasFileChanged()) return false;
        prefs.reload();

        boolean nEnabled = prefs.getBoolean(Prefs.ENABLED, true);
        boolean nUseLux = Prefs.SRC_LUX.equals(prefs.getString(Prefs.SOURCE, Prefs.SRC_BRIGHTNESS));
        float d, l;
        float[] th;
        if (nUseLux) {
            d = Prefs.parse(prefs.getString(Prefs.L_DARK, null), Prefs.DEF_L_DARK);
            l = Prefs.parse(prefs.getString(Prefs.L_LIGHT, null), Prefs.DEF_L_LIGHT);
            th = Prefs.sanitizeLux(d, l);
        } else {
            d = Prefs.parse(prefs.getString(Prefs.B_DARK, null), Prefs.DEF_B_DARK);
            l = Prefs.parse(prefs.getString(Prefs.B_LIGHT, null), Prefs.DEF_B_LIGHT);
            th = Prefs.sanitizeBrightness(d, l);   // 保证两个阈值都落在亮度条能到达的 0..100 内
        }
        // 延迟有上限（1 小时）：既避免"填错后看起来像坏了"，也保证算截止时间时不会溢出
        long nDelay = (long) (Prefs.sanitizeDelay(
                Prefs.parse(prefs.getString(Prefs.DELAY_SEC, null), Prefs.DEF_DELAY_SEC)) * 1000);
        boolean nRequireAuto = prefs.getBoolean(Prefs.REQUIRE_AUTO, true);
        boolean nRespectAuto = prefs.getBoolean(Prefs.RESPECT_SYSTEM_AUTO, true);
        boolean nHomeOnly = prefs.getBoolean(Prefs.HOME_ONLY, true);

        // "哪种光线对应哪种主题"的规则变了 → 之前"已处理"的结论不再成立，要重新判断。
        // 只是"什么时候动手"变了（延迟、仅桌面、仅自动亮度）→ 重排等待即可，已处理的区（含手动选择）保持。
        // 都没变（只改了调试开关）→ 什么都不动。
        boolean first = !prefsLoaded;
        boolean rulesChanged = first || nEnabled != enabled || nUseLux != useLux
                || th[0] != darkBelow || th[1] != lightAbove || nRespectAuto != respectAuto;
        boolean timingChanged = nDelay != delayMs || nRequireAuto != requireAuto || nHomeOnly != homeOnly;

        enabled = nEnabled;
        useLux = nUseLux;
        darkBelow = th[0];
        lightAbove = th[1];
        delayMs = nDelay;
        requireAuto = nRequireAuto;
        respectAuto = nRespectAuto;
        homeOnly = nHomeOnly;
        boolean nDebug = prefs.getBoolean(Prefs.DEBUG, false);
        boolean debugChanged = nDebug != debug;
        debug = nDebug;
        prefsLoaded = true;

        // 注意：这里判断的是"读到的值有没有变"，不是"框架说文件变没变"。
        // 有的框架在配置文件不存在时（全新安装、尚未保存）每次都报告"已变化"，
        // 读出来仍是同一组默认值，这种情况必须当作没变，否则会一直重读、永远切不了。
        if (!rulesChanged && !timingChanged) {
            if (debugChanged) log("调试日志已" + (debug ? "开启" : "关闭") + "，切换规则未变，状态保持");
            return false;
        }
        if (rulesChanged && (th[0] != d || th[1] != l)) {
            log(String.format(Locale.US, "保存的阈值 %.1f / %.1f 无法同时达到，已改用 %.1f / %.1f", d, l, th[0], th[1]));
        }
        cancel();
        rulesSince = now;   // 新配置从此刻起生效：之后的倒计时不早于此刻起算，哪怕队列里还有更早的采样
        if (rulesChanged) handled.clear();
        log(String.format(Locale.US, "配置已加载: %s%s 深色<=%.1f 浅色>=%.1f 延迟%ds 仅桌面=%b%s",
                enabled ? "" : "已禁用 ", useLux ? "lux" : "亮度条%", darkBelow, lightAbove,
                delayMs / 1000, homeOnly, rulesChanged ? "" : "（规则未变，仅重排等待）"));
        return true;
    }

    private static Context context() {
        if (ctx == null) {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            ctx = (Context) XposedHelpers.callMethod(at, "getSystemContext");
        }
        return ctx;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }

    private ThemeController() {}
}
