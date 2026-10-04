package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 「推举孝廉」一键答题执行器。
 *
 * <h3>完整流程（2026-09-23 简化：孝廉按钮不再寻路）</h3>
 * <pre>
 *  [孝廉] 请先把角色走到成都「诰令司丞」面前再点按钮 —— 赶路由玩家自己负责：
 *  0.  （可选）F11 屏蔽其他玩家 → 关闭广告弹窗
 *  1.  G 唤起对话 → 「对话/任务」→ 选「推举孝廉」→ 确认参加
 *  2.  答题循环（约 10 题，每题限时 15 秒、逐题递减）：
 *        a. 截图 → 抠出对话框区域 → 放大 2 倍 → 交给系统 OCR 读出文字行
 *        b. 在本地 2256 条题库里模糊匹配题目（二元组 Dice）→ 拿到标准答案
 *        c. 再把标准答案和屏幕上的每个选项比一遍 → 最像的那个就是正确选项
 *        d. 题库查不到时才调 DeepSeek 兜底
 *        e. 点中该选项 → 复查是否翻页了；没翻页再补一次回车确认
 *  3.  收尾：再对话一次把任务交掉
 *
 *  [运送] 仍走完整自动寻路（{@link #navigateToNpc()}）：
 *  T 回城 → 寻路 (20,16) → 按「↑」换图 → 寻路 (11,8) → G →「对话/任务」→「运送物资」…
 * </pre>
 *
 * <h3>为什么用「先读屏再决策」而不是全像素</h3>
 * 这是答题任务，题目和选项都是文字。且每题只有 15 秒，
 * 所以走「本地题库优先（零等待）、大模型兜底」的策略。
 *
 * <h3>坐标说明</h3>
 * 全部是「窗口内坐标」，基准分辨率 1030x797，运行时按游戏窗口真实尺寸等比换算。
 */
public class XiaolianTask {

    /**
     * 任务模式：两个任务找的是<b>同一个 NPC（成都诰令司丞）</b>，
     * 只有「要不要自动寻路」和「接完任务做什么」不一样。
     *
     * <table>
     *   <tr><th></th><th>{@link #XIAOLIAN}</th><th>{@link #YUNSONG}</th></tr>
     *   <tr><td>寻路</td><td>不寻路（玩家自己走到 NPC 面前）</td>
     *       <td>T 回城 → (20,16) → ↑ 换图 → (11,8)</td></tr>
     *   <tr><td>接任务</td><td>对话/任务 → 推举孝廉 → 请交给我吧</td>
     *       <td>对话/任务 → 运送物资 → 请交给我吧</td></tr>
     *   <tr><td>后续</td><td>答题循环 → 收尾再对话交任务</td>
     *       <td>G → 对话/任务 → 长正文 → 好的我马上去 → 长正文×2 → 好的请给我吧</td></tr>
     * </table>
     */
    public enum Mode {
        /** 推举孝廉：接任务后答题。 */
        XIAOLIAN("孝廉"),
        /** 运送物资：接任务后连按回车结束对话。 */
        YUNSONG("运送物资");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
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

    // ==================== 基准 ====================

    private static final int BASE_W = GameWindowController.BASE_WIDTH;
    private static final int BASE_H = GameWindowController.BASE_HEIGHT;

    /** DeepSeek API Key（可用 -Dqqsg.deepseek.key=xxx 覆盖，或在工作目录放 deepseek.key 文件）。 */
    public static final String DEFAULT_API_KEY = "sk-ad02e253684e4fc1b3361e4328d69520";

    // ==================== 通用界面坐标（与工资任务一致） ====================

    private static final int[] PT_BACK_TO_LEGION = {830, 635};
    private static final int[] PT_AD_HOTSPOT_X = {778, 322};
    private static final int[] PATCH_AD_HOTSPOT = {753, 297, 50, 50};
    private static final double TH_AD_RG = 50.0;

    /** 「热点活动 / 游戏活动展示」弹窗标题的 OCR 搜索区与关键词（055152 误报修复）。
     *  色块 (753,297) 只能说明「那块偏红」，秋叶/灯笼等红色景物同样命中 —— 200056（工资）
     *  和 055152（孝廉）两次「广告未关」现场截图里画面上根本没有弹窗，纯色块判据把好局
     *  白白中止。弹窗在时标题栏必有这些字，OCR 认得到才算真的在。搜索区刻意避开
     *  x≥830 的右上角按钮列（那里有「热点」按钮，会把按钮文字误当弹窗标题）。 */
    private static final int[] ZONE_AD_TITLE = {250, 810, 180, 400};
    private static final String[] KW_AD_TITLE = {
        "热点活动", "热点活", "点活动", "热点", "活动展示", "游戏活动", "动展示"};

    /**
     * 右上角「寻路」按钮的候选点（窗口坐标）。
     *
     * <p>「寻路」按钮在右上角那一竖列（热点 / 引导 / 炫装 / 寻路）的最下面，
     * 实测在 1030x797 窗口下是 (806,115)。但这一列按钮的 y 会随上面那排
     * 功能条（跨服 / 数字 / 大厅线路条）的展开状态轻微变化，所以给几个候选点，
     * 依次点一遍，哪个能把面板点开就用哪个。
     */
    private static final int[][] PT_XUNLU_BTNS = {
            {827, 189},   // 地图横幅展开时的位置（最常见）
            {810, 113},   // 地图横幅收起时的位置
    };

    /**
     * OCR 搜索「寻路」按钮字样的区域（窗口坐标）。
     *
     * <p>右上角那一竖列按钮（热点 / 引导 / 炫装 / 寻路）的 y 会随右上角
     * <b>地图横幅展开 / 收起</b> 上下浮动约 74px：横幅展开时「寻路」在 y≈187，
     * 收起时在 y≈113。写死坐标必然点错（曾经点在「炫装」上打开了炫装空间）。
     * 所以优先用 OCR 在这一片区域里找到「寻路」两个字，点到它的中心。
     */
    private static final int XL_BTN_X0 = 746, XL_BTN_X1 = 1028;
    private static final int XL_BTN_Y0 = 86, XL_BTN_Y1 = 244;

    /** 找答题面板右上角红色 X 的搜索区域（窗口坐标）。 */
    private static final int XL_CLOSE_X0 = 600, XL_CLOSE_X1 = 800;
    private static final int XL_CLOSE_Y0 = 168, XL_CLOSE_Y1 = 242;
    /**
     * 「自动寻路」面板里两个坐标框的中心。
     *
     * <p>实测框的垂直范围是 y449~466（中心 457）。之前写 465 落在框的下边缘之外，
     * 点了框但没拿到输入焦点，于是数字全打空 —— 表现就是框里一直显示旧值 14/12。
     * x 取不同面板位置下的交集中心（框1 ≈509、框2 ≈534）。
     */
    private static final int[] PT_NAV_BOX1 = {509, 457};
    private static final int[] PT_NAV_BOX2 = {534, 457};
    /** 最近一次实测的寻路坐标框位置（用于推算面板 X 的预期位置）。 */
    private int[] lastNavBox;
    /**
     * 「移动」按钮中心（绿按钮，实测矩形 x618~676 / y450~471 → 中心 (647,460)）。
     *
     * <p>只是<b>最后兜底</b>：正常情况下先用绿按钮质心实测（见 {@link #findNavMoveBtnCore}），
     * 绿按钮找不到就用 OCR「坐标」标签反推（offset 见 {@link #NAV_MOVE_DX}）。
     */
    private static final int[] PT_NAV_MOVE = {650, 458};
    /**
     * 「移动」按钮的「一行至少多少绿像素」门槛。
     *
     * <p>2026-09-22 实测：按钮本体一行有 23~55 个绿像素（矩形 618~676），
     * 而面板右侧绿色滚动条箭头一行只有 4~17 个 —— 旧实现把两者一起平均，
     * 质心被滚动条往上拉了 13~20px（04_arrived 帧实测 438，真值 460，
     * 那个位置已经在按钮外面了）。按行过滤后再取质心即可。
     */
    private static final int MOVE_BTN_ROW_MIN = 20;
    /** 「移动」按钮的形状闸门：连续按钮行 ≥10 行、横向跨度 40~90px、绿像素 ≥150。 */
    private static final int MOVE_BTN_MIN_ROWS = 10;
    private static final int MOVE_BTN_MIN_W = 40;
    private static final int MOVE_BTN_MAX_W = 90;
    private static final int MOVE_BTN_MIN_PX = 150;
    /** 面板右上角 X 的兜底坐标（正常情况下用红色自动定位）。 */
    private static final int[] PT_NAV_CLOSE = {690, 202};

    // ---------- 「自动寻路」面板的 OCR 判据与反推（2026-09-22 罗城段翻车后新增） ----------

    /**
     * 面板输入行「坐标」标签的搜索条带（窗口坐标）。
     *
     * <p>2026-09-22 02:04 实车：孝廉第二段（罗城 → 11,8）连点三次「寻路」都报
     * 「面板未出现」，但用户看到面板就挂在屏幕上 —— 说明<b>暗框指纹在那一刻没命中</b>
     * （框内亮度/背景随地图变、面板整体漂移都可能造成），而不是面板没开。
     * 所以加一路 OCR 判据：面板输入行「坐标：」标签只在面板里出现，
     * 正负两态各 10 帧离线实测 20/20 干净可分（`_dev/check_label_hits.py`）。
     *
     * <p>条带要给足：面板整体会随地图横幅浮动 ~74px（标签实测 y461，最高到 y≈387）。
     * 上边界取 340 是为了避开面板内部「坐标」表头（y280 附近），
     * 再叠加「取 y 最大的那一行」规则（输入行永远在表头下方），双保险。
     */
    private static final int[] NAV_LABEL_ZONE = {380, 610, 340, 560};
    /** OCR 匹配「坐标」标签时接受的写法（normalize 后的包含判断）。 */
    private static final String[] NAV_LABEL_WORDS = {"坐标", "座标"};
    /**
     * 「坐标」标签左边缘 → 第 1 / 第 2 个输入框中心的偏移（20 帧实测标定）。
     *
     * <p>标定依据：标签行框 x0=448 稳定不漂，框1 中心 503、框2 中心 532、
     * 框心 y=459，标签行中心 y=462 → dx1=+55、dx2=+84、dy=−3。
     * 面板是固定布局窗口，所以标签一动，框跟着一动，反推永远成立。
     */
    private static final int NAV_BOX_DX1 = 55;
    private static final int NAV_BOX_DX2 = 84;
    private static final int NAV_BOX_DY = -3;
    /** 「坐标」标签左边缘 → 「移动」按钮中心的偏移（实测 647−448=199、460−462=−2）。 */
    private static final int NAV_MOVE_DX = 199;
    private static final int NAV_MOVE_DY = -2;

    // ==================== 答题框（固定位置，屏幕正中） ====================

    /**
     * A/B/C/D 四个选项行的固定 y（窗口坐标）。
     *
     * <p>答题框每次都出现在屏幕正中、大小位置完全不变，四个选项等间距排列 ——
     * 所以点选项时<b>直接点固定坐标</b>，比「点 OCR 识别框的中心」稳得多
     * （OCR 的框偶尔会飘、或者整行没认出来）。
     *
     * <p><b>⚠ 2026-10-01 重新标定（血泪）</b>：旧值 {@code {355, 387, 420, 452}}
     * 是早期用肉眼估的，<b>A/C/D 三行都是偏的</b>（A 偏 3px、C 偏 4px、D 偏 7px），
     * 只有 B 恰好在 387 上。这次用「文字亮像素重心」把
     * {@code xiaolian_shots/*quiz_probe_full.png} 里 <b>144 张可用答题帧</b>
     * 逐帧量了一遍，四行的重心是<b>铁打不动的一致</b>（p10 = p90）：
     * <pre>
     *   A = 358   (144 帧，p10=p90=358)
     *   B = 387   (144 帧，p10=p90=387)
     *   C = 416   (144 帧，p10=p90=416)
     *   D = 445   (144 帧，p10=p90=445)
     * </pre>
     *
     * <p><b>旧值闯的祸（第 1 题答错的真根因）</b>：题「与最近的NPC对话的快捷键是什么?」
     * 答案 {@code G}，A 行 OCR 把孤立的 {@code G} 读成 {@code 0回}、其框的中心在
     * {@code y=388}。归行判据 {@link #optionRowIndex} 是「离哪个旧中心最近」——
     * 旧 A 中心 355 与 388 差 <b>33</b>、旧 B 中心 387 与 388 差 <b>1</b> ⇒
     * {@code 0回} 被判成 <b>B 行</b>；真正的 B 行 {@code F2}（中心也是 387）也判成 B 行，
     * 两者在 B 行打架、更长的 {@code 0回} 赢 ⇒ <b>A 行反而空着</b> ⇒
     * 「异类行推断」「缺行推断」都因为「A 行是空的」直接 return null ⇒
     * 答案 {@code G} 认不出 ⇒ 最高分掉到 {@code F2} 的 0.08 ⇒ 转大模型 ⇒ 答错。
     */
    private static final int[] OPT_ROW_Y = {358, 387, 416, 445};
    /** 点选项时兜底用的 x（落在选项文字区域内）。 */
    private static final int OPT_CLICK_X = 300;
    /**
     * 结算弹窗（恭喜你答对了 / 答错了）里「下一题」按钮的固定位置。
     *
     * <p>实测确认：这个按钮<b>每次都出现在同一个位置</b>，中心 (417,343)、
     * 矩形 (390,334)-(444,352)，横跨 545 px，跨多次答题完全一致。
     */
    private static final int[] PT_NEXT_Q = {417, 343};
    /**
     * 【用户 2026-09-28 给定的两个红框】答题面板的「题目框」与「选线框」。
     *
     * <p>数值来自用户亲笔画红框的现场图 {@code 屏幕截图 2026-09-28 185041.png}
     * （红框线机器实测：左右边界 x210..505；题目框上下 y250..338、选线框上下 y344..464），
     * 按 1026x795 → 1030x797 换算到窗口内坐标。
     *
     * <p><b>用户要求</b>：「两个红色框内分别是题目和选线，<b>务必只在这些框内找</b>」。
     * 所以读题干只扫 {@link #QBOX_Q_X0}..{@link #QBOX_Q_Y1} 这个框，读 A/B/C/D 选项
     * 只扫 {@link #QBOX_O_X0}..{@link #QBOX_O_Y1} 那个框 —— 面板上方的「推举孝廉」
     * 标题、题号匾、右侧「倒计时 / 拥有道具数」全都进不了候选池，识别命中率明显提高。
     */
    private static final int QBOX_Q_X0 = 211, QBOX_Q_X1 = 507, QBOX_Q_Y0 = 246, QBOX_Q_Y1 = 341;
    /** 【用户红框】选线框（四个选项 A/B/C/D 都在这里面）。 */
    private static final int QBOX_O_X0 = 211, QBOX_O_X1 = 507, QBOX_O_Y0 = 342, QBOX_O_Y1 = 466;
    /**
     * 「第 N 题」题号匾带（在两个红框<b>上方</b>）。
     *
     * <p>它不属于用户给的两个框，但 {@link #quizVisible()}（＝答题在不在）就靠
     * 「倒计时 / 第 N 题」这两个标志词判断，而「倒计时」在面板右侧（x≈580，早在裁剪区外），
     * 所以题号匾必须留在裁剪区里 —— 它<b>只用于答题在不在的探测</b>，不参与题干/选项候选。
     */
    private static final int QBOX_NO_X0 = 280, QBOX_NO_X1 = 440, QBOX_NO_Y0 = 212, QBOX_NO_Y1 = 246;

    /**
     * 答题读屏的<strong>裁剪包络</strong>（= 题目框 ∪ 选线框 ∪ 题号匾带）。
     *
     * <p>答题弹窗每次出现在固定位置，裁剪到这一块就不再满屏找字，OCR 又快又准。
     * 出框的行在 {@link #readScreenQuiz(String)} 里会被逐行丢掉（{@link #inQuizBoxes}）。
     * 可用 -Dqqsg.xl.region=x0,y0,x1,y1 覆盖（标定时用）。
     */
    private static final int QUIZQ_X0 = 211, QUIZQ_X1 = 507, QUIZQ_Y0 = 212, QUIZQ_Y1 = 466;

    /**
     * <b>对话读屏</b>的抠图区（窗口内坐标）—— NPC 对话框选项（「请交给我吧 / 我暂时没空」、
     * 运送物资的「好的，我马上去」等）都落在这附近。
     *
     * <p>数值与 0928 改动<b>之前</b>的答题框完全一致（x232~490 / y185~470），故意不动：
     * 用户这次只要求改军团和孝廉答题，运送/领奖那些既有流程读哪块就继续读哪块，
     * 免得「顺手优化」把已经在跑的东西改坏。
     */
    private static final int DBOX_X0 = 232, DBOX_X1 = 490, DBOX_Y0 = 185, DBOX_Y1 = 470;
    /** 结算弹窗里「下一题」按钮的搜索框（比实测矩形大一圈，留容错）。 */
    private static final int NQ_X0 = 384, NQ_X1 = 450, NQ_Y0 = 328, NQ_Y1 = 358;
    /** 橙色按钮判定：R 高、R-G 明显、G-B 明显；实测整块约 545 px。 */
    private static final int NQ_MIN_COUNT = 120;

    /**
     * 「快捷购买」弹窗（答题失败时弹出，提示「您可以购买下列道具继续完成操作」）。
     *
     * <p>答题失败 → 游戏会弹这个框让你花钱买「免错锦囊」/「再答一次的机会」。
     * 检测到就必须点右下角的「取消」，否则流程会卡死在这里。
     *
     * <p><b>布局（机器实测，非目测）</b>：弹窗浮在出题面板之上，底部一行两个
     * 橙色圆角按钮 —— 左「确认支付」x 509~589，右<b>「取消」x 631~687</b>，
     * 两者纵向 y 555~576。整块橙像素：「确认支付」约 948 px，「取消」约 689 px。
     *
     * <p><b>判据（在 222 张真实截图上回归：0 误报、0 漏检）</b>：
     * 光看「橙色像素总数」完全不靠谱 —— 背景/聊天区/装饰的零星橙点也能凑够一两百。
     * 真正唯一可靠的指纹是：<b>在该行里恰好出现两个宽约 81 / 57 的密集橙色列块，
     * 且位置分别落在 509~589 与 631~687</b>。别处从没同时满足过。
     */
    private static final int[] PT_BUY_CANCEL = {659, 563};
    /** 按钮行搜索区：横向覆盖两个按钮，纵向只框住按钮本身（避免压进说明文字）。 */
    private static final int BUY_X0 = 480, BUY_X1 = 700, BUY_Y0 = 545, BUY_Y1 = 585;
    /** 「确认支付」按钮的实测 x 范围（左块）。 */
    private static final int BUY_PAY_X0 = 509, BUY_PAY_X1 = 589;
    /** 「取消」按钮的实测 x 范围（右块）—— 这才是我们要点的那块。 */
    private static final int BUY_CX0 = 631, BUY_CX1 = 687;
    /** 判定「某列是按钮实心部分」的橙色像素数下限（按钮高约 21 px）。 */
    private static final int BUY_COL_MIN = 5;
    /** 列块最小宽度：按钮至少 8 px 宽才算数，滤掉文字笔画形成的细碎列。 */
    private static final int BUY_BLOCK_MIN_W = 8;

    /**
     * 运送物资收尾按钮「好的，我马上去」的<b>兜底固定坐标</b>（窗口内 1030x797 基准）。
     *
     * <p><b>实测来源（用户提供的 4 张真实截图，机器测量）</b>：
     * 「好的，我马上去」在选项列表第一行，其蓝色高亮条实测
     * <b>y 251~272、中心 y=261</b>，横向 x 346~610 以上（铺满对话框内宽）。
     * 所以取按钮行左侧区域点击即可（不必点最右，避开「不，我暂时不想参加」）。
     *
     * <p>注意：这里点的是<b>第一行选项</b>（高亮条所在行）—— 同一渲染位置在
     * 图1（对话/任务）、图2（运送物资）、图4（好的，我马上去）里完全一致
     * （高亮条中心 y 都在 262~263），是对话框固定的首行位置。
     *
     * <p>正常路径是 OCR 找到「好的，我马上去」的文字质心；只有 OCR 漏字时才退到这里。
     */
    private static final int[] PT_CONFIRM_OK = {400, 261};
    /**
     * 对话选项列表「首行高亮条」的实测几何（用于校验 / 兜底点击）。
     *
     * <p>实测四张截图：首行高亮条 y 251~272（高约 22），是对话框里最上面那一行选项。
     * 图1/图2/图4 都命中这个位置；图3 是纯 NPC 正文，<b>没有任何高亮条</b>。
     */
    private static final int DLG_ROW1_Y0 = 251, DLG_ROW1_Y1 = 272;
    /**
     * 对话框内容区（窗口内坐标）—— 用于扫描「有没有高亮条」。
     *
     * <p>实测四张截图后确认：<b>高亮条不一定出现在首行</b>。
     * 图1/图4 的菜单从 y≈251 开始；但图2 因为上方多了一句 NPC 问话
     * （「你来找我有什么事吗？」），菜单被推到 <b>y≈330~405</b>。
     * 所以判据改为「在整个内容区里找高亮条」，而不是只看某一行。
     */
    private static final int DLG_SCAN_X0 = 330, DLG_SCAN_X1 = 620;
    private static final int DLG_SCAN_Y0 = 190, DLG_SCAN_Y1 = 470;
    /**
     * 高亮条的判定宽度下限：一行里亮蓝像素超过这个数才算「一条高亮条」。
     *
     * <p><b>阈值为什么是 55</b>：高亮条上压着选项文字，文字笔画会把蓝色抠掉一块，
     * 导致<b>条带中间几行的亮蓝像素骤降到 60~80</b>。实测图2：条是完整的
     * y370~391（22 行），但中间 y373~388 只有 57~77 px —— 阈值取 90 会把
     * 中间切掉，只剩 3 行。降到 55 就能拿到完整 22 行（与图1/图4 一致）。
     */
    private static final int DLG_BAR_MIN_PX = 55;
    /**
     * 高亮条<b>总高度上限</b>（像素）。
     *
     * <p>这是防误判的关键约束。<b>血泪教训</b>：真实画面里天空、UI 边框都是亮蓝色，
     * 不加限制时会测出「高亮条 182 行 (y 210..469)」这种离谱结果，把大片蓝天
     * 当成菜单，然后点到完全错误的位置。
     *
     * <p>实测真实高亮条的高度都是 <b>22 行</b>（图1 = 22、图4 = 22），
     * 图2 因为分辨率换算略小（6 行）。上限取 40 行即可滤掉所有大块蓝色。
     */
    private static final int DLG_BAR_MAX_ROWS = 40;
    /**
     * 高亮条必须<b>上下都被暗色包夹</b>：条上方 {@link #DLG_BAR_LIP} 行、
     * 下方同样行数内，暗色占比要够高。
     *
     * <p>真正的高亮条是「暗底对话框里的一条细带」，上下都是对话框的暗色底。
     * 天空那种蓝色不会上下都贴着暗色 —— 这条约束能进一步杜绝误判。
     */
    private static final int DLG_BAR_LIP = 6;
    private static final double DLG_BAR_LIP_DARK_FRAC = 0.45;
    /**
     * 没有高亮条时，内容区暗色占比超过这个值 → NPC 正文（图3）。
     *
     * <p>图3 是纯文字正文，对话框底色大片暗色，且没有任何高亮条（实测 0.54）。
     */
    private static final double DLG_DARK_MIN_FRAC = 0.35;
    /** 菜单画面点首行时用的 x（避开文字居中位置，点左侧空白区同样能选中整行）。 */
    private static final int DLG_ROW1_CLICK_X = 400;
    /** 菜单画面点首行时用的 y（实测首行高亮条中心 261）。 */
    private static final int DLG_ROW1_CY = 261;

    /**
     * 上一次 {@link #detectDialogState()} 测到的<b>高亮条纵向中心</b>（窗口坐标）。
     *
     * <p>因为高亮条不一定在首行（图2 就被 NPC 问句推到了 y≈380），
     * 所以点击时用这个实测值，而不是硬编码 {@link #DLG_ROW1_CY}。
     */
    private int lastBarCy = DLG_ROW1_CY;
    /**
     * 图3「NPC 正文」画面要点击的位置（对话框正文中部）。
     *
     * <p>实测该画面对话框大约 y 200~352，取中部偏上、避开文字与底部箭头的位置。
     */
    private static final int DLG_BODY_CLICK_X = 430, DLG_BODY_CLICK_Y = 300;
    /**
     * 读屏前把鼠标停到这里（窗口标题栏），避免鼠标压在某个选项上把那一行高亮 ——
     * 高亮行的文字 OCR 认不出来。
     */
    private static final int[] PT_MOUSE_PARK = {515, 14};

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

    /**
     * 倒计时数字的区域：这块里的「37 / 40」等数字是倒计时读数，不是选项，直接丢弃。
     * 实测倒计时数字固定渲染在 (592,340)-(613,351)，这里留一圈余量。
     */
    private static final int CD_X0 = 560, CD_X1 = 690, CD_Y0 = 295, CD_Y1 = 380;
    /**
     * 选项行的横向范围（用于单独抠出每一行做高倍 OCR）。
     * 实测出题面板选项区 x 211~507（用户 0928 红框），选项文字从 A/B/C/D 标记后开始
     * （「三/四/六/五」在 x≈240 起），左留余量不切字，故取 235~500 —— 仍落在红框内。
     */
    private static final int OPT_ROW_X0 = 235, OPT_ROW_X1 = 500;
    /**
     * 选项行裁切带：以行中心为基准上下各取一段。
     *
     * <p><b>2026-10-01 随 {@link #OPT_ROW_Y} 重新标定</b>：旧值 上 30 / 下 14 是配
     * 偏心旧中心（355/387/420/452）凑出来的，换到实测中心（358/387/416/445）后会
     * 把上一行的尾巴切进来。四行行距 29、字高约 10，取「上 12 / 下 12」正好是一行
     * 干净的文字带（上下各留一点余量，不碰邻行）。
     */
    private static final int OPT_BAND_UP = 12, OPT_BAND_DOWN = 12;
    /**
     * 「选项行」单行图专用的放大倍数。
     *
     * <p>选项常常只有 2~3 个字（如「太丑 / 太笨」），Windows OCR 对<b>孤立的短文本</b>很
     * 容易整行丢掉。把这一行单独抠出来、放大 4 倍再识别，命中的概率明显提高。
     */
    private static final int ROW_SCALE = 4;

    private static final int PANEL_X0 = 420, PANEL_X1 = 690, PANEL_Y0 = 240, PANEL_Y1 = 510;
    /**
     * 寻路面板「开着」的判定阈值。
     *
     * <p>实测（后台截图，寻路面板含城名列表）：面板开着时该区域深蓝占比约
     * <b>0.55~0.58</b>，关闭时约 <b>0.12</b>。原来取 0.65 —— 高于开着的实测值，
     * 会导致面板明明开着却被判成「关着」（我曾因此误判后台点击无效）。
     * 取 0.35 落在两态正中间，两边都有 0.2 以上余量。
     */
    private static final double TH_PANEL_FRAC = 0.35;

    private static final int MENU_X0 = 435, MENU_X1 = 635, MENU_Y0 = 325, MENU_Y1 = 565;
    private static final double TH_MENU_FRAC = 0.30;

    // ==================== 本任务的寻路坐标 ====================

    /** 第一段：去 (20,16) 然后按「↑」换图。 */
    private static final String COORD_A_X = "20";
    private static final String COORD_A_Y = "16";
    /** 第二段：换图后走到 NPC 所在处 (11,8)。 */
    private static final String COORD_B_X = "11";
    private static final String COORD_B_Y = "8";

    // ==================== 节奏 ====================

    private static final int STEP_MS = 1800;
    private static final int CLICK_MS = 1400;
    private static final int SHORT_MS = 800;
    private static final int ESC_MS = 1300;
    private static final int TELEPORT_MS = 3500;
    /** 寻路后等角色走过去 */
    private static final int NAV_WALK_MS = 12000;
    /** 到达 (20,16) 后等多久再按「↑」 */
    private static final int UP_DELAY_MS = 2000;
    /** 换图后的加载等待 */
    private static final int MAP_LOAD_MS = 3000;
    private static final int DIALOG_WAIT_MS = 1700;
    private static final int G_MAX_TRIES = 6;

    /** 运送物资：连按回车的节奏（太快游戏吃不到，太慢浪费时间）。 */
    private static final int ENTER_STEP_MS = 1200;
    /** 运送物资：连按回车的次数上限（防止对话一直不关时死循环）。 */
    private static final int ENTER_MAX_TIMES = 20;

    /**
     * 运送物资收尾：每一段回车之间的等待。
     *
     * <p><b>用户实测节奏</b>：G 展开对话、点「关于运送物资」之后，
     * <ol>
     *   <li>先有一个中间对话 —— <b>必须按回车才过得去</b>（不按就卡住不动）；</li>
     *   <li>过了之后进入「好的，我马上去」那一段，<b>再连按三次回车</b>就必定出现。</li>
     * </ol>
     * 间隔给到 1200ms 比 1 秒稍宽一点，防止游戏没吃住上一按。
     */
    private static final int CONFIRM_WAIT_MS = 1200;

    /**
     * 点完「关于运送物资」后，<b>用来跳过中间对话</b>的回车次数。
     *
     * <p>用户实测：这个中间对话<b>必须按回车才能跳过</b>，按一次即可推进；
     * 这里留 2 下余量（每下之间等 {@link #CONFIRM_WAIT_MS}），
     * 因为「这一层到底有几屏」游戏的显示节奏偶尔会差一帧。
     */
    private static final int CONFIRM_SKIP_ENTER_TIMES = 2;

    /**
     * 跳过中间对话之后，<b>连按三次回车</b>把「好的，我马上去」逼出来。
     *
     * <p>用户实测就是 <b>3</b> 下。这是实测出来的确定值，不要随手改成
     * 「多一些更保险」—— 多按会打到后续弹窗上误选选项。
     */
    private static final int CONFIRM_ENTER_TIMES = 3;

    /** 等「好的，我马上去」按钮出现后，点它之前再确认几次（每次间隔 {@link #SHORT_MS}）。 */
    private static final int CONFIRM_FIND_TRIES = 4;

    // ==================== 答题相关 ====================

    /**
     * 答题对话框的抠图区域（窗口内坐标）—— 运行时值，默认 = {@link #QUIZQ_X0} 那个包络。
     *
     * <p><b>实测确认：每次出题都在同一位置，完全固定</b>，所以这里直接用固定区域：
     * 题目框、选线框、题号匾带依次往下。区域比整个弹窗小得多，正好排掉面板标题
     * 「推举孝廉」、右侧「倒计时」「拥有道具数」那些无关元素，OCR 更快更准；
     * 出框的行还会被 {@link #inQuizBoxes} 再滤一道（用户 0928：只在两个红框里找）。
     * 可用 -Dqqsg.xl.region=x0,y0,x1,y1 覆盖（标定时用）。
     */
    private static int QUIZ_X0 = QUIZQ_X0, QUIZ_Y0 = QUIZQ_Y0;
    private static int QUIZ_X1 = QUIZQ_X1, QUIZ_Y1 = QUIZQ_Y1;
    /**
     * 是否用 -Dqqsg.xl.region 覆盖过抠图区域。
     *
     * <p>标定模式下用户在试别的区域，这时候 {@link #inQuizBoxes} 的出框过滤必须让路，
     * 否则手动框出来的区域会被「题目框/选线框」判据整片滤掉，标定无从下手。
     */
    private static boolean regionOverridden = false;
    /**
     * 预处理版本的放大倍数。
     *
     * <p>原图放大 3 倍（游戏里的描边字放大后更容易认），二值化后的版本放大 2 倍就够
     * （二值化本身已经把小字变得很干净），这样三个版本加起来也不会太慢。
     */
    private static final int VAR1_SCALE = 3;
    private static final int VAR2_SCALE = 2;
    /** 单题最多认几次 */
    private static final int QUIZ_MAX_Q = 14;
    /**
     * 题目匹配的最低相似度（2026-09-29 由 0.58 下调到 0.45）。
     *
     * <p><b>为什么下调</b>：题干 OCR 常见错字（实机实读「残阳炙是<b>嘟</b>个<b>軹</b>的技能？」
     * ← 原题「残阳炙是哪个职业的技能?」），短题干错 2 个字相似度就掉到 <b>0.526</b>，
     * 被 0.58 挡在门外 → 题库里明明有正确答案「阴阳士」却判成「低分噪声」转大模型，
     * 大模型再答错。日志实测题库命中率一度只有 7%（1/14），大量题白跑 AI。
     *
     * <p>下调的风险是「张冠李戴到别的题」，所以<b>不能只降阈值</b>：同时要求
     * {@link #TH_GAP} 间隔保护 —— 首选必须明显领先另一道题，否则不采用。
     */
    private static final double TH_QUESTION = 0.45;
    /**
     * 首选必须比「另一道题的最高分」高出这么多才敢用题库（2026-09-29 新增的间隔保护）。
     *
     * <p>只降阈值会让「差不多像的好几道题」都有机会被选中，一旦选错就是答错。
     * 要求冠军与亚军（不同题目）拉开距离，才能既救回擦边球、又不误配。
     * 同一道题的多条记录不算亚军（那本就是同一个答案组，见 {@code Match#sameQuestion}）。
     */
    private static final double TH_GAP = 0.08;
    /** 答案与选项匹配的最低相似度 */
    private static final double TH_OPTION = 0.40;
    /** 认为「题目还没变」的相似度（用来判断点击有没有生效） */
    private static final double TH_SAME = 0.72;
    /**
     * 题库「差不多是这题」的相似度 —— 把这个分以上的题库条目当作<b>参考</b>喂给大模型。
     *
     * <p>注意与 {@link #TH_QUESTION} 的分工：{@code TH_QUESTION} 管「要不要直接采信题库答案」，
     * 本常量管「要不要把题库条目当提示一起给模型」。0929 起阈值下调到 0.45 后两者同值，
     * 但语义仍不同 —— 前者还要过 {@link #TH_GAP} 间隔保护，后者只看分数。
     * OCR 有错字时，模型光靠错字很容易答偏，给个八九不离十的参考会稳很多。
     */
    private static final double TH_HINT = 0.45;
    /** 一行的字数超过它就基本不可能是选项（选项都很短） */
    private static final int OPT_MAX_CHARS = 26;
    /** 选项最多取几个送进大模型 */
    private static final int OPT_MAX_N = 6;

    /** 左下角聊天区的排除范围：x 小于它、y 大于它 → 判定为聊天区，识别结果丢弃。 */
    private static final int CHAT_X1 = 470, CHAT_Y0 = 592;
    /** 点完选项后等界面反应（倒计时是全局的，这里要尽量短） */
    private static final int AFTER_PICK_MS = 650;
    /** 进入下一题后的等待 */
    private static final int NEXT_Q_WAIT_MS = 850;
    /** 单次 OCR 的超时 */
    private static final int OCR_TIMEOUT_S = 15;

    // ==================== 运行状态 ====================

    private final GameWindowController controller;
    private final Listener listener;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;

    /** 演练模式：只识别并提示，不真的点选项（用于标定，不消耗答题机会的判断力）。 */
    private final boolean dryRun;
    /** 是否在开始时按 F11 屏蔽其他玩家 */
    private final boolean hidePlayers;
    /** API Key */
    private final String apiKey;
    /** 数据库 / 截图目录（按任务分目录，方便事后排查） */
    private final File shotDir;

    /**
     * 「仅导航」模式：只跑到 (11,8) 就停，不按 G、不接任务。
     *
     * <p>存在的理由：回城 / 寻路 / 按「↑」换图这一段是纯手感的操作、最难调，
     * 一旦接取任务就开始 15 秒倒计时、失败还要等第二天。所以先用这个模式把导航
     * 单独跑通（零成本），再跑正式流程。
     */
    private volatile boolean navOnly = false;

    private boolean playersHidden = false;
    /** 上一次读到的题干文字（用于判断是否翻页） */
    private String lastQuestion = "";

    /** 当前跑的是哪个任务（孝廉 / 运送物资）。默认孝廉，保持旧行为不变。 */
    private final Mode mode;

    public XiaolianTask(GameWindowController controller, Listener listener) {
        this(controller, listener, false, true, resolveApiKey());
    }

    public XiaolianTask(GameWindowController controller, Listener listener,
                        boolean dryRun, boolean hidePlayers, String apiKey) {
        this(controller, listener, dryRun, hidePlayers, apiKey, Mode.XIAOLIAN);
    }

    public XiaolianTask(GameWindowController controller, Listener listener,
                        boolean dryRun, boolean hidePlayers, String apiKey, Mode mode) {
        this.controller = controller;
        this.listener = listener;
        this.dryRun = dryRun;
        this.hidePlayers = hidePlayers;
        this.apiKey = apiKey;
        this.mode = mode == null ? Mode.XIAOLIAN : mode;
        this.shotDir = new File(this.mode == Mode.YUNSONG ? "yunsong_shots" : "xiaolian_shots");
        readRegionOverride();
    }

    /** 当前任务模式。 */
    public Mode getMode() {
        return mode;
    }

    /** API Key 解析顺序：系统属性 > 工作目录 deepseek.key 文件 > 内置默认值。 */
    public static String resolveApiKey() {
        String k = System.getProperty("qqsg.deepseek.key");
        if (k != null && !k.trim().isEmpty()) {
            return k.trim();
        }
        try {
            File f = new File("deepseek.key");
            if (f.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (Throwable ignore) {
            // 读不到就用默认
        }
        return DEFAULT_API_KEY;
    }

    /** 允许用 -Dqqsg.xl.region=x0,y0,x1,y1 临时改抠图区域，方便标定。 */
    private static void readRegionOverride() {
        String s = System.getProperty("qqsg.xl.region");
        if (s == null || s.trim().isEmpty()) {
            return;
        }
        String[] p = s.split(",");
        if (p.length != 4) {
            return;
        }
        try {
            QUIZ_X0 = Integer.parseInt(p[0].trim());
            QUIZ_Y0 = Integer.parseInt(p[1].trim());
            QUIZ_X1 = Integer.parseInt(p[2].trim());
            QUIZ_Y1 = Integer.parseInt(p[3].trim());
            regionOverridden = true;   // 标定模式：出框过滤让路，见字段注释
        } catch (Throwable ignore) {
            // 解析失败就用默认
        }
    }

    /** 当前抠图区域（供调试命令打印）。 */
    public static String regionInfo() {
        return QUIZ_X0 + "," + QUIZ_Y0 + "," + QUIZ_X1 + "," + QUIZ_Y1;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    /** 打开「仅导航」模式（见 {@link #navOnly}）。必须在 {@link #start()} / {@link #runBlocking()} 之前调用。 */
    public void setNavOnly(boolean v) {
        this.navOnly = v;
    }

    /**
     * 同步执行整条流程（供命令行探针使用）。
     *
     * <p>与 {@link #start()} 的区别：不另起线程，跑完才返回，异常就地兜住，
     * 这样探针可以把日志直接打到控制台、跑完就退出。
     */
    public void runBlocking() {
        if (running) {
            listener.log("孝廉任务已在运行中");
            return;
        }
        running = true;
        stopRequested = false;
        try {
            execute();
        } catch (Abort a) {
            listener.log("⛔ 中止：" + a.getMessage());
        } catch (Throwable e) {
            e.printStackTrace();
            listener.log("⛔ 执行异常：" + e);
        } finally {
            running = false;
        }
    }

    /**
     * 供探针使用：题目框已经开着时，只跑答题循环 + 收尾，返回结果摘要。
     *
     * <p>用于「中途接手」——导航和接任务已经手工/上一步做完了，直接开始答题。
     */
    public String quizAndFinish() {
        if (running) {
            return "孝廉任务已在运行中";
        }
        running = true;
        stopRequested = false;
        int n = 0;
        try {
            controller.focusWindow();
            sleep(SHORT_MS);
            n = quizLoop();
            if (!dryRun) {
                finishDialogs();
            }
            return "已作答 " + n + " 题";
        } catch (Abort a) {
            return "中止：" + a.getMessage() + "（已作答 " + n + " 题）";
        } catch (Throwable e) {
            e.printStackTrace();
            return "异常：" + e;
        } finally {
            running = false;
        }
    }

    public void requestStop() {
        stopRequested = true;
    }

    public void start() {
        if (running) {
            listener.log(mode.label() + "任务已在运行中");
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
        }, "XiaolianTask-" + mode);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步执行一次完整任务（供 {@link #start()} 与「一键日常」序列共用）。
     *
     * <p><b>后台模式作用域也在这里</b>：进入时记下用户原来的后台开关并强制开启
     * （截图走 PrintWindow、点击/按键走窗口消息，全程不抢焦点、不动真实鼠标），
     * 退出时原样还原 —— 组队任务同款做法。
     */
    public TaskOutcome runOnce() {
        if (running) {
            return new TaskOutcome(false, mode.label() + "任务已在运行中");
        }
        running = true;
        stopRequested = false;
        boolean savedBg = controller.isRunInBackground();
        try {
            controller.setRunInBackground(true);
            listener.log(mode.label() + "任务以「后台模式」运行（不抢焦点、不移动鼠标）");
            execute();
            return new TaskOutcome(true, "已完成" + mode.label() + "流程");
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
        listener.log("================ " + mode.label() + " 开始 ================");

        Rectangle r = controller.getWindowRect();
        if (r == null) {
            throw new Abort("找不到游戏窗口，请先启动 QQ三国 并确认窗口未关闭");
        }
        listener.log("游戏窗口：" + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");
        controller.ensureWindowVisible(); // 窗口被最小化时截图/坐标全失效（160x28 @ -32000），先还原
        if (mode == Mode.XIAOLIAN) {
            listener.log("抠图区域（窗口坐标）：" + regionInfo()
                    + "，预处理 " + variantInfo());

            if (!XiaolianBank.loaded()) {
                throw new Abort("本地题库没加载成功：" + XiaolianBank.loadError());
            }
            listener.log("本地题库：" + XiaolianBank.size() + " 条");
        }
        if (!OcrLite.available()) {
            throw new Abort("OCR 不可用：" + OcrLite.lastError());
        }
        listener.log("OCR 就绪（Windows 自带 zh-Hans-CN 引擎）");
        if (dryRun) {
            listener.log("⚠ 演练模式：只识别并提示，不会真的点选项");
        }

        int[] savedCursor = controller.getCursorPosition();
        try {
            // 后台模式不抢焦点（校验前台状态的日志也没意义，一并跳过）
            if (controller.isRunInBackground()) {
                listener.log("后台模式：不抢焦点，直接给游戏窗口发消息");
            } else {
                controller.focusWindow();
                sleep(SHORT_MS);
                listener.log("已把游戏窗口切到前台（校验=" + controller.isForegroundGameWindow() + "）");
            }

            // ---------- 0. 清理 ----------
            if (hidePlayers) {
                hideOtherPlayers();
            }
            closeAds();

            // ---------- 1. 到 NPC 面前 ----------
            //  2026-09-23 应用户要求：<b>孝廉按钮的寻路段整体删除</b> —— 用户自己把角色走到
            //  成都「诰令司丞」面前再点按钮，脚本只负责「G 之后的对话 + 接任务 + 答题 + 交任务」。
            //  运送物资（YUNSONG）走的是同一个 NPC，暂时仍保留原来的回城 → 寻路 → 换图 → 寻路。
            if (mode == Mode.XIAOLIAN) {
                listener.log("—— 简化模式：已假定角色站在成都「诰令司丞」面前，"
                        + "跳过 回城 / 寻路(20,16) / ↑ 换图 / 寻路(11,8) ——");
            } else {
                navigateToNpc();
            }

            if (navOnly) {
                listener.log("✔ 仅导航模式：导航段已跑完，未接任务（不消耗今日机会）");
                return;
            }

            // ---------- 2. 接任务（G 唤起对话之后的所有逻辑全部保留） ----------
            acceptQuest();

            // ---------- 3. 接完任务后的处理：两个任务从这里开始分道扬镳 ----------
            if (mode == Mode.YUNSONG) {
                // 运送物资：接完还要<b>再对话一轮</b>（G →「对话/任务」→「运送物资」），
                //   然后一直按回车把对话推完，才算真正完成。
                finishYunsongQuest();
                String p = snapshot("99_done");
                listener.log("✔ 运送物资全流程执行完毕" + (p != null ? "（截图：" + p + "）" : ""));
                return;
            }

            // ---- 孝廉：答题 + 收尾交任务 ----
            int answered = quizLoop();

            if (!dryRun) {
                finishDialogs();
            }
            String p = snapshot("99_done");
            listener.log("✔ 孝廉全流程执行完毕，共作答 " + answered + " 题，任务已交付"
                    + (p != null ? "（截图：" + p + "）" : ""));
        } finally {
            restoreOtherPlayers();
            // 后台模式不动真实鼠标（用户可能正在用电脑）
            if (!controller.isRunInBackground() && savedCursor != null) {
                controller.moveCursor(savedCursor[0], savedCursor[1]);
            }
        }
    }

    // ==================== 导航段（寻路）====================

    /**
     * 把角色从任意位置送到成都「诰令司丞」面前：
     * <b>按 O 回军团 → T 回城 → 寻路 (20,16) → 按「↑」换图 → 寻路 (11,8)</b>。
     *
     * <p>2026-09-23 改动：<b>孝廉按钮已不再调用本方法</b> —— 用户自己走到 NPC 面前点按钮，
     * 脚本从「按 G 唤起对话」那一步开始接手。目前只有「运送物资」按钮还走这段寻路。
     * 之所以保留成独立方法而不是删掉：一是运送物资还在用，二是这段手感操作调试代价高，
     * 留着以后想恢复一键全自动时直接接回来即可。
     */
    private void navigateToNpc() {
        // 1. 回城到子城
        backToZicheng();

        // 2. 寻路 20/16
        navTo(COORD_A_X, COORD_A_Y);

        // 3. 按「↑」换图
        goUpToNpcMap();

        // 4. 寻路 11/8（已在 NPC 地图里 → 不许传送回城重试）
        navTo(COORD_B_X, COORD_B_Y, false);
    }

    // ==================== 运送物资：收尾 ====================

    // ==================== 运送物资：菜单/正文关键词（2026-09-22 实测标定） ====================

    /**
     * 运送物资菜单/正文的关键词表（按用户 2026-09-22 给的 9 张实机截图重做）。
     *
     * <p>全部用「整窗 ×2 + Windows OCR」离线实测过（{@code _dev/sim_screentext.py}，
     * 数据源 {@code _dev/ysfull_box.txt}）：归一化后<b>包含</b>即命中，个别关键词靠
     * 相似度兜 OCR 的固定误读。括号里是实测的 OCR 原样输出 —— 这些误读直接决定了
     * 关键词怎么取：
     * <ul>
     *   <li>{@code YS_KW_TALK}「对话/任务」（OCR 读作「对话/任务」，去掉斜杠＝「对话任务」）；</li>
     *   <li>{@code YS_KW_TRANSPORT}「运送物资」（OCR 读作「！运送物资」）；</li>
     *   <li>{@code YS_KW_ACCEPT}「请交给我吧」（OCR 只读出前三个字「请交给」，
     *       所以主关键词就取「请交给」，整句放前面只为万一识别全时更精确）；</li>
     *   <li>{@code YS_KW_GO}「好的，我马上去」（OCR 把「去」认成「前宏」
     *       →「好的，我马上前宏」，所以主关键词取「好的我马上」而不是整句）；</li>
     *   <li>{@code YS_KW_FINAL}「好的，请给我吧」（OCR 把「给」认成「蛤」
     *       →「好的，请蛤我吧」，所以主关键词取「好的请」）。</li>
     * </ul>
     * 反例实测：三屏长正文（无高亮条）里这一整组关键词<b>全部落空</b>，不会误点。
     */
    private static final String[] YS_KW_TALK = {"对话任务", "对话与任务", "对话"};
    private static final String[] YS_KW_TRANSPORT = {"运送物资", "关于运送", "运送"};
    private static final String[] YS_KW_ACCEPT = {"请交给我吧", "请交给"};
    private static final String[] YS_KW_GO = {"好的我马上去", "好的我马上", "我马上去"};
    private static final String[] YS_KW_FINAL = {"好的请给我吧", "好的请给", "请给我吧", "好的请"};
    /** 收尾对话中途可能重新冒出来的菜单（自愈用）。 */
    private static final String[] YS_KW_MID = {"运送物资", "请交给"};

    /**
     * 对话框 NPC 标题「成都诰令司书丞：」的特征词 + 专属搜索区。
     *
     * <p>只认 {@code {300,700,225,290}} 这条<b>窄带</b>（实测三屏正文的标题都在
     * y240~262）：再往下就是场景 NPC 头顶的浮动名、右下角「任务追踪」面板
     * （那里也有「…书丞」字样），宽 zone 会误判成「对话框还开着」。
     */
    private static final String[] YS_KW_DLG_TITLE = {"书丞", "诰令司"};
    private static final int[] YS_ZONE_DLG_TITLE = {300, 700, 225, 290};
    /** 点正文时从标题中心往下偏多少像素（实测正文第一行就在标题下方约 42px）。 */
    private static final int YS_BODY_DY = 42;
    /** 每个选项最多尝试几轮（OCR 失败退蓝条，再失败重来或按 G 重开对话）。 */
    private static final int YS_OPTION_TRIES = 4;
    /** 收尾对话推进的总轮数上限。 */
    private static final int YS_ADVANCE_ROUNDS = 14;

    /**
     * 运送物资收尾：<b>按 G 唤起第二轮对话，一路推到「好的，请给我吧」</b>。
     *
     * <p><b>完整流程（用户 2026-09-22 的 9 张实机截图逐步确认）</b>：
     * <ol>
     *   <li>按 G 唤起对话（对话框还开着就不用按）；</li>
     *   <li>第一层菜单 → 点「对话/任务」；</li>
     *   <li>NPC 长正文（无高亮条）→ 点正文（点完没反应才补回车）；</li>
     *   <li>菜单 → 点「好的，我马上去」；</li>
     *   <li>长正文 → 点正文；</li>
     *   <li>长正文 → 点正文；</li>
     *   <li>菜单 → 点「好的，请给我吧」→ 对话框关闭 ＝ 任务完成。</li>
     * </ol>
     *
     * <p><b>为什么每步都「先看再动」</b>：实测这几屏的高亮条位置在
     * <b>y=290 / y=406 / y=404</b> 三处跳（对话框大小随选项条数变），写死坐标必错 ——
     * 这正是旧版实车点偏、卡住的根因。所以每轮先 OCR 认画面：认出哪个关键词就点哪个；
     * 认不出但有高亮条就点高亮条本身（{@link SalaryTask.Ui#option1X}/{@code option1Y}）；
     * 两者都没有（长正文）才去点正文。
     */
    private void finishYunsongQuest() {
        listener.log("—— 收尾：再对话一轮，把运送物资推完 ——");

        if (dialogOpenNow()) {
            listener.log("  ✔ 对话框还开着，直接进入第二轮（不用再按 G）");
        } else if (pressGUntilDialog("运送物资（收尾）", G_MAX_TRIES)) {
            listener.log("  ✔ 已按 G 唤起第二轮对话");
        } else {
            listener.log("  ⚠ 按了 " + G_MAX_TRIES + " 次 G 都没唤起对话，可能任务已经自动完成了");
            snapshot("finish_no_dialog");
            return;
        }
        sleep(STEP_MS);

        // 第二轮的第一层：还是「对话/任务」
        clickMenuOption(YS_KW_TALK, "对话/任务");

        // 之后是「长正文 → 好的我马上去 → 长正文 → 长正文 → 好的请给我吧」
        advanceYunsongDialogs();

        listener.log("  ✔ 运送物资收尾完成");
    }

    /**
     * 点一个菜单选项：<b>先 OCR 按文字点</b>（本项目铁律），认不出才退「点高亮条本身」。
     *
     * <p>点完的验收标准 ＝ <b>这个关键词从画面上消失了</b>。
     * 不能沿用「蓝条消失 ＝ 点中」这个守卫：菜单换菜单时（对话/任务 → 运送物资）
     * 蓝条一直在，那个守卫区分不了「点中了」和「根本没点动」。
     *
     * @param keywords 候选关键词（按优先级排列；长词在前）
     * @param what     日志里显示的名字
     */
    private void clickMenuOption(String[] keywords, String what) {
        for (int round = 1; round <= YS_OPTION_TRIES; round++) {
            checkStop();
            BufferedImage img = controller.captureWindow();

            int[] pt = ScreenText.find(img, keywords, ScreenText.ZONE_CENTRAL, 0);
            if (pt != null) {
                listener.log("  OCR 找到「" + what + "」@ (" + pt[0] + "," + pt[1] + ")，点击");
                controller.clickWindowPoint(pt[0], pt[1]);
            } else {
                SalaryTask.Ui ui = SalaryTask.scanImage(img);
                if (!ui.dialogOpen) {
                    listener.log("  第 " + round + "/" + YS_OPTION_TRIES + " 轮：没认出「" + what
                            + "」，画面上也没有菜单 → 按 G 重新唤起对话");
                    controller.sendKey(WindowUtils.VK_G);
                    sleep(DIALOG_WAIT_MS);
                    continue;
                }
                listener.log("  OCR 没认出「" + what + "」，退而点高亮条本身 ("
                        + ui.option1X + "," + ui.option1Y + ")");
                controller.clickWindowPoint(ui.option1X, ui.option1Y);
            }
            sleep(STEP_MS);

            if (optionGone(keywords)) {
                listener.log("  ✔ 「" + what + "」已从画面消失 —— 点中了");
                return;
            }
            listener.log("  ⚠ 「" + what + "」还在画面上，重新找一次（第 "
                    + round + "/" + YS_OPTION_TRIES + " 轮）");
        }
        String shot = snapshot("anomaly_yunsong_option");
        abort("点不动运送物资的对话选项",
                "连续 " + YS_OPTION_TRIES + " 轮都没能把「" + what + "」点掉。\n\n"
                        + "可能对话框布局和标定时不一致，或任务/活动状态不对。\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "看一眼截图里当前对话框，手动处理后再重跑。");
    }

    /** 画面上还认不认得出这组关键词里的任意一个（认不出＝已翻屏）。 */
    private boolean optionGone(String[] keywords) {
        try {
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                return true;
            }
            return ScreenText.find(img, keywords, ScreenText.ZONE_CENTRAL, 0) == null;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 运送物资收尾对话推进 —— <b>观察驱动状态机，先看再动</b>。
     *
     * <p>每轮按优先级判断：
     * <ol>
     *   <li>认出「好的，请给我吧」→ 点它；点完对话框关掉就是<b>完成</b>；</li>
     *   <li>认出「好的，我马上去」→ 点它；</li>
     *   <li>认出「运送物资 / 请交给我吧」→ 点它（对话被误关重开时自愈）；</li>
     *   <li>有高亮条但没认出字 → 点高亮条本身；</li>
     *   <li>没有高亮条（长正文）→ 点正文；<b>点完画面纹丝不动才补按回车</b>；</li>
     *   <li>连对话框都没有 → 按 G 重新唤起；连续几轮都没有则判定已完成。</li>
     * </ol>
     * 全程有轮数上限（{@value #YS_ADVANCE_ROUNDS}），不会死循环。
     */
    private void advanceYunsongDialogs() {
        listener.log("  —— 收尾对话：逐屏判断画面并推进 ——");
        for (int round = 1; round <= YS_ADVANCE_ROUNDS; round++) {
            checkStop();
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                sleep(SHORT_MS);
                continue;
            }

            // ① 终局：「好的，请给我吧」
            int[] fin = ScreenText.find(img, YS_KW_FINAL, ScreenText.ZONE_CENTRAL, 0);
            if (fin != null) {
                listener.log("  认到「好的，请给我吧」@ (" + fin[0] + "," + fin[1] + ")，点击");
                controller.clickWindowPoint(fin[0], fin[1]);
                sleep(CLICK_MS);
                if (!dialogStillOpen()) {
                    listener.log("  ✔ 对话框已关闭 —— 运送物资任务完成");
                    snapshot("07_yunsong_done");
                    return;
                }
                listener.log("  ⚠ 点完「好的，请给我吧」对话框还在，再推一轮");
                continue;
            }

            // ② 中途菜单选项：「好的，我马上去」
            int[] go = ScreenText.find(img, YS_KW_GO, ScreenText.ZONE_CENTRAL, 0);
            if (go != null) {
                listener.log("  认到「好的，我马上去」@ (" + go[0] + "," + go[1] + ")，点击");
                controller.clickWindowPoint(go[0], go[1]);
                sleep(STEP_MS);
                continue;
            }
            // ③ 对话被误关重开时，中途菜单会重新冒出来 → 补点
            int[] mid = ScreenText.find(img, YS_KW_MID, ScreenText.ZONE_CENTRAL, 0);
            if (mid != null) {
                listener.log("  认到中途菜单「运送物资 / 请交给我吧」@ (" + mid[0] + "," + mid[1]
                        + ")，补点一下");
                controller.clickWindowPoint(mid[0], mid[1]);
                sleep(STEP_MS);
                continue;
            }

            // ④ 有高亮条但 OCR 没认出字 → 点高亮条本身
            SalaryTask.Ui ui = SalaryTask.scanImage(img);
            if (ui.dialogOpen) {
                listener.log("  有高亮条但没认出选项文字，点高亮条本身 ("
                        + ui.option1X + "," + ui.option1Y + ")（第 " + round + " 轮）");
                controller.clickWindowPoint(ui.option1X, ui.option1Y);
                sleep(STEP_MS);
                continue;
            }

            // ⑤ 没有高亮条：要么是长正文，要么对话框已经没了
            int[] title = ScreenText.find(img, YS_KW_DLG_TITLE, YS_ZONE_DLG_TITLE, 0);
            if (title == null) {
                if (round >= 3) {
                    listener.log("  ✔ 画面上已经没有对话框了 —— 判定运送物资流程已结束");
                    snapshot("07_yunsong_done");
                    return;
                }
                listener.log("  第 " + round + " 轮：画面上没有对话框，按 G 重新唤起");
                controller.sendKey(WindowUtils.VK_G);
                sleep(DIALOG_WAIT_MS);
                continue;
            }

            // ⑥ 长正文：点正文；点完画面纹丝不动才补一次回车（用户实测：点它或回车都行）
            long before = dialogBodySignature(img);
            int bx = title[0];
            int by = title[1] + YS_BODY_DY;
            listener.log("  长正文 → 点正文 (" + bx + "," + by + ")（第 " + round + " 轮）");
            controller.clickWindowPoint(bx, by);
            sleep(CONFIRM_WAIT_MS);
            if (before == dialogBodySignature(controller.captureWindow())) {
                listener.log("    点完画面没变，补按一次回车");
                controller.sendKey(WindowUtils.VK_RETURN);
                sleep(CONFIRM_WAIT_MS);
            }
        }
        String shot = snapshot("anomaly_yunsong_advance");
        abort("运送物资收尾对话推不动",
                "推了 " + YS_ADVANCE_ROUNDS + " 轮都没走到「好的，请给我吧」。\n\n"
                        + "常见原因：今天已经运送过（每天 2 次）、不在活动时段（12:00~23:30）、"
                        + "或对话中途被手动点过。\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "看一眼截图再决定是否重跑。");
    }

    /**
     * 对话框还开着吗：有高亮条，或者还认得出 NPC 标题「…书丞」。
     *
     * <p>不能只看高亮条 —— 长正文那一屏同样没有高亮条，但对话框是开着的。
     */
    private boolean dialogStillOpen() {
        try {
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                return false;
            }
            if (SalaryTask.scanImage(img).dialogOpen) {
                return true;
            }
            return ScreenText.find(img, YS_KW_DLG_TITLE, YS_ZONE_DLG_TITLE, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 对话框正文区的像素指纹 —— 判断「点了一下画面到底变没变」。
     *
     * <p>取窗口坐标 x360~670、y255~305：实测三屏长正文的文字行都在
     * y259~311 / x341~695 之间，这个取样框<b>完全落在对话框内部</b> ——
     * 一旦越到框外（比如 y&gt;330）就会把场景里的动画/飘字算进来，
     * 指纹永远在变，「点完没反应」就永远判不出来。
     *
     * <p>每 2 像素取样并按 8 级量化后求和，避免抖动造成的假差异。
     */
    private long dialogBodySignature(BufferedImage img) {
        if (img == null) {
            return 0L;
        }
        try {
            double kx = img.getWidth() / (double) BASE_W;
            double ky = img.getHeight() / (double) BASE_H;
            int x0 = clamp((int) Math.round(360 * kx), 0, img.getWidth() - 1);
            int x1 = clamp((int) Math.round(670 * kx), x0 + 1, img.getWidth());
            int y0 = clamp((int) Math.round(255 * ky), 0, img.getHeight() - 1);
            int y1 = clamp((int) Math.round(305 * ky), y0 + 1, img.getHeight());
            long sum = 17L;
            for (int y = y0; y < y1; y += 2) {
                for (int x = x0; x < x1; x += 2) {
                    int p = img.getRGB(x, y);
                    int v = (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 24;
                    sum = sum * 131 + v;
                }
            }
            return sum;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 「好的，我马上去」这类收尾确认按钮的关键词（归一化后包含即命中）。 */
    private static final String[] CONFIRM_KEYWORDS = {
            "好的我马上去", "我马上去", "马上就去", "这就去", "好的我这就去", "好的"
    };

    /**
     * 收尾对话推进。用户的 4 张实测截图定义了这条链路上会依次遇到的 <b>4 种画面</b>：
     *
     * <table>
     *   <tr><th>图</th><th>画面</th><th>过关方式</th></tr>
     *   <tr><td>1</td><td>第一层菜单：对话/任务 · 兑换声望 · 行脚的委托 · 取消</td>
     *       <td>点「对话/任务」（首行高亮）</td></tr>
     *   <tr><td>2</td><td>第二层菜单：？运送物资 · ！赤兔追风化元神 · ！获取精魄</td>
     *       <td>点「运送物资」（首行高亮）</td></tr>
     *   <tr><td>3</td><td><b>NPC 长正文</b>，底部一个白色「点击继续」箭头，<b>没有任何高亮条</b></td>
     *       <td><b>必须先点击对话框正文，再按回车</b> —— 只按回车过不去！</td></tr>
     *   <tr><td>4</td><td>两个选项：「好的，我马上去」 / 「不，我暂时不想参加」</td>
     *       <td>点首行「好的，我马上去」</td></tr>
     * </table>
     *
     * <p>所以本方法的实现是：<b>逐屏判断当前是哪一种画面，再用对应方式过关</b>，
     * 而不是盲目连按回车 —— 图3 那种画面按键是无效的，必须补一次点击。
     *
     * <p>每种画面最多重试 {@link #CONFIRM_FIND_TRIES} 轮，全程有上限，不会死循环。
     */
    private void pressEnterThenConfirm() {
        listener.log("  —— 收尾对话：逐屏判断画面类型并过关 ——");
        int enterCount = 0;
        int guardMax = CONFIRM_SKIP_ENTER_TIMES + CONFIRM_ENTER_TIMES + CONFIRM_FIND_TRIES + 4;

        for (int step = 1; step <= guardMax; step++) {
            checkStop();

            // ① 已经是「好的，我马上去」那一屏 → 点掉它，收工
            if (confirmButtonPresent()) {
                if (clickConfirmButton()) {
                    listener.log("  ✔ 已点掉「好的，我马上去」，运送物资完成");
                    sleep(CLICK_MS);
                    snapshot("07_yunsong_done");
                    return;
                }
                listener.log("  ⚠ 认出了收尾按钮但点不动，用固定坐标兜底 "
                        + "(" + PT_CONFIRM_OK[0] + "," + PT_CONFIRM_OK[1] + ")");
                snapshot("anomaly_confirm_button_missing");
                controller.clickWindowPoint(PT_CONFIRM_OK[0], PT_CONFIRM_OK[1]);
                sleep(CLICK_MS);
                snapshot("07_yunsong_done_fallback");
                return;
            }

            DialogState st = detectDialogState();

            // ② 菜单类画面（图1/图2）→ 点高亮条那一行
            if (st == DialogState.MENU) {
                listener.log("  画面=菜单（有高亮条），点 (" + DLG_ROW1_CLICK_X + ","
                        + lastBarCy + ")");
                controller.clickWindowPoint(DLG_ROW1_CLICK_X, lastBarCy);
                sleep(STEP_MS);
                continue;
            }

            // ③ NPC 长正文（图3）：必须先点对话框正文，再按回车
            if (st == DialogState.NPC_TEXT) {
                listener.log("  画面=NPC 正文（无高亮条）→ 先点击对话框正文，再按回车");
                controller.clickWindowPoint(DLG_BODY_CLICK_X, DLG_BODY_CLICK_Y);
                sleep(SHORT_MS);
                controller.sendKey(WindowUtils.VK_RETURN);
                enterCount++;
                sleep(CONFIRM_WAIT_MS);
                continue;
            }

            // ④ 没识别出对话框 → 按回车推进（这是实测「连按三次」的落地处）
            if (st == DialogState.NONE) {
                if (!dialogOpenNow()) {
                    listener.log("  ⚠ 对话框已关闭，但没走到「好的，我马上去」那一屏，先停下");
                    snapshot("anomaly_no_confirm_button");
                    return;
                }
                enterCount++;
                listener.log("  回车推进对话（第 " + enterCount + " 次）");
                controller.sendKey(WindowUtils.VK_RETURN);
                sleep(CONFIRM_WAIT_MS);
            }
        }

        listener.log("  ⚠ 收尾对话重试到上限仍未出现「好的，我马上去」，先停下");
        snapshot("anomaly_confirm_button_missing");
    }

    /** 当前对话框画面的类型（用户 4 张实测截图对应的状态）。 */
    private enum DialogState {
        /** 有选项菜单、首行高亮（图1/图2/图4 类）。 */
        MENU,
        /** NPC 长正文，无高亮条、底部「点击继续」箭头（图3）。 */
        NPC_TEXT,
        /** 判断不出来。 */
        NONE
    }

    /**
     * 判断当前对话框属于哪种画面，并记录<b>高亮条的纵向中心</b>（供点击用）。
     *
     * <p>判据来自 4 张实测截图的机器测量，核心是<b>找一个「连续的高亮条带」</b>：
     * <ol>
     *   <li>逐行数亮蓝像素，得到「候选行」；</li>
     *   <li>把候选行<b>按连续性切成若干段</b>（真实高亮条是一整段，天空是一大段）；</li>
     *   <li>每段必须同时满足：<b>高度 ≤ {@link #DLG_BAR_MAX_ROWS}</b>
     *       且<b>上下都有暗色包夹</b>（{@link #DLG_BAR_LIP}）→ 才算真高亮条；</li>
     *   <li>取满足条件的段里<b>最高的一段</b>（即行数最多、最像完整选项行的那段），
     *       把它的中心 y 写进 {@link #lastBarCy}。</li>
     * </ol>
     *
     * <p>有真高亮条 → {@link DialogState#MENU}；
     * 没有高亮条但暗底占比够 → {@link DialogState#NPC_TEXT}（图3）；
     * 都不是 → {@link DialogState#NONE}，由调用方按回车推进。
     */
    private DialogState detectDialogState() {
        lastBarCy = DLG_ROW1_CY;
        try {
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                return DialogState.NONE;
            }
            double kx = img.getWidth() / (double) BASE_W;
            double ky = img.getHeight() / (double) BASE_H;

            int x0 = clamp((int) Math.round(DLG_SCAN_X0 * kx), 0, img.getWidth() - 1);
            int x1 = clamp((int) Math.round(DLG_SCAN_X1 * kx), x0 + 1, img.getWidth());
            int y0 = clamp((int) Math.round(DLG_SCAN_Y0 * ky), 0, img.getHeight() - 1);
            int y1 = clamp((int) Math.round(DLG_SCAN_Y1 * ky), y0 + 1, img.getHeight());
            int rowMinPx = Math.max(20, (int) Math.round(DLG_BAR_MIN_PX * kx));
            int maxRows = Math.max(6, (int) Math.round(DLG_BAR_MAX_ROWS * ky));
            int lip = Math.max(2, (int) Math.round(DLG_BAR_LIP * ky));
            int rowW = x1 - x0;

            // ---- 逐行统计 ----
            boolean[] isBar = new boolean[y1 - y0];
            boolean[] isDark = new boolean[y1 - y0];
            int darkTotal = 0, total = 0;
            for (int i = 0; i < isBar.length; i++) {
                int y = y0 + i;
                int barPx = 0, darkPx = 0;
                for (int x = x0; x < x1; x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    if (b > 110 && b - r > 30 && g > 70 && g < 200) {
                        barPx++;
                    }
                    if (r < 95 && g < 105 && b < 125) {
                        darkPx++;
                    }
                }
                isBar[i] = barPx >= rowMinPx;
                isDark[i] = darkPx > rowW * 0.5;
                darkTotal += darkPx;
                total += rowW;
            }
            if (total == 0) {
                return DialogState.NONE;
            }

            // ---- 把连续的高亮行切成段，挑出「像真高亮条」的那些 ----
            int bestLen = 0, bestTop = -1, bestBottom = -1;
            int i = 0;
            while (i < isBar.length) {
                if (!isBar[i]) {
                    i++;
                    continue;
                }
                int s = i;
                while (i < isBar.length && isBar[i]) {
                    i++;
                }
                int e = i - 1;              // 段 = [s, e]
                int len = e - s + 1;
                if (len > maxRows) {
                    // 太高 —— 大片蓝天/UI，直接扔掉，并记一笔便于排查
                    listener.log("    [画面判据] 忽略超高的蓝色段 y=" + (y0 + s)
                            + ".." + (y0 + e) + "（" + len + " 行 > " + maxRows + "）");
                    continue;
                }
                // 上下是否被暗色包夹
                boolean upOk = true, downOk = true;
                for (int k = 1; k <= lip; k++) {
                    if (s - k < 0 || !isDark[s - k]) {
                        upOk = false;
                    }
                    if (e + k >= isDark.length || !isDark[e + k]) {
                        downOk = false;
                    }
                }
                if (!upOk || !downOk) {
                    listener.log("    [画面判据] 忽略无暗边包夹的蓝色段 y=" + (y0 + s)
                            + ".." + (y0 + e) + "（上暗=" + upOk + " 下暗=" + downOk + "）");
                    continue;
                }
                if (len > bestLen) {
                    bestLen = len;
                    bestTop = s;
                    bestBottom = e;
                }
            }

            if (bestLen > 0) {
                int cy = (int) Math.round((y0 + bestTop + y0 + bestBottom) / 2.0 / ky);
                lastBarCy = cy;
                listener.log("    [画面判据] 高亮条 " + bestLen + " 行 (y "
                        + (y0 + bestTop) + ".." + (y0 + bestBottom) + ") → 菜单画面，点 y=" + cy);
                return DialogState.MENU;
            }

            double df = darkTotal / (double) total;
            if (df > DLG_DARK_MIN_FRAC) {
                listener.log("    [画面判据] 无高亮条，暗底占比 " + String.format("%.2f", df)
                        + " → NPC 正文");
                return DialogState.NPC_TEXT;
            }
            return DialogState.NONE;
        } catch (Throwable t) {
            listener.log("    判断画面类型出错（" + t + "）");
            return DialogState.NONE;
        }
    }

    /**
     * 找并点掉「好的，我马上去」按钮。
     *
     * @return true = 认出来了并点了；false = 两轮都没认出来（交给调用方兜底）
     */
    private boolean clickConfirmButton() {
        for (int i = 1; i <= CONFIRM_FIND_TRIES; i++) {
            checkStop();
            int[] pt = findConfirmButtonPoint();
            if (pt != null) {
                listener.log("  ✔ 找到「好的，我马上去」@ (" + pt[0] + "," + pt[1] + ")，点击");
                controller.clickWindowPoint(pt[0], pt[1]);
                return true;
            }
            if (i < CONFIRM_FIND_TRIES) {
                listener.log("  第 " + i + "/" + CONFIRM_FIND_TRIES
                        + " 次没找到收尾按钮，等 " + SHORT_MS + "ms 再试（可能还在刷新）");
                sleep(SHORT_MS);
            }
        }
        return false;
    }

    /**
     * 在当前画面里找「好的，我马上去」按钮，返回它的窗口内坐标；找不到返回 null。
     *
     * <p>优先返回<b>纵向最靠下</b>的那一行 —— 对话框的确认按钮都在最底下，
     * 而 NPC 正文里也常出现「好的」之类的字眼，取最下面一行最稳。
     */
    private int[] findConfirmButtonPoint() {
        try {
            ReadResult rr = readScreen("confirm_probe");
            OcrLite.Line best = null;
            for (OcrLite.Line ln : rr.lines) {
                String n = XiaolianBank.normalize(ln.text);
                if (n.isEmpty()) {
                    continue;
                }
                for (String k : CONFIRM_KEYWORDS) {
                    if (n.contains(k)) {
                        if (best == null || ln.cy() > best.cy()) {
                            best = ln;
                        }
                        listener.log("    候选收尾按钮：\"" + ln.text + "\" @ ("
                                + ln.cx() + "," + ln.cy() + ")");
                        break;
                    }
                }
            }
            if (best != null) {
                return new int[]{best.cx(), best.cy()};
            }
        } catch (Throwable t) {
            listener.log("    找收尾按钮时读屏出错（" + t + "）");
        }
        return null;
    }

    /** 当前画面上有没有「好的，我马上去」这类收尾确认按钮。 */
    private boolean confirmButtonPresent() {
        return findConfirmButtonPoint() != null;
    }

    /**
     * 尽力在当前画面里点某一项：识别到就点并返回 true；没识别到<b>什么都不做</b>、返回 false。
     *
     * <p>与 {@link #chooseDialogOption} 的区别：后者找不到时会用键盘兜底（回车/↓+回车），
     * 这里不能用兜底 —— 万一当前菜单里根本没有这一项，乱按回车会误选别的功能。
     * 用于「有就点、没有就跳过」的可选层级。
     */
    private boolean tryChooseDialogOption(String[] keywords, String what) {
        try {
            List<String> norm = new ArrayList<>();
            for (String k : keywords) {
                norm.add(XiaolianBank.normalize(k));
            }
            ReadResult rr = readScreen("dialog_optional_" + what.replaceAll("[^a-zA-Z0-9]", ""));
            for (OcrLite.Line ln : rr.lines) {
                String n = XiaolianBank.normalize(ln.text);
                if (n.isEmpty()) {
                    continue;
                }
                for (String k : norm) {
                    if (k.isEmpty()) {
                        continue;
                    }
                    if (n.contains(k) || XiaolianBank.similarity(n, k) >= 0.72) {
                        listener.log("  识别到「" + what + "」：\"" + ln.text + "\" @ ("
                                + ln.cx() + "," + ln.cy() + ")，直接点它");
                        controller.clickWindowPoint(ln.cx(), ln.cy());
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            listener.log("  找「" + what + "」时出错（" + t + "），跳过这一层");
        }
        return false;
    }

    /** 当前画面上有没有「带选项/高亮条」的对话框。 */
    private boolean dialogOpenNow() {
        try {
            return SalaryTask.scanImage(controller.captureWindow()).dialogOpen;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 运送物资：连按回车推进对话 ====================

    /**
     * 一直按回车推进对话，直到画面上不再有「带选项/高亮条的对话框」。
     *
     * <p>现在只给探针 {@code ysenter} 用。正式流程走
     * {@link #pressEnterThenConfirm()} —— 那个是「连按三次回车 → 点掉
     * 『好的，我马上去』」，才是任务真正完成的标志。
     */
    private void pressEnterUntilDialogClosed() {
        listener.log("—— 连按回车，直到对话结束 ——");
        for (int i = 1; i <= ENTER_MAX_TIMES; i++) {
            checkStop();
            SalaryTask.Ui ui = SalaryTask.scanImage(controller.captureWindow());
            if (!ui.dialogOpen) {
                listener.log("  ✔ 对话已结束（共按了 " + (i - 1) + " 次回车）");
                return;
            }
            listener.log("  第 " + i + "/" + ENTER_MAX_TIMES + " 次回车推进对话");
            controller.sendKey(WindowUtils.VK_RETURN);
            sleep(ENTER_STEP_MS);
        }
        listener.log("  ⚠ 按了 " + ENTER_MAX_TIMES + " 次回车对话仍未结束，先停下等你确认");
        snapshot("anomaly_dialog_not_closed");
    }

    /** 探针用：单独跑「连按回车推对话」，不导航、不接任务。 */
    void pressEnterUntilDialogClosedProbe() {
        try {
            pressEnterUntilDialogClosed();
            listener.log("✔ 回车推对话结束");
        } catch (Abort a) {
            listener.log("✗ " + a.getMessage());
        } catch (Throwable t) {
            listener.log("✗ 执行出错：" + t);
        }
    }

    /**
     * 探针用：单独跑收尾节奏 —— <b>连按三次回车 → 找并点掉「好的，我马上去」</b>。
     *
     * <p>用法：在游戏里手动把运送物资接到手、打开到该点的对话，
     * 然后跑这个探针，只验证最后这三下回车 + 点按钮这一段。
     */
    void pressEnterUntilConfirmButtonProbe() {
        try {
            pressEnterThenConfirm();
            listener.log("✔ 收尾按钮流程跑完");
        } catch (Abort a) {
            listener.log("✗ " + a.getMessage());
        } catch (Throwable t) {
            listener.log("✗ 执行出错：" + t);
        }
    }

    /**
     * 探针用：只跑「找并点掉收尾按钮」，<b>不按回车</b>。
     *
     * <p>用于单独校准 {@link #CONFIRM_KEYWORDS} 和按钮坐标：先手动让
     * 「好的，我马上去」停在屏幕上，再跑这个探针，看日志里认出来的坐标对不对。
     */
    void clickConfirmButtonProbe() {
        try {
            if (clickConfirmButton()) {
                listener.log("✔ 收尾按钮已点击");
            } else {
                listener.log("✗ 没找到「好的，我马上去」，"
                        + "建议按日志和截图调 CONFIRM_KEYWORDS 或 PT_CONFIRM_OK");
            }
        } catch (Throwable t) {
            listener.log("✗ 执行出错：" + t);
        }
    }

    /** 探针用：单独跑运送物资的「收尾那一轮对话」（再 G → 点运送物资 → 连按回车）。 */
    void finishYunsongQuestProbe() {
        try {
            finishYunsongQuest();
            listener.log("✔ 运送物资收尾流程跑完");
        } catch (Abort a) {
            listener.log("✗ " + a.getMessage());
        } catch (Throwable t) {
            listener.log("✗ 执行出错：" + t);
        }
    }

    /** F11：屏蔽其他玩家，画面干净很多。 */
    private void hideOtherPlayers() {
        controller.sendKey(WindowUtils.VK_F11);
        sleep(SHORT_MS);
        playersHidden = true;
        listener.log("已按 F11 屏蔽其他玩家");
    }

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
            controller.sendKey(WindowUtils.VK_F11);
            sleep(SHORT_MS);
            listener.log("已按 F11 还原显示");
        } catch (Throwable ignore) {
            // 还原失败不影响主流程
        }
    }

    // ==================== 前置清理 ====================

    /** 关闭游戏广告弹窗（检测到才处理），逻辑与工资任务一致。 */
    void closeAds() {
        listener.log("—— 准备：关闭游戏广告弹窗 ——");
        if (!adColorHit()) {
            listener.log("  未检测到广告弹窗，跳过（不按 ESC，避免打开系统菜单）");
            return;
        }
        if (!adTitleRecognized()) {
            // 色块偏红但 OCR 认不出广告标题：200056/055152 两次现场都是这种「红色景物误报」，
            // 旧逻辑会 ESC×3 + 点 X 两轮 + 中止，把好局废掉。这里直接放行。
            listener.log("  结论：不是广告弹窗（见上），不按 ESC、不点 X，直接继续");
            return;
        }
        for (int i = 1; i <= 3; i++) {
            checkStop();
            controller.sendKey(WindowUtils.VK_ESCAPE);
            listener.log("  ESC 第 " + i + " 次（关「游戏活动展示」）");
            sleep(ESC_MS);
        }
        if (systemMenuOpen()) {
            controller.sendKey(WindowUtils.VK_ESCAPE);
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
                            + "请手动关掉后重新点击「孝廉」。");
        }
        listener.log("  ✔ 广告弹窗处理完成");
    }

    // ==================== 寻路 ====================

    /** T 回城到成都·子城。 */
    /**
     * 步骤 1：<b>按 O 回军团 → 按 T 回城</b>，确保角色站在主城里。
     *
     * <p>2026-09-22 用户口述流程定稿：不管角色当前在哪（副本/北邙山/别的地图），
     * 先 O 回军团归一状态，再 T 回城。回城后读一次坐标条确认位置（读不出只记日志，
     * 不拦截 —— 后面两段寻路都有到达校验兜底）。O 面板若残留，补按 O 关掉，
     * 否则会被 bigPanelOpen() 误判成「自动寻路」面板。
     */
    void backToZicheng() {
        listener.log("—— 导航：按 O 回军团 → 按 T 回城 ——");
        // O 打开军团界面 → 点「回到军团」传送（动态识别按钮，识别不到退固定坐标）
        controller.sendKey(WindowUtils.VK_O);
        sleep(STEP_MS);
        int[] legionBtn = null;
        try {
            legionBtn = SalaryTask.findBackToLegionButton(captureQuiet());
        } catch (Throwable t) {
            listener.log("  动态识别「回到军团」出错（" + t.getMessage() + "），用固定坐标");
        }
        if (legionBtn != null) {
            listener.log("  动态定位「回到军团」按钮 @ (" + legionBtn[0] + "," + legionBtn[1] + ")");
        } else {
            legionBtn = PT_BACK_TO_LEGION;
            listener.log("  未动态识别到「回到军团」按钮，用固定坐标 (" + legionBtn[0] + "," + legionBtn[1] + ")");
        }
        controller.clickWindowPoint(legionBtn[0], legionBtn[1]);
        sleep(TELEPORT_MS);
        listener.log("  ✔ 已回到军团");
        // T 回城
        controller.sendKey(WindowUtils.VK_T);
        sleep(TELEPORT_MS);
        listener.log("  ✔ 已回城");
        // 军团界面（O 面板）可能跟着传送残留 —— 「回到军团」按钮还看得见就补按 O 关掉
        boolean legionPanelStillOpen = false;
        try {
            legionPanelStillOpen = SalaryTask.findBackToLegionButton(captureQuiet()) != null;
        } catch (Throwable ignore) {
            // 识别不出就当面板已关（多按一次 O 也无害，但少干扰更好）
        }
        if (legionPanelStillOpen) {
            controller.sendKey(WindowUtils.VK_O);
            sleep(STEP_MS);
            listener.log("  ✔ 已关掉残留的军团界面（O 面板）");
        }
        // 「确保你在主城」：读坐标条确认，读不出只记日志不拦截
        int[] pos = readMapCoordsOnce();
        if (pos != null) {
            listener.log("  当前位置 (" + pos[0] + "," + pos[1] + ")");
        } else {
            listener.log("  ⚠ 坐标条读不出来，无法确认回城位置（继续，后续寻路有到达校验兜底）");
        }
    }

    /** 打开寻路面板，填目标坐标，点「移动」，走过去，再关掉面板。
     *  填完检查面板还开着（防止中途被关导致假填入），走完用右上角地图坐标条校验是否真到达。 */
    void navTo(String x, String y) {
        navTo(x, y, true);
    }

    /**
     * 同上，但可以关掉「面板打不开 → O 回军团 → T 回城」这套重试。
     *
     * @param allowTownRecovery 见 {@link #openNavPanel(boolean)}：子城段 true，
     *        NPC 地图段（罗城 11,8）必须 false，否则会把角色传回子城、静默走错地图。
     */
    void navTo(String x, String y, boolean allowTownRecovery) {
        int tx = Integer.parseInt(x.trim()), ty = Integer.parseInt(y.trim());
        for (int attempt = 1; attempt <= 2; attempt++) {
            listener.log("—— 寻路到 (" + x + ", " + y + ") ——"
                    + (attempt > 1 ? "（第 " + attempt + " 次尝试）" : ""));
            openNavPanel(allowTownRecovery);
            // 面板会整体漂移，固定坐标会点到两框之间的缝上 → 填不进数字。
            // 所以每次都实测两个输入框和「移动」按钮的位置，测不到才退回固定坐标。
            int[][] boxes = findCoordBoxes();
            if (boxes == null) {
                // 面板可能还在展开动画中，等一下再量一次（量到就少走一次固定坐标保底）
                listener.log("  首次没量到坐标框（面板可能正在展开），等 800ms 再量一次");
                sleep(800);
                boxes = findCoordBoxes();
            }
            int[] b1 = boxes != null ? boxes[0] : PT_NAV_BOX1;
            int[] b2 = boxes != null ? boxes[1] : PT_NAV_BOX2;
            int[] mv = findMoveButton();
            fillCoordBox(b1, x, "第 1 个坐标框");
            fillCoordBox(b2, y, "第 2 个坐标框");
            if (!navPanelVisible()) {
                listener.log("  ⚠ 填坐标过程中「自动寻路」面板被关掉了，重来");
                snapshot("nav_panel_closed_midway");
                continue;
            }
            verifyBothBoxes(b1, b2, x, y);
            snapshot("nav_coords_" + x + "_" + y);
            // 量框 → 填两框之间隔了几秒，保险起见临点前再量一次「移动」的位置，
            // 面板若在这几秒里漂移过，用新位置顶掉旧位置。
            int[] mv2 = findMoveButton();
            if (mv2 != null && (mv2[0] != mv[0] || mv2[1] != mv[1])) {
                listener.log("  「移动」按钮位置微移：" + mv[0] + "," + mv[1]
                        + " → " + mv2[0] + "," + mv2[1]);
                mv = mv2;
            }
            // 起步前先读一遍坐标，作为「是否真的开始走」的对比基准。
            int[] before = readMapCoordsOnce();
            if (before != null) {
                listener.log("  起步前角色在 (" + before[0] + "," + before[1] + ")");
            }
            long t0 = System.currentTimeMillis();
            listener.log("  点击「移动」，角色自动走过去");
            boolean walking = clickMoveUntilWalking(mv, before);
            if (!walking) {
                String shot = snapshot("anomaly_move_ineffective");
                listener.log("  ⚠ 连点 3 次「移动」角色都没动，重开面板再试"
                        + (shot != null ? "（现场截图 " + shot + "）" : ""));
                closeNavPanel();
                sleep(1500);
                continue;
            }
            long walkedMs = System.currentTimeMillis() - t0;
            long restMs = Math.max(2000, NAV_WALK_MS - walkedMs);
            listener.log("  角色已在移动，等走完（再等 " + (restMs / 1000) + " 秒）");
            sleep(restMs);
            closeNavPanel();
            sleep(3000);
            // 到达校验：读右上角地图坐标条（如「成都·子城（ 20，16 ）」）
            int[] cur = readMapCoords();
            if (cur != null && Math.abs(cur[0] - tx) <= 1 && Math.abs(cur[1] - ty) <= 1) {
                listener.log("  ✔ 已到达 (" + cur[0] + "," + cur[1] + ")");
                return;
            }
            listener.log("  ⚠ 当前坐标 " + (cur == null ? "读取失败" : "(" + cur[0] + "," + cur[1] + ")")
                    + " ≠ 目标 (" + x + "," + y + ")");
        }
        String shot = snapshot("anomaly_nav_not_arrived");
        abort("寻路两次都没能到达 (" + x + "," + y + ")",
                "自动寻路重试后仍未到达目标坐标。\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "请手动走到 (" + x + "," + y + ") 后重新点击「孝廉」。");
    }

    /**
     * 点「移动」并当场验证角色是否真的动起来了（2.5 秒后读一次坐标条，和起步前对比）。
     *
     * <p>GUI 实测出现过「面板开着、坐标填对、移动点下去角色却没走」的情况
     * （怀疑是辅助窗和游戏窗重叠、抬窗偶发失败时点击被辅助窗吃掉，或面板上有
     * 弹层挡住按钮）。死等 12 秒才发现没走纯属浪费 —— 这里没动就带小偏移重点，
     * 最多 3 次；坐标条读不出来（被气泡挡住等）时无法验证，按老办法相信这一下。
     */
    private boolean clickMoveUntilWalking(int[] mv, int[] before) {
        for (int k = 1; k <= 3; k++) {
            int jx = mv[0] + (k == 1 ? 0 : (k == 2 ? -3 : 4));
            int jy = mv[1] + (k == 1 ? 0 : (k == 2 ? 2 : -3));
            if (k > 1) {
                boolean bg = controller.isRunInBackground();
                boolean fg = bg || controller.isForegroundGameWindow();
                listener.log("  第 " + k + " 次点「移动」（偏移 " + (jx - mv[0]) + "," + (jy - mv[1])
                        + (bg ? "），后台模式直接重点（点击走窗口消息）"
                              : "），游戏是否前台：" + (fg ? "是" : "否——先重新抬窗")));
                if (!fg) {
                    controller.focusWindow();
                }
            }
            controller.clickWindowPoint(jx, jy);
            sleep(2500);
            int[] now = readMapCoordsOnce();
            if (before == null || now == null) {
                listener.log("  坐标条读不出来，无法验证是否开始移动，按已生效处理");
                return true;
            }
            if (now[0] != before[0] || now[1] != before[1]) {
                listener.log("  ✔ 角色动了：" + before[0] + "," + before[1]
                        + " → " + now[0] + "," + now[1]);
                return true;
            }
            listener.log("  ⚠ 点完「移动」坐标还是 (" + now[0] + "," + now[1] + ")，角色没动");
            if (k == 1) {
                snapshot("nav_move_no_effect");
            }
        }
        return false;
    }

    /**
     * OCR 右上角地图坐标条（如「成都·子城（ 28，16 ）」），返回 {x, y}；读不出返回 null。
     * 坐标条是黄字，聊天气泡（白/黑）经常盖在上面。直接 OCR 原图会被气泡带偏，
     * 所以先做「黄度灰度图」：黄度 = r+g-2b，映射成黑字白底的灰度图，气泡完全消失。
     * 实测：平滑灰度能读出「（20，16）」，硬二值反而 0 行（锯齿干扰引擎）。
     */
    int[] readMapCoords() {
        for (int tries = 1; tries <= 3; tries++) {
            int[] xy = readMapCoordsOnce();
            if (xy != null) {
                if (tries > 1) {
                    listener.log("  （地图坐标条第 " + tries + " 遍才读出）");
                }
                return xy;
            }
            if (tries < 3) sleep(1200);
        }
        listener.log("  地图坐标条读了 3 遍都没认出来（可能被聊天气泡挡住）");
        return null;
    }

    /** 读一遍地图坐标条（不重试），读不出 / 出错返回 null —— 供「移动是否生效」这类快速验证用。 */
    int[] readMapCoordsOnce() {
        return readMapCoordsOnce(controller);
    }

    /**
     * 静态版：读指定控制器窗口的地图坐标条（不重试），读不出返回 null。
     * 供工资任务等其它任务类复用（寻路「一到就按 G」的到达轮询）。
     */
    static int[] readMapCoordsOnce(GameWindowController c) {
        try {
            BufferedImage img = c.captureWindow();
            BufferedImage crop = img.getSubimage(850, 145,
                    Math.min(1030, img.getWidth()) - 850, 178 - 145);
            File jobDir = OcrLite.prepareJobDir();
            List<File> files = new ArrayList<>();
            files.add(saveYellowGray(crop, 4, new File(jobDir, "coord_gray.png")));
            List<OcrLite.Result> res = OcrLite.recognize(files, OCR_TIMEOUT_S);
            for (OcrLite.Result r : res) {
                if (!r.file.contains("coord_gray")) {
                    continue; // recognize 按目录跑，只认我们自己那张
                }
                for (OcrLite.Line ln : r.lines) {
                    int[] xy = parseCoords(ln.text);
                    if (xy != null) {
                        return xy;
                    }
                }
            }
        } catch (Throwable t) {
            // 静默：由调用方决定怎么兜底
        }
        return null;
    }

    /** 黄度灰度图：黄字→黑、其余→白，×scale 双线性放大（保留抗锯齿，OCR 友好）。 */
    private static File saveYellowGray(BufferedImage crop, int scale, File dst) throws Exception {
        int w = crop.getWidth(), h = crop.getHeight();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = crop.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int v = 255 - (r + g - 2 * b);
                gray.getRaster().setSample(x, y, 0, Math.max(0, Math.min(255, v)));
            }
        }
        BufferedImage up = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g2 = up.createGraphics();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(gray, 0, 0, w * scale, h * scale, null);
        g2.dispose();
        javax.imageio.ImageIO.write(up, "png", dst);
        return dst;
    }

    /** 从「成都·子城（ 28，16 ）」这类文本解析出两个数字（逗号被气泡遮挡也能兜住）。 */
    private static int[] parseCoords(String text) {
        if (text == null) return null;
        String t = text.replace('（', '(').replace('）', ')')
                .replace('，', ',').replace('。', '.');
        java.util.regex.Matcher pair = java.util.regex.Pattern
                .compile("(\\d{1,3})\\s*[,，]\\s*(\\d{1,3})").matcher(t);
        if (pair.find()) {
            return new int[]{Integer.parseInt(pair.group(1)), Integer.parseInt(pair.group(2))};
        }
        // 逗号被遮挡/误读时的兜底：这一条上只会有坐标这一对数字，取前两个数字组
        java.util.regex.Matcher nums = java.util.regex.Pattern.compile("\\d{1,3}").matcher(t);
        List<Integer> v = new ArrayList<>();
        while (nums.find() && v.size() < 2) {
            v.add(Integer.valueOf(nums.group()));
        }
        if (v.size() == 2) {
            return new int[]{v.get(0), v.get(1)};
        }
        return null;
    }

    /**
     * 动态测量寻路面板上两个坐标输入框的位置（窗口坐标）。
     * 面板会随右上角地图横幅整体上下漂 ~74px（2026-09-21 09:44 现场：框心 457→383），
     * 扫描带必须够宽（y350~505），测不到才退回固定坐标。
     */
    int[][] findCoordBoxes() {
        try {
            BufferedImage img = controller.captureWindow();
            int[][] boxes = findNavBoxesCore(img);
            if (boxes != null) {
                listener.log("  实测坐标框：box1 中心 (" + boxes[0][0] + "," + boxes[0][1]
                        + ")，box2 中心 (" + boxes[1][0] + "," + boxes[1][1] + ")");
                lastNavBox = boxes[0];
                return boxes;
            }
            // 暗框指纹没命中时用 OCR「坐标」标签反推（面板漂出扫描带 / 框内亮度被背景抬高时走这条）
            int[] lb = ocrNavInputLabel();
            if (lb != null) {
                int ly = lb[1] + NAV_BOX_DY;
                boxes = new int[][]{{lb[0] + NAV_BOX_DX1, ly}, {lb[0] + NAV_BOX_DX2, ly}};
                listener.log("  暗框指纹未命中 → 用 OCR「坐标」标签 @ (" + lb[0] + "," + lb[1]
                        + ") 反推坐标框：box1 中心 (" + boxes[0][0] + "," + boxes[0][1]
                        + ")，box2 中心 (" + boxes[1][0] + "," + boxes[1][1] + ")");
                lastNavBox = boxes[0];
                return boxes;
            }
            listener.log("  未能实测坐标框位置（面板漂移或未开），用固定坐标");
            return null;
        } catch (Throwable t) {
            listener.log("  实测坐标框出错（" + t.getMessage() + "），用固定坐标");
            return null;
        }
    }

    /**
     * OCR 找面板输入行的「坐标」标签，返回 {@code {标签左边缘x, 标签中心y}}（窗口坐标）；
     * 找不到 / OCR 异常返回 null。
     *
     * <p>条带内可能同时出现面板内部的「坐标」表头（列表顶部），所以<b>取 y 最大的那一行</b>
     * —— 输入行永远在表头下方。OCR 用整窗 ×2 原图（和 ScreenText 同参数），
     * 坐标换算 = 行坐标 / 2 / 窗口缩放。
     */
    private int[] ocrNavInputLabel() {
        try {
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                return null;
            }
            double kx = img.getWidth() / (double) BASE_W;
            double ky = img.getHeight() / (double) BASE_H;
            File jobDir = OcrLite.prepareJobDir();
            File f = OcrLite.saveForOcr(img, 2, new File(jobDir, "navlabel.png"), OcrLite.MODE_ORIGINAL);
            if (f == null) {
                return null;
            }
            List<OcrLite.Result> res = OcrLite.recognize(Collections.singletonList(f), OCR_TIMEOUT_S);
            int bestX0 = -1, bestCy = Integer.MIN_VALUE;
            for (OcrLite.Result one : res) {
                if (one.file == null || !one.file.replace('\\', '/').endsWith("navlabel.png")) {
                    continue; // recognize 按目录跑，过滤掉同目录历史图（铁律）
                }
                for (OcrLite.Line l : one.sorted()) {
                    String t = XiaolianBank.normalize(l.text);
                    boolean hit = false;
                    for (String w : NAV_LABEL_WORDS) {
                        if (t.contains(w)) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) {
                        continue;
                    }
                    int cx = (int) Math.round(l.cx() / 2.0 / kx);
                    int cy = (int) Math.round(l.cy() / 2.0 / ky);
                    if (cx < NAV_LABEL_ZONE[0] || cx > NAV_LABEL_ZONE[1]
                            || cy < NAV_LABEL_ZONE[2] || cy > NAV_LABEL_ZONE[3]) {
                        continue;
                    }
                    if (cy > bestCy) {
                        bestCy = cy;
                        bestX0 = (int) Math.round(l.x0 / 2.0 / kx);
                    }
                }
            }
            return bestCy == Integer.MIN_VALUE ? null : new int[]{bestX0, bestCy};
        } catch (Throwable t) {
            listener.log("  OCR 找「坐标」标签失败（" + t + "）");
            return null;
        }
    }

    /**
     * 「自动寻路」面板是否开着：<b>暗框指纹优先，OCR「坐标」标签兜底</b>。
     *
     * <p>指纹精确（能直接给出框坐标），但只在「框够暗 + 邻域干净 + 绿按钮 + 金黄字」
     * 全部满足时才命中；2026-09-22 罗城段就出现过「面板明明开着、指纹没命中」。
     * 所以判据两路并进：指纹命中算开；指纹没命中再看 OCR 有没有「坐标」标签。
     */
    private boolean navPanelVisible() {
        if (bigPanelOpen()) {
            return true;
        }
        return ocrNavInputLabel() != null;
    }

    /**
     * 坐标框扫描核心（静默版，三个任务类共用）· 等宽双框指纹 v2：
     * 「坐标：[ ][ ] (移动)」行的两个输入框是等宽超暗块（各 20~36px、间隙 1~10px、
     * 总域 42~64px），判暗阈值 THR=45 —— 面板半透明底色实测 (0,68,93) 全通道 <85，
     * 旧 85 阈值把整块面板底算成暗（一条 146px 连续带）导致"面板开着却找不到"；
     * 框底 (9,30,35) 才是真超暗。框内白字会把个别列压破阈值形成内部浅谷，
     * 用「枚举谷 + 左右等宽(差≤6)」唯一定位真框间隙（字谷分割会得到 40/14 不等宽被拒）。
     * 再过三道防误报闸：干净邻域（段外暗列 >2 的脏列 ≤2）、绿色「移动」按钮 ≥100、
     * 按钮金黄字「移 动」52~150（树丛 0、成就横幅 37-41、大块金黄 UI 300+ 全被挡）。
     * 滑窗 y365~478：正常框带 y447±、抬升 74px 后 y373±；NPC 列表假框行 y337 在域外。
     * 测不到返回 null（调用方有 sleep 重测 + lastNavBox/固定坐标兜底）。
     * 离线回归 _dev/test_boxes_v9.py：65 真帧 61 检出、12 负样本 0 误报。
     */
    static int[][] findNavBoxesCore(BufferedImage img) {
        if (img == null) {
            return null;
        }
        try {
            final int XR0 = 484, XR1 = 565, BH = 20, NEED = 5;
            int yTop = Math.min(478, img.getHeight() - BH - 1);
            int[] colCnt = new int[XR1 - XR0 + 1];
            for (int y = 365; y <= yTop; y += 2) {
                java.util.Arrays.fill(colCnt, 0);
                for (int x = XR0; x <= XR1; x++) {
                    int n = 0;
                    for (int yy = y; yy < y + BH; yy++) {
                        int rgb = img.getRGB(x, yy);
                        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                        if (r < 45 && g < 45 && b < 45) n++;
                    }
                    colCnt[x - XR0] = n;
                }
                int s0 = -1, s1 = -1;
                for (int i = 0; i < colCnt.length; i++) {
                    if (colCnt[i] >= NEED) {
                        if (s0 < 0) s0 = i;
                        s1 = i;
                    }
                }
                if (s0 < 0) continue;
                int span = s1 - s0 + 1;
                if (span < 42 || span > 64) continue;
                int[][] pair = pairEqualBoxes(colCnt, s0, s1);
                if (pair == null) continue;
                int xs0 = XR0 + s0, xs1 = XR0 + s1;
                int dirty = 0;
                for (int i = 0; i < s0; i++) if (colCnt[i] > 2) dirty++;
                for (int i = s1 + 1; i < colCnt.length; i++) if (colCnt[i] > 2) dirty++;
                int green = 0, gold = 0;
                for (int yy = Math.max(0, y - 8); yy < y + BH + 8; yy++) {
                    for (int xx = xs1 + 10; xx <= xs1 + 169 && xx < img.getWidth(); xx++) {
                        int rgb = img.getRGB(xx, yy);
                        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                        if (g > 140 && g - r > 40 && g - b > 40) green++;
                    }
                }
                for (int yy = Math.max(0, y - 4); yy < y + BH + 4; yy++) {
                    for (int xx = xs1 + 20; xx <= xs1 + 149 && xx < img.getWidth(); xx++) {
                        int rgb = img.getRGB(xx, yy);
                        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                        if (r > 190 && g > 120 && g < 200 && b < 120 && r - b > 90) gold++;
                    }
                }
                if (dirty > 2 || green < 100 || gold < 52 || gold > 150) continue;
                int l0 = XR0 + pair[0][0], l1 = XR0 + pair[0][1];
                int r0 = XR0 + pair[1][0], r1 = XR0 + pair[1][1];
                int yc = refineBoxBandY(img, l0, r1, y);
                return new int[][]{{(l0 + l1) / 2, yc}, {(r0 + r1) / 2, yc}};
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在总域内枚举暗谷，左右等宽（各 20~36、差 ≤6）的那条谷即两框真间隙。 */
    private static int[][] pairEqualBoxes(int[] c, int s0, int s1) {
        int i = s0;
        while (i <= s1) {
            if (c[i] < 5) {
                int j = i;
                while (j + 1 <= s1 && c[j + 1] < 5) j++;
                int gw = j - i + 1;
                int wl = i - s0, wr = s1 - j;
                if (gw >= 1 && gw <= 10 && wl >= 20 && wl <= 36 && wr >= 20 && wr <= 36
                        && Math.abs(wl - wr) <= 6) {
                    return new int[][]{{s0, i - 1}, {j + 1, s1}};
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return null;
    }

    /** 两框列范围内找暗行连续段（≥8 行）的垂直中心；找不到退回滑窗带中心。 */
    private static int refineBoxBandY(BufferedImage img, int xa, int xb, int yHint) {
        int best0 = -1, best1 = -1, bestLen = 0, cur0 = -1;
        int yEnd = Math.min(img.getHeight(), yHint + 34);
        for (int y = Math.max(0, yHint - 14); y < yEnd; y++) {
            int n = 0;
            for (int x = xa; x <= xb; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 45 && g < 45 && b < 45) n++;
            }
            if (n >= 8) {
                if (cur0 < 0) cur0 = y;
            } else {
                if (cur0 >= 0 && y - cur0 >= 8 && y - cur0 > bestLen) {
                    bestLen = y - cur0;
                    best0 = cur0;
                    best1 = y - 1;
                }
                cur0 = -1;
            }
        }
        if (cur0 >= 0 && yEnd - cur0 >= 8 && yEnd - cur0 > bestLen) {
            best0 = cur0;
            best1 = yEnd - 1;
        }
        return best0 >= 0 ? (best0 + best1) / 2 : yHint + 10;
    }

    /** 动态找「移动」绿色按钮的质心（窗口坐标）；绿按钮没找到就用「坐标」标签反推；再不行退回固定坐标。 */
    int[] findMoveButton() {
        try {
            int[] c = findNavMoveBtnCore(controller.captureWindow());
            if (c != null) {
                listener.log("  实测「移动」按钮中心 (" + c[0] + "," + c[1] + ")");
                return c;
            }
            int[] lb = ocrNavInputLabel();
            if (lb != null) {
                int[] d = {lb[0] + NAV_MOVE_DX, lb[1] + NAV_MOVE_DY};
                listener.log("  绿按钮没找到 → 用 OCR「坐标」标签 @ (" + lb[0] + "," + lb[1]
                        + ") 反推「移动」按钮 (" + d[0] + "," + d[1] + ")");
                return d;
            }
            listener.log("  未能实测「移动」按钮，用固定坐标");
        } catch (Throwable t) {
            listener.log("  实测「移动」按钮出错（" + t.getMessage() + "），用固定坐标");
        }
        return PT_NAV_MOVE;
    }

    /** 「移动」绿色按钮质心扫描核心（静默共享，y350~505 覆盖面板漂移）；绿色像素不足返回 null。 */
    static int[] findNavMoveBtnCore(BufferedImage img) {
        if (img == null) {
            return null;
        }
        try {
            int xs = 575, xe = Math.min(712, img.getWidth() - 1);
            int ys = 350, ye = Math.min(505, img.getHeight() - 1);
            if (xe - xs < 20 || ye - ys < 20) {
                return null;
            }
            int rows = ye - ys + 1;
            int[] rowCnt = new int[rows];
            int[] rowXmin = new int[rows];
            int[] rowXmax = new int[rows];
            for (int i = 0; i < rows; i++) {
                int y = ys + i;
                rowXmin[i] = Integer.MAX_VALUE;
                rowXmax[i] = -1;
                for (int x = xs; x <= xe; x++) {
                    if (isMoveGreen(img.getRGB(x, y))) {
                        rowCnt[i]++;
                        if (x < rowXmin[i]) {
                            rowXmin[i] = x;
                        }
                        if (x > rowXmax[i]) {
                            rowXmax[i] = x;
                        }
                    }
                }
            }
            // 只认「按钮行」组成的连续实心横条：滚动条箭头一行绿像素太少（被行门槛挡掉），
            // 场景里的绿色树丛虽然行数够但连不成 ≥10 行、跨度也不对（形状闸门挡掉）。
            int best0 = -1, best1 = -1;
            for (int i = 0; i < rows; i++) {
                if (rowCnt[i] < MOVE_BTN_ROW_MIN) {
                    continue;
                }
                int j = i;
                while (j + 1 < rows && rowCnt[j + 1] >= MOVE_BTN_ROW_MIN) {
                    j++;
                }
                if (j - i + 1 >= MOVE_BTN_MIN_ROWS && (best0 < 0 || j - i > best1 - best0)) {
                    best0 = i;
                    best1 = j;
                }
                i = j;
            }
            if (best0 < 0) {
                return null;
            }
            int xmin = Integer.MAX_VALUE, xmax = -1;
            for (int k = best0; k <= best1; k++) {
                xmin = Math.min(xmin, rowXmin[k]);
                xmax = Math.max(xmax, rowXmax[k]);
            }
            int wdt = xmax - xmin + 1;
            if (wdt < MOVE_BTN_MIN_W || wdt > MOVE_BTN_MAX_W) {
                return null;
            }
            long sx = 0, sy = 0;
            int n = 0;
            for (int k = best0; k <= best1; k++) {
                int y = ys + k;
                for (int x = xs; x <= xe; x++) {
                    if (isMoveGreen(img.getRGB(x, y))) {
                        sx += x;
                        sy += y;
                        n++;
                    }
                }
            }
            if (n >= MOVE_BTN_MIN_PX) {
                return new int[]{(int) (sx / n), (int) (sy / n)};
            }
        } catch (Throwable t) {
            // 忽略，返回 null
        }
        return null;
    }

    /** 「移动」按钮的绿色判据（实测：绿芯 G>140 且明显偏绿）。 */
    private static boolean isMoveGreen(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return g > 140 && g - r > 40 && g - b > 40;
    }

    /** 打开「自动寻路」面板；打不开就 O → 回到军团 → T 重试一轮。 */
    private void openNavPanel() {
        openNavPanel(true);
    }

    /**
     * 打开「自动寻路」面板。判据<b>两路并进</b>：暗框指纹优先，OCR「坐标」标签兜底
     * （2026-09-22 罗城段实车：面板开着但指纹没命中，旧代码连报三次「面板未出现」）。
     *
     * @param allowTownRecovery 面板打不开时是否允许走「O 回军团 → T 回城」这套重试。
     *        子城段（20,16）允许；<b>罗城段（11,8）必须传 false</b> —— 那时角色已经在
     *        NPC 地图里，传送回去会把人送回子城，后面填的 11,8 就变成在子城里走，
     *        整条路线被静默搞乱（2026-09-22 02:02 实车就是这么翻的）。
     */
    private void openNavPanel(boolean allowTownRecovery) {
        for (int round = 1; round <= 2; round++) {
            for (int i = 1; i <= 3; i++) {
                checkStop();
                if (bigPanelOpen()) {
                    listener.log("  ✔ 「自动寻路」面板已打开（暗框指纹）");
                    return;
                }
                int[] lb = ocrNavInputLabel();
                if (lb != null) {
                    listener.log("  ✔ 「自动寻路」面板已打开（OCR 找到「坐标」标签 @ ("
                            + lb[0] + "," + lb[1] + ")，指纹未命中）");
                    return;
                }
                int[] pt = findXunluButton();
                if (pt != null) {
                    listener.log("  OCR 定位到「寻路」按钮 @ (" + pt[0] + "," + pt[1] + ")");
                } else {
                    pt = PT_XUNLU_BTNS[(i - 1) % PT_XUNLU_BTNS.length];
                    listener.log("  未识别到「寻路」字样，退回固定坐标 (" + pt[0] + "," + pt[1] + ")");
                }
                controller.clickWindowPoint(pt[0], pt[1]);
                sleep(CLICK_MS);
            }
            if (bigPanelOpen() || ocrNavInputLabel() != null) {
                listener.log("  ✔ 「自动寻路」面板已打开");
                return;
            }
            if (round == 1 && !allowTownRecovery) {
                listener.log("  ⚠ 面板还没打开 —— 但角色已在 NPC 地图（传回城会打乱路线），"
                        + "只再补点一轮「寻路」");
                sleep(STEP_MS);
                continue;
            }
            if (round == 1) {
                listener.log("  ⚠ 面板未出现，可能角色不在城镇 —— 补按 O → 回到军团 → T 回城后重试");
                controller.ensureWindowVisible();
                controller.sendKey(WindowUtils.VK_O);
                sleep(STEP_MS);
                // 两套机制：动态识别「回到军团」橙色按钮优先，识别不到退回固定坐标保底
                int[] legionBtn = null;
                try {
                    legionBtn = SalaryTask.findBackToLegionButton(captureQuiet());
                } catch (Throwable t) {
                    listener.log("  动态识别「回到军团」出错（" + t.getMessage() + "），用固定坐标");
                }
                if (legionBtn != null) {
                    listener.log("  动态定位「回到军团」按钮 @ (" + legionBtn[0] + "," + legionBtn[1] + ")");
                } else {
                    legionBtn = PT_BACK_TO_LEGION;
                    listener.log("  未动态识别到「回到军团」按钮，用固定坐标 (" + legionBtn[0] + "," + legionBtn[1] + ")");
                }
                controller.clickWindowPoint(legionBtn[0], legionBtn[1]);
                sleep(TELEPORT_MS);
                controller.sendKey(WindowUtils.VK_T);
                sleep(TELEPORT_MS);
                // 回到军团后军团界面（O 面板）会留在屏幕上 —— 它是个大深色面板，
                // 会被 bigPanelOpen() 误判成「自动寻路面板已打开」，导致后面量不到坐标框、填框落空。
                // 所以只要「回到军团」按钮还看得见（=面板还开着），就补按 O 关掉。
                boolean legionPanelStillOpen = false;
                try {
                    legionPanelStillOpen = SalaryTask.findBackToLegionButton(captureQuiet()) != null;
                } catch (Throwable t) {
                    // 忽略：识别不出就当面板已关，不额外按 O（多按一次 O 也无害，但少干扰更好）
                }
                if (legionPanelStillOpen) {
                    controller.sendKey(WindowUtils.VK_O);
                    sleep(STEP_MS);
                    listener.log("  ✔ 已关掉残留的军团界面（O 面板）");
                }
            }
        }
        String shot = snapshot("anomaly_nav_missing");
        // 三路探针：把每路判据的结果写进日志，下次一眼看出是哪一路没通（免得再来一轮「面板明明开着」）
        try {
            BufferedImage probe = controller.captureWindow();
            listener.log("  ✗ 面板探针：暗框指纹=" + (findNavBoxesCore(probe) == null ? "无" : "有")
                    + "，绿「移动」按钮=" + (findNavMoveBtnCore(probe) == null ? "无" : "有")
                    + "，OCR「坐标」标签=" + (ocrNavInputLabel() == null ? "无" : "有"));
        } catch (Throwable t) {
            listener.log("  ✗ 面板探针失败（" + t + "）");
        }
        abort("未能打开「自动寻路」面板",
                "点击右上角「寻路」按钮后没有出现「自动寻路」面板，流程已停止。\n\n"
                        + (allowTownRecovery
                        ? "已经试过「O 回军团 → T 回城」再来一轮，仍然打不开。\n\n"
                        : "当前在 NPC 地图上（没有传送回城，避免打乱路线），已经补点过一轮。\n\n")
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "请检查游戏窗口是否卡住 / 角色是否在可寻路的地图上，然后重新点击任务按钮。");
    }

    /**
     * 在右上角区域 OCR 找「寻路」按钮，返回窗口坐标；找不到返回 null。
     *
     * <p>这么做是因为按钮列的 y 会随右上角地图横幅展开/收起浮动约 74px。
     */
    int[] findXunluButton() {
        BufferedImage img = controller.captureWindow();
        if (img == null) {
            return null;
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x0 = Math.max(0, (int) Math.round(XL_BTN_X0 * kx));
        int x1 = Math.min(img.getWidth(), (int) Math.round(XL_BTN_X1 * kx));
        int y0 = Math.max(0, (int) Math.round(XL_BTN_Y0 * ky));
        int y1 = Math.min(img.getHeight(), (int) Math.round(XL_BTN_Y1 * ky));
        if (x1 - x0 < 20 || y1 - y0 < 20) {
            return null;
        }
        try {
            final int sc = 3;
            BufferedImage crop = img.getSubimage(x0, y0, x1 - x0, y1 - y0);
            File jobDir = OcrLite.prepareJobDir();
            File f = OcrLite.saveForOcr(crop, sc, new File(jobDir, "xlbtn.png"), OcrLite.MODE_ORIGINAL);
            List<OcrLite.Result> res = OcrLite.recognize(Collections.singletonList(f), OCR_TIMEOUT_S);
            int[] loose = null;
            for (OcrLite.Result r : res) {
                for (OcrLite.Line ln : r.lines) {
                    String t = ln.text.replace(" ", "").replace("\u3000", "");
                    if (t.isEmpty() || t.length() > 4) {
                        continue;
                    }
                    int wx = (int) Math.round((x0 + ln.cx() / (double) sc) / kx);
                    int wy = (int) Math.round((y0 + ln.cy() / (double) sc) / ky);
                    if (t.contains("寻路")) {
                        return new int[]{wx, wy};
                    }
                    if (loose == null && t.contains("寻")) {
                        loose = new int[]{wx, wy};
                    }
                }
            }
            return loose;
        } catch (Throwable t) {
            System.err.println("OCR 找「寻路」按钮失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 在面板标题栏区域找红色关闭按钮（X），返回窗口坐标；找不到返回 null。
     *
     * <p>规则：红色像素聚成的连通簇里，<b>最靠右、且宽 9~22 / 高 7~20</b> 的那个
     * 就是 X（旁边的「?」是灰的，面板外的红色装饰都又宽又扁）。这样即使面板被
     * 拖动过、位置漂移十几像素，也点得中。
     */
    int[] findRedCloseButton() {
        BufferedImage img = controller.captureWindow();
        if (img == null) {
            return null;
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x0 = Math.max(0, (int) Math.round(XL_CLOSE_X0 * kx));
        int x1 = Math.min(img.getWidth(), (int) Math.round(XL_CLOSE_X1 * kx));
        int y0 = Math.max(0, (int) Math.round(XL_CLOSE_Y0 * ky));
        int y1 = Math.min(img.getHeight(), (int) Math.round(XL_CLOSE_Y1 * ky));
        int w = x1 - x0, h = y1 - y0;
        if (w < 20 || h < 20) {
            return null;
        }
        boolean[] red = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x0 + x, y0 + y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                red[y * w + x] = r > 140 && (r - g) > 60 && (r - b) > 50 && g < 0.62 * r;
            }
        }
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int bestN = 0, bestCx = -1, bestCy = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < w * h; i++) {
            if (!red[i] || seen[i]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            int n = 0, minX = w, maxX = -1, minY = h, maxY = -1;
            long sx = 0, sy = 0;
            while (sp > 0) {
                int cur = stack[--sp];
                int cx = cur % w, cy = cur / w;
                n++;
                sx += cx;
                sy += cy;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                            continue;
                        }
                        int ni = ny * w + nx;
                        if (red[ni] && !seen[ni]) {
                            seen[ni] = true;
                            stack[sp++] = ni;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (n >= 40 && bw >= 9 && bw <= 22 && bh >= 7 && bh <= 20) {
                int ccx = (int) (sx / n), ccy = (int) (sy / n);
                int wx = (int) Math.round((x0 + ccx) / kx);
                int wy = (int) Math.round((y0 + ccy) / ky);
                // 预期 X 位置：(698,198) 随面板水平漂移（用实测 box1 推算）。
                // 右上角有小地图红绸带等装饰，不能「取最靠右」，要「取离预期最近的」。
                int ex = 698, ey = 198;
                if (lastNavBox != null) {
                    ex += lastNavBox[0] - 512;
                    ey += lastNavBox[1] - 457;
                }
                double dist = Math.hypot(wx - ex, wy - ey);
                if (bestN == 0 || dist < bestDist) {
                    bestDist = dist;
                    bestCx = ccx;
                    bestCy = ccy;
                    bestN = n;
                }
            }
        }
        if (bestN == 0) {
            return null;
        }
        int wx = (int) Math.round((x0 + bestCx) / kx);
        int wy = (int) Math.round((y0 + bestCy) / ky);
        return new int[]{wx, wy};
    }

    private void closeNavPanel() {
        for (int i = 1; i <= 3; i++) {
            if (!navPanelVisible()) {
                listener.log("  ✔ 「自动寻路」面板已关闭");
                return;
            }
            int[] x = findRedCloseButton();
            if (x == null) {
                x = PT_NAV_CLOSE;
                listener.log("  未定位到红色 X，退回固定坐标 (" + x[0] + "," + x[1] + ")");
            } else {
                listener.log("  定位到面板 X @ (" + x[0] + "," + x[1] + ")");
            }
            listener.log("  点击面板右上角 X（第 " + i + " 次）");
            controller.clickWindowPoint(x[0], x[1]);
            sleep(CLICK_MS);
        }
        if (navPanelVisible()) {
            String shot = snapshot("anomaly_nav_still_open");
            abort("「自动寻路」面板未能关闭",
                    "「自动寻路」面板一直没关掉。\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请手动关掉后重新点击「孝廉」。");
        }
    }

    /**
     * 点进坐标输入框 → 清空（截图验证真的空了）→ 输入数字（字形计数验证 + 自动纠正）。
     *
     * <p>「7 被填成 77」的最终修复：根因是后台 PostMessage 的 WM_KEYDOWN+WM_KEYUP
     * 被游戏翻译成<b>两个</b>字符（'7'→"77"），坐标框限长 2 位，后续输入被吞。
     * 现在：①输入走 WM_CHAR（一条消息一个字符，根上杜绝翻倍）；
     * ②输入后数「字形个数」——白像素总数分不清 "7"/"77"（都 ≈42），字形数能分清；
     * ③字形偏多就退格删掉、偏少就补上、一个没有就退回逐位按键（每打一位数一次，翻倍立刻退格），
     * 最多纠正 4 轮；仍不行整体重填，3 轮不行记日志软失败（下游到达校验会发现走错并自动重来）。
     */
    private void fillCoordBox(int[] pt, String text, String label) {
        for (int round = 1; round <= 3; round++) {
            checkStop();
            if (round > 1) {
                listener.log("  " + label + " 第 " + round + " 次尝试（点击稍微偏移重点）");
            }
            controller.moveToWindowPoint(PT_MOUSE_PARK[0], PT_MOUSE_PARK[1]);
            int wBefore = countBoxWhite(captureQuiet(), pt[0], pt[1]);
            if (round == 1 && wBefore > INK_EMPTY_MAX) {
                listener.log("  " + label + " 里有旧内容（白像素 " + wBefore + "），先清空再输入");
            }

            // 面板上下会漂 ~8px（框心 457~465 之间），横竖都要偏移才能兜住漂移
            int dx = (round == 2) ? -3 : (round == 3 ? 3 : 0);
            int dy = (round == 2) ? 8 : (round == 3 ? -8 : 0);
            controller.clickWindowPoint(pt[0] + dx, pt[1] + dy);
            sleep(500);

            controller.sendKeyNoFocus(WindowUtils.VK_END);
            sleep(120);
            controller.sendKeyNoFocus(WindowUtils.VK_END);
            sleep(120);
            for (int i = 0; i < 14; i++) {
                controller.sendKeyNoFocus(WindowUtils.VK_BACK);
                sleep(55);
            }
            sleep(250);

            // 验证清空：框内白像素要么归零，要么比清空前少了至少一个数字的量
            controller.moveToWindowPoint(PT_MOUSE_PARK[0], PT_MOUSE_PARK[1]);
            sleep(250);
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
            // 读数必须用「稳定帧」：拥挤场景游戏渲染延迟大，单帧验证会拿到过期画面——
            // 09:24 孝廉 88 事故：验证抓到单个 8 的旧帧判"通过"，实际画面已是翻倍的 88，
            // 角色被指路到不存在的坐标 (11,88)，点「移动」自然纹丝不动。
            boolean refocusRetried = false;
            for (int fix = 1; fix <= 4; fix++) {
                checkStop();
                controller.moveToWindowPoint(PT_MOUSE_PARK[0], PT_MOUSE_PARK[1]);
                sleep(200);
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
                        controller.sendKeyNoFocus(WindowUtils.VK_BACK);
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
        listener.log("  ⚠ " + label + " 三次都没稳妥填进去，现场截图：" + shot
                + "（下游到达校验会发现走错并自动重来）");
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
                    controller.sendKeyNoFocus(WindowUtils.VK_BACK);
                    sleep(90);
                }
                sleep(200);
            }
        }
    }

    /**
     * 稳定帧读数：连抓两帧（间隔 ~450ms），两帧的（白像素, 字形数）完全一致才采信。
     * 拥挤场景（军团活动时的罗城等）游戏渲染延迟大，PrintWindow 会拿到过期画面——
     * 09:24 孝廉事故：单帧验证抓到 "8" 的旧帧判通过，实际画面已是翻倍的 "88"，
     * 角色被指路到不存在的坐标点不动。连续 4 轮都不一致返回 null（调用方按不可靠处理）。
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
     * 两框都填完后终检：字形数必须恰好等于目标位数（防 7→77、防串框如 20 被补成 207）。
     *
     * <p><b>2026-09-22 00:19 实车教训</b>：第二段寻路 (11,8) 两个框各自填入时都通过了
     * 「双帧一致」验证，但终检碰上渲染延迟、连续两轮读数不稳，旧代码直接中止了整个任务
     * ——「移动」按钮根本没机会点（用户看到的正是：坐标填进去了却不点移动）。
     * 现在<b>「读数不稳」只重试、永不中止</b>：超过重试轮数就放行去点「移动」，
     * 万一真填错，下游 clickMoveUntilWalking（角色没动会带偏移重点）和到达校验
     * （±1 坐标比对，不对整段寻路重来）会兜底。只有「读数稳定但内容确实不对」
     * 且重填一次后仍不对才停止 —— 那才是真填错。
     */
    private void verifyBothBoxes(int[] b1, int[] b2, String x, String y) {
        boolean refilled = false;
        for (int round = 1; round <= 4; round++) {
            checkStop();
            int[] s1 = stableBoxCounts(b1[0], b1[1]);
            int[] s2 = stableBoxCounts(b2[0], b2[1]);
            if (s1 == null || s2 == null) {
                listener.log("  ⚠ 坐标终检读数不稳（渲染延迟大），等 1s 重试（第 " + round + "/4 轮）");
                sleep(1000);
                continue;
            }
            int g1 = s1[1], w1 = s1[0];
            int g2 = s2[1], w2 = s2[0];
            // 单个 "8" 只有 ~24 白像素，门槛按位数 9/位（旧门槛 25 会误杀单 8）
            boolean ok1 = g1 < 0 || (g1 == x.length() && w1 >= Math.max(8, 9 * x.length()));
            boolean ok2 = g2 < 0 || (g2 == y.length() && w2 >= Math.max(8, 9 * y.length()));
            if (ok1 && ok2) {
                listener.log("  ✔ 坐标终检通过：框1=" + x + "（字形 " + g1
                        + "）、框2=" + y + "（字形 " + g2 + "），双帧一致");
                return;
            }
            if (!refilled) {
                refilled = true;
                listener.log("  ⚠ 坐标终检异常（框1 字形=" + g1 + "/应为 " + x.length()
                        + "，框2 字形=" + g2 + "/应为 " + y.length() + "），重新填写异常的框");
                if (!ok1) {
                    fillCoordBox(b1, x, "第 1 个坐标框");
                }
                if (!ok2) {
                    fillCoordBox(b2, y, "第 2 个坐标框");
                }
                continue;
            }
            // 重填后读数「稳定但仍然不对」—— 这是真填错了，带截图停止
            String shot = snapshot("anomaly_coords_final");
            abort("坐标终检未通过",
                    "重填后两个坐标框的内容仍确认不对，为避免寻路找错位置已停止。\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请看截图里「坐标」后面的两个框，手动确认后重新开始任务。");
        }
        // 连续 4 轮读数都不稳 —— 不中止。两框在各自填入时均已逐框验证（双帧一致），
        // 放行去点「移动」；真填错由下游移动检测 + 到达校验兜底。
        listener.log("  ⚠ 坐标终检连续 4 轮读数不稳，不再纠缠 —— 两框填入时均已验证过，"
                + "继续点「移动」（角色移动检测 + 到达校验兜底）");
    }

    /** 到达 (20,16) 后等 2 秒，按「↑」换到 NPC 所在的地图。按前后读坐标对比，没换图补按一次。 */
    void goUpToNpcMap() {
        listener.log("—— 导航：到达 (" + COORD_A_X + "," + COORD_A_Y + ")，等 "
                + (UP_DELAY_MS / 1000) + " 秒后按「↑」进入 NPC 所在地图 ——");
        sleep(UP_DELAY_MS);
        int[] before = readMapCoordsOnce();
        if (before != null) {
            listener.log("  按「↑」前角色在 (" + before[0] + "," + before[1] + ")");
        }
        boolean switched = false;
        for (int k = 1; k <= 2 && !switched; k++) {
            if (k > 1) {
                listener.log("  再按一次「↑」（第 2 次）");
            }
            controller.sendKey(WindowUtils.VK_UP);
            sleep(MAP_LOAD_MS);
            int[] now = readMapCoordsOnce();
            if (before == null || now == null) {
                listener.log("  坐标条读不出来，无法确认是否换图，按已切换继续");
                switched = true;
            } else if (now[0] != before[0] || now[1] != before[1]) {
                listener.log("  ✔ 已换图：" + before[0] + "," + before[1]
                        + " → " + now[0] + "," + now[1]);
                switched = true;
            } else if (k == 1) {
                listener.log("  ⚠ 按「↑」后坐标没变，可能没换上图，重试");
                snapshot("nav_up_no_effect");
            }
        }
        if (!switched) {
            listener.log("  ⚠ 按了两次「↑」坐标都没变 —— 后续寻路 ("
                    + COORD_B_X + "," + COORD_B_Y + ") 若失败多半是这个原因");
            snapshot("anomaly_up_no_effect");
        }
        snapshot("03_after_up");
        listener.log("  ✔ 「↑」步骤完成，等地图加载稳定");
    }

    // ==================== 接任务 ====================

    /**
     * G 唤起对话 → 「对话/任务」→ 第二个对话框选「推举孝廉」。
     *
     * <p>优先用 OCR 找到对应文字的那一行直接点它；找不到就用键盘兜底
     * （第一层直接回车，第二层先按下方向键再回车 —— 第 1 项默认高亮）。
     */
    void acceptQuest() {
        listener.log("—— 对话：G 唤起 NPC，接取「" + mode.label() + "」——");

        if (!pressGUntilDialog("诰令司丞", G_MAX_TRIES)) {
            String shot = snapshot("anomaly_accept_dialog_missing");
            abort("未能唤起 NPC 对话",
                    "连续按了 " + G_MAX_TRIES + " 次 G 都没出现带选项的对话，流程已停止。\n\n"
                            + "常见原因：\n"
                            + "  ① 角色没站在成都「诰令司丞」面前（孝廉按钮已不再自动寻路）\n"
                            + "  ② 站得太远 / 中间隔了人，走进可对话范围再试\n"
                            + "  ③ 有弹窗遮挡\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请处理后重新点击「" + mode.label() + "」。");
        }

        // 第一层：「对话/任务」（两个任务都一样）
        chooseDialogOption(new String[]{"对话任务", "对话与任务", "对话"}, 1, "对话/任务");
        sleep(STEP_MS);

        if (mode == Mode.YUNSONG) {
            acceptYunsongQuest();
            return;
        }
        acceptXiaolianQuest();
    }

    /**
     * 运送物资：第二层选「运送物资」就接下来了。
     *
     * <p>不像孝廉还要问「要参加吗」。但<b>接完还没完</b> —— 还要再对话一轮
     * （见 {@link #finishYunsongQuest()}）才算真正完成。
     */
    private void acceptYunsongQuest() {
        // 第二层：「运送物资」—— 用户说这一层可能有多个选项（赤兔追风化元神 / 获取精魄…），
        //   所以必须按文字找，不能靠「默认高亮就是它」。
        clickMenuOption(YS_KW_TRANSPORT, "运送物资");
        // 第三层：「请交给我吧」（NPC 会问「有一份重要的差事正需要你的协助呢！」）
        clickMenuOption(YS_KW_ACCEPT, "请交给我吧");
        snapshot("05_quest_accepted");
        listener.log("  ✔ 「运送物资」任务已接取（接下来还要再对话一轮才算完成）");
    }

    /** 孝廉：第二层选「推举孝廉」→ 再确认参加 → 直到题目框出现。 */
    private void acceptXiaolianQuest() {
        // 第二层：「推举孝廉」
        chooseDialogOption(new String[]{"推举孝廉", "举荐孝廉", "孝廉"}, 2, "推举孝廉");
        sleep(STEP_MS);

        // 第三层：确认参加答题。
        //   选完「推举孝廉」后 NPC 还会问一句「怎么样，要参加吗？」，下面是
        //   「请交给我吧 / 我暂时没空」两个选项，第一项默认高亮 —— 必须点它。
        //   关键词只用「请交给」，它对 OCR 常见的漏字（请交给吧）也能命中，且不会误伤 NPC 正文。
        chooseDialogOption(new String[]{"请交给我吧", "请交给", "交给我吧"}, 1, "确认参加");
        sleep(STEP_MS);
        snapshot("05_quest_accepted");
        listener.log("  ✔ 任务已接取");

        // 第四层：真正进入答题。
        //   实测：第一轮「推举孝廉」+「请交给我吧」只是把任务接下来（右侧任务栏出现「推举孝廉」），
        //   要再对话一轮（G → 对话/任务 → 推举孝廉）才会弹出题目框。
        for (int i = 1; i <= 3 && !quizVisible(); i++) {
            listener.log("  题目框还没出现，再对话一轮进入答题（第 " + i + "/3 次）");
            if (!pressGUntilDialog("诰令司丞", G_MAX_TRIES)) {
                break;
            }
            chooseDialogOption(new String[]{"对话任务", "对话与任务", "对话"}, 1, "对话/任务");
            sleep(STEP_MS);
            chooseDialogOption(new String[]{"推举孝廉", "举荐孝廉", "孝廉"}, 1, "推举孝廉");
            sleep(STEP_MS);
        }
        if (!quizVisible()) {
            String shot = snapshot("anomaly_quiz_missing");
            abort("没能进入答题",
                    "接取任务后反复对话都没出现题目框。\n\n"
                            + "可能原因：今天机会已用完 / 活力不足 / 界面卡住。\n\n"
                            + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                            + "请确认后重新点击「孝廉」。");
        }
        listener.log("  ✔ 已进入答题环节");
    }

    /**
     * 题号匾「第 N 题」的匹配模式。
     *
     * <p><b>实测（2026-09-26 07:02 事故）</b>：这行大字可能被 OCR 拆成两行同行碎片
     * ——「第」@ (269,117) + 「1题」@ (372,119)（同一次跑里昨天帧读成整行「第1题」、
     * 今天帧拆成两半，精简版/一般版客户端渲染都有可能）。所以不能只判「同一行同时含
     * 第+题」，还要兼容碎片。
     */
    private static final Pattern QUIZ_NO_P = Pattern.compile("第?[0-9]{1,2}题");

    /** 题目框是否已经出现在画面上（靠「倒计时 / 第 N 题」这两个标志词判断）。 */
    private boolean quizVisible() {
        try {
            ReadResult rr = readScreenQuiz("quiz_probe");   // 答题模式：只看用户给的两个红框
            if (quizNoPresent(rr.lines)) {
                return true;
            }
            // 漏判时把读到的行打进日志 —— 以前这里是静默的，出了事只能靠离线探针复盘
            if (listener != null) {
                StringBuilder sb = new StringBuilder("  [答题探测] 没认出题号匾，OCR 读到");
                int k = 0;
                for (OcrLite.Line l : rr.lines) {
                    String n = XiaolianBank.normalize(l.text);
                    if (n.isEmpty()) {
                        continue;
                    }
                    if (k == 8) {
                        sb.append(" …");
                        break;
                    }
                    sb.append(k == 0 ? "：" : "、").append("「").append(n).append("」");
                    k++;
                }
                if (k == 0) {
                    sb.append(" 0 行有效文字");
                }
                listener.log(sb.toString());
            }
        } catch (Throwable t) {
            if (listener != null) {
                listener.log("  [答题探测] OCR 异常（" + t + "）");
            }
        }
        return false;
    }

    /** 题号匾是否在 OCR 行里：整行命中，或同行碎片（按 y 分桶、按 x 排序拼接）拼回后命中。包级可见供离线探针回归。 */
    static boolean quizNoPresent(List<OcrLite.Line> lines) {
        if (lines == null) {
            return false;
        }
        for (OcrLite.Line l : lines) {
            String n = XiaolianBank.normalize(l.text);
            if (n.contains("倒计时") || (n.length() <= 6 && QUIZ_NO_P.matcher(n).find())) {
                return true;
            }
        }
        // 「第 N 题」拆成同行碎片的情形：把中点 y 差 ≤ 10px 的行按 x 排序拼起来再判
        int n = lines.size();
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (used[i]) {
                continue;
            }
            OcrLite.Line li = lines.get(i);
            int cyi = (li.y0 + li.y1) / 2;
            List<OcrLite.Line> row = new ArrayList<>();
            row.add(li);
            for (int j = i + 1; j < n; j++) {
                if (used[j]) {
                    continue;
                }
                OcrLite.Line lj = lines.get(j);
                if (Math.abs((lj.y0 + lj.y1) / 2 - cyi) <= 10) {
                    row.add(lj);
                    used[j] = true;
                }
            }
            if (row.size() >= 2) {
                Collections.sort(row, (a, b) -> Integer.compare(a.x0, b.x0));
                StringBuilder sb = new StringBuilder();
                for (OcrLite.Line l : row) {
                    sb.append(XiaolianBank.normalize(l.text));
                }
                String m = sb.toString();
                if (m.length() <= 6 && QUIZ_NO_P.matcher(m).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 在当前对话框里选一项。
     *
     * @param keywords      候选关键词（归一化后包含即算命中）
     * @param fallbackIndex 键盘兜底时选第几项（1 基）
     */
    private void chooseDialogOption(String[] keywords, int fallbackIndex, String what) {
        try {
            List<String> norm = new ArrayList<>();
            for (String k : keywords) {
                norm.add(XiaolianBank.normalize(k));
            }
            ReadResult rr = readScreen("dialog_" + fallbackIndex);
            for (OcrLite.Line ln : rr.lines) {
                String n = XiaolianBank.normalize(ln.text);
                if (n.isEmpty()) {
                    continue;
                }
                for (String k : norm) {
                    if (k.isEmpty()) {
                        continue;
                    }
                    if (n.contains(k) || XiaolianBank.similarity(n, k) >= 0.72) {
                        listener.log("  识别到「" + what + "」：\"" + ln.text + "\" @ ("
                                + ln.cx() + "," + ln.cy() + ")，直接点它");
                        controller.clickWindowPoint(ln.cx(), ln.cy());
                        return;
                    }
                }
            }
            listener.log("  没在画面上找到「" + what + "」字样，改用键盘："
                    + (fallbackIndex <= 1 ? "回车" : "↓×" + (fallbackIndex - 1) + " + 回车"));
        } catch (Throwable t) {
            listener.log("  OCR 找「" + what + "」失败（" + t + "），改用键盘操作");
        }

        for (int i = 1; i < fallbackIndex; i++) {
            controller.sendKey(WindowUtils.VK_DOWN);
            sleep(300);
        }
        controller.sendKey(WindowUtils.VK_RETURN);
        sleep(CLICK_MS);
    }

    /** 反复按 G 直到出现「带选项的对话」（首项高亮条）。 */
    private boolean pressGUntilDialog(String what, int maxTries) {
        for (int i = 1; i <= maxTries; i++) {
            checkStop();
            SalaryTask.Ui ui = SalaryTask.scanImage(controller.captureWindow());
            if (ui.dialogOpen) {
                listener.log("  ✔ 已检测到「" + what + "」对话（首项 y=" + ui.option1Y + "）");
                return true;
            }
            listener.log("  第 " + i + "/" + maxTries + " 次按 G 尝试唤起「" + what + "」对话");
            controller.sendKey(WindowUtils.VK_G);
            sleep(DIALOG_WAIT_MS);
        }
        return false;
    }

    // ==================== 答题主循环 ====================

    /** 答题循环，返回实际作答的题数。 */
    int quizLoop() {
        listener.log("—— 答题：开始（最多 " + QUIZ_MAX_Q + " 题）——");
        int answered = 0;
        int bankHits = 0;
        int aiHits = 0;
        int missStreak = 0;
        long t0 = System.currentTimeMillis();

        // 用 while 而不是 for：答完一题会弹出「恭喜你，答对了 / 答错了 + 下一题」结算框，
        // 需要先点掉「下一题」才算真正翻页，所以「读屏」与「作答」的轮次不等价。
        int round = 0;
        while (answered < QUIZ_MAX_Q && round < QUIZ_MAX_Q * 3) {
            checkStop();
            round++;
            sleep(NEXT_Q_WAIT_MS);

            // ---- 上一题答错会弹「快捷购买」窗，盖住题面 → 先点掉，再读屏 ----
            //   否则读到的全是弹窗文字，会白白累积 missStreak，最后误判「答题已结束」。
            if (answered > 0 && dismissBuyDialogIfPresent()) {
                listener.log("    已点「取消」关掉上一题残留的购买弹窗");
                sleep(NEXT_Q_WAIT_MS);
            }

            ReadResult rr = readScreenQuiz("r" + pad2(round) + "_q" + pad2(answered + 1));
            if (rr.lines.isEmpty()) {
                missStreak++;
                listener.log("  第 " + (answered + 1) + " 题：画面上没读到文字（连续 "
                        + missStreak + " 次，轮次 " + round + "）");
                if (missStreak >= 3) {
                    listener.log("  ✔ 连续多次读不到文字，判定答题已结束");
                    break;
                }
                continue;
            }
            missStreak = 0;

            if (looksLikeCaptcha(rr)) {
                String shot = snapshot("anomaly_captcha");
                stopRequested = true;
                listener.alert("孝廉 · 需要人工操作",
                        "检测到画面里出现「验证码 / 请输入」字样。\n\n"
                                + "请立刻在游戏里手动完成验证码并继续答题。\n\n"
                                + "OCR 读到的内容：\n" + rr.joined() + "\n\n"
                                + (shot != null ? "现场截图：" + shot + "\n" : ""));
                throw new Abort("出现验证码，已交还人工处理");
            }

            // ---- 优先处理上一题的结算弹窗：找到「下一题」就点掉翻页 ----
            //   clickNextIfPresent 内部已含「颜色找橙色按钮 → OCR 找文字 → 结算文字兜底」
            //   三重判据，返回 true 就说明它已经点了（按钮或兜底位置）。
            if (clickNextIfPresent(rr)) {
                listener.log("    已翻到下一题（第 " + (answered + 1) + " 题）");
                continue;
            }

            Decision d = null;
            for (int attempt = 1; attempt <= 2 && d == null; attempt++) {
                d = decide(answered + 1, rr);
                if (d == null && attempt < 2) {
                    // 可能正卡在动画/过渡帧上，稍微等一下再读一次
                    listener.log("    第 " + (answered + 1) + " 题没读出结果，稍等重读一次…");
                    sleep(900);
                    rr = readScreenQuiz("r" + pad2(round) + "_retry");
                    if (clickNextIfPresent(rr)) {
                        break;      // 重读时发现是结算框，交给下一轮点「下一题」
                    }
                }
            }
            if (d == null) {
                String shot = snapshot("anomaly_no_decision");
                stopRequested = true;
                listener.alert("孝廉 · 需要人工操作",
                        "第 " + (answered + 1) + " 题没能确定答案（题库没匹配上、大模型也没给出结果）。\n\n"
                                + "请手动作答，或者中止后重新点击「孝廉」。\n\n"
                                + "OCR 读到的内容：\n" + rr.joined() + "\n\n"
                                + (shot != null ? "现场截图：" + shot + "\n" : ""));
                throw new Abort("第 " + (answered + 1) + " 题无法确定答案");
            }

            if ("题库".equals(d.source)) {
                bankHits++;
            } else {
                aiHits++;
            }

            listener.log("  第 " + (answered + 1) + " 题 [" + d.source + "，置信度 "
                    + String.format("%.2f", d.score) + "]" + (d.dryRun ? "（演练，不点）" : ""));
            listener.log("    题干：" + shorten(d.question, 60));
            listener.log("    答案：" + shorten(d.answerText, 30)
                    + "  →  选项 \"" + shorten(d.optionLine.text, 24) + "\" @ ("
                    + d.optionLine.cx() + "," + d.optionLine.cy() + ")");

            if (dryRun) {
                answered++;
                continue;
            }

            // ---- 点选项：答题框位置固定，y 直接吸附到 A/B/C/D 四行之一，比点 OCR 框中心稳 ----
            int row = optionRowIndex(d.optionLine.cy());
            int cx = d.optionLine.cx();
            if (cx < 280 || cx > 480) {
                cx = OPT_CLICK_X;
            }
            int cy = row >= 0 ? OPT_ROW_Y[row] : d.optionLine.cy();
            listener.log("    点击选项 " + (row >= 0 ? "第 " + (char) ('A' + row) + " 行" : "（按 OCR 位置）")
                    + " @ (" + cx + "," + cy + ")");
            controller.clickWindowPoint(cx, cy);
            answered++;
            sleep(AFTER_PICK_MS);

            // ---- 先查「快捷购买」弹窗：答题失败会弹它，不点掉会卡死流程 ----
            //   失败时游戏提示「您可以购买下列道具继续完成操作」，右下角有「取消」。
            //   不处理的话：① 弹窗挡住画面，OCR 读不到东西；② 后面按 G 对话无效。
            if (dismissBuyDialogIfPresent()) {
                listener.log("    已点「取消」关掉购买弹窗（这题答错了）");
                sleep(AFTER_PICK_MS);
            }

            // ---- 答完立刻找「下一题」并点掉，省下一轮的等待 ----
            //   坑一：不是每题都弹结算框（答对/答错表现可能不同，也可能直接翻页）。
            //        没弹框时绝不能硬按回车 —— 那一下会打到新题目界面上误选一个选项。
            //   坑二：弹了框，但 OCR 认不出白字压在橙色按钮上的「下一题」三个字。
            //        所以 clickNextIfPresent 优先用颜色特征找那个橙色按钮，
            //        OCR 只是备选 —— 只要弹窗在，橙色按钮就一定在。
            //   两个坑都堵住后：它返回 true 就是真点了；返回 false 说明画面既没有
            //   结算框也没有按钮，此时安静等下一轮读屏，不乱按任何键。
            ReadResult again = readScreenQuiz("r" + pad2(round) + "_after");
            if (clickNextIfPresent(again)) {
                listener.log("    已点「下一题」，继续下一题");
            } else {
                listener.log("    这题没出现结算框（或已自动翻页），不补回车，等下一轮");
            }
        }

        long cost = System.currentTimeMillis() - t0;
        listener.log("—— 答题结束：共 " + answered + " 题，题库命中 " + bankHits
                + " 题，大模型兜底 " + aiHits + " 题，耗时 " + (cost / 1000.0) + " 秒 ——");
        return answered;
    }

    /**
     * 把屏幕上的某一行按 y 归到 A/B/C/D 的第几行。
     *
     * <p><b>⚠ 2026-10-01 放宽容差（血泪）</b>：原来写死「离最近的中心 ≤14px」，
     * 但四行真实中心是 358/387/416/445、<b>行距只有 29px</b>，而 OCR 给的行框中心
     * 会因为「多认/漏认一两个字符」整体上下飘 5~15px（实机实锤：A 行的 {@code G}
     * 被读成 {@code 0回} 后框中心落到 y=388，比真实 A 中心 358 低 30px）。
     * 容差 14 太窄 ⇒ 这种整行读歪的情况全被判到<b>相邻行</b>上，两行打架丢一行。
     *
     * <p>改法：<b>半行距（14px）分界</b>——落在相邻两行中线上就归到更近的那一行
     * （等价于以 358/387/416/445 为种子做最近邻分区）。行距 29 ⇒ 每行管 ±14px 的
     * 上下各 14.5px 带，正好铺满不留缝。再叠一个「整屏四行之外」的硬边界：
     * 落在最上行之上 20px / 最下行之下 20px 之外的不归行（表格/正文跑进来的字）。
     *
     * @return 0..3；偏差太大、归不进去返回 -1
     */
    private static int optionRowIndex(int cy) {
        int best = -1;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < OPT_ROW_Y.length; i++) {
            int d = Math.abs(cy - OPT_ROW_Y[i]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        // 半行距分界：最近邻距离超过「行距的一半」就算出界，不硬塞给某一行。
        // 行距 29 ⇒ 上限 14（与旧值一致）；这样既铺满行间空隙，又不会把远处
        // 的题干/表格行吸进选项行。
        return bestD <= OPT_ROW_HALF ? best : -1;
    }

    /**
     * 归行的最大允许偏差（= 行距 29 的一半，向上取整到 15 留 1px 余量）。
     *
     * <p>历史上这个值写死 14，配合偏心的旧中心坐标时把「A 行读歪 30px」的框
     * 甩给了 B 行。现在中心已按实测校正，15px 的分界正好卡在相邻两行的中线，
     * 读歪 5~15px 的整行不会再串行。
     */
    private static final int OPT_ROW_HALF =
            (OPT_ROW_Y[1] - OPT_ROW_Y[0] + 1) / 2 + 1;

    /** 是否是「倒计时数字」区域（那里的数字不是选项）。 */
    private static boolean inCountdownArea(int cx, int cy) {
        return cx >= CD_X0 && cx <= CD_X1 && cy >= CD_Y0 && cy <= CD_Y1;
    }

    /** 与题目无关的界面标签（倒计时、道具数、免错锦囊…），识别到就直接丢弃。 */
    private static boolean isUiNoise(String text) {
        String n = XiaolianBank.normalize(text);
        return n.contains("倒计时") || n.contains("拥有道具")
                || n.contains("免错") || n.contains("锦囊");
    }

    /**
     * 画面上是否有「结算框」—— 在<strong>固定的按钮位置</strong>找那个橙色「下一题」按钮，
     * 不依赖 OCR。
     *
     * <p>用户实测确认「下一题」按钮每次都固定出现（中心 417,343），所以直接在
     * {@link #NQ_X0}..{@link #NQ_Y1} 这个小框里找橙色像素即可，准且快。
     * 白字压橙色渐变 OCR 认不出「下一题」三个字，但橙色像素特征极稳。
     *
     * <p>找到就返回按钮中心坐标（比固定值更贴合实际渲染位置）；没找到返回 null。
     */
    private int[] findNextQuestionButton() {
        BufferedImage img = captureQuiet();
        if (img == null) {
            return null;
        }
        int x1 = Math.min(NQ_X1, img.getWidth() - 1);
        int y1 = Math.min(NQ_Y1, img.getHeight() - 1);
        long sumX = 0, sumY = 0;
        int count = 0;
        int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
        for (int y = Math.max(0, NQ_Y0); y <= y1; y++) {
            for (int x = Math.max(0, NQ_X0); x <= x1; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (r > 170 && r - g > 60 && g - b > 20) {
                    count++;
                    sumX += x;
                    sumY += y;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (count < NQ_MIN_COUNT) {
            return null;
        }
        // 用「橙色像素重心」定位：比几何外接矩形中心更抗边缘噪点
        int cx = (int) Math.round(sumX / (double) count);
        int cy = (int) Math.round(sumY / (double) count);
        listener.log("    检测到「下一题」按钮（" + count + " px）@ (" + cx + "," + cy + ")");
        return new int[]{cx, cy};
    }

    /**
     * 答题失败时会弹出「快捷购买」弹窗（提示「您可以购买下列道具继续完成操作」）。
     *
     * <p>检测方法：找弹窗底部那行橙色按钮。布局是「确认支付」「取消」左右并排，
     * 取<b>最靠右</b>的那一块就是「取消」。找不到返回 null。
     *
     * <p>补充判据：弹窗主体（深蓝面板）必须存在，避免把别处的橙色误判成按钮。
     */
    int[] findBuyCancelButton() {
        BufferedImage img = captureQuiet();
        if (img == null) {
            return null;
        }
        int x1 = Math.min(BUY_X1, img.getWidth() - 1);
        int y1 = Math.min(BUY_Y1, img.getHeight() - 1);
        int baseX = Math.max(0, BUY_X0);

        // 逐列统计按钮行里的橙色像素（按钮高约 21 px，实心列能达到十几）
        int[] colCount = new int[x1 - baseX + 1];
        for (int y = Math.max(0, BUY_Y0); y <= y1; y++) {
            for (int x = baseX; x <= x1; x++) {
                if (isBuyButtonOrange(img.getRGB(x, y))) {
                    colCount[x - baseX]++;
                }
            }
        }

        // 收集「密集列块」（连续若干列橙量都达标 → 那是一整块按钮）
        java.util.List<int[]> blocks = new java.util.ArrayList<>();
        int cur0 = -1, cur1 = -1;
        for (int i = 0; i < colCount.length; i++) {
            if (colCount[i] >= BUY_COL_MIN) {
                if (cur0 < 0) {
                    cur0 = baseX + i;
                }
                cur1 = baseX + i;
            } else if (cur0 >= 0) {
                if (cur1 - cur0 + 1 >= BUY_BLOCK_MIN_W) {
                    blocks.add(new int[]{cur0, cur1});
                }
                cur0 = cur1 = -1;
            }
        }
        if (cur0 >= 0 && cur1 - cur0 + 1 >= BUY_BLOCK_MIN_W) {
            blocks.add(new int[]{cur0, cur1});
        }

        // ---- 指纹判据：必须恰好两块，且位置分别落在实测的「确认支付 / 取消」上 ----
        //   这样背景里零星橙点凑出的杂块、以及其他界面元素都会被排除。
        if (blocks.size() != 2) {
            return null;
        }
        int[] pay = blocks.get(0);
        int[] cxl = blocks.get(1);
        if (Math.abs(pay[0] - BUY_PAY_X0) > 6 || Math.abs(pay[1] - BUY_PAY_X1) > 6) {
            return null;
        }
        if (Math.abs(cxl[0] - BUY_CX0) > 6 || Math.abs(cxl[1] - BUY_CX1) > 6) {
            return null;
        }

        // 纵向：在「取消」的 x 范围内取橙色像素的 y 质心
        int bx0 = cxl[0], bx1 = cxl[1];
        int sumY = 0, cnt = 0;
        for (int y = Math.max(0, BUY_Y0); y <= y1; y++) {
            for (int x = bx0; x <= bx1; x++) {
                if (isBuyButtonOrange(img.getRGB(x, y))) {
                    cnt++;
                    sumY += y;
                }
            }
        }
        if (cnt < 60) {
            return null;
        }
        int cx = (bx0 + bx1) / 2;
        int cy = (int) Math.round(sumY / (double) cnt);
        listener.log("    检测到「快捷购买」弹窗：「取消」按钮（" + cnt + " px，x "
                + bx0 + "~" + bx1 + "）@ (" + cx + "," + cy + ")");
        return new int[]{cx, cy};
    }

    /**
     * 「快捷购买」弹窗按钮的橙色判定。
     *
     * <p>实测两个按钮都是橙/琥珀渐变（RGB 大致 (214,140,0)~(248,205,118)），
     * 特征是 R 高、G 中等、B 很低。比「下一题」按钮的判据更宽松一点，
     * 因为按钮上有高光白条和深色描边，需要容忍较大的明度跨度。
     */
    private static boolean isBuyButtonOrange(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        return r > 140 && g > 60 && b < 130 && r - b > 60;
    }

    /**
     * 收尾/翻页前统一处理「快捷购买」弹窗：<b>答题失败就会弹它</b>，
     * 必须点「取消」关掉，否则后面按 G 对话会被这个框挡住。
     *
     * <p>检测不到就什么都不做（不是失败，只是没弹框）。
     *
     * @return 是否处理了购买弹窗
     */
    boolean dismissBuyDialogIfPresent() {
        for (int i = 1; i <= 2; i++) {
            int[] cancel = findBuyCancelButton();
            if (cancel == null) {
                return i > 1;
            }
            listener.log("  ⚠ 检测到「快捷购买」弹窗（答题失败），点「取消」关闭（第 " + i + " 次）");
            controller.clickWindowPoint(cancel[0], cancel[1]);
            sleep(CLICK_MS);
        }
        // 点了两次还在 → 用固定位置兜底再点一次
        int[] stillThere = findBuyCancelButton();
        if (stillThere != null) {
            listener.log("  ⚠ 「取消」点了两次弹窗还在，改用固定位置 @ ("
                    + PT_BUY_CANCEL[0] + "," + PT_BUY_CANCEL[1] + ")");
            controller.clickWindowPoint(PT_BUY_CANCEL[0], PT_BUY_CANCEL[1]);
            sleep(CLICK_MS);
        }
        snapshot("buy_dialog_dismissed");
        return true;
    }

    /**
     * 画面上是否有「结算框」（橙色「下一题」按钮在，或读到结算文字）。
     *
     * <p>优先看颜色特征（{@link #findNextQuestionButton}）—— OCR 认不出按钮文字时
     * 它仍然可靠。文字判据作为补充（万一按钮被特效遮住）。
     * 用来区分「这一题弹了结算框」和「这一题没弹、直接翻页」两种情况 ——
     * 没有结算框时绝不能补按回车，否则回车会打到新题目上误选选项。
     */
    private boolean settleTextPresent(ReadResult rr) {
        if (findNextQuestionButton() != null) {
            return true;
        }
        if (rr == null) {
            return false;
        }
        for (OcrLite.Line ln : rr.lines) {
            String n = XiaolianBank.normalize(ln.text);
            if (n.contains("恭喜") || n.contains("答对") || n.contains("答错")
                    || n.contains("回答正确") || n.contains("回答错误")
                    || n.contains("正确答案") || n.contains("答案")
                    || n.contains("超时") || n.contains("机会已用完")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 结算弹窗处理：找「下一题」按钮并点它。返回是否真的点了。
     *
     * <p>三重判据，从最可靠到最不可靠：
     * <ol>
     *   <li><b>颜色找橙色按钮</b>（{@link #findNextQuestionButton}）—— 最可靠，
     *       白字 OCR 认不出「下一题」时这条仍然管用；</li>
     *   <li>OCR 行里找到「下一题」文字，直接点它；</li>
     *   <li>只读到结算文字却没有按钮证据时，退到固定位置 PT_NEXT_Q。</li>
     * </ol>
     */
    private boolean clickNextIfPresent(ReadResult rr) {
        // ---- 1. 颜色找橙色按钮（最可靠） ----
        int[] byColor = findNextQuestionButton();
        if (byColor != null) {
            controller.clickWindowPoint(byColor[0], byColor[1]);
            return true;
        }

        // ---- 2. OCR 找「下一题」文字 ----
        OcrLite.Line hit = null;
        boolean settle = false;
        if (rr != null) {
            for (OcrLite.Line ln : rr.lines) {
                String n = XiaolianBank.normalize(ln.text);
                if (n.contains("下一题") || n.contains("下一題")) {
                    hit = ln;
                } else if (n.contains("恭喜") || n.contains("答对") || n.contains("答错")
                        || n.contains("回答正确") || n.contains("回答错误")
                        || n.contains("正确答案") || n.contains("超时")) {
                    settle = true;
                }
            }
        }
        if (hit != null) {
            listener.log("    检测到「" + hit.text.trim() + "」@ (" + hit.cx() + "," + hit.cy() + ")，点击");
            controller.clickWindowPoint(hit.cx(), hit.cy());
            return true;
        }
        if (settle) {
            listener.log("    只读到结算文字、没找到按钮证据，点固定位置 @ ("
                    + PT_NEXT_Q[0] + "," + PT_NEXT_Q[1] + ")");
            controller.clickWindowPoint(PT_NEXT_Q[0], PT_NEXT_Q[1]);
            return true;
        }
        return false;
    }

    /**
     * 收尾：把右下的「下一题」结算框清掉，补齐最后一步交付。
     *
     * <p>真实的孝廉流程在答完最后一题后并<b>没有结束</b> —— 还要再按一次 G 和 NPC
     * 对话、在弹出的对话框里点一下（「交任务」那一项），任务才算真正交付、
     * 奖励才到手。旧代码只连按 3 次回车就收工，任务挂在身上没交掉。
     *
     * <p>步骤：
     * <ol>
     *   <li>先把残余的「下一题 / 结算」弹窗点掉，回到能按 G 的干净画面；</li>
     *   <li>按 G 唤起 NPC 对话（复用接任务时那套「对话/任务 → 推举孝廉」菜单）；</li>
     *   <li>逐层把「交任务 / 交付 / 完成 / 领取 / 谢谢」这类选项点掉，直到对话框消失；</li>
     *   <li>校验对话框确实关掉了，没关掉就截图留证（不 abort，避免任务其实已交付却报错）。</li>
     * </ol>
     */
    private void finishDialogs() {
        listener.log("—— 收尾：清结算框 → 再对话一次交任务 ——");

        // ---- 0. 先清「快捷购买」弹窗 ----
        //   最后一题答错时，这个弹窗会一直停在画面上，挡住后面按 G 的对话。
        //   用户实测确认：点掉它的「取消」，再按 G 对话就能正常结束。
        if (dismissBuyDialogIfPresent()) {
            listener.log("  ✔ 已关掉「快捷购买」弹窗（最后一题答错了）");
            sleep(STEP_MS);
        }

        // ---- 1. 清掉「下一题 / 结算」弹窗 ----
        //   注意：不是每题都弹结算框（答对/答错表现可能不同，也可能直接翻页），
        //   所以这里必须先确认「确实有结算框」才动手：
        //   ① 首选颜色判据（橙色「下一题」按钮在 → 直接点它，比按回车精准）；
        //   ② 再看对话高亮条（SalaryTask.scanImage）兜底，有框才按回车。
        //   画面本来就干净就什么都不做 —— 无脑按回车会误触别的东西。
        boolean hadDialog = false;
        for (int i = 1; i <= 3; i++) {
            int[] nq = findNextQuestionButton();
            if (nq != null) {
                hadDialog = true;
                listener.log("  检测到橙色「下一题」按钮 @" + "(" + nq[0] + "," + nq[1]
                        + ")，点击清掉结算框（第 " + i + " 次）");
                controller.clickWindowPoint(nq[0], nq[1]);
                sleep(CLICK_MS);
                continue;
            }
            SalaryTask.Ui ui = SalaryTask.scanImage(controller.captureWindow());
            if (!ui.dialogOpen) {
                listener.log(i == 1
                        ? "  ✔ 画面干净，没有残留的结算框（不需要按回车）"
                        : "  ✔ 结算框已清干净");
                break;
            }
            hadDialog = true;
            listener.log("  检测到对话/结算框（首项 y=" + ui.option1Y + "），按回车确认第 " + i + " 次");
            controller.sendKey(WindowUtils.VK_RETURN);
            sleep(CLICK_MS);
            if (i == 3) {
                listener.log("  ⚠ 连按 3 次回车仍有对话框，先按 ESC 关掉再走交任务流程");
                controller.sendKey(WindowUtils.VK_ESCAPE);
                sleep(ESC_MS);
            }
        }
        if (!hadDialog) {
            listener.log("  （答题过程中没出现需要清理的结算框）");
        }

        // ---- 2. 再对话一次，进入交任务流程 ----
        //   答完题后 NPC 的菜单和接任务时一样：G →「对话/任务」→「推举孝廉」。
        if (!pressGUntilDialog("推举孝廉（交任务）", G_MAX_TRIES)) {
            listener.log("  ⚠ 按了 " + G_MAX_TRIES + " 次 G 都没唤起交付对话，"
                    + "可能任务已经交掉了（答完题就自动完成的情况）");
            snapshot("finish_no_dialog");
            return;
        }

        // ---- 3. 逐层点掉交付选项 ----
        //   菜单层级和接任务时一致，先用同样的两层定位到「推举孝廉」。
        chooseDialogOption(new String[]{"对话任务", "对话与任务", "对话"}, 1, "对话/任务");
        sleep(STEP_MS);
        chooseDialogOption(new String[]{"推举孝廉", "举荐孝廉", "孝廉"}, 1, "推举孝廉（交任务）");
        sleep(STEP_MS);

        // 交付确认层：不同区服/版本用词不一样，关键词覆盖常见的几种。
        // 这一项通常默认高亮在第一项，所以键盘兜底也选第 1 项。
        String[][] deliverLayers = {
                {"交任务", "交付任务", "提交任务", "交纳任务"},
                {"完成任务", "完成", "交付", "结束任务"},
                {"领取奖励", "领取", "好的", "好", "谢谢", "多谢", "再见"},
        };
        for (int i = 0; i < deliverLayers.length; i++) {
            SalaryTask.Ui ui = SalaryTask.scanImage(controller.captureWindow());
            if (!ui.dialogOpen) {
                listener.log("  ✔ 对话框已关闭（交任务流程结束于第 " + i + " 层之前）");
                break;
            }
            chooseDialogOption(deliverLayers[i], 1, deliverLayers[i][0]);
            sleep(STEP_MS);
        }

        // ---- 4. 校验收尾结果 ----
        // 注意：这里只留证、不 abort —— 有可能任务已交付但 NPC 对话还留着。
        SalaryTask.Ui last = SalaryTask.scanImage(controller.captureWindow());
        if (last.dialogOpen) {
            String shot = snapshot("finish_dialog_left_open");
            listener.log("  ⚠ 交付流程走完仍有对话框（首项 y=" + last.option1Y + "），请人工看一眼"
                    + (shot != null ? ("；截图：" + shot) : ""));
        } else {
            listener.log("  ✔ 任务已交付，对话框已关闭");
        }
        snapshot("finish_done");
    }

    // ==================== 决策 ====================

    /** 一道题的作答决策。 */
    static final class Decision {
        String source;
        double score;
        String question;
        String answerText;
        OcrLite.Line optionLine;
        boolean dryRun;
        /** {@code >= 0} 表示这一题是「缺行推断」出来的（该行 OCR 读成乱码），仅用于日志。 */
        int inferRow = -1;
        /** 缺行推断时那一行实际被读成了什么（日志用）。 */
        String inferRowRaw;
    }

    /**
     * 决定第 {@code idx} 题选哪个选项。
     *
     * <p>先把 OCR 出来的行（以及相邻行拼起来的组合）丢进本地题库匹配题目；
     * 命中就用「标准答案 vs 每个选项」的相似度挑选项；
     * 题库没命中才调 DeepSeek。
     */
    Decision decide(int idx, ReadResult rr) {
        List<String> candidates = buildCandidates(rr);
        XiaolianBank.Match m = XiaolianBank.matchQuestion(candidates);

        // 2026-09-29：阈值降到 0.45 后，必须靠「间隔保护」兜住误配风险 ——
        // 首选要明显领先**另一道题**才采用；同题多答案不算竞争对手。
        boolean hit = m != null && m.score >= TH_QUESTION;
        boolean gapOk = m != null && m.gap() >= TH_GAP;
        if (hit && !gapOk) {
            listener.log("    题库首选「" + shortText(m.entry.question) + "」有 "
                    + String.format("%.2f", m.score) + "，但另一道题「"
                    + shortText(String.valueOf(m.runnerUpQuestion)) + "」也有 "
                    + String.format("%.2f", m.runnerUp)
                    + "（间隔 " + String.format("%.2f", m.gap()) + " < " + TH_GAP
                    + "）→ 分不清是哪道题，转大模型");
            hit = false;
        }

        if (hit) {
            // ---- 题库命中 ----
            // 同一道题在库里可能有多条答案（同题多答案，见 XiaolianBank.Match#sameQuestion）：
            // 要给「全部答案」一起给选项打分取最高，只认 entry.answer 会漏掉其它同样正确的选项。
            //
            // 2026-09-29：打分前先剥掉行首选项字母（「A提高惕…」→「提高惕…」）。
            // 字母参与打分是纯噪声，还让四个选项因为都以字母开头而互相"抱团"。
            // 打分逻辑与离线评测（Probe）共用 scoreBestOption()，两边口径必须一致。
            ScoreResult sr = scoreBestOption(m, rr.lines);
            OcrLite.Line bestLine = sr.line;
            double bestScore = sr.score;
            String bestAnswer = sr.answer;
            String bestOptionText = sr.optionText;
            if (bestLine != null && bestScore >= TH_OPTION) {
                Decision d = new Decision();
                d.source = "题库";
                d.score = Math.min(m.score, bestScore);
                d.question = m.entry.question;
                d.answerText = bestAnswer != null ? bestAnswer
                        : (bestOptionText != null ? bestOptionText : m.entry.answer);
                d.optionLine = bestLine;
                d.dryRun = dryRun;
                return d;
            }
            // 2026-09-30 新增：四项都没匹配上时，先试「缺行推断」——短答案常常是
            // 被 OCR 读丢 / 读花的那一行（实机铁证：答案「是」，B 行读成乱码「00」；
            // 2026-10-01 第 1 题：答案「G」，A 行整行被 OCR 丢掉、其余三行是
            // F2/CFI/DF10 快捷键 —— 见 inferByMissingRow 的注释）。
            Decision inferred = inferByMissingRow(m, rr);
            if (inferred != null) {
                inferred.dryRun = dryRun;
                listener.log("    [缺行推断] 答案「" + inferred.answerText
                        + "」在已读到的三行里都没有，而 " + (char) ('A' + inferred.inferRow)
                        + " 行读成乱码（" + inferred.inferRowRaw + "）→ 判定答案就在 "
                        + (char) ('A' + inferred.inferRow) + " 行");
                return inferred;
            }
            listener.log("    题库匹配到题目（" + String.format("%.2f", m.score)
                    + "）但选项对不上答案「" + answerListText(m.sameQuestion) + "」，转大模型");
        } else if (m != null) {            // 措辞修正（0929）：以前一律说「低分噪声」，但很多其实是**真题干被 OCR 读错了字**，
            // 说成噪声会让人以为是脏数据，排查方向跑偏。这里把最像的那条一并打出来对照。
            listener.log("    题库最像的一条只有 " + String.format("%.2f", m.score)
                    + "（< " + TH_QUESTION + "，阈值内不采用）→ 转大模型。最像的是「"
                    + shortText(m.entry.question) + "」；屏幕实际读到的是：");
            logOcrRows(rr);
        } else {
            listener.log("    题库没有可比对的候选，转大模型；屏幕实际读到的是：");
            logOcrRows(rr);
        }

        // ---- 大模型兜底 ----
        return askAi(idx, rr, m);
    }

    /**
     * 短答案「缺行推断」兜底（2026-09-30 新增）。
     *
     * <p><b>为什么需要</b>：实机事故 —— 题库答案「是」，OCR 把 B 行那个孤零零的单字
     * 读成了乱码「00」（实测 3/4/6/8/12/14 倍 × 原图/二值化/反色二值化共 18 种组合，
     * 一个「是」字都读不出来；Windows 自带 OCR 对孤立单字就是这水平）。
     * 此时四行里三项都读得好好的、且都跟答案对不上，唯一读坏的那行恰好就是答案行；
     * 而旧打分又会把「不是」当成「是」的命中（包含关系）→ 直接答错。
     *
     * <p>游戏必然给出 4 个选项、题库命中的答案必然是其中之一 —— 于是当
     * 「三行读出了可辨认的选项文字、都不匹配答案 + 恰好一行是空/读废」时，
     * 答案只可能在那一行。
     *
     * <p><b>闸门</b>（缺一不可，宁可转人工也不盲点）：
     * <ol>
     *   <li>题库已命中（调用点保证）；</li>
     *   <li>答案归一化后长度 ≤ 2 —— 长答案局部读错也能靠相似度救回，不必赌；</li>
     *   <li>四行里恰好 <b>1</b> 行是「空 / 读废」的行，另 <b>3</b> 行都是
     *       「非空且像选项」的文字。</li>
     * </ol>
     *
     * <p><b>⚠ 2026-10-01 放宽「另三行必须含汉字」这个前提（血泪第 1 题）</b>：
     * 原来要求「另三行都读到汉字」，可<b>快捷键题</b>（答案 {@code G}，选项
     * {@code G/F2/F1/F10}）里另三行全是字母数字、一个汉字都没有 ⇒ 闸门把 3 行
     * 全判成 missing（missingCnt=3）→ 直接 return null → 白丢一道会做的题。
     * 正确的不变量是<b>「像不像一个合法选项」</b>（{@link #looksLikeOptionText}／
     * 非空即可），而不是「有没有汉字」。
     */
    private static Decision inferByMissingRow(XiaolianBank.Match m, ReadResult rr) {
        String na = XiaolianBank.normalize(m.entry.answer);
        if (na.isEmpty() || na.length() > 2) {
            return null;
        }
        String[] rows = rowsText(rr);
        int missing = -1;
        int missingCnt = 0;
        int known = 0;
        for (int i = 0; i < rows.length; i++) {
            String n = rows[i] == null ? "" : XiaolianBank.normalize(rows[i]);
            if (n.isEmpty() || !isReadableOption(n)) {
                missing = i;
                missingCnt++;
            } else {
                known++;
            }
        }
        if (missingCnt != 1 || known != OPT_ROW_Y.length - 1) {
            return null;
        }
        Decision d = new Decision();
        d.source = "题库";
        d.score = m.score;
        d.question = m.entry.question;
        d.answerText = m.entry.answer;
        d.inferRow = missing;
        d.inferRowRaw = rows[missing] == null ? "（空）" : XiaolianBank.normalize(rows[missing]);
        d.optionLine = new OcrLite.Line(OPT_CLICK_X, OPT_ROW_Y[missing],
                OPT_CLICK_X, OPT_ROW_Y[missing], m.entry.answer);
        return d;
    }

    /**
     * 这一行的归一化文字「像不像一个能读懂的选项」。
     *
     * <p>用来做「缺行推断」的分母：四行里恰好一行不像（空/纯符号/超短乱码），
     * 其余三行都像 ⇒ 不像的那行就是被 OCR 读废的答案行。
     *
     * <p><b>判据刻意宽松</b>：含汉字 / 含字母 / 含数字 都算「像」——
     * 快捷键（{@code F2}）、纯数字（{@code 20}）、中文（{@code 罗贯中}）都该被判成
     * 正常选项。只有「空串」「纯标点符号」「单个无法归类的字符」才算读废。
     * 2026-10-01 之前用的是「含汉字」，于是快捷键题三行全被判废 → 推断闸门永远
     * 过不了（第 1 题答案 {@code G} 就是这么丢的）。
     */
    private static boolean isReadableOption(String n) {
        if (n == null || n.isEmpty()) {
            return false;
        }
        int useful = 0;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) {
                useful++;
            }
        }
        return useful >= 1;
    }

    /** 字符串里有没有汉字（用来判「这一行的 OCR 结果是不是垃圾」）。 */
    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                    || (c >= 0xF900 && c <= 0xFAFF)) {
                return true;
            }
        }
        return false;
    }

    /** 日志里截断长文本，避免一行刷屏。 */
    private static String shortText(String s) {
        if (s == null) {
            return "(null)";
        }
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    /** {@link #scoreBestOption} 的结果：分数最高的那一行选项，及其对应的答案文字。 */
    static final class ScoreResult {
        OcrLite.Line line;
        double score;
        String optionText;
        String answer;
    }

    /**
     * 在一屏 OCR 行里挑出「最像题库答案」的那一行选项。
     *
     * <p>线上决策（{@link #decide}）与离线评测（{@code Probe.logQuizDecision}）<b>必须共用这一份实现</b>，
     * 否则评测口径和线上会悄悄跑偏 —— 2026-09-29 就栽过一次：评测里自己重写了一遍打分，
     * 没做字母剥离、没滤题号行，评测显示「选项 0.400 会点」而线上其实已经修好了，
     * 反而把真问题掩盖住。
     *
     * <p>两道过滤：
     * <ol>
     *   <li>{@link #isQuizNoLine}：题号匾带（「第1题」）不是选项 —— 短题干时它会被字符级
     *       相似度抬到阈值以上抢走点击（0929 实机复现：0.400 恰好压过 0.40）。</li>
     *   <li>{@link #stripOptionLetter}：剥掉行首选项字母（「A提高惕…」→「提高惕…」）——
     *       字母是纯噪声，还让四个选项因为都以字母开头而互相"抱团"。</li>
     * </ol>
     */
    static ScoreResult scoreBestOption(XiaolianBank.Match m, List<OcrLite.Line> lines) {
        ScoreResult out = new ScoreResult();
        double bestScore = 0;
        for (OcrLite.Line ln : lines) {
            if (isQuizNoLine(ln.text)) {
                continue;   // 题号匾带不是选项
            }
            String optText = stripOptionLetter(ln.text);
            if (optText == null || XiaolianBank.normalize(optText).length() < 1) {
                continue;
            }
            if (!looksLikeOptionText(optText, ln.cy())) {
                continue;   // 行位之外的纯数字碎片（如漂浮的「00」）不是选项文字
            }
            double sc = XiaolianBank.scoreAnyAnswer(m.sameQuestion, optText);
            // 选项固定落在四行上，加一点权重，避免误匹配到题干
            if (optionRowIndex(ln.cy()) >= 0) {
                sc += 0.08;
            }
            if (out.line == null || sc > bestScore) {
                bestScore = sc;
                out.line = ln;
                out.score = sc;
                out.optionText = optText;
                out.answer = nearestAnswer(m.sameQuestion, optText);
            }
        }
        return out;
    }

    /**
     * 在这一组「同题答案」里挑出跟某个选项最贴的那个答案（只用于日志/回执里显示，
     * 判定分数一律用 {@link XiaolianBank#scoreAnyAnswer}）。
     */
    private static String nearestAnswer(List<XiaolianBank.Entry> entries, String option) {
        String best = null;
        double bestScore = 0;
        if (entries == null) {
            return null;
        }
        for (XiaolianBank.Entry e : entries) {
            double sc = XiaolianBank.scoreAnswer(e.answer, option);
            if (sc > bestScore) {
                bestScore = sc;
                best = e.answer;
            }
        }
        return best;
    }

    /** 把「同题多答案」拼成一段可读文本，供日志显示。 */
    private static String answerListText(List<XiaolianBank.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (XiaolianBank.Entry e : entries) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(e.answer);
        }
        return sb.toString();
    }

    /** 把 OCR 收敛后的每一行打出来，用来排查「屏幕上到底读到了什么」。 */
    private void logOcrRows(ReadResult rr) {
        for (OcrLite.Line ln : rowRepresentatives(rr.lines)) {
            int r = optionRowIndex(ln.cy());
            listener.log("      OCR[" + (r >= 0 ? "选项" + (char) ('A' + r) : "其它")
                    + "] y=" + ln.cy() + " x=" + ln.cx() + " 「" + shorten(ln.text, 40) + "」");
        }
    }

    /**
     * 题库没命中时，用「题库里那条低分记录」当题干会误导排查
     * （实测 0.18 分的那条和真实题目根本不是一回事）。
     * 这里直接从 OCR 行里挑题干：排除选项行之后，信息量最大的那一行。
     */
    static String bestQuestionGuess(ReadResult rr) {
        String best = null;
        int bestLen = -1;
        for (OcrLite.Line ln : rowRepresentatives(rr.lines)) {
            if (optionRowIndex(ln.cy()) >= 0) {
                continue;                       // 选项行不可能是题干
            }
            String t = ln.text.trim();
            int n = XiaolianBank.normalize(t).length();
            if (n > bestLen) {
                bestLen = n;
                best = t;
            }
        }
        return best == null ? "（没读到题干）" : best;
    }

    private Decision askAi(int idx, ReadResult rr, XiaolianBank.Match m) {
        return askAiCore(apiKey, dryRun, rr, m, listener);
    }

    /**
     * 大模型兜底的核心实现（静态版 —— 离线自测 {@code xlfile} 也要复用这段）。
     *
     * <p>采用「复述模式」：把<b>整屏</b> OCR 文字交给模型，让它把正确选项那一行原样抄回来，
     * 再按相似度把这行文字反查回屏幕上的某一行。<br>
     * 之所以不直接问序号：游戏界面里「倒计时」标签夹在题干中间，题干会被切成碎片，
     * 选项行也常被漏检，序号非常容易错位；而复述文字不怕这些。
     */
    static Decision askAiCore(String apiKey, boolean dryRun, ReadResult rr,
                              XiaolianBank.Match m, Listener listener) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            listener.log("    没有配置 API Key，无法兜底");
            return null;
        }
        if (rr.lines.isEmpty()) {
            return null;
        }
        StringBuilder screen = new StringBuilder();
        for (OcrLite.Line ln : rr.lines) {
            String t = ln.text.trim();
            if (!t.isEmpty()) {
                screen.append(t).append('\n');
            }
        }
        long t0 = System.currentTimeMillis();

        // ---- 首选「字母模式」：把选项按固定四行归位，让模型只回 A/B/C/D ----
        String[] rows = rowsText(rr);
        // 题库虽然没过阈值，但只要「差不多是这题」就把它当参考一并给出，
        // 这样即使 OCR 把选项认花了，模型也有个可靠的锚点。
        String hint = "";
        if (m != null && m.entry != null && m.score >= TH_HINT) {
            hint = "【本地题库参考】相似度 " + String.format("%.2f", m.score)
                    + "（仅供参考，也可能不是同一题）\n"
                    + "题库题目：" + m.entry.question + "\n"
                    + "题库答案：" + m.entry.answer + "\n\n";
            listener.log("    带上题库参考（" + String.format("%.2f", m.score) + "）："
                    + shorten(m.entry.question, 30) + " -> " + shorten(m.entry.answer, 20));
        }
        try {
            listener.log("    问 DeepSeek（字母模式）…");
            String raw = DeepSeek.pickByLetter(apiKey, hint + screen, rows, 4000, 6000);
            int letter = DeepSeek.firstLetterIndex(raw);
            listener.log("    DeepSeek 耗时 " + (System.currentTimeMillis() - t0) + "ms，原文「"
                    + shorten(raw, 30) + "」→ 第 " + (letter >= 0 ? String.valueOf((char) ('A' + letter)) : "?") + " 项");
            if (letter >= 0 && letter < OPT_ROW_Y.length) {
                Decision d = new Decision();
                d.source = "AI";
                d.score = 0.6;
                // 题库真正命中（过阈值）才敢用它的题干；否则那条只是低分噪声，会误导排查
                d.question = (m != null && m.entry != null && m.score >= TH_QUESTION)
                        ? m.entry.question : bestQuestionGuess(rr);
                d.answerText = rows[letter] != null ? rows[letter] : ("第 " + (char) ('A' + letter) + " 项");
                // 直接落在该行的固定位置上，不依赖 OCR 坐标
                d.optionLine = new OcrLite.Line(OPT_CLICK_X, OPT_ROW_Y[letter],
                        OPT_CLICK_X, OPT_ROW_Y[letter], d.answerText);
                d.dryRun = dryRun;
                return d;
            }
        } catch (Throwable t) {
            listener.log("    DeepSeek 字母模式失败：" + t.getMessage());
        }

        // ---- 兜底「复述模式」：让模型把正确选项那行文字抄回来，再按相似度反查屏幕行 ----
        long t1 = System.currentTimeMillis();
        try {
            listener.log("    改问 DeepSeek（复述模式，整屏 " + rr.lines.size() + " 行）…");
            String raw = DeepSeek.pickByScreenText(apiKey, screen.toString(), 4000, 6000);
            listener.log("    DeepSeek 耗时 " + (System.currentTimeMillis() - t1) + "ms，原文「"
                    + shorten(raw, 50) + "」");
            OcrLite.Line best = null;
            double bestScore = 0;
            for (OcrLite.Line ln : rr.lines) {
                String n = XiaolianBank.normalize(ln.text);
                if (n.length() < 2) {
                    continue;
                }
                double sc = XiaolianBank.scoreAnswer(ln.text, raw);
                if (optionRowIndex(ln.cy()) >= 0) {
                    sc += 0.08;
                }
                if (sc > bestScore + 1e-6
                        || (Math.abs(sc - bestScore) <= 1e-6 && best != null
                        && n.length() < XiaolianBank.normalize(best.text).length())) {
                    bestScore = sc;
                    best = ln;
                }
            }
            listener.log("    匹配回屏幕行："
                    + (best == null ? "无" : "\"" + shorten(best.text, 24) + "\" @ ("
                    + best.cx() + "," + best.cy() + ")，相似度 " + String.format("%.2f", bestScore)));
            if (best == null || bestScore < 0.5) {
                return null;
            }
            Decision d = new Decision();
            d.source = "AI";
            d.score = bestScore;
            d.question = (m != null && m.entry != null) ? m.entry.question : "（大模型，题干未命中题库）";
            d.answerText = raw.trim();
            d.optionLine = best;
            d.dryRun = dryRun;
            return d;
        } catch (Throwable t) {
            listener.log("    DeepSeek 调用失败：" + t.getMessage());
            return null;
        }
    }

    /**
     * 剥离选项行首的选项字母（「A提高惕…」→「提高惕…」）。
     *
     * <p>2026-09-29 抽出为公共方法：原来只有 {@link #rowsText} 做了这一步，
     * 而<b>题库打分那条路径直接拿 {@code rr.lines} 的原文</b>去算相似度 ——
     * 字母 A 成了答案文字之外的噪声，还会让「A…」形式互相抱团（同一题的四个选项
     * 都以字母开头，彼此更像是"同一类"）。实机日志实锤：
     * {@code 「A提高惕，确保自身賢虽物昼是否绑定」}、{@code 「B侍」} 都带着字母。
     *
     * <p>剥离条件：首字符是 A~D（忽略大小写）且<b>第二个字符不是字母</b>
     * （避免把正常的英文选项如 {@code "A. option"} 或纯字母答案误剥）。
     */
    static String stripOptionLetter(String t) {
        if (t == null) {
            return null;
        }
        String s = t.trim();
        if (s.length() >= 2) {
            char c0 = Character.toUpperCase(s.charAt(0));
            if (c0 >= 'A' && c0 <= 'D' && !Character.isLetter(s.charAt(1))) {
                return s.substring(1).trim();
            }
        }
        return s;
    }

    /**
     * 这一行是不是<b>题号匾带</b>的文字（「第 1 题」「第1的」这类），不是选项。
     *
     * <p>为什么需要它：题号匾带 {@link #QBOX_NO_X0}..{@link #QBOX_NO_Y1} 是<b>故意放行</b>的
     * （{@link #quizVisible} 要靠它认「第 N 题」判断答题面板在不在），所以它会一起进入
     * {@code rr.lines}。选项打分那条路径如果不过滤，题号行就会被当成选项参与打分 ——
     * 2026-09-29 实机复现：短题干几道题里，题号行「第1题」经字符级相似度抬到 0.400，
     * 恰好压过阈值 0.40，把真正该点的选项挤掉 → 点空。
     *
     * <p>判据刻意保守：只有「第 + 数字 + 题」这个结构才算，且整行长度 ≤ 6
     * （题号行读好了就是「第1题」三个字，读碎了也是「第1的」这类短碎片），
     * 避免题干里恰好出现「第1题」字样时被误杀。
     */
    static boolean isQuizNoLine(String t) {
        String n = XiaolianBank.normalize(t);
        if (n == null || n.isEmpty() || n.length() > 6) {
            return false;
        }
        return n.matches("第[0-9lI一二三四五六七八九十]{1,2}题?.*")
                && (n.contains("题") || n.length() <= 4);
    }

    /**
     * 这一行像不像「真正的选项文字」—— 排掉那些「压在选项行上、但明显不是选项」的
     * 纯数字/符号碎片。
     *
     * <p><b>为什么需要这个闸门</b>：选线框 {@link #QBOX_O_Y0}..{@link #QBOX_O_Y1} 覆盖了
     * 四个选项行，OCR 偶尔会吐出一段无意义碎片（实机实锤：y388 读出 {@code "00"}）。
     *
     * <p><b>⚠ 2026-09-29 血的教训 —— 判据绝不能只看「是不是纯数字」</b>：
     * 第一版写成「纯数字且长度≤3 就丢弃」，结果把**正确答案**一起丢了。
     * 实机事故（第 1 题「多少级别以上可以收徒弟?」，选项 A15/B12/C20/D25 <b>全是数字</b>）：
     * 正确项 {@code C20} 剥离字母后是 {@code "20"} → 被判成碎片丢掉 →
     * 只剩下 OCR 读坏的 {@code "0田"}（实为 B 行「12」，得分仅 0.50）→ 点到 B 行 → <b>答错</b>。
     * 修复后 {@code C20} 得分 1.00 正常胜出。
     *
     * <p><b>正确的判据 = 看它落在哪一行</b>：真正的选项必定落在四个固定行位置上
     * （{@link #OPT_ROW_Y}，行判据 {@link #optionRowIndex}）；落在行位上的数字就是合法选项，
     * 只有「不在任何行位上」的纯数字短碎片才是噪声。
     *
     * @param t  已经剥过选项字母的候选文字
     * @param cy 这一行的窗口 y 中心（用来判断行位）
     */
    static boolean looksLikeOptionText(String t, int cy) {
        String n = XiaolianBank.normalize(t);
        if (n == null || n.length() < 1) {
            return false;
        }
        // 站得住脚的选项：落在四个固定行位置上 —— 数字、字母、汉字都算数
        if (optionRowIndex(cy) >= 0) {
            return true;
        }
        // 行位之外的：只收「像正常词句」的（含汉字，或长度够长），纯短数字碎片丢掉
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                return true;   // 含汉字 → 正常选项文字
            }
        }
        boolean allDigit = true;
        for (int i = 0; i < n.length(); i++) {
            if (!Character.isDigit(n.charAt(i))) {
                allDigit = false;
                break;
            }
        }
        return !(allDigit && n.length() <= 3);   // 行位外的短数字（如漂浮的「00」）→ 噪声
    }

    /**
     * 把 OCR 出来的行按「固定四行」归位，返回 A/B/C/D 每一行的文字（没读到就是 null）。
     *
     * <p>因为选项行位置固定，一行里认出来的碎片可以合并；同一行有多条时保留信息量最大的那条。
     */
    static String[] rowsText(ReadResult rr) {
        String[] out = new String[OPT_ROW_Y.length];
        for (OcrLite.Line ln : rr.lines) {
            int r = optionRowIndex(ln.cy());
            if (r < 0) {
                continue;
            }
            String t = stripOptionLetter(ln.text);
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (out[r] == null
                    || XiaolianBank.normalize(t).length() > XiaolianBank.normalize(out[r]).length()) {
                out[r] = t;
            }
        }
        return out;
    }

    // ==================== 读屏 ====================

    /** 一次读屏的结果。 */
    static final class ReadResult {
        int quizIndex;
        /** 已换算成「窗口内坐标」的行。 */
        List<OcrLite.Line> lines = new ArrayList<>();
        File crop;
        File full;

        String joined() {
            StringBuilder sb = new StringBuilder();
            for (OcrLite.Line l : lines) {
                sb.append("    ").append(l.text).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 读屏（<b>对话模式</b>）：抠 NPC 对话框那一带（{@link #DBOX_X0}），不做红框过滤。
     *
     * <p>运送物资、领奖确认这些既有流程走这条 —— 抠图区与 0928 改动之前<b>完全一致</b>，
     * 免得「顺手优化」把已经在跑的东西改坏。
     */
    ReadResult readScreen(String tag) {
        return readScreenCore(tag, DBOX_X0, DBOX_X1, DBOX_Y0, DBOX_Y1, false);
    }

    /**
     * 读屏（<b>答题模式</b>）：只抠用户给的两个红框 ∪ 题号匾带（{@link #QUIZQ_X0}），
     * 出框的行按 {@link #inQuizBoxes} 丢掉。
     *
     * <p>用户 2026-09-28：「两个红色框内分别是题目和选线，<b>务必只在这些框内找</b>」。
     * 标定用 -Dqqsg.xl.region 覆盖时过滤让路（见 {@link #regionOverridden}）。
     */
    ReadResult readScreenQuiz(String tag) {
        return readScreenCore(tag, QUIZ_X0, QUIZ_X1, QUIZ_Y0, QUIZ_Y1, !regionOverridden);
    }

    /**
     * 抓图 → 抠出指定区域 → 放大 → OCR → 把坐标换算回窗口坐标。
     *
     * @param tag       截图文件名标签（方便事后对着图排查）
     * @param bx0       抠图左边界（窗口内坐标）
     * @param bx1       抠图右边界
     * @param by0       抠图上边界
     * @param by1       抠图下边界
     * @param boxFilter true = 只保留落在「题目框 / 选线框 / 题号匾带」里的行（答题模式）
     */
    private ReadResult readScreenCore(String tag, int bx0, int bx1, int by0, int by1, boolean boxFilter) {
        ReadResult rr = new ReadResult();
        // 先把鼠标从选项上挪开：压在某一项上会把那行高亮，OCR 就认不出那行文字
        controller.moveToWindowPoint(PT_MOUSE_PARK[0], PT_MOUSE_PARK[1]);
        sleep(180);
        BufferedImage img = controller.captureWindow();
        if (img == null) {
            return rr;
        }
        try {
            if (!shotDir.exists() && !shotDir.mkdirs()) {
                listener.log("  ⚠ 无法创建截图目录 " + shotDir.getAbsolutePath());
            }
        } catch (Throwable ignore) {
            // 建不了目录就只做识别，不存图
        }
        rr.full = saveImage(img, tag + "_full");

        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x0 = clamp((int) Math.round(bx0 * kx), 0, Math.max(0, img.getWidth() - 1));
        int y0 = clamp((int) Math.round(by0 * ky), 0, Math.max(0, img.getHeight() - 1));
        int x1 = clamp((int) Math.round(bx1 * kx), x0 + 1, img.getWidth());
        int y1 = clamp((int) Math.round(by1 * ky), y0 + 1, img.getHeight());

        BufferedImage crop = img.getSubimage(x0, y0, x1 - x0, y1 - y0);

        // 给 OCR 用的图放在独立作业目录里（每次清空），避免把历史截图重认一遍
        File jobDir = OcrLite.prepareJobDir();
        List<File> inputs = new ArrayList<>();
        File v1 = OcrLite.saveForOcr(crop, VAR1_SCALE, new File(jobDir, "v1.png"), OcrLite.MODE_ORIGINAL);
        File v2 = OcrLite.saveForOcr(crop, VAR2_SCALE, new File(jobDir, "v2.png"), OcrLite.MODE_BINARY_INV);
        File v3 = OcrLite.saveForOcr(crop, VAR2_SCALE, new File(jobDir, "v3.png"), OcrLite.MODE_BINARY);
        if (v1 == null && v2 == null && v3 == null) {
            return rr;
        }
        for (File f : new File[]{v1, v2, v3}) {
            if (f != null) {
                inputs.add(f);
            }
        }
        // 每个图的「左上角在窗口中的坐标」，换算 OCR 行坐标时用
        java.util.Map<String, int[]> origins = new java.util.HashMap<>();
        origins.put("v1.png", new int[]{bx0, by0});
        origins.put("v2.png", new int[]{bx0, by0});
        origins.put("v3.png", new int[]{bx0, by0});
        // 四行选项各自单独抠图放大：选项常常只有两三个字，整块识别时容易被 OCR 丢掉。
        // 两条路径都加（对话模式的选项行也常落在这些带上，历史行为如此，保持不动）。
        addOptionRowImages(img, kx, ky, jobDir, inputs, origins);
        // 另外存一份到截图目录，方便事后对着图排查 OCR 到底看到了什么
        rr.crop = OcrLite.saveForOcr(crop, VAR1_SCALE,
                new File(shotDir, ts() + "_" + tag + "_crop.png"), OcrLite.MODE_ORIGINAL);

        long t0 = System.currentTimeMillis();
        List<OcrLite.Result> res = OcrLite.recognize(inputs, OCR_TIMEOUT_S);
        listener.log("  OCR " + res.size() + " 个预处理版本，耗时 "
                + (System.currentTimeMillis() - t0) + "ms");
        if (res.isEmpty()) {
            listener.log("  ⚠ OCR 没有返回结果：" + OcrLite.lastError());
            return rr;
        }
        for (OcrLite.Result one : res) {
            // 每个图的放大倍数与原点都不一样（整块图用 QUIZ 原点、选项行小图用自己的）
            double scale = scaleOf(one.file);
            int[] org = origins.get(one.file);
            if (org == null) {
                org = new int[]{bx0, by0};
            }
            for (OcrLite.Line l : one.sorted()) {
                // 放大图坐标 → 窗口坐标
                int wx0 = org[0] + (int) Math.round(l.x0 / scale / kx);
                int wy0 = org[1] + (int) Math.round(l.y0 / scale / ky);
                int wx1 = org[0] + (int) Math.round(l.x1 / scale / kx);
                int wy1 = org[1] + (int) Math.round(l.y1 / scale / ky);
                if (boxFilter && !inQuizBoxes((wx0 + wx1) / 2, (wy0 + wy1) / 2)) {
                    continue;   // 用户 0928：答题只在「题目框 / 选线框 / 题号匾带」里找，出框的丢掉
                }
                if (inChatArea((wx0 + wx1) / 2, (wy0 + wy1) / 2)) {
                    continue;   // 聊天区的字跟题目无关，丢掉
                }
                if (inCountdownArea((wx0 + wx1) / 2, (wy0 + wy1) / 2)) {
                    continue;   // 倒计时数字不是选项，丢掉
                }
                if (isUiNoise(l.text)) {
                    continue;   // 「倒计时 / 拥有道具数 / 免错锦囊」等界面标签
                }
                rr.lines.add(new OcrLite.Line(wx0, wy0, wx1, wy1, l.text));
            }
        }
        // 保留放大后的局部图，出问题时可以对照着看 OCR 到底读到了什么
        return rr;
    }

    /** 先把 OCR 行组合成候选题干：单行 + 相邻 2 行 + 相邻 3 行。 */
    static List<String> buildCandidates(ReadResult rr) {
        List<String> out = new ArrayList<>();
        List<OcrLite.Line> ls = new ArrayList<>();
        for (OcrLite.Line l : rowRepresentatives(rr.lines)) {
            // 题号匾带的行（「第1题」）不是题干的一部分：留着它会把「题号+题干首行」
            // 拼成一个假候选，还会把真正的题干首行挤到拼接窗口外（0929）。
            if (isQuizNoLine(l.text)) {
                continue;
            }
            ls.add(l);
            out.add(l.text);
        }
        for (int i = 0; i + 1 < ls.size(); i++) {
            out.add(ls.get(i).text + ls.get(i + 1).text);
        }
        for (int i = 0; i + 2 < ls.size(); i++) {
            out.add(ls.get(i).text + ls.get(i + 1).text + ls.get(i + 2).text);
        }
        return out;
    }

    /**
     * 把「同一行」的多个识别结果收敛成一条。
     *
     * <p>一次会送 3 个预处理版本去 OCR，同一行会得到 2~3 种读法（例如
     * 「罗贯中」/「1.罗贯中」/「艺罗贯中」）。拼多行候选时只取每行信息量最大的那条，
     * 否则会拼出一堆「同一行自己和自己拼」的垃圾候选。
     */
    static List<OcrLite.Line> rowRepresentatives(List<OcrLite.Line> lines) {
        List<OcrLite.Line> sorted = new ArrayList<>(lines);
        Collections.sort(sorted, (a, b) -> a.y0 != b.y0 ? Integer.compare(a.y0, b.y0)
                : Integer.compare(a.x0, b.x0));
        List<OcrLite.Line> out = new ArrayList<>();
        List<OcrLite.Line> group = new ArrayList<>();
        for (OcrLite.Line l : sorted) {
            if (!group.isEmpty()) {
                OcrLite.Line first = group.get(0);
                int tol = Math.max(4, (first.y1 - first.y0) * 45 / 100);
                if (Math.abs(l.cy() - first.cy()) > tol) {
                    out.add(bestOf(group));
                    group = new ArrayList<>();
                }
            }
            group.add(l);
        }
        if (!group.isEmpty()) {
            out.add(bestOf(group));
        }
        return out;
    }

    /** 一组同行的识别结果里挑信息量最大的一条（汉字/字母/数字最多者胜，同分取更长的）。 */
    private static OcrLite.Line bestOf(List<OcrLite.Line> group) {
        OcrLite.Line best = group.get(0);
        int bestScore = -1;
        for (OcrLite.Line l : group) {
            int sc = informative(l.text);
            if (sc > bestScore || (sc == bestScore && l.text.length() > best.text.length())) {
                bestScore = sc;
                best = l;
            }
        }
        return best;
    }

    /** 一段文字里「有用字符」的个数（汉字/字母/数字）。游戏里的花边符号不算。 */
    private static int informative(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= 0x4E00 && c <= 0x9FFF)) {
                n++;
            }
        }
        return n;
    }

    /** 各预处理版本对应的放大倍数（文件名 v1/v2/v3）。 */
    private static double scaleOf(String fileName) {
        if (fileName == null) {
            return VAR2_SCALE;
        }
        if (fileName.startsWith("v1")) {
            return VAR1_SCALE;
        }
        if (fileName.startsWith("row")) {
            return ROW_SCALE;
        }
        return VAR2_SCALE;
    }

    /**
     * 生成「选项行」的独立放大图（每题固定四行，每行一张小图）。
     *
     * <p>这些图连同行名一起放进 OCR 作业目录，和整块区域的三个版本<b>同一次</b>识别，
     * 所以不会多花一次进程启动的时间。识别出的行会通过 {@code origins} 里的原点换算回
     * 窗口坐标，再和整块图的行按行合并。
     */
    private static void addOptionRowImages(BufferedImage img, double kx, double ky,
                                           File jobDir, List<File> inputs,
                                           java.util.Map<String, int[]> origins) {
        for (int i = 0; i < OPT_ROW_Y.length; i++) {
            int ry = OPT_ROW_Y[i];
            int ry0 = clamp((int) Math.round((ry - OPT_BAND_UP) * ky), 0, Math.max(0, img.getHeight() - 1));
            int ry1 = clamp((int) Math.round((ry + OPT_BAND_DOWN) * ky), ry0 + 1, img.getHeight());
            int rx0 = clamp((int) Math.round(OPT_ROW_X0 * kx), 0, Math.max(0, img.getWidth() - 1));
            int rx1 = clamp((int) Math.round(OPT_ROW_X1 * kx), rx0 + 1, img.getWidth());
            BufferedImage band = img.getSubimage(rx0, ry0, rx1 - rx0, ry1 - ry0);
            String fn = "row" + (char) ('A' + i) + ".png";
            File f = OcrLite.saveForOcr(band, ROW_SCALE, new File(jobDir, fn), OcrLite.MODE_ORIGINAL);
            if (f != null) {
                inputs.add(f);
                origins.put(fn, new int[]{OPT_ROW_X0, ry - OPT_BAND_UP});
            }
        }
    }

    private static String joinedText(ReadResult rr) {
        StringBuilder sb = new StringBuilder();
        for (OcrLite.Line l : rr.lines) {
            sb.append(l.text);
        }
        return sb.toString();
    }

    private static String longestLine(List<OcrLite.Line> lines) {
        String best = null;
        int bestLen = 0;
        for (OcrLite.Line l : lines) {
            int n = XiaolianBank.normalize(l.text).length();
            if (n > bestLen) {
                bestLen = n;
                best = l.text;
            }
        }
        return best;
    }

    /** 题干下面那几行短文字（候选选项）。 */
    private static List<OcrLite.Line> optionLinesBelow(ReadResult rr, String question) {
        List<OcrLite.Line> ls = rowRepresentatives(rr.lines);
        int qi = -1;
        for (int i = 0; i < ls.size(); i++) {
            if (ls.get(i).text.equals(question)
                    || XiaolianBank.similarity(ls.get(i).text, question) >= 0.9) {
                qi = i;
                break;
            }
        }
        List<OcrLite.Line> out = new ArrayList<>();
        int start = qi < 0 ? 0 : qi + 1;
        int qy = qi < 0 ? 0 : ls.get(qi).y0;
        for (int i = start; i < ls.size() && out.size() < OPT_MAX_N; i++) {
            OcrLite.Line l = ls.get(i);
            if (qi >= 0 && l.y0 - qy > 320) {
                break;
            }
            int n = XiaolianBank.normalize(l.text).length();
            if (n < 1 || l.text.length() > OPT_MAX_CHARS) {
                continue;
            }
            out.add(l);
        }
        return out;
    }

    /**
     * 这个坐标是不是落在左下角的聊天区里。
     *
     * <p>聊天区里全是玩家喊话，跟题目毫无关系，而且里面的字会把题干/选项的候选池搅浑
     * （尤其是「收XX武器」这类长句），所以识别出来后要把这部分行直接丢掉。
     */
    private static boolean inChatArea(int x, int y) {
        return x < CHAT_X1 && y > CHAT_Y0;
    }

    /**
     * 这个坐标是不是落在用户给定的题面区域里（题目框 / 选线框 / 题号匾带）。
     *
     * <p>用户 2026-09-28 定稿：「两个红色框内分别是题目和选线，务必只在这些框内找」。
     * 读屏时凡是中心落在这三块之外的行，一律丢掉 —— 这样面板上方的「推举孝廉」标题、
     * 「倒计时 / 拥有道具数」之类界面字就再也进不了题干/选项候选池。
     */
    static boolean inQuizBoxes(int cx, int cy) {
        return inBox(cx, cy, QBOX_Q_X0, QBOX_Q_X1, QBOX_Q_Y0, QBOX_Q_Y1)
                || inBox(cx, cy, QBOX_O_X0, QBOX_O_X1, QBOX_O_Y0, QBOX_O_Y1)
                || inBox(cx, cy, QBOX_NO_X0, QBOX_NO_X1, QBOX_NO_Y0, QBOX_NO_Y1);
    }

    private static boolean inBox(int cx, int cy, int x0, int x1, int y0, int y1) {
        return cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1;
    }

    /**
     * 画面里是不是出现了验证码 / 输入提示。
     *
     * <p>逐行判断，而不是把整屏拼起来：
     * <ul>
     *   <li>「验证码 / 安全验证 / 完成验证 / 拖动下方滑块 / 拼图」—— 这些词只在验证码弹窗出现，
     *       出现即判定。</li>
     *   <li>「请输入 / 输入答案」比较通用，只有出现在「短行」（验证码提示语都很短）里才算，
     *       避免某道题目正文里恰好带了「输入」二字就把答题流程误中断。</li>
     * </ul>
     *
     * <p><b>2026-09-29 补齐</b>：原判据只有「验证码 / 请输入」，而真机弹窗写的是
     * <b>「安全验证」「请在89S内完成验证」「拖动下方滑块完成拼图」</b> —— 一个都不含「验证码」，
     * 于是弹窗盖住题面时判据全灭：机器人把被遮挡的残题（如「九酬」）当新题、连着瞎答了两轮。
     * 实机截图（091933_r13_q13_full.png）与 OCR 实读（「拖动下方滑块完」）双重实锤。
     */
    private boolean looksLikeCaptcha(ReadResult rr) {
        String[] kw = {"验证码", "安全验证", "完成验证", "拖动下方滑块", "滑块完成", "拼图"};
        for (OcrLite.Line ln : rr.lines) {
            String n = XiaolianBank.normalize(ln.text);
            for (String k : kw) {
                if (n.contains(k)) {
                    return true;
                }
            }
            if (n.length() <= 10 && (n.contains("请输入") || n.contains("输入答案"))) {
                return true;
            }
        }
        return false;
    }

    // ==================== 界面检测（与工资任务同源） ====================

    /** 色块初筛（旧判据）：采样区偏橙红才算「可能有问题」。红色景物也会命中，只做初筛。 */
    private boolean adColorHit() {
        double[] m = patchMean(controller.captureWindow(), PATCH_AD_HOTSPOT);
        return m != null && (m[0] - m[1]) > TH_AD_RG;
    }

    /**
     * 「热点活动」弹窗是否真的在 = 色块偏红 <b>且</b> OCR 认得出广告标题。
     *
     * <p>纯色块判据的两次冤案：200056（工资）/ 055152（孝廉）的「广告未关」现场截图里
     * 画面上根本没有弹窗 —— 秋天树冠、灯笼等红色景物让 (753,297) 采样区 R-G&gt;50，
     * 任务被白白中止。加 OCR 标题闸门后这类误报直接按「无弹窗」放行。
     */
    private boolean adHotspotPresent() {
        if (!adColorHit()) {
            return false;
        }
        return adTitleRecognized();
    }

    /**
     * OCR 认「热点活动 / 游戏活动展示」标题（整窗 ×2 原图，与 {@link #ocrNavInputLabel()} 同参数）。
     *
     * <p>失败策略：<b>OCR 挂掉按「弹窗在」处理</b>（走原关闭流程）—— 宁可多关一次，
     * 也不能把真弹窗放过去挡住后面的点击。
     */
    private boolean adTitleRecognized() {
        try {
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                return false;
            }
            double kx = img.getWidth() / (double) BASE_W;
            double ky = img.getHeight() / (double) BASE_H;
            File jobDir = OcrLite.prepareJobDir();
            File f = OcrLite.saveForOcr(img, 2, new File(jobDir, "adtitle.png"), OcrLite.MODE_ORIGINAL);
            if (f == null) {
                return true; // 存图失败 = OCR 不可用，退回旧色块行为
            }
            List<OcrLite.Result> res = OcrLite.recognize(Collections.singletonList(f), OCR_TIMEOUT_S);
            for (OcrLite.Result one : res) {
                if (one.file == null || !one.file.replace('\\', '/').endsWith("adtitle.png")) {
                    continue; // recognize 按目录跑，过滤掉同目录历史图（铁律）
                }
                for (OcrLite.Line l : one.sorted()) {
                    int cx = (int) Math.round(l.cx() / 2.0 / kx);
                    int cy = (int) Math.round(l.cy() / 2.0 / ky);
                    if (cx < ZONE_AD_TITLE[0] || cx > ZONE_AD_TITLE[1]
                            || cy < ZONE_AD_TITLE[2] || cy > ZONE_AD_TITLE[3]) {
                        continue;
                    }
                    String t = XiaolianBank.normalize(l.text);
                    for (String w : KW_AD_TITLE) {
                        if (t.contains(w)) {
                            listener.log("    [检测] 广告标题 OCR 命中「" + l.text.trim()
                                    + "」@ (" + cx + "," + cy + ")");
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            listener.log("  ⚠ 广告标题 OCR 异常（按「弹窗在」处理，走原关闭流程）：" + t);
            return true;
        }
        listener.log("    [检测] 色块偏红但 OCR 没认出广告标题 —— 多半是红色景物（秋叶/灯笼）误报，按「无弹窗」处理");
        return false;
    }

    boolean bigPanelOpen() {
        // 面板会随右上角地图横幅整体上下漂 ~74px（09:44 现场：面板开着、旧固定区域判"没开"）。
        // 两个深色坐标输入框是「自动寻路」面板独有的指纹：位置无关，且 O 军团界面/ESC 菜单
        // 都没有这种框对，不会误判。
        return findNavBoxesCore(controller.captureWindow()) != null;
    }

    /** 探针用：直接暴露当前画面的面板占比分数（便于校准阈值）。 */
    double panelFractionProbe() {
        double f = panelFraction(controller.captureWindow());
        double kx = controller.captureWindow() == null ? 1
                : controller.captureWindow().getWidth() / (double) BASE_W;
        return f;
    }

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

    // ==================== 离线自检（标定用） ====================

    /**
     * 对一张「已经存好的截图」跑一遍完整的读屏 + 决策流程，不碰游戏。
     *
     * <p>这是标定时最有用的工具：不用真的去答题（每天只有一次机会），
     * 只要把答题那一瞬间的截图存下来丢进来，就能看到 OCR 读到了什么、
     * 题库匹配到哪道题、最后会点哪里。
     */
    public static String analyzeImage(BufferedImage img) {
        return analyzeImage(img, true);
    }

    /**
     * 同 {@link #analyzeImage(BufferedImage)}，但可以只跑题库路径（{@code allowAi=false}）。
     *
     * <p>批量评测命中率时用：题库覆盖率的统计只关心「题库有没有认出来」，
     * AI 兜底那段每帧要花 ~600ms 走网络，40 帧就是好几分钟白等。
     */
    public static String analyzeImageNoAi(BufferedImage img) {
        return analyzeImage(img, false);
    }

    public static String analyzeImage(BufferedImage img, boolean allowAi) {
        StringBuilder sb = new StringBuilder();
        if (img == null) {
            return "读图失败";
        }
        sb.append("题库 ").append(XiaolianBank.size()).append(" 条，抠图区域 ")
                .append(regionInfo()).append('\n');
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x0 = clamp((int) Math.round(QUIZ_X0 * kx), 0, img.getWidth() - 1);
        int y0 = clamp((int) Math.round(QUIZ_Y0 * ky), 0, img.getHeight() - 1);
        int x1 = clamp((int) Math.round(QUIZ_X1 * kx), x0 + 1, img.getWidth());
        int y1 = clamp((int) Math.round(QUIZ_Y1 * ky), y0 + 1, img.getHeight());
        BufferedImage crop = img.getSubimage(x0, y0, x1 - x0, y1 - y0);
        File jobDir = OcrLite.prepareJobDir();
        List<File> inputs = new ArrayList<>();
        File v1 = OcrLite.saveForOcr(crop, VAR1_SCALE, new File(jobDir, "v1.png"), OcrLite.MODE_ORIGINAL);
        File v2 = OcrLite.saveForOcr(crop, VAR2_SCALE, new File(jobDir, "v2.png"), OcrLite.MODE_BINARY_INV);
        File v3 = OcrLite.saveForOcr(crop, VAR2_SCALE, new File(jobDir, "v3.png"), OcrLite.MODE_BINARY);
        for (File f : new File[]{v1, v2, v3}) {
            if (f != null) {
                inputs.add(f);
            }
        }
        java.util.Map<String, int[]> origins = new java.util.HashMap<>();
        origins.put("v1.png", new int[]{QUIZ_X0, QUIZ_Y0});
        origins.put("v2.png", new int[]{QUIZ_X0, QUIZ_Y0});
        origins.put("v3.png", new int[]{QUIZ_X0, QUIZ_Y0});
        addOptionRowImages(img, kx, ky, jobDir, inputs, origins);
        if (inputs.isEmpty()) {
            return sb.append("抠图/放大失败：").append(OcrLite.lastError()).append('\n').toString();
        }
        long t0 = System.currentTimeMillis();
        List<OcrLite.Result> res = OcrLite.recognize(inputs, OCR_TIMEOUT_S);
        long cost = System.currentTimeMillis() - t0;
        sb.append("OCR 耗时 ").append(cost).append("ms（").append(inputs.size()).append(" 个预处理版本）\n");
        if (res.isEmpty()) {
            return sb.append("OCR 无结果：").append(OcrLite.lastError()).append('\n').toString();
        }

        List<OcrLite.Line> lines = new ArrayList<>();
        for (OcrLite.Result one : res) {
            double scale = scaleOf(one.file);
            int[] org = origins.get(one.file);
            if (org == null) {
                org = new int[]{QUIZ_X0, QUIZ_Y0};
            }
            sb.append("  ── ").append(one.file).append("（").append(one.lines.size()).append(" 行）\n");
            for (OcrLite.Line l : one.sorted()) {
                int wx0 = org[0] + (int) Math.round(l.x0 / scale / kx);
                int wy0 = org[1] + (int) Math.round(l.y0 / scale / ky);
                int wx1 = org[0] + (int) Math.round(l.x1 / scale / kx);
                int wy1 = org[1] + (int) Math.round(l.y1 / scale / ky);
                int mcx = (wx0 + wx1) / 2, mcy = (wy0 + wy1) / 2;
                boolean outBox = !inQuizBoxes(mcx, mcy);   // 用户 0928：红框外的丢掉（与线上一致）
                boolean chat = inChatArea(mcx, mcy);
                boolean cd = inCountdownArea(mcx, mcy) || isUiNoise(l.text);
                sb.append(String.format("     (%4d,%4d)-(%4d,%4d)  %s%s%n",
                        wx0, wy0, wx1, wy1, l.text,
                        outBox ? "   [题目框/选线框外，已丢弃]"
                                : (chat ? "   [聊天区，已丢弃]" : (cd ? "   [界面标签，已丢弃]" : ""))));
                if (outBox || chat || cd) {
                    continue;
                }
                lines.add(new OcrLite.Line(wx0, wy0, wx1, wy1, l.text));
            }
        }
        sb.append("  ── 合并成 ").append(rowRepresentatives(lines).size()).append(" 行：\n");
        for (OcrLite.Line l : rowRepresentatives(lines)) {
            sb.append(String.format("     (%4d,%4d)-(%4d,%4d)  %s%n",
                    l.x0, l.y0, l.x1, l.y1, l.text));
        }
        ReadResult rr = new ReadResult();
        rr.lines = lines;
        XiaolianBank.Match m = XiaolianBank.matchQuestion(buildCandidates(rr));
        boolean decidedOk = false;
        if (m == null) {
            sb.append("题库：没有可比对的候选\n");
        } else {
            sb.append(String.format("题库最佳：%.3f  %s  ->  %s%n", m.score, m.entry.question, m.entry.answer));
            sb.append("   （参与比对的文字：「").append(m.source).append("」）\n");
            sb.append("   是否达到阈值 ").append(TH_QUESTION).append("：")
                    .append(m.score >= TH_QUESTION ? "是" : "否").append('\n');
        }
        if (m != null && m.score >= TH_QUESTION) {
            // 复用产品代码的打分（含字母剥离 + 题号行过滤），避免评测口径与线上脱节
            ScoreResult sr = scoreBestOption(m, lines);
            if (sr.line != null) {
                sb.append(String.format("   选项匹配：%.3f  \"%s\" @ (%d,%d)%n",
                        sr.score, sr.line.text, sr.line.cx(), sr.line.cy()));
                sb.append("   是否达到阈值 ").append(TH_OPTION).append("：")
                        .append(sr.score >= TH_OPTION ? "是（会点这里）" : "否（会转大模型）").append('\n');
                decidedOk = sr.score >= TH_OPTION;
            }
        }
        // 与线上 decide() 同源：四项都对不上时先试「缺行推断」，否则评测会低估线上能力
        // （0929 的教训：评测自己重写一遍路由，会把线上已经修好的能力漏掉）。
        if (!decidedOk && m != null && m.score >= TH_QUESTION) {
            Decision inf = inferByMissingRow(m, rr);
            if (inf != null) {
                sb.append(String.format("   [缺行推断] 答案「%s」在已读到的三行里都没有，"
                                + "%c 行读成乱码 → 会点这里 @ (%d,%d)%n",
                        inf.answerText, (char) ('A' + inf.inferRow),
                        inf.optionLine.cx(), inf.optionLine.cy()));
                decidedOk = true;
            }
        }
        // 题库没命中、或命中了但选项对不上答案 → 顺带跑一次大模型，覆盖完整决策链路
        if (!decidedOk && !allowAi) {
            sb.append("—— 题库未定案（评测模式，不跑大模型）——\n");
            return sb.toString();
        }
        if (!decidedOk) {
            sb.append("—— 题库未定案，试大模型兜底 ——\n");
            Decision d = askAiCore(resolveApiKey(), false, rr, m, new Listener() {
                public void log(String message) {
                    sb.append("   ").append(message).append('\n');
                }

                public void alert(String title, String message) {
                    sb.append("   [alert] ").append(title).append('\n');
                }

                public void finished(boolean success, String summary) {
                }
            });
            if (d != null) {
                sb.append(String.format("   → 决定点：「%s」 @ (%d,%d)%n",
                        d.optionLine.text, d.optionLine.cx(), d.optionLine.cy()));
            } else {
                sb.append("   → 大模型也没给出结果\n");
            }
        }
        return sb.toString();
    }

    /** 预处理版本说明（日志里显示用）。 */
    public static String variantInfo() {
        return "v1 原图×" + VAR1_SCALE + " / v2 二值化反色×" + VAR2_SCALE + " / v3 二值化×" + VAR2_SCALE;
    }

    // ==================== 异常与收尾 ====================

    private void abort(String reason, String humanMessage) {
        listener.log("✘ 异常中止：" + reason);
        listener.alert("孝廉 · 需要人工处理", humanMessage);
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

    private static String ts() {
        return new SimpleDateFormat("HHmmss").format(new Date());
    }

    private static String pad2(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static String shorten(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private File saveImage(BufferedImage img, String tag) {
        try {
            if (!shotDir.exists() && !shotDir.mkdirs()) {
                return null;
            }
            File f = new File(shotDir, ts() + "_" + tag + ".png");
            javax.imageio.ImageIO.write(img, "png", f);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private String snapshot(String tag) {
        BufferedImage img = controller.captureWindow();
        if (img == null) {
            return null;
        }
        File f = saveImage(img, tag);
        return f == null ? null : f.getAbsolutePath();
    }

    /** 便于调试：打印当前用到的关键词。 */
    public static String keywords() {
        return Arrays.toString(new String[]{"对话任务", "推举孝廉", "孝廉"});
    }
}
