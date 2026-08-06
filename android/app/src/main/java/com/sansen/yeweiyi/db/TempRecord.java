package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;

/**
 * Created by PC on 2018-11-02.
 */

public class TempRecord {
    @Id
    public int id;
    @Column(column = "time")
    public String time;
    @Column(column = "tanId")
    public int tanId;
    @Column(column = "temp")
    public double temp;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getTanId() {
        return tanId;
    }

    public void setTanId(int tanId) {
        this.tanId = tanId;
    }

    public double getTemp() {
        return temp;
    }

    public void setTemp(double temp) {
        this.temp = temp;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
