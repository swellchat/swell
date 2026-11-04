"""
Call test peer for the Mac (WebRTC via aiortc). Driven by the Kotlin test peer over stdin/stdout,
one JSON object per line:

  in  {"op":"offer","sdp":...,"turn":{...}}   -> out {"op":"answer","sdp":...}
  in  {"op":"call","turn":{...}}              -> out {"op":"offer","sdp":...}
  in  {"op":"answer","sdp":...}               (answer to our call)
  in  {"op":"hangup"}                         -> out {"op":"stats",...} and the recording is closed
Out lines are "ANSWER <base64 sdp>", "OFFER <base64 sdp>", "STATE {...}", "STATS {...}".

Sends a 440 Hz tone and records what the phone sends to RECORD (default callpeer-in.wav).
Test tool only; never shipped.
"""
import asyncio, base64, json, math, os, sys, time, fractions

import av
import numpy as np
from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
from aiortc.contrib.media import MediaRecorder
from aiortc.mediastreams import AudioStreamTrack

RECORD = os.environ.get("RECORD", "callpeer-in.wav")


class Tone(AudioStreamTrack):
    def __init__(self):
        super().__init__()
        self.t = 0

    async def recv(self):
        rate, n = 48000, 960
        if not hasattr(self, "start"):
            self.start = time.time()
        wait = self.start + self.t / rate - time.time()
        if wait > 0:
            await asyncio.sleep(wait)
        x = (np.sin(2 * math.pi * 440 * (np.arange(n) + self.t) / rate) * 8000).astype(np.int16)
        f = av.AudioFrame.from_ndarray(x.reshape(1, -1), format="s16", layout="mono")
        f.sample_rate = rate
        f.pts = self.t
        f.time_base = fractions.Fraction(1, rate)
        self.t += n
        return f


def out(o):
    # SDP goes out base64-encoded on its own line ("ANSWER <b64>" / "OFFER <b64>") for the Kotlin side.
    if o["op"] in ("answer", "offer"):
        line = o["op"].upper() + " " + base64.b64encode(o["sdp"].encode()).decode()
    else:
        line = o["op"].upper() + " " + json.dumps(o)
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


async def main():
    pc = None
    rec = None
    t0 = None
    loop = asyncio.get_event_loop()
    reader = asyncio.StreamReader()
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)

    def new_pc(turn):
        nonlocal rec
        servers = [RTCIceServer(urls=turn["urls"], username=turn["username"], credential=turn["password"])] if turn else []
        p = RTCPeerConnection(RTCConfiguration(iceServers=servers))
        p.addTrack(Tone())
        rec = MediaRecorder(RECORD)

        @p.on("track")
        def on_track(track):
            rec.addTrack(track)
            asyncio.ensure_future(rec.start())

        @p.on("connectionstatechange")
        async def on_state():
            out({"op": "state", "state": p.connectionState, "ms": int((time.time() - t0) * 1000)})

        return p

    while True:
        line = await reader.readline()
        if not line:
            break
        m = json.loads(line)
        if m["op"] in ("offer", "call"):
            t0 = time.time()
            pc = new_pc(m.get("turn"))
            if m["op"] == "offer":
                await pc.setRemoteDescription(RTCSessionDescription(m["sdp"], "offer"))
                await pc.setLocalDescription(await pc.createAnswer())
                out({"op": "answer", "sdp": pc.localDescription.sdp})
            else:
                await pc.setLocalDescription(await pc.createOffer())
                out({"op": "offer", "sdp": pc.localDescription.sdp})
        elif m["op"] == "answer" and pc:
            await pc.setRemoteDescription(RTCSessionDescription(m["sdp"], "answer"))
        elif m["op"] == "hangup" and pc:
            stats = await pc.getStats()
            got = {k: v for s in stats.values() for k, v in vars(s).items() if k in ("bytesReceived", "bytesSent", "packetsLost", "jitter")}
            pair = [s for s in stats.values() if getattr(s, "type", "") == "transport"]
            await rec.stop()
            await pc.close()
            out({"op": "stats", **got, "record": RECORD, "transports": len(pair)})
            pc = None


asyncio.run(main())
