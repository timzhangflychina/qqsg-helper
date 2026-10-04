package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * 「抢线」执行器（2026-09-23 用户口述 + 2 张实机截图机器标定）。
 *
 * <h3>完整流程</h3>
 * <pre>
 *  0. <b>先判断角色在不在军团里</b>（2026-09-23 用户要求：在军团里切不了线，得先回城）。
 *     判据 = 右上角<b>地图名条</b>：军团地图 / 军团大厅没有小地图面板，名条顶在工具栏
 *     正下方（实测窗口 y≈63）；城镇的名条在小地图下方（y≈164）。
 *     ① 整窗 OCR 读到「军团」/「大厅」→ 在军团；
 *     ② 没读到就裁下那一条放大 4x 单独 OCR，只要能读出字（哪怕只是坐标「（7，2）」）
 *        就说明名条顶在上面 → 也是军团类地图。
 *     判定在军团 → 按一次 T 用回城符回城；不在 → 跳过，不白费一张回城符。
 *  1. <b>脚本自己按 ESC</b> 打开「系统」菜单（2026-09-23 用户要求：不再等玩家按，省掉几秒）。
 *     按之前先 OCR 看菜单是不是已经开着 —— ESC 是<b>开关</b>，连按两次会把菜单又关掉。
 *  2. OCR 找「服务器选线」并点击 → 弹出选线面板（16 条线路，2 列 × 8 行）。
 *  3. 循环（最多 {@value #MAX_ROUNDS} 轮，中途可再点按钮中止）：
 *       a. 先确认选线面板还在（忙的时候那个提示框会把面板一起关掉）→ 不在就重开；
 *       b. 整窗 OCR <b>一次</b>，从行文本里解析出每条「N线（状态）」的位置；
 *       c. 目标线认出来了 → 点 OCR 实测坐标；没认出来 → 退固定点位表；
 *       d. <b>先查屏幕正中间的「线路繁忙」提示框</b>：有 → 按 Enter 关掉它 → 回到 a 接着抢；
 *       e. 没弹窗 且 选线面板消失 = <b>真的进线了</b>；
 *       f. 面板还在 → 补点「确定」→ 再查一次弹窗 → 还在 ＝ 该线忙 → 回到 a 继续抢。
 * </pre>
 *
 * <h3>为什么「查弹窗」必须排在「判面板」前面</h3>
 * 提示框是模态的，弹出来会盖住选线面板的文字 —— 如果先判面板，OCR 读不到面板内容会
 * 得出「面板没了」，正好被判成<b>进线成功</b>（假成功）。所以点完线路第一件事就是找弹窗，
 * 只要屏幕中间弹了「繁忙」，这一轮一律按<b>失败</b>处理：关掉提示框、继续抢。
 *
 * <h3>为什么用「行首数字正则」而不是字符串包含</h3>
 * 选线面板里「1线」是「11线」的子串 —— 用 {@link ScreenText#find} 那种
 * 「包含即命中」的匹配，抢 1 线会点到 11 线上。这里改成对每行做
 * <code>^(\d{1,2})线</code> 取<b>行首</b>数字，天然区分开。
 *
 * <h3>标定数据（机器测量，非目测）</h3>
 * 用 {@code _dev/ocr_box.ps1} 对用户 2026-09-23 的两张截图（1026x795，×2 后 OCR）
 * 导出行框，再换算到本项目的窗口坐标基准 1030x797。实测：
 * <ul>
 *   <li>左列（奇数线）文字行中心 x≈467.5 → 基准域 <b>469</b>；右列（偶数线）x≈572 → <b>574</b>；</li>
 *   <li>8 行的行中心 y：303 / 333 / 363 / 393 / 423 / 454 / 484 / 514（行内左奇右偶）；</li>
 *   <li>「当前所在：」行在 y≈556（面板存活的判据之一）；「确定」按钮 (458, 595)；</li>
 *   <li>系统菜单「服务器选线」在 (514, 443)。</li>
 * </ul>
 * 注：实测那张截图里 OCR 认出了 15/16 条线路（5线 漏了），所以固定点位表是必需品，
 * 不是摆设。
 */
public class GrabLineTask {

    /** 进度 / 结果回调。回调在后台线程触发，界面层需自行切回 EDT。 */
    public interface Listener {
        void log(String message);

        void alert(String title, String message);

        void finished(boolean success, String summary);
    }

    /** 用于中断流程的内部异常。 */
    private static class Abort extends RuntimeException {
        Abort(String message) {
            super(message);
        }
    }

    // ==================== 坐标常数（窗口内坐标，基准 1030x797） ====================

    /** 选线面板里线路标签的搜索区，排除底部「当前所在」（y≈556）与聊天区。 */
    private static final int[] ZONE_LINES = {395, 665, 272, 536};
    /** 左列（奇数线）/ 右列（偶数线）的文字行中心 x。 */
    private static final int COL_X_ODD = 469;
    private static final int COL_X_EVEN = 574;
    /** 8 行的行中心 y（第 1 行 = 1线/2线，第 8 行 = 15线/16线）。 */
    private static final int[] ROW_Y = {303, 333, 363, 393, 423, 454, 484, 514};
    /** 「确定」按钮兜底坐标（实测 2x 图 890,1176~936,1198 → 中心 456.5,593.5）。 */
    private static final int[] PT_CONFIRM = {458, 595};
    /** 「确定」按钮的搜索区（面板底部左侧）。 */
    private static final int[] ZONE_CONFIRM = {400, 525, 570, 630};
    /** 系统菜单「服务器选线」兜底坐标（实测 2x 图中心 512,442）。 */
    private static final int[] PT_SERVER_LINE = {514, 443};
    /** 系统菜单 / 选线面板文字的搜索区（中央大面板）。 */
    private static final int[] ZONE_MENU = {300, 780, 220, 620};
    /** 面板存活判据：「当前所在：」那一行（实测 y≈556）。OCR 常把「当」读成「兰」。 */
    private static final int[] ZONE_PANEL_TITLE = {380, 640, 535, 578};
    private static final String[] KW_PANEL_TITLE = {"当前所在", "前所在", "当前所"};
    /** 系统菜单项（先长后短；实测 OCR 把「选线」读成「、选」）。 */
    private static final String[] KW_SERVER_LINE = {"服务器选线", "服务器选", "选线", "服务器"};
    private static final String[] KW_CONFIRM = {"确定", "确宁", "确"};

    // ==================== 「线路繁忙」提示框（2026-09-23 用户要求） ====================

    /**
     * 「线路忙」提示框里出现的词。
     *
     * <p>用户实测：抢线失败时<b>屏幕正中间</b>会弹一个提示框（「线路繁忙 / 请稍后再试」这类），
     * 它不会自己消失，<b>必须按 Enter 或点「确定」才能关掉</b>；关掉后可以接着抢。
     *
     * <p>这里刻意<b>不收录</b>单独的「繁忙」—— 选线面板上自己就写着「9线（繁忙）」，
     * 拿它当判据会每轮都误判成「弹出提示框了」。只取「基本只可能在提示框里出现」的长词。
     */
    private static final String[] KW_BUSY_POPUP = {
            "稍后再试", "稍后重试", "请稍后", "重新再试", "请重试", "再试一次",
            "人数已满", "服务器繁忙", "线路繁忙", "无法进入", "不可进入",
    };

    /**
     * 兜底通用词。命中它们还要再过两道闸：
     * ① 整行不含「所在」（排除面板底部「当前所在：9线（繁忙）」）；
     * ② 行首不是 {@code N线}（排除面板上的线路行）。
     */
    private static final String[] KW_BUSY_WEAK = {"繁忙", "已满", "满了"};

    /**
     * 提示框的搜索区 —— 用户描述是「屏幕正中间」。
     *
     * <p>y 上限取 520 是为了避开选线面板底部 y≈556 的「当前所在：9线（繁忙）」
     * 以及面板自己的「确定」(458,595)。
     */
    private static final int[] ZONE_BUSY_POPUP = {270, 790, 250, 520};

    /** 提示框里「确定」按钮的搜索区（中央偏下，同样避开面板底部那个确定）。 */
    private static final int[] ZONE_POPUP_OK = {330, 720, 300, 520};

    /** 行首「数字 + 线」——抢线的核心解析规则。 */
    private static final Pattern LINE_NO = Pattern.compile("^([0-9lI]{1,2})线");

    // ==================== 「在不在军团」判据（2026-09-23 新增） ====================

    /**
     * 右上角<b>地图名条</b>的搜索区。实机机器测量的两态：
     * <ul>
     *   <li>军团地图：{@code 军团地图（17，6）} 行中心 y≈<b>63</b>、x≈885~1014；</li>
     *   <li>军团大厅：{@code 大厅（7，2）} 同样顶在 y≈63（工具栏正下方）；</li>
     *   <li>城镇（成都·子城 / 成都·罗城）：地图名在小地图<b>下方</b> y≈<b>164</b>，
     *       所以不会落进这个 zone —— 这就是判据的核心。</li>
     * </ul>
     * x 下限取 830 是为了避开这一带可能出现的「热点 / 引导 / 炫装 / 寻路」按钮文字。
     */
    private static final int[] ZONE_MAP_NAME = {830, 1030, 45, 85};

    /**
     * 地图名条<b>裁剪区</b> {x0, y0, x1, y1}（窗口基准域）——判据②用。
     *
     * <p>军团地图 / 军团大厅<b>没有小地图面板</b>，地图名条直接顶在工具栏正下方；
     * 城镇的地图名条在小地图下方（y≈164），这一带是空的小地图 —— 所以
     * 「这一带能 OCR 出文字」就等于「名条顶在上面」= 军团类地图。实测 13 张样本
     * （5 军团 + 8 城镇/罗城/子城）全部分对。
     */
    private static final int[] BOX_MAP_STRIP = {826, 40, 1030, 92};

    /**
     * 名条裁剪图的放大倍数。整窗 OCR 是 2x，但军团大厅的「大厅」二字 2x / 4x 都读不出来，
     * 而同一条上的坐标「（7，2）」在 4x 下能读出来 —— 判据②只看「有没有字」，
     * 所以照样判得出来。
     */
    private static final int STRIP_SCALE = 4;

    /**
     * 地图名里出现这些词 → 判定「角色在军团里」。
     * 先长后短：{@code 军团地图} 比 {@code 军团} 更精确，命中即用。
     * （军团大厅的名条写的是「大厅」，不含「军团」二字，所以单独列出来。）
     */
    private static final String[] KW_LEGION_MAP = {"军团地图", "军团", "大厅"};

    // ==================== 节奏 ====================

    /** 按 ESC 之后等「系统」菜单弹出来的时间（2026-09-23 起由脚本自己按，不再等玩家）。 */
    private static final int ESCAPE_WAIT_MS = 1800;
    private static final int PANEL_WAIT_MS = 2000;
    private static final int LINE_CLICK_WAIT_MS = 900;
    /** 「线路繁忙」提示框：两次采样之间的间隔（防点完线那一帧还没渲染出来）。 */
    private static final int POPUP_CHECK_WAIT_MS = 700;
    /** 关提示框（Enter）之后，等它淡出再复查的间隔。 */
    private static final int POPUP_CLOSE_WAIT_MS = 1100;
    private static final int CONFIRM_WAIT_MS = 1800;
    private static final int SETTLE_MS = 1600;
    private static final int RETRY_WAIT_MS = 1500;
    /** 回城符（T）生效 + 换图加载的等待时间（与其它任务的 TELEPORT_MS 同量级）。 */
    private static final int TOWN_WAIT_MS = 3500;
    /** 按了 T 但还没离开军团时，最多再补按几次（防一次没生效）。 */
    private static final int LEAVE_LEGION_TRIES = 3;
    /** 抢线循环上限（一轮约 4 秒 → 约 4 分钟）；没成功可以再点一次按钮接着抢。 */
    private static final int MAX_ROUNDS = 60;
    /** 「线路繁忙」提示框的现场截图最多留几张（60 轮全存会把目录刷满）。 */
    private static final int BUSY_SHOT_MAX = 3;

    // ==================== 运行状态 ====================

    private final GameWindowController controller;
    private final Listener listener;
    /** 目标线路（1~16）。 */
    private final int targetLine;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;
    /** 已经存了几张「线路繁忙」提示框的现场截图（上限 {@value #BUSY_SHOT_MAX}）。 */
    private int busyShotCount = 0;

    private final File shotDir = new File("grabline_shots");

    public GrabLineTask(GameWindowController controller, Listener listener, int targetLine) {
        this.controller = controller;
        this.listener = listener;
        this.targetLine = targetLine;
    }

    public boolean isRunning() {
        return running;
    }

    public void requestStop() {
        stopRequested = true;
    }

    public void start() {
        if (running) {
            listener.log("抢线任务已在运行中");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                TaskOutcome o = runOnce();
                try {
                    listener.finished(o.ok, o.summary);
                } catch (Throwable ignore) {
                    // 回调异常不影响线程收尾
                }
            }
        }, "GrabLineTask");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步执行一次抢线（供 {@link #start()} 使用）。
     *
     * <p><b>后台模式作用域也在这里</b>：进入时记下用户原来的后台开关并强制开启，
     * 退出时原样还原 —— 与其它任务同款做法（抢线期间用户可能正在用电脑）。
     */
    public TaskOutcome runOnce() {
        if (running) {
            return new TaskOutcome(false, "抢线任务已在运行中");
        }
        if (targetLine < 1 || targetLine > 16) {
            return new TaskOutcome(false, "线路号必须是 1~16，收到 " + targetLine);
        }
        running = true;
        stopRequested = false;
        boolean savedBg = controller.isRunInBackground();
        try {
            controller.setRunInBackground(true);
            listener.log("抢线任务以「后台模式」运行（不抢焦点、不移动鼠标）");
            execute();
            return new TaskOutcome(true, "已切到 " + targetLine + " 线");
        } catch (Abort a) {
            return new TaskOutcome(false, a.getMessage());
        } catch (Throwable e) {
            e.printStackTrace();
            return new TaskOutcome(false, "执行异常：" + e);
        } finally {
            controller.setRunInBackground(savedBg);
            running = false;
        }
    }

    // ==================== 主流程 ====================

    private void execute() {
        listener.log("================ 抢线 · 目标 " + targetLine + " 线 开始 ================");

        Rectangle r = controller.getWindowRect();
        if (r == null) {
            throw new Abort("找不到游戏窗口，请先启动 QQ三国 并确认窗口未关闭");
        }
        listener.log("游戏窗口：" + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");
        controller.ensureWindowVisible();
        if (!OcrLite.available()) {
            listener.log("⚠ OCR 不可用（" + OcrLite.lastError() + "）—— 本轮全部走固定点位表");
        }

        // ---------- 0. 军团里切不了线路 —— 先判在不在军团，在的话按 T 回城 ----------
        ensureOutOfLegion();

        // ---------- 1. 系统菜单 → 点「服务器选线」 ----------
        clickServerLineOption();

        // ---------- 2. 等选线面板 ----------
        if (!waitPanel(4)) {
            String shot = snapshot("anomaly_no_line_panel");
            throw new Abort("点了「服务器选线」但选线面板没出现。\n\n"
                    + "可能原因：面板还没加载完 / 点偏了 / 系统菜单被关掉了。\n\n"
                    + (shot != null ? "现场截图：" + shot : ""));
        }

        // ---------- 3. 抢线循环 ----------
        listener.log("—— 步骤 3：开始抢 " + targetLine + " 线（最多 " + MAX_ROUNDS
                + " 轮，想停就再点一次「抢线」按钮）——");
        int blindStreak = 0;
        int panelFailStreak = 0;
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            checkStop();

            // 3.0 每轮开头先确认选线面板还开着 —— 忙的时候那个提示框可能把面板一起关掉
            if (!ensurePanelOpen(round)) {
                panelFailStreak++;
                if (panelFailStreak >= 3) {
                    String shot = snapshot("anomaly_no_line_panel");
                    throw new Abort("连续 3 轮都打不开选线面板，抢线已中止。\n\n"
                            + "可能原因：游戏里正开着别的窗口挡住了 ESC 菜单 / 点击没生效。\n"
                            + "手工按 ESC 打开系统菜单确认能点到「服务器选线」后，再点一次「抢线」。\n\n"
                            + (shot != null ? "现场截图：" + shot : ""));
                }
                sleep(RETRY_WAIT_MS);
                continue;
            }
            panelFailStreak = 0;

            PanelScan scan = scanPanel();
            if (scan.totalLines == 0) {
                blindStreak++;
                listener.log("  ⚠ 第 " + round + " 轮：OCR 一行都没读到（" + blindStreak + "/3）");
                if (blindStreak >= 3) {
                    String shot = snapshot("anomaly_ocr_blind");
                    throw new Abort("连续 3 轮 OCR 读不到任何文字，无法判断是否已进线。\n\n"
                            + (shot != null ? "现场截图：" + shot : ""));
                }
                sleep(RETRY_WAIT_MS);
                continue;
            }
            blindStreak = 0;

            logPanel(round, scan);

            int[] pt = scan.found != null ? scan.found : fixedPoint(targetLine);
            String src = scan.found != null ? "OCR 实测" : "固定点位（OCR 没认出来）";
            listener.log("  第 " + round + " 轮：点 " + targetLine + " 线 @ (" + pt[0] + "," + pt[1] + ") —— " + src);
            controller.clickWindowPoint(pt[0], pt[1]);
            sleep(LINE_CLICK_WAIT_MS);

            // 3.2 【关键】先看屏幕中间有没有「线路繁忙」提示框 —— 有 = 没抢上，关掉它接着抢。
            //     必须先查弹窗再判面板：弹窗会盖住面板文字，若先判面板就会误判成「面板没了=成功」。
            if (handleBusyPopup(round)) {
                continue;
            }

            // 3.3 没有提示框 → 面板消失 = 真的进线了
            Boolean alive = panelAliveSafe();
            if (Boolean.FALSE.equals(alive)) {
                finish("点选线路后就进线了");
                return;
            }

            // 3.4 面板还在 → 补点「确定」（有的客户端点完线路要点确定才提交）
            listener.log("  面板还在 → 补点「确定」");
            clickConfirm();
            sleep(CONFIRM_WAIT_MS);

            if (handleBusyPopup(round)) {
                continue;
            }

            alive = panelAliveSafe();
            if (Boolean.FALSE.equals(alive)) {
                finish("点「确定」后进线了");
                return;
            }
            if (alive == null) {
                listener.log("  ⚠ 面板状态判断不出来（OCR 读不到字），继续下一轮");
            } else {
                listener.log("  ⚠ 第 " + round + " 轮没进线（" + targetLine + " 线忙），继续抢");
            }
            sleep(600);
        }

        String shot = snapshot("anomaly_grab_timeout");
        throw new Abort("抢了 " + MAX_ROUNDS + " 轮（约 4 分钟）还没进 " + targetLine + " 线。\n\n"
                + "线路一直满 —— 想接着抢就再点一次「抢线」按钮。\n\n"
                + (shot != null ? "现场截图：" + shot : ""));
    }

    /** 进线成功后的收尾日志。 */
    private void finish(String how) {
        sleep(SETTLE_MS);
        snapshot("99_grab_done");
        listener.log("✔ 已进入 " + targetLine + " 线（" + how + "）");
    }

    // ==================== 步骤 0：在军团里不能切线路，先回城 ====================

    /**
     * 抢线前的守卫：<b>角色在军团里就先用一次回城符（T）回城</b>。
     *
     * <p>用户 2026-09-23 口述：在军团地图里切不了线路，得先回城才行。
     * 判据见 {@link #inLegionNow()}（「地图名文字」+「名条位置」两条，任一成立即在军团）。三种结果：
     * <ul>
     *   <li><b>在军团</b> → 按 T 用回城符，最多补按 {@value #LEAVE_LEGION_TRIES} 次，
     *       直到名条不再是军团字样；</li>
     *   <li><b>不在军团</b> → 直接放行，<b>一次 T 都不按</b>（回城符是消耗品，不白费）；</li>
     *   <li><b>判不出来</b>（OCR 不可用 / 一行都没读到）→ 也放行并记日志 ——
     *       宁可后面抢线失败让人看见，也不瞎按浪费道具。</li>
     * </ul>
     */
    private void ensureOutOfLegion() {
        listener.log("—— 步骤 0：判断是否在军团里（在军团要先按 T 回城才能切线路）——");
        Boolean inLegion = inLegionNow();
        if (inLegion == null) {
            listener.log("  ⚠ 地图名读不出来（OCR 不可用或整窗无文字），无法判断是否在军团");
            listener.log("     → 跳过回城符直接抢线；若抢不到，请手工按 T 回城后重试");
            return;
        }
        if (!inLegion) {
            listener.log("  ✔ 不在军团地图 → 直接抢线（不按 T，省一张回城符）");
            return;
        }

        listener.log("  ⚠ 检测到角色在军团里 → 先按 T 用回城符回城");
        for (int i = 1; i <= LEAVE_LEGION_TRIES; i++) {
            checkStop();
            controller.sendKey(WindowUtils.VK_T);
            listener.log("  第 " + i + "/" + LEAVE_LEGION_TRIES + " 次按 T，等 "
                    + (TOWN_WAIT_MS / 1000) + " 秒回城…");
            sleep(TOWN_WAIT_MS);

            Boolean still = inLegionNow();
            if (Boolean.FALSE.equals(still)) {
                listener.log("  ✔ 已离开军团地图，继续抢线");
                return;
            }
            if (still == null) {
                listener.log("  ⚠ 回城后地图名读不出来，按「已离开」处理并继续抢线");
                return;
            }
            listener.log("  ⚠ 地图名还是军团 → 再补按一次 T");
        }

        String shot = snapshot("anomaly_still_in_legion");
        throw new Abort("连按 " + LEAVE_LEGION_TRIES + " 次 T 仍显示在军团里，抢线已中止。\n\n"
                + "常见原因：\n"
                + "  ① 回城符用完了（按 T 没反应）\n"
                + "  ② 有弹窗挡住了按键\n\n"
                + "手工按 T 回到城里后，再点一次「抢线」即可。\n\n"
                + (shot != null ? "现场截图：" + shot : ""));
    }

    /**
     * 角色现在在不在军团里。两条判据，任一成立即「在军团」：
     * <ol>
     *   <li><b>判据①（文字）</b>：整窗 OCR 的地图名条文字里出现「军团」/「大厅」——最直接。</li>
     *   <li><b>判据②（名条位置）</b>：军团类地图没有小地图面板，地图名条顶在工具栏正下方；
     *       城镇的名条在小地图下方（y≈164），{@link #BOX_MAP_STRIP} 那一带是小地图、
     *       一个 OCR 字都没有。所以「那一带能读出文字」就等于「名条顶在上面」。</li>
     * </ol>
     *
     * <p>为什么要两条：军团大厅的名条写的是「大厅」，而 OCR 对这两个字 2x / 4x 都读不出来
     * （2026-09-23 实测），只靠判据①会漏判军团大厅 —— 那会导致「在军团大厅里抢线」
     * 走到「点不到服务器选线」的死路。
     *
     * @return {@code true} = 在军团；{@code false} = 不在；
     *         {@code null} = <b>判不出来</b>（两条 OCR 通道都读不到任何字）。
     *         调用方必须把「判不出来」和「不在军团」区别对待。
     */
    private Boolean inLegionNow() {
        // ---- 判据①：整窗 OCR 直接找地图名里的关键词 ----
        List<ScreenText.TextLine> lines = ScreenText.lines(controller.captureWindow());
        ScreenText.TextLine hit = findKw(lines, KW_LEGION_MAP, ZONE_MAP_NAME);
        if (hit != null) {
            listener.log("    判据① 地图名文字：「" + hit.text + "」@ (" + hit.cx + "," + hit.cy + ") → 在军团");
            return true;
        }

        // ---- 判据②：名条那一带有没有文字（有 = 名条顶在工具栏下 = 军团类地图）----
        Boolean strip = mapNameStripPresent();
        if (strip != null) {
            listener.log("    判据② 名条带：" + (Boolean.TRUE.equals(strip)
                    ? "读得到文字 → 名条顶在工具栏下方 → 在军团类地图"
                    : "一个字都没有（那带被小地图占着）→ 不在军团"));
            return strip;
        }

        listener.log("    两条判据都读不到字（OCR 不可用？）→ 判不出来");
        return null;
    }

    /**
     * 裁剪「地图名条带」{@link #BOX_MAP_STRIP} 放大 {@value #STRIP_SCALE} 倍<b>单独</b> OCR，
     * 只看这一带<b>有没有</b>地图名文字（不关心写的是什么地名）。
     *
     * <p>整窗 OCR（{@link ScreenText#lines}）在 2x 下把「大厅」这种短地名丢了，所以这里
     * 换一条更高倍的通道。代价很小：裁剪图只有 204×52，4x 后 OCR 实测 ~20ms。
     *
     * @return {@code true} = 这一带读到文字；{@code false} = 一行都没有；
     *         {@code null} = OCR 不可用 / 截图失败（判不出来）
     */
    private Boolean mapNameStripPresent() {
        if (!OcrLite.available()) {
            return null;
        }
        try {
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                return null;
            }
            double kx = img.getWidth() / (double) GameWindowController.BASE_WIDTH;
            double ky = img.getHeight() / (double) GameWindowController.BASE_HEIGHT;
            int x0 = (int) Math.round(BOX_MAP_STRIP[0] * kx);
            int y0 = (int) Math.round(BOX_MAP_STRIP[1] * ky);
            int x1 = Math.min(img.getWidth(), (int) Math.round(BOX_MAP_STRIP[2] * kx));
            int y1 = Math.min(img.getHeight(), (int) Math.round(BOX_MAP_STRIP[3] * ky));
            if (x1 - x0 < 16 || y1 - y0 < 12) {
                return null; // 窗口小得反常，别硬判
            }
            BufferedImage crop = img.getSubimage(x0, y0, x1 - x0, y1 - y0);

            File jobDir = OcrLite.prepareJobDir();
            File f = OcrLite.saveForOcr(crop, STRIP_SCALE, new File(jobDir, "strip.png"),
                    OcrLite.MODE_ORIGINAL);
            if (f == null) {
                return null;
            }
            List<OcrLite.Result> res = OcrLite.recognize(Collections.singletonList(f), 30);
            for (OcrLite.Result one : res) {
                if (one.file == null || !one.file.replace('\\', '/').endsWith("strip.png")) {
                    continue; // recognize 按目录跑，过滤掉目录里的历史图
                }
                for (OcrLite.Line l : one.sorted()) {
                    if (!XiaolianBank.normalize(l.text).isEmpty()) {
                        listener.log("      名条带读到：「" + l.text + "」");
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 步骤 1：系统菜单 → 服务器选线 ====================

    /**
     * 打开「系统」菜单并点「服务器选线」。
     *
     * <p><b>2026-09-23 用户要求：不要等玩家按 ESC，脚本自己按</b> —— 省掉那几秒等待。
     * 但 ESC 是<b>开关</b>，连按两次会把菜单又关掉，所以按之前先 OCR 确认菜单是不是
     * 已经开着（玩家手快先按了、或上一轮抢线留下的），只有确实没开时才按。
     *
     * <p>最多按 3 次：奇数次按下后菜单是开着的，所以最后一轮即便 OCR 认不出
     * 「服务器选线」，固定坐标兜底点下去也是打在菜单上的。
     */
    private void clickServerLineOption() {
        listener.log("—— 步骤 1：脚本自己按 ESC 打开「系统」菜单 → 点「服务器选线」——");

        ScreenText.TextLine hit = findMenuOption();
        for (int attempt = 1; hit == null && attempt <= 3; attempt++) {
            checkStop();
            listener.log("  第 " + attempt + "/3 次：菜单没开 → 脚本按 ESC，等 "
                    + (ESCAPE_WAIT_MS / 1000) + " 秒");
            controller.sendKey(WindowUtils.VK_ESCAPE);
            sleep(ESCAPE_WAIT_MS);
            hit = findMenuOption();
            if (hit == null) {
                listener.log("  ⚠ 按完 ESC 还是没看到「服务器选线」");
            }
        }

        if (hit != null) {
            listener.log("  OCR 找到「服务器选线」@ (" + hit.cx + "," + hit.cy + ")，点击");
            controller.clickWindowPoint(hit.cx, hit.cy);
        } else {
            listener.log("  OCR 没找到「服务器选线」，退固定坐标 ("
                    + PT_SERVER_LINE[0] + "," + PT_SERVER_LINE[1] + ")");
            controller.clickWindowPoint(PT_SERVER_LINE[0], PT_SERVER_LINE[1]);
        }
        sleep(PANEL_WAIT_MS);
    }

    /** 系统菜单开着吗 —— OCR 在中央区域找「服务器选线」。 */
    private ScreenText.TextLine findMenuOption() {
        return findKw(ScreenText.lines(controller.captureWindow()), KW_SERVER_LINE, ZONE_MENU);
    }

    /** 轮询等选线面板出现（判据同 {@link #panelAlive(List)}）。 */
    private boolean waitPanel(int tries) {
        listener.log("—— 步骤 2：等选线面板出现 ——");
        for (int i = 1; i <= tries; i++) {
            checkStop();
            List<ScreenText.TextLine> lines = ScreenText.lines(controller.captureWindow());
            if (panelAlive(lines)) {
                listener.log("  ✔ 选线面板已出现（第 " + i + " 次检测，读到 " + lines.size() + " 行文字）");
                return true;
            }
            sleep(1200);
        }
        return false;
    }

    // ==================== 面板扫描 / 判活 ====================

    /** 一次 OCR 的结果：面板上各条线路的位置 + 目标线位置 + 面板是否还在。 */
    private static final class PanelScan {
        /** 线路号 → {x, y}（按 OCR 读到的顺序）。 */
        final Map<Integer, int[]> lines = new LinkedHashMap<>();
        /** 线路号 → 状态文字（正常 / 优良 / 繁忙…）。 */
        final Map<Integer, String> states = new LinkedHashMap<>();
        /** 目标线在面板上的坐标（OCR 没认出来则为 null）。 */
        int[] found;
        /** 这一轮 OCR 读到多少行文字（0 = OCR 瞎了）。 */
        int totalLines;
        /** 面板是否还在。 */
        boolean alive;
    }

    /**
     * 整窗 OCR <b>一次</b>，解析出面板上 16 条线路各自的位置。
     *
     * <p>行文本形如「9线（繁忙）」→ 归一化后「9线繁忙」，用 {@link #LINE_NO} 取行首数字。
     * 30 行文字里只有落在 {@link #ZONE_LINES} 里的才算线路标签 —— 底部
     * 「当前所在：16线」的 y≈556 就在 zone 之外，不会被误当成线路。
     */
    private PanelScan scanPanel() {
        PanelScan s = new PanelScan();
        List<ScreenText.TextLine> lines = ScreenText.lines(controller.captureWindow());
        s.totalLines = lines.size();
        s.alive = panelAlive(lines);
        for (ScreenText.TextLine l : lines) {
            Integer n = lineNumberOf(l.text);
            if (n == null || n < 1 || n > 16 || !inZone(l.cx, l.cy, ZONE_LINES)) {
                continue;
            }
            s.lines.put(n, new int[]{l.cx, l.cy});
            s.states.put(n, stateOf(l.text));
            if (n == targetLine) {
                s.found = new int[]{l.cx, l.cy};
            }
        }
        return s;
    }

    /**
     * 面板是否还开着。两条正面判据（任一成立即「还在」）：
     * ① OCR 找到「当前所在：」那一行；② 线路标签 ≥ 6 条。
     *
     * <p>刻意做成<b>正面判据</b>而不是「找不到某文字就算没了」—— 后者在 OCR 瞎掉时
     * 会误判成「成功进线」，那是假成功，比失败更糟。
     */
    private boolean panelAlive(List<ScreenText.TextLine> lines) {
        if (findKw(lines, KW_PANEL_TITLE, ZONE_PANEL_TITLE) != null) {
            return true;
        }
        int cnt = 0;
        for (ScreenText.TextLine l : lines) {
            Integer n = lineNumberOf(l.text);
            if (n != null && n >= 1 && n <= 16 && inZone(l.cx, l.cy, ZONE_LINES)) {
                cnt++;
            }
        }
        return cnt >= 6;
    }

    /**
     * 面板存活判断的容错版：OCR 读不到任何行时重试一次，仍读不到返回 null
     * （调用方按「判断不出来」处理，绝不当作成功）。
     */
    private Boolean panelAliveSafe() {
        for (int i = 0; i < 2; i++) {
            List<ScreenText.TextLine> lines = ScreenText.lines(controller.captureWindow());
            if (!lines.isEmpty()) {
                return panelAlive(lines);
            }
            sleep(500);
        }
        return null;
    }

    /** 把这一轮 OCR 读到的线路分布写进日志（方便人工核对识别效果）。 */
    private void logPanel(int round, PanelScan scan) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : scan.states.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append("线").append(e.getValue());
        }
        listener.log("  第 " + round + " 轮 OCR：" + (sb.length() == 0 ? "（没认出任何线路）" : sb.toString())
                + (scan.found != null ? "  ✓ 目标线在里面" : "  ✗ 目标线没认出来"));
    }

    // ==================== 「线路繁忙」提示框 / 重开面板（2026-09-23 新增） ====================

    /**
     * 确保选线面板开着。抢线失败弹的「线路繁忙」提示框有时会把面板一起关掉，
     * 所以每轮开抢之前都确认一次；不在就重新「按 ESC → 点服务器选线」。
     *
     * @return {@code true} = 面板已就绪；{@code false} = 两次重开都失败
     */
    private boolean ensurePanelOpen(int round) {
        Boolean alive = panelAliveSafe();
        if (Boolean.TRUE.equals(alive)) {
            return true;
        }
        listener.log("  ⚠ 第 " + round + " 轮：选线面板不在（忙的时候提示框可能连带把面板关了）"
                + "→ 重新打开");
        for (int i = 1; i <= 2; i++) {
            clickServerLineOption();
            if (waitPanel(3)) {
                return true;
            }
            listener.log("  ⚠ 第 " + i + "/2 次重开面板失败");
        }
        return false;
    }

    /**
     * 检测并关掉「线路繁忙」提示框。
     *
     * <p>用户 2026-09-23 口述：抢线失败时<b>屏幕正中间</b>会弹一个提示框，它不会自己消失，
     * 必须按 Enter 或点它上面的「确定」才能消除；关掉后可以接着抢。
     * 这就是「抢不到就继续挤」这一段的实现。
     *
     * @return {@code true} = 这一轮确实弹了提示框（调用方应 {@code continue} 重新抢）
     */
    private boolean handleBusyPopup(int round) {
        ScreenText.TextLine pop = findBusyPopupStable();
        if (pop == null) {
            return false;
        }
        listener.log("  ⚠ 第 " + round + " 轮：屏幕中间弹出「" + pop.text + "」@ ("
                + pop.cx + "," + pop.cy + ") → " + targetLine + " 线没抢上");
        if (busyShotCount < BUSY_SHOT_MAX) {
            busyShotCount++;
            String s = snapshot("busy_popup");
            if (s != null) {
                listener.log("    提示框现场截图：" + s);
            }
        }
        dismissBusyPopup();
        return true;
    }

    /**
     * 关掉「线路繁忙」提示框：<b>先按 Enter</b>（用户实测最有效），
     * 没关掉再 OCR 找提示框自己的「确定」点一下，还不行就再补一次 Enter。
     */
    private void dismissBusyPopup() {
        controller.sendKey(WindowUtils.VK_RETURN);
        listener.log("    已按 Enter 关提示框，等 " + POPUP_CLOSE_WAIT_MS + "ms");
        sleep(POPUP_CLOSE_WAIT_MS);

        if (findBusyPopup() == null) {
            listener.log("    ✔ 提示框已关闭，接着抢 " + targetLine + " 线");
            return;
        }

        ScreenText.TextLine ok = findKw(ScreenText.lines(controller.captureWindow()),
                KW_CONFIRM, ZONE_POPUP_OK);
        if (ok != null) {
            listener.log("    Enter 没关掉 → OCR 找到提示框的「确定」@ ("
                    + ok.cx + "," + ok.cy + ")，点击");
            controller.clickWindowPoint(ok.cx, ok.cy);
        } else {
            listener.log("    Enter 没关掉，也没认出提示框的「确定」→ 再补按一次 Enter");
            controller.sendKey(WindowUtils.VK_RETURN);
        }
        sleep(POPUP_CLOSE_WAIT_MS);

        if (findBusyPopup() == null) {
            listener.log("    ✔ 提示框已关闭，接着抢 " + targetLine + " 线");
        } else {
            listener.log("    ⚠ 提示框还挂在屏幕上 —— 下一轮继续关（一直关不掉请手工看一下）");
        }
    }

    /**
     * 采样两次（间隔 {@value #POPUP_CHECK_WAIT_MS}ms）确认提示框在不在 ——
     * 刚点完线路时第一帧可能还没渲染出来，单帧容易漏判成「没弹窗 = 成功」。
     */
    private ScreenText.TextLine findBusyPopupStable() {
        ScreenText.TextLine pop = findBusyPopup();
        if (pop != null) {
            return pop;
        }
        sleep(POPUP_CHECK_WAIT_MS);
        return findBusyPopup();
    }

    /**
     * 屏幕上有没有「线路繁忙」提示框；有就返回命中的那一行，没有返回 {@code null}。
     *
     * <p>要绕开两个坑：
     * <ol>
     *   <li>选线面板自己就写着「9线（繁忙）」—— 所以先排除<b>行首是 N线</b>的行；</li>
     *   <li>面板底部「当前所在：9线（繁忙）」也含「繁忙」—— 它在 {@link #ZONE_BUSY_POPUP}
     *       的 y 范围之外，这里再额外要求整行不含「所在」，双保险。</li>
     * </ol>
     */
    private ScreenText.TextLine findBusyPopup() {
        return findBusyPopupLine(ScreenText.lines(controller.captureWindow()));
    }

    /**
     * 提示框判定的<b>纯函数</b>版本（不碰截图 / OCR）—— 规则可离线回归
     * （{@code _dev/BusyPopupTest.java} 直接喂伪造的 OCR 行进来跑）。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>先用 {@link #KW_BUSY_POPUP}（只可能在提示框里出现的长词）在
     *       {@link #ZONE_BUSY_POPUP} 里找，命中即算；</li>
     *   <li>没有再用 {@link #KW_BUSY_WEAK} 兜底，但要过两道闸：整行不含「所在」
     *       （排除面板底部「当前所在：9线（繁忙）」）且行首不是 {@code N线}
     *       （排除面板上的线路行）。</li>
     * </ol>
     *
     * @param lines 整窗 OCR 的文字行
     * @return 命中的那一行；没有提示框返回 {@code null}
     */
    static ScreenText.TextLine findBusyPopupLine(List<ScreenText.TextLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }

        // ① 只可能在提示框里出现的词，命中即算
        ScreenText.TextLine hit = findKw(lines, KW_BUSY_POPUP, ZONE_BUSY_POPUP);
        if (hit != null) {
            return hit;
        }

        // ② 通用词兜底：必须「不含所在」且「行首不是 N线」才算提示框
        for (String kw : KW_BUSY_WEAK) {
            String want = XiaolianBank.normalize(kw);
            if (want.isEmpty()) {
                continue;
            }
            for (ScreenText.TextLine l : lines) {
                if (!l.text.contains(want) || !inZone(l.cx, l.cy, ZONE_BUSY_POPUP)) {
                    continue;
                }
                if (l.text.contains("所在") || lineNumberOf(l.text) != null) {
                    continue;
                }
                return l;
            }
        }
        return null;
    }

    // ==================== 点击 ====================

    /** OCR 找「确定」按钮再点；找不到退固定坐标。 */
    private void clickConfirm() {
        ScreenText.TextLine hit = findKw(ScreenText.lines(controller.captureWindow()),
                KW_CONFIRM, ZONE_CONFIRM);
        if (hit != null) {
            listener.log("  OCR 找到「确定」@ (" + hit.cx + "," + hit.cy + ")，点击");
            controller.clickWindowPoint(hit.cx, hit.cy);
        } else {
            listener.log("  OCR 没找到「确定」，退固定坐标 ("
                    + PT_CONFIRM[0] + "," + PT_CONFIRM[1] + ")");
            controller.clickWindowPoint(PT_CONFIRM[0], PT_CONFIRM[1]);
        }
    }

    /** 固定点位表：奇数线在左列、偶数线在右列，行号 = 线路号在列内的序号。 */
    private static int[] fixedPoint(int line) {
        boolean odd = (line % 2) == 1;
        int row = odd ? (line + 1) / 2 : line / 2;   // 1..8
        if (row < 1) {
            row = 1;
        }
        if (row > ROW_Y.length) {
            row = ROW_Y.length;
        }
        return new int[]{odd ? COL_X_ODD : COL_X_EVEN, ROW_Y[row - 1]};
    }

    // ==================== 解析 / 匹配工具 ====================

    /**
     * 从一行文本里取线路号：只认<b>行首</b>的「数字 + 线」。
     *
     * <p>这是抢线最关键的一条规则 —— 用字符串包含的话「1线」会命中「11线」。
     * OCR 偶尔把数字 1 读成字母 l / I，一并按 1 处理。
     */
    private static Integer lineNumberOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = LINE_NO.matcher(text);
        if (!m.find()) {
            return null;
        }
        String num = m.group(1).replace('l', '1').replace('I', '1');
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 线路状态文字 = 去掉行首「N线」之后剩下的部分（正常 / 优良 / 繁忙）。 */
    private static String stateOf(String text) {
        Matcher m = LINE_NO.matcher(text);
        if (!m.find()) {
            return "?";
        }
        String s = text.substring(m.end());
        return s.isEmpty() ? "?" : s;
    }

    /**
     * 在一批 OCR 行里找一个关键词（与 {@link ScreenText#find} 同款匹配规则：
     * 包含 或 相似度≥0.72，多行命中取最短行），并限制在给定 zone 内。
     */
    private static ScreenText.TextLine findKw(List<ScreenText.TextLine> lines,
                                              String[] keywords, int[] zone) {
        for (String kw : keywords) {
            String want = XiaolianBank.normalize(kw);
            if (want.isEmpty()) {
                continue;
            }
            ScreenText.TextLine best = null;
            for (ScreenText.TextLine l : lines) {
                if (!l.text.contains(want) && XiaolianBank.similarity(l.text, want) < 0.72) {
                    continue;
                }
                if (!inZone(l.cx, l.cy, zone)) {
                    continue;
                }
                if (best == null || l.text.length() < best.text.length()) {
                    best = l;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean inZone(int x, int y, int[] zone) {
        return x >= zone[0] && x <= zone[1] && y >= zone[2] && y <= zone[3];
    }

    // ==================== 基础设施 ====================

    private void checkStop() {
        if (stopRequested) {
            throw new Abort("已被用户中止");
        }
    }

    /** 可中断的 sleep：等待期间每 200ms 查一次停止标志。 */
    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            checkStop();
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(200, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Abort("已被中断");
            }
        }
    }

    /** 存一张现场截图到 grabline_shots/，返回文件名（失败返回 null，不影响主流程）。 */
    private String snapshot(String tag) {
        try {
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                return null;
            }
            if (!shotDir.exists() && !shotDir.mkdirs()) {
                return null;
            }
            String name = new SimpleDateFormat("HHmmss").format(new Date()) + "_" + tag + ".png";
            File f = new File(shotDir, name);
            ImageIO.write(img, "png", f);
            return f.getPath();
        } catch (Throwable ignore) {
            return null;
        }
    }
}
