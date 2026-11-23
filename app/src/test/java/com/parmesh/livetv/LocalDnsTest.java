package com.parmesh.livetv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class LocalDnsTest {
    @Test
    public void acceptsMatchingCompressedAnswerAndTtl() throws Exception {
        byte[] packet = reply(1, 0xc0, 0x0c, 10, 0, 0, 42);
        var answers = LocalDns.parse(packet, "server.local", 123, 1);
        assertEquals(1, answers.size());
        assertArrayEquals(new byte[] {10, 0, 0, 42}, answers.get(0).address().getAddress());
        assertEquals(10L, answers.get(0).ttl());
    }

    @Test
    public void rejectsMismatchedQuestionsAndInvalidCompression() throws Exception {
        byte[] packet = reply(1, 0xc0, 0x0c, 10, 0, 0, 42);
        assertTrue(LocalDns.parse(packet, "other.local", 123, 1).isEmpty());
        assertTrue(LocalDns.parse(packet, "server.local", 124, 1).isEmpty());
        assertTrue(LocalDns.parse(packet, "server.local", 123, 28).isEmpty());
        assertTrue(
                LocalDns.parse(Arrays.copyOf(packet, packet.length - 1), "server.local", 123, 1)
                        .isEmpty());
        assertTrue(
                LocalDns.parse(reply(1, 0xc0, 0x7f, 10, 0, 0, 42), "server.local", 123, 1)
                        .isEmpty());
    }

    private static byte[] reply(int type, int pointerHigh, int pointerLow, int... address)
            throws Exception {
        byte[] question = LocalDns.question("server.local", 123, type);
        byte[] packet = Arrays.copyOf(question, question.length + 12 + address.length);
        packet[2] = (byte) 0x84;
        packet[7] = 1;
        byte[] answer = {
            (byte) pointerHigh,
            (byte) pointerLow,
            0,
            (byte) type,
            (byte) 0x80,
            1,
            0,
            0,
            0,
            10,
            0,
            (byte) address.length
        };
        System.arraycopy(answer, 0, packet, question.length, answer.length);
        for (int i = 0; i < address.length; i++)
            packet[question.length + 12 + i] = (byte) address[i];
        return packet;
    }
}
