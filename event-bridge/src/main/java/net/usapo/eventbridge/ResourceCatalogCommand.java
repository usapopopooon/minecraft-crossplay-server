package net.usapo.eventbridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

final class ResourceCatalogCommand {
    private final ResourceCatalogManager manager;

    ResourceCatalogCommand(ResourceCatalogManager manager) {
        this.manager = manager;
    }

    boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        if (arguments.length == 2 && arguments[0].equals("resource-pack-validate")) {
            ResourceCatalogManager.ValidationResult result =
                    manager.validatePack(arguments[1]);
            sender.sendMessage(result.valid()
                    ? "Resource pack valid"
                    : "Resource pack rejected: " + result.error());
            return true;
        }
        if (arguments.length != 3 || !arguments[0].equals("resource-catalog-sync")) {
            return false;
        }
        final long revision;
        try {
            revision = Long.parseLong(arguments[1]);
        } catch (NumberFormatException error) {
            sender.sendMessage("Resource catalog rejected: version must be an integer");
            return true;
        }
        ResourceCatalogManager.SyncResult result =
                manager.synchronize(revision, arguments[2]);
        switch (result.status()) {
            case APPLIED -> sender.sendMessage("Resource catalog synchronized: revision "
                    + result.revision() + " (" + result.packCount() + " packs)");
            case CURRENT -> sender.sendMessage("Resource catalog already current: revision "
                    + result.revision() + " (" + result.packCount() + " packs)");
            case REJECTED -> sender.sendMessage("Resource catalog rejected: " + result.error());
        }
        return true;
    }
}
