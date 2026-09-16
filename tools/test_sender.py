#!/usr/bin/env python3
"""
AgentRing-Android 蓝牙/测试数据推送脚本
用于向手机副屏模拟发送配额剩余数据
"""

import sys
import time
import json
import socket
import argparse

def generate_mock_payload(num_providers=3):
    providers = []
    
    # 1. Codex
    if num_providers >= 1:
        providers.append({
            "id": "codex",
            "name": "Codex",
            "primary": {
                "label": "5小时窗口",
                "remainingPercent": 82.0,
                "resetsAt": "2小时 18分",
                "remainingDetails": "82%"
            },
            "secondary": {
                "label": "7天窗口",
                "remainingPercent": 94.0,
                "remainingDetails": "94%"
            },
            "extraInfo": "Credits 余额: $24.50"
        })
    
    # 2. Cursor
    if num_providers >= 2:
        providers.append({
            "id": "cursor",
            "name": "Cursor",
            "primary": {
                "label": "Included 额度",
                "remainingPercent": 68.0,
                "resetsAt": "10天后",
                "remainingDetails": "340 / 500"
            },
            "secondary": {
                "label": "API 模型池",
                "remainingPercent": 100.0,
                "remainingDetails": "100%"
            },
            "extraInfo": "On-demand: $15.00 可用"
        })
        
    # 3. Antigravity
    if num_providers >= 3:
        providers.append({
            "id": "antigravity",
            "name": "Antigravity",
            "primary": {
                "label": "Gemini 5小时",
                "remainingPercent": 90.0,
                "resetsAt": "3小时 40分",
                "remainingDetails": "90%"
            },
            "secondary": {
                "label": "Gemini 周级",
                "remainingPercent": 75.0,
                "remainingDetails": "75%"
            },
            "extraInfo": "Claude/GPT: 100% 剩余"
        })

    return {
        "timestamp": int(time.time()),
        "providers": providers
    }

def main():
    parser = argparse.ArgumentParser(description="AgentRing-Android 数据测试工具")
    parser.add_argument("--providers", type=int, default=3, choices=[1, 2, 3], help="配置的 Provider 数量 (1, 2, 或 3)")
    parser.add_argument("--device", type=str, help="蓝牙串口设备路径 (如 /dev/tty.AgentRing-xxx 或 RFCOMM 地址)")
    parser.add_argument("--loop", action="store_true", help="循环定期更新模拟数据")
    parser.add_argument("--interval", type=int, default=5, help="循环更新间隔秒数")
    parser.add_argument("--dump-json", action="store_true", help="直接打印生成的 JSON 数据包")

    args = parser.parse_args()

    if args.dump_json:
        payload = generate_mock_payload(args.providers)
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return

    print("AgentRing-Android 测试数据生成器")
    print(f"模式: {args.providers} 个 Provider 剩余额度")

    if args.device:
        print(f"正在打开串口/蓝牙设备: {args.device}")
        try:
            with open(args.device, "w", encoding="utf-8") as f:
                while True:
                    payload = generate_mock_payload(args.providers)
                    line = json.dumps(payload, ensure_ascii=False) + "\n"
                    f.write(line)
                    f.flush()
                    print(f"[{time.strftime('%H:%M:%S')}] 已发送数据包: {line.strip()}")
                    if not args.loop:
                        break
                    time.sleep(args.interval)
        except Exception as e:
            print(f"写入设备失败: {e}", file=sys.stderr)
    else:
        payload = generate_mock_payload(args.providers)
        line = json.dumps(payload, ensure_ascii=False)
        print("\n当前生成的数据行 (单行 JSON Lines):")
        print(line)
        print("\n提示: 电脑与手机通过蓝牙配对后，agentRing macOS 端将自动推送。或使用 --device 写入 /dev/cu.AgentRing-* 测试。")

if __name__ == "__main__":
    main()
