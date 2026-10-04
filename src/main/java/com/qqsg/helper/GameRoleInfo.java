package com.qqsg.helper;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 QQ三国 客户端当前登录的角色信息（角色名 / 登录账号）。
 *
 * <p><b>数据来源</b>：游戏安装目录下的运行日志
 * <pre>
 *   &lt;游戏目录&gt;\Logs\QQSG_&lt;PID&gt;_&lt;yyyyMMdd&gt;_&lt;时间戳&gt;.log
 * </pre>
 * 登录完成后客户端会把角色名与账号写进日志：
 * <pre>
 *   [09-20 18:04:38.093][05288][App]拉取Cross票据:账号292789419成功获取Cross登录票据!
 *   [09-20 18:04:39.629][05288][App]RoleName : 良良和姗姗
 * </pre>
 * 日志文件名中的数字就是进程 PID，因此可以精确地把「游戏窗口 → 角色」对应起来，
 * 多开时每个客户端各写各的日志，互不干扰。
 *
 * <p><b>为什么不用其它办法</b>
 * <ul>
 *   <li>游戏窗口标题只含区服与线路（如「三足鼎立 16线」），没有角色信息；</li>
 *   <li>读游戏进程内存确实能拿到角色名，但会触发 ACE 反作弊，有封号风险 ——
 *       本类只读取磁盘上的文本日志，不触碰游戏进程。</li>
 * </ul>
 *
 * <p>日志是追加写入的，同一客户端在选人界面切换角色后会追写新的 {@code RoleName} 行，
 * 所以这里一律取<b>最后一条</b>记录。
 */
public final class GameRoleInfo {

    /** 游戏日志统一使用 GBK 编码（角色名是中文）。 */
    private static final Charset LOG_CHARSET = Charset.forName("GBK");

    /** 日志文件名：QQSG_<PID>_<yyyyMMdd>_<时间戳>.log */
    private static final Pattern LOG_FILE_NAME =
            Pattern.compile("^QQSG_(\\d+)_(\\d{8})_(\\d+)\\.log$", Pattern.CASE_INSENSITIVE);

    /** 角色名行：...RoleName : 良良和姗姗 */
    private static final Pattern P_ROLE_NAME =
            Pattern.compile("RoleName\\s*[:：]\\s*([^\\r\\n]+)");

    /** 账号行：...拉取Cross票据:账号292789419成功获取Cross登录票据! */
    private static final Pattern P_ACCOUNT =
            Pattern.compile("账号\\s*(\\d{5,})");

    /** 解析结果缓存时长：窗口列表刷新频繁，避免重复读盘。 */
    private static final long CACHE_TTL_MS = 3000L;
    private static final Map<Integer, Cached> CACHE = new HashMap<>();

    private static final class Cached {
        final GameRoleInfo info;
        final long time;
        Cached(GameRoleInfo info, long time) { this.info = info; this.time = time; }
    }

    private final int pid;
    private final String roleName;
    private final String account;
    private final String logFileName;
    private final String error;

    private GameRoleInfo(int pid, String roleName, String account, String logFileName, String error) {
        this.pid = pid;
        this.roleName = roleName;
        this.account = account;
        this.logFileName = logFileName;
        this.error = error;
    }

    // ==================== 对外接口 ====================

    public int getPid() {
        return pid;
    }

    /** 角色名；未登录或解析失败时返回 null。 */
    public String getRoleName() {
        return roleName;
    }

    public boolean hasRoleName() {
        return roleName != null && !roleName.isEmpty();
    }

    /** 登录账号（QQ 号）；解析不到返回 null。 */
    public String getAccount() {
        return account;
    }

    /** 命中的日志文件名，便于排查。 */
    public String getLogFileName() {
        return logFileName;
    }

    /** 解析失败原因；成功时为 null。 */
    public String getError() {
        return error;
    }

    /** 用于界面悬浮提示的多行说明。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("角色：").append(hasRoleName() ? roleName : "（未识别）");
        if (account != null) {
            sb.append("\n账号：").append(account);
        }
        sb.append("\nPID：").append(pid);
        if (logFileName != null) {
            sb.append("\n日志：").append(logFileName);
        }
        if (error != null) {
            sb.append("\n说明：").append(error);
        }
        return sb.toString();
    }

    /**
     * 解析指定进程对应的角色信息（带短时缓存，避免频繁读盘）。
     *
     * @param pid 游戏进程 PID，取自窗口所属进程
     */
    public static GameRoleInfo resolve(int pid) {
        if (pid <= 0) {
            return new GameRoleInfo(pid, null, null, null, "无效的 PID");
        }
        synchronized (CACHE) {
            Cached c = CACHE.get(pid);
            if (c != null && System.currentTimeMillis() - c.time < CACHE_TTL_MS) {
                return c.info;
            }
        }
        GameRoleInfo info = load(pid);
        synchronized (CACHE) {
            CACHE.put(pid, new Cached(info, System.currentTimeMillis()));
        }
        return info;
    }

    /** 清空缓存，强制下次调用重新读取日志。 */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    // ==================== 内部实现 ====================

    private static GameRoleInfo load(int pid) {
        String exePath = queryProcessPath(pid);
        if (exePath == null || exePath.isEmpty()) {
            return new GameRoleInfo(pid, null, null, null, "无法获取该进程的可执行文件路径");
        }

        File exeFile = new File(exePath);
        String exeName = exeFile.getName();
        if (!exeName.toLowerCase().startsWith("qqsg")) {
            // 快速排除非游戏进程，避免对每个窗口都去猜日志目录
            return new GameRoleInfo(pid, null, null, null, "非 QQ三国 进程（" + exeName + "）");
        }

        File gameDir = exeFile.getParentFile();
        if (gameDir == null || !gameDir.isDirectory()) {
            return new GameRoleInfo(pid, null, null, null, "无法定位游戏安装目录");
        }

        File logDir = new File(gameDir, "Logs");
        if (!logDir.isDirectory()) {
            return new GameRoleInfo(pid, null, null, null, "未找到日志目录：" + logDir.getAbsolutePath());
        }

        File log = findLatestLog(logDir, pid);
        if (log == null) {
            return new GameRoleInfo(pid, null, null, null,
                    "未找到 PID " + pid + " 的日志文件（客户端可能还没登录过）");
        }

        String text;
        try {
            text = new String(Files.readAllBytes(log.toPath()), LOG_CHARSET);
        } catch (Exception e) {
            return new GameRoleInfo(pid, null, null, log.getName(),
                    "读取日志失败：" + e.getMessage());
        }

        String role = lastGroup(P_ROLE_NAME, text);
        String account = lastGroup(P_ACCOUNT, text);

        String err = null;
        if (role == null) {
            err = "日志中还没有 RoleName 记录（角色尚未进入游戏）";
        }
        return new GameRoleInfo(pid, role, account, log.getName(), err);
    }

    /**
     * 在日志目录中找到属于指定 PID 的、时间最新的那个日志文件。
     * <p>文件名形如 {@code QQSG_02464_20260920_180404569.log}，
     * 其中的 PID 是零填充的，但位数并不固定，所以这里解析成整数比较。
     */
    private static File findLatestLog(File logDir, int pid) {
        File[] files = logDir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        File best = null;
        String bestKey = null;
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            Matcher m = LOG_FILE_NAME.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }
            int filePid;
            try {
                filePid = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (filePid != pid) {
                continue;
            }
            // yyyyMMdd + 归一化后的时间戳，可直接按字典序比较
            String key = m.group(2) + String.format("%015d", parseLong(m.group(3)));
            if (bestKey == null || key.compareTo(bestKey) > 0) {
                bestKey = key;
                best = f;
            }
        }
        return best;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 取正则最后一次匹配到的分组内容（日志切换角色后会追加新记录）。 */
    private static String lastGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        String found = null;
        while (m.find()) {
            String g = m.group(1);
            if (g != null) {
                g = g.trim();
                if (!g.isEmpty()) {
                    found = g;
                }
            }
        }
        return found;
    }

    /** 用 JNA 查询进程的可执行文件完整路径。 */
    private static String queryProcessPath(int pid) {
        WinNT.HANDLE handle = null;
        try {
            handle = Kernel32.INSTANCE.OpenProcess(
                    WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (isNullHandle(handle)) {
                return null;
            }
            char[] buffer = new char[2048];
            IntByReference size = new IntByReference(buffer.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(handle, 0, buffer, size)) {
                return null;
            }
            int len = size.getValue();
            if (len <= 0 || len > buffer.length) {
                len = buffer.length;
            }
            return new String(buffer, 0, len).trim();
        } catch (Throwable t) {
            System.err.println("[ROLE INFO] 查询进程路径失败 pid=" + pid + " : " + t.getMessage());
            return null;
        } finally {
            if (!isNullHandle(handle)) {
                try {
                    Kernel32.INSTANCE.CloseHandle(handle);
                } catch (Throwable ignored) {
                    // 忽略关闭失败
                }
            }
        }
    }

    private static boolean isNullHandle(WinNT.HANDLE handle) {
        if (handle == null) {
            return true;
        }
        Pointer p = handle.getPointer();
        return p == null || Pointer.nativeValue(p) == 0L;
    }
}
