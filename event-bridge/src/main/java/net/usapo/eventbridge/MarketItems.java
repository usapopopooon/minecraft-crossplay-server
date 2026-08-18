package net.usapo.eventbridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.TranslationStore;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class MarketItems {
    private static final Locale DISPLAY_LOCALE = Locale.JAPANESE;
    private static final PlainTextComponentSerializer PLAIN_TEXT =
            PlainTextComponentSerializer.plainText();
    private static final TranslatableComponentRenderer<Locale> JAPANESE_RENDERER =
            loadJapaneseRenderer();

    private MarketItems() {}

    static String displayName(ItemStack item) {
        Component effectiveName = item.effectiveName();
        if (effectiveName != null) {
            String translated = PLAIN_TEXT
                    .serialize(JAPANESE_RENDERER.render(effectiveName, DISPLAY_LOCALE))
                    .strip();
            if (!translated.isEmpty()) {
                return translated;
            }
        }
        return item.getType().getKey().getKey().replace('_', ' ');
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
}
