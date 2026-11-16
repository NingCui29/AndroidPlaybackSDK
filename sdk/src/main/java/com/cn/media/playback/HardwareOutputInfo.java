package com.cn.media.playback;

/**
 * MediaCodec 硬件解码器实际报告的画幅几何与动态格式信息类（不可变对象）。
 * <p>
 * 裁剪坐标为闭区间 [cropLeft, cropRight]、[cropTop, cropBottom]，且在画面旋转变换前定义。
 */
public final class HardwareOutputInfo {
    /** 图像缓冲区像素宽度。 */
    public final int width;
    /** 图像缓冲区像素高度。 */
    public final int height;
    /** 裁剪区域左边界坐标（包含）。 */
    public final int cropLeft;
    /** 裁剪区域顶边界坐标（包含）。 */
    public final int cropTop;
    /** 裁剪区域右边界坐标（包含）。 */
    public final int cropRight;
    /** 裁剪区域底边界坐标（包含）。 */
    public final int cropBottom;
    /** 采样宽高比宽度（SAR / PAR 分子）。 */
    public final int pixelAspectWidth;
    /** 采样宽高比高度（SAR / PAR 分母）。 */
    public final int pixelAspectHeight;
    /** 顺时针旋转角度（0, 90, 180, 270）。 */
    public final int clockwiseRotation;
    /** 解码器配置版本代号。 */
    public final int configurationVersion;
    /** 播放期间发生的输出格式变更次数（如尺寸动态切换）。 */
    public final int outputFormatChanges;
    /** 自适应画幅重塑次数。 */
    public final int adaptiveChanges;
    /** 硬件解码器重建重启次数。 */
    public final int codecRebuilds;
    /** 实际运行的 MediaCodec 硬件组件名称。 */
    public final String component;

    HardwareOutputInfo(String component, int width, int height, int left, int top, int right, int bottom,
                       int sarWidth, int sarHeight, int rotation, int version, int formats, int adaptive, int rebuilds) {
        this.component = component;
        this.width = width; this.height = height;
        cropLeft = left; cropTop = top; cropRight = right; cropBottom = bottom;
        pixelAspectWidth = sarWidth; pixelAspectHeight = sarHeight; clockwiseRotation = rotation;
        configurationVersion = version; outputFormatChanges = formats; adaptiveChanges = adaptive; codecRebuilds = rebuilds;
    }

    /**
     * 计算经过硬件裁剪、像素宽高比矫正及顺时针画面旋转后的最终屏幕显示宽高比（Display Aspect Ratio = 宽 / 高）。
     *
     * @return 实际显示画幅比率数值
     */
    public double displayAspectRatio() {
        double w = (cropRight - cropLeft + 1.0) * pixelAspectWidth / pixelAspectHeight;
        double h = cropBottom - cropTop + 1.0;
        double angle = Math.toRadians(clockwiseRotation);
        double c = Math.abs(Math.cos(angle)), s = Math.abs(Math.sin(angle));
        return (w*c + h*s) / (w*s + h*c);
    }
}
