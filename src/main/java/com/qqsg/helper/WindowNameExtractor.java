package com.qqsg.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WindowNameExtractor {
    
    public static String extractChineseCharacters(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        
        Pattern pattern = Pattern.compile("[\u4e00-\u9fa5]+");
        Matcher matcher = pattern.matcher(input);
        
        StringBuilder chineseChars = new StringBuilder();
        while (matcher.find()) {
            chineseChars.append(matcher.group());
        }
        
        return chineseChars.toString();
    }
    
    public static List<String> getWindowNamesWithChineseOnly() {
        List<String> chineseWindowNames = new ArrayList<>();
        
        try {
            List<WindowInfo> windows = WindowUtils.getAllWindowsWithIds();
            
            System.out.println("Original window names found:");
            for (WindowInfo window : windows) {
                String originalTitle = window.getTitle();
                System.out.println("- " + originalTitle);
                
                String chineseOnly = extractChineseCharacters(originalTitle);
                if (!chineseOnly.isEmpty()) {
                    chineseWindowNames.add(chineseOnly);
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error getting window names: " + e.getMessage());
            e.printStackTrace();
        }
        
        return chineseWindowNames;
    }
    
    public static void main(String[] args) {
        List<String> chineseWindowNames = getWindowNamesWithChineseOnly();
        
        System.out.println("\nWindow names with Chinese characters only:");
        for (String name : chineseWindowNames) {
            System.out.println("- " + name);
        }
    }
}