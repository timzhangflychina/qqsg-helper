
package com.qqsg.helper;

import java.io.IOException;
import javax.swing.JFrame;

/**
 * 程序入口。
 *
 * <p>启动方式说明：为了让本程序能够向以管理员权限运行的 QQ三国客户端
 * 发送窗口消息（PostMessage 受 UAC 的 UIPI 隔离限制），本进程必须以
 * 管理员权限运行。
 *
 * <p>项目采用「一次性提权」方案：专用的启动器副本
 * {@code target/qqsg-launcher.exe} 被打上了 RUNASADMIN 兼容标记，
 * 双击即可直接以提权令牌启动，不会每次弹出 UAC 同意框。
 * 详见 deploy_no_uac.py 与 环境配置指南.md。
 */
public class Main {
    public static void main(String[] args) {
        installFileLogging();
        System.out.println("=== QQSG Helper Starting ===");
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name")
                + " " + System.getProperty("os.version"));
        System.out.println("Admin privileges: " + isAdmin());

        if (!isAdmin()) {
            System.out.println("[WARN] 当前未以管理员权限运行，"
                    + "可能无法控制游戏窗口。");
            System.out.println("[WARN] 请使用 target/qqsg-launcher.exe "
                    + "或 start_qqsg.bat 启动。");
        }

        System.out.println("[DEBUG] Starting main UI...");
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                // 安装统一外观与字体（必须在创建任何组件之前）
                com.qqsg.helper.ui.UiKit.install();
                MainFrame mainFrame = new MainFrame();
                mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                mainFrame.setVisible(true);
                System.out.println("Main UI started successfully");
                maybeAutoScreenshot(mainFrame);
            } catch (Exception e) {
                System.err.println("Failed to create main frame: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 把 System.out / System.err 同时写入工作目录下的 qqsg_gui.log。
     *
     * <p>桌面快捷方式用 javaw 启动，没有控制台，GUI 任务的全部日志
     * （任务进度、[CLICK] 点击坐标、异常栈）原本都会丢掉 —— 出问题只能靠截图盲猜。
     * 装上这个 tee 之后，每次 GUI 运行都在 qqsg_gui.log 留下完整痕迹，
     * 5MB 自动轮转到 qqsg_gui.old.log，避免无限膨胀。
     */
    private static void installFileLogging() {
        try {
            java.io.File f = new java.io.File("qqsg_gui.log");
            if (f.isFile() && f.length() > 5L * 1024 * 1024) {
                java.io.File old = new java.io.File("qqsg_gui.old.log");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            final java.io.PrintStream origOut = System.out;
            final java.io.PrintStream fileOut = new java.io.PrintStream(
                    new java.io.FileOutputStream(f, true), true, "UTF-8");
            java.io.OutputStream tee = new java.io.OutputStream() {
                @Override
                public void write(int b) throws java.io.IOException {
                    origOut.write(b);
                    fileOut.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws java.io.IOException {
                    origOut.write(b, off, len);
                    fileOut.write(b, off, len);
                }

                @Override
                public void flush() throws java.io.IOException {
                    origOut.flush();
                    fileOut.flush();
                }
            };
            System.setOut(new java.io.PrintStream(tee, true));
            System.setErr(new java.io.PrintStream(tee, true));
            fileOut.println();
            fileOut.println("===== 启动 " + new java.util.Date() + " =====");
            fileOut.flush();
        } catch (Throwable t) {
            System.err.println("installFileLogging 失败: " + t);
        }
    }

    /**
     * 开发期自检用：加了 {@code -Dqqsg.ui.shot=<png 路径>} 时，
     * 界面显示 2.5 秒后自动截图保存并退出进程，方便在不切换窗口的情况下检查排版。
     */
    private static void maybeAutoScreenshot(final JFrame frame) {
        final String path = System.getProperty("qqsg.ui.shot");
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        frame.setAlwaysOnTop(true);

        // 可选：截图前先把窗口改成指定尺寸，用来验证换行是否跟着宽度走
        String resize = System.getProperty("qqsg.ui.shot.resize");
        if (resize != null && resize.contains("x")) {
            final String[] p = resize.split("x");
            javax.swing.Timer t = new javax.swing.Timer(1200, e -> {
                try {
                    frame.setSize(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
                } catch (Exception ignored) {
                    // 参数不合法就跳过
                }
            });
            t.setRepeats(false);
            t.start();
        }

        javax.swing.Timer timer = new javax.swing.Timer(2500, e -> {
            try {
                java.awt.Rectangle r = frame.getBounds();
                java.awt.image.BufferedImage img =
                        new java.awt.Robot().createScreenCapture(r);
                javax.imageio.ImageIO.write(img, "png", new java.io.File(path));
                System.out.println("[SHOT] saved " + path);
            } catch (Exception ex) {
                System.out.println("[SHOT] failed: " + ex);
            }
            System.exit(0);
        });
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * 判断当前进程是否具有管理员权限。
     *
     * <p>原先用 {@code net session} 子进程探测，启动慢且依赖外部命令；
     * 这里改用 Windows API {@code shell32.IsUserAnAdmin}，快且无副作用。
     */
    private static boolean isAdmin() {
        String os = System.getProperty("os.name");
        if (os == null || !os.contains("Windows")) {
            return true; // 非 Windows 系统不做限制
        }

        try {
            return WindowsAPI.Shell32Ex.INSTANCE.IsUserAnAdmin();
        } catch (Throwable t) {
            System.err.println("[WARN] 权限检测失败: " + t.getMessage());
            return false;
        }
    }
}
