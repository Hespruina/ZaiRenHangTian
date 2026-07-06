package top.zhrhello.zaiRenHangTian;

import top.zhrhello.zaiRenHangTian.utls.JsonUtil;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ZaiRenHangTian extends JavaPlugin implements Listener {
    // 核心数据结构
    private final Map<UUID, Boolean> blacklistMap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> flightPairs = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> fireworkEffectActive = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Double> flightTriggerHeights = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> fireworkTasks = new ConcurrentHashMap<>();
    private BukkitTask globalCheckerTask;

    // 自定义物品
    private ItemStack getZR370Item() {
        ItemStack item = new ItemStack(Material.FIREWORK_ROCKET);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "宰人航天ZR370航班");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "对一名玩家右键邀请ta一起搭乘本次航班");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPermissions();
        registerEvents();
        registerRecipe();
        registerCommand();
        
        // 启动全局烟花检测任务
        startFireworkEffectChecker();
        
        getLogger().info("宰人航天插件已启用！");
    }

    @Override
    public void onDisable() {
        // 清理所有任务
        if (globalCheckerTask != null) {
            globalCheckerTask.cancel();
        }
        fireworkTasks.values().forEach(BukkitTask::cancel);
        fireworkTasks.clear();
        fireworkEffectActive.clear();
        flightTriggerHeights.clear();
        inFlight.clear();
        flightPairs.clear();
    }

    // 启动全局烟花检测任务
    private void startFireworkEffectChecker() {
        globalCheckerTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : inFlight) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) continue;
                    
                    double currentY = player.getLocation().getY();
                    double triggerHeight = flightTriggerHeights.getOrDefault(uuid, 0.0);
                    
                    // 检查是否达到触发高度
                    if (currentY >= triggerHeight && !fireworkEffectActive.contains(uuid)) {
                        activateFireworkEffect(uuid);
                    }
                    // 检查是否跌回触发高度以下
                    else if (currentY < triggerHeight && fireworkEffectActive.contains(uuid)) {
                        deactivateFireworkEffect(uuid);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    // 激活烟花特效
    private void activateFireworkEffect(UUID uuid) {
        fireworkEffectActive.add(uuid);
        
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    deactivateFireworkEffect(uuid);
                    return;
                }
                
                Location loc = player.getLocation();
                World world = player.getWorld();
                Random random = new Random();
                
                // 在主玩家位置生成烟花
                spawnFireworkParticles(world, loc, random);
                
                // 在配对玩家的位置也生成烟花
                UUID pairUUID = flightPairs.get(uuid);
                if (pairUUID != null) {
                    Player pairPlayer = Bukkit.getPlayer(pairUUID);
                    if (pairPlayer != null && pairPlayer.isOnline()) {
                        spawnFireworkParticles(world, pairPlayer.getLocation(), random);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 2L);
        
        fireworkTasks.put(uuid, task);
        
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.sendMessage(ChatColor.GOLD + "[宰人航天] 已到达太空！烟花庆祝！🎆");
        }
    }

    // 生成烟花粒子
    private void spawnFireworkParticles(World world, Location loc, Random random) {
        for (int i = 0; i < 20; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 3;
            double offsetY = (random.nextDouble() - 0.5) * 3;
            double offsetZ = (random.nextDouble() - 0.5) * 3;
            
            world.spawnParticle(
                Particle.FIREWORKS_SPARK,
                loc.clone().add(offsetX, offsetY, offsetZ),
                1,
                0.1, 0.1, 0.1,
                0.02
            );
            
            if (random.nextBoolean()) {
                Color color = Color.fromRGB(
                    random.nextInt(256),
                    random.nextInt(256),
                    random.nextInt(256)
                );
                
                world.spawnParticle(
                    Particle.SPELL_MOB,
                    loc.clone().add(offsetX, offsetY, offsetZ),
                    0,
                    color.getRed() / 255.0,
                    color.getGreen() / 255.0,
                    color.getBlue() / 255.0,
                    1.0
                );
            }
        }
    }

    // 取消烟花特效
    private void deactivateFireworkEffect(UUID uuid) {
        fireworkEffectActive.remove(uuid);
        
        BukkitTask task = fireworkTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.sendMessage(ChatColor.RED + "[宰人航天] 你正在返回大气层...");
        }
    }

    // 注册合成表
    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(this, "zr370");
        ShapedRecipe recipe = new ShapedRecipe(key, getZR370Item());
        recipe.shape("FFF", "FCF", "FFF");
        recipe.setIngredient('F', Material.FIREWORK_ROCKET);
        recipe.setIngredient('C', Material.FIREWORK_ROCKET);
        Bukkit.addRecipe(recipe);
    }

    // 合成事件监听
    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (event.getRecipe() == null) return;
        if (!event.getRecipe().getResult().isSimilar(getZR370Item())) return;

        ItemStack[] matrix = event.getInventory().getMatrix();
        if (matrix.length < 5) return;

        ItemStack center = matrix[4];
        if (center == null || center.getType() != Material.FIREWORK_ROCKET) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(ChatColor.RED + "[宰人航天] 合成需要三级烟花火箭作为中心！");
            return;
        }

        if (center.getItemMeta() instanceof FireworkMeta fwMeta) {
            if (fwMeta.getPower() != 3) {
                event.setCancelled(true);
                event.getWhoClicked().sendMessage(ChatColor.RED + "[宰人航天] 中心必须使用三级烟花火箭！");
            }
        } else {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(ChatColor.RED + "[宰人航天] 中心必须使用三级烟花火箭！");
        }
    }

    // 实体交互监听
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)) return;
        Player player = event.getPlayer();

        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (!item.isSimilar(getZR370Item())) {
            return;
        }

        if (isBlacklisted(target.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[宰人航天] 对方为本公司黑名单，不予点火");
            event.setCancelled(true);
            return;
        }

        if (!isSkyClear(player) || !isSkyClear(target)) {
            player.sendMessage(ChatColor.RED + "[宰人航天] 您当前所处并非旷野，不允许点火");
            event.setCancelled(true);
            return;
        }

        startFlight(player, target);
        
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
        
        event.setCancelled(true);
    }

    // 天空检测
    private boolean isSkyClear(Player player) {
        Location loc = player.getLocation();
        World world = player.getWorld();
        for (int y = loc.getBlockY() + 1; y < world.getMaxHeight(); y++) {
            if (world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ()).getType() != Material.AIR) {
                return false;
            }
        }
        return true;
    }

    // 启动航班
    private void startFlight(Player player, Player target) {
        String msg = ChatColor.GREEN + "[宰人航天] 欢迎搭乘本公司载人航天VIP专线，本次航班无降落服务。";
        player.sendMessage(msg);
        target.sendMessage(msg);

        double triggerHeightPlayer = player.getLocation().getY() + 100;
        double triggerHeightTarget = target.getLocation().getY() + 100;
        
        inFlight.add(player.getUniqueId());
        inFlight.add(target.getUniqueId());
        flightPairs.put(player.getUniqueId(), target.getUniqueId());
        flightPairs.put(target.getUniqueId(), player.getUniqueId());
        
        flightTriggerHeights.put(player.getUniqueId(), triggerHeightPlayer);
        flightTriggerHeights.put(target.getUniqueId(), triggerHeightTarget);

        PotionEffect levitation = new PotionEffect(PotionEffectType.LEVITATION, 600, 254, false, false);
        player.addPotionEffect(levitation);
        target.addPotionEffect(levitation);
        
        player.sendMessage(ChatColor.YELLOW + "[宰人航天] 触发烟花高度: " + String.format("%.1f", triggerHeightPlayer));
        target.sendMessage(ChatColor.YELLOW + "[宰人航天] 触发烟花高度: " + String.format("%.1f", triggerHeightTarget));
    }

    // 结束航班
    private void endFlight(UUID uuid) {
        if (!inFlight.remove(uuid)) return;

        deactivateFireworkEffect(uuid);
        flightTriggerHeights.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.removePotionEffect(PotionEffectType.LEVITATION);
        }

        UUID pairUuid = flightPairs.remove(uuid);
        if (pairUuid != null) {
            flightPairs.remove(pairUuid);
            endFlight(pairUuid);
        }
    }

    // 注册事件监听
    private void registerEvents() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // 命令注册
    private void registerCommand() {
        Objects.requireNonNull(getCommand("zrhb")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("仅玩家可使用此命令");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(ChatColor.YELLOW + "/zrhb <on|off>");
                return false;
            }

            boolean enable = args[0].equalsIgnoreCase("on");
            setBlacklist(player.getUniqueId(), !enable);

            String status = enable ?
                    ChatColor.GREEN + "已允许" :
                    ChatColor.RED + "已禁止";
            player.sendMessage("[宰人航天] " + status + "被邀请搭乘航班");
            return true;
        });
    }

    // 权限系统
    private void loadPermissions() {
        File file = new File(getDataFolder(), "permissions.json");
        if (!file.exists()) saveDefaultPermissions();

        try {
            blacklistMap.clear();
            Map<UUID, Boolean> loaded = JsonUtil.loadPermissions(file);
            blacklistMap.putAll(loaded);
        } catch (IOException e) {
            getLogger().severe("权限文件加载失败: " + e.getMessage());
        }
    }

    private void saveDefaultPermissions() {
        File file = new File(getDataFolder(), "permissions.json");
        file.getParentFile().mkdirs();
        try {
            JsonUtil.savePermissions(file, new HashMap<>());
        } catch (IOException e) {
            getLogger().severe("默认权限文件创建失败: " + e.getMessage());
        }
    }

    public void savePermissions() {
        try {
            JsonUtil.savePermissions(
                    new File(getDataFolder(), "permissions.json"),
                    blacklistMap
            );
        } catch (IOException e) {
            getLogger().severe("权限保存失败: " + e.getMessage());
        }
    }

    private boolean isBlacklisted(UUID uuid) {
        return blacklistMap.getOrDefault(uuid, false);
    }

    private void setBlacklist(UUID uuid, boolean isBlacklisted) {
        blacklistMap.put(uuid, isBlacklisted);
        savePermissions();
    }

    // 事件监听
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        endFlight(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endFlight(event.getPlayer().getUniqueId());
    }
}