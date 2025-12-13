package io.github.baymaxawa.vLobbyConnect;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;

import net.kyori.adventure.text.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Plugin(
	id = "vserverconnect",
	name = "vServerConnect",
	url = "https://github.com/Aruvelut-123/vServerConnect/",
	description = "A Velocity Plugin for Server Connection",
	version = Constants.VERSION,
	authors = { "Baymaxawa", "kmaba" }
)
public final class VelocityPlugin {
	@Inject
	private Logger logger;

	@Inject
	private com.velocitypowered.api.proxy.ProxyServer server;

	@Inject
	private Metrics.Factory metricsFactory;

	private final Map<String, List<RegisteredServer>> Servers = new HashMap<>();
	private final Map<UUID, Integer> connectionAttempts = new ConcurrentHashMap<>();
	private ModLoaderDetector modLoaderDetector;
	private UpdateChecker updateChecker;

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		int pluginId = 24615;
		Metrics metrics = metricsFactory.make(this, pluginId);
		
		// 初始化模组加载器检测器
		modLoaderDetector = new ModLoaderDetector(server, logger);
		server.getEventManager().register(this, modLoaderDetector);

		// 读取配置文件
		boolean updateCheckEnabled = true;
		long checkIntervalHours = 6;
		Map<String, Object> config = null;
		
		try {
			// Load the config.yml file
			Yaml yaml = new Yaml();
			File configFile = new File("plugins/vServerConnect/config.yml");
			if (!configFile.exists()) {
				configFile.getParentFile().mkdirs();
				Files.copy(getClass().getResourceAsStream("/config.yml"), configFile.toPath());
			}

			// Parse the config.yml file
			config = yaml.load(Files.newInputStream(configFile.toPath()));
			
			Map<String, Object> updateCheckConfig = (Map<String, Object>) config.get("update-check");
			if (updateCheckConfig != null) {
				updateCheckEnabled = Boolean.TRUE.equals(updateCheckConfig.get("enabled"));
				Object interval = updateCheckConfig.get("check-interval-hours");
				if (interval instanceof Number) {
					checkIntervalHours = ((Number) interval).longValue();
					if (checkIntervalHours < 1) checkIntervalHours = 1;
				}
			}
		} catch (Exception e) {
			logger.warn("Failed to load update check configuration, using defaults", e);
		}
		
		// 初始化更新检查器
		updateChecker = new UpdateChecker(server, logger, updateCheckEnabled, checkIntervalHours);
		updateChecker.startUpdateCheck(this);

        Map<String, String> servers = null;
        if (config != null) {
            servers = (Map<String, String>) config.get("servers");
        }
        if (servers == null) {
            logger.error("Failed to load server settings.");
            return;
        }

        // Validate and log the configuration
        // 正则表达式分组解释：
        // group(1): 完整的VIA前缀（如果有），如"VIA-"
        // group(2): 仅"VIA"（如果有）
        // group(3): 版本号加横杠（如果有），如"1.20-"
        // group(4): 模组加载器，如"VANILLA"
        // group(5): 末尾编号（如果有），如"1"
        Pattern pattern = Pattern.compile("^((VIA)-)?((?:\\d+(?:\\.\\d+)*)-)?([A-Z]+)(?:-(\\d+))?$");

        for (Map.Entry<String, String> entry : servers.entrySet()) {
            String configKey = entry.getKey();
            Matcher matcher = pattern.matcher(configKey);

            if (matcher.matches()) {
                // 提取分组信息
                String viaPrefix = matcher.group(1);  // 完整的VIA前缀，如"VIA-"或null
                String via = matcher.group(2);         // 仅"VIA"或null
                String versionWithDash = matcher.group(3); // 版本号加横杠，如"1.20-"或null
                String loader = matcher.group(4);     // 模组加载器，如"VANILLA"
                String number = matcher.group(5);      // 末尾编号，如"1"或null

                // 清理版本号：去掉末尾的横杠
                String version = (versionWithDash != null && versionWithDash.endsWith("-"))
                        ? versionWithDash.substring(0, versionWithDash.length() - 1)
                        : null;

                // 如果有VIA前缀，但版本号存在，这是无效组合
                if (via != null && version != null) {
                    logger.warn("Invalid combination: VIA prefix with version number in key: {}", configKey);
                    continue;
                }

                // 确定版本标识：如果有VIA前缀，使用"VIA"，否则使用实际的版本号
                String versionIdentifier = (via != null) ? "VIA" : version;

                String serverName = entry.getValue();
                Optional<RegisteredServer> serverOpt = server.getServer(serverName);

                if (serverOpt.isPresent()) {
                    RegisteredServer registeredServer = serverOpt.get();

                    // 使用版本标识作为键存储服务器
                    Servers.computeIfAbsent(versionIdentifier, k -> new ArrayList<>()).add(registeredServer);

                    // 构建详细的日志信息
                    StringBuilder logBuilder = new StringBuilder();
                    logBuilder.append("Config servers, ");

                    if (via != null) {
                        logBuilder.append("[VIA] ");
                    } else if (version != null) {
                        logBuilder.append("[VERSION] ").append(version).append(" ");
                    }

                    logBuilder.append("[LOADER] ").append(loader);

                    if (number != null) {
                        logBuilder.append(" [NUMBER] ").append(number);
                    }

                    logBuilder.append(" Server: ").append(serverName)
                            .append(" IP: ").append(registeredServer.getServerInfo().getAddress());

                    logger.info(logBuilder.toString());
                } else {
                    logger.warn("Server '{}' not found in Velocity configuration for key: {}", serverName, configKey);
                }
            } else {
                logger.warn("Invalid server configuration key format: {}", configKey);
                logger.warn("Expected format: VERSION-LOADER-NUMBER or VIA-LOADER-NUMBER");
                logger.warn("Examples: 1.20-VANILLA-1, 1.20.1-FORGE, VIA-VANILLA-2, 1.21-FABRIC");
            }
        }

        // Check if all lobbies were retrieved successfully
        if (Servers.isEmpty()) {
            logger.error("No valid servers were found. Ensure they are defined in velocity.toml.");
        } else {
            logger.info("vServerConnect initialized successfully. Loaded {} version group(s).", Servers.size());
        }

        // Register commands
		server.getCommandManager().register("hub", new HubCommand(server, logger));
		server.getCommandManager().register("lobby", new LobbyCommand(server, logger));
		server.getCommandManager().register("vsc", new StatsCommand(server, logger, modLoaderDetector, updateChecker));
	}

	@Subscribe(order = PostOrder.FIRST)
	void onPlayerJoin(final PlayerChooseInitialServerEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		int attempts = connectionAttempts.getOrDefault(uuid, 0) + 1;
		connectionAttempts.put(uuid, attempts);

		// 获取版本信息
		String protocolVersion = player.getProtocolVersion().getName();
		String version = extractVersionFromProtocol(protocolVersion);
		String loader = detectModLoader(player);
		
		// 构建服务器查找键
		String serverKey = buildServerKey(version, loader);
		List<RegisteredServer> servers = Servers.get(serverKey);
		
		// Fallback if no exact match exists:
		if (servers == null || servers.isEmpty()) {
			servers = getFallbackServers(version, loader);
		}
		
		if (servers == null || servers.isEmpty()) {
			player.sendMessage(Component.text("No servers available for your Minecraft version or loader."));
			logger.warn("No servers available for version {} or loader {}", version, loader);
			return;
		}

		RegisteredServer targetServer = getLeastLoadedServer(servers);

		if (targetServer == null) {
			// Try fallback server if all version-specific server are offline
			List<RegisteredServer> fallbackServers = getAllFallbackServers();
			if (fallbackServers != null && !fallbackServers.isEmpty()) {
				targetServer = getLeastLoadedServer(fallbackServers);
			}
		}

		if (targetServer == null) {
			player.sendMessage(Component.text("All servers are currently unavailable, please try again later."));
			logger.warn("All servers are offline for version {} or loader {}", version, loader);
			return;
		}

		if (player.getCurrentServer().isPresent() &&
			player.getCurrentServer().get().getServerInfo().getName().equals(targetServer.getServerInfo().getName())) {
			player.sendMessage(Component.text("You are already in a server."));
			return;
		}

		logger.info("Player {} connecting to lobby '{}' (version: {}, loader: {})", 
			player.getUsername(), targetServer.getServerInfo().getName(), version, loader);
		// Instead of a connection request, set the initial server directly:
		event.setInitialServer(targetServer);
	}

	private RegisteredServer getLeastLoadedServer(List<RegisteredServer> servers) {
		// Filter only online lobbies
		List<RegisteredServer> onlineServers = servers.stream()
			.filter(this::isServerOnline)
			.collect(Collectors.toList());
		
		if (onlineServers.isEmpty()) {
			return null;
		}

		return onlineServers.stream()
			.min(Comparator.comparingInt(server -> server.getPlayersConnected().size()))
			.orElse(null);
	}

	// Helper: Fallback to the highest available lobby version when an exact match is missing.
	private List<RegisteredServer> getFallbackServers(String playerVersion, String loader) {
		// 首先尝试同版本的其他加载器
		for (Map.Entry<String, List<RegisteredServer>> entry : Servers.entrySet()) {
			String key = entry.getKey();
			if (key.equals("VIA")) {
				// VIA服务器可以作为任何版本的备选
				return entry.getValue();
			}
			if (key.startsWith(playerVersion)) {
				return entry.getValue();
			}
		}
		
		// 然后尝试VIA服务器
		List<RegisteredServer> viaServers = Servers.get("VIA");
		if (viaServers != null && !viaServers.isEmpty()) {
			return viaServers;
		}
		
		return null;
	}
	
	// Helper: Get all fallback servers
	private List<RegisteredServer> getAllFallbackServers() {
		List<RegisteredServer> allServers = new ArrayList<>();
		for (List<RegisteredServer> serverList : Servers.values()) {
			allServers.addAll(serverList);
		}
		return allServers;
	}
	
	// Helper: Extract version from protocol version name
	private String extractVersionFromProtocol(String protocolVersion) {
		// Velocity协议版本名称通常是 "1.20.1", "1.8.9" 等格式
		// 我们可以直接使用，或者根据需要进行转换
		if (protocolVersion.contains(".")) {
			String[] parts = protocolVersion.split("\\.");
			if (parts.length >= 2) {
				return parts[0] + "." + parts[1]; // 返回主版本号，如 "1.20"
			}
		}
		return protocolVersion;
	}
	
	// Helper: Detect mod loader from player connection
	private String detectModLoader(Player player) {
		if (modLoaderDetector != null) {
			return modLoaderDetector.getModLoader(player);
		}
		return "VANILLA";
	}
	
	// Helper: Build server key from version and loader
	private String buildServerKey(String version, String loader) {
		if ("VIA".equals(version)) {
			return "VIA-" + loader;
		}
		return version + "-" + loader;
	}

	// Helper: Compare version strings (e.g. "1.8" vs "1.21.1")
	private int compareVersions(String v1, String v2) {
		String[] parts1 = v1.split("\\.");
		String[] parts2 = v2.split("\\.");
		int len = Math.max(parts1.length, parts2.length);
		for (int i = 0; i < len; i++) {
			int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
			int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
			if (num1 != num2) {
				return num1 - num2;
			}
		}
		return 0;
	}

	// Add this new helper method
	private boolean isServerOnline(RegisteredServer server) {
		try {
			// Try to ping the server with a short timeout
			server.ping().get(2, TimeUnit.SECONDS);
			return true;
		} catch (Exception e) {
			logger.warn("Lobby '{}' appears to be offline", server.getServerInfo().getName());
			return false;
		}
	}

	@Subscribe
	public void onServerKick(com.velocitypowered.api.event.player.KickedFromServerEvent event) {
		Player player = event.getPlayer();
		RegisteredServer kickedServer = event.getServer();
		String serverName = kickedServer.getServerInfo().getName();

		// If the kicked server is already a lobby, do nothing.
		if (Servers.values().stream().flatMap(List::stream).anyMatch(server -> server.getServerInfo().getName().equals(serverName))) {
			return;
		}

		RegisteredServer fallback = null;
		String version = player.getProtocolVersion().getName();
		List<RegisteredServer> servers = Servers.get(version);

		if (servers != null && !servers.isEmpty()) {
			fallback = getLeastLoadedServer(servers);
		}

		if (fallback != null) {
			event.setResult(com.velocitypowered.api.event.player.KickedFromServerEvent.RedirectPlayer.create(fallback));
		}
	}

	@Subscribe
	public void onPlayerDisconnect(DisconnectEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		connectionAttempts.remove(uuid);
		
		// 清理模组加载器检测器中的玩家信息
		if (modLoaderDetector != null) {
			modLoaderDetector.removePlayer(player);
		}
		
		logger.info("Player {} disconnected.", player.getUsername());
	}
}
