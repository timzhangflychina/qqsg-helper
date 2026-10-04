package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 军团初级任务自动化执行器。
 *
 * <h3>完整流程（每轮）</h3>
 * <pre>
 * 轮1（完整）：O → 点「回到军团」→【名条OCR确认「军团地图」】→ G → 点「进入军团大厅」
 *             → G →【到位判据=掌簿菜单认出任务选项】→ 点「对话/任务」→ 点「1级军团任务」
 *             → 点「确定」
 * 轮2~N（精简）：G → 点「对话/任务」→ 点「1级军团任务」→ 点「确定」
 *             （人已在军团大厅掌簿旁，省去传送）
 *
 * <p><b>掌簿菜单分两层（0927 01:44 用户定稿，纠正 01:37 的误判）</b>：
 * 首层「对话/任务(高亮) / 提升军团等级 / 我要回去 / 军团长期不上线 / 取消」是
 * <b>每一轮都会出现的正常菜单</b>，每轮都要先点高亮的第一项「对话/任务」进二层；
 * 二层任务列表才有「1级军团任务」。「今日已做满」的<b>唯一</b>可信信号是
 * 「你已经做够了五次任务，明日请早」提示框（点了对话/任务之后才可能弹出）——
 * <b>基础首层菜单绝不是做满信号</b>（01:37 版曾把首层菜单误判成做满导致每轮秒退）。
 * </pre>
 *
 * <h3>只在用户给定的 3 个框里找字（2026-09-28 用户定稿，铁律）</h3>
 * 用户原话：「找图时候不要满屏幕的找，只在我给定的三个框里找」。三个框（窗口内坐标，
 * 基准 1030x797）全部按用户亲笔画红框的现场图机器实测：
 * <ol>
 *   <li>{@link #ZONE_MENU_BOX}（首层菜单框）<b>{347, 698, 240, 447}</b>
 *       —— 014426 / 185255 两张图取并集；</li>
 *   <li>{@link #ZONE_OPTION}（掌簿对话的任务/选项框）<b>{336, 703, 358, 470}</b>
 *       —— 185304（请交给我吧/我暂时没空）+ 185259（! 1级军团任务）取并集；</li>
 *   <li>{@link #ZONE_QUEST_PANEL}（完成任务/任务详情面板框）<b>{325, 713, 142, 592}</b>
 *       —— 185236 红框实测。</li>
 * </ol>
 * <b>任何搜字都必须落在框内</b>；「明日请早」探测也从全屏收进对话正文区
 * {@link #ZONE_DIALOG}（{@link #fullHintInFrame}）。
 *
 * <p>同时定下一条语义（用户 0928 原话）：「如果找到军团任务完成的说明，
 * 就是<b>接了任务还没还</b>，<b>务必继续做下去</b>」—— 即面板顶栏 / NPC 头顶横幅
 * 「完成任务 1级军团任务，获得评价 甲」（{@link #KW_QUEST_DONE_HINT}）是
 * <b>继续信号</b>（这轮做完了，去交/进下一轮），<b>绝不是今日做满的收工信号</b>。
 *
 * <h3>为什么整条链路不用回车（2026-09-25 用户反馈后定稿）</h3>
 * 回车在 QQ三国里<b>同时是「聊天输入框的开关」</b>：只要某一屏没有窗口把这次回车消费掉，
 * 它就会顺手把屏幕底部的聊天输入框打开；之后按 G 会被当成打字，把字母 g 写进聊天框，
 * 对话再也弹不出来 —— 任务静默失败、还留下半句乱码。
 * （实证：{@code legion_shots/061438_anomaly_dialog_missing.png} 里对话框明明开着，
 * 聊天区却躺着 {@code ksdafqawert} 这种残留字。）
 * <p>
 * 所以这条链路改成<b>全程鼠标点击、一次回车都不按</b>，每一步都用画面判据验收：
 * <ul>
 *   <li>对话菜单分两层：首层 OCR 找「对话/任务」那一行点进去（认不出就点高亮条本身，
 *       它就是首层第一项）；二层任务列表 OCR 找「1级军团任务」再点（实测命中 @ (420,408)）；
 *       点中的验收标准是<b>任务详情面板弹出来</b>（只认「菜单消失」会把误点当成功）；</li>
 *   <li>任务详情 / 结算窗口 → OCR 认出「任务名称/任务内容」面板文字后点橙色的「确定」
 *       （实测 bbox x490..551 y507..530；0926 起纯色块判据废弃 —— 场景橙木让它恒真）；</li>
 *   <li>NPC 长正文屏 → 点对话框正文推进；</li>
 *   <li>认不出画面 → 不动手，等一轮再看，连续两轮都干净才收工。</li>
 * </ul>
 *
 * <h3>三个阶段</h3>
 * <ol>
 *   <li><b>关广告</b>：「游戏活动展示」用 ESC 关；「热点活动」点右上角 X 关。
 *       只有在检测到广告时才按 ESC —— 避免在干净画面下误按 ESC 打开游戏系统菜单。</li>
 *   <li><b>跑轮次</b>：每轮关键节点都做界面校验（对话是否真的弹出）。</li>
 *   <li><b>异常处理</b>：出现「缺少物品 / 验证提示 / 界面未弹出」等异常时立即停止，
 *       保存现场截图并弹窗提示人工处理。</li>
 * </ol>
 *
 * <h3>坐标说明</h3>
 * 所有坐标都是「窗口内坐标」，基准分辨率 1030x797（即 QQ三国 默认窗口尺寸）。
 * 实际执行时会按游戏窗口真实尺寸等比缩放，因此窗口大小/位置变化不影响使用。
 *
 * <h3>检测原理（基于实测样本标定，余量充足）</h3>
 * <ul>
 *   <li>热点活动弹窗：(753,297) 50x50 区域 R-G &gt; 20 判定弹窗仍在
 *       （实测弹窗在 = +46~+48，弹窗关 = -10~-34）</li>
 *   <li>NPC 对话：(505,283) 195x26 区域（对话首项高亮蓝条）B-R &gt; 50 判定对话已弹出
 *       （实测对话在 = +58~+66，无对话 = 最高 +40）</li>
 * </ul>
 */
public class LegionTask {

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

    /**
     * 今天的军团任务已经做完 —— <b>不是错误</b>，按用户确认的真实判定流程：
     * 做满 5 次后游戏会弹「你已经做够了五次任务，明日请早」，此时应当优雅结束
     * （success=true），而不是乱点 4 轮再弹「需要人工处理」（0927 凌晨 5 开现场）。
     */
    private static class QuestDone extends RuntimeException {
        QuestDone() {
            super("今天的军团任务已做完");
        }
    }

    // ==================== 坐标常数（窗口内坐标，基准 1030x797） ====================

    private static final int BASE_W = GameWindowController.BASE_WIDTH;
    private static final int BASE_H = GameWindowController.BASE_HEIGHT;

    /** 军团界面「回到军团」按钮 */
    private static final int[] PT_BACK_TO_LEGION = {830, 635};
    /** 军团尉对话「进入军团大厅」 */
    private static final int[] PT_ENTER_HALL = {500, 291};
    /** 「热点活动」弹窗右上角 X */
    private static final int[] PT_AD_HOTSPOT_X = {778, 322};

    /** 广告检测采样区（热点活动 X 附近，弹窗在时是橙色） */
    private static final int[] PATCH_AD_HOTSPOT = {753, 297, 50, 50};

    /** 「热点活动 / 游戏活动展示」弹窗标题的 OCR 搜索区与关键词（0926 误报修复，同孝廉/霸王城）。
     *  色块 (753,297) 在秋天树冠/灯笼等红色景物下也 R-G>50（060929 现场 R-G=+95、画面没有
     *  弹窗）—— 纯色块判据会 ESC×3 + 点 X 两轮后中止，「进入军团」等后续动作全没执行。
     *  弹窗在时标题栏必有这些字，OCR 认得到才算真的在。搜索区避开 x≥830 的右上角按钮列
     *  （那里有「热点」按钮，会把按钮文字误当弹窗标题）。 */
    private static final int[] ZONE_AD_TITLE = {250, 810, 180, 400};
    private static final String[] KW_AD_TITLE = {
        "热点活动", "热点活", "点活动", "热点", "活动展示", "游戏活动", "动展示"};
    /** 对话检测采样区（对话列表首项高亮蓝条） */
    private static final int[] PATCH_DLG_OPTION = {560, 285, 140, 22};

    // ==================== 对话链路：鼠标点击标定（2026-09-25 实测定标） ====================

    /**
     * 军团掌簿对话里的任务选项关键词（长词在前）。
     *
     * <p>「1级军团任务」实测 OCR 命中 @ (420,408)。注意<b>结算窗口里也有这七个字</b>
     * （任务名称栏），所以找它时必须把搜索区限制在菜单选项那一带
     * （{@link #ZONE_OPTION}），否则会点到结算窗口的「任务名称」标签上。
     */
    private static final String[] KW_QUEST_ITEM = {
            "1级军团任务", "级军团任务", "1级军团仕务", "军团任务"
    };

    /**
     * 【2026-09-30 新增】第三层「确认接取任务」对话框的接受项关键词。
     *
     * <p><b>现场</b>（{@code legion_shots/101714_opt_r21_unread.png}，用户 0930 报
     * 「军团任务每次到最后一步失败，前四次都很完美」）：点二层「1级军团任务」之后，
     * 游戏<b>还会再弹一层确认</b> —— 正文「最近军团库存严重不足，急需补充…对了，
     * 这个军团任务每天只能做5次，你确定要接受这个任务吗？」，选项
     * 「<b>请交给我吧</b> / 我暂时没空」（实测 y≈404 / y≈435，落在 {@link #ZONE_OPTION} 内）。
     *
     * <p>旧实现只认「任务详情面板弹出 / 对话自动关闭」两种验收，把这一层判成
     * 「对话还开着但面板没认出的失败」→ 每轮空转、直到 {@link #OPTION_TRIES} 轮硬上限
     * 才误报「需要人工处理」。日志实证（10:16:08~10:17:26 整段）：
     * {@code 第 2 轮 点「1级军团任务」→ 面板判据 没认出 → 第 3 轮 读不出任何行 → 点高亮条兜底}
     * 反复 24 轮 —— 而那条「高亮条」其实正是「请交给我吧」，点中了却完全不自知。
     *
     * <p><b>OCR 误读兜底（2026-09-30 第二次修复的关键）</b>：08:xx 那版只写了
     * {@code {请交给我吧, 请交给我, 请交给}}，<b>上线后依然每次卡在第 5 轮</b> ——
     * 用 {@code _dev/LegionAcceptProbe} 直接跑现场帧 {@code 104148_opt_r3_unread.png}
     * 拿到决定性读数：
     * <pre>
     *   [OPTION] (420,405) 「请交绐」        ← 「给」被读成形近字「绐」！且丢了「我/吧」
     *   [OPTION] (436,428) 「我暫时没空」
     *   >> 确认框判据 accept = null（不是确认框）
     * </pre>
     * {@code "请交绐"} 与 {@code "请交给"} 的 bigram Dice 只有 <b>0.5</b>
     * （「给≠绐」，仅 {@code 请交} 一个 bigram 重合），够不到 {@link ScreenText#find}
     * 的 0.72 相似度兜底 ⇒ 三个关键词<b>全军覆没</b> ⇒ 判据恒 null。
     * 于是又回到「靠兜底坐标 (481,404) 碰巧撞在同一行」的老路：一次任务 6 轮、
     * {@code 24÷6=} 刚好 4 次，<b>第 5 次永远轮不到</b>（这正是用户「第五轮开始就失败」）。
     *
     * <p>教训：本作客户端把「给」读成「绐」是<b>稳定复现</b>的误读（不是偶发），
     * 所以关键词必须补形近变体，并保留**最短健壮前缀「请交」**兜底
     * （两字同时读错的概率极低）——「顾」类前缀兜底是本项目一贯做法（见 0928 铁律）。
     */
    private static final String[] KW_ACCEPT_QUEST = {
            "请交给我吧", "请交给我", "请交给", "请交绐", "请交"
    };

    /**
     * 首层菜单「对话/任务」选项关键词（0927 01:44 用户定稿：首层基础菜单是
     * <b>每轮都会出现的正常菜单</b>，必须点高亮的第一项「对话/任务」进二层任务列表）。
     *
     * <p>OCR 实测误读（两台客户端）：「对话/任务」→「对任务」（掉「话/」）、
     * →「对任气」（务→气，014426 一般版现场），「/」也可能被读成别的字符，
     * 所以从精确全称到前缀逐级兜底（normalize 丢标点后「对话/任务」→
     * 「对话任务」，前缀「对话」能兜住「对话7任务」这类碎片误读）。
     */
    private static final String[] KW_TALK_OPTION = {
            "对话/任务", "对任务", "对任", "对话"
    };

    /**
     * 「今日已做满」提示对话框的关键词（0927 用户确认：做完 5 次后游戏弹
     * 「你已经做够了五次任务，明日请早」）。OCR 偶尔会把字读碎，「明日请早」
     * 几乎是专属词，单独命中即可判满；「做够」必须与「五次」同帧出现才作数，
     * 防止别的 NPC 台词里出现「做够」误判。
     */
    private static final String KW_FULL_DAY = "明日请早";
    private static final String KW_FULL_ENOUGH = "做够";
    private static final String KW_FULL_FIVE = "五次";

    /**
     * 首层菜单特征词（0927 00:28 现场：对话/任务、提升军团等级、我要回去、
     * 军团长期不上线、取消）。命中 ≥2 个且菜单框内无「军团任务」字样 →
     * 「首层基础菜单在」—— <b>这是正常态，动作是点「对话/任务」进二层</b>，
     * 绝不是「今日已做满」（01:37 版在这里栽过：把首层菜单误判成做满导致每轮秒退；
     * 做满的唯一信号是「明日请早」提示框）。
     *
     * <p><b>照实测误读取容错</b>（010943 现场整窗 OCR 实读）：「对话/任务」→「对任务」、
     * 「我要回去」→「我要回宏」、「军团长期不上线」→「团长长期不上线」（军→团）。
     * 全用精确全称的话 4 个基础项只命中 1 个，判据失灵 —— 所以每个词都带前缀兜底。
     */
    private static final String[] KW_MENU_BASE = {
            "提升军团等级", "提升军团",
            "军团长期不上线", "长期不上线",
            "对话/任务", "对任务", "对任",
            "我要回去", "我要回"
    };

    /**
     * 【用户给定的 3 个框 ①】首层菜单框 {x0, x1, y0, y1}。
     *
     * <p>数值来自用户 2026-09-28 亲笔画框的两张现场图（{@code 屏幕截图 2026-09-27 014426.png}
     * 框 x362..694 / y239..446、{@code 屏幕截图 2026-09-28 185255.png} 框 x346..695 /
     * y264..422），机器量红框线后取并集，再按 1026x795 → 1030x797 换算。
     * 框内正好是「对话/任务、提升军团等级、我要回去、军团长期不上线、取消」五行。
     *
     * <p><b>0927 01:09 事故根因</b>：旧实现全屏扫「军团任务」字样，被右侧任务追踪器
     * <b>常驻</b>的「1级军团任务 已完成」骗成「任务选项还在」→ 做满识别全程失灵 →
     * 4 轮空点 + 人工告警。所以「有没有军团任务」「基础项计数」都必须<b>只看这个框内</b>
     * （x 上限 698 天然避开追踪器 x≥807，y 上限 447 避开 NPC 头顶「接受任务」浮动横幅 cy≈172）。
     * <b>用户 0928 明确要求：找字只在画好的框里找，不许再满屏扫。</b>
     */
    private static final int[] ZONE_MENU_BOX = {347, 698, 240, 447};

    // ==================== 名条判据（移植自抢线任务 0923 实测定标） ====================

    /** 右上角地图名条搜索区：军团地图/大厅没有小地图面板，名条顶在工具栏正下方 y≈63；
     *  城镇名条在小地图下方 y≈164，落不进来 —— 这就是「在不在军团」的核心判据。 */
    private static final int[] ZONE_MAP_NAME = {830, 1030, 45, 85};

    /** 名条裁剪区：军团地图/大厅这一带必能 OCR 出文字；城镇这一带是小地图（无文字）。 */
    private static final int[] BOX_MAP_STRIP = {826, 40, 1030, 92};

    /** 名条裁剪图的放大倍数（「大厅」整窗 2x 读不出来，4x 能读出坐标「（7，2）」）。 */
    private static final int STRIP_SCALE = 4;

    /** 名条关键词：命中即「在军团类地图」。先长后短。 */
    private static final String[] KW_LEGION_MAP = {"军团地图", "军团", "大厅"};

    /**
     * 【用户给定的 3 个框 ②】掌簿对话的「任务 / 选项」框 {x0, x1, y0, y1}。
     *
     * <p>数值来自用户 0928 现场图 {@code 屏幕截图 2026-09-28 185304.png}（接受任务确认屏，
     * 「请交给我吧 / 我暂时没空」实测 y372..393 / y≈400..420）与
     * {@code 屏幕截图 2026-09-28 185259.png}（二层任务列表「! 1级军团任务 / ! 1级机密任务」
     * 实测 y≈405 / y≈435）；取并集后 y358..470，x336..703（对话框内宽）。
     *
     * <p>用途：找二层的「1级军团任务」（{@link #KW_QUEST_ITEM}）与收尾按钮。
     * 下限 358 避开对话正文里的「军团任务」字样（正文 y271..320），
     * 上限 x703 避开右侧任务追踪器（x≥807 常驻「1级军团任务 已完成」），
     * 也避开结算面板里的「任务名称：1级军团任务」（y237）。
     */
    private static final int[] ZONE_OPTION = {336, 703, 358, 470};

    /**
     * 对话标题 / 正文的搜索区 {x0, x1, y0, y1}。
     *
     * <p>y 只留到 340：对话框标题与正文都在 y 240~320 这一带，而<b>军团面板的成员
     * 列表里也有「军团掌簿」这个职位名</b>（实测 @ (574,386)）—— 把 y 收到 340 就把它
     * 排除在外，不会把军团面板误当成对话标题去点正文。
     * x 上限 780 避开右上角地图条，下限 300 避开左侧状态球。
     */
    private static final int[] ZONE_DIALOG = {300, 780, 190, 340};

    /** 任务选项的固定兜底坐标（实测：对话菜单屏「1级军团任务」@ (420,408)）。 */
    private static final int[] PT_QUEST_ITEM = {420, 408};

    /**
     * 「完成任务」结算窗口的橙色「确定」按钮中心。
     *
     * <p>实测定标（{@code legion_shots/211355_round2_done.png}）：按钮橙色像素
     * bbox x490..551 / y507..530，中心 (520,518)。该按钮上的「确定」二字
     * <b>OCR 读不出来</b>（橙底小字），所以按钮<b>定位</b>只能靠颜色团块质心 +
     * 固定坐标；但面板的「在不在」已经改由面板文字 OCR 判（见
     * {@link #settleWindowPresent}，0926 事故：纯色块判据被场景橙木骗成恒真）。
     */
    private static final int[] PT_SETTLE_OK = {520, 518};

    /** 结算按钮橙色像素数门槛（实测定标：真按钮 754 个，军团面板上的橙色装饰只有 59 个）。
     *  0926 起只用于定位（质心够大才用），不再作为「结算窗在不在」的判据。 */
    private static final int SETTLE_OK_MIN_PX = 300;

    /** 结算按钮橙色团的 y 跨度门槛（真按钮 21px，军团面板那条橙色装饰只有 9px）。 */
    private static final int SETTLE_OK_MIN_SPAN_Y = 15;

    /**
     * 结算「确定」按钮的橙色检测区。
     *
     * <p>面积与小节标题同宽，特意只框住按钮本体、<b>不含右侧那颗红色「评价·赏」印章</b>
     * （印章也是纯红，会满足橙色判据）。
     */
    private static final int[] PATCH_SETTLE_OK = {486, 503, 70, 32};

    /**
     * 【用户给定的 3 个框 ③】「完成任务 / 任务详情」面板框 {x0, x1, y0, y1}。
     *
     * <p>数值来自用户 0928 亲笔画框的现场图 {@code 屏幕截图 2026-09-28 185236.png}
     * （红框线实测 x324..711 / y142..589），换算到 1030x797 基准。
     * 框内自上而下：标题「完成任务 1级军团任务，获得评价 甲」y≈178、
     * 「任务名称：1级军团任务」y≈237、「任务内容」y≈270、「你完成的很好…」y≈303、
     * 橙色「确定」y≈518、红印章「评价·赏」y≈480。
     *
     * <p>x 上限 713 天然避开右侧任务追踪器（x≥807，那里常驻「1级军团任务 已完成」，
     * 绝不能让它参与判面板）；y 下限 142 虽然会框到 NPC 头顶「接受任务」浮动横幅
     * （cy≈172），但那行字不含 {@link #KW_QUEST_PANEL} 里的任何词，不会误判
     * （062209 现场就是只有横幅没有面板 —— 靠关键词区分，不靠区域）。
     */
    private static final int[] ZONE_QUEST_PANEL = {325, 713, 142, 592};

    /**
     * 任务面板独有的文字（长词在前）。
     *
     * <p>菜单对话、正文屏、场景浮动横幅里都没有这些字。面板无论在「接任务」还是
     * 「交任务（结算）」状态，标题栏「任务名称」与栏头「任务内容」都在。
     *
     * <p><b>注意别把「完成任务 … 获得评价 …」放进来</b>（0928 实测）：那句是 NPC 头顶
     * 的浮动横幅，在<b>军团面板（按 O）</b>画面上同样会出现
     * （{@code legion_shots/213045_legion_panel.png} y≈168），拿它当面板特征会误判。
     * 它另有用途，见 {@link #KW_QUEST_DONE_HINT}。
     */
    private static final String[] KW_QUEST_PANEL = {
            "任务名称", "任务内容", "你完成的很好", "嘉奖",
            // 0927 02:11 现场：另一台客户端面板字也会误读（任→倩/仟 之类首字符失真，
            // 相似度 0.72 擦边不中），补去首字/去头兜底 —— 只在面板区扫，均安全。
            "务名称", "务内容", "完成的很好", "的嘉奖"
    };

    /**
     * 【0928 用户语义】「军团任务已完成（待还）」说明：「完成任务 1级军团任务，获得评价 甲」。
     *
     * <p>用户原话：「如果找到军团任务完成的说明，就是接了任务还没还，<b>务必继续做下去</b>」。
     * 也就是说这句说明 ＝ <b>这一轮有进展</b>（任务接了/做完了，还差交任务那一步），
     * <b>绝不是「今日已做满」的收工信号</b>（收工只认「明日请早」）。
     *
     * <p>它出现在两处：完成任务面板顶栏（185236 y≈178）、NPC 头顶浮动横幅
     * （军团面板 213045 y≈168）。所以它<b>只用来判「有进展、继续」</b>，
     * 不用来判面板在不在（见 {@link #KW_QUEST_PANEL} 的注释）。
     */
    private static final String[] KW_QUEST_DONE_HINT = {"获得评价", "完成任务"};

    /**
     * 系统提示区（左下角「[系统] …」消息带）搜索框 {x0, x1, y0, y1}。
     *
     * <p>这里放的是游戏对玩家动作的<b>拒绝回执</b>。实测行都在 x≈50..640 / y≈540..680
     * 这一带（见 {@code legion_shots/115753_opt_r1_quest.png}：
     * 「[系统]玩家状态错误,不能执行该动作」OCR 读数 {@code (161,617)}）。
     * x 上限 660 避开左下角状态球与右下角技能栏，y 下限 535 避开聊天大喇叭区。
     */
    private static final int[] ZONE_SYS_MSG = {40, 660, 535, 690};

    /**
     * 「游戏拒绝了这个动作」的系统提示词（2026-10-01 新增）。
     *
     * <p><b>现场</b>（{@code legion_shots/115753_opt_r1_quest.png}，11:57 那次运行）：
     * 第 1 轮点二层「1级军团任务」→ 点「请交给我吧」之后，系统提示区出现
     * <b>「[系统]玩家状态错误,不能执行该动作」</b> —— 也就是<b>这一次接取被游戏拒了</b>，
     * 任务根本没接上。但同一帧的对话框确实关掉了，旧实现按「对话自动关闭 ＝ 已被
     * 游戏处理」判定本步成功 → 5 轮里第 1 轮是虚的，实际只有 4 次进账
     * （11:59:20 的收尾面板截图 {@code 115920_legion_panel.png} 实锤「军团任务 4/5」，
     * 而日志却在 11:59:21 报「已完成 5 轮」）。
     *
     * <p><b>根因</b>：「对话关闭」这个验收信号<b>有歧义</b> —— 既可能是「选项被接受」，
     * 也可能是「选项被拒绝后游戏把框关掉」。必须叠加一条否定判据（系统回执里没有
     * 拒绝词）才能区分。这正是读屏铁律「验收要用下一步 UI 的正面证据」的又一次翻车：
     * 当时把「关闭」当成了正面证据。
     *
     * <p><b>OCR 误读兜底</b>：实测读数是「<b>統</b>玩家状态<b>错</b>误不能执行该动作」
     * （「[系统]」读成「統」，「错」偶被读成「蜡」），但「玩家状态错误」「不能执行该动作」
     * 两段都完整读出 → 既能命中。⚠ 特意<b>不收单薄的「不能执行」</b>：
     * 那是泛用片段，系统区里别的正常提示（如「你不能执行此操作」）也可能含它 → 误报。
     * 两个长片段互为冗余（任一读丢还有另一个），已足够稳。
     */
    private static final String[] KW_ACTION_REJECTED = {
            "玩家状态错误", "不能执行该动作", "状态错误", "不能执行该"
    };

    /**
     * 【用户 2026-10-01 给定】右侧「任务追踪」面板里「1级军团任务」那一条的搜索框。
     *
     * <p>数值来自用户现场图 {@code 屏幕截图 2026-09-28 185229.png}（1026x795）上
     * 亲笔画的红框：机器量红框线得 x 769..972 / y 309..382（框内正好是
     * 「1级军团任务[已完成]」+「NPC:军团掌簿」+「道具:神机石-2级3/8」三行），
     * 换算到 1030x797 基准后取 x 772..976 / y 306..386（四周各留 ~4px 余量）。
     *
     * <p><b>为什么必须圈定这个框</b>（0927 01:09 事故）：右侧任务追踪器里
     * 「1级军团任务」是<b>常驻</b>条目 —— 全屏扫这个词会被它恒真骗过。反过来，
     * 现在要用的正是「这个常驻条目还在不在」这个信号，所以必须<b>只看这个框内</b>，
     * 才能与左侧对话/面板里的同名字样彻底隔离（用户 0928 铁律：只在给定框里找字）。
     */
    private static final int[] ZONE_QUEST_TRACKER = {772, 976, 306, 386};

    /**
     * 追踪器里「还有军团任务」的关键词（用户 2026-10-01 语义）。
     *
     * <p>现场实读：「1级军团任务[已完成]」。取「1级军团任务」为主判据，
     * 「军团任务」为 OCR 读丢首字时的兜底前缀（本客户端常见形近误读）。
     */
    private static final String[] KW_TRACKER_LEGION = {"1级军团任务", "级军团任务", "军团任务"};

    /**
     * 收尾时「追踪器还有任务 → 再补跑一轮」的最大补跑次数（防死循环）。
     *
     * <p>用户语义：追踪器里还有「1级军团任务」条目 ⇒ 还有活没干完 ⇒ 继续 G 对话一轮；
     * 追踪器干净了 ⇒ 真做满 ⇒ 收工。正常情况下补跑 0~1 次即可收敛；
     * 给到 3 次是为了容忍 OCR 偶发误判与任务刷新延迟，触顶即停止（避免无限补跑）。
     */
    private static final int TRACKER_EXTRA_ROUNDS = 3;

    /** 「追踪器还有任务」的两帧确认间隔（铁律：绝不信单帧）。 */
    private static final int TRACKER_RECHECK_MS = 450;

    /** NPC 正文屏的兜底点击点（对话菜单屏与正文屏的正文区共同内部点）。 */
    private static final int[] PT_DLG_BODY = {515, 310};

    /** 对话标题关键词 —— 认出标题后点它下方 {@link #DLG_BODY_DY} 像素处推进正文。 */
    private static final String[] KW_DLG_TITLE = {
            "军团掌簿", "团掌簿", "掌簿", "军团尉", "尚书郎"
    };

    /** 标题 → 正文的点击偏移（实测标题 y≈248、正文 y 265..301）。 */
    private static final int DLG_BODY_DY = 60;

    /**
     * 点任务选项的硬上限轮数。
     *
     * <p>0927 02:11 教训：4 轮一刀切会在正常推进中误中止 —— 真正的中止条件是
     * {@link #BLIND_ABORT} 连续盲轮，这里只是兜底硬上限。
     *
     * <p>2026-10-01 调高 24 → 32：新增「被游戏拒绝 → 重试」分支后，一次「拒绝 + 重来」
     * 要多耗 3~4 轮内层循环（唤起对话 → 点首层 → 进二层 → 再点任务）。
     * 若第 1 轮连续被拒两次，24 轮的预算会在第 5 次任务之前耗尽 —— 那正是 0930
     * 「第 5 次永远轮不到」的同款死法。32 轮给 5 次任务 × (3 轮 + 1 次重试) 留足余量。
     */
    private static final int OPTION_TRIES = 32;

    /** 连续这么多轮「画面上啥可交互元素都认不出」（按 G 也唤不出对话）才中止。 */
    private static final int BLIND_ABORT = 6;
    /** 「今日已做满」两帧确认的间隔（铁律：绝不信单帧，450ms 同款）。 */
    private static final int FULL_RECHECK_MS = 450;

    /** 传送后先等地图加载的缓冲（霸王城 awaitMap 同款）。 */
    private static final int MAP_SETTLE_MS = 2500;
    /** 传送后等名条确认的最长时间，超时中止。 */
    private static final int MAP_WAIT_MAX_MS = 20000;

    /**
     * 进大厅后、点任务前多等的落地缓冲（2026-10-01 新增）。
     *
     * <p>115753 现场：11:57:52 掌簿菜单就位，11:57:53 就点任务 —— 从传送落地到接任务
     * 只隔 1 秒，游戏回了「玩家状态错误,不能执行该动作」，这一次任务白做。
     * 第 2~5 轮（人早已在大厅里站着）从没出过这个问题，说明就是「传送后状态未稳定」。
     * 落 1.5 秒缓冲；代价可忽略，换来第 1 轮不再被拒。
     */
    private static final int HALL_SETTLE_MS = 1500;

    /** 逐屏推进的上限轮数（一轮约 1.5 秒）。 */
    private static final int ADVANCE_MAX_ROUNDS = 12;

    /** 连续这么多轮看不到任何可交互元素 → 判定画面已干净、本轮对话结束。 */
    private static final int CLEAN_ROUNDS = 2;

    /** 广告判定阈值：R-G 大于该值说明橙色弹窗还在 */
    private static final double TH_AD_RG = 20.0;
    /** 对话判定阈值：B-R 大于该值说明蓝色高亮条存在（即对话已弹出） */
    private static final double TH_DLG_BR = 35.0;

    // ==================== 虚拟键码 ====================

    private static final int VK_O     = 0x4F;
    private static final int VK_G     = 0x47;
    /**
     * 回车 —— <b>2026-09-25 起本类不再使用</b>。
     *
     * <p>常量保留下来只为把「为什么不能按它」写在代码里：回车在 QQ三国里同时是
     * 聊天输入框的开关，一旦某一步漏到主界面，后面的 G 就会被当成打字输入聊天框。
     * 整条任务链路已改成鼠标点击，见类注释。
     */
    @SuppressWarnings("unused")
    private static final int VK_ENTER = 0x0D;
    private static final int VK_ESC   = 0x1B;
    private static final int VK_F11   = 0x7A;

    // ==================== 节奏 ====================

    /** 步骤间缓冲（用户要求 2 秒） */
    private static final int STEP_MS = 2000;
    /** 点击后缓冲 */
    private static final int CLICK_MS = 1500;
    /** ESC 后缓冲 */
    private static final int ESC_MS = 1300;
    /** 窗口切换/短动作缓冲 */
    private static final int SHORT_MS = 800;

    // ==================== 运行状态 ====================

    private final GameWindowController controller;
    private final Listener listener;
    private final int rounds;
    private final boolean hidePlayers;
    private final boolean forceCloseAds;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;

    private final File shotDir = new File("legion_shots");
    private int anomalyCount = 0;

    /**
     * 真正「被游戏接受」的任务次数 —— 每点中一次确认框且<b>没收到拒绝回执</b>才 +1。
     *
     * <p>2026-10-01 新增：旧实现把「对话框关了」当成功（有歧义），曾导致 5 轮里
     * 第 1 轮被游戏拒（「玩家状态错误」）却仍报「已完成 5 轮」，实际面板只有 4/5。
     * 这个计数器提供一条不依赖 OCR 读面板数字的<b>内部事实</b>，收尾时与目标轮次对账。
     */
    private int questAccepted = 0;

    public LegionTask(GameWindowController controller, Listener listener,
                      int rounds, boolean hidePlayers, boolean forceCloseAds) {
        this.controller = controller;
        this.listener = listener;
        this.rounds = Math.max(1, rounds);
        this.hidePlayers = hidePlayers;
        this.forceCloseAds = forceCloseAds;
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
            listener.log("军团任务已在运行中");
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
        }, "LegionTask");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步执行一次完整任务（供 {@link #start()} 与「一键日常」序列共用）。
     *
     * <p><b>后台模式作用域也在这里</b>：进入时记下用户原来的后台开关并强制开启
     * （截图走 PrintWindow、点击走窗口消息，全程不抢焦点、不动真实鼠标），
     * 退出时原样还原 —— 组队任务同款做法。
     */
    public TaskOutcome runOnce() {
        if (running) {
            return new TaskOutcome(false, "军团任务已在运行中");
        }
        running = true;
        stopRequested = false;
        boolean savedBg = controller.isRunInBackground();
        try {
            controller.setRunInBackground(true);
            listener.log("军团任务以「后台模式」运行（不抢焦点、不移动鼠标）");
            execute();
            // 【2026-10-01】汇总用内部计数（不依赖 OCR 读面板）：被游戏拒过的轮次不再算数。
            if (questAccepted < rounds) {
                return new TaskOutcome(true, "已完成 " + questAccepted + " / " + rounds
                        + " 轮军团任务（少 " + (rounds - questAccepted)
                        + " 次：某一轮被游戏拒绝执行，可再点一次按钮补做）");
            }
            return new TaskOutcome(true, "已完成 " + questAccepted + " 轮军团任务");
        } catch (QuestDone d) {
            // 0927：做满 5 次不是错误 —— 按用户确认的真实流程优雅结束，
            // 一键日常汇总里也算成功，不再弹「需要人工处理」。
            return new TaskOutcome(true, d.getMessage() + "，跳过");
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
        listener.log("================ 军团初级任务 开始 ================");
        listener.log("目标轮次：" + rounds + " 轮　隐藏玩家(F11)：" + (hidePlayers ? "是" : "否")
                + "　强制关广告：" + (forceCloseAds ? "是" : "否"));

        Rectangle r = controller.getWindowRect();
        if (r == null) {
            throw new Abort("找不到游戏窗口，请先启动 QQ三国 并确认窗口未关闭");
        }
        listener.log("游戏窗口：" + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");

        int[] savedCursor = controller.getCursorPosition();
        try {
            // ---------- 阶段 1：接管窗口（后台模式不抢焦点） ----------
            if (controller.isRunInBackground()) {
                listener.log("后台模式：不抢焦点，直接给游戏窗口发消息");
            } else {
                controller.focusWindow();
                sleep(SHORT_MS);
                listener.log("已把游戏窗口切到前台");
            }

            if (hidePlayers) {
                controller.sendKey(VK_F11);
                listener.log("按 F11 隐藏周围玩家（若原本已隐藏，这一次会恢复显示，不影响后续流程）");
                sleep(SHORT_MS);
            }

            // ---------- 阶段 2：关闭广告弹窗 ----------
            closeAds();

            // 0927：开局先认一次「今日已做满」—— 残留的「明日请早」提示框直接优雅收工，
            // 不进轮次（用户定稿：做满不是错误）。只认提示文字；首层菜单不是做满信号。
            if (dailyFullDetected()) {
                listener.log("✔ 开局即认出「今日已做满」→ 今天的军团任务已做完，任务正常结束");
                snapshot("quest_full_today");
                throw new QuestDone();
            }

            // ---------- 阶段 3：执行任务轮次 ----------
            for (int i = 1; i <= rounds; i++) {
                checkStop();
                if (i == 1) {
                    doFullRound(i);
                } else {
                    doShortRound(i);
                }
                afterRound(i);
            }

            listener.log("✔ 全部 " + rounds + " 轮动作执行完毕（实际被游戏接受 "
                    + questAccepted + " 次）");
            if (questAccepted < rounds) {
                listener.log("⚠ 有 " + (rounds - questAccepted)
                        + " 轮被游戏拒绝执行（系统回执为「拒绝」）—— 这次是真的少做了，"
                        + "不再是旧版那种「报了 5 轮其实只有 4 次」的静默漏做");
            }

            // ---------- 阶段 3.5【用户 2026-10-01 新增】追踪器门禁：确认是否真的做满 ----------
            // 用户语义：右侧任务追踪器里还挂着「1级军团任务」条目 ⇒ 还有活没干完 ⇒
            // 继续 G 对话再跑一轮；追踪器干净了 ⇒ 才彻底做完。
            // 这道门禁正好补上「5 轮实际只有 4 次」那类漏做的最后一环：
            // 内部计数不足时靠它兜回来，计数足但游戏其实没做满时也靠它兜回来。
            for (int extra = 1; extra <= TRACKER_EXTRA_ROUNDS; extra++) {
                checkStop();
                if (!trackerStillHasLegionTask()) {
                    listener.log("✔ 右侧任务追踪器里已无军团任务条目 → 确认已彻底做完");
                    break;
                }
                listener.log("⚠ 右侧任务追踪器里仍有「1级军团任务」条目 → 说明还没做完，"
                        + "自动补跑第 " + extra + "/" + TRACKER_EXTRA_ROUNDS + " 轮");
                snapshot("tracker_extra_" + extra + "_before");
                try {
                    doShortRound(rounds + extra);
                } catch (QuestDone qd) {
                    listener.log("  ✔ 补跑过程中认出「今日已做满」提示 → 任务正常结束");
                    snapshot("quest_full_today");
                    throw qd;
                }
                afterRound(rounds + extra);
            }
            if (trackerStillHasLegionTask()) {
                listener.log("⚠ 连续补跑 " + TRACKER_EXTRA_ROUNDS
                        + " 轮后追踪器里仍有军团任务条目 —— 已停止自动补跑，"
                        + "请人工看一眼是否已 5/5（可能是 OCR 误判，也可能还有剩余次数）");
                snapshot("tracker_still_dirty");
            }

            String p = snapshot("final_done");
            if (p != null) {
                listener.log("最终画面截图：" + p);
            }

            // ---------- 阶段 4：打开军团面板截图，方便人工核对 5/5 ----------
            verifyProgress();

        } finally {
            // 后台模式不动真实鼠标（用户可能正在用电脑）
            if (!controller.isRunInBackground() && savedCursor != null) {
                controller.moveCursor(savedCursor[0], savedCursor[1]);
            }
        }
    }

    // ==================== 阶段 2：关闭广告弹窗 ====================

    private void closeAds() {
        listener.log("—— 阶段 1/3：关闭游戏广告弹窗 ——");

        boolean adPresent = adHotspotPresent();
        if (!adPresent && !forceCloseAds) {
            listener.log("未检测到「热点活动」弹窗，跳过关广告阶段（避免误按 ESC 打开游戏菜单）");
            return;
        }

        // 「游戏活动展示」大面板：ESC 关闭（实测首次 ESC 即可关闭，按满 3 次更稳）
        listener.log("检测到广告，使用 ESC 关闭「游戏活动展示」面板");
        for (int i = 1; i <= 3; i++) {
            checkStop();
            controller.sendKey(VK_ESC);
            listener.log("  ESC 第 " + i + " 次");
            sleep(ESC_MS);
        }

        // 「热点活动」弹窗：它是独立窗口，ESC 无效，必须点右上角 X
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
            snapshot("ad_still_open");
            abort("广告弹窗「热点活动」未能关闭", "未能关闭广告弹窗，后续点击会被弹窗遮挡。\n"
                    + "请手动关闭游戏内的广告窗口后重试。");
        }
        listener.log("  ✔ 广告弹窗处理完成");
    }

    // ==================== 阶段 3：单个轮次 ====================

    /**
     * 打开军团界面并点「回到军团」。
     *
     * <p>O 是开关，所以先探测面板是否已开（「回到军团」按钮 OCR 动态定位）再决定按不按。
     * 抽成独立方法是因为名条等待中途也可能要补一次同样的动作。
     */
    private void clickBackToLegion() {
        int[] lgBtn = null;
        try {
            lgBtn = SalaryTask.findBackToLegionButton(controller.captureWindow());
        } catch (Throwable t) {
            // 截图失败按 null 处理，走固定坐标
        }
        if (lgBtn == null) {
            controller.sendKey(VK_O);
            sleep(STEP_MS);
            try {
                lgBtn = SalaryTask.findBackToLegionButton(controller.captureWindow());
            } catch (Throwable t) {
                lgBtn = null;
            }
        } else {
            listener.log("  军团界面已打开（「回到军团」@ " + lgBtn[0] + "," + lgBtn[1] + "），不重复按 O");
        }

        if (lgBtn != null) {
            listener.log("  动态定位「回到军团」@ (" + lgBtn[0] + "," + lgBtn[1] + ")");
            controller.clickWindowPoint(lgBtn[0], lgBtn[1]);
        } else {
            listener.log("  未识别到按钮，退固定坐标 (" + PT_BACK_TO_LEGION[0] + "," + PT_BACK_TO_LEGION[1] + ")");
            controller.clickWindowPoint(PT_BACK_TO_LEGION[0], PT_BACK_TO_LEGION[1]);
        }
        sleep(CLICK_MS);
    }

    /** 第 1 轮：完整流程（含 O 面板与两次传送，每一步都有画面验收）。 */
    private void doFullRound(int n) {
        listener.log("—— 第 " + n + " 轮 / 完整流程 ——");
        listener.log("步骤 1+2：O 军团界面 → 点「回到军团」（传送到军团地图）");
        clickBackToLegion();
        // 0927 用户定稿：传送后必须用右上角小地图名条 OCR 确认真到了「军团地图」
        awaitLegionMap("回到军团", true);

        // 步骤 3~5：G 军团尉 → 点「进入军团大厅」→ G 军团掌簿。
        // 到位判据 = 掌簿弹出了可交互菜单：二层任务列表（「1级军团任务」）/ 首层基础
        // 菜单（对话/任务…）/ 做满提示框 —— 任一都是下一步 UI 的正面证据。
        // 传送没生效时 G 出来的还是军团尉的菜单（里面没有任务选项），
        // 自动整段重来，最多 3 次。
        boolean menuReady = false;
        for (int hop = 1; hop <= 3 && !menuReady; hop++) {
            checkStop();
            listener.log("步骤 3~5（第 " + hop + "/3 次）：G 军团尉 → 点「进入军团大厅」→ G 军团掌簿");
            controller.sendKey(VK_G);
            sleep(STEP_MS);
            ensureDialog("军团尉");

            listener.log("  点「进入军团大厅」（传送到大厅）");
            controller.clickWindowPoint(PT_ENTER_HALL[0], PT_ENTER_HALL[1]);
            sleep(CLICK_MS);

            controller.sendKey(VK_G);
            sleep(STEP_MS);
            ensureDialog("军团掌簿");

            BufferedImage img = controller.captureWindow();
            legionMapWord(img); // 只记日志：大厅二字 2x 常读不出，不作判据
            if (ScreenText.find(img, KW_QUEST_ITEM, ZONE_OPTION, 0) != null
                    || fullHintInFrame(img) || menuBaseNoQuest(img)) {
                listener.log("  ✔ 掌簿菜单就位（任务列表 / 首层菜单 / 做满提示）—— 人在大厅");
                menuReady = true;
                break;
            }
            if (hop < 3) {
                listener.log("  ⚠ G 出来的菜单里没有任务选项 → 传送可能没生效，按 G 关掉重来");
                controller.sendKey(VK_G);
                sleep(STEP_MS);
            }
        }

        // 【2026-10-01 新增】刚传送进大厅时角色可能还在「移动/加载」状态，
        // 此时立刻接任务会被游戏拒（115753 现场的「玩家状态错误,不能执行该动作」）。
        // 落一个短缓冲等状态稳定，比出事后再重试更省时间。
        sleep(HALL_SETTLE_MS);
        enterSequence(n);
    }

    /** 第 2 轮起：精简循环（人已在军团大厅掌簿旁边）。 */
    private void doShortRound(int n) {
        listener.log("—— 第 " + n + " 轮 / 精简循环（大厅内）——");
        // 0927：先用名条确认人还在军团里。被传走/回城等意外可自动恢复，
        // 不再一头扎进 G 对话然后报「对话弹不出来」。
        Boolean inLg = inLegionMapFrame(controller.captureWindow());
        if (Boolean.FALSE.equals(inLg)) {
            listener.log("  ⚠ 名条判出人已不在军团 → 本轮改走完整流程重新进军团");
            doFullRound(n);
            return;
        }
        if (inLg == null) {
            listener.log("  ⚠ 名条 OCR 判不出来 → 继续精简循环（对话校验会兜底报错）");
        } else {
            listener.log("  ✔ 名条确认仍在军团内");
        }

        listener.log("步骤 1：按 G 与军团掌簿对话");
        controller.sendKey(VK_G);
        sleep(STEP_MS);
        ensureDialog("军团掌簿");

        enterSequence(n);
    }

    /**
     * 对话弹出后的收尾动作 —— <b>全程鼠标点击，一次回车都不按</b>。
     *
     * <p>回车会顺手打开聊天输入框（见类注释），所以「接受任务 / 确认 / 关闭结算」
     * 这三件事全部改成点画面上的元素，每一步都拿画面判据验收。
     */
    private void enterSequence(int n) {
        listener.log("步骤 6：点「对话/任务」→「1级军团任务」（鼠标点击，不再按回车；"
                + "对话自动关闭=选项已被处理）");
        clickQuestOption();

        listener.log("步骤 7：逐屏推进 —— 点「确定」/ 点正文，直到对话框全部关闭");
        advanceDialogsUntilClean(n);

        snapshot("round" + n + "_dialogs_done");
    }

    /**
     * 画面里是不是第三层「确认接取任务」那一屏 —— 认到就返回「请交给我吧」的点击坐标。
     *
     * <p>只在 {@link #ZONE_OPTION}（用户给定的三个框之一）里找，绝不满屏扫（0928 铁律）。
     * {@code null} ＝ 这一屏不是确认框。{@link #clickQuestOption} 与
     * {@link #advanceDialogsUntilClean} 共用同一判据，避免两处口径漂移。
     */
    private int[] findAcceptQuestPoint(BufferedImage img) {
        if (img == null) {
            return null;
        }
        int[] pt = ScreenText.find(img, KW_ACCEPT_QUEST, ZONE_OPTION, 0);
        if (pt != null) {
            return pt;
        }
        // 【2026-09-30 兜底】万一「请交给我吧」整行读糊（实测「给→绐」+ 吞字），
        // 改用同屏第二个选项「我暂时没空」反推：104148 现场里「没空」是读对的，
        // 而两行选项间距固定 23px（405 → 428，见现场帧），点上一行即可。
        // 只认「没空」两字而不认「暂」——「暂/暫」在本作客户端两种字形都出现过，
        // 「没空」更稳；命中多行时取最上面那行。
        try {
            ScreenText.TextLine no = null;
            for (ScreenText.TextLine ln : ScreenText.lines(img)) {
                if (ln.cx < ZONE_OPTION[0] || ln.cx > ZONE_OPTION[1]
                        || ln.cy < ZONE_OPTION[2] || ln.cy > ZONE_OPTION[3]) {
                    continue;
                }
                String n = XiaolianBank.normalize(ln.text);
                if (n.contains("没空") || n.contains("沒空")) {
                    if (no == null || ln.cy < no.cy) {
                        no = ln;
                    }
                }
            }
            if (no != null) {
                int cy = no.cy - 23;
                if (cy >= ZONE_OPTION[2] && cy <= ZONE_OPTION[3]) {
                    return new int[]{no.cx, cy};
                }
            }
        } catch (Throwable t) {
            // 忽略：返回 null，交给调用方原有的兜底分支处理
        }
        return null;
    }

    /**
     * 在掌簿对话里点掉任务选项 —— 分两层（0927 01:44 用户定稿）。
     *
     * <p>首层菜单（对话/任务、提升军团等级、我要回去、军团长期不上线、取消）是
     * <b>每轮都会出现的正常菜单</b>：先点高亮的第一项「对话/任务」进二层；
     * 二层任务列表再点「1级军团任务」。
     *
     * <p>定位优先级：① 「明日请早/做够五次」提示（两帧一致）→ 优雅收工；
     * ② OCR 找「1级军团任务」（二层）→ ③ OCR 找「对话/任务」（首层）→
     * ④ 对话开着但一行都读不出（0211 现场：这台客户端面板字 OCR 读不出）
     * → 点高亮条，同一位置没进展就改点面板「确定」标定位 (520,518)。
     *
     * <p><b>验收语义（0927 02:11 现场修正）</b>：点中 <b>OCR 实测</b>的「1级军团任务」行后
     * —— 面板弹出 ＝ 最理想；<b>对话自动关闭 ＝ 任务已被游戏处理（接/交完成），本步也算
     * 完成</b>。02:11 现场：交任务后对话直接关、面板字又读不出，旧判据把「关闭」当
     * 误点 → 空转 4 轮后误中止，实际任务状态一直在推进（追踪器 10/10 已完成）。
     * 只有「兜底坐标点出去」导致的关闭才按事故处理（0926 第 1 轮事故：点在菜单边上）。
     * 中止条件 ＝ <b>连续 {@value #BLIND_ABORT} 盲轮</b>（啥都认不出、按 G 也唤不出），
     * 硬上限 {@value #OPTION_TRIES} 轮；每一步都留 opt_r* 现场截图。
     */
    private void clickQuestOption() {
        int blind = 0;          // 连续「画面上啥可交互元素都认不出」的轮数
        int unreadStreak = 0;   // 连续「对话开着但一行读不出」的轮数
        int fullStreak = 0;     // 连续「疑似今日已做满」的轮数（防单帧误判，见 ① 的加固）
        int[] lastFallback = null;
        for (int round = 1; round <= OPTION_TRIES; round++) {
            checkStop();
            BufferedImage img = controller.captureWindow();

            // ① 做满提示（明日请早 / 做够+五次）—— 唯一可信的做满信号，两帧一致
            //    （0927 01:44 教训：首层基础菜单每轮都有，绝不是做满信号）
            if (fullHintInFrame(img)) {
                fullStreak++;
                if (fullStreak >= 2 || dailyFullDetected()) {
                    snapshot("quest_full_today");
                    listener.log("  ✔ 认出「今日已做满」提示 → 今天的军团任务已做完，任务正常结束");
                    closeDialogAfterFull();
                    throw new QuestDone();
                }
                // 【2026-09-30 加固】单帧命中了、但复核还没过（`dailyFullDetected` 的两帧
                // 间隔只有 450ms，对话框渐入动画 / 字还没渲染全时第二帧会读不出）。
                // 此时**绝不能继续往下走去点任务选项** —— 那一手会把提示框点掉，
                // 之后再也认不出做满 → 一路空转到 OPTION_TRIES 硬上限误报「需要人工处理」
                // （今天 10:17 现场就是这个死法：做满提示被自己点没了）。
                // 什么手都不动，等下一轮再看一眼（跨循环间隔 ≈4s，比 450ms 更稳）。
                snapshot("opt_r" + round + "_full_wait");
                listener.log("  第 " + round + " 轮：疑似「今日已做满」提示（复核未过，"
                        + fullStreak + "/2）→ 不动手，等下一轮再看");
                sleep(SHORT_MS);
                continue;
            }
            fullStreak = 0;

            // ①.5【2026-09-30 修复】第三层「确认接取任务」框 → 点「请交给我吧」。
            //     点二层「1级军团任务」之后，游戏还会再弹一层确认
            //     （正文「…你确定要接受这个任务吗？」+「请交给我吧 / 我暂时没空」）。
            //     旧实现完全不认这一层：每轮都判「对话还开着但面板没认出」的失败 →
            //     空转 24 轮后误报「需要人工处理」（用户 0930 报「最后一步必失败」）。
            //     必须先于 ② 判断，否则这一屏会被当成「既不是任务列表也不是首层菜单」
            //     一路漏到兜底分支去。
            int[] acc = findAcceptQuestPoint(img);
            if (acc != null) {
                snapshot("opt_r" + round + "_accept");
                listener.log("  第 " + round + " 轮：认出【确认接取任务】框 → 点「请交给我吧」@ ("
                        + acc[0] + "," + acc[1] + ")");
                controller.clickWindowPoint(acc[0], acc[1]);
                sleep(CLICK_MS);
                // 与「1级军团任务」同款语义：点中 OCR 实测的行 ＝ 已被游戏处理，本步完成。
                // 收尾推进交给 advanceDialogsUntilClean（它同样认这一层，双保险）。
                BufferedImage afterAccept = controller.captureWindow();
                if (findAcceptQuestPoint(afterAccept) != null) {
                    listener.log("  ⚠ 点了「请交给我吧」但这屏还在 → 下一轮再点一次");
                    continue;
                }
                // 【2026-10-01 新增】确认框是关掉了，但要再问一句「游戏是不是拒绝了我」——
                // 115753 现场：框关了、系统却回「玩家状态错误,不能执行该动作」，任务压根没接上。
                // 认到拒绝词就**不算完成**，回外层重来（本轮还会再走一次完整选项流程）。
                if (actionRejected(afterAccept)) {
                    snapshot("opt_r" + round + "_rejected");
                    listener.log("  ✘ 确认框关了但系统回执是「拒绝执行」→ 这一次没接上，回重试");
                    sleep(SHORT_MS);
                    continue;
                }
                listener.log("  ✔ 已点「请交给我吧」—— 任务确认框已被游戏处理，本步完成");
                questAccepted++;
                listener.log("    [计数] 本次运行已被游戏接受的任务数：" + questAccepted
                        + " / 目标 " + rounds);
                return;
            }

            // ② 二层任务列表的「1级军团任务」→ 点它
            int[] pt = ScreenText.find(img, KW_QUEST_ITEM, ZONE_OPTION, 0);
            boolean questRow = (pt != null);

            // ③ 首层菜单的「对话/任务」→ 点它进二层（0927 01:44 用户定稿：
            //    这个基础菜单每一轮都出现，必须点高亮的第一项）
            if (pt == null) {
                pt = findTalkOptionInZone(img, listener);
            }

            if (pt != null) {
                blind = 0;
                unreadStreak = 0;
                String what = questRow ? "1级军团任务" : "对话/任务";
                listener.log("  第 " + round + " 轮：点「" + what + "」@ (" + pt[0] + ","
                        + pt[1] + ") —— OCR 实测");
                snapshot("opt_r" + round + (questRow ? "_quest" : "_talk"));
                controller.clickWindowPoint(pt[0], pt[1]);
                sleep(CLICK_MS);

                if (!questRow) {
                    // 首层「对话/任务」：只要求菜单有反应（换成二层任务列表 / 做满提示 /
                    // 结算面板），下一轮再认下一层。这里复核菜单确实换了样子，别原地连点。
                    BufferedImage img3 = controller.captureWindow();
                    int[] again = findTalkOptionInZone(img3, null);
                    if (again != null && Math.abs(again[0] - pt[0]) <= 14
                            && Math.abs(again[1] - pt[1]) <= 14) {
                        listener.log("  ⚠ 点了「对话/任务」但首层菜单没变 → 下一轮重试");
                    } else {
                        listener.log("  ✔ 已点「对话/任务」，首层菜单已切换 → 下一轮认二层界面");
                    }
                    sleep(SHORT_MS);
                    continue;
                }

                // 二层任务行验收：面板弹出 ＝ 最理想；对话自动关闭 ＝ 已被游戏处理。
                // 0928 补充：认出「完成任务 … 获得评价 …」说明也算已处理 —— 用户语义
                // 「找到这个说明就是这轮已经做完了，务必继续做下去」（继续，不是收工）。
                boolean panelUp = false;
                boolean menuStill = false;
                for (int chk = 0; chk < 2; chk++) {
                    BufferedImage img2 = controller.captureWindow();
                    if (questPanelTextPresent(img2)) {
                        panelUp = true;
                        break;
                    }
                    if (questDoneHintInFrame(img2)) {
                        listener.log("  ✔ 认出「军团任务已完成（待还）」说明 —— 按用户语义："
                                + "这轮已做完，点掉面板继续下一轮（绝不收工）");
                        panelUp = true;
                        break;
                    }
                    // 【2026-09-30 新增】点「1级军团任务」后游戏会再弹一层确认框
                    // （正文「…你确定要接受这个任务吗？」+「请交给我吧 / 我暂时没空」）。
                    // 这里立刻认出来点掉 —— 修复前这一层没人认，要等下一轮才走 ①.5，
                    // 而兜底坐标又恰好在同一行上，于是每做 1 次任务白绕 6 轮内层循环，
                    // OPTION_TRIES(24) ÷ 6 = 刚好 4 次 → 第 5 次永远轮不到。
                    // 就地处理把它压到每任务 2~3 轮，预算问题连同症状一起消失。
                    int[] acc2 = findAcceptQuestPoint(img2);
                    if (acc2 != null) {
                        listener.log("  ✔ 点「1级军团任务」后弹出【确认接取任务】框 → 点「请交给我吧」@ ("
                                + acc2[0] + "," + acc2[1] + ")");
                        controller.clickWindowPoint(acc2[0], acc2[1]);
                        sleep(CLICK_MS);
                        // 【2026-10-01】同款拒绝回执复核 —— 被拒就不计数、回重试。
                        if (actionRejected(controller.captureWindow())) {
                            snapshot("opt_r" + round + "_rejected");
                            listener.log("  ✘ 点「请交给我吧」后系统回执是「拒绝执行」"
                                    + "→ 这一次没接上，回重试");
                            sleep(SHORT_MS);
                            continue;
                        }
                        questAccepted++;
                        listener.log("    [计数] 本次运行已被游戏接受的任务数：" + questAccepted
                                + " / 目标 " + rounds);
                        return;
                    }
                    boolean open;
                    try {
                        open = SalaryTask.scanImage(img2).dialogOpen;
                    } catch (Throwable t) {
                        open = true; // 读不了图宁可当「还开着」，下一轮再确认
                    }
                    if (open) {
                        menuStill = true;
                        break;
                    }
                    sleep(SHORT_MS); // 给面板弹出动画留时间，再看一眼
                }
                if (panelUp) {
                    listener.log("  ✔ 任务详情面板已弹出 —— 任务选项点中了");
                    return;
                }
                if (menuStill) {
                    listener.log("  ⚠ 对话还开着但面板没认出（面板字可能读不出）→ 下一轮接着认");
                    sleep(SHORT_MS);
                    continue;
                }
                // 对话自动关闭 —— 点击被游戏消费了（接/交任务处理完），本步完成。
                // （0926 的「误点关对话」只发生在兜底坐标；OCR 实测行点中的关闭是进展。）
                //
                // 【2026-10-01 新增】但「关闭」有歧义：115753 现场里关掉的原因是
                // **游戏拒绝了这一手** —— 同一帧系统区写着「玩家状态错误,不能执行该动作」。
                // 先查拒绝回执，认到就不算完成，回外层重来。
                BufferedImage closed = controller.captureWindow();
                if (actionRejected(closed)) {
                    snapshot("opt_r" + round + "_rejected");
                    listener.log("  ✘ 点「1级军团任务」后对话关了，但系统回执是「拒绝执行」"
                            + "→ 这一手没生效，回重试");
                    sleep(SHORT_MS);
                    continue;
                }
                listener.log("  ✔ 点「1级军团任务」后对话自动关闭 —— 选项已被处理，本步完成");
                return;
            }

            // ④ 画面上没有对话 → 按 G 唤起；连续盲轮超限才中止
            boolean dialogOpen;
            SalaryTask.Ui ui;
            try {
                ui = SalaryTask.scanImage(img);
                dialogOpen = ui.dialogOpen;
            } catch (Throwable t) {
                ui = null;
                dialogOpen = false;
            }
            if (!dialogOpen) {
                blind++;
                listener.log("  第 " + round + " 轮：画面上没有对话（盲 " + blind + "/" + BLIND_ABORT
                        + "）→ 按 G 唤起对话");
                controller.sendKey(VK_G);
                sleep(STEP_MS);
                if (blind >= BLIND_ABORT) {
                    break; // 走末轮复查 + 中止
                }
                continue;
            }

            // ⑤a 【0928 用户语义】先看一眼是不是「军团任务已完成（待还）」：
            //     面板顶栏 / NPC 头顶横幅「完成任务 1级军团任务，获得评价 甲」。
            //     认出＝这一轮已经有进展、只差交任务 → 点面板「确定」收尾，本步结束，
            //     外层轮次循环继续往下做（**继续信号，绝不是收工信号**）。
            //     只在面板框 / 对话正文区里找，不满屏扫。
            if (questDoneHintInFrame(img)) {
                snapshot("opt_r" + round + "_donehint");
                int[] ok = locateSettleOk(img);
                listener.log("  第 " + round + " 轮：认出「军团任务已完成（待还）」说明 @ ("
                        + ok[0] + "," + ok[1] + ") → 按用户语义：这轮已做完，收尾并继续下一轮");
                controller.clickWindowPoint(ok[0], ok[1]);
                sleep(CLICK_MS);
                return;
            }

            // ⑤ 对话开着但一行都读不出（0211 现场实测）—— 先点高亮条（= 当前行），
            //    同一位置没进展就改点面板「确定」标定位 (520,518)。面板「确定」按钮
            //    位置固定（居中对话框标定）；开着的若是二层任务列表，该点落在列表
            //    下方空白处，无副作用。每一步都留截图。
            blind = 0;
            snapshot("opt_r" + round + "_unread");
            int[] fallback = (ui.option1X > 0 && ui.option1Y > 0)
                    ? new int[]{ui.option1X, ui.option1Y}
                    : PT_QUEST_ITEM.clone();
            int[] target;
            String src;
            if (unreadStreak >= 1 && lastFallback != null
                    && Math.abs(lastFallback[0] - fallback[0]) <= 14
                    && Math.abs(lastFallback[1] - fallback[1]) <= 14) {
                target = locateSettleOk(img);
                src = "面板「确定」标定位";
                unreadStreak = 0;
            } else {
                target = fallback;
                src = "高亮条兜底";
                unreadStreak++;
            }
            lastFallback = target;
            listener.log("  第 " + round + " 轮：对话开着但读不出任何行 → 点" + src + " @ ("
                    + target[0] + "," + target[1] + ")（已留现场截图）");
            controller.clickWindowPoint(target[0], target[1]);
            sleep(CLICK_MS);
        }

        // 中止前最后再查一次「今日已做满」提示
        // （只认「明日请早/做够五次」文字，两帧一致；首层菜单不是做满信号）
        if (dailyFullDetected()) {
            snapshot("quest_full_today");
            listener.log("  ✔ 末轮复查认出「今日已做满」提示 → 今天的军团任务已做完，任务正常结束");
            closeDialogAfterFull();
            throw new QuestDone();
        }

        String shot = snapshot("anomaly_legion_option");
        abort("点不动掌簿对话里的任务选项",
                "连续多轮都没能把「对话/任务 → 1级军团任务」点掉（硬上限 " + OPTION_TRIES
                        + " 轮，或连续 " + BLIND_ABORT + " 轮画面上啥都认不出）。\n\n"
                        + "常见原因：\n"
                        + "  ① 今天的军团任务已经做满 5/5（画面应有「明日请早」提示）\n"
                        + "  ② 对话菜单布局与标定时不一致\n"
                        + "  ③ 有别的弹窗挡在对话框上面\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "过程每一步都有 opt_r* 截图（legion_shots/），看一眼就知道卡在哪一屏。"
                        + "手动处理后再重跑。");
    }

    // （dialogMenuGone 已删，0926：菜单判据分不清「菜单关了」和「菜单换成了任务
    //   面板」，验收改由 clickQuestOption 里的面板 OCR 判据承担。）

    /**
     * 逐屏推进，直到画面上再没有可点的对话元素。
     *
     * <p>每轮按优先级判断当前是哪一屏，用对应方式过关（<b>全部是鼠标点击</b>）：
     * <ol>
     *   <li>任务详情 / 「完成任务」结算窗口（OCR 认出「任务名称/任务内容」面板文字）
     *       → 点橙色「确定」，<b>点完验证面板关闭</b>；</li>
     *   <li>又冒出带高亮条的对话菜单 → 点高亮条本身（兜底，正常不会走到）；</li>
     *   <li>军团面板还开着（按 O 没关干净）→ 按 O 关掉，<b>别去点正文</b>
     *       —— 军团成员列表里也有「军团掌簿」这个职位名；</li>
     *   <li>NPC 正文屏（认得出对话标题）→ 点标题下方的正文推进；</li>
     *   <li>什么都没认出来 → 记一轮「干净」，连续 {@value #CLEAN_ROUNDS} 轮都干净才收工。</li>
     * </ol>
     * 第 1 条要读屏认面板文字（0926 事故：纯色块判据被场景橙木骗成恒真，12 轮全点在
     * 空地上）；②③是纯像素判据；第 4 条才再读一次屏。全程有轮数上限，
     * 不会死循环，推不动也<b>不乱按键</b>，只如实记日志。
     */
    private void advanceDialogsUntilClean(int n) {
        int cleanStreak = 0;
        int unreadMenuRounds = 0; // 连续「对话开着但啥字都读不出」的轮数
        for (int round = 1; round <= ADVANCE_MAX_ROUNDS; round++) {
            checkStop();
            BufferedImage img = controller.captureWindow();
            if (img == null) {
                sleep(SHORT_MS);
                continue;
            }

            // ① 任务详情 / 「完成任务」结算窗口 → 点橙色「确定」
            //    0926 事故：旧纯橙色判据（橙像素 ≥300 且 y 跨度 ≥15）在大厅场景里恒真
            //    —— 角色脚下的橙木地板/木桶在采样区里也有 642 个橙像素（三次运行读数
            //    一像素不差），于是「结算窗口在」→ 12 轮全点在空地 (521,513) 上。
            //    现在必须 OCR 在面板区认出「任务名称/任务内容」才算面板在（与关广告
            //    判据同一铁律：色块会被环境同色骗，认字不会）；橙色团块只用来定位按钮。
            if (settleWindowPresent(img)) {
                cleanStreak = 0;
                unreadMenuRounds = 0;
                int[] pt = locateSettleOk(img);
                listener.log("  第 " + round + " 轮：【任务面板】OCR 认出面板文字 → 点「确定」@ ("
                        + pt[0] + "," + pt[1] + ")");
                snapshot("round" + n + "_settle");
                controller.clickWindowPoint(pt[0], pt[1]);
                sleep(CLICK_MS);
                // 点完必须验证面板关闭（抓鬼同款教训：点完不复核 = 假成功）
                if (questPanelTextPresent(controller.captureWindow())) {
                    listener.log("  ⚠ 结算面板还没关掉，下一轮再点一次");
                } else {
                    listener.log("  ✔ 结算面板已关闭");
                }
                continue;
            }

            // ①.5【2026-09-30 修复】第三层「确认接取任务」框 → 点「请交给我吧」。
            //     必须放在第 ② 条（还有对话菜单 → 点高亮条）之前：那一屏也是「对话开着」，
            //     会被 ② 当成无字菜单去点高亮条 —— 碰巧点在同一行却毫无日志，
            //     出问题时完全看不出发生了什么（0930 事故就是这种「静默撞对/撞错」）。
            int[] acc = findAcceptQuestPoint(img);
            if (acc != null) {
                cleanStreak = 0;
                unreadMenuRounds = 0;
                listener.log("  第 " + round + " 轮：【确认接取任务】认出「请交给我吧」@ ("
                        + acc[0] + "," + acc[1] + ") → 点它");
                snapshot("adv_accept_r" + round);
                controller.clickWindowPoint(acc[0], acc[1]);
                sleep(CLICK_MS);
                // 【2026-10-01 新增】拒绝回执复核（同 clickQuestOption 里的那道）：
                // 这一步万一被游戏拒了，也要留痕，别静默当成功。
                if (actionRejected(controller.captureWindow())) {
                    snapshot("adv_accept_r" + round + "_rejected");
                    listener.log("  ✘ 点「请交给我吧」后系统回执是「拒绝执行」→ 这一步没生效");
                }
                continue;
            }

            // ② 还有对话菜单（兜底）→ 点高亮条本身（纯像素判据）。
            //    连续 3 轮都卡在这一步 ＝ 对话开着但字全读不出（0927 02:11 现场实测，
            //    这台客户端面板字 OCR 读不出）→ 改点面板「确定」标定位 (520,518)。
            SalaryTask.Ui ui = SalaryTask.scanImage(img);
            if (ui.dialogOpen) {
                cleanStreak = 0;
                unreadMenuRounds++;
                if (unreadMenuRounds >= 3) {
                    int[] ok = locateSettleOk(img);
                    listener.log("  第 " + round + " 轮：对话连续 " + unreadMenuRounds
                            + " 轮读不出任何行 → 点面板「确定」标定位 @ (" + ok[0] + "," + ok[1]
                            + ")（已留截图）");
                    snapshot("adv_unread_r" + round);
                    controller.clickWindowPoint(ok[0], ok[1]);
                } else {
                    listener.log("  第 " + round + " 轮：还有对话菜单 → 点高亮条 @ ("
                            + ui.option1X + "," + ui.option1Y + ")");
                    controller.clickWindowPoint(ui.option1X, ui.option1Y);
                }
                sleep(CLICK_MS);
                continue;
            }
            unreadMenuRounds = 0;

            // ③ 军团面板还开着（按 O 没关干净）→ 先按 O 关掉，别去点正文
            //    军团成员列表里也有「军团掌簿」这个职位名，不排掉会被当成对话标题误点。
            if (legionPanelOpen(img)) {
                cleanStreak = 0;
                listener.log("  第 " + round + " 轮：检测到军团面板还开着（O 残留）→ 按 O 关掉");
                controller.sendKey(VK_O);
                sleep(STEP_MS);
                continue;
            }

            // ④ 读一次屏，认认是不是 NPC 正文 / 又冒出选项
            java.util.List<ScreenText.TextLine> lines = ScreenText.lines(img);
            // 0927：做完第 5 次交任务后，游戏会弹「你已经做够了五次任务，明日请早」
            // —— 这是正常收工信号，点掉它并优雅结束整个任务，不进下一轮。
            if (fullHintInFrame(img)) {
                listener.log("  ✔ 认出「今日已做满」提示（5/5 完成）→ 今天的军团任务已做完，任务正常结束");
                snapshot("quest_full_today");
                closeDialogAfterFull();
                throw new QuestDone();
            }
            ScreenText.TextLine title = findInLines(lines, KW_DLG_TITLE, ZONE_DIALOG);
            if (title != null) {
                cleanStreak = 0;
                listener.log("  第 " + round + " 轮：NPC 正文屏（标题「" + title.text
                        + "」@ (" + title.cx + "," + title.cy + ")）→ 点正文 @ ("
                        + PT_DLG_BODY[0] + "," + PT_DLG_BODY[1] + ") 推进");
                controller.clickWindowPoint(PT_DLG_BODY[0], PT_DLG_BODY[1]);
                sleep(CLICK_MS);
                continue;
            }
            ScreenText.TextLine item = findInLines(lines, KW_QUEST_ITEM, ZONE_OPTION);
            if (item != null) {
                cleanStreak = 0;
                listener.log("  第 " + round + " 轮：又看到任务选项「" + item.text + "」@ ("
                        + item.cx + "," + item.cy + ") → 点它");
                controller.clickWindowPoint(item.cx, item.cy);
                sleep(CLICK_MS);
                continue;
            }

            // ⑤ 认不出任何可点的对话元素 → 记一轮「干净」
            cleanStreak++;
            listener.log("  第 " + round + " 轮：没认出可点的对话框（干净 " + cleanStreak
                    + "/" + CLEAN_ROUNDS + "）");
            if (cleanStreak >= CLEAN_ROUNDS) {
                listener.log("  ✔ 对话框已全部关闭，本轮收工");
                return;
            }
            sleep(SHORT_MS);
        }
        listener.log("  ⚠ 推进到上限 " + ADVANCE_MAX_ROUNDS + " 轮仍未确认干净，直接进入下一轮"
                + "（若状态有残留，下一轮的对话校验会把问题报出来）");
        snapshot("anomaly_legion_advance_max");
    }

    private void afterRound(int n) {
        String p = snapshot("round" + n + "_done");
        listener.log("第 " + n + " 轮动作执行完毕" + (p != null ? "（截图：" + p + "）" : ""));
    }

    // ==================== 校验与异常处理 ====================

    /**
     * 确认 NPC 对话确实弹出了。没弹出就重按一次 G；仍然没有则判定为异常并中止。
     *
     * <p>这是「缺少物品 / 验证提示」的主要发现手段：物品不足或弹出验证窗口时，
     * 对话界面不会正常出现，或会出现完全不同的界面。
     */
    private void ensureDialog(String npcName) {
        if (dialogOpenNow()) {
            listener.log("  ✔ 已检测到「" + npcName + "」对话界面");
            return;
        }
        listener.log("  ⚠ 未检测到「" + npcName + "」对话界面，重按一次 G 重试");
        controller.sendKey(VK_G);
        sleep(STEP_MS);

        if (dialogOpenNow()) {
            listener.log("  ✔ 重试后已检测到「" + npcName + "」对话界面");
            return;
        }

        anomalyCount++;
        String shot = snapshot("anomaly_dialog_missing");
        abort("第 " + anomalyCount + " 次异常：未出现「" + npcName + "」对话界面",
                "未能弹出「" + npcName + "」对话界面，流程已停止。\n\n"
                        + "常见原因：\n"
                        + "  ① 背包里缺少任务道具（如神机石-2级 / 剑玲珑-2级）\n"
                        + "  ② 游戏弹出了验证提示，需要人工点击\n"
                        + "  ③ 有弹窗/其他窗口遮挡了游戏画面\n\n"
                        + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                        + "请处理完毕后重新点击「军团任务」。");
    }

    /**
     * 结束后打开军团界面截图，方便人工核对进度是否 5/5 —— 并与内部计数对账。
     *
     * <p><b>为什么不能只读面板数字</b>：军团面板这行字在实机上 OCR 读不出
     * （115920 现场帧整窗 OCR 为空，见 0930 日志同款记录），所以对账改用
     * {@link #questAccepted} —— 每次「点中确认框且没收到拒绝回执」才 +1，
     * 是一条不依赖 OCR 的内部事实。
     *
     * <p>2026-10-01 事故：旧实现 5 轮全报成功、面板却只有 4/5，因为第 1 轮被
     * 游戏拒了（「玩家状态错误,不能执行该动作」）而验收没看出来。现在计数不足
     * 目标轮次会<b>明确告警并提示补做</b>，不再把虚的算成实的。
     */
    private void verifyProgress() {
        try {
            listener.log("—— 收尾：打开军团界面截图，供核对进度 ——");
            controller.sendKey(VK_O);
            sleep(STEP_MS);
            String p = snapshot("legion_panel");
            if (p != null) {
                listener.log("军团界面截图：" + p + "　（可查看「军团任务 X/5」）");
            }
            controller.sendKey(VK_O);
            sleep(SHORT_MS);
        } catch (Throwable t) {
            listener.log("收尾截图失败（不影响任务结果）：" + t.getMessage());
        }

        // 与内部计数对账（不依赖 OCR 读面板数字）
        if (questAccepted < rounds) {
            listener.log("⚠ 对账不符：本次实际被游戏接受 " + questAccepted + " 次，目标 "
                    + rounds + " 次，少 " + (rounds - questAccepted) + " 次"
                    + "（多半是某一轮被游戏拒绝执行 —— 见日志里的「系统回执」行与 opt_r*_rejected 截图）");
            listener.log("   建议：再点一次「军团任务」按钮补做（做满时会自动认出「明日请早」优雅收工）");
        } else {
            listener.log("✔ 对账通过：本次被游戏接受 " + questAccepted + " 次 = 目标 " + rounds + " 次");
        }
    }

    /** 抛异常前统一记录现场并提醒。 */
    private void abort(String reason, String humanMessage) {
        listener.log("✘ 异常中止：" + reason);
        listener.alert("军团任务 · 需要人工处理", humanMessage);
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

    // ==================== 画面检测 ====================

    /** 全部文字行里有没有命中这组关键词（归一化后包含，或相似度 ≥0.72）；只在 zone 内找。 */
    private static ScreenText.TextLine findInLines(java.util.List<ScreenText.TextLine> lines,
                                                   String[] keywords, int[] zone) {
        for (ScreenText.TextLine l : lines) {
            if (l.cx < zone[0] || l.cx > zone[1] || l.cy < zone[2] || l.cy > zone[3]) {
                continue;
            }
            String n = XiaolianBank.normalize(l.text);
            if (n.isEmpty()) {
                continue;
            }
            for (String k : keywords) {
                String want = XiaolianBank.normalize(k);
                if (want.isEmpty()) {
                    continue;
                }
                if (n.contains(want) || XiaolianBank.similarity(n, want) >= 0.72) {
                    return l;
                }
            }
        }
        return null;
    }

    /** 军团面板（按 O 打开的那个）是否还开着 —— 找「回到军团」按钮，纯像素判据。 */
    private boolean legionPanelOpen(BufferedImage img) {
        try {
            return SalaryTask.findBackToLegionButton(img) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 「完成任务」结算窗口在不在 —— <b>OCR 认面板文字</b>（{@link #questPanelTextPresent}）。
     *
     * <p>0926 事故：旧的纯橙色判据（橙像素 ≥300 且 y 跨度 ≥15）在大厅场景里恒真 ——
     * 角色脚下的橙木地板/木桶在 {@link #PATCH_SETTLE_OK} 里也有 642 个橙像素（三次
     * 运行读数一像素不差），于是「结算窗口在」→ 12 轮全点在空地 (521,513) 上。
     * 与关广告判据同一条铁律：<b>色块会被环境同色骗，必须 OCR 认字</b>。
     * 橙色团块只保留一个职责：给「确定」按钮定位（{@link #locateSettleOk}）。
     */
    private boolean settleWindowPresent(BufferedImage img) {
        return questPanelTextPresent(img);
    }

    /**
     * 任务面板文字判据：OCR 在 {@link #ZONE_QUEST_PANEL} 里认出
     * {@link #KW_QUEST_PANEL}（「任务名称 / 任务内容 / 你完成的很好 / 嘉奖」）。
     *
     * <p>OCR 异常按「不在」处理 —— 面板真开着时下一轮扫描还有机会；宁可不点，
     * 也不能学旧色块判据那样对空场景连点 12 轮。
     */
    /**
     * 今天的军团任务是否已经做满 —— <b>只认提示句文字 + 两帧一致</b>（铁律：绝不信单帧）。
     *
     * <p>两个信号任一成立即判满：
     * <ol>
     *   <li>画面上有「明日请早」（做满 5 次提示对话框，几乎专属词）；</li>
     *   <li>「做够」与「五次」同帧出现（提示句被 OCR 读碎时的兜底组合）。</li>
     * </ol>
     * <p><b>0927 01:44 教训（推翻 01:37 曾有的「基础菜单」第三判据）</b>：
     * 「基础菜单无任务选项」<b>不是</b>做满信号 —— 首层菜单（对话/任务、提升军团等级、
     * 我要回去、军团长期不上线、取消）是<b>每一轮都会出现的正常菜单</b>，把它当
     * 「已满」会让任务每轮见到菜单就秒退（01:37~01:44 三连翻车）。做满只能在点了
     * 「对话/任务」之后、由「明日请早」提示框证实。任何一帧不满足都按「不是已满」
     * 处理（宁可多跑一轮重试，也不能把能做的任务误判成做满）。
     */
    private boolean dailyFullDetected() {
        for (int i = 0; i < 2; i++) {
            BufferedImage f = controller.captureWindow();
            if (!fullHintInFrame(f)) {
                return false;
            }
            // 【用户 2026-10-01 新增反向门禁】认出「明日请早」提示还不够 ——
            // 必须同时确认右侧任务追踪器里「1级军团任务」条目已经消失，才算真做满。
            // 用户原话：追踪器里还挂着这个条目，就说明还有活，得继续 G 对话推进；
            // 条目没了，那才是「已做完五轮」的对话框，才算彻底完成。
            // 这条同时给「提示句被 OCR 断行误判」加了一道硬保险。
            if (trackerHasLegionTask(f, listener)) {
                listener.log("    [已满探测] 对话正文认出做满提示，但右侧追踪器仍有军团任务条目"
                        + " → 按用户口径「还没彻底做完」，本次不判做满");
                return false;
            }
            if (i == 0) {
                sleep(FULL_RECHECK_MS);
            }
        }
        return true;
    }

    /**
     * 单帧：有没有「做够了五次任务，明日请早」这类提示句。
     *
     * <p><b>0928 用户要求：不许满屏扫，只在给定的框里找。</b>「明日请早」提示就是
     * 军团掌簿的对话正文（{@code legion_shots/023956_quest_full_today.png}：
     * 「你今天已经做够了5次任务，明日请早。我们军团能发展到今天…」实测在
     * 对话框正文区 x≈390..690 / y≈258..300），所以只扫对话正文区 {@link #ZONE_DIALOG}。
     * 这样右侧任务追踪器、聊天区、场景横幅里的同名字样都进不来。
     */
    private boolean fullHintInFrame(BufferedImage img) {
        return fullHintInZone(img, listener);
    }

    /**
     * 单帧（包级可见，供回归探针直接调用）：<b>只看对话正文区 {@link #ZONE_DIALOG} 内</b>的
     * OCR 行找「做够五次 / 明日请早」。返回 true = 今日已做满（收工唯一信号）。
     *
     * <p>地域圈定是 0928 用户要求（「不许满屏扫，只在给定的框里找」）：右侧任务追踪器、
     * 聊天区、场景横幅里的同名字样都进不来。
     */
    static boolean fullHintInZone(BufferedImage img, Listener ls) {
        try {
            java.util.ArrayList<ScreenText.TextLine> inZone = new java.util.ArrayList<>();
            for (ScreenText.TextLine ln : ScreenText.lines(img)) {
                if (ln.cx < ZONE_DIALOG[0] || ln.cx > ZONE_DIALOG[1]
                        || ln.cy < ZONE_DIALOG[2] || ln.cy > ZONE_DIALOG[3]) {
                    continue;   // 只看对话正文区的行（用户 0928：不做满屏扫）
                }
                if (isFullHintLine(ln.text)) {
                    if (ls != null) {
                        ls.log("    [已满探测] 对话正文区认出「" + ln.text + "」→ 今日已做满提示在");
                    }
                    return true;
                }
                inZone.add(ln);
            }
            // 【2026-09-30 加固】OCR 把提示句断成两行时逐行判会漏（实拍
            // 023956：正文是「…做够了5次任务，明日请早。我们军团能发展到今天」+
            // 「，承蒙国中一些忠义之士的扶持。」两行；若「明日请早」被断在行尾更早的
            // 版本上就会整句读碎）。把框内所有行按 y 再按 x 拼成一段正文再找一次 ——
            // 这个框只含标题 + 正文（选项在 y≥358，框下沿 340），拼不进无关内容。
            inZone.sort((a, b) -> a.cy != b.cy ? Integer.compare(a.cy, b.cy)
                    : Integer.compare(a.cx, b.cx));
            StringBuilder sb = new StringBuilder();
            for (ScreenText.TextLine ln : inZone) {
                sb.append(ln.text);
            }
            String joined = sb.toString();
            if (isFullHintLine(joined)) {
                if (ls != null) {
                    ls.log("    [已满探测] 正文区跨行拼接后命中 → 今日已做满提示在（「" + joined + "」）");
                }
                return true;
            }
        } catch (Throwable t) {
            if (ls != null) {
                ls.log("    [已满探测] OCR 异常（" + t + "）→ 按不是已满处理");
            }
        }
        return false;
    }

    /**
     * 「今日已做满」提示句判据（2026-09-30 加固）。
     *
     * <p><b>实拍漏判点</b>：{@code legion_shots/023956_quest_full_today.png} 的正文写的是
     * 「你今天已经做够<b>了5次</b>任务，明日请早」—— 是<b>阿拉伯数字 5</b>，而旧判据
     * 只认 {@link #KW_FULL_FIVE}「五次」，所以「做够 + 次数」这条组合在真机上
     * <b>永远不成立</b>，只剩「明日请早」一条腿走路（一旦被 OCR 断行就彻底漏判）。
     *
     * <p>两条信号任一成立即判满：①「明日请早」（几乎专属词）；
     * ②「做够」+ 次数（「五次」或「5次」）同段出现 —— 次数必须与「做够」同在，
     * 防止第三层确认框正文「这个军团任务每天只能<b>做5次</b>」被误判成做满。
     */
    private static boolean isFullHintLine(String text) {
        String n = XiaolianBank.normalize(text);
        if (n == null || n.isEmpty()) {
            return false;
        }
        if (n.contains(KW_FULL_DAY)) {
            return true;
        }
        boolean enough = n.contains(KW_FULL_ENOUGH);
        boolean five = n.contains(KW_FULL_FIVE) || n.contains("5次");
        return enough && five;
    }

    /** 单帧：对话/面板上有没有「军团任务已完成（待还）」的说明（用户 0928 语义）。 */
    private boolean questDoneHintInFrame(BufferedImage img) {
        return findFirstInZones(img, KW_QUEST_DONE_HINT, ZONE_QUEST_PANEL, ZONE_DIALOG) != null;
    }

    /**
     * 单帧：右侧任务追踪器里<b>还有没有</b>「1级军团任务」那一条（用户 2026-10-01 语义）。
     *
     * <p>只扫用户给定的 {@link #ZONE_QUEST_TRACKER}，绝不满屏扫（0928 铁律）。
     * 返回 {@code true} ＝ 追踪器里还挂着军团任务 ⇒ <b>还没彻底做完</b>，应该继续推进。
     */
    static boolean trackerHasLegionTask(BufferedImage img, Listener ls) {
        if (img == null) {
            return false;
        }
        try {
            ScreenText.TextLine hit = findInLines(ScreenText.lines(img),
                    KW_TRACKER_LEGION, ZONE_QUEST_TRACKER);
            if (hit != null) {
                if (ls != null) {
                    ls.log("    [追踪器] 右侧任务追踪器仍有「" + hit.text + "」@ ("
                            + hit.cx + "," + hit.cy + ") → 军团任务还没彻底做完");
                }
                return true;
            }
        } catch (Throwable t) {
            if (ls != null) {
                ls.log("    [追踪器] OCR 异常（" + t + "）→ 按「追踪器已干净」处理");
            }
        }
        return false;
    }

    /**
     * 两帧确认：右侧任务追踪器里是否还有「1级军团任务」。
     *
     * <p>铁律：绝不信单帧 —— 连续两帧（间隔 {@link #TRACKER_RECHECK_MS}）都认到才算「还有」；
     * 任何一帧没认到就按「已干净」处理（宁可多补跑一轮，也不把没做完的当做完）。
     */
    private boolean trackerStillHasLegionTask() {
        boolean first = false;
        for (int i = 0; i < 2; i++) {
            BufferedImage f = controller.captureWindow();
            boolean hit = trackerHasLegionTask(f, listener);
            if (i == 0) {
                first = hit;
                if (!hit) {
                    return false;       // 第一帧就干净 → 直接判「已干净」
                }
                sleep(TRACKER_RECHECK_MS);
            } else if (!hit) {
                listener.log("    [追踪器] 第二帧没认到条目 → 两帧不一致，按「已干净」处理");
                return false;
            }
        }
        return first;
    }

    /**
     * 在若干区域里找关键词，返回第一条命中的行（{@code null} = 都没命中）。
     *
     * <p>只做「区域内的行」判定，绝不满屏扫（用户 0928 要求）。多区域按顺序试。
     */
    private ScreenText.TextLine findFirstInZones(BufferedImage img, String[] keywords, int[]... zones) {
        if (img == null) {
            return null;
        }
        try {
            java.util.List<ScreenText.TextLine> lines = ScreenText.lines(img);
            for (int[] z : zones) {
                ScreenText.TextLine hit = findInLines(lines, keywords, z);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Throwable t) {
            if (listener != null) {
                listener.log("    [完成说明] OCR 异常（" + t + "）→ 按没认出处理");
            }
        }
        return null;
    }

    /**
     * 单帧：首层基础菜单开着（基础项 ≥2 且框内无「军团任务」字样）。
     * <b>这是正常态信号 → 动作是点「对话/任务」</b>，不是「今日已做满」
     * （实例包装，供日常流程调用）。
     */
    private boolean menuBaseNoQuest(BufferedImage img) {
        return menuBaseNoQuestInZone(img, listener);
    }

    /**
     * 单帧（包级可见，供回归探针直接调用）：<b>只看菜单框 {@link #ZONE_MENU_BOX} 内</b>的
     * OCR 行 —— 基础项命中 ≥2 且框内无「军团任务」字样 → <b>首层基础菜单在</b>。
     *
     * <p>语义（0927 01:44 定稿）：true = 首层菜单开着（正常态，动作是点「对话/任务」），
     * <b>不是</b>「今日已做满」；做满唯一信号是「明日请早」提示句（见
     * {@link #fullHintInFrame}）。地域圈定是 01:09 事故的教训：旧实现全屏扫
     * 「军团任务」，被右侧追踪器常驻的「1级军团任务 已完成」骗成「选项还在」。
     * 每次都把菜单框里读到什么打进日志（静默 false 是事故放大器）。
     */
    static boolean menuBaseNoQuestInZone(BufferedImage img, Listener ls) {
        try {
            int base = 0;
            int menuLines = 0;
            String seen = "";
            for (ScreenText.TextLine ln : ScreenText.lines(img)) {
                if (ln.cx < ZONE_MENU_BOX[0] || ln.cx > ZONE_MENU_BOX[1]
                        || ln.cy < ZONE_MENU_BOX[2] || ln.cy > ZONE_MENU_BOX[3]) {
                    continue;
                }
                String n = XiaolianBank.normalize(ln.text);
                if (n.isEmpty()) {
                    continue;
                }
                menuLines++;
                if (n.contains("军团任务")) {
                    if (ls != null) {
                        ls.log("    [菜单探测] 框内认出「" + ln.text + "」→ 任务列表/面板在，不是首层菜单");
                    }
                    return false;
                }
                for (String kw : KW_MENU_BASE) {
                    if (n.contains(XiaolianBank.normalize(kw))) {
                        base++;
                        seen = seen.isEmpty() ? kw : seen + "、" + kw;
                        break;
                    }
                }
            }
            if (ls != null) {
                if (base >= 2) {
                    ls.log("    [菜单探测] 框内基础项（" + seen + "）、无「军团任务」→ 首层菜单在（点「对话/任务」）");
                } else {
                    ls.log("    [菜单探测] 框内读到 " + menuLines + " 行、基础项命中 "
                            + base + " 个 → 非首层菜单");
                }
            }
            return base >= 2;
        } catch (Throwable t) {
            if (ls != null) {
                ls.log("    [菜单探测] OCR 异常（" + t + "）→ 按没有首层菜单处理");
            }
            return false;
        }
    }

    /**
     * 在首层菜单框 {@link #ZONE_MENU_BOX} 里找「对话/任务」那一行
     * （包级可见，供回归探针直接调用）。
     *
     * <p>返回该行点击坐标 {x, y}；没认出 / OCR 异常返回 {@code null}。
     * 命中行每次都打日志（静默 null 是事故放大器）。
     */
    static int[] findTalkOptionInZone(BufferedImage img, Listener ls) {
        try {
            for (ScreenText.TextLine t : ScreenText.lines(img)) {
                if (t.cx < ZONE_MENU_BOX[0] || t.cx > ZONE_MENU_BOX[1]
                        || t.cy < ZONE_MENU_BOX[2] || t.cy > ZONE_MENU_BOX[3]) {
                    continue;
                }
                String n = XiaolianBank.normalize(t.text);
                if (n.isEmpty()) {
                    continue;
                }
                for (String kw : KW_TALK_OPTION) {
                    if (n.contains(XiaolianBank.normalize(kw))) {
                        if (ls != null) {
                            ls.log("    [对话/任务] 认出「" + t.text + "」@ (" + t.cx + "," + t.cy
                                    + ")（命中关键词「" + kw + "」）");
                        }
                        return new int[]{t.cx, t.cy};
                    }
                }
            }
            return null;
        } catch (Throwable t2) {
            if (ls != null) {
                ls.log("    [对话/任务] OCR 异常（" + t2 + "）→ 没认出");
            }
            return null;
        }
    }

    /**
     * 做满收尾：把「明日请早」提示框 / 残留对话关掉，别挡后面的一键日常项。
     * 优先 OCR 点提示框上的「确定 / 关闭」；认不出就按 G（G 是对话开关）。
     */
    private void closeDialogAfterFull() {
        try {
            BufferedImage img = controller.captureWindow();
            int[] ok = ScreenText.find(img,
                    new String[]{"确定", "关闭", "知道了"}, ZONE_OPTION, 0);
            if (ok != null) {
                listener.log("  点提示框按钮关闭做满提示 @ (" + ok[0] + "," + ok[1] + ")");
                controller.clickWindowPoint(ok[0], ok[1]);
                sleep(STEP_MS);
                return;
            }
        } catch (Throwable ignore) {
            // OCR 找不到按钮就走 G 兜底
        }
        listener.log("  没认出提示框按钮 → 按 G 关闭残留对话");
        controller.sendKey(VK_G);
        sleep(STEP_MS);
    }

    // ==================== 名条判据：右上角确认「军团地图 / 大厅」（0927 用户定稿） ====================

    /**
     * 单帧：右上角名条里认出的地图名行。
     *
     * <p>判据与搜索区移植自抢线任务实测定标（2026-09-23，13 张样本全部分对）：
     * 军团地图 / 军团大厅没有小地图面板，名条顶在工具栏正下方（y≈63）；城镇的名条
     * 在小地图下方（y≈164），落不进这个搜索区。
     *
     * @return 认出的那一行文字；{@code null} = 没认出 / OCR 异常。
     */
    private String legionMapWord(BufferedImage img) {
        if (img == null) {
            return null;
        }
        try {
            ScreenText.TextLine hit = findInLines(
                    ScreenText.lines(img), KW_LEGION_MAP, ZONE_MAP_NAME);
            if (hit != null && listener != null) {
                listener.log("    [名条判据] 认出「" + hit.text + "」@ (" + hit.cx + "," + hit.cy + ")");
            }
            return hit == null ? null : hit.text;
        } catch (Throwable t) {
            if (listener != null) {
                listener.log("    [名条判据] OCR 异常（" + t + "）→ 判不出");
            }
            return null;
        }
    }

    /**
     * 名条带裁剪 4x 单独 OCR：这一带读得到文字 = 名条顶在工具栏正下方 = 军团类地图。
     *
     * <p>「大厅」二字整窗 2x 读不出来，靠这条更高倍的通道兜底（抢线任务 0923 实测，
     * 4x 下能读出名条上的坐标「（7，2）」）。代价很小：裁剪图只有 204×52。
     *
     * @return {@code true} 读到文字 / {@code false} 一行都没有 / {@code null} 判不出来
     */
    private Boolean mapNameStripPresent(BufferedImage img) {
        if (img == null || !OcrLite.available()) {
            return null;
        }
        try {
            double kx = img.getWidth() / (double) BASE_W;
            double ky = img.getHeight() / (double) BASE_H;
            int x0 = (int) Math.round(BOX_MAP_STRIP[0] * kx);
            int y0 = (int) Math.round(BOX_MAP_STRIP[1] * ky);
            int x1 = Math.min(img.getWidth(), (int) Math.round(BOX_MAP_STRIP[2] * kx));
            int y1 = Math.min(img.getHeight(), (int) Math.round(BOX_MAP_STRIP[3] * ky));
            if (x1 - x0 < 16 || y1 - y0 < 12) {
                return null; // 窗口小得反常，别硬判
            }
            BufferedImage crop = img.getSubimage(x0, y0, x1 - x0, y1 - y0);

            File jobDir = OcrLite.prepareJobDir();
            File f = OcrLite.saveForOcr(crop, STRIP_SCALE,
                    new File(jobDir, "legion_strip.png"), OcrLite.MODE_ORIGINAL);
            if (f == null) {
                return null;
            }
            List<OcrLite.Result> res = OcrLite.recognize(Collections.singletonList(f), 30);
            for (OcrLite.Result one : res) {
                if (one.file == null
                        || !one.file.replace('\\', '/').endsWith("legion_strip.png")) {
                    continue; // recognize 按目录跑，过滤掉目录里的历史图
                }
                for (OcrLite.Line l : one.sorted()) {
                    if (!XiaolianBank.normalize(l.text).isEmpty()) {
                        if (listener != null) {
                            listener.log("    [名条判据] 名条带 4x 读到：「" + l.text + "」");
                        }
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            if (listener != null) {
                listener.log("    [名条判据] 名条带 OCR 异常（" + t + "）→ 判不出");
            }
            return null;
        }
    }

    /**
     * 在不在军团类地图。{@code true}=在（关键词命中或名条带读到文字）；
     * {@code false}=两条通道都 confident 地读不到（=不在）；
     * {@code null}=OCR 不可用，判不出来。
     */
    private Boolean inLegionMapFrame(BufferedImage img) {
        if (legionMapWord(img) != null) {
            return true;
        }
        return mapNameStripPresent(img);
    }

    /**
     * 传送后等名条真的变成军团地图 / 大厅（0927 用户定稿的验收方式）。
     *
     * <p>先等 {@link #MAP_SETTLE_MS} 让地图加载，然后轮询右上角名条：关键词命中或
     * 名条带读到文字都算到位。{@code retryWithBackToLegion} 时第 3 次探测还没到位就
     * 重做一遍「O → 点回到军团」（防第一次点击没吃进去）。超时 → 存现场截图并中止。
     */
    private void awaitLegionMap(String action, boolean retryWithBackToLegion) {
        listener.log("  等待名条确认（" + action + " → 右上角应显示「军团地图 / 大厅」）");
        sleep(MAP_SETTLE_MS);
        long deadline = System.currentTimeMillis() + MAP_WAIT_MAX_MS;
        int poll = 0;
        while (true) {
            checkStop();
            poll++;
            BufferedImage img = controller.captureWindow();
            String w = legionMapWord(img);
            if (w != null) {
                listener.log("  ✔ 第 " + poll + " 次探测：名条认出「" + w + "」→ " + action + "已到位");
                return;
            }
            Boolean strip = mapNameStripPresent(img);
            if (Boolean.TRUE.equals(strip)) {
                listener.log("  ✔ 第 " + poll + " 次探测：名条带读到文字（军团类地图独有形态）→ "
                        + action + "已到位");
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                String shot = snapshot("anomaly_map_confirm");
                abort("传送后未确认到军团地图（" + action + "）",
                        "点了「" + action + "」之后等了 " + (MAP_WAIT_MAX_MS / 1000)
                                + " 秒，右上角名条始终没认出「军团地图 / 大厅」。\n\n"
                                + "常见原因：\n"
                                + "  ① 传送没有生效（角色卡住 / 还在加载）\n"
                                + "  ② 有弹窗挡住了画面\n"
                                + "  ③ 网络卡顿\n\n"
                                + (shot != null ? "现场截图：" + shot + "\n\n" : "")
                                + "手动把角色送回军团大厅后，再点一次「军团任务」。");
            }
            if (poll == 3 && retryWithBackToLegion) {
                listener.log("  3 次探测没到位 → 重做一遍「O → 点回到军团」");
                clickBackToLegion();
            }
            sleep(700);
        }
    }

    private boolean questPanelTextPresent(BufferedImage img) {
        if (img == null) {
            return false;
        }
        try {
            ScreenText.TextLine hit = findInLines(
                    ScreenText.lines(img), KW_QUEST_PANEL, ZONE_QUEST_PANEL);
            if (listener != null) {
                listener.log("    [面板判据] " + (hit != null
                        ? "OCR 认出「" + hit.text + "」@ (" + hit.cx + "," + hit.cy + ") → 任务面板在"
                        : "没认出面板文字 → 不在"));
            }
            return hit != null;
        } catch (Throwable t) {
            if (listener != null) {
                listener.log("    [面板判据] OCR 异常（" + t + "）→ 按不在处理");
            }
            return false;
        }
    }

    /**
     * 系统提示区里是不是出现了「游戏拒绝了这个动作」的回执
     * （{@link #KW_ACTION_REJECTED}）—— 认到说明这一手没生效，本轮不能算完成。
     *
     * <p>只在 {@link #ZONE_SYS_MSG} 里找，绝不满屏扫（0928 铁律）。
     * 判定与 {@link #actionRejectedZoom} 完全同源（同一份整窗 OCR 结果 + 同一区域），
     * 两条都留着只为日志能分别标出「整窗/区域」两个视角，结果必然一致。
     */
    private boolean actionRejectedInFrame(BufferedImage img) {
        if (img == null) {
            return false;
        }
        try {
            ScreenText.TextLine hit = findInLines(
                    ScreenText.lines(img), KW_ACTION_REJECTED, ZONE_SYS_MSG);
            if (hit != null && listener != null) {
                listener.log("    [系统回执·整窗] OCR 认出「" + hit.text + "」@ (" + hit.cx + ","
                        + hit.cy + ") → 这一手被游戏拒绝了");
            }
            return hit != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 裁剪通道 —— <b>不再对子图重新 OCR</b>，而是复用整窗 OCR 的结果按区域过滤。
     *
     * <p><b>为什么（2026-10-01 实测教训）</b>：最初这里对 {@code getSubimage} 出来的
     * 子图单独调 {@code ScreenText.lines} —— 但那份 OCR 结果是按
     * <b>{@code IdentityHashMap} 按对象引用</b>缓存的，{@code getSubimage} 每次都造新对象，
     * 缓存永不命中 ⇒ 同一帧每问一次就真跑一次 OCR ⇒ 暴露 <b>OCR 本身的抖动</b>：
     * 探针实测同一子图第一次读出「…玩家状态蜡误不能执行该动作」，紧接着复跑 3 次全读不出。
     * 判据「时有时无」＝ 形同虚设（这正是铁律 2「绝不信单帧」的反面教材）。
     *
     * <p>改法：整窗 OCR 已经包含那几行的读数（115753 整窗就在 (161,617) 命中了），
     * 没必要再 OCR 一遍。这里只把<b>同一份</b>整窗结果按 {@link #ZONE_SYS_MSG} 过滤 ——
     * 结果确定、不抖动、也不多耗一次 OCR。
     */
    private boolean actionRejectedZoom(BufferedImage img) {
        if (img == null) {
            return false;
        }
        try {
            java.util.List<ScreenText.TextLine> lines = ScreenText.lines(img);
            // 坐标口径：lines() 已把坐标归一化回基准画布，zone 直接用常量即可。
            ScreenText.TextLine hit = findInLines(lines, KW_ACTION_REJECTED, ZONE_SYS_MSG);
            if (hit != null && listener != null) {
                listener.log("    [系统回执·区域] 认出「" + hit.text + "」→ 这一手被游戏拒绝了");
            }
            return hit != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 两道判据任一命中 = 这一手被游戏拒绝。 */
    private boolean actionRejected(BufferedImage img) {
        return actionRejectedInFrame(img) || actionRejectedZoom(img);
    }

    /** 结算「确定」按钮的位置：橙色团块够大就用它的质心，否则退实测定标值。 */
    private int[] locateSettleOk(BufferedImage img) {
        int[] st = orangeStat(img, PATCH_SETTLE_OK);
        if (st[0] >= SETTLE_OK_MIN_PX) {
            return new int[]{st[3], st[4]};
        }
        return PT_SETTLE_OK.clone();
    }

    /**
     * 数采样区里的橙色像素，返回 {个数, x 跨度, y 跨度, 质心x, 质心y}（后两项是窗口坐标）。
     *
     * <p>判据 {@code r>140 && b<60 && (r-b)>110 && g<150} 是拿结算窗口的「确定」按钮
     * 实测出来的：按钮本体 (221,85,17)/(153,51,17) 满足，按钮上的白字 (238,136,85)
     * 因为 b=85 被排除。旁边那颗红色「评价·赏」印章 (206,0,0) 本身也满足这个判据 ——
     * 所以采样区 {@link #PATCH_SETTLE_OK} 特意只框住按钮、不含印章（印章在 x≥570）。
     */
    private int[] orangeStat(BufferedImage img, int[] patch) {
        if (img == null) {
            return new int[]{0, 0, 0, 0, 0};
        }
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x = (int) Math.round(patch[0] * kx);
        int y = (int) Math.round(patch[1] * ky);
        int w = Math.max(1, (int) Math.round(patch[2] * kx));
        int h = Math.max(1, (int) Math.round(patch[3] * ky));
        long sx = 0;
        long sy = 0;
        int n = 0;
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        int minY = Integer.MAX_VALUE;
        int maxY = -1;
        for (int j = y; j < y + h && j < img.getHeight(); j++) {
            if (j < 0) {
                continue;
            }
            for (int i = x; i < x + w && i < img.getWidth(); i++) {
                if (i < 0) {
                    continue;
                }
                int p = img.getRGB(i, j);
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                if (r > 140 && b < 60 && (r - b) > 110 && g < 150) {
                    n++;
                    sx += i;
                    sy += j;
                    if (i < minX) {
                        minX = i;
                    }
                    if (i > maxX) {
                        maxX = i;
                    }
                    if (j < minY) {
                        minY = j;
                    }
                    if (j > maxY) {
                        maxY = j;
                    }
                }
            }
        }
        if (n == 0) {
            return new int[]{0, 0, 0, 0, 0};
        }
        return new int[]{n, maxX - minX + 1, maxY - minY + 1,
                (int) Math.round(sx / (double) n / kx),
                (int) Math.round(sy / (double) n / ky)};
    }

    /**
     * 「热点活动」弹窗是否还在 = 色块偏红 <b>且</b> OCR 认得出广告标题。
     *
     * <p>纯色块判据会被红色景物骗过（0926 现场 R-G=+95、画面根本没有弹窗，任务被白白
     * 中止），所以色块命中后还要 OCR 在标题区认到「热点活动/游戏活动展示」字样才算数。
     * OCR 异常时按「弹窗在」处理 —— 宁可多关一次，不放走真弹窗挡住后续点击。
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
        try {
            boolean title = findInLines(ScreenText.lines(controller.captureWindow()),
                    KW_AD_TITLE, ZONE_AD_TITLE) != null;
            if (!title) {
                listener.log("    [检测] 色块偏红但 OCR 没认出广告标题 —— 多半是红色景物（秋叶/灯笼）误报，按「无弹窗」处理");
            }
            return title;
        } catch (Throwable t) {
            listener.log("    [检测] 广告标题 OCR 异常（按「弹窗在」处理，走原关闭流程）：" + t);
            return true;
        }
    }

    /**
     * NPC 对话是否已经弹出 —— 两路并进。
     *
     * <p>① 采样区高亮蓝条：菜单屏有、<b>长正文屏没有</b>；
     * ② 整窗 OCR 认得出对话标题（{@link #KW_DLG_TITLE}）。
     * 只看 ① 会把「正文开着」误判成「对话没弹出」并中止任务（实证：
     * {@code legion_shots/211412_anomaly_dialog_missing.png} —— 画面里正文好好开着，
     * 日志却报「未出现对话界面」），所以补上 ②。
     */
    private boolean dialogOpenNow() {
        if (dialogOptionHighlighted()) {
            return true;
        }
        try {
            return ScreenText.find(controller.captureWindow(), KW_DLG_TITLE, ZONE_DIALOG, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** NPC 对话是否已经弹出（对话列表首项的高亮蓝条是否存在）。 */
    private boolean dialogOptionHighlighted() {
        double[] m = patchMean(controller.captureWindow(), PATCH_DLG_OPTION);
        if (m == null) {
            return false;
        }
        double br = m[2] - m[0];
        listener.log(String.format("    [检测] 对话区 RGB=(%.0f,%.0f,%.0f)　B-R=%+.0f（>%.0f 表示对话已弹出）",
                m[0], m[1], m[2], br, TH_DLG_BR));
        return br > TH_DLG_BR;
    }

    /** 计算图片中指定采样区的平均 RGB。采样区坐标按图片实际尺寸等比缩放。 */
    private double[] patchMean(BufferedImage img, int[] patch) {
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

    /** 保存一张窗口截图到 legion_shots/，返回文件路径（失败返回 null）。 */
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
