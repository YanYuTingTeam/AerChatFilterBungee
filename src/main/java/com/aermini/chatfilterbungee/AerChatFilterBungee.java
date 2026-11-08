package com.aermini.chatfilterbungee;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AerChatFilterBungee extends Plugin implements Listener {
    private volatile Configuration config;
    private volatile List<String> mgWords;
    private volatile List<String> wjWords;
    private volatile List<String> ffWords;
    private volatile Map<String, List<String>> replaceMap;
    private volatile List<String> blacklist;
    private volatile boolean allowRepeat;
    private volatile int maxRepeat;
    private volatile double similarityThreshold;
    private volatile long cooldownTime;
    private Map<UUID, List<String>> playerMessageHistory = new ConcurrentHashMap<>(); // 存储玩家消息
    private Map<UUID, Long> lastMessageTimes = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("无法创建插件数据目录,插件功能未启用");
            return;
        }
        saveDefaultConfig();
        if (!loadConfig()) {
            getLogger().severe("配置加载失败,插件功能未启用");
            return;
        }
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new ReloadCommand());
        getLogger().info("AerChatFilterBungee 已启用");
    }

    @Override
    public void onDisable() {
        getLogger().info("AerChatFilterBungee 已禁用");
    }

    private void saveDefaultConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try (InputStream in = getResourceAsStream("config.yml")) {
                if (in == null) {
                    getLogger().severe("jar 内缺少 config.yml,无法释放默认配置");
                    return;
                }
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean loadConfig() {
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
            mgWords = config.getStringList("mg");
            wjWords = config.getStringList("wj");
            ffWords = config.getStringList("ff");
            blacklist = config.getStringList("blacklist");
            replaceMap = new LinkedHashMap<>();
            Configuration replaceSection = config.getSection("replace");
            if (replaceSection != null) {
                for (String replacement : replaceSection.getKeys()) {
                    List<String> originalWords = replaceSection.getStringList(replacement);
                    replaceMap.put(replacement, originalWords);
                }
            }
            Configuration rateSection = config.getSection("rate");
            if (rateSection != null) {
                allowRepeat = rateSection.getBoolean("allow_repeat", false);
                maxRepeat = rateSection.getInt("max_repeat", 1);
                similarityThreshold = rateSection.getDouble("similarity", 0.9);
                cooldownTime = rateSection.getLong("cooldown", 3000);
            }
            return true;
        } catch (Exception e) {
            getLogger().severe("配置加载失败: " + e.getMessage());
            return false;
        }
    }

    @EventHandler
    public void onChat(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        String currentServer = player.getServer() != null ? player.getServer().getInfo().getName() : "";
        if (blacklist.contains(currentServer)) return;
        String message = event.getMessage();
        if (isCommand(message)) {
            checkWords(player, event, message);
            return;
        }
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        if (checkCooldown(player, event, currentTime)) return;
        if (checkRepeat(player, event, playerId, message)) return;
        if (checkWords(player, event, message)) return;
        if (!player.hasPermission("aerchatfilter.bypass.repeat") && !allowRepeat) {
            List<String> messageHistory = playerMessageHistory.computeIfAbsent(playerId, k -> new ArrayList<>());
            messageHistory.add(message);
            if (messageHistory.size() > 10) {
                messageHistory.remove(0);
            }
        }
        if (!player.hasPermission("aerchatfilter.bypass.cooldown")) lastMessageTimes.put(playerId, currentTime);
        String filteredMessage = message;
        if (!player.hasPermission("aerchatfilter.bypass.replace")) {
            filteredMessage = applyReplacements(message);
        }
        if (!player.hasPermission("aerchatfilter.bypass.mg")) {
            String sensitiveReplacement = config.getString("msg.sensitive", "*");
            for (String word : mgWords) {
                if (filteredMessage.toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT))) {
                    filteredMessage = filteredMessage.replaceAll("(?i)" + Pattern.quote(word), Matcher.quoteReplacement(getReplacementString(word, sensitiveReplacement)));
                }
            }
        }
        event.setMessage(filteredMessage);
    }

    private boolean checkCooldown(ProxiedPlayer player, ChatEvent event, long currentTime) {
        if (player.hasPermission("aerchatfilter.bypass.cooldown")) return false;
        Long lastMessageTime = lastMessageTimes.get(player.getUniqueId());
        if (lastMessageTime != null && (currentTime - lastMessageTime) < cooldownTime) {
            long remainingCooldown = (cooldownTime - (currentTime - lastMessageTime)) / 1000 + 1;
            String cooldownMsg = config.getString("msg.cooldown", "&7发送消息过于频繁,请等待&e&l{cooldown}秒&r&7后再尝试!").replace("{cooldown}", String.valueOf(remainingCooldown));
            player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', cooldownMsg)));
            event.setCancelled(true);
            return true;
        }
        return false;
    }

    private boolean checkRepeat(ProxiedPlayer player, ChatEvent event, UUID playerId, String message) {
        if (player.hasPermission("aerchatfilter.bypass.repeat") || allowRepeat) return false;
        List<String> messageHistory = playerMessageHistory.computeIfAbsent(playerId, k -> new ArrayList<>());
        String lastMessage = messageHistory.isEmpty() ? "" : messageHistory.get(messageHistory.size() - 1);
        if (lastMessage.isEmpty()) return false;
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        String lowerLast = lastMessage.toLowerCase(Locale.ROOT);
        if (calculateSimilarity(lowerMessage, lowerLast) < similarityThreshold) return false;
        int repeatCount = 0;
        for (int i = messageHistory.size() - 1; i >= 0; i--) {
            if (calculateSimilarity(messageHistory.get(i).toLowerCase(Locale.ROOT), lowerLast) >= similarityThreshold) {
                repeatCount++;
            } else {
                break;
            }
        }
        if (repeatCount >= maxRepeat) {
            sendMsg(player, "msg.repeat", "&f请不要频繁发送重复消息");
            event.setCancelled(true);
            return true;
        }
        return false;
    }

    private boolean checkWords(ProxiedPlayer player, ChatEvent event, String message) {
        if (!player.hasPermission("aerchatfilter.bypass.wj") && containsWord(message, wjWords)) {
            sendMsg(player, "msg.violation", "&f请勿发送敏感内容");
            event.setCancelled(true);
            return true;
        }
        if (!player.hasPermission("aerchatfilter.bypass.ff") && containsWord(message, ffWords)) {
            sendMsg(player, "msg.illegal", "&f请勿发送非法内容");
            event.setCancelled(true);
            return true;
        }
        return false;
    }

    private boolean containsWord(String message, List<String> words) {
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lowerMessage.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void sendMsg(ProxiedPlayer player, String key, String def) {
        player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', config.getString(key, def))));
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerMessageHistory.remove(playerId);
        lastMessageTimes.remove(playerId);
    }

    private boolean isCommand(String message) {
        return message.startsWith("/");
    }

    private String applyReplacements(String message) {
        String result = message;
        for (Map.Entry<String, List<String>> entry : replaceMap.entrySet()) {
            String replacement = entry.getKey();
            List<String> originalWords = entry.getValue();
            for (String originalWord : originalWords) {
                result = result.replaceAll("(?i)" + Pattern.quote(originalWord), Matcher.quoteReplacement(replacement));
            }
        }
        return result;
    }

    private String getReplacementString(String originalWord, String replacementChar) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < originalWord.length(); i++) {
            sb.append(replacementChar);
        }
        return sb.toString();
    }

    // ai
    private double calculateSimilarity(String str1, String str2) {
        if (str1.isEmpty() && str2.isEmpty()) return 1.0;
        if (str1.isEmpty() || str2.isEmpty()) return 0.0;
        if (str1.equals(str2)) return 1.0;
        int[][] dp = new int[str1.length() + 1][str2.length() + 1];
        for (int i = 0; i <= str1.length(); i++) {
            for (int j = 0; j <= str2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else if (str1.charAt(i - 1) == str2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(Math.min(dp[i][j - 1], dp[i - 1][j]), dp[i - 1][j - 1]);
                }
            }
        }
        int maxLen = Math.max(str1.length(), str2.length());
        return 1.0 - (double) dp[str1.length()][str2.length()] / maxLen;
    }

    public class ReloadCommand extends Command {
        public ReloadCommand() {
            super("acfb", "aerchatfilterbungee.reload", "aerchatfilterbungee");
        }
        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("aerchatfilterbungee.reload")) {
                    sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', "&c你没有权限执行此命令!")));
                    return;
                }
                if (loadConfig()) {
                    sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', "&a配置已重新加载!")));
                } else {
                    sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', "&c配置重载失败,请检查后台日志!")));
                }
                return;
            }
            sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', "&e&lAerChatFilterBungee &r&7for &b&lYanYuTing &8| &r&fby. &dAerMini")));
        }
    }
}
