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
import java.util.regex.Pattern;

public class AerChatFilterBungee extends Plugin implements Listener {
    private Configuration config;
    private List<String> mgWords;
    private List<String> wjWords;
    private List<String> ffWords;
    private Map<String, List<String>> replaceMap;
    private List<String> blacklist;
    private boolean allowRepeat;
    private int maxRepeat;
    private double similarityThreshold;
    private long cooldownTime;
    private Map<UUID, List<String>> playerMessageHistory = new ConcurrentHashMap<>(); // 存储玩家消息
    private Map<UUID, Long> lastMessageTimes = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdir();
        saveDefaultConfig();
        loadConfig();
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
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadConfig() {
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
            mgWords = config.getStringList("mg");
            wjWords = config.getStringList("wj");
            ffWords = config.getStringList("ff");
            blacklist = config.getStringList("blacklist");
            replaceMap = new HashMap<>();
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
        } catch (IOException e) {
            e.printStackTrace();
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
            if (!player.hasPermission("aerchatfilter.bypass.wj")) {
                for (String word : wjWords) {
                    if (message.toLowerCase().contains(word.toLowerCase())) {
                        player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', config.getString("msg.violation"))));
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            
            if (!player.hasPermission("aerchatfilter.bypass.ff")) {
                for (String word : ffWords) {
                    if (message.toLowerCase().contains(word.toLowerCase())) {
                        player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', config.getString("msg.illegal"))));
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            
            UUID playerId = player.getUniqueId();
            long currentTime = System.currentTimeMillis();
            if (!player.hasPermission("aerchatfilter.bypass.cooldown")) {
                Long lastMessageTime = lastMessageTimes.get(playerId);
                if (lastMessageTime != null && (currentTime - lastMessageTime) < cooldownTime) {
                    long remainingCooldown = (cooldownTime - (currentTime - lastMessageTime)) / 1000 + 1;
                    String cooldownMsg = config.getString("msg.cooldown").replace("{cooldown}", String.valueOf(remainingCooldown));
                    player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', cooldownMsg)));
                    event.setCancelled(true);
                    return;
                }
            }
            if (!player.hasPermission("aerchatfilter.bypass.repeat") && !allowRepeat) {
                List<String> messageHistory = playerMessageHistory.computeIfAbsent(playerId, k -> new ArrayList<>());
                String lastMessage = messageHistory.isEmpty() ? "" : messageHistory.get(messageHistory.size() - 1);
                if (!lastMessage.isEmpty()) {
                    double similarity = calculateSimilarity(message.toLowerCase(), lastMessage.toLowerCase());
                    if (similarity >= similarityThreshold) {
                        int repeatCount = 0;
                        for (int i = messageHistory.size() - 1; i >= 0; i--) {
                            if (calculateSimilarity(messageHistory.get(i).toLowerCase(), lastMessage.toLowerCase()) >= similarityThreshold) {
                                repeatCount++;
                            } else {
                                break;
                            }
                        }
                        
                        if (repeatCount >= maxRepeat) {
                            player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', config.getString("msg.repeat"))));
                            event.setCancelled(true);
                            return;
                        }
                    } 
                }
                
                messageHistory.add(message);
                if (messageHistory.size() > 10) {
                    messageHistory.remove(0);
                }
            }
            if (!player.hasPermission("aerchatfilter.bypass.cooldown")) lastMessageTimes.put(playerId, currentTime);
            return;
        }
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // 发言冷却
        if (!player.hasPermission("aerchatfilter.bypass.cooldown")) {
            Long lastMessageTime = lastMessageTimes.get(playerId);
            if (lastMessageTime != null && (currentTime - lastMessageTime) < cooldownTime) {
                long remainingCooldown = (cooldownTime - (currentTime - lastMessageTime)) / 1000 + 1;
                String cooldownMsg = config.getString("msg.cooldown").replace("{cooldown}", String.valueOf(remainingCooldown));
                player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', cooldownMsg)));
                event.setCancelled(true);
                return;
            }
        }
        
        // 重复消息
        if (!player.hasPermission("aerchatfilter.bypass.repeat") && !allowRepeat) {
            List<String> messageHistory = playerMessageHistory.computeIfAbsent(playerId, k -> new ArrayList<>());
            String lastMessage = messageHistory.isEmpty() ? "" : messageHistory.get(messageHistory.size() - 1);
            if (!lastMessage.isEmpty()) {
                double similarity = calculateSimilarity(message.toLowerCase(), lastMessage.toLowerCase());
                if (similarity >= similarityThreshold) {
                    int repeatCount = 0;
                    for (int i = messageHistory.size() - 1; i >= 0; i--) {
                        if (calculateSimilarity(messageHistory.get(i).toLowerCase(), lastMessage.toLowerCase()) >= similarityThreshold) {
                            repeatCount++;
                        } else {
                            break;
                        }
                    }
                    
                    if (repeatCount >= maxRepeat) {
                        player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', config.getString("msg.repeat"))));
                        event.setCancelled(true);
                        return;
                    }
                } 
            }
            messageHistory.add(message);
            if (messageHistory.size() > 10) {
                messageHistory.remove(0);
            }
        }
        
        // 违禁词
        if (!player.hasPermission("aerchatfilter.bypass.wj")) {
            for (String word : wjWords) {
                if (message.toLowerCase().contains(word.toLowerCase())) {
                    player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', config.getString("msg.violation"))));
                    event.setCancelled(true);
                    return;
                }
            }
        }
        
        // 非法词
        if (!player.hasPermission("aerchatfilter.bypass.ff")) {
            for (String word : ffWords) {
                if (message.toLowerCase().contains(word.toLowerCase())) {
                    player.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', config.getString("msg.illegal"))));
                    event.setCancelled(true);
                    return;
                }
            }
        }
        
        // 替换
        String filteredMessage = message;
        if (!player.hasPermission("aerchatfilter.bypass.replace")) {
            filteredMessage = applyReplacements(message);
        }
        
        // 敏感词替换
        if (!player.hasPermission("aerchatfilter.bypass.mg")) {
            for (String word : mgWords) {
                if (filteredMessage.toLowerCase().contains(word.toLowerCase())) {
                    String sensitiveReplacement = config.getString("msg.sensitive", "*");
                    filteredMessage = filteredMessage.replaceAll("(?i)" + Pattern.quote(word), getReplacementString(word, sensitiveReplacement));
                }
            }
        }

        if (!player.hasPermission("aerchatfilter.bypass.cooldown")) lastMessageTimes.put(playerId, currentTime);
        event.setMessage(filteredMessage);
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
                result = result.replaceAll("(?i)" + Pattern.quote(originalWord), replacement);
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
            super("acfb", "", "aerchatfilterbungee");
        }
        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("aerchatfilterbungee.reload")) {
                    sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', "&c你没有权限执行此命令!")));
                    return;
                }
                loadConfig();
                sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', "&a配置已重新加载!")));
                return;
            }
            sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&', "&e&lAerChatFilterBungee &r&7for &b&lYanYuTing &8| &r&fby. &dAerMini")));
        }
    }
}


