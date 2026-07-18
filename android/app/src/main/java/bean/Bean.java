package bean;

import com.sansen.yeweiyi.bean.AlarmRecord;
import com.sansen.yeweiyi.bean.OilRecord;
import com.sansen.yeweiyi.bean.OutOilRecord;

public class Bean {
    private String defaultName;
    private OilRecord tankData;
    private String productId;
    private Long uploadDate;
    private AlarmRecord alarmRecord;
    private OutOilRecord equipmentRecord;

    public AlarmRecord getAlarmRecord() {
        return alarmRecord;
    }

    public void setAlarmRecord(AlarmRecord alarmRecord) {
        this.alarmRecord = alarmRecord;
    }

    public OutOilRecord getEquipmentRecord() {
        return equipmentRecord;
    }

    public void setEquipmentRecord(OutOilRecord equipmentRecord) {
        this.equipmentRecord = equipmentRecord;
    }

    public String getDefaultName() {
        return defaultName;
    }

    public void setDefaultName(String defaultName) {
        this.defaultName = defaultName;
    }

    public OilRecord getTankData() {
        return tankData;
    }

    public void setTankData(OilRecord tankData) {
        this.tankData = tankData;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public Long getUploadDate() {
        return uploadDate;
    }

    public void setUploadDate(Long uploadDate) {
        this.uploadDate = uploadDate;
    }
}
