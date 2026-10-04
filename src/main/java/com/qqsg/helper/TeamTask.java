package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.imageio.ImageIO;

/**
 * 「组队 / 集体召唤」一键执行器。
 *
 * <p>两个任务的流程<b>完全一样</b>（扫描 → 点图标 → 点确定），只有「找哪个图标」
 * 和文案不同 —— 用 {@link Kind} 区分，<b>不复制类</b>：
 * <ul>
 *   <li>{@link Kind#TEAM} —— 组队图标（橙色六边形群 + 蓝色底座）</li>
 *   <li>{@link Kind#SUMMON} —— 集体召唤的「传送」图标（橙色二字 + 蓝色箭头）。
 *       用户 2026-09-23 确认：<b>它出现的位置与组队图标一模一样</b>，
 *       所以搜索区、固定点、确定按钮全部沿用同一套标定。</li>
 * </ul>
 *
 * <h3>完整流程</h3>
 * <pre>
 *  1.  持续扫描「底部中间 · 技能栏上方」区域，用模板匹配找<b>目标图标</b>
 *        （组队 = 橙色六边形群 + 蓝色底座；集体召唤 = 「传送」二字 + 蓝色箭头。
 *        图标只存在约 10 秒就会消失，所以要一直扫）
 *  2.  找到图标 → 点击它（点图案中心）
 *  3.  屏幕中央弹出对话框，里面有个<b>红色确定按钮</b> → 点它
 *  4.  完成
 * </pre>
 *
 * <h3>标定结论（实机验证）</h3>
 * <ul>
 *   <li><b>图标特征</b>：5 个橙色六边形（R≈210~230, G≈110~165, B≈40~80）叠在蓝色底座
 *       （R≈0~90, G≈70~160, B≈170~215）上，橙色在上、蓝色在下。</li>
 *   <li><b>匹配方式</b>：拿模板里「橙色 + 蓝色」像素做掩码，逐点比较 RGB 距离，
 *       命中率 = 掩码内匹配上的像素数 / 掩码总数。实测贴图命中 1.000，
 *       真实游戏画面最高误报仅 0.213，阈值取 0.72 余量极大。</li>
 *   <li><b>搜索区</b>：窗口底部中间、技能栏上方。基准 1030x797 下取
 *       x∈[300,740]、y∈[590,715]。</li>
 *   <li><b>图标是瞬时的</b>（约 10 秒），所以主循环是「扫描 → 没找到就等一会再扫」，
 *       最多扫 {@link #SCAN_TOTAL_MS} 毫秒。</li>
 * </ul>
 *
 * <h3>坐标说明</h3>
 * 全部是「窗口内坐标」，基准分辨率 1030x797，运行时按窗口真实尺寸等比换算。
 */
public class TeamTask {

    /**
     * 任务种类：两个任务的「扫描 → 点图标 → 点确定」逻辑完全一样，
     * 只有<b>找哪个图标</b>和<b>文案</b>不同。
     *
     * <p>模板缓存是<b>实例级</b>的（不是 static）—— 这样两个种类各自加载自己的模板，
     * 「先跑组队、再跑集体召唤」不会串用上一份模板。
     */
    public enum Kind {
        /** 组队：橙色六边形群 + 蓝色底座。 */
        TEAM("组队", "组队图标", "/team/team_icon.png", "team_shots",
                "src/main/resources/team/team_icon.png", "resources/team/team_icon.png",
                "team_icon.png"),

        /**
         * 集体召唤（传送）：橙色「传送」二字 + 蓝色箭头。
         *
         * <p>用户 2026-09-23 确认：这个图标<b>出现的位置与组队图标一模一样</b>，
         * 所以搜索区、固定点、确定按钮全部沿用同一套标定，只把模板换成
         * {@code summon_icon.png}。
         */
        SUMMON("集体召唤", "传送图标", "/team/summon_icon.png", "summon_shots",
                "src/main/resources/team/summon_icon.png", "resources/team/summon_icon.png",
                "summon_icon.png");

        /** 任务名（日志用），如「组队」「集体召唤」。 */
        final String taskName;
        /** 图标名（日志用），如「组队图标」「传送图标」。 */
        final String iconName;
        /** jar 内模板资源路径。 */
        final String res;
        /** 截图目录。 */
        final String shotDir;
        /** 开发期回退路径（直接跑 build_test 时用）。 */
        final String[] fallback;

        Kind(String taskName, String iconName, String res, String shotDir, String... fallback) {
            this.taskName = taskName;
            this.iconName = iconName;
            this.res = res;
            this.shotDir = shotDir;
            this.fallback = fallback;
        }
    }

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

    private static final int BASE_W = GameWindowController.BASE_WIDTH;
    private static final int BASE_H = GameWindowController.BASE_HEIGHT;

    // ==================== 图标模板 ====================
    // 模板资源路径 / 回退路径 / 截图目录都由 Kind 提供（见上面的枚举）。

    /**
     * 「偏远命中」的判定半径（窗口坐标，基准 1030x797）。
     *
     * <p>用户确认两个图标出现的位置完全一样，但组队图标历史实测会偶尔漂 20px 左右，
     * 所以不直接砍掉远处命中，而是<b>对远处命中要求更高分</b>（见 {@link #FAR_MATCH_TH}）。
     */
    private static final int NEAR_R = 26;

    /**
     * 偏远命中（距固定点 &gt; {@link #NEAR_R}）要求的最低分；固定点附近用 {@link #MATCH_TH}。
     *
     * <p><b>为什么加这条</b>（2026-09-23 实测数据，仅对 {@link Kind#SUMMON} 生效）：
     * 拿 347 张真实游戏帧回放，传送模板在组队画面上有 5 帧误报，分数
     * 0.803 / 0.808 / 0.811 / 0.877 / 0.902 —— 命中的全是<b>画面里的橙色宠物、
     * 组队邀请界面的大片红蓝 UI</b>，位置距固定点 27~29px。真正的图标（含合成正样本）
     * 稳定落在固定点 ±3px 内、分数 1.000。所以：近点照常 0.80，远点要 0.95 才认。
     */
    private static final double FAR_MATCH_TH = 0.95;

    /**
     * 命中率阈值：高于它才算找到。
     *
     * <p>用「颜色类别」匹配（把像素分成 橙 / 蓝 / 其它 三类，比类别是否一致，
     * 而不是比精确 RGB）。实测：有图标的帧最高 <b>0.968</b>，没有图标的帧最高只有 <b>0.651</b>，
     * 阈值取 0.80 余量充足。
     *
     * <p>为什么不用精确 RGB：游戏里图标比模板的橙色更亮（模板来自缩放过的截图），
     * 精确 RGB 距离对亮度太敏感 —— 同一个位置实测只有 0.688（低于阈值会漏检）。
     */
    private static final double MATCH_TH = 0.80;
    /** 抽查锚点数：先比几个点，太少就直接跳过，省时间 */
    private static final int ANCHOR_N = 8;
    /** 锚点通过数下限（低于它直接跳过该位置） */
    private static final int ANCHOR_MIN = 6;

    // ==================== 搜索区（用户现场红框限定，2026-09-28） ====================
    //
    // 用户 2026-09-28 现场画框并明确要求「找图必须在红框内，注意是范围限定」。
    // 两张现场图是 1005x795（截图工具左裁约 23px、上裁约 2px），换算到窗口坐标（基准 1030x797）：
    //     x_win ≈ 1.0048·x_shot + 20.4      y_win ≈ 1.00252·y_shot
    // 系数用「确定/取消」按钮实测中心 (427.5,473.5)/(531,473.5) 对表 CONFIRM_FIX/CANCEL_FIX 反推，
    // 再用 ICON_FIX (556,657) 交叉验证（误差 ≤3px）。

    /**
     * 组队 / 集体召唤<b>图标</b>的搜索框（窗口坐标，基准 1030x797）。
     *
     * <p>用户红框截图 (467,611,627,688) → 窗口 {490, 634, 629, 690}；
     * 框中心恰为 {@link #ICON_FIX_X},{@link #ICON_FIX_Y} = (556,657)。
     * <b>找图标只在这个框里做，不再全区扫。</b>
     */
    private static final int ICON_BOX_X0 = 490, ICON_BOX_X1 = 634;
    private static final int ICON_BOX_Y0 = 629, ICON_BOX_Y1 = 690;

    /**
     * 组队邀请对话框（「确定」按钮）的搜索框（窗口坐标）。
     *
     * <p>用户红框截图 (321,636,290,521) → 窗口 {343, 660, 291, 522}；
     * 框中心 x=501 恰为确定(450)/取消(554)的中点。
     * <b>找「确定」按钮只在这个框里做。</b>
     */
    private static final int DIALOG_BOX_X0 = 343, DIALOG_BOX_X1 = 660;
    private static final int DIALOG_BOX_Y0 = 291, DIALOG_BOX_Y1 = 522;

    // ==================== 固定位置（用户确认：图标与按钮都是固定的） ====================

    /**
     * 组队/召唤图标的<b>固定中心</b>（窗口内坐标，基准 1030x797）。
     *
     * <p>来源：用户截图（2026-09-21）+ 历史实测帧。图标固定出现在技能栏上方 ——
     * 2026-09-28 日志实测「固定位置命中：(556,657)，命中率 0.920」。
     *
     * <p>搜索区已改为用户红框 {@link #ICON_BOX_X0}..{@link #ICON_BOX_Y1}（框中心就是这个点），
     * 这里保留中心值只给 {@link #acceptHit} 的「偏远命中」判定用。
     */
    private static final int ICON_FIX_X = 556, ICON_FIX_Y = 657;

    /**
     * 「确定」按钮的<b>固定中心</b>（组队邀请对话框的左按钮）。
     *
     * <p>来源：25 张历史成功帧测量 —— 主流位置 14/23 帧完全一致 =
     * 确定 (450,475)、取消 (554,475)，与用户 2026-09-21 截图实测值
     * （450,475 / 554,475）完全吻合。其余帧是「好友面板开着把对话框挤下去」
     * 的变体位置 —— 那种情况由后面的全区域检测兜底。
     */
    private static final int CONFIRM_FIX_X = 450, CONFIRM_FIX_Y = 475;
    /** 「取消」按钮固定中心（校验用：两个按钮同时在场才算对话框真的弹了）。 */
    private static final int CANCEL_FIX_X = 554, CANCEL_FIX_Y = 475;
    /** 两个按钮<b>中间的空隙</b>固定中心（x 480~520 这一段必须是空的）。 */
    private static final int GAP_FIX_X = 500, GAP_FIX_Y = 475;
    /** 固定点校验盒半径（x / y）。 */
    private static final int FIX_BOX_RX = 32, FIX_BOX_RY = 14;
    /** 间隙盒半径（比按钮盒窄，只覆盖两按钮正中间那段）。 */
    private static final int GAP_BOX_RX = 16;
    /**
     * 单个按钮盒内「按钮橙」像素下限。
     *
     * <p>实测：对话框帧中位 <b>422 / 433</b>，没有对话框的帧中位 <b>0~9</b>，
     * 阈值取 300 上下都有大余量。
     */
    private static final int FIX_BOX_MIN_PX = 300;
    /**
     * 间隙盒允许的最大「按钮橙」像素数。
     *
     * <p>真对话框两个按钮中间是<b>空的</b>（列簇实测 x480~520 全空）；而地面、
     * 火焰这类误报源是<b>连成一片</b>的橙，中间不会空。加这一条能把误报压到 0
     * —— 实测其它两个任务的 513 张截图 0 误报。
     */
    private static final int GAP_BOX_MAX_PX = 80;

    // ==================== 确认按钮检测（对话框里的橙色「确定」按钮） ====================

    // 检测区已改为「用户红框」DIALOG_BOX_X0..DIALOG_BOX_Y1（见文件上方「搜索区」段）。
    // 原先的 BTN 380..665 / 418..555 会在红框外扫，2026-09-28 用户要求「只在红框内找图」后废弃。
    // 历史实测：基准 1030x797 下两个按钮落在 y≈462~482、x≈422~581（确定 450,475 / 取消 554,475）。
    /** 按钮橙红像素判定：R 高、G 中、B 低 */
    private static final int BTN_R_MIN = 150, BTN_G_MIN = 50, BTN_G_MAX = 180;
    private static final int BTN_B_MAX = 95, BTN_RB_MIN = 90;

    // ---- 「按钮芯色」：比上面严格得多，只认按钮本体那种鲜橙 ----
    /**
     * <p>上面那套宽松判据（R&gt;150）会把游戏地面、土壤的<b>土黄棕</b>
     * （实测主色 (153,102,34)、(149,51,14)）也算成按钮色 —— 用它做固定点校验时
     * 误报率高达 34%（42/123）。
     *
     * <p>按钮本体实测主色是 <b>(203,84,26) (229,96,27) (239,98,26) (221,103,53) (222,110,64)</b>
     * —— 共同点是 R 高（≥195）、G 中等偏暗（70~130）、B 很低、R−G 差大。
     * 这套判据只用于<b>固定位置校验</b>，全区域聚类仍用宽松版（避免改动既有表现）。
     */
    private static final int CORE_R_MIN = 195, CORE_G_MIN = 70, CORE_G_MAX = 130;
    private static final int CORE_B_MAX = 75, CORE_RG_MIN = 85;
    /** 第一步：行橙红像素下限（找按钮所在的行带） */
    private static final int BTN_ROW_MIN = 60;
    /** 行带允许空洞 */
    private static final int BTN_ROW_GAP = 8;
    /** 第二步：行带内，单列橙红像素下限 */
    private static final int BTN_COL_MIN = 8;
    /** 列簇允许空洞 —— 要足够大，才能跨过按钮里的白字（「确定」两个字会把按钮切成两段） */
    private static final int BTN_COL_GAP = 16;
    /** 单个按钮的橙红像素下限 */
    private static final int BTN_MIN_PX = 250;
    /** 按钮尺寸范围（排除噪点与细边框） */
    private static final int BTN_W_MIN = 30, BTN_W_MAX = 100;
    private static final int BTN_H_MIN = 12, BTN_H_MAX = 40;

    // ==================== 节奏 ====================

    /** 单次扫描间隔 */
    private static final int SCAN_INTERVAL_MS = 600;
    /** 最多扫描多久（图标瞬时出现，给足 2 分钟） */
    private static final long SCAN_TOTAL_MS = 120_000L;
    /** 点图标后等对话框弹出 */
    private static final int DIALOG_WAIT_MS = 1200;
    /** 找「确定」按钮的最多轮数 */
    private static final int BTN_MAX_TRIES = 8;

    // ==================== 模板缓存（实例级：组队与集体召唤各持一份，互不干扰） ====================

    private boolean tplTried = false;
    /** 模板的「颜色类别」图：1=橙 2=蓝 0=其它（背景/描边） */
    private byte[] tplCls;
    private int tplW, tplH, tplMaskN;
    /** 锚点在模板里的下标（tplCls 数组下标） */
    private int[] anchorIdx;

    // ==================== 运行状态 ====================

    private final GameWindowController controller;
    private final Listener listener;
    /** 任务种类（组队 / 集体召唤）。 */
    private final Kind kind;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;

    private final File shotDir;

    /** 默认「组队」任务（保留原构造签名，Probe / 离线回归照旧可用）。 */
    public TeamTask(GameWindowController controller, Listener listener) {
        this(controller, listener, Kind.TEAM);
    }

    public TeamTask(GameWindowController controller, Listener listener, Kind kind) {
        this.controller = controller;
        this.listener = listener;
        this.kind = (kind == null) ? Kind.TEAM : kind;
        this.shotDir = new File(this.kind.shotDir);
    }

    /** 任务种类（日志 / 界面用）。 */
    public Kind getKind() {
        return kind;
    }

    public boolean isRunning() {
        return running;
    }

    public void requestStop() {
        stopRequested = true;
    }

    public void start() {
        if (running) {
            listener.log(kind.taskName + "任务已在运行中");
            return;
        }
        running = true;
        stopRequested = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String summary;
                // 后台模式作用域：进任务时记录用户原设置，退出时原样还原
                boolean savedBg = controller.isRunInBackground();
                try {
                    // 组队是「全后台」任务：抓图走 PrintWindow、点击走 PostMessage，
                    // 全程不抢焦点、不移动真实鼠标，所以游戏窗口被遮挡/在后台也能跑。
                    controller.setRunInBackground(true);
                    listener.log(kind.taskName + "任务以「后台模式」运行（不抢焦点、不移动鼠标）");

                    execute();
                    ok = true;
                    summary = "已完成" + kind.taskName + "点击";
                } catch (Abort a) {
                    summary = a.getMessage();
                } catch (Throwable e) {
                    e.printStackTrace();
                    summary = "执行异常：" + e;
                } finally {
                    // 还原用户原本的后台开关状态
                    controller.setRunInBackground(savedBg);
                    running = false;
                }
                try {
                    listener.finished(ok, summary);
                } catch (Throwable ignore) {
                    // 回调异常不影响线程收尾
                }
            }
        }, "TeamTask-" + kind.name());
        t.setDaemon(true);
        t.start();
    }

    // ==================== 主流程 ====================

    private void execute() {
        listener.log("================ " + kind.taskName + " 开始 ================");

        Rectangle r = controller.getWindowRect();
        if (r == null) {
            throw new Abort("找不到游戏窗口，请先启动 QQ三国 并确认窗口未关闭");
        }
        listener.log("游戏窗口：" + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");
        controller.ensureWindowVisible(); // 窗口被最小化时截图/坐标全失效，先还原
        if (!ensureTemplate()) {
            throw new Abort(kind.iconName + "模板加载失败（jar 里缺少 " + kind.res + "）");
        }
        listener.log(kind.iconName + "模板已加载：" + tplW + "x" + tplH + "，有效像素 " + tplMaskN);

        int[] savedCursor = controller.getCursorPosition();
        try {
            if (controller.isRunInBackground()) {
                // 后台模式：绝不抢焦点、不挪鼠标（挪了反而会打断正在用电脑的人）
                listener.log("后台模式：不抢焦点，直接给游戏窗口发消息");
            } else {
                controller.focusWindow();
                sleep(500);
                listener.log("已把游戏窗口切到前台");
            }

            // ---------- 1. 扫描图标 ----------
            int[] hit = scanForIcon();
            if (hit == null) {
                String shot = snapshot("anomaly_icon_not_found");
                listener.alert(kind.taskName + "任务 · 没找到图标",
                        "在红框（" + ICON_BOX_X0 + "," + ICON_BOX_Y0 + " ~ " + ICON_BOX_X1 + ","
                                + ICON_BOX_Y1 + "）里扫了 " + (SCAN_TOTAL_MS / 1000) + " 秒，都没找到"
                                + kind.iconName + "。\n\n"
                                + "这个图标只存在约 10 秒就会消失，请再点一次「" + kind.taskName + "」按钮。\n"
                                + "（后台模式下无需把游戏窗口放在最前，保持窗口不被最小化即可。）\n\n"
                                + (shot != null ? "现场截图：" + shot + "\n\n" : ""));
                throw new Abort("未找到" + kind.iconName);
            }
            listener.log("✔ 找到" + kind.iconName + "：窗口坐标 (" + hit[0] + "," + hit[1] + ")，命中率 "
                    + String.format("%.3f", hit[2] / 1000.0));
            snapshot("01_icon_found");

            // ---------- 2. 点图标 ----------
            listener.log("—— 点击" + kind.iconName + " ——");
            controller.clickWindowPoint(hit[0], hit[1]);
            sleep(DIALOG_WAIT_MS);
            snapshot("02_after_icon_click");

            // ---------- 3. 点对话框里的橙色「确定」按钮 ----------
            clickConfirmButton();

            snapshot("99_done");
            listener.log("✔ " + kind.taskName + "流程执行完毕");
        } finally {
            // 只有前台模式才需要还原鼠标（后台模式没动过真实鼠标）
            if (!controller.isRunInBackground() && savedCursor != null) {
                controller.moveCursor(savedCursor[0], savedCursor[1]);
            }
        }
    }

    /** 循环扫描，直到找到图标 / 超时 / 被中止。返回 {窗口x, 窗口y, 命中率*1000}。 */
    private int[] scanForIcon() {
        listener.log("—— 步骤 1：扫描" + kind.iconName + "（只扫红框 "
                + ICON_BOX_X0 + "," + ICON_BOX_Y0 + " ~ " + ICON_BOX_X1 + "," + ICON_BOX_Y1
                + "，最多 " + (SCAN_TOTAL_MS / 1000) + " 秒）——");
        long deadline = System.currentTimeMillis() + SCAN_TOTAL_MS;
        int round = 0;
        while (System.currentTimeMillis() < deadline) {
            checkStop();
            round++;
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                listener.log("  第 " + round + " 次扫描：抓图失败，稍后重试");
                sleep(SCAN_INTERVAL_MS);
                continue;
            }
            // 用户 2026-09-28：找图标只在红框内做（不再全区扫）
            int[] res = findIcon(img);
            if (res != null) {
                listener.log("  ✔ 红框内命中：(" + res[0] + "," + res[1] + ")，命中率 "
                        + String.format("%.3f", res[2] / 1000.0));
                return res;
            }
            if (round == 1 || round % 10 == 0) {
                listener.log("  第 " + round + " 次扫描：红框内暂未出现（框内最高分 "
                        + String.format("%.3f", lastBest / 1000.0) + "）");
            }
            sleep(SCAN_INTERVAL_MS);
        }
        return null;
    }

    /**
     * 只在<b>用户红框 {@link #ICON_BOX_X0}..{@link #ICON_BOX_Y1}</b> 里做模板匹配。
     *
     * <p>框只有全区的一小块：速度更快，更重要的是场景里其它橙色元素
     * （技能栏、聊天区的玩家名字等）根本不进框，识别率天然提高。
     * 阈值与原先相同（{@link #MATCH_TH}），不会放宽标准。
     */
    int[] findIconFixed(BufferedImage img) {
        if (!ensureTemplate() || img == null) {
            return null;
        }
        int W = img.getWidth(), H = img.getHeight();
        double kx = W / (double) BASE_W, ky = H / (double) BASE_H;
        int x0 = clamp((int) Math.round(ICON_BOX_X0 * kx), 0, Math.max(0, W - tplW));
        int y0 = clamp((int) Math.round(ICON_BOX_Y0 * ky), 0, Math.max(0, H - tplH));
        int x1 = clamp((int) Math.round(ICON_BOX_X1 * kx), 0, W - tplW);
        int y1 = clamp((int) Math.round(ICON_BOX_Y1 * ky), 0, H - tplH);
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        byte[] cls = classMap(img);
        double best = 0;
        int bx = -1, by = -1;
        for (int oy = y0; oy <= y1; oy++) {
            for (int ox = x0; ox <= x1; ox++) {
                double sc = matchScoreAt(cls, W, ox, oy);
                if (sc > best) {
                    best = sc;
                    bx = ox;
                    by = oy;
                }
            }
        }
        if (best >= MATCH_TH && bx >= 0) {
            lastBest = (int) Math.round(best * 1000);
            int icx = (int) Math.round((bx + tplW / 2.0) / kx);
            int icy = (int) Math.round((by + tplH / 2.0) / ky);
            if (!acceptHit(icx, icy, best)) {
                return null;
            }
            return new int[]{icx, icy, (int) Math.round(best * 1000)};
        }
        return null;
    }

    /**
     * 命中位置是否可接受：固定点附近用 {@link #MATCH_TH}，
     * 偏远位置（距固定点 &gt; {@link #NEAR_R}）要求 {@link #FAR_MATCH_TH}。
     *
     * <p>加严规则只对 {@link Kind#SUMMON} 生效（实测数据见 {@link #FAR_MATCH_TH}），
     * 组队任务的行为保持原样，一点没动。
     */
    private boolean acceptHit(int cx, int cy, double score) {
        if (kind != Kind.SUMMON) {
            return true;
        }
        double dx = cx - ICON_FIX_X, dy = cy - ICON_FIX_Y;
        if (dx * dx + dy * dy > (double) NEAR_R * NEAR_R && score < FAR_MATCH_TH) {
            listener.log("    ⚠ 忽略偏远命中 (" + cx + "," + cy + ")：命中率 "
                    + String.format("%.3f", score) + " < " + FAR_MATCH_TH
                    + "，距固定点 " + Math.round(Math.sqrt(dx * dx + dy * dy)) + "px");
            return false;
        }
        return true;
    }

    /** 计算某位置的模板命中率（0~1）。供固定窗与全区扫描共用。 */
    private double matchScoreAt(byte[] cls, int W, int ox, int oy) {
        // 锚点快速预筛
        int pass = 0;
        for (int a = 0; a < anchorIdx.length; a++) {
            int ti = anchorIdx[a];
            if (cls[(oy + ti / tplW) * W + (ox + ti % tplW)] == tplCls[ti]) {
                pass++;
            }
        }
        if (anchorIdx.length > 0 && pass < ANCHOR_MIN) {
            return 0;
        }
        int ok = 0;
        for (int ty = 0; ty < tplH; ty++) {
            int rowBase = (oy + ty) * W + ox;
            int tBase = ty * tplW;
            for (int tx = 0; tx < tplW; tx++) {
                int ti = tBase + tx;
                byte tc = tplCls[ti];
                if (tc != 0 && cls[rowBase + tx] == tc) {
                    ok++;
                }
            }
        }
        return ok / (double) tplMaskN;
    }

    /**
     * 点对话框里的橙色「确定」按钮。
     *
     * <p><b>固定位置优先</b>：确定按钮固定在 (450,475)（25 帧实测 + 用户截图确认），
     * 先用「确定盒 + 取消盒 双校验」确认对话框真的在 —— 两个按钮同时在场，
     * 误判率几乎为零 —— 在就直接点固定坐标，不走易受干扰的全区域聚类。
     * 固定点连续几轮都不在（比如好友面板把对话框挤下去了），再退回全区域检测。
     */
    private void clickConfirmButton() {
        listener.log("—— 步骤 3：点击「确定」按钮（固定位置 "
                + CONFIRM_FIX_X + "," + CONFIRM_FIX_Y + " 优先）——");
        int fixedMiss = 0;
        for (int i = 1; i <= BTN_MAX_TRIES; i++) {
            checkStop();
            BufferedImage img = controller.captureWindow();

            // 快路径：固定位置双按钮校验
            if (img != null && confirmFixedPresent(img)) {
                listener.log("  ✔ 固定位置三条件校验通过（确定+取消都在、中间是空的）：直接点 ("
                        + CONFIRM_FIX_X + "," + CONFIRM_FIX_Y + ")");
                snapshot("03_confirm_button");
                controller.clickWindowPoint(CONFIRM_FIX_X, CONFIRM_FIX_Y);
                sleep(DIALOG_WAIT_MS);
                listener.log("  ✔ 已点击「确定」");
                return;
            }

            // 慢路径：全区域聚类检测（兜住对话框被其它面板挤走的情况）
            int[] btn = (img == null) ? null : findConfirmButton(img);
            if (btn != null) {
                listener.log("  ✔ 全区域检测到「确定」按钮：(" + btn[0] + "," + btn[1]
                        + ")，橙红像素 " + btn[2] + (fixedMiss > 0
                        ? "（固定点缺席 " + fixedMiss + " 轮后由全区域检测命中）" : ""));
                snapshot("03_confirm_button");
                controller.clickWindowPoint(btn[0], btn[1]);
                sleep(DIALOG_WAIT_MS);
                listener.log("  ✔ 已点击「确定」");
                return;
            }

            fixedMiss++;
            listener.log("  第 " + i + "/" + BTN_MAX_TRIES + " 次：固定位置无对话框、全区域也没找到按钮，稍候再试");
            sleep(DIALOG_WAIT_MS);
        }

        // 兜底：可能对话框结构不同，用回车确认（多数对话框回车 = 确定）
        listener.log("  ⚠ 一直没检测到橙色按钮，改用回车确认");
        controller.sendKey(WindowUtils.VK_RETURN);
        sleep(DIALOG_WAIT_MS);
        snapshot("03b_enter_fallback");
    }

    /**
     * 固定点校验，三个条件<b>同时</b>满足才算对话框真的弹出来了：
     * <ol>
     *   <li>确定盒 (450,475) 内「按钮芯色」像素 ≥ 300；</li>
     *   <li>取消盒 (554,475) 内同样 ≥ 300；</li>
     *   <li>两按钮<b>中间的空隙</b> (500,475) 内 &lt; 80（必须是空的）。</li>
     * </ol>
     *
     * <p>为什么必须校验这么多：
     * <ul>
     *   <li>只查「确定」盒 —— 场景里的火焰特效、橙色地面都可能凑够像素
     *       （用宽松判据时实测误报率高达 34%，42/123）。</li>
     *   <li>加了「取消」盒还不够 —— 地面橙色常常是<b>连成一片</b>的，两个盒子会一起命中；
     *       而真对话框两按钮中间是<b>空的</b>，加间隙条件后误报降到 <b>0</b>
     *       （另外两个任务的 513 张截图实测 0 误报）。</li>
     * </ul>
     *
     * <p>三个盒子（确定 418~482 / 取消 522~586 / 间隙 484~516，y 461~489）本身就完全落在
     * 用户红框 {@link #DIALOG_BOX_X0}..{@link #DIALOG_BOX_Y1} 内 —— 天然满足「只在红框内找」。
     *
     * <p>离线回归：{@code python _dev/fixtest.py team_shots xiaolian_shots yunsong_shots}
     * —— 正样本 26/29、误报 0/38，剩下 3 帧由全区域检测兜住（最终 29/29 = 100%）。
     */
    static boolean confirmFixedPresent(BufferedImage img) {
        if (img == null) {
            return false;
        }
        return fixedButtonBoxCount(img, CONFIRM_FIX_X, CONFIRM_FIX_Y, FIX_BOX_RX) >= FIX_BOX_MIN_PX
                && fixedButtonBoxCount(img, CANCEL_FIX_X, CANCEL_FIX_Y, FIX_BOX_RX) >= FIX_BOX_MIN_PX
                && fixedButtonBoxCount(img, GAP_FIX_X, GAP_FIX_Y, GAP_BOX_RX) < GAP_BOX_MAX_PX;
    }

    /**
     * 以 (cx,cy) 为中心、半径 rx × {@link #FIX_BOX_RY} 的盒子里有多少「按钮芯色」像素。
     *
     * <p>离线回归要打印具体数值，所以单独暴露；计数不做提前返回截断，
     * 否则返回值会一律卡在阈值上，看不出真实分离度。
     */
    static int fixedButtonBoxCount(BufferedImage img, int cx, int cy, int rx) {
        if (img == null) {
            return 0;
        }
        int W = img.getWidth(), H = img.getHeight();
        double kx = W / (double) BASE_W, ky = H / (double) BASE_H;
        int x0 = clamp((int) Math.round((cx - rx) * kx), 0, W - 1);
        int x1 = clamp((int) Math.round((cx + rx) * kx), 0, W - 1);
        int y0 = clamp((int) Math.round((cy - FIX_BOX_RY) * ky), 0, H - 1);
        int y1 = clamp((int) Math.round((cy + FIX_BOX_RY) * ky), 0, H - 1);
        int hit = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (isBtnCoreColor(img.getRGB(x, y))) {
                    hit++;
                }
            }
        }
        return hit;
    }

    // ==================== 模板加载与匹配 ====================

    /**
     * 把一个像素分成三类：1=橙、2=蓝、0=其它。
     *
     * <p>阈值刻意放宽（比起「精确 RGB」），这样游戏里更亮/更暗的渲染都能落进同一类。
     */
    private static byte classify(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        if (r > 140 && (r - b) > 50 && (r - g) > 20 && g < 200) {
            return 1; // 橙
        }
        if (b > 80 && (b - r) > 20) {
            return 2; // 蓝
        }
        return 0; // 其它
    }

    private synchronized boolean ensureTemplate() {
        if (tplTried) {
            return tplCls != null;
        }
        tplTried = true;
        BufferedImage t = loadTemplateImage();
        if (t == null) {
            return false;
        }
        int w = t.getWidth(), h = t.getHeight();
        int[] rgb = new int[w * h];
        t.getRGB(0, 0, w, h, rgb, 0, w);

        byte[] cls = new byte[w * h];
        int minx = w, miny = h, maxx = -1, maxy = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                byte c = classify(rgb[y * w + x]);
                cls[y * w + x] = c;
                if (c != 0) {
                    if (x < minx) minx = x;
                    if (x > maxx) maxx = x;
                    if (y < miny) miny = y;
                    if (y > maxy) maxy = y;
                }
            }
        }
        if (maxx < 0) {
            return false;
        }
        tplW = maxx - minx + 1;
        tplH = maxy - miny + 1;
        tplCls = new byte[tplW * tplH];
        int n = 0;
        for (int y = 0; y < tplH; y++) {
            for (int x = 0; x < tplW; x++) {
                byte c = cls[(miny + y) * w + (minx + x)];
                tplCls[y * tplW + x] = c;
                if (c != 0) {
                    n++;
                }
            }
        }
        tplMaskN = n;
        buildAnchors();
        return tplCls != null && tplMaskN > 50;
    }

    private BufferedImage loadTemplateImage() {
        try {
            InputStream in = TeamTask.class.getResourceAsStream(kind.res);
            if (in != null) {
                try {
                    return ImageIO.read(in);
                } finally {
                    in.close();
                }
            }
        } catch (Throwable ignore) {
            // 落到文件回退
        }
        for (String p : kind.fallback) {
            try {
                File f = new File(p);
                if (f.exists()) {
                    BufferedImage img = ImageIO.read(f);
                    if (img != null) {
                        return img;
                    }
                }
            } catch (Throwable ignore) {
                // 继续尝试下一个
            }
        }
        return null;
    }

    /** 挑几个分散的锚点像素，先比它们，快速排除绝大多数位置。 */
    private void buildAnchors() {
        int[] tmp = new int[Math.min(ANCHOR_N, tplMaskN)];
        int cnt = 0;
        int step = Math.max(1, tplMaskN / ANCHOR_N);
        int seen = 0;
        for (int i = 0; i < tplCls.length && cnt < tmp.length; i++) {
            if (tplCls[i] == 0) {
                continue;
            }
            if (seen % step == 0) {
                tmp[cnt++] = i;
            }
            seen++;
        }
        anchorIdx = new int[cnt];
        System.arraycopy(tmp, 0, anchorIdx, 0, cnt);
    }

    /** 最近一次扫描的最高命中率（×1000），用于日志。 */
    private volatile int lastBest = 0;

    /** 最近一次扫描的最高命中率（0~1000），供调试用。 */
    public int lastBestScore() {
        return lastBest;
    }

    /** 调试：不管阈值，返回最低分位置 {x, y, score*1000}。找不到返回 null。 */
    public int[] scanBest(BufferedImage img) {
        if (!ensureTemplate() || img == null) {
            return null;
        }
        int W = img.getWidth(), H = img.getHeight();
        double kx = W / (double) BASE_W, ky = H / (double) BASE_H;
        int x0 = clamp((int) Math.round(ICON_BOX_X0 * kx), 0, Math.max(0, W - tplW));
        int y0 = clamp((int) Math.round(ICON_BOX_Y0 * ky), 0, Math.max(0, H - tplH));
        int x1 = clamp((int) Math.round(ICON_BOX_X1 * kx), 0, W - tplW);
        int y1 = clamp((int) Math.round(ICON_BOX_Y1 * ky), 0, H - tplH);
        byte[] cls = classMap(img);
        double best = 0;
        int bx = x0, by = y0;
        for (int oy = y0; oy <= y1; oy++) {
            for (int ox = x0; ox <= x1; ox++) {
                int ok = 0;
                for (int ty = 0; ty < tplH; ty++) {
                    int rowBase = (oy + ty) * W + ox;
                    int tBase = ty * tplW;
                    for (int tx = 0; tx < tplW; tx++) {
                        int ti = tBase + tx;
                        byte tc = tplCls[ti];
                        if (tc != 0 && cls[rowBase + tx] == tc) {
                            ok++;
                        }
                    }
                }
                double sc = ok / (double) tplMaskN;
                if (sc > best) {
                    best = sc;
                    bx = ox;
                    by = oy;
                }
            }
        }
        return new int[]{bx + tplW / 2, by + tplH / 2, (int) Math.round(best * 1000)};
    }

    /** 模板加载状态，供调试用（含任务种类与模板来源）。 */
    public String templateInfo() {
        boolean ok = ensureTemplate();
        return "kind=" + kind.name()
                + " loaded=" + ok + " size=" + tplW + "x" + tplH + " maskPx=" + tplMaskN
                + " res=" + kind.res + " clsRes=" + (TeamTask.class.getResource(kind.res) != null);
    }

    /**
     * 在<b>用户红框 {@link #ICON_BOX_X0}..{@link #ICON_BOX_Y1}</b> 内做模板匹配，
     * 返回 {窗口x, 窗口y, 命中率*1000}；没到阈值返回 null。
     *
     * <p>2026-09-28 起框＝用户现场红框（原来那套「全区域 300..740 / 590..715」已废弃）。
     */
    int[] findIcon(BufferedImage img) {
        if (!ensureTemplate() || img == null) {
            return null;
        }
        int W = img.getWidth(), H = img.getHeight();
        double kx = W / (double) BASE_W, ky = H / (double) BASE_H;

        int x0 = clamp((int) Math.round(ICON_BOX_X0 * kx), 0, Math.max(0, W - tplW));
        int y0 = clamp((int) Math.round(ICON_BOX_Y0 * ky), 0, Math.max(0, H - tplH));
        int x1 = clamp((int) Math.round(ICON_BOX_X1 * kx), 0, W - tplW);
        int y1 = clamp((int) Math.round(ICON_BOX_Y1 * ky), 0, H - tplH);
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }

        byte[] cls = classMap(img);

        double best = 0;
        int bx = -1, by = -1;
        for (int oy = y0; oy <= y1; oy++) {
            for (int ox = x0; ox <= x1; ox++) {
                double sc = matchScoreAt(cls, W, ox, oy);
                if (sc > best) {
                    best = sc;
                    bx = ox;
                    by = oy;
                }
            }
        }
        lastBest = (int) Math.round(best * 1000);
        if (best >= MATCH_TH && bx >= 0) {
            int cx = (int) Math.round((bx + tplW / 2.0) / kx);
            int cy = (int) Math.round((by + tplH / 2.0) / ky);
            if (!acceptHit(cx, cy, best)) {
                return null;
            }
            return new int[]{cx, cy, (int) Math.round(best * 1000)};
        }
        return null;
    }

    /** 把整幅图转成「颜色类别」图。 */
    private static byte[] classMap(BufferedImage img) {
        int W = img.getWidth(), H = img.getHeight();
        int[] px = new int[W * H];
        img.getRGB(0, 0, W, H, px, 0, W);
        byte[] c = new byte[W * H];
        for (int i = 0; i < px.length; i++) {
            c[i] = classify(px[i]);
        }
        return c;
    }

    /**
     * 在<b>用户红框 {@link #DIALOG_BOX_X0}..{@link #DIALOG_BOX_Y1}</b> 里找橙色按钮簇，
     * 返回<b>最左边</b>那个（即「确定」）的中心：{窗口x, 窗口y, 像素数}。找不到返回 null。
     *
     * <p>做法：在框内挑出「橙红」像素，按<b>列</b>聚簇（列间隙 ≤ {@link #BTN_COL_GAP}），
     * 再按尺寸（宽 {@link #BTN_W_MIN}~{@link #BTN_W_MAX}、高 {@link #BTN_H_MIN}~{@link #BTN_H_MAX}）
     * 与像素量（≥ {@link #BTN_MIN_PX}）过滤掉细边框和噪点，最后取中心 x 最小的那一簇。
     *
     * <p>2026-09-28 起框＝用户现场红框（原来那套 BTN 380..665 / 418..555 已废弃）。
     */
    int[] findConfirmButton(BufferedImage img) {
        if (img == null) {
            return null;
        }
        int W = img.getWidth(), H = img.getHeight();
        double kx = W / (double) BASE_W, ky = H / (double) BASE_H;
        int x0 = clamp((int) Math.round(DIALOG_BOX_X0 * kx), 0, W);
        int x1 = clamp((int) Math.round(DIALOG_BOX_X1 * kx), 0, W);
        int y0 = clamp((int) Math.round(DIALOG_BOX_Y0 * ky), 0, H);
        int y1 = clamp((int) Math.round(DIALOG_BOX_Y1 * ky), 0, H);
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        int rw = x1 - x0, rh = y1 - y0;

        // ---------- 第一步：找「按钮行带」 ----------
        int[] rowCnt = new int[rh];
        for (int y = 0; y < rh; y++) {
            int c = 0;
            for (int x = 0; x < rw; x++) {
                if (isBtnColor(img.getRGB(x0 + x, y0 + y))) {
                    c++;
                }
            }
            rowCnt[y] = c;
        }
        int bi = -1, bj = -1, bestTotal = -1;
        int i = 0;
        while (i < rh) {
            if (rowCnt[i] < BTN_ROW_MIN) {
                i++;
                continue;
            }
            int j = i, last = i, total = 0;
            while (j < rh && j - last <= BTN_ROW_GAP) {
                if (rowCnt[j] >= BTN_ROW_MIN) {
                    last = j;
                    total += rowCnt[j];
                }
                j++;
            }
            if (total > bestTotal) {
                bestTotal = total;
                bi = i;
                bj = last;
            }
            i = last + 1;
        }
        if (bi < 0) {
            return null;
        }
        int bandH = bj - bi + 1;
        if (bandH < BTN_H_MIN || bandH > BTN_H_MAX) {
            return null;
        }

        // ---------- 第二步：行带内找「按钮列簇」，取最左边那个（＝确定） ----------
        int[] colCnt = new int[rw];
        for (int x = 0; x < rw; x++) {
            int c = 0;
            for (int y = bi; y <= bj; y++) {
                if (isBtnColor(img.getRGB(x0 + x, y0 + y))) {
                    c++;
                }
            }
            colCnt[x] = c;
        }
        int ci = 0;
        while (ci < rw) {
            if (colCnt[ci] < BTN_COL_MIN) {
                ci++;
                continue;
            }
            int cj = ci, last = ci, sum = 0;
            while (cj < rw && cj - last <= BTN_COL_GAP) {
                if (colCnt[cj] >= BTN_COL_MIN) {
                    last = cj;
                    sum += colCnt[cj];
                }
                cj++;
            }
            int wBtn = last - ci + 1;
            if (sum >= BTN_MIN_PX && wBtn >= BTN_W_MIN && wBtn <= BTN_W_MAX) {
                int cxImg = x0 + (ci + last) / 2;
                int cyImg = y0 + (bi + bj) / 2;
                lastBtnInfo = "x" + (x0 + ci) + "-" + (x0 + last) + " y" + (y0 + bi) + "-" + (y0 + bj);
                return new int[]{(int) Math.round(cxImg / kx), (int) Math.round(cyImg / ky), sum};
            }
            ci = last + 1;
        }
        return null;
    }

    /** 最近一次 {@link #findConfirmButton} 命中的按钮边界，供调试用。 */
    private String lastBtnInfo = "";

    /** 按钮边界信息（调试）。 */
    public String lastButtonInfo() {
        return lastBtnInfo;
    }

    /** 这个像素是不是「按钮的橙红」。 */
    private static boolean isBtnColor(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        return r > BTN_R_MIN && g > BTN_G_MIN && g < BTN_G_MAX
                && b < BTN_B_MAX && (r - b) > BTN_RB_MIN;
    }

    /**
     * 「按钮芯色」判定 —— 比 {@link #isBtnColor} 严格，只认按钮本体那种鲜橙，
     * 专供<b>固定位置校验</b>使用。判据来源见 {@link #CORE_R_MIN} 的注释。
     */
    private static boolean isBtnCoreColor(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        return r >= CORE_R_MIN && g >= CORE_G_MIN && g <= CORE_G_MAX
                && b <= CORE_B_MAX && (r - g) >= CORE_RG_MIN;
    }

    private static int rgbDist(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ==================== 异常与收尾 ====================

    private void abort(String reason, String humanMessage) {
        listener.log("✘ 异常中止：" + reason);
        listener.alert(kind.taskName + "任务 · 需要人工处理", humanMessage);
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
