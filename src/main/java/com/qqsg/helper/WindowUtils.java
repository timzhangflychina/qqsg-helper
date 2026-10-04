package com.qqsg.helper;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WindowUtils {
    
    // Get all visible windows
    public static List<String> getAllWindows() {
        List<String> windows = new ArrayList<>();
        try {
            // Run PowerShell command to get window list
            ProcessBuilder pb = new ProcessBuilder("powershell", 
                "Get-Process | Where-Object {$_.MainWindowTitle -ne ''} | Format-Table -AutoSize -Property MainWindowTitle");
            Process process = pb.start();
            
            // Read output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean started = false;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    
                    // Skip header lines
                    if (line.contains("MainWindowTitle")) {
                        started = true;
                        continue;
                    }
                    
                    if (started && !line.contains("----")) {
                        windows.add(line);
                    }
                }
            }
            
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return windows;
    }
    
    // Get all visible windows with their process IDs and creation times
    //
    // 入口策略：优先走 JNA EnumWindows（后台/最小化窗口都能枚举到，且不用拉起
    // PowerShell，刷新从 1~3 秒变成毫秒级）；JNA 一旦异常或一个游戏窗口都
    // 没找到（比如以后改了窗口标题规则），再回退旧的 PowerShell 方案兜底。
    public static List<WindowInfo> getAllWindowsWithIds() {
        List<WindowInfo> windows = new ArrayList<>();
        try {
            windows = getAllWindowsWithJNA();
        } catch (Throwable t) {
            System.err.println("JNA enumeration crashed, falling back to PowerShell: " + t);
        }
        if (!windows.isEmpty()) {
            // 枚举顺序是 Z 序，不稳定；按 PID 排一下让下拉框顺序稳定
            windows.sort((w1, w2) -> Integer.compare(w1.getPid(), w2.getPid()));
            System.out.println("Found " + windows.size() + " game windows via JNA (sorted by PID)");
            return windows;
        }
        return getAllWindowsWithIdsLegacy();
    }

    // 旧方案：PowerShell Get-Process MainWindowTitle（只能看到「主窗口」，后台会漏）
    private static List<WindowInfo> getAllWindowsWithIdsLegacy() {
        List<WindowInfo> windows = new ArrayList<>();
        try {
            // PowerShell command to get window list with titles, PIDs, and creation times
            String powerShellCommand = "Get-Process | Where-Object { $_.MainWindowTitle } | Select-Object MainWindowTitle, Id, StartTime | ConvertTo-Json";
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-Command", powerShellCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read PowerShell output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            System.out.println("PowerShell command exited with code: " + exitCode);
            String jsonOutput = output.toString();
            System.out.println("PowerShell output: " + jsonOutput);
            
            System.out.println("Parsing JSON output...");
            
            // 最简单直接的解析方法：按行解析，查找title和ID
            String[] lines = jsonOutput.split("\n");
            String currentTitle = null;
            Integer currentId = null;
            String currentCreationTime = null;
            
            for (String line : lines) {
                line = line.trim();
                
                // 查找标题行 - 寻找包含MainWindowTitle的行
                if (line.contains("MainWindowTitle")) {
                    // 提取引号中的实际标题
                    // 查找第一个和第二个引号的位置
                    int firstQuote = line.indexOf('"');
                    if (firstQuote != -1) {
                        int secondQuote = line.indexOf('"', firstQuote + 1);
                        if (secondQuote != -1) {
                            // 查找第三个引号(实际标题的开始)和第四个引号(实际标题的结束)
                            int thirdQuote = line.indexOf('"', secondQuote + 1);
                            if (thirdQuote != -1) {
                                int fourthQuote = line.indexOf('"', thirdQuote + 1);
                                if (fourthQuote != -1) {
                                    currentTitle = line.substring(thirdQuote + 1, fourthQuote);
                                }
                            }
                        }
                    }
                }
                // 查找ID行
                else if (line.contains("Id")) {
                    // 提取ID值
                    try {
                        // 查找冒号后的内容
                        int colonIndex = line.indexOf(':');
                        if (colonIndex != -1) {
                            // 提取冒号后的部分并去除空格
                            String idPart = line.substring(colonIndex + 1).trim();
                            // 移除所有非数字字符
                            String numericPart = idPart.replaceAll("\\D+", "");
                            if (!numericPart.isEmpty()) {
                                currentId = Integer.parseInt(numericPart);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to parse ID from line: " + line + ", error: " + e.getMessage());
                        currentId = null;
                    }
                }
                
                // 查找创建时间行
                else if (line.contains("StartTime")) {
                    try {
                        // 提取时间值
                        int colonIndex = line.indexOf(':');
                        if (colonIndex != -1) {
                            // 提取冒号后的部分并去除空格和引号
                            String timePart = line.substring(colonIndex + 1).trim();
                            // 去除首尾引号
                            if (timePart.startsWith("\"")) {
                                timePart = timePart.substring(1);
                            }
                            if (timePart.endsWith("\"")) {
                                timePart = timePart.substring(0, timePart.length() - 1);
                            }
                            currentCreationTime = timePart;
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to parse creation time from line: " + line + ", error: " + e.getMessage());
                        currentCreationTime = null;
                    }
                }
                
                // 当找到一个完整的title和ID对时，添加到窗口列表
                if (currentTitle != null && currentId != null) {
                    WindowInfo windowInfo = new WindowInfo(currentTitle, currentId, currentCreationTime);
                    windows.add(windowInfo);
                    System.out.println("Added window: " + currentTitle + " (PID: " + currentId + ") created at: " + currentCreationTime);
                    // 重置以寻找下一个窗口
                    currentTitle = null;
                    currentId = null;
                    currentCreationTime = null;
                }
            }
            
            // 按照进程创建时间排序窗口列表（从早到晚）
            windows.sort((w1, w2) -> {
                if (w1.getCreationTime() == null && w2.getCreationTime() == null) {
                    return 0; // 两者都没有创建时间，保持原顺序
                } else if (w1.getCreationTime() == null) {
                    return 1; // w1没有创建时间，排在后面
                } else if (w2.getCreationTime() == null) {
                    return -1; // w2没有创建时间，排在后面
                } else {
                    return w1.getCreationTime().compareTo(w2.getCreationTime()); // 按创建时间从早到晚排序
                }
            });
            
            System.out.println("Found " + windows.size() + " windows total (sorted by creation time)");
            return windows;
            
        } catch (Exception e) {
            System.err.println("Error getting windows: " + e.getMessage());
            e.printStackTrace();
            return windows;
        }
    }
    
    // Helper method to extract value from simple JSON
    private static String extractValue(String json, String key) {
        // Use a safer approach without escape characters
        String keyPrefix = "\"" + key + "\":\"";
        int startIndex = json.indexOf(keyPrefix);
        
        if (startIndex == -1) {
            // Try without quotes around the value (for numbers)
            keyPrefix = "\"" + key + "\":";
            startIndex = json.indexOf(keyPrefix);
            if (startIndex != -1) {
                int valueStart = startIndex + keyPrefix.length();
                int valueEnd = json.indexOf(',', valueStart);
                if (valueEnd == -1) {
                    valueEnd = json.indexOf('}', valueStart);
                }
                if (valueEnd != -1) {
                    return json.substring(valueStart, valueEnd).trim();
                }
            }
            return null;
        }
        
        int valueStart = startIndex + keyPrefix.length();
        int valueEnd = json.indexOf('\"', valueStart);
        
        if (valueEnd == -1) return null;
        
        return json.substring(valueStart, valueEnd);
    }
    
    // Fallback method using the original Format-Table approach
    private static List<WindowInfo> getAllWindowsWithIdsFallback() {
        List<WindowInfo> windows = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", 
                "Get-Process | Where-Object {$_.MainWindowTitle -ne ''} | Format-Table -AutoSize -Property MainWindowTitle,Id");
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean started = false;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    System.out.println("Processing line: '" + line + "'");
                    
                    if (line.isEmpty()) continue;
                    
                    // Skip header lines
                    if (line.contains("MainWindowTitle") && line.contains("Id")) {
                        started = true;
                        continue;
                    }
                    
                    if (started && !line.contains("----")) {
                        // Try to find the last space that separates title and ID
                        int lastSpaceIndex = -1;
                        for (int i = line.length() - 1; i >= 0; i--) {
                            if (Character.isWhitespace(line.charAt(i))) {
                                // Check if the part after space is a number
                                String potentialId = line.substring(i).trim();
                                try {
                                    Integer.parseInt(potentialId);
                                    lastSpaceIndex = i;
                                    break;
                                } catch (NumberFormatException e) {
                                    // Continue searching
                                }
                            }
                        }
                        
                        if (lastSpaceIndex > 0) {
                            String title = line.substring(0, lastSpaceIndex).trim();
                            String idStr = line.substring(lastSpaceIndex).trim();
                            try {
                                int pid = Integer.parseInt(idStr);
                                WindowInfo windowInfo = new WindowInfo(title, pid);
                                windows.add(windowInfo);
                                System.out.println("Added via fallback: " + windowInfo);
                            } catch (NumberFormatException e) {
                                System.out.println("Couldn't parse PID from: " + idStr);
                                windows.add(new WindowInfo(line, -1));
                            }
                        } else {
                            windows.add(new WindowInfo(line, -1));
                        }
                    }
                }
            }
            
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Fallback method failed: " + e.getMessage());
        }
        
        System.out.println("Fallback method found " + windows.size() + " windows");
        return windows;
    }
    
    // Use JNA to enumerate all windows directly (most reliable method)
    //
    // 为什么刷新按钮必须走这里：PowerShell 的 Get-Process MainWindowTitle 只报每个
    // 进程的「主窗口」，游戏窗口切到后台/最小化后系统常把它标成非主窗口
    // （MainWindowHandle=0），列表里就消失了 —— 这正是「刷新刷不出后台窗口」的根因。
    // EnumWindows 枚举的是所有顶级窗口，与前后台无关，最小化窗口也能枚举到
    // （IsWindowVisible 对最小化窗口仍返回 true）。
    private static List<WindowInfo> getAllWindowsWithJNA() {
        // PID -> 目前分数最高的窗口（同一进程里 Default IME / MSCTFIME UI 等
        // 辅助窗也可能带标题，用 gameWindowScore 只留最像主窗口的那个）
        final java.util.Map<Integer, WindowInfo> bestByPid = new java.util.HashMap<>();
        final java.util.Map<Integer, Integer> bestScoreByPid = new java.util.HashMap<>();

        WindowsAPI.WNDENUMPROC callback = new WindowsAPI.WNDENUMPROC() {
            @Override
            public boolean callback(HWND hWnd, Pointer data) {
                try {
                    // Check if window is visible
                    if (!WindowsAPI.INSTANCE.IsWindowVisible(hWnd)) {
                        return true; // Skip invisible windows
                    }

                    // Get window text
                    int textLength = WindowsAPI.INSTANCE.GetWindowTextLength(hWnd);
                    if (textLength > 0) {
                        char[] buffer = new char[textLength + 1];
                        WindowsAPI.INSTANCE.GetWindowText(hWnd, buffer, buffer.length);
                        String windowText = new String(buffer).trim();

                        // Get process ID
                        int[] windowPid = {0};
                        WindowsAPI.INSTANCE.GetWindowThreadProcessId(hWnd, windowPid);

                        // Only add windows with title and valid PID
                        if (!windowText.isEmpty() && windowPid[0] > 0) {
                            // 只认游戏窗口：类名 QQSGWinClass 或标题含「三国/QQSG」，
                            // 避免把浏览器/聊天窗口一起塞进下拉框。
                            char[] cls = new char[256];
                            WindowsAPI.INSTANCE.GetClassName(hWnd, cls, 256);
                            String className = new String(cls).trim();
                            boolean isGame = className.contains("QQSG")
                                    || windowText.contains("三国")
                                    || windowText.contains("QQSG");
                            if (!isGame) {
                                return true;
                            }

                            int score = gameWindowScore(hWnd, windowText);
                            Integer prevBest = bestScoreByPid.get(windowPid[0]);
                            if (prevBest == null || score > prevBest) {
                                bestScoreByPid.put(windowPid[0], score);
                                bestByPid.put(windowPid[0], new WindowInfo(windowText, windowPid[0]));
                                System.out.println("JNA found game window: " + windowText
                                        + " (PID: " + windowPid[0] + ", class: " + className
                                        + ", score: " + score + ")");
                            }
                        }
                    }

                    // Continue enumeration
                    return true;
                } catch (Exception e) {
                    System.err.println("Error during JNA window enumeration: " + e.getMessage());
                    return true; // Continue even if there's an error
                }
            }
        };

        // Enumerate all windows
        boolean enumSuccess = WindowsAPI.INSTANCE.EnumWindows(callback, null);
        List<WindowInfo> foundWindows = new ArrayList<>(bestByPid.values());
        System.out.println("JNA window enumeration " + (enumSuccess ? "succeeded" : "failed")
                + ", found " + foundWindows.size() + " game windows");

        return foundWindows;
    }
    
    // Get PID from window name (format: "Title (PID: 1234)" or "Title (1) (PID: 1234)")
    public static int getPidFromWindowName(String windowName) {
        if (windowName.contains("(PID: ")) {
            // 使用lastIndexOf确保找到最后一个PID标签，无论标题中是否有其他括号
            int pidStart = windowName.lastIndexOf("(PID: ") + 6;
            int pidEnd = windowName.lastIndexOf(")");
            if (pidStart > 0 && pidEnd > pidStart) {
                try {
                    // 提取PID字符串并移除所有非数字字符，确保能正确解析
                    String pidStr = windowName.substring(pidStart, pidEnd).trim();
                    pidStr = pidStr.replaceAll("\\D+", "");
                    if (!pidStr.isEmpty()) {
                        return Integer.parseInt(pidStr);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse PID from window name: " + windowName);
                }
            }
        }
        return -1;
    }
    
    /**
     * 给窗口打分，挑出「最像游戏主窗口」的那个。
     *
     * <p>同一进程里会有一堆辅助窗（Default IME / MSCTFIME UI / get_offset_wnd 等），
     * 它们也可能带标题。真正的游戏主窗口有名有姓：标题含「QQ三国」、类名是
     * {@code QQSGWinClass}、尺寸也是最大的那个。
     *
     * @return 分数，越高越像主窗口
     */
    private static int gameWindowScore(HWND hWnd, String title) {
        int score = 0;
        if (title != null) {
            if (title.contains("三国")) score += 1000;
            if (title.contains("QQSG") || title.contains("QQ三国")) score += 200;
            if (title.contains("IME") || title.contains("MSCTF")
                    || title.contains("Default") || title.contains("get_offset")
                    || title.contains("BaseWnd")) score -= 500;
        }
        try {
            char[] cls = new char[256];
            WindowsAPI.INSTANCE.GetClassName(hWnd, cls, 256);
            String cn = new String(cls).trim();
            if (cn.contains("QQSG")) score += 800;
            if (cn.contains("SunAwt") || cn.contains("D3DFocus")
                    || cn.contains("Toolkit")) score -= 300;
        } catch (Throwable ignore) {
        }
        try {
            com.sun.jna.platform.win32.WinDef.RECT r =
                    new com.sun.jna.platform.win32.WinDef.RECT();
            if (com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(hWnd, r)) {
                int w = r.right - r.left, h = r.bottom - r.top;
                // 主窗口通常是最大的；给尺寸一点点权重（不会盖过标题/类名的判断）
                if (w > 400 && h > 300) score += 50;
            }
        } catch (Throwable ignore) {
        }
        return score;
    }

    // Find window by process ID using JNA (works for hidden windows)

    /**
     * 恢复最小化的游戏窗口（SW_SHOWNOACTIVATE=4：还原但不抢焦点）。
     *
     * <p>窗口最小化后缩成 160x28 @ (-32000,-32000)，PrintWindow 截图和所有坐标
     * 换算全部失效，后台任务必然失败（09:04 霸王城「寻路面板打不开」就是这个原因）。
     * 任务开始时调用 {@code GameWindowController.ensureWindowVisible()} 触发。
     */
    public static boolean restoreWindow(int pid) {
        HWND hwnd = findWindowByPid(pid);
        if (hwnd == null) {
            return false;
        }
        if (!WindowsAPI.INSTANCE.IsIconic(hwnd)) {
            return true; // 没最小化，无需恢复
        }
        boolean ok = WindowsAPI.INSTANCE.ShowWindow(hwnd, 4);
        System.out.println("[WINDOW UTILS] restoreWindow pid=" + pid
                + " IsIconic=true -> ShowWindow(SW_SHOWNOACTIVATE) ok=" + ok);
        return ok;
    }

    public static HWND findWindowByPid(int pid) {
        try {
            // 注意：本方法会被频繁调用（每次点击/截图都可能调用数次），
            // 原先逐条打印「找到窗口」会把日志刷爆，这里改为静默 + 只输出结论。
            final java.util.List<HWND> foundWindows = new java.util.ArrayList<>();
            final java.util.List<String> titles = new java.util.ArrayList<>();
            final java.util.List<Integer> scores = new java.util.ArrayList<>();
            
            WindowsAPI.WNDENUMPROC callback = new WindowsAPI.WNDENUMPROC() {
                @Override
                public boolean callback(HWND hWnd, Pointer data) {
                    try {
                        // 获取此窗口的进程ID
                        int[] windowPid = {0};
                        WindowsAPI.INSTANCE.GetWindowThreadProcessId(hWnd, windowPid);
                        
                        // 检查是否属于目标进程
                        if (windowPid[0] == pid) {
                            // 获取窗口文本以验证
                            int textLength = WindowsAPI.INSTANCE.GetWindowTextLength(hWnd);
                            String windowText = "";
                            if (textLength > 0) {
                                char[] buffer = new char[textLength + 1];
                                WindowsAPI.INSTANCE.GetWindowText(hWnd, buffer, buffer.length);
                                windowText = new String(buffer).trim();
                            }
                            foundWindows.add(hWnd);
                            titles.add(windowText);
                            scores.add(gameWindowScore(hWnd, windowText));
                        }
                        
                        // 继续枚举所有窗口，不提前停止
                        return true;
                    } catch (Exception e) {
                        System.err.println("Error during window enumeration: " + e.getMessage());
                        return true; // 即使出错也要继续枚举
                    }
                }
            };
            
            // 枚举所有窗口
            boolean enumSuccess = WindowsAPI.INSTANCE.EnumWindows(callback, null);
            
            // 挑「最像游戏主窗口」的那个：
            //   有游戏标题(QQ三国) > 有 QQSG 类名 > 有标题 > 其它。
            // 绝不能只取第一个带标题的 —— 同进程里 "Default IME"/"MSCTFIME UI"
            // 也有标题，而且枚举顺序更靠前，会把主窗口挤掉（曾踩过这个坑）。
            if (!foundWindows.isEmpty()) {
                int best = 0;
                for (int i = 1; i < scores.size(); i++) {
                    if (scores.get(i) > scores.get(best)) {
                        best = i;
                    }
                }
                HWND selectedWindow = foundWindows.get(best);
                String title = titles.get(best);
                System.out.println("[WINDOW] pid=" + pid + " 句柄=" + selectedWindow
                        + " 标题=\"" + title + "\" 评分=" + scores.get(best)
                        + "（共 " + foundWindows.size() + " 个窗口）");
                return selectedWindow;
            }
            
            // 备用方案：尝试使用PowerShell获取主窗口句柄
            try {
                System.out.println("Trying fallback method with PowerShell...");
                ProcessBuilder pb = new ProcessBuilder("powershell", 
                    "Get-Process -Id " + pid + " | Select-Object -ExpandProperty MainWindowHandle");
                pb.redirectErrorStream(true); // 合并标准错误到标准输出
                Process process = pb.start();
                
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line.trim());
                        System.out.println("PowerShell output line: " + line.trim());
                    }
                }
                
                int exitCode = process.waitFor();
                System.out.println("PowerShell command exited with code: " + exitCode);
                
                String handleStr = output.toString();
                System.out.println("PowerShell handle string: '" + handleStr + "'");
                
                if (!handleStr.isEmpty() && !handleStr.equals("0")) {
                    try {
                        long handleValue = Long.parseLong(handleStr);
                        HWND hwnd = new HWND(com.sun.jna.Pointer.createConstant(handleValue));
                        System.out.println("Created handle from PowerShell: " + hwnd);
                        return hwnd;
                    } catch (NumberFormatException e) {
                        System.err.println("Failed to parse handle from PowerShell output: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("Fallback to PowerShell failed: " + e.getMessage());
                e.printStackTrace();
            }
            
        } catch (Exception e) {
            System.err.println("Failed to find window by PID: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.err.println("No window found for PID: " + pid);
        return null;
    }
    
    // Send a key to a process using JNA
    public static boolean sendKeyToProcess(int pid, char key) {
        System.out.println("[WINDOW UTILS] Attempting to send key '" + key + "' to process " + pid);
        
        // 重试机制
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // 尝试查找进程的窗口句柄
                System.out.println("[WINDOW UTILS] Searching for window with process ID: " + pid + ", attempt " + attempt);
                HWND hwnd = findWindowByPid(pid);
                
                if (hwnd == null) {
                    System.err.println("[WINDOW UTILS] ERROR: Could not find window for process ID: " + pid + ", attempt " + attempt);
                    if (attempt < maxRetries) {
                        System.out.println("[WINDOW UTILS] Retrying window lookup...");
                        Thread.sleep(100);
                        continue;
                    }
                    return false;
                }
                
                System.out.println("[WINDOW UTILS] Found window handle: " + hwnd + " for process ID: " + pid);
                
                // 检查窗口是否可见
                boolean isVisible = WindowsAPI.INSTANCE.IsWindowVisible(hwnd);
                System.out.println("[WINDOW UTILS] Window visibility check: " + isVisible);
                
                // 获取虚拟键码
                System.out.println("[WINDOW UTILS] Converting character to virtual key code: " + key);
                int vkCode = charToVirtualKey(key);
                System.out.println("[WINDOW UTILS] Virtual key code for " + key + " is: " + vkCode);
                
                if (vkCode == 0) {
                    System.err.println("[WINDOW UTILS] ERROR: Unsupported key character: " + key);
                    return false;
                }
                
                // 改进的lParam参数计算 - 提供更完整的键盘状态信息
                int scanCode = 0; // 简化处理，实际应用中可能需要获取真实的扫描码
                int extendedKey = (vkCode == 0x5B || vkCode == 0x5C) ? 1 : 0; // Windows键是扩展键
                long lParamDown = (scanCode << 16) | (extendedKey << 24);
                long lParamUp = (scanCode << 16) | (extendedKey << 24) | (1 << 30); // 前一个键状态设为1
                
                // 发送按键消息 - 使用更准确的lParam参数
                System.out.println("[WINDOW UTILS] Sending WM_KEYDOWN message for key: " + key + " (VK: " + vkCode + ", lParam: " + lParamDown + ")");
                boolean keydownResult = WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_KEYDOWN, vkCode, (int)lParamDown);
                System.out.println("[WINDOW UTILS] WM_KEYDOWN message result: " + keydownResult);
                
                if (!keydownResult) {
                    System.err.println("[WINDOW UTILS] ERROR: Failed to send WM_KEYDOWN message, attempt " + attempt);
                    if (attempt < maxRetries) {
                        System.out.println("[WINDOW UTILS] Retrying key down...");
                        Thread.sleep(100);
                        continue;
                    }
                    return false;
                }
                
                // 增加延迟时间以确保游戏有足够时间响应按键
                System.out.println("[WINDOW UTILS] Waiting 100ms between keydown and keyup events");
                Thread.sleep(100);  // 增加延迟时间
                
                System.out.println("[WINDOW UTILS] Sending WM_KEYUP message for key: " + key + " (VK: " + vkCode + ", lParam: " + lParamUp + ")");
                boolean keyupResult = WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_KEYUP, vkCode, (int)lParamUp);
                System.out.println("[WINDOW UTILS] WM_KEYUP message result: " + keyupResult);
                
                if (!keyupResult) {
                    System.err.println("[WINDOW UTILS] ERROR: Failed to send WM_KEYUP message, attempt " + attempt);
                    if (attempt < maxRetries) {
                        System.out.println("[WINDOW UTILS] Retrying key up...");
                        Thread.sleep(100);
                        continue;
                    }
                    return false;
                }
                
                // 再增加一个小延迟确保操作完成
                Thread.sleep(20);
                
                // 只有两个消息都成功发送才返回成功
                boolean success = keydownResult && keyupResult;
                System.out.println("[WINDOW UTILS] Key send SUCCESS: Key: " + key + " to process: " + pid + " (attempt " + attempt + ")");
                
                return success;
            } catch (Exception e) {
                System.err.println("[WINDOW UTILS] ERROR: Exception during key send attempt " + attempt + ": " + e.getMessage());
                if (attempt < maxRetries) {
                    System.out.println("[WINDOW UTILS] Retrying after exception...");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    System.err.println("[WINDOW UTILS] All retry attempts failed");
                    System.err.println("[WINDOW UTILS] Stack trace:");
                    e.printStackTrace();
                }
            }
        }
        
        System.err.println("[WINDOW UTILS] Failed to send key after " + maxRetries + " attempts");
        return false;
    }
    
    // Convert a character to virtual key code
    private static int charToVirtualKey(char key) {
        switch (Character.toUpperCase(key)) {
            case 'A': return 0x41; // VK_A
            case 'S': return 0x53; // VK_S
            case 'D': return 0x44; // VK_D
            case 'F': return 0x46; // VK_F
            case 'Q': return 0x51; // VK_Q
            case 'W': return 0x57; // VK_W
            case 'E': return 0x45; // VK_E
            case 'R': return 0x52; // VK_R
            case 'C': return 0x43; // VK_C
            case 'O': return 0x4F; // VK_O  —— 打开军团界面
            case 'G': return VK_G; // VK_G  —— 与 NPC 对话
            // 注意：故意不映射空格。原代码 testControlFunctionality() / releaseAllKeys()
            // 都会尝试发空格键，映射后会让「Start」按钮真的向游戏发一次空格，属行为回归。
            default: return 0; // Invalid key
        }
    }

    // ==================== 常用虚拟键码 ====================

    public static final int VK_RETURN = 0x0D; // Enter
    public static final int VK_ESCAPE = 0x1B; // ESC
    public static final int VK_SPACE  = 0x20; // 空格
    public static final int VK_G      = 0x47; // G
    public static final int VK_O      = 0x4F; // O
    public static final int VK_T      = 0x54; // T  —— 回城
    public static final int VK_F5     = 0x74; // F5  —— 任务面板
    public static final int VK_F11    = 0x7A; // F11
    public static final int VK_BACK   = 0x08; // Backspace —— 清空输入框
    public static final int VK_END    = 0x23; // End       —— 光标移到末尾
    public static final int VK_UP     = 0x26; // ↑         —— 孝廉：换图 / 上移选项
    public static final int VK_DOWN   = 0x28; // ↓         —— 孝廉：下移选项
    public static final int VK_LEFT   = 0x25; // ←
    public static final int VK_RIGHT  = 0x27; // →

    /**
     * 直接按虚拟键码向指定 PID 的窗口投递按键消息（WM_KEYDOWN + WM_KEYUP）。
     *
     * <p>与 {@link #sendKeyToProcess(int, char)} 的区别：本方法支持 Enter / ESC / F11 等
     * 非常规字符键，供军团任务等流程使用。走的是窗口消息，不依赖窗口是否在前台。
     *
     * @return 两条消息都投递成功才返回 true
     */
    public static boolean sendVkToProcess(int pid, int vk) {
        if (pid <= 0 || vk <= 0) return false;

        HWND hwnd = findWindowByPid(pid);
        if (hwnd == null) {
            System.err.println("[WINDOW UTILS] sendVkToProcess: 未找到 PID " + pid + " 的窗口");
            return false;
        }

        // lParam 取值与已验证可用的 sendKeyToProcess 保持一致：
        // keydown=0，keyup 置位 bit30（上一状态为按下）
        boolean down = WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_KEYDOWN, vk, 0);
        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean up = WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_KEYUP, vk, 0x40000000);

        System.out.println("[WINDOW UTILS] sendVkToProcess pid=" + pid + " vk=0x"
                + Integer.toHexString(vk) + " down=" + down + " up=" + up);
        return down && up;
    }

    /**
     * 给指定 PID 的窗口投递一条 WM_CHAR（后台输入可打印字符用）。
     *
     * <p>为什么不用按键消息：实测这个游戏会把一条 PostMessage 的 WM_KEYDOWN+WM_KEYUP
     * 翻译成<b>两个</b>字符（编辑框里打 '7' 变 "77"、打 '1' 变 "11" 占满长度上限，
     * 后面的位直接被吞）。WM_CHAR 一条消息就是<b>一个</b>字符，从根上杜绝翻倍。
     */
    public static boolean sendCharToProcess(int pid, int ch) {
        if (pid <= 0 || ch <= 0) return false;

        HWND hwnd = findWindowByPid(pid);
        if (hwnd == null) {
            System.err.println("[WINDOW UTILS] sendCharToProcess: 未找到 PID " + pid + " 的窗口");
            return false;
        }

        boolean ok = WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_CHAR, ch, 0);
        System.out.println("[WINDOW UTILS] sendCharToProcess pid=" + pid + " ch=0x"
                + Integer.toHexString(ch) + " ok=" + ok);
        return ok;
    }
}