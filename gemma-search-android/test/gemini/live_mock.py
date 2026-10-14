#!/usr/bin/env python3
"""A stand-in for the Gemini Live API, held to Google's own SDK (google-genai): every message the phone sends is
validated as the SDK's LiveClientMessage (camelCase, no unknown fields), every message sent back is built from the SDK's
LiveServerMessage — so the phone speaks and reads the protocol exactly as the SDK does.

  setup-ref            the setup JSON the SDK makes for the phone's configuration (to compare with GeminiLive.setup)
  serve PORT LOG       a server on 127.0.0.1:PORT: the models list (HTTP, two pages), the Live WebSocket — the API key
                       checked, the setup taken, then per turn of speech: what was heard, a function call (first turn),
                       the answer's voice (24 kHz) with its text, «turn complete». Every client message to LOG (jsonl).
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


async def setup_ref():
    from google.genai import _common, _live_converters as lc
    from google.genai.live import _t_live_connect_config
    client = genai.Client(api_key="x")
    api = client._api_client
    model = "models/gemini-test-native-audio"
    param = await _t_live_connect_config(api, sdk_config())
    d = _common.convert_to_dict(lc._LiveConnectParameters_to_mldev(
        api_client=api, from_object=types.LiveConnectParameters(model=model, config=param).model_dump(exclude_none=True)))
    del d["config"]
    d = _common.encode_unserializable_types(d)
    d["setup"]["model"] = model
    print(json.dumps(d, ensure_ascii=False))


def out(msg):
    return msg.model_dump_json(by_alias=True, exclude_none=True)


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
        audio = 0
        turns = 0
        answered_at = 0
        pending_tool = None
        async for raw in ws:
            text = raw if isinstance(raw, str) else raw.decode()
            log.write(text + "\n")
            log.flush()
            try:
                d = json.loads(text)
                # the SDK sends realtimeInputConfig in the setup (its converter) though its LiveClientSetup type lacks it
                rc = d.get("setup", {}).pop("realtimeInputConfig", None)
                if rc is not None:
                    types.RealtimeInputConfig.model_validate(rc)
                msg = types.LiveClientMessage.model_validate(d)
            except Exception as e:  # the phone sent what the SDK would not
                await ws.close(1007, ("invalid message: " + str(e))[:120])
                return
            if msg.setup is not None:
                await ws.send(out(types.LiveServerMessage(setup_complete=types.LiveServerSetupComplete())))
                continue
            if msg.tool_response is not None:
                fr = msg.tool_response.function_responses[0]
                if pending_tool is None or fr.id != pending_tool:
                    await ws.close(1007, "unexpected tool response")
                    return
                pending_tool = None
                await answer(ws, turns, (fr.response or {}).get("result", ""))
                continue
            ri = msg.realtime_input
            if ri is None:
                continue
            if ri.audio is not None:
                audio += len(ri.audio.data)
                if ri.audio.mime_type != "audio/pcm;rate=16000":
                    await ws.close(1007, "unexpected audio format " + str(ri.audio.mime_type))
                    return
            # a turn: a second of voice since the last answer, or the stream paused
            if (audio - answered_at >= 32000 or ri.audio_stream_end) and audio > answered_at:
                answered_at = audio
                turns += 1
                await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(
                    input_transcription=types.Transcription(text="Напомни завтра в 9 " if turns == 1 else "Как дела?")))))
                if turns == 1:
                    await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(
                        input_transcription=types.Transcription(text="купить хлеб")))))
                    pending_tool = "call-1"
                    await ws.send(out(types.LiveServerMessage(tool_call=types.LiveServerToolCall(function_calls=[
                        types.FunctionCall(id="call-1", name="save_note", args={"text": "Напомни завтра в 9 купить хлеб"})]))))
                else:
                    await answer(ws, turns, "")

    async def answer(ws, turn, said):
        pcm = tone(0.9)
        third = len(pcm) // 3 // 2 * 2
        words = ["Готово, ", "напомню завтра ", "в девять."] if turn == 1 else ["Всё ", "хорошо, ", "спасибо!"]
        for i in range(3):
            part = pcm[i * third:(i + 1) * third] if i < 2 else pcm[2 * third:]
            await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(
                model_turn=types.Content(role="model", parts=[types.Part(inline_data=types.Blob(mime_type="audio/pcm;rate=24000", data=part))])))))
            await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(output_transcription=types.Transcription(text=words[i])))))
            if i == 0:
                # spoken over? half a second of voice more while the answer has begun (a phone that passes the voice on
                # all the time — headphones) stops it, as the real server does
                spoken = 0
                end = asyncio.get_event_loop().time() + 1.5
                while spoken < 16000:
                    left = end - asyncio.get_event_loop().time()
                    if left <= 0:
                        break
                    try:
                        raw = await asyncio.wait_for(ws.recv(), left)
                    except asyncio.TimeoutError:
                        break
                    text = raw if isinstance(raw, str) else raw.decode()
                    log.write(text + "\n")
                    log.flush()
                    m = types.LiveClientMessage.model_validate_json(text)
                    if m.realtime_input is not None and m.realtime_input.audio is not None:
                        spoken += len(m.realtime_input.audio.data)
                if spoken >= 16000:
                    await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(interrupted=True))))
                    await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(turn_complete=True))))
                    return
        await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(generation_complete=True))))
        await ws.send(out(types.LiveServerMessage(server_content=types.LiveServerContent(turn_complete=True)),))

    async with ws_serve(handler, "127.0.0.1", port, process_request=process_request, max_size=2 ** 24):
        print("ready", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    if sys.argv[1] == "setup-ref":
        asyncio.run(setup_ref())
    else:
        asyncio.run(serve(int(sys.argv[2]), sys.argv[3]))
