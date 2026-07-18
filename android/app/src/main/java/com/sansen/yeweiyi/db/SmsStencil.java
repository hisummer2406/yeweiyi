package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;

/**
 * Created by PC on 2018-05-02.
 * 短信模版对象
 */

public class SmsStencil {
    @Id
    private int id;
    @Column(column = "sms_id")
    private String sms_id;
    @Column(column = "name")
    private String name;
    @Column(column = "content")
    private String content;
    @Column(column = "type")
    private int type;//是报警还是请求内容  0 报警 1 内容

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getSms_id() {
        return sms_id;
    }

    public void setSms_id(String sms_id) {
        this.sms_id = sms_id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }
}
