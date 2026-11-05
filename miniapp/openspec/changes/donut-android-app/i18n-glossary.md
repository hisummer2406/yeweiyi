# 术语表（中 / 英 / 西）

西班牙语由 Claude 翻译，**尚未经过熟悉行业术语的人核对**（issue #12 已确认：先用，上线后再改）。
下表列出最需要核对的行业词；改词时直接改 `miniprogram/locales/es.ts` 里对应的键，`npm run check` 会检查三种语言的键与占位符是否一致。

| 中文 | English | Español（待核对） | 说明 |
| --- | --- | --- | --- |
| 油罐 / 储罐 | tank | tanque | 西语里的“油”我用了 combustible（燃料）而不是 aceite（食用/润滑油），例如“高油温”→ temperatura alta del combustible；若设备实际装的是别的介质，请改 |
| 液位 | level | nivel | |
| 水高 / 水位 | water (level) | agua / nivel de agua | |
| 空高 | ullage | espacio vacío | 英文用行业词 ullage；西语如有通行词请替换 |
| 容积 | volume | volumen | |
| 质量 | mass | masa | |
| 罐表 | tank chart | tabla de aforo | 行业里也叫容积表；西语 tabla de aforo 是我的判断 |
| 探针 | probe | sonda | |
| 卧罐 / 立罐 | horizontal / vertical tank | tanque horizontal / vertical | |
| 高高液位 / 低低液位 | high-high / low-low level | nivel muy alto / muy bajo | |
| 溢罐风险 | overfill risk | riesgo de desbordamiento | |
| 疑似泄漏 | suspected leak | posible fuga | |
| 入库 / 出库 | stock in / stock out | entrada / salida | 页面里 进油/出油 也用同一对词 |
| 出入库记录 | stock in/out records | registros de entradas y salidas | |
| 交班 | shift handover | turno | 目前只在“暂未开放”的入口里出现 |
| 油站 | station | estación | 完整说法 gas station / estación de servicio；界面里只用 station / estación |
| 控制命令 | control command | comando de control | |
| 二次确认 | second confirmation | segunda confirmación | |
| 消音 | mute | silenciar | |
| 设备联锁 | device interlock | enclavamiento del dispositivo | |
| 报警状态 报警中 / 已恢复 | active / cleared | activa / resuelta | |
| 严重程度 紧急 / 重要 / 次要 / 警告 / 未定级 | critical / major / minor / warning / indeterminate | crítica / importante / menor / advertencia / indeterminada | 对应云端的 CRITICAL/MAJOR/MINOR/WARNING/INDETERMINATE |
| 平台管理员 / 客户管理员 / 客户普通用户 | platform administrator / customer administrator / customer staff | administrador de la plataforma / administrador del cliente / personal del cliente | |
| 命令状态 待下发…已过期 | pending … expired | pendiente … caducado | 见 `cmdStatus.*` |

## 已知的语言上的取舍

- 时间格式：中文、英文 `YYYY-MM-DD HH:mm:ss`，西语 `DD/MM/YYYY HH:mm:ss`（`utils/format.ts`）。
- 数字：小数点沿用现状（点），不做千分位和逗号小数本地化（design.md Open Questions 第 7 条，待人确认）。
- 设备或云端自己产生的文字（报警说明 `msg`、命令的 `errorReason`）原样显示，不翻译；界面自己的文字才走词表。
- 语言自称（`中文` / `English` / `Español`）固定不翻译，方便找不到自己语言的人识别。
