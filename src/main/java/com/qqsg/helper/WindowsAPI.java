package com.qqsg.helper;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.W32APIOptions;

public interface WindowsAPI extends Library {
    WindowsAPI INSTANCE = Native.load("user32", WindowsAPI.class, W32APIOptions.DEFAULT_OPTIONS);
    
    // 发送窗口消息
    boolean PostMessage(HWND hWnd, int Msg, int wParam, int lParam);

    /**
     * 发送窗口消息（<b>同步</b>，等目标窗口处理完才返回）。
     *
     * <p>与 {@link #PostMessage} 的区别：PostMessage 是「投递到队列，立刻返回」，
     * SendMessage 是「直接调用目标窗口过程，处理完才返回」。部分游戏引擎
     * 只认 SendMessage（因为它会真正进入窗口过程并在同一线程执行），
     * 对 PostMessage 投递的鼠标消息反而不予理会。
     */
    int SendMessage(HWND hWnd, int Msg, int wParam, int lParam);

    /** 不抢前台、只把窗口设为「活动窗口」（部分引擎靠它决定是否接收输入）。 */
    HWND SetActiveWindow(HWND hWnd);

    /** 把键盘焦点给指定窗口（不置前）。 */
    HWND SetFocus(HWND hWnd);

    /** 把两个线程的输入队列接在一起（跨进程抢焦点/发输入时的惯用手法）。 */
    boolean AttachThreadInput(int idAttach, int idAttachTo, boolean fAttach);

    /** 取当前线程 ID。 */
    int GetCurrentThreadId();

    // 窗口激活相关消息
    int WM_ACTIVATE = 0x0006;
    int WM_ACTIVATEAPP = 0x001C;
    int WM_NCACTIVATE = 0x0086;
    int WM_SETFOCUS = 0x0007;
    int WM_KILLFOCUS = 0x0008;
    int WA_ACTIVE = 1;
    int WA_CLICKACTIVE = 2;
    
    // 查找窗口
    HWND FindWindow(String lpClassName, String lpWindowName);
    
    // 获取窗口文本
    int GetWindowText(HWND hWnd, char[] lpString, int nMaxCount);

    /** 取窗口类名（用来认「QQSGWinClass」这类主窗口特征）。 */
    int GetClassName(HWND hWnd, char[] lpClassName, int nMaxCount);
    
    // 获取窗口文本长度
    int GetWindowTextLength(HWND hWnd);
    
    // 获取下一个窗口
    HWND GetNextWindow(HWND hWnd, int wCmd);
    
    // 获取窗口进程ID
    int GetWindowThreadProcessId(HWND hWnd, int[] lpdwProcessId);
    
    // 键盘消息常量
    int WM_KEYDOWN = 0x0100;
    int WM_KEYUP = 0x0101;
    /** 字符输入消息：编辑框收到一条就插入一个字符（不会被翻译成两个）。 */
    int WM_CHAR = 0x0102;

    // ---- 鼠标消息常量（后台点击用） ----

    /** 鼠标移动（客户区坐标在 lParam）。 */
    int WM_MOUSEMOVE = 0x0200;
    /** 左键按下。 */
    int WM_LBUTTONDOWN = 0x0201;
    /** 左键抬起。 */
    int WM_LBUTTONUP = 0x0202;
    /** 左键双击。 */
    int WM_LBUTTONDBLCLK = 0x0203;

    /**
     * 把窗口客户区坐标 (x,y) 打包成 WM_*MOUSE* 消息的 lParam。
     *
     * <p>低 16 位 = x，高 16 位 = y，两者都是<b>客户区坐标</b>（相对窗口内部左上角），
     * 不是屏幕坐标。坐标会被截断到 16 位（0~65535），游戏窗口远小于这个范围，够用。
     */
    static int makeLParam(int x, int y) {
        return (x & 0xFFFF) | ((y & 0xFFFF) << 16);
    }

    /**
     * 把屏幕坐标转成 WM_*MOUSE* 消息 lParam 用的值。
     *
     * <p>只有当游戏真的按「屏幕坐标」解析时才需要；一般用客户区坐标即可。
     * 这里保留一个转换函数方便实测两种口径。
     */
    static int makeLParamScreen(int x, int y) {
        return makeLParam(x, y);
    }

    // ---- 后台截图 ----

    /**
     * 把窗口内容画到目标 DC 上 —— <b>后台截图的核心 API</b>。
     *
     * <p>与 {@code Robot.createScreenCapture} 的根本区别：Robot 是「抓屏幕」，
     * 窗口被遮挡就抓到遮挡物；PrintWindow 是「让窗口自己把自己画一遍」，
     * 被遮挡、甚至部分移出屏幕也能拿到真实画面。
     *
     * @param hWnd   目标窗口
     * @param hdc    目标 DC（由调用方创建/释放）
     * @param flags  0 = 普通；{@link #PW_RENDERFULLCONTENT} = 支持 DirectComposition
     *               等硬件合成内容（很多自绘游戏必须带这个标志）
     */
    boolean PrintWindow(HWND hWnd, com.sun.jna.platform.win32.WinDef.HDC hdc, int flags);

    /** PrintWindow 标志：整窗渲染。 */
    int PW_CLIENTONLY = 0x00000001;
    /** PrintWindow 标志：渲染包含 DirectComposition 在内的完整内容（Win8.1+）。 */
    int PW_RENDERFULLCONTENT = 0x00000002;

    /** 取窗口客户区大小（后台点击需要客户区坐标）。 */
    boolean GetClientRect(HWND hWnd, com.sun.jna.platform.win32.WinDef.RECT lpRect);

    /** 客户区坐标 -> 屏幕坐标。 */
    boolean ClientToScreen(HWND hWnd, com.sun.jna.platform.win32.WinDef.POINT lpPoint);

    /** 屏幕坐标 -> 客户区坐标。 */
    boolean ScreenToClient(HWND hWnd, com.sun.jna.platform.win32.WinDef.POINT lpPoint);

    // ---- GDI（配合 PrintWindow 建位图） ----

    interface Gdi32Ex extends Library {
        Gdi32Ex INSTANCE = Native.load("gdi32", Gdi32Ex.class,
                W32APIOptions.DEFAULT_OPTIONS);

        /** 创建内存 DC。 */
        com.sun.jna.platform.win32.WinDef.HDC CreateCompatibleDC(
                com.sun.jna.platform.win32.WinDef.HDC hdc);

        /** 创建与指定 DC 兼容的位图。 */
        com.sun.jna.platform.win32.WinDef.HBITMAP CreateCompatibleBitmap(
                com.sun.jna.platform.win32.WinDef.HDC hdc, int width, int height);

        /**
         * 把位图选进 DC。
         *
         * <p>参数用 {@code HBITMAP} 而不是 {@code HGDIOBJ} —— JNA 的
         * {@code platform.win32.WinDef} 里<b>没有</b> HGDIOBJ 这个类型
         * （只有 HDC/HBITMAP/HBRUSH 等具体句柄）。HBITMAP 是 PointerType
         * 子类，按值传递句柄完全没有问题。
         */
        com.sun.jna.platform.win32.WinDef.HBITMAP SelectObject(
                com.sun.jna.platform.win32.WinDef.HDC hdc,
                com.sun.jna.platform.win32.WinDef.HBITMAP h);

        /** 删除 GDI 对象（HBITMAP 等）。 */
        boolean DeleteObject(com.sun.jna.platform.win32.WinDef.HBITMAP ho);

        /** 删除 DC。 */
        boolean DeleteDC(com.sun.jna.platform.win32.WinDef.HDC hdc);

        /** 取 DIB 位。 */
        int GetDIBits(com.sun.jna.platform.win32.WinDef.HDC hdc,
                      com.sun.jna.platform.win32.WinDef.HBITMAP hbm,
                      int start, int cLines, Pointer lpvBits,
                      com.sun.jna.platform.win32.WinGDI.BITMAPINFO lpbmi, int usage);
    }

    /** 取窗口 DC（配合 PrintWindow 用，PW_CLIENTONLY 时给客户区 DC）。 */
    com.sun.jna.platform.win32.WinDef.HDC GetWindowDC(HWND hWnd);

    /** 取客户区 DC。 */
    com.sun.jna.platform.win32.WinDef.HDC GetDC(HWND hWnd);

    /** 释放 DC。 */
    int ReleaseDC(HWND hWnd, com.sun.jna.platform.win32.WinDef.HDC hDC);
    
    // 获取窗口句柄
    HWND GetTopWindow(HWND hWnd);
    
    // 判断窗口是否可见
    boolean IsWindowVisible(HWND hWnd);
    
    // 枚举窗口回调接口
    interface WNDENUMPROC extends Callback {
        boolean callback(HWND hWnd, Pointer data);
    }
    
    // 枚举所有顶级窗口
    boolean EnumWindows(WNDENUMPROC lpEnumFunc, Pointer lParam);

    /**
     * 枚举某个窗口的所有<b>子窗口</b>（含孙窗口）。
     *
     * <p>为什么需要它：很多游戏把真正的渲染/输入窗口做成子窗口
     * （例如 D3DFocusWindow），鼠标消息投给顶层窗口没用，得投给子窗口。
     */
    boolean EnumChildWindows(HWND hWndParent, WNDENUMPROC lpEnumFunc, Pointer lParam);
    
    // ---- 管理员权限检测 ----
    
    /**
     * 判断当前进程是否以管理员权限运行。
     * 声明在 shell32 上，由 Shell32API 接口实际调用。
     */
    interface Shell32Ex extends Library {
        Shell32Ex INSTANCE = Native.load("shell32", Shell32Ex.class,
                W32APIOptions.DEFAULT_OPTIONS);
        
        /** 当前进程令牌是否属于管理员组（提权后返回非 0） */
        boolean IsUserAnAdmin();
    }
    
    // ---- 窗口置前（可选用） ----
    
    /** 将窗口带到前台 */
    boolean SetForegroundWindow(HWND hWnd);
    
    /** 窗口是否最小化 */
    boolean IsIconic(HWND hWnd);

    // ---- 窗口 Z 序 / 位置（解决「被别的窗口挡住」导致点击打偏） ----

    /**
     * 改变窗口的位置与 Z 序。
     *
     * <p>为什么必须要有它：Robot 的点击是「按屏幕坐标」发出去的，
     * 只要游戏窗口被别的窗口（例如置顶的 Electron 应用）物理挡住，
     * 那一下点击就会被挡在前面的窗口吃掉 —— 而 {@code SetForegroundWindow}
     * 并不保证能把它抬起来。所以置前之后还要用这个把窗口挪到 Z 序最前。
     */
    boolean SetWindowPos(HWND hWnd, HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags);

    /** 移动窗口（不改变大小）。 */
    boolean MoveWindow(HWND hWnd, int X, int Y, int nWidth, int nHeight, boolean bRepaint);

    /** 当前前台窗口句柄。 */
    HWND GetForegroundWindow();

    /** 显示/还原窗口（SW_RESTORE = 9）。 */
    boolean ShowWindow(HWND hWnd, int nCmdShow);

    // SetWindowPos 的 uFlags
    int SWP_NOSIZE = 0x0001;
    int SWP_NOMOVE = 0x0002;
    int SWP_SHOWWINDOW = 0x0040;
}
