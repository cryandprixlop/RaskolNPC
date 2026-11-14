package ru.raskolnpc.integration.towny;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownyKingdomGeneralServiceTest {
    private static final UUID KING_ID = UUID.randomUUID();
    private static final UUID NATION_ID = UUID.randomUUID();
    private static final UUID OTHER_NATION_ID = UUID.randomUUID();

    private TownyKingdomGeneralService.Access access;
    private TownyKingdomGeneralService service;
    private Player king;
    private TownyKingdomGeneralService.ResidentRef kingResident;
    private TownyKingdomGeneralService.ResidentRef target;
    private TownyKingdomGeneralService.NationRef nation;
    private TownyKingdomGeneralService.NationRef otherNation;

    @BeforeEach
    void setUp() {
        access = mock(TownyKingdomGeneralService.Access.class);
        service = new TownyKingdomGeneralService(access, "general");
        king = mock(Player.class);
        kingResident = mock(TownyKingdomGeneralService.ResidentRef.class);
        target = mock(TownyKingdomGeneralService.ResidentRef.class);
        nation = mock(TownyKingdomGeneralService.NationRef.class);
        otherNation = mock(TownyKingdomGeneralService.NationRef.class);

        when(king.getUniqueId()).thenReturn(KING_ID);
        when(access.resident(KING_ID)).thenReturn(kingResident);
        when(access.nationOf(kingResident)).thenReturn(nation);
        when(access.isKing(nation, kingResident)).thenReturn(true);
        when(access.matchNationRank("general")).thenReturn("General");
        when(access.resident("Target")).thenReturn(target);
        when(access.nationOf(target)).thenReturn(nation);
        when(nation.id()).thenReturn(NATION_ID);
        when(nation.name()).thenReturn("Kingdom");
        when(target.name()).thenReturn("Target");
    }

    @Test
    void kingCanAppointTownyResidentAndPersistRank() {
        when(access.addRank(target, "General")).thenReturn(true);

        assertDoesNotThrow(() -> service.appoint(king, "Target"));

        verify(access).save(target);
        verify(access).addRank(target, "General");
    }

    @Test
    void kingCanDismissGeneralAndPersistRankRemoval() {
        when(access.hasRank(target, "General")).thenReturn(true);
        when(access.removeRank(target, "General")).thenReturn(true);

        assertDoesNotThrow(() -> service.dismiss(king, "Target"));

        verify(access).save(target);
        verify(access).removeRank(target, "General");
    }

    @Test
    void nonKingCannotAppoint() {
        when(access.isKing(nation, kingResident)).thenReturn(false);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> service.appoint(king, "Target"));

        assertTrue(error.getMessage().contains("только король"));
        verify(access, never()).addRank(any(), anyString());
    }

    @Test
    void cannotAppointResidentFromAnotherNation() {
        when(access.nationOf(target)).thenReturn(otherNation);
        when(otherNation.id()).thenReturn(OTHER_NATION_ID);

        assertThrows(IllegalStateException.class, () -> service.appoint(king, "Target"));
        verify(access, never()).addRank(any(), anyString());
    }

    @Test
    void missingTownyRankFailsSafely() {
        when(access.matchNationRank("general")).thenReturn(null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> service.appoint(king, "Target"));

        assertTrue(error.getMessage().contains("не настроен в Towny"));
        verify(access, never()).addRank(any(), anyString());
    }

    @Test
    void administratorCanInspectAllNations() {
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission("raskolnpc.admin")).thenReturn(true);
        when(access.nations()).thenReturn(List.of(nation));
        when(access.residents(nation)).thenReturn(List.of(target));
        when(access.hasRank(target, "General")).thenReturn(true);

        assertEquals(List.of(new TownyKingdomGeneralService.GeneralEntry("Target", "Kingdom")),
                service.list(admin));
    }

    @Test
    void failedSaveAttemptsToRestorePreviousRankState() {
        when(access.addRank(target, "General")).thenReturn(true);
        doAnswer(invocation -> {
            throw new IllegalStateException("disk error");
        }).when(access).save(target);

        assertThrows(IllegalStateException.class, () -> service.appoint(king, "Target"));

        verify(access).removeRank(target, "General");
        verify(access, times(2)).save(target);
    }
}
