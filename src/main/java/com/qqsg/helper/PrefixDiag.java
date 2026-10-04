package com.qqsg.helper;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 「霸王城」前置三步的实机验证器 —— 直接调用 {@link BawangTask} 里的真实代码，
 * 不另抄一份坐标，所以流程/坐标改了之后跑一遍就能确认是否还走得通。
 *
 * <h3>用法</h3>
 * <pre>
 * java -cp helper-....jar com.qqsg.helper.PrefixDiag [PID] [截图目录]
 * </pre>
 *
 * <p>验证内容：关广告 → O 回军团 → T 回主城，每步存一张截图。
 * 全程<b>不进入霸王城</b>，不消耗静修凭证；但会真实操作游戏（角色会被传送），
 * 所以不要在战斗/副本中途跑。
 */
public class PrefixDiag {

    public static void main(String[] args) throws Exception {
        int pid = args.length >= 1 ? Integer.parseInt(args[0]) : -1;
        String outDir = args.length >= 2 ? args[1] : "prefix_shots";

        if (pid <= 0) {
            for (WindowInfo w : WindowUtils.getAllWindowsWithIds()) {
                if (w.getTitle() != null && w.getTitle().contains("QQ三国")) {
                    pid = w.getPid();
                    break;
                }
            }
        }
        if (pid <= 0) {
            System.out.println("✗ 没有找到 QQ三国 窗口，请用 java ... PrefixDiag <PID>");
            return;
        }

        File dir = new File(outDir);
        dir.mkdirs();

        GameWindowController c = new GameWindowController("prefix-diag(pid=" + pid + ")", pid);
        Rectangle r = c.getWindowRect();
        System.out.println("窗口 PID : " + pid);
        System.out.println("窗口矩形 : " + (r == null ? "null"
                : r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")"));
        if (r == null) {
            return;
        }

        BawangTask task = new BawangTask(c, new BawangTask.Listener() {
            @Override
            public void log(String message) {
                System.out.println(message);
            }

            @Override
            public void alert(String title, String message) {
                System.out.println("[需要人工处理] " + title + "\n" + message);
            }

            @Override
            public void finished(boolean success, String summary) {
                // 本工具只跑前置，不用这个回调
            }
        });

        c.focusWindow();
        Thread.sleep(800);
        dump(c, dir, "p0_start");

        System.out.println("\n>>> 步骤 1/3：关闭广告弹窗");
        task.closeAds();
        dump(c, dir, "p1_ads_closed");

        System.out.println("\n>>> 步骤 2/3：按 O →「回到军团」");
        task.goBackToLegion();
        dump(c, dir, "p2_back_legion");

        System.out.println("\n>>> 步骤 3/3：按 T 回城");
        task.backToTown();
        dump(c, dir, "p3_back_town");

        System.out.println("\nDONE —— 请对照截图确认最终停在成都·子城");
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
