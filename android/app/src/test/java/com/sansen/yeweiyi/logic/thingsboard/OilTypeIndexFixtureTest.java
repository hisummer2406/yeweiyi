package com.sansen.yeweiyi.logic.thingsboard;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * oil_type_index.json 对照表必须与设备真实的 oiltypeArray 一致：有人改了数组顺序或内容，这里变红，
 * 提醒同步 fixture 并通知云端（oilTypeIndex 是跨仓库的稳定标识）。下标规则见 DataUtils.getOilType()。
 */
public class OilTypeIndexFixtureTest {

    private static List<String> oilTypeArray(String valuesDir) throws Exception {
        File file = new File("src/main/res/" + valuesDir + "/array.xml");
        NodeList arrays = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++) {
            Element array = (Element) arrays.item(i);
            if ("oiltypeArray".equals(array.getAttribute("name"))) {
                NodeList items = array.getElementsByTagName("item");
                List<String> names = new ArrayList<>();
                for (int j = 0; j < items.getLength(); j++) {
                    names.add(items.item(j).getTextContent().trim());
                }
                return names;
            }
        }
        throw new AssertionError("oiltypeArray not found in " + file);
    }

    @Test
    public void builtinEntriesMatchDeviceArrays() throws Exception {
        JsonObject table = FixtureLoader.loadProtocolV1("oil_type_index.json");
        JsonObject builtin = table.getAsJsonObject("builtin");
        List<String> zh = oilTypeArray("values");
        List<String> en = oilTypeArray("values-en");

        // getOilType() 拷贝数组除最后一项（"空白"）以外的部分：0 = 未设置，1..length-2 为内置。
        int lastBuiltin = zh.size() - 2;
        assertEquals(lastBuiltin, builtin.size());
        assertEquals(lastBuiltin + 1, table.getAsJsonObject("custom").get("from").getAsInt());
        for (int i = 1; i <= lastBuiltin; i++) {
            JsonObject entry = builtin.getAsJsonObject(String.valueOf(i));
            assertEquals("zh[" + i + "]", zh.get(i), entry.get("zh").getAsString());
            assertEquals("en[" + i + "]", en.get(i), entry.get("en").getAsString());
        }
    }
}
