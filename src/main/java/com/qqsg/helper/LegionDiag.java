package com.qqsg.helper;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * 军团任务自检工具（只读画面，不按键、不点击）。
 *
 * <p>用途：在真实游戏窗口上验证「窗口定位 / 截图 / 采样检测」这套链路是否正常，
 * 排查「检测不到弹窗」「检测不到对话」这类问题。
 *
 * <pre>
 * 用法：java -cp helper-xxx-jar-with-dependencies.jar com.qqsg.helper.LegionDiag
 * </pre>
 *
 * 输出：窗口矩形、两张检测采样区的平均 RGB 与判定结果、以及一张截图存到 legion_shots/。
 */
public class LegionDiag {

    // 与 LegionTask 保持一致的采样区与阈值
    private static final int BASE_W = GameWindowController.BASE_WIDTH;
    private static final int BASE_H = GameWindowController.BASE_HEIGHT;
    private static final int[] PATCH_AD_HOTSPOT = {753, 297, 50, 50};
    private static final int[] PATCH_DLG_OPTION = {560, 285, 140, 22};
    private static final double TH_AD_RG = 20.0;
    private static final double TH_DLG_BR = 35.0;

    public static void main(String[] args) {
        System.out.println("=== 军团任务自检 ===\n");

        final int[] found = {-1};
        final String[] title = {null};

        WindowsAPI.WNDENUMPROC cb = new WindowsAPI.WNDENUMPROC() {
            @Override
            public boolean callback(HWND hWnd, Pointer data) {
                try {
                    int len = WindowsAPI.INSTANCE.GetWindowTextLength(hWnd);
                    if (len <= 0) return true;
                    char[] buf = new char[len + 1];
                    WindowsAPI.INSTANCE.GetWindowText(hWnd, buf, buf.length);
                    String t = new String(buf).trim();
                    if (t.contains("QQ三国") || t.contains("三足鼎立") || t.contains("线")) {
                        int[] pid = {0};
                        WindowsAPI.INSTANCE.GetWindowThreadProcessId(hWnd, pid);
                        if (pid[0] > 0) {
                            System.out.println("候选窗口: \"" + t + "\"  PID=" + pid[0] + "  HWND=" + hWnd);
                            if (found[0] < 0) {
                                found[0] = pid[0];
                                title[0] = t;
                            }
                        }
                    }
                    return true;
                } catch (Throwable e) {
                    return true;
                }
            }
        };
        WindowsAPI.INSTANCE.EnumWindows(cb, null);

        if (found[0] < 0) {
            System.out.println("✘ 未找到 QQ三国 游戏窗口，请确认游戏已启动。");
            return;
        }

        System.out.println("\n使用窗口: \"" + title[0] + "\"  PID=" + found[0]);
        GameWindowController ctl = new GameWindowController(title[0], found[0]);

        Rectangle r = ctl.getWindowRect();
        if (r == null) {
            System.out.println("✘ 取窗口矩形失败。");
            return;
        }
        System.out.println("窗口矩形: " + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");
        System.out.println("坐标基准: " + BASE_W + "x" + BASE_H
                + "，缩放系数 = " + String.format("%.3f / %.3f",
                r.width / (double) BASE_W, r.height / (double) BASE_H));

        BufferedImage img = ctl.captureWindow();
        if (img == null) {
            System.out.println("✘ 截图失败。");
            return;
        }
        System.out.println("截图尺寸: " + img.getWidth() + "x" + img.getHeight());

        double[] ad = patchMean(img, PATCH_AD_HOTSPOT);
        double[] dlg = patchMean(img, PATCH_DLG_OPTION);

        System.out.println();
        if (ad != null) {
            double rg = ad[0] - ad[1];
            System.out.printf("[广告检测] 区域%s 均值 RGB=(%.0f,%.0f,%.0f)  R-G=%+.0f  → %s%n",
                    java.util.Arrays.toString(PATCH_AD_HOTSPOT), ad[0], ad[1], ad[2], rg,
                    rg > TH_AD_RG ? "「热点活动」弹窗仍在" : "无「热点活动」弹窗");
        }
        if (dlg != null) {
            double br = dlg[2] - dlg[0];
            System.out.printf("[对话检测] 区域%s 均值 RGB=(%.0f,%.0f,%.0f)  B-R=%+.0f  → %s%n",
                    java.util.Arrays.toString(PATCH_DLG_OPTION), dlg[0], dlg[1], dlg[2], br,
                    br > TH_DLG_BR ? "已弹出 NPC 对话" : "当前没有 NPC 对话");
        }

        System.out.println("\n=== 自检结束（未对游戏做任何操作）===");
    }

    private static double[] patchMean(BufferedImage img, int[] patch) {
        double kx = img.getWidth() / (double) BASE_W;
        double ky = img.getHeight() / (double) BASE_H;
        int x = (int) Math.round(patch[0] * kx);
        int y = (int) Math.round(patch[1] * ky);
        int w = Math.max(1, (int) Math.round(patch[2] * kx));
        int h = Math.max(1, (int) Math.round(patch[3] * ky));
        if (x < 0 || y < 0 || x + w > img.getWidth() || y + h > img.getHeight()) return null;
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
        return n == 0 ? null : new double[]{sr / (double) n, sg / (double) n, sb / (double) n};
    }
}
