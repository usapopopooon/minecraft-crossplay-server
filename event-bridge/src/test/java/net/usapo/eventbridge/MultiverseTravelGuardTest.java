package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.core.destination.DestinationInstance;
import org.mvplugins.multiverse.core.event.MVTeleportDestinationEvent;
import org.mvplugins.multiverse.external.vavr.control.Option;

final class MultiverseTravelGuardTest {
    private final MultiverseTravelGuard guard = new MultiverseTravelGuard();

    @Test
    void allDestinationKindsToNetherAndEndAreDeniedEvenForOperators() {
        for (World.Environment environment : new World.Environment[] {
                World.Environment.NETHER, World.Environment.THE_END}) {
            for (boolean op : new boolean[] {false, true}) {
                for (String kind : new String[] {"w", "e", "p", "a", "b", "ca"}) {
                    Player player = mock(Player.class);
                    when(player.isOp()).thenReturn(op);
                    DestinationInstance<?, ?> destination = destination(player, location(environment));
                    when(destination.getIdentifier()).thenReturn(kind);
                    MVTeleportDestinationEvent event =
                            new MVTeleportDestinationEvent(destination, player, player);

                    guard.onDestinationTeleport(event);

                    assertTrue(event.isCancelled(), environment + " op=" + op + " kind=" + kind);
                }
            }
        }
    }

    @Test
    void bothNormalWorldsAndReturnFromOtherDimensionsRemainAllowed() {
        for (String worldName : new String[] {"world", "resource"}) {
            Player player = mock(Player.class);
            Location origin = location(World.Environment.NETHER);
            when(player.getLocation()).thenReturn(origin);
            Location target = location(World.Environment.NORMAL);
            when(target.getWorld().getName()).thenReturn(worldName);
            MVTeleportDestinationEvent event = new MVTeleportDestinationEvent(
                    destination(player, target), player, player);

            guard.onDestinationTeleport(event);

            assertFalse(event.isCancelled(), worldName);
        }
    }

    @Test
    void otherPlayerAndConsoleTeleportsCheckTheTeleporteesDestination() {
        Player target = mock(Player.class);
        CommandSender console = mock(CommandSender.class);
        DestinationInstance<?, ?> destination = destination(target, location(World.Environment.THE_END));
        MVTeleportDestinationEvent event = new MVTeleportDestinationEvent(destination, target, console);

        guard.onDestinationTeleport(event);

        assertTrue(event.isCancelled());
        verify(destination).getLocation(target);
    }

    @Test
    void alreadyCancelledAndNonPlayerEventsAreNotChanged() {
        Player player = mock(Player.class);
        DestinationInstance<?, ?> destination = destination(player, location(World.Environment.NORMAL));
        MVTeleportDestinationEvent cancelled = new MVTeleportDestinationEvent(destination, player, player);
        cancelled.setCancelled(true);
        guard.onDestinationTeleport(cancelled);
        assertTrue(cancelled.isCancelled());
        verify(destination, never()).getLocation(player);

        Entity entity = mock(Entity.class);
        DestinationInstance<?, ?> entityDestination = destination(entity, location(World.Environment.NETHER));
        MVTeleportDestinationEvent nonPlayer = new MVTeleportDestinationEvent(entityDestination, entity, player);
        guard.onDestinationTeleport(nonPlayer);
        assertFalse(nonPlayer.isCancelled());
        verify(entityDestination, never()).getLocation(entity);
    }

    @Test
    void unresolvedDestinationsRetainMultiverseErrorHandling() {
        Player player = mock(Player.class);
        for (Location unresolved : new Location[] {null, new Location(null, 0, 80, 0)}) {
            MVTeleportDestinationEvent event = new MVTeleportDestinationEvent(
                    destination(player, unresolved), player, player);
            guard.onDestinationTeleport(event);
            assertFalse(event.isCancelled());
        }
    }

    private static Location location(World.Environment environment) {
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(environment);
        return new Location(world, 0, 80, 0);
    }

    @SuppressWarnings("unchecked")
    private static DestinationInstance<?, ?> destination(Entity entity, Location location) {
        DestinationInstance<?, ?> destination = mock(DestinationInstance.class);
        when(destination.getLocation(entity)).thenReturn(Option.of(location));
        return destination;
    }
}
