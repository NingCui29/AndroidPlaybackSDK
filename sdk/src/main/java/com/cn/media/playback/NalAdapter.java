package com.cn.media.playback;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AVC (H.264) 与 HEVC (H.265) 码流适配与 NAL 单元解析器。
 * <p>
 * 负责解析容器提供的编解码配置记录（如 MP4 中的 AVCC/HVCC 头部）或原始码流中的 SPS/PPS/VPS 参数集，
 * 统一将输入包转换为 Android {@link android.media.MediaCodec} 所需的标准 Annex B 字节流格式（{@code 0x00000001} 起始码），
 * 并生成初始化所需的 {@code csd-0} / {@code csd-1} 配置缓冲区。
 * </p>
 * <p>
 * 数据包的顺序与显示时间戳（PTS）保持由解封装器管理，本适配器专注于码流格式转换、关键帧识别与参数集版本追踪。
 * </p>
 */
final class NalAdapter {
    /** 单个视频访问单元（Access Unit）的最大保护阈值（4 MB），超出则视为数据损坏。 */
    static final int LIMIT = 4 * 1024 * 1024;
    /** 标识是否为 HEVC (H.265) 编码格式（{@code true} 为 HEVC，{@code false} 为 AVC）。 */
    private final boolean hevc;
    /** 缓存的参数集原始字节数组映射表，Key 为 {@code (type << 16) | id}。 */
    private final Map<Integer,byte[]> sets = new LinkedHashMap<>();
    /** 参数集结构解析模型映射表，Key 为 {@code (type << 16) | id}。 */
    private final Map<Integer,NalParameters> ids = new LinkedHashMap<>();
    /** AVCC/HVCC 封装模式下指示 NAL 单元长度的字节数（通常为 4 字节，0 表示 Annex B 模式）。 */
    private int lengthBytes;
    /** 当前已存储的所有参数集累计字节数。 */
    private int storedBytes;
    /** 参数集配置的版本单调递增代数。 */
    private int revision;
    /** 当前活跃画面切片所引用的活动 PPS 标识符。 */
    private int activePps = -1;
    /** 标记最近一次解析的数据包中是否包含随机访问关键帧（如 IDR 或 CRA/BLA 帧）。 */
    private boolean randomAccess;

    /**
     * 构造 NAL 适配器并解析初始额外数据（extradata）。
     *
     * @param hevc 是否为 HEVC 编码
     * @param extra 包含编解码初始化数据的缓冲区（如 AVCC/HVCC 头部或包含参数集的 Annex B 数据），可为空
     */
    NalAdapter(boolean hevc, ByteBuffer extra) {
        this.hevc = hevc;
        if (extra == null || !extra.hasRemaining()) return;
        ByteBuffer b = extra.slice();
        check(b.remaining() <= LIMIT);
        if (b.get(0) != 1) { collect(b); return; }
        if (hevc) {
            check(b.remaining() >= 23);
            lengthBytes = (b.get(21) & 3) + 1;
            b.position(22);
            int arrays = unsigned(b);
            for (int i = 0; i < arrays; i++) {
                unsigned(b);
                int count = word(b);
                for (int n = 0; n < count; n++) record(b);
            }
        } else {
            check(b.remaining() >= 7);
            lengthBytes = (b.get(4) & 3) + 1;
            b.position(5);
            int sps = unsigned(b) & 31;
            for (int i = 0; i < sps; i++) record(b);
            int pps = unsigned(b);
            for (int i = 0; i < pps; i++) record(b);
        }
    }

    /** 校验条件是否满足，若非法则抛出参数异常。 */
    private static void check(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Malformed or oversized video access unit");
    }

    /** 读取 1 字节无符号整数（0~255）。 */
    private static int unsigned(ByteBuffer b) { check(b.hasRemaining()); return b.get() & 255; }
    /** 读取 2 字节大端无符号短整数。 */
    private static int word(ByteBuffer b) { return (unsigned(b) << 8) | unsigned(b); }

    /** 从 NAL 单元首字节提取 NALU 类型。 */
    private int type(int first) { return hevc ? (first >> 1) & 63 : first & 31; }

    /** 判断指定的 NAL 单元类型是否为参数集（AVC: SPS/PPS; HEVC: VPS/SPS/PPS）。 */
    private boolean parameter(int type) {
        return hevc ? type >= 32 && type <= 34 : type == 7 || type == 8;
    }

    /** 从缓冲区中解析指定长度的参数集 NAL 单元并记录。 */
    private void record(ByteBuffer b) {
        int size = word(b);
        check(size > 0 && size <= b.remaining());
        int end = b.position() + size;
        addSet(b, b.position(), end);
        b.position(end);
    }

    /** 将一个参数集加入内部集合并解析其参数特征。 */
    private void addSet(ByteBuffer b, int begin, int end) {
        int type = type(b.get(begin));
        if (!parameter(type)) return;
        ByteBuffer part = b.duplicate();
        part.position(begin).limit(end);
        byte[] bytes = new byte[part.remaining()];
        part.get(bytes);
        NalParameters p = NalParameters.read(bytes, hevc, type);
        int key = (type << 16) | p.id;
        byte[] old = sets.get(key);
        if (Arrays.equals(old, bytes)) return;
        int previous = old == null ? 0 : old.length + 4;
        check(bytes.length <= LIMIT - (storedBytes - previous) - 4);
        sets.put(key, bytes);
        ids.put(key, p);
        storedBytes += bytes.length + 4 - previous;
        revision++;
    }

    /** 检索 3 字节起始码 {@code 0x000001} 的偏移位置。 */
    private static int start(ByteBuffer b, int pos) {
        for (int i = pos; i + 2 < b.limit(); i++)
            if (b.get(i) == 0 && b.get(i + 1) == 0 && b.get(i + 2) == 1) return i;
        return -1;
    }

    /** NAL 单元遍历回调接口。 */
    private interface Visitor { void nal(ByteBuffer data, int begin, int end); }

    /** 遍历缓冲区中的所有 NAL 单元（自适应支持 AVCC 长度前缀与 Annex B 000001 起始码）。 */
    private void visit(ByteBuffer data, Visitor visitor) {
        ByteBuffer b = data.slice();
        check(b.remaining() > 0 && b.remaining() <= LIMIT);
        if (lengthBytes != 0) {
            while (b.hasRemaining()) {
                check(b.remaining() >= lengthBytes);
                long size = 0;
                for (int i = 0; i < lengthBytes; i++) size = (size << 8) | unsigned(b);
                check(size > 0 && size <= b.remaining());
                int end = b.position() + (int)size;
                visitor.nal(b, b.position(), end);
                b.position(end);
            }
        } else {
            int prefix = start(b, 0);
            check(prefix >= 0);
            for (int i = 0; i < prefix; ++i) check(b.get(i) == 0);
            while (prefix >= 0) {
                int begin = prefix + 3;
                prefix = start(b, begin);
                int end = prefix < 0 ? b.limit() : prefix;
                while (end > begin && b.get(end - 1) == 0) end--;
                check(end > begin);
                visitor.nal(b, begin, end);
            }
        }
    }

    /** 检查 NAL 单元，提取内联的参数集并检测关键帧切片头部所引用的 PPS ID。 */
    private void inspect(ByteBuffer b, int begin, int end) {
        int first = b.get(begin) & 255, type = type(first);
        check((first & 128) == 0 && (!hevc || (end - begin >= 2 && (b.get(begin+1) & 7) != 0)));
        if (parameter(type)) addSet(b, begin, end);
        else if (hevc ? type >= 16 && type <= 21 : type == 5) {
            byte[] header = new byte[Math.min(64, end - begin)];
            ByteBuffer data = b.duplicate();
            data.position(begin);
            data.get(header);
            int id = NalParameters.picturePps(header, hevc);
            if (id >= 0) { activePps = id; randomAccess = true; }
        }
    }

    /**
     * 收集并检视视频包中的参数集信息，同时重置并更新随机访问标志。
     *
     * @param packet 视频数据包缓冲区
     */
    void collect(ByteBuffer packet) { randomAccess = false; visit(packet, this::inspect); }

    /** 获取最近一次解析的数据包中是否包含关键帧。 */
    boolean randomAccess() { return randomAccess; }

    /** 获取参数集版本代数。 */
    int revision() { return revision; }

    /**
     * 比较与另一个适配器的参数配置是否完全一致。
     *
     * @param other 待比对的 NAL 适配器
     * @return 若长度封装模式与所有参数集字节完全相同则返回 {@code true}
     */
    boolean sameConfiguration(NalAdapter other) {
        if (lengthBytes != other.lengthBytes || sets.size() != other.sets.size()) return false;
        for (Map.Entry<Integer,byte[]> e : sets.entrySet())
            if (!Arrays.equals(e.getValue(), other.sets.get(e.getKey()))) return false;
        return true;
    }

    /**
     * 获取当前处于激活状态的 SPS 参数描述模型。
     *
     * @return 激活的 SPS 参数模型，若未能完整解析出匹配的 SPS/PPS 则返回 {@code null}
     */
    NalParameters activeSps() {
        int ppsType = hevc ? 34 : 8, spsType = hevc ? 33 : 7;
        for (Map.Entry<Integer,NalParameters> e : ids.entrySet()) {
            NalParameters p = e.getValue();
            if ((e.getKey() >>> 16) != ppsType || (activePps >= 0 && activePps != p.id)) continue;
            NalParameters sps = ids.get((spsType << 16) | p.parent);
            if (sps != null && (!hevc || ids.containsKey((32 << 16) | sps.parent))) return sps;
        }
        return null;
    }

    /** 检查是否已拥有解码所需全部完整参数集（必须有可匹配的 SPS）。 */
    boolean complete() {
        return activeSps() != null;
    }

    /**
     * 生成用于 MediaCodec 配置的 Annex B 格式缓冲区（{@code csd-0} / {@code csd-1}）。
     *
     * @param index 配置索引（AVC 时 0 对应 SPS，1 对应 PPS；HEVC 时 0 包含 VPS/SPS/PPS）
     * @return 包含 4 字节起始码 {@code 0x00000001} 的参数集字节缓冲区
     */
    ByteBuffer configuration(int index) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int first = hevc ? 32 : 7 + index, last = hevc ? 34 : first;
        for (int type = first; type <= last; type++) {
            for (byte[] set : sets.values()) if (type(set[0]) == type) {
                bytes.write(0); bytes.write(0); bytes.write(0); bytes.write(1);
                bytes.write(set, 0, set.length);
            }
        }
        return ByteBuffer.wrap(bytes.toByteArray());
    }

    /**
     * 将输入数据包中的各 NAL 单元转换为 Annex B 格式（注入 {@code 0x00000001} 起始码）并拷贝至输出缓冲区。
     *
     * @param packet 输入原始视频包
     * @param output 输出目标缓冲区
     * @param configuredWithThisPacket 是否已通过当前包完成参数集配置（若是则跳过内联重复的参数集 NALU）
     * @return 实际写入输出缓冲区的有效字节数
     */
    int copy(ByteBuffer packet, ByteBuffer output, boolean configuredWithThisPacket) {
        int base = output.position();
        visit(packet, (data, begin, end) -> {
            if (configuredWithThisPacket && parameter(type(data.get(begin)))) return;
            int size = end - begin;
            check(size + 4 <= output.remaining() && size + 4 <= LIMIT - (output.position() - base));
            output.put((byte)0).put((byte)0).put((byte)0).put((byte)1);
            ByteBuffer part = data.duplicate();
            part.position(begin).limit(end);
            output.put(part);
        });
        return output.position() - base;
    }

    /**
     * 自适应组装配置头与视频数据载荷至输出缓冲区。
     * <p>
     * 将必要的参数集头部与当前帧载荷合并拷贝，适用于向硬件解码器投递动态刷新配置的关键帧。
     * </p>
     *
     * @param packet 输入数据包
     * @param output 目标输出缓冲区
     * @return 实际写入的总字节数
     */
    int adaptiveCopy(ByteBuffer packet, ByteBuffer output) {
        int pos = output.position();
        ByteBuffer first = configuration(0);
        ByteBuffer second = hevc ? null : configuration(1);
        int bytes = first.remaining() + (second == null ? 0 : second.remaining());
        check(bytes <= LIMIT && bytes <= output.remaining());
        output.put(first);
        if (second != null) output.put(second);
        ByteBuffer bounded = output.slice();
        bounded.limit(Math.min(bounded.remaining(), LIMIT - bytes));
        output.position(output.position() + copy(packet, bounded, true));
        return output.position() - pos;
    }
}
