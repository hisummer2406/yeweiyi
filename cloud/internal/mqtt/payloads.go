package mqtt

// RequestRecord mirrors bean/RequestRecord.java exactly (plain Gson, no @SerializedName, so JSON
// keys are the Java field names). The backend publishes this as the request body for
// requestAlarm/requestUnloadOil/requestShift/requestOilLeak/requestOilStorage. requestOilcan
// ignores its request body entirely (messageArrived just sets a flag regardless of payload), so no
// payload type is needed for it — an empty JSON object is published for that one.
type RequestRecord struct {
	Sort      bool   `json:"sort"`
	BeginTime string `json:"beginTime"`
	EndTime   string `json:"endTime"`
	Limit     int    `json:"limit"`
	Offset    int    `json:"offset"`
}

// deviceTimeLayout is the format device timestamps ("time" fields below) are actually written in —
// confirmed from DateUtils.getYYYMMddHHmmss() in the Android repo, which despite its name uses
// "yyyy-MM-dd HH:mm" (no seconds; the seconds-including format is commented out in that source).
// The string carries no timezone; assumed to be the device's local wall-clock time. Which timezone
// that actually is isn't documented anywhere in either repo — Asia/Shanghai is assumed as the only
// reasonable default for this product, not verified against a real device.
const deviceTimeLayout = "2006-01-02 15:04"

// AlarmPayload mirrors db/Alarm.java's Java field names — these differ from the Postgres column
// names in db/migrations (guanhao/jiezhi), which instead mirror the device's @Column DB
// annotations. Gson serializes by field name, so the wire format uses guanHao/jieZhi.
type AlarmPayload struct {
	ID      int    `json:"id"`
	Time    string `json:"time"`
	Info    string `json:"info"`
	GuanHao string `json:"guanHao"`
	JieZhi  string `json:"jieZhi"`
}

// OilInOutPayload mirrors db/OilInOut.java's field names, typos included (beginHeigth/endHeigth,
// beginVolum/endVolum are the device's actual Java field names, not transcription errors here).
type OilInOutPayload struct {
	ID          int     `json:"id"`
	OilID       int     `json:"oilId"`
	JieZhi      string  `json:"jieZhi"`
	Time        string  `json:"time"`
	BeginHeigth float64 `json:"beginHeigth"`
	EndHeigth   float64 `json:"endHeigth"`
	BeginVolum  float64 `json:"beginVolum"`
	EndVolum    float64 `json:"endVolum"`
	BeginWeight float64 `json:"beginWeight"`
	EndWeight   float64 `json:"endWeight"`
}

// CommitWorkPayload mirrors db/CommitWork.java's field names. Note the JSON key is "info", not the
// DB column name "oilinfogson" — those diverge on-device (Gson uses the field name, not the
// xUtils @Column annotation). `endTime` is reliably a real device-local timestamp on the live
// shift-close path (DBDataManager.CommitWork(int,String,SparseArray) -> setEndTime(curTime)) — a
// separate, unreachable method (saveCommitWorkRecord, never called anywhere) passes a user id into
// the endTime constructor argument by mistake, but since nothing calls it that bug can't reach real
// data.
type CommitWorkPayload struct {
	ID      int    `json:"id"`
	WorkID  int    `json:"workId"`
	Time    string `json:"time"`
	EndTime string `json:"endTime"`
	Info    string `json:"info"`
}

// NetOilCanPayload mirrors bean/NetOilCan.java, which OilcanDataManager.getNetOilcans() serializes
// for BOTH the LAN /baseOilcan HTTP endpoint and MQTT's requestOilcan response — despite the design
// doc §二 calling this "BaseOilCan" with a completely different field list (id/oilType/level/
// volume/weight/temple/oilRadio/waterRadio/connet/stateInfo). No BaseOilCan class exists anywhere
// in the Android repo; this struct was built directly from NetOilCan.java + the getters
// OilcanDataManager.getNetOilcans() actually populates, not from the doc's summary table.
// Serialized with fastjson (JSONObject.toJSONString), which (like Gson) uses JavaBean
// introspection — isConnect() maps to JSON key "connect" under fastjson's default naming, though
// this specific mapping wasn't verified against a running device.
type NetOilCanPayload struct {
	ID             int     `json:"id"`
	OilType        string  `json:"oiltype"`
	OilColor       int     `json:"oilColor"`
	OilLevel       float64 `json:"oilLevel"`
	WaterLevel     float64 `json:"waterLevel"`
	Temp           float64 `json:"temp"`
	OilVolume      float64 `json:"oilVolume"`
	WaterVolume    float64 `json:"waterVolume"`
	OilWeight      float64 `json:"oilWeight"`
	NullVolume     float64 `json:"nullVolume"`
	OilRadio       int     `json:"oilRadio"`
	WaterRadio     int     `json:"waterRadio"`
	AlarmState     string  `json:"alarmState"`
	ProbeAddress   string  `json:"probeAddress"`
	Connect        bool    `json:"connect"`
	ProbeType      string  `json:"probeType"`
	Address        string  `json:"address"`
	FatherAllProbe int     `json:"fatherAllProbe"`
	DeviceName     string  `json:"deviceName"`
}

// OnlineStatusPayload mirrors BackService.getOilneStateJson: {"isOline": true/false} — published
// retained on eventTopic(isOline) both as the connect-time announcement and as the MQTT Will.
type OnlineStatusPayload struct {
	IsOnline bool `json:"isOline"`
}

// MsgPayload mirrors the real-time alarm push body BackService.sendAlarms (the AIDL-facing one, not
// PushCallback's) constructs: {"msg": "<alarm text>"}.
type MsgPayload struct {
	Msg string `json:"msg"`
}
