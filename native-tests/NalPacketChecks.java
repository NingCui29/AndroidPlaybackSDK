package com.cn.media.playback;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;

/** Runs the candidate classes.jar against independent packet and Annex B references. */
public final class NalPacketChecks {
    private static byte[] bytes(DataInputStream in) throws Exception {
        int size = in.readInt();
        if (size < 0 || size > NalAdapter.LIMIT) throw new AssertionError("Packet size");
        byte[] value = new byte[size];
        in.readFully(value);
        return value;
    }

    public static void main(String[] args) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(args[0]))))) {
            boolean hevc = in.readBoolean();
            NalAdapter adapter = new NalAdapter(hevc, ByteBuffer.wrap(bytes(in)));
            int count = in.readInt();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] expected = new byte[32];
            ByteBuffer output = ByteBuffer.allocate(NalAdapter.LIMIT);
            for (int i = 0; i < count; ++i) {
                long pts = in.readLong(), dts = in.readLong();
                ByteBuffer packet = ByteBuffer.wrap(bytes(in)).asReadOnlyBuffer();
                in.readFully(expected);
                adapter.collect(packet);
                output.clear();
                int size = adapter.copy(packet, output, false);
                if (packet.position() != 0 || !adapter.complete() || size != output.position())
                    throw new AssertionError("Buffer/configuration at packet " + i);
                output.flip();
                sha.update(output);
                if (!Arrays.equals(expected, sha.digest()))
                    throw new AssertionError("Payload at packet " + i + " pts=" + pts + " dts=" + dts);
            }
            if (in.read() != -1 || count <= 0) throw new AssertionError("Reference extent");
            System.out.println("PASS " + count + " packets");
        }
    }
}
