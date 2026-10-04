package com.qqsg.helper;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 内置 OCR：把图片交给系统自带的 Windows OCR 引擎识别，拿回<b>带坐标的文字行</b>。
 *
 * <h3>为什么要用它</h3>
 * 「推举孝廉」是答题任务，题目和选项都以文字形式出现在画面上 —— 纯像素检测搞不定，
 * 必须知道「屏幕上写了什么字、写在哪里」。本机是中文 Windows，自带 {@code zh-Hans-CN}
 * OCR 语言包，调用它比引入 Tesseract 之类的第三方库省事得多，而且不用安装任何东西。
 *
 * <h3>开销（实测）</h3>
 * 引擎创建约 40ms，单张图 8~150ms，<b>含 PowerShell 启动总共约 0.5 秒</b>。
 * 所以每题调一次完全来得及（题库每题限时 15 秒），不需要做成常驻进程。
 *
 * <h3>实现方式</h3>
 * jar 里打包着 {@code /xiaolian/ocr.ps1}，第一次用到时释放到临时目录，
 * 然后 {@code powershell -File ocr.ps1 -Dir <目录> -Out <结果文件>}，
 * 结果文件按行读取解析（见脚本头部的格式说明）。
 */
public final class OcrLite {

    /** 一行的识别结果，坐标是「传给 OCR 的那张图」的像素坐标。 */
    public static final class Line {
        public final int x0;
        public final int y0;
        public final int x1;
        public final int y1;
        public final String text;

        Line(int x0, int y0, int x1, int y1, String text) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.text = text;
        }

        public int cx() {
            return (x0 + x1) / 2;
        }

        public int cy() {
            return (y0 + y1) / 2;
        }

        public int chars() {
            return text.length();
        }

        @Override
        public String toString() {
            return "(" + x0 + "," + y0 + "-" + x1 + "," + y1 + ") " + text;
        }
    }

    /** 一张图的识别结果。 */
    public static final class Result {
        public final String file;
        public final int width;
        public final int height;
        public final List<Line> lines = new ArrayList<>();
        public String error;

        Result(String file, int width, int height) {
            this.file = file;
            this.width = width;
            this.height = height;
        }

        /** 按上到下、左到右排好序的行。 */
        public List<Line> sorted() {
            List<Line> copy = new ArrayList<>(lines);
            Collections.sort(copy, (a, b) -> a.y0 != b.y0 ? Integer.compare(a.y0, b.y0)
                    : Integer.compare(a.x0, b.x0));
            return copy;
        }
    }

    /** 释放出来的脚本文件（只释放一次）。 */
    private static File script;
    private static String lastError = "";

    private OcrLite() {
    }

    /** 最近一次失败原因。 */
    public static String lastError() {
        return lastError;
    }

    /** 供 {@link OcrServer} 写入失败原因。 */
    static synchronized void setLastError(String msg) {
        lastError = msg;
    }

    /** 供 {@link OcrServer} 取已释放出来的 ocr.ps1。 */
    static File scriptFile() {
        return ensureScript();
    }

    /** 本机有没有可用的中文 OCR 引擎。 */
    public static synchronized boolean available() {
        return ensureScript() != null;
    }

    /** 把 jar 里的 ocr.ps1 释放到临时目录。 */
    private static synchronized File ensureScript() {
        try {
            byte[] jarBytes = readScriptFromJar();
            if (jarBytes == null) {
                // 开发期直接从源码目录读
                File dev = new File("src/main/resources/xiaolian/ocr.ps1");
                if (dev.exists()) {
                    script = dev;
                    return script;
                }
                lastError = "jar 里缺少 /xiaolian/ocr.ps1";
                return null;
            }
            File dir = new File(System.getProperty("java.io.tmpdir"), "qqsg_ocr");
            if (!dir.exists() && !dir.mkdirs()) {
                lastError = "无法创建临时目录 " + dir;
                return null;
            }
            File f = new File(dir, "ocr.ps1");
            // 2026-09-29 修正：旧实现只在「文件不存在」时释放，导致升级后
            // 临时目录里一直留着<b>老脚本</b>（服务模式参数不存在 → 起不来）。
            // 现在比对内容，不一致就重写。
            if (f.exists() && sameContent(f, jarBytes)) {
                script = f;
                return script;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(jarBytes);
            }
            script = f;
            return script;
        } catch (Throwable t) {
            lastError = "释放 ocr.ps1 失败：" + t;
            return null;
        }
    }

    /** 读 jar 里的 ocr.ps1 字节（读不到返回 null）。 */
    private static byte[] readScriptFromJar() {
        try (InputStream in = OcrLite.class.getResourceAsStream("/xiaolian/ocr.ps1")) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 磁盘文件内容是否与给定字节一致。 */
    private static boolean sameContent(File f, byte[] expect) {
        try {
            if (f.length() != expect.length) {
                return false;
            }
            byte[] cur = Files.readAllBytes(f.toPath());
            if (cur.length != expect.length) {
                return false;
            }
            for (int i = 0; i < cur.length; i++) {
                if (cur[i] != expect[i]) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 识别一批图片。
     *
     * @param images 待识别的 PNG 文件（按文件名排序后依次识别）
     * @return 每张图一个结果；整体失败时返回空表并设置 {@link #lastError()}
     */
    public static List<Result> recognize(List<File> images, int timeoutSeconds) {
        if (images == null || images.isEmpty()) {
            return out0();
        }
        // ① 优先进常驻服务（省掉每次 ~0.45s 的 PowerShell 冷启 + 引擎初始化）
        List<Result> fast = OcrServer.recognize(images.get(0).getParentFile(), timeoutSeconds);
        if (fast != null) {
            return fast;
        }
        // ② 回退：一次性的 powershell -File 老路子（服务不可用时才走到这）
        return recognizeOneShot(images, timeoutSeconds);
    }

    private static List<Result> out0() {
        return new ArrayList<>();
    }

    /**
     * 老路径：每次调用冷启一个 PowerShell 进程。
     *
     * <p>2026-09-29 起只在常驻服务不可用时兜底使用（正常路径见 {@link OcrServer}）。
     * 保留它是因为：服务可能因语言包/权限/异常退出而不可用，任务不能因此全线瘫痪。
     */
    static List<Result> recognizeOneShot(List<File> images, int timeoutSeconds) {
        List<Result> out = new ArrayList<>();
        if (images == null || images.isEmpty()) {
            return out;
        }
        File ps = ensureScript();
        if (ps == null) {
            return out;
        }
        File dir = images.get(0).getParentFile();
        File outFile;
        try {
            outFile = File.createTempFile("qqsg_ocr_", ".txt");
        } catch (Throwable t) {
            lastError = "无法创建结果文件：" + t;
            return out;
        }

        Process p = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("powershell.exe");
            cmd.add("-NoProfile");
            cmd.add("-NonInteractive");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-File");
            cmd.add(ps.getAbsolutePath());
            cmd.add("-Dir");
            cmd.add(dir.getAbsolutePath());
            cmd.add("-Out");
            cmd.add(outFile.getAbsolutePath());
            cmd.add("-Lang");
            cmd.add("zh-Hans-CN");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();

            // 把 stdout 读干，避免管道写满把子进程卡死
            final Process fp = p;
            Thread drain = new Thread(() -> {
                try {
                    InputStream in = fp.getInputStream();
                    byte[] buf = new byte[4096];
                    while (in.read(buf) > 0) {
                        // 丢弃：脚本本身不往 stdout 写东西
                    }
                } catch (Throwable ignore) {
                    // 进程被销毁时读管道会抛异常，忽略
                }
            }, "ocr-drain");
            drain.setDaemon(true);
            drain.start();

            boolean done = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                lastError = "OCR 超时（" + timeoutSeconds + "s）";
                return out;
            }
            return parse(outFile);
        } catch (Throwable t) {
            lastError = "调用 OCR 失败：" + t;
            return out;
        } finally {
            if (p != null) {
                p.destroy();
            }
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }
    }

    /** 解析脚本写出的结果文件。 */
    private static List<Result> parse(File outFile) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(outFile.toPath(), StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = br.readLine()) != null) {
                lines.add(raw);
            }
        } catch (Throwable t) {
            lastError = "解析 OCR 结果失败：" + t;
            return new ArrayList<>();
        }
        return parseLines(lines);
    }

    /**
     * 解析 OCR 输出行（<b>单次模式与服务模式共用同一套格式</b>，保证两边行为一致）。
     *
     * <p>包级可见：{@link OcrServer} 从常驻进程 stdout 收到行后直接交给它。
     *
     * @return 逐步构建的结果表；永不返回 null（异常时返回已收集的部分）
     */
    static List<Result> parseLines(List<String> rawLines) {
        List<Result> list = new ArrayList<>();
        Result cur = null;
        if (rawLines == null) {
            return list;
        }
        for (String raw : rawLines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            if (line.startsWith("FILE=")) {
                String body = line.substring(5);
                String[] parts = body.split("\\|");
                String name = parts[0];
                int w = 0, h = 0;
                String err = null;
                for (int i = 1; i < parts.length; i++) {
                    String[] kv = parts[i].split("=", 2);
                    if (kv.length == 2) {
                        if ("W".equals(kv[0])) {
                            w = parseInt(kv[1]);
                        } else if ("H".equals(kv[0])) {
                            h = parseInt(kv[1]);
                        } else if ("ERR".equals(kv[0])) {
                            err = kv[1];
                        }
                    }
                }
                // 每个 FILE= 行都开一条全新记录：v1/v2/v3 是各自独立的版本，
                // 合并进同一条会导致 scaleOf() 对全部行按同一缩放换算坐标 → 点击位置错位。
                cur = new Result(name, w, h);
                if (err != null) {
                    cur.error = err;
                }
                list.add(cur);
                continue;
            }
            if (line.startsWith("LINE=") && cur != null) {
                String body = line.substring(5);
                int bar = body.indexOf('|');
                if (bar <= 0) {
                    continue;
                }
                String[] xy = body.substring(0, bar).split(",");
                if (xy.length < 4) {
                    continue;
                }
                String text = body.substring(bar + 1);
                cur.lines.add(new Line(parseInt(xy[0]), parseInt(xy[1]),
                        parseInt(xy[2]), parseInt(xy[3]), text));
                continue;
            }
            if (line.startsWith("FATAL=") || line.startsWith("ERROR=")) {
                lastError = line;
            }
        }
        return list;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ==================== 便捷方法 ====================

    /**
     * 准备一个「干净的作业目录」，专门放这次要识别的图。
     *
     * <p>为什么需要：脚本是按<b>目录</b>批量识别的。如果把裁剪图直接存进任务自己的截图目录
     * （截图会一题一张地累积），第 10 题就会把前 9 题的图重新识别一遍 —— 又慢又会被旧结果污染。
     * 所以每次识别前先清空一个独立目录，只放当前这一张。
     *
     * <p>目录名带线程 ID：同时控制多个游戏窗口时互不干扰。
     */
    public static File prepareJobDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "qqsg_ocr_job_" + Thread.currentThread().getId());
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 把一张图放大 {@code scale} 倍后存成 PNG（OCR 对小字识别率不高，放大后明显更准）。 */
    public static File saveScaled(BufferedImage img, int scale, File dst) {
        return saveForOcr(img, scale, dst, MODE_ORIGINAL);
    }

    /** 预处理方式。 */
    public static final int MODE_ORIGINAL = 0;
    /** 自适应二值化：亮像素→白、暗像素→黑（深底浅字仍是浅字）。 */
    public static final int MODE_BINARY = 1;
    /** 自适应二值化后反色：黑字压白底 —— <b>这是 Windows OCR 最擅长的形态</b>。 */
    public static final int MODE_BINARY_INV = 2;
    /** 灰度 + 自动色阶 + 反色（不做二值化，保留抗锯齿）。 */
    public static final int MODE_INVERT = 3;

    /**
     * 按指定预处理方式放大并保存，供 OCR 使用。
     *
     * <p>为什么要预处理：游戏 UI 基本都是「浅色字 + 深色底」，而 Windows OCR 主要是在
     * 「黑字白底」的语料上训练的，直接送进去会漏行（实测合成题图里的「剑」「15」
     * 这种短选项直接没被识别出来）。二值化 + 反色之后就能救回来。
     */
    public static File saveForOcr(BufferedImage img, int scale, File dst, int mode) {
        try {
            int w = img.getWidth() * scale;
            int h = img.getHeight() * scale;
            BufferedImage src = img;
            if (mode != MODE_ORIGINAL) {
                src = preprocess(img, mode);
            }
            BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = target.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            ImageIO.write(target, "png", dst);
            return dst;
        } catch (Throwable t) {
            lastError = "保存图片失败：" + t;
            return null;
        }
    }

    /** 灰度 + 自动色阶（按 2%/98% 分位拉伸），去掉半透明遮罩带来的整体偏暗。 */
    private static BufferedImage preprocess(BufferedImage img, int mode) {
        int w = img.getWidth(), h = img.getHeight();
        int[] gray = new int[w * h];
        int[] hist = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int v = (r * 299 + g * 587 + b * 114) / 1000;
                gray[y * w + x] = v;
                hist[v]++;
            }
        }
        // 自动色阶
        int lo = 0, hi = 255, total = w * h, acc = 0;
        int cut = Math.max(1, total / 50);
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc >= cut) {
                lo = v;
                break;
            }
        }
        acc = 0;
        for (int v = 255; v >= 0; v--) {
            acc += hist[v];
            if (acc >= cut) {
                hi = v;
                break;
            }
        }
        if (hi - lo < 12) {
            lo = 0;
            hi = 255;
        }
        int span = hi - lo;
        for (int i = 0; i < gray.length; i++) {
            int v = (gray[i] - lo) * 255 / span;
            gray[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }

        int thr = 140;
        if (mode == MODE_BINARY || mode == MODE_BINARY_INV) {
            // 用自适应阈值（Otsu），比固定阈值更稳
            thr = otsu(gray);
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < gray.length; i++) {
            int v;
            if (mode == MODE_BINARY) {
                v = gray[i] > thr ? 255 : 0;
            } else if (mode == MODE_BINARY_INV) {
                v = gray[i] > thr ? 0 : 255;
            } else { // MODE_INVERT
                v = 255 - gray[i];
            }
            out.setRGB(i % w, i / w, (v << 16) | (v << 8) | v);
        }
        return out;
    }

    /** Otsu 自适应阈值。 */
    private static int otsu(int[] gray) {
        int[] hist = new int[256];
        long sum = 0;
        for (int v : gray) {
            hist[v]++;
            sum += v;
        }
        int total = gray.length;
        long sumB = 0;
        int wB = 0;
        double best = -1;
        int thr = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) {
                continue;
            }
            int wF = total - wB;
            if (wF == 0) {
                break;
            }
            sumB += (long) t * hist[t];
            double mB = sumB / (double) wB;
            double mF = (sum - sumB) / (double) wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                thr = t;
            }
        }
        return thr;
    }
}
