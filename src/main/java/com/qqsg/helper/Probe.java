package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 通用「实机探测」工具 —— 走与正式任务完全相同的通道（{@link GameWindowController} 的
 * Robot 按键 / 点击 / 截图），用来标定新流程的坐标，避免「Python 侧能跑、Java 侧不灵」的偏差。
 *
 * <h3>用法</h3>
 * <pre>
 * java -cp helper-....jar com.qqsg.helper.Probe &lt;PID&gt; &lt;截图目录&gt; "命令1; 命令2; ..."
 * </pre>
 *
 * <h3>命令</h3>
 * <ul>
 *   <li>{@code key 0x47}   发送按键（十六进制或十进制虚拟键码），会先抢焦点</li>
 *   <li>{@code keyf 0x47}  发送按键，不抢焦点</li>
 *   <li>{@code click 570 400} 在窗口内坐标点击</li>
 *   <li>{@code shot name}  截图存到截图目录</li>
 *   <li>{@code sleep 1200} 等待毫秒</li>
 *   <li>{@code ui}         打印界面检测结果（导航面板 / 对话高亮条）</li>
 *   <li>{@code crop x y w h name} 存一张放大 4 倍的局部图，便于量坐标</li>
 * </ul>
 *
 * <h3>孝廉（推举孝廉）相关命令</h3>
 * <ul>
 *   <li>{@code xlnav} 只跑导航：回城 → 寻路(20,16) → ↑ 换图 → 寻路(11,8)</li>
 *   <li>{@code xlrun [dry]} 跑完整孝廉流程（dry = 只识别不点选项）</li>
 *   <li>{@code xlquiz [dry]} 只跑答题循环（题目面板已开着时用）</li>
 *   <li>{@code xlboxes} 实测寻路面板两个坐标框 + 「移动」按钮位置（不点）</li>
 *   <li>{@code xlcoord} OCR 右上角地图坐标条</li>
 *   <li>{@code xlraw &lt;png&gt;} 对单张图跑 OCR 并打印全部原始行</li>
 *   <li>{@code xlbuy} 只检测「快捷购买」弹窗并点「取消」（验证答题失败弹窗识别用）</li>
 * </ul>
 *
 * <h3>运送物资相关命令</h3>
 * <ul>
 *   <li>{@code ysnav} 运送物资：只跑导航（路线与孝廉完全相同）</li>
 *   <li>{@code ysrun} 运送物资：跑完整流程（接任务 → 再对话一轮 → 回车两下 → 点掉收尾按钮）</li>
 *   <li>{@code ysenter} 运送物资：只跑收尾节奏「回车两下 + 点掉『好的，我马上去』」（跳过导航/接任务）</li>
 *   <li>{@code ysclick} 运送物资：只找并点掉收尾按钮、<b>不按回车</b>（校准按钮关键词/坐标用）</li>
 *   <li>{@code ysfinish} 运送物资：只跑「收尾那一轮对话」（再 G → 点运送物资 → 回车两下 → 点按钮）</li>
 * </ul>
 *
 * <h3>后台操作验证命令</h3>
 * <ul>
 *   <li>{@code bg} 后台能力自检：PrintWindow 截图 vs Robot 截图、坐标换算、captureWindow 分支</li>
 *   <li>{@code bgclick x y} 后台模式点一下</li>
 *   <li>{@code bgtest x y} 后台点击 + 前后像素差</li>
 *   <li>{@code bgtest_ctl} 对照组：不点击，只测「画面自然变化」的基线</li>
 *   <li>{@code bgprobe x y} 依次试 PostMessage/SendMessage/激活后/子窗等手法</li>
 *   <li>{@code bgopen} 【决定性】OCR 定位寻路按钮 → 后台点击 → bigPanelOpen 判定是否打开</li>
 *   <li>{@code bgopen2} 只读当前 bigPanelOpen 与占比分数</li>
 *   <li>{@code bgclose} 后台点面板 X 关闭</li>
 *   <li>{@code bgchild} 列出游戏窗口的所有子窗口</li>
 * </ul>
 *
 * <p>示例：{@code Probe 2464 probe_shots "key 0x47; sleep 2000; shot g1; ui"}
 */
public class Probe {

    private static GameWindowController c;
    private static File dir;

    public static void main(String[] args) throws Exception {
        int pid = args.length >= 1 ? Integer.parseInt(args[0]) : -1;
        String outDir = args.length >= 2 ? args[1] : "probe_shots";
        String script = args.length >= 3 ? args[2] : "shot start";

        if (pid <= 0) {
            for (WindowInfo w : WindowUtils.getAllWindowsWithIds()) {
                if (w.getTitle() != null && w.getTitle().contains("QQ三国")) {
                    pid = w.getPid();
                    break;
                }
            }
        }
        if (pid <= 0) {
            System.out.println("✗ 没有找到 QQ三国 窗口");
            return;
        }

        dir = new File(outDir);
        dir.mkdirs();
        c = new GameWindowController("probe(pid=" + pid + ")", pid);

        Rectangle r = c.getWindowRect();
        System.out.println("PID=" + pid + "  rect=" + (r == null ? "null"
                : r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")"));
        if (r == null && needsWindow(script)) {
            System.out.println("✗ 拿不到窗口矩形，后面的命令需要窗口，先退出");
            return;
        }
        if (r == null) {
            System.out.println("（没有游戏窗口，但本次命令都是离线命令，继续）");
        }

        for (String raw : script.split(";")) {
            String cmd = raw.trim();
            if (cmd.isEmpty()) {
                continue;
            }
            System.out.println("\n>>> " + cmd);
            run(cmd);
        }
        System.out.println("\nDONE");
    }

    private static void run(String cmd) throws Exception {
        String[] a = cmd.split("\\s+");
        switch (a[0]) {
            case "key":
                c.sendKey(Integer.decode(a[1]));
                break;
            case "keyf":
                c.sendKeyNoFocus(Integer.decode(a[1]));
                break;
            case "click":
                c.clickWindowPoint(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                break;
            case "sleep":
                Thread.sleep(Long.parseLong(a[1]));
                break;
            case "shot":
                dump(a[1]);
                break;
            case "focus":
                c.focusWindow();
                Thread.sleep(500);
                System.out.println("   已置前，游戏窗口在前台：" + c.isForegroundGameWindow());
                break;
            case "bg": {
                // bg —— 后台能力自检：截图(PrintWindow) + 坐标换算 + 点击(PostMessage)
                System.out.println("   当前 runInBackground=" + c.isRunInBackground());
                c.setRunInBackground(true);
                System.out.println("   已切后台模式");

                // 1) 后台截图 vs Robot 截图 对比
                BufferedImage bgImg = c.captureWindowBackground();
                BufferedImage rbImg = c.captureWindowRobots();
                System.out.println("   PrintWindow 截图 = "
                        + (bgImg == null ? "null" : bgImg.getWidth() + "x" + bgImg.getHeight()));
                System.out.println("   Robot 截图       = "
                        + (rbImg == null ? "null" : rbImg.getWidth() + "x" + rbImg.getHeight()));
                if (bgImg != null) {
                    System.out.println("   PrintWindow 画面非空白 = " + !isBlank(bgImg)
                            + "（颜色种类 " + colorCount(bgImg) + "）");
                    saveImg(bgImg, "bg_printwindow");
                }
                if (rbImg != null) {
                    System.out.println("   Robot 画面非空白 = " + !isBlank(rbImg)
                            + "（颜色种类 " + colorCount(rbImg) + "）");
                }

                // 2) 坐标换算自检：窗口内基准坐标 -> 客户区坐标
                int[][] probes = {{515, 400}, {515, 14}, {650, 458}};
                for (int[] p : probes) {
                    int[] cl = c.windowToClient(p[0], p[1]);
                    System.out.println("   windowToClient(" + p[0] + "," + p[1] + ") = "
                            + (cl == null ? "null" : "(" + cl[0] + "," + cl[1] + ")"));
                }

                // 3) captureWindow() 在后台模式下是否走 PrintWindow
                BufferedImage viaCapture = c.captureWindow();
                System.out.println("   captureWindow()（后台模式）= "
                        + (viaCapture == null ? "null"
                        : viaCapture.getWidth() + "x" + viaCapture.getHeight()
                        + " 非空白=" + !isBlank(viaCapture)));

                c.setRunInBackground(false);
                System.out.println("   已还原前台模式");
                break;
            }
            case "bgclick": {
                // bgclick <x> <y> —— 后台模式点一下（验证 PostMessage 点击）
                c.setRunInBackground(true);
                boolean ok = c.clickWindowPointBackground(
                        Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                System.out.println("   后台点击结果=" + ok);
                c.setRunInBackground(false);
                break;
            }
            case "bgprobe": {
                // bgprobe <x> <y> —— 依次试各种「后台发鼠标」手法，看哪种游戏真的认。
                //   关键：游戏画面本身在动（火焰/人物/特效），所以要有一个
                //   「什么都不点」的对照组，否则分不清「点击生效」和「画面本来就在动」。
                int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]);
                System.out.println("   目标窗口内坐标 (" + x + "," + y + ")");
                c.setRunInBackground(true);
                System.out.println("   ---- 对照组：不点任何东西，只等同样时长 ----");
                testBgStrategy("对照组(不点击)", x, y, 0);
                testBgStrategy("PostMessage(顶层)", x, y, 1);
                testBgStrategy("SendMessage(顶层)", x, y, 2);
                testBgStrategy("PostMessage(激活后)", x, y, 3);
                testBgStrategy("SendMessage(激活后)", x, y, 4);
                c.setRunInBackground(false);
                break;
            }
            case "bgchild": {
                // bgchild —— 列出指定 PID 的所有子窗口，找 D3D/输入窗口
                c.enumerateChildWindows();
                break;
            }
            case "bgopen": {
                // bgopen —— 【后台点击决定性验证】
                //   1) 用 OCR 定位右上角「寻路」按钮（真实坐标，避免猜）
                //   2) 后台点击它
                //   3) 用 bigPanelOpen() 判断寻路面板到底开没开
                //   面板开 = 后台点击 100% 生效（布尔判定，不受画面动画干扰）
                c.setRunInBackground(true);
                XiaolianTask t = newXiaolianTask(true, true);

                System.out.println("   1) OCR 定位「寻路」按钮…");
                int[] btn = t.findXunluButton();
                if (btn == null) {
                    System.out.println("   ✗ 没定位到「寻路」按钮，先确认游戏画面正常");
                    c.setRunInBackground(false);
                    break;
                }
                System.out.println("      按钮窗口坐标 = (" + btn[0] + "," + btn[1] + ")");

                boolean openBefore = t.bigPanelOpen();
                System.out.println("   2) 点击前：寻路面板已开 = " + openBefore);

                System.out.println("   3) 后台点击…");
                c.clickWindowPointBackground(btn[0], btn[1]);
                Thread.sleep(1500);

                boolean openAfter = t.bigPanelOpen();
                System.out.println("   4) 点击后：寻路面板已开 = " + openAfter);
                System.out.println("   >>> " + (openAfter && !openBefore
                        ? "✔✔ 后台点击【生效】，面板被打开了"
                        : (openAfter ? "面板本来就开着，结果不可判" : "✗ 后台点击没打开面板")));
                saveImg(c.captureWindowBackground(), "bgopen_result");
                c.setRunInBackground(false);
                break;
            }
            case "bgclose": {
                // bgclose —— 后台点面板右上角 X 关掉寻路面板（顺便测后台点击）
                c.setRunInBackground(true);
                XiaolianTask t = newXiaolianTask(true, true);
                int[] x = t.findRedCloseButton();
                if (x == null) {
                    System.out.println("   没定位到 X");
                } else {
                    System.out.println("   X = (" + x[0] + "," + x[1] + ")，后台点击");
                    c.clickWindowPointBackground(x[0], x[1]);
                    Thread.sleep(1200);
                    System.out.println("   点击后 panelFraction = " + t.panelFractionProbe()
                            + "  bigPanelOpen=" + t.bigPanelOpen());
                }
                c.setRunInBackground(false);
                break;
            }
            case "bgopen2": {
                // bgopen2 —— 只读当前状态：寻路面板开着吗？顺带报分数
                XiaolianTask t = newXiaolianTask(true, true);
                System.out.println("   bigPanelOpen = " + t.bigPanelOpen());
                System.out.println("   panelFraction = " + t.panelFractionProbe());
                saveImg(c.captureWindow(), "bgopen2_now");
                break;
            }
            case "bgtest_ctl": {
                // bgtest_ctl —— 对照组：不点任何东西，只截图→等 1.8s→截图→算差异。
                //   用来建立「画面自然变化」的基线，排除游戏动画的干扰。
                c.setRunInBackground(true);
                BufferedImage b1 = c.captureWindowBackground();
                Thread.sleep(1800);
                BufferedImage a1 = c.captureWindowBackground();
                if (b1 != null && a1 != null
                        && b1.getWidth() == a1.getWidth() && b1.getHeight() == a1.getHeight()) {
                    System.out.println("   [对照] 前后平均像素差 = "
                            + String.format("%.2f", meanAbsDiff(b1, a1)));
                } else {
                    System.out.println("   [对照] 截图失败");
                }
                c.setRunInBackground(false);
                break;
            }
            case "bgtest": {
                // bgtest <x> <y> —— 后台点击「是否真的生效」闭环验证
                int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]);
                c.setRunInBackground(true);
                BufferedImage before = c.captureWindow();
                System.out.println("   点击前后台截图颜色数=" + (before == null ? -1 : colorCount(before)));
                boolean ok = c.clickWindowPointBackground(x, y);
                System.out.println("   后台点击(" + x + "," + y + ") 结果=" + ok);
                Thread.sleep(1800);
                BufferedImage after = c.captureWindow();
                if (before != null && after != null
                        && before.getWidth() == after.getWidth()
                        && before.getHeight() == after.getHeight()) {
                    double d = meanAbsDiff(before, after);
                    System.out.println("   >>> 前后平均像素差 = " + String.format("%.2f", d)
                            + "  " + (d > 3.0 ? "✔ 画面变了，点击生效" : "✗ 画面几乎没变"));
                    saveImg(after, "bgtest_after");
                }
                c.setRunInBackground(false);
                break;
            }
            case "movewin":
                // movewin <x> <y> —— 把游戏窗口挪到屏幕指定位置（躲开挡在前面的窗口）
                c.moveWindowTo(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                break;
            case "fgwin":
                System.out.println("   游戏窗口在前台：" + c.isForegroundGameWindow());
                break;
            case "input": {
                // input <x> <y> <数字> —— 点输入框、清空、逐位敲数字（与正式流程同一套动作）
                int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]);
                String text = a[3];
                c.clickWindowPoint(x, y);
                Thread.sleep(500);
                c.sendKeyNoFocus(WindowUtils.VK_END);
                Thread.sleep(120);
                c.sendKeyNoFocus(WindowUtils.VK_END);
                Thread.sleep(120);
                for (int i = 0; i < 10; i++) {
                    c.sendKeyNoFocus(WindowUtils.VK_BACK);
                    Thread.sleep(55);
                }
                Thread.sleep(200);
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    if (ch >= '0' && ch <= '9') {
                        c.sendKeyNoFocus(0x30 + (ch - '0'));
                        Thread.sleep(180);
                    }
                }
                Thread.sleep(300);
                System.out.println("   已输入 " + text);
                break;
            }
            case "ui": {
                BawangTask.Ui ui = BawangTask.scanImage(c.captureWindow());
                System.out.println("    navOpen=" + ui.navOpen + "  dialogOpen=" + ui.dialogOpen
                        + "  option1Y=" + ui.option1Y);
                break;
            }
            case "ui2": {
                SalaryTask.Ui ui = SalaryTask.scanImage(c.captureWindow());
                System.out.println("    对话带选项=" + ui.dialogOpen + "  首项y=" + ui.option1Y);
                break;
            }
            case "track": {
                SalaryTask st = new SalaryTask(c, new SalaryTask.Listener() {
                    public void log(String m) { System.out.println("      " + m); }
                    public void alert(String t, String m) { System.out.println("      [alert] " + t); }
                    public void finished(boolean ok, String s) { }
                });
                boolean has = st.trackerHasNpcTarget();
                boolean panel = st.bigPanelOpen();
                boolean menu = st.systemMenuOpen();
                System.out.println("    「任务追踪」有 NPC 名=" + has
                        + "  自动寻路面板=" + panel + "  系统菜单=" + menu);
                break;
            }
            case "team": {
                TeamTask tt = newTeamTask();
                BufferedImage img = c.captureWindow();
                int[] hit = tt.findIcon(img);
                System.out.println("    组队图标 = " + (hit == null ? "未找到"
                        : ("(" + hit[0] + "," + hit[1] + ")  命中率=" + (hit[2] / 1000.0))));
                break;
            }
            case "red": {
                TeamTask tt = newTeamTask();
                int[] red = tt.findConfirmButton(c.captureWindow());
                System.out.println("    「确定」按钮 = " + (red == null ? "未找到"
                        : ("(" + red[0] + "," + red[1] + ")  橙红px=" + red[2])));
                break;
            }
            case "teamtask": {
                // 跑一遍真正的 TeamTask 全流程（扫描图标 → 点图标 → 点确定）
                TeamTask tt = newTeamTask();
                tt.start();
                long dl = System.currentTimeMillis() + 180_000L;
                while (tt.isRunning() && System.currentTimeMillis() < dl) {
                    Thread.sleep(300);
                }
                System.out.println("    TeamTask 结束，running=" + tt.isRunning());
                break;
            }
            case "tpl": {
                System.out.println("    模板 " + newTeamTask().templateInfo());
                System.out.println("    模板 " + newSummonTask().templateInfo());
                break;
            }
            case "summonfile": {
                // summonfile <png路径> —— 用「集体召唤（传送）」模板对离线图片跑检测
                BufferedImage img = ImageIO.read(new File(a[1]));
                if (img == null) {
                    System.out.println("    读图失败");
                    break;
                }
                TeamTask tt = newSummonTask();
                System.out.println("    " + tt.templateInfo());
                int[] fixed = tt.findIconFixed(img);
                System.out.println("    固定窗 = " + (fixed == null ? "未找到"
                        : ("(" + fixed[0] + "," + fixed[1] + ") 命中率=" + (fixed[2] / 1000.0))));
                int[] hit = tt.findIcon(img);
                System.out.println("    全区图标 = " + (hit == null ? "未找到"
                        : ("(" + hit[0] + "," + hit[1] + ") 命中率=" + (hit[2] / 1000.0))));
                int[] best = tt.scanBest(img);
                System.out.println("    全区最高分 = " + (best == null ? "n/a"
                        : ("(" + best[0] + "," + best[1] + ") 命中率=" + (best[2] / 1000.0))));
                break;
            }
            case "teamfile": {
                // teamfile <png路径> —— 对离线图片跑一遍两个检测器
                BufferedImage img = ImageIO.read(new File(a[1]));
                if (img == null) {
                    System.out.println("    读图失败");
                    break;
                }
                TeamTask tt = newTeamTask();
                int[] best = tt.scanBest(img);
                System.out.println("    最高分位置 = " + (best == null ? "n/a"
                        : ("(" + best[0] + "," + best[1] + ") 命中率=" + (best[2] / 1000.0))));
                int[] hit = tt.findIcon(img);
                System.out.println("    图标 = " + (hit == null ? "未找到"
                        : ("(" + hit[0] + "," + hit[1] + ") 命中率=" + (hit[2] / 1000.0))));
                int[] btn = tt.findConfirmButton(img);
                System.out.println("    确定 = " + (btn == null ? "未找到"
                        : ("(" + btn[0] + "," + btn[1] + ") px=" + btn[2])));
                break;
            }
            case "teamloop": {
                // teamloop <秒> [click] —— 持续扫描组队图标；找到后（可选）点击并抓对话框
                int secs = Integer.parseInt(a[1]);
                boolean doClick = a.length > 2 && a[2].equals("click");
                TeamTask tt = newTeamTask();
                long deadline = System.currentTimeMillis() + secs * 1000L;
                int round = 0;
                int[] hit = null;
                while (System.currentTimeMillis() < deadline) {
                    round++;
                    BufferedImage img = c.captureWindow();
                    int[] res = tt.findIcon(img);
                    if (res != null) {
                        hit = res;
                        System.out.println("    ★ FOUND 组队图标 (" + res[0] + "," + res[1] + ")  命中率="
                                + (res[2] / 1000.0) + "  第 " + round + " 次扫描");
                        dump("found_icon");
                        if (doClick) {
                            c.clickWindowPoint(res[0], res[1]);
                            Thread.sleep(1500);
                            dump("after_icon_click");
                            int[] red = tt.findConfirmButton(c.captureWindow());
                            System.out.println("    「确定」按钮 = " + (red == null ? "未找到"
                                    : ("(" + red[0] + "," + red[1] + ") px=" + red[2])));
                        }
                        break;
                    }
                    if (round % 8 == 0) {
                        System.out.println("    扫描中… 第 " + round + " 次  当前最高分=" + (tt.lastBestScore() / 1000.0));
                    }
                    Thread.sleep(500);
                }
                if (hit == null) {
                    System.out.println("    ✗ " + secs + " 秒内没找到组队图标");
                }
                break;
            }
            case "teammark": {
                // 在图上标出搜索区与检测结果，存盘便于核对
                TeamTask tt = newTeamTask();
                BufferedImage img = c.captureWindow();
                if (img == null) {
                    System.out.println("   截图失败");
                    break;
                }
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(new java.awt.Color(0, 255, 0));
                g.drawRect(300, 590, 740 - 300, 715 - 590);
                g.setColor(new java.awt.Color(255, 0, 255));
                g.drawRect(380, 290, 660 - 380, 530 - 290);
                int[] hit = tt.findIcon(img);
                if (hit != null) {
                    g.setColor(new java.awt.Color(255, 0, 0));
                    g.drawOval(hit[0] - 18, hit[1] - 18, 36, 36);
                    System.out.println("    组队图标 (" + hit[0] + "," + hit[1] + ") 命中率=" + (hit[2] / 1000.0));
                } else {
                    System.out.println("    组队图标 未找到");
                }
                int[] red = tt.findConfirmButton(img);
                if (red != null) {
                    g.setColor(new java.awt.Color(0, 255, 255));
                    g.drawOval(red[0] - 18, red[1] - 18, 36, 36);
                    System.out.println("    「确定」按钮 (" + red[0] + "," + red[1] + ") px=" + red[2]);
                }
                g.dispose();
                File f = new File(dir, a[1] + ".png");
                ImageIO.write(img, "png", f);
                System.out.println("    -> " + f.getAbsolutePath());
                break;
            }
            case "crop": {
                int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]);
                int w = Integer.parseInt(a[3]), h = Integer.parseInt(a[4]);
                BufferedImage img = c.captureWindow();
                if (img == null) {
                    System.out.println("   截图失败");
                    break;
                }
                BufferedImage sub = img.getSubimage(
                        Math.max(0, x), Math.max(0, y),
                        Math.min(w, img.getWidth() - x), Math.min(h, img.getHeight() - y));
                int sc = 4;
                BufferedImage big = new BufferedImage(sub.getWidth() * sc, sub.getHeight() * sc,
                        BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = big.createGraphics();
                g.drawImage(sub, 0, 0, big.getWidth(), big.getHeight(), null);
                g.dispose();
                File f = new File(dir, a[5] + ".png");
                ImageIO.write(big, "png", f);
                System.out.println("   crop -> " + f.getAbsolutePath() + "  (原区域 " + x + "," + y + " " + w + "x" + h + ")");
                break;
            }
            case "xlpool": {
                // 本地题库状态
                System.out.println("    题库 = " + XiaolianBank.size() + " 条，loaded="
                        + XiaolianBank.loaded() + (XiaolianBank.loaded() ? "" : "  错误：" + XiaolianBank.loadError()));
                System.out.println("    抠图区域 = " + XiaolianTask.regionInfo());
                System.out.println("    OCR 可用 = " + OcrLite.available()
                        + (OcrLite.available() ? "" : "  错误：" + OcrLite.lastError()));
                break;
            }
            case "xlq": {
                // xlq <一段文字> —— 拿去题库里查，看前 5 个候选与相似度
                String text = cmd.substring(Math.min(cmd.length(), a[0].length())).trim();
                for (String s : XiaolianBank.topMatches(text, 5)) {
                    System.out.println("    " + s);
                }
                break;
            }
            case "xlkey": {
                System.out.println("    API Key = " + mask(XiaolianTask.resolveApiKey()));
                String err = DeepSeek.selfTest(XiaolianTask.resolveApiKey());
                System.out.println("    连通性自检 = " + (err == null ? "✔ 正常" : "✘ " + err));
                break;
            }
            case "xlfile": {
                // xlfile <png路径> —— 对一张离线截图跑完整「读屏 + 决策」，不碰游戏
                BufferedImage img = ImageIO.read(new File(a[1]));
                if (img == null) {
                    System.out.println("    读图失败：" + a[1]);
                    break;
                }
                System.out.println("    图片 " + img.getWidth() + "x" + img.getHeight());
                System.out.println(XiaolianTask.analyzeImage(img));
                break;
            }
            case "xlfile-nai": {
                // xlfile-nai <png路径> —— 同上，但跳过 DeepSeek 兜底（批量评测命中率时快很多，
                // 且评测题库覆盖不看 AI 兜底结果，不必花那个网络等待）
                BufferedImage img = ImageIO.read(new File(a[1]));
                if (img == null) {
                    System.out.println("    读图失败：" + a[1]);
                    break;
                }
                System.out.println("    图片 " + img.getWidth() + "x" + img.getHeight());
                System.out.println(XiaolianTask.analyzeImageNoAi(img));
                break;
            }
            case "xlshot": {
                // 抓当前画面，存图并立刻跑一遍识别（用来标定答题时的抠图区域）
                BufferedImage img = c.captureWindow();
                if (img == null) {
                    System.out.println("   截图失败");
                    break;
                }
                File f = new File(dir, "xl_now.png");
                ImageIO.write(img, "png", f);
                System.out.println("    -> " + f.getAbsolutePath());
                System.out.println(XiaolianTask.analyzeImage(img));
                break;
            }
            case "xlask": {
                // xlask 题目|选项1|选项2|选项3|… —— 直接问 DeepSeek（不依赖游戏画面）
                String rest = cmd.substring(a[0].length()).trim();
                String[] seg = rest.split("\\|");
                if (seg.length < 3) {
                    System.out.println("    用法：xlask 题目|选项1|选项2|选项3");
                    break;
                }
                String q = seg[0].trim();
                java.util.List<String> opts = new java.util.ArrayList<>();
                for (int i = 1; i < seg.length; i++) {
                    if (!seg[i].trim().isEmpty()) {
                        opts.add(seg[i].trim());
                    }
                }
                long t = System.currentTimeMillis();
                Object[] r = DeepSeek.pickOptionVerboseRaw(
                        XiaolianTask.resolveApiKey(), q, opts, 4000, 6000);
                int idx = (Integer) r[1];
                System.out.println("    耗时 " + (System.currentTimeMillis() - t) + "ms");
                System.out.println("    原文：" + r[0]);
                System.out.println("    解析下标：" + idx
                        + (idx >= 0 && idx < opts.size() ? "（" + opts.get(idx) + "）" : ""));
                break;
            }
            case "move": {
                // move x y —— 把鼠标移到窗口内坐标（不点击），便于截图时不被光标遮挡
                c.moveToWindowPoint(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                break;
            }
            case "xlbtn": {
                // xlbtn —— 只 OCR 定位右上角「寻路」按钮（不点），用来验证定位
                int[] p = newXiaolianTask(true, true).findXunluButton();
                System.out.println("    「寻路」按钮定位：" + (p == null ? "未找到" : "(" + p[0] + "," + p[1] + ")"));
                break;
            }
            case "xlclose": {
                // xlclose —— 只定位答题面板右上角红色 X（不点）
                int[] p = newXiaolianTask(true, true).findRedCloseButton();
                System.out.println("    面板 X 定位：" + (p == null ? "未找到" : "(" + p[0] + "," + p[1] + ")"));
                break;
            }
            case "xlboxes": {
                // xlboxes —— 实测寻路面板两个坐标框 + 「移动」按钮位置（不点）
                XiaolianTask t = newXiaolianTask(true, true);
                int[][] bs = t.findCoordBoxes();
                if (bs == null) System.out.println("    坐标框：未找到（用固定坐标）");
                else System.out.println("    坐标框：box1=(" + bs[0][0] + "," + bs[0][1] + ") box2=(" + bs[1][0] + "," + bs[1][1] + ")");
                int[] mv = t.findMoveButton();
                System.out.println("    移动按钮：(" + mv[0] + "," + mv[1] + ")");
                break;
            }
            case "xlcoord": {
                // xlcoord —— OCR 右上角地图坐标条
                int[] xy = newXiaolianTask(true, true).readMapCoords();
                System.out.println("    当前地图坐标：" + (xy == null ? "读取失败" : "(" + xy[0] + "," + xy[1] + ")"));
                break;
            }
            case "xlraw": {
                // xlraw <png> —— 对单张图跑 OCR 并打印全部原始行（调试用）
                java.io.File img = new java.io.File(a[1]);
                java.util.List<OcrLite.Result> rs = OcrLite.recognize(
                        java.util.Collections.singletonList(img), 30);
                for (OcrLite.Result r : rs) {
                    System.out.println("    [" + r.file + "] err=" + r.error
                            + " lines=" + r.lines.size());
                    for (OcrLite.Line ln : r.lines) {
                        System.out.println("      「" + ln.text + "」 @(" + ln.x0 + "," + ln.y0 + ")");
                    }
                }
                break;
            }
            case "xlnav": {
                // xlnav —— 只跑导航（回城 → 寻路(20,16) → ↑ 换图 → 寻路(11,8)），
                //          不按 G、不接任务，零成本验证这段手感操作
                newXiaolianTask(true, false).runBlocking();
                break;
            }
            case "xlrun": {
                // xlrun [dry] —— 跑完整流程；带 dry 只识别不点选项
                boolean dry = a.length >= 2 && "dry".equalsIgnoreCase(a[1]);
                newXiaolianTask(false, dry).runBlocking();
                break;
            }
            case "xlquiz": {
                // xlquiz [dry] —— 只跑答题循环（题目框已经开着时用），不导航、不接任务
                boolean dry = a.length >= 2 && "dry".equalsIgnoreCase(a[1]);
                XiaolianTask t = newXiaolianTask(false, dry);
                System.out.println("    " + t.quizAndFinish());
                break;
            }
            case "xlbuy": {
                // xlbuy —— 只检测「快捷购买」弹窗并点「取消」（不点选项、不答题）。
                //   想验证「答题失败弹窗」的识别准不准时用：手动让游戏弹出该框，
                //   然后执行本命令，看它能不能认出来并点掉。
                XiaolianTask t = newXiaolianTask(false, false);
                int[] c = t.findBuyCancelButton();
                if (c == null) {
                    System.out.println("    未检测到「快捷购买」弹窗（画面上没有这个框）");
                } else {
                    System.out.println("    检测到「取消」按钮 @ (" + c[0] + "," + c[1] + ")，开始点击…");
                    t.dismissBuyDialogIfPresent();
                    System.out.println("    点击完成，画面应已恢复");
                }
                break;
            }
            case "ysnav": {
                // ysnav —— 运送物资：只跑导航（回城 → 寻路(20,16) → ↑ → 寻路(11,8)）
                XiaolianTask t = newXiaolianTask(true, false, XiaolianTask.Mode.YUNSONG);
                t.runBlocking();
                break;
            }
            case "ysrun": {
                // ysrun —— 运送物资：跑完整流程（连按回车结束对话）
                XiaolianTask t = newXiaolianTask(false, false, XiaolianTask.Mode.YUNSONG);
                t.runBlocking();
                break;
            }
            case "ysenter": {
                // ysenter —— 运送物资：跳过导航/接任务，只跑收尾节奏
                //   「回车一下 → 等一秒 → 再回车一下 → 找并点掉『好的，我马上去』」。
                //   想单独验证最后这两下回车 + 点按钮这一段时用。
                XiaolianTask t = newXiaolianTask(false, false, XiaolianTask.Mode.YUNSONG);
                t.pressEnterUntilConfirmButtonProbe();
                break;
            }
            case "ysclick": {
                // ysclick —— 运送物资：只找并点掉收尾按钮，不按回车。
                //   校准 CONFIRM_KEYWORDS / PT_CONFIRM_OK 用：先手动把
                //   「好的，我马上去」停在屏幕上，再跑这个，看日志里的坐标对不对。
                XiaolianTask t = newXiaolianTask(false, false, XiaolianTask.Mode.YUNSONG);
                t.clickConfirmButtonProbe();
                break;
            }
            case "ysfinish": {
                // ysfinish —— 运送物资：只跑「收尾那一轮对话」
                //   （G → 点「关于运送物资」→ 回车两下 → 点掉收尾按钮），不导航、不接任务。
                //   想单独验证收尾那轮菜单长什么样、点的对不对时用。
                XiaolianTask t = newXiaolianTask(false, false, XiaolianTask.Mode.YUNSONG);
                t.finishYunsongQuestProbe();
                break;
            }
            default:
                System.out.println("   未知命令：" + a[0]);
        }
    }

    private static TeamTask newTeamTask() {
        return newTeamTask(TeamTask.Kind.TEAM);
    }

    /** 同上，但是「集体召唤」（传送图标）任务。 */
    private static TeamTask newSummonTask() {
        return newTeamTask(TeamTask.Kind.SUMMON);
    }

    private static TeamTask newTeamTask(TeamTask.Kind kind) {
        return new TeamTask(c, new TeamTask.Listener() {
            public void log(String m) { System.out.println("      " + m); }
            public void alert(String t, String m) { System.out.println("      [alert] " + t); }
            public void finished(boolean ok, String s) { }
        }, kind);
    }

    /** 造一个把日志直接打到控制台的孝廉任务（供 xlnav / xlrun 使用）。 */
    private static XiaolianTask newXiaolianTask(boolean navOnly, boolean dryRun) {
        return newXiaolianTask(navOnly, dryRun, XiaolianTask.Mode.XIAOLIAN);
    }

    /** 同上，但可以指定任务模式（孝廉 / 运送物资）。 */
    private static XiaolianTask newXiaolianTask(boolean navOnly, boolean dryRun,
                                                XiaolianTask.Mode mode) {
        XiaolianTask t = new XiaolianTask(c, new XiaolianTask.Listener() {
            public void log(String m) { System.out.println("      " + m); }
            public void alert(String title, String m) {
                System.out.println("      [alert] " + title + " | " + m.replace("\n", " / "));
            }
            public void finished(boolean ok, String s) { }
        }, dryRun, false, XiaolianTask.resolveApiKey(), mode);
        t.setNavOnly(navOnly);
        return t;
    }

    /** 这批命令里有没有「必须要有游戏窗口」的（纯离线命令 — 查题库 / 分析图片 — 不需要）。 */
    private static boolean needsWindow(String script) {
        for (String raw : script.split(";")) {
            String cmd = raw.trim();
            if (cmd.isEmpty()) {
                continue;
            }
            String head = cmd.split("\\s+")[0];
            if ("xlfile".equals(head) || "xlq".equals(head) || "xlask".equals(head)
                    || "xlpool".equals(head) || "xlkey".equals(head)
                    || "tpl".equals(head) || "summonfile".equals(head)
                    || "teamfile".equals(head)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "(空)";
        }
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    private static void dump(String tag) throws Exception {
        BufferedImage img = c.captureWindow();
        if (img == null) {
            System.out.println("   截图失败");
            return;
        }
        File f = new File(dir, tag + ".png");
        ImageIO.write(img, "png", f);
        int wx = img.getWidth() - GameWindowController.BASE_WIDTH;
        int wy = img.getHeight() - GameWindowController.BASE_HEIGHT;
        System.out.println("   截图 -> " + f.getAbsolutePath() + "  尺寸 " + img.getWidth() + "x" + img.getHeight()
                + (wx != 0 || wy != 0 ? "  ⚠ 与基准 1030x797 不同，坐标需按比例换算" : "  (与基准一致)"));
    }

    /** 抽样算颜色种类数：越少越像「空白/全黑」。 */
    private static int colorCount(BufferedImage img) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        int sx = Math.max(1, img.getWidth() / 30);
        int sy = Math.max(1, img.getHeight() / 20);
        for (int y = 0; y < img.getHeight(); y += sy) {
            for (int x = 0; x < img.getWidth(); x += sx) {
                set.add(img.getRGB(x, y) & 0x00FFFFFF);
            }
        }
        return set.size();
    }

    /** 颜色种类 <= 8 视为空白（PrintWindow 失败典型症状）。 */
    private static boolean isBlank(BufferedImage img) {
        return colorCount(img) <= 8;
    }

    private static void saveImg(BufferedImage img, String tag) throws Exception {
        File f = new File(dir, tag + ".png");
        ImageIO.write(img, "png", f);
        System.out.println("   图片 -> " + f.getAbsolutePath());
    }

    /** 两张同尺寸图的平均像素差（0~255），用来判断「画面有没有变」。 */
    private static double meanAbsDiff(BufferedImage a, BufferedImage b) {
        long sum = 0;
        long n = 0;
        int sx = Math.max(1, a.getWidth() / 60);
        int sy = Math.max(1, a.getHeight() / 40);
        for (int y = 0; y < a.getHeight(); y += sy) {
            for (int x = 0; x < a.getWidth(); x += sx) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                sum += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
                sum += Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
                sum += Math.abs((p & 0xFF) - (q & 0xFF));
                n += 3;
            }
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    /**
     * 用某一种「后台发鼠标」手法点一下，并比较前后画面差异，判断游戏是否认。
     *
     * @param strategy 1=PostMessage顶层 2=SendMessage顶层 3=PostMessage(先激活)
     *                 4=SendMessage(先激活) 5=PostMessage(子窗)
     */
    private static void testBgStrategy(String name, int wx, int wy, int strategy)
            throws Exception {
        BufferedImage before = c.captureWindowBackground();
        if (before == null) {
            System.out.println("   [" + name + "] 截图失败，跳过");
            return;
        }
        boolean sent = true;
        if (strategy != 0) {
            sent = c.testBackgroundClickStrategy(strategy, wx, wy);
        }
        Thread.sleep(1500);
        BufferedImage after = c.captureWindowBackground();
        double d = (after == null) ? -1 : meanAbsDiff(before, after);
        System.out.println("   [" + name + "] 发送=" + (strategy == 0 ? "(对照)" : String.valueOf(sent))
                + "  像素差=" + (d < 0 ? "n/a" : String.format("%.2f", d)));
        if (after != null && strategy != 0) {
            saveImg(after, "bgprobe_" + strategy + "_after");
        }
    }
}
