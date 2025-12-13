package io.github.baymaxawa.vLobbyConnect;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.network.ProtocolVersion;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModLoaderDetector - 检测玩家使用的模组加载器
 * 支持检测 Forge、Fabric、Quilt、NeoForge 等主流模组加载器
 * 
 * 检测方法：
 * 1. 连接握手阶段检测 (ConnectionHandshakeEvent) - 早期检测，使用@Subscribe(order = PostOrder.EARLY)
 * 2. 插件消息监听 - 最准确的方法
 * 3. 品牌信息分析 - 备用方法
 * 4. 协议版本启发式推断 - 最后手段
 */
public class ModLoaderDetector {
    
    private final ProxyServer server;
    private final Logger logger;
    private final Object plugin; // 插件实例，用于调度器
    private final Map<UUID, String> playerLoaders = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();
    private final Map<UUID, ClientInfo> clientInfo = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> detectionComplete = new ConcurrentHashMap<>();
    
    // 客户端信息类
    private static class ClientInfo {
        String brand = "";
        boolean forgeHandshake = false;
        boolean fabricHandshake = false;
        boolean quiltHandshake = false;
        boolean neoforgeHandshake = false;
        boolean hasCustomPayload = false;
        int pluginMessageCount = 0;
        long firstMessageTime = 0;
        String detectedLoader = "VANILLA";
        
        void addPluginMessage(String channel, String data) {
            pluginMessageCount++;
            if (firstMessageTime == 0) {
                firstMessageTime = System.currentTimeMillis();
            }
            
            if (channel.startsWith("fml:") || channel.startsWith("forge:")) {
                forgeHandshake = true;
                detectedLoader = "FORGE";
            } else if (channel.startsWith("fabric:") || channel.startsWith("quilt:")) {
                if (channel.startsWith("quilt:")) {
                    quiltHandshake = true;
                    detectedLoader = "QUILT";
                } else {
                    fabricHandshake = true;
                    detectedLoader = "FABRIC";
                }
            } else if (channel.startsWith("neoforge:")) {
                neoforgeHandshake = true;
                detectedLoader = "NEOFORGE";
            }
            
            if (!"minecraft:brand".equals(channel) && !"brand".equals(channel)) {
                hasCustomPayload = true;
            }
        }
        
        String analyzeClientType() {
            // 优先级：NeoForge > Forge > Quilt > Fabric > Vanilla
            if (neoforgeHandshake) return "NEOFORGE";
            if (forgeHandshake) return "FORGE";
            if (quiltHandshake) return "QUILT";
            if (fabricHandshake) return "FABRIC";
            
            // 分析品牌信息
            String lowerBrand = brand.toLowerCase();
            if (lowerBrand.contains("neoforge")) return "NEOFORGE";
            if (lowerBrand.contains("forge") && !lowerBrand.contains("neoforge")) return "FORGE";
            if (lowerBrand.contains("quilt")) return "QUILT";
            if (lowerBrand.contains("fabric")) return "FABRIC";
            
            // 基于插件消息数量的启发式检测
            if (pluginMessageCount > 3 && hasCustomPayload) {
                return "FORGE"; // 大量插件消息通常是Forge
            }
            
            return "VANILLA";
        }
    }
    
    // 已知的模组加载器标识符 - 扩展版本
    private static final String[] FORGE_CHANNELS = {
        "fml:handshake", "fml:loginwrapper", "forge:register", "forge:handshake",
        "fml:play", "forge:tie", "fml:login", "forge:modlist", "fml:modlist"
    };
    
    private static final String[] FABRIC_CHANNELS = {
        "fabric:registry", "fabric:resource", "fabric:ping", "fabric:networking",
        "fabric:server:login", "fabric:custom_payload", "fabric:minecraft"
    };
    
    private static final String[] QUILT_CHANNELS = {
        "quilt:registry", "quilt:resource", "quilt:ping", "quilt:networking",
        "quilt:server:login", "quilt:custom_payload", "quilt:minecraft"
    };
    
    private static final String[] NEOFORGE_CHANNELS = {
        "neoforge:register", "neoforge:handshake", "neoforge:networking",
        "neoforge:modlist", "neoforge:config"
    };
    
    public ModLoaderDetector(ProxyServer server, Logger logger, Object plugin) {
        this.server = server;
        this.logger = logger;
        this.plugin = plugin;
    }
    
    /**
     * 监听连接握手事件，进行早期模组加载器检测
     */
    @Subscribe(order = PostOrder.EARLY)
    public void onHandshake(ConnectionHandshakeEvent event) {
        // 在握手阶段，我们无法获取UUID，使用连接的远程地址作为临时标识
        String connectionId = event.getConnection().getRemoteAddress().toString();
        String protocolVersion = event.getConnection().getProtocolVersion().getName();
        
        logger.debug("Handshake detected for connection: {} (protocol: {})", connectionId, protocolVersion);
        
        // 在握手阶段，我们主要进行初步的连接分析
        // 无法存储到playerLoaders中，因为还没有UUID
        // 只能记录握手信息，等待PreLogin事件
        
        try {
            // 基于握手信息进行初步分析
            String initialDetection = performHandshakeAnalysis(event.getConnection());
            
            if (!"VANILLA".equals(initialDetection)) {
                logger.info("Early detection during handshake for {}: {}", connectionId, initialDetection);
            }
            
        } catch (Exception e) {
            logger.warn("Error during handshake analysis for connection: {}", connectionId, e);
        }
    }
    
    /**
     * 执行握手阶段的模组加载器分析
     */
    private String performHandshakeAnalysis(com.velocitypowered.api.proxy.InboundConnection connection) {
        // 获取连接的详细信息
        String protocolVersion = connection.getProtocolVersion().getName();
        
        // 检查连接中是否有特殊的客户端标识
        // 这里可以根据ConnectionHandshakeEvent提供的具体信息进行检测
        
        // 基于协议版本的启发式检测
        if (isModdedProtocol(protocolVersion)) {
            // 对于已知的模组版本，返回VANILLA等待进一步检测
            return "VANILLA";
        }
        
        // 基于连接类型进行初步判断
        if (connection.getVirtualHost().isPresent()) {
            String virtualHost = connection.getVirtualHost().get().getHostString();
            if (containsModdedHostIndicators(virtualHost)) {
                logger.debug("Modded host detected in handshake: {}", virtualHost);
                return "FORGE"; // 默认猜测，可能会被后续检测覆盖
            }
        }
        
        return "VANILLA";
    }
    
    /**
     * 检查协议版本是否可能是模组版本
     */
    private boolean isModdedProtocol(String protocolVersion) {
        // 某些协议版本通常与模组加载器相关
        // 这里可以添加特定的版本检查逻辑
        return false; // 大多数版本都是标准的，除非有特殊情况
    }
    
    /**
     * 检查主机名是否包含模组指示器
     */
    private boolean containsModdedHostIndicators(String hostname) {
        if (hostname == null) return false;
        String lowerHost = hostname.toLowerCase();
        return lowerHost.contains("forge") || lowerHost.contains("fabric") || 
               lowerHost.contains("quilt") || lowerHost.contains("neoforge");
    }
    
    /**
     * 监听玩家预登录事件，初始化检测状态
     */
    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        UUID playerId = event.getUniqueId();
        // 初始化玩家为VANILLA，直到检测到其他加载器
        playerLoaders.put(playerId, "VANILLA");
        logger.debug("Initialized mod loader detection for player: {}", event.getUsername());
    }
    
    /**
     * 监听插件消息以检测模组加载器 - 使用ClientDetectorPlus风格的方法
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getSource();
        String channel = event.getIdentifier().getId();
        UUID playerId = player.getUniqueId();
        
        // 获取或创建客户端信息
        ClientInfo info = clientInfo.computeIfAbsent(playerId, k -> new ClientInfo());
        
        // 记录所有插件消息
        String dataStr = "";
        try {
            dataStr = new String(event.getData());
        } catch (Exception e) {
            dataStr = "[binary data]";
        }
        
        logger.debug("Plugin message from {}: channel={}, data={}", player.getUsername(), channel, dataStr);
        
        // 添加插件消息到客户端信息
        info.addPluginMessage(channel, dataStr);
        
        // 处理品牌信息
        if (channel.equals("minecraft:brand") || channel.equals("brand")) {
            info.brand = dataStr;
            playerBrands.put(playerId, dataStr);
            logger.debug("Brand received from {}: {}", player.getUsername(), dataStr);
        }
        
        // 检测特定的模组加载器通道
        String detectedLoader = detectFromChannel(channel);
        if (!"VANILLA".equals(detectedLoader)) {
            info.detectedLoader = detectedLoader;
            playerLoaders.put(playerId, detectedLoader);
            logger.info("Detected {} for player: {} from channel: {}", detectedLoader, player.getUsername(), channel);
        }
    }
    
    /**
     * 检测特定通道对应的模组加载器
     */
    private String detectFromChannel(String channel) {
        if (isNeoForgeChannel(channel)) return "NEOFORGE";
        if (isForgeChannel(channel)) return "FORGE";
        if (isQuiltChannel(channel)) return "QUILT";
        if (isFabricChannel(channel)) return "FABRIC";
        return "VANILLA";
    }
    
    /**
     * 监听玩家登录完成事件，进行最终检测
     */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // 立即执行检测，并延迟执行更深入的检测
        performFinalDetection(player);
        
        // 延迟执行更深入的检测，给插件消息一些时间到达
        server.getScheduler().buildTask(plugin, () -> {
            performDeepDetection(player);
        }).delay(1, java.util.concurrent.TimeUnit.SECONDS).schedule();
    }
    
    /**
     * 执行最终的模组加载器检测
     */
    private void performFinalDetection(Player player) {
        UUID playerId = player.getUniqueId();
        ClientInfo info = clientInfo.get(playerId);
        
        if (info == null) {
            // 如果没有收到任何插件消息，进行基础检测
            String protocolVersion = player.getProtocolVersion().getName();
            String brand = playerBrands.getOrDefault(playerId, "Unknown");
            String detectedLoader = detectFromBrand(brand);
            
            if ("VANILLA".equals(detectedLoader)) {
                // 基于协议版本进行启发式检测
                detectedLoader = heuristicDetection(protocolVersion, brand);
            }
            
            playerLoaders.put(playerId, detectedLoader);
            logger.debug("Initial detection for {}: {} (protocol: {}, brand: {})", 
                player.getUsername(), detectedLoader, protocolVersion, brand);
            return;
        }
        
        // 分析客户端信息
        String finalLoader = info.analyzeClientType();
        playerLoaders.put(playerId, finalLoader);
        
        logger.debug("Initial detection for {}: {}", player.getUsername(), finalLoader);
    }
    
    /**
     * 执行更深入的检测（延迟1秒后）
     */
    private void performDeepDetection(Player player) {
        UUID playerId = player.getUniqueId();
        ClientInfo info = clientInfo.get(playerId);
        
        String currentLoader = playerLoaders.get(playerId);
        String deepLoader = currentLoader;
        
        // 如果当前是VANILLA，尝试更深入的分析
        if ("VANILLA".equals(currentLoader)) {
            if (info != null) {
                deepLoader = deepClientAnalysis(info, player.getProtocolVersion().getName());
                if (!"VANILLA".equals(deepLoader)) {
                    playerLoaders.put(playerId, deepLoader);
                    logger.info("Deep analysis updated loader for player {}: {} -> {}", 
                        player.getUsername(), currentLoader, deepLoader);
                }
            }
        }
        
        // 记录最终的检测信息
        if (info != null) {
            logger.info("=== Final Client Detection Report for {} ===", player.getUsername());
            logger.info("Final Detected Loader: {}", deepLoader);
            logger.info("Brand: {}", info.brand);
            logger.info("Forge Handshake: {}", info.forgeHandshake);
            logger.info("Fabric Handshake: {}", info.fabricHandshake);
            logger.info("Quilt Handshake: {}", info.quiltHandshake);
            logger.info("NeoForge Handshake: {}", info.neoforgeHandshake);
            logger.info("Plugin Message Count: {}", info.pluginMessageCount);
            logger.info("Has Custom Payload: {}", info.hasCustomPayload);
        } else {
            logger.info("=== Final Client Detection Report for {} ===", player.getUsername());
            logger.info("Final Detected Loader: {} (no plugin messages received)", deepLoader);
        }
        
        detectionComplete.put(playerId, new AtomicBoolean(true));
    }
    
    /**
     * 基于协议版本和品牌信息的启发式检测
     */
    private String heuristicDetection(String protocolVersion, String brand) {
        // 基于协议版本的启发式规则
        if (protocolVersion.startsWith("1.20") || protocolVersion.startsWith("1.19")) {
            String lowerBrand = brand.toLowerCase();
            if (lowerBrand.contains("forge") || lowerBrand.contains("fabric") || 
                lowerBrand.contains("quilt") || lowerBrand.contains("neoforge")) {
                return detectFromBrand(brand);
            }
            
            // 对于这些版本，如果品牌信息不明确，可能是VANILLA
            return "VANILLA";
        }
        
        return "VANILLA";
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
     * 获取玩家的模组加载器 - 使用新的ClientDetectorPlus风格检测
     * @param player 玩家对象
     * @return 模组加载器名称，默认返回 "VANILLA"
     */
    public String getModLoader(Player player) {
        UUID playerId = player.getUniqueId();
        String loader = playerLoaders.get(playerId);
        
        if (loader == null) {
            // 如果没有检测信息，立即进行检测
            ClientInfo info = clientInfo.get(playerId);
            if (info != null) {
                // 如果有客户端信息，立即分析
                loader = info.analyzeClientType();
                playerLoaders.put(playerId, loader);
                logger.debug("Analyzed client info for {}: {}", player.getUsername(), loader);
            } else {
                // 没有客户端信息，使用品牌信息检测
                String brand = playerBrands.getOrDefault(playerId, "");
                if (!brand.isEmpty()) {
                    loader = detectFromBrand(brand);
                } else {
                    // 最后使用协议版本启发式检测
                    loader = heuristicDetection(player.getProtocolVersion().getName(), brand);
                }
                playerLoaders.put(playerId, loader);
                logger.debug("Brand/Version detection for {}: {}", player.getUsername(), loader);
            }
        }
        
        return loader;
    }
    
    /**
     * 获取玩家的模组加载器，等待一小段时间以获得更准确的检测结果
     * @param player 玩家对象
     * @return 模组加载器名称
     */
    public String getModLoaderWithDelay(Player player) {
        // 首先尝试获取当前检测结果
        String loader = getModLoader(player);
        
        UUID playerId = player.getUniqueId();
        AtomicBoolean isComplete = detectionComplete.get(playerId);
        
        // 如果检测未完成且当前为VANILLA，尝试更深入的检测
        if ((isComplete == null || !isComplete.get()) && "VANILLA".equals(loader)) {
            // 分析客户端信息
            ClientInfo info = clientInfo.get(playerId);
            if (info != null) {
                String deepAnalysis = deepClientAnalysis(info, player.getProtocolVersion().getName());
                if (!"VANILLA".equals(deepAnalysis)) {
                    playerLoaders.put(playerId, deepAnalysis);
                    logger.info("Deep analysis updated loader for player {}: {} -> {}", 
                        player.getUsername(), loader, deepAnalysis);
                    return deepAnalysis;
                }
            }
            
            // 最终尝试品牌检测
            String brand = playerBrands.getOrDefault(playerId, "");
            if (!brand.isEmpty()) {
                String brandLoader = detectFromBrand(brand);
                if (!"VANILLA".equals(brandLoader)) {
                    playerLoaders.put(playerId, brandLoader);
                    logger.info("Updated loader {} for player {} from brand: {}", 
                        brandLoader, player.getUsername(), brand);
                    return brandLoader;
                }
            }
        }
        
        // 记录最终检测结果
        logger.debug("Final loader detection for player {}: {}", player.getUsername(), loader);
        return loader;
    }
    
    /**
     * 深度客户端分析 - 类似于ClientDetectorPlus的分析方法
     */
    private String deepClientAnalysis(ClientInfo info, String protocolVersion) {
        // 分析插件消息模式
        if (info.pluginMessageCount > 5) {
            // 大量插件消息通常表示模组客户端
            if (info.neoforgeHandshake) return "NEOFORGE";
            if (info.forgeHandshake) return "FORGE";
            if (info.quiltHandshake) return "QUILT";
            if (info.fabricHandshake) return "FABRIC";
            return "FORGE"; // 默认假设为Forge
        }
        
        // 分析品牌信息的复杂性
        if (info.brand.length() > 20) {
            // 复杂的品牌信息通常表示模组客户端
            String lowerBrand = info.brand.toLowerCase();
            if (lowerBrand.contains("neoforge")) return "NEOFORGE";
            if (lowerBrand.contains("forge")) return "FORGE";
            if (lowerBrand.contains("quilt")) return "QUILT";
            if (lowerBrand.contains("fabric")) return "FABRIC";
        }
        
        // 分析时间模式
        long currentTime = System.currentTimeMillis();
        if (info.firstMessageTime > 0 && (currentTime - info.firstMessageTime) < 1000) {
            // 快速发送的插件消息可能是模组客户端
            return "FORGE";
        }
        
        return "VANILLA";
    }
    
    /**
     * 启发式检测基于协议版本和其他特征
     */
    private String heuristicDetect(Player player, String protocolVersion) {
        // 对于较新的版本，如果玩家使用了特定的端口或连接模式，可能是模组客户端
        if (protocolVersion.startsWith("1.20") || protocolVersion.startsWith("1.19")) {
            // 检查玩家的连接信息
            String remoteAddress = player.getRemoteAddress().toString();
            logger.debug("Heuristic check for {} - version: {}, address: {}", 
                player.getUsername(), protocolVersion, remoteAddress);
            
            // 这里可以添加更多的启发式规则
            // 目前返回VANILLA作为默认值
        }
        return "VANILLA";
    }
    
    /**
     * 移除玩家的模组加载器信息（玩家断开连接时调用）
     */
    public void removePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        String loader = playerLoaders.remove(playerId);
        playerBrands.remove(playerId);
        clientInfo.remove(playerId);
        detectionComplete.remove(playerId);
        
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
        
        // 记录接收到的品牌信息用于调试
        logger.debug("Received brand message: {}", brand);
        
        // 按优先级检测
        if (lowerBrand.contains("neoforge")) {
            logger.debug("Detected NeoForge from brand: {}", brand);
            return "NEOFORGE";
        } else if (lowerBrand.contains("forge") && !lowerBrand.contains("neoforge")) {
            logger.debug("Detected Forge from brand: {}", brand);
            return "FORGE";
        } else if (lowerBrand.contains("fabric")) {
            logger.debug("Detected Fabric from brand: {}", brand);
            return "FABRIC";
        } else if (lowerBrand.contains("quilt")) {
            logger.debug("Detected Quilt from brand: {}", brand);
            return "QUILT";
        } else if (lowerBrand.contains("vanilla")) {
            logger.debug("Detected Vanilla from brand: {}", brand);
            return "VANILLA";
        } else if (lowerBrand.contains("minecraft")) {
            // 如果只是包含 "minecraft" 但没有其他模组信息，可能是VANILLA
            logger.debug("Assuming Vanilla from Minecraft brand: {}", brand);
            return "VANILLA";
        }
        
        // 如果品牌信息不明确，返回VANILLA
        logger.debug("Unknown brand, assuming Vanilla: {}", brand);
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
     * 获取详细的客户端检测信息（用于深度调试）
     */
    public String getClientDetectionDetails(Player player) {
        UUID playerId = player.getUniqueId();
        ClientInfo info = clientInfo.get(playerId);
        
        if (info == null) {
            return "No client info available";
        }
        
        StringBuilder details = new StringBuilder();
        details.append("Brand: ").append(info.brand).append("\n");
        details.append("Forge Handshake: ").append(info.forgeHandshake).append("\n");
        details.append("Fabric Handshake: ").append(info.fabricHandshake).append("\n");
        details.append("Quilt Handshake: ").append(info.quiltHandshake).append("\n");
        details.append("NeoForge Handshake: ").append(info.neoforgeHandshake).append("\n");
        details.append("Plugin Message Count: ").append(info.pluginMessageCount).append("\n");
        details.append("Has Custom Payload: ").append(info.hasCustomPayload).append("\n");
        details.append("Detected Loader: ").append(info.detectedLoader).append("\n");
        details.append("Detection Complete: ").append(
            detectionComplete.getOrDefault(playerId, new AtomicBoolean(false)).get());
        
        return details.toString();
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