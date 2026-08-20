package net.usapo.eventbridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.TranslationStore;
import org.bukkit.Keyed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class MarketItems {
    private static final Locale DISPLAY_LOCALE = Locale.JAPANESE;
    private static final PlainTextComponentSerializer PLAIN_TEXT =
            PlainTextComponentSerializer.plainText();
    private static final TranslatableComponentRenderer<Locale> JAPANESE_RENDERER =
            loadJapaneseRenderer();
    private static final List<DisplayedEnchantment> DISPLAYED_ENCHANTMENTS = List.of(
            new DisplayedEnchantment("efficiency", "効率", true),
            new DisplayedEnchantment("unbreaking", "耐久力", true),
            new DisplayedEnchantment("fortune", "幸運", true),
            new DisplayedEnchantment("silk_touch", "シルクタッチ", false),
            new DisplayedEnchantment("mending", "修繕", false));
    private static final List<String> ROMAN_LEVELS =
            List.of("", "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ");

    private MarketItems() {}

    static String displayName(ItemStack item) {
        Component effectiveName = item.effectiveName();
        if (effectiveName != null) {
            String translated = translate(effectiveName);
            if (!translated.isEmpty()) {
                return translated;
            }
        }
        return item.getType().getKey().getKey().replace('_', ' ');
    }

    static String marketDisplayName(ItemStack item) {
        String effectiveName = displayName(item);
        if (!isGeneratedEnchantmentDescription(item, effectiveName)) {
            return effectiveName;
        }
        String materialName = translate(Component.translatable(item.getType().translationKey()));
        if (materialName.isEmpty() || materialName.equals(effectiveName)) {
            return effectiveName;
        }
        return effectiveName + "（" + materialName + "）";
    }

    private static boolean isGeneratedEnchantmentDescription(ItemStack item, String name) {
        Map<String, Integer> remaining = new HashMap<>();
        for (Map.Entry<?, Integer> entry : item.getEnchantments().entrySet()) {
            if (!(entry.getKey() instanceof Keyed keyed)
                    || remaining.put(keyed.getKey().getKey(), entry.getValue()) != null) {
                return false;
            }
        }
        if (remaining.isEmpty()) {
            return false;
        }

        StringBuilder enchantments = new StringBuilder();
        for (DisplayedEnchantment displayed : DISPLAYED_ENCHANTMENTS) {
            Integer level = remaining.remove(displayed.key());
            if (level == null) {
                continue;
            }
            if (level <= 0 || level >= ROMAN_LEVELS.size()) {
                return false;
            }
            enchantments.append(displayed.name());
            if (displayed.showsLevel()) {
                enchantments.append(ROMAN_LEVELS.get(level));
            } else if (level != 1) {
                return false;
            }
        }
        if (!remaining.isEmpty()) {
            return false;
        }

        String prefix = enchantments + "付き";
        String materialKey = item.getType().getKey().getKey();
        if (materialKey.endsWith("_pickaxe")) {
            return name.equals(prefix + "ツルハシ") || name.equals(prefix + "のツルハシ");
        }
        if (materialKey.endsWith("_axe")) {
            return name.equals(prefix + "の斧");
        }
        if (materialKey.endsWith("_shovel")) {
            return name.equals(prefix + "のシャベル");
        }
        return false;
    }

    private static String translate(Component name) {
        return PLAIN_TEXT
                .serialize(JAPANESE_RENDERER.render(name, DISPLAY_LOCALE))
                .strip();
    }

    private static TranslatableComponentRenderer<Locale> loadJapaneseRenderer() {
        TranslationStore<MessageFormat> translations =
                TranslationStore.messageFormat(Key.key("usapo", "minecraft-ja-jp"));
        translations.defaultLocale(DISPLAY_LOCALE);
        InputStream resource = MarketItems.class.getResourceAsStream("/minecraft-ja_jp.tsv");
        if (resource == null) {
            throw new IllegalStateException("Missing Minecraft Japanese translations");
        }
        try (var reader = new BufferedReader(
                new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('\t');
                if (separator <= 0 || separator == line.length() - 1) {
                    throw new IllegalStateException("Invalid Minecraft Japanese translation");
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                translations.register(
                        key,
                        DISPLAY_LOCALE,
                        new MessageFormat(toMessageFormat(value), DISPLAY_LOCALE));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not load Minecraft Japanese translations", error);
        }
        return TranslatableComponentRenderer.usingTranslationSource(translations);
    }

    private static String toMessageFormat(String translation) {
        StringBuilder pattern = new StringBuilder(translation.length());
        int implicitArgument = 0;
        for (int index = 0; index < translation.length(); index++) {
            char current = translation.charAt(index);
            if (current == '\'') {
                pattern.append("''");
                continue;
            }
            if (current == '{') {
                pattern.append("'{'");
                continue;
            }
            if (current == '}') {
                pattern.append("'}'");
                continue;
            }
            if (current != '%' || index + 1 >= translation.length()) {
                pattern.append(current);
                continue;
            }
            char next = translation.charAt(index + 1);
            if (next == '%') {
                pattern.append('%');
                index++;
                continue;
            }
            if (next == 's') {
                pattern.append('{').append(implicitArgument++).append('}');
                index++;
                continue;
            }
            int digitsStart = index + 1;
            int cursor = digitsStart;
            while (cursor < translation.length()
                    && Character.isDigit(translation.charAt(cursor))) {
                cursor++;
            }
            if (cursor > digitsStart
                    && cursor + 1 < translation.length()
                    && translation.charAt(cursor) == '$'
                    && translation.charAt(cursor + 1) == 's') {
                int argument = Integer.parseInt(translation.substring(digitsStart, cursor)) - 1;
                if (argument < 0) {
                    throw new IllegalStateException("Invalid Minecraft translation argument");
                }
                pattern.append('{').append(argument).append('}');
                index = cursor + 1;
                continue;
            }
            pattern.append(current);
        }
        return pattern.toString();
    }

    static boolean canFit(PlayerInventory inventory, ItemStack incoming) {
        int remaining = incoming.getAmount();
        for (ItemStack stored : inventory.getStorageContents()) {
            if (stored == null || stored.getType().isAir()) {
                remaining -= incoming.getMaxStackSize();
            } else if (stored.isSimilar(incoming)) {
                remaining -= Math.max(0, stored.getMaxStackSize() - stored.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    static ItemStack[] snapshot(PlayerInventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        ItemStack[] snapshot = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            snapshot[index] = contents[index] == null ? null : contents[index].clone();
        }
        return snapshot;
    }

    private record DisplayedEnchantment(String key, String name, boolean showsLevel) {}
}
