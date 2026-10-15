#!/usr/bin/env python3
"""A stand-in for the Gemini Live API, held to Google's own SDK (google-genai): every message the phone sends is
validated as the SDK's LiveClientMessage (camelCase, no unknown fields), every message sent back is built from the SDK's
LiveServerMessage — so the phone speaks and reads the protocol exactly as the SDK does.

  setup-ref [manual]   the setup JSON the SDK makes for the phone's configuration (to compare with GeminiLive.setup);
                       «manual»: the phone tells where speech starts and ends, the model answers without thinking
  serve PORT LOG       a server on 127.0.0.1:PORT: the models list (HTTP, two pages), the Live WebSocket — the API key
                       checked, the setup taken, then per turn of speech: what was heard, a function call (first turn),
                       the answer's voice (24 kHz) with its text, «turn complete». A turn: a second of speech or «the
                       stream paused» (the server finding the speech), or «activity end» (the client telling it, when
                       the setup turned the server's finding off); an answer is cut short by half a second of speech —
                       or by «activity start». Every client message to LOG (jsonl).
"""
import asyncio
import base64
import json
import math
import sys

from google import genai
from google.genai import types

KEY = "test-key"
PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

# the configuration the phone sets up with (app/Talk): the same in the SDK's terms
FUNCTIONS = [
    {"name": "save_note", "description": "Сохранить заметку",
     "parameters": {"type": "OBJECT", "properties": {"text": {"type": "STRING", "description": "Текст"}}, "required": ["text"]}},
]


# a model that takes no thinking config (the phone's way back without it is tested)
REFUSE_THINKING = {"models/gemini-3-flash-native-audio-preview-2026-03-02"}


def sdk_config_manual():
    return types.LiveConnectConfig(
        response_modalities=["AUDIO"],
        speech_config=types.SpeechConfig(voice_config=types.VoiceConfig(prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name="Kore"))),
        thinking_config=types.ThinkingConfig(thinking_budget=0),
        system_instruction="Ты голосовой помощник.",
        tools=[types.Tool(function_declarations=[types.FunctionDeclaration(**f) for f in FUNCTIONS])],
        realtime_input_config=types.RealtimeInputConfig(automatic_activity_detection=types.AutomaticActivityDetection(disabled=True)),
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
    )


def sdk_config():
    return types.LiveConnectConfig(
        response_modalities=["AUDIO"],
        speech_config=types.SpeechConfig(voice_config=types.VoiceConfig(prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name="Kore"))),
        system_instruction="Ты голосовой помощник.",
        tools=[types.Tool(function_declarations=[types.FunctionDeclaration(**f) for f in FUNCTIONS])],
        realtime_input_config=types.RealtimeInputConfig(automatic_activity_detection=types.AutomaticActivityDetection(
            silence_duration_ms=500, end_of_speech_sensitivity=types.EndSensitivity.END_SENSITIVITY_HIGH)),
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
    )


async def setup_ref(manual=False):
    from google.genai import _common, _live_converters as lc
    from google.genai.live import _t_live_connect_config
    client = genai.Client(api_key="x")
    api = client._api_client
    model = "models/gemini-test-native-audio"
    param = await _t_live_connect_config(api, sdk_config_manual() if manual else sdk_config())
    d = _common.convert_to_dict(lc._LiveConnectParameters_to_mldev(
        api_client=api, from_object=types.LiveConnectParameters(model=model, config=param).model_dump(exclude_none=True)))
    del d["config"]
    d = _common.encode_unserializable_types(d)
    d["setup"]["model"] = model
    print(json.dumps(d, ensure_ascii=False))


def out(msg):
    return msg.model_dump_json(by_alias=True, exclude_none=True)


def loud(d):
    """Speech, not silence (a microphone that is always on — a headset — sends silence too)."""
    n = len(d) // 2
    rms = math.sqrt(sum(int.from_bytes(d[2 * i:2 * i + 2], "little", signed=True) ** 2 for i in range(n)) / max(1, n))
    return rms > 300


def tone(seconds, rate=24000, f=440.0):
    n = int(seconds * rate)
    b = bytearray()
    for i in range(n):
        v = int(8000 * math.sin(2 * math.pi * f * i / rate))
        b += v.to_bytes(2, "little", signed=True)
    return bytes(b)


async def serve(port, log_path):
    from websockets.asyncio.server import serve as ws_serve
    from websockets.http11 import Response
    from websockets.datastructures import Headers

    log = open(log_path, "w", encoding="utf-8")

    def process_request(connection, request):
        if request.path.startswith("/v1beta/models"):
            if request.headers.get("x-goog-api-key") != KEY:
                body = json.dumps({"error": {"code": 400, "message": "API key not valid. Please pass a valid API key."}}).encode()
                return Response(400, "Bad Request", Headers([("Content-Type", "application/json")]), body)
            if "pageToken=p2" in request.path:
                models = [{"name": "models/gemini-2.5-flash-native-audio-preview-09-2025", "supportedGenerationMethods": ["countTokens", "bidiGenerateContent"]},
                          {"name": "models/gemini-3-flash-live-preview", "supportedGenerationMethods": ["bidiGenerateContent"]}]
                body = {"models": models}
            else:
                models = [{"name": "models/gemini-2.5-flash", "supportedGenerationMethods": ["generateContent", "countTokens"]},
                          {"name": "models/gemini-live-2.5-flash-preview", "supportedGenerationMethods": ["bidiGenerateContent"]},
                          {"name": "models/gemini-3-flash-native-audio-preview-2026-03-02", "supportedGenerationMethods": ["bidiGenerateContent"]}]
                body = {"models": models, "nextPageToken": "p2"}
            return Response(200, "OK", Headers([("Content-Type", "application/json")]), json.dumps(body).encode())
        if request.path != PATH:
            return Response(404, "Not Found", Headers(), b"no such path")
        return None

    async def handler(ws):
        if ws.request.headers.get("x-goog-api-key") != KEY:
            await ws.close(1008, "API key not valid. Please pass a valid API key.")
            return
        # the session: speech heard since the last answer (the server finding it), the client's activity (when it tells)
        st = {"audio": 0, "answered_at": 0, "turns": 0, "pending_tool": None, "manual": False, "active": False, "act_audio": 0}

        async def take(text):
            """One client message: validated, the state moved on; what it means — "turn", "speech" (a start of activity), None."""
            log.write(text + "\n")
            log.flush()
            try:
                d = json.loads(text)
                setup = d.get("setup")
                # the SDK sends realtimeInputConfig in the setup (its converter) though its LiveClientSetup type lacks it
                rc = setup.pop("realtimeInputConfig", None) if setup is not None else None
                if rc is not None:
                    rc = types.RealtimeInputConfig.model_validate(rc)
                msg = types.LiveClientMessage.model_validate(d)
            except Exception as e:  # the phone sent what the SDK would not
                await ws.close(1007, ("invalid message: " + str(e))[:120])
                return "closed"
            if msg.setup is not None:
                gc = msg.setup.generation_config
                if gc is not None and gc.thinking_config is not None and msg.setup.model in REFUSE_THINKING:
                    await ws.close(1007, "Request contains an invalid argument: thinking is not supported by this model")
                    return "closed"
                st["manual"] = bool(rc and rc.automatic_activity_detection and rc.automatic_activity_detection.disabled)
                await ws.send(out(types.LiveServerMessage(setup_complete=types.LiveServerSetupComplete())))
                return None
            if msg.tool_response is not None:
                fr = msg.tool_response.function_responses[0]
                if st["pending_tool"] is None or fr.id != st["pending_tool"]:
                    await ws.close(1007, "unexpected tool response")
                    return "closed"
                st["pending_tool"] = None
                return ("tool", (fr.response or {}).get("result", ""))
            ri = msg.realtime_input
            if ri is None:
                return None
            if (ri.activity_start is not None or ri.activity_end is not None) and not st["manual"]:
                await ws.close(1007, "activity signals need automatic activity detection disabled")
                return "closed"
            if ri.audio_stream_end and st["manual"]:
                await ws.close(1007, "audioStreamEnd is for automatic activity detection only")
                return "closed"
            if ri.audio is not None:
                if ri.audio.mime_type != "audio/pcm;rate=16000":
                    await ws.close(1007, "unexpected audio format " + str(ri.audio.mime_type))
                    return "closed"
                if st["manual"]:
                    if st["active"]:
                        st["act_audio"] += len(ri.audio.data)
                elif loud(ri.audio.data):
                    st["audio"] += len(ri.audio.data)
            if ri.activity_start is not None:
                st["active"] = True
                st["act_audio"] = 0
                return "speech"
            if ri.activity_end is not None:
                was = st["active"] and st["act_audio"] > 0
                st["active"] = False
                return "turn" if was else None
            # the server finding the speech: a second of it since the last answer, or the stream paused
            if not st["manual"] and (st["audio"] - st["answered_at"] >= 32000 or ri.audio_stream_end) and st["audio"] > st["answered_at"]:
                st["answered_at"] = st["audio"]
                return "turn"
            return None

        async def turn():
            st["turns"] += 1
            n = st["turns"]
            await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(
                input_transcription=types.Transcription(text="Напомни завтра в 9 " if n == 1 else "Как дела?")))))
            if n == 1:
                await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(
                    input_transcription=types.Transcription(text="купить хлеб")))))
                st["pending_tool"] = "call-1"
                await ws.send(out(types.LiveServerMessage(tool_call=types.LiveServerToolCall(function_calls=[
                    types.FunctionCall(id="call-1", name="save_note", args={"text": "Напомни завтра в 9 купить хлеб"})]))))
            else:
                await answer(n)

        async def answer(n):
            pcm = tone(0.9)
            third = len(pcm) // 3 // 2 * 2
            words = ["Готово, ", "напомню завтра ", "в девять."] if n == 1 else ["Всё ", "хорошо, ", "спасибо!"]
            for i in range(3):
                part = pcm[i * third:(i + 1) * third] if i < 2 else pcm[2 * third:]
                await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(
                    model_turn=types.Content(role="model", parts=[types.Part(inline_data=types.Blob(mime_type="audio/pcm;rate=24000", data=part))])))))
                await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(output_transcription=types.Transcription(text=words[i])))))
                if i == 0:
                    # spoken over? (a phone that passes the voice on all the time — headphones, a headset): half a
                    # second of speech while the answer has begun, or the client's «activity start», stops it, as the
                    # real server does
                    spoken, cut = 0, False
                    end = asyncio.get_event_loop().time() + 1.5
                    while not cut:
                        left = end - asyncio.get_event_loop().time()
                        if left <= 0:
                            break
                        try:
                            raw = await asyncio.wait_for(ws.recv(), left)
                        except asyncio.TimeoutError:
                            break
                        text = raw if isinstance(raw, str) else raw.decode()
                        before = st["audio"]
                        what = await take(text)
                        if what == "closed":
                            return
                        if st["manual"]:
                            cut = what == "speech"
                        else:
                            spoken += st["audio"] - before
                            st["answered_at"] = st["audio"]  # what interrupts is not a turn of its own
                            cut = spoken >= 16000
                    if cut:
                        await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(interrupted=True))))
                        await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(turn_complete=True))))
                        return
            await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(generation_complete=True))))
            await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(turn_complete=True)),))

        async for raw in ws:
            what = await take(raw if isinstance(raw, str) else raw.decode())
            if what == "closed":
                return
            if what == "turn":
                await turn()
            elif isinstance(what, tuple):
                await answer(st["turns"])

    async with ws_serve(handler, "127.0.0.1", port, process_request=process_request, max_size=2 ** 24):
        print("ready", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    if sys.argv[1] == "setup-ref":
        asyncio.run(setup_ref(len(sys.argv) > 2 and sys.argv[2] == "manual"))
    else:
        asyncio.run(serve(int(sys.argv[2]), sys.argv[3]))
