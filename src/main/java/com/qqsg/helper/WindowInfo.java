package com.qqsg.helper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class WindowInfo {
    private String title;
    private int pid;
    private LocalDateTime creationTime;
    
    public WindowInfo(String title, int pid) {
        this.title = title;
        this.pid = pid;
        this.creationTime = null; // 默认值为null
    }
    
    public WindowInfo(String title, int pid, String creationTimeStr) {
        this.title = title;
        this.pid = pid;
        try {
            // PowerShell返回的时间格式通常为"yyyy-MM-dd HH:mm:ss"或类似格式
            // 尝试几种可能的格式
            DateTimeFormatter[] formatters = {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
            };
            
            for (DateTimeFormatter formatter : formatters) {
                try {
                    this.creationTime = LocalDateTime.parse(creationTimeStr, formatter);
                    break;
                } catch (DateTimeParseException e) {
                    // 尝试下一个格式
                }
            }
            
            // 如果所有格式都失败，设置为null
            if (this.creationTime == null) {
                System.err.println("Failed to parse creation time: " + creationTimeStr);
            }
        } catch (Exception e) {
            System.err.println("Error parsing creation time: " + e.getMessage());
            this.creationTime = null;
        }
    }
    
    public String getTitle() {
        return title;
    }
    
    public int getPid() {
        return pid;
    }
    
    public LocalDateTime getCreationTime() {
        return creationTime;
    }
    
    public void setCreationTime(LocalDateTime creationTime) {
        this.creationTime = creationTime;
    }
    
    @Override
    public String toString() {
        if (pid > 0) {
            return title + " (PID: " + pid + ")";
        }
        return title;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        WindowInfo that = (WindowInfo) obj;
        return pid == that.pid && title.equals(that.title);
    }
    
    @Override
    public int hashCode() {
        return java.util.Objects.hash(title, pid);
    }
}