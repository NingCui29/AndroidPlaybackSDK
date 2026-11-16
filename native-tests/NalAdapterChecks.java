package com.cn.media.playback;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Host JVM regression using the actual avcC/hvcC records of the synthetic stsd fixtures. */
public final class NalAdapterChecks {
    private static void require(boolean condition) { if (!condition) throw new AssertionError(); }

    private static byte[] record(byte[] mp4, String tag, int occurrence) {
        for (int i = 4; i < mp4.length-8; i++) {
            boolean matches = true;
            for (int c = 0; c < 4; c++) if (mp4[i+c] != tag.charAt(c)) matches = false;
            if (matches && occurrence-- == 0) {
                int size = ByteBuffer.wrap(mp4).getInt(i-4);
                require(size >= 8 && size <= mp4.length-i+4);
                return Arrays.copyOfRange(mp4,i+4,i-4+size);
            }
        }
        throw new AssertionError("Missing configuration record");
    }

    private static void malformed(Runnable call) {
        try { call.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Malformed NAL accepted");
    }

    public static void main(String[] args) throws Exception {
        for (boolean hevc : new boolean[]{false,true}) {
            String codec = hevc ? "hevc" : "h264";
            byte[] mp4 = Files.readAllBytes(Paths.get(args[0],"H02-"+codec+"-stsd.mp4"));
            int[] widths={640,320,800}, heights={360,240,448};
            for (int version=0; version<3; version++) {
                byte[] config = record(mp4,hevc ? "hvcC" : "avcC",version);
                for (int n : new int[]{1,2,4}) {
                    int at = hevc ? 21 : 4;
                    config[at] = (byte)((config[at]&~3) | (n-1));
                    NalAdapter adapter = new NalAdapter(hevc,ByteBuffer.wrap(config));
                    require(adapter.complete());
                    require(adapter.activeSps().width==widths[version] && adapter.activeSps().height==heights[version]);
                    byte[] payload = {(byte)(hevc ? 2 : 0x41),1,0x40};
                    ByteBuffer packet = ByteBuffer.allocate(n+payload.length);
                    for (int i=n-1; i>=0; i--) packet.put((byte)(payload.length >>> (i*8)));
                    packet.put(payload).flip();
                    adapter.collect(packet);
                    ByteBuffer out = ByteBuffer.allocate(8192);
                    require(adapter.copy(packet,out,false)==7);
                    require(out.getInt(0)==1 && out.get(4)==payload[0] && out.get(6)==payload[2]);
                    out.clear();
                    int expected = adapter.configuration(0).remaining() + (hevc ? 0 : adapter.configuration(1).remaining()) + 7;
                    require(adapter.adaptiveCopy(packet,out)==expected);
                    ByteBuffer cut = packet.duplicate(); cut.limit(cut.limit()-1);
                    malformed(() -> adapter.copy(cut,ByteBuffer.allocate(64),false));
                    ByteBuffer badHeader = packet.duplicate();
                    badHeader.put(n, (byte)0x80);
                    malformed(() -> adapter.collect(badHeader));
                }
            }
            System.out.println("PASS "+codec+" 3 changing SPS dimensions, lengths 1/2/4, configuration + AU, malformed/truncated");
        }
    }
}
