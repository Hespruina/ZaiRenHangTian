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

import com.cjcrafter.foliascheduler.FoliaCompatibility;
import com.cjcrafter.foliascheduler.ServerImplementation;
import com.cjcrafter.foliascheduler.TaskImplementation;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class ZaiRenHangTian extends JavaPlugin implements Listener {
    // 核心数据结构
    private final Map<UUID, Boolean> blacklistMap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> flightPairs = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> fireworkEffectActive = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Double> flightTriggerHeights = new ConcurrentHashMap<>();
    private final Map<UUID, TaskImplementation<Void>> fireworkTasks = new ConcurrentHashMap<>();
    private final Map<UUID, TaskImplementation<Void>> heightCheckTasks = new ConcurrentHashMap<>();

    // Folia 兼容调度器（Bukkit/Paper 上回退为 Bukkit 调度器，Folia 上使用 Region/Entity 调度器）
    private ServerImplementation scheduler;

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
        scheduler = new FoliaCompatibility(this).getServerImplementation();
        saveDefaultConfig();
        loadPermissions();
        registerEvents();
        registerRecipe();
        registerCommand();

        getLogger().info("宰人航天插件已启用！");
    }

    @Override
    public void onDisable() {
        // 清理所有任务
        heightCheckTasks.values().forEach(TaskImplementation::cancel);
        heightCheckTasks.clear();
        fireworkTasks.values().forEach(TaskImplementation::cancel);
        fireworkTasks.clear();
        if (scheduler != null) {
            scheduler.cancelTasks();
        }
        fireworkEffectActive.clear();
        flightTriggerHeights.clear();
        inFlight.clear();
        flightPairs.clear();

        // 保证最后一次权限修改落盘
        savePermissions();
    }

    // 为单个玩家启动高度检测（在玩家所在线程执行，玩家离线自动停）
    private void startHeightChecker(Player player) {
        UUID uuid = player.getUniqueId();
        TaskImplementation<Void> task = scheduler.entity(player).runAtFixedRate(t -> {
            // 玩家离线或已结束航班则自停
            if (!player.isOnline() || !inFlight.contains(uuid)) {
                t.cancel();
                heightCheckTasks.remove(uuid, t);
                return;
            }

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
        }, 1L, 5L);
        heightCheckTasks.put(uuid, task);
    }

    // 激活烟花特效
    private void activateFireworkEffect(UUID uuid) {
        if (!fireworkEffectActive.add(uuid)) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            fireworkEffectActive.remove(uuid);
            return;
        }

        player.sendMessage(ChatColor.GOLD + "[宰人航天] 已到达太空！烟花庆祝！🎆");

        TaskImplementation<Void> task = scheduler.entity(player).runAtFixedRate(t -> {
            if (!player.isOnline()) {
                deactivateFireworkEffect(uuid);
                return;
            }

            // 在主玩家位置生成烟花
            spawnFireworkParticles(player.getWorld(), player.getLocation());

            // 在配对玩家的位置也生成烟花（调度到对方所在线程）
            UUID pairUUID = flightPairs.get(uuid);
            if (pairUUID != null) {
                Player pairPlayer = Bukkit.getPlayer(pairUUID);
                if (pairPlayer != null && pairPlayer.isOnline()) {
                    Player pp = pairPlayer;
                    scheduler.entity(pp).run(() -> spawnFireworkParticles(pp.getWorld(), pp.getLocation()));
                }
            }
        }, 1L, 2L);

        fireworkTasks.put(uuid, task);
    }

    // 生成烟花粒子（内部使用 ThreadLocalRandom，可安全地在任意线程调用）
    private void spawnFireworkParticles(World world, Location loc) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
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
                    color
                );
            }
        }
    }

    // 取消烟花特效
    private void deactivateFireworkEffect(UUID uuid) {
        fireworkEffectActive.remove(uuid);

        TaskImplementation<Void> task = fireworkTasks.remove(uuid);
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

    // 实体交互监听（事件在点击者所在线程触发，target 的实体操作会调度到 target 所在线程）
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

        // 玩家自身的天空检测（当前线程即玩家所在线程）
        if (!isSkyClear(player)) {
            player.sendMessage(ChatColor.RED + "[宰人航天] 您当前所处并非旷野，不允许点火");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        // target 的天空检测在 target 所在线程执行
        scheduler.entity(target).run(() -> {
            if (!isSkyClear(target)) {
                player.sendMessage(ChatColor.RED + "[宰人航天] 您当前所处并非旷野，不允许点火");
                return;
            }
            // 回到 player 所在线程消耗物品并启动航班
            Player p = player;
            scheduler.entity(p).run(() -> {
                // 期间主手物品可能被更换，重新校验
                ItemStack current = p.getInventory().getItemInMainHand();
                if (!current.isSimilar(getZR370Item())) return;

                if (current.getAmount() > 1) {
                    current.setAmount(current.getAmount() - 1);
                } else {
                    p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                }

                startFlight(p, target);
            });
        });
    }

    // 天空检测（必须在该玩家所在线程调用）
    private boolean isSkyClear(Player player) {
        Location loc = player.getLocation();
        int highestY = player.getWorld().getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        return highestY <= loc.getBlockY();
    }

    // 启动航班（必须在 player 所在线程调用；target 的实体操作自动调度到 target 所在线程）
    private void startFlight(Player player, Player target) {
        String msg = ChatColor.GREEN + "[宰人航天] 欢迎搭乘本公司载人航天VIP专线，本次航班无降落服务。";

        double triggerHeightPlayer = player.getLocation().getY() + 100;

        inFlight.add(player.getUniqueId());
        inFlight.add(target.getUniqueId());
        flightPairs.put(player.getUniqueId(), target.getUniqueId());
        flightPairs.put(target.getUniqueId(), player.getUniqueId());

        flightTriggerHeights.put(player.getUniqueId(), triggerHeightPlayer);

        player.sendMessage(msg);
        player.sendMessage(ChatColor.YELLOW + "[宰人航天] 触发烟花高度: " + String.format("%.1f", triggerHeightPlayer));

        PotionEffect levitation = new PotionEffect(PotionEffectType.LEVITATION, 600, 254, false, false);
        player.addPotionEffect(levitation);
        startHeightChecker(player);

        // target 的实体操作在其所在线程执行
        Player t = target;
        scheduler.entity(t).run(() -> {
            double triggerHeightTarget = t.getLocation().getY() + 100;
            flightTriggerHeights.put(t.getUniqueId(), triggerHeightTarget);

            t.sendMessage(msg);
            t.sendMessage(ChatColor.YELLOW + "[宰人航天] 触发烟花高度: " + String.format("%.1f", triggerHeightTarget));
            t.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 600, 254, false, false));
            startHeightChecker(t);
        });
    }

    // 结束航班（被 onDeath/onQuit 调用，调用线程即该玩家所在线程；配对玩家的操作会调度到对方线程）
    private void endFlight(UUID uuid) {
        if (!inFlight.remove(uuid)) return;

        deactivateFireworkEffect(uuid);
        flightTriggerHeights.remove(uuid);
        TaskImplementation<Void> heightTask = heightCheckTasks.remove(uuid);
        if (heightTask != null) {
            heightTask.cancel();
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.removePotionEffect(PotionEffectType.LEVITATION);
        }

        // 同时处理配对玩家，避免递归
        UUID pairUuid = flightPairs.remove(uuid);
        if (pairUuid != null) {
            flightPairs.remove(pairUuid);
            if (inFlight.remove(pairUuid)) {
                deactivateFireworkEffect(pairUuid);
                flightTriggerHeights.remove(pairUuid);
                TaskImplementation<Void> pairHeightTask = heightCheckTasks.remove(pairUuid);
                if (pairHeightTask != null) {
                    pairHeightTask.cancel();
                }

                Player pairPlayer = Bukkit.getPlayer(pairUuid);
                if (pairPlayer != null && pairPlayer.isOnline()) {
                    Player pp = pairPlayer;
                    scheduler.entity(pp).run(() -> pp.removePotionEffect(PotionEffectType.LEVITATION));
                }
            }
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
        // 异步保存，避免阻塞所在线程
        scheduler.async().runNow(this::savePermissions);
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
