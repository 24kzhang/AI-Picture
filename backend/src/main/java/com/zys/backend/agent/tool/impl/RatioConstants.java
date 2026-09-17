package com.zys.backend.agent.tool.impl;

import com.zys.backend.agent.tool.AgentToolException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投放比例定义，与旧实现 ratios.py 对齐。
 */
public final class RatioConstants {

    public static final String SQUARE = "1:1";
    public static final String PORTRAIT_4_5 = "4:5";
    public static final String PORTRAIT_3_4 = "3:4";
    public static final String VERTICAL_9_16 = "9:16";
    public static final String LANDSCAPE_16_9 = "16:9";

    private static final Map<String, int[]> SIZES = new LinkedHashMap<>();

    static {
        SIZES.put(SQUARE, new int[]{1080, 1080});
        SIZES.put(PORTRAIT_4_5, new int[]{1080, 1350});
        SIZES.put(PORTRAIT_3_4, new int[]{1080, 1440});
        SIZES.put(VERTICAL_9_16, new int[]{1080, 1920});
        SIZES.put(LANDSCAPE_16_9, new int[]{1920, 1080});
    }

    public static final List<String> ALL = Arrays.asList(SQUARE, PORTRAIT_4_5, PORTRAIT_3_4, VERTICAL_9_16, LANDSCAPE_16_9);

    public static final List<String> DELIVERY = Arrays.asList(SQUARE, PORTRAIT_4_5, VERTICAL_9_16);

    private RatioConstants() {
    }

    public static int[] sizeOf(String ratio) {
        int[] size = SIZES.get(ratio);
        if (size == null) {
            throw new AgentToolException("不支持的比例：" + ratio);
        }
        return size;
    }

    /**
     * 刚好包住原图的目标比例画幅，扩图时主体不必被裁切
     */
    public static int[] coverSize(int width, int height, String ratio) {
        int[] parts = partsOf(ratio);
        int rw = parts[0];
        int rh = parts[1];
        if ((long) width * rh >= (long) height * rw) {
            return new int[]{width, Math.max(1, (int) ((long) width * rh / rw))};
        }
        return new int[]{Math.max(1, (int) ((long) height * rw / rh)), height};
    }

    private static int[] partsOf(String ratio) {
        try {
            String[] split = ratio.split(":");
            return new int[]{Integer.parseInt(split[0].trim()), Integer.parseInt(split[1].trim())};
        } catch (Exception e) {
            throw new AgentToolException("不支持的比例：" + ratio);
        }
    }
}
