package com.qqsg.helper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OCR 常驻服务：把 {@code ocr.ps1 -Serve} 拉起来挂着，之后每次识别只发一个目录路径。
 *
 * <h3>为什么要它（2026-09-29 实测）</h3>
 * 原来每识别一张图都要冷启一个 PowerShell 进程：
 * <pre>
 *   单次 OCR 总耗时 0.750s = 冷启固定开销 0.451s + 真正识别 0.299s
 * </pre>
 * 军团任务一轮里要识别十几次，光冷启动就白等十几秒。改成常驻后：
 * <pre>
 *   启动一次 0.447s（全程只付一次），之后每次识别仅 0.149~0.287s
 * </pre>
 *
 * <h3>协议</h3>
 * stdin 收到「作业目录绝对路径」→ stdout 回 {@code BEGIN} / {@code FILE=…}/{@code LINE=…} /
 * {@code END}；启动完成先回一行 {@code READY}。
 *
 * <h3>安全设计（任务不能因为加速而瘫痪）</h3>
 * <ul>
 *   <li>懒启动：第一次用到才拉进程。</li>
 *   <li>任何一步失败（启动超时、进程死了、读不到 END、解析异常）都<b>返回 null</b>，
 *       调用方 {@link OcrLite#recognize} 自动回退到「每次冷启」的老路子。</li>
 *   <li>进程死了下次自动重启（{@link #alive()} 检查 + {@link #stop()}）。</li>
 *   <li>整个类加锁串行化 —— 一次只发一个识别请求（OCR 引擎本身也不是并发安全的）。</li>
 *   <li>JVM 退出时关掉子进程（shutdown hook），不留孤儿 PowerShell。</li>
 * </ul>
 */
final class OcrServer {

    /** 常驻进程；null = 尚未启动或已关闭。 */
    private static Process proc;
    private static BufferedWriter toProc;
    private static BufferedReader fromProc;
    /** 启动失败的冷却时间戳：避免每次都去撞一个起不来的进程。 */
    private static long retryAfter = 0;
    /** 启动超时（毫秒）。实测 READY 约 0.45s，给足余量。 */
    private static final long START_TIMEOUT_MS = 15000;
    /** 连续失败到阈值后不再尝试（本次运行内彻底回退老路径）。 */
    private static int failStreak = 0;
    private static final int FAIL_LIMIT = 3;
    private static boolean disabled = false;

    private OcrServer() {
    }

    /** 服务是否可用（供日志/探针查看）。 */
    static boolean isUp() {
        return proc != null && proc.isAlive();
    }

    /** 取当前失败连击数与是否已放弃（探针用）。 */
    static int failStreak() {
        return failStreak;
    }

    static boolean disabled() {
        return disabled;
    }

    /**
     * 走常驻服务识别一个目录里的所有 png。
     *
     * @return 识别结果；<b>服务不可用或出错返回 null</b>（调用方回退老路径）
     */
    static synchronized List<OcrLite.Result> recognize(File dir, int timeoutSeconds) {
        if (dir == null || disabled) {
            return null;
        }
        if (System.currentTimeMillis() < retryAfter) {
            return null;
        }
        try {
            if (!ensureStarted()) {
                return null;
            }
            toProc.write(dir.getAbsolutePath());
            toProc.newLine();
            toProc.flush();

            List<String> lines = new ArrayList<>();
            long deadline = System.currentTimeMillis() + Math.max(5, timeoutSeconds) * 1000L;
            boolean ended = false;
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    break;   // 超时 → 当作服务坏了
                }
                // 用 ready 判断，避免 readLine 永久阻塞把任务卡死
                if (!fromProc.ready()) {
                    if (!proc.isAlive()) {
                        break;   // 进程死了
                    }
                    Thread.sleep(5);
                    continue;
                }
                String l = fromProc.readLine();
                if (l == null) {
                    break;       // 流关闭
                }
                if ("END".equals(l)) {
                    ended = true;
                    break;
                }
                lines.add(l);
            }

            if (!ended) {
                noteFailure("服务模式未收到 END");
                return null;
            }
            List<OcrLite.Result> res = OcrLite.parseLines(lines);
            if (res == null) {
                noteFailure("服务模式结果解析失败");
                return null;
            }
            failStreak = 0;      // 成功即重置连击
            return res;
        } catch (Throwable t) {
            noteFailure("服务模式异常：" + t);
            return null;
        }
    }

    /** 确认常驻进程起来（含 READY 等待）。 */
    private static synchronized boolean ensureStarted() {
        if (isUp() && toProc != null && fromProc != null) {
            return true;
        }
        stop();   // 清掉可能残留的死进程
        if (disabled) {
            return false;
        }
        try {
            File ps = OcrLite.scriptFile();
            if (ps == null) {
                noteFailure("释放 ocr.ps1 失败");
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", ps.getAbsolutePath(),
                    "-Serve");
            pb.redirectErrorStream(true);
            proc = pb.start();
            toProc = new BufferedWriter(new OutputStreamWriter(
                    proc.getOutputStream(), StandardCharsets.UTF_8));
            fromProc = new BufferedReader(new InputStreamReader(
                    proc.getInputStream(), StandardCharsets.UTF_8));

            // 等 READY
            long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                if (!fromProc.ready()) {
                    if (!proc.isAlive()) {
                        break;
                    }
                    Thread.sleep(5);
                    continue;
                }
                String l = fromProc.readLine();
                if (l == null) {
                    break;
                }
                if ("READY".equals(l.trim())) {
                    ready = true;
                    break;
                }
                if (l.startsWith("FATAL=")) {
                    OcrLite.setLastError("OCR 服务启动失败：" + l);
                    break;
                }
            }
            if (!ready) {
                noteFailure("服务模式启动未就绪（超时/进程退出）");
                return false;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(OcrServer::stop, "ocr-server-stop"));
            return true;
        } catch (Throwable t) {
            noteFailure("启动服务模式异常：" + t);
            return false;
        }
    }

    /** 记一次失败；连击到上限就本次运行内彻底回退老路径，不再反复撞。 */
    private static void noteFailure(String why) {
        failStreak++;
        OcrLite.setLastError(why);
        retryAfter = System.currentTimeMillis() + 5000;   // 5s 内不再重试
        if (failStreak >= FAIL_LIMIT) {
            disabled = true;
        }
        stop();
    }

    /** 关掉常驻进程（幂等）。 */
    static synchronized void stop() {
        try {
            if (toProc != null) {
                toProc.close();
            }
        } catch (Throwable ignore) {
            // 关闭失败无妨，下面直接 kill
        }
        try {
            if (proc != null && proc.isAlive()) {
                proc.destroy();
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        proc = null;
        toProc = null;
        fromProc = null;
    }
}
