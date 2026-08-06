package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Id;

/**
 * Created by PC on 2017-12-21.
 */

public class FyfRecord {
    @Id
    public int id;
    public int fyfId;
    public String time;
    public int way;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getFyfId() {
        return fyfId;
    }

    public void setFyfId(int fyfId) {
        this.fyfId = fyfId;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public int getWay() {
        return way;
    }

    public void setWay(int way) {
        this.way = way;
    }

    @Override
    public String toString() {
        return "FyfRecord{" +
                "id=" + id +
                ", fyfId=" + fyfId +
                ", time='" + time + '\'' +
                ", way=" + way +
                '}';
    }
}
