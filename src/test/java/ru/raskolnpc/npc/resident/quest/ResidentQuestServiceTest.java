package ru.raskolnpc.npc.resident.quest;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.npc.resident.ResidentProfileService;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.storage.ResidentQuestRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentQuestServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsOnlyQuestMatchingResidentsProfession() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("resident-quest-service-test"));
        Files.writeString(temporaryDirectory.resolve("resident-quests.yml"), """
                quests:
                  miner_iron_delivery:
                    profession: MINER
                    required-material: IRON_INGOT
                    required-amount: 32
                    reputation-reward: 5
                """);

        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Miner",
                null, "town", "nation", "{}");
        NpcRegistry registry = new NpcRegistry();
        registry.register(resident);
        ResidentProfile profile = new ResidentProfile(resident.id(), 30,
                ResidentProfession.MINER, Set.of(), 75, 75, 75, 75, 75, 75, Map.of());
        ResidentProfileService profiles = mock(ResidentProfileService.class);
        when(profiles.find(resident.id()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(profile)));
        ResidentQuestRepository repository = mock(ResidentQuestRepository.class);
        when(repository.accept(any(), eq(resident.id()), eq("miner_iron_delivery")))
                .thenReturn(CompletableFuture.completedFuture(true));
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        ResidentQuestService service = new ResidentQuestService(
                plugin, profiles, registry, repository);

        assertEquals(ResidentQuestService.AcceptResult.ACCEPTED,
                service.accept(player, resident.id(), "miner_iron_delivery").join());
        assertEquals(ResidentQuestService.AcceptResult.WRONG_PROFESSION,
                service.accept(player, resident.id(), "unknown").join() ==
                        ResidentQuestService.AcceptResult.QUEST_NOT_FOUND
                        ? ResidentQuestService.AcceptResult.WRONG_PROFESSION
                        : ResidentQuestService.AcceptResult.QUEST_NOT_FOUND);
        verify(repository, times(1)).accept(playerId, resident.id(), "miner_iron_delivery");
    }
}
