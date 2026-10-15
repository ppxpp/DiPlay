#!/usr/bin/env python3
"""LAN client: one JSON header, then optional length-delimited binary payload."""
import argparse
import json
import os
from pathlib import Path
import socket
import sys
import time


def read_exact(stream, count):
    while count:
        data = stream.read(min(count, 65536))
        if not data:
            raise ConnectionError("连接提前关闭，数据不完整")
        yield data
        count -= len(data)


def request(args, cmd, **fields):
    header = {"cmd": cmd, "token": args.token, **fields}
    upload = args.apk if cmd == "update" else None
    if upload:
        header["size"] = Path(upload).stat().st_size
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        sock.settimeout(args.timeout)
        sock.sendall((json.dumps(header) + "\n").encode())
        if upload:
            with open(upload, "rb") as source:
                while chunk := source.read(65536):
                    sock.sendall(chunk)
        with sock.makefile("rb") as stream:
            line = stream.readline(16385)
            if not line.endswith(b"\n") or len(line) > 16384:
                raise ConnectionError("未收到有效 APP 响应（检查真实 LAN 路由、端口和 TUN）")
            reply = json.loads(line)
            if not reply.get("ok"):
                raise RuntimeError(reply.get("error", "操作失败"))
            size = reply.get("size", 0)
            if cmd == "export":
                target = Path(args.output)
                with target.open("wb") as output:
                    for chunk in read_exact(stream, size):
                        output.write(chunk)
                print(f"已保存 {target.resolve()} ({size} bytes)")
                return reply, b""
            body = b"".join(read_exact(stream, size))
            return reply, body


def main():
    parser = argparse.ArgumentParser(description="usbcheck TCP 调试，车机与本机需在同一局域网")
    parser.add_argument("--host", required=True, help="车机首页显示的 LAN IPv4")
    parser.add_argument("--port", type=int, default=9911, help="以 APP 显示的实际端口为准")
    parser.add_argument("--token", default=os.environ.get("USBCHECK_TOKEN"), help="首页口令或 USBCHECK_TOKEN 环境变量")
    parser.add_argument("--timeout", type=float, default=45)
    commands = parser.add_subparsers(dest="cmd", required=True)
    commands.add_parser("status")
    modes = ["USB_ONLY", "NEGOTIATE", "DETACH_NEGOTIATE", "NATIVE_NEGOTIATE", "DISCONNECT_NEGOTIATE", "LOW_POWER_DISCONNECT", "MFI_AUTH"]
    run = commands.add_parser("run")
    run.add_argument("--mode", choices=modes, default="NEGOTIATE")
    arm = commands.add_parser("arm", help="once: start locally on the next Apple USB attach")
    arm.add_argument("--mode", choices=modes, default="LOW_POWER_DISCONNECT")
    commands.add_parser("stop")
    commands.add_parser("vpn-demo", help="请求车机 VPN 授权并执行最小 TUN UDP 往返验证")
    logs = commands.add_parser("logs")
    logs.add_argument("--follow", action="store_true")
    logs.add_argument("--offset", type=int, default=0)
    export = commands.add_parser("export")
    export.add_argument("--output", default="usbcheck-report.zip")
    update = commands.add_parser("update")
    update.add_argument("apk")
    args = parser.parse_args()
    if not args.token:
        parser.error("请通过 --token 或 USBCHECK_TOKEN 指定首页口令")
    try:
        if args.cmd == "logs":
            offset = args.offset
            decoder = __import__("codecs").getincrementaldecoder("utf-8")(errors="replace")
            while True:
                reply, body = request(args, "logs", offset=offset)
                if reply["offset"] < offset:
                    decoder.reset()
                sys.stdout.write(decoder.decode(body, final=not args.follow))
                sys.stdout.flush()
                offset = reply["offset"]
                if not args.follow:
                    break
                time.sleep(1)
        else:
            fields = {"mode": args.mode} if args.cmd in ("run", "arm") else {}
            reply, _ = request(args, args.cmd, **fields)
            if args.cmd != "export":
                print(json.dumps(reply, ensure_ascii=False, indent=2))
    except KeyboardInterrupt:
        pass
    except (OSError, ValueError, RuntimeError) as error:
        print(f"失败: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
