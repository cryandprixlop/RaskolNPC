package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;
import ru.raskolnpc.npc.resident.model.ResidentProfile.ResidentTrait;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class SqliteResidentProfileRepository implements ResidentProfileRepository {
    private final DatabaseManager database;

    public SqliteResidentProfileRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Void> save(ResidentProfile profile) {
        return database.transaction(connection -> {
            try (var check = connection.prepareStatement(
                    "SELECT type FROM npcs WHERE id = ?")) {
                check.setString(1, profile.npcId().toString());
                try (ResultSet result = check.executeQuery()) {
                    if (!result.next() || !"RESIDENT".equals(result.getString(1))) {
                        throw new SQLException("Resident NPC record does not exist: "
                                + profile.npcId());
                    }
                }
            }

            try (var statement = connection.prepareStatement("""
                    INSERT INTO resident_profiles (
                        npc_id, age, profession, hunger, energy, rest, social,
                        housing_satisfaction, mood
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(npc_id) DO UPDATE SET
                        age = excluded.age,
                        profession = excluded.profession,
                        hunger = excluded.hunger,
                        energy = excluded.energy,
                        rest = excluded.rest,
                        social = excluded.social,
                        housing_satisfaction = excluded.housing_satisfaction,
                        mood = excluded.mood
                    """)) {
                statement.setString(1, profile.npcId().toString());
                statement.setInt(2, profile.age());
                statement.setString(3, profile.profession().name());
                statement.setInt(4, profile.hunger());
                statement.setInt(5, profile.energy());
                statement.setInt(6, profile.rest());
                statement.setInt(7, profile.social());
                statement.setInt(8, profile.housingSatisfaction());
                statement.setInt(9, profile.mood());
                statement.executeUpdate();
            }

            try (var traits = connection.prepareStatement(
                    "DELETE FROM resident_traits WHERE npc_id = ?");
                 var skills = connection.prepareStatement(
                    "DELETE FROM resident_skills WHERE npc_id = ?")) {
                traits.setString(1, profile.npcId().toString());
                traits.executeUpdate();
                skills.setString(1, profile.npcId().toString());
                skills.executeUpdate();
            }

            try (var statement = connection.prepareStatement(
                    "INSERT INTO resident_traits(npc_id, trait) VALUES (?, ?)")) {
                for (ResidentTrait trait : profile.traits()) {
                    statement.setString(1, profile.npcId().toString());
                    statement.setString(2, trait.name());
                    statement.addBatch();
                }
                statement.executeBatch();
            }

            try (var statement = connection.prepareStatement("""
                    INSERT INTO resident_skills(npc_id, profession, skill_level)
                    VALUES (?, ?, ?)
                    """)) {
                for (var skill : profile.skills().entrySet()) {
                    statement.setString(1, profile.npcId().toString());
                    statement.setString(2, skill.getKey().name());
                    statement.setString(3, skill.getValue().name());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<ResidentProfile>> findByNpcId(NpcId npcId) {
        return database.query(connection -> {
            int age;
            ResidentProfession profession;
            int hunger;
            int energy;
            int rest;
            int social;
            int housing;
            int mood;
            try (var statement = connection.prepareStatement("""
                    SELECT age, profession, hunger, energy, rest, social,
                           housing_satisfaction, mood
                    FROM resident_profiles WHERE npc_id = ?
                    """)) {
                statement.setString(1, npcId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    age = result.getInt("age");
                    profession = ResidentProfession.valueOf(result.getString("profession"));
                    hunger = result.getInt("hunger");
                    energy = result.getInt("energy");
                    rest = result.getInt("rest");
                    social = result.getInt("social");
                    housing = result.getInt("housing_satisfaction");
                    mood = result.getInt("mood");
                }
            }

            EnumSet<ResidentTrait> traits = EnumSet.noneOf(ResidentTrait.class);
            try (var statement = connection.prepareStatement(
                    "SELECT trait FROM resident_traits WHERE npc_id = ?")) {
                statement.setString(1, npcId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        traits.add(ResidentTrait.valueOf(result.getString(1)));
                    }
                }
            }

            EnumMap<ResidentProfession, ResidentSkillLevel> skills =
                    new EnumMap<>(ResidentProfession.class);
            try (var statement = connection.prepareStatement("""
                    SELECT profession, skill_level FROM resident_skills WHERE npc_id = ?
                    """)) {
                statement.setString(1, npcId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        skills.put(ResidentProfession.valueOf(result.getString(1)),
                                ResidentSkillLevel.valueOf(result.getString(2)));
                    }
                }
            }

            return Optional.of(new ResidentProfile(npcId, age, profession, traits,
                    hunger, energy, rest, social, housing, mood, skills));
        });
    }

    /**
     * Reads the profile, trait and skill tables once each. This avoids N+1
     * lookups when the behavior service initializes its in-memory resident cache.
     */
    @Override
    public CompletableFuture<List<ResidentProfile>> findAll() {
        return database.query(connection -> {
            Map<NpcId, ProfileBuilder> profiles = new LinkedHashMap<>();
            try (var statement = connection.prepareStatement("""
                    SELECT npc_id, age, profession, hunger, energy, rest, social,
                           housing_satisfaction, mood
                    FROM resident_profiles
                    ORDER BY npc_id
                    """);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    NpcId id = NpcId.fromString(result.getString("npc_id"));
                    profiles.put(id, new ProfileBuilder(id,
                            result.getInt("age"),
                            ResidentProfession.valueOf(result.getString("profession")),
                            result.getInt("hunger"),
                            result.getInt("energy"),
                            result.getInt("rest"),
                            result.getInt("social"),
                            result.getInt("housing_satisfaction"),
                            result.getInt("mood")));
                }
            }

            try (var statement = connection.prepareStatement(
                    "SELECT npc_id, trait FROM resident_traits ORDER BY npc_id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ProfileBuilder profile = profiles.get(
                            NpcId.fromString(result.getString("npc_id")));
                    if (profile != null) {
                        profile.traits.add(ResidentTrait.valueOf(result.getString("trait")));
                    }
                }
            }

            try (var statement = connection.prepareStatement("""
                    SELECT npc_id, profession, skill_level
                    FROM resident_skills ORDER BY npc_id
                    """);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ProfileBuilder profile = profiles.get(
                            NpcId.fromString(result.getString("npc_id")));
                    if (profile != null) {
                        profile.skills.put(
                                ResidentProfession.valueOf(result.getString("profession")),
                                ResidentSkillLevel.valueOf(result.getString("skill_level")));
                    }
                }
            }

            List<ResidentProfile> result = new ArrayList<>(profiles.size());
            profiles.values().forEach(profile -> result.add(profile.build()));
            return List.copyOf(result);
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(NpcId npcId) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM resident_profiles WHERE npc_id = ?")) {
                statement.setString(1, npcId.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static final class ProfileBuilder {
        private final NpcId npcId;
        private final int age;
        private final ResidentProfession profession;
        private final int hunger;
        private final int energy;
        private final int rest;
        private final int social;
        private final int housing;
        private final int mood;
        private final EnumSet<ResidentTrait> traits = EnumSet.noneOf(ResidentTrait.class);
        private final EnumMap<ResidentProfession, ResidentSkillLevel> skills =
                new EnumMap<>(ResidentProfession.class);

        private ProfileBuilder(NpcId npcId, int age, ResidentProfession profession,
                int hunger, int energy, int rest, int social, int housing, int mood) {
            this.npcId = npcId;
            this.age = age;
            this.profession = profession;
            this.hunger = hunger;
            this.energy = energy;
            this.rest = rest;
            this.social = social;
            this.housing = housing;
            this.mood = mood;
        }

        private ResidentProfile build() {
            return new ResidentProfile(npcId, age, profession, traits,
                    hunger, energy, rest, social, housing, mood, skills);
        }
    }
}
