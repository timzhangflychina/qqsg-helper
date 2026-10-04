package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 「工资 · 官爵任务」实机验证器 —— 直接调用 {@link SalaryTask} 的真实方法，不另抄坐标。
 *
 * <h3>用法</h3>
 * <pre>
 * java -cp helper-....jar com.qqsg.helper.SalaryDiag [PID] [截图目录] [stage]
 * </pre>
 *
 * <p>stage：{@code prefix} 只跑前置三步；{@code nav} 再加寻路；{@code accept} 再加接任务；
 * 缺省 {@code all} 跑完整流程（含逐个 NPC）。每步存一张截图。
 */
public class SalaryDiag {

    public static void main(String[] args) throws Exception {
        int pid = args.length >= 1 ? Integer.parseInt(args[0]) : -1;
        String outDir = args.length >= 2 ? args[1] : "salary_diag";
        String stage = args.length >= 3 ? args[2] : "all";

        if (pid <= 0) {
            for (WindowInfo w : WindowUtils.getAllWindowsWithIds()) {
                if (w.getTitle() != null && w.getTitle().contains("QQ三国")) {
                    pid = w.getPid();
                    break;
                }
            }
        }
        if (pid <= 0) {
            System.out.println("✗ 没有找到 QQ三国 窗口，请用 java ... SalaryDiag <PID>");
            return;
        }

        File dir = new File(outDir);
        dir.mkdirs();

        GameWindowController c = new GameWindowController("salary-diag(pid=" + pid + ")", pid);
        Rectangle r = c.getWindowRect();
        System.out.println("窗口 PID : " + pid);
        System.out.println("窗口矩形 : " + (r == null ? "null"
                : r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")"));
        if (r == null) {
            return;
        }

        SalaryTask task = new SalaryTask(c, new SalaryTask.Listener() {
            @Override
            public void log(String m) {
                System.out.println(m);
            }

            @Override
            public void alert(String title, String m) {
                System.out.println("[需要人工处理] " + title + "\n" + m);
            }

            @Override
            public void finished(boolean ok, String s) {
                System.out.println("finished ok=" + ok + " " + s);
            }
        });

        c.focusWindow();
        Thread.sleep(800);
        dump(c, dir, "d0_start");

        System.out.println("\n>>> 前置：关广告 → 回军团 → 回城");
        task.closeAds();
        task.goBackToLegion();
        task.backToTown();
        dump(c, dir, "d1_town");

        if (stage.equals("prefix")) {
            System.out.println("\nDONE(prefix)");
            return;
        }

        System.out.println("\n>>> 寻路 (11,7)");
        task.navToSalaryNpc();
        dump(c, dir, "d2_nav_done");

        if (stage.equals("nav")) {
            System.out.println("\nDONE(nav)");
            return;
        }

        System.out.println("\n>>> 接受官爵任务");
        task.acceptSalaryQuest();
        dump(c, dir, "d3_accepted");

        if (stage.equals("accept")) {
            System.out.println("\nDONE(accept)");
            return;
        }

        System.out.println("\n>>> 逐个 NPC 跑任务链");
        task.runNpcChain();
        dump(c, dir, "d4_chain_done");

        System.out.println("\nDONE(all)");
    }

    private static void dump(GameWindowController c, File dir, String tag) throws Exception {
        BufferedImage img = c.captureWindow();
        if (img == null) {
            System.out.println("   截图失败");
            return;
        }
        File f = new File(dir, tag + ".png");
        ImageIO.write(img, "png", f);
        System.out.println("   截图 -> " + f.getAbsolutePath());
    }
}
