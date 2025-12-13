package io.github.baymaxawa.vLobbyConnect;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModLoaderDetector - 检测玩家使用的模组加载器
 * 支持检测 Forge、Fabric、Quilt、NeoForge 等主流模组加载器
 * 
 * 检测方法：
 * 1. 插件消息监听 - 最准确的方法
 * 2. 品牌信息分析 - 备用方法
 * 3. 协议版本启发式推断 - 最后手段
 */
public class ModLoaderDetector {
    
    private final ProxyServer server;
    private final Logger logger;
    private final Map<UUID, String> playerLoaders = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();
    
    // 已知的模组加载器标识符
    private static final String[] FORGE_CHANNELS = {
        "fml:handshake", "fml:loginwrapper", "forge:register", "forge:handshake"
    };
    
    private static final String[] FABRIC_CHANNELS = {
        "fabric:registry", "fabric:resource", "fabric:ping"
    };
    
    private static final String[] QUILT_CHANNELS = {
        "quilt:registry", "quilt:resource", "quilt:ping"
    };
    
    private static final String[] NEOFORGE_CHANNELS = {
        "neoforge:register", "neoforge:handshake"
    };
    
    public ModLoaderDetector(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }
    
    /**
     * 监听玩家预登录事件，初始化检测状态
     */
    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        UUID playerId = event.getUniqueId();
        // 初始化玩家为VANILLA，直到检测到其他加载器
        playerLoaders.put(playerId, "VANILLA");
    }
    
    /**
     * 监听插件消息以检测模组加载器
     * 这是最准确的检测方法
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getSource();
        String channel = event.getIdentifier().getId();
        UUID playerId = player.getUniqueId();
        
        // 如果已经检测到非VANILLA加载器，跳过
        String currentLoader = playerLoaders.get(playerId);
        if (currentLoader != null && !"VANILLA".equals(currentLoader)) {
            return;
        }
        
        // 检测各种模组加载器的特征
        if (isForgeChannel(channel)) {
            playerLoaders.put(playerId, "FORGE");
            logger.info("Detected Forge for player: {} from channel: {}", player.getUsername(), channel);
            return;
        }
        
        if (isFabricChannel(channel)) {
            playerLoaders.put(playerId, "FABRIC");
            logger.info("Detected Fabric for player: {} from channel: {}", player.getUsername(), channel);
            return;
        }
        
        if (isQuiltChannel(channel)) {
            playerLoaders.put(playerId, "QUILT");
            logger.info("Detected Quilt for player: {} from channel: {}", player.getUsername(), channel);
            return;
        }
        
        if (isNeoForgeChannel(channel)) {
            playerLoaders.put(playerId, "NEOFORGE");
            logger.info("Detected NeoForge for player: {} from channel: {}", player.getUsername(), channel);
            return;
        }
        
        // 检测品牌信息
        if (channel.equals("minecraft:brand") || channel.equals("brand")) {
            try {
                String brand = new String(event.getData());
                playerBrands.put(playerId, brand);
                String detectedLoader = detectFromBrand(brand);
                if (!"VANILLA".equals(detectedLoader)) {
                    playerLoaders.put(playerId, detectedLoader);
                    logger.info("Detected {} for player: {} from brand: {}", detectedLoader, player.getUsername(), brand);
                }
            } catch (Exception e) {
                logger.debug("Failed to parse brand message for player: {}", player.getUsername(), e);
            }
        }
    }
    
    private boolean isForgeChannel(String channel) {
        for (String forgeChannel : FORGE_CHANNELS) {
            if (channel.startsWith(forgeChannel)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isFabricChannel(String channel) {
        for (String fabricChannel : FABRIC_CHANNELS) {
            if (channel.startsWith(fabricChannel)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isQuiltChannel(String channel) {
        for (String quiltChannel : QUILT_CHANNELS) {
            if (channel.startsWith(quiltChannel)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isNeoForgeChannel(String channel) {
        for (String neoforgeChannel : NEOFORGE_CHANNELS) {
            if (channel.startsWith(neoforgeChannel)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取玩家的模组加载器
     * @param player 玩家对象
     * @return 模组加载器名称，默认返回 "VANILLA"
     */
    public String getModLoader(Player player) {
        UUID playerId = player.getUniqueId();
        String loader = playerLoaders.get(playerId);
        
        if (loader == null) {
            // 如果没有检测信息，尝试基于版本推断
            String protocolVersion = player.getProtocolVersion().getName();
            loader = detectFromVersion(protocolVersion);
            playerLoaders.put(playerId, loader);
            logger.debug("Inferred loader {} for player {} based on version {}", 
                loader, player.getUsername(), protocolVersion);
        }
        
        return loader;
    }
    
    /**
     * 移除玩家的模组加载器信息（玩家断开连接时调用）
     */
    public void removePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        String loader = playerLoaders.remove(playerId);
        playerBrands.remove(playerId);
        logger.debug("Removed mod loader info for player: {} (was: {})", player.getUsername(), loader);
    }
    
    /**
     * 基于品牌信息检测模组加载器
     * 这是一个备用检测方法
     */
    public String detectFromBrand(String brand) {
        if (brand == null || brand.isEmpty()) {
            return "VANILLA";
        }
        
        String lowerBrand = brand.toLowerCase();
        
        // 按优先级检测
        if (lowerBrand.contains("neoforge")) {
            return "NEOFORGE";
        } else if (lowerBrand.contains("forge") && !lowerBrand.contains("neoforge")) {
            return "FORGE";
        } else if (lowerBrand.contains("fabric")) {
            return "FABRIC";
        } else if (lowerBrand.contains("quilt")) {
            return "QUILT";
        } else if (lowerBrand.contains("vanilla")) {
            return "VANILLA";
        }
        
        // 如果品牌信息不明确，返回VANILLA
        return "VANILLA";
    }
    
    /**
     * 基于协议版本检测可能的模组加载器
     * 这是一个启发式方法，提供基本推断
     */
    public String detectFromVersion(String protocolVersion) {
        if (protocolVersion == null) {
            return "VANILLA";
        }
        
        // 某些版本通常与特定的模组加载器生态更相关
        if (protocolVersion.startsWith("1.20.1") || 
            protocolVersion.startsWith("1.19.2") ||
            protocolVersion.startsWith("1.18.2")) {
            // 这些版本有活跃的模组社区，但我们需要实际检测
            return "VANILLA"; // 保持默认，等待实际检测
        }
        
        return "VANILLA";
    }
    
    /**
     * 获取玩家的品牌信息（用于调试）
     */
    public String getPlayerBrand(Player player) {
        return playerBrands.getOrDefault(player.getUniqueId(), "Unknown");
    }
    
    /**
     * 获取当前检测统计信息
     */
    public Map<String, Integer> getDetectionStats() {
        Map<String, Integer> stats = new java.util.HashMap<>();
        for (String loader : playerLoaders.values()) {
            stats.put(loader, stats.getOrDefault(loader, 0) + 1);
        }
        return stats;
    }
}