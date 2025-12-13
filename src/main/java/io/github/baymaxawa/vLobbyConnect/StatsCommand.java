package io.github.baymaxawa.vLobbyConnect;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.TextComponent;
import org.slf4j.Logger;

import java.util.Map;

/**
 * StatsCommand - 显示插件统计信息和管理员命令
 */
public class StatsCommand implements SimpleCommand {

    private final ProxyServer server;
    private final Logger logger;
    private ModLoaderDetector modLoaderDetector;
    private UpdateChecker updateChecker;

    public StatsCommand(ProxyServer server, Logger logger, ModLoaderDetector modLoaderDetector, UpdateChecker updateChecker) {
        this.server = server;
        this.logger = logger;
        this.modLoaderDetector = modLoaderDetector;
        this.updateChecker = updateChecker;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!source.hasPermission("vserverconnect.stats")) {
            source.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        // 处理子命令
        if (args.length > 0) {
            if ("update".equalsIgnoreCase(args[0])) {
                if (updateChecker != null) {
                    updateChecker.checkUpdatesManually(source);
                } else {
                    source.sendMessage(Component.text("Update checker is not available.", NamedTextColor.RED));
                }
                return;
            } else if ("help".equalsIgnoreCase(args[0])) {
                showHelp(source);
                return;
            }
        }

        // 显示当前在线玩家统计
        TextComponent.Builder message = Component.text()
            .append(Component.text("=== vServerConnect Statistics ===\n", NamedTextColor.GOLD))
            .append(Component.text("Online Players: ", NamedTextColor.YELLOW))
            .append(Component.text(server.getPlayerCount(), NamedTextColor.WHITE))
            .append(Component.newline());

        // 显示模组加载器统计
        if (modLoaderDetector != null) {
            Map<String, Integer> stats = modLoaderDetector.getDetectionStats();
            if (!stats.isEmpty()) {
                message.append(Component.text("Mod Loader Distribution:\n", NamedTextColor.YELLOW));
                for (Map.Entry<String, Integer> entry : stats.entrySet()) {
                    message.append(Component.text("  ", NamedTextColor.GRAY))
                           .append(Component.text(entry.getKey(), NamedTextColor.AQUA))
                           .append(Component.text(": ", NamedTextColor.GRAY))
                           .append(Component.text(entry.getValue(), NamedTextColor.WHITE))
                           .append(Component.text(" players\n", NamedTextColor.GRAY));
                }
            } else {
                message.append(Component.text("No mod loader data available yet.\n", NamedTextColor.GRAY));
            }
        }

        // 显示版本信息
        message.append(Component.text("Plugin Version: ", NamedTextColor.YELLOW))
               .append(Component.text(Constants.VERSION, NamedTextColor.WHITE))
               .append(Component.newline());
        
        // 显示更新检查状态
        if (updateChecker != null) {
            message.append(Component.text("Update Status: ", NamedTextColor.YELLOW));
            String updateStatus = updateChecker.getUpdateStatus();
            NamedTextColor statusColor = updateChecker.isUpdateAvailable() ? 
                NamedTextColor.GOLD : NamedTextColor.GREEN;
            message.append(Component.text(updateStatus, statusColor))
                   .append(Component.newline());
            
            if (updateChecker.isUpdateAvailable()) {
                message.append(Component.text("Latest Version: ", NamedTextColor.YELLOW))
                       .append(Component.text(updateChecker.getLatestVersion(), NamedTextColor.AQUA))
                       .append(Component.newline())
                       .append(Component.text("Download: ", NamedTextColor.YELLOW))
                       .append(Component.text("github.com/Aruvelut-123/vServerConnect/releases", NamedTextColor.BLUE))
                       .append(Component.newline());
            }
        }

        message.append(Component.newline())
               .append(Component.text("Commands: ", NamedTextColor.YELLOW))
               .append(Component.text("/vsc update", NamedTextColor.GRAY))
               .append(Component.text(" - Check for updates", NamedTextColor.WHITE))
               .append(Component.newline());

        source.sendMessage(message.build());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("vserverconnect.stats");
    }
    
    private void showHelp(CommandSource source) {
        TextComponent.Builder help = Component.text()
            .append(Component.text("=== vServerConnect Commands ===\n", NamedTextColor.GOLD))
            .append(Component.text("/vsc", NamedTextColor.YELLOW))
            .append(Component.text(" - Show plugin statistics\n", NamedTextColor.WHITE))
            .append(Component.text("/vsc update", NamedTextColor.YELLOW))
            .append(Component.text(" - Check for updates\n", NamedTextColor.WHITE))
            .append(Component.text("/vsc help", NamedTextColor.YELLOW))
            .append(Component.text(" - Show this help message\n", NamedTextColor.WHITE))
            .append(Component.text("/lobby or /hub", NamedTextColor.YELLOW))
            .append(Component.text(" - Go to your appropriate lobby server\n", NamedTextColor.WHITE));
        
        source.sendMessage(help.build());
    }
}