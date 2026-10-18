#!/usr/bin/env python3
"""液位仪 Modbus TCP（端口 5020）连通性自测：分别用标准 Modbus TCP 和 RTU over TCP 读罐数据并解析。

用法: python3 tools/modbus_tcp_check.py <设备IP> [端口=5020] [罐数=2]
只依赖标准库。寄存器规则见 docs/协议/对外Modbus对接说明.docx。
"""
import socket
import struct
import sys

FIELDS = ["油高mm", "水高mm", "温度℃", "油水体积L", "油体积L", "水体积L"]


def crc16(b):
    c = 0xFFFF
    for x in b:
        c ^= x
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return bytes([c & 0xFF, c >> 8])


def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        part = s.recv(n - len(buf))
        if not part:
            raise ConnectionError("对端断开")
        buf += part
    return buf


def read_mbap(s, start, qty, tid=1):
    pdu = struct.pack(">BHH", 3, start, qty)
    s.sendall(struct.pack(">HHHB", tid, 0, len(pdu) + 1, 8) + pdu)
    head = recv_exact(s, 7)
    _, proto, length, _ = struct.unpack(">HHHB", head)
    body = recv_exact(s, length - 1)
    if body[0] & 0x80:
        raise RuntimeError(f"异常应答 功能码={body[0]:02X} 异常码={body[1]:02X}")
    return body[2:]


def read_rtu(s, start, qty):
    req = struct.pack(">BBHH", 8, 3, start, qty)
    s.sendall(req + crc16(req))
    head = recv_exact(s, 3)
    if head[1] & 0x80:
        recv_exact(s, 2)
        raise RuntimeError(f"异常应答 异常码={head[2]:02X}")
    data = recv_exact(s, head[2])
    crc = recv_exact(s, 2)
    if crc16(head + data) != crc:
        raise RuntimeError("CRC 校验失败")
    return data


def show(data, tanks):
    for t in range(tanks):
        vals = struct.unpack(">6f", data[t * 24:(t + 1) * 24])
        print(f"  {t + 1}号罐: " + "  ".join(f"{n}={v:.2f}" for n, v in zip(FIELDS, vals)))


def main():
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5020
    tanks = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    start, qty = 999, tanks * 12  # 1 号罐油高的报文地址为 999（0x03E7）
    for name, fn in (("标准 Modbus TCP", read_mbap), ("RTU over TCP", read_rtu)):
        with socket.create_connection((host, port), timeout=3) as s:
            s.settimeout(3)
            print(f"[{name}] {host}:{port}")
            show(fn(s, start, qty), tanks)


if __name__ == "__main__":
    main()
