package io.github.baymaxawa.vLobbyConnect;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class HubCommand implements SimpleCommand {

    private final ProxyServer server;
    private final Logger logger;
    private final Map<String, List<RegisteredServer>> versionLobbies = new HashMap<>();
    private ModLoaderDetector modLoaderDetector;

    @SuppressWarnings("unchecked")
    public HubCommand(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
        this.modLoaderDetector = new ModLoaderDetector(server, logger);
        try {
            Yaml yaml = new Yaml();
            File configFile = new File("plugins/vServerConnect/config.yml");
            if (!configFile.exists()) {
                configFile.getParentFile().mkdirs();
                Files.copy(getClass().getResourceAsStream("/config.yml"), configFile.toPath());
            }
            Map<String, Object> config = yaml.load(Files.newInputStream(configFile.toPath()));
            Map<String, String> servers = (Map<String, String>) config.get("servers");
            if (servers == null) {
                logger.error("Failed to load valid server settings from config file.");
            } else {
                // 使用与主插件相同的正则表达式
                Pattern pattern = Pattern.compile("^((VIA)-)?((?:\\d+(?:\\.\\d+)*)-)?([A-Z]+)(?:-(\\d+))?$");
                for (Map.Entry<String, String> entry : servers.entrySet()) {
                    String configKey = entry.getKey();
                    Matcher matcher = pattern.matcher(configKey);
                    
                    if (matcher.matches()) {
                        String viaPrefix = matcher.group(1);
                        String via = matcher.group(2);
                        String versionWithDash = matcher.group(3);
                        String loader = matcher.group(4);
                        
                        String version = (versionWithDash != null && versionWithDash.endsWith("-"))
                            ? versionWithDash.substring(0, versionWithDash.length() - 1)
                            : null;
                        
                        String versionIdentifier = (via != null) ? "VIA" : version;
                        
                        String serverName = entry.getValue();
                        Optional<RegisteredServer> serverOpt = server.getServer(serverName);
                        if (serverOpt.isPresent()) {
                            versionLobbies.computeIfAbsent(versionIdentifier, k -> new ArrayList<>()).add(serverOpt.get());
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error loading config.yml", e);
        }
    }

    @Override
    public void execute(Invocation invocation) {
        logger.info("HubCommand execution started.");
        CommandSource source = invocation.source();

        if (!(source instanceof Player)) {
            source.sendMessage(Component.text("This command can only be used by players."));
            logger.warn("Non-player command source attempted to use HubCommand.");
            return;
        }

        Player player = (Player) source;
        String protocolVersion = player.getProtocolVersion().getName();
        String version = extractVersionFromProtocol(protocolVersion);
        String loader = detectModLoader(player);
        String serverKey = buildServerKey(version, loader);
        
        List<RegisteredServer> lobbies = versionLobbies.get(serverKey);
        if (lobbies == null || lobbies.isEmpty()) {
            lobbies = getFallbackLobbies(version, loader);
        }

        if (lobbies == null || lobbies.isEmpty()) {
            player.sendMessage(Component.text("No servers available for your Minecraft version or loader."));
            logger.warn("No servers available for version {} or loader {}", version, loader);
            return;
        }

        RegisteredServer targetServer = getLeastLoadedLobby(lobbies);

        if (targetServer == null) {
            player.sendMessage(Component.text("All servers are currently unavailable, please try again later."));
            logger.warn("All servers are unavailable for version {} or loader {}", version, loader);
            return;
        }

        // Instead of checking if current server equals target only, check if player's current server is any hub.
        if (player.getCurrentServer().isPresent() &&
            lobbies.stream().anyMatch(s -> s.getServerInfo().getName().equals(
                player.getCurrentServer().get().getServerInfo().getName()
            ))) {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cYou are already in a lobby."));
            return;
        }

        logger.info("Player {} connecting to lobby '{}' (version: {}, loader: {})", 
            player.getUsername(), targetServer.getServerInfo().getName(), version, loader);
        player.createConnectionRequest(targetServer).fireAndForget();
    }

    private RegisteredServer getLeastLoadedLobby(List<RegisteredServer> lobbies) {
        List<RegisteredServer> onlineLobbies = lobbies.stream()
            .filter(this::isServerOnline)
            .collect(Collectors.toList());
        
        if (onlineLobbies.isEmpty()) {
            return null;
        }

        return onlineLobbies.stream()
            .min(Comparator.comparingInt(server -> server.getPlayersConnected().size()))
            .orElse(null);
    }

    private boolean isServerOnline(RegisteredServer server) {
        try {
            server.ping().get(2, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            logger.warn("Lobby '{}' appears to be offline", server.getServerInfo().getName());
            return false;
        }
    }

    // Helper: Fallback to the highest available lobby version when an exact match is missing.
    private List<RegisteredServer> getFallbackLobbies(String playerVersion, String loader) {
        // 首先尝试同版本的其他加载器
        for (Map.Entry<String, List<RegisteredServer>> entry : versionLobbies.entrySet()) {
            String key = entry.getKey();
            if (key.equals("VIA")) {
                return entry.getValue();
            }
            if (key.startsWith(playerVersion)) {
                return entry.getValue();
            }
        }
        
        // 然后尝试VIA服务器
        List<RegisteredServer> viaServers = versionLobbies.get("VIA");
        if (viaServers != null && !viaServers.isEmpty()) {
            return viaServers;
        }
        
        return null;
    }
    
    // Helper: Extract version from protocol version name
    private String extractVersionFromProtocol(String protocolVersion) {
        if (protocolVersion.contains(".")) {
            String[] parts = protocolVersion.split("\\.");
            if (parts.length >= 2) {
                return parts[0] + "." + parts[1];
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
}
