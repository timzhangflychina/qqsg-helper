package com.qqsg.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QQSGWindowNameExtractor {
    
    public static void main(String[] args) {
        System.out.println("=== QQ三国窗口名称提取工具 ===");
        System.out.println("正在获取窗口列表...");
        
        try {
            // 获取所有窗口信息
            List<WindowInfo> allWindows = WindowUtils.getAllWindowsWithIds();
            List<String> qqsgWindows = new ArrayList<>();
            
            System.out.println("\n找到的所有窗口:");
            for (WindowInfo window : allWindows) {
                String title = window.getTitle();
                System.out.println("- " + title + " (PID: " + window.getPid() + ")");
                
                // 过滤可能是QQ三国的窗口（包含相关关键词）
                if (title.contains("三国") || title.contains("QQSG") || title.contains("QQ三国")) {
                    qqsgWindows.add(title);
                }
            }
            
            System.out.println("\n提取的QQ三国窗口名称（仅中文字符）:");
            boolean hasChineseOnly = false;
            int windowNumber = 1;
            for (String windowTitle : qqsgWindows) {
                String chineseOnly = extractChineseCharacters(windowTitle);
                if (!chineseOnly.isEmpty()) {
                    System.out.println(windowNumber + ". " + chineseOnly);
                    hasChineseOnly = true;
                    windowNumber++;
                }
            }
            
            if (!hasChineseOnly) {
                System.out.println("- 未提取到中文字符");
            }
            
            // 如果没有找到明确的QQ三国窗口，提取所有窗口的中文字符
            if (qqsgWindows.isEmpty()) {
                System.out.println("\n未找到明确的QQ三国窗口，提取所有窗口的中文字符:");
                for (WindowInfo window : allWindows) {
                    String chineseOnly = extractChineseCharacters(window.getTitle());
                    if (!chineseOnly.isEmpty()) {
                        System.out.println("- " + chineseOnly);
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("处理窗口时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // 提取字符串中的中文服务器名称和线路编号
    private static String extractChineseCharacters(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        
        // 针对QQ三国窗口标题的特殊处理
        // 格式通常为: QQ三国1.0Beta82Build80 三足鼎立 8线
        if (input.contains("三国")) {
            // 尝试直接提取服务器名称和线路号
            try {
                // 1. 提取服务器名称
                Pattern serverPattern = Pattern.compile("[\\u4e00-\\u9fa5]+三足鼎立[\\u4e00-\\u9fa5]*");
                Matcher serverMatcher = serverPattern.matcher(input);
                String serverName = "三国三足鼎立";
                
                if (serverMatcher.find()) {
                    serverName = serverMatcher.group();
                }
                
                // 2. 提取线路号
                Pattern linePattern = Pattern.compile("(\\d+)线");
                Matcher lineMatcher = linePattern.matcher(input);
                String lineNumber = "";
                
                if (lineMatcher.find()) {
                    lineNumber = lineMatcher.group(1) + "线";
                }
                
                // 3. 组合结果
                return serverName + lineNumber;
            } catch (Exception e) {
                // 如果上述方法失败，使用更简单的方法
                // 直接提取所有中文字符和线路号
                StringBuilder result = new StringBuilder();
                
                // 提取中文字符
                Pattern chinesePattern = Pattern.compile("[\\u4e00-\\u9fa5]+");
                Matcher chineseMatcher = chinesePattern.matcher(input);
                
                boolean foundSanGuo = false;
                while (chineseMatcher.find()) {
                    String match = chineseMatcher.group();
                    if (match.contains("三国")) {
                        result.append("三国");
                        foundSanGuo = true;
                    } else if (foundSanGuo) {
                        result.append(match);
                    }
                }
                
                // 提取线路号
                Pattern linePattern = Pattern.compile("(\\d+)线");
                Matcher lineMatcher = linePattern.matcher(input);
                if (lineMatcher.find()) {
                    result.append(lineMatcher.group(1)).append("线");
                }
                
                return result.toString();
            }
        }
        
        // 回退方案：只提取中文字符
        return extractChineseOnly(input);
    }
    
    // 辅助方法：只提取中文字符
    private static String extractChineseOnly(String input) {
        Pattern pattern = Pattern.compile("[\\u4e00-\\u9fa5]+");
        Matcher matcher = pattern.matcher(input);
        
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            result.append(matcher.group());
        }
        
        return result.toString().trim();
    }
}