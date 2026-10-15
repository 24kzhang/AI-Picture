package com.zys.backend.agent.provider;

/** 明确区分待补全的白色留边与原图背景，防止模型输出“白纸上的小照片”。 */
public final class OutpaintingInstructions {
    private OutpaintingInstructions() { }

    public static String build(String requirement) {
        return "请完成照片扩图：画布中央矩形是原图，四周新增的白色区域是待补全的缺失内容，不是设计留白。"
                + "把新增区域全部补成与原图边缘连续的真实场景，延续相邻背景纹理、物体表面、光线和透视。"
                + "不要保留白色边框或内嵌小照片效果，不新增重复商品，不拉伸或重绘原图主体，移除选区标记。"
                + "只修改白色掩码区域，黑色掩码区域保持原始像素。补全要求："
                + (requirement == null ? "自然延伸原有背景" : requirement);
    }
}
