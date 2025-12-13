package io.github.baymaxawa.vLobbyConnect;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * UpdateChecker - 检查GitHub上的插件更新
 */
public class UpdateChecker {
    
    private static final String GITHUB_API_URL = "https://api.github.com/repos/Aruvelut-123/vServerConnect/releases/latest";
    private static final String DOWNLOAD_URL = "https://github.com/Aruvelut-123/vServerConnect/releases/latest";
    
    private final ProxyServer server;
    private final Logger logger;
    private final Gson gson = new Gson();
    private boolean updateCheckEnabled;
    private long checkIntervalHours;
    private String lastVersion;
    private String latestVersion;
    private boolean updateAvailable;
    
    public UpdateChecker(ProxyServer server, Logger logger, boolean updateCheckEnabled, long checkIntervalHours) {
        this.server = server;
        this.logger = logger;
        this.updateCheckEnabled = updateCheckEnabled;
        this.checkIntervalHours = checkIntervalHours;
        this.lastVersion = Constants.VERSION;
        this.updateAvailable = false;
    }
    
    /**
     * 启动定期更新检查
     */
    public void startUpdateCheck(Object plugin) {
        if (!updateCheckEnabled) {
            logger.info("Update checking is disabled in configuration.");
            return;
        }
        
        logger.info("Starting update checker for vServerConnect (current version: {})", lastVersion);
        
        // 立即检查一次
        checkForUpdates();
        
        // 设置定期检查
        server.getScheduler().buildTask(plugin, this::checkForUpdates)
            .repeat(checkIntervalHours, TimeUnit.HOURS)
            .schedule();
    }
    
    /**
     * 检查更新（异步执行）
     */
    public CompletableFuture<Boolean> checkForUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("Checking for updates from GitHub...");
                
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "vServerConnect-UpdateChecker");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    try (InputStream inputStream = connection.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                        
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        
                        JsonObject release = gson.fromJson(response.toString(), JsonObject.class);
                        latestVersion = release.get("tag_name").getAsString();
                        
                        // 移除版本号前的 'v' 前缀（如果存在）
                        if (latestVersion.startsWith("v")) {
                            latestVersion = latestVersion.substring(1);
                        }
                        
                        // 比较版本号
                        updateAvailable = compareVersions(latestVersion, lastVersion) > 0;
                        
                        if (updateAvailable) {
                            logger.warn("New version available: {} (current: {})", latestVersion, lastVersion);
                            logger.warn("Download at: {}", DOWNLOAD_URL);
                            notifyAdministrators();
                        } else {
                            logger.debug("No updates available. Current version: {}", lastVersion);
                        }
                        
                        return updateAvailable;
                    }
                } else {
                    logger.warn("Failed to check for updates. HTTP response code: {}", responseCode);
                    return false;
                }
            } catch (IOException e) {
                logger.warn("Failed to check for updates", e);
                return false;
            }
        });
    }
    
    /**
     * 通知管理员有可用更新
     */
    private void notifyAdministrators() {
        String updateMessage = String.format(
            "§e[vServerConnect] §6New version available: §a%s§6 (current: §c%s§6)\n" +
            "§e[vServerConnect] §6Download at: §b%s",
            latestVersion, lastVersion, DOWNLOAD_URL
        );
        
        server.getAllPlayers().stream()
            .filter(player -> player.hasPermission("vserverconnect.admin"))
            .forEach(player -> player.sendMessage(net.kyori.adventure.text.Component.text(updateMessage)));
        
        // 同时在控制台输出
        logger.warn("=== UPDATE AVAILABLE ===");
        logger.warn("New version: {} (current: {})", latestVersion, lastVersion);
        logger.warn("Download at: {}", DOWNLOAD_URL);
        logger.warn("========================");
    }
    
    /**
     * 手动检查更新
     */
    public void checkUpdatesManually(com.velocitypowered.api.command.CommandSource source) {
        if (!updateCheckEnabled) {
            source.sendMessage(net.kyori.adventure.text.Component.text(
                "§cUpdate checking is disabled in configuration.", 
                net.kyori.adventure.text.format.NamedTextColor.RED
            ));
            return;
        }
        
        source.sendMessage(net.kyori.adventure.text.Component.text(
            "§eChecking for updates...", 
            net.kyori.adventure.text.format.NamedTextColor.YELLOW
        ));
        
        checkForUpdates().thenAccept(hasUpdate -> {
            if (hasUpdate) {
                source.sendMessage(net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.Component.text("§aUpdate available! ", net.kyori.adventure.text.format.NamedTextColor.GREEN))
                    .append(net.kyori.adventure.text.Component.text("§6Version: §e" + latestVersion, net.kyori.adventure.text.format.NamedTextColor.GOLD))
                    .append(net.kyori.adventure.text.Component.text("§6 (current: §c" + lastVersion + "§6)", net.kyori.adventure.text.format.NamedTextColor.RED))
                    .build()
                );
                source.sendMessage(net.kyori.adventure.text.Component.text(
                    "§6Download at: §b" + DOWNLOAD_URL, 
                    net.kyori.adventure.text.format.NamedTextColor.BLUE
                ));
            } else {
                source.sendMessage(net.kyori.adventure.text.Component.text(
                    "§aYou are running the latest version: §e" + lastVersion, 
                    net.kyori.adventure.text.format.NamedTextColor.GREEN
                ));
            }
        });
    }
    
    /**
     * 比较版本号
     * @return 1 if v1 > v2, 0 if equal, -1 if v1 < v2
     */
    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLength = Math.max(parts1.length, parts2.length);
        
        for (int i = 0; i < maxLength; i++) {
            int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        
        return 0;
    }
    
    /**
     * 获取更新状态信息
     */
    public String getUpdateStatus() {
        if (!updateCheckEnabled) {
            return "Update checking disabled";
        }
        
        if (updateAvailable) {
            return String.format("Update available: %s (current: %s)", latestVersion, lastVersion);
        }
        
        return String.format("Up to date: %s", lastVersion);
    }
    
    /**
     * 获取最新版本号
     */
    public String getLatestVersion() {
        return latestVersion;
    }
    
    /**
     * 获取当前版本号
     */
    public String getCurrentVersion() {
        return lastVersion;
    }
    
    /**
     * 检查是否有可用更新
     */
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }
    
    /**
     * 设置更新检查开关
     */
    public void setUpdateCheckEnabled(boolean enabled) {
        this.updateCheckEnabled = enabled;
        logger.info("Update checking {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * 设置检查间隔
     */
    public void setCheckInterval(long hours) {
        this.checkIntervalHours = hours;
        logger.info("Update check interval set to {} hours", hours);
    }
}