package ru.raskolnpc.command;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.towny.TownyNpcLimitService;
import ru.raskolnpc.integration.towny.TownyResidentService;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcCommandServiceTest {

    @Test
    void reportsWhetherTownyIntegrationIsConfigured() {
        NpcCommandService enabled = service(mock(TownyResidentService.class), new NpcRegistry());
        NpcCommandService standalone = service((TownyResidentService) null, new NpcRegistry());

        assertTrue(enabled.isTownyIntegrationEnabled());
        assertFalse(standalone.isTownyIntegrationEnabled());
    }

    @Test
    void refusesToListNpcsBeforeLifecycleIsReady() {
        NpcLifecycleService lifecycle = mock(NpcLifecycleService.class);
        when(lifecycle.isReady()).thenReturn(false);
        NpcCommandService service = service(lifecycle, new NpcRegistry());

        assertThrows(IllegalStateException.class, service::list);
    }

    @Test
    void listsRegisteredNpcsInCreationOrder() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord newer = record("2026-01-02T00:00:00Z");
        NpcRecord older = record("2026-01-01T00:00:00Z");
        registry.put(newer);
        registry.put(older);
        NpcLifecycleService lifecycle = mock(NpcLifecycleService.class);
        when(lifecycle.isReady()).thenReturn(true);

        List<NpcRecord> listed = service(lifecycle, registry).list();

        assertEquals(List.of(older, newer), listed);
        assertEquals(List.of(older, newer), listed.stream()
                .sorted(Comparator.comparing(NpcRecord::createdAt)).toList());
    }

    @Test
    void delegatesFindToLifecycle() {
        NpcLifecycleService lifecycle = mock(NpcLifecycleService.class);
        var id = record("2026-01-01T00:00:00Z").id();
        when(lifecycle.find(id)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        assertTrue(service(lifecycle, new NpcRegistry()).find(id).join().isEmpty());
        verify(lifecycle).find(id);
    }

    @Test
    void failsResidentCreationWhenTownAuthorizationFailsBeforePersistence() {
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(mock(Location.class));
        TownyResidentService towny = mock(TownyResidentService.class);
        when(towny.resolveAndAuthorize(eq(player), any(Location.class)))
                .thenThrow(new IllegalStateException("not authorized"));
        NpcLifecycleService lifecycle = mock(NpcLifecycleService.class);
        NpcCommandService service = new NpcCommandService(mock(Plugin.class), lifecycle,
                new NpcRegistry(), mock(NpcEntityManager.class), towny,
                new TownyNpcLimitService(), 15);

        assertThrows(RuntimeException.class,
                () -> service.createResident("Resident", player).join());
        verify(lifecycle, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failsResidentCreationWhenTownLimitIsReachedBeforePersistence() {
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(mock(Location.class));
        TownyResidentService towny = mock(TownyResidentService.class);
        when(towny.resolveAndAuthorize(eq(player), any(Location.class)))
                .thenReturn(new TownyResidentService.TownAssignment("town-1", null));
        NpcRegistry registry = new NpcRegistry();
        for (int i = 0; i < 15; i++) {
            registry.put(NpcRecord.create(NpcType.RESIDENT, "Resident " + i,
                    null, "town-1", null, "{}"));
        }
        NpcLifecycleService lifecycle = mock(NpcLifecycleService.class);
        NpcCommandService service = new NpcCommandService(mock(Plugin.class), lifecycle,
                registry, mock(NpcEntityManager.class), towny,
                new TownyNpcLimitService(), 15);

        assertThrows(RuntimeException.class,
                () -> service.createResident("Another", player).join());
        verify(lifecycle, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    private NpcCommandService service(TownyResidentService towny, NpcRegistry registry) {
        return new NpcCommandService(mock(Plugin.class), mock(NpcLifecycleService.class),
                registry, mock(NpcEntityManager.class), towny,
                new TownyNpcLimitService(), 15);
    }

    private NpcCommandService service(NpcLifecycleService lifecycle, NpcRegistry registry) {
        return new NpcCommandService(mock(Plugin.class), lifecycle,
                registry, mock(NpcEntityManager.class), null,
                new TownyNpcLimitService(), 15);
    }

    private NpcRecord record(String createdAt) {
        return new NpcRecord(ru.raskolnpc.npc.model.NpcId.create(), NpcType.RESIDENT,
                "Test", null, null, null, ru.raskolnpc.npc.model.NpcState.UNLOADED,
                "{}", createdAt, createdAt, null, null);
    }
}
