package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.imageio.ImageIO;

/**
 * 「工资 · 官爵任务」一键流程执行器。
 *
 * <h3>完整流程</h3>
 * <pre>
 *  0.  F11 屏蔽其他玩家（跑完自动再按一次还原）
 *  1.  关闭游戏广告弹窗（检测到才处理）
 *  2.  O → 点「回到军团」(830,635)     先回军团地图（把角色定锚）
 *  3.  T                              再回城（成都·子城）
 *  4.  点右上角「寻路」(827,189)       打开「自动寻路」面板
 *  5.  坐标框填 11 / 7 → 点「移动」(648,463) → 等 ~12s 走过去
 *  6.  关掉寻路面板 (690,200)
 *  7.  G → 奋威中郎将 → Enter 逐层确认「对话/任务」→「官爵任务」→「请交给我吧」
 *  8.  循环（最多 8 个 NPC）：
 *        a. 点「任务追踪」面板里的红色 NPC 名(861,348) → 角色自动寻路过去
 *        b. 等 ~11s 走到位
 *        c. 按 G 唤起对话 → 按 Enter 逐层确认选项（第 1 项默认高亮）
 *        d. ESC 关掉对话（若误开系统菜单会自动再关掉）
 *        e. 看「任务追踪」里还有没有「NPC: xxx」
 *             有   → 下一个 NPC
 *             没有 → 官爵任务链完成，结束
 * </pre>
 *
 * <h3>关键标定结论（实机验证）</h3>
 * <ul>
 *   <li><b>NPC 红名的来源</b>：不是 F5 任务面板，而是窗口<b>右上角的「任务追踪」面板</b>，
 *       面板位置固定的。里面第二行是「NPC: <b>名字</b>」，名字是红色、可点击，点一下角色就自动寻路过去。</li>
 *   <li><b>红名位置</b>：固定的 —— 「NPC:」白字在 x≈822~848，红名从 x≈850 开始，
 *       整行 y≈341~356。所以点 (861,348) 一律落在名字上（前 2~5 个字都覆盖）。</li>
 *   <li><b>有没有待拜访的 NPC</b>：用「NPC:」这几个<b>白字</b>的像素数判断最干净 ——
 *       有目标时白像素 58~78（含背景渗色波动），任务做完面板消失时降到 0~41。
 *       阈值取 50。用红名签名比对<b>不可靠</b>：面板半透明，背景一直在变，
 *       同一个名字两帧的差异（0~154）比不同名字之间的差异（38~107）还大。</li>
 *   <li><b>确认选项用 Enter</b>：对话弹出后第 1 项默认高亮，直接回车即等价于点它。
 *       比点坐标稳（实测选项高亮条的横向落点会左右浮动，x 在 448 附近浮动）。</li>
 *   <li><b>ESC 关对话</b>：0.4s 内生效。没有对话时按 ESC 会打开系统菜单，所以要复查一次。</li>
 *   <li><b>对话框残留会吃掉点击</b>：点追踪红名之前必须先保证没有对话框，
 *       否则那一次点击只是把对话框关掉，角色不会动。</li>
 * </ul>
 *
 * <h3>坐标说明</h3>
 * 全部是「窗口内坐标」，基准分辨率 1030x797（QQ三国 默认窗口尺寸），
 * 运行时按游戏窗口真实尺寸等比换算。
 */
public class SalaryTask {

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

    private static final int BASE_W = GameWindowController.BASE_WIDTH;
    private static final int BASE_H = GameWindowController.BASE_HEIGHT;

    /** 军团界面「回到军团」按钮 */
    private static final int[] PT_BACK_TO_LEGION = {830, 635};
    /** 「热点活动」弹窗右上角 X */
    private static final int[] PT_AD_HOTSPOT_X = {778, 322};
    /** 广告检测采样区 */
    private static final int[] PATCH_AD_HOTSPOT = {753, 297, 50, 50};
    /** 广告判定阈值：R-G 大于该值说明橙色弹窗还在
     * （实测真弹窗远大于此；成都·子城闹市背景实测最高 +38，所以取 50 避免误判） */
    private static final double TH_AD_RG = 50.0;

    /** 「热点活动 / 游戏活动展示」弹窗标题的 OCR 搜索区与关键词（0926 误报修复，同孝廉/霸王城/军团）。
     *  色块会被红色景物（秋叶/灯笼）骗过 —— 200056 现场「广告未关」截图里画面根本没有弹窗。
     *  搜索区避开 x≥830 的右上角按钮列（那里有「热点」按钮，会把按钮文字误当弹窗标题）。 */
    private static final int[] ZONE_AD_TITLE = {250, 810, 180, 400};
    private static final String[] KW_AD_TITLE = {
        "热点活动", "热点活", "点活动", "热点", "活动展示", "游戏活动", "动展示"};

    /** 右上角「寻路」按钮 */
    private static final int[] PT_XUNLU_BTN = {827, 189};
    private static final int[] PT_NAV_BOX1  = {505, 465};
    private static final int[] PT_NAV_BOX2  = {533, 465};
    private static final int[] PT_NAV_MOVE  = {648, 463};
    private static final int[] PT_NAV_CLOSE = {690, 200};

    /** 寻路目标坐标（成都·子城，奋威中郎将） */
    private static final String TARGET_X = "11";
    private static final String TARGET_Y = "7";

    // ==================== 「任务追踪」面板 NPC 红名 ====================

    /** 「NPC:」白字的检测区（用来判断还有没有待拜访的 NPC） */
    private static final int TR_W_X0 = 818, TR_W_X1 = 849, TR_W_Y0 = 340, TR_W_Y1 = 359;
    /** 白字像素下限：有目标实测 58~76，没目标 0~56（其他玩家的白名牌是主要干扰） */
    private static final int TR_WHITE_MIN = 52;
    /** 名字行的红像素检测区（在「NPC:」右边） */
    private static final int TR_RED_X0 = 846, TR_RED_X1 = 900, TR_RED_Y0 = 341, TR_RED_Y1 = 358;
    /** 红像素下限（单行≥3 像素的行才计入）：有目标实测 134~168，没目标 0~85 */
    private static final int TR_RED_MIN = 100;
    /** 计入统计的单行红像素下限 */
    private static final int TR_RED_ROW_PX = 3;
    /** 点红名的落点：x=861 落在名字第 1 个字上（2~5 个字的名字都覆盖），y=348 是名字行中线 */
    private static final int TR_CLICK_X = 861, TR_CLICK_Y = 348;

    // ==================== 对话高亮条检测 ====================

    private static final int BAR_X0 = 340, BAR_X1 = 700;
    private static final int BAR_Y0 = 225, BAR_Y1 = 665;
    /** 单行「亮蓝像素」个数下限 */
    private static final int BAR_MIN_ROW_PX = 70;
    /** 行聚带允许的空洞（条子中间的白色文字会把均值拉低） */
    private static final int BAR_ROW_GAP = 16;
    /** 带高范围 → 对话首项高亮条 */
    private static final int BAR_SPAN_MIN = 12, BAR_SPAN_MAX = 66;
    /** 高亮条中心 y 的下限（更靠上的蓝色横带都是误检） */
    private static final int BAR_MIN_CY = 275;

    // ==================== 大面板 / 系统菜单检测 ====================

    private static final int PANEL_X0 = 420, PANEL_X1 = 690, PANEL_Y0 = 240, PANEL_Y1 = 510;
    private static final double TH_PANEL_FRAC = 0.65;

    private static final int MENU_X0 = 435, MENU_X1 = 635, MENU_Y0 = 325, MENU_Y1 = 565;
    private static final double TH_MENU_FRAC = 0.30;

    // ==================== 虚拟键码 ====================

    private static final int VK_T    = WindowUtils.VK_T;
    private static final int VK_O    = WindowUtils.VK_O;
    private static final int VK_G    = WindowUtils.VK_G;
    private static final int VK_ESC  = WindowUtils.VK_ESCAPE;
    private static final int VK_F11  = WindowUtils.VK_F11;
    private static final int VK_ENTER = WindowUtils.VK_RETURN;
    private static final int VK_END  = WindowUtils.VK_END;
    private static final int VK_BACK = WindowUtils.VK_BACK;

    // —— 坐标框填写自检（阈值由 salary_shots 历史截图实测：空框≈0~6、单字≈17~20、
    //    "11"≈42、"7"≈50 白像素；白手套光标压框会凭空多出 ≈60，所以清空判定
    //    用「绝对空」和「前后对比」双条件，不能只看绝对值）——
    /** RGB 三通道都 ≥ 此值才算白像素（数字是白字，手套光标也是白）。 */
    private static final int WHITE_MIN = 200;
    /** 清空后框内白像素 ≤ 此值 → 确认框内已无数字。 */
    private static final int INK_EMPTY_MAX = 12;
    /** 清空前后白像素下降 ≥ 此值 → 确认清掉了旧数字（一个薄数字 ≈17）。 */
    private static final int INK_CLEAR_DROP = 14;
    /** 输入后框内白像素 ≥ 此值 → 确认数字真的落进了这个框（"11"≈42、"7"≈50）。 */
    private static final int INK_TYPED_MIN = 25;

    // ==================== 节奏 ====================

    private static final int STEP_MS = 1800;
    private static final int CLICK_MS = 1400;
    private static final int SHORT_MS = 800;
    private static final int ESC_MS = 1300;
    private static final int TELEPORT_MS = 3500;
    /** 寻路 (11,7) 后等角色走过去 */
    private static final int NAV_WALK_MS = 12000;
    /** 点任务追踪红名后等角色自动寻路到 NPC */
    private static final int NPC_WALK_MS = 11000;
    private static final int DIALOG_WAIT_MS = 1700;
    private static final int PANEL_WAIT_MS = 1500;
    /** 按 G 唤起对话的最多尝试次数 */
    private static final int G_MAX_TRIES = 4;
    /** 一层对话最多往深处确认几层（「对话/任务」→「官爵任务」→「请交给我吧」是 3 层） */
    private static final int DIALOG_MAX_DEPTH = 3;

    // ==================== 任务参数 ====================

    /** 最多找几个 NPC（任务链约 4 个，留足余量；目标消失会提前结束） */
    private static final int MAX_NPC = 8;

    // ==================== 运行状态 ====================

    private final GameWindowController controller;
    private final Listener listener;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;

    /** F11 是否按过（用于 finally 里还原） */
    private boolean playersHidden = false;

    private final File shotDir = new File("salary_shots");

    public SalaryTask(GameWindowController controller, Listener listener) {
        this.controller = controller;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void requestStop() {
        stopRequested = true;
    }

    public void start() {
        if (running) {
            listener.log("工资任务已在运行中");
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
        }, "SalaryTask");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步执行一次完整任务（供 {@link #start()} 与「一键日常」序列共用）。
     *
     * <p><b>后台模式作用域也在这里</b>：进入时记下用户原来的后台开关并强制开启，
     * 退出时原样还原 —— 组队任务同款做法。
     */
    public TaskOutcome runOnce() {
        if (running) {
            return new TaskOutcome(false, "工资任务已在运行中");
        }
        running = true;
        stopRequested = false;
        boolean savedBg = controller.isRunInBackground();
        try {
            controller.setRunInBackground(true);
            listener.log("工资任务以「后台模式」运行（不抢焦点、不移动鼠标）");
            execute();
            return new TaskOutcome(true, "已完成官爵任务流程");
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
        listener.log("================ 工资 · 官爵任务 开始 ================");

        Rectangle r = controller.getWindowRect();
        if (r == null) {
            throw new Abort("找不到游戏窗口，请先启动 QQ三国 并确认窗口未关闭");
        }
        listener.log("游戏窗口：" + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");
        controller.ensureWindowVisible();

        int[] savedCursor = controller.getCursorPosition();
        try {
            // 后台模式不抢焦点
            if (controller.isRunInBackground()) {
                listener.log("后台模式：不抢焦点，直接给游戏窗口发消息");
            } else {
                controller.focusWindow();
                sleep(SHORT_MS);
                listener.log("已把游戏窗口切到前台");
            }

            // ---------- 0. F11 屏蔽其他玩家 ----------
            hideOtherPlayers();

            // ---------- 1. 关广告 ----------
            closeAds();

            // ---------- 2. O → 回到军团 ----------
            goBackToLegion();

            // ---------- 3. T 回城 ----------
            backToTown();

            // ---------- 4. 寻路到 (11,7) ----------
            navToSalaryNpc();

            // ---------- 5. 接官爵任务 ----------
            acceptSalaryQuest();

            // ---------- 6. 逐个 NPC 跑任务链 ----------
            runNpcChain();

            String p = snapshot("99_done");
            listener.log("✔ 官爵任务流程执行完毕" + (p != null ? "（截图：" + p + "）" : ""));
        } finally {
            restoreOtherPlayers();
            // 后台模式不动真实鼠标（用户可能正在用电脑）
            if (!controller.isRunInBackground() && savedCursor != null) {
                controller.moveCursor(savedCursor[0], savedCursor[1]);
            }
        }
    }

    /** F11：屏蔽其他玩家，去掉满屏杂色，追踪面板背景也干净很多。 */
    private void hideOtherPlayers() {
        controller.sendKey(VK_F11);
        sleep(SHORT_MS);
        playersHidden = true;
        listener.log("已按 F11 屏蔽其他玩家");
    }

    /** 流程结束还原 F11。 */
    private void restoreOtherPlayers() {
        if (!playersHidden) {
            return;
        }
        try {
            if (!controller.isRunInBackground()) {
                // 后台模式：F11 走窗口消息，不需要焦点
                controller.focusWindow();
                sleep(300);
            }
            controller.sendKey(VK_F11);
            sleep(SHORT_MS);
            listener.log("已按 F11 还原显示");
        } catch (Throwable ignore) {
            // 还原失败不影响主流程结果
        }
    }

    // ==================== 前置清理 ====================

    /** 关闭游戏广告弹窗（检测到才处理）。 */
    void closeAds() {
        listener.log("—— 步骤 1：关闭游戏广告弹窗 ——");

        if (!adHotspotPresent()) {
            listener.log("  未检测到广告弹窗，跳过（不按 ESC，避免打开系统菜单）");
            return;
        }

        for (int i = 1; i <= 3; i++) {
            checkStop();
            controller.sendKey(VK_ESC);
            listener.log("  ESC 第 " + i + " 次（关「游戏活动展示」）");
            sleep(ESC_MS);
        }
        // ESC 若把系统菜单打开了，顺手关掉
        if (systemMenuOpen()) {
            controller.sendKey(VK_ESC);
            sleep(ESC_MS);
        }

        for (int i = 1; i <= 2; i++) {
            checkStop();
            if (!adHotspotPresent()) {
                listener.log("  ✔ 「热点活动」弹窗已关闭");
                break;
            }
            listener.log("  点击「热点活动」右上角 X（第 " + i + " 次）");
            controller.clickWindowPoint(PT_AD_HOTSPOT_X[0], PT_AD_HOTSPOT_X[1]);
            sleep(CLICK_MS);
        }

        if (adHotspotPresent()) {
            String shot = snapshot("anomaly_ad_still_open");
            abort("广告弹窗未能关闭",
                    "广告弹窗没关掉，它会遮住右上角按钮，后面每一步点击都会落空。\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请手动关掉后重新点击「工资」。");
        }
        listener.log("  ✔ 广告弹窗处理完成");
    }

    /** O → 点「回到军团」，把角色定锚到军团地图。
     *  动态识别橙色按钮优先，识别不到退固定坐标 (830,635)；
     *  按钮本来可见说明军团界面已开，再按 O 反而会把它关掉。 */
    void goBackToLegion() {
        listener.log("—— 步骤 2：按 O 打开军团界面 → 点「回到军团」——");
        controller.ensureWindowVisible();
        int[] btn = findBackToLegionButton(captureQuiet());
        if (btn == null) {
            controller.sendKey(VK_O);
            sleep(STEP_MS);
            btn = findBackToLegionButton(captureQuiet());
        } else {
            listener.log("  军团界面已打开（「回到军团」按钮可见），不重复按 O");
        }
        int cx, cy;
        if (btn != null) {
            cx = btn[0];
            cy = btn[1];
            listener.log("  动态定位「回到军团」@ (" + cx + "," + cy + ")");
        } else {
            cx = PT_BACK_TO_LEGION[0];
            cy = PT_BACK_TO_LEGION[1];
            listener.log("  未识别到按钮，退固定坐标 (" + cx + "," + cy + ")");
        }
        controller.clickWindowPoint(cx, cy);
        sleep(TELEPORT_MS);
        listener.log("  ✔ 已回到军团地图");
    }

    /** 「回到军团」按钮橙色判据（与组队按钮芯色同族）。 */
    private static boolean isLegionBtnOrange(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r >= 180 && g >= 60 && g <= 160 && b <= 90;
    }

    /**
     * 动态定位 O 面板右下角「回到军团」橙色按钮：在按钮行 (560~920, 618~654) 逐列
     * 数橙像素 → 密集列（≥6）聚类（间隙 ≤3 列算同块）→ 取「最靠右且 x 起点 ≥780、
     * 宽 30~130」的块。判据在真实截图上验证过：O 面板开 → 命中 800~868（质心 ≈834,636，
     * 与固定点 830,635 吻合）；面板关 → 0 簇；其它地图的零星橙色（如霸王城 x570~605）被
     * x≥780 一票排除。返回按钮质心（窗口坐标），没识别到返回 null —— 调用方退固定坐标。
     */
    static int[] findBackToLegionButton(BufferedImage img) {
        if (img == null) {
            return null;
        }
        int x0 = 560, x1 = Math.min(920, img.getWidth() - 1);
        int y0 = 618, y1 = Math.min(654, img.getHeight() - 1);
        int[] col = new int[x1 - x0 + 1];
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                if (isLegionBtnOrange(img.getRGB(x, y))) {
                    col[x - x0]++;
                }
            }
        }
        int bestS = -1, bestE = -1, s = -1, e = -1, gap = 0;
        for (int i = 0; i < col.length; i++) {
            if (col[i] >= 6) {
                if (s < 0) {
                    s = i;
                }
                e = i;
                gap = 0;
            } else if (s >= 0) {
                gap++;
                if (gap > 3) {
                    if (e - s + 1 >= 30 && (x0 + s) >= 780) {
                        bestS = s;
                        bestE = e;
                    }
                    s = -1;
                    gap = 0;
                }
            }
        }
        if (s >= 0 && e - s + 1 >= 30 && (x0 + s) >= 780) {
            bestS = s;
            bestE = e;
        }
        if (bestS < 0) {
            return null;
        }
        long sx = 0, sy = 0, n = 0;
        for (int x = x0 + bestS; x <= x0 + bestE; x++) {
            for (int y = y0; y <= y1; y++) {
                if (isLegionBtnOrange(img.getRGB(x, y))) {
                    sx += x;
                    sy += y;
                    n++;
                }
            }
        }
        if (n < 300) {
            return null; // 零星橙点凑不成一个按钮
        }
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }

    /** T 回城（成都·子城）——「自动寻路」的坐标只在这张图里成立。 */
    void backToTown() {
        listener.log("—— 步骤 3：按 T 回城（成都）——");
        controller.sendKey(VK_T);
        sleep(TELEPORT_MS);
        listener.log("  ✔ 已回到主城");
    }

    /**
     * 「热点活动」弹窗是否还在 = 色块偏红 <b>且</b> OCR 认得出广告标题。
     *
     * <p>纯色块判据会被秋叶/灯笼等红色景物骗过（200056 现场「广告未关」截图里画面没有
     * 弹窗却被中止），所以色块命中后还要 OCR 认到「热点活动/游戏活动展示」字样才算数。
     * OCR 异常时按「弹窗在」处理 —— 宁可多关一次，不放走真弹窗。
     */
    private boolean adHotspotPresent() {
        double[] m = patchMean(controller.captureWindow(), PATCH_AD_HOTSPOT);
        if (m == null) {
            return false;
        }
        double rg = m[0] - m[1];
        if (rg <= TH_AD_RG) {
            return false;
        }
        try {
            boolean title = ScreenText.find(controller.captureWindow(), KW_AD_TITLE, ZONE_AD_TITLE, 0) != null;
            if (!title) {
                listener.log("    [检测] 色块偏红但 OCR 没认出广告标题 —— 多半是红色景物（秋叶/灯笼）误报，按「无弹窗」处理");
            }
            return title;
        } catch (Throwable t) {
            listener.log("    [检测] 广告标题 OCR 异常（按「弹窗在」处理，走原关闭流程）：" + t);
            return true;
        }
    }

    // ==================== 寻路 ====================

    /** 打开寻路面板 → 填 11 / 7 → 点移动 → 等走过去 → 关面板。 */
    void navToSalaryNpc() {
        listener.log("—— 步骤 4：寻路到 (" + TARGET_X + ", " + TARGET_Y + ") ——");
        openNavPanel();
        measureNavPanel();

        fillCoordBox(curBox1, TARGET_X, "第 1 个坐标框");
        fillCoordBox(curBox2, TARGET_Y, "第 2 个坐标框");
        verifyBothBoxes();
        snapshot("04_coords_filled");

        listener.log("  点击「移动」，角色自动走过去（一到就按 G，最多等 " + (NAV_WALK_MS / 1000) + " 秒）");
        controller.clickWindowPoint(curMove[0], curMove[1]);
        waitForArrival(Integer.parseInt(TARGET_X), Integer.parseInt(TARGET_Y), NAV_WALK_MS, ARRIVE);

        closeNavPanel();
    }

    /** 本轮实测的面板控件位置（面板会随右上角地图横幅整体漂 ~74px，量到用实测、量不到退固定保底）。 */
    private int[] curBox1 = PT_NAV_BOX1;
    private int[] curBox2 = PT_NAV_BOX2;
    private int[] curMove = PT_NAV_MOVE;
    private int[] curClose = PT_NAV_CLOSE;

    /**
     * 两套机制：动态实测面板上的坐标框 /「移动」按钮 / X 关闭钮。
     * 两个深色框对是「自动寻路」面板独有的指纹（XiaolianTask.findNavBoxesCore），
     * 位置无关；量不到退回固定坐标保底。
     */
    private void measureNavPanel() {
        int[][] boxes = null;
        try {
            boxes = XiaolianTask.findNavBoxesCore(controller.captureWindow());
        } catch (Throwable t) {
            listener.log("  实测坐标框出错（" + t.getMessage() + "），用固定坐标");
        }
        if (boxes != null) {
            curBox1 = boxes[0];
            curBox2 = boxes[1];
            listener.log("  实测坐标框：box1 (" + curBox1[0] + "," + curBox1[1]
                    + ")，box2 (" + curBox2[0] + "," + curBox2[1] + ")");
        } else {
            curBox1 = PT_NAV_BOX1;
            curBox2 = PT_NAV_BOX2;
            listener.log("  未量到坐标框，用固定坐标 (" + curBox1[0] + "," + curBox1[1] + ")");
        }
        int[] mv = null;
        try {
            mv = XiaolianTask.findNavMoveBtnCore(controller.captureWindow());
        } catch (Throwable t) {
            // 忽略，走推算
        }
        if (mv != null) {
            curMove = mv;
            listener.log("  实测「移动」按钮 (" + mv[0] + "," + mv[1] + ")");
        } else {
            // 按钮和框同一行：跟着实测框心走（y 差 ~1px）
            curMove = new int[]{PT_NAV_MOVE[0] + (curBox2[0] - PT_NAV_BOX2[0]), curBox2[1]};
            listener.log("  未量到「移动」按钮，按框位推算 (" + curMove[0] + "," + curMove[1] + ")");
        }
        // X 在面板标题行右端：实测相对偏移 box2 +（157,-255）（09:24 现场实测）
        curClose = new int[]{curBox2[0] + 157, curBox2[1] - 255};
    }

    /** 到达判定模式：ARRIVE=坐标到达目标；STABLE=坐标连续两次不变（自动寻路停了，目标未知时用）。 */
    static final int ARRIVE = 0, STABLE = 1;

    /**
     * 轮询右上角坐标条，到位立刻返回 —— 修复「角色 4~5 秒就走到了 NPC 面前，
     * 还要干等满 12 秒才按 G」的空窗。坐标条被聊天气泡挡住读不出时退回原定等待。
     *
     * @param tx ty 目标坐标（STABLE 模式忽略）；maxWaitMs 原定盲等时间；超出 6 秒宽限仍没到位就放弃
     */
    private void waitForArrival(int tx, int ty, long maxWaitMs, int mode) {
        long start = System.currentTimeMillis();
        long deadline = start + maxWaitMs + 6000;
        int[] first = null, last = null;
        while (System.currentTimeMillis() < deadline) {
            checkStop();
            sleep(1500);
            long waited = (System.currentTimeMillis() - start) / 1000;
            int[] xy = XiaolianTask.readMapCoordsOnce(controller);
            if (xy != null) {
                if (mode == ARRIVE && Math.abs(xy[0] - tx) <= 1 && Math.abs(xy[1] - ty) <= 1) {
                    listener.log("  ✔ 已到达 (" + xy[0] + "," + xy[1] + ")（走了 " + waited
                            + " 秒），立刻按 G 对话");
                    return;
                }
                if (mode == STABLE) {
                    if (first == null) {
                        first = xy;
                    }
                    boolean moved = xy[0] != first[0] || xy[1] != first[1];
                    if (moved && last != null && last[0] == xy[0] && last[1] == xy[1]) {
                        listener.log("  ✔ 坐标停在 (" + xy[0] + "," + xy[1] + ")（走了 " + waited
                                + " 秒），自动寻路已到位，立刻按 G 对话");
                        return;
                    }
                }
                last = xy;
            }
            // 超过原定等待且坐标条一直读不出 → 退回旧行为（死等时间到）
            if (xy == null && System.currentTimeMillis() - start >= maxWaitMs) {
                return;
            }
        }
        listener.log("  ⚠ 等待超时还没确认到位（可能被卡住），继续按 G 试试");
    }

    /** 打开「自动寻路」面板；失败时补按一次 T 回城重试。 */
    private void openNavPanel() {
        for (int round = 1; round <= 2; round++) {
            for (int i = 1; i <= 3; i++) {
                checkStop();
                if (bigPanelOpen()) {
                    listener.log("  ✔ 「自动寻路」面板已打开");
                    return;
                }
                listener.log("  点击「寻路」按钮（第 " + i + " 次）");
                controller.clickWindowPoint(PT_XUNLU_BTN[0], PT_XUNLU_BTN[1]);
                sleep(CLICK_MS);
            }
            if (round == 1) {
                listener.log("  ⚠ 面板未出现，可能角色不在城镇 —— 补按 T 回城后重试一轮");
                controller.sendKey(VK_T);
                sleep(TELEPORT_MS);
            }
        }

        String shot = snapshot("anomaly_nav_missing");
        abort("未能打开「自动寻路」面板",
                "点击右上角「寻路」按钮后没有出现「自动寻路」面板，流程已停止。\n\n"
                        + "常见原因：\n"
                        + "  ① 角色不在城镇里\n"
                        + "  ② 有其它弹窗遮挡了右上角\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "请处理后重新点击「工资」。");
    }

    /** 关闭「自动寻路」面板（点右上角 X，位置跟着实测框走）。 */
    private void closeNavPanel() {
        for (int i = 1; i <= 3; i++) {
            if (!bigPanelOpen()) {
                listener.log("  ✔ 「自动寻路」面板已关闭");
                return;
            }
            if (i == 1) {
                measureNavPanel(); // 面板可能漂过，按最新框位重算 X
            }
            listener.log("  点击面板右上角 X @ (" + curClose[0] + "," + curClose[1] + ")（第 " + i + " 次）");
            controller.clickWindowPoint(curClose[0], curClose[1]);
            sleep(CLICK_MS);
        }
        if (bigPanelOpen()) {
            String shot = snapshot("anomaly_nav_still_open");
            abort("「自动寻路」面板未能关闭",
                    "「自动寻路」面板一直没关掉。\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请手动关掉后重新点击「工资」。");
        }
    }

    /**
     * 点进坐标输入框 → 清空（截图验证真的空了）→ 输入数字（字形计数验证 + 自动纠正）。
     *
     * <p>「11 7 被填成 11 77」的最终修复：根因是后台 PostMessage 的 WM_KEYDOWN+WM_KEYUP
     * 被游戏翻译成<b>两个</b>字符（'7'→"77"），坐标框限长 2 位，后续输入被吞。
     * 现在：①输入走 WM_CHAR（一条消息一个字符，根上杜绝翻倍）；
     * ②输入后数「字形个数」——白像素总数分不清 "7"/"77"（都 ≈42），字形数能分清；
     * ③字形偏多就退格删掉、偏少就补上、一个没有就退回逐位按键（每打一位数一次，翻倍立刻退格），
     * 最多纠正 4 轮；仍不行整体重填，3 轮不行带截图停止。
     */
    private void fillCoordBox(int[] pt, String text, String label) {
        for (int round = 1; round <= 3; round++) {
            checkStop();
            if (round > 1) {
                listener.log("  " + label + " 第 " + round + " 次尝试（点击稍微偏移重点）");
            }
            int wBefore = countBoxWhite(captureQuiet(), pt[0], pt[1]);
            if (round == 1 && wBefore > INK_EMPTY_MAX) {
                listener.log("  " + label + " 里有旧内容（白像素 " + wBefore + "），先清空再输入");
            }

            // 面板上下会漂 ~8px（框心 457~465 之间），横竖都要偏移才能兜住漂移
            int dx = (round == 2) ? -3 : (round == 3 ? 3 : 0);
            int dy = (round == 2) ? 8 : (round == 3 ? -8 : 0);
            controller.clickWindowPoint(pt[0] + dx, pt[1] + dy);
            sleep(500);

            // 清空：End 把光标挪到末尾，再连按退格
            controller.sendKeyNoFocus(VK_END);
            sleep(120);
            controller.sendKeyNoFocus(VK_END);
            sleep(120);
            for (int i = 0; i < 14; i++) {
                controller.sendKeyNoFocus(VK_BACK);
                sleep(55);
            }
            sleep(250);

            // 验证清空：框内白像素要么归零，要么比清空前少了至少一个数字的量
            int wCleared = countBoxWhite(captureQuiet(), pt[0], pt[1]);
            boolean cleared;
            if (wCleared < 0 || wBefore < 0) {
                cleared = true; // 截图不可用（罕见）：退化为旧流程，不阻塞任务
            } else if (wCleared <= INK_EMPTY_MAX) {
                if (wBefore > INK_EMPTY_MAX) {
                    listener.log("  ✔ " + label + " 已清空（白像素 " + wBefore + "→" + wCleared + "）");
                }
                cleared = true;
            } else if (wBefore - wCleared >= INK_CLEAR_DROP) {
                listener.log("  ✔ " + label + " 旧内容已清掉（白像素 " + wBefore + "→" + wCleared
                        + "，残留白色是手套光标压在框上，不影响输入）");
                cleared = true;
            } else {
                listener.log("  ⚠ " + label + " 清空未生效（白像素 " + wBefore + "→" + wCleared + "）");
                cleared = false;
            }
            if (!cleared) {
                continue; // 重新点击 + 重新清空
            }

            // 输入数字：后台走 WM_CHAR（一条消息一个字符）。
            // 千万别退回 sendKeyNoFocus 敲数字 —— 实测这个游戏会把一条 PostMessage 的
            // WM_KEYDOWN+WM_KEYUP 翻译成两个字符（打 '7' 变 "77"），这正是 77 事故的根因。
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= '0' && c <= '9') {
                    controller.sendCharNoFocus(c);
                    sleep(180);
                }
            }
            sleep(350);

            // 验证 + 纠正：数字形个数，必须恰好等于目标位数
            // （白像素数分不清 "7" 和 "77" —— 两者都是 ~42，前两版修复就栽在这）
            // 读数必须用「稳定帧」：单帧验证会拿到过期画面（09:24 孝廉 88 事故——
            // 旧帧判"通过"，实际已是翻倍的 88，角色被指路到不存在的坐标）。
            boolean refocusRetried = false;
            for (int fix = 1; fix <= 4; fix++) {
                checkStop();
                int[] sc = stableBoxCounts(pt[0], pt[1]);
                if (sc == null) {
                    listener.log("  ⚠ " + label + " 连续 4 帧读数都不一致（渲染延迟大），等 1s 再验证");
                    sleep(1000);
                    continue;
                }
                int wTyped = sc[0];
                int glyphs = sc[1];
                // 单个 "8" 只有 ~24 白像素，旧门槛 25 会把正确的 8 误判成"没填进去"→乱补位
                int wFloor = Math.max(8, 9 * text.length());
                if (glyphs == text.length() && wTyped >= wFloor) {
                    listener.log("  ✔ " + label + " 已填入 " + text + "（字形 " + glyphs
                            + " 个，白像素 " + wCleared + "→" + wTyped + "，双帧一致）");
                    return;
                }
                if (glyphs > text.length()) {
                    // 多了：字符翻倍的残留（如 7→77），退格删掉多余字形再验证
                    int extra = glyphs - text.length();
                    listener.log("  ⚠ " + label + " 里字形偏多（" + glyphs + " 个 > " + text.length()
                            + "，疑似字符翻倍），退格删掉 " + extra + " 个再验证");
                    for (int i = 0; i < extra; i++) {
                        controller.sendKeyNoFocus(VK_BACK);
                        sleep(90);
                    }
                    sleep(250);
                    continue;
                }
                if (glyphs == 0) {
                    if (!refocusRetried) {
                        // 先重点框重新聚焦 + WM_CHAR 重打（安全路径）；vk 按键翻倍是老根子，只当最后兜底
                        refocusRetried = true;
                        listener.log("  ⚠ " + label + " 输入后框内为空，重点框聚焦后用 WM_CHAR 重打 " + text);
                        controller.clickWindowPoint(pt[0], pt[1]);
                        sleep(400);
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (c >= '0' && c <= '9') {
                                controller.sendCharNoFocus(c);
                                sleep(180);
                            }
                        }
                        sleep(400);
                        continue;
                    }
                    // WM_CHAR 重打也没进去，才退回逐位按键（每打一位验一次，翻倍立刻退格）
                    listener.log("  ⚠ " + label + " 重打仍为空，退回逐位按键（每打一位验一次，翻倍立刻退格）");
                    typeDigitsByKey(pt, text);
                    sleep(300);
                    continue;
                }
                // 少字：补上缺的尾部字符
                listener.log("  ⚠ " + label + " 里只有 " + glyphs + " 个字形（应为 "
                        + text.length() + "），补打后面缺的位");
                for (int i = glyphs; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c >= '0' && c <= '9') {
                        controller.sendCharNoFocus(c);
                        sleep(180);
                    }
                }
                sleep(350);
            }
            listener.log("  ⚠ " + label + " 多次纠正后字形仍不对，整体重填");
        }

        String shot = snapshot("anomaly_fill_fail");
        abort(label + "填写失败",
                "连续 3 次都没能把 " + text + " 稳妥填进" + label + "，为避免寻路找错位置已停止。\n\n"
                        + "常见原因：\n"
                        + "  ① 白色手套光标正好停在输入框上 —— 把鼠标移到游戏窗口外再重试\n"
                        + "  ② 面板位置漂移，点击没落进输入框\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "请处理后重新开始任务。");
    }

    private BufferedImage captureQuiet() {
        try {
            return controller.captureWindow();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 统计坐标框区域内的「白像素」数（RGB 三通道都 ≥200）。
     *
     * <p>窗口取中心 x±14、y±10：实测数字带渲染在框内偏下半部（2026-09-21 孝廉现场：
     * 实测框心 y=457、数字却在 y458~466），而且面板位置会上下漂 8px 左右，
     * 窗口不够高会把数字下半截切掉、把「已填对」误判成「没填进去」。
     * 数字是白字：单字 ≈17~50；空框 ≈0~6；手套光标压框会多 ≈60。截图不可用返回 -1。
     */
    private int countBoxWhite(BufferedImage img, int cx, int cy) {
        if (img == null) {
            return -1;
        }
        int x0 = Math.max(0, cx - 14), x1 = Math.min(img.getWidth() - 1, cx + 14);
        int y0 = Math.max(0, cy - 10), y1 = Math.min(img.getHeight() - 1, cy + 10);
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r >= WHITE_MIN && g >= WHITE_MIN && b >= WHITE_MIN) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 数坐标框里有几个「字形」（比白像素总数更硬：能分清 "7" 和 "77"）。
     *
     * <p>做法：窗口（同 countBoxWhite）内逐列统计白像素，连续非空列（允许 1 列内隙）
     * 算一个字形，空隙 ≥2 列分家；宽度 &lt;2 列的细条是文本光标，不算。
     * <b>手套光标排除</b>：手套 ~20px 大、必然贴到窗口边缘（历史帧 003807/213116 里
     * 手套指尖伸进框内曾被误数成第二个字形），凡贴左/右/下沿的簇、以及贴上沿且
     * 宽 &gt;8 的簇都不算；数字从不贴边（实测边距 ≥3px），不受影响。
     * 实测：空框/光标 =0，"7" =1（手套在框上也 =1），"77"/"11" =2。截图不可用返回 -1。
     */
    private int countGlyphs(BufferedImage img, int cx, int cy) {
        if (img == null) {
            return -1;
        }
        int x0 = Math.max(0, cx - 14), x1 = Math.min(img.getWidth() - 1, cx + 14);
        int y0 = Math.max(0, cy - 10), y1 = Math.min(img.getHeight() - 1, cy + 10);
        int w = x1 - x0 + 1;
        int[] col = new int[w];
        boolean[] top = new boolean[w];
        boolean[] bot = new boolean[w];
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r >= WHITE_MIN && g >= WHITE_MIN && b >= WHITE_MIN) {
                    col[x - x0]++;
                    if (y == y0) {
                        top[x - x0] = true;
                    }
                    if (y == y1) {
                        bot[x - x0] = true;
                    }
                }
            }
        }
        int glyphs = 0, run = 0, runPix = 0, gap = 0, runStart = -1;
        boolean rTop = false, rBot = false;
        for (int i = 0; i < w; i++) {
            if (col[i] > 0) {
                if (run == 0) {
                    runStart = i;
                    rTop = false;
                    rBot = false;
                }
                run++;
                runPix += col[i];
                gap = 0;
                rTop |= top[i];
                rBot |= bot[i];
            } else if (run > 0) {
                gap++;
                if (gap >= 2) {
                    if (acceptGlyph(runStart, run, runPix, rTop, rBot, w)) {
                        glyphs++;
                    }
                    run = 0;
                    runPix = 0;
                    gap = 0;
                    runStart = -1;
                }
            }
        }
        if (run > 0 && acceptGlyph(runStart, run, runPix, rTop, rBot, w)) {
            glyphs++;
        }
        return glyphs;
    }

    /** 字形簇验收：太细的是文本光标；贴边的是手套光标/框外杂物（见 countGlyphs）。 */
    private boolean acceptGlyph(int start, int len, int pix, boolean top, boolean bot, int winW) {
        if (len < 2 || pix < 4) {
            return false;
        }
        if (start == 0 || start + len == winW) {
            return false; // 贴左/右沿：手套或框外杂物
        }
        if (bot) {
            return false; // 贴下沿：手套从下方伸进来
        }
        if (top && len > 8) {
            return false; // 贴上沿且偏宽：手套从上方来（数字被面板漂移顶到上沿时宽度仍 ≤6，不受影响）
        }
        return true;
    }

    /**
     * 逐位按键输入（WM_CHAR 重打也不被接受时的最后兜底）：每打一位就用稳定帧数一次字形，
     * 按键被游戏翻倍成两个字符时立刻退格删掉多的那位，保证每位只落一个字形。
     */
    private void typeDigitsByKey(int[] pt, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            int[] before = stableBoxCounts(pt[0], pt[1]);
            int gBefore = before == null ? -1 : before[1];
            controller.sendKeyNoFocus(0x30 + (c - '0'));
            sleep(350);
            int[] after = stableBoxCounts(pt[0], pt[1]);
            int gAfter = after == null ? -1 : after[1];
            if (gBefore >= 0 && gAfter > gBefore + 1) {
                listener.log("    [逐位] 按键翻倍（字形 " + gBefore + "→" + gAfter
                        + "），退格 " + (gAfter - gBefore - 1) + " 次");
                for (int k = 0; k < gAfter - gBefore - 1; k++) {
                    controller.sendKeyNoFocus(VK_BACK);
                    sleep(90);
                }
                sleep(200);
            }
        }
    }

    /**
     * 稳定帧读数：连抓两帧（间隔 ~450ms），两帧的（白像素, 字形数）完全一致才采信。
     * 拥挤场景游戏渲染延迟大，PrintWindow 会拿到过期画面——09:24 孝廉事故：
     * 单帧验证抓到 "8" 的旧帧判通过，实际画面已是翻倍的 "88"。连续 4 轮不一致返回 null。
     */
    private int[] stableBoxCounts(int cx, int cy) {
        for (int k = 0; k < 4; k++) {
            BufferedImage a = captureQuiet();
            int wa = countBoxWhite(a, cx, cy);
            int ga = countGlyphs(a, cx, cy);
            sleep(450);
            BufferedImage b = captureQuiet();
            int wb = countBoxWhite(b, cx, cy);
            int gb = countGlyphs(b, cx, cy);
            if (wa >= 0 && ga >= 0 && wa == wb && ga == gb) {
                return new int[]{wa, ga};
            }
        }
        return null;
    }

    /**
     * 两框都填完后终检：字形数必须恰好等于目标位数（防 7→77、防串框如 11 被补成 117）。
     * 读数用稳定帧（双帧一致才采信，防过期画面假通过）。发现哪个框不对就重填哪个，
     * 两轮还不行就带截图停止。
     */
    private void verifyBothBoxes() {
        for (int round = 1; round <= 2; round++) {
            checkStop();
            int[] s1 = stableBoxCounts(curBox1[0], curBox1[1]);
            int[] s2 = stableBoxCounts(curBox2[0], curBox2[1]);
            if (s1 == null || s2 == null) {
                listener.log("  ⚠ 坐标终检读数不稳（渲染延迟大），等 1s 重试");
                sleep(1000);
                continue;
            }
            int g1 = s1[1], w1 = s1[0];
            int g2 = s2[1], w2 = s2[0];
            // 单个 "8" 只有 ~24 白像素，门槛按位数 9/位（旧门槛 25 会误杀单 8）
            boolean ok1 = g1 < 0 || (g1 == TARGET_X.length() && w1 >= Math.max(8, 9 * TARGET_X.length()));
            boolean ok2 = g2 < 0 || (g2 == TARGET_Y.length() && w2 >= Math.max(8, 9 * TARGET_Y.length()));
            if (ok1 && ok2) {
                listener.log("  ✔ 坐标终检通过：框1=" + TARGET_X + "（字形 " + g1
                        + "）、框2=" + TARGET_Y + "（字形 " + g2 + "），双帧一致");
                return;
            }
            listener.log("  ⚠ 坐标终检异常（框1 字形=" + g1 + "/应为 " + TARGET_X.length()
                    + "，框2 字形=" + g2 + "/应为 " + TARGET_Y.length() + "），重新填写异常的框");
            if (!ok1) {
                fillCoordBox(curBox1, TARGET_X, "第 1 个坐标框");
            }
            if (!ok2) {
                fillCoordBox(curBox2, TARGET_Y, "第 2 个坐标框");
            }
        }
        String shot = snapshot("anomaly_coords_final");
        abort("坐标终检未通过",
                "两次终检都没能确认两个坐标框里都有数字，为避免寻路找错位置已停止。\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "请看截图里「坐标」后面的两个框，手动确认后重新开始任务。");
    }

    // ==================== 接任务 ====================

    /**
     * G → 奋威中郎将 → 逐层 Enter 确认：
     * 「对话/任务」→「官爵任务」→「请交给我吧」。
     */
    void acceptSalaryQuest() {
        listener.log("—— 步骤 5：与奋威中郎将对话，接受官爵任务 ——");

        if (!pressGUntilDialog("奋威中郎将", 6)) {
            String shot = snapshot("anomaly_accept_dialog_missing");
            abort("未能唤起「奋威中郎将」对话",
                    "连续按了 6 次 G 都没出现带选项的对话，流程已停止。\n\n"
                            + "常见原因：\n"
                            + "  ① 角色还没走到 (11,7)（自动寻路没生效或被卡住）\n"
                            + "  ② 角色不在成都·子城\n"
                            + "  ③ 有弹窗遮挡\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请处理后重新点击「工资」。");
        }

        // 「对话/任务」→「官爵任务」→「请交给我吧」，每层回车确认第 1 项
        confirmDialogOptions(DIALOG_MAX_DEPTH, "接受任务");
        sleep(STEP_MS);
        dismissDialog();
        listener.log("  ✔ 官爵任务已接受");
        snapshot("05_quest_accepted");
    }

    // ==================== NPC 任务链 ====================

    /** 循环：点任务追踪红名 → 走过去 → G + Enter 对话 → 直到追踪里没有 NPC 名。 */
    void runNpcChain() {
        listener.log("—— 步骤 6：按「任务追踪」里的红名逐个拜访 NPC ——");

        if (!trackerHasNpcTarget()) {
            listener.log("  ⚠ 任务追踪里没有「NPC: xxx」，可能任务已完成，跳过");
            return;
        }

        for (int i = 1; i <= MAX_NPC; i++) {
            checkStop();
            listener.log("—— 第 " + i + "/" + MAX_NPC + " 个 NPC ——");

            // a. 先把残留对话关干净，否则这次点击只会被对话吃掉（角色不会动）
            dismissDialog();

            // b. 点「任务追踪」里的红色 NPC 名 → 自动寻路
            controller.clickWindowPoint(TR_CLICK_X, TR_CLICK_Y);
            sleep(SHORT_MS);
            snapshot("06_npc" + i + "_walk");
            listener.log("  已点任务追踪红名，角色自动寻路（一到就按 G，最多等 " + (NPC_WALK_MS / 1000) + " 秒）");
            waitForArrival(-1, -1, NPC_WALK_MS, STABLE);

            // c. 对话：G 唤起 → Enter 逐层确认 → ESC 关掉
            talkToNpc(i);

            // d. 看追踪里还有没有待拜访的 NPC
            if (!trackerHasNpcTarget()) {
                listener.log("  ✔ 「任务追踪」里已经没有 NPC 名 —— 官爵任务链完成");
                return;
            }
            listener.log("  任务追踪里还有 NPC 名，继续下一个");
        }

        String shot = snapshot("anomaly_npc_chain_incomplete");
        listener.alert("工资任务 · 需要人工确认",
                "已经连续拜访了 " + MAX_NPC + " 个 NPC，「任务追踪」里仍然有待拜访的 NPC 名。\n\n"
                        + "常见原因：\n"
                        + "  ① 某个 NPC 的对话没走完（选项不在第 1 项）\n"
                        + "  ② 自动寻路没走到位，按 G 对错了人\n"
                        + "  ③ 任务条件未满足（物品/等级/次数）\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "请手动处理后重新点击「工资」。");
    }

    /** 与当前 NPC 对话：按 G 唤起，Enter 逐层确认选项，最后 ESC 关掉。 */
    private void talkToNpc(int idx) {
        if (pressGUntilDialog("第 " + idx + " 个 NPC", G_MAX_TRIES)) {
            confirmDialogOptions(DIALOG_MAX_DEPTH, "NPC 对话");
        } else {
            listener.log("  ⚠ 按了 " + G_MAX_TRIES + " 次 G 都没出现带选项的对话"
                    + "（可能是叙述型对话，或没走到位）——先关掉残留再靠追踪面板判断");
        }
        dismissDialog();
    }

    /**
     * 连续按 Enter 逐层确认对话选项。
     *
     * <p>对话第 1 项默认高亮，回车等价于点它 —— 比点坐标稳
     * （实测选项高亮条的横向落点会左右浮动）。每确认一层都重新扫一次，
     * 没有带选项的对话就停下，避免在无对话时误按回车。
     */
    private void confirmDialogOptions(int maxDepth, String what) {
        for (int depth = 1; depth <= maxDepth; depth++) {
            checkStop();
            Ui ui = scanUi();
            if (!ui.dialogOpen) {
                if (depth == 1) {
                    listener.log("  （" + what + "：没有检测到带选项的对话，跳过回车）");
                }
                return;
            }
            listener.log("  " + what + " 第 " + depth + " 层：按 Enter 确认选项 1（首项 y=" + ui.option1Y + "）");
            controller.sendKey(VK_ENTER);
            sleep(CLICK_MS);
        }
    }

    // ==================== 任务追踪面板 ====================

    /**
     * 「任务追踪」面板里还有没有「NPC: xxx」这一行。
     *
     * <p>用<b>两个</b>条件一起判断，缺一不可：
     * <ol>
     *   <li>「NPC:」这几个<b>白字</b>的像素数 ≥ {@link #TR_WHITE_MIN}
     *       —— 有目标实测 58~76，没目标 0~56（其他玩家的白色名牌是主要干扰）。</li>
     *   <li>紧跟其后的<b>红色名字</b>像素数 ≥ {@link #TR_RED_MIN}
     *       —— 有目标实测 134~168，没目标 0~85。</li>
     * </ol>
     * 单看白字会被其他玩家的名牌骗到（实测 56 也出现过），所以必须同时有红名。
     */
    boolean trackerHasNpcTarget() {
        BufferedImage img = controller.captureWindow();
        if (img == null) {
            return false;
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;

        int white = 0;
        int wx0 = Math.max(0, (int) Math.round(TR_W_X0 * kx));
        int wx1 = Math.min(img.getWidth(), (int) Math.round(TR_W_X1 * kx));
        int wy0 = Math.max(0, (int) Math.round(TR_W_Y0 * ky));
        int wy1 = Math.min(img.getHeight(), (int) Math.round(TR_W_Y1 * ky));
        for (int y = wy0; y < wy1; y++) {
            for (int x = wx0; x < wx1; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (r > 225 && g > 225 && b > 225) {
                    white++;
                }
            }
        }

        int redBand = 0;
        int rx0 = Math.max(0, (int) Math.round(TR_RED_X0 * kx));
        int rx1 = Math.min(img.getWidth(), (int) Math.round(TR_RED_X1 * kx));
        int ry0 = Math.max(0, (int) Math.round(TR_RED_Y0 * ky));
        int ry1 = Math.min(img.getHeight(), (int) Math.round(TR_RED_Y1 * ky));
        for (int y = ry0; y < ry1; y++) {
            int cnt = 0;
            for (int x = rx0; x < rx1; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (r > 150 && (r - g) > 50 && (r - b) > 50) {
                    cnt++;
                }
            }
            if (cnt >= TR_RED_ROW_PX) {
                redBand += cnt;
            }
        }

        listener.log("  「NPC:」白字 " + white + "（≥" + TR_WHITE_MIN + "）"
                + "，红名 " + redBand + "（≥" + TR_RED_MIN + "）");
        return white >= TR_WHITE_MIN && redBand >= TR_RED_MIN;
    }

    // ==================== 对话与选项 ====================

    /**
     * 反复按 G 直到出现「带选项的对话」（首项高亮条）。
     *
     * <p>有些 NPC 的对话是叙述型（没有选项列表），这种情况本方法会一直返回 false ——
     * 调用方按「任务追踪有没有变化」来判断是否推进即可。
     */
    private boolean pressGUntilDialog(String what, int maxTries) {
        for (int i = 1; i <= maxTries; i++) {
            checkStop();
            Ui ui = scanUi();
            if (ui.dialogOpen) {
                listener.log("  ✔ 已检测到「" + what + "」对话（首项 y=" + ui.option1Y + "）");
                return true;
            }
            listener.log("  第 " + i + "/" + maxTries + " 次按 G 尝试唤起「" + what + "」对话");
            controller.sendKey(VK_G);
            sleep(DIALOG_WAIT_MS);
        }
        return false;
    }

    /**
     * 关掉可能残留的对话框。
     *
     * <p>ESC 在「有对话框时」是关闭，在「什么都没有时」会打开游戏系统菜单 ——
     * 所以按完 ESC 后再检查一次系统菜单，误开了就再按一次关掉。
     */
    private void dismissDialog() {
        for (int i = 1; i <= 2; i++) {
            controller.sendKey(VK_ESC);
            sleep(ESC_MS);
            if (systemMenuOpen()) {
                listener.log("  （ESC 打开了系统菜单，再按一次关掉）");
                controller.sendKey(VK_ESC);
                sleep(ESC_MS);
                return;
            }
            if (!scanUi().dialogOpen) {
                return;   // 没有带选项的对话，视为已干净
            }
        }
    }

    // ==================== 界面检测 ====================

    /** 一次扫描得到的界面信息。 */
    static final class Ui {
        boolean dialogOpen;
        int option1Y;
        /**
         * 高亮条的横向中心（窗口坐标）。
         *
         * <p>对话框每一屏的<b>横竖位置都会漂</b>（实测：四选项菜单的首行高亮条在
         * y=290、单选项菜单在 y=406、另一屏在 y=261），所以「点高亮行」必须
         * 横竖都用实测值，不能只固定 y 再用一个写死的 x（x 会掉到条外）。
         */
        int option1X;
    }

    /** 用当前实时画面做一次检测。 */
    Ui scanUi() {
        return scanImage(controller.captureWindow());
    }

    /** 对话高亮条检测（纯静态，便于离线自检）。 */
    static Ui scanImage(BufferedImage img) {
        Ui ui = new Ui();
        if (img == null) {
            return ui;
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x0 = Math.max(0, (int) Math.round(BAR_X0 * kx));
        int x1 = Math.min(img.getWidth(), (int) Math.round(BAR_X1 * kx));
        int y0 = Math.max(0, (int) Math.round(BAR_Y0 * ky));
        int y1 = Math.min(img.getHeight(), (int) Math.round(BAR_Y1 * ky));
        if (x1 <= x0 || y1 <= y0) {
            return ui;
        }

        // 收集「亮蓝像素」够多的行 : (row, 该行蓝像素的 xmin, xmax)
        int[] hits = new int[y1 - y0];
        int[] hx0 = new int[y1 - y0];
        int[] hx1 = new int[y1 - y0];
        int nh = 0;
        for (int y = y0; y < y1; y++) {
            int cnt = 0;
            int mn = Integer.MAX_VALUE, mx = -1;
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF;
                int b = p & 0xFF;
                if (r < 60 && (b - r) > 70 && b > 85) {
                    cnt++;
                    if (x < mn) {
                        mn = x;
                    }
                    if (x > mx) {
                        mx = x;
                    }
                }
            }
            if (cnt >= BAR_MIN_ROW_PX) {
                hits[nh] = y;
                hx0[nh] = mn;
                hx1[nh] = mx;
                nh++;
            }
        }
        if (nh == 0) {
            return ui;
        }

        // 按行聚带（允许 BAR_ROW_GAP 的空洞），取第一条带；同时记录整条带的横向范围
        int t = hits[0], b = hits[0];
        int bandX0 = hx0[0], bandX1 = hx1[0];
        int idx = 1;
        while (idx < nh && hits[idx] - b <= BAR_ROW_GAP) {
            b = hits[idx];
            if (hx0[idx] < bandX0) {
                bandX0 = hx0[idx];
            }
            if (hx1[idx] > bandX1) {
                bandX1 = hx1[idx];
            }
            idx++;
        }

        int span = b - t + 1;
        int cy = (t + b) / 2;
        if (span < BAR_SPAN_MIN || span > BAR_SPAN_MAX) {
            return ui;
        }
        if (cy < BAR_MIN_CY) {
            return ui;
        }
        ui.dialogOpen = true;
        ui.option1Y = cy;
        ui.option1X = (int) Math.round((bandX0 + bandX1) / 2.0 / kx);
        return ui;
    }

    /** 大面板（寻路面板）是否打开。 */
    boolean bigPanelOpen() {
        // 面板会整体上下漂 ~74px，固定区域暗块占比会漏判（09:44 现场：面板开着却报"没开"）。
        // 两个深色坐标框是「自动寻路」面板独有的指纹，位置无关且不误认 O 军团界面。
        return XiaolianTask.findNavBoxesCore(controller.captureWindow()) != null;
    }

    /** 系统菜单是否打开。 */
    boolean systemMenuOpen() {
        BufferedImage img = controller.captureWindow();
        if (img == null) {
            return false;
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x0 = Math.max(0, (int) Math.round(MENU_X0 * kx));
        int x1 = Math.min(img.getWidth(), (int) Math.round(MENU_X1 * kx));
        int y0 = Math.max(0, (int) Math.round(MENU_Y0 * ky));
        int y1 = Math.min(img.getHeight(), (int) Math.round(MENU_Y1 * ky));
        if (x1 <= x0 || y1 <= y0) {
            return false;
        }
        int hit = 0, n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                n++;
                if (r < 100 && g > 110 && b > 150 && (b - r) > 80) {
                    hit++;
                }
            }
        }
        return n > 0 && hit / (double) n > TH_MENU_FRAC;
    }

    /** x∈[PANEL_*] 区域内「深蓝青」像素占比（大面板打开时 ≈ 0.7~0.8）。 */
    private double panelFraction(BufferedImage img) {
        if (img == null) {
            return 0;
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x0 = Math.max(0, (int) Math.round(PANEL_X0 * kx));
        int x1 = Math.min(img.getWidth(), (int) Math.round(PANEL_X1 * kx));
        int y0 = Math.max(0, (int) Math.round(PANEL_Y0 * ky));
        int y1 = Math.min(img.getHeight(), (int) Math.round(PANEL_Y1 * ky));
        if (x1 <= x0 || y1 <= y0) {
            return 0;
        }
        int hit = 0, n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF, b = p & 0xFF;
                n++;
                if (r < 90 && (b - r) > 25 && b < 175) {
                    hit++;
                }
            }
        }
        return n > 0 ? hit / (double) n : 0;
    }

    /** 计算图片中指定采样区的平均 RGB（坐标按图片实际尺寸等比缩放）。 */
    private static double[] patchMean(BufferedImage img, int[] patch) {
        if (img == null) {
            return null;
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x = (int) Math.round(patch[0] * kx);
        int y = (int) Math.round(patch[1] * ky);
        int w = Math.max(1, (int) Math.round(patch[2] * kx));
        int h = Math.max(1, (int) Math.round(patch[3] * ky));
        if (x < 0 || y < 0 || x + w > img.getWidth() || y + h > img.getHeight()) {
            return null;
        }
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int j = y; j < y + h; j++) {
            for (int i = x; i < x + w; i++) {
                int p = img.getRGB(i, j);
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        return new double[]{sr / (double) n, sg / (double) n, sb / (double) n};
    }

    // ==================== 异常与收尾 ====================

    private void abort(String reason, String humanMessage) {
        listener.log("✘ 异常中止：" + reason);
        listener.alert("工资任务 · 需要人工处理", humanMessage);
        stopRequested = true;
        throw new Abort(reason);
    }

    private void checkStop() {
        if (stopRequested) {
            throw new Abort("已被用户中止");
        }
    }

    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            checkStop();
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.max(20, Math.min(200, left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Abort("线程被中断");
            }
        }
    }

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
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }
}
