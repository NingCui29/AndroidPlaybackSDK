package com.cn.media.playback;

/**
 * 视频参数集（SPS / PPS / VPS）核心参数解析与特征模型。
 * <p>
 * 从原始 NAL 单元中解析标识符、依赖关系、画面可见尺寸、编码 Profile、Level、位深以及 Tier 特征，
 * 用于 Android 平台硬件解码器的参数匹配、自适应播放重配置及色彩格式初始化。
 * </p>
 */
final class NalParameters {
    /** 当前参数集的唯一标识符 ID（如 SPS ID 或 PPS ID）。 */
    final int id;
    /** 父级参数集引用 ID（如 PPS 引用的 SPS ID，或 SPS 引用的 VPS ID），无引用时为 -1。 */
    final int parent;
    /** 经过裁剪窗口（Crop Window）计算后的有效画面像素宽度。 */
    final int width;
    /** 经过裁剪窗口（Crop Window）计算后的有效画面像素高度。 */
    final int height;
    /** 编码 Profile 规范代码。 */
    final int profile;
    /** 编码 Level 规范级别。 */
    final int level;
    /** 像素颜色位深（如 8、10 等）。 */
    final int depth;
    /** HEVC 格式下是否为 High Tier（高层级）。 */
    final boolean highTier;

    /**
     * 内部构造函数（用于 PPS / VPS 等非 SPS 参数集）。
     */
    private NalParameters(int id, int parent, int width, int height) {
        this(id, parent, width, height, 0, 0, 0, false);
    }

    /**
     * 内部全量构造函数（用于 SPS 参数集）。
     */
    private NalParameters(int id, int parent, int width, int height, int profile, int level, int depth, boolean tier) {
        this.id = id; this.parent = parent; this.width = width; this.height = height;
        this.profile = profile; this.level = level; this.depth = depth; this.highTier = tier;
    }

    /**
     * 读取并解析参数集数据。
     *
     * @param set 包含完整参数集 NAL 单元的字节数组（不含起始码）
     * @param hevc 是否为 HEVC (H.265) 格式
     * @param type NAL 单元类型编号
     * @return 解析生成的参数模型实例
     */
    static NalParameters read(byte[] set, boolean hevc, int type) {
        Bits b = new Bits(set, hevc ? 2 : 1);
        if (hevc && type == 32) return new NalParameters(b.bits(4), -1, 0, 0);
        if (type == (hevc ? 34 : 8)) return new NalParameters(b.ue(hevc ? 63 : 255), b.ue(hevc ? 15 : 31), 0, 0);
        return hevc ? hevcSps(b) : avcSps(b);
    }

    /**
     * 从视频画面切片头（Slice Header）中解析出该画面所引用的 PPS 标识符。
     *
     * @param header 画面切片头字节数组
     * @param hevc 是否为 HEVC 格式
     * @return 引用的 PPS ID，若解析失败或非首切片则返回 -1
     */
    static int picturePps(byte[] header, boolean hevc) {
        Bits b = new Bits(header, hevc ? 2 : 1);
        if (hevc) {
            if (b.bits(1) == 0) return -1; // 后续切片段（Subsequent Slice Segment）
            b.skip(1); // no_output_of_prior_pics_flag，IRAP 关键帧存在
        } else {
            if (b.ue(Integer.MAX_VALUE) != 0) return -1;
            b.ue(9);
        }
        return b.ue(hevc ? 63 : 255);
    }

    /**
     * 解析 AVC (H.264) 的序列参数集（SPS）。
     *
     * @param b 比特流读取器
     * @return SPS 参数模型
     */
    private static NalParameters avcSps(Bits b) {
        int profile = b.bits(8);
        int constraints = b.bits(8), level = b.bits(8);
        if ((profile == 66 || profile == 77 || profile == 88) && level == 11 && (constraints & 16) != 0) level = 9;
        int id = b.ue(31), chroma = 1, depth = 8;
        switch (profile) {
            case 44: case 83: case 86: case 100: case 110: case 118: case 122:
            case 128: case 134: case 135: case 138: case 139: case 144: case 244:
                chroma = b.ue(3);
                boolean separate = chroma == 3 && b.bits(1) != 0;
                depth = 8 + b.ue(6); b.ue(6); b.skip(1);
                if (b.bits(1) != 0) {
                    int lists = chroma == 3 ? 12 : 8;
                    for (int i = 0; i < lists; i++) if (b.bits(1) != 0) {
                        int last = 8, next = 8;
                        for (int j = 0; j < (i < 6 ? 16 : 64); j++) {
                            if (next != 0) next = (last + b.se() + 256) & 255;
                            last = next == 0 ? last : next;
                        }
                    }
                }
                if (separate) chroma = 0;
                break;
            default: break;
        }
        b.ue(12);
        int poc = b.ue(2);
        if (poc == 0) b.ue(12);
        if (poc == 1) {
            b.skip(1); b.se(); b.se();
            int offsets = b.ue(255);
            for (int i = 0; i < offsets; i++) b.se();
        }
        b.ue(Integer.MAX_VALUE); b.skip(1);
        long width = 16L * (1L + b.ue(Integer.MAX_VALUE));
        long height = 16L * (1L + b.ue(Integer.MAX_VALUE));
        int frameOnly = b.bits(1);
        height *= 2 - frameOnly;
        if (frameOnly == 0) b.skip(1);
        b.skip(1);
        if (b.bits(1) != 0) {
            int unitX = chroma == 1 || chroma == 2 ? 2 : 1;
            int unitY = (chroma == 1 ? 2 : 1) * (2 - frameOnly);
            width -= unitX * ((long)b.ue(Integer.MAX_VALUE) + b.ue(Integer.MAX_VALUE));
            height -= unitY * ((long)b.ue(Integer.MAX_VALUE) + b.ue(Integer.MAX_VALUE));
        }
        return new NalParameters(id, -1, dimension(width), dimension(height), profile, level, depth, false);
    }

    /**
     * 解析 HEVC (H.265) 的序列参数集（SPS）。
     *
     * @param b 比特流读取器
     * @return SPS 参数模型
     */
    private static NalParameters hevcSps(Bits b) {
        int vps = b.bits(4), layers = b.bits(3);
        b.skip(1 + 2);
        boolean highTier = b.bits(1) != 0;
        int profile = b.bits(5);
        b.skip(32 + 48);
        int level = b.bits(8);
        int profileFlags = 0, levelFlags = 0;
        for (int i = 0; i < layers; i++) {
            profileFlags |= b.bits(1) << i;
            levelFlags |= b.bits(1) << i;
        }
        if (layers > 0) b.skip(2 * (8 - layers));
        for (int i = 0; i < layers; i++) {
            if ((profileFlags & (1 << i)) != 0) b.skip(88);
            if ((levelFlags & (1 << i)) != 0) b.skip(8);
        }
        int id = b.ue(15), chroma = b.ue(3);
        if (chroma == 3 && b.bits(1) != 0) chroma = 0;
        long width = b.ue(Integer.MAX_VALUE), height = b.ue(Integer.MAX_VALUE);
        if (b.bits(1) != 0) {
            width -= (chroma == 1 || chroma == 2 ? 2 : 1) * ((long)b.ue(Integer.MAX_VALUE) + b.ue(Integer.MAX_VALUE));
            height -= (chroma == 1 ? 2 : 1) * ((long)b.ue(Integer.MAX_VALUE) + b.ue(Integer.MAX_VALUE));
        }
        int depth = 8 + b.ue(8);
        return new NalParameters(id, vps, dimension(width), dimension(height), profile, level, depth, highTier);
    }

    /** 校验画面尺寸并在非法时抛出异常。 */
    private static int dimension(long n) {
        if (n < 1 || n > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid coded dimensions");
        return (int)n;
    }

    /**
     * 具备 Emulation Prevention (0x000003) 脱壳能力的无缓冲位流解码器。
     */
    private static final class Bits {
        final byte[] bytes;
        int next, zeros, octet, remaining;
        Bits(byte[] bytes, int start) { this.bytes = bytes; next = start; }

        /** 读取指定位数的整数。 */
        int bits(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                if (remaining == 0) {
                    if (zeros == 2 && next < bytes.length && bytes[next] == 3) { next++; zeros = 0; }
                    if (next >= bytes.length) throw new IllegalArgumentException("Truncated parameter set");
                    octet = bytes[next++] & 255;
                    zeros = octet == 0 ? Math.min(2, zeros + 1) : 0;
                    remaining = 8;
                }
                value = (value << 1) | ((octet >>> (--remaining)) & 1);
            }
            return value;
        }

        /** 跳过指定位数。 */
        void skip(int count) { bits(count); }

        /** 读取无符号指数哥伦布编码（Unsigned Exp-Golomb）。 */
        int ue(int max) {
            int leading = 0;
            while (bits(1) == 0) if (++leading > 30) throw new IllegalArgumentException("Parameter value too large");
            long value = (1L << leading) - 1 + bits(leading);
            if (value > max) throw new IllegalArgumentException("Parameter value out of range");
            return (int)value;
        }

        /** 读取有符号指数哥伦布编码（Signed Exp-Golomb）。 */
        int se() { int n = ue(Integer.MAX_VALUE); return (n & 1) == 0 ? -(n / 2) : n / 2 + 1; }
    }
}
