package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.imageio.ImageIO;

/**
 * 「打老鼠 · 褐仓鼠横行的粮仓」一键流程执行器。
 *
 * <h3>完整流程（用户 2026-09-21 口述 + 4 张实测截图）</h3>
 * <pre>
 *  1. O → 点「回到军团」→ T 回城          确保角色在 成都·子城
 *  2. 右上角「寻路」→ 坐标填 29 / 16 → 点「移动」
 *     （太仓尉就在子城 (29,16)，复用 XiaolianTask.navTo：实测坐标框 + 到达校验）
 *  3. <b>点击 NPC「太仓尉」激活对话</b>（OCR 找名字点它；按 G 不会对话，实车教训）
 *     → OCR 找「进入褐仓鼠横行的粮仓」点击（写死 (523,449) 会因对话框漂移点偏，
 *       抓鬼实车教训，OCR 优先、固定坐标兜底）
 *  4. 弹出「35800五铢、508活力」正文框     焦点已在对话框上，点一下正文再按 Enter
 *  5. 弹出「确定 / 取消」框                点「确定」(523,291)
 *  6. 进入粮仓 —— 任务完成
 * </pre>
 *
 * <h3>关键标定结论（4 张截图实测，1026x795 与基准 1030x797 差 &lt; 0.5%，直接用）</h3>
 * <ul>
 *   <li><b>太仓尉对话框选项行</b>（图2）：对话/任务 y≈395、活动介绍 y≈420、
 *       <b>进入褐仓鼠横行的粮仓 y≈448</b>、取消 y≈477，行中心 x≈523。</li>
 *   <li><b>正文框先点再回车</b>（图3）：消耗说明框无按钮、无高亮条 —— 与运送物资的
 *       「NPC 长正文」同族，<b>只按回车可能过不去</b>，所以先点一下正文 (520,300) 再回车。
 *       用户实测回车能过，点正文是无害冗余（两条路都通）。</li>
 *   <li><b>确定/取消框</b>（图4）：确定 y≈290（默认蓝条高亮）、取消 y≈317。</li>
 *   <li><b>对话是否打开</b>：复用 SalaryTask.scanImage 的「首项高亮蓝条」检测 ——
 *       图2 的目标行、图4 的确定行都有蓝条；图3 正文框没有蓝条（正好用来区分
 *       「消耗说明还挂着」和「确定框已弹出」）。</li>
 *   <li><b>到达后对话不会自己弹</b>：运送物资同款 NPC 的经验是<b>按 G 唤起对话</b>，
 *       所以到达后先扫蓝条，没有就按 G，最多 4 次。</li>
 *   <li><b>寻路直接复用 XiaolianTask.navTo</b>（包级方法，同包可见）：孝廉/运送与打老鼠
 *       是同一个 NPC 太仓尉，navTo 里那套「实测坐标框 + WM_CHAR 填数 + 移动自愈 +
 *       右上角坐标条到达校验」全部适用，不复制代码。</li>
 * </ul>
 *
 * <h3>坐标说明</h3>
 * 全部是「窗口内坐标」，基准分辨率 1030x797（QQ三国 默认窗口尺寸），
 * 运行时按游戏窗口真实尺寸等比换算。
 */
public class RatTask {

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

    /** 军团界面「回到军团」按钮（动态识别失败时的保底） */
    private static final int[] PT_BACK_TO_LEGION = {830, 635};

    /** 寻路目标：太仓尉（成都·子城） */
    private static final String TARGET_X = "29";
    private static final String TARGET_Y = "16";

    /**
     * 太仓尉对话框第 3 行「进入褐仓鼠横行的粮仓」兜底坐标。
     * <b>实车教训：对话框会漂移，OCR 找到选项文字优先，这里只是最后手段</b>。
     */
    private static final int[] PT_RAT_OPTION = {523, 449};
    /** 对话框正文区（按回车前先点一下，运送物资同款保险动作） */
    private static final int[] PT_DIALOG_BODY = {520, 300};
    /** 确定/取消框的「确定」 */
    private static final int[] PT_CONFIRM_OK = {523, 291};

    /** NPC 名字标签关键词（先长后短；「太仓尉」被 OCR 认错一半时试「仓尉」）。 */
    private static final String[] NPC_NAME_KEYWORDS = {"太仓尉", "仓尉"};

    // ==================== 节奏 ====================

    private static final int STEP_MS = 1800;
    private static final int CLICK_MS = 1500;
    private static final int SHORT_MS = 800;
    private static final int TELEPORT_MS = 3500;
    private static final int DIALOG_WAIT_MS = 1700;
    /** 按 G 唤起对话的最多尝试次数 */
    private static final int G_MAX_TRIES = 4;
    /** 「消耗说明 → 确定框」的确认重试轮数 */
    private static final int CONFIRM_MAX_ROUNDS = 2;

    // ==================== 虚拟键码 ====================

    private static final int VK_T = WindowUtils.VK_T;
    private static final int VK_O = WindowUtils.VK_O;
    private static final int VK_G = WindowUtils.VK_G;
    private static final int VK_ENTER = WindowUtils.VK_RETURN;

    // ==================== 运行状态 ====================

    private final GameWindowController controller;
    private final Listener listener;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;

    /** 寻路助手（复用 XiaolianTask 的 navTo 全套机制），runOnce 里创建。 */
    private XiaolianTask navHelper;

    private final File shotDir = new File("rat_shots");

    public RatTask(GameWindowController controller, Listener listener) {
        this.controller = controller;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void requestStop() {
        stopRequested = true;
        if (navHelper != null) {
            navHelper.requestStop();
        }
    }

    public void start() {
        if (running) {
            listener.log("打老鼠任务已在运行中");
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
        }, "RatTask");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步执行一次完整任务（供 {@link #start()} 与「一键日常」序列共用）。
     *
     * <p><b>后台模式作用域也在这里</b>：进入时记下用户原来的后台开关并强制开启，
     * 退出时原样还原 —— 工资/组队任务同款做法。
     */
    public TaskOutcome runOnce() {
        if (running) {
            return new TaskOutcome(false, "打老鼠任务已在运行中");
        }
        running = true;
        stopRequested = false;
        boolean savedBg = controller.isRunInBackground();
        try {
            controller.setRunInBackground(true);
            listener.log("打老鼠任务以「后台模式」运行（不抢焦点、不移动鼠标）");
            execute();
            return new TaskOutcome(true, "已进入褐仓鼠横行的粮仓");
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
        listener.log("================ 打老鼠 · 褐仓鼠粮仓 开始 ================");

        Rectangle r = controller.getWindowRect();
        if (r == null) {
            throw new Abort("找不到游戏窗口，请先启动 QQ三国 并确认窗口未关闭");
        }
        listener.log("游戏窗口：" + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");
        controller.ensureWindowVisible();

        if (!OcrLite.available()) {
            throw new Abort("OCR 不可用：" + OcrLite.lastError() + "（寻路到达校验需要读右上角坐标条）");
        }

        // 后台模式不抢焦点
        if (controller.isRunInBackground()) {
            listener.log("后台模式：不抢焦点，直接给游戏窗口发消息");
        } else {
            controller.focusWindow();
            sleep(SHORT_MS);
            listener.log("已把游戏窗口切到前台");
        }

        // ---------- 1. O → 回到军团 → T 回城（确保在子城） ----------
        goBackToLegion();
        backToTown();

        // ---------- 2. 寻路到 (29,16) 太仓尉（复用 XiaolianTask.navTo） ----------
        listener.log("—— 步骤 3：寻路到 (" + TARGET_X + "," + TARGET_Y + ") 太仓尉 ——");
        navHelper = new XiaolianTask(controller, navListener(), false, false,
                XiaolianTask.resolveApiKey(), XiaolianTask.Mode.XIAOLIAN);
        try {
            navHelper.navTo(TARGET_X, TARGET_Y);
        } catch (Throwable t) {
            // navTo 失败会抛 XiaolianTask.Abort（消息里写的是「孝廉」，这里换个说法）
            String msg = t.getMessage() == null ? t.toString() : t.getMessage();
            throw new Abort("寻路到 (" + TARGET_X + "," + TARGET_Y + ") 失败：" + msg);
        }
        sleep(SHORT_MS);

        // ---------- 3. 激活太仓尉对话（点 NPC，G 兜底） ----------
        activateNpcDialog();

        // ---------- 4. 点「进入褐仓鼠横行的粮仓」 ----------
        listener.log("—— 步骤 4：OCR 找「进入褐仓鼠横行的粮仓」并点击 ——");
        clickOptionByText("进入褐仓鼠横行的粮仓",
                new String[]{"进入褐仓鼠横行的粮仓", "褐仓鼠横行的粮仓", "褐仓鼠"},
                PT_RAT_OPTION);

        // ---------- 5. 消耗说明正文框：点一下正文 → 回车 ----------
        listener.log("—— 步骤 5：消耗说明框 —— 点正文 → 按 Enter ——");
        controller.clickWindowPoint(PT_DIALOG_BODY[0], PT_DIALOG_BODY[1]);
        sleep(500);
        controller.sendKey(VK_ENTER);
        sleep(CLICK_MS);

        // ---------- 6. 确定/取消框：等「确定」蓝条出现 → 点确定 ----------
        confirmEnter();

        String p = snapshot("99_rat_done");
        listener.log("✔ 已进入褐仓鼠横行的粮仓，打老鼠任务完成"
                + (p != null ? "（截图：" + p + "）" : ""));
    }

    /**
     * 点「进入褐仓鼠横行的粮仓」并确认选项菜单真的关了 —— <b>OCR 找到选项文字再点</b>。
     *
     * <p>抓鬼实车教训（同一天）：对话框整体位置和标定截图有偏差，写死行坐标会点到
     * 上一行。每轮先 {@link ScreenText#find} 找选项文字（找到就点文字本身），
     * OCR 没找到才退固定坐标 + 小偏移。
     *
     * <p>「蓝条消失」守卫不变：消耗说明框<b>没有</b>蓝条，菜单还挂着说明点偏了 ——
     * 重试（每轮重新 OCR），3 次还关不掉就 abort，绝不带着挂着的菜单往下走
     * （那样后面会一路点空、假成功）。
     */
    private void clickOptionByText(String what, String[] keywords, int[] fallbackPt) {
        int[] jx = {0, -4, 5};
        int[] jy = {0, 3, -3};
        for (int i = 0; i < 3; i++) {
            checkStop();
            int[] p = ScreenText.find(controller.captureWindow(), keywords,
                    ScreenText.ZONE_CENTRAL, 0);
            if (p != null) {
                listener.log("  OCR 找到「" + what + "」@ (" + p[0] + "," + p[1] + ")，点击");
                controller.clickWindowPoint(p[0], p[1]);
            } else {
                listener.log("  OCR 没找到「" + what + "」，退固定坐标 ("
                        + fallbackPt[0] + "," + fallbackPt[1] + ")");
                controller.clickWindowPoint(fallbackPt[0] + jx[i], fallbackPt[1] + jy[i]);
            }
            sleep(CLICK_MS);
            if (!SalaryTask.scanImage(controller.captureWindow()).dialogOpen) {
                listener.log("  ✔ 选项菜单已关闭（消耗说明框弹出）");
                return;
            }
            listener.log("  ⚠ 蓝条还在，选项菜单没关掉（第 " + (i + 1) + " 次点击）");
        }
        String shot = snapshot("anomaly_rat_option_no_effect");
        throw new Abort("点了 3 次「进入褐仓鼠横行的粮仓」，选项菜单都没关掉。\n\n"
                + "可能对话框布局和标定时不一致（比如活动没开放，菜单里没有这一项）。\n\n"
                + (shot != null ? "现场截图：" + shot : ""));
    }

    // ==================== 步骤实现（其余） ====================

    /** O → 点「回到军团」，把角色定锚到军团地图（复用 SalaryTask 的动态橙按钮识别）。 */
    private void goBackToLegion() {
        listener.log("—— 步骤 1：按 O 打开军团界面 → 点「回到军团」——");
        int[] btn = SalaryTask.findBackToLegionButton(controller.captureWindow());
        if (btn == null) {
            controller.sendKey(VK_O);
            sleep(STEP_MS);
            btn = SalaryTask.findBackToLegionButton(controller.captureWindow());
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

    /** T 回城（成都·子城）—— 寻路坐标只在这张图里成立。 */
    private void backToTown() {
        listener.log("—— 步骤 2：按 T 回城（成都·子城）——");
        controller.sendKey(VK_T);
        sleep(TELEPORT_MS);
        listener.log("  ✔ 已回到子城");
    }

    /**
     * 激活太仓尉对话：到达后<b>必须点击 NPC 才会弹出对话</b>（2026-09-21 实车教训：
     * 只按 G 不会对话，流程白白卡死重来）。每轮顺序：扫蓝条 → OCR 找「太仓尉」名字
     * → 点名字下方的 NPC 身体 → 再扫蓝条；点不到名字或没弹出才按 G 兜底
     * （孝廉/运送同款激活键），最多 {@value #G_MAX_TRIES} 轮。
     */
    private void activateNpcDialog() {
        listener.log("—— 步骤 3a：激活太仓尉对话（点 NPC，G 兜底）——");
        for (int i = 1; i <= G_MAX_TRIES; i++) {
            checkStop();
            if (SalaryTask.scanImage(controller.captureWindow()).dialogOpen) {
                listener.log("  ✔ 已检测到太仓尉对话框（第 " + i + " 轮）");
                return;
            }
            int[] npc = findNpc();
            if (npc != null) {
                listener.log("  OCR 找到「太仓尉」@ (" + npc[0] + "," + npc[1] + ")，点击激活对话");
                controller.clickWindowPoint(npc[0], npc[1]);
                sleep(CLICK_MS);
                if (SalaryTask.scanImage(controller.captureWindow()).dialogOpen) {
                    listener.log("  ✔ 点 NPC 后对话框已弹出");
                    return;
                }
                listener.log("  点了 NPC 对话框还没弹（第 " + i + "/" + G_MAX_TRIES + " 轮）");
            } else {
                listener.log("  画面上没 OCR 到「太仓尉」名字（第 " + i + "/" + G_MAX_TRIES + " 轮）");
            }
            listener.log("  按 G 兜底尝试唤起对话");
            controller.sendKey(VK_G);
            sleep(DIALOG_WAIT_MS);
        }
        if (SalaryTask.scanImage(controller.captureWindow()).dialogOpen) {
            listener.log("  ✔ 已检测到太仓尉对话框");
            return;
        }
        String shot = snapshot("anomaly_rat_no_dialog");
        throw new Abort("太仓尉对话框一直没出现（点 NPC + 按 G 各试了 " + G_MAX_TRIES + " 轮）。\n\n"
                + "可能角色没站到 NPC 旁边，或今天已进入过粮仓（每天一次）。\n\n"
                + (shot != null ? "现场截图：" + shot : ""));
    }

    /**
     * 全窗 OCR 找 NPC 名字标签（{@link #NPC_NAME_KEYWORDS} 依序尝试），返回要点击的
     * 窗口坐标（名字中心下方 18px ≈ NPC 身体）。找不到 / OCR 异常返回 null。
     *
     * <p>委托 {@link ScreenText#find} 统一实现（搜索区、×2 放大坐标换算、
     * 「包含或相似度≥0.72」匹配、最短行优先）。
     */
    private int[] findNpc() {
        return ScreenText.find(controller.captureWindow(), NPC_NAME_KEYWORDS,
                ScreenText.ZONE_CENTRAL, 18);
    }

    /**
     * 确定/取消框：等「确定」行的蓝条出现再点它。
     *
     * <p>上一手回车可能没被消耗说明框吃进去（画面还停在正文框，正文框<b>没有</b>蓝条），
     * 所以蓝条没出现就补「点正文 + 回车」一轮，最多 {@value #CONFIRM_MAX_ROUNDS} 轮。
     */
    private void confirmEnter() {
        listener.log("—— 步骤 6：等「确定」框出现 → 点确定 ——");
        for (int round = 1; round <= CONFIRM_MAX_ROUNDS; round++) {
            checkStop();
            if (SalaryTask.scanImage(controller.captureWindow()).dialogOpen) {
                listener.log("  ✔ 「确定」框已弹出（第 " + round + " 轮检测），点「确定」("
                        + PT_CONFIRM_OK[0] + "," + PT_CONFIRM_OK[1] + ")");
                controller.clickWindowPoint(PT_CONFIRM_OK[0], PT_CONFIRM_OK[1]);
                sleep(2000);
                return;
            }
            listener.log("  第 " + round + "/" + CONFIRM_MAX_ROUNDS
                    + " 轮没等到「确定」框，补一次 点正文 → 回车");
            controller.clickWindowPoint(PT_DIALOG_BODY[0], PT_DIALOG_BODY[1]);
            sleep(500);
            controller.sendKey(VK_ENTER);
            sleep(CLICK_MS);
        }
        // 最后一轮之后再扫一次，还没有就交还人工
        if (SalaryTask.scanImage(controller.captureWindow()).dialogOpen) {
            listener.log("  ✔ 「确定」框已弹出（补按后），点「确定」");
            controller.clickWindowPoint(PT_CONFIRM_OK[0], PT_CONFIRM_OK[1]);
            sleep(2000);
            return;
        }
        String shot = snapshot("anomaly_rat_no_confirm");
        throw new Abort("「确定/取消」框一直没出现，可能五铢或活力不足。\n\n"
                + "进入需要 35800 五铢、508 活力，且每天只能进入一次。\n\n"
                + (shot != null ? "现场截图：" + shot : ""));
    }

    // ==================== 工具 ====================

    /**
     * 把本任务的回调适配成 XiaolianTask.Listener（两个接口方法同形但互不相干），
     * 寻路日志直接进同一份日志；finished/alert 走对应转发。
     */
    private XiaolianTask.Listener navListener() {
        return new XiaolianTask.Listener() {
            @Override
            public void log(String message) {
                listener.log(message);
            }

            @Override
            public void alert(String title, String message) {
                listener.alert(title, message);
            }

            @Override
            public void finished(boolean success, String summary) {
                // navTo 只是中途借用，不产生独立完成事件，记一条日志即可
                listener.log("（寻路助手）" + (success ? "✔ " : "⛔ ") + summary);
            }
        };
    }

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

    /** 存一张现场截图到 rat_shots/，返回文件名（失败返回 null，不影响主流程）。 */
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
