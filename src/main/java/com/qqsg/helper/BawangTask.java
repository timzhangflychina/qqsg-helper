package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 「霸王城」一键流程执行器。
 *
 * <h3>完整流程</h3>
 * <pre>
 *  1.  关闭游戏广告弹窗（ESC×3 关「游戏活动展示」+ 点 X 关「热点活动」）
 *  2.  O → 点「回到军团」(830,635)     先回军团地图
 *  3.  T                              再回城（成都）
 *  4.  点右上角「寻路」(827,189)       打开「自动寻路」面板
 *  5.  坐标框1 填 10、坐标框2 填 16
 *  6.  点「移动」(648,463)            角色自动走过去
 *  7.  关掉「自动寻路」面板 (690,200)
 *  8.  G                              大司马对话 → 选「垓下学艺」(第 2 项)
 *  9.                                  → 选「送我到霸王城内」(第 4 项)
 * 10.                                  → 点「出发」(第 1 项)  进入霸王城
 * 11. G                               高阶学艺导师对话 → 选「我要开始静修」(第 1 项)
 * 12.                                  → 选「延长学艺10分钟」(第 1 项)
 * 13. G                                → 选「离开霸王城」(第 5 项)
 * </pre>
 *
 * <h3>为什么要先「关广告 → 回军团 → 回主城」</h3>
 * 广告弹窗会挡住右上角「寻路」按钮与坐标输入框，导致点击落到弹窗上；
 * 而「自动寻路」的坐标 (10,16) 只在<b>成都·子城</b>这一张图里才有意义 ——
 * 若角色当时在军团大厅 / 霸王城 / 其它地图，点「移动」会走到别处甚至原地不动，
 * 后面按 G 就会点空。所以起手先把界面清干净、把角色一路带回主城，再开始寻路，
 * 把「角色当前在哪」这个不确定因素彻底消掉。
 *
 * <h3>坐标说明</h3>
 * 全部是「窗口内坐标」，基准分辨率 1030x797（QQ三国 默认窗口尺寸）。
 * 运行时按游戏窗口真实尺寸等比换算，换分辨率/换位置都不影响。
 *
 * <h3>选项定位（本任务的关键）</h3>
 * <b>一律 OCR 认字 → 点文字所在坐标 → 点完复检</b>，认不出才退「实测首行 y + (k-1)×27.7」
 * 兜底。早期版本靠「亮蓝色高亮条」定位，实测在真实画面上两个方向都会错：
 * <ul>
 *   <li><b>假阳性</b>：霸王城/成都的普通场景被判成「对话开着」（151 张真实截图里 35 帧
 *       与另一种判据结论相反），于是 <b>一次 G 都没按</b> 就「检测到对话」，接着一路盲点、
 *       却报成功 —— 这就是「明明没有成功却一直返回结果已经成功」的根因；</li>
 *   <li><b>高亮条不是首行</b>：061701_99_done 那帧真菜单首项在 y=396，蓝带判到了最后一行
 *       y=531（「取消」），于是「点第 1 项 我要开始静修」实际点的是「取消」，把对话框关掉了。</li>
 * </ul>
 * OCR 实测能稳定读出的（窗口内坐标，1030x797）：
 * <pre>
 *   大司马对话     标题「大司马」@(382,248)、正文「你来找我有什么事吗」@(430,276)
 *   高阶学艺导师   标题「高阶学艺导师」@(392,253)，选项 6 行 @(527, 396/423/450/477/504/531)
 *                  → 我要开始静修 / 我要去切磋 / 领取今日所需静修凭证 / 进入切磋观看模式
 *                    / 离开霸王城（读作「嵩开霸王」）/ 取消
 *   地图名条       霸王城读作「霸王高阶」@(907,165)；成都读作「成都子」@(901,165)
 * </pre>
 *
 * <h3>回车只当最后一级兜底（2026-09-26）</h3>
 * 回车在 QQ三国 里过不了窗口消息时，会顺手打开屏幕底部的聊天输入框，之后按 G 被当成打字
 * （军团任务实测聊天区残留 <code>ksdafqawert</code>），对话再也弹不出来。因此推进选项的
 * 手段按优先级：<b>① 识图</b>（OCR 认字后点文字）→ <b>② 定点辅助</b>（实测行距推算坐标）
 * → <b>③ Enter 兜底</b>（仅当前两级都没拿到画面证据时按一次当「确定」，且没推进就再按
 * 一次把聊天框关掉，见 {@link #enterFallback}）。正常情况下仍然一次回车都不按。
 *
 * <h3>每一步都要「被证实」</h3>
 * 点完选项后必须拿到「下一步提示词出现」或「本步对话关闭」；进图后还要 OCR 地图名确认。
 * 任何一步没被证实就记入 <code>failedSteps</code>，收尾时统一报错并附截图 —— 宁可报失败，
 * 也绝不报假成功。
 */
public class BawangTask {

    /** 进度 / 结果回调。回调在后台线程触发，界面层需自行切回 EDT。 */
    public interface Listener {
        /** 普通日志 */
        void log(String message);

        /** 需要人工处理的弹窗提醒 */
        void alert(String title, String message);

        /** 任务结束（success=false 表示异常中止） */
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

    /** 军团界面「回到军团」按钮（坐标与 LegionTask 保持一致） */
    private static final int[] PT_BACK_TO_LEGION = {830, 635};
    /** 「热点活动」弹窗右上角 X */
    private static final int[] PT_AD_HOTSPOT_X = {778, 322};
    /** 广告检测采样区（热点活动 X 附近，弹窗在时是橙色） */
    private static final int[] PATCH_AD_HOTSPOT = {753, 297, 50, 50};
    /** 广告判定阈值：R-G 大于该值说明橙色弹窗还在
     * （成都·子城闹市/红色装饰多的位置实测最高 +38，所以取 50 避免误判） */
    private static final double TH_AD_RG = 50.0;

    /** 「热点活动 / 游戏活动展示」弹窗标题的 OCR 搜索区与关键词（055152 孝廉误报同款修复）。
     *  色块 (753,297) 在秋天树冠/灯笼等红色景物下也会 R-G&gt;50 —— 200056（工资）和
     *  055152（孝廉）两次「广告未关」现场截图里画面上根本没有弹窗。弹窗在时标题栏必有
     *  这些字，OCR 认得到才算真的在。搜索区避开 x≥830 的右上角按钮列（那里有「热点」
     *  按钮，会把按钮文字误当弹窗标题）。 */
    private static final int[] ZONE_AD_TITLE = {250, 810, 180, 400};
    private static final String[] KW_AD_TITLE = {
        "热点活动", "热点活", "点活动", "热点", "活动展示", "游戏活动", "动展示"};

    /** 右上角「寻路」按钮 */
    private static final int[] PT_XUNLU_BTN = {827, 189};
    /** 「自动寻路」面板：第 1 个坐标输入框 */
    private static final int[] PT_NAV_BOX1  = {505, 465};
    /** 「自动寻路」面板：第 2 个坐标输入框 */
    private static final int[] PT_NAV_BOX2  = {533, 465};
    /** 「自动寻路」面板：绿色「移动」按钮 */
    private static final int[] PT_NAV_MOVE  = {648, 463};
    /** 「自动寻路」面板右上角关闭 X */
    private static final int[] PT_NAV_CLOSE = {690, 200};

    /** 目标坐标（成都·子城） */
    private static final String TARGET_X = "10";
    private static final String TARGET_Y = "16";

    // ==================== 对话选项检测参数 ====================

    /** 高亮条扫描范围（窗口内坐标） */
    private static final int DLG_X0 = 505, DLG_X1 = 645;
    private static final int DLG_Y0 = 330, DLG_Y1 = 600;
    /** 判定「这行属于高亮条」的阈值：B - R 大于它 */
    private static final double TH_HILITE = 70.0;
    /** 高亮带高度上限；超过说明是「自动寻路」面板的大蓝底，不是对话 */
    private static final int MAX_DLG_SPAN = 45;
    /** 选项行距（实测 27~28px） */
    private static final double ROW_PITCH = 27.7;
    /** 点击选项时的横向落点，取在高亮条内部（条子横跨 505~645） */
    private static final int OPTION_CLICK_X = 570;

    // ==================== 对话识别：OCR 认字（2026-09-25 重构） ====================
    //
    // 为什么要从「像素猜高亮条」改成「OCR 认字」：
    //   实测 151 张真实截图，BawangTask 的蓝色带判据与 SalaryTask 的判据有 35 帧结论相反——
    //   ① 假阳性：霸王城/成都的普通场景被判成「对话开着」（例如 063246_10_entered_bawang：
    //      人物站在霸王城，画面里根本没有对话框，却报 dialogOpen=true、首项 y=386）。
    //      后果：pressGUntilDialog 一次 G 都不按就「检测到对话」，clickOption 盲点 (570,38x)，
    //      全程什么都没做，却在 06:35:12 报「霸王城流程全部执行完毕」——这就是用户说的
    //      「明明没有成功却一直返回结果已经成功」。
    //   ② 假阴性：真对话（大司马 / 军团掌簿）反而被判成「没开」→ 白等 10 轮后中止。
    //   ③ 高亮条本身也不可信：061701_99_done 那帧真菜单首项在 y=396，而蓝色带判到了最后一行
    //      y=531（=「取消」），于是「点第 1 项 我要开始静修」实际点的是「取消」，把对话框关掉了。
    // 所以：对话判定与选项定位一律以 OCR 认字为准，认不出来就按各步实测的首行 y 兜底，
    // 并且点完必须复检，绝不「点了就算成功」。

    /**
     * 对话文字搜索区 {x0, x1, y0, y1}（窗口内坐标，基准 1030x797）。
     * 排除：聊天区（y&gt;560）、右上角小地图与「寻路」按钮（x&gt;810）、左侧技能/血条（x&lt;300）。
     */
    private static final int[] ZONE_DLG = {300, 810, 180, 560};

    /**
     * 地图名条区（右上角小地图正下方那行「地图名（x，y）」）。
     * 实测 063246：读作「霸王高阶」@ (907,165)；062141：「成都子」@ (901,165)。
     */
    private static final int[] ZONE_MAP_NAME = {840, 1030, 130, 195};

    /** 空关键词表：表示「本步不用这一类判据」（见 {@link #KW_T_DASIMA} 的说明）。 */
    private static final String[] KW_NONE = {};

    /** 「大司马」对话的标题关键词。
     *  ⚠️ **故意不给正文关键词**：大司马的正文是「你来找我有什么事吗?」，而军团大厅的
     *  「军团掌簿」NPC 说的是**一模一样的一句**（实测 060859）——用正文当判据会把
     *  「人在军团、对面站着军团掌簿」误判成「已经站在大司马面前」。所以只认标题
     *  「大司马」和目标选项文字，宁可认不出停下来，也不要点错。 */
    private static final String[] KW_T_DASIMA = {"大司马", "建武郎将", "武郎将"};

    /** 「高阶学艺导师」对话的标题 / 正文关键词（OCR 实测会误读成「学乞」）。 */
    private static final String[] KW_T_DAOSHI = {"高阶学艺导师", "高阶学乞导师", "学艺导师", "学乞导师", "艺导师", "乞导师"};
    /** 正文判据只取「只可能出现在这位 NPC 身上」的句子（「垓下剑派」实测读得出来）。 */
    private static final String[] KW_B_DAOSHI = {"垓下剑派", "学艺引导", "学乞引导", "切磋实践"};

    /** 各步骤要点的选项文字（**长词优先**，后面的短词只在前面的都认不出时才用）。
     *  括号里是 OCR 实测读法：「我要开始静修 → 我要始靜修」「离开霸王城 → 嵩开霸王」
     *  这类固定误读必须写进关键词；但**不要写太泛的词**（如裸的「垓下」），
     *  否则点完复检时会拿正文里的同一个词误判成「没点中」，反而去重复点击。 */
    private static final String[] OPT_GAIXIA   = {"垓下学艺", "垓下学乞", "下学艺", "下学乞"};
    private static final String[] OPT_SONGWO   = {"送我到霸王城内", "送我到霸王", "送到霸王"};
    private static final String[] OPT_CHUFA    = {"出发"};
    /** 「出发」确认框的正文（073259 实测「好出发了么」@(386,299)）——只在这个确认框出现，
     *  是「确认框开着」的身份判据；也是第 9 步点完「送我到霸王城内」后期望看到的下一步。 */
    private static final String[] KW_B_CHUFA   = {"好出发了么", "出发了么"};
    /** 「出发」选项匹配的**禁词**（073259 实跑事故）：确认框正文「好出发了么」里也有「出发」
     *  二字，包含匹配会把它当成选项点正文（点正文永远没反应）；而真按钮 @(527,336) 压在
     *  蓝条上 OCR 经常读不出来，最短行规则也救不了。带「了/么」的一定是正文不是按钮
     *  （按钮就俩字），匹配「出发」时把这类行剔掉 → 认不出就走兜底 (570,338)，正对按钮。 */
    private static final String[] BAN_CHUFA    = {"了", "么"};
    private static final String[] OPT_JINGXIU  = {"我要开始静修", "我要始静修", "我要始靜修", "开始静修", "始静修", "始靜修"};
    private static final String[] OPT_YANCHANG = {"延长学艺10分钟", "延长学艺10分", "学艺10分钟", "延长学艺"};
    private static final String[] OPT_LIKAI    = {"离开霸王城", "嵩开霸王城", "开霸王城", "离开霸王", "嵩开霸王", "开霸王"};

    /** 各步骤「首行 y」的实测兜底值（OCR 认不出选项时按它 + (index-1)×行距 推算）。
     *  来源：06:34 实跑日志（大司马 396 / 垓下菜单 376 / 出发 338 / 静修时长 399）
     *  + 061701_99_done 真菜单 OCR（高阶学艺导师 396）。 */
    private static final int FB_Y_DASIMA  = 396;
    private static final int FB_Y_GAIXIA  = 376;
    private static final int FB_Y_CHUFA   = 338;
    private static final int FB_Y_DAOSHI  = 396;
    private static final int FB_Y_YANCHANG = 399;

    /** 单步「认字→点→复检」的最大轮数（每轮含一次重新 OCR）。 */
    private static final int OPTION_TRIES = 3;

    /** 进图后等地图名变成目标地图的最长时间（用户要求：地图切换有 2~3 秒延迟，要等够）。 */
    private static final int MAP_SWITCH_SETTLE_MS = 2500;
    private static final int MAP_SWITCH_MAX_MS = 15000;

    /** 识别到的地图名关键词。 */
    private static final String[] KW_MAP_BAWANG   = {"霸王"};
    private static final String[] KW_MAP_CHENGDU  = {"成都"};

    // ==================== 虚拟键码 ====================

    private static final int VK_T    = WindowUtils.VK_T;      // 回城
    private static final int VK_O    = WindowUtils.VK_O;      // 军团界面
    private static final int VK_G    = WindowUtils.VK_G;      // 对话
    private static final int VK_ESC  = WindowUtils.VK_ESCAPE; // 关广告面板
    private static final int VK_ENTER = WindowUtils.VK_RETURN; // Enter 兜底（识图/定点都失败时当「确定」）
    private static final int VK_END  = WindowUtils.VK_END;    // 光标到末尾
    private static final int VK_BACK = WindowUtils.VK_BACK;   // 退格

    // —— 坐标框填写自检（阈值由 salary_shots 历史截图实测：空框≈0~6、单字≈17~20、
    //    "11"≈42、"7"≈50 白像素；白手套光标压框会凭空多出 ≈60，所以清空判定
    //    用「绝对空」和「前后对比」双条件，不能只看绝对值）——
    /** RGB 三通道都 ≥ 此值才算白像素（数字是白字，手套光标也是白）。 */
    private static final int WHITE_MIN = 200;
    /** 清空后框内白像素 ≤ 此值 → 确认框内已无数字。 */
    private static final int INK_EMPTY_MAX = 12;
    /** 清空前后白像素下降 ≥ 此值 → 确认清掉了旧数字（一个薄数字 ≈17）。 */
    private static final int INK_CLEAR_DROP = 14;
    /** 输入后框内白像素 ≥ 此值 → 确认数字真的落进了这个框（"10"/"16"≈42+）。 */
    private static final int INK_TYPED_MIN = 25;

    // ==================== 节奏 ====================

    /** 普通步骤缓冲 */
    private static final int STEP_MS = 1800;
    /** 点击后缓冲 */
    private static final int CLICK_MS = 1400;
    /** 短动作缓冲 */
    private static final int SHORT_MS = 800;
    /** ESC 关广告后缓冲 */
    private static final int ESC_MS = 1300;
    /** 跨地图传送（回军团 / 回城）的等待时间 */
    private static final int TELEPORT_MS = 3500;
    /** 点「移动」后等角色自动走过去的时间 */
    private static final int WALK_MS = 10000;
    /** 按 G 后等对话弹出的时间 */
    private static final int DIALOG_WAIT_MS = 1800;
    /** 按 G 尝试的最大次数 */
    private static final int G_MAX_TRIES = 12;

    // ==================== 运行状态 ====================

    private final GameWindowController controller;
    private final Listener listener;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;

    /** 本次运行里「点了鼠标但没能从画面上证实成功」的步骤名（收尾时统一汇报，杜绝假成功）。 */
    private final List<String> failedSteps = new java.util.ArrayList<>();

    private final File shotDir = new File("bawang_shots");

    public BawangTask(GameWindowController controller, Listener listener) {
        this.controller = controller;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** 请求中止（异步生效，最多等一个步骤缓冲）。 */
    public void requestStop() {
        stopRequested = true;
    }

    /** 启动任务线程。 */
    public void start() {
        if (running) {
            listener.log("霸王城任务已在运行中");
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
        }, "BawangTask");
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
            return new TaskOutcome(false, "霸王城任务已在运行中");
        }
        running = true;
        stopRequested = false;
        failedSteps.clear();
        boolean savedBg = controller.isRunInBackground();
        try {
            controller.setRunInBackground(true);
            listener.log("霸王城任务以「后台模式」运行（不抢焦点、不移动鼠标）");
            execute();
            return new TaskOutcome(true, "已完成霸王城全套流程（静修 10 分钟）");
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
        listener.log("================ 霸王城 开始 ================");

        Rectangle r = controller.getWindowRect();
        if (r == null) {
            throw new Abort("找不到游戏窗口，请先启动 QQ三国 并确认窗口未关闭");
        }
        listener.log("游戏窗口：" + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");

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

            // ---------- 1. 关闭广告等限制行动的界面 ----------
            closeAds();

            // ---------- 2. O → 点「回到军团」，先把角色拉回军团地图 ----------
            goBackToLegion();

            // ---------- 3. T 回城（成都） ----------
            backToTown();

            // ---------- 4. 打开寻路面板 ----------
            openNavPanel();

            // ---------- 5. 填坐标 10 / 16 ----------
            listener.log("—— 步骤 5/13：填入目标坐标 (" + TARGET_X + ", " + TARGET_Y + ") ——");
            measureNavPanel();
            fillCoordBox(curBox1, TARGET_X, "第 1 个坐标框");
            fillCoordBox(curBox2, TARGET_Y, "第 2 个坐标框");
            verifyBothBoxes();
            snapshot("05_coords_filled");

            // ---------- 6. 点移动，等走过去 ----------
            listener.log("—— 步骤 6/13：点击「移动」，角色自动走过去 ——");
            controller.clickWindowPoint(curMove[0], curMove[1]);
            sleep(WALK_MS);

            // ---------- 7. 关掉寻路面板 ----------
            closeNavPanel();

            // ---------- 8. G 与大司马对话，选「垓下学艺」 ----------
            listener.log("—— 步骤 8/13：G 与大司马对话 → 「垓下学艺」——");
            runDialogStep("垓下学艺", KW_T_DASIMA, KW_NONE, OPT_GAIXIA, 2, FB_Y_DASIMA,
                    OPT_SONGWO, true, null);

            // ---------- 9. 选「送我到霸王城内」 ----------
            listener.log("—— 步骤 9/13：「送我到霸王城内」——");
            // 下一步判据用确认框正文「好出发了么」，不用 OPT_CHUFA——
            // 虽然两者此刻都能命中同一句，但语义上等的是「确认框出现」，不是「出发按钮」。
            runDialogStep("送我到霸王城内", KW_T_DASIMA, KW_NONE, OPT_SONGWO, 4, FB_Y_GAIXIA,
                    KW_B_CHUFA, false, null);

            // ---------- 10. 点「出发」进入霸王城 ----------
            // 073259 事故：确认框正文「好出发了么」含「出发」，被包含匹配当成选项点正文；
            // 真按钮 @(527,336) 压在蓝条上 OCR 常读不出来。对策：正文行加禁词剔掉
            // （认不出就退兜底 (570,338)，实测正对按钮）；对话判据加正文「好出发了么」。
            listener.log("—— 步骤 10/13：点「出发」进入霸王城 ——");
            runDialogStep("出发", KW_T_DASIMA, KW_B_CHUFA, OPT_CHUFA, 1, FB_Y_CHUFA,
                    null, false, BAN_CHUFA);
            // 地图切换有 2~3 秒延迟：先等够，再轮询地图名确认「真的进了霸王城」，
            // 不再像旧版那样盲等 6 秒后无条件往下走（那正是假成功的源头之一）。
            awaitMap("霸王城", KW_MAP_BAWANG, true);
            // 能走到这 = 地图名已证实进了霸王城。若「出发」只是没拿到「对话关闭」证据
            // （传送动画超过了等框窗口），以地图名为准按成功处理，不算未证实。
            if (failedSteps.remove("出发")) {
                listener.log("  ✔「出发」未拿到对话关闭证据，但地图名已证实进入霸王城，按成功处理");
            }
            snapshot("10_entered_bawang");

            // ---------- 11. 霸王城内：静修 ----------
            listener.log("—— 步骤 11/13：G 与「高阶学艺导师」对话 → 「我要开始静修」——");
            runDialogStep("我要开始静修", KW_T_DAOSHI, KW_B_DAOSHI, OPT_JINGXIU, 1, FB_Y_DAOSHI,
                    OPT_YANCHANG, true, null);

            // ---------- 12. 延长学艺 10 分钟 ----------
            listener.log("—— 步骤 12/13：选择「延长学艺10分钟」——");
            runDialogStep("延长学艺10分钟", KW_T_DAOSHI, KW_B_DAOSHI, OPT_YANCHANG, 1, FB_Y_YANCHANG,
                    null, false, null);
            sleep(STEP_MS);
            snapshot("12_jingxiu_started");

            // ---------- 13. 离开霸王城 ----------
            // ⚠️ pressG 必须是 true（074525 实跑事故）：「延长学艺10分钟」点完后对话就关了，
            // 这一步要重新按 G 唤起导师菜单才点得到「离开霸王城」。误设 false 会变成
            // 纯等待 12 秒 → 「始终没认到对话」→ 白白记一笔未证实。
            listener.log("—— 步骤 13/13：G → 「离开霸王城」——");
            runDialogStep("离开霸王城", KW_T_DAOSHI, KW_B_DAOSHI, OPT_LIKAI, 5, FB_Y_DAOSHI,
                    null, true, null);
            sleep(STEP_MS);

            // 收尾前把「哪些步骤没被证实」说清楚 —— 绝不再出现「什么都没做却报成功」
            if (!failedSteps.isEmpty()) {
                String shot = snapshot("anomaly_steps_unverified");
                abort("有 " + failedSteps.size() + " 个步骤没能证实成功",
                        "下面这些步骤点了鼠标但没能从画面上证实成功，为避免「假成功」已停下：\n\n"
                                + "  · " + String.join("\n  · ", failedSteps) + "\n\n"
                                + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                                + "请对照截图确认进度后重新点击「霸王城」。");
            }

            String p = snapshot("99_done");
            listener.log("✔ 霸王城流程全部执行完毕" + (p != null ? "（截图：" + p + "）" : ""));
        } finally {
            // 后台模式不动真实鼠标（用户可能正在用电脑）
            if (!controller.isRunInBackground() && savedCursor != null) {
                controller.moveCursor(savedCursor[0], savedCursor[1]);
            }
        }
    }

    // ==================== 前置清理（关广告 → 回军团 → 回城） ====================

    /**
     * 关闭游戏广告弹窗。
     *
     * <p>「游戏活动展示」大面板用 ESC 关（只有检测到广告时才按，避免在干净画面下
     * 误按 ESC 打开游戏系统菜单）；「热点活动」是独立小窗口，ESC 无效，必须点右上角 X。
     *
     * <p>广告不关干净的话，右上角「寻路」按钮与坐标输入框会被盖住，点击会落到弹窗上。
     */
    void closeAds() {
        listener.log("—— 步骤 1/13：关闭游戏广告弹窗 ——");

        if (!adHotspotPresent()) {
            listener.log("  未检测到广告弹窗，跳过（不按 ESC，避免打开游戏系统菜单）");
            return;
        }

        listener.log("  检测到广告，使用 ESC 关闭「游戏活动展示」面板");
        for (int i = 1; i <= 3; i++) {
            checkStop();
            controller.sendKey(VK_ESC);
            listener.log("  ESC 第 " + i + " 次");
            sleep(ESC_MS);
        }

        for (int i = 1; i <= 2; i++) {
            checkStop();
            if (!adHotspotPresent()) {
                listener.log("  ✔ 「热点活动」弹窗已关闭");
                break;
            }
            listener.log("  检测到「热点活动」弹窗，点击右上角 X（第 " + i + " 次）");
            controller.clickWindowPoint(PT_AD_HOTSPOT_X[0], PT_AD_HOTSPOT_X[1]);
            sleep(CLICK_MS);
        }

        if (adHotspotPresent()) {
            String shot = snapshot("anomaly_ad_still_open");
            abort("广告弹窗「热点活动」未能关闭",
                    "广告弹窗没关掉，它会遮住右上角「寻路」按钮，后面每一步点击都会落空。\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请手动关掉该弹窗后重新点击「霸王城」。");
        }
        listener.log("  ✔ 广告弹窗处理完成");
    }

    /**
     * 按 O 打开军团界面 → 点「回到军团」，把角色传送到军团地图。
     *
     * <p>这一步是为了「定锚」：不管角色当前在霸王城、还是别的什么地图，
     * 先统一回到军团，再按 T 就一定能回到成都主城。
     */
    void goBackToLegion() {
        listener.log("—— 步骤 2/13：按 O 打开军团界面 → 点「回到军团」——");
        controller.ensureWindowVisible();
        int[] btn = SalaryTask.findBackToLegionButton(captureQuiet());
        if (btn == null) {
            controller.sendKey(VK_O);
            sleep(STEP_MS);
            btn = SalaryTask.findBackToLegionButton(captureQuiet());
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
        sleep(TELEPORT_MS); // 等跨地图传送完成
        listener.log("  ✔ 已回到军团地图");
    }

    /** 按 T 回城（成都·子城）。「自动寻路」的坐标 (10,16) 只在这张图里成立。 */
    void backToTown() {
        listener.log("—— 步骤 3/13：按 T 回城（成都）——");
        controller.sendKey(VK_T);
        sleep(TELEPORT_MS);
        listener.log("  ✔ 已回到主城");
    }

    /**
     * 「热点活动」弹窗是否还在 = 色块偏红 <b>且</b> OCR 认得出广告标题。
     *
     * <p>色块判据的老坑：200056（工资）/ 055152（孝廉）两次「广告未关」现场截图里画面
     * 根本没有弹窗 —— 秋天树冠、灯笼等红色景物让采样区 R-G&gt;50，纯色块会把好局白白中止。
     * 所以色块命中后还要 OCR 认到「热点活动/游戏活动展示」字样才算数（ScreenText 与
     * 对话判据同源）。
     */
    private boolean adHotspotPresent() {
        double[] m = patchMean(controller.captureWindow(), PATCH_AD_HOTSPOT);
        if (m == null) {
            return false;
        }
        double rg = m[0] - m[1];
        listener.log(String.format("    [检测] 广告区 RGB=(%.0f,%.0f,%.0f)　R-G=%+.0f（>%.0f 表示弹窗仍在）",
                m[0], m[1], m[2], rg, TH_AD_RG));
        if (rg <= TH_AD_RG) {
            return false;
        }
        boolean title = findInLines(ScreenText.lines(captureQuiet()), KW_AD_TITLE, ZONE_AD_TITLE) != null;
        if (!title) {
            listener.log("    [检测] 色块偏红但 OCR 没认出广告标题 —— 多半是红色景物（秋叶/灯笼）误报，按「无弹窗」处理");
        }
        return title;
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

    // ==================== 寻路面板 ====================

    /**
     * 打开「自动寻路」面板。
     *
     * <p>「寻路」按钮点开后不出现面板，最常见的原因就是<b>角色不在城镇里</b>
     * （「自动寻路」只在城镇可用）。所以这里除了点 3 次重试，还会在整轮失败后
     * <b>补按一次 T 回城</b>再重来一轮 —— 把「传送没到位」这种偶发情况也兜住。
     */
    private void openNavPanel() {
        listener.log("—— 步骤 4/13：点击右上角「寻路」按钮 ——");

        for (int round = 1; round <= 2; round++) {
            if (navPanelOpen()) {
                listener.log("  检测到「自动寻路」面板已打开，先关闭");
                measureNavPanel();
                clickNavClose();
                sleep(CLICK_MS);
            }

            for (int i = 1; i <= 3; i++) {
                checkStop();
                listener.log("  点击「寻路」按钮（第 " + i + " 次）");
                controller.clickWindowPoint(PT_XUNLU_BTN[0], PT_XUNLU_BTN[1]);
                sleep(CLICK_MS);
                if (navPanelOpen()) {
                    listener.log("  ✔ 「自动寻路」面板已打开");
                    return;
                }
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
                        + "  ① 角色不在城镇里（先按 T 回城再试）\n"
                        + "  ② 屏幕上有其它弹窗遮挡了右上角\n"
                        + "  ③ 游戏窗口尺寸/位置被改过，请用默认 1030x797\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "请处理后重新点击「霸王城」。");
    }

    /** 关闭「自动寻路」面板（点右上角 X，位置跟着实测框走），最多试 3 次。 */
    private void closeNavPanel() {
        listener.log("—— 步骤 7/13：关闭「自动寻路」面板 ——");
        for (int i = 1; i <= 3; i++) {
            if (!navPanelOpen()) {
                listener.log("  ✔ 「自动寻路」面板已关闭");
                return;
            }
            if (i == 1) {
                measureNavPanel(); // 面板可能漂过，按最新框位重算 X
            }
            listener.log("  点击面板右上角 X @ (" + curClose[0] + "," + curClose[1] + ")（第 " + i + " 次）");
            clickNavClose();
            sleep(CLICK_MS);
        }
        if (navPanelOpen()) {
            String shot = snapshot("anomaly_nav_still_open");
            abort("「自动寻路」面板未能关闭",
                    "「自动寻路」面板一直没关掉，后面按 G 会被它挡住。\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请手动关掉该面板后重新点击「霸王城」。");
        }
    }

    private void clickNavClose() {
        controller.clickWindowPoint(curClose[0], curClose[1]);
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
            curMove = new int[]{PT_NAV_MOVE[0] + (curBox2[0] - PT_NAV_BOX2[0]), curBox2[1]};
            listener.log("  未量到「移动」按钮，按框位推算 (" + curMove[0] + "," + curMove[1] + ")");
        }
        // X 在面板标题行右端：实测相对偏移 box2 +（157,-255）（09:24 现场实测）
        curClose = new int[]{curBox2[0] + 157, curBox2[1] - 255};
    }

    /** 「自动寻路」面板是否开着：用两个深色坐标框的独有指纹判定（位置无关，面板漂移不影响）。 */
    private boolean navPanelOpen() {
        try {
            return XiaolianTask.findNavBoxesCore(controller.captureWindow()) != null;
        } catch (Throwable t) {
            return scanUi().navOpen; // 截图不可用时退回旧判定
        }
    }

    /**
     * 点进坐标输入框 → 清空（截图验证真的空了）→ 输入数字（字形计数验证 + 自动纠正）。
     *
     * <p>与工资任务同款「7 被填成 77」修复：根因是后台 PostMessage 的
     * WM_KEYDOWN+WM_KEYUP 被游戏翻译成<b>两个</b>字符，坐标框限长 2 位。
     * 现在输入走 WM_CHAR（一条消息一个字符），输入后数「字形个数」做精确验证，
     * 多了退格、少了补打、一个没有退回逐位按键（每打一位数一次，翻倍立刻退格）。
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
            // （白像素数分不清 "7" 和 "77" —— 两者都是 ~42，工资任务前两版修复就栽在这）
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
     * 两框都填完后终检：字形数必须恰好等于目标位数（防 7→77、防串框如 10 被补成 107）。
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

    // ==================== 对话与选项（OCR 认字优先，2026-09-25 重构） ====================
    //
    // 旧实现：scanUi() 用「亮蓝色横带」判对话、clickOption() 按「首行 y + (index-1)×27.7」盲点。
    // 实测两大类翻车（151 帧里 35 帧与另一种判据结论相反）：
    //   · 假阳性 → 一次 G 都不按就「检测到对话」，然后一路盲点并报成功；
    //   · 高亮条判到的行不是首行（061701 那帧判到「取消」），点「我要开始静修」实际点了「取消」。
    // 现在：对话在不在 = OCR 认不认得出该 NPC 的标题/正文/选项文字；选项位置 = OCR 命中的坐标
    // （认不出才退实测首行 y 兜底）；点完必须复检；每一步都要拿到「下一步提示词出现」或
    // 「本步对话关闭」才算成功，否则记入 failedSteps 并在收尾时统一报错。
    // ⚠ 名牌陷阱（071439 实跑）：NPC 头顶名字浮标（「建武郎将」@(504,247)）落在 ZONE_DLG 内，
    //   会被 OCR 认成「对话标题」→ 一次 G 都不按就以为对话开着。因此按 G 的步骤只有
    //   「选项文字出现」才算对话真的开着（见 ensureDialogUp）。

    /**
     * 跑完一个「对话选项步骤」，没被证实就计入 {@link #failedSteps}（绝不静默算成功）。
     *
     * <p>推进手段按优先级：<b>① 识图</b>（OCR 认字后点文字本身）→ <b>② 定点辅助</b>
     * （OCR 认不出时按实测行距推算坐标）→ <b>③ Enter 兜底</b>（前两级都没拿到画面证据时
     * 按一次 Enter 当「确定」，见 {@link #enterFallback}）。
     *
     * @param what      日志里显示的步骤名（也是「要点的那一项」）
     * @param titleKw   该 NPC 对话的标题关键词（判定对话在不在）
     * @param bodyKw    该 NPC 对话的正文关键词（标题被遮挡时的兜底判据）
     * @param optKw     要点的选项文字关键词（长词优先）
     * @param index     选项序号（1 起；OCR 认不出文字时按行距兜底推算）
     * @param fbFirstY  OCR 认不出选项时用的「实测首行 y」
     * @param nextKw    点完后期望出现的下一步提示词；null = 只要求本步对话关闭
     * @param pressG    对话不在时是否需要按 G 唤起（第 9/10/12 步的菜单是上一步点出来的，不用按）
     * @param optBan    选项匹配的禁词（可为 null）：正文里也含选项同词时把正文行剔掉，
     *                  见 {@link #BAN_CHUFA}（「好出发了么」vs「出发」按钮）
     */
    private void runDialogStep(String what, String[] titleKw, String[] bodyKw,
                               String[] optKw, int index, int fbFirstY,
                               String[] nextKw, boolean pressG, String[] optBan) {
        for (int round = 1; round <= 2; round++) {
            checkStop();
            if (!ensureDialogUp(what, titleKw, bodyKw, optKw, pressG, round)) {
                listener.log("  ⚠「" + what + "」第 " + round + " 轮：始终没认到该 NPC 的对话文字");
                continue;
            }
            boolean confirmed = clickOptionByText(what, optKw, titleKw, bodyKw, index, fbFirstY, optBan);
            boolean ok;
            if (nextKw != null) {
                ok = confirmed || awaitKeyword(nextKw, what + " 的下一步");
                if (!confirmed && ok) {
                    listener.log("  （复检没确认，但下一步提示词已出现，按成功处理）");
                }
            } else {
                ok = confirmed || awaitDialogGone(what, titleKw, bodyKw, optKw);
            }
            if (!ok) {
                // 三级策略的最后一级：识图最优 → 定点辅助（clickOptionByText 的兜底坐标）
                // → Enter 当「确定」。0707~0745 三次实跑证明 OCR 偶发整片读不出、蓝条判据
                // 已废、定点也可能压空 —— 这时与其直接记失败，不如按一次 Enter 搏一下，
                // 但必须用画面证据把关（见 {@link #enterFallback}）。
                ok = enterFallback(what, titleKw, bodyKw, nextKw);
            }
            if (ok) {
                listener.log("  ✔ " + what + "：已从画面证实完成");
                return;
            }
            listener.log("  ⚠「" + what + "」第 " + round + " 轮：点了但没能从画面证实");
        }
        listener.log("  ✘ " + what + "：两轮都没能证实，记入「未证实清单」");
        failedSteps.add(what);
    }

    /**
     * Enter 兜底：识图最优 → 定点辅助 → <b>Enter 确认</b> 的最后一级（2026-09-26 用户要求加入）。
     *
     * <p>按一次 Enter，用画面证据判断是否真的推进了：
     * <ul>
     *   <li>有 nextKw → 认到下一步提示词 = 推进；</li>
     *   <li>nextKw 为 null → 对话整体关闭（标题+正文都认不到了）= 推进。</li>
     * </ul>
     *
     * <p>⚠️ <b>Enter 是聊天输入框的开关</b>（军团任务实测：Enter 被聊天框吃掉后，后续
     * 按 G 全变成打字，残留 {@code ksdafqawert}）。所以没推进时<b>必须再按一次 Enter
     * 把聊天框关掉</b>，关掉后复检一次证据 —— 万一第一次其实推进了只是 OCR 慢，也别错过。
     */
    private boolean enterFallback(String what, String[] titleKw, String[] bodyKw, String[] nextKw) {
        for (int press = 1; press <= 2; press++) {
            checkStop();
            if (press == 1) {
                listener.log("  ⚠ 识图与定点都没证实，按 Enter 兜底当「确定」（第 1 次）");
            }
            controller.sendKey(VK_ENTER);
            sleep(DIALOG_WAIT_MS);
            List<ScreenText.TextLine> lines = ScreenText.lines(captureQuiet());
            boolean advanced = (nextKw != null && findInLines(lines, nextKw, ZONE_DLG) != null)
                    || (findInLines(lines, titleKw, ZONE_DLG) == null
                        && findInLines(lines, bodyKw, ZONE_DLG) == null);
            if (advanced) {
                listener.log("  ✔ Enter 兜底生效：「" + what + "」已从画面证实推进");
                return true;
            }
            if (press == 1) {
                listener.log("  ⚠ Enter 没推进对话 —— 很可能打开了底部聊天输入框（Enter 是开关），"
                        + "再按一次 Enter 把它关掉，防止后面的按键变成打字");
            }
        }
        return false;
    }

    /** 对话是否已在画面里：OCR 认得出该 NPC 的标题 / 正文 / 目标选项任一即可。 */
    private boolean dialogUp(String[] titleKw, String[] bodyKw, String[] optKw) {
        List<ScreenText.TextLine> lines = ScreenText.lines(captureQuiet());
        return findInLines(lines, titleKw, ZONE_DLG) != null
                || findInLines(lines, bodyKw, ZONE_DLG) != null
                || findInLines(lines, optKw, ZONE_DLG) != null;
    }

    /**
     * 确保对话出现在画面里：已经有了就直接过，没有就按 G（或纯等待）直到认出来。
     *
     * <p><b>名牌陷阱（071439 实跑事故）</b>：角色走到 NPC 面前后，NPC 头顶的名字浮标
     * （大司马头顶读作「建武郎将」@(504,247)）正好落在 {@link #ZONE_DLG} 里，OCR 会把它
     * 认成「对话标题」→ 旧逻辑一次 G 都不按就以为对话开着 → 退兜底坐标盲点 → 什么都没点成。
     * 因此 pressG=true 的步骤<b>只有「选项文字出现」才算对话真的开着</b>——选项只存在于
     * 真对话里，名牌上不会有；只认到标题/正文时照按 G。
     */
    private boolean ensureDialogUp(String what, String[] titleKw, String[] bodyKw,
                                   String[] optKw, boolean pressG, int round) {
        if (dialogUpByOption(optKw)) {
            listener.log("  ✔ 已认到「" + what + "」的选项文字（对话确实开着，无需按 G）");
            return true;
        }
        if (!pressG) {
            for (int i = 1; i <= 6; i++) {
                checkStop();
                listener.log("  等待「" + what + "」的对话出现…（第 " + i + "/6 次）");
                sleep(DIALOG_WAIT_MS);
                if (dialogUp(titleKw, bodyKw, optKw)) {
                    listener.log("  ✔ 已认到「" + what + "」的对话文字");
                    return true;
                }
            }
            return false;
        }
        // 对话正文 = NPC 台词，只出现在真对话里（头顶名牌不会有）。正文认得出 = 对话已经
        // 开着，只是选项 OCR 还没读出来 —— 直接放行，交给 clickOptionByText 认字/兜底去点。
        // ⚠️ 074419 实跑事故：不看正文、只认选项，认不出就连按 12 次 G —— G 把开着的
        // 对话开了关、关了开，菜单始终不稳，拖到下一步才认到。正文在就绝不再按 G。
        if (findInLines(ScreenText.lines(captureQuiet()), bodyKw, ZONE_DLG) != null) {
            listener.log("  ✔ 已认到「" + what + "」的对话正文（对话确实开着，无需按 G）");
            return true;
        }
        if (dialogUp(titleKw, bodyKw, optKw)) {
            listener.log("  ℹ 画面里只认到「" + what + "」的标题、没认到正文/选项文字"
                    + "（很可能只是 NPC 头顶名牌，不是对话），仍按 G 唤起");
        }
        for (int i = 1; i <= G_MAX_TRIES; i++) {
            checkStop();
            listener.log("  第 " + i + "/" + G_MAX_TRIES + " 次按 G 尝试唤起「" + what + "」对话");
            controller.sendKey(VK_G);
            sleep(DIALOG_WAIT_MS);
            if (dialogUpByOption(optKw)) {
                listener.log("  ✔ 已认到「" + what + "」的选项文字（按 G 后对话已打开）");
                return true;
            }
            if (findInLines(ScreenText.lines(captureQuiet()), bodyKw, ZONE_DLG) != null) {
                listener.log("  ✔ 按 G 后认到「" + what + "」的对话正文（对话已打开，无需再按 G）");
                return true;
            }
        }
        return false;
    }

    /** 选项文字是否已在画面里 —— 选项只存在于真对话中，NPC 头顶名牌不会有。 */
    private boolean dialogUpByOption(String[] optKw) {
        return optKw.length > 0
                && findInLines(ScreenText.lines(captureQuiet()), optKw, ZONE_DLG) != null;
    }

    /**
     * 认字 → 点击 → 复检。返回 true = 已确认点中（该关键词的文字从画面消失）。
     *
     * <p>OCR 认出选项文字时点它的实测坐标；认不出才退「实测首行 y + (index-1)×行距」，
     * 这时没法用认字复检，返回 false 交给调用方用「下一步提示词 / 对话关闭」把关。
     */
    private boolean clickOptionByText(String what, String[] optKw, String[] titleKw,
                                      String[] bodyKw, int index, int fbFirstY, String[] optBan) {
        for (int attempt = 1; attempt <= OPTION_TRIES; attempt++) {
            checkStop();
            ScreenText.TextLine hit = findInLines(ScreenText.lines(captureQuiet()), optKw, ZONE_DLG, optBan);
            boolean typed = hit != null;
            int cx;
            int cy;
            if (typed) {
                cx = hit.cx;
                cy = hit.cy;
                listener.log("  认字命中「" + hit.text + "」@ (" + cx + "," + cy + ")"
                        + "（第 " + index + " 项 " + what + "）");
            } else {
                cy = (int) Math.round(fbFirstY + (index - 1) * ROW_PITCH);
                cx = OPTION_CLICK_X;
                listener.log("  ⚠ 没认出「" + what + "」的文字，退兜底坐标 (" + cx + "," + cy
                        + ")（首行 y " + fbFirstY + " + " + (index - 1) + "×" + ROW_PITCH + "）");
            }
            controller.clickWindowPoint(cx, cy);
            sleep(CLICK_MS);

            if (!typed) {
                return false; // 兜底路径无法用认字复检 → 交给下一步判据
            }
            // 复检：选项文字消失（菜单换菜单）或整个对话关闭（点中了会关框的选项）都算点中
            List<ScreenText.TextLine> after = ScreenText.lines(captureQuiet());
            boolean optGone = findInLines(after, optKw, ZONE_DLG, optBan) == null;
            boolean dlgGone = findInLines(after, titleKw, ZONE_DLG) == null
                    && findInLines(after, bodyKw, ZONE_DLG) == null;
            if (optGone || dlgGone) {
                listener.log("  ✔ 复检通过（" + (optGone ? "选项文字已从画面消失" : "对话已关闭") + "）");
                return true;
            }
            ScreenText.TextLine still = findInLines(after, optKw, ZONE_DLG, optBan);
            listener.log("  ⚠ 点完「" + what + "」的状态没变（文字还在 @ ("
                    + (still != null ? still.cx + "," + still.cy : "?") + ")），重试（第 "
                    + attempt + "/" + OPTION_TRIES + " 次）");
        }
        return false;
    }

    /** 等某个提示词出现在画面里（点完上一步后期望看到的下一步 UI 文字）。 */
    private boolean awaitKeyword(String[] kw, String what) {
        for (int i = 1; i <= 10; i++) {
            checkStop();
            ScreenText.TextLine hit = findInLines(ScreenText.lines(captureQuiet()), kw, ZONE_DLG);
            if (hit != null) {
                listener.log("  ✔ 已等到「" + what + "」：" + hit.text + " @ (" + hit.cx + "," + hit.cy + ")");
                return true;
            }
            listener.log("  等待「" + what + "」出现…（第 " + i + "/10 次）");
            sleep(700);
        }
        return false;
    }

    /** 等本步对话整体消失（没有「下一步提示词」可等时的推进判据）。 */
    private boolean awaitDialogGone(String what, String[] titleKw, String[] bodyKw, String[] optKw) {
        for (int i = 1; i <= 8; i++) {
            checkStop();
            List<ScreenText.TextLine> lines = ScreenText.lines(captureQuiet());
            if (findInLines(lines, titleKw, ZONE_DLG) == null
                    && findInLines(lines, bodyKw, ZONE_DLG) == null
                    && findInLines(lines, optKw, ZONE_DLG) == null) {
                listener.log("  ✔「" + what + "」的对话已从画面消失（推进成功）");
                return true;
            }
            listener.log("  等「" + what + "」的对话关闭…（第 " + i + "/8 次）");
            sleep(700);
        }
        return false;
    }

    /**
     * 等地图名变成目标地图（跨图传送校验）。
     *
     * <p>用户反馈「地图切换过程也有一定的延迟」：所以先无条件等 {@link #MAP_SWITCH_SETTLE_MS}
     * （2.5 秒）再开始轮询，最长等 {@link #MAP_SWITCH_MAX_MS}。
     * <ul>
     *   <li>认到目标地图 → 通过；</li>
     *   <li>读出了地图名但不是目标 → 报错中止（基本等于「根本没进去」，旧版在这里静默放过）；</li>
     *   <li>一个字都没读出来 → 只记日志（OCR 偶发漏读不该阻断任务），但如实说明「无法确认」。</li>
     * </ul>
     */
    private void awaitMap(String what, String[] kw, boolean required) {
        sleep(MAP_SWITCH_SETTLE_MS);
        long deadline = System.currentTimeMillis() + MAP_SWITCH_MAX_MS;
        int tries = 0;
        String lastRead = null;
        while (System.currentTimeMillis() < deadline) {
            checkStop();
            tries++;
            List<ScreenText.TextLine> lines = ScreenText.lines(captureQuiet());
            ScreenText.TextLine hit = findInLines(lines, kw, ZONE_MAP_NAME);
            if (hit != null) {
                listener.log("  ✔ 已确认进入「" + what + "」：地图名读作「" + hit.text
                        + "」@ (" + hit.cx + "," + hit.cy + ")（第 " + tries + " 次轮询）");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (ScreenText.TextLine l : lines) {
                if (l.cx >= ZONE_MAP_NAME[0] && l.cx <= ZONE_MAP_NAME[1]
                        && l.cy >= ZONE_MAP_NAME[2] && l.cy <= ZONE_MAP_NAME[3]) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append('"').append(l.text).append('"');
                }
            }
            lastRead = sb.length() == 0 ? null : sb.toString();
            listener.log("  等待地图名变成「" + what + "」…（第 " + tries + " 次"
                    + (lastRead != null ? "，当前读到 " + lastRead : "，当前地图名一个字都没读到") + "）");
            sleep(700);
        }
        if (lastRead != null && required) {
            String shot = snapshot("anomaly_not_entered");
            abort("没能进入「" + what + "」",
                    "点了传送之后等了 " + ((MAP_SWITCH_SETTLE_MS + MAP_SWITCH_MAX_MS) / 1000)
                            + " 秒，地图名一直是 " + lastRead + "，没有变成「" + what + "」。\n\n"
                            + "常见原因：\n"
                            + "  ① 上一步的「" + what + "」入口选项没点中（对话框位置漂了）\n"
                            + "  ② 角色不在成都·子城的 (10,16)，大司马旁边\n"
                            + "  ③ 游戏弹了验证提示或提示框挡住了点击\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请处理后重新点击「霸王城」。");
        }
        // 一个字都没读出来 ≠ 没进去（OCR 偶发漏读）。这里**只警告、不判失败**：
        // 真正的把关交给下游 —— 没进霸王城的话，第 11~13 步认不到「高阶学艺导师」，
        // 那会走 failedSteps 收尾报错。凭空因 OCR 漏读把成功的局判失败，同样是「报错结果」。
        listener.log("  ⚠ 地图名区一个字都没读出来（OCR 漏读），无法确认是否已进入「" + what
                + "」，继续往下走；若第 11 步认不到「高阶学艺导师」就会在最后统一报错");
    }

    /**
     * 在一批 OCR 文字行里找关键词命中。
     *
     * <p>规则：只在 {@code zone} 内找；关键词**长词优先**（依数组顺序，先命中先返回）；
     * 同一个关键词命中多行时取**最短**的那行（正文里也常出现同样的词，选项才是短的）。
     *
     * <p>匹配用「繁简折叠后的包含」——实测屏幕上是简体的「我要开始静修」，OCR 却给回
     * 「我要始靜修」；不做折叠关键词会整片失配。
     */
    private static ScreenText.TextLine findInLines(List<ScreenText.TextLine> lines,
                                                   String[] keywords, int[] zone) {
        return findInLines(lines, keywords, zone, null);
    }

    /** 同上，额外支持「禁词」：行内含任一禁词直接跳过（用于把含同词的正文行从选项匹配里剔掉，
     *  见 {@link #BAN_CHUFA} 的事故说明）。 */
    private static ScreenText.TextLine findInLines(List<ScreenText.TextLine> lines,
                                                   String[] keywords, int[] zone, String[] banSubs) {
        if (lines == null || lines.isEmpty() || keywords == null || zone == null) {
            return null;
        }
        for (String kw : keywords) {
            String want = fold(kw);
            if (want.isEmpty()) {
                continue;
            }
            ScreenText.TextLine best = null;
            for (ScreenText.TextLine l : lines) {
                if (l.cx < zone[0] || l.cx > zone[1] || l.cy < zone[2] || l.cy > zone[3]) {
                    continue;
                }
                String text = fold(l.text);
                if (banSubs != null) {
                    boolean banned = false;
                    for (String b : banSubs) {
                        String fb = fold(b);
                        if (!fb.isEmpty() && text.contains(fb)) {
                            banned = true;
                            break;
                        }
                    }
                    if (banned) {
                        continue;
                    }
                }
                if (!text.contains(want)) {
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

    /**
     * 把 OCR 常吐出的繁体字形折叠成简体，再走 {@link XiaolianBank#normalize}。
     *
     * <p>只覆盖本任务实测出现过的字 + 常见高频字。{@code XiaolianBank.normalize} 只做
     * 标点/全角归一，不做繁简转换，所以这层折叠是必须的。
     */
    private static String fold(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '靜': c = '静'; break;
                case '學': c = '学'; break;
                case '藝': c = '艺'; break;
                case '開': c = '开'; break;
                case '內': c = '内'; break;
                case '離': c = '离'; break;
                case '習': c = '习'; break;
                case '練': c = '练'; break;
                case '體': c = '体'; break;
                case '點': c = '点'; break;
                case '說': c = '说'; break;
                case '謝': c = '谢'; break;
                case '錢': c = '钱'; break;
                case '園': c = '园'; break;
                case '軍': c = '军'; break;
                case '將': c = '将'; break;
                case '導': c = '导'; break;
                case '師': c = '师'; break;
                case '級': c = '级'; break;
                case '務': c = '务'; break;
                case '動': c = '动'; break;
                case '對': c = '对'; break;
                case '過': c = '过'; break;
                case '還': c = '还'; break;
                case '選': c = '选'; break;
                case '擇': c = '择'; break;
                case '閉': c = '闭'; break;
                case '險': c = '险'; break;
                case '緊': c = '紧'; break;
                default: break;
            }
            sb.append(c);
        }
        return XiaolianBank.normalize(sb.toString());
    }

    // ==================== 界面检测 ====================

    /** 一次扫描得到的所有界面信息。（包内可见，供 {@link BawangDiag} 自检复用） */
    static final class Ui {
        /** 「自动寻路」面板是否打开 */
        boolean navOpen;
        /** NPC 对话（带选项高亮条）是否打开 */
        boolean dialogOpen;
        /** 对话第 1 个选项的中心 y（窗口内坐标，仅 dialogOpen 时有效） */
        int option1Y;
    }

    /**
     * 扫描游戏画面，判断「自动寻路」面板 / NPC 对话 是否打开。
     *
     * <p>两者都靠「亮蓝色横带」识别：面板是又高又宽的大蓝底（带高 &gt; 45px），
     * 对话首项高亮条则只有 17~22px 高。这里逐行算平均色的 B-R，超过阈值即视为蓝色行。
     *
     * <p>包内可见，{@link BawangDiag} 直接复用同一套判定，避免自检与实跑不一致。
     */
    Ui scanUi() {
        return scanImage(controller.captureWindow());
    }

    /** 在给定截图上跑判定逻辑（抽出来是为了能用离线截图做自检）。 */
    static Ui scanImage(BufferedImage img) {
        Ui ui = new Ui();
        if (img == null) {
            return ui;
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x0 = (int) Math.round(DLG_X0 * kx);
        int x1 = (int) Math.round(DLG_X1 * kx);
        int y0 = (int) Math.round(DLG_Y0 * ky);
        int y1 = (int) Math.round(DLG_Y1 * ky);
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(img.getWidth(), x1);
        y1 = Math.min(img.getHeight(), y1);
        if (x1 <= x0 || y1 <= y0) {
            return ui;
        }

        int top = -1, bot = -1;
        double peak = 0;
        for (int y = y0; y < y1; y++) {
            double sr = 0, sb = 0;
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                sr += (p >> 16) & 0xFF;
                sb += p & 0xFF;
            }
            int n = x1 - x0;
            double br = (sb - sr) / n;
            if (br > TH_HILITE) {
                if (top < 0) {
                    top = y;
                }
                bot = y;
                if (br > peak) {
                    peak = br;
                }
            }
        }
        if (top < 0) {
            return ui;
        }

        int span = bot - top + 1;
        if (span > MAX_DLG_SPAN) {
            ui.navOpen = true;
            return ui;
        }
        ui.dialogOpen = true;
        double centerBase = ((top + bot) / 2.0) / ky;
        ui.option1Y = (int) Math.round(centerBase);
        return ui;
    }

    // ==================== 异常与收尾 ====================

    /** 抛异常前统一记录现场并提醒。 */
    private void abort(String reason, String humanMessage) {
        listener.log("✘ 异常中止：" + reason);
        listener.alert("霸王城 · 需要人工处理", humanMessage);
        stopRequested = true;
        throw new Abort(reason);
    }

    private void checkStop() {
        if (stopRequested) {
            throw new Abort("已被用户中止");
        }
    }

    /** 可中断的等待。 */
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

    /** 保存一张窗口截图到 bawang_shots/，返回文件路径（失败返回 null）。 */
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
