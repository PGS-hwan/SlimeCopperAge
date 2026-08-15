package com.github.hwan.slimecopperage;

import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.Collections;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class SlimeCopperAge extends JavaPlugin implements SlimefunAddon {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern LEGACY_FORMAT_PATTERN = Pattern.compile("(?i)&([0-9a-fk-or])");

    private FileConfiguration messages;
    private Set<String> bannedItems;
    private Set<String> outputOnlyItems;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private MessageFormat messageFormat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfiguration();
        getCommand("sca").setExecutor(this::onCommand);
        getCommand("sca").setTabCompleter(this::onTabComplete);
        send("plugin-version", "version", getPluginMeta().getVersion());
        send("server-version", "version", Bukkit.getBukkitVersion());

        Version serverVersion = Version.parse(Bukkit.getBukkitVersion());
        if (serverVersion.isAtLeast(1, 17, 0)) {
            replaceVanillaCopperContent();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (arguments.length != 1 || !arguments[0].equalsIgnoreCase("reload")) {
            send(sender, "command-usage", Collections.emptyMap());
            return true;
        }
        if (!sender.hasPermission("slimecopperage.reload")) {
            send(sender, "no-permission", Collections.emptyMap());
            return true;
        }

        reloadPluginConfiguration();
        send(sender, "config-reloaded", Collections.emptyMap());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        if (arguments.length == 1 && sender.hasPermission("slimecopperage.reload")) {
            return List.of("reload");
        }
        return List.of();
    }

    private void reloadPluginConfiguration() {
        reloadConfig();
        loadMessages();
        bannedItems = normalizedValues(getConfig().getStringList("banned-items"));
        outputOnlyItems = normalizedValues(getConfig().getStringList("only-replace-textures"));
        messageFormat = MessageFormat.fromConfig(getConfig().getString("message-format", "minimessage"));
    }

    private void loadMessages() {
        String language = getConfig().getString("lang", "en-us").toLowerCase(Locale.ROOT);
        if (getResource("lang/" + language + ".yml") == null) {
            messages = YamlConfiguration.loadConfiguration(new InputStreamReader(getResource("lang/en-us.yml")));
            send("language-fallback", "language", language);
            return;
        }
        messages = YamlConfiguration.loadConfiguration(new InputStreamReader(getResource("lang/" + language + ".yml")));
    }

    private void replaceVanillaCopperContent() {
        Map<String, Material> vanillaCopperMaterials = findVanillaCopperMaterials();
        List<CopperMigration> migrations = new ArrayList<>();

        for (Map.Entry<String, Material> entry : vanillaCopperMaterials.entrySet()) {
            String itemId = entry.getKey();
            if (bannedItems.contains(itemId)) {
                continue;
            }

            SlimefunItem item = SlimefunItem.getById(itemId);
            if (item == null || item.getRecipeOutput() == null) {
                continue;
            }

            migrations.add(new CopperMigration(item, item.getRecipeOutput().clone(), entry.getValue(), outputOnlyItems.contains(itemId)));
        }

        int recipeReplacements = 0;
        for (CopperMigration migration : migrations) {
            if (!migration.outputOnly()) {
                for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
                    if (!bannedItems.contains(item.getId())) {
                        recipeReplacements += replaceCopperIngredient(item, migration.originalOutput(), migration.vanillaMaterial());
                    }
                }
            }
        }

        for (CopperMigration migration : migrations) {
            ItemStack replacement = new ItemStack(migration.vanillaMaterial(), migration.originalOutput().getAmount());
            migration.item().setRecipeOutput(replacement);
        }

        send("items-replaced", "count", String.valueOf(migrations.size()));
        send("recipe-references-replaced", "count", String.valueOf(recipeReplacements));
    }

    private Map<String, Material> findVanillaCopperMaterials() {
        Map<String, Material> materials = new LinkedHashMap<>();
        for (Material material : Material.values()) {
            String materialName = material.name();
            if (materialName.contains("COPPER") || materialName.startsWith("RAW_COPPER")) {
                materials.put(materialName, material);
            }
        }
        return materials;
    }

    private int replaceCopperIngredient(SlimefunItem item, ItemStack originalOutput, Material vanillaMaterial) {
        ItemStack[] replacementRecipe = item.getRecipe().clone();
        int replacements = 0;
        for (int index = 0; index < replacementRecipe.length; index++) {
            ItemStack ingredient = replacementRecipe[index];
            if (ingredient != null && ingredient.isSimilar(originalOutput)) {
                replacementRecipe[index] = new ItemStack(vanillaMaterial, ingredient.getAmount());
                replacements++;
            }
        }
        if (replacements > 0) {
            item.setRecipe(replacementRecipe);
        }
        return replacements;
    }

    private Set<String> normalizedValues(List<String> values) {
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            normalized.add(value.toUpperCase(Locale.ROOT));
        }
        return normalized;
    }

    private void send(String key, String placeholder, String value) {
        getLogger().info(PlainTextComponentSerializer.plainText().serialize(message(key, Map.of(placeholder, value))));
    }

    private void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(message(key, placeholders));
    }

    private Component message(String key, Map<String, String> placeholders) {
        String content = messages.getString(key, key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            content = content.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return switch (messageFormat) {
            case CLASSIC -> LegacyComponentSerializer.legacyAmpersand().deserialize(content);
            case MINIMESSAGE -> miniMessage.deserialize(content);
            case MIXED -> miniMessage.deserialize(convertLegacyFormatting(content));
        };
    }

    private String convertLegacyFormatting(String content) {
        return LEGACY_FORMAT_PATTERN.matcher(content).replaceAll(match -> switch (match.group(1).toLowerCase(Locale.ROOT)) {
            case "0" -> "<black>";
            case "1" -> "<dark_blue>";
            case "2" -> "<dark_green>";
            case "3" -> "<dark_aqua>";
            case "4" -> "<dark_red>";
            case "5" -> "<dark_purple>";
            case "6" -> "<gold>";
            case "7" -> "<gray>";
            case "8" -> "<dark_gray>";
            case "9" -> "<blue>";
            case "a" -> "<green>";
            case "b" -> "<aqua>";
            case "c" -> "<red>";
            case "d" -> "<light_purple>";
            case "e" -> "<yellow>";
            case "f" -> "<white>";
            case "k" -> "<obfuscated>";
            case "l" -> "<bold>";
            case "m" -> "<strikethrough>";
            case "n" -> "<underlined>";
            case "o" -> "<italic>";
            case "r" -> "<reset>";
            default -> match.group();
        });
    }

    @Override
    public String getBugTrackerURL() {
        return null;
    }

    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    private record Version(int major, int minor, int patch) {

        private static Version parse(String input) {
            Matcher matcher = VERSION_PATTERN.matcher(input);
            if (!matcher.find()) {
                return new Version(0, 0, 0);
            }
            return new Version(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))
            );
        }

        private boolean isAtLeast(int targetMajor, int targetMinor, int targetPatch) {
            if (major != targetMajor) {
                return major > targetMajor;
            }
            if (minor != targetMinor) {
                return minor > targetMinor;
            }
            return patch >= targetPatch;
        }
    }

    private record CopperMigration(SlimefunItem item, ItemStack originalOutput, Material vanillaMaterial, boolean outputOnly) {
    }

    private enum MessageFormat {
        CLASSIC,
        MINIMESSAGE,
        MIXED;

        private static MessageFormat fromConfig(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return MINIMESSAGE;
            }
        }
    }
}
