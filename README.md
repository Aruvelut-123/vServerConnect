# vServerConnect – The Ultimate Server Manager for Velocity Proxy  

vServerConnect is a **powerful and lightweight Velocity plugin** designed to seamlessly manage lobby connections for **players using different Minecraft protocol versions and different mod loaders**. Whether your server supports multiple Minecraft versions or needs efficient load balancing or has vanilla and mod servers on one velocity frontend, vServerConnect ensures players are sent to the **correct server** every time.  

## 🚀 Key Features  
- **Version-Specific Server Assignment** – Automatically sends players to the appropriate server based on their Minecraft version.  
- **ModLoader-Specific Server Assignment** – Automatically detects and routes players based on their mod loader (Vanilla, Forge, Fabric, Quilt, NeoForge).
- **Real-time Mod Loader Detection** – Uses plugin message listeners and brand analysis to accurately detect client mod loaders.
- **Automatic Update Checking** – GitHub integration with configurable update notifications and manual update commands.
- **ViaVersion Support** – Dedicated VIA servers act as fallbacks for any Minecraft version.
- **Seamless Load Balancing** – Distributes players evenly across multiple servers, preventing overcrowding and lag.  
- **Failsafe Mechanisms** – If a server is misconfigured or full, players are redirected to an available fallback server.  
- **Easy Setup & Configuration** – Just drop the plugin into Velocity, configure the servers, and you're good to go!  

## 🔧 Supported Mod Loaders
- **Vanilla** - Standard Minecraft without mods
- **Forge** - Minecraft Forge mod loader
- **Fabric** - Fabric mod loader
- **Quilt** - Quilt mod loader  
- **NeoForge** - NeoForge mod loader (newer Forge versions)  

## Setup

1. Place the plugin jar in your Velocity plugins folder.
2. Configure your servers in two places:

### Plugin Config (config.yml)
This file is located in `src/main/resources/config.yml` (it will be copied to `plugins/vServerConnect/config.yml` on first run):

```yaml
servers:
  # Vanilla servers for different Minecraft versions
  1.20-VANILLA-1: "survival1"
  1.20-VANILLA-2: "survival2"
  1.19-VANILLA-1: "survival3"
  1.8-VANILLA-1: "legacy1"
  1.8-VANILLA-2: "legacy2"
  
  # ViaVersion servers (compatible with multiple versions)
  VIA-VANILLA-1: "via1"
  VIA-VANILLA-2: "via2"
  
  # Modded servers
  1.20.1-FORGE: "forge1"
  1.20.1-FABRIC: "fabric1"
  1.19.2-FORGE: "forge2"
  1.19.2-FABRIC: "fabric2"
  1.19.2-QUILT: "quilt1"
  
  # Additional examples (uncomment to use)
  # 1.18.2-FORGE: "forge3"
  # 1.18.2-FABRIC: "fabric3"
  # 1.17.1-FORGE: "forge4"
  # 1.16.5-FORGE: "forge5"
```

### Server Configuration Pattern:
**Format**: `VERSION-LOADER-NUMBER`

- **VERSION**: Minecraft version (e.g., 1.20, 1.19, 1.8) or `VIA` for ViaVersion compatible servers
- **LOADER**: Mod loader type:
  - `VANILLA`: No mods (standard Minecraft)
  - `FORGE`: Minecraft Forge mod loader
  - `FABRIC`: Fabric mod loader  
  - `QUILT`: Quilt mod loader
  - `NEOFORGE`: NeoForge mod loader
- **NUMBER**: Server instance number (optional, for multiple servers of same type)

**Examples**:
- `1.20-VANILLA-1`: First vanilla server for Minecraft 1.20
- `1.20-VANILLA-2`: Second vanilla server for Minecraft 1.20
- `1.20.1-FORGE`: Forge server for Minecraft 1.20.1
- `VIA-VANILLA-1`: ViaVersion compatible server (works with multiple versions)

**Notes**:
- VIA servers are used as fallbacks when no exact version match is found
- Multiple servers of the same type enable load balancing
- Mod loader detection requires client-side support (Forge/Fabric/etc.)
- Configure corresponding server names in your velocity.toml

### Update Check Configuration
```yaml
# Update checking configuration
update-check:
  # Enable automatic update checking from GitHub
  enabled: true
  # Check interval in hours (minimum: 1 hour)
  check-interval-hours: 6
  # Notify admins in-game when updates are available
  notify-admins: true
```

**Update Check Features**:
- Automatically checks GitHub releases for updates
- Configurable check interval (minimum 1 hour)
- In-game notifications for administrators
- Manual update checking with `/vsc update`
- Version comparison and download links
- Can be completely disabled in configuration

### Velocity Server Configuration (velocity.toml)
In your `velocity.toml`, configure the servers with the required modifications. For example:

```toml
[servers]
name1 = "ip"
name2 = "ip"
name3 = "ip"
name4 = "ip"
name5 = "ip"
name6 = "ip"
name7 = "ip"
try = []             # keep fallback empty
```

## ⚡ Commands  
- **/lobby** – Instantly teleports the player to the appropriate lobby server.
- **/hub** – Alternative command for /lobby.
- **/vsc** – Shows plugin statistics and player distribution (requires permission: `vserverconnect.stats`).
  - **/vsc update** – Manually check for updates from GitHub.
  - **/vsc help** – Show command help.

## 🛠️ Permissions
- `vserverconnect.stats` – Allows viewing plugin statistics with /vsc command.

## 🛡️ Future Enhancements (Planned Features)  
- **Customizable Messages** – Modify join/fallback messages in `config.yml`.  
- **Priority Servers** – Assign preferred servers based on player rank or permissions.  

## 🎮 Conclusion  
vServerConnect is the **ultimate lobby management solution** for Velocity servers, ensuring a smooth, version-compatible experience for all players. Download it today and **enhance your network’s performance and player experience!** 🚀
