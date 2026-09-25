"""端到端测试里电脑上"网关"的替身:一个 WebSocket 回声服务,路径照网关的 /ws/device/{id}。"""
import asyncio
import sys

import websockets


async def handler(ws):
    path = ws.request.path
    async for msg in ws:
        await ws.send(f"{path}|{msg}")


async def main(port):
    async with websockets.serve(handler, "127.0.0.1", port):
        await asyncio.Future()


asyncio.run(main(int(sys.argv[1])))
