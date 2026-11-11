package ru.raskolnpc.guard;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.raskolnpc.integration.towny.KingdomGuardService;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardHiringServiceTest {
    @Mock Player player;

    @Test
    void kingCanHireAndPaysLevelScaledHireCostAndRent() {
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        AtomicReference<Double> withdrawn = new AtomicReference<>(0.0);
        AtomicReference<Double> createdRent = new AtomicReference<>();
        GuardHiringService service = service(
                (candidate, nation) -> true,
                new GuardHiringService.Economy() {
                    public boolean withdraw(UUID account, double amount) {
                        withdrawn.updateAndGet(value -> value + amount);
                        return true;
                    }
                    public void deposit(UUID account, double amount) { }
                },
                (candidate, nation, level, rent) -> createdRent.set(rent));

        GuardHiringService.HireResult result = service.hire(player, "nation-a", 3);

        assertEquals(300.0, result.hireCost());
        assertEquals(30.0, result.dailyRent());
        assertEquals(300.0, withdrawn.get());
        assertEquals(30.0, createdRent.get());
    }

    @Test
    void rejectsPlayerWithoutHiringPermissionBeforeCharging() {
        GuardHiringService service = service((candidate, nation) -> false,
                economyAlwaysSucceeds(), (candidate, nation, level, rent) -> fail("must not create"));

        assertThrows(IllegalStateException.class, () -> service.hire(player, "nation-a", 1));
    }

    @Test
    void rejectsHiringWhenNationLimitIsReached() {
        NpcRegistry registry = new NpcRegistry();
        for (int i = 0; i < 30; i++) {
            registry.put(NpcRecord.create(NpcType.GUARD, "Guard " + i,
                    null, null, "nation-a", "{}"));
        }
        GuardHiringService service = service(registry, (candidate, nation) -> true,
                economyAlwaysSucceeds(), (candidate, nation, level, rent) -> fail("must not create"));

        assertThrows(RuntimeException.class, () -> service.hire(player, "nation-a", 1));
    }

    @Test
    void refundsHireCostWhenGuardCreationFails() {
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        AtomicReference<Double> deposited = new AtomicReference<>(0.0);
        GuardHiringService.Economy economy = new GuardHiringService.Economy() {
            public boolean withdraw(UUID account, double amount) { return true; }
            public void deposit(UUID account, double amount) {
                deposited.updateAndGet(value -> value + amount);
            }
        };
        GuardHiringService service = service((candidate, nation) -> true, economy,
                (candidate, nation, level, rent) -> { throw new IllegalStateException("spawn failed"); });

        assertThrows(IllegalStateException.class, () -> service.hire(player, "nation-a", 2));
        assertEquals(200.0, deposited.get());
    }

    @Test
    void failsWhenBalanceCannotCoverHireCost() {
        GuardHiringService service = service((candidate, nation) -> true,
                new GuardHiringService.Economy() {
                    public boolean withdraw(UUID account, double amount) { return false; }
                    public void deposit(UUID account, double amount) { fail("no refund expected"); }
                },
                (candidate, nation, level, rent) -> fail("must not create"));

        assertThrows(IllegalStateException.class, () -> service.hire(player, "nation-a", 1));
    }

    @Test
    void dailyRentReturnsFalseWhenPaymentFails() {
        GuardHiringService service = service((candidate, nation) -> true,
                new GuardHiringService.Economy() {
                    public boolean withdraw(UUID account, double amount) { return false; }
                    public void deposit(UUID account, double amount) { }
                },
                (candidate, nation, level, rent) -> { });

        assertFalse(service.collectDailyRent(UUID.randomUUID(), 10.0));
    }

    @Test
    void validatesLevelAndRentAmount() {
        GuardHiringService service = service((candidate, nation) -> true,
                economyAlwaysSucceeds(), (candidate, nation, level, rent) -> { });

        assertThrows(IllegalArgumentException.class, () -> service.hire(player, "nation-a", 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.collectDailyRent(UUID.randomUUID(), -1));
    }

    private GuardHiringService service(GuardHiringService.HiringAccess access,
                                       GuardHiringService.Economy economy,
                                       GuardHiringService.GuardCreator creator) {
        return service(new NpcRegistry(), access, economy, creator);
    }

    private GuardHiringService service(NpcRegistry registry,
                                       GuardHiringService.HiringAccess access,
                                       GuardHiringService.Economy economy,
                                       GuardHiringService.GuardCreator creator) {
        return new GuardHiringService(new KingdomGuardService(), registry, 30,
                100.0, 10.0, access, economy, creator);
    }

    private GuardHiringService.Economy economyAlwaysSucceeds() {
        return new GuardHiringService.Economy() {
            public boolean withdraw(UUID account, double amount) { return true; }
            public void deposit(UUID account, double amount) { }
        };
    }
}
