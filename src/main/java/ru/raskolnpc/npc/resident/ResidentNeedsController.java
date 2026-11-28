package ru.raskolnpc.npc.resident;

import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;

import java.util.Objects;

/** Small deterministic daily needs update; it does not simulate hunger or pathfinding per tick. */
public final class ResidentNeedsController {
    private static final int DAILY_HUNGER_LOSS = 2;

    public ResidentProfile advance(ResidentProfile profile, ResidentSchedule.Activity activity) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(activity, "activity");

        int hunger = decrease(profile.hunger(), DAILY_HUNGER_LOSS);
        int energy = profile.energy();
        int rest = profile.rest();
        int social = profile.social();

        switch (activity) {
            case SLEEP -> {
                energy = increase(energy, 12);
                rest = increase(rest, 15);
                social = decrease(social, 3);
            }
            case REST -> {
                energy = increase(energy, 8);
                rest = increase(rest, 10);
                social = decrease(social, 1);
            }
            case WORK -> {
                energy = decrease(energy, 6);
                rest = decrease(rest, 5);
                social = decrease(social, 2);
            }
            case SOCIALIZE -> {
                energy = decrease(energy, 2);
                rest = decrease(rest, 1);
                social = increase(social, 10);
            }
            case EAT -> {
                hunger = increase(hunger, 18);
                energy = increase(energy, 2);
            }
            case LEISURE, ERRAND -> {
                energy = decrease(energy, 2);
                rest = decrease(rest, 1);
                social = increase(social, 2);
            }
        }

        int mood = (hunger + energy + rest + social + profile.housingSatisfaction()) / 5;
        return new ResidentProfile(profile.npcId(), profile.age(), profile.profession(),
                profile.traits(), hunger, energy, rest, social,
                profile.housingSatisfaction(), mood, profile.skills());
    }

    private static int increase(int value, int amount) {
        return Math.min(100, value + amount);
    }

    private static int decrease(int value, int amount) {
        return Math.max(0, value - amount);
    }
}
