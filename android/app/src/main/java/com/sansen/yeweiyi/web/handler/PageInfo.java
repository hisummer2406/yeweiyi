package com.sansen.yeweiyi.web.handler;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by PC on 2018-01-24.
 */

public class PageInfo {

    private long total; // 总记录数  必须有
    private long page; // 总页数   根据总记录数计算得出
    private int current = 1; // 当前页数
    private int limit = 25; // 每页记录数
    private List rows; // 分页记录信息
    private int start; // 开始记录索引号
    private int type;
    private int offset;

    public int getOffset() {
        return offset;
    }
    public void setOffset(int offset) {
        this.offset = offset;
    }
    public int getType() {
        return this.type;
    }
    /**
     * @param type the type to set
     */
    public void setType(int type) {
        this.type = type;
    }
    public PageInfo() {
        super();
    }
    public PageInfo(List rows, long total, int limit) {
        this.rows = rows;
        this.total = total;
        this.limit = limit;
    }
    public long getTotal() {
        return total;
    }
    public void setTotal(int total) {
        this.total = total;
        this.getPage();
    }
    public long getPage() {
        if (total <= 0) {
            this.page = 1;
        } else {
            if (limit!=0) {
                System.out.println("====limit的值"+limit+"页数"+page+"total"+total);

                if (total%limit==0) {
                    System.out.println("==0 limit的值"+limit+"页数"+page+"total"+total);
                    this.page=total/limit;
                    System.out.println("==0 limit的值"+limit+"页数"+page+"total"+total);
                    return this.page;
                }else {
                    System.out.println("!=0 limit的值"+limit+"页数"+page+"total"+total);
                    this.page=total/limit+1;
                    System.out.println("==0 limit的值"+limit+"页数"+page+"total"+total);
                    return page;
                }


            }

        }
        System.out.println("limit的值"+limit+"页数"+page+"total"+total);
        return this.page;
    }
    public long getCurrent() {
        System.out.println("当前的页数"+current+"总的数据数"+total+"总的页数"+page);
        if (total == 0) {
            return 1;
        } else if (current <= 1) {
            return 1;
        } else if (current >= page) {
            return page;
        } else {
            return current;
        }
    }
    public void setCurrent(int current) {
        this.current = current;
    }
    public int getLimit() {
        return limit;
    }
    public void setLimit(int limit) {
        this.limit = limit;
    }

    public List getRows() {
        return rows;
    }
    public void setRows(List rows) {
        this.rows = rows;
    }
    public long getCurrentPageIndex() {
        return (this.getCurrent() - 1) * this.getLimit();
    }
    public boolean isFirst() {
        return getCurrent() == 1;
    }
    public boolean isLast() {
        return getCurrent() >= getPage();
    }
    public int getStart() {
        return this.start;
    }
    public void setStart(int start) {
        this.start = start;
    }
    public void updaterows(){
        System.out.println("当前的页"+current);
        getPage();
        if (rows!=null&&rows.size()>=1) {
            int end=current*limit;
            List<Object> data=new ArrayList<Object>();
            if (end>rows.size()) {
                end=rows.size();
            }
            for (int i = (current-1)*limit; i <end; i++) {
                data.add(rows.get(i));
            }
            rows = data;
        }
    }
    public void updateOffestRows(){
        List<Object> list = new ArrayList<Object>();
        System.out.println("开始数据"+list+"结束数据"+limit+offset);
        long lastValue=limit+offset;
        if(lastValue>total){
            lastValue =total;
        }
        for (int i = offset; i <lastValue; i++) {

            list.add(rows.get(i));
        }
        rows=list;
    }
    @Override
    public String toString() {
        return "PageInfo [total=" + total + ", page=" + page + ", current="
                + current + ", limit=" + limit + ", rows=" + rows + ", start="
                + start + ", type=" + type + ", offset=" + offset + "]";
    }


}
