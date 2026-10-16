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
            line = stream.readline(262145)
            if not line.endswith(b"\n") or len(line) > 262144:
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
    parser = argparse.ArgumentParser(description="DiPlay TCP 调试，车机与本机需在同一局域网")
    parser.add_argument("--host", required=True, help="车机首页显示的 LAN IPv4")
    parser.add_argument("--port", type=int, default=8721, help="以 APP 显示的实际端口为准")
    parser.add_argument("--token", default=os.environ.get("DIPLAY_DEBUG_TOKEN"), help="首页口令或 DIPLAY_DEBUG_TOKEN 环境变量")
    parser.add_argument("--timeout", type=float, default=45)
    commands = parser.add_subparsers(dest="cmd", required=True)
    commands.add_parser("status")
    video = commands.add_parser("video-settings", help="读取或修改视频设置，下次连接生效")
    video.add_argument("--fps", type=int, choices=[30, 60])
    video.add_argument("--resolution-percent", type=int, help="30..160，下次连接生效")
    video.add_argument("--low-latency-decoder", choices=["ON", "OFF"])
    video.add_argument("--smooth-video", choices=["ON", "OFF"])
    video.add_argument("--direct-video-output", choices=["ON", "OFF"])
    tap = commands.add_parser("video-tap", help="点击当前CarPlay视频，归一化坐标0..1")
    tap.add_argument("--x", type=float, required=True)
    tap.add_argument("--y", type=float, required=True)
    gesture = commands.add_parser("video-gesture", help="CarPlay主屏连续12次往返滑动或缩放；先显示地图页面")
    gesture.add_argument("--kind", choices=["SWIPE", "PINCH"], default="SWIPE")
    commands.add_parser("network", help="读取 IPv6/TUN 内核计数、路由与可读防火墙规则")
    commands.add_parser("runtime", help="读取本进程CPU、线程及Logcat；不执行远程shell")
    commands.add_parser("system-load", help="只读系统进程CPU计数、温度及解码能力")
    for name in ("async-session-logs", "freescale-reorder", "audio-clock-trim"):
        item = commands.add_parser(name)
        item.add_argument("--mode", choices=["ON", "OFF"], required=True)
    coalescing = commands.add_parser("touch-coalescing", help="仅已知车机：相邻未发送MOVE合并对照")
    coalescing.add_argument("--mode", choices=["ON", "OFF"], required=True)
    poll = commands.add_parser("lwip-poll-wait", help="仅已知车机：接收等待1/2/4ms对照")
    poll.add_argument("--millis", type=int, choices=[1, 2, 4], required=True)
    priority = commands.add_parser("video-priority", help="同连接视频输入线程优先级对比")
    priority.add_argument("--mode", choices=["ON", "OFF"], required=True)
    crypto = commands.add_parser("native-crypto", help="仅用于同连接加解密性能对照，重启恢复默认")
    crypto.add_argument("--mode", choices=["ON", "OFF"], required=True)
    snapshot = commands.add_parser("video-snapshot", help="读取当前CarPlay视频Surface的PNG，非系统截图")
    snapshot.add_argument("--output", default="carplay-surface.png")
    batch = commands.add_parser("usb-tx-batch", help="实时切换NCM已排队数据聚合上限，1为原模式")
    batch.add_argument("--frames", type=int, choices=[1, 4, 8], required=True)
    commands.add_parser("usb-reset", help="会话完全停止后，通过已授权FD复位唯一iPhone；随后重新查询状态")
    commands.add_parser("start-wired", help="停止旧会话后启动有线 CarPlay")
    commands.add_parser("stop", help="停止 CarPlay，保留 TCP 服务")
    commands.add_parser("retry-vpn", help="手动 ADB 授权后，重新检查 VPN 并继续启动")
    mode = commands.add_parser("usb-mode", help="下次连接生效；切换前先 stop")
    mode.add_argument("--mode", choices=["AUTO", "ON", "OFF"], required=True)
    configuration = commands.add_parser("usb-configuration", help="先完全 stop；REINITIALIZE=lwip11 对照，REUSE=相同配置复用实验")
    configuration.add_argument("--mode", choices=["REINITIALIZE", "REUSE"], required=True)
    toast = commands.add_parser("toast", help="在车机提示操作，实际显示为异步")
    toast.add_argument("--text", default="DiPlay 调试：请执行一次交互（滑动/点击）")
    backend = commands.add_parser("network-backend", help="切换用户态 lwIP/原 VPN，下一次连接生效")
    backend.add_argument("--mode", choices=["LWIP", "VPN"], required=True)
    diagnostics = commands.add_parser("lwip-diagnostics", help="立即切换 lwIP 聚合计时，用于观测开销对照")
    diagnostics.add_argument("--mode", choices=["ON", "OFF"], required=True)
    phone = commands.add_parser("phone-diagnostics", help="默认关闭；临时采集iPhone日志会占用CPU和USB")
    phone.add_argument("--mode", choices=["ON", "OFF"], required=True)
    logs = commands.add_parser("logs")
    logs.add_argument("--follow", action="store_true")
    logs.add_argument("--source", choices=["session", "adapter"], default="session")
    logs.add_argument("--offset", type=int, default=0)
    export = commands.add_parser("export")
    export.add_argument("--output", default="diplay-report.zip")
    update = commands.add_parser("update")
    update.add_argument("apk")
    args = parser.parse_args()
    if not args.token:
        parser.error("请通过 --token 或 DIPLAY_DEBUG_TOKEN 指定首页口令")
    try:
        if args.cmd == "logs":
            offset = args.offset
            decoder = __import__("codecs").getincrementaldecoder("utf-8")(errors="replace")
            while True:
                reply, body = request(args, "logs", offset=offset, source=args.source)
                if reply["offset"] < offset:
                    decoder.reset()
                sys.stdout.write(decoder.decode(body, final=not args.follow))
                sys.stdout.flush()
                offset = reply["offset"]
                if not args.follow:
                    break
                time.sleep(1)
        else:
            fields = {"mode": args.mode} if args.cmd in ("usb-mode", "usb-configuration", "network-backend", "lwip-diagnostics", "phone-diagnostics", "native-crypto", "video-priority", "touch-coalescing", "async-session-logs", "freescale-reorder", "audio-clock-trim") else {}
            if args.cmd == "lwip-poll-wait": fields = {"millis": args.millis}
            if args.cmd == "video-gesture": fields = {"kind": args.kind}
            if args.cmd == "video-tap": fields = {"x": args.x, "y": args.y}
            if args.cmd == "toast": fields = {"text": args.text}
            if args.cmd == "usb-tx-batch": fields = {"frames": args.frames}
            if args.cmd == "video-settings":
                if args.fps is not None: fields["fps"] = args.fps
                if args.resolution_percent is not None: fields["resolutionPercent"] = args.resolution_percent
                for attr, key in [("low_latency_decoder", "lowLatencyDecoder"), ("smooth_video", "smoothVideo"),
                                  ("direct_video_output", "directVideoOutput")]:
                    if getattr(args, attr) is not None: fields[key] = getattr(args, attr) == "ON"
            reply, body = request(args, args.cmd, **fields)
            if args.cmd == "video-snapshot":
                Path(args.output).write_bytes(body)
                print(f"已保存 {Path(args.output).resolve()} ({len(body)} bytes)")
            elif args.cmd in ("network", "runtime", "system-load"):
                print(body.decode("utf-8", errors="replace"))
            elif args.cmd != "export":
                print(json.dumps(reply, ensure_ascii=False, indent=2))
    except KeyboardInterrupt:
        pass
    except (OSError, ValueError, RuntimeError) as error:
        print(f"失败: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
