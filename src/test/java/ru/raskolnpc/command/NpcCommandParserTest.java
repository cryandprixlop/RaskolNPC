package ru.raskolnpc.command;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcCommandParserTest {

    @Test
    void parsesValidUuid() {
        UUID expected = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        assertEquals(expected, NpcCommandParser.parseId(expected.toString()).orElseThrow().value());
    }

    @Test
    void rejectsInvalidOrMissingUuid() {
        assertTrue(NpcCommandParser.parseId("not-a-uuid").isEmpty());
        assertTrue(NpcCommandParser.parseId(null).isEmpty());
        assertTrue(NpcCommandParser.parseId("  ").isEmpty());
    }

    @Test
    void joinsMultiWordNamesAndTrimsWhitespace() {
        assertEquals("Guard of the North",
                NpcCommandParser.joinName(new String[]{"create", "resident", "Guard", "of", "the", "North"}, 2));
    }

    @Test
    void returnsEmptyNameWhenNoNameArgumentsExist() {
        assertEquals("", NpcCommandParser.joinName(new String[]{"create", "resident"}, 2));
    }
}
