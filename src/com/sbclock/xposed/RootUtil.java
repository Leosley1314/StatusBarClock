package com.sbclock.xposed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 通过 su 执行 shell 脚本（写配置、重启 SystemUI、读日志）。
 *
 * root 方案在不同设备/管理器下把 su 放在不同位置（Magisk 在 /system/bin 或 /sbin、
 * KernelSU 在 /data/adb/ksu/bin、APatch 在 /data/adb/apd），因此这里逐个尝试，
 * 不预先用 File.exists 判断（普通 app 无权 stat /data/adb，会误判为不存在）。
 */
public final class RootUtil {

    private static final String[] SU_BINS = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
    };

    private static volatile String sWorkingBin;

    public static final class Result {
        public final boolean ok;
        public final String output;

        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output == null ? "" : output;
        }

        public boolean success() {
            return ok && output.contains("__SBC_OK__");
        }
    }

    private RootUtil() {
    }

    public static Result run(String script, long timeoutMs) {
        if (sWorkingBin != null) {
            Result r = runWith(sWorkingBin, script, timeoutMs);
            if (r.ok) {
                return r;
            }
            sWorkingBin = null;
        }
        StringBuilder errs = new StringBuilder();
        for (String bin : SU_BINS) {
            Result r = runWith(bin, script, timeoutMs);
            if (r.ok) {
                sWorkingBin = bin;
                return r;
            }
            if (!r.output.startsWith("su 不可用")) {
                errs.append(bin).append(" -> ").append(r.output.trim()).append('\n');
            }
        }
        return new Result(false, errs.length() > 0
                ? errs.toString()
                : "su 不可用：请在 KernelSU / Magisk / APatch 中为本应用授权 root");
    }

    private static Result runWith(final String bin, String script, long timeoutMs) {
        final Process proc;
        try {
            proc = new ProcessBuilder(bin).redirectErrorStream(true).start();
        } catch (Throwable t) {
            return new Result(false, "su 不可用：" + t.getMessage());
        }
        try {
            Thread watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(timeoutMs);
                        proc.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            });
            watchdog.setDaemon(true);
            watchdog.start();

            OutputStream os = proc.getOutputStream();
            os.write(script.getBytes("UTF-8"));
            os.flush();
            os.close();

            String out = readAll(proc.getInputStream());
            proc.waitFor();
            return new Result(proc.exitValue() == 0, out);
        } catch (Throwable t) {
            return new Result(false, "执行失败：" + t.getMessage());
        } finally {
            try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public static Result apply(Cfg c) {
        String tag = "SBCFG_" + System.nanoTime();
        String script = "cat > " + Cfg.PATH + " <<'" + tag + "'\n"
                + c.serialize()
                + tag + "\n"
                + "chmod 644 " + Cfg.PATH + "\n"
                + "echo __SBC_OK__\n";
        return run(script, 30000);
    }

    public static Result restartSystemUI() {
        String script = "P=$(pidof com.android.systemui)\n"
                + "if [ -n \"$P\" ]; then kill -9 $P; fi\n"
                + "echo __SBC_OK__\n";
        return run(script, 25000);
    }

    /**
     * 一次 su 里把判定「是否激活」需要的东西全取回来：
     *
     * - `SBC_HOOK`：SystemUI 注入时写进 Settings.Global 的时间戳（毫秒）。
     *   普通应用在 HyperOS 上直接读 Global 会被拦成 0，所以这里用 su 读。
     * - `SBC_ACT`：SystemUI 进程的启动时刻（毫秒），由 `/proc/<pid>/stat` 第 22 字段
     *   （开机以来的时钟滴答数，CLK_TCK=100）配合 `/proc/uptime` 反推。
     *
     * 判定「是否激活」= 注入时间戳晚于当前 SystemUI 进程的启动时刻。
     * 只看时间戳是否大于 0 会在 SystemUI 重启后误报：旧时间戳还在，新进程其实没被注入。
     */
    public static Result activationProbe() {
        String script = "echo SBC_HOOK $(settings get global sbclock_hook_at)\n"
                + "P=$(pidof com.android.systemui)\n"
                + "ST=$(awk '{print $22}' /proc/$P/stat 2>/dev/null)\n"
                + "UP=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)\n"
                + "echo SBC_ACT $P $ST $UP $(date +%s)\n"
                // 顺带把配置文件里的总开关值取回来：界面状态一律以「配置里真正写着的」为准，
                // 免得 SharedPreferences 与配置文件不同步时界面自说自话。
                + "echo SBC_EN $(grep -m1 '^enabled=' /data/system/sbclock.conf 2>/dev/null)\n"
                + "echo __SBC_OK__\n";
        return run(script, 15000);
    }

    public static Result checkRoot() {
        return run("id\necho __SBC_OK__\n", 30000);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
            if (buf.size() > 256 * 1024) {
                break;
            }
        }
        return buf.toString("UTF-8");
    }
}
