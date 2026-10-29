package me.minezone;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MineZonePlugin extends JavaPlugin implements Listener, TabExecutor {

    record Mine(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(Block b) {
            return b.getWorld().getName().equals(world)
                    && b.getX() >= minX && b.getX() <= maxX
                    && b.getY() >= minY && b.getY() <= maxY
                    && b.getZ() >= minZ && b.getZ() <= maxZ;
        }
    }

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Map<String, Mine> mines = new LinkedHashMap<>();
    private final Map<Material, Integer> oreWeights = new LinkedHashMap<>();
    private final Random random = new Random();

    private File minesFile;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        minesFile = new File(getDataFolder(), "mines.yml");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("mzone").setExecutor(this);
        getCommand("mine").setExecutor(this);
        loadAll();
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
    }

    // ------------------------------------------------------------------ загрузка

    private void loadAll() {
        reloadConfig();

        oreWeights.clear();
        ConfigurationSection ores = getConfig().getConfigurationSection("ores");
        if (ores != null) {
            for (String key : ores.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                int w = ores.getInt(key);
                if (m != null && w > 0) oreWeights.put(m, w);
                else getLogger().warning("Неверная руда в конфиге: " + key);
            }
        }

        mines.clear();
        if (minesFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(minesFile);
            ConfigurationSection sec = y.getConfigurationSection("mines");
            if (sec != null) {
                for (String name : sec.getKeys(false)) {
                    ConfigurationSection s = sec.getConfigurationSection(name);
                    if (s == null) continue;
                    mines.put(name.toLowerCase(), new Mine(name, s.getString("world"),
                            s.getInt("minX"), s.getInt("minY"), s.getInt("minZ"),
                            s.getInt("maxX"), s.getInt("maxY"), s.getInt("maxZ")));
                }
            }
        }

        if (task != null) task.cancel();
        long ticks = Math.max(1, getConfig().getLong("spawn-interval-seconds", 120)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(this, this::spawnCycle, ticks, ticks);
    }

    private void saveMines() {
        YamlConfiguration y = new YamlConfiguration();
        for (Mine m : mines.values()) {
            String p = "mines." + m.name() + ".";
            y.set(p + "world", m.world());
            y.set(p + "minX", m.minX()); y.set(p + "minY", m.minY()); y.set(p + "minZ", m.minZ());
            y.set(p + "maxX", m.maxX()); y.set(p + "maxY", m.maxY()); y.set(p + "maxZ", m.maxZ());
        }
        try {
            y.save(minesFile);
        } catch (IOException e) {
            getLogger().severe("Не удалось сохранить mines.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ спавн руды

    private Material pickOre() {
        int total = 0;
        for (int w : oreWeights.values()) total += w;
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Map.Entry<Material, Integer> e : oreWeights.entrySet()) {
            r -= e.getValue();
            if (r < 0) return e.getKey();
        }
        return null;
    }

    private void spawnCycle() {
        for (Mine m : mines.values()) fillMine(m, true);
    }

    /** Заполняет шахту рудой. onlyAir=true: только пустые места (после ломания), false: вся область. */
    private int fillMine(Mine m, boolean onlyAir) {
        World w = Bukkit.getWorld(m.world());
        if (w == null) return 0;
        int count = 0;
        for (int x = m.minX(); x <= m.maxX(); x++) {
            for (int z = m.minZ(); z <= m.maxZ(); z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = m.minY(); y <= m.maxY(); y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (onlyAir && !b.getType().isAir()) continue;
                    Material ore = pickOre();
                    if (ore == null) return count;
                    b.setType(ore, false);
                    count++;
                }
            }
        }
        return count;
    }

    private int rnd(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    // ------------------------------------------------------------------ ломание руды сквозь приват

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!oreWeights.containsKey(b.getType())) return;
        for (Mine m : mines.values()) {
            if (m.contains(b)) {
                e.setCancelled(false); // перебиваем отмену от WorldGuard и т.п.
                return;
            }
        }
    }

    // ------------------------------------------------------------------ команды

    private void msg(CommandSender s, String text) {
        s.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("minezone.admin")) {
            msg(sender, getConfig().getString("messages.no-permission", "&cНет прав."));
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("mzone")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "&cТолько для игроков.");
                return true;
            }
            if (args.length < 1) {
                msg(sender, "&eИспользование: /mzone <one|two>");
                return true;
            }
            Location loc = p.getLocation().getBlock().getLocation();
            switch (args[0].toLowerCase()) {
                case "one" -> {
                    pos1.put(p.getUniqueId(), loc);
                    msg(p, "&aТочка 1 установлена: &f" + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
                }
                case "two" -> {
                    pos2.put(p.getUniqueId(), loc);
                    msg(p, "&aТочка 2 установлена: &f" + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
                }
                default -> msg(p, "&eИспользование: /mzone <one|two>");
            }
            return true;
        }

        // /mine ...
        if (args.length < 1) {
            msg(sender, "&eИспользование: /mine <create [имя]|delete <имя>|list|reload>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "&cТолько для игроков.");
                    return true;
                }
                Location a = pos1.get(p.getUniqueId());
                Location b = pos2.get(p.getUniqueId());
                if (a == null || b == null) {
                    msg(p, "&cСначала поставь точки: /mzone one и /mzone two");
                    return true;
                }
                if (!Objects.equals(a.getWorld(), b.getWorld())) {
                    msg(p, "&cТочки должны быть в одном мире.");
                    return true;
                }
                String name = args.length >= 2 ? args[1] : "mine";
                Mine m = new Mine(name, a.getWorld().getName(),
                        Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                        Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));
                mines.put(name.toLowerCase(), m);
                saveMines();
                int filled = fillMine(m, false);
                msg(p, "&aШахта &f" + name + " &aсоздана и заполнена рудой (" + filled + " блоков). "
                        + "Сломанные блоки восстанавливаются каждые "
                        + getConfig().getLong("spawn-interval-seconds", 120) + " сек.");
            }
            case "delete" -> {
                if (args.length < 2) {
                    msg(sender, "&eИспользование: /mine delete <имя>");
                    return true;
                }
                if (mines.remove(args[1].toLowerCase()) != null) {
                    saveMines();
                    msg(sender, "&aШахта удалена.");
                } else {
                    msg(sender, "&cТакой шахты нет.");
                }
            }
            case "list" -> {
                if (mines.isEmpty()) msg(sender, "&7Шахт нет.");
                for (Mine m : mines.values()) {
                    msg(sender, "&f" + m.name() + " &7(" + m.world() + ": "
                            + m.minX() + " " + m.minY() + " " + m.minZ() + " -> "
                            + m.maxX() + " " + m.maxY() + " " + m.maxZ() + ")");
                }
            }
            case "reload" -> {
                loadAll();
                msg(sender, "&aПерезагружено.");
            }
            default -> msg(sender, "&eИспользование: /mine <create [имя]|delete <имя>|list|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("minezone.admin") || args.length != 1) return List.of();
        List<String> opts = cmd.getName().equalsIgnoreCase("mzone")
                ? List.of("one", "two")
                : List.of("create", "delete", "list", "reload");
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.startsWith(args[0].toLowerCase())) out.add(o);
        return out;
    }
}
