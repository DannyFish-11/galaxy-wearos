"""端到端测试里手表 App 的替身:照 App 的样子连 ws://127.0.0.1:<转发口>/ws/device/<id>。"""
import asyncio
import sys

import websockets


async def main(url):
    async with websockets.connect(url, open_timeout=20) as ws:
        for i in range(3):
            await ws.send(f"ping-{i}")
            got = await asyncio.wait_for(ws.recv(), 10)
            want = f"/ws/device/watch-1|ping-{i}"
            assert got == want, (got, want)
        # 大一点的一帧,确认不是只有小包能过
        big = "x" * 200_000
        await ws.send(big)
        got = await asyncio.wait_for(ws.recv(), 20)
        assert got.endswith(big), len(got)
    print("E2E_OK")


asyncio.run(main(sys.argv[1]))
