package com.sansen.yeweiyi.utils;

/**
 * 液位/容积/质量三个单位偏好的语言无关表示。
 *
 * 历史上 {@link Constans#YeWeiUnit}/{@link Constans#RongJiUnit}/{@link Constans#ZhiLiangUnit}
 * 这三个 SharedPreferences 键直接存的是"毫米"/"米"这样的中文原文，业务代码（OilCanFragment、
 * MinOilCanView）也直接拿中文字面量比较。array.xml 补上英文翻译后，英文界面下 Spinner 展示的
 * 选项文字变成了 "mm"/"m"，而 SetBaseFragment 存偏好时直接存的就是 Spinner 当前展示的文字——
 * 于是英文界面下选的单位存成了英文，跟中文比较全部失效，表现为"明明选的是 mm，显示却按另一个
 * 单位换算、小数位也不对"。
 *
 * 现在统一存/比较这里定义的 enum.name()（"MM"/"M" 这种固定标识，不随界面语言变化）；parse()
 * 同时认得三种历史写法——新的规范值、一直以来正确的中文原文、这次 bug 误存的英文——保证不管
 * 设备上现在存的是哪种历史状态，都能被正确识别，不会被这次修复重置成默认值。
 */
public class UnitPref {

    public enum Length {
        MM, M;

        public static Length parse(String raw) {
            if ("米".equals(raw) || "m".equalsIgnoreCase(raw)) {
                return M;
            }
            return MM; // 默认 / "毫米" / "mm" / 无法识别的旧值
        }
    }

    public enum Volume {
        L, CUBIC, GALLON;

        public static final double LITERS_PER_GALLON = 0.26417;

        public static Volume parse(String raw) {
            if ("立方".equals(raw) || "CUBIC".equalsIgnoreCase(raw) || "m³".equalsIgnoreCase(raw) || "m3".equalsIgnoreCase(raw)) {
                return CUBIC;
            }
            // 故意不认裸的 "G"：这里跟上面 CUBIC 分支认 "m³"/"m3" 不是同一种情况——那两个是
            // 历史上真的存进过 SharedPreferences 的展示文案，parse() 要兼容旧数据；加仑是全新
            // 选项，SharedPreferences 里不可能已经存在任何历史脏值，没有必须认 "G" 的理由，反而
            // 单字母字符串作为 parse() 的合法输入风险偏高（容易被将来别的东西撞上）。西语界面
            // Spinner 显示的 "G" 只是 UI 文案，不代表 parse() 也要认它。
            if ("加仑".equals(raw) || "GALLON".equalsIgnoreCase(raw) || "Gal".equalsIgnoreCase(raw)) {
                return GALLON;
            }
            return L; // 默认 / "升" / "L" / 无法识别的旧值
        }

        /** raw 恒为升数；返回换算到当前单位后的数值。 */
        public double convert(double liters) {
            switch (this) {
                case CUBIC:
                    return liters / 1000;
                case GALLON:
                    return liters * LITERS_PER_GALLON;
                default:
                    return liters;
            }
        }

        /** raw 恒为升数；返回换算 + 格式化后可直接展示的字符串。 */
        public String format(double liters) {
            return this == CUBIC
                    ? DataUtils.doubleFomentStr2(convert(liters))
                    : DataUtils.doubleFomentStr(convert(liters)); // L 和 GALLON 都保留 1 位小数
        }
    }

    public enum Mass {
        KG, TON;

        public static Mass parse(String raw) {
            if ("吨".equals(raw) || "TON".equalsIgnoreCase(raw) || "t".equalsIgnoreCase(raw)) {
                return TON;
            }
            return KG; // 默认 / "千克" / "kg" / 无法识别的旧值
        }
    }
}
