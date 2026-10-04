package com.qqsg.helper;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 霸王城任务的只读自检工具 —— <b>不按键、不点击、不动游戏</b>。
 *
 * <h3>两种用法</h3>
 * <pre>
 * 1) 对当前游戏画面自检（需要游戏在运行）
 *    java -cp helper-....jar com.qqsg.helper.BawangDiag &lt;PID&gt;
 *    PID 省略时自动取第一个 QQ三国 窗口。
 *
 * 2) 对离线截图批量自检（回归用，不碰游戏）
 *    java -cp helper-....jar com.qqsg.helper.BawangDiag --dir &lt;截图目录&gt;
 * </pre>
 *
 * <p>它调用的是 {@link BawangTask#scanImage}，与实跑用的是同一套判定，
 * 所以自检结果能真实反映运行时会不会点错位置。
 */
public class BawangDiag {

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--dir".equals(args[0])) {
            runDir(args[1]);
            return;
        }
        int pid = args.length >= 1 ? Integer.parseInt(args[0]) : -1;
        runLive(pid);
    }

    // ==================== 实时自检 ====================

    private static void runLive(int pid) {
        if (pid <= 0) {
            // 退而求其次：找第一个 QQ三国 窗口
            for (WindowInfo w : WindowUtils.getAllWindowsWithIds()) {
                if (w.getTitle() != null && w.getTitle().contains("QQ三国")) {
                    pid = w.getPid();
                    break;
                }
            }
        }
        if (pid <= 0) {
            System.out.println("✗ 没有找到 QQ三国 窗口，请用 java ... BawangDiag <PID>");
            return;
        }

        GameWindowController c = new GameWindowController("diag(pid=" + pid + ")", pid);
        System.out.println("================ 霸王城 · 实时自检 ================");
        java.awt.Rectangle r = c.getWindowRect();
        if (r == null) {
            System.out.println("✗ 取不到窗口矩形，游戏可能已退出");
            return;
        }
        System.out.println("窗口 PID : " + pid);
        System.out.println("窗口矩形 : " + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");
        double kx = r.width / (double) GameWindowController.BASE_WIDTH;
        double ky = r.height / (double) GameWindowController.BASE_HEIGHT;
        System.out.printf("缩放系数 : %.3f x %.3f %s%n", kx, ky,
                (Math.abs(kx - 1) < 0.02 && Math.abs(ky - 1) < 0.02) ? "(与基准一致 ✔)" : "(非基准尺寸，坐标会等比换算)");

        BawangTask.Ui ui = BawangTask.scanImage(c.captureWindow());
        report(ui);

        System.out.println("\n说明：");
        System.out.println("  · 两个都 false 是正常状态（没开寻路面板、也没在对话中）");
        System.out.println("  · navOpen=true  → 说明「自动寻路」面板开着");
        System.out.println("  · dialogOpen=true → 说明 NPC 对话开着，option1Y 是首项中心 y");
    }

    private static void report(BawangTask.Ui ui) {
        System.out.println("「自动寻路」面板打开 : " + (ui.navOpen ? "是" : "否"));
        System.out.println("NPC 对话已弹出       : " + (ui.dialogOpen ? "是" : "否"));
        if (ui.dialogOpen) {
            System.out.println("对话首项中心 y       : " + ui.option1Y
                    + "   （第 2 项 ≈" + Math.round(ui.option1Y + 27.7)
                    + "，第 4 项 ≈" + Math.round(ui.option1Y + 3 * 27.7)
                    + "，第 5 项 ≈" + Math.round(ui.option1Y + 4 * 27.7) + "）");
        }
    }

    // ==================== 离线截图自检 ====================

    /** 期望值表：文件名 -> 对话首项中心 y 的人工量图结果。 */
    private static final String[][] EXPECT = {
            {"08_press_G.png", "395"},   // 大司马主菜单（5 项）
            {"09_gaixia.png", "375"},    // 垓下学艺菜单（5 项，位置更靠上）
            {"10_send_me.png", "338"},   // 出发/取消确认框（2 项）
            {"12_bw_G.png", "395"},      // 高阶学艺导师菜单（6 项）
            {"13_jingxiu.png", "395"},   // 静修时长菜单（6 项）
            {"15_G_again.png", "395"},   // 收尾对话（6 项）
    };
    /** 应判定成「自动寻路面板打开」的截图。 */
    private static final String[] EXPECT_NAV = {
            "02_after_xunlu.png", "03_coords_typed.png", "04_after_move.png",
            "05_wait.png", "06_closed_xunlu.png",
    };

    private static void runDir(String dir) throws Exception {
        File d = new File(dir);
        if (!d.isDirectory()) {
            System.out.println("✗ 不是目录: " + dir);
            return;
        }
        System.out.println("================ 霸王城 · 离线截图自检 ================");
        System.out.println("目录: " + d.getAbsolutePath());

        System.out.println("\n--- 正样本：应判定为「对话已弹出」---");
        int ok = 0, fail = 0;
        for (String[] e : EXPECT) {
            BawangTask.Ui ui = scanFile(new File(d, e[0]));
            if (ui == null) {
                System.out.println("  " + e[0] + " : 打不开");
                fail++;
                continue;
            }
            int want = Integer.parseInt(e[1]);
            boolean pass = ui.dialogOpen && !ui.navOpen && Math.abs(ui.option1Y - want) <= 10;
            System.out.printf("  %-20s dialog=%-5s nav=%-5s option1Y=%-4d (期望 %d) %s%n",
                    e[0], ui.dialogOpen, ui.navOpen, ui.option1Y, want, pass ? "✔" : "✗");
            if (pass) {
                ok++;
            } else {
                fail++;
            }
        }

        System.out.println("\n--- 「自动寻路」面板样本：应判定为 navOpen ---");
        for (String n : EXPECT_NAV) {
            BawangTask.Ui ui = scanFile(new File(d, n));
            if (ui == null) {
                System.out.println("  " + n + " : 打不开");
                continue;
            }
            boolean pass = ui.navOpen && !ui.dialogOpen;
            System.out.printf("  %-20s dialog=%-5s nav=%-5s %s%n",
                    n, ui.dialogOpen, ui.navOpen, pass ? "✔" : "✗");
            if (pass) {
                ok++;
            } else {
                fail++;
            }
        }

        System.out.println("\n--- 负样本：应判定为「两者都没有」---");
        List<String> negs = new ArrayList<>();
        File[] all = d.listFiles();
        if (all != null) {
            for (File f : all) {
                String n = f.getName();
                if (!n.endsWith(".png") || n.startsWith("grid_") || n.startsWith("off_")
                        || n.startsWith("crop_") || n.startsWith("diag_")) {
                    continue;
                }
                boolean skip = false;
                for (String[] e : EXPECT) {
                    if (e[0].equals(n)) {
                        skip = true;
                    }
                }
                for (String e : EXPECT_NAV) {
                    if (e.equals(n)) {
                        skip = true;
                    }
                }
                if (!skip) {
                    negs.add(n);
                }
            }
        }
        for (String n : negs) {
            BawangTask.Ui ui = scanFile(new File(d, n));
            if (ui == null) {
                continue;
            }
            boolean pass = !ui.navOpen && !ui.dialogOpen;
            System.out.printf("  %-20s dialog=%-5s nav=%-5s %s%n",
                    n, ui.dialogOpen, ui.navOpen, pass ? "✔" : "✗");
            if (pass) {
                ok++;
            } else {
                fail++;
            }
        }

        System.out.println("\n结果: 通过 " + ok + " / 失败 " + fail);
        System.out.println("（正样本 option1Y 允许 ±10px 误差 —— 选项行高约 22px）");
    }

    private static BawangTask.Ui scanFile(File f) {
        if (!f.exists()) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(f);
            return BawangTask.scanImage(img);
        } catch (Exception e) {
            return null;
        }
    }
}
