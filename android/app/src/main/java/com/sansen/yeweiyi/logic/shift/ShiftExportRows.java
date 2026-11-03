package com.sansen.yeweiyi.logic.shift;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.CommitWork;

import java.util.ArrayList;
import java.util.List;

/**
 * 交班记录 Excel 导出的行：每班每罐一行，带上该班起止时间。纯 Java。
 * 修两个旧缺陷：每行被加两遍；罐已删除时取当前介质 NPE（现在留空）。
 */
public final class ShiftExportRows {

    /** 当前罐设置的介质显示名；罐已不存在时返回 null。 */
    public interface OilTypeLookup {
        String currentOilType(int tank);
    }

    private static final Gson GSON = new Gson();

    private ShiftExportRows() {
    }

    public static List<OilWork> build(List<CommitWork> shifts, OilTypeLookup lookup) {
        List<OilWork> rows = new ArrayList<>();
        for (CommitWork shift : shifts) {
            for (OilWork work : parse(shift.getInfo())) {
                work.setTime(shift.getTime());
                work.setEndTime(shift.getEndTime());
                // 优先用开始交班时记录的介质，旧记录才退回当前罐设置（#49）
                if (work.getJieZhi() == null || work.getJieZhi().isEmpty()) {
                    work.setJieZhi(lookup.currentOilType(work.getOilId()));
                }
                rows.add(work);
            }
        }
        return rows;
    }

    private static List<OilWork> parse(String info) {
        List<OilWork> works = new ArrayList<>();
        if (info == null || info.isEmpty()) {
            return works;
        }
        try {
            OilWork[] parsed = GSON.fromJson(info, OilWork[].class);
            if (parsed != null) {
                for (OilWork w : parsed) {
                    if (w != null) {
                        works.add(w);
                    }
                }
            }
        } catch (JsonParseException e) {
            // 损坏的记录不导出，不影响其它班
        }
        return works;
    }
}
